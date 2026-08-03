package xyz.w4ve.regioncast.net;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import xyz.w4ve.regioncast.RegionCast;
import xyz.w4ve.regioncast.model.ChunkKey;
import xyz.w4ve.regioncast.model.RegionKey;

/**
 * What the map asks the server to do: everything the GUI can trigger, in one packet.
 *
 * <p>The server never trusts this. Permission is checked on arrival, every time, because a
 * payload is just bytes anyone can send.
 *
 * @param action what to do
 * @param argument the backup id for the actions about backups, empty for everything else
 * @param regions which regions it applies to, empty for the actions that do not need any
 * @param chunks which chunks it applies to, for the two actions that work at chunk level
 */
public record RegionCommandPayload(Action action, String argument, List<RegionKey> regions,
		List<ChunkKey> chunks)
		implements CustomPacketPayload {

	/** Longest backup id we will read off the wire. Ours are 15 characters. */
	private static final int MAX_ARGUMENT = 64;

	/** Everything the map can ask for. */
	public enum Action {
		/** Put these regions in the queue. */
		ADD,
		/** Take these regions out of the queue. */
		REMOVE,
		/** Put these chunks in the queue, for a selection made at close zoom. */
		ADD_CHUNKS,
		/** Take these chunks out of the queue. */
		REMOVE_CHUNKS,
		/** Empty the queue. */
		CLEAR,
		/** Rescan the source and send fresh colours back. */
		REFRESH,
		/** Copy everything in the queue, then restart. The one that actually does something. */
		RUN,
		/** Put the backup named in {@code argument} back, then restart. */
		RESTORE,
		/** Delete the backup named in {@code argument}, for when the disk matters more. */
		DELETE_BACKUP
	}

	public RegionCommandPayload(Action action, List<RegionKey> regions) {
		this(action, "", regions, List.of());
	}

	/** The chunk level equivalent, for {@link Action#ADD_CHUNKS} and {@link Action#REMOVE_CHUNKS}. */
	public static RegionCommandPayload ofChunks(Action action, List<ChunkKey> chunks) {
		return new RegionCommandPayload(action, "", List.of(), chunks);
	}

	public static final CustomPacketPayload.Type<RegionCommandPayload> TYPE =
			new CustomPacketPayload.Type<>(
					ResourceLocation.fromNamespaceAndPath(RegionCast.MOD_ID, "region_command"));

	public static final StreamCodec<FriendlyByteBuf, RegionCommandPayload> CODEC =
			CustomPacketPayload.codec(RegionCommandPayload::write, RegionCommandPayload::new);

	public static RegionCommandPayload of(Action action) {
		return new RegionCommandPayload(action, "", List.of(), List.of());
	}

	public static RegionCommandPayload of(Action action, String argument) {
		return new RegionCommandPayload(action, argument, List.of(), List.of());
	}

	private RegionCommandPayload(FriendlyByteBuf buf) {
		this(readAction(buf), buf.readUtf(MAX_ARGUMENT), readRegions(buf), readChunks(buf));
	}

	private static List<ChunkKey> readChunks(FriendlyByteBuf buf) {
		int size = buf.readVarInt();
		List<ChunkKey> list = new ArrayList<>(Math.min(size, 4096));
		for (int i = 0; i < size; i++) {
			list.add(RegionStatePayload.readChunk(buf));
		}
		return list;
	}

	private static Action readAction(FriendlyByteBuf buf) {
		int ordinal = buf.readByte();
		if (ordinal < 0 || ordinal >= Action.values().length) {
			// Garbage or a newer client: the harmless action, never RUN.
			return Action.REFRESH;
		}
		return Action.values()[ordinal];
	}

	private static List<RegionKey> readRegions(FriendlyByteBuf buf) {
		int size = buf.readVarInt();
		List<RegionKey> list = new ArrayList<>(Math.min(size, 1024));
		for (int i = 0; i < size; i++) {
			list.add(RegionStatePayload.readRegion(buf));
		}
		return list;
	}

	private void write(FriendlyByteBuf buf) {
		buf.writeByte(action.ordinal());
		buf.writeUtf(argument, MAX_ARGUMENT);
		buf.writeVarInt(regions.size());
		for (RegionKey region : regions) {
			RegionStatePayload.writeRegion(buf, region);
		}
		buf.writeVarInt(chunks.size());
		for (ChunkKey chunk : chunks) {
			RegionStatePayload.writeChunk(buf, chunk);
		}
	}

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
