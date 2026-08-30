package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.MmmMod;
import cc.ecisr.mmm.api.RegionAvailabilityReason;
import cc.ecisr.mmm.application.ContainerRecoveryCoordinator;
import cc.ecisr.mmm.application.ContainerTransferCoordinator;
import cc.ecisr.mmm.application.ControllerQueryUseCase;
import cc.ecisr.mmm.application.RegionApplicationService;
import cc.ecisr.mmm.application.RegionCatalog;
import cc.ecisr.mmm.application.ThermalReservoirUseCase;
import cc.ecisr.mmm.application.command.CheckpointRegionCommand;
import cc.ecisr.mmm.application.command.MotionStepCommand;
import cc.ecisr.mmm.application.result.CheckpointRegionResult;
import cc.ecisr.mmm.application.result.MotionStepResult;
import cc.ecisr.mmm.block.MmmBlocks;
import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.fluid.CpuFluidSolverBackend;
import cc.ecisr.mmm.fluid.FluidDomain;
import cc.ecisr.mmm.fluid.GasPressureBoundary;
import cc.ecisr.mmm.fluid.FluidSolverBackend;
import cc.ecisr.mmm.fluid.FluidMaterial;
import cc.ecisr.mmm.fluid.GasTopologyBlockedException;
import cc.ecisr.mmm.fluid.air.AirNetwork;
import cc.ecisr.mmm.fluid.air.BuiltinGasDefinitions;
import cc.ecisr.mmm.fluid.air.GasAuditLedger;
import cc.ecisr.mmm.fluid.air.GasRegistry;
import cc.ecisr.mmm.granular.GranularMechanics;
import cc.ecisr.mmm.granular.GranularRegistry;
import cc.ecisr.mmm.transfer.ChemicalContents;
import cc.ecisr.mmm.transfer.FluidMixture;
import cc.ecisr.mmm.item.ContainerItemStacks;
import cc.ecisr.mmm.item.MmmComponents;
import cc.ecisr.mmm.simulation.controller.FluidBoundaryScanner;
import cc.ecisr.mmm.simulation.controller.FluidControllerRegions;
import cc.ecisr.mmm.simulation.controller.FluidRegionFootprint;
import cc.ecisr.mmm.simulation.ownership.OwnershipJournal;
import cc.ecisr.mmm.thermal.ThermalAuditLedger;
import cc.ecisr.mmm.thermal.ThermalMaterialDefinition;
import cc.ecisr.mmm.thermal.ThermalRegistry;
import cc.ecisr.mmm.thermal.ThermalDisplaySnapshot;
import cc.ecisr.mmm.thermal.ThermalStateFunction;
import cc.ecisr.mmm.fluid.MaterialRegistry;
import cc.ecisr.mmm.world.MovingBodyFluidCoupling;
import cc.ecisr.mmm.worldprofile.WorldCapability;
import cc.ecisr.mmm.worldprofile.WorldCapabilityProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

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
		this.playerContainerTransfers = new PlayerContainerTransferAdapter(startup, worldRegistries, targets,
				regionAvailability, containerTransactions, snapshots);
		this.dispenserContainerTransfers = new DispenserContainerTransferAdapter(startup, worldRegistries, targets,
				regionAvailability, containerTransactions, snapshots);
	}

	MinecraftControllerConsole controllerConsole() { return controllerConsole; }
	MinecraftRegionActivation regionActivation() { return regionActivation; }
	MinecraftIndicatorEditing indicatorEditing() { return indicatorEditing; }
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

	/** Restores the world blocks the gas-demo glass scaffold replaced before a new scenario. */
	public void restoreAirDemoScaffolding(MinecraftServer server) {
		debugFixtures.restoreAirDemoScaffolding(server);
	}

	public BlockPos createControllerDemo(ServerPlayer player) {
		return debugFixtures.createControllerDemo(player);
	}

	public boolean createDemo(ServerPlayer player) {
		return debugFixtures.createDemo(player);
	}

	public boolean createMaterialDemo(ServerPlayer player) {
		return debugFixtures.createMaterialDemo(player);
	}

	public boolean createChemistryDemo(ServerPlayer player) {
		return debugFixtures.createChemistryDemo(player);
	}

	public boolean createTensionDemo(ServerPlayer player, int angleDegrees) {
		return debugFixtures.createTensionDemo(player, angleDegrees);
	}

	public boolean createAirDemo(ServerPlayer player, boolean vacuum) {
		return debugFixtures.createAirDemo(player, vacuum);
	}

	public CaptureResult captureNearbyWater(ServerPlayer player, boolean takeover) {
		return debugFixtures.captureNearbyWater(player, takeover);
	}

	/** Restores every journaled vanilla-water block; the journal is cleared only after the world pass. */
	public RestorationStats restoreTakeover(MinecraftServer server) {
		return takeover.restore(server);
	}

	/** The last failure reported by a debug fixture or by an interrupted takeover transaction. */
	public String lastTakeoverFailure() {
		return debugFixtures.lastFailure();
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
		restoreAirDemoScaffolding(server);
		regionManager.clear();
		snapshots.clearAll(server);
		return new RestorationStats(restoration.journalFound(), restoration.restoredBlocks(),
				restoration.alreadyRestoredBlocks(), restoration.skippedBlocks(),
				0, 0, true, null);
	}

	public List<String> regionSummaries() {
		return regionOperations.summaries();
	}

	/** Read-only ownership reconciliation report across every controller region. */
	public String ownershipReport(MinecraftServer server) {
		StringBuilder report = new StringBuilder("worldbridge flag ")
				.append(worldBridgeFeatureEnabled ? "on" : "off")
				.append("（配置 ")
				.append(configuredWorldBridgeFeatureEnabled() ? "on" : "off")
				.append("）");
		boolean any = false;
		for (FluidRegion region : regionManager.regions()) {
			if (!region.controllerManaged()) continue;
			any = true;
			report.append("\n").append(regionOperations.ownershipSummary(server, region));
		}
		if (!any) report.append("\n没有控制器区域");
		for (String issue : ownershipIssues) report.append("\n! ").append(issue);
		return report.toString();
	}

	public SelectionResult selectRegion(String prefix) {
		return regionOperations.select(prefix);
	}

	public String checkpointSelected() {
		FluidRegion selected = activeRegion();
		if (selected == null) return "没有选中区域";
		CheckpointRegionResult result = regionApplication.checkpoint(new CheckpointRegionCommand(
				selected.id(), selected.checkpointGeneration(), hasPendingContainerTransfer(selected.id())));
		return switch (result.status()) {
			case COMMITTED -> "checkpoint " + result.checkpointGeneration() + " 已原子发布";
			case ALREADY_UNLOADED -> "区域已是 metadata-only UNLOADED；checkpoint "
					+ result.checkpointGeneration();
			case REGION_NOT_FOUND -> "没有选中区域";
			case NOT_PERSISTENT -> "开发命令区域不写入 region-state";
			case STALE_GENERATION -> "checkpoint generation 已改变；拒绝过期命令，当前为 "
					+ result.checkpointGeneration();
			case PENDING_CONTAINER_WAL -> "区域存在未完成或不可识别的容器转移 WAL；拒绝发布额外 checkpoint";
			case OWNERSHIP_BLOCKED, PERSISTENCE_FAILED -> result.diagnostic();
		};
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

	/** Explicit HEAD rollback is available only after a failed restore and never crosses an item WAL. */
	public String recoverSelectedPreviousCheckpoint(long expectedCurrentGeneration) {
		FluidRegion selected = activeRegion();
		if (selected == null) return "没有选中区域";
		if (hasPendingContainerTransfer(selected.id())) {
			return "上一代恢复失败：区域存在未完成或不可识别的容器转移 WAL；禁止跨代回退";
		}
		return regionOperations.recoverPreviousSelected(expectedCurrentGeneration);
	}


	void checkpointForShutdown() {
		regionOperations.checkpointForShutdown(region -> !hasPendingContainerTransfer(region.id()));
	}

	public String status(MinecraftServer server) {
		String takeoverLock = MinecraftTakeoverAdapter.lockDescription(server);
		RegionCatalog.BudgetUsage usage = regionManager.usage();
		FluidRegion region = activeRegion();
		if (region == null) {
			return "没有活动流体域 | regions 0/" + regionManager.limits().maximumRegions()
					+ " | controller flag " + (controllerFeatureEnabled ? "on" : "off")
					+ ", bridge " + (worldBridgeFeatureEnabled ? "on" : "off")
					+ " | takeover lock " + takeoverLock
					+ (regionOperations.issues().isEmpty() ? "" : " | persistence issues " + regionOperations.issues().size())
					+ (ownershipIssues.isEmpty() ? "" : " | ownership issues " + ownershipIssues.size())
					+ (debugFixtures.lastFailure() == null ? "" : " | " + debugFixtures.lastFailure());
		}
		RegionCatalog.BudgetUsage selectedUsage = regionManager.usage();
		if (!region.solverLoaded()) {
			return String.format(
					"UNLOADED/PAUSED | selected %s | regions %d/%d, reserved %d/%d cells, %d/%d markers | controller %s | %s | origin %s | grid %dx%dx%d | checkpoint %d, water %.3f cells, markers %d | controller flag %s, bridge %s | takeover lock %s%s",
					region.id().toString().substring(0, 8), selectedUsage.regionCount(), regionManager.limits().maximumRegions(),
					selectedUsage.reservedCells(), regionManager.limits().maximumTotalCells(), selectedUsage.reservedMarkers(),
					regionManager.limits().maximumTotalMarkers(), region.footprint().controllerPosition().toShortString(),
					region.availabilityFailure() == null ? "awaiting explicit run" : "blocked: " + region.availabilityFailure(),
					region.origin().toShortString(), region.sizeX(), region.sizeY(), region.sizeZ(), region.checkpointGeneration(),
					region.checkpointVolume(), region.checkpointMarkerCount(), controllerFeatureEnabled ? "on" : "off",
					worldBridgeFeatureEnabled ? "on" : "off", takeoverLock,
					regionOperations.issues().isEmpty() ? "" : " | persistence issues " + regionOperations.issues().size());
		}
		FluidDomain.StepStats lastStats = region.lastStats();
		String stats = lastStats == null
				? "尚未步进"
				: String.format("%.2f ms, substeps %d, PCG %d, residual %.4g%s",
						region.lastStepMilliseconds(), lastStats.substeps(), lastStats.pressureIterations(), lastStats.pressureResidual(),
						lastStats.reseededMerges() == 0 && lastStats.reseededSplits() == 0
								&& lastStats.reseedBudgetSkipped() == 0 && lastStats.reseedGeometrySkipped() == 0
								? ""
								: String.format(", reseed split %d/merge %d, skipped budget %d/geometry %d, ledger %.3g",
										lastStats.reseededSplits(), lastStats.reseededMerges(),
										lastStats.reseedBudgetSkipped(), lastStats.reseedGeometrySkipped(),
										lastStats.reseedLedgerError()));
		FluidDomain domain = region.domain();
		BlockPos origin = region.origin();
		float cellVolumes = domain.totalVolume();
		FluidDomain.VolumeDistribution distribution = domain.volumeDistribution(8.0F / 255.0F);
		FluidDomain.SolidDistribution solids = domain.solidDistribution();
		FluidRegion.CollisionStats collision = region.lastCollisionStats();
		FluidRegion.EntityCouplingStats entityCoupling = region.lastEntityCoupling();
		FluidRegionFootprint footprint = region.footprint();
		String regionSource = footprint == null
				? "command"
				: "controller " + footprint.controllerPosition().toShortString() + ", boundary "
						+ footprint.boundaryBlocks().size() + ", chunks " + footprint.requiredChunks().size();
		String availability = region.availabilityDescription();
		String gas = gasSummary(domain);
		String thermal = worldRegistries.hasCapability(WorldCapability.THERMAL) ? thermalSummary(region, domain) : "";
		return String.format(
				"%s%s | selected %s | regions %d/%d, reserved %d/%d cells, %d/%d markers | %s | %s | origin %s | grid %dx%dx%d | sim %.1f Hz | water %.2f blocks (%.2f cells) | particles %d/%d in %d cells | visible %d, dense %d | takeover %d, lock %s | bridge %s | entities %s/%d, liquid impulse [%.3g,%.3g,%.3g] kg·m/s | maxP %.1f | collision partial/full %d/%d, last Δ%d, escaped %.3f%s | %s%s",
				region.lifecycle().state(), region.paused() ? "/PAUSED" : "",
				region.id().toString().substring(0, 8),
				usage.regionCount(), regionManager.limits().maximumRegions(),
				usage.reservedCells(), regionManager.limits().maximumTotalCells(),
				usage.reservedMarkers(), regionManager.limits().maximumTotalMarkers(),
				regionSource, availability,
				origin.toShortString(), domain.sizeX(), domain.sizeY(), domain.sizeZ(),
				20.0 / region.stepIntervalTicks(),
				cellVolumes / FluidRegionFactory.CELL_VOLUMES_PER_BLOCK, cellVolumes,
				domain.particleCount(), domain.maximumParticleCount(), domain.markerCellCount(),
				distribution.visibleCells(), distribution.denseCells(), takeover.pendingBlockCount(), takeoverLock,
				worldBridgeFeatureEnabled ? "on" : "off",
				region.entityPhysicsEnabled() ? "on" : "off", region.lastAffectedEntities(),
				entityCoupling.impulseXKilogramMetresPerSecond(),
				entityCoupling.impulseYKilogramMetresPerSecond(),
				entityCoupling.impulseZKilogramMetresPerSecond(),
				domain.maximumPressure(),
				solids.partialCells(), solids.fullCells(),
				collision.changedCells(), collision.escapedCellVolume() / FluidRegionFactory.CELL_VOLUMES_PER_BLOCK,
				collision.markerBudgetLimited() ? " (marker budget)" : "", stats,
				(gas.isEmpty() ? "" : " | " + gas) + (thermal.isEmpty() ? "" : " | " + thermal)
		);
	}

	private String thermalSummary(FluidRegion region, FluidDomain domain) {
		if (!domain.hasThermalState()) return "thermal missing";
		ThermalAuditLedger audit = domain.thermalAudit();
		ThermalActivity activity = region.thermalActivity();
		short[] temperatureQ = domain.copyThermalDisplaySnapshot().cellTemperatureQ();
		int minimumQ = Integer.MAX_VALUE, maximumQ = Integer.MIN_VALUE;
		for (short raw : temperatureQ) {
			int value = Short.toUnsignedInt(raw);
			if (value == ThermalDisplaySnapshot.EMPTY_TEMPERATURE_Q) continue;
			minimumQ = Math.min(minimumQ, value);
			maximumQ = Math.max(maximumQ, value);
		}
		String temperature = minimumQ == Integer.MAX_VALUE ? "empty"
				: minimumQ == maximumQ ? String.format("%.2f K", minimumQ / 16.0)
				: String.format("%.2f..%.2f K", minimumQ / 16.0, maximumQ / 16.0);
		FluidDomain.ThermalPhaseStats condensed = domain.lastThermalPhaseStats();
		FluidDomain.GasThermalPhaseStats gas = domain.lastGasThermalPhaseStats();
		int phaseBudget = serverConfig.maximumPhaseOperationsPerStep();
		String phase = condensed.operations() == 0 && gas.operations() == 0 ? "idle"
				: String.format("solid=%d gas=%d%s (boil %d/condense %d cells)",
						condensed.operations(), gas.operations(),
						condensed.operations() + gas.operations() == phaseBudget ? "/" + phaseBudget + " budget-hit" : "",
						gas.boiledCells(), gas.condensedCells());
		return String.format("thermal %s T=%s U=%.6g EU source=%.6g sink=%.6g work=%.6g device=%.6g coherent=%d SVQ phase=%s pending-work=%s audit=%s",
				activity == null ? "unbound" : activity.state(), temperature, domain.totalInternalEnergy(),
				audit.externalSource().toDouble(), audit.externalSink().toDouble(),
				audit.mechanicalWork().toDouble(), audit.finiteDeviceExchange().toDouble(),
				domain.totalCoherentSolidVolumeSvq(), phase,
				domain.hasPendingPhaseVolumeWork() ? "yes" : "no",
				domain.exactInternalEnergy().equals(audit.expectedInventory()) ? "closed" : "FAILED");
	}

	/** Read-only gas-network summary for status output; empty when no sealed cavity exists. */
	private static String gasSummary(FluidDomain domain) {
		GasPressureBoundary network = domain.gasBoundary();
		List<Integer> ids = network.nodeIds();
		if (ids.isEmpty()) return "";
		StringBuilder builder = new StringBuilder("gas ");
		for (int i = 0; i < ids.size() && i < 4; i++) {
			int id = ids.get(i);
			if (i > 0) builder.append("; ");
			builder.append(String.format("node %d p=%+.0f Pa T=%.2f K V0=%.2f m³",
					id, domain.gasNodeGaugePressurePa(id), domain.gasNodeTemperatureK(id),
					network.standardVolume(id)));
			var vapor = network.inventory(id).component(BuiltinGasDefinitions.WATER_VAPOR);
			if (vapor != null) builder.append(String.format(" vapor=%.6g kg/%.6g m³std",
					vapor.mass(), AirNetwork.cubicMeters(vapor.standardVolumeNanos())));
		}
		if (ids.size() > 4) builder.append("; +").append(ids.size() - 4).append(" more");
		long airSource = componentNanos(network.externalSource(), BuiltinGasDefinitions.AIR);
		long airSink = componentNanos(network.escapedSink(), BuiltinGasDefinitions.AIR);
		long vaporSource = componentNanos(network.externalSource(), BuiltinGasDefinitions.WATER_VAPOR);
		long vaporSink = componentNanos(network.escapedSink(), BuiltinGasDefinitions.WATER_VAPOR);
		builder.append(String.format(", turnover air=%.2f/%.2f vapor=%.2f/%.2f m³std (source/sink)",
				AirNetwork.cubicMeters(airSource), AirNetwork.cubicMeters(airSink),
				AirNetwork.cubicMeters(vaporSource), AirNetwork.cubicMeters(vaporSink)));
		return builder.toString();
	}

	private static long componentNanos(GasAuditLedger ledger, String componentId) {
		for (GasAuditLedger.Entry entry : ledger.entries()) {
			if (entry.componentId().equals(componentId)) return entry.standardVolumeNanos();
		}
		return 0L;
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

	/** Administrator-only command payload; caller is responsible for enforcing permission and transcript logging. */
	public String chemistryInventoryStatus(ServerLevel level) {
		if (!worldRegistries.hasCapability(WorldCapability.CHEMISTRY)) return "化学功能门本次启动未开启";
		FluidRegion region = selectedRegionIn(level);
		if (region == null || !region.solverLoaded() || !region.domain().hasChemistryState()) {
			return "当前维度没有已加载且选中的化学区域";
		}
		long[] totals = region.domain().chemicalParcels().speciesTotals(region.domain().particleCount());
		StringBuilder text = new StringBuilder("region ").append(region.id())
				.append(" generation ").append(region.checkpointGeneration())
				.append(" fingerprint ").append(worldRegistries.chemicalRegistry().fingerprint())
				.append(" totalQuanta ").append(region.domain().chemicalParcels().totalQuanta())
				.append(" residualBits 0x").append(Long.toUnsignedString(
						region.domain().exportChemicalParticleState().residualLedgerBits(), 16));
		for (int handle = 0; handle < totals.length; handle++) if (totals[handle] != 0L) {
			text.append('\n').append(worldRegistries.chemicalRegistry().species(handle).id())
					.append('=').append(totals[handle]);
		}
		text.append("\nexternalSourceQuanta=").append(region.domain().chemicalParcels().externalSource().totalQuanta())
				.append(" escapedSinkQuanta=").append(region.domain().chemicalParcels().escapedSink().totalQuanta());
		return text.toString();
	}

	/** Administrator-only exact granular inventory/audit report; display snapshots never feed this path. */
	public String granularInventoryStatus(ServerLevel level) {
		if (!worldRegistries.hasCapability(WorldCapability.GRANULAR)) return "颗粒功能门本次启动未开启";
		FluidRegion region = selectedRegionIn(level);
		if (region == null || !region.solverLoaded() || !region.domain().hasGranularState()) {
			return "当前维度没有已加载且选中的颗粒区域";
		}
		var snapshot = region.domain().exportGranularState();
		long[] materialSvq = new long[snapshot.materialPalette().size()];
		double[] materialMass = new double[materialSvq.length];
		for (var parcel : snapshot.activeParcels()) {
			materialSvq[parcel.materialPaletteIndex()] = Math.addExact(
					materialSvq[parcel.materialPaletteIndex()], parcel.solidVolumeSvq());
			materialMass[parcel.materialPaletteIndex()] += parcel.mass();
		}
		for (var deposit : snapshot.depositGroups()) {
			materialSvq[deposit.materialPaletteIndex()] = Math.addExact(
					materialSvq[deposit.materialPaletteIndex()], deposit.solidVolumeSvq());
			materialMass[deposit.materialPaletteIndex()] += deposit.mass();
		}
		long sourceSvq = 0L, sinkSvq = 0L;
		for (var entry : snapshot.externalSource().entries()) sourceSvq = Math.addExact(sourceSvq, entry.solidVolumeSvq());
		for (var entry : snapshot.escapedSink().entries()) sinkSvq = Math.addExact(sinkSvq, entry.solidVolumeSvq());
		StringBuilder text = new StringBuilder("region ").append(region.id())
				.append(" generation ").append(region.checkpointGeneration())
				.append(" fingerprint ").append(snapshot.registryFingerprint())
				.append(" totalSvq ").append(snapshot.totalSolidVolumeSvq())
				.append(" totalMass ").append(snapshot.totalMass())
				.append(" active ").append(snapshot.activeParcels().size())
				.append(" depositGroups ").append(snapshot.depositGroups().size())
				.append(" sourceSvq ").append(sourceSvq).append(" sinkSvq ").append(sinkSvq)
				.append(" boundaryImpulse=[")
				.append(region.domain().lastGranularBoundaryImpulseX()).append(',')
				.append(region.domain().lastGranularBoundaryImpulseY()).append(',')
				.append(region.domain().lastGranularBoundaryImpulseZ()).append(']');
		for (int material = 0; material < materialSvq.length; material++) if (materialSvq[material] > 0L) {
			text.append('\n').append(snapshot.materialPalette().get(material)).append(" svq=")
					.append(materialSvq[material]).append(" mass=").append(materialMass[material]);
		}
		for (var species : snapshot.solidSpeciesTotals().entrySet()) {
			text.append('\n').append("solidSpecies ").append(species.getKey()).append('=').append(species.getValue());
		}
		return text.toString();
	}

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

	public record SelectionResult(boolean succeeded, UUID regionId, String failure) {
		static SelectionResult failed(String failure) { return new SelectionResult(false, null, failure); }
	}

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
