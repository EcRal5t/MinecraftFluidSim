package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.chemistry.BuiltinChemicalDefinitions;
import cc.ecisr.mmm.chemistry.ChemicalLimits;
import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.chemistry.ChemicalTransferLedger;
import cc.ecisr.mmm.fluid.BuiltinFluidMaterials;
import cc.ecisr.mmm.fluid.FluidMaterial;
import cc.ecisr.mmm.granular.BuiltinGranularDefinitions;
import cc.ecisr.mmm.granular.GranularTransferLedger;
import cc.ecisr.mmm.granular.SolidVolume;
import cc.ecisr.mmm.item.ContainerItemStacks;
import cc.ecisr.mmm.transfer.ChemicalContents;
import cc.ecisr.mmm.transfer.ContainerFluidLedger;
import cc.ecisr.mmm.transfer.CreativeSourceDescriptor;
import cc.ecisr.mmm.transfer.FluidMixture;
import cc.ecisr.mmm.transfer.GranularContents;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.TreeMap;

/** Resolves whitelisted creative intent against the committed world registries on the server thread. */
final class CreativeSourceCatalog {
	static final long LIQUID_AMOUNT = ContainerFluidLedger.NANOS_PER_BLOCK;
	static final long GRANULAR_AMOUNT = SolidVolume.SVQ_PER_BV;
	static final double STANDARD_TEMPERATURE_K = 293.15;

	private CreativeSourceCatalog() { }

	static Resolved resolve(FluidSimulationService service, CreativeSourceDescriptor descriptor) {
		String id = descriptor.templateStableId();
		if (id.startsWith("mmm:source/material/")) return material(service, descriptor);
		if (id.startsWith("mmm:source/reagent/")) return reagent(service, descriptor);
		if (id.startsWith("mmm:source/granular/")) return granular(service, descriptor);
		throw new IllegalArgumentException("creative source template is not in the committed server whitelist");
	}

	private static Resolved material(FluidSimulationService service, CreativeSourceDescriptor descriptor) {
		require(descriptor, LIQUID_AMOUNT);
		String name = descriptor.templateStableId().substring("mmm:source/material/".length());
		FluidMixture mixture;
		if (name.equals("water_oil")) {
			FluidMaterial water = service.resolveMaterial(BuiltinFluidMaterials.WATER.id());
			FluidMaterial oil = service.resolveMaterial(BuiltinFluidMaterials.OIL.id());
			long first = LIQUID_AMOUNT / 2L;
			mixture = new FluidMixture(List.of(
					new FluidMixture.Component(water.id(), first, 0.5 * water.referenceDensity()),
					new FluidMixture.Component(oil.id(), LIQUID_AMOUNT - first, 0.5 * oil.referenceDensity())));
		} else {
			String materialId = switch (name) {
				case "water" -> BuiltinFluidMaterials.WATER.id();
				case "oil" -> BuiltinFluidMaterials.OIL.id();
				case "mercury" -> BuiltinFluidMaterials.MERCURY.id();
				default -> throw new IllegalArgumentException("creative material template is not whitelisted");
			};
			FluidMaterial material = service.resolveMaterial(materialId);
			mixture = new FluidMixture(List.of(new FluidMixture.Component(
					material.id(), LIQUID_AMOUNT, material.referenceDensity())));
		}
		ItemStack stack = ContainerItemStacks.toStack(mixture);
		ContainerItemStacks.bindProfile(stack, service.worldCapabilityProfile().profileFingerprint());
		service.attachLiquidThermalContents(stack, temperature(descriptor));
		return new Resolved(Kind.LIQUID, stack, LIQUID_AMOUNT);
	}

	private static Resolved reagent(FluidSimulationService service, CreativeSourceDescriptor descriptor) {
		require(descriptor, LIQUID_AMOUNT);
		String name = descriptor.templateStableId().substring("mmm:source/reagent/".length());
		String species = switch (name) {
			case "water" -> BuiltinChemicalDefinitions.H2O;
			case "hcl" -> BuiltinChemicalDefinitions.HCL;
			case "naoh" -> BuiltinChemicalDefinitions.NAOH;
			case "nacl" -> BuiltinChemicalDefinitions.NACL;
			case "nano3" -> BuiltinChemicalDefinitions.NANO3;
			case "kcl" -> BuiltinChemicalDefinitions.KCL;
			case "kno3" -> BuiltinChemicalDefinitions.KNO3;
			default -> throw new IllegalArgumentException("creative reagent template is not whitelisted");
		};
		var registry = service.chemicalRegistry();
		TreeMap<String, Long> amounts = new TreeMap<>();
		if (species.equals(BuiltinChemicalDefinitions.H2O)) amounts.put(species, 55_555_555_556L);
		else {
			amounts.put(BuiltinChemicalDefinitions.H2O, 50_000_000_000L);
			amounts.put(species, ChemicalLimits.QUANTA_PER_AU);
		}
		FluidMaterial carrier = service.resolveMaterial(BuiltinFluidMaterials.WATER.id());
		double mass = carrier.referenceDensity();
		ChemicalMixtureRecord exact = ChemicalMixtureRecord.fromStableAmounts(registry, amounts, 0.0);
		ChemicalMixtureRecord mixture = ChemicalMixtureRecord.fromStableAmounts(registry, amounts,
				mass - exact.exactSpeciesMassKilograms(registry).toDouble());
		ChemicalTransferLedger ledger = new ChemicalTransferLedger(List.of(new ChemicalTransferLedger.Group(
				carrier.id(), false, 1.0, mass, mixture.massResidualKilogramsBits(), mixture.stableAmounts(registry))));
		ItemStack stack = ContainerItemStacks.toStack(ChemicalContents.fromLedger(ledger, registry, LIQUID_AMOUNT));
		ContainerItemStacks.bindProfile(stack, service.worldCapabilityProfile().profileFingerprint());
		service.attachLiquidThermalContents(stack, temperature(descriptor));
		return new Resolved(Kind.LIQUID, stack, LIQUID_AMOUNT);
	}

	private static Resolved granular(FluidSimulationService service, CreativeSourceDescriptor descriptor) {
		require(descriptor, GRANULAR_AMOUNT);
		if (descriptor.temperatureKelvin() != null) throw new IllegalArgumentException("granular source cannot carry temperature");
		String name = descriptor.templateStableId().substring("mmm:source/granular/".length());
		String materialId = switch (name) {
			case "fine_sand" -> BuiltinGranularDefinitions.FINE_SAND;
			case "light_beads" -> BuiltinGranularDefinitions.LIGHT_BEADS;
			default -> throw new IllegalArgumentException("creative granular template is not whitelisted");
		};
		var registry = service.granularRegistry();
		var material = registry.material(registry.materialHandle(materialId));
		if (material.reactive()) throw new IllegalArgumentException("reactive granular source is not allowed");
		GranularTransferLedger ledger = new GranularTransferLedger(List.of(new GranularTransferLedger.Group(
				material.id(), GRANULAR_AMOUNT, material.referenceDensity(), null)));
		GranularContents contents = GranularContents.fromLedger(ledger, registry,
				service.chemistryFeatureEnabled() ? service.chemicalRegistry() : null);
		ItemStack stack = ContainerItemStacks.toStack(contents);
		ContainerItemStacks.bindProfile(stack, service.worldCapabilityProfile().profileFingerprint());
		return new Resolved(Kind.GRANULAR, stack, GRANULAR_AMOUNT);
	}

	private static void require(CreativeSourceDescriptor descriptor, long amount) {
		if (descriptor.requestedTypedAmount() != amount) throw new IllegalArgumentException("creative source amount is not whitelisted");
	}

	private static double temperature(CreativeSourceDescriptor descriptor) {
		return descriptor.temperatureKelvin() == null ? STANDARD_TEMPERATURE_K : descriptor.temperatureKelvin();
	}

	enum Kind { LIQUID, GRANULAR }
	record Resolved(Kind kind, ItemStack actualStack, long typedAmount) { }
}
