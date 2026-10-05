package cc.ecisr.mmm.geometry;

import java.math.BigInteger;

/** Immutable exact convex support and its mean velocity in a normalized dyadic root. */
public final class ResolvedControlVolume {
	private final int level, x, y, z;
	private final BigInteger[] vertices;
	private final BigInteger[] planes;
	private final int[] faceOffsets, faceVertices;
	private final double[] velocity;

	public ResolvedControlVolume(int level, int x, int y, int z, BigInteger[] vertices, int[] faceOffsets, int[] faceVertices, BigInteger[] planes) {
		this(level, x, y, z, vertices, faceOffsets, faceVertices, planes, new double[3]);
	}
	public ResolvedControlVolume(int level, int x, int y, int z, BigInteger[] vertices, int[] faceOffsets, int[] faceVertices, BigInteger[] planes, double[] velocity) {
		if (velocity == null || velocity.length != 3) throw new IllegalArgumentException("Incomplete resolved control-volume velocity");
		for (double component : velocity) if (!Double.isFinite(component)) throw new IllegalArgumentException("Non-finite resolved control-volume velocity");
		if (level < 0 || level > 30 || x < 0 || y < 0 || z < 0
				|| x >= 1L << level || y >= 1L << level || z >= 1L << level)
			throw new IllegalArgumentException("Invalid resolved control-volume address");
		if (vertices == null || vertices.length < 16 || vertices.length % 4 != 0 || faceOffsets == null
				|| faceOffsets.length < 5 || faceVertices == null || faceOffsets[0] != 0
				|| faceOffsets[faceOffsets.length - 1] != faceVertices.length || planes == null
				|| planes.length != 4 * (faceOffsets.length - 1))
			throw new IllegalArgumentException("Invalid resolved control-volume faces");
		int[] coordinate = {x, y, z};
		for (int i = 0; i < vertices.length; i += 4) {
			for (int a = 0; a < 4; a++) if (vertices[i + a] == null) throw new IllegalArgumentException("Missing exact resolved vertex coordinate");
			var denominator = vertices[i + 3]; var divisor = denominator.abs();
			if (denominator.signum() <= 0) throw new IllegalArgumentException("Invalid resolved vertex denominator");
			for (int a = 0; a < 3; a++) {
				divisor = divisor.gcd(vertices[i + a].abs()); var scaled = vertices[i + a].shiftLeft(level);
				if (scaled.compareTo(denominator.multiply(BigInteger.valueOf(coordinate[a]))) < 0
						|| scaled.compareTo(denominator.multiply(BigInteger.valueOf((long) coordinate[a] + 1))) > 0)
					throw new IllegalArgumentException("Liquid vertex leaves its control volume");
			}
			if (!divisor.equals(BigInteger.ONE)) throw new IllegalArgumentException("Liquid vertex coordinates are not canonical");
		}
		for (int f = 1; f < faceOffsets.length; f++)
			if (faceOffsets[f] < 0 || faceOffsets[f] - faceOffsets[f - 1] < 3 || faceOffsets[f] > faceVertices.length)
				throw new IllegalArgumentException("Liquid face has invalid vertex support");
		for (int vertex : faceVertices) if (vertex < 0 || vertex >= vertices.length / 4)
			throw new IllegalArgumentException("Liquid face refers to an absent vertex");
		for (int f = 0; f < planes.length; f += 4) {
			BigInteger divisor = BigInteger.ZERO; boolean normal = false;
			for (int a = 0; a < 4; a++) {
				if (planes[f + a] == null) throw new IllegalArgumentException("Missing exact resolved plane coefficient");
				divisor = divisor.gcd(planes[f + a].abs()); normal |= a < 3 && planes[f + a].signum() != 0;
			}
			if (!normal || !divisor.equals(BigInteger.ONE))
				throw new IllegalArgumentException("Invalid resolved supporting plane");
		}
		this.level = level; this.x = x; this.y = y; this.z = z;
		this.vertices = vertices.clone(); this.faceOffsets = faceOffsets.clone(); this.faceVertices = faceVertices.clone();
		this.planes = planes.clone();
		this.velocity = velocity.clone();
	}
	public int level() { return level; }
	public int x() { return x; }
	public int y() { return y; }
	public int z() { return z; }
	public BigInteger[] copyVertices() { return vertices.clone(); }
	public int[] copyFaceOffsets() { return faceOffsets.clone(); }
	public int[] copyFaceVertices() { return faceVertices.clone(); }
	public BigInteger[] copyPlanes() { return planes.clone(); }
	/** Mean velocity in metres per second, transported with this geometric control volume. */
	public double[] copyVelocity() { return velocity.clone(); }
	public long cpuBytes() {
		long bytes = (long) (faceOffsets.length + faceVertices.length) * Integer.BYTES + 3L * Double.BYTES;
		for (var coordinate : vertices) bytes = Math.addExact(bytes, 32L + (coordinate.bitLength() + 7L) / 8);
		for (var coefficient : planes) bytes = Math.addExact(bytes, 32L + (coefficient.bitLength() + 7L) / 8);
		return bytes;
	}

	public static int compare(ResolvedControlVolume a, ResolvedControlVolume b) {
		int order = compareGeometry(a, b);
		return order == 0 ? java.util.Arrays.compare(a.velocity, b.velocity) : order;
	}
	public static int compareGeometry(ResolvedControlVolume a, ResolvedControlVolume b) {
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
