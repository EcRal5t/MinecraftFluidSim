package mmm.simulation;

import java.util.List;
import java.util.UUID;
import mmm.fluid.*;
import mmm.thermal.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Device-to-host handoff keeps current availability and restores one checkpoint generation. */
final class RegionHostHandoffValidation {
    static void validate() throws Exception {
        finiteDeviceReloadUsesCheckpoint();
        hostEditsRespectCurrentActivity();
    }

    private static void finiteDeviceReloadUsesCheckpoint() throws Exception {
        var registries = FullCapabilityTestFixture.load();
        var host = new HostFluidState(2, 2, 2, new FluidSolverConfig(.5f,1000,9.81f,.05f,48,1e-5f,1e-4f));
        host.sealDomainBoundary();
        host.bindMaterialRegistry(registries.liquids());
        host.bindGasRegistry(registries.gas());
        host.bindThermal(registries.thermal(), registries.liquids(), 300);
        var region = region(host);
        var oldDevices = deviceInventory(registries.thermal(), 300, 0);
        var newDevices = deviceInventory(registries.thermal(), 310, 1);
        region.restoreFiniteThermalDevices(oldDevices, registries.thermal(), 0);
        var backend = new HandoffBackend();
        region.solverBackend(backend);
        region.ensureDeviceResident();
        // Model the device-owned inventory after an accepted heat exchange.
        backend.devices = newDevices;
        var checkpoint = region.hostSnapshot().captureAuthoritativeState();
        var checkpointDevices = region.finiteThermalDevices(registries.thermal().fingerprint());
        require(checkpointDevices.equals(newDevices), "Checkpoint read stale device energy");
        require(region.recordPublishedCheckpoint(1) && region.releaseSolverAfterCheckpoint(1), "Checkpoint unload failed");
        region.restoreAuthoritativeSolver(checkpoint, 1);
        region.restoreFiniteThermalDevices(checkpointDevices, registries.thermal(), 1);
        require(region.finiteThermalDevices(registries.thermal().fingerprint()).equals(newDevices),
                "Reload did not restore device inventory from the published generation");
        require(region.devicePortsDirty(), "Reload retained obsolete device ports");
    }

    private static FiniteThermalDeviceInventory deviceInventory(ThermalRegistry registry, double temperature, long generation) {
        String material = "mmm:copper_heat_exchanger";
        double energy = new ThermalStateFunction(registry).internalEnergyAtTemperature(
                registry.materialHandle(material), 16, java.util.Map.of(), temperature);
        var body = new FiniteThermalBodyState(generation, registry.fingerprint(), material,
                mmm.units.Mass.fromKilograms(16), energy);
        return new FiniteThermalDeviceInventory(registry.fingerprint(), List.of(new FiniteThermalDeviceState(
                "mmm:finite_heat_exchanger", "test:heater", "mmm:finite_heat_exchanger", body)));
    }

    private static void hostEditsRespectCurrentActivity() throws Exception {
        var host = new HostFluidState(6, 2, 2, new FluidSolverConfig(.5f,1000,9.81f,.05f,48,1e-5f,1e-4f));
        for (int z=0;z<2;z++) for (int y=0;y<2;y++) host.setSolid(2,y,z,true);
        host.setVolume(0,0,0,.5f);
        host.setVolume(4,0,0,.75f);
        var registries = FullCapabilityTestFixture.load();
        host.sealDomainBoundary();
        host.bindMaterialRegistry(registries.liquids());
        host.bindGasRegistry(registries.gas());
        host.bindThermal(registries.thermal(), registries.liquids(), 300);
        host.restorePendingPhaseMotionState(new PendingPhaseMotionState(24,
                List.of(new PendingPhaseMotionState.Entry(4))));
        var region = region(host);
        var backend = new HandoffBackend();
        region.solverBackend(backend);
        region.ensureDeviceResident();
        var available = new boolean[24];
        java.util.Arrays.fill(available,true);
        available[5] = false;
        require(region.prepareGroupAvailability(available), "Available group was lost");
        require(region.groupAvailable(0) && !region.groupAvailable(4), "Fixture groups were not isolated");
        var preview = region.hostSnapshot();
        var edit = region.beginHostEdit();
        require(preview.containerExtractableVolumeNear(0,0,0,6,-1)==.5
                && edit.containerExtractableVolumeNear(0,0,0,6,-1)==.5,
                "Preview or edit included inventory from a paused group");
        require(edit.addVolumeNear(0,0,0,.125f,6)==.125f,
                "Pending work in a paused group blocked liquid insertion");
        require(edit.removeVolumeNear(0,0,0,2,6)==.625f && edit.volume(4,0,0)==.75f,
                "Container extraction changed the paused group");
        region.commitHostEdit(region.solverGeneration(),edit);
        require(region.hostSnapshot().volume(4,0,0)==.75f, "Committed edit changed paused inventory");
        require(region.hostSnapshot().exportPendingPhaseMotionState().entries().equals(
                List.of(new PendingPhaseMotionState.Entry(4))), "Committed edit changed paused pending work");
        java.util.Arrays.fill(available,true);
        region.prepareGroupAvailability(available);
        require(region.hostSnapshot().containerExtractableVolumeNear(0,0,0,6,-1)==0,
                "Recovered pending work did not block extraction before projection");
    }

    private static FluidRegion region(HostFluidState host) {
        var controller = new BlockPos(-1,0,0);
        var footprint = new mmm.simulation.controller.FluidRegionFootprint(controller, BlockPos.ZERO, BlockPos.ZERO,
                java.util.Set.of(BlockPos.ZERO.asLong()),java.util.Set.of(controller.asLong()),
                java.util.Set.of(net.minecraft.world.level.ChunkPos.pack(0,0)));
        var dimensions = net.minecraft.resources.ResourceKey.<Level>createRegistryKey(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("mmm", "handoff_dimensions"));
        var dimension = net.minecraft.resources.ResourceKey.create(dimensions,
                net.minecraft.resources.Identifier.fromNamespaceAndPath("mmm", "handoff_dimension"));
        return new FluidRegion(UUID.randomUUID(),dimension,BlockPos.ZERO,host,footprint);
    }

    /** Read-only committed device observations; no alternate simulation implementation. */
    private static final class HandoffBackend implements FluidSolverBackend {
        FiniteThermalDeviceInventory devices;
        public String id() { return "test:handoff"; }
        public PreparedFluidState prepareState(FluidStateAccess source, FluidDomainState state,
                FiniteThermalDeviceInventory inventory, List<FluidStateAccess.DevicePort> ports) {
            devices=inventory;
            var host=HostFluidState.restoreAuthoritativeState(state);
            var geometry=FixedGeometrySnapshot.fromMasks(host.sizeX(),host.sizeY(),host.sizeZ(),host.copyCollisionMicroMasks());
            var resident=(ResidentFluidState)java.lang.reflect.Proxy.newProxyInstance(ResidentFluidState.class.getClassLoader(),
                    new Class<?>[]{ResidentFluidState.class},(proxy,method,args)->switch(method.getName()) {
                        case "layout" -> state.layout();
                        case "exportState" -> state;
                        case "retain" -> proxy;
                        case "close" -> null;
                        case "finiteThermalDevices" -> devices;
                        case "finiteThermalPorts" -> ports;
                        case "fixedGeometry" -> geometry;
                        case "entityBodies" -> EntityBodyState.EMPTY;
                        case "activityGraph" -> new PhysicalActivityGraph(host.sizeX(),host.sizeY(),host.sizeZ(),geometry,
                                null,new int[0],ports,EntityBodyState.EMPTY,host.config().cellSize());
                        case "observation" -> new ResidentFluidState.Observation(host.totalVolumeExact(),host.particleCount(),0,false,true,false,true);
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            return new PreparedFluidState() {
                public FluidDomainState.Layout layout() { return state.layout(); }
                public FluidDomainState exportState() { return state; }
                public FluidStateAccess commit() { return resident; }
                public void close() { }
            };
        }
        public FluidStepCandidate evaluate(FluidStepRequest request) { throw new UnsupportedOperationException(); }
        public FluidThermalStepCandidate evaluateThermal(FluidThermalStepRequest request) { throw new UnsupportedOperationException(); }
    }

    private static void require(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
}
