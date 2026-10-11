package cc.ecisr.mmm.granular;

/** Shared closed sphere/AABB classification for placement and bulk-bed admission. */
public final class GranularSphereGeometry {
	private GranularSphereGeometry() { }

	public static boolean touchesBox(double x, double y, double z, double radius,
	                                double minX, double minY, double minZ,
	                                double maxX, double maxY, double maxZ) {
		double dx = x - Math.max(minX, Math.min(maxX, x));
		double dy = y - Math.max(minY, Math.min(maxY, y));
		double dz = z - Math.max(minZ, Math.min(maxZ, z));
		return dx * dx + dy * dy + dz * dz <= radius * radius;
	}
}
