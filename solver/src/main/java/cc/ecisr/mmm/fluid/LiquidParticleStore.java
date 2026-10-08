package cc.ecisr.mmm.fluid;

import java.util.Arrays;
import cc.ecisr.mmm.units.SignedDyadic;
import cc.ecisr.mmm.units.ExactVolume;

/**
 * Unique owner of the authoritative liquid marker columns: position, velocity, volume, solver
 * mass, material handle and pressure-phase label, plus the live count and array capacity.
 * Exact extensive quantities retain any remainder beyond their float/double observations.
 * The carrier mutation coordinator aligns structural edits with chemistry and thermal columns.
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
	private double[] mass;
	private ExactVolume[] exactVolume; private SignedDyadic[] exactMass;
	private int[] materialHandle;
	private int[] phaseLabel;

	LiquidParticleStore(int maximumParticleCount) {
		if (maximumParticleCount <= 0) throw new IllegalArgumentException("Maximum particle count must be positive");
		this.maximumParticleCount = maximumParticleCount;
		int initialCapacity = Math.min(16_384, maximumParticleCount);
		this.positionX = new float[initialCapacity];
		this.positionY = new float[initialCapacity];
		this.positionZ = new float[initialCapacity];
		this.velocityX = new float[initialCapacity];
		this.velocityY = new float[initialCapacity];
		this.velocityZ = new float[initialCapacity];
		this.volume = new float[initialCapacity];
		this.mass = new double[initialCapacity];
		this.exactVolume = new ExactVolume[initialCapacity];
		this.exactMass = new SignedDyadic[initialCapacity];
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

	void setVolume(int index, float value) { volume[index] = value; exactVolume[index] = null; }

	double mass(int index) { return mass[index]; }

	void setMass(int index, double value) { mass[index] = value; exactMass[index] = null; }

	ExactVolume exactVolume(int index) { return exactVolume[index] == null ? ExactVolume.of(volume[index]) : exactVolume[index]; }
	SignedDyadic exactMass(int index) { return exactMass[index] == null ? SignedDyadic.of(mass[index]) : exactMass[index]; }
	void setQuantities(int index, SignedDyadic v, SignedDyadic m) { setQuantities(index, ExactVolume.of(v), m); }
	void setQuantities(int index, ExactVolume v, SignedDyadic m) {
		volume[index] = v.toFloat(); mass[index] = m.toDouble();
		exactVolume[index] = v.equals(ExactVolume.of(volume[index])) ? null : v;
		exactMass[index] = m.equals(SignedDyadic.of(mass[index])) ? null : m;
	}
	LiquidInventoryState inventory() {
		var v = new ExactVolume[count]; var m = new SignedDyadic[count];
		for (int p = 0; p < count; p++) { v[p] = exactVolume(p); m[p] = exactMass(p); }
		return new LiquidInventoryState(v, m);
	}
	ExactVolume totalVolume() {
		var total = ExactVolume.of(SignedDyadic.sum(volume, count));
		for (int p = 0; p < count; p++) if (exactVolume[p] != null) total = total.add(exactVolume[p].subtract(SignedDyadic.of(volume[p])));
		return total;
	}
	SignedDyadic totalMass() {
		var total = SignedDyadic.sum(mass, count);
		for (int p = 0; p < count; p++) if (exactMass[p] != null) total = total.add(exactMass[p].subtract(SignedDyadic.of(mass[p])));
		return total;
	}

	int materialHandle(int index) { return materialHandle[index]; }

	void setMaterialHandle(int index, int value) { materialHandle[index] = value; }

	int phaseLabel(int index) { return phaseLabel[index]; }

	void setPhaseLabel(int index, int value) { phaseLabel[index] = value; }

	/** Wholesale count transition for restore, clear and reseed; marker-level edits go through spawn/swapRemove. */
	void setCount(int value) {
		if (value < count) { Arrays.fill(exactVolume, value, count, null); Arrays.fill(exactMass, value, count, null); }
		this.count = value;
	}

	void incrementCount() { this.count = count + 1; }

	/**
	 * Main-column swap-remove; the caller coordinates side columns and derived links around it.
	 * The removed ordinal is always the current last marker, matching the former removeParticle order.
	 */
	void swapRemove(int index) {
		count--;
		var lastVolume = exactVolume[count]; var lastMass = exactMass[count];
		exactVolume[count] = null; exactMass[count] = null;
		if (index == count) return;
		positionX[index] = positionX[count];
		positionY[index] = positionY[count];
		positionZ[index] = positionZ[count];
		velocityX[index] = velocityX[count];
		velocityY[index] = velocityY[count];
		velocityZ[index] = velocityZ[count];
		volume[index] = volume[count];
		mass[index] = mass[count];
		exactVolume[index] = lastVolume; exactMass[index] = lastMass;
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
		mass = Arrays.copyOf(mass, capacity);
		exactVolume = Arrays.copyOf(exactVolume, capacity); exactMass = Arrays.copyOf(exactMass, capacity);
		materialHandle = Arrays.copyOf(materialHandle, capacity);
		phaseLabel = Arrays.copyOf(phaseLabel, capacity);
		return capacity;
	}

	double[] copyMass() { return Arrays.copyOf(mass, count); }

	int[] copyMaterialHandles() { return Arrays.copyOf(materialHandle, count); }

	/**
	 * Solver-internal, non-escaping array view for in-place algorithms (reseed, advection,
	 * chemistry reaction scan, render capture). Capacity must not change while a view is held;
	 * no code outside the solver package may obtain it.
	 */
	DirectArrays directArrays() {
		return new DirectArrays(positionX, positionY, positionZ, velocityX, velocityY, velocityZ,
				volume, mass, materialHandle, phaseLabel);
	}

	static final class DirectArrays {
		final float[] positionX;
		final float[] positionY;
		final float[] positionZ;
		final float[] velocityX;
		final float[] velocityY;
		final float[] velocityZ;
		final float[] volume;
		final double[] mass;
		final int[] materialHandle;
		final int[] phaseLabel;

		private DirectArrays(float[] positionX, float[] positionY, float[] positionZ,
		                     float[] velocityX, float[] velocityY, float[] velocityZ,
		                     float[] volume, double[] mass, int[] materialHandle, int[] phaseLabel) {
			this.positionX = positionX;
			this.positionY = positionY;
			this.positionZ = positionZ;
			this.velocityX = velocityX;
			this.velocityY = velocityY;
			this.velocityZ = velocityZ;
			this.volume = volume;
			this.mass = mass;
			this.materialHandle = materialHandle;
			this.phaseLabel = phaseLabel;
		}
	}
}
