package xyz.w4ve.regioncast.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RegionKeyTest {

	@Test
	@DisplayName("a block at the origin lands in region 0,0")
	void originIsRegionZero() {
		RegionKey region = RegionKey.ofBlock(Dim.OVERWORLD, 0, 0);
		assertEquals(0, region.x());
		assertEquals(0, region.z());
		assertEquals("r.0.0.mca", region.fileName());
	}

	@Test
	@DisplayName("negative coordinates floor instead of truncating")
	void negativeCoordinatesFloor() {
		// The trap: -1 / 512 is 0 in Java, which would put block -1 in region 0 and send the
		// wrong file across. Block -1 is in region -1.
		assertEquals(-1, RegionKey.ofBlock(Dim.OVERWORLD, -1, -1).x());
		assertEquals(-1, RegionKey.ofBlock(Dim.OVERWORLD, -512, -512).x());
		assertEquals(-2, RegionKey.ofBlock(Dim.OVERWORLD, -513, -513).x());
	}

	@Test
	@DisplayName("a region covers exactly 512 blocks per side")
	void regionCoversFiveTwelve() {
		RegionKey region = new RegionKey(Dim.OVERWORLD, 3, -2);
		assertEquals(1536, region.minBlockX());
		assertEquals(2047, region.maxBlockX());
		assertEquals(-1024, region.minBlockZ());
		assertEquals(-513, region.maxBlockZ());
		assertEquals(512, region.maxBlockX() - region.minBlockX() + 1);
	}

	@Test
	@DisplayName("every block inside a region maps back to it")
	void roundTripsThroughBlocks() {
		RegionKey region = new RegionKey(Dim.END, -3, 7);
		for (int x : new int[] {region.minBlockX(), region.minBlockX() + 1, region.maxBlockX()}) {
			for (int z : new int[] {region.minBlockZ(), region.maxBlockZ()}) {
				assertTrue(region.containsBlock(x, z));
				assertEquals(region, RegionKey.ofBlock(Dim.END, x, z));
			}
		}
		// One block past the edge belongs to the neighbour.
		assertEquals(new RegionKey(Dim.END, -2, 7),
				RegionKey.ofBlock(Dim.END, region.maxBlockX() + 1, region.minBlockZ()));
	}

	@Test
	@DisplayName("chunk coordinates group 32 to a region")
	void chunksGroupByThirtyTwo() {
		assertEquals(new RegionKey(Dim.OVERWORLD, 0, 0), RegionKey.ofChunk(Dim.OVERWORLD, 31, 31));
		assertEquals(new RegionKey(Dim.OVERWORLD, 1, 1), RegionKey.ofChunk(Dim.OVERWORLD, 32, 32));
		assertEquals(new RegionKey(Dim.OVERWORLD, -1, -1), RegionKey.ofChunk(Dim.OVERWORLD, -1, -1));
	}

	@Test
	@DisplayName("file names parse back, including negatives")
	void parsesFileNames() {
		assertEquals(new RegionKey(Dim.NETHER, 3, -2), RegionKey.parse(Dim.NETHER, "r.3.-2.mca"));
		assertEquals(new RegionKey(Dim.NETHER, -12, -30), RegionKey.parse(Dim.NETHER, "r.-12.-30.mca"));
		assertEquals(new RegionKey(Dim.NETHER, 0, 0), RegionKey.parse(Dim.NETHER, "r.0.0.mca"));
	}

	@Test
	@DisplayName("junk in the region folder is skipped, not thrown about")
	void parseRejectsJunk() {
		// Worlds collect all of these. None of them should stop a scan.
		assertNull(RegionKey.parse(Dim.OVERWORLD, "r.0.0.mca.tmp"));
		assertNull(RegionKey.parse(Dim.OVERWORLD, "r.0.0.mcc"));
		assertNull(RegionKey.parse(Dim.OVERWORLD, "r.x.0.mca"));
		assertNull(RegionKey.parse(Dim.OVERWORLD, "r..0.mca"));
		assertNull(RegionKey.parse(Dim.OVERWORLD, "r.0..mca"));
		assertNull(RegionKey.parse(Dim.OVERWORLD, "session.lock"));
		assertNull(RegionKey.parse(Dim.OVERWORLD, ""));
	}

	@Test
	@DisplayName("the file name a region reports is the one it parses from")
	void fileNameRoundTrips() {
		for (int x = -3; x <= 3; x++) {
			for (int z = -3; z <= 3; z++) {
				RegionKey region = new RegionKey(Dim.OVERWORLD, x, z);
				assertEquals(region, RegionKey.parse(Dim.OVERWORLD, region.fileName()));
			}
		}
	}

	@Test
	@DisplayName("a region is one map tile, so the indices are the same number")
	void regionSizeMatchesMapTile() {
		// The whole design rests on this: the map already cuts the world into 512 block tiles,
		// so tile (x,z) is region (x,z) and no conversion is needed anywhere.
		assertEquals(512, RegionKey.BLOCKS);
		assertEquals(RegionKey.BLOCKS, RegionKey.CHUNKS * 16);
	}
}
