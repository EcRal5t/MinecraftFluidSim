package mmm.simulation;

import mmm.fluid.HostFluidState;

/** Shared initial inventories for the production debug commands and their automated replays. */
public final class DebugScenarioInitialization {
	private DebugScenarioInitialization() { }

	/** Sets a two-cell capillary length and the requested contact angle for the demonstration. */
	public static mmm.fluid.MaterialRegistry tensionDefinitions(mmm.fluid.MaterialRegistry original, int angleDegrees,
			float cellSize, float gravity) {
		var entries = original.entries().stream().map(entry -> {
			var material = entry.material();
			if (material.id().equals("mmm:water")) {
				material = new mmm.fluid.FluidMaterial(material.id(), material.referenceDensity(),
						material.miscibilityGroup(), material.referenceDensity() * gravity * Math.pow(2.0 * cellSize, 2),
						Math.toRadians(angleDegrees), material.displayArgb());
			}
			return new mmm.fluid.MaterialRegistry.MaterialEntry(material, entry.viscosity(), entry.thermalExpansion());
		}).toList();
		return new mmm.fluid.MaterialRegistry(entries, original.pairTensions());
	}

	public static void bindTensionPalette(HostFluidState domain, mmm.fluid.MaterialRegistry definitions) {
		FluidRegionFactory.bindDefaultMaterialState(domain, definitions);
	}

	public static void fillDam(HostFluidState domain, boolean layeredMaterials) {
		float[] volumes=new float[domain.grid().cellCount()];
		int fillX = domain.sizeX() * 2 / 5;
		int fillY = domain.sizeY() * 3 / 4;
		for (int z = 0; z < domain.sizeZ(); z++) {
			for (int y = 0; y < fillY; y++) {
				for (int x = 0; x < fillX; x++) {
					if (domain.grid().cell(x,y,z) >= 0 && !domain.isSolid(x, y, z)) volumes[domain.grid().cell(x,y,z)] = 1.0F;
				}
			}
		}
		int[] materials=layeredMaterials?new int[volumes.length]:null;
		if(materials!=null) for(int z=0;z<domain.sizeZ();z++) for(int y=0;y<domain.sizeY();y++) for(int x=0;x<domain.sizeX();x++)
			if (domain.grid().cell(x,y,z) >= 0) materials[domain.grid().cell(x,y,z)]=y+.5F>=fillY/2.0F?1:0;
		domain.setVolumes(volumes,materials);
		domain.resetAmbientGas();
	}

	public static void assignChemistry(HostFluidState domain) {
		float[] positions = domain.exportChemicalParticleState().materials().copyPositionVolume();
		float divider = domain.sizeX() * domain.config().cellSize() / 5.0F;
		var stocks = domain.exportLiquidCellState().physicalStocks();
		int acidCount = 0;
		for (int i = 0; i < stocks.size(); i++) if (positions[i * 4] < divider) acidCount++;
		int baseCount = stocks.size() - acidCount;
		if (acidCount == 0 || baseCount == 0) throw new IllegalStateException("Chemical demo requires liquid on both sides");
		long total = Math.multiplyExact((long) Math.min(acidCount, baseCount),
				mmm.chemistry.ChemicalAmount.quanta(mmm.chemistry.ExactRational.ONE));
		int acidIndex = 0, baseIndex = 0;
		var registry = domain.chemistry().registry();
		for (int i = 0; i < stocks.size(); i++) {
			boolean acid = positions[i * 4] < divider;
			int count = acid ? acidCount : baseCount;
			int index = acid ? acidIndex++ : baseIndex++;
			long amount = total / count + (index < total % count ? 1 : 0);
			var solution = mmm.chemistry.ChemicalMixtureRecord.fromSolutesAndSolventMass(registry,
					java.util.Map.of(acid ? "mmm:hcl" : "mmm:naoh", amount), "mmm:h2o", stocks.get(i).massUnits());
			domain.setMarkerChemical(i, solution.stableAmounts(registry));
		}
	}

	/** Sets every local gas inventory in the closed left arm to the chosen pressure. */
	public static int presetAirPressure(HostFluidState domain, boolean vacuum) {
		return presetAirPressure(domain, vacuum ? AirPressure.VACUUM : AirPressure.PRESSURE);
	}

	public enum AirPressure { PRESSURE, AMBIENT, VACUUM }

	public static int presetAirPressure(HostFluidState domain, AirPressure pressure) {
		int node = domain.airNodeAt(2, 6, 2);
		if (node <= 0) return -1;
		var gas = domain.gasBoundary();
		double factor = switch (pressure) {
			case PRESSURE -> 1.12;
			case AMBIENT -> 1.0;
			case VACUUM -> 0.88;
		};
		int leftArmLimit = 3 * FluidRegionFactory.CELLS_PER_BLOCK;
		for (int localNode : gas.nodeIds()) {
			int cell = localNode - 1;
			if (domain.grid().x(cell) < leftArmLimit)
				domain.setGasNodeStandardVolume(localNode, gas.availableVolume(localNode) * factor);
		}
		return node;
	}

	public static void fillAirWater(HostFluidState domain) {
		float[] volumes=new float[domain.grid().cellCount()];
		int cellsPerBlock = FluidRegionFactory.CELLS_PER_BLOCK;
		for (int z = 0; z < domain.sizeZ(); z++) for (int y = cellsPerBlock; y < 5 * cellsPerBlock / 2; y++)
			for (int x = 0; x < domain.sizeX(); x++) if (domain.grid().cell(x,y,z) >= 0 && !domain.isSolid(x,y,z))
				volumes[domain.grid().cell(x,y,z)] = 1.0F;
		domain.setVolumes(volumes);
		domain.resetAmbientGas();
	}


	public static void fillTensionCap(HostFluidState domain) {
		int sx = domain.sizeX(), sy = domain.sizeY(), sz = domain.sizeZ();
		float cellSize = domain.config().cellSize();
		double wallY = 2.0 * cellSize;
		double targetCellVolume = 220.0;
		double theta = Math.PI / 2.0;
		double cos = Math.cos(theta);
		double radiusCells = Math.cbrt(3.0 * targetCellVolume / (Math.PI * (1.0 - cos) * (1.0 - cos) * (2.0 + cos)));
		double radius = radiusCells * cellSize;
		double centerX = 0.5 * (sx - 1) * cellSize;
		double centerY = wallY - radius * cos;
		double centerZ = 0.5 * (sz - 1) * cellSize;
		float[] volumes=new float[domain.grid().cellCount()];
		for(int z=0;z<sz;z++) for(int y=2;y<sy;y++) for(int x=0;x<sx;x++)
			if (domain.grid().cell(x,y,z) >= 0 && !domain.isSolid(x, y, z)) volumes[domain.grid().cell(x,y,z)]=(float)sphericalCapFraction(x,y,z,cellSize,centerX,centerY,centerZ,radius,wallY);
		domain.setVolumes(volumes);
		domain.resetAmbientGas();
	}

	private static double sphericalCapFraction(int x, int y, int z, double cellSize,
	                                           double centerX, double centerY, double centerZ,
	                                           double radius, double wallY) {
		double px = x * cellSize, py = y * cellSize, pz = z * cellSize;
		int samples = 4;
		int hits = 0;
		for (int subZ = 0; subZ < samples; subZ++) for (int subY = 0; subY < samples; subY++) {
			for (int subX = 0; subX < samples; subX++) {
				double sx = px + (subX + 0.5) * cellSize / samples;
				double sy = py + (subY + 0.5) * cellSize / samples;
				double sz = pz + (subZ + 0.5) * cellSize / samples;
				double dx = sx - centerX, dy = sy - centerY, dz = sz - centerZ;
				if (sy >= wallY && dx * dx + dy * dy + dz * dz <= radius * radius) hits++;
			}
		}
		return (double) hits / (samples * samples * samples);
	}

}
