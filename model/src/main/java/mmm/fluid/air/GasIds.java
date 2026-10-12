package mmm.fluid.air;

import mmm.api.StableId;

final class GasIds {
	private GasIds() { }

	static String require(String value, String label) {
		return StableId.require(value, GasLimits.MAX_GAS_ID_LENGTH, label);
	}
}
