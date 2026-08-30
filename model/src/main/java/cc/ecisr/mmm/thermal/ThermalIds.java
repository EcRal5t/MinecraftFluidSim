package cc.ecisr.mmm.thermal;

import cc.ecisr.mmm.api.StableId;

final class ThermalIds {
	private ThermalIds() { }

	static String requireId(String value, String label) {
		return StableId.require(value, ThermalLimits.MAX_THERMAL_ID_LENGTH, label);
	}
}
