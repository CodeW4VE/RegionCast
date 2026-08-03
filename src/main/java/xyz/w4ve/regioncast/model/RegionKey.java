package xyz.w4ve.regioncast.model;

/**
 * One region file, identified the same way Minecraft names it on disk: {@code r.<x>.<z>.mca}.
 *
 * <p>A region is 32 by 32 chunks, so 512 by 512 blocks. That is exactly the size of a map tile
 * in our map code, which is why a tile index and a region index are the same number and no
 * conversion is needed anywhere in this mod.
 */
public record RegionKey(Dim dim, int x, int z) {
	/** Blocks per region side. Same value as the map tile size, and that is on purpose. */
	public static final int BLOCKS = 512;
	/** Chunks per region side. */
	public static final int CHUNKS = 32;

	/** The region containing the given block coordinates. */
	public static RegionKey ofBlock(Dim dim, int blockX, int blockZ) {
		return new RegionKey(dim, Math.floorDiv(blockX, BLOCKS), Math.floorDiv(blockZ, BLOCKS));
	}

	/** The region containing the given chunk coordinates. */
	public static RegionKey ofChunk(Dim dim, int chunkX, int chunkZ) {
		return new RegionKey(dim, Math.floorDiv(chunkX, CHUNKS), Math.floorDiv(chunkZ, CHUNKS));
	}

	/** File name on disk, identical in the region, poi and entities folders. */
	public String fileName() {
		return "r." + x + "." + z + ".mca";
	}

	/**
	 * Reads a region back out of its file name, for scanning a folder.
	 *
	 * @return the region, or null if the name is not {@code r.<x>.<z>.mca} with two integers.
	 *     Worlds pick up all sorts of stray files, so an unparseable name is expected and skipped
	 *     rather than thrown about.
	 */
	public static RegionKey parse(Dim dim, String fileName) {
		if (!fileName.startsWith("r.") || !fileName.endsWith(".mca")) {
			return null;
		}
		String middle = fileName.substring(2, fileName.length() - 4);
		int dot = middle.indexOf('.');
		if (dot <= 0 || dot == middle.length() - 1) {
			return null;
		}
		try {
			int x = Integer.parseInt(middle.substring(0, dot));
			int z = Integer.parseInt(middle.substring(dot + 1));
			return new RegionKey(dim, x, z);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/** Lowest block X inside this region. */
	public int minBlockX() {
		return x * BLOCKS;
	}

	/** Lowest block Z inside this region. */
	public int minBlockZ() {
		return z * BLOCKS;
	}

	/** Highest block X inside this region, inclusive. */
	public int maxBlockX() {
		return minBlockX() + BLOCKS - 1;
	}

	/** Highest block Z inside this region, inclusive. */
	public int maxBlockZ() {
		return minBlockZ() + BLOCKS - 1;
	}

	public boolean containsBlock(int blockX, int blockZ) {
		return blockX >= minBlockX() && blockX <= maxBlockX()
				&& blockZ >= minBlockZ() && blockZ <= maxBlockZ();
	}

	@Override
	public String toString() {
		return dim.name().toLowerCase() + " " + fileName();
	}
}
