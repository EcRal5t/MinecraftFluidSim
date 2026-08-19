package com.example.fluid;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Immutable material definitions with compact runtime handles assigned in declaration order. */
public final class FluidMaterialRegistry {
	private final List<FluidMaterial> materials;
	private final Map<String, Integer> handleById;

	public FluidMaterialRegistry(List<FluidMaterial> definitions) {
		if (definitions == null || definitions.isEmpty()) {
			throw new IllegalArgumentException("Material registry must not be empty");
		}
		for (FluidMaterial material : definitions) {
			if (material == null) throw new IllegalArgumentException("Material registry contains null");
		}
		materials = List.copyOf(definitions);
		Map<String, Integer> handles = new HashMap<>();
		for (int handle = 0; handle < materials.size(); handle++) {
			FluidMaterial material = materials.get(handle);
			if (handles.putIfAbsent(material.id(), handle) != null) {
				throw new IllegalArgumentException("Duplicate material id " + material.id());
			}
		}
		handleById = Map.copyOf(handles);
	}

	public int size() { return materials.size(); }

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
}
