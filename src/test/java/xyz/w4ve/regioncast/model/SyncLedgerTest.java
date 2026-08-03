package xyz.w4ve.regioncast.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SyncLedgerTest {
	private static final RegionKey REGION = new RegionKey(Dim.OVERWORLD, 2, -1);

	@Test
	@DisplayName("a region we have never copied reads as never synced")
	void unknownIsNeverSynced() {
		SyncLedger ledger = new SyncLedger();
		assertFalse(ledger.knows(REGION));
		assertEquals(RegionStatus.NEVER_SYNCED, ledger.status(REGION, 1_000L));
	}

	@Test
	@DisplayName("right after a copy the region is in sync")
	void freshCopyIsInSync() {
		SyncLedger ledger = new SyncLedger();
		ledger.record(REGION, 1_000L);
		assertEquals(RegionStatus.IN_SYNC, ledger.status(REGION, 1_000L));
	}

	@Test
	@DisplayName("the source changing again makes it stale")
	void sourceChangeMakesStale() {
		SyncLedger ledger = new SyncLedger();
		ledger.record(REGION, 1_000L);
		assertEquals(RegionStatus.STALE, ledger.status(REGION, 1_001L));
	}

	@Test
	@DisplayName("the mirror rewriting its own copy does not make anything stale")
	void mirrorWritesDoNotMatter() {
		// This is the entire reason the ledger exists. Somebody walking around the mirror
		// rewrites its region files constantly. If we compared the two files we would report
		// every visited region as out of date forever. We only ever look at the source.
		SyncLedger ledger = new SyncLedger();
		ledger.record(REGION, 1_000L);
		for (int i = 0; i < 10; i++) {
			assertEquals(RegionStatus.IN_SYNC, ledger.status(REGION, 1_000L));
		}
	}

	@Test
	@DisplayName("a source saved again with the same timestamp does not flap")
	void equalTimestampIsNotStale() {
		SyncLedger ledger = new SyncLedger();
		ledger.record(REGION, 5_000L);
		assertEquals(RegionStatus.IN_SYNC, ledger.status(REGION, 5_000L));
	}

	@Test
	@DisplayName("a source that disappeared reads as gone")
	void missingSourceIsGone() {
		SyncLedger ledger = new SyncLedger();
		ledger.record(REGION, 1_000L);
		assertEquals(RegionStatus.GONE, ledger.status(REGION, 0L));
	}

	@Test
	@DisplayName("a region never copied and now missing from the source is still never synced")
	void unknownAndMissingStaysUnknown() {
		SyncLedger ledger = new SyncLedger();
		assertEquals(RegionStatus.NEVER_SYNCED, ledger.status(REGION, 0L));
	}

	@Test
	@DisplayName("forgetting a region sends it back to never synced")
	void forgetResets() {
		SyncLedger ledger = new SyncLedger();
		ledger.record(REGION, 1_000L);
		ledger.forget(REGION);
		assertEquals(RegionStatus.NEVER_SYNCED, ledger.status(REGION, 1_000L));
		assertEquals(0, ledger.size());
	}

	@Test
	@DisplayName("regions are tracked one by one, not in bulk")
	void regionsAreIndependent() {
		SyncLedger ledger = new SyncLedger();
		RegionKey other = new RegionKey(Dim.NETHER, 2, -1);
		ledger.record(REGION, 1_000L);
		// Same coordinates, different dimension: must not be confused with each other.
		assertTrue(ledger.knows(REGION));
		assertFalse(ledger.knows(other));
		assertEquals(RegionStatus.NEVER_SYNCED, ledger.status(other, 1_000L));
	}

	@Test
	@DisplayName("a reloaded ledger behaves like the one that was saved")
	void survivesRoundTrip() {
		SyncLedger saved = new SyncLedger();
		saved.record(REGION, 1_000L);
		saved.record(new RegionKey(Dim.END, 0, 0), 2_000L);

		SyncLedger loaded = new SyncLedger();
		loaded.putAll(saved.asMap());

		assertEquals(2, loaded.size());
		assertEquals(RegionStatus.IN_SYNC, loaded.status(REGION, 1_000L));
		assertEquals(RegionStatus.STALE, loaded.status(REGION, 1_500L));
	}
}
