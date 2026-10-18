package mmm.fluid;

/** Finite-volume replacement keeps quantities, macroscopic transport and recovery together. */
public final class FlipFluidReseedingValidation {
	private FlipFluidReseedingValidation() { }
	public static void main(String[] args) {
		LiquidCellInventoryValidation.main(new String[0]);
		LiquidMacroscopicMotionValidation.main(new String[]{"joint-moments"});
		System.out.println("Cell inventory and macroscopic inertia validation passed");
	}
}
