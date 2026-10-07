package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Vertex;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** One shared-face coordinate solve imposes all phase volumes after planar joint reconstruction. */
final class LiquidJointInterfaceVolume {
	record Result(List<LiquidMomentReconstruction.Phase> phases, List<Fraction> symmetricDifferenceBounds) { }
	private LiquidJointInterfaceVolume() { }
	static Result project(LiquidGeometryRefinement.Cell open,
			List<LiquidMomentReconstruction.Phase> phases, List<Fraction> volume, int maximumRegions) {
		if (phases.size() != volume.size()) throw new IllegalArgumentException("Incomplete joint volume constraints");
		var cells = new ArrayList<LiquidGeometryRefinement.Cell>(); var groups = new ArrayList<Integer>();
		var address = LiquidPhasePartition.Address.of(open);
		for (int p = 0; p < phases.size(); p++) for (var piece : phases.get(p).pieces()) { cells.add(address.cell(piece)); groups.add(p); }
		if (cells.size() > maximumRegions) throw new IllegalStateException("Joint phase correction exceeds its region budget");
		var source = new Fraction[phases.size()]; var constrained = new boolean[source.length]; Arrays.fill(constrained, true);
		for (int p = 0; p < phases.size(); p++) source[p] = volume.get(p).subtract(LiquidVolumeInterface.measure(phases.get(p).pieces()).volume());
		var difference = new Fraction[phases.size()]; Arrays.fill(difference, Fraction.ZERO);
		if (Arrays.stream(source).allMatch(value -> value.signum() == 0)) return new Result(List.copyOf(phases), List.of(difference));
		var mesh = new LiquidControlVolumeMesh(cells, 1, 1, (c, surface) -> new LiquidControlVolumeMesh.Boundary(true, 0));
		var selected = new ArrayList<Integer>();
		for (int f = 0; f < mesh.faces.size(); f++) {
			var face = mesh.faces.get(f);
			if (face.second() >= 0 && !groups.get(face.first()).equals(groups.get(face.second()))) selected.add(f);
		}
		var first = new int[selected.size()]; var second = first.clone(); var fixed = new Fraction[first.length]; var weights = new double[first.length];
		for (int e = 0; e < selected.size(); e++) {
			var face = mesh.faces.get(selected.get(e)); first[e] = groups.get(face.first()); second[e] = groups.get(face.second());
			var area = face.areaVector().toArray(Fraction[]::new); weights[e] = 1 / dot(area, area).rounded();
			if (!(weights[e] > 0) || !Double.isFinite(weights[e])) throw new LiquidCoordinateResolutionRequired("Joint phase face metric exceeds its coordinate support");
		}
		var coordinates = new ConservativeFluxCoordinates(phases.size(), first, second, constrained, fixed, weights);
		var independent = new Fraction[coordinates.size()]; Arrays.fill(independent, Fraction.ZERO); var transfer = coordinates.liftExact(independent, source);
		var centers = new ArrayList<Vertex>(); var changed = new boolean[mesh.cells.size()];
		for (var polygon : mesh.exactPolygons) centers.add(LiquidExactPolygon.vertex(polygon.centroidCoordinates()));
		for (int e = 0; e < selected.size(); e++) if (transfer[e].signum() != 0) {
			var magnitude = transfer[e].signum() > 0 ? transfer[e] : transfer[e].negate();
			difference[first[e]] = difference[first[e]].add(magnitude); difference[second[e]] = difference[second[e]].add(magnitude);
			int f = selected.get(e); var face = mesh.faces.get(f); var area = face.areaVector().toArray(Fraction[]::new);
			var factor = transfer[e].multiply(Fraction.of(3)).divide(dot(area, area)); var point = centers.get(f).coordinates();
			for (int a = 0; a < 3; a++) point[a] = point[a].add(area[a].multiply(factor));
			var vertex = LiquidExactPolygon.vertex(point);
			for (var plane : LiquidExactPolygon.faces(open.geometry())) if (vertex.distance(plane.coefficients()).signum() > 0)
				throw new LiquidCoordinateResolutionRequired("Joint phase correction leaves its open cell");
			centers.set(f, vertex); changed[face.first()] = changed[face.second()] = true;
		}
		var output = new ArrayList<ArrayList<LiquidPolyhedron>>(); for (int p = 0; p < phases.size(); p++) output.add(new ArrayList<>());
		int regions = 0;
		for (int c = 0; c < cells.size(); c++) {
			var result = output.get(groups.get(c));
			if (!changed[c]) { result.add(cells.get(c).geometry()); regions++; }
			else {
				var center = LiquidExactPolygon.vertex(cells.get(c).geometry().measure().centroidCoordinates());
				var surfaces = new ArrayList<LiquidExactPolygon>(); var fan = new ArrayList<LiquidPolyhedron>();
				for (int f : mesh.cellFaces[c]) {
					var polygon = mesh.faces.get(f).first() == c ? mesh.exactPolygons.get(f) : mesh.exactPolygons.get(f).reversed();
					var original = LiquidExactPolygon.vertex(polygon.centroidCoordinates()); var next = centers.get(f);
					for (int e = 0; e < polygon.vertices().size(); e++) {
						var a = polygon.vertices().get(e); var b = polygon.vertices().get((e + 1) % polygon.vertices().size());
						var old = List.of(center, original, a, b); var moved = List.of(center, next, a, b);
						if (LiquidExactPolygon.homogeneousTetrahedronDeterminant(old).signum() != LiquidExactPolygon.homogeneousTetrahedronDeterminant(moved).signum())
							throw new LiquidCoordinateResolutionRequired("Joint phase correction inverted a shared tetrahedron");
						var tetrahedron = LiquidConvexRegion.ofTetrahedron(moved); fan.add(tetrahedron.geometry()); surfaces.add(tetrahedron.faces().getFirst());
					}
				}
				var vertices = surfaces.stream().flatMap(face -> face.vertices().stream()).distinct().toList();
				boolean convex = surfaces.stream().allMatch(face -> vertices.stream().allMatch(vertex -> vertex.distance(face.coefficients()).signum() <= 0));
				if (convex) { result.add(LiquidPolyhedron.restoredExact(surfaces)); regions++; }
				else { result.addAll(fan); regions += fan.size(); }
			}
			if (regions > maximumRegions) throw new IllegalStateException("Joint phase correction exceeds its region budget");
		}
		var result = new ArrayList<LiquidMomentReconstruction.Phase>();
		for (int p = 0; p < phases.size(); p++) {
			if (!LiquidVolumeInterface.measure(output.get(p)).volume().equals(volume.get(p))) throw new IllegalStateException("Joint phase correction lost an exact volume constraint");
			result.add(new LiquidMomentReconstruction.Phase(phases.get(p).control(), output.get(p)));
		}
		// Each shifted planar face bounds one pyramid of volume |transfer|. Their union bounds the occupied-space change.
		return new Result(List.copyOf(result), List.of(difference));
	}
	private static Fraction dot(Fraction[] a, Fraction[] b) { var sum = Fraction.ZERO; for (int i = 0; i < 3; i++) sum = sum.add(a[i].multiply(b[i])); return sum; }
}
