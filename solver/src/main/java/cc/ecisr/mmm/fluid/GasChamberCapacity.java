package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Raw-stock control volumes on the existing immutable piece graph, with separate SI rounding bounds. */
final class GasChamberCapacity {
	record Chamber(int node, int minimumPiece, double cubicMeters, double roundingErrorBound) { }
	private final List<GasPieceCapacity.Piece> pieces;
	private final List<Chamber> chambers;
	private final double ambientCubicMeters, ambientRoundingErrorBound;

	private GasChamberCapacity(List<GasPieceCapacity.Piece> pieces, List<Chamber> chambers,
	                          double ambientCubicMeters, double ambientRoundingErrorBound) {
		this.pieces = List.copyOf(pieces); this.chambers = List.copyOf(chambers);
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
		if (domain == null || geometry == null || topology == null || !topology.matchesGeometry(geometry))
			throw new IllegalArgumentException("Gas chamber capacity requires its matching captured geometry");
		var components = GasComponentCapacity.capture(domain, geometry, futureEdit, occupiedSvq);
		int count = topology.chambers().size();
		SignedDyadic[] capacities = new SignedDyadic[count + 1], errors = new SignedDyadic[count + 1];
		Arrays.fill(capacities, SignedDyadic.ZERO); Arrays.fill(errors, SignedDyadic.ZERO);
		boolean[] seen = new boolean[Math.multiplyExact(8, Math.multiplyExact(geometry.sizeX(), Math.multiplyExact(geometry.sizeY(), geometry.sizeZ())))];
		var pieces = new ArrayList<GasPieceCapacity.Piece>(geometry.pieces().size());
		for (var component : components) for (var piece : component.pieces()) {
			int node = topology.nodeOfPiece(piece.id());
			if (node < 0 || node > count || seen[piece.id()]) throw new IllegalArgumentException("Gas capacity topology names a missing or repeated piece");
			seen[piece.id()] = true; pieces.add(piece);
			capacities[node] = capacities[node].add(SignedDyadic.of(piece.cubicMeters()));
			errors[node] = errors[node].add(SignedDyadic.of(piece.roundingErrorBound()));
		}
		if (pieces.size() != geometry.pieces().size()) throw new IllegalArgumentException("Gas capacity omits selected gas geometry");
		pieces.sort(java.util.Comparator.comparingInt(GasPieceCapacity.Piece::id));
		var chambers = new ArrayList<Chamber>(count);
		for (var chamber : topology.chambers()) {
			int node = chamber.node(); double capacity = positive(capacities[node]);
			chambers.add(new Chamber(node, chamber.minimumPiece(), capacity, bound(errors[node], capacity)));
		}
		double ambient = capacities[0].signum() == 0 ? 0 : positive(capacities[0]);
		return new GasChamberCapacity(pieces, chambers, ambient, bound(errors[0], ambient));
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
	List<Chamber> chambers() { return chambers; }
	double ambientCubicMeters() { return ambientCubicMeters; }
	double ambientRoundingErrorBound() { return ambientRoundingErrorBound; }
}
