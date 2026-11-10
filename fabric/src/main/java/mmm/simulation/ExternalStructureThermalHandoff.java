package mmm.simulation;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import mmm.api.motion.MovingStructureApi.FixedBlock;
import mmm.api.motion.MovingStructureApi.Handoff;
import mmm.api.motion.MovingStructureApi.HandoffKind;
import mmm.thermal.FiniteThermalDeviceInventory;
import mmm.thermal.FiniteThermalDeviceState;
import net.minecraft.core.BlockPos;

/** Relocates ordinary-wall heat inventory with a structure without an external heat exchange. */
final class ExternalStructureThermalHandoff {
	static final String PROVIDER = "mmm:external_structure_wall";
	private ExternalStructureThermalHandoff() { }

	static boolean hasCarried(FiniteThermalDeviceInventory inventory, String provider, java.util.UUID structureId) {
		Objects.requireNonNull(inventory, "Thermal device inventory");
		mmm.api.StableId.require(provider, 128, "structure provider");
		Objects.requireNonNull(structureId, "Structure identity");
		String prefix = structurePrefix(provider, structureId);
		return inventory.devices().stream().anyMatch(device -> device.providerId().equals(PROVIDER)
				&& device.objectId().startsWith(prefix));
	}

	static FiniteThermalDeviceInventory plan(FiniteThermalDeviceInventory previous, String provider, Handoff handoff) {
		Objects.requireNonNull(previous, "Thermal device inventory");
		mmm.api.StableId.require(provider, 128, "structure provider");
		if (handoff == null || handoff.kind() == null || handoff.structure() == null || handoff.structure().id() == null)
			throw new IllegalArgumentException("Thermal handoff identity is missing");
		String structure = structurePrefix(provider, handoff.structure().id());
		var devices = new ArrayList<>(previous.devices());
		var keys = new HashSet<String>();
		var positions = new HashSet<Long>();
		var expectedKeys = new HashSet<String>();
		for (FixedBlock block : handoff.blocks()) {
			if (block == null || block.key() == null || block.key().isEmpty() || block.blockId() == null || block.blockId().isEmpty())
				throw new IllegalArgumentException("Thermal handoff block identity is missing");
			long position = BlockPos.asLong(block.x(), block.y(), block.z());
			if (!keys.add(block.key()) || !positions.add(position))
				throw new IllegalArgumentException("Duplicate thermal handoff block");
			String keyPrefix = structure + encode(block.key()) + "|";
			expectedKeys.add(keyPrefix);
			String identity = keyPrefix + encode(block.blockId()) + "|";
			if (handoff.kind() == HandoffKind.ASSEMBLE) {
				if (devices.stream().anyMatch(d -> d.providerId().equals(PROVIDER) && d.objectId().startsWith(keyPrefix)))
					throw new IllegalArgumentException("Structure already owns this thermal wall");
				String fixedPrefix = position + "@";
				var matches = devices.stream().filter(d -> d.providerId().equals(OrdinaryThermalWalls.PROVIDER)
						&& d.objectId().startsWith(fixedPrefix)).toList();
				if (matches.size() > 1) throw new IllegalArgumentException("Multiple thermal walls occupy the same position");
				if (matches.isEmpty()) continue;
				var wall = matches.getFirst();
				String[] parts = wall.objectId().split("@", -1);
				if ((parts.length != 2 && parts.length != 3) || !parts[1].equals(block.blockId())
						|| parts.length == 3 && !List.of("X", "Y", "Z").contains(parts[2]))
					throw new IllegalArgumentException("Fixed thermal wall identity does not match the structure block");
				devices.remove(wall);
				devices.add(relocate(wall, PROVIDER, identity + (parts.length == 3 ? parts[2] : "-")));
			} else {
				var matches = devices.stream().filter(d -> d.providerId().equals(PROVIDER)
						&& d.objectId().startsWith(keyPrefix)).toList();
				if (matches.size() > 1) throw new IllegalArgumentException("Multiple carried walls share a block identity");
				if (matches.isEmpty()) continue;
				var wall = matches.getFirst();
				if (!wall.objectId().startsWith(identity)) throw new IllegalArgumentException("Carried wall block identity changed");
				String axis = wall.objectId().substring(identity.length());
				if (!List.of("-", "X", "Y", "Z").contains(axis)) throw new IllegalArgumentException("Carried wall face axis is invalid");
				String fixedPrefix = position + "@";
				if (devices.stream().anyMatch(d -> d.providerId().equals(OrdinaryThermalWalls.PROVIDER)
						&& d.objectId().startsWith(fixedPrefix)))
					throw new IllegalArgumentException("Target already has a fixed thermal wall");
				devices.remove(wall);
				devices.add(relocate(wall, OrdinaryThermalWalls.PROVIDER,
						fixedPrefix + block.blockId() + (axis.equals("-") ? "" : "@" + axis)));
			}
		}
		if (handoff.kind() == HandoffKind.DISASSEMBLE) {
			for (var device : devices) if (device.providerId().equals(PROVIDER) && device.objectId().startsWith(structure)
					&& expectedKeys.stream().noneMatch(device.objectId()::startsWith))
				throw new IllegalArgumentException("Handoff omitted a carried thermal wall");
		}
		return new FiniteThermalDeviceInventory(previous.thermalRegistryFingerprint(), devices, previous.audit());
	}

	/** Checks restored screen axes against the actual target block states. */
	static void validateRestoredScreenAxes(FiniteThermalDeviceInventory inventory, Handoff handoff,
			java.util.function.Function<FixedBlock, String> axisAt) {
		Objects.requireNonNull(inventory, "Thermal device inventory");
		Objects.requireNonNull(handoff, "Thermal handoff");
		Objects.requireNonNull(axisAt, "Target screen axis lookup");
		if (handoff.kind() != HandoffKind.DISASSEMBLE) return;
		for (var block : handoff.blocks()) {
			String prefix = BlockPos.asLong(block.x(), block.y(), block.z()) + "@" + block.blockId() + "@";
			for (var device : inventory.devices()) {
				if (!device.providerId().equals(OrdinaryThermalWalls.PROVIDER) || !device.objectId().startsWith(prefix)) continue;
				String savedAxis = device.objectId().substring(prefix.length());
				if (!List.of("X", "Y", "Z").contains(savedAxis) || !savedAxis.equals(axisAt.apply(block)))
					throw new IllegalArgumentException("Restored thermal screen axis does not match the target block");
			}
		}
	}

	private static FiniteThermalDeviceState relocate(FiniteThermalDeviceState wall, String provider, String object) {
		return new FiniteThermalDeviceState(provider, object, wall.deviceTypeId(), wall.body(), wall.surfaces());
	}
	private static String encode(String value) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}
	private static String structurePrefix(String provider, java.util.UUID structureId) {
		return "wall|" + encode(provider) + "|" + structureId + "|";
	}
}
