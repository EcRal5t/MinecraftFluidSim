package mmm.item;

import mmm.chemistry.ChemicalRegistry;
import mmm.granular.GranularRegistry;
import mmm.transfer.ChemicalContents;
import mmm.transfer.ContainerFluidLedger;
import mmm.transfer.FluidMixture;
import mmm.transfer.GranularContents;
import mmm.transfer.ThermalContents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/** Maps pure-Java container truth to Minecraft item components. */
public final class ContainerItemStacks {
	private ContainerItemStacks() { }

	/** Combines compatible resting batches while retaining each integer material ledger. */
	public static ItemStack merge(ItemStack first,ItemStack second) {
		ItemStack result;
		if(MaterialBucketItem.isEmptyMaterialBucket(first)||GranularContainerItem.isEmpty(first)||GasContainerItem.isEmpty(first))return second;
		if(first.getItem() instanceof GasContainerItem && second.getItem() instanceof GasContainerItem) {
			var a=GasContainerItem.contents(first);var b=GasContainerItem.contents(second);
			if(a==null||b==null||!a.gasRegistryFingerprint().equals(b.gasRegistryFingerprint())||!a.chemicalRegistryFingerprint().equals(b.chemicalRegistryFingerprint()))throw new IllegalArgumentException("气体批次定义不同");
			return bindProfile(toStack(new mmm.transfer.GasContents(a.gasRegistryFingerprint(),a.chemicalRegistryFingerprint(),a.inventory().merge(b.inventory()),a.internalEnergy()+b.internalEnergy())),profileFingerprint(first));
		}
		if(first.get(MmmComponents.CHEMICAL_CONTENTS)!=null&&second.get(MmmComponents.CHEMICAL_CONTENTS)!=null){
			var a=ChemicalContents.decode(first.get(MmmComponents.CHEMICAL_CONTENTS));var b=ChemicalContents.decode(second.get(MmmComponents.CHEMICAL_CONTENTS));
			if(!a.registryFingerprint().equals(b.registryFingerprint()))throw new IllegalArgumentException("批次材料定义不同");
			var groups=new java.util.TreeMap<String,ChemicalContents.Group>();
			for(var g:a.groups())groups.put(g.materialId(),g);
			for(var g:b.groups())groups.merge(g.materialId(),g,(x,y)->{
				var amounts=new java.util.TreeMap<String,Long>();
				for(var v:x.species())amounts.put(v.speciesId(),v.amountQuanta());
				for(var v:y.species())amounts.merge(v.speciesId(),v.amountQuanta(),Math::addExact);
				return new ChemicalContents.Group(x.materialId(),Math.addExact(x.volumeNanos(),y.volumeNanos()),Math.addExact(x.massUnits(),y.massUnits()),Math.addExact(x.massResidualUnits(),y.massResidualUnits()),amounts.entrySet().stream().map(v->new ChemicalContents.SpeciesAmount(v.getKey(),v.getValue())).toList());
			});
			result=toStack(new ChemicalContents(a.registryFingerprint(),a.quantaPerMol(),List.copyOf(groups.values())));
		}else if(first.get(MmmComponents.GRANULAR_CONTENTS)!=null&&second.get(MmmComponents.GRANULAR_CONTENTS)!=null){
			var a=GranularContents.decode(first.get(MmmComponents.GRANULAR_CONTENTS));var b=GranularContents.decode(second.get(MmmComponents.GRANULAR_CONTENTS));
			if(!a.registryFingerprint().equals(b.registryFingerprint()))throw new IllegalArgumentException("批次材料定义不同");
			var groups=new java.util.TreeMap<String,GranularContents.Group>();for(var g:a.groups())groups.put(g.materialId(),g);
			for(var g:b.groups())groups.merge(g.materialId(),g,(x,y)->{
				var amounts=new java.util.TreeMap<String,Long>();for(var v:x.composition())amounts.put(v.speciesId(),v.amountQuanta());for(var v:y.composition())amounts.merge(v.speciesId(),v.amountQuanta(),Math::addExact);
				return new GranularContents.Group(x.materialId(),Math.addExact(x.solidVolumeSvq(),y.solidVolumeSvq()),Math.addExact(x.massUnits(),y.massUnits()),Math.addExact(x.massResidualUnits(),y.massResidualUnits()),amounts.entrySet().stream().map(v->new GranularContents.SpeciesAmount(v.getKey(),v.getValue())).toList());
			});
			result=toStack(new GranularContents(a.registryFingerprint(),a.svqPerCubicMeter(),List.copyOf(groups.values())));
		}else{
			var a=fluidMixture(first);var b=fluidMixture(second);if(a==null||b==null)throw new IllegalArgumentException("请选择相同物相的批次");
			var parts=new java.util.TreeMap<String,FluidMixture.Component>();for(var c:a.components())parts.put(c.materialId(),c);
			for(var c:b.components())parts.merge(c.materialId(),c,(x,y)->new FluidMixture.Component(x.materialId(),Math.addExact(x.volumeNanos(),y.volumeNanos()),Math.addExact(x.massUnits(),y.massUnits())));
			result=toStack(new FluidMixture(List.copyOf(parts.values())));
		}
		var heatA=thermalContents(first);var heatB=thermalContents(second);
		if(heatA!=null&&heatB!=null)attachThermal(result,heatA.registryFingerprint(),heatA.internalEnergy()+heatB.internalEnergy());
		else if(heatA!=null||heatB!=null)throw new IllegalArgumentException("批次热状态不一致");
		return bindProfile(result,profileFingerprint(first));
	}

	public static ItemStack bindProfile(ItemStack stack, String fingerprint) {
		if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException("Container world profile fingerprint is invalid");
		}
		if (fluidMixture(stack) == null && stack.get(MmmComponents.CHEMICAL_CONTENTS) == null
				&& stack.get(MmmComponents.GRANULAR_CONTENTS) == null && stack.get(MmmComponents.GAS_CONTENTS) == null) {
			throw new IllegalArgumentException("Only a filled typed container may bind a world profile");
		}
		stack.set(MmmComponents.PROFILE_FINGERPRINT, fingerprint);
		return stack;
	}
	public static ItemStack toStack(mmm.transfer.GasContents contents) {
		ItemStack stack = new ItemStack(MmmItems.GAS_CONTAINER);
		stack.set(MmmComponents.GAS_CONTENTS, contents.encode());
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
			Long massUnits = stack.get(MmmComponents.FLUID_MASS_UNITS);
			if (materialId == null || massUnits == null) return null;
			mixture = new FluidMixture(List.of(new FluidMixture.Component(materialId, volume, massUnits)));
		}
		Long persistedMassUnits = stack.get(MmmComponents.FLUID_MASS_UNITS);
		return mixture.totalVolumeNanos() == volume
				&& persistedMassUnits != null
				&& mixture.totalMassUnits() == persistedMassUnits
				? mixture : null;
	}

	public static ItemStack toStack(FluidMixture mixture) {
		ItemStack stack = new ItemStack(MmmItems.MATERIAL_BUCKET);
		stack.set(MmmComponents.FLUID_VOLUME, mixture.totalVolumeNanos());
		stack.set(MmmComponents.FLUID_MASS_UNITS, mixture.totalMassUnits());
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
		Long massUnits = stack.get(MmmComponents.FLUID_MASS_UNITS);
		return MaterialBucketItem.volume(stack) == contents.totalVolumeNanos() && massUnits != null
				&& massUnits == contents.totalMassUnits() ? contents : null;
	}

	public static ItemStack toStack(ChemicalContents contents) {
		ItemStack stack = new ItemStack(MmmItems.MATERIAL_BUCKET);
		stack.set(MmmComponents.FLUID_VOLUME, contents.totalVolumeNanos());
		stack.set(MmmComponents.FLUID_MASS_UNITS, contents.totalMassUnits());
		stack.set(MmmComponents.CHEMICAL_CONTENTS, contents.encode());
		return stack;
	}

	public static GranularContents granularContents(
			ItemStack stack, GranularRegistry registry, ChemicalRegistry chemistry
	) {
		if (!(stack.getItem() instanceof GranularContainerItem) || hasLiquidInventoryComponents(stack)) return null;
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
		return hasLiquidInventoryComponents(stack) || stack.get(MmmComponents.THERMAL_CONTENTS) != null;
	}

	public static boolean hasLiquidInventoryComponents(ItemStack stack) {
		return stack.get(MmmComponents.WATER_VOLUME) != null
				|| stack.get(MmmComponents.FLUID_VOLUME) != null
				|| stack.get(MmmComponents.MATERIAL_ID) != null
				|| stack.get(MmmComponents.FLUID_MASS_UNITS) != null
				|| stack.get(MmmComponents.MIXTURE_COMPONENTS) != null
				|| stack.get(MmmComponents.CHEMICAL_CONTENTS) != null;
	}

	private static ThermalContents thermalForUnderlying(
			ItemStack stack, String registryFingerprint, double internalEnergy
	) {
		if (stack == null || stack.getCount() != 1) {
			throw new IllegalArgumentException("Thermal contents require one inventory container");
		}
		if (stack.getItem() instanceof GranularContainerItem && !hasLiquidInventoryComponents(stack)) {
			String encoded = stack.get(MmmComponents.GRANULAR_CONTENTS);
			if (encoded == null) throw new IllegalArgumentException("Thermal granular container is empty");
			return ThermalContents.forGranular(registryFingerprint, GranularContents.decode(encoded), internalEnergy);
		}
		if (stack.get(MmmComponents.GRANULAR_CONTENTS) != null) {
			throw new IllegalArgumentException("Container carries conflicting granular and liquid inventories");
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
				Long mass = stack.get(MmmComponents.FLUID_MASS_UNITS);
				if (MaterialBucketItem.volume(stack) != contents.totalVolumeNanos() || mass == null
						|| mass != contents.totalMassUnits()
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
				|| stack.get(MmmComponents.FLUID_MASS_UNITS) != null
				|| stack.get(MmmComponents.MIXTURE_COMPONENTS) != null
				|| stack.get(MmmComponents.CHEMICAL_CONTENTS) != null) {
			throw new IllegalArgumentException("Pure-water container has conflicting material ledgers");
		}
	}
}
