package mmm.simulation.persistence;

import mmm.worldprofile.RegistryFingerprintSet;
import mmm.worldprofile.WorldCapability;
import mmm.worldprofile.WorldCapabilityProfile;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Map;

/** Strict disk codec for the immutable profile member of a world-capability bundle. */
public final class WorldCapabilityProfileCodec {
	public static final long MAX_BYTES = 64L * 1024L;

	private WorldCapabilityProfileCodec() { }

	public static void write(Path path, WorldCapabilityProfile profile) throws IOException {
		if (path == null || profile == null) throw new IllegalArgumentException("World capability profile write is incomplete");
		NbtIo.writeCompressed(encode(profile), path);
	}

	public static WorldCapabilityProfile read(Path path) throws IOException {
		if (path == null) throw new IllegalArgumentException("World capability profile path is missing");
		CompoundTag root;
		try { root = NbtIo.readCompressed(path, NbtAccounter.create(MAX_BYTES)); }
		catch (RuntimeException invalid) { throw new IOException("Unreadable world capability profile " + path, invalid); }
		return decode(root);
	}

	public static CompoundTag encode(WorldCapabilityProfile profile) {
		if (profile == null) throw new IllegalArgumentException("World capability profile is missing");
		CompoundTag root = new CompoundTag();
		root.putString("Schema", profile.schema());
		root.putString("UnitIdentity", profile.unitIdentity());
		ListTag capabilities = new ListTag();
		for (WorldCapability capability : profile.capabilities()) {
			CompoundTag entry = new CompoundTag();
			entry.putString("Id", capability.stableId());
			capabilities.add(entry);
		}
		root.put("Capabilities", capabilities);
		root.putString("RegionLayoutId", profile.regionLayoutId());
		RegistryFingerprintSet bindings = profile.registryFingerprints();
		CompoundTag registries = new CompoundTag();
		registries.putString("Material", bindings.material());
		registries.putString("Chemical", bindings.chemical());
		registries.putString("Granular", bindings.granular());
		registries.putString("Gas", bindings.gas());
		registries.putString("Thermal", bindings.thermal());
		root.put("RegistryFingerprints", registries);
		root.putString("AggregateFingerprint", profile.aggregateFingerprint());
		root.putString("ProfileFingerprint", profile.profileFingerprint());
		return root;
	}

	public static WorldCapabilityProfile decode(CompoundTag root) throws IOException {
		StrictNbtFields.requireExact(root, Map.of(
				"Schema", Tag.TAG_STRING,
				"UnitIdentity", Tag.TAG_STRING,
				"Capabilities", Tag.TAG_LIST,
				"RegionLayoutId", Tag.TAG_STRING,
				"RegistryFingerprints", Tag.TAG_COMPOUND,
				"AggregateFingerprint", Tag.TAG_STRING,
				"ProfileFingerprint", Tag.TAG_STRING), "world capability profile");
		CompoundTag registries = root.getCompoundOrEmpty("RegistryFingerprints");
		StrictNbtFields.requireExact(registries, Map.of(
				"Material", Tag.TAG_STRING,
				"Chemical", Tag.TAG_STRING,
				"Granular", Tag.TAG_STRING,
				"Gas", Tag.TAG_STRING,
				"Thermal", Tag.TAG_STRING), "world capability registry bindings");
		ListTag encodedCapabilities = root.getListOrEmpty("Capabilities");
		if (encodedCapabilities.size() > WorldCapability.values().length) {
			throw new IOException("World capability count is over budget");
		}
		EnumSet<WorldCapability> capabilities = EnumSet.noneOf(WorldCapability.class);
		try {
			String previousCapabilityId = null;
			for (int index = 0; index < encodedCapabilities.size(); index++) {
				Tag value = encodedCapabilities.get(index);
				if (!(value instanceof CompoundTag entry)) throw new IOException("Invalid world capability entry");
				StrictNbtFields.requireExact(entry, Map.of("Id", Tag.TAG_STRING), "world capability entry");
				String capabilityId = entry.getStringOr("Id", "");
				if (previousCapabilityId != null && previousCapabilityId.compareTo(capabilityId) >= 0) {
					throw new IOException(previousCapabilityId.equals(capabilityId)
							? "Duplicate world capability " + capabilityId
							: "World capabilities are not in canonical order");
				}
				WorldCapability capability = WorldCapability.byStableId(capabilityId);
				if (!capabilities.add(capability)) throw new IOException(
						"Duplicate world capability " + capability.stableId());
				previousCapabilityId = capabilityId;
			}
			RegistryFingerprintSet bindings = new RegistryFingerprintSet(
					registries.getStringOr("Material", ""), registries.getStringOr("Chemical", ""),
					registries.getStringOr("Granular", ""), registries.getStringOr("Gas", ""),
					registries.getStringOr("Thermal", ""));
			return new WorldCapabilityProfile(root.getStringOr("Schema", ""),
					root.getStringOr("UnitIdentity", ""), capabilities,
					root.getStringOr("RegionLayoutId", ""), bindings,
					root.getStringOr("AggregateFingerprint", ""),
					root.getStringOr("ProfileFingerprint", ""));
		} catch (IllegalArgumentException invalid) {
			throw new IOException("Invalid world capability profile: " + invalid.getMessage(), invalid);
		}
	}
}
