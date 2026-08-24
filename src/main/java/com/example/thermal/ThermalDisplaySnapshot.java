package com.example.thermal;

import java.util.List;

/** Read-only quantized M9 display state; it is never accepted as inventory or persistence truth. */
public record ThermalDisplaySnapshot(
		short[] cellTemperatureQ,
		byte[] coherentFraction,
		byte[] debugFlags,
		List<GasNode> gasNodes
) {
	public static final int EMPTY_TEMPERATURE_Q = 0xffff;
	public static final int FLAG_CONDENSED = 1;
	public static final int FLAG_COHERENT = 1 << 1;

	public ThermalDisplaySnapshot {
		cellTemperatureQ = cellTemperatureQ.clone();
		coherentFraction = coherentFraction.clone();
		debugFlags = debugFlags.clone();
		gasNodes = List.copyOf(gasNodes);
		if (cellTemperatureQ.length != coherentFraction.length
				|| cellTemperatureQ.length != debugFlags.length) {
			throw new IllegalArgumentException("Thermal display cell arrays differ in length");
		}
	}

	@Override public short[] cellTemperatureQ() { return cellTemperatureQ.clone(); }
	@Override public byte[] coherentFraction() { return coherentFraction.clone(); }
	@Override public byte[] debugFlags() { return debugFlags.clone(); }

	public record GasNode(int nodeId, int temperatureQ, int gaugePressureDecaPa) {
		public GasNode {
			if (nodeId <= 0 || temperatureQ <= 0 || temperatureQ >= EMPTY_TEMPERATURE_Q) {
				throw new IllegalArgumentException("Thermal display gas node is invalid");
			}
		}
	}
}
