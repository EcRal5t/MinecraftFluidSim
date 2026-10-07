package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import cc.ecisr.mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Extracts inventory from its actual spatial support and preflights the complete successor. */
final class LiquidGeometryExtractionPlan {
	record Remainder(int marker, SignedDyadic volume, SignedDyadic mass) {
		static Remainder plan(FluidDomain domain, int marker, float volume, double mass, double maximum) {
			// Subtract the accepted observation deltas from the complete quantities, retaining their precision remainder.
			var keptVolume = domain.particles.exactVolume(marker).subtract(SignedDyadic.of(domain.particles.volume(marker)).subtract(SignedDyadic.of(volume)));
			var keptMass = domain.particles.exactMass(marker).subtract(SignedDyadic.of(domain.particles.mass(marker)).subtract(SignedDyadic.of(mass)));
			if (domain.particles.exactVolume(marker).subtract(keptVolume).compareTo(SignedDyadic.of(maximum)) > 0) return null;
			if (keptVolume.signum() < 0 || keptMass.signum() < 0 || (keptVolume.signum() == 0) != (keptMass.signum() == 0)) return null;
			if (volume == 0 && keptVolume.signum() != 0) return null;
			if (keptVolume.signum() > 0 && (!(keptVolume.toFloat() > 0) || !(keptMass.toDouble() > 0))) return null;
			return new Remainder(marker, keptVolume, keptMass);
		}
		float observedVolume() { return volume.toFloat(); }
		double observedMass() { return mass.toDouble(); }
	}
	private final FluidDomain domain;
	private final LiquidGeometryState input;
	private final List<LiquidGeometryRefinement.Cell> source;
	private final List<List<LiquidPolyhedron>> available;
	private final Fraction[] remaining;
	private final Fraction scale;
	private final int[] markerOwners;
	final List<Integer> markers;
	private LiquidGeometryState successor;
	private float[] positions, velocities;

	LiquidGeometryExtractionPlan(FluidDomain domain, List<FluidDomain.CellCandidate> cells) {
		this.domain = domain; input = domain.liquidGeometryState;
		markerOwners = input == null ? new int[0] : input.copyMarkerCells();
		var order = new ArrayList<Integer>();
		if (input == null) {
			source = List.of(); available = List.of(); remaining = new Fraction[0]; scale = Fraction.of(1);
			for (var cell : cells) for (int p = domain.particles.count() - 1; p >= 0; p--)
				if (domain.particleCellIndex(p) == cell.index()) order.add(p);
		} else {
			LiquidGeometryStateMapper.requireCompatible(input, domain);
			source = LiquidGeometryStateMapper.restore(input);
			int span = (int) (input.rootWidthMetres() / domain.config.cellSize());
			scale = Fraction.of(span).multiply(Fraction.of(span)).multiply(Fraction.of(span));
			available = new ArrayList<>(); remaining = new Fraction[source.size()]; Arrays.fill(remaining, Fraction.ZERO);
			for (int f = 0; f < source.size(); f++) available.add(new ArrayList<>());
			int[] owners = input.copyFragmentOwners(); var seen = new boolean[domain.particles.count()];
			int baseLevel = Integer.numberOfTrailingZeros(span);
			int regions = 0;
			for (var candidate : cells) {
				int x = candidate.index() % domain.sizeX, yz = candidate.index() / domain.sizeX, y = yz % domain.sizeY, z = yz / domain.sizeY;
				var box = LiquidPolyhedron.box(x / (double) span, y / (double) span, z / (double) span,
						(x + 1.0) / span, (y + 1.0) / span, (z + 1.0) / span);
				for (int f = 0; f < source.size(); f++) {
					var cell = source.get(f); int shift = cell.level() - baseLevel;
					if (shift >= 0 ? cell.x() >>> shift != x || cell.y() >>> shift != y || cell.z() >>> shift != z
							: x >>> -shift != cell.x() || y >>> -shift != cell.y() || z >>> -shift != cell.z()) continue;
					var clipped = intersection(source.get(f).geometry(), box);
					var volume = LiquidConvexRegion.of(clipped).measure().volume(); if (volume.signum() == 0) continue;
					if (++regions > LiquidGeometryInsertionPlan.MAXIMUM_SOURCE_REGIONS) throw new IllegalStateException("Liquid extraction exceeds its spatial support budget");
					int owner = owners[f]; available.get(owner).add(clipped); remaining[owner] = remaining[owner].add(volume);
					for (int p = seen.length - 1; p >= 0; p--) if (!seen[p] && markerOwners[p] == owner) { seen[p] = true; order.add(p); }
				}
			}
		}
		markers = List.copyOf(order);
	}
	boolean hasVolume(float requested, java.util.function.IntPredicate eligible) {
		var quantity = Fraction.ZERO;
		if (input == null) {
			for (int p : markers) if (eligible.test(p)) quantity = quantity.add(Fraction.of(domain.particles.exactVolume(p)));
		} else {
			var stocks = new Fraction[source.size()]; Arrays.fill(stocks, Fraction.ZERO);
			for (int p : markers) if (eligible.test(p)) stocks[markerOwners[p]] = stocks[markerOwners[p]].add(Fraction.of(domain.particles.exactVolume(p)));
			for (int f = 0; f < stocks.length; f++) {
				var space = remaining[f].multiply(scale);
				quantity = quantity.add(stocks[f].subtract(space).signum() > 0 ? space : stocks[f]);
			}
		}
		return quantity.subtract(Fraction.of(requested)).signum() >= 0;
	}

	double volume(int marker) {
		var stock = Fraction.of(domain.particles.exactVolume(marker));
		var amount = input == null ? stock : remaining[markerOwners[marker]].multiply(scale);
		if (stock.compareTo(amount) < 0) amount = stock;
		double observed = amount.rounded(); if (Fraction.of(observed).subtract(amount).signum() > 0) observed = Math.nextDown(observed);
		return observed;
	}
	void reserve(Remainder remainder) {
		if (input == null) return;
		int marker = remainder.marker;
		int owner = markerOwners[marker];
		remaining[owner] = remaining[owner].subtract(Fraction.of(domain.particles.exactVolume(marker).subtract(remainder.volume)).divide(scale));
		if (remaining[owner].signum() < 0) throw new IllegalStateException("Liquid extraction exceeds its actual occupied support");
	}

	void finish(List<Remainder> remainders) {
		if (input == null) return;
		int[] oldMarkers = input.copyMarkerCells(), oldOwners = input.copyFragmentOwners();
		var removed = new Fraction[source.size()]; Arrays.fill(removed, Fraction.ZERO);
		var volume = new SignedDyadic[domain.particles.count()]; var mass = new SignedDyadic[volume.length];
		for (int p = 0; p < volume.length; p++) { volume[p] = domain.particles.exactVolume(p); mass[p] = domain.particles.exactMass(p); }
		for (var remainder : remainders) {
			removed[oldMarkers[remainder.marker]] = removed[oldMarkers[remainder.marker]].add(Fraction.of(volume[remainder.marker].subtract(remainder.volume)).divide(scale));
			volume[remainder.marker] = remainder.volume; mass[remainder.marker] = remainder.mass;
		}
		var cuts = new ArrayList<List<LiquidPolyhedron>>();
		for (int f = 0; f < source.size(); f++) cuts.add(removed[f].signum() == 0 ? List.of()
				: new LiquidGeometrySourcePlan(available.get(f), removed[f], LiquidGeometryInsertionPlan.MAXIMUM_SOURCE_REGIONS).geometry);
		var cells = new ArrayList<LiquidGeometryRefinement.Cell>(); var groups = new ArrayList<Integer>(); var field = new ArrayList<double[]>();
		var controls = new ArrayList<Integer>(); int[] previousControls = input.copyControlCells(); var firstControl = new java.util.HashMap<Integer, Integer>();
		for (int f = 0; f < source.size(); f++) {
			int start = cells.size();
			var original = source.get(f); int owner = oldOwners[f]; var intersections = new ArrayList<LiquidPolyhedron>();
			for (var cut : cuts.get(owner)) { var clipped = intersection(original.geometry(), cut); if (LiquidConvexRegion.of(clipped).hasVolume()) intersections.add(clipped); }
			if (intersections.isEmpty()) add(cells, groups, field, original, owner, input.cells().get(f).copyVelocity());
			else for (var piece : LiquidPhasePartition.build(original, intersections, LiquidGeometryInsertionPlan.MAXIMUM_SOURCE_REGIONS))
				if (piece.liquid() < 0) add(cells, groups, field, piece.cell(), owner, input.cells().get(f).copyVelocity());
			for (int part = start; part < cells.size(); part++) { firstControl.putIfAbsent(previousControls[f], part); controls.add(firstControl.get(previousControls[f])); }
		}
		var first = new java.util.HashMap<Integer, Integer>(); for (int f = 0; f < cells.size(); f++) first.putIfAbsent(groups.get(f), f);
		// The same descending swap-removal order is used by the inventory and its side columns.
		int[] ordinals = java.util.stream.IntStream.range(0, volume.length).toArray(); int count = volume.length;
		for (int p = volume.length - 1; p >= 0; p--) if (volume[p].signum() == 0) ordinals[p] = ordinals[--count];
		int[] markers = new int[count]; for (int p = 0; p < count; p++) markers[p] = first.get(oldMarkers[ordinals[p]]);
		var velocity = new double[3 * cells.size()]; for (int f = 0; f < cells.size(); f++) System.arraycopy(field.get(f), 0, velocity, 3 * f, 3);
		// Canonical packing uses a live fragment ordinal as each temporary group representative.
		int[] owners = new int[cells.size()]; for (int f = 0; f < cells.size(); f++) owners[f] = first.get(groups.get(f));
		successor = LiquidGeometryStateMapper.capture(cells, input.rootWidthMetres(), markers, owners, velocity, controls.stream().mapToInt(Integer::intValue).toArray());
		var measure = new LiquidExactPolygon.Measure[source.size()]; Arrays.fill(measure, LiquidConvexRegion.EMPTY.measure());
		var momentum = new Fraction[3 * source.size()]; Arrays.fill(momentum, Fraction.ZERO);
		for (int f = 0; f < cells.size(); f++) {
			int owner = groups.get(f); var m = LiquidConvexRegion.of(cells.get(f).geometry()).measure(); measure[owner] = measure[owner].add(m);
			for (int a = 0; a < 3; a++) momentum[3 * owner + a] = momentum[3 * owner + a].add(m.volume().multiply(Fraction.of(field.get(f)[a])));
		}
		positions = new float[3 * count]; velocities = new float[3 * count]; var width = Fraction.of(input.rootWidthMetres());
		var originalInventory = LiquidGeometryInventoryView.of(domain.particles);
		for (int p = 0; p < count; p++) {
			int original = ordinals[p], owner = oldMarkers[original]; var center = measure[owner].centroidCoordinates();
			for (int a = 0; a < 3; a++) {
				int extent = a == 0 ? domain.sizeX : a == 1 ? domain.sizeY : domain.sizeZ;
				positions[3 * p + a] = removed[owner].signum() == 0 ? (float) originalInventory.position(original, a)
						: Math.min((float) center[a].multiply(width).rounded(), Math.nextDown(extent * domain.config.cellSize()));
				velocities[3 * p + a] = removed[owner].signum() == 0 ? (float) originalInventory.velocity(original, a)
						: (float) momentum[3 * owner + a].divide(measure[owner].volume()).rounded();
			}
		}
		final int size = count;
		LiquidGeometryStateMapper.requireCompatible(successor, new LiquidGeometryInventoryView() {
			public int count() { return size; }
			public double volume(int p) { return volume[ordinals[p]].toDouble(); }
			public double mass(int p) { return mass[ordinals[p]].toDouble(); }
			public SignedDyadic exactVolume(int p) { return volume[ordinals[p]]; }
			public SignedDyadic exactMass(int p) { return mass[ordinals[p]]; }
			public double position(int p, int a) { return positions[3 * p + a]; }
			public double velocity(int p, int a) { return velocities[3 * p + a]; }
		}, domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize(), domain.geometry.cutCells.copyMicroMasks());
	}
	void publish() {
		if (input == null) return;
		for (int p = 0; p < domain.particles.count(); p++) {
			if (domain.chemicalParcels != null) domain.chemicalParcels.markDirtyCell(domain.particleCellIndex(p));
			domain.particles.setPositionX(p, positions[3 * p]); domain.particles.setPositionY(p, positions[3 * p + 1]); domain.particles.setPositionZ(p, positions[3 * p + 2]);
			domain.particles.setVelocityX(p, velocities[3 * p]); domain.particles.setVelocityY(p, velocities[3 * p + 1]); domain.particles.setVelocityZ(p, velocities[3 * p + 2]);
			if (domain.chemicalParcels != null) domain.chemicalParcels.markDirtyCell(domain.particleCellIndex(p));
		}
		domain.liquidGeometryState = successor; domain.gridDirty = true; domain.velocityGridDirty = true;
	}
	private static LiquidPolyhedron intersection(LiquidPolyhedron original, LiquidPolyhedron clip) {
		var result = original; for (var plane : clip.planes()) { result = plane.clip(result); if (!LiquidConvexRegion.of(result).hasVolume()) break; } return result;
	}
	private static void add(ArrayList<LiquidGeometryRefinement.Cell> cells, ArrayList<Integer> groups, ArrayList<double[]> field,
			LiquidGeometryRefinement.Cell cell, int group, double[] velocity) {
		if (cells.size() == LiquidGeometryInsertionPlan.MAXIMUM_SOURCE_REGIONS) throw new IllegalStateException("Liquid extraction exceeds its geometric fragment budget");
		cells.add(cell); groups.add(group); field.add(velocity);
	}
}
