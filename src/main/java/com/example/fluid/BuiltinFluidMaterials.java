package com.example.fluid;

import java.util.List;

/** Stable built-in material definitions. M4 surface-tension/contact-angle values are data-contract fields, but
 *  the wire and {@code region-state} codecs do not carry them yet, so the frozen built-ins stay on the M3 lossless
 *  defaults until a reviewed format bump carries real values into persistence. */
public final class BuiltinFluidMaterials {
	public static final FluidMaterial WATER = new FluidMaterial("realwater:water", 1_000.0, "realwater:aqueous", 0.0, Math.PI / 2.0);
	public static final FluidMaterial OIL = new FluidMaterial("realwater:oil", 800.0, "realwater:oil", 0.0, Math.PI / 2.0);
	public static final FluidMaterial MERCURY = new FluidMaterial("realwater:mercury", 13_600.0, "realwater:mercury", 0.0, Math.PI / 2.0);
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
