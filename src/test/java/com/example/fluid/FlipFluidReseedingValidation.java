package com.example.fluid;

/** Deterministic M2.5 gates for conservative marker merge/split and hard-budget refusal. */
public final class FlipFluidReseedingValidation {
	private static final int SIZE = 4;
	private static final float CELL_SIZE = 1.0F;

	private FlipFluidReseedingValidation() { }

	public static void main(String[] args) {
		underpopulatedCellSplitsConservatively();
		overpopulatedCellMergesConservatively();
		nonBinaryVolumesRemainWithinFloatLedgerTolerance();
		hardBudgetSkipsWithoutMutation();
		reseedingIsBitDeterministic();
		multiMaterialReseedingPreservesInventories();
		longRunShearDistributionRemainsBounded();
		longRunDamBreakDistributionRemainsBounded();
		System.out.println("FlipFluid reseeding validation passed");
	}

	private static void underpopulatedCellSplitsConservatively() {
		Fixture fixture = new Fixture(64, 1);
		fixture.x[0] = 1.5F;
		fixture.y[0] = 1.5F;
		fixture.z[0] = 1.5F;
		fixture.vx[0] = 0.25F;
		fixture.vy[0] = -0.50F;
		fixture.vz[0] = 0.75F;
		fixture.volume[0] = 1.0F;
		Ledger before = fixture.ledger();
		FlipParticleReseeder.Result result = fixture.reseed(64);
		Ledger after = fixture.ledger(result.particleCount());
		check(result.particleCount() == 4 && result.split() == 3 && result.merged() == 0,
				"full underpopulated cell did not split to four markers: " + result);
		checkLedger(before, after, 2.0E-7, "split");
		check(Math.abs(result.ledgerError()) < 1.0E-12, "power-of-two split changed the exact float ledger");
	}

	private static void overpopulatedCellMergesConservatively() {
		Fixture fixture = new Fixture(64, 16);
		for (int particle = 0; particle < fixture.count; particle++) {
			fixture.x[particle] = 1.10F + 0.20F * (particle & 3);
			fixture.y[particle] = 1.15F + 0.20F * ((particle >> 2) & 3);
			fixture.z[particle] = 1.20F + 0.10F * (particle & 1);
			fixture.vx[particle] = -0.30F + particle * 0.04F;
			fixture.vy[particle] = 0.20F - particle * 0.01F;
			fixture.vz[particle] = (particle & 1) == 0 ? 0.125F : -0.125F;
			fixture.volume[particle] = particle % 3 == 0 ? 0.03125F : 0.0625F;
		}
		Ledger before = fixture.ledger();
		FlipParticleReseeder.Result result = fixture.reseed(64);
		Ledger after = fixture.ledger(result.particleCount());
		check(result.particleCount() == 8 && result.merged() == 8 && result.split() == 0,
				"overpopulated cell did not merge to eight distributed markers: " + result);
		checkLedger(before, after, 2.0E-6, "merge");
		check(Math.abs(result.ledgerError()) < 1.0E-12, "binary-fraction merge changed the exact float ledger");
	}

	private static void hardBudgetSkipsWithoutMutation() {
		Fixture fixture = new Fixture(1, 1);
		fixture.x[0] = fixture.y[0] = fixture.z[0] = 1.5F;
		fixture.volume[0] = 1.0F;
		Ledger before = fixture.ledger();
		FlipParticleReseeder.Result result = fixture.reseed(1);
		Ledger after = fixture.ledger(result.particleCount());
		check(result.particleCount() == 1 && result.budgetSkipped() == 3,
				"hard marker budget did not report all skipped splits: " + result);
		checkLedger(before, after, 0.0, "budget refusal");
	}

	private static void nonBinaryVolumesRemainWithinFloatLedgerTolerance() {
		Fixture fixture = new Fixture(64, 17);
		for (int particle = 0; particle < fixture.count; particle++) {
			fixture.x[particle] = 1.10F + 0.17F * (particle & 3);
			fixture.y[particle] = 1.12F + 0.16F * ((particle >> 2) & 3);
			fixture.z[particle] = 1.15F + 0.08F * (particle & 1);
			fixture.vx[particle] = 0.013F * particle;
			fixture.vy[particle] = -0.007F * particle;
			fixture.vz[particle] = 0.011F * (particle - 8);
			fixture.volume[particle] = 0.017F + 0.0013F * particle;
		}
		Ledger before = fixture.ledger();
		FlipParticleReseeder.Result result = fixture.reseed(64);
		Ledger after = fixture.ledger(result.particleCount());
		check(result.particleCount() == 8 && result.merged() == 9,
				"non-binary merge did not reach its bounded target");
		checkLedger(before, after, 2.0E-6, "non-binary merge");
		check(Math.abs(result.ledgerError()) < 2.0E-7,
				"non-binary merge exceeded the declared float-ledger tolerance: " + result.ledgerError());
	}

	private static void reseedingIsBitDeterministic() {
		Fixture first = mixedFixture();
		Fixture second = mixedFixture();
		FlipParticleReseeder.Result firstResult = first.reseed(64);
		FlipParticleReseeder.Result secondResult = second.reseed(64);
		check(firstResult.equals(secondResult), "deterministic fixture produced different diagnostics");
		int count = firstResult.particleCount();
		check(equalBits(first.x, second.x, count) && equalBits(first.y, second.y, count)
				&& equalBits(first.z, second.z, count) && equalBits(first.vx, second.vx, count)
				&& equalBits(first.vy, second.vy, count) && equalBits(first.vz, second.vz, count)
				&& equalBits(first.volume, second.volume, count),
				"fixed-order reseeding did not reproduce bit-identical marker state");
	}

	private static Fixture mixedFixture() {
		Fixture fixture = new Fixture(64, 14);
		for (int particle = 0; particle < fixture.count; particle++) {
			fixture.x[particle] = 1.15F + 0.10F * (particle & 3);
			fixture.y[particle] = 1.20F + 0.08F * ((particle >> 2) & 3);
			fixture.z[particle] = 1.25F + 0.05F * (particle & 1);
			fixture.vx[particle] = particle * 0.01F;
			fixture.vy[particle] = -particle * 0.02F;
			fixture.vz[particle] = particle * 0.03F;
			fixture.volume[particle] = 0.0625F;
		}
		return fixture;
	}

	private static void multiMaterialReseedingPreservesInventories() {
		Fixture fixture = new Fixture(64, 16);
		for (int particle = 0; particle < fixture.count; particle++) {
			fixture.x[particle] = 1.10F + 0.20F * (particle & 3);
			fixture.y[particle] = 1.15F + 0.20F * ((particle >> 2) & 3);
			fixture.z[particle] = 1.20F + 0.10F * (particle & 1);
			fixture.vx[particle] = (particle % 4 - 2) * 0.125F;
			fixture.vy[particle] = (particle % 3 - 1) * 0.25F;
			fixture.vz[particle] = (particle & 1) == 0 ? 0.125F : -0.125F;
			fixture.volume[particle] = 0.0625F;
			boolean second = (particle & 1) != 0;
			fixture.mass[particle] = fixture.volume[particle] * (second ? 800.0 : 1_000.0);
			fixture.material[particle] = second ? 202 : 101;
			fixture.phase[particle] = second ? 22 : 11;
		}
		MaterialLedger before = fixture.materialLedger(fixture.count);
		FlipParticleReseeder.Result result = fixture.reseedMaterialAware(64);
		MaterialLedger after = fixture.materialLedger(result.particleCount());
		check(result.merged() > 0 && result.particleCount() <= 12,
				"multi-material overpopulation did not reduce without crossing labels: " + result);
		check(Math.abs(result.massLedgerError()) <= 1.0E-12, "multi-material reseeding changed exact mass");
		check(Math.abs(after.massA - before.massA) <= 1.0E-12
				&& Math.abs(after.massB - before.massB) <= 1.0E-12,
				"multi-material reseeding moved mass across material ledgers");
		check(Math.abs(after.momentumX - before.momentumX) <= 2.0E-5
				&& Math.abs(after.momentumY - before.momentumY) <= 2.0E-5
				&& Math.abs(after.momentumZ - before.momentumZ) <= 2.0E-5,
				"multi-material reseeding changed mass-weighted momentum");
		for (int particle = 0; particle < result.particleCount(); particle++) {
			check((fixture.material[particle] == 101 && fixture.phase[particle] == 11)
					|| (fixture.material[particle] == 202 && fixture.phase[particle] == 22),
					"reseeding crossed a material/phase boundary");
		}
	}

	private static void longRunShearDistributionRemainsBounded() {
		FluidSolverConfig config = new FluidSolverConfig(
				0.5F, 1_000.0F, 9.81F, 1.0F / 40.0F,
				160, 1.0E-5F, 0.40F, 2.0E-3F, 0.80F
		);
		FlipFluidDomain domain = new FlipFluidDomain(8, 7, 8, config, 8 * 7 * 8 * 8);
		for (int z = 0; z < 8; z++) for (int x = 0; x < 8; x++) domain.setSolid(x, 0, z, true);
		for (int z = 0; z < 8; z++) for (int y = 1; y <= 3; y++) for (int x = 0; x < 8; x++) {
			domain.setVolume(x, y, z, 1.0F);
		}
		FlipParticleState initial = domain.exportParticleState();
		float[] positions = initial.copyPositionVolume();
		float[] velocities = initial.copyParticleVelocities();
		for (int particle = 0; particle < initial.particleCount(); particle++) {
			float normalizedY = positions[particle * 4 + 1] / config.cellSize();
			velocities[particle * 3] = normalizedY >= 2.5F ? 0.35F : -0.35F;
			velocities[particle * 3 + 2] = ((particle * 17) & 7) * 0.025F - 0.0875F;
		}
		domain.restoreParticleState(new FlipParticleState(
				domain.sizeX(), domain.sizeY(), domain.sizeZ(), config.cellSize(), initial.particleCount(),
				initial.totalVolume(), positions, velocities, initial.copySolidMask()
		));

		float exactVolume = domain.totalVolume();
		long merges = 0, splits = 0, budgetSkipped = 0, geometrySkipped = 0;
		double maximumLedgerError = 0.0;
		for (int step = 0; step < 5_000; step++) {
			FlipFluidDomain.StepStats stats = domain.step();
			merges += stats.reseededMerges();
			splits += stats.reseededSplits();
			budgetSkipped += stats.reseedBudgetSkipped();
			geometrySkipped += stats.reseedGeometrySkipped();
			maximumLedgerError = Math.max(maximumLedgerError, Math.abs(stats.reseedLedgerError()));
			check(stats.relativeVolumeError() < 2.0E-6F, "long-run shear step changed marker volume");
		}
		Distribution distribution = distribution(domain.exportParticleState());
		System.out.printf(
				"Reseeding long-run: markers %d, occupied %d, y=%d..%d, min/max %d/%d, merge/split %d/%d%n",
				domain.particleCount(), distribution.occupiedCells, distribution.minimumY, distribution.maximumY,
				distribution.minimumQualifyingMarkers, distribution.maximumMarkers, merges, splits
		);
		check(Math.abs(domain.totalVolume() - exactVolume) < 1.0E-4F, "long-run shear changed exact marker volume");
		check(budgetSkipped == 0, "ordinary long-run reseeding exhausted its hard marker budget");
		check(geometrySkipped == 0, "ordinary long-run reseeding could not place a conservative split");
		check(maximumLedgerError < 2.0E-7,
				"ordinary long-run reseeding exceeded float-ledger tolerance: " + maximumLedgerError);
		check(distribution.maximumMarkers <= 12, "overpopulated cell escaped the merge threshold");
		check(distribution.minimumQualifyingMarkers >= 4,
				"a cell carrying at least half a cell-volume remained under-sampled");
		check(distribution.maximumY >= 3, "long-run marker distribution collapsed an entire initialized layer");
	}

	private static Distribution distribution(FlipParticleState state) {
		int cellCount = state.sizeX() * state.sizeY() * state.sizeZ();
		int[] counts = new int[cellCount];
		float[] volumes = new float[cellCount];
		float[] positions = state.copyPositionVolume();
		int minimumY = state.sizeY();
		int maximumY = -1;
		for (int particle = 0; particle < state.particleCount(); particle++) {
			int base = particle * 4;
			int x = Math.min(state.sizeX() - 1, (int) Math.floor(positions[base] / state.cellSize()));
			int y = Math.min(state.sizeY() - 1, (int) Math.floor(positions[base + 1] / state.cellSize()));
			int z = Math.min(state.sizeZ() - 1, (int) Math.floor(positions[base + 2] / state.cellSize()));
			int cell = (z * state.sizeY() + y) * state.sizeX() + x;
			counts[cell]++;
			volumes[cell] += positions[base + 3];
			minimumY = Math.min(minimumY, y);
			maximumY = Math.max(maximumY, y);
		}
		int occupied = 0;
		int minimumQualifying = Integer.MAX_VALUE;
		int maximum = 0;
		for (int cell = 0; cell < cellCount; cell++) {
			if (counts[cell] > 0) occupied++;
			if (volumes[cell] >= 0.50F) minimumQualifying = Math.min(minimumQualifying, counts[cell]);
			maximum = Math.max(maximum, counts[cell]);
		}
		return new Distribution(occupied, minimumY, maximumY, minimumQualifying, maximum);
	}

	private static void longRunDamBreakDistributionRemainsBounded() {
		FluidSolverConfig config = new FluidSolverConfig(
				0.5F, 1_000.0F, 9.81F, 1.0F / 40.0F,
				160, 1.0E-5F, 0.40F, 2.0E-3F, 0.80F
		);
		int sizeX = 12, sizeY = 8, sizeZ = 6;
		FlipFluidDomain domain = new FlipFluidDomain(sizeX, sizeY, sizeZ, config, sizeX * sizeY * sizeZ * 8);
		for (int z = 0; z < sizeZ; z++) for (int x = 0; x < sizeX; x++) domain.setSolid(x, 0, z, true);
		for (int z = 0; z < sizeZ; z++) for (int y = 1; y <= 6; y++) for (int x = 0; x < 6; x++) {
			domain.setVolume(x, y, z, 1.0F);
		}
		float exactVolume = domain.totalVolume();
		long merges = 0, splits = 0, budgetSkipped = 0, geometrySkipped = 0;
		double maximumLedgerError = 0.0;
		DamSurface settledReference = null;
		for (int step = 0; step < 5_000; step++) {
			FlipFluidDomain.StepStats stats = domain.step();
			merges += stats.reseededMerges();
			splits += stats.reseededSplits();
			budgetSkipped += stats.reseedBudgetSkipped();
			geometrySkipped += stats.reseedGeometrySkipped();
			maximumLedgerError = Math.max(maximumLedgerError, Math.abs(stats.reseedLedgerError()));
			check(stats.relativeVolumeError() < 2.0E-6F, "long-run dam-break step changed marker volume");
			if (step + 1 == 1_000) settledReference = damSurface(domain);
		}
		FlipParticleState finalState = domain.exportParticleState();
		Distribution distribution = distribution(finalState);
		DamSurface finalSurface = damSurface(domain);
		System.out.printf(
				"Reseeding dam-break: markers %d, occupied %d, y=%d..%d, min/max %d/%d, "
						+ "top/above %.3f/%.3f, max/excess %.3f/%.3f, comY %.3f, visible %d, "
						+ "merge/split %d/%d%n",
				domain.particleCount(), distribution.occupiedCells, distribution.minimumY, distribution.maximumY,
				distribution.minimumQualifyingMarkers, distribution.maximumMarkers,
				finalSurface.targetTopLayerVolume, finalSurface.volumeAboveTarget,
				finalSurface.maximumCellVolume, finalSurface.excessCellVolume,
				finalSurface.centerOfMassY, finalSurface.visibleCells, merges, splits
		);
		check(Math.abs(domain.totalVolume() - exactVolume) < 1.0E-4F, "long-run dam-break changed exact marker volume");
		check(budgetSkipped == 0, "long-run dam-break reseeding exhausted its hard marker budget");
		check(geometrySkipped == 0, "long-run dam-break could not place a conservative split");
		check(maximumLedgerError < 2.0E-7,
				"long-run dam-break exceeded float-ledger tolerance: " + maximumLedgerError);
		check(distribution.maximumMarkers <= 12, "dam-break left an overpopulated marker cell");
		check(distribution.minimumQualifyingMarkers >= 4,
				"dam-break left a half-full marker cell under-sampled");
		check(distribution.occupiedCells >= 216,
				"long-run dam-break compressed 216 cell-volumes into too few pressure cells: "
						+ distribution.occupiedCells);
		check(finalSurface.targetTopLayerVolume >= 48.0,
				"long-run dam-break collapsed its third physical layer: " + finalSurface.targetTopLayerVolume);
		check(finalSurface.volumeAboveTarget < 2.0,
				"volume-drift correction sprayed persistent water above the target depth: "
						+ finalSurface.volumeAboveTarget);
		check(finalSurface.maximumCellVolume <= 2.0 && finalSurface.excessCellVolume <= 32.0,
				"long-run dam-break retained severe containing-cell compression: " + finalSurface);
		check(settledReference != null
				&& Math.abs(finalSurface.centerOfMassY - settledReference.centerOfMassY) < 0.03
				&& Math.abs(finalSurface.visibleCells - settledReference.visibleCells) < 16,
				"dam-break surface continued to sink after the settled reference: at 1000="
						+ settledReference + ", at 5000=" + finalSurface);
	}

	private static DamSurface damSurface(FlipFluidDomain domain) {
		FlipParticleState state = domain.exportParticleState();
		float[] particles = state.copyPositionVolume();
		float[] cellVolumes = new float[state.sizeX() * state.sizeY() * state.sizeZ()];
		double weightedY = 0.0, totalVolume = 0.0;
		double targetTopLayerVolume = 0.0, volumeAboveTarget = 0.0;
		for (int particle = 0; particle < state.particleCount(); particle++) {
			int base = particle * 4;
			float amount = particles[base + 3];
			int x = Math.min(state.sizeX() - 1, (int) Math.floor(particles[base] / state.cellSize()));
			int y = Math.min(state.sizeY() - 1, (int) Math.floor(particles[base + 1] / state.cellSize()));
			int z = Math.min(state.sizeZ() - 1, (int) Math.floor(particles[base + 2] / state.cellSize()));
			cellVolumes[(z * state.sizeY() + y) * state.sizeX() + x] += amount;
			weightedY += particles[base + 1] / state.cellSize() * amount;
			totalVolume += amount;
			if (y == 3) targetTopLayerVolume += amount;
			else if (y > 3) volumeAboveTarget += amount;
		}
		double maximumCellVolume = 0.0, excessCellVolume = 0.0;
		for (float cellVolume : cellVolumes) {
			maximumCellVolume = Math.max(maximumCellVolume, cellVolume);
			excessCellVolume += Math.max(0.0, cellVolume - 1.0F);
		}
		FlipFluidDomain.VolumeDistribution distribution = domain.volumeDistribution(8.0F / 255.0F);
		return new DamSurface(
				weightedY / totalVolume, targetTopLayerVolume, volumeAboveTarget,
				maximumCellVolume, excessCellVolume, distribution.visibleCells()
		);
	}

	private static void checkLedger(Ledger before, Ledger after, double tolerance, String operation) {
		check(Math.abs(after.volume - before.volume) <= tolerance, operation + " changed marker volume");
		check(Math.abs(after.momentumX - before.momentumX) <= tolerance
				&& Math.abs(after.momentumY - before.momentumY) <= tolerance
				&& Math.abs(after.momentumZ - before.momentumZ) <= tolerance,
				operation + " changed marker momentum");
		check(Math.abs(after.centroidX - before.centroidX) <= tolerance
				&& Math.abs(after.centroidY - before.centroidY) <= tolerance
				&& Math.abs(after.centroidZ - before.centroidZ) <= tolerance,
				operation + " changed marker volume centroid");
	}

	private static boolean equalBits(float[] first, float[] second, int length) {
		for (int index = 0; index < length; index++) {
			if (Float.floatToRawIntBits(first[index]) != Float.floatToRawIntBits(second[index])) return false;
		}
		return true;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static final class Fixture {
		private final boolean[] solid = new boolean[SIZE * SIZE * SIZE];
		private final float[] x;
		private final float[] y;
		private final float[] z;
		private final float[] vx;
		private final float[] vy;
		private final float[] vz;
		private final float[] volume;
		private final double[] mass;
		private final int[] material;
		private final int[] phase;
		private final int count;

		private Fixture(int capacity, int count) {
			this.x = new float[capacity];
			this.y = new float[capacity];
			this.z = new float[capacity];
			this.vx = new float[capacity];
			this.vy = new float[capacity];
			this.vz = new float[capacity];
			this.volume = new float[capacity];
			this.mass = new double[capacity];
			this.material = new int[capacity];
			this.phase = new int[capacity];
			this.count = count;
		}

		private FlipParticleReseeder.Result reseed(int maximumParticleCount) {
			for (int particle = 0; particle < count; particle++) mass[particle] = volume[particle] * 1_000.0;
			return new FlipParticleReseeder(SIZE, SIZE, SIZE, CELL_SIZE, maximumParticleCount).reseed(
					solid, x, y, z, vx, vy, vz, volume, mass, material, phase, count, false,
					(candidateX, candidateY, candidateZ) -> true
			);
		}

		private FlipParticleReseeder.Result reseedMaterialAware(int maximumParticleCount) {
			return new FlipParticleReseeder(SIZE, SIZE, SIZE, CELL_SIZE, maximumParticleCount).reseed(
					solid, x, y, z, vx, vy, vz, volume, mass, material, phase, count, true,
					(candidateX, candidateY, candidateZ) -> true
			);
		}

		private MaterialLedger materialLedger(int particleCount) {
			double massA = 0.0, massB = 0.0;
			double momentumX = 0.0, momentumY = 0.0, momentumZ = 0.0;
			for (int particle = 0; particle < particleCount; particle++) {
				if (material[particle] == 101) massA += mass[particle];
				else if (material[particle] == 202) massB += mass[particle];
				momentumX += mass[particle] * vx[particle];
				momentumY += mass[particle] * vy[particle];
				momentumZ += mass[particle] * vz[particle];
			}
			return new MaterialLedger(massA, massB, momentumX, momentumY, momentumZ);
		}

		private Ledger ledger() {
			return ledger(count);
		}

		private Ledger ledger(int particleCount) {
			double totalVolume = 0.0;
			double weightedX = 0.0, weightedY = 0.0, weightedZ = 0.0;
			double momentumX = 0.0, momentumY = 0.0, momentumZ = 0.0;
			for (int particle = 0; particle < particleCount; particle++) {
				double amount = volume[particle];
				totalVolume += amount;
				weightedX += x[particle] * amount;
				weightedY += y[particle] * amount;
				weightedZ += z[particle] * amount;
				momentumX += vx[particle] * amount;
				momentumY += vy[particle] * amount;
				momentumZ += vz[particle] * amount;
			}
			return new Ledger(
					totalVolume, momentumX, momentumY, momentumZ,
					weightedX / totalVolume, weightedY / totalVolume, weightedZ / totalVolume
			);
		}
	}

	private record Ledger(
			double volume,
			double momentumX,
			double momentumY,
			double momentumZ,
			double centroidX,
			double centroidY,
			double centroidZ
	) { }

	private record MaterialLedger(
			double massA,
			double massB,
			double momentumX,
			double momentumY,
			double momentumZ
	) { }

	private record Distribution(
			int occupiedCells,
			int minimumY,
			int maximumY,
			int minimumQualifyingMarkers,
			int maximumMarkers
	) { }

	private record DamSurface(
			double centerOfMassY,
			double targetTopLayerVolume,
			double volumeAboveTarget,
			double maximumCellVolume,
			double excessCellVolume,
			int visibleCells
	) { }
}
