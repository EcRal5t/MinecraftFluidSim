package mmm.gpuprobe;

import java.util.List;
import java.util.Set;
import java.util.Collections;
import java.util.IdentityHashMap;
import mmm.fluid.*;
import mmm.thermal.*;
import mmm.units.SignedDyadic;
import mmm.world.MovingBodyFluidCoupling;

/** Owns native execution; committed generations remain retained by their region owners. */
public final class VulkanFluidSolverBackend implements FluidSolverBackend {
    public static final String ID="mmm:vulkan-resident";
    public static final class Provider implements FluidSolverBackend.Provider {
        @Override public String id(){return "vulkan";}
        @Override public FluidSolverBackend create(int deviceIndex){return new VulkanFluidSolverBackend(deviceIndex);}
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
    private final VulkanCellObservation cellObservation;private final VulkanWaterContacts waterContacts;
    private final Set<Region> regions=Collections.newSetFromMap(new IdentityHashMap<>());
    private boolean closed;
    public VulkanFluidSolverBackend(int deviceIndex){
        var allocated=new java.util.ArrayList<AutoCloseable>();
        try {
            context=owned(allocated,new VulkanDeviceContext(deviceIndex,true,true));
            geometry=owned(allocated,new VulkanLiquidGeometry(context));projection=owned(allocated,new VulkanLiquidProjection(context));
            pressure=owned(allocated,new VulkanPressureKernel(context,4096));multigrid=owned(allocated,new VulkanPressureMultigrid(context));
            observation=owned(allocated,new VulkanRegionObservation(context));liquidDisplay=owned(allocated,new VulkanLiquidDisplay(context));granularDisplay=owned(allocated,new VulkanGranularDisplay(context));
            fieldsDisplay=owned(allocated,new VulkanRegionDisplay(context));bodyObservation=owned(allocated,new VulkanBodyObservation(context));inventorySummary=owned(allocated,new VulkanRegionInventorySummary(context));
            cellObservation=owned(allocated,new VulkanCellObservation(context));waterContacts=owned(allocated,new VulkanWaterContacts(context));
        }catch(RuntimeException|Error failure){for(var resource:allocated.reversed())try{resource.close();}catch(Exception|LinkageError closing){failure.addSuppressed(closing);}throw failure;}

    }
    private static <T extends AutoCloseable>T owned(java.util.List<AutoCloseable> resources,T resource){resources.add(resource);return resource;}
    @Override public String id(){return ID;}
    @Override public String executionDescription(){return "Vulkan resident / "+context.deviceName();}
    private State acquire(FluidStateAccess input){
        if(closed)throw new IllegalStateException("Vulkan backend is closed");
        if(input instanceof State state&&state.owner()==this)return state.retain();
        var template=input.exportState();var ports=input.finiteThermalPorts();
        var encoded=ports.stream().map(port->{var c=port.contact();return new VulkanThermalDeviceLayout.Port(port.providerId(),port.objectId(),c.cellX()+template.sizeX()*(c.cellY()+template.sizeY()*c.cellZ()),c.direction(),c.wallThicknessMetres(),c.wallConductivityWPerMetreK(),c.filmThicknessMetres());}).toList();
        var codec=new VulkanFluidStateCodec(template,input.finiteThermalDevices(),encoded);
        var region=new Region(codec,codec.importRegion(context,geometry,projection,pressure,multigrid),ports);regions.add(region);
        return new State(region,region.device.captureState(),template.thermalOwner()==null?null:template.thermalOwner().audit().withRoundoff(region.device.importRoundoff()));
    }
    @Override public synchronized FluidStepCandidate evaluate(FluidStepRequest request){
        State source=acquire(request.stateAccess());VulkanLiquidRegion.Prepared prepared=null;
        try {
            var config=source.region.codec.template().config();
            prepared=source.region.device.prepareMechanical(source.capture,new VulkanLiquidProjection.Parameters(config.cellSize(),config.timeStep(),config.gravity(),config.pressureTolerance(),config.maxPressureIterations()),512,new VulkanLiquidRegion.RequestInputs(request));
            var pending=new Pending(source,prepared);prepared=null;
            try {
                var before=source.totals();var after=pending.successor.totals();var diagnostic=pending.successor.capture.pressureDiagnostics();
                var chemical=pending.successor.capture.chemistryCounters();var granular=pending.successor.capture.granularCounters();
                var stats=new FluidDomain.StepStats(pending.prepared.substeps(),(int)diagnostic[0],(float)diagnostic[1],(float)before.volume(),(float)after.volume(),before.mass(),after.mass(),
                    chemical[3],chemical[4],chemical[5],new FluidDomain.ThermalPhaseStats(0,0,0,0),new FluidDomain.GasThermalPhaseStats(0,0,0),
                    new FluidDomain.MechanicalStepAudit(false,0,0,0,0,0,0,0,0,0,0,0,0,0,0,SignedDyadic.ZERO,0),thermalStats(pending.successor),
                    new mmm.granular.GranularDepositDynamics.Stats(granular[3],granular[5],granular[4],0,0),new FluidDomain.CarrierViscosityStats(0,""),
                    new FluidDomain.ViscousStepStats(0,Double.NaN,Double.NaN,Double.NaN,Double.NaN,after.viscousHeat(),Double.NaN,Double.NaN,Double.NaN),
                    new FluidDomain.MechanicalCorrection(after.liquidForce(),after.granularForce(),after.potential(),after.gasCounterWork(),after.liquidForce()+after.granularForce()+after.potential()+after.gasCounterWork(),after.absoluteCorrection(),false,Double.NaN,Double.NaN,Double.NaN,Double.NaN,Double.NaN));
                return FluidStepCandidate.preparedDevice(ID,request.baseGeneration(),pending,stats,MovingBodyFluidCoupling.Result.NONE,
                    new FluidStepCandidate.MechanicalResolution(0,pending.prepared.substeps(),""));
            }catch(RuntimeException|Error failure){pending.close();throw failure;}
        }catch(RuntimeException|Error failure){if(prepared!=null)prepared.close();source.close();throw failure;}
    }
    @Override public synchronized FluidThermalStepCandidate evaluateThermal(FluidThermalStepRequest request){
        State source=acquire(request.stateAccess());VulkanLiquidRegion.Prepared prepared=null;
        try {
            prepared=source.region.device.prepareThermal(source.capture,source.region.codec.template().config().timeStep(),new VulkanLiquidRegion.RequestInputs(source.entityBodies(),request.phaseChangeEnabled(),request.maximumPhaseOperations()));
            var pending=new Pending(source,prepared);prepared=null;
            try{return FluidThermalStepCandidate.preparedDevice(ID,request.baseGeneration(),pending,thermalStats(pending.successor));}
            catch(RuntimeException|Error failure){pending.close();throw failure;}
        }catch(RuntimeException|Error failure){if(prepared!=null)prepared.close();source.close();throw failure;}
    }
    private FluidDomain.ThermalAxisStats thermalStats(State state){
        var totals=state.totals();var counters=state.capture.chemistryCounters();var p=state.posting;
        return new FluidDomain.ThermalAxisStats(new ThermalTransport.Stats(totals.thermalSubsteps(),totals.thermalContacts(),totals.temperatureChange(),totals.maximumHeat(),p==null?SignedDyadic.ZERO:p.roundoff(),p==null?SignedDyadic.ZERO:p.reservoir()),
            new mmm.chemistry.RegionChemistry.ReactionStats(counters[3],counters[4],counters[5]),new FluidDomain.PhaseTransferStats(0,0,0,0),
            new FluidDomain.ThermalPhaseStats(0,0,0,0),new FluidDomain.GasThermalPhaseStats(0,0,0),new FluidDomain.GasLiquidMassTransferStats(false,false));
    }
    private final class Region {
        final VulkanFluidStateCodec codec;final VulkanLiquidRegion device;final List<FluidStateAccess.DevicePort> ports;int references;
        Region(VulkanFluidStateCodec codec,VulkanLiquidRegion device,List<FluidStateAccess.DevicePort> ports){this.codec=codec;this.device=device;this.ports=List.copyOf(ports);}
        boolean disposed;
        void retain(){if(disposed)throw new IllegalStateException("Region storage closed");references++;}
        void dispose(){if(disposed)return;disposed=true;device.close();}
        void release(){if(--references==0){regions.remove(this);dispose();}}
    }
    private final class State implements ResidentFluidState {
        final Region region;final VulkanLiquidRegion.Capture capture;final ThermalAuditLedger audit;
        VulkanLiquidRegion.ThermalPosting posting;
        VulkanRegionObservation.Totals totals;InventorySummary summary;Observation observed;Display display;FiniteThermalDeviceInventory devices;boolean released;
        State(Region region,VulkanLiquidRegion.Capture capture,ThermalAuditLedger audit){this.region=region;this.capture=capture;this.audit=audit;region.retain();}
        VulkanFluidSolverBackend owner(){return VulkanFluidSolverBackend.this;}
        @Override public State retain(){synchronized(owner()){ensure();var state=new State(region,capture.retain(),audit);state.totals=totals;state.summary=summary;state.observed=observed;state.display=display;state.devices=devices;state.posting=posting;return state;}}
        private void ensure(){if(released||closed)throw new IllegalStateException("Resident generation is released");}
        VulkanRegionObservation.Totals totals(){synchronized(owner()){ensure();if(totals==null)totals=observation.capture(capture);return totals;}}
        @Override public FluidDomainState.Layout layout(){return region.codec.layout();}
        @Override public FluidDomainState exportState(){synchronized(owner()){ensure();return region.codec.export(capture,audit);}}
        @Override public FixedGeometrySnapshot fixedGeometry(){return region.codec.template().fixedGeometry();}
        @Override public EntityBodyState entityBodies(){return capture.entityBodies();}
        @Override public ThermalAuditLedger thermalAudit(){return audit;}
        @Override public FiniteThermalDeviceInventory finiteThermalDevices(){synchronized(owner()){ensure();if(devices==null)devices=region.codec.exportDevices(capture);return devices;}}
        @Override public List<FluidStateAccess.DevicePort> finiteThermalPorts(){return region.ports;}
        @Override public InventorySummary inventorySummary(){synchronized(owner()){ensure();if(summary==null)summary=inventorySummary.capture(capture,region.codec.template().config().cellSize(),audit!=null);return summary;}}
        @Override public Observation observation(){synchronized(owner()){ensure();if(observed==null){var t=totals();observed=new Observation(t.volume(),t.stocks(),(float)t.maximumSpeed(),capture.gasFlowActive(),t.particles()==0&&t.dirtyGranularColumns()==0&&capture.chemistryCounters()[5]==0,capture.hasPendingPhaseMotion(),audit==null||capture.exactThermalInventory().equals(audit.expectedInventory()));}return observed;}}
        @Override public Display display(){synchronized(owner()){ensure();if(display==null){var fields=fieldsDisplay.capture(capture,region.codec);display=new Display(liquidDisplay.capture(capture,region.codec.fluids(),region.codec.template().config().cellSize()),fields.chemical(),
            granularDisplay.capture(capture,region.codec.template().granularRegistry(),region.codec.template().config().cellSize()),fields.thermal());}return display;}}
        @Override public FluidCellInventoryView observeCellInventory(int x,int y,int z){synchronized(owner()){ensure();return cellObservation.capture(capture,region.codec,x,y,z);}}
        @Override public List<FluidDomain.WaterContact> waterContacts(List<FluidDomain.WaterContactFace> faces){synchronized(owner()){ensure();return waterContacts.capture(capture,region.codec,faces);}}
        @Override public FluidDomain.BodyFluidSample sampleBody(double minX,double minY,double minZ,double maxX,double maxY,double maxZ){synchronized(owner()){ensure();var fluids=region.codec.fluids();int water=-1;for(int m=0;m<fluids.size();m++)if(fluids.material(m).id().equals("mmm:water"))water=m;return bodyObservation.capture(capture,minX,minY,minZ,maxX,maxY,maxZ,water,region.codec.template().config().cellSize(),region.codec.template().config().gravity());}}
        @Override public void close(){synchronized(owner()){if(released)return;released=true;capture.close();region.release();}}
    }
    private final class Pending implements PreparedFluidState {
        private final State source,successor;private final VulkanLiquidRegion.Prepared prepared;private boolean released,committed;
        Pending(State source,VulkanLiquidRegion.Prepared prepared){
            this.source=source;this.prepared=prepared;var capture=prepared.captureState();
            try{var posting=source.audit==null?null:capture.thermalPosting();successor=new State(source.region,capture,posting==null?null:posting.apply(source.audit));successor.posting=posting;}
            catch(RuntimeException|Error failure){capture.close();prepared.close();throw failure;}
        }
        @Override public FluidDomainState.Layout layout(){return successor.layout();}
        @Override public FluidDomainState exportState(){return successor.exportState();}
        @Override public FluidStateAccess commit(){synchronized(VulkanFluidSolverBackend.this){if(released||committed)throw new IllegalStateException("Resident candidate already handled");prepared.commit();committed=true;State owned=successor.retain();close();return owned;}}
        @Override public void close(){synchronized(VulkanFluidSolverBackend.this){if(released)return;released=true;try{prepared.close();}finally{successor.close();source.close();}}}
    }
    @Override public synchronized void close(){if(closed)return;closed=true;for(var r:List.copyOf(regions))r.dispose();regions.clear();waterContacts.close();cellObservation.close();inventorySummary.close();bodyObservation.close();fieldsDisplay.close();granularDisplay.close();liquidDisplay.close();observation.close();multigrid.close();pressure.close();projection.close();geometry.close();context.close();}
}
