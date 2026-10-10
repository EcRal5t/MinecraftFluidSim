package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.air.AirNetwork;

import java.util.Arrays;

/**
 * Assembles liquid/gas pressure rows, solves pressure with finite granular inertia and drag,
 * and projects the joint velocity. Pending phase work is planned after convergence and
 * before the final velocity write.
 */
final class PressureProjectionStage {
	record Workspace(
			int sizeX, int sizeY, int sizeZ, FluidSolverConfig config, DomainGeometry geometry,
			MacVelocityGrid mac, PressureProjectionWorkspace projection
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
		PressureFaceCoupling pressureFaceCoupling(
				int first, int second, int directionX, int directionY, int directionZ);
		void pressureAnchor(int cell);
		void pressureReference(float pressure);
		float pressureReference();
		void pressureResult(int iterations, float residual);
	}

	private final Workspace state;
	private final Access access;
	private final PressureSystem system;
	private final PressureBoundaryView gasBoundary;
	private final boolean[] referenceNodes;
	private int pressureAnchor;

	PressureProjectionStage(Workspace state, Access access, PressureSystem system, PressureBoundaryView gasBoundary) {
		this.state = state;
		this.access = access;
		this.system = system;
		this.gasBoundary = gasBoundary;
		referenceNodes = new boolean[state.projection.gasPressureUnknown.length];
	}

	void solve(float dt) {
		access.rebuildPressureBoundaryLineage();
		Arrays.fill(state.projection.rhs, 0.0F);
		Arrays.fill(state.projection.gasPressureUnknown, false);
		Arrays.fill(state.projection.gasPressure, 0.0F);
		Arrays.fill(state.projection.gasRhs, 0.0F);
		Arrays.fill(state.projection.gasMatrixDiagonal, 0.0F);
		Arrays.fill(state.projection.gasStorageCoefficient, 0.0F);
		state.projection.fluidUnknownCount = 0;
		state.projection.gasUnknownCount = 0;
		pressureAnchor = findPressureAnchor();
		access.pressureAnchor(pressureAnchor);
		access.pressureReference(pressureAnchor < 0 ? pressureBoundaryReference(dt) : 0.0F);
		Arrays.fill(state.projection.pressure, 0.0F);
		float inverseCell = 1.0F / state.config.cellSize();
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
			float correctedDivergence = div + access.surfaceTensionBoundaryDivergence(index, dt);
			state.projection.divergence[index] = correctedDivergence;
			// The paired face pass below assembles the pressure RHS without cancellation of gas loads.
			state.projection.fluidUnknownCells[state.projection.fluidUnknownCount++] = index;
			fluidCells++;
		}
		assembleCompressibleGasRows(dt);
		system.forEachGranularGasNode(dt, node -> {
			if (gasBoundary.gasNodeHasContinuumState(node)) activateCompressibleGasNode(node, dt);
		});
		system.prepareGranular(dt);
		double target = state.config.pressureTolerance();
		PressureProjectionResult result = system.solve(fluidCells + state.projection.gasUnknownCount, target);
		float residual = (float) result.residualRms();
		access.pressureResult(result.iterations(), residual);
		if (!result.converged()) {
			throw new FluidDomain.PressureSolveBlockedException("Pressure projection did not converge: residual="
					+ residual + ", tolerance=" + target + ", total iterations=" + result.iterations()
					+ ", linear iteration limit=" + state.config.maxPressureIterations());
		}
	}

	void projectVelocities(float dt) {
		boolean coupled = system.projectGranular(dt);
		double scale = dt / (state.config.density() * (double) state.config.cellSize());
		for (int z = 0; z < state.sizeZ; z++) for (int y = 0; y < state.sizeY; y++) for (int x = 1; x < state.sizeX; x++) {
			int left = access.cellIndex(x - 1, y, z), right = access.cellIndex(x, y, z), face = access.xFaceIndex(x, y, z);
			float openArea = access.pressureXFaceOpen(x, y, z);
			if (faceActive(left, right, openArea)) {
				double difference = coupled ? 0 : pressureDifference(left, right, 1, 0, 0);
				state.mac.velocityX[face] -= scale * difference;
			} else if (!access.carrierFaceActive(left, right, openArea)) state.mac.velocityX[face] = 0.0F;
		}
		for (int z = 0; z < state.sizeZ; z++) for (int y = 1; y < state.sizeY; y++) for (int x = 0; x < state.sizeX; x++) {
			int below = access.cellIndex(x, y - 1, z), above = access.cellIndex(x, y, z), face = access.yFaceIndex(x, y, z);
			float openArea = access.pressureYFaceOpen(x, y, z);
			if (faceActive(below, above, openArea)) {
				double difference = coupled ? 0 : pressureDifference(below, above, 0, 1, 0);
				state.mac.velocityY[face] -= scale * difference;
			} else if (!access.carrierFaceActive(below, above, openArea)) state.mac.velocityY[face] = 0.0F;
		}
		for (int z = 1; z < state.sizeZ; z++) for (int y = 0; y < state.sizeY; y++) for (int x = 0; x < state.sizeX; x++) {
			int back = access.cellIndex(x, y, z - 1), front = access.cellIndex(x, y, z), face = access.zFaceIndex(x, y, z);
			float openArea = access.pressureZFaceOpen(x, y, z);
			if (faceActive(back, front, openArea)) {
				double difference = coupled ? 0 : pressureDifference(back, front, 0, 0, 1);
				state.mac.velocityZ[face] -= scale * difference;
			} else if (!access.carrierFaceActive(back, front, openArea)) state.mac.velocityZ[face] = 0.0F;
		}
	}

	private double pressureDifference(int first, int second, int directionX, int directionY, int directionZ) {
		boolean firstFluid = access.isFluid(first);
		boolean secondFluid = access.isFluid(second);
		PressureFaceCoupling coupling = access.pressureFaceCoupling(
				first, second, directionX, directionY, directionZ);
		double firstPressure = firstFluid ? system.liquidPressure(first)
				: gasPressureOfAirCell(first);
		double secondPressure = secondFluid ? system.liquidPressure(second)
				: gasPressureOfAirCell(second);
		return coupling.mobilityScale() * (secondPressure - firstPressure + coupling.pressureJumpFirstMinusSecond());
	}

	private double gasPressureOfAirCell(int airCell) {
		int node = gasBoundary.gasNodeOfAirCell(airCell);
		return node > 0 && state.projection.gasPressureUnknown[node]
				? system.gasPressure(node)
				: gasBoundary.gasPressureOfAirCell(airCell) - access.pressureReference();
	}

	/**
	 * Adds one uniform, compressible pressure unknown for each finite sealed node touching liquid.
	 * Its storage is rho_ref * V * beta_s / (dt^2 h^3); every liquid/gas face contributes the
	 * same symmetric off-diagonal used by the liquid row. AirNetwork inventory and gas U remain
	 * authoritative—the solved node pressure is projection scratch and the next topology refresh
	 * evaluates the nonlinear EoS from the advected cavity volume.
	 */
	private void assembleCompressibleGasRows(float dt) {
		for (int z = 0; z < state.sizeZ; z++) for (int y = 0; y < state.sizeY; y++) {
			for (int x = 1; x < state.sizeX; x++) {
				assembleCompressibleGasFace(
						access.cellIndex(x - 1, y, z), access.cellIndex(x, y, z), 1, 0, 0,
						access.pressureXFaceOpen(x, y, z), state.mac.velocityX[access.xFaceIndex(x, y, z)], dt);
			}
		}
		for (int z = 0; z < state.sizeZ; z++) for (int y = 1; y < state.sizeY; y++) {
			for (int x = 0; x < state.sizeX; x++) {
				assembleCompressibleGasFace(
						access.cellIndex(x, y - 1, z), access.cellIndex(x, y, z), 0, 1, 0,
						access.pressureYFaceOpen(x, y, z), state.mac.velocityY[access.yFaceIndex(x, y, z)], dt);
			}
		}
		for (int z = 1; z < state.sizeZ; z++) for (int y = 0; y < state.sizeY; y++) {
			for (int x = 0; x < state.sizeX; x++) {
				assembleCompressibleGasFace(
						access.cellIndex(x, y, z - 1), access.cellIndex(x, y, z), 0, 0, 1,
						access.pressureZFaceOpen(x, y, z), state.mac.velocityZ[access.zFaceIndex(x, y, z)], dt);
			}
		}
	}

	private void assembleCompressibleGasFace(
			int first, int second, int directionX, int directionY, int directionZ,
			float openArea, float orientedVelocity, float dt
	) {
		if (!(openArea > 0.0F) || state.geometry.solid[first] || state.geometry.solid[second]) return;
		boolean firstFluid = access.isFluid(first), secondFluid = access.isFluid(second);
		if (!firstFluid && !secondFluid) return;
		double h = state.config.cellSize();
		double flux = state.config.density() * (double) openArea * orientedVelocity / (dt * h);
		if (firstFluid && first != pressureAnchor) state.projection.rhs[first] -= flux;
		if (secondFluid && second != pressureAnchor) state.projection.rhs[second] += flux;
		if (firstFluid && secondFluid) return;
		int fluid = firstFluid ? first : second;
		int air = firstFluid ? second : first;
		int node = gasBoundary.gasNodeOfAirCell(air);
		boolean finite = gasBoundary.gasNodeHasContinuumState(node) && activateCompressibleGasNode(node, dt);
		PressureFaceCoupling coupling = access.pressureFaceCoupling(fluid, air,
				firstFluid ? directionX : -directionX, firstFluid ? directionY : -directionY,
				firstFluid ? directionZ : -directionZ);
		double coefficient = coupling.matrixCoefficient(openArea) / (h * h);
		double jump = coefficient * coupling.pressureJumpFirstMinusSecond();
		state.projection.rhs[fluid] += jump;
		if (finite) {
			state.projection.gasMatrixDiagonal[node] += coefficient;
			state.projection.gasRhs[node] += (firstFluid ? flux : -flux) - jump;
		} else {
			state.projection.rhs[fluid] += coefficient * (gasBoundary.gasPressureOfAirCell(air) - access.pressureReference());
		}
	}
	private boolean activateCompressibleGasNode(int node, float dt) {
		if (state.projection.gasPressureUnknown[node]) return true;
		double cellSize = state.config.cellSize();
		double storage = state.config.density() * gasBoundary.gasNodeVolumeCompliance(node)
				/ (dt * (double) dt * cellSize * cellSize * cellSize);
		if (!(storage > 0.0) || Double.isNaN(storage)) {
			throw new IllegalStateException("Compressible gas-node pressure row exceeds solver precision");
		}
		if (!Double.isFinite(storage)) return false;
		double advectedPressure = gasBoundary.gasPressureOfNode(node) - access.pressureReference();
		double rhs = storage * advectedPressure;
		if (Double.isNaN(rhs)) {
			throw new IllegalStateException("Compressible gas-node pressure row exceeds solver precision");
		}
		// A physically near-vacuum node can have a valid EoS pressure but a compliance too large
		// for the pressure row. Keep its actual pressure as this projection's Dirichlet boundary;
		// the following topology remap still settles the exact isentropic volume work.
		if (!Double.isFinite(rhs)) return false;
		state.projection.gasPressureUnknown[node] = true;
		state.projection.gasUnknownNodes[state.projection.gasUnknownCount] = node;
		state.projection.gasStorageCoefficient[node] = storage;
		state.projection.gasMatrixDiagonal[node] = storage;
		state.projection.gasRhs[node] = rhs;
		state.projection.gasUnknownCount++;
		return true;
	}

	private boolean faceActive(int first, int second, float openArea) {
		return openArea > 0.0F && !state.geometry.solid[first] && !state.geometry.solid[second]
				&& (access.isFluid(first) || access.isFluid(second));
	}

	private float pressureBoundaryReference(float dt) {
		if (!gasBoundary.pressureActive()) return 0.0F;
		boolean[] seen = referenceNodes;
		Arrays.fill(seen, false);
		system.forEachGranularGasNode(dt, node -> seen[Math.max(0, node)] = true);
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
			seen[Math.max(0, node)] = true;
		}
		if (seen[AirNetwork.AMBIENT_NODE]) return 0.0F;
		double compliance = 0, reference = 0;
		for (int node = 1; node < seen.length; node++) {
			if (seen[node]) {
				if (!gasBoundary.gasNodeHasContinuumState(node)) return (float) gasBoundary.gasPressureOfNode(node);
				double weight = gasBoundary.gasNodeVolumeCompliance(node);
				if (!Double.isFinite(weight)) return (float) gasBoundary.gasPressureOfNode(node);
				compliance += weight;
				reference += (gasBoundary.gasPressureOfNode(node) - reference) * (weight / compliance);
			}
		}
		return (float) reference;
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
