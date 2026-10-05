package cc.ecisr.mmm.fluid;

/** The shared interface operators identify actual control volumes needing subdivision. */
final class LiquidInterfaceResolutionRequired extends IllegalStateException {
	private final int[] cells;
	LiquidInterfaceResolutionRequired(int[] cells) {
		this(cells, "Conservative interface velocity support requires finer gas geometry");
	}
	LiquidInterfaceResolutionRequired(int[] cells, String reason) {
		super(reason);
		if (cells == null || cells.length == 0) throw new IllegalArgumentException("Missing interface resolution support");
		this.cells = cells.clone();
	}
	int[] cells() { return cells.clone(); }
}
