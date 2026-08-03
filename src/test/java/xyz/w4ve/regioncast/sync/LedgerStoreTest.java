package xyz.w4ve.regioncast.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import xyz.w4ve.regioncast.model.Dim;
import xyz.w4ve.regioncast.model.RegionKey;
import xyz.w4ve.regioncast.model.RegionStatus;
import xyz.w4ve.regioncast.model.SyncLedger;

class LedgerStoreTest {

	@TempDir
	Path dir;

	@Test
	@DisplayName("a ledger survives a save and a load")
	void roundTrips() throws IOException {
		LedgerStore store = new LedgerStore(dir.resolve("ledger.txt"));
		SyncLedger ledger = new SyncLedger();
		ledger.record(new RegionKey(Dim.OVERWORLD, 1, -2), 1_000L);
		ledger.record(new RegionKey(Dim.NETHER, 0, 0), 2_000L);
		ledger.record(new RegionKey(Dim.END, -5, 5), 3_000L);

		store.save(ledger);
		SyncLedger loaded = store.load();

		assertEquals(3, loaded.size());
		assertEquals(1_000L, loaded.lastCopiedSourceTime(new RegionKey(Dim.OVERWORLD, 1, -2)));
		assertEquals(2_000L, loaded.lastCopiedSourceTime(new RegionKey(Dim.NETHER, 0, 0)));
		assertEquals(3_000L, loaded.lastCopiedSourceTime(new RegionKey(Dim.END, -5, 5)));
	}

	@Test
	@DisplayName("a first run with no file starts empty instead of failing")
	void missingFileLoadsEmpty() throws IOException {
		assertEquals(0, new LedgerStore(dir.resolve("nope.txt")).load().size());
	}

	@Test
	@DisplayName("saving creates the folder if it is not there")
	void createsParentFolder() throws IOException {
		LedgerStore store = new LedgerStore(dir.resolve("config/regioncast/ledger.txt"));
		store.save(new SyncLedger());
		assertTrue(Files.isRegularFile(store.file()));
	}

	@Test
	@DisplayName("a damaged line is dropped, the rest of the history survives")
	void badLinesAreSkipped() throws IOException {
		Path file = dir.resolve("ledger.txt");
		Files.write(file, java.util.List.of(
				"# a comment",
				"",
				"0 1 2 1000",
				"garbage",
				"0 1",
				"0 x 2 1000",
				"99 1 2 1000",
				"-1 0 0 2000"));

		SyncLedger loaded = new LedgerStore(file).load();

		assertEquals(2, loaded.size());
		assertEquals(1_000L, loaded.lastCopiedSourceTime(new RegionKey(Dim.OVERWORLD, 1, 2)));
		assertEquals(2_000L, loaded.lastCopiedSourceTime(new RegionKey(Dim.NETHER, 0, 0)));
	}

	@Test
	@DisplayName("the saved file is readable by a human")
	void fileIsPlainText() throws IOException {
		LedgerStore store = new LedgerStore(dir.resolve("ledger.txt"));
		SyncLedger ledger = new SyncLedger();
		ledger.record(new RegionKey(Dim.OVERWORLD, 3, -2), 1_700_000_000_000L);
		store.save(ledger);

		String text = Files.readString(store.file());
		assertTrue(text.startsWith("#"), "should open with a header explaining the columns");
		assertTrue(text.contains("0 3 -2 1700000000000"), text);
	}

	@Test
	@DisplayName("saving twice replaces rather than appends")
	void saveReplaces() throws IOException {
		LedgerStore store = new LedgerStore(dir.resolve("ledger.txt"));
		SyncLedger first = new SyncLedger();
		first.record(new RegionKey(Dim.OVERWORLD, 0, 0), 1_000L);
		store.save(first);

		SyncLedger second = new SyncLedger();
		second.record(new RegionKey(Dim.OVERWORLD, 1, 1), 2_000L);
		store.save(second);

		SyncLedger loaded = store.load();
		assertEquals(1, loaded.size());
		assertFalse(loaded.knows(new RegionKey(Dim.OVERWORLD, 0, 0)));
	}

	@Test
	@DisplayName("no leftover temp file after saving")
	void leavesNoTempFile() throws IOException {
		LedgerStore store = new LedgerStore(dir.resolve("ledger.txt"));
		store.save(new SyncLedger());
		assertFalse(Files.exists(dir.resolve("ledger.txt.tmp")));
	}

	@Test
	@DisplayName("a reloaded ledger still answers the stale question correctly")
	void reloadedLedgerStillJudgesStaleness() throws IOException {
		LedgerStore store = new LedgerStore(dir.resolve("ledger.txt"));
		SyncLedger ledger = new SyncLedger();
		RegionKey region = new RegionKey(Dim.OVERWORLD, 2, 2);
		ledger.record(region, 5_000L);
		store.save(ledger);

		SyncLedger loaded = store.load();

		assertEquals(RegionStatus.IN_SYNC, loaded.status(region, 5_000L));
		assertEquals(RegionStatus.STALE, loaded.status(region, 6_000L));
		assertEquals(RegionStatus.GONE, loaded.status(region, 0L));
	}
}
