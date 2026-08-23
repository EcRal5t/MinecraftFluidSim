package com.example.chemistry;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Immutable, canonically ordered M7 definition registry. */
public final class ChemicalRegistry {
	private final List<ChemicalSpecies> species;
	private final List<ChemicalPhaseDefinition> phases;
	private final List<ChemicalReaction> reactions;
	private final List<ChemicalReaction> reactionOrder;
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
		this.species = canonicalUnique(speciesDefinitions, ChemicalLimits.MAX_SPECIES,
				ChemicalSpecies::id, "species");
		this.phases = canonicalUnique(phaseDefinitions, ChemicalLimits.MAX_PHASES,
				ChemicalPhaseDefinition::id, "chemical phase");
		this.reactions = canonicalUnique(reactionDefinitions, ChemicalLimits.MAX_REACTIONS,
				ChemicalReaction::id, "reaction");
		this.speciesHandles = handles(this.species, ChemicalSpecies::id);
		this.phaseHandles = handles(this.phases, ChemicalPhaseDefinition::id);
		this.phaseByMaterial = validatePhases();
		validateCompositionBasisBudget();
		validateReactions();
		this.reactionOrder = topologicalReactionOrder();
		this.canonicalBytes = encodeCanonical();
		this.fingerprint = sha256(canonicalBytes);
	}

	public List<ChemicalSpecies> species() { return species; }
	public List<ChemicalPhaseDefinition> phases() { return phases; }
	public List<ChemicalReaction> reactions() { return reactions; }
	public List<ChemicalReaction> reactionOrder() { return reactionOrder; }
	public ChemicalSpecies species(int handle) { return species.get(handle); }
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
			for (String id : phase.defaultInventoryPerBv().keySet()) {
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
		Map<String, String> instantaneousConsumer = new HashMap<>();
		Map<String, String> dissociationConsumer = new HashMap<>();
		for (ChemicalReaction reaction : reactions) {
			ChemicalPhaseDefinition phase = phases.get(phaseHandle(reaction.phaseId()));
			if (!Collections.disjoint(reaction.reactants().keySet(), reaction.products().keySet())) {
				throw new IllegalArgumentException("Chemical reaction has direct non-terminating feedback: " + reaction.id());
			}
			for (String id : union(reaction.reactants().keySet(), reaction.products().keySet())) {
				if (!speciesHandles.containsKey(id)) throw new IllegalArgumentException(
						"Reaction " + reaction.id() + " references unknown species " + id);
				if (!phase.allowedSpecies().contains(id)) throw new IllegalArgumentException(
						"Reaction " + reaction.id() + " uses species outside phase " + id);
			}
			validateConservation(reaction);
			if (reaction.kind() == ChemicalReaction.Kind.INSTANTANEOUS) {
				for (String reactant : reaction.reactants().keySet()) {
					String previous = instantaneousConsumer.putIfAbsent(reactant, reaction.id());
					if (previous != null) throw new IllegalArgumentException(
							"Competing instantaneous consumers for " + reactant + ": " + previous + ", " + reaction.id());
				}
			} else {
				String reactant = reaction.reactants().keySet().iterator().next();
				String previous = dissociationConsumer.putIfAbsent(reactant, reaction.id());
				if (previous != null) throw new IllegalArgumentException(
						"Duplicate complete dissociation for " + reactant);
			}
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
			result = result.add(species.get(speciesHandle(term.getKey())).massPerAu().multiply(term.getValue()));
		}
		return result;
	}

	private List<ChemicalReaction> topologicalReactionOrder() {
		int count = reactions.size();
		List<Set<Integer>> outgoing = new ArrayList<>(count);
		int[] indegree = new int[count];
		for (int i = 0; i < count; i++) outgoing.add(new TreeSet<>());
		for (int before = 0; before < count; before++) {
			for (int after = 0; after < count; after++) {
				if (before == after) continue;
				if (!Collections.disjoint(reactions.get(before).products().keySet(),
						reactions.get(after).reactants().keySet()) && outgoing.get(before).add(after)) {
					indegree[after]++;
				}
			}
		}
		PriorityQueue<Integer> ready = new PriorityQueue<>(Comparator.comparing(i -> reactions.get(i).id()));
		for (int i = 0; i < count; i++) if (indegree[i] == 0) ready.add(i);
		List<ChemicalReaction> order = new ArrayList<>(count);
		while (!ready.isEmpty()) {
			int next = ready.remove();
			order.add(reactions.get(next));
			for (int target : outgoing.get(next)) if (--indegree[target] == 0) ready.add(target);
		}
		if (order.size() != count) throw new IllegalArgumentException("Chemical reaction graph is non-terminating");
		return List.copyOf(order);
	}

	private byte[] encodeCanonical() {
		StringBuilder out = new StringBuilder(4096).append("realwater-chemistry-registry-v1\n");
		for (ChemicalSpecies definition : species) {
			out.append("S|").append(definition.id()).append('|').append(definition.charge()).append('|')
					.append(definition.massPerAu().canonical()).append('|')
					.append(Integer.toUnsignedString(definition.baseArgb())).append('|');
			appendMap(out, definition.composition());
		}
		for (ChemicalPhaseDefinition definition : phases) {
			out.append("P|").append(definition.id()).append('|').append(definition.materialId()).append('|')
					.append(definition.acceptsIndicator()).append('|')
					.append(String.join(",", definition.allowedSpecies())).append('|');
			appendRationalMap(out, definition.defaultInventoryPerBv());
		}
		for (ChemicalReaction definition : reactions) {
			out.append("R|").append(definition.id()).append('|').append(definition.kind()).append('|')
					.append(definition.phaseId()).append('|');
			appendInlineMap(out, definition.reactants());
			out.append('|');
			appendInlineMap(out, definition.products());
			out.append('|').append(definition.volumeDeltaBvPerExtent().canonical()).append('\n');
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
