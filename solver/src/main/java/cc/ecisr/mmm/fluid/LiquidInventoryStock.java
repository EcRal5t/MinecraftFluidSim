package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import cc.ecisr.mmm.units.SignedDyadic;
import java.util.TreeMap;

/** Scratch P0 inventory for one mechanical control, material and indicator provenance. */
final class LiquidInventoryStock {
	record Key(int control, int material, boolean indicator) implements Comparable<Key> {
		@Override public int compareTo(Key other) {
			int c = Integer.compare(control, other.control); if (c != 0) return c;
			c = Integer.compare(material, other.material); return c != 0 ? c : Boolean.compare(indicator, other.indicator);
		}
	}
	final Key key;
	SignedDyadic volume = SignedDyadic.ZERO, mass = SignedDyadic.ZERO;
	Fraction energy = Fraction.ZERO;
	final TreeMap<Integer, Long> species = new TreeMap<>();
	double minimumTemperature = Double.POSITIVE_INFINITY, maximumTemperature = Double.NEGATIVE_INFINITY;
	LiquidInventoryStock(Key key) { this.key = key; }
}
