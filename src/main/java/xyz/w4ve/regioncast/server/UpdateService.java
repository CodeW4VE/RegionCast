package xyz.w4ve.regioncast.server;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.w4ve.regioncast.anvil.ChunkCopier;
import xyz.w4ve.regioncast.model.ChunkKey;
import xyz.w4ve.regioncast.model.ChunkMask;
import xyz.w4ve.regioncast.model.Dim;
import xyz.w4ve.regioncast.model.RegionKey;
import xyz.w4ve.regioncast.model.RegionStatus;
import xyz.w4ve.regioncast.model.SyncLedger;
import xyz.w4ve.regioncast.net.RegionStatePayload;
import xyz.w4ve.regioncast.sync.BackupStore;
import xyz.w4ve.regioncast.sync.CopyResult;
import xyz.w4ve.regioncast.sync.LedgerStore;
import xyz.w4ve.regioncast.sync.QueueStore;
import xyz.w4ve.regioncast.sync.RconClient;
import xyz.w4ve.regioncast.sync.RegionCopier;
import xyz.w4ve.regioncast.sync.RegionScanner;

/**
 * Holds the queue, works out what is out of date, and does the copying at the one moment it is
 * safe to.
 *
 * <p>The timing is the whole trick. A mod lives inside the Minecraft process, so it cannot stop
 * the server, copy, and start it again the way an external wrapper can: it would be killing
 * itself halfway through the job. What it can do is copy during shutdown. By the time
 * {@code SERVER_STOPPED} fires the world has been saved and every region file has been closed,
 * but the JVM is still alive and this code still runs. That window is when {@link #runPending}
 * happens, which is why the copy never has to fight the server for a file.
 */
public final class UpdateService {
	/**
	 * Printed on the last line before we exit. A tiny MCDR plugin watches for it and starts the
	 * server back up. Anything that restarts on exit works just as well.
	 */
	public static final String RESTART_MARKER = "[RegionCast] RESTART_REQUESTED";

	private static final Logger LOGGER = LoggerFactory.getLogger("RegionCast");

	private final RegionCastConfig config;
	private final RegionScanner sourceScanner;
	private final RegionCopier copier;
	private final ChunkCopier chunkCopier;
	private final LedgerStore<RegionKey> ledgerStore;
	private final LedgerStore<ChunkKey> chunkLedgerStore;
	private final QueueStore<RegionKey> queueStore;
	private final QueueStore<ChunkKey> chunkQueueStore;
	private final BackupStore backups;
	private final SyncLedger<RegionKey> ledger;
	/** Chunks pulled across on their own, against the source region file's time at the time. */
	private final SyncLedger<ChunkKey> chunkLedger;

	/** Insertion ordered, so regions are copied in the order they were clicked. */
	private final Set<RegionKey> queue = new LinkedHashSet<>();
	/** Chunks picked one by one, for when a whole region is more than you wanted. */
	private final Set<ChunkKey> chunkQueue = new LinkedHashSet<>();
	/** Last scan of the source, kept so the map can be repainted without hitting the disk again. */
	private Map<Dim, Map<RegionKey, Long>> lastScan = new java.util.EnumMap<>(Dim.class);
	private String lastReport = "";
	private boolean updatePending;
	/** Snapshot waiting to be put back on the way out, or null when the pending job is an update. */
	private String restorePending;

	public UpdateService(RegionCastConfig config, RegionCastPaths paths) throws IOException {
		this.config = config;
		this.sourceScanner = new RegionScanner(paths.sourceWorld());
		this.copier = new RegionCopier(paths.sourceWorld(), paths.destinationWorld());
		this.chunkCopier = new ChunkCopier(paths.sourceWorld(), paths.destinationWorld(),
				paths.workDir());
		this.ledgerStore = LedgerStore.forRegions(paths.ledgerFile());
		this.ledger = ledgerStore.load();
		this.chunkLedgerStore = LedgerStore.forChunks(paths.chunkLedgerFile());
		this.chunkLedger = chunkLedgerStore.load();
		this.queueStore = QueueStore.forRegions(paths.queueFile());
		this.chunkQueueStore = QueueStore.forChunks(paths.chunkQueueFile());
		this.backups = new BackupStore(paths.backupDir(), paths.destinationWorld(),
				config.backupKeep());
		queue.addAll(queueStore.load());
		chunkQueue.addAll(chunkQueueStore.load());
		LOGGER.info("Ledger loaded with {} region(s) and {} loose chunk(s)",
				ledger.size(), chunkLedger.size());
		if (!queue.isEmpty() || !chunkQueue.isEmpty()) {
			LOGGER.info("Picked the queue back up with {} region(s) and {} chunk(s) still in it",
					queue.size(), chunkQueue.size());
		}
		if (backups.enabled()) {
			LOGGER.info("Keeping the last {} update(s) undoable in {}",
					backups.keep(), paths.backupDir());
		} else {
			LOGGER.warn("Backups are off (backup_keep=0): an update cannot be taken back");
		}
	}

	public RegionCastConfig config() {
		return config;
	}

	public Path sourceWorld() {
		return copier.sourceWorld();
	}

	public boolean sourceAvailable() {
		return java.nio.file.Files.isDirectory(copier.sourceWorld());
	}

	/** Re-reads the source and recomputes what the map should paint. */
	public synchronized List<RegionStatePayload.Entry> refresh() throws IOException {
		Map<Dim, Map<RegionKey, Long>> scan = new java.util.EnumMap<>(Dim.class);
		for (Dim dim : Dim.values()) {
			scan.put(dim, sourceScanner.scan(dim));
		}
		lastScan = scan;
		return buildEntries();
	}

	/** The current colours, from the last scan, without touching the disk. */
	public synchronized List<RegionStatePayload.Entry> entries() {
		return buildEntries();
	}

	private List<RegionStatePayload.Entry> buildEntries() {
		List<RegionStatePayload.Entry> entries = new ArrayList<>();
		Set<RegionKey> seen = new LinkedHashSet<>();

		for (Map.Entry<Dim, Map<RegionKey, Long>> perDim : lastScan.entrySet()) {
			for (Map.Entry<RegionKey, Long> found : perDim.getValue().entrySet()) {
				RegionKey region = found.getKey();
				seen.add(region);
				RegionStatus status = ledger.status(region, found.getValue());
				// In sync is the overwhelming majority and means "nothing to say", so it does not
				// travel. Roughly 60 entries instead of 1800 on a real world.
				if (status != RegionStatus.IN_SYNC) {
					entries.add(new RegionStatePayload.Entry(region, status));
				}
			}
		}

		// Regions we copied once that the source no longer has. They are not in the scan, so
		// they have to be picked up from the ledger side.
		for (RegionKey known : ledger.known()) {
			if (!seen.contains(known)) {
				entries.add(new RegionStatePayload.Entry(known, RegionStatus.GONE));
			}
		}
		return entries;
	}

	/**
	 * The regions holding chunks that were pulled across on their own, as two bit masks each.
	 *
	 * <p>Masks rather than a list of chunks: a region is 128 bytes to describe completely however
	 * many of its 1024 chunks are involved, and the map only needs to know which squares to paint.
	 */
	public synchronized List<RegionStatePayload.Partial> partials() {
		Map<RegionKey, ChunkMask> copied = new LinkedHashMap<>();
		Map<RegionKey, ChunkMask> stale = new LinkedHashMap<>();

		for (Map.Entry<ChunkKey, Long> entry : chunkLedger.asMap().entrySet()) {
			RegionKey region = entry.getKey().region();
			copied.computeIfAbsent(region, key -> new ChunkMask()).set(entry.getKey());
			if (sourceTimeOf(region) > entry.getValue()) {
				stale.computeIfAbsent(region, key -> new ChunkMask()).set(entry.getKey());
			}
		}

		List<RegionStatePayload.Partial> partials = new ArrayList<>(copied.size());
		for (Map.Entry<RegionKey, ChunkMask> entry : copied.entrySet()) {
			partials.add(new RegionStatePayload.Partial(entry.getKey(), entry.getValue(),
					stale.getOrDefault(entry.getKey(), new ChunkMask())));
		}
		return partials;
	}

	/** Source timestamp of a region from the last scan, without going back to the disk. */
	private long sourceTimeOf(RegionKey region) {
		Map<RegionKey, Long> perDim = lastScan.get(region.dim());
		return perDim == null ? 0L : perDim.getOrDefault(region, 0L);
	}

	public synchronized List<RegionKey> queued() {
		return List.copyOf(queue);
	}

	public synchronized String lastReport() {
		return lastReport;
	}

	public synchronized boolean isUpdatePending() {
		return updatePending;
	}

	/** True when the shutdown has a job waiting for it, whichever kind. */
	public synchronized boolean hasPendingWork() {
		return updatePending || restorePending != null;
	}

	public BackupStore backups() {
		return backups;
	}

	public List<BackupStore.Snapshot> snapshots() {
		return backups.list();
	}

	public synchronized List<ChunkKey> queuedChunks() {
		return List.copyOf(chunkQueue);
	}

	public synchronized void add(List<RegionKey> regions) {
		queue.addAll(regions);
		// A whole region covers every chunk in it, so holding on to the loose ones would only make
		// the same work happen twice, slowly, right after it already happened.
		regions.forEach(region -> chunkQueue.removeIf(chunk -> chunk.region().equals(region)));
		saveQueue();
	}

	public synchronized void remove(List<RegionKey> regions) {
		regions.forEach(queue::remove);
		saveQueue();
	}

	public synchronized void addChunks(List<ChunkKey> chunks) {
		for (ChunkKey chunk : chunks) {
			// Already covered by its whole region: picking it changes nothing, so say so by
			// leaving the queue alone rather than adding something that will be skipped.
			if (!queue.contains(chunk.region())) {
				chunkQueue.add(chunk);
			}
		}
		saveQueue();
	}

	public synchronized void removeChunks(List<ChunkKey> chunks) {
		chunks.forEach(chunkQueue::remove);
		saveQueue();
	}

	public synchronized void clear() {
		queue.clear();
		chunkQueue.clear();
		saveQueue();
	}

	/**
	 * Writes the queue out after every change.
	 *
	 * <p>A failure here is logged and swallowed: not being able to remember the queue is annoying,
	 * but it is no reason to stop someone from picking regions and copying them right now.
	 */
	private void saveQueue() {
		try {
			queueStore.save(queue);
			chunkQueueStore.save(chunkQueue);
		} catch (IOException e) {
			LOGGER.error("Could not save the queue, it will not survive a restart", e);
		}
	}

	/**
	 * Checks everything that can be checked while the server is still up.
	 *
	 * <p>Called before shutting anything down, so a wrong RCON password is a message in chat
	 * rather than a server that went away and came back having done nothing.
	 *
	 * @return null when the update can go ahead, or the reason it cannot
	 */
	public synchronized String whyNotReady() {
		if (queue.isEmpty() && chunkQueue.isEmpty()) {
			return "the queue is empty";
		}
		if (!sourceAvailable()) {
			return "the source world is not there: " + copier.sourceWorld();
		}
		if (config.hasRcon()) {
			try (RconClient rcon = RconClient.connect(
					config.rconHost(), config.rconPort(), config.rconPassword(),
					config.rconTimeoutMillis())) {
				rcon.command("list");
			} catch (IOException e) {
				return "cannot reach the source server over RCON: " + e.getMessage();
			}
		}
		return null;
	}

	/** Arms the update. The copying itself happens on the way out, in {@link #runPending}. */
	public synchronized void arm() {
		updatePending = true;
		restorePending = null;
	}

	/**
	 * Arms a restore. Like an update it can only happen with the world closed, so it takes the
	 * same route out: arm now, do the work in {@link #runPending} during shutdown.
	 *
	 * @return null when it can go ahead, or the reason it cannot
	 */
	public synchronized String armRestore(String snapshotId) {
		boolean exists = backups.list().stream().anyMatch(s -> s.id().equals(snapshotId));
		if (!exists) {
			return "there is no backup called " + snapshotId;
		}
		restorePending = snapshotId;
		updatePending = false;
		return null;
	}

	public synchronized String restorePending() {
		return restorePending;
	}

	/**
	 * Does whatever was armed. Only safe to call once the world is closed.
	 *
	 * @return a one line summary, also stored as the last report
	 */
	public synchronized String runPending() {
		if (restorePending != null) {
			return runRestore();
		}
		return runUpdate();
	}

	private String runUpdate() {
		updatePending = false;
		if (queue.isEmpty() && chunkQueue.isEmpty()) {
			return "";
		}
		List<RegionKey> regions = List.copyOf(queue);
		Map<RegionKey, Set<ChunkKey>> looseChunks = ChunkCopier.byRegion(chunkQueue);
		LOGGER.info("Copying {} region(s) and {} chunk(s) from {}",
				regions.size(), chunkQueue.size(), copier.sourceWorld());

		BackupStore.Session snapshot = openSnapshot();
		boolean savingHeld = holdSourceSaves();
		int ok = 0;
		int failed = 0;
		int chunksCopied = 0;
		try {
			for (RegionKey region : regions) {
				// Backup first. A region whose old files could not be set aside is left alone:
				// the whole point of this batch is that it can be taken back.
				if (!capture(snapshot, region)) {
					failed++;
					continue;
				}
				CopyResult result = copier.copy(region);
				if (result.ok()) {
					ok++;
					if (result.sourceModifiedMillis() > 0) {
						ledger.record(region, result.sourceModifiedMillis());
					} else {
						// The source no longer has it, so we no longer track it.
						ledger.forget(region);
					}
					// The whole file just landed, so nothing in it is a loose chunk any more.
					forgetChunksOf(region);
					LOGGER.info("  {} copied {} deleted {}", region, result.filesCopied(), result.filesDeleted());
				} else {
					failed++;
					LOGGER.error("  {} FAILED: {}", region, result.error());
				}
			}

			for (Map.Entry<RegionKey, Set<ChunkKey>> entry : looseChunks.entrySet()) {
				RegionKey region = entry.getKey();
				if (queue.contains(region)) {
					// Already came across whole in the pass above.
					continue;
				}
				if (!capture(snapshot, region)) {
					failed++;
					continue;
				}
				chunksCopied += copyChunks(region, entry.getValue()) ? entry.getValue().size() : 0;
			}
		} finally {
			if (savingHeld) {
				releaseSourceSaves();
			}
			closeSnapshot(snapshot);
		}

		saveLedger();

		queue.clear();
		chunkQueue.clear();
		saveQueue();
		lastReport = describe(ok, chunksCopied, failed);
		LOGGER.info("RegionCast: {}", lastReport);
		return lastReport;
	}

	/**
	 * Pulls the picked chunks of one region across.
	 *
	 * <p>Every chunk is recorded against the source region file's timestamp, because that is the
	 * only timestamp the source has. See {@link SyncLedger}.
	 *
	 * @return false when nothing could be copied at all
	 */
	private boolean copyChunks(RegionKey region, Set<ChunkKey> chunks) {
		long sourceModified = sourceRegionTime(region);
		ChunkCopier.Result result = chunkCopier.copy(region, chunks);

		for (ChunkKey chunk : chunks) {
			if (sourceModified > 0) {
				chunkLedger.record(chunk, sourceModified);
			} else {
				// The source lost the whole region, so those chunks were removed here too.
				chunkLedger.forget(chunk);
			}
		}
		for (String error : result.errors()) {
			LOGGER.error("  {} chunk FAILED: {}", region, error);
		}
		LOGGER.info("  {} chunks copied {} cleared {}",
				region, result.chunksCopied(), result.chunksDeleted());
		return result.chunksCopied() > 0 || result.chunksDeleted() > 0;
	}

	/** Last modified time of the source's terrain file for this region, or 0 when it has none. */
	private long sourceRegionTime(RegionKey region) {
		try {
			return sourceScanner.modifiedTime(region);
		} catch (IOException e) {
			return 0L;
		}
	}

	/** The loose chunks recorded inside one region, for the backup manifest. */
	private Map<ChunkKey, Long> chunksRecordedIn(RegionKey region) {
		Map<ChunkKey, Long> inside = new java.util.LinkedHashMap<>();
		for (Map.Entry<ChunkKey, Long> entry : chunkLedger.asMap().entrySet()) {
			if (entry.getKey().region().equals(region)) {
				inside.put(entry.getKey(), entry.getValue());
			}
		}
		return inside;
	}

	/** Drops every loose chunk record inside a region, for when the whole file has been copied. */
	private void forgetChunksOf(RegionKey region) {
		List<ChunkKey> inside = chunkLedger.known().stream()
				.filter(chunk -> chunk.region().equals(region))
				.toList();
		inside.forEach(chunkLedger::forget);
	}

	private static String describe(int regions, int chunks, int failed) {
		StringBuilder text = new StringBuilder();
		if (regions > 0 || chunks == 0) {
			text.append(regions).append(" region(s)");
		}
		if (chunks > 0) {
			if (!text.isEmpty()) {
				text.append(" and ");
			}
			text.append(chunks).append(" chunk(s)");
		}
		text.append(" updated");
		if (failed > 0) {
			text.append(", ").append(failed).append(" failed, see the log");
		}
		return text.toString();
	}

	/**
	 * Puts a snapshot back and rewinds the ledger with it.
	 *
	 * <p>The ledger half matters as much as the files: restored regions have to go back to
	 * reading as out of date against the source, or the map would show them green while holding
	 * older terrain than the source has.
	 */
	private String runRestore() {
		String id = restorePending;
		restorePending = null;
		LOGGER.info("Restoring backup {}", id);

		BackupStore.RestoreResult result = backups.restore(id);
		for (Map.Entry<RegionKey, Long> entry : result.ledgerBefore().entrySet()) {
			if (entry.getValue() > 0) {
				ledger.record(entry.getKey(), entry.getValue());
			} else {
				// It was never copied before that update, so it goes back to never copied.
				ledger.forget(entry.getKey());
			}
			// Every loose chunk inside it goes as well, and then the ones that were there before
			// that update come back below. What is left is exactly what the restored file holds.
			forgetChunksOf(entry.getKey());
		}
		result.chunkLedgerBefore().forEach(chunkLedger::record);
		saveLedger();

		lastReport = result.ok()
				? "restored " + result.regions() + " region(s) from backup " + id
				: "restored " + result.regions() + " region(s) from " + id + " with "
						+ result.errors().size() + " problem(s), see the log";
		for (String error : result.errors()) {
			LOGGER.error("  restore: {}", error);
		}
		LOGGER.info("RegionCast: {} ({} file(s) put back, {} removed)",
				lastReport, result.filesRestored(), result.filesDeleted());
		return lastReport;
	}

	/** Opens a snapshot for this batch, or returns null when backups are off or unwritable. */
	private BackupStore.Session openSnapshot() {
		try {
			return backups.begin();
		} catch (IOException e) {
			LOGGER.error("Could not open a backup folder, this update will NOT be undoable", e);
			return null;
		}
	}

	/** @return false when this region must be skipped because its backup failed */
	private boolean capture(BackupStore.Session snapshot, RegionKey region) {
		if (snapshot == null) {
			return true;
		}
		try {
			snapshot.capture(region, ledger.lastCopiedSourceTime(region), chunksRecordedIn(region));
			return true;
		} catch (IOException e) {
			LOGGER.error("  {} SKIPPED: could not back it up first: {}", region, e.getMessage());
			return false;
		}
	}

	private void closeSnapshot(BackupStore.Session snapshot) {
		if (snapshot == null) {
			return;
		}
		try {
			BackupStore.Snapshot written = snapshot.commit();
			if (written != null) {
				LOGGER.info("Backup {} holds {} region(s), {}",
						written.id(), written.regions(), written.sizeText());
			}
		} catch (IOException e) {
			LOGGER.error("Could not finish the backup, it may not be restorable", e);
		}
	}

	private void saveLedger() {
		try {
			ledgerStore.save(ledger);
			chunkLedgerStore.save(chunkLedger);
		} catch (IOException e) {
			LOGGER.error("Could not save the ledger, regions will look out of date again", e);
		}
	}

	/** Asks the source to finish writing and stop, so nothing is copied mid-save. */
	private boolean holdSourceSaves() {
		if (!config.hasRcon()) {
			LOGGER.warn("No RCON configured: copying while the source may be writing");
			return false;
		}
		try (RconClient rcon = RconClient.connect(
				config.rconHost(), config.rconPort(), config.rconPassword(),
				config.rconTimeoutMillis())) {
			rcon.command("save-off");
			rcon.command("save-all flush");
			LOGGER.info("Source saving held");
			return true;
		} catch (IOException e) {
			LOGGER.error("Could not hold the source's saves, copying anyway: {}", e.getMessage());
			return false;
		}
	}

	/**
	 * Turns saving back on at the source.
	 *
	 * <p>Runs in a finally block and retries, because leaving another server with saving off is
	 * far worse than anything this mod was trying to do.
	 */
	private void releaseSourceSaves() {
		for (int attempt = 1; attempt <= 3; attempt++) {
			try (RconClient rcon = RconClient.connect(
					config.rconHost(), config.rconPort(), config.rconPassword(),
					config.rconTimeoutMillis())) {
				rcon.command("save-on");
				LOGGER.info("Source saving restored");
				return;
			} catch (IOException e) {
				LOGGER.error("save-on attempt {} of 3 failed: {}", attempt, e.getMessage());
			}
		}
		LOGGER.error("COULD NOT RESTORE SAVING ON THE SOURCE SERVER. Run save-on there by hand.");
	}

	public synchronized SyncLedger ledger() {
		return ledger;
	}

	public synchronized Set<RegionKey> queueView() {
		return Collections.unmodifiableSet(queue);
	}
}
