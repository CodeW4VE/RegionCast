package xyz.w4ve.regioncast.server;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import java.io.IOException;
import java.util.List;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import xyz.w4ve.regioncast.RegionCast;
import xyz.w4ve.regioncast.model.ChunkKey;
import xyz.w4ve.regioncast.model.Dim;
import xyz.w4ve.regioncast.model.RegionKey;
import xyz.w4ve.regioncast.model.RegionStatus;
import xyz.w4ve.regioncast.net.RegionStatePayload;
import xyz.w4ve.regioncast.sync.BackupStore;

/**
 * The {@code /rc} command: everything the map can do, for when the map is not open.
 *
 * <p>Also the whole interface for anyone without the client mod, which is why nothing here needs
 * the map to work.
 */
public final class RegionCastCommand {

	private RegionCastCommand() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
				dispatcher.register(literal("rc")
						.requires(source -> source.hasPermission(permissionLevel()))
						.executes(RegionCastCommand::rootCommand)
						.then(literal("status").executes(RegionCastCommand::status))
						.then(literal("map").executes(RegionCastCommand::map))
						.then(literal("here")
								.executes(RegionCastCommand::here)
								.then(literal("chunk").executes(RegionCastCommand::hereChunk)))
						.then(literal("scan").executes(RegionCastCommand::scan))
						.then(literal("list").executes(RegionCastCommand::list))
						.then(literal("clear").executes(RegionCastCommand::clear))
						.then(literal("run").executes(RegionCastCommand::run))
						.then(literal("backups").executes(RegionCastCommand::backups))
						.then(literal("restore")
								.executes(context -> restore(context, 1))
								.then(argument("which", IntegerArgumentType.integer(1))
										.executes(context -> restore(context,
												IntegerArgumentType.getInteger(context, "which")))))
						.then(literal("add")
								.then(argument("dimension", IntegerArgumentType.integer(-1, 1))
										.then(argument("x", IntegerArgumentType.integer())
												.then(argument("z", IntegerArgumentType.integer())
														.executes(RegionCastCommand::addExplicit)))))));
	}

	private static int permissionLevel() {
		UpdateService service = RegionCast.service();
		return service == null ? 3 : service.config().permissionLevel();
	}

	/** Null and a message when the mod did not come up, so every command can bail the same way. */
	private static UpdateService service(CommandSourceStack source) {
		UpdateService service = RegionCast.service();
		if (service == null) {
			source.sendFailure(Component.literal("RegionCast is not set up on this server"));
		}
		return service;
	}

	/**
	 * Plain {@code /rc}: the map for anyone who can open it, the status text for everyone else.
	 *
	 * <p>The map is what you want nine times out of ten, so it should not need a second word. The
	 * console and anybody without the client mod still get something useful out of the same
	 * command rather than an error about a screen they cannot open.
	 */
	private static int rootCommand(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		if (RegionCast.service() != null
				&& source.getEntity() instanceof ServerPlayer player
				&& RegionCast.sendOpenMap(player)) {
			return 1;
		}
		return status(context);
	}

	private static int status(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		UpdateService service = service(source);
		if (service == null) {
			return 0;
		}
		int stale = 0;
		int never = 0;
		int gone = 0;
		for (RegionStatePayload.Entry entry : service.entries()) {
			switch (entry.status()) {
				case STALE -> stale++;
				case NEVER_SYNCED -> never++;
				case GONE -> gone++;
				default -> {
				}
			}
		}
		String sourceProblem = service.sourceProblem();
		source.sendSuccess(() -> Component.literal("RegionCast").withStyle(ChatFormatting.AQUA), false);
		source.sendSuccess(() -> Component.literal("  source: " + service.sourceWorld()
				+ (sourceProblem == null ? "" : "  (UNUSABLE)")), false);
		if (sourceProblem != null) {
			source.sendSuccess(() -> Component.literal("  " + sourceProblem)
					.withStyle(ChatFormatting.RED), false);
		}
		int finalStale = stale;
		int finalNever = never;
		int finalGone = gone;
		source.sendSuccess(() -> Component.literal("  out of date: " + finalStale
				+ "   never copied: " + finalNever
				+ "   gone from source: " + finalGone), false);
		source.sendSuccess(() -> Component.literal("  queued: " + service.queued().size()
				+ " region(s), " + service.queuedChunks().size() + " chunk(s)"), false);
		BackupStore.Snapshot latest = service.backups().latest();
		source.sendSuccess(() -> Component.literal("  backups: " + (service.backups().enabled()
				? service.snapshots().size() + " of " + service.backups().keep() + " kept"
						+ (latest == null ? "" : ", newest " + latest.when()) + "   /rc backups"
				: "off")), false);
		if (!service.lastReport().isEmpty()) {
			source.sendSuccess(() -> Component.literal("  last update: " + service.lastReport()), false);
		}
		return 1;
	}

	/**
	 * Opens the map on the player's client.
	 *
	 * <p>Done from the server so there is one {@code /rc} and not a client side command sitting
	 * next to it under the same name. A player without the client mod gets told so instead of
	 * nothing happening.
	 */
	private static int map(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context)
			throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		UpdateService service = service(source);
		if (service == null) {
			return 0;
		}
		ServerPlayer player = source.getPlayerOrException();
		if (!RegionCast.sendOpenMap(player)) {
			source.sendFailure(Component.literal(
					"You need the RegionCast client mod, and one as new as the server's, to open"
					+ " the map. Everything else works with /rc"));
			return 0;
		}
		return 1;
	}

	private static int here(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context)
			throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		UpdateService service = service(source);
		if (service == null) {
			return 0;
		}
		ServerPlayer player = source.getPlayerOrException();
		String dimensionId = player.level().dimension().location().toString();
		Dim dim = Dim.byId(dimensionId);
		if (dim == null) {
			source.sendFailure(Component.literal("RegionCast only handles the three vanilla dimensions, not "
					+ dimensionId));
			return 0;
		}
		RegionKey region = RegionKey.ofBlock(dim, player.getBlockX(), player.getBlockZ());
		service.add(List.of(region));
		source.sendSuccess(() -> Component.literal("Queued " + region).withStyle(ChatFormatting.GREEN), false);
		RegionCast.sendState(player);
		return 1;
	}

	/** The same, but only the 16 by 16 you are standing on rather than the whole region file. */
	private static int hereChunk(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context)
			throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		CommandSourceStack source = context.getSource();
		UpdateService service = service(source);
		if (service == null) {
			return 0;
		}
		ServerPlayer player = source.getPlayerOrException();
		String dimensionId = player.level().dimension().location().toString();
		Dim dim = Dim.byId(dimensionId);
		if (dim == null) {
			source.sendFailure(Component.literal("RegionCast only handles the three vanilla dimensions, not "
					+ dimensionId));
			return 0;
		}
		ChunkKey chunk = ChunkKey.ofBlock(dim, player.getBlockX(), player.getBlockZ());
		service.addChunks(List.of(chunk));
		source.sendSuccess(() -> Component.literal("Queued " + chunk).withStyle(ChatFormatting.GREEN), false);
		RegionCast.sendState(player);
		return 1;
	}

	private static int addExplicit(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		UpdateService service = service(source);
		if (service == null) {
			return 0;
		}
		Dim dim = Dim.byIndex(IntegerArgumentType.getInteger(context, "dimension"));
		RegionKey region = new RegionKey(dim,
				IntegerArgumentType.getInteger(context, "x"),
				IntegerArgumentType.getInteger(context, "z"));
		service.add(List.of(region));
		source.sendSuccess(() -> Component.literal("Queued " + region).withStyle(ChatFormatting.GREEN), false);
		return 1;
	}

	private static int scan(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		UpdateService service = service(source);
		if (service == null) {
			return 0;
		}
		try {
			List<RegionStatePayload.Entry> entries = service.refresh();
			long stale = entries.stream().filter(e -> e.status() == RegionStatus.STALE).count();
			source.sendSuccess(() -> Component.literal("Scanned the source: " + stale + " region(s) changed since the last copy")
					.withStyle(ChatFormatting.AQUA), false);
		} catch (IOException e) {
			source.sendFailure(Component.literal("Scan failed: " + e.getMessage()));
			return 0;
		}
		if (source.getEntity() instanceof ServerPlayer player) {
			RegionCast.sendState(player);
		}
		return 1;
	}

	private static int list(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		UpdateService service = service(source);
		if (service == null) {
			return 0;
		}
		List<RegionKey> queued = service.queued();
		List<ChunkKey> chunks = service.queuedChunks();
		if (queued.isEmpty() && chunks.isEmpty()) {
			source.sendSuccess(() -> Component.literal("The queue is empty"), false);
			return 1;
		}
		if (!queued.isEmpty()) {
			source.sendSuccess(() -> Component.literal(queued.size() + " region(s) queued:")
					.withStyle(ChatFormatting.AQUA), false);
			for (RegionKey region : queued) {
				source.sendSuccess(() -> Component.literal("  " + region), false);
			}
		}
		if (!chunks.isEmpty()) {
			source.sendSuccess(() -> Component.literal(chunks.size() + " chunk(s) queued:")
					.withStyle(ChatFormatting.AQUA), false);
			for (ChunkKey chunk : chunks) {
				source.sendSuccess(() -> Component.literal("  " + chunk
						+ "  in " + chunk.region().fileName()), false);
			}
		}
		return 1;
	}

	private static int clear(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		UpdateService service = service(source);
		if (service == null) {
			return 0;
		}
		service.clear();
		source.sendSuccess(() -> Component.literal("Queue emptied"), false);
		if (source.getEntity() instanceof ServerPlayer player) {
			RegionCast.sendState(player);
		}
		return 1;
	}

	/** The updates that can still be taken back, newest first, numbered the way restore wants them. */
	private static int backups(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		UpdateService service = service(source);
		if (service == null) {
			return 0;
		}
		List<BackupStore.Snapshot> snapshots = service.snapshots();
		if (snapshots.isEmpty()) {
			source.sendSuccess(() -> Component.literal(service.backups().enabled()
					? "No backups yet. One is taken every time an update runs."
					: "Backups are off (backup_keep=0 in regioncast.properties)"), false);
			return 1;
		}
		source.sendSuccess(() -> Component.literal(snapshots.size() + " backup(s), newest first:")
				.withStyle(ChatFormatting.AQUA), false);
		for (int i = 0; i < snapshots.size(); i++) {
			BackupStore.Snapshot snapshot = snapshots.get(i);
			int number = i + 1;
			source.sendSuccess(() -> Component.literal("  " + number + ". " + snapshot.when() + "  ")
					.append(Component.literal(snapshot.regions() + " region(s), " + snapshot.sizeText())
							.withStyle(ChatFormatting.GRAY))
					.append(Component.literal("  /rc restore " + number).withStyle(ChatFormatting.DARK_GRAY)),
					false);
		}
		return 1;
	}

	/**
	 * Puts one of them back. Takes the position in that list rather than the folder name, since
	 * "the last one" is what anybody actually wants at the moment they need this.
	 */
	private static int restore(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
			int which) {
		CommandSourceStack source = context.getSource();
		UpdateService service = service(source);
		if (service == null) {
			return 0;
		}
		if (RegionCast.isCountingDown()) {
			source.sendFailure(Component.literal("The server is already on its way down"));
			return 0;
		}
		List<BackupStore.Snapshot> snapshots = service.snapshots();
		if (which > snapshots.size()) {
			source.sendFailure(Component.literal(snapshots.isEmpty()
					? "There are no backups to restore"
					: "There are only " + snapshots.size() + " backup(s), see /rc backups"));
			return 0;
		}
		BackupStore.Snapshot snapshot = snapshots.get(which - 1);
		String problem = service.armRestore(snapshot.id());
		if (problem != null) {
			source.sendFailure(Component.literal("Cannot restore: " + problem));
			return 0;
		}
		source.sendSuccess(() -> Component.literal("Restoring " + snapshot.regions()
				+ " region(s) from " + snapshot.when() + ", the server will restart")
				.withStyle(ChatFormatting.GOLD), true);
		RegionCast.beginRestore(source.getServer(), snapshot.id());
		return 1;
	}

	private static int run(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		UpdateService service = service(source);
		if (service == null) {
			return 0;
		}
		if (RegionCast.isCountingDown()) {
			source.sendFailure(Component.literal("An update is already on its way"));
			return 0;
		}
		String problem = service.whyNotReady();
		if (problem != null) {
			source.sendFailure(Component.literal("Cannot update: " + problem));
			return 0;
		}
		int regions = service.queued().size();
		int chunks = service.queuedChunks().size();
		source.sendSuccess(() -> Component.literal("Updating " + regions + " region(s)"
				+ (chunks > 0 ? " and " + chunks + " chunk(s)" : "") + ", the server will restart")
				.withStyle(ChatFormatting.GOLD), true);
		RegionCast.beginUpdate(source.getServer());
		return 1;
	}
}
