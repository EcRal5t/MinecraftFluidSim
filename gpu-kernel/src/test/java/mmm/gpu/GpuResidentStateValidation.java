package mmm.gpu;

import mmm.fluid.FluidDomainContracts;

import java.util.ArrayList;
import java.util.List;
import mmm.fluid.*;
import mmm.thermal.*;

/** Production-format cold import, deferred adoption, constant observations and exact checkpoint. */
public final class GpuResidentStateValidation {
    public static void main(String[] args) {
        int device=args.length>0?Integer.parseInt(args[0]):0;
        var domain=new HostFluidState(8,8,8,new FluidSolverConfig(.5f,9.81f,.05f,200,1e-5f,.002f), mmm.fluid.TestFluidDefinitions.load());
        var fluids=new FluidMaterialRegistry(List.of(mmm.fluid.TestFluidDefinitions.load().material().entry("mmm:water").material()));
        domain.bindMaterialRegistry(fluids);domain.bindMaterialDefinitions(mmm.fluid.TestFluidDefinitions.load().material());domain.sealDomainBoundary();
        float[] volumes=new float[512];for(int c=0;c<512;c++)volumes[c]=(c/8)%8<3?.7f:0;
        domain.setVolumes(volumes);
        var thermal=mmm.fluid.TestFluidDefinitions.load().thermal();
        domain.bindGasRegistry(mmm.fluid.TestFluidDefinitions.load().gas());domain.bindThermal(thermal,fluids,300);
        var initial=domain.captureAuthoritativeState();var codec=new VulkanFluidStateCodec(initial);var audit=domain.thermalAudit();long expectedMass=domain.totalMassUnits();
        validateCapabilityContracts(initial);
        if(initial.spatialGas()==null||initial.spatialGas().cells().stream().allMatch(mmm.fluid.air.GasInventory::isEmpty))throw new AssertionError("Resident fixture did not initialize local gas");
        try(var context=new VulkanDeviceContext(device,true,true);var geometry=new VulkanLiquidGeometry(context);
            var projection=new VulkanLiquidProjection(context);var pressure=new VulkanPressureKernel(context,4096);
            var mg=new VulkanPressureMultigrid(context);var region=codec.importRegion(context,geometry,projection,pressure,mg);
            var observation=new VulkanRegionObservation(context);var display=new VulkanLiquidDisplay(context);var fields=new VulkanRegionDisplay(context);
            var bodyObservation=new VulkanBodyObservation(context);var inventorySummary=new VulkanRegionInventorySummary(context);
            var cellObservation=new VulkanCellObservation(context);var waterContacts=new VulkanWaterContacts(context)) {
            var p=new VulkanLiquidProjection.Parameters(.5f,.05f,9.81f,1e-5f,200);
            try (var cold = region.captureState()) {
                requireGasCell(cellObservation.capture(cold, codec, 1, 6, 1).carriers(),
                        domain.observeCellCarriers(1, 6, 1));
                requireDisplayTemperature(fields.capture(cold, codec).thermal(), 65, 300);
            }
            try(var before=region.captureState();var abandoned=region.prepareMechanical(before,p)) {
                try(var current=region.captureState()){if(current.generation()!=0)throw new AssertionError("Unadopted candidate changed committed generation");}
            }
            for(int i=0;i<3;i++) {
                try(var before=region.captureState();var prepared=i==1?region.prepareThermal(before,.05):region.prepareMechanical(before,p);
                    var successor=prepared.captureState()) {
                    var posting=successor.thermalPosting();audit=posting.apply(audit);
                    var exported=codec.export(successor,audit);
                    requireGasCell(cellObservation.capture(successor, codec, 1, 6, 1).carriers(),
                            HostFluidState.restoreAuthoritativeState(exported).observeCellCarriers(1, 6, 1));
                    if(!exported.layout().equals(initial.layout()))throw new AssertionError("Device export changed layout");
                    if(!audit.acceptsInventory(successor.exactThermalInventory()))throw new AssertionError("Device thermal inventory reduction differs from audit");
                    var derived=fields.capture(successor,codec);if(derived.thermal()==null)throw new AssertionError("Thermal display missing");
                    var totals=observation.capture(successor);
                    if(!(totals.volume()>0&&totals.mass()>0))throw new AssertionError("Device observations lost inventory");
                    var summary=inventorySummary.capture(successor,.5,true);
                    if(summary.liquidMassUnits()!=expectedMass||summary.chemicalTotalQuanta()!=0||!(summary.minimumTemperatureK()>0&&summary.maximumTemperatureK()>=summary.minimumTemperatureK())
                            ||!audit.acceptsInventory(mmm.units.SignedDyadic.of(summary.totalInternalEnergyJoules())))throw new AssertionError("Constant-size inventory/temperature summary differs from committed inventory");
                    var bodySample=bodyObservation.capture(successor,.5,.25,.5,1,.75,1,0,.5,9.81);
                    requireWetSample(bodySample);
                    var cell=cellObservation.capture(successor,codec,1,0,1).liquid();requireCellAndWater(cell,waterContacts.capture(successor,codec,contactFaces()));
                    var rendered=display.capture(successor,codec.fluids(),.5f);
                    if(rendered.geometry().pieces().isEmpty())throw new AssertionError("Committed display lost liquid surface");
                    prepared.commit();
                    System.out.println("committed="+(i+1)+" liquid_volume="+totals.volume()+" mass="+totals.mass()+" audit_closed=true");
                }
            }
            System.out.println("Production state import, rejected private candidate, mechanical/thermal adoption, device readout and exact checkpoint passed");
        }
        validateFiniteBodyResponse(device,initial);
        validateAmbientBodyClosure(device,thermal,false);
        validateAmbientBodyClosure(device,thermal,true);
        validateSmallGeometry(device,thermal);
        validateGranularColdImport(device);
        long expectedGasMass=0;for(var cell:initial.spatialGas().cells())expectedGasMass=Math.addExact(expectedGasMass,cell.totalMassUnits());
        for (int attempt = 0; attempt < Integer.getInteger("mmm.residentAttempts", 1); attempt++) {
            System.out.println("resident_attempt=" + attempt);
            validateBackend(device,initial,expectedMass,expectedGasMass);
        }
    }
    private static void validateFiniteBodyResponse(int device, FluidDomainState initial) {
        var id=java.util.UUID.fromString("a7184c41-7a9c-4c56-8b2a-e7d7b474ca24");
        double[] inverse={1.0/20.0,1.0/2000.0}, response=new double[2];
        for(int i=0;i<inverse.length;i++){
            var domain=HostFluidState.restoreAuthoritativeState(initial);
            var before=new mmm.world.MovingBodyFluidCoupling.Bounds(1,.6,1,1.25,.85,1.25);
            var after=new mmm.world.MovingBodyFluidCoupling.Bounds(1.025,.6,1,1.275,.85,1.25);
            var source=new mmm.world.MovingBodyFluidCoupling.Request(id,before,.5,0,0,1.125,.725,1.125,0,0,0,0,inverse[i]);
            var target=new mmm.world.MovingBodyFluidCoupling.Request(id,after,.5,0,0,1.15,.725,1.125,0,0,0,0,inverse[i]);
            domain.restoreEntityBodyState(new EntityBodyState(List.of(source)));
            FluidStateAccess current=domain.captureAuthoritativeState();
            try(var backend=new VulkanFluidSolverBackend(device);
                var candidate=backend.evaluate(new FluidStepRequest(0,current,List.of(target),false,false,false));
                var prepared=candidate.claimPreparedDeviceState()) {
                var impulse=candidate.movingBodyCoupling().bodyImpulses().stream().filter(value->value.bodyId().equals(id)).findFirst().orElseThrow();
                response[i]=inverse[i]*impulse.impulseXKilogramMetresPerSecond();
                var next=prepared.commit();
                try {
                    if(!((ResidentFluidState)next).observation().thermalAuditClosed())
                        throw new AssertionError("Finite body pressure/kinetic energy ledger is not closed");
                } finally {next.close();}
            } finally {current.close();}
        }
        if(!(response[0]<0 && response[1]<0 && Math.abs(response[0])>Math.abs(response[1])))
            throw new AssertionError("Finite body did not respond to fluid resistance: "+java.util.Arrays.toString(response));
        System.out.println("finite_body_velocity_change="+java.util.Arrays.toString(response));
    }

    private static void validateAmbientBodyClosure(int device,ThermalRegistry thermal,boolean high){
        var domain=new HostFluidState(3,3,3,new FluidSolverConfig(.5f,0,.05f,200,1e-5f,.002f), mmm.fluid.TestFluidDefinitions.load());
        var fluids=new FluidMaterialRegistry(List.of(mmm.fluid.TestFluidDefinitions.load().material().entry("mmm:water").material()));
        domain.bindMaterialRegistry(fluids);domain.bindMaterialDefinitions(mmm.fluid.TestFluidDefinitions.load().material());
        int edge=high?2:0,cell=12+edge;
        for(int z=0;z<3;z++)for(int y=0;y<3;y++)for(int x=0;x<3;x++)
            if(x!=edge||y!=1||z!=1)domain.setSolid(x,y,z,true);
        domain.bindGasRegistry(mmm.fluid.TestFluidDefinitions.load().gas());
        domain.bindThermal(thermal,fluids,300);
        FluidStateAccess current=domain.captureAuthoritativeState();
        var initial=current.exportState();var initialGas=initial.spatialGas();
        var expected=gasAccount(initialGas);long initialMass=gasAccountMass(initialGas);
        if(initialGas.cells().get(cell).isEmpty())throw new AssertionError("Ambient closure fixture has no gas");
        var body=new mmm.world.MovingBodyFluidCoupling.Request(java.util.UUID.fromString("a7184c41-7a9c-4c56-8b2a-e7d7b474ca23"),
                new mmm.world.MovingBodyFluidCoupling.Bounds(edge*.5,.5,.5,(edge+1)*.5,1,1),0,0,0);
        try(var backend=new VulkanFluidSolverBackend(device)){
            try(var candidate=backend.evaluate(new FluidStepRequest(0,current,List.of(body),false,false,false));
                var prepared=candidate.claimPreparedDeviceState()){
                var next=prepared.commit();current.close();current=next;
            }
            var checkpoint=current.exportState();var gas=checkpoint.spatialGas();
            if(!gas.cells().get(cell).isEmpty()||gas.escapedSink().entries().isEmpty()
                    ||gasAccountMass(gas)!=initialMass||!gasAccount(gas).equals(expected)
                    ||Math.abs(checkpoint.thermalOwner().audit().roundoffResidual().subtract(initial.thermalOwner().audit().roundoffResidual()).toDouble())>1e-6
                    ||!((ResidentFluidState)current).observation().thermalAuditClosed())
                throw new AssertionError("Ambient closure lost gas or energy accounting");
            var restored=HostFluidState.restoreAuthoritativeState(checkpoint).captureAuthoritativeState();
            current.close();current=restored;
            try(var candidate=backend.evaluate(new FluidStepRequest(1,current,List.of(body),false,false,false));
                var prepared=candidate.claimPreparedDeviceState()){
                var next=prepared.commit();current.close();current=next;
            }
            var continued=current.exportState().spatialGas();
            if(gasAccountMass(continued)!=initialMass||!gasAccount(continued).equals(expected)
                    ||!((ResidentFluidState)current).observation().thermalAuditClosed())
                throw new AssertionError("Restored ambient closure changed gas or energy accounting");
        } finally { current.close(); }
    }
    private static long gasAccountMass(mmm.fluid.air.SpatialGasState gas){
        long total=0;for(var cell:gas.cells())total=Math.addExact(total,cell.totalMassUnits());
        for(var entry:gas.escapedSink().entries())total=Math.addExact(total,entry.massUnits());
        for(var entry:gas.externalSource().entries())total=Math.subtractExact(total,entry.massUnits());
        return total;
    }
    private static java.util.Map<String,Long> gasAccount(mmm.fluid.air.SpatialGasState gas){
        var totals=new java.util.TreeMap<String,Long>();
        for(var cell:gas.cells())for(var component:cell.components())for(var species:component.species())
            totals.merge(species.speciesId(),species.amountQuanta(),Math::addExact);
        for(var entry:gas.escapedSink().entries())for(var species:entry.species())totals.merge(species.speciesId(),species.amountQuanta(),Math::addExact);
        for(var entry:gas.externalSource().entries())for(var species:entry.species())totals.merge(species.speciesId(),-species.amountQuanta(),Math::addExact);
        totals.values().removeIf(value->value==0);return totals;
    }
    private static void validateCapabilityContracts(FluidDomainState thermalOnly) {
        expectIllegal(() -> new FluidStepRequest(0, thermalOnly, List.of(), true));
        expectIllegal(() -> new FluidThermalStepRequest(0, thermalOnly, true));
        var domain = new HostFluidState(2, 2, 2, thermalOnly.config(), mmm.fluid.TestFluidDefinitions.load());
        domain.bindMaterialRegistry(new FluidMaterialRegistry(List.of(mmm.fluid.TestFluidDefinitions.load().material().entry("mmm:water").material())));
        domain.bindChemistry(mmm.fluid.TestFluidDefinitions.load().chemistry());
        expectIllegal(() -> new VulkanFluidStateCodec(domain.captureAuthoritativeState()));
        var simple = new HostFluidState(2, 2, 2, thermalOnly.config(), mmm.fluid.TestFluidDefinitions.load()).captureAuthoritativeState();
        if (simple.spatialGas()==null || simple.spatialGas().cells().stream().allMatch(mmm.fluid.air.GasInventory::isEmpty)
                || !new VulkanFluidStateCodec(simple).template().spatialGas().cells().equals(simple.spatialGas().cells()))
            throw new AssertionError("Cold import lost its local gas inventory");
    }
    private static void expectIllegal(Runnable action) {
        try { action.run(); } catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Unsupported capability combination was accepted");
    }
    private static void validateGranularColdImport(int device) {
        var domain = new HostFluidState(2, 2, 2, new FluidSolverConfig(.5f,9.81f,.05f,200,1e-5f,.002f), mmm.fluid.TestFluidDefinitions.load());
        var fluids = new FluidMaterialRegistry(List.of(mmm.fluid.TestFluidDefinitions.load().material().entry("mmm:water").material()));
        domain.bindMaterialRegistry(fluids);
        domain.bindMaterialDefinitions(mmm.fluid.TestFluidDefinitions.load().material());
        domain.bindGranular(mmm.fluid.TestFluidDefinitions.load().granular(), fluids, mmm.fluid.TestFluidDefinitions.load().material());
        var initial = domain.captureAuthoritativeState();
        if (initial.spatialGas()==null || initial.spatialGas().cells().stream().allMatch(mmm.fluid.air.GasInventory::isEmpty))
            throw new AssertionError("Granular fixture lost its local gas inventory");
        try (var backend = new VulkanFluidSolverBackend(device);
             var candidate = backend.evaluate(new FluidStepRequest(0, initial, List.of(), false));
             var prepared = candidate.claimPreparedDeviceState()) {
            var gas=prepared.exportState().spatialGas();
            if (gas==null || gasAccountMass(gas)!=gasAccountMass(initial.spatialGas())
                    || !gasAccount(gas).equals(gasAccount(initial.spatialGas())))
                throw new AssertionError("Granular import lost its local gas accounting");
        }
    }
    private static void validateSmallGeometry(int device,ThermalRegistry thermal){
        var fluids=new FluidMaterialRegistry(List.of(mmm.fluid.TestFluidDefinitions.load().material().entry("mmm:water").material()));
        var definitions=VulkanLiquidRegion.MechanicalDefinitions.fromRegistries(fluids,mmm.fluid.TestFluidDefinitions.load().material(),
                mmm.fluid.TestFluidDefinitions.load().chemistry(),thermal,8,0,64,4,-40,true);
        double small=Math.scalb(1.0,-40);
        try(var context=new VulkanDeviceContext(device,true,true);var geometry=new VulkanLiquidGeometry(context);
            var projection=new VulkanLiquidProjection(context);var pressure=new VulkanPressureKernel(context,4096);
            var mg=new VulkanPressureMultigrid(context);var display=new VulkanLiquidDisplay(context)){
            for(int mask:new int[]{0,0xaa,1})for(boolean full:new boolean[]{false,true}){
                long solid=mask!=0?1L<<21:0;
                double geometricCapacity=Integer.bitCount(~mask&255)*.125;
                double capacity=geometricCapacity-solid/16777216.0,porosity=capacity/geometricCapacity;
                double volume=full?capacity-small:small;
                var stock=new mmm.inventory.DeviceLiquidInventory(new int[]{0,1,1,1,1,1,1,1,1},new int[]{0},new int[]{~mask&255},
                    mmm.units.DeviceDyadicArray.encode(List.of(mmm.units.SignedDyadic.of(volume))),new long[]{1},new long[]{0},
                    new int[]{0},new int[]{0,0},new int[0],new long[0],new long[]{0});
                var momentum=new MacMomentumState(2,2,2,new double[12],new double[12],new double[12]);
                var masks=new byte[8];masks[0]=(byte)mask;
                var solids=new long[8];solids[0]=solid;
                try(var region=new VulkanLiquidRegion(context,geometry,projection,pressure,mg,stock,momentum,
                        FixedGeometrySnapshot.fromMasks(2,2,2,masks),solids,definitions)){
                    region.rebuildGeometry();
                    try(var capture=region.captureState()){
                        double occupied=java.util.Arrays.stream(capture.occupancy().copyLiquidMicroVolumes()).sum();
                        double expectedOccupied=Math.min(capacity,volume*(1.0+MixtureVolumeModel.REFERENCE_PRESSURE_PA/MixtureVolumeModel.LIQUID_BULK_PA));
                        if(Math.abs(occupied-expectedOccupied)>Math.max(1e-18,expectedOccupied*1e-11))
                            throw new AssertionError("Small-phase occupied volume differs: "+occupied+" vs "+expectedOccupied);
                        var rendered=display.capture(capture,fluids,1);
                        var mesh=FluidMaterialSurfaceMesher.build(rendered);
                        double signedVolume=0,area=0;
                        for(var face:mesh.outerTriangles()){
                            signedVolume+=face.a().dot(face.b().cross(face.c()))/6;
                            area+=Math.sqrt(face.areaSquared());
                        }
                        double geometricVolume=expectedOccupied/porosity;
                        if(Math.abs(signedVolume-geometricVolume)>Math.max(3e-14,geometricVolume*1e-5))
                            throw new AssertionError("Small-phase display volume differs: "+signedVolume+" vs "+geometricVolume);
                        if(!full&&!(Double.isFinite(area)&&area>0))
                            throw new AssertionError("Small liquid lost its surface: area="+area);
                    }
                }
            }
        }
    }
    private static void validateBackend(int device,FluidDomainState initial,long expectedMass,long expectedGasMass){
        validateThermalBoundaryCandidate(device, initial);
        FluidStateAccess current=initial;
        var providers=java.util.ServiceLoader.load(FluidSolverBackend.Provider.class).stream()
                .map(java.util.ServiceLoader.Provider::get).filter(provider->provider.id().equals("vulkan")).toList();
        if(providers.size()!=1)throw new AssertionError("Expected one discoverable Vulkan backend provider: "+providers.size());
        try(var backend=providers.getFirst().create(device)){
            if(!backend.id().equals(VulkanFluidSolverBackend.ID))throw new AssertionError("Discovered backend identity differs");
            // Rejecting a prepared successor releases it without publication.
            try(var abandoned=backend.evaluate(new FluidStepRequest(0,current,List.of(),false,false,false))){
                if(!abandoned.layout().equals(initial.layout()))throw new AssertionError("Abandoned backend successor changed layout");
            }
            for(int step=0;step<3;step++){
                FluidStateAccess next;
                if(step==1){try(var candidate=backend.evaluateThermal(new FluidThermalStepRequest(step,current,false));var prepared=candidate.claimPreparedDeviceState()){
                    if(prepared==null)throw new AssertionError("Thermal backend did not retain a private device candidate");next=prepared.commit();}}
                else{try(var candidate=backend.evaluate(new FluidStepRequest(step,current,List.of(),false,false,false));var prepared=candidate.claimPreparedDeviceState()){
                    if(prepared==null)throw new AssertionError("Mechanical backend did not retain a private device candidate");next=prepared.commit();}}
                current.close();current=next;if(!(current instanceof ResidentFluidState resident))throw new AssertionError("Backend adoption lost GPU residency");
                var summary=resident.inventorySummary();if(summary.liquidMassUnits()!=expectedMass||summary.gasMassUnits()!=expectedGasMass||!resident.observation().thermalAuditClosed())throw new AssertionError("Backend adoption changed liquid/gas inventory or thermal audit");
                requireWetSample(resident.sampleBody(.5,.25,.5,1,.75,1));if(resident.display().thermal()==null)throw new AssertionError("Backend thermal display unavailable");
                requireCellAndWater(resident.observeCellInventory(1,0,1),resident.waterContacts(contactFaces()));
                if(step==0){var before=resident.inventorySummary();try(var abandoned=backend.evaluate(new FluidStepRequest(step+1,current,List.of(),false,false,false))){}
                    if(!before.equals(resident.inventorySummary()))throw new AssertionError("Rejected resident candidate changed its pinned source summary");}
                System.out.println("backend_committed="+(step+1)+" resident=true summary_mass_units="+summary.liquidMassUnits()+" body_water_contact=true");
            }
            // An explicit same-layout import reuses the resident storage and can be abandoned.
            long importsBefore = ((VulkanFluidSolverBackend) backend).reusedImportCount();
            var exported = current.exportState();
            var stableSummary = ((ResidentFluidState) current).inventorySummary();
            try (var abandoned = backend.prepareState(current, exported, current.finiteThermalDevices(), current.finiteThermalPorts())) {
                if (!abandoned.layout().equals(current.layout())) throw new AssertionError("Edit changed layout");
            }
            if (!stableSummary.equals(((ResidentFluidState) current).inventorySummary()))
                throw new AssertionError("Abandoned import changed the committed generation");
            try (var imported = backend.prepareState(current, exported, current.finiteThermalDevices(), current.finiteThermalPorts())) {
                var next = imported.commit();
                current.close();
                current = next;
            }
            requireResidentInventory(current, expectedMass, expectedGasMass);
            if (((VulkanFluidSolverBackend) backend).reusedImportCount() != importsBefore + 2)
                throw new AssertionError("Same-layout imports did not reuse resident candidate storage");
            var editedHost = HostFluidState.restoreAuthoritativeState(current.exportState());
            editedHost.setThermalTemperatureInCell(1, 0, 1, 350);
            try (var edited = backend.prepareState(current, editedHost.captureAuthoritativeState(), current.finiteThermalDevices(), current.finiteThermalPorts())) {
                var next = edited.commit(); current.close(); current = next;
            }
            requireDisplayTemperature(((ResidentFluidState) current).display().thermal(), 65, 350);
            var bodyId=java.util.UUID.fromString("a7184c41-7a9c-4c56-8b2a-e7d7b474ca22");
            for(int step=3;step<5;step++){
                // First insertion displaces liquid; the next pose changes per-cell occupied volume.
                double shift=(step-3)*.005;
                var body=new mmm.world.MovingBodyFluidCoupling.Request(bodyId,
                        new mmm.world.MovingBodyFluidCoupling.Bounds(1.42+shift,.15,1.42,1.58+shift,.35,1.58),.1,0,0);
                try(var candidate=backend.evaluate(new FluidStepRequest(step,current,List.of(body),false,false,false));
                    var prepared=candidate.claimPreparedDeviceState()){
                    requireBodyResult(candidate.movingBodyCoupling(),bodyId);
                    var next=prepared.commit();current.close();current=next;
                }
                requireResidentInventory(current,expectedMass,expectedGasMass);
            }
            var withBodies = current.exportState();
            long bodyImportsBefore = ((VulkanFluidSolverBackend) backend).reusedImportCount();
            try (var abandoned = backend.prepareState(current, withBodies, current.finiteThermalDevices(), current.finiteThermalPorts())) { }
            try (var edited = backend.prepareState(current, withBodies, current.finiteThermalDevices(), current.finiteThermalPorts())) {
                var next = edited.commit(); current.close(); current = next;
            }
            if (((VulkanFluidSolverBackend) backend).reusedImportCount() != bodyImportsBefore + 2)
                throw new AssertionError("Entity workspace imports did not reuse resident candidate storage");
            requireResidentInventory(current, expectedMass, expectedGasMass);
            var checkpoint=current.exportState();
            if(!checkpoint.layout().equals(initial.layout()))throw new AssertionError("Backend final explicit checkpoint changed layout");
            var restored=HostFluidState.restoreAuthoritativeState(checkpoint).captureAuthoritativeState();
            current.close();current=restored;
            var retainedBody=new mmm.world.MovingBodyFluidCoupling.Request(bodyId,
                    new mmm.world.MovingBodyFluidCoupling.Bounds(1.425,.15,1.42,1.585,.35,1.58),0,0,0);
            try(var candidate=backend.evaluate(new FluidStepRequest(5,current,List.of(retainedBody),false,false,false));
                var prepared=candidate.claimPreparedDeviceState()){
                requireBodyResult(candidate.movingBodyCoupling(),bodyId);
                var next=prepared.commit();current.close();current=next;
            }
            requireResidentInventory(current,expectedMass,expectedGasMass);
            System.out.println("backend_prescribed_body=true occupied_pose_changed=true named_result=true checkpoint_continuation=true");
            current.close();current=initial;
        }finally{current.close();}
        System.out.println("Backend evaluate, rejection, device claim/adoption, mechanical/thermal continuation, constant readouts and explicit checkpoint passed");
    }
    private static void requireDisplayTemperature(ThermalDisplaySnapshot display, int cell, int temperatureK) {
        if (display == null || Math.abs(Short.toUnsignedInt(display.cellTemperatureQ()[cell]) - 16 * temperatureK) > 1)
            throw new AssertionError("Imported thermal display temperature differs at cell " + cell);
    }
    private static void validateThermalBoundaryCandidate(int device,FluidDomainState initial) {
        try (var backend = new VulkanFluidSolverBackend(device);
             var initialCandidate = backend.prepareState(null, initial, null, List.of());
             var imported = initialCandidate.commit()) {
            var source = (ResidentFluidState) imported;
            var before = source.inventorySummary();
            var original = source.exportState().copyThermalBoundaryTemperaturesK();
            double[] changed = new double[6 * initial.sizeX() * initial.sizeY() * initial.sizeZ()];
            java.util.Arrays.fill(changed, Double.NaN);
            changed[0] = 350;
            long beforeReadback = backend.readbackBytes();
            try (var abandoned = backend.prepareThermalBoundary(source, changed)) {
                if (backend.readbackBytes() - beforeReadback != 4)
                    throw new AssertionError("Preparing thermal input read back resident inventory");
                if (!java.util.Arrays.equals(changed, abandoned.exportState().copyThermalBoundaryTemperaturesK()))
                    throw new AssertionError("Private thermal input candidate differs");
            }
            if (!java.util.Arrays.equals(original, source.exportState().copyThermalBoundaryTemperaturesK())
                    || !before.equals(source.inventorySummary()))
                throw new AssertionError("Rejected thermal input changed its source");
            try (var accepted = backend.prepareThermalBoundary(source, changed);
                 var successor = accepted.commit()) {
                if (!java.util.Arrays.equals(changed, successor.exportState().copyThermalBoundaryTemperaturesK())
                        || !before.equals(((ResidentFluidState) successor).inventorySummary()))
                    throw new AssertionError("Adopted thermal input changed inventory or lost its temperature");
                try { accepted.commit(); throw new AssertionError("Thermal candidate was adopted twice"); }
                catch (IllegalStateException expected) { }
                try (var thermal = backend.evaluateThermal(new FluidThermalStepRequest(1, successor, false));
                     var prepared = thermal.claimPreparedDeviceState(); var advanced = prepared.commit()) {
                    if (!java.util.Arrays.equals(changed, advanced.exportState().copyThermalBoundaryTemperaturesK()))
                        throw new AssertionError("Thermal input disappeared after a device step");
                }
            }
        }
    }
    private static void requireBodyResult(mmm.world.MovingBodyFluidCoupling.Result result,java.util.UUID id){
        if(!(result.coupledVolumeCubicMetres()>0)||result.bodyImpulses().size()!=1||!result.bodyImpulses().getFirst().bodyId().equals(id))
            throw new AssertionError("Prescribed-body request did not return its wet interaction result");
    }
    private static void requireGasCell(CellCarrierInventoryView observed, CellCarrierInventoryView saved) {
        if (observed == null || observed.gas() == null || saved == null || saved.gas() == null)
            throw new AssertionError("Compact cell observation lost its finite gas chamber");
        var gas = observed.gas();
        var expected = saved.gas();
        if (gas.ambient() || !gas.components().equals(expected.components())
                || Math.abs(gas.absolutePressurePascal() - expected.absolutePressurePascal()) > .01
                || Math.abs(gas.temperatureKelvin() - expected.temperatureKelvin()) > 1e-6)
            throw new AssertionError("Compact gas observation differs from saved inventory: " + gas + " vs " + expected);
    }
    private static void requireResidentInventory(FluidStateAccess state,long expectedMass,long expectedGasMass){
        if(!(state instanceof ResidentFluidState resident)||resident.inventorySummary().liquidMassUnits()!=expectedMass||resident.inventorySummary().gasMassUnits()!=expectedGasMass||!resident.observation().thermalAuditClosed())
            throw new AssertionError("Prescribed-body continuation changed liquid/gas inventory or thermal accounting");
    }
    private static void requireWetSample(FluidDomainContracts.BodyFluidSample sample){
        if(!(sample.displacedVolumeCubicMetres()>0&&sample.displacedMassKilograms()>0&&sample.wetSurfaceAreaSquareMetres()>0)
                ||Math.abs(sample.waterContactVolumeCubicMetres()-sample.displacedVolumeCubicMetres())>1e-7)
            throw new AssertionError("Committed pure-water body query lost physical overlap or water contact");
    }
    private static List<FluidDomainContracts.WaterContactFace> contactFaces(){return List.of(new FluidDomainContracts.WaterContactFace(0,.5,0,.5,.5,1,1),new FluidDomainContracts.WaterContactFace(2,.5,.5,1,0,.5,1));}
    private static void requireCellAndWater(FluidCellInventoryView cell,List<FluidDomainContracts.WaterContact> contacts){
        if(cell==null||cell.materials().isEmpty()||cell.temperatureKelvin().isEmpty()||contacts.isEmpty())throw new AssertionError("Committed cell/water readout lost liquid or temperature");
        double volume=0;var keys=new java.util.HashSet<String>();for(var contact:contacts){if(contact.areaSquareMetres()<=0||contact.waterVolumeCubicMetres()<=0||!keys.add(contact.cell()+":"+contact.supportMask()+":"+contact.materialHandle()))throw new AssertionError("Water contact lost physical area or duplicated a stock");volume+=contact.waterVolumeCubicMetres();}
        if(volume>cell.liquidVolumeCubicMetres()+1e-9)throw new AssertionError("Water contact repeats contacted inventory across faces");
    }
}
