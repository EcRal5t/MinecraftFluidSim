package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.chemistry.ExactRational;
import cc.ecisr.mmm.units.SignedDyadic;
import java.math.BigInteger;

/** Independent PLICs for the fixed face-connected supports of a partially filled cell. */
final class PlicComponentGeometry {
	record Plane(int support, double fraction, double porosity, float nx, float ny, float nz, double offset) {
		float normal(int axis) { return axis == 0 ? nx : axis == 1 ? ny : nz; }
	}
	private final Plane[] byMicro = new Plane[8];

	static PlicComponentGeometry capture(PhaseLiquidGeometryEdit edit, SignedDyadic[] capacities,
			int x, int y, int z, float cellSize, float fallbackX, float fallbackY, float fallbackZ) {
		if (edit.components().size() != capacities.length) throw new IllegalArgumentException("PLIC component capacities differ");
		var result = new PlicComponentGeometry();
		for (int i = 0; i < capacities.length; i++) {
			var stock = edit.components().get(i); SignedDyadic capacity = capacities[i];
			if (capacity.signum() < 0 || stock.volume().compareTo(capacity) > 0)
				throw new MechanicalTimeRefinement(2,"Liquid inventory exceeds its connected pore capacity");
			double fraction = capacity.signum() == 0 ? 0 : ratio(stock.volume(),capacity);
			float[] normal = {0,0,0}; double offset = 0;
			if (fraction > 0 && fraction < 1) {
				int support = stock.supportMask(), count = Integer.bitCount(support);
				int[] low = new int[3];
				for (int micro = 0; micro < 8; micro++) if ((support & 1 << micro) != 0)
					for (int axis = 0; axis < 3; axis++) low[axis] += 1 - (micro >> axis & 1);
				SignedDyadic metricVolume = stock.volume().multiply(SignedDyadic.of(cellSize));
				double[] direction = {
						FlipPressurePhaseGeometry.momentDirection(FlipPressurePhaseGeometry.centeredMoment(stock.firstMomentX(),metricVolume,x),low[0],count),
						FlipPressurePhaseGeometry.momentDirection(FlipPressurePhaseGeometry.centeredMoment(stock.firstMomentY(),metricVolume,y),low[1],count),
						FlipPressurePhaseGeometry.momentDirection(FlipPressurePhaseGeometry.centeredMoment(stock.firstMomentZ(),metricVolume,z),low[2],count)};
				double scale = Math.max(Math.abs(direction[0]),Math.max(Math.abs(direction[1]),Math.abs(direction[2])));
				for (int axis = 0; axis < 3; axis++) normal[axis] = scale == 0 ? axis == 0 ? fallbackX : axis == 1 ? fallbackY : fallbackZ
						: Double.isInfinite(scale) ? Double.isInfinite(direction[axis]) ? (float)Math.signum(direction[axis]) : 0
						: (float)(direction[axis]/scale);
				double length = Math.sqrt((double)normal[0]*normal[0]+(double)normal[1]*normal[1]+(double)normal[2]*normal[2]);
				if (length == 0) normal[1] = 1;
				else for (int axis = 0; axis < 3; axis++) normal[axis] = (float)(normal[axis]/length);
				offset = offset(support,normal,fraction*count/8);
			}
			Plane plane = new Plane(stock.supportMask(),fraction,capacity.toDouble()*8/Integer.bitCount(stock.supportMask()),normal[0],normal[1],normal[2],offset);
			for (int micro = 0; micro < 8; micro++) if ((plane.support & 1 << micro) != 0) result.byMicro[micro] = plane;
		}
		return result;
	}

	private static double ratio(SignedDyadic a, SignedDyadic b) {
		int shift = a.exponent()-b.exponent(); BigInteger numerator = a.significand(), denominator = b.significand();
		return (shift >= 0 ? new ExactRational(numerator.shiftLeft(shift),denominator)
				: new ExactRational(numerator,denominator.shiftLeft(-shift))).toDouble();
	}
	private static double offset(int support, float[] normal, double volume) {
		double lower = Math.min(0,normal[0])+Math.min(0,normal[1])+Math.min(0,normal[2]);
		double upper = Math.max(0,normal[0])+Math.max(0,normal[1])+Math.max(0,normal[2]);
		for (int i = 0; i < 64; i++) {
			double middle = .5*(lower+upper); if (middle == lower || middle == upper) break;
			double retained = 0;
			for (int micro = 0; micro < 8; micro++) if ((support & 1 << micro) != 0) {
				double threshold = middle-.5*(normal[0]*(micro&1)+normal[1]*(micro>>1&1)+normal[2]*(micro>>2&1));
				retained += .125*FlipFreeSurfaceGeometry.unitCubeLiquidFraction(.5*normal[0],.5*normal[1],.5*normal[2],threshold);
			}
			if (retained < volume) lower = middle; else upper = middle;
		}
		return .5*(lower+upper);
	}
	Plane plane(int micro) { return byMicro[micro]; }
}
