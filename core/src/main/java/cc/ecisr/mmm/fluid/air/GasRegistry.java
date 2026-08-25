package cc.ecisr.mmm.fluid.air;

import cc.ecisr.mmm.chemistry.ExactRational;

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
	public static final String UNIT_SYSTEM_ID = "mmm:gas_standard_v1";
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

	/** Creates one explicit ambient-source inventory with exact total standard nanos. */
	public GasInventory createAmbient(long standardVolumeNanos) {
		if (standardVolumeNanos < 0L) throw new IllegalArgumentException("Ambient gas volume cannot be negative");
		if (standardVolumeNanos == 0L) return GasInventory.EMPTY;
		long[] shares = rationalShares(standardVolumeNanos, ambient.stream().map(AmbientFraction::fraction).toList());
		ArrayList<GasComponentAmount> amounts = new ArrayList<>(shares.length);
		for (int index = 0; index < shares.length; index++) {
			long nanos = shares[index];
			if (nanos == 0L) continue;
			GasComponentDefinition definition = component(ambient.get(index).componentId());
			double mass = definition.standardMassPerCubicMeter() * AirNetwork.cubicMeters(nanos);
			ArrayList<GasComponentAmount.SpeciesAmount> species = new ArrayList<>();
			for (GasComponentDefinition.DefaultSpecies entry : definition.defaultSpeciesPerCubicMeter()) {
				BigInteger numerator = entry.quantaPerCubicMeter().numerator().multiply(BigInteger.valueOf(nanos));
				BigInteger denominator = entry.quantaPerCubicMeter().denominator().multiply(BigInteger.valueOf(1_000_000_000L));
				long quanta = numerator.divide(denominator).longValueExact();
				if (quanta > 0L) species.add(new GasComponentAmount.SpeciesAmount(entry.speciesId(), quanta));
			}
			double residual = definition.defaultMassResidualPerCubicMeter() * AirNetwork.cubicMeters(nanos);
			amounts.add(new GasComponentAmount(definition.id(), nanos, mass, species, residual));
		}
		return new GasInventory(amounts);
	}

	private byte[] encodeCanonical() {
		StringBuilder out = new StringBuilder(1024).append("mmm-gas-registry\n")
				.append("units=").append(UNIT_SYSTEM_ID).append('\n')
				.append("pressure=").append(Long.toUnsignedString(Double.doubleToRawLongBits(STANDARD_PRESSURE_PA))).append('\n')
				.append("temperature=").append(Long.toUnsignedString(Double.doubleToRawLongBits(STANDARD_TEMPERATURE_K))).append('\n');
		for (GasComponentDefinition component : components) {
			out.append("C|").append(component.id()).append('|')
					.append(Long.toUnsignedString(Double.doubleToRawLongBits(component.standardMassPerCubicMeter()))).append('|')
					.append(Long.toUnsignedString(Double.doubleToRawLongBits(component.defaultMassResidualPerCubicMeter()))).append('|');
			for (GasComponentDefinition.DefaultSpecies species : component.defaultSpeciesPerCubicMeter()) {
				out.append(species.speciesId()).append('=').append(species.quantaPerCubicMeter().canonical()).append(';');
			}
			out.append('\n');
		}
		for (AmbientFraction fraction : ambient) {
			out.append("A|").append(fraction.componentId()).append('|').append(fraction.fraction().canonical()).append('\n');
		}
		return out.toString().getBytes(StandardCharsets.UTF_8);
	}

	private static long[] rationalShares(long total, List<ExactRational> weights) {
		BigInteger common = BigInteger.ONE;
		for (ExactRational weight : weights) common = lcm(common, weight.denominator());
		ArrayList<BigInteger> integers = new ArrayList<>(weights.size());
		for (ExactRational weight : weights) integers.add(weight.numerator().multiply(common.divide(weight.denominator())));
		return GasInventory.largestRemainder(total, integers);
	}

	private static BigInteger lcm(BigInteger first, BigInteger second) {
		return first.divide(first.gcd(second)).multiply(second);
	}

	private static String sha256(byte[] bytes) {
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
		catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
	}
}
