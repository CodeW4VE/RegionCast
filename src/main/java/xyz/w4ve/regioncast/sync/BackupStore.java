package xyz.w4ve.regioncast.sync;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import xyz.w4ve.regioncast.model.ChunkKey;
import xyz.w4ve.regioncast.model.RegionKey;
import xyz.w4ve.regioncast.model.RegionPaths;

/**
 * Keeps the last few updates undoable.
 *
 * <p>An update overwrites region files in this world with somebody else's. That is the entire
 * point of the mod, and also the one thing about it that cannot be taken back: whatever was built
 * here inside those 512 by 512 blocks is gone the moment the copy lands. So before each update
 * writes anything, the files it is about to replace are set aside as a snapshot, and only the
 * newest few are kept.
 *
 * <p>A snapshot is one update, not one region. That matches how the map is used: you click a
 * handful of regions, press Update once, and later decide that whole batch was a mistake.
 *
 * <p>Each snapshot also remembers what the ledger said about every region it touched, because
 * putting the files back is only half of an undo. Without the ledger going back too, restored
 * regions would keep claiming they were up to date with a source they no longer match.
 *
 * <p>Restoring does not take a snapshot of what it overwrites. What a restore throws away is the
 * copy RegionCast pulled from the source, and that is the one thing in this whole arrangement
 * that can always be fetched again by pressing Update.
 */
public final class BackupStore {
	/** Manifest file inside a snapshot folder. Plain text, on purpose. See {@link LedgerStore}. */
	static final String MANIFEST = "manifest.txt";
	/** Sub folder holding the saved files, laid out exactly like the world. */
	static final String FILES = "files";

	private static final String HEADER = "# RegionCast backup. Restore it with /rc restore";
	private static final DateTimeFormatter ID_FORMAT =
			DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());

	private final Path root;
	private final Path world;
	private final int keep;

	/**
	 * @param root where snapshots live, kept outside the world folder so Minecraft never sees them
	 * @param world the world being protected, the place files are taken from and put back into
	 * @param keep how many snapshots to hold on to, or zero to not back anything up at all
	 */
	public BackupStore(Path root, Path world, int keep) {
		this.root = root;
		this.world = world;
		this.keep = keep;
	}

	public Path root() {
		return root;
	}

	public boolean enabled() {
		return keep > 0;
	}

	public int keep() {
		return keep;
	}

	/** One backed up update, as the list and the buttons see it. */
	public record Snapshot(String id, long takenMillis, int regions, int files, long bytes) {
		/** Short label for a button: {@code 3 Aug 14:15}. */
		public String when() {
			return DateTimeFormatter.ofPattern("d MMM HH:mm")
					.withZone(ZoneId.systemDefault())
					.format(Instant.ofEpochMilli(takenMillis));
		}

		public String sizeText() {
			return BackupStore.sizeText(bytes);
		}
	}

	/** Sizes for humans. Region files are megabytes, so nothing smaller is worth a unit. */
	public static String sizeText(long bytes) {
		if (bytes >= 1024L * 1024L * 1024L) {
			return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
		}
		return String.format("%.0f MB", Math.ceil(bytes / (1024.0 * 1024.0)));
	}

	/**
	 * What a restore did, for the log line and the chat message.
	 *
	 * @param ledgerBefore what the region ledger said about each region before the update
	 * @param chunkLedgerBefore the loose chunks recorded inside those regions before the update.
	 *     Chunks pulled in by earlier updates are in the restored files and belong in the ledger;
	 *     the ones this update added do not, and are simply absent from here.
	 */
	public record RestoreResult(String id, int regions, int filesRestored, int filesDeleted,
			Map<RegionKey, Long> ledgerBefore, Map<ChunkKey, Long> chunkLedgerBefore,
			List<String> errors) {
		public boolean ok() {
			return errors.isEmpty();
		}
	}

	/**
	 * Snapshots on disk, newest first.
	 *
	 * <p>Never throws: a broken or half written snapshot folder is skipped, because the list is
	 * drawn in a GUI and an unreadable backup should cost you that one row, not the screen.
	 */
	public List<Snapshot> list() {
		if (!Files.isDirectory(root)) {
			return List.of();
		}
		List<Snapshot> snapshots = new ArrayList<>();
		try (Stream<Path> dirs = Files.list(root)) {
			for (Path dir : dirs.filter(Files::isDirectory).toList()) {
				Snapshot snapshot = read(dir);
				if (snapshot != null) {
					snapshots.add(snapshot);
				}
			}
		} catch (IOException | UncheckedIOException e) {
			return snapshots;
		}
		snapshots.sort(Comparator.comparingLong(Snapshot::takenMillis).reversed());
		return snapshots;
	}

	/** The newest snapshot, or null when there is none. */
	public Snapshot latest() {
		List<Snapshot> all = list();
		return all.isEmpty() ? null : all.get(0);
	}

	private Snapshot read(Path dir) {
		Path manifest = dir.resolve(MANIFEST);
		if (!Files.isRegularFile(manifest)) {
			return null;
		}
		long taken = 0L;
		int regions = 0;
		int files = 0;
		long bytes = 0L;
		try {
			for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
				String[] parts = line.trim().split("\\s+");
				switch (parts[0]) {
					case "taken" -> taken = parseLong(parts.length > 1 ? parts[1] : "", 0L);
					case "r" -> regions++;
					case "+" -> {
						files++;
						bytes += parseLong(parts.length > 2 ? parts[2] : "", 0L);
					}
					default -> {
					}
				}
			}
		} catch (IOException e) {
			return null;
		}
		if (taken == 0L) {
			// Fall back to the folder's own timestamp rather than dropping the snapshot: the files
			// are still there and still restorable, which is all that really matters.
			try {
				taken = Files.getLastModifiedTime(dir).toMillis();
			} catch (IOException e) {
				return null;
			}
		}
		return new Snapshot(dir.getFileName().toString(), taken, regions, files, bytes);
	}

	private static long parseLong(String text, long fallback) {
		try {
			return Long.parseLong(text);
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	/**
	 * Opens a snapshot to write into. Call {@link Session#capture} per region, then
	 * {@link Session#commit}.
	 *
	 * @return null when backups are switched off, so callers can treat "no backup wanted" and
	 *     "nothing to write" the same way
	 */
	public Session begin() throws IOException {
		if (!enabled()) {
			return null;
		}
		Instant now = Instant.now();
		Path dir = uniqueDir(now);
		Files.createDirectories(dir);
		return new Session(dir, now);
	}

	/** A folder for right now, with a counter on the end if a snapshot beat us to this second. */
	private Path uniqueDir(Instant now) {
		String base = ID_FORMAT.format(now);
		Path dir = root.resolve(base);
		for (int n = 2; Files.exists(dir) && n < 100; n++) {
			dir = root.resolve(base + "-" + n);
		}
		return dir;
	}

	/** A snapshot being written, one region at a time. */
	public final class Session {
		private final Path dir;
		private final List<String> lines = new ArrayList<>();
		private int regions;
		private int files;

		private Session(Path dir, Instant taken) {
			this.dir = dir;
			lines.add(HEADER);
			lines.add("taken " + taken.toEpochMilli());
		}

		public String id() {
			return dir.getFileName().toString();
		}

		public int files() {
			return files;
		}

		/**
		 * Sets aside everything this region has here right now, before it gets overwritten.
		 *
		 * @param ledgerBefore what the ledger said about this region, or 0 when it did not know it
		 * @param chunksBefore the loose chunks already recorded inside this region, so a restore
		 *     can tell them apart from the ones this update is about to add
		 */
		public void capture(RegionKey region, long ledgerBefore, Map<ChunkKey, Long> chunksBefore)
				throws IOException {
			List<String> pending = new ArrayList<>();
			for (String relative : RegionPaths.of(region)) {
				Path from = world.resolve(relative);
				if (Files.isRegularFile(from)) {
					FileTime stamp = Files.getLastModifiedTime(from);
					AtomicCopy.copy(from, dir.resolve(FILES).resolve(relative), stamp);
					pending.add("+ " + stamp.toMillis() + " " + Files.size(from) + " " + relative);
					files++;
				} else {
					// Written down as well: putting this region back has to remove a file that did
					// not exist before the update, not just restore the ones that did.
					pending.add("- " + relative);
				}
			}
			lines.add("r " + RegionText.format(region) + " " + ledgerBefore);
			for (Map.Entry<ChunkKey, Long> chunk : chunksBefore.entrySet()) {
				lines.add("c " + RegionText.formatChunk(chunk.getKey()) + " " + chunk.getValue());
			}
			lines.addAll(pending);
			regions++;
		}

		/** Convenience for the common case of a region with no loose chunks recorded in it. */
		public void capture(RegionKey region, long ledgerBefore) throws IOException {
			capture(region, ledgerBefore, Map.of());
		}

		/**
		 * Finishes the snapshot and drops the oldest ones over the limit.
		 *
		 * <p>A snapshot with no regions in it is deleted instead of written: an update that copied
		 * nothing has nothing to undo.
		 */
		public Snapshot commit() throws IOException {
			if (regions == 0) {
				abort();
				return null;
			}
			Files.write(dir.resolve(MANIFEST), lines, StandardCharsets.UTF_8);
			prune();
			return read(dir);
		}

		/** Throws the half written snapshot away, for when the update never happened. */
		public void abort() {
			deleteTree(dir);
		}
	}

	/** Keeps the newest {@code keep} snapshots and deletes the rest. */
	void prune() {
		List<Snapshot> all = list();
		for (int i = keep; i < all.size(); i++) {
			deleteTree(root.resolve(all.get(i).id()));
		}
	}

	/**
	 * Puts one snapshot back into the world.
	 *
	 * <p>Same rule as copying: only safe with the server stopped, because these files are open
	 * while it runs.
	 *
	 * <p>Failures are collected rather than thrown. Half a world put back is better than stopping
	 * at the first unreadable file and leaving the rest of the batch overwritten.
	 */
	public RestoreResult restore(String id) {
		Path dir = root.resolve(id);
		Path manifest = dir.resolve(MANIFEST);
		List<String> errors = new ArrayList<>();
		Map<RegionKey, Long> ledgerBefore = new LinkedHashMap<>();
		Map<ChunkKey, Long> chunkLedgerBefore = new LinkedHashMap<>();
		int restored = 0;
		int removed = 0;

		List<String> lines;
		try {
			lines = Files.readAllLines(manifest, StandardCharsets.UTF_8);
		} catch (IOException e) {
			return new RestoreResult(id, 0, 0, 0, Map.of(), Map.of(),
					List.of("cannot read " + manifest + ": " + e.getMessage()));
		}

		for (String line : lines) {
			String[] parts = line.trim().split("\\s+");
			try {
				switch (parts[0]) {
					case "r" -> {
						RegionKey region = RegionText.parse(new String[] {parts[1], parts[2], parts[3]});
						if (region != null) {
							ledgerBefore.put(region, parseLong(parts.length > 4 ? parts[4] : "", 0L));
						}
					}
					case "c" -> {
						ChunkKey chunk = RegionText.parseChunk(new String[] {parts[1], parts[2], parts[3]});
						if (chunk != null) {
							chunkLedgerBefore.put(chunk, parseLong(parts.length > 4 ? parts[4] : "", 0L));
						}
					}
					case "+" -> {
						String relative = parts[3];
						Path from = dir.resolve(FILES).resolve(relative);
						if (!Files.isRegularFile(from)) {
							errors.add("missing from the backup: " + relative);
							continue;
						}
						AtomicCopy.copy(from, world.resolve(relative),
								FileTime.fromMillis(parseLong(parts[1], Files.getLastModifiedTime(from).toMillis())));
						restored++;
					}
					case "-" -> {
						if (Files.deleteIfExists(world.resolve(parts[1]))) {
							removed++;
						}
					}
					default -> {
					}
				}
			} catch (IOException | ArrayIndexOutOfBoundsException e) {
				errors.add(line.trim() + ": " + e);
			}
		}
		return new RestoreResult(id, ledgerBefore.size(), restored, removed, ledgerBefore,
				chunkLedgerBefore, errors);
	}

	/** Removes a snapshot by hand, for when the disk matters more than the safety net. */
	public boolean delete(String id) {
		Path dir = root.resolve(id);
		if (!Files.isDirectory(dir)) {
			return false;
		}
		deleteTree(dir);
		return true;
	}

	/** Total size of every snapshot, for telling somebody what this is costing them. */
	public long totalBytes() {
		long total = 0L;
		for (Snapshot snapshot : list()) {
			total += snapshot.bytes();
		}
		return total;
	}

	private static void deleteTree(Path dir) {
		if (!Files.exists(dir)) {
			return;
		}
		try (Stream<Path> tree = Files.walk(dir)) {
			tree.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException e) {
					// Leaving a stray file behind is untidy, not dangerous.
				}
			});
		} catch (IOException | UncheckedIOException e) {
			// Same: a snapshot that will not delete is a disk space problem for another day.
		}
	}
}
