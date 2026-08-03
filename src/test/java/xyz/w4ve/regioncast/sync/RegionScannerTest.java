package xyz.w4ve.regioncast.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.w4ve.regioncast.model.Dim;
import xyz.w4ve.regioncast.model.RegionKey;

class RegionScannerTest {

	@TempDir
	Path world;

	private void writeRegion(String folder, String name, long millis) throws IOException {
		Path file = world.resolve(folder).resolve(name);
		Files.createDirectories(file.getParent());
		Files.writeString(file, "x");
		Files.setLastModifiedTime(file, FileTime.fromMillis(millis));
	}

	@Test
	@DisplayName("finds every region with its timestamp")
	void findsRegions() throws IOException {
		writeRegion("region", "r.0.0.mca", 1_000L);
		writeRegion("region", "r.-1.3.mca", 2_000L);

		Map<RegionKey, Long> found = new RegionScanner(world).scan(Dim.OVERWORLD);

		assertEquals(2, found.size());
		assertEquals(1_000L, found.get(new RegionKey(Dim.OVERWORLD, 0, 0)));
		assertEquals(2_000L, found.get(new RegionKey(Dim.OVERWORLD, -1, 3)));
	}

	@Test
	@DisplayName("a dimension nobody has visited is empty, not an error")
	void missingDimensionIsEmpty() throws IOException {
		assertTrue(new RegionScanner(world).scan(Dim.END).isEmpty());
	}

	@Test
	@DisplayName("stray files in the region folder are ignored")
	void ignoresJunk() throws IOException {
		writeRegion("region", "r.0.0.mca", 1_000L);
		writeRegion("region", "r.0.0.mca.tmp", 1_000L);
		writeRegion("region", "session.lock", 1_000L);
		writeRegion("region", "r.5.5.mcc", 1_000L);

		Map<RegionKey, Long> found = new RegionScanner(world).scan(Dim.OVERWORLD);

		assertEquals(1, found.size());
	}

	@Test
	@DisplayName("only terrain is scanned, poi and entities are not double counted")
	void scansTerrainOnly() throws IOException {
		writeRegion("region", "r.0.0.mca", 1_000L);
		writeRegion("poi", "r.0.0.mca", 9_000L);
		writeRegion("entities", "r.0.0.mca", 9_000L);

		Map<RegionKey, Long> found = new RegionScanner(world).scan(Dim.OVERWORLD);

		assertEquals(1, found.size());
		assertEquals(1_000L, found.get(new RegionKey(Dim.OVERWORLD, 0, 0)));
	}

	@Test
	@DisplayName("each dimension is scanned in its own folder")
	void scansPerDimension() throws IOException {
		writeRegion("region", "r.0.0.mca", 1_000L);
		writeRegion("DIM-1/region", "r.7.7.mca", 2_000L);
		writeRegion("DIM1/region", "r.9.9.mca", 3_000L);

		RegionScanner scanner = new RegionScanner(world);

		assertEquals(1, scanner.scan(Dim.OVERWORLD).size());
		assertEquals(2_000L, scanner.scan(Dim.NETHER).get(new RegionKey(Dim.NETHER, 7, 7)));
		assertEquals(3_000L, scanner.scan(Dim.END).get(new RegionKey(Dim.END, 9, 9)));
	}

	@Test
	@DisplayName("a single region reports 0 when it is not there")
	void missingRegionIsZero() throws IOException {
		RegionScanner scanner = new RegionScanner(world);
		assertEquals(0L, scanner.modifiedTime(new RegionKey(Dim.OVERWORLD, 4, 4)));

		writeRegion("region", "r.4.4.mca", 7_000L);
		assertEquals(7_000L, scanner.modifiedTime(new RegionKey(Dim.OVERWORLD, 4, 4)));
	}
}
