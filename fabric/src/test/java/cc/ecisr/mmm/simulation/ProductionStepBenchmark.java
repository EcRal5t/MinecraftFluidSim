package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.fluid.CpuFluidSolverBackend;
import cc.ecisr.mmm.fluid.FluidDomain;
import cc.ecisr.mmm.fluid.FluidStepRequest;
import cc.ecisr.mmm.thermal.ThermalLimits;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.registries.Registries;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Short offline production-path measurement; diagnostic only, not a hardware-dependent CI threshold. */
public final class ProductionStepBenchmark {
	private ProductionStepBenchmark() { }

	public static void main(String[] args) throws Exception {
		var registries = ProductionReplayValidationSupport.loadProductionRegistries();
		run("controller", ProductionReplayValidationSupport.createFullyBoundPlanarHalfFill(registries));
		run("demo", ProductionReplayValidationSupport.createFullyBoundDamBreakDemo(registries));
	}

	private static void run(String name, FluidDomain initial) {
		var region = new FluidRegion(UUID.randomUUID(), ResourceKey.create(Registries.DIMENSION,
				Identifier.fromNamespaceAndPath("mmm", "benchmark")), BlockPos.ZERO,
				initial, initial.maximumParticleCount());
		var backend = new CpuFluidSolverBackend();
		int warmup = 20, samples = 20;
		double[][] times = new double[3][samples];
		var threadBean = ManagementFactory.getThreadMXBean();
		var allocations = threadBean instanceof com.sun.management.ThreadMXBean extended
				&& extended.isThreadAllocatedMemorySupported() ? extended : null;
		if (allocations != null) allocations.setThreadAllocatedMemoryEnabled(true);
		long allocated = 0;
		long warmupStart = System.nanoTime();
		for (int step = 0; step < warmup + samples; step++) {
			long bytes = allocations == null ? 0 : allocations.getThreadAllocatedBytes(Thread.currentThread().threadId());
			long start = System.nanoTime();
			var state = region.domain().captureAuthoritativeState();
			long captured = System.nanoTime();
			var candidate = backend.evaluate(new FluidStepRequest(region.solverGeneration(), state,
					List.of(), true, ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP));
			long evaluated = System.nanoTime();
			region.adoptStepCandidate(candidate, backend.id());
			long adopted = System.nanoTime();
			if (step + 1 == warmup) System.out.printf("%s warmup=%.1f ms%n", name,
					(adopted - warmupStart) / 1e6);
			if (step >= warmup) {
				int index = step - warmup;
				times[0][index] = (captured - start) / 1e6;
				times[1][index] = (evaluated - captured) / 1e6;
				times[2][index] = (adopted - evaluated) / 1e6;
				if (allocations != null) allocated += allocations.getThreadAllocatedBytes(
						Thread.currentThread().threadId()) - bytes;
			}
			if (!region.domain().gasBoundary().auditClosed()
					|| !region.domain().exactInternalEnergy().equals(region.domain().thermalAudit().expectedInventory())) {
				throw new AssertionError("Benchmark step did not close inventory audit");
			}
		}
		String[] stages = {"capture", "evaluate", "adopt"};
		for (int stage = 0; stage < times.length; stage++) {
			Arrays.sort(times[stage]);
			System.out.printf("%s %s mean=%.3f p50=%.3f p95=%.3f ms%n", name, stages[stage],
					Arrays.stream(times[stage]).average().orElseThrow(), times[stage][samples / 2],
					times[stage][samples - 2]);
		}
		System.out.printf("%s allocated=%s MiB/step markers=%d%n", name,
				allocations == null ? "unavailable" : String.format(java.util.Locale.ROOT, "%.2f",
						allocated / (double) samples / 1048576.0), region.domain().particleCount());
	}
}
