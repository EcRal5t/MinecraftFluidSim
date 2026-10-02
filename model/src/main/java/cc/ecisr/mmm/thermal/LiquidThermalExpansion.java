package cc.ecisr.mmm.thermal;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Bounded Boussinesq constitutive definition and built-in reference data.
 *
 * <p>Each world's material registry saves its coefficients and validity interval. The separate model
 * fingerprint binds the formula, carrier observation and pressure-face averaging used by thermal regions.</p>
 */
public final class LiquidThermalExpansion {
	public static final String MODEL_ID = "mmm:boussinesq";

	public record Definition(String carrierId, double referenceTemperatureK, double volumetricCoefficientPerK,
			double maximumRelativeDensityChange) {
		public Definition(String carrierId, double referenceTemperatureK, double volumetricCoefficientPerK) {
			this(carrierId, referenceTemperatureK, volumetricCoefficientPerK, 0.05);
		}
		public Definition {
			ThermalIds.requireId(carrierId, "liquid expansion carrier id");
			if (!(Double.isFinite(referenceTemperatureK) && referenceTemperatureK > 0.0
					&& Double.isFinite(volumetricCoefficientPerK) && volumetricCoefficientPerK > 0.0
					&& Double.isFinite(maximumRelativeDensityChange) && maximumRelativeDensityChange > 0.0
					&& maximumRelativeDensityChange < 1.0)) {
				throw new IllegalArgumentException("Liquid thermal expansion definition is invalid");
			}
		}

		public double relativeBuoyancy(double temperatureK) {
			if (!Double.isFinite(temperatureK) || temperatureK < Double.MIN_NORMAL)
				throw new IllegalArgumentException("Liquid temperature must be finite and positive");
			double relative = volumetricCoefficientPerK * (temperatureK - referenceTemperatureK);
			if (Math.abs(relative) > maximumRelativeDensityChange) throw new ValidityExceededException(
					"Boussinesq " + carrierId + " at " + temperatureK + " K: |rho-rho_ref|/rho_ref="
							+ Math.abs(relative) + " exceeds " + maximumRelativeDensityChange);
			return relative;
		}
	}

	/** The complete private mechanical candidate is rejected when its thermal approximation expires. */
	public static final class ValidityExceededException extends IllegalStateException {
		public ValidityExceededException(String message) { super(message); }
	}

	private static final List<Definition> DEFINITIONS = List.of(
			new Definition("mmm:mercury", 293.15, 1.82E-4),
			new Definition("mmm:oil", 293.15, 7.00E-4),
			new Definition("mmm:water", 293.15, 2.07E-4)
	);
	private static final Map<String, Definition> BY_CARRIER = DEFINITIONS.stream()
			.collect(Collectors.toUnmodifiableMap(Definition::carrierId, Function.identity()));
	private static final byte[] CANONICAL = canonical();
	private static final String FINGERPRINT = sha256(CANONICAL);

	private LiquidThermalExpansion() { }

	public static List<Definition> definitions() { return DEFINITIONS; }
	public static String fingerprint() { return FINGERPRINT; }
	public static Definition find(String carrierId) { return BY_CARRIER.get(carrierId); }

	public static Definition require(String carrierId) {
		Definition definition = BY_CARRIER.get(carrierId);
		if (definition == null) throw new IllegalArgumentException(
				"No frozen liquid thermal expansion definition for " + carrierId);
		return definition;
	}

	private static byte[] canonical() {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (DataOutputStream output = new DataOutputStream(bytes)) {
				output.writeUTF(MODEL_ID);
				output.writeUTF("beta*(T_carrier-T_ref);connected_single_phase_trilinear_mass_volume;pressure_face_density_segments;closed_carrier_relative_density_domain");
			}
			return bytes.toByteArray();
		} catch (IOException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	private static String sha256(byte[] bytes) {
		try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
		catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
	}
}
