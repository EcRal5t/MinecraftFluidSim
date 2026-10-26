package mmm.gpuprobe;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Locale;
import mmm.units.SignedDyadic;

/** Accepted cell volumes -> material routes, quantity shares, inventory/MAC and exact energy ledgers. */
public final class GpuLiquidGatherBenchmark {
    private GpuLiquidGatherBenchmark() { }
    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        int device=args.length>0?Integer.parseInt(args[0]):0;
        int samples=args.length>1?Integer.parseInt(args[1]):10;
        if(samples<1)throw new IllegalArgumentException("At least one sample required");
        try(var context=new VulkanDeviceContext(device,true,true);var materialRoutes=new VulkanLiquidMaterialRoutes(context);var quantities=new VulkanLiquidRouteQuantities(context);
            var gather=new VulkanLiquidGather(context);var momentum=new VulkanLiquidMomentum(context);var ledger=new VulkanEnergyLedger(context)) {
            System.out.println("Device: "+context.deviceName());
            System.out.println("Accepted cell volumes + material routes + quantities + gather + MAC + heat + exact energy ledgers: fixed resident input, half retained/half exchanged, two materials/species, different donor quantities and energy.");
            System.out.println("Timed candidates are discarded; one separate candidate is committed and sampled after timing. Excludes capacity acceptance, pressure, gas coupling and full steps. Zero reference-energy tables select the no-reference-energy fixture.");
            System.out.println("grid,source_stocks,output_stocks,median_ms,max_ms,gpu_mean_ms,upload_B_per_step,readback_B_per_step,device_MiB,staging_MiB");
            checkSignedReduction(context,ledger);
            checkTailBundle(context,materialRoutes,quantities,gather,ledger,false);
            checkTailBundle(context,materialRoutes,quantities,gather,ledger,true);
            for(int grid:new int[]{32,64})measure(context,materialRoutes,quantities,gather,momentum,ledger,grid,samples);
        }
    }

    /** Two subquantum materials share one real accepted quantum; the second case drains both. */
    private static void checkTailBundle(VulkanDeviceContext context,VulkanLiquidMaterialRoutes materials,
                                       VulkanLiquidRouteQuantities quantities,VulkanLiquidGather gather,
                                       VulkanEnergyLedger ledger,boolean drain){
        int cells=8,stocks=16;long alignment=Math.max(8,context.storageOffsetAlignment());
        var state=new VulkanLiquidStateLayout(2,2,2,stocks,stocks,4*stocks,alignment,2);
        var routes=new VulkanLiquidRouteLayout(stocks,7*stocks,state.bytes(),alignment);
        long fixed=routes.endOffset(),mass=fixed+4L*cells,materialReference=mass+16,speciesReference=materialReference+16;
        long accepted=speciesReference+32,phases=accepted+140L*cells,limb=phases+8;
        var material=VulkanLiquidMaterialRoutes.layout(state,routes,Math.toIntExact(accepted/4),Math.toIntExact(phases/4),0,-3,256,limb+8,alignment);
        var quantity=VulkanLiquidRouteQuantities.layout(state,routes,Math.toIntExact(materialReference/4),Math.toIntExact(speciesReference/4),2,material.endOffset(),alignment);
        var output=VulkanLiquidGather.layout(state,state,routes,Math.toIntExact(fixed/4),Math.toIntExact(mass/4),quantity.endOffset(),alignment);
        var roundoff=VulkanEnergyLedger.layout(java.util.List.of(stream(quantity.energyRemainders(),1),stream(output.energyRemainders(),-1)),output.endOffset(),alignment);
        var endpoint=VulkanEnergyLedger.layout(java.util.List.of(
                new VulkanEnergyLedger.Stream(0,Math.toIntExact(state.column(VulkanLiquidStateLayout.Field.ENERGY_BITS).offset()/4),stocks,2,-1),
                stream(state.column(VulkanLiquidStateLayout.Field.ENERGY_BITS),1)),roundoff.endOffset(),alignment);
        var initial=ByteBuffer.allocate(Math.toIntExact(endpoint.endOffset())).order(ByteOrder.LITTLE_ENDIAN);
        material.initializeHeader(initial);quantity.initializeHeader(initial);output.initializeHeader(initial);roundoff.initializeHeader(initial);endpoint.initializeHeader(initial);
        initial.putLong(Math.toIntExact(mass),1).putLong(Math.toIntExact(mass+8),1);
        initial.putInt(Math.toIntExact(phases),0).putInt(Math.toIntExact(phases+4),1).putInt(Math.toIntExact(limb),1);
        for(int cell=0;cell<cells;cell++)for(int slot=0;slot<7;slot++){
            int at=Math.toIntExact(accepted+20L*(7*cell+slot));boolean positive=cell==0&&(slot==2||slot==0&&!drain);
            initial.putInt(at,1).putInt(at+4,Math.toIntExact(limb/4)).putInt(at+8,positive?1:0)
                    .putInt(at+12,slot==0?-4:-3).putInt(at+16,positive?1:0);
        }
        put(initial,state,VulkanLiquidStateLayout.Field.COUNTS,0,stocks);put(initial,state,VulkanLiquidStateLayout.Field.COUNTS,1,2);put(initial,state,VulkanLiquidStateLayout.Field.COUNTS,2,4*stocks);
        for(int cell=0;cell<=cells;cell++)put(initial,state,VulkanLiquidStateLayout.Field.CELL_OFFSETS,cell,2*cell);
        for(int stock=0;stock<stocks;stock++){
            boolean present=stock<2;int numerator=stock==0?3:drain?1:3;long amount=present?4L*numerator:0;
            put(initial,state,VulkanLiquidStateLayout.Field.MATERIALS,stock,stock%2);
            put(initial,state,VulkanLiquidStateLayout.Field.SUPPORT_MASKS,stock,present?255:0);
            put(initial,state,VulkanLiquidStateLayout.Field.VOLUME_OFFSETS,stock,4*stock);
            put(initial,state,VulkanLiquidStateLayout.Field.VOLUME_COUNTS,stock,present?1:0);
            put(initial,state,VulkanLiquidStateLayout.Field.VOLUME_EXPONENTS,stock,present?-5:0);
            put(initial,state,VulkanLiquidStateLayout.Field.VOLUME_LIMBS,4*stock,present?numerator:0);
            putLong(initial,state.column(VulkanLiquidStateLayout.Field.MASS_UNITS),stock,amount);
            putLong(initial,state.column(VulkanLiquidStateLayout.Field.ENERGY_BITS),stock,Double.doubleToRawLongBits((double)amount));
            put(initial,state,VulkanLiquidStateLayout.Field.MIXTURE_PRESENT,stock,present?1:0);
            put(initial,state,VulkanLiquidStateLayout.Field.SPECIES_OFFSETS,stock,Math.min(stock,2));
            if(present){put(initial,state,VulkanLiquidStateLayout.Field.SPECIES_HANDLES,stock,stock);putLong(initial,state.column(VulkanLiquidStateLayout.Field.SPECIES_AMOUNTS),stock,amount);}
            putLong(initial,state.column(VulkanLiquidStateLayout.Field.NUMERIC_VOLUMES),stock,Double.doubleToRawLongBits(present?numerator/32.0:0.0));
        }
        put(initial,state,VulkanLiquidStateLayout.Field.VOLUME_OFFSETS,stocks,4*stocks);put(initial,state,VulkanLiquidStateLayout.Field.SPECIES_OFFSETS,stocks,2);
        try(var storage=new VulkanRegionStorage(context,initial.capacity(),1024*1024)){
            storage.initialize(initial,0);
            try(var source=storage.capture();var candidate=storage.prepare(source)){
                int error=candidate.execute(0,(command,input,result,errors,staging,stack)->{
                    var src=new FlipKernelBindings.BufferRange(input);var dst=new FlipKernelBindings.BufferRange(result);var err=new FlipKernelBindings.BufferRange(errors);
                    materials.rebindRanges(src,dst,err);materials.record(command,material);
                    quantities.rebindRanges(src,dst,err);quantities.record(command,quantity);
                    gather.rebindRanges(src,dst,err);gather.record(command,output);
                    ledger.rebindRanges(src,dst,err);ledger.record(command,roundoff);ledger.record(command,endpoint);
                });
                if(error!=0)throw new AssertionError("Material tail bundle status="+error+" drain="+drain);candidate.commit();
            }
            try(var result=storage.capture()){
                SignedDyadic[] expected={SignedDyadic.ZERO,drain?SignedDyadic.ZERO:SignedDyadic.of(1,-4),SignedDyadic.of(3,-5),SignedDyadic.of(1,-5)};
                long totalMass=0,totalSpecies=0;double totalEnergy=0;
                for(int stock=0;stock<stocks;stock++){
                    if(!readStockVolume(storage,result,state,stock).equals(stock<expected.length?expected[stock]:SignedDyadic.ZERO))throw new AssertionError("Material tail volume stock="+stock+" drain="+drain);
                    long massValue=read(storage,result,state.column(VulkanLiquidStateLayout.Field.MASS_UNITS).offset()+8L*stock,8).getLong(0);
                    double energyValue=read(storage,result,state.column(VulkanLiquidStateLayout.Field.ENERGY_BITS).offset()+8L*stock,8).getDouble(0);
                    if(massValue<0||!Double.isFinite(energyValue)||energyValue<0||stock>=4&&(massValue!=0||energyValue!=0))throw new AssertionError("Material tail empty/negative stock="+stock);
                    totalMass+=massValue;totalEnergy+=energyValue;
                }
                var offsets=read(storage,result,state.column(VulkanLiquidStateLayout.Field.SPECIES_OFFSETS).offset(),4*(stocks+1));int species=offsets.getInt(4*stocks);
                for(int stock=4;stock<stocks;stock++)if(offsets.getInt(4*stock)!=offsets.getInt(4*(stock+1)))throw new AssertionError("Material tail empty species stock="+stock);
                for(int entry=0;entry<species;entry++){
                    long amount=read(storage,result,state.column(VulkanLiquidStateLayout.Field.SPECIES_AMOUNTS).offset()+8L*entry,8).getLong(0);
                    if(amount<0)throw new AssertionError("Material tail negative species");totalSpecies+=amount;
                }
                long expectedMass=drain?16:24;
                if(totalMass!=expectedMass||totalSpecies!=expectedMass||totalEnergy!=expectedMass||!readDyadic(storage,result,roundoff.result().offset()).equals(readDyadic(storage,result,endpoint.result().offset())))
                    throw new AssertionError("Material tail inventory/energy differs drain="+drain);
            }
        }
        System.out.println("Material tail bundle: "+(drain?"complete drain":"two 0.75q sources -> one q transfer")+" passed");
    }
    private static SignedDyadic readStockVolume(VulkanRegionStorage storage,VulkanRegionStorage.Capture capture,VulkanLiquidStateLayout layout,int stock){
        int begin=read(storage,capture,layout.column(VulkanLiquidStateLayout.Field.VOLUME_OFFSETS).offset()+4L*stock,4).getInt(0);
        int count=read(storage,capture,layout.column(VulkanLiquidStateLayout.Field.VOLUME_COUNTS).offset()+4L*stock,4).getInt(0);
        int exponent=read(storage,capture,layout.column(VulkanLiquidStateLayout.Field.VOLUME_EXPONENTS).offset()+4L*stock,4).getInt(0);
        if(count==0)return SignedDyadic.ZERO;
        var limbs=read(storage,capture,layout.column(VulkanLiquidStateLayout.Field.VOLUME_LIMBS).offset()+4L*begin,4*count);BigInteger magnitude=BigInteger.ZERO;
        for(int i=count-1;i>=0;i--)magnitude=magnitude.shiftLeft(32).add(BigInteger.valueOf(Integer.toUnsignedLong(limbs.getInt(4*i))));
        return new SignedDyadic(magnitude,exponent);
    }

    private static void measure(VulkanDeviceContext context,VulkanLiquidMaterialRoutes materialRoutes,VulkanLiquidRouteQuantities quantities,VulkanLiquidGather gather,VulkanLiquidMomentum momentum,VulkanEnergyLedger ledger,int grid,int samples) {
        int cells=grid*grid*grid,stocks=2*cells;
        long alignment=Math.max(8,context.storageOffsetAlignment());
        var state=new VulkanLiquidStateLayout(grid,grid,grid,stocks,stocks,stocks,alignment,2);
        var routes=new VulkanLiquidRouteLayout(stocks,7*stocks,state.bytes(),alignment);
        long fixed=routes.endOffset(),mass=fixed+4L*cells,materialReference=mass+16,speciesReference=materialReference+16;
        long acceptedBase=speciesReference+32,phaseBase=acceptedBase+140L*cells,acceptedLimb=phaseBase+8;
        int scratchLimbs=Math.multiplyExact(7*stocks+3*cells,4);
        var materialLayout=VulkanLiquidMaterialRoutes.layout(state,routes,Math.toIntExact(acceptedBase/4),
                Math.toIntExact(phaseBase/4),-1,-40,scratchLimbs,acceptedLimb+8,alignment);
        System.out.println("material_route_capacity grid="+grid+" scratch_limbs="+scratchLimbs);
        var quantityLayout=VulkanLiquidRouteQuantities.layout(state,routes,Math.toIntExact(materialReference/4),
                Math.toIntExact(speciesReference/4),2,materialLayout.endOffset(),alignment);
        var layout=VulkanLiquidGather.layout(state,state,routes,Math.toIntExact(fixed/4),
                Math.toIntExact(mass/4),quantityLayout.endOffset(),alignment);
        int faces=state.column(VulkanLiquidStateLayout.Field.MOMENTUM_X).count()
                +state.column(VulkanLiquidStateLayout.Field.MOMENTUM_Y).count()+state.column(VulkanLiquidStateLayout.Field.MOMENTUM_Z).count();
        long faceBase=layout.endOffset();
        var momentumLayout=VulkanLiquidMomentum.layout(state,state,routes,Math.toIntExact(faceBase/4),
                Math.toIntExact(faceBase/4),-1,faceBase+32L*faces,alignment);
        var roundoffLayout=VulkanEnergyLedger.layout(java.util.List.of(
                stream(quantityLayout.energyRemainders(),1),stream(layout.energyRemainders(),-1),
                stream(momentumLayout.heatPostingRemainders(),1),stream(momentumLayout.heatAllocationRemainders(),1)),
                momentumLayout.endOffset(),alignment);
        var heatLayout=VulkanEnergyLedger.layout(java.util.List.of(stream(momentumLayout.cellHeat(),1)),roundoffLayout.endOffset(),alignment);
        var endpointLayout=VulkanEnergyLedger.layout(java.util.List.of(
                new VulkanEnergyLedger.Stream(0,Math.toIntExact(state.column(VulkanLiquidStateLayout.Field.ENERGY_BITS).offset()/4),stocks,2,-1),
                stream(state.column(VulkanLiquidStateLayout.Field.ENERGY_BITS),1),stream(momentumLayout.cellHeat(),-1)),heatLayout.endOffset(),alignment);
        int bytes=Math.toIntExact(endpointLayout.endOffset());
        if(bytes>context.maxStorageBufferRange())throw new IllegalArgumentException("Gather arena exceeds device storage descriptor range");
        var initial=ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
        layout.initializeHeader(initial);
        materialLayout.initializeHeader(initial);
        initial.putInt(Math.toIntExact(phaseBase),0).putInt(Math.toIntExact(phaseBase+4),1);
        initial.putInt(Math.toIntExact(acceptedLimb),1);
        for(int cell=0;cell<cells;cell++) {
            int moving=(cell%grid)%2==0?2:1;
            for(int slot=0;slot<7;slot++) {
                int at=Math.toIntExact(acceptedBase+20L*(7*cell+slot));
                boolean active=slot==0||slot==moving;
                initial.putInt(at,1).putInt(at+4,Math.toIntExact(acceptedLimb/4)).putInt(at+8,active?1:0)
                        .putInt(at+12,-3).putInt(at+16,active?1:0);
            }
        }
        quantityLayout.initializeHeader(initial);
        momentumLayout.initializeHeader(initial);
        roundoffLayout.initializeHeader(initial);heatLayout.initializeHeader(initial);endpointLayout.initializeHeader(initial);
        int face=0;
        for(int axis=0;axis<3;axis++) {
            int nx=grid+(axis==0?1:0),ny=grid+(axis==1?1:0),nz=grid+(axis==2?1:0);
            for(int z=0;z<nz;z++)for(int y=0;y<ny;y++)for(int x=0;x<nx;x++,face++) {
                int along=axis==0?x:axis==1?y:z;boolean interior=along>0&&along<grid;
                int at=Math.toIntExact(faceBase+32L*face);
                initial.putDouble(at,interior?3e-8:1.5e-8).putDouble(at+8,interior?.1*(axis+1)+.001*x:0)
                        .putDouble(at+16,interior?1:0).putDouble(at+24,interior?1:0);
            }
        }
        initial.putLong(Math.toIntExact(mass),1).putLong(Math.toIntExact(mass+8),1);
        put(initial,state,VulkanLiquidStateLayout.Field.COUNTS,0,stocks);
        put(initial,state,VulkanLiquidStateLayout.Field.COUNTS,1,stocks);
        put(initial,state,VulkanLiquidStateLayout.Field.COUNTS,2,4*stocks);
        for(int cell=0;cell<=cells;cell++)put(initial,state,VulkanLiquidStateLayout.Field.CELL_OFFSETS,cell,2*cell);
        for(int stock=0;stock<stocks;stock++) {
            int material=stock%2,cell=stock/2;
            boolean even=(cell%grid)%2==0;
            put(initial,state,VulkanLiquidStateLayout.Field.MATERIALS,stock,material);
            put(initial,state,VulkanLiquidStateLayout.Field.SUPPORT_MASKS,stock,255);
            put(initial,state,VulkanLiquidStateLayout.Field.VOLUME_OFFSETS,stock,4*stock);
            put(initial,state,VulkanLiquidStateLayout.Field.VOLUME_COUNTS,stock,1);
            put(initial,state,VulkanLiquidStateLayout.Field.VOLUME_EXPONENTS,stock,-3);
            put(initial,state,VulkanLiquidStateLayout.Field.VOLUME_LIMBS,4*stock,1);
            putLong(initial,state.column(VulkanLiquidStateLayout.Field.MASS_UNITS),stock,even?100:200);
            putLong(initial,state.column(VulkanLiquidStateLayout.Field.ENERGY_BITS),stock,Double.doubleToRawLongBits(even?8:24));
            put(initial,state,VulkanLiquidStateLayout.Field.MIXTURE_PRESENT,stock,1);
            put(initial,state,VulkanLiquidStateLayout.Field.SPECIES_OFFSETS,stock,stock);
            put(initial,state,VulkanLiquidStateLayout.Field.SPECIES_HANDLES,stock,material);
            putLong(initial,state.column(VulkanLiquidStateLayout.Field.SPECIES_AMOUNTS),stock,even?80:160);
            putLong(initial,state.column(VulkanLiquidStateLayout.Field.MASS_RESIDUAL_UNITS),stock,even?20:40);
            putLong(initial,state.column(VulkanLiquidStateLayout.Field.NUMERIC_VOLUMES),stock,Double.doubleToRawLongBits(.125));
        }
        put(initial,state,VulkanLiquidStateLayout.Field.VOLUME_OFFSETS,stocks,4*stocks);
        put(initial,state,VulkanLiquidStateLayout.Field.SPECIES_OFFSETS,stocks,stocks);
        try(var storage=new VulkanRegionStorage(context,bytes,1024*1024)) {
            storage.initialize(initial,0);
            try(var source=storage.capture()) {
                for(int i=0;i<5;i++)advance(storage,source,materialRoutes,materialLayout,quantities,quantityLayout,gather,layout,momentum,momentumLayout,ledger,roundoffLayout,heatLayout,endpointLayout,false);
                var before=storage.metrics();long[] nanos=new long[samples];
                for(int i=0;i<samples;i++) {
                    long start=System.nanoTime();advance(storage,source,materialRoutes,materialLayout,quantities,quantityLayout,gather,layout,momentum,momentumLayout,ledger,roundoffLayout,heatLayout,endpointLayout,false);nanos[i]=System.nanoTime()-start;
                }
                var after=storage.metrics();Arrays.sort(nanos);
                System.out.printf("%d,%d,%d,%.3f,%.3f,%.3f,%d,%d,%.1f,%.1f%n",grid,stocks,stocks,
                        nanos[samples/2]*1e-6,nanos[samples-1]*1e-6,(after.gpuNanos()-before.gpuNanos())*1e-6/samples,
                        (after.uploadBytes()-before.uploadBytes())/samples,(after.readbackBytes()-before.readbackBytes())/samples,
                        after.deviceBytes()/1048576.0,after.stagingBytes()/1048576.0);
                advance(storage,source,materialRoutes,materialLayout,quantities,quantityLayout,gather,layout,momentum,momentumLayout,ledger,roundoffLayout,heatLayout,endpointLayout,true);
            }
            try(var result=storage.capture()) {
                int requiredScratch=read(storage,result,materialLayout.reserveOffsets().offset()+4L*materialLayout.windowCount(),4).getInt(0);
                int candidates=read(storage,result,routes.column(VulkanLiquidRouteLayout.Field.COUNTS).offset(),4).getInt(0);
                System.out.println("material_route_required grid="+grid+" scratch_limbs="+requiredScratch+" candidates="+candidates);
                var roundoff=readDyadic(storage,result,roundoffLayout.result().offset());
                var endpoint=readDyadic(storage,result,endpointLayout.result().offset());
                if(!roundoff.equals(endpoint))throw new AssertionError("Exact device energy ledger differs from endpoints: "+roundoff+" != "+endpoint);
                if(readDyadic(storage,result,heatLayout.result().offset()).signum()<0)throw new AssertionError("Negative mechanical heat ledger");
                var counts=read(storage,result,state.column(VulkanLiquidStateLayout.Field.COUNTS).offset(),12);
                if(counts.getInt(0)!=stocks||counts.getInt(4)!=stocks)
                    throw new AssertionError("Gather logical counts differ: "+counts.getInt(0)+","+counts.getInt(4)+","+counts.getInt(8));
                for(int cell:new int[]{0,grid+1,cells-1}) {
                    double heat=read(storage,result,momentumLayout.cellHeat().offset()+8L*cell,8).getDouble(0);
                    if(!Double.isFinite(heat)||heat<0)throw new AssertionError("Invalid resident cell heat at "+cell);
                    var allocation=read(storage,result,momentumLayout.heatAllocationRemainders().offset()+24L*cell,24);
                    for(int j=0;j<3;j++)if(!Double.isFinite(allocation.getDouble(8*j)))
                        throw new AssertionError("Nonfinite heat allocation roundoff");
                    var offset=read(storage,result,state.column(VulkanLiquidStateLayout.Field.CELL_OFFSETS).offset()+4L*cell,8);
                    int start=offset.getInt(0),end=offset.getInt(4),expected=2;
                    if(end-start!=expected)throw new AssertionError("Gather cell stock count differs at "+cell);
                    for(int stock=start;stock<end;stock++)checkStock(storage,result,state,stock,stock-start,heat,momentumLayout);
                }
                for(var field:new VulkanLiquidStateLayout.Field[]{VulkanLiquidStateLayout.Field.MOMENTUM_X,
                        VulkanLiquidStateLayout.Field.MOMENTUM_Y,VulkanLiquidStateLayout.Field.MOMENTUM_Z}) {
                    var column=state.column(field);
                    for(int index:new int[]{0,column.count()/2,column.count()-1}) {
                        double value=read(storage,result,column.offset()+8L*index,8).getDouble(0);
                        if(!Double.isFinite(value))throw new AssertionError("Nonfinite MAC momentum");
                    }
                }
                for(int index:new int[]{0,faces/2,faces-1}) {
                    double heat=read(storage,result,momentumLayout.faceHeat().offset()+8L*index,8).getDouble(0);
                    if(!Double.isFinite(heat)||heat<0)throw new AssertionError("Invalid resident face heat");
                }
                System.out.println("checkpoint grid="+grid+" counts and three cells: exact volume, material, mass, species, residual and posted energy; finite MAC, heat and exact endpoint energy ledger passed");
            }
        }
    }

    private static void advance(VulkanRegionStorage storage,VulkanRegionStorage.Capture source,
                                VulkanLiquidMaterialRoutes materialRoutes,VulkanLiquidMaterialRoutes.Layout materialLayout,
                                VulkanLiquidRouteQuantities quantities,VulkanLiquidRouteQuantities.Layout quantityLayout,
                                VulkanLiquidGather gather,VulkanLiquidGather.Layout layout,VulkanLiquidMomentum momentum,
                                VulkanLiquidMomentum.Layout momentumLayout,VulkanEnergyLedger ledger,VulkanEnergyLedger.Layout roundoffLayout,
                                VulkanEnergyLedger.Layout heatLayout,VulkanEnergyLedger.Layout endpointLayout,boolean commit) {
        try(var candidate=storage.prepare(source)) {
            int error=candidate.execute(0,(command,input,output,errors,staging,stack)-> {
                materialRoutes.rebindRanges(new FlipKernelBindings.BufferRange(input),new FlipKernelBindings.BufferRange(output),new FlipKernelBindings.BufferRange(errors));
                materialRoutes.record(command,materialLayout);
                quantities.rebindRanges(new FlipKernelBindings.BufferRange(input),new FlipKernelBindings.BufferRange(output),new FlipKernelBindings.BufferRange(errors));
                quantities.record(command,quantityLayout);
                gather.rebindRanges(new FlipKernelBindings.BufferRange(input),new FlipKernelBindings.BufferRange(output),new FlipKernelBindings.BufferRange(errors));
                gather.record(command,layout);
                momentum.rebindRanges(new FlipKernelBindings.BufferRange(input),new FlipKernelBindings.BufferRange(output),new FlipKernelBindings.BufferRange(errors));
                momentum.recordTransport(command,momentumLayout);
                momentum.recordResolve(command,momentumLayout);
                momentum.recordHeatPosting(command,momentumLayout);
                ledger.rebindRanges(new FlipKernelBindings.BufferRange(input),new FlipKernelBindings.BufferRange(output),new FlipKernelBindings.BufferRange(errors));
                ledger.record(command,roundoffLayout);ledger.record(command,heatLayout);
                if(commit)ledger.record(command,endpointLayout);
            });
            if(error!=0)throw new IllegalStateException("Resident liquid gather failed: status="+error);
            if(commit)candidate.commit();
        }
    }
    private static void checkStock(VulkanRegionStorage storage,VulkanRegionStorage.Capture capture,VulkanLiquidStateLayout layout,int stock,int material,double cellHeat,VulkanLiquidMomentum.Layout momentumLayout) {
        var mass=read(storage,capture,layout.column(VulkanLiquidStateLayout.Field.MASS_UNITS).offset()+8L*stock,8);
        var mat=read(storage,capture,layout.column(VulkanLiquidStateLayout.Field.MATERIALS).offset()+4L*stock,4);
        var species=read(storage,capture,layout.column(VulkanLiquidStateLayout.Field.SPECIES_OFFSETS).offset()+4L*stock,8);
        int first=species.getInt(0);
        var handle=read(storage,capture,layout.column(VulkanLiquidStateLayout.Field.SPECIES_HANDLES).offset()+4L*first,4);
        var amount=read(storage,capture,layout.column(VulkanLiquidStateLayout.Field.SPECIES_AMOUNTS).offset()+8L*first,8);
        var offsets=read(storage,capture,layout.column(VulkanLiquidStateLayout.Field.VOLUME_OFFSETS).offset()+4L*stock,8);
        int begin=offsets.getInt(0);
        int count=read(storage,capture,layout.column(VulkanLiquidStateLayout.Field.VOLUME_COUNTS).offset()+4L*stock,4).getInt(0),end=begin+count;
        var limbs=read(storage,capture,layout.column(VulkanLiquidStateLayout.Field.VOLUME_LIMBS).offset()+4L*begin,4*(end-begin));
        var sign=read(storage,capture,layout.column(VulkanLiquidStateLayout.Field.VOLUME_COUNTS).offset()+4L*stock,4);
        var exponent=read(storage,capture,layout.column(VulkanLiquidStateLayout.Field.VOLUME_EXPONENTS).offset()+4L*stock,4);
        BigInteger magnitude=BigInteger.ZERO;
        for(int i=end-begin-1;i>=0;i--)magnitude=magnitude.shiftLeft(32).add(BigInteger.valueOf(Integer.toUnsignedLong(limbs.getInt(4*i))));
        var volume=new SignedDyadic(sign.getInt(0)<0?magnitude.negate():magnitude,exponent.getInt(0));
        var residual=read(storage,capture,layout.column(VulkanLiquidStateLayout.Field.MASS_RESIDUAL_UNITS).offset()+8L*stock,8);
        var energy=read(storage,capture,layout.column(VulkanLiquidStateLayout.Field.ENERGY_BITS).offset()+8L*stock,8);
        double expectedEnergy=16+cellHeat*.5,actualEnergy=energy.getDouble(0);
        var roundoff=read(storage,capture,momentumLayout.heatPostingRemainders().offset()+16L*stock,16);
        if(!Double.isFinite(roundoff.getDouble(0))||!Double.isFinite(roundoff.getDouble(8)))
            throw new AssertionError("Nonfinite heat posting roundoff");
        if(mat.getInt(0)!=material||mass.getLong(0)!=150||species.getInt(4)!=first+1||handle.getInt(0)!=material
                ||amount.getLong(0)!=120||residual.getLong(0)!=30||!Double.isFinite(actualEnergy)||Math.abs(actualEnergy-expectedEnergy)>4*Math.ulp(expectedEnergy)
                ||!volume.equals(SignedDyadic.of(1,-3)))
            throw new AssertionError("Gather stock differs at "+stock+" volume="+volume);
    }
    private static VulkanEnergyLedger.Stream stream(VulkanLiquidStateLayout.Column column,int coefficient) {
        return new VulkanEnergyLedger.Stream(1,Math.toIntExact(column.offset()/4),column.count(),2,coefficient);
    }
    private static SignedDyadic readDyadic(VulkanRegionStorage storage,VulkanRegionStorage.Capture capture,long offset) {
        var data=read(storage,capture,offset,4*(68+4));int count=data.getInt(8);BigInteger magnitude=BigInteger.ZERO;
        for(int i=count-1;i>=0;i--)magnitude=magnitude.shiftLeft(32).add(BigInteger.valueOf(Integer.toUnsignedLong(data.getInt(16+4*i))));
        return new SignedDyadic(data.getInt(0)<0?magnitude.negate():magnitude,data.getInt(4));
    }
    private static void checkSignedReduction(VulkanDeviceContext context,VulkanEnergyLedger ledger) {
        int terms=2049;long alignment=Math.max(8,context.storageOffsetAlignment());
        var positive=VulkanEnergyLedger.layout(java.util.List.of(new VulkanEnergyLedger.Stream(0,0,terms,2,1)),8L*terms,alignment);
        var negative=VulkanEnergyLedger.layout(java.util.List.of(new VulkanEnergyLedger.Stream(0,0,terms,2,-1)),positive.endOffset(),alignment);
        var initial=ByteBuffer.allocate(Math.toIntExact(negative.endOffset())).order(ByteOrder.LITTLE_ENDIAN);
        initial.putDouble(0,Double.MAX_VALUE).putDouble(8*1023,Double.MAX_VALUE)
                .putDouble(8*1024,-Double.MAX_VALUE).putDouble(8*2047,-Double.MAX_VALUE).putDouble(8*2048,Double.MIN_VALUE);
        positive.initializeHeader(initial);negative.initializeHeader(initial);
        try(var storage=new VulkanRegionStorage(context,initial.capacity(),1024*1024)) {
            storage.initialize(initial,0);
            try(var source=storage.capture();var candidate=storage.prepare(source)) {
                int error=candidate.execute(0,(command,input,output,errors,staging,stack)->{
                    ledger.rebindRanges(new FlipKernelBindings.BufferRange(input),new FlipKernelBindings.BufferRange(output),new FlipKernelBindings.BufferRange(errors));
                    ledger.record(command,positive);ledger.record(command,negative);
                });
                if(error!=0)throw new AssertionError("Signed energy reduction status="+error);candidate.commit();
            }
            try(var capture=storage.capture()) {
                if(!readDyadic(storage,capture,positive.result().offset()).equals(SignedDyadic.of(Double.MIN_VALUE))
                        ||!readDyadic(storage,capture,negative.result().offset()).equals(SignedDyadic.of(-Double.MIN_VALUE)))
                    throw new AssertionError("Exact energy reduction lost subnormal after large cancellation");
            }
            try(var source=storage.capture();var candidate=storage.prepare(source)){
                int error=candidate.execute(0,(command,input,output,errors,staging,stack)->{
                    ledger.rebindRanges(new FlipKernelBindings.BufferRange(input),new FlipKernelBindings.BufferRange(output),new FlipKernelBindings.BufferRange(errors));
                    ledger.record(command,positive);ledger.recordAccumulate(command,positive,Math.toIntExact(positive.result().offset()/4));
                    ledger.record(command,negative);ledger.recordAccumulate(command,negative,Math.toIntExact(positive.result().offset()/4));
                });
                if(error!=0)throw new AssertionError("Private substep ledger accumulation status="+error);candidate.commit();
            }
            try(var capture=storage.capture()){
                if(!readDyadic(storage,capture,positive.result().offset()).equals(SignedDyadic.of(Double.MIN_VALUE).multiply(SignedDyadic.of(2.0)))
                        ||!readDyadic(storage,capture,negative.result().offset()).equals(SignedDyadic.ZERO))
                    throw new AssertionError("Private substep ledger accumulation lost exact addition or cancellation");
            }
        }
        System.out.println("Exact signed energy reduction: cross-block cancellation and subnormal remainder passed");
    }
    private static ByteBuffer read(VulkanRegionStorage storage,VulkanRegionStorage.Capture capture,long offset,int bytes) {
        var result=ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);storage.read(capture,offset,result);return result;
    }
    private static void put(ByteBuffer out,VulkanLiquidStateLayout layout,VulkanLiquidStateLayout.Field field,int index,int value) {put(out,layout.column(field),index,value);}
    private static void put(ByteBuffer out,VulkanLiquidStateLayout.Column column,int index,int value) {out.putInt(Math.toIntExact(column.offset()+4L*index),value);}
    private static void putLong(ByteBuffer out,VulkanLiquidStateLayout.Column column,int index,long value) {out.putLong(Math.toIntExact(column.offset()+8L*index),value);}
}
