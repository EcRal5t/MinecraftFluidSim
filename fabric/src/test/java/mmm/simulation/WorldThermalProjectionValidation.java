package mmm.simulation;

import java.util.List;
import java.util.Map;
import mmm.fluid.EnvironmentBinding;
import mmm.fluid.air.GasInventory;
import mmm.thermal.*;

/** A shared wall edited elsewhere must refresh both heat and surface stock before a host edit. */
public final class WorldThermalProjectionValidation {
    public static void main(String[] args) {
        String fingerprint="0".repeat(64);
        var key=new EnvironmentThermalState.Key(-2,4,7,EnvironmentThermalState.Kind.SOLID,0);
        var surface=new FixedSurfaceState(FiniteThermalDeviceDefinition.LocalFace.NORTH,"mmm:adsorbent",10,
                Map.of("mmm:co2",37L),Map.of("mmm:co2",.25));
        var wall=new FiniteThermalDeviceState(WorldThermalEnvironment.PROVIDER,"wall","mmm:iron",
                new FiniteThermalBodyState(2,fingerprint,"mmm:iron",100,20));
        var machine=new FiniteThermalDeviceState("mmm:machine","machine","mmm:iron",
                new FiniteThermalBodyState(1,fingerprint,"mmm:iron",100,30));
        var inventory=new FiniteThermalDeviceInventory(fingerprint,List.of(wall,machine));
        var world=EnvironmentThermalState.initialized("minecraft:overworld",fingerprint,
                List.of(new EnvironmentThermalState.Node(key,"mmm:iron",100,1,GasInventory.EMPTY,80,List.of(surface))),List.of());
        var binding=new EnvironmentBinding(world.dimensionId(),List.of(new EnvironmentBinding.DeviceReference(wall.providerId(),wall.objectId(),key)));
        var projected=WorldThermalInventories.project(inventory,binding,world);
        var refreshed=projected.devices().stream().filter(d->d.objectId().equals("wall")).findFirst().orElseThrow();
        if(refreshed.body().internalEnergyJ()!=80 || !refreshed.surfaces().equals(List.of(surface))
                || !projected.devices().contains(machine) || projected.exactInternalEnergy().toDouble()!=110
                || !projected.audit().externalSource().equals(inventory.audit().externalSource())
                || !projected.audit().acceptsInventory(projected.exactInternalEnergy()))
            throw new AssertionError("Shared wall projection overwrote world stock or changed external energy accounting");
        if(inventory.exactInternalEnergy().toDouble()!=50 || world.exactEnergy().toDouble()!=80)
            throw new AssertionError("Projection modified its committed sources");
    }
}
