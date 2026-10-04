package cc.ecisr.mmm.fluid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Face-event integration of one shared RT velocity field; cell connectivity carries no force switch. */
final class LiquidVelocityTrajectory {
	record Result(LiquidPolyhedron.Point position, int cell, int faceEvents) { }
	static final class Piece {
		final int cell;
		final LiquidControlVolumeVelocity.Tetrahedron tetra;
		final LiquidPolyhedron geometry;
		final LiquidPolyhedron.Surface[] surfaces = new LiquidPolyhedron.Surface[4];
		final LiquidPolyhedron.Point[] faceOrigins = new LiquidPolyhedron.Point[4];
		final Piece[] neighbor = new Piece[4];
		final double[] normalVelocity = new double[4];
		private LiquidExactPolygon.Fraction[] characteristicVelocity;
		LiquidExactPolygon.Fraction[] velocity() {
			if (characteristicVelocity == null) characteristicVelocity = LiquidCharacteristicVelocity.reconstruct(tetra.geometry(), tetra.outwardFlux());
			return characteristicVelocity;
		}
		Piece(int cell, LiquidControlVolumeVelocity.Tetrahedron tetra) {
			this.cell = cell; this.tetra = tetra; geometry = tetra.geometry().geometry();
			for (int f = 0; f < 4; f++) {
				var polygon = tetra.geometry().faces().get(f); var surface = polygon.surface();
				surfaces[f] = surface; faceOrigins[f] = polygon.rounded().getFirst(); normalVelocity[f] = tetra.outwardFlux().get(f).toDouble() / surface.area();
			}
		}
	}
	private record Side(Piece piece, int face) { }
	private final LiquidControlVolumeMesh mesh;
	private final List<List<Piece>> cells;
	private final List<List<Piece>> boundaryFaces;

	LiquidVelocityTrajectory(LiquidControlVolumeMesh mesh, LiquidControlVolumeVelocity.Result[] velocity) {
		if (velocity.length != mesh.cells.size()) throw new IllegalArgumentException("Incomplete liquid velocity field");
		this.mesh = mesh; var cells = new ArrayList<List<Piece>>(); var faces = new ArrayList<List<Piece>>();
		for (int f = 0; f < mesh.faces.size(); f++) faces.add(new ArrayList<>());
		for (int c = 0; c < velocity.length; c++) {
			var pieces = new ArrayList<Piece>(); var unmatched = new HashMap<List<LiquidExactPolygon.Vertex>, Side>();
			for (var tetra : velocity[c].tetrahedra()) {
				var piece = new Piece(c, tetra); pieces.add(piece); faces.get(tetra.boundaryFace()).add(piece);
				for (int f = 1; f < 4; f++) {
					var key = tetra.geometry().faces().get(f).vertexKey(); var other = unmatched.remove(key);
					if (other == null) unmatched.put(key, new Side(piece, f));
					else { piece.neighbor[f] = other.piece; other.piece.neighbor[other.face] = piece; }
				}
			}
			if (!unmatched.isEmpty()) throw new IllegalArgumentException("Liquid velocity field has an open tetrahedral face");
			cells.add(List.copyOf(pieces));
		}
		for (int f = 0; f < mesh.faces.size(); f++) if (mesh.faces.get(f).second() >= 0) {
			var unmatched = new HashMap<List<LiquidExactPolygon.Vertex>, Piece>();
			for (var piece : faces.get(f)) {
				var key = piece.tetra.geometry().faces().getFirst().vertexKey(); var other = unmatched.remove(key);
				if (other == null) unmatched.put(key, piece);
				else { piece.neighbor[0] = other; other.neighbor[0] = piece; }
			}
			if (!unmatched.isEmpty()) throw new IllegalArgumentException("Shared velocity face has an incomplete triangle arrangement");
		}
		this.cells = List.copyOf(cells); this.boundaryFaces = List.copyOf(faces);
	}
	List<Piece> pieces(int cell) { return cells.get(cell); }
	LiquidControlVolumeMesh mesh() { return mesh; }

	Result advance(int cell, LiquidPolyhedron.Point position, double dt, int maximumFaceEvents) {
		if (!(dt >= 0) || !Double.isFinite(dt) || maximumFaceEvents <= 0) throw new IllegalArgumentException("Invalid liquid trajectory request");
		Piece piece = containing(cells.get(cell), cell, position); double remaining = dt; int events = 0;
		while (remaining > 0) {
			int exit = -1; double time = remaining;
			for (int f = 0; f < 4; f++) if (piece.normalVelocity[f] > 0) {
				var surface = piece.surfaces[f];
				double distance = -surface.areaVector().dot(position.subtract(piece.faceOrigins[f])) / surface.area();
				// A point on a submitted face may differ by the rounding of its coordinate conversion.
				if (distance < 0) {
					if (-distance > pointError(position, piece.faceOrigins[f])) throw new IllegalStateException("Liquid trajectory left its velocity support");
					distance = 0;
				}
				double ratio = distance / piece.normalVelocity[f], gradient = piece.tetra.isotropicGradient();
				double crossing = gradient == 0 ? ratio : -Math.log1p(-gradient * ratio) / gradient;
				if (Double.isFinite(crossing) && crossing >= 0 && crossing < time) { exit = f; time = crossing; }
			}
			position = characteristic(piece.tetra, position, time); remaining -= time;
			if (exit < 0) break;
			if (++events > maximumFaceEvents) throw new IllegalStateException("Liquid trajectory exceeds its face-event work budget");
			Piece next = piece.neighbor[exit];
			if (next == null) {
				var face = mesh.faces.get(piece.tetra.boundaryFace());
				int target = face.first() == piece.cell ? face.second() : face.first();
				if (target < 0) throw new IllegalStateException("Liquid trajectory requires velocity support beyond the phase interface");
				next = containing(boundaryFaces.get(piece.tetra.boundaryFace()), target, position);
			}
			piece = next;
		}
		return new Result(position, piece.cell, events);
	}

	private static Piece containing(List<Piece> pieces, int cell, LiquidPolyhedron.Point point) {
		for (var piece : pieces) if (piece.cell == cell) {
			boolean inside = true;
			for (int f = 0; f < 4; f++) inside &= piece.surfaces[f].areaVector().dot(point.subtract(piece.faceOrigins[f]))
					<= piece.surfaces[f].area() * pointError(point, piece.faceOrigins[f]);
			if (inside) return piece;
		}
		throw new IllegalArgumentException("Liquid position has no control-volume velocity support");
	}
	private static LiquidPolyhedron.Point characteristic(LiquidControlVolumeVelocity.Tetrahedron tetra, LiquidPolyhedron.Point point, double dt) {
		double gradient = tetra.isotropicGradient(); double factor = gradient == 0 ? dt : Math.expm1(gradient * dt) / gradient;
		var velocity = tetra.sample(point);
		return new LiquidPolyhedron.Point(Math.fma(factor, velocity.x(), point.x()), Math.fma(factor, velocity.y(), point.y()), Math.fma(factor, velocity.z(), point.z()));
	}
	private static double pointError(LiquidPolyhedron.Point first, LiquidPolyhedron.Point second) {
		double error = 0; for (int a = 0; a < 3; a++) error += Math.ulp(first.coordinate(a)) + Math.ulp(second.coordinate(a));
		return 32 * error;
	}
	}
