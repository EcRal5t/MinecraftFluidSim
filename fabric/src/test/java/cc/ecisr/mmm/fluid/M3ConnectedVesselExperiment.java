package cc.ecisr.mmm.fluid;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Isolated 3D connected-vessel experiment for M3 marker inventory and pressure work.
 * It is test-only state and deliberately does not participate in the active Minecraft solver.
 */
final class M3ConnectedVesselExperiment {
	static final double WATER_DENSITY = 1_000.0;
	static final int MATERIAL_A = 101;
	static final int MATERIAL_B = 202;
	static final int PHASE_A = 11;
	static final int PHASE_B = 22;

	private static final double CELL_SIZE = 0.5;
	private static final float MARKER_VOLUME = (float) (CELL_SIZE * CELL_SIZE * CELL_SIZE);
	private static final double TIME_STEP = 1.0 / 20.0;
	private static final double GRAVITY = 9.81;
	private static final int MAX_PCG_ITERATIONS = 256;
	private static final double PCG_TOLERANCE = 1.0E-12;
	private static final int MATERIAL_INTERFACE_LOWER_Y = 2;

	private M3ConnectedVesselExperiment() { }

	static Topology uTube() {
		return connectedVessel(6, 6, 1.0, 1.0);
	}

	static Scenario balancedScenario(String name, double heavyDensity, double lightDensity,
	                                 double lowerMaterialDistanceFraction,
	                                 int leftTopY, int rightTopY, double rightSurfaceDistanceFraction) {
		double interfaceDensity = lowerMaterialDistanceFraction * heavyDensity
				+ (1.0 - lowerMaterialDistanceFraction) * lightDensity;
		double rightLightDistance = rightTopY - MATERIAL_INTERFACE_LOWER_Y - 1
				+ rightSurfaceDistanceFraction;
		double leftSurfaceDistanceFraction = (rightLightDistance * lightDensity + interfaceDensity) / heavyDensity
				- (leftTopY - MATERIAL_INTERFACE_LOWER_Y);
		return new Scenario(name, heavyDensity, lightDensity, lowerMaterialDistanceFraction,
				leftTopY, rightTopY, leftSurfaceDistanceFraction, rightSurfaceDistanceFraction);
	}

	static Topology variableDensityTube(Scenario scenario) {
		return connectedVessel(scenario.leftTopY(), scenario.rightTopY(),
				scenario.leftSurfaceDistanceFraction(), scenario.rightSurfaceDistanceFraction());
	}

	private static Topology connectedVessel(int leftTopY, int rightTopY,
	                                        double leftSurfaceDistanceFraction,
	                                        double rightSurfaceDistanceFraction) {
		int sizeX = 9, sizeY = Math.max(leftTopY, rightTopY) + 2, sizeZ = 4;
		boolean[] fluid = new boolean[sizeX * sizeY * sizeZ];
		for (int z = 1; z <= 2; z++) {
			for (int y = 1; y <= 2; y++) {
				for (int x = 1; x <= 7; x++) fluid[gridIndex(sizeX, sizeZ, x, y, z)] = true;
			}
			for (int y = 3; y <= leftTopY; y++) for (int x : new int[] {1, 2}) {
				fluid[gridIndex(sizeX, sizeZ, x, y, z)] = true;
			}
			for (int y = 3; y <= rightTopY; y++) for (int x : new int[] {6, 7}) {
				fluid[gridIndex(sizeX, sizeZ, x, y, z)] = true;
			}
		}
		return new Topology(sizeX, sizeY, sizeZ, fluid, leftTopY, rightTopY,
				leftSurfaceDistanceFraction, rightSurfaceDistanceFraction);
	}

	static MarkerState singleMaterialState(Topology topology) {
		return markerState(topology, false);
	}

	static MarkerState equalDensityTwoMaterialState(Topology topology) {
		return markerState(topology, true);
	}

	static MarkerState variableDensityState(Topology topology, Scenario scenario) {
		int count = topology.cellCount();
		int[] cell = new int[count];
		float[] volume = new float[count];
		double[] mass = new double[count];
		int[] material = new int[count];
		int[] phase = new int[count];
		for (int marker = 0; marker < count; marker++) {
			boolean light = topology.x(marker) >= 6 && topology.y(marker) > MATERIAL_INTERFACE_LOWER_Y;
			double density = light ? scenario.lightDensity() : scenario.heavyDensity();
			cell[marker] = marker;
			volume[marker] = MARKER_VOLUME;
			mass[marker] = density * MARKER_VOLUME;
			material[marker] = light ? MATERIAL_B : MATERIAL_A;
			phase[marker] = light ? PHASE_B : PHASE_A;
		}
		return new MarkerState(cell, volume, mass, material, phase);
	}

	private static MarkerState markerState(Topology topology, boolean splitLabels) {
		int count = topology.cellCount();
		int[] cell = new int[count];
		float[] volume = new float[count];
		double[] mass = new double[count];
		int[] material = new int[count];
		int[] phase = new int[count];
		for (int marker = 0; marker < count; marker++) {
			cell[marker] = marker;
			volume[marker] = MARKER_VOLUME;
			mass[marker] = WATER_DENSITY * MARKER_VOLUME;
			boolean second = splitLabels && topology.x(marker) >= 5;
			material[marker] = second ? MATERIAL_B : MATERIAL_A;
			phase[marker] = second ? PHASE_B : PHASE_A;
		}
		return new MarkerState(cell, volume, mass, material, phase);
	}

	/** Constant-density reference matching the pre-M3 pure-water pressure coefficient. */
	static Result runPureWaterReference(Topology topology) {
		double[] density = new double[topology.cellCount()];
		Arrays.fill(density, WATER_DENSITY);
		int[] markerCount = new int[topology.cellCount()];
		Arrays.fill(markerCount, 1);
		return project(topology, markerCount, density);
	}

	/** M3 candidate path: pressure density is derived only from exact marker mass and volume inventories. */
	static Result runMarkerMassModel(Topology topology, MarkerState state) {
		return runMarkerMassModel(topology, state, 0.5);
	}

	static Result runMarkerMassModel(Topology topology, MarkerState state,
	                                 double lowerMaterialDistanceFraction) {
		int cells = topology.cellCount();
		int[] markerCount = new int[cells];
		double[] cellVolume = new double[cells];
		double[] cellMass = new double[cells];
		for (int marker = 0; marker < state.size(); marker++) {
			int cell = state.cell()[marker];
			if (cell < 0 || cell >= cells) throw new IllegalArgumentException("Marker is outside the connected vessel");
			markerCount[cell]++;
			cellVolume[cell] += state.particleVolume()[marker];
			cellMass[cell] += state.particleMass()[marker];
		}
		double[] density = new double[cells];
		for (int cell = 0; cell < cells; cell++) {
			if (markerCount[cell] == 0) throw new IllegalArgumentException("Pressure cell has no marker");
			density[cell] = cellMass[cell] / cellVolume[cell];
			if (!(Double.isFinite(density[cell]) && density[cell] > 0.0)) {
				throw new IllegalArgumentException("Marker inventory produced an invalid density");
			}
		}
		return project(topology, markerCount, density, lowerMaterialDistanceFraction);
	}

	static double[] expectedHydrostaticPressure(Topology topology, Scenario scenario) {
		double[] result = new double[topology.cellCount()];
		double interfaceDensity = scenario.interfaceEffectiveDensity();
		for (int cell = 0; cell < result.length; cell++) {
			int x = topology.x(cell), y = topology.y(cell);
			double pressureDensityHeight;
			if (x <= 2) {
				pressureDensityHeight = scenario.heavyDensity()
						* (scenario.leftTopY() - y + scenario.leftSurfaceDistanceFraction());
			} else if (x >= 6 && y > MATERIAL_INTERFACE_LOWER_Y) {
				pressureDensityHeight = scenario.lightDensity()
						* (scenario.rightTopY() - y + scenario.rightSurfaceDistanceFraction());
			} else {
				pressureDensityHeight = scenario.lightDensity()
						* (scenario.rightTopY() - MATERIAL_INTERFACE_LOWER_Y - 1
						+ scenario.rightSurfaceDistanceFraction())
						+ interfaceDensity
						+ scenario.heavyDensity() * (MATERIAL_INTERFACE_LOWER_Y - y);
			}
			result[cell] = GRAVITY * CELL_SIZE * pressureDensityHeight;
		}
		return result;
	}

	static Ledger ledger(MarkerState state) {
		double totalVolume = 0.0, totalMass = 0.0;
		Map<Integer, MutableInventory> material = new HashMap<>();
		Map<Integer, MutableInventory> phase = new HashMap<>();
		for (int marker = 0; marker < state.size(); marker++) {
			double volume = state.particleVolume()[marker];
			double mass = state.particleMass()[marker];
			totalVolume += volume;
			totalMass += mass;
			material.computeIfAbsent(state.materialId()[marker], ignored -> new MutableInventory()).add(volume, mass);
			phase.computeIfAbsent(state.phaseLabel()[marker], ignored -> new MutableInventory()).add(volume, mass);
		}
		return new Ledger(totalVolume, totalMass, freeze(material), freeze(phase));
	}

	private static Map<Integer, Inventory> freeze(Map<Integer, MutableInventory> source) {
		Map<Integer, Inventory> result = new HashMap<>();
		source.forEach((key, value) -> result.put(key, new Inventory(value.volume, value.mass)));
		return Map.copyOf(result);
	}

	private static Result project(Topology topology, int[] markerCount, double[] density) {
		return project(topology, markerCount, density, 0.5);
	}

	private static Result project(Topology topology, int[] markerCount, double[] density,
	                              double lowerMaterialDistanceFraction) {
		Face[] faces = topology.faces();
		double[] inverseDensityByFace = new double[faces.length];
		for (int faceIndex = 0; faceIndex < faces.length; faceIndex++) {
			Face face = faces[faceIndex];
			double lowDensity = density[face.lowCell()];
			if (face.highCell() < 0) {
				inverseDensityByFace[faceIndex] = 1.0 / (lowDensity * face.surfaceDistanceFraction());
				continue;
			}
			double highDensity = density[face.highCell()];
			double effectiveDensity = Double.doubleToLongBits(lowDensity) == Double.doubleToLongBits(highDensity)
					? lowDensity
					: lowerMaterialDistanceFraction * lowDensity
					+ (1.0 - lowerMaterialDistanceFraction) * highDensity;
			inverseDensityByFace[faceIndex] = 1.0 / effectiveDensity;
		}

		int cells = topology.cellCount();
		double inverseCellSquared = 1.0 / (CELL_SIZE * CELL_SIZE);
		double[] matrix = new double[cells * cells];
		for (int faceIndex = 0; faceIndex < faces.length; faceIndex++) {
			Face face = faces[faceIndex];
			double coefficient = inverseDensityByFace[faceIndex] * inverseCellSquared;
			add(matrix, cells, face.lowCell(), face.lowCell(), coefficient);
			if (face.highCell() >= 0) {
				add(matrix, cells, face.highCell(), face.highCell(), coefficient);
				add(matrix, cells, face.lowCell(), face.highCell(), -coefficient);
				add(matrix, cells, face.highCell(), face.lowCell(), -coefficient);
			}
		}

		double[] provisionalVelocity = new double[faces.length];
		for (int faceIndex = 0; faceIndex < faces.length; faceIndex++) {
			if (faces[faceIndex].axis() == Axis.Y) provisionalVelocity[faceIndex] = -GRAVITY * TIME_STEP;
		}
		double[] rhs = rightHandSide(cells, faces, provisionalVelocity);
		PcgResult solved = solvePcg(matrix, cells, rhs);
		double[] projectedVelocity = projectFaces(faces, provisionalVelocity, inverseDensityByFace, solved.pressure());
		double maximumDivergence = maximumDivergence(cells, faces, projectedVelocity);
		MatrixDiagnostics diagnostics = inspectMatrix(matrix, cells);
		return new Result(markerCount.clone(), density.clone(), inverseDensityByFace, matrix, rhs,
				solved.pressure(), projectedVelocity, solved.iterations(), solved.residualRms(),
				diagnostics.symmetryError(), diagnostics.minimumCholeskyPivot(), maximumDivergence);
	}

	private static double[] rightHandSide(int cells, Face[] faces, double[] velocity) {
		double[] divergence = new double[cells];
		for (int faceIndex = 0; faceIndex < faces.length; faceIndex++) {
			Face face = faces[faceIndex];
			double flux = velocity[faceIndex] / CELL_SIZE;
			divergence[face.lowCell()] += flux;
			if (face.highCell() >= 0) divergence[face.highCell()] -= flux;
		}
		double[] rhs = new double[cells];
		for (int cell = 0; cell < cells; cell++) rhs[cell] = -divergence[cell] / TIME_STEP;
		return rhs;
	}

	private static double[] projectFaces(Face[] faces, double[] provisionalVelocity,
	                                     double[] inverseDensityByFace, double[] pressure) {
		double[] result = new double[faces.length];
		for (int faceIndex = 0; faceIndex < faces.length; faceIndex++) {
			Face face = faces[faceIndex];
			double highPressure = face.highCell() >= 0 ? pressure[face.highCell()] : 0.0;
			result[faceIndex] = provisionalVelocity[faceIndex]
					- TIME_STEP * inverseDensityByFace[faceIndex]
					* (highPressure - pressure[face.lowCell()]) / CELL_SIZE;
		}
		return result;
	}

	private static double maximumDivergence(int cells, Face[] faces, double[] velocity) {
		double[] divergence = new double[cells];
		for (int faceIndex = 0; faceIndex < faces.length; faceIndex++) {
			Face face = faces[faceIndex];
			double flux = velocity[faceIndex] / CELL_SIZE;
			divergence[face.lowCell()] += flux;
			if (face.highCell() >= 0) divergence[face.highCell()] -= flux;
		}
		double maximum = 0.0;
		for (double value : divergence) maximum = Math.max(maximum, Math.abs(value));
		return maximum;
	}

	private static PcgResult solvePcg(double[] matrix, int size, double[] rhs) {
		double[] pressure = new double[size];
		double[] residual = rhs.clone();
		double[] preconditioned = new double[size];
		double[] direction = new double[size];
		double[] matrixDirection = new double[size];
		applyDiagonalPreconditioner(matrix, size, residual, preconditioned);
		System.arraycopy(preconditioned, 0, direction, 0, size);
		double rz = dot(residual, preconditioned);
		double target = PCG_TOLERANCE * Math.max(1.0, rms(rhs));
		int iterations = 0;
		for (; iterations < MAX_PCG_ITERATIONS && rms(residual) > target; iterations++) {
			multiply(matrix, size, direction, matrixDirection);
			double denominator = dot(direction, matrixDirection);
			if (!(denominator > 1.0E-30)) throw new IllegalStateException("M3 PCG lost positive definiteness");
			double alpha = rz / denominator;
			for (int i = 0; i < size; i++) {
				pressure[i] += alpha * direction[i];
				residual[i] -= alpha * matrixDirection[i];
			}
			if (rms(residual) <= target) { iterations++; break; }
			applyDiagonalPreconditioner(matrix, size, residual, preconditioned);
			double nextRz = dot(residual, preconditioned);
			if (!(Math.abs(rz) > 1.0E-30)) throw new IllegalStateException("M3 PCG recurrence collapsed");
			double beta = nextRz / rz;
			for (int i = 0; i < size; i++) direction[i] = preconditioned[i] + beta * direction[i];
			rz = nextRz;
		}
		if (rms(residual) > target) throw new IllegalStateException("M3 PCG did not converge");
		return new PcgResult(pressure, iterations, rms(residual));
	}

	private static void applyDiagonalPreconditioner(double[] matrix, int size,
	                                                double[] source, double[] destination) {
		for (int i = 0; i < size; i++) destination[i] = source[i] / matrix[i * size + i];
	}

	private static void multiply(double[] matrix, int size, double[] vector, double[] destination) {
		Arrays.fill(destination, 0.0);
		for (int row = 0; row < size; row++) {
			for (int column = 0; column < size; column++) {
				destination[row] += matrix[row * size + column] * vector[column];
			}
		}
	}

	private static MatrixDiagnostics inspectMatrix(double[] matrix, int size) {
		double maximumEntry = 0.0, maximumAsymmetry = 0.0;
		for (int row = 0; row < size; row++) for (int column = 0; column < size; column++) {
			double entry = matrix[row * size + column];
			maximumEntry = Math.max(maximumEntry, Math.abs(entry));
			maximumAsymmetry = Math.max(maximumAsymmetry,
					Math.abs(entry - matrix[column * size + row]));
		}
		double[] lower = new double[size * size];
		double minimumPivot = Double.POSITIVE_INFINITY;
		for (int row = 0; row < size; row++) {
			for (int column = 0; column <= row; column++) {
				double value = matrix[row * size + column];
				for (int k = 0; k < column; k++) value -= lower[row * size + k] * lower[column * size + k];
				if (row == column) {
					if (!(value > 0.0)) throw new IllegalStateException("M3 pressure matrix is not positive definite");
					minimumPivot = Math.min(minimumPivot, value);
					lower[row * size + column] = Math.sqrt(value);
				} else {
					lower[row * size + column] = value / lower[column * size + column];
				}
			}
		}
		return new MatrixDiagnostics(maximumEntry == 0.0 ? 0.0 : maximumAsymmetry / maximumEntry, minimumPivot);
	}

	private static void add(double[] matrix, int size, int row, int column, double value) {
		matrix[row * size + column] += value;
	}

	private static double dot(double[] left, double[] right) {
		double result = 0.0;
		for (int i = 0; i < left.length; i++) result += left[i] * right[i];
		return result;
	}

	private static double rms(double[] values) { return Math.sqrt(dot(values, values) / values.length); }

	private static int gridIndex(int sizeX, int sizeZ, int x, int y, int z) {
		return (y * sizeZ + z) * sizeX + x;
	}

	record MarkerState(int[] cell, float[] particleVolume, double[] particleMass,
	                   int[] materialId, int[] phaseLabel) {
		MarkerState {
			int count = cell.length;
			if (particleVolume.length != count || particleMass.length != count
					|| materialId.length != count || phaseLabel.length != count) {
				throw new IllegalArgumentException("Marker fields must have equal lengths");
			}
			cell = cell.clone();
			particleVolume = particleVolume.clone();
			particleMass = particleMass.clone();
			materialId = materialId.clone();
			phaseLabel = phaseLabel.clone();
			for (int marker = 0; marker < count; marker++) {
				if (!(Float.isFinite(particleVolume[marker]) && particleVolume[marker] > 0.0F)) {
					throw new IllegalArgumentException("Marker volume must be finite and positive");
				}
				if (!(Double.isFinite(particleMass[marker]) && particleMass[marker] > 0.0)) {
					throw new IllegalArgumentException("Marker mass must be finite and positive");
				}
			}
		}

		int size() { return cell.length; }
	}

	record Inventory(double volume, double mass) { }
	record Ledger(double totalVolume, double totalMass,
	              Map<Integer, Inventory> byMaterial, Map<Integer, Inventory> byPhase) { }
	record Scenario(String name, double heavyDensity, double lightDensity,
	                double lowerMaterialDistanceFraction, int leftTopY, int rightTopY,
	                double leftSurfaceDistanceFraction, double rightSurfaceDistanceFraction) {
		Scenario {
			if (!(heavyDensity > lightDensity && lightDensity > 0.0)) {
				throw new IllegalArgumentException("Scenario requires positive heavy and light densities");
			}
			if (!(lowerMaterialDistanceFraction > 0.0 && lowerMaterialDistanceFraction < 1.0)) {
				throw new IllegalArgumentException("Material interface distance must be inside (0,1)");
			}
			if (leftTopY < MATERIAL_INTERFACE_LOWER_Y || rightTopY <= MATERIAL_INTERFACE_LOWER_Y) {
				throw new IllegalArgumentException("Scenario column heights do not contain the material interface");
			}
			if (!(leftSurfaceDistanceFraction > 0.0 && leftSurfaceDistanceFraction <= 1.0)
					|| !(rightSurfaceDistanceFraction > 0.0 && rightSurfaceDistanceFraction <= 1.0)) {
				throw new IllegalArgumentException("Free-surface distance must be inside (0,1]");
			}
		}

		double interfaceEffectiveDensity() {
			return lowerMaterialDistanceFraction * heavyDensity
					+ (1.0 - lowerMaterialDistanceFraction) * lightDensity;
		}
	}

	record Result(int[] markerCount, double[] cellDensity, double[] inverseDensityByFace,
	              double[] pressureMatrix, double[] rightHandSide, double[] pressure,
	              double[] projectedVelocity, int pcgIterations, double pcgResidualRms,
	              double symmetryError, double minimumCholeskyPivot, double maximumDivergence) {
		double maximumVelocity() {
			double maximum = 0.0;
			for (double value : projectedVelocity) maximum = Math.max(maximum, Math.abs(value));
			return maximum;
		}
	}

	static final class Topology {
		private final int sizeX;
		private final int sizeY;
		private final int sizeZ;
		private final int[] gridToCell;
		private final int[] cellToGrid;
		private final Face[] faces;

		private final int leftTopY;
		private final int rightTopY;
		private final double leftSurfaceDistanceFraction;
		private final double rightSurfaceDistanceFraction;

		private Topology(int sizeX, int sizeY, int sizeZ, boolean[] fluid,
		                 int leftTopY, int rightTopY,
		                 double leftSurfaceDistanceFraction, double rightSurfaceDistanceFraction) {
			this.sizeX = sizeX;
			this.sizeY = sizeY;
			this.sizeZ = sizeZ;
			this.leftTopY = leftTopY;
			this.rightTopY = rightTopY;
			this.leftSurfaceDistanceFraction = leftSurfaceDistanceFraction;
			this.rightSurfaceDistanceFraction = rightSurfaceDistanceFraction;
			gridToCell = new int[fluid.length];
			Arrays.fill(gridToCell, -1);
			int count = 0;
			for (boolean value : fluid) if (value) count++;
			cellToGrid = new int[count];
			for (int grid = 0, cell = 0; grid < fluid.length; grid++) if (fluid[grid]) {
				gridToCell[grid] = cell;
				cellToGrid[cell++] = grid;
			}
			List<Face> builtFaces = new ArrayList<>();
			for (int cell = 0; cell < count; cell++) {
				int x = x(cell), y = y(cell), z = z(cell);
				for (Axis axis : Axis.values()) {
					int high = cellAt(x + axis.dx, y + axis.dy, z + axis.dz);
					if (high >= 0) {
						builtFaces.add(new Face(cell, high, axis));
					} else if (axis == Axis.Y && x <= 2 && y == leftTopY) {
						builtFaces.add(new Face(cell, -1, axis, leftSurfaceDistanceFraction));
					} else if (axis == Axis.Y && x >= 6 && y == rightTopY) {
						builtFaces.add(new Face(cell, -1, axis, rightSurfaceDistanceFraction));
					}
				}
			}
			faces = builtFaces.toArray(Face[]::new);
		}

		int cellCount() { return cellToGrid.length; }
		Face[] faces() { return faces; }
		int sizeZ() { return sizeZ; }

		int x(int cell) { return cellToGrid[cell] % sizeX; }
		int z(int cell) { return (cellToGrid[cell] / sizeX) % sizeZ; }
		int y(int cell) { return cellToGrid[cell] / (sizeX * sizeZ); }

		long faceCount(Axis axis) {
			return Arrays.stream(faces).filter(face -> face.axis() == axis && face.highCell() >= 0).count();
		}

		long atmosphereFaceCount() {
			return Arrays.stream(faces).filter(face -> face.highCell() < 0).count();
		}

		boolean isConnected() {
			boolean[] visited = new boolean[cellCount()];
			ArrayDeque<Integer> pending = new ArrayDeque<>();
			visited[0] = true;
			pending.add(0);
			int reached = 0;
			while (!pending.isEmpty()) {
				int cell = pending.removeFirst();
				reached++;
				for (Face face : faces) {
					int neighbor = face.lowCell() == cell ? face.highCell()
							: face.highCell() == cell ? face.lowCell() : -1;
					if (neighbor >= 0 && !visited[neighbor]) {
						visited[neighbor] = true;
						pending.add(neighbor);
					}
				}
			}
			return reached == cellCount();
		}

		private int cellAt(int x, int y, int z) {
			if (x < 0 || x >= sizeX || y < 0 || y >= sizeY || z < 0 || z >= sizeZ) return -1;
			return gridToCell[gridIndex(sizeX, sizeZ, x, y, z)];
		}
	}

	enum Axis {
		X(1, 0, 0), Y(0, 1, 0), Z(0, 0, 1);

		private final int dx;
		private final int dy;
		private final int dz;

		Axis(int dx, int dy, int dz) {
			this.dx = dx;
			this.dy = dy;
			this.dz = dz;
		}
	}

	record Face(int lowCell, int highCell, Axis axis, double surfaceDistanceFraction) {
		Face(int lowCell, int highCell, Axis axis) {
			this(lowCell, highCell, axis, 1.0);
		}
	}

	private static final class MutableInventory {
		private double volume;
		private double mass;

		private void add(double addedVolume, double addedMass) {
			volume += addedVolume;
			mass += addedMass;
		}
	}

	private record PcgResult(double[] pressure, int iterations, double residualRms) { }
	private record MatrixDiagnostics(double symmetryError, double minimumCholeskyPivot) { }
}
