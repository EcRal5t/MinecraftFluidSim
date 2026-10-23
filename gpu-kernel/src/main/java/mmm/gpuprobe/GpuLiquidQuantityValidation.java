package mmm.gpuprobe;

import mmm.inventory.LiquidCellInventory;
import mmm.inventory.LiquidQuantityCompute;
import mmm.inventory.LiquidTransportRoutes;
import mmm.units.SignedDyadic;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Hardware comparison of actual production donor quantities, including integer cast extremes. */
public final class GpuLiquidQuantityValidation {
    public static void main(String[] args){
        int device=args.length==0?0:Integer.parseInt(args[0]);
        try(var compute=new VulkanSparseMatrixCompute(device)){
            var quantity=(VulkanLiquidQuantityCompute)compute.liquidQuantityCompute();
            var sources=List.of(
                    new LiquidQuantityCompute.Source(new double[]{Math.nextDown(1d),0},1,Long.MAX_VALUE,new long[]{Long.MAX_VALUE,(1L<<53)+1}),
                    new LiquidQuantityCompute.Source(new double[]{.5,.25,0},2,(1L<<53)+3,new long[]{Long.MAX_VALUE,17}),
                    new LiquidQuantityCompute.Source(new double[]{.125,0,.375},1,0,new long[]{Long.MAX_VALUE,(1L<<53)+1}),
                    new LiquidQuantityCompute.Source(new double[]{0,.2,.3},0,1001,new long[]{71,913}),
                    new LiquidQuantityCompute.Source(new double[]{1,0},1,Long.MAX_VALUE,new long[]{Long.MAX_VALUE}));
            var actual=quantity.partition(sources);
            for(int donor=0;donor<sources.size();donor++){
                var source=sources.get(donor);var result=actual.get(donor);
                long[] mass=allocate(source.massUnits(),source.fractions(),source.remainder());
                require(Arrays.equals(mass,result.masses()),"GPU mass partition differs at donor "+donor);
                int largest=0;for(int part=1;part<mass.length;part++)if(mass[part]>mass[largest])largest=part;
                double[] fractions=source.fractions();int remainder=source.remainder();
                if(source.massUnits()>0){fractions=new double[mass.length];remainder=largest;
                    for(int part=0;part<mass.length;part++)if(part!=largest)fractions[part]=mass[part]/(double)source.massUnits();}
                for(int species=0;species<source.speciesAmounts().length;species++)
                    require(Arrays.equals(allocate(source.speciesAmounts()[species],fractions,remainder),result.species()[species]),
                            "GPU species partition differs at donor "+donor+" species "+species);
            }
            var tail=new SignedDyadic(BigInteger.ONE.shiftLeft(1100).add(BigInteger.ONE),-1101);
            var rows=new ArrayList<List<LiquidCellInventory.Stock>>();
            rows.add(List.of(stock(0,0,tail,1001),stock(0,1,SignedDyadic.of(1,-2),2003)));
            rows.add(List.of(stock(1,0,SignedDyadic.of(1,-1),31)));
            rows.add(List.of());rows.add(List.of());
            var inventory=new LiquidCellInventory(new LiquidCellInventory.Snapshot(rows),null);
            var routes=LiquidTransportRoutes.builder(3,5);
            for(var source:inventory.stocks())routes.source(source)
                    .route(0,2,255,SignedDyadic.of(1,-3)).route(1,3,255,SignedDyadic.of(1,-4));
            var accepted=routes.build();
            var cpu=inventory.planRoutes(accepted,null);
            long before=quantity.measurements().batches();
            var gpu=inventory.planRoutes(accepted,null,null,quantity);
            require(quantity.measurements().batches()==before+1,"Production plan did not batch GPU donor quantities");
            require(cpu.future().snapshot().rows().equals(gpu.future().snapshot().rows()),"Production liquid routes changed exact stock columns");
            require(cpu.transfers().equals(gpu.transfers()),"Production transported masses differ");
            gpu.commit();
            require(inventory.snapshot().rows().equals(cpu.future().snapshot().rows()),"Production quantity commit differs");
            System.out.println("GPU liquid quantities PASS | "+compute.deviceName()+" | "+quantity.measurements());
        }
    }
    private static LiquidCellInventory.Stock stock(int cell,int material,SignedDyadic volume,long mass){
        return new LiquidCellInventory.Stock(new LiquidCellInventory.Key(cell,material,255),volume,mass,null,mass*.25);
    }
    private static long[] allocate(long total,double[] fractions,int remainder){
        var result=new long[fractions.length];long remaining=total;
        for(int i=0;i<result.length;i++)if(i!=remainder){result[i]=Math.min(remaining,(long)(total*fractions[i]));remaining-=result[i];}
        result[remainder]=remaining;return result;
    }
    private static void require(boolean valid,String message){if(!valid)throw new AssertionError(message);}
}
