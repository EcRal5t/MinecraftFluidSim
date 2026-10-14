package mmm.fluid;

import java.util.Arrays;
import java.util.LinkedHashMap;

/** Preview-local reuse of fully identical, successfully certified compression inputs. */
final class GranularContactMemo {
	private static final long MAX_WORDS = 1_048_576 / Long.BYTES;
	private final Runnable spend;
	private final LinkedHashMap<Key, GranularContactCompression.Result> entries = new LinkedHashMap<>(16, .75F, true);
	private long words;

	GranularContactMemo(Runnable spend) { this.spend = spend; }

	GranularContactCompression.Result solve(double[] mass, double[][] input, GranularContactCompression.Row[] rows,
	                                       double[] initial, GranularContactChainSeed chain) {
		long size = 4 + 10L * mass.length + 7L * rows.length + (initial == null ? 0 : initial.length);
		if (size > MAX_WORDS || mass.length != input.length || initial != null && initial.length != rows.length)
			return GranularContactCompression.solve(mass, input, rows, spend, initial, chain);
		var key = new Key(4 + 4 * mass.length + 5 * rows.length + (initial == null ? 0 : initial.length));
		key.add(mass.length); key.add(input.length); key.add(rows.length); key.add(initial == null ? -1 : initial.length);
		for (int i = 0; i < mass.length; i++) {
			spend.run(); if (input[i].length != 3) return GranularContactCompression.solve(mass, input, rows, spend, initial, chain);
			key.add(Double.doubleToRawLongBits(mass[i]));
			for (double value : input[i]) key.add(Double.doubleToRawLongBits(value));
		}
		for (var row : rows) {
			spend.run(); if (row.normal().length != 3) return GranularContactCompression.solve(mass, input, rows, spend, initial, chain);
			key.add(row.first()); key.add(row.second());
			for (double value : row.normal()) key.add(Double.doubleToRawLongBits(value));
		}
		if (initial != null) for (double value : initial) { spend.run(); key.add(Double.doubleToRawLongBits(value)); }
		spend.run(); var cached = entries.get(key);
		if (cached != null) return copy(cached);
		var result = GranularContactCompression.solve(mass, input, rows, spend, initial, chain);
		while (words + size > MAX_WORDS) {
			spend.run(); var iterator = entries.entrySet().iterator(); var oldest = iterator.next();
			words -= footprint(oldest.getKey()); iterator.remove();
		}
		spend.run(); entries.put(key, copy(result)); words += size;
		return result;
	}

	private static long footprint(Key key) { return key.bits.length + 6L * key.bits[0] + 2L * key.bits[2]; }

	private GranularContactCompression.Result copy(GranularContactCompression.Result source) {
		double[][] values = new double[source.values().length][], error = new double[values.length][];
		for (int i = 0; i < values.length; i++) {
			spend.run(); values[i] = source.values()[i].clone(); error[i] = source.arithmeticError()[i].clone();
		}
		double[] impulses = new double[source.impulses().length], gaps = new double[impulses.length];
		for (int i = 0; i < impulses.length; i++) { spend.run(); impulses[i] = source.impulses()[i]; gaps[i] = source.gapError()[i]; }
		return new GranularContactCompression.Result(values, impulses, gaps, error);
	}

	private final class Key {
		private final long[] bits;
		private int cursor, hash = 1;
		Key(int size) { bits = new long[size]; }
		void add(long value) { bits[cursor++] = value; hash = 31 * hash + Long.hashCode(value); }
		@Override public int hashCode() { return hash; }
		@Override public boolean equals(Object object) {
			if (this == object) return true;
			if (!(object instanceof GranularContactMemo.Key other)) return false;
			spend.run(); if (!Arrays.equals(bits, 0, 4, other.bits, 0, 4)) return false;
			int bodies = (int) bits[0], rows = (int) bits[2], seeded = bits[3] < 0 ? 0 : rows, offset = 4;
			for (int i = 0; i < bodies + rows + seeded; i++) {
				spend.run(); int width = i < bodies ? 4 : i < bodies + rows ? 5 : 1;
				if (!Arrays.equals(bits, offset, offset + width, other.bits, offset, offset + width)) return false;
				offset += width;
			}
			return true;
		}
	}
}
