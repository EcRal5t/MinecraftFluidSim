package cc.ecisr.mmm.simulation.persistence;

import cc.ecisr.mmm.worldprofile.WorldCapability;
import cc.ecisr.mmm.worldprofile.WorldCapabilityProfile;

import java.io.IOException;

/** The sole mapping from a committed world profile to a region checkpoint layout. */
public final class WorldCapabilityLayoutResolver {
	private WorldCapabilityLayoutResolver() { }

	public static RegionStateLayout resolve(WorldCapabilityProfile profile) {
		if (profile == null) throw new IllegalArgumentException("World capability profile is missing");
		try {
			RegionStateLayout layout = RegionStateLayout.decode(profile.regionLayoutId());
			if (layout.hasMaterials() != profile.hasCapability(WorldCapability.MATERIALS)
					|| profile.hasCapability(WorldCapability.CHEMISTRY) && !layout.permitsOptionalChemistry()
					|| profile.hasCapability(WorldCapability.GRANULAR) && layout != RegionStateLayout.THERMAL
							&& layout != RegionStateLayout.GRANULAR
					|| profile.hasCapability(WorldCapability.THERMAL) != (layout == RegionStateLayout.THERMAL)) {
				throw new IllegalArgumentException("World capability profile and region layout disagree");
			}
			return layout;
		} catch (IOException invalid) {
			throw new IllegalArgumentException("World capability profile has an unsupported region layout", invalid);
		}
	}

	public static void validateManifestBinding(
			WorldCapabilityProfile profile, RegionManifest manifest, boolean bindingRequired
	) throws IOException {
		if (manifest == null) throw new IllegalArgumentException("Region manifest is missing");
		if (profile == null) {
			if (bindingRequired) {
				throw new IOException("World capability profile is unavailable; region state cannot be interpreted");
			}
			return;
		}
		if (resolve(profile) != manifest.layout()
				|| !manifest.capabilityProfileFingerprint().equals(profile.profileFingerprint())
				|| !manifest.registryAggregateFingerprint().equals(profile.aggregateFingerprint())
				|| !manifest.materialRegistryFingerprint().equals(profile.registryFingerprints().material())) {
			throw new IOException("Persisted region world capability bindings do not match the committed bundle");
		}
	}

	public static RegionStateLayout fixtureLayout(boolean materials, boolean chemistry, boolean granular, boolean thermal) {
		if (thermal) return RegionStateLayout.THERMAL;
		if (granular) return RegionStateLayout.GRANULAR;
		if (chemistry) return RegionStateLayout.CHEMICAL;
		if (materials) return RegionStateLayout.MATERIAL;
		return RegionStateLayout.PURE;
	}
}
