package mmm.fluid;

import mmm.chemistry.ChemicalMixtureRecord;
import mmm.units.SignedDyadic;
import java.util.Arrays;
import java.util.List;

/** Exact source species allocation with positive-volume paths covering final sampling nodes. */
final class CellRemapSpeciesAllocation {
	record Source(CellSpeciesInventory inventory, SignedDyadic[] volumes, int[] targets) { }
	static final class Unavailable extends IllegalStateException {
		final int target;
		Unavailable(int target, int needed, long available) {
			super("Chemical target has insufficient reachable quanta: target=" + target
					+ ", needed=" + needed + ", available=" + available);
			this.target=target;
		}
		Unavailable(String message,Unavailable cause) { super(message,cause); target=cause.target; }
	}
	private CellRemapSpeciesAllocation() { }

	static ChemicalMixtureRecord[][] allocate(List<Source> sources, int[] required) {
		if (sources == null || required == null) throw new IllegalArgumentException("Missing chemical sampling allocation");
		sources = List.copyOf(sources);
		for (int count : required) if (count < 0) throw new IllegalArgumentException("Negative chemical sampling requirement");
		int sourceCount = sources.size(), edgeCount = 0;
		for (var source : sources) {
			if (source.inventory() == null || source.inventory().isEmpty() || source.volumes() == null
					|| source.targets() == null || source.volumes().length != source.targets().length)
				throw new IllegalArgumentException("Incomplete chemical source shares");
			boolean positive = false;
			for (int share = 0; share < source.volumes().length; share++) {
				var volume = source.volumes()[share]; int target = source.targets()[share];
				if (volume == null || volume.signum() < 0 || target < 0 || target >= required.length)
					throw new IllegalArgumentException("Invalid chemical source volume or target");
				if (volume.signum() > 0) { positive = true; edgeCount = Math.addExact(edgeCount, 1); }
			}
			if (!positive) throw new IllegalArgumentException("Chemical source has no positive volume support");
		}

		long[][][] quantities = new long[sourceCount][][];
		long[][] totals = new long[sourceCount][];
		long[] targetTotals = new long[required.length];
		int[] head = new int[required.length], next = new int[edgeCount], edgeSource = new int[edgeCount], edgeShare = new int[edgeCount];
		Arrays.fill(head, -1); int edge = 0;
		for (int source = 0; source < sourceCount; source++) {
			var input = sources.get(source); int shares = input.volumes().length;
			quantities[source] = new long[input.inventory().size()][]; totals[source] = new long[shares];
			for (int species = 0; species < quantities[source].length; species++) {
				long[] parts = ExactVolumeAllocation.allocate(input.inventory().amountAt(species), input.volumes());
				quantities[source][species] = parts;
				for (int share = 0; share < shares; share++) totals[source][share] = Math.addExact(totals[source][share], parts[share]);
			}
			for (int share = 0; share < shares; share++) {
				int target = input.targets()[share];
				targetTotals[target] = Math.addExact(targetTotals[target], totals[source][share]);
				if (input.volumes()[share].signum() == 0) {
					if (totals[source][share] != 0) throw new IllegalStateException("Zero chemical volume received quanta");
					continue;
				}
				edgeSource[edge] = source; edgeShare[edge] = share; next[edge] = head[target]; head[target] = edge++;
			}
		}

		int[] queue = new int[required.length], seenTarget = new int[required.length], seenSource = new int[sourceCount];
		int[] parentSource = new int[required.length], oldShare = new int[required.length];
		int[] parentTarget = new int[sourceCount], newShare = new int[sourceCount];
		int generation = 0;
		for (int target = 0; target < required.length; target++) while (targetTotals[target] < required[target]) {
			if (++generation == 0) { Arrays.fill(seenTarget, 0); Arrays.fill(seenSource, 0); generation = 1; }
			int begin = 0, end = 0, surplus = -1; queue[end++] = target; seenTarget[target] = generation;
			while (begin < end && surplus < 0) {
				int desired = queue[begin++];
				for (int link = head[desired]; link >= 0 && surplus < 0; link = next[link]) {
					int source = edgeSource[link]; if (seenSource[source] == generation) continue;
					seenSource[source] = generation; parentTarget[source] = desired; newShare[source] = edgeShare[link];
					var input = sources.get(source);
					for (int share = 0; share < input.targets().length; share++) {
						int other = input.targets()[share];
						if (totals[source][share] == 0 || seenTarget[other] == generation) continue;
						seenTarget[other] = generation; parentSource[other] = source; oldShare[other] = share;
						if (targetTotals[other] > required[other]) { surplus = other; break; }
						queue[end++] = other;
					}
				}
			}
			if (surplus < 0) throw new Unavailable(target, required[target], targetTotals[target]);
			long push = Math.min(required[target] - targetTotals[target], targetTotals[surplus] - required[surplus]);
			for (int cursor = surplus; cursor != target; cursor = parentTarget[parentSource[cursor]])
				push = Math.min(push, totals[parentSource[cursor]][oldShare[cursor]]);
			// Intermediate targets exchange equal amounts; only the two endpoints change their totals.
			for (int cursor = surplus; cursor != target; cursor = parentTarget[parentSource[cursor]]) {
				int source = parentSource[cursor], from = oldShare[cursor], to = newShare[source]; long remaining = push;
				for (long[] species : quantities[source]) {
					long moved = Math.min(remaining, species[from]);
					species[from] -= moved; species[to] = Math.addExact(species[to], moved); remaining -= moved;
					if (remaining == 0) break;
				}
				if (remaining != 0) throw new IllegalStateException("Chemical augmenting path lost its source inventory");
				totals[source][from] -= push; totals[source][to] = Math.addExact(totals[source][to], push);
			}
			targetTotals[target] += push; targetTotals[surplus] -= push;
		}

		var result = new ChemicalMixtureRecord[sourceCount][];
		for (int source = 0; source < sourceCount; source++) {
			result[source] = records(sources.get(source).inventory(), quantities[source], totals[source]);
		}
		return result;
	}

	private static ChemicalMixtureRecord[] records(CellSpeciesInventory inventory, long[][] quantities, long[] totals) {
		var result = new ChemicalMixtureRecord[totals.length];
		for (int share = 0; share < result.length; share++) {
			if (totals[share] == 0) continue;
			for (int earlier = 0; earlier < share; earlier++) if (result[earlier] != null) {
				boolean same = true;
				for (long[] species : quantities) if (species[earlier] != species[share]) { same = false; break; }
				if (same) { result[share] = result[earlier]; break; }
			}
			if (result[share] != null) continue;
			int count = 0; for (long[] species : quantities) if (species[share] > 0) count++;
			int[] handles = new int[count]; long[] amounts = new long[count]; int entry = 0;
			for (int species = 0; species < quantities.length; species++) if (quantities[species][share] > 0) {
				handles[entry] = inventory.handleAt(species); amounts[entry++] = quantities[species][share];
			}
			result[share] = new ChemicalMixtureRecord(handles, amounts, 0);
		}
		return result;
	}
}
