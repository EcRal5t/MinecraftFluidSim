package mmm.fluid;

import mmm.thermal.ThermalMaterialDefinition;
import mmm.units.SignedDyadic;

import java.util.List;

/** One retained selector for M9 definition, state-function and later transport/phase invariants. */

abstract class ThermalValidationSupport {
	static final String CHEMICAL_FINGERPRINT = "1".repeat(64);

	/** Explicit raw owners for phase storage, integer partitions and carrier-count budgets. */
	static void restorePhaseTestCarriers(FluidDomain domain, FluidMaterialRegistry materials,
	                                     float[] volumes, int[] materialHandles) {
		restorePhaseTestCarriers(domain, materials, 1, 1, 1, volumes, materialHandles);
	}
	static void restorePhaseTestCarriers(FluidDomain domain, FluidMaterialRegistry materials, int x, int y, int z,
	                                     float[] volumes, int[] materialHandles) {
		float[] positions = new float[4 * volumes.length]; double[] masses = new double[volumes.length];
		for (int marker = 0; marker < volumes.length; marker++) {
			float[] position = domain.markerSlotPosition(x, y, z, marker);
			System.arraycopy(position, 0, positions, 4 * marker, 3); positions[4 * marker + 3] = volumes[marker];
			masses[marker] = volumes[marker] * materials.material(materialHandles[marker]).referenceDensity();
		}
		domain.restoreMaterialParticleState(new FlipMaterialParticleState(domain.sizeX, domain.sizeY, domain.sizeZ,
				domain.config.cellSize(), volumes.length, SignedDyadic.sum(volumes, volumes.length).toDouble(),
				SignedDyadic.sum(masses).toDouble(), materials.materials(), positions, new float[3 * volumes.length],
				domain.geometry.cutCells.copyMicroMasks(), masses, materialHandles));
	}

	static ThermalMaterialDefinition material(
			String id, String carrier, double referenceSpecificEnergy,
			List<ThermalMaterialDefinition.CaloricSegment> segments
	) {
		return new ThermalMaterialDefinition(id, ThermalMaterialDefinition.CarrierKind.LIQUID, carrier,
				300.0, referenceSpecificEnergy, 0.5, segments);
	}

	static boolean close(double first, double second) {
		return Math.abs(first - second) <= Math.max(1.0E-10,
				Math.max(Math.ulp(first), Math.ulp(second)) * 8.0);
	}

	static double relativeError(double actual, double expected) {
		return Math.abs(actual - expected) / Math.abs(expected);
	}

	static void expectFailure(Runnable action, String message) {
		try { action.run(); }
		catch (RuntimeException expected) { return; }
		throw new AssertionError(message);
	}

	static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
