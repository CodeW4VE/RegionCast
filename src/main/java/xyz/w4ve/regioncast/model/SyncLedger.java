package xyz.w4ve.regioncast.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Remembers, for every region we have ever copied, how old the source file was at the time.
 *
 * <p>This exists because comparing the two files directly does not work. The obvious approach,
 * "copy the file and later check whether source and destination still match", breaks twice over:
 * the copy itself gives the destination a fresh timestamp, and the mirror keeps rewriting its own
 * region files as soon as anybody loads a chunk. Either way every region would read as out of
 * date forever.
 *
 * <p>So instead we record the source timestamp we copied, and compare future source timestamps
 * against that. Whatever the mirror does to its own copy afterwards is its business.
 */
public final class SyncLedger {
	/** Source last modified time, in millis, at the moment of the last successful copy. */
	private final Map<RegionKey, Long> copiedSourceTime = new HashMap<>();

	/** Records a successful copy. */
	public void record(RegionKey region, long sourceModifiedMillis) {
		copiedSourceTime.put(region, sourceModifiedMillis);
	}

	/** Forgets a region, so it reads as never synced again. */
	public void forget(RegionKey region) {
		copiedSourceTime.remove(region);
	}

	public boolean knows(RegionKey region) {
		return copiedSourceTime.containsKey(region);
	}

	/** Source timestamp of the last copy, or 0 if this region was never copied. */
	public long lastCopiedSourceTime(RegionKey region) {
		return copiedSourceTime.getOrDefault(region, 0L);
	}

	public Set<RegionKey> known() {
		return Collections.unmodifiableSet(copiedSourceTime.keySet());
	}

	public Map<RegionKey, Long> asMap() {
		return Collections.unmodifiableMap(copiedSourceTime);
	}

	public void putAll(Map<RegionKey, Long> entries) {
		copiedSourceTime.putAll(entries);
	}

	public int size() {
		return copiedSourceTime.size();
	}

	/**
	 * Works out what the map should paint for one region.
	 *
	 * @param sourceModifiedMillis last modified time of the source region file, or 0 if it is gone
	 */
	public RegionStatus status(RegionKey region, long sourceModifiedMillis) {
		boolean sourceExists = sourceModifiedMillis > 0;
		if (!knows(region)) {
			// Never copied. If the source does not have it either there is nothing to show, but
			// callers only ask about regions one side or the other actually has.
			return RegionStatus.NEVER_SYNCED;
		}
		if (!sourceExists) {
			return RegionStatus.GONE;
		}
		// Strictly newer, so a region saved again with the same timestamp does not flap.
		return sourceModifiedMillis > lastCopiedSourceTime(region)
				? RegionStatus.STALE
				: RegionStatus.IN_SYNC;
	}
}
