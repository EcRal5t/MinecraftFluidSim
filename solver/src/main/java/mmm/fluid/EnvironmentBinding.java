package mmm.fluid;

import mmm.thermal.EnvironmentThermalState;
import java.util.List;
import java.util.Objects;

/** Region thermal device rows that project world-owned inventories into a private GPU candidate. */
public record EnvironmentBinding(String dimensionId, List<DeviceReference> devices, List<GasReference> gas) {
    public EnvironmentBinding(String dimensionId,List<DeviceReference> devices){this(dimensionId,devices,List.of());}
    public record DeviceReference(String providerId, String objectId, EnvironmentThermalState.Key node) {
        public DeviceReference {
            Objects.requireNonNull(providerId); Objects.requireNonNull(objectId); Objects.requireNonNull(node);
        }
    }
    public record GasReference(int cell, int direction, EnvironmentThermalState.Key node, double areaFraction) {
        public GasReference {
            Objects.requireNonNull(node);
            if(cell<0||direction<0||direction>5||node.kind()!=EnvironmentThermalState.Kind.AIR
                    || !(Double.isFinite(areaFraction)&&areaFraction>0&&areaFraction<=1))
                throw new IllegalArgumentException("Invalid environment gas contact");
        }
    }
    public EnvironmentBinding {
        Objects.requireNonNull(dimensionId);
        devices = List.copyOf(devices);
        gas = List.copyOf(gas);
    }
}
