package mmm.pipe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import mmm.chemistry.ChemicalRegistry;
import mmm.fluid.air.GasInventory;
import mmm.fluid.air.GasRegistry;
import mmm.thermal.GasThermalModel;
import mmm.units.SignedDyadic;

/** Simultaneous gas routing, with composition and caloric partition from the source generation. */
public final class PipeGasTransport {
    private PipeGasTransport() { }
    public record Move(int connection, int source, int target, long standardVolumeNanos) {
        public Move {
            if (connection < 0 || source < 0 || target < 0 || standardVolumeNanos <= 0)
                throw new IllegalArgumentException("Invalid pipe gas move");
        }
    }
    public record Result(PipeNetworkState state, SignedDyadic energyRoundoff) { }
    public static Result route(PipeNetworkState state, List<Move> moves, GasRegistry gases,
                                         ChemicalRegistry chemistry, GasThermalModel thermal) {
        var sorted = new ArrayList<>(moves);
        sorted.sort(Comparator.comparingInt(Move::source).thenComparingInt(Move::connection).thenComparingInt(Move::target));
        int count=state.segments().size();
        var gas=new GasInventory[count]; var energy=new SignedDyadic[count];
        for(int i=0;i<count;i++) { gas[i]=state.segments().get(i).gas(); energy[i]=SignedDyadic.of(state.segments().get(i).gasInternalEnergy()); }
        var incoming=new GasInventory[count]; var added=new SignedDyadic[count];
        for(int i=0;i<count;i++) { incoming[i]=GasInventory.EMPTY; added[i]=SignedDyadic.ZERO; }
        SignedDyadic roundoff=SignedDyadic.ZERO;
        int offset=0;
        while(offset<sorted.size()) {
            int source=sorted.get(offset).source();
            if(source>=count)throw new IllegalArgumentException("Pipe gas source outside network");
            int end=offset; while(end<sorted.size() && sorted.get(end).source()==source)end++;
            var original=gas[source]; var rest=original; var portions=new ArrayList<GasInventory>();
            for(int i=offset;i<end;i++) {
                var move=sorted.get(i);
                if(move.connection()>=state.connections().size())throw new IllegalArgumentException("Unknown pipe connection");
                var edge=state.connections().get(move.connection());
                if(edge.opening()==0 || !(edge.first()==source && edge.second()==move.target()
                        || edge.second()==source && edge.first()==move.target()))
                    throw new IllegalArgumentException("Pipe gas route does not follow an open connection");
                var split=rest.splitTotal(move.standardVolumeNanos(),gases,chemistry);
                portions.add(split.first()); rest=split.second();
            }
            portions.add(rest);
            var energies=thermal.partitionEnergy(original,state.segments().get(source).gasInternalEnergy(),portions);
            roundoff=roundoff.add(SignedDyadic.sum(energies).subtract(SignedDyadic.of(state.segments().get(source).gasInternalEnergy())));
            gas[source]=rest; energy[source]=SignedDyadic.of(energies[energies.length-1]);
            for(int i=offset;i<end;i++) {
                int target=sorted.get(i).target(), part=i-offset;
                incoming[target]=incoming[target].merge(portions.get(part));
                added[target]=added[target].add(SignedDyadic.of(energies[part]));
            }
            offset=end;
        }
        var segments=new ArrayList<PipeSegmentState>(count);
        for(int i=0;i<count;i++) {
            var old=state.segments().get(i);
            SignedDyadic exact=energy[i].add(added[i]);
            double published=exact.toDouble();
            roundoff=roundoff.add(SignedDyadic.of(published).subtract(exact));
            segments.add(new PipeSegmentState(old.id(),old.elevationM(),old.capacityM3(),gas[i].merge(incoming[i]),
                    published,old.deposits()));
        }
        return new Result(new PipeNetworkState(segments,state.connections(),state.materialIds(),state.liquids()),roundoff);
    }
}
