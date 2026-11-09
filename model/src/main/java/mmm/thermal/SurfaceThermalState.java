package mmm.thermal;

import java.util.Map;

/** The finite wall and its adsorbates share one temperature and one internal-energy inventory. */
public final class SurfaceThermalState {
	private SurfaceThermalState() { }

	public static double energyAtTemperature(ThermalRegistry registry, FiniteThermalDeviceState device, double temperatureK) {
		var body = device.body();
		double energy = new ThermalStateFunction(registry).internalEnergyAtTemperature(
				registry.materialHandle(body.thermalMaterialId()), body.massKilograms(), Map.of(), temperatureK);
		for (var surface : device.surfaces()) energy += surfaceEnergyAtTemperature(registry, surface, temperatureK);
		if (!Double.isFinite(energy)) throw new IllegalArgumentException("Surface energy exceeds its representation");
		return energy;
	}

	public static double surfaceEnergyAtTemperature(ThermalRegistry registry, FixedSurfaceState surface, double temperatureK) {
		if (!(Double.isFinite(temperatureK) && temperatureK >= ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K))
			throw new IllegalArgumentException("Invalid surface temperature");
		var material = registry.surfaceMaterial(surface.materialId());
		double energy = mmm.units.Mass.kilograms(surface.loadingMassUnits()) * (material.referenceSpecificEnergyJPerKg()
				+ material.specificHeatJPerKgK() * (temperatureK - material.referenceTemperatureK()));
		for (var entry : surface.speciesQuanta().entrySet()) {
			var adsorbate = material.adsorbates().stream().filter(value -> value.speciesId().equals(entry.getKey()))
					.findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown surface adsorbate " + entry.getKey()));
			double moles = entry.getValue() / (double) mmm.chemistry.ChemicalLimits.QUANTA_PER_MOL;
			energy += moles * (adsorbate.referenceEnergyJPerMol()
					+ adsorbate.heatCapacityJPerMolK() * (temperatureK - adsorbate.referenceTemperatureK()));
		}
		if (!Double.isFinite(energy)) throw new IllegalArgumentException("Surface energy exceeds its representation");
		return energy;
	}

	public static FiniteThermalDeviceState attach(ThermalRegistry registry, FiniteThermalDeviceState device,
			FixedSurfaceState surface, double surfaceEnergyJ) {
		if (!Double.isFinite(surfaceEnergyJ)) throw new IllegalArgumentException("Invalid surface energy");
		registry.surfaceMaterial(surface.materialId());
		var surfaces = new java.util.ArrayList<>(device.surfaces());
		int existing = -1;
		for (int i = 0; i < surfaces.size(); i++) if (surfaces.get(i).face() == surface.face()) { existing = i; break; }
		if (existing < 0) surfaces.add(surface);
		else {
			var current = surfaces.get(existing);
			if (!current.materialId().equals(surface.materialId()))
				throw new IllegalArgumentException("Remove the installed surface before changing its material");
			var species = new java.util.TreeMap<String, Long>(current.speciesQuanta());
			for (var entry : surface.speciesQuanta().entrySet()) species.merge(entry.getKey(), entry.getValue(), Math::addExact);
			var residuals = new java.util.TreeMap<String, Double>(current.residualQuanta());
			for (var entry : surface.residualQuanta().entrySet()) residuals.merge(entry.getKey(), entry.getValue(), Double::sum);
			surfaces.set(existing, new FixedSurfaceState(current.face(), current.materialId(),
					Math.addExact(current.loadingMassUnits(), surface.loadingMassUnits()), species, residuals));
		}
		var body = device.body();
		var updated = new FiniteThermalDeviceState(device.providerId(), device.objectId(), device.deviceTypeId(),
				new FiniteThermalBodyState(Math.addExact(body.generation(), 1), body.thermalRegistryFingerprint(),
						body.thermalMaterialId(), body.massUnits(), body.internalEnergyJ() + surfaceEnergyJ), surfaces);
		temperature(registry, updated);
		return updated;
	}

	public record Detached(FiniteThermalDeviceState device, FixedSurfaceState surface, double surfaceEnergyJ) { }

	public static Detached detach(ThermalRegistry registry, FiniteThermalDeviceState device,
			FiniteThermalDeviceDefinition.LocalFace face) {
		var surface = device.surfaces().stream().filter(value -> value.face() == face).findFirst()
				.orElseThrow(() -> new IllegalArgumentException("Fixed surface is not installed"));
		double energy = surfaceEnergyAtTemperature(registry, surface, temperature(registry, device));
		var surfaces = new java.util.ArrayList<>(device.surfaces());
		surfaces.remove(surface);
		var body = device.body();
		var updated = new FiniteThermalDeviceState(device.providerId(), device.objectId(), device.deviceTypeId(),
				new FiniteThermalBodyState(Math.addExact(body.generation(), 1), body.thermalRegistryFingerprint(),
						body.thermalMaterialId(), body.massUnits(), body.internalEnergyJ() - energy), surfaces);
		temperature(registry, updated);
		return new Detached(updated, surface, energy);
	}

	public static double temperature(ThermalRegistry registry, FiniteThermalDeviceState device) {
		if (device.surfaces().isEmpty())
			return new ThermalStateFunction(registry).stateForInternalEnergy(registry.materialHandle(device.body().thermalMaterialId()),
					device.body().massKilograms(), Map.of(), device.body().internalEnergyJ()).temperatureK();
		double target = device.body().internalEnergyJ(), low = ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K, high = 300;
		if (energyAtTemperature(registry, device, low) > target)
			throw new ThermalStateFunction.EnergyOutsideValidityException();
		while (energyAtTemperature(registry, device, high) < target) {
			high *= 2;
			if (!Double.isFinite(high)) throw new ThermalStateFunction.EnergyRepresentationException();
		}
		for (int i = 0; i < 64; i++) {
			double middle = (low + high) * 0.5;
			if (energyAtTemperature(registry, device, middle) < target) low = middle; else high = middle;
		}
		return (low + high) * 0.5;
	}
}
