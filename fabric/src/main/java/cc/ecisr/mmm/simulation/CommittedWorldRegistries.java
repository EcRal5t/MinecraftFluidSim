package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.fluid.FluidDomain;
import cc.ecisr.mmm.fluid.FluidMaterial;
import cc.ecisr.mmm.fluid.MaterialRegistry;
import cc.ecisr.mmm.fluid.air.GasRegistry;
import cc.ecisr.mmm.granular.GranularRegistry;
import cc.ecisr.mmm.item.ContainerItemStacks;
import cc.ecisr.mmm.item.MmmComponents;
import cc.ecisr.mmm.thermal.ThermalMaterialDefinition;
import cc.ecisr.mmm.thermal.ThermalRegistry;
import cc.ecisr.mmm.thermal.ThermalStateFunction;
import cc.ecisr.mmm.transfer.ChemicalContents;
import cc.ecisr.mmm.transfer.FluidMixture;
import cc.ecisr.mmm.worldprofile.WorldCapability;
import cc.ecisr.mmm.worldprofile.WorldCapabilityProfile;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** The proven world capability profile and immutable registry snapshots used to interpret inventory. */
public final class CommittedWorldRegistries {
	private final WorldStartupCoordinator.Result startup;
	private final WorldCapabilityProfile profile;
	private final MaterialRegistry material;
	private final ChemicalRegistry chemical;
	private final GranularRegistry granular;
	private final GasRegistry gas;
	private final ThermalRegistry thermal;

	CommittedWorldRegistries(WorldStartupCoordinator.Result startup) {
		this.startup = Objects.requireNonNull(startup, "World startup result");
		this.profile = startup.profile();
		RegistryBundleLoader.RegistryBundle registries = startup.registries();
		this.material = registries == null ? null : registries.material();
		this.chemical = registries == null ? null : registries.chemical();
		this.granular = registries == null ? null : registries.granular();
		this.gas = registries == null ? null : registries.gas();
		this.thermal = registries == null ? null : registries.thermal();
	}

	public WorldCapabilityProfile profile() { return profile; }

	public boolean hasCapability(WorldCapability capability) {
		return profile.hasCapability(capability);
	}

	boolean profileProven() { return startup.profileProven(); }

	public MaterialRegistry materialRegistry() {
		if (material == null) throw new IllegalStateException("Authoritative material registry is unavailable");
		return material;
	}

	public ChemicalRegistry chemicalRegistry() {
		if (chemical == null) throw new IllegalStateException("Authoritative chemical registry is unavailable");
		return chemical;
	}

	public GranularRegistry granularRegistry() {
		if (granular == null) throw new IllegalStateException("Authoritative granular registry is unavailable");
		return granular;
	}

	public GasRegistry gasRegistry() {
		if (gas == null) throw new IllegalStateException("Authoritative gas registry is unavailable");
		return gas;
	}

	public ThermalRegistry thermalRegistry() {
		if (thermal == null) throw new IllegalStateException("Authoritative thermal registry is unavailable");
		return thermal;
	}

	ChemicalRegistry chemicalOrNull() { return chemical; }
	GranularRegistry granularOrNull() { return granular; }
	GasRegistry gasOrNull() { return gas; }
	ThermalRegistry thermalOrNull() { return thermal; }

	public FluidMaterial resolveMaterial(String stableId) {
		return materialRegistry().entry(stableId).material();
	}

	public String thermalRegistryFingerprint() {
		return thermalRegistry().fingerprint();
	}

	public ItemStack bindActualContainerProfile(ItemStack stack) {
		if (stack.get(MmmComponents.FLUID_VOLUME) != null || stack.get(MmmComponents.CHEMICAL_CONTENTS) != null
				|| stack.get(MmmComponents.GRANULAR_CONTENTS) != null) {
			if (!startup.profileProven()) {
				throw new IllegalStateException("Authoritative world profile is unavailable");
			}
			ContainerItemStacks.bindProfile(stack, profile.profileFingerprint());
		}
		return stack;
	}

	public boolean acceptsActualContainerProfile(ItemStack stack) {
		return startup.profileProven() && profile.profileFingerprint().equals(
				ContainerItemStacks.profileFingerprint(stack));
	}

	/** Adds authoritative U to a server-resolved mass-bearing liquid source. */
	public void attachLiquidThermalContents(ItemStack stack, double temperatureKelvin) {
		if (!hasCapability(WorldCapability.THERMAL)) return;
		ThermalRegistry thermal = thermalRegistry();
		ThermalStateFunction stateFunction = new ThermalStateFunction(thermal);
		double energy = 0.0;
		if (stack.get(MmmComponents.CHEMICAL_CONTENTS) != null) {
			ChemicalRegistry chemical = chemicalRegistry();
			ChemicalContents contents = ContainerItemStacks.chemicalContents(stack, chemical);
			if (contents == null) throw new IllegalArgumentException("Debug chemical liquid inventory is malformed");
			for (ChemicalContents.Group group : contents.groups()) {
				TreeMap<String, Long> species = new TreeMap<>();
				for (ChemicalContents.SpeciesAmount amount : group.species()) {
					species.put(amount.speciesId(), amount.amountQuanta());
				}
				int handle = thermal.materialHandle(ThermalMaterialDefinition.CarrierKind.LIQUID, group.materialId());
				validateLiquidTemperature(group.materialId(), temperatureKelvin);
				energy += stateFunction.internalEnergyAtTemperature(
						handle, group.massKilograms(), species, temperatureKelvin);
			}
		} else {
			FluidMixture mixture = ContainerItemStacks.fluidMixture(stack);
			if (mixture == null) throw new IllegalArgumentException("Debug material liquid inventory is malformed");
			for (FluidMixture.Component component : mixture.components()) {
				int handle = thermal.materialHandle(
						ThermalMaterialDefinition.CarrierKind.LIQUID, component.materialId());
				validateLiquidTemperature(component.materialId(), temperatureKelvin);
				energy += stateFunction.internalEnergyAtTemperature(
						handle, component.mass(), Map.of(), temperatureKelvin);
			}
		}
		if (!Double.isFinite(energy)) throw new IllegalArgumentException("Liquid internal energy overflow");
		ContainerItemStacks.attachThermal(stack, thermal.fingerprint(), energy);
	}

	public void attachDebugLiquidThermalContents(ItemStack stack) {
		attachLiquidThermalContents(stack, GasRegistry.STANDARD_TEMPERATURE_K);
	}

	void bindConfiguredState(FluidDomain domain, int maximumThermalSubstepsPerStep) {
		if (hasCapability(WorldCapability.MATERIALS)) {
			FluidRegionFactory.bindDefaultMaterialState(domain);
			domain.bindMaterialDefinitions(materialRegistry());
		}
		if (hasCapability(WorldCapability.CHEMISTRY) && !domain.hasChemistryState()) {
			domain.bindChemistry(chemicalRegistry());
		}
		if (hasCapability(WorldCapability.GRANULAR) && !domain.hasGranularState()) {
			domain.bindGranular(granularRegistry(), domain.materialRegistry(), materialRegistry());
		}
		if (hasCapability(WorldCapability.PRECIPITATION)) domain.setPrecipitationEnabled(true);
		if (hasCapability(WorldCapability.THERMAL) && !domain.hasThermalState()) {
			domain.bindGasRegistry(gasRegistry());
			domain.bindThermal(thermalRegistry(), domain.materialRegistry(), GasRegistry.STANDARD_TEMPERATURE_K,
					maximumThermalSubstepsPerStep);
		}
	}

	private void validateLiquidTemperature(String stableId, double temperatureKelvin) {
		MaterialRegistry material = materialRegistry();
		if (material.declaresViscosity(stableId)) material.dynamicViscosityAt(stableId, temperatureKelvin);
	}
}
