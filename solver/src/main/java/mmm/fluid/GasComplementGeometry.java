package mmm.fluid;

import mmm.units.SignedDyadic;


import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.Collections;

/** Immutable positive gas complement of the admitted PLIC and fixed open microboxes, in SI units. */
final class GasComplementGeometry {
	/** Stable microbox key; normalizedVolume is the floating fraction of one whole cell, volume uses SI. */
    record Piece(int id, int cell, int micro, double volume, double normalizedVolume,
                 GasGeometryOverlap.HalfSpace dryPlane, PlicPlaneOffset.Result offsetBracket) { }
	/** Internal microfaces join one gas volume; complete fixed and dry cell faces join adjacent volumes. */
	record Port(int first, int second, int axis, double area, boolean fullCutConnection) { }
	record ExteriorPort(int piece, int axis, int sign, double area, boolean fullCutConnection) { }

	private static final double MICRO_VOLUME = .125;
	private final int sizeX, sizeY, sizeZ;
	private final double cellVolumeCubicMeters;
	private final byte[] collisionMasks;
	private final SignedDyadic[] poreVolumes;
	private final Piece[] byId;
	private final Port[] portArray;
	private final ExteriorPort[] exteriorArray;
	private final List<Piece> pieces;
	private final List<Port> ports;
	private final List<ExteriorPort> exteriorPorts;

	/** All arrays are owned snapshots; the public lists cannot modify their backing arrays. */
	private GasComplementGeometry(int sizeX, int sizeY, int sizeZ, double h3, byte[] masks,
	                              SignedDyadic[] poreVolumes, Piece[] byId, Piece[] pieces, Port[] ports,
	                              ExteriorPort[] exterior) {
		this.sizeX = sizeX; this.sizeY = sizeY; this.sizeZ = sizeZ;
		cellVolumeCubicMeters = h3;
		collisionMasks = masks; this.poreVolumes = poreVolumes; this.byId = byId;
		portArray = ports; exteriorArray = exterior;
		this.pieces = Collections.unmodifiableList(Arrays.asList(pieces));
		this.ports = Collections.unmodifiableList(Arrays.asList(ports));
		exteriorPorts = Collections.unmodifiableList(Arrays.asList(exterior));
	}

	static GasComplementGeometry capture(int sizeX, int sizeY, int sizeZ, double cellSize,
	                                    boolean[] solid, FlipCutCellGeometry cut, FlipPressurePhaseGeometry phase) {
		return capture(sizeX,sizeY,sizeZ,cellSize,solid,cut,phase,null,null);
	}
	/** One ordered scan reuses unchanged cells and incident faces without a separate region index. */
	static GasComplementGeometry capture(int sizeX, int sizeY, int sizeZ, double cellSize,
	                                    boolean[] solid, FlipCutCellGeometry cut, FlipPressurePhaseGeometry phase,
	                                    GasComplementGeometry source, boolean[] changed) {
		validateShape(sizeX, sizeY, sizeZ, solid, cut, phase);
		double h2 = cellSize * cellSize, h3 = h2 * cellSize;
		if (!(cellSize > 0 && h2 > 0 && h3 > 0 && Double.isFinite(h2) && Double.isFinite(h3)))
			throw new IllegalArgumentException("Gas complement SI area and volume must be representable");
		int count = solid.length;
		if (source != null && (source.sizeX != sizeX || source.sizeY != sizeY || source.sizeZ != sizeZ
				|| source.cellVolumeCubicMeters != h3 || changed == null || changed.length != count))
			throw new IllegalArgumentException("Gas geometry continuation differs in shape or scale");
		Piece[] byId = source == null ? new Piece[Math.multiplyExact(count,8)] : source.byId.clone();
		SignedDyadic[] pores = source == null ? new SignedDyadic[count] : source.poreVolumes.clone();
		var pieces = new ArrayList<Piece>(); var ports = new ArrayList<Port>(); var exterior = new ArrayList<ExteriorPort>();
		for (int cell = 0; cell < count; cell++) {
			if (source == null || changed[cell]) {
				Arrays.fill(byId,8*cell,8*cell+8,null); pores[cell] = phase.poreVolume(cell);
				captureCell(cell,solid,cut,phase,h3,pores[cell],byId);
			}
			for (int micro = 0; micro < 8; micro++) if (byId[8*cell+micro] != null) pieces.add(byId[8*cell+micro]);
		}
		byte[] fullFaces = new byte[Math.multiplyExact(6,count)];
		int oldPort = 0, oldExterior = 0;
		for (Piece piece : pieces) for (int axis = 0; axis < 3; axis++) {
			long key = 3L*piece.id()+axis; int cell = piece.cell();
			boolean rebuild = source == null || changed[cell];
			if (!rebuild && (piece.micro() & 1<<axis) != 0
					&& coordinate(cell,axis,sizeX,sizeY)+1 < (axis==0?sizeX:axis==1?sizeY:sizeZ))
				rebuild = changed[cell+(axis==0?1:axis==1?sizeX:sizeX*sizeY)];
			Port port = null;
			if (source != null) {
				while (oldPort < source.portArray.length && portKey(source.portArray[oldPort]) < key) oldPort++;
				if (!rebuild && oldPort < source.portArray.length && portKey(source.portArray[oldPort]) == key)
					port = source.portArray[oldPort];
			}
			if (rebuild) port = capturePort(key,sizeX,sizeY,sizeZ,byId,cut,phase,h2,fullFaces);
			if (port != null) ports.add(port);
			ExteriorPort edge = null;
			if (source != null) {
				while (oldExterior < source.exteriorArray.length && exteriorKey(source.exteriorArray[oldExterior]) < key) oldExterior++;
				if (!changed[cell] && oldExterior < source.exteriorArray.length && exteriorKey(source.exteriorArray[oldExterior]) == key)
					edge = source.exteriorArray[oldExterior];
			}
			if (source == null || changed[cell]) edge = captureExterior(key,sizeX,sizeY,sizeZ,byId,cut,phase,h2,fullFaces);
			if (edge != null) exterior.add(edge);
		}
		return new GasComplementGeometry(sizeX,sizeY,sizeZ,h3,cut.copyMicroMasks(),pores,byId,
				pieces.toArray(Piece[]::new),ports.toArray(Port[]::new),exterior.toArray(ExteriorPort[]::new));
	}
	private static long portKey(Port port) { return 3L*port.first()+port.axis(); }
	private static long exteriorKey(ExteriorPort port) { return 3L*port.piece()+port.axis(); }
	private static void validateShape(int sizeX, int sizeY, int sizeZ, boolean[] solid, FlipCutCellGeometry cut, FlipPressurePhaseGeometry phase) {
		if (sizeX < 2 || sizeY < 2 || sizeZ < 2 || solid == null || cut == null || phase == null
				|| solid.length != Math.multiplyExact(sizeX, Math.multiplyExact(sizeY, sizeZ))
				|| !cut.matchesShape(sizeX, sizeY, sizeZ) || !phase.matchesShape(sizeX, sizeY, sizeZ))
			throw new IllegalArgumentException("Gas complement geometry shapes differ");
	}
	private static void captureCell(int cell, boolean[] solid, FlipCutCellGeometry cut, FlipPressurePhaseGeometry phase,
	                                double physicalScale, SignedDyadic poreVolume, Piece[] byId) {
		if (phase.collisionMask(cell) != cut.mask(cell))
			throw new IllegalArgumentException("Gas complement PLIC collision geometry differs at cell " + cell);
		if (solid[cell]) return;
		int[] supports = CellRemapMicroTopology.components(cut.mask(cell));
		SignedDyadic[] capacities = CellRemapMicroTopology.capacities(cut.mask(cell),poreVolume);
		int positivePores = 0;
		for(int i=0;i<supports.length;i++) if(capacities[i].signum()>0) positivePores |= supports[i];
		for (int slot = 0; slot < 8; slot++) {
			if ((positivePores & 1<<slot)==0) continue;

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
					nx, ny, nz, offset);
			double normalizedVolume = plane == null ? MICRO_VOLUME : MicroBoxPlaneVolume.dryVolume(slot, plane);
			if (normalizedVolume == 0) continue;
			double volume = normalizedVolume * physicalScale;
			if (!(volume > 0 && Double.isFinite(volume))) throw blocked("Positive gas piece volume is not representable", cell);
			Piece piece = new Piece(8 * cell + slot, cell, slot, volume, normalizedVolume, plane, phase.offsetBracket(cell,slot));
			byId[piece.id()] = piece;
		}
	}

	private static Port capturePort(long key, int sizeX, int sizeY, int sizeZ, Piece[] byId, FlipCutCellGeometry cut,
	                                FlipPressurePhaseGeometry phase, double h2, byte[] fullFaces) {
		Piece piece = byId[(int) (key / 3)]; if (piece == null) return null;
		int axis = (int) (key % 3), slot = piece.micro(), cell = piece.cell();
		int coordinate = coordinate(cell, axis, sizeX, sizeY), size = axis == 0 ? sizeX : axis == 1 ? sizeY : sizeZ;
		int bit = slot >>> axis & 1, peer = -1;
		Piece neighbor;
		if (bit == 0) neighbor = byId[8 * cell + (slot | 1 << axis)];
		else {
			if (coordinate + 1 == size) return null;
			peer = cell + (axis == 0 ? 1 : axis == 1 ? sizeX : sizeX * sizeY);
			neighbor = byId[8 * peer + (slot ^ 1 << axis)];
		}
		if (neighbor == null) return null;
		double tangent = .5 * (slot >>> ((axis + 1) % 3) & 1), other = .5 * (slot >>> ((axis + 2) % 3) & 1);
		double area = physicalArea(GasFaceIntersection.area(phase, cell, peer, axis, bit == 0 ? .5 : 1, 0, tangent, other, .5), h2, cell);
		boolean full = bit == 0 || fullFixedFace(cut, cell, axis, 1, fullFaces) && fullFixedFace(cut, peer, axis, 0, fullFaces);
		return area > 0 ? new Port(piece.id(), neighbor.id(), axis, area, full) : null;
	}
	private static ExteriorPort captureExterior(long key, int sizeX, int sizeY, int sizeZ, Piece[] byId, FlipCutCellGeometry cut,
	                                            FlipPressurePhaseGeometry phase, double h2, byte[] fullFaces) {
		Piece piece = byId[(int) (key / 3)]; if (piece == null) return null;
		int axis = (int) (key % 3), slot = piece.micro(), cell = piece.cell(), bit = slot >>> axis & 1;
		int coordinate = coordinate(cell, axis, sizeX, sizeY), size = axis == 0 ? sizeX : axis == 1 ? sizeY : sizeZ;
		if (bit == 0 ? coordinate != 0 : coordinate + 1 != size) return null;
		double tangent = .5 * (slot >>> ((axis + 1) % 3) & 1), other = .5 * (slot >>> ((axis + 2) % 3) & 1);
		double area = physicalArea(GasFaceIntersection.area(phase, cell, -1, axis, bit, 0, tangent, other, .5), h2, cell);
		return area > 0 ? new ExteriorPort(piece.id(), axis, bit == 0 ? -1 : 1, area, fullFixedFace(cut, cell, axis, bit, fullFaces)) : null;
	}
	private static int coordinate(int cell, int axis, int sizeX, int sizeY) {
		return axis == 0 ? cell % sizeX : axis == 1 ? cell / sizeX % sizeY : cell / (sizeX * sizeY);
	}
	private static boolean open(FlipCutCellGeometry cut, int cell, int slot) {
		return cut.microCellOpen(cell, slot & 1, slot >>> 1 & 1, slot >>> 2 & 1);
	}
	private static boolean fullFixedFace(FlipCutCellGeometry cut, int cell,
	                                   int axis, int coordinate, byte[] cached) {
		int key = 6 * cell + 2 * axis + coordinate;
		if (cached[key] != 0) return cached[key] == 2;
		boolean full = true;
		for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++) {
			int micro = coordinate << axis | a << ((axis + 1) % 3) | b << ((axis + 2) % 3);
			full &= open(cut, cell, micro);
		}
		cached[key] = (byte) (full ? 2 : 1);
		return full;
	}
	private static double physicalArea(double normalizedArea, double h2, int cell) {
		if (normalizedArea == 0) return 0;
		double result = normalizedArea * h2;
		if (!(result > 0 && Double.isFinite(result))) throw blocked("Positive gas port area is not representable", cell);
		return result;
	}
	private static GasTopologyBlockedException blocked(String message, int cell) {
		return new GasTopologyBlockedException(message + " at cell " + cell);
	}

	double cellVolumeCubicMeters() { return cellVolumeCubicMeters; }
	int collisionMask(int cell) { return Byte.toUnsignedInt(collisionMasks[cell]); }
	SignedDyadic poreVolume(int cell) { return poreVolumes[cell]; }
	int sizeX() { return sizeX; }
	int sizeY() { return sizeY; }
	int sizeZ() { return sizeZ; }
	Piece piece(int id) { return byId[id]; }
	List<Piece> pieces() { return pieces; }
	List<Port> ports() { return ports; }
	List<ExteriorPort> exteriorPorts() { return exteriorPorts; }
}
