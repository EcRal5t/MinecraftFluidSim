package cc.ecisr.mmm.fluid;

/** PLIC face-sweep preferences for distinct immiscible phase inventories. */
final class PhaseVolumeTransportGeometry {
	private final FluidDomain domain;
	private final double[][] volumes;
	private final float[][] nx, ny, nz;
	private final double[][] offsets;
	private final LiquidFluxGeometry geometry;

	PhaseVolumeTransportGeometry(FluidDomain domain, double[][] volumes) {
		this.domain = domain; this.volumes = volumes;
		geometry = new LiquidFluxGeometry(domain);
		nx = new float[volumes.length][domain.cellCount]; ny = new float[volumes.length][domain.cellCount];
		nz = new float[volumes.length][domain.cellCount]; offsets = new double[volumes.length][domain.cellCount];
		for (int phase = 0; phase < volumes.length; phase++) for (int cell = 0; cell < domain.cellCount; cell++) {
			double fraction = volumes[phase][cell];
			if (!(fraction > 0 && fraction < geometry.capacity(cell))) continue;
			int x = cell % domain.sizeX, y = cell / domain.sizeX % domain.sizeY, z = cell / (domain.sizeX * domain.sizeY);
			float a = (float) (sample(phase, x - 1, y, z, cell) - sample(phase, x + 1, y, z, cell));
			float b = (float) (sample(phase, x, y - 1, z, cell) - sample(phase, x, y + 1, z, cell));
			float c = (float) (sample(phase, x, y, z - 1, cell) - sample(phase, x, y, z + 1, cell));
			double length = Math.sqrt((double) a * a + (double) b * b + (double) c * c);
			if (!(length > 0)) continue;
			nx[phase][cell] = a = (float) (a / length); ny[phase][cell] = b = (float) (b / length); nz[phase][cell] = c = (float) (c / length);
			offsets[phase][cell] = geometry.offset(cell, a, b, c, fraction);
		}
	}

	double volume(int phase, int cell) { return volumes[phase][cell]; }
	double swept(int phase, int cell, int neighbor, int axis, double width, boolean positive) {
		double fraction = volumes[phase][cell];
		if (!(fraction > 0)) return 0;
		if (fraction >= geometry.capacity(cell)) return width * geometry.aperture(positive ? cell : neighbor, positive ? neighbor : cell, axis);
		float a = nx[phase][cell], b = ny[phase][cell], c = nz[phase][cell];
		double off = offsets[phase][cell];
		if (a == 0 && b == 0 && c == 0) return width * fraction / geometry.capacity(cell)
				* geometry.aperture(positive ? cell : neighbor, positive ? neighbor : cell, axis);
		return geometry.swept(cell, neighbor, axis, width, positive, a, b, c, off);
	}

	void sweptMoments(int phase, int cell, int neighbor, int axis, double width, boolean positive,
	                  int donorSupport, int receiverSupport, double[] result) {
		sweptAffineMoments(phase, cell, neighbor, axis, positive, Math.copySign(width, positive ? 1 : -1),
				0, 0, 1, donorSupport, receiverSupport, result);
	}

	void sweptAffineMoments(int phase, int cell, int neighbor, int axis, boolean positive,
	                        double intercept, double tangentSlope, double otherSlope, double dtOverH,
	                        int donorSupport, int receiverSupport, double[] result) {
		double fraction = volumes[phase][cell];
		if (!(fraction > 0)) { java.util.Arrays.fill(result, 0, 4, 0); return; }
		double a = nx[phase][cell], b = ny[phase][cell], c = nz[phase][cell], off = offsets[phase][cell];
		boolean uniform = a == 0 && b == 0 && c == 0;
		boolean full = fraction >= geometry.capacity(cell);
		LiquidAffineSweepGeometry.sweptPlaneMoments(domain, cell, neighbor, axis, positive, intercept, tangentSlope,
				otherSlope, dtOverH, donorSupport, receiverSupport, full || uniform ? 0 : a,
				full || uniform ? 0 : b, full || uniform ? 0 : c, full || uniform ? 1 : off, result);
		if (uniform && !full) for (int i = 0; i < 4; i++) result[i] *= fraction / geometry.capacity(cell);
	}

	private double sample(int phase, int x, int y, int z, int fallback) {
		if (x < 0 || x >= domain.sizeX || y < 0 || y >= domain.sizeY || z < 0 || z >= domain.sizeZ) return volumes[phase][fallback];
		int cell = domain.cellIndex(x, y, z); return domain.geometry.solid[cell] ? volumes[phase][fallback] : volumes[phase][cell];
	}
}
