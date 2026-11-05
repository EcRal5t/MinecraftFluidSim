package mmm.gpu;

import org.lwjgl.vulkan.VkCommandBuffer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Closes actual force K, transported PE and gas compression work on connected caloric bodies. */
final class VulkanMechanicalEnergy implements AutoCloseable {
    private enum Header {
        SIZE_X(0), SIZE_Y(1), SIZE_Z(2), CELLS(3),
        VERTICES(4), FACES(5), SOURCE_STOCK_CAPACITY(6), TARGET_STOCK_CAPACITY(7),
        PROJECTED_FACES(8), CELL_MASS(9), ROUTE_MASS(10), GAS_WORK(11),
        BODY_PARENTS(12), BODY_OFFSETS(13), BODY_MEMBERS(14), BODY_COUNTS(15),
        HEAT_CAPACITY(16), COLD_ENERGY(17), SOURCE_HEAT(18), EXTERNAL_WORK(19),
        FACE_TERMS(20), POTENTIAL_TERMS(21), VERTEX_DATA(22), BLOCK_COUNTS(23),
        BLOCK_OFFSETS(24), BLOCK_ROOTS(25), PARTIALS(26), BODY_DATA(27),
        ENERGY_CHANGES(28), DISPATCH(29), SOURCE_FIELDS(40), TARGET_FIELDS(64),
        GAS_CAPACITY(83), PARTICLE_CAPACITY(84), GRAIN_COUNTS(85), GRAIN_HEAT_CAPACITY(86),
        GRAIN_COLD_ENERGY(87), GRAIN_ENERGY(88), SOURCE_K(89), FINAL_K(90),
        SOURCE_Y(91), FINAL_Y(92), GRAIN_MASS(93), GRAIN_TERMS(94),
        GRAIN_CHANGES(95), GRAIN_EXTERNAL_WORK(96), GRAIN_SOURCE_HEAT(97), DEPOSIT_CAPACITY(98),
        GRAIN_OFFSETS(99), GRAIN_ENTRIES(100), DEPOSIT_CELL(101), SOURCE_FACE_K(102),
        GAS_EXTERNAL_WORK(103), GAS_ENERGY(104), GAS_HEAT_CAPACITY(105), GAS_COLD_ENERGY(106),
        GAS_ENERGY_CHANGES(107), GAS_COUNTS(108), ROUTE_OFFSETS(109), ROUTE_MASKS(110),
        ACTIVITY_CELLS(111), ACTIVITY_FACES(112), SOURCE_ELASTIC(113), TARGET_ELASTIC(114), GAS_STATES(115), GAS_POTENTIAL(116), GAS_ROUTE_MASS(117), GAS_COMPONENTS(118), GAS_EOS(119);
        final int word; Header(int word) { this.word = word; }
    }
    private static String shaderLayout() {
        var result = new StringBuilder();
        for (var field : Header.values()) result.append("#define H_").append(field.name()).append(' ').append(field.word).append('\n');
        return result.toString();
    }

    record GranularEnergy(VulkanLiquidThermodynamics.Layout thermal,VulkanGranularGeometry.Layout geometry,
                          int sourceHeatWordOffset,int externalWorkWordOffset) { }
    record LocalMixture(VulkanLiquidStateLayout.Column source,VulkanMixtureState.Layout target,
                        VulkanLocalGasTransport.ProductionLayout transport,VulkanGasThermodynamics.Layout eos,
                        VulkanLiquidStateLayout.Column energyChanges,VulkanLiquidStateLayout.Column potential) { }
    record Layout(VulkanMechanicalBodies.Layout bodies,VulkanLiquidMomentum.Layout momentum,
                  VulkanLiquidThermodynamics.Layout thermal,int gasWorkWordOffset,int sourceHeatWordOffset,int externalWorkWordOffset,
                  GranularEnergy granular,
                  VulkanLiquidStateLayout.Column header,VulkanLiquidStateLayout.Column faceTerms,
                  VulkanLiquidStateLayout.Column potentialTerms,VulkanLiquidStateLayout.Column vertexData,
                  VulkanLiquidStateLayout.Column blockCounts,VulkanLiquidStateLayout.Column blockOffsets,
                  VulkanLiquidStateLayout.Column blockRoots,VulkanLiquidStateLayout.Column partials,
                  VulkanLiquidStateLayout.Column bodyData,VulkanLiquidStateLayout.Column energyChanges,
                  VulkanLiquidStateLayout.Column dispatch,VulkanLiquidStateLayout.Column scanWorkspace,
                  VulkanLiquidStateLayout.Column granularTerms,VulkanLiquidStateLayout.Column granularChanges,int sourceFaceKWordOffset,int gasExternalWorkWordOffset,LocalMixture gasEnergy,long endOffset) {
        Layout withSourceFaceK(int word){return new Layout(bodies,momentum,thermal,gasWorkWordOffset,sourceHeatWordOffset,externalWorkWordOffset,granular,header,faceTerms,potentialTerms,vertexData,blockCounts,blockOffsets,blockRoots,partials,bodyData,energyChanges,dispatch,scanWorkspace,granularTerms,granularChanges,word,gasExternalWorkWordOffset,gasEnergy,endOffset);}
        Layout withLocalMixture(VulkanLiquidStateLayout.Column source,VulkanMixtureState.Layout target,
                                VulkanLocalGasTransport.ProductionLayout transport,VulkanGasThermodynamics.Layout eos){
            long alignment=momentum.source().alignment(),offset=align(endOffset,alignment);
            var changes=new VulkanLiquidStateLayout.Column(offset,transport==null?0:2*transport.cells,8);
            var potential=new VulkanLiquidStateLayout.Column(align(offset+changes.bindingBytes(),alignment),transport==null?0:2*transport.cells,8);
            var local=new LocalMixture(source,target,transport,eos,changes,potential);
            return new Layout(bodies,momentum,thermal,transport==null?-1:transport.word(VulkanLocalGasTransport.Field.WORK),sourceHeatWordOffset,externalWorkWordOffset,
                    granular,header,faceTerms,potentialTerms,vertexData,blockCounts,blockOffsets,blockRoots,partials,bodyData,energyChanges,dispatch,scanWorkspace,
                    granularTerms,granularChanges,sourceFaceKWordOffset,transport==null?-1:transport.word(VulkanLocalGasTransport.Field.EXTERNAL_PRESSURE_WORK),local,align(potential.offset()+potential.bindingBytes(),alignment));
        }
        ByteBuffer headerBytes(){return headerBytes(momentum.source());}
        ByteBuffer headerBytes(VulkanLiquidStateLayout source) {
            var out=ByteBuffer.allocateDirect(512).order(ByteOrder.LITTLE_ENDIAN);int[] data=new int[128];
            var target=thermal.liquid();data[Header.SIZE_X.word]=source.sizeX();data[Header.SIZE_Y.word]=source.sizeY();data[Header.SIZE_Z.word]=source.sizeZ();
            data[Header.CELLS.word]=source.cellCount();data[Header.VERTICES.word]=bodies.vertices();data[Header.FACES.word]=bodies.faces();data[Header.SOURCE_STOCK_CAPACITY.word]=source.stockCapacity();data[Header.TARGET_STOCK_CAPACITY.word]=target.stockCapacity();
            data[Header.PROJECTED_FACES.word]=momentum.projectedFacesWordOffset();data[Header.CELL_MASS.word]=word(momentum.cellMasses());data[Header.ROUTE_MASS.word]=word(momentum.routes().column(VulkanLiquidRouteLayout.Field.MASS_UNITS));
            data[Header.GAS_WORK.word]=gasWorkWordOffset;data[Header.BODY_PARENTS.word]=word(bodies.parents());data[Header.BODY_OFFSETS.word]=word(bodies.offsets());data[Header.BODY_MEMBERS.word]=word(bodies.members());data[Header.BODY_COUNTS.word]=word(bodies.counts());
            data[Header.HEAT_CAPACITY.word]=word(thermal.heatCapacities());data[Header.COLD_ENERGY.word]=word(thermal.coldEnergies());
            data[Header.SOURCE_HEAT.word]=sourceHeatWordOffset;data[Header.EXTERNAL_WORK.word]=externalWorkWordOffset;
            var fields=List.of(faceTerms,potentialTerms,vertexData,blockCounts,blockOffsets,blockRoots,partials,bodyData,energyChanges,dispatch);
            for(int i=0;i<fields.size();i++)data[Header.FACE_TERMS.word+i]=word(fields.get(i));
            for(var field:VulkanLiquidStateLayout.Field.values()){
                data[Header.SOURCE_FIELDS.word+field.ordinal()]=word(source.column(field));data[Header.TARGET_FIELDS.word+field.ordinal()]=word(target.column(field));
            }
            data[Header.GAS_CAPACITY.word]=bodies.gasNodeCapacity();
            if(granular!=null){var state=bodies.granular().state();var pressure=bodies.granular().pressure();
                data[Header.PARTICLE_CAPACITY.word]=state.particleCapacity();data[Header.GRAIN_COUNTS.word]=state.word(VulkanGranularStateLayout.Field.COUNTS);
                data[Header.GRAIN_HEAT_CAPACITY.word]=word(granular.thermal().heatCapacities());data[Header.GRAIN_COLD_ENERGY.word]=word(granular.thermal().coldEnergies());
                data[Header.GRAIN_ENERGY.word]=state.word(VulkanGranularStateLayout.Field.INTERNAL_ENERGY);
                data[Header.SOURCE_K.word]=pressure.word(VulkanMixturePressure.Field.SOURCE_K);data[Header.FINAL_K.word]=pressure.word(VulkanMixturePressure.Field.FINAL_K);
                data[Header.SOURCE_Y.word]=pressure.word(VulkanMixturePressure.Field.SOURCE_Y);data[Header.FINAL_Y.word]=pressure.word(VulkanMixturePressure.Field.FINAL_Y);
                data[Header.GRAIN_MASS.word]=state.word(VulkanGranularStateLayout.Field.MASS_UNITS);data[Header.GRAIN_TERMS.word]=word(granularTerms);data[Header.GRAIN_CHANGES.word]=word(granularChanges);
                data[Header.GRAIN_EXTERNAL_WORK.word]=granular.externalWorkWordOffset();data[Header.GRAIN_SOURCE_HEAT.word]=granular.sourceHeatWordOffset();
                data[Header.DEPOSIT_CAPACITY.word]=state.depositCapacity();data[Header.GRAIN_OFFSETS.word]=granular.geometry().word(VulkanGranularGeometry.Field.OFFSETS);
                data[Header.GRAIN_ENTRIES.word]=granular.geometry().word(VulkanGranularGeometry.Field.ENTRIES);
                data[Header.DEPOSIT_CELL.word]=state.word(VulkanGranularStateLayout.Field.DEPOSIT_CELL);
            }
            data[Header.SOURCE_FACE_K.word]=sourceFaceKWordOffset;
            data[Header.GAS_EXTERNAL_WORK.word]=gasExternalWorkWordOffset;
            data[Header.GAS_POTENTIAL.word]=-1;data[Header.GAS_ENERGY.word]=-1;data[Header.SOURCE_ELASTIC.word]=-1;data[Header.TARGET_ELASTIC.word]=-1;
            if(gasEnergy!=null){
                data[Header.SOURCE_ELASTIC.word]=word(gasEnergy.source())+2*VulkanMixtureState.Quantity.ELASTIC_ENERGY.ordinal();
                data[Header.TARGET_ELASTIC.word]=gasEnergy.target().statesWord()+2*VulkanMixtureState.Quantity.ELASTIC_ENERGY.ordinal();
                if(gasEnergy.transport()!=null){var gas=gasEnergy.transport().source;
                    data[Header.GAS_ENERGY.word]=Math.toIntExact(gas.column(VulkanGasStateLayout.Field.INTERNAL_ENERGY).offset()/4);
                    data[Header.GAS_COUNTS.word]=Math.toIntExact(gas.column(VulkanGasStateLayout.Field.COUNTS).offset()/4);
                    data[Header.GAS_POTENTIAL.word]=word(gasEnergy.potential());
                    data[Header.GAS_ROUTE_MASS.word]=gasEnergy.transport().word(VulkanLocalGasTransport.Field.MASS);
                    data[Header.GAS_COMPONENTS.word]=gas.definitions().componentIds().size();
                    data[Header.GAS_ENERGY_CHANGES.word]=word(gasEnergy.energyChanges());data[Header.GAS_STATES.word]=word(gasEnergy.eos().states());data[Header.GAS_EOS.word]=gasEnergy.eos().headerWordOffset();
                }
            }
            data[Header.ROUTE_OFFSETS.word]=word(momentum.routes().column(VulkanLiquidRouteLayout.Field.SOURCE_OFFSETS));data[Header.ROUTE_MASKS.word]=word(momentum.routes().column(VulkanLiquidRouteLayout.Field.SOURCE_MASKS));
            data[Header.ACTIVITY_CELLS.word]=momentum.activityCellsWordOffset();data[Header.ACTIVITY_FACES.word]=momentum.activityFacesWordOffset();
            for(int i=0;i<data.length;i++)out.putInt(4*i,data[i]);out.putDouble(4*120,mmm.thermal.ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K);return out;
        }
        void initializeHeader(ByteBuffer destination){destination.duplicate().position(Math.toIntExact(header.offset())).put(headerBytes());}
        /** Actual stored U change minus the physical work; exact binary reduction retains all rounding. */
        List<VulkanEnergyLedger.Stream> roundoffStreams() {
            var streams=new java.util.ArrayList<VulkanEnergyLedger.Stream>();
            streams.add(stream(faceTerms,-1));streams.add(stream(potentialTerms,-1));streams.add(stream(energyChanges,1));
            if(gasWorkWordOffset>=0)streams.add(new VulkanEnergyLedger.Stream(1,gasWorkWordOffset,bodies.gasNodeCapacity(),2,1));
            if(sourceHeatWordOffset>=0)streams.add(new VulkanEnergyLedger.Stream(1,sourceHeatWordOffset,bodies.source().cellCount(),2,1));
            if(externalWorkWordOffset>=0)streams.add(new VulkanEnergyLedger.Stream(1,externalWorkWordOffset,bodies.source().cellCount(),2,-1));
            if(gasExternalWorkWordOffset>=0)streams.add(new VulkanEnergyLedger.Stream(1,gasExternalWorkWordOffset,bodies.gasNodeCapacity(),2,-1));
            if(gasEnergy!=null){
                if(gasEnergy.transport()!=null){streams.add(stream(gasEnergy.energyChanges(),1));streams.add(stream(gasEnergy.potential(),-1));}
                int quantity=2*VulkanMixtureState.Quantity.ELASTIC_ENERGY.ordinal();
                streams.add(new VulkanEnergyLedger.Stream(1,word(gasEnergy.source())+quantity,bodies.source().cellCount(),14,-1));
                streams.add(new VulkanEnergyLedger.Stream(1,gasEnergy.target().statesWord()+quantity,bodies.source().cellCount(),14,1));
            }
            if(granular!=null){
                streams.add(stream(granularTerms,-1));streams.add(stream(granularChanges,1));int count=bodies.granular().state().particleCapacity();
                if(granular.sourceHeatWordOffset()>=0)streams.add(new VulkanEnergyLedger.Stream(1,granular.sourceHeatWordOffset(),count,2,1));
                if(granular.externalWorkWordOffset()>=0)streams.add(new VulkanEnergyLedger.Stream(1,granular.externalWorkWordOffset(),count,2,-1));
            }
            return List.copyOf(streams);
        }
    }
    static Layout layout(VulkanMechanicalBodies.Layout bodies,VulkanLiquidMomentum.Layout momentum,
                         VulkanLiquidThermodynamics.Layout thermal,int gasWorkWordOffset,long scratchBase,long alignment) {
        return layout(bodies,momentum,thermal,gasWorkWordOffset,-1,-1,scratchBase,alignment);
    }
    /** Source heat is already carried by accepted material routes; external work enters the system once. */
    static Layout layout(VulkanMechanicalBodies.Layout bodies,VulkanLiquidMomentum.Layout momentum,
                         VulkanLiquidThermodynamics.Layout thermal,int gasWorkWordOffset,int sourceHeatWordOffset,
                         int externalWorkWordOffset,long scratchBase,long alignment) {
        return layout(bodies,momentum,thermal,gasWorkWordOffset,sourceHeatWordOffset,externalWorkWordOffset,null,scratchBase,alignment);
    }
    static Layout layout(VulkanMechanicalBodies.Layout bodies,VulkanLiquidMomentum.Layout momentum,
                         VulkanLiquidThermodynamics.Layout thermal,int gasWorkWordOffset,int sourceHeatWordOffset,
                         int externalWorkWordOffset,GranularEnergy granular,long scratchBase,long alignment){
        if(scratchBase<Math.max(bodies.endOffset(),Math.max(momentum.endOffset(),thermal.endOffset()))
                ||alignment<8||(alignment&(alignment-1))!=0||thermal.liquid()!=momentum.output()
                ||bodies.source().cellCount()!=thermal.liquid().cellCount())throw new IllegalArgumentException("Mechanical energy layouts differ");
        if((granular==null)!=(bodies.granular()==null)||granular!=null&&(scratchBase<Math.max(granular.thermal().endOffset(),granular.geometry().endOffset())
                ||granular.geometry().state()!=bodies.granular().state()
                ||granular.thermal().inventory().countsWord()!=bodies.granular().state().word(VulkanGranularStateLayout.Field.COUNTS)))
            throw new IllegalArgumentException("Granular caloric inventory differs from mechanical bodies");
        int vertices=bodies.vertices();long[] cursor={scratchBase};var header=column(cursor,128,4,alignment);
        var faces=column(cursor,Math.multiplyExact(bodies.faces(),2),8,alignment);
        var potential=column(cursor,Math.multiplyExact(momentum.source().stockCapacity(),2),8,alignment);
        var vertex=column(cursor,Math.multiplyExact(vertices,4),8,alignment);
        var counts=column(cursor,vertices,4,alignment);var offsets=column(cursor,Math.addExact(vertices,1),4,alignment);
        var roots=column(cursor,vertices,4,alignment);var partials=column(cursor,Math.multiplyExact(vertices,4),8,alignment);
        var result=column(cursor,Math.multiplyExact(vertices,4),8,alignment);
        var changes=column(cursor,Math.multiplyExact(thermal.liquid().stockCapacity(),2),8,alignment);
        var dispatch=column(cursor,3,4,alignment);var scan=column(cursor,Math.toIntExact(VulkanPrefixScan.workspaceBytes(vertices)/4),4,alignment);
        int particles=granular==null?0:bodies.granular().state().particleCapacity();
        int granularRows=granular==null?0:Math.addExact(particles,bodies.granular().state().depositCapacity());
        var granularTerms=column(cursor,Math.multiplyExact(particles,4),8,alignment);var granularChanges=column(cursor,Math.multiplyExact(granularRows,2),8,alignment);
        return new Layout(bodies,momentum,thermal,gasWorkWordOffset,sourceHeatWordOffset,externalWorkWordOffset,granular,header,faces,potential,vertex,counts,offsets,roots,partials,result,changes,dispatch,scan,granularTerms,granularChanges,-1,-1,null,align(cursor[0],alignment));
    }
    private final FlipKernelBindings bindings;
    private final VulkanPrefixScan scan;
    private FlipKernelBindings.BufferRange candidate;
    VulkanMechanicalEnergy(VulkanDeviceContext context){
        bindings=new FlipKernelBindings(context,3,shader(),24);
        try{scan=new VulkanPrefixScan(context);}catch(RuntimeException|LinkageError failure){bindings.close();throw failure;}
    }
    void rebindRanges(FlipKernelBindings.BufferRange source,FlipKernelBindings.BufferRange candidate,FlipKernelBindings.BufferRange errors){
        bindings.rebindRanges(List.of(source,candidate,errors));scan.rebindRanges(candidate,candidate,candidate,errors);this.candidate=candidate;
    }
    /** Run after accepted momentum mixing heat and stock caloric fields, with stable body CSR. */
    void record(VkCommandBuffer command,Layout layout,double cellSize,double gravity){
        stage(command,layout,0,layout.bodies().faces(),cellSize,gravity,false);
        stage(command,layout,1,Math.max(layout.momentum().source().stockCapacity(),layout.bodies().gasNodeCapacity()),cellSize,gravity,false);
        if(layout.granular()!=null)stage(command,layout,8,layout.granularChanges().count()/2,cellSize,gravity,false);
        stage(command,layout,2,layout.bodies().vertices(),cellSize,gravity,false);
        scan.record(command,layout.bodies().vertices(),word(layout.blockCounts()),word(layout.blockOffsets()),word(layout.scanWorkspace()));
        stage(command,layout,3,layout.bodies().vertices(),cellSize,gravity,false);
        stage(command,layout,4,layout.bodies().vertices(),cellSize,gravity,true);
        stage(command,layout,5,layout.bodies().vertices(),cellSize,gravity,true);
        stage(command,layout,6,layout.thermal().liquid().stockCapacity(),cellSize,gravity,false);
        stage(command,layout,7,layout.thermal().liquid().cellCount(),cellSize,gravity,false);
        if(layout.granular()!=null)stage(command,layout,9,layout.granularChanges().count()/2,cellSize,gravity,false);
        if(layout.gasEnergy()!=null&&layout.gasEnergy().transport()!=null)stage(command,layout,10,layout.bodies().gasNodeCapacity(),cellSize,gravity,false);
    }
    private void stage(VkCommandBuffer command,Layout layout,int stage,int count,double cellSize,double gravity,boolean indirect){
        if(count==0)return;int groups=(count+127)/128;
        try(var stack=stackPush()) {
            bindings.bind(command,stack);var push=stack.malloc(24).putInt(word(layout.header())).putInt(stage).putDouble(cellSize).putDouble(gravity).flip();
            vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,push);
            if(indirect){
                VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,
                        VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,VK_ACCESS_INDIRECT_COMMAND_READ_BIT,stack);
                vkCmdDispatchIndirect(command,candidate.buffer().handle(),candidate.offset()+layout.dispatch().offset());
            }else vkCmdDispatch(command,Math.min(groups,65535),(groups+65534)/65535,1);
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
        }
    }
    private static VulkanEnergyLedger.Stream stream(VulkanLiquidStateLayout.Column column,int sign){return new VulkanEnergyLedger.Stream(1,word(column),column.count(),2,sign);}
    private static int word(VulkanLiquidStateLayout.Column column){return Math.toIntExact(column.offset()/4);}
    private static long align(long value,long alignment){return Math.addExact(value,alignment-1)&-alignment;}
    private static VulkanLiquidStateLayout.Column column(long[] cursor,int count,int width,long alignment){
        var result=new VulkanLiquidStateLayout.Column(align(cursor[0],alignment),count,width);cursor[0]=result.offset()+result.bindingBytes();return result;
    }
    private static String shader(){
        try(var input=VulkanMechanicalEnergy.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/mechanical-energy.comp")){
            if(input==null)throw new IllegalStateException("Mechanical energy shader missing");return new String(input.readAllBytes(),StandardCharsets.UTF_8).replace("// HOST_LAYOUT", shaderLayout() + VulkanShaderLayout.fields("L_", VulkanLiquidStateLayout.Field.values()) + VulkanGasThermodynamics.shaderLayout()).replace("// GAS_CALORIC_MATERIAL",VulkanLiquidPhaseRows.resource("gas-caloric-material.glsl")).replace("// LOCAL_GAS_GRID",VulkanLiquidPhaseRows.resource("local-gas-grid.glsl"));
        }catch(IOException failure){throw new IllegalStateException("Mechanical energy shader unavailable",failure);}
    }
    @Override public void close(){try{scan.close();}finally{bindings.close();}}
}
