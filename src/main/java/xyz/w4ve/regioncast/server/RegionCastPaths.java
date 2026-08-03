package xyz.w4ve.regioncast.server;

import java.nio.file.Path;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Every path the service works with, worked out once from the server and the config.
 *
 * <p>Its own type because there are eight of them and six were already being passed positionally,
 * which is exactly the shape of bug where the queue ends up written over the ledger.
 *
 * @param sourceWorld the world folder to copy from, normally a read only mount
 * @param destinationWorld this server's world folder
 * @param ledgerFile what was copied whole, and when
 * @param queueFile whole regions waiting to be copied
 * @param chunkLedgerFile chunks copied on their own, and when
 * @param chunkQueueFile chunks waiting to be copied
 * @param backupDir where undoable updates are kept, outside the world
 * @param workDir scratch space for chunk copying, emptied as it goes
 */
public record RegionCastPaths(
		Path sourceWorld,
		Path destinationWorld,
		Path ledgerFile,
		Path queueFile,
		Path chunkLedgerFile,
		Path chunkQueueFile,
		Path backupDir,
		Path workDir) {

	/** Works the whole set out from a running server plus its config. */
	public static RegionCastPaths of(MinecraftServer server, RegionCastConfig config) {
		Path world = server.getWorldPath(LevelResource.ROOT).normalize();
		Path configDir = world.resolve("../config").normalize();
		return new RegionCastPaths(
				world.resolve(config.sourceWorld()).normalize(),
				world,
				configDir.resolve("regioncast-ledger.txt"),
				configDir.resolve("regioncast-queue.txt"),
				configDir.resolve("regioncast-chunk-ledger.txt"),
				configDir.resolve("regioncast-chunk-queue.txt"),
				world.resolve(config.backupDir()).normalize(),
				world.resolve(config.backupDir()).normalize().resolve("work"));
	}

	/** Where the config itself lives, the one path needed before there is a config to read. */
	public static Path configFile(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve("../config/regioncast.properties")
				.normalize();
	}
}
