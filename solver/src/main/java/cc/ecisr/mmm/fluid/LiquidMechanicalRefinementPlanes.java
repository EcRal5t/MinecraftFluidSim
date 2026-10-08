package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Field interfaces supply oblique cuts after auxiliary faces have canceled inside each original field. */
final class LiquidMechanicalRefinementPlanes {
	private LiquidMechanicalRefinementPlanes() { }
	static List<List<BigInteger>> of(LiquidGeometryRefinement.Cell box, List<LiquidPolyhedron> occupied,
			List<LiquidGeometryVelocityProjection.Source> sources, LiquidSupportVolumeFit.Work work) {
		var candidates = new LinkedHashMap<LiquidGeometryPlane.Key, List<BigInteger>>();
		var extent = box.geometry().coordinateBounds();
		for (int axis = 0; axis < 3; axis++) {
			var middle = extent[0][axis].add(extent[1][axis]).divide(Fraction.of(2)); var plane = new ArrayList<BigInteger>();
			for (int a = 0; a < 3; a++) plane.add(a == axis ? middle.denominator() : BigInteger.ZERO);
			plane.add(middle.numerator()); candidates.put(LiquidGeometryPlane.exact(plane).key(), List.copyOf(plane));
		}
		var fields = new LinkedHashMap<LiquidGeometryVelocityProjection.Key, ArrayList<LiquidGeometryRefinement.Cell>>();
		var address = LiquidPhasePartition.Address.of(box);
		for (var source : sources) fields.computeIfAbsent(new LiquidGeometryVelocityProjection.Key(source.phase(), source.inventory(), source.density(), source.velocity()),
				ignored -> new ArrayList<>()).add(address.cell(source.geometry()));
		for (var field : fields.values()) for (var face : LiquidExteriorGeometry.of(field, work)) {
			var key = face.plane().key(); if (candidates.containsKey(key)) continue;
			work.charge(); boolean positive = false, negative = false;
			for (var piece : occupied) for (var polygon : LiquidExactPolygon.faces(piece)) for (var point : polygon.vertices()) {
				int sign = point.distance(key.coefficients()).signum(); positive |= sign > 0; negative |= sign < 0;
			}
			if (positive && negative) candidates.put(key, key.coefficients());
		}
		return List.copyOf(candidates.values());
	}
}
