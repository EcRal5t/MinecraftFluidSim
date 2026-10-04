package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.units.SignedDyadic;
import java.util.Arrays;
import java.util.List;

/** Hybrid pressure energy solved in exactly conservative volume-rate coordinates. */
final class LiquidControlVolumeProjection {
	record Cell(LiquidPolyhedron geometry, double density) {
		Cell {
			if (geometry == null || !(geometry.moments().volume() > 0) || !(density > 0) || !Double.isFinite(density))
				throw new IllegalArgumentException("Invalid liquid pressure control volume");
			double mass = density * geometry.moments().volume();
			if (!(mass > 0) || !Double.isFinite(mass) || !Double.isFinite(1 / density))
				throw new IllegalArgumentException("Liquid control-volume inertia exceeds numerical range");
		}
		LiquidPolyhedron.Point centroid() {
			var m = geometry.moments(); return new LiquidPolyhedron.Point(m.centroid(0), m.centroid(1), m.centroid(2));
		}
	}
	/** second >= 0: liquid neighbor; -1: liquid-side interface pressure; -2: solid normal velocity. */
	record Face(int first, int second, LiquidPolyhedron.Surface surface, double prescribed) {
		Face {
			if (first < 0 || second < -2 || first == second || surface == null || !(surface.area() > 0)
					|| !Double.isFinite(surface.area()) || !Double.isFinite(prescribed)) throw new IllegalArgumentException("Invalid liquid pressure face");
		}
	}
	/** Positive volume compliance is -dV/dp in m^3/Pa, evaluated by the gas equation of state. */
	record GasNode(double pressure, double volumeCompliance) {
		GasNode {
			if (!Double.isFinite(pressure) || !(volumeCompliance > 0) || !Double.isFinite(volumeCompliance))
				throw new IllegalArgumentException("Invalid compliant gas pressure node");
		}
	}
	record Result(double[] pressure, double[] normalVelocity, int iterations, double fluxResidual,
			double[] gasPressure, double[] gasVolumeChange, LiquidPolyhedron.Point[] pressureGradient,
			double[] cellVelocity, double pressureDissipationJoules, double boundaryWorkJoules,
			LiquidPolyhedron.Point boundaryImpulse, double divergenceResidual, double divergenceRoundoffBound,
			SignedDyadic[] volumeRate, double[] gasPressureChange) { }
	private final ConservativeControlVolumeProjection projection;
	LiquidControlVolumeProjection(List<Cell> cells, List<Face> faces) {
		this(cells, faces, List.of(), fixedGasFaces(faces.size()));
	}
	LiquidControlVolumeProjection(List<Cell> cells, List<Face> faces, List<GasNode> gasNodes, int[] faceGasNode) {
		projection = new ConservativeControlVolumeProjection(cells, faces, gasNodes, faceGasNode);
	}
	/** tolerance (1/s) bounds the variational stationarity error, normalized by supporting volume. */
	Result project(double[] incoming, LiquidPolyhedron.Point gravity, double dt, double tolerance, int maximumIterations) {
		return projection.project(incoming, gravity, dt, tolerance, maximumIterations);
	}
	private static int[] fixedGasFaces(int count) { int[] result = new int[count]; Arrays.fill(result, -1); return result; }
}
