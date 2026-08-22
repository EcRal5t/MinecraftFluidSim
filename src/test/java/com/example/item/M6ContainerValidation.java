package com.example.item;

import com.example.fluid.FlipFluidDomain;
import com.example.fluid.FlipParticleState;
import com.example.fluid.FluidSolverConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Standalone M6 container numeric-representation gate. Answers the pre-implementation questions:
 * long-quanta container arithmetic is exact, conversions from real solver marker volumes round
 * within one half quantum, and simulated container↔region round-trips stay inside a documented
 * drift bound without ever producing negative volumes or non-finite values.
 */
public final class M6ContainerValidation {
	private static final FluidSolverConfig CONFIG = new FluidSolverConfig(
			0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F, 48, 1.0E-4F, 0.40F, 2.0E-3F, 0.80F);
	private static final long BUCKET = ContainerFluidLedger.NANOS_PER_BLOCK;
	private static int passed;

	private M6ContainerValidation() { }

	public static void main(String[] args) throws Exception {
		run("ledger arithmetic is exact and overflow-checked", M6ContainerValidation::testArithmetic);
		run("conversions round real marker volumes within half a quantum", M6ContainerValidation::testConversions);
		run("bucket canonicalization boundaries", M6ContainerValidation::testCanonicalization);
		run("round-trip drift stays bounded over ten thousand cycles", M6ContainerValidation::testRoundTrip);
		System.out.println("M6ContainerValidation passed " + passed + " scenarios");
	}

	private static void testArithmetic() {
		check(ContainerFluidLedger.volumeToNanos(0.0) == 0L, "empty volume converted");
		check(ContainerFluidLedger.volumeToNanos(1.0) == BUCKET, "one block volume is one bucket");
		check(ContainerFluidLedger.volumeToNanos(0.5) == BUCKET / 2L, "half bucket converted");
		check(ContainerFluidLedger.nanosToVolume(BUCKET) == 1.0, "bucket converted back");
		check(ContainerFluidLedger.nanosToFloatVolume(BUCKET / 4L) == 0.25F, "quarter converts to an exact float");

		check(ContainerFluidLedger.transferable(0L, BUCKET, BUCKET) == BUCKET, "empty bucket accepts a full bucket");
		check(ContainerFluidLedger.transferable(BUCKET / 2L, BUCKET, BUCKET) == BUCKET / 2L, "atomic fill leaves the remainder");
		check(ContainerFluidLedger.transferable(BUCKET, BUCKET, BUCKET) == 0L, "full bucket accepts nothing");
		check(ContainerFluidLedger.transferable(3L, 10L, 8L) == 5L, "partial source is fully consumed by capacity space");

		check(ContainerFluidLedger.checkedAdd(BUCKET, BUCKET) == 2L * BUCKET, "checked add");
		try {
			ContainerFluidLedger.checkedAdd(Long.MAX_VALUE, 1L);
			throw new AssertionError("Expected overflow rejection");
		} catch (ArithmeticException expected) {
			// expected
		}
		check(ContainerFluidLedger.checkedSubtract(0L, 1L) == -1L,
				"checked subtract only guards overflow; non-negativity is validated by the container");
		try {
			ContainerFluidLedger.checkedSubtract(Long.MIN_VALUE, 1L);
			throw new AssertionError("Expected underflow rejection");
		} catch (ArithmeticException expected) {
			// expected
		}
		for (Runnable invalid : new Runnable[] {
				() -> ContainerFluidLedger.volumeToNanos(-0.1),
				() -> ContainerFluidLedger.volumeToNanos(Double.NaN),
				() -> ContainerFluidLedger.volumeToNanos(Double.POSITIVE_INFINITY),
				() -> ContainerFluidLedger.volumeToNanos(1.0E19),
				() -> ContainerFluidLedger.nanosToVolume(-1L),
				() -> ContainerFluidLedger.transferable(-1L, 0L, BUCKET),
				() -> ContainerFluidLedger.transferable(0L, -1L, BUCKET),
				() -> ContainerFluidLedger.transferable(0L, 0L, 0L),
				() -> ContainerFluidLedger.transferable(BUCKET + 1L, 0L, BUCKET)
		}) {
			try {
				invalid.run();
				throw new AssertionError("Expected IllegalArgumentException");
			} catch (IllegalArgumentException expected) {
				// expected
			}
		}
	}

	private static void testConversions() {
		FlipFluidDomain domain = new FlipFluidDomain(6, 4, 6, CONFIG, 512);
		domain.setVolume(1, 1, 1, 0.25F);
		domain.setVolume(2, 1, 2, 1.0F);
		domain.setVolume(4, 2, 4, 0.625F);
		domain.setVolume(3, 2, 1, 1.0F / 3.0F);
		domain.setVolume(5, 1, 3, 0.1F);
		FlipParticleState state = domain.exportParticleState();
		check(state.particleCount() > 0, "the solver produced no markers to convert");
		float[] markers = state.copyPositionVolume();
		double exactSum = 0.0;
		long summedQuanta = 0L;
		for (int i = 0; i < state.particleCount(); i++) {
			float volume = markers[i * 4 + 3];
			check(volume > 0.0F && Float.isFinite(volume), "marker volume is not positive and finite");
			long quanta = ContainerFluidLedger.volumeToNanos(volume);
			check(Math.abs(quanta - volume * 1_000_000_000.0) <= 0.5, "conversion exceeded half a quantum");
			float roundTrip = ContainerFluidLedger.nanosToFloatVolume(quanta);
			check(Math.abs(roundTrip - volume) <= Math.ulp(volume) * 2.0F, "float round-trip exceeded float precision");
			exactSum += volume;
			summedQuanta += quanta;
		}
		// Converting the exact sum vs summing conversions may differ by at most half a quantum per marker.
		long sumQuanta = ContainerFluidLedger.volumeToNanos(exactSum);
		check(Math.abs(sumQuanta - summedQuanta) <= state.particleCount() / 2L + 1L,
				"quanta aggregation drifted beyond the documented bound");
	}

	private static void testCanonicalization() {
		check(ContainerFluidLedger.isFullBucket(BUCKET), "one bucket exactly is a full bucket");
		check(!ContainerFluidLedger.isFullBucket(BUCKET - 1L) && !ContainerFluidLedger.isFullBucket(BUCKET + 1L),
				"only one bucket exactly is full");
		check(ContainerFluidLedger.isPartialBucket(1L), "one quantum is a partial bucket");
		check(ContainerFluidLedger.isPartialBucket(BUCKET - 1L), "one quantum short is a partial bucket");
		check(!ContainerFluidLedger.isPartialBucket(0L) && !ContainerFluidLedger.isPartialBucket(BUCKET),
				"empty and full are not partial");
	}

	/**
	 * Simulates the planned transfer primitive: greedy full-marker removal with a float remainder
	 * split, quanta conversion at the container, and float re-creation on insert. The combined
	 * region+container ledger must stay within a small documented bound over 10,000 cycles.
	 */
	private static void testRoundTrip() {
		Random random = new Random(2_626L);
		List<Float> region = new ArrayList<>(512);
		for (int i = 0; i < 256; i++) region.add(0.015625F);
		double initialVolume = region.stream().mapToDouble(Float::doubleValue).sum();
		long containerNanos = 0L;
		long worstDriftNanos = 0L;
		for (int cycle = 0; cycle < 10_000; cycle++) {
			if (containerNanos == 0L || (random.nextBoolean() && !region.isEmpty())) {
				double availableVolume = region.stream().mapToDouble(Float::doubleValue).sum();
				long request = 1L + random.nextInt((int) Math.max(1L,
						Math.min(BUCKET, Math.round(availableVolume * 1_000_000_000.0))));
				double taken = 0.0;
				while (!region.isEmpty() && Math.round(taken * 1_000_000_000.0) < request) {
					float marker = region.getLast();
					double need = request / 1_000_000_000.0 - taken;
					float remainder = (float) (marker - need);
					if (remainder > 0.0F && remainder < marker) {
						region.set(region.size() - 1, remainder);
						taken += marker - remainder;
					} else {
						region.removeLast();
						taken += marker;
					}
				}
				containerNanos = ContainerFluidLedger.checkedAdd(containerNanos, ContainerFluidLedger.volumeToNanos(taken));
			} else {
				long give = Math.min(containerNanos, BUCKET);
				region.add(ContainerFluidLedger.nanosToFloatVolume(give));
				containerNanos = ContainerFluidLedger.checkedSubtract(containerNanos, give);
			}
			double regionVolume = region.stream().mapToDouble(Float::doubleValue).sum();
			check(regionVolume >= 0.0 && Double.isFinite(regionVolume) && containerNanos >= 0L,
					"ledger produced a negative or non-finite value");
			long drift = Math.abs(Math.round((regionVolume + containerNanos / 1_000_000_000.0 - initialVolume) * 1_000_000_000.0));
			worstDriftNanos = Math.max(worstDriftNanos, drift);
		}
		check(worstDriftNanos <= 2_000L,
				"round-trip drift exceeded the documented bound: " + worstDriftNanos + " quanta");
		System.out.println("  worst combined drift over 10000 cycles: " + worstDriftNanos + " quanta ("
				+ String.format("%.3e", worstDriftNanos / 1.0E9) + " m^3)");
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static void run(String name, Runnable test) {
		test.run();
		passed++;
		System.out.println("PASS " + name);
	}
}
