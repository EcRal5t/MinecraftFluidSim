package mmm.gpu;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import mmm.chemistry.BuiltinChemicalDefinitions;
import mmm.fluid.*;
import mmm.fluid.air.BuiltinGasDefinitions;
import mmm.granular.BuiltinGranularDefinitions;
import mmm.thermal.*;
import mmm.units.Mass;

/** Two closed physical groups: one advances while the other retains its complete inventory. */
public final class GpuActivityMaskValidation {
    private static final int X=6, Y=4, Z=4;
    public static void main(String[] args) throws Exception {
        validateActivityGraph();
        int device=Integer.parseInt(args[0]);var definitions=Path.of(args[1]);
        var chemistry=BuiltinChemicalDefinitions.REGISTRY;var granular=BuiltinGranularDefinitions.createRegistry();var gas=BuiltinGasDefinitions.createRegistry();
        var thermal=new ThermalRegistry(GpuThermalPhaseValidation.read(definitions,"thermal_materials",ThermalDefinitionCodec::decodeMaterial),
            GpuThermalPhaseValidation.read(definitions,"thermal_species",ThermalDefinitionCodec::decodeSpecies),
            GpuThermalPhaseValidation.read(definitions,"phase_transitions",ThermalDefinitionCodec::decodeTransition),
            List.of(),List.of(),chemistry.fingerprint(),granular.fingerprint(),gas.fingerprint());
        var domain=new HostFluidState(X,Y,Z,new FluidSolverConfig(.5f,1000,9.81f,.05f,200,1e-5f,.002f));
        var fluids=new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER));
        domain.bindMaterialRegistry(fluids);domain.bindMaterialDefinitions(BuiltinFluidMaterials.createRegistry());domain.sealDomainBoundary();
        for(int z=0;z<Z;z++)for(int y=0;y<Y;y++)domain.setSolidMicroMask(3,y,z,255);
        // An isolated cut cell connects to the active chamber through a partial face.
        domain.setSolidMicroMask(0,3,3,2);domain.setSolidMicroMask(0,2,3,255);domain.setSolidMicroMask(0,3,2,255);
        domain.setVolume(1,2,1,.5f);domain.setVolume(1,1,1,.5f);domain.setVolume(4,2,1,.5f);
        domain.bindChemistry(chemistry);domain.bindGasRegistry(gas);
        domain.bindGranular(granular,fluids,BuiltinFluidMaterials.createRegistry());
        int sand=granular.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
        long svq=granular.material(sand).maximumActiveParcelSvq();
        long sandMass=Mass.fromKilograms(granular.material(sand).referenceDensity()*Math.scalb((double)svq,-27));
        var inserted=domain.tryInsertExactGranular(4,1,1,sand,0,svq,sandMass);
        if(!inserted.inserted())throw new AssertionError("Paused granular fixture was not inserted: "+inserted.failure());
        domain.bindThermal(thermal,fluids,300);
        domain.setThermalTemperatureInCell(1,2,1,330);
        double iceEnergy=new ThermalStateFunction(thermal).internalEnergyAtTemperature(thermal.materialHandle("mmm:ice"),1,Map.of(),300);
        domain.addCoherentPhaseExternal(4,0,2,"mmm:ice",146_000,Mass.fromKilograms(1),null,iceEnergy);
        domain.bootstrapGasState(chemistry.fingerprint());
        domain.restorePendingPhaseMotionState(new PendingPhaseMotionState(X*Y*Z,
                List.of(new PendingPhaseMotionState.Entry(index(1,1,1)),new PendingPhaseMotionState.Entry(index(4,1,1)))));
        var retainedBody = new mmm.world.MovingBodyFluidCoupling.Request(java.util.UUID.randomUUID(),
                new mmm.world.MovingBodyFluidCoupling.Bounds(2.05,.55,1.05,2.3,.8,1.3),0,0,0);
        domain.restoreEntityBodyState(new EntityBodyState(List.of(retainedBody)));
        double[] x=new double[(X+1)*Y*Z],y=new double[X*(Y+1)*Z],z=new double[X*Y*(Z+1)];
        x[(1*Y+2)*(X+1)+1]=.01;x[(1*Y+2)*(X+1)+5]=.01;
        domain.restoreMacMomentum(new MacMomentumState(X,Y,Z,x,y,z));
        var initial=domain.captureAuthoritativeState();
        boolean[] active=new boolean[X*Y*Z];for(int c=0;c<active.length;c++)active[c]=c%X<3;
        try(var backend=new VulkanFluidSolverBackend(device);
            var imported=backend.prepareState(null,initial,null,List.of());var resident=imported.commit()) {
            var before=resident.exportState();
            try(var candidate=backend.evaluate(new FluidStepRequest(0,resident,List.of(retainedBody),false,0,false,false,active));
                var prepared=candidate.claimPreparedDeviceState();var state=(ResidentFluidState)prepared.commit()) {
            checkMechanicalCounters(state);
            checkActivity(state,active);
            var after=state.exportState();
            if (!after.entityBodyState().equals(before.entityBodyState()))
                throw new AssertionError("Paused body geometry changed");
            if (!after.pendingPhaseMotion().entries().equals(List.of(new PendingPhaseMotionState.Entry(index(4,1,1)))))
                throw new AssertionError("Mechanical step must retain pending phase motion only in paused groups");
            checkPaused(before,after);
            var beforeActive=liquidTotals(before,true);var afterActive=liquidTotals(after,true);
            if(beforeActive.equals(afterActive))throw new AssertionError("Active group did not transport liquid");
            checkMomenta(before.momentum(),after.momentum());
            try(var thermalCandidate=backend.evaluateThermal(new FluidThermalStepRequest(1,state,true,ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP,active));
                var thermalPrepared=thermalCandidate.claimPreparedDeviceState();var thermalState=(ResidentFluidState)thermalPrepared.commit()) {
                checkActivity(thermalState,active);
                if(thermalState.observation().gasPressureFlow())
                    throw new AssertionError("Thermal candidate must not report an earlier mechanical gas flow");
                var heated=thermalState.exportState();
                checkPaused(after,heated);
                if(liquidEnergy(after,true).equals(liquidEnergy(heated,true)))
                    throw new AssertionError("Thermal step did not change active liquid energy");
            }
            System.out.println("GPU activity groups: active transport/heat and paused liquid, gas, solid, energy and momentum passed");
            }
        } finally { initial.close(); }
    }
    private static void checkMechanicalCounters(ResidentFluidState state) throws Exception {
        var captureField=state.getClass().getDeclaredField("capture");captureField.setAccessible(true);
        var capture=(VulkanLiquidRegion.Capture)captureField.get(state);
        var gasField=capture.getClass().getDeclaredField("currentGas");gasField.setAccessible(true);
        if(!gasField.getBoolean(capture))throw new AssertionError("Fixture must complete phase gas topology");
        var layoutField=capture.getClass().getDeclaredField("layout");layoutField.setAccessible(true);
        var layout=(VulkanLiquidWorkspace)layoutField.get(capture);
        var read=capture.getClass().getDeclaredMethod("read",long.class,long.class);read.setAccessible(true);
        var control=layout.transport.dynamics.transitions.column(VulkanGranularTransitions.Field.CONTROL);
        var raw=(java.nio.ByteBuffer)read.invoke(capture,control.offset(),24L);
        int[] expected=new int[6];raw.asIntBuffer().get(expected);
        if(expected[0]==0)throw new AssertionError("Fixture must retain a granular parcel in the mechanical control");
        if(!Arrays.equals(expected,capture.granularCounters()))
            throw new AssertionError("Granular counters differ from the mechanical candidate control");
        var mass=layout.transport.gas.transport.column(VulkanLocalGasTransport.Field.PRIMAL_MASS);
        var values=(java.nio.ByteBuffer)read.invoke(capture,mass.offset(),mass.bytes());
        boolean active=false;
        for(int i=0;i<mass.count();i++)if(values.getLong(8*i)!=0)active=true;
        if(capture.gasFlowActive()!=active)
            throw new AssertionError("Gas flow observation differs from the local transported mass");
    }
    private static void checkActivity(ResidentFluidState state,boolean[] world) {
        var available=state.activityGraph().available(world);
        if(!available[index(1,1,1)]||available[index(4,1,1)])
            throw new AssertionError("Current gas groups must preserve partial availability after a candidate");
    }
    private static void validateActivityGraph() {
        byte[] masks = {0, 0, (byte) 255, 0, 0};
        var geometry = FixedGeometrySnapshot.fromMasks(5, 1, 1, masks);
        boolean[] world = {true, true, false, false, false};
        int[] separateGas = {1, 1, -1, 2, 2};
        var graph = new PhysicalActivityGraph(5, 1, 1, geometry, separateGas, new int[0], List.of(), EntityBodyState.EMPTY, .5);
        var available = graph.available(world);
        if (!available[0] || !available[1] || available[3] || available[4])
            throw new AssertionError("A missing chunk must block only its connected activity group");
        var restored = graph.available(new boolean[]{true, true, true, true, true});
        if (!restored[0] || !restored[4]) throw new AssertionError("Reloaded groups must become available");
        int[] sharedGas = {1, 1, -1, 1, 1};
        graph = new PhysicalActivityGraph(5, 1, 1, geometry, sharedGas, new int[0], List.of(), EntityBodyState.EMPTY, .5);
        if (graph.available(world)[0]) throw new AssertionError("One gas chamber must keep every occupied cell together");
        var firstPort = new FluidStateAccess.DevicePort("provider", "device", new FluidDomainContracts.ThermalPortRequest(1,0,0,0,.1,1,.1));
        var secondPort = new FluidStateAccess.DevicePort("provider", "device", new FluidDomainContracts.ThermalPortRequest(3,0,0,1,.1,1,.1));
        graph = new PhysicalActivityGraph(5, 1, 1, geometry, separateGas, new int[0], List.of(firstPort, secondPort), EntityBodyState.EMPTY, .5);
        if (graph.available(world)[0]) throw new AssertionError("One finite device must connect its ports");
        var id = java.util.UUID.randomUUID();
        var before = new mmm.world.MovingBodyFluidCoupling.Request(id,
                new mmm.world.MovingBodyFluidCoupling.Bounds(.25,0,0,.75,.5,.5),0,0,0);
        var after = new mmm.world.MovingBodyFluidCoupling.Request(id,
                new mmm.world.MovingBodyFluidCoupling.Bounds(1.5,0,0,2,.5,.5),0,0,0);
        graph = new PhysicalActivityGraph(5, 1, 1, geometry, separateGas, new int[0], List.of(), new EntityBodyState(List.of(before)), .5);
        graph.joinBodyMotion(new EntityBodyState(List.of(before)), new EntityBodyState(List.of(after)), .5);
        if (graph.available(world)[0]) throw new AssertionError("A body sweep must connect its old and new groups");
        int[] supports = {1,-1,-1,-1,-1,-1,-1,-1};
        graph = new PhysicalActivityGraph(5, 1, 1, geometry, separateGas, supports, List.of(), EntityBodyState.EMPTY, .5);
        if (!graph.available(world)[0]) throw new AssertionError("Particle neighbourhood must respect a full-cell wall");
        var adjacent = FixedGeometrySnapshot.fromMasks(2, 1, 1, new byte[]{(byte) 170, (byte) 85});
        graph = new PhysicalActivityGraph(2, 1, 1, adjacent, null,
                new int[]{0,-1,-1,-1,-1,-1,-1,-1}, List.of(), EntityBodyState.EMPTY, .5);
        if (graph.available(new boolean[]{true, false})[0])
            throw new AssertionError("Particle contact neighbourhood must join nearby activity groups");
    }
    private static void checkPaused(FluidDomainState before,FluidDomainState after){
        var beforePaused=liquidTotals(before,false);
        if(beforePaused.isEmpty()||!beforePaused.equals(liquidTotals(after,false)))
            throw new AssertionError("Paused liquid volume, mass, composition or energy changed");
            if(!before.granular().activeParcels().equals(after.granular().activeParcels())
                    ||!before.granular().depositGroups().equals(after.granular().depositGroups())
                    ||!Arrays.equals(before.thermalOwner().copyActiveParcelEnergy(),after.thermalOwner().copyActiveParcelEnergy())
                    ||!Arrays.equals(before.thermalOwner().copyDepositEnergy(),after.thermalOwner().copyDepositEnergy())
                    ||!before.thermalOwner().coherentGroups().equals(after.thermalOwner().coherentGroups()))
                throw new AssertionError("Paused solid inventory or energy changed");
            int beforeNode=before.gas().cellNodeOrdinal()[index(4,3,1)],afterNode=after.gas().cellNodeOrdinal()[index(4,3,1)];
            if(beforeNode<=0||afterNode<=0||!before.gas().nodes().get(beforeNode-1).inventory().equals(after.gas().nodes().get(afterNode-1).inventory())
                    ||Double.doubleToLongBits(before.thermalOwner().copyGasNodeEnergy()[beforeNode-1])!=Double.doubleToLongBits(after.thermalOwner().copyGasNodeEnergy()[afterNode-1]))
                throw new AssertionError("Paused gas inventory or energy changed");
    }
    private record LiquidTotal(mmm.units.SignedDyadic volume,long mass,mmm.units.SignedDyadic energy,Map<String,Long> species) { }
    private static Map<String,LiquidTotal> liquidTotals(FluidDomainState state,boolean active){
        var totals=new java.util.TreeMap<String,LiquidTotal>();
        for(var stock:state.liquid().stocks()){
            if((stock.key().cell()%X<3)!=active || !active&&stock.key().cell()%X<4)continue;
            String key=stock.key().cell()+":"+stock.key().material();var old=totals.get(key);
            var species=new java.util.TreeMap<String,Long>();if(old!=null)species.putAll(old.species());
            if(stock.mixture()!=null)stock.mixture().stableAmounts(state.chemicalRegistry()).forEach((id,n)->species.merge(id,n,Math::addExact));
            totals.put(key,new LiquidTotal((old==null?mmm.units.SignedDyadic.ZERO:old.volume()).add(stock.volume()),
                Math.addExact(old==null?0:old.mass(),stock.massUnits()),
                (old==null?mmm.units.SignedDyadic.ZERO:old.energy()).add(mmm.units.SignedDyadic.of(stock.internalEnergy())),Map.copyOf(species)));
        }
        return totals;
    }
    private static mmm.units.SignedDyadic liquidEnergy(FluidDomainState state,boolean active){
        var result=mmm.units.SignedDyadic.ZERO;for(var value:liquidTotals(state,active).values())result=result.add(value.energy());return result;
    }
    private static int index(int x,int y,int z){return (z*Y+y)*X+x;}
    private static void checkMomenta(MacMomentumState before,MacMomentumState after){
        double[][] a={before.copyX(),before.copyY(),before.copyZ()},b={after.copyX(),after.copyY(),after.copyZ()};
        for(int axis=0;axis<3;axis++){
            int sx=X+(axis==0?1:0),sy=Y+(axis==1?1:0),sz=Z+(axis==2?1:0);
            for(int z=0;z<sz;z++)for(int y=0;y<sy;y++)for(int x=4;x<sx;x++){
                int i=(z*sy+y)*sx+x;
                if(Double.doubleToLongBits(a[axis][i])!=Double.doubleToLongBits(b[axis][i]))
                    throw new AssertionError("Paused face momentum changed: axis="+axis+" index="+i);
            }
        }
    }
}
