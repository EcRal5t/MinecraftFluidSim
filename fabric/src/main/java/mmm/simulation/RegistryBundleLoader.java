package mmm.simulation;

import mmm.chemistry.ChemicalDataPackLoader;
import mmm.chemistry.ChemicalLimits;
import mmm.chemistry.ChemicalRegistry;
import mmm.fluid.BuiltinFluidMaterials;
import mmm.fluid.MaterialRegistry;
import mmm.fluid.air.AirNetwork;
import mmm.fluid.air.BuiltinGasDefinitions;
import mmm.fluid.air.GasRegistry;
import mmm.granular.GranularDataPackLoader;
import mmm.granular.GranularRegistry;
import mmm.simulation.persistence.WorldCapabilityBundleStore;
import mmm.thermal.ThermalDataPackLoader;
import mmm.thermal.ThermalMaterialDefinition;
import mmm.thermal.ThermalRegistry;
import mmm.worldprofile.WorldCapability;
import mmm.worldprofile.WorldCapabilityProfile;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.util.EnumSet;
import java.util.Objects;

/** Loads current datapack definitions and validates the frozen registry bundle used by one server runtime. */
public final class RegistryBundleLoader {
	private RegistryBundleLoader() { }

	public static WorldCapabilityBundleStore.Definitions loadDefinitions(MinecraftServer server) throws IOException {
		Objects.requireNonNull(server, "Minecraft server");
		MaterialRegistry material = BuiltinFluidMaterials.createRegistry();
		ChemicalRegistry chemical = ChemicalDataPackLoader.load(server.getResourceManager());
		GranularRegistry granular = GranularDataPackLoader.load(server.getResourceManager(), chemical);
		GasRegistry gas = BuiltinGasDefinitions.createRegistry();
		ThermalRegistry thermal = ThermalDataPackLoader.load(server.getResourceManager(), chemical.fingerprint(),
				granular.fingerprint(), gas.fingerprint());
		return new WorldCapabilityBundleStore.Definitions(EnumSet.allOf(WorldCapability.class),
				material, chemical, granular, gas, thermal);
	}

	/** Cross-registry invariants are checked once, before any runtime component can observe the bundle. */
	static RegistryBundle validateAndExtract(WorldCapabilityBundleStore.Bundle bundle) {
		Objects.requireNonNull(bundle, "World capability bundle");
		validate(bundle.material(), bundle.chemical(), bundle.granular(), bundle.gas(), bundle.thermal());
		return new RegistryBundle(bundle.profile(), bundle.material(), bundle.chemical(), bundle.granular(),
				bundle.gas(), bundle.thermal());
	}

	/** Invalid current definitions must fail before first publication, never poison immutable world authority. */
	static WorldCapabilityBundleStore.Definitions validateDefinitions(
			WorldCapabilityBundleStore.Definitions definitions
	) {
		Objects.requireNonNull(definitions, "Registry definitions");
		validate(definitions.material(), definitions.chemical(), definitions.granular(), definitions.gas(), definitions.thermal());
		return definitions;
	}

	private static void validate(
			MaterialRegistry material, ChemicalRegistry chemical, GranularRegistry granular, GasRegistry gas, ThermalRegistry thermal
	) {
		validateSupportedChemicalMaterials(chemical);
		validateSupportedGranularCarriers(granular);
		validateSupportedGasComponents(gas, chemical);
		validateSupportedThermalCarriers(material, thermal, chemical, granular, gas);
	}

	private static void validateSupportedChemicalMaterials(ChemicalRegistry registry) {
		for (var material : BuiltinFluidMaterials.DEFAULT_PALETTE) registry.phaseHandleForMaterial(material.id());
	}

	private static void validateSupportedGranularCarriers(GranularRegistry registry) {
		for (var material : BuiltinFluidMaterials.DEFAULT_PALETTE) {
			if (!registry.hasCarrier(material.id())) throw new IllegalArgumentException(
					"Granular registry has no carrier for " + material.id());
		}
	}

	private static void validateSupportedGasComponents(GasRegistry registry, ChemicalRegistry chemicalRegistry) {
		registry.component(BuiltinGasDefinitions.AIR);
		registry.component(BuiltinGasDefinitions.WATER_VAPOR);
		for (var component : registry.components()) for (String species : component.allowedSpecies()) {
			if (!chemicalRegistry.hasSpecies(species)) {
				throw new IllegalArgumentException("Gas component " + component.id()
						+ " allows unavailable chemical species " + species);
			}
		}
	}

	private static void validateSupportedThermalCarriers(
			MaterialRegistry definitions, ThermalRegistry registry, ChemicalRegistry chemicalRegistry,
			GranularRegistry granularRegistry, GasRegistry gasRegistry
	) {
		for (var material : BuiltinFluidMaterials.DEFAULT_PALETTE) {
			registry.materialHandle(ThermalMaterialDefinition.CarrierKind.LIQUID, material.id());
			if (definitions.entry(material.id()).thermalExpansion() == null) throw new IllegalArgumentException(
					"World material " + material.id() + " has no Boussinesq definition");
		}
		for (var material : granularRegistry.materials()) {
			registry.materialHandle(ThermalMaterialDefinition.CarrierKind.GRANULAR, material.id());
		}
		for (var component : gasRegistry.components()) {
			int host = registry.materialHandle(ThermalMaterialDefinition.CarrierKind.GAS, component.id());
			for (String species : component.allowedSpecies()) registry.speciesOffset(host, species);
		}
		for (var phase : chemicalRegistry.phases()) {
			int host = registry.materialHandle(ThermalMaterialDefinition.CarrierKind.LIQUID, phase.materialId());
			for (String species : phase.allowedSpecies()) registry.speciesOffset(host, species);
		}
		for (var reaction : granularRegistry.reactions()) {
			int host = registry.materialHandle(ThermalMaterialDefinition.CarrierKind.GRANULAR,
					reaction.solidMaterialId());
			for (String species : reaction.solidComposition().keySet()) registry.speciesOffset(host, species);
		}
		for (var pair : registry.gasLiquidPairs()) {
			var component = gasRegistry.component(pair.gasComponentId());
			if (!component.allowsSpecies(pair.speciesId())) throw new IllegalArgumentException(
					"Gas/liquid pair " + pair.id() + " maps species outside gas component " + component.id());
			var phase = chemicalRegistry.phase(chemicalRegistry.phaseHandle(pair.liquidPhaseId()));
			if (!phase.allowedSpecies().contains(pair.speciesId())) throw new IllegalArgumentException(
					"Gas/liquid pair " + pair.id() + " maps species outside liquid phase " + phase.id());
			int gasHost = registry.materialHandle(ThermalMaterialDefinition.CarrierKind.GAS, component.id());
			int liquidHost = registry.materialHandle(ThermalMaterialDefinition.CarrierKind.LIQUID, phase.materialId());
			registry.speciesOffset(gasHost, pair.speciesId());
			registry.speciesOffset(liquidHost, pair.speciesId());
			double mappedGasKilograms = component.standardMassPerCubicMeter()
					* AirNetwork.cubicMeters(pair.gasStandardVolumeNanosPerBatch());
			if (Double.doubleToRawLongBits(mappedGasKilograms)
					!= Double.doubleToRawLongBits(pair.gasKilogramsPerBatch())) {
				throw new IllegalArgumentException("Gas/liquid pair " + pair.id()
						+ " gas nanos and kilograms do not close at the gas standard state");
			}
			double mappedLiquidKilograms = chemicalRegistry.species(
					chemicalRegistry.speciesHandle(pair.speciesId())).massKilogramsPerAu()
					.multiply(pair.liquidQuantaPerBatch()).divide(ChemicalLimits.QUANTA_PER_AU).toDouble();
			if (Double.doubleToRawLongBits(mappedLiquidKilograms)
					!= Double.doubleToRawLongBits(pair.liquidKilogramsPerBatch())) {
				throw new IllegalArgumentException("Gas/liquid pair " + pair.id()
						+ " liquid quanta and kilograms do not close against the chemical species mass");
			}
		}
	}

	public record RegistryBundle(
			WorldCapabilityProfile profile,
			MaterialRegistry material,
			ChemicalRegistry chemical,
			GranularRegistry granular,
			GasRegistry gas,
			ThermalRegistry thermal
	) {
		public RegistryBundle {
			Objects.requireNonNull(profile, "World capability profile");
			Objects.requireNonNull(material, "Material registry");
			Objects.requireNonNull(chemical, "Chemical registry");
			Objects.requireNonNull(granular, "Granular registry");
			Objects.requireNonNull(gas, "Gas registry");
			Objects.requireNonNull(thermal, "Thermal registry");
		}
	}
}
