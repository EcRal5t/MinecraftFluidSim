package mmm.simulation;

import mmm.fluid.FluidDomain;

/** Initial inventories shared by interactive demonstrations and their offline replays. */
public final class DemoSceneInitialization {
	private DemoSceneInitialization() { }

	public static void fillFlow(FluidDomain domain) {
		int subdivisions = FluidRegionFactory.CELLS_PER_BLOCK;
		float[] volumes = new float[domain.sizeX() * domain.sizeY() * domain.sizeZ()];
		for (int z = subdivisions; z < 5 * subdivisions; z++)
			for (int y = subdivisions; y < 4 * subdivisions; y++)
				for (int x = subdivisions; x < 3 * subdivisions; x++)
					if (!domain.isSolid(x, y, z)) volumes[(z * domain.sizeY() + y) * domain.sizeX() + x] = 1.0F;
		domain.setVolumes(volumes);
	}
}
