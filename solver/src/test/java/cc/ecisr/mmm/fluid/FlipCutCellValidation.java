package cc.ecisr.mmm.fluid;

/** Deterministic M2.5 gates for runtime cut-cell volume, apertures, persistence fallback and flow. */
public final class FlipCutCellValidation {
	private static final int LOWER_HALF = 0x33;
	private static final int NEGATIVE_X_HALF = 0x55;

	private FlipCutCellValidation() { }

	public static void main(String[] args) {
		solverConfigRejectsNonfiniteCoefficients();
		LiquidViscosityValidation.validate();
		volumeAndSharedFaceFractionsAreConsistent();
		wallAreasFollowMicroGeometry();
		bodyOverlapExcludesOccupiedMicroCells();
		cutCellSweepStopsOnlyTheContactNormal();
		straightSweepResolvesDiagonalContacts();
		simultaneousContactsRespectSpatialSymmetry();
		contactPositionsFollowFloatMicroGeometry();
		exactMarkerDisplacementUsesMicroOccupancy();
		cutCellCapacityDisplacesMergedMarkerVolume();
		checkpointPreservesCutCellGeometry();
		partialObstacleFlowRemainsFiniteAndConservative();
		System.out.println("FlipFluid cut-cell validation passed");
	}

	private static void solverConfigRejectsNonfiniteCoefficients() {
		for (float invalid : new float[] {Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY}) {
			for (int field = 0; field < 5; field++) {
				float[] values = {.5F, 1000, 9.81F, .05F, 5E-5F}; values[field] = invalid;
				boolean rejected = false;
				try {
					new FluidSolverConfig(values[0], values[1], values[2], values[3],
							160, values[4], .4F, .002F, .8F);
				} catch (IllegalArgumentException expected) { rejected = true; }
				check(rejected, "solver config accepted nonfinite coefficient at field " + field + ": " + invalid);
			}
		}
		new FluidSolverConfig(.5F, 1000, 0, .05F, 160, 5E-5F, 1, .002F, 0);
		new FluidSolverConfig(.5F, 1000, 9.81F, .05F, 160, 5E-5F, .4F, .002F, 1);
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

	private static void cutCellSweepStopsOnlyTheContactNormal() {
		int size = 3;
		FlipCutCellGeometry geometry = new FlipCutCellGeometry(size, size, size);
		geometry.replaceMask(index(size, size, 1, 1, 1), NEGATIVE_X_HALF);
		FlipParticleCollision collision = new FlipParticleCollision(size, size, size, 1.0F, geometry);
		float[] x = {1.73F}, y = {1.50F}, z = {1.50F};
		float[] velocityX = {-10.0F}, velocityY = {0.0F}, velocityZ = {2.0F};
		collision.advect(x, y, z, velocityX, velocityY, velocityZ,
				velocityX, velocityY, velocityZ, 1, 0.10F);
		check(x[0] >= 1.5F, "swept marker crossed a cut-cell wall");
		checkClose(x[0], 1.5F, Math.ulp(1.5F), "cut-cell contact left a step-sized wall gap");
		checkClose(velocityX[0], 0.0F, 0.0F, "cut-cell contact normal velocity");
		checkClose(z[0], 1.70F, 1.0E-6F, "cut-cell tangential displacement");
		checkClose(velocityZ[0], 2.0F, 0.0F, "cut-cell tangential velocity");
		x[0] = 1.75F; y[0] = 1.25F; z[0] = 1.25F;
		velocityX[0] = -.5F; velocityY[0] = .5F; velocityZ[0] = 0;
		collision.advect(x, y, z, velocityX, velocityY, velocityZ,
				velocityX, velocityY, velocityZ, 1, 1);
		checkClose(x[0], 1.5F, Math.ulp(1.5F), "simultaneous tangent grid crossing contact");
		checkClose(y[0], 1.75F, 0, "tangent micro-grid crossing became a collision normal");
		checkClose(velocityY[0], .5F, 0, "tangent micro-grid crossing removed tangent velocity");
	}

	private static void straightSweepResolvesDiagonalContacts() {
		int size = 3;
		FlipCutCellGeometry geometry = new FlipCutCellGeometry(size, size, size);
		geometry.replaceMask(index(size, size, 1, 1, 1), 1);
		FlipParticleCollision collision = new FlipParticleCollision(size, size, size, 1, geometry);
		float[] x = {.75F}, y = {.95F}, z = {1.25F};
		float[] vx = {1}, vy = {.4F}, vz = {0};
		collision.advect(x, y, z, vx, vy, vz, vx, vy, vz, 1, 1);
		checkClose(x[0], 1, Math.ulp(1F), "diagonal segment bypassed a solid micro box");
		check(x[0] < 1, "positive contact rounded into the solid micro box");
		checkClose(y[0], 1.35F, Math.ulp(1.35F), "diagonal contact lost remaining tangent motion");
		checkClose(vx[0], 0, 0, "diagonal contact normal velocity");
		checkClose(vy[0], .4F, 0, "diagonal contact tangent velocity");

		// This straight path clears the obstacle before entering its X interval.
		x[0] = .75F; y[0] = 1.4F; vx[0] = vy[0] = 1;
		collision.advect(x, y, z, vx, vy, vz, vx, vy, vz, 1, 1);
		checkClose(x[0], 1.75F, 0, "axis order introduced a false diagonal contact");
		checkClose(y[0], 2.4F, 0, "clear diagonal segment changed tangent motion");

		// A simultaneous corner entry constrains both normals and retains the third axis.
		x[0] = y[0] = .75F; z[0] = 1.25F; vx[0] = vy[0] = .5F; vz[0] = .1F;
		collision.advect(x, y, z, vx, vy, vz, vx, vy, vz, 1, 1);
		check(x[0] < 1 && y[0] < 1, "corner contact entered solid geometry");
		checkClose(x[0], 1, Math.ulp(1F), "corner X contact gap");
		checkClose(y[0], 1, Math.ulp(1F), "corner Y contact gap");
		checkClose(z[0], 1.35F, Math.ulp(1.35F), "corner contact lost tangent displacement");
		checkClose(vx[0], 0, 0, "corner X normal velocity");
		checkClose(vy[0], 0, 0, "corner Y normal velocity");
		checkClose(vz[0], .1F, 0, "corner tangent velocity");
	}

	private static void contactPositionsFollowFloatMicroGeometry() {
		int size = 4;
		for (float h : new float[] {.3F, .1F, .07F, .5F}) {
			FlipCutCellGeometry geometry = new FlipCutCellGeometry(size, size, size);
			geometry.replaceMask(index(size, size, 1, 1, 1), NEGATIVE_X_HALF);
			FlipParticleCollision collision = new FlipParticleCollision(size, size, size, h, geometry);
			float[] x = {1.73F * h}, y = {1.25F * h}, z = {1.25F * h};
			float[] vx = {-h}, vy = {0}, vz = {0};
			collision.advect(x, y, z, vx, vy, vz, vx, vy, vz, 1, 1);
			checkClose(x[0], 1.5F * h, 3 * Math.ulp(1.5F * h), "nonbinary grid negative contact");
			check(!collision.centerInSolid(x[0], y[0], z[0]), "negative contact float entered solid");
			x[0] = .73F * h; vx[0] = h;
			collision.advect(x, y, z, vx, vy, vz, vx, vy, vz, 1, 1);
			checkClose(x[0], h, 3 * Math.ulp(h), "nonbinary grid positive contact");
			check(!collision.centerInSolid(x[0], y[0], z[0]), "positive contact float entered solid");
			x[0] = 3.73F * h; vx[0] = h;
			collision.advect(x, y, z, vx, vy, vz, vx, vy, vz, 1, 1);
			check(x[0] < size * h, "domain contact rounded outside the grid");
			checkClose(x[0], size * h, 3 * Math.ulp(size * h), "domain wall contact gap");
			x[0] = .23F * h; vx[0] = -h;
			collision.advect(x, y, z, vx, vy, vz, vx, vy, vz, 1, 1);
			checkClose(x[0], 0, 0, "lower domain wall contact");
		}
	}

	private static void simultaneousContactsRespectSpatialSymmetry() {
		int[][] permutations = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};
		for (int mask = 0; mask < 256; mask += 2) {
			float[][] original = cornerMotion(mask, permutations[0], 0);
			if (mask == 168) {
				checkClose(original[1][0], 0, 0, "union corner X normal");
				checkClose(original[1][1], .5F, 0, "union corner Y tangent");
				checkClose(original[1][2], .5F, 0, "union corner Z tangent");
			}
			for (int[] permutation : permutations) for (int reflection = 0; reflection < 8; reflection++) {
				float[][] transformed = cornerMotion(mask, permutation, reflection);
				for (int axis = 0; axis < 3; axis++) {
					boolean reflected = (reflection & 1 << axis) != 0;
					float position = reflected ? 3 - transformed[0][axis] : transformed[0][axis];
					float velocity = reflected ? -transformed[1][axis] : transformed[1][axis];
					checkClose(position, original[0][permutation[axis]], 8 * Math.ulp(position),
							"corner position depends on axis order: mask=" + mask);
					checkClose(velocity, original[1][permutation[axis]], 0,
							"corner velocity depends on axis order: mask=" + mask);
				}
			}
		}
	}

	private static float[][] cornerMotion(int mask, int[] permutation, int reflection) {
		FlipCutCellGeometry geometry = new FlipCutCellGeometry(3, 3, 3);
		for (int bit = 0; bit < 8; bit++) if ((mask & 1 << bit) != 0) {
			int[] original = {1 + (bit & 1), 1 + (bit >> 1 & 1), 1 + (bit >> 2 & 1)};
			int[] micro = new int[3];
			for (int axis = 0; axis < 3; axis++) micro[axis] = (reflection & 1 << axis) == 0
					? original[permutation[axis]] : 5 - original[permutation[axis]];
			int cell = index(3, 3, micro[0] / 2, micro[1] / 2, micro[2] / 2);
			geometry.replaceMask(cell, geometry.mask(cell) | 1 << ((micro[0] & 1) + 2 * ((micro[1] & 1) + 2 * (micro[2] & 1))));
		}
		float[][] position = new float[3][1], velocity = new float[3][1];
		for (int axis = 0; axis < 3; axis++) {
			position[axis][0] = (reflection & 1 << axis) == 0 ? .75F : 2.25F;
			velocity[axis][0] = (reflection & 1 << axis) == 0 ? .5F : -.5F;
		}
		new FlipParticleCollision(3, 3, 3, 1, geometry).advect(
				position[0], position[1], position[2], velocity[0], velocity[1], velocity[2],
				velocity[0], velocity[1], velocity[2], 1, 1);
		return new float[][] {{position[0][0], position[1][0], position[2][0]},
				{velocity[0][0], velocity[1][0], velocity[2][0]}};
	}

	private static void exactMarkerDisplacementUsesMicroOccupancy() {
		FluidDomain domain = new FluidDomain(5, 5, 5, config());
		domain.setVolume(2, 2, 2, 1.0F);
		float before = domain.totalVolume();
		float displaced = domain.setSolidMicroMask(2, 2, 2, LOWER_HALF);
		checkClose(displaced, 0.5F, 1.0E-7F, "exact displaced marker volume");
		checkClose(domain.totalVolume(), before - displaced, 1.0E-7F, "remaining marker ledger");
		float restored = domain.addVolumeNear(2, 2, 2, displaced, 3);
		checkClose(restored, displaced, 1.0E-7F, "nearby exact restoration");
		checkClose(domain.totalVolume(), before, 1.0E-7F, "restored marker ledger");
	}

	private static void cutCellCapacityDisplacesMergedMarkerVolume() {
		FluidDomain domain = new FluidDomain(5, 5, 5, config());
		float coordinate = (2.0F + 0.75F) * domain.config().cellSize();
		domain.restoreParticleState(new FlipParticleState(
				5, 5, 5, domain.config().cellSize(), 1, 1.0F,
				new float[] {coordinate, coordinate, coordinate, 1.0F},
				new float[] {0.0F, 0.0F, 0.0F}, new byte[5 * 5 * 5]
		));
		float displaced = domain.setSolidMicroMask(2, 2, 2, LOWER_HALF);
		checkClose(displaced, 0.5F, 1.0E-7F,
				"cut-cell capacity did not displace excess merged-marker volume");
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
		FlipParticleState checkpoint = live.exportParticleState();
		int partial = index(4, 4, 1, 1, 1);
		int full = index(4, 4, 3, 3, 3);
		check(Byte.toUnsignedInt(checkpoint.copyCollisionMicroMasks()[partial]) == LOWER_HALF,
				"checkpoint lost partial cut-cell geometry");
		check(Byte.toUnsignedInt(checkpoint.copyCollisionMicroMasks()[full]) == 0xff,
				"fully solid checkpoint cell was lost");

		FluidDomain restored = new FluidDomain(4, 4, 4, config());
		restored.restoreParticleState(checkpoint);
		check(restored.solidMicroMask(1, 1, 1) == LOWER_HALF, "partial cut-cell geometry changed on restore");
		float displacedOnRefresh = restored.setSolidMicroMask(1, 1, 1, LOWER_HALF);
		checkClose(displacedOnRefresh, 0.0F, 0.0F,
				"unchanged world geometry displaced markers after checkpoint restore");
		checkClose(restored.totalVolume(), exactVolume, 0.0F, "restore/resample marker ledger");
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
		var backend = new CpuFluidSolverBackend();
		for (int step = 0; step < 600; step++) {
			var candidate = backend.evaluate(new FluidStepRequest(step, domain.captureAuthoritativeState(),
					java.util.List.of(), false, 0, false));
			FluidDomain.StepStats stats = candidate.stats();
			domain = candidate.claimPreparedCpuDomain();
			check(Float.isFinite(stats.pressureResidual()), "cut-cell PCG residual became non-finite");
			check(Float.isFinite(domain.maximumPressure()), "cut-cell pressure became non-finite");
			check(stats.relativeVolumeError() < 2.0E-6F, "cut-cell step changed exact marker volume");
			assertNoMarkerCenterInsideSolid(domain);
		}
		checkClose(domain.totalVolume(), exactVolume, 2.0E-4F, "long cut-cell marker ledger");
		check(domain.centerOfMassX() > 3.0F, "fluid did not pass the partial obstacle aperture");
	}

	private static void assertNoMarkerCenterInsideSolid(FluidDomain domain) {
		FlipParticleState state = domain.exportParticleState();
		float[] particles = state.copyPositionVolume();
		float h = domain.config().cellSize();
		for (int particle = 0; particle < state.particleCount(); particle++) {
			int base = particle * 4;
			int cellX = Math.min(domain.sizeX() - 1, (int) Math.floor(particles[base] / h));
			int cellY = Math.min(domain.sizeY() - 1, (int) Math.floor(particles[base + 1] / h));
			int cellZ = Math.min(domain.sizeZ() - 1, (int) Math.floor(particles[base + 2] / h));
			int microX = Math.min(1, (int) Math.floor((particles[base] / h - cellX) * 2.0F));
			int microY = Math.min(1, (int) Math.floor((particles[base + 1] / h - cellY) * 2.0F));
			int microZ = Math.min(1, (int) Math.floor((particles[base + 2] / h - cellZ) * 2.0F));
			int bit = 1 << (microX + 2 * (microY + 2 * microZ));
			check((domain.solidMicroMask(cellX, cellY, cellZ) & bit) == 0,
					"marker center penetrated runtime cut-cell geometry at particle " + particle);
		}
	}

	private static FluidSolverConfig config() {
		return new FluidSolverConfig(0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F,
				160, 5.0E-5F, 0.40F, 2.0E-3F, 0.80F);
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
