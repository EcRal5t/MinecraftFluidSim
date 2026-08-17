package com.example.fluid;

import java.util.Locale;

/** Simple local benchmark for sizing active simulation domains. */
public final class FluidDomainBenchmark {
	private FluidDomainBenchmark() { }

	public static void main(String[] args) {
		int side = args.length > 0 ? Integer.parseInt(args[0]) : 64;
		int steps = args.length > 1 ? Integer.parseInt(args[1]) : 5;
		int warmupSteps = args.length > 2 ? Integer.parseInt(args[2]) : 3;
		FluidSolverConfig config = FluidSolverConfig.prototypeDefaults();
		FluidDomain domain = new FluidDomain(side, side, side, config);

		// A half-width column gives the pressure projection a free surface and a
		// horizontal direction in which to collapse.
		for (int z = 0; z < side; z++) {
			for (int y = 0; y < side * 3 / 4; y++) {
				for (int x = 0; x < side / 2; x++) domain.setVolume(x, y, z, 1.0F);
			}
		}

		for (int i = 0; i < warmupSteps; i++) domain.step();
		float initialVolume = domain.totalVolume();
		long start = System.nanoTime();
		FluidDomain.StepStats latest = null;
		for (int i = 0; i < steps; i++) latest = domain.step();
		double elapsedMilliseconds = (System.nanoTime() - start) / 1_000_000.0;
		float relativeVolumeError = Math.abs(domain.totalVolume() - initialVolume) / initialVolume;

		System.out.printf(Locale.ROOT,
				"domain=%d^3 cells=%,d warmup=%d steps=%d avg=%.2f ms substeps=%d pcgIterations=%d residual=%.4g volumeError=%.4g%n",
				side,
				side * side * side,
				warmupSteps,
				steps,
				elapsedMilliseconds / steps,
				latest.substeps(),
				latest.pressureIterations(),
				latest.pressureResidual(),
				relativeVolumeError);
	}
}
