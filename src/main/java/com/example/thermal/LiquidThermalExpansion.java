package com.example.thermal;

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
 * Frozen thermophysical data for the built-in liquid Boussinesq model.
 *
 * <p>The coefficients are volumetric expansion coefficients near 293.15 K. They are definition
 * truth, not a stability knob: the model fingerprint is persisted by region-state v7 so later
 * executable changes cannot silently reinterpret an existing velocity/temperature field.</p>
 */
public final class LiquidThermalExpansion {
	public static final String MODEL_ID = "realwater:boussinesq_builtin_v1";

	public record Definition(String carrierId, double referenceTemperatureK, double volumetricCoefficientPerK) {
		public Definition {
			ThermalIds.requireId(carrierId, "liquid expansion carrier id");
			if (!(Double.isFinite(referenceTemperatureK) && referenceTemperatureK > 0.0
					&& Double.isFinite(volumetricCoefficientPerK) && volumetricCoefficientPerK > 0.0)) {
				throw new IllegalArgumentException("Liquid thermal expansion definition is invalid");
			}
		}

		public double relativeBuoyancy(double temperatureK) {
			if (!Double.isFinite(temperatureK)) throw new IllegalArgumentException("Liquid temperature is not finite");
			return volumetricCoefficientPerK * (temperatureK - referenceTemperatureK);
		}
	}

	private static final List<Definition> DEFINITIONS = List.of(
			new Definition("realwater:mercury", 293.15, 1.82E-4),
			new Definition("realwater:oil", 293.15, 7.00E-4),
			new Definition("realwater:water", 293.15, 2.07E-4)
	);
	private static final Map<String, Definition> BY_CARRIER = DEFINITIONS.stream()
			.collect(Collectors.toUnmodifiableMap(Definition::carrierId, Function.identity()));
	private static final byte[] CANONICAL = canonical();
	private static final String FINGERPRINT = sha256(CANONICAL);

	private LiquidThermalExpansion() { }

	public static List<Definition> definitions() { return DEFINITIONS; }
	public static String fingerprint() { return FINGERPRINT; }

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
				output.writeInt(DEFINITIONS.size());
				for (Definition definition : DEFINITIONS) {
					output.writeUTF(definition.carrierId());
					output.writeLong(Double.doubleToRawLongBits(definition.referenceTemperatureK()));
					output.writeLong(Double.doubleToRawLongBits(definition.volumetricCoefficientPerK()));
				}
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
