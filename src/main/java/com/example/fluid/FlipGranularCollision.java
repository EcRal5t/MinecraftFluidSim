package com.example.fluid;

import com.example.granular.GranularCouplingFields;
import com.example.granular.GranularDepositStore;
import com.example.granular.GranularLimits;
import com.example.granular.GranularParcelStore;
import com.example.granular.GranularRegistry;
import com.example.granular.ParticulateMaterial;
import com.example.granular.SolidVolume;

import java.util.Arrays;
import java.util.function.IntToLongFunction;

/** Collision-resolved granular motion preview committed only after the matching pressure projection. */
final class FlipGranularCollision {
	private static final int[] SWEEP_AXIS_ORDER = {0, 2, 1};
	private final int sizeX, sizeY, sizeZ;
	private final float cellSize;
	private final FlipCutCellGeometry cutCells;
	private final GranularDepositStore deposits;
	private final float[] nextX, nextY, nextZ, nextVelocityX, nextVelocityY, nextVelocityZ;
	private final int[] nextContactAge;
	private final byte[] nextFlags;
	private final boolean[] capacityPending;
	private int previewCount;
	private int lastCapacityHeld;

	FlipGranularCollision(int sizeX, int sizeY, int sizeZ, float cellSize,
	                      FlipCutCellGeometry cutCells, GranularDepositStore deposits, int capacity) {
		this.sizeX = sizeX; this.sizeY = sizeY; this.sizeZ = sizeZ;
		this.cellSize = cellSize; this.cutCells = cutCells; this.deposits = deposits;
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
		for (int index = 0; index < previewCount; index++) {
			GranularParcelStore.Parcel parcel = parcels.parcel(index);
			ParticulateMaterial material = registry.material(parcel.materialHandle());
			float radius = collisionRadius(parcel.solidVolumeSvq());
			float[] position = {parcel.x(), parcel.y(), parcel.z()};
			float[] velocity = {parcel.velocityX(), parcel.velocityY(), parcel.velocityZ()};
			boolean supported = false;
			for (int axis : SWEEP_AXIS_ORDER) {
				float maximum = (axis == 0 ? sizeX : axis == 1 ? sizeY : sizeZ) * cellSize - radius;
				float rawTarget = position[axis] + velocity[axis] * dt;
				float target = clamp(rawTarget, radius, maximum);
				if (target != rawTarget) {
					boolean lowerSupport = axis == 1 && rawTarget < radius;
					resolveContact(velocity, axis, material);
					supported |= lowerSupport;
				}
				boolean downward = axis == 1 && velocity[axis] <= 0.0F;
				position[axis] = sweepAxis(position, velocity, axis, target, radius, material);
				supported |= downward && position[axis] != target;
				if (axis == 1) {
					float depositSurface = depositSurfaceY(position[0], position[2]);
					if (depositSurface >= 0.0F && position[1] - radius <= depositSurface + cellSize * 1.0e-5F) {
						position[1] = Math.min(maximum, depositSurface + radius);
						if (velocity[1] <= 0.0F) resolveContact(velocity, 1, material);
						supported = true;
					}
				}
			}
			nextX[index] = position[0]; nextY[index] = position[1]; nextZ[index] = position[2];
			nextVelocityX[index] = velocity[0]; nextVelocityY[index] = velocity[1]; nextVelocityZ[index] = velocity[2];
			float restingSpeed = cellSize * 0.05F;
			boolean resting = supported && velocity[0] * velocity[0] + velocity[1] * velocity[1]
					+ velocity[2] * velocity[2] <= restingSpeed * restingSpeed;
			nextContactAge[index] = resting ? Math.min(Integer.MAX_VALUE, parcel.contactAge() + 1) : 0;
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
			if (!sphereTouchesSolid(x, y, z, radius)) {
				float depositSurface = depositSurfaceY(x, z);
				if (depositSurface < 0.0F || y - radius > depositSurface + cellSize * 1.0e-5F) {
					return new GranularCouplingFields.Position(x, y, z);
				}
			}
		}
		return null;
	}

	boolean positionValid(float x, float y, float z, long solidVolumeSvq) {
		float radius = collisionRadius(solidVolumeSvq);
		if (x < radius || x > sizeX * cellSize - radius
				|| y < radius || y > sizeY * cellSize - radius
				|| z < radius || z > sizeZ * cellSize - radius
				|| sphereTouchesSolid(x, y, z, radius)) return false;
		float depositSurface = depositSurfaceY(x, z);
		return depositSurface < 0.0F || y - radius > depositSurface + cellSize * 1.0e-5F;
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

	void commit(GranularParcelStore parcels) {
		if (parcels.count() != previewCount) throw new IllegalStateException("Granular parcel count changed during preview");
		for (int index = 0; index < previewCount; index++) {
			GranularParcelStore.Parcel parcel = parcels.parcel(index);
			parcels.overwrite(index, new GranularParcelStore.Parcel(nextX[index], nextY[index], nextZ[index],
					nextVelocityX[index], nextVelocityY[index], nextVelocityZ[index], parcel.solidVolumeSvq(),
					parcel.mass(), parcel.materialHandle(), parcel.solidCompositionHandle(),
					nextContactAge[index], nextFlags[index]));
		}
		previewCount = 0;
	}

	private void holdParcel(GranularParcelStore parcels, int index) {
		GranularParcelStore.Parcel parcel = parcels.parcel(index);
		boolean downwardContact = nextY[index] < parcel.y();
		nextX[index] = parcel.x(); nextY[index] = parcel.y(); nextZ[index] = parcel.z();
		nextVelocityX[index] = nextVelocityY[index] = nextVelocityZ[index] = 0.0F;
		nextContactAge[index] = downwardContact ? Math.min(Integer.MAX_VALUE, parcel.contactAge() + 1) : 0;
		nextFlags[index] = downwardContact ? (byte) (parcel.flags() | GranularParcelStore.FLAG_SUPPORTED)
				: (byte) (parcel.flags() & ~GranularParcelStore.FLAG_SUPPORTED);
	}

	private float sweepAxis(float[] position, float[] velocity, int axis, float target, float radius,
	                        ParticulateMaterial material) {
		float start = position[axis], distance = target - start;
		if (distance == 0.0F) return start;
		float maximumIncrement = cellSize / (FlipCutCellGeometry.MICRO_CELLS_PER_AXIS * 4.0F);
		int increments = Math.max(1, (int) Math.ceil(Math.abs(distance) / maximumIncrement));
		float accepted = start;
		for (int increment = 1; increment <= increments; increment++) {
			float candidate = start + distance * increment / increments;
			float previous = position[axis]; position[axis] = candidate;
			boolean blocked = sphereTouchesSolid(position[0], position[1], position[2], radius);
			position[axis] = previous;
			if (blocked) {
				resolveContact(velocity, axis, material);
				break;
			}
			accepted = candidate;
		}
		return accepted;
	}

	private static void resolveContact(float[] velocity, int axis, ParticulateMaterial material) {
		velocity[axis] = (float) (-velocity[axis] * material.restitution());
		float tangentScale = (float) Math.max(0.0, 1.0 - material.dynamicFriction());
		for (int tangent = 0; tangent < 3; tangent++) if (tangent != axis) velocity[tangent] *= tangentScale;
	}

	/** Low-cost deposits are vertical bulk columns; skeleton remains the displaced-volume truth. */
	private float depositSurfaceY(float x, float z) {
		if (deposits == null) return -1.0F;
		int cellX = Math.min(sizeX - 1, Math.max(0, (int) Math.floor(x / cellSize)));
		int cellZ = Math.min(sizeZ - 1, Math.max(0, (int) Math.floor(z / cellSize)));
		long surface = 0L;
		for (int cellY = 0; cellY < sizeY; cellY++) {
			long bulk = deposits.cellBulkVolumeSvq((cellZ * sizeY + cellY) * sizeX + cellX);
			if (bulk > 0L) surface = Math.max(surface, Math.addExact(
					Math.multiplyExact((long) cellY, SolidVolume.SVQ_PER_CELL), bulk));
		}
		return surface == 0L ? -1.0F : (float) ((double) surface / SolidVolume.SVQ_PER_CELL * cellSize);
	}

	private boolean sphereTouchesSolid(float x, float y, float z, float radius) {
		return cutCells.pointInsideSolid(x, y, z, cellSize)
				|| cutCells.pointInsideSolid(x - radius, y, z, cellSize)
				|| cutCells.pointInsideSolid(x + radius, y, z, cellSize)
				|| cutCells.pointInsideSolid(x, y - radius, z, cellSize)
				|| cutCells.pointInsideSolid(x, y + radius, z, cellSize)
				|| cutCells.pointInsideSolid(x, y, z - radius, cellSize)
				|| cutCells.pointInsideSolid(x, y, z + radius, cellSize);
	}

	private float collisionRadius(long svq) {
		double cellVolume = SolidVolume.toCellVolume(svq);
		double radius = Math.cbrt(3.0 * cellVolume / (4.0 * Math.PI)) * cellSize;
		return (float) clamp(radius, cellSize * 0.02F, cellSize * 0.20F);
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

	private static double clamp(double value, double minimum, double maximum) {
		return Math.max(minimum, Math.min(maximum, value));
	}
}
