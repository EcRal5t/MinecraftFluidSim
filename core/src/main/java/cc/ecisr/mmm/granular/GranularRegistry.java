package cc.ecisr.mmm.granular;

import cc.ecisr.mmm.chemistry.ChemicalPhaseDefinition;
import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.chemistry.ExactRational;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable, canonically ordered granular material and carrier registry. */
public final class GranularRegistry {
	private final List<ParticulateMaterial> materials;
	private final List<GranularCarrier> carriers;
	private final List<SolidLiquidReaction> reactions;
	private final Map<String, Integer> materialHandles;
	private final Map<String, Integer> carrierHandles;
	private final Map<String, Integer> reactionHandles;
	private final String chemicalRegistryFingerprint;
	private final byte[] canonicalBytes;
	private final String fingerprint;

	public GranularRegistry(List<ParticulateMaterial> materials, List<GranularCarrier> carriers) {
		this(materials, carriers, List.of(), null);
	}

	public GranularRegistry(
			List<ParticulateMaterial> materials, List<GranularCarrier> carriers,
			List<SolidLiquidReaction> reactions, ChemicalRegistry chemicalRegistry
	) {
		this.materials = canonicalUnique(materials, GranularLimits.MAX_MATERIALS,
				ParticulateMaterial::id, "particulate material");
		this.carriers = canonicalUnique(carriers, GranularLimits.MAX_CARRIERS,
				GranularCarrier::liquidMaterialId, "granular carrier");
		this.reactions = reactions == null || reactions.isEmpty() ? List.of()
				: canonicalUnique(reactions, GranularLimits.MAX_REACTIONS, SolidLiquidReaction::id, "solid-liquid reaction");
		this.materialHandles = handles(this.materials, ParticulateMaterial::id);
		this.carrierHandles = handles(this.carriers, GranularCarrier::liquidMaterialId);
		this.reactionHandles = handles(this.reactions, SolidLiquidReaction::id);
		if (this.reactions.isEmpty()) this.chemicalRegistryFingerprint = "";
		else {
			if (chemicalRegistry == null) throw new IllegalArgumentException("Solid-liquid reactions require a chemical registry");
			this.chemicalRegistryFingerprint = chemicalRegistry.fingerprint();
			validateReactions(chemicalRegistry);
		}
		this.canonicalBytes = encodeCanonical();
		this.fingerprint = sha256(canonicalBytes);
	}

	public List<ParticulateMaterial> materials() { return materials; }
	public List<GranularCarrier> carriers() { return carriers; }
	public List<SolidLiquidReaction> reactions() { return reactions; }
	public ParticulateMaterial material(int handle) { return materials.get(requireHandle(handle, materials.size(), "material")); }
	public GranularCarrier carrier(int handle) { return carriers.get(requireHandle(handle, carriers.size(), "carrier")); }
	public int materialHandle(String id) { return requireStableHandle(materialHandles, id, "particulate material"); }
	public int carrierHandle(String liquidMaterialId) { return requireStableHandle(carrierHandles, liquidMaterialId, "carrier"); }
	public int reactionHandle(String id) { return requireStableHandle(reactionHandles, id, "solid-liquid reaction"); }
	public boolean hasCarrier(String liquidMaterialId) { return carrierHandles.containsKey(liquidMaterialId); }
	public String chemicalRegistryFingerprint() { return chemicalRegistryFingerprint; }
	public boolean hasReactiveRules() { return !reactions.isEmpty(); }
	public byte[] canonicalBytes() { return canonicalBytes.clone(); }
	public String fingerprint() { return fingerprint; }

	private byte[] encodeCanonical() {
		StringBuilder out = new StringBuilder(2048).append("mmm-granular-registry\n")
				.append("svq-per-bv=").append(SolidVolume.SVQ_PER_BV).append('\n')
				.append("porosity-scale=").append(GranularLimits.POROSITY_SCALE).append('\n')
				.append("chemical-registry=").append(chemicalRegistryFingerprint).append('\n');
		for (ParticulateMaterial material : materials) {
			out.append("M|").append(material.id()).append('|')
					.append(Long.toUnsignedString(Double.doubleToRawLongBits(material.referenceDensity()))).append('|')
					.append(Long.toUnsignedString(Double.doubleToRawLongBits(material.primaryGrainDiameterMeters()))).append('|')
					.append(material.restPorosityQ()).append('|')
					.append(Long.toUnsignedString(Double.doubleToRawLongBits(material.reposeAngleRadians()))).append('|')
					.append(Long.toUnsignedString(Double.doubleToRawLongBits(material.staticFriction()))).append('|')
					.append(Long.toUnsignedString(Double.doubleToRawLongBits(material.dynamicFriction()))).append('|')
					.append(Long.toUnsignedString(Double.doubleToRawLongBits(material.restitution()))).append('|')
					.append(Long.toUnsignedString(Double.doubleToRawLongBits(material.criticalShields()))).append('|')
					.append(material.erosionRateSvqPerSecond()).append('|').append(material.maximumActiveParcelSvq()).append('|')
					.append(Integer.toUnsignedString(material.baseArgb())).append('|').append(material.displayTextureId()).append('|')
					.append(material.reactive()).append('\n');
		}
		for (GranularCarrier carrier : carriers) {
			out.append("C|").append(carrier.liquidMaterialId()).append('|')
					.append(Long.toUnsignedString(Double.doubleToRawLongBits(carrier.dynamicViscosity()))).append('|')
					.append(Long.toUnsignedString(Double.doubleToRawLongBits(carrier.maximumReynolds()))).append('\n');
		}
		for (SolidLiquidReaction reaction : reactions) {
			out.append("R|").append(reaction.id()).append('|').append(reaction.liquidPhaseId()).append('|');
			appendMap(out, reaction.aqueousStoichiometry()); out.append('|').append(reaction.solidMaterialId()).append('|');
			appendMap(out, reaction.solidComposition());
			out.append('|').append(reaction.saturationCapacityExtentPerBv().canonical()).append('|')
					.append(reaction.solidExtentPerBatch()).append('|')
					.append(reaction.solidVolumeSvqPerBatch()).append('|')
					.append(reaction.accessibleSurfaceSvqPerCellStep()).append('|')
					.append(reaction.reversible()).append('\n');
		}
		return out.toString().getBytes(StandardCharsets.UTF_8);
	}

	private void validateReactions(ChemicalRegistry chemicalRegistry) {
		Map<String, String> consumers = new java.util.HashMap<>();
		for (SolidLiquidReaction reaction : reactions) {
			ChemicalPhaseDefinition phase = chemicalRegistry.phase(chemicalRegistry.phaseHandle(reaction.liquidPhaseId()));
			ParticulateMaterial solid = material(materialHandle(reaction.solidMaterialId()));
			if (!solid.reactive()) throw new IllegalArgumentException(
					"Solid-liquid reaction targets inert material " + reaction.solidMaterialId());
			if (!reaction.aqueousStoichiometry().equals(reaction.solidComposition())) {
				throw new IllegalArgumentException("Solid-liquid rules must preserve exact species identities: " + reaction.id());
			}
			validateDeclaredSolidVolume(reaction, solid, chemicalRegistry);
			for (String species : reaction.aqueousStoichiometry().keySet()) {
				chemicalRegistry.speciesHandle(species);
				if (!phase.allowedSpecies().contains(species)) throw new IllegalArgumentException(
						"Solid-liquid reaction species is outside phase " + reaction.id() + ": " + species);
				String key = reaction.liquidPhaseId() + '\0' + species;
				String previous = consumers.putIfAbsent(key, reaction.id());
				if (previous != null) throw new IllegalArgumentException(
						"Competing solid-liquid consumers for " + species + ": " + previous + ", " + reaction.id());
			}
		}
	}

	private static void validateDeclaredSolidVolume(
			SolidLiquidReaction reaction, ParticulateMaterial solid, ChemicalRegistry chemicalRegistry
	) {
		ExactRational exactMassPerBatch = ExactRational.ZERO;
		for (Map.Entry<String, Integer> term : reaction.solidComposition().entrySet()) {
			exactMassPerBatch = exactMassPerBatch.add(
					chemicalRegistry.species(chemicalRegistry.speciesHandle(term.getKey())).massKilogramsPerAu()
					.multiply(term.getValue()).multiply(reaction.solidExtentPerBatch())
					.divide(cc.ecisr.mmm.chemistry.ChemicalLimits.QUANTA_PER_AU));
		}
		double representedDensity = exactMassPerBatch.multiply(SolidVolume.SVQ_PER_BV)
				.divide(reaction.solidVolumeSvqPerBatch()).toDouble();
		double tolerance = Math.max(1.0E-9,
				Math.max(Math.ulp(representedDensity), Math.ulp(solid.referenceDensity())) * 8.0);
		if (!(Double.isFinite(representedDensity) && representedDensity > 0.0)
				|| Math.abs(representedDensity - solid.referenceDensity()) > tolerance) {
			throw new IllegalArgumentException("Solid-liquid declared volume does not conserve its exact mass at the material reference density: "
					+ reaction.id());
		}
	}

	private static void appendMap(StringBuilder out, Map<String, Integer> values) {
		new java.util.TreeMap<>(values).forEach((key, value) -> out.append(key).append('=').append(value).append(','));
	}

	private static int requireHandle(int handle, int size, String label) {
		if (handle < 0 || handle >= size) throw new IllegalArgumentException("Unknown granular " + label + " handle " + handle);
		return handle;
	}

	private static int requireStableHandle(Map<String, Integer> handles, String id, String label) {
		Integer handle = handles.get(id);
		if (handle == null) throw new IllegalArgumentException("Unknown " + label + " " + id);
		return handle;
	}

	private static <T> List<T> canonicalUnique(
			List<T> definitions, int maximum, java.util.function.Function<T, String> id, String label
	) {
		if (definitions == null || definitions.isEmpty() || definitions.size() > maximum) {
			throw new IllegalArgumentException(label + " definitions are empty or over budget");
		}
		ArrayList<T> canonical = new ArrayList<>(definitions);
		if (canonical.stream().anyMatch(java.util.Objects::isNull)) throw new IllegalArgumentException(label + " contains null");
		canonical.sort(Comparator.comparing(id));
		for (int index = 1; index < canonical.size(); index++) {
			if (id.apply(canonical.get(index - 1)).equals(id.apply(canonical.get(index)))) {
				throw new IllegalArgumentException("Duplicate " + label + " id " + id.apply(canonical.get(index)));
			}
		}
		return List.copyOf(canonical);
	}

	private static <T> Map<String, Integer> handles(List<T> values, java.util.function.Function<T, String> id) {
		LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
		for (int index = 0; index < values.size(); index++) result.put(id.apply(values.get(index)), index);
		return Collections.unmodifiableMap(result);
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is unavailable", impossible);
		}
	}
}
