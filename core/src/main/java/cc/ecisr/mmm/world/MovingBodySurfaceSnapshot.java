package cc.ecisr.mmm.world;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Loader-neutral bounded set of translational axis-aligned moving-solid surfaces for one step. */
public final class MovingBodySurfaceSnapshot {
	public static final int MAXIMUM_BODIES = 256;
	public static final int FACES_PER_BOX = 6;
	public static final MovingBodySurfaceSnapshot EMPTY = new MovingBodySurfaceSnapshot("none", List.of());

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
			double initialMomentumVelocityXMetresPerSecond,
			double initialMomentumVelocityYMetresPerSecond,
			double initialMomentumVelocityZMetresPerSecond,
			CouplingMode couplingMode,
			double massKilograms
	) {
		public Body(long transientId, BoxPose beforePose, BoxPose afterPose,
				double velocityXMetresPerSecond, double velocityYMetresPerSecond,
				double velocityZMetresPerSecond, CouplingMode couplingMode, double massKilograms) {
			this(transientId, beforePose, afterPose,
					velocityXMetresPerSecond, velocityYMetresPerSecond, velocityZMetresPerSecond,
					velocityXMetresPerSecond, velocityYMetresPerSecond, velocityZMetresPerSecond,
					couplingMode, massKilograms);
		}

		public Body {
			if (transientId < 0L || beforePose == null || afterPose == null || couplingMode == null
					|| !(Double.isFinite(velocityXMetresPerSecond)
					&& Double.isFinite(velocityYMetresPerSecond)
					&& Double.isFinite(velocityZMetresPerSecond)
					&& Double.isFinite(initialMomentumVelocityXMetresPerSecond)
					&& Double.isFinite(initialMomentumVelocityYMetresPerSecond)
					&& Double.isFinite(initialMomentumVelocityZMetresPerSecond))) {
				throw new IllegalArgumentException("Moving-body identity, pose or velocity is invalid");
			}
			if (couplingMode == CouplingMode.DYNAMIC) {
				if (!(Double.isFinite(massKilograms) && massKilograms > 0.0)) {
					throw new IllegalArgumentException("Dynamic moving body requires a finite positive mass");
				}
			} else if (Double.doubleToRawLongBits(massKilograms) != Double.doubleToRawLongBits(0.0)) {
				throw new IllegalArgumentException("Kinematic external body must not claim a mass");
			} else if (!sameVelocity(velocityXMetresPerSecond, initialMomentumVelocityXMetresPerSecond)
					|| !sameVelocity(velocityYMetresPerSecond, initialMomentumVelocityYMetresPerSecond)
					|| !sameVelocity(velocityZMetresPerSecond, initialMomentumVelocityZMetresPerSecond)) {
				throw new IllegalArgumentException("Kinematic external body has no independent momentum velocity");
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

		public Body atFraction(double fraction) {
			if (!(Double.isFinite(fraction) && fraction >= 0.0 && fraction <= 1.0)) {
				throw new IllegalArgumentException("Moving-body interpolation fraction is invalid");
			}
			BoxPose pose = new BoxPose(
					interpolate(beforePose.minimumX(), afterPose.minimumX(), fraction),
					interpolate(beforePose.minimumY(), afterPose.minimumY(), fraction),
					interpolate(beforePose.minimumZ(), afterPose.minimumZ(), fraction),
					interpolate(beforePose.maximumX(), afterPose.maximumX(), fraction),
					interpolate(beforePose.maximumY(), afterPose.maximumY(), fraction),
					interpolate(beforePose.maximumZ(), afterPose.maximumZ(), fraction));
			return new Body(transientId, pose, pose, velocityXMetresPerSecond,
					velocityYMetresPerSecond, velocityZMetresPerSecond,
					initialMomentumVelocityXMetresPerSecond, initialMomentumVelocityYMetresPerSecond,
					initialMomentumVelocityZMetresPerSecond, couplingMode, massKilograms);
		}

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

		private static boolean sameVelocity(double first, double second) {
			return Double.doubleToRawLongBits(first) == Double.doubleToRawLongBits(second);
		}

		private static double interpolate(double before, double after, double fraction) {
			return Math.fma(after - before, fraction, before);
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
	private final String definitionFingerprint;

	public MovingBodySurfaceSnapshot(List<Body> bodies) {
		this("fixture", bodies);
	}

	public MovingBodySurfaceSnapshot(String definitionFingerprint, List<Body> bodies) {
		if (definitionFingerprint == null || definitionFingerprint.isBlank()) {
			throw new IllegalArgumentException("Moving-body definition fingerprint is missing");
		}
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
			if (collideDuringStep(copy.get(first), copy.get(second))) {
				throw new IllegalArgumentException("Moving-body snapshot contains colliding body trajectories");
			}
		}
		this.bodies = List.copyOf(copy);
		this.definitionFingerprint = definitionFingerprint;
	}

	public List<Body> bodies() { return bodies; }
	public String definitionFingerprint() { return definitionFingerprint; }
	public int faceCount() { return Math.multiplyExact(bodies.size(), FACES_PER_BOX); }

	private static boolean collideDuringStep(Body first, Body second) {
		double[] interval = {0.0, 1.0};
		return overlapInterval(interval,
				centre(first.beforePose().minimumX(), first.beforePose().maximumX())
						- centre(second.beforePose().minimumX(), second.beforePose().maximumX()),
				centre(first.afterPose().minimumX(), first.afterPose().maximumX())
						- centre(second.afterPose().minimumX(), second.afterPose().maximumX()),
				halfExtent(first.beforePose().minimumX(), first.beforePose().maximumX())
						+ halfExtent(second.beforePose().minimumX(), second.beforePose().maximumX()))
				&& overlapInterval(interval,
				centre(first.beforePose().minimumY(), first.beforePose().maximumY())
						- centre(second.beforePose().minimumY(), second.beforePose().maximumY()),
				centre(first.afterPose().minimumY(), first.afterPose().maximumY())
						- centre(second.afterPose().minimumY(), second.afterPose().maximumY()),
				halfExtent(first.beforePose().minimumY(), first.beforePose().maximumY())
						+ halfExtent(second.beforePose().minimumY(), second.beforePose().maximumY()))
				&& overlapInterval(interval,
				centre(first.beforePose().minimumZ(), first.beforePose().maximumZ())
						- centre(second.beforePose().minimumZ(), second.beforePose().maximumZ()),
				centre(first.afterPose().minimumZ(), first.afterPose().maximumZ())
						- centre(second.afterPose().minimumZ(), second.afterPose().maximumZ()),
				halfExtent(first.beforePose().minimumZ(), first.beforePose().maximumZ())
						+ halfExtent(second.beforePose().minimumZ(), second.beforePose().maximumZ()))
				&& interval[0] < interval[1];
	}

	private static boolean overlapInterval(double[] interval, double relativeBefore,
			double relativeAfter, double halfExtentSum) {
		double delta = relativeAfter - relativeBefore;
		if (delta == 0.0) return Math.abs(relativeBefore) < halfExtentSum;
		double first = (-halfExtentSum - relativeBefore) / delta;
		double second = (halfExtentSum - relativeBefore) / delta;
		double entry = Math.min(first, second), exit = Math.max(first, second);
		interval[0] = Math.max(interval[0], entry);
		interval[1] = Math.min(interval[1], exit);
		return interval[0] < interval[1];
	}

	private static double centre(double minimum, double maximum) { return 0.5 * (minimum + maximum); }
	private static double halfExtent(double minimum, double maximum) { return 0.5 * (maximum - minimum); }
}
