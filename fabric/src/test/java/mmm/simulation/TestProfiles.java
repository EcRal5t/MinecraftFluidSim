package mmm.simulation;

import mmm.chemistry.ChemicalRegistry;
import mmm.fluid.BuiltinFluidMaterials;
import mmm.fluid.MaterialRegistry;
import mmm.fluid.air.BuiltinGasDefinitions;
import mmm.fluid.air.GasRegistry;
import mmm.granular.GranularRegistry;
import mmm.thermal.ThermalRegistry;
import mmm.worldprofile.RegistryFingerprintSet;
import mmm.worldprofile.RegistryKind;
import mmm.worldprofile.WorldCapability;
import mmm.worldprofile.WorldCapabilityProfile;

import java.util.EnumSet;
import java.util.Set;

/** Builds world capability profiles for persistence and protocol gates that no longer use feature flags. */
public final class TestProfiles {
	private static final MaterialRegistry MATERIAL = BuiltinFluidMaterials.createRegistry();
	private static final GasRegistry GAS = BuiltinGasDefinitions.createRegistry();

	private TestProfiles() { }

	public static WorldCapabilityProfile of(
			Set<WorldCapability> capabilities, ChemicalRegistry chemical, GranularRegistry granular,
			ThermalRegistry thermal, GasRegistry gas
	) {
		RegistryFingerprintSet bindings = new RegistryFingerprintSet(
				capabilities.contains(WorldCapability.MATERIALS) ? MATERIAL.fingerprint() : "",
				chemical == null ? "" : chemical.fingerprint(),
				granular == null ? "" : granular.fingerprint(),
				gas == null ? "" : gas.fingerprint(),
				thermal == null ? "" : thermal.fingerprint());
		return WorldCapabilityProfile.of(capabilities, bindings);
	}

	public static WorldCapabilityProfile pure() { return of(EnumSet.noneOf(WorldCapability.class), null, null, null, null); }
	public static WorldCapabilityProfile materials() { return of(EnumSet.of(WorldCapability.MATERIALS), null, null, null, null); }
	public static WorldCapabilityProfile chemical(ChemicalRegistry chemical) {
		return of(EnumSet.of(WorldCapability.MATERIALS, WorldCapability.CHEMISTRY), chemical, null, null, null);
	}
	public static WorldCapabilityProfile granular(ChemicalRegistry chemical, GranularRegistry granular) {
		Set<WorldCapability> capabilities = EnumSet.of(WorldCapability.MATERIALS, WorldCapability.GRANULAR);
		if (chemical != null) {
			capabilities.add(WorldCapability.CHEMISTRY);
			capabilities.add(WorldCapability.PRECIPITATION);
		}
		return of(capabilities, chemical, granular, null, null);
	}
	public static WorldCapabilityProfile thermal(ChemicalRegistry chemical, ThermalRegistry thermal) {
		return of(EnumSet.of(WorldCapability.MATERIALS, WorldCapability.THERMAL), chemical, null, thermal, GAS);
	}
	public static WorldCapabilityProfile full() {
		FullCapabilityTestFixture.Registries registries;
		try {
			registries = FullCapabilityTestFixture.load();
		} catch (Exception unavailable) {
			throw new AssertionError("Production datapack registries are unavailable", unavailable);
		}
		return of(EnumSet.allOf(WorldCapability.class), registries.chemical(), registries.granular(),
				registries.thermal(), registries.gas());
	}

	public static MaterialRegistry materialRegistry() { return MATERIAL; }
	public static RegistryKind kind() { return RegistryKind.MATERIAL; }
}
