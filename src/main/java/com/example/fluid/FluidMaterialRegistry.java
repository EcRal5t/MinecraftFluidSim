package com.example.fluid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Immutable material definitions with compact runtime handles assigned in declaration order. */
public final class FluidMaterialRegistry {
	private final List<FluidMaterial> materials;
	private final List<String> phaseGroups;
	private final Map<String, Integer> handleById;
	private final int[] phaseHandleByMaterial;

	public FluidMaterialRegistry(List<FluidMaterial> definitions) {
		if (definitions == null || definitions.isEmpty()) {
			throw new IllegalArgumentException("Material registry must not be empty");
		}
		for (FluidMaterial material : definitions) {
			if (material == null) throw new IllegalArgumentException("Material registry contains null");
		}
		materials = List.copyOf(definitions);
		Map<String, Integer> handles = new HashMap<>();
		Map<String, Integer> phaseHandles = new HashMap<>();
		List<String> groups = new ArrayList<>();
		phaseHandleByMaterial = new int[materials.size()];
		for (int handle = 0; handle < materials.size(); handle++) {
			FluidMaterial material = materials.get(handle);
			if (handles.putIfAbsent(material.id(), handle) != null) {
				throw new IllegalArgumentException("Duplicate material id " + material.id());
			}
			Integer phase = phaseHandles.get(material.miscibilityGroup());
			if (phase == null) {
				phase = phaseHandles.size();
				phaseHandles.put(material.miscibilityGroup(), phase);
				groups.add(material.miscibilityGroup());
			}
			phaseHandleByMaterial[handle] = phase;
		}
		handleById = Map.copyOf(handles);
		phaseGroups = List.copyOf(groups);
	}

	public int size() { return materials.size(); }
	public List<FluidMaterial> materials() { return materials; }
	public List<String> phaseGroups() { return phaseGroups; }

	public FluidMaterial material(int handle) {
		if (handle < 0 || handle >= materials.size()) throw new IllegalArgumentException("Unknown material handle " + handle);
		return materials.get(handle);
	}

	public int handle(String id) {
		Integer handle = handleById.get(id);
		if (handle == null) throw new IllegalArgumentException("Unknown material id " + id);
		return handle;
	}

	public boolean areMiscible(int firstHandle, int secondHandle) {
		return material(firstHandle).miscibilityGroup().equals(material(secondHandle).miscibilityGroup());
	}

	/** Process-local liquid phase handle; like material handles, this is never a wire or persistence id. */
	public int phaseHandle(int materialHandle) {
		material(materialHandle);
		return phaseHandleByMaterial[materialHandle];
	}
}
