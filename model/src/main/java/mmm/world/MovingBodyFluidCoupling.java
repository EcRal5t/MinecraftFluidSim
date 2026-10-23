package mmm.world;

/** Prescribed body motion, its delivered liquid impulse and the actual external mechanical work. */
public final class MovingBodyFluidCoupling {
  public static final int MAXIMUM_REQUESTS_PER_STEP = 256;

  private MovingBodyFluidCoupling() {}

  public record Bounds(
      double minimumX,
      double minimumY,
      double minimumZ,
      double maximumX,
      double maximumY,
      double maximumZ) {
    public Bounds {
      if (!(Double.isFinite(minimumX)
          && Double.isFinite(minimumY)
          && Double.isFinite(minimumZ)
          && Double.isFinite(maximumX)
          && Double.isFinite(maximumY)
          && Double.isFinite(maximumZ)
          && maximumX > minimumX
          && maximumY > minimumY
          && maximumZ > minimumZ))
        throw new IllegalArgumentException("Body bounds must be finite and have positive extent");
    }

    public double volume() {
      return (maximumX - minimumX) * (maximumY - minimumY) * (maximumZ - minimumZ);
    }
  }

  public record Request(
      java.util.UUID bodyId,
      Bounds bounds,
      double velocityXMetresPerSecond,
      double velocityYMetresPerSecond,
      double velocityZMetresPerSecond) {
    public Request {
      java.util.Objects.requireNonNull(bodyId, "Body identity is missing");
      java.util.Objects.requireNonNull(bounds, "Body bounds are missing");
      if (!(Double.isFinite(velocityXMetresPerSecond)
          && Double.isFinite(velocityYMetresPerSecond)
          && Double.isFinite(velocityZMetresPerSecond)))
        throw new IllegalArgumentException("Body velocity must be finite");
    }

    public float localXMetres() {
      return (float) (.5 * (bounds.minimumX() + bounds.maximumX()));
    }

    public float localYMetres() {
      return (float) (.5 * (bounds.minimumY() + bounds.maximumY()));
    }

    public float localZMetres() {
      return (float) (.5 * (bounds.minimumZ() + bounds.maximumZ()));
    }

    public double displacedVolumeCubicMetres() {
      return bounds.volume();
    }
  }

  public record Result(
      int coupledMarkers,
      double coupledVolumeCubicMetres,
      double impulseXKilogramMetresPerSecond,
      double impulseYKilogramMetresPerSecond,
      double impulseZKilogramMetresPerSecond,
      double externalWorkJoules) {
    public static final Result NONE = new Result(0, 0.0, 0.0, 0.0, 0.0, 0.0);

    public Result {
      if (coupledMarkers < 0
          || !(Double.isFinite(coupledVolumeCubicMetres) && coupledVolumeCubicMetres >= 0.0)
          || !(Double.isFinite(impulseXKilogramMetresPerSecond)
              && Double.isFinite(impulseYKilogramMetresPerSecond)
              && Double.isFinite(impulseZKilogramMetresPerSecond)
              && Double.isFinite(externalWorkJoules))) {
        throw new IllegalArgumentException("Moving-body fluid coupling result is invalid");
      }
    }

    public Result plus(Result other) {
      if (other == null) throw new IllegalArgumentException("Moving-body result is missing");
      return new Result(
          Math.addExact(coupledMarkers, other.coupledMarkers),
          coupledVolumeCubicMetres + other.coupledVolumeCubicMetres,
          impulseXKilogramMetresPerSecond + other.impulseXKilogramMetresPerSecond,
          impulseYKilogramMetresPerSecond + other.impulseYKilogramMetresPerSecond,
          impulseZKilogramMetresPerSecond + other.impulseZKilogramMetresPerSecond,
          externalWorkJoules + other.externalWorkJoules);
    }
  }

  /** Explicit equal-volume cube for prescribed-motion scenarios. */
  public static Request prescribedCube(
      float x, float y, float z, double vx, double vy, double vz, double volume) {
    double radius = .5 * Math.cbrt(volume);
    var bounds = new Bounds(x - radius, y - radius, z - radius, x + radius, y + radius, z + radius);
    var id =
        java.util.UUID.nameUUIDFromBytes(
            (x + ":" + y + ":" + z + ":" + volume)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return new Request(id, bounds, vx, vy, vz);
  }
}
