package mmm.fluid;

import java.util.Arrays;

/** Fixed marker geometry and SI masses for a normalized P2G map and its exact discrete adjoint. */
final class MarkerVelocityTransfer {
	private double[] mass = new double[0];
	private int[][] faces = new int[3][0];
	private double[][] coefficients = new double[3][0], faceMass = new double[3][0];
	private byte[][] counts = new byte[3][0];

	/** Replaces this snapshot only after every mass and sparse coefficient has been checked. */
	void capture(FluidDomain domain) {
		int count = domain.particles.count(), entries = Math.multiplyExact(count, 8);
		double[] nextMass = new double[count];
		int[][] nextFaces = new int[3][entries];
		double[][] nextCoefficients = new double[3][entries], nextFaceMass = new double[3][];
		byte[][] nextCounts = new byte[3][count];
		for (int marker = 0; marker < count; marker++) {
			double solverMass = domain.particles.mass(marker);
			if (!(solverMass > 0 && Double.isFinite(solverMass))) throw new IllegalStateException("Invalid marker mass");
			double kilograms = domain.unitScale.kilograms(solverMass);
			if (!(kilograms > 0 && Double.isFinite(kilograms))) throw new IllegalStateException("Invalid marker SI mass");
			nextMass[marker] = kilograms;
		}
		for (int axis = 0; axis < 3; axis++) {
			int nx = domain.sizeX + (axis == 0 ? 1 : 0), ny = domain.sizeY + (axis == 1 ? 1 : 0), nz = domain.sizeZ + (axis == 2 ? 1 : 0);
			float[] aperture = domain.geometry.cutCells.openFaceField(axis);
			nextFaceMass[axis] = new double[Math.multiplyExact(Math.multiplyExact(nx, ny), nz)];
			for (int marker = 0; marker < count; marker++) {
				float gx = coordinate(domain.particles.positionX(marker), domain.config.cellSize(), axis == 0, nx);
				float gy = coordinate(domain.particles.positionY(marker), domain.config.cellSize(), axis == 1, ny);
				float gz = coordinate(domain.particles.positionZ(marker), domain.config.cellSize(), axis == 2, nz);
				int ix = (int) gx, iy = (int) gy, iz = (int) gz;
				float fx = gx - ix, fy = gy - iy, fz = gz - iz;
				int endX = Math.min(1, nx - 1 - ix), endY = Math.min(1, ny - 1 - iy), endZ = Math.min(1, nz - 1 - iz);
				for (int oz = 0; oz <= endZ; oz++) for (int oy = 0; oy <= endY; oy++) for (int ox = 0; ox <= endX; ox++) {
					int face = ((iz + oz) * ny + iy + oy) * nx + ix + ox;
					float geometricWeight = FluidDomainStepCoordinator.trilinearWeight(ox, oy, oz, fx, fy, fz);
					if (geometricWeight == 0 || aperture[face] == 0) continue;
					double weightedMass = geometricWeight * nextMass[marker];
					if (!(weightedMass > 0 && Double.isFinite(weightedMass))) throw new IllegalStateException("Unrepresentable marker face mass");
					int entry = marker * 8 + nextCounts[axis][marker]++;
					nextFaces[axis][entry] = face; nextCoefficients[axis][entry] = weightedMass;
					nextFaceMass[axis][face] += weightedMass;
					if (!Double.isFinite(nextFaceMass[axis][face])) throw new IllegalStateException("Marker face mass overflow");
				}
			}
			for (int marker = 0; marker < count; marker++) for (int slot = 0; slot < nextCounts[axis][marker]; slot++) {
				int entry = marker * 8 + slot;
				double coefficient = nextCoefficients[axis][entry] / nextFaceMass[axis][nextFaces[axis][entry]];
				if (!(coefficient > 0 && Double.isFinite(coefficient))) throw new IllegalStateException("Unrepresentable normalized marker coefficient");
				nextCoefficients[axis][entry] = coefficient;
			}
		}
		mass = nextMass; faces = nextFaces; coefficients = nextCoefficients; faceMass = nextFaceMass; counts = nextCounts;
	}

	private static float coordinate(float position, float h, boolean normal, int size) {
		if (!Float.isFinite(position)) throw new IllegalStateException("Invalid marker position");
		return Math.clamp(position / h - (normal ? 0 : .5F), 0, size - 1);
	}

	int markerCount() { return mass.length; }
	int faceCount(int axis) { return faceMass[axis].length; }
	double massKilograms(int marker) { return mass[marker]; }
	double faceMassKilograms(int axis, int face) { return faceMass[axis][face]; }
	boolean supportsFace(int axis, int face) { return faceMass[axis][face] > 0; }
	int coefficientCount(int axis, int marker) { return counts[axis][marker]; }
	int faceIndex(int axis, int marker, int slot) { checkSlot(axis, marker, slot); return faces[axis][marker * 8 + slot]; }
	double coefficient(int axis, int marker, int slot) { checkSlot(axis, marker, slot); return coefficients[axis][marker * 8 + slot]; }
	private void checkSlot(int axis, int marker, int slot) {
		if (slot < 0 || slot >= counts[axis][marker]) throw new IndexOutOfBoundsException("Marker coefficient slot");
	}

	/** J v. Unsupported and closed faces are zero; finite vectors use the captured coefficients. */
	void apply(int axis, double[] markerVelocity, double[] faceVelocity) {
		checkShape(axis, markerVelocity, faceVelocity);
		Arrays.fill(faceVelocity, 0);
		for (int marker = 0; marker < mass.length; marker++) for (int slot = 0; slot < counts[axis][marker]; slot++) {
			int entry = marker * 8 + slot;
			faceVelocity[faces[axis][entry]] += coefficients[axis][entry] * markerVelocity[marker];
		}
	}

	/** J^T f. Face loads and marker loads use the ordinary SI virtual-work pairing. */
	void transpose(int axis, double[] faceLoad, double[] markerLoad) {
		checkShape(axis, markerLoad, faceLoad);
		Arrays.fill(markerLoad, 0);
		for (int marker = 0; marker < mass.length; marker++) for (int slot = 0; slot < counts[axis][marker]; slot++) {
			int entry = marker * 8 + slot;
			markerLoad[marker] += coefficients[axis][entry] * faceLoad[faces[axis][entry]];
		}
	}

	void apply(double[][] markerVelocity, double[][] faceVelocity) {
		checkShapes(markerVelocity, faceVelocity);
		for (int axis = 0; axis < 3; axis++) apply(axis, markerVelocity[axis], faceVelocity[axis]);
	}
	void transpose(double[][] faceLoad, double[][] markerLoad) {
		checkShapes(markerLoad, faceLoad);
		for (int axis = 0; axis < 3; axis++) transpose(axis, faceLoad[axis], markerLoad[axis]);
	}

	/** Publishes the same J using double sums and one float rounding, after checking all three axes. */
	void publish(double[][] markerVelocity, float[][] faceVelocity, double[][] workspace) {
		if (faceVelocity.length != 3) throw new IllegalArgumentException("Expected three MAC axes");
		for (int axis = 0; axis < 3; axis++) if (faceVelocity[axis].length != faceCount(axis)) throw new IllegalArgumentException("MAC shape mismatch");
		if (faceVelocity[0] == faceVelocity[1] || faceVelocity[0] == faceVelocity[2] || faceVelocity[1] == faceVelocity[2]) throw new IllegalArgumentException("Aliased MAC axes");
		apply(markerVelocity, workspace);
		for (double[] axis : workspace) for (double value : axis) if (!Float.isFinite((float) value)) {
			throw new IllegalStateException("Unrepresentable published MAC velocity");
		}
		for (int axis = 0; axis < 3; axis++) for (int face = 0; face < faceCount(axis); face++) faceVelocity[axis][face] = (float) workspace[axis][face];
	}

	private void checkShape(int axis, double[] markers, double[] field) {
		if (markers.length != mass.length || field.length != faceCount(axis) || markers == field) throw new IllegalArgumentException("Marker transfer shape or alias mismatch");
	}
	private void checkShapes(double[][] markers, double[][] field) {
		if (markers.length != 3 || field.length != 3) throw new IllegalArgumentException("Expected three velocity axes");
		for (int axis = 0; axis < 3; axis++) checkShape(axis, markers[axis], field[axis]);
		for (double[] input : markers) for (double[] output : field) if (input == output) throw new IllegalArgumentException("Aliased marker transfer vectors");
		for (int first = 0; first < 3; first++) for (int second = first + 1; second < 3; second++) if (field[first] == field[second] || markers[first] == markers[second]) {
			throw new IllegalArgumentException("Aliased marker transfer axes");
		}
	}
}
