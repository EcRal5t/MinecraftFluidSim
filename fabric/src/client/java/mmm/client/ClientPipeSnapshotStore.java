package mmm.client;

import java.util.*;
import mmm.network.FluidProtocol;
import mmm.network.PipeSnapshotPayload;
import net.minecraft.resources.Identifier;

/** Installs only complete, newer dimension-wide pipe snapshots. */
final class ClientPipeSnapshotStore {
    private record Pending(long generation,PipeSnapshotPayload[] parts) { }
    private record Snapshot(long generation,List<PipeSnapshotPayload.Segment> segments) { }
    private final Map<Identifier,Pending> pending=new HashMap<>();
    private final Map<Identifier,Snapshot> snapshots=new HashMap<>();
    synchronized void accept(PipeSnapshotPayload payload,FluidProtocol.Negotiation negotiation) {
        if(negotiation==null || !negotiation.accepted() || (negotiation.capabilities()&FluidProtocol.CAP_PIPE_SNAPSHOT)==0)return;
        var previous=snapshots.get(payload.dimension());
        if(previous!=null && payload.generation()<=previous.generation())return;
        var candidate=pending.get(payload.dimension());
        if(candidate!=null && payload.generation()<candidate.generation())return;
        if(candidate==null || payload.generation()>candidate.generation()) {
            candidate=new Pending(payload.generation(),new PipeSnapshotPayload[payload.parts()]);pending.put(payload.dimension(),candidate);
        }
        if(candidate.parts().length!=payload.parts())throw new IllegalArgumentException("Pipe snapshot part count changed");
        var old=candidate.parts()[payload.part()];
        if(old!=null && !old.equals(payload))throw new IllegalArgumentException("Pipe snapshot part changed");
        candidate.parts()[payload.part()]=payload;
        for(var item:candidate.parts())if(item==null)return;
        var values=new ArrayList<PipeSnapshotPayload.Segment>();var positions=new HashSet<Long>();
        for(var item:candidate.parts())for(var segment:item.segments()) {
            if(!positions.add(segment.position()))throw new IllegalArgumentException("Duplicate pipe snapshot position");
            values.add(segment);
        }
        snapshots.put(payload.dimension(),new Snapshot(payload.generation(),List.copyOf(values)));pending.remove(payload.dimension());
    }
    synchronized List<PipeSnapshotPayload.Segment> forDimension(Identifier dimension) {
        var snapshot=snapshots.get(dimension);return snapshot==null?List.of():snapshot.segments();
    }
    synchronized void clear(){pending.clear();snapshots.clear();}
}
