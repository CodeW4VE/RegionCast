package xyz.w4ve.regioncast.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Remembers, for everything we have ever copied, how old the source file was at the time.
 *
 * <p>This exists because comparing the two files directly does not work. The obvious approach,
 * "copy the file and later check whether source and destination still match", breaks twice over:
 * the copy itself gives the destination a fresh timestamp, and the mirror keeps rewriting its own
 * region files as soon as anybody loads a chunk. Either way every region would read as out of
 * date forever.
 *
 * <p>So instead we record the source timestamp we copied, and compare future source timestamps
 * against that. Whatever the mirror does to its own copy afterwards is its business.
 *
 * <p>Used twice over: keyed by {@link RegionKey} for whole region files, and by {@link ChunkKey}
 * for single chunks. Chunks are still judged against the timestamp of the region file that holds
 * them, because that is the only timestamp the source has. That makes it pessimistic in one
 * direction: a change anywhere in a region marks every chunk copied out of it as out of date,
 * even the ones nobody touched. Wrong in the direction that tells you to look, which is the safe
 * way to be wrong.
 *
 * @param <K> what is being tracked, a region or a chunk
 */
public final class SyncLedger<K> {
	/** Source last modified time, in millis, at the moment of the last successful copy. */
	private final Map<K, Long> copiedSourceTime = new HashMap<>();

	/** Records a successful copy. */
	public void record(K key, long sourceModifiedMillis) {
		copiedSourceTime.put(key, sourceModifiedMillis);
	}

	/** Forgets an entry, so it reads as never synced again. */
	public void forget(K key) {
		copiedSourceTime.remove(key);
	}

	public boolean knows(K key) {
		return copiedSourceTime.containsKey(key);
	}

	/** Source timestamp of the last copy, or 0 if this was never copied. */
	public long lastCopiedSourceTime(K key) {
		return copiedSourceTime.getOrDefault(key, 0L);
	}

	public Set<K> known() {
		return Collections.unmodifiableSet(copiedSourceTime.keySet());
	}

	public Map<K, Long> asMap() {
		return Collections.unmodifiableMap(copiedSourceTime);
	}

	public void putAll(Map<K, Long> entries) {
		copiedSourceTime.putAll(entries);
	}

	public int size() {
		return copiedSourceTime.size();
	}

	/**
	 * Works out what the map should paint.
	 *
	 * @param sourceModifiedMillis last modified time of the source region file, or 0 if it is gone
	 */
	public RegionStatus status(K key, long sourceModifiedMillis) {
		boolean sourceExists = sourceModifiedMillis > 0;
		if (!knows(key)) {
			// Never copied. If the source does not have it either there is nothing to show, but
			// callers only ask about regions one side or the other actually has.
			return RegionStatus.NEVER_SYNCED;
		}
		if (!sourceExists) {
			return RegionStatus.GONE;
		}
		// Strictly newer, so a region saved again with the same timestamp does not flap.
		return sourceModifiedMillis > lastCopiedSourceTime(key)
				? RegionStatus.STALE
				: RegionStatus.IN_SYNC;
	}
}
