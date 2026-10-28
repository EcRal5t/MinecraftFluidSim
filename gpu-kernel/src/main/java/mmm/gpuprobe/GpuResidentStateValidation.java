package mmm.gpuprobe;

import java.util.ArrayList;
import java.util.List;
import mmm.fluid.*;
import mmm.thermal.*;

/** Production-format cold import, deferred adoption, constant observations and exact checkpoint. */
public final class GpuResidentStateValidation {
    public static void main(String[] args) {
        int device=args.length>0?Integer.parseInt(args[0]):0;
        var domain=new FluidDomain(8,8,8,new FluidSolverConfig(.5f,1000,9.81f,.05f,200,1e-5f,.002f));
        var fluids=new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER));
        domain.bindMaterialRegistry(fluids);domain.bindMaterialDefinitions(BuiltinFluidMaterials.createRegistry());domain.sealDomainBoundary();
        float[] volumes=new float[512];for(int c=0;c<512;c++)volumes[c]=(c/8)%8<3?.7f:0;
        domain.setVolumes(volumes);
        var materials=new ArrayList<ThermalMaterialDefinition>();
        materials.add(material("water",ThermalMaterialDefinition.CarrierKind.LIQUID,"mmm:water",4184,.6));
        var offsets=new ArrayList<ThermalSpeciesOffset>();
        for(var gas:mmm.fluid.air.BuiltinGasDefinitions.createRegistry().components()){
            String host="test:resident_"+gas.id().substring(4);
            materials.add(material(gas.id().substring(4),ThermalMaterialDefinition.CarrierKind.GAS,gas.id(),718,.026));
            for(String species:gas.allowedSpecies())offsets.add(new ThermalSpeciesOffset(host+"/"+species.substring(4),species,host,mmm.chemistry.ExactRational.ZERO));
        }
        var thermal=new ThermalRegistry(materials,offsets,List.of(),"","");
        domain.bindGasRegistry(mmm.fluid.air.BuiltinGasDefinitions.createRegistry());domain.bindThermal(thermal,fluids,300);
        domain.bootstrapGasState("");
        var initial=domain.captureAuthoritativeState();var codec=new VulkanFluidStateCodec(initial);var audit=domain.thermalAudit();long expectedMass=domain.totalMassUnits();
        if(!initial.hasGas()||initial.gas().nodes().isEmpty())throw new AssertionError("Resident fixture did not initialize a finite gas chamber");
        try(var context=new VulkanDeviceContext(device,true,true);var geometry=new VulkanLiquidGeometry(context);
            var projection=new VulkanLiquidProjection(context);var pressure=new VulkanPressureKernel(context,4096);
            var mg=new VulkanPressureMultigrid(context);var region=codec.importRegion(context,geometry,projection,pressure,mg);
            var observation=new VulkanRegionObservation(context);var display=new VulkanLiquidDisplay(context);var fields=new VulkanRegionDisplay(context);
            var bodyObservation=new VulkanBodyObservation(context);var inventorySummary=new VulkanRegionInventorySummary(context);
            var cellObservation=new VulkanCellObservation(context);var waterContacts=new VulkanWaterContacts(context)) {
            var p=new VulkanLiquidProjection.Parameters(.5f,.05f,9.81f,1e-5f,200);
            try(var before=region.captureState();var abandoned=region.prepareMechanical(before,p,512)) {
                try(var current=region.captureState()){if(current.generation()!=0)throw new AssertionError("Unadopted candidate changed committed generation");}
            }
            for(int i=0;i<3;i++) {
                try(var before=region.captureState();var prepared=i==1?region.prepareThermal(before,.05):region.prepareMechanical(before,p,512);
                    var successor=prepared.captureState()) {
                    var posting=successor.thermalPosting();audit=posting.apply(audit);
                    var exported=codec.export(successor,audit);
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
                    var cell=cellObservation.capture(successor,codec,1,0,1);requireCellAndWater(cell,waterContacts.capture(successor,codec,contactFaces()));
                    var rendered=display.capture(successor,codec.fluids(),.5f);
                    if(rendered.geometry().pieces().isEmpty())throw new AssertionError("Committed display lost liquid surface");
                    prepared.commit();
                    System.out.println("committed="+(i+1)+" liquid_volume="+totals.volume()+" mass="+totals.mass()+" audit_closed=true");
                }
            }
            System.out.println("Production state import, rejected private candidate, mechanical/thermal adoption, device readout and exact checkpoint passed");
        }
        long expectedGasMass=0;for(var node:initial.gas().nodes())expectedGasMass=Math.addExact(expectedGasMass,node.inventory().totalMassUnits());
        validateBackend(device,initial,expectedMass,expectedGasMass);
    }
    private static void validateBackend(int device,FluidDomainState initial,long expectedMass,long expectedGasMass){
        FluidStateAccess current=initial;
        var providers=java.util.ServiceLoader.load(FluidSolverBackend.Provider.class).stream()
                .map(java.util.ServiceLoader.Provider::get).filter(provider->provider.id().equals("vulkan")).toList();
        if(providers.size()!=1)throw new AssertionError("Expected one discoverable Vulkan backend provider: "+providers.size());
        try(var backend=providers.getFirst().create(device)){
            if(!backend.id().equals(VulkanFluidSolverBackend.ID))throw new AssertionError("Discovered backend identity differs");
            // Rejecting a prepared successor releases it without publication.
            try(var abandoned=backend.evaluate(new FluidStepRequest(0,current,List.of(),false,0,false,false))){
                if(!abandoned.layout().equals(initial.layout()))throw new AssertionError("Abandoned backend successor changed layout");
            }
            for(int step=0;step<3;step++){
                FluidStateAccess next;
                if(step==1){try(var candidate=backend.evaluateThermal(new FluidThermalStepRequest(step,current,false,0));var prepared=candidate.claimPreparedDeviceState()){
                    if(prepared==null)throw new AssertionError("Thermal backend did not retain a private device candidate");next=prepared.commit();}}
                else{try(var candidate=backend.evaluate(new FluidStepRequest(step,current,List.of(),false,0,false,false));var prepared=candidate.claimPreparedDeviceState()){
                    if(prepared==null)throw new AssertionError("Mechanical backend did not retain a private device candidate");next=prepared.commit();}}
                current.close();current=next;if(!(current instanceof ResidentFluidState resident))throw new AssertionError("Backend adoption lost GPU residency");
                var summary=resident.inventorySummary();if(summary.liquidMassUnits()!=expectedMass||summary.gasMassUnits()!=expectedGasMass||!resident.observation().thermalAuditClosed())throw new AssertionError("Backend adoption changed liquid/gas inventory or thermal audit");
                requireWetSample(resident.sampleBody(.5,.25,.5,1,.75,1));if(resident.display().thermal()==null)throw new AssertionError("Backend thermal display unavailable");
                requireCellAndWater(resident.observeCellInventory(1,0,1),resident.waterContacts(contactFaces()));
                if(step==0){var before=resident.inventorySummary();try(var abandoned=backend.evaluate(new FluidStepRequest(step+1,current,List.of(),false,0,false,false))){}
                    if(!before.equals(resident.inventorySummary()))throw new AssertionError("Rejected resident candidate changed its pinned source summary");}
                System.out.println("backend_committed="+(step+1)+" resident=true summary_mass_units="+summary.liquidMassUnits()+" body_water_contact=true");
            }
            var bodyId=java.util.UUID.fromString("a7184c41-7a9c-4c56-8b2a-e7d7b474ca22");
            for(int step=3;step<5;step++){
                // First insertion displaces liquid; the next pose changes per-cell occupied volume.
                double shift=(step-3)*.005;
                var body=new mmm.world.MovingBodyFluidCoupling.Request(bodyId,
                        new mmm.world.MovingBodyFluidCoupling.Bounds(1.42+shift,.15,1.42,1.58+shift,.35,1.58),.1,0,0);
                try(var candidate=backend.evaluate(new FluidStepRequest(step,current,List.of(body),false,0,false,false));
                    var prepared=candidate.claimPreparedDeviceState()){
                    requireBodyResult(candidate.movingBodyCoupling(),bodyId);
                    var next=prepared.commit();current.close();current=next;
                }
                requireResidentInventory(current,expectedMass,expectedGasMass);
            }
            var checkpoint=current.exportState();
            if(!checkpoint.layout().equals(initial.layout()))throw new AssertionError("Backend final explicit checkpoint changed layout");
            var restored=FluidDomain.restoreAuthoritativeState(checkpoint).captureAuthoritativeState();
            current.close();current=restored;
            var retainedBody=new mmm.world.MovingBodyFluidCoupling.Request(bodyId,
                    new mmm.world.MovingBodyFluidCoupling.Bounds(1.425,.15,1.42,1.585,.35,1.58),0,0,0);
            try(var candidate=backend.evaluate(new FluidStepRequest(5,current,List.of(retainedBody),false,0,false,false));
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
    private static void requireBodyResult(mmm.world.MovingBodyFluidCoupling.Result result,java.util.UUID id){
        if(!(result.coupledVolumeCubicMetres()>0)||result.bodyImpulses().size()!=1||!result.bodyImpulses().getFirst().bodyId().equals(id))
            throw new AssertionError("Prescribed-body request did not return its wet interaction result");
    }
    private static void requireResidentInventory(FluidStateAccess state,long expectedMass,long expectedGasMass){
        if(!(state instanceof ResidentFluidState resident)||resident.inventorySummary().liquidMassUnits()!=expectedMass||resident.inventorySummary().gasMassUnits()!=expectedGasMass||!resident.observation().thermalAuditClosed())
            throw new AssertionError("Prescribed-body continuation changed liquid/gas inventory or thermal accounting");
    }
    private static void requireWetSample(FluidDomain.BodyFluidSample sample){
        if(!(sample.displacedVolumeCubicMetres()>0&&sample.displacedMassKilograms()>0&&sample.wetSurfaceAreaSquareMetres()>0)
                ||Math.abs(sample.waterContactVolumeCubicMetres()-sample.displacedVolumeCubicMetres())>1e-7)
            throw new AssertionError("Committed pure-water body query lost physical overlap or water contact");
    }
    private static List<FluidDomain.WaterContactFace> contactFaces(){return List.of(new FluidDomain.WaterContactFace(0,.5,0,.5,.5,1,1),new FluidDomain.WaterContactFace(2,.5,.5,1,0,.5,1));}
    private static void requireCellAndWater(FluidCellInventoryView cell,List<FluidDomain.WaterContact> contacts){
        if(cell==null||cell.materials().isEmpty()||cell.temperatureKelvin().isEmpty()||contacts.isEmpty())throw new AssertionError("Committed cell/water readout lost liquid or temperature");
        double volume=0;var keys=new java.util.HashSet<String>();for(var contact:contacts){if(contact.areaSquareMetres()<=0||contact.waterVolumeCubicMetres()<=0||!keys.add(contact.cell()+":"+contact.supportMask()+":"+contact.materialHandle()))throw new AssertionError("Water contact lost physical area or duplicated a stock");volume+=contact.waterVolumeCubicMetres();}
        if(volume>cell.liquidVolumeCubicMetres()+1e-9)throw new AssertionError("Water contact repeats contacted inventory across faces");
    }
    private static ThermalMaterialDefinition material(String id,ThermalMaterialDefinition.CarrierKind kind,String carrier,double cp,double conductivity){
        return new ThermalMaterialDefinition("test:resident_"+id,kind,carrier,300,0,conductivity,List.of(new ThermalMaterialDefinition.CaloricSegment(1,2000,cp)));
    }
}
