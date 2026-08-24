package com.example.thermal;

import java.util.regex.Pattern;

final class ThermalIds {
	private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

	private ThermalIds() { }

	static String requireId(String value, String label) {
		if (value == null || value.length() > ThermalLimits.MAX_THERMAL_ID_LENGTH || !ID.matcher(value).matches()) {
			throw new IllegalArgumentException("Invalid " + label + ": " + value);
		}
		return value;
	}
}
