package xyz.w4ve.regioncast.net;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import xyz.w4ve.regioncast.RegionCast;
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
 * @param report human readable outcome of the last update, empty when there has not been one
 * @param backups the updates that can still be taken back, newest first. A handful of rows at
 *     most, so they ride along with every state packet instead of needing a channel of their own.
 * @param openMap set only in reply to {@code /rc map}, so one server side command can open a
 *     client side screen. Keeps the whole mod behind a single {@code /rc} instead of needing a
 *     separate client command that would collide with it.
 */
public record RegionStatePayload(List<Entry> entries, List<RegionKey> queued, String report,
		List<BackupStore.Snapshot> backups, boolean openMap)
		implements CustomPacketPayload {

	/** Sanity bound on the backup list coming off the wire. The default keeps three. */
	private static final int MAX_BACKUPS = 64;

	public RegionStatePayload(List<Entry> entries, List<RegionKey> queued, String report,
			List<BackupStore.Snapshot> backups) {
		this(entries, queued, report, backups, false);
	}

	/** One region and why the map should colour it. */
	public record Entry(RegionKey region, RegionStatus status) {
	}

	public static final CustomPacketPayload.Type<RegionStatePayload> TYPE =
			new CustomPacketPayload.Type<>(
					ResourceLocation.fromNamespaceAndPath(RegionCast.MOD_ID, "region_state"));

	public static final StreamCodec<FriendlyByteBuf, RegionStatePayload> CODEC =
			CustomPacketPayload.codec(RegionStatePayload::write, RegionStatePayload::new);

	private RegionStatePayload(FriendlyByteBuf buf) {
		this(readEntries(buf), readRegions(buf), buf.readUtf(), readBackups(buf), buf.readBoolean());
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
