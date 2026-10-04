package cc.ecisr.mmm.fluid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import cc.ecisr.mmm.units.SignedDyadic;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;

/** Equal exact RT coefficients coalesce within a convex cell without changing the velocity field. */
final class LiquidVelocityRegions {
	record Face(LiquidExactPolygon polygon, Region neighbor) { }
	static final class Region {
		final LiquidConvexRegion geometry;
		final ArrayList<Face> faces = new ArrayList<>();
		private final Fraction[] velocity;
		Region(LiquidConvexRegion geometry, Fraction[] velocity) { this.geometry = geometry; this.velocity = velocity; }
		Fraction[] velocity() {
			if (velocity == null) throw new IllegalStateException("Liquid motion requires incompressible velocity support at finer resolution"); return velocity;
		}
	}
	private final List<List<Region>> cells;
	LiquidVelocityRegions(LiquidVelocityTrajectory field) {
		var mesh = field.mesh(); var cells = new ArrayList<List<Region>>(); var regions = new IdentityHashMap<LiquidVelocityTrajectory.Piece, Region>();
		var coarse = new boolean[mesh.cells.size()];
		for (int c = 0; c < coarse.length; c++) {
			var pieces = field.pieces(c); var values = new ArrayList<Fraction[]>();
			for (var piece : pieces) values.add(incompressible(piece) ? piece.velocity() : null);
			var reference = values.getFirst(); coarse[c] = reference != null && values.stream().allMatch(v -> Arrays.equals(v, reference));
			var local = new ArrayList<Region>();
			if (coarse[c]) {
				var geometry = LiquidConvexRegion.of(mesh.cells.get(c).geometry()); var measure = LiquidConvexRegion.EMPTY.measure();
				for (var piece : pieces) measure = measure.add(piece.tetra.geometry().measure());
				if (!measure.same(geometry.measure())) throw new IllegalStateException("Coalesced velocity support has incomplete exact cell geometry");
				var region = new Region(geometry, reference); local.add(region);
				for (var piece : pieces) regions.put(piece, region);
			} else for (int i = 0; i < pieces.size(); i++) {
				var region = new Region(pieces.get(i).tetra.geometry(), values.get(i)); local.add(region); regions.put(pieces.get(i), region);
			}
			cells.add(List.copyOf(local));
		}
		for (int c = 0; c < coarse.length; c++) {
			if (!coarse[c]) {
				for (var piece : field.pieces(c)) for (int f = 0; f < 4; f++)
					regions.get(piece).faces.add(new Face(piece.tetra.geometry().faces().get(f), regions.get(piece.neighbor[f])));
				continue;
			}
			var region = cells.get(c).getFirst();
			for (int f : mesh.cellFaces[c]) {
				var face = mesh.faces.get(f); int neighbor = face.first() == c ? face.second() : face.first();
				if (neighbor < 0 || coarse[neighbor]) {
					var polygon = mesh.exactPolygons.get(f); if (face.first() != c) polygon = polygon.reversed();
					region.faces.add(new Face(polygon, neighbor < 0 ? null : cells.get(neighbor).getFirst()));
				} else for (var piece : field.pieces(c)) if (piece.tetra.boundaryFace() == f)
					region.faces.add(new Face(piece.tetra.geometry().faces().getFirst(), regions.get(piece.neighbor[0])));
			}
		}
		this.cells = List.copyOf(cells);
	}
	List<Region> cells(int cell) { return cells.get(cell); }
	private static boolean incompressible(LiquidVelocityTrajectory.Piece piece) {
		var sum = SignedDyadic.ZERO; for (var rate : piece.tetra.outwardFlux()) sum = sum.add(rate); return sum.signum() == 0;
	}
}
