package cc.ecisr.mmm.api;

import java.util.regex.Pattern;

/** Loader-neutral validation for canonical {@code namespace:path} resource identities. */
public final class StableId {
	private static final Pattern CANONICAL = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

	private StableId() { }

	public static boolean isValid(String value, int maximumLength) {
		return maximumLength > 0 && value != null && value.length() <= maximumLength
				&& CANONICAL.matcher(value).matches();
	}

	public static String require(String value, int maximumLength, String label) {
		if (!isValid(value, maximumLength)) {
			throw new IllegalArgumentException("Invalid " + label + ": " + value);
		}
		return value;
	}
}
