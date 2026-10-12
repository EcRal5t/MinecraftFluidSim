package mmm.fluid;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.TreeSet;
import mmm.thermal.LiquidThermalExpansion;

/**
 * Immutable world-authoritative material definitions: base fluid properties, optional Newtonian
 * dynamic-viscosity segments, bounded thermal expansion and liquid-liquid pair tensions keyed by stable miscibility groups.
 */
public final class MaterialRegistry {
	public static final String UNIT_SYSTEM_ID = "mmm:material_standard";
	public static final int MAX_MATERIALS = 256;
	public static final int MAX_PAIR_TENSIONS = 4096;

	/**
	 * Standard reference temperature for material properties. Material definitions carry no thermal
	 * host of their own, so a property evaluated without a bound thermal carrier uses this state.
	 */
	public static final double STANDARD_TEMPERATURE_K = 293.15;

	/** Newtonian dynamic viscosity, linear in temperature on one closed interval of a contiguous domain. */
	public record ViscositySegment(double minimumTemperatureK, double maximumTemperatureK,
			double dynamicViscosityPaPerSAtMinimum, double dynamicViscosityPaPerSAtMaximum) {
		public ViscositySegment {
			if (!(Double.isFinite(minimumTemperatureK) && Double.isFinite(maximumTemperatureK)
					&& minimumTemperatureK > 0.0 && minimumTemperatureK < maximumTemperatureK)) {
				throw new IllegalArgumentException("Viscosity segment temperature bounds are invalid");
			}
			if (!(Double.isFinite(dynamicViscosityPaPerSAtMinimum) && dynamicViscosityPaPerSAtMinimum > 0.0
					&& Double.isFinite(dynamicViscosityPaPerSAtMaximum) && dynamicViscosityPaPerSAtMaximum > 0.0)) {
				throw new IllegalArgumentException("Dynamic viscosity must be finite and positive");
			}
		}
	}

	public record MaterialEntry(FluidMaterial material, List<ViscositySegment> viscosity,
			LiquidThermalExpansion.Definition thermalExpansion) {
		public MaterialEntry(FluidMaterial material, List<ViscositySegment> viscosity) {
			this(material, viscosity, material == null ? null : LiquidThermalExpansion.find(material.id()));
		}
		public MaterialEntry {
			if (material == null) throw new IllegalArgumentException("Material definition is missing");
			if (thermalExpansion != null && !thermalExpansion.carrierId().equals(material.id()))
				throw new IllegalArgumentException("Thermal expansion carrier disagrees with material " + material.id());
			TreeMap<Double, ViscositySegment> sorted = new TreeMap<>();
			if (viscosity != null) {
				for (ViscositySegment segment : viscosity) {
					if (segment == null) throw new IllegalArgumentException("Viscosity segment is missing");
					if (sorted.put(segment.minimumTemperatureK(), segment) != null) {
						throw new IllegalArgumentException("Duplicate viscosity segment start for " + material.id());
					}
				}
			}
			ArrayList<ViscositySegment> ordered = new ArrayList<>(sorted.values());
			for (int index = 1; index < ordered.size(); index++) {
				ViscositySegment previous = ordered.get(index - 1);
				ViscositySegment current = ordered.get(index);
				if (previous.maximumTemperatureK() != current.minimumTemperatureK()
						|| previous.dynamicViscosityPaPerSAtMaximum() != current.dynamicViscosityPaPerSAtMinimum()) {
					throw new IllegalArgumentException("Viscosity segments must be contiguous for " + material.id());
				}
			}
			viscosity = List.copyOf(ordered);
		}

		public boolean declaresViscosity() { return !viscosity.isEmpty(); }

		public double dynamicViscosityAt(double temperatureK) {
			requireTemperature(temperatureK);
			if (!declaresViscosity()) {
				throw new IllegalArgumentException("Material " + material.id() + " does not declare dynamic viscosity");
			}
			if (temperatureK < viscosity.getFirst().minimumTemperatureK()
					|| temperatureK > viscosity.getLast().maximumTemperatureK()) {
				throw new IllegalArgumentException("Temperature " + temperatureK + " is outside the declared viscosity domain of "
						+ material.id());
			}
			return interpolate(temperatureK);
		}

		/**
		 * Viscosity for a carrier coupling that must stay defined over the whole thermal domain.
		 *
		 * <p>Temperatures outside the declared segments take the nearest segment's endpoint value, the
		 * same continuous continuation the caloric state functions use outside their measured range.
		 * Nothing is invented between measured points; only the boundary value is held. The caller
		 * records the excursion so it stays observable instead of being silently clamped.
		 */
		public double dynamicViscosityExtendingAt(double temperatureK) {
			requireTemperature(temperatureK);
			if (!declaresViscosity()) {
				throw new IllegalArgumentException("Material " + material.id() + " does not declare dynamic viscosity");
			}
			return interpolate(Math.min(viscosity.getLast().maximumTemperatureK(),
					Math.max(viscosity.getFirst().minimumTemperatureK(), temperatureK)));
		}

		public boolean viscosityDomainContains(double temperatureK) {
			return declaresViscosity() && temperatureK >= viscosity.getFirst().minimumTemperatureK()
					&& temperatureK <= viscosity.getLast().maximumTemperatureK();
		}

		/** Lowest declared viscosity temperature; diagnostics only. */
		public double minimumViscosityTemperatureK() {
			if (!declaresViscosity()) {
				throw new IllegalArgumentException("Material " + material.id() + " does not declare dynamic viscosity");
			}
			return viscosity.getFirst().minimumTemperatureK();
		}

		/** Highest declared viscosity temperature; diagnostics only. */
		public double maximumViscosityTemperatureK() {
			if (!declaresViscosity()) {
				throw new IllegalArgumentException("Material " + material.id() + " does not declare dynamic viscosity");
			}
			return viscosity.getLast().maximumTemperatureK();
		}

		private double interpolate(double temperatureK) {
			int low = 0;
			int high = viscosity.size() - 1;
			while (low < high) {
				int middle = (low + high) >>> 1;
				if (viscosity.get(middle).maximumTemperatureK() < temperatureK) low = middle + 1;
				else high = middle;
			}
			ViscositySegment segment = viscosity.get(low);
			if (temperatureK == segment.minimumTemperatureK()) return segment.dynamicViscosityPaPerSAtMinimum();
			if (temperatureK == segment.maximumTemperatureK()) return segment.dynamicViscosityPaPerSAtMaximum();
			double span = segment.maximumTemperatureK() - segment.minimumTemperatureK();
			double alpha = span <= 0.0 ? 0.0 : (temperatureK - segment.minimumTemperatureK()) / span;
			return segment.dynamicViscosityPaPerSAtMinimum() * (1.0 - alpha)
					+ segment.dynamicViscosityPaPerSAtMaximum() * alpha;
		}

		private static void requireTemperature(double temperatureK) {
			if (!Double.isFinite(temperatureK) || temperatureK < Double.MIN_NORMAL) {
				throw new IllegalArgumentException("Viscosity temperature must be finite and in the positive thermal domain");
			}
		}
	}

	/** Liquid-liquid interfacial tension between two distinct miscibility groups, keyed by stable ids. */
	public record LiquidPairTension(String firstMiscibilityGroup, String secondMiscibilityGroup, double tension) {
		public LiquidPairTension {
			if (firstMiscibilityGroup == null || secondMiscibilityGroup == null
					|| firstMiscibilityGroup.equals(secondMiscibilityGroup)) {
				throw new IllegalArgumentException("Liquid pair tension requires two distinct miscibility groups");
			}
			if (!(Double.isFinite(tension) && tension >= 0.0)) {
				throw new IllegalArgumentException("Liquid pair tension must be finite and non-negative");
			}
		}
	}

	private final List<MaterialEntry> entries;
	private final Map<String, MaterialEntry> entryById;
	private final List<String> miscibilityGroups;
	private final List<LiquidPairTension> pairTensions;
	private final byte[] canonicalBytes;
	private final String fingerprint;

	public MaterialRegistry(List<MaterialEntry> entries, List<LiquidPairTension> pairTensions) {
		if (entries == null || entries.isEmpty() || entries.size() > MAX_MATERIALS) {
			throw new IllegalArgumentException("Material registry is empty or over budget");
		}
		ArrayList<MaterialEntry> canonicalEntries = new ArrayList<>(entries);
		canonicalEntries.sort(Comparator.comparing(entry -> entry.material().id()));
		HashMap<String, MaterialEntry> byId = new HashMap<>();
		TreeSet<String> groups = new TreeSet<>();
		for (MaterialEntry entry : canonicalEntries) {
			if (byId.put(entry.material().id(), entry) != null) {
				throw new IllegalArgumentException("Duplicate material id " + entry.material().id());
			}
			groups.add(entry.material().miscibilityGroup());
		}
		ArrayList<LiquidPairTension> canonicalPairs = new ArrayList<>();
		if (pairTensions != null) {
			if (pairTensions.size() > MAX_PAIR_TENSIONS) throw new IllegalArgumentException("Liquid pair tensions over budget");
			canonicalPairs.addAll(pairTensions);
			canonicalPairs.sort(Comparator.comparing(LiquidPairTension::firstMiscibilityGroup)
					.thenComparing(LiquidPairTension::secondMiscibilityGroup));
			LiquidPairTension previous = null;
			for (LiquidPairTension pair : canonicalPairs) {
				if (!groups.contains(pair.firstMiscibilityGroup()) || !groups.contains(pair.secondMiscibilityGroup())) {
					throw new IllegalArgumentException("Liquid pair tension references an unknown miscibility group");
				}
				if (previous != null
						&& previous.firstMiscibilityGroup().equals(pair.firstMiscibilityGroup())
						&& previous.secondMiscibilityGroup().equals(pair.secondMiscibilityGroup())) {
					throw new IllegalArgumentException("Duplicate liquid pair tension for the same phase pair");
				}
				previous = pair;
			}
		}
		this.entries = List.copyOf(canonicalEntries);
		this.entryById = Map.copyOf(byId);
		this.miscibilityGroups = List.copyOf(groups);
		this.pairTensions = List.copyOf(canonicalPairs);
		this.canonicalBytes = encodeCanonical();
		this.fingerprint = sha256(canonicalBytes);
	}

	public List<MaterialEntry> entries() { return entries; }
	public List<String> miscibilityGroups() { return miscibilityGroups; }
	public List<LiquidPairTension> pairTensions() { return pairTensions; }
	public int size() { return entries.size(); }
	public boolean hasMaterial(String id) { return entryById.containsKey(id); }
	public byte[] canonicalBytes() { return canonicalBytes.clone(); }
	public String fingerprint() { return fingerprint; }

	public MaterialEntry entry(String id) {
		MaterialEntry entry = entryById.get(id);
		if (entry == null) throw new IllegalArgumentException("Unknown material " + id);
		return entry;
	}

	public boolean declaresViscosity(String id) { return entry(id).declaresViscosity(); }

	public double dynamicViscosityAt(String id, double temperatureK) { return entry(id).dynamicViscosityAt(temperatureK); }

	/** Continuation lookup by stable id; see {@link MaterialEntry#dynamicViscosityExtendingAt}. */
	public double dynamicViscosityExtendingAt(String id, double temperatureK) {
		return entry(id).dynamicViscosityExtendingAt(temperatureK);
	}

	/** True when the temperature sits inside the id's declared viscosity domain. */
	public boolean viscosityDomainContains(String id, double temperatureK) {
		return entry(id).viscosityDomainContains(temperatureK);
	}

	public double liquidPairTension(String firstGroup, String secondGroup) {
		String low = firstGroup.compareTo(secondGroup) <= 0 ? firstGroup : secondGroup;
		String high = firstGroup.compareTo(secondGroup) <= 0 ? secondGroup : firstGroup;
		int lowIndex = 0;
		int highIndex = pairTensions.size() - 1;
		while (lowIndex <= highIndex) {
			int middle = (lowIndex + highIndex) >>> 1;
			LiquidPairTension pair = pairTensions.get(middle);
			int comparison = pair.firstMiscibilityGroup().compareTo(low);
			if (comparison == 0) comparison = pair.secondMiscibilityGroup().compareTo(high);
			if (comparison < 0) lowIndex = middle + 1;
			else if (comparison > 0) highIndex = middle - 1;
			else return pair.tension();
		}
		return 0.0;
	}

	private byte[] encodeCanonical() {
		StringBuilder out = new StringBuilder(1024).append("mmm-material-registry\n")
				.append("units=").append(UNIT_SYSTEM_ID).append('\n');
		for (MaterialEntry entry : entries) {
			FluidMaterial material = entry.material();
			out.append("M|").append(material.id()).append('|')
					.append(Long.toUnsignedString(Double.doubleToRawLongBits(material.referenceDensity()))).append('|')
					.append(material.miscibilityGroup()).append('|')
					.append(Long.toUnsignedString(Double.doubleToRawLongBits(material.surfaceTension()))).append('|')
					.append(Long.toUnsignedString(Double.doubleToRawLongBits(material.contactAngleRadians()))).append('\n');
			LiquidThermalExpansion.Definition expansion = entry.thermalExpansion();
			if (expansion != null) out.append("B|").append(material.id()).append('|')
					.append(Long.toUnsignedString(Double.doubleToRawLongBits(expansion.referenceTemperatureK()))).append('|')
					.append(Long.toUnsignedString(Double.doubleToRawLongBits(expansion.volumetricCoefficientPerK()))).append('|')
					.append(Long.toUnsignedString(Double.doubleToRawLongBits(expansion.maximumRelativeDensityChange()))).append('\n');
			for (ViscositySegment segment : entry.viscosity()) {
				out.append("V|").append(material.id()).append('|')
						.append(Long.toUnsignedString(Double.doubleToRawLongBits(segment.minimumTemperatureK()))).append('|')
						.append(Long.toUnsignedString(Double.doubleToRawLongBits(segment.maximumTemperatureK()))).append('|')
						.append(Long.toUnsignedString(Double.doubleToRawLongBits(segment.dynamicViscosityPaPerSAtMinimum()))).append('|')
						.append(Long.toUnsignedString(Double.doubleToRawLongBits(segment.dynamicViscosityPaPerSAtMaximum()))).append('\n');
			}
		}
		for (LiquidPairTension pair : pairTensions) {
			out.append("P|").append(pair.firstMiscibilityGroup()).append('|')
					.append(pair.secondMiscibilityGroup()).append('|')
					.append(Long.toUnsignedString(Double.doubleToRawLongBits(pair.tension()))).append('\n');
		}
		out.append("end\n");
		return out.toString().getBytes(StandardCharsets.UTF_8);
	}

	private static String sha256(byte[] payload) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 unavailable", exception);
		}
	}
}
