package xyz.w4ve.regioncast.client.gui;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import xyz.w4ve.regioncast.client.RegionCastClient;
import xyz.w4ve.regioncast.client.map.MapStore;
import xyz.w4ve.regioncast.client.map.MapView;
import xyz.w4ve.regioncast.model.ChunkKey;
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
				minecraft.setScreenAndShow(new BackupsScreen(this)))
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

	/** Everything picked, regions and loose chunks alike. What Update is going to act on. */
	private static int pickedCount() {
		return RegionCastClient.queued().size() + RegionCastClient.queuedChunks().size();
	}

	private Component updateLabel() {
		int queued = pickedCount();
		return queued == 0
				? Component.literal("Update")
				: Component.literal("Update " + queued).withStyle(ChatFormatting.GOLD);
	}

	/** The dimension we are standing in, or null when it is one we cannot map to a folder. */
	private Dim currentDim() {
		return minecraft == null || minecraft.level == null
				? null
				: Dim.byId(minecraft.level.dimension().identifier().toString());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(graphics, mouseX, mouseY, partialTick);

		Dim dim = currentDim();
		VIEW.render(graphics, dim, MARGIN, mapTop(), mapWidth(), mapHeight(), mouseX, mouseY);
		graphics.outline(MARGIN - 1, mapTop() - 1, mapWidth() + 2, mapHeight() + 2, 0xFF3A3634);

		drawSummary(graphics, dim);
		drawHover(graphics, dim);

		boolean canUpdate = pickedCount() > 0 && RegionCastClient.serverHasMod();
		updateButton.active = canUpdate;
		updateButton.setMessage(updateLabel());
		clearButton.active = pickedCount() > 0;
		// Grey when there is nothing to undo, rather than opening an empty list.
		backupsButton.active = !RegionCastClient.backups().isEmpty();
	}

	private void drawSummary(GuiGraphicsExtractor graphics, Dim dim) {
		if (!RegionCastClient.serverHasMod()) {
			graphics.text(font, Component.literal("This server does not run RegionCast")
					.withStyle(ChatFormatting.RED), MARGIN, MARGIN, 0xFFFFFFFF);
			return;
		}
		if (dim == null) {
			graphics.text(font, Component.literal("RegionCast only handles the three vanilla dimensions")
					.withStyle(ChatFormatting.YELLOW), MARGIN, MARGIN, 0xFFFFFFFF);
			return;
		}

		Component summary = Component.literal("")
				.append(chip(RegionCastClient.count(RegionStatus.STALE) + " out of date", ChatFormatting.RED))
				.append(Component.literal("   "))
				.append(chip(RegionCastClient.count(RegionStatus.NEVER_SYNCED) + " never copied", ChatFormatting.AQUA))
				.append(Component.literal("   "))
				.append(chip(pickedCount() + " queued", ChatFormatting.GOLD));
		graphics.text(font, summary, MARGIN, MARGIN, 0xFFFFFFFF);

		String report = RegionCastClient.report();
		if (!report.isEmpty()) {
			graphics.text(font, Component.literal("last update: " + report)
					.withStyle(ChatFormatting.DARK_GRAY), MARGIN, MARGIN + 11, 0xFFFFFFFF);
		}
	}

	private static Component chip(String text, ChatFormatting colour) {
		return Component.literal(text).withStyle(colour);
	}

	/** The line under the cursor: what you are pointing at and what is wrong with it. */
	private void drawHover(GuiGraphicsExtractor graphics, Dim dim) {
		RegionKey region = VIEW.hovered();
		if (region == null || dim == null) {
			return;
		}

		int y = mapTop() + mapHeight() + 5;
		ChunkKey chunk = VIEW.hoveredChunk();
		if (chunk != null) {
			// Says chunk, so it is obvious the click has stopped meaning the whole region.
			String state = RegionCastClient.isQueued(chunk)
					? "queued"
					: RegionCastClient.isCopied(chunk)
							? (RegionCastClient.isChunkStale(chunk)
									? "copied, source changed since"
									: "copied on its own")
							: "click to queue this chunk";

			Component line = Component.literal("chunk " + chunk.x() + ", " + chunk.z() + "  ")
					.append(Component.literal(state).withStyle(ChatFormatting.GRAY))
					.append(Component.literal("   in " + region.fileName()
							+ "   " + chunk.minBlockX() + ", " + chunk.minBlockZ())
							.withStyle(ChatFormatting.DARK_GRAY));
			graphics.text(font, line, MARGIN, y, 0xFFFFFFFF);
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

		graphics.text(font, line, MARGIN, y, 0xFFFFFFFF);
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
		double mouseX = event.x(), mouseY = event.y();
		int button = event.button();
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}

		Dim dim = currentDim();
		RegionKey region = VIEW.regionAt(dim, mouseX, mouseY, MARGIN, mapTop(), mapWidth(), mapHeight());

		if (region == null) {
			return false;
		}

		if (button == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) {
			// Left click toggles, so picking and unpicking are the same gesture. What it toggles
			// depends on how close you are: zoomed in, a chunk; zoomed out, the whole region.
			ChunkKey chunk = VIEW.chunkMode()
					? VIEW.chunkAt(dim, mouseX, mouseY, MARGIN, mapTop(), mapWidth(), mapHeight())
					: null;

			if (chunk != null) {
				RegionCastClient.sendChunks(
						RegionCastClient.queuedChunks().contains(chunk)
								? RegionCommandPayload.Action.REMOVE_CHUNKS
								: RegionCommandPayload.Action.ADD_CHUNKS,
						List.of(chunk));
				return true;
			}

			RegionCastClient.send(
					RegionCastClient.isQueued(region)
							? RegionCommandPayload.Action.REMOVE
							: RegionCommandPayload.Action.ADD,
					List.of(region));
			return true;
		}

		if (button == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT || button == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_MIDDLE) {
			dragging = true;
			return true;
		}

		return false;
	}

	@Override
	public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
		dragging = false;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dragX, double dragY) {
		if (dragging) {
			VIEW.drag(dragX, dragY);
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
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
