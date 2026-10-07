package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Vertex;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** Shared-face coordinate solves impose the finite local phase volumes in a common partition. */
final class LiquidJointInterfaceVolume {
	record Result(List<LiquidMomentReconstruction.Phase> phases, List<Fraction> symmetricDifferenceBounds) { }
	record PartitionResult(List<LiquidMomentReconstruction.Leaf> leaves, List<List<Fraction>> symmetricDifferenceBounds) { }
	private LiquidJointInterfaceVolume() { }
	static Result project(LiquidGeometryRefinement.Cell open,
			List<LiquidMomentReconstruction.Phase> phases, List<Fraction> volume, int maximumRegions) {
		if (phases.size() != volume.size()) throw new IllegalArgumentException("Incomplete joint volume constraints");
		var indexed = new ArrayList<LiquidMomentReconstruction.Phase>(); var targets = new LinkedHashMap<Integer, Fraction>();
		for (int p = 0; p < phases.size(); p++) { indexed.add(new LiquidMomentReconstruction.Phase(p, phases.get(p).pieces())); targets.put(p, volume.get(p)); }
		var result = project(List.of(new LiquidMomentReconstruction.Leaf(open, indexed)), targets, List.of(targets), maximumRegions);
		var restored = new ArrayList<LiquidMomentReconstruction.Phase>();
		for (int p = 0; p < phases.size(); p++) restored.add(new LiquidMomentReconstruction.Phase(phases.get(p).control(), result.leaves.getFirst().phases().get(p).pieces()));
		return new Result(List.copyOf(restored), result.symmetricDifferenceBounds.getFirst());
	}
	static PartitionResult project(List<LiquidMomentReconstruction.Leaf> leaves, Map<Integer, Fraction> volume,
			List<Map<Integer, Fraction>> localVolumes, int maximumRegions) {
		if (localVolumes.size() != leaves.size()) throw new IllegalArgumentException("Incomplete local phase volume constraints");
		var cells = new ArrayList<LiquidGeometryRefinement.Cell>(); var groups = new ArrayList<Integer>();
		var owners = new ArrayList<Integer>(); var localPhases = new ArrayList<Integer>(); var localTargets = new ArrayList<Fraction>();
		var targetTotals = new LinkedHashMap<Integer, Fraction>();
		for (int l = 0; l < leaves.size(); l++) {
			var leaf = leaves.get(l); var address = LiquidPhasePartition.Address.of(leaf.open());
			for (int p = 0; p < leaf.phases().size(); p++) {
				var phase = leaf.phases().get(p); int group = localTargets.size(); var target = localVolumes.get(l).get(phase.control());
				if (target == null || target.signum() <= 0) throw new IllegalArgumentException("Missing positive local phase volume");
				localTargets.add(target); targetTotals.merge(phase.control(), target, Fraction::add);
				for (var piece : phase.pieces()) { cells.add(address.cell(piece)); groups.add(group); owners.add(l); localPhases.add(p); }
			}
		}
		if (cells.size() > maximumRegions) throw new IllegalStateException("Joint phase correction exceeds its region budget");
		if (!targetTotals.equals(volume)) throw new IllegalArgumentException("Local phase constraints disagree with material volumes");
		var represented = new Fraction[localTargets.size()]; Arrays.fill(represented, Fraction.ZERO);
		for (int c = 0; c < cells.size(); c++) represented[groups.get(c)] = represented[groups.get(c)].add(cells.get(c).geometry().measure().volume());
		var source = new Fraction[localTargets.size()];
		for (int group = 0; group < source.length; group++) source[group] = localTargets.get(group).subtract(represented[group]);
		var difference = new ArrayList<Fraction[]>();
		for (var leaf : leaves) { var values = new Fraction[leaf.phases().size()]; Arrays.fill(values, Fraction.ZERO); difference.add(values); }
		if (Arrays.stream(source).allMatch(value -> value.signum() == 0)) return new PartitionResult(List.copyOf(leaves), difference.stream().map(List::of).toList());
		var mesh = new LiquidControlVolumeMesh(cells, 1, 1, (c, surface) -> new LiquidControlVolumeMesh.Boundary(true, 0));
		var selected = new ArrayList<Integer>();
		for (int f = 0; f < mesh.faces.size(); f++) {
			var face = mesh.faces.get(f);
			if (face.second() >= 0 && owners.get(face.first()).equals(owners.get(face.second()))
					&& !groups.get(face.first()).equals(groups.get(face.second()))) selected.add(f);
		}
		var centers = new ArrayList<Vertex>(); var changed = new boolean[mesh.cells.size()];
		for (var polygon : mesh.exactPolygons) centers.add(LiquidExactPolygon.interiorVertex(polygon.vertices()));
		var transfer = LiquidJointVolumeFlux.solve(mesh, cells, owners, groups, leaves, selected, source, centers);
		for (int e = 0; e < selected.size(); e++) if (transfer[e].signum() != 0) {
			var magnitude = transfer[e].signum() > 0 ? transfer[e] : transfer[e].negate();
			int f = selected.get(e); var face = mesh.faces.get(f); var area = face.areaVector().toArray(Fraction[]::new);
			for (int cell : new int[]{face.first(), face.second()}) {
				var bound = difference.get(owners.get(cell)); int phase = localPhases.get(cell); bound[phase] = bound[phase].add(magnitude);
			}
			var factor = transfer[e].multiply(Fraction.of(3)).divide(dot(area, area)); var point = centers.get(f).coordinates();
			for (int a = 0; a < 3; a++) point[a] = point[a].add(area[a].multiply(factor));
			var vertex = LiquidExactPolygon.vertex(point);
			for (var plane : LiquidExactPolygon.faces(leaves.get(owners.get(face.first())).open().geometry())) if (vertex.distance(plane.coefficients()).signum() > 0)
				throw new LiquidInterfaceResolutionRequired(new int[]{owners.get(face.first())}, "Joint phase correction leaves its open cell");
			centers.set(f, vertex); changed[face.first()] = changed[face.second()] = true;
		}
		var output = new ArrayList<ArrayList<ArrayList<LiquidPolyhedron>>>();
		for (var leaf : leaves) { var phases = new ArrayList<ArrayList<LiquidPolyhedron>>(); for (var phase : leaf.phases()) phases.add(new ArrayList<>()); output.add(phases); }
		int regions = 0;
		for (int c = 0; c < cells.size(); c++) {
			var result = output.get(owners.get(c)).get(localPhases.get(c));
			if (!changed[c]) { result.add(cells.get(c).geometry()); regions++; }
			else {
				var center = LiquidExactPolygon.interiorVertex(LiquidExactPolygon.faces(cells.get(c).geometry()).stream().flatMap(face -> face.vertices().stream()).distinct().toList());
				var surfaces = new ArrayList<LiquidExactPolygon>();
				for (int f : mesh.cellFaces[c]) {
					var polygon = mesh.faces.get(f).first() == c ? mesh.exactPolygons.get(f) : mesh.exactPolygons.get(f).reversed();
					var original = LiquidExactPolygon.interiorVertex(polygon.vertices()); var next = centers.get(f);
					if (original.equals(next)) { surfaces.add(polygon); continue; }
					for (int e = 0; e < polygon.vertices().size(); e++) {
						var a = polygon.vertices().get(e); var b = polygon.vertices().get((e + 1) % polygon.vertices().size());
						var old = List.of(center, original, a, b); var moved = List.of(center, next, a, b);
						if (LiquidExactPolygon.homogeneousTetrahedronDeterminant(old).signum() != LiquidExactPolygon.homogeneousTetrahedronDeterminant(moved).signum())
							throw new LiquidInterfaceResolutionRequired(new int[]{owners.get(c)}, "Joint phase correction exceeds its positive spatial support");
						surfaces.add(LiquidExactPolygon.triangle(next, a, b));
					}
				}
				var vertices = surfaces.stream().flatMap(face -> face.vertices().stream()).distinct().toList();
				boolean convex = surfaces.stream().allMatch(face -> vertices.stream().allMatch(vertex -> vertex.distance(face.coefficients()).signum() <= 0));
				if (convex) { result.add(LiquidPolyhedron.restoredExact(surfaces)); regions++; }
				else {
					var originalFaces = new ArrayList<LiquidExactPolygon>(); var movedCenters = new ArrayList<Vertex>();
					for (int f : mesh.cellFaces[c]) {
						var polygon = mesh.faces.get(f).first() == c ? mesh.exactPolygons.get(f) : mesh.exactPolygons.get(f).reversed();
						originalFaces.add(polygon); movedCenters.add(centers.get(f));
					}
					var partition = LiquidSharedFaceVolumeGeometry.partition(cells.get(c).geometry(), originalFaces, movedCenters, maximumRegions - regions);
					result.addAll(partition); regions += partition.size();
				}
			}
			if (regions > maximumRegions) throw new IllegalStateException("Joint phase correction exceeds its region budget");
		}
		var result = new ArrayList<LiquidMomentReconstruction.Leaf>(); var finalVolumes = new LinkedHashMap<Integer, Fraction>();
		for (int l = 0; l < leaves.size(); l++) {
			var leaf = leaves.get(l); var phases = new ArrayList<LiquidMomentReconstruction.Phase>();
			for (int p = 0; p < leaf.phases().size(); p++) {
				int phase = leaf.phases().get(p).control(); var pieces = output.get(l).get(p); var measured = LiquidVolumeInterface.measure(pieces).volume();
				if (!measured.equals(localVolumes.get(l).get(phase))) throw new IllegalStateException("Joint phase correction lost a local volume constraint");
				finalVolumes.merge(phase, measured, Fraction::add);
				phases.add(new LiquidMomentReconstruction.Phase(phase, pieces));
			}
			result.add(new LiquidMomentReconstruction.Leaf(leaf.open(), phases));
		}
		if (!finalVolumes.equals(volume)) throw new IllegalStateException("Joint phase correction lost an exact material volume constraint");
		// Each shifted planar face bounds one pyramid of volume |transfer|. Their union bounds the occupied-space change.
		return new PartitionResult(List.copyOf(result), difference.stream().map(List::of).toList());
	}
	private static Fraction dot(Fraction[] a, Fraction[] b) { var sum = Fraction.ZERO; for (int i = 0; i < 3; i++) sum = sum.add(a[i].multiply(b[i])); return sum; }
}
