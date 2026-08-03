package xyz.w4ve.regioncast.client.map;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import xyz.w4ve.regioncast.client.RegionCastClient;

/**
 * One 512 by 512 block square of map, drawn at one pixel per block.
 *
 * <p>Tiles are the reason the map is not capped in size or resolution any more: only the ones
 * you are looking at are held in memory, the rest sit on disk as PNGs and come back when the
 * view reaches them.
 */
public final class MapTile {
	/** Blocks, and pixels, per side. */
	public static final int SIZE = 512;
	/** Colour for ground that has never been loaded. */
	private static final int EMPTY = 0xFF1A1918;

	private final int tileX;
	private final int tileZ;
	private final NativeImage image;
	private final Map<Long, Long> chunkTimes = new HashMap<>();
	/** Chunks that were reached but had nothing to draw yet, so they can be retried soon. */
	private final Map<Long, Long> chunkEmpty = new HashMap<>();

	private DynamicTexture texture;
	private ResourceLocation textureId;
	private boolean dirty;
	private boolean changedSinceSave;
	private long lastUsed = System.currentTimeMillis();

	MapTile(int tileX, int tileZ) {
		this.tileX = tileX;
		this.tileZ = tileZ;
		this.image = new NativeImage(SIZE, SIZE, true);
		image.fillRect(0, 0, SIZE, SIZE, EMPTY);
	}

	public int tileX() {
		return tileX;
	}

	public int tileZ() {
		return tileZ;
	}

	/** World X of this tile's western edge. */
	public int originX() {
		return tileX * SIZE;
	}

	/** World Z of this tile's northern edge. */
	public int originZ() {
		return tileZ * SIZE;
	}

	public void touch() {
		lastUsed = System.currentTimeMillis();
	}

	public long lastUsed() {
		return lastUsed;
	}

	public boolean hasContent() {
		return !chunkTimes.isEmpty() || changedSinceSave;
	}

	/** When this chunk was last drawn, or 0 if never. */
	public long chunkDrawnAt(int chunkX, int chunkZ) {
		return chunkTimes.getOrDefault(chunkKey(chunkX, chunkZ), 0L);
	}

	/** When this chunk last came up empty, or 0 if it never did. */
	public long chunkEmptyAt(int chunkX, int chunkZ) {
		return chunkEmpty.getOrDefault(chunkKey(chunkX, chunkZ), 0L);
	}

	/**
	 * Marks every chunk as never drawn, so they are all rasterised again.
	 *
	 * <p>Needed under a ceiling, where the picture depends on how high the player is: walking down
	 * to a new floor means everything already drawn is now showing the wrong layer. The pixels are
	 * kept rather than cleared, so the old picture stays up until the new one replaces it.
	 */
	void forgetDrawn() {
		chunkTimes.clear();
		chunkEmpty.clear();
	}

	// ----------------------------------------------------------------- raster

	/**
	 * Draws one chunk into the tile.
	 *
	 * <p>Two rules here are not obvious and both came from watching the map eat itself:
	 *
	 * <ul>
	 *   <li>A column with no map colour leaves the pixel alone instead of clearing it. A chunk
	 *       that has arrived but whose heightmap is not built yet reads as air from top to
	 *       bottom, and painting that would wipe good terrain that was already there.
	 *   <li>A chunk that produced nothing at all is not marked as drawn, so it gets another go
	 *       shortly instead of waiting for the slow refresh.
	 * </ul>
	 *
	 * @param ceilingY highest Y worth looking at, or {@link Integer#MAX_VALUE} for no limit. In the
	 *     nether the roof is solid bedrock from edge to edge, so scanning from the top of the world
	 *     paints a flat grey sheet and nothing else. Passing the player's own height instead draws
	 *     the floor they are standing on, which is the only useful thing to show under a roof.
	 * @return true when the chunk had something to draw
	 */
	public boolean rasteriseChunk(Level level, LevelChunk chunk, int chunkX, int chunkZ, int ceilingY) {
		long key = chunkKey(chunkX, chunkZ);

		if (chunk.isEmpty()) {
			chunkEmpty.put(key, System.currentTimeMillis());
			return false;
		}

		int startY = Math.min(topOfContent(chunk), ceilingY);

		if (startY == Integer.MIN_VALUE || startY < level.getMinBuildHeight()) {
			// No section holds anything yet, so the chunk data has not really arrived. Or the
			// ceiling is below the world, which means there is nothing above to look down from.
			chunkEmpty.put(key, System.currentTimeMillis());
			return false;
		}

		int minY = level.getMinBuildHeight();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		int[] heights = new int[256];
		int[] colours = new int[256];
		int painted = 0;

		for (int localZ = 0; localZ < 16; localZ++) {
			for (int localX = 0; localX < 16; localX++) {
				int index = localZ * 16 + localX;
				heights[index] = Integer.MIN_VALUE;
				int worldX = (chunkX << 4) + localX;
				int worldZ = (chunkZ << 4) + localZ;
				int depth = 0;
				int waterColour = 0;

				// The heightmap is a shortcut, not a source of truth: when it is built it saves
				// scanning down from the ceiling, and when it is not we simply start at the top.
				int hint = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ);
				int from = hint > minY && hint <= startY ? hint + 1 : startY;

				for (int y = from; y >= minY; y--) {
					cursor.set(worldX, y, worldZ);
					BlockState state = chunk.getBlockState(cursor);

					if (state.isAir()) {
						continue;
					}

					MapColor colour = state.getMapColor(level, cursor);

					if (colour == MapColor.NONE) {
						continue;
					}

					// Water is drawn by how deep it is, the way vanilla maps do it, so coastlines
					// and river beds read as shape instead of one flat blue.
					if (!state.getFluidState().isEmpty()) {
						if (depth == 0) {
							waterColour = colour.col;
						}

						depth++;

						if (depth < 24) {
							continue;
						}
					}

					heights[index] = y;
					colours[index] = depth > 0
							? abgr(waterColour, waterShade(depth).modifier)
							: colour.col;
					break;
				}
			}
		}

		int baseX = (chunkX << 4) - originX();
		int baseZ = (chunkZ << 4) - originZ();

		for (int localZ = 0; localZ < 16; localZ++) {
			int pixelZ = baseZ + localZ;

			if (pixelZ < 0 || pixelZ >= SIZE) {
				continue;
			}

			for (int localX = 0; localX < 16; localX++) {
				int pixelX = baseX + localX;
				int index = localZ * 16 + localX;

				if (pixelX < 0 || pixelX >= SIZE || heights[index] == Integer.MIN_VALUE) {
					continue;
				}

				int packed = colours[index];

				if (packed >>> 24 == 0) {
					// Land: shade it against the column to the north for relief.
					int northIndex = index - 16;
					int north = localZ > 0 && heights[northIndex] != Integer.MIN_VALUE
							? heights[northIndex] : heights[index];
					packed = abgr(packed, shadeFor(heights[index] - north).modifier);
				}

				image.setPixelRGBA(pixelX, pixelZ, packed);
				painted++;
			}
		}

		if (painted == 0) {
			chunkEmpty.put(key, System.currentTimeMillis());
			return false;
		}

		chunkTimes.put(key, System.currentTimeMillis());
		chunkEmpty.remove(key);
		dirty = true;
		changedSinceSave = true;
		return true;
	}

	/**
	 * Y of the highest block that could hold anything, found through the chunk sections rather
	 * than the heightmap.
	 *
	 * <p>This is the whole reason the map used to eat itself: a chunk that has just arrived does
	 * not have its heightmap built, so asking for the surface height gives the bottom of the
	 * world and every column reads as air. The sections, on the other hand, either hold blocks or
	 * the chunk genuinely has not arrived.
	 *
	 * @return the Y to start scanning down from, or {@link Integer#MIN_VALUE} when there is
	 *         nothing in the chunk at all
	 */
	private static int topOfContent(LevelChunk chunk) {
		LevelChunkSection[] sections = chunk.getSections();

		for (int index = sections.length - 1; index >= 0; index--) {
			if (sections[index] != null && !sections[index].hasOnlyAir()) {
				return chunk.getSectionYFromSectionIndex(index) * 16 + 15;
			}
		}

		return Integer.MIN_VALUE;
	}

	private static MapColor.Brightness waterShade(int depth) {
		if (depth <= 2) {
			return MapColor.Brightness.HIGH;
		}

		if (depth <= 6) {
			return MapColor.Brightness.NORMAL;
		}

		return MapColor.Brightness.LOW;
	}

	private static MapColor.Brightness shadeFor(int step) {
		if (step > 1) {
			return MapColor.Brightness.HIGH;
		}

		if (step < -1) {
			return MapColor.Brightness.LOW;
		}

		return MapColor.Brightness.NORMAL;
	}

	/** NativeImage stores ABGR; map colours arrive as RGB. */
	/**
	 * Swaps the red and blue bytes. NativeImage took ABGR up to 1.21.1 and takes ARGB from 1.21.2,
	 * so the multiversion build routes the pixels through here for the newer ones.
	 */
	static int argbFromAbgr(int abgr) {
		return (abgr & 0xFF00FF00) | (abgr & 0xFF) << 16 | (abgr >> 16 & 0xFF);
	}

	private static int abgr(int rgb, int modifier) {
		int red = (rgb >> 16 & 0xFF) * modifier / 255;
		int green = (rgb >> 8 & 0xFF) * modifier / 255;
		int blue = (rgb & 0xFF) * modifier / 255;
		return 0xFF000000 | blue << 16 | green << 8 | red;
	}

	// ---------------------------------------------------------------- texture

	public ResourceLocation textureId() {
		if (texture == null) {
			texture = new DynamicTexture(image);
			textureId = Minecraft.getInstance().getTextureManager()
					.register("regioncast_map_" + tileX + "_" + tileZ, texture);
			dirty = false;
		} else if (dirty) {
			texture.upload();
			dirty = false;
		}

		return textureId;
	}

	// ------------------------------------------------------------ persistence

	static String fileName(int tileX, int tileZ) {
		return "t." + tileX + "." + tileZ + ".png";
	}

	void save(Path dir) {
		if (!changedSinceSave) {
			return;
		}

		try {
			Files.createDirectories(dir);
			image.writeToFile(dir.resolve(fileName(tileX, tileZ)));
			changedSinceSave = false;
		} catch (IOException e) {
			RegionCastClient.LOGGER.warn("Could not save map tile {} {}", tileX, tileZ, e);
		}
	}

	boolean load(Path dir) {
		Path path = dir.resolve(fileName(tileX, tileZ));

		if (!Files.isRegularFile(path)) {
			return false;
		}

		try (InputStream input = Files.newInputStream(path); NativeImage saved = NativeImage.read(input)) {
			if (saved.getWidth() != SIZE || saved.getHeight() != SIZE) {
				return false;
			}

			image.copyFrom(saved);
			dirty = true;
			return true;
		} catch (IOException | RuntimeException e) {
			RegionCastClient.LOGGER.warn("Could not read map tile {} {}", tileX, tileZ, e);
			return false;
		}
	}

	void close() {
		if (texture != null) {
			Minecraft.getInstance().getTextureManager().release(textureId);
			texture.close();
			texture = null;
		} else {
			image.close();
		}
	}

	static long chunkKey(int chunkX, int chunkZ) {
		return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
	}

	static long tileKey(int tileX, int tileZ) {
		return ((long) tileX << 32) | (tileZ & 0xFFFFFFFFL);
	}
}
