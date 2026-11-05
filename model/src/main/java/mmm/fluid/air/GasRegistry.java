package mmm.fluid.air;

import mmm.chemistry.ExactRational;

import java.math.BigInteger;
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

/** Immutable canonical gas-component and ambient definition registry. */
public final class GasRegistry {
	public static final String UNIT_SYSTEM_ID = "mmm:gas_standard_si_mol";
	public static final double MOLAR_GAS_CONSTANT = 8.31446261815324;
	public static final double STANDARD_PRESSURE_PA = 101_325.0;
	public static final double STANDARD_TEMPERATURE_K = 293.15;

	public record AmbientFraction(String componentId, ExactRational fraction) {
		public AmbientFraction {
			GasIds.require(componentId, "ambient gas component id");
			if (fraction == null || fraction.signum() <= 0) {
				throw new IllegalArgumentException("Ambient gas fraction must be positive");
			}
		}
	}

	private final List<GasComponentDefinition> components;
	private final List<AmbientFraction> ambient;
	private final Map<String, GasComponentDefinition> byId;
	private final byte[] canonicalBytes;
	private final String fingerprint;
	private volatile GasInventory standardAmbient;
	private final SourceRecipe ambientSource;
	private final Map<String, ExactRational> massPerNano;
	private final Map<GasComponentDefinition.DefaultSpecies, ExactRational> speciesPerNano;
	private record SourceRecipe(List<AmbientFraction> parts, BigInteger[] weights, BigInteger total) { }


	public GasRegistry(List<GasComponentDefinition> components, List<AmbientFraction> ambient) {
		if (components == null || components.isEmpty() || components.size() > GasLimits.MAX_COMPONENTS) {
			throw new IllegalArgumentException("Gas component registry is empty or over budget");
		}
		ArrayList<GasComponentDefinition> canonicalComponents = new ArrayList<>(components);
		if (canonicalComponents.stream().anyMatch(java.util.Objects::isNull)) {
			throw new IllegalArgumentException("Gas component registry contains null");
		}
		canonicalComponents.sort(Comparator.comparing(GasComponentDefinition::id));
		LinkedHashMap<String, GasComponentDefinition> definitions = new LinkedHashMap<>();
		for (GasComponentDefinition definition : canonicalComponents) {
			if (definitions.put(definition.id(), definition) != null) {
				throw new IllegalArgumentException("Duplicate gas component " + definition.id());
			}
		}
		if (ambient == null || ambient.isEmpty() || ambient.size() > GasLimits.MAX_COMPONENTS_PER_NODE) {
			throw new IllegalArgumentException("Ambient gas definition is empty or over budget");
		}
		ArrayList<AmbientFraction> canonicalAmbient = new ArrayList<>(ambient);
		if (canonicalAmbient.stream().anyMatch(java.util.Objects::isNull)) {
			throw new IllegalArgumentException("Ambient gas definition contains null");
		}
		canonicalAmbient.sort(Comparator.comparing(AmbientFraction::componentId));
		ExactRational sum = ExactRational.ZERO;
		String previous = null;
		for (AmbientFraction fraction : canonicalAmbient) {
			if (!definitions.containsKey(fraction.componentId())) {
				throw new IllegalArgumentException("Ambient references unknown gas component " + fraction.componentId());
			}
			if (fraction.componentId().equals(previous)) {
				throw new IllegalArgumentException("Duplicate ambient gas component " + fraction.componentId());
			}
			sum = sum.add(fraction.fraction());
			previous = fraction.componentId();
		}
		if (!sum.equals(ExactRational.ONE)) throw new IllegalArgumentException("Ambient gas fractions must sum to one");
		this.components = List.copyOf(canonicalComponents);
		this.ambient = List.copyOf(canonicalAmbient);
		this.byId = Collections.unmodifiableMap(definitions);
		this.canonicalBytes = encodeCanonical();
		this.fingerprint = sha256(canonicalBytes);
		this.ambientSource = prepareSource(this.ambient);
		var masses = new LinkedHashMap<String, ExactRational>();
		var species = new LinkedHashMap<GasComponentDefinition.DefaultSpecies, ExactRational>();
		for (var definition : this.components) {
			var density = ExactRational.parse(Double.toString(definition.standardDensityKgPerCubicMeter()));
			masses.put(definition.id(), new ExactRational(density.numerator().multiply(BigInteger.TEN), density.denominator()));
			for (var entry : definition.defaultSpeciesConcentrations()) {
				var concentration = entry.concentrationMolPerStandardCubicMeter();
				species.put(entry, new ExactRational(concentration.numerator().multiply(BigInteger.valueOf(mmm.chemistry.ChemicalLimits.QUANTA_PER_MOL)),
						concentration.denominator().multiply(BigInteger.valueOf(1_000_000_000L))));
			}
		}
		massPerNano = Map.copyOf(masses);
		speciesPerNano = Map.copyOf(species);
	}

	public List<GasComponentDefinition> components() { return components; }
	public List<AmbientFraction> ambient() { return ambient; }
	public GasComponentDefinition component(String id) {
		GasComponentDefinition definition = byId.get(id);
		if (definition == null) throw new IllegalArgumentException("Unknown gas component " + id);
		return definition;
	}
	public boolean hasComponent(String id) { return byId.containsKey(id); }
	public byte[] canonicalBytes() { return canonicalBytes.clone(); }
	public String fingerprint() { return fingerprint; }

	/** Rejects inventory whose component or component-local species identity is outside this frozen registry. */
	public void requireCompatible(GasInventory inventory) {
		if (inventory == null) throw new IllegalArgumentException("Gas inventory is missing");
		for (GasComponentAmount amount : inventory.components()) {
			requireCompatible(amount.componentId(), amount.species());
		}
	}

	/** Rejects one persisted/audited component column whose species do not belong to that component. */
	public void requireCompatible(String componentId, List<GasComponentAmount.SpeciesAmount> species) {
		GasComponentDefinition definition = component(componentId);
		if (species == null) throw new IllegalArgumentException("Gas species list is missing");
		for (GasComponentAmount.SpeciesAmount amount : species) {
			if (!definition.allowsSpecies(amount.speciesId())) {
				throw new IllegalArgumentException("Gas component " + componentId
						+ " does not allow species " + amount.speciesId());
			}
		}
	}

	/** Explicit sources and presets share the same mole-fraction allocator. */
	public GasInventory createAmbient(long standardVolumeNanos) { return createMixture(standardVolumeNanos, ambient); }
	/** Immutable one-standard-cubic-metre sample shared by density and thermal property queries. */
	public GasInventory standardAmbientInventory() {
		GasInventory prepared = standardAmbient;
		if (prepared == null) standardAmbient = prepared = createAmbient(1_000_000_000L);
		return prepared;
	}
	public GasInventory createMixture(long standardVolumeNanos, List<AmbientFraction> recipe) {
		return createMixtureInterval(0, standardVolumeNanos, recipe);
	}

	/** Cumulative-source differences preserve trace gas and chemical quanta across tiny injections. */
	public GasInventory createAmbientInterval(long previousNanos, long addedNanos) {
		return createMixtureInterval(previousNanos, addedNanos, ambient);
	}
	private GasInventory createMixtureInterval(long previous, long added, List<AmbientFraction> recipe) {
		if (previous < 0 || added < 0) throw new IllegalArgumentException("Gas source volume cannot be negative");
		if (added == 0) return GasInventory.EMPTY;
		long end = Math.addExact(previous, added);
		var source = recipe == ambient ? ambientSource : prepareSource(recipe);
		var sorted = source.parts();
		long[] before = sourceShares(previous, source), after = sourceShares(end, source);
		var result = new ArrayList<GasComponentAmount>();
		for (int i = 0; i < sorted.size(); i++) {
			long nanos = after[i] - before[i]; if (nanos == 0) continue;
			var definition = component(sorted.get(i).componentId());
			long mass = sourceMass(definition, after[i]) - sourceMass(definition, before[i]);
			if (mass <= 0) throw new GasInventory.RepresentationException("Gas source lies below the mass encoding");
			var species = new ArrayList<GasComponentAmount.SpeciesAmount>(); long chemicalMass = 0;
			for (var entry : definition.defaultSpeciesConcentrations()) {
				long quanta = sourceSpecies(entry, after[i]) - sourceSpecies(entry, before[i]);
				if (quanta > 0) species.add(new GasComponentAmount.SpeciesAmount(entry.speciesId(), quanta));
				chemicalMass = Math.addExact(chemicalMass, Math.multiplyExact(quanta, entry.massUnitsPerQuantum()));
			}
			result.add(new GasComponentAmount(definition.id(), nanos, mass, species, Math.subtractExact(mass, chemicalMass)));
		}
		return new GasInventory(result);
	}
	private long sourceMass(GasComponentDefinition definition, long nanos) {
		var ratio = massPerNano.get(definition.id());
		if (ratio.numerator().bitLength() < 63 && ratio.denominator().bitLength() < 63) {
			long numerator = ratio.numerator().longValue(), denominator = ratio.denominator().longValue();
			if (numerator == 0 || nanos <= Long.MAX_VALUE / numerator) {
				long product = numerator * nanos, quotient = product / denominator, remainder = product % denominator;
				int half = Long.compare(remainder, denominator - remainder);
				return half > 0 || half == 0 && (quotient & 1) != 0 ? Math.addExact(quotient, 1) : quotient;
			}
		}
		var division = ratio.numerator().multiply(BigInteger.valueOf(nanos)).divideAndRemainder(ratio.denominator());
		int half = division[1].shiftLeft(1).compareTo(ratio.denominator());
		return (half > 0 || half == 0 && division[0].testBit(0) ? division[0].add(BigInteger.ONE) : division[0]).longValueExact();
	}
	private long sourceSpecies(GasComponentDefinition.DefaultSpecies entry, long nanos) {
		var ratio = speciesPerNano.get(entry);
		return sourceFloor(ratio.numerator(), nanos, ratio.denominator());
	}
	private SourceRecipe prepareSource(List<AmbientFraction> recipe) {
		var sorted = new ArrayList<>(recipe);
		sorted.sort(Comparator.comparing(AmbientFraction::componentId));
		ExactRational sum = ExactRational.ZERO;
		BigInteger common = BigInteger.ONE;
		String last = null;
		for (var part : sorted) {
			component(part.componentId());
			if (part.componentId().equals(last)) throw new IllegalArgumentException("Duplicate source component");
			sum = sum.add(part.fraction());
			common = lcm(common, part.fraction().denominator());
			last = part.componentId();
		}
		if (!sum.equals(ExactRational.ONE)) throw new IllegalArgumentException("Gas source mole fractions must sum to one");
		BigInteger[] weights = new BigInteger[sorted.size()];
		BigInteger total = BigInteger.ZERO;
		for (int i = 0; i < weights.length; i++) {
			var weight = sorted.get(i).fraction();
			weights[i] = weight.numerator().multiply(common.divide(weight.denominator()));
			total = total.add(weights[i]);
		}
		return new SourceRecipe(List.copyOf(sorted), weights, total);
	}
	/** Jefferson allocation is monotone in cumulative source size, so every difference is positive. */
	private static long[] sourceShares(long total, SourceRecipe source) {
		long[] result = new long[source.weights().length];
		if (total == 0) return result;
		if (result.length == 1) { result[0] = total; return result; }
		var integers = source.weights();
		long assigned = 0;
		for (int i = 0; i < result.length; i++) {
			result[i] = sourceFloor(integers[i], total, source.total());
			assigned += result[i];
		}
		while (assigned++ < total) {
			int best = 0;
			for (int i = 1; i < result.length; i++)
				if (integers[i].multiply(BigInteger.valueOf(result[best]).add(BigInteger.ONE)).compareTo(
						integers[best].multiply(BigInteger.valueOf(result[i]).add(BigInteger.ONE))) > 0) best = i;
			result[best]++;
		}
		return result;
	}

	private static long sourceFloor(BigInteger numerator, long amount, BigInteger denominator) {
		if (numerator.bitLength() < 63 && denominator.bitLength() < 63) {
			long factor = numerator.longValue();
			if (factor == 0 || amount <= Long.MAX_VALUE / factor)
				return factor * amount / denominator.longValue();
		}
		return numerator.multiply(BigInteger.valueOf(amount)).divide(denominator).longValueExact();
	}

	private byte[] encodeCanonical() {
		StringBuilder out = new StringBuilder(1024).append("massEncoding=").append(mmm.units.Mass.ENCODING).append('\n').append("mmm-gas-registry\n")
				.append("units=").append(UNIT_SYSTEM_ID).append('\n')
				.append("pressure=").append(Long.toUnsignedString(Double.doubleToRawLongBits(STANDARD_PRESSURE_PA))).append('\n')
				.append("temperature=").append(Long.toUnsignedString(Double.doubleToRawLongBits(STANDARD_TEMPERATURE_K))).append('\n');
		for (GasComponentDefinition component : components) {
			out.append("C|").append(component.id()).append('|')
					.append(Long.toUnsignedString(Double.doubleToRawLongBits(component.standardDensityKgPerCubicMeter()))).append('|');
			for (String species : component.allowedSpecies()) out.append(species).append(';');
			out.append('|');
			for (GasComponentDefinition.DefaultSpecies species : component.defaultSpeciesConcentrations()) {
				out.append(species.speciesId()).append('=').append(species.concentrationMolPerStandardCubicMeter().canonical()).append('@').append(species.massUnitsPerQuantum()).append(';');
			}
			out.append('\n');
		}
		for (AmbientFraction fraction : ambient) {
			out.append("A|").append(fraction.componentId()).append('|').append(fraction.fraction().canonical()).append('\n');
		}
		return out.toString().getBytes(StandardCharsets.UTF_8);
	}

	private static BigInteger lcm(BigInteger first, BigInteger second) {
		return first.divide(first.gcd(second)).multiply(second);
	}

	private static String sha256(byte[] bytes) {
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
		catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
	}
}
