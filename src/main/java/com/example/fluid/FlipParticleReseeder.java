package com.example.fluid;

import com.example.chemistry.ChemicalParcelStore;

import java.util.Arrays;

/** Deterministic, cell-local marker merge/split maintenance for the CPU FLIP reference solver. */
final class FlipParticleReseeder {
	private static final int MAXIMUM_MARKERS_PER_CELL = 12;
	private static final int MERGE_TARGET_MARKERS = 8;
	private static final float MEDIUM_VOLUME = 0.125F;
	private static final float LARGE_VOLUME = 0.50F;
	private static final float POSITION_EPSILON_FRACTION = 1.0E-3F;
	private static final float DOMAIN_CONTACT_FRACTION = 0.45F;
	private static final float SOLID_CONTACT_FRACTION = 0.451F;

	private final int sizeX;
	private final int sizeY;
	private final int sizeZ;
	private final int cellCount;
	private final float cellSize;
	private final int maximumParticleCount;
	private final int[] cellHead;
	private final int[] cellMarkerCount;
	private final float[] cellExactVolume;
	private int[] nextParticle = new int[0];
	private boolean[] removed = new boolean[0];
	private int[] localParticles = new int[16];
	private boolean[] representative = new boolean[16];

	FlipParticleReseeder(int sizeX, int sizeY, int sizeZ, float cellSize, int maximumParticleCount) {
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.cellCount = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
		this.cellSize = cellSize;
		this.maximumParticleCount = maximumParticleCount;
		this.cellHead = new int[cellCount];
		this.cellMarkerCount = new int[cellCount];
		this.cellExactVolume = new float[cellCount];
	}

	int capacityUpperBound(int particleCount) {
		long occupiedUpperBound = Math.min((long) cellCount, particleCount);
		return (int) Math.min(maximumParticleCount, particleCount + 3L * occupiedUpperBound);
	}

	Result reseed(
			boolean[] solid,
			float[] particleX, float[] particleY, float[] particleZ,
			float[] velocityX, float[] velocityY, float[] velocityZ,
			float[] particleVolume, double[] particleMass,
			int[] particleMaterialHandle, int[] particlePhaseLabel,
			ChemicalParcelStore chemicalParcels, int particleCount,
			boolean massWeightedDynamics, PositionValidator positionValidator
	) {
		return reseed(solid, particleX, particleY, particleZ, velocityX, velocityY, velocityZ,
				particleVolume, particleMass, particleMaterialHandle, particlePhaseLabel,
				chemicalParcels, null, particleCount, massWeightedDynamics, positionValidator);
	}

	Result reseed(
			boolean[] solid,
			float[] particleX,
			float[] particleY,
			float[] particleZ,
			float[] velocityX,
			float[] velocityY,
			float[] velocityZ,
			float[] particleVolume,
			double[] particleMass,
			int[] particleMaterialHandle,
			int[] particlePhaseLabel,
			ChemicalParcelStore chemicalParcels,
			ThermalAdapter thermal,
			int particleCount,
			boolean massWeightedDynamics,
			PositionValidator positionValidator
	) {
		ensureScratchCapacity(particleX.length);
		double volumeBefore = totalVolume(particleVolume, particleCount);
		double massBefore = totalMass(particleMass, particleCount);
		double energyBefore = thermal == null ? 0.0 : thermal.totalEnergy(particleCount);
		buildCellLists(particleX, particleY, particleZ, particleVolume, particleCount);
		MergeResult mergeResult = mergeOverpopulatedCells(
				particleX, particleY, particleZ, velocityX, velocityY, velocityZ,
				particleVolume, particleMass, particleMaterialHandle, particlePhaseLabel,
				chemicalParcels, thermal, particleCount, massWeightedDynamics, positionValidator
		);
		int merged = mergeResult.merged();
		if (merged > 0) {
			particleCount = compactParticles(
					particleX, particleY, particleZ, velocityX, velocityY, velocityZ,
					particleVolume, particleMass, particleMaterialHandle, particlePhaseLabel,
					chemicalParcels, thermal, particleCount
			);
		}

		buildCellLists(particleX, particleY, particleZ, particleVolume, particleCount);
		int split = 0;
		int budgetSkipped = 0;
		int geometrySkipped = mergeResult.geometrySkipped();
		for (int cell = 0; cell < cellCount; cell++) {
			if (solid[cell] || cellMarkerCount[cell] == 0) continue;
			int minimum = minimumMarkers(cellExactVolume[cell]);
			while (cellMarkerCount[cell] < minimum) {
				if (particleCount >= maximumParticleCount || particleCount >= particleX.length) {
					budgetSkipped += minimum - cellMarkerCount[cell];
					break;
				}
				int source = largestMarker(cell, particleVolume);
				if (source < 0 || !splitParticle(
						solid, cell, source, particleCount,
						particleX, particleY, particleZ, velocityX, velocityY, velocityZ,
						particleVolume, particleMass, particleMaterialHandle, particlePhaseLabel,
						chemicalParcels, thermal, positionValidator
				)) {
					geometrySkipped += minimum - cellMarkerCount[cell];
					break;
				}
				nextParticle[particleCount] = cellHead[cell];
				cellHead[cell] = particleCount;
				cellMarkerCount[cell]++;
				particleCount++;
				split++;
			}
		}

		double volumeAfter = totalVolume(particleVolume, particleCount);
		double massAfter = totalMass(particleMass, particleCount);
		double energyAfter = thermal == null ? 0.0 : thermal.totalEnergy(particleCount);
		double ledgerError = volumeAfter - volumeBefore;
		return new Result(particleCount, merged, split, budgetSkipped, geometrySkipped,
				ledgerError, massAfter - massBefore, energyAfter - energyBefore);
	}

	private MergeResult mergeOverpopulatedCells(
			float[] particleX,
			float[] particleY,
			float[] particleZ,
			float[] velocityX,
			float[] velocityY,
			float[] velocityZ,
			float[] particleVolume,
			double[] particleMass,
			int[] particleMaterialHandle,
			int[] particlePhaseLabel,
			ChemicalParcelStore chemicalParcels,
			ThermalAdapter thermal,
			int particleCount,
			boolean massWeightedDynamics,
			PositionValidator positionValidator
	) {
		Arrays.fill(removed, 0, particleCount, false);
		int merged = 0;
		int geometrySkipped = 0;
		for (int cell = 0; cell < cellCount; cell++) {
			int count = cellMarkerCount[cell];
			if (count <= MAXIMUM_MARKERS_PER_CELL) continue;
			ensureLocalCapacity(count);
			int localCount = 0;
			for (int particle = cellHead[cell]; particle >= 0; particle = nextParticle[particle]) {
				localParticles[localCount++] = particle;
			}
			Arrays.sort(localParticles, 0, localCount);
			Arrays.fill(representative, 0, localCount, false);
			selectDistributedRepresentatives(particleX, particleY, particleZ, localCount);
			ensureMaterialRepresentatives(particleMaterialHandle, particlePhaseLabel, chemicalParcels, localCount);
			for (int local = 0; local < localCount; local++) {
				if (representative[local]) continue;
				int source = localParticles[local];
				int targetLocal = nearestRepresentative(
						particleX, particleY, particleZ, particleMaterialHandle, particlePhaseLabel,
						chemicalParcels, source, localCount
				);
				if (targetLocal < 0) {
					geometrySkipped++;
					continue;
				}
				int target = localParticles[targetLocal];
				if (!mergeParticleInto(
						target, source, particleX, particleY, particleZ,
						velocityX, velocityY, velocityZ, particleVolume, particleMass,
						chemicalParcels, thermal, massWeightedDynamics, positionValidator
				)) {
					geometrySkipped++;
					continue;
				}
				removed[source] = true;
				merged++;
			}
		}
		return new MergeResult(merged, geometrySkipped);
	}

	private void selectDistributedRepresentatives(float[] particleX, float[] particleY, float[] particleZ, int count) {
		representative[0] = true;
		int selected = 1;
		while (selected < MERGE_TARGET_MARKERS) {
			int best = -1;
			float bestDistance = -1.0F;
			for (int candidate = 0; candidate < count; candidate++) {
				if (representative[candidate]) continue;
				int candidateParticle = localParticles[candidate];
				float nearest = Float.POSITIVE_INFINITY;
				for (int existing = 0; existing < count; existing++) if (representative[existing]) {
					nearest = Math.min(nearest, distanceSquared(
							particleX, particleY, particleZ, candidateParticle, localParticles[existing]
					));
				}
				if (nearest > bestDistance) {
					bestDistance = nearest;
					best = candidate;
				}
			}
			if (best < 0) break;
			representative[best] = true;
			selected++;
		}
	}

	private void ensureMaterialRepresentatives(
			int[] particleMaterialHandle, int[] particlePhaseLabel,
			ChemicalParcelStore chemicalParcels, int count
	) {
		for (int candidate = 0; candidate < count; candidate++) {
			int particle = localParticles[candidate];
			boolean represented = false;
			for (int existing = 0; existing < count; existing++) if (representative[existing]) {
				int representativeParticle = localParticles[existing];
				if (particleMaterialHandle[representativeParticle] == particleMaterialHandle[particle]
						&& particlePhaseLabel[representativeParticle] == particlePhaseLabel[particle]
						&& (chemicalParcels == null
						|| chemicalParcels.indicator(representativeParticle) == chemicalParcels.indicator(particle))) {
					represented = true;
					break;
				}
			}
			if (!represented) representative[candidate] = true;
		}
	}

	private int nearestRepresentative(
			float[] particleX, float[] particleY, float[] particleZ,
			int[] particleMaterialHandle, int[] particlePhaseLabel,
			ChemicalParcelStore chemicalParcels,
			int particle, int count
	) {
		int best = -1;
		float bestDistance = Float.POSITIVE_INFINITY;
		for (int local = 0; local < count; local++) {
			if (!representative[local]) continue;
			int representativeParticle = localParticles[local];
			if (particleMaterialHandle[representativeParticle] != particleMaterialHandle[particle]
					|| particlePhaseLabel[representativeParticle] != particlePhaseLabel[particle]) continue;
			if (chemicalParcels != null && !chemicalParcels.canMerge(representativeParticle, particle)) continue;
			float distance = distanceSquared(particleX, particleY, particleZ, particle, representativeParticle);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = local;
			}
		}
		return best;
	}

	private static boolean mergeParticleInto(
			int target,
			int source,
			float[] particleX,
			float[] particleY,
			float[] particleZ,
			float[] velocityX,
			float[] velocityY,
			float[] velocityZ,
			float[] particleVolume,
			double[] particleMass,
			ChemicalParcelStore chemicalParcels,
			ThermalAdapter thermal,
			boolean massWeightedDynamics,
			PositionValidator positionValidator
	) {
		double targetVolume = particleVolume[target];
		double sourceVolume = particleVolume[source];
		double mergedVolume = targetVolume + sourceVolume;
		float mergedX = (float) ((particleX[target] * targetVolume + particleX[source] * sourceVolume) / mergedVolume);
		float mergedY = (float) ((particleY[target] * targetVolume + particleY[source] * sourceVolume) / mergedVolume);
		float mergedZ = (float) ((particleZ[target] * targetVolume + particleZ[source] * sourceVolume) / mergedVolume);
		if (!positionValidator.allowed(mergedX, mergedY, mergedZ)) return false;
		particleX[target] = mergedX;
		particleY[target] = mergedY;
		particleZ[target] = mergedZ;
		double targetWeight = massWeightedDynamics ? particleMass[target] : targetVolume;
		double sourceWeight = massWeightedDynamics ? particleMass[source] : sourceVolume;
		double mergedWeight = targetWeight + sourceWeight;
		velocityX[target] = (float) ((velocityX[target] * targetWeight + velocityX[source] * sourceWeight) / mergedWeight);
		velocityY[target] = (float) ((velocityY[target] * targetWeight + velocityY[source] * sourceWeight) / mergedWeight);
		velocityZ[target] = (float) ((velocityZ[target] * targetWeight + velocityZ[source] * sourceWeight) / mergedWeight);
		particleVolume[target] = (float) mergedVolume;
		particleMass[target] += particleMass[source];
		if (chemicalParcels != null) chemicalParcels.merge(target, source, particleMass[target]);
		if (thermal != null) thermal.merge(target, source);
		return true;
	}

	private boolean splitParticle(
			boolean[] solid,
			int cell,
			int source,
			int destination,
			float[] particleX,
			float[] particleY,
			float[] particleZ,
			float[] velocityX,
			float[] velocityY,
			float[] velocityZ,
			float[] particleVolume,
			double[] particleMass,
			int[] particleMaterialHandle,
			int[] particlePhaseLabel,
			ChemicalParcelStore chemicalParcels,
			ThermalAdapter thermal,
			PositionValidator positionValidator
	) {
		int x = cell % sizeX;
		int yz = cell / sizeX;
		int y = yz % sizeY;
		int z = yz / sizeY;
		float[] positions = {particleX[source], particleY[source], particleZ[source]};
		int bestAxis = -1;
		float bestRoom = 0.0F;
		for (int axis = 0; axis < 3; axis++) {
			float minimum = minimumCoordinate(axis, x, y, z, solid);
			float maximum = maximumCoordinate(axis, x, y, z, solid);
			float room = Math.min(positions[axis] - minimum, maximum - positions[axis]);
			if (room > bestRoom) {
				bestRoom = room;
				bestAxis = axis;
			}
		}
		float sourceX = particleX[source], sourceY = particleY[source], sourceZ = particleZ[source];
		float destinationX = sourceX, destinationY = sourceY, destinationZ = sourceZ;
		if (bestAxis >= 0 && bestRoom > cellSize * 1.0E-4F) {
			float displacement = Math.min(cellSize * 0.20F, bestRoom * 0.80F);
			if (bestAxis == 0) {
				if (positionValidator.allowed(sourceX - displacement, sourceY, sourceZ)
						&& positionValidator.allowed(sourceX + displacement, sourceY, sourceZ)) {
					particleX[source] = sourceX - displacement;
					destinationX = sourceX + displacement;
				}
			} else if (bestAxis == 1) {
				if (positionValidator.allowed(sourceX, sourceY - displacement, sourceZ)
						&& positionValidator.allowed(sourceX, sourceY + displacement, sourceZ)) {
					particleY[source] = sourceY - displacement;
					destinationY = sourceY + displacement;
				}
			} else {
				if (positionValidator.allowed(sourceX, sourceY, sourceZ - displacement)
						&& positionValidator.allowed(sourceX, sourceY, sourceZ + displacement)) {
					particleZ[source] = sourceZ - displacement;
					destinationZ = sourceZ + displacement;
				}
			}
		}
		float originalVolume = particleVolume[source];
		ThermalSplit thermalSplit = thermal == null ? null : thermal.beforeSplit(source);
		float firstVolume = originalVolume * 0.5F;
		float secondVolume = originalVolume - firstVolume;
		if (chemicalParcels != null && !chemicalParcels.canSplit(
				source, destination, firstVolume, secondVolume)) {
			particleX[source] = sourceX;
			particleY[source] = sourceY;
			particleZ[source] = sourceZ;
			return false;
		}
		copyParticle(source, destination, particleX, particleY, particleZ,
				velocityX, velocityY, velocityZ, particleVolume, particleMass,
				particleMaterialHandle, particlePhaseLabel);
		particleX[destination] = destinationX;
		particleY[destination] = destinationY;
		particleZ[destination] = destinationZ;
		particleVolume[destination] = secondVolume;
		particleVolume[source] = firstVolume;
		double firstMass = particleMass[source] * 0.5;
		particleMass[destination] = particleMass[source] - firstMass;
		particleMass[source] = firstMass;
		if (chemicalParcels != null) chemicalParcels.split(
				source, destination, firstVolume, secondVolume,
				particleMass[source], particleMass[destination]);
		if (thermal != null) thermal.afterSplit(thermalSplit, source, destination);
		return true;
	}

	private float minimumCoordinate(int axis, int x, int y, int z, boolean[] solid) {
		int coordinate = axis == 0 ? x : axis == 1 ? y : z;
		float inset = coordinate == 0 ? DOMAIN_CONTACT_FRACTION
				: neighborSolid(axis, -1, x, y, z, solid) ? SOLID_CONTACT_FRACTION : POSITION_EPSILON_FRACTION;
		return (coordinate + inset) * cellSize;
	}

	private float maximumCoordinate(int axis, int x, int y, int z, boolean[] solid) {
		int coordinate = axis == 0 ? x : axis == 1 ? y : z;
		int size = axis == 0 ? sizeX : axis == 1 ? sizeY : sizeZ;
		float inset = coordinate + 1 == size ? DOMAIN_CONTACT_FRACTION
				: neighborSolid(axis, 1, x, y, z, solid) ? SOLID_CONTACT_FRACTION : POSITION_EPSILON_FRACTION;
		return (coordinate + 1.0F - inset) * cellSize;
	}

	private boolean neighborSolid(int axis, int direction, int x, int y, int z, boolean[] solid) {
		int nx = x + (axis == 0 ? direction : 0);
		int ny = y + (axis == 1 ? direction : 0);
		int nz = z + (axis == 2 ? direction : 0);
		return solid[cellIndex(nx, ny, nz)];
	}

	private int largestMarker(int cell, float[] particleVolume) {
		int best = -1;
		float bestVolume = -1.0F;
		for (int particle = cellHead[cell]; particle >= 0; particle = nextParticle[particle]) {
			if (particleVolume[particle] > bestVolume) {
				bestVolume = particleVolume[particle];
				best = particle;
			}
		}
		return best;
	}

	private void buildCellLists(
			float[] particleX, float[] particleY, float[] particleZ, float[] particleVolume, int particleCount
	) {
		Arrays.fill(cellHead, -1);
		Arrays.fill(cellMarkerCount, 0);
		Arrays.fill(cellExactVolume, 0.0F);
		for (int particle = particleCount - 1; particle >= 0; particle--) {
			int cell = particleCell(particleX[particle], particleY[particle], particleZ[particle]);
			nextParticle[particle] = cellHead[cell];
			cellHead[cell] = particle;
			cellMarkerCount[cell]++;
			cellExactVolume[cell] += particleVolume[particle];
		}
	}

	private int compactParticles(
			float[] particleX,
			float[] particleY,
			float[] particleZ,
			float[] velocityX,
			float[] velocityY,
			float[] velocityZ,
			float[] particleVolume,
			double[] particleMass,
			int[] particleMaterialHandle,
			int[] particlePhaseLabel,
			ChemicalParcelStore chemicalParcels,
			ThermalAdapter thermal,
			int particleCount
	) {
		int destination = 0;
		for (int source = 0; source < particleCount; source++) {
			if (removed[source]) continue;
			if (destination != source) copyParticle(
					source, destination, particleX, particleY, particleZ,
					velocityX, velocityY, velocityZ, particleVolume, particleMass,
					particleMaterialHandle, particlePhaseLabel
			);
			if (chemicalParcels != null && destination != source) chemicalParcels.move(source, destination);
			if (thermal != null && destination != source) thermal.move(source, destination);
			destination++;
		}
		return destination;
	}

	private static void copyParticle(
			int source,
			int destination,
			float[] particleX,
			float[] particleY,
			float[] particleZ,
			float[] velocityX,
			float[] velocityY,
			float[] velocityZ,
			float[] particleVolume,
			double[] particleMass,
			int[] particleMaterialHandle,
			int[] particlePhaseLabel
	) {
		particleX[destination] = particleX[source];
		particleY[destination] = particleY[source];
		particleZ[destination] = particleZ[source];
		velocityX[destination] = velocityX[source];
		velocityY[destination] = velocityY[source];
		velocityZ[destination] = velocityZ[source];
		particleVolume[destination] = particleVolume[source];
		particleMass[destination] = particleMass[source];
		particleMaterialHandle[destination] = particleMaterialHandle[source];
		particlePhaseLabel[destination] = particlePhaseLabel[source];
	}

	private int minimumMarkers(float exactVolume) {
		if (exactVolume >= LARGE_VOLUME) return 4;
		if (exactVolume >= MEDIUM_VOLUME) return 2;
		return 1;
	}

	private int particleCell(float x, float y, float z) {
		int cellX = Math.min(sizeX - 1, Math.max(0, (int) Math.floor(x / cellSize)));
		int cellY = Math.min(sizeY - 1, Math.max(0, (int) Math.floor(y / cellSize)));
		int cellZ = Math.min(sizeZ - 1, Math.max(0, (int) Math.floor(z / cellSize)));
		return cellIndex(cellX, cellY, cellZ);
	}

	private int cellIndex(int x, int y, int z) {
		return (z * sizeY + y) * sizeX + x;
	}

	private static float distanceSquared(
			float[] particleX, float[] particleY, float[] particleZ, int first, int second
	) {
		float dx = particleX[first] - particleX[second];
		float dy = particleY[first] - particleY[second];
		float dz = particleZ[first] - particleZ[second];
		return dx * dx + dy * dy + dz * dz;
	}

	private static double totalVolume(float[] particleVolume, int particleCount) {
		double total = 0.0;
		for (int particle = 0; particle < particleCount; particle++) total += particleVolume[particle];
		return total;
	}

	private static double totalMass(double[] particleMass, int particleCount) {
		double total = 0.0;
		for (int particle = 0; particle < particleCount; particle++) total += particleMass[particle];
		return total;
	}

	private void ensureScratchCapacity(int particleCapacity) {
		if (nextParticle.length < particleCapacity) nextParticle = new int[particleCapacity];
		if (removed.length < particleCapacity) removed = new boolean[particleCapacity];
	}

	private void ensureLocalCapacity(int count) {
		if (localParticles.length >= count) return;
		int capacity = Math.max(count, localParticles.length * 2);
		localParticles = new int[capacity];
		representative = new boolean[capacity];
	}

	@FunctionalInterface
	interface PositionValidator { boolean allowed(float x, float y, float z); }

	interface ThermalAdapter {
		double totalEnergy(int particleCount);
		void merge(int target, int source);
		ThermalSplit beforeSplit(int source);
		void afterSplit(ThermalSplit split, int source, int destination);
		void move(int source, int destination);
	}

	record ThermalSplit(double parentEnergy, double temperatureK) { }

	private record MergeResult(int merged, int geometrySkipped) { }

	record Result(
			int particleCount,
			int merged,
			int split,
			int budgetSkipped,
			int geometrySkipped,
			double ledgerError,
			double massLedgerError,
			double energyLedgerError
	) { }
}
