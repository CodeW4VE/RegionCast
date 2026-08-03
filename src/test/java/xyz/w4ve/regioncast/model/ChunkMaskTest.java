package xyz.w4ve.regioncast.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChunkMaskTest {
	private static final RegionKey REGION = new RegionKey(Dim.OVERWORLD, -2, 3);

	@Test
	@DisplayName("a fresh mask holds nothing")
	void startsEmpty() {
		ChunkMask mask = new ChunkMask();

		assertTrue(mask.isEmpty());
		assertEquals(0, mask.count());
		assertFalse(mask.get(ChunkKey.ofIndex(REGION, 500)));
	}

	@Test
	@DisplayName("what goes in comes out, and only that")
	void setsAndGets() {
		ChunkMask mask = new ChunkMask();
		ChunkKey chunk = ChunkKey.ofIndex(REGION, 77);

		mask.set(chunk);

		assertTrue(mask.get(chunk));
		assertFalse(mask.get(ChunkKey.ofIndex(REGION, 78)));
		assertEquals(1, mask.count());
	}

	@Test
	@DisplayName("all 1024 chunks of a region fit, in 128 bytes")
	void holdsAWholeRegion() {
		ChunkMask mask = new ChunkMask();
		for (int index = 0; index < 1024; index++) {
			mask.set(ChunkKey.ofIndex(REGION, index));
		}

		assertEquals(1024, mask.count());
		assertEquals(16, mask.bits().length);
		for (int index = 0; index < 1024; index++) {
			assertTrue(mask.get(ChunkKey.ofIndex(REGION, index)), "lost chunk " + index);
		}
	}

	@Test
	@DisplayName("a mask of the wrong length off the wire is padded rather than fatal")
	void toleratesBadInput() {
		ChunkMask shortMask = new ChunkMask(new long[] {1L});
		ChunkMask longMask = new ChunkMask(new long[64]);

		assertEquals(16, shortMask.bits().length);
		assertEquals(16, longMask.bits().length);
		assertTrue(shortMask.get(ChunkKey.ofIndex(REGION, 0)));
	}

	@Test
	@DisplayName("the same chunk twice is still one chunk")
	void settingTwiceIsIdempotent() {
		ChunkMask mask = new ChunkMask();
		ChunkKey chunk = ChunkKey.ofIndex(REGION, 12);

		mask.set(chunk);
		mask.set(chunk);

		assertEquals(1, mask.count());
	}
}
