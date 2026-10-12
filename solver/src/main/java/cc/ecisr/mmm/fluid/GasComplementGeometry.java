package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.List;

/** Immutable positive gas complement of the admitted PLIC and fixed open microboxes, in SI units. */
final class GasComplementGeometry {
	/** Stable geometric key: eight times cell ordinal plus its microbox slot. */
	record Piece(int id, int cell, int micro, double volume) { }
	/** Oriented along the positive axis; a fixed cut restriction retains finite-rate chamber transport. */
	record Port(int first, int second, int axis, double area, boolean fullCutConnection) { }
	record ExteriorPort(int piece, int axis, int sign, double area) { }

	private final int sizeX, sizeY, sizeZ;
	private final Piece[] byId;
	private final List<Piece> pieces;
	private final List<Port> ports;
	private final List<ExteriorPort> exteriorPorts;

	private GasComplementGeometry(int sizeX, int sizeY, int sizeZ, Piece[] byId,
			List<Piece> pieces, List<Port> ports, List<ExteriorPort> exteriorPorts) {
		this.sizeX = sizeX; this.sizeY = sizeY; this.sizeZ = sizeZ;
		this.byId = byId;
		this.pieces = List.copyOf(pieces);
		this.ports = List.copyOf(ports);
		this.exteriorPorts = List.copyOf(exteriorPorts);
	}

	static GasComplementGeometry capture(int sizeX, int sizeY, int sizeZ, double cellSize,
			boolean[] solid, FlipCutCellGeometry cut, FlipPressurePhaseGeometry phase) {
		if (sizeX < 2 || sizeY < 2 || sizeZ < 2 || !(cellSize > 0) || !Double.isFinite(cellSize)
				|| solid == null || cut == null || phase == null) {
			throw new IllegalArgumentException("Gas complement dimensions, metric and geometry are required");
		}
		int count = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
		if (solid.length != count || !cut.matchesShape(sizeX, sizeY, sizeZ) || !phase.matchesShape(sizeX, sizeY, sizeZ)) {
			throw new IllegalArgumentException("Gas complement geometry shapes differ");
		}
		double h2 = cellSize * cellSize, h3 = h2 * cellSize;
		if (!(h2 > 0 && h3 > 0 && Double.isFinite(h2) && Double.isFinite(h3))) {
			throw new IllegalArgumentException("Gas complement SI area and volume must be representable");
		}
		var byId = new Piece[Math.multiplyExact(count, 8)];
		var pieces = new ArrayList<Piece>();
		for (int cell = 0; cell < count; cell++) {
			double fraction = phase.liquidFractionExact(cell);
			double nx = phase.planeNormal(cell, 0), ny = phase.planeNormal(cell, 1), nz = phase.planeNormal(cell, 2);
			double offset = phase.planeOffset(cell);
			if (!(fraction >= 0 && fraction <= 1) || !Double.isFinite(nx) || !Double.isFinite(ny)
					|| !Double.isFinite(nz) || !Double.isFinite(offset)) {
				throw new IllegalArgumentException("Gas complement PLIC is invalid at cell " + cell);
			}
			if (solid[cell] || fraction == 1) continue;
			if (fraction > 0 && nx == 0 && ny == 0 && nz == 0) {
				throw blocked("Partial PLIC has no normal", cell);
			}
			SignedDyadic[] normal = {SignedDyadic.of(nx), SignedDyadic.of(ny), SignedDyadic.of(nz)};
			SignedDyadic threshold = SignedDyadic.of(offset);
			for (int slot = 0; slot < 8; slot++) {
				if (!open(cut, cell, slot)) continue;
				double unitVolume = fraction == 0 ? 1 : dryMicroVolume(cell, slot, normal, threshold);
				if (unitVolume == 0) continue;
				double volume = SignedDyadic.of(unitVolume).multiply(SignedDyadic.of(.125)).multiply(SignedDyadic.of(h3)).toDouble();
				if (!(volume > 0 && Double.isFinite(volume))) throw blocked("Positive gas piece volume is not representable", cell);
				Piece piece = new Piece(8 * cell + slot, cell, slot, volume);
				byId[piece.id()] = piece; pieces.add(piece);
			}
		}
		var ports = new ArrayList<Port>();
		var exterior = new ArrayList<ExteriorPort>();
		int[] sizes = {sizeX, sizeY, sizeZ}, strides = {1, sizeX, sizeX * sizeY};
		for (Piece piece : pieces) {
			int cell = piece.cell(), slot = piece.micro();
			int[] coordinate = {cell % sizeX, cell / sizeX % sizeY, cell / (sizeX * sizeY)};
			for (int axis = 0; axis < 3; axis++) {
				int bit = slot >>> axis & 1;
				double tangent = .5 * (slot >>> ((axis + 1) % 3) & 1);
				double other = .5 * (slot >>> ((axis + 2) % 3) & 1);
				if (bit == 0) {
					Piece neighbor = byId[8 * cell + (slot | 1 << axis)];
					if (neighbor != null) addPort(ports, piece, neighbor, axis,
							GasFaceIntersection.area(phase, cell, -1, axis, .5, 0, tangent, other, .5), h2, true);
					if (coordinate[axis] == 0) addExterior(exterior, piece, axis, -1,
							GasFaceIntersection.area(phase, cell, -1, axis, 0, 0, tangent, other, .5), h2);
				} else if (coordinate[axis] + 1 < sizes[axis]) {
					int peer = cell + strides[axis];
					Piece neighbor = byId[8 * peer + (slot ^ 1 << axis)];
					if (neighbor != null) addPort(ports, piece, neighbor, axis,
							GasFaceIntersection.area(phase, cell, peer, axis, 1, 0, tangent, other, .5), h2,
							fullCutFace(cut, solid, cell, peer, axis));
				} else addExterior(exterior, piece, axis, 1,
						GasFaceIntersection.area(phase, cell, -1, axis, 1, 0, tangent, other, .5), h2);
			}
		}
		return new GasComplementGeometry(sizeX, sizeY, sizeZ, byId, pieces, ports, exterior);
	}

	private static double dryMicroVolume(int cell, int slot, SignedDyadic[] normal, SignedDyadic threshold) {
		SignedDyadic origin = SignedDyadic.ZERO, minimum = SignedDyadic.ZERO, maximum = SignedDyadic.ZERO;
		for (int axis = 0; axis < 3; axis++) {
			SignedDyadic start = normal[axis].multiply(SignedDyadic.of(.5 * (slot >>> axis & 1)));
			SignedDyadic extent = normal[axis].multiply(SignedDyadic.of(.5));
			origin = origin.add(start);
			minimum = minimum.add(start).add(extent.signum() < 0 ? extent : SignedDyadic.ZERO);
			maximum = maximum.add(start).add(extent.signum() > 0 ? extent : SignedDyadic.ZERO);
		}
		// Equality at the maximum has no positive 3D support; equality at the minimum is full dry volume.
		if (maximum.compareTo(threshold) <= 0) return 0;
		if (minimum.compareTo(threshold) >= 0) return 1;
		var integral = new double[4];
		LiquidFluxGeometry.unitCubeMoments(-.5 * normal[0].toDouble(), -.5 * normal[1].toDouble(),
				-.5 * normal[2].toDouble(), origin.subtract(threshold).toDouble(), integral);
		if (!(integral[0] > 0 && integral[0] <= 1 && Double.isFinite(integral[0]))) {
			throw blocked("Positive gas support has no representable clipped volume", cell);
		}
		return integral[0];
	}

	private static boolean open(FlipCutCellGeometry cut, int cell, int slot) {
		return cut.microCellOpen(cell, slot & 1, slot >>> 1 & 1, slot >>> 2 & 1);
	}
	private static boolean fullCutFace(FlipCutCellGeometry cut, boolean[] solid, int minus, int plus, int axis) {
		if (solid[minus] || solid[plus]) return false;
		for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++) {
			int base = a << ((axis + 1) % 3) | b << ((axis + 2) % 3);
			if (!open(cut, minus, base | 1 << axis) || !open(cut, plus, base)) return false;
		}
		return true;
	}
	private static void addPort(List<Port> ports, Piece first, Piece second, int axis,
			double normalizedArea, double h2, boolean fullCut) {
		double area = physicalArea(normalizedArea, h2, first.cell());
		if (area > 0) ports.add(new Port(first.id(), second.id(), axis, area, fullCut));
	}
	private static void addExterior(List<ExteriorPort> ports, Piece piece, int axis, int sign,
			double normalizedArea, double h2) {
		double area = physicalArea(normalizedArea, h2, piece.cell());
		if (area > 0) ports.add(new ExteriorPort(piece.id(), axis, sign, area));
	}
	private static double physicalArea(double normalizedArea, double h2, int cell) {
		if (normalizedArea == 0) return 0;
		double result = SignedDyadic.of(normalizedArea).multiply(SignedDyadic.of(h2)).toDouble();
		if (!(result > 0 && Double.isFinite(result))) throw blocked("Positive gas port area is not representable", cell);
		return result;
	}
	private static GasTopologyBlockedException blocked(String message, int cell) {
		return new GasTopologyBlockedException(message + " at cell " + cell);
	}

	int sizeX() { return sizeX; }
	int sizeY() { return sizeY; }
	int sizeZ() { return sizeZ; }
	Piece piece(int id) { return byId[id]; }
	List<Piece> pieces() { return pieces; }
	List<Port> ports() { return ports; }
	List<ExteriorPort> exteriorPorts() { return exteriorPorts; }
}
