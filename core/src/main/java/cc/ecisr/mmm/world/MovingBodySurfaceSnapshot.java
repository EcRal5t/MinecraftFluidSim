package cc.ecisr.mmm.world;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Loader-neutral bounded set of translational axis-aligned moving-solid surfaces for one step. */
public final class MovingBodySurfaceSnapshot {
	public static final int MAXIMUM_BODIES = 256;
	public static final int FACES_PER_BOX = 6;

	public enum CouplingMode { DYNAMIC, KINEMATIC_EXTERNAL }

	public record BoxPose(
			double minimumX, double minimumY, double minimumZ,
			double maximumX, double maximumY, double maximumZ
	) {
		public BoxPose {
			if (!(Double.isFinite(minimumX) && Double.isFinite(minimumY) && Double.isFinite(minimumZ)
					&& Double.isFinite(maximumX) && Double.isFinite(maximumY) && Double.isFinite(maximumZ)
					&& minimumX < maximumX && minimumY < maximumY && minimumZ < maximumZ)) {
				throw new IllegalArgumentException("Moving-body box pose is invalid");
			}
		}

		public double volumeCubicMetres() {
			return (maximumX - minimumX) * (maximumY - minimumY) * (maximumZ - minimumZ);
		}
	}

	public record Body(
			long transientId,
			BoxPose beforePose,
			BoxPose afterPose,
			double velocityXMetresPerSecond,
			double velocityYMetresPerSecond,
			double velocityZMetresPerSecond,
			CouplingMode couplingMode,
			double massKilograms
	) {
		public Body {
			if (transientId < 0L || beforePose == null || afterPose == null || couplingMode == null
					|| !(Double.isFinite(velocityXMetresPerSecond)
					&& Double.isFinite(velocityYMetresPerSecond)
					&& Double.isFinite(velocityZMetresPerSecond))) {
				throw new IllegalArgumentException("Moving-body identity, pose or velocity is invalid");
			}
			if (couplingMode == CouplingMode.DYNAMIC) {
				if (!(Double.isFinite(massKilograms) && massKilograms > 0.0)) {
					throw new IllegalArgumentException("Dynamic moving body requires a finite positive mass");
				}
			} else if (Double.doubleToRawLongBits(massKilograms) != Double.doubleToRawLongBits(0.0)) {
				throw new IllegalArgumentException("Kinematic external body must not claim a mass");
			}
			if (!sameExtent(beforePose.maximumX() - beforePose.minimumX(),
					afterPose.maximumX() - afterPose.minimumX())
					|| !sameExtent(beforePose.maximumY() - beforePose.minimumY(),
					afterPose.maximumY() - afterPose.minimumY())
					|| !sameExtent(beforePose.maximumZ() - beforePose.minimumZ(),
					afterPose.maximumZ() - afterPose.minimumZ())) {
				throw new IllegalArgumentException("Moving-body box may translate but cannot change dimensions");
			}
		}

		public double displacedVolumeCubicMetres() { return afterPose.volumeCubicMetres(); }

		public List<Face> faces() {
			double minX = afterPose.minimumX(), minY = afterPose.minimumY(), minZ = afterPose.minimumZ();
			double maxX = afterPose.maximumX(), maxY = afterPose.maximumY(), maxZ = afterPose.maximumZ();
			double midX = 0.5 * (minX + maxX), midY = 0.5 * (minY + maxY), midZ = 0.5 * (minZ + maxZ);
			double areaX = (maxY - minY) * (maxZ - minZ);
			double areaY = (maxX - minX) * (maxZ - minZ);
			double areaZ = (maxX - minX) * (maxY - minY);
			return List.of(
					face(areaX, -1.0, 0.0, 0.0, minX, midY, midZ),
					face(areaX, 1.0, 0.0, 0.0, maxX, midY, midZ),
					face(areaY, 0.0, -1.0, 0.0, midX, minY, midZ),
					face(areaY, 0.0, 1.0, 0.0, midX, maxY, midZ),
					face(areaZ, 0.0, 0.0, -1.0, midX, midY, minZ),
					face(areaZ, 0.0, 0.0, 1.0, midX, midY, maxZ));
		}

		private Face face(double area, double normalX, double normalY, double normalZ,
				double centroidX, double centroidY, double centroidZ) {
			return new Face(area, normalX, normalY, normalZ, centroidX, centroidY, centroidZ,
					velocityXMetresPerSecond, velocityYMetresPerSecond, velocityZMetresPerSecond);
		}

		private static boolean sameExtent(double first, double second) {
			return Math.abs(first - second) <= Math.max(Math.ulp(first), Math.ulp(second)) * 8.0;
		}
	}

	public record Face(
			double areaSquareMetres,
			double normalX, double normalY, double normalZ,
			double centroidXMetres, double centroidYMetres, double centroidZMetres,
			double velocityXMetresPerSecond, double velocityYMetresPerSecond, double velocityZMetresPerSecond
	) {
		public Face {
			double normalSquared = normalX * normalX + normalY * normalY + normalZ * normalZ;
			if (!(Double.isFinite(areaSquareMetres) && areaSquareMetres > 0.0
					&& normalSquared == 1.0
					&& Double.isFinite(centroidXMetres) && Double.isFinite(centroidYMetres)
					&& Double.isFinite(centroidZMetres)
					&& Double.isFinite(velocityXMetresPerSecond)
					&& Double.isFinite(velocityYMetresPerSecond)
					&& Double.isFinite(velocityZMetresPerSecond))) {
				throw new IllegalArgumentException("Moving-body surface face is invalid");
			}
		}
	}

	private final List<Body> bodies;

	public MovingBodySurfaceSnapshot(List<Body> bodies) {
		if (bodies == null || bodies.size() > MAXIMUM_BODIES) {
			throw new IllegalArgumentException("Moving-body snapshot exceeds its body budget");
		}
		ArrayList<Body> copy = new ArrayList<>(bodies.size());
		HashSet<Long> ids = new HashSet<>();
		for (Body body : bodies) {
			if (body == null || !ids.add(body.transientId())) {
				throw new IllegalArgumentException("Moving-body snapshot has a missing or duplicate transient id");
			}
			copy.add(body);
		}
		copy.sort(java.util.Comparator.comparingLong(Body::transientId));
		for (int first = 0; first < copy.size(); first++) for (int second = first + 1; second < copy.size(); second++) {
			if (overlaps(copy.get(first).afterPose(), copy.get(second).afterPose())) {
				throw new IllegalArgumentException("Moving-body snapshot contains overlapping bodies");
			}
		}
		this.bodies = List.copyOf(copy);
	}

	public List<Body> bodies() { return bodies; }
	public int faceCount() { return Math.multiplyExact(bodies.size(), FACES_PER_BOX); }

	private static boolean overlaps(BoxPose first, BoxPose second) {
		return first.minimumX() < second.maximumX() && first.maximumX() > second.minimumX()
				&& first.minimumY() < second.maximumY() && first.maximumY() > second.minimumY()
				&& first.minimumZ() < second.maximumZ() && first.maximumZ() > second.minimumZ();
	}
}
