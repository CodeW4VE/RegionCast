package xyz.w4ve.regioncast.sync;

import xyz.w4ve.regioncast.model.RegionKey;

/**
 * What happened to one region during an update, and enough detail to say so in chat.
 *
 * @param sourceModifiedMillis the source timestamp that was copied, to hand to the ledger. Zero
 *     when the source had nothing, which is the delete case.
 */
public record CopyResult(
		RegionKey region,
		boolean ok,
		int filesCopied,
		int filesDeleted,
		long sourceModifiedMillis,
		String error) {

	public static CopyResult ok(RegionKey region, int copied, int deleted, long sourceModified) {
		return new CopyResult(region, true, copied, deleted, sourceModified, null);
	}

	public static CopyResult failed(RegionKey region, String error) {
		return new CopyResult(region, false, 0, 0, 0L, error);
	}

	/** True when the source had no files at all, so this region was removed here. */
	public boolean wasDeletion() {
		return ok && filesCopied == 0 && filesDeleted > 0;
	}

	/** True when neither side had anything, so there was nothing to do. */
	public boolean wasNoOp() {
		return ok && filesCopied == 0 && filesDeleted == 0;
	}
}
