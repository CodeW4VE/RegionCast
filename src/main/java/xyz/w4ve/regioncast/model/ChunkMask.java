package xyz.w4ve.regioncast.model;

import java.util.Arrays;

/**
 * Which of a region's 1024 chunks something applies to, as 1024 bits.
 *
 * <p>Exists so the map can be told about chunks without the packet growing with the world. A
 * region that has had chunks pulled into it costs 128 bytes to describe completely, whatever the
 * number of chunks involved, instead of nine bytes per chunk.
 */
public final class ChunkMask {
	/** 1024 chunks per region, 64 bits per long. */
	public static final int LONGS = RegionKey.CHUNKS * RegionKey.CHUNKS / Long.SIZE;

	private final long[] bits;

	public ChunkMask() {
		this(new long[LONGS]);
	}

	/** Wraps existing bits, padding or trimming to the right length so a bad packet cannot crash us. */
	public ChunkMask(long[] bits) {
		this.bits = bits.length == LONGS ? bits : Arrays.copyOf(bits, LONGS);
	}

	public long[] bits() {
		return bits;
	}

	public void set(ChunkKey chunk) {
		int index = chunk.indexInRegion();
		bits[index / Long.SIZE] |= 1L << (index % Long.SIZE);
	}

	public boolean get(ChunkKey chunk) {
		int index = chunk.indexInRegion();
		return (bits[index / Long.SIZE] & 1L << (index % Long.SIZE)) != 0;
	}

	public boolean isEmpty() {
		for (long word : bits) {
			if (word != 0L) {
				return false;
			}
		}
		return true;
	}

	public int count() {
		int total = 0;
		for (long word : bits) {
			total += Long.bitCount(word);
		}
		return total;
	}
}
