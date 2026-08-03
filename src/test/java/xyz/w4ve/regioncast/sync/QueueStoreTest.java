package xyz.w4ve.regioncast.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.w4ve.regioncast.model.Dim;
import xyz.w4ve.regioncast.model.RegionKey;

class QueueStoreTest {

	@TempDir
	Path dir;

	@Test
	@DisplayName("a queue survives a restart")
	void roundTrips() throws IOException {
		QueueStore store = new QueueStore(dir.resolve("queue.txt"));
		List<RegionKey> picked = List.of(
				new RegionKey(Dim.NETHER, -5, -1),
				new RegionKey(Dim.NETHER, -6, 0),
				new RegionKey(Dim.OVERWORLD, 3, 3));

		store.save(picked);

		assertEquals(picked, store.load());
	}

	@Test
	@DisplayName("the order regions were picked in is kept")
	void keepsOrder() throws IOException {
		QueueStore store = new QueueStore(dir.resolve("queue.txt"));
		Set<RegionKey> picked = new LinkedHashSet<>();
		for (int x = 5; x >= 0; x--) {
			picked.add(new RegionKey(Dim.END, x, 0));
		}

		store.save(picked);
		List<RegionKey> loaded = store.load();

		assertEquals(List.copyOf(picked), loaded);
		assertEquals(5, loaded.get(0).x(), "the first one picked is still first");
	}

	@Test
	@DisplayName("no queue file means an empty queue, not a failure")
	void missingFileIsEmpty() throws IOException {
		assertTrue(new QueueStore(dir.resolve("nope.txt")).load().isEmpty());
	}

	@Test
	@DisplayName("an emptied queue is written out, not left behind")
	void savingEmptyClearsIt() throws IOException {
		QueueStore store = new QueueStore(dir.resolve("queue.txt"));
		store.save(List.of(new RegionKey(Dim.OVERWORLD, 1, 1)));

		store.save(List.of());

		assertTrue(store.load().isEmpty(), "a copied queue must not come back after a restart");
	}

	@Test
	@DisplayName("damaged lines are skipped and the rest of the picks survive")
	void badLinesAreSkipped() throws IOException {
		Path file = dir.resolve("queue.txt");
		Files.write(file, List.of(
				"# RegionCast queue",
				"0 1 2",
				"nonsense",
				"0 x 2",
				"99 0 0",
				"-1 -6 0"));

		List<RegionKey> loaded = new QueueStore(file).load();

		assertEquals(List.of(
				new RegionKey(Dim.OVERWORLD, 1, 2),
				new RegionKey(Dim.NETHER, -6, 0)), loaded);
	}

	@Test
	@DisplayName("a region listed twice is only queued once")
	void deduplicates() throws IOException {
		Path file = dir.resolve("queue.txt");
		Files.write(file, List.of("0 1 2", "0 1 2", "0 1 2"));

		assertEquals(1, new QueueStore(file).load().size());
	}

	@Test
	@DisplayName("same coordinates in different dimensions stay separate")
	void dimensionsAreDistinct() throws IOException {
		QueueStore store = new QueueStore(dir.resolve("queue.txt"));
		store.save(List.of(
				new RegionKey(Dim.OVERWORLD, 0, 0),
				new RegionKey(Dim.NETHER, 0, 0),
				new RegionKey(Dim.END, 0, 0)));

		assertEquals(3, store.load().size());
	}

	@Test
	@DisplayName("no leftover temp file")
	void leavesNoTempFile() throws IOException {
		QueueStore store = new QueueStore(dir.resolve("queue.txt"));
		store.save(List.of(new RegionKey(Dim.OVERWORLD, 0, 0)));
		assertFalse(Files.exists(dir.resolve("queue.txt.tmp")));
	}

	@Test
	@DisplayName("saving creates the folder if it is not there")
	void createsParentFolder() throws IOException {
		QueueStore store = new QueueStore(dir.resolve("config/regioncast/queue.txt"));
		store.save(List.of());
		assertTrue(Files.isRegularFile(store.file()));
	}
}
