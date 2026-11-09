package mmm.gpu;

import mmm.chemistry.*;
import mmm.fluid.*;
import mmm.fluid.air.BuiltinGasDefinitions;
import mmm.granular.BuiltinGranularDefinitions;
import mmm.inventory.DeviceLiquidInventory;
import mmm.thermal.*;
import mmm.units.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/** A real concentration gradient, fixed wall and joint mass/energy/momentum settlement. */
public final class GpuLiquidDiffusionValidation {
    private GpuLiquidDiffusionValidation() {}
    private static void validateDataDrivenDefinitions() {
        var original = mmm.fluid.TestFluidDefinitions.load();
        var water = original.material().entry("mmm:water");
        var fluid = water.material();
        double density = fluid.referenceDensity() * 1.25;
        var changedFluid = new FluidMaterial(fluid.id(), density, fluid.miscibilityGroup(),
                fluid.surfaceTension(), fluid.contactAngleRadians(), fluid.displayArgb());
        var entries = original.material().entries().stream().map(entry ->
                entry.material().id().equals(fluid.id())
                        ? new MaterialRegistry.MaterialEntry(changedFluid, entry.viscosity(), entry.thermalExpansion())
                        : entry).toList();
        var material = new MaterialRegistry(entries, original.material().pairTensions());
        var sourceThermal = original.thermal();
        var thermalMaterials = sourceThermal.materials().stream().map(definition -> {
            boolean selected = definition.carrierKind() == ThermalMaterialDefinition.CarrierKind.LIQUID
                    && definition.carrierId().equals("mmm:water")
                    || definition.carrierKind() == ThermalMaterialDefinition.CarrierKind.GAS
                    && definition.carrierId().equals(BuiltinGasDefinitions.NITROGEN);
            if (!selected) return definition;
            var segments = definition.caloricSegments().stream().map(segment ->
                    new ThermalMaterialDefinition.CaloricSegment(segment.minimumTemperatureK(),
                            segment.maximumTemperatureK(), segment.specificHeatJPerKgK() * 1.25)).toList();
            return new ThermalMaterialDefinition(definition.id(), definition.carrierKind(), definition.carrierId(),
                    definition.referenceTemperatureK(), definition.referenceSpecificEnergyJPerKg(),
                    definition.conductivityWPerMeterK(), segments);
        }).toList();
        var thermal = new ThermalRegistry(thermalMaterials, sourceThermal.speciesOffsets(), sourceThermal.transitions(),
                sourceThermal.boundaries(), sourceThermal.finiteDevices(), original.chemistry().fingerprint(),
                original.granular().fingerprint(), original.gas().fingerprint());
        var definitions = mmm.fluid.TestFluidDefinitions.withRegistries(material, null, null, null, thermal);
        var domain = new HostFluidState(2, 2, 2, FluidSolverConfig.prototypeDefaults(), definitions);
        domain.setVolume(0, 0, 0, .5F);
        var state = domain.captureAuthoritativeState();
        long mass = state.liquid().rows().get(0).getFirst().massUnits();
        if (mass != Mass.fromKilograms(.5 * density))
            throw new AssertionError("Host water inventory ignored the world density");
        var device = new VulkanFluidStateCodec(state).importData();
        double liquidCp = definitions.thermal().material(definitions.thermal().materialHandle(
                ThermalMaterialDefinition.CarrierKind.LIQUID, "mmm:water")).caloricSegments().getFirst().specificHeatJPerKgK();
        if (device.definitions().thermal().capacity()[0] != liquidCp)
            throw new AssertionError("Device liquid caloric table ignored the world definition");
        var gasTables = device.gas().tables();
        int component = gasTables.definitions().componentIds().indexOf(BuiltinGasDefinitions.NITROGEN);
        double gasCp = definitions.thermal().material(definitions.thermal().materialHandle(
                ThermalMaterialDefinition.CarrierKind.GAS, BuiltinGasDefinitions.NITROGEN)).caloricSegments().getFirst().specificHeatJPerKgK();
        if (component < 0 || gasTables.segmentHeatCapacity()[gasTables.segmentOffsets()[component]] != gasCp)
            throw new AssertionError("Device gas caloric table ignored the world definition");
    }

    public static void main(String[] args){
        validateDataDrivenDefinitions();
        int device=args.length==0?0:Integer.parseInt(args[0]),side=4,cells=64;
        int a=21,b=22,blocked=25;
        var chemistry=mmm.fluid.TestFluidDefinitions.load().chemistry();
        var granular=mmm.fluid.TestFluidDefinitions.load().granular();var gas=mmm.fluid.TestFluidDefinitions.load().gas();
        var material=new ThermalMaterialDefinition("mmm:diffusion_water",ThermalMaterialDefinition.CarrierKind.LIQUID,
                "mmm:water",293.15,0,0.6,List.of(new ThermalMaterialDefinition.CaloricSegment(1,2000,4200)));
        var thermal=new ThermalRegistry(List.of(material),List.of(),List.of(),List.of(),chemistry.fingerprint(),granular.fingerprint(),gas.fingerprint());
        var fluids=new FluidMaterialRegistry(List.of(FluidMaterial.withSurfaceTension("mmm:water",1000,"mmm:aqueous",0,90, 0xff197ae8)));
        var tables=VulkanReactionDefinitions.fromRegistries(chemistry,granular,thermal,gas);
        int water=chemistry.speciesHandle(BuiltinChemicalDefinitions.H2O),ethanol=chemistry.speciesHandle(BuiltinChemicalDefinitions.ETHANOL);
        int[] cellOffsets=new int[cells+1];for(int i=0;i<cells;i++)cellOffsets[i+1]=cellOffsets[i]+(i==a||i==b||i==blocked?1:0);
        int[] handles={Math.min(water,ethanol),Math.max(water,ethanol),water,water};
        long[] amounts=water<ethanol?new long[]{55,10,55,55}:new long[]{10,55,55,55};
        long[] mass={17500,12900,12900},residual={3000,3000,3000};
        double ethanolMass=10*460e-10,initialU=0;long[] energy=new long[3];
        for(int i=0;i<3;i++){
            double kg=mass[i]*1e-10,cp=(kg-(i==0?ethanolMass:0))*4200+(i==0?ethanolMass:0)*2430;
            double value=cp*(310-293.15);energy[i]=Double.doubleToRawLongBits(value);initialU+=value;
        }
        var inventory=new DeviceLiquidInventory(cellOffsets,new int[]{0,0,0},new int[]{255,255,255},
                DeviceDyadicArray.encode(List.of(SignedDyadic.of(1),SignedDyadic.of(1),SignedDyadic.of(1))),mass,energy,new int[]{1,1,1},
                new int[]{0,2,3,4},handles,amounts,residual);
        int faceCount=(side+1)*side*side;double[] vx=new double[faceCount],vy=new double[faceCount],vz=new double[faceCount];
        double[] geometry=new double[3*faceCount*4];
        double[] cellMass=new double[cells];cellMass[a]=mass[0]*1e-10;cellMass[b]=mass[1]*1e-10;cellMass[blocked]=mass[2]*1e-10;
        for(int axis=0;axis<3;axis++)for(int z=0;z<side+(axis==2?1:0);z++)for(int y=0;y<side+(axis==1?1:0);y++)for(int x=0;x<side+(axis==0?1:0);x++){
            int[] pos={x,y,z};int id=faceIndex(side,axis,x,y,z),lo=cell(side,x-(axis==0?1:0),y-(axis==1?1:0),z-(axis==2?1:0)),hi=cell(side,x,y,z);
            double faceMass=.5*((lo<0?0:cellMass[lo])+(hi<0?0:cellMass[hi]));double velocity=axis==0?.1:axis==1?.05:-.03;
            geometry[4*id]=faceMass;geometry[4*id+1]=velocity;geometry[4*id+2]=1;
            if(axis==0&&lo==a&&hi==b)geometry[4*id+3]=1;
            (axis==0?vx:axis==1?vy:vz)[id-axis*faceCount]=faceMass*velocity;
        }
        var momentum=new MacMomentumState(side,side,side,vx,vy,vz);
        try(var context=new VulkanDeviceContext(device,true,true);var diffusion=new VulkanLiquidDiffusion(context);var caloricKernel=new VulkanLiquidThermodynamics(context)){
            long alignment=Math.max(8,context.storageOffsetAlignment());
            var liquid=new VulkanLiquidStateLayout(side,side,side,3,32,32,alignment,1);
            var definitions=tables.layout(liquid.bytes(),alignment);long ref=align(definitions.endOffset(),alignment),speciesRef=align(ref+8,alignment);
            var ethanolDefinition=chemistry.species(ethanol);
            var caloric=VulkanLiquidThermodynamics.layout(liquid,VulkanLiquidThermodynamics.tables(fluids,thermal).withAqueous(0,ethanol,
                    ethanolDefinition.molarMassKgPerMol().toDouble(),ethanolDefinition.liquidProperties().heatCapacityJPerKgK(),ethanolDefinition.liquidProperties().conductivityWPerMK()),Math.toIntExact(ref/4),
                    Math.toIntExact(speciesRef/4),chemistry.species().size(),speciesRef+8L*chemistry.species().size(),alignment);
            var progress=new VulkanReactionProgressLayout(cells,cells*32,0,definitions,caloric.endOffset(),alignment);
            long geometryOffset=align(progress.endOffset(),alignment);
            var rows=new VulkanLiquidPhaseRows.Layout(liquid,definitions,.001,geometryOffset+geometry.length*8L,alignment);
            var config=new VulkanRegionReactions.Configuration(tables,fluids,chemistry,VulkanReactionProgressLayout.State.empty(),0,false,thermal,gas);
            var layout=new VulkanLiquidDiffusion.Layout(config,rows,caloric,progress,Math.toIntExact(geometryOffset/4),.001,-1,-1,rows.endOffset,alignment);
            var bytes=ByteBuffer.allocate(Math.toIntExact(layout.endOffset)).order(ByteOrder.LITTLE_ENDIAN);
            liquid.importState(bytes,inventory,momentum);definitions.initialize(bytes);caloric.initializeHeaderAndTables(bytes);progress.initializeHeader(bytes);rows.initializeHeader(bytes);
            bytes.duplicate().position(Math.toIntExact(layout.column(VulkanLiquidDiffusion.Field.HEADER).offset())).put(layout.headerBytes(100));
            bytes.duplicate().position(Math.toIntExact(layout.column(VulkanLiquidDiffusion.Field.RULES).offset())).put(layout.ruleBytes.duplicate());
            for(int i=0;i<geometry.length;i++)bytes.putDouble(Math.toIntExact(geometryOffset+8L*i),geometry[i]);
            try(var storage=new VulkanRegionStorage(context,layout.endOffset,1024*1024,liquid.grid())){
                storage.initialize(bytes,0);
                try(var source=storage.capture();var candidate=storage.prepare(source)){
                    int init=candidate.beginBatch((command,input,output,errors,staging,stack)->VulkanRegionStorage.copy(command,input,0,output,0,layout.endOffset,stack),0,0).error();
                    if(init!=0)throw new AssertionError("Diffusion initial copy failed: "+init);
                    int status=diffusion.execute(candidate,layout,100);if(status!=0)throw new AssertionError("Diffusion failed: "+status);
                    if(candidate.finishBatch((command,input,output,errors,staging,stack)->{
                        caloricKernel.rebindRanges(new FlipKernelBindings.BufferRange(output),new FlipKernelBindings.BufferRange(errors));
                        caloricKernel.record(command,caloric);
                    })!=0)throw new AssertionError("Diffusion final batch failed");candidate.commit();
                }
                var actual=ByteBuffer.allocate(Math.toIntExact(liquid.bytes())).order(ByteOrder.LITTLE_ENDIAN);
                VulkanReactionProgressLayout.State pending;
                var cp=ByteBuffer.allocate(Math.toIntExact(caloric.heatCapacities().bytes())).order(ByteOrder.LITTLE_ENDIAN);
                var temperatures=ByteBuffer.allocate(Math.toIntExact(caloric.temperatures().bytes())).order(ByteOrder.LITTLE_ENDIAN);
                try(var capture=storage.capture()){
                    storage.read(capture,0,actual);storage.read(capture,caloric.heatCapacities().offset(),cp);
                    storage.read(capture,caloric.temperatures().offset(),temperatures);
                    pending=progress.export(column->{var buffer=ByteBuffer.allocate(Math.toIntExact(column.bytes())).order(ByteOrder.LITTLE_ENDIAN);
                        storage.read(capture,column.offset(),buffer);buffer.flip();return buffer;});
                }
                actual.flip();var finalState=liquid.exportState(actual);var after=finalState.inventory();
                int ethanolRule=tables.identity().rules().indexOf("mmm:diffusion/mmm/aqueous/mmm/ethanol/0");
                if(pending.pending().stream().noneMatch(value->value.cell()==a&&value.rule()==ethanolRule&&value.residualMoles()!=0))
                    throw new AssertionError("Fractional diffusion progress was not retained");
                long initialMass=Arrays.stream(mass).sum(),finalMass=Arrays.stream(after.massUnits()).sum();
                if(initialMass!=finalMass)throw new AssertionError("Diffusion did not conserve mass");
                for(int i=0;i<3;i++)if(!inventory.volumes().valueAt(i).equals(after.volumes().valueAt(i)))throw new AssertionError("Diffusion changed volume");
                if(after.massUnits()[2]!=mass[2])throw new AssertionError("Diffusion crossed the closed face");
                long[] first=species(after,0,water,ethanol),second=species(after,1,water,ethanol),third=species(after,2,water,ethanol);
                if(first[1]>=10||second[1]==0||third[1]!=0||first[1]+second[1]+third[1]!=10||first[0]+second[0]+third[0]!=165)
                    throw new AssertionError("Incorrect solvent/solute exchange "+Arrays.toString(first)+Arrays.toString(second)+Arrays.toString(third));
                for(int i=0;i<3;i++){
                    long[] q=species(after,i,water,ethanol);long expected=q[0]*180+q[1]*460+after.massResidualUnits()[i];
                    double kg=after.massUnits()[i]*1e-10,ethyl=q[1]*460e-10;
                    double expectedCp=(kg-ethyl)*4200+ethyl*2430;
                    int slot=i==0?a:i==1?b:blocked;
                    if(Math.abs(cp.getDouble(8*slot)-expectedCp)>1e-12*expectedCp)throw new AssertionError("Composition heat capacity differs row="+i+" actual="+cp.getDouble(8*slot)+" expected="+expectedCp+" species="+Arrays.toString(q)+" mass="+kg);
                    if(i==2&&Math.abs(temperatures.getDouble(8*slot)-310)>1e-8)throw new AssertionError("Closed-cell temperature changed");
                    if(expected!=after.massUnits()[i]||after.massResidualUnits()[i]!=residual[i])throw new AssertionError("Species mass and residual disagree");
                }
                double[] newCellMass=new double[cells];newCellMass[a]=after.massUnits()[0]*1e-10;newCellMass[b]=after.massUnits()[1]*1e-10;newCellMass[blocked]=after.massUnits()[2]*1e-10;
                double oldK=kinetic(side,cellMass,momentum),newK=kinetic(side,newCellMass,finalState.momentum());
                double internal=Arrays.stream(after.energyBits()).mapToDouble(Double::longBitsToDouble).sum();
                if(Math.abs(internal+newK-oldK-initialU)>1e-14)throw new AssertionError("Diffusion energy did not close: "+(internal+newK-oldK-initialU));
                for(int axis=0;axis<3;axis++)if(Math.abs(sum(momentum,axis)-sum(finalState.momentum(),axis))>1e-18)
                    throw new AssertionError("Diffusion changed total momentum");
                System.out.println("GPU diffusion passed: ethanol="+first[1]+","+second[1]+","+third[1]+" totalMass="+finalMass);
            }
            completeQuantumTransfer(context,fluids,chemistry,thermal,granular,gas,tables,water,false);
            completeQuantumTransfer(context,fluids,chemistry,thermal,granular,gas,tables,water,true);
        }
    }
    private static void completeQuantumTransfer(VulkanDeviceContext context,FluidMaterialRegistry fluids,ChemicalRegistry chemistry,
            ThermalRegistry thermal,mmm.granular.GranularRegistry granular,mmm.fluid.air.GasRegistry gas,
            VulkanReactionDefinitions.Tables tables,int water,boolean vertical){
        int side=2,cells=8,a=0,b=vertical?2:1,salt=chemistry.speciesHandle(BuiltinChemicalDefinitions.NACL);
        int[] cellOffsets=new int[cells+1];for(int i=0;i<cells;i++)cellOffsets[i+1]=cellOffsets[i]+(i==a||i==b?1:0);
        long saltMass=580,waterMass=180;double donorU=Math.nextUp(saltMass*1e-10*4200*(310-293.15));
        double waterU=waterMass*1e-10*4200*(310-293.15);
        var inventory=new DeviceLiquidInventory(cellOffsets,new int[]{0,0},new int[]{255,255},
                DeviceDyadicArray.encode(List.of(SignedDyadic.of(1),SignedDyadic.of(1))),new long[]{saltMass,waterMass},
                new long[]{Double.doubleToRawLongBits(donorU),Double.doubleToRawLongBits(waterU)},new int[]{1,1},
                new int[]{0,1,2},new int[]{salt,water},new long[]{1,1},new long[]{0,0});
        int axisFaces=(side+1)*side*side,totalFaces=3*axisFaces;double[] geometry=new double[4*totalFaces];
        double[] px=new double[axisFaces],py=new double[axisFaces],pz=new double[axisFaces],cellMass=new double[cells];
        cellMass[a]=saltMass*1e-10;cellMass[b]=waterMass*1e-10;
        for(int axis=0;axis<3;axis++)for(int z=0;z<side+(axis==2?1:0);z++)for(int y=0;y<side+(axis==1?1:0);y++)for(int x=0;x<side+(axis==0?1:0);x++){
            int id=faceIndex(side,axis,x,y,z),left=cell(side,x-(axis==0?1:0),y-(axis==1?1:0),z-(axis==2?1:0)),right=cell(side,x,y,z);
            double lm=left==a?saltMass*1e-10:left==b?waterMass*1e-10:0;
            double rm=right==a?saltMass*1e-10:right==b?waterMass*1e-10:0;
            double velocity=vertical?(axis==0?.1:axis==1?.05:-.03):0;
            geometry[4*id]=.5*(lm+rm);geometry[4*id+1]=velocity;geometry[4*id+2]=1;
            if(axis==(vertical?1:0)&&left==a&&right==b)geometry[4*id+3]=1;
            (axis==0?px:axis==1?py:pz)[id-axis*axisFaces]=geometry[4*id]*velocity;
        }
        var momentum=new MacMomentumState(side,side,side,px,py,pz);
        long alignment=Math.max(8,context.storageOffsetAlignment());
        var liquid=new VulkanLiquidStateLayout(side,side,side,2,32,32,alignment,1);
        var definitions=tables.layout(liquid.bytes(),alignment);long ref=align(definitions.endOffset(),alignment),speciesRef=align(ref+8,alignment);
        var caloric=VulkanLiquidThermodynamics.layout(liquid,VulkanLiquidThermodynamics.tables(fluids,thermal),Math.toIntExact(ref/4),
                Math.toIntExact(speciesRef/4),chemistry.species().size(),speciesRef+8L*chemistry.species().size(),alignment);
        var progress=new VulkanReactionProgressLayout(cells,cells*32,0,definitions,caloric.endOffset(),alignment);
        long geometryOffset=align(progress.endOffset(),alignment);
        var rows=new VulkanLiquidPhaseRows.Layout(liquid,definitions,.001,geometryOffset+geometry.length*8L,alignment);
        var config=new VulkanRegionReactions.Configuration(tables,fluids,chemistry,VulkanReactionProgressLayout.State.empty(),vertical?9.81:0,false,thermal,gas);
        var layout=new VulkanLiquidDiffusion.Layout(config,rows,caloric,progress,Math.toIntExact(geometryOffset/4),.001,-1,-1,rows.endOffset,alignment);
        var bytes=ByteBuffer.allocate(Math.toIntExact(layout.endOffset)).order(ByteOrder.LITTLE_ENDIAN);
        liquid.importState(bytes,inventory,momentum);definitions.initialize(bytes);caloric.initializeHeaderAndTables(bytes);progress.initializeHeader(bytes);rows.initializeHeader(bytes);
        bytes.duplicate().position(Math.toIntExact(layout.column(VulkanLiquidDiffusion.Field.HEADER).offset())).put(layout.headerBytes(1000));
        bytes.duplicate().position(Math.toIntExact(layout.column(VulkanLiquidDiffusion.Field.RULES).offset())).put(layout.ruleBytes.duplicate());
        for(int i=0;i<geometry.length;i++)bytes.putDouble(Math.toIntExact(geometryOffset+8L*i),geometry[i]);
        try(var diffusion=new VulkanLiquidDiffusion(context);var storage=new VulkanRegionStorage(context,layout.endOffset,1024*1024,liquid.grid())){
            storage.initialize(bytes,0);
            for(int step=0;step<3;step++)try(var source=storage.capture();var candidate=storage.prepare(source)){
                if(candidate.beginBatch((command,input,output,errors,staging,stack)->VulkanRegionStorage.copy(command,input,0,output,0,layout.endOffset,stack),0,0).error()!=0)
                    throw new AssertionError("Quantum diffusion copy failed");
                int status=diffusion.execute(candidate,layout,1000);if(status!=0)throw new AssertionError("Quantum diffusion failed: "+status);
                if(candidate.finishBatch((command,input,output,errors,staging,stack)->{})!=0)throw new AssertionError("Quantum diffusion publish failed");
                candidate.commit();
            }
            var actual=ByteBuffer.allocate(Math.toIntExact(liquid.bytes())).order(ByteOrder.LITTLE_ENDIAN);
            try(var capture=storage.capture()){storage.read(capture,0,actual);}actual.flip();var finalState=liquid.exportState(actual);var result=finalState.inventory();
            if(result.cellOffsets()[a]!=result.cellOffsets()[a+1])throw new AssertionError("Final solute quantum was not transferred");
            if(Arrays.stream(result.massUnits()).sum()!=saltMass+waterMass)throw new AssertionError("Quantum diffusion lost mass");
            double totalEnergy=Arrays.stream(result.energyBits()).mapToDouble(Double::longBitsToDouble).sum();
            double[] finalMass=new double[cells];finalMass[b]=(saltMass+waterMass)*1e-10;
            double oldK=kinetic(side,cellMass,momentum),newK=kinetic(side,finalMass,finalState.momentum());
            double potential=vertical?saltMass*1e-10*9.81*.001:0;
            if(Math.abs(totalEnergy+newK+potential-(donorU+waterU+oldK))>1e-15)
                throw new AssertionError("Quantum diffusion lost energy: "+(totalEnergy+newK+potential-donorU-waterU-oldK));
            System.out.println("GPU diffusion passed: complete solute quantum and energy, vertical="+vertical);
        }
    }
    private static long[] species(DeviceLiquidInventory inventory,int row,int water,int ethanol){
        long[] result=new long[2];for(int i=inventory.speciesOffsets()[row];i<inventory.speciesOffsets()[row+1];i++){
            int h=inventory.speciesHandles()[i];if(h==water)result[0]=inventory.speciesAmounts()[i];if(h==ethanol)result[1]=inventory.speciesAmounts()[i];}return result;
    }
    private static double sum(MacMomentumState state,int axis){return Arrays.stream(axis==0?state.copyX():axis==1?state.copyY():state.copyZ()).sum();}
    private static double kinetic(int n,double[] cellMass,MacMomentumState momentum){
        double result=0;for(int axis=0;axis<3;axis++){double[] p=axis==0?momentum.copyX():axis==1?momentum.copyY():momentum.copyZ();
            int count=(n+1)*n*n;
            for(int z=0;z<n+(axis==2?1:0);z++)for(int y=0;y<n+(axis==1?1:0);y++)for(int x=0;x<n+(axis==0?1:0);x++){
                int lo=cell(n,x-(axis==0?1:0),y-(axis==1?1:0),z-(axis==2?1:0)),hi=cell(n,x,y,z);
                double mass=.5*((lo<0?0:cellMass[lo])+(hi<0?0:cellMass[hi]));double value=p[faceIndex(n,axis,x,y,z)-axis*count];
                if(mass>0)result+=.5*value*value/mass;else if(value!=0)throw new AssertionError("Momentum without mass");
            }}return result;
    }
    private static int cell(int n,int x,int y,int z){return x<0||y<0||z<0||x>=n||y>=n||z>=n?-1:(z*n+y)*n+x;}
    private static int faceIndex(int n,int axis,int x,int y,int z){return mmm.spatial.SpatialGrid.rectangular(n,n,n).faceAt(x,y,z,axis);}
    private static long align(long value,long alignment){return (value+alignment-1)&-alignment;}
}
