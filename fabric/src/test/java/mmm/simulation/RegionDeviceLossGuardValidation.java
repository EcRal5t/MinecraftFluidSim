package mmm.simulation;

import java.util.List;
import java.util.UUID;
import mmm.api.RegionAvailabilityReason;
import mmm.fluid.FluidDomainState;
import mmm.fluid.FluidSolverBackend;
import mmm.fluid.FluidSolverConfig;
import mmm.fluid.FluidStateAccess;
import mmm.fluid.FluidStepCandidate;
import mmm.fluid.FluidStepRequest;
import mmm.fluid.FluidThermalStepCandidate;
import mmm.fluid.FluidThermalStepRequest;
import mmm.fluid.HostFluidState;
import mmm.fluid.PreparedFluidState;
import mmm.thermal.FiniteThermalDeviceInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Exercises failures from different parts of the per-region tick guard. */
final class RegionDeviceLossGuardValidation {
	private static final ResourceKey<Registry<Level>> DIMENSIONS = ResourceKey.createRegistryKey(
			Identifier.fromNamespaceAndPath("mmm", "device_guard_dimensions"));
	private static final ResourceKey<Level> DIMENSION = ResourceKey.create(
			DIMENSIONS, Identifier.fromNamespaceAndPath("mmm", "device_guard_dimension"));

	static void validate() {
		var stages = List.of("availability", "activity connections", "collision refresh", "step observation", "snapshot");
		for (int failedStage = 0; failedStage < stages.size(); failedStage++) {
			String stage = stages.get(failedStage);
			int target = failedStage;
			var backend = new LossBackend();
			var host = new HostFluidState(2, 2, 2, FluidSolverConfig.prototypeDefaults(), mmm.fluid.TestFluidDefinitions.load());
			host.setVolume(0, 0, 0, .5F);
			var region = new FluidRegion(UUID.randomUUID(), DIMENSION, BlockPos.ZERO, host);
			region.setPaused(false);
			region.solverBackend(backend);
			region.ensureDeviceResident();
			boolean[] handled = {false};
			int[] visited = {0};
			MinecraftSimulationLoop.runWithDeviceLossGuard(region, () -> {
				for (int i = 0; i < stages.size(); i++) {
					visited[0]++;
					if (i == target) {
						backend.failRead = true;
						region.hostSnapshot();
					}
				}
			}, failure -> handled[0] = failure instanceof FluidSolverBackend.DeviceLostException);
			require(visited[0] == target + 1 && handled[0] && !region.solverLoaded() && !region.canStep() && !region.paused()
					&& region.availabilityReason() == RegionAvailabilityReason.NUMERICAL_PAUSE,
					stage + " device loss did not pause the region and preserve its running intent");
			boolean[] ran = {false};
			MinecraftSimulationLoop.runWithDeviceLossGuard(region, () -> ran[0] = true, failure -> {
				throw new AssertionError("Unavailable device reported twice");
			});
			require(!ran[0], stage + " continued while the device was unavailable");
		}
		var sharedBackend = new LossBackend();
		var first = new FluidRegion(UUID.randomUUID(), DIMENSION, BlockPos.ZERO,
				new HostFluidState(2, 2, 2, FluidSolverConfig.prototypeDefaults(), mmm.fluid.TestFluidDefinitions.load()));
		var second = new FluidRegion(UUID.randomUUID(), DIMENSION, BlockPos.ZERO,
				new HostFluidState(2, 2, 2, FluidSolverConfig.prototypeDefaults(), mmm.fluid.TestFluidDefinitions.load()));
		first.solverBackend(sharedBackend);
		second.solverBackend(sharedBackend);
		first.ensureDeviceResident();
		second.ensureDeviceResident();
		sharedBackend.failRead = true;
		sharedBackend.failClose = true;
		MinecraftSimulationLoop.runWithDeviceLossGuard(first, first::hostSnapshot, failure -> { });
		int[] notifications = {0};
		Runnable unexpectedTick = () -> { throw new AssertionError("Tick ran on an unavailable device"); };
		java.util.function.Consumer<RuntimeException> notify = failure -> notifications[0]++;
		MinecraftSimulationLoop.runWithDeviceLossGuard(second, unexpectedTick, notify);
		MinecraftSimulationLoop.runWithDeviceLossGuard(second, unexpectedTick, notify);
		require(notifications[0] == 1 && !second.solverLoaded(),
				"Shared device loss did not remove the second region exactly once");

		var region = new FluidRegion(UUID.randomUUID(), DIMENSION, BlockPos.ZERO,
				new HostFluidState(2, 2, 2, FluidSolverConfig.prototypeDefaults(), mmm.fluid.TestFluidDefinitions.load()));
		var unrelated = new IllegalStateException("unrelated failure");
		try {
			MinecraftSimulationLoop.runWithDeviceLossGuard(region, () -> { throw unrelated; }, failure -> {
				throw new AssertionError("Unrelated exception was classified as device loss");
			});
			throw new AssertionError("Unrelated exception was swallowed");
		} catch (IllegalStateException actual) {
			require(actual == unrelated, "Guard replaced an unrelated exception");
		}
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static final class LossBackend implements FluidSolverBackend {
		boolean failRead;
		boolean failClose;
		String unavailable;
		@Override public String id() { return "test:guard"; }
		@Override public String unavailableReason() { return unavailable; }
		@Override public PreparedFluidState prepareState(FluidStateAccess source, FluidDomainState successor,
				FiniteThermalDeviceInventory devices, List<FluidStateAccess.DevicePort> ports) {
			var state = new FluidStateAccess() {
				@Override public FluidDomainState.Layout layout() { return successor.layout(); }
				@Override public void close() {
					if (failClose) throw new IllegalStateException("test close failure");
				}
				@Override public FluidDomainState exportState() {
					if (failRead) {
						unavailable = "test device lost";
						throw new DeviceLostException(unavailable);
					}
					return successor;
				}
			};
			return new PreparedFluidState() {
				@Override public FluidDomainState.Layout layout() { return state.layout(); }
				@Override public FluidDomainState exportState() { return state.exportState(); }
				@Override public FluidStateAccess commit() { return state; }
				@Override public void close() { }
			};
		}
		@Override public FluidStepCandidate evaluate(FluidStepRequest request) { throw new UnsupportedOperationException(); }
		@Override public FluidThermalStepCandidate evaluateThermal(FluidThermalStepRequest request) { throw new UnsupportedOperationException(); }
	}
}
