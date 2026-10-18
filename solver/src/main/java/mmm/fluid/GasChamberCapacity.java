package mmm.fluid;


import java.util.ArrayList;
import java.util.List;

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

	private static void requireTopology(FluidDomain domain, GasComplementGeometry geometry, GasChamberTopology topology) {
		if (domain == null || geometry == null || topology == null || !topology.matchesGeometry(geometry))
			throw new IllegalArgumentException("Gas chamber capacity requires its matching captured geometry");
	}
	private static double[] totals(int count) { return new double[count + 1]; }
	private static void add(GasPieceCapacity.Piece piece, GasChamberTopology topology,
	                        double[] capacities, double[] errors) {
		int node = topology.nodeOfPiece(piece.id());
		if (node < 0 || node >= capacities.length) throw new IllegalArgumentException("Gas capacity topology names a missing piece");
		capacities[node] += piece.cubicMeters();
		errors[node] += piece.roundingErrorBound() + Math.ulp(capacities[node]);
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
	                                         double[] capacities, double[] errors) {
		var chambers = new ArrayList<Chamber>(topology.chambers().size());
		for (var chamber : topology.chambers()) {
			int node = chamber.node(); double capacity = positive(capacities[node]);
			chambers.add(new Chamber(node, chamber.minimumPiece(), capacity, bound(errors[node], capacity)));
		}
		double ambient = capacities[0] == 0 ? 0 : positive(capacities[0]);
		return new GasChamberCapacity(geometry, components, pieces, chambers, ambient, bound(errors[0], ambient));
	}

    private static double positive(double value) {
        if (!(Double.isFinite(value) && value > 0)) throw new GasTopologyBlockedException("Gas chamber raw capacity is not representable");
        return value;
    }
    private static double bound(double leaves, double capacity) {
        return capacity == 0 ? 0 : Math.nextUp(leaves + Math.ulp(capacity));
    }
	List<GasPieceCapacity.Piece> pieces() { return pieces; }
	List<GasComponentCapacity.Component> components() { return components; }
	List<Chamber> chambers() { return chambers; }
	double ambientCubicMeters() { return ambientCubicMeters; }
	double ambientRoundingErrorBound() { return ambientRoundingErrorBound; }
}
