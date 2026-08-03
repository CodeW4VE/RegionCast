package xyz.w4ve.regioncast.client.gui;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import xyz.w4ve.regioncast.client.RegionCastClient;
import xyz.w4ve.regioncast.client.map.MapStore;
import xyz.w4ve.regioncast.client.map.MapView;
import xyz.w4ve.regioncast.model.Dim;
import xyz.w4ve.regioncast.model.RegionKey;
import xyz.w4ve.regioncast.model.RegionStatus;
import xyz.w4ve.regioncast.net.RegionCommandPayload;

/**
 * The whole interface: a map you click regions on, and a row of buttons.
 *
 * <p>One screen on purpose. The job is small enough that tabs would be furniture.
 */
public final class RegionCastScreen extends Screen {
	private static final int MARGIN = 8;
	private static final int BAR_HEIGHT = 24;
	private static final int STATUS_HEIGHT = 22;

	/** Kept between openings so the map does not jump back to the player every time. */
	private static final MapView VIEW = new MapView();

	private Button updateButton;
	private Button clearButton;
	private Button backupsButton;
	private boolean dragging;

	public RegionCastScreen() {
		super(Component.literal("RegionCast"));
	}

	private int mapTop() {
		return MARGIN + STATUS_HEIGHT;
	}

	private int mapHeight() {
		return height - mapTop() - BAR_HEIGHT - MARGIN * 2;
	}

	private int mapWidth() {
		return width - MARGIN * 2;
	}

	@Override
	protected void init() {
		VIEW.ensureCentred();
		RegionCastClient.send(RegionCommandPayload.Action.REFRESH);

		int y = height - BAR_HEIGHT - MARGIN / 2;
		int buttonWidth = Math.min(110, (width - MARGIN * 2 - 30) / 6);
		int x = MARGIN;

		addRenderableWidget(Button.builder(Component.literal("Rescan"), button ->
				RegionCastClient.send(RegionCommandPayload.Action.REFRESH))
				.bounds(x, y, buttonWidth, 20).build());
		x += buttonWidth + 6;

		clearButton = addRenderableWidget(Button.builder(Component.literal("Clear queue"), button ->
				RegionCastClient.send(RegionCommandPayload.Action.CLEAR))
				.bounds(x, y, buttonWidth, 20).build());
		x += buttonWidth + 6;

		updateButton = addRenderableWidget(Button.builder(updateLabel(), button -> {
			RegionCastClient.send(RegionCommandPayload.Action.RUN);
			onClose();
		}).bounds(x, y, buttonWidth, 20).build());
		x += buttonWidth + 6;

		backupsButton = addRenderableWidget(Button.builder(Component.literal("Backups"), button ->
				minecraft.setScreen(new BackupsScreen(this)))
				.bounds(x, y, buttonWidth, 20).build());
		x += buttonWidth + 6;

		addRenderableWidget(Button.builder(Component.literal("Redraw"), button ->
				MapStore.redrawLoaded(minecraft))
				.bounds(x, y, buttonWidth, 20).build());
		x += buttonWidth + 6;

		addRenderableWidget(Button.builder(Component.literal("Centre"), button ->
				VIEW.centreOnPlayer())
				.bounds(x, y, buttonWidth, 20).build());
	}

	private Component updateLabel() {
		int queued = RegionCastClient.queued().size();
		return queued == 0
				? Component.literal("Update")
				: Component.literal("Update " + queued).withStyle(ChatFormatting.GOLD);
	}

	/** The dimension we are standing in, or null when it is one we cannot map to a folder. */
	private Dim currentDim() {
		return minecraft == null || minecraft.level == null
				? null
				: Dim.byId(minecraft.level.dimension().location().toString());
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);

		Dim dim = currentDim();
		VIEW.render(graphics, dim, MARGIN, mapTop(), mapWidth(), mapHeight(), mouseX, mouseY);
		graphics.renderOutline(MARGIN - 1, mapTop() - 1, mapWidth() + 2, mapHeight() + 2, 0xFF3A3634);

		drawSummary(graphics, dim);
		drawHover(graphics, dim);

		boolean canUpdate = !RegionCastClient.queued().isEmpty() && RegionCastClient.serverHasMod();
		updateButton.active = canUpdate;
		updateButton.setMessage(updateLabel());
		clearButton.active = !RegionCastClient.queued().isEmpty();
		// Grey when there is nothing to undo, rather than opening an empty list.
		backupsButton.active = !RegionCastClient.backups().isEmpty();
	}

	private void drawSummary(GuiGraphics graphics, Dim dim) {
		if (!RegionCastClient.serverHasMod()) {
			graphics.drawString(font, Component.literal("This server does not run RegionCast")
					.withStyle(ChatFormatting.RED), MARGIN, MARGIN, 0xFFFFFF);
			return;
		}
		if (dim == null) {
			graphics.drawString(font, Component.literal("RegionCast only handles the three vanilla dimensions")
					.withStyle(ChatFormatting.YELLOW), MARGIN, MARGIN, 0xFFFFFF);
			return;
		}

		Component summary = Component.literal("")
				.append(chip(RegionCastClient.count(RegionStatus.STALE) + " out of date", ChatFormatting.RED))
				.append(Component.literal("   "))
				.append(chip(RegionCastClient.count(RegionStatus.NEVER_SYNCED) + " never copied", ChatFormatting.AQUA))
				.append(Component.literal("   "))
				.append(chip(RegionCastClient.queued().size() + " queued", ChatFormatting.GOLD));
		graphics.drawString(font, summary, MARGIN, MARGIN, 0xFFFFFF);

		String report = RegionCastClient.report();
		if (!report.isEmpty()) {
			graphics.drawString(font, Component.literal("last update: " + report)
					.withStyle(ChatFormatting.DARK_GRAY), MARGIN, MARGIN + 11, 0xFFFFFF);
		}
	}

	private static Component chip(String text, ChatFormatting colour) {
		return Component.literal(text).withStyle(colour);
	}

	/** The line under the cursor: which region it is and what is wrong with it. */
	private void drawHover(GuiGraphics graphics, Dim dim) {
		RegionKey region = VIEW.hovered();
		if (region == null || dim == null) {
			return;
		}

		RegionStatus status = RegionCastClient.statusOf(region);
		String state = RegionCastClient.isQueued(region)
				? "queued"
				: switch (status == null ? RegionStatus.IN_SYNC : status) {
					case STALE -> "changed since the last copy";
					case NEVER_SYNCED -> "never copied";
					case GONE -> "gone from the source";
					case IN_SYNC -> "up to date";
				};

		Component line = Component.literal(region.fileName() + "  ")
				.append(Component.literal(state).withStyle(ChatFormatting.GRAY))
				.append(Component.literal("   " + region.minBlockX() + ", " + region.minBlockZ()
						+ " to " + region.maxBlockX() + ", " + region.maxBlockZ())
						.withStyle(ChatFormatting.DARK_GRAY));

		int y = mapTop() + mapHeight() + 5;
		graphics.drawString(font, line, MARGIN, y, 0xFFFFFF);
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (super.mouseClicked(mouseX, mouseY, button)) {
			return true;
		}

		Dim dim = currentDim();
		RegionKey region = VIEW.regionAt(dim, mouseX, mouseY, MARGIN, mapTop(), mapWidth(), mapHeight());

		if (region == null) {
			return false;
		}

		if (button == 0) {
			// Left click toggles, so picking and unpicking are the same gesture.
			RegionCastClient.send(
					RegionCastClient.isQueued(region)
							? RegionCommandPayload.Action.REMOVE
							: RegionCommandPayload.Action.ADD,
					List.of(region));
			return true;
		}

		if (button == 1 || button == 2) {
			dragging = true;
			return true;
		}

		return false;
	}

	@Override
	public boolean mouseReleased(double mouseX, double mouseY, int button) {
		dragging = false;
		return super.mouseReleased(mouseX, mouseY, button);
	}

	@Override
	public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
		if (dragging) {
			VIEW.drag(dragX, dragY);
			return true;
		}
		return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (mouseX >= MARGIN && mouseX <= MARGIN + mapWidth()
				&& mouseY >= mapTop() && mouseY <= mapTop() + mapHeight()) {
			VIEW.zoomBy(scrollY, mouseX, mouseY, MARGIN, mapTop(), mapWidth(), mapHeight());
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean isPauseScreen() {
		// Staying unpaused matters on a server anyway, and it keeps the map filling in while open.
		return false;
	}
}
