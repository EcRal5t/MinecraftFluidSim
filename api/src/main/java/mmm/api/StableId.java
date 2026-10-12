package mmm.api;

/** Loader-neutral validation for canonical {@code namespace:path} resource identities. */
public final class StableId {
	private StableId() { }

	public static boolean isValid(String value, int maximumLength) {
		if (maximumLength <= 0 || value == null || value.length() > maximumLength) return false;
		int separator = -1;
		for (int index = 0; index < value.length(); index++) {
			char c = value.charAt(index);
			if (c == ':') {
				if (separator >= 0 || index == 0 || index + 1 == value.length()) return false;
				separator = index;
			} else if (!(c >= 'a' && c <= 'z' || c >= '0' && c <= '9'
					|| c == '_' || c == '.' || c == '-' || c == '/' && separator >= 0)) return false;
		}
		return separator >= 0;
	}

	public static String require(String value, int maximumLength, String label) {
		if (!isValid(value, maximumLength)) {
			throw new IllegalArgumentException("Invalid " + label + ": " + value);
		}
		return value;
	}
}
