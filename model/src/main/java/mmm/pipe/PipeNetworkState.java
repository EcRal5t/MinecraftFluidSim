package mmm.pipe;

import java.util.HashSet;
import java.util.List;
import mmm.inventory.LiquidCellInventory;

/** Segment ordering gives liquid inventory cell indices. */
public record PipeNetworkState(List<PipeSegmentState> segments, List<PipeConnection> connections, List<String> materialIds,
                               LiquidCellInventory.Snapshot liquids) {
    public PipeNetworkState {
        segments = List.copyOf(segments);
        connections = List.copyOf(connections);
        materialIds = List.copyOf(materialIds);
        if (new HashSet<>(materialIds).size() != materialIds.size()) throw new IllegalArgumentException("Duplicate material ID");
        if (segments.isEmpty() || liquids == null || liquids.rows().size() != segments.size())
            throw new IllegalArgumentException("Pipe segments and liquid rows differ");
        var ids = new HashSet<String>();
        for (var segment : segments) if (!ids.add(segment.id())) throw new IllegalArgumentException("Duplicate pipe segment");
        for (var row : liquids.rows()) for (var stock : row)
            if (stock.key().material() >= materialIds.size() || stock.key().supportMask() != 255)
                throw new IllegalArgumentException("Pipe liquid material or support is invalid");
        for (var connection : connections) {
            if (connection.first() >= segments.size() || connection.second() >= segments.size())
                throw new IllegalArgumentException("Pipe connection outside network");
            double rise = segments.get(connection.second()).elevationM() - segments.get(connection.first()).elevationM();
            if (Math.abs(rise - connection.riseM()) > 1e-9)
                throw new IllegalArgumentException("Pipe connection elevation differs from segments");
        }
    }
}
