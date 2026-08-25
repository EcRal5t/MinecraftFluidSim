package cc.ecisr.mmm.granular;

import cc.ecisr.mmm.api.StableId;

final class GranularIds {
	private GranularIds() { }

	static String requireId(String value, String label) {
		return StableId.require(value, GranularLimits.MAX_STABLE_ID_LENGTH, label);
	}
}
