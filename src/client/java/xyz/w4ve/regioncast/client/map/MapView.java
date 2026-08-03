package xyz.w4ve.regioncast.client.map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import xyz.w4ve.regioncast.client.RegionCastClient;
import xyz.w4ve.regioncast.model.ChunkKey;
import xyz.w4ve.regioncast.model.Dim;
import xyz.w4ve.regioncast.model.RegionKey;
import xyz.w4ve.regioncast.model.RegionStatus;

/**
 * The camera over the tiles, plus the region grid drawn on top of it.
 *
 * <p>The grid is free: a tile is 512 blocks and so is a region, so the lines between tiles are
 * already the lines between regions. Clicking one picks the region with the same index.
 */
public final class MapView {
	/** Green: copied, and the source has not changed since. */
	private static final int COLOUR_IN_SYNC = 0x44DD66;
	/** Red: the source changed after our last copy. The one you are looking for. */
	private static final int COLOUR_STALE = 0xE04040;
	/** Blue: the source has it and we never copied it. */
	private static final int COLOUR_NEVER = 0x4499EE;
	/** Purple: we copied it once and the source lost it. Updating deletes it here. */
	private static final int COLOUR_GONE = 0xAA55DD;
	/** Amber: waiting in the queue. */
	private static final int COLOUR_QUEUED = 0xFFB030;

	/**
	 * Pixels a chunk has to be worth before chunks become the thing you are clicking.
	 *
	 * <p>Eight is small but hittable, and it lands at a zoom where a whole region still fits on
	 * screen, so the switch happens while you can still see where you are.
	 */
	private static final int CHUNK_PIXELS_TO_SELECT = 8;

	private double centerX;
	private double centerZ;
	private double zoom = 0.12;
	private boolean centred;
	private boolean showGrid = true;
	private RegionKey hovered;
	private ChunkKey hoveredChunk;

	public boolean showGrid() {
		return showGrid;
	}

	public void toggleGrid() {
		showGrid = !showGrid;
	}

	public RegionKey hovered() {
		return hovered;
	}

	public ChunkKey hoveredChunk() {
		return hoveredChunk;
	}

	/**
	 * True when the map is zoomed in far enough that clicking picks a single chunk.
	 *
	 * <p>One gesture, two meanings, decided by how close you are. That is the only way it can
	 * work without a mode button: at this zoom a region is 256 pixels across and clicking one
	 * whole would be a click you could not aim, and zoomed out a chunk is a pixel.
	 */
	public boolean chunkMode() {
		return ChunkKey.BLOCKS * zoom >= CHUNK_PIXELS_TO_SELECT;
	}

	public double zoom() {
		return zoom;
	}

	public void centreOnPlayer() {
		Minecraft minecraft = Minecraft.getInstance();

		if (minecraft.player != null) {
			centerX = minecraft.player.getX();
			centerZ = minecraft.player.getZ();
			centred = true;
		}
	}

	public void ensureCentred() {
		if (!centred) {
			centreOnPlayer();
		}
	}

	public void zoomBy(double amount, double mouseX, double mouseY, int x, int y, int width, int height) {
		double worldBeforeX = screenToWorldX(mouseX, x, width);
		double worldBeforeZ = screenToWorldZ(mouseY, y, height);
		zoom = Math.clamp(zoom * (amount > 0 ? 1.2 : 1 / 1.2), 0.01, 4.0);
		// Keep the block under the cursor where it was, the way every map does it.
		centerX += worldBeforeX - screenToWorldX(mouseX, x, width);
		centerZ += worldBeforeZ - screenToWorldZ(mouseY, y, height);
	}

	public void drag(double dx, double dy) {
		centerX -= dx / zoom;
		centerZ -= dy / zoom;
	}

	public double screenToWorldX(double screenX, int x, int width) {
		return centerX + (screenX - (x + width / 2.0)) / zoom;
	}

	public double screenToWorldZ(double screenY, int y, int height) {
		return centerZ + (screenY - (y + height / 2.0)) / zoom;
	}

	public double worldToScreenX(double worldX, int x, int width) {
		return x + width / 2.0 + (worldX - centerX) * zoom;
	}

	public double worldToScreenZ(double worldZ, int y, int height) {
		return y + height / 2.0 + (worldZ - centerZ) * zoom;
	}

	/** The region under the cursor, or null when the cursor is outside the map area. */
	public RegionKey regionAt(Dim dim, double mouseX, double mouseY, int x, int y, int width, int height) {
		if (dim == null || mouseX < x || mouseX > x + width || mouseY < y || mouseY > y + height) {
			return null;
		}
		return RegionKey.ofBlock(dim,
				(int) Math.floor(screenToWorldX(mouseX, x, width)),
				(int) Math.floor(screenToWorldZ(mouseY, y, height)));
	}

	/** The chunk under the cursor, or null when the cursor is outside the map area. */
	public ChunkKey chunkAt(Dim dim, double mouseX, double mouseY, int x, int y, int width, int height) {
		if (dim == null || mouseX < x || mouseX > x + width || mouseY < y || mouseY > y + height) {
			return null;
		}
		return ChunkKey.ofBlock(dim,
				(int) Math.floor(screenToWorldX(mouseX, x, width)),
				(int) Math.floor(screenToWorldZ(mouseY, y, height)));
	}

	// ------------------------------------------------------------------ render

	public void render(GuiGraphics graphics, Dim dim,
			int x, int y, int width, int height, int mouseX, int mouseY) {
		graphics.enableScissor(x, y, x + width, y + height);
		graphics.fill(x, y, x + width, y + height, 0xFF12100F);
		drawTiles(graphics, x, y, width, height);

		hovered = regionAt(dim, mouseX, mouseY, x, y, width, height);
		hoveredChunk = chunkMode() ? chunkAt(dim, mouseX, mouseY, x, y, width, height) : null;

		if (showGrid && dim != null) {
			drawRegions(graphics, dim, x, y, width, height);
			if (chunkMode()) {
				drawChunks(graphics, dim, x, y, width, height);
			}
		}

		drawPlayer(graphics, x, y, width, height);
		graphics.disableScissor();
	}

	private void drawTiles(GuiGraphics graphics, int x, int y, int width, int height) {
		int minTileX = Math.floorDiv((int) Math.floor(screenToWorldX(x, x, width)), MapTile.SIZE);
		int maxTileX = Math.floorDiv((int) Math.ceil(screenToWorldX(x + width, x, width)), MapTile.SIZE);
		int minTileZ = Math.floorDiv((int) Math.floor(screenToWorldZ(y, y, height)), MapTile.SIZE);
		int maxTileZ = Math.floorDiv((int) Math.ceil(screenToWorldZ(y + height, y, height)), MapTile.SIZE);

		// Reading a tile off disk is a few milliseconds and only happens once, so the only case
		// worth refusing is a view so far out that it would pull in the entire world at once.
		int span = (maxTileX - minTileX + 1) * (maxTileZ - minTileZ + 1);
		boolean allowDiskReads = span <= 256;
		int drawSize = (int) Math.ceil(MapTile.SIZE * zoom) + 1;

		for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
			for (int tileZ = minTileZ; tileZ <= maxTileZ; tileZ++) {
				MapTile tile = allowDiskReads
						? MapStore.tile(tileX, tileZ, false)
						: MapStore.tileIfLoaded(tileX, tileZ);

				if (tile == null) {
					continue;
				}

				int screenX = (int) Math.floor(worldToScreenX(tile.originX(), x, width));
				int screenZ = (int) Math.floor(worldToScreenZ(tile.originZ(), y, height));
				graphics.blit(tile.textureId(), screenX, screenZ, drawSize, drawSize,
						0.0f, 0.0f, MapTile.SIZE, MapTile.SIZE, MapTile.SIZE, MapTile.SIZE);
			}
		}
	}

	/**
	 * The region grid and its colours.
	 *
	 * <p>Only regions the server had something to say about are filled in. Everything else is left
	 * as bare terrain with a faint outline, because on a real world the up to date regions are the
	 * overwhelming majority and colouring them all would drown out the handful that matter.
	 */
	private void drawRegions(GuiGraphics graphics, Dim dim, int x, int y, int width, int height) {
		int minX = Math.floorDiv((int) Math.floor(screenToWorldX(x, x, width)), RegionKey.BLOCKS);
		int maxX = Math.floorDiv((int) Math.ceil(screenToWorldX(x + width, x, width)), RegionKey.BLOCKS);
		int minZ = Math.floorDiv((int) Math.floor(screenToWorldZ(y, y, height)), RegionKey.BLOCKS);
		int maxZ = Math.floorDiv((int) Math.ceil(screenToWorldZ(y + height, y, height)), RegionKey.BLOCKS);

		// Zoomed far enough out that regions are a few pixels across, the grid is noise.
		boolean drawOutlines = RegionKey.BLOCKS * zoom >= 12;

		for (int regionX = minX; regionX <= maxX; regionX++) {
			for (int regionZ = minZ; regionZ <= maxZ; regionZ++) {
				RegionKey region = new RegionKey(dim, regionX, regionZ);
				RegionStatus status = RegionCastClient.statusOf(region);
				boolean queued = RegionCastClient.isQueued(region);
				boolean isHovered = region.equals(hovered);

				if (status == null && !queued && !isHovered && !drawOutlines) {
					continue;
				}

				int left = (int) Math.round(worldToScreenX(region.minBlockX(), x, width));
				int top = (int) Math.round(worldToScreenZ(region.minBlockZ(), y, height));
				int right = (int) Math.round(worldToScreenX(region.maxBlockX() + 1.0, x, width));
				int bottom = (int) Math.round(worldToScreenZ(region.maxBlockZ() + 1.0, y, height));

				if (right < x || left > x + width || bottom < y || top > y + height) {
					continue;
				}

				Integer colour = colourFor(status, queued);

				if (colour != null) {
					int alpha = isHovered ? 0x70 : 0x40;
					graphics.fill(left, top, right, bottom, alpha << 24 | colour);
				}

				if (drawOutlines) {
					int edge = colour != null
							? 0xDD000000 | colour
							: (isHovered ? 0x99FFFFFF : 0x33FFFFFF);
					graphics.hLine(left, right - 1, top, edge);
					graphics.hLine(left, right - 1, bottom - 1, edge);
					graphics.vLine(left, top, bottom - 1, edge);
					graphics.vLine(right - 1, top, bottom - 1, edge);
				}
			}
		}
	}

	/**
	 * The chunk grid, drawn over the region colours once you are close enough to click chunks.
	 *
	 * <p>Only chunks with something to say are filled: picked, or already pulled across on their
	 * own. Everything else gets a hairline, which is what tells you the click is now landing on a
	 * chunk. The lines are faint on purpose, since a screen full of 1024 boxes per region is the
	 * fastest way to make a map unreadable.
	 */
	private void drawChunks(GuiGraphics graphics, Dim dim, int x, int y, int width, int height) {
		int minX = Math.floorDiv((int) Math.floor(screenToWorldX(x, x, width)), ChunkKey.BLOCKS);
		int maxX = Math.floorDiv((int) Math.ceil(screenToWorldX(x + width, x, width)), ChunkKey.BLOCKS);
		int minZ = Math.floorDiv((int) Math.floor(screenToWorldZ(y, y, height)), ChunkKey.BLOCKS);
		int maxZ = Math.floorDiv((int) Math.ceil(screenToWorldZ(y + height, y, height)), ChunkKey.BLOCKS);

		for (int chunkX = minX; chunkX <= maxX; chunkX++) {
			for (int chunkZ = minZ; chunkZ <= maxZ; chunkZ++) {
				ChunkKey chunk = new ChunkKey(dim, chunkX, chunkZ);
				boolean queued = RegionCastClient.isQueued(chunk);
				boolean copied = RegionCastClient.isCopied(chunk);
				boolean isHovered = chunk.equals(hoveredChunk);

				int left = (int) Math.round(worldToScreenX(chunk.minBlockX(), x, width));
				int top = (int) Math.round(worldToScreenZ(chunk.minBlockZ(), y, height));
				int right = (int) Math.round(worldToScreenX(chunk.maxBlockX() + 1.0, x, width));
				int bottom = (int) Math.round(worldToScreenZ(chunk.maxBlockZ() + 1.0, y, height));

				if (queued) {
					graphics.fill(left, top, right, bottom, 0xA0000000 | COLOUR_QUEUED);
				} else if (copied) {
					// Green for brought across and still matching, red once the source moved on.
					int colour = RegionCastClient.isChunkStale(chunk) ? COLOUR_STALE : COLOUR_IN_SYNC;
					graphics.fill(left, top, right, bottom, 0x70000000 | colour);
				} else if (isHovered) {
					graphics.fill(left, top, right, bottom, 0x40FFFFFF);
				}

				int edge = isHovered ? 0xCCFFFFFF : 0x22FFFFFF;
				graphics.hLine(left, right - 1, top, edge);
				graphics.vLine(left, top, bottom - 1, edge);
			}
		}
	}

	/** The colour a region gets, or null when it is up to date and has nothing to say. */
	private static Integer colourFor(RegionStatus status, boolean queued) {
		// Queued wins: once you have picked a region, what you want to see is that it is picked.
		if (queued) {
			return COLOUR_QUEUED;
		}
		if (status == null) {
			return null;
		}
		return switch (status) {
			case STALE -> COLOUR_STALE;
			case NEVER_SYNCED -> COLOUR_NEVER;
			case GONE -> COLOUR_GONE;
			case IN_SYNC -> COLOUR_IN_SYNC;
		};
	}

	private void drawPlayer(GuiGraphics graphics, int x, int y, int width, int height) {
		Minecraft minecraft = Minecraft.getInstance();

		if (minecraft.player == null) {
			return;
		}

		int px = (int) Math.round(worldToScreenX(minecraft.player.getX(), x, width));
		int pz = (int) Math.round(worldToScreenZ(minecraft.player.getZ(), y, height));
		graphics.fill(px - 3, pz - 3, px + 3, pz + 3, 0xFF000000);
		graphics.fill(px - 2, pz - 2, px + 2, pz + 2, 0xFFFF4444);
	}
}
