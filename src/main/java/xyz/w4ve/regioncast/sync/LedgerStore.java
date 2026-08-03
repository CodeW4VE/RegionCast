package xyz.w4ve.regioncast.sync;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import xyz.w4ve.regioncast.model.RegionKey;
import xyz.w4ve.regioncast.model.SyncLedger;

/**
 * Reads and writes the ledger as plain text.
 *
 * <p>One region per line, {@code <dimension index> <x> <z> <source millis>}, because this is a
 * file somebody will eventually open at three in the morning wondering why a region will not go
 * green. JSON would need a library on the test classpath and would read worse.
 *
 * <p>Unparseable lines are dropped rather than fatal. A ledger that lost an entry re-copies one
 * region; a ledger that refuses to load loses the whole history.
 */
public final class LedgerStore {
	private static final String HEADER =
			"# RegionCast sync ledger: dimension x z source-last-modified-millis";

	private final Path file;

	public LedgerStore(Path file) {
		this.file = file;
	}

	public Path file() {
		return file;
	}

	/** Loads the ledger, returning an empty one when the file is not there yet. */
	public SyncLedger load() throws IOException {
		SyncLedger ledger = new SyncLedger();
		if (!Files.isRegularFile(file)) {
			return ledger;
		}
		for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
			String[] fields = RegionText.fields(line);
			if (fields == null || fields.length != 4) {
				continue;
			}
			RegionKey region = RegionText.parse(fields);
			if (region == null) {
				continue;
			}
			try {
				ledger.record(region, Long.parseLong(fields[3]));
			} catch (NumberFormatException e) {
				// Bad timestamp: skip the line, keep the rest.
			}
		}
		return ledger;
	}

	/** Writes the ledger out, replacing the previous one only once the new one is complete. */
	public void save(SyncLedger ledger) throws IOException {
		Path parent = file.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		List<String> lines = new ArrayList<>(ledger.size() + 1);
		lines.add(HEADER);
		for (Map.Entry<RegionKey, Long> entry : ledger.asMap().entrySet()) {
			lines.add(RegionText.format(entry.getKey()) + " " + entry.getValue());
		}
		Path temp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.write(temp, lines, StandardCharsets.UTF_8);
		Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
	}
}
