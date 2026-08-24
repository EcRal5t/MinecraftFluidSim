package com.example.fluid.air;

import java.util.regex.Pattern;

final class GasIds {
	private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

	private GasIds() { }

	static String require(String value, String label) {
		if (value == null || value.length() > GasLimits.MAX_GAS_ID_LENGTH || !ID.matcher(value).matches()) {
			throw new IllegalArgumentException("Invalid " + label + ": " + value);
		}
		return value;
	}
}
