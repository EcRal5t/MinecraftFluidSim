package mmm.pipe;

import java.util.Map;

/** Accepted world geometry, stored with the inventory that occupies its cavity. */
public record PipeInstallation(String blockId, Map<String,String> properties) {
    public PipeInstallation {
        if (blockId == null || blockId.isBlank()) throw new IllegalArgumentException("Missing pipe block ID");
        properties = Map.copyOf(properties);
    }
}
