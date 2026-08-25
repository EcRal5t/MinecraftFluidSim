package cc.ecisr.mmm.chemistry;

import cc.ecisr.mmm.api.StableId;

import java.util.regex.Pattern;

final class ChemicalIds {
	private static final Pattern BASIS = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,63}");

	private ChemicalIds() { }

	static String requireId(String value, String label) {
		return StableId.require(value, ChemicalLimits.MAX_CHEMICAL_ID_LENGTH, label);
	}

	static String requireBasis(String value) {
		if (value == null || !BASIS.matcher(value).matches()) throw new IllegalArgumentException("Invalid composition basis: " + value);
		return value;
	}
}
