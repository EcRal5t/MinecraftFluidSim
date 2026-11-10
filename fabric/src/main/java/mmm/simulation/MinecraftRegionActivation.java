package mmm.simulation;

import mmm.MmmMod;
import mmm.application.ContainerRecoveryCoordinator;
import mmm.fluid.HostFluidState;
import mmm.block.MmmBlocks;
import mmm.simulation.controller.RegionWorldAvailability;
import mmm.simulation.controller.FluidRegionFootprint;
import mmm.simulation.ownership.OwnershipJournal;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Owns controller selection, run-toggle and new-region activation through the first durable HEAD. */
public final class MinecraftRegionActivation {
	private final WorldStartupCoordinator.Result startup;
	private final CommittedWorldRegistries world;
	private final FluidRegionManager regions;
	private final FluidRegionOperations operations;
	private final FluidSnapshotPublisher snapshots;
	private final MinecraftRegionTick regionTick;
	private final MinecraftTakeoverAdapter takeover;
	private final ContainerRecoveryCoordinator containerRecovery;
	private final BooleanSupplier controllerFeatureEnabled;
	private final BooleanSupplier defaultEntityPhysics;
	private final BooleanSupplier defaultDebugOverlays;
	private final Supplier<List<String>> ownershipIssues;
	private final MinecraftRegionAvailability availability;
	private final boolean worldBridgeFeatureEnabled;
	private final int maximumThermalSubstepsPerStep;
	private WorldThermalEnvironment environment;
	void environment(WorldThermalEnvironment value) { environment = value; }

	MinecraftRegionActivation(
			WorldStartupCoordinator.Result startup,
			CommittedWorldRegistries world,
			FluidRegionManager regions,
			FluidRegionOperations operations,
			FluidSnapshotPublisher snapshots,
			MinecraftRegionTick regionTick,
			MinecraftTakeoverAdapter takeover,
			ContainerRecoveryCoordinator containerRecovery,
			BooleanSupplier controllerFeatureEnabled,
			BooleanSupplier defaultEntityPhysics,
			BooleanSupplier defaultDebugOverlays,
			Supplier<List<String>> ownershipIssues,
			MinecraftRegionAvailability availability,
			boolean worldBridgeFeatureEnabled,
			int maximumThermalSubstepsPerStep
	) {
		this.startup = Objects.requireNonNull(startup, "World startup result");
		this.world = Objects.requireNonNull(world, "Committed world registries");
		this.regions = Objects.requireNonNull(regions, "Region manager");
		this.operations = Objects.requireNonNull(operations, "Region operations");
		this.snapshots = Objects.requireNonNull(snapshots, "Snapshot publisher");
		this.regionTick = Objects.requireNonNull(regionTick, "Region tick adapter");
		this.takeover = Objects.requireNonNull(takeover, "Takeover adapter");
		this.containerRecovery = Objects.requireNonNull(containerRecovery, "Container recovery coordinator");
		this.controllerFeatureEnabled = Objects.requireNonNull(controllerFeatureEnabled, "Controller feature gate");
		this.defaultEntityPhysics = Objects.requireNonNull(defaultEntityPhysics, "Default entity physics");
		this.defaultDebugOverlays = Objects.requireNonNull(defaultDebugOverlays, "Default debug overlays");
		this.ownershipIssues = Objects.requireNonNull(ownershipIssues, "Ownership issues");
		this.availability = Objects.requireNonNull(availability, "Region availability");
		this.worldBridgeFeatureEnabled = worldBridgeFeatureEnabled;
		this.maximumThermalSubstepsPerStep = maximumThermalSubstepsPerStep;
	}

	public Result activate(ServerPlayer player, BlockPos controllerPosition, boolean toggleRunning) {
		ServerLevel level = player.level();

		for (FluidRegion existing : regions.regions()) {
			FluidRegionFootprint existingFootprint = existing.footprint();
			if (existingFootprint == null || !existing.dimension().equals(level.dimension())
					|| !existingFootprint.containsInterior(controllerPosition)) continue;
			if (toggleRunning && !startup.authoritativeWritesReady())
				return Result.failed(authoritativeWriteFailure("修改区域运行状态"));
			if (toggleRunning) existing.clearExpansionBlock();
			boolean retryPerformance = toggleRunning && !existing.paused()
					&& existing.availabilityReason() == mmm.api.RegionAvailabilityReason.PERFORMANCE_PAUSE;
			if (toggleRunning && existing.availabilityReason() == mmm.api.RegionAvailabilityReason.PERFORMANCE_PAUSE)
				MinecraftRegionOperatorActions.clearPerformancePauseForExplicitRetry(existing);
			regions.select(existing.id());
			if (!availability.ensure(level, existing)) {
				return Result.failed("区域当前不可用：" + existing.availabilityFailure());
			}
			Action action;
			if (toggleRunning) {
				if (retryPerformance) {
					existing.wakeForActivity();
					action = Action.RESUMED;
				} else if (existing.paused()) {
					MinecraftRegionOperatorActions.clearPerformancePauseForExplicitRetry(existing);
					existing.setPaused(false);
					existing.wakeForActivity();
					action = Action.RESUMED;
				} else {
					existing.setPaused(true);
					action = Action.PAUSED;
				}
			} else {
				action = existing.paused() ? Action.SELECTED_PAUSED : Action.SELECTED_RUNNING;
			}
			snapshots.sendToLevel(level, existing);
			return Result.succeeded(existingFootprint, action);
		}
        return Result.failed("此位置没有区域；手持探针潜行选择两个角点");
    }

    private final java.util.Map<java.util.UUID, Selection> selections = new java.util.HashMap<>();

    public String renameSelected(ServerPlayer player, String name) {
        var region = regions.selected();
        if (region == null || !region.dimension().equals(player.level().dimension()) || !nearRegion(player, region))
            return "请先选择附近的区域";
        String trimmed = name.strip();
        if (trimmed.isEmpty() || trimmed.length() > 64) return "名称须为 1–64 个字符";
        if (!startup.authoritativeWritesReady()) return authoritativeWriteFailure("修改区域名称");
        if (!availability.ensure(player.level(), region)) return region.availabilityFailure();
        String previous = region.footprint().name();
        long generation = region.checkpointGeneration();
        region.rename(trimmed);
        try {
            operations.checkpoint(region);
            snapshots.sendToLevel(player.level(), region);
            return "区域名称已设为 " + trimmed;
        } catch (IOException | RuntimeException failure) {
            if (region.checkpointGeneration() == generation) region.rename(previous);
            return "保存区域名称失败：" + failure.getMessage();
        }
    }

    static boolean nearRegion(ServerPlayer player, FluidRegion region) {
        var volume = region.footprint();
        var min = volume.minimumInterior();
        var max = volume.maximumInterior();
        double x = Math.max(min.getX(), Math.min(max.getX() + 1.0, player.getX()));
        double y = Math.max(min.getY(), Math.min(max.getY() + 1.0, player.getY()));
        double z = Math.max(min.getZ(), Math.min(max.getZ() + 1.0, player.getZ()));
        return player.distanceToSqr(x, y, z) <= 64.0;
    }

    public boolean selecting(ServerPlayer player) { return selections.containsKey(player.getUUID()); }

    public boolean contains(ServerPlayer player, BlockPos position) {
        for (FluidRegion region : regions.regions())
            if (region.dimension().equals(player.level().dimension()) && region.footprint() != null
                    && region.footprint().containsInterior(position)) return true;
        return false;
    }

    /** Sneak-click two corners, preview, then confirm the same selection. */
    public void selectCorner(ServerPlayer player, BlockPos position) {
        var id = player.getUUID();
        var selection = selections.get(id);
        if (selection == null || !selection.dimension().equals(player.level().dimension())) {
            selections.put(id, new Selection(player.level().dimension(), position.immutable(), null, false));
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("已选择第一个角点 " + position.toShortString() + "；潜行点击第二个角点"));
            return;
        }
        if (selection.second() == null) {
            selections.put(id, new Selection(selection.dimension(), selection.first(), position.immutable(), false));
            previewSelection(player);
            return;
        }
        if (!selection.previewed()) { previewSelection(player); return; }
        FluidRegionFootprint footprint;
        try { footprint = selectionFootprint(player.level(), selection); }
        catch (IllegalArgumentException failure) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(failure.getMessage()));
            selections.remove(id);
            return;
        }
        Result result = createSelection(player, footprint);
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(result.succeeded()
                ? "区域已创建：" + result.sizeX() + " × " + result.sizeY() + " × " + result.sizeZ()
                : "创建失败：" + result.failure()));
        selections.remove(id);
    }

    private void previewSelection(ServerPlayer player) {
        Selection selection = selections.get(player.getUUID());
        try {
            FluidRegionFootprint footprint = selectionFootprint(player.level(), selection);
            long cells = FluidRegionFactory.grid(footprint).cellCount();
            var admission = regions.previewAdd(cells);
            if (!admission.accepted()) throw new IllegalArgumentException(admission.failure());
            for (FluidRegion region : regions.regions()) {
                if (!region.dimension().equals(player.level().dimension()) || region.footprint() == null) continue;
                for (long block : footprint.interiorBlocks())
                    if (region.footprint().containsInterior(BlockPos.of(block)))
                        throw new IllegalArgumentException("选区与现有区域重叠：" + region.id());
            }
            String failure = RegionWorldAvailability.availabilityFailure(player.level(), footprint);
            if (failure != null) throw new IllegalArgumentException(failure);
            selections.put(player.getUUID(), new Selection(selection.dimension(), selection.first(), selection.second(), true));
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("选区预览：" + footprint.minimumInterior().toShortString()
                    + " 至 " + footprint.maximumInterior().toShortString() + "，" + footprint.interiorBlocks().size()
                    + " 方块，" + cells + " 单元，" + footprint.requiredChunks().size() + " 区块；再次潜行点击确认创建"));
        } catch (IllegalArgumentException | ArithmeticException failure) {
            selections.remove(player.getUUID());
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal("选区无效：" + failure.getMessage()));
        }
    }

    private FluidRegionFootprint selectionFootprint(ServerLevel level, Selection selection) {
        BlockPos a = selection.first(), b = selection.second();
        int minX = Math.min(a.getX(), b.getX()), minY = Math.min(a.getY(), b.getY()), minZ = Math.min(a.getZ(), b.getZ());
        int maxX = Math.max(a.getX(), b.getX()), maxY = Math.max(a.getY(), b.getY()), maxZ = Math.max(a.getZ(), b.getZ());
        long blocks = Math.multiplyExact(Math.multiplyExact((long) maxX-minX+1, (long) maxY-minY+1), (long) maxZ-minZ+1);
        if (blocks > regions.limits().maximumCellsPerRegion() / FluidRegionFactory.CELL_VOLUMES_PER_BLOCK)
            throw new IllegalArgumentException("选区超出区域单元预算");
        var interior = new java.util.HashSet<Long>();
        var chunks = new java.util.HashSet<Long>();
        for (int x=minX; x<=maxX; x++) for (int y=minY; y<=maxY; y++) for (int z=minZ; z<=maxZ; z++) {
            BlockPos pos = new BlockPos(x,y,z);
            if (!level.isInWorldBounds(pos) || !level.isInsideBuildHeight(pos)) throw new IllegalArgumentException("选区超出世界范围");
            interior.add(pos.asLong());
            chunks.add(net.minecraft.world.level.ChunkPos.pack(x >> 4, z >> 4));
        }
        BlockPos min = new BlockPos(minX,minY,minZ), max = new BlockPos(maxX,maxY,maxZ);
        return FluidRegionFootprint.selected(min, max, interior, chunks);
    }

    public Result createSelection(ServerPlayer player, BlockPos first, BlockPos second) {
        try {
            return createSelection(player, selectionFootprint(player.level(),
                    new Selection(player.level().dimension(), first, second, true)));
        } catch (IllegalArgumentException | ArithmeticException failure) {
            return Result.failed(failure.getMessage());
        }
    }

    private Result createSelection(ServerPlayer player, FluidRegionFootprint footprint) {
        if (!controllerFeatureEnabled.getAsBoolean()) return Result.DISABLED;
        if (!startup.authoritativeWritesReady()) return Result.failed(authoritativeWriteFailure("创建区域"));
        if (containerRecovery.hasStoreIssues() || operations.creationLocked() || !ownershipIssues.get().isEmpty())
            return Result.failed("现有存档有未完成的恢复，请先处理日志中的问题");
        if (takeover.pendingBlockCount() > 0) return Result.failed("请先恢复待处理的原版水体");
        RestorationStats restoration = takeover.restore(player.level().getServer());
        if (!restoration.completed()) return Result.failed(restoration.failure());
        return createRegion(player, footprint);
    }

    private record Selection(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
                             BlockPos first, BlockPos second, boolean previewed) { }

	Result activatePool(ServerPlayer player, FluidRegionFootprint footprint) {
		if (!controllerFeatureEnabled.getAsBoolean() || !worldBridgeFeatureEnabled) return Result.failed("请先启用区域和原版水接管功能");
		if (!startup.authoritativeWritesReady()) return Result.failed(authoritativeWriteFailure("接管池水"));
		if (containerRecovery.hasStoreIssues() || operations.creationLocked() || !ownershipIssues.get().isEmpty())
			return Result.failed("现有存档有未完成的恢复，请先处理日志中的问题");
		if (footprint.kind() != mmm.model.region.RegionGeometry.RegionKind.POOL) return Result.failed("目标不是池体");
		return createRegion(player, footprint);
	}

	private Result createRegion(ServerPlayer player, FluidRegionFootprint footprint) {
		ServerLevel level=player.level();
		String unavailable = RegionWorldAvailability.availabilityFailure(level, footprint);
		if (unavailable != null) return Result.failed(unavailable);
		List<OwnershipJournal.BlockEntry> ownershipPreimage;
		try {
			ownershipPreimage = operations.scanOwnershipPreimage(level, footprint);
		} catch (IOException invalidPreimage) {
			return Result.failed("无法读取区域世界方块原状态：" + invalidPreimage.getMessage());
		}
		if (!worldBridgeFeatureEnabled && !ownershipPreimage.isEmpty()) {
			return Result.failed("worldbridge 策略关闭；拒绝接管选区内原版水，请先排空或启用策略并重启");
		}
		RegionCreationPlan creationPlan = new RegionCreationPlan(
				world.profile().profileFingerprint(), footprint, ownershipPreimage);
		var air = new WorldThermalEnvironment.AirHandoff[1];
		var preparedEnvironment = new mmm.fluid.PreparedEnvironmentState[1];
		var preparedFluid = new mmm.fluid.PreparedFluidState[1];
		boolean[] adopted = {false};
		try {
		RegionCreation.Result creation = RegionCreation.create(
				regions, level.dimension(), footprint, defaultEntityPhysics.getAsBoolean(),
				defaultDebugOverlays.getAsBoolean(), world, () -> RegionWorldAvailability.availabilityFailure(level, footprint),
				candidate -> {
					if (environment == null) {
						regionTick.refreshRegionInputs(level, candidate);
						if (worldBridgeFeatureEnabled) FluidRegionFactory.captureVanillaWater(level, candidate);
						world.bindConfiguredState(candidate.editableHost(), maximumThermalSubstepsPerStep);
						regionTick.initializeThermalBoundary(level, candidate);
						return;
					}
					FluidRegionWorldInteraction.refreshCollisions(level, candidate, FluidRegionFactory.CELLS_PER_BLOCK);
					world.bindConfiguredState(candidate.editableHost(), maximumThermalSubstepsPerStep);
					var positions = footprint.interiorBlocks().stream().map(BlockPos::of).collect(java.util.stream.Collectors.toSet());
					air[0] = environment.prepareRange(level, candidate, positions);
					var transfer = air[0].split(candidate.origin(), candidate.grid());
					var old = candidate.editableHost().captureAuthoritativeState();
					var previousGas = old.spatialGas();
					if (previousGas == null) throw new IllegalStateException("区域初始气体缺失");
					var gas = new mmm.fluid.air.SpatialGasState(candidate.grid(), previousGas.registryFingerprint(),
							previousGas.chemicalRegistryFingerprint(), transfer.cells(), transfer.energy(), transfer.source(),
							mmm.fluid.air.GasAuditLedger.EMPTY, mmm.fluid.air.GasAuditLedger.EMPTY, mmm.fluid.air.GasAuditLedger.EMPTY);
					var host = HostFluidState.restoreAuthoritativeState(mmm.fluid.FluidDomainInitialGas.replace(old, gas, transfer.roundoff()));
					candidate.commitHostEdit(candidate.solverGeneration(), host);
					if (worldBridgeFeatureEnabled) FluidRegionFactory.captureVanillaWater(level, candidate);
					preparedEnvironment[0] = air[0].prepare();
					candidate.environmentCandidate(preparedEnvironment[0].capture());
					candidate.environmentCandidateExternal(true);
					try (var source = candidate.captureSolverState()) {
						preparedFluid[0] = regions.solverBackend().prepareState(source, source.exportState(),
								source.finiteThermalDevices(), source.finiteThermalPorts(), null);
					}
					preparedFluid[0].attachEnvironment(preparedEnvironment[0]);
				}, candidate -> {
					if (!creationPlan.profileFingerprint().equals(world.profile().profileFingerprint())
							|| !creationPlan.footprint().equals(candidate.footprint())) {
						throw new IOException("Region creation inputs changed after preflight");
					}
					long targetGeneration = Math.addExact(candidate.checkpointGeneration(), 1L);
					try {
						if (worldBridgeFeatureEnabled) operations.acquireOwnership(level, candidate, creationPlan.ownershipPreimage());
						else operations.checkpoint(candidate);
						if (preparedFluid[0] != null) {
							candidate.adoptResizedDevice(preparedFluid[0].commit());
							air[0].adopted();
							adopted[0] = true;
							candidate.clearEnvironmentCandidate();
							candidate.requireCollisionRefresh();
						}
					} catch (IOException | RuntimeException failure) {
						boolean published = candidate.checkpointGeneration() >= targetGeneration
								|| operations.worldCheckpointContains(candidate, targetGeneration);
						if (!published && environment == null) {
							try { published = operations.committedGeneration(candidate.id()) == targetGeneration; }
							catch (IOException inspection) { failure.addSuppressed(inspection); }
						}
						if (published) {
							if (candidate.checkpointGeneration() < targetGeneration) candidate.recordPublishedCheckpoint(targetGeneration);
							try {
								if (preparedEnvironment[0] != null && preparedEnvironment[0].isCurrent()) preparedEnvironment[0].commit();
								if (air[0] != null && !adopted[0]) {
									var expected = candidate.environmentCandidate();
									var actual = environment.capture().get(expected.dimensionId());
									if (actual == null || actual.generation() != expected.generation())
										throw new IllegalStateException("共享环境需要从已保存的世界代恢复");
									air[0].adopted(); adopted[0] = true; candidate.clearEnvironmentCandidate();
								}
							} catch (RuntimeException recovery) { failure.addSuppressed(recovery); }
							candidate.blockNumerical("已保存的区域等待恢复：" + failure.getMessage());
							var recovery = regions.addAndSelect(candidate);
							if (!recovery.accepted()) failure.addSuppressed(new IllegalStateException(recovery.failure()));
						}
						throw failure;
					}
				});
		if (creation.failure() != null) {
			if (creation.retainShell()) {
				operations.recordIssue(creation.failure());
				MmmMod.LOGGER.error("Region creation requires recovery: {}", creation.failure());
				return Result.failedAfterPersistence(creation.failure());
			}
			return Result.failed(creation.failure());
		}
		FluidRegion candidate = creation.region();
		snapshots.sendToLevel(level, candidate);
		return Result.created(footprint);
		} finally {
			if (preparedFluid[0] != null) preparedFluid[0].close();
			if (preparedEnvironment[0] != null) preparedEnvironment[0].close();
			if (air[0] != null && !adopted[0]) air[0].discard();
		}
	}

	private String authoritativeWriteFailure(String operation) {
		if (!startup.authoritativeWritesReady() && startup.state() != WorldStartupCoordinator.State.READY) {
			return operation + "失败：world capability startup=" + startup.state()
					+ "；不可证明的 profile 不会创建或改写区域";
		}
		return operation + "失败：server.properties 未通过校验；原文件已保留，请修复后重启服务器";
	}

	public record Result(
			boolean succeeded, boolean disabled, Action action, String failure,
			int interiorBlocks, int sizeX, int sizeY, int sizeZ, int requiredChunks, boolean retainShell
	) {
		private static final Result DISABLED = new Result(
				false, true, null, "controller feature flag is disabled", 0, 0, 0, 0, 0, false);

		private static Result failed(String failure) {
			return new Result(false, false, null, failure, 0, 0, 0, 0, 0, false);
		}

		private static Result failedAfterPersistence(String failure) {
			return new Result(false, false, null, failure, 0, 0, 0, 0, 0, true);
		}

		private static Result created(FluidRegionFootprint footprint) {
			return succeeded(footprint, Action.CREATED);
		}

		private static Result succeeded(FluidRegionFootprint footprint, Action action) {
			return new Result(true, false, action, null, footprint.interiorBlocks().size(),
					footprint.blockSizeX(), footprint.blockSizeY(), footprint.blockSizeZ(),
					footprint.requiredChunks().size(), false);
		}
	}

	public enum Action { CREATED, SELECTED_RUNNING, SELECTED_PAUSED, PAUSED, RESUMED }

	private record RegionCreationPlan(
			String profileFingerprint,
			FluidRegionFootprint footprint,
			List<OwnershipJournal.BlockEntry> ownershipPreimage
	) {
		private RegionCreationPlan {
			if (profileFingerprint == null || !profileFingerprint.matches("[0-9a-f]{64}")) {
				throw new IllegalArgumentException("Controller creation profile fingerprint is invalid");
			}
			Objects.requireNonNull(footprint, "footprint");
			ownershipPreimage = List.copyOf(ownershipPreimage);
		}
	}
}
