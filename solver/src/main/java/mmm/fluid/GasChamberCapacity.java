package mmm.fluid;

import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

/** Raw-stock control volumes on the existing immutable piece graph, with separate SI rounding bounds. */
final class GasChamberCapacity {
	record Chamber(int node, int minimumPiece, double cubicMeters, double roundingErrorBound) { }
	private final GasComplementGeometry geometry;
	private final List<GasPieceCapacity.Piece> pieces;
	private final List<GasComponentCapacity.Component> components;
	private final List<Chamber> chambers;
	private final double ambientCubicMeters, ambientRoundingErrorBound;

	private GasChamberCapacity(GasComplementGeometry geometry, List<GasComponentCapacity.Component> components,
	                          List<GasPieceCapacity.Piece> pieces, List<Chamber> chambers,
	                          double ambientCubicMeters, double ambientRoundingErrorBound) {
		this.geometry = geometry;
		this.pieces = List.copyOf(pieces); this.chambers = List.copyOf(chambers);
		this.components = List.copyOf(components);
		this.ambientCubicMeters = ambientCubicMeters; this.ambientRoundingErrorBound = ambientRoundingErrorBound;
	}

	static GasChamberCapacity capture(FluidDomain domain, GasComplementGeometry geometry,
	                                 GasChamberTopology topology, PhaseLiquidGeometryEdit futureEdit) {
		if (domain == null) throw new IllegalArgumentException("Gas chamber domain is missing");
		long[] occupied = domain.granularCoupling == null ? new long[domain.cellCount] : domain.granularCoupling.copySolidSvq();
		return capture(domain, geometry, topology, futureEdit, occupied);
	}

	static GasChamberCapacity capture(FluidDomain domain, GasComplementGeometry geometry,
	                                 GasChamberTopology topology, PhaseLiquidGeometryEdit futureEdit, long[] occupiedSvq) {
		requireTopology(domain, geometry, topology);
		var components = GasComponentCapacity.capture(domain, geometry, futureEdit, occupiedSvq);
		return assemble(components,geometry,topology);
	}

	static GasChamberCapacity capture(FluidDomain domain, GasComplementGeometry geometry,
	                                 GasChamberTopology topology, long[] occupiedSvq,
	                                 List<PhaseLiquidGeometryEdit> liquidStock) {
		return capture(domain, geometry, topology, occupiedSvq, liquidStock, null);
	}

	static GasChamberCapacity capture(FluidDomain domain, GasComplementGeometry geometry,
	                                 GasChamberTopology topology, long[] occupiedSvq,
	                                 List<PhaseLiquidGeometryEdit> liquidStock, GasRuntimeGeometry source) {
		requireTopology(domain, geometry, topology);
		return assemble(GasComponentCapacity.capture(domain,geometry,occupiedSvq,liquidStock,source),geometry,topology);
	}

	/** Fixed raw capacities follow the same forest as the prepared geometry and topology. */
	static Prepared prepare(GasRuntimeGeometry source, GasChamberTopology.Prepared topologySource) {
		return new Prepared(source, topologySource);
	}
	private record FixedCapacity(int root, SignedDyadic volume, SignedDyadic error) { }
	static final class Prepared {
		private final GasRuntimeGeometry source;
		private final GasComplementGeometry sourceGeometry;
		private final long[] sourceOccupied;
		private final GasComponentCapacity.Component[] sourceComponents;
		private final GasPieceCapacity.Piece[] sourcePieces;
		private final int[] cells, componentStarts, pieceStarts;
		private final List<FixedCapacity> fixed;
		private final int variablePieceCount;

		private Prepared(GasRuntimeGeometry source, GasChamberTopology.Prepared topologySource) {
			if (source == null || topologySource == null || source.capacity().geometry != source.geometry())
				throw new IllegalArgumentException("Prepared gas capacity requires its matching source geometry");
			this.source = source; sourceGeometry = source.geometry(); sourceOccupied = source.occupiedSvq();
			cells = topologySource.variableCells();
			sourceComponents = source.capacity().components.toArray(GasComponentCapacity.Component[]::new);
			sourcePieces = source.capacity().pieces.toArray(GasPieceCapacity.Piece[]::new);
			int count = Math.multiplyExact(sourceGeometry.sizeX(), Math.multiplyExact(sourceGeometry.sizeY(), sourceGeometry.sizeZ()));
			componentStarts = new int[count + 1]; pieceStarts = new int[count + 1];
			int component = 0, piece = 0;
			for (int cell = 0; cell < count; cell++) {
				componentStarts[cell] = component; pieceStarts[cell] = piece;
				while (component < sourceComponents.length && sourceComponents[component].cell() == cell) component++;
				while (piece < sourcePieces.length && sourcePieces[piece].id() / 8 == cell) piece++;
			}
			componentStarts[count] = component; pieceStarts[count] = piece;
			if (component != sourceComponents.length || piece != sourcePieces.length)
				throw new IllegalArgumentException("Prepared gas capacity source is not in cell order");
			var totals = new TreeMap<Integer, SignedDyadic[]>();
			for (var value : sourcePieces) {
				int root = topologySource.fixedRoot(value.id());
				if (root < 0) continue;
				var total = totals.computeIfAbsent(root, ignored -> new SignedDyadic[]{SignedDyadic.ZERO, SignedDyadic.ZERO});
				total[0] = total[0].add(SignedDyadic.of(value.cubicMeters()));
				total[1] = total[1].add(SignedDyadic.of(value.roundingErrorBound()));
			}
			var parts = new ArrayList<FixedCapacity>(totals.size());
			for (var entry : totals.entrySet()) parts.add(new FixedCapacity(entry.getKey(), entry.getValue()[0], entry.getValue()[1]));
			fixed = List.copyOf(parts);
			int removed = 0;
			for (int cell : cells) removed += pieceStarts[cell + 1] - pieceStarts[cell];
			variablePieceCount = removed;
		}

		/** The preparing phase source retains all fixed stock, occupancy and collision inputs. */
		GasChamberCapacity capture(FluidDomain domain, GasComplementGeometry geometry, GasChamberTopology topology,
		                           long[] occupied, List<PhaseLiquidGeometryEdit> liquidStock) {
			requireTopology(domain, geometry, topology);
			if (sourceGeometry.sizeX() != geometry.sizeX() || sourceGeometry.sizeY() != geometry.sizeY() || sourceGeometry.sizeZ() != geometry.sizeZ()
					|| Double.doubleToRawLongBits(sourceGeometry.cellVolumeCubicMeters()) != Double.doubleToRawLongBits(geometry.cellVolumeCubicMeters()))
				throw new IllegalArgumentException("Prepared gas capacity candidate differs in shape or metric");
			var changed = GasComponentCapacity.captureCells(domain, geometry, occupied, liquidStock, cells, source, sourceOccupied, componentStarts);
			var components = sourceComponents.clone(); int next = 0;
			var byCell = new GasPieceCapacity.Piece[cells.length][8]; int added = 0;
			for (int selected = 0; selected < cells.length; selected++) {
				int cell = cells[selected];
				for (int index = componentStarts[cell]; index < componentStarts[cell + 1]; index++) {
					if (next >= changed.size() || changed.get(next).cell() != cell
							|| changed.get(next).supportMask() != sourceComponents[index].supportMask())
						throw new IllegalArgumentException("Prepared gas capacity component supports changed");
					var component = changed.get(next++); components[index] = component;
					for (var value : component.pieces()) {
						int micro = value.id() & 7;
						if (value.id() / 8 != cell || byCell[selected][micro] != null)
							throw new IllegalArgumentException("Local gas capacity names a repeated or misplaced piece");
						byCell[selected][micro] = value; added++;
					}
				}
			}
			if (next != changed.size()) throw new IllegalArgumentException("Prepared gas capacity component supports changed");
			var pieces = new GasPieceCapacity.Piece[sourcePieces.length - variablePieceCount + added];
			int oldPosition = 0, output = 0;
			for (int selected = 0; selected < cells.length; selected++) {
				int cell = cells[selected], length = pieceStarts[cell] - oldPosition;
				System.arraycopy(sourcePieces, oldPosition, pieces, output, length); output += length;
				for (var value : byCell[selected]) if (value != null) pieces[output++] = value;
				oldPosition = pieceStarts[cell + 1];
			}
			System.arraycopy(sourcePieces, oldPosition, pieces, output, sourcePieces.length - oldPosition);
			if (pieces.length != geometry.pieces().size()) throw new IllegalArgumentException("Gas capacity omits selected gas geometry");
			int count = topology.chambers().size();
			var capacities = totals(count); var errors = totals(count);
			for (var part : fixed) {
				int node = topology.nodeOfPiece(part.root());
				if (node < 0 || node > count) throw new IllegalArgumentException("Fixed gas capacity lost its topology owner");
				capacities[node] = capacities[node].add(part.volume()); errors[node] = errors[node].add(part.error());
			}
			for (var row : byCell) for (var value : row) if (value != null) add(value, topology, capacities, errors);
			return finish(Arrays.asList(components), Arrays.asList(pieces), geometry, topology, capacities, errors);
		}
	}

	private static void requireTopology(FluidDomain domain, GasComplementGeometry geometry, GasChamberTopology topology) {
		if (domain == null || geometry == null || topology == null || !topology.matchesGeometry(geometry))
			throw new IllegalArgumentException("Gas chamber capacity requires its matching captured geometry");
	}
	private static SignedDyadic[] totals(int count) {
		var result = new SignedDyadic[count + 1]; Arrays.fill(result, SignedDyadic.ZERO); return result;
	}
	private static void add(GasPieceCapacity.Piece piece, GasChamberTopology topology,
	                        SignedDyadic[] capacities, SignedDyadic[] errors) {
		int node = topology.nodeOfPiece(piece.id());
		if (node < 0 || node >= capacities.length) throw new IllegalArgumentException("Gas capacity topology names a missing piece");
		capacities[node] = capacities[node].add(SignedDyadic.of(piece.cubicMeters()));
		errors[node] = errors[node].add(SignedDyadic.of(piece.roundingErrorBound()));
	}
	private static GasChamberCapacity assemble(List<GasComponentCapacity.Component> components,
	                                           GasComplementGeometry geometry, GasChamberTopology topology) {
		int count = topology.chambers().size();
		var capacities = totals(count); var errors = totals(count);
		var byId = new GasPieceCapacity.Piece[Math.multiplyExact(8, Math.multiplyExact(geometry.sizeX(), Math.multiplyExact(geometry.sizeY(), geometry.sizeZ())))];
		int size = 0;
		for (var component : components) for (var piece : component.pieces()) {
			if (geometry.piece(piece.id()) == null || byId[piece.id()] != null)
				throw new IllegalArgumentException("Gas capacity topology names a missing or repeated piece");
			byId[piece.id()] = piece; size++; add(piece, topology, capacities, errors);
		}
		if (size != geometry.pieces().size()) throw new IllegalArgumentException("Gas capacity omits selected gas geometry");
		var pieces = new ArrayList<GasPieceCapacity.Piece>(size);
		for (var piece : byId) if (piece != null) pieces.add(piece);
		return finish(components, pieces, geometry, topology, capacities, errors);
	}
	private static GasChamberCapacity finish(List<GasComponentCapacity.Component> components, List<GasPieceCapacity.Piece> pieces,
	                                         GasComplementGeometry geometry, GasChamberTopology topology,
	                                         SignedDyadic[] capacities, SignedDyadic[] errors) {
		var chambers = new ArrayList<Chamber>(topology.chambers().size());
		for (var chamber : topology.chambers()) {
			int node = chamber.node(); double capacity = positive(capacities[node]);
			chambers.add(new Chamber(node, chamber.minimumPiece(), capacity, bound(errors[node], capacity)));
		}
		double ambient = capacities[0].signum() == 0 ? 0 : positive(capacities[0]);
		return new GasChamberCapacity(geometry, components, pieces, chambers, ambient, bound(errors[0], ambient));
	}

	private static double positive(SignedDyadic value) {
		double result = value.toDouble();
		if (!(Double.isFinite(result) && result > 0)) throw new GasTopologyBlockedException("Gas chamber raw capacity is not representable");
		return result;
	}
	private static double bound(SignedDyadic leaves, double capacity) {
		if (capacity == 0) return 0;
		SignedDyadic total = leaves.add(SignedDyadic.of(Math.ulp(capacity)).multiply(SignedDyadic.of(1, -1)));
		double result = Math.nextUp(total.toDouble());
		if (!Double.isFinite(result)) throw new GasTopologyBlockedException("Gas chamber capacity error is not representable");
		return result;
	}
	List<GasPieceCapacity.Piece> pieces() { return pieces; }
	List<GasComponentCapacity.Component> components() { return components; }
	List<Chamber> chambers() { return chambers; }
	double ambientCubicMeters() { return ambientCubicMeters; }
	double ambientRoundingErrorBound() { return ambientRoundingErrorBound; }
}
