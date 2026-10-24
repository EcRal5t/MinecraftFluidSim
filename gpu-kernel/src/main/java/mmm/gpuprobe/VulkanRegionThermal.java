package mmm.gpuprobe;

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
            var all=new ArrayList<ThermalMaterialDefinition>();
            int[] l=append(all,liquid),g=append(all,gas),p=append(all,granular),c=append(all,coherent);
            return new Configuration(VulkanThermalConduction.Tables.fromMaterials(all),
                    new VulkanThermalContacts.Materials(l,g,p,c,all.stream().mapToDouble(ThermalMaterialDefinition::conductivityWPerMeterK).toArray()),
                    cellSize,bathTemperatures,contactCapacity);
        }
        private static int[] append(List<ThermalMaterialDefinition> all,List<ThermalMaterialDefinition> values){
            int[] map=new int[values.size()];for(int i=0;i<map.length;i++){map[i]=all.size();all.add(values.get(i));}return map;
        }
    }
    static final class ContactCapacityGrowth extends IllegalStateException {
        final int required;
        ContactCapacityGrowth(int required){super("Thermal contacts require "+required+" entries");this.required=required;}
    }
    record Layout(VulkanThermalContacts.Inputs input,VulkanThermalConduction.Layout conduction,
                  VulkanThermalContacts.Layout contacts,VulkanEnergyLedger.Layout reservoir,long endOffset) {
        void initialize(ByteBuffer arena){conduction.initializeHeaderAndTables(arena);contacts.initializeHeaderAndTables(arena);reservoir.initializeHeader(arena);}
        List<VulkanGasThermodynamics.TableChunk> headers(){return List.of(
                new VulkanGasThermodynamics.TableChunk(conduction.header.offset(),conduction.headerBytes()),
                new VulkanGasThermodynamics.TableChunk(contacts.header.offset(),contacts.headerBytes()),
                new VulkanGasThermodynamics.TableChunk(reservoir.header().offset(),reservoir.headerBytes()));}
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
    static Layout layout(VulkanThermalContacts.Inputs input,Configuration config,int contacts,long base,long alignment){
        var conduction=VulkanThermalConduction.layout(input.owners(),input.terms(),contacts,config.tables(),base,alignment);
        var sampling=VulkanThermalContacts.layout(input,config.materials(),conduction,conduction.endOffset(),alignment);
        var reservoir=VulkanEnergyLedger.layout(List.of(stream(conduction,VulkanThermalConduction.Field.RESERVOIR_CHANGE,1)),sampling.endOffset(),alignment);
        return new Layout(input,conduction,sampling,reservoir,reservoir.endOffset());
    }
    private static VulkanEnergyLedger.Stream stream(VulkanThermalConduction.Layout l,VulkanThermalConduction.Field field,int sign){
        return new VulkanEnergyLedger.Stream(1,l.word(field),l.column(field).count(),2,sign);
    }
    private final List<AutoCloseable> owned=new ArrayList<>();
    private final VulkanLiquidThermodynamics caloric;
    private final VulkanGasThermodynamics gasEOS;
    private final VulkanThermalContacts contacts;
    private final VulkanThermalConduction conduction;
    private final VulkanPressureKernel pressure;
    VulkanRegionThermal(VulkanDeviceContext context){
        try{caloric=own(new VulkanLiquidThermodynamics(context));gasEOS=own(new VulkanGasThermodynamics(context));
            contacts=own(new VulkanThermalContacts(context));conduction=own(new VulkanThermalConduction(context));pressure=own(new VulkanPressureKernel(context,4096));
        }catch(RuntimeException|Error failure){try{close();}catch(RuntimeException closing){failure.addSuppressed(closing);}throw failure;}
    }
    private <T extends AutoCloseable>T own(T resource){owned.add(resource);return resource;}
    /** Leaves the candidate open for its remaining phases and the single request commit. */
    int execute(VulkanRegionStorage.Candidate candidate,Layout l,double duration){
        var status=candidate.continueBatch((command,in,out,errors,staging,stack)->{
            var arena=new FlipKernelBindings.BufferRange(out);var err=new FlipKernelBindings.BufferRange(errors);
            caloric.rebindRanges(arena,err);gasEOS.rebindRanges(arena,err);contacts.rebindRanges(arena,err);
            conduction.rebindRanges(arena,err);pressure.rebindRanges(l.conduction().pressureRanges(arena,err));
            // Temperature belongs to the post-work inventory, including the finite-aperture gas transfer.
            caloric.record(command,l.input().liquidThermal());
            if(l.input().granular()!=null)caloric.record(command,l.input().granularThermal());
            if(l.input().coherent()!=null)caloric.record(command,l.input().coherentThermal());
            if(l.input().gas()!=null){gasEOS.recordVolumes(command,l.input().gasThermal(),l.input().gasGeometry());gasEOS.record(command,l.input().gasThermal());}
            contacts.recordPrepare(command,l.contacts());
        },l.contacts().column(VulkanThermalContacts.Field.REQUIREMENTS).offset(),16);
        if(status.error()==64){int required=status.feedback().getInt(12);if(required>l.conduction().contacts)throw new ContactCapacityGrowth(required);}
        if(status.error()!=0)return status.error();
        status=candidate.continueBatch((command,in,out,errors,staging,stack)->{
            conduction.recordPrepare(command,l.conduction(),duration);conduction.recordExplicit(command,l.conduction(),duration);
        },l.conduction().column(VulkanThermalConduction.Field.MODE).offset(),4);
        if(status.error()!=0)return status.error();
        if(status.feedback().getInt(0)==VulkanThermalConduction.IMPLICIT)conduction.solveImplicit(candidate,l.conduction(),pressure,duration);
        return candidate.continueBatch((command,in,out,errors,staging,stack)->{
            conduction.recordFinish(command,l.conduction(),duration);contacts.recordPublish(command,l.contacts());
        },0,0).error();
    }
    @Override public void close(){RuntimeException failure=null;for(int i=owned.size()-1;i>=0;i--)try{owned.get(i).close();}catch(Exception e){
        if(failure==null)failure=new IllegalStateException("Closing thermal kernels failed",e);else failure.addSuppressed(e);}
        owned.clear();if(failure!=null)throw failure;}
}
