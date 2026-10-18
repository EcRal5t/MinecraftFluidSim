package mmm.fluid;

import mmm.units.SignedDyadic;

/** Independent PLICs for the fixed face-connected supports of a partially filled cell. */
final class PlicComponentGeometry {
	record Plane(int support, double fraction, double porosity, float nx, float ny, float nz, PlicPlaneOffset.Result bracket) {
		float normal(int axis) { return axis == 0 ? nx : axis == 1 ? ny : nz; }
		double offset() { return bracket == null ? 0 : bracket.offset(); }
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
			SignedDyadic gas = capacity.subtract(stock.volume());
			double fraction = capacity.signum() == 0 ? 0 : ratio(stock.volume(),capacity);
			if (stock.volume().signum() > 0 && gas.signum() > 0)
				fraction = Math.clamp(fraction, Double.MIN_VALUE, Math.nextDown(1.0));
			float[] normal = {0,0,0}; PlicPlaneOffset.Result bracket = null;
			if (fraction > 0 && fraction < 1) {
				int support = stock.supportMask(), count = Integer.bitCount(support);
				double metricVolume = stock.volume().toDouble() * cellSize;
				double[] direction = {
						FlipPressurePhaseGeometry.ownMomentDirection(stock.firstMomentX(),metricVolume,x,support,0),
						FlipPressurePhaseGeometry.ownMomentDirection(stock.firstMomentY(),metricVolume,y,support,1),
						FlipPressurePhaseGeometry.ownMomentDirection(stock.firstMomentZ(),metricVolume,z,support,2)};
				double scale = edit.components().size() == 1 ? 0
						: Math.max(Math.abs(direction[0]),Math.max(Math.abs(direction[1]),Math.abs(direction[2])));
				for (int axis = 0; axis < 3; axis++) normal[axis] = scale == 0 ? axis == 0 ? fallbackX : axis == 1 ? fallbackY : fallbackZ
						: Double.isInfinite(scale) ? Double.isInfinite(direction[axis]) ? (float)Math.signum(direction[axis]) : 0
						: (float)(direction[axis]/scale);
				double length = Math.sqrt((double)normal[0]*normal[0]+(double)normal[1]*normal[1]+(double)normal[2]*normal[2]);
				if (length == 0) normal[1] = 1;
				else for (int axis = 0; axis < 3; axis++) normal[axis] = (float)(normal[axis]/length);
				// A phase below binary32 cell-volume resolution uses a compact corner shape.
				// Solving the smaller side directly retains its capacity when 1-fraction rounds to zero.
				boolean compact = (float)stock.volume().toDouble() == (float)capacity.toDouble()
						|| (float)gas.toDouble() == (float)capacity.toDouble();
				if (compact) for (int axis=0;axis<3;axis++)
					normal[axis] = (float)Math.copySign(1 / Math.sqrt(3), normal[axis] == 0 ? 1 : normal[axis]);
				if (stock.volume().compareTo(gas) <= 0)
					bracket = PlicPlaneOffset.solve(support,normal[0],normal[1],normal[2],ratio(stock.volume(),capacity) * (count * .125));
				else {
					var dry = PlicPlaneOffset.solve(support,-normal[0],-normal[1],-normal[2],ratio(gas,capacity) * (count * .125));
					bracket = new PlicPlaneOffset.Result(-dry.offset(),fraction * (count * .125));
				}
			}
			Plane plane = new Plane(stock.supportMask(),fraction,capacity.toDouble()*8/Integer.bitCount(stock.supportMask()),normal[0],normal[1],normal[2],bracket);
			for (int micro = 0; micro < 8; micro++) if ((plane.support & 1 << micro) != 0) result.byMicro[micro] = plane;
		}
		return result;
	}

	static double ratio(SignedDyadic a, SignedDyadic b) {
		return a.toDouble() / b.toDouble();
	}
	Plane plane(int micro) { return byMicro[micro]; }
}
