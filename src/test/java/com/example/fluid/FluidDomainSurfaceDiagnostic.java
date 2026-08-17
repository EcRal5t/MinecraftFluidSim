package com.example.fluid;

/** Prints volume-fraction diffusion metrics for the Minecraft-sized dam test. */
public final class FluidDomainSurfaceDiagnostic {
	private FluidDomainSurfaceDiagnostic() { }

	public static void main(String[] args) {
		float compression = args.length < 2 ? 0.80F : Float.parseFloat(args[1]);
		float epsilon = args.length < 3 ? 2.0E-3F : Float.parseFloat(args[2]);
		FluidSolverConfig config = new FluidSolverConfig(
				0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F,
				120, 1.0E-4F, 0.40F, epsilon, compression
		);
		FluidDomain domain = new FluidDomain(24, 16, 24, config);
		for (int z = 0; z < 24; z++) for (int y = 0; y < 4; y++) for (int x = 0; x < 24; x++) domain.setSolid(x, y, z, true);
		for (int z = 0; z < 24; z++) for (int y = 4; y < 12; y++) for (int x = 0; x < 9; x++) domain.setVolume(x, y, z, 1.0F);

		int steps = args.length == 0 ? 120 : Integer.parseInt(args[0]);
		print(domain, 0);
		for (int step = 1; step <= steps; step++) {
			domain.step();
			if (step % 20 == 0 || step == steps) print(domain, step);
		}
	}

	private static void print(FluidDomain domain, int step) {
		int wet = 0, visible = 0, dense = 0;
		float[] byHeight = new float[domain.sizeY()];
		for (int z = 0; z < domain.sizeZ(); z++) for (int y = 0; y < domain.sizeY(); y++) for (int x = 0; x < domain.sizeX(); x++) {
			float volume = domain.volume(x, y, z);
			byHeight[y] += volume;
			if (volume > domain.config().fluidEpsilon()) wet++;
			if (volume >= 8.0F / 255.0F) visible++;
			if (volume >= 0.9F) dense++;
		}
		System.out.printf("step=%d volume=%.2f wet=%d visible=%d dense=%d meanWet=%.3f byY=", step, domain.totalVolume(), wet, visible, dense, wet == 0 ? 0.0F : domain.totalVolume() / wet);
		for (float value : byHeight) System.out.printf(" %.1f", value);
		System.out.println();
	}
}
