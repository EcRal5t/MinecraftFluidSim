package mmm.fluid;

import mmm.inventory.LiquidCellInventory;

import mmm.granular.GranularCouplingFields;
import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Captured gas geometry, ownership and raw capacity, bound to authoritative liquid components. */
final class GasRuntimeGeometry {
	private final float cellSize;
	private final boolean sealedExterior;
	private final long[] occupiedSvq, sourceOccupiedSvq;
	private final byte[] sourceCollisionMasks;
	private final List<PhaseLiquidGeometryEdit> liquidStock;
	private final FlipPressurePhaseGeometry phase;
	private final GasComplementGeometry geometry;
	private final GasChamberTopology topology;
	private final GasChamberCapacity capacity;
	private final int[] minimumPieceKeys;
	private final LiquidCellInventory cellOwner;
	private final long sourceCellGeometryRevision;
	private LiquidCellInventory.Snapshot targetCells;

	private GasRuntimeGeometry(FluidDomain domain, List<PhaseLiquidGeometryEdit> edits, long[] occupiedSvq,
                                   boolean sealedExterior, boolean allDry) {
		this(domain, edits, occupiedSvq, sealedExterior, allDry, null);
	}
	private GasRuntimeGeometry(FluidDomain domain, List<PhaseLiquidGeometryEdit> edits, long[] occupiedSvq,
	                           boolean sealedExterior, boolean allDry, byte[] futureCollisionMasks) {
		if (domain == null || occupiedSvq == null || occupiedSvq.length != domain.cellCount || edits == null)
			throw new IllegalArgumentException("Gas runtime geometry requires a complete candidate");
		edits = List.copyOf(edits);
		sourceCollisionMasks = domain.geometry.cutCells.copyMicroMasks();
		byte[] targetMasks = futureCollisionMasks == null ? sourceCollisionMasks : futureCollisionMasks.clone();
		if (targetMasks.length != domain.cellCount) throw new IllegalArgumentException("Future gas collision shape differs");
		boolean[] selected = new boolean[domain.cellCount];
		for (var edit : edits) {
			if (edit.cell() >= domain.cellCount || selected[edit.cell()]
					|| edit.collisionMask() != Byte.toUnsignedInt(targetMasks[edit.cell()]))
				throw new IllegalArgumentException("Repeated gas liquid edit or mismatched collision mask");
			selected[edit.cell()] = true;
		}
		this.cellSize = domain.config.cellSize(); this.sealedExterior = sealedExterior;
		this.cellOwner = domain.liquidCells;
		if (cellOwner == null) throw new IllegalArgumentException("Gas geometry requires authoritative liquid cells");
		this.sourceCellGeometryRevision = cellOwner.geometryRevision();
		if (edits.isEmpty() && !allDry) {
			targetCells = cellOwner.snapshot();
		}
		this.occupiedSvq = occupiedSvq.clone();
		this.sourceOccupiedSvq = domain.granularCoupling == null ? new long[domain.cellCount] : domain.granularCoupling.copySolidSvq();
        var cut = new FlipCutCellGeometry(domain.sizeX, domain.sizeY, domain.sizeZ);
        cut.restoreMicroMasks(targetMasks);
        boolean[] solid = new boolean[domain.cellCount];
        for (int cell = 0; cell < solid.length; cell++) {
            solid[cell] = cut.fullySolid(cell);
            if (futureCollisionMasks == null && solid[cell] != domain.geometry.solid[cell])
                throw new IllegalArgumentException("Gas binary and micro collision geometry disagree");
        }
		GasRuntimeGeometry captured = domain.gasState.frame;
		ArrayList<PhaseLiquidGeometryEdit> target;
		if (allDry) target = new ArrayList<>(PhaseLiquidGeometryEdit.fromInventory(domain,
				new LiquidCellInventory(domain.cellCount, cellOwner.chemicalRegistry())));
		else if (edits.size() == domain.cellCount) {
			// A wholesale remap already supplies every future component. Its source is bound by the raw columns.
			target = new ArrayList<>(java.util.Collections.nCopies(domain.cellCount, null));
		} else {
			boolean reuseSource = captured != null && captured.matchesCarrierGeometry(domain, sourceOccupiedSvq);
			target = new ArrayList<>(reuseSource ? captured.liquidStock : PhaseLiquidGeometryEdit.fromInventory(domain, cellOwner));
		}
		for (var edit : edits) {
			target.set(edit.cell(), edit);
		}
		this.liquidStock = List.copyOf(target);
		boolean[] sameLocalStock = matchingLocalStock(captured, cut, liquidStock, this.occupiedSvq, cellSize);
		this.phase = capturePhase(domain, cut, solid, this.occupiedSvq, liquidStock, captured, sameLocalStock);
		if (sameLocalStock == null) {
			this.geometry = GasComplementGeometry.capture(domain.sizeX, domain.sizeY, domain.sizeZ, cellSize, solid, cut, phase);
		} else {
			boolean[] changed = new boolean[domain.cellCount];
			for (int cell = 0; cell < changed.length; cell++) changed[cell] = !phase.sameCellGeometry(cell, captured.phase);
			this.geometry = GasComplementGeometry.capture(domain.sizeX,domain.sizeY,domain.sizeZ,cellSize,
					solid,cut,phase,captured.geometry,changed);
		}
		this.topology = GasChamberTopology.capture(geometry, sealedExterior);
		this.capacity = GasChamberCapacity.capture(domain, geometry, topology, this.occupiedSvq, liquidStock, captured);
		this.minimumPieceKeys = new int[topology.chambers().size() + 1]; Arrays.fill(minimumPieceKeys, -1);
		for (var chamber : topology.chambers()) minimumPieceKeys[chamber.node()] = chamber.minimumPiece();
	}

	private static boolean[] matchingLocalStock(GasRuntimeGeometry captured, FlipCutCellGeometry cut,
			List<PhaseLiquidGeometryEdit> liquidStock, long[] occupiedSvq, float cellSize) {
		if (captured == null || !cut.matchesShape(captured.geometry.sizeX(), captured.geometry.sizeY(), captured.geometry.sizeZ())
				|| Float.floatToRawIntBits(cellSize) != Float.floatToRawIntBits(captured.cellSize)
				|| captured.liquidStock.size() != liquidStock.size()) return null;
		boolean[] same = new boolean[liquidStock.size()];
		for (int cell = 0; cell < same.length; cell++) same[cell] = occupiedSvq[cell] == captured.occupiedSvq[cell]
				&& cut.mask(cell) == captured.geometry.collisionMask(cell) && liquidStock.get(cell).equals(captured.liquidStock.get(cell));
		return same;
	}
	private static FlipPressurePhaseGeometry capturePhase(FluidDomain domain, FlipCutCellGeometry cut, boolean[] solid,
			long[] occupied, List<PhaseLiquidGeometryEdit> stock,
			GasRuntimeGeometry captured, boolean[] sameLocalStock) {
		double[] liquidVolume = new double[domain.cellCount];
		for (int cell = 0; cell < domain.cellCount; cell++) liquidVolume[cell] = stock.get(cell).volume().toDouble();
		var occupancy = new GranularCouplingFields(domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize());
		occupancy.setFixedCellSvq(occupied);
		var phase = new FlipPressurePhaseGeometry(domain.sizeX, domain.sizeY, domain.sizeZ);
		phase.rebuildLiquidEdits(solid, liquidVolume, cut, occupancy, new boolean[domain.cellCount],
				domain.config.cellSize(), stock, sameLocalStock == null ? null : captured.phase, sameLocalStock);
		return phase;
	}

	static GasRuntimeGeometry capture(FluidDomain domain, PhaseLiquidGeometryEdit edit, long[] occupiedSvq, boolean sealedExterior) {
		return captureLiquidEdits(domain, edit == null ? List.of() : List.of(edit), occupiedSvq, sealedExterior);
	}

	static GasRuntimeGeometry captureLiquidEdits(FluidDomain domain, List<PhaseLiquidGeometryEdit> edits,
	                                          long[] occupiedSvq, boolean sealedExterior) {
		return new GasRuntimeGeometry(domain, edits, occupiedSvq, sealedExterior, false);
	}

	/** A complete cell transaction supplies geometry once, before any authoritative row is published. */
	static GasRuntimeGeometry captureCellFuture(FluidDomain domain, LiquidCellInventory future,
	                                           long[] occupied, boolean sealed) {
		return captureCellFuture(domain, future, occupied, sealed, null);
	}
	static GasRuntimeGeometry captureCellFuture(FluidDomain domain, LiquidCellInventory future,
	                                           long[] occupied, boolean sealed, byte[] collisionMasks) {
		var result = new GasRuntimeGeometry(domain, PhaseLiquidGeometryEdit.fromInventory(domain, future, collisionMasks),
				occupied, sealed, false, collisionMasks);
		result.targetCells = future.snapshot();
		return result;
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

	/** Matches captured liquid geometry across immutable backend handoff and energy-only edits. */
	boolean matchesCarrierGeometry(FluidDomain domain, long[] occupiedSvq) {
		if (!matchesGeometryInputs(domain, occupiedSvq, this.occupiedSvq)) return false;
		return targetCells != null ? domain.liquidCells.hasSameGeometry(targetCells)
				: liquidStock.equals(PhaseLiquidGeometryEdit.fromInventory(domain,domain.liquidCells));
	}

	/** Checks source liquid geometry and occupancy before committing a prepared phase or clear candidate. */
	boolean matchesSourceCarrierGeometry(FluidDomain domain, long[] occupiedSvq) {
		return matchesGeometryInputs(domain, occupiedSvq, sourceOccupiedSvq, sourceCollisionMasks) && domain.liquidCells == cellOwner
				&& cellOwner.geometryRevision() == sourceCellGeometryRevision;
	}

	private boolean matchesGeometryInputs(FluidDomain domain, long[] occupiedSvq, long[] expectedOccupied) {
		return matchesGeometryInputs(domain, occupiedSvq, expectedOccupied, null);
	}
	private boolean matchesGeometryInputs(FluidDomain domain, long[] occupiedSvq, long[] expectedOccupied, byte[] expectedMasks) {
		if (domain == null || geometry.sizeX() != domain.sizeX || geometry.sizeY() != domain.sizeY || geometry.sizeZ() != domain.sizeZ
				|| Float.floatToRawIntBits(cellSize) != Float.floatToRawIntBits(domain.config.cellSize())
				|| !Arrays.equals(expectedOccupied, occupiedSvq)) return false;
		for (int cell = 0; cell < domain.cellCount; cell++)
			if ((expectedMasks == null ? geometry.collisionMask(cell) : Byte.toUnsignedInt(expectedMasks[cell])) != domain.geometry.cutCells.mask(cell)
					|| domain.geometry.solid[cell] != (domain.geometry.cutCells.mask(cell) == 255)) return false;
		return true;
	}

	GasGeometryTransition transitionFrom(GasRuntimeGeometry source) {
		return new GasGeometryTransition(source, this);
	}
	GasComplementGeometry geometry() { return geometry; }
	FlipPressurePhaseGeometry phase() { return phase; }
	float cellSize() { return cellSize; }

	boolean cellDependsOnNeighborFractions(int cell) {
		return phase.dependsOnNeighborFractions(cell, liquidStock.get(cell), cellSize);
	}

	/** A stock edit changes its own geometry and only direct neighbors reading its fraction. */
	boolean[] phaseEditCells(int editedCell) {
		boolean[] variable = new boolean[liquidStock.size()]; variable[editedCell] = true;
		int[] sizes = {geometry.sizeX(), geometry.sizeY(), geometry.sizeZ()}, strides = {1, sizes[0], sizes[0] * sizes[1]};
		int[] coordinate = {editedCell % sizes[0], editedCell / sizes[0] % sizes[1], editedCell / strides[2]};
		for (int axis = 0; axis < 3; axis++) for (int sign : new int[]{-1, 1})
			if (coordinate[axis] + sign >= 0 && coordinate[axis] + sign < sizes[axis]) {
				int neighbor = editedCell + sign * strides[axis];
				variable[neighbor] = cellDependsOnNeighborFractions(neighbor);
			}
		return variable;
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
	int[] pieceNodes() { return topology.copyPieceNodes(); }
	int[] minimumPieceKeys() { return minimumPieceKeys.clone(); }
	int nodeCount() { return minimumPieceKeys.length - 1; }
	int minimumPiece(int node) {
		if (node < 0 || node >= minimumPieceKeys.length) throw new IllegalArgumentException("Invalid gas node");
		return minimumPieceKeys[node];
	}
	int nodeOfPiece(int piece) {
		if (piece < 0 || piece >= 8 * liquidStock.size()) throw new IllegalArgumentException("Invalid gas piece");
		return topology.nodeOfPiece(piece);
	}
	/** Cell views retain only a unique common piece owner; absent or multiple owners return -1. */
	int commonCellNode(int cell) {
		if (cell < 0 || cell >= liquidStock.size()) throw new IllegalArgumentException("Invalid gas cell");
		int owner = -1;
		for (int micro = 0; micro < 8; micro++) {
			int next = topology.nodeOfPiece(8 * cell + micro); if (next < 0) continue;
			if (owner >= 0 && owner != next) return -1;
			owner = next;
		}
		return owner;
	}
	boolean sealedExterior() { return sealedExterior; }
}
