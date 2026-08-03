package xyz.w4ve.regioncast.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChunkKeyTest {

	@Test
	@DisplayName("a chunk knows which region file it lives in")
	void findsItsRegion() {
		assertEquals(new RegionKey(Dim.OVERWORLD, 0, 0), new ChunkKey(Dim.OVERWORLD, 5, 5).region());
		assertEquals(new RegionKey(Dim.OVERWORLD, 1, 1), new ChunkKey(Dim.OVERWORLD, 32, 32).region());
		assertEquals(new RegionKey(Dim.OVERWORLD, 0, 0), new ChunkKey(Dim.OVERWORLD, 31, 31).region());
	}

	@Test
	@DisplayName("negative coordinates round down, they do not round towards zero")
	void negativesFloor() {
		assertEquals(new RegionKey(Dim.OVERWORLD, -1, -1), new ChunkKey(Dim.OVERWORLD, -1, -1).region());
		assertEquals(new RegionKey(Dim.OVERWORLD, -1, -1), new ChunkKey(Dim.OVERWORLD, -32, -32).region());
		assertEquals(new RegionKey(Dim.OVERWORLD, -2, -2), new ChunkKey(Dim.OVERWORLD, -33, -33).region());
	}

	@Test
	@DisplayName("the chunk under a block, on both sides of zero")
	void fromBlock() {
		assertEquals(new ChunkKey(Dim.NETHER, 0, 0), ChunkKey.ofBlock(Dim.NETHER, 15, 15));
		assertEquals(new ChunkKey(Dim.NETHER, 1, 1), ChunkKey.ofBlock(Dim.NETHER, 16, 16));
		assertEquals(new ChunkKey(Dim.NETHER, -1, -1), ChunkKey.ofBlock(Dim.NETHER, -1, -1));
	}

	@Test
	@DisplayName("its place inside the region is 0 to 1023, wherever it is in the world")
	void indexInRegion() {
		assertEquals(0, new ChunkKey(Dim.OVERWORLD, 0, 0).indexInRegion());
		assertEquals(1023, new ChunkKey(Dim.OVERWORLD, 31, 31).indexInRegion());
		assertEquals(0, new ChunkKey(Dim.OVERWORLD, -32, -32).indexInRegion());
		assertEquals(1023, new ChunkKey(Dim.OVERWORLD, -1, -1).indexInRegion());
	}

	@Test
	@DisplayName("index and chunk are the same thing read two ways")
	void indexRoundTrips() {
		for (RegionKey region : new RegionKey[] {
				new RegionKey(Dim.OVERWORLD, 0, 0),
				new RegionKey(Dim.END, -3, 7)}) {
			for (int index = 0; index < 1024; index++) {
				ChunkKey chunk = ChunkKey.ofIndex(region, index);
				assertEquals(region, chunk.region(), "index " + index + " left its own region");
				assertEquals(index, chunk.indexInRegion());
			}
		}
	}

	@Test
	@DisplayName("its blocks are inside its region's blocks")
	void blockBoundsAgree() {
		ChunkKey chunk = new ChunkKey(Dim.OVERWORLD, -5, 9);
		RegionKey region = chunk.region();

		assertTrue(region.containsBlock(chunk.minBlockX(), chunk.minBlockZ()));
		assertTrue(region.containsBlock(chunk.maxBlockX(), chunk.maxBlockZ()));
		assertEquals(16, chunk.maxBlockX() - chunk.minBlockX() + 1);
	}
}
