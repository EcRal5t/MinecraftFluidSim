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
        ordinaryWallContactsSpanBothSides();
        sparseControllerSharesWallInventory();
        processSettingsSurviveRestartAndStop();
        driverStopUsesClickedBlock();
        touchingUnavailableCellKeepsBodyAvailable();
        hostEditsRespectCurrentActivity();
        fullyLoadedPlannerGroupsRemainIndependent();
    }

    private static void processSettingsSurviveRestartAndStop() throws Exception {
        var root=java.nio.file.Files.createTempDirectory("mmm-process-settings-");
        var region=UUID.randomUUID();var player=UUID.randomUUID();
        var first=new MinecraftProcessOperations(root,null,null,null);
        var drive=new MinecraftProcessOperations.Drive(20,220,1,2,3,.5,1,123L);
        first.setDrive(region,drive);
        var restored=new MinecraftProcessOperations(root,null,null,null);
        require(drive.equals(restored.drive(region)),"Drive settings did not survive restart");
        restored.clear(region);
        var stopped=new MinecraftProcessOperations(root,null,null,null);
        require(stopped.drive(region)==null,"Stopped process restarted after reload");
    }

    private static void driverStopUsesClickedBlock() throws Exception {
        var config=new FluidSolverConfig(.5f,9.81f,.05f,48,1e-5f,1e-4f);
        var first=region(new HostFluidState(2,2,2,config, mmm.fluid.TestFluidDefinitions.load()));
        var second=new FluidRegion(UUID.randomUUID(),first.dimension(),new BlockPos(10,0,0),new HostFluidState(2,2,2,config, mmm.fluid.TestFluidDefinitions.load()));
        var manager=new FluidRegionManager(new mmm.application.RegionCatalog.Limits(4,100,400));
        require(manager.add(first).accepted()&&manager.addAndSelect(second).accepted(),"Drive regions were not admitted");
        var root=java.nio.file.Files.createTempDirectory("mmm-driver-target-");
        var processes=new MinecraftProcessOperations(root,manager,null,null);
        var firstAnchor=new BlockPos(-1,0,0);var secondAnchor=new BlockPos(9,0,0);
        var firstDrive=new MinecraftProcessOperations.Drive(0,200,0,0,0,.5,0,firstAnchor.asLong());
        var secondDrive=new MinecraftProcessOperations.Drive(0,200,0,0,0,.5,0,secondAnchor.asLong());
        processes.setDrive(first.id(),firstDrive);processes.setDrive(second.id(),secondDrive);
		var driveId=UUID.nameUUIDFromBytes(("mmm:drive:"+first.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
		var bounds=new mmm.world.MovingBodyFluidCoupling.Bounds(.1,.1,.1,.2,.2,.2);
		var prior=List.of(new mmm.world.MovingBodyFluidCoupling.Request(driveId,bounds,1,0,0));
		require(processes.manualBodies(first.id(),prior).size()==1
				&& processes.manualBodies(first.id(),prior).getFirst().velocityXMetresPerSecond()==0,
				"Paused manual step lost the configured drive pose");
        var dimensions=net.minecraft.resources.ResourceKey.<Level>createRegistryKey(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("mmm","handoff_dimensions"));
        var otherDimension=net.minecraft.resources.ResourceKey.create(dimensions,
                net.minecraft.resources.Identifier.fromNamespaceAndPath("mmm","driver_other_dimension"));
        require(!processes.stopDriveAt(otherDimension,firstAnchor)
                &&!processes.stopDriveAt(first.dimension(),BlockPos.ZERO),"Foreign dimension or inactive block stopped a drive");
        require(processes.stopDriveAt(first.dimension(),firstAnchor)&&manager.selected()==second
                &&processes.drive(first.id())==null&&secondDrive.equals(processes.drive(second.id())),"Clicked drive stop changed the selected region or another process");
		require(processes.manualBodies(first.id(),prior).isEmpty(),"Stopped drive retained occupancy on manual step");
        var restored=new MinecraftProcessOperations(root,manager,null,null);
        require(restored.drive(first.id())==null&&secondDrive.equals(restored.drive(second.id())),"Clicked drive stop did not survive restart");
    }

    private static void touchingUnavailableCellKeepsBodyAvailable() {
        var host=new HostFluidState(6,2,2,new FluidSolverConfig(.5f,9.81f,.05f,48,1e-5f,1e-4f), mmm.fluid.TestFluidDefinitions.load());
        for(int z=0;z<2;z++)for(int y=0;y<2;y++)host.setSolid(2,y,z,true);
        var region=region(host);region.solverBackend(new HandoffBackend());region.ensureDeviceResident();
        var available=new boolean[24];java.util.Arrays.fill(available,true);
        available[2]=false;
        require(region.prepareGroupAvailability(available)&&region.groupAvailable(1)&&!region.groupAvailable(2),
                "Body contact fixture lost the active side");
        require(MinecraftGroupAvailability.boxAvailable(region,new net.minecraft.world.phys.AABB(.5,0,0,1,.5,.5)),
                "Face contact included an unoccupied unavailable cell");
        require(!MinecraftGroupAvailability.boxAvailable(region,new net.minecraft.world.phys.AABB(.5,0,0,1.01,.5,.5)),
                "Positive overlap with an unavailable cell was accepted");
    }

    private static void ordinaryWallContactsSpanBothSides() throws Exception {
        var registry=FullCapabilityTestFixture.load().thermal();
        var host=new HostFluidState(6,6,6,new FluidSolverConfig(.5f,9.81f,.05f,48,1e-5f,1e-4f), mmm.fluid.TestFluidDefinitions.load());
        for(int z=2;z<4;z++)for(int y=2;y<4;y++)for(int x=2;x<4;x++)host.setSolid(x,y,z,true);
        var dimensions=net.minecraft.resources.ResourceKey.<Level>createRegistryKey(net.minecraft.resources.Identifier.fromNamespaceAndPath("mmm","wall_dimensions"));
        var dimension=net.minecraft.resources.ResourceKey.create(dimensions,net.minecraft.resources.Identifier.fromNamespaceAndPath("mmm","wall_dimension"));
        var region=new FluidRegion(UUID.randomUUID(),dimension,BlockPos.ZERO,host);
        var pos=new BlockPos(1,1,1);var definition=registry.finiteDevice("mmm:ordinary_stone_wall");
        double energy=new ThermalStateFunction(registry).internalEnergyAtTemperature(registry.materialHandle(definition.thermalMaterialId()),
                definition.bodyMassKilograms(),java.util.Map.of(),definition.initialTemperatureK());
        var body=new FiniteThermalBodyState(0,registry.fingerprint(),definition.thermalMaterialId(),definition.bodyMassUnits(),energy);
        var wall=new FiniteThermalDeviceState(OrdinaryThermalWalls.PROVIDER,pos.asLong()+"@minecraft:stone",definition.id(),body);
        var ports=OrdinaryThermalWalls.ports(region,wall,registry);
        require(ports.size()==24,"Ordinary wall lost exposed contact cells");
        for(int face=0;face<6;face++){
            final int expected=face;
            require(ports.stream().filter(port->port.direction()==expected).count()==4,"Ordinary wall missing contact on face "+face);
        }
    }

    private static void sparseControllerSharesWallInventory() throws Exception {
        var interior = new java.util.HashSet<Long>();
        interior.add(new BlockPos(0,0,0).asLong());
        for (int x=2;x<=20;x++) interior.add(new BlockPos(x,0,0).asLong());
        for (int z=1;z<=20;z++) interior.add(new BlockPos(20,0,z).asLong());
        var wallPosition = new BlockPos(1,0,0);
        var footprint = new mmm.simulation.controller.FluidRegionFootprint(wallPosition,BlockPos.ZERO,new BlockPos(20,0,20),
                interior,java.util.Set.of(wallPosition.asLong()),java.util.Set.of(net.minecraft.world.level.ChunkPos.pack(0,0)));
        var grid = FluidRegionFactory.grid(footprint);
        require(grid.cellCount() < grid.sizeX()*grid.sizeY()*grid.sizeZ(),"Sparse footprint allocated its entire bounding box");
        require(grid.cell(0,0,0)>=0 && grid.cell(40,0,40)>=0 && grid.cell(20,0,20)<0,
                "Sparse footprint lost a real arm or retained an empty block");
        var definitions=mmm.fluid.TestFluidDefinitions.load();
        var host=new HostFluidState(grid,new FluidSolverConfig(.5f,9.81f,.05f,48,1e-5f,1e-4f),definitions);
        host.bindMaterialRegistry(FullCapabilityTestFixture.load().liquids());
        var key=net.minecraft.resources.ResourceKey.<Level>createRegistryKey(net.minecraft.resources.Identifier.fromNamespaceAndPath("mmm","sparse_dimensions"));
        var dimension=net.minecraft.resources.ResourceKey.create(key,net.minecraft.resources.Identifier.fromNamespaceAndPath("mmm","sparse_wall"));
        var region=new FluidRegion(UUID.randomUUID(),dimension,BlockPos.ZERO,host,footprint);
        host.bindGasRegistry(definitions.gas());
        host.bindThermal(definitions.thermal(),host.materialRegistry(),300);
        var thermal=definitions.thermal(); var type=thermal.finiteDevice("mmm:ordinary_stone_wall");
        double energy=new ThermalStateFunction(thermal).internalEnergyAtTemperature(thermal.materialHandle(type.thermalMaterialId()),
                type.bodyMassKilograms(),java.util.Map.of(),type.initialTemperatureK());
        var wall=new FiniteThermalDeviceState(OrdinaryThermalWalls.PROVIDER,wallPosition.asLong()+"@minecraft:stone",type.id(),
                new FiniteThermalBodyState(0,thermal.fingerprint(),type.thermalMaterialId(),type.bodyMassUnits(),energy));
        var ports=OrdinaryThermalWalls.ports(region,wall,thermal);
        require(ports.stream().anyMatch(port->port.cellX()==1) && ports.stream().anyMatch(port->port.cellX()==4),
                "The shared wall did not contact both chambers");
        var inventory=FiniteThermalDeviceInventory.empty(thermal.fingerprint()).insertExternal(wall);
        region.restoreFiniteThermalDevices(inventory,thermal,0);
        require(region.finiteThermalDevices(thermal.fingerprint()).devices().size()==1,
                "The shared wall did not keep one thermal inventory");
        require(region.reservedCellCapacity()==grid.cellCount(),"Sparse region budget differs from resident cells");
    }

    private static void finiteDeviceReloadUsesCheckpoint() throws Exception {
        var registries = FullCapabilityTestFixture.load();
        var host = new HostFluidState(2, 2, 2, new FluidSolverConfig(.5f,9.81f,.05f,48,1e-5f,1e-4f), mmm.fluid.TestFluidDefinitions.load());
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
        var host = new HostFluidState(6, 2, 2, new FluidSolverConfig(.5f,9.81f,.05f,48,1e-5f,1e-4f), mmm.fluid.TestFluidDefinitions.load());
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

    private static void fullyLoadedPlannerGroupsRemainIndependent() throws Exception {
        var host = new HostFluidState(6, 2, 2, new FluidSolverConfig(.5f,9.81f,.05f,48,1e-5f,1e-4f), mmm.fluid.TestFluidDefinitions.load());
        for (int z=0;z<2;z++) for (int y=0;y<2;y++) host.setSolid(2,y,z,true);
        host.setVolume(0,0,0,.5f);
        host.setVolume(4,0,0,.75f);
        host.restorePendingPhaseMotionState(new PendingPhaseMotionState(24,
                List.of(new PendingPhaseMotionState.Entry(4))));
        var registries = FullCapabilityTestFixture.load();
        host.sealDomainBoundary();
        host.bindMaterialRegistry(registries.liquids());
        host.bindGasRegistry(registries.gas());
        host.bindThermal(registries.thermal(), registries.liquids(), 300);
        // Direct host planning and a fresh all-loaded snapshot must both build physical groups.
        require(host.containerExtractableVolumeNear(0,0,0,0,-1)==.5,
                "Unprepared host groups blocked an unrelated edit");
        var restored = HostFluidState.restoreAuthoritativeState(host.captureAuthoritativeState());
        var available = new boolean[24]; java.util.Arrays.fill(available,true);
        restored.prepareGroupAvailability(available);
        require(restored.containerExtractableVolumeNear(0,0,0,0,-1)==.5
                && restored.containerExtractableVolumeNear(4,0,0,0,-1)==0,
                "Fully loaded snapshot failed to isolate pending phase work");
        require(restored.removeVolumeNear(0,0,0,.25f,0)==.25f
                && restored.exportPendingPhaseMotionState().entries().size()==1,
                "Independent edit changed pending work");
        // Removing the separating wall makes the pending task relevant to the same planner.
        for (int z=0;z<2;z++) for (int y=0;y<2;y++) restored.setSolid(2,y,z,false);
        require(restored.containerExtractableVolumeNear(0,0,0,0,-1)==0,
                "Planner reused stale groups after geometry changed");
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
