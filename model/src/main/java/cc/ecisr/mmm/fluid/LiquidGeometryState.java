package cc.ecisr.mmm.fluid;

import java.util.List;
import java.math.BigInteger;

/** Immutable liquid interfaces and their marker quadrature, in a normalized dyadic root. */
public final class LiquidGeometryState {
	public static final class ControlVolume {
		private final int level, x, y, z;
		private final double[] vertices;
		private final BigInteger[] planes;
		private final int[] faceOffsets, faceVertices;

		public ControlVolume(int level, int x, int y, int z, double[] vertices, int[] faceOffsets, int[] faceVertices, BigInteger[] planes) {
			if (level < 0 || level > 30 || x < 0 || y < 0 || z < 0
					|| x >= 1L << level || y >= 1L << level || z >= 1L << level)
				throw new IllegalArgumentException("Invalid liquid control-volume address");
			if (vertices == null || vertices.length < 12 || vertices.length % 3 != 0 || faceOffsets == null
					|| faceOffsets.length < 5 || faceVertices == null || faceOffsets[0] != 0
					|| faceOffsets[faceOffsets.length - 1] != faceVertices.length || planes == null
					|| planes.length != 4 * (faceOffsets.length - 1))
				throw new IllegalArgumentException("Invalid liquid control-volume faces");
			int[] coordinate = {x, y, z}; double width = Math.scalb(1.0, -level);
			for (int i = 0; i < vertices.length; i++) {
				double lo = coordinate[i % 3] * width, hi = lo + width;
				if (!Double.isFinite(vertices[i]) || vertices[i] < lo || vertices[i] > hi)
					throw new IllegalArgumentException("Liquid vertex leaves its control volume");
			}
			for (int f = 1; f < faceOffsets.length; f++)
				if (faceOffsets[f] < 0 || faceOffsets[f] - faceOffsets[f - 1] < 3 || faceOffsets[f] > faceVertices.length)
					throw new IllegalArgumentException("Liquid face has invalid vertex support");
			for (int vertex : faceVertices) if (vertex < 0 || vertex >= vertices.length / 3)
				throw new IllegalArgumentException("Liquid face refers to an absent vertex");
			for (int f = 0; f < planes.length; f += 4) {
				BigInteger divisor = BigInteger.ZERO; boolean normal = false;
				for (int a = 0; a < 4; a++) {
					if (planes[f + a] == null) throw new IllegalArgumentException("Missing exact liquid plane coefficient");
					divisor = divisor.gcd(planes[f + a].abs()); normal |= a < 3 && planes[f + a].signum() != 0;
				}
				if (!normal || !divisor.equals(BigInteger.ONE))
					throw new IllegalArgumentException("Invalid liquid supporting plane");
			}
			this.level = level; this.x = x; this.y = y; this.z = z;
			this.vertices = vertices.clone(); this.faceOffsets = faceOffsets.clone(); this.faceVertices = faceVertices.clone();
			this.planes = planes.clone();
		}
		public int level() { return level; }
		public int x() { return x; }
		public int y() { return y; }
		public int z() { return z; }
		public double[] copyVertices() { return vertices.clone(); }
		public int[] copyFaceOffsets() { return faceOffsets.clone(); }
		public int[] copyFaceVertices() { return faceVertices.clone(); }
		public BigInteger[] copyPlanes() { return planes.clone(); }
		public long cpuBytes() {
			long bytes = (long) vertices.length * Double.BYTES + (long) (faceOffsets.length + faceVertices.length) * Integer.BYTES;
			for (var coefficient : planes) bytes = Math.addExact(bytes, 32L + (coefficient.bitLength() + 7L) / 8);
			return bytes;
		}
	}
	private final double rootWidthMetres;
	private final List<ControlVolume> cells;
	private final int[] markerCells;

	public LiquidGeometryState(double rootWidthMetres, List<ControlVolume> cells, int[] markerCells) {
		if (!(rootWidthMetres > 0) || !Double.isFinite(rootWidthMetres) || cells == null || markerCells == null)
			throw new IllegalArgumentException("Invalid liquid geometry state");
		this.cells = List.copyOf(cells); this.markerCells = markerCells.clone();
		for (int c = 1; c < cells.size(); c++) if (compare(cells.get(c - 1), cells.get(c)) >= 0)
			throw new IllegalArgumentException("Liquid geometry fragments must be unique and canonical");
		boolean[] supported = new boolean[cells.size()];
		for (int cell : markerCells) {
			if (cell < 0 || cell >= cells.size()) throw new IllegalArgumentException("Liquid quadrature has no geometry");
			supported[cell] = true;
		}
		for (boolean present : supported) if (!present) throw new IllegalArgumentException("Liquid geometry has no inventory quadrature");
		this.rootWidthMetres = rootWidthMetres;
	}
	public double rootWidthMetres() { return rootWidthMetres; }
	public List<ControlVolume> cells() { return cells; }
	public int markerCount() { return markerCells.length; }
	public int[] copyMarkerCells() { return markerCells.clone(); }
	public long cpuBytes() {
		long bytes = (long) markerCells.length * Integer.BYTES;
		for (var cell : cells) bytes = Math.addExact(bytes, cell.cpuBytes());
		return bytes;
	}
	public static int compare(ControlVolume a, ControlVolume b) {
		int order = Integer.compare(a.level, b.level);
		if (order == 0) order = Integer.compare(a.x, b.x);
		if (order == 0) order = Integer.compare(a.y, b.y);
		if (order == 0) order = Integer.compare(a.z, b.z);
		if (order == 0) order = java.util.Arrays.compare(a.vertices, b.vertices);
		if (order == 0) order = java.util.Arrays.compare(a.faceOffsets, b.faceOffsets);
		if (order == 0) order = java.util.Arrays.compare(a.faceVertices, b.faceVertices);
		if (order == 0) order = java.util.Arrays.compare(a.planes, b.planes);
		return order;
	}
}
