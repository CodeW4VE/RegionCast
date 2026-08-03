package xyz.w4ve.regioncast.sync;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import xyz.w4ve.regioncast.model.RegionKey;
import xyz.w4ve.regioncast.model.RegionPaths;

/**
 * Copies region files from the source world to ours.
 *
 * <p>Deliberately knows nothing about Minecraft, so it can be tested against a pair of temporary
 * folders instead of a 13 GB world. It also assumes nothing is holding the destination files open:
 * callers are responsible for only running this while the server is stopped.
 *
 * <p>The actual writing is {@link AtomicCopy}: never over the live file, and always carrying the
 * source timestamp across so the ledger has something to compare against later.
 */
public final class RegionCopier {
	private final Path sourceWorld;
	private final Path destinationWorld;

	public RegionCopier(Path sourceWorld, Path destinationWorld) {
		this.sourceWorld = sourceWorld;
		this.destinationWorld = destinationWorld;
	}

	public Path sourceWorld() {
		return sourceWorld;
	}

	public Path destinationWorld() {
		return destinationWorld;
	}

	/**
	 * Brings one region across, including its poi and entities files.
	 *
	 * <p>A file the source does not have is deleted here, matching what the source looks like.
	 * A region with no files on either side is a no-op rather than an error: plenty of regions
	 * have terrain but no points of interest.
	 */
	public CopyResult copy(RegionKey region) {
		List<String> paths = RegionPaths.of(region);
		int copied = 0;
		int deleted = 0;
		long newestSource = 0L;

		for (String relative : paths) {
			Path from = sourceWorld.resolve(relative);
			Path to = destinationWorld.resolve(relative);
			try {
				if (Files.isRegularFile(from)) {
					long modified = Files.getLastModifiedTime(from).toMillis();
					newestSource = Math.max(newestSource, modified);
					AtomicCopy.copy(from, to);
					copied++;
				} else if (Files.isRegularFile(to)) {
					Files.delete(to);
					deleted++;
				}
			} catch (IOException e) {
				return CopyResult.failed(region, relative + ": " + e.getMessage());
			}
		}

		return CopyResult.ok(region, copied, deleted, newestSource);
	}
}
