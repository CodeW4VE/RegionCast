package xyz.w4ve.regioncast.sync;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import xyz.w4ve.regioncast.model.Dim;
import xyz.w4ve.regioncast.model.RegionKey;
import xyz.w4ve.regioncast.model.RegionPaths;

/**
 * Reads a world folder and reports when each region was last written.
 *
 * <p>Only looks at {@code region/}, not {@code poi/} or {@code entities/}. Terrain is what gets
 * written whenever anything happens in a chunk, so its timestamp is the honest answer to "did
 * this area change", and checking three folders would triple the work for nothing.
 */
public final class RegionScanner {
	private final Path worldFolder;

	public RegionScanner(Path worldFolder) {
		this.worldFolder = worldFolder;
	}

	/**
	 * Last modified time, in millis, of every region file in one dimension.
	 *
	 * <p>A dimension folder that does not exist yields an empty map rather than an error: a world
	 * that nobody has taken to the end has no {@code DIM1} at all.
	 */
	public Map<RegionKey, Long> scan(Dim dim) throws IOException {
		Path folder = worldFolder.resolve(RegionPaths.dimensionFolder(dim, "region"));
		Map<RegionKey, Long> found = new HashMap<>();
		if (!Files.isDirectory(folder)) {
			return found;
		}
		try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
			for (Path entry : entries) {
				RegionKey region = RegionKey.parse(dim, entry.getFileName().toString());
				if (region == null || !Files.isRegularFile(entry)) {
					continue;
				}
				found.put(region, Files.getLastModifiedTime(entry).toMillis());
			}
		}
		return found;
	}

	/**
	 * Whether this world holds a single region file anywhere.
	 *
	 * <p>Tells a world apart from a folder that merely exists. Stops at the first file it finds
	 * rather than listing everything, since one is all the answer needs.
	 */
	public boolean hasAnyRegion() throws IOException {
		for (Dim dim : Dim.values()) {
			Path folder = worldFolder.resolve(RegionPaths.dimensionFolder(dim, "region"));
			if (!Files.isDirectory(folder)) {
				continue;
			}
			try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
				for (Path entry : entries) {
					if (RegionKey.parse(dim, entry.getFileName().toString()) != null
							&& Files.isRegularFile(entry)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/** Timestamp of one region's terrain file, or 0 when it is not there. */
	public long modifiedTime(RegionKey region) throws IOException {
		Path file = worldFolder
				.resolve(RegionPaths.dimensionFolder(region.dim(), "region"))
				.resolve(region.fileName());
		return Files.isRegularFile(file) ? Files.getLastModifiedTime(file).toMillis() : 0L;
	}

	public Path worldFolder() {
		return worldFolder;
	}
}
