package com.example.fluid;

/**
 * Dependency-free numerical validation. Run with Java assertions enabled or
 * call main directly; failures throw AssertionError in either case.
 */
public final class FluidDomainValidation {
	private FluidDomainValidation() { }

	public static void main(String[] args) {
		hydrostaticPressureIncreasesWithDepth();
		damBreakMovesAndConservesVolume();
		volumeEditingIsBounded();
		pointImmersionMatchesReconstructedSurface();
		waterReachesAClosedWallCorner();
		System.out.println("FluidDomain validation passed");
	}

	private static void hydrostaticPressureIncreasesWithDepth() {
		FluidSolverConfig config = new FluidSolverConfig(1.0F, 1_000.0F, 9.81F, 0.025F, 300, 1.0E-6F, 0.45F, 2.0E-3F, 0.80F);
		FluidDomain domain = new FluidDomain(5, 10, 5, config);
		for (int z = 0; z < 5; z++) for (int y = 0; y < 6; y++) for (int x = 0; x < 5; x++) domain.setVolume(x, y, z, 1.0F);

		FluidDomain.StepStats stats = domain.step();
		float previous = Float.POSITIVE_INFINITY;
		for (int y = 0; y < 6; y++) {
			float pressure = domain.pressure(2, y, 2);
			if (!(pressure > 0.0F)) throw new AssertionError("Hydrostatic pressure must be positive at y=" + y + ": " + pressure);
			if (y > 0 && !(pressure < previous)) throw new AssertionError("Pressure must decrease toward the surface");
			previous = pressure;
		}
		float bottom = domain.pressure(2, 0, 2);
		float top = domain.pressure(2, 5, 2);
		if (!(bottom > top * 4.0F)) throw new AssertionError("Water-column pressure gradient is too small: bottom=" + bottom + ", top=" + top);
		if (!(stats.pressureResidual() < 1.0F)) throw new AssertionError("Hydrostatic pressure solve did not converge: " + stats.pressureResidual());
	}

	private static void damBreakMovesAndConservesVolume() {
		FluidSolverConfig config = new FluidSolverConfig(1.0F, 1_000.0F, 9.81F, 0.025F, 250, 2.0E-5F, 0.40F, 2.0E-3F, 0.80F);
		FluidDomain domain = new FluidDomain(16, 8, 6, config);
		for (int z = 1; z < 5; z++) for (int y = 0; y < 5; y++) for (int x = 0; x < 5; x++) domain.setVolume(x, y, z, 1.0F);
		float initialVolume = domain.totalVolume();
		float initialCenter = domain.centerOfMassX();
		float worstRelativeError = 0.0F;

		for (int step = 0; step < 80; step++) {
			FluidDomain.StepStats stats = domain.step();
			worstRelativeError = Math.max(worstRelativeError, stats.relativeVolumeError());
		}

		float finalVolume = domain.totalVolume();
		float finalCenter = domain.centerOfMassX();
		float totalRelativeError = Math.abs(finalVolume - initialVolume) / initialVolume;
		if (!(finalCenter > initialCenter + 0.35F)) {
			throw new AssertionError("Dam-break front did not move far enough: initial=" + initialCenter + ", final=" + finalCenter);
		}
		if (!(totalRelativeError < 2.0E-5F && worstRelativeError < 2.0E-5F)) {
			throw new AssertionError("Volume was not conserved: total=" + totalRelativeError + ", per-step worst=" + worstRelativeError);
		}
	}

	private static void volumeEditingIsBounded() {
		FluidDomain domain = new FluidDomain(7, 7, 7, FluidSolverConfig.prototypeDefaults());
		domain.setSolid(3, 3, 3, true);
		float added = domain.addVolumeNear(3, 3, 3, 8.0F, 2);
		if (Math.abs(added - 8.0F) > 1.0E-5F || Math.abs(domain.totalVolume() - 8.0F) > 1.0E-5F) {
			throw new AssertionError("Nearby add did not insert the requested volume: " + added);
		}
		if (domain.volume(3, 3, 3) != 0.0F) throw new AssertionError("Volume editor filled a solid cell");

		float removed = domain.removeVolumeNear(3, 3, 3, 3.25F, 2);
		if (Math.abs(removed - 3.25F) > 1.0E-5F || Math.abs(domain.totalVolume() - 4.75F) > 1.0E-5F) {
			throw new AssertionError("Nearby drain did not remove the requested volume: " + removed);
		}

		float capacityLimited = domain.addVolumeNear(3, 3, 3, 1_000.0F, 1);
		if (!(capacityLimited > 0.0F && capacityLimited < 1_000.0F)) {
			throw new AssertionError("Nearby add did not report a capacity-limited edit");
		}
	}

	private static void pointImmersionMatchesReconstructedSurface() {
		FluidDomain domain = new FluidDomain(4, 4, 4, FluidSolverConfig.prototypeDefaults());
		domain.setVolume(1, 1, 1, 0.50F);
		if (!domain.containsFluidAt(1.50F, 1.20F, 1.50F)) {
			throw new AssertionError("Point below a half-filled cell surface was reported dry");
		}
		if (domain.containsFluidAt(1.50F, 1.75F, 1.50F)) {
			throw new AssertionError("Point above a half-filled cell surface was reported submerged");
		}
		domain.setSolid(1, 1, 1, true);
		if (domain.containsFluidAt(1.50F, 1.20F, 1.50F)) {
			throw new AssertionError("Solid cell was reported as fluid");
		}
	}

	private static void waterReachesAClosedWallCorner() {
		FluidSolverConfig config = new FluidSolverConfig(1.0F, 1_000.0F, 9.81F, 0.025F, 250, 2.0E-5F, 0.40F, 2.0E-3F, 0.80F);
		FluidDomain domain = new FluidDomain(8, 6, 8, config);
		for (int z = 0; z < 8; z++) for (int x = 0; x < 8; x++) domain.setSolid(x, 0, z, true);
		for (int z = 0; z < 8; z++) for (int y = 0; y < 6; y++) domain.setSolid(0, y, z, true);
		for (int x = 0; x < 8; x++) for (int y = 0; y < 6; y++) domain.setSolid(x, y, 0, true);
		for (int z = 4; z <= 6; z++) for (int y = 1; y <= 4; y++) for (int x = 4; x <= 6; x++) domain.setVolume(x, y, z, 1.0F);

		for (int step = 0; step < 160; step++) domain.step();
		float cornerColumn = 0.0F;
		for (int y = 1; y < 6; y++) cornerColumn += domain.volume(1, y, 1);
		if (!(cornerColumn > 0.90F)) {
			throw new AssertionError("Water failed to wet the closed wall corner: " + cornerColumn);
		}
	}
}
