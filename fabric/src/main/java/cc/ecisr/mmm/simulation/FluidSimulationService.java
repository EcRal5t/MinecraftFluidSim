package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.MmmMod;
import cc.ecisr.mmm.api.RegionAvailabilityReason;
import cc.ecisr.mmm.application.ContainerRecoveryCoordinator;
import cc.ecisr.mmm.application.ContainerTransferCoordinator;
import cc.ecisr.mmm.application.ControllerQueryUseCase;
import cc.ecisr.mmm.application.RegionApplicationService;
import cc.ecisr.mmm.application.ThermalReservoirUseCase;
import cc.ecisr.mmm.application.command.MotionStepCommand;
import cc.ecisr.mmm.application.result.MotionStepResult;
import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.fluid.CpuFluidSolverBackend;
import cc.ecisr.mmm.fluid.FluidDomain;
import cc.ecisr.mmm.fluid.FluidSolverBackend;
import cc.ecisr.mmm.fluid.GasTopologyBlockedException;
import cc.ecisr.mmm.fluid.air.GasRegistry;
import cc.ecisr.mmm.granular.GranularMechanics;
import cc.ecisr.mmm.granular.GranularRegistry;
import cc.ecisr.mmm.thermal.ThermalRegistry;
import cc.ecisr.mmm.world.MovingBodyFluidCoupling;
import cc.ecisr.mmm.worldprofile.WorldCapability;
import cc.ecisr.mmm.worldprofile.WorldCapabilityProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Adapts fluid regions to Minecraft while delegating region-state lifecycle and takeover transactions. */
public final class FluidSimulationService {
	private final Path worldRoot;
	private final boolean serverConfigLocked;
	private FluidServerConfig serverConfig;
	private final FluidRegionManager regionManager;
	private final FluidSolverBackend solverBackend = new CpuFluidSolverBackend();
	private final CommittedEventHub committedEvents;
	private final FluidWorldTargetResolver targets;
	private final FluidRegionOperations regionOperations;
	private final RegionApplicationService regionApplication;
	private final MinecraftTakeoverAdapter takeover;
	private final MinecraftDebugFixtures debugFixtures;
	private final MinecraftRegionTick regionTick;
	private final MinecraftRegionAvailability regionAvailability;
	private final ControllerQueryUseCase controllerQueries;
	private final MinecraftRegionLifecycle regionLifecycle;
	private final ThermalReservoirUseCase thermalReservoirs = new ThermalReservoirUseCase();
	private final MinecraftControllerConsole controllerConsole;
	private final MinecraftRegionActivation regionActivation;
	private final MinecraftIndicatorEditing indicatorEditing;
	private final MinecraftRegionAdministration regionAdministration;
	private final RegionDiagnosticsReport regionDiagnostics;
	private final PlayerContainerTransferAdapter playerContainerTransfers;
	private final DispenserContainerTransferAdapter dispenserContainerTransfers;
	private final FluidSnapshotPublisher snapshots;
	private final ContainerRecoveryCoordinator containerRecovery;
	private final MinecraftContainerTransactionAdapter containerTransactions;
	private boolean defaultEntityPhysicsEnabled = true;
	private boolean defaultDebugOverlays;
	private boolean controllerFeatureEnabled;
	private final boolean worldBridgeFeatureEnabled;
	/** Typed startup proof retained so no authoritative write can use the fail-closed empty profile. */
	private final WorldStartupCoordinator.Result startup;
	/** Committed profile, or an empty fail-closed value while startup is not provable. */
	private final CommittedWorldRegistries worldRegistries;
	private List<String> ownershipIssues = List.of();

	FluidSimulationService(
			WorldStartupCoordinator.Result startup,
			CommittedWorldRegistries worldRegistries,
			FluidRegionManager regionManager,
			FluidRegionOperations regionOperations,
			FluidSnapshotPublisher snapshots,
			ContainerRecoveryCoordinator containerRecovery,
			List<String> ownershipIssues,
			CommittedEventHub committedEvents
	) {
		this.startup = java.util.Objects.requireNonNull(startup, "World startup result");
		this.worldRoot = startup.worldRoot();
		this.serverConfig = startup.config();
		this.serverConfigLocked = !startup.policyReadable();
		this.controllerFeatureEnabled = startup.policyReadable() && serverConfig.controllersEnabled();
		this.worldBridgeFeatureEnabled = startup.policyReadable() && serverConfig.worldBridgeEnabled();
		this.worldRegistries = java.util.Objects.requireNonNull(worldRegistries, "Committed world registries");
		WorldCapabilityProfile worldCapabilityProfile = worldRegistries.profile();
		ChemicalRegistry chemicalRegistry = worldRegistries.chemicalOrNull();
		GranularRegistry granularRegistry = worldRegistries.granularOrNull();
		ThermalRegistry thermalRegistry = worldRegistries.thermalOrNull();
		MinecraftRegionInputs regionInputs = new MinecraftRegionInputs(
				thermalRegistry, FluidRegionFactory.CELLS_PER_BLOCK);
		this.regionManager = java.util.Objects.requireNonNull(regionManager, "Region manager");
		this.committedEvents = java.util.Objects.requireNonNull(committedEvents, "Committed event hub");
		this.targets = new FluidWorldTargetResolver(regionManager);
		this.regionOperations = java.util.Objects.requireNonNull(regionOperations, "Region operations");
		this.snapshots = java.util.Objects.requireNonNull(snapshots, "Snapshot publisher");
		this.containerRecovery = java.util.Objects.requireNonNull(containerRecovery, "Container recovery coordinator");
		this.containerTransactions = new MinecraftContainerTransactionAdapter(regionManager, regionOperations,
				containerRecovery, worldCapabilityProfile.profileFingerprint(),
				thermalRegistry == null ? null : thermalRegistry.fingerprint());
		this.ownershipIssues = List.copyOf(ownershipIssues);
		this.regionApplication = new RegionApplicationService(solverBackend,
				new FluidRegionRepository(regionManager), regionOperations);
		this.regionAdministration = new MinecraftRegionAdministration(
				regionManager, regionOperations, regionApplication, containerRecovery);
		this.takeover = new MinecraftTakeoverAdapter();
		this.debugFixtures = new MinecraftDebugFixtures(regionManager, snapshots, takeover, debugRuntime(),
				worldCapabilityProfile, () -> defaultEntityPhysicsEnabled, () -> defaultDebugOverlays);
		this.regionTick = new MinecraftRegionTick(regionApplication, regionInputs, snapshots,
				worldCapabilityProfile, serverConfig.maximumPhaseOperationsPerStep());
		this.regionAvailability = new MinecraftRegionAvailability(containerRecovery, regionOperations,
				() -> controllerFeatureEnabled, regionTick, snapshots, worldRegistries);
		this.controllerQueries = new ControllerQueryUseCase(
				new ControllerSummaryAdapter(regionManager, regionOperations, worldCapabilityProfile, thermalRegistry,
						this::hasPendingContainerTransfer, this::authoritativeWritesReady, worldBridgeFeatureEnabled));
		this.regionLifecycle = new MinecraftRegionLifecycle(startup, regionManager, regionOperations, snapshots, targets,
				controllerQueries, thermalRegistry, this::hasPendingContainerTransfer);
		this.controllerConsole = new MinecraftControllerConsole(targets, controllerQueries, regionLifecycle,
				thermalReservoirs, regionTick, thermalRegistry, startup, regionManager,
				this::authoritativeWritesReady, this::ensureRegionAvailable);
		this.regionActivation = new MinecraftRegionActivation(startup, worldRegistries, regionManager,
				regionOperations, snapshots, regionTick, takeover, debugFixtures, containerRecovery,
				() -> controllerFeatureEnabled, () -> defaultEntityPhysicsEnabled, () -> defaultDebugOverlays,
				() -> ownershipIssues, regionAvailability, worldBridgeFeatureEnabled,
				serverConfig.maximumThermalSubstepsPerStep());
		this.indicatorEditing = new MinecraftIndicatorEditing(worldRegistries, targets, regionOperations,
				snapshots, regionAvailability);
		this.regionDiagnostics = new RegionDiagnosticsReport(regionManager, regionOperations, takeover,
				debugFixtures, worldRegistries, () -> controllerFeatureEnabled, () -> worldBridgeFeatureEnabled,
				this::configuredWorldBridgeFeatureEnabled, () -> ownershipIssues,
				() -> serverConfig.maximumPhaseOperationsPerStep());
		this.playerContainerTransfers = new PlayerContainerTransferAdapter(startup, worldRegistries, targets,
				regionAvailability, containerTransactions, snapshots);
		this.dispenserContainerTransfers = new DispenserContainerTransferAdapter(startup, worldRegistries, targets,
				regionAvailability, containerTransactions, snapshots);
	}

	MinecraftControllerConsole controllerConsole() { return controllerConsole; }
	MinecraftRegionActivation regionActivation() { return regionActivation; }
	MinecraftIndicatorEditing indicatorEditing() { return indicatorEditing; }
	MinecraftRegionAdministration regionAdministration() { return regionAdministration; }
	RegionDiagnosticsReport regionDiagnostics() { return regionDiagnostics; }
	MinecraftDebugFixtures debugFixtures() { return debugFixtures; }
	PlayerContainerTransferAdapter playerContainerTransfers() { return playerContainerTransfers; }
	DispenserContainerTransferAdapter dispenserContainerTransfers() { return dispenserContainerTransfers; }

	MinecraftContainerTransactionAdapter containerTransactions() { return containerTransactions; }

	public void setContainerTransferFault(ContainerTransferCoordinator.FaultPoint point) {
		containerTransactions.setFault(point);
	}

	public ContainerTransferCoordinator.FaultPoint containerTransferFault() {
		return containerTransactions.fault();
	}

	boolean hasPendingContainerTransfer(UUID regionId) { return containerRecovery.hasPending(regionId); }

	boolean hasAnyContainerTransferLock() { return containerRecovery.hasAnyLock(); }

	boolean hasContainerTransferStoreIssues() { return containerRecovery.hasStoreIssues(); }

	public void recoverContainerTransfers(MinecraftServer server) { containerRecovery.recoverIndexed(); }

	public void retryUnavailableContainerTransfers(MinecraftServer server, UUID playerId) {
		containerRecovery.retryPlayer(playerId);
	}

	public void retryUnavailableContainerTransfers(ServerLevel level, ChunkPos chunk) {
		containerRecovery.retryDispenserChunk(
				level.dimension().identifier().toString(), chunk.x(), chunk.z());
	}

	void recoverTakeoverBackup(MinecraftServer server) {
		for (String issue : regionOperations.issues()) MmmMod.LOGGER.error("Matter, Motion & Mechanics persistent region issue: {}", issue);
		RestorationStats result = restoreTakeover(server);
		if (result.completed() && result.journalFound()) {
			MmmMod.LOGGER.warn(
					"Recovered unfinished Matter, Motion & Mechanics takeover: restored {} blocks, already restored {}, skipped {} occupied positions",
					result.restoredBlocks(), result.alreadyRestoredBlocks(), result.skippedBlocks()
			);
		}
	}

	/** Boot-time ownership recovery; refreshes the issue list so resolved WALs stop locking creation. */
	void recoverWorldOwnership(MinecraftServer server) {
		List<String> failures = regionOperations.recoverUnfinishedOwnership(server);
		List<String> refreshed = new java.util.ArrayList<>(regionOperations.scanOwnershipIssues());
		for (String failure : failures) {
			refreshed.add(failure);
			MmmMod.LOGGER.error("Matter, Motion & Mechanics world ownership recovery failed: {}", failure);
		}
		ownershipIssues = refreshed;
	}

	public boolean exists() {
		return activeRegion() != null;
	}

	public boolean paused() {
		FluidRegion region = activeRegion();
		return region == null || region.paused();
	}

	public boolean controllerFeatureEnabled() {
		return controllerFeatureEnabled;
	}

	boolean authoritativeWritesReady() {
		return startup.authoritativeWritesReady();
	}

	private String authoritativeWriteFailure(String operation) {
		if (!startup.authoritativeWritesReady() && startup.state() != WorldStartupCoordinator.State.READY) {
			return operation + "失败：world capability startup=" + startup.state()
					+ "；不可证明的 profile 不会创建或改写区域";
		}
		return operation + "失败：server.properties 未通过校验；原文件已保留，请修复后重启服务器";
	}

	/** Returns null on success; the world-local flag is durably replaced before runtime state changes. */
	public String setControllerFeatureEnabled(boolean enabled) {
		if (serverConfigLocked) return "server.properties 未通过校验；原文件已保留，请修复后重启服务器";
		FluidServerConfig replacement = serverConfig.withControllersEnabled(enabled);
		try {
			replacement.save(worldRoot);
		} catch (IOException exception) {
			MmmMod.LOGGER.error("Could not persist Matter, Motion & Mechanics controller feature flag", exception);
			return exception.getMessage();
		}
		serverConfig = replacement;
		controllerFeatureEnabled = enabled;
		if (!enabled) {
			for (FluidRegion region : regionManager.regions()) {
				if (region.controllerManaged()) region.setAvailabilityFailure(
						RegionAvailabilityReason.POLICY_DISABLED, "controller admission policy is disabled");
			}
		}
		return null;
	}

	/** Live world-bridge gate; ownership gameplay binds to this boot's value like the material gate. */
	public boolean worldBridgeFeatureEnabled() {
		return worldBridgeFeatureEnabled;
	}

	public boolean configuredWorldBridgeFeatureEnabled() {
		return serverConfig.worldBridgeEnabled();
	}

	/** Persists the next-startup world-bridge gate; ownership binds to this boot's value. */
	public String setWorldBridgeFeatureForRestart(boolean enabled) {
		if (serverConfigLocked) return "server.properties 未通过校验；原文件已保留，请修复后重启服务器";
		FluidServerConfig replacement = serverConfig.withWorldBridgeEnabled(enabled);
		try {
			replacement.save(worldRoot);
		} catch (IOException exception) {
			MmmMod.LOGGER.error("Could not persist Matter, Motion & Mechanics world-bridge feature flag", exception);
			return exception.getMessage();
		}
		serverConfig = replacement;
		return null;
	}

	private MinecraftRegionActivation.Result activateController(
			ServerPlayer player, BlockPos controllerPosition
	) {
		return regionActivation.activate(player, controllerPosition, false);
	}

	/** Restores every journaled vanilla-water block; the journal is cleared only after the world pass. */
	public RestorationStats restoreTakeover(MinecraftServer server) {
		return takeover.restore(server);
	}

	/** The narrow seam the debug fixtures need back into the live region runtime. */
	private MinecraftDebugFixtures.Runtime debugRuntime() {
		return new MinecraftDebugFixtures.Runtime() {
			@Override public FluidRegion.CollisionStats refreshCollisions(ServerLevel level) {
				return FluidSimulationService.this.refreshCollisions(level);
			}

			@Override public void stepMotion(FluidRegion region) {
				stepMotionOnce(region, List.of());
			}

			@Override public void bindCapabilityState(FluidDomain domain) {
				worldRegistries.bindConfiguredState(domain, serverConfig.maximumThermalSubstepsPerStep());
			}

			@Override public String activateController(ServerPlayer player, BlockPos controllerPosition) {
				MinecraftRegionActivation.Result activation =
						FluidSimulationService.this.activateController(player, controllerPosition);
				return activation.succeeded() ? null : activation.failure();
			}
		};
	}

	public boolean setPaused(boolean value, ServerLevel level) {
		FluidRegion region = activeRegion();
		if (region == null) return false;
		if (!value && !ensureRegionAvailable(level, region)) return false;
		region.setPaused(value);
		return true;
	}

	public boolean entityPhysicsEnabled() {
		FluidRegion region = activeRegion();
		return region == null ? defaultEntityPhysicsEnabled : region.entityPhysicsEnabled();
	}

	public void setEntityPhysicsEnabled(boolean enabled) {
		defaultEntityPhysicsEnabled = enabled;
		FluidRegion region = activeRegion();
		if (region != null) region.setEntityPhysicsEnabled(enabled);
	}

	public boolean debugOverlays() {
		FluidRegion region = activeRegion();
		return region == null ? defaultDebugOverlays : region.debugOverlays();
	}

	public void setDebugOverlays(boolean enabled, ServerLevel level) {
		defaultDebugOverlays = enabled;
		FluidRegion region = activeRegion();
		if (region != null) region.setDebugOverlays(enabled);
		sendSnapshot(level);
	}

	public int step(ServerLevel level, int count) {
		if (!hasRegionIn(level)) return 0;
		FluidRegion region = activeRegion();
		if (!ensureRegionAvailable(level, region)) return -1;
		region.wakeForActivity();
		try {
			for (int i = 0; i < count; i++) stepMotionOnce(region, List.of());
		} catch (FluidDomain.PressureSolveBlockedException blocked) {
			regionTick.blockNumerical(level, region, blocked);
			return -1;
		} catch (GasTopologyBlockedException blocked) {
			regionTick.blockGasTopology(level, region, blocked);
			return -1;
		} catch (FluidDomain.ThermalTransportBlockedException blocked) {
			regionTick.blockThermal(level, region, "thermal-transport PAUSED：" + blocked.getMessage(), blocked);
			return -1;
		} catch (FluidDomain.GasThermalBlockedException blocked) {
			regionTick.blockThermal(level, region, "gas-thermal PAUSED：" + blocked.getMessage(), blocked);
			return -1;
		} catch (FluidDomain.ThermalPhaseBlockedException blocked) {
			regionTick.blockThermal(level, region, "phase-change PAUSED：" + blocked.getMessage(), blocked);
			return -1;
		} catch (GranularMechanics.ValidityExceededException invalid) {
			region.blockGranular("granular-validity-blocked PAUSED：" + invalid.getMessage());
			return -1;
		}
		sendSnapshot(level);
		return count;
	}

	public void tick(MinecraftServer server) {
		regionOperations.advanceTick();
		recoverContainerTransfers(server);

		takeover.maintainActive(server);
		for (FluidRegion region : regionManager.regions()) {
			ServerLevel level = server.getLevel(region.dimension());
			if (level == null) {
				if (hasPendingContainerTransfer(region.id())) {
					region.setAvailabilityFailure(RegionAvailabilityReason.PENDING_WAL,
							"dimension is not loaded; unresolved container transfer WAL prevents unload checkpoint");
					continue;
				}
				regionOperations.unloadForFailure(server, region, RegionAvailabilityReason.CHUNK_MISSING,
						"dimension is not loaded", snapshots::sendRemoval);
				continue;
			}
			if (ensureRegionAvailable(level, region)) regionTick.tickAvailable(level, region);
		}
		regionTick.finishServerTick();
	}

	public boolean ensureRegionAvailable(ServerLevel level, FluidRegion region) {
		return regionAvailability.ensure(level, region);
	}

	/**
	 * Evaluates one logical motion step through the complete candidate boundary and adopts the result
	 * atomically. The live domain is only replaced after the backend returns its whole successor, so a
	 * rejected step leaves no partially applied marker momentum.
	 */
	private MotionStepResult stepMotionOnce(
			FluidRegion region, List<MovingBodyFluidCoupling.Request> movingBodies
	) {
		long start = System.nanoTime();
		MotionStepResult result = regionApplication.stepMotion(new MotionStepCommand(
				region.id(), region.solverGeneration(), movingBodies,
				worldRegistries.hasCapability(WorldCapability.PHASECHANGE),
				worldRegistries.hasCapability(WorldCapability.PHASECHANGE)
						? serverConfig.maximumPhaseOperationsPerStep() : 0, false));
		if (!result.accepted()) throw new IllegalStateException(
				"Motion step application rejected synchronous region state: " + result.status());
		region.recordStep(result.stats(), (System.nanoTime() - start) / 1_000_000.0);
		return result;
	}

	public WaterEditResult pourWater(ServerPlayer player, int buckets) {
		if (!hasRegionIn(player.level())) return WaterEditResult.NO_DOMAIN;
		FluidRegion region = activeRegion();
		if (!ensureRegionAvailable(player.level(), region)) {
			return WaterEditResult.blocked(region.availabilityFailure());
		}
		FluidWorldTargetResolver.CellTarget resolved = targets.aimedCellForEdit(player, region, false);
		if (resolved == null) return WaterEditResult.NO_TARGET;
		CellTarget target = new CellTarget(resolved.x(), resolved.y(), resolved.z());
		float requested = buckets * FluidRegionFactory.CELL_VOLUMES_PER_BLOCK;
		FluidDomain.VolumeAddition addition = domain().addVolumeNearBudgeted(
				target.x(), target.y(), target.z(), requested, 6
		);
		if (addition.addedVolume() > 0.0F) region.wakeForActivity();
		sendSnapshot(player.level());
		return new WaterEditResult(true, target, requested, addition.addedVolume(), addition.markerBudgetLimited(), null);
	}

	public WaterEditResult drainWater(ServerPlayer player, int buckets) {
		if (!hasRegionIn(player.level())) return WaterEditResult.NO_DOMAIN;
		FluidRegion region = activeRegion();
		if (!ensureRegionAvailable(player.level(), region)) {
			return WaterEditResult.blocked(region.availabilityFailure());
		}
		FluidWorldTargetResolver.CellTarget resolved = targets.aimedCellForEdit(player, region, true);
		if (resolved == null) return WaterEditResult.NO_TARGET;
		CellTarget target = new CellTarget(resolved.x(), resolved.y(), resolved.z());
		float requested = buckets * FluidRegionFactory.CELL_VOLUMES_PER_BLOCK;
		float changed = domain().removeVolumeNear(target.x(), target.y(), target.z(), requested, 6);
		if (changed > 0.0F) region.wakeForActivity();
		sendSnapshot(player.level());
		return new WaterEditResult(true, target, requested, changed, false, null);
	}

	/** Re-samples Minecraft collision shapes at the simulation's subcell resolution. */
	public FluidRegion.CollisionStats refreshCollisions(ServerLevel level) {
		FluidRegion region = activeRegion();
		if (region == null || !level.dimension().equals(region.dimension())) return FluidRegion.CollisionStats.EMPTY;
		if (!ensureRegionAvailable(level, region)) return region.lastCollisionStats();
		try { return regionTick.refreshRegionInputs(level, region); }
		catch (FluidDomain.CollisionEditBlockedException blocked) {
			regionTick.blockCollisionEdit(level, region, blocked);
			throw blocked;
		}
	}

	public RestorationStats remove(MinecraftServer server) {
		if (regionManager.regions().stream().anyMatch(FluidRegion::controllerManaged)) {
			return RestorationStats.failed(false, 0, 0, 0,
					"controller 区域只能在控制台依次清空、排气、解除 ownership 并确认 REMOVED");
		}
		if (hasAnyContainerTransferLock()) {
			return RestorationStats.failed(false, 0, 0, 0,
					"存在未完成或不可识别的容器转移 WAL；拒绝移除区域，先完成恢复");
		}
		RestorationStats restoration = restoreTakeover(server);
		if (!restoration.completed()) {
			FluidRegion region = activeRegion();
			if (region != null) region.setPaused(true);
			return restoration;
		}
		debugFixtures.restoreAirDemoScaffolding(server);
		regionManager.clear();
		snapshots.clearAll(server);
		return new RestorationStats(restoration.journalFound(), restoration.restoredBlocks(),
				restoration.alreadyRestoredBlocks(), restoration.skippedBlocks(),
				0, 0, true, null);
	}

	public String emptySelectedRegion(ServerLevel level) {
		if (!authoritativeWritesReady()) return authoritativeWriteFailure("清空区域库存");
		FluidRegion region = activeRegion();
		if (region == null || !level.dimension().equals(region.dimension())) return "没有活动流体域或位于其他维度";
		if (region.controllerManaged()) return "清空失败：controller 区域必须使用控制台 lifecycle 确认操作";
		if (!ensureRegionAvailable(level, region)) return "清空失败：控制器域当前不可用：" + region.availabilityFailure();
		if (hasPendingContainerTransfer(region.id())) {
			return "清空失败：区域存在未完成或不可识别的容器转移 WAL；拒绝清空";
		}
		FluidDomain domain = region.domain();
		if (domain.hasPendingPhaseVolumeWork()) {
			return "清空失败：区域存在尚未由下一次 pressure projection 消费的相变体积/功记录";
		}
		float beforeBlocks = domain.totalVolume() / FluidRegionFactory.CELL_VOLUMES_PER_BLOCK;
		int beforeParticles = domain.particleCount();
		FluidDomain.OperatorClearPlan clearPlan;
		try {
			clearPlan = domain.planOperatorClear();
		} catch (RuntimeException failure) {
			return "清空失败：联合液体/coherent/颗粒预检未通过，活动库存未改变：" + failure.getMessage();
		}
		FluidDomain.OperatorClear cleared;
		try {
			cleared = domain.commitOperatorClear(clearPlan);
		} catch (RuntimeException failure) {
			region.setPaused(true);
			region.setAvailabilityFailure("empty 联合提交违反已预检不变量：" + failure.getMessage());
			return "清空联合提交失败；区域已暂停：" + failure.getMessage();
		}
		region.wakeForActivity();
		FluidDomain.CondensedClear condensed = cleared.condensed();
		FluidDomain.GranularClear granular = cleared.granular();
		if (region.controllerManaged()) {
			try {
				long generation = regionOperations.checkpoint(region);
				sendSnapshot(level, region);
				return String.format("已清空区域：%.3f 方块液体（%d 个 marker），%d SVQ coherent（%d groups），%d SVQ 颗粒（active %d，deposit groups %d），checkpoint %d 已发布",
						beforeBlocks, beforeParticles, condensed.coherentVolumeSvq(), condensed.coherentGroups(),
						granular.solidVolumeSvq(), granular.activeParcels(), granular.depositGroups(), generation);
			} catch (IOException | RuntimeException exception) {
				region.setPaused(true);
				region.setAvailabilityFailure("empty 后 checkpoint 失败，活动 solver 已清空凝聚相；重启后可能恢复旧库存：" + exception.getMessage());
				return "已从活动 solver 清空液体、coherent 与颗粒，但 checkpoint 失败；区域已暂停：" + exception.getMessage();
			}
		}
		sendSnapshot(level, region);
		return String.format("已清空区域：%.3f 方块液体（%d 个 marker），%d SVQ coherent（%d groups），%d SVQ 颗粒（active %d，deposit groups %d）",
				beforeBlocks, beforeParticles, condensed.coherentVolumeSvq(), condensed.coherentGroups(),
				granular.solidVolumeSvq(), granular.activeParcels(), granular.depositGroups());
	}

	void checkpointForShutdown() {
		regionOperations.checkpointForShutdown(region -> !hasPendingContainerTransfer(region.id()));
	}

	public void sendSnapshot(ServerLevel level) {
		FluidRegion region = activeRegion();
		if (region != null && level.dimension().equals(region.dimension())) sendSnapshot(level, region);
	}

	/** Transfer-service queries: region presence, selection and availability in one dimension. */
	public FluidRegion selectedRegionIn(ServerLevel level) {
		return targets.selectedRegionIn(level);
	}

	public boolean ensureRegionAvailableFor(ServerPlayer player, FluidRegion region) {
		boolean available = ensureRegionAvailable(player.level(), region);
		if (!available) {
			player.sendSystemMessage(Component.literal("物质、运动与力学目标区域不可用：" + region.availabilityFailure()), true);
		}
		return available;
	}

	/** Bound shell/device blocks remain under lifecycle coordination until the runtime owner is removed. */
	public boolean isLifecycleManagedBlock(ServerLevel level, BlockPos position) {
		if (level == null || position == null || !level.getServer().isSameThread()) return false;
		long packed = position.asLong();
		for (FluidRegion region : regionManager.regions()) {
			if (region.dimension().equals(level.dimension()) && region.footprint() != null
					&& region.footprint().boundaryBlocks().contains(packed)) return true;
		}
		return false;
	}

	/**
	 * Resolves either an interior block or a visible controller shell block to the exact region
	 * and interior block used by a container transfer. A shell is a port only when it has one
	 * face-adjacent interior in one region; shared, edge and corner shells fail closed.
	 */
	public TransferTarget transferTargetAtBlock(ServerLevel level, BlockPos position) {
		return transferTargetAtBlock(level, position, null);
	}

	/** Dispenser variant: the interior must continue in the dispenser's facing direction. */
	public TransferTarget transferTargetAtBlock(
			ServerLevel level, BlockPos position, Direction boundaryInteriorDirection
	) {
		FluidWorldTargetResolver.TransferTarget resolved =
				targets.transferTargetAtBlock(level, position, boundaryInteriorDirection);
		return resolved == null ? null : new TransferTarget(resolved.region(), resolved.interiorBlock());
	}

	public record TransferTarget(FluidRegion region, BlockPos interiorBlock) { }

	/** First region volume intersected by the player's ray; selection is intentionally irrelevant. */
	public FluidRegion aimedRegion(ServerPlayer player) {
		return targets.aimedRegion(player);
	}

	/** Resolves the solver cell containing the center of a block position inside a loaded region. */
	public int[] cellAtBlock(ServerLevel level, FluidRegion region, BlockPos pos) {
		return targets.cellAtBlock(level, region, pos);
	}


	/** Resolves the transfer target cell near the player's aim; requireFluid selects scoop semantics. */
	public int[] aimedCellNear(ServerPlayer player, FluidRegion region, boolean requireFluid) {
		return targets.aimedCellNear(player, region, requireFluid);
	}

	/** First ray-intersected cell carrying active or exposed/deep granular inventory truth. */
	public int[] aimedGranularCellNear(ServerPlayer player, FluidRegion region) {
		return targets.aimedGranularCellNear(player, region);
	}

	public void sendSnapshotFor(ServerPlayer player) {
		FluidRegion region = activeRegion();
		if (region != null) sendSnapshotFor(player, region);
	}

	public void sendSnapshotFor(ServerPlayer player, FluidRegion region) {
		snapshots.sendTo(player, region);
	}

	public void sendSnapshot(ServerLevel level, FluidRegion region) {
		snapshots.sendToLevel(level, region);
	}

	private FluidRegion activeRegion() {
		return regionManager.selected();
	}

	private FluidDomain domain() {
		return activeRegion().domain();
	}

	private BlockPos origin() {
		return activeRegion().origin();
	}

	private boolean hasRegionIn(ServerLevel level) {
		FluidRegion region = activeRegion();
		return region != null && level.dimension().equals(region.dimension());
	}

	public record CellTarget(int x, int y, int z) { }

	public record WaterEditResult(
			boolean domainExists,
			CellTarget target,
			float requestedCellVolume,
			float changedCellVolume,
			boolean markerBudgetLimited,
			String failure
	) {
		private static final WaterEditResult NO_DOMAIN = new WaterEditResult(false, null, 0.0F, 0.0F, false, null);
		private static final WaterEditResult NO_TARGET = new WaterEditResult(true, null, 0.0F, 0.0F, false, null);

		private static WaterEditResult blocked(String failure) {
			return new WaterEditResult(true, null, 0.0F, 0.0F, false, failure);
		}

		public float changedBlocks() {
			return changedCellVolume / FluidRegionFactory.CELL_VOLUMES_PER_BLOCK;
		}
	}

}
