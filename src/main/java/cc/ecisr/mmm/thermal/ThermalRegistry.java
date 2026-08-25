package cc.ecisr.mmm.thermal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable, canonically ordered world thermal definition candidate. Runtime handles never cross a save or wire boundary. */
public final class ThermalRegistry {
	public static final String UNIT_SYSTEM_ID = "mmm:si_block_v1";

	private final List<ThermalMaterialDefinition> materials;
	private final List<ThermalSpeciesOffset> speciesOffsets;
	private final List<ThermalPhaseTransition> transitions;
	private final List<ThermalBoundaryDefinition> boundaries;
	private final Map<String, Integer> materialHandles;
	private final Map<String, Integer> transitionHandles;
	private final Map<String, Integer> materialByCarrier;
	private final Map<String, ThermalSpeciesOffset> offsetByHostSpecies;
	private final String chemicalRegistryFingerprint;
	private final String granularRegistryFingerprint;
	private final byte[] canonicalBytes;
	private final String fingerprint;

	public ThermalRegistry(
			List<ThermalMaterialDefinition> materials,
			List<ThermalSpeciesOffset> speciesOffsets,
			List<ThermalPhaseTransition> transitions,
			List<ThermalBoundaryDefinition> boundaries,
			String chemicalRegistryFingerprint,
			String granularRegistryFingerprint
	) {
		this.materials = canonicalUnique(materials, ThermalLimits.MAX_MATERIALS,
				ThermalMaterialDefinition::id, "thermal material");
		this.speciesOffsets = canonicalOffsets(speciesOffsets);
		this.transitions = canonicalUniqueOptional(transitions, ThermalLimits.MAX_PHASE_TRANSITIONS,
				ThermalPhaseTransition::id, "thermal transition");
		this.boundaries = canonicalUniqueOptional(boundaries, ThermalLimits.MAX_BOUNDARIES,
				ThermalBoundaryDefinition::id, "thermal boundary");
		this.chemicalRegistryFingerprint = requireFingerprint(chemicalRegistryFingerprint, "chemical");
		this.granularRegistryFingerprint = requireFingerprint(granularRegistryFingerprint, "granular");
		this.materialHandles = handles(this.materials, ThermalMaterialDefinition::id);
		this.transitionHandles = handles(this.transitions, ThermalPhaseTransition::id);
		this.materialByCarrier = validateCarriers();
		this.offsetByHostSpecies = validateSpeciesOffsets();
		validateSegmentBudget();
		validateTransitions();
		validateBoundaries();
		this.canonicalBytes = encodeCanonical();
		this.fingerprint = sha256(canonicalBytes);
	}

	public ThermalRegistry(
			List<ThermalMaterialDefinition> materials,
			List<ThermalSpeciesOffset> speciesOffsets,
			List<ThermalPhaseTransition> transitions,
			String chemicalRegistryFingerprint,
			String granularRegistryFingerprint
	) {
		this(materials, speciesOffsets, transitions, List.of(),
				chemicalRegistryFingerprint, granularRegistryFingerprint);
	}

	public ThermalRegistry(List<ThermalMaterialDefinition> materials) {
		this(materials, List.of(), List.of(), List.of(), "", "");
	}

	public List<ThermalMaterialDefinition> materials() { return materials; }
	public List<ThermalSpeciesOffset> speciesOffsets() { return speciesOffsets; }
	public List<ThermalPhaseTransition> transitions() { return transitions; }
	public List<ThermalBoundaryDefinition> boundaries() { return boundaries; }
	public ThermalMaterialDefinition material(int handle) { return materials.get(requireHandle(handle, materials.size(), "material")); }
	public ThermalPhaseTransition transition(int handle) { return transitions.get(requireHandle(handle, transitions.size(), "transition")); }
	public int materialHandle(String id) { return requireStableHandle(materialHandles, id, "thermal material"); }
	public int transitionHandle(String id) { return requireStableHandle(transitionHandles, id, "thermal transition"); }
	public int materialHandle(ThermalMaterialDefinition.CarrierKind kind, String carrierId) {
		Integer handle = materialByCarrier.get(carrierKey(kind, carrierId));
		if (handle == null) throw new IllegalArgumentException("No thermal material for " + kind + " carrier " + carrierId);
		return handle;
	}
	public boolean hasMaterial(ThermalMaterialDefinition.CarrierKind kind, String carrierId) {
		return materialByCarrier.containsKey(carrierKey(kind, carrierId));
	}
	public ThermalSpeciesOffset speciesOffset(int materialHandle, String speciesId) {
		ThermalMaterialDefinition material = material(materialHandle);
		ThermalSpeciesOffset offset = offsetByHostSpecies.get(offsetKey(material.id(), speciesId));
		if (offset == null) throw new IllegalArgumentException(
				"Missing thermal species offset for " + speciesId + " in " + material.id());
		return offset;
	}
	/** Presence is the registry declaration that this host can carry the named chemical species. */
	public boolean hasSpeciesOffset(int materialHandle, String speciesId) {
		return offsetByHostSpecies.containsKey(offsetKey(material(materialHandle).id(), speciesId));
	}
	public String chemicalRegistryFingerprint() { return chemicalRegistryFingerprint; }
	public String granularRegistryFingerprint() { return granularRegistryFingerprint; }
	public byte[] canonicalBytes() { return canonicalBytes.clone(); }
	public String fingerprint() { return fingerprint; }

	private Map<String, Integer> validateCarriers() {
		LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
		for (int handle = 0; handle < materials.size(); handle++) {
			ThermalMaterialDefinition definition = materials.get(handle);
			String key = carrierKey(definition.carrierKind(), definition.carrierId());
			if (result.putIfAbsent(key, handle) != null) {
				throw new IllegalArgumentException("Duplicate thermal model for carrier " + definition.carrierId());
			}
		}
		return Collections.unmodifiableMap(result);
	}

	private Map<String, ThermalSpeciesOffset> validateSpeciesOffsets() {
		if (!speciesOffsets.isEmpty() && chemicalRegistryFingerprint.isEmpty()) {
			throw new IllegalArgumentException("Thermal species offsets require a chemical registry fingerprint");
		}
		LinkedHashMap<String, ThermalSpeciesOffset> result = new LinkedHashMap<>();
		for (ThermalSpeciesOffset offset : speciesOffsets) {
			materialHandle(offset.hostThermalMaterialId());
			String key = offsetKey(offset.hostThermalMaterialId(), offset.speciesId());
			if (result.putIfAbsent(key, offset) != null) {
				throw new IllegalArgumentException("Duplicate thermal species offset " + offset.speciesId()
						+ " in " + offset.hostThermalMaterialId());
			}
		}
		return Collections.unmodifiableMap(result);
	}

	private void validateSegmentBudget() {
		int total = 0;
		for (ThermalMaterialDefinition material : materials) {
			total = Math.addExact(total, material.caloricSegments().size());
			if (total > ThermalLimits.MAX_TOTAL_SEGMENTS) {
				throw new IllegalArgumentException("Thermal caloric segment total is over budget");
			}
		}
	}

	private void validateTransitions() {
		Map<String, String> pairs = new HashMap<>();
		for (ThermalPhaseTransition transition : transitions) {
			ThermalMaterialDefinition lower = material(materialHandle(transition.lowerEnergyMaterialId()));
			ThermalMaterialDefinition upper = material(materialHandle(transition.upperEnergyMaterialId()));
			double temperature = transition.equilibriumTemperatureK();
			if (temperature < lower.minimumTemperatureK() || temperature > lower.maximumTemperatureK()
					|| temperature < upper.minimumTemperatureK() || temperature > upper.maximumTemperatureK()) {
				throw new IllegalArgumentException("Thermal transition equilibrium is outside a phase validity range: "
						+ transition.id());
			}
			double lowerEnergy = lower.referenceSpecificEnergyEuPerMu() + lower.sensibleSpecificEnergyAt(temperature);
			double upperEnergy = upper.referenceSpecificEnergyEuPerMu() + upper.sensibleSpecificEnergyAt(temperature);
			if (!(Double.isFinite(lowerEnergy) && Double.isFinite(upperEnergy) && upperEnergy > lowerEnergy)) {
				throw new IllegalArgumentException("Thermal transition must have a positive state-function energy gap: "
						+ transition.id());
			}
			String pair = transition.lowerEnergyMaterialId() + '\0' + transition.upperEnergyMaterialId();
			String previous = pairs.putIfAbsent(pair, transition.id());
			if (previous != null) throw new IllegalArgumentException(
					"Competing thermal transitions for the same phase pair: " + previous + ", " + transition.id());
		}
	}

	private void validateBoundaries() {
		Map<String, String> targets = new HashMap<>();
		for (ThermalBoundaryDefinition boundary : boundaries) {
			String key = boundary.targetKind() + "\0" + boundary.targetId();
			String previous = targets.putIfAbsent(key, boundary.id());
			if (previous != null) throw new IllegalArgumentException(
					"Competing thermal boundaries for the same target: " + previous + ", " + boundary.id());
		}
	}

	private byte[] encodeCanonical() {
		StringBuilder out = new StringBuilder(4096).append("mmm-thermal-registry-v1\n")
				.append("units=").append(UNIT_SYSTEM_ID).append('\n')
				.append("chemical-registry=").append(chemicalRegistryFingerprint).append('\n')
				.append("granular-registry=").append(granularRegistryFingerprint).append('\n');
		for (ThermalMaterialDefinition material : materials) {
			out.append("M|").append(material.id()).append('|').append(material.carrierKind()).append('|')
					.append(material.carrierId()).append('|');
			appendDouble(out, material.referenceTemperatureK()); out.append('|');
			appendDouble(out, material.referenceSpecificEnergyEuPerMu()); out.append('|');
			appendDouble(out, material.conductivityEuPerSecondMeterK()); out.append('|');
			for (ThermalMaterialDefinition.CaloricSegment segment : material.caloricSegments()) {
				appendDouble(out, segment.minimumTemperatureK()); out.append(',');
				appendDouble(out, segment.maximumTemperatureK()); out.append(',');
				appendDouble(out, segment.specificHeatEuPerMuK()); out.append(';');
			}
			out.append('\n');
		}
		for (ThermalSpeciesOffset offset : speciesOffsets) {
			out.append("S|").append(offset.id()).append('|').append(offset.speciesId()).append('|')
					.append(offset.hostThermalMaterialId()).append('|')
					.append(offset.referenceInternalEnergyEuPerAu().canonical()).append('\n');
		}
		for (ThermalPhaseTransition transition : transitions) {
			out.append("P|").append(transition.id()).append('|').append(transition.lowerEnergyMaterialId()).append('|')
					.append(transition.upperEnergyMaterialId()).append('|');
			appendDouble(out, transition.equilibriumTemperatureK());
			out.append('|').append(transition.lowerVolumeSvqPerBatch()).append('|')
					.append(transition.upperVolumeSvqPerBatch()).append('|')
					.append(transition.massMuPerBatch().canonical()).append('|')
					.append(transition.interfaceRequirement()).append('|');
			appendDouble(out, transition.minimumPressurePa()); out.append('|');
			appendDouble(out, transition.maximumPressurePa()); out.append('\n');
		}
		for (ThermalBoundaryDefinition boundary : boundaries) {
			out.append("B|").append(boundary.id()).append('|').append(boundary.targetKind()).append('|')
					.append(boundary.targetId()).append('|').append(boundary.kind()).append('|');
			appendDouble(out, boundary.surfaceTemperatureK()); out.append('\n');
		}
		return out.toString().getBytes(StandardCharsets.UTF_8);
	}

	private static void appendDouble(StringBuilder out, double value) {
		out.append(Long.toUnsignedString(Double.doubleToRawLongBits(value)));
	}

	private static List<ThermalSpeciesOffset> canonicalOffsets(List<ThermalSpeciesOffset> values) {
		if (values == null || values.size() > ThermalLimits.MAX_SPECIES_OFFSETS) {
			throw new IllegalArgumentException("Thermal species offsets are missing or over budget");
		}
		ArrayList<ThermalSpeciesOffset> canonical = new ArrayList<>(values);
		if (canonical.stream().anyMatch(java.util.Objects::isNull)) {
			throw new IllegalArgumentException("Thermal species offsets contain null");
		}
		canonical.sort(Comparator.comparing(ThermalSpeciesOffset::id));
		for (int index = 1; index < canonical.size(); index++) {
			if (canonical.get(index - 1).id().equals(canonical.get(index).id())) {
				throw new IllegalArgumentException("Duplicate thermal species offset id " + canonical.get(index).id());
			}
		}
		return List.copyOf(canonical);
	}

	private static <T> List<T> canonicalUnique(
			List<T> definitions, int maximum, java.util.function.Function<T, String> id, String label
	) {
		if (definitions == null || definitions.isEmpty() || definitions.size() > maximum) {
			throw new IllegalArgumentException(label + " definitions are empty or over budget");
		}
		return canonical(definitions, id, label);
	}

	private static <T> List<T> canonicalUniqueOptional(
			List<T> definitions, int maximum, java.util.function.Function<T, String> id, String label
	) {
		if (definitions == null || definitions.size() > maximum) {
			throw new IllegalArgumentException(label + " definitions are missing or over budget");
		}
		return canonical(definitions, id, label);
	}

	private static <T> List<T> canonical(
			List<T> definitions, java.util.function.Function<T, String> id, String label
	) {
		ArrayList<T> result = new ArrayList<>(definitions);
		if (result.stream().anyMatch(java.util.Objects::isNull)) throw new IllegalArgumentException(label + " contains null");
		result.sort(Comparator.comparing(id));
		for (int index = 1; index < result.size(); index++) if (id.apply(result.get(index - 1)).equals(id.apply(result.get(index)))) {
			throw new IllegalArgumentException("Duplicate " + label + " id " + id.apply(result.get(index)));
		}
		return List.copyOf(result);
	}

	private static <T> Map<String, Integer> handles(List<T> values, java.util.function.Function<T, String> id) {
		LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
		for (int index = 0; index < values.size(); index++) result.put(id.apply(values.get(index)), index);
		return Collections.unmodifiableMap(result);
	}

	private static String requireFingerprint(String value, String label) {
		if (value == null || !(value.isEmpty() || value.matches("[0-9a-f]{64}"))) {
			throw new IllegalArgumentException("Invalid " + label + " registry fingerprint");
		}
		return value;
	}

	private static int requireHandle(int handle, int size, String label) {
		if (handle < 0 || handle >= size) throw new IllegalArgumentException("Unknown thermal " + label + " handle " + handle);
		return handle;
	}

	private static int requireStableHandle(Map<String, Integer> handles, String id, String label) {
		Integer handle = handles.get(id);
		if (handle == null) throw new IllegalArgumentException("Unknown " + label + " " + id);
		return handle;
	}

	private static String carrierKey(ThermalMaterialDefinition.CarrierKind kind, String carrierId) {
		return kind + "\0" + carrierId;
	}

	private static String offsetKey(String host, String species) { return host + '\0' + species; }

	private static String sha256(byte[] bytes) {
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
		catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
	}
}
