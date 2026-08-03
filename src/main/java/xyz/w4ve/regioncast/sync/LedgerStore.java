package xyz.w4ve.regioncast.sync;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import xyz.w4ve.regioncast.model.ChunkKey;
import xyz.w4ve.regioncast.model.RegionKey;
import xyz.w4ve.regioncast.model.SyncLedger;

/**
 * Reads and writes a ledger as plain text.
 *
 * <p>One entry per line, {@code <dimension index> <x> <z> <source millis>}, because this is a
 * file somebody will eventually open at three in the morning wondering why a region will not go
 * green. JSON would need a library on the test classpath and would read worse.
 *
 * <p>Unparseable lines are dropped rather than fatal. A ledger that lost an entry re-copies one
 * region; a ledger that refuses to load loses the whole history.
 *
 * <p>Two of these exist: one keyed by region for whole file copies, one keyed by chunk for the
 * chunks pulled across on their own. Identical format, different coordinates.
 *
 * @param <K> what the ledger is keyed by
 */
public final class LedgerStore<K> {
	private final Path file;
	private final String header;
	private final Function<K, String> format;
	private final Function<String[], K> parse;

	private LedgerStore(Path file, String header, Function<K, String> format,
			Function<String[], K> parse) {
		this.file = file;
		this.header = header;
		this.format = format;
		this.parse = parse;
	}

	public static LedgerStore<RegionKey> forRegions(Path file) {
		return new LedgerStore<>(file,
				"# RegionCast sync ledger: dimension x z source-last-modified-millis",
				RegionText::format, RegionText::parse);
	}

	public static LedgerStore<ChunkKey> forChunks(Path file) {
		return new LedgerStore<>(file,
				"# RegionCast chunk ledger: dimension chunk-x chunk-z source-region-last-modified-millis",
				RegionText::formatChunk, RegionText::parseChunk);
	}

	public Path file() {
		return file;
	}

	/** Loads the ledger, returning an empty one when the file is not there yet. */
	public SyncLedger<K> load() throws IOException {
		SyncLedger<K> ledger = new SyncLedger<>();
		if (!Files.isRegularFile(file)) {
			return ledger;
		}
		for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
			String[] fields = RegionText.fields(line);
			if (fields == null || fields.length != 4) {
				continue;
			}
			K key = parse.apply(fields);
			if (key == null) {
				continue;
			}
			try {
				ledger.record(key, Long.parseLong(fields[3]));
			} catch (NumberFormatException e) {
				// Bad timestamp: skip the line, keep the rest.
			}
		}
		return ledger;
	}

	/** Writes the ledger out, replacing the previous one only once the new one is complete. */
	public void save(SyncLedger<K> ledger) throws IOException {
		Path parent = file.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		List<String> lines = new ArrayList<>(ledger.size() + 1);
		lines.add(header);
		for (Map.Entry<K, Long> entry : ledger.asMap().entrySet()) {
			lines.add(format.apply(entry.getKey()) + " " + entry.getValue());
		}
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.write(temp, lines, StandardCharsets.UTF_8);
		Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
	}
}
