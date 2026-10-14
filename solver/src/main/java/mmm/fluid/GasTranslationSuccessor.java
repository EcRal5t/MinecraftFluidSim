package mmm.fluid;

import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Positive successor witnesses for one uniformly mixed chamber under a certified liquid translation. */
final class GasTranslationSuccessor {
	private static final SignedDyadic ONE = SignedDyadic.of(1), HALF = SignedDyadic.of(1, -1);
	private GasTranslationSuccessor() { }

	static Map<Long, List<ExactRational>> weights(FluidDomain domain, GasRuntimeGeometry old,
	                                            GasRuntimeGeometry next, SignedDyadic[] displacement) {
		if (domain == null || old == null || next == null || !old.sealedExterior() || !next.sealedExterior()
				|| old.nodeCount() != 1 || next.nodeCount() == 0 || displacement == null || displacement.length != 3
				|| !matches(domain, old) || !matches(domain, next)) return null;
		var inverse = new SignedDyadic[3];
		for (int a = 0; a < 3; a++) {
			if (displacement[a] == null || magnitude(displacement[a]).compareTo(ONE) > 0) return null;
			inverse[a] = displacement[a].negate();
		}
		for (int cell = 0; cell < domain.cellCount; cell++) {
			if (domain.geometry.cutCells.mask(cell) != 0 || old.geometry().collisionMask(cell) != 0
					|| next.geometry().collisionMask(cell) != 0 || !old.poreVolume(cell).equals(ONE)
					|| !next.poreVolume(cell).equals(ONE)) return null;
		}
		var raw = rawCapacity(old);
		if (raw.signum() <= 0 || !raw.equals(rawCapacity(next)) || !certifiedCapacity(old, raw)
				|| !certifiedCapacity(next, raw)) throw new GasTopologyBlockedException("Liquid translation gas capacities lack a common raw-volume certificate");
		var proven = new boolean[next.nodeCount() + 1];
		for (var future : next.geometry().pieces()) {
			int node = next.nodeOfPiece(future.id());
			if (node <= 0 || node >= proven.length) throw new GasTopologyBlockedException("Liquid translation names an invalid sealed gas successor");
			if (proven[node]) continue;
			var boxes = new ArrayList<LiquidCellShape.Box>(future.drySupports().size());
			for (var support : future.drySupports()) {
				var box = box(future.micro(), support);
				if (box == null) throw new GasTopologyBlockedException("Liquid translation gas successor lacks a positive box support");
				boxes.add(box);
			}
			var previous = old.geometry().piece(future.id());
			if (previous != null && old.nodeOfPiece(previous.id()) == 1
					&& GasGeometryOverlap.unionOverlapVolume(future.micro(), previous.drySupports(), future.drySupports()).signum() > 0) {
				proven[node] = true;
				continue;
			}
			for (var box : boxes) if (translatedWitness(domain, old, future.cell(), box, inverse)) {
				proven[node] = true;
				break;
			}
		}
		var result = new TreeMap<Long, List<ExactRational>>();
		for (var chamber : next.capacity().chambers()) {
			if (!proven[chamber.node()]) throw new GasTopologyBlockedException("Liquid translation gas successor lacks an original positive support: node=" + chamber.node());
			result.put(1L << 32 | chamber.node(), List.of(exact(SignedDyadic.of(chamber.cubicMeters()))));
		}
		return java.util.Collections.unmodifiableMap(result);
	}

	private static boolean matches(FluidDomain domain, GasRuntimeGeometry frame) {
		var geometry = frame.geometry();
		return geometry.sizeX() == domain.sizeX && geometry.sizeY() == domain.sizeY && geometry.sizeZ() == domain.sizeZ
				&& Float.floatToRawIntBits(frame.cellSize()) == Float.floatToRawIntBits(domain.config.cellSize())
				&& Double.doubleToRawLongBits(geometry.cellVolumeCubicMeters()) == Double.doubleToRawLongBits(
						(double) domain.config.cellSize() * domain.config.cellSize() * domain.config.cellSize());
	}
	private static SignedDyadic rawCapacity(GasRuntimeGeometry frame) {
		var result = SignedDyadic.ZERO;
		for (var component : frame.capacity().components()) result = result.add(component.availableVolumeNormalized());
		return result;
	}
	private static boolean certifiedCapacity(GasRuntimeGeometry frame, SignedDyadic raw) {
		if (frame.capacity().ambientCubicMeters() != 0) return false;
		var stored = SignedDyadic.ZERO; var bound = SignedDyadic.ZERO;
		for (var chamber : frame.capacity().chambers()) {
			stored = stored.add(SignedDyadic.of(chamber.cubicMeters()));
			bound = bound.add(SignedDyadic.of(chamber.roundingErrorBound()));
		}
		var target = raw.multiply(SignedDyadic.of(frame.geometry().cellVolumeCubicMeters()));
		return magnitude(stored.subtract(target)).compareTo(bound) <= 0;
	}
	private static LiquidCellShape.Box box(int micro, List<GasGeometryOverlap.HalfSpace> support) {
		var lower = new SignedDyadic[3]; var upper = new SignedDyadic[3];
		for (int a = 0; a < 3; a++) { lower[a] = SignedDyadic.of(micro >>> a & 1, -1); upper[a] = lower[a].add(HALF); }
		for (var plane : support) {
			SignedDyadic[] n = {plane.nx(), plane.ny(), plane.nz()}; int axis = -1;
			for (int a = 0; a < 3; a++) if (n[a].signum() != 0) {
				if (axis >= 0 || !magnitude(n[a]).equals(ONE)) return null;
				axis = a;
			}
			if (axis < 0) { if (plane.threshold().signum() > 0) return null; continue; }
			if (n[axis].signum() > 0) {
				if (plane.threshold().compareTo(lower[axis]) > 0) lower[axis] = plane.threshold();
			} else {
				var end = plane.threshold().negate(); if (end.compareTo(upper[axis]) < 0) upper[axis] = end;
			}
		}
		for (int a = 0; a < 3; a++) if (lower[a].compareTo(upper[a]) >= 0) return null;
		return new LiquidCellShape.Box(lower[0], lower[1], lower[2], upper[0], upper[1], upper[2]);
	}
	private static boolean translatedWitness(FluidDomain domain, GasRuntimeGeometry old, int futureCell,
	                                         LiquidCellShape.Box box, SignedDyadic[] inverse) {
		int x = futureCell % domain.sizeX, y = futureCell / domain.sizeX % domain.sizeY, z = futureCell / (domain.sizeX * domain.sizeY);
		for (var part : LiquidTranslationPartition.box(box, inverse)) {
			int px = x + part.dx(), py = y + part.dy(), pz = z + part.dz();
			if (px < 0 || px >= domain.sizeX || py < 0 || py >= domain.sizeY || pz < 0 || pz >= domain.sizeZ) continue;
			int cell = domain.cellIndex(px, py, pz); var lower = new SignedDyadic[3]; var upper = new SignedDyadic[3];
			for (int a = 0; a < 3; a++) {
				var centre = part.centroid(a).add(HALF); var radius = part.width(a).multiply(HALF);
				lower[a] = centre.subtract(radius); upper[a] = centre.add(radius);
			}
			var translated = LiquidShapeGeometry.planes(new LiquidCellShape.Box(lower[0], lower[1], lower[2], upper[0], upper[1], upper[2]));
			for (int micro = 0; micro < 8; micro++) {
				var piece = old.geometry().piece(8 * cell + micro);
				if (piece == null || old.nodeOfPiece(piece.id()) != 1) continue;
				for (var support : piece.drySupports()) {
					var planes = new ArrayList<>(translated); planes.addAll(support);
					if (GasGeometryOverlap.normalizedVolume(micro, planes).signum() > 0) return true;
				}
			}
		}
		return false;
	}
	private static SignedDyadic magnitude(SignedDyadic value) { return value.signum() < 0 ? value.negate() : value; }
	private static ExactRational exact(SignedDyadic value) { return PlicComponentGeometry.ratio(value, ONE); }
}
