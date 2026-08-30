package cc.ecisr.mmm.thermal;

import cc.ecisr.mmm.units.SignedDyadic;

import java.util.ArrayList;
import java.util.List;

/** Canonical region-owned finite-device truth; provider objects are locators, never energy owners. */
public record FiniteThermalDeviceInventory(
		String thermalRegistryFingerprint,
		List<FiniteThermalDeviceState> devices,
		FiniteThermalDeviceAudit audit
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
		if (audit == null || !exactInternalEnergy(devices).equals(audit.expectedInventory())) {
			throw new IllegalArgumentException("Finite thermal device energy audit does not close");
		}
	}

	public FiniteThermalDeviceInventory(String thermalRegistryFingerprint, List<FiniteThermalDeviceState> devices) {
		this(thermalRegistryFingerprint, devices,
				FiniteThermalDeviceAudit.initialized(exactInternalEnergy(devices)));
	}

	public static FiniteThermalDeviceInventory empty(String thermalRegistryFingerprint) {
		return new FiniteThermalDeviceInventory(thermalRegistryFingerprint, List.of());
	}

	public SignedDyadic exactInternalEnergy() {
		return exactInternalEnergy(devices);
	}

	public FiniteThermalDeviceInventory applyExchange(
			String providerId, String objectId, FiniteThermalExchange.Plan plan
	) {
		if (plan == null) throw new IllegalArgumentException("Finite thermal exchange plan is missing");
		ArrayList<FiniteThermalDeviceState> candidate = new ArrayList<>(devices);
		int match = -1;
		for (int index = 0; index < candidate.size(); index++) {
			FiniteThermalDeviceState device = candidate.get(index);
			if (device.providerId().equals(providerId) && device.objectId().equals(objectId)) {
				if (match >= 0) throw new IllegalStateException("Finite thermal device identity is not unique");
				match = index;
			}
		}
		if (match < 0) throw new IllegalArgumentException("Unknown finite thermal device object");
		FiniteThermalDeviceState current = candidate.get(match);
		if (!current.body().equals(plan.before())) {
			throw new IllegalArgumentException("Finite thermal exchange plan is stale");
		}
		candidate.set(match, new FiniteThermalDeviceState(current.providerId(), current.objectId(),
				current.deviceTypeId(), plan.after()));
		SignedDyadic fluidDelta = SignedDyadic.ZERO;
		for (FiniteThermalExchange.ContactDelta contact : plan.contacts()) {
			fluidDelta = fluidDelta.add(SignedDyadic.of(contact.internalEnergyDeltaEu()));
		}
		return new FiniteThermalDeviceInventory(thermalRegistryFingerprint, candidate,
				audit.withFluidExchange(fluidDelta, plan.roundoffResidual()));
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

	private static SignedDyadic exactInternalEnergy(List<FiniteThermalDeviceState> devices) {
		if (devices == null) throw new IllegalArgumentException("Finite thermal device inventory is missing");
		SignedDyadic total = SignedDyadic.ZERO;
		for (FiniteThermalDeviceState device : devices) {
			if (device == null) throw new IllegalArgumentException("Finite thermal device inventory contains null");
			total = total.add(SignedDyadic.of(device.body().internalEnergyEu()));
		}
		return total;
	}
}
