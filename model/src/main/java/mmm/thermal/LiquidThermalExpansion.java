package mmm.thermal;

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

/** Fixed-volume liquid buoyancy from a positive linear thermal-volume constitutive approximation. */
public final class LiquidThermalExpansion {
	public static final String MODEL_ID = "mmm:liquid_volume_expansion";

	public record Definition(String carrierId, double referenceTemperatureK, double volumetricCoefficientPerK) {
		public Definition {
			ThermalIds.requireId(carrierId, "liquid expansion carrier id");
			if (!(Double.isFinite(referenceTemperatureK) && referenceTemperatureK > 0.0
					&& Double.isFinite(volumetricCoefficientPerK) && volumetricCoefficientPerK > 0.0))
				throw new IllegalArgumentException("Liquid thermal expansion definition is invalid");
		}

		/** Uses rho/rho_ref = 1/(1+alpha*dT); inventory density and caloric energy retain their own state. */
		public double relativeBuoyancy(double temperatureK) {
			if (!Double.isFinite(temperatureK) || temperatureK <= 0)
				throw new IllegalArgumentException("Liquid temperature must be finite and positive");
			double expansion = volumetricCoefficientPerK * (temperatureK - referenceTemperatureK);
			double volumeRatio = 1 + expansion;
			if (!(Double.isFinite(volumeRatio) && volumeRatio > 0))
				throw new IllegalArgumentException("Liquid " + carrierId + " has nonpositive or unrepresentable thermal volume at " + temperatureK + " K");
			return expansion / volumeRatio;
		}
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
				"No liquid thermal expansion definition for " + carrierId);
		return definition;
	}

	private static byte[] canonical() {
		try {
			ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (DataOutputStream output = new DataOutputStream(bytes)) {
				output.writeUTF(MODEL_ID);
				output.writeUTF("alpha*dT/(1+alpha*dT);connected_single_phase_trilinear_mass_volume;pressure_face_density_segments;positive_p2g_fringe_mass_average;positive_thermal_volume");
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
