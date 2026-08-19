package com.example.fluid;

import java.util.List;

/** Stable built-in M3 material definitions used by the opt-in live feature gate. */
public final class BuiltinFluidMaterials {
	public static final FluidMaterial WATER = new FluidMaterial("realwater:water", 1_000.0, "realwater:aqueous");
	public static final FluidMaterial OIL = new FluidMaterial("realwater:oil", 800.0, "realwater:oil");
	public static final FluidMaterial MERCURY = new FluidMaterial("realwater:mercury", 13_600.0, "realwater:mercury");
	public static final List<FluidMaterial> M3_PALETTE = List.of(WATER, OIL, MERCURY);

	private BuiltinFluidMaterials() { }

	/** Display-only palette metadata; it never participates in marker inventory or solver coefficients. */
	public static int displayArgb(String materialId) {
		return switch (materialId) {
			case "realwater:water" -> 0xff197ae8;
			case "realwater:oil" -> 0xffc89a32;
			case "realwater:mercury" -> 0xffaeb6c2;
			default -> 0xffff00ff;
		};
	}
}
