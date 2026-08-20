package com.example.fluid;

import java.util.List;

/**
 * Diagnostic, not a gate: measures how much water fails to sink below oil after a layered dam break settles.
 * It answers whether water markers stranded above an oil layer are a real solver outcome or a rendering artefact.
 */
public final class M3StratificationDiagnostic {
	private M3StratificationDiagnostic() { }

	public static void main(String[] args) {
		FluidMaterialRegistry registry = new FluidMaterialRegistry(List.of(
				new FluidMaterial("realwater:water", 1_000.0, "realwater:aqueous"),
				new FluidMaterial("realwater:oil", 800.0, "realwater:oil")
		));
		int water = registry.handle("realwater:water");
		int oil = registry.handle("realwater:oil");
		FluidSolverConfig config = new FluidSolverConfig(
				1.0F, 1_000.0F, 9.81F, 1.0F / 40.0F,
				200, 1.0E-5F, 0.40F, 2.0E-3F, 0.80F
		);
		int sizeX = 24, sizeY = 16, sizeZ = 8;
		FlipFluidDomain domain = new FlipFluidDomain(sizeX, sizeY, sizeZ, config, 60_000);
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			domain.setSolid(x, y, z, true);
		}
		for (int z = 1; z <= sizeZ - 2; z++) for (int y = 1; y <= sizeY - 2; y++) for (int x = 1; x <= sizeX - 2; x++) {
			domain.setSolid(x, y, z, false);
		}
		// Same proportions as /realwater materialdemo: a dam-break column, lower half water and upper half oil.
		int fillX = 9, fillY = 12, interfaceCell = 1 + fillY / 2;
		for (int z = 1; z <= sizeZ - 2; z++) for (int y = 1; y <= fillY; y++) for (int x = 1; x <= fillX; x++) {
			domain.setVolume(x, y, z, 1.0F);
		}
		for (int z = 1; z <= sizeZ - 2; z++) for (int y = 1; y <= fillY; y++) for (int x = 1; x <= fillX; x++) {
			domain.setMaterialInCell(x, y, z, registry, y < interfaceCell ? water : oil);
		}

		double initialVolume = domain.totalVolume(), initialMass = domain.totalMass();
		System.out.printf("initial: markers=%d volume=%.3f mass=%.3f%n",
				domain.particleCount(), initialVolume, initialMass);
		report(domain, water, oil, 0);
		for (int step = 1; step <= 4_000; step++) {
			domain.step();
			if (step % 1_000 == 0) report(domain, water, oil, step);
		}
		System.out.printf("conservation: dVolume=%.3e dMass=%.3e maxSpeed=%.3g%n",
				domain.totalVolume() - initialVolume, domain.totalMass() - initialMass,
				domain.maximumParticleSpeed());
	}

	private static void report(FlipFluidDomain domain, int water, int oil, int step) {
		FlipMaterialParticleState state = domain.exportMaterialParticleState();
		float[] positionVolume = state.copyPositionVolume();
		int[] materials = state.copyMaterialPaletteIndex();
		double waterVolume = 0.0, oilVolume = 0.0, waterWeightedY = 0.0, oilWeightedY = 0.0;
		double highestWaterY = Double.NEGATIVE_INFINITY, highestOilY = Double.NEGATIVE_INFINITY;
		for (int marker = 0; marker < state.particleCount(); marker++) {
			double y = positionVolume[marker * 4 + 1];
			double volume = positionVolume[marker * 4 + 3];
			if (materials[marker] == water) {
				waterVolume += volume;
				waterWeightedY += y * volume;
				highestWaterY = Math.max(highestWaterY, y);
			} else if (materials[marker] == oil) {
				oilVolume += volume;
				oilWeightedY += y * volume;
				highestOilY = Math.max(highestOilY, y);
			}
		}
		double oilCenterY = oilVolume <= 0.0 ? 0.0 : oilWeightedY / oilVolume;
		double strandedVolume = 0.0;
		int strandedMarkers = 0;
		for (int marker = 0; marker < state.particleCount(); marker++) {
			if (materials[marker] != water) continue;
			if (positionVolume[marker * 4 + 1] <= oilCenterY) continue;
			strandedVolume += positionVolume[marker * 4 + 3];
			strandedMarkers++;
		}
		System.out.printf(
				"step %4d: waterY=%.3f oilY=%.3f | water above oil centre: %.4f of %.3f (%.3f%%), %d of %d markers "
						+ "| topWater=%.2f topOil=%.2f%n",
				step, waterVolume <= 0.0 ? 0.0 : waterWeightedY / waterVolume, oilCenterY,
				strandedVolume, waterVolume, waterVolume <= 0.0 ? 0.0 : strandedVolume / waterVolume * 100.0,
				strandedMarkers, domain.particleCount(), highestWaterY, highestOilY
		);
	}
}
