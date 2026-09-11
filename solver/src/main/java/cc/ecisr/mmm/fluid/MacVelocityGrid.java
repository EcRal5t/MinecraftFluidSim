package cc.ecisr.mmm.fluid;

/**
 * Discardable MAC face-velocity workspace: face velocities, P2G scatter targets and weights,
 * and valid-face flags. Rebuilt every mechanics substep from the authoritative marker columns;
 * never checkpoint truth. Solver-internal package-private access only.
 */
final class MacVelocityGrid {
	final float[] velocityX;
	final float[] velocityY;
	final float[] velocityZ;
	final float[] p2gVelocityX;
	final float[] p2gVelocityY;
	final float[] p2gVelocityZ;
	// P2G accumulation weights are reused as 0/1 flags during air extrapolation.
	final float[] velocityWeightX;
	final float[] velocityWeightY;
	final float[] velocityWeightZ;
	// Retained mass/volume weights for physical impulse exchange and kinetic energy.
	final float[] p2gWeightX;
	final float[] p2gWeightY;
	final float[] p2gWeightZ;
	final boolean[] velocityValidX;
	final boolean[] velocityValidY;
	final boolean[] velocityValidZ;

	MacVelocityGrid(int sizeX, int sizeY, int sizeZ) {
		this.velocityX = new float[(sizeX + 1) * sizeY * sizeZ];
		this.velocityY = new float[sizeX * (sizeY + 1) * sizeZ];
		this.velocityZ = new float[sizeX * sizeY * (sizeZ + 1)];
		this.p2gVelocityX = new float[velocityX.length];
		this.p2gVelocityY = new float[velocityY.length];
		this.p2gVelocityZ = new float[velocityZ.length];
		this.velocityWeightX = new float[velocityX.length];
		this.velocityWeightY = new float[velocityY.length];
		this.velocityWeightZ = new float[velocityZ.length];
		this.p2gWeightX = new float[velocityX.length];
		this.p2gWeightY = new float[velocityY.length];
		this.p2gWeightZ = new float[velocityZ.length];
		this.velocityValidX = new boolean[velocityX.length];
		this.velocityValidY = new boolean[velocityY.length];
		this.velocityValidZ = new boolean[velocityZ.length];
	}
}
