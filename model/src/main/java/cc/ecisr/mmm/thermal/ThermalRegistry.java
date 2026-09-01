package cc.ecisr.mmm.thermal;

import cc.ecisr.mmm.fluid.air.GasRegistry;

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
	public static final String UNIT_SYSTEM_ID = "mmm:si_block";

	private final List<ThermalMaterialDefinition> materials;
	private final List<ThermalSpeciesOffset> speciesOffsets;
	private final List<ThermalPhaseTransition> transitions;
	private final List<ThermalBoundaryDefinition> boundaries;
	private final List<GasLiquidSpeciesPair> gasLiquidPairs;
	private final List<FiniteThermalDeviceDefinition> finiteDevices;
	private final Map<String, Integer> materialHandles;
	private final Map<String, Integer> transitionHandles;
	private final Map<String, Integer> materialByCarrier;
	private final Map<String, ThermalSpeciesOffset> offsetByHostSpecies;
	private final String chemicalRegistryFingerprint;
	private final String granularRegistryFingerprint;
	private final String gasRegistryFingerprint;
	private final byte[] canonicalBytes;
	private final String fingerprint;

	public ThermalRegistry(
			List<ThermalMaterialDefinition> materials,
			List<ThermalSpeciesOffset> speciesOffsets,
			List<ThermalPhaseTransition> transitions,
			List<ThermalBoundaryDefinition> boundaries,
			List<GasLiquidSpeciesPair> gasLiquidPairs,
			List<FiniteThermalDeviceDefinition> finiteDevices,
			String chemicalRegistryFingerprint,
			String granularRegistryFingerprint,
			String gasRegistryFingerprint
	) {
		this.materials = canonicalUnique(materials, ThermalLimits.MAX_MATERIALS,
				ThermalMaterialDefinition::id, "thermal material");
		this.speciesOffsets = canonicalOffsets(speciesOffsets);
		this.transitions = canonicalUniqueOptional(transitions, ThermalLimits.MAX_PHASE_TRANSITIONS,
				ThermalPhaseTransition::id, "thermal transition");
		this.boundaries = canonicalUniqueOptional(boundaries, ThermalLimits.MAX_BOUNDARIES,
				ThermalBoundaryDefinition::id, "thermal boundary");
		this.gasLiquidPairs = canonicalPairs(gasLiquidPairs);
		this.finiteDevices = canonicalUniqueOptional(finiteDevices, ThermalLimits.MAX_FINITE_DEVICE_TYPES,
				FiniteThermalDeviceDefinition::id, "finite thermal device");
		this.chemicalRegistryFingerprint = requireFingerprint(chemicalRegistryFingerprint, "chemical");
		this.granularRegistryFingerprint = requireFingerprint(granularRegistryFingerprint, "granular");
		this.gasRegistryFingerprint = requireFingerprint(gasRegistryFingerprint, "gas");
		this.materialHandles = handles(this.materials, ThermalMaterialDefinition::id);
		this.transitionHandles = handles(this.transitions, ThermalPhaseTransition::id);
		this.materialByCarrier = validateCarriers();
		this.offsetByHostSpecies = validateSpeciesOffsets();
		validateSegmentBudget();
		validateTransitions();
		validateBoundaries();
		validateGasLiquidPairs();
		validateFiniteDevices();
		this.canonicalBytes = encodeCanonical();
		this.fingerprint = sha256(canonicalBytes);
	}

	public ThermalRegistry(
			List<ThermalMaterialDefinition> materials,
			List<ThermalSpeciesOffset> speciesOffsets,
			List<ThermalPhaseTransition> transitions,
			List<ThermalBoundaryDefinition> boundaries,
			List<GasLiquidSpeciesPair> gasLiquidPairs,
			String chemicalRegistryFingerprint,
			String granularRegistryFingerprint,
			String gasRegistryFingerprint
	) {
		this(materials, speciesOffsets, transitions, boundaries, gasLiquidPairs, List.of(),
				chemicalRegistryFingerprint, granularRegistryFingerprint, gasRegistryFingerprint);
	}

	public ThermalRegistry(
			List<ThermalMaterialDefinition> materials,
			List<ThermalSpeciesOffset> speciesOffsets,
			List<ThermalPhaseTransition> transitions,
			List<ThermalBoundaryDefinition> boundaries,
			String chemicalRegistryFingerprint,
			String granularRegistryFingerprint
	) {
		this(materials, speciesOffsets, transitions, boundaries, List.of(),
				chemicalRegistryFingerprint, granularRegistryFingerprint, "");
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
	public List<GasLiquidSpeciesPair> gasLiquidPairs() { return gasLiquidPairs; }
	public List<FiniteThermalDeviceDefinition> finiteDevices() { return finiteDevices; }
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
	public String gasRegistryFingerprint() { return gasRegistryFingerprint; }
	public byte[] canonicalBytes() { return canonicalBytes.clone(); }
	public String fingerprint() { return fingerprint; }

	/** Common declared calibration interval accepted for an externally imposed world temperature. */
	public TemperatureRange worldBoundaryTemperatureRange() {
		double minimum = ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K;
		double maximum = Double.POSITIVE_INFINITY;
		boolean found = false;
		for (ThermalMaterialDefinition material : materials) {
			if (material.carrierKind() == ThermalMaterialDefinition.CarrierKind.DEVICE) continue;
			found = true;
			minimum = Math.max(minimum, material.minimumTemperatureK());
			maximum = Math.min(maximum, material.maximumTemperatureK());
		}
		if (!found || !(Double.isFinite(maximum) && maximum > minimum)) {
			throw new IllegalStateException("World thermal carriers have no common boundary-temperature interval");
		}
		return new TemperatureRange(minimum, maximum);
	}

	public record TemperatureRange(double minimumK, double maximumK) {
		public TemperatureRange {
			if (!(Double.isFinite(minimumK) && Double.isFinite(maximumK)
					&& minimumK >= ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K && maximumK > minimumK)) {
				throw new IllegalArgumentException("Thermal registry temperature range is invalid");
			}
		}

		public boolean contains(double temperatureK) {
			return Double.isFinite(temperatureK) && temperatureK >= minimumK && temperatureK <= maximumK;
		}
	}

	public GasLiquidSpeciesPair gasLiquidPair(String id) {
		for (GasLiquidSpeciesPair pair : gasLiquidPairs) {
			if (pair.id().equals(id)) return pair;
		}
		throw new IllegalArgumentException("Unknown gas/liquid species pair " + id);
	}

	public FiniteThermalDeviceDefinition finiteDevice(String id) {
		for (FiniteThermalDeviceDefinition device : finiteDevices) {
			if (device.id().equals(id)) return device;
		}
		throw new IllegalArgumentException("Unknown finite thermal device " + id);
	}

	private void validateFiniteDevices() {
		for (FiniteThermalDeviceDefinition device : finiteDevices) {
			ThermalMaterialDefinition material = material(materialHandle(device.thermalMaterialId()));
			if (material.carrierKind() != ThermalMaterialDefinition.CarrierKind.DEVICE
					|| !material.carrierId().equals(device.id())) {
				throw new IllegalArgumentException("Finite thermal device " + device.id()
						+ " is not the carrier of its device material");
			}
			if (material.conductivityEuPerSecondMeterK() <= 0.0) {
				throw new IllegalArgumentException("Finite thermal device material must conduct heat " + material.id());
			}
			material.specificHeatAt(device.initialTemperatureK());
		}
	}

	private void validateGasLiquidPairs() {
		if (!gasLiquidPairs.isEmpty() && gasRegistryFingerprint.isEmpty()) {
			throw new IllegalArgumentException("Gas/liquid species pairs require a gas registry fingerprint");
		}
		Map<String, String> exchangeKeys = new HashMap<>();
		for (GasLiquidSpeciesPair pair : gasLiquidPairs) {
			String key = pair.gasComponentId() + '\0' + pair.liquidPhaseId() + '\0' + pair.speciesId();
			String previous = exchangeKeys.putIfAbsent(key, pair.id());
			if (previous != null) throw new IllegalArgumentException(
					"Competing gas/liquid species pairs: " + previous + ", " + pair.id());
		}
	}

	private static List<GasLiquidSpeciesPair> canonicalPairs(List<GasLiquidSpeciesPair> values) {
		if (values == null || values.size() > ThermalLimits.MAX_GAS_LIQUID_PAIRS) {
			throw new IllegalArgumentException("Gas/liquid species pairs are missing or over budget");
		}
		ArrayList<GasLiquidSpeciesPair> canonical = new ArrayList<>(values);
		if (canonical.stream().anyMatch(java.util.Objects::isNull)) {
			throw new IllegalArgumentException("Gas/liquid species pairs contain null");
		}
		canonical.sort(Comparator.comparing(GasLiquidSpeciesPair::id));
		for (int index = 1; index < canonical.size(); index++) {
			if (canonical.get(index - 1).id().equals(canonical.get(index).id())) {
				throw new IllegalArgumentException("Duplicate gas/liquid species pair id " + canonical.get(index).id());
			}
		}
		return List.copyOf(canonical);
	}

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
			if (transition.interfaceRequirement() == ThermalPhaseTransition.InterfaceRequirement.LIQUID_GAS
					&& (transition.minimumPressurePa() > GasRegistry.STANDARD_PRESSURE_PA
					|| transition.maximumPressurePa() < GasRegistry.STANDARD_PRESSURE_PA)) {
				throw new IllegalArgumentException(
						"Liquid/gas transition must define its standard-pressure reference state: " + transition.id());
			}
			for (ThermalPhaseTransition.EquilibriumPoint point : transition.equilibriumCurve()) {
				double temperature = point.temperatureK();
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
		StringBuilder out = new StringBuilder(4096).append("mmm-thermal-registry\n")
				.append("units=").append(UNIT_SYSTEM_ID).append('\n')
				.append("chemical-registry=").append(chemicalRegistryFingerprint).append('\n')
				.append("granular-registry=").append(granularRegistryFingerprint).append('\n')
				.append("gas-registry=").append(gasRegistryFingerprint).append('\n');
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
		for (FiniteThermalDeviceDefinition device : finiteDevices) {
			out.append("D|").append(device.id()).append('|').append(device.thermalMaterialId()).append('|');
			appendDouble(out, device.bodyMassKilograms()); out.append('|');
			appendDouble(out, device.initialTemperatureK()); out.append('|');
			for (FiniteThermalDeviceDefinition.Port port : device.ports()) {
				out.append(port.id()).append(',').append(port.contactGroupId()).append(',')
						.append(port.localFace()).append(',');
				appendDouble(out, port.wallThicknessMeters()); out.append(',');
				appendDouble(out, port.fluidFilmThicknessMeters()); out.append(';');
			}
			out.append('\n');
		}
		for (ThermalSpeciesOffset offset : speciesOffsets) {
			out.append("S|").append(offset.id()).append('|').append(offset.speciesId()).append('|')
					.append(offset.hostThermalMaterialId()).append('|')
					.append(offset.referenceInternalEnergyEuPerAu().canonical()).append('\n');
		}
		for (GasLiquidSpeciesPair pair : gasLiquidPairs) {
			out.append("X|").append(pair.id()).append('|').append(pair.gasComponentId()).append('|')
					.append(pair.liquidPhaseId()).append('|').append(pair.speciesId()).append('|')
					.append(pair.gasStandardVolumeNanosPerBatch()).append('|');
			appendDouble(out, pair.gasKilogramsPerBatch()); out.append('|');
			out.append(pair.liquidQuantaPerBatch()).append('|');
			appendDouble(out, pair.liquidKilogramsPerBatch()); out.append('|');
			appendDouble(out, pair.liquidResidualKilogramsPerBatch()); out.append('|');
			out.append(pair.molesPerBatch().canonical()).append('|');
			appendDouble(out, pair.minimumPressurePa()); out.append('|');
			appendDouble(out, pair.maximumPressurePa()); out.append('|');
			appendDouble(out, pair.maximumLiquidConcentrationMolPerCubicMeter()); out.append('|');
			appendDouble(out, pair.liquidDiffusionSquareMetersPerSecond()); out.append('|');
			appendDouble(out, pair.liquidFilmThicknessMeters()); out.append('|');
			appendDouble(out, pair.gasResistanceSecondsPerMeter()); out.append('|');
			for (GasLiquidSpeciesPair.HenrySegment segment : pair.henrySegments()) {
				appendDouble(out, segment.minimumTemperatureK()); out.append(',');
				appendDouble(out, segment.maximumTemperatureK()); out.append(',');
				appendDouble(out, segment.henryConstantMolPerCubicMeterPaAtMinimum()); out.append(',');
				appendDouble(out, segment.henryConstantMolPerCubicMeterPaAtMaximum()); out.append(';');
			}
			out.append('\n');
		}
		for (ThermalPhaseTransition transition : transitions) {
			out.append("P|").append(transition.id()).append('|').append(transition.lowerEnergyMaterialId()).append('|')
					.append(transition.upperEnergyMaterialId()).append('|')
					.append(transition.lowerVolumeSvqPerBatch()).append('|')
					.append(transition.upperVolumeSvqPerBatch()).append('|')
					.append(transition.massMuPerBatch().canonical()).append('|')
					.append(transition.interfaceRequirement()).append('|');
			for (ThermalPhaseTransition.EquilibriumPoint point : transition.equilibriumCurve()) {
				appendDouble(out, point.pressurePa()); out.append(',');
				appendDouble(out, point.temperatureK()); out.append(';');
			}
			out.append('\n');
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
