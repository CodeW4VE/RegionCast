package xyz.w4ve.regioncast.client;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.w4ve.regioncast.client.gui.RegionCastScreen;
import xyz.w4ve.regioncast.client.map.MapStore;
import xyz.w4ve.regioncast.model.ChunkKey;
import xyz.w4ve.regioncast.model.RegionKey;
import xyz.w4ve.regioncast.model.RegionStatus;
import xyz.w4ve.regioncast.net.RegionCommandPayload;
import xyz.w4ve.regioncast.net.RegionStatePayload;
import xyz.w4ve.regioncast.sync.BackupStore;

/**
 * Client entry point: keeps whatever the server last told us, so the map can paint without asking.
 *
 * <p>Nothing here assumes the server has RegionCast. Until a state packet arrives the map simply
 * has no colours, which is exactly how it should look on a server that does not run the mod.
 */
public final class RegionCastClient implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("RegionCast");

	/** Colours the server has told us about. Empty on a server without the mod. */
	private static final Map<RegionKey, RegionStatus> STATUS = new HashMap<>();
	/** Queue as the server sees it, in order. */
	private static final Set<RegionKey> QUEUED = new LinkedHashSet<>();
	/** Chunks picked one by one, waiting to be copied. */
	private static final Set<ChunkKey> QUEUED_CHUNKS = new LinkedHashSet<>();
	/** Regions holding chunks that came across on their own, keyed for lookup while painting. */
	private static final Map<RegionKey, RegionStatePayload.Partial> PARTIALS = new HashMap<>();
	/** Updates that can still be taken back, newest first. */
	private static List<BackupStore.Snapshot> backups = List.of();

	private static String report = "";
	private static boolean serverHasMod;

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(RegionStatePayload.TYPE, (payload, context) ->
				context.client().execute(() -> accept(payload)));

		// A fresh world means a fresh server: forget everything rather than showing the last
		// server's colours over this one's terrain.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(() -> {
			reset();
			MapStore.closeAll();
		}));

		// A chunk that just arrived is drawn before the sweep gets to it, otherwise the chunk you
		// are walking into shows up long after the ones beside it.
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) ->
				MapStore.onChunkLoaded(chunk.getPos().x(), chunk.getPos().z()));

		ClientTickEvents.END_CLIENT_TICK.register(MapStore::tick);
	}

	private static void accept(RegionStatePayload payload) {
		serverHasMod = true;
		STATUS.clear();
		for (RegionStatePayload.Entry entry : payload.entries()) {
			STATUS.put(entry.region(), entry.status());
		}
		QUEUED.clear();
		QUEUED.addAll(payload.queued());
		QUEUED_CHUNKS.clear();
		QUEUED_CHUNKS.addAll(payload.queuedChunks());
		PARTIALS.clear();
		for (RegionStatePayload.Partial partial : payload.partials()) {
			PARTIALS.put(partial.region(), partial);
		}
		backups = payload.backups();
		report = payload.report();

		// /rc map asked for the screen. The state it needs is already in place by this point.
		if (payload.openMap()) {
			Minecraft client = Minecraft.getInstance();
			if (client.gui.screen() == null) {
				client.setScreenAndShow(new RegionCastScreen());
			}
		}
	}

	private static void reset() {
		STATUS.clear();
		QUEUED.clear();
		QUEUED_CHUNKS.clear();
		PARTIALS.clear();
		backups = List.of();
		report = "";
		serverHasMod = false;
	}

	public static List<BackupStore.Snapshot> backups() {
		return backups;
	}

	public static Set<ChunkKey> queuedChunks() {
		return QUEUED_CHUNKS;
	}

	public static boolean isQueued(ChunkKey chunk) {
		// A chunk whose whole region is queued is coming across either way, and the map should
		// say so rather than leaving it looking unpicked inside a picked region.
		return QUEUED_CHUNKS.contains(chunk) || QUEUED.contains(chunk.region());
	}

	/** True when this chunk was pulled across on its own at some point. */
	public static boolean isCopied(ChunkKey chunk) {
		RegionStatePayload.Partial partial = PARTIALS.get(chunk.region());
		return partial != null && partial.copied().get(chunk);
	}

	/** True when the source has changed since that chunk was copied. */
	public static boolean isChunkStale(ChunkKey chunk) {
		RegionStatePayload.Partial partial = PARTIALS.get(chunk.region());
		return partial != null && partial.stale().get(chunk);
	}

	/** Whether this region has any loose chunks at all, so the map can skip the rest quickly. */
	public static boolean hasCopiedChunks(RegionKey region) {
		return PARTIALS.containsKey(region);
	}

	/** What the server thinks of this region, or null when it had nothing to say about it. */
	public static RegionStatus statusOf(RegionKey region) {
		return STATUS.get(region);
	}

	public static boolean isQueued(RegionKey region) {
		return QUEUED.contains(region);
	}

	public static Set<RegionKey> queued() {
		return QUEUED;
	}

	public static String report() {
		return report;
	}

	/** False until the server sends its first state packet, which only a server with the mod does. */
	public static boolean serverHasMod() {
		return serverHasMod;
	}

	/** Counts how many regions carry one status, for the summary line in the GUI. */
	public static int count(RegionStatus status) {
		return (int) STATUS.values().stream().filter(s -> s == status).count();
	}

	public static void send(RegionCommandPayload.Action action, List<RegionKey> regions) {
		if (ClientPlayNetworking.canSend(RegionCommandPayload.TYPE)) {
			ClientPlayNetworking.send(new RegionCommandPayload(action, regions));
		}
	}

	public static void send(RegionCommandPayload.Action action) {
		send(action, List.of());
	}

	/** For the actions that name a backup instead of a set of regions. */
	public static void send(RegionCommandPayload.Action action, String argument) {
		if (ClientPlayNetworking.canSend(RegionCommandPayload.TYPE)) {
			ClientPlayNetworking.send(RegionCommandPayload.of(action, argument));
		}
	}

	/** For the two actions that work on chunks rather than whole regions. */
	public static void sendChunks(RegionCommandPayload.Action action, List<ChunkKey> chunks) {
		if (ClientPlayNetworking.canSend(RegionCommandPayload.TYPE)) {
			ClientPlayNetworking.send(RegionCommandPayload.ofChunks(action, chunks));
		}
	}
}
