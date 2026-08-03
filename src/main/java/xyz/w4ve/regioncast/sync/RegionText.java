package xyz.w4ve.regioncast.sync;

import xyz.w4ve.regioncast.model.ChunkKey;
import xyz.w4ve.regioncast.model.Dim;
import xyz.w4ve.regioncast.model.RegionKey;

/**
 * How a region is written down in the plain text files this mod keeps.
 *
 * <p>{@code <dimension index> <x> <z>}, using the same dimension numbers as the commands, so the
 * ledger and the queue read the same way and can be edited by hand.
 */
final class RegionText {

	private RegionText() {
	}

	static String format(RegionKey region) {
		return region.dim().index() + " " + region.x() + " " + region.z();
	}

	/**
	 * Reads a region from the first three fields of a line.
	 *
	 * @return the region, or null when the line is a comment, blank, or damaged. Callers drop bad
	 *     lines rather than failing: losing one entry re-copies one region, refusing to load the
	 *     file loses the whole history.
	 */
	static RegionKey parse(String[] parts) {
		if (parts.length < 3) {
			return null;
		}
		try {
			return new RegionKey(
					Dim.byIndex(Integer.parseInt(parts[0])),
					Integer.parseInt(parts[1]),
					Integer.parseInt(parts[2]));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** Same three fields, but the numbers are chunk coordinates rather than region ones. */
	static String formatChunk(ChunkKey chunk) {
		return chunk.dim().index() + " " + chunk.x() + " " + chunk.z();
	}

	static ChunkKey parseChunk(String[] parts) {
		if (parts.length < 3) {
			return null;
		}
		try {
			return new ChunkKey(
					Dim.byIndex(Integer.parseInt(parts[0])),
					Integer.parseInt(parts[1]),
					Integer.parseInt(parts[2]));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** Splits a line into fields, or null when there is nothing worth reading on it. */
	static String[] fields(String line) {
		String trimmed = line.trim();
		if (trimmed.isEmpty() || trimmed.startsWith("#")) {
			return null;
		}
		return trimmed.split("\\s+");
	}
}
