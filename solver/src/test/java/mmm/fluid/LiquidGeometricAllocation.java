package mmm.fluid;

import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Joint positive binary32-volume allocation on the actual geometric source/receiver graph. */
final class LiquidGeometricAllocation {
	private static final int UNIT_EXPONENT = -149;
	private LiquidGeometricAllocation() { }

	record Piece(int source, int receiver, ExactRational volume) { }
	/** Quantities and signed (stored minus geometric) deltas follow the original piece order. */
	record Allocation(List<SignedDyadic> quantities, List<ExactRational> geometricDeltas) {
		Allocation { quantities = List.copyOf(quantities); geometricDeltas = List.copyOf(geometricDeltas); }
	}
	private static final class Edge {
		final int to, reverse;
		BigInteger remaining;
		Edge(int to, int reverse, BigInteger remaining) { this.to = to; this.reverse = reverse; this.remaining = remaining; }
	}

	static Allocation allocate(SignedDyadic[] sourceVolumes, SignedDyadic[] receiverCapacities, List<Piece> inputPieces) {
		if (sourceVolumes == null || receiverCapacities == null || inputPieces == null)
			throw new IllegalArgumentException("Geometric allocation requires complete sources, capacities and pieces");
		var pieces = List.copyOf(inputPieces);
		var sourceGeometry = rationals(sourceVolumes.length); var receiverGeometry = rationals(receiverCapacities.length);
		var sourceRemaining = new BigInteger[sourceVolumes.length]; var receiverRemaining = new BigInteger[receiverCapacities.length];
		for (int i = 0; i < sourceVolumes.length; i++) sourceRemaining[i] = units(sourceVolumes[i], true);
		for (int i = 0; i < receiverCapacities.length; i++) receiverRemaining[i] = units(receiverCapacities[i], false);
		for (var piece : pieces) {
			if (piece.source < 0 || piece.source >= sourceVolumes.length || piece.receiver < 0 || piece.receiver >= receiverCapacities.length
					|| piece.volume == null || piece.volume.signum() <= 0)
				throw new IllegalArgumentException("Geometric allocation requires positive pieces with valid source and receiver indices");
			sourceGeometry[piece.source] = sourceGeometry[piece.source].add(piece.volume);
			receiverGeometry[piece.receiver] = receiverGeometry[piece.receiver].add(piece.volume);
		}
		for (int i = 0; i < sourceVolumes.length; i++) if (!sourceGeometry[i].equals(exact(sourceVolumes[i])))
			throw new IllegalArgumentException("Geometric pieces differ from their exact source inventory at source " + i);
		for (int i = 0; i < receiverCapacities.length; i++) if (receiverGeometry[i].compareTo(exact(receiverCapacities[i])) > 0)
			throw new IllegalArgumentException("Geometric pieces exceed receiver capacity at receiver " + i);

		int terminal = Math.addExact(sourceVolumes.length, receiverCapacities.length), root = Math.addExact(terminal, 1);
		var graph = new ArrayList<ArrayList<Edge>>(Math.addExact(root, 1));
		for (int i = 0; i <= root; i++) graph.add(new ArrayList<>());
		var lower = new BigInteger[pieces.size()]; var originalCapacity = new BigInteger[pieces.size()]; var variable = new Edge[pieces.size()];
		var order = new ArrayList<Integer>(pieces.size()); for (int i = 0; i < pieces.size(); i++) order.add(i);
		order.sort(Comparator.comparingInt((Integer i) -> pieces.get(i).source).thenComparingInt(i -> pieces.get(i).receiver).thenComparingInt(i -> i));
		for (int i : order) {
			var piece = pieces.get(i); var interval = interval(piece.volume);
			lower[i] = units(interval[0], true); originalCapacity[i] = units(interval[1], true).subtract(lower[i]);
			sourceRemaining[piece.source] = sourceRemaining[piece.source].subtract(lower[i]);
			receiverRemaining[piece.receiver] = receiverRemaining[piece.receiver].subtract(lower[i]);
			variable[i] = add(graph, piece.source, sourceVolumes.length + piece.receiver, originalCapacity[i]);
		}
		var required = BigInteger.ZERO;
		for (int i = 0; i < sourceRemaining.length; i++) {
			if (sourceRemaining[i].signum() < 0) throw new IllegalStateException("Geometric allocation lower bounds exceed source inventory");
			add(graph, root, i, sourceRemaining[i]); required = required.add(sourceRemaining[i]);
		}
		for (int i = 0; i < receiverRemaining.length; i++) {
			if (receiverRemaining[i].signum() < 0) throw new IllegalStateException("Geometric allocation lower bounds exceed receiver capacity");
			add(graph, sourceVolumes.length + i, terminal, receiverRemaining[i]);
		}
		if (!flow(graph, root, terminal, required).equals(required))
			throw new IllegalArgumentException("Positive geometric pieces have no joint binary32-volume allocation within adjacent storage bounds");
		var quantities = new ArrayList<SignedDyadic>(pieces.size()); var deltas = new ArrayList<ExactRational>(pieces.size());
		for (int i = 0; i < pieces.size(); i++) {
			var quantity = new SignedDyadic(lower[i].add(originalCapacity[i]).subtract(variable[i].remaining), UNIT_EXPONENT);
			quantities.add(quantity); deltas.add(exact(quantity).subtract(pieces.get(i).volume));
		}
		return new Allocation(quantities, deltas);
	}

	private static SignedDyadic[] interval(ExactRational volume) {
		var minimum = exact(SignedDyadic.of(Float.MIN_VALUE));
		if (volume.compareTo(minimum) < 0) throw new IllegalArgumentException("Positive geometric piece is below binary32 volume storage");
		if (volume.compareTo(ExactRational.ofFloat(Float.MAX_VALUE)) > 0)
			throw new IllegalArgumentException("Geometric piece exceeds finite binary32 volume storage");
		float rounded = (float) volume.toDouble();
		if (!Float.isFinite(rounded)) throw new IllegalArgumentException("Geometric piece exceeds finite binary32 volume storage");
		int power = volume.denominator().getLowestSetBit();
		if (power == volume.denominator().bitLength() - 1) {
			var dyadic = new SignedDyadic(volume.numerator(), -power);
			if (dyadic.exponent() >= UNIT_EXPONENT) return new SignedDyadic[]{dyadic, dyadic};
		}
		var stored = SignedDyadic.of(rounded); int comparison = exact(stored).compareTo(volume);
		float low = comparison > 0 ? Math.nextDown(rounded) : rounded;
		float high = comparison < 0 ? Math.nextUp(rounded) : rounded;
		if (!(low > 0) || !Float.isFinite(high)) throw new IllegalArgumentException("Geometric piece lacks positive finite binary32 neighbours");
		return new SignedDyadic[]{SignedDyadic.of(low), SignedDyadic.of(high)};
	}
	private static BigInteger units(SignedDyadic value, boolean exact) {
		if (value == null || value.signum() < 0) throw new IllegalArgumentException("Geometric allocation volumes must be nonnegative");
		if (value.signum() == 0) return BigInteger.ZERO;
		int shift = Math.subtractExact(value.exponent(), UNIT_EXPONENT);
		if (shift < 0 && exact) throw new IllegalArgumentException("Source volume has a tail below binary32 storage");
		return shift >= 0 ? value.significand().shiftLeft(shift) : value.significand().shiftRight(-shift);
	}
	private static ExactRational exact(SignedDyadic value) {
		return value.exponent() >= 0 ? new ExactRational(value.significand().shiftLeft(value.exponent()), BigInteger.ONE)
				: new ExactRational(value.significand(), BigInteger.ONE.shiftLeft(-value.exponent()));
	}
	private static ExactRational[] rationals(int count) { var result = new ExactRational[count]; Arrays.fill(result, ExactRational.ZERO); return result; }
	private static Edge add(ArrayList<ArrayList<Edge>> graph, int from, int to, BigInteger capacity) {
		var forward = new Edge(to, graph.get(to).size(), capacity); var reverse = new Edge(from, graph.get(from).size(), BigInteger.ZERO);
		graph.get(from).add(forward); graph.get(to).add(reverse); return forward;
	}
	private static BigInteger flow(ArrayList<ArrayList<Edge>> graph, int root, int terminal, BigInteger required) {
		var delivered = BigInteger.ZERO; var previous = new int[graph.size()]; var selected = new Edge[graph.size()]; var queue = new int[graph.size()];
		while (delivered.compareTo(required) < 0) {
			Arrays.fill(previous, -1); previous[root] = root; int head = 0, tail = 0; queue[tail++] = root;
			while (head < tail && previous[terminal] < 0) {
				int from = queue[head++];
				for (var edge : graph.get(from)) if (edge.remaining.signum() > 0 && previous[edge.to] < 0) {
					previous[edge.to] = from; selected[edge.to] = edge; queue[tail++] = edge.to;
					if (edge.to == terminal) break;
				}
			}
			if (previous[terminal] < 0) return delivered;
			var amount = required.subtract(delivered);
			for (int to = terminal; to != root; to = previous[to]) amount = amount.min(selected[to].remaining);
			for (int to = terminal; to != root; to = previous[to]) {
				var edge = selected[to]; edge.remaining = edge.remaining.subtract(amount);
				var reverse = graph.get(to).get(edge.reverse); reverse.remaining = reverse.remaining.add(amount);
			}
			delivered = delivered.add(amount);
		}
		return delivered;
	}
}
