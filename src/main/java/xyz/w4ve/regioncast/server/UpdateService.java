package xyz.w4ve.regioncast.server;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
	private final LedgerStore ledgerStore;
	private final QueueStore queueStore;
	private final BackupStore backups;
	private final SyncLedger ledger;

	/** Insertion ordered, so regions are copied in the order they were clicked. */
	private final Set<RegionKey> queue = new LinkedHashSet<>();
	/** Last scan of the source, kept so the map can be repainted without hitting the disk again. */
	private Map<Dim, Map<RegionKey, Long>> lastScan = new java.util.EnumMap<>(Dim.class);
	private String lastReport = "";
	private boolean updatePending;
	/** Snapshot waiting to be put back on the way out, or null when the pending job is an update. */
	private String restorePending;

	public UpdateService(RegionCastConfig config, Path sourceWorld, Path destinationWorld,
			Path ledgerFile, Path queueFile, Path backupDir) throws IOException {
		this.config = config;
		this.sourceScanner = new RegionScanner(sourceWorld);
		this.copier = new RegionCopier(sourceWorld, destinationWorld);
		this.ledgerStore = new LedgerStore(ledgerFile);
		this.ledger = ledgerStore.load();
		this.queueStore = new QueueStore(queueFile);
		this.backups = new BackupStore(backupDir, destinationWorld, config.backupKeep());
		queue.addAll(queueStore.load());
		LOGGER.info("Ledger loaded with {} regions from {}", ledger.size(), ledgerFile);
		if (!queue.isEmpty()) {
			LOGGER.info("Picked the queue back up with {} region(s) still in it", queue.size());
		}
		if (backups.enabled()) {
			LOGGER.info("Keeping the last {} update(s) undoable in {}", backups.keep(), backupDir);
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

	public synchronized void add(List<RegionKey> regions) {
		queue.addAll(regions);
		saveQueue();
	}

	public synchronized void remove(List<RegionKey> regions) {
		regions.forEach(queue::remove);
		saveQueue();
	}

	public synchronized void clear() {
		queue.clear();
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
		if (queue.isEmpty()) {
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
		if (queue.isEmpty()) {
			return "";
		}
		List<RegionKey> regions = List.copyOf(queue);
		LOGGER.info("Copying {} region(s) from {}", regions.size(), copier.sourceWorld());

		BackupStore.Session snapshot = openSnapshot();
		boolean savingHeld = holdSourceSaves();
		int ok = 0;
		int failed = 0;
		List<CopyResult> results = new ArrayList<>(regions.size());
		try {
			for (RegionKey region : regions) {
				// Backup first. A region whose old files could not be set aside is left alone:
				// the whole point of this batch is that it can be taken back.
				if (!capture(snapshot, region)) {
					failed++;
					continue;
				}
				CopyResult result = copier.copy(region);
				results.add(result);
				if (result.ok()) {
					ok++;
					if (result.sourceModifiedMillis() > 0) {
						ledger.record(region, result.sourceModifiedMillis());
					} else {
						// The source no longer has it, so we no longer track it.
						ledger.forget(region);
					}
					LOGGER.info("  {} copied {} deleted {}", region, result.filesCopied(), result.filesDeleted());
				} else {
					failed++;
					LOGGER.error("  {} FAILED: {}", region, result.error());
				}
			}
		} finally {
			if (savingHeld) {
				releaseSourceSaves();
			}
			closeSnapshot(snapshot);
		}

		saveLedger();

		queue.clear();
		saveQueue();
		lastReport = failed == 0
				? ok + " region(s) updated"
				: ok + " updated, " + failed + " failed, see the log";
		LOGGER.info("RegionCast: {}", lastReport);
		return lastReport;
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
		}
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
			snapshot.capture(region, ledger.lastCopiedSourceTime(region));
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
