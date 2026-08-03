package xyz.w4ve.regioncast.model;

/**
 * One chunk, the finer of the two things this mod can copy.
 *
 * <p>A region holds 32 by 32 of these. Copying a whole region is a file copy; copying a chunk
 * means opening the region file at both ends and moving one entry across, which is slower but
 * lets you take a single build without dragging the 512 blocks around it along with it.
 */
public record ChunkKey(Dim dim, int x, int z) {
	/** Blocks per chunk side. */
	public static final int BLOCKS = 16;

	/** The chunk containing the given block coordinates. */
	public static ChunkKey ofBlock(Dim dim, int blockX, int blockZ) {
		return new ChunkKey(dim, Math.floorDiv(blockX, BLOCKS), Math.floorDiv(blockZ, BLOCKS));
	}

	/** The region file this chunk lives inside. */
	public RegionKey region() {
		return RegionKey.ofChunk(dim, x, z);
	}

	/** Position within its region, 0 to 31 on each axis. */
	public int localX() {
		return Math.floorMod(x, RegionKey.CHUNKS);
	}

	public int localZ() {
		return Math.floorMod(z, RegionKey.CHUNKS);
	}

	/** Index of this chunk inside its region, 0 to 1023. The bit it owns in a coverage mask. */
	public int indexInRegion() {
		return localZ() * RegionKey.CHUNKS + localX();
	}

	/** The chunk at the given index of the given region, the reverse of {@link #indexInRegion}. */
	public static ChunkKey ofIndex(RegionKey region, int index) {
		return new ChunkKey(region.dim(),
				region.x() * RegionKey.CHUNKS + index % RegionKey.CHUNKS,
				region.z() * RegionKey.CHUNKS + index / RegionKey.CHUNKS);
	}

	public int minBlockX() {
		return x * BLOCKS;
	}

	public int minBlockZ() {
		return z * BLOCKS;
	}

	public int maxBlockX() {
		return minBlockX() + BLOCKS - 1;
	}

	public int maxBlockZ() {
		return minBlockZ() + BLOCKS - 1;
	}

	@Override
	public String toString() {
		return dim.name().toLowerCase() + " chunk " + x + " " + z;
	}
}
