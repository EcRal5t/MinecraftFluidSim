package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Plans a complete source batch in actual remaining gas before any inventory column changes. */
final class LiquidGeometryInsertionPlan {
	/** Allocation ceiling for a private source plan; inventory marker and chemical budgets remain independent. */
	static final int MAXIMUM_SOURCE_REGIONS = 65_536;
	record Insertion(int x, int y, int z, float volume) { }
	final LiquidGeometryState geometry;
	final List<LiquidPolyhedron.Point> positions;

	LiquidGeometryInsertionPlan(LiquidGeometryState input, int sizeX, int sizeY, int sizeZ, float cellSize,
			byte[] collision, List<Insertion> insertions, int maximumRegions) {
		int span = LiquidGeometryDomainPartition.rootSpan(sizeX, sizeY, sizeZ);
		int level = Integer.numberOfTrailingZeros(span); double h = 1.0 / span;
		var source = LiquidGeometryStateMapper.restore(input); var requested = new ArrayList<>(source);
		for (var insertion : insertions) {
			if (insertion.x < 0 || insertion.x >= sizeX || insertion.y < 0 || insertion.y >= sizeY || insertion.z < 0 || insertion.z >= sizeZ
					|| !(insertion.volume > 0) || !Float.isFinite(insertion.volume)) throw new IllegalArgumentException("Invalid geometric liquid insertion");
			requested.add(new LiquidGeometryRefinement.Cell(level, insertion.x, insertion.y, insertion.z,
					LiquidPolyhedron.box(insertion.x * h, insertion.y * h, insertion.z * h, (insertion.x + 1) * h, (insertion.y + 1) * h, (insertion.z + 1) * h)));
		}
		var domain = new LiquidGeometryDomainPartition(sizeX, sizeY, sizeZ, cellSize, collision, requested, maximumRegions);
		if (input.rootWidthMetres() != domain.rootWidthMetres) throw new IllegalArgumentException("Liquid source geometry uses a different root");
		var remap = new LiquidGeometryRemap(domain.leaves); var cells = new ArrayList<LiquidGeometryRefinement.Cell>();
		var owners = new ArrayList<Integer>(); var velocities = new ArrayList<double[]>(); var first = new HashMap<Integer, Integer>();
		var liquids = new ArrayList<ArrayList<LiquidPolyhedron>>();
		for (var leaf : domain.leaves) liquids.add(new ArrayList<>());
		int[] previousOwners = input.copyFragmentOwners();
		for (int f = 0; f < source.size(); f++) for (var piece : remap.partitionGeometry(source.get(f).geometry())) {
			first.putIfAbsent(previousOwners[f], cells.size());
			add(cells, owners, velocities, liquids, domain, piece, previousOwners[f], input.cells().get(f).copyVelocity(), maximumRegions);
		}
		var observations = new ArrayList<LiquidPolyhedron.Point>();
		var rootVolume = Fraction.of(span); rootVolume = rootVolume.multiply(rootVolume).multiply(rootVolume);
		for (int p = 0; p < insertions.size(); p++) {
			var insertion = insertions.get(p); var available = new ArrayList<LiquidPolyhedron>();
			for (int c = 0; c < domain.leaves.size(); c++) {
				var leaf = domain.leaves.get(c); int shift = leaf.level() - level;
				if (shift < 0 || leaf.x() >>> shift != insertion.x || leaf.y() >>> shift != insertion.y || leaf.z() >>> shift != insertion.z) continue;
				for (var phase : LiquidPhasePartition.build(leaf, liquids.get(c), maximumRegions))
					if (phase.liquid() < 0) available.add(phase.cell().geometry());
			}
			int owner = source.size() + p; first.put(owner, cells.size());
			var selected = new LiquidGeometrySourcePlan(available, Fraction.of(insertion.volume).divide(rootVolume), maximumRegions - cells.size());
			var measure = LiquidConvexRegion.EMPTY.measure();
			for (var piece : selected.geometry) {
				measure = measure.add(LiquidConvexRegion.of(piece).measure());
				for (var part : remap.partitionGeometry(piece)) add(cells, owners, velocities, liquids, domain, part, owner, new double[3], maximumRegions);
			}
			var center = measure.centroidCoordinates(); var width = Fraction.of(input.rootWidthMetres());
			// Inventory centroids are binary32 observations; actual interfaces retain exact coordinates.
			observations.add(new LiquidPolyhedron.Point(observe(center[0].multiply(width), sizeX, cellSize),
					observe(center[1].multiply(width), sizeY, cellSize), observe(center[2].multiply(width), sizeZ, cellSize)));
		}
		int[] markers = new int[input.markerCount() + insertions.size()]; var previousMarkers = input.copyMarkerCells();
		for (int p = 0; p < previousMarkers.length; p++) markers[p] = first.get(previousMarkers[p]);
		for (int p = 0; p < insertions.size(); p++) markers[input.markerCount() + p] = first.get(source.size() + p);
		var velocity = new double[3 * cells.size()];
		for (int f = 0; f < cells.size(); f++) System.arraycopy(velocities.get(f), 0, velocity, 3 * f, 3);
		geometry = LiquidGeometryStateMapper.capture(cells, input.rootWidthMetres(), markers, owners.stream().mapToInt(Integer::intValue).toArray(), velocity);
		positions = List.copyOf(observations);
	}
	private static float observe(Fraction position, int size, float h) { return Math.min((float) position.rounded(), Math.nextDown(size * h)); }
	private static void add(ArrayList<LiquidGeometryRefinement.Cell> cells, ArrayList<Integer> owners, ArrayList<double[]> velocities,
			ArrayList<ArrayList<LiquidPolyhedron>> liquids, LiquidGeometryDomainPartition domain, LiquidGeometryRemap.GeometryFragment piece,
			int owner, double[] velocity, int maximum) {
		if (cells.size() == maximum) throw new IllegalStateException("Liquid insertion exceeds its geometric region budget");
		cells.add(LiquidPhasePartition.Address.of(domain.leaves.get(piece.cell())).cell(piece.geometry()));
		owners.add(owner); velocities.add(velocity); liquids.get(piece.cell()).add(piece.geometry());
	}
}
