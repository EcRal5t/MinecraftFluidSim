package mmm.gpu;

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
        CARRIED_ENERGY(8),MECHANICAL_ENERGY(8),ENERGY_CHANGE(8),STATUS(4),
        PENDING_COUNT(4),PENDING_CELL(4),PENDING_MATERIAL(4),PENDING_SUPPORT(4),PENDING_RULE(4),PHASE_MOTION_CELLS(4),PHASE_PENDING_SUMMARY(4);
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
    record State(int cursor,long requestGeneration,List<Pending> pending,int[] dirtyCells,int[] phaseMotionCells){
        State{pending=List.copyOf(pending);dirtyCells=dirtyCells.clone();phaseMotionCells=phaseMotionCells.clone();}
        static State empty(){return new State(0,0,List.of(),new int[0],new int[0]);}
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
            int count=switch(f){case HEADER->64+12*inventories.size();case CONTROL->8;case REQUEST_GENERATION,CURSOR,PENDING_COUNT,PHASE_PENDING_SUMMARY->1;
                case DIRTY_CELLS,PHASE_MOTION_CELLS->cells;case SPECIES_OFFSETS->proposals+1;case SPECIES_HANDLES,SPECIES_DELTAS->speciesEntries;
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
    /** Checkpoint/cold-import data; ordinary device steps retain these columns without host reconstruction. */
    List<VulkanGasThermodynamics.TableChunk> stateChunks(State state){
        if(state.cursor()<0||state.cursor()>=cells||state.pending().size()>proposals)throw new IllegalArgumentException("Reaction progress exceeds device layout");
        var result=new java.util.ArrayList<VulkanGasThermodynamics.TableChunk>();
        result.add(intChunk(Field.CURSOR,new int[]{state.cursor()}));result.add(intChunk(Field.PENDING_COUNT,new int[]{state.pending().size()}));
        var generation=ByteBuffer.allocateDirect(8).order(ByteOrder.LITTLE_ENDIAN).putLong(0,state.requestGeneration());result.add(new VulkanGasThermodynamics.TableChunk(column(Field.REQUEST_GENERATION).offset(),generation));
        int[] dirty=new int[cells],phase=new int[cells];for(int c:state.dirtyCells()){if(c<0||c>=cells)throw new IllegalArgumentException("Dirty reaction cell outside grid");dirty[c]=1;}
        for(int c:state.phaseMotionCells()){if(c<0||c>=cells)throw new IllegalArgumentException("Pending phase cell outside grid");phase[c]=1;}
        result.add(intChunk(Field.DIRTY_CELLS,dirty));result.add(intChunk(Field.PHASE_MOTION_CELLS,phase));
        result.add(intChunk(Field.PHASE_PENDING_SUMMARY,new int[]{state.phaseMotionCells().length>0?1:0}));
        int n=state.pending().size();if(n>0){int[] cs=new int[n],ms=new int[n],ss=new int[n],rs=new int[n];var time=ByteBuffer.allocateDirect(8*n).order(ByteOrder.LITTLE_ENDIAN);var residual=ByteBuffer.allocateDirect(8*n).order(ByteOrder.LITTLE_ENDIAN);
            for(int i=0;i<n;i++){var item=state.pending().get(i);if(item.cell()>=cells)throw new IllegalArgumentException("Pending transfer cell outside grid");cs[i]=item.cell();ms[i]=item.material();ss[i]=item.support();rs[i]=item.rule();time.putDouble(8*i,item.seconds());residual.putDouble(8*i,item.residualMoles());}
            result.add(intChunk(Field.PENDING_CELL,cs));result.add(intChunk(Field.PENDING_MATERIAL,ms));result.add(intChunk(Field.PENDING_SUPPORT,ss));result.add(intChunk(Field.PENDING_RULE,rs));
            result.add(new VulkanGasThermodynamics.TableChunk(column(Field.PENDING_SECONDS).offset(),time));result.add(new VulkanGasThermodynamics.TableChunk(column(Field.RESIDUAL_MOLES).offset(),residual));
        }return List.copyOf(result);
    }
    State export(java.util.function.Function<VulkanLiquidStateLayout.Column,ByteBuffer> read){
        int cursor=read.apply(column(Field.CURSOR)).order(ByteOrder.LITTLE_ENDIAN).getInt(0),n=read.apply(column(Field.PENDING_COUNT)).order(ByteOrder.LITTLE_ENDIAN).getInt(0);
        if(cursor<0||cursor>=cells||n<0||n>proposals)throw new IllegalStateException("Device reaction progress exceeds layout");
        long generation=read.apply(column(Field.REQUEST_GENERATION)).order(ByteOrder.LITTLE_ENDIAN).getLong(0);var pending=new java.util.ArrayList<Pending>(n);
        if(n>0){var c=read.apply(column(Field.PENDING_CELL)).order(ByteOrder.LITTLE_ENDIAN);var m=read.apply(column(Field.PENDING_MATERIAL)).order(ByteOrder.LITTLE_ENDIAN);var s=read.apply(column(Field.PENDING_SUPPORT)).order(ByteOrder.LITTLE_ENDIAN);var r=read.apply(column(Field.PENDING_RULE)).order(ByteOrder.LITTLE_ENDIAN);
            var t=read.apply(column(Field.PENDING_SECONDS)).order(ByteOrder.LITTLE_ENDIAN);var q=read.apply(column(Field.RESIDUAL_MOLES)).order(ByteOrder.LITTLE_ENDIAN);
            for(int i=0;i<n;i++)pending.add(new Pending(c.getInt(4*i),m.getInt(4*i),s.getInt(4*i),r.getInt(4*i),t.getDouble(8*i),q.getDouble(8*i)));}
        return new State(cursor,generation,pending,marked(read.apply(column(Field.DIRTY_CELLS))),marked(read.apply(column(Field.PHASE_MOTION_CELLS))));
    }
    private int[] marked(ByteBuffer data){var b=data.order(ByteOrder.LITTLE_ENDIAN);return java.util.stream.IntStream.range(0,cells).filter(i->b.getInt(4*i)!=0).toArray();}
    private VulkanGasThermodynamics.TableChunk intChunk(Field f,int[] values){var data=ByteBuffer.allocateDirect(4*values.length).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<values.length;i++)data.putInt(4*i,values[i]);return new VulkanGasThermodynamics.TableChunk(column(f).offset(),data);}
}
