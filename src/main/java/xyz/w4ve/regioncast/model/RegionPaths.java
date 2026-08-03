package xyz.w4ve.regioncast.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a region's data actually lives inside a world folder.
 *
 * <p>Since 1.17 a region is not one file: block data is in {@code region/}, points of interest
 * in {@code poi/} and mobs, item frames and everything else entity shaped in {@code entities/}.
 * Copying only {@code region/} is the classic way to end up with terrain that moved and mobs
 * that did not, so all three travel together and always in that order.
 */
public final class RegionPaths {
	/** Sub folders of a dimension that hold per region files, in copy order. */
	public static final List<String> FOLDERS = List.of("region", "poi", "entities");

	private RegionPaths() {
	}

	/**
	 * Paths of every file belonging to this region, relative to the world folder.
	 *
	 * <p>Not all of them necessarily exist: a region nobody has visited enough may have no
	 * {@code poi} file at all. Callers are expected to skip what is missing rather than fail.
	 */
	public static List<String> of(RegionKey region) {
		List<String> paths = new ArrayList<>(FOLDERS.size());
		for (String folder : FOLDERS) {
			paths.add(dimensionFolder(region.dim(), folder) + "/" + region.fileName());
		}
		return paths;
	}

	/** Path of one dimension sub folder relative to the world folder, e.g. {@code DIM-1/poi}. */
	public static String dimensionFolder(Dim dim, String folder) {
		String prefix = dim.folder();
		return prefix.isEmpty() ? folder : prefix + "/" + folder;
	}
}
