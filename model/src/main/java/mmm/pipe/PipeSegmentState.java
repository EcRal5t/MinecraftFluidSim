package mmm.pipe;

import java.util.List;
import java.util.Objects;
import mmm.fluid.air.GasInventory;
import mmm.granular.GranularTransferLedger;

/** Persistent inventories and physical capacity of one pipe cavity. */
public record PipeSegmentState(String id, double elevationM, double capacityM3, GasInventory gas, double gasInternalEnergy,
                               List<GranularTransferLedger.Portion> deposits) {
    public PipeSegmentState {
        if (id == null || id.isBlank() || !Double.isFinite(elevationM) || !(Double.isFinite(capacityM3) && capacityM3 > 0)
                || gas == null || !Double.isFinite(gasInternalEnergy) || gas.isEmpty() && gasInternalEnergy != 0)
            throw new IllegalArgumentException("Invalid pipe segment");
        deposits = List.copyOf(deposits);
    }
}
