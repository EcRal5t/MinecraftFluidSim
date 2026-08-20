package com.example.fluid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable material definitions with compact runtime handles assigned in declaration order.
 * Liquid-liquid pair tensions are runtime-only M4.1 data; they are not part of material snapshots,
 * wire payloads or region-state bytes yet, so decode paths still rebuild the default empty pair table.
 */
public final class FluidMaterialRegistry {
public record LiquidPairTension(int firstPhase, int secondPhase, double tension) {
public LiquidPairTension {
if (!(Double.isFinite(tension) && tension >= 0.0)) {
throw new IllegalArgumentException("Liquid pair tension must be finite and non-negative");
}
}
}

private final List<FluidMaterial> materials;
private final List<String> phaseGroups;
private final Map<String, Integer> handleById;
private final int[] phaseHandleByMaterial;
private final Map<Long, Double> liquidPairTensions;

public FluidMaterialRegistry(List<FluidMaterial> definitions) {
this(definitions, List.of());
}

public FluidMaterialRegistry(List<FluidMaterial> definitions, List<LiquidPairTension> pairTensions) {
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
Map<Long, Double> pairMap = new HashMap<>();
if (pairTensions != null) {
for (LiquidPairTension pair : pairTensions) {
if (pair == null) throw new IllegalArgumentException("Liquid pair tension must not be null");
if (pair.firstPhase() == pair.secondPhase()) {
throw new IllegalArgumentException("Liquid pair tension requires two different phases");
}
if (!validPhase(pair.firstPhase()) || !validPhase(pair.secondPhase())) {
throw new IllegalArgumentException("Liquid pair tension references an unknown phase");
}
long key = pairKey(pair.firstPhase(), pair.secondPhase());
if (pairMap.putIfAbsent(key, pair.tension()) != null) {
throw new IllegalArgumentException("Duplicate liquid pair tension for the same phase pair");
}
}
}
liquidPairTensions = Map.copyOf(pairMap);
}

public int size() { return materials.size(); }
public List<FluidMaterial> materials() { return materials; }
public List<String> phaseGroups() { return phaseGroups; }
public int phaseCount() { return phaseGroups.size(); }
public Map<Long, Double> liquidPairTensions() { return liquidPairTensions; }

public double liquidPairTension(int firstPhase, int secondPhase) {
if (!validPhase(firstPhase) || !validPhase(secondPhase)) {
throw new IllegalArgumentException("Unknown phase handle for liquid pair tension");
}
return liquidPairTensions.getOrDefault(pairKey(firstPhase, secondPhase), 0.0);
}

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

private boolean validPhase(int phase) {
return phase >= 0 && phase < phaseGroups.size();
}

private static long pairKey(int firstPhase, int secondPhase) {
int low = Math.min(firstPhase, secondPhase);
int high = Math.max(firstPhase, secondPhase);
return ((long) low << 32) | (high & 0xffffffffL);
}
}