package xyz.w4ve.regioncast.sync;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import xyz.w4ve.regioncast.model.RegionKey;

/**
 * Keeps the queue on disk so a restart does not throw away what you picked.
 *
 * <p>The queue used to live only in memory, which is fine for the normal run (it empties itself
 * once the regions are copied) but not for anything else: a server that goes down for any other
 * reason takes the selection with it, and nobody wants to click forty regions off a map twice.
 *
 * <p>Order is kept, so regions are copied in the order they were picked.
 */
public final class QueueStore {
	private static final String HEADER = "# RegionCast queue: dimension x z";

	private final Path file;

	public QueueStore(Path file) {
		this.file = file;
	}

	public Path file() {
		return file;
	}

	public List<RegionKey> load() throws IOException {
		List<RegionKey> regions = new ArrayList<>();
		if (!Files.isRegularFile(file)) {
			return regions;
		}
		for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
			String[] fields = RegionText.fields(line);
			if (fields == null) {
				continue;
			}
			RegionKey region = RegionText.parse(fields);
			if (region != null && !regions.contains(region)) {
				regions.add(region);
			}
		}
		return regions;
	}

	/** Writes the queue out, replacing the old file only once the new one is complete. */
	public void save(Collection<RegionKey> regions) throws IOException {
		Path parent = file.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		List<String> lines = new ArrayList<>(regions.size() + 1);
		lines.add(HEADER);
		for (RegionKey region : regions) {
			lines.add(RegionText.format(region));
		}
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.write(temp, lines, StandardCharsets.UTF_8);
		Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
	}
}
