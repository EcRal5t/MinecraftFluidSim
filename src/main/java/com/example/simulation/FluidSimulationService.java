package com.example.simulation;

import com.example.RealWaterMod;
import com.example.block.RealWaterBlocks;
import com.example.chemistry.ChemicalDataPackLoader;
import com.example.chemistry.ChemicalRegistry;
import com.example.chemistry.ChemicalRegistryStore;
import com.example.chemistry.ChemicalReactionEngine;
import com.example.fluid.BuiltinFluidMaterials;
import com.example.fluid.FlipFluidDomain;
import com.example.fluid.FlipMaterialParticleState;
import com.example.fluid.FlipParticleState;
import com.example.fluid.FluidMaterial;
import com.example.fluid.FluidMaterialRegistry;
import com.example.fluid.FluidMaterialRenderSnapshot;
import com.example.fluid.FluidSolverConfig;
import com.example.fluid.air.AirNetwork;
import com.example.network.FluidMaterialSnapshotPayload;
import com.example.network.FluidChemicalSnapshotPayload;
import com.example.network.FluidSnapshotPayload;
import com.example.network.FluidProtocolServer;
import com.example.simulation.controller.FluidBoundaryScanner;
import com.example.simulation.controller.FluidControllerRegions;
import com.example.simulation.controller.FluidRegionFootprint;
import com.example.simulation.persistence.RegionStateFiles;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

/** Adapts fluid regions to Minecraft while delegating region-state lifecycle and takeover transactions. */
public final class FluidSimulationService {
	public static final int BLOCK_SIZE_X = 12;
	public static final int BLOCK_SIZE_Y = 8;
	public static final int BLOCK_SIZE_Z = 12;
	public static final int CELLS_PER_BLOCK = 2;
	static final int CELL_VOLUMES_PER_BLOCK = CELLS_PER_BLOCK * CELLS_PER_BLOCK * CELLS_PER_BLOCK;
	private static final int SNAPSHOT_INTERVAL_TICKS = 4;
	private static final int COLLISION_REFRESH_INTERVAL_TICKS = 5;
	private static final double STEP_BUDGET_MILLIS = 40.0;
	private static final double HARD_PAUSE_MILLIS = 200.0;
	private static final int HARD_PAUSE_STREAK = 3;
	private static final int MAX_STEP_INTERVAL_TICKS = 4;
	private static final int RECOVERY_STEPS = 8;
	private static final int HYDROSTATIC_QUIET_STEPS = 200;
	private static final float HYDROSTATIC_MAX_MARKER_SPEED = 2.5E-3F;
	private static final double AIM_DISTANCE = 16.0;
	private static final long COMPATIBILITY_REGION_CELLS = (long) BLOCK_SIZE_X * BLOCK_SIZE_Y * BLOCK_SIZE_Z
			* CELLS_PER_BLOCK * CELLS_PER_BLOCK * CELLS_PER_BLOCK;
	private static final long COMPATIBILITY_REGION_MARKERS = COMPATIBILITY_REGION_CELLS * 8L;
	private static final Map<MinecraftServer, FluidSimulationService> SERVICES = new WeakHashMap<>();

	private final Map<BlockPos, BlockState> takeoverOriginalBlocks = new HashMap<>();
	private final Path worldRoot;
	private final boolean serverConfigLocked;
	private FluidServerConfig serverConfig;
	private final FluidRegionManager regionManager;
	private final FluidRegionOperations regionOperations;
	private final ContainerTransferStore containerTransfers;
	/** Transactions which changed the live solver but have not yet proved a committed region HEAD. */
	private final Set<UUID> runtimeDirtyContainerTransfers = new HashSet<>();
	/** Prevents an unresolved WAL from emitting the same ERROR every server tick. */
	private final Map<UUID, String> reportedContainerTransferLocks = new HashMap<>();
	private Set<String> reportedContainerTransferStoreIssues = Set.of();
	private ContainerTransferFaultPoint containerTransferFault = ContainerTransferFaultPoint.NONE;
	private ResourceKey<Level> takeoverDimension;
	private boolean defaultEntityPhysicsEnabled = true;
	private boolean defaultDebugOverlays;
	private boolean controllerFeatureEnabled;
	private final boolean materialFeatureEnabled;
	private final boolean worldBridgeFeatureEnabled;
	private final boolean chemistryFeatureEnabled;
	private final ChemicalRegistry chemicalRegistry;
	private List<String> ownershipIssues = List.of();
	private String lastTakeoverFailure;
	private boolean takeoverMaintenanceEnabled;

	private FluidSimulationService(MinecraftServer server) {
		worldRoot = server.getWorldPath(LevelResource.ROOT);
		boolean configurationLocked = false;
		try {
			serverConfig = FluidServerConfig.loadOrCreate(worldRoot);
		} catch (IOException exception) {
			serverConfig = FluidServerConfig.defaults();
			configurationLocked = true;
			RealWaterMod.LOGGER.error("Could not load Real Water world config; controllers remain disabled", exception);
		}
		ChemicalRegistry bootChemicalRegistry = null;
		if (serverConfig.chemistryEnabled()) {
			try {
				ChemicalRegistryStore registryStore = new ChemicalRegistryStore(worldRoot);
				if (!registryStore.exists()) {
					RegionStateFiles.Discovery regions = new RegionStateFiles(worldRoot).discover();
					if (!regions.issues().isEmpty() || regions.manifests().stream()
							.anyMatch(manifest -> manifest.formatVersion() == 3)) {
						throw new IOException("region-state v3 or unreadable region data exists but chemistry/registry.nbt is missing");
					}
				}
				ChemicalRegistry datapackDefinitions = ChemicalDataPackLoader.load(server.getResourceManager());
				ChemicalRegistryStore.LoadResult loaded = registryStore.loadOrCreate(datapackDefinitions);
				bootChemicalRegistry = loaded.registry();
				if (loaded.definitionMismatch()) {
					RealWaterMod.LOGGER.warn("Chemical datapack definitions differ from the authoritative world registry {}; existing inventory keeps world semantics",
							loaded.registry().fingerprint());
				} else if (loaded.created()) {
					RealWaterMod.LOGGER.info("Published authoritative chemical registry snapshot {}", loaded.registry().fingerprint());
				}
			} catch (IOException | RuntimeException exception) {
				configurationLocked = true;
				RealWaterMod.LOGGER.error("Chemical registry is LOCKED; chemistry and controller creation remain disabled", exception);
			}
		}
		serverConfigLocked = configurationLocked;
		controllerFeatureEnabled = serverConfig.controllersEnabled();
		materialFeatureEnabled = serverConfig.materialsEnabled();
		worldBridgeFeatureEnabled = serverConfig.worldBridgeEnabled();
		chemicalRegistry = bootChemicalRegistry;
		chemistryFeatureEnabled = serverConfig.chemistryEnabled() && chemicalRegistry != null;
		regionManager = new FluidRegionManager(serverConfig.limits());
		regionOperations = new FluidRegionOperations(worldRoot, regionManager, configurationLocked, materialFeatureEnabled,
				chemistryFeatureEnabled, chemicalRegistry);
		containerTransfers = new ContainerTransferStore(worldRoot);
		if (configurationLocked) regionOperations.recordIssue(
				"server.properties 不可用，已安全使用默认预算并关闭控制器；原文件未覆盖");
		ownershipIssues = regionOperations.scanOwnershipIssues();
		for (String issue : ownershipIssues) RealWaterMod.LOGGER.error("Real Water world ownership issue: {}", issue);
	}

	ContainerTransferStore containerTransfers() {
		return containerTransfers;
	}

	long committedRegionGeneration(UUID regionId) throws IOException {
		return regionOperations.committedGeneration(regionId);
	}

	long committedRegionManifestChecksum(UUID regionId) throws IOException {
		return regionOperations.committedManifestChecksum(regionId);
	}

	FluidRegionPersistence.StagedCheckpoint stageControllerRegionCheckpoint(FluidRegion region, long target) throws IOException {
		return regionOperations.stageCheckpoint(region, target);
	}

	long commitControllerRegionCheckpoint(FluidRegion region, FluidRegionPersistence.StagedCheckpoint staged) throws IOException {
		return regionOperations.commitStagedCheckpoint(region, staged);
	}

	void abortControllerRegionCheckpoint(FluidRegionPersistence.StagedCheckpoint staged) throws IOException {
		regionOperations.abortStagedCheckpoint(staged);
	}

	long checkpointControllerRegion(FluidRegion region) throws IOException {
		return regionOperations.checkpoint(region);
	}

	public enum ContainerTransferFaultPoint {
		NONE,
		BEFORE_PREPARE,
		AFTER_PREPARE,
		AFTER_SOLVER,
		AFTER_STAGE,
		AFTER_WAL,
		AFTER_CHECKPOINT
	}

	public void setContainerTransferFault(ContainerTransferFaultPoint point) {
		containerTransferFault = point == null ? ContainerTransferFaultPoint.NONE : point;
	}

	public ContainerTransferFaultPoint containerTransferFault() {
		return containerTransferFault;
	}

	void injectContainerTransferFault(ContainerTransferFaultPoint point) {
		if (containerTransferFault == point) {
			throw new RuntimeException("Injected container transfer fault: " + point);
		}
	}


	boolean hasPendingContainerTransfer(UUID regionId) {
		try {
			ContainerTransferStore.Scan scan = containerTransfers.scan();
			if (!scan.issues().isEmpty()) return true;
			for (UUID transactionId : scan.transactions()) {
				if (containerTransfers.read(transactionId).regionId().equals(regionId)) return true;
			}
			return false;
		} catch (IOException | RuntimeException listingFailure) {
			return true;
		}
	}

	boolean hasAnyContainerTransferLock() {
		try {
			return containerTransfers.scan().hasAny();
		} catch (IOException | RuntimeException scanFailure) {
			return true;
		}
	}

	boolean hasContainerTransferStoreIssues() {
		try {
			return !containerTransfers.scan().issues().isEmpty();
		} catch (IOException | RuntimeException scanFailure) {
			return true;
		}
	}

	void markContainerTransferSolverDirty(UUID transactionId) {
		runtimeDirtyContainerTransfers.add(transactionId);
	}

	void markContainerTransferSolverClean(UUID transactionId) {
		runtimeDirtyContainerTransfers.remove(transactionId);
	}

	void markContainerTransferRegionCommitted(UUID transactionId) {
		runtimeDirtyContainerTransfers.remove(transactionId);
	}

	void lockContainerTransferRegion(FluidRegion region, String failure) {
		region.setAvailabilityFailure(failure);
	}

	public void recoverContainerTransfers(MinecraftServer server) {
		try {
			ContainerTransferStore.Scan scan = containerTransfers.scan();
			if (!scan.issues().isEmpty()) {
				Set<String> currentIssues = Set.copyOf(scan.issues());
				for (String issue : scan.issues()) {
					if (!reportedContainerTransferStoreIssues.contains(issue)) {
						RealWaterMod.LOGGER.error("Container-transfer recovery store is globally LOCKED: {}", issue);
					}
				}
				reportedContainerTransferStoreIssues = currentIssues;
				return;
			}
			reportedContainerTransferStoreIssues = Set.of();
			reportedContainerTransferLocks.keySet().retainAll(new HashSet<>(scan.transactions()));
			for (UUID transactionId : scan.transactions()) {
				processContainerTransfer(server, transactionId);
			}
		} catch (IOException exception) {
			RealWaterMod.LOGGER.error("Could not scan container transfer WALs", exception);
		}
	}

	void cleanupContainerTransfers(MinecraftServer server) {
		recoverContainerTransfers(server);
	}

	private void processContainerTransfer(MinecraftServer server, UUID transactionId) {
		try {
			ContainerTransferJournal journal = containerTransfers.read(transactionId);
			long head = committedRegionGeneration(journal.regionId());
			long headChecksum = committedRegionManifestChecksum(journal.regionId());
			ContainerTransferRecovery.Decision decision = ContainerTransferRecovery.decide(
					head, headChecksum, journal, runtimeDirtyContainerTransfers.contains(transactionId));
			switch (decision) {
				case LOCK_LIVE_SOLVER_DIRTY -> {
					logContainerTransferLockOnce(transactionId,
							"Container transfer WAL " + transactionId + " remains locked: its live solver changed but region "
									+ journal.regionId() + " HEAD is still " + head
									+ "; restart is required to restore the base checkpoint");
				}
				case DISCARD_UNCOMMITTED -> {
					containerTransfers.delete(transactionId);
					reportedContainerTransferLocks.remove(transactionId);
					RealWaterMod.LOGGER.warn("Discarded uncommitted container transfer WAL {} for region {}", transactionId, journal.regionId());
				}
				case CONVERGE_AFTER_ITEM -> {
				if (applyAfterItem(server, journal)) {
					persistAfterItem(server, journal);
						adoptRecoveredContainerCheckpoint(journal);
						containerTransfers.delete(transactionId);
						reportedContainerTransferLocks.remove(transactionId);
						RealWaterMod.LOGGER.warn("Converged container transfer WAL {} to a durably saved after item", transactionId);
					}
				}
				case LOCK_TARGET_WITHOUT_AFTER_ITEM -> logContainerTransferLockOnce(transactionId,
						"Container transfer WAL " + transactionId + " is locked: region " + journal.regionId()
								+ " HEAD committed target " + head + ", but WAL phase " + journal.phase()
								+ " contains no authoritative after-item");
				case LOCK_CHECKSUM_MISMATCH -> logContainerTransferLockOnce(transactionId,
						"Container transfer WAL " + transactionId + " is locked: target generation checksum "
								+ journal.targetManifestChecksum() + " does not match HEAD " + headChecksum);
				case LOCK_GENERATION_MISMATCH -> logContainerTransferLockOnce(transactionId,
						"Container transfer WAL " + transactionId + " is locked: region " + journal.regionId()
								+ " HEAD " + head + " is neither base " + journal.baseGeneration()
								+ " nor target " + journal.targetGeneration());
			}
		} catch (IOException | RuntimeException exception) {
			RealWaterMod.LOGGER.error("Could not recover container transfer WAL {}", transactionId, exception);
		}
	}

	private void logContainerTransferLockOnce(UUID transactionId, String message) {
		if (!message.equals(reportedContainerTransferLocks.put(transactionId, message))) {
			RealWaterMod.LOGGER.error(message);
		}
	}

	private void adoptRecoveredContainerCheckpoint(ContainerTransferJournal journal) throws IOException {
		for (FluidRegion region : regionManager.regions()) {
			if (!region.id().equals(journal.regionId()) || !region.solverLoaded()) continue;
			if (region.checkpointGeneration() == journal.targetGeneration()) return;
			if (region.checkpointGeneration() != journal.baseGeneration()
					|| !region.recordPublishedCheckpoint(journal.targetGeneration())) {
				throw new IOException("Live region did not adopt recovered container checkpoint "
						+ journal.targetGeneration());
			}
			return;
		}
	}

	/** The WAL is deleted only after the actor's owning save file has been forced through Minecraft. */
	private void persistAfterItem(MinecraftServer server, ContainerTransferJournal journal) throws IOException {
		switch (journal.actor().type()) {
			case PLAYER_HAND -> server.getPlayerList().saveAll();
			case DISPENSER -> {
				Identifier dimensionId = Identifier.tryParse(journal.actor().actorDimensionId());
				if (dimensionId == null) throw new IOException("Container transfer actor dimension is invalid");
				ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimensionId));
				if (level == null) throw new IOException("Container transfer actor dimension is not loaded");
				level.save(null, false, false);
			}
		}
	}

	private boolean applyAfterItem(MinecraftServer server, ContainerTransferJournal journal) {
		ContainerTransferJournal.Actor actor = journal.actor();
		return switch (actor.type()) {
			case PLAYER_HAND -> applyAfterItemToPlayer(server, actor, journal);
			case DISPENSER -> applyAfterItemToDispenser(server, actor, journal);
		};
	}

	private boolean applyAfterItemToPlayer(
			MinecraftServer server,
			ContainerTransferJournal.Actor actor,
			ContainerTransferJournal journal
	) {
		ServerPlayer player = server.getPlayerList().getPlayer(actor.playerId());
		if (player == null) return false;
		InteractionHand hand = "OFF_HAND".equals(actor.hand()) ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
		ItemStack current = player.getItemInHand(hand);
		if (!fingerprintMatches(current, journal.beforeItem()) && !fingerprintMatches(current, journal.afterItem())) return false;
		player.setItemInHand(hand, journal.afterItem().toStack());
		player.getInventory().setChanged();
		return true;
	}

	private boolean applyAfterItemToDispenser(
			MinecraftServer server,
			ContainerTransferJournal.Actor actor,
			ContainerTransferJournal journal
	) {
		Identifier dimensionId = Identifier.tryParse(actor.actorDimensionId());
		if (dimensionId == null) return false;
		ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimensionId));
		if (level == null || !(level.getBlockEntity(actor.dispenserPos()) instanceof DispenserBlockEntity dispenser)) return false;
		ItemStack current = dispenser.getItem(actor.dispenserSlot());
		if (!fingerprintMatches(current, journal.beforeItem()) && !fingerprintMatches(current, journal.afterItem())) return false;
		dispenser.setItem(actor.dispenserSlot(), journal.afterItem().toStack());
		dispenser.setChanged();
		return true;
	}

	private static boolean fingerprintMatches(ItemStack stack, ContainerTransferJournal.ItemFingerprint fingerprint) {
		try {
			return ContainerTransferJournal.ItemFingerprint.of(stack).equals(fingerprint);
		} catch (IllegalArgumentException invalid) {
			return false;
		}
	}



	public static FluidSimulationService get(MinecraftServer server) {
		return SERVICES.computeIfAbsent(server, FluidSimulationService::new);
	}




	public static void removeServer(MinecraftServer server) {
		FluidSimulationService service = SERVICES.remove(server);
		if (service != null) {
			service.checkpointForShutdown();
			service.restoreTakeover(server);
		}
	}

	public static void recoverTakeoverBackup(MinecraftServer server) {
		FluidSimulationService service = get(server);
		for (String issue : service.regionOperations.issues()) RealWaterMod.LOGGER.error("Real Water persistent region issue: {}", issue);
		RestorationStats result = service.restoreTakeover(server);
		if (result.completed() && result.journalFound()) {
			RealWaterMod.LOGGER.warn(
					"Recovered unfinished Real Water takeover: restored {} blocks, already restored {}, skipped {} occupied positions",
					result.restoredBlocks(), result.alreadyRestoredBlocks(), result.skippedBlocks()
			);
		}
	}

	/** Boot-time ownership recovery; refreshes the issue list so resolved WALs stop locking creation. */
	public static void recoverWorldOwnership(MinecraftServer server) {
		FluidSimulationService service = get(server);
		List<String> failures = service.regionOperations.recoverUnfinishedOwnership(server);
		List<String> refreshed = new java.util.ArrayList<>(service.regionOperations.scanOwnershipIssues());
		for (String failure : failures) {
			refreshed.add(failure);
			RealWaterMod.LOGGER.error("Real Water world ownership recovery failed: {}", failure);
		}
		service.ownershipIssues = refreshed;
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

	/** Returns null on success; the world-local flag is durably replaced before runtime state changes. */
	public String setControllerFeatureEnabled(boolean enabled) {
		if (serverConfigLocked) return "server.properties 未通过 v1 校验；原文件已保留，请修复后重启服务器";
		FluidServerConfig replacement = serverConfig.withControllersEnabled(enabled);
		try {
			replacement.save(worldRoot);
		} catch (IOException exception) {
			RealWaterMod.LOGGER.error("Could not persist Real Water controller feature flag", exception);
			return exception.getMessage();
		}
		serverConfig = replacement;
		controllerFeatureEnabled = enabled;
		if (!enabled) {
			for (FluidRegion region : regionManager.regions()) {
				if (region.controllerManaged()) region.setAvailabilityFailure("controller feature flag is disabled");
			}
		}
		return null;
	}

	public boolean materialFeatureEnabled() {
		return materialFeatureEnabled;
	}

	public boolean chemistryFeatureEnabled() {
		return chemistryFeatureEnabled;
	}

	public ChemicalRegistry chemicalRegistry() {
		if (chemicalRegistry == null) throw new IllegalStateException("Authoritative chemical registry is unavailable");
		return chemicalRegistry;
	}

	/** Live world-bridge gate; ownership gameplay binds to this boot's value like the material gate. */
	public boolean worldBridgeFeatureEnabled() {
		return worldBridgeFeatureEnabled;
	}

	public boolean configuredMaterialFeatureEnabled() {
		return serverConfig.materialsEnabled();
	}

	/** Persists the next-startup material gate; live sessions keep their negotiated protocol until restart. */
	public String setMaterialFeatureForRestart(boolean enabled) {
		if (serverConfigLocked) return "server.properties 未通过校验；原文件已保留，请修复后重启服务器";
		if (!enabled && serverConfig.chemistryEnabled()) return "请先关闭 chemistry；chemistry=true 要求 materials=true";
		FluidServerConfig replacement = serverConfig.withMaterialsEnabled(enabled);
		try {
			replacement.save(worldRoot);
		} catch (IOException exception) {
			RealWaterMod.LOGGER.error("Could not persist Real Water material feature flag", exception);
			return exception.getMessage();
		}
		serverConfig = replacement;
		return null;
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
			RealWaterMod.LOGGER.error("Could not persist Real Water world-bridge feature flag", exception);
			return exception.getMessage();
		}
		serverConfig = replacement;
		return null;
	}

	public ControllerActivation activateController(ServerPlayer player, BlockPos controllerPosition) {
		if (!controllerFeatureEnabled) return ControllerActivation.DISABLED;
		if (hasContainerTransferStoreIssues()) {
			return ControllerActivation.failed("container-transfer 恢复目录含未知或损坏内容；已全局锁住新控制器，详见日志");
		}
		ServerLevel level = player.level();
		restoreAirDemoScaffolding(level.getServer());
		if (!level.getBlockState(controllerPosition).is(RealWaterBlocks.FLUID_CONTROLLER)) {
			return ControllerActivation.failed("目标方块不再是 Real Water 控制器");
		}
		for (FluidRegion existing : regionManager.regions()) {
			FluidRegionFootprint existingFootprint = existing.footprint();
			if (existingFootprint == null || !existing.dimension().equals(level.dimension())
					|| !existingFootprint.controllerPosition().equals(controllerPosition)) continue;
			regionManager.select(existing.id());
			ensureRegionAvailable(level, existing);
			sendSnapshot(level);
			return ControllerActivation.selected(existingFootprint);
		}
		if (regionOperations.creationLocked() || !ownershipIssues.isEmpty()) {
			return ControllerActivation.failed("存在未解决或超预算的 region-state/ownership；已锁住新控制器区域，详见 /realwater regions list、/realwater ownership 与日志");
		}

		FluidBoundaryScanner.ScanResult scan = FluidControllerRegions.scan(
				level, controllerPosition, (int) Math.max(1L, Math.min(Integer.MAX_VALUE,
						regionManager.limits().maximumCellsPerRegion() / CELL_VOLUMES_PER_BLOCK)
				)
		);
		if (!scan.succeeded()) return ControllerActivation.failed(scan.failure());
		FluidRegionFootprint footprint = scan.footprint();
		long requiredCells;
		long requiredMarkers;
		try {
			requiredCells = Math.multiplyExact(footprint.denseBlockCapacity(), CELL_VOLUMES_PER_BLOCK);
			requiredMarkers = Math.multiplyExact(requiredCells, 8L);
		} catch (ArithmeticException exception) {
			return ControllerActivation.failed("控制器区域尺寸溢出资源计数");
		}
		FluidRegionManager.AdmissionResult preview = regionManager.previewAdd(requiredCells, requiredMarkers);
		if (!preview.accepted()) return ControllerActivation.failed(preview.failure());

		RestorationStats restoration = restoreTakeover(level.getServer());
		if (!restoration.completed()) return ControllerActivation.failed(restoration.failure());
		String availabilityFailure = FluidControllerRegions.availabilityFailure(level, footprint);
		if (availabilityFailure != null) return ControllerActivation.failed(availabilityFailure);

		FluidRegion candidate;
		try {
			candidate = FluidRegionFactory.createControllerRegion(level, footprint, CELLS_PER_BLOCK, requiredMarkers);
		} catch (IllegalArgumentException | ArithmeticException exception) {
			return ControllerActivation.failed("无法分配控制器区域：" + exception.getMessage());
		}
		candidate.setEntityPhysicsEnabled(defaultEntityPhysicsEnabled);
		candidate.setDebugOverlays(defaultDebugOverlays);
		FluidRegionManager.AdmissionResult admission = regionManager.addAndSelect(candidate);
		if (!admission.accepted()) return ControllerActivation.failed(admission.failure());
		refreshCollisions(level, candidate);
		FluidRegionFactory.captureVanillaWater(level, candidate);
		if (materialFeatureEnabled) bindDefaultMaterialState(candidate.domain());
		if (chemistryFeatureEnabled) candidate.domain().bindChemistry(chemicalRegistry);
		candidate.setPaused(true);
		try {
			if (worldBridgeFeatureEnabled) {
				regionOperations.acquireOwnership(level, candidate);
			} else {
				regionOperations.checkpoint(candidate);
			}
		} catch (IOException | RuntimeException exception) {
			regionManager.remove(candidate.id());
			candidate.setAvailabilityFailure("initial checkpoint failed: " + exception.getMessage());
			RealWaterMod.LOGGER.error("Controller region {} was rejected after its initial checkpoint failed", candidate.id(), exception);
			return ControllerActivation.failed("区域未创建；初始 checkpoint/ownership 事务失败：" + exception.getMessage());
		}
		sendSnapshot(level);
		return ControllerActivation.created(footprint);
	}

	/** World blocks the airdemo glass scaffold replaced, for restoration; null dimension means no scaffold. */
	private final Map<BlockPos, BlockState> airDemoScaffolding = new HashMap<>();
	private ResourceKey<Level> airDemoScaffoldingDimension;

	public boolean createDemo(ServerPlayer player) {
		return createDemo(player, false);
	}

	public boolean createMaterialDemo(ServerPlayer player) {
		if (!materialFeatureEnabled) {
			lastTakeoverFailure = "材料功能门未在本次启动启用；先保存配置并重启";
			return false;
		}

		return createDemo(player, true);
	}

	/** Creates a paused acid/base fixture without changing the mechanical demo geometry. */
	public boolean createChemistryDemo(ServerPlayer player) {
		if (!chemistryFeatureEnabled) {
			lastTakeoverFailure = "化学功能门未在本次启动启用；先启用 materials/chemistry 并重启";
			return false;
		}
		if (!createDemo(player, false)) return false;
		FlipFluidDomain domain = domain();
		float[] positionVolume = domain.exportChemicalParticleState().materials().copyPositionVolume();
		float divider = domain.sizeX() * domain.config().cellSize() / 5.0F;
		for (int marker = 0; marker < domain.particleCount(); marker++) {
			if (positionVolume[marker * 4] < divider) {
				domain.setMarkerChemical(marker, Map.of("realwater:hcl", 1_000_000L), true);
			} else {
				domain.setMarkerChemical(marker, Map.of("realwater:naoh", 1_000_000L), false);
			}
		}
		activeRegion().setPaused(true);
		sendSnapshot(player.level());
		return true;
	}

	public boolean createTensionDemo(ServerPlayer player, int angleDegrees) {
		if (!materialFeatureEnabled) {
			lastTakeoverFailure = "材料功能门未在本次启动启用；先保存配置并重启";
			return false;
		}
		if (chemistryFeatureEnabled) {
			lastTakeoverFailure = "表面张力测试材料没有 M7 chemical phase；chemistry 开启时拒绝混用测试域";
			return false;
		}
		Vec3 look = player.getLookAngle();
		BlockPos center = BlockPos.containing(player.getX() + look.x * 9.0, player.getY(), player.getZ() + look.z * 9.0);
		ServerLevel level = player.level();
		restoreAirDemoScaffolding(level.getServer());
		RestorationStats restoration = restoreTakeover(level.getServer());
		if (!restoration.completed()) return false;
		if (activeRegion() != null && activeRegion().controllerManaged()) {
			lastTakeoverFailure = "拒绝用开发测试域覆盖持久控制器区域；请先显式移除控制器区域";
			return false;
		}
		BlockPos newOrigin = center.offset(-BLOCK_SIZE_X / 2, -2, -BLOCK_SIZE_Z / 2);
		try {
			createTransientEmptyRegion(level, newOrigin);
		} catch (IllegalStateException exception) {
			lastTakeoverFailure = exception.getMessage();
			return false;
		}
		refreshCollisions(level);
		FlipFluidDomain domain = domain();
		for (int z = 0; z < domain.sizeZ(); z++) for (int x = 0; x < domain.sizeX(); x++) {
			domain.setSolid(x, 0, z, true);
		}
		restoreTensionCap(domain, angleDegrees);
		bindTensionDemoMaterial(domain, angleDegrees);
		activeRegion().setPaused(true);
		sendSnapshot(level);
		return true;
	}

	/**
	 * Creates a paused transient gas-pressure demo: a glass U-tube is scaffolded into the world
	 * (replacing only air), the lower part is filled with water, and the sealed left chamber's
	 * gas pocket is preset above or below ambient pressure while the right chamber stays open.
	 * Running it shows the gas pressure holding a sustained liquid-level difference. Pure water,
	 * no surface tension, no material gate; writes no region-state and the scaffold restores on
	 * removal, replacement or server shutdown.
	 */
		public boolean createAirDemo(ServerPlayer player, boolean vacuum) {
			Vec3 look = player.getLookAngle();
			BlockPos center = BlockPos.containing(player.getX() + look.x * 9.0, player.getY(), player.getZ() + look.z * 9.0);
			ServerLevel level = player.level();
			restoreAirDemoScaffolding(level.getServer());
			RestorationStats restoration = restoreTakeover(level.getServer());
			if (!restoration.completed()) return false;
			if (activeRegion() != null && activeRegion().controllerManaged()) {
				lastTakeoverFailure = "拒绝用开发测试域覆盖持久控制器区域；请先显式移除控制器区域";
				return false;
			}
			BlockPos newOrigin = center.offset(-BLOCK_SIZE_X / 2, -2, -BLOCK_SIZE_Z / 2);
			try {
				createTransientEmptyRegion(level, newOrigin);
			} catch (IllegalStateException exception) {
				lastTakeoverFailure = exception.getMessage();
				return false;
			}
			placeAirDemoScaffolding(level, newOrigin);
			refreshCollisions(level);
			FlipFluidDomain domain = domain();
			fillAirDemoWater(domain);
			if (materialFeatureEnabled) bindDefaultMaterialState(domain);
			if (chemistryFeatureEnabled) domain.bindChemistry(chemicalRegistry);
			domain.step();
			AirNetwork network = domain.airNetwork();
			if (network.nodeCount() < 1) {
				lastTakeoverFailure = "左侧气腔未密闭；玻璃只替换空气方块，请清理模拟区域内的其他方块后重试";
				restoreAirDemoScaffolding(level.getServer());
				FluidRegion failedRegion = activeRegion();
				if (failedRegion != null && !failedRegion.controllerManaged()) {
					regionManager.remove(failedRegion.id());
					sendRegionRemoval(level.getServer(), failedRegion);
				}
				return false;
			}
			int node = network.nodeIds().get(0);
			// pressure adds inventory (p = P0(V0/V - 1) > 0), vacuum removes it; the soft gas spring
			// settles near a third of the preset head, so presets are scaled for a visible difference
			network.setStandardVolume(node, vacuum ? network.availableVolume(node) * 0.78
					: network.availableVolume(node) / 0.70);
			activeRegion().setPaused(true);
			sendSnapshot(level);
			return true;
		}

	/** Glass U-tube layout in region-relative block coordinates: shell, sealed-left roof and a divider with a bottom doorway. */
	private static boolean isAirDemoScaffoldBlock(int bx, int by, int bz) {
		boolean shell = bx == 0 || bx == BLOCK_SIZE_X - 1 || bz == 0 || bz == BLOCK_SIZE_Z - 1 || by == 0;
		boolean roof = by == BLOCK_SIZE_Y - 1 && bx <= 6;
		boolean divider = bx == 6 && by >= 3;
		return shell || roof || divider;
	}

	private void placeAirDemoScaffolding(ServerLevel level, BlockPos origin) {
		airDemoScaffolding.clear();
		airDemoScaffoldingDimension = level.dimension();
		BlockState glass = Blocks.GLASS.defaultBlockState();
		for (int by = 0; by < BLOCK_SIZE_Y; by++) for (int bz = 0; bz < BLOCK_SIZE_Z; bz++) {
			for (int bx = 0; bx < BLOCK_SIZE_X; bx++) {
				if (!isAirDemoScaffoldBlock(bx, by, bz)) continue;
				BlockPos pos = origin.offset(bx, by, bz);
				BlockState previous = level.getBlockState(pos);
				if (!previous.isAir()) continue;
				if (level.setBlock(pos, glass, Block.UPDATE_ALL)) airDemoScaffolding.put(pos, previous);
			}
		}
	}

	/** Restores the world blocks the airdemo glass scaffold replaced; player-edited positions are left alone. */
	public void restoreAirDemoScaffolding(MinecraftServer server) {
		if (airDemoScaffoldingDimension == null) return;
		ServerLevel level = server.getLevel(airDemoScaffoldingDimension);
		if (level != null) {
			for (Map.Entry<BlockPos, BlockState> entry : airDemoScaffolding.entrySet()) {
				if (!level.getBlockState(entry.getKey()).is(Blocks.GLASS)) continue;
				level.setBlock(entry.getKey(), entry.getValue(), Block.UPDATE_ALL);
			}
		}
		airDemoScaffolding.clear();
		airDemoScaffoldingDimension = null;
	}

	/** Fills both chambers to 3.5 blocks; the two-block doorway column stays inside the fill range and submerged. */
	private static void fillAirDemoWater(FlipFluidDomain domain) {
		for (int bz = 1; bz <= BLOCK_SIZE_Z - 2; bz++) {
			for (int bx = 1; bx <= BLOCK_SIZE_X - 2; bx++) {
				for (int by = 1; by <= 3; by++) {
					float fraction = by <= 2 ? 1.0F : 0.5F;
					for (int localZ = 0; localZ < CELLS_PER_BLOCK; localZ++) {
						for (int localY = 0; localY < CELLS_PER_BLOCK; localY++) {
							for (int localX = 0; localX < CELLS_PER_BLOCK; localX++) {
								int x = bx * CELLS_PER_BLOCK + localX;
								int y = by * CELLS_PER_BLOCK + localY;
								int z = bz * CELLS_PER_BLOCK + localZ;
								if (!domain.isSolid(x, y, z)) domain.setVolume(x, y, z, fraction);
							}
						}
					}
				}
			}
		}
	}

	private static void bindTensionDemoMaterial(FlipFluidDomain domain, int angleDegrees) {
		FlipParticleState pure = domain.exportParticleState();
		float[] positionVolume = pure.copyPositionVolume();
		float[] velocities = pure.copyParticleVelocities();
		double[] markerMass = new double[pure.particleCount()];
		int[] material = new int[pure.particleCount()];
		FluidMaterial tensionWater = new FluidMaterial("realwater:tension_water", domain.config().density(),
				"realwater:tension_water", 100.0, Math.toRadians(angleDegrees));
		FluidMaterialRegistry registry = new FluidMaterialRegistry(List.of(tensionWater));
		double totalMass = 0.0;
		for (int marker = 0; marker < pure.particleCount(); marker++) {
			double volume = positionVolume[marker * 4 + 3];
			markerMass[marker] = volume * tensionWater.referenceDensity();
			totalMass += markerMass[marker];
			material[marker] = 0;
		}
		domain.restoreMaterialParticleState(new FlipMaterialParticleState(
				pure.sizeX(), pure.sizeY(), pure.sizeZ(), pure.cellSize(), pure.particleCount(),
				pure.totalVolume(), totalMass, registry.materials(), positionVolume, velocities,
				pure.copySolidMask(), markerMass, material
		));
	}

	private void createTransientEmptyRegion(ServerLevel level, BlockPos origin) {
		FluidRegion replaced = activeRegion();
		if (replaced != null && replaced.controllerManaged()) {
			throw new IllegalStateException("Refusing to replace a persistent controller region; remove it explicitly first");
		}
		FluidRegionManager.AdmissionResult preview = regionManager.previewReplacement(
				COMPATIBILITY_REGION_CELLS, COMPATIBILITY_REGION_MARKERS);
		if (!preview.accepted()) throw new IllegalStateException("Compatibility fluid region rejected: " + preview.failure());
		FluidSolverConfig config = new FluidSolverConfig(
				0.5F, 1_000.0F, 9.81F, 1.0F / 40.0F,
				300, 1.0E-5F, 0.35F, 2.0E-3F, 0.80F
		);
		FlipFluidDomain domain = new FlipFluidDomain(
				BLOCK_SIZE_X * CELLS_PER_BLOCK, BLOCK_SIZE_Y * CELLS_PER_BLOCK, BLOCK_SIZE_Z * CELLS_PER_BLOCK,
				config, Math.toIntExact(COMPATIBILITY_REGION_MARKERS)
		);
		FluidRegion candidate = new FluidRegion(
				UUID.randomUUID(), level.dimension(), origin, domain, COMPATIBILITY_REGION_MARKERS
		);
		candidate.setEntityPhysicsEnabled(defaultEntityPhysicsEnabled);
		candidate.setDebugOverlays(defaultDebugOverlays);
		FluidRegionManager.AdmissionResult admission = regionManager.replaceSelected(candidate);
		if (!admission.accepted()) throw new IllegalStateException("Compatibility fluid region rejected: " + admission.failure());
		if (replaced != null) sendRegionRemoval(level.getServer(), replaced);
	}

	private static void restoreTensionCap(FlipFluidDomain domain, int angleDegrees) {
		int sx = domain.sizeX(), sy = domain.sizeY(), sz = domain.sizeZ();
		float cellSize = domain.config().cellSize();
		double wallY = 0.5 * cellSize;
		double targetCellVolume = 220.0;
		double theta = Math.toRadians(angleDegrees);
		double cos = Math.cos(theta), sin = Math.sin(theta);
		double radiusCells = Math.cbrt(3.0 * targetCellVolume / (Math.PI * (1.0 - cos) * (1.0 - cos) * (2.0 + cos)));
		double radius = radiusCells * cellSize;
		double centerX = 0.5 * (sx - 1) * cellSize;
		double centerY = wallY - radius * cos;
		double centerZ = 0.5 * (sz - 1) * cellSize;
		int particleCount = 0;
		for (int z = 0; z < sz; z++) for (int y = 1; y < sy; y++) for (int x = 0; x < sx; x++) {
			if (sphericalCapFraction(x, y, z, cellSize, centerX, centerY, centerZ, radius, wallY) > 1.0E-6) {
				particleCount += 8;
			}
		}
		float[] positionVolume = new float[particleCount * 4];
		float[] velocities = new float[particleCount * 3];
		float totalVolume = 0.0F;
		int particle = 0;
		for (int z = 0; z < sz; z++) for (int y = 1; y < sy; y++) for (int x = 0; x < sx; x++) {
			double fraction = sphericalCapFraction(x, y, z, cellSize, centerX, centerY, centerZ, radius, wallY);
			if (fraction <= 1.0E-6) continue;
			float perMarker = (float) (fraction / 8.0);
			for (int slot = 0; slot < 8; slot++) {
				int base = particle * 4;
				positionVolume[base] = (x + 0.5F) * cellSize;
				positionVolume[base + 1] = (y + 0.5F) * cellSize;
				positionVolume[base + 2] = (z + 0.5F) * cellSize;
				positionVolume[base + 3] = perMarker;
				totalVolume += perMarker;
				particle++;
			}
		}
		domain.restoreParticleState(new FlipParticleState(
				sx, sy, sz, cellSize, particleCount, totalVolume, positionVolume, velocities, domain.copySolidMask()
		));
	}

	private static double sphericalCapFraction(int x, int y, int z, double cellSize,
	                                           double centerX, double centerY, double centerZ,
	                                           double radius, double wallY) {
		double px = x * cellSize, py = y * cellSize, pz = z * cellSize;
		int samples = 4;
		int hits = 0;
		for (int subZ = 0; subZ < samples; subZ++) for (int subY = 0; subY < samples; subY++) {
			for (int subX = 0; subX < samples; subX++) {
				double sx = px + (subX + 0.5) * cellSize / samples;
				double sy = py + (subY + 0.5) * cellSize / samples;
				double sz = pz + (subZ + 0.5) * cellSize / samples;
				double dx = sx - centerX, dy = sy - centerY, dz = sz - centerZ;
				if (sy >= wallY && dx * dx + dy * dy + dz * dz <= radius * radius) hits++;
			}
		}
		return (double) hits / (samples * samples * samples);
	}

	private boolean createDemo(ServerPlayer player, boolean layeredMaterials) {
		Vec3 look = player.getLookAngle();
		BlockPos center = BlockPos.containing(player.getX() + look.x * 9.0, player.getY(), player.getZ() + look.z * 9.0);
		ServerLevel level = player.level();
		restoreAirDemoScaffolding(level.getServer());
		RestorationStats restoration = restoreTakeover(level.getServer());
		if (!restoration.completed()) return false;
		if (activeRegion() != null && activeRegion().controllerManaged()) {
			lastTakeoverFailure = "拒绝用开发测试域覆盖持久控制器区域；请先显式移除控制器区域";
			return false;
		}
		BlockPos newOrigin = center.offset(-BLOCK_SIZE_X / 2, -2, -BLOCK_SIZE_Z / 2);
		try {
			createEmpty(level, newOrigin);
		} catch (IllegalStateException exception) {
			lastTakeoverFailure = exception.getMessage();
			return false;
		}
		refreshCollisions(level);

		FlipFluidDomain domain = domain();
		int fillX = domain.sizeX() * 2 / 5;
		int fillY = domain.sizeY() * 3 / 4;
		for (int z = 0; z < domain.sizeZ(); z++) {
			for (int y = 0; y < fillY; y++) {
				for (int x = 0; x < fillX; x++) {
					if (!domain.isSolid(x, y, z)) domain.setVolume(x, y, z, 1.0F);
				}
			}
		}
		if (materialFeatureEnabled) bindDefaultMaterialState(domain);
		if (layeredMaterials) assignLayeredDemoMaterials(domain, fillY / 2.0F * domain.config().cellSize());
		if (chemistryFeatureEnabled) domain.bindChemistry(chemicalRegistry);
		activeRegion().setPaused(true);
		sendSnapshot(level);
		return true;
	}

	public CaptureResult captureNearbyWater(ServerPlayer player, boolean takeover) {
		ServerLevel level = player.level();
		restoreAirDemoScaffolding(level.getServer());
		RestorationStats restoration = restoreTakeover(level.getServer());
		if (!restoration.completed()) {
			return new CaptureResult(0, -1, restoration.failure());
		}
		if (activeRegion() != null && activeRegion().controllerManaged()) {
			return new CaptureResult(0, -1, "拒绝用开发捕获域覆盖持久控制器区域；请先显式移除控制器区域");
		}
		BlockPos newOrigin = player.blockPosition().offset(-BLOCK_SIZE_X / 2, -2, -BLOCK_SIZE_Z / 2);
		try {
			createEmpty(level, newOrigin);
		} catch (IllegalStateException exception) {
			return new CaptureResult(0, -1, exception.getMessage());
		}
		refreshCollisions(level);
		int wetCells = FluidRegionFactory.captureVanillaWater(level, activeRegion());
		if (materialFeatureEnabled) bindDefaultMaterialState(activeRegion().domain());
		if (chemistryFeatureEnabled) activeRegion().domain().bindChemistry(chemicalRegistry);
		int removedBlocks = takeover ? beginTakeover(level) : 0;
		activeRegion().setPaused(true);
		sendSnapshot(level);
		return new CaptureResult(wetCells, removedBlocks, removedBlocks < 0 ? lastTakeoverFailure : null);
	}

	/** Temporarily replaces only standalone vanilla water blocks, never waterlogged blocks. */
	private int beginTakeover(ServerLevel level) {
		Map<BlockPos, BlockState> candidates = new HashMap<>();
		for (int blockZ = 0; blockZ < BLOCK_SIZE_Z; blockZ++) for (int blockY = 0; blockY < BLOCK_SIZE_Y; blockY++) for (int blockX = 0; blockX < BLOCK_SIZE_X; blockX++) {
			BlockPos pos = origin().offset(blockX, blockY, blockZ);
			BlockState state = level.getBlockState(pos);
			if (!state.is(Blocks.WATER)) continue;
			candidates.put(pos.immutable(), state);
		}
		if (candidates.isEmpty()) return 0;
		try {
			TakeoverBackupStore.BeginResult result = TakeoverBackupStore.begin(level.getServer(), level.dimension(), candidates);
			takeoverDimension = level.dimension();
			for (BlockPos position : result.removedPositions()) {
				takeoverOriginalBlocks.put(position, candidates.get(position));
			}
			takeoverMaintenanceEnabled = result.removedBlocks() > 0;
			lastTakeoverFailure = null;
			if (result.removedBlocks() == 0) {
				RestorationStats cleanup = restoreTakeover(level.getServer());
				if (!cleanup.completed()) return -1;
			}
			return result.removedBlocks();
		} catch (IOException | RuntimeException exception) {
			String beginFailure = "接管事务启动失败：" + exception.getMessage();
			RealWaterMod.LOGGER.error("Refusing water takeover; no recovery file will be overwritten", exception);
			RestorationStats rollback = restoreTakeover(level.getServer());
			lastTakeoverFailure = rollback.completed()
					? beginFailure + "；已安全回滚，原版水未保持接管状态"
					: beginFailure + "；自动回滚未完成，journal 已锁定新 takeover：" + rollback.failure();
			return -1;
		}
	}

	public RestorationStats restoreTakeover(MinecraftServer server) {
		// Once restoration begins, never let the tick hook remove a block that an interrupted pass already restored.
		takeoverMaintenanceEnabled = false;
		try {
			TakeoverBackupStore.RecoveryResult result = TakeoverBackupStore.recover(server);
			if (!result.found() && !takeoverOriginalBlocks.isEmpty()) {
				lastTakeoverFailure = "活动接管缺少 on-disk journal；已停止替换域，需从世界备份核对";
				RealWaterMod.LOGGER.error(lastTakeoverFailure);
				return RestorationStats.failed(false, 0, 0, 0, lastTakeoverFailure);
			}
			takeoverOriginalBlocks.clear();
			takeoverDimension = null;
			lastTakeoverFailure = null;
			return new RestorationStats(
					result.found(), result.restoredBlocks(), result.alreadyRestoredBlocks(), result.skippedBlocks(), true, null
			);
		} catch (TakeoverTransaction.RecoveryException exception) {
			if (exception.worldPassCompleted()) {
				takeoverOriginalBlocks.clear();
				takeoverDimension = null;
			}
			lastTakeoverFailure = "恢复未完成，journal 已保留并锁定新 takeover：" + exception.getMessage();
			RealWaterMod.LOGGER.error("Could not finish the Real Water takeover recovery; recovery files were preserved", exception);
			return RestorationStats.failed(
					true,
					exception.restoredBlocks(),
					exception.alreadyRestoredBlocks(),
					exception.skippedBlocks(),
					lastTakeoverFailure
			);
		} catch (IOException | RuntimeException exception) {
			lastTakeoverFailure = "恢复未完成，journal 已保留并锁定新 takeover：" + exception.getMessage();
			RealWaterMod.LOGGER.error("Could not recover the unfinished Real Water takeover; recovery files were preserved", exception);
			return RestorationStats.failed(TakeoverBackupStore.isLocked(server), 0, 0, 0, lastTakeoverFailure);
		}
	}

	private void createEmpty(ServerLevel level, BlockPos origin) {
		FluidRegion replaced = activeRegion();
		if (replaced != null && replaced.controllerManaged()) {
			throw new IllegalStateException("Refusing to replace a persistent controller region; remove it explicitly first");
		}
		FluidRegionManager.AdmissionResult preview = regionManager.previewReplacement(
				COMPATIBILITY_REGION_CELLS, COMPATIBILITY_REGION_MARKERS);
		if (!preview.accepted()) throw new IllegalStateException("Compatibility fluid region rejected: " + preview.failure());
		FluidRegion candidate = FluidRegionFactory.createCommandRegion(
				level, origin, BLOCK_SIZE_X, BLOCK_SIZE_Y, BLOCK_SIZE_Z,
				CELLS_PER_BLOCK, COMPATIBILITY_REGION_MARKERS
		);
		candidate.setEntityPhysicsEnabled(defaultEntityPhysicsEnabled);
		candidate.setDebugOverlays(defaultDebugOverlays);
		FluidRegionManager.AdmissionResult admission = regionManager.replaceSelected(candidate);
		if (!admission.accepted()) throw new IllegalStateException("Compatibility fluid region rejected: " + admission.failure());
		if (replaced != null) sendRegionRemoval(level.getServer(), replaced);
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
		for (int i = 0; i < count; i++) stepOnce(region);
		sendSnapshot(level);
		return count;
	}

	public void tick(MinecraftServer server) {
		regionOperations.advanceTick();
		cleanupContainerTransfers(server);

		if (takeoverDimension != null) {
			ServerLevel takeoverLevel = server.getLevel(takeoverDimension);
			if (takeoverLevel != null) maintainTakeover(takeoverLevel);
		}
		for (FluidRegion region : regionManager.regions()) {
			ServerLevel level = server.getLevel(region.dimension());
			if (level == null) {
				if (hasPendingContainerTransfer(region.id())) {
					region.setAvailabilityFailure("dimension is not loaded; unresolved container transfer WAL prevents unload checkpoint");
					continue;
				}
				regionOperations.unloadForFailure(server, region, "dimension is not loaded", FluidSimulationService::sendRegionRemoval);
				continue;
			}
			tickRegion(level, region);
		}
	}

	private void tickRegion(ServerLevel level, FluidRegion region) {
		if (!ensureRegionAvailable(level, region)) return;
		long tick = region.incrementTick();
		boolean collisionsChanged = false;
		if (tick % COLLISION_REFRESH_INTERVAL_TICKS == 0) {
			collisionsChanged = refreshCollisions(level, region).changedCells() > 0;
		}
		int affectedEntities = region.entityPhysicsEnabled()
				? FluidRegionWorldInteraction.applyEntityForces(level, region)
				: 0;
		region.setLastAffectedEntities(affectedEntities);
		if (collisionsChanged || affectedEntities > 0) region.wakeForActivity();
		if (!region.canStep()) {
			if (collisionsChanged) sendSnapshot(level, region);
			return;
		}
		if (tick % region.stepIntervalTicks() != 0) {
			if (collisionsChanged) sendSnapshot(level, region);
			return;
		}
		try {
			stepOnce(region);
		} catch (ChemicalReactionEngine.BudgetExceededException budget) {
			String failure = "chemistry-blocked PAUSED：" + budget.getMessage();
			region.blockChemistry(failure);
			RealWaterMod.LOGGER.error("Region {} exhausted a frozen chemistry budget; parcel commit was rejected and the region is paused",
					region.id(), budget);
			sendSnapshot(level, region);
			return;
		}
		updateStepPacing(region);
		FlipFluidDomain.StepStats stats = region.lastStats();
		float residualLimit = Math.max(1.0E-5F, region.domain().config().pressureTolerance() * 4.0F);
		boolean hydrostaticCandidate = !collisionsChanged && affectedEntities == 0
				&& region.domain().maximumParticleSpeed() <= HYDROSTATIC_MAX_MARKER_SPEED
				&& stats != null && stats.pressureResidual() <= residualLimit;
		if (region.lifecycle().observeCompletedStep(hydrostaticCandidate, HYDROSTATIC_QUIET_STEPS)) {
			float exactVolume = region.domain().totalVolume();
			int markerCount = region.domain().particleCount();
			region.domain().commitHydrostaticSleep();
			if (markerCount != region.domain().particleCount() || exactVolume != region.domain().totalVolume()) {
				throw new IllegalStateException("Hydrostatic sleep changed the exact marker ledger for region " + region.id());
			}
			RealWaterMod.LOGGER.info("Region {} entered hydrostatic sleep with {} markers and {} exact cell volume",
					region.id(), markerCount, exactVolume);
		}
		if (region.hardOverrunStreak() >= HARD_PAUSE_STREAK) {
			region.setPaused(true);
			RealWaterMod.LOGGER.warn("Auto-paused fluid region {} after {} consecutive steps above {} ms",
					region.id(), HARD_PAUSE_STREAK, HARD_PAUSE_MILLIS);
		}
		if (tick % SNAPSHOT_INTERVAL_TICKS == 0 || region.paused()
				|| region.lifecycle().state() == FluidRegionLifecycle.State.HYDROSTATIC_SLEEP) {
			sendSnapshot(level, region);
		}
	}

	private void maintainTakeover(ServerLevel level) {
		if (!takeoverMaintenanceEnabled || takeoverOriginalBlocks.isEmpty() || !level.dimension().equals(takeoverDimension)) return;
		for (BlockPos pos : takeoverOriginalBlocks.keySet()) {
			if (level.getBlockState(pos).is(Blocks.WATER)) {
				level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
			}
		}
	}

	public boolean ensureRegionAvailable(ServerLevel level, FluidRegion region) {
		if (hasPendingContainerTransfer(region.id())) {
			region.setAvailabilityFailure("存在未完成容器转移 WAL；恢复完成后才能继续");
			return false;
		}

		return regionOperations.ensureAvailable(
				level, region, controllerFeatureEnabled, this::refreshCollisions, this::sendSnapshot,
				FluidSimulationService::sendRegionRemoval
		);
	}

	private static void stepOnce(FluidRegion region) {
		long start = System.nanoTime();
		FlipFluidDomain.StepStats stats = region.domain().step();
		region.recordStep(stats, (System.nanoTime() - start) / 1_000_000.0);
	}

	private static void updateStepPacing(FluidRegion region) {
		double lastStepMilliseconds = region.lastStepMilliseconds();
		if (lastStepMilliseconds > HARD_PAUSE_MILLIS) region.setHardOverrunStreak(region.hardOverrunStreak() + 1);
		else region.setHardOverrunStreak(0);

		int requiredInterval = Mth.clamp((int) Math.ceil(lastStepMilliseconds / STEP_BUDGET_MILLIS), 1, MAX_STEP_INTERVAL_TICKS);
		if (requiredInterval > region.stepIntervalTicks()) {
			region.setStepIntervalTicks(requiredInterval);
			region.setRecoveryStreak(0);
			RealWaterMod.LOGGER.info("Reduced fluid simulation rate to approximately {} Hz after a {} ms step",
					20 / region.stepIntervalTicks(), String.format("%.1f", lastStepMilliseconds));
		} else if (requiredInterval < region.stepIntervalTicks()) {
			region.setRecoveryStreak(region.recoveryStreak() + 1);
			if (region.recoveryStreak() >= RECOVERY_STEPS) {
				region.setStepIntervalTicks(region.stepIntervalTicks() - 1);
				region.setRecoveryStreak(0);
			}
		} else {
			region.setRecoveryStreak(0);
		}
	}

	public WaterEditResult pourWater(ServerPlayer player, int buckets) {
		if (!hasRegionIn(player.level())) return WaterEditResult.NO_DOMAIN;
		FluidRegion region = activeRegion();
		if (!ensureRegionAvailable(player.level(), region)) {
			return WaterEditResult.blocked(region.availabilityFailure());
		}
		CellTarget target = findAimedCell(player, false);
		if (target == null) return WaterEditResult.NO_TARGET;
		float requested = buckets * CELL_VOLUMES_PER_BLOCK;
		FlipFluidDomain.VolumeAddition addition = domain().addVolumeNearBudgeted(
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
		CellTarget target = findAimedCell(player, true);
		if (target == null) return WaterEditResult.NO_TARGET;
		float requested = buckets * CELL_VOLUMES_PER_BLOCK;
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
		return refreshCollisions(level, region);
	}

	private FluidRegion.CollisionStats refreshCollisions(ServerLevel level, FluidRegion region) {
		FluidRegion.CollisionStats stats = FluidRegionWorldInteraction.refreshCollisions(
				level, region, CELLS_PER_BLOCK
		);
		if (stats.changedCells() > 0 || stats.displacedCellVolume() > 0.0F
				|| stats.escapedCellVolume() > 0.0F || stats.markerBudgetLimited()) {
			region.setLastCollisionStats(stats);
		}
		region.markCollisionRefreshed();
		if (stats.changedCells() > 0) region.wakeForActivity();
		return stats;
	}

	private CellTarget findAimedCell(ServerPlayer player, boolean requireFluid) {
		FlipFluidDomain domain = domain();
		Vec3 eye = player.getEyePosition();
		Vec3 look = player.getLookAngle();
		double step = domain.config().cellSize() * 0.25;
		CellTarget previousOpen = null;
		CellTarget previousSample = null;
		boolean enteredDomain = false;

		for (double distance = 0.0; distance <= AIM_DISTANCE; distance += step) {
			Vec3 point = eye.add(look.scale(distance));
			CellTarget cell = worldToCell(point);
			if (cell == null) {
				if (enteredDomain && !requireFluid) return previousOpen;
				continue;
			}
			enteredDomain = true;
			if (cell.equals(previousSample)) continue;
			previousSample = cell;
			if (requireFluid) {
				if (domain.volume(cell.x(), cell.y(), cell.z()) > domain.config().fluidEpsilon()) return cell;
			} else if (domain.isSolid(cell.x(), cell.y(), cell.z())) {
				return previousOpen;
			} else if (domain.volume(cell.x(), cell.y(), cell.z()) > domain.config().fluidEpsilon()) {
				return previousOpen == null ? cell : previousOpen;
			} else {
				previousOpen = cell;
			}
		}
		return requireFluid ? null : previousOpen;
	}

	private CellTarget worldToCell(Vec3 point) {
		FlipFluidDomain domain = domain();
		BlockPos origin = origin();
		float h = domain.config().cellSize();
		int x = Mth.floor((point.x - origin.getX()) / h);
		int y = Mth.floor((point.y - origin.getY()) / h);
		int z = Mth.floor((point.z - origin.getZ()) / h);
		if (x < 0 || x >= domain.sizeX() || y < 0 || y >= domain.sizeY() || z < 0 || z >= domain.sizeZ()) return null;
		return new CellTarget(x, y, z);
	}

	public RestorationStats remove(MinecraftServer server) {
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
		FluidRegionOperations.RegionRemoval removal = regionOperations.deletePersistentRegions(server);
		if (removal.failure() != null) return RestorationStats.failed(false, 0, 0, 0, removal.failure());
		regionManager.clear();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			FluidProtocolServer.send(player, FluidSnapshotPayload.clearAll());
			FluidProtocolServer.send(player, FluidMaterialSnapshotPayload.clearAll());
			FluidProtocolServer.send(player, FluidChemicalSnapshotPayload.clearAll());
		}
		return new RestorationStats(restoration.journalFound(), restoration.restoredBlocks(),
				restoration.alreadyRestoredBlocks(), restoration.skippedBlocks(),
				removal.releasedBlocks(), removal.releasedSkipped(), true, null);
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
		if (selected != null && hasPendingContainerTransfer(selected.id())) {
			return "区域存在未完成或不可识别的容器转移 WAL；拒绝发布额外 checkpoint";
		}
		return regionOperations.checkpointSelected();
	}

	public String emptySelectedRegion(ServerLevel level) {
		FluidRegion region = activeRegion();
		if (region == null || !level.dimension().equals(region.dimension())) return "没有活动流体域或位于其他维度";
		if (!ensureRegionAvailable(level, region)) return "控制器域当前不可用：" + region.availabilityFailure();
		FlipFluidDomain domain = region.domain();
		float beforeBlocks = domain.totalVolume() / CELL_VOLUMES_PER_BLOCK;
		int beforeParticles = domain.particleCount();
		domain.removeAllWaterExact();
		region.wakeForActivity();
		if (region.controllerManaged()) {
			try {
				long generation = regionOperations.checkpoint(region);
				sendSnapshot(level, region);
				return String.format("已抹除全部水体：%.3f 方块水（%d 个 marker），checkpoint %d 已发布",
						beforeBlocks, beforeParticles, generation);
			} catch (IOException | RuntimeException exception) {
				region.setPaused(true);
				region.setAvailabilityFailure("empty 后 checkpoint 失败，活动 solver 已清空；重启后可能恢复旧水量：" + exception.getMessage());
				return "已从活动 solver 抹除全部水体，但 checkpoint 失败；区域已暂停：" + exception.getMessage();
			}
		}
		sendSnapshot(level, region);
		return String.format("已抹除全部水体：%.3f 方块水（%d 个 marker）", beforeBlocks, beforeParticles);
	}


	private void checkpointForShutdown() {
		regionOperations.checkpointForShutdown(region -> !hasPendingContainerTransfer(region.id()));
	}

	public String status(MinecraftServer server) {
		String takeoverLock = TakeoverBackupStore.lockDescription(server);
		FluidRegionManager.BudgetUsage usage = regionManager.usage();
		FluidRegion region = activeRegion();
		if (region == null) {
			return "没有活动流体域 | regions 0/" + regionManager.limits().maximumRegions()
					+ " | controller flag " + (controllerFeatureEnabled ? "on" : "off")
					+ ", bridge " + (worldBridgeFeatureEnabled ? "on" : "off")
					+ " | takeover lock " + takeoverLock
					+ (regionOperations.issues().isEmpty() ? "" : " | persistence issues " + regionOperations.issues().size())
					+ (ownershipIssues.isEmpty() ? "" : " | ownership issues " + ownershipIssues.size())
					+ (lastTakeoverFailure == null ? "" : " | " + lastTakeoverFailure);
		}
		FluidRegionManager.BudgetUsage selectedUsage = regionManager.usage();
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
		FlipFluidDomain.StepStats lastStats = region.lastStats();
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
		FlipFluidDomain domain = region.domain();
		BlockPos origin = region.origin();
		float cellVolumes = domain.totalVolume();
		FlipFluidDomain.VolumeDistribution distribution = domain.volumeDistribution(8.0F / 255.0F);
		FlipFluidDomain.SolidDistribution solids = domain.solidDistribution();
		FluidRegion.CollisionStats collision = region.lastCollisionStats();
		FluidRegionFootprint footprint = region.footprint();
		String regionSource = footprint == null
				? "command"
				: "controller " + footprint.controllerPosition().toShortString() + ", boundary "
						+ footprint.boundaryBlocks().size() + ", chunks " + footprint.requiredChunks().size();
		String availability = region.availabilityFailure() == null ? "ready" : "blocked: " + region.availabilityFailure();
		String gas = gasSummary(domain);
		return String.format(
				"%s%s | selected %s | regions %d/%d, reserved %d/%d cells, %d/%d markers | %s | %s | origin %s | grid %dx%dx%d | sim %.1f Hz | water %.2f blocks (%.2f cells) | particles %d/%d in %d cells | visible %d, dense %d | takeover %d, lock %s | bridge %s | entities %s/%d | maxP %.1f | collision partial/full %d/%d, last Δ%d, escaped %.3f%s | %s%s",
				region.lifecycle().state(), region.paused() ? "/PAUSED" : "",
				region.id().toString().substring(0, 8),
				usage.regionCount(), regionManager.limits().maximumRegions(),
				usage.reservedCells(), regionManager.limits().maximumTotalCells(),
				usage.reservedMarkers(), regionManager.limits().maximumTotalMarkers(),
				regionSource, availability,
				origin.toShortString(), domain.sizeX(), domain.sizeY(), domain.sizeZ(),
				20.0 / region.stepIntervalTicks(),
				cellVolumes / CELL_VOLUMES_PER_BLOCK, cellVolumes,
				domain.particleCount(), domain.maximumParticleCount(), domain.markerCellCount(),
				distribution.visibleCells(), distribution.denseCells(), takeoverOriginalBlocks.size(), takeoverLock,
				worldBridgeFeatureEnabled ? "on" : "off",
				region.entityPhysicsEnabled() ? "on" : "off", region.lastAffectedEntities(),
				domain.maximumPressure(),
				solids.partialCells(), solids.fullCells(),
				collision.changedCells(), collision.escapedCellVolume() / CELL_VOLUMES_PER_BLOCK,
				collision.markerBudgetLimited() ? " (marker budget)" : "", stats,
				gas.isEmpty() ? "" : " | " + gas
		);
	}

	/** Read-only gas-network summary for status output; empty when no sealed cavity exists. */
	private static String gasSummary(FlipFluidDomain domain) {
		AirNetwork network = domain.airNetwork();
		List<Integer> ids = network.nodeIds();
		if (ids.isEmpty()) return "";
		StringBuilder builder = new StringBuilder("gas ");
		for (int i = 0; i < ids.size() && i < 4; i++) {
			int id = ids.get(i);
			if (i > 0) builder.append("; ");
			builder.append(String.format("node %d p=%+.0f Pa V0=%.2f m³",
					id, network.gaugePressure(id), network.standardVolume(id)));
		}
		if (ids.size() > 4) builder.append("; +").append(ids.size() - 4).append(" more");
		builder.append(String.format(", vented %.2f m³ drawn %.2f m³",
				AirNetwork.cubicMeters(network.ventedNanos()), AirNetwork.cubicMeters(network.drawnFromAmbientNanos())));
		return builder.toString();
	}

	public void sendSnapshot(ServerLevel level) {
		FluidRegion region = activeRegion();
		if (region != null && level.dimension().equals(region.dimension())) sendSnapshot(level, region);
	}

	/** Transfer-service queries: region presence, selection and availability in one dimension. */
	public FluidRegion selectedRegionIn(ServerLevel level) {
		FluidRegion selected = regionManager.selected();
		return selected != null && selected.dimension().equals(level.dimension()) ? selected : null;
	}

	public boolean ensureRegionAvailableFor(ServerPlayer player, FluidRegion region) {
		return ensureRegionAvailable(player.level(), region);
	}

	/** Returns the first region whose solver bounds contain the block position. */
	public FluidRegion regionAtBlock(ServerLevel level, BlockPos pos) {
		if (pos == null) return null;
		for (FluidRegion region : regionManager.regions()) {
			if (!region.dimension().equals(level.dimension())) continue;
			BlockPos origin = region.origin();
			double h = region.solverConfig().cellSize();
			if (pos.getX() >= origin.getX() && pos.getX() < origin.getX() + region.sizeX() * h
					&& pos.getY() >= origin.getY() && pos.getY() < origin.getY() + region.sizeY() * h
					&& pos.getZ() >= origin.getZ() && pos.getZ() < origin.getZ() + region.sizeZ() * h) {
				return region;
			}
		}
		return null;
	}

	public boolean configuredChemistryFeatureEnabled() {
		return serverConfig.chemistryEnabled();
	}

	/** Persists the next-startup chemistry gate; the registry/protocol remain fixed for this boot. */
	public String setChemistryFeatureForRestart(boolean enabled) {
		if (serverConfigLocked) return "server.properties 未通过校验；原文件已保留，请修复后重启服务器";
		if (enabled && !serverConfig.materialsEnabled()) return "chemistry=true 要求先启用 materials";
		FluidServerConfig replacement = serverConfig.withChemistryEnabled(enabled);
		try {
			replacement.save(worldRoot);
		} catch (IOException exception) {
			RealWaterMod.LOGGER.error("Could not persist Real Water chemistry feature flag", exception);
			return exception.getMessage();
		}
		serverConfig = replacement;
		return null;
	}

	/** Applies the bounded indicator/eraser primitive to the actually aimed region. */
	public IndicatorUseResult editIndicatorAtAim(ServerPlayer player, boolean indicated) {
		if (!chemistryFeatureEnabled) return IndicatorUseResult.rejected("化学功能门本次启动未开启");
		FluidRegion region = aimedRegion(player);
		if (region == null) return IndicatorUseResult.rejected("准星方向没有化学流体区域");
		if (!ensureRegionAvailableFor(player, region)) {
			return IndicatorUseResult.rejected("目标控制器区域当前不可用：" + region.availabilityFailure());
		}
		int[] cell = aimedCellNear(player, region, true);
		if (cell == null) return IndicatorUseResult.rejected("准星方向没有化学液体");
		FlipFluidDomain.IndicatorEditResult edit = region.domain().editIndicatorFloodFill(
				cell[0], cell[1], cell[2], indicated, Math.toIntExact(region.reservedCellCapacity()), 100.0);
		if (!edit.accepted()) return IndicatorUseResult.rejected(edit.failure());
		String checkpointWarning = null;
		if (edit.changedMarkers() > 0) {
			region.wakeForActivity();
			if (region.controllerManaged()) {
				try {
					regionOperations.checkpoint(region);
				} catch (IOException | RuntimeException failure) {
					checkpointWarning = "live parcel 已修改但 checkpoint 失败：" + failure.getMessage();
					region.setAvailabilityFailure(checkpointWarning);
					RealWaterMod.LOGGER.error("Could not checkpoint indicator edit for region {}", region.id(), failure);
				}
			}
		}
		sendSnapshot(player.level(), region);
		return new IndicatorUseResult(true, edit.scannedCells(), edit.changedMarkers(),
				edit.affectedBlockVolume(), checkpointWarning, null);
	}

	/** Administrator-only command payload; caller is responsible for enforcing permission and transcript logging. */
	public String chemistryInventoryStatus(ServerLevel level) {
		if (!chemistryFeatureEnabled) return "化学功能门本次启动未开启";
		FluidRegion region = selectedRegionIn(level);
		if (region == null || !region.solverLoaded() || !region.domain().hasChemistryState()) {
			return "当前维度没有已加载且选中的化学区域";
		}
		long[] totals = region.domain().chemicalParcels().speciesTotals(region.domain().particleCount());
		StringBuilder text = new StringBuilder("region ").append(region.id())
				.append(" generation ").append(region.checkpointGeneration())
				.append(" fingerprint ").append(chemicalRegistry.fingerprint())
				.append(" totalQuanta ").append(region.domain().chemicalParcels().totalQuanta())
				.append(" residualBits 0x").append(Long.toUnsignedString(
						region.domain().exportChemicalParticleState().residualLedgerBits(), 16));
		for (int handle = 0; handle < totals.length; handle++) if (totals[handle] != 0L) {
			text.append('\n').append(chemicalRegistry.species(handle).id()).append('=').append(totals[handle]);
		}
		text.append("\nexternalSourceQuanta=").append(region.domain().chemicalParcels().externalSource().totalQuanta())
				.append(" escapedSinkQuanta=").append(region.domain().chemicalParcels().escapedSink().totalQuanta());
		return text.toString();
	}

	public record IndicatorUseResult(
			boolean accepted, int scannedCells, int changedMarkers, double affectedBlockVolume,
			String checkpointWarning, String failure
	) {
		private static IndicatorUseResult rejected(String failure) {
			return new IndicatorUseResult(false, 0, 0, 0.0, null, failure);
		}
	}

	/** First region volume intersected by the player's ray; selection is intentionally irrelevant. */
	public FluidRegion aimedRegion(ServerPlayer player) {
		Vec3 start = player.getEyePosition();
		Vec3 direction = player.getLookAngle();
		for (double distance = 0.0; distance <= AIM_DISTANCE; distance += 0.125) {
			Vec3 point = start.add(direction.scale(distance));
			for (FluidRegion region : regionManager.regions()) {
				if (!region.dimension().equals(player.level().dimension())) continue;
				BlockPos origin = region.origin();
				double h = region.solverConfig().cellSize();
				if (point.x >= origin.getX() && point.x < origin.getX() + region.sizeX() * h
						&& point.y >= origin.getY() && point.y < origin.getY() + region.sizeY() * h
						&& point.z >= origin.getZ() && point.z < origin.getZ() + region.sizeZ() * h) {
					return region;
				}
			}
		}
		return null;
	}

	/** Resolves the solver cell containing the center of a block position inside a loaded region. */
	public int[] cellAtBlock(ServerLevel level, FluidRegion region, BlockPos pos) {
		if (!level.dimension().equals(region.dimension()) || !region.solverLoaded()) return null;
		FlipFluidDomain domain = region.domain();
		BlockPos origin = region.origin();
		double h = domain.config().cellSize();
		int x = Mth.floor((pos.getX() + 0.5 - origin.getX()) / h);
		int y = Mth.floor((pos.getY() + 0.5 - origin.getY()) / h);
		int z = Mth.floor((pos.getZ() + 0.5 - origin.getZ()) / h);
		if (x < 0 || x >= domain.sizeX() || y < 0 || y >= domain.sizeY() || z < 0 || z >= domain.sizeZ()) return null;
		return new int[] {x, y, z};
	}


	/** Resolves the transfer target cell near the player's aim; requireFluid selects scoop semantics. */
	public int[] aimedCellNear(ServerPlayer player, FluidRegion region, boolean requireFluid) {
		if (region == null || !region.solverLoaded()
				|| !player.level().dimension().equals(region.dimension())) return null;
		Vec3 start = player.getEyePosition();
		Vec3 direction = player.getLookAngle();
		FlipFluidDomain domain = region.domain();
		double step = domain.config().cellSize() * 0.25;
		CellTarget previousOpen = null;
		CellTarget previousSample = null;
		for (double distance = 0.0; distance <= AIM_DISTANCE; distance += step) {
			Vec3 point = start.add(direction.scale(distance));
			CellTarget cell = worldToCell(region, point);
			if (cell == null) {
				if (!requireFluid && previousOpen != null) return cellTargetArray(previousOpen);
				continue;
			}
			if (cell.equals(previousSample)) continue;
			previousSample = cell;
			if (requireFluid) {
				if (domain.volume(cell.x(), cell.y(), cell.z()) > domain.config().fluidEpsilon()) {
					return cellTargetArray(cell);
				}
			} else if (domain.isSolid(cell.x(), cell.y(), cell.z())) {
				return previousOpen == null ? null : cellTargetArray(previousOpen);
			} else if (domain.volume(cell.x(), cell.y(), cell.z()) > domain.config().fluidEpsilon()) {
				return cellTargetArray(previousOpen == null ? cell : previousOpen);
			} else {
				previousOpen = cell;
			}
		}
		return null;
	}

	private static CellTarget worldToCell(FluidRegion region, Vec3 point) {
		FlipFluidDomain domain = region.domain();
		BlockPos origin = region.origin();
		float h = domain.config().cellSize();
		int x = Mth.floor((point.x - origin.getX()) / h);
		int y = Mth.floor((point.y - origin.getY()) / h);
		int z = Mth.floor((point.z - origin.getZ()) / h);
		if (x < 0 || x >= domain.sizeX() || y < 0 || y >= domain.sizeY()
				|| z < 0 || z >= domain.sizeZ()) return null;
		return new CellTarget(x, y, z);
	}

	private static int[] cellTargetArray(CellTarget target) {
		return new int[] {target.x(), target.y(), target.z()};
	}

	public void sendSnapshotFor(ServerPlayer player) {
		FluidRegion region = activeRegion();
		if (region != null) sendSnapshotFor(player, region);
	}

	public void sendSnapshotFor(ServerPlayer player, FluidRegion region) {
		if (region != null && region.solverLoaded() && player.level().dimension().equals(region.dimension())) {
			if (chemistryFeatureEnabled) {
				FluidProtocolServer.send(player, createChemicalSnapshot(region));
			} else if (materialFeatureEnabled) {
				FluidProtocolServer.send(player, createMaterialSnapshot(region));
			} else {
				FluidProtocolServer.send(player, createSnapshot(region));
			}
		}
	}

	public void sendSnapshots(ServerPlayer player) {
		for (FluidRegion region : regionManager.regions()) {
			if (!region.solverLoaded() || !player.level().dimension().equals(region.dimension())) continue;
			if (chemistryFeatureEnabled) FluidProtocolServer.send(player, createChemicalSnapshot(region));
			else if (materialFeatureEnabled) FluidProtocolServer.send(player, createMaterialSnapshot(region));
			else FluidProtocolServer.send(player, createSnapshot(region));
		}
	}

	public void sendSnapshot(ServerLevel level, FluidRegion region) {
		if (!region.solverLoaded() || !level.dimension().equals(region.dimension())) return;
		if (chemistryFeatureEnabled) {
			FluidChemicalSnapshotPayload payload = createChemicalSnapshot(region);
			for (ServerPlayer player : level.players()) FluidProtocolServer.send(player, payload);
		} else if (materialFeatureEnabled) {
			FluidMaterialSnapshotPayload payload = createMaterialSnapshot(region);
			for (ServerPlayer player : level.players()) FluidProtocolServer.send(player, payload);
		} else {
			FluidSnapshotPayload payload = createSnapshot(region);
			for (ServerPlayer player : level.players()) FluidProtocolServer.send(player, payload);
		}
	}

	private static FluidSnapshotPayload createSnapshot(FluidRegion region) {
		FlipFluidDomain domain = region.domain();
		List<FluidSnapshotPayload.Extension> extensions = List.of();
		long cellCount = (long) domain.sizeX() * domain.sizeY() * domain.sizeZ();
		if (domain.gasPressureActive() && cellCount <= FluidSnapshotPayload.MAX_EXTENSION_BYTES) {
			extensions = List.of(new FluidSnapshotPayload.Extension(
					FluidSnapshotPayload.EXTENSION_GAS_NODES, domain.copyGasNodeMask()));
		}
		return FluidSnapshotPayload.snapshot(
				region.dimension().identifier(), region.id(), region.generation(), region.nextSnapshotSequence(),
				region.origin(), domain.config().cellSize(), domain.sizeX(), domain.sizeY(), domain.sizeZ(),
				domain.copyQuantizedVolume(), domain.copyMarkerMask(), domain.copySolidMask(), region.debugOverlays(),
				extensions
		);
	}

	private static void sendRegionRemoval(MinecraftServer server, FluidRegion region) {
		long sequence = region.nextSnapshotSequence();
		FluidSnapshotPayload purePayload = FluidSnapshotPayload.removeRegion(
				region.dimension().identifier(), region.id(), region.generation(), sequence
		);
		FluidMaterialSnapshotPayload materialPayload = FluidMaterialSnapshotPayload.removeRegion(
				region.dimension().identifier(), region.id(), region.generation(), sequence
		);
		FluidChemicalSnapshotPayload chemicalPayload = FluidChemicalSnapshotPayload.removeRegion(materialPayload);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			FluidProtocolServer.send(player, purePayload);
			FluidProtocolServer.send(player, materialPayload);
			FluidProtocolServer.send(player, chemicalPayload);
		}
	}

	private FluidRegion activeRegion() {
		return regionManager.selected();
	}

	private FlipFluidDomain domain() {
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

	public String lastTakeoverFailure() {
		return lastTakeoverFailure;
	}

	public record CaptureResult(int wetCells, int removedVanillaBlocks, String failure) { }

	public record SelectionResult(boolean succeeded, UUID regionId, String failure) {
		static SelectionResult failed(String failure) { return new SelectionResult(false, null, failure); }
	}

	private static FluidMaterialSnapshotPayload createMaterialSnapshot(FluidRegion region) {
		FluidMaterialRenderSnapshot snapshot = region.domain().copyMaterialRenderSnapshot();
		return FluidMaterialSnapshotPayload.snapshot(
				region.dimension().identifier(), region.id(), region.generation(), region.nextSnapshotSequence(), region.origin(),
				snapshot, region.debugOverlays()
		);
	}

	private static FluidChemicalSnapshotPayload createChemicalSnapshot(FluidRegion region) {
		if (!region.domain().hasChemistryState()) throw new IllegalStateException("Chemical region is missing parcel state");
		FluidMaterialRenderSnapshot material = region.domain().copyMaterialRenderSnapshot();
		FluidMaterialSnapshotPayload base = FluidMaterialSnapshotPayload.snapshot(
				region.dimension().identifier(), region.id(), region.generation(), region.nextSnapshotSequence(), region.origin(),
				material, region.debugOverlays());
		return FluidChemicalSnapshotPayload.snapshot(base,
				region.domain().chemicalParcels().registry().fingerprint(), region.domain().copyChemicalDisplaySnapshot());
	}

	private static void bindDefaultMaterialState(FlipFluidDomain domain) {
		if (domain.hasMaterialState()) return;
		FlipParticleState pure = domain.exportParticleState();
		float[] positionVolume = pure.copyPositionVolume();
		double[] markerMass = new double[pure.particleCount()];
		int[] material = new int[pure.particleCount()];
		double totalVolume = 0.0, totalMass = 0.0;
		for (int marker = 0; marker < pure.particleCount(); marker++) {
			double volume = positionVolume[marker * 4 + 3];
			markerMass[marker] = volume * BuiltinFluidMaterials.WATER.referenceDensity();
			totalVolume += volume;
			totalMass += markerMass[marker];
		}
		domain.restoreMaterialParticleState(new FlipMaterialParticleState(
				pure.sizeX(), pure.sizeY(), pure.sizeZ(), pure.cellSize(), pure.particleCount(), totalVolume, totalMass,
				BuiltinFluidMaterials.M3_PALETTE, positionVolume, pure.copyParticleVelocities(), pure.copySolidMask(), markerMass, material
		));
	}

	private static void assignLayeredDemoMaterials(FlipFluidDomain domain, float interfaceY) {
		FlipMaterialParticleState state = domain.exportMaterialParticleState();
		float[] positionVolume = state.copyPositionVolume();
		double[] markerMass = state.copyParticleMass();
		int[] materials = state.copyMaterialPaletteIndex();
		double totalMass = 0.0;
		for (int marker = 0; marker < state.particleCount(); marker++) {
			int material = positionVolume[marker * 4 + 1] >= interfaceY ? 1 : 0;
			materials[marker] = material;
			markerMass[marker] = positionVolume[marker * 4 + 3]
					* BuiltinFluidMaterials.M3_PALETTE.get(material).referenceDensity();
			totalMass += markerMass[marker];
		}
		domain.restoreMaterialParticleState(new FlipMaterialParticleState(
				state.sizeX(), state.sizeY(), state.sizeZ(), state.cellSize(), state.particleCount(), state.totalVolume(), totalMass,
				state.materials(), positionVolume, state.copyParticleVelocities(), state.copySolidMask(), markerMass, materials
		));
	}

	public record ControllerActivation(
			boolean succeeded,
			boolean selectedExisting,
			boolean disabled,
			String failure,
			int interiorBlocks,
			int sizeX,
			int sizeY,
			int sizeZ,
			int requiredChunks
	) {
		private static final ControllerActivation DISABLED = new ControllerActivation(
				false, false, true, "controller feature flag is disabled", 0, 0, 0, 0, 0
		);

		private static ControllerActivation failed(String failure) {
			return new ControllerActivation(false, false, false, failure, 0, 0, 0, 0, 0);
		}

		private static ControllerActivation created(FluidRegionFootprint footprint) {
			return succeeded(footprint, false);
		}

		private static ControllerActivation selected(FluidRegionFootprint footprint) {
			return succeeded(footprint, true);
		}

		private static ControllerActivation succeeded(FluidRegionFootprint footprint, boolean selectedExisting) {
			return new ControllerActivation(
					true, selectedExisting, false, null, footprint.interiorBlocks().size(),
					footprint.blockSizeX(), footprint.blockSizeY(), footprint.blockSizeZ(),
					footprint.requiredChunks().size()
			);
		}
	}

	public record RestorationStats(
			boolean journalFound,
			int restoredBlocks,
			int alreadyRestoredBlocks,
			int skippedBlocks,
			int ownershipRestoredBlocks,
			int ownershipSkippedBlocks,
			boolean completed,
			String failure
	) {
		RestorationStats(
				boolean journalFound,
				int restoredBlocks,
				int alreadyRestoredBlocks,
				int skippedBlocks,
				boolean completed,
				String failure
		) {
			this(journalFound, restoredBlocks, alreadyRestoredBlocks, skippedBlocks, 0, 0, completed, failure);
		}

		private static RestorationStats failed(
				boolean journalFound,
				int restoredBlocks,
				int alreadyRestoredBlocks,
				int skippedBlocks,
				String failure
		) {
			return new RestorationStats(journalFound, restoredBlocks, alreadyRestoredBlocks, skippedBlocks, 0, 0, false, failure);
		}
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
			return changedCellVolume / CELL_VOLUMES_PER_BLOCK;
		}
	}

}
