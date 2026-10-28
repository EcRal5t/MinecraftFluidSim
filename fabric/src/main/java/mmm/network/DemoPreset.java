package mmm.network;

public enum DemoPreset {
	WATER, MATERIAL, CHEMISTRY, FLOW, AMBIENT, PRESSURE, VACUUM;
	public boolean tank() { return this == WATER || this == MATERIAL || this == CHEMISTRY; }
}
