package mmm.simulation;

import mmm.fluid.CpuFluidSolverBackend;
import mmm.fluid.FluidDomain;
import mmm.fluid.FluidStepRequest;
import mmm.thermal.ThermalLimits;
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
		if (args.length == 1 && args[0].equals("--entities-hot")) {
			ProductionFluidReplayValidation.main(new String[] {"--controller-gas"});
			main(new String[] {"--entities"});
			return;
		}
		var registries = ProductionReplayValidationSupport.loadProductionRegistries();
		if (args.length == 1 && args[0].equals("--demo-entity")) {
			run("demo-entity", ProductionReplayValidationSupport.createFullyBoundDamBreakDemo(registries), true);
			return;
		}
		if (args.length == 1 && args[0].equals("--entities")) {
			run("controller-entity", ProductionReplayValidationSupport.createFullyBoundPlanarHalfFill(registries), true);
			run("demo-entity", ProductionReplayValidationSupport.createFullyBoundDamBreakDemo(registries), true);
			return;
		}
		if (args.length != 0) throw new IllegalArgumentException("Expected no arguments, --entities, --entities-hot or --demo-entity");
		run("controller", ProductionReplayValidationSupport.createFullyBoundPlanarHalfFill(registries));
		run("demo", ProductionReplayValidationSupport.createFullyBoundDamBreakDemo(registries));
	}

	private static void run(String name, FluidDomain initial) {
		run(name, initial, false);
	}
	private static void run(String name, FluidDomain initial, boolean entities) {
		var region = new FluidRegion(UUID.randomUUID(), ResourceKey.create(Registries.DIMENSION,
				Identifier.fromNamespaceAndPath("mmm", "benchmark")), BlockPos.ZERO,
				initial);
		var backend = new CpuFluidSolverBackend();
		int warmup = 20, samples = 20;
		double[][] times = new double[4][samples];
		var threadBean = ManagementFactory.getThreadMXBean();
		var allocations = threadBean instanceof com.sun.management.ThreadMXBean extended
				&& extended.isThreadAllocatedMemorySupported() ? extended : null;
		if (allocations != null) allocations.setThreadAllocatedMemoryEnabled(true);
		long allocated = 0;
		int overBudget = 0;
		UUID bodyId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
		long warmupStart = System.nanoTime();
		for (int step = 0; step < warmup + samples; step++) {
			long bytes = allocations == null ? 0 : allocations.getThreadAllocatedBytes(Thread.currentThread().threadId());
			long start = System.nanoTime();
			long cpuStart = threadBean.isCurrentThreadCpuTimeSupported() ? threadBean.getCurrentThreadCpuTime() : 0;
			var state = region.domain().captureAuthoritativeState();
			long captured = System.nanoTime();
			List<mmm.world.MovingBodyFluidCoupling.Request> bodies = List.of();
			if (entities) {
				double phase = step * region.domain().config().timeStep();
				double centerX = 1.75 + .02 * Math.sin(phase);
				var bounds = new mmm.world.MovingBodyFluidCoupling.Bounds(centerX-.15,1.3,.85,centerX+.15,1.6,1.15);
				bodies = List.of(new mmm.world.MovingBodyFluidCoupling.Request(bodyId, bounds, .02*Math.cos(phase), 0, 0));
			}
			var candidate = backend.evaluate(new FluidStepRequest(region.solverGeneration(), state,
					bodies, true, ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP));
			long evaluated = System.nanoTime();
			region.adoptStepCandidate(candidate, backend.id());
			long adopted = System.nanoTime();
			if (step + 1 == warmup) System.out.printf("%s warmup=%.1f ms%n", name,
					(adopted - warmupStart) / 1e6);
			if (step >= warmup) {
				if (entities) {
					double elapsed = (adopted-start)/1e6;
					double budget = initial.sizeX() == 8 ? 40 : 200;
					overBudget = elapsed > budget ? overBudget + 1 : 0;
					if (overBudget >= (initial.sizeX() == 8 ? 1 : 3))
						throw new AssertionError(name+" entity full-step budget exceeded: "+elapsed+" ms, caller CPU="
								+(threadBean.isCurrentThreadCpuTimeSupported() ? (threadBean.getCurrentThreadCpuTime()-cpuStart)/1e6 : -1)
								+" ms, sample="+(step-warmup));
					if (candidate.movingBodyCoupling().bodyImpulses().stream().noneMatch(body -> body.bodyId().equals(bodyId)))
						throw new AssertionError("Entity budget omitted the actual pressure/shear response");
				}
				int index = step - warmup;
				times[0][index] = (captured - start) / 1e6;
				times[1][index] = (evaluated - captured) / 1e6;
				times[2][index] = (adopted - evaluated) / 1e6;
				times[3][index] = (adopted - start) / 1e6;
				if (allocations != null) allocated += allocations.getThreadAllocatedBytes(
						Thread.currentThread().threadId()) - bytes;
			}
			if (!region.domain().gasBoundary().auditClosed()
					|| !region.domain().exactInternalEnergy().equals(region.domain().thermalAudit().expectedInventory())) {
				throw new AssertionError("Benchmark step did not close inventory audit");
			}
		}
		String[] stages = {"capture", "evaluate", "adopt", "complete"};
		for (int stage = 0; stage < times.length; stage++) {
			Arrays.sort(times[stage]);
			System.out.printf("%s %s mean=%.3f p50=%.3f p95=%.3f max=%.3f ms%n", name, stages[stage],
					Arrays.stream(times[stage]).average().orElseThrow(), times[stage][samples / 2],
					times[stage][samples - 2], times[stage][samples - 1]);
		}
		System.out.printf("%s callerAllocated=%s MiB/step markers=%d%n", name,
				allocations == null ? "unavailable" : String.format(java.util.Locale.ROOT, "%.2f",
						allocated / (double) samples / 1048576.0), region.domain().particleCount());
	}
}
