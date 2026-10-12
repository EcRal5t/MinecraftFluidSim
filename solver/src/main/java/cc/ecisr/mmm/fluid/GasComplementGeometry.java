package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.units.SignedDyadic;
import cc.ecisr.mmm.chemistry.ExactRational;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** Immutable positive gas complement of the admitted PLIC and fixed open microboxes, in SI units. */
final class GasComplementGeometry {
	/** Stable microbox key; normalizedVolume is the exact fraction of one whole cell, volume is its SI observation. */
	record Piece(int id, int cell, int micro, double volume, ExactRational normalizedVolume,
	             GasGeometryOverlap.HalfSpace dryPlane) { }
	/** Oriented along the positive axis; a fixed cut restriction retains finite-rate chamber transport. */
	record Port(int first, int second, int axis, double area, boolean fullCutConnection) { }
	record ExteriorPort(int piece, int axis, int sign, double area) { }

	private static final ExactRational MICRO_VOLUME = ExactRational.ONE.divide(8);
	private final int sizeX, sizeY, sizeZ;
	private final double cellVolumeCubicMeters;
	private final byte[] collisionMasks;
	private final Piece[] byId;
	private final List<Piece> pieces;
	private final List<Port> ports;
	private final List<ExteriorPort> exteriorPorts;

	private GasComplementGeometry(int sizeX, int sizeY, int sizeZ, double cellVolumeCubicMeters, byte[] collisionMasks, Piece[] byId,
			List<Piece> pieces, List<Port> ports, List<ExteriorPort> exteriorPorts) {
		this.sizeX = sizeX; this.sizeY = sizeY; this.sizeZ = sizeZ;
		this.cellVolumeCubicMeters = cellVolumeCubicMeters;
		this.collisionMasks = collisionMasks.clone();
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
		ExactRational physicalScale = exact(SignedDyadic.of(h3));
		var byId = new Piece[Math.multiplyExact(count, 8)];
		var pieces = new ArrayList<Piece>();
		for (int cell = 0; cell < count; cell++) {
			if (phase.collisionMask(cell) != cut.mask(cell))
				throw new IllegalArgumentException("Gas complement PLIC collision geometry differs at cell " + cell);
			if (solid[cell]) continue;
			for (int slot = 0; slot < 8; slot++) {
				if (!open(cut, cell, slot)) continue;
				double fraction = phase.liquidFractionExact(cell,slot);
				double nx = phase.planeNormal(cell,slot,0), ny = phase.planeNormal(cell,slot,1), nz = phase.planeNormal(cell,slot,2);
				double offset = phase.planeOffset(cell,slot);
				if (!(fraction >= 0 && fraction <= 1) || !Double.isFinite(nx) || !Double.isFinite(ny)
						|| !Double.isFinite(nz) || !Double.isFinite(offset)) {
					throw new IllegalArgumentException("Gas complement PLIC is invalid at cell " + cell);
				}
				if (fraction == 1) continue;
				if (fraction > 0 && nx == 0 && ny == 0 && nz == 0) {
					throw blocked("Partial PLIC has no normal", cell);
				}
				GasGeometryOverlap.HalfSpace plane = fraction == 0 ? null : new GasGeometryOverlap.HalfSpace(
						SignedDyadic.of(nx), SignedDyadic.of(ny), SignedDyadic.of(nz), SignedDyadic.of(offset));
				ExactRational normalizedVolume = plane == null ? MICRO_VOLUME : MicroBoxPlaneVolume.dryVolume(slot, plane);
				if (normalizedVolume.isZero()) continue;
				double volume = normalizedVolume.multiply(physicalScale).toDouble();
				if (!(volume > 0 && Double.isFinite(volume))) throw blocked("Positive gas piece volume is not representable", cell);
				Piece piece = new Piece(8 * cell + slot, cell, slot, volume, normalizedVolume, plane);
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
		return new GasComplementGeometry(sizeX, sizeY, sizeZ, h3, cut.copyMicroMasks(), byId, pieces, ports, exterior);
	}

	private static ExactRational exact(SignedDyadic value) {
		return value.exponent() >= 0
				? new ExactRational(value.significand().shiftLeft(value.exponent()), BigInteger.ONE)
				: new ExactRational(value.significand(), BigInteger.ONE.shiftLeft(-value.exponent()));
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

	double cellVolumeCubicMeters() { return cellVolumeCubicMeters; }
	int collisionMask(int cell) { return Byte.toUnsignedInt(collisionMasks[cell]); }
	int sizeX() { return sizeX; }
	int sizeY() { return sizeY; }
	int sizeZ() { return sizeZ; }
	Piece piece(int id) { return byId[id]; }
	List<Piece> pieces() { return pieces; }
	List<Port> ports() { return ports; }
	List<ExteriorPort> exteriorPorts() { return exteriorPorts; }
}
