package mmm.fluid;

import mmm.units.SignedDyadic;
import mmm.chemistry.ExactRational;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Arrays;
import java.util.Collections;
import java.util.TreeSet;
import java.util.function.ToLongFunction;

/** Immutable positive gas complement of the admitted PLIC and fixed open microboxes, in SI units. */
final class GasComplementGeometry {
	/** Stable microbox key; normalizedVolume is the exact fraction of one whole cell, volume is its SI observation. */
    record Piece(int id, int cell, int micro, double volume, ExactRational normalizedVolume,
                 GasGeometryOverlap.HalfSpace dryPlane, PlicPlaneOffset.Result offsetBracket) { }
	/** Internal microfaces join one gas volume; complete fixed and dry cell faces join adjacent volumes. */
	record Port(int first, int second, int axis, double area, boolean fullCutConnection) { }
	record ExteriorPort(int piece, int axis, int sign, double area, boolean fullCutConnection) { }

	/** Complete variable-cell set and its incident microfaces, including absent source pieces. */
	static final class Region {
		private final int sizeX, sizeY, sizeZ;
		private final boolean[] selected;
		final int[] cells, pieceIds;
		final long[] portKeys, exteriorKeys;
		Region(int sizeX, int sizeY, int sizeZ, boolean[] selectedCells) {
			int count = Math.multiplyExact(sizeX, Math.multiplyExact(sizeY, sizeZ));
			if (sizeX < 2 || sizeY < 2 || sizeZ < 2 || selectedCells == null || selectedCells.length != count)
				throw new IllegalArgumentException("Gas region requires matching dimensions and selected cells");
			this.sizeX = sizeX; this.sizeY = sizeY; this.sizeZ = sizeZ; selected = selectedCells.clone();
			var cellIds = new ArrayList<Integer>(); var pieces = new ArrayList<Integer>();
			var ports = new TreeSet<Long>(); var exterior = new TreeSet<Long>();
			int[] strides = {1, sizeX, sizeX * sizeY};
			for (int cell = 0; cell < count; cell++) if (selected[cell]) {
				cellIds.add(cell); int[] coordinate = {cell % sizeX, cell / sizeX % sizeY, cell / strides[2]};
				for (int micro = 0; micro < 8; micro++) {
					int piece = 8 * cell + micro; pieces.add(piece);
					for (int axis = 0; axis < 3; axis++) {
						long key = 3L * piece + axis; ports.add(key); exterior.add(key);
						if ((micro & 1 << axis) == 0 && coordinate[axis] > 0)
							ports.add(3L * (8 * (cell - strides[axis]) + (micro | 1 << axis)) + axis);
					}
				}
			}
			cells = cellIds.stream().mapToInt(Integer::intValue).toArray();
			pieceIds = pieces.stream().mapToInt(Integer::intValue).toArray();
			portKeys = ports.stream().mapToLong(Long::longValue).toArray();
			exteriorKeys = exterior.stream().mapToLong(Long::longValue).toArray();
		}
		int[] cells() { return cells.clone(); }
		boolean selected(int cell) { return selected[cell]; }
		boolean matches(GasComplementGeometry source) { return sizeX == source.sizeX && sizeY == source.sizeY && sizeZ == source.sizeZ; }
	}

	private static final ExactRational MICRO_VOLUME = ExactRational.ONE.divide(8);
	private final int sizeX, sizeY, sizeZ;
	private final double cellVolumeCubicMeters, cellAreaSquareMeters;
	private final byte[] collisionMasks;
	private final SignedDyadic[] poreVolumes;
	private final Piece[] byId, pieceArray;
	private final Port[] portArray;
	private final ExteriorPort[] exteriorArray;
	private final List<Piece> pieces;
	private final List<Port> ports;
	private final List<ExteriorPort> exteriorPorts;
	private final Object identity = new Object(), fixedSourceIdentity;
	private final Region fixedRegion;

	/** All arrays are owned snapshots; the public lists cannot modify their backing arrays. */
	private GasComplementGeometry(int sizeX, int sizeY, int sizeZ, double h3, double h2, byte[] masks,
	                              SignedDyadic[] poreVolumes, Piece[] byId, Piece[] pieces, Port[] ports,
	                              ExteriorPort[] exterior, Prepared preparation) {
		this.sizeX = sizeX; this.sizeY = sizeY; this.sizeZ = sizeZ;
		cellVolumeCubicMeters = h3; cellAreaSquareMeters = h2;
		collisionMasks = masks; this.poreVolumes = poreVolumes; this.byId = byId;
		pieceArray = pieces; portArray = ports; exteriorArray = exterior;
		fixedSourceIdentity = preparation == null ? null : preparation.source.identity;
		fixedRegion = preparation == null ? null : preparation.region;
		this.pieces = Collections.unmodifiableList(Arrays.asList(pieces));
		this.ports = Collections.unmodifiableList(Arrays.asList(ports));
		exteriorPorts = Collections.unmodifiableList(Arrays.asList(exterior));
	}

	/** Fixed sorted spans are copied in bulk around the region's replacement keys. */
	private static final class Splice<T> {
		private final T[] source, empty, removed;
		private final int[] starts, lengths;
		private final int fixedCount;
		Splice(T[] source, long[] keys, ToLongFunction<T> key) {
			this.source = source; empty = Arrays.copyOf(source, 0); removed = Arrays.copyOf(empty, keys.length);
			starts = new int[keys.length + 1]; lengths = new int[starts.length];
			int cursor = 0, fixed = 0;
			for (int i = 0; i < keys.length; i++) {
				starts[i] = cursor;
				while (cursor < source.length && key.applyAsLong(source[cursor]) < keys[i]) cursor++;
				lengths[i] = cursor - starts[i]; fixed += lengths[i];
				if (cursor < source.length && key.applyAsLong(source[cursor]) == keys[i]) removed[i] = source[cursor++];
			}
			starts[keys.length] = cursor; lengths[keys.length] = source.length - cursor;
			fixedCount = fixed + source.length - cursor;
		}
		T[] replace(T[] replacement) {
			int count = fixedCount; for (T item : replacement) if (item != null) count++;
			T[] result = Arrays.copyOf(empty, count); int cursor = 0;
			for (int i = 0; i < starts.length; i++) {
				System.arraycopy(source, starts[i], result, cursor, lengths[i]); cursor += lengths[i];
				if (i < replacement.length && replacement[i] != null) result[cursor++] = replacement[i];
			}
			return result;
		}
	}

	static Prepared prepare(GasComplementGeometry source, Region region) { return new Prepared(source, region); }
	static final class Prepared {
		private final GasComplementGeometry source;
		private final Region region;
		private final Splice<Piece> pieces;
		private final Splice<Port> ports;
		private final Splice<ExteriorPort> exterior;
		private final ExactRational physicalScale;
		private Prepared(GasComplementGeometry source, Region region) {
			if (source == null || region == null || !region.matches(source))
				throw new IllegalArgumentException("Prepared gas geometry requires its source region");
			this.source = source; this.region = region; physicalScale = exact(SignedDyadic.of(source.cellVolumeCubicMeters));
			long[] ids = Arrays.stream(region.pieceIds).mapToLong(id -> id).toArray();
			pieces = new Splice<>(source.pieceArray, ids, Piece::id);
			ports = new Splice<>(source.portArray, region.portKeys, port -> 3L * port.first() + port.axis());
			exterior = new Splice<>(source.exteriorArray, region.exteriorKeys, port -> 3L * port.piece() + port.axis());
		}
		/** Actual changes are certified by the runtime's complete per-cell geometry comparison. */
		GasComplementGeometry capture(boolean[] solid, FlipCutCellGeometry cut, FlipPressurePhaseGeometry phase, boolean[] changedCells) {
			validateShape(source.sizeX, source.sizeY, source.sizeZ, solid, cut, phase);
			if (changedCells == null || changedCells.length != solid.length)
				throw new IllegalArgumentException("Local gas geometry requires its actual changed-cell flags");
			Piece[] byId = source.byId.clone(); byte[] masks = source.collisionMasks.clone();
			SignedDyadic[] pores = source.poreVolumes.clone();
			for (int cell : region.cells) if (changedCells[cell]) {
				Arrays.fill(byId, 8 * cell, 8 * cell + 8, null);
				masks[cell] = (byte) cut.mask(cell); pores[cell] = phase.poreVolume(cell);
				captureCell(cell, solid, cut, phase, physicalScale, pores[cell], byId);
			}
			Piece[] changedPieces = new Piece[region.pieceIds.length];
			for (int i = 0; i < changedPieces.length; i++) changedPieces[i] = byId[region.pieceIds[i]];
			Port[] changedPorts = new Port[region.portKeys.length];
			ExteriorPort[] changedExterior = new ExteriorPort[region.exteriorKeys.length];
			byte[] fullFaces = new byte[Math.multiplyExact(6, solid.length)];
			for (int i = 0; i < changedPorts.length; i++) {
				long key = region.portKeys[i]; int piece = (int) (key / 3), axis = (int) (key % 3), cell = piece / 8;
				boolean changed = changedCells[cell];
				if ((piece & 1 << axis) != 0 && coordinate(cell, axis, source.sizeX, source.sizeY) + 1 <
						(axis == 0 ? source.sizeX : axis == 1 ? source.sizeY : source.sizeZ))
					changed |= changedCells[cell + (axis == 0 ? 1 : axis == 1 ? source.sizeX : source.sizeX * source.sizeY)];
				changedPorts[i] = changed ? capturePort(key, source.sizeX, source.sizeY, source.sizeZ,
						byId, cut, phase, source.cellAreaSquareMeters, fullFaces) : ports.removed[i];
			}
			for (int i = 0; i < changedExterior.length; i++) {
				long key = region.exteriorKeys[i];
				changedExterior[i] = changedCells[(int) (key / 24)] ? captureExterior(key, source.sizeX, source.sizeY, source.sizeZ,
						byId, cut, phase, source.cellAreaSquareMeters, fullFaces) : exterior.removed[i];
			}
			return new GasComplementGeometry(source.sizeX, source.sizeY, source.sizeZ, source.cellVolumeCubicMeters, source.cellAreaSquareMeters,
					masks, pores, byId, pieces.replace(changedPieces), ports.replace(changedPorts), exterior.replace(changedExterior), this);
		}
	}
	boolean reusesFixedGeometry(GasComplementGeometry source, Region region) {
		return fixedSourceIdentity == source.identity && fixedRegion == region;
	}

	static GasComplementGeometry capture(int sizeX, int sizeY, int sizeZ, double cellSize,
	                                    boolean[] solid, FlipCutCellGeometry cut, FlipPressurePhaseGeometry phase) {
		validateShape(sizeX, sizeY, sizeZ, solid, cut, phase);
		double h2 = cellSize * cellSize, h3 = h2 * cellSize;
		if (!(cellSize > 0 && h2 > 0 && h3 > 0 && Double.isFinite(h2) && Double.isFinite(h3)))
			throw new IllegalArgumentException("Gas complement SI area and volume must be representable");
		int count = solid.length; ExactRational scale = exact(SignedDyadic.of(h3));
		Piece[] byId = new Piece[Math.multiplyExact(count, 8)]; SignedDyadic[] pores = new SignedDyadic[count];
		var pieces = new ArrayList<Piece>(); var ports = new ArrayList<Port>(); var exterior = new ArrayList<ExteriorPort>();
		for (int cell = 0; cell < count; cell++) {
			pores[cell] = phase.poreVolume(cell); captureCell(cell, solid, cut, phase, scale, pores[cell], byId);
			for (int micro = 0; micro < 8; micro++) if (byId[8 * cell + micro] != null) pieces.add(byId[8 * cell + micro]);
		}
		byte[] fullFaces = new byte[Math.multiplyExact(6, count)];
		for (Piece piece : pieces) for (int axis = 0; axis < 3; axis++) {
			long key = 3L * piece.id() + axis;
			Port port = capturePort(key, sizeX, sizeY, sizeZ, byId, cut, phase, h2, fullFaces);
			if (port != null) ports.add(port);
			ExteriorPort edge = captureExterior(key, sizeX, sizeY, sizeZ, byId, cut, phase, h2, fullFaces);
			if (edge != null) exterior.add(edge);
		}
		return new GasComplementGeometry(sizeX, sizeY, sizeZ, h3, h2, cut.copyMicroMasks(), pores, byId,
				pieces.toArray(Piece[]::new), ports.toArray(Port[]::new), exterior.toArray(ExteriorPort[]::new), null);
	}
	private static void validateShape(int sizeX, int sizeY, int sizeZ, boolean[] solid, FlipCutCellGeometry cut, FlipPressurePhaseGeometry phase) {
		if (sizeX < 2 || sizeY < 2 || sizeZ < 2 || solid == null || cut == null || phase == null
				|| solid.length != Math.multiplyExact(sizeX, Math.multiplyExact(sizeY, sizeZ))
				|| !cut.matchesShape(sizeX, sizeY, sizeZ) || !phase.matchesShape(sizeX, sizeY, sizeZ))
			throw new IllegalArgumentException("Gas complement geometry shapes differ");
	}
	private static void captureCell(int cell, boolean[] solid, FlipCutCellGeometry cut, FlipPressurePhaseGeometry phase,
	                                ExactRational physicalScale, SignedDyadic poreVolume, Piece[] byId) {
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
					SignedDyadic.of(nx), SignedDyadic.of(ny), SignedDyadic.of(nz), SignedDyadic.of(offset));
			ExactRational normalizedVolume = plane == null ? MICRO_VOLUME : MicroBoxPlaneVolume.dryVolume(slot, plane);
			if (normalizedVolume.isZero()) continue;
			double volume = normalizedVolume.multiply(physicalScale).toDouble();
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
		boolean full = bit == 0 || fullGasFace(cut, phase, cell, axis, 1, fullFaces) && fullGasFace(cut, phase, peer, axis, 0, fullFaces);
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
		return area > 0 ? new ExteriorPort(piece.id(), axis, bit == 0 ? -1 : 1, area, fullGasFace(cut, phase, cell, axis, bit, fullFaces)) : null;
	}
	private static int coordinate(int cell, int axis, int sizeX, int sizeY) {
		return axis == 0 ? cell % sizeX : axis == 1 ? cell / sizeX % sizeY : cell / (sizeX * sizeY);
	}
	private static ExactRational exact(SignedDyadic value) {
		return value.exponent() >= 0
				? new ExactRational(value.significand().shiftLeft(value.exponent()), BigInteger.ONE)
				: new ExactRational(value.significand(), BigInteger.ONE.shiftLeft(-value.exponent()));
	}
	private static boolean open(FlipCutCellGeometry cut, int cell, int slot) {
		return cut.microCellOpen(cell, slot & 1, slot >>> 1 & 1, slot >>> 2 & 1);
	}
	private static boolean fullGasFace(FlipCutCellGeometry cut, FlipPressurePhaseGeometry phase, int cell,
	                                   int axis, int coordinate, byte[] cached) {
		int key = 6 * cell + 2 * axis + coordinate;
		if (cached[key] != 0) return cached[key] == 2;
		boolean full = true;
		for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++) {
			int micro = coordinate << axis | a << ((axis + 1) % 3) | b << ((axis + 2) % 3);
			full &= open(cut, cell, micro);
		}
		full = full && GasFaceIntersection.fullDryFace(phase, cell, axis, coordinate);
		cached[key] = (byte) (full ? 2 : 1);
		return full;
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
	SignedDyadic poreVolume(int cell) { return poreVolumes[cell]; }
	int sizeX() { return sizeX; }
	int sizeY() { return sizeY; }
	int sizeZ() { return sizeZ; }
	Piece piece(int id) { return byId[id]; }
	List<Piece> pieces() { return pieces; }
	List<Port> ports() { return ports; }
	List<ExteriorPort> exteriorPorts() { return exteriorPorts; }
}
