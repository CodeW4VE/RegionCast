package xyz.w4ve.regioncast.anvil;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import xyz.w4ve.regioncast.model.ChunkKey;
import xyz.w4ve.regioncast.model.RegionKey;
import xyz.w4ve.regioncast.model.RegionPaths;

/**
 * Copies single chunks from one world to another, in place, inside the region file.
 *
 * <p>The whole file copy in {@code sync} is a better tool whenever it fits: it is a file copy,
 * it is instant, and it cannot half work. This is for when it does not fit, when what you want is
 * one build and not the 512 by 512 blocks of terrain it happens to share a file with.
 *
 * <p>It uses Minecraft's own {@link RegionFile} at both ends rather than reimplementing the Anvil
 * format. The sector allocation, the header, the oversized chunk files and the compression are
 * all handled by the same code the game uses, so a chunk written here is indistinguishable from
 * one the server wrote itself. It also means this is the one package in the mod that cannot be
 * unit tested without Minecraft on the classpath, which is why it is on its own.
 *
 * <p><b>The source is copied to a work file first, and read from there.</b> Not an optimisation:
 * {@code RegionFile} always opens its file with {@code CREATE, READ, WRITE}, and the source world
 * is supposed to be mounted read only. Pointing it at the source directly would fail on a correct
 * setup and, on an incorrect one, would open somebody else's live world for writing. Copying the
 * file first costs one file copy per region touched and removes the question entirely.
 *
 * <p>Three caveats worth knowing, all inherent rather than bugs:
 *
 * <ul>
 *   <li>Lighting and anything crossing a chunk border arrives half done until the game
 *       recalculates it. It does, but it is visible for a moment in caves.
 *   <li>Every chunk is decompressed and recompressed, so this is much slower per block than a
 *       file copy. Still seconds, not minutes, for the numbers a person picks by hand.
 *   <li>The source only has one timestamp per region file, so a chunk can only ever be judged
 *       against that. See {@code SyncLedger}.
 * </ul>
 */
public final class ChunkCopier {
	/** Only ever used in the text of an error message, never to decide anything. */
	private static final RegionStorageInfo INFO =
			new RegionStorageInfo("regioncast", Level.OVERWORLD, "chunk");

	private final Path sourceWorld;
	private final Path destinationWorld;
	private final Path workDir;

	/**
	 * @param workDir a writable scratch folder, outside both worlds. Emptied as it goes.
	 */
	public ChunkCopier(Path sourceWorld, Path destinationWorld, Path workDir) {
		this.sourceWorld = sourceWorld;
		this.destinationWorld = destinationWorld;
		this.workDir = workDir;
	}

	/** What one region's worth of chunk copying did. */
	public record Result(RegionKey region, int chunksCopied, int chunksDeleted, List<String> errors) {
		public boolean ok() {
			return errors.isEmpty();
		}
	}

	/**
	 * Groups loose chunks by the region file they live in.
	 *
	 * <p>Every region file has to be opened and copied, which is the expensive part, so all the
	 * chunks of one region are done in one pass. Insertion ordered, so the work happens in the
	 * order things were picked.
	 */
	public static Map<RegionKey, Set<ChunkKey>> byRegion(Collection<ChunkKey> chunks) {
		Map<RegionKey, Set<ChunkKey>> grouped = new LinkedHashMap<>();
		for (ChunkKey chunk : chunks) {
			grouped.computeIfAbsent(chunk.region(), key -> new LinkedHashSet<>()).add(chunk);
		}
		return grouped;
	}

	/**
	 * Brings the given chunks of one region across, in terrain, poi and entities alike.
	 *
	 * <p>Only safe with the server stopped, same as the file copier: these are the files it has
	 * open while it runs.
	 */
	public Result copy(RegionKey region, Set<ChunkKey> chunks) {
		int copied = 0;
		int deleted = 0;
		List<String> errors = new ArrayList<>();

		for (String relative : RegionPaths.of(region)) {
			Path from = sourceWorld.resolve(relative);
			Path to = destinationWorld.resolve(relative);

			if (!Files.isRegularFile(from)) {
				// Nothing of this kind on the source at all. Anything we hold for these chunks is
				// stale by definition, so it goes, the same way a whole file copy would remove it.
				deleted += clearAll(to, chunks, errors, relative);
				continue;
			}

			Path work = null;
			try {
				Files.createDirectories(to.getParent());
				work = toWorkFile(from, chunks);

				try (RegionFile source = open(work);
						RegionFile destination = open(to)) {
					for (ChunkKey chunk : chunks) {
						ChunkPos pos = new ChunkPos(chunk.x(), chunk.z());
						try {
							if (source.doesChunkExist(pos)) {
								copyOne(source, destination, pos);
								copied++;
							} else if (destination.doesChunkExist(pos)) {
								destination.clear(pos);
								deleted++;
							}
						} catch (IOException e) {
							errors.add(relative + " " + chunk + ": " + e.getMessage());
						}
					}
					destination.flush();
				}
			} catch (IOException e) {
				errors.add(relative + ": " + e.getMessage());
			} finally {
				clearWork(work);
			}
		}

		return new Result(region, copied, deleted, errors);
	}

	/**
	 * Puts a writable copy of the source region file in the work folder.
	 *
	 * <p>Oversized chunks live in a separate {@code .mcc} beside the region file rather than
	 * inside it, so any the selection needs travels too, or those chunks would read as damaged.
	 */
	private Path toWorkFile(Path from, Set<ChunkKey> chunks) throws IOException {
		Files.createDirectories(workDir);
		Path work = workDir.resolve(from.getFileName().toString());
		Files.copy(from, work, StandardCopyOption.REPLACE_EXISTING);

		for (ChunkKey chunk : chunks) {
			String external = "c." + chunk.x() + "." + chunk.z() + ".mcc";
			Path oversized = from.resolveSibling(external);
			if (Files.isRegularFile(oversized)) {
				Files.copy(oversized, workDir.resolve(external), StandardCopyOption.REPLACE_EXISTING);
			}
		}
		return work;
	}

	/** Empties the work folder. Leftovers are wasted disk, never a correctness problem. */
	private void clearWork(Path work) {
		if (work == null) {
			return;
		}
		try (var files = Files.list(workDir)) {
			for (Path path : files.toList()) {
				Files.deleteIfExists(path);
			}
		} catch (IOException e) {
			// Nothing to do about it here, and nothing depends on it being empty.
		}
	}

	/**
	 * Moves one chunk's bytes across.
	 *
	 * <p>The streams handed out are already past the compression on the way in and before it on
	 * the way out, so this is a straight transfer of the chunk's NBT. It is written under the
	 * destination's own compression settings, which is what makes the result indistinguishable
	 * from a chunk the server saved itself.
	 */
	private static void copyOne(RegionFile source, RegionFile destination, ChunkPos pos)
			throws IOException {
		try (DataInputStream in = source.getChunkDataInputStream(pos)) {
			if (in == null) {
				return;
			}
			try (DataOutputStream out = destination.getChunkDataOutputStream(pos)) {
				in.transferTo(out);
			}
		}
	}

	/** Removes chunks from a destination file whose source counterpart does not exist. */
	private int clearAll(Path to, Set<ChunkKey> chunks, List<String> errors, String relative) {
		if (!Files.isRegularFile(to)) {
			return 0;
		}
		int deleted = 0;
		try (RegionFile destination = open(to)) {
			for (ChunkKey chunk : chunks) {
				ChunkPos pos = new ChunkPos(chunk.x(), chunk.z());
				if (destination.doesChunkExist(pos)) {
					destination.clear(pos);
					deleted++;
				}
			}
			destination.flush();
		} catch (IOException e) {
			errors.add(relative + ": " + e.getMessage());
		}
		return deleted;
	}

	/** Oversized chunks spill out next to the file itself, which is where the game keeps them. */
	private static RegionFile open(Path file) throws IOException {
		return new RegionFile(INFO, file, file.getParent(), false);
	}
}
