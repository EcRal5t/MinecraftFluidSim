package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.granular.GranularCouplingFields;
import java.util.Arrays;

/** Derives completely filled closed components from exact liquid inventory and available volume. */
final class ClosedLiquidVolumeConstraint {
	private final int nx, ny, nz;
	private final float h;
	private final FlipCutCellGeometry geometry;
	private final int[] labels, ordered, starts, ends, markerHeads;
	private final byte[] masks;
	private final float[] capacities;
	private final double[] volumes;
	private int[] nextMarker = new int[0];
	private final long[] bins = new long[255];
	private int components;
	private boolean prepared;

	ClosedLiquidVolumeConstraint(int nx, int ny, int nz, float h, FlipCutCellGeometry geometry) {
		this.nx = nx; this.ny = ny; this.nz = nz; this.h = h; this.geometry = geometry;
		int count = nx * ny * nz;
		labels = new int[count]; ordered = new int[count]; starts = new int[count]; ends = new int[count]; markerHeads = new int[count];
		masks = new byte[count]; capacities = new float[count]; volumes = new double[count];
	}

	void prepare() {
		boolean changed = !prepared;
		for (int cell = 0; cell < labels.length; cell++) changed |= Byte.toUnsignedInt(masks[cell]) != geometry.mask(cell);
		if (!changed) return;
		Arrays.fill(labels, -1); components = 0; int appended = 0;
		for (int cell = 0; cell < labels.length; cell++) masks[cell] = (byte) geometry.mask(cell);
		for (int seed = 0; seed < labels.length; seed++) {
			if (labels[seed] >= 0 || geometry.fullySolid(seed)) continue;
			starts[components] = appended; ordered[appended++] = seed; labels[seed] = components;
			for (int head = starts[components]; head < appended; head++) {
				int cell = ordered[head], x = cell % nx, y = cell / nx % ny, z = cell / (nx * ny);
				if (x > 0 && geometry.xFaceOpen(x, y, z) > 0) appended = visit(cell - 1, appended);
				if (x + 1 < nx && geometry.xFaceOpen(x + 1, y, z) > 0) appended = visit(cell + 1, appended);
				if (y > 0 && geometry.yFaceOpen(x, y, z) > 0) appended = visit(cell - nx, appended);
				if (y + 1 < ny && geometry.yFaceOpen(x, y + 1, z) > 0) appended = visit(cell + nx, appended);
				if (z > 0 && geometry.zFaceOpen(x, y, z) > 0) appended = visit(cell - nx * ny, appended);
				if (z + 1 < nz && geometry.zFaceOpen(x, y, z + 1) > 0) appended = visit(cell + nx * ny, appended);
			}
			ends[components++] = appended;
		}
		prepared = true;
	}

	private int visit(int cell, int appended) {
		if (labels[cell] >= 0 || geometry.fullySolid(cell)) return appended;
		labels[cell] = components; ordered[appended++] = cell; return appended;
	}

	int component(int cell) { if (!prepared) prepare(); return labels[cell]; }

	void apply(double[] pressureVolume, LiquidParticleStore particles, GranularCouplingFields granular) {
		prepare(); Arrays.fill(volumes, 0, components, 0); Arrays.fill(markerHeads, 0, components, -1);
		if (nextMarker.length < particles.count()) nextMarker = new int[particles.count()];
		for (int p = 0; p < particles.count(); p++) {
			int x = Math.clamp((int) Math.floor(particles.positionX(p) / h), 0, nx - 1);
			int y = Math.clamp((int) Math.floor(particles.positionY(p) / h), 0, ny - 1);
			int z = Math.clamp((int) Math.floor(particles.positionZ(p) / h), 0, nz - 1);
			int component = labels[x + nx * (y + ny * z)];
			if (component < 0) throw new IllegalStateException("Liquid marker is inside a closed solid cell");
			volumes[component] += particles.volume(p); nextMarker[p] = markerHeads[component]; markerHeads[component] = p;
		}
		for (int component = 0; component < components; component++) {
			double capacity = 0;
			for (int index = starts[component]; index < ends[component]; index++) {
				int cell = ordered[index]; float open = geometry.openCellVolume(cell);
				capacities[cell] = granular == null ? open : granular.effectiveOpenCellVolume(cell, open);
				capacity += capacities[cell];
			}
			// Screen with a reduction-roundoff bound; only the exact exponent sum can prove equality.
			double roundoff = 4 * Math.ulp(Math.max(capacity, volumes[component])) * (particles.count() + ends[component] - starts[component]);
			if (!(capacity > 0) || Math.abs(capacity - volumes[component]) > roundoff) continue;
			Arrays.fill(bins, 0);
			for (int index = starts[component]; index < ends[component]; index++) add(capacities[ordered[index]], -1);
			for (int p = markerHeads[component]; p >= 0; p = nextMarker[p]) add(particles.volume(p), 1);
			if (!exactZero()) continue;
			for (int index = starts[component]; index < ends[component]; index++) {
				int cell = ordered[index]; pressureVolume[cell] = capacities[cell];
			}
		}
	}

	private void add(float value, int sign) {
		int bits = Float.floatToRawIntBits(value), exponent = bits >>> 23 & 255;
		int mantissa = bits & 0x7fffff;
		if (exponent != 0) mantissa |= 0x800000;
		bins[Math.max(1, exponent)] += (long) sign * mantissa;
	}

	private boolean exactZero() {
		long carry = 0;
		for (int exponent = 1; exponent < bins.length; exponent++) {
			carry += bins[exponent]; if ((carry & 1) != 0) return false; carry >>= 1;
		}
		return carry == 0;
	}
}
