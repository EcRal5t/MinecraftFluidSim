package mmm.chemistry;

/** Exact mol/μmol conversion for integer chemical inventories. */
public final class ChemicalAmount {
	private ChemicalAmount() { }

	public static ExactRational moles(long quanta) {
		if (quanta < 0) throw new IllegalArgumentException("Negative chemical inventory");
		return ExactRational.of(quanta).divide(ChemicalLimits.QUANTA_PER_MOL);
	}

	/** Requires an exact whole μmol; callers plan fractional transfers before committing. */
	public static long quanta(ExactRational moles) {
		if (moles == null || moles.signum() < 0) throw new IllegalArgumentException("Invalid mol amount");
		ExactRational amount = moles.multiply(ChemicalLimits.QUANTA_PER_MOL);
		if (!amount.denominator().equals(java.math.BigInteger.ONE)) {
			throw new IllegalArgumentException("Chemical amount is not an integer micromole");
		}
		return amount.numerator().longValueExact();
	}
}
