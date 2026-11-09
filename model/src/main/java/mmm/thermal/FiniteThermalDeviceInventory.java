package mmm.thermal;

import mmm.units.SignedDyadic;

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
		if (audit == null || !audit.acceptsInventory(exactInternalEnergy(devices))) {
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

	/** Adsorbate totals join the region's gas, liquid and solid inventories during material accounting. */
	public java.util.Map<String, Long> adsorbedSpeciesQuanta() {
		var totals = new java.util.TreeMap<String, Long>();
		for (var device : devices) for (var surface : device.surfaces())
			for (var entry : surface.speciesQuanta().entrySet())
				totals.merge(entry.getKey(), entry.getValue(), Math::addExact);
		return java.util.Collections.unmodifiableMap(totals);
	}

	public long adsorbedMassUnits(mmm.chemistry.ChemicalRegistry chemistry) {
		long mass = 0;
		for (var entry : adsorbedSpeciesQuanta().entrySet())
			mass = Math.addExact(mass, chemistry.speciesMassUnits(chemistry.speciesHandle(entry.getKey()), entry.getValue()));
		return mass;
	}

	public long surfaceLoadingMassUnits() {
		long mass = 0;
		for (var device : devices) for (var surface : device.surfaces()) mass = Math.addExact(mass, surface.loadingMassUnits());
		return mass;
	}

	public java.util.Map<String, Long> adsorbedElementQuanta(mmm.chemistry.ChemicalRegistry chemistry) {
		var totals = new java.util.TreeMap<String, Long>();
		for (var entry : adsorbedSpeciesQuanta().entrySet()) {
			var species = chemistry.species(chemistry.speciesHandle(entry.getKey()));
			for (var element : species.composition().entrySet())
				totals.merge(element.getKey(), Math.multiplyExact(entry.getValue(), element.getValue().longValue()), Math::addExact);
		}
		return java.util.Collections.unmodifiableMap(totals);
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
				current.deviceTypeId(), plan.after(), current.surfaces()));
		SignedDyadic fluidDelta = SignedDyadic.ZERO;
		for (FiniteThermalExchange.ContactDelta contact : plan.contacts()) {
			fluidDelta = fluidDelta.add(SignedDyadic.of(contact.internalEnergyDeltaJ()));
		}
		return new FiniteThermalDeviceInventory(thermalRegistryFingerprint, candidate,
				audit.withFluidExchange(fluidDelta, plan.roundoffResidual()));
	}

	public FiniteThermalDeviceInventory insertExternal(FiniteThermalDeviceState device) {
		if (devices.stream().anyMatch(current -> current.sameObject(device))) {
			throw new IllegalArgumentException("Finite thermal device is already installed");
		}
		var future = new ArrayList<>(devices);
		future.add(device);
		return new FiniteThermalDeviceInventory(thermalRegistryFingerprint, future,
				new FiniteThermalDeviceAudit(audit.initialInventory(),
						audit.externalSource().add(SignedDyadic.of(device.body().internalEnergyJ())),
						audit.externalSink(), audit.solverExchange(), audit.roundoffResidual()));
	}

	public FiniteThermalDeviceInventory extractExternal(String providerId, String objectId) {
		var future = new ArrayList<>(devices);
		var removed = future.stream().filter(device -> device.providerId().equals(providerId)
				&& device.objectId().equals(objectId)).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Finite thermal device is not installed"));
		future.remove(removed);
		return new FiniteThermalDeviceInventory(thermalRegistryFingerprint, future,
				new FiniteThermalDeviceAudit(audit.initialInventory(), audit.externalSource(),
						audit.externalSink().add(SignedDyadic.of(removed.body().internalEnergyJ())),
						audit.solverExchange(), audit.roundoffResidual()));
	}

	public void requireCompatible(ThermalRegistry registry) {
		if (registry == null || !thermalRegistryFingerprint.equals(registry.fingerprint())) {
			throw new IllegalArgumentException("Finite thermal device registry changed");
		}
		for (FiniteThermalDeviceState device : devices) {
			int materialHandle = registry.materialHandle(device.body().thermalMaterialId());
			ThermalMaterialDefinition material = registry.material(materialHandle);
			if (material.carrierKind() != ThermalMaterialDefinition.CarrierKind.DEVICE
					|| !material.carrierId().equals(device.deviceTypeId())) {
				throw new IllegalArgumentException("Finite thermal device type/material binding changed");
			}
			for (var surface : device.surfaces()) {
				var definition = registry.surfaceMaterial(surface.materialId());
				var allowed = definition.adsorbates().stream().map(SurfaceMaterialDefinition.Adsorbate::speciesId)
						.collect(java.util.stream.Collectors.toSet());
				if (!allowed.containsAll(surface.speciesQuanta().keySet()) || !allowed.containsAll(surface.residualQuanta().keySet()))
					throw new IllegalArgumentException("Surface inventory contains an undeclared species");
				long total = 0;
				for (long amount : surface.speciesQuanta().values()) total = Math.addExact(total, amount);
				if (total / (double) mmm.chemistry.ChemicalLimits.QUANTA_PER_MOL
						> mmm.units.Mass.kilograms(surface.loadingMassUnits()) * definition.siteCapacityMolesPerKg())
					throw new IllegalArgumentException("Surface adsorbates exceed site capacity");
			}
			SurfaceThermalState.temperature(registry, device);
		}
	}

	private static SignedDyadic exactInternalEnergy(List<FiniteThermalDeviceState> devices) {
		if (devices == null) throw new IllegalArgumentException("Finite thermal device inventory is missing");
		SignedDyadic total = SignedDyadic.ZERO;
		for (FiniteThermalDeviceState device : devices) {
			if (device == null) throw new IllegalArgumentException("Finite thermal device inventory contains null");
			total = total.add(SignedDyadic.of(device.body().internalEnergyJ()));
		}
		return total;
	}
}
