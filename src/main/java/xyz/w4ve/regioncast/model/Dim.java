package xyz.w4ve.regioncast.model;

/**
 * The three vanilla dimensions, and where their region files live inside a world folder.
 *
 * <p>Kept as a plain enum instead of a Minecraft {@code ResourceKey} so the whole {@code model}
 * package stays free of Minecraft classes and can be unit tested.
 */
public enum Dim {
	NETHER(-1, "minecraft:the_nether", "DIM-1"),
	OVERWORLD(0, "minecraft:overworld", ""),
	END(1, "minecraft:the_end", "DIM1");

	/** The index used by the old {@code !!region} command, kept so muscle memory still works. */
	private final int index;
	private final String id;
	/** Folder holding this dimension's data, relative to the world folder. Empty for the overworld. */
	private final String folder;

	Dim(int index, String id, String folder) {
		this.index = index;
		this.id = id;
		this.folder = folder;
	}

	public int index() {
		return index;
	}

	public String id() {
		return id;
	}

	public String folder() {
		return folder;
	}

	public static Dim byIndex(int index) {
		for (Dim dim : values()) {
			if (dim.index == index) {
				return dim;
			}
		}
		throw new IllegalArgumentException("no dimension with index " + index);
	}

	/**
	 * Looks up a dimension by its registry id.
	 *
	 * @return the dimension, or null when it is a modded or datapack one we cannot place on disk
	 */
	public static Dim byId(String id) {
		for (Dim dim : values()) {
			if (dim.id.equals(id)) {
				return dim;
			}
		}
		return null;
	}
}
