package xyz.w4ve.regioncast.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The config file, which is the one file in this mod somebody edits by hand over ssh.
 *
 * <p>No Minecraft classes involved, so it is testable like the rest of the plain logic.
 */
class RegionCastConfigTest {

	@TempDir
	Path dir;

	@Test
	@DisplayName("a missing config is written out with every setting in it")
	void createsTemplate() throws IOException {
		Path file = dir.resolve("regioncast.properties");

		RegionCastConfig config = RegionCastConfig.loadOrCreate(file);

		String text = Files.readString(file, StandardCharsets.UTF_8);
		assertTrue(text.contains("backup_keep=3"), text);
		assertTrue(text.contains("source_world="), text);
		assertEquals(3, config.backupKeep());
	}

	@Test
	@DisplayName("settings added in a later version appear in an older file, at their defaults")
	void topsUpAnOlderFile() throws IOException {
		Path file = dir.resolve("regioncast.properties");
		Files.writeString(file, "source_world=../../smp_world\npermission_level=2\n",
				StandardCharsets.UTF_8);

		RegionCastConfig config = RegionCastConfig.loadOrCreate(file);

		String text = Files.readString(file, StandardCharsets.UTF_8);
		assertTrue(text.contains("backup_keep=3"), "the new setting should have been written in:\n" + text);
		assertEquals(3, config.backupKeep());
	}

	@Test
	@DisplayName("topping up never touches a value somebody set")
	void keepsExistingValues() throws IOException {
		Path file = dir.resolve("regioncast.properties");
		Files.writeString(file, "permission_level=2\nbackup_keep=7\nsource_world=/mnt/other\n",
				StandardCharsets.UTF_8);

		RegionCastConfig config = RegionCastConfig.loadOrCreate(file);

		assertEquals(2, config.permissionLevel());
		assertEquals(7, config.backupKeep());
		assertEquals("/mnt/other", config.sourceWorld());
	}

	@Test
	@DisplayName("a file that already has everything is not rewritten")
	void completeFileIsLeftAlone() throws IOException {
		Path file = dir.resolve("regioncast.properties");
		RegionCastConfig.loadOrCreate(file);
		long writtenAt = Files.getLastModifiedTime(file).toMillis();

		RegionCastConfig.loadOrCreate(file);

		assertEquals(writtenAt, Files.getLastModifiedTime(file).toMillis());
	}

	@Test
	@DisplayName("backups off is a real setting, not a broken one")
	void backupsCanBeSwitchedOff() throws IOException {
		Path file = dir.resolve("regioncast.properties");
		Files.writeString(file, "backup_keep=0\n", StandardCharsets.UTF_8);

		assertEquals(0, RegionCastConfig.loadOrCreate(file).backupKeep());
	}

	@Test
	@DisplayName("nonsense in a number field falls back rather than stopping the server")
	void survivesGarbage() throws IOException {
		Path file = dir.resolve("regioncast.properties");
		Files.writeString(file, "backup_keep=lots\npermission_level=\n", StandardCharsets.UTF_8);

		RegionCastConfig config = RegionCastConfig.loadOrCreate(file);

		assertEquals(3, config.backupKeep());
		assertEquals(3, config.permissionLevel());
	}
}
