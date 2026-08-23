package com.example.fluid;

import com.example.granular.GranularCouplingFields;
import com.example.granular.GranularParcelStore;
import com.example.granular.GranularRegistry;
import com.example.granular.ParticulateMaterial;
import com.example.granular.SolidVolume;

/** Collision-resolved granular motion preview committed only after the matching pressure projection. */
final class FlipGranularCollision {
	private final int sizeX, sizeY, sizeZ;
	private final float cellSize;
	private final FlipCutCellGeometry cutCells;
	private final float[] nextX, nextY, nextZ, nextVelocityX, nextVelocityY, nextVelocityZ;
	private int previewCount;

	FlipGranularCollision(int sizeX, int sizeY, int sizeZ, float cellSize,
	                      FlipCutCellGeometry cutCells, int capacity) {
		this.sizeX = sizeX; this.sizeY = sizeY; this.sizeZ = sizeZ;
		this.cellSize = cellSize; this.cutCells = cutCells;
		this.nextX = new float[capacity]; this.nextY = new float[capacity]; this.nextZ = new float[capacity];
		this.nextVelocityX = new float[capacity]; this.nextVelocityY = new float[capacity];
		this.nextVelocityZ = new float[capacity];
	}

	void preview(GranularParcelStore parcels, GranularRegistry registry, float dt) {
		if (!(Float.isFinite(dt) && dt > 0.0F)) throw new IllegalArgumentException("Granular preview dt is invalid");
		previewCount = parcels.count();
		for (int index = 0; index < previewCount; index++) {
			GranularParcelStore.Parcel parcel = parcels.parcel(index);
			ParticulateMaterial material = registry.material(parcel.materialHandle());
			float radius = collisionRadius(parcel.solidVolumeSvq());
			float[] position = {parcel.x(), parcel.y(), parcel.z()};
			float[] velocity = {parcel.velocityX(), parcel.velocityY(), parcel.velocityZ()};
			for (int axis = 0; axis < 3; axis++) {
				float maximum = (axis == 0 ? sizeX : axis == 1 ? sizeY : sizeZ) * cellSize - radius;
				float target = clamp(position[axis] + velocity[axis] * dt, radius, maximum);
				position[axis] = sweepAxis(position, velocity, axis, target, radius, material);
			}
			nextX[index] = position[0]; nextY[index] = position[1]; nextZ[index] = position[2];
			nextVelocityX[index] = velocity[0]; nextVelocityY[index] = velocity[1]; nextVelocityZ[index] = velocity[2];
		}
	}

	GranularCouplingFields.Position previewPosition(int index, GranularParcelStore.Parcel ignored) {
		if (index < 0 || index >= previewCount) throw new IllegalArgumentException("Unknown granular preview index");
		return new GranularCouplingFields.Position(nextX[index], nextY[index], nextZ[index]);
	}

	void commit(GranularParcelStore parcels) {
		if (parcels.count() != previewCount) throw new IllegalStateException("Granular parcel count changed during preview");
		for (int index = 0; index < previewCount; index++) {
			GranularParcelStore.Parcel parcel = parcels.parcel(index);
			parcels.overwrite(index, new GranularParcelStore.Parcel(nextX[index], nextY[index], nextZ[index],
					nextVelocityX[index], nextVelocityY[index], nextVelocityZ[index], parcel.solidVolumeSvq(),
					parcel.mass(), parcel.materialHandle(), parcel.solidCompositionHandle(), parcel.contactAge(), parcel.flags()));
		}
		previewCount = 0;
	}

	void holdPosition(GranularParcelStore parcels) {
		previewCount = parcels.count();
		for (int index = 0; index < previewCount; index++) {
			GranularParcelStore.Parcel parcel = parcels.parcel(index);
			nextX[index] = parcel.x(); nextY[index] = parcel.y(); nextZ[index] = parcel.z();
			nextVelocityX[index] = nextVelocityY[index] = nextVelocityZ[index] = 0.0F;
		}
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
				velocity[axis] = (float) (-velocity[axis] * material.restitution());
				float tangentScale = (float) Math.max(0.0, 1.0 - material.dynamicFriction());
				for (int tangent = 0; tangent < 3; tangent++) if (tangent != axis) velocity[tangent] *= tangentScale;
				break;
			}
			accepted = candidate;
		}
		return accepted;
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

	private static float clamp(float value, float minimum, float maximum) {
		return Math.max(minimum, Math.min(maximum, value));
	}

	private static double clamp(double value, double minimum, double maximum) {
		return Math.max(minimum, Math.min(maximum, value));
	}
}
