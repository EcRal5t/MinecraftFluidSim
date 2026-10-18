package mmm.fluid;

import java.util.Arrays;

/**
 * Unique owner of the authoritative liquid marker columns: position, velocity, volume, solver
 * mass, material handle and pressure-phase label, plus the live count and array capacity.
 * Growth, wholesale count transitions and main-column swap-remove happen only here. Chemistry
 * and thermal side columns are not owned by this store; the domain coordinates them until the
 * carrier mutation coordinator exists. S12 moves this class to the solver domain package.
 */
final class LiquidParticleStore {
	private final int maximumParticleCount;
	private int count;
	private float[] positionX;
	private float[] positionY;
	private float[] positionZ;
	private float[] velocityX;
	private float[] velocityY;
	private float[] velocityZ;
	private float[] volume;
	private long[] massUnits;
	private final mmm.units.SolverUnitScale units;
	private int[] materialHandle;
	private int[] phaseLabel;

	LiquidParticleStore(int maximumParticleCount, mmm.units.SolverUnitScale units) {
		if (maximumParticleCount <= 0) throw new IllegalArgumentException("Maximum particle count must be positive");
		this.maximumParticleCount = maximumParticleCount;
		this.units = java.util.Objects.requireNonNull(units);
		int initialCapacity = Math.min(16_384, maximumParticleCount);
		this.positionX = new float[initialCapacity];
		this.positionY = new float[initialCapacity];
		this.positionZ = new float[initialCapacity];
		this.velocityX = new float[initialCapacity];
		this.velocityY = new float[initialCapacity];
		this.velocityZ = new float[initialCapacity];
		this.volume = new float[initialCapacity];
		this.massUnits = new long[initialCapacity];
		this.materialHandle = new int[initialCapacity];
		this.phaseLabel = new int[initialCapacity];
	}

	int count() { return count; }

	int capacity() { return positionX.length; }

	int maximumParticleCount() { return maximumParticleCount; }

	float positionX(int index) { return positionX[index]; }

	void setPositionX(int index, float value) { positionX[index] = value; }

	float positionY(int index) { return positionY[index]; }

	void setPositionY(int index, float value) { positionY[index] = value; }

	float positionZ(int index) { return positionZ[index]; }

	void setPositionZ(int index, float value) { positionZ[index] = value; }

	float velocityX(int index) { return velocityX[index]; }

	void setVelocityX(int index, float value) { velocityX[index] = value; }

	float velocityY(int index) { return velocityY[index]; }

	void setVelocityY(int index, float value) { velocityY[index] = value; }

	float velocityZ(int index) { return velocityZ[index]; }

	void setVelocityZ(int index, float value) { velocityZ[index] = value; }

	float volume(int index) { return volume[index]; }

	void setVolume(int index, float value) { volume[index] = value; }

	double mass(int index) { return units.normalizedMass(massUnits[index]); }
	long massUnits(int index) { return massUnits[index]; }

	void setMassUnits(int index, long value) { if (value < 0) throw new IllegalArgumentException("Negative liquid mass"); massUnits[index] = value; }

	int materialHandle(int index) { return materialHandle[index]; }

	void setMaterialHandle(int index, int value) { materialHandle[index] = value; }

	int phaseLabel(int index) { return phaseLabel[index]; }

	void setPhaseLabel(int index, int value) { phaseLabel[index] = value; }

	/** Wholesale count transition for restore, clear and reseed; marker-level edits go through spawn/swapRemove. */
	void setCount(int value) { this.count = value; }

	void incrementCount() { this.count = count + 1; }

	/**
	 * Main-column swap-remove; the caller coordinates side columns and derived links around it.
	 * The removed ordinal is always the current last marker, matching the former removeParticle order.
	 */
	void swapRemove(int index) {
		count--;
		if (index == count) return;
		positionX[index] = positionX[count];
		positionY[index] = positionY[count];
		positionZ[index] = positionZ[count];
		velocityX[index] = velocityX[count];
		velocityY[index] = velocityY[count];
		velocityZ[index] = velocityZ[count];
		volume[index] = volume[count];
		massUnits[index] = massUnits[count];
		materialHandle[index] = materialHandle[count];
		phaseLabel[index] = phaseLabel[count];
	}

	/** Verbatim growth from the former ensureParticleCapacity; returns the resulting capacity. */
	int ensureCapacity(int required) {
		if (required > maximumParticleCount) {
			throw new IllegalStateException("Marker capacity request exceeds the domain marker budget");
		}
		if (required <= positionX.length) return positionX.length;
		int capacity = Math.min(maximumParticleCount, Math.max(required, Math.max(1, positionX.length * 2)));
		positionX = Arrays.copyOf(positionX, capacity);
		positionY = Arrays.copyOf(positionY, capacity);
		positionZ = Arrays.copyOf(positionZ, capacity);
		velocityX = Arrays.copyOf(velocityX, capacity);
		velocityY = Arrays.copyOf(velocityY, capacity);
		velocityZ = Arrays.copyOf(velocityZ, capacity);
		volume = Arrays.copyOf(volume, capacity);
		massUnits = Arrays.copyOf(massUnits, capacity);
		materialHandle = Arrays.copyOf(materialHandle, capacity);
		phaseLabel = Arrays.copyOf(phaseLabel, capacity);
		return capacity;
	}

	long[] copyMassUnits() { return Arrays.copyOf(massUnits, count); }

	int[] copyMaterialHandles() { return Arrays.copyOf(materialHandle, count); }

	/**
	 * Solver-internal, non-escaping array view for in-place algorithms (reseed, advection,
	 * chemistry reaction scan, render capture). Capacity must not change while a view is held;
	 * no code outside the solver package may obtain it.
	 */
	DirectArrays directArrays() {
		return new DirectArrays(positionX, positionY, positionZ, velocityX, velocityY, velocityZ,
				volume, massUnits, materialHandle, phaseLabel);
	}

	static final class DirectArrays {
		final float[] positionX;
		final float[] positionY;
		final float[] positionZ;
		final float[] velocityX;
		final float[] velocityY;
		final float[] velocityZ;
		final float[] volume;
		final long[] massUnits;
		final int[] materialHandle;
		final int[] phaseLabel;

		private DirectArrays(float[] positionX, float[] positionY, float[] positionZ,
		                     float[] velocityX, float[] velocityY, float[] velocityZ,
		                     float[] volume, long[] massUnits, int[] materialHandle, int[] phaseLabel) {
			this.positionX = positionX;
			this.positionY = positionY;
			this.positionZ = positionZ;
			this.velocityX = velocityX;
			this.velocityY = velocityY;
			this.velocityZ = velocityZ;
			this.volume = volume;
			this.massUnits = massUnits;
			this.materialHandle = materialHandle;
			this.phaseLabel = phaseLabel;
		}
	}
}
