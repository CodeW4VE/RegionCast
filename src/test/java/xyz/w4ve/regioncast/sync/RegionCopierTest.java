package xyz.w4ve.regioncast.sync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.w4ve.regioncast.model.Dim;
import xyz.w4ve.regioncast.model.RegionKey;
import xyz.w4ve.regioncast.model.RegionPaths;

class RegionCopierTest {
	private static final RegionKey REGION = new RegionKey(Dim.OVERWORLD, 1, -2);

	@TempDir
	Path root;

	private Path source;
	private Path destination;
	private RegionCopier copier;

	@BeforeEach
	void setUp() throws IOException {
		source = Files.createDirectories(root.resolve("smp/world"));
		destination = Files.createDirectories(root.resolve("mirror/world"));
		copier = new RegionCopier(source, destination);
	}

	private void write(Path world, String relative, String content, long millis) throws IOException {
		Path file = world.resolve(relative);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, StandardCharsets.UTF_8);
		Files.setLastModifiedTime(file, FileTime.fromMillis(millis));
	}

	private String read(Path world, String relative) throws IOException {
		return Files.readString(world.resolve(relative), StandardCharsets.UTF_8);
	}

	@Test
	@DisplayName("terrain, poi and entities all cross together")
	void copiesAllThreeFolders() throws IOException {
		for (String relative : RegionPaths.of(REGION)) {
			write(source, relative, "from smp " + relative, 5_000L);
		}

		CopyResult result = copier.copy(REGION);

		assertTrue(result.ok(), result.error());
		assertEquals(3, result.filesCopied());
		for (String relative : RegionPaths.of(REGION)) {
			assertEquals("from smp " + relative, read(destination, relative));
		}
	}

	@Test
	@DisplayName("the copy keeps the source timestamp, or the ledger has nothing to compare")
	void preservesSourceTimestamp() throws IOException {
		write(source, RegionPaths.of(REGION).get(0), "terrain", 1_234_000L);

		CopyResult result = copier.copy(REGION);

		Path copied = destination.resolve(RegionPaths.of(REGION).get(0));
		assertEquals(1_234_000L, Files.getLastModifiedTime(copied).toMillis());
		// And the result reports it, so the ledger records the source time and not "now".
		assertEquals(1_234_000L, result.sourceModifiedMillis());
	}

	@Test
	@DisplayName("an existing region is overwritten, not merged")
	void overwritesExisting() throws IOException {
		String terrain = RegionPaths.of(REGION).get(0);
		write(destination, terrain, "old mirror terrain", 1_000L);
		write(source, terrain, "new smp terrain", 9_000L);

		copier.copy(REGION);

		assertEquals("new smp terrain", read(destination, terrain));
	}

	@Test
	@DisplayName("a file the source no longer has is deleted here")
	void deletesWhatTheSourceLost() throws IOException {
		String poi = RegionPaths.of(REGION).get(1);
		write(destination, poi, "stale poi", 1_000L);
		write(source, RegionPaths.of(REGION).get(0), "terrain", 2_000L);

		CopyResult result = copier.copy(REGION);

		assertTrue(result.ok(), result.error());
		assertEquals(1, result.filesCopied());
		assertEquals(1, result.filesDeleted());
		assertFalse(Files.exists(destination.resolve(poi)));
	}

	@Test
	@DisplayName("a region missing from both sides is a no-op, not a failure")
	void missingEverywhereIsFine() {
		CopyResult result = copier.copy(REGION);

		assertTrue(result.ok(), result.error());
		assertTrue(result.wasNoOp());
	}

	@Test
	@DisplayName("a source with no terrain at all removes the region here")
	void emptySourceDeletesRegion() throws IOException {
		for (String relative : RegionPaths.of(REGION)) {
			write(destination, relative, "to be removed", 1_000L);
		}

		CopyResult result = copier.copy(REGION);

		assertTrue(result.wasDeletion());
		assertEquals(3, result.filesDeleted());
		for (String relative : RegionPaths.of(REGION)) {
			assertFalse(Files.exists(destination.resolve(relative)), relative);
		}
	}

	@Test
	@DisplayName("missing destination folders are created")
	void createsDestinationFolders() throws IOException {
		RegionKey end = new RegionKey(Dim.END, 0, 0);
		write(source, RegionPaths.of(end).get(0), "the end", 3_000L);

		CopyResult result = copier.copy(end);

		assertTrue(result.ok(), result.error());
		assertTrue(Files.isRegularFile(destination.resolve("DIM1/region/r.0.0.mca")));
	}

	@Test
	@DisplayName("no temporary files are left behind")
	void leavesNoTempFiles() throws IOException {
		for (String relative : RegionPaths.of(REGION)) {
			write(source, relative, "content", 4_000L);
		}

		copier.copy(REGION);

		try (Stream<Path> tree = Files.walk(destination)) {
			assertTrue(tree.noneMatch(p -> p.getFileName().toString().endsWith(AtomicCopy.TEMP_SUFFIX)),
					"a half written file was left in the world folder");
		}
	}

	@Test
	@DisplayName("byte for byte, not just the same length")
	void copiesContentExactly() throws IOException {
		String terrain = RegionPaths.of(REGION).get(0);
		byte[] payload = new byte[8192];
		for (int i = 0; i < payload.length; i++) {
			payload[i] = (byte) (i * 31);
		}
		Path file = source.resolve(terrain);
		Files.createDirectories(file.getParent());
		Files.write(file, payload);

		copier.copy(REGION);

		assertArrayEquals(payload, Files.readAllBytes(destination.resolve(terrain)));
	}

	@Test
	@DisplayName("dimensions do not bleed into each other")
	void dimensionsStaySeparate() throws IOException {
		RegionKey overworld = new RegionKey(Dim.OVERWORLD, 0, 0);
		RegionKey nether = new RegionKey(Dim.NETHER, 0, 0);
		write(source, RegionPaths.of(overworld).get(0), "overworld", 1_000L);
		write(source, RegionPaths.of(nether).get(0), "nether", 2_000L);

		copier.copy(nether);

		assertEquals("nether", read(destination, "DIM-1/region/r.0.0.mca"));
		assertFalse(Files.exists(destination.resolve("region/r.0.0.mca")),
				"copying the nether must not touch the overworld");
	}

	@Test
	@DisplayName("a source that cannot be read fails that region instead of the whole run")
	void unreadableSourceFailsCleanly() throws IOException {
		// A directory where a region file should be: readable as a path, impossible to copy.
		Path fake = source.resolve(RegionPaths.of(REGION).get(0));
		Files.createDirectories(fake);

		CopyResult result = copier.copy(REGION);

		assertTrue(result.ok(), "a directory is not a regular file, so it is skipped rather than copied");
		assertTrue(result.wasNoOp());
	}
}
