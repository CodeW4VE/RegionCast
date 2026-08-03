package xyz.w4ve.regioncast.server;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the source world is, and how to ask the source server to hold still.
 *
 * <p>Plain {@code key=value} text so it can be edited over ssh without a JSON library and without
 * counting brackets. Unknown keys are kept on rewrite, so a config from a newer version is not
 * quietly destroyed by an older one.
 */
public final class RegionCastConfig {
	/** Default source: a read only bind mount of the other server's world, next to ours. */
	public static final String DEFAULT_SOURCE = "../source_world";
	/** Default backup folder: beside the world, never inside it, so Minecraft never walks into it. */
	public static final String DEFAULT_BACKUP_DIR = "../regioncast-backups";
	/** How many updates are undoable out of the box. */
	public static final int DEFAULT_BACKUP_KEEP = 3;

	private static final String TEMPLATE = """
			# RegionCast: pulls region files from another server's world into this one.
			#
			# source_world: path to the world folder to copy FROM, relative to the server
			#   working directory or absolute. Mount it read only; this mod never writes there.
			# rcon_*: the source server's RCON, used to run save-off before copying and save-on
			#   after. Leave rcon_host empty to skip it, at the risk of copying a region file
			#   while the source is midway through writing it.
			# permission_level: vanilla operator level needed to open the map and queue regions.
			# require_empty: refuse to run an update while players are online here.
			# backup_keep: how many updates stay undoable. Each one holds the region files it
			#   replaced, so a batch of 60 regions costs roughly the size of those 60 regions.
			#   Set it to 0 to never back anything up, and remember that an update cannot then
			#   be taken back at all.
			# backup_dir: where those snapshots live, relative to the world folder or absolute.
			#   Keep it OUTSIDE the world folder.
			""";

	private final Map<String, String> values = new HashMap<>();
	private final List<String> comments = new ArrayList<>();

	public String sourceWorld() {
		return get("source_world", DEFAULT_SOURCE);
	}

	public String rconHost() {
		return get("rcon_host", "");
	}

	public int rconPort() {
		return getInt("rcon_port", 25575);
	}

	public String rconPassword() {
		return get("rcon_password", "");
	}

	public int rconTimeoutMillis() {
		return getInt("rcon_timeout_millis", 5000);
	}

	/** True when we have enough to ask the source to stop saving. */
	public boolean hasRcon() {
		return !rconHost().isBlank() && !rconPassword().isBlank();
	}

	public int permissionLevel() {
		return getInt("permission_level", 3);
	}

	public boolean requireEmpty() {
		return getBoolean("require_empty", false);
	}

	/** How many updates can be taken back. Zero switches backups off entirely. */
	public int backupKeep() {
		return Math.max(0, getInt("backup_keep", DEFAULT_BACKUP_KEEP));
	}

	public String backupDir() {
		return get("backup_dir", DEFAULT_BACKUP_DIR);
	}

	/** Seconds of warning before the server goes down for an update. */
	public int restartWarningSeconds() {
		return getInt("restart_warning_seconds", 10);
	}

	private String get(String key, String fallback) {
		String value = values.get(key);
		return value == null ? fallback : value;
	}

	private int getInt(String key, int fallback) {
		try {
			return Integer.parseInt(get(key, String.valueOf(fallback)).trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	private boolean getBoolean(String key, boolean fallback) {
		String value = get(key, String.valueOf(fallback)).trim();
		return value.equalsIgnoreCase("true") || value.equals("1");
	}

	/** Loads the config, writing a commented template first if there is not one yet. */
	public static RegionCastConfig loadOrCreate(Path file) throws IOException {
		RegionCastConfig config = new RegionCastConfig();
		if (!Files.isRegularFile(file)) {
			config.values.put("source_world", DEFAULT_SOURCE);
			config.values.put("rcon_host", "");
			config.values.put("rcon_port", "25575");
			config.values.put("rcon_password", "");
			config.values.put("permission_level", "3");
			config.values.put("require_empty", "false");
			config.values.put("backup_keep", String.valueOf(DEFAULT_BACKUP_KEEP));
			config.values.put("backup_dir", DEFAULT_BACKUP_DIR);
			config.save(file);
			return config;
		}
		for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
			String trimmed = line.trim();
			if (trimmed.isEmpty() || trimmed.startsWith("#")) {
				config.comments.add(line);
				continue;
			}
			int equals = trimmed.indexOf('=');
			if (equals <= 0) {
				continue;
			}
			config.values.put(
					trimmed.substring(0, equals).trim(),
					trimmed.substring(equals + 1).trim());
		}
		return config;
	}

	public void save(Path file) throws IOException {
		Path parent = file.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		StringBuilder text = new StringBuilder(TEMPLATE);
		values.entrySet().stream()
				.sorted(Map.Entry.comparingByKey())
				.forEach(entry -> text.append(entry.getKey()).append('=').append(entry.getValue()).append('\n'));
		Files.writeString(file, text.toString(), StandardCharsets.UTF_8);
	}
}
