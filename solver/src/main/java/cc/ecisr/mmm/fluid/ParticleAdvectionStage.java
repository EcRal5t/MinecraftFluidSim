package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.chemistry.ChemicalParcelStore;

/**
 * Marker advection through the collision resolver, bookending the chemical dirty-cell
 * trail: markers that move mark both their source and destination cells so the reaction
 * scan sees exactly the disturbed neighbourhood. The collision resolver and the trail
 * columns stay with the domain lifecycle.
 */
final class ParticleAdvectionStage {
	record Workspace(
			LiquidParticleStore particles, FlipParticleCollision collision, MacVelocityGrid mac,
			DomainGeometry geometry, boolean[] advectionCells, int sizeX, int sizeY, int sizeZ, float cellSize
	) { }

	interface Access {
		ChemicalParcelStore chemicalParcels();
		int[] chemicalPreviousCells();
		int[] chemicalMovingMarkers();
		int particleCellIndex(int marker);
		void markGridDirty();
	}

	private final Workspace state;
	private final Access access;
	private float[] motionX = new float[0];
	private float[] motionY = new float[0];
	private float[] motionZ = new float[0];
	private final DivergenceConformingVelocityField.Sample firstVelocity =
			new DivergenceConformingVelocityField.Sample();
	private final DivergenceConformingVelocityField.Sample midpointVelocity =
			new DivergenceConformingVelocityField.Sample();
	private final DivergenceConformingVelocityField.Sample secondMidpointVelocity =
			new DivergenceConformingVelocityField.Sample();
	private final DivergenceConformingVelocityField.Sample endpointVelocity =
			new DivergenceConformingVelocityField.Sample();

	ParticleAdvectionStage(Workspace state, Access access) {
		this.state = state;
		this.access = access;
	}

	void advect(float dt) {
		LiquidParticleStore particles = state.particles;
		ChemicalParcelStore chemicalParcels = access.chemicalParcels();
		int chemicalMovingCount = 0;
		LiquidParticleStore.DirectArrays advectArrays = particles.directArrays();
		ensureMotionCapacity(particles.count());
		for (int marker = 0; marker < particles.count(); marker++) {
			float x = advectArrays.positionX[marker];
			float y = advectArrays.positionY[marker];
			float z = advectArrays.positionZ[marker];
			int cellX = Math.clamp((int) Math.floor(x / state.cellSize), 0, state.sizeX - 1);
			int cellY = Math.clamp((int) Math.floor(y / state.cellSize), 0, state.sizeY - 1);
			int cellZ = Math.clamp((int) Math.floor(z / state.cellSize), 0, state.sizeZ - 1);
			if (!state.advectionCells[(cellZ * state.sizeY + cellY) * state.sizeX + cellX]) {
				motionX[marker] = advectArrays.velocityX[marker];
				motionY[marker] = advectArrays.velocityY[marker];
				motionZ[marker] = advectArrays.velocityZ[marker];
			} else if (supportsDivergenceConformingField(x, y, z)) {
				DivergenceConformingVelocityField.sample(state.mac, state.geometry.cutCells, state.sizeX, state.sizeY, state.sizeZ,
						state.cellSize, x, y, z, firstVelocity);
				float midpointX = x + 0.5F * dt * firstVelocity.x;
				float midpointY = y + 0.5F * dt * firstVelocity.y;
				float midpointZ = z + 0.5F * dt * firstVelocity.z;
				DivergenceConformingVelocityField.sample(state.mac, state.geometry.cutCells, state.sizeX, state.sizeY, state.sizeZ,
						state.cellSize, midpointX, midpointY, midpointZ, midpointVelocity);
				midpointX = x + 0.5F * dt * midpointVelocity.x;
				midpointY = y + 0.5F * dt * midpointVelocity.y;
				midpointZ = z + 0.5F * dt * midpointVelocity.z;
				DivergenceConformingVelocityField.sample(state.mac, state.geometry.cutCells, state.sizeX, state.sizeY, state.sizeZ,
						state.cellSize, midpointX, midpointY, midpointZ, secondMidpointVelocity);
				float endpointX = x + dt * secondMidpointVelocity.x;
				float endpointY = y + dt * secondMidpointVelocity.y;
				float endpointZ = z + dt * secondMidpointVelocity.z;
				DivergenceConformingVelocityField.sample(state.mac, state.geometry.cutCells, state.sizeX, state.sizeY, state.sizeZ,
						state.cellSize, endpointX, endpointY, endpointZ, endpointVelocity);
				motionX[marker] = (firstVelocity.x + 2.0F * midpointVelocity.x
						+ 2.0F * secondMidpointVelocity.x + endpointVelocity.x) / 6.0F;
				motionY[marker] = (firstVelocity.y + 2.0F * midpointVelocity.y
						+ 2.0F * secondMidpointVelocity.y + endpointVelocity.y) / 6.0F;
				motionZ[marker] = (firstVelocity.z + 2.0F * midpointVelocity.z
						+ 2.0F * secondMidpointVelocity.z + endpointVelocity.z) / 6.0F;
			} else {
				// Partial cut faces resolve sub-cell geometry not represented by the full-face
				// H(div) polynomial. Keep their established cut-cell interpolation and CCD.
				float firstX = trilinearX(x, y, z);
				float firstY = trilinearY(x, y, z);
				float firstZ = trilinearZ(x, y, z);
				float midpointX = x + 0.5F * dt * firstX;
				float midpointY = y + 0.5F * dt * firstY;
				float midpointZ = z + 0.5F * dt * firstZ;
				motionX[marker] = trilinearX(midpointX, midpointY, midpointZ);
				motionY[marker] = trilinearY(midpointX, midpointY, midpointZ);
				motionZ[marker] = trilinearZ(midpointX, midpointY, midpointZ);
			}
			if (chemicalParcels != null && (motionX[marker] != 0 || motionY[marker] != 0 || motionZ[marker] != 0)) {
				access.chemicalPreviousCells()[marker] = access.particleCellIndex(marker);
				access.chemicalMovingMarkers()[chemicalMovingCount++] = marker;
			}
		}
		state.collision.advect(
				advectArrays.positionX, advectArrays.positionY, advectArrays.positionZ,
				motionX, motionY, motionZ,
				advectArrays.velocityX, advectArrays.velocityY, advectArrays.velocityZ,
				particles.count(), dt
		);
		for (int moving = 0; moving < chemicalMovingCount; moving++) {
			int marker = access.chemicalMovingMarkers()[moving];
			int previous = access.chemicalPreviousCells()[marker], current = access.particleCellIndex(marker);
			if (previous != current) {
				chemicalParcels.markDirtyCell(previous);
				chemicalParcels.markDirtyCell(current);
			}
		}
		access.markGridDirty();
	}

	private boolean supportsDivergenceConformingField(float x, float y, float z) {
		int cellX = Math.clamp((int) Math.floor(x / state.cellSize), 0, state.sizeX - 1);
		int cellY = Math.clamp((int) Math.floor(y / state.cellSize), 0, state.sizeY - 1);
		int cellZ = Math.clamp((int) Math.floor(z / state.cellSize), 0, state.sizeZ - 1);
		FlipCutCellGeometry cutCells = state.geometry.cutCells;
		return binary(cutCells.xFaceOpen(cellX, cellY, cellZ))
				&& binary(cutCells.xFaceOpen(cellX + 1, cellY, cellZ))
				&& binary(cutCells.yFaceOpen(cellX, cellY, cellZ))
				&& binary(cutCells.yFaceOpen(cellX, cellY + 1, cellZ))
				&& binary(cutCells.zFaceOpen(cellX, cellY, cellZ))
				&& binary(cutCells.zFaceOpen(cellX, cellY, cellZ + 1));
	}

	private static boolean binary(float aperture) { return aperture == 0.0F || aperture == 1.0F; }

	private float trilinearX(float x, float y, float z) {
		return FluidDomain.trilinearValid(state.mac.velocityX, state.mac.velocityValidX,
				state.sizeX + 1, state.sizeY, state.sizeZ,
				x / state.cellSize, y / state.cellSize - 0.5F, z / state.cellSize - 0.5F);
	}

	private float trilinearY(float x, float y, float z) {
		return FluidDomain.trilinearValid(state.mac.velocityY, state.mac.velocityValidY,
				state.sizeX, state.sizeY + 1, state.sizeZ,
				x / state.cellSize - 0.5F, y / state.cellSize, z / state.cellSize - 0.5F);
	}

	private float trilinearZ(float x, float y, float z) {
		return FluidDomain.trilinearValid(state.mac.velocityZ, state.mac.velocityValidZ,
				state.sizeX, state.sizeY, state.sizeZ + 1,
				x / state.cellSize - 0.5F, y / state.cellSize - 0.5F, z / state.cellSize);
	}

	private void ensureMotionCapacity(int count) {
		if (motionX.length >= count) return;
		motionX = new float[count];
		motionY = new float[count];
		motionZ = new float[count];
	}
}
