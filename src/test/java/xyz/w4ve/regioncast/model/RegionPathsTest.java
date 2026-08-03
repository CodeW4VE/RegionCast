package xyz.w4ve.regioncast.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RegionPathsTest {

	@Test
	@DisplayName("the overworld has no dimension prefix")
	void overworldSitsAtTheRoot() {
		assertEquals(
				List.of("region/r.1.2.mca", "poi/r.1.2.mca", "entities/r.1.2.mca"),
				RegionPaths.of(new RegionKey(Dim.OVERWORLD, 1, 2)));
	}

	@Test
	@DisplayName("the nether and the end sit under DIM-1 and DIM1")
	void otherDimensionsAreNested() {
		assertEquals(
				List.of("DIM-1/region/r.0.0.mca", "DIM-1/poi/r.0.0.mca", "DIM-1/entities/r.0.0.mca"),
				RegionPaths.of(new RegionKey(Dim.NETHER, 0, 0)));
		assertEquals(
				List.of("DIM1/region/r.-1.-1.mca", "DIM1/poi/r.-1.-1.mca", "DIM1/entities/r.-1.-1.mca"),
				RegionPaths.of(new RegionKey(Dim.END, -1, -1)));
	}

	@Test
	@DisplayName("entities travel with the terrain")
	void entitiesAreIncluded() {
		// Copying region/ alone is the classic way to get terrain that moved and mobs that did
		// not. Since 1.17 entities live in their own folder and must come along.
		for (Dim dim : Dim.values()) {
			List<String> paths = RegionPaths.of(new RegionKey(dim, 0, 0));
			assertEquals(3, paths.size());
			assertTrue(paths.stream().anyMatch(p -> p.contains("entities/")),
					dim + " must copy its entities folder");
		}
	}

	@Test
	@DisplayName("every dimension names the same file")
	void sameFileNameEverywhere() {
		RegionKey region = new RegionKey(Dim.NETHER, 5, -7);
		for (String path : RegionPaths.of(region)) {
			assertTrue(path.endsWith("/" + region.fileName()), path);
		}
	}

	@Test
	@DisplayName("dimension indices match the ones the old command used")
	void indicesMatchTheOldCommand() {
		// Muscle memory from !!region add 0 3 2: overworld 0, nether -1, end 1.
		assertEquals(Dim.OVERWORLD, Dim.byIndex(0));
		assertEquals(Dim.NETHER, Dim.byIndex(-1));
		assertEquals(Dim.END, Dim.byIndex(1));
	}
}
