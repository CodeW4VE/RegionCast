package xyz.w4ve.regioncast;

import net.minecraft.commands.CommandSourceStack;

public final class PermissionChecks {
	private PermissionChecks() {}

	public static boolean allowed(CommandSourceStack source, int level) {
		return level >= 0 && level <= 4 && source.hasPermission(level);
	}
}
