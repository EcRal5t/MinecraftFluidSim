package mmm.fluid;

import java.util.BitSet;

/** Coalesces phase changes until the next accepted mechanical step. */
final class PendingPhaseMotionWork {
    private final int cellCount;
    private final BitSet cells=new BitSet();
    PendingPhaseMotionWork(int cellCount){this.cellCount=cellCount;}
    void mark(int cell){if(cell<0||cell>=cellCount)throw new IllegalArgumentException("Invalid pending phase cell");cells.set(cell);}
    boolean hasWork(){return !cells.isEmpty();}
    boolean isPending(int cell){return cells.get(cell);}
    PendingPhaseMotionState capture(){return new PendingPhaseMotionState(cellCount,cells.stream().mapToObj(PendingPhaseMotionState.Entry::new).toList());}
    void clear(){cells.clear();}
    void restore(PendingPhaseMotionState state){
        if(state==null||state.cellCount()!=cellCount||hasWork())throw new IllegalArgumentException("Pending phase motion restore is incompatible");
        for(var entry:state.entries())cells.set(entry.cell());
    }
}
