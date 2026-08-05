package xyz.w4ve.regioncast.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.w4ve.regioncast.model.Dim;
import xyz.w4ve.regioncast.model.RegionKey;
import xyz.w4ve.regioncast.model.RegionStatus;
import xyz.w4ve.regioncast.net.RegionStatePayload;

/**
 * What the service does when the source world is not what it should be.
 *
 * <p>Written after a real morning of it: a bind mount was left pointing at a folder the backup
 * plugin had moved away, so the source read as an empty world, every copied region looked lost,
 * and an update deleted four of them on this side and called itself a success. Everything here
 * is about that not being possible again.
 */
class UpdateServiceTest {

	@TempDir
	Path root;

	private Path source;
	private Path destination;

	@BeforeEach
	void layOutTheWorlds() throws IOException {
		source = root.resolve("smp_world");
		destination = root.resolve("world");
		Files.createDirectories(source);
		Files.createDirectories(destination.resolve("region"));
	}

	private UpdateService service() throws IOException {
		Path configFile = root.resolve("regioncast.properties");
		Files.writeString(configFile, "source_world=../smp_world\n", StandardCharsets.UTF_8);
		RegionCastConfig config = RegionCastConfig.loadOrCreate(configFile);
		RegionCastPaths paths = new RegionCastPaths(source, destination,
				root.resolve("ledger.txt"), root.resolve("queue.txt"),
				root.resolve("chunk-ledger.txt"), root.resolve("chunk-queue.txt"),
				root.resolve("backups"), root.resolve("backups/work"));
		return new UpdateService(config, paths);
	}

	private void sourceRegion(String name) throws IOException {
		Path file = source.resolve("region").resolve(name);
		Files.createDirectories(file.getParent());
		Files.writeString(file, "terrain");
	}

	/** A region this side copied once, so the ledger knows it and can call it gone. */
	private void ledgerKnows(String line) throws IOException {
		Files.writeString(root.resolve("ledger.txt"), "# ledger\n" + line + "\n",
				StandardCharsets.UTF_8);
	}

	@Test
	@DisplayName("a source folder that exists but is empty counts as unusable")
	void emptySourceIsAProblem() throws IOException {
		UpdateService service = service();

		String problem = service.sourceProblem();
		assertNotNull(problem, "an empty folder must not pass as a world");
		assertFalse(service.sourceAvailable());
		assertTrue(problem.contains("no region files"), problem);
	}

	@Test
	@DisplayName("a source with terrain in it is fine")
	void realSourceIsFine() throws IOException {
		sourceRegion("r.0.0.mca");

		assertNull(service().sourceProblem());
	}

	@Test
	@DisplayName("an unreadable source never paints a region as gone")
	void emptySourceDoesNotMarkAnythingGone() throws IOException {
		ledgerKnows("0 -6 0 1783386478603");
		UpdateService service = service();

		service.refresh();

		assertEquals(List.of(), service.entries(),
				"nothing can be said about regions when the source cannot be read");
	}

	@Test
	@DisplayName("a region the source really lost is still painted as gone")
	void arealLossIsStillGone() throws IOException {
		sourceRegion("r.0.0.mca");
		ledgerKnows("0 -6 0 1783386478603");
		UpdateService service = service();

		service.refresh();

		List<RegionStatePayload.Entry> entries = service.entries();
		assertTrue(entries.contains(new RegionStatePayload.Entry(
				new RegionKey(Dim.OVERWORLD, -6, 0), RegionStatus.GONE)), entries.toString());
	}

	@Test
	@DisplayName("an update over an unreadable source is refused before anything is armed")
	void updateIsRefusedWhenTheSourceIsEmpty() throws IOException {
		UpdateService service = service();
		service.add(List.of(new RegionKey(Dim.OVERWORLD, -43, -25)));

		String why = service.whyNotReady();

		assertNotNull(why, "the update must not go ahead");
		assertTrue(why.contains("no region files"), why);
	}

	@Test
	@DisplayName("the source going away between arming and shutdown deletes nothing and keeps the queue")
	void runningWithoutASourceKeepsTheQueue() throws IOException {
		sourceRegion("r.0.0.mca");
		UpdateService service = service();
		service.add(List.of(new RegionKey(Dim.OVERWORLD, 0, 0)));
		service.arm();

		// The mount goes away while the server is on its way down, which is the whole window the
		// copy runs in.
		Files.delete(source.resolve("region").resolve("r.0.0.mca"));

		String report = service.runPending();

		assertTrue(report.startsWith("update skipped"), report);
		assertEquals(1, service.queued().size(), "the queue is kept so it can be run again");
	}
}
