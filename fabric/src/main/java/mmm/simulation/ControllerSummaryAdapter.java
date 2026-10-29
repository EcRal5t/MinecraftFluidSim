package mmm.simulation;

import mmm.fluid.FluidDomainContracts;

import mmm.api.RegionAvailabilityReason;
import mmm.application.port.ControllerSummaryPort;
import mmm.controller.ControllerSummary;
import mmm.fluid.HostFluidState;
import mmm.fluid.air.GasLimits;
import mmm.granular.GranularLimits;
import mmm.simulation.controller.FluidRegionFootprint;
import mmm.thermal.ThermalLimits;
import mmm.thermal.ThermalRegistry;
import mmm.thermal.ThermalStateFunction;
import mmm.worldprofile.WorldCapabilityProfile;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** Minecraft-side projection into the bounded loader-neutral controller read model. */
final class ControllerSummaryAdapter implements ControllerSummaryPort {
	private final FluidRegionManager regions;
	private final FluidRegionOperations operations;
	private final WorldCapabilityProfile profile;
	private final ThermalRegistry thermalRegistry;
	private final Predicate<UUID> hasPendingContainerWal;
	private final BooleanSupplier authoritativeWritesReady;
	private final boolean worldBridgeEnabled;

	ControllerSummaryAdapter(
			FluidRegionManager regions,
			FluidRegionOperations operations,
			WorldCapabilityProfile profile,
			ThermalRegistry thermalRegistry,
			Predicate<UUID> hasPendingContainerWal,
			BooleanSupplier authoritativeWritesReady,
			boolean worldBridgeEnabled
	) {
		this.regions = Objects.requireNonNull(regions, "regions");
		this.operations = Objects.requireNonNull(operations, "operations");
		this.profile = Objects.requireNonNull(profile, "profile");
		this.thermalRegistry = thermalRegistry;
		this.hasPendingContainerWal = Objects.requireNonNull(hasPendingContainerWal, "hasPendingContainerWal");
		this.authoritativeWritesReady = Objects.requireNonNull(authoritativeWritesReady, "authoritativeWritesReady");
		this.worldBridgeEnabled = worldBridgeEnabled;
	}

	@Override public Optional<View> read(UUID regionId) {
		FluidRegion region = regions.region(regionId);
		if (region == null || region.footprint() == null) return Optional.empty();
		FluidRegionFootprint footprint = region.footprint();
		boolean live = region.solverLoaded();
		var device = live ? region.inventorySummary() : null;
		HostFluidState domain = live && device == null ? region.hostSnapshot() : null;
		long depositLimit = Math.min(GranularLimits.MAX_DEPOSIT_GROUPS_PER_REGION,
				Math.multiplyExact(region.reservedCellCapacity(), GranularLimits.MAX_DEPOSIT_GROUPS_PER_CELL));
		ControllerSummary.Inventory inventory = live
				? device == null ? liveInventory(region, domain) : deviceInventory(device) : ControllerSummary.Inventory.unavailable();
		String ownershipLock = operations.ownershipLockDescription(region);
		boolean containerWalPending = hasPendingContainerWal.test(region.id());
		boolean phaseWorkPending = live && region.hasPendingPhaseMotionWork();
		boolean checkpointAllowed = authoritativeWritesReady.getAsBoolean() && live
				&& region.availabilityFailure() == null && ownershipLock == null && !containerWalPending;
		FluidDomainContracts.StepStats stats = region.lastStats();
		ThermalActivity thermal = region.thermalActivity();
		String availabilityFailure = bounded(region.availabilityFailure());
		String numericalFailure = region.availabilityReason() == RegionAvailabilityReason.NUMERICAL_PAUSE
				? availabilityFailure : null;
		return Optional.of(new View(region.id(), region.dimension().identifier().toString(),
				profile.profileFingerprint(), region.checkpointGeneration(), region.networkGeneration(),
				new ControllerSummary.Footprint(footprint.controllerPosition().getX(),
						footprint.controllerPosition().getY(), footprint.controllerPosition().getZ(),
						footprint.blockSizeX(), footprint.blockSizeY(), footprint.blockSizeZ(),
						footprint.interiorBlocks().size(), footprint.requiredChunks().size()),
				regions.selected() == region,
				region.paused() ? ControllerSummary.OperatorIntent.PAUSED : ControllerSummary.OperatorIntent.RUNNING,
				ControllerSummary.MotionState.valueOf(region.lifecycle().state().name()),
				thermal == null ? ControllerSummary.ThermalState.UNBOUND
						: ControllerSummary.ThermalState.valueOf(thermal.state().name()),
				new ControllerSummary.Availability(region.availabilityReason(), availabilityFailure),
				new ControllerSummary.Usage(
						capacity(region.reservedCellCapacity(), regions.limits().maximumCellsPerRegion()),
						live ? capacity(device==null?domain.granularParcelCount():device.granularParcelCount(), GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION)
								: unavailable(GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION),
						live ? capacity(device==null?domain.granularDepositGroupCount():device.granularDepositCount(), depositLimit) : unavailable(depositLimit),
						live ? capacity(device==null?domain.coherentPhaseGroupCount():device.coherentGroupCount(), ThermalLimits.MAX_COHERENT_GROUPS_PER_REGION)
								: unavailable(ThermalLimits.MAX_COHERENT_GROUPS_PER_REGION),
						live ? capacity(device==null?domain.gasBoundary().nodeCount():device.gasNodeCount(), GasLimits.MAX_NODES_PER_REGION)
								: unavailable(GasLimits.MAX_NODES_PER_REGION),
						live ? capacity(thermalRegistry == null ? 0L
								: device==null?region.finiteThermalDevices(thermalRegistry.fingerprint()).devices().size():device.finiteDeviceCount(),
								ThermalLimits.MAX_FINITE_DEVICES_PER_REGION)
								: unavailable(ThermalLimits.MAX_FINITE_DEVICES_PER_REGION)),
				inventory,
				new ControllerSummary.Gates(ownershipLock == null, containerWalPending, phaseWorkPending,
						worldBridgeEnabled, checkpointAllowed),
				new ControllerSummary.Diagnostics(region.lastStepMilliseconds(),
						stats == null ? 0 : stats.pressureIterations(),
						stats == null ? 0.0 : stats.pressureResidual(), numericalFailure)));
	}

    private static ControllerSummary.Inventory deviceInventory(mmm.fluid.ResidentFluidState.InventorySummary device){
        var range=Double.isFinite(device.minimumTemperatureK())?ControllerSummary.TemperatureRange.available(device.minimumTemperatureK(),device.maximumTemperatureK()):ControllerSummary.TemperatureRange.unavailable();
        return new ControllerSummary.Inventory(true,device.liquidVolumeCubicMetres(),mmm.units.Mass.kilograms(device.liquidMassUnits()),device.chemicalTotalQuanta(),
            device.granularSolidVolumeSvq(),mmm.units.Mass.kilograms(device.granularMassUnits()),device.coherentSolidVolumeSvq(),mmm.units.Mass.kilograms(device.coherentMassUnits()),
            device.gasStandardVolumeNanos(),mmm.units.Mass.kilograms(device.gasMassUnits()),device.thermalBound(),device.totalInternalEnergyJoules(),range);
    }

	private ControllerSummary.Inventory liveInventory(FluidRegion region, HostFluidState domain) {
		long gasNanos = 0L;
		double gasMass = 0.0;
		double minimumTemperature = Double.POSITIVE_INFINITY;
		double maximumTemperature = Double.NEGATIVE_INFINITY;
		var carrierRange = domain.observedCarrierTemperatureRange();
		if (carrierRange != null) {
			minimumTemperature = carrierRange.minimumK();
			maximumTemperature = carrierRange.maximumK();
		}
		for (int node : domain.gasBoundary().nodeIds()) {
			var gas = domain.gasBoundary().inventory(node);
			gasNanos = Math.addExact(gasNanos, gas.standardVolumeNanos());
			for (var component : gas.components()) gasMass += component.mass();
			if (domain.hasThermalState() && !gas.isEmpty()) {
				double temperature = domain.gasNodeTemperatureK(node);
				minimumTemperature = Math.min(minimumTemperature, temperature);
				maximumTemperature = Math.max(maximumTemperature, temperature);
			}
		}
		if (!Double.isFinite(gasMass)) throw new ArithmeticException("Controller gas mass summary overflow");
		double totalInternalEnergy = Double.NaN;
		if (domain.hasThermalState()) {
			totalInternalEnergy = domain.totalInternalEnergy();
			if (thermalRegistry != null) {
				var devices = region.finiteThermalDevices(thermalRegistry.fingerprint());
				totalInternalEnergy += devices.exactInternalEnergy().toDouble();
				ThermalStateFunction stateFunction = new ThermalStateFunction(thermalRegistry);
				for (var device : devices.devices()) {
					var body = device.body();
					double temperature = stateFunction.stateForInternalEnergy(
							thermalRegistry.materialHandle(body.thermalMaterialId()), body.massKilograms(),
							Map.of(), body.internalEnergyJ()).temperatureK();
					minimumTemperature = Math.min(minimumTemperature, temperature);
					maximumTemperature = Math.max(maximumTemperature, temperature);
				}
			}
			if (!Double.isFinite(totalInternalEnergy)) {
				throw new ArithmeticException("Controller total internal-energy summary overflow");
			}
		}
		ControllerSummary.TemperatureRange temperatureRange = minimumTemperature == Double.POSITIVE_INFINITY
				? ControllerSummary.TemperatureRange.unavailable()
				: ControllerSummary.TemperatureRange.available(minimumTemperature, maximumTemperature);
		return new ControllerSummary.Inventory(true,
				domain.physicalVolumeCubicMetres(domain.totalVolumeExact()),
				mmm.units.Mass.kilograms(domain.totalMassUnits()), domain.totalChemicalSpeciesQuanta(),
				domain.totalGranularSolidVolumeSvq(), domain.totalGranularMass(),
				domain.totalCoherentSolidVolumeSvq(), domain.totalCoherentMass(), gasNanos, gasMass,
				domain.hasThermalState(), totalInternalEnergy, temperatureRange);
	}

	private static ControllerSummary.Capacity capacity(long used, long limit) {
		return ControllerSummary.Capacity.available(used, limit);
	}

	private static ControllerSummary.Capacity unavailable(long limit) {
		return ControllerSummary.Capacity.unavailable(limit);
	}

	private static String bounded(String value) {
		if (value == null || value.length() <= 1_024) return value;
		return value.substring(0, 1_021) + "...";
	}
}
