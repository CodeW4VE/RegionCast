package xyz.w4ve.regioncast;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

public final class PermissionChecks {
	private PermissionChecks() {}

	public static boolean allowed(CommandSourceStack source, int level) {
		return switch (level) {
			case 0 -> Commands.LEVEL_ALL.check(source.permissions());
			case 1 -> Commands.LEVEL_MODERATORS.check(source.permissions());
			case 2 -> Commands.LEVEL_GAMEMASTERS.check(source.permissions());
			case 3 -> Commands.LEVEL_ADMINS.check(source.permissions());
			case 4 -> Commands.LEVEL_OWNERS.check(source.permissions());
			default -> false;
		};
	}
}
