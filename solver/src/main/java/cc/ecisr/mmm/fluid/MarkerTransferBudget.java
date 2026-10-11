package cc.ecisr.mmm.fluid;

import java.util.Arrays;
import cc.ecisr.mmm.units.SolverUnitScale;

/** Energy-admissible FLIP transfer in joules on connected dependencies of the current numerical operators. */
final class MarkerTransferBudget {
	private final int nx, ny, nz, cells, faces;
	private final float h;
	private final SolverUnitScale unitScale;
	private final MacVelocityGrid mac;
	private final FlipCutCellGeometry geometry;
	private final int[] offsets;
	private final double[][] predicted;
	private int[] parent = new int[0], markerRoots = new int[0];
	private final int[] dependencyHead, initialRoot, componentNext, queue;
	private int[] dependencySource = new int[0], dependencyNext = new int[0], componentHead = new int[0];
	private boolean[] requiredComponent = new boolean[0];
	private int dependencyCount;
	private double[][] sums = new double[0][];
	private int parcelStart, markerStart, size;
	private boolean singleComponent, anyResolved;
	private boolean[] resolvedRoots = new boolean[0];

	MarkerTransferBudget(int nx, int ny, int nz, float h, MacVelocityGrid mac, FlipCutCellGeometry geometry) {
		unitScale = SolverUnitScale.forCellSize(h);
		this.nx = nx; this.ny = ny; this.nz = nz; this.h = h; this.mac = mac; this.geometry = geometry; cells = nx * ny * nz;
		offsets = new int[] {0, mac.velocityX.length, mac.velocityX.length + mac.velocityY.length};
		faces = offsets[2] + mac.velocityZ.length;
		dependencyHead = new int[faces]; initialRoot = new int[faces]; componentNext = new int[faces]; queue = new int[faces];
		predicted = new double[][] {new double[mac.velocityX.length], new double[mac.velocityY.length], new double[mac.velocityZ.length]};
	}

	void capture(double densityScale) {
		float[][] velocity = velocities(); double[][] weights = weights();
		for (int a = 0; a < 3; a++) for (int f = 0; f < velocity[a].length; f++) {
			predicted[a][f] = 0.5 * unitScale.kilograms(weights[a][f] * densityScale) * velocity[a][f] * (double) velocity[a][f];
		}
	}

	boolean begin(LiquidParticleStore particles, int parcels) {
		parcelStart = faces + 2 * cells + 1; markerStart = parcelStart + 3 * parcels;
		size = markerStart + particles.count();
		if (parent.length < size) parent = new int[size];
		for (int i = 0; i < size; i++) parent[i] = i;
		Arrays.fill(dependencyHead, -1); dependencyCount = 0;
		connectMarkers(particles, false);
		singleComponent = true;
		for (int p = 1; p < particles.count(); p++)
			if (root(markerStart + p) != root(markerStart)) { singleComponent = false; break; }
		return !singleComponent;
	}

	int face(int axis, int local) { return offsets[axis] + local; }
	int pressureRow(int row) { return row >= 0 ? faces + row : faces + cells - row; }
	int parcel(int ordinal, int axis) { return parcelStart + 3 * ordinal + axis; }
	int airPotential(int cell) { return faces + cell; }
	/** Extrapolation has directed inputs: an unused output never couples its sources. */
	void recordExtrapolationInput(int axis, int target, int source) {
		if (dependencyCount == dependencySource.length) {
			int capacity = Math.max(32, 2 * dependencyCount);
			dependencySource = Arrays.copyOf(dependencySource, capacity); dependencyNext = Arrays.copyOf(dependencyNext, capacity);
		}
		int output = face(axis, target);
		dependencySource[dependencyCount] = face(axis, source); dependencyNext[dependencyCount] = dependencyHead[output];
		dependencyHead[output] = dependencyCount++;
	}
	void connect(int a, int b) {
		int ra = root(a), rb = root(b);
		if (ra != rb) parent[Math.max(ra, rb)] = Math.min(ra, rb);
	}
	private int root(int vertex) {
		while (parent[vertex] != vertex) { parent[vertex] = parent[parent[vertex]]; vertex = parent[vertex]; }
		return vertex;
	}

	private void connectRequiredExtrapolation(LiquidParticleStore particles) {
		if (componentHead.length < size) { componentHead = new int[size]; requiredComponent = new boolean[size]; }
		Arrays.fill(componentHead, 0, size, -1); Arrays.fill(requiredComponent, 0, size, false);
		for (int f = 0; f < faces; f++) {
			int r = initialRoot[f] = root(f); componentNext[f] = componentHead[r]; componentHead[r] = f;
		}
		int end = 0;
		for (int p = 0; p < particles.count(); p++) end = requireComponent(root(markerStart + p), end);
		for (int start = 0; start < end; start++) {
			int target = queue[start];
			for (int e = dependencyHead[target]; e >= 0; e = dependencyNext[e]) {
				int source = dependencySource[e]; connect(target, source);
				end = requireComponent(initialRoot[source], end);
			}
		}
	}

	private int requireComponent(int component, int end) {
		if (!requiredComponent[component]) {
			requiredComponent[component] = true;
			for (int f = componentHead[component]; f >= 0; f = componentNext[f]) queue[end++] = f;
		}
		return end;
	}

	/** Uses the same clamped positive trilinear coefficients as P2G and valid G2P. */
	private void connectMarkers(LiquidParticleStore particles, boolean sampling) {
		boolean[][] valid = {mac.velocityValidX, mac.velocityValidY, mac.velocityValidZ};
		float[][] open = {geometry.openFaceField(0), geometry.openFaceField(1), geometry.openFaceField(2)};
		for (int p = 0; p < particles.count(); p++) for (int a = 0; a < 3; a++) {
			int sx = nx + (a == 0 ? 1 : 0), sy = ny + (a == 1 ? 1 : 0), sz = nz + (a == 2 ? 1 : 0);
			float x = Math.clamp(particles.positionX(p) / h - (a == 0 ? 0 : 0.5F), 0, sx - 1);
			float y = Math.clamp(particles.positionY(p) / h - (a == 1 ? 0 : 0.5F), 0, sy - 1);
			float z = Math.clamp(particles.positionZ(p) / h - (a == 2 ? 0 : 0.5F), 0, sz - 1);
			int ix = (int) x, iy = (int) y, iz = (int) z;
			float fx = x - ix, fy = y - iy, fz = z - iz;
			for (int oz = 0; oz <= 1; oz++) for (int oy = 0; oy <= 1; oy++) for (int ox = 0; ox <= 1; ox++) {
				float w = (ox == 0 ? 1 - fx : fx) * (oy == 0 ? 1 - fy : fy) * (oz == 0 ? 1 - fz : fz);
				int f = (Math.min(iz + oz, sz - 1) * sy + Math.min(iy + oy, sy - 1)) * sx + Math.min(ix + ox, sx - 1);
				if (w > 0 && open[a][f] > 0 && (!sampling || valid[a][f])) connect(markerStart + p, face(a, f));
			}
		}
	}

	/** Finalizes the actual support before choosing its interpolation space or projecting velocity. */
	void prepareSupport(LiquidParticleStore particles, boolean[] fullCells) {
		if (!singleComponent) { connectMarkers(particles, true); connectRequiredExtrapolation(particles); }
		if (markerRoots.length < particles.count()) markerRoots = new int[particles.count()];
		if (resolvedRoots.length < size) resolvedRoots = new boolean[size];
		Arrays.fill(resolvedRoots, 0, size, true);
		singleComponent = true;
		for (int p = 0; p < particles.count(); p++) {
			int r = markerRoots[p] = root(markerStart + p);
			if (p > 0 && r != markerRoots[0]) singleComponent = false;
			int cx = Math.clamp((int) (particles.positionX(p) / h), 0, nx - 1);
			int cy = Math.clamp((int) (particles.positionY(p) / h), 0, ny - 1);
			int cz = Math.clamp((int) (particles.positionZ(p) / h), 0, nz - 1);
			resolvedRoots[r] &= fullCells[(cz * ny + cy) * nx + cx];
		}
		anyResolved = false;
		for (int p = 0; p < particles.count(); p++) anyResolved |= resolved(p);
	}

	void prepare(LiquidParticleStore particles, float[] picX, float[] picY, float[] picZ,
			float[] deltaX, float[] deltaY, float[] deltaZ, double[] incomingEnergy, double densityScale) {
		if (singleComponent) return;
		if (sums.length == 0 || sums[0].length < size || anyResolved && sums.length < 8) sums = new double[anyResolved ? 8 : 6][size];
		else for (double[] values : sums) Arrays.fill(values, 0, size, 0);
		float[][] pic = {picX, picY, picZ}, delta = {deltaX, deltaY, deltaZ};
		for (int p = 0; p < particles.count(); p++) {
			int r = markerRoots[p]; double mass = unitScale.kilograms(particles.mass(p));
			if (anyResolved && resolvedRoots[r]) sums[6][r] += incomingEnergy[p];
			for (int a = 0; a < 3; a++) {
				sums[0][r] += 0.5 * mass * delta[a][p] * (double) delta[a][p];
				sums[1][r] += mass * pic[a][p] * (double) delta[a][p];
				sums[2][r] += 0.5 * mass * pic[a][p] * (double) pic[a][p];
			}
		}
		float[][] velocity = velocities(); double[][] weights = weights();
		float[][] previous = {mac.p2gVelocityX, mac.p2gVelocityY, mac.p2gVelocityZ};
		for (int a = 0; a < 3; a++) for (int f = 0; f < velocity[a].length; f++) {
			int r = root(face(a, f));
			sums[3][r] += predicted[a][f];
			if (anyResolved && resolvedRoots[r]) sums[7][r] += 0.5 * unitScale.kilograms(weights[a][f] * densityScale) * previous[a][f] * (double) previous[a][f];
			sums[4][r] += 0.5 * unitScale.kilograms(weights[a][f] * densityScale) * velocity[a][f] * (double) velocity[a][f];
		}
		for (int i = 0; i < size; i++) if (parent[i] == i)
			sums[5][i] = FlipTransferScheme.maximumEnergyStableTransfer(sums[0][i], sums[1][i], sums[2][i],
					Math.max(sums[2][i], Math.min(sums[3][i], sums[4][i]) + (anyResolved && resolvedRoots[i] ? sums[6][i] - sums[7][i] : 0)));
	}
	double transfer(int marker) { return sums[5][markerRoots[marker]]; }
	boolean singleComponent() { return singleComponent; }
	boolean anyResolved() { return anyResolved; }
	boolean resolved(int marker) { return resolvedRoots[markerRoots[marker]]; }
	int component(int axis, int face) { return root(face(axis, face)); }
	int componentCapacity() { return size; }
	private float[][] velocities() { return new float[][] {mac.velocityX, mac.velocityY, mac.velocityZ}; }
	private double[][] weights() { return new double[][] {mac.p2gWeightX, mac.p2gWeightY, mac.p2gWeightZ}; }
}
