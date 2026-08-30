package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.application.checkpoint.RegionCheckpointSource;
import cc.ecisr.mmm.fluid.FlipChemicalParticleState;
import cc.ecisr.mmm.fluid.FlipMaterialParticleState;
import cc.ecisr.mmm.fluid.FlipParticleState;
import cc.ecisr.mmm.fluid.FlipThermalParticleState;
import cc.ecisr.mmm.fluid.PendingPhaseVolumeState;
import cc.ecisr.mmm.fluid.air.GasStateSnapshot;
import cc.ecisr.mmm.granular.GranularStateSnapshot;
import cc.ecisr.mmm.model.region.RegionGeometry;
import cc.ecisr.mmm.model.region.RegionIdentity;
import cc.ecisr.mmm.model.region.RegionRuntimeIntent;
import cc.ecisr.mmm.simulation.controller.FluidRegionFootprint;
import cc.ecisr.mmm.thermal.FiniteThermalDeviceInventory;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.util.Objects;

/** Minecraft/runtime adapter for one server-thread checkpoint capture. */
public final class MinecraftRegionCheckpointSource implements RegionCheckpointSource {
	private final FluidRegion region;

	public MinecraftRegionCheckpointSource(FluidRegion region) {
		this.region = Objects.requireNonNull(region, "region");
	}

	@Override
	public RegionIdentity identity(long checkpointGeneration) throws IOException {
		if (!region.controllerManaged() || !region.solverLoaded()) {
			throw new IOException("Only a loaded controller region can be checkpointed");
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
				region.solverConfig(), region.reservedMarkerCapacity(), footprint(region.footprint()));
	}

	@Override public RegionRuntimeIntent runtimeIntent() { return runtimeIntent(region.persistedRuntime()); }
	@Override public FlipParticleState pureParticles() { return region.domain().exportParticleState(); }
	@Override public FlipMaterialParticleState materialParticles() { return region.domain().exportMaterialParticleState(); }
	@Override public FlipChemicalParticleState chemicalParticles() { return region.domain().exportChemicalParticleState(); }
	@Override public GranularStateSnapshot granularState() { return region.domain().exportGranularState(); }
	@Override public GasStateSnapshot gasState(String fingerprint) { return region.domain().exportGasState(fingerprint); }
	@Override public FlipThermalParticleState thermalState() { return region.domain().exportThermalParticleState(); }
	@Override public PendingPhaseVolumeState pendingPhaseVolume() { return region.domain().exportPendingPhaseVolumeState(); }
	@Override public FiniteThermalDeviceInventory finiteThermalDevices(String fingerprint) {
		return region.finiteThermalDevices(fingerprint);
	}
	@Override public boolean hasMaterialState() { return region.domain().hasMaterialState(); }
	@Override public boolean hasChemicalState() { return region.domain().hasChemistryState(); }
	@Override public boolean hasGranularState() { return region.domain().hasGranularState(); }
	@Override public boolean hasThermalState() { return region.domain().hasThermalState(); }

	public static RegionGeometry.BlockPosition position(BlockPos value) {
		return new RegionGeometry.BlockPosition(value.getX(), value.getY(), value.getZ());
	}

	public static BlockPos blockPosition(RegionGeometry.BlockPosition value) {
		return new BlockPos(value.x(), value.y(), value.z());
	}

	public static RegionGeometry.ControllerFootprint footprint(FluidRegionFootprint value) {
		Objects.requireNonNull(value, "value");
		return new RegionGeometry.ControllerFootprint(position(value.controllerPosition()),
				position(value.minimumInterior()), position(value.maximumInterior()), value.interiorBlocks(),
				value.boundaryBlocks(), value.requiredChunks());
	}

	public static FluidRegionFootprint minecraftFootprint(RegionGeometry.ControllerFootprint value) {
		return new FluidRegionFootprint(blockPosition(value.controllerPosition()), blockPosition(value.minimumInterior()),
				blockPosition(value.maximumInterior()), value.interiorBlocks(), value.boundaryBlocks(), value.requiredChunks());
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
