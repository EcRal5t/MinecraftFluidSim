package mmm.thermal;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

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

	private static final byte[] CANONICAL = canonical();
	private static final String FINGERPRINT = sha256(CANONICAL);

	private LiquidThermalExpansion() { }

	public static String fingerprint() { return FINGERPRINT; }

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
