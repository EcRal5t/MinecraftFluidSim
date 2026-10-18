package mmm.units;

import java.util.Locale;

/** Read-only SI presentation; rounded strings never enter inventory settlement. */
public final class UnitFormat {
	private UnitFormat() { }

	public static String amount(long micromoles) {
		if (micromoles < 0) throw new IllegalArgumentException("Negative chemical amount");
		if (micromoles == 0) return "0 mol";
		if (micromoles < 1_000) return micromoles + " μmol";
		if (micromoles < 1_000_000L) return value(micromoles / 1_000.0, "mmol");
		if (micromoles < 1_000_000_000L) return value(micromoles / 1_000_000.0, "mol");
		return value(micromoles / 1_000_000_000.0, "kmol");
	}

	public static String volume(double cubicMeters) {
		double magnitude = Math.abs(cubicMeters);
		if (magnitude > 0 && magnitude < .001) return value(cubicMeters * 1_000_000, "mL");
		if (magnitude > 0 && magnitude < 1) return value(cubicMeters * 1_000, "L");
		return value(cubicMeters, "m³");
	}

	public static String volumeNanos(long nanos) { return volume(nanos * 1e-9); }
	public static String solidVolume(long svq) { return volume(Math.scalb((double) svq, -27)); }
	public static String energy(double joules) { return scaled(joules, "J", "kJ", "MJ"); }
	public static String pressure(double pascals) { return scaled(pascals, "Pa", "kPa", "MPa"); }

	private static String scaled(double quantity, String base, String kilo, String mega) {
		double magnitude = Math.abs(quantity);
		return magnitude >= 1e6 ? value(quantity / 1e6, mega)
				: magnitude >= 1e3 ? value(quantity / 1e3, kilo) : value(quantity, base);
	}

	private static String value(double quantity, String unit) {
		if (!Double.isFinite(quantity)) throw new IllegalArgumentException("Non-finite display quantity");
		return String.format(Locale.ROOT, "%.6g %s", quantity, unit);
	}
}
