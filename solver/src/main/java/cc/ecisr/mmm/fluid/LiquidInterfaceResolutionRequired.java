package cc.ecisr.mmm.fluid;

/** The conservative interface extension identifies the actual gas support needing subdivision. */
final class LiquidInterfaceResolutionRequired extends IllegalStateException {
	private final int[] cells;
	LiquidInterfaceResolutionRequired(int[] cells) {
		super("Conservative interface velocity support requires finer gas geometry");
		if (cells == null || cells.length == 0) throw new IllegalArgumentException("Missing gas resolution support");
		this.cells = cells.clone();
	}
	int[] cells() { return cells.clone(); }
}
