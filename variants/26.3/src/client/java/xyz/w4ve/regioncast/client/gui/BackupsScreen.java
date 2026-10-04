package xyz.w4ve.regioncast.client.gui;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import xyz.w4ve.regioncast.client.RegionCastClient;
import xyz.w4ve.regioncast.net.RegionCommandPayload;
import xyz.w4ve.regioncast.sync.BackupStore;

/**
 * The undo list: the last few updates, and a way to put one back.
 *
 * <p>Rows rather than a dropdown, because there are three of them and the interesting part of
 * each one is the date, not the name.
 */
public final class BackupsScreen extends Screen {
	private static final int MARGIN = 10;
	private static final int ROW_HEIGHT = 26;
	private static final int BUTTON_WIDTH = 70;

	private final Screen parent;

	public BackupsScreen(Screen parent) {
		super(Component.literal("RegionCast backups"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		List<BackupStore.Snapshot> snapshots = RegionCastClient.backups();
		int y = MARGIN + 34;

		for (BackupStore.Snapshot snapshot : snapshots) {
			int right = width - MARGIN;

			addRenderableWidget(Button.builder(Component.literal("Delete"), button -> {
				RegionCastClient.send(RegionCommandPayload.Action.DELETE_BACKUP, snapshot.id());
				// The server answers with a fresh list, so rebuild from whatever it sends back.
				minecraft.setScreenAndShow(new BackupsScreen(parent));
			}).bounds(right - BUTTON_WIDTH, y, BUTTON_WIDTH, 20).build());

			addRenderableWidget(Button.builder(Component.literal("Restore"), button ->
					confirmRestore(snapshot))
					.bounds(right - BUTTON_WIDTH * 2 - 4, y, BUTTON_WIDTH, 20).build());

			y += ROW_HEIGHT;
		}

		addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
				.bounds(width / 2 - 60, height - MARGIN - 20, 120, 20).build());
	}

	/**
	 * Asks first. Restoring throws away what the last update brought in and takes the server down
	 * with it, which is not something to hand to a single click.
	 */
	private void confirmRestore(BackupStore.Snapshot snapshot) {
		minecraft.setScreenAndShow(new ConfirmScreen(
				confirmed -> {
					if (confirmed) {
						RegionCastClient.send(RegionCommandPayload.Action.RESTORE, snapshot.id());
						minecraft.setScreenAndShow(null);
					} else {
						minecraft.setScreenAndShow(this);
					}
				},
				Component.literal("Restore " + snapshot.regions() + " region(s)?"),
				Component.literal("The world goes back to how it was before the update of "
						+ snapshot.when() + ". The server restarts to do it, and what that update "
						+ "brought in is dropped. You can always pull it in again.")
						.withStyle(ChatFormatting.GRAY),
				Component.literal("Restore and restart"),
				Component.literal("Cancel")));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);

		List<BackupStore.Snapshot> snapshots = RegionCastClient.backups();
		graphics.text(font, Component.literal("Backups").withStyle(ChatFormatting.AQUA),
				MARGIN, MARGIN, 0xFFFFFFFF);
		graphics.text(font, Component.literal(
						"Every update sets aside the regions it replaces. Restoring puts one batch back.")
						.withStyle(ChatFormatting.DARK_GRAY),
				MARGIN, MARGIN + 12, 0xFFFFFFFF);

		if (snapshots.isEmpty()) {
			graphics.text(font, Component.literal("No backups yet").withStyle(ChatFormatting.GRAY),
					MARGIN, MARGIN + 36, 0xFFFFFFFF);
			return;
		}

		int y = MARGIN + 34;
		long total = 0L;
		for (BackupStore.Snapshot snapshot : snapshots) {
			// Trimmed to the space left of the two buttons: Minecraft centres button and label text
			// and lets it spill out both sides, which leaves an unreadable stump rather than a cut.
			int room = width - MARGIN * 2 - BUTTON_WIDTH * 2 - 12;
			String line = snapshot.when() + "   " + snapshot.regions() + " region(s)   "
					+ snapshot.sizeText();
			graphics.text(font, font.plainSubstrByWidth(line, room), MARGIN, y + 6, 0xFFFFFFFF);
			total += snapshot.bytes();
			y += ROW_HEIGHT;
		}

		graphics.text(font, Component.literal("using "
						+ BackupStore.sizeText(total) + " on the server")
						.withStyle(ChatFormatting.DARK_GRAY),
				MARGIN, y + 4, 0xFFFFFFFF);
	}

	@Override
	public void onClose() {
		minecraft.setScreenAndShow(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
