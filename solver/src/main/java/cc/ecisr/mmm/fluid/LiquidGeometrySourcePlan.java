package cc.ecisr.mmm.fluid;

import java.util.ArrayList;
import java.util.List;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;

/** Selects an exact source volume within the disjoint gas space of a complete phase partition. */
final class LiquidGeometrySourcePlan {
	final List<LiquidPolyhedron> geometry;

	LiquidGeometrySourcePlan(List<LiquidPolyhedron> available, Fraction requested, int maximumFragments) {
		if (requested.signum() <= 0 || maximumFragments < 1) throw new IllegalArgumentException("Invalid liquid geometric source");
		var selected = new ArrayList<LiquidPolyhedron>(); var remaining = requested;
		for (var open : available) {
			if (remaining.signum() == 0) break;
			var volume = LiquidConvexRegion.of(open).measure().volume();
			if (volume.signum() <= 0) throw new IllegalArgumentException("Liquid source has empty open space");
			if (remaining.subtract(volume).signum() >= 0) { add(selected, open, maximumFragments); remaining = remaining.subtract(volume); continue; }
			int precision = 52;
			while (true) {
				try {
					var interfaceGeometry = LiquidVolumeInterface.reconstruct(open, remaining, new LiquidPolyhedron.Point(0, 1, 0), precision);
					add(selected, interfaceGeometry.singleGeometry(), maximumFragments); remaining = Fraction.ZERO; break;
				} catch (LiquidCoordinateResolutionRequired unresolved) {
					if (precision == 1074) throw unresolved;
					precision = Math.min(1074, 2 * precision);
				}
			}
		}
		if (remaining.signum() != 0) throw new IllegalStateException("Liquid source exceeds exact open geometric capacity");
		var measured = LiquidConvexRegion.EMPTY.measure(); for (var piece : selected) measured = measured.add(LiquidConvexRegion.of(piece).measure());
		if (!measured.volume().equals(requested)) throw new IllegalStateException("Liquid source changed exact requested volume");
		geometry = List.copyOf(selected);
	}
	private static void add(ArrayList<LiquidPolyhedron> selected, LiquidPolyhedron piece, int maximum) {
		if (selected.size() == maximum) throw new IllegalStateException("Liquid source exceeds its geometric fragment budget");
		selected.add(piece);
	}
}
