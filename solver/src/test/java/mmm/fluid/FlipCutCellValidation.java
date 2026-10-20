package mmm.fluid;

/** Deterministic M2.5 gates for runtime cut-cell volume, apertures, persistence fallback and flow. */
public final class FlipCutCellValidation {
	private static final int LOWER_HALF = 0x33;
	private static final int NEGATIVE_X_HALF = 0x55;

	private FlipCutCellValidation() { }

	public static void main(String[] args) {
		boolean fast = args.length == 1 && args[0].equals("--fast");
		boolean flowOnly = args.length == 1 && args[0].equals("--flow-only");
		if (args.length != 0 && !fast && !flowOnly)
			throw new IllegalArgumentException("Use --fast, --flow-only, or no arguments for the complete cut-cell validation");
		if (flowOnly) {
			partialObstacleFlowRemainsFiniteAndConservative();
			System.out.println("FlipFluid cut-cell validation passed (600-step obstacle flow)");
			return;
		}
		solverConfigRejectsNonfiniteCoefficients();
		LiquidViscosityValidation.validate();
		volumeAndSharedFaceFractionsAreConsistent();
		wallAreasFollowMicroGeometry();
		bodyOverlapExcludesOccupiedMicroCells();
		collisionDisplacementUsesOpenCapacity();
		cutCellCapacityDisplacesImportedInventory();
		checkpointPreservesCutCellGeometry();
		if (!fast) partialObstacleFlowRemainsFiniteAndConservative();
		System.out.println("FlipFluid cut-cell validation passed" + (fast ? " (short; 600-step obstacle flow skipped)" : ""));
	}

	private static void solverConfigRejectsNonfiniteCoefficients() {
		for (float invalid : new float[] {Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY}) {
			for (int field = 0; field < 5; field++) {
				float[] values = {.5F, 1000, 9.81F, .05F, 5E-5F}; values[field] = invalid;
				boolean rejected = false;
				try {
					new FluidSolverConfig(values[0], values[1], values[2], values[3],
							160, values[4], .002F, .8F);
				} catch (IllegalArgumentException expected) { rejected = true; }
				check(rejected, "solver config accepted nonfinite coefficient at field " + field + ": " + invalid);
			}
		}
		new FluidSolverConfig(.5F, 1000, 0, .05F, 160, 5E-5F, .002F, 0);
		new FluidSolverConfig(.5F, 1000, 9.81F, .05F, 160, 5E-5F, .002F, 1);
	}

	private static void bodyOverlapExcludesOccupiedMicroCells() {
		FluidDomain domain = new FluidDomain(4, 4, 4, config());
		domain.setSolidMicroMask(1, 1, 1, LOWER_HALF);
		domain.setVolume(1, 1, 1, 1.0F);
		double h = domain.config().cellSize();
		FluidDomain.BodyFluidSample sample = domain.sampleAxisAlignedBody(
				h, h, h, 2.0 * h, 2.0 * h, 2.0 * h);
		double expectedOpenVolume = domain.volume(1, 1, 1) * h * h * h;
		check(Math.abs(sample.displacedVolumeCubicMetres() - expectedOpenVolume) < 1.0E-9,
				"body overlap counted occupied cut-cell volume: expected=" + expectedOpenVolume
						+ ", actual=" + sample.displacedVolumeCubicMetres());
		check(sample.centerYMetres() >= 1.5 * h && sample.centerYMetres() <= 2.0 * h,
				"body overlap centroid entered occupied cut-cell half");
	}

	private static void volumeAndSharedFaceFractionsAreConsistent() {
		FluidDomain domain = new FluidDomain(4, 4, 4, config());
		domain.setSolidMicroMask(1, 1, 1, LOWER_HALF);
		checkClose(domain.solidVolumeFraction(1, 1, 1), 0.5F, 0.0F, "cut-cell solid volume");
		checkClose(domain.openFaceFractionX(1, 1, 1), 0.5F, 0.0F, "negative X aperture");
		checkClose(domain.openFaceFractionX(2, 1, 1), 0.5F, 0.0F, "positive X aperture");
		checkClose(domain.openFaceFractionY(1, 1, 1), 0.0F, 0.0F, "blocked lower Y aperture");
		checkClose(domain.openFaceFractionY(1, 2, 1), 1.0F, 0.0F, "open upper Y aperture");
		checkClose(domain.openFaceFractionZ(1, 1, 1), 0.5F, 0.0F, "negative Z aperture");
		checkClose(domain.openFaceFractionZ(1, 1, 2), 0.5F, 0.0F, "positive Z aperture");
	}

	private static void wallAreasFollowMicroGeometry() {
		int size = 5;
		FlipCutCellGeometry geometry = new FlipCutCellGeometry(size, size, size);
		int cutCell = index(size, size, 2, 2, 2);
		geometry.replaceMask(cutCell, LOWER_HALF);
		checkClose(geometry.solidWallArea(cutCell, 0), 0.0F, 0.0F, "cut-cell X-normal wall area");
		checkClose(geometry.solidWallArea(cutCell, 1), 1.0F, 0.0F, "cut-cell internal Y-normal wall area");
		checkClose(geometry.solidWallArea(cutCell, 2), 0.0F, 0.0F, "cut-cell Z-normal wall area");
		checkClose(geometry.solidWallArea(index(size, size, 1, 2, 2), 0), 0.5F, 0.0F,
				"open neighbor did not own the cut-cell side wall");
		checkClose(geometry.solidWallArea(index(size, size, 2, 1, 2), 1), 1.0F, 0.0F,
				"open neighbor did not own the cut-cell lower wall");
	}




	private static void collisionDisplacementUsesOpenCapacity() {
		FluidDomain domain = new FluidDomain(5, 5, 5, config());
		domain.setVolume(2, 2, 2, 1.0F);
		float before = domain.totalVolume();
		float displaced = domain.setSolidMicroMask(2, 2, 2, LOWER_HALF);
		checkClose(displaced, 0.5F, 1.0E-7F, "exact displaced liquid volume");
		checkClose(domain.totalVolume(), before - displaced, 1.0E-7F, "remaining liquid volume");
		float restored = domain.addVolumeNear(2, 2, 2, displaced, 3);
		checkClose(restored, displaced, 1.0E-7F, "nearby exact restoration");
		checkClose(domain.totalVolume(), before, 1.0E-7F, "restored liquid volume");
	}

	private static void cutCellCapacityDisplacesImportedInventory() {
		FluidDomain domain = new FluidDomain(5, 5, 5, config());
		float coordinate = (2.0F + 0.75F) * domain.config().cellSize();
		domain.restoreParticleState(new FlipParticleState(
				5, 5, 5, domain.config().cellSize(), 1, 1.0F,
				new float[] {coordinate, coordinate, coordinate, 1.0F},
				new float[] {0.0F, 0.0F, 0.0F}, mmm.units.Mass.createLiquid(domain.config().cellSize(), 1000.0, new float[] {coordinate, coordinate, coordinate, 1.0F}), new byte[5 * 5 * 5]
		));
		float displaced = domain.setSolidMicroMask(2, 2, 2, LOWER_HALF);
		checkClose(displaced, 0.5F, 1.0E-7F,
				"cut-cell capacity did not displace excess imported inventory");
		checkClose(domain.totalVolume(), 0.5F, 1.0E-7F, "cut-cell capacity remaining ledger");
		checkClose(domain.addVolumeNear(2, 2, 2, displaced, 3), displaced, 1.0E-7F,
				"cut-cell capacity restoration");
		checkClose(domain.totalVolume(), 1.0F, 1.0E-7F, "cut-cell capacity restored ledger");
	}

	private static void checkpointPreservesCutCellGeometry() {
		FluidDomain live = new FluidDomain(4, 4, 4, config());
		live.setSolidMicroMask(1, 1, 1, LOWER_HALF);
		live.setSolid(3, 3, 3, true);
		live.setVolume(1, 1, 1, 1.0F);
		float exactVolume = live.totalVolume();
		var checkpoint = live.captureAuthoritativeState();
		int partial = index(4, 4, 1, 1, 1);
		int full = index(4, 4, 3, 3, 3);
		check(Byte.toUnsignedInt(checkpoint.copyCollisionMasks()[partial]) == LOWER_HALF,
				"checkpoint lost partial cut-cell geometry");
		check(Byte.toUnsignedInt(checkpoint.copyCollisionMasks()[full]) == 0xff,
				"fully solid checkpoint cell was lost");

		FluidDomain restored = FluidDomain.restoreAuthoritativeState(checkpoint);
		check(restored.exportLiquidCellState().stocks().equals(checkpoint.liquid().stocks()), "Cut-cell restore changed cell inventories");
		check(restored.solidMicroMask(1, 1, 1) == LOWER_HALF, "partial cut-cell geometry changed on restore");
		float displacedOnRefresh = restored.setSolidMicroMask(1, 1, 1, LOWER_HALF);
		checkClose(displacedOnRefresh, 0.0F, 0.0F,
				"unchanged world geometry displaced liquid after checkpoint restore");
		checkClose(restored.totalVolume(), exactVolume, 0.0F, "restored liquid volume");
	}

	private static void partialObstacleFlowRemainsFiniteAndConservative() {
		FluidDomain domain = new FluidDomain(10, 8, 8, config());
		for (int z = 0; z < 8; z++) for (int x = 0; x < 10; x++) domain.setSolid(x, 0, z, true);
		for (int z = 2; z <= 5; z++) for (int y = 1; y <= 3; y++) {
			domain.setSolidMicroMask(5, y, z, NEGATIVE_X_HALF);
		}
		for (int z = 2; z <= 5; z++) for (int y = 1; y <= 5; y++) for (int x = 1; x <= 4; x++) {
			domain.setVolume(x, y, z, 1.0F);
		}
		float exactVolume = domain.totalVolume();
		long exactMass = domain.totalMassUnits();
		var backend = new CpuFluidSolverBackend();
		long start = System.nanoTime();
		for (int step = 0; step < 600; step++) {
			if (step == 0) System.out.printf("Cut-cell obstacle flow: starting 600 steps, stocks=%d%n", domain.liquidCells.size());
			var candidate = backend.evaluate(new FluidStepRequest(step, domain.captureAuthoritativeState(),
					java.util.List.of(), false, 0, false));
			FluidDomain.StepStats stats = candidate.stats();
			domain = candidate.claimPreparedCpuDomain();
			check(Float.isFinite(stats.pressureResidual()), "cut-cell PCG residual became non-finite");
			check(Float.isFinite(domain.maximumPressure()), "cut-cell pressure became non-finite");
			check(stats.relativeVolumeError() < 2.0E-6F, "cut-cell step changed exact liquid volume");
			assertInventoryFitsOpenGeometry(domain);
			check(domain.totalMassUnits() == exactMass && domain.gasBoundary().auditClosed(), "Cut-cell flow lost mass or gas inventory");
			if ((step + 1) % 100 == 0) domain = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
			if (step < 5 || (step + 1) % 25 == 0)
				System.out.printf("Cut-cell obstacle flow: completed %d/600, stocks=%d, substeps=%d, elapsed=%.3fs%n",
						step + 1, domain.liquidCells.size(), stats.substeps(), (System.nanoTime() - start) / 1e9);
		}
		checkClose(domain.totalVolume(), exactVolume, 2.0E-4F, "long cut-cell liquid volume");
		check(domain.centerOfMassX() > 3.0F, "fluid did not pass the partial obstacle aperture");
	}

	private static void assertInventoryFitsOpenGeometry(FluidDomain domain) {
		for (var stock : domain.liquidCells.stocks()) {
			int cell=stock.key().cell();
			check((stock.key().supportMask() & domain.geometry.cutCells.mask(cell)) == 0,
					"Liquid inventory support overlaps a solid micro-cell");
			check(domain.liquidCells.exactCellVolume(cell).toDouble() <= domain.domainCollision.effectiveOpenCellVolume(cell) + 1e-12,
					"Liquid inventory exceeds joint capacity");
		}
	}

	private static FluidSolverConfig config() {
		return new FluidSolverConfig(0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F,
				160, 5.0E-5F, 2.0E-3F, 0.80F);
	}

	private static int index(int sizeX, int sizeY, int x, int y, int z) {
		return (z * sizeY + y) * sizeX + x;
	}

	private static void checkClose(float actual, float expected, float tolerance, String message) {
		check(Math.abs(actual - expected) <= tolerance, message + ": expected=" + expected + ", actual=" + actual);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
