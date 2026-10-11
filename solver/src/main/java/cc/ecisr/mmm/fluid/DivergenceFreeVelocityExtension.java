package cc.ecisr.mmm.fluid;

import java.util.Arrays;

/** Extends incompressible motion through the air fringe while retaining every liquid face. */
final class DivergenceFreeVelocityExtension {
	private final int nx, ny, nz, cells;
	private final FlipCutCellGeometry geometry;
	private final FlipPressurePhaseGeometry liquid;
	final boolean[] advectionCells;
	private final boolean[] fringeCells;
	private final boolean[] active;
	private final double[] potential, residual, direction, product, diagonal;
	private final int[] first, second, face, axis;
	private final int[] queue;
	private final boolean[] visited;
	private int edges;
	private MarkerTransferBudget dependencies;

	DivergenceFreeVelocityExtension(int nx, int ny, int nz, FlipCutCellGeometry geometry, FlipPressurePhaseGeometry liquid) {
		this.nx = nx; this.ny = ny; this.nz = nz; this.geometry = geometry; this.liquid = liquid;
		cells = nx * ny * nz;
		advectionCells = new boolean[cells]; fringeCells = new boolean[cells]; active = new boolean[cells];
		potential = new double[cells]; residual = new double[cells]; direction = new double[cells];
		product = new double[cells]; diagonal = new double[cells];
		first = new int[3 * cells]; second = new int[3 * cells];
		face = new int[3 * cells]; axis = new int[3 * cells];
		queue = new int[cells]; visited = new boolean[cells];
	}

	void extend(MacVelocityGrid mac, boolean[] fluid, boolean[] solid, int[] markerCount) {
		extend(mac, fluid, solid, markerCount, null);
	}

	void extend(MacVelocityGrid mac, boolean[] fluid, boolean[] solid, int[] markerCount, MarkerTransferBudget dependencies) {
		this.dependencies = dependencies;
		prepareAdvectionCells(fluid, solid, markerCount);
		prepareConnectedCells(fringeCells, fluid, solid, markerCount, false);
		projectFringe(mac, fluid, solid);
		this.dependencies = null;
	}

	/** Classifies connected finite-volume carriers without changing any velocity. */
	void prepareAdvectionCells(boolean[] fluid, boolean[] solid, int[] markerCount) {
		prepareConnectedCells(advectionCells, fluid, solid, markerCount, true);
	}

	private void prepareConnectedCells(boolean[] connected, boolean[] fluid, boolean[] solid, int[] markerCount, boolean requireWetFace) {
		System.arraycopy(fluid, 0, connected, 0, cells);
		int head = 0, tail = 0;
		for (int c = 0; c < cells; c++) if (fluid[c]) queue[tail++] = c;
		while (head < tail) {
			int c = queue[head++], x = c % nx, y = c / nx % ny, z = c / (nx * ny);
			for (int d = 0; d < 6; d++) {
				int ax = x + (d == 0 ? -1 : d == 1 ? 1 : 0);
				int ay = y + (d == 2 ? -1 : d == 3 ? 1 : 0);
				int az = z + (d == 4 ? -1 : d == 5 ? 1 : 0);
				if (ax < 0 || ax >= nx || ay < 0 || ay >= ny || az < 0 || az >= nz) continue;
				int neighbor = cell(ax, ay, az);
				if (solid[neighbor] || connected[neighbor] || markerCount[neighbor] == 0) continue;
				float aperture = d < 2 ? geometry.xFaceOpen(Math.max(x, ax), y, z)
						: d < 4 ? geometry.yFaceOpen(x, Math.max(y, ay), z) : geometry.zFaceOpen(x, y, Math.max(z, az));
				if (aperture > 0 && (!requireWetFace || liquid.sharedLiquidFaceArea(
						Math.min(c, neighbor), Math.max(c, neighbor), d / 2, geometry) > 0)) {
					connected[neighbor] = true; queue[tail++] = neighbor;
				}
			}
		}
	}

	private void projectFringe(MacVelocityGrid mac, boolean[] fluid, boolean[] solid) {
		Arrays.fill(potential, 0); Arrays.fill(residual, 0); Arrays.fill(diagonal, 0);
		for (int c = 0; c < cells; c++) active[c] = fringeCells[c] && !fluid[c];
		edges = 0;
		for (int z = 0; z < nz; z++) for (int y = 0; y < ny; y++) for (int x = 1; x < nx; x++)
			add(cell(x - 1, y, z), cell(x, y, z), (z * ny + y) * (nx + 1) + x,
					0, geometry.xFaceOpen(x, y, z), fluid, mac.velocityX, mac.p2gWeightX);
		for (int z = 0; z < nz; z++) for (int y = 1; y < ny; y++) for (int x = 0; x < nx; x++)
			add(cell(x, y - 1, z), cell(x, y, z), (z * (ny + 1) + y) * nx + x,
					1, geometry.yFaceOpen(x, y, z), fluid, mac.velocityY, mac.p2gWeightY);
		for (int z = 1; z < nz; z++) for (int y = 0; y < ny; y++) for (int x = 0; x < nx; x++)
			add(cell(x, y, z - 1), cell(x, y, z), (z * ny + y) * nx + x,
					2, geometry.zFaceOpen(x, y, z), fluid, mac.velocityZ, mac.p2gWeightZ);
		prepareClosedComponents(mac, fluid, solid);
		double initial = 0, rr = 0;
		for (int c = 0; c < cells; c++) {
			direction[c] = diagonal[c] > 0 ? residual[c] / diagonal[c] : 0;
			initial += residual[c] * residual[c]; rr += residual[c] * direction[c];
		}
		double tolerance = initial * Math.pow(8 * Math.ulp(1.0F), 2);
		for (int iteration = 0; rr > 0; iteration++) {
			Arrays.fill(product, 0);
			for (int e = 0; e < edges; e++) {
				int a = first[e], b = second[e]; double difference = direction[a] - direction[b];
				if (active[a]) product[a] += difference;
				if (active[b]) product[b] -= difference;
			}
			double denominator = 0; for (int c = 0; c < cells; c++) denominator += direction[c] * product[c];
			if (!(denominator > 0)) throw new IllegalStateException("Air velocity extension has an incompatible flux");
			double alpha = rr / denominator, next = 0, error = 0;
			for (int c = 0; c < cells; c++) {
				potential[c] += alpha * direction[c]; residual[c] -= alpha * product[c];
				error += residual[c] * residual[c];
				if (diagonal[c] > 0) next += residual[c] * residual[c] / diagonal[c];
			}
			if (error <= tolerance) break;
			if (iteration >= 2 * cells) throw new IllegalStateException("Air velocity extension did not converge");
			for (int c = 0; c < cells; c++) direction[c] = (diagonal[c] > 0 ? residual[c] / diagonal[c] : 0) + next / rr * direction[c];
			rr = next;
		}
		for (int e = 0; e < edges; e++) {
			float[] velocity = axis[e] == 0 ? mac.velocityX : axis[e] == 1 ? mac.velocityY : mac.velocityZ;
			velocity[face[e]] += potential[first[e]] - potential[second[e]];
		}
	}

	private void add(int a, int b, int f, int component, float aperture, boolean[] fluid, float[] velocity, double[] massWeight) {
		if (aperture != 1 || !active[a] && !active[b]) return;
		if (dependencies != null) {
			if (active[a]) dependencies.connect(dependencies.airPotential(a), dependencies.face(component, f));
			if (active[b]) dependencies.connect(dependencies.airPotential(b), dependencies.face(component, f));
		}
		if (active[a]) residual[a] -= velocity[f];
		if (active[b]) residual[b] += velocity[f];
		// Carrier-supported values have already received their physical impulses.
		// Only massless extrapolation values belong to this auxiliary solve.
		if (fluid[a] || fluid[b] || massWeight[f] > 0) return;
		first[edges] = a; second[edges] = b; face[edges] = f; axis[edges++] = component;
		if (active[a]) diagonal[a]++;
		if (active[b]) diagonal[b]++;
	}

	private int cell(int x, int y, int z) { return (z * ny + y) * nx + x; }

	private void prepareClosedComponents(MacVelocityGrid mac, boolean[] fluid, boolean[] solid) {
		Arrays.fill(visited, false);
		for (int start = 0; start < cells; start++) {
			if (!active[start] || visited[start]) continue;
			int head = 0, tail = 1; queue[0] = start; visited[start] = true;
			boolean open = false;
			while (head < tail) {
				int c = queue[head++], x = c % nx, y = c / nx % ny, z = c / (nx * ny);
				for (int d = 0; d < 6; d++) {
					int ax = x + (d == 0 ? -1 : d == 1 ? 1 : 0);
					int ay = y + (d == 2 ? -1 : d == 3 ? 1 : 0);
					int az = z + (d == 4 ? -1 : d == 5 ? 1 : 0);
					if (ax < 0 || ax >= nx || ay < 0 || ay >= ny || az < 0 || az >= nz) continue;
					int neighbor = cell(ax, ay, az);
					float aperture = d < 2 ? geometry.xFaceOpen(Math.max(x, ax), y, z)
							: d < 4 ? geometry.yFaceOpen(x, Math.max(y, ay), z) : geometry.zFaceOpen(x, y, Math.max(z, az));
					if (aperture != 1 || fluid[neighbor] || solid[neighbor]) continue;
					double massWeight = d < 2 ? mac.p2gWeightX[(z * ny + y) * (nx + 1) + Math.max(x, ax)]
							: d < 4 ? mac.p2gWeightY[(z * (ny + 1) + Math.max(y, ay)) * nx + x]
							: mac.p2gWeightZ[(Math.max(z, az) * ny + y) * nx + x];
					if (massWeight > 0) continue;
					if (!active[neighbor]) open = true;
					else if (!visited[neighbor]) { visited[neighbor] = true; queue[tail++] = neighbor; }
				}
			}
			// The prescribed liquid flux fixes a closed component's mean compression.
			// Distribute that mean uniformly; the scalar potential retains a gauge anchor.
			if (!open) {
				double mean = 0; for (int entry = 0; entry < tail; entry++) mean += residual[queue[entry]];
				mean /= tail;
				for (int entry = 0; entry < tail; entry++) residual[queue[entry]] -= mean;
				active[start] = false; residual[start] = 0; diagonal[start] = 0;
			}
		}
	}
}
