package cc.ecisr.mmm.fluid;

/** Deterministic M2.5 gates for conservative marker merge/split and hard-budget refusal. */
public final class FlipFluidReseedingValidation {
	private static final int SIZE = 4;
	private static final float CELL_SIZE = 1.0F;

	private FlipFluidReseedingValidation() { }

	public static void main(String[] args) {
		boolean fast = java.util.Arrays.asList(args).contains("--fast");
		underpopulatedCellSplitsConservatively();
		overpopulatedCellMergesConservatively();
		mergePreservesSpatialCovarianceAndRejectsRankLoss();
		mergeRetainsAffineVelocityWithoutEnergyGain();
		nonBinaryVolumesRefuseInexactConsolidation();
		hardBudgetSkipsWithoutMutation();
		reseedingIsBitDeterministic();
		multiMaterialReseedingPreservesInventories();
		if (!fast) {
			longRunShearDistributionRemainsBounded();
			longRunDamBreakDistributionRemainsBounded();
		}
		System.out.println("FlipFluid reseeding validation passed" + (fast ? " (short; long-run soaks skipped)" : ""));
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
		int target = ConservativeMarkerSampling.targetMarkers(1.0F);
		check(result.particleCount() == target && result.split() == target - 1 && result.merged() == 0,
				"full underpopulated cell did not reach its quadrature count: " + result);
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
		int target = ConservativeMarkerSampling.targetMarkers((float) before.volume);
		check(result.particleCount() == target
				&& result.merged() == fixture.count - target && result.split() == 0,
				"overpopulated cell did not merge to its volume-derived quadrature count: " + result);
		checkLedger(before, after, 2.0E-6, "merge");
		check(Math.abs(result.ledgerError()) < 1.0E-12, "binary-fraction merge changed the exact float ledger");
	}

	private static void mergePreservesSpatialCovarianceAndRejectsRankLoss() {
		Fixture fixture = new Fixture(64, 16);
		for (int p = 0; p < fixture.count; p++) {
			float a = 0.25F * (p & 3) - 0.375F, b = 0.5F * (p >> 2 & 1) - 0.25F, c = 0.5F * (p >> 3 & 1) - 0.25F;
			fixture.x[p] = 1.5F + 0.6F * a + 0.2F * b;
			fixture.y[p] = 1.5F + 0.2F * a + 0.7F * b;
			fixture.z[p] = 1.5F + 0.15F * a + 0.2F * b + 0.5F * c;
			fixture.volume[p] = 0.0625F;
		}
		double[] before = spatialMoments(fixture, fixture.count);
		var result = fixture.reseed(64);
		check(result.merged() == 8, "positive-weight covariance fit did not consolidate the full-rank rule");
		double[] after = spatialMoments(fixture, result.particleCount());
		for (int m = 0; m < before.length; m++) check(Math.abs(after[m] - before[m]) < 2e-7,
				"quadrature replacement changed spatial moment " + m + ": " + before[m] + " -> " + after[m]);
		for (int p = 0; p < result.particleCount(); p++) check(fixture.volume[p] > 0
				&& fixture.x[p] >= 1 && fixture.x[p] < 2 && fixture.y[p] >= 1 && fixture.y[p] < 2
				&& fixture.z[p] >= 1 && fixture.z[p] < 2, "moment fit left the positive cell-local quadrature space");
		Fixture line = new Fixture(8, 2);
		line.x[0] = 1.25F; line.x[1] = 1.75F;
		for (int p = 0; p < 2; p++) { line.y[p] = line.z[p] = 1.5F; line.volume[p] = 0.0625F; }
		var refused = line.reseed(8);
		check(refused.merged() == 0 && refused.particleCount() == 2 && refused.geometrySkipped() > 0,
				"rank-losing replacement collapsed a finite spatial variance");
		check(line.x[0] == 1.25F && line.x[1] == 1.75F && line.volume[0] == 0.0625F && line.volume[1] == 0.0625F,
				"rank refusal changed the original quadrature");
	}

	private static void mergeRetainsAffineVelocityWithoutEnergyGain() {
		Fixture fixture = new Fixture(64, 16); double beforeEnergy = 0;
		for (int p = 0; p < fixture.count; p++) {
			fixture.x[p] = 1.125F + 0.25F * (p & 3);
			fixture.y[p] = 1.25F + 0.5F * (p >> 2 & 1);
			fixture.z[p] = 1.25F + 0.5F * (p >> 3 & 1);
			fixture.volume[p] = 0.0625F; fixture.mass[p] = 62.5;
			fixture.vx[p] = 0.4F * (fixture.x[p] - 1.5F) - 0.2F * (fixture.y[p] - 1.5F);
			fixture.vy[p] = 0.3F * (fixture.y[p] - 1.5F) + 0.1F * (fixture.z[p] - 1.5F);
			fixture.vz[p] = -0.1F * (fixture.x[p] - 1.5F);
			beforeEnergy += fixture.volume[p] * (fixture.vx[p] * fixture.vx[p] + fixture.vy[p] * fixture.vy[p] + fixture.vz[p] * fixture.vz[p]);
		}
		Ledger before = fixture.ledger(); var result = fixture.reseed(64); double afterEnergy = 0;
		check(result.merged() > 0, "affine quadrature fit refused its resolvable velocity field");
		for (int p = 0; p < result.particleCount(); p++) {
			check(Math.abs(fixture.vx[p] - (0.4F * (fixture.x[p] - 1.5F) - 0.2F * (fixture.y[p] - 1.5F))) < 1e-7
					&& Math.abs(fixture.vy[p] - (0.3F * (fixture.y[p] - 1.5F) + 0.1F * (fixture.z[p] - 1.5F))) < 1e-7
					&& Math.abs(fixture.vz[p] + 0.1F * (fixture.x[p] - 1.5F)) < 1e-7, "resampling lost its affine velocity field");
			afterEnergy += fixture.volume[p] * (fixture.vx[p] * fixture.vx[p] + fixture.vy[p] * fixture.vy[p] + fixture.vz[p] * fixture.vz[p]);
		}
		check(Math.abs(afterEnergy / beforeEnergy - 1) < 8 * Math.ulp(1.0F), "affine resampling changed kinetic energy beyond float roundoff");
		checkLedger(before, fixture.ledger(result.particleCount()), 2e-7, "affine merge");
	}

	private static double[] spatialMoments(Fixture fixture, int count) {
		double[] result = new double[10];
		for (int p = 0; p < count; p++) {
			double[] coordinate = {fixture.x[p] - 1.5, fixture.y[p] - 1.5, fixture.z[p] - 1.5};
			double w = fixture.volume[p]; result[0] += w;
			for (int a = 0; a < 3; a++) result[1 + a] += w * coordinate[a];
			int index = 4;
			for (int a = 0; a < 3; a++) for (int b = a; b < 3; b++) result[index++] += w * coordinate[a] * coordinate[b];
		}
		return result;
	}

	private static void hardBudgetSkipsWithoutMutation() {
		Fixture fixture = new Fixture(1, 1);
		fixture.x[0] = fixture.y[0] = fixture.z[0] = 1.5F;
		fixture.volume[0] = 1.0F;
		Ledger before = fixture.ledger();
		FlipParticleReseeder.Result result = fixture.reseed(1);
		Ledger after = fixture.ledger(result.particleCount());
		check(result.particleCount() == 1
				&& result.budgetSkipped() == ConservativeMarkerSampling.targetMarkers(1.0F) - 1,
				"hard marker budget did not report all skipped splits: " + result);
		checkLedger(before, after, 0.0, "budget refusal");
	}

	private static void nonBinaryVolumesRefuseInexactConsolidation() {
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
		int target = ConservativeMarkerSampling.targetMarkers((float) before.volume);
		check(result.particleCount() >= target && result.particleCount() == fixture.count - result.merged(),
				"non-binary merge count did not match its committed exact consolidations");
		check(result.particleCount() == target || result.conservationSkipped() > 0,
				"unfinished non-binary consolidation lacks a conservation refusal");
		checkLedger(before, after, 2.0E-6, "non-binary merge");
		check(result.ledgerError() == 0.0,
				"non-binary merge changed the authoritative float ledger: " + result.ledgerError());
		Fixture refused = new Fixture(2, 2);
		for (int p = 0; p < 2; p++) refused.x[p] = refused.y[p] = refused.z[p] = 1.5F;
		refused.volume[0] = 0.0625F;
		refused.volume[1] = Math.ulp(refused.volume[0]) / 2;
		Ledger refusalBefore = refused.ledger();
		var refusal = refused.reseed(2);
		check(refusal.particleCount() == 2 && refusal.merged() == 0 && refusal.conservationSkipped() == 1,
				"half-ULP tail was lost during exact consolidation");
		checkLedger(refusalBefore, refused.ledger(), 0, "half-ULP refusal");
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
		check(result.merged() > 0
				&& result.particleCount() <= ConservativeMarkerSampling.NOMINAL_MARKERS_PER_FULL_CELL,
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
		FluidDomain domain = new FluidDomain(8, 7, 8, config, 8 * 7 * 8 * 8);
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

		double exactVolume = domain.totalVolumeExact();
		long merges = 0, splits = 0, budgetSkipped = 0, geometrySkipped = 0, conservationSkipped = 0;
		double maximumLedgerError = 0.0;
		CpuFluidSolverBackend backend = new CpuFluidSolverBackend();
		for (int step = 0; step < 5_000; step++) {
			var candidate = backend.evaluate(new FluidStepRequest(step,
					domain.captureAuthoritativeState(), java.util.List.of(), false, 0));
			FluidDomain.StepStats stats = candidate.stats();
			domain = candidate.claimPreparedCpuDomain();
			merges += stats.reseededMerges();
			splits += stats.reseededSplits();
			budgetSkipped += stats.reseedBudgetSkipped();
			geometrySkipped += stats.reseedGeometrySkipped();
			conservationSkipped += stats.reseedConservationSkipped();
			maximumLedgerError = Math.max(maximumLedgerError, Math.abs(stats.reseedLedgerError()));
			check(stats.relativeVolumeError() < 2.0E-6F, "long-run shear step changed marker volume");
		}
		Distribution distribution = distribution(domain.exportParticleState());
		System.out.printf(
				"Reseeding long-run: markers %d, occupied %d, y=%d..%d, min/max %d/%d, merge/split %d/%d%n",
				domain.particleCount(), distribution.occupiedCells, distribution.minimumY, distribution.maximumY,
				distribution.minimumQualifyingMarkers, distribution.maximumMarkers, merges, splits
		);
		check(Double.doubleToRawLongBits(domain.totalVolumeExact()) == Double.doubleToRawLongBits(exactVolume),
				"long-run shear changed exact marker volume");
		check(budgetSkipped == 0, "ordinary long-run reseeding exhausted its hard marker budget");
		check(geometrySkipped == 0, "ordinary long-run reseeding could not place a conservative split");
		check(maximumLedgerError == 0.0,
				"ordinary long-run reseeding changed the exact float ledger: " + maximumLedgerError);
		check(distribution.maximumMarkers <= ConservativeMarkerSampling.NOMINAL_MARKERS_PER_FULL_CELL,
				"overpopulated cell escaped the quadrature count");
		check(distribution.minimumQualifyingMarkers >= ConservativeMarkerSampling.targetMarkers(0.50F),
				"a cell carrying at least half a cell-volume remained under-sampled");
		check(distribution.maximumY >= 3, "long-run marker distribution collapsed an entire initialized layer");
		check(conservationSkipped == 0, "binary-volume shear unexpectedly required a conservation refusal");
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
		FluidDomain domain = new FluidDomain(sizeX, sizeY, sizeZ, config, sizeX * sizeY * sizeZ * 8);
		for (int z = 0; z < sizeZ; z++) for (int x = 0; x < sizeX; x++) domain.setSolid(x, 0, z, true);
		for (int z = 0; z < sizeZ; z++) for (int y = 1; y <= 6; y++) for (int x = 0; x < 6; x++) {
			domain.setVolume(x, y, z, 1.0F);
		}
		double exactVolume = domain.totalVolumeExact();
		int initialParticleCount = domain.particleCount();
		long merges = 0, splits = 0, budgetSkipped = 0, geometrySkipped = 0, conservationSkipped = 0;
		double maximumLedgerError = 0.0;
		CpuFluidSolverBackend backend = new CpuFluidSolverBackend();
		for (int step = 0; step < 5_000; step++) {
			var candidate = backend.evaluate(new FluidStepRequest(step,
					domain.captureAuthoritativeState(), java.util.List.of(), false, 0));
			FluidDomain.StepStats stats = candidate.stats();
			domain = candidate.claimPreparedCpuDomain();
			merges += stats.reseededMerges();
			splits += stats.reseededSplits();
			budgetSkipped += stats.reseedBudgetSkipped();
			geometrySkipped += stats.reseedGeometrySkipped();
			conservationSkipped += stats.reseedConservationSkipped();
			maximumLedgerError = Math.max(maximumLedgerError, Math.abs(stats.reseedLedgerError()));
			check(stats.relativeVolumeError() < 2.0E-6F, "long-run dam-break step changed marker volume");
		}
		FlipParticleState finalState = domain.exportParticleState();
		Distribution distribution = distribution(finalState);
		System.out.printf(
				"Reseeding dam-break: markers %d, occupied %d, y=%d..%d, min/max %d/%d, merge/split %d/%d%n",
				domain.particleCount(), distribution.occupiedCells, distribution.minimumY, distribution.maximumY,
				distribution.minimumQualifyingMarkers, distribution.maximumMarkers,
				merges, splits
		);
		check(Double.doubleToRawLongBits(domain.totalVolumeExact()) == Double.doubleToRawLongBits(exactVolume),
				"long-run dam-break changed exact marker volume");
		check(budgetSkipped == 0, "long-run dam-break reseeding exhausted its hard marker budget");
		check(maximumLedgerError == 0.0,
				"long-run dam-break changed the exact float ledger: " + maximumLedgerError);
		check(domain.particleCount() <= initialParticleCount,
				"dam-break exact residual markers exceeded the fixture's initial marker population");
		check(distribution.maximumMarkers <= ConservativeMarkerSampling.NOMINAL_MARKERS_PER_FULL_CELL
				|| conservationSkipped > 0 || geometrySkipped > 0,
				"overpopulated dam-break cells lack a conservation or spatial-quadrature refusal");
		check(distribution.minimumQualifyingMarkers >= ConservativeMarkerSampling.targetMarkers(0.50F),
				"dam-break left a half-full marker cell under-sampled");
		check(distribution.occupiedCells >= 216,
				"long-run dam-break compressed 216 cell-volumes into too few pressure cells: "
						+ distribution.occupiedCells);
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
					solid, x, y, z, vx, vy, vz, volume, mass, material, phase, null, count, false,
					(candidateX, candidateY, candidateZ) -> true
			);
		}

		private FlipParticleReseeder.Result reseedMaterialAware(int maximumParticleCount) {
			return new FlipParticleReseeder(SIZE, SIZE, SIZE, CELL_SIZE, maximumParticleCount).reseed(
					solid, x, y, z, vx, vy, vz, volume, mass, material, phase, null, count, true,
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

}
