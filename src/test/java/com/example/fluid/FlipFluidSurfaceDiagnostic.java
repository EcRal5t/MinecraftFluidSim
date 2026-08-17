package com.example.fluid;

/** Reports particle-density sampling quality for the Minecraft dam-break domain. */
public final class FlipFluidSurfaceDiagnostic {
	private FlipFluidSurfaceDiagnostic() { }

	public static void main(String[] args) {
		FluidSolverConfig config = new FluidSolverConfig(
				0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F,
				120, 1.0E-4F, 0.40F, 2.0E-3F, 0.80F
		);
		FlipFluidDomain domain = new FlipFluidDomain(24, 16, 24, config);
		for (int z = 0; z < 24; z++) for (int y = 0; y < 4; y++) for (int x = 0; x < 24; x++) domain.setSolid(x, y, z, true);
		for (int z = 0; z < 24; z++) for (int y = 4; y < 12; y++) for (int x = 0; x < 9; x++) domain.setVolume(x, y, z, 1.0F);
		print(domain, 0);
		for (int step = 1; step <= 160; step++) {
			domain.step();
			if (step == 20 || step == 80 || step == 160) print(domain, step);
		}
	}

	private static void print(FlipFluidDomain domain, int step) {
		int[] bins = new int[6];
		int enclosedLow = 0;
		for (int z = 0; z < domain.sizeZ(); z++) for (int y = 0; y < domain.sizeY(); y++) for (int x = 0; x < domain.sizeX(); x++) {
			if (domain.isSolid(x, y, z)) continue;
			float value = domain.volume(x, y, z);
			int bin = value < 0.01F ? 0 : value < 0.125F ? 1 : value < 0.25F ? 2 : value < 0.50F ? 3 : value < 0.90F ? 4 : 5;
			bins[bin]++;
			if (value < 0.50F && x > 0 && x + 1 < domain.sizeX() && y > 0 && y + 1 < domain.sizeY() && z > 0 && z + 1 < domain.sizeZ()
					&& domain.volume(x - 1, y, z) >= 0.50F && domain.volume(x + 1, y, z) >= 0.50F
					&& domain.volume(x, y - 1, z) >= 0.50F && domain.volume(x, y + 1, z) >= 0.50F
					&& domain.volume(x, y, z - 1) >= 0.50F && domain.volume(x, y, z + 1) >= 0.50F) enclosedLow++;
		}
		System.out.printf("step %d particles %d volume %.2f bins %s enclosed<.5 %d%n",
				step, domain.particleCount(), domain.totalVolume(), java.util.Arrays.toString(bins), enclosedLow);
	}
}
