package xyz.w4ve.regioncast.net;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import xyz.w4ve.regioncast.RegionCast;
import xyz.w4ve.regioncast.model.ChunkKey;
import xyz.w4ve.regioncast.model.ChunkMask;
import xyz.w4ve.regioncast.model.Dim;
import xyz.w4ve.regioncast.model.RegionKey;
import xyz.w4ve.regioncast.model.RegionStatus;
import xyz.w4ve.regioncast.sync.BackupStore;

/**
 * Everything the map needs to paint itself, sent whenever the server rescans or the queue moves.
 *
 * <p>Only regions worth a colour travel: the ones the source changed since our last copy, the
 * ones never copied, and the ones the source lost. Regions that are up to date are the vast
 * majority and say nothing, so leaving them out keeps this small. On the real world that is
 * around 60 entries out of 1812 rather than all of them.
 *
 * <p>A client without the mod never registers the channel and simply receives nothing, the same
 * way Servux behaves.
 *
 * @param entries regions that are not in sync, with what is wrong with each
 * @param queued regions currently waiting to be copied, in order
 * @param queuedChunks single chunks waiting to be copied, for the ones picked at close zoom
 * @param partials regions holding chunks that were pulled across on their own
 * @param report human readable outcome of the last update, empty when there has not been one
 * @param backups the updates that can still be taken back, newest first. A handful of rows at
 *     most, so they ride along with every state packet instead of needing a channel of their own.
 * @param openMap set only in reply to {@code /rc map}, so one server side command can open a
 *     client side screen. Keeps the whole mod behind a single {@code /rc} instead of needing a
 *     separate client command that would collide with it.
 */
public record RegionStatePayload(List<Entry> entries, List<RegionKey> queued,
		List<ChunkKey> queuedChunks, List<Partial> partials, String report,
		List<BackupStore.Snapshot> backups, boolean openMap)
		implements CustomPacketPayload {

	/** Sanity bound on the backup list coming off the wire. The default keeps three. */
	private static final int MAX_BACKUPS = 64;
	/** Sanity bound on partially copied regions. Well past anything a person clicks by hand. */
	private static final int MAX_PARTIALS = 4096;

	public RegionStatePayload(List<Entry> entries, List<RegionKey> queued,
			List<ChunkKey> queuedChunks, List<Partial> partials, String report,
			List<BackupStore.Snapshot> backups) {
		this(entries, queued, queuedChunks, partials, report, backups, false);
	}

	/** One region and why the map should colour it. */
	public record Entry(RegionKey region, RegionStatus status) {
	}

	/**
	 * A region with chunks copied into it one at a time.
	 *
	 * @param copied which of its 1024 chunks came across on their own
	 * @param stale which of those the source has changed since. A subset of {@code copied}, and
	 *     always all or nothing per region in practice, because the source only timestamps whole
	 *     region files.
	 */
	public record Partial(RegionKey region, ChunkMask copied, ChunkMask stale) {
	}

	/**
	 * The channel carries its layout version in its name, and the number goes up whenever a field
	 * is added or moved.
	 *
	 * <p>Two mod versions that disagree about the fields cannot both read this, and the way that
	 * fails is ugly: the older reader stops early, netty finds bytes it did not expect and drops
	 * the connection, so the player is kicked every time the server sends an update. With the
	 * version in the name a client that does not know this layout never registers the channel,
	 * the server sees it cannot send, and that player simply gets told to update their mod.
	 */
	public static final CustomPacketPayload.Type<RegionStatePayload> TYPE =
			new CustomPacketPayload.Type<>(
					ResourceLocation.fromNamespaceAndPath(RegionCast.MOD_ID, "region_state_v2"));

	public static final StreamCodec<FriendlyByteBuf, RegionStatePayload> CODEC =
			CustomPacketPayload.codec(RegionStatePayload::write, RegionStatePayload::new);

	private RegionStatePayload(FriendlyByteBuf buf) {
		this(readEntries(buf), readRegions(buf), readChunks(buf), readPartials(buf),
				buf.readUtf(), readBackups(buf), buf.readBoolean());
	}

	private static List<ChunkKey> readChunks(FriendlyByteBuf buf) {
		int size = buf.readVarInt();
		List<ChunkKey> list = new ArrayList<>(Math.min(size, 1024));
		for (int i = 0; i < size; i++) {
			list.add(readChunk(buf));
		}
		return list;
	}

	private static List<Partial> readPartials(FriendlyByteBuf buf) {
		int size = Math.min(buf.readVarInt(), MAX_PARTIALS);
		List<Partial> list = new ArrayList<>(size);
		for (int i = 0; i < size; i++) {
			list.add(new Partial(readRegion(buf), readMask(buf), readMask(buf)));
		}
		return list;
	}

	private static ChunkMask readMask(FriendlyByteBuf buf) {
		long[] bits = new long[ChunkMask.LONGS];
		for (int i = 0; i < bits.length; i++) {
			bits[i] = buf.readLong();
		}
		return new ChunkMask(bits);
	}

	private static void writeMask(FriendlyByteBuf buf, ChunkMask mask) {
		for (long word : mask.bits()) {
			buf.writeLong(word);
		}
	}

	static ChunkKey readChunk(FriendlyByteBuf buf) {
		Dim dim = Dim.byIndex(buf.readByte());
		return new ChunkKey(dim, buf.readVarInt(), buf.readVarInt());
	}

	static void writeChunk(FriendlyByteBuf buf, ChunkKey chunk) {
		buf.writeByte(chunk.dim().index());
		buf.writeVarInt(chunk.x());
		buf.writeVarInt(chunk.z());
	}

	private static List<BackupStore.Snapshot> readBackups(FriendlyByteBuf buf) {
		int size = Math.min(buf.readVarInt(), MAX_BACKUPS);
		List<BackupStore.Snapshot> list = new ArrayList<>(size);
		for (int i = 0; i < size; i++) {
			list.add(new BackupStore.Snapshot(
					buf.readUtf(64), buf.readLong(), buf.readVarInt(), buf.readVarInt(), buf.readLong()));
		}
		return list;
	}

	private static List<Entry> readEntries(FriendlyByteBuf buf) {
		int size = buf.readVarInt();
		List<Entry> list = new ArrayList<>(size);
		for (int i = 0; i < size; i++) {
			RegionKey region = readRegion(buf);
			int ordinal = buf.readByte();
			// An unknown status from a newer server is treated as stale rather than dropped:
			// showing a region as needing attention is the safe way to be wrong.
			RegionStatus status = ordinal >= 0 && ordinal < RegionStatus.values().length
					? RegionStatus.values()[ordinal]
					: RegionStatus.STALE;
			list.add(new Entry(region, status));
		}
		return list;
	}

	private static List<RegionKey> readRegions(FriendlyByteBuf buf) {
		int size = buf.readVarInt();
		List<RegionKey> list = new ArrayList<>(size);
		for (int i = 0; i < size; i++) {
			list.add(readRegion(buf));
		}
		return list;
	}

	static RegionKey readRegion(FriendlyByteBuf buf) {
		Dim dim = Dim.byIndex(buf.readByte());
		return new RegionKey(dim, buf.readVarInt(), buf.readVarInt());
	}

	static void writeRegion(FriendlyByteBuf buf, RegionKey region) {
		buf.writeByte(region.dim().index());
		buf.writeVarInt(region.x());
		buf.writeVarInt(region.z());
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeVarInt(entries.size());
		for (Entry entry : entries) {
			writeRegion(buf, entry.region());
			buf.writeByte(entry.status().ordinal());
		}
		buf.writeVarInt(queued.size());
		for (RegionKey region : queued) {
			writeRegion(buf, region);
		}
		buf.writeVarInt(queuedChunks.size());
		for (ChunkKey chunk : queuedChunks) {
			writeChunk(buf, chunk);
		}
		int partialCount = Math.min(partials.size(), MAX_PARTIALS);
		buf.writeVarInt(partialCount);
		for (Partial partial : partials.subList(0, partialCount)) {
			writeRegion(buf, partial.region());
			writeMask(buf, partial.copied());
			writeMask(buf, partial.stale());
		}
		buf.writeUtf(report);
		buf.writeVarInt(Math.min(backups.size(), MAX_BACKUPS));
		for (BackupStore.Snapshot snapshot : backups.subList(0, Math.min(backups.size(), MAX_BACKUPS))) {
			buf.writeUtf(snapshot.id(), 64);
			buf.writeLong(snapshot.takenMillis());
			buf.writeVarInt(snapshot.regions());
			buf.writeVarInt(snapshot.files());
			buf.writeLong(snapshot.bytes());
		}
		buf.writeBoolean(openMap);
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
