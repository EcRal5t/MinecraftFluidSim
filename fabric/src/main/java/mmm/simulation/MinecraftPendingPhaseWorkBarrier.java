package mmm.simulation;

import net.minecraft.server.level.ServerLevel;

import java.util.Objects;

/** Orders synchronous host transfers after the pressure projection that consumes prior phase work. */
final class MinecraftPendingPhaseWorkBarrier {
	private final MinecraftMotionExecutor motion;
	private final MinecraftRegionTick regionTick;

	MinecraftPendingPhaseWorkBarrier(MinecraftMotionExecutor motion, MinecraftRegionTick regionTick) {
		this.motion = Objects.requireNonNull(motion, "Motion executor");
		this.regionTick = Objects.requireNonNull(regionTick, "Region tick");
	}

	boolean settle(ServerLevel level, FluidRegion region) {
		if (!region.domain().hasPendingPhaseVolumeWork()) return true;
		try {
			motion.settlePendingPhaseWork(region);
			return true;
		} catch (RuntimeException failure) {
			if (!regionTick.blockStepFailure(level, region, failure)) throw failure;
			return false;
		}
	}
}
