package cc.ecisr.mmm.fluid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable material definitions with compact runtime handles assigned in declaration order.
 * Liquid-liquid pair tensions are runtime definitions; display snapshots and region payloads do not carry them.
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
	private final List<List<MaterialRegistry.ViscositySegment>> viscosityByMaterial;

	public FluidMaterialRegistry(List<FluidMaterial> definitions) {
		this(definitions, List.of(), null);
	}

	public FluidMaterialRegistry(List<FluidMaterial> definitions, List<LiquidPairTension> pairTensions) {
		this(definitions, pairTensions, null);
	}

	FluidMaterialRegistry(
			List<FluidMaterial> definitions, List<LiquidPairTension> pairTensions,
			List<List<MaterialRegistry.ViscositySegment>> viscosity
	) {
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
		if (viscosity == null) {
			ArrayList<List<MaterialRegistry.ViscositySegment>> absent = new ArrayList<>(materials.size());
			for (int handle = 0; handle < materials.size(); handle++) absent.add(List.of());
			viscosityByMaterial = List.copyOf(absent);
		} else {
			if (viscosity.size() != materials.size()) {
				throw new IllegalArgumentException("Material viscosity profiles disagree with runtime handles");
			}
			ArrayList<List<MaterialRegistry.ViscositySegment>> profiles = new ArrayList<>(viscosity.size());
			for (List<MaterialRegistry.ViscositySegment> profile : viscosity) {
				profiles.add(profile == null ? List.of() : List.copyOf(profile));
			}
			viscosityByMaterial = List.copyOf(profiles);
		}
	}

	public int size() { return materials.size(); }
	public List<FluidMaterial> materials() { return materials; }
	public List<String> phaseGroups() { return phaseGroups; }
	public int phaseCount() { return phaseGroups.size(); }
	public Map<Long, Double> liquidPairTensions() { return liquidPairTensions; }
	public boolean declaresViscosity(int materialHandle) {
		material(materialHandle);
		return !viscosityByMaterial.get(materialHandle).isEmpty();
	}

	public double dynamicViscosityAt(int materialHandle, double temperatureK) {
		FluidMaterial material = material(materialHandle);
		return evaluateViscosity(material.id(), viscosityByMaterial.get(materialHandle), temperatureK);
	}

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

	private static double evaluateViscosity(
			String materialId, List<MaterialRegistry.ViscositySegment> profile, double temperatureK
	) {
		if (profile.isEmpty()) {
			throw new IllegalArgumentException("Material " + materialId + " does not declare dynamic viscosity");
		}
		if (!Double.isFinite(temperatureK) || temperatureK < profile.getFirst().minimumTemperatureK()
				|| temperatureK > profile.getLast().maximumTemperatureK()) {
			throw new IllegalArgumentException("Temperature " + temperatureK
					+ " is outside the declared viscosity domain of " + materialId);
		}
		int low = 0, high = profile.size() - 1;
		while (low < high) {
			int middle = (low + high) >>> 1;
			if (profile.get(middle).maximumTemperatureK() < temperatureK) low = middle + 1;
			else high = middle;
		}
		MaterialRegistry.ViscositySegment segment = profile.get(low);
		double alpha = (temperatureK - segment.minimumTemperatureK())
				/ (segment.maximumTemperatureK() - segment.minimumTemperatureK());
		return segment.dynamicViscosityPaPerSAtMinimum() * (1.0 - alpha)
				+ segment.dynamicViscosityPaPerSAtMaximum() * alpha;
	}

	private static long pairKey(int firstPhase, int secondPhase) {
		int low = Math.min(firstPhase, secondPhase);
		int high = Math.max(firstPhase, secondPhase);
		return ((long) low << 32) | (high & 0xffffffffL);
	}
}
