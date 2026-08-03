package xyz.w4ve.regioncast.model;

/**
 * What the map paints for a region, and the only thing the client needs to know about it.
 *
 * <p>Deliberately about the <em>source</em> and our own sync history, never about what the mirror
 * did on its own. The mirror rewrites its region files all the time just by having someone walk
 * around, so "the two files differ" is useless as a signal. "The source changed since the last
 * time we copied it" is the question actually worth answering.
 */
public enum RegionStatus {
	/** Copied at some point, and the source has not changed since. Nothing to do. */
	IN_SYNC,
	/** The source changed after our last copy. This is the one the map paints red. */
	STALE,
	/** Exists on the source and we have never copied it. */
	NEVER_SYNCED,
	/** We copied it once, but the source no longer has it. Updating deletes it here. */
	GONE
}
