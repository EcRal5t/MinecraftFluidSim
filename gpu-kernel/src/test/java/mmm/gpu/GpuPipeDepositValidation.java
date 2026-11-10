package mmm.gpu;

import java.util.*;
import mmm.fluid.*;
import mmm.granular.*;
import mmm.units.Mass;

/** A saturated granular deposit restricts a resolved pipe until it is extracted. */
public final class GpuPipeDepositValidation {
    private static final int X=12,Y=4,Z=4, BLOCKER=5, OUTLET=9;
    private GpuPipeDepositValidation() { }
    public static void main(String[] args) {
        mmm.fluid.PipeBedSamplingValidation.run();
        int device=Integer.parseInt(args[0]);
        var blocked=fixture(BuiltinGranularDefinitions.FINE_SAND,1);long deposited=blocked.exportGranularState().totalSolidVolumeSvq();
        var fine=fixture(BuiltinGranularDefinitions.FINE_SILT,1);
        var clean=HostFluidState.restoreAuthoritativeState(blocked.captureAuthoritativeState());
        long extracted=0;
        for(int y=1;y>=0;y--)for(int z=0;z<2;z++) {
            var result=clean.tryExtractExactGranular(BLOCKER,y,z,SolidVolume.SVQ_PER_CUBIC_METER,0);
            if(!result.transferred())throw new AssertionError("Pipe deposit extraction rejected: "+result.failure());
            extracted=Math.addExact(extracted,result.ledger().totalSolidVolumeSvq());
        }
        if(extracted!=deposited || clean.exportGranularState().totalSolidVolumeSvq()!=0)
            throw new AssertionError("Pipe cleaning changed solid inventory");
        saturate(blocked);saturate(fine);saturate(clean);
        // Balance the extra clean-pipe liquid in an isolated, non-driving inventory cell.
        double difference=clean.totalVolumeExact()-blocked.totalVolumeExact();
        if(!(difference>=0 && difference<=1))throw new AssertionError("Fixture balance cell is too small");
        blocked.setVolume(6,3,3,(float)difference);
        fine.setVolume(6,3,3,(float)difference);
        if(Math.abs(blocked.totalVolumeExact()-clean.totalVolumeExact())>1e-6
                || Math.abs(blocked.totalMassUnits()-clean.totalMassUnits())>Mass.fromKilograms(.0002))
            throw new AssertionError("Pipe comparison liquid inventories differ: "+blocked.totalVolumeExact()+" / "+clean.totalVolumeExact());
        // Compare equal initial gas pressure after the fixture's two inventory preparations.
        blocked.resetAmbientGas();fine.resetAmbientGas();clean.resetAmbientGas();
        double clogged=run(device,blocked,16),fineFlow=run(device,fine,16),cleared=run(device,clean,16);
        System.out.printf("pipe deposit accepted flow sand_m3=%.9g silt_m3=%.9g cleaned_m3=%.9g solid_svq=%d%n",clogged,fineFlow,cleared,deposited);
        if(!(cleared>0 && cleared>clogged*1.05 && clogged>fineFlow))
            throw new AssertionError("Pipe cleaning or grain-size resistance trend differs");
        var partial=fixture(BuiltinGranularDefinitions.FINE_SAND,.5);saturate(partial);partial.resetAmbientGas();
        verifyResistance(device,partial);
        var nearlyFull=fixture(BuiltinGranularDefinitions.FINE_SAND,.999999);saturate(nearlyFull);nearlyFull.resetAmbientGas();
        verifyResistance(device,nearlyFull);
    }
    private static HostFluidState fixture(String materialId,double fillFraction) {
        var defs=TestFluidDefinitions.load();var fluids=new FluidMaterialRegistry(List.of(defs.material().entry("mmm:water").material()));
        var domain=new HostFluidState(X,Y,Z,new FluidSolverConfig(.5f,9.81f,.05f,200,1e-5f,.002f),defs);
        domain.bindMaterialRegistry(fluids);domain.bindMaterialDefinitions(defs.material());domain.sealDomainBoundary();
        for(int z=0;z<Z;z++)for(int y=0;y<Y;y++)for(int x=0;x<X;x++) {
            if(x<3 || x>=OUTLET)continue;
            int open=0;
            if(y<2 && z<2) {
                int microY=y==0?1:0,microZ=z==0?1:0;
                open=(1<<(2*microY+4*microZ))|(1<<(1+2*microY+4*microZ));
            }
            domain.setSolidMicroMask(x,y,z,(~open)&255);
        }
        domain.setSolid(6,3,3,false);
        for(int z=0;z<Z;z++)for(int y=0;y<Y;y++)for(int x=0;x<X;x++) {
            if(x<3 && y<3 || x>=OUTLET && y<1)domain.setVolume(x,y,z,1);
            else if(x>=3 && x<OUTLET && x!=BLOCKER && y<2 && z<2)domain.setVolume(x,y,z,.25f);
        }
        var granular=defs.granular();int material=granular.materialHandle(materialId);
        int porosity=granular.material(material).restPorosityQ();
        long solid=(long)(((SolidVolume.SVQ_PER_CELL/4)*(1_000_000L-porosity))/1_000_000L*fillFraction);
        long mass=Mass.fromKilograms(granular.material(material).referenceDensity()*Math.scalb((double)solid,-27));
        var groups=new ArrayList<GranularStateSnapshot.DepositGroup>();
        for(int z=0;z<2;z++)for(int y=0;y<2;y++)groups.add(new GranularStateSnapshot.DepositGroup(domain.grid().cell(BLOCKER,y,z),porosity,material,0,solid,mass));
        groups.sort(Comparator.comparingInt(GranularStateSnapshot.DepositGroup::cell));
        var snapshot=new GranularStateSnapshot(X,Y,Z,granular.fingerprint(),granular.materials().stream().map(ParticulateMaterial::id).toList(),
                List.of(),groups,Math.multiplyExact(solid,4),Math.multiplyExact(mass,4));
        domain.bindGasRegistry(defs.gas());domain.restoreGranularState(snapshot,granular,fluids,defs.material());
        domain.bindThermal(defs.thermal(),fluids,293.15);
        return domain;
    }
    private static void saturate(HostFluidState domain) {
        for(int y=0;y<2;y++)for(int z=0;z<2;z++) {
            double room=domain.cellCondensedRoom(BLOCKER,y,z);
            if(!(room>0))throw new AssertionError("No pore space remains in the deposited pipe");
            float fill=Math.nextDown((float)room);
            domain.setVolume(BLOCKER,y,z,fill);
        }
    }
    private static double run(int device,HostFluidState domain,int steps) {
        double before=outlet(domain);long liquid=domain.totalMassUnits(),solid=domain.exportGranularState().totalMassUnits();
        int[] faces=new int[4];int cursor=0;
        for(int y=0;y<2;y++)for(int z=0;z<2;z++)faces[cursor++]=domain.grid().faceAt(BLOCKER+1,y,z,0);
        try(var backend=new VulkanFluidSolverBackend(device);var initial=domain.captureAuthoritativeState();
            var imported=backend.prepareState(null,initial,null,List.of())) {
            FluidStateAccess current=imported.commit();
            try {
                double flux=0,pressureIntegral=0;
                for(int step=0;step<steps;step++)try(var candidate=backend.evaluate(new FluidStepRequest(step,current,List.of(),false,0,false,false));
                        var prepared=candidate.claimPreparedDeviceState()) {
                    var next=prepared.commit();current.close();current=next;
                    flux+=((ResidentFluidState)current).observeLiquidFlow(faces)*.05;
                    var measured=HostFluidState.restoreAuthoritativeState(current.exportState());
                    if(!measured.thermalAudit().acceptsInventory(measured.exactInternalEnergy()))
                        throw new AssertionError("Pipe deposit thermal audit differs at step "+step);
                    pressureIntegral+=(measured.pressure(BLOCKER-1,0,0)-measured.pressure(BLOCKER+1,0,0))*.05;
                }
                var after=HostFluidState.restoreAuthoritativeState(current.exportState());
                if(after.totalMassUnits()!=liquid || after.exportGranularState().totalMassUnits()!=solid)
                    throw new AssertionError("Pipe step changed material inventory");
                System.out.printf("pipe outlet delta=%.9g crossing_m3=%.9g pressure_Pa_s=%.9g%n",outlet(after)-before,flux,pressureIntegral);
                return flux;
            } finally {current.close();}
        }
    }
    private static double outlet(HostFluidState host) {
        double result=0;for(int x=OUTLET;x<X;x++)for(int y=0;y<Y;y++)for(int z=0;z<Z;z++)result+=host.volume(x,y,z);return result;
    }
    private static void verifyResistance(int device,HostFluidState host) {
        try(var backend=new VulkanFluidSolverBackend(device);var initial=host.captureAuthoritativeState();
            var imported=backend.prepareState(null,initial,null,List.of());var source=imported.commit();
            var candidate=backend.evaluate(new FluidStepRequest(0,source,List.of(),false,0,false,false));
            var prepared=candidate.claimPreparedDeviceState();var state=(ResidentFluidState)prepared.commit()) {
            var field=state.getClass().getDeclaredField("capture");field.setAccessible(true);
            var capture=(VulkanLiquidRegion.Capture)field.get(state);
            var layoutField=capture.getClass().getDeclaredField("layout");layoutField.setAccessible(true);
            var layout=(VulkanLiquidWorkspace)layoutField.get(capture);
            var read=capture.getClass().getDeclaredMethod("read",long.class,long.class);read.setAccessible(true);
            var column=layout.transport.viscosity.column(VulkanLiquidViscosity.Field.BED_CONDUCTANCE);
            var bytes=(java.nio.ByteBuffer)read.invoke(capture,column.offset(),column.bytes());
            int cell=host.grid().cell(BLOCKER,0,0);
            double horizontal=bytes.getDouble(8*(3*cell)),vertical=bytes.getDouble(8*(3*cell+1));
            var muColumn=layout.transport.viscosity.column(VulkanLiquidViscosity.Field.MU);
            var muBytes=(java.nio.ByteBuffer)read.invoke(capture,muColumn.offset(),muColumn.bytes());
            double mu=muBytes.getDouble(8*cell);
            var sand=host.definitions().granular().material(host.definitions().granular().materialHandle(BuiltinGranularDefinitions.FINE_SAND));
            double phi=(float)(sand.restPorosityQ()/1_000_000.0),diameter=sand.primaryGrainDiameterMeters();
            double permeability=diameter*diameter*phi*phi*phi/(180*(1-phi)*(1-phi));
            double geometricVolume=.25*.5*.5*.5;
            double bulk=host.granularSolidVolumeSvq(BLOCKER,0,0)/134217728.0/(1-phi);
            double fraction=bulk/geometricVolume,freeK=.5*.5/12;
            double seriesK=1/((1-fraction)/freeK+fraction/permeability);
            double expected=.5*mu*geometricVolume*(1/seriesK-1/freeK);
            double gap=1-fraction,parallelK=gap*gap*gap*freeK+fraction*permeability;
            double expectedHorizontal=.5*mu*geometricVolume*(1/parallelK-1/freeK);
            System.out.printf("partial pipe bed damping horizontal=%.9g vertical=%.9g theory=%.9g kg/s%n",horizontal,vertical,expected);
            if(!(horizontal>0 && vertical>=horizontal*.999))throw new AssertionError("Vertical bed resistance did not use the series path");
            if(Math.abs(horizontal-expectedHorizontal)>expectedHorizontal*.001)throw new AssertionError("Horizontal Darcy resistance differs from narrowing-gap formula");
            if(Math.abs(vertical-expected)>expected*.001)throw new AssertionError("Vertical Darcy resistance differs from its SI formula");
        } catch(ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
}
