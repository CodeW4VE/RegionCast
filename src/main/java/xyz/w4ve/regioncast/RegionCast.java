package xyz.w4ve.regioncast;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.w4ve.regioncast.net.RegionCommandPayload;
import xyz.w4ve.regioncast.net.RegionStatePayload;
import xyz.w4ve.regioncast.server.RegionCastCommand;
import xyz.w4ve.regioncast.server.RegionCastConfig;
import xyz.w4ve.regioncast.server.RegionCastPaths;
import xyz.w4ve.regioncast.server.UpdateService;

/**
 * Server side entry point: owns the update service and answers the map.
 *
 * <p>A client without RegionCast never registers the channels and never hears from any of this,
 * so the mod is safe to run on a server people join with a plain client.
 */
public final class RegionCast implements ModInitializer {
	public static final String MOD_ID = "regioncast";
	public static final Logger LOGGER = LoggerFactory.getLogger("RegionCast");

	private static UpdateService service;
	/** Ticks left before the server goes down for queued work, or -1 when nothing is armed. */
	private static int countdownTicks = -1;
	/** What the countdown is for, so players are told whether regions are coming or going back. */
	private static String countdownReason = "update regions";

	public static UpdateService service() {
		return service;
	}

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.playS2C().register(RegionStatePayload.TYPE, RegionStatePayload.CODEC);
		PayloadTypeRegistry.playC2S().register(RegionCommandPayload.TYPE, RegionCommandPayload.CODEC);

		ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPED.register(this::onServerStopped);
		ServerTickEvents.END_SERVER_TICK.register(this::onTick);
		ServerPlayNetworking.registerGlobalReceiver(RegionCommandPayload.TYPE, this::onCommandPayload);
		RegionCastCommand.register();
	}

	private void onServerStarted(MinecraftServer server) {
		Path configFile = RegionCastPaths.configFile(server);
		try {
			RegionCastConfig config = RegionCastConfig.loadOrCreate(configFile);
			RegionCastPaths paths = RegionCastPaths.of(server, config);
			service = new UpdateService(config, paths);

			if (!service.sourceAvailable()) {
				LOGGER.warn("Source world not found at {}. Set source_world in {} and mount it.",
						paths.sourceWorld(), configFile);
			} else {
				service.refresh();
				LOGGER.info("Watching {} for regions to pull in", paths.sourceWorld());
			}
		} catch (IOException e) {
			LOGGER.error("RegionCast could not start, the mod will stay idle", e);
			service = null;
		}
	}

	/**
	 * The one moment copying is safe: the world is saved and closed, and this code is still alive.
	 */
	private void onServerStopped(MinecraftServer server) {
		if (service == null || !service.hasPendingWork()) {
			return;
		}
		String report = service.runPending();
		LOGGER.info("Finished on shutdown: {}", report);
		// Last line out. Whatever supervises this server sees it and starts us again.
		LOGGER.info(UpdateService.RESTART_MARKER);
	}

	private void onTick(MinecraftServer server) {
		if (countdownTicks < 0) {
			return;
		}
		countdownTicks--;
		if (countdownTicks % 20 == 0) {
			int seconds = countdownTicks / 20;
			if (seconds > 0) {
				broadcast(server, Component.literal("Server restarting in " + seconds + "s to "
						+ countdownReason).withStyle(ChatFormatting.GOLD));
			}
		}
		if (countdownTicks <= 0) {
			countdownTicks = -1;
			LOGGER.info("Stopping to {}", countdownReason);
			server.halt(false);
		}
	}

	/** Starts the warning countdown that ends in a shutdown, and then the copy. */
	public static void beginUpdate(MinecraftServer server) {
		service.arm();
		int chunks = service.queuedChunks().size();
		countdownReason = "update " + service.queued().size() + " region(s)"
				+ (chunks > 0 ? " and " + chunks + " chunk(s)" : "");
		startCountdown();
	}

	/** Same route as an update: the restore itself happens once the world is closed. */
	public static void beginRestore(MinecraftServer server, String snapshotId) {
		countdownReason = "restore backup " + snapshotId;
		startCountdown();
	}

	private static void startCountdown() {
		countdownTicks = Math.max(1, service.config().restartWarningSeconds()) * 20;
	}

	public static boolean isCountingDown() {
		return countdownTicks >= 0;
	}

	private void onCommandPayload(RegionCommandPayload payload, ServerPlayNetworking.Context context) {
		MinecraftServer server = context.server();
		ServerPlayer player = context.player();
		server.execute(() -> handle(payload, server, player));
	}

	private void handle(RegionCommandPayload payload, MinecraftServer server, ServerPlayer player) {
		if (service == null) {
			player.sendSystemMessage(Component.literal("RegionCast is not set up on this server")
					.withStyle(ChatFormatting.RED));
			return;
		}
		// Checked on every packet: a payload is bytes, and anyone can send bytes.
		if (!player.hasPermissions(service.config().permissionLevel())) {
			LOGGER.warn("{} sent a RegionCast command without permission", player.getGameProfile().getName());
			return;
		}

		switch (payload.action()) {
			case ADD -> service.add(payload.regions());
			case REMOVE -> service.remove(payload.regions());
			case ADD_CHUNKS -> service.addChunks(payload.chunks());
			case REMOVE_CHUNKS -> service.removeChunks(payload.chunks());
			case CLEAR -> service.clear();
			case REFRESH -> {
				try {
					service.refresh();
				} catch (IOException e) {
					player.sendSystemMessage(Component.literal("Scan failed: " + e.getMessage())
							.withStyle(ChatFormatting.RED));
				}
			}
			case RUN -> {
				if (isCountingDown()) {
					return;
				}
				String problem = service.whyNotReady();
				if (problem != null) {
					player.sendSystemMessage(Component.literal("Cannot update: " + problem)
							.withStyle(ChatFormatting.RED));
					return;
				}
				if (service.config().requireEmpty() && server.getPlayerList().getPlayerCount() > 1) {
					player.sendSystemMessage(Component.literal("Cannot update: other players are online")
							.withStyle(ChatFormatting.RED));
					return;
				}
				beginUpdate(server);
			}
			case RESTORE -> {
				if (isCountingDown()) {
					return;
				}
				// Checked before arming, so a refused restore leaves nothing pending behind.
				if (service.config().requireEmpty() && server.getPlayerList().getPlayerCount() > 1) {
					player.sendSystemMessage(Component.literal("Cannot restore: other players are online")
							.withStyle(ChatFormatting.RED));
					return;
				}
				String problem = service.armRestore(payload.argument());
				if (problem != null) {
					player.sendSystemMessage(Component.literal("Cannot restore: " + problem)
							.withStyle(ChatFormatting.RED));
					return;
				}
				beginRestore(server, payload.argument());
			}
			case DELETE_BACKUP -> {
				if (service.backups().delete(payload.argument())) {
					player.sendSystemMessage(Component.literal("Deleted backup " + payload.argument()));
				}
			}
		}
		sendState(player);
	}

	/** Sends the current colours and queue to one player. */
	public static void sendState(ServerPlayer player) {
		if (service == null || !ServerPlayNetworking.canSend(player, RegionStatePayload.TYPE)) {
			return;
		}
		ServerPlayNetworking.send(player, new RegionStatePayload(
				service.entries(), service.queued(), service.queuedChunks(), service.partials(),
				service.lastReport(), service.snapshots()));
	}

	/**
	 * Sends the state and asks the client to open the map with it.
	 *
	 * @return false when this player has no client mod to open anything, so the caller can say so
	 */
	public static boolean sendOpenMap(ServerPlayer player) {
		if (service == null || !ServerPlayNetworking.canSend(player, RegionStatePayload.TYPE)) {
			return false;
		}
		ServerPlayNetworking.send(player, new RegionStatePayload(
				service.entries(), service.queued(), service.queuedChunks(), service.partials(),
				service.lastReport(), service.snapshots(), true));
		return true;
	}

	private static void broadcast(MinecraftServer server, Component message) {
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		for (ServerPlayer player : players) {
			player.sendSystemMessage(message);
		}
		LOGGER.info(message.getString());
	}
}
