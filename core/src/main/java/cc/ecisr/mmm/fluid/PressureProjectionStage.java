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
	record Workspace(
			int sizeX, int sizeY, int sizeZ, FluidSolverConfig config, DomainGeometry geometry,
			MacVelocityGrid mac, PressureProjectionWorkspace projection,
			FlipFreeSurfaceGeometry freeSurfaceGeometry, SurfaceTensionWorkspace tension
	) { }

	interface Access {
		void rebuildPressureBoundaryLineage();
		int cellIndex(int x, int y, int z);
		int xFaceIndex(int x, int y, int z);
		int yFaceIndex(int x, int y, int z);
		int zFaceIndex(int x, int y, int z);
		float pressureXFaceOpen(int x, int y, int z);
		float pressureYFaceOpen(int x, int y, int z);
		float pressureZFaceOpen(int x, int y, int z);
		boolean isFluid(int cell);
		boolean isAir(int cell);
		boolean carrierFaceActive(int first, int second, float openArea);
		float surfaceTensionBoundaryDivergence(int cell, float dt);
		float granularMovingSolidDivergence(int cell, float dt);
		float pressureOpenCellVolume(int cell);
		float pressureInterfaceTheta(int fluidCell, int directionX, int directionY, int directionZ);
		boolean variableDensityActive();
		float pressureDensityScale(int first, int second);
		void pressureAnchor(int cell);
		void pressureReference(float pressure);
		float pressureReference();
		void pressureResult(int iterations, float residual);
	}

	private final Workspace state;
	private final Access access;
	private final PressureSystem system;
	private final PressureBoundaryView gasBoundary;

	PressureProjectionStage(Workspace state, Access access, PressureSystem system, PressureBoundaryView gasBoundary) {
		this.state = state;
		this.access = access;
		this.system = system;
		this.gasBoundary = gasBoundary;
	}

	void solve(float dt) {
		access.rebuildPressureBoundaryLineage();
		Arrays.fill(state.projection.rhs, 0.0F);
		int pressureAnchor = findPressureAnchor();
		access.pressureAnchor(pressureAnchor);
		access.pressureReference(pressureAnchor < 0 ? pressureBoundaryReference() : 0.0F);
		Arrays.fill(state.projection.pressure, 0.0F);
		float inverseCell = 1.0F / state.config.cellSize();
		float rhsScale = -state.config.density() / dt;
		int fluidCells = 0;
		for (int z = 0; z < state.sizeZ; z++) for (int y = 0; y < state.sizeY; y++) for (int x = 0; x < state.sizeX; x++) {
			int index = access.cellIndex(x, y, z);
			if (!access.isFluid(index) || index == pressureAnchor) { state.projection.divergence[index] = 0.0F; continue; }
			float div = (access.pressureXFaceOpen(x + 1, y, z) * state.mac.velocityX[access.xFaceIndex(x + 1, y, z)]
					- access.pressureXFaceOpen(x, y, z) * state.mac.velocityX[access.xFaceIndex(x, y, z)]
					+ access.pressureYFaceOpen(x, y + 1, z) * state.mac.velocityY[access.yFaceIndex(x, y + 1, z)]
					- access.pressureYFaceOpen(x, y, z) * state.mac.velocityY[access.yFaceIndex(x, y, z)]
					+ access.pressureZFaceOpen(x, y, z + 1) * state.mac.velocityZ[access.zFaceIndex(x, y, z + 1)]
					- access.pressureZFaceOpen(x, y, z) * state.mac.velocityZ[access.zFaceIndex(x, y, z)]) * inverseCell;
			// Solve toward the drift target: div(u_projected) = targetDivergence.
			float correctedDivergence = div + access.surfaceTensionBoundaryDivergence(index, dt)
					- volumeDriftDivergence(index, dt) - access.granularMovingSolidDivergence(index, dt);
			state.projection.divergence[index] = correctedDivergence;
			state.projection.rhs[index] = rhsScale * correctedDivergence;
			fluidCells++;
		}
		if (fluidCells == 0) { access.pressureResult(0, 0.0F); return; }
		double target = state.config.pressureTolerance();
		PressureProjectionResult result = system.solve(fluidCells, target);
		float residual = (float) result.residualRms();
		access.pressureResult(result.iterations(), residual);
		if (!result.converged()) {
			throw new FluidDomain.PressureSolveBlockedException("Pressure projection did not converge: residual="
					+ residual + ", tolerance=" + target + ", iterations=" + result.iterations()
					+ "/" + state.config.maxPressureIterations());
		}
	}

	void projectVelocities(float dt) {
		float scale = dt / (state.config.density() * state.config.cellSize());
		for (int z = 0; z < state.sizeZ; z++) for (int y = 0; y < state.sizeY; y++) for (int x = 1; x < state.sizeX; x++) {
			int left = access.cellIndex(x - 1, y, z), right = access.cellIndex(x, y, z), face = access.xFaceIndex(x, y, z);
			float openArea = access.pressureXFaceOpen(x, y, z);
			if (faceActive(left, right, openArea)) {
				float difference = pressureDifference(left, right, 1, 0, 0);
				if (access.variableDensityActive()) difference *= access.pressureDensityScale(left, right);
				state.mac.velocityX[face] -= scale * difference;
			} else if (!access.carrierFaceActive(left, right, openArea)) state.mac.velocityX[face] = 0.0F;
		}
		for (int z = 0; z < state.sizeZ; z++) for (int y = 1; y < state.sizeY; y++) for (int x = 0; x < state.sizeX; x++) {
			int below = access.cellIndex(x, y - 1, z), above = access.cellIndex(x, y, z), face = access.yFaceIndex(x, y, z);
			float openArea = access.pressureYFaceOpen(x, y, z);
			if (faceActive(below, above, openArea)) {
				float difference = pressureDifference(below, above, 0, 1, 0);
				if (access.variableDensityActive()) difference *= access.pressureDensityScale(below, above);
				state.mac.velocityY[face] -= scale * difference;
			} else if (!access.carrierFaceActive(below, above, openArea)) state.mac.velocityY[face] = 0.0F;
		}
		for (int z = 1; z < state.sizeZ; z++) for (int y = 0; y < state.sizeY; y++) for (int x = 0; x < state.sizeX; x++) {
			int back = access.cellIndex(x, y, z - 1), front = access.cellIndex(x, y, z), face = access.zFaceIndex(x, y, z);
			float openArea = access.pressureZFaceOpen(x, y, z);
			if (faceActive(back, front, openArea)) {
				float difference = pressureDifference(back, front, 0, 0, 1);
				if (access.variableDensityActive()) difference *= access.pressureDensityScale(back, front);
				state.mac.velocityZ[face] -= scale * difference;
			} else if (!access.carrierFaceActive(back, front, openArea)) state.mac.velocityZ[face] = 0.0F;
		}
	}

	private float pressureDifference(int first, int second, int directionX, int directionY, int directionZ) {
		boolean firstFluid = access.isFluid(first);
		boolean secondFluid = access.isFluid(second);
		if (firstFluid && secondFluid) return state.projection.pressure[second] - state.projection.pressure[first];
		if (firstFluid) {
			float theta = access.pressureInterfaceTheta(first, directionX, directionY, directionZ);
			float jump = state.tension.surfaceTensionPressure[first] + gasBoundary.gasPressureOfAirCell(second) - access.pressureReference();
			return (jump - state.projection.pressure[first]) / theta;
		}
		float theta = access.pressureInterfaceTheta(second, -directionX, -directionY, -directionZ);
		float jump = state.tension.surfaceTensionPressure[second] + gasBoundary.gasPressureOfAirCell(first) - access.pressureReference();
		return (state.projection.pressure[second] - jump) / theta;
	}

	private boolean faceActive(int first, int second, float openArea) {
		return openArea > 0.0F && !state.geometry.solid[first] && !state.geometry.solid[second]
				&& (access.isFluid(first) || access.isFluid(second));
	}

	private float pressureBoundaryReference() {
		if (!gasBoundary.pressureActive()) return 0.0F;
		int referenceNode = Integer.MAX_VALUE;
		for (int z = 0; z < state.sizeZ; z++) for (int y = 0; y < state.sizeY; y++) for (int x = 0; x < state.sizeX; x++) {
			int air = access.cellIndex(x, y, z);
			if (!access.isAir(air)) continue;
			boolean touchesFluid = x > 0 && state.geometry.cutCells.xFaceOpen(x, y, z) > 0.0F
					&& access.isFluid(access.cellIndex(x - 1, y, z))
					|| x + 1 < state.sizeX && state.geometry.cutCells.xFaceOpen(x + 1, y, z) > 0.0F
					&& access.isFluid(access.cellIndex(x + 1, y, z))
					|| y > 0 && state.geometry.cutCells.yFaceOpen(x, y, z) > 0.0F
					&& access.isFluid(access.cellIndex(x, y - 1, z))
					|| y + 1 < state.sizeY && state.geometry.cutCells.yFaceOpen(x, y + 1, z) > 0.0F
					&& access.isFluid(access.cellIndex(x, y + 1, z))
					|| z > 0 && state.geometry.cutCells.zFaceOpen(x, y, z) > 0.0F
					&& access.isFluid(access.cellIndex(x, y, z - 1))
					|| z + 1 < state.sizeZ && state.geometry.cutCells.zFaceOpen(x, y, z + 1) > 0.0F
					&& access.isFluid(access.cellIndex(x, y, z + 1));
			if (!touchesFluid) continue;
			int node = gasBoundary.gasNodeOfAirCell(air);
			if (node < 0) continue;
			referenceNode = Math.min(referenceNode, node);
			if (referenceNode == AirNetwork.AMBIENT_NODE) return 0.0F;
		}
		return referenceNode == Integer.MAX_VALUE ? 0.0F : (float) gasBoundary.gasPressureOfNode(referenceNode);
	}

	private float volumeDriftDivergence(int index, float dt) {
		float openVolume = access.pressureOpenCellVolume(index);
		int x = index % state.sizeX, yz = index / state.sizeX;
		int y = yz % state.sizeY, z = yz / state.sizeY;
		return FlipVolumeDriftCorrection.targetDivergence(
				(float) state.projection.pressureVolume[index], state.freeSurfaceGeometry.exactCellVolume(index), openVolume,
				hasAirNeighbor(x, y, z), dt
		);
	}

	private int findPressureAnchor() {
		int firstFluid = -1;
		for (int z = 0; z < state.sizeZ; z++) for (int y = 0; y < state.sizeY; y++) for (int x = 0; x < state.sizeX; x++) {
			int index = access.cellIndex(x, y, z);
			if (!access.isFluid(index)) continue;
			if (firstFluid < 0) firstFluid = index;
			if (hasAirNeighbor(x, y, z)) return -1;
		}
		return firstFluid;
	}

	private boolean hasAirNeighbor(int x, int y, int z) {
		return (x > 0 && state.geometry.cutCells.xFaceOpen(x, y, z) > 0.0F && access.isAir(access.cellIndex(x - 1, y, z)))
				|| (x + 1 < state.sizeX && state.geometry.cutCells.xFaceOpen(x + 1, y, z) > 0.0F && access.isAir(access.cellIndex(x + 1, y, z)))
				|| (y > 0 && state.geometry.cutCells.yFaceOpen(x, y, z) > 0.0F && access.isAir(access.cellIndex(x, y - 1, z)))
				|| (y + 1 < state.sizeY && state.geometry.cutCells.yFaceOpen(x, y + 1, z) > 0.0F && access.isAir(access.cellIndex(x, y + 1, z)))
				|| (z > 0 && state.geometry.cutCells.zFaceOpen(x, y, z) > 0.0F && access.isAir(access.cellIndex(x, y, z - 1)))
				|| (z + 1 < state.sizeZ && state.geometry.cutCells.zFaceOpen(x, y, z + 1) > 0.0F && access.isAir(access.cellIndex(x, y, z + 1)));
	}
}
