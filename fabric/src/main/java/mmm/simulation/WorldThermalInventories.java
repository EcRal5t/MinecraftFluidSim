package mmm.simulation;

import java.util.ArrayList;
import java.util.HashMap;
import mmm.fluid.EnvironmentBinding;
import mmm.thermal.EnvironmentThermalState;
import mmm.thermal.FiniteThermalBodyState;
import mmm.thermal.FiniteThermalDeviceAudit;
import mmm.thermal.FiniteThermalDeviceInventory;
import mmm.thermal.FiniteThermalDeviceState;
import mmm.units.SignedDyadic;

/** Refreshes wall projections before a host observation or external inventory edit. */
final class WorldThermalInventories {
    private WorldThermalInventories() { }

    static FiniteThermalDeviceInventory project(FiniteThermalDeviceInventory inventory,
                                                EnvironmentBinding binding, EnvironmentThermalState world) {
        if (binding == null) return inventory;
        if (!binding.dimensionId().equals(world.dimensionId()))
            throw new IllegalArgumentException("Wall projection belongs to another dimension");
        var nodes = new HashMap<EnvironmentThermalState.Key, EnvironmentThermalState.Node>();
        for (var node : world.nodes()) nodes.put(node.key(), node);
        var references = new HashMap<String, EnvironmentThermalState.Key>();
        for (var reference : binding.devices())
            references.put(reference.providerId() + "\0" + reference.objectId(), reference.node());
        var devices = new ArrayList<FiniteThermalDeviceState>(inventory.devices().size());
        var delta = SignedDyadic.ZERO;
        for (var device : inventory.devices()) {
            var key = references.remove(device.providerId() + "\0" + device.objectId());
            if (key == null) { devices.add(device); continue; }
            var node = nodes.get(key);
            if (node == null) throw new IllegalStateException("Projected world wall is absent");
            var body = device.body();
            if (!body.thermalMaterialId().equals(node.materialId()) || body.massUnits() != node.massUnits())
                throw new IllegalStateException("Projected wall geometry needs refresh");
            delta = delta.add(SignedDyadic.of(node.internalEnergyJ()).subtract(SignedDyadic.of(body.internalEnergyJ())));
            devices.add(new FiniteThermalDeviceState(device.providerId(), device.objectId(), device.deviceTypeId(),
                    new FiniteThermalBodyState(body.generation(), body.thermalRegistryFingerprint(), node.materialId(),
                            node.massUnits(), node.internalEnergyJ()), node.surfaces()));
        }
        if (!references.isEmpty()) throw new IllegalStateException("Projected world wall has no region reference");
        var audit = inventory.audit();
        return new FiniteThermalDeviceInventory(inventory.thermalRegistryFingerprint(), devices,
                new FiniteThermalDeviceAudit(audit.initialInventory().add(delta), audit.externalSource(),
                        audit.externalSink(), audit.solverExchange(), audit.roundoffResidual()));
    }
}
