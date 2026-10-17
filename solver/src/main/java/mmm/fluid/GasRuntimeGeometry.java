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
	private final GasGeometryTransition phaseTransition;
	private final int[] minimumPieceKeys;
	private float[] currentMarkerGeometry;

	private GasRuntimeGeometry(FluidDomain domain, List<PhaseLiquidGeometryEdit> edits, long[] occupiedSvq,
	                           boolean sealedExterior, boolean allDry) {
		this(domain, edits, occupiedSvq, sealedExterior, allDry, null);
	}
	private GasRuntimeGeometry(FluidDomain domain, List<PhaseLiquidGeometryEdit> edits, long[] occupiedSvq,
	                           boolean sealedExterior, boolean allDry, PhaseSource prepared) {
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
		this.sourceOccupiedSvq = prepared != null ? prepared.frame.occupiedSvq
				: domain.granularCoupling == null ? new long[domain.cellCount] : domain.granularCoupling.copySolidSvq();
		if (prepared != null) {
			prepared.requireCurrent(domain);
			if (edits.size() != 1 || edits.getFirst().cell() != prepared.editedCell)
				throw new IllegalArgumentException("BOIL geometry source requires its prepared edited cell");
		}
		var cut = prepared == null ? new FlipCutCellGeometry(domain.sizeX, domain.sizeY, domain.sizeZ) : prepared.cut;
		if (prepared == null) cut.restoreMicroMasks(domain.geometry.cutCells.copyMicroMasks());
		boolean[] solid = prepared == null ? new boolean[domain.cellCount] : prepared.solid;
		if (prepared == null) for (int cell = 0; cell < solid.length; cell++) {
			solid[cell] = cut.fullySolid(cell);
			if (solid[cell] != domain.geometry.solid[cell])
				throw new IllegalArgumentException("Gas runtime binary and micro collision geometry disagree");
		}
		MarkerGridIndex rawIndex = prepared == null ? index(domain) : null;
		this.sourceLiquidStock = prepared == null ? stock(domain, cut, rawIndex) : prepared.frame.liquidStock;
		var target = new ArrayList<>(allDry ? stock(domain, cut, emptyIndex(domain.cellCount, 0)) : sourceLiquidStock);
		for (var edit : edits) {
			var source = target.get(edit.cell());

			target.set(edit.cell(), edit);
		}
		this.liquidStock = List.copyOf(target);
		boolean local = prepared != null && sealedExterior == prepared.frame.sealedExterior
				&& Arrays.equals(this.occupiedSvq, prepared.frame.occupiedSvq);
		if (local) {
			this.phase = prepared.phase(edits.getFirst()).copy();
			int[] cells = prepared.region.cells();
			boolean[] changed = new boolean[domain.cellCount];
			for (int cell : cells) changed[cell] = !phase.sameCellGeometry(cell, prepared.frame.phase);
			this.geometry = prepared.geometry().capture(solid, cut, phase, changed);
			this.topology = prepared.topology().capture(geometry);
			this.capacity = prepared.capacity().capture(domain, geometry, topology, this.occupiedSvq, liquidStock);
		} else {
			MarkerGridIndex phaseIndex = allDry ? emptyIndex(domain.cellCount, 0) : rawIndex == null ? index(domain) : rawIndex;
			GasRuntimeGeometry captured = domain.gasState.frame;
			boolean[] sameLocalStock = matchingLocalStock(captured, cut, liquidStock, this.occupiedSvq, cellSize);
			this.phase = capturePhase(domain, cut, solid, this.occupiedSvq, liquidStock, phaseIndex, captured, sameLocalStock);
			if (sameLocalStock == null) {
				this.geometry = GasComplementGeometry.capture(domain.sizeX, domain.sizeY, domain.sizeZ, cellSize, solid, cut, phase);
			} else {
				boolean[] changed = new boolean[domain.cellCount];
				for (int cell = 0; cell < changed.length; cell++) changed[cell] = !phase.sameCellGeometry(cell, captured.phase);
				var region = new GasComplementGeometry.Region(domain.sizeX, domain.sizeY, domain.sizeZ, changed);
				this.geometry = GasComplementGeometry.prepare(captured.geometry, region).capture(solid, cut, phase, changed);
			}
			this.topology = GasChamberTopology.capture(geometry, sealedExterior);
			this.capacity = GasChamberCapacity.capture(domain, geometry, topology, this.occupiedSvq, liquidStock, captured);
		}
		phaseTransition = local ? prepared.transition(edits.getFirst()) : null;
		if (phaseTransition != null) phaseTransition.complete(geometry);
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
			long[] occupied, List<PhaseLiquidGeometryEdit> stock, MarkerGridIndex index,
			GasRuntimeGeometry captured, boolean[] sameLocalStock) {
		index.exactCellVolumeInventory = new SignedDyadic[domain.cellCount];
		for (int cell = 0; cell < domain.cellCount; cell++) {
			var tuple = stock.get(cell);
			index.exactCellVolumeInventory[cell] = tuple.volume();
			index.exactCellMarkerVolume[cell] = tuple.volume().toDouble(); index.markerCount[cell] = tuple.markerCount();
		}
		var occupancy = new GranularCouplingFields(domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize());
		occupancy.setFixedCellSvq(occupied);
		var phase = new FlipPressurePhaseGeometry(domain.sizeX, domain.sizeY, domain.sizeZ);
		phase.rebuildLiquidEdits(solid, index.exactCellMarkerVolume, cut, occupancy, new boolean[domain.cellCount],
				index, domain.particles, domain.config.cellSize(), stock, sameLocalStock == null ? null : captured.phase, sameLocalStock);
		return phase;
	}

	static GasRuntimeGeometry capture(FluidDomain domain, PhaseLiquidGeometryEdit edit, long[] occupiedSvq, boolean sealedExterior) {
		return captureLiquidEdits(domain, edit == null ? List.of() : List.of(edit), occupiedSvq, sealedExterior);
	}

	static GasRuntimeGeometry captureLiquidEdits(FluidDomain domain, List<PhaseLiquidGeometryEdit> edits,
	                                          long[] occupiedSvq, boolean sealedExterior) {
		return new GasRuntimeGeometry(domain, edits, occupiedSvq, sealedExterior, false);
	}

	/** One synchronous phase search shares its validated source and local PLIC workspace. */
	static final class PhaseSource {
		private final FluidDomain owner;
		private final GasRuntimeGeometry frame;
		private final int editedCell;
		private final GasComplementGeometry.Region region;
		private GasComplementGeometry.Prepared geometry;
		private GasChamberTopology.Prepared topology;
		private GasChamberCapacity.Prepared capacity;
		private FlipPressurePhaseGeometry scratch;
		private PhaseLiquidGeometryEdit scratchEdit;
		private GasGeometryTransition trialTransition;
		private final FlipCutCellGeometry cut;
		private final boolean[] solid;
		private final long inventoryRevision, carrierRevision;
		private final int markerCount;
		private PhaseSource(FluidDomain owner, GasRuntimeGeometry frame, int editedCell) {
			this.owner = owner; this.frame = frame; this.editedCell = editedCell;
			this.region = new GasComplementGeometry.Region(owner.sizeX, owner.sizeY, owner.sizeZ, frame.phaseEditCells(editedCell));
			// Trials share collision inputs; complete runtime frames copy the local PLIC workspace.
			cut = new FlipCutCellGeometry(owner.sizeX, owner.sizeY, owner.sizeZ);
			cut.restoreMicroMasks(owner.geometry.cutCells.copyMicroMasks());
			solid = owner.geometry.solid.clone();
			this.inventoryRevision = owner.markerGrid.inventoryRevision;
			this.carrierRevision = owner.carrierMutationRevision(); this.markerCount = owner.particles.count();
		}
		GasRuntimeGeometry frame() { return frame; }
		GasComplementGeometry.Region region() { return region; }
		private GasComplementGeometry.Prepared geometry() {
			if (geometry == null) geometry = GasComplementGeometry.prepare(frame.geometry, region);
			return geometry;
		}
		GasChamberTopology.Prepared topology() {
			if (topology == null) topology = GasChamberTopology.prepare(frame.geometry, frame.sealedExterior, region);
			return topology;
		}
		GasChamberCapacity.Prepared capacity() {
			if (capacity == null) capacity = GasChamberCapacity.prepare(frame, topology());
			return capacity;
		}
		/** Borrowed local phase workspace; an admitted runtime frame takes its own copy. */
		FlipPressurePhaseGeometry phase(PhaseLiquidGeometryEdit edit) {
			if (edit == null || edit.cell() != editedCell || edit.collisionMask() != cut.mask(editedCell))
				throw new IllegalArgumentException("BOIL PLIC requires its prepared edited cell and collision mask");
			if (owner.gasState.frame != frame) throw new IllegalStateException("Phase geometry source changed during candidate search");
			if (scratchEdit != edit) {
				if (scratch == null) scratch = frame.phase.copy();
				scratchEdit = null; trialTransition = null;
				scratch.rebuildEditedCells(frame.phase, edit, region.cells(), frame.liquidStock, cut, solid, frame.cellSize);
				scratchEdit = edit;
			}
			return scratch;
		}

		GasGeometryTransition transition(PhaseLiquidGeometryEdit edit) {
			var candidate = phase(edit);
			if (trialTransition == null) trialTransition = new GasGeometryTransition(frame, candidate);
			return trialTransition;
		}

		// The synchronous search does not mutate source carriers. Publication checks actual source stock.
		private void requireCurrent(FluidDomain domain) {
			if (domain != owner || domain.gasState.frame != frame || domain.gridDirty
					|| inventoryRevision != domain.markerGrid.inventoryRevision
					|| carrierRevision != domain.carrierMutationRevision() || markerCount != domain.particles.count())
				throw new IllegalStateException("Phase geometry source changed during candidate search");
		}
	}

	static PhaseSource preparePhaseSource(FluidDomain domain, int editedCell) {
		if (editedCell < 0 || editedCell >= domain.cellCount) throw new IllegalArgumentException("BOIL edited cell is outside the domain");
		domain.ensureInventoryGrid();
		var frame = domain.gasState.frame;
		if (frame == null) return null;
		long[] occupied = domain.granularCoupling == null ? new long[domain.cellCount] : domain.granularCoupling.copySolidSvq();
		if (!frame.matchesCarrierGeometry(domain, occupied)) return null;
		return new PhaseSource(domain, frame, editedCell);
	}

	static GasRuntimeGeometry capturePhaseEdit(FluidDomain domain, PhaseLiquidGeometryEdit edit,
	                                          long[] occupied, boolean sealed, PhaseSource source) {
		return new GasRuntimeGeometry(domain, List.of(edit), occupied, sealed, false, source);
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
		if (!matchesGeometryInputs(domain, occupiedSvq, this.occupiedSvq)) return false;
		if (matchesRawMarkers(domain)) return true;
		if (!matches(domain, occupiedSvq, this.occupiedSvq, liquidStock)) return false;
		currentMarkerGeometry = new float[Math.multiplyExact(4, domain.particles.count())];
		for (int marker = 0; marker < domain.particles.count(); marker++) {
			currentMarkerGeometry[4 * marker] = domain.particles.positionX(marker);
			currentMarkerGeometry[4 * marker + 1] = domain.particles.positionY(marker);
			currentMarkerGeometry[4 * marker + 2] = domain.particles.positionZ(marker);
			currentMarkerGeometry[4 * marker + 3] = domain.particles.volume(marker);
		}
		return true;
	}
	private boolean matchesRawMarkers(FluidDomain domain) {
		if (currentMarkerGeometry == null || currentMarkerGeometry.length != 4 * domain.particles.count()) return false;
		for (int marker = 0; marker < domain.particles.count(); marker++)
			if (Float.floatToRawIntBits(currentMarkerGeometry[4 * marker]) != Float.floatToRawIntBits(domain.particles.positionX(marker))
					|| Float.floatToRawIntBits(currentMarkerGeometry[4 * marker + 1]) != Float.floatToRawIntBits(domain.particles.positionY(marker))
					|| Float.floatToRawIntBits(currentMarkerGeometry[4 * marker + 2]) != Float.floatToRawIntBits(domain.particles.positionZ(marker))
					|| Float.floatToRawIntBits(currentMarkerGeometry[4 * marker + 3]) != Float.floatToRawIntBits(domain.particles.volume(marker))) return false;
		return true;
	}

	/** Compares all source markers and occupancy before committing a prepared phase or clear candidate. */
	boolean matchesSourceCarrierGeometry(FluidDomain domain, long[] occupiedSvq) {
		return matches(domain, occupiedSvq, sourceOccupiedSvq, sourceLiquidStock);
	}

	private boolean matches(FluidDomain domain, long[] occupiedSvq, long[] expectedOccupied, List<PhaseLiquidGeometryEdit> expectedStock) {
		if (!matchesGeometryInputs(domain, occupiedSvq, expectedOccupied)) return false;
		return expectedStock.equals(stock(domain, domain.geometry.cutCells, index(domain)));
	}
	private boolean matchesGeometryInputs(FluidDomain domain, long[] occupiedSvq, long[] expectedOccupied) {
		if (domain == null || geometry.sizeX() != domain.sizeX || geometry.sizeY() != domain.sizeY || geometry.sizeZ() != domain.sizeZ
				|| Float.floatToRawIntBits(cellSize) != Float.floatToRawIntBits(domain.config.cellSize())
				|| !Arrays.equals(expectedOccupied, occupiedSvq)) return false;
		for (int cell = 0; cell < domain.cellCount; cell++)
			if (geometry.collisionMask(cell) != domain.geometry.cutCells.mask(cell)
					|| domain.geometry.solid[cell] != (geometry.collisionMask(cell) == 255)) return false;
		return true;
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

	GasGeometryTransition transitionFrom(GasRuntimeGeometry source) {
		return phaseTransition != null && phaseTransition.startsAt(source) ? phaseTransition : new GasGeometryTransition(source, this);
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

	boolean fullDryFace(int cell, int axis, int coordinate) {
		return GasFaceIntersection.fullDryFace(phase, cell, axis, coordinate);
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
