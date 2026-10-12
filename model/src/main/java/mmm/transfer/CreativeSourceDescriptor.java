package mmm.transfer;

import java.util.Objects;

/** Creative-player intent only; this descriptor never represents physical inventory. */
public record CreativeSourceDescriptor(String templateStableId, long requestedTypedAmount, Double temperatureKelvin) {
	public CreativeSourceDescriptor {
		templateStableId = Objects.requireNonNull(templateStableId, "templateStableId");
		if (!templateStableId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || templateStableId.length() > 128) {
			throw new IllegalArgumentException("Creative source template id is invalid");
		}
		if (requestedTypedAmount <= 0L) throw new IllegalArgumentException("Creative source amount must be positive");
		if (temperatureKelvin != null && !(Double.isFinite(temperatureKelvin) && temperatureKelvin > 0.0)) {
			throw new IllegalArgumentException("Creative source temperature is invalid");
		}
	}

	public String encode() {
		return templateStableId + ";" + requestedTypedAmount + ";"
				+ (temperatureKelvin == null ? "-" : Long.toUnsignedString(
						Double.doubleToRawLongBits(temperatureKelvin), 16));
	}

	public static CreativeSourceDescriptor decode(String encoded) {
		if (encoded == null || encoded.length() > 192) {
			throw new IllegalArgumentException("Creative source encoding is invalid");
		}
		String[] fields = encoded.split(";", -1);
		if (fields.length != 3) throw new IllegalArgumentException("Creative source field count is invalid");
		try {
			long amount = Long.parseLong(fields[1]);
			Double temperature = fields[2].equals("-") ? null
					: Double.longBitsToDouble(Long.parseUnsignedLong(fields[2], 16));
			CreativeSourceDescriptor descriptor = new CreativeSourceDescriptor(fields[0], amount, temperature);
			if (!descriptor.encode().equals(encoded)) {
				throw new IllegalArgumentException("Creative source encoding is not canonical");
			}
			return descriptor;
		} catch (NumberFormatException invalid) {
			throw new IllegalArgumentException("Creative source numeric field is invalid", invalid);
		}
	}
}
