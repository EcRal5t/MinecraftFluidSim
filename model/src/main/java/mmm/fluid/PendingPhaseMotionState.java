package mmm.fluid;

import java.util.List;

/** Cells whose changed phase geometry awaits a successful mechanical step. */
public record PendingPhaseMotionState(int cellCount, List<Entry> entries) {
    public record Entry(int cell) {
        public Entry { if(cell<0)throw new IllegalArgumentException("Invalid pending phase cell"); }
    }
    public PendingPhaseMotionState {
        if(cellCount<=0||entries==null||entries.size()>cellCount)throw new IllegalArgumentException("Invalid pending phase cell set");
        entries=List.copyOf(entries);int previous=-1;
        for(var entry:entries){if(entry.cell()<=previous||entry.cell()>=cellCount)throw new IllegalArgumentException("Pending phase cells are not sorted and unique");previous=entry.cell();}
    }
    public boolean isEmpty(){return entries.isEmpty();}
}
