package mmm.chemistry;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Immutable, canonically ordered chemical definition registry. */
public final class ChemicalRegistry {
	private final List<ChemicalSpecies> species;
	private final ExactRational[] speciesMassPerQuantum;
	private final long[] speciesMassUnitsPerQuantum;
	private final List<ChemicalPhaseDefinition> phases;
	private final List<ChemicalReaction> reactions;
	private final List<GasLiquidMassTransferDefinition> gasLiquidMassTransfers;
	private final Map<String, Integer> speciesHandles;
	private final Map<String, Integer> phaseHandles;
	private final Map<String, Integer> phaseByMaterial;
	private final byte[] canonicalBytes;
	private final String fingerprint;

	public ChemicalRegistry(
			List<ChemicalSpecies> speciesDefinitions,
			List<ChemicalPhaseDefinition> phaseDefinitions,
			List<ChemicalReaction> reactionDefinitions
	) {
		this(speciesDefinitions, phaseDefinitions, reactionDefinitions, List.of());
	}

	public ChemicalRegistry(List<ChemicalSpecies> speciesDefinitions,
			List<ChemicalPhaseDefinition> phaseDefinitions, List<ChemicalReaction> reactionDefinitions,
			List<GasLiquidMassTransferDefinition> gasLiquidMassTransfers) {
		this.species = canonicalUnique(speciesDefinitions, ChemicalLimits.MAX_SPECIES,
				ChemicalSpecies::id, "species");
		this.speciesMassPerQuantum = new ExactRational[this.species.size()];
		this.speciesMassUnitsPerQuantum = new long[this.species.size()];
		for (int handle = 0; handle < this.species.size(); handle++) {
			ExactRational mass = this.species.get(handle).molarMassKgPerMol();
			var quantum=mass.divide(ChemicalLimits.QUANTA_PER_MOL);
			speciesMassUnitsPerQuantum[handle]=mmm.units.Mass.exactUnits(quantum.numerator(),quantum.denominator());
			if (mass.denominator().bitLength() + 30 <= ChemicalLimits.MAX_RATIONAL_BITS)
				speciesMassPerQuantum[handle] = mass.divide(ChemicalLimits.QUANTA_PER_MOL);
		}
		this.phases = canonicalUnique(phaseDefinitions, ChemicalLimits.MAX_PHASES,
				ChemicalPhaseDefinition::id, "chemical phase");
		this.reactions = canonicalUnique(reactionDefinitions, ChemicalLimits.MAX_REACTIONS,
				ChemicalReaction::id, "reaction");
		this.speciesHandles = handles(this.species, ChemicalSpecies::id);
		this.phaseHandles = handles(this.phases, ChemicalPhaseDefinition::id);
		this.phaseByMaterial = validatePhases();
		validateCompositionBasisBudget();
		validateReactions();
		if (gasLiquidMassTransfers == null) throw new IllegalArgumentException("Gas/liquid mass transfer definitions are missing");
		this.gasLiquidMassTransfers = gasLiquidMassTransfers.isEmpty() ? List.of()
				: canonicalUnique(gasLiquidMassTransfers, ChemicalLimits.MAX_GAS_LIQUID_MASS_TRANSFERS,
						GasLiquidMassTransferDefinition::id, "gas/liquid mass transfer");
		validateGasLiquidMassTransfers();
		this.canonicalBytes = encodeCanonical();
		this.fingerprint = sha256(canonicalBytes);
	}

	public List<ChemicalSpecies> species() { return species; }
	public List<ChemicalPhaseDefinition> phases() { return phases; }
	public List<ChemicalReaction> reactions() { return reactions; }
	public List<GasLiquidMassTransferDefinition> gasLiquidMassTransfers() { return gasLiquidMassTransfers; }
	public ChemicalSpecies species(int handle) { return species.get(handle); }
	public long speciesMassUnits(int handle,long quanta){return Math.multiplyExact(speciesMassUnitsPerQuantum[handle],quanta);}
	ExactRational speciesMass(int handle, long quanta) {
		ExactRational factor = speciesMassPerQuantum[handle];
		return factor == null ? species.get(handle).molarMassKgPerMol().multiply(quanta).divide(ChemicalLimits.QUANTA_PER_MOL)
				: factor.multiply(quanta);
	}
	public ChemicalPhaseDefinition phase(int handle) { return phases.get(handle); }
	public int speciesHandle(String id) {
		Integer handle = speciesHandles.get(id);
		if (handle == null) throw new IllegalArgumentException("Unknown chemical species " + id);
		return handle;
	}
	public int phaseHandle(String id) {
		Integer handle = phaseHandles.get(id);
		if (handle == null) throw new IllegalArgumentException("Unknown chemical phase " + id);
		return handle;
	}
	public int phaseHandleForMaterial(String materialId) {
		Integer handle = phaseByMaterial.get(materialId);
		if (handle == null) throw new IllegalArgumentException("No chemical phase for material " + materialId);
		return handle;
	}
	public boolean hasSpecies(String id) { return speciesHandles.containsKey(id); }
	public byte[] canonicalBytes() { return canonicalBytes.clone(); }
	public String fingerprint() { return fingerprint; }

	private Map<String, Integer> validatePhases() {
		Map<String, Integer> byMaterial = new TreeMap<>();
		for (int handle = 0; handle < phases.size(); handle++) {
			ChemicalPhaseDefinition phase = phases.get(handle);
			if (byMaterial.put(phase.materialId(), handle) != null) {
				throw new IllegalArgumentException("Multiple chemical phases claim material " + phase.materialId());
			}
			for (String id : phase.allowedSpecies()) {
				if (!speciesHandles.containsKey(id)) throw new IllegalArgumentException(
						"Chemical phase " + phase.id() + " references unknown species " + id);
			}
			for (String id : phase.defaultConcentrationMolPerCubicMeter().keySet()) {
				if (!phase.allowedSpecies().contains(id)) throw new IllegalArgumentException(
						"Chemical phase default is not allowed: " + id);
			}
		}
		return Collections.unmodifiableMap(byMaterial);
	}

	private void validateCompositionBasisBudget() {
		Set<String> bases = new HashSet<>();
		for (ChemicalSpecies definition : species) bases.addAll(definition.composition().keySet());
		if (bases.size() > ChemicalLimits.MAX_COMPOSITION_BASES) {
			throw new IllegalArgumentException("Composition basis count is over budget");
		}
	}

	private void validateReactions() {
		for (ChemicalReaction reaction : reactions) {
			ChemicalPhaseDefinition phase = phases.get(phaseHandle(reaction.phaseId()));
			for (String id : union(reaction.reactants().keySet(), reaction.products().keySet())) {
				if (!speciesHandles.containsKey(id) || !phase.allowedSpecies().contains(id))
					throw new IllegalArgumentException("Reaction uses species outside its phase: " + reaction.id() + ": " + id);
			}
			validateConservation(reaction);
		}
	}

	private void validateConservation(ChemicalReaction reaction) {
		TreeMap<String, BigInteger> left = composition(reaction.reactants());
		TreeMap<String, BigInteger> right = composition(reaction.products());
		if (!left.equals(right)) throw new IllegalArgumentException("Reaction composition is not conserved: " + reaction.id());
		BigInteger leftCharge = charge(reaction.reactants());
		BigInteger rightCharge = charge(reaction.products());
		if (!leftCharge.equals(rightCharge)) throw new IllegalArgumentException("Reaction charge is not conserved: " + reaction.id());
		ExactRational leftMass = mass(reaction.reactants());
		ExactRational rightMass = mass(reaction.products());
		if (leftMass.compareTo(rightMass) != 0) throw new IllegalArgumentException("Reaction mass is not conserved: " + reaction.id());
	}

	private TreeMap<String, BigInteger> composition(Map<String, Integer> side) {
		TreeMap<String, BigInteger> result = new TreeMap<>();
		for (Map.Entry<String, Integer> term : side.entrySet()) {
			ChemicalSpecies definition = species.get(speciesHandle(term.getKey()));
			for (Map.Entry<String, Integer> basis : definition.composition().entrySet()) {
				BigInteger amount = BigInteger.valueOf((long) term.getValue() * basis.getValue());
				result.merge(basis.getKey(), amount, BigInteger::add);
			}
		}
		return result;
	}

	private BigInteger charge(Map<String, Integer> side) {
		BigInteger result = BigInteger.ZERO;
		for (Map.Entry<String, Integer> term : side.entrySet()) {
			result = result.add(BigInteger.valueOf((long) term.getValue()
					* species.get(speciesHandle(term.getKey())).charge()));
		}
		return result;
	}

	private ExactRational mass(Map<String, Integer> side) {
		ExactRational result = ExactRational.ZERO;
		for (Map.Entry<String, Integer> term : side.entrySet()) {
			result = result.add(species.get(speciesHandle(term.getKey())).molarMassKgPerMol().multiply(term.getValue()));
		}
		return result;
	}

	private void validateGasLiquidMassTransfers() {
		Set<String> pairs = new HashSet<>();
		for (var transfer : gasLiquidMassTransfers) {
			ChemicalSpecies solute = species(speciesHandle(transfer.speciesId()));
			var phase = phase(phaseHandle(transfer.liquidPhaseId()));
			if (solute.charge() != 0 || !phase.allowedSpecies().contains(transfer.speciesId()))
				throw new IllegalArgumentException("Gas/liquid mass transfer requires a neutral species allowed by its liquid phase: " + transfer.id());
			if (!pairs.add(transfer.liquidPhaseId() + "|" + transfer.speciesId() + "|" + transfer.gasComponentId()))
				throw new IllegalArgumentException("Duplicate gas/liquid transfer relation: " + transfer.id());
		}
	}

	private byte[] encodeCanonical() {
		StringBuilder out = new StringBuilder(4096).append("massEncoding=").append(mmm.units.Mass.ENCODING).append('\n').append("mmm-chemistry-registry|SI|kg/mol|mol/m3|quanta-per-mol=1000000\n");
		for (ChemicalSpecies definition : species) {
			out.append("S|").append(definition.id()).append('|').append(definition.charge()).append('|')
					.append(definition.molarMassKgPerMol().canonical()).append('|')
					.append(Integer.toUnsignedString(definition.baseArgb())).append('|')
					.append(definition.diffusivityM2PerS() == null ? "" : Double.toHexString(definition.diffusivityM2PerS())).append('|')
					.append(definition.effectiveVolumeM3PerMol() == null ? "" : Double.toHexString(definition.effectiveVolumeM3PerMol())).append('|');
			var liquid = definition.liquidProperties();
			if (liquid != null) out.append("liquid|").append(Double.toHexString(liquid.referenceDensityKgPerM3())).append('|')
					.append(Double.toHexString(liquid.conductivityWPerMK())).append('|')
					.append(Double.toHexString(liquid.viscosityPaS())).append('|');
			appendMap(out, definition.composition());
		}
		for (ChemicalPhaseDefinition definition : phases) {
			out.append("P|").append(definition.id()).append('|').append(definition.materialId()).append('|')
					.append(String.join(",", definition.allowedSpecies())).append('|');
			var correction=definition.viscosityCorrection();
			if(correction!=null)out.append("viscosity|").append(Double.toHexString(correction.a())).append('|')
					.append(Double.toHexString(correction.b())).append('|').append(Double.toHexString(correction.maximumMolality())).append('|')
					.append(Double.toHexString(correction.referenceTemperatureK())).append('|').append(Double.toHexString(correction.temperatureToleranceK())).append('|');
			appendRationalMap(out, definition.defaultConcentrationMolPerCubicMeter());
		}
		for (ChemicalReaction definition : reactions) {
			out.append("R|").append(definition.id()).append('|').append(definition.kind()).append('|')
					.append(definition.phaseId()).append('|');
			appendInlineMap(out, definition.reactants());
			out.append('|');
			appendInlineMap(out, definition.products());
			out.append('|').append(definition.volumeDeltaCubicMetersPerExtent().canonical());
			var k = definition.kinetics();
			for (double value : new double[]{k.forwardRate(), k.reverseRate(), k.forwardActivationJMol(),
					k.reverseActivationJMol(), k.referenceTemperatureK()})
				out.append('|').append(Long.toUnsignedString(Double.doubleToRawLongBits(value)));
			out.append('\n');
		}
		for (var definition : gasLiquidMassTransfers) {
			out.append("G|").append(definition.id()).append('|').append(definition.speciesId()).append('|')
					.append(definition.liquidPhaseId()).append('|').append(definition.gasComponentId());
			for (double value : new double[] {definition.referenceTemperatureK(), definition.henrySolubilityMolPerM3Pa(),
					definition.temperatureSlopeK(), definition.liquidFilmVelocityMPerS(), definition.gasFilmVelocityMPerS(),
					definition.minimumTemperatureK(),definition.maximumTemperatureK()})
				out.append('|').append(Long.toUnsignedString(Double.doubleToRawLongBits(value)));
			out.append('\n');
		}
		return out.toString().getBytes(StandardCharsets.UTF_8);
	}

	private static void appendMap(StringBuilder out, Map<String, Integer> values) {
		appendInlineMap(out, values);
		out.append('\n');
	}
	private static void appendInlineMap(StringBuilder out, Map<String, Integer> values) {
		new TreeMap<>(values).forEach((key, value) -> out.append(key).append('=').append(value).append(','));
	}
	private static void appendRationalMap(StringBuilder out, Map<String, ExactRational> values) {
		new TreeMap<>(values).forEach((key, value) -> out.append(key).append('=').append(value.canonical()).append(','));
		out.append('\n');
	}

	private static String sha256(byte[] bytes) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
			return java.util.HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException("SHA-256 is unavailable", impossible);
		}
	}

	private static <T> List<T> canonicalUnique(
			List<T> definitions, int maximum, java.util.function.Function<T, String> id, String label
	) {
		if (definitions == null || definitions.isEmpty() || definitions.size() > maximum) {
			throw new IllegalArgumentException(label + " definitions are empty or over budget");
		}
		ArrayList<T> canonical = new ArrayList<>(definitions);
		canonical.sort(Comparator.comparing(id));
		for (int i = 1; i < canonical.size(); i++) if (id.apply(canonical.get(i - 1)).equals(id.apply(canonical.get(i)))) {
			throw new IllegalArgumentException("Duplicate " + label + " id " + id.apply(canonical.get(i)));
		}
		return List.copyOf(canonical);
	}

	private static <T> Map<String, Integer> handles(List<T> definitions, java.util.function.Function<T, String> id) {
		LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
		for (int i = 0; i < definitions.size(); i++) result.put(id.apply(definitions.get(i)), i);
		return Collections.unmodifiableMap(result);
	}

	private static <T> Set<T> union(Set<T> left, Set<T> right) {
		HashSet<T> result = new HashSet<>(left);
		result.addAll(right);
		return result;
	}
}
