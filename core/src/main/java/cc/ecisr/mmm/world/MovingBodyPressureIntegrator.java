package cc.ecisr.mmm.world;

/** Exact planar-face quadrature for pressure impulse on a bounded moving-body surface. */
public final class MovingBodyPressureIntegrator {
	@FunctionalInterface
	public interface PressureSampler {
		double absolutePressurePa(double xMetres, double yMetres, double zMetres);
	}

	public record Result(
			long transientId,
			double impulseXKilogramMetresPerSecond,
			double impulseYKilogramMetresPerSecond,
			double impulseZKilogramMetresPerSecond,
			double displacementWorkJoules
	) {
		public Result {
			if (transientId < 0L || !(Double.isFinite(impulseXKilogramMetresPerSecond)
					&& Double.isFinite(impulseYKilogramMetresPerSecond)
					&& Double.isFinite(impulseZKilogramMetresPerSecond)
					&& Double.isFinite(displacementWorkJoules))) {
				throw new IllegalArgumentException("Moving-body pressure result is invalid");
			}
		}
	}

	private MovingBodyPressureIntegrator() { }

	public static Result integrate(
			MovingBodySurfaceSnapshot.Body body, PressureSampler pressure, double timeStepSeconds
	) {
		if (body == null || pressure == null
				|| !(Double.isFinite(timeStepSeconds) && timeStepSeconds > 0.0)) {
			throw new IllegalArgumentException("Moving-body pressure integration input is invalid");
		}
		double impulseX = 0.0, impulseY = 0.0, impulseZ = 0.0;
		for (MovingBodySurfaceSnapshot.Face face : body.faces()) {
			double sample = pressure.absolutePressurePa(
					face.centroidXMetres(), face.centroidYMetres(), face.centroidZMetres());
			if (!(Double.isFinite(sample) && sample >= 0.0)) {
				throw new IllegalArgumentException("Moving-body pressure sample is invalid");
			}
			double impulseScale = -sample * face.areaSquareMetres() * timeStepSeconds;
			impulseX += impulseScale * face.normalX();
			impulseY += impulseScale * face.normalY();
			impulseZ += impulseScale * face.normalZ();
		}
		double work = impulseX * body.velocityXMetresPerSecond()
				+ impulseY * body.velocityYMetresPerSecond()
				+ impulseZ * body.velocityZMetresPerSecond();
		return new Result(body.transientId(), impulseX, impulseY, impulseZ, work);
	}
}
