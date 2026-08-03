package xyz.w4ve.regioncast.anvil;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.w4ve.regioncast.model.ChunkKey;
import xyz.w4ve.regioncast.model.Dim;
import xyz.w4ve.regioncast.model.RegionKey;
import xyz.w4ve.regioncast.model.RegionPaths;

/**
 * The chunk copier against real region files.
 *
 * <p>Uses Minecraft's own {@link RegionFile} to lay out the fixtures, which is the point: if the
 * bytes go in through the same door the game uses and come out the other side identical, the copy
 * is sound. The chunk payloads are not valid NBT and do not need to be, since nothing here parses
 * them; what is being tested is that the right bytes end up in the right slot of the right file.
 */
class ChunkCopierTest {
	private static final RegionKey REGION = new RegionKey(Dim.OVERWORLD, 0, 0);
	private static final ChunkKey CHUNK = new ChunkKey(Dim.OVERWORLD, 3, 4);
	private static final ChunkKey NEIGHBOUR = new ChunkKey(Dim.OVERWORLD, 4, 4);

	@TempDir
	Path root;

	private Path source;
	private Path destination;
	private ChunkCopier copier;

	@BeforeEach
	void setUp() throws IOException {
		source = Files.createDirectories(root.resolve("smp/world"));
		destination = Files.createDirectories(root.resolve("mirror/world"));
		copier = new ChunkCopier(source, destination, root.resolve("work"));
	}

	/** Terrain file of the region under test, the first of the three RegionPaths entries. */
	private static String terrain() {
		return RegionPaths.of(REGION).get(0);
	}

	private void write(Path world, String relative, ChunkKey chunk, String content)
			throws IOException {
		Path file = world.resolve(relative);
		Files.createDirectories(file.getParent());
		try (RegionFile region = open(file);
				DataOutputStream out = region.getChunkDataOutputStream(pos(chunk))) {
			out.write(content.getBytes(StandardCharsets.UTF_8));
		}
	}

	private String read(Path world, String relative, ChunkKey chunk) throws IOException {
		Path file = world.resolve(relative);
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try (RegionFile region = open(file)) {
			if (!region.doesChunkExist(pos(chunk))) {
				return null;
			}
			try (DataInputStream in = region.getChunkDataInputStream(pos(chunk))) {
				return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
			}
		}
	}

	private static RegionFile open(Path file) throws IOException {
		return new RegionFile(new RegionStorageInfo("test", Level.OVERWORLD, "chunk"),
				file, file.getParent(), false);
	}

	private static ChunkPos pos(ChunkKey chunk) {
		return new ChunkPos(chunk.x(), chunk.z());
	}

	@Test
	@DisplayName("one chunk crosses, byte for byte")
	void copiesOneChunk() throws IOException {
		write(source, terrain(), CHUNK, "the build");

		ChunkCopier.Result result = copier.copy(REGION, Set.of(CHUNK));

		assertTrue(result.ok(), result.errors().toString());
		assertEquals("the build", read(destination, terrain(), CHUNK));
	}

	@Test
	@DisplayName("the chunks beside it are left exactly as they were")
	void leavesNeighboursAlone() throws IOException {
		write(source, terrain(), CHUNK, "new");
		write(source, terrain(), NEIGHBOUR, "also new");
		write(destination, terrain(), CHUNK, "old");
		write(destination, terrain(), NEIGHBOUR, "keep me");

		copier.copy(REGION, Set.of(CHUNK));

		assertEquals("new", read(destination, terrain(), CHUNK));
		assertEquals("keep me", read(destination, terrain(), NEIGHBOUR),
				"a chunk nobody picked must not move");
	}

	@Test
	@DisplayName("terrain, poi and entities of that chunk all cross")
	void copiesAllThreeFolders() throws IOException {
		for (String relative : RegionPaths.of(REGION)) {
			write(source, relative, CHUNK, "from " + relative);
		}

		ChunkCopier.Result result = copier.copy(REGION, Set.of(CHUNK));

		assertEquals(3, result.chunksCopied());
		for (String relative : RegionPaths.of(REGION)) {
			assertEquals("from " + relative, read(destination, relative, CHUNK));
		}
	}

	@Test
	@DisplayName("a chunk the source does not have is removed here")
	void removesWhatTheSourceLost() throws IOException {
		Files.createDirectories(source.resolve(terrain()).getParent());
		write(source, terrain(), NEIGHBOUR, "something else keeps the file alive");
		write(destination, terrain(), CHUNK, "we had this");

		ChunkCopier.Result result = copier.copy(REGION, Set.of(CHUNK));

		assertEquals(1, result.chunksDeleted());
		assertEquals(null, read(destination, terrain(), CHUNK));
	}

	@Test
	@DisplayName("a destination with no region file at all is created")
	void createsMissingDestination() throws IOException {
		write(source, terrain(), CHUNK, "first thing here");

		copier.copy(REGION, Set.of(CHUNK));

		assertTrue(Files.isRegularFile(destination.resolve(terrain())));
		assertEquals("first thing here", read(destination, terrain(), CHUNK));
	}

	@Test
	@DisplayName("the source file is never touched")
	void sourceIsUntouched() throws IOException {
		write(source, terrain(), CHUNK, "mine");
		byte[] before = Files.readAllBytes(source.resolve(terrain()));

		copier.copy(REGION, Set.of(CHUNK));

		assertArrayEquals(before, Files.readAllBytes(source.resolve(terrain())),
				"the source world is meant to be mounted read only");
	}

	@Test
	@DisplayName("the work folder is left empty")
	void cleansUpAfterItself() throws IOException {
		write(source, terrain(), CHUNK, "x");

		copier.copy(REGION, Set.of(CHUNK));

		Path work = root.resolve("work");
		if (Files.isDirectory(work)) {
			try (var files = Files.list(work)) {
				assertEquals(List.of(), files.toList());
			}
		}
	}

	@Test
	@DisplayName("a region the source has nothing of clears what we hold")
	void sourceMissingEntirely() throws IOException {
		write(destination, terrain(), CHUNK, "ours only");

		ChunkCopier.Result result = copier.copy(REGION, Set.of(CHUNK));

		assertEquals(1, result.chunksDeleted());
		assertFalse(result.chunksCopied() > 0);
		assertEquals(null, read(destination, terrain(), CHUNK));
	}

	@Test
	@DisplayName("chunks are grouped by the file they live in, in the order they were picked")
	void groupsByRegion() {
		ChunkKey far = new ChunkKey(Dim.OVERWORLD, 40, 40);

		Map<RegionKey, Set<ChunkKey>> grouped = ChunkCopier.byRegion(List.of(CHUNK, far, NEIGHBOUR));

		assertEquals(2, grouped.size());
		assertEquals(Set.of(CHUNK, NEIGHBOUR), grouped.get(REGION));
		assertEquals(Set.of(far), grouped.get(far.region()));
	}

	@Test
	@DisplayName("many chunks in one pass")
	void copiesManyAtOnce() throws IOException {
		for (int i = 0; i < 32; i++) {
			write(source, terrain(), new ChunkKey(Dim.OVERWORLD, i, 7), "chunk " + i);
		}

		ChunkCopier.Result result = copier.copy(REGION,
				new java.util.LinkedHashSet<>(java.util.stream.IntStream.range(0, 32)
						.mapToObj(i -> new ChunkKey(Dim.OVERWORLD, i, 7))
						.toList()));

		assertTrue(result.ok(), result.errors().toString());
		assertEquals(32, result.chunksCopied());
		for (int i = 0; i < 32; i++) {
			assertEquals("chunk " + i, read(destination, terrain(), new ChunkKey(Dim.OVERWORLD, i, 7)));
		}
	}
}
