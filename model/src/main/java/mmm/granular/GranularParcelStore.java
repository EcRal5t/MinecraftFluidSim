package mmm.granular;

import mmm.units.SignedDyadic;
import mmm.units.ProportionalDoubleSplit;

import java.util.Arrays;

/** Bounded, growing primitive SoA for active granular parcels; mass columns use integer 100 ng counts. */
public final class GranularParcelStore {
	public static final byte FLAG_SUPPORTED = 1;

	public record Parcel(
			float x, float y, float z,
			float velocityX, float velocityY, float velocityZ,
			long solidVolumeSvq, long massUnits,
			int materialHandle, int solidCompositionHandle,
			int contactAge, byte flags, int ceilMask
	) {
		public Parcel(float x, float y, float z, float velocityX, float velocityY, float velocityZ,
		              long solidVolumeSvq, long massUnits, int materialHandle, int solidCompositionHandle, int contactAge, byte flags) {
			this(x, y, z, velocityX, velocityY, velocityZ, solidVolumeSvq, massUnits, materialHandle, solidCompositionHandle, contactAge, flags, -1);
		}
		public double mass() { return mmm.units.Mass.kilograms(massUnits); }
		public Parcel {
			if (!(Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z)
					&& Float.isFinite(velocityX) && Float.isFinite(velocityY) && Float.isFinite(velocityZ))) {
				throw new IllegalArgumentException("Granular parcel position and velocity must be finite");
			}
			if (solidVolumeSvq <= 0L) throw new IllegalArgumentException("Granular parcel volume must be positive");
			if (!(massUnits > 0)) throw new IllegalArgumentException("Granular parcel massUnits must be positive");
			if (materialHandle < 0 || solidCompositionHandle < 0 || contactAge < 0) {
				throw new IllegalArgumentException("Granular parcel handles and contact age must be non-negative");
			}
			if (ceilMask < -1 || ceilMask > 254) throw new IllegalArgumentException("Granular occupancy selection is outside its candidate range");
		}
		public Parcel withCeilMask(int mask) {
			return new Parcel(x, y, z, velocityX, velocityY, velocityZ, solidVolumeSvq, massUnits,
					materialHandle, solidCompositionHandle, contactAge, flags, mask);
		}
	}

	private final GranularRegistry registry;
	private final int capacity;
	private float[] x, y, z, velocityX, velocityY, velocityZ;
	private long[] solidVolumeSvq;
	private long[] massUnits;
	private int[] materialHandle, solidCompositionHandle, contactAge;
	private byte[] flags;
	private byte[] ceilMask;
	private final GranularKernel kernel;
	private int count;
	private long mutationRevision;

	private GranularParcelStore(GranularParcelStore source) {
		this.registry=source.registry;
		this.capacity=source.capacity;
		this.kernel=source.kernel;
		this.x=source.x.clone();
		this.y=source.y.clone();
		this.z=source.z.clone();
		this.velocityX=source.velocityX.clone();
		this.velocityY=source.velocityY.clone();
		this.velocityZ=source.velocityZ.clone();
		this.solidVolumeSvq=source.solidVolumeSvq.clone();
		this.massUnits=source.massUnits.clone();
		this.materialHandle=source.materialHandle.clone();
		this.solidCompositionHandle=source.solidCompositionHandle.clone();
		this.contactAge=source.contactAge.clone();
		this.flags=source.flags.clone();
		this.ceilMask=source.ceilMask.clone();
		this.count=source.count;
		mutationRevision=source.mutationRevision;
	}
	/** One private candidate copy for a complete inventory transaction. */
	public GranularParcelStore copy() { return new GranularParcelStore(this); }
	public void requirePrepared(GranularParcelStore prepared,long sourceRevision) {
		if(prepared==null||prepared==this||mutationRevision!=sourceRevision||prepared.registry!=registry||prepared.capacity!=capacity||prepared.kernel!=kernel)
			throw new IllegalStateException("Prepared GranularParcelStore differs from its source generation");
	}
	/** Exchanges validated columns; the candidate receives the detached old inventory. */
	public void adoptPrepared(GranularParcelStore prepared,long sourceRevision) {
		requirePrepared(prepared,sourceRevision);
		var oldx=x;x=prepared.x;prepared.x=oldx;
		var oldy=y;y=prepared.y;prepared.y=oldy;
		var oldz=z;z=prepared.z;prepared.z=oldz;
		var oldvelocityX=velocityX;velocityX=prepared.velocityX;prepared.velocityX=oldvelocityX;
		var oldvelocityY=velocityY;velocityY=prepared.velocityY;prepared.velocityY=oldvelocityY;
		var oldvelocityZ=velocityZ;velocityZ=prepared.velocityZ;prepared.velocityZ=oldvelocityZ;
		var oldsolidVolumeSvq=solidVolumeSvq;solidVolumeSvq=prepared.solidVolumeSvq;prepared.solidVolumeSvq=oldsolidVolumeSvq;
		var oldmassUnits=massUnits;massUnits=prepared.massUnits;prepared.massUnits=oldmassUnits;
		var oldmaterialHandle=materialHandle;materialHandle=prepared.materialHandle;prepared.materialHandle=oldmaterialHandle;
		var oldsolidCompositionHandle=solidCompositionHandle;solidCompositionHandle=prepared.solidCompositionHandle;prepared.solidCompositionHandle=oldsolidCompositionHandle;
		var oldcontactAge=contactAge;contactAge=prepared.contactAge;prepared.contactAge=oldcontactAge;
		var oldflags=flags;flags=prepared.flags;prepared.flags=oldflags;
		var oldceilMask=ceilMask;ceilMask=prepared.ceilMask;prepared.ceilMask=oldceilMask;
		var oldcount=count;count=prepared.count;prepared.count=oldcount;
		mutationRevision++;
	}

	public GranularParcelStore(GranularRegistry registry, int capacity) {
		this(registry, capacity, null);
	}
	public GranularParcelStore(GranularRegistry registry, int capacity, int sizeX, int sizeY, int sizeZ, double cellSize) {
		this(registry, capacity, new GranularKernel(sizeX, sizeY, sizeZ, cellSize));
	}
	public GranularParcelStore(GranularRegistry registry,int capacity,mmm.spatial.SpatialGrid grid,double cellSize) {
		this(registry,capacity,new GranularKernel(grid,cellSize));
	}
	private GranularParcelStore(GranularRegistry registry, int capacity, GranularKernel kernel) {
		if (registry == null) throw new IllegalArgumentException("Granular registry is missing");
		if (capacity <= 0 || capacity > GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION) {
			throw new IllegalArgumentException("Granular parcel capacity is invalid");
		}
		this.registry = registry;
		this.kernel = kernel;
		this.capacity = capacity;
		this.x = new float[0]; this.y = new float[0]; this.z = new float[0];
		this.velocityX = new float[0]; this.velocityY = new float[0]; this.velocityZ = new float[0];
		this.solidVolumeSvq = new long[0]; this.massUnits = new long[0];
		this.materialHandle = new int[0]; this.solidCompositionHandle = new int[0];
		this.contactAge = new int[0]; this.flags = new byte[0];
		this.ceilMask = new byte[0];
	}

	private void ensureAllocated(int required) {
		if(required>capacity)throw new BudgetExceededException("Active granular parcel budget exceeded");
		if(required<=x.length)return;
		int length=Math.min(capacity,Math.max(required,Math.max(16,2*x.length)));
		x=Arrays.copyOf(x,length);
		y=Arrays.copyOf(y,length);
		z=Arrays.copyOf(z,length);
		velocityX=Arrays.copyOf(velocityX,length);
		velocityY=Arrays.copyOf(velocityY,length);
		velocityZ=Arrays.copyOf(velocityZ,length);
		solidVolumeSvq=Arrays.copyOf(solidVolumeSvq,length);
		massUnits=Arrays.copyOf(massUnits,length);
		materialHandle=Arrays.copyOf(materialHandle,length);
		solidCompositionHandle=Arrays.copyOf(solidCompositionHandle,length);
		contactAge=Arrays.copyOf(contactAge,length);
		flags=Arrays.copyOf(flags,length);
		ceilMask=Arrays.copyOf(ceilMask,length);
	}

	public int count() { return count; }
	public int capacity() { return capacity; }
	public void requireGeometry(GranularKernel geometry) {
		if (kernel != null && !kernel.sameGeometry(geometry)) throw new IllegalArgumentException("Granular occupancy domain geometry differs");
	}
	public boolean hasCapacity(int additional) { return additional >= 0 && additional <= capacity - count; }
	/** Monotonic counter over every structural mutation; plan consumers re-verify it before commit. */
	public long mutationRevision() { return mutationRevision; }
	long maximumActiveParcelSvq(int material) { return registry.material(material).maximumActiveParcelSvq(); }

	public int add(Parcel parcel) {
		validate(parcel);
		if (count == capacity) throw new BudgetExceededException("Active granular parcel budget exceeded");
		return appendPrepared(parcel);
	}
	/** Inventory plans have already validated the parcel and its unchanged append slot. */
	int appendPrepared(Parcel parcel) {
		int mask = prepareMask(parcel, -1);
		ensureAllocated(count+1);
		int index = count++;
		write(index, parcel, mask);
		mutationRevision++;
		return index;
	}

	public boolean canAdd(Parcel parcel) {
		prepareAppend(parcel);
		return count < capacity;
	}
	/** Resolves the candidate selection before an inventory plan can publish any source column. */
	public Parcel prepareAppend(Parcel parcel) {
		validate(parcel); return parcel.withCeilMask(prepareMask(parcel, -1));
	}

	public Parcel parcel(int index) {
		requireIndex(index);
		return new Parcel(x[index], y[index], z[index], velocityX[index], velocityY[index], velocityZ[index],
				solidVolumeSvq[index], massUnits[index], materialHandle[index], solidCompositionHandle[index],
				contactAge[index], flags[index], (ceilMask[index] & 255) == 255 ? -1 : ceilMask[index] & 255);
	}

	public void overwrite(int index, Parcel parcel) {
		parcel = prepareOverwrite(index, parcel);
		boolean changedGeometry = solidVolumeSvq[index] != parcel.solidVolumeSvq();
		write(index, parcel, parcel.ceilMask());
		if (changedGeometry) { contactAge[index] = 0; flags[index] &= ~FLAG_SUPPORTED; }
		mutationRevision++;
	}
	/** Prepares the exact future occupancy choice without changing any carrier column. */
	public Parcel prepareOverwrite(int index, Parcel parcel) {
		requireIndex(index);
		validate(parcel);
		int mask = prepareMask(parcel, index);
		return parcel.withCeilMask(mask);
	}

	public Parcel removeSwap(int index) {
		Parcel removed = parcel(index);
		int last = count - 1;
		Parcel moved = index == last ? null : parcel(last);
		count--;
		if (moved != null) write(index, moved, moved.ceilMask());
		clear(last);
		mutationRevision++;
		return removed;
	}

	/** Splits an exact positive SVQ amount into a new parcel, preserving massUnits and velocity. */
	public int split(int index, long takenSvq) {
		Parcel original = parcel(index);
		if (takenSvq <= 0L || takenSvq >= original.solidVolumeSvq()) {
			throw new IllegalArgumentException("Granular split amount must be a strict parcel subset");
		}
		if (!hasCapacity(1)) throw new BudgetExceededException("Active granular parcel budget exceeded");
		long takenMass = mmm.units.Mass.portion(original.massUnits(), takenSvq, original.solidVolumeSvq());
		long remainingMass = Math.subtractExact(original.massUnits(), takenMass);
		if (!(takenMass > 0.0 && remainingMass > 0.0)) throw new IllegalArgumentException("Granular split massUnits is not representable");
		Parcel remaining = prepareOverwrite(index, new Parcel(original.x(), original.y(), original.z(), original.velocityX(), original.velocityY(),
				original.velocityZ(), original.solidVolumeSvq() - takenSvq, remainingMass,
				original.materialHandle(), original.solidCompositionHandle(), 0, (byte) (original.flags() & ~FLAG_SUPPORTED)));
		Parcel taken = prepareAppend(new Parcel(original.x(), original.y(), original.z(), original.velocityX(), original.velocityY(),
				original.velocityZ(), takenSvq, takenMass, original.materialHandle(), original.solidCompositionHandle(),
				0, (byte) (original.flags() & ~FLAG_SUPPORTED)));
		write(index, remaining, remaining.ceilMask()); mutationRevision++;
		return appendPrepared(taken);
	}

	/** Merges two compatible parcels and swap-removes the second index. */
	public void merge(int first, int second) {
		if (first == second) throw new IllegalArgumentException("Cannot merge a parcel with itself");
		Parcel left = parcel(first), right = parcel(second);
		if (!GranularMergeKinematics.preservesAngularMomentum(left, right)) {
			throw new IllegalArgumentException("Granular merge cannot retain the pair's internal angular momentum");
		}
		if (left.materialHandle() != right.materialHandle()
				|| left.solidCompositionHandle() != right.solidCompositionHandle()) {
			throw new IllegalArgumentException("Granular parcels have incompatible inventory identities");
		}
		long combinedVolume = Math.addExact(left.solidVolumeSvq(), right.solidVolumeSvq());
		long maximum = registry.material(left.materialHandle()).maximumActiveParcelSvq();
		if (combinedVolume > maximum) throw new IllegalArgumentException("Merged granular parcel exceeds its material limit");
		long combinedMass = Math.addExact(left.massUnits(), right.massUnits());
		Parcel merged = new Parcel(
				weighted(left.x(), left.massUnits(), right.x(), right.massUnits(), combinedMass),
				weighted(left.y(), left.massUnits(), right.y(), right.massUnits(), combinedMass),
				weighted(left.z(), left.massUnits(), right.z(), right.massUnits(), combinedMass),
				weighted(left.velocityX(), left.massUnits(), right.velocityX(), right.massUnits(), combinedMass),
				weighted(left.velocityY(), left.massUnits(), right.velocityY(), right.massUnits(), combinedMass),
				weighted(left.velocityZ(), left.massUnits(), right.velocityZ(), right.massUnits(), combinedMass),
				combinedVolume, combinedMass, left.materialHandle(), left.solidCompositionHandle(),
				0, (byte) ((left.flags() | right.flags()) & ~FLAG_SUPPORTED));
		merged = prepareOverwrite(first, merged);
		write(first, merged, merged.ceilMask());
		int last = count - 1;
		removeSwap(second);
		if (first == last && second != last) write(second, merged, merged.ceilMask());
		mutationRevision++;
	}

	public long totalSolidVolumeSvq() {
		long total = 0L;
		for (int index = 0; index < count; index++) total = Math.addExact(total, solidVolumeSvq[index]);
		return total;
	}

	public double totalMass() {
		return mmm.units.Mass.kilograms(totalMassUnits());
	}

	/** Order-independent exact aggregate of the authoritative binary64 massUnits column. */
	public long totalMassUnits() { return mmm.units.Mass.sum(massUnits, count); }

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

	private void write(int index, Parcel parcel, int mask) {
		x[index] = parcel.x(); y[index] = parcel.y(); z[index] = parcel.z();
		velocityX[index] = parcel.velocityX(); velocityY[index] = parcel.velocityY(); velocityZ[index] = parcel.velocityZ();
		solidVolumeSvq[index] = parcel.solidVolumeSvq(); massUnits[index] = parcel.massUnits();
		materialHandle[index] = parcel.materialHandle(); solidCompositionHandle[index] = parcel.solidCompositionHandle();
		contactAge[index] = parcel.contactAge(); flags[index] = parcel.flags();
		ceilMask[index] = (byte) mask;
	}
	private int prepareMask(Parcel parcel, int previous) {
		int mask = parcel.ceilMask();
		if (previous >= 0 && x[previous] == parcel.x() && y[previous] == parcel.y()
				&& z[previous] == parcel.z() && solidVolumeSvq[previous] == parcel.solidVolumeSvq()) {
			int stored = (ceilMask[previous] & 255) == 255 ? -1 : ceilMask[previous] & 255;
			if (mask < 0) mask = stored;
			if (kernel != null && stored >= 0 && mask == stored) return mask;
		}
		if (kernel != null) {
			kernel.capture(parcel.x(), parcel.y(), parcel.z(), parcel.solidVolumeSvq());
			if (mask < 0) mask = kernel.canonicalMask();
			kernel.requireMask(mask);
		}
		return mask;
	}

	private void clear(int index) {
		x[index] = y[index] = z[index] = velocityX[index] = velocityY[index] = velocityZ[index] = 0.0F;
		solidVolumeSvq[index] = 0L; massUnits[index] = 0L;
		materialHandle[index] = solidCompositionHandle[index] = contactAge[index] = 0; flags[index] = 0;
		ceilMask[index] = 0;
	}

	private void requireIndex(int index) {
		if (index < 0 || index >= count) throw new IllegalArgumentException("Unknown granular parcel index " + index);
	}

	private static float weighted(float left, double leftWeight, float right, double rightWeight, double total) {
		return (float) ((left * leftWeight + right * rightWeight) / total);
	}

	public static final class BudgetExceededException extends IllegalStateException {
		public BudgetExceededException(String message) { super(message); }
	}
}
