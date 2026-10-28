package mmm.simulation;

import mmm.fluid.FluidDomain;

/** Shared initial inventories for the production debug commands and their automated replays. */
public final class DebugScenarioInitialization {
	private DebugScenarioInitialization() { }

	/** Exaggerated surface tension makes the wetting effect visible at block scale. */
	public static mmm.fluid.MaterialRegistry tensionDefinitions(mmm.fluid.MaterialRegistry original, int angleDegrees) {
		var entries = original.entries().stream().map(entry -> {
			var material = entry.material();
			if (material.id().equals(mmm.fluid.BuiltinFluidMaterials.WATER.id())) {
				material = new mmm.fluid.FluidMaterial(material.id(), material.referenceDensity(),
						material.miscibilityGroup(), 1000.0, Math.toRadians(angleDegrees));
			}
			return new mmm.fluid.MaterialRegistry.MaterialEntry(material, entry.viscosity(), entry.thermalExpansion());
		}).toList();
		return new mmm.fluid.MaterialRegistry(entries, original.pairTensions());
	}

	public static void bindTensionPalette(FluidDomain domain, mmm.fluid.MaterialRegistry definitions) {
		domain.bindMaterialRegistry(new mmm.fluid.FluidMaterialRegistry(
				mmm.fluid.BuiltinFluidMaterials.DEFAULT_PALETTE.stream()
						.map(material -> definitions.entry(material.id()).material()).toList()));
	}

	public static void fillDam(FluidDomain domain, boolean layeredMaterials) {
		float[] volumes=new float[domain.sizeX()*domain.sizeY()*domain.sizeZ()];
		int fillX = domain.sizeX() * 2 / 5;
		int fillY = domain.sizeY() * 3 / 4;
		for (int z = 0; z < domain.sizeZ(); z++) {
			for (int y = 0; y < fillY; y++) {
				for (int x = 0; x < fillX; x++) {
					if (!domain.isSolid(x, y, z)) volumes[(z*domain.sizeY()+y)*domain.sizeX()+x] = 1.0F;
				}
			}
		}
		int[] materials=layeredMaterials?new int[volumes.length]:null;
		if(materials!=null) for(int z=0;z<domain.sizeZ();z++) for(int y=0;y<domain.sizeY();y++) for(int x=0;x<domain.sizeX();x++)
			materials[(z*domain.sizeY()+y)*domain.sizeX()+x]=y+.5F>=fillY/2.0F?1:0;
		domain.setVolumes(volumes,materials);
	}

	public static void assignChemistry(FluidDomain domain) {
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

	/** Presets the closed chamber above the water in the U-tube scaffold. */
	public static int presetAirPressure(FluidDomain domain, boolean vacuum) {
		return presetAirPressure(domain, vacuum ? AirPressure.VACUUM : AirPressure.PRESSURE);
	}

	public enum AirPressure { PRESSURE, AMBIENT, VACUUM }

	public static int presetAirPressure(FluidDomain domain, AirPressure pressure) {
		int node = domain.airNodeAt(4, 12, 4);
		if (node <= 0) return -1;
		var gas = domain.gasBoundary();
		double factor = switch (pressure) {
			case PRESSURE -> 1.0 / 0.70;
			case AMBIENT -> 1.0;
			case VACUUM -> 0.78;
		};
		domain.setGasNodeStandardVolume(node, gas.availableVolume(node) * factor);
		return node;
	}

	public static void fillAirWater(FluidDomain domain) {
		float[] volumes=new float[domain.sizeX()*domain.sizeY()*domain.sizeZ()];
		int cellsPerBlock = FluidRegionFactory.CELLS_PER_BLOCK;
		for (int bz = 1; bz <= MinecraftDebugFixtures.BLOCK_SIZE_Z - 2; bz++) {
			for (int bx = 1; bx <= MinecraftDebugFixtures.BLOCK_SIZE_X - 2; bx++) {
				for (int by = 1; by <= 3; by++) {
					for (int localZ = 0; localZ < cellsPerBlock; localZ++) {
						for (int localY = 0; localY < cellsPerBlock; localY++) {
							float fraction = by <= 2 ? 1.0F : Math.clamp(0.5F * cellsPerBlock - localY, 0.0F, 1.0F);
							for (int localX = 0; localX < cellsPerBlock; localX++) {
								int x = bx * cellsPerBlock + localX;
								int y = by * cellsPerBlock + localY;
								int z = bz * cellsPerBlock + localZ;
								if (!domain.isSolid(x, y, z)) volumes[(z*domain.sizeY()+y)*domain.sizeX()+x] = fraction;
							}
						}
					}
				}
			}
		}
		domain.setVolumes(volumes);
	}


	public static void fillTensionCap(FluidDomain domain) {
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
		float[] volumes=new float[sx*sy*sz];
		for(int z=0;z<sz;z++) for(int y=2;y<sy;y++) for(int x=0;x<sx;x++)
			if (!domain.isSolid(x, y, z)) volumes[(z*sy+y)*sx+x]=(float)sphericalCapFraction(x,y,z,cellSize,centerX,centerY,centerZ,radius,wallY);
		domain.setVolumes(volumes);
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
