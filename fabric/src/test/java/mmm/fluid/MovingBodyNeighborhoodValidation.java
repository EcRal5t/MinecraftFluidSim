package mmm.fluid;

import mmm.world.MovingBodyFluidCoupling;
import java.util.ArrayList;
import java.util.Arrays;

/** Indexed batches retain full-scan floating-point order, including overlapping body supports. */
public final class MovingBodyNeighborhoodValidation {
	public static void main(String[] args) {
		var config = new FluidSolverConfig(0.5F, 1000, 0, 0.025F, 200, 1e-5F, 0.4F, 2e-3F, 0.8F);
		var reference = new FluidDomain(10, 6, 8, config, 4096);
		var indexed = new FluidDomain(10, 6, 8, config, 4096);
		for (int z = 0; z < 8; z++) for (int y = 0; y < 6; y++) for (int x = 0; x < 10; x++) {
			reference.setVolume(x, y, z, 0.75F); indexed.setVolume(x, y, z, 0.75F);
		}
		var requests = new ArrayList<MovingBodyFluidCoupling.Request>();
		for (int index = 0; index < 256; index++) requests.add(new MovingBodyFluidCoupling.Request(
				(index % 17 - 3) * 0.4F, (index % 11 - 2) * 0.4F, (index % 13 - 2) * 0.4F,
				0.2 * (index % 3), -0.1, 0.15, 0.025));
		for (int round = 0; round < 2; round++) {
			var expected = MovingBodyFluidCoupling.Result.NONE;
			for (var request : requests) expected = expected.plus(reference.applyMovingBodyCoupling(request));
			var actual = indexed.applyMovingBodyCouplings(requests);
			if (!expected.equals(actual) || !Arrays.equals(reference.exportParticleState().copyParticleVelocities(),
					indexed.exportParticleState().copyParticleVelocities())) {
				throw new AssertionError("Indexed moving-body batch differs from ordered full scans");
			}
			reference.setVolume(1, 1, 1, 0); indexed.setVolume(1, 1, 1, 0);
			reference.setVolume(2, 2, 2, 1); indexed.setVolume(2, 2, 2, 1);
		}
		System.out.println("Moving-body neighborhood validation passed");
	}
}
