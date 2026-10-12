package mmm.fluid;

import mmm.granular.GranularCouplingFields;
import mmm.granular.GranularDepositStore;
import mmm.granular.GranularLimits;
import mmm.granular.GranularParcelStore;
import mmm.granular.GranularRegistry;
import mmm.granular.ParticulateMaterial;
import mmm.granular.SolidVolume;

import java.util.Arrays;
import java.util.function.IntToLongFunction;

/** Collision-resolved granular motion preview from pressure-corrected velocities, committed after liquid advection. */
final class FlipGranularCollision {
	private final int sizeX, sizeY, sizeZ;
	private final float cellSize;
	private final GranularCollisionGeometry geometry;
	private final GranularSphereCollision sphereCollision;
	private final float[] nextX, nextY, nextZ, nextVelocityX, nextVelocityY, nextVelocityZ;
	private final int[] nextContactAge;
	private final byte[] nextFlags;
	private final boolean[] capacityPending;
	private int previewCount;
	private int lastCapacityHeld;

	FlipGranularCollision(int sizeX, int sizeY, int sizeZ, float cellSize,
	                      FlipCutCellGeometry cutCells, GranularDepositStore deposits, int capacity) {
		this.sizeX = sizeX; this.sizeY = sizeY; this.sizeZ = sizeZ;
		this.cellSize = cellSize; this.geometry = new GranularCollisionGeometry(sizeX, sizeY, sizeZ, cellSize, cutCells, deposits);
		this.sphereCollision = new GranularSphereCollision(sizeX, sizeY, sizeZ, cellSize, cutCells,
				deposits);
		this.nextX = new float[capacity]; this.nextY = new float[capacity]; this.nextZ = new float[capacity];
		this.nextVelocityX = new float[capacity]; this.nextVelocityY = new float[capacity];
		this.nextVelocityZ = new float[capacity];
		this.nextContactAge = new int[capacity]; this.nextFlags = new byte[capacity];
		this.capacityPending = new boolean[capacity];
	}

	void preview(GranularParcelStore parcels, GranularRegistry registry, float dt) {
		if (!(Float.isFinite(dt) && dt > 0.0F)) throw new IllegalArgumentException("Granular preview dt is invalid");
		previewCount = parcels.count();
		lastCapacityHeld = 0;
		int operations = GranularLimits.MAX_OPERATIONS_PER_LOGICAL_STEP;
		for (int index = 0; index < previewCount; index++) {
			GranularParcelStore.Parcel parcel = parcels.parcel(index);
			ParticulateMaterial material = registry.material(parcel.materialHandle());
			float radius = collisionRadius(parcel.solidVolumeSvq());
			float[] position = {parcel.x(), parcel.y(), parcel.z()};
			float[] velocity = {parcel.velocityX(), parcel.velocityY(), parcel.velocityZ()};
			var motion = sphereCollision.preview(position, velocity, radius, dt, material, operations);
			operations -= motion.operations();
			nextX[index] = motion.x(); nextY[index] = motion.y(); nextZ[index] = motion.z();
			nextVelocityX[index] = motion.vx(); nextVelocityY[index] = motion.vy(); nextVelocityZ[index] = motion.vz();
			float restingSpeed = cellSize * 0.05F;
			boolean resting = motion.supported() && motion.vx() * motion.vx() + motion.vy() * motion.vy()
					+ motion.vz() * motion.vz() <= restingSpeed * restingSpeed;
			nextContactAge[index] = resting ? incrementContactAge(parcel.contactAge()) : 0;
			nextFlags[index] = resting ? (byte) (parcel.flags() | GranularParcelStore.FLAG_SUPPORTED)
					: (byte) (parcel.flags() & ~GranularParcelStore.FLAG_SUPPORTED);
		}
	}

	/** Deterministic sub-cell placement for representative parcels; no particle-particle contact is implied. */
	GranularCouplingFields.Position spawnPosition(
			int cellX, int cellY, int cellZ, long solidVolumeSvq, long sequence,
			float preferredX, float preferredY, float preferredZ
	) {
		if (cellX < 0 || cellX >= sizeX || cellY < 0 || cellY >= sizeY || cellZ < 0 || cellZ >= sizeZ
				|| solidVolumeSvq <= 0L || sequence < 0L) {
			throw new IllegalArgumentException("Granular spawn request is outside the domain");
		}
		float radius = collisionRadius(solidVolumeSvq);
		float margin = Math.min(cellSize * 0.45F, radius + cellSize * 0.025F);
		float minimumX = cellX * cellSize + margin, maximumX = (cellX + 1) * cellSize - margin;
		float minimumY = cellY * cellSize + margin, maximumY = (cellY + 1) * cellSize - margin;
		float minimumZ = cellZ * cellSize + margin, maximumZ = (cellZ + 1) * cellSize - margin;
		boolean preferred = Float.isFinite(preferredX) && Float.isFinite(preferredY) && Float.isFinite(preferredZ);
		for (int attempt = 0; attempt < 48; attempt++) {
			long sample = Math.addExact(sequence + 1L, attempt * 65_537L);
			float unitX = (float) radicalInverse(sample, 2);
			float unitY = (float) radicalInverse(sample, 3);
			float unitZ = (float) radicalInverse(sample, 5);
			float x, y, z;
			if (preferred) {
				float jitter = cellSize * 0.32F;
				x = clamp(preferredX + (unitX - 0.5F) * jitter, minimumX, maximumX);
				y = clamp(preferredY + (unitY - 0.5F) * jitter, minimumY, maximumY);
				z = clamp(preferredZ + (unitZ - 0.5F) * jitter, minimumZ, maximumZ);
			} else {
				x = minimumX + (maximumX - minimumX) * unitX;
				y = minimumY + (maximumY - minimumY) * unitY;
				z = minimumZ + (maximumZ - minimumZ) * unitZ;
			}
			if (positionValid(x, y, z, solidVolumeSvq)) return new GranularCouplingFields.Position(x, y, z);
		}
		return null;
	}

	boolean positionValid(float x, float y, float z, long solidVolumeSvq) {
		float radius = collisionRadius(solidVolumeSvq);
		if (x < radius || x > sizeX * (double) cellSize - radius
				|| y < radius || y > sizeY * (double) cellSize - radius
				|| z < radius || z > sizeZ * (double) cellSize - radius
				|| sphereTouchesSolid(x, y, z, radius)) return false;
		return true;
	}

	boolean verticalSpherePathTouchesSolid(float x, float fromY, float z, float toY, long solidVolumeSvq) {
		return geometry.verticalSpherePathTouchesSolid(x, fromY, z, toY, collisionRadius(solidVolumeSvq));
	}

	int verticalSpherePathWork(float x, float fromY, float z, float toY, long solidVolumeSvq) {
		return geometry.verticalSpherePathWork(x, fromY, z, toY, collisionRadius(solidVolumeSvq));
	}

	boolean bedGeometryFits(int cell, long bulk) { return geometry.bedGeometryFits(cell, bulk); }

	boolean spherePathTouchesSolid(float x, float y, float z, float toX, float toY, float toZ, long solidVolumeSvq) {
		return geometry.spherePathTouchesSolid(x, y, z, toX, toY, toZ, collisionRadius(solidVolumeSvq));
	}

	int spherePathWork(float x, float y, float z, float toX, float toY, float toZ, long solidVolumeSvq) {
		return geometry.spherePathWork(x, y, z, toX, toY, toZ, collisionRadius(solidVolumeSvq));
	}

	GranularCouplingFields.Position previewPosition(int index, GranularParcelStore.Parcel ignored) {
		if (index < 0 || index >= previewCount) throw new IllegalArgumentException("Unknown granular preview index");
		return new GranularCouplingFields.Position(nextX[index], nextY[index], nextZ[index]);
	}

	/**
	 * Resolves exact cell capacity per representative parcel. A failed candidate remains at its current
	 * position without cancelling unrelated motion; bounded retry passes allow vacated cells to propagate
	 * through a falling column while retaining deterministic operation limits.
	 */
	int resolveCapacity(
			GranularParcelStore parcels, GranularCouplingFields current, IntToLongFunction geometricCapacity
	) {
		if (parcels == null || current == null || geometricCapacity == null || parcels.count() != previewCount) {
			throw new IllegalArgumentException("Granular capacity preview is incomplete");
		}
		if (previewCount == 0) return 0;
		long[] planned = current.copySolidSvq();
		for (int cell = 0; cell < planned.length; cell++) {
			long capacity = geometricCapacity.applyAsLong(cell);
			if (planned[cell] > capacity) {
				throw new IllegalStateException("Granular current occupancy exceeds geometry at cell " + cell
						+ ": solidSvq=" + planned[cell] + ", capacitySvq=" + capacity);
			}
		}
		Arrays.fill(capacityPending, 0, previewCount, true);
		int pending = previewCount;
		int dimensionPasses = Math.max(sizeX, Math.max(sizeY, sizeZ));
		int operationPasses = Math.max(1, GranularLimits.MAX_OPERATIONS_PER_LOGICAL_STEP / previewCount);
		int maximumPasses = Math.min(dimensionPasses, operationPasses);
		for (int pass = 0; pass < maximumPasses && pending > 0; pass++) {
			int accepted = 0;
			boolean reverse = (pass & 1) != 0;
			for (int ordinal = 0; ordinal < previewCount; ordinal++) {
				int index = reverse ? previewCount - 1 - ordinal : ordinal;
				if (!capacityPending[index]) continue;
				GranularParcelStore.Parcel parcel = parcels.parcel(index);
				GranularCouplingFields.Position currentPosition = new GranularCouplingFields.Position(
						parcel.x(), parcel.y(), parcel.z());
				current.removePlannedParcel(planned, currentPosition, parcel.solidVolumeSvq());
				GranularCouplingFields.Position candidate = new GranularCouplingFields.Position(
						nextX[index], nextY[index], nextZ[index]);
				if (current.tryPlanParcel(planned, candidate, parcel.solidVolumeSvq(), geometricCapacity)) {
					capacityPending[index] = false;
					pending--; accepted++;
				} else if (!current.tryPlanParcel(planned, currentPosition, parcel.solidVolumeSvq(), geometricCapacity)) {
					throw new IllegalStateException("Granular current occupancy no longer fits geometry");
				}
			}
			if (accepted == 0) break;
		}
		for (int index = 0; index < previewCount; index++) {
			if (capacityPending[index]) holdParcel(parcels, index);
		}
		lastCapacityHeld = pending;
		return pending;
	}

	int lastCapacityHeld() { return lastCapacityHeld; }

	/** Momentum received by the fixed geometry and capacity constraints, in kg m/s. */
	record BoundaryImpulse(double x, double y, double z) { }

	BoundaryImpulse commit(GranularParcelStore parcels) {
		if (parcels.count() != previewCount) throw new IllegalStateException("Granular parcel count changed during preview");
		double impulseX = 0, impulseY = 0, impulseZ = 0;
		for (int index = 0; index < previewCount; index++) {
			GranularParcelStore.Parcel parcel = parcels.parcel(index);
			impulseX += parcel.mass() * ((double) parcel.velocityX() - nextVelocityX[index]);
			impulseY += parcel.mass() * ((double) parcel.velocityY() - nextVelocityY[index]);
			impulseZ += parcel.mass() * ((double) parcel.velocityZ() - nextVelocityZ[index]);
			parcels.overwrite(index, new GranularParcelStore.Parcel(nextX[index], nextY[index], nextZ[index],
					nextVelocityX[index], nextVelocityY[index], nextVelocityZ[index], parcel.solidVolumeSvq(),
					parcel.mass(), parcel.materialHandle(), parcel.solidCompositionHandle(),
					nextContactAge[index], nextFlags[index]));
		}
		previewCount = 0;
		return new BoundaryImpulse(impulseX, impulseY, impulseZ);
	}

	private void holdParcel(GranularParcelStore parcels, int index) {
		GranularParcelStore.Parcel parcel = parcels.parcel(index);
		boolean supported = sphereCollision.supportsAtRest(parcel.x(), parcel.y(), parcel.z(),
				collisionRadius(parcel.solidVolumeSvq()));
		nextX[index] = parcel.x(); nextY[index] = parcel.y(); nextZ[index] = parcel.z();
		nextVelocityX[index] = nextVelocityY[index] = nextVelocityZ[index] = 0.0F;
		nextContactAge[index] = supported ? incrementContactAge(parcel.contactAge()) : 0;
		nextFlags[index] = supported ? (byte) (parcel.flags() | GranularParcelStore.FLAG_SUPPORTED)
				: (byte) (parcel.flags() & ~GranularParcelStore.FLAG_SUPPORTED);
	}

	private static int incrementContactAge(int age) { return age == Integer.MAX_VALUE ? age : age + 1; }

	private boolean sphereTouchesSolid(float x, float y, float z, float radius) {
		return geometry.sphereTouchesSolid(x, y, z, radius);
	}

	private float collisionRadius(long svq) {
		return mmm.granular.GranularMechanics.representativeRadius(svq, cellSize);
	}

	private static double radicalInverse(long value, int base) {
		double result = 0.0, place = 1.0 / base;
		while (value > 0L) {
			result += value % base * place;
			value /= base; place /= base;
		}
		return result;
	}

	private static float clamp(float value, float minimum, float maximum) {
		return Math.max(minimum, Math.min(maximum, value));
	}

}
