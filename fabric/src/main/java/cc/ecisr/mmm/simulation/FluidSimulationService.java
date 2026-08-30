package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.MmmMod;
import cc.ecisr.mmm.api.RegionAvailabilityReason;
import cc.ecisr.mmm.api.query.MmmQueryApi;
import cc.ecisr.mmm.application.ContainerRecoveryCoordinator;
import cc.ecisr.mmm.application.ContainerTransferCoordinator;
import cc.ecisr.mmm.application.ControllerQueryUseCase;
import cc.ecisr.mmm.application.RegionApplicationService;
import cc.ecisr.mmm.application.RegionCatalog;
import cc.ecisr.mmm.application.ThermalReservoirUseCase;
import cc.ecisr.mmm.application.command.CheckpointRegionCommand;
import cc.ecisr.mmm.application.command.ControllerSummaryCommand;
import cc.ecisr.mmm.application.command.MotionStepCommand;
import cc.ecisr.mmm.application.result.CheckpointRegionResult;
import cc.ecisr.mmm.application.result.ControllerSummaryResult;
import cc.ecisr.mmm.application.result.MotionStepResult;
import cc.ecisr.mmm.block.MmmBlocks;
import cc.ecisr.mmm.block.ThermalReservoirBlockEntity;
import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.controller.ControllerConsoleAction;
import cc.ecisr.mmm.controller.ControllerSummary;
import cc.ecisr.mmm.controller.LifecycleOperation;
import cc.ecisr.mmm.fluid.BuiltinFluidMaterials;
import cc.ecisr.mmm.fluid.CellCarrierInventoryView;
import cc.ecisr.mmm.fluid.CpuFluidSolverBackend;
import cc.ecisr.mmm.fluid.FluidDomain;
import cc.ecisr.mmm.fluid.GasPressureBoundary;
import cc.ecisr.mmm.fluid.FluidSolverBackend;
import cc.ecisr.mmm.fluid.FlipMaterialParticleState;
import cc.ecisr.mmm.fluid.FlipParticleState;
import cc.ecisr.mmm.fluid.FluidMaterial;
import cc.ecisr.mmm.fluid.FluidMaterialRegistry;
import cc.ecisr.mmm.fluid.FluidSolverConfig;
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
import cc.ecisr.mmm.transfer.ThermalContents;
import cc.ecisr.mmm.simulation.controller.FluidBoundaryScanner;
import cc.ecisr.mmm.simulation.controller.FluidControllerRegions;
import cc.ecisr.mmm.simulation.controller.FluidRegionFootprint;
import cc.ecisr.mmm.simulation.ownership.OwnershipJournal;
import cc.ecisr.mmm.thermal.ThermalAuditLedger;
import cc.ecisr.mmm.thermal.ThermalMaterialDefinition;
import cc.ecisr.mmm.thermal.ThermalRegistry;
import cc.ecisr.mmm.thermal.ThermalReservoirSummary;
import cc.ecisr.mmm.thermal.ThermalDisplaySnapshot;
import cc.ecisr.mmm.thermal.ThermalStateFunction;
import cc.ecisr.mmm.fluid.MaterialRegistry;
import cc.ecisr.mmm.world.MovingBodyFluidCoupling;
import cc.ecisr.mmm.worldprofile.WorldCapability;
import cc.ecisr.mmm.worldprofile.WorldCapabilityProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Adapts fluid regions to Minecraft while delegating region-state lifecycle and takeover transactions. */
public final class FluidSimulationService {
	public static final int BLOCK_SIZE_X = 12;
	public static final int BLOCK_SIZE_Y = 8;
	public static final int BLOCK_SIZE_Z = 12;
	public static final int CELLS_PER_BLOCK = 2;
	static final int CELL_VOLUMES_PER_BLOCK = CELLS_PER_BLOCK * CELLS_PER_BLOCK * CELLS_PER_BLOCK;
	private static final long COMPATIBILITY_REGION_CELLS = (long) BLOCK_SIZE_X * BLOCK_SIZE_Y * BLOCK_SIZE_Z
			* CELLS_PER_BLOCK * CELLS_PER_BLOCK * CELLS_PER_BLOCK;
	private static final long COMPATIBILITY_REGION_MARKERS = COMPATIBILITY_REGION_CELLS * 8L;

	private final Map<BlockPos, BlockState> takeoverOriginalBlocks = new HashMap<>();
	private final Path worldRoot;
	private final boolean serverConfigLocked;
	private FluidServerConfig serverConfig;
	private final FluidRegionManager regionManager;
	private final FluidSolverBackend solverBackend = new CpuFluidSolverBackend();
	private final CommittedEventHub committedEvents;
	private final FluidWorldTargetResolver targets;
	private final FluidRegionOperations regionOperations;
	private final RegionApplicationService regionApplication;
	private final MinecraftRegionTick regionTick;
	private final ControllerQueryUseCase controllerQueries;
	private final MinecraftRegionLifecycle regionLifecycle;
	private final ThermalReservoirUseCase thermalReservoirs = new ThermalReservoirUseCase();
	private final FluidSnapshotPublisher snapshots;
	private final ContainerRecoveryCoordinator containerRecovery;
	private final MinecraftContainerTransactionAdapter containerTransactions;
	private ResourceKey<Level> takeoverDimension;
	private boolean defaultEntityPhysicsEnabled = true;
	private boolean defaultDebugOverlays;
	private boolean controllerFeatureEnabled;
	private final boolean worldBridgeFeatureEnabled;
	/** Typed startup proof retained so no authoritative write can use the fail-closed empty profile. */
	private final WorldStartupCoordinator.Result startup;
	/** Committed profile, or an empty fail-closed value while startup is not provable. */
	private final WorldCapabilityProfile worldCapabilityProfile;
	private final MaterialRegistry materialRegistry;
	private final ChemicalRegistry chemicalRegistry;
	private final GranularRegistry granularRegistry;
	private final ThermalRegistry thermalRegistry;
	private final GasRegistry gasRegistry;
	private List<String> ownershipIssues = List.of();
	private String lastTakeoverFailure;
	private boolean takeoverMaintenanceEnabled;

	FluidSimulationService(
			WorldStartupCoordinator.Result startup,
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
		this.worldCapabilityProfile = startup.profile();
		RegistryBundleLoader.RegistryBundle registries = startup.registries();
		this.materialRegistry = registries == null ? null : registries.material();
		this.chemicalRegistry = registries == null ? null : registries.chemical();
		this.granularRegistry = registries == null ? null : registries.granular();
		this.gasRegistry = registries == null ? null : registries.gas();
		this.thermalRegistry = registries == null ? null : registries.thermal();
		MinecraftRegionInputs regionInputs = new MinecraftRegionInputs(thermalRegistry, CELLS_PER_BLOCK);
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
		this.regionTick = new MinecraftRegionTick(regionApplication, regionInputs, snapshots,
				worldCapabilityProfile, serverConfig.maximumPhaseOperationsPerStep());
		this.controllerQueries = new ControllerQueryUseCase(
				new ControllerSummaryAdapter(regionManager, regionOperations, worldCapabilityProfile, thermalRegistry,
						this::hasPendingContainerTransfer, this::authoritativeWritesReady, worldBridgeFeatureEnabled));
		this.regionLifecycle = new MinecraftRegionLifecycle(startup, regionManager, regionOperations, snapshots, targets,
				controllerQueries, thermalRegistry, this::hasPendingContainerTransfer);
	}

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

	void cleanupContainerTransfers(MinecraftServer server) { containerRecovery.recoverIndexed(); }

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

	/** Stable read-only API surface; construction performs no world or region I/O. */
	public MmmQueryApi queryApi(MinecraftServer server) {
		return new FabricFluidQueryApi(server, regionManager, startup.profileProven());
	}

	/** Read-only companion to Query for cell-local solid/gas carriers; callers first resolve a unique interior point. */
	public CarrierCellObservation observeCellCarriers(ServerLevel level, Vec3 interiorPoint) {
		if (!level.getServer().isSameThread()) throw new IllegalStateException("Carrier observation is server-thread-only");
		FluidRegion region = targets.regionAtBlock(level, BlockPos.containing(interiorPoint));
		if (region == null || region.availabilityFailure() != null || !region.solverLoaded()) return null;
		double h = region.solverConfig().cellSize();
		int x = Mth.floor((interiorPoint.x - region.origin().getX()) / h);
		int y = Mth.floor((interiorPoint.y - region.origin().getY()) / h);
		int z = Mth.floor((interiorPoint.z - region.origin().getZ()) / h);
		if (x < 0 || x >= region.sizeX() || y < 0 || y >= region.sizeY() || z < 0 || z >= region.sizeZ()) {
			return null;
		}
		return new CarrierCellObservation(region.id(), region.networkGeneration(), x, y, z,
				region.domain().observeCellCarriers(x, y, z));
	}

	/** Returns the centre of the first liquid/granular/coherent cell along an already resolved interior ray. */
	public Vec3 firstCondensedProbePoint(ServerLevel level, Vec3 interiorPoint, Vec3 direction) {
		if (!level.getServer().isSameThread()) throw new IllegalStateException("Probe traversal is server-thread-only");
		FluidRegion region = targets.regionAtBlock(level, BlockPos.containing(interiorPoint));
		if (region == null || region.availabilityFailure() != null || !region.solverLoaded()) return null;
		int[] cell = targets.firstCondensedCellAlong(region, interiorPoint, direction);
		if (cell == null) return null;
		double h = region.solverConfig().cellSize();
		return new Vec3(region.origin().getX() + (cell[0] + 0.5) * h,
				region.origin().getY() + (cell[1] + 0.5) * h,
				region.origin().getZ() + (cell[2] + 0.5) * h);
	}

	public record CarrierCellObservation(
			UUID regionId, long regionNetworkGeneration, int cellX, int cellY, int cellZ,
			CellCarrierInventoryView carriers
	) {
		public CarrierCellObservation {
			if (regionId == null || regionNetworkGeneration <= 0L
					|| cellX < 0 || cellY < 0 || cellZ < 0) {
				throw new IllegalArgumentException("Carrier cell observation identity is invalid");
			}
		}
	}

	/** Snapshots bounded maintained ledgers without loading, waking, checkpointing, selecting, or building display grids. */
	public ControllerSummary controllerSummary(ServerPlayer player, BlockPos controllerPosition) {
		if (player == null || controllerPosition == null) return null;
		ServerLevel level = player.level();
		if (!level.getServer().isSameThread()) throw new IllegalStateException("Controller summary is server-thread-only");
		if (player.distanceToSqr(controllerPosition.getX() + 0.5,
				controllerPosition.getY() + 0.5, controllerPosition.getZ() + 0.5) > 64.0) return null;
		if (!level.getBlockState(controllerPosition).is(MmmBlocks.FLUID_CONTROLLER)) return null;
		FluidRegion region = targets.controllerRegionAt(level, controllerPosition);
		if (region == null || !startup.profileProven()) return null;
		ControllerSummaryResult result = controllerQueries.query(
				new ControllerSummaryCommand(player.getUUID(), region.id()));
		return result.ok() ? result.summary() : null;
	}

	/** Re-resolves every client-supplied identity and consumes the one-use server token before acting. */
	public Component applyControllerConsoleAction(
			ServerPlayer player, UUID requestedRegionId, BlockPos controllerPosition,
			long summaryGeneration, ControllerConsoleAction action
	) {
		if (player == null || requestedRegionId == null || controllerPosition == null || action == null) {
			return Component.translatable("message.mmm.controller.request_identity_missing");
		}
		ServerLevel level = player.level();
		if (!level.getServer().isSameThread()) throw new IllegalStateException("Controller actions are server-thread-only");
		double distanceSquared = player.distanceToSqr(controllerPosition.getX() + 0.5,
				controllerPosition.getY() + 0.5, controllerPosition.getZ() + 0.5);
		if (distanceSquared > 64.0) return Component.translatable("message.mmm.controller.too_far");
		if (!level.getBlockState(controllerPosition).is(MmmBlocks.FLUID_CONTROLLER)) {
			return Component.translatable("message.mmm.controller.target_gone");
		}
		FluidRegion region = targets.controllerRegionAt(level, controllerPosition);
		if (region == null || !region.id().equals(requestedRegionId)) {
			return Component.translatable("message.mmm.controller.owner_changed");
		}
		if (!controllerQueries.consumeActionToken(player.getUUID(), region.id(), summaryGeneration)) {
			return Component.translatable("message.mmm.controller.summary_expired");
		}

		return switch (action) {
			case SELECT -> regionManager.select(region.id())
					? null : Component.translatable("message.mmm.controller.region_gone");
			case REFRESH_AVAILABILITY -> ensureRegionAvailable(level, region)
					? null : Component.translatable("message.mmm.controller.availability_still_blocked",
							region.availabilityFailure());
			case PAUSE -> changeControllerRunIntent(player, region, true);
			case RESUME -> changeControllerRunIntent(player, region, false);
		};
	}

	private Component changeControllerRunIntent(ServerPlayer player, FluidRegion region, boolean pause) {
		return regionLifecycle.changeRunIntent(player, region, pause);
	}

	/** Issues a bounded destructive-operation preview after consuming the displayed console generation. */
	public MinecraftRegionLifecycle.PreviewResult previewControllerLifecycleOperation(
			ServerPlayer player, UUID requestedRegionId, BlockPos controllerPosition,
			long summaryGeneration, LifecycleOperation operation
	) {
		return regionLifecycle.preview(player, requestedRegionId, controllerPosition, summaryGeneration, operation);
	}

	/** Consumes one same-player preview and rechecks all commit gates before changing authoritative state. */
	public MinecraftRegionLifecycle.CommitResult confirmControllerLifecycleOperation(
			ServerPlayer player, UUID requestedRegionId, BlockPos controllerPosition,
			UUID nonce, LifecycleOperation operation
	) {
		return regionLifecycle.confirm(player, requestedRegionId, controllerPosition, nonce, operation);
	}

	public void forgetInteractiveViewer(UUID playerId) {
		controllerQueries.forgetViewer(playerId);
		regionLifecycle.forgetViewer(playerId);
		thermalReservoirs.forgetViewer(playerId);
	}

	/** Read-only tuning state; invalid persisted setpoints remain visible so a creative player can replace them. */
	public ThermalReservoirSummary thermalReservoirSummary(ServerPlayer player, BlockPos position) {
		if (player == null || position == null) return null;
		ServerLevel level = player.level();
		if (!level.getServer().isSameThread()) {
			throw new IllegalStateException("Thermal reservoir summary is server-thread-only");
		}
		if (player.distanceToSqr(position.getX() + 0.5, position.getY() + 0.5, position.getZ() + 0.5) > 64.0
				|| !level.getBlockState(position).is(MmmBlocks.THERMAL_RESERVOIR)
				|| !(level.getBlockEntity(position) instanceof ThermalReservoirBlockEntity reservoir)
				|| thermalRegistry == null) return null;
		ThermalRegistry.TemperatureRange range = thermalRegistry.worldBoundaryTemperatureRange();
		double temperature = reservoir.temperatureK();
		return thermalReservoirs.summary(thermalTarget(player, position), temperature, range);
	}

	/** Revalidates a proposed setpoint, persists the world owner, then refreshes and wakes adjacent thermal regions. */
	public String applyThermalReservoirSetpoint(
			ServerPlayer player, BlockPos position, long summaryGeneration, double temperatureK
	) {
		if (player == null || position == null) return "请求缺少温度源身份";
		ServerLevel level = player.level();
		if (!level.getServer().isSameThread()) {
			throw new IllegalStateException("Thermal reservoir actions are server-thread-only");
		}
		if (player.distanceToSqr(position.getX() + 0.5, position.getY() + 0.5, position.getZ() + 0.5) > 64.0) {
			return "玩家距离温度源过远";
		}
		ThermalReservoirBlockEntity reservoir = level.getBlockEntity(position)
				instanceof ThermalReservoirBlockEntity found ? found : null;
		boolean targetPresent = level.getBlockState(position).is(MmmBlocks.THERMAL_RESERVOIR)
				&& reservoir != null;
		ThermalReservoirUseCase.Decision authorization = thermalReservoirs.authorize(
				thermalTarget(player, position), summaryGeneration, player.isCreative(),
				authoritativeWritesReady(), targetPresent, thermalRegistry != null);
		if (authorization != ThermalReservoirUseCase.Decision.AUTHORIZED) {
			return switch (authorization) {
				case TOKEN_EXPIRED -> "summary 已过期，请刷新";
				case PERMISSION_DENIED -> "只有创造模式玩家可以修改温度源";
				case WRITE_NOT_READY -> authoritativeWriteFailure("修改温度源");
				case TARGET_MISSING -> "目标方块不再是温度源";
				case REGISTRY_UNAVAILABLE -> "world thermal registry 不可用";
				case TEMPERATURE_OUT_OF_RANGE, AUTHORIZED -> throw new IllegalStateException(
						"Unexpected thermal reservoir authorization result " + authorization);
			};
		}
		ThermalRegistry.TemperatureRange range = thermalRegistry.worldBoundaryTemperatureRange();
		if (thermalReservoirs.validateSetpoint(temperatureK, range)
				== ThermalReservoirUseCase.Decision.TEMPERATURE_OUT_OF_RANGE) {
			return String.format(java.util.Locale.ROOT,
				"温度必须是有限数值且位于 world thermal registry 的 [%.6f, %.6f] K",
				range.minimumK(), range.maximumK());
		}
		reservoir.setTemperatureK(temperatureK);
		refreshThermalReservoir(level, position);
		return null;
	}

	private static ThermalReservoirUseCase.Target thermalTarget(ServerPlayer player, BlockPos position) {
		return new ThermalReservoirUseCase.Target(player.getUUID(),
				player.level().dimension().identifier().toString(), position.getX(), position.getY(), position.getZ());
	}

	private void refreshThermalReservoir(ServerLevel level, BlockPos position) {
		for (FluidRegion region : regionManager.regions()) {
			FluidRegionFootprint footprint = region.footprint();
			if (footprint == null || !region.dimension().equals(level.dimension())
					|| !footprint.boundaryBlocks().contains(position.asLong())
					|| !region.solverLoaded() || !region.domain().hasThermalState()) continue;
			regionTick.refreshThermalBoundary(level, region);
		}
	}

	/** Adds authoritative U to a server-resolved mass-bearing liquid source. */
	void attachLiquidThermalContents(ItemStack stack, double temperatureKelvin) {
		if (!worldCapabilityProfile.hasCapability(WorldCapability.THERMAL)) return;
		if (thermalRegistry == null) throw new IllegalStateException("Authoritative thermal registry is unavailable");
		ThermalStateFunction stateFunction = new ThermalStateFunction(thermalRegistry);
		double energy = 0.0;
		if (stack.get(MmmComponents.CHEMICAL_CONTENTS) != null) {
			if (chemicalRegistry == null) throw new IllegalStateException("Authoritative chemical registry is unavailable");
			ChemicalContents contents = ContainerItemStacks.chemicalContents(stack, chemicalRegistry);
			if (contents == null) throw new IllegalArgumentException("Debug chemical liquid inventory is malformed");
			for (ChemicalContents.Group group : contents.groups()) {
				java.util.TreeMap<String, Long> species = new java.util.TreeMap<>();
				for (ChemicalContents.SpeciesAmount amount : group.species()) {
					species.put(amount.speciesId(), amount.amountQuanta());
				}
				int handle = thermalRegistry.materialHandle(
						ThermalMaterialDefinition.CarrierKind.LIQUID, group.materialId());
				validateLiquidTemperature(group.materialId(), temperatureKelvin);
				energy += stateFunction.internalEnergyAtTemperature(handle, group.massKilograms(), species, temperatureKelvin);
			}
		} else {
			FluidMixture mixture = ContainerItemStacks.fluidMixture(stack);
			if (mixture == null) throw new IllegalArgumentException("Debug material liquid inventory is malformed");
			for (FluidMixture.Component component : mixture.components()) {
				int handle = thermalRegistry.materialHandle(
						ThermalMaterialDefinition.CarrierKind.LIQUID, component.materialId());
				validateLiquidTemperature(component.materialId(), temperatureKelvin);
				energy += stateFunction.internalEnergyAtTemperature(handle, component.mass(), Map.of(), temperatureKelvin);
			}
		}
		if (!Double.isFinite(energy)) throw new IllegalArgumentException("Liquid internal energy overflow");
		ContainerItemStacks.attachThermal(stack, thermalRegistry.fingerprint(), energy);
	}

	/** Fixture adapter retained for commands; production sources use the temperature-aware resolver. */
	public void attachDebugLiquidThermalContents(ItemStack stack) {
		attachLiquidThermalContents(stack, GasRegistry.STANDARD_TEMPERATURE_K);
	}

	FluidMaterial resolveMaterial(String stableId) {
		if (materialRegistry == null) throw new IllegalStateException("Authoritative material registry is unavailable");
		return materialRegistry.entry(stableId).material();
	}

	private void validateLiquidTemperature(String stableId, double temperatureKelvin) {
		if (materialRegistry == null) throw new IllegalStateException("Authoritative material registry is unavailable");
		if (materialRegistry.declaresViscosity(stableId)) {
			materialRegistry.dynamicViscosityAt(stableId, temperatureKelvin);
		}
	}

	public ItemStack bindActualContainerProfile(ItemStack stack) {
		if (stack.get(MmmComponents.FLUID_VOLUME) != null || stack.get(MmmComponents.CHEMICAL_CONTENTS) != null
				|| stack.get(MmmComponents.GRANULAR_CONTENTS) != null) {
			if (!startup.profileProven()) {
				throw new IllegalStateException("Authoritative world profile is unavailable");
			}
			ContainerItemStacks.bindProfile(stack, worldCapabilityProfile.profileFingerprint());
		}
		return stack;
	}

	boolean acceptsActualContainerProfile(ItemStack stack) {
		return startup.profileProven() && worldCapabilityProfile.profileFingerprint().equals(
				ContainerItemStacks.profileFingerprint(stack));
	}

	String thermalRegistryFingerprint() {
		if (thermalRegistry == null) throw new IllegalStateException("Authoritative thermal registry is unavailable");
		return thermalRegistry.fingerprint();
	}
	public GranularRegistry granularRegistry() {
		if (granularRegistry == null) throw new IllegalStateException("Authoritative granular registry is unavailable");
		return granularRegistry;
	}

	public ChemicalRegistry chemicalRegistry() {
		if (chemicalRegistry == null) throw new IllegalStateException("Authoritative chemical registry is unavailable");
		return chemicalRegistry;
	}

	/** Live world-bridge gate; ownership gameplay binds to this boot's value like the material gate. */
	public boolean worldBridgeFeatureEnabled() {
		return worldBridgeFeatureEnabled;
	}

	/** The committed profile, or an empty fail-closed value while startup is not provable. */
	public WorldCapabilityProfile worldCapabilityProfile() {
		return worldCapabilityProfile;
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

	public ControllerActivation activateController(ServerPlayer player, BlockPos controllerPosition) {
		return activateController(player, controllerPosition, false);
	}

	public ControllerActivation activateController(
			ServerPlayer player, BlockPos controllerPosition, boolean toggleRunning
	) {
		ServerLevel level = player.level();
		restoreAirDemoScaffolding(level.getServer());
		if (!level.getBlockState(controllerPosition).is(MmmBlocks.FLUID_CONTROLLER)) {
			return ControllerActivation.failed("目标方块不再是 Matter, Motion & Mechanics 控制器");
		}
		for (FluidRegion existing : regionManager.regions()) {
			FluidRegionFootprint existingFootprint = existing.footprint();
			if (existingFootprint == null || !existing.dimension().equals(level.dimension())
					|| !existingFootprint.controllerPosition().equals(controllerPosition)) continue;
			regionManager.select(existing.id());
			if (!ensureRegionAvailable(level, existing)) {
				return ControllerActivation.failed("控制器区域当前不可用：" + existing.availabilityFailure());
			}
			ControllerAction action;
			if (toggleRunning) {
				if (!authoritativeWritesReady()) {
					return ControllerActivation.failed(authoritativeWriteFailure("修改控制器运行意图"));
				}
				if (existing.paused()) {
					existing.setPaused(false);
					existing.wakeForActivity();
					action = ControllerAction.RESUMED;
				} else {
					existing.setPaused(true);
					action = ControllerAction.PAUSED;
				}
			} else {
				action = existing.paused() ? ControllerAction.SELECTED_PAUSED : ControllerAction.SELECTED_RUNNING;
			}
			sendSnapshot(level);
			return ControllerActivation.succeeded(existingFootprint, action);
		}
		if (!controllerFeatureEnabled) return ControllerActivation.DISABLED;
		if (!authoritativeWritesReady()) {
			return ControllerActivation.failed(authoritativeWriteFailure("创建控制器区域"));
		}
		if (hasContainerTransferStoreIssues()) {
			return ControllerActivation.failed("container-transfer 恢复目录含未知或损坏内容；已全局锁住新控制器，详见日志");
		}
		if (regionOperations.creationLocked() || !ownershipIssues.isEmpty()) {
			return ControllerActivation.failed("存在未解决或超预算的 region-state/ownership；已锁住新控制器区域，详见 /mmm regions list、/mmm ownership 与日志");
		}

		RestorationStats restoration = restoreTakeover(level.getServer());
		if (!restoration.completed()) return ControllerActivation.failed(restoration.failure());

		FluidBoundaryScanner.ScanResult scan = FluidControllerRegions.scan(
				level, controllerPosition, (int) Math.max(1L, Math.min(Integer.MAX_VALUE,
						regionManager.limits().maximumCellsPerRegion() / CELL_VOLUMES_PER_BLOCK)
				)
		);
		if (!scan.succeeded()) return ControllerActivation.failed(scan.failure());
		FluidRegionFootprint footprint = scan.footprint();
		List<OwnershipJournal.BlockEntry> ownershipPreimage;
		try {
			ownershipPreimage = regionOperations.scanOwnershipPreimage(level, footprint);
		} catch (IOException invalidPreimage) {
			return ControllerActivation.failed("无法冻结 controller world preimage：" + invalidPreimage.getMessage());
		}
		if (!worldBridgeFeatureEnabled && !ownershipPreimage.isEmpty()) {
			return ControllerActivation.failed("worldbridge 策略关闭；拒绝接管壳内原版水，请先排空或启用策略并重启");
		}
		ControllerCreationPlan creationPlan = new ControllerCreationPlan(
				worldCapabilityProfile.profileFingerprint(), footprint, ownershipPreimage);
		long requiredCells;
		long requiredMarkers;
		try {
			requiredCells = Math.multiplyExact(footprint.denseBlockCapacity(), CELL_VOLUMES_PER_BLOCK);
			requiredMarkers = Math.multiplyExact(requiredCells, 8L);
		} catch (ArithmeticException exception) {
			return ControllerActivation.failed("控制器区域尺寸溢出资源计数");
		}
		RegionCatalog.AdmissionResult preview = regionManager.previewAdd(requiredCells, requiredMarkers);
		if (!preview.accepted()) return ControllerActivation.failed(preview.failure());

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
		RegionCatalog.AdmissionResult exactPreview = regionManager.previewAdd(candidate);
		if (!exactPreview.accepted()) return ControllerActivation.failed(exactPreview.failure());
		try {
			regionTick.refreshRegionInputs(level, candidate);
			if (worldBridgeFeatureEnabled) FluidRegionFactory.captureVanillaWater(level, candidate);
			bindConfiguredState(candidate.domain());
		} catch (RuntimeException invalidCandidate) {
			return ControllerActivation.failed("无法构造完整控制器区域候选：" + invalidCandidate.getMessage());
		}
		candidate.setPaused(false);
		candidate.wakeForActivity();
		try {
			if (!creationPlan.profileFingerprint().equals(worldCapabilityProfile.profileFingerprint())
					|| !creationPlan.footprint().equals(candidate.footprint())) {
				throw new IOException("Controller creation proof changed after preflight");
			}
			if (worldBridgeFeatureEnabled) {
				regionOperations.acquireOwnership(level, candidate, creationPlan.ownershipPreimage());
			} else {
				regionOperations.checkpoint(candidate);
			}
		} catch (IOException | RuntimeException exception) {
			MmmMod.LOGGER.error("Controller region {} was rejected after its initial checkpoint failed", candidate.id(), exception);
			return ControllerActivation.failed("区域未创建；初始 checkpoint/ownership 事务失败：" + exception.getMessage());
		}
		RegionCatalog.AdmissionResult admission = regionManager.addAndSelect(candidate);
		if (!admission.accepted()) {
			String issue = "首个 region HEAD 已提交，但 live owner 登记违反已通过的 admission 预检：" + admission.failure();
			regionOperations.recordIssue(issue);
			MmmMod.LOGGER.error("Controller region {} requires restart after live registration failed: {}",
					candidate.id(), admission.failure());
			return ControllerActivation.failed(issue + "；请重启服务器恢复已提交区域");
		}
		sendSnapshot(level);
		return ControllerActivation.created(footprint);
	}

	/**
	 * Safely builds and activates the small persistent controller fixture used by container and
	 * chemistry smoke tests. The complete 6x6x6 occupied volume must already be loaded and air;
	 * failed placement or activation restores every block written by this method.
	 */
	public BlockPos createControllerDemo(ServerPlayer player) {
		final int interiorSize = 4;
		final int outerSize = interiorSize + 2;
		ServerLevel level = player.level();
		Direction facing = player.getDirection();
		BlockPos nearFace = player.blockPosition().relative(facing, 4).above(2);
		BlockPos minimum;
		BlockPos controller;
		if (facing.getAxis() == Direction.Axis.X) {
			int minimumX = facing == Direction.EAST ? nearFace.getX() : nearFace.getX() - outerSize + 1;
			minimum = new BlockPos(minimumX, player.blockPosition().getY(), nearFace.getZ() - 2);
			controller = new BlockPos(nearFace.getX(), minimum.getY() + 2, minimum.getZ() + 2);
		} else {
			int minimumZ = facing == Direction.SOUTH ? nearFace.getZ() : nearFace.getZ() - outerSize + 1;
			minimum = new BlockPos(nearFace.getX() - 2, player.blockPosition().getY(), minimumZ);
			controller = new BlockPos(minimum.getX() + 2, minimum.getY() + 2, nearFace.getZ());
		}

		for (int z = 0; z < outerSize; z++) for (int y = 0; y < outerSize; y++) {
			for (int x = 0; x < outerSize; x++) {
				BlockPos position = minimum.offset(x, y, z);
				if (!level.isInWorldBounds(position) || !level.isInsideBuildHeight(position)) {
					lastTakeoverFailure = "快速控制器超出世界高度/边界：" + position.toShortString();
					return null;
				}
				if (level.getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4) == null) {
					lastTakeoverFailure = "快速控制器需要已加载区块："
							+ (position.getX() >> 4) + "," + (position.getZ() >> 4);
					return null;
				}
				if (!level.getBlockState(position).isAir()) {
					lastTakeoverFailure = "快速控制器只替换空气；请清空 " + position.toShortString() + " 后重试";
					return null;
				}
			}
		}

		List<BlockPos> placed = new java.util.ArrayList<>();
		try {
			for (int z = 0; z < outerSize; z++) for (int y = 0; y < outerSize; y++) {
				for (int x = 0; x < outerSize; x++) {
					if (x != 0 && x != outerSize - 1 && y != 0 && y != outerSize - 1
							&& z != 0 && z != outerSize - 1) continue;
					BlockPos position = minimum.offset(x, y, z);
					BlockState state = position.equals(controller)
							? MmmBlocks.FLUID_CONTROLLER.defaultBlockState()
							: MmmBlocks.FLUID_BOUNDARY.defaultBlockState();
					if (!level.setBlock(position, state, Block.UPDATE_ALL)) {
						throw new IllegalStateException("无法放置壳块 " + position.toShortString());
					}
					placed.add(position.immutable());
				}
			}
		} catch (RuntimeException failure) {
			rollbackControllerDemoBlocks(level, placed);
			lastTakeoverFailure = "快速控制器已回滚：" + failure.getMessage();
			return null;
		}

		ControllerActivation activation;
		try {
			activation = activateController(player, controller);
		} catch (RuntimeException failure) {
			// An unchecked activation failure may have reached persistent region-state. Keep the
			// visible shell so recovery never has to guess whether removing it is safe.
			lastTakeoverFailure = "快速控制器激活异常；为保护可能已发布的 region-state，壳体已保留："
					+ failure.getMessage();
			return null;
		}
		if (!activation.succeeded()) {
			rollbackControllerDemoBlocks(level, placed);
			lastTakeoverFailure = "快速控制器已回滚：" + activation.failure();
			return null;
		}
		lastTakeoverFailure = null;
		return controller.immutable();
	}

	private static void rollbackControllerDemoBlocks(ServerLevel level, List<BlockPos> placed) {
		for (int index = placed.size() - 1; index >= 0; index--) {
			BlockPos position = placed.get(index);
			if (MmmBlocks.isBoundary(level.getBlockState(position))) {
				level.setBlock(position, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			}
		}
	}

	/** World blocks the airdemo glass scaffold replaced, for restoration; null dimension means no scaffold. */
	private final Map<BlockPos, BlockState> airDemoScaffolding = new HashMap<>();
	private ResourceKey<Level> airDemoScaffoldingDimension;

	public boolean createDemo(ServerPlayer player) {
		return createDemo(player, false);
	}

	public boolean createMaterialDemo(ServerPlayer player) {
		if (!worldCapabilityProfile.hasCapability(WorldCapability.MATERIALS)) {
			lastTakeoverFailure = "材料功能门未在本次启动启用；先保存配置并重启";
			return false;
		}

		return createDemo(player, true);
	}

	/** Creates a paused acid/base fixture without changing the mechanical demo geometry. */
	public boolean createChemistryDemo(ServerPlayer player) {
		if (!worldCapabilityProfile.hasCapability(WorldCapability.CHEMISTRY)) {
			lastTakeoverFailure = "化学功能门未在本次启动启用；先启用 materials/chemistry 并重启";
			return false;
		}
		if (!createDemo(player, false)) return false;
		FluidDomain domain = domain();
		float[] positionVolume = domain.exportChemicalParticleState().materials().copyPositionVolume();
		float divider = domain.sizeX() * domain.config().cellSize() / 5.0F;
		for (int marker = 0; marker < domain.particleCount(); marker++) {
			if (positionVolume[marker * 4] < divider) {
				domain.setMarkerChemical(marker, Map.of("mmm:hcl", 1_000_000L), true);
			} else {
				domain.setMarkerChemical(marker, Map.of("mmm:naoh", 1_000_000L), false);
			}
		}
		activeRegion().setPaused(true);
		sendSnapshot(player.level());
		return true;
	}

	public boolean createTensionDemo(ServerPlayer player, int angleDegrees) {
		if (!worldCapabilityProfile.hasCapability(WorldCapability.MATERIALS)) {
			lastTakeoverFailure = "材料功能门未在本次启动启用；先保存配置并重启";
			return false;
		}
		if (worldCapabilityProfile.hasCapability(WorldCapability.CHEMISTRY)) {
			lastTakeoverFailure = "表面张力测试材料没有 chemical phase；chemistry 开启时拒绝混用测试域";
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
		FluidDomain domain = domain();
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
			FluidDomain domain = domain();
			fillAirDemoWater(domain);
			bindConfiguredState(domain);
			stepMotionOnce(activeRegion(), List.of());
			domain = activeRegion().domain();
			GasPressureBoundary network = domain.gasBoundary();
			if (network.nodeCount() < 1) {
				lastTakeoverFailure = "左侧气腔未密闭；玻璃只替换空气方块，请清理模拟区域内的其他方块后重试";
				restoreAirDemoScaffolding(level.getServer());
				FluidRegion failedRegion = activeRegion();
				if (failedRegion != null && !failedRegion.controllerManaged()) {
					regionManager.remove(failedRegion.id());
					snapshots.sendRemoval(level.getServer(), failedRegion);
				}
				return false;
			}
			int node = network.nodeIds().get(0);
			// pressure adds inventory (p = P0(V0/V - 1) > 0), vacuum removes it; the soft gas spring
			// settles near a third of the preset head, so presets are scaled for a visible difference
			domain.setGasNodeStandardVolume(node, vacuum ? network.availableVolume(node) * 0.78
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
	private static void fillAirDemoWater(FluidDomain domain) {
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

	private static void bindTensionDemoMaterial(FluidDomain domain, int angleDegrees) {
		FlipParticleState pure = domain.exportParticleState();
		float[] positionVolume = pure.copyPositionVolume();
		float[] velocities = pure.copyParticleVelocities();
		double[] markerMass = new double[pure.particleCount()];
		int[] material = new int[pure.particleCount()];
		FluidMaterial tensionWater = new FluidMaterial("mmm:tension_water", domain.config().density(),
				"mmm:tension_water", 100.0, Math.toRadians(angleDegrees));
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
		RegionCatalog.AdmissionResult preview = regionManager.previewReplacement(
				COMPATIBILITY_REGION_CELLS, COMPATIBILITY_REGION_MARKERS);
		if (!preview.accepted()) throw new IllegalStateException("Compatibility fluid region rejected: " + preview.failure());
		FluidSolverConfig config = new FluidSolverConfig(
				0.5F, 1_000.0F, 9.81F, 1.0F / 40.0F,
				300, 1.0E-5F, 0.35F, 2.0E-3F, 0.80F
		);
		FluidDomain domain = new FluidDomain(
				BLOCK_SIZE_X * CELLS_PER_BLOCK, BLOCK_SIZE_Y * CELLS_PER_BLOCK, BLOCK_SIZE_Z * CELLS_PER_BLOCK,
				config, Math.toIntExact(COMPATIBILITY_REGION_MARKERS)
		);
		FluidRegion candidate = new FluidRegion(
				UUID.randomUUID(), level.dimension(), origin, domain, COMPATIBILITY_REGION_MARKERS
		);
		candidate.setEntityPhysicsEnabled(defaultEntityPhysicsEnabled);
		candidate.setDebugOverlays(defaultDebugOverlays);
		RegionCatalog.AdmissionResult admission = regionManager.replaceSelected(candidate);
		if (!admission.accepted()) throw new IllegalStateException("Compatibility fluid region rejected: " + admission.failure());
		if (replaced != null) snapshots.sendRemoval(level.getServer(), replaced);
	}

	private static void restoreTensionCap(FluidDomain domain, int angleDegrees) {
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
				sx, sy, sz, cellSize, particleCount, totalVolume, positionVolume, velocities,
				domain.copyCollisionMicroMasks()
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

		FluidDomain domain = domain();
		int fillX = domain.sizeX() * 2 / 5;
		int fillY = domain.sizeY() * 3 / 4;
		for (int z = 0; z < domain.sizeZ(); z++) {
			for (int y = 0; y < fillY; y++) {
				for (int x = 0; x < fillX; x++) {
					if (!domain.isSolid(x, y, z)) domain.setVolume(x, y, z, 1.0F);
				}
			}
		}
		if (worldCapabilityProfile.hasCapability(WorldCapability.MATERIALS)) bindDefaultMaterialState(domain);
		if (layeredMaterials) assignLayeredDemoMaterials(domain, fillY / 2.0F * domain.config().cellSize());
		bindConfiguredState(domain);
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
		bindConfiguredState(activeRegion().domain());
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
			MmmMod.LOGGER.error("Refusing water takeover; no recovery file will be overwritten", exception);
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
				MmmMod.LOGGER.error(lastTakeoverFailure);
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
			MmmMod.LOGGER.error("Could not finish the Matter, Motion & Mechanics takeover recovery; recovery files were preserved", exception);
			return RestorationStats.failed(
					true,
					exception.restoredBlocks(),
					exception.alreadyRestoredBlocks(),
					exception.skippedBlocks(),
					lastTakeoverFailure
			);
		} catch (IOException | RuntimeException exception) {
			lastTakeoverFailure = "恢复未完成，journal 已保留并锁定新 takeover：" + exception.getMessage();
			MmmMod.LOGGER.error("Could not recover the unfinished Matter, Motion & Mechanics takeover; recovery files were preserved", exception);
			return RestorationStats.failed(TakeoverBackupStore.isLocked(server), 0, 0, 0, lastTakeoverFailure);
		}
	}

	private void createEmpty(ServerLevel level, BlockPos origin) {
		FluidRegion replaced = activeRegion();
		if (replaced != null && replaced.controllerManaged()) {
			throw new IllegalStateException("Refusing to replace a persistent controller region; remove it explicitly first");
		}
		RegionCatalog.AdmissionResult preview = regionManager.previewReplacement(
				COMPATIBILITY_REGION_CELLS, COMPATIBILITY_REGION_MARKERS);
		if (!preview.accepted()) throw new IllegalStateException("Compatibility fluid region rejected: " + preview.failure());
		FluidRegion candidate = FluidRegionFactory.createCommandRegion(
				level, origin, BLOCK_SIZE_X, BLOCK_SIZE_Y, BLOCK_SIZE_Z,
				CELLS_PER_BLOCK, COMPATIBILITY_REGION_MARKERS
		);
		candidate.setEntityPhysicsEnabled(defaultEntityPhysicsEnabled);
		candidate.setDebugOverlays(defaultDebugOverlays);
		RegionCatalog.AdmissionResult admission = regionManager.replaceSelected(candidate);
		if (!admission.accepted()) throw new IllegalStateException("Compatibility fluid region rejected: " + admission.failure());
		if (replaced != null) snapshots.sendRemoval(level.getServer(), replaced);
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
		cleanupContainerTransfers(server);

		if (takeoverDimension != null) {
			ServerLevel takeoverLevel = server.getLevel(takeoverDimension);
			if (takeoverLevel != null) maintainTakeover(takeoverLevel);
		}
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
			region.setAvailabilityFailure(RegionAvailabilityReason.PENDING_WAL,
					"存在未完成容器转移 WAL；恢复完成后才能继续");
			return false;
		}

		boolean available = regionOperations.ensureAvailable(
				level, region, controllerFeatureEnabled, regionTick::refreshRegionInputs, this::sendSnapshot,
				snapshots::sendRemoval
		);
		if (available && worldCapabilityProfile.hasCapability(WorldCapability.THERMAL) && region.solverLoaded() && region.domain().hasThermalState()) {
			region.enableThermalActivity();
			if (region.domain().hasPendingPhaseVolumeWork()) region.wakeForActivity();
		}
		return available;
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
				worldCapabilityProfile.hasCapability(WorldCapability.PHASECHANGE),
				worldCapabilityProfile.hasCapability(WorldCapability.PHASECHANGE)
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
		float requested = buckets * CELL_VOLUMES_PER_BLOCK;
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
		float beforeBlocks = domain.totalVolume() / CELL_VOLUMES_PER_BLOCK;
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
		String takeoverLock = TakeoverBackupStore.lockDescription(server);
		RegionCatalog.BudgetUsage usage = regionManager.usage();
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
		String thermal = worldCapabilityProfile.hasCapability(WorldCapability.THERMAL) ? thermalSummary(region, domain) : "";
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
				cellVolumes / CELL_VOLUMES_PER_BLOCK, cellVolumes,
				domain.particleCount(), domain.maximumParticleCount(), domain.markerCellCount(),
				distribution.visibleCells(), distribution.denseCells(), takeoverOriginalBlocks.size(), takeoverLock,
				worldBridgeFeatureEnabled ? "on" : "off",
				region.entityPhysicsEnabled() ? "on" : "off", region.lastAffectedEntities(),
				entityCoupling.impulseXKilogramMetresPerSecond(),
				entityCoupling.impulseYKilogramMetresPerSecond(),
				entityCoupling.impulseZKilogramMetresPerSecond(),
				domain.maximumPressure(),
				solids.partialCells(), solids.fullCells(),
				collision.changedCells(), collision.escapedCellVolume() / CELL_VOLUMES_PER_BLOCK,
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

	/** Returns the sole region whose actual interior contains the block position. */
	public FluidRegion regionAtBlock(ServerLevel level, BlockPos pos) {
		return targets.regionAtBlock(level, pos);
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

	private record ControllerCreationPlan(
			String profileFingerprint,
			FluidRegionFootprint footprint,
			List<OwnershipJournal.BlockEntry> ownershipPreimage
	) {
		private ControllerCreationPlan {
			if (profileFingerprint == null || !profileFingerprint.matches("[0-9a-f]{64}")) {
				throw new IllegalArgumentException("Controller creation profile fingerprint is invalid");
			}
			java.util.Objects.requireNonNull(footprint, "footprint");
			ownershipPreimage = List.copyOf(ownershipPreimage);
		}
	}


	/** Applies the bounded indicator/eraser primitive to the actually aimed region. */
	public IndicatorUseResult editIndicatorAtAim(ServerPlayer player, boolean indicated) {
		if (!worldCapabilityProfile.hasCapability(WorldCapability.CHEMISTRY)) return IndicatorUseResult.rejected("化学功能门本次启动未开启");
		FluidRegion region = aimedRegion(player);
		if (region == null) return IndicatorUseResult.rejected("准星方向没有化学流体区域");
		if (!ensureRegionAvailableFor(player, region)) {
			return IndicatorUseResult.rejected("目标控制器区域当前不可用：" + region.availabilityFailure());
		}
		int[] cell = aimedCellNear(player, region, true);
		if (cell == null) return IndicatorUseResult.rejected("准星方向没有化学液体");
		FluidDomain.IndicatorEditResult edit = region.domain().editIndicatorFloodFill(
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
					MmmMod.LOGGER.error("Could not checkpoint indicator edit for region {}", region.id(), failure);
				}
			}
		}
		sendSnapshot(player.level(), region);
		return new IndicatorUseResult(true, edit.scannedCells(), edit.changedMarkers(),
				edit.affectedBlockVolume(), checkpointWarning, null);
	}

	/** Administrator-only command payload; caller is responsible for enforcing permission and transcript logging. */
	public String chemistryInventoryStatus(ServerLevel level) {
		if (!worldCapabilityProfile.hasCapability(WorldCapability.CHEMISTRY)) return "化学功能门本次启动未开启";
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

	/** Administrator-only exact granular inventory/audit report; display snapshots never feed this path. */
	public String granularInventoryStatus(ServerLevel level) {
		if (!worldCapabilityProfile.hasCapability(WorldCapability.GRANULAR)) return "颗粒功能门本次启动未开启";
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

	public void sendSnapshots(ServerPlayer player) {
		for (FluidRegion region : regionManager.regions()) {
			snapshots.sendTo(player, region);
		}
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

	public String lastTakeoverFailure() {
		return lastTakeoverFailure;
	}

	public record CaptureResult(int wetCells, int removedVanillaBlocks, String failure) { }

	public record SelectionResult(boolean succeeded, UUID regionId, String failure) {
		static SelectionResult failed(String failure) { return new SelectionResult(false, null, failure); }
	}

	private static void bindDefaultMaterialState(FluidDomain domain) {
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
				BuiltinFluidMaterials.DEFAULT_PALETTE, positionVolume, pure.copyParticleVelocities(), pure.copySolidMask(), markerMass, material
		));
	}

	private void bindConfiguredState(FluidDomain domain) {
		if (worldCapabilityProfile.hasCapability(WorldCapability.MATERIALS)) bindDefaultMaterialState(domain);
		if (worldCapabilityProfile.hasCapability(WorldCapability.CHEMISTRY) && !domain.hasChemistryState()) domain.bindChemistry(chemicalRegistry);
		if (worldCapabilityProfile.hasCapability(WorldCapability.GRANULAR) && !domain.hasGranularState()) {
			domain.bindGranular(granularRegistry, domain.materialRegistry());
		}
		if (worldCapabilityProfile.hasCapability(WorldCapability.PRECIPITATION)) domain.setPrecipitationEnabled(true);
		if (worldCapabilityProfile.hasCapability(WorldCapability.THERMAL) && !domain.hasThermalState()) {
			domain.bindGasRegistry(gasRegistry);
			domain.bindThermal(thermalRegistry, domain.materialRegistry(), GasRegistry.STANDARD_TEMPERATURE_K,
					serverConfig.maximumThermalSubstepsPerStep());
		}
	}

	private static void assignLayeredDemoMaterials(FluidDomain domain, float interfaceY) {
		FlipMaterialParticleState state = domain.exportMaterialParticleState();
		float[] positionVolume = state.copyPositionVolume();
		double[] markerMass = state.copyParticleMass();
		int[] materials = state.copyMaterialPaletteIndex();
		double totalMass = 0.0;
		for (int marker = 0; marker < state.particleCount(); marker++) {
			int material = positionVolume[marker * 4 + 1] >= interfaceY ? 1 : 0;
			materials[marker] = material;
			markerMass[marker] = positionVolume[marker * 4 + 3]
					* BuiltinFluidMaterials.DEFAULT_PALETTE.get(material).referenceDensity();
			totalMass += markerMass[marker];
		}
		domain.restoreMaterialParticleState(new FlipMaterialParticleState(
				state.sizeX(), state.sizeY(), state.sizeZ(), state.cellSize(), state.particleCount(), state.totalVolume(), totalMass,
				state.materials(), positionVolume, state.copyParticleVelocities(), state.copySolidMask(), markerMass, materials
		));
	}

	public record ControllerActivation(
			boolean succeeded,
			boolean disabled,
			ControllerAction action,
			String failure,
			int interiorBlocks,
			int sizeX,
			int sizeY,
			int sizeZ,
			int requiredChunks
	) {
		private static final ControllerActivation DISABLED = new ControllerActivation(
				false, true, null, "controller feature flag is disabled", 0, 0, 0, 0, 0
		);

		private static ControllerActivation failed(String failure) {
			return new ControllerActivation(false, false, null, failure, 0, 0, 0, 0, 0);
		}

		private static ControllerActivation created(FluidRegionFootprint footprint) {
			return succeeded(footprint, ControllerAction.CREATED);
		}

		private static ControllerActivation succeeded(FluidRegionFootprint footprint, ControllerAction action) {
			return new ControllerActivation(
					true, false, action, null, footprint.interiorBlocks().size(),
					footprint.blockSizeX(), footprint.blockSizeY(), footprint.blockSizeZ(),
					footprint.requiredChunks().size()
			);
		}
	}

	public enum ControllerAction {
		CREATED,
		SELECTED_RUNNING,
		SELECTED_PAUSED,
		PAUSED,
		RESUMED
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
