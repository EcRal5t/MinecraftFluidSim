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
			return geometry.centroid();
		}
	}
	/** second >= 0: liquid neighbor; -1: liquid-side interface pressure; -2: solid normal velocity. */
	record Face(int first, int second, LiquidPolyhedron.Surface surface, double prescribed,
			List<LiquidExactPolygon.Fraction> areaVector, List<LiquidExactPolygon.Fraction> centroid) {
		Face(int first, int second, LiquidPolyhedron.Surface surface, double prescribed) {
			this(first, second, surface, prescribed, coordinates(surface.areaVector()), coordinates(surface.centroid()));
		}
		Face(int first, int second, LiquidPolyhedron.Surface surface, double prescribed, LiquidExactPolygon polygon) {
			this(first, second, surface, prescribed, List.of(polygon.areaVector()), List.of(polygon.centroidCoordinates()));
		}
		Face {
			if (first < 0 || second < -2 || first == second || surface == null || !(surface.area() > 0)
					|| !Double.isFinite(surface.area()) || !Double.isFinite(prescribed) || areaVector.size() != 3 || centroid.size() != 3) throw new IllegalArgumentException("Invalid liquid pressure face");
			areaVector = List.copyOf(areaVector); centroid = List.copyOf(centroid);
		}
		LiquidPolyhedron.Point displacement(LiquidPolyhedron geometry) {
			var center = geometry.centroidCoordinates(); double[] result = new double[3];
			for (int a = 0; a < 3; a++) result[a] = centroid.get(a).subtract(center[a]).rounded();
			return new LiquidPolyhedron.Point(result[0], result[1], result[2]);
		}
		double distance(LiquidPolyhedron geometry, int sign) {
			var center = geometry.centroidCoordinates(); var result = LiquidExactPolygon.Fraction.of(0);
			for (int a = 0; a < 3; a++) result = result.add(areaVector.get(a).multiply(centroid.get(a).subtract(center[a])));
			return sign * result.rounded() / surface.area();
		}
		private static List<LiquidExactPolygon.Fraction> coordinates(LiquidPolyhedron.Point point) {
			return List.of(LiquidExactPolygon.Fraction.of(point.x()), LiquidExactPolygon.Fraction.of(point.y()), LiquidExactPolygon.Fraction.of(point.z()));
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
