package mmm.simulation;

import mmm.chemistry.BuiltinChemicalDefinitions;
import mmm.chemistry.ChemicalLimits;
import mmm.chemistry.ChemicalMixtureRecord;
import mmm.chemistry.ChemicalTransferLedger;
import mmm.fluid.BuiltinFluidMaterials;
import mmm.fluid.FluidMaterial;
import mmm.granular.BuiltinGranularDefinitions;
import mmm.granular.GranularTransferLedger;
import mmm.granular.SolidVolume;
import mmm.item.ContainerItemStacks;
import mmm.transfer.ChemicalContents;
import mmm.transfer.ContainerFluidLedger;
import mmm.transfer.CreativeSourceDescriptor;
import mmm.transfer.FluidMixture;
import mmm.transfer.GranularContents;
import mmm.worldprofile.WorldCapability;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.TreeMap;

/** Resolves whitelisted creative intent against the committed world registries on the server thread. */
final class CreativeSourceCatalog {
	static final long LIQUID_AMOUNT = ContainerFluidLedger.NANOS_PER_BLOCK;
	static final long GRANULAR_AMOUNT = SolidVolume.SVQ_PER_BV;
	static final double STANDARD_TEMPERATURE_K = 293.15;

	private CreativeSourceCatalog() { }

	static Resolved resolve(CommittedWorldRegistries world, CreativeSourceDescriptor descriptor) {
		String id = descriptor.templateStableId();
		if (id.startsWith("mmm:source/material/")) return material(world, descriptor);
		if (id.startsWith("mmm:source/reagent/")) return reagent(world, descriptor);
		if (id.startsWith("mmm:source/granular/")) return granular(world, descriptor);
		throw new IllegalArgumentException("creative source template is not in the committed server whitelist");
	}

	private static Resolved material(CommittedWorldRegistries world, CreativeSourceDescriptor descriptor) {
		require(descriptor, LIQUID_AMOUNT);
		requireCapability(world, WorldCapability.MATERIALS);
		String name = descriptor.templateStableId().substring("mmm:source/material/".length());
		FluidMixture mixture;
		if (name.equals("water_oil")) {
			FluidMaterial water = world.resolveMaterial(BuiltinFluidMaterials.WATER.id());
			FluidMaterial oil = world.resolveMaterial(BuiltinFluidMaterials.OIL.id());
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
			FluidMaterial material = world.resolveMaterial(materialId);
			mixture = new FluidMixture(List.of(new FluidMixture.Component(
					material.id(), LIQUID_AMOUNT, material.referenceDensity())));
		}
		ItemStack stack = world.bindActualContainerProfile(ContainerItemStacks.toStack(mixture));
		world.attachLiquidThermalContents(stack, temperature(descriptor));
		return new Resolved(Kind.LIQUID, stack, LIQUID_AMOUNT);
	}

	private static Resolved reagent(CommittedWorldRegistries world, CreativeSourceDescriptor descriptor) {
		require(descriptor, LIQUID_AMOUNT);
		requireCapability(world, WorldCapability.CHEMISTRY);
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
		var registry = world.chemicalRegistry();
		TreeMap<String, Long> amounts = new TreeMap<>();
		if (species.equals(BuiltinChemicalDefinitions.H2O)) amounts.put(species, 55_555_555_556L);
		else {
			amounts.put(BuiltinChemicalDefinitions.H2O, 50_000_000_000L);
			amounts.put(species, ChemicalLimits.QUANTA_PER_AU);
		}
		FluidMaterial carrier = world.resolveMaterial(BuiltinFluidMaterials.WATER.id());
		double mass = carrier.referenceDensity();
		ChemicalMixtureRecord exact = ChemicalMixtureRecord.fromStableAmounts(registry, amounts, 0.0);
		ChemicalMixtureRecord mixture = ChemicalMixtureRecord.fromStableAmounts(registry, amounts,
				mass - exact.exactSpeciesMassKilograms(registry).toDouble());
		ChemicalTransferLedger ledger = new ChemicalTransferLedger(List.of(new ChemicalTransferLedger.Group(
				carrier.id(), false, 1.0, mass, mixture.massResidualKilogramsBits(), mixture.stableAmounts(registry))));
		ItemStack stack = world.bindActualContainerProfile(ContainerItemStacks.toStack(
				ChemicalContents.fromLedger(ledger, registry, LIQUID_AMOUNT)));
		world.attachLiquidThermalContents(stack, temperature(descriptor));
		return new Resolved(Kind.LIQUID, stack, LIQUID_AMOUNT);
	}

	private static Resolved granular(CommittedWorldRegistries world, CreativeSourceDescriptor descriptor) {
		require(descriptor, GRANULAR_AMOUNT);
		requireCapability(world, WorldCapability.GRANULAR);
		if (descriptor.temperatureKelvin() != null) {
			throw new IllegalArgumentException("granular source cannot carry temperature");
		}
		String name = descriptor.templateStableId().substring("mmm:source/granular/".length());
		String materialId = switch (name) {
			case "fine_sand" -> BuiltinGranularDefinitions.FINE_SAND;
			case "light_beads" -> BuiltinGranularDefinitions.LIGHT_BEADS;
			default -> throw new IllegalArgumentException("creative granular template is not whitelisted");
		};
		var registry = world.granularRegistry();
		var material = registry.material(registry.materialHandle(materialId));
		if (material.reactive()) throw new IllegalArgumentException("reactive granular source is not allowed");
		GranularTransferLedger ledger = new GranularTransferLedger(List.of(new GranularTransferLedger.Group(
				material.id(), GRANULAR_AMOUNT, material.referenceDensity(), null)));
		GranularContents contents = GranularContents.fromLedger(ledger, registry,
				world.hasCapability(WorldCapability.CHEMISTRY) ? world.chemicalRegistry() : null);
		ItemStack stack = world.bindActualContainerProfile(ContainerItemStacks.toStack(contents));
		return new Resolved(Kind.GRANULAR, stack, GRANULAR_AMOUNT);
	}

	private static void require(CreativeSourceDescriptor descriptor, long amount) {
		if (descriptor.requestedTypedAmount() != amount) {
			throw new IllegalArgumentException("creative source amount is not whitelisted");
		}
	}

	private static void requireCapability(CommittedWorldRegistries world, WorldCapability capability) {
		if (!world.hasCapability(capability)) {
			throw new IllegalArgumentException("world profile does not provide " + capability.stableId());
		}
	}

	private static double temperature(CreativeSourceDescriptor descriptor) {
		return descriptor.temperatureKelvin() == null ? STANDARD_TEMPERATURE_K : descriptor.temperatureKelvin();
	}

	enum Kind { LIQUID, GRANULAR }
	record Resolved(Kind kind, ItemStack actualStack, long typedAmount) { }
}
