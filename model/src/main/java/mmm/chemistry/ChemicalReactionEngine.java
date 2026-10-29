package mmm.chemistry;

/** Chemical display calculations and reaction budget status. */
public final class ChemicalReactionEngine {
	private ChemicalReactionEngine() { }

	/** Strong-acid/base display approximation shared by queries and snapshots. */
	public static double ph(long acid, long base, double volumeCubicMeters) {
		if (!(Double.isFinite(volumeCubicMeters) && volumeCubicMeters > 0.0)) {
			throw new IllegalArgumentException("pH volume in m³ must be positive and finite");
		}
		long difference = acid >= base ? acid - base : base - acid;
		if (difference == 0L) return 7.0;
		double strength = Math.min(7.0, Math.log10(1.0 + (difference / (double) ChemicalLimits.QUANTA_PER_MOL) / (1e-3 * volumeCubicMeters)));
		return acid > base ? 7.0 - strength : 7.0 + strength;
	}

	/** The current pH model requires a water solvent and both acid/base species definitions. */
	public static boolean supportsPh(ChemicalPhaseDefinition phase) {
		return phase.allowedSpecies().contains(BuiltinChemicalDefinitions.H2O)
				&& phase.allowedSpecies().contains(BuiltinChemicalDefinitions.H_PLUS)
				&& phase.allowedSpecies().contains(BuiltinChemicalDefinitions.OH_MINUS)
				&& phase.defaultConcentrationMolPerCubicMeter().containsKey(BuiltinChemicalDefinitions.H2O);
	}

	public static final class BudgetExceededException extends IllegalStateException {
		public BudgetExceededException(String message) { super(message); }
	}
}
