package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.chemistry.ChemicalParcelStore;

import java.util.Arrays;

/** Deterministic, cell-local marker merge/split maintenance for the CPU FLIP reference solver. */
final class FlipParticleReseeder {
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
		int conservationSkipped = mergeResult.conservationSkipped();
		for (int cell = 0; cell < cellCount; cell++) {
			if (solid[cell] || cellMarkerCount[cell] == 0) continue;
			int target = ConservativeMarkerSampling.targetMarkers(cellExactVolume[cell]);
			while (cellMarkerCount[cell] < target) {
				if (particleCount >= maximumParticleCount || particleCount >= particleX.length) {
					budgetSkipped += target - cellMarkerCount[cell];
					break;
				}
				int source = largestMarker(cell, particleVolume);
				if (source < 0 || !splitParticle(
						solid, cell, source, particleCount,
						particleX, particleY, particleZ, velocityX, velocityY, velocityZ,
						particleVolume, particleMass, particleMaterialHandle, particlePhaseLabel,
						chemicalParcels, thermal, positionValidator
				)) {
					geometrySkipped += target - cellMarkerCount[cell];
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
		return new Result(particleCount, merged, split, budgetSkipped, geometrySkipped, conservationSkipped,
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
		int conservationSkipped = 0;
		for (int cell = 0; cell < cellCount; cell++) {
			int count = cellMarkerCount[cell];
			int target = ConservativeMarkerSampling.targetMarkers(cellExactVolume[cell]);
			if (count <= target) continue;
			ensureLocalCapacity(count);
			int localCount = 0;
			for (int particle = cellHead[cell]; particle >= 0; particle = nextParticle[particle]) {
				localParticles[localCount++] = particle;
			}
			Arrays.sort(localParticles, 0, localCount);
			Arrays.fill(representative, 0, localCount, false);
			selectDistributedRepresentatives(particleX, particleY, particleZ, localCount, target);
			ensureMaterialRepresentatives(particleMaterialHandle, particlePhaseLabel, chemicalParcels, localCount);
			MomentPreservingMergePlan plan = new MomentPreservingMergePlan(localParticles, representative, localCount,
					particleX, particleY, particleZ, velocityX, velocityY, velocityZ,
					particleVolume, particleMass, particleMaterialHandle, particlePhaseLabel,
					chemicalParcels, thermal, cellSize, positionValidator);
			geometrySkipped += plan.geometrySkipped;
			conservationSkipped += plan.conservationSkipped;
			for (int local = 0; local < localCount; local++) {
				if (plan.targets[local] < 0) continue;
				int source = localParticles[local], targetParticle = localParticles[plan.targets[local]];
				mergeInventories(targetParticle, source, particleVolume, particleMass, chemicalParcels, thermal);
				removed[source] = true;
				merged++;
			}
			for (int local = 0; local < localCount; local++) if (!removed[localParticles[local]]) {
				int particle = localParticles[local];
				particleX[particle] = plan.positions[0][local];
				particleY[particle] = plan.positions[1][local];
				particleZ[particle] = plan.positions[2][local];
				velocityX[particle] = plan.velocities[0][local];
				velocityY[particle] = plan.velocities[1][local];
				velocityZ[particle] = plan.velocities[2][local];
			}
		}
		return new MergeResult(merged, geometrySkipped, conservationSkipped);
	}

	private void selectDistributedRepresentatives(
			float[] particleX, float[] particleY, float[] particleZ, int count, int target
	) {
		representative[0] = true;
		int selected = 1;
		while (selected < target) {
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

	private static void mergeInventories(
			int target,
			int source,
			float[] particleVolume,
			double[] particleMass,
			ChemicalParcelStore chemicalParcels,
			ThermalAdapter thermal
	) {
		double targetVolume = particleVolume[target];
		double sourceVolume = particleVolume[source];
		double mergedVolume = targetVolume + sourceVolume;
		if (!exactlyMergeable(targetVolume, sourceVolume, mergedVolume)) throw new IllegalStateException("Preflighted quadrature merge changed its exact volume");
		particleVolume[target] = (float) mergedVolume;
		particleMass[target] += particleMass[source];
		if (chemicalParcels != null) chemicalParcels.merge(target, source, particleMass[target]);
		if (thermal != null) thermal.merge(target, source);
	}

	private static boolean exactlyMergeable(double first, double second, double sum) {
		return Double.isFinite(sum) && (double) (float) sum == sum
				&& sum - first == second && sum - second == first;
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
		if (bestAxis >= 0 && bestRoom > 0.0F) {
			// Half the available symmetric interval gives the two-point quadrature spacing
			// without a tuned displacement or a near-wall overshoot.
			float displacement = bestRoom * 0.5F;
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
		float inset = coordinate == 0 ? ConservativeMarkerSampling.DOMAIN_CONTACT_FRACTION
				: neighborSolid(axis, -1, x, y, z, solid)
				? ConservativeMarkerSampling.SOLID_CONTACT_FRACTION
				: ConservativeMarkerSampling.POSITION_EPSILON_FRACTION;
		return (coordinate + inset) * cellSize;
	}

	private float maximumCoordinate(int axis, int x, int y, int z, boolean[] solid) {
		int coordinate = axis == 0 ? x : axis == 1 ? y : z;
		int size = axis == 0 ? sizeX : axis == 1 ? sizeY : sizeZ;
		float inset = coordinate + 1 == size ? ConservativeMarkerSampling.DOMAIN_CONTACT_FRACTION
				: neighborSolid(axis, 1, x, y, z, solid)
				? ConservativeMarkerSampling.SOLID_CONTACT_FRACTION
				: ConservativeMarkerSampling.POSITION_EPSILON_FRACTION;
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

	private record MergeResult(int merged, int geometrySkipped, int conservationSkipped) { }

	record Result(
			int particleCount,
			int merged,
			int split,
			int budgetSkipped,
			int geometrySkipped,
			int conservationSkipped,
			double ledgerError,
			double massLedgerError,
			double energyLedgerError
	) { }
}
