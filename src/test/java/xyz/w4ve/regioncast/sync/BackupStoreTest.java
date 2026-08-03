package xyz.w4ve.regioncast.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.w4ve.regioncast.model.Dim;
import xyz.w4ve.regioncast.model.RegionKey;
import xyz.w4ve.regioncast.model.RegionPaths;

class BackupStoreTest {
	private static final RegionKey REGION = new RegionKey(Dim.OVERWORLD, 1, -2);
	private static final RegionKey OTHER = new RegionKey(Dim.NETHER, 0, 0);

	@TempDir
	Path root;

	private Path world;
	private Path backupRoot;
	private BackupStore backups;

	@BeforeEach
	void setUp() throws IOException {
		world = Files.createDirectories(root.resolve("mirror/world"));
		backupRoot = root.resolve("mirror/regioncast-backups");
		backups = new BackupStore(backupRoot, world, 3);
	}

	private void write(String relative, String content, long millis) throws IOException {
		Path file = world.resolve(relative);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, StandardCharsets.UTF_8);
		Files.setLastModifiedTime(file, FileTime.fromMillis(millis));
	}

	private String read(String relative) throws IOException {
		return Files.readString(world.resolve(relative), StandardCharsets.UTF_8);
	}

	/** Backs up one region, then overwrites it the way an update would. */
	private String captureAndOverwrite(RegionKey region, long ledgerBefore, String newContent)
			throws IOException {
		BackupStore.Session session = backups.begin();
		session.capture(region, ledgerBefore);
		for (String relative : RegionPaths.of(region)) {
			write(relative, newContent + " " + relative, 9_000L);
		}
		BackupStore.Snapshot snapshot = session.commit();
		return snapshot == null ? null : snapshot.id();
	}

	@Test
	@DisplayName("what the update replaced comes back byte for byte")
	void restoresContent() throws IOException {
		for (String relative : RegionPaths.of(REGION)) {
			write(relative, "ours " + relative, 5_000L);
		}

		String id = captureAndOverwrite(REGION, 1_234L, "from the smp");
		for (String relative : RegionPaths.of(REGION)) {
			assertTrue(read(relative).startsWith("from the smp"), "the update should have landed");
		}

		BackupStore.RestoreResult result = backups.restore(id);

		assertTrue(result.ok(), result.errors().toString());
		assertEquals(3, result.filesRestored());
		for (String relative : RegionPaths.of(REGION)) {
			assertEquals("ours " + relative, read(relative));
		}
	}

	@Test
	@DisplayName("the timestamp comes back too, so nothing looks freshly written")
	void restoresTimestamp() throws IOException {
		write(RegionPaths.of(REGION).get(0), "ours", 5_000L);

		String id = captureAndOverwrite(REGION, 0L, "theirs");
		backups.restore(id);

		assertEquals(5_000L,
				Files.getLastModifiedTime(world.resolve(RegionPaths.of(REGION).get(0))).toMillis());
	}

	@Test
	@DisplayName("a region we did not have before the update is removed again, not left behind")
	void restoreDeletesFilesThatDidNotExist() throws IOException {
		// Nothing here at all: the update is bringing this region in for the first time.
		String id = captureAndOverwrite(REGION, 0L, "brand new");
		for (String relative : RegionPaths.of(REGION)) {
			assertTrue(Files.isRegularFile(world.resolve(relative)));
		}

		BackupStore.RestoreResult result = backups.restore(id);

		assertEquals(3, result.filesDeleted());
		for (String relative : RegionPaths.of(REGION)) {
			assertFalse(Files.exists(world.resolve(relative)), relative + " should be gone again");
		}
	}

	@Test
	@DisplayName("the ledger state travels with the snapshot, so restored regions read as out of date")
	void remembersLedgerState() throws IOException {
		write(RegionPaths.of(REGION).get(0), "ours", 5_000L);
		String id = captureAndOverwrite(REGION, 4_321L, "theirs");

		BackupStore.RestoreResult result = backups.restore(id);

		assertEquals(4_321L, result.ledgerBefore().get(REGION));
	}

	@Test
	@DisplayName("a region the ledger never knew comes back as never copied, not as zero time")
	void neverCopiedStaysNeverCopied() throws IOException {
		String id = captureAndOverwrite(REGION, 0L, "theirs");

		assertEquals(0L, backups.restore(id).ledgerBefore().get(REGION));
	}

	@Test
	@DisplayName("one snapshot holds the whole batch")
	void oneSnapshotPerUpdate() throws IOException {
		write(RegionPaths.of(REGION).get(0), "one", 5_000L);
		write(RegionPaths.of(OTHER).get(0), "two", 5_000L);

		BackupStore.Session session = backups.begin();
		session.capture(REGION, 100L);
		session.capture(OTHER, 200L);
		BackupStore.Snapshot snapshot = session.commit();

		assertEquals(1, backups.list().size());
		assertEquals(2, snapshot.regions());
		assertEquals(2, snapshot.files());
	}

	@Test
	@DisplayName("only the newest three survive")
	void prunesToTheLimit() throws IOException {
		List<String> ids = new java.util.ArrayList<>();
		for (int i = 0; i < 5; i++) {
			write(RegionPaths.of(REGION).get(0), "version " + i, 5_000L + i);
			ids.add(captureAndOverwrite(REGION, i, "update " + i));
		}

		List<BackupStore.Snapshot> kept = backups.list();

		assertEquals(3, kept.size());
		assertEquals(List.of(ids.get(4), ids.get(3), ids.get(2)),
				kept.stream().map(BackupStore.Snapshot::id).toList());
	}

	@Test
	@DisplayName("newest first, whatever order the folders come off the disk in")
	void listedNewestFirst() throws IOException {
		write(RegionPaths.of(REGION).get(0), "a", 5_000L);
		String first = captureAndOverwrite(REGION, 1L, "b");
		String second = captureAndOverwrite(REGION, 2L, "c");

		List<BackupStore.Snapshot> all = backups.list();

		assertEquals(second, all.get(0).id());
		assertEquals(first, all.get(1).id());
		assertEquals(all.get(0).id(), backups.latest().id());
	}

	@Test
	@DisplayName("an update that touched nothing leaves no empty snapshot lying around")
	void emptySnapshotIsNotKept() throws IOException {
		BackupStore.Session session = backups.begin();

		assertNull(session.commit());
		assertTrue(backups.list().isEmpty());
	}

	@Test
	@DisplayName("backups off means no folder and no session")
	void disabledDoesNothing() throws IOException {
		BackupStore off = new BackupStore(backupRoot, world, 0);

		assertFalse(off.enabled());
		assertNull(off.begin());
		assertFalse(Files.exists(backupRoot));
	}

	@Test
	@DisplayName("snapshots live outside the world, where Minecraft will not walk into them")
	void staysOutOfTheWorldFolder() throws IOException {
		write(RegionPaths.of(REGION).get(0), "ours", 5_000L);
		captureAndOverwrite(REGION, 0L, "theirs");

		try (Stream<Path> tree = Files.walk(world)) {
			assertTrue(tree.noneMatch(path -> path.toString().contains("regioncast-backups")));
		}
	}

	@Test
	@DisplayName("no half written temporary files are left in the snapshot")
	void leavesNoTemporaries() throws IOException {
		write(RegionPaths.of(REGION).get(0), "ours", 5_000L);
		captureAndOverwrite(REGION, 0L, "theirs");

		try (Stream<Path> tree = Files.walk(backupRoot)) {
			assertTrue(tree.noneMatch(path ->
					path.getFileName().toString().endsWith(AtomicCopy.TEMP_SUFFIX)));
		}
	}

	@Test
	@DisplayName("restoring twice in a row is harmless")
	void restoreIsRepeatable() throws IOException {
		write(RegionPaths.of(REGION).get(0), "ours", 5_000L);
		String id = captureAndOverwrite(REGION, 0L, "theirs");

		backups.restore(id);
		BackupStore.RestoreResult second = backups.restore(id);

		assertTrue(second.ok(), second.errors().toString());
		assertEquals("ours", read(RegionPaths.of(REGION).get(0)));
	}

	@Test
	@DisplayName("a snapshot that is not there is a message, not a crash")
	void missingSnapshotReportsCleanly() {
		BackupStore.RestoreResult result = backups.restore("20260101-000000");

		assertFalse(result.ok());
		assertEquals(0, result.filesRestored());
	}

	@Test
	@DisplayName("a damaged snapshot folder is skipped, the good ones still list")
	void damagedSnapshotIsSkipped() throws IOException {
		write(RegionPaths.of(REGION).get(0), "ours", 5_000L);
		String good = captureAndOverwrite(REGION, 0L, "theirs");
		Files.createDirectories(backupRoot.resolve("not-a-backup"));

		List<BackupStore.Snapshot> all = backups.list();

		assertEquals(1, all.size());
		assertEquals(good, all.get(0).id());
	}

	@Test
	@DisplayName("deleting one by hand leaves the others alone")
	void deleteOne() throws IOException {
		write(RegionPaths.of(REGION).get(0), "ours", 5_000L);
		String first = captureAndOverwrite(REGION, 1L, "a");
		String second = captureAndOverwrite(REGION, 2L, "b");

		assertTrue(backups.delete(first));
		assertFalse(backups.delete(first));

		List<BackupStore.Snapshot> all = backups.list();
		assertEquals(1, all.size());
		assertEquals(second, all.get(0).id());
	}

	@Test
	@DisplayName("two updates in the same second do not land in the same folder")
	void idsDoNotCollide() throws IOException {
		write(RegionPaths.of(REGION).get(0), "ours", 5_000L);

		String first = captureAndOverwrite(REGION, 1L, "a");
		String second = captureAndOverwrite(REGION, 2L, "b");

		assertNotNull(first);
		assertNotNull(second);
		org.junit.jupiter.api.Assertions.assertNotEquals(first, second);
	}

	@Test
	@DisplayName("an abandoned snapshot takes its files with it")
	void abortCleansUp() throws IOException {
		write(RegionPaths.of(REGION).get(0), "ours", 5_000L);

		BackupStore.Session session = backups.begin();
		session.capture(REGION, 0L);
		session.abort();

		assertTrue(backups.list().isEmpty());
		assertFalse(Files.exists(backupRoot.resolve(session.id())));
	}

	@Test
	@DisplayName("the size adds up to what is actually on disk")
	void reportsSize() throws IOException {
		write(RegionPaths.of(REGION).get(0), "x".repeat(4096), 5_000L);
		write(RegionPaths.of(REGION).get(1), "y".repeat(2048), 5_000L);

		BackupStore.Session session = backups.begin();
		session.capture(REGION, 0L);
		BackupStore.Snapshot snapshot = session.commit();

		assertEquals(6144L, snapshot.bytes());
		assertEquals(6144L, backups.totalBytes());
	}
}
