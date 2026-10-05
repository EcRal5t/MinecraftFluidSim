package cc.ecisr.mmm.fluid;

import java.util.List;
import cc.ecisr.mmm.geometry.ResolvedControlVolume;

/** Immutable liquid interfaces and their marker quadrature, in a normalized dyadic root. */
public final class LiquidGeometryState {
	private final double rootWidthMetres;
	private final List<ResolvedControlVolume> cells;
	private final int[] markerCells;
	private final int[] fragmentOwners;

	public LiquidGeometryState(double rootWidthMetres, List<ResolvedControlVolume> cells, int[] markerCells) {
		this(rootWidthMetres, cells, markerCells, cells == null ? null : java.util.stream.IntStream.range(0, cells.size()).toArray());
	}
	/** Fragment owners reference the canonical first fragment of one inventory group. */
	public LiquidGeometryState(double rootWidthMetres, List<ResolvedControlVolume> cells, int[] markerCells, int[] fragmentOwners) {
		if (!(rootWidthMetres > 0) || !Double.isFinite(rootWidthMetres) || cells == null || markerCells == null)
			throw new IllegalArgumentException("Invalid liquid geometry state");
		if (fragmentOwners == null || fragmentOwners.length != cells.size()) throw new IllegalArgumentException("Incomplete liquid fragment ownership");
		this.cells = List.copyOf(cells); this.markerCells = markerCells.clone();
		this.fragmentOwners = fragmentOwners.clone();
		for (int c = 1; c < cells.size(); c++) if (compareGeometry(cells.get(c - 1), cells.get(c)) >= 0)
			throw new IllegalArgumentException("Liquid geometry fragments must be unique and canonical");
		boolean[] supported = new boolean[cells.size()];
		for (int c = 0; c < fragmentOwners.length; c++) {
			int owner = fragmentOwners[c];
			if (owner < 0 || owner > c || fragmentOwners[owner] != owner) throw new IllegalArgumentException("Liquid fragment owner is not canonical");
		}
		for (int cell : markerCells) {
			if (cell < 0 || cell >= cells.size() || fragmentOwners[cell] != cell) throw new IllegalArgumentException("Liquid quadrature has no inventory group");
			supported[cell] = true;
		}
		for (int owner : fragmentOwners) if (!supported[owner]) throw new IllegalArgumentException("Liquid geometry has no inventory quadrature");
		this.rootWidthMetres = rootWidthMetres;
	}
	public double rootWidthMetres() { return rootWidthMetres; }
	public List<ResolvedControlVolume> cells() { return cells; }
	public int markerCount() { return markerCells.length; }
	public int[] copyMarkerCells() { return markerCells.clone(); }
	public int[] copyFragmentOwners() { return fragmentOwners.clone(); }
	public long cpuBytes() {
		long bytes = (long) (markerCells.length + fragmentOwners.length) * Integer.BYTES;
		for (var cell : cells) bytes = Math.addExact(bytes, cell.cpuBytes());
		return bytes;
	}
	public static int compare(ResolvedControlVolume a, ResolvedControlVolume b) { return ResolvedControlVolume.compare(a, b); }
	public static int compareGeometry(ResolvedControlVolume a, ResolvedControlVolume b) { return ResolvedControlVolume.compareGeometry(a, b); }
}