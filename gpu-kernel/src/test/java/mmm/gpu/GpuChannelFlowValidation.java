package mmm.gpu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import mmm.fluid.*;
import mmm.inventory.DeviceLiquidInventory;
import mmm.thermal.ThermalMaterialDefinition;
import mmm.thermal.ThermalLimits;
import mmm.thermal.ThermalAuditLedger;
import mmm.units.DeviceDyadicArray;
import mmm.units.Mass;
import mmm.units.SignedDyadic;

/** A common liquid head difference drives resolved channels through complete resident mechanical steps. */
public final class GpuChannelFlowValidation {
    private GpuChannelFlowValidation() { }
    public static void main(String[] args) {
        int device=args.length>0?Integer.parseInt(args[0]):0;
        int steps=args.length>1?Integer.parseInt(args[1]):16;
        try(var context=new VulkanDeviceContext(device,true,true);var geometry=new VulkanLiquidGeometry(context);
            var projection=new VulkanLiquidProjection(context);var pressure=new VulkanPressureKernel(context,4096);
            var multigrid=new VulkanPressureMultigrid(context)) {
            double base=run(context,geometry,projection,pressure,multigrid,4,2,20,steps);
            double highMu=run(context,geometry,projection,pressure,multigrid,4,2,40,steps);
            double longer=run(context,geometry,projection,pressure,multigrid,8,2,20,steps);
            double wider=run(context,geometry,projection,pressure,multigrid,4,3,20,steps);
            System.out.printf("channel transferred normalized volume: base=%.9g highMu=%.9g long=%.9g wide=%.9g%n",base,highMu,longer,wider);
            if(!(base>0 && highMu<base && longer<base && wider>base))
                throw new AssertionError("Pressure-driven channel flow does not follow viscosity, length and cross-section trends");
        }
    }
    private static double run(VulkanDeviceContext context,VulkanLiquidGeometry geometry,VulkanLiquidProjection projection,
                              VulkanPressureKernel pressure,VulkanPressureMultigrid multigrid,int length,int width,double mu,int steps) {
        int sx=length+6,sy=6,sz=5,n=sx*sy*sz,right=length+3;
        double h=.5, mass=1000*h*h*h;
        byte[] masks=new byte[n];int[] offsets=new int[n+1];
        var material=new ArrayList<Integer>();var support=new ArrayList<Integer>();var volumes=new ArrayList<SignedDyadic>();
        for(int z=0;z<sz;z++)for(int y=0;y<sy;y++)for(int x=0;x<sx;x++){
            int c=(z*sy+y)*sx+x;
            boolean reservoir=(x<3||x>=right)&&z>=1&&z<=3;
            boolean channel=x>=3&&x<right&&z>=1&&z<=width&&y<2;
            boolean open=reservoir||channel;
            masks[c]=(byte)(open?0:255);
            boolean wet=open&&(channel||(x<3?y<4:y<2));
            if(wet){material.add(0);support.add(255);volumes.add(SignedDyadic.of(1));}
            offsets[c+1]=material.size();
        }
        int stocks=material.size();long[] masses=new long[stocks],energy=new long[stocks];
        Arrays.fill(masses,Mass.fromKilograms(mass));Arrays.fill(energy,Double.doubleToRawLongBits(mass*4200*293.15));
        var inventory=new DeviceLiquidInventory(offsets,material.stream().mapToInt(Integer::intValue).toArray(),
                support.stream().mapToInt(Integer::intValue).toArray(),DeviceDyadicArray.encode(volumes),masses,energy,
                new int[stocks],new int[stocks+1],new int[0],new long[0],new long[stocks]);
        var momentum=new MacMomentumState(sx,sy,sz,new double[(sx+1)*sy*sz],new double[sx*(sy+1)*sz],new double[sx*sy*(sz+1)]);
        var fluids=new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER));
        var constitutive=BuiltinFluidMaterials.createRegistry();
        var caloric=List.of(new ThermalMaterialDefinition("mmm:water",ThermalMaterialDefinition.CarrierKind.LIQUID,"mmm:water",
                293.15,4200*293.15,0,List.of(new ThermalMaterialDefinition.CaloricSegment(ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K,10000,4200))));
        var definitions=new VulkanLiquidRegion.MechanicalDefinitions(n,0,4*n,4,-40,new int[]{0},new long[]{0},
                new double[]{4200*293.15},new double[]{0},1,VulkanLiquidThermodynamics.tables(caloric),
                new VulkanLiquidViscosity.Tables(new int[]{0,1},new double[]{273,1000,mu,mu}),
                VulkanLiquidForces.tables(fluids,constitutive),VulkanSurfaceTension.tables(fluids),true);
        var parameters=new VulkanLiquidProjection.Parameters((float)h,.05f,9.81f,1e-5f,200);
        try(var region=new VulkanLiquidRegion(context,geometry,projection,pressure,multigrid,inventory,momentum,
                FixedGeometrySnapshot.fromMasks(sx,sy,sz,masks),new long[n],definitions,null,null,null)) {
            var audit=ThermalAuditLedger.initialized(SignedDyadic.sum(Arrays.stream(energy).mapToDouble(Double::longBitsToDouble).toArray()));
            for(int step=0;step<steps;step++)try(var source=region.captureState();
                    var prepared=region.prepareMechanical(source,parameters,512);var successor=prepared.captureState()){
                audit=successor.thermalPosting().apply(audit);
                if(!audit.acceptsInventory(successor.exactThermalInventory()))
                    throw new AssertionError("Channel thermal inventory differs from its energy audit at step "+step);
                prepared.commit();
            }
            var result=region.checkpointLiquid().inventory();
            double total=0,rightVolume=0;
            for(int c=0;c<n;c++)for(int row=result.cellOffsets()[c];row<result.cellOffsets()[c+1];row++){
                double volume=result.volumes().valueAt(row).toDouble();total+=volume;
                if(c%sx>=right)rightVolume+=volume;
            }
            if(Math.abs(total-stocks)>1e-8)throw new AssertionError("Channel liquid volume changed: "+total+" vs "+stocks);
            long actualMass=0;for(long units:result.massUnits())actualMass=Math.addExact(actualMass,units);
            if(actualMass!=Math.multiplyExact(Mass.fromKilograms(mass),(long)stocks))
                throw new AssertionError("Channel liquid mass changed");
            double transferred=rightVolume-18;
            System.out.printf("length=%d width=%d viscosity=%.3g steps=%d transfer=%.9g%n",length,width,mu,steps,transferred);
            return transferred;
        }
    }
}
