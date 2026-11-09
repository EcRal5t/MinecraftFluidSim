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
	private static final MaterialRegistry MATERIAL = mmm.fluid.TestFluidDefinitions.load().material();
	private static final GasRegistry GAS = mmm.fluid.TestFluidDefinitions.load().gas();

	private TestProfiles() { }

	public static WorldCapabilityProfile of(
			Set<WorldCapability> capabilities, ChemicalRegistry chemical, GranularRegistry granular,
			ThermalRegistry thermal, GasRegistry gas
	) {
		RegistryFingerprintSet bindings = new RegistryFingerprintSet(
				MATERIAL.fingerprint(),
				(chemical == null ? mmm.fluid.TestFluidDefinitions.load().chemistry() : chemical).fingerprint(),
				(granular == null ? mmm.fluid.TestFluidDefinitions.load().granular() : granular).fingerprint(),
				(gas == null ? GAS : gas).fingerprint(),
				(thermal == null ? mmm.fluid.TestFluidDefinitions.load().thermal() : thermal).fingerprint());
		return WorldCapabilityProfile.of(capabilities, bindings);
	}

	public static WorldCapabilityProfile pure() { return of(EnumSet.noneOf(WorldCapability.class), null, null, null, null); }
	public static WorldCapabilityProfile materials() { return of(EnumSet.of(WorldCapability.MATERIALS), null, null, null, null); }
	public static WorldCapabilityProfile chemical(ChemicalRegistry chemical) {
		return of(EnumSet.of(WorldCapability.MATERIALS, WorldCapability.CHEMISTRY, WorldCapability.THERMAL),
				chemical, null, fullRegistries().thermal(), GAS);
	}
	public static WorldCapabilityProfile granular(ChemicalRegistry chemical, GranularRegistry granular) {
		Set<WorldCapability> capabilities = EnumSet.of(WorldCapability.MATERIALS, WorldCapability.GRANULAR);
		if (chemical != null) {
			capabilities.add(WorldCapability.CHEMISTRY);
			capabilities.add(WorldCapability.PRECIPITATION);
			capabilities.add(WorldCapability.THERMAL);
		}
		return of(capabilities, chemical, granular, chemical == null ? null : fullRegistries().thermal(), chemical == null ? null : GAS);
	}
	public static WorldCapabilityProfile thermal(ChemicalRegistry chemical, ThermalRegistry thermal) {
		return of(EnumSet.of(WorldCapability.MATERIALS, WorldCapability.THERMAL), chemical, null, thermal, GAS);
	}
	public static WorldCapabilityProfile full() {
		var registries = fullRegistries();
		return of(EnumSet.allOf(WorldCapability.class), registries.chemical(), registries.granular(),
				registries.thermal(), registries.gas());
	}
	private static FullCapabilityTestFixture.Registries fullRegistries() {
		try {
			return FullCapabilityTestFixture.load();
		} catch (Exception unavailable) {
			throw new AssertionError("Production datapack registries are unavailable", unavailable);
		}
	}

	public static MaterialRegistry materialRegistry() { return MATERIAL; }
	public static RegistryKind kind() { return RegistryKind.MATERIAL; }
}
