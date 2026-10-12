package mmm.units;

/** Two finite inventory columns: compute the smaller share directly and round the larger remainder once. */
public record ProportionalDoubleSplit(double removed, double remaining) {
	public static ProportionalDoubleSplit of(double source, long taken, long total) {
		if (!Double.isFinite(source) || total <= 0L || taken < 0L || taken > total) {
			throw new IllegalArgumentException("Proportional inventory split is invalid");
		}
		if (taken == 0L) return new ProportionalDoubleSplit(0.0, source);
		if (taken == total) return new ProportionalDoubleSplit(source, 0.0);
		long retained = total - taken;
		double smaller = source * ((double) Math.min(taken, retained) / total);
		double larger = SignedDyadic.of(source).subtract(SignedDyadic.of(smaller)).toDouble();
		return taken <= retained ? new ProportionalDoubleSplit(smaller, larger)
				: new ProportionalDoubleSplit(larger, smaller);
	}
}
