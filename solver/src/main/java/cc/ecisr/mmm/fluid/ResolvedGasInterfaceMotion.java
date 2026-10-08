package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import cc.ecisr.mmm.fluid.air.GasGeometryState;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Gas contacts follow material surfaces through the same exact liquid characteristic maps. */
final class ResolvedGasInterfaceMotion {
	record Patch(int previousNode, LiquidExactPolygon surface) { }
	final List<Patch> patches;
	final GasGeometryState previous;

	private ResolvedGasInterfaceMotion(GasGeometryState previous, List<Patch> patches) { this.previous = previous; this.patches = List.copyOf(patches); }
	static final class Builder {
		private final int[] previousNodes;
		private final LiquidControlVolumeMesh mesh;
		private final int[] fragments;
		private final int[] contactNodes;
		private final int maximumPieces;
		private final GasGeometryState previous;
		private final ArrayList<Patch> patches = new ArrayList<>();
		Builder(LiquidGeometryPhaseInput input, GasGeometryState previous, int maximumPieces, int maximumPairs) {
			this(ResolvedGasGeometry.match(previous, input, maximumPairs), maximumPieces);
		}
		Builder(ResolvedGasGeometry.Match match, int maximumPieces) {
			this(match, null, maximumPieces);
		}
		Builder(ResolvedGasGeometry.Match match, LiquidMechanicalFaceInput mechanical, int maximumPieces) {
			var input = match.input(); var previous = match.saved();
			if (previous == null || maximumPieces < 1) throw new IllegalArgumentException("Incomplete resolved gas contact labels");
			this.previous = previous; this.maximumPieces = maximumPieces;
			previousNodes = match.nodes();
			mesh = mechanical == null ? input.mesh : mechanical.mesh;
			fragments = mechanical == null ? input.fragments : mechanical.fragments;
			contactNodes = mechanical == null ? null : mechanical.contactNodes;
		}
		void requireInput(LiquidControlVolumeMesh mesh, int[] fragments) {
			if (mesh != this.mesh || !java.util.Arrays.equals(fragments, this.fragments))
				throw new IllegalArgumentException("Gas surface transport refers to another phase input");
		}
		void collect(int c, List<LiquidCharacteristicTransport.Image> images) {
			if (fragments[c] < 0) throw new IllegalArgumentException("Gas contact transport requires liquid support");
			for (int f : mesh.cellFaces[c]) {
				var face = mesh.faces.get(f); int neighbor = face.first() == c ? face.second() : face.first();
				if (neighbor == -2 || neighbor >= 0 && fragments[neighbor] >= 0) continue;
				int node = contactNodes == null ? neighbor < 0 ? 0 : previousNodes[neighbor] : contactNodes[f];
				if (node < 0) throw new IllegalArgumentException("Gas contact has no chamber label");
				var surface = mesh.exactPolygons.get(f); if (face.first() != c) surface = surface.reversed();
				ResolvedGasInterfaceMotion.collect(patches, node, surface, images, maximumPieces);
			}
		}
		ResolvedGasInterfaceMotion finish() { return new ResolvedGasInterfaceMotion(previous, patches); }
		void collect(LiquidConformingTransport motion) {
			if (motion.mesh != mesh || !java.util.Arrays.equals(motion.liquid, fragments))
				throw new IllegalArgumentException("Continuous gas transport refers to another phase input");
			for (var surface : motion.surfaces) {
				int c = surface.cell(), f = surface.face(); var face = mesh.faces.get(f);
				if (fragments[c] < 0) throw new IllegalArgumentException("Continuous gas surface requires liquid support");
				int neighbor = face.first() == c ? face.second() : face.first();
				if (neighbor == -2 || neighbor >= 0 && fragments[neighbor] >= 0) continue;
				int node = contactNodes == null ? neighbor < 0 ? 0 : previousNodes[neighbor] : contactNodes[f];
				if (patches.size() == maximumPieces) throw new IllegalStateException("Gas interface motion exceeds its surface budget");
				patches.add(new Patch(node, surface.geometry()));
			}
		}
	}

	private static void collect(ArrayList<Patch> patches, int node, LiquidExactPolygon surface,
			List<LiquidCharacteristicTransport.Image> images, int maximumPieces) {
		var area = new Fraction[]{Fraction.ZERO, Fraction.ZERO, Fraction.ZERO};
		for (var image : images) {
			var part = surface.clippedTo(image.source()); if (!part.hasArea()) continue;
			var vector = part.areaVector(); for (int a = 0; a < 3; a++) area[a] = area[a].add(vector[a]);
			if (patches.size() == maximumPieces) throw new IllegalStateException("Gas interface motion exceeds its surface budget");
			patches.add(new Patch(node, image.transform().apply(part)));
		}
		if (!java.util.Arrays.equals(area, surface.areaVector())) throw new IllegalStateException("Characteristic preimages lost gas contact area");
	}

	/** Old chamber labels on each successor's actual gas contacts; stock repartition remains separate. */
	List<Set<Integer>> contacts(LiquidGeometryPhaseInput next, int[] nextNodes, int nextNodeCount, int maximumPairs) {
		if (nextNodes.length != next.fragments.length || nextNodeCount < 0 || maximumPairs < 0)
			throw new IllegalArgumentException("Invalid gas interface successor");
		var byPlane = new HashMap<LiquidGeometryPlane.Key, ArrayList<Patch>>();
		for (var patch : patches) byPlane.computeIfAbsent(patch.surface.plane().key(), ignored -> new ArrayList<>()).add(patch);
		var result = new ArrayList<TreeSet<Integer>>(); for (int n = 0; n <= nextNodeCount; n++) result.add(new TreeSet<>());
		int pairs = 0;
		for (int f = 0; f < next.mesh.faces.size(); f++) {
			var face = next.mesh.faces.get(f); boolean firstLiquid = next.fragments[face.first()] >= 0;
			boolean secondLiquid = face.second() >= 0 && next.fragments[face.second()] >= 0;
			if (face.second() == -2 || firstLiquid == secondLiquid) continue;
			int gas = firstLiquid ? face.second() : face.first(), node = gas < 0 ? 0 : nextNodes[gas];
			if (node < 0 || node > nextNodeCount) throw new IllegalArgumentException("Gas successor has no contact chamber");
			var surface = next.mesh.exactPolygons.get(f); if (!firstLiquid) surface = surface.reversed();
			var candidates = byPlane.get(surface.plane().key()); if (candidates == null) throw new IllegalStateException("Gas successor contact lacks transported material support");
			var covered = new Fraction[]{Fraction.ZERO, Fraction.ZERO, Fraction.ZERO};
			for (var patch : candidates) {
				if (pairs++ == maximumPairs) throw new IllegalStateException("Gas interface matching exceeds its pair budget");
				if (surface.plane().positive() != patch.surface.plane().positive()) continue;
				var intersection = surface.intersection(patch.surface); if (!intersection.hasArea()) continue;
				result.get(node).add(patch.previousNode); var vector = intersection.areaVector();
				for (int a = 0; a < 3; a++) covered[a] = covered[a].add(vector[a]);
			}
			if (!java.util.Arrays.equals(covered, surface.areaVector())) throw new IllegalStateException("Gas successor contact has incomplete transported area");
		}
		return result.stream().map(Set::copyOf).toList();
	}
}
