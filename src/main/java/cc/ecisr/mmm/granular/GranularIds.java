package cc.ecisr.mmm.granular;

import java.util.regex.Pattern;

final class GranularIds {
	private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

	private GranularIds() { }

	static String requireId(String value, String label) {
		if (value == null || value.length() > GranularLimits.MAX_STABLE_ID_LENGTH || !ID.matcher(value).matches()) {
			throw new IllegalArgumentException("Invalid " + label + ": " + value);
		}
		return value;
	}
}
