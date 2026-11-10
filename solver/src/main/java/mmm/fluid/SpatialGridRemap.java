package mmm.fluid;

import java.util.Arrays;
import mmm.spatial.SpatialGrid;

/** Maps an existing grid into a larger grid at an integer-cell offset. */
public final class SpatialGridRemap {
    private final SpatialGrid source;
    private final SpatialGrid target;
    private final int[] cells;
    private final int[] faces;
    private final int offsetX, offsetY, offsetZ;
    private final boolean[] retained;

    public SpatialGridRemap(SpatialGrid source, SpatialGrid target, int offsetX, int offsetY, int offsetZ) {
        this(source, target, offsetX, offsetY, offsetZ, null);
    }

    public SpatialGridRemap(SpatialGrid source, SpatialGrid target, int offsetX, int offsetY, int offsetZ,
            boolean[] retainedCells) {
        this.source = java.util.Objects.requireNonNull(source);
        this.target = java.util.Objects.requireNonNull(target);
        this.offsetX = offsetX; this.offsetY = offsetY; this.offsetZ = offsetZ;
        if (retainedCells != null && retainedCells.length != source.cellCount())
            throw new IllegalArgumentException("Retained cells differ from source grid");
        retained = retainedCells == null ? new boolean[source.cellCount()] : retainedCells.clone();
        if (retainedCells == null) Arrays.fill(retained, true);
        cells = new int[source.cellCount()];
        for (int cell = 0; cell < cells.length; cell++) {
            int mapped = target.cell(Math.addExact(source.x(cell), offsetX),
                    Math.addExact(source.y(cell), offsetY), Math.addExact(source.z(cell), offsetZ));
            if (mapped < 0) throw new IllegalArgumentException("Target grid omits an existing cell");
            cells[cell] = mapped;
        }
        faces = new int[source.faceCount()];
        for (int face = 0; face < faces.length; face++) {
            int mapped = target.faceAt(Math.addExact(source.faceCoordinate(face, 0), offsetX),
                    Math.addExact(source.faceCoordinate(face, 1), offsetY),
                    Math.addExact(source.faceCoordinate(face, 2), offsetZ), source.faceAxis(face));
            if (mapped < 0) throw new IllegalArgumentException("Target grid omits an existing face");
            faces[face] = mapped;
        }
    }

    public int cell(int sourceCell) { return cells[sourceCell]; }
    public int face(int sourceFace) { return faces[sourceFace]; }

    /** Combines conserved old gas with caller-provided inventory in newly added cells. */
    public mmm.fluid.air.SpatialGasState gas(mmm.fluid.air.SpatialGasState previous,
            java.util.List<mmm.fluid.air.GasInventory> newCells, double[] newEnergy) {
        if (!source.equals(previous.grid()) || newCells.size() != target.cellCount() || newEnergy.length != target.cellCount())
            throw new IllegalArgumentException("Gas grids differ");
        var inventories = new java.util.ArrayList<>(newCells);
        double[] energy = newEnergy.clone();
        var sourceLedger = previous.externalSource();
        boolean[] targetRetained = new boolean[target.cellCount()];
        for (int cell = 0; cell < cells.length; cell++) {
            int mapped = cells[cell];
            if (!retained[cell]) {
                if (!previous.cells().get(cell).isEmpty() || previous.energyAt(cell) != 0)
                    throw new IllegalArgumentException("Former padding contains gas inventory");
                continue;
            }
            if (!inventories.get(mapped).isEmpty() || energy[mapped] != 0)
                throw new IllegalArgumentException("New gas overlaps an existing cell");
            targetRetained[mapped] = true;
            inventories.set(mapped, previous.cells().get(cell));
            energy[mapped] = previous.energyAt(cell);
        }
        for (int cell = 0; cell < targetRetained.length; cell++) if (!targetRetained[cell])
            sourceLedger = sourceLedger.add(inventories.get(cell));
        return new mmm.fluid.air.SpatialGasState(target, previous.registryFingerprint(), previous.chemicalRegistryFingerprint(),
                inventories, energy, sourceLedger, previous.escapedSink(), previous.phaseSource(), previous.phaseSink());
    }

    public record GranularResult(mmm.granular.GranularStateSnapshot state, int[] depositSourceOrder) {
        public GranularResult { depositSourceOrder = depositSourceOrder.clone(); }
        @Override public int[] depositSourceOrder() { return depositSourceOrder.clone(); }
    }

    public GranularResult granular(mmm.granular.GranularStateSnapshot value, float cellSize) {
        if (value == null) return null;
        if (!source.equals(value.grid()) || !(Float.isFinite(cellSize) && cellSize > 0))
            throw new IllegalArgumentException("Granular grid differs");
        var active = value.activeParcels().stream().map(p -> new mmm.granular.GranularStateSnapshot.ActiveParcel(
                p.x() + offsetX * cellSize, p.y() + offsetY * cellSize, p.z() + offsetZ * cellSize,
                p.velocityX(), p.velocityY(), p.velocityZ(), p.solidVolumeSvq(), p.massUnits(),
                p.materialPaletteIndex(), p.solidCompositionIndex(), p.contactAge(), p.flags(), p.ceilMask())).toList();
        record IndexedDeposit(mmm.granular.GranularStateSnapshot.DepositGroup group, int index) { }
        var deposits = new java.util.ArrayList<IndexedDeposit>();
        for (int index = 0; index < value.depositGroups().size(); index++) {
            var p = value.depositGroups().get(index);
            deposits.add(new IndexedDeposit(new mmm.granular.GranularStateSnapshot.DepositGroup(cells[p.cell()],
                    p.porosityQ(), p.materialPaletteIndex(), p.solidCompositionIndex(), p.solidVolumeSvq(), p.massUnits()), index));
        }
        deposits.sort(java.util.Comparator.comparingInt((IndexedDeposit p) -> p.group().cell())
                .thenComparingInt(p -> p.group().materialPaletteIndex()).thenComparingInt(p -> p.group().solidCompositionIndex()));
        var state = new mmm.granular.GranularStateSnapshot(target, value.registryFingerprint(), value.chemicalRegistryFingerprint(),
                value.materialPalette(), value.solidCompositions(), value.solidSpeciesTotals(), active,
                deposits.stream().map(IndexedDeposit::group).toList(), value.totalSolidVolumeSvq(), value.totalMassUnits(),
                value.externalSource(), value.escapedSink());
        return new GranularResult(state, deposits.stream().mapToInt(IndexedDeposit::index).toArray());
    }

    public java.util.List<mmm.chemistry.RegionChemistry.MassTransferPending> chemicalPending(
            java.util.List<mmm.chemistry.RegionChemistry.MassTransferPending> pending) {
        if (pending == null) return null;
        return pending.stream().map(p -> new mmm.chemistry.RegionChemistry.MassTransferPending(cells[p.cell()],
                p.material(), p.supportMask(), p.definitionId(), p.pendingSeconds(), p.residualMoles())).sorted().toList();
    }

    public mmm.thermal.ThermalOwnerSnapshot thermal(mmm.thermal.ThermalOwnerSnapshot value,
            GranularResult granular, double[] gasEnergy, mmm.thermal.ThermalAuditLedger audit) {
        if (value == null) return null;
        double[] oldDeposits = value.copyDepositEnergy();
        int[] order = granular == null ? new int[0] : granular.depositSourceOrder();
        if (oldDeposits.length != order.length) throw new IllegalArgumentException("Thermal deposits differ");
        double[] deposits = new double[order.length];
        for (int index = 0; index < order.length; index++) deposits[index] = oldDeposits[order[index]];
        var coherent = value.coherentGroups().stream().map(p -> new mmm.thermal.CoherentPhaseStore.Group(
                cells[p.cell()], p.phaseId(), p.occupiedVolumeSvq(), p.massUnits(), p.composition(), p.internalEnergy()))
                .sorted(java.util.Comparator.comparingInt(mmm.thermal.CoherentPhaseStore.Group::cell)
                        .thenComparing(mmm.thermal.CoherentPhaseStore.Group::phaseId)).toList();
        return new mmm.thermal.ThermalOwnerSnapshot(value.registryFingerprint(), value.insertionTemperatureK(),
                value.copyActiveParcelEnergy(), deposits, gasEnergy, coherent, audit);
    }

    public mmm.granular.GranularDepositDynamics.State granularDynamics(mmm.granular.GranularDepositDynamics.State value) {
        if (value == null) return null;
        int[] old = value.stableAge();
        if (old.length != source.columnCount()) throw new IllegalArgumentException("Granular columns differ");
        int[] ages = new int[target.columnCount()], columns = new int[old.length];
        for (int column = 0; column < old.length; column++) {
            int mapped = target.column(source.columnX(column) + offsetX, source.columnZ(column) + offsetZ);
            if (mapped < 0) throw new IllegalArgumentException("Target grid omits a granular column");
            columns[column] = mapped;
            ages[mapped] = old[column];
        }
        int[] queued = value.queuedColumns();
        for (int index = 0; index < queued.length; index++) queued[index] = columns[queued[index]];
        return new mmm.granular.GranularDepositDynamics.State(value.logicalStep(), ages, queued);
    }

    public EntityBodyState bodies(EntityBodyState value, float cellSize) {
        double dx = offsetX * (double) cellSize, dy = offsetY * (double) cellSize, dz = offsetZ * (double) cellSize;
        var bodies = value.bodies().stream().map(body -> {
            var bounds = body.bounds();
            var moved = new mmm.world.MovingBodyFluidCoupling.Bounds(bounds.minimumX() + dx, bounds.minimumY() + dy,
                    bounds.minimumZ() + dz, bounds.maximumX() + dx, bounds.maximumY() + dy, bounds.maximumZ() + dz);
            return new mmm.world.MovingBodyFluidCoupling.Request(body.bodyId(), moved,
                    body.velocityXMetresPerSecond(), body.velocityYMetresPerSecond(), body.velocityZMetresPerSecond(),
                    body.centerXMetres() + dx, body.centerYMetres() + dy, body.centerZMetres() + dz,
                    body.angularXPerSecond(), body.angularYPerSecond(), body.angularZPerSecond(),
                    body.responseAxis(), body.inverseMassPerKilogram());
        }).toList();
        return new EntityBodyState(bodies);
    }

    public mmm.inventory.LiquidCellInventory.Snapshot liquid(mmm.inventory.LiquidCellInventory.Snapshot value) {
        if (value.cellCount() != source.cellCount()) throw new IllegalArgumentException("Liquid grid differs");
        var rows = new java.util.ArrayList<java.util.List<mmm.inventory.LiquidCellInventory.Stock>>(target.cellCount());
        for (int cell = 0; cell < target.cellCount(); cell++) rows.add(new java.util.ArrayList<>());
        for (int cell = 0; cell < cells.length; cell++) {
            if (!retained[cell]) {
                if (!value.rows().get(cell).isEmpty()) throw new IllegalArgumentException("Former padding contains liquid inventory");
                continue;
            }
            for (var stock : value.rows().get(cell)) {
                var key = stock.key();
                rows.get(cells[cell]).add(new mmm.inventory.LiquidCellInventory.Stock(
                        new mmm.inventory.LiquidCellInventory.Key(cells[cell], key.material(), key.supportMask()),
                        stock.volume(), stock.massUnits(), stock.mixture(), stock.internalEnergy()));
            }
        }
        return new mmm.inventory.LiquidCellInventory.Snapshot(rows);
    }

    public mmm.inventory.PhaseOccupancyState occupancy(mmm.inventory.PhaseOccupancyState value) {
        if (value.cellCount() != source.cellCount()) throw new IllegalArgumentException("Occupancy grid differs");
        return new mmm.inventory.PhaseOccupancyState(cells(value.copyLiquidMicroVolumes(), 8));
    }

    public PendingPhaseMotionState pending(PendingPhaseMotionState value) {
        if (value == null) return null;
        if (value.cellCount() != source.cellCount()) throw new IllegalArgumentException("Pending phase grid differs");
        var entries = value.entries().stream().map(entry -> new PendingPhaseMotionState.Entry(cells[entry.cell()]))
                .sorted(java.util.Comparator.comparingInt(PendingPhaseMotionState.Entry::cell)).toList();
        return new PendingPhaseMotionState(target.cellCount(), entries);
    }

    /** Keeps old collision masks and screen faces while accepting newly sampled cells. */
    public FixedGeometrySnapshot geometry(FixedGeometrySnapshot value, byte[] sampledMasks,
            java.util.List<ScreenFace> sampledScreens) {
        if (!source.equals(value.grid()) || sampledMasks.length != target.cellCount())
            throw new IllegalArgumentException("Collision grid differs");
        byte[] masks = sampledMasks.clone(), old = value.copyMasks();
        for (int cell = 0; cell < cells.length; cell++) if (retained[cell]) masks[cells[cell]] = old[cell];
        var screens = new java.util.TreeMap<Integer, ScreenFace>();
        for (var screen : sampledScreens) screens.put(screen.face(), screen);
        for (var screen : value.screens()) {
            int mapped = faces[screen.face()];
            screens.put(mapped, new ScreenFace(mapped, screen.poreDiameter(), screen.openFraction()));
        }
        return FixedGeometrySnapshot.fromMasks(target, masks, java.util.List.copyOf(screens.values()));
    }

    public CondensedPhasePressureField pressure(CondensedPhasePressureField value) {
        if (value == null) return null;
        if (value.cellCount() != source.cellCount()) throw new IllegalArgumentException("Pressure grid differs");
        float[] previous = value.copyRelativeGaugePressurePa(), next = new float[target.cellCount()];
        for (int cell = 0; cell < cells.length; cell++) next[cells[cell]] = previous[cell];
        return new CondensedPhasePressureField(value.pressureReferencePa(), next);
    }

    public double[] cells(double[] values, int components) {
        if (components <= 0 || values.length != source.cellCount() * components)
            throw new IllegalArgumentException("Cell array does not match source grid");
        double[] result = new double[Math.multiplyExact(target.cellCount(), components)];
        for (int cell = 0; cell < cells.length; cell++) if (retained[cell])
            System.arraycopy(values, cell * components, result, cells[cell] * components, components);
        return result;
    }

    public double[] cells(double[] values, double[] sampled, int components) {
        if (components <= 0 || values.length != source.cellCount() * components
                || sampled.length != target.cellCount() * components)
            throw new IllegalArgumentException("Cell arrays differ from grid");
        double[] result = sampled.clone();
        for (int cell = 0; cell < cells.length; cell++) if (retained[cell])
            System.arraycopy(values, cell * components, result, cells[cell] * components, components);
        return result;
    }

    public byte[] cells(byte[] values, byte newValue) {
        if (values.length != source.cellCount()) throw new IllegalArgumentException("Cell array does not match source grid");
        byte[] result = new byte[target.cellCount()];
        Arrays.fill(result, newValue);
        for (int cell = 0; cell < cells.length; cell++) result[cells[cell]] = values[cell];
        return result;
    }

    public MacMomentumState momentum(MacMomentumState value) {
        if (!source.equals(value.grid())) throw new IllegalArgumentException("Momentum does not match source grid");
        double[][] old = {value.copyX(), value.copyY(), value.copyZ()};
        double[][] next = {new double[target.faceCount(0)], new double[target.faceCount(1)], new double[target.faceCount(2)]};
        for (int axis = 0; axis < 3; axis++) {
            for (int i = 0; i < old[axis].length; i++) {
                int mapped = faces[source.faceOffset(axis) + i] - target.faceOffset(axis);
                next[axis][mapped] = old[axis][i];
            }
        }
        return new MacMomentumState(target, next[0], next[1], next[2]);
    }
}
