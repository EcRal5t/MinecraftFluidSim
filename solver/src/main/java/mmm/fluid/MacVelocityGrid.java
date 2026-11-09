package mmm.fluid;

import mmm.spatial.SpatialGrid;

/**
 * Staggered liquid momentum and its derived velocity, inertia and extrapolation workspace.
 * Face momentum uses kg m/s; pressure and viscosity update the current velocity before the
 * substep publishes its momentum against the same finite-volume face masses.
 */
final class MacVelocityGrid {
	private long momentumRevision;
	long momentumRevision() { return momentumRevision; }
	void markMomentumChanged() { momentumRevision++; }
	final double[] momentumX;
	final double[] momentumY;
	final double[] momentumZ;
	private final SpatialGrid grid;
	final float[] velocityX;
	final float[] velocityY;
	final float[] velocityZ;
	// Availability flags for disposable velocity observations.
	final float[] velocityWeightX;
	final float[] velocityWeightY;
	final float[] velocityWeightZ;
	// Dual face mass divided by the physical cell volume (kg/m^3).
	final double[] faceMassX;
	final double[] faceMassY;
	final double[] faceMassZ;
	final boolean[] velocityValidX;
	final boolean[] velocityValidY;
	final boolean[] velocityValidZ;


	MacVelocityGrid(int sizeX, int sizeY, int sizeZ) {
		this(SpatialGrid.rectangular(sizeX,sizeY,sizeZ));
	}
	MacVelocityGrid(SpatialGrid grid) {
		this.grid = grid;
		this.velocityX = new float[grid.faceCount(0)];
		this.velocityY = new float[grid.faceCount(1)];
		this.velocityZ = new float[grid.faceCount(2)];
		this.momentumX = new double[velocityX.length];
		this.momentumY = new double[velocityY.length];
		this.momentumZ = new double[velocityZ.length];
		this.velocityWeightX = new float[velocityX.length];
		this.velocityWeightY = new float[velocityY.length];
		this.velocityWeightZ = new float[velocityZ.length];
		this.faceMassX = new double[velocityX.length];
		this.faceMassY = new double[velocityY.length];
		this.faceMassZ = new double[velocityZ.length];
		this.velocityValidX = new boolean[velocityX.length];
		this.velocityValidY = new boolean[velocityY.length];
		this.velocityValidZ = new boolean[velocityZ.length];
	}

	MacMomentumState captureMomentum() {
		return new MacMomentumState(grid, momentumX, momentumY, momentumZ);
	}

	void restoreMomentum(MacMomentumState state) {
		if (!state.grid().equals(grid))
			throw new IllegalArgumentException("MAC momentum restore differs from its grid");
		state.copyInto(momentumX, momentumY, momentumZ);
		markMomentumChanged();
	}

	/** The mass columns are physical kg on this same dual control volume. */
	void publishMomentum(double[] massX, double[] massY, double[] massZ) {
		publish(momentumX, velocityX, massX);
		publish(momentumY, velocityY, massY);
		publish(momentumZ, velocityZ, massZ);
		markMomentumChanged();
	}

	void deriveVelocity(double[] massX, double[] massY, double[] massZ) {
		derive(momentumX, velocityX, massX);
		derive(momentumY, velocityY, massY);
		derive(momentumZ, velocityZ, massZ);
	}

	private static void publish(double[] momentum, float[] velocity, double[] mass) {
		if (mass.length != momentum.length) throw new IllegalArgumentException("Face mass shape differs from momentum");
		for (int face = 0; face < momentum.length; face++) {
			if (!(Double.isFinite(mass[face]) && mass[face] >= 0) || !Float.isFinite(velocity[face]))
				throw new IllegalArgumentException("Face inertia or velocity is invalid");
			momentum[face] = mass[face] * velocity[face];
			if (!Double.isFinite(momentum[face])) throw new ArithmeticException("Face momentum overflow");
		}
	}

	private static void derive(double[] momentum, float[] velocity, double[] mass) {
		if (mass.length != momentum.length) throw new IllegalArgumentException("Face mass shape differs from momentum");
		for (int face = 0; face < momentum.length; face++) {
			if (!(Double.isFinite(mass[face]) && mass[face] >= 0)) throw new IllegalArgumentException("Face inertia is invalid");
			if (mass[face] == 0) {
				if (momentum[face] != 0) throw new IllegalStateException("Unassigned face momentum requires conservative transport");
				velocity[face] = 0;
			} else {
				velocity[face] = (float) (momentum[face] / mass[face]);
				if (!Float.isFinite(velocity[face])) throw new ArithmeticException("Face velocity overflow");
			}
		}
	}
}
