package mmm.gpuprobe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.EnumMap;
import java.util.List;

/** Private conversion proposals refer to existing inventory owners; accepted rows remain in their owning stores. */
final class VulkanReactionProgressLayout {
    enum Carrier { LIQUID, GAS, PARTICLE, DEPOSIT, COHERENT }
    enum Status { PREPARING, NEED_CAPACITY, READY, ACCEPTED, DEFERRED, INVALID }
    enum Field {
        HEADER(4),CONTROL(4),REQUEST_GENERATION(8),DIRTY_CELLS(4),CURSOR(4),
        CELL(4),MATERIAL(4),SUPPORT(4),PHASE(4),RULE(4),SOURCE_KIND(4),SOURCE_ROW(4),TARGET_KIND(4),TARGET_ROW(4),
        REQUESTED_EXTENT(8),ACCEPTED_EXTENT(8),PENDING_SECONDS(8),RESIDUAL_MOLES(8),
        MASS_DELTA(8),SOURCE_VOLUME_DELTA_SVQ(8),TARGET_VOLUME_DELTA_SVQ(8),GAS_STANDARD_VOLUME_DELTA(8),
        SPECIES_COUNTS(4),SPECIES_OFFSETS(4),SPECIES_HANDLES(4),SPECIES_DELTAS(8),
        CARRIED_ENERGY(8),MECHANICAL_ENERGY(8),ENERGY_CHANGE(8),STATUS(4);
        final int width;Field(int width){this.width=width;}
    }
    /** Bank-relative byte ranges are bound at execution, so a workspace migration never persists pointers. */
    record Inventory(Carrier carrier,int capacity,int countsWord,int materialWord,int massWord,int energyWord,
                     int speciesOffsetsWord,int speciesHandlesWord,int speciesAmountsWord,int massResidualWord){
        Inventory{if(carrier==null||capacity<0||countsWord<0||materialWord<0||massWord<0||energyWord<0
                ||speciesOffsetsWord<0||speciesHandlesWord<0||speciesAmountsWord<0||massResidualWord<0)
            throw new IllegalArgumentException("Invalid reaction inventory columns");}
    }
    /** Persistent kinetic work uses stock identity; gas and compact owner rows are resolved anew each prepare. */
    record Pending(int cell,int material,int support,int rule,double seconds,double residualMoles){
        Pending{if(cell<0||material<0||support<=0||support>255||rule<0||!Double.isFinite(seconds)||seconds<0||!Double.isFinite(residualMoles))
            throw new IllegalArgumentException("Invalid pending reaction transfer");}
    }
    private final int cells,proposals,speciesEntries;private final long endOffset;
    private final List<Inventory> inventories;private final VulkanReactionDefinitions.Layout definitions;
    private final EnumMap<Field,VulkanLiquidStateLayout.Column> columns=new EnumMap<>(Field.class);
    VulkanReactionProgressLayout(int cells,int proposalCapacity,int speciesEntryCapacity,List<Inventory> inventories,
                                VulkanReactionDefinitions.Layout definitions,long base,long alignment){
        if(cells<1||proposalCapacity<1||speciesEntryCapacity<0||base<definitions.endOffset()||alignment<8||(alignment&(alignment-1))!=0)
            throw new IllegalArgumentException("Invalid reaction progress layout");
        this.cells=cells;proposals=proposalCapacity;speciesEntries=speciesEntryCapacity;this.inventories=List.copyOf(inventories);this.definitions=definitions;
        long cursor=base;
        for(var f:Field.values()){
            int count=switch(f){case HEADER->64+12*inventories.size();case CONTROL->8;case REQUEST_GENERATION,CURSOR->1;
                case DIRTY_CELLS->cells;case SPECIES_OFFSETS->proposals+1;case SPECIES_HANDLES,SPECIES_DELTAS->speciesEntries;
                case ENERGY_CHANGE->2*proposals;default->proposals;};
            long start=Math.addExact(cursor,alignment-1)&-alignment;var c=new VulkanLiquidStateLayout.Column(start,count,f.width);columns.put(f,c);cursor=start+c.bindingBytes();
        }
        endOffset=Math.addExact(cursor,alignment-1)&-alignment;Math.toIntExact(endOffset/4);
    }
    VulkanLiquidStateLayout.Column column(Field field){return columns.get(field);}int word(Field field){return Math.toIntExact(column(field).offset()/4);}
    long endOffset(){return endOffset;}int proposalCapacity(){return proposals;}int speciesEntryCapacity(){return speciesEntries;}
    /** CONTROL: status, proposals required, species required, evaluations, phase batches, pending, error, substep ordinal. */
    ByteBuffer headerBytes(){
        var out=ByteBuffer.allocateDirect(Math.toIntExact(column(Field.HEADER).bytes())).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(0,cells).putInt(4,proposals).putInt(8,speciesEntries).putInt(12,definitions.word()).putInt(16,inventories.size());
        for(var f:Field.values())out.putInt(4*(16+f.ordinal()),word(f));
        for(int i=0;i<inventories.size();i++){var v=inventories.get(i);int at=4*(64+12*i);
            int[] row={v.carrier().ordinal(),v.capacity(),v.countsWord(),v.materialWord(),v.massWord(),v.energyWord(),
                    v.speciesOffsetsWord(),v.speciesHandlesWord(),v.speciesAmountsWord(),v.massResidualWord(),0,0};
            for(int j=0;j<row.length;j++)out.putInt(at+4*j,row[j]);}
        return out;
    }
    void initializeHeader(ByteBuffer arena){arena.duplicate().position(Math.toIntExact(column(Field.HEADER).offset())).put(headerBytes());}
}
