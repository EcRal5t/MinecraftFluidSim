package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.air.AirNetwork;

import java.util.Arrays;

/**
 * The pressure projection stage: gas lineage refresh, reference anchoring, target-divergence
 * assembly (surface-tension, volume-drift and granular-moving-solid contributions), the PCG
 * solve through {@link PressureSystem} and the velocity projection. Loop order, float/double
 * typing, the absolute RMS tolerance and the typed non-convergence rejection are kept
 * verbatim; the pending phase-work plan is produced by the step pipeline only after
 * {@code solve} returns and before {@code projectVelocities} runs.
 */
final class PressureProjectionStage {
	private final FlipFluidDomain domain;
	private final PressureSystem system;
	private final PressureBoundaryView gasBoundary;

	PressureProjectionStage(FlipFluidDomain domain, PressureSystem system, PressureBoundaryView gasBoundary) {
		this.domain = domain;
		this.system = system;
		this.gasBoundary = gasBoundary;
	}

	void solve(float dt) {
		domain.rebuildPressureBoundaryLineage();
		Arrays.fill(domain.projection.rhs, 0.0F);
		domain.pressureAnchor = findPressureAnchor();
		domain.pressureReference = domain.pressureAnchor < 0 ? pressureBoundaryReference() : 0.0F;
		Arrays.fill(domain.projection.pressure, 0.0F);
		float inverseCell = 1.0F / domain.config.cellSize();
		float rhsScale = -domain.config.density() / dt;
		int fluidCells = 0;
		for (int z = 0; z < domain.sizeZ; z++) for (int y = 0; y < domain.sizeY; y++) for (int x = 0; x < domain.sizeX; x++) {
			int index = domain.cellIndex(x, y, z);
			if (!domain.isFluid(index) || index == domain.pressureAnchor) { domain.projection.divergence[index] = 0.0F; continue; }
			float div = (domain.pressureXFaceOpen(x + 1, y, z) * domain.mac.velocityX[domain.xFaceIndex(x + 1, y, z)]
					- domain.pressureXFaceOpen(x, y, z) * domain.mac.velocityX[domain.xFaceIndex(x, y, z)]
					+ domain.pressureYFaceOpen(x, y + 1, z) * domain.mac.velocityY[domain.yFaceIndex(x, y + 1, z)]
					- domain.pressureYFaceOpen(x, y, z) * domain.mac.velocityY[domain.yFaceIndex(x, y, z)]
					+ domain.pressureZFaceOpen(x, y, z + 1) * domain.mac.velocityZ[domain.zFaceIndex(x, y, z + 1)]
					- domain.pressureZFaceOpen(x, y, z) * domain.mac.velocityZ[domain.zFaceIndex(x, y, z)]) * inverseCell;
			// Solve toward the drift target: div(u_projected) = targetDivergence.
			float correctedDivergence = div + domain.surfaceTensionBoundaryDivergence(index, dt)
					- volumeDriftDivergence(index, dt) - domain.granularMovingSolidDivergence(index, dt);
			domain.projection.divergence[index] = correctedDivergence;
			domain.projection.rhs[index] = rhsScale * correctedDivergence;
			fluidCells++;
		}
		if (fluidCells == 0) { domain.lastPressureIterations = 0; domain.lastPressureResidual = 0.0F; return; }
		double target = domain.config.pressureTolerance();
		PressureProjectionResult result = system.solve(fluidCells, target);
		domain.lastPressureIterations = result.iterations();
		domain.lastPressureResidual = (float) result.residualRms();
		if (!result.converged()) {
			throw new FlipFluidDomain.PressureSolveBlockedException("Pressure projection did not converge: residual="
					+ domain.lastPressureResidual + ", tolerance=" + target + ", iterations=" + domain.lastPressureIterations
					+ "/" + domain.config.maxPressureIterations());
		}
	}

	void projectVelocities(float dt) {
		float scale = dt / (domain.config.density() * domain.config.cellSize());
		for (int z = 0; z < domain.sizeZ; z++) for (int y = 0; y < domain.sizeY; y++) for (int x = 1; x < domain.sizeX; x++) {
			int left = domain.cellIndex(x - 1, y, z), right = domain.cellIndex(x, y, z), face = domain.xFaceIndex(x, y, z);
			float openArea = domain.pressureXFaceOpen(x, y, z);
			if (faceActive(left, right, openArea)) {
				float difference = pressureDifference(left, right, 1, 0, 0);
				if (domain.variableDensityActive) difference *= domain.pressureDensityScale(left, right);
				domain.mac.velocityX[face] -= scale * difference;
			} else if (!domain.carrierFaceActive(left, right, openArea)) domain.mac.velocityX[face] = 0.0F;
		}
		for (int z = 0; z < domain.sizeZ; z++) for (int y = 1; y < domain.sizeY; y++) for (int x = 0; x < domain.sizeX; x++) {
			int below = domain.cellIndex(x, y - 1, z), above = domain.cellIndex(x, y, z), face = domain.yFaceIndex(x, y, z);
			float openArea = domain.pressureYFaceOpen(x, y, z);
			if (faceActive(below, above, openArea)) {
				float difference = pressureDifference(below, above, 0, 1, 0);
				if (domain.variableDensityActive) difference *= domain.pressureDensityScale(below, above);
				domain.mac.velocityY[face] -= scale * difference;
			} else if (!domain.carrierFaceActive(below, above, openArea)) domain.mac.velocityY[face] = 0.0F;
		}
		for (int z = 1; z < domain.sizeZ; z++) for (int y = 0; y < domain.sizeY; y++) for (int x = 0; x < domain.sizeX; x++) {
			int back = domain.cellIndex(x, y, z - 1), front = domain.cellIndex(x, y, z), face = domain.zFaceIndex(x, y, z);
			float openArea = domain.pressureZFaceOpen(x, y, z);
			if (faceActive(back, front, openArea)) {
				float difference = pressureDifference(back, front, 0, 0, 1);
				if (domain.variableDensityActive) difference *= domain.pressureDensityScale(back, front);
				domain.mac.velocityZ[face] -= scale * difference;
			} else if (!domain.carrierFaceActive(back, front, openArea)) domain.mac.velocityZ[face] = 0.0F;
		}
	}

	private float pressureDifference(int first, int second, int directionX, int directionY, int directionZ) {
		boolean firstFluid = domain.isFluid(first);
		boolean secondFluid = domain.isFluid(second);
		if (firstFluid && secondFluid) return domain.projection.pressure[second] - domain.projection.pressure[first];
		if (firstFluid) {
			float theta = domain.pressureInterfaceTheta(first, directionX, directionY, directionZ);
			float jump = domain.tension.surfaceTensionPressure[first] + domain.gasPressureOfAirCell(second) - domain.pressureReference;
			return (jump - domain.projection.pressure[first]) / theta;
		}
		float theta = domain.pressureInterfaceTheta(second, -directionX, -directionY, -directionZ);
		float jump = domain.tension.surfaceTensionPressure[second] + domain.gasPressureOfAirCell(first) - domain.pressureReference;
		return (domain.projection.pressure[second] - jump) / theta;
	}

	private boolean faceActive(int first, int second, float openArea) {
		return openArea > 0.0F && !domain.geometry.solid[first] && !domain.geometry.solid[second]
				&& (domain.isFluid(first) || domain.isFluid(second));
	}

	private float pressureBoundaryReference() {
		if (!gasBoundary.pressureActive()) return 0.0F;
		int referenceNode = Integer.MAX_VALUE;
		for (int z = 0; z < domain.sizeZ; z++) for (int y = 0; y < domain.sizeY; y++) for (int x = 0; x < domain.sizeX; x++) {
			int air = domain.cellIndex(x, y, z);
			if (!domain.isAir(air)) continue;
			boolean touchesFluid = x > 0 && domain.geometry.cutCells.xFaceOpen(x, y, z) > 0.0F
					&& domain.isFluid(domain.cellIndex(x - 1, y, z))
					|| x + 1 < domain.sizeX && domain.geometry.cutCells.xFaceOpen(x + 1, y, z) > 0.0F
					&& domain.isFluid(domain.cellIndex(x + 1, y, z))
					|| y > 0 && domain.geometry.cutCells.yFaceOpen(x, y, z) > 0.0F
					&& domain.isFluid(domain.cellIndex(x, y - 1, z))
					|| y + 1 < domain.sizeY && domain.geometry.cutCells.yFaceOpen(x, y + 1, z) > 0.0F
					&& domain.isFluid(domain.cellIndex(x, y + 1, z))
					|| z > 0 && domain.geometry.cutCells.zFaceOpen(x, y, z) > 0.0F
					&& domain.isFluid(domain.cellIndex(x, y, z - 1))
					|| z + 1 < domain.sizeZ && domain.geometry.cutCells.zFaceOpen(x, y, z + 1) > 0.0F
					&& domain.isFluid(domain.cellIndex(x, y, z + 1));
			if (!touchesFluid) continue;
			int node = gasBoundary.gasNodeOfAirCell(air);
			if (node < 0) continue;
			referenceNode = Math.min(referenceNode, node);
			if (referenceNode == AirNetwork.AMBIENT_NODE) return 0.0F;
		}
		return referenceNode == Integer.MAX_VALUE ? 0.0F : (float) gasBoundary.gasPressureOfNode(referenceNode);
	}

	private float volumeDriftDivergence(int index, float dt) {
		float openVolume = domain.pressureOpenCellVolume(index);
		int x = index % domain.sizeX, yz = index / domain.sizeX;
		int y = yz % domain.sizeY, z = yz / domain.sizeY;
		return FlipVolumeDriftCorrection.targetDivergence(
				(float) domain.projection.pressureVolume[index], domain.freeSurfaceGeometry.exactCellVolume(index), openVolume,
				hasAirNeighbor(x, y, z), dt
		);
	}

	private int findPressureAnchor() {
		int firstFluid = -1;
		for (int z = 0; z < domain.sizeZ; z++) for (int y = 0; y < domain.sizeY; y++) for (int x = 0; x < domain.sizeX; x++) {
			int index = domain.cellIndex(x, y, z);
			if (!domain.isFluid(index)) continue;
			if (firstFluid < 0) firstFluid = index;
			if (hasAirNeighbor(x, y, z)) return -1;
		}
		return firstFluid;
	}

	private boolean hasAirNeighbor(int x, int y, int z) {
		return (x > 0 && domain.geometry.cutCells.xFaceOpen(x, y, z) > 0.0F && domain.isAir(domain.cellIndex(x - 1, y, z)))
				|| (x + 1 < domain.sizeX && domain.geometry.cutCells.xFaceOpen(x + 1, y, z) > 0.0F && domain.isAir(domain.cellIndex(x + 1, y, z)))
				|| (y > 0 && domain.geometry.cutCells.yFaceOpen(x, y, z) > 0.0F && domain.isAir(domain.cellIndex(x, y - 1, z)))
				|| (y + 1 < domain.sizeY && domain.geometry.cutCells.yFaceOpen(x, y + 1, z) > 0.0F && domain.isAir(domain.cellIndex(x, y + 1, z)))
				|| (z > 0 && domain.geometry.cutCells.zFaceOpen(x, y, z) > 0.0F && domain.isAir(domain.cellIndex(x, y, z - 1)))
				|| (z + 1 < domain.sizeZ && domain.geometry.cutCells.zFaceOpen(x, y, z + 1) > 0.0F && domain.isAir(domain.cellIndex(x, y, z + 1)));
	}
}
