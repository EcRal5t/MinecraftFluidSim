package com.example.granular;

/** Fixed-capacity primitive SoA for active granular parcels. */
public final class GranularParcelStore {
	public static final byte FLAG_SUPPORTED = 1;

	public record Parcel(
			float x, float y, float z,
			float velocityX, float velocityY, float velocityZ,
			long solidVolumeSvq, double mass,
			int materialHandle, int solidCompositionHandle,
			int contactAge, byte flags
	) {
		public Parcel {
			if (!(Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z)
					&& Float.isFinite(velocityX) && Float.isFinite(velocityY) && Float.isFinite(velocityZ))) {
				throw new IllegalArgumentException("Granular parcel position and velocity must be finite");
			}
			if (solidVolumeSvq <= 0L) throw new IllegalArgumentException("Granular parcel volume must be positive");
			if (!(Double.isFinite(mass) && mass > 0.0)) throw new IllegalArgumentException("Granular parcel mass must be positive");
			if (materialHandle < 0 || solidCompositionHandle < 0 || contactAge < 0) {
				throw new IllegalArgumentException("Granular parcel handles and contact age must be non-negative");
			}
		}
	}

	private final GranularRegistry registry;
	private final int capacity;
	private final float[] x, y, z, velocityX, velocityY, velocityZ;
	private final long[] solidVolumeSvq;
	private final double[] mass;
	private final int[] materialHandle, solidCompositionHandle, contactAge;
	private final byte[] flags;
	private int count;

	public GranularParcelStore(GranularRegistry registry, int capacity) {
		if (registry == null) throw new IllegalArgumentException("Granular registry is missing");
		if (capacity <= 0 || capacity > GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION) {
			throw new IllegalArgumentException("Granular parcel capacity is invalid");
		}
		this.registry = registry;
		this.capacity = capacity;
		this.x = new float[capacity]; this.y = new float[capacity]; this.z = new float[capacity];
		this.velocityX = new float[capacity]; this.velocityY = new float[capacity]; this.velocityZ = new float[capacity];
		this.solidVolumeSvq = new long[capacity]; this.mass = new double[capacity];
		this.materialHandle = new int[capacity]; this.solidCompositionHandle = new int[capacity];
		this.contactAge = new int[capacity]; this.flags = new byte[capacity];
	}

	public int count() { return count; }
	public int capacity() { return capacity; }
	public boolean hasCapacity(int additional) { return additional >= 0 && additional <= capacity - count; }
	long maximumActiveParcelSvq(int material) { return registry.material(material).maximumActiveParcelSvq(); }

	public int add(Parcel parcel) {
		validate(parcel);
		if (count == capacity) throw new BudgetExceededException("Active granular parcel budget exceeded");
		int index = count++;
		write(index, parcel);
		return index;
	}

	public boolean canAdd(Parcel parcel) {
		validate(parcel);
		return count < capacity;
	}

	public Parcel parcel(int index) {
		requireIndex(index);
		return new Parcel(x[index], y[index], z[index], velocityX[index], velocityY[index], velocityZ[index],
				solidVolumeSvq[index], mass[index], materialHandle[index], solidCompositionHandle[index],
				contactAge[index], flags[index]);
	}

	public void overwrite(int index, Parcel parcel) {
		requireIndex(index);
		validate(parcel);
		write(index, parcel);
	}

	public Parcel removeSwap(int index) {
		Parcel removed = parcel(index);
		int last = count - 1;
		Parcel moved = index == last ? null : parcel(last);
		count--;
		if (moved != null) write(index, moved);
		clear(last);
		return removed;
	}

	/** Splits an exact positive SVQ amount into a new parcel, preserving mass and velocity. */
	public int split(int index, long takenSvq) {
		Parcel original = parcel(index);
		if (takenSvq <= 0L || takenSvq >= original.solidVolumeSvq()) {
			throw new IllegalArgumentException("Granular split amount must be a strict parcel subset");
		}
		if (!hasCapacity(1)) throw new BudgetExceededException("Active granular parcel budget exceeded");
		double takenMass = original.mass() * ((double) takenSvq / original.solidVolumeSvq());
		double remainingMass = original.mass() - takenMass;
		if (!(takenMass > 0.0 && remainingMass > 0.0)) throw new IllegalArgumentException("Granular split mass is not representable");
		write(index, new Parcel(original.x(), original.y(), original.z(), original.velocityX(), original.velocityY(),
				original.velocityZ(), original.solidVolumeSvq() - takenSvq, remainingMass,
				original.materialHandle(), original.solidCompositionHandle(), original.contactAge(), original.flags()));
		return add(new Parcel(original.x(), original.y(), original.z(), original.velocityX(), original.velocityY(),
				original.velocityZ(), takenSvq, takenMass, original.materialHandle(), original.solidCompositionHandle(),
				original.contactAge(), original.flags()));
	}

	/** Merges two compatible parcels and swap-removes the second index. */
	public void merge(int first, int second) {
		if (first == second) throw new IllegalArgumentException("Cannot merge a parcel with itself");
		Parcel left = parcel(first), right = parcel(second);
		if (left.materialHandle() != right.materialHandle()
				|| left.solidCompositionHandle() != right.solidCompositionHandle()) {
			throw new IllegalArgumentException("Granular parcels have incompatible inventory identities");
		}
		long combinedVolume = Math.addExact(left.solidVolumeSvq(), right.solidVolumeSvq());
		long maximum = registry.material(left.materialHandle()).maximumActiveParcelSvq();
		if (combinedVolume > maximum) throw new IllegalArgumentException("Merged granular parcel exceeds its material limit");
		double combinedMass = left.mass() + right.mass();
		if (!Double.isFinite(combinedMass)) throw new ArithmeticException("Merged granular parcel mass overflow");
		Parcel merged = new Parcel(
				weighted(left.x(), left.solidVolumeSvq(), right.x(), right.solidVolumeSvq(), combinedVolume),
				weighted(left.y(), left.solidVolumeSvq(), right.y(), right.solidVolumeSvq(), combinedVolume),
				weighted(left.z(), left.solidVolumeSvq(), right.z(), right.solidVolumeSvq(), combinedVolume),
				weighted(left.velocityX(), left.mass(), right.velocityX(), right.mass(), combinedMass),
				weighted(left.velocityY(), left.mass(), right.velocityY(), right.mass(), combinedMass),
				weighted(left.velocityZ(), left.mass(), right.velocityZ(), right.mass(), combinedMass),
				combinedVolume, combinedMass, left.materialHandle(), left.solidCompositionHandle(),
				Math.max(left.contactAge(), right.contactAge()), (byte) (left.flags() | right.flags()));
		write(first, merged);
		int last = count - 1;
		removeSwap(second);
		if (first == last && second != last) write(second, merged);
	}

	public long totalSolidVolumeSvq() {
		long total = 0L;
		for (int index = 0; index < count; index++) total = Math.addExact(total, solidVolumeSvq[index]);
		return total;
	}

	public double totalMass() {
		double total = 0.0;
		for (int index = 0; index < count; index++) total += mass[index];
		return total;
	}

	private void validate(Parcel parcel) {
		if (parcel == null) throw new IllegalArgumentException("Granular parcel is missing");
		ParticulateMaterial material = registry.material(parcel.materialHandle());
		if (parcel.solidVolumeSvq() > material.maximumActiveParcelSvq()) {
			throw new IllegalArgumentException("Granular parcel exceeds its material volume limit");
		}
		if (!material.reactive() && parcel.solidCompositionHandle() != 0) {
			throw new IllegalArgumentException("Inert granular parcel cannot carry solid composition");
		}
		if (material.reactive() && parcel.solidCompositionHandle() == 0) {
			throw new IllegalArgumentException("Reactive granular parcel requires solid composition");
		}
	}

	private void write(int index, Parcel parcel) {
		x[index] = parcel.x(); y[index] = parcel.y(); z[index] = parcel.z();
		velocityX[index] = parcel.velocityX(); velocityY[index] = parcel.velocityY(); velocityZ[index] = parcel.velocityZ();
		solidVolumeSvq[index] = parcel.solidVolumeSvq(); mass[index] = parcel.mass();
		materialHandle[index] = parcel.materialHandle(); solidCompositionHandle[index] = parcel.solidCompositionHandle();
		contactAge[index] = parcel.contactAge(); flags[index] = parcel.flags();
	}

	private void clear(int index) {
		x[index] = y[index] = z[index] = velocityX[index] = velocityY[index] = velocityZ[index] = 0.0F;
		solidVolumeSvq[index] = 0L; mass[index] = 0.0;
		materialHandle[index] = solidCompositionHandle[index] = contactAge[index] = 0; flags[index] = 0;
	}

	private void requireIndex(int index) {
		if (index < 0 || index >= count) throw new IllegalArgumentException("Unknown granular parcel index " + index);
	}

	private static float weighted(float left, long leftWeight, float right, long rightWeight, long total) {
		return (float) (((double) left * leftWeight + (double) right * rightWeight) / total);
	}

	private static float weighted(float left, double leftWeight, float right, double rightWeight, double total) {
		return (float) ((left * leftWeight + right * rightWeight) / total);
	}

	public static final class BudgetExceededException extends IllegalStateException {
		public BudgetExceededException(String message) { super(message); }
	}
}
