package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Positive conservative volume weights carry momentum and its second moment through spatial regridding. */
final class LiquidConservativeVolumeRedistribution {
	record Source(int leaf, int owner, LiquidGeometryVelocityProjection.Value field) {
		Source { if (leaf < 0 || owner < 0 || field == null || field.volume().signum() <= 0) throw new IllegalArgumentException("Invalid conservative regrid source"); }
	}
	record Target(int leaf, int owner, Fraction volume) {
		Target { if (leaf < 0 || owner < 0 || volume == null || volume.signum() <= 0) throw new IllegalArgumentException("Invalid conservative regrid target"); }
	}
	record Result(List<Map<Integer, LiquidGeometryVelocityProjection.Value>> fields, Fraction movedVolume, Fraction squaredDisplacementVolumeBound) { }
	private static final class Received {
		Fraction volume = Fraction.ZERO, kinetic = Fraction.ZERO;
		final Fraction[] momentum = {Fraction.ZERO, Fraction.ZERO, Fraction.ZERO};
		void add(Source source, Fraction volume) {
			this.volume = this.volume.add(volume); var weight = volume.divide(source.field.volume());
			kinetic = kinetic.add(weight.multiply(source.field.originalKinetic()));
			for (int a = 0; a < 3; a++) momentum[a] = momentum[a].add(weight.multiply(source.field.momentum().get(a)));
		}
	}
	private LiquidConservativeVolumeRedistribution() { }
	static Result project(List<LiquidGeometryRefinement.Cell> leaves, List<Source> sources, List<Target> targets) {
		var byOwner = new LinkedHashMap<Integer, ArrayList<Source>>(); var sinks = new LinkedHashMap<Integer, ArrayList<Target>>();
		for (var source : sources) byOwner.computeIfAbsent(source.owner, ignored -> new ArrayList<>()).add(source);
		for (var target : targets) sinks.computeIfAbsent(target.owner, ignored -> new ArrayList<>()).add(target);
		if (!byOwner.keySet().equals(sinks.keySet())) throw new IllegalStateException("Conservative regrid lost an inventory group");
		var output = new ArrayList<Map<Integer, LiquidGeometryVelocityProjection.Value>>(); for (var leaf : leaves) output.add(new LinkedHashMap<>());
		var moved = Fraction.ZERO; var displacement = Fraction.ZERO;
		Comparator<Integer> order = Comparator.comparingDouble((Integer l) -> leaves.get(l).minimum(0)).thenComparingDouble(l -> leaves.get(l).minimum(1)).thenComparingDouble(l -> leaves.get(l).minimum(2));
		for (var group : byOwner.entrySet()) {
			int owner = group.getKey(); var supply = group.getValue(); var demand = sinks.get(owner);
			supply.sort(Comparator.comparing(Source::leaf, order)); demand.sort(Comparator.comparing(Target::leaf, order));
			var supplyVolume = supply.stream().map(source -> source.field.volume()).reduce(Fraction.ZERO, Fraction::add);
			var demandVolume = demand.stream().map(Target::volume).reduce(Fraction.ZERO, Fraction::add);
			if (!supplyVolume.equals(demandVolume)) throw new IllegalStateException("Conservative regrid has unequal source and target volumes");
			var remaining = supply.stream().map(source -> source.field.volume()).toArray(Fraction[]::new);
			var required = demand.stream().map(Target::volume).toArray(Fraction[]::new); var received = new Received[demand.size()];
			var sourceIndex = new java.util.HashMap<Integer, Integer>(); for (int s = 0; s < supply.size(); s++)
				if (sourceIndex.put(supply.get(s).leaf, s) != null) throw new IllegalArgumentException("Repeated conservative regrid source");
			var targetLeaves = new java.util.HashSet<Integer>();
			for (int t = 0; t < demand.size(); t++) {
				if (!targetLeaves.add(demand.get(t).leaf)) throw new IllegalArgumentException("Repeated conservative regrid target");
				received[t] = new Received(); var s = sourceIndex.get(demand.get(t).leaf); if (s == null) continue;
				var amount = remaining[s].subtract(required[t]).signum() < 0 ? remaining[s] : required[t];
				if (amount.signum() > 0) received[t].add(supply.get(s), amount);
				remaining[s] = remaining[s].subtract(amount); required[t] = required[t].subtract(amount);
			}
			// The common local overlap is kept first; only residual capacities enter the sparse spatial-order transport.
			int s = 0, t = 0;
			while (s < supply.size() && t < demand.size()) {
				if (remaining[s].signum() == 0) { s++; continue; } if (required[t].signum() == 0) { t++; continue; }
				var amount = remaining[s].subtract(required[t]).signum() < 0 ? remaining[s] : required[t];
				received[t].add(supply.get(s), amount); moved = moved.add(amount);
				var first = leaves.get(supply.get(s).leaf); var second = leaves.get(demand.get(t).leaf); var squared = Fraction.ZERO;
				for (int a = 0; a < 3; a++) {
					var lo = Fraction.of(first.minimum(a)).subtract(Fraction.of(second.minimum(a) + second.width())).absolute();
					var hi = Fraction.of(first.minimum(a) + first.width()).subtract(Fraction.of(second.minimum(a))).absolute();
					var distance = lo.subtract(hi).signum() > 0 ? lo : hi; squared = squared.add(distance.multiply(distance));
				}
				displacement = displacement.add(amount.multiply(squared)); remaining[s] = remaining[s].subtract(amount); required[t] = required[t].subtract(amount);
			}
			for (int i = 0; i < remaining.length; i++) if (remaining[i].signum() != 0) throw new IllegalStateException("Conservative regrid retained source volume");
			for (int i = 0; i < required.length; i++) {
				if (required[i].signum() != 0 || !received[i].volume.equals(demand.get(i).volume)) throw new IllegalStateException("Conservative regrid left target capacity unfilled");
				output.get(demand.get(i).leaf).put(owner, LiquidGeometryVelocityProjection.observe(received[i].volume, List.of(received[i].momentum), received[i].kinetic));
			}
		}
		return new Result(output.stream().map(Map::copyOf).toList(), moved, displacement);
	}
}
