package mmm.simulation;

import mmm.application.checkpoint.RegionCheckpointSource;
import mmm.fluid.FlipChemicalParticleState;
import mmm.fluid.FlipMaterialParticleState;
import mmm.fluid.FlipParticleState;
import mmm.fluid.FlipThermalParticleState;
import mmm.fluid.PendingPhaseMotionState;
import mmm.granular.GranularStateSnapshot;
import mmm.model.region.RegionGeometry;
import mmm.model.region.RegionIdentity;
import mmm.model.region.RegionRuntimeIntent;
import mmm.simulation.controller.FluidRegionFootprint;
import mmm.thermal.FiniteThermalDeviceInventory;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.util.Objects;

/** Minecraft/runtime adapter for one server-thread checkpoint capture. */
public final class MinecraftRegionCheckpointSource implements RegionCheckpointSource {
	private final FluidRegion region;
	private mmm.fluid.FluidDomainState state;
	private final mmm.fluid.HostFluidState host;

	public MinecraftRegionCheckpointSource(FluidRegion region) {
		this(region, null);
	}

	MinecraftRegionCheckpointSource(FluidRegion region, mmm.fluid.HostFluidState host) {
		this.region = Objects.requireNonNull(region, "region");
		this.host = host;
	}

	@Override
	public RegionIdentity identity(long checkpointGeneration) throws IOException {
		if (!region.hasSelectedVolume() || !region.solverLoaded()) {
			throw new IOException("Only a loaded region can be checkpointed");
		}
		if (checkpointGeneration <= region.checkpointGeneration()) {
			throw new IOException("Checkpoint generation must advance");
		}
		return new RegionIdentity(region.id(), region.dimension().identifier().toString(), checkpointGeneration,
				region.networkGeneration());
	}

	@Override
	public RegionGeometry geometry() {
		return new RegionGeometry(position(region.origin()), region.sizeX(), region.sizeY(), region.sizeZ(),
				region.solverConfig(), footprint(region.footprint()));
	}

	@Override public RegionRuntimeIntent runtimeIntent() { return runtimeIntent(region.persistedRuntime()); }
	@Override public mmm.fluid.FluidDomainState authoritativeState() {
		if (state == null) {
			if (host != null) state = host.captureAuthoritativeState();
			else try (var capture = region.captureSolverState()) { state = capture.exportState(); }
		}
		return state;
	}
	@Override public GranularStateSnapshot granularState() { return authoritativeState().granular(); }
	@Override public FlipThermalParticleState thermalState() { return authoritativeState().thermal(); }
	@Override public PendingPhaseMotionState pendingPhaseMotion() { return authoritativeState().pendingPhaseMotion(); }
	@Override public FiniteThermalDeviceInventory finiteThermalDevices(String fingerprint) {
		return region.finiteThermalDevices(fingerprint);
	}
	@Override public boolean hasMaterialState() { return !authoritativeState().materialPalette().isEmpty(); }
	@Override public boolean hasChemicalState() { return authoritativeState().chemicalRegistry() != null; }
	@Override public boolean hasGranularState() { return authoritativeState().granularRegistry() != null; }
	@Override public boolean hasThermalState() { return authoritativeState().thermalRegistry() != null; }

	public static RegionGeometry.BlockPosition position(BlockPos value) {
		return new RegionGeometry.BlockPosition(value.getX(), value.getY(), value.getZ());
	}

	public static BlockPos blockPosition(RegionGeometry.BlockPosition value) {
		return new BlockPos(value.x(), value.y(), value.z());
	}

	public static RegionGeometry.SelectedVolume footprint(FluidRegionFootprint value) {
		Objects.requireNonNull(value, "value");
		return new RegionGeometry.SelectedVolume(position(value.minimumInterior()), position(value.maximumInterior()), value.interiorBlocks(),
				value.requiredChunks(), value.kind(), value.poolShellBlocks(), value.atmosphereBlocks(), value.returnedBlocks(), value.name());
	}

	public static FluidRegionFootprint minecraftFootprint(RegionGeometry.SelectedVolume value) {
		return new FluidRegionFootprint(blockPosition(value.minimumInterior()),
				blockPosition(value.maximumInterior()), value.interiorBlocks(), value.poolShellBlocks(), value.requiredChunks(), value.kind(), value.atmosphereBlocks(), value.returnedBlocks(), value.name());
	}

	public static RegionRuntimeIntent runtimeIntent(FluidRegion.PersistedRuntime value) {
		return new RegionRuntimeIntent(RegionRuntimeIntent.ResumeState.valueOf(value.resumeState().name()),
				value.operatorPaused(), value.entityPhysicsEnabled(), value.debugOverlays(), value.stepIntervalTicks(), value.tick());
	}

	public static FluidRegion.PersistedRuntime persistedRuntime(RegionRuntimeIntent value) {
		return new FluidRegion.PersistedRuntime(FluidRegionLifecycle.State.valueOf(value.resumeState().name()),
				value.operatorPaused(), value.entityPhysicsEnabled(), value.debugOverlays(), value.stepIntervalTicks(), value.tick());
	}
}
