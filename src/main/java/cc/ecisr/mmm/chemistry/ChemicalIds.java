package cc.ecisr.mmm.chemistry;

import java.util.regex.Pattern;

final class ChemicalIds {
	private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");
	private static final Pattern BASIS = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,63}");

	private ChemicalIds() { }

	static String requireId(String value, String label) {
		if (value == null || value.length() > ChemicalLimits.MAX_CHEMICAL_ID_LENGTH || !ID.matcher(value).matches()) {
			throw new IllegalArgumentException("Invalid " + label + ": " + value);
		}
		return value;
	}

	static String requireBasis(String value) {
		if (value == null || !BASIS.matcher(value).matches()) throw new IllegalArgumentException("Invalid composition basis: " + value);
		return value;
	}
}
