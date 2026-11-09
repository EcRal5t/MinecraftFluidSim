package mmm.gpu;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import mmm.thermal.ThermalMaterialDefinition;

/** Conduction within the mechanical candidate, after all inventory transport and mechanical work. */
final class VulkanRegionThermal implements AutoCloseable {
    record Configuration(VulkanThermalConduction.Tables tables,VulkanThermalContacts.Materials materials,
                         double cellSize,double[] bathTemperatures,int contactCapacity) {
        Configuration {
            bathTemperatures=bathTemperatures.clone();
            if(tables==null||materials==null||!(Double.isFinite(cellSize)&&cellSize>0)||contactCapacity<1)
                throw new IllegalArgumentException("Invalid region thermal configuration");
            for(double t:bathTemperatures)if(!Double.isNaN(t)&&!(Double.isFinite(t)&&t>0))
                throw new IllegalArgumentException("Invalid thermal bath temperature");
        }
        /** Each list follows its inventory's immutable material handles. */
        static Configuration fromMaterials(List<ThermalMaterialDefinition> liquid,List<ThermalMaterialDefinition> gas,
                List<ThermalMaterialDefinition> granular,List<ThermalMaterialDefinition> coherent,
                double cellSize,double[] bathTemperatures,int contactCapacity) {
            return fromMaterials(liquid,gas,granular,coherent,List.of(),cellSize,bathTemperatures,contactCapacity);
        }
        static Configuration fromMaterials(List<ThermalMaterialDefinition> liquid,List<ThermalMaterialDefinition> gas,
                List<ThermalMaterialDefinition> granular,List<ThermalMaterialDefinition> coherent,List<ThermalMaterialDefinition> devices,
                double cellSize,double[] bathTemperatures,int contactCapacity) {
            var all=new ArrayList<ThermalMaterialDefinition>();
            int[] l=append(all,liquid),g=append(all,gas),p=append(all,granular),c=append(all,coherent),d=append(all,devices);
            return new Configuration(VulkanThermalConduction.Tables.fromMaterials(all),
                    new VulkanThermalContacts.Materials(l,g,p,c,d,all.stream().mapToDouble(ThermalMaterialDefinition::conductivityWPerMeterK).toArray()),
                    cellSize,bathTemperatures,contactCapacity);
        }
        private static int[] append(List<ThermalMaterialDefinition> all,List<ThermalMaterialDefinition> values){
            int[] map=new int[values.size()];for(int i=0;i<map.length;i++){map[i]=all.size();all.add(values.get(i));}return map;
        }
    }
    static final class ContactCapacityGrowth extends IllegalStateException {
        final int required,baths;
        ContactCapacityGrowth(int required,int baths){super("Thermal contacts require "+required+" entries and "+baths+" bath entries");this.required=required;this.baths=baths;}
    }
    record Layout(VulkanThermalContacts.Inputs input,VulkanThermalConduction.Layout conduction,
                  VulkanThermalContacts.Layout contacts,VulkanEnergyLedger.Layout reservoir,VulkanEnergyLedger.Layout roundoff,long endOffset) {
        void initialize(ByteBuffer arena){conduction.initializeHeaderAndTables(arena);contacts.initializeHeaderAndTables(arena);reservoir.initializeHeader(arena);roundoff.initializeHeader(arena);}
        List<VulkanGasThermodynamics.TableChunk> headers(){return List.of(
                new VulkanGasThermodynamics.TableChunk(conduction.header.offset(),conduction.headerBytes()),
                new VulkanGasThermodynamics.TableChunk(contacts.header.offset(),contacts.headerBytes()),
                new VulkanGasThermodynamics.TableChunk(reservoir.header().offset(),reservoir.headerBytes()),new VulkanGasThermodynamics.TableChunk(roundoff.header().offset(),roundoff.headerBytes()));}
        List<VulkanGasThermodynamics.TableChunk> tables(){var chunks=new ArrayList<>(conduction.tableChunks());chunks.addAll(contacts.tableChunks());return chunks;}
        List<VulkanLiquidStateLayout.Column> persistentColumns(){
            var columns=new ArrayList<VulkanLiquidStateLayout.Column>();
            for(var chunk:headers())columns.add(new VulkanLiquidStateLayout.Column(chunk.offset(),chunk.bytes().remaining()/4,4));
            for(var chunk:tables())columns.add(new VulkanLiquidStateLayout.Column(chunk.offset(),chunk.bytes().remaining()/4,4));
            return columns;
        }
        List<VulkanEnergyLedger.Stream> roundoffStreams(){return List.of(stream(conduction,VulkanThermalConduction.Field.ENERGY_CHANGE,1),
                stream(conduction,VulkanThermalConduction.Field.RESERVOIR_CHANGE,-1));}
    }
    static Layout layout(VulkanThermalContacts.Inputs input,Configuration config,int contacts,int baths,long base,long alignment){
        int sampleCapacity=Math.addExact(input.samples(),input.device()==null?0:input.device().portCapacity());
        var conduction=VulkanThermalConduction.spatialLayout(input.owners(),input.terms(),contacts,sampleCapacity,config.tables(),base,alignment,baths);
        var sampling=VulkanThermalContacts.layout(input,config.materials(),conduction,conduction.endOffset(),alignment);
        var reservoir=VulkanEnergyLedger.layout(List.of(stream(conduction,VulkanThermalConduction.Field.RESERVOIR_CHANGE,1)),sampling.endOffset(),alignment);
        var roundoff=VulkanEnergyLedger.layout(List.of(stream(conduction,VulkanThermalConduction.Field.ENERGY_CHANGE,1),stream(conduction,VulkanThermalConduction.Field.RESERVOIR_CHANGE,-1)),reservoir.endOffset(),alignment);
        return new Layout(input,conduction,sampling,reservoir,roundoff,roundoff.endOffset());
    }
    static Layout currentGas(Layout layout,VulkanGasThermodynamics.Layout eos,VulkanGasGeometry.Layout geometry,long alignment){
        var i=layout.input();var input=new VulkanThermalContacts.Inputs(i.liquid(),i.liquidThermal(),i.gas(),eos,geometry,i.granular(),i.granularThermal(),
                i.coherent(),i.coherentThermal(),i.device(),i.deviceThermal(),i.fixedMasksWord(),i.bathTemperaturesWord(),i.facesWord(),i.cellSize(),i.activityCellWord(),i.plicPlanesWord(),i.plicCellsWord());
        var contacts=VulkanThermalContacts.layout(input,layout.contacts().materials,layout.conduction(),layout.contacts().header.offset(),alignment);
        contacts.wallGeometryWord=layout.contacts().wallGeometryWord;
        return new Layout(input,layout.conduction(),contacts,layout.reservoir(),layout.roundoff(),layout.endOffset());
    }
    private static VulkanEnergyLedger.Stream stream(VulkanThermalConduction.Layout l,VulkanThermalConduction.Field field,int sign){
        return new VulkanEnergyLedger.Stream(1,l.word(field),l.column(field).count(),2,sign);
    }
    private final List<AutoCloseable> owned=new ArrayList<>();
    private final VulkanLiquidThermodynamics caloric;
    private final VulkanThermalContacts contacts;
    private final VulkanThermalConduction conduction;
    private final VulkanPressureKernel pressure;
    private final VulkanEnergyLedger deviceLedger;
    private final VulkanCommandTemplates templates;
    private record PreparationKey(VulkanThermalContacts.Inputs input,VulkanThermalConduction.Layout conduction,
                                  long header,int cells,int owners,int samples,double duration,
                                  FlipKernelBindings.BufferIdentity frozen) {}
    private VulkanStageTimings timings;
    VulkanRegionThermal(VulkanDeviceContext context){
        try{caloric=own(new VulkanLiquidThermodynamics(context));
            contacts=own(new VulkanThermalContacts(context));conduction=own(new VulkanThermalConduction(context));pressure=own(new VulkanPressureKernel(context,4096));deviceLedger=own(new VulkanEnergyLedger(context));
            templates=own(new VulkanCommandTemplates(context));
            if(Boolean.getBoolean("mmm.gpuMechanicalStages")&&context.hasTimestampQueries())timings=own(new VulkanStageTimings(context,384));
        }catch(RuntimeException|Error failure){try{close();}catch(RuntimeException closing){failure.addSuppressed(closing);}throw failure;}
    }
    private <T extends AutoCloseable>T own(T resource){owned.add(resource);return resource;}
    /** Consumes current gas caloric state and leaves the candidate open for its remaining phases. */
    int execute(VulkanRegionStorage.Candidate candidate,Layout l,double duration){return execute(candidate,l,duration,null,null,false);}
    int execute(VulkanRegionStorage.Candidate candidate,Layout l,double duration,VulkanThermalDeviceLayout sourceDevices,VulkanThermalDeviceLayout.Settlement settlement,boolean continuation){
        return execute(candidate,l,duration,sourceDevices,settlement,continuation,null,null,-1);
    }
    /** Hooks share the first preparation and final publication submissions; failed candidates remain private. */
    int execute(VulkanRegionStorage.Candidate candidate,Layout l,double duration,VulkanThermalDeviceLayout sourceDevices,VulkanThermalDeviceLayout.Settlement settlement,boolean continuation,
                VulkanRegionStorage.ComputeCommands prefix,VulkanRegionStorage.ComputeCommands suffix){
        return execute(candidate,l,duration,sourceDevices,settlement,continuation,prefix,suffix,-1);
    }
    int execute(VulkanRegionStorage.Candidate candidate,Layout l,double duration,VulkanThermalDeviceLayout sourceDevices,VulkanThermalDeviceLayout.Settlement settlement,boolean continuation,
                VulkanRegionStorage.ComputeCommands prefix,VulkanRegionStorage.ComputeCommands suffix,long finalUploadedBytes){
        if(finalUploadedBytes < -1)throw new IllegalArgumentException("Final thermal upload bytes must be nonnegative or -1 for continuation");
        var status=candidate.continueBatch((command,in,out,errors,staging,stack)->{
            if(prefix!=null)prefix.record(command,in,out,errors,staging,stack);
            var arena=new FlipKernelBindings.BufferRange(out);var err=new FlipKernelBindings.BufferRange(errors);
            caloric.rebindRanges(arena,err);contacts.rebindRanges(arena,err);
            deviceLedger.rebindRanges(new FlipKernelBindings.BufferRange(in),arena,err);
            conduction.rebindRanges(arena,err);pressure.rebindRanges(l.conduction().pressureRanges(arena,err));
            if(timings!=null)timings.reset(command);
            if(timings!=null)recordPreparation(command,l,duration);
            else {
                var c=l.contacts();
                var key=new PreparationKey(l.input(),l.conduction(),c.header.offset(),c.cells,c.owners,c.samples,duration,contacts.frozenIdentity());
                templates.record(command,new VulkanCommandTemplates.Key(0,out,errors,0,0,key),
                        secondary->recordPreparation(secondary,l,duration));
            }
        },l.contacts().column(VulkanThermalContacts.Field.REQUIREMENTS).offset(),44);
        if(status.error()==64){int required=status.feedback().getInt(12),baths=status.feedback().getInt(16);if(required>l.conduction().contacts||baths>l.conduction().baths)throw new ContactCapacityGrowth(required,baths);}
        if(status.error()!=0)return status.error();
        int bathCount=status.feedback().getInt(24);
        int fastOwners=status.feedback().getInt(28),fastSamples=status.feedback().getInt(32);
        if(fastOwners>0){
            var offsets=l.contacts().column(VulkanThermalContacts.Field.CACHE_OFFSETS);
            status=candidate.continueBatch((command,in,out,errors,staging,stack)->{
                timed(command,"thermal-imex-fast-contacts",()->contacts.recordImexContacts(command,l.contacts(),duration,fastSamples));
                timed(command,"thermal-imex-isolated",()->conduction.recordImexIsolated(command,l.conduction(),duration,fastOwners));
            },offsets.offset()+4L*fastSamples,4);
            if(status.error()!=0)return status.error();int directedFastContacts=status.feedback().getInt(0);
            if(directedFastContacts>0){
                contacts.ensureFrozenCapacity(Math.multiplyExact(2,directedFastContacts));
                status=candidate.continueBatch((command,in,out,errors,staging,stack)->timed(command,"thermal-imex-cache",()->contacts.recordImexCache(command,l.contacts(),duration,fastSamples)),0,0);
                if(status.error()!=0)return status.error();
                conduction.solveImex(candidate,l.conduction(),pressure,duration,fastOwners,contacts.imexOperator(l.contacts(),duration,fastSamples),timings);
            }
        }
        VulkanRegionStorage.ComputeCommands publish=(command,in,out,errors,staging,stack)->{
            timed(command,"thermal-finish",()->{if(fastOwners>0)contacts.recordImexCorrection(command,l.contacts(),duration);conduction.recordImexFinish(command,l.conduction(),duration);});
            timed(command,"thermal-publish",()->contacts.recordPublish(command,l.contacts()));
            if(l.input().device()!=null)l.input().device().recordSettle(command,sourceDevices,settlement,deviceLedger,out,continuation);
            caloric.record(command,l.input().liquidThermal());
            if(l.input().granular()!=null)caloric.record(command,l.input().granularThermal());
            if(l.input().coherent()!=null)caloric.record(command,l.input().coherentThermal());
            if(l.input().device()!=null)caloric.record(command,l.input().deviceThermal());
            if(suffix!=null)suffix.record(command,in,out,errors,staging,stack);
        };
        int finalError=finalUploadedBytes<0?candidate.continueBatch(publish,0,0).error():candidate.finishBatch(finalUploadedBytes,publish);
        if(timings!=null){
            for(var sample:timings.read())System.out.printf("mechanical_kernel=%s gpu_ms=%.3f%n",sample.stage(),sample.milliseconds());
            System.out.printf("thermal_layout owner_capacity=%d term_capacity=%d contact_capacity=%d bath_count=%d bath_capacity=%d reservoir_MiB=%.3f%n",
                    l.conduction().owners,l.conduction().terms,l.conduction().contacts,bathCount,l.conduction().baths,l.conduction().column(VulkanThermalConduction.Field.RESERVOIR_CHANGE).bytes()/1048576.0);
            System.out.printf("thermal_imex fast_owners=%d fast_samples=%d frozen_SS_MiB=%.3f%n",fastOwners,fastSamples,contacts.frozenBytes()/1048576.0);
        }
        return finalError;
    }
    private void recordPreparation(org.lwjgl.vulkan.VkCommandBuffer command,Layout l,double duration){
        timed(command,"thermal-caloric",()->{
            caloric.record(command,l.input().liquidThermal());
            if(l.input().granular()!=null)caloric.record(command,l.input().granularThermal());
            if(l.input().coherent()!=null)caloric.record(command,l.input().coherentThermal());
            if(l.input().device()!=null)caloric.record(command,l.input().deviceThermal());
        });
        timed(command,"thermal-samples",()->contacts.recordSpatial(command,l.contacts()));
        conduction.recordSpatialInitialize(command,l.conduction(),duration);
        timed(command,"thermal-sample-operator",()->contacts.recordFullTrial(command,l.contacts(),duration));
        conduction.recordImexPrepare(command,l.conduction(),duration,timings);
        timed(command,"thermal-imex-classify",()->{
            conduction.recordImexClassify(command,l.conduction(),duration);
            contacts.recordImexSamples(command,l.contacts(),duration);
        });
        contacts.recordConductionState(command,l.contacts());
    }
    long deviceBytes(){return contacts.frozenBytes();}
    /** Settles the wall energy written by dry-wall condensation after conduction. */
    void recordCondensationSettlement(org.lwjgl.vulkan.VkCommandBuffer command,GpuBuffer output,GpuBuffer errors,
            VulkanThermalDeviceLayout device,VulkanThermalDeviceLayout.Settlement settlement,
            VulkanLiquidThermodynamics.Layout deviceThermal){
        var arena=new FlipKernelBindings.BufferRange(output);
        var errorBuffer=new FlipKernelBindings.BufferRange(errors);
        deviceLedger.rebindRanges(arena,arena,errorBuffer);
        device.recordSettle(command,device,settlement,deviceLedger,output,true);
        caloric.rebindRanges(arena,errorBuffer);
        caloric.record(command,deviceThermal);
    }
    private void timed(org.lwjgl.vulkan.VkCommandBuffer command,String label,Runnable commands){
        if(timings==null)commands.run();else timings.record(command,label,commands);
    }
    @Override public void close(){RuntimeException failure=null;for(int i=owned.size()-1;i>=0;i--)try{owned.get(i).close();}catch(Exception e){
        if(failure==null)failure=new IllegalStateException("Closing thermal kernels failed",e);else failure.addSuppressed(e);}
        owned.clear();if(failure!=null)throw failure;}
}
