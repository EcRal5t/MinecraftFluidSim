package mmm.pipe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import mmm.chemistry.ChemicalRegistry;
import mmm.inventory.LiquidCellInventory;
import mmm.inventory.LiquidTransportRoutes;
import mmm.units.SignedDyadic;

/** One simultaneous liquid routing candidate over an immutable pipe network. */
public final class PipeLiquidTransport {
    private PipeLiquidTransport() { }
    public record Move(int connection, LiquidCellInventory.Key source, int target, SignedDyadic volume) {
        public Move {
            if (connection < 0 || source == null || target < 0 || volume == null || volume.signum() <= 0)
                throw new IllegalArgumentException("Invalid pipe liquid move");
        }
    }
    public record VolumeMove(int connection, int source, int target, SignedDyadic volume) {
        public VolumeMove {
            if (connection < 0 || source < 0 || target < 0 || volume == null || volume.signum() <= 0)
                throw new IllegalArgumentException("Invalid pipe volume move");
        }
    }
    public record Result(PipeNetworkState state, SignedDyadic energyRoundoff) { }

    /** Distribute each requested volume across the source materials without creating inventory. */
    public static Result routeVolumes(PipeNetworkState state, List<VolumeMove> requests, ChemicalRegistry chemistry,
                                      LiquidCellInventory.EnergyPartitioner caloric) {
        var remaining=new ArrayList<SignedDyadic[]>();
        for(var row:state.liquids().rows())remaining.add(row.stream().map(LiquidCellInventory.Stock::volume).toArray(SignedDyadic[]::new));
        var moves=new ArrayList<Move>();
        for(var request:requests) {
            if(request.source()>=state.segments().size())throw new IllegalArgumentException("Pipe source outside network");
            var row=state.liquids().rows().get(request.source());var available=remaining.get(request.source());
            double[] preference=new double[available.length];
            for(int i=0;i<available.length;i++)preference[i]=available[i].toDouble();
            var portions=LiquidCellInventory.allocateVolumes(request.volume(),available,preference);
            for(int i=0;i<portions.length;i++)if(portions[i].signum()>0) {
                moves.add(new Move(request.connection(),row.get(i).key(),request.target(),portions[i]));
                available[i]=available[i].subtract(portions[i]);
            }
        }
        return route(state,moves,chemistry,caloric);
    }
    public static Result route(PipeNetworkState state, List<Move> moves, ChemicalRegistry chemistry,
                                         LiquidCellInventory.EnergyPartitioner caloric) {
        var inventory = new LiquidCellInventory(state.liquids(), chemistry);
        var sorted = new ArrayList<>(moves);
        sorted.sort(Comparator.comparing(Move::source).thenComparingInt(Move::connection).thenComparingInt(Move::target));
        var builder = LiquidTransportRoutes.builder(sorted.size(), sorted.size());
        LiquidCellInventory.Key previous = null;
        for (var move : sorted) {
            if (move.connection() >= state.connections().size()) throw new IllegalArgumentException("Unknown pipe connection");
            var edge = state.connections().get(move.connection());
            int from = move.source().cell();
            if (edge.opening() == 0 || !(edge.first() == from && edge.second() == move.target()
                    || edge.second() == from && edge.first() == move.target()))
                throw new IllegalArgumentException("Pipe route does not follow an open connection");
            if (!move.source().equals(previous)) {
                var stock = inventory.stock(move.source());
                if (stock == null) throw new IllegalArgumentException("Pipe liquid source is missing");
                builder.source(stock); previous = move.source();
            }
            builder.route(move.connection(),move.target(),255,move.volume());
        }
        var plan = inventory.planRoutes(builder.build(),caloric);
        return new Result(new PipeNetworkState(state.segments(),state.connections(),state.materialIds(),plan.future().snapshot()),plan.energyRoundoff());
    }
}
