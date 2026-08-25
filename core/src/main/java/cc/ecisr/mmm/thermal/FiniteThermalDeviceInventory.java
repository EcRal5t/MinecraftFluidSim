package cc.ecisr.mmm.thermal;

import java.util.ArrayList;
import java.util.List;

/** Canonical region-owned finite-device truth; provider objects are locators, never energy owners. */
public record FiniteThermalDeviceInventory(
		String thermalRegistryFingerprint,
		List<FiniteThermalDeviceState> devices
) {
	public FiniteThermalDeviceInventory {
		if (thermalRegistryFingerprint == null || !thermalRegistryFingerprint.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException("Finite thermal device registry fingerprint is invalid");
		}
		if (devices == null || devices.size() > ThermalLimits.MAX_FINITE_DEVICES_PER_REGION) {
			throw new IllegalArgumentException("Finite thermal device inventory is missing or over budget");
		}
		ArrayList<FiniteThermalDeviceState> canonical = new ArrayList<>(devices);
		if (canonical.stream().anyMatch(java.util.Objects::isNull)) {
			throw new IllegalArgumentException("Finite thermal device inventory contains null");
		}
		canonical.sort(null);
		for (int index = 0; index < canonical.size(); index++) {
			FiniteThermalDeviceState device = canonical.get(index);
			if (!thermalRegistryFingerprint.equals(device.body().thermalRegistryFingerprint())) {
				throw new IllegalArgumentException("Finite thermal device registry identities disagree");
			}
			if (index > 0 && canonical.get(index - 1).sameObject(device)) {
				throw new IllegalArgumentException("Duplicate finite thermal device object identity");
			}
		}
		devices = List.copyOf(canonical);
	}

	public static FiniteThermalDeviceInventory empty(String thermalRegistryFingerprint) {
		return new FiniteThermalDeviceInventory(thermalRegistryFingerprint, List.of());
	}

	public SignedDyadic exactInternalEnergy() {
		SignedDyadic total = SignedDyadic.ZERO;
		for (FiniteThermalDeviceState device : devices) {
			total = total.add(SignedDyadic.of(device.body().internalEnergyEu()));
		}
		return total;
	}

	public void requireCompatible(ThermalRegistry registry) {
		if (registry == null || !thermalRegistryFingerprint.equals(registry.fingerprint())) {
			throw new IllegalArgumentException("Finite thermal device registry changed");
		}
		ThermalStateFunction stateFunction = new ThermalStateFunction(registry);
		for (FiniteThermalDeviceState device : devices) {
			int materialHandle = registry.materialHandle(device.body().thermalMaterialId());
			ThermalMaterialDefinition material = registry.material(materialHandle);
			if (material.carrierKind() != ThermalMaterialDefinition.CarrierKind.DEVICE
					|| !material.carrierId().equals(device.deviceTypeId())) {
				throw new IllegalArgumentException("Finite thermal device type/material binding changed");
			}
			stateFunction.stateForInternalEnergy(materialHandle,
					device.body().massKilograms(), java.util.Map.of(), device.body().internalEnergyEu());
		}
	}
}
