package cc.ecisr.mmm.item;

import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.granular.GranularRegistry;
import cc.ecisr.mmm.transfer.ChemicalContents;
import cc.ecisr.mmm.transfer.ContainerFluidLedger;
import cc.ecisr.mmm.transfer.FluidMixture;
import cc.ecisr.mmm.transfer.GranularContents;
import cc.ecisr.mmm.transfer.ThermalContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/** Maps pure-Java container truth to Minecraft item components. */
public final class ContainerItemStacks {
	private ContainerItemStacks() { }

	public static ItemStack bindProfile(ItemStack stack, String fingerprint) {
		if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException("Container world profile fingerprint is invalid");
		}
		if (fluidMixture(stack) == null && stack.get(MmmComponents.CHEMICAL_CONTENTS) == null
				&& stack.get(MmmComponents.GRANULAR_CONTENTS) == null) {
			throw new IllegalArgumentException("Only a filled typed container may bind a world profile");
		}
		stack.set(MmmComponents.PROFILE_FINGERPRINT, fingerprint);
		return stack;
	}

	public static String profileFingerprint(ItemStack stack) {
		String value = stack.get(MmmComponents.PROFILE_FINGERPRINT);
		return value != null && value.matches("[0-9a-f]{64}") ? value : null;
	}

	public static FluidMixture fluidMixture(ItemStack stack) {
		if (!(stack.getItem() instanceof MaterialBucketItem)
				|| stack.get(MmmComponents.CHEMICAL_CONTENTS) != null) return null;
		long volume = MaterialBucketItem.volume(stack);
		if (volume <= 0L) return null;
		String encoded = stack.get(MmmComponents.MIXTURE_COMPONENTS);
		String materialId = stack.get(MmmComponents.MATERIAL_ID);
		FluidMixture mixture;
		if (encoded != null) {
			if (materialId != null) return null;
			mixture = FluidMixture.decode(encoded);
			if (mixture.components().size() < 2 || !mixture.encode().equals(encoded)) return null;
		} else {
			Long massBits = stack.get(MmmComponents.FLUID_MASS_BITS);
			if (materialId == null || massBits == null) return null;
			mixture = new FluidMixture(List.of(new FluidMixture.Component(materialId, volume, massBits)));
		}
		Long persistedMassBits = stack.get(MmmComponents.FLUID_MASS_BITS);
		return mixture.totalVolumeNanos() == volume
				&& persistedMassBits != null
				&& Double.doubleToRawLongBits(mixture.totalMass()) == persistedMassBits
				? mixture : null;
	}

	public static ItemStack toStack(FluidMixture mixture) {
		ItemStack stack = new ItemStack(MmmItems.MATERIAL_BUCKET);
		stack.set(MmmComponents.FLUID_VOLUME, mixture.totalVolumeNanos());
		stack.set(MmmComponents.FLUID_MASS_BITS, Double.doubleToRawLongBits(mixture.totalMass()));
		if (mixture.components().size() == 1) {
			stack.set(MmmComponents.MATERIAL_ID, mixture.components().getFirst().materialId());
		} else {
			stack.set(MmmComponents.MIXTURE_COMPONENTS, mixture.encode());
		}
		return stack;
	}

	public static ChemicalContents chemicalContents(ItemStack stack, ChemicalRegistry registry) {
		if (!(stack.getItem() instanceof MaterialBucketItem)) return null;
		String encoded = stack.get(MmmComponents.CHEMICAL_CONTENTS);
		if (encoded == null || stack.get(MmmComponents.MATERIAL_ID) != null
				|| stack.get(MmmComponents.MIXTURE_COMPONENTS) != null) return null;
		ChemicalContents contents = ChemicalContents.decode(encoded);
		contents.validateAgainst(registry);
		Long massBits = stack.get(MmmComponents.FLUID_MASS_BITS);
		return MaterialBucketItem.volume(stack) == contents.totalVolumeNanos() && massBits != null
				&& massBits == Double.doubleToRawLongBits(contents.totalMass()) ? contents : null;
	}

	public static ItemStack toStack(ChemicalContents contents) {
		ItemStack stack = new ItemStack(MmmItems.MATERIAL_BUCKET);
		stack.set(MmmComponents.FLUID_VOLUME, contents.totalVolumeNanos());
		stack.set(MmmComponents.FLUID_MASS_BITS, Double.doubleToRawLongBits(contents.totalMass()));
		stack.set(MmmComponents.CHEMICAL_CONTENTS, contents.encode());
		return stack;
	}

	public static GranularContents granularContents(
			ItemStack stack, GranularRegistry registry, ChemicalRegistry chemistry
	) {
		if (!(stack.getItem() instanceof GranularContainerItem) || hasLiquidComponents(stack)) return null;
		String encoded = stack.get(MmmComponents.GRANULAR_CONTENTS);
		if (encoded == null) return null;
		GranularContents contents = GranularContents.decode(encoded);
		contents.validateAgainst(registry, chemistry);
		return contents;
	}

	public static ItemStack toStack(GranularContents contents) {
		ItemStack stack = new ItemStack(MmmItems.GRANULAR_CONTAINER);
		stack.set(MmmComponents.GRANULAR_CONTENTS, contents.encode());
		return stack;
	}

	public static ThermalContents attachThermal(
			ItemStack stack, String registryFingerprint, double internalEnergy
	) {
		ThermalContents result = thermalForUnderlying(stack, registryFingerprint, internalEnergy);
		stack.set(MmmComponents.THERMAL_CONTENTS, result.encode());
		return result;
	}

	/** Returns null only when the component is absent; malformed or mismatched truth is rejected. */
	public static ThermalContents thermalContents(ItemStack stack) {
		String encoded = stack.get(MmmComponents.THERMAL_CONTENTS);
		if (encoded == null) return null;
		ThermalContents result = ThermalContents.decode(encoded);
		ThermalContents expected = thermalForUnderlying(stack, result.registryFingerprint(), result.internalEnergy());
		if (result.underlyingKind() != expected.underlyingKind()
				|| !result.underlyingHash().equals(expected.underlyingHash())) {
			throw new IllegalArgumentException("Thermal contents do not match the underlying liquid inventory");
		}
		return result;
	}

	public static boolean hasLiquidComponents(ItemStack stack) {
		return stack.get(MmmComponents.WATER_VOLUME) != null
				|| stack.get(MmmComponents.FLUID_VOLUME) != null
				|| stack.get(MmmComponents.MATERIAL_ID) != null
				|| stack.get(MmmComponents.FLUID_MASS_BITS) != null
				|| stack.get(MmmComponents.MIXTURE_COMPONENTS) != null
				|| stack.get(MmmComponents.CHEMICAL_CONTENTS) != null
				|| stack.get(MmmComponents.THERMAL_CONTENTS) != null;
	}

	private static ThermalContents thermalForUnderlying(
			ItemStack stack, String registryFingerprint, double internalEnergy
	) {
		if (stack == null || stack.getCount() != 1 || stack.get(MmmComponents.GRANULAR_CONTENTS) != null) {
			throw new IllegalArgumentException("Thermal contents require one non-granular liquid container");
		}
		if (stack.is(Items.WATER_BUCKET)) {
			requireNoMaterialLedger(stack);
			return ThermalContents.forPureWater(registryFingerprint,
					ContainerFluidLedger.NANOS_PER_BLOCK, internalEnergy);
		}
		if (stack.getItem() instanceof PartialWaterBucketItem) {
			requireNoMaterialLedger(stack);
			long nanos = PartialWaterBucketItem.volume(stack);
			if (nanos <= 0L) throw new IllegalArgumentException("Partial water container is invalid");
			return ThermalContents.forPureWater(registryFingerprint, nanos, internalEnergy);
		}
		if (stack.getItem() instanceof MaterialBucketItem) {
			String chemical = stack.get(MmmComponents.CHEMICAL_CONTENTS);
			if (chemical != null) {
				ChemicalContents contents = ChemicalContents.decode(chemical);
				Long mass = stack.get(MmmComponents.FLUID_MASS_BITS);
				if (MaterialBucketItem.volume(stack) != contents.totalVolumeNanos() || mass == null
						|| mass != Double.doubleToRawLongBits(contents.totalMass())
						|| stack.get(MmmComponents.MATERIAL_ID) != null
						|| stack.get(MmmComponents.MIXTURE_COMPONENTS) != null) {
					throw new IllegalArgumentException("Chemical container ledgers disagree");
				}
				return ThermalContents.forChemical(registryFingerprint, contents, internalEnergy);
			}
			FluidMixture mixture = fluidMixture(stack);
			if (mixture == null) throw new IllegalArgumentException("Material container is empty or malformed");
			return ThermalContents.forMixture(registryFingerprint, mixture, internalEnergy);
		}
		throw new IllegalArgumentException("Unsupported thermal liquid container");
	}

	private static void requireNoMaterialLedger(ItemStack stack) {
		if (stack.get(MmmComponents.FLUID_VOLUME) != null
				|| stack.get(MmmComponents.MATERIAL_ID) != null
				|| stack.get(MmmComponents.FLUID_MASS_BITS) != null
				|| stack.get(MmmComponents.MIXTURE_COMPONENTS) != null
				|| stack.get(MmmComponents.CHEMICAL_CONTENTS) != null) {
			throw new IllegalArgumentException("Pure-water container has conflicting material ledgers");
		}
	}
}
