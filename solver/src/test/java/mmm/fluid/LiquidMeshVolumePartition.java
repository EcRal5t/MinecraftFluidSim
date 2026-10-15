package mmm.fluid;

import java.util.ArrayList;
import java.util.List;
import mmm.chemistry.ExactRational;
import mmm.fluid.GasGeometryOverlap.Point;

/** Exact reference remapping between positive tetrahedral decompositions of equal volume. */
final class LiquidMeshVolumePartition {
	record Result(List<LiquidMappedTetrahedron> maps, ExactRational firstMomentErrorBound) {
		Result { maps = List.copyOf(maps); }
	}
	private record Tetra(List<Point> vertices, ExactRational volume) {
		Tetra(List<Point> vertices) { this(List.copyOf(vertices), determinant(vertices).divide(6)); }
		Tetra[] split(ExactRational quantity) {
			if (quantity.signum() <= 0 || quantity.compareTo(volume) > 0) throw new IllegalArgumentException("Invalid positive simplex split");
			if (quantity.equals(volume)) return new Tetra[]{this, null};
			var point = vertices.get(0).interpolate(vertices.get(1), divide(quantity, volume));
			return new Tetra[]{new Tetra(List.of(vertices.get(0), point, vertices.get(2), vertices.get(3)), quantity),
					new Tetra(List.of(point, vertices.get(1), vertices.get(2), vertices.get(3)), volume.subtract(quantity))};
		}
	}
	private LiquidMeshVolumePartition() { }

	static Result match(List<LiquidMappedTetrahedron> preferred, int maximumMaps) {
		var before = new Tetra[preferred.size()]; var after = new Tetra[preferred.size()];
		var sourceVolume = ExactRational.ZERO; var arrivalVolume = ExactRational.ZERO;
		for (int i = 0; i < preferred.size(); i++) {
			before[i] = new Tetra(preferred.get(i).sourceVertices()); after[i] = new Tetra(preferred.get(i).arrivalVertices());
			sourceVolume = sourceVolume.add(before[i].volume); arrivalVolume = arrivalVolume.add(after[i].volume);
		}
		if (!sourceVolume.equals(arrivalVolume)) throw new GasTopologyBlockedException("Moving liquid parent volume does not close before local remapping");
		var result = new ArrayList<LiquidMappedTetrahedron>(); var bound = ExactRational.ZERO;
		for (int i = 0; i < preferred.size(); i++) bound = bound.add(pair(before, i, after, i, preferred.get(i), result, maximumMaps));
		for (int i = 0, j = 0; i < before.length; i++) while (before[i] != null) {
			while (j < after.length && after[j] == null) j++;
			if (j == after.length) throw new IllegalStateException("Positive liquid remapping exhausted its arrival partition");
			bound = bound.add(pair(before, i, after, j, preferred.get(i), result, maximumMaps));
		}
		for (var remainder : after) if (remainder != null) throw new IllegalStateException("Positive liquid remapping has an arrival remainder");
		return new Result(result, bound);
	}
	private static ExactRational pair(Tetra[] before, int i, Tetra[] after, int j, LiquidMappedTetrahedron preferred,
	                                  List<LiquidMappedTetrahedron> result, int maximumMaps) {
		if (before[i] == null || after[j] == null) return ExactRational.ZERO;
		var quantity = before[i].volume.compareTo(after[j].volume) < 0 ? before[i].volume : after[j].volume;
		var source = before[i].split(quantity); var arrival = after[j].split(quantity);
		if (result.size() >= maximumMaps) throw new GasTopologyBlockedException("Positive liquid remapping exceeds its simplex budget");
		var map = new LiquidMappedTetrahedron(source[0].vertices, arrival[0].vertices);
		if (!map.jacobian().equals(ExactRational.ONE)) throw new IllegalStateException("Matched liquid simplices have different volumes");
		result.add(map); before[i] = source[1]; after[j] = arrival[1];
		var maximum = ExactRational.ZERO;
		for (int p = 0; p < 4; p++) {
			var expected = preferred.mapSource(source[0].vertices.get(p));
			for (int a = 0; a < 3; a++) {
				var error = arrival[0].vertices.get(p).component(a).subtract(expected.component(a));
				if (error.signum() < 0) error = error.multiply(-1);
				if (error.compareTo(maximum) > 0) maximum = error;
			}
		}
		// The diagnostic bound rounds upward; conserved volumes and mappings remain exact.
		var exactBound = quantity.multiply(maximum); double observed = exactBound.toDouble();
		var bound = PlicComponentGeometry.ratio(mmm.units.SignedDyadic.of(observed), mmm.units.SignedDyadic.of(1));
		if (bound.compareTo(exactBound) < 0) bound = PlicComponentGeometry.ratio(mmm.units.SignedDyadic.of(Math.nextUp(observed)), mmm.units.SignedDyadic.of(1));
		return bound;
	}
	private static ExactRational determinant(List<Point> vertices) { return GasGeometryOverlap.determinant(vertices.get(1).subtract(vertices.get(0)), vertices.get(2).subtract(vertices.get(0)), vertices.get(3).subtract(vertices.get(0))); }
	private static ExactRational divide(ExactRational a, ExactRational b) { return new ExactRational(a.numerator().multiply(b.denominator()), a.denominator().multiply(b.numerator())); }
}
