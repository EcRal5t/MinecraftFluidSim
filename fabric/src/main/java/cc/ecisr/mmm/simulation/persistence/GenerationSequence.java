package cc.ecisr.mmm.simulation.persistence;

import java.io.IOException;

/** Overflow-safe adjacency checks for durable generation sequences. */
public final class GenerationSequence {
	private GenerationSequence() { }

	public static long next(long current, String label) throws IOException {
		if (current < 0L || current == Long.MAX_VALUE) {
			throw new IOException(label + " generation cannot advance from " + current);
		}
		return Math.addExact(current, 1L);
	}

	public static boolean isNext(long base, long target) {
		return base >= 0L && base != Long.MAX_VALUE && target == base + 1L;
	}

	public static void requireNext(long base, long target, String label) throws IOException {
		if (!isNext(base, target)) {
			throw new IOException(label + " generations are not adjacent: base " + base + ", target " + target);
		}
	}
}
