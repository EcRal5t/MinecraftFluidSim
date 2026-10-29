package mmm.gpu;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import mmm.fluid.*;
import mmm.fluid.FluidDomainContracts;
import mmm.thermal.*;
import mmm.units.SignedDyadic;
import mmm.world.MovingBodyFluidCoupling;

/** Owns native execution; committed generations remain retained by their region owners. */
public final class VulkanFluidSolverBackend implements FluidSolverBackend {
    public static final String ID = "mmm:vulkan-resident";
    public static final class Provider implements FluidSolverBackend.Provider {
        @Override
        public String id() {
            return "vulkan";
        }
        @Override
        public FluidSolverBackend create(int deviceIndex) {
            return new VulkanFluidSolverBackend(deviceIndex);
        }
    }
    private final VulkanDeviceContext context;
    private final VulkanLiquidGeometry geometry;
    private final VulkanLiquidProjection projection;
    private final VulkanPressureKernel pressure;
    private final VulkanPressureMultigrid multigrid;
    private final VulkanRegionObservation observation;
    private final VulkanLiquidDisplay liquidDisplay;
    private final VulkanGranularDisplay granularDisplay;
    private final VulkanRegionDisplay fieldsDisplay;
    private final VulkanBodyObservation bodyObservation;
    private final VulkanRegionInventorySummary inventorySummary;
    private final VulkanCellObservation cellObservation;
    private final VulkanWaterContacts waterContacts;
    private final Set<Region> regions = Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean closed;
    private String unavailable;
    private long reusedImports;
    synchronized long reusedImportCount() {
        return reusedImports;
    }
    synchronized long readbackBytes() {
        return regions.stream().mapToLong(region -> region.device.storage.metrics().readbackBytes()).sum();
    }
    @Override
    public synchronized String unavailableReason() {
        return unavailable;
    }
    private void observeFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof DeviceLostException) {
                unavailable = cause.getMessage() == null ? "Vulkan device lost" : cause.getMessage();
                return;
            }
    }
    private <T> T deviceCall(java.util.function.Supplier<T> operation) {
        try {
            return operation.get();
        } catch (RuntimeException | Error failure) {
            observeFailure(failure);
            throw failure;
        }
    }
    public VulkanFluidSolverBackend(int deviceIndex) {
        var allocated = new java.util.ArrayList<AutoCloseable>();
        try {
            context = owned(allocated, new VulkanDeviceContext(deviceIndex, true, true));
            geometry = owned(allocated, new VulkanLiquidGeometry(context));
            projection = owned(allocated, new VulkanLiquidProjection(context));
            pressure = owned(allocated, new VulkanPressureKernel(context, 4096));
            multigrid = owned(allocated, new VulkanPressureMultigrid(context));
            observation = owned(allocated, new VulkanRegionObservation(context));
            liquidDisplay = owned(allocated, new VulkanLiquidDisplay(context));
            granularDisplay = owned(allocated, new VulkanGranularDisplay(context));
            fieldsDisplay = owned(allocated, new VulkanRegionDisplay(context));
            bodyObservation = owned(allocated, new VulkanBodyObservation(context));
            inventorySummary = owned(allocated, new VulkanRegionInventorySummary(context));
            cellObservation = owned(allocated, new VulkanCellObservation(context));
            waterContacts = owned(allocated, new VulkanWaterContacts(context));
        } catch (RuntimeException | Error failure) {
            for (var resource : allocated.reversed())
                try {
                    resource.close();
                } catch (Exception | LinkageError closing) {
                    failure.addSuppressed(closing);
                }
            throw failure;
        }
    }
    private static <T extends AutoCloseable> T owned(java.util.List<AutoCloseable> resources, T resource) {
        resources.add(resource);
        return resource;
    }
    @Override
    public String id() {
        return ID;
    }
    @Override
    public String executionDescription() {
        return "Vulkan resident / " + context.deviceName();
    }
    @Override
    public synchronized void prepare(FluidDomainState state) {
        if (closed)
            throw new IllegalStateException("Vulkan backend is closed");
        if (unavailable != null)
            throw new DeviceLostException(unavailable);
        deviceCall(() -> {
            try (var region = new VulkanFluidStateCodec(state).importRegion(
                     context, geometry, projection, pressure, multigrid)) {
                return null;
            }
        });
    }
    @Override
    public synchronized PreparedFluidState prepareState(FluidStateAccess source, FluidDomainState successor,
        FiniteThermalDeviceInventory devices, List<FluidStateAccess.DevicePort> ports) {
        return deviceCall(() -> prepareStateInternal(source, successor, devices, ports));
    }
    @Override
    public synchronized PreparedFluidState prepareThermalBoundary(ResidentFluidState source, double[] temperatures) {
        return deviceCall(() -> {
            if (!(source instanceof State current) || current.owner() != this)
                throw new IllegalArgumentException("Thermal input requires this backend's resident state");
            current.ensure();
            var prepared = current.region.device.prepareThermalBoundary(current.capture, temperatures);
            try {
                return new Edited(prepared,
                    new State(current.region, current.codec.withBoundaryTemperatures(temperatures), current.ports,
                        prepared.captureState(), current.audit));
            } catch (RuntimeException | Error failure) {
                prepared.close();
                throw failure;
            }
        });
    }
    private PreparedFluidState prepareStateInternal(FluidStateAccess source, FluidDomainState successor,
        FiniteThermalDeviceInventory devices, List<FluidStateAccess.DevicePort> ports) {
        if (source instanceof State current && current.owner() == this && current.layout().equals(successor.layout())) {
            current.ensure();
            var encodedPorts =
                ports.stream()
                    .map(port -> {
                        var contact = port.contact();
                        int cell = contact.cellX()
                            + successor.sizeX() * (contact.cellY() + successor.sizeY() * contact.cellZ());
                        return new VulkanThermalDeviceLayout.Port(port.providerId(), port.objectId(), cell,
                            contact.direction(), contact.wallThicknessMetres(), contact.wallConductivityWPerMetreK(),
                            contact.filmThicknessMetres());
                    })
                    .toList();
            var codec = new VulkanFluidStateCodec(successor, devices, encodedPorts);
            var prepared = current.region.device.prepareImport(current.capture, codec.importData());
            if (prepared != null) {
                reusedImports++;
                try {
                    var audit = successor.thermalOwner() == null
                        ? null
                        : successor.thermalOwner().audit().withRoundoff(prepared.importRoundoff());
                    return new Edited(
                        prepared, new State(current.region, codec, ports, prepared.captureState(), audit));
                } catch (RuntimeException | Error failure) {
                    prepared.close();
                    throw failure;
                }
            }
        }
        var payload = new FluidStateAccess() {
            @Override
            public FluidDomainState.Layout layout() {
                return successor.layout();
            }
            @Override
            public FluidDomainState exportState() {
                return successor;
            }
            @Override
            public FiniteThermalDeviceInventory finiteThermalDevices() {
                return devices;
            }
            @Override
            public List<FluidStateAccess.DevicePort> finiteThermalPorts() {
                return ports;
            }
        };
        return new Imported(acquire(payload));
    }

    private final class Edited implements PreparedFluidState {
        private final VulkanLiquidRegion.Prepared prepared;
        private State state;
        Edited(VulkanLiquidRegion.Prepared prepared, State state) {
            this.prepared = prepared;
            this.state = state;
        }
        private State owned() {
            if (state == null)
                throw new IllegalStateException("Edit candidate was already handled");
            return state;
        }
        @Override
        public FluidDomainState.Layout layout() {
            return owned().layout();
        }
        @Override
        public FluidDomainState exportState() {
            return owned().exportState();
        }
        @Override
        public FluidStateAccess commit() {
            synchronized (VulkanFluidSolverBackend.this) {
                var result = owned();
                result.ensure();
                deviceCall(() -> {
                    prepared.commit();
                    return null;
                });
                state = null;
                prepared.close();
                return result;
            }
        }
        @Override
        public void close() {
            synchronized (VulkanFluidSolverBackend.this) {
                if (state != null) {
                    try {
                        prepared.close();
                    } finally {
                        state.close();
                        state = null;
                    }
                }
            }
        }
    }

    /** The imported storage stays private until the region's transaction adopts it. */
    private final class Imported implements PreparedFluidState {
        private State state;
        Imported(State state) {
            this.state = state;
        }
        private State owned() {
            if (state == null)
                throw new IllegalStateException("Import candidate was already handled");
            return state;
        }
        @Override
        public FluidDomainState.Layout layout() {
            return owned().layout();
        }
        @Override
        public FluidDomainState exportState() {
            return owned().exportState();
        }
        @Override
        public FluidStateAccess commit() {
            synchronized (VulkanFluidSolverBackend.this) {
                var result = owned();
                result.ensure();
                state = null;
                return result;
            }
        }
        @Override
        public void close() {
            synchronized (VulkanFluidSolverBackend.this) {
                if (state != null) {
                    state.close();
                    state = null;
                }
            }
        }
    }

    private State acquire(FluidStateAccess input) {
        if (closed)
            throw new IllegalStateException("Vulkan backend is closed");
        if (unavailable != null)
            throw new DeviceLostException(unavailable);
        if (input instanceof State state && state.owner() == this)
            return state.retain();
        try {
            var template = input.exportState();
            var ports = input.finiteThermalPorts();
            var encoded =
                ports.stream()
                    .map(port -> {
                        var c = port.contact();
                        return new VulkanThermalDeviceLayout.Port(port.providerId(), port.objectId(),
                            c.cellX() + template.sizeX() * (c.cellY() + template.sizeY() * c.cellZ()), c.direction(),
                            c.wallThicknessMetres(), c.wallConductivityWPerMetreK(), c.filmThicknessMetres());
                    })
                    .toList();
            var codec = new VulkanFluidStateCodec(template, input.finiteThermalDevices(), encoded);
            var region =
                new Region(codec, codec.importRegion(context, geometry, projection, pressure, multigrid), ports);
            regions.add(region);
            VulkanLiquidRegion.Capture capture = null;
            try {
                capture = region.device.captureState();
                var audit = template.thermalOwner() == null
                    ? null
                    : template.thermalOwner().audit().withRoundoff(region.device.importRoundoff());
                return new State(region, capture, audit);
            } catch (RuntimeException | Error failure) {
                if (capture != null)
                    capture.close();
                regions.remove(region);
                region.dispose();
                throw failure;
            }
        } catch (StateRestoreRejectedException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            observeFailure(failure);
            throw new StateRestoreRejectedException("Vulkan region import failed: " + failure.getMessage(), failure);
        }
    }
    @Override
    public synchronized FluidStepCandidate evaluate(FluidStepRequest request) {
        State source = acquire(request.stateAccess());
        VulkanLiquidRegion.Prepared prepared = null;
        try {
            var config = source.codec.template().config();
            prepared = source.region.device.prepareMechanical(source.capture,
                new VulkanLiquidProjection.Parameters(config.cellSize(), config.timeStep(), config.gravity(),
                    config.pressureTolerance(), config.maxPressureIterations()),
                512, new VulkanLiquidRegion.RequestInputs(request));
            var pending = new Pending(source, prepared);
            prepared = null;
            try {
                var before = source.totals();
                var after = pending.successor.totals();
                var diagnostic = pending.successor.capture.pressureDiagnostics();
                var chemical = pending.successor.capture.chemistryCounters();
                var granular = pending.successor.capture.granularCounters();
                var stats = new FluidDomainContracts.StepStats(pending.prepared.substeps(), (int) diagnostic[0],
                    (float) diagnostic[1], (float) before.volume(), (float) after.volume(), before.mass(), after.mass(),
                    chemical[3], chemical[4], chemical[5], condensedStats(pending.successor),
                    gasPhaseStats(pending.successor),
                    new FluidDomainContracts.MechanicalStepAudit(
                        false, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, SignedDyadic.ZERO, 0),
                    thermalStats(pending.successor),
                    new mmm.granular.GranularDepositDynamics.Stats(granular[3], granular[5], granular[4], 0, 0),
                    new FluidDomainContracts.CarrierViscosityStats(0, ""),
                    new FluidDomainContracts.ViscousStepStats(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                        after.viscousHeat(), Double.NaN, Double.NaN, Double.NaN),
                    new FluidDomainContracts.MechanicalCorrection(after.liquidForce(), after.granularForce(),
                        after.potential(), after.gasCounterWork(),
                        after.liquidForce() + after.granularForce() + after.potential() + after.gasCounterWork(),
                        after.absoluteCorrection(), false, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN));
                return FluidStepCandidate.preparedDevice(ID, request.baseGeneration(), pending, stats,
                    pending.prepared.bodyCoupling(),
                    new FluidStepCandidate.MechanicalResolution(0, pending.prepared.substeps(), ""));
            } catch (RuntimeException | Error failure) {
                pending.close();
                throw failure;
            }
        } catch (RuntimeException failure) {
            observeFailure(failure);
            if (prepared != null)
                prepared.close();
            source.close();
            if (failure instanceof CandidateEvaluationRejectedException rejected)
                throw rejected;
            throw new CandidateEvaluationRejectedException(
                "Vulkan private candidate failed: " + failure.getMessage(), failure);
        } catch (Error failure) {
            if (prepared != null)
                prepared.close();
            source.close();
            throw failure;
        }
    }
    @Override
    public synchronized FluidThermalStepCandidate evaluateThermal(FluidThermalStepRequest request) {
        State source = acquire(request.stateAccess());
        VulkanLiquidRegion.Prepared prepared = null;
        try {
            prepared = source.region.device.prepareThermal(source.capture, source.codec.template().config().timeStep(),
                new VulkanLiquidRegion.RequestInputs(source.entityBodies(), request.phaseChangeEnabled(),
                    request.maximumPhaseOperations(), request.activeCells()));
            var pending = new Pending(source, prepared);
            prepared = null;
            try {
                return FluidThermalStepCandidate.preparedDevice(
                    ID, request.baseGeneration(), pending, thermalStats(pending.successor));
            } catch (RuntimeException | Error failure) {
                pending.close();
                throw failure;
            }
        } catch (RuntimeException failure) {
            observeFailure(failure);
            if (prepared != null)
                prepared.close();
            source.close();
            if (failure instanceof CandidateEvaluationRejectedException rejected)
                throw rejected;
            throw new CandidateEvaluationRejectedException(
                "Vulkan private candidate failed: " + failure.getMessage(), failure);
        } catch (Error failure) {
            if (prepared != null)
                prepared.close();
            source.close();
            throw failure;
        }
    }
    private FluidDomainContracts.ThermalAxisStats thermalStats(State state) {
        var totals = state.totals();
        var counters = state.capture.chemistryCounters();
        var p = state.posting;
        return new FluidDomainContracts.ThermalAxisStats(
            new ThermalTransport.Stats(totals.thermalSubsteps(), totals.thermalContacts(), totals.temperatureChange(),
                totals.maximumHeat(), p == null ? SignedDyadic.ZERO : p.roundoff(),
                p == null ? SignedDyadic.ZERO : p.reservoir()),
            new mmm.chemistry.RegionChemistry.ReactionStats(counters[3], counters[4], counters[5]),
            new FluidDomainContracts.PhaseTransferStats(0, 0, 0, 0), condensedStats(state), gasPhaseStats(state),
            massTransferStats(state));
    }
    private FluidDomainContracts.GasLiquidMassTransferStats massTransferStats(State state) {
        var c = state.capture.massTransferCounters();
        return new FluidDomainContracts.GasLiquidMassTransferStats(c[0] != 0, c[1] != 0);
    }
    private FluidDomainContracts.ThermalPhaseStats condensedStats(State state) {
        var c = state.capture.thermalPhaseCounters();
        return new FluidDomainContracts.ThermalPhaseStats(c[8], c[6], c[7], c[9]);
    }
    private FluidDomainContracts.GasThermalPhaseStats gasPhaseStats(State state) {
        var c = state.capture.gasPhaseCounters();
        return new FluidDomainContracts.GasThermalPhaseStats(c[0], c[1], c[2]);
    }
    private final class Region {
        final VulkanFluidStateCodec codec;
        final VulkanLiquidRegion device;
        final List<FluidStateAccess.DevicePort> ports;
        int references;
        Region(VulkanFluidStateCodec codec, VulkanLiquidRegion device, List<FluidStateAccess.DevicePort> ports) {
            this.codec = codec;
            this.device = device;
            this.ports = List.copyOf(ports);
        }
        boolean disposed;
        void retain() {
            if (disposed)
                throw new IllegalStateException("Region storage closed");
            references++;
        }
        void dispose() {
            if (disposed)
                return;
            disposed = true;
            device.close();
        }
        void release() {
            if (--references == 0) {
                regions.remove(this);
                dispose();
            }
        }
    }
    private final class State implements ResidentFluidState {
        final Region region;
        final VulkanFluidStateCodec codec;
        final List<FluidStateAccess.DevicePort> ports;
        final VulkanLiquidRegion.Capture capture;
        final ThermalAuditLedger audit;
        VulkanLiquidRegion.ThermalPosting posting;
        VulkanRegionObservation.Totals totals;
        InventorySummary summary;
        Observation observed;
        Display display;
        FiniteThermalDeviceInventory devices;
        int observedCell = -1;
        VulkanCellObservation.Cell cell;
        boolean released;
        State(Region region, VulkanLiquidRegion.Capture capture, ThermalAuditLedger audit) {
            this(region, region.codec, region.ports, capture, audit);
        }
        State(Region region, VulkanFluidStateCodec codec, List<FluidStateAccess.DevicePort> ports,
            VulkanLiquidRegion.Capture capture, ThermalAuditLedger audit) {
            this.region = region;
            this.codec = codec;
            this.ports = List.copyOf(ports);
            this.capture = capture;
            this.audit = audit;
            region.retain();
        }
        VulkanFluidSolverBackend owner() {
            return VulkanFluidSolverBackend.this;
        }
        @Override
        public State retain() {
            synchronized (owner()) {
                ensure();
                var state = new State(region, codec, ports, capture.retain(), audit);
                state.totals = totals;
                state.summary = summary;
                state.observed = observed;
                state.display = display;
                state.devices = devices;
                state.posting = posting;
                return state;
            }
        }
        private void ensure() {
            if (unavailable != null)
                throw new DeviceLostException(unavailable);
            if (released || closed)
                throw new IllegalStateException("Resident generation is released");
        }
        VulkanRegionObservation.Totals totals() {
            synchronized (owner()) {
                return deviceCall(() -> {
                    ensure();
                    if (totals == null)
                        totals = observation.capture(capture);
                    return totals;
                });
            }
        }
        @Override
        public FluidDomainState.Layout layout() {
            return codec.layout();
        }
        @Override
        public FluidDomainState exportState() {
            synchronized (owner()) {
                return deviceCall(() -> {
                    ensure();
                    return codec.export(capture, audit);
                });
            }
        }
        @Override
        public FixedGeometrySnapshot fixedGeometry() {
            return codec.template().fixedGeometry();
        }
        @Override
        public PhysicalActivityGraph activityGraph() {
            synchronized (owner()) {
                return deviceCall(() -> {
                    ensure();
                    var state = codec.template();
                    return new PhysicalActivityGraph(state.sizeX(), state.sizeY(), state.sizeZ(), state.fixedGeometry(),
                        capture.activityGasNodes(), capture.activityParticleSupports(), ports, capture.entityBodies(),
                        state.config().cellSize());
                });
            }
        }
        @Override
        public boolean hasPendingPhaseMotion(boolean[] activeCells) {
            synchronized (owner()) {
                return deviceCall(() -> {
                    ensure();
                    return capture.hasPendingPhaseMotion(activeCells);
                });
            }
        }
        @Override
        public EntityBodyState entityBodies() {
            return capture.entityBodies();
        }
        @Override
        public ThermalAuditLedger thermalAudit() {
            return audit;
        }
        @Override
        public FiniteThermalDeviceInventory finiteThermalDevices() {
            synchronized (owner()) {
                return deviceCall(() -> {
                    ensure();
                    if (devices == null)
                        devices = codec.exportDevices(capture);
                    return devices;
                });
            }
        }
        @Override
        public List<FluidStateAccess.DevicePort> finiteThermalPorts() {
            return ports;
        }
        @Override
        public InventorySummary inventorySummary() {
            synchronized (owner()) {
                return deviceCall(() -> {
                    ensure();
                    if (summary == null)
                        summary =
                            inventorySummary.capture(capture, codec.template().config().cellSize(), audit != null);
                    return summary;
                });
            }
        }
        @Override
        public Observation observation() {
            synchronized (owner()) {
                return deviceCall(() -> {
                    ensure();
                    if (observed == null) {
                        var t = totals();
                        observed =
                            new Observation(t.volume(), t.stocks(), (float) t.maximumSpeed(), capture.gasFlowActive(),
                                t.particles() == 0 && t.dirtyGranularColumns() == 0
                                    && capture.chemistryCounters()[5] == 0 && capture.massTransferCounters()[0] == 0,
                                capture.hasPendingPhaseMotion(),
                                audit == null || audit.acceptsInventory(capture.exactThermalInventory()));
                    }
                    return observed;
                });
            }
        }
        @Override
        public Display display() {
            synchronized (owner()) {
                return deviceCall(() -> {
                    ensure();
                    if (display == null) {
                        var fields = fieldsDisplay.capture(capture, codec);
                        display = new Display(
                            liquidDisplay.capture(capture, codec.fluids(), codec.template().config().cellSize()),
                            fields.chemical(),
                            granularDisplay.capture(
                                capture, codec.template().granularRegistry(), codec.template().config().cellSize()),
                            fields.thermal());
                    }
                    return display;
                });
            }
        }
        @Override
        public FluidCellInventoryView observeCellInventory(int x, int y, int z) {
            synchronized (owner()) {
                return deviceCall(() -> {
                    ensure();
                    return cellAt(x, y, z).liquid();
                });
            }
        }
        @Override
        public CellCarrierInventoryView observeCellCarriers(int x, int y, int z) {
            synchronized (owner()) {
                return deviceCall(() -> {
                    ensure();
                    return cellAt(x, y, z).carriers();
                });
            }
        }
        @Override
        public boolean hasGranularOccupancy(int x, int y, int z) {
            synchronized (owner()) {
                return deviceCall(() -> {
                    ensure();
                    return cellAt(x, y, z).granularOccupied();
                });
            }
        }
        private VulkanCellObservation.Cell cellAt(int x, int y, int z) {
            int index = x + codec.layout().sizeX() * (y + codec.layout().sizeY() * z);
            if (cell == null || observedCell != index) {
                cell = cellObservation.capture(capture, codec, x, y, z);
                observedCell = index;
            }
            return cell;
        }
        @Override
        public List<FluidDomainContracts.WaterContact> waterContacts(
            List<FluidDomainContracts.WaterContactFace> faces) {
            synchronized (owner()) {
                return deviceCall(() -> {
                    ensure();
                    return waterContacts.capture(capture, codec, faces);
                });
            }
        }
        @Override
        public FluidDomainContracts.BodyFluidSample sampleBody(
            double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            synchronized (owner()) {
                return deviceCall(() -> {
                    ensure();
                    var fluids = codec.fluids();
                    int water = -1;
                    for (int m = 0; m < fluids.size(); m++)
                        if (fluids.material(m).id().equals("mmm:water"))
                            water = m;
                    return bodyObservation.capture(capture, minX, minY, minZ, maxX, maxY, maxZ, water,
                        codec.template().config().cellSize(), codec.template().config().gravity());
                });
            }
        }
        @Override
        public void close() {
            synchronized (owner()) {
                if (released)
                    return;
                released = true;
                capture.close();
                region.release();
            }
        }
    }
    private final class Pending implements PreparedFluidState {
        private final State source, successor;
        private final VulkanLiquidRegion.Prepared prepared;
        private boolean released, committed;
        Pending(State source, VulkanLiquidRegion.Prepared prepared) {
            this.source = source;
            this.prepared = prepared;
            var capture = prepared.captureState();
            try {
                var posting = source.audit == null ? null : capture.thermalPosting();
                successor = new State(source.region, source.codec, source.ports, capture,
                    posting == null ? null : posting.apply(source.audit));
                successor.posting = posting;
                if (posting != null && Boolean.getBoolean("mmm.gpu.handoffDiagnostics")) {
                    var actual = capture.exactThermalInventory();
                    var expected = successor.audit.expectedInventory();
                    if (!successor.audit.acceptsInventory(actual))
                        System.err.println("GPU thermal audit generation=" + capture.generation() + " substeps="
                            + prepared.substeps() + " sourceInventory=" + source.capture.exactThermalInventory()
                            + " sourceExpected=" + source.audit.expectedInventory() + " actual=" + actual
                            + " expected=" + expected + " residual=" + actual.subtract(expected)
                            + " residualJ=" + actual.subtract(expected).toDouble() + " posting=" + posting);
                }
            } catch (RuntimeException | Error failure) {
                capture.close();
                prepared.close();
                throw failure;
            }
        }
        @Override
        public FluidDomainState.Layout layout() {
            return successor.layout();
        }
        @Override
        public FluidDomainState exportState() {
            return successor.exportState();
        }
        @Override
        public FluidStateAccess commit() {
            synchronized (VulkanFluidSolverBackend.this) {
                if (released || committed)
                    throw new IllegalStateException("Resident candidate already handled");
                successor.ensure();
                deviceCall(() -> {
                    prepared.commit();
                    return null;
                });
                committed = true;
                State owned = successor.retain();
                close();
                return owned;
            }
        }
        @Override
        public void close() {
            synchronized (VulkanFluidSolverBackend.this) {
                if (released)
                    return;
                released = true;
                try {
                    prepared.close();
                } finally {
                    successor.close();
                    source.close();
                }
            }
        }
    }
    @Override
    public synchronized void close() {
        if (closed)
            return;
        closed = true;
        for (var r : List.copyOf(regions))
            r.dispose();
        regions.clear();
        waterContacts.close();
        cellObservation.close();
        inventorySummary.close();
        bodyObservation.close();
        fieldsDisplay.close();
        granularDisplay.close();
        liquidDisplay.close();
        observation.close();
        multigrid.close();
        pressure.close();
        projection.close();
        geometry.close();
        context.close();
    }
}
