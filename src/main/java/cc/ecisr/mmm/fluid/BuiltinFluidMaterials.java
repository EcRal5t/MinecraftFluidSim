package cc.ecisr.mmm.fluid;

import java.util.List;

/** Stable built-in material definitions used by solver, snapshots and persistence. */
public final class BuiltinFluidMaterials {
	public static final FluidMaterial WATER = new FluidMaterial("mmm:water", 1_000.0, "mmm:aqueous", 0.0, Math.PI / 2.0);
	public static final FluidMaterial OIL = new FluidMaterial("mmm:oil", 800.0, "mmm:oil", 0.0, Math.PI / 2.0);
	public static final FluidMaterial MERCURY = new FluidMaterial("mmm:mercury", 13_600.0, "mmm:mercury", 0.0, Math.PI / 2.0);
	public static final List<FluidMaterial> DEFAULT_PALETTE = List.of(WATER, OIL, MERCURY);

	private BuiltinFluidMaterials() { }

	/** Display-only palette metadata; it never participates in marker inventory or solver coefficients. */
	public static int displayArgb(String materialId) {
		return switch (materialId) {
			case "mmm:water" -> 0xff197ae8;
			case "mmm:oil" -> 0xffc89a32;
			case "mmm:mercury" -> 0xffaeb6c2;
			default -> 0xffff00ff;
		};
	}
}
