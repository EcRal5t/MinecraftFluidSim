package mmm.simulation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import mmm.api.RegionAvailabilityReason;
import mmm.fluid.FluidDomainResize;
import mmm.fluid.HostFluidState;
import mmm.fluid.SpatialGridRemap;
import mmm.units.SignedDyadic;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Prepares and publishes a larger mechanical volume before its next motion step. */
final class RegionExpansion {
    private final FluidRegionManager regions;
    private final FluidRegionOperations operations;
    private final WorldThermalEnvironment environment;
    private final FluidSnapshotPublisher snapshots;
    private final CommittedWorldRegistries world;
    private final java.util.function.BooleanSupplier worldBridgeEnabled;
    private final int maximumThermalSubsteps;

    RegionExpansion(FluidRegionManager regions, FluidRegionOperations operations,
                    WorldThermalEnvironment environment, FluidSnapshotPublisher snapshots, CommittedWorldRegistries world,
                    java.util.function.BooleanSupplier worldBridgeEnabled, int maximumThermalSubsteps) {
        this.regions = regions;
        this.operations = operations;
        this.environment = environment;
        this.snapshots = snapshots;
        this.world = java.util.Objects.requireNonNull(world);
        this.worldBridgeEnabled = java.util.Objects.requireNonNull(worldBridgeEnabled);
        this.maximumThermalSubsteps = maximumThermalSubsteps;
    }

    /** Returns true when this tick must stop because a resize was attempted. */
    boolean handle(ServerLevel level, FluidRegion region) {
        if (!region.hasSelectedVolume()) return false;
        int directions = region.expansionDirections();
        if (directions == 0) return false;
        boolean published = false;
        boolean environmentAdopted = false;
        FluidRegion candidate = null;
        WorldThermalEnvironment.AirHandoff handoff = null;
        mmm.fluid.PreparedEnvironmentState preparedEnvironment = null;
        mmm.fluid.PreparedFluidState preparedFluid = null;
        try {
            var footprint = region.footprint().expanded(directions, regions.limits().maximumCellsPerRegion());
            var added = new HashSet<BlockPos>();
            for (long packed : footprint.interiorBlocks()) {
                if (region.footprint().interiorBlocks().contains(packed)) continue;
                BlockPos position = BlockPos.of(packed);
                if (!level.isInWorldBounds(position) || !level.isInsideBuildHeight(position))
                    throw new IllegalArgumentException("扩容范围超出世界高度或范围：" + position.toShortString());
                if (!level.hasChunkAt(position)) {
                    region.blockExpansion(RegionAvailabilityReason.CHUNK_MISSING, "扩容需要加载区块：" + position.toShortString());
                    return true;
                }
                added.add(position);
            }
            var grid = FluidRegionFactory.grid(footprint);
            var admission = regions.previewResize(region, footprint, grid);
            if (!admission.accepted()) {
                region.blockExpansion(admission.reason(), "扩容受阻：" + admission.failure());
                return true;
            }
            var oldState = region.hostSnapshot().captureAuthoritativeState();
            var empty = new HostFluidState(grid, region.solverConfig(), world.definitions());
            var geometryCandidate = region.resizedCandidate(empty, footprint);
            FluidRegionWorldInteraction.refreshCollisions(level, geometryCandidate, FluidRegionFactory.CELLS_PER_BLOCK);
            if (environment == null) world.bindConfiguredState(geometryCandidate.editableHost(), maximumThermalSubsteps);
            var geometry = geometryCandidate.fixedGeometry();
            int dx = (region.origin().getX() - footprint.minimumInterior().getX()) * FluidRegionFactory.CELLS_PER_BLOCK;
            int dy = (region.origin().getY() - footprint.minimumInterior().getY()) * FluidRegionFactory.CELLS_PER_BLOCK;
            int dz = (region.origin().getZ() - footprint.minimumInterior().getZ()) * FluidRegionFactory.CELLS_PER_BLOCK;
            boolean[] retainedCells = new boolean[oldState.grid().cellCount()];
            for (int cell = 0; cell < retainedCells.length; cell++) {
                BlockPos position = region.origin().offset(oldState.grid().x(cell) / FluidRegionFactory.CELLS_PER_BLOCK,
                        oldState.grid().y(cell) / FluidRegionFactory.CELLS_PER_BLOCK,
                        oldState.grid().z(cell) / FluidRegionFactory.CELLS_PER_BLOCK);
                retainedCells[cell] = region.footprint().interiorBlocks().contains(position.asLong());
            }
            var map = new SpatialGridRemap(oldState.grid(), grid, dx, dy, dz, retainedCells);
            mmm.fluid.air.SpatialGasState gas = null;
            mmm.thermal.ThermalAuditLedger audit = oldState.thermalOwner() == null ? null : oldState.thermalOwner().audit();
            if (environment != null) {
                handoff = environment.prepareRange(level, geometryCandidate, added);
                var air = handoff.split(footprint.minimumInterior(), grid);
                if (oldState.spatialGas() == null) throw new IllegalStateException("区域逐格气体缺失");
                gas = map.gas(oldState.spatialGas(), air.cells(), air.energy());
                audit = audit.withExternalSourceEnergy(SignedDyadic.sum(air.energy()).subtract(air.roundoff()))
                        .withRoundoff(air.roundoff());
            } else if (oldState.spatialGas() != null) {
                var ambient = geometryCandidate.editableHost().captureAuthoritativeState().spatialGas();
                if (ambient == null) throw new IllegalStateException("新范围的初始气体缺失");
                var cells = new ArrayList<>(ambient.cells());
                double[] energy = ambient.internalEnergy();
                for (int cell = 0; cell < oldState.grid().cellCount(); cell++) {
                    if (!retainedCells[cell]) continue;
                    int mapped = map.cell(cell);
                    cells.set(mapped, mmm.fluid.air.GasInventory.EMPTY);
                    energy[mapped] = 0;
                }
                gas = map.gas(oldState.spatialGas(), cells, energy);
            }
            double[] boundary = oldState.copyThermalBoundaryTemperaturesK() == null ? null : new double[grid.cellCount() * 6];
            if (boundary != null) java.util.Arrays.fill(boundary, Double.NaN);
            var state = FluidDomainResize.prepare(oldState, grid, dx, dy, dz, retainedCells,
                    geometry.copyMasks(), geometry.screens(), gas, audit, boundary);
            var host = HostFluidState.restoreAuthoritativeState(state);
            PipeCollisionBodies.install(host,geometryCandidate.entityBodyState());
            var packedAdded = new HashSet<Long>();
            for (var position : added) packedAdded.add(position.asLong());
            FluidRegionFactory.captureAddedWater(level, host, footprint.minimumInterior(), packedAdded);
            candidate = region.resizedCandidate(host, footprint);
            var water = operations.scanOwnershipPreimage(level, footprint).stream()
                    .filter(entry -> packedAdded.contains(entry.packedPosition())).toList();
            if (!water.isEmpty() && !worldBridgeEnabled.getAsBoolean())
                throw new IllegalStateException("扩容范围包含原版水，世界物质交接当前关闭");
            if (handoff != null) {
                preparedEnvironment = handoff.prepare();
                candidate.environmentCandidate(preparedEnvironment.capture());
                candidate.environmentCandidateExternal(true);
            }
            try (var source = region.captureSolverState()) {
                preparedFluid = regions.solverBackend().prepareState(source, host.captureAuthoritativeState(),
                        source.finiteThermalDevices(), List.of(), null);
            }
            if (preparedEnvironment != null) preparedFluid.attachEnvironment(preparedEnvironment);
            operations.acquireOwnership(level, candidate, water);
            published = true;
            candidate.adoptResizedDevice(preparedFluid.commit());
            if (handoff != null) { handoff.adopted(); environmentAdopted = true; candidate.clearEnvironmentCandidate(); }
            candidate.requireCollisionRefresh();
            var result = regions.adoptResize(region, candidate);
            if (!result.accepted()) throw new IllegalStateException("扩容已保存但区域登记失败：" + result.failure());
            snapshots.sendToLevel(level, candidate);
        } catch (Exception failure) {
            long targetGeneration = Math.addExact(region.checkpointGeneration(), 1L);
            boolean durable = candidate != null && operations.worldCheckpointContains(candidate, targetGeneration);
            if (!durable && candidate != null && environment == null) {
                try { durable = operations.committedGeneration(candidate.id()) == targetGeneration; }
                catch (java.io.IOException inspection) { failure.addSuppressed(inspection); }
            }
            if (!published && candidate != null && durable) {
                published = true;
                if (candidate.checkpointGeneration() < targetGeneration) candidate.recordPublishedCheckpoint(targetGeneration);
            }
            if (published && candidate != null) {
                try {
					if (preparedEnvironment != null && preparedEnvironment.isCurrent()) {
						preparedEnvironment.commit();
					}
                    if (handoff != null && !environmentAdopted) {
                        var expected = candidate.environmentCandidate();
                        var actual = environment.capture().get(expected.dimensionId());
                        if (actual == null || actual.generation() != expected.generation())
                            throw new IllegalStateException("共享环境需要从已保存的世界代恢复");
                        handoff.adopted(); environmentAdopted = true; candidate.clearEnvironmentCandidate();
                    }
                } catch (Exception recovery) { failure.addSuppressed(recovery); }
                try {
                    if (regions.region(candidate.id()) != candidate) {
                        var result = regions.adoptResize(region, candidate);
                        if (!result.accepted()) throw new IllegalStateException(result.failure());
                    }
                    candidate.blockNumerical("扩容恢复需要处理：" + failure.getMessage());
                } catch (Exception recovery) {
                    region.blockNumerical("已保存的扩容代需要恢复：" + failure.getMessage());
                    failure.addSuppressed(recovery);
                }
            } else region.blockExpansion(failure instanceof mmm.simulation.controller.FluidRegionFootprint.ExpansionBudgetExceededException
                    ? RegionAvailabilityReason.BUDGET : RegionAvailabilityReason.NUMERICAL_PAUSE, "扩容失败：" + failure.getMessage());
            mmm.MmmMod.LOGGER.error("Region expansion failed for {}", region.id(), failure);
        } finally {
            if (preparedFluid != null) preparedFluid.close();
            if (preparedEnvironment != null) preparedEnvironment.close();
            if (handoff != null && !environmentAdopted) handoff.discard();
        }
        return true;
    }
}
