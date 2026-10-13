package mmm.fluid;

import mmm.granular.GranularCouplingFields;
import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** One captured gas geometry, ownership and raw capacity, bound to complete liquid-position moments. */
final class GasRuntimeGeometry {
	private final float cellSize;
	private final boolean sealedExterior;
	private final long[] occupiedSvq, sourceOccupiedSvq;
	private final List<PhaseLiquidGeometryEdit> liquidStock, sourceLiquidStock;
	private final FlipPressurePhaseGeometry phase;
	private final GasComplementGeometry geometry;
	private final GasChamberTopology topology;
	private final GasChamberCapacity capacity;
	private final int[] pieceNodes, minimumPieceKeys;

	private GasRuntimeGeometry(FluidDomain domain, List<PhaseLiquidGeometryEdit> edits, long[] occupiedSvq,
	                           boolean sealedExterior, boolean allDry) {
		if (domain == null || occupiedSvq == null || occupiedSvq.length != domain.cellCount || edits == null)
			throw new IllegalArgumentException("Gas runtime geometry requires a complete candidate");
		edits = List.copyOf(edits);
		boolean[] selected = new boolean[domain.cellCount];
		for (var edit : edits) {
			if (edit.cell() >= domain.cellCount || selected[edit.cell()]
					|| edit.collisionMask() != domain.geometry.cutCells.mask(edit.cell()))
				throw new IllegalArgumentException("Repeated gas liquid edit or mismatched collision mask");
			selected[edit.cell()] = true;
		}
		this.cellSize = domain.config.cellSize(); this.sealedExterior = sealedExterior;
		this.occupiedSvq = occupiedSvq.clone();
		this.sourceOccupiedSvq = domain.granularCoupling == null ? new long[domain.cellCount]
				: domain.granularCoupling.copySolidSvq();
		var cut = new FlipCutCellGeometry(domain.sizeX, domain.sizeY, domain.sizeZ);
		cut.restoreMicroMasks(domain.geometry.cutCells.copyMicroMasks());
		boolean[] solid = new boolean[domain.cellCount];
		for (int cell = 0; cell < solid.length; cell++) {
			solid[cell] = cut.fullySolid(cell);
			if (solid[cell] != domain.geometry.solid[cell])
				throw new IllegalArgumentException("Gas runtime binary and micro collision geometry disagree");
		}
		MarkerGridIndex rawIndex = index(domain);
		this.sourceLiquidStock = stock(domain, cut, rawIndex);
		MarkerGridIndex phaseIndex = allDry ? emptyIndex(domain.cellCount, 0) : rawIndex;
		var target = new ArrayList<>(allDry ? stock(domain, cut, phaseIndex) : sourceLiquidStock);
		for (var edit : edits) target.set(edit.cell(), edit);
		this.liquidStock = List.copyOf(target);
		phaseIndex.exactCellVolumeInventory = new SignedDyadic[domain.cellCount];
		for (int cell = 0; cell < domain.cellCount; cell++) {
			var tuple = liquidStock.get(cell);
			phaseIndex.exactCellVolumeInventory[cell] = tuple.volume();
			phaseIndex.exactCellMarkerVolume[cell] = tuple.volume().toDouble();
			phaseIndex.markerCount[cell] = tuple.markerCount();
		}
		var occupancy = new GranularCouplingFields(domain.sizeX, domain.sizeY, domain.sizeZ, cellSize);
		occupancy.setFixedCellSvq(this.occupiedSvq);
		this.phase = new FlipPressurePhaseGeometry(domain.sizeX, domain.sizeY, domain.sizeZ);
		GasRuntimeGeometry captured = domain.gasState.frame;
		boolean[] sameLocalStock = matchingLocalStock(captured, cut);
		phase.rebuildLiquidEdits(solid, phaseIndex.exactCellMarkerVolume, cut, occupancy, new boolean[domain.cellCount],
				phaseIndex, domain.particles, cellSize, liquidStock, sameLocalStock == null ? null : captured.phase, sameLocalStock);
		boolean[] sameGeometry = sameLocalStock == null ? null : new boolean[domain.cellCount];
		if (sameGeometry != null) for (int cell = 0; cell < sameGeometry.length; cell++)
			sameGeometry[cell] = phase.sameCellGeometry(cell, captured.phase);
		this.geometry = GasComplementGeometry.capture(domain.sizeX, domain.sizeY, domain.sizeZ, cellSize, solid, cut, phase,
				sameGeometry == null ? null : captured.geometry, sameGeometry);
		this.topology = GasChamberTopology.capture(geometry, sealedExterior);
		this.capacity = GasChamberCapacity.capture(domain, geometry, topology, this.occupiedSvq, liquidStock,
				domain.gasState.frame);
		this.pieceNodes = new int[Math.multiplyExact(8, domain.cellCount)]; Arrays.fill(pieceNodes, -1);
		for (var piece : geometry.pieces()) pieceNodes[piece.id()] = topology.nodeOfPiece(piece.id());
		this.minimumPieceKeys = new int[topology.chambers().size() + 1]; Arrays.fill(minimumPieceKeys, -1);
		for (var chamber : topology.chambers()) minimumPieceKeys[chamber.node()] = chamber.minimumPiece();
	}

	private boolean[] matchingLocalStock(GasRuntimeGeometry captured, FlipCutCellGeometry cut) {
		if (captured == null || !cut.matchesShape(captured.geometry.sizeX(), captured.geometry.sizeY(), captured.geometry.sizeZ())
				|| Float.floatToRawIntBits(cellSize) != Float.floatToRawIntBits(captured.cellSize)
				|| captured.liquidStock.size() != liquidStock.size()) return null;
		boolean[] same = new boolean[liquidStock.size()];
		for (int cell = 0; cell < same.length; cell++) same[cell] = occupiedSvq[cell] == captured.occupiedSvq[cell]
				&& cut.mask(cell) == captured.geometry.collisionMask(cell) && liquidStock.get(cell).equals(captured.liquidStock.get(cell));
		return same;
	}

	static GasRuntimeGeometry capture(FluidDomain domain, PhaseLiquidGeometryEdit edit, long[] occupiedSvq, boolean sealedExterior) {
		return captureLiquidEdits(domain, edit == null ? List.of() : List.of(edit), occupiedSvq, sealedExterior);
	}

	static GasRuntimeGeometry captureLiquidEdits(FluidDomain domain, List<PhaseLiquidGeometryEdit> edits,
	                                          long[] occupiedSvq, boolean sealedExterior) {
		return new GasRuntimeGeometry(domain, edits, occupiedSvq, sealedExterior, false);
	}

	/** Complete future clear: no liquid or solid stock, retaining the captured fixed collision masks. */
	static GasRuntimeGeometry allDry(FluidDomain domain, boolean sealedExterior) {
		if (domain == null) throw new IllegalArgumentException("Gas runtime domain is missing");
		return allDry(domain, new long[domain.cellCount], sealedExterior);
	}

	/** Clears the liquid column while retaining the supplied future solid occupancy. */
	static GasRuntimeGeometry allDry(FluidDomain domain, long[] occupiedSvq, boolean sealedExterior) {
		return new GasRuntimeGeometry(domain, List.of(), occupiedSvq, sealedExterior, true);
	}

	/** Compares physical markers after publication with the complete captured target, including zero components. */
	boolean matchesCarrierGeometry(FluidDomain domain, long[] occupiedSvq) {
		return matches(domain, occupiedSvq, this.occupiedSvq, liquidStock);
	}

	/** Compares all source markers and occupancy before committing a prepared phase or clear candidate. */
	boolean matchesSourceCarrierGeometry(FluidDomain domain, long[] occupiedSvq) {
		return matches(domain, occupiedSvq, sourceOccupiedSvq, sourceLiquidStock);
	}

	private boolean matches(FluidDomain domain, long[] occupiedSvq, long[] expectedOccupied, List<PhaseLiquidGeometryEdit> expectedStock) {
		if (domain == null || geometry.sizeX() != domain.sizeX || geometry.sizeY() != domain.sizeY || geometry.sizeZ() != domain.sizeZ
				|| Float.floatToRawIntBits(cellSize) != Float.floatToRawIntBits(domain.config.cellSize())
				|| !Arrays.equals(expectedOccupied, occupiedSvq)) return false;
		for (int cell = 0; cell < domain.cellCount; cell++)
			if (geometry.collisionMask(cell) != domain.geometry.cutCells.mask(cell)
					|| domain.geometry.solid[cell] != (geometry.collisionMask(cell) == 255)) return false;
		try { return expectedStock.equals(stock(domain, domain.geometry.cutCells, index(domain))); }
		catch (IllegalArgumentException | IllegalStateException | ArithmeticException invalid) { return false; }
	}

	private static MarkerGridIndex emptyIndex(int cells, int markers) {
		var index = new MarkerGridIndex(cells, markers);
		Arrays.fill(index.cellMarkerHead, -1); Arrays.fill(index.cellMarkerTail, -1);
		return index;
	}
	private static MarkerGridIndex index(FluidDomain domain) {
		var index = emptyIndex(domain.cellCount, domain.particles.count());
		for (int marker = 0; marker < domain.particles.count(); marker++) {
			int cell = domain.particleCellIndex(marker), previous = index.cellMarkerTail[cell];
			index.particleCellPrevious[marker] = previous; index.particleCellNext[marker] = -1;
			if (previous < 0) index.cellMarkerHead[cell] = marker;
			else index.particleCellNext[previous] = marker;
			index.cellMarkerTail[cell] = marker;
		}
		return index;
	}
	private static List<PhaseLiquidGeometryEdit> stock(FluidDomain domain, FlipCutCellGeometry cut, MarkerGridIndex index) {
		var stock = new ArrayList<PhaseLiquidGeometryEdit>(domain.cellCount);
		for (int cell = 0; cell < domain.cellCount; cell++)
			stock.add(PhaseLiquidGeometryEdit.current(cell, cut.mask(cell), domain.config.cellSize(), index, domain.particles));
		return List.copyOf(stock);
	}

	GasComplementGeometry geometry() { return geometry; }
	float cellSize() { return cellSize; }

	boolean cellDependsOnNeighborFractions(int cell) {
		return phase.dependsOnNeighborFractions(cell, liquidStock.get(cell), cellSize);
	}

	double dryMicroFaceArea(int cell, int micro, int axis, int coordinate) {
		return GasFaceIntersection.area(phase, cell, -1, axis, coordinate, 0,
				.5 * (micro >>> ((axis + 1) % 3) & 1), .5 * (micro >>> ((axis + 2) % 3) & 1), .5);
	}
	LiquidPressureGeometry liquidPressureGeometry(FluidDomain domain) {
		requireCurrentCarrierGeometry(domain);
		return LiquidPressureGeometry.capture(domain, phase, liquidStock);
	}
	LiquidSpatialInput liquidSpatialInput(FluidDomain domain) {
		requireCurrentCarrierGeometry(domain);
		return LiquidSpatialInput.capture(domain, phase, this);
	}
	private void requireCurrentCarrierGeometry(FluidDomain domain) {
		long[] occupied = domain.granularCoupling == null ? new long[domain.cellCount] : domain.granularCoupling.copySolidSvq();
		if (domain.gasState.frame != this || sealedExterior != domain.gasState.sealedDomainBoundary || !matchesCarrierGeometry(domain, occupied))
			throw new GasTopologyBlockedException("Pressure gas frame no longer matches carrier geometry");
	}
	float planeNormal(int cell, int micro, int axis) { return phase.planeNormal(cell, micro, axis); }
	double planeOffset(int cell, int micro) { return phase.planeOffset(cell, micro); }
	double liquidFractionExact(int cell, int micro) { return phase.liquidFractionExact(cell, micro); }
	PlicPlaneOffset.Result offsetBracket(int cell, int micro) { return phase.offsetBracket(cell, micro); }
	SignedDyadic poreVolume(int cell) { return phase.poreVolume(cell); }
	GasChamberTopology topology() { return topology; }
	GasChamberCapacity capacity() { return capacity; }
	List<PhaseLiquidGeometryEdit> liquidStock() { return liquidStock; }
	long[] occupiedSvq() { return occupiedSvq.clone(); }
	int[] pieceNodes() { return pieceNodes.clone(); }
	int[] minimumPieceKeys() { return minimumPieceKeys.clone(); }
	int nodeCount() { return minimumPieceKeys.length - 1; }
	int minimumPiece(int node) {
		if (node < 0 || node >= minimumPieceKeys.length) throw new IllegalArgumentException("Invalid gas node");
		return minimumPieceKeys[node];
	}
	int nodeOfPiece(int piece) {
		if (piece < 0 || piece >= pieceNodes.length) throw new IllegalArgumentException("Invalid gas piece");
		return pieceNodes[piece];
	}
	/** Cell views retain only a unique common piece owner; absent or multiple owners return -1. */
	int commonCellNode(int cell) {
		if (cell < 0 || cell >= liquidStock.size()) throw new IllegalArgumentException("Invalid gas cell");
		int owner = -1;
		for (int micro = 0; micro < 8; micro++) {
			int next = pieceNodes[8 * cell + micro]; if (next < 0) continue;
			if (owner >= 0 && owner != next) return -1;
			owner = next;
		}
		return owner;
	}
	boolean sealedExterior() { return sealedExterior; }
}
