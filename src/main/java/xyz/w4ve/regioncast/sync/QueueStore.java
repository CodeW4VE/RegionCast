package xyz.w4ve.regioncast.sync;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import xyz.w4ve.regioncast.model.ChunkKey;
import xyz.w4ve.regioncast.model.RegionKey;

/**
 * Keeps a queue on disk so a restart does not throw away what you picked.
 *
 * <p>The queue used to live only in memory, which is fine for the normal run (it empties itself
 * once the regions are copied) but not for anything else: a server that goes down for any other
 * reason takes the selection with it, and nobody wants to click forty regions off a map twice.
 *
 * <p>Order is kept, so things are copied in the order they were picked.
 *
 * @param <K> a region or a chunk, the two things that can be queued
 */
public final class QueueStore<K> {
	private final Path file;
	private final String header;
	private final Function<K, String> format;
	private final Function<String[], K> parse;

	private QueueStore(Path file, String header, Function<K, String> format,
			Function<String[], K> parse) {
		this.file = file;
		this.header = header;
		this.format = format;
		this.parse = parse;
	}

	public static QueueStore<RegionKey> forRegions(Path file) {
		return new QueueStore<>(file, "# RegionCast queue: dimension x z",
				RegionText::format, RegionText::parse);
	}

	public static QueueStore<ChunkKey> forChunks(Path file) {
		return new QueueStore<>(file, "# RegionCast chunk queue: dimension chunk-x chunk-z",
				RegionText::formatChunk, RegionText::parseChunk);
	}

	public Path file() {
		return file;
	}

	public List<K> load() throws IOException {
		List<K> keys = new ArrayList<>();
		if (!Files.isRegularFile(file)) {
			return keys;
		}
		for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
			String[] fields = RegionText.fields(line);
			if (fields == null) {
				continue;
			}
			K key = parse.apply(fields);
			if (key != null && !keys.contains(key)) {
				keys.add(key);
			}
		}
		return keys;
	}

	/** Writes the queue out, replacing the old file only once the new one is complete. */
	public void save(Collection<K> keys) throws IOException {
		Path parent = file.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		List<String> lines = new ArrayList<>(keys.size() + 1);
		lines.add(header);
		for (K key : keys) {
			lines.add(format.apply(key));
		}
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.write(temp, lines, StandardCharsets.UTF_8);
		Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
	}
}
