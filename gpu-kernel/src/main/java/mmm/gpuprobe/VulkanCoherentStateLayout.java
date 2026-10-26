package mmm.gpuprobe;

import mmm.chemistry.ChemicalRegistry;
import mmm.thermal.CoherentPhaseStore;
import mmm.thermal.ThermalMaterialDefinition;
import mmm.thermal.ThermalRegistry;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import static org.lwjgl.vulkan.VK10.vkCmdCopyBuffer;

/** Cell-local continuous solids. Integer SVQ, mass, composition and stored U share one device generation. */
final class VulkanCoherentStateLayout {
    enum Field {
        COUNTS(4),CELL(4),MATERIAL(4),OCCUPIED_VOLUME_SVQ(8),MASS_UNITS(8),MIXTURE_PRESENT(4),MASS_RESIDUAL_UNITS(8),
        SPECIES_OFFSETS(4),SPECIES_HANDLES(4),SPECIES_AMOUNTS(8),INTERNAL_ENERGY(8);
        final int width;Field(int width){this.width=width;}
    }
    record Definitions(String thermalRegistryFingerprint,String chemicalRegistryFingerprint,List<String> phaseIds,List<String> speciesIds){
        Definitions{phaseIds=List.copyOf(phaseIds);speciesIds=List.copyOf(speciesIds);}
    }
    record Configuration(Definitions definitions,List<CoherentPhaseStore.Group> initial,VulkanLiquidThermodynamics.Tables thermal,
                         double[] materialReferences,double[] speciesReferences,int speciesStride){
        Configuration{initial=List.copyOf(initial);materialReferences=materialReferences.clone();speciesReferences=speciesReferences.clone();}
        static Configuration fromRegistries(List<CoherentPhaseStore.Group> groups,List<String> speciesIds,ThermalRegistry thermal,ChemicalRegistry chemistry){
            var materials=thermal.materials().stream().filter(m->m.carrierKind()==ThermalMaterialDefinition.CarrierKind.COHERENT_SOLID).toList();
            var phases=materials.stream().map(ThermalMaterialDefinition::id).toList();int stride=Math.max(1,speciesIds.size());
            double[] reference=new double[phases.size()],species=new double[Math.multiplyExact(phases.size(),stride)];java.util.Arrays.fill(species,Double.NaN);
            for(int m=0;m<materials.size();m++){
                var material=materials.get(m);reference[m]=material.referenceSpecificEnergyJPerKg();int handle=thermal.materialHandle(material.id());
                for(int s=0;s<speciesIds.size();s++)if(thermal.hasSpeciesOffset(handle,speciesIds.get(s)))
                    species[m*stride+s]=thermal.speciesOffset(handle,speciesIds.get(s)).referenceInternalEnergyJPerMol()
                            .divide(mmm.chemistry.ChemicalLimits.QUANTA_PER_MOL).toDouble();
            }
            return new Configuration(new Definitions(thermal.fingerprint(),chemistry==null?"":chemistry.fingerprint(),phases,speciesIds),
                    groups,VulkanLiquidThermodynamics.tables(materials),reference,species,stride);
        }
        int speciesCount(){int count=0;for(var group:initial)if(group.composition()!=null)count=Math.addExact(count,group.composition().components().size());return count;}
    }
    private final int cells,groups,species;private final long baseOffset,endOffset,alignment;private final Definitions definitions;
    private final EnumMap<Field,VulkanLiquidStateLayout.Column> columns=new EnumMap<>(Field.class);
    VulkanCoherentStateLayout(int cells,int groupCapacity,int speciesCapacity,Definitions definitions,long base,long alignment){
        if(cells<1||groupCapacity<0||speciesCapacity<0||definitions==null||base<0||alignment<8||(alignment&(alignment-1))!=0)
            throw new IllegalArgumentException("Invalid continuous solid device layout");
        this.cells=cells;groups=groupCapacity;species=speciesCapacity;this.definitions=definitions;this.alignment=alignment;baseOffset=align(base);long cursor=baseOffset;
        for(Field f:Field.values()){
            int count=switch(f){case COUNTS->2;case SPECIES_OFFSETS->Math.addExact(groups,1);case SPECIES_HANDLES,SPECIES_AMOUNTS->species;default->groups;};
            var c=new VulkanLiquidStateLayout.Column(align(cursor),count,f.width);columns.put(f,c);cursor=Math.addExact(c.offset(),c.bindingBytes());
        }
        endOffset=align(cursor);Math.toIntExact(endOffset/4);
    }
    int cellCount(){return cells;}int groupCapacity(){return groups;}int speciesCapacity(){return species;}
    Definitions definitions(){return definitions;}long baseOffset(){return baseOffset;}long endOffset(){return endOffset;}long bytes(){return endOffset-baseOffset;}
    VulkanLiquidStateLayout.Column column(Field f){return columns.get(f);}int word(Field f){return Math.toIntExact(column(f).offset()/4);}
    void importState(ByteBuffer target,List<CoherentPhaseStore.Group> state){
        if(state.size()>groups)throw new IllegalArgumentException("Continuous solid groups exceed device capacity");
        int total=0,previousCell=-1;String previousPhase="";
        for(var group:state){
            if(group.cell()>=cells||!definitions.phaseIds().contains(group.phaseId())||group.cell()<previousCell
                    ||group.cell()==previousCell&&group.phaseId().compareTo(previousPhase)<=0)
                throw new IllegalArgumentException("Continuous solid groups differ from the canonical cell/material layout");
            previousCell=group.cell();previousPhase=group.phaseId();
            if(group.composition()!=null)for(var component:group.composition().components()){
                if(!definitions.speciesIds().contains(component.speciesId()))throw new IllegalArgumentException("Continuous solid chemical species is absent: "+component.speciesId());
                total=Math.addExact(total,1);
            }
        }
        if(total>species)throw new IllegalArgumentException("Continuous solid species exceed device capacity");
        var b=view(target);putInt(b,Field.COUNTS,0,state.size());putInt(b,Field.COUNTS,1,total);int cursor=0;
        for(int row=0;row<groups;row++){
            putInt(b,Field.SPECIES_OFFSETS,row,cursor);if(row>=state.size())continue;var group=state.get(row);
            putInt(b,Field.CELL,row,group.cell());putInt(b,Field.MATERIAL,row,definitions.phaseIds().indexOf(group.phaseId()));
            putLong(b,Field.OCCUPIED_VOLUME_SVQ,row,group.occupiedVolumeSvq());putLong(b,Field.MASS_UNITS,row,group.massUnits());
            putLong(b,Field.INTERNAL_ENERGY,row,Double.doubleToRawLongBits(group.internalEnergy()));
            var composition=group.composition();putInt(b,Field.MIXTURE_PRESENT,row,composition==null?0:1);
            putLong(b,Field.MASS_RESIDUAL_UNITS,row,composition==null?0:composition.massResidualUnits());
            if(composition!=null)for(var component:composition.components()){
                putInt(b,Field.SPECIES_HANDLES,cursor,definitions.speciesIds().indexOf(component.speciesId()));putLong(b,Field.SPECIES_AMOUNTS,cursor++,component.amountQuanta());
            }
        }
        putInt(b,Field.SPECIES_OFFSETS,groups,cursor);
    }
    /** Explicit checkpoint export; ordinary conduction and phase edits keep these columns on the device. */
    List<CoherentPhaseStore.Group> exportState(ByteBuffer source){
        var b=view(source);int count=getInt(b,Field.COUNTS,0),components=getInt(b,Field.COUNTS,1);
        if(count<0||count>groups||components<0||components>species)throw new IllegalStateException("Continuous solid checkpoint counts exceed layout");
        var result=new ArrayList<CoherentPhaseStore.Group>(count);
        for(int row=0;row<count;row++){
            int material=getInt(b,Field.MATERIAL,row),cell=getInt(b,Field.CELL,row),begin=getInt(b,Field.SPECIES_OFFSETS,row),end=getInt(b,Field.SPECIES_OFFSETS,row+1);
            if(material<0||material>=definitions.phaseIds().size()||cell<0||cell>=cells||begin<0||end<begin||end>components)
                throw new IllegalStateException("Continuous solid checkpoint row exceeds layout");
            CoherentPhaseStore.Composition composition=null;
            if(getInt(b,Field.MIXTURE_PRESENT,row)!=0&&begin<end){
                var values=new ArrayList<CoherentPhaseStore.Component>(end-begin);
                for(int i=begin;i<end;i++)values.add(new CoherentPhaseStore.Component(definitions.speciesIds().get(getInt(b,Field.SPECIES_HANDLES,i)),getLong(b,Field.SPECIES_AMOUNTS,i)));
                values.sort(Comparator.comparing(CoherentPhaseStore.Component::speciesId));composition=new CoherentPhaseStore.Composition(values,getLong(b,Field.MASS_RESIDUAL_UNITS,row));
            }else if(begin!=end)throw new IllegalStateException("Inert continuous solid contains chemical entries");
            result.add(new CoherentPhaseStore.Group(cell,definitions.phaseIds().get(material),getLong(b,Field.OCCUPIED_VOLUME_SVQ,row),
                    getLong(b,Field.MASS_UNITS,row),composition,Double.longBitsToDouble(getLong(b,Field.INTERNAL_ENERGY,row))));
        }
        result.sort(Comparator.comparingInt(CoherentPhaseStore.Group::cell).thenComparing(CoherentPhaseStore.Group::phaseId));return List.copyOf(result);
    }
    void copyColumns(VkCommandBuffer command,GpuBuffer input,VulkanCoherentStateLayout old,GpuBuffer output,MemoryStack stack){
        if(cells!=old.cells||groups<old.groups||species<old.species||!definitions.equals(old.definitions))
            throw new IllegalArgumentException("Continuous solid migration changes definitions or shrinks authority storage");
        var copies=VkBufferCopy.calloc(Field.values().length,stack);int count=0;
        for(Field f:Field.values()){var from=old.column(f);if(from.bytes()>0)copies.get(count++).srcOffset(from.offset()).dstOffset(column(f).offset()).size(from.bytes());}
        copies.limit(count);vkCmdCopyBuffer(command,input.handle(),output.handle(),copies);
    }
    private ByteBuffer view(ByteBuffer b){
        if(b.remaining()>=endOffset)return b.slice(Math.toIntExact(baseOffset),Math.toIntExact(bytes())).order(ByteOrder.LITTLE_ENDIAN);
        if(b.remaining()==bytes())return b.slice().order(ByteOrder.LITTLE_ENDIAN);
        throw new IllegalArgumentException("Continuous solid checkpoint buffer differs from layout");
    }
    private int address(Field f,int i){return Math.toIntExact(column(f).offset()-baseOffset+(long)i*f.width);}
    private void putInt(ByteBuffer b,Field f,int i,int x){b.putInt(address(f,i),x);}private int getInt(ByteBuffer b,Field f,int i){return b.getInt(address(f,i));}
    private void putLong(ByteBuffer b,Field f,int i,long x){b.putLong(address(f,i),x);}private long getLong(ByteBuffer b,Field f,int i){return b.getLong(address(f,i));}
    private long align(long n){return Math.addExact(n,alignment-1)&-alignment;}
}
