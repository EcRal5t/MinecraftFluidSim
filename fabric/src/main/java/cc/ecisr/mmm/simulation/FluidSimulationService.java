package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.MmmMod;
import cc.ecisr.mmm.api.query.MmmQueryApi;
import cc.ecisr.mmm.api.event.ContainerTransferCommittedEvent;
import cc.ecisr.mmm.api.event.MmmEventApi;
import cc.ecisr.mmm.api.transfer.TransferQuantity;
import cc.ecisr.mmm.block.MmmBlocks;
import cc.ecisr.mmm.chemistry.ChemicalDataPackLoader;
import cc.ecisr.mmm.chemistry.ChemicalLimits;
import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.chemistry.ChemicalReactionEngine;
import cc.ecisr.mmm.fluid.BuiltinFluidMaterials;
import cc.ecisr.mmm.fluid.FlipFluidDomain;
import cc.ecisr.mmm.fluid.FlipMaterialParticleState;
import cc.ecisr.mmm.fluid.FlipParticleState;
import cc.ecisr.mmm.fluid.FluidMaterial;
import cc.ecisr.mmm.fluid.FluidMaterialRegistry;
import cc.ecisr.mmm.fluid.MaterialRegistry;
import cc.ecisr.mmm.fluid.FluidSolverConfig;
import cc.ecisr.mmm.fluid.air.AirNetwork;
import cc.ecisr.mmm.fluid.air.BuiltinGasDefinitions;
import cc.ecisr.mmm.fluid.air.GasAuditLedger;
import cc.ecisr.mmm.fluid.air.GasRegistry;
import cc.ecisr.mmm.granular.GranularDataPackLoader;
import cc.ecisr.mmm.granular.GranularMechanics;
import cc.ecisr.mmm.granular.GranularPhaseTransferEngine;
import cc.ecisr.mmm.granular.GranularRegistry;
import cc.ecisr.mmm.transfer.ChemicalContents;
import cc.ecisr.mmm.transfer.ContainerTransferActor;
import cc.ecisr.mmm.transfer.ContainerTransferJournal;
import cc.ecisr.mmm.transfer.FluidMixture;
import cc.ecisr.mmm.item.ContainerItemStacks;
import cc.ecisr.mmm.item.MmmComponents;
import cc.ecisr.mmm.transfer.ThermalContents;
import cc.ecisr.mmm.simulation.controller.FluidBoundaryScanner;
import cc.ecisr.mmm.simulation.controller.FluidControllerRegions;
import cc.ecisr.mmm.simulation.controller.FluidRegionFootprint;
import cc.ecisr.mmm.simulation.persistence.WorldCapabilityBundleStore;
import cc.ecisr.mmm.simulation.persistence.WorldCapabilityLayoutResolver;
import cc.ecisr.mmm.simulation.persistence.WorldCapabilityStartup;
import cc.ecisr.mmm.thermal.ThermalDataPackLoader;
import cc.ecisr.mmm.thermal.ThermalAuditLedger;
import cc.ecisr.mmm.thermal.ThermalMaterialDefinition;
import cc.ecisr.mmm.thermal.LiquidThermalExpansion;
import cc.ecisr.mmm.thermal.ThermalRegistry;
import cc.ecisr.mmm.thermal.ThermalDisplaySnapshot;
import cc.ecisr.mmm.thermal.ThermalStateFunction;
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
import java.util.OptionalDouble;
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
	private static final int THERMAL_QUIET_STEPS = 200;
	private static final float HYDROSTATIC_MAX_MARKER_SPEED = 2.5E-3F;
	private static final long COMPATIBILITY_REGION_CELLS = (long) BLOCK_SIZE_X * BLOCK_SIZE_Y * BLOCK_SIZE_Z
			* CELLS_PER_BLOCK * CELLS_PER_BLOCK * CELLS_PER_BLOCK;
	private static final long COMPATIBILITY_REGION_MARKERS = COMPATIBILITY_REGION_CELLS * 8L;
	private static final Map<MinecraftServer, FluidSimulationService> SERVICES = new WeakHashMap<>();
	private static final Map<MinecraftServer, CommittedEventHub> COMMITTED_EVENTS = new WeakHashMap<>();

	private final Map<BlockPos, BlockState> takeoverOriginalBlocks = new HashMap<>();
	private final Path worldRoot;
	private final boolean serverConfigLocked;
	private FluidServerConfig serverConfig;
	private final FluidRegionManager regionManager;
	private final CommittedEventHub committedEvents;
	private final FluidWorldTargetResolver targets;
	private final FluidRegionOperations regionOperations;
	private final FluidSnapshotPublisher snapshots;
	private final ContainerTransferStore containerTransfers;
	private final MinecraftContainerActorAccess containerActors = new MinecraftContainerActorAccess();
	/** Boot-decoded WAL truth; normal ticks never rescan or reread the transfer directory. */
	private final Map<UUID, ContainerTransferJournal> indexedContainerTransfers = new HashMap<>();
	private final Set<UUID> auditedContainerTransfers = new HashSet<>();
	private final Set<UUID> awaitingContainerActors = new HashSet<>();
	private Set<String> indexedContainerTransferIssues = Set.of();
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
	private final WorldCapabilityStartup.Result capabilityStartup;
	private final WorldCapabilityProfile capabilityProfile;
	private final MaterialRegistry materialRegistryDefinitions;
	private final FluidMaterialRegistry materialRegistry;
	private final boolean materialFeatureEnabled;
	private final boolean worldBridgeFeatureEnabled;
	private final boolean chemistryFeatureEnabled;
	private final ChemicalRegistry chemicalRegistry;
	private final boolean granularFeatureEnabled;
	private final boolean precipitationFeatureEnabled;
	private final GranularRegistry granularRegistry;
	private final boolean thermalFeatureEnabled;
	private final boolean phaseChangeFeatureEnabled;
	private final ThermalRegistry thermalRegistry;
	private final GasRegistry gasRegistry;
	private final ThermalBoundaryResolver thermalBoundaryResolver;
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
			MmmMod.LOGGER.error("Could not load Matter, Motion & Mechanics world config; controllers remain disabled", exception);
		}
		WorldCapabilityBundleStore capabilityStore = new WorldCapabilityBundleStore(worldRoot,
				new WorldCapabilityBundleStore.QuarantineAdmission(serverConfig.maximumQuarantineEntries(),
						serverConfig.maximumQuarantineBytes()));
		capabilityStartup = WorldCapabilityStartup.start(capabilityStore,
				() -> loadCurrentCapabilityDefinitions(server), !configurationLocked);
		WorldCapabilityBundleStore.Bundle bundle = capabilityStartup.bundle();
		capabilityProfile = bundle == null ? null : bundle.profile();
		if (capabilityProfile != null) WorldCapabilityLayoutResolver.resolve(capabilityProfile);
		materialRegistryDefinitions = bundle == null ? null : bundle.material();
		materialRegistry = materialRegistryDefinitions == null ? null : materialRegistryDefinitions.runtimeRegistry();
		serverConfigLocked = configurationLocked;
		controllerFeatureEnabled = !configurationLocked && serverConfig.controllersEnabled();
		worldBridgeFeatureEnabled = !configurationLocked && serverConfig.worldBridgeEnabled();
		materialFeatureEnabled = hasCapability(WorldCapability.MATERIALS);
		chemicalRegistry = bundle == null ? null : bundle.chemical();
		chemistryFeatureEnabled = hasCapability(WorldCapability.CHEMISTRY);
		granularRegistry = bundle == null ? null : bundle.granular();
		granularFeatureEnabled = hasCapability(WorldCapability.GRANULAR);
		precipitationFeatureEnabled = hasCapability(WorldCapability.PRECIPITATION);
		gasRegistry = bundle == null ? null : bundle.gas();
		thermalRegistry = bundle == null ? null : bundle.thermal();
		thermalBoundaryResolver = thermalRegistry == null ? null : new ThermalBoundaryResolver(thermalRegistry);
		thermalFeatureEnabled = hasCapability(WorldCapability.THERMAL);
		phaseChangeFeatureEnabled = hasCapability(WorldCapability.PHASECHANGE);
		regionManager = new FluidRegionManager(serverConfig.limits());
		committedEvents = committedEventHub(server);
		targets = new FluidWorldTargetResolver(regionManager);
		regionOperations = new FluidRegionOperations(worldRoot, regionManager,
				capabilityStartup.state() != WorldCapabilityStartup.State.READY, materialFeatureEnabled,
				chemistryFeatureEnabled, chemicalRegistry, granularFeatureEnabled, granularRegistry,
				precipitationFeatureEnabled, thermalFeatureEnabled, thermalRegistry, gasRegistry,
				serverConfig.maximumThermalSubstepsPerStep(),
				materialRegistry == null ? null : materialRegistry.materials(), capabilityProfile, true);
		snapshots = new FluidSnapshotPublisher(materialFeatureEnabled, chemistryFeatureEnabled, chemicalRegistry,
				granularFeatureEnabled, thermalFeatureEnabled, thermalRegistry);
		containerTransfers = new ContainerTransferStore(worldRoot);
		loadContainerTransferIndex();
		if (configurationLocked) regionOperations.recordIssue(
				"server.properties 不可用，已安全使用默认预算并关闭控制器；原文件未覆盖");
		if (capabilityStartup.state() != WorldCapabilityStartup.State.READY) {
			regionOperations.recordIssue("world capability startup " + capabilityStartup.state()
					+ "：" + capabilityStartup.issue());
			MmmMod.LOGGER.error("Matter, Motion & Mechanics world capability startup is {}: {}",
					capabilityStartup.state(), capabilityStartup.issue());
		} else if (capabilityStartup.definitionMismatch()) {
			MmmMod.LOGGER.warn("Current datapack definitions differ from committed world capability profile {}; bundle snapshots remain authoritative",
					capabilityProfile.profileFingerprint());
		} else if (!capabilityStartup.issue().isEmpty()) {
			MmmMod.LOGGER.warn("Current datapack definitions could not be compared with committed world capability profile {}: {}",
					capabilityProfile.profileFingerprint(), capabilityStartup.issue());
		}
		ownershipIssues = regionOperations.scanOwnershipIssues();
		for (String issue : ownershipIssues) MmmMod.LOGGER.error("Matter, Motion & Mechanics world ownership issue: {}", issue);
	}

	private boolean hasCapability(WorldCapability capability) {
		return capabilityProfile != null && capabilityProfile.hasCapability(capability);
	}

	private static WorldCapabilityBundleStore.Definitions loadCurrentCapabilityDefinitions(MinecraftServer server)
			throws IOException {
		MaterialRegistry material = BuiltinFluidMaterials.createRegistry();
		ChemicalRegistry chemical = ChemicalDataPackLoader.load(server.getResourceManager());
		validateSupportedChemicalMaterials(chemical, material);
		GranularRegistry granular = GranularDataPackLoader.load(server.getResourceManager(), chemical);
		validateSupportedGranularCarriers(granular, material);
		GasRegistry gas = BuiltinGasDefinitions.createRegistry();
		validateSupportedGasComponents(gas, chemical);
		ThermalRegistry thermal = ThermalDataPackLoader.load(server.getResourceManager(), chemical.fingerprint(),
				granular.fingerprint(), gas.fingerprint());
		validateSupportedThermalCarriers(thermal, material, chemical, granular, gas);
		return new WorldCapabilityBundleStore.Definitions(material, chemical, granular, gas, thermal);
	}

	private static void validateSupportedChemicalMaterials(ChemicalRegistry registry, MaterialRegistry materials) {
		for (var entry : materials.entries()) registry.phaseHandleForMaterial(entry.material().id());
	}

	private static void validateSupportedGranularCarriers(GranularRegistry registry, MaterialRegistry materials) {
		for (var entry : materials.entries()) {
			if (!registry.hasCarrier(entry.material().id())) throw new IllegalArgumentException(
					"Granular registry has no carrier for " + entry.material().id());
		}
	}

	private static void validateSupportedGasComponents(GasRegistry registry, ChemicalRegistry chemicalRegistry) {
		registry.component(BuiltinGasDefinitions.AIR);
		registry.component(BuiltinGasDefinitions.WATER_VAPOR);
		for (var component : registry.components()) for (String species : component.allowedSpecies()) {
			if (chemicalRegistry != null && !chemicalRegistry.hasSpecies(species)) {
				throw new IllegalArgumentException("Gas component " + component.id()
						+ " allows unavailable chemical species " + species);
			}
		}
	}

	private static void validateSupportedThermalCarriers(
			ThermalRegistry registry, MaterialRegistry materials, ChemicalRegistry chemicalRegistry,
			GranularRegistry granularRegistry, GasRegistry gasRegistry
	) {
		for (var entry : materials.entries()) {
			registry.materialHandle(ThermalMaterialDefinition.CarrierKind.LIQUID, entry.material().id());
			LiquidThermalExpansion.require(entry.material().id());
		}
		if (granularRegistry != null) for (var material : granularRegistry.materials()) {
			registry.materialHandle(ThermalMaterialDefinition.CarrierKind.GRANULAR, material.id());
		}
		for (var component : gasRegistry.components()) {
			int host = registry.materialHandle(ThermalMaterialDefinition.CarrierKind.GAS, component.id());
			if (chemicalRegistry != null) for (String species : component.allowedSpecies()) {
				registry.speciesOffset(host, species);
			}
		}
		if (chemicalRegistry != null) for (var phase : chemicalRegistry.phases()) {
			int host = registry.materialHandle(ThermalMaterialDefinition.CarrierKind.LIQUID, phase.materialId());
			for (String species : phase.allowedSpecies()) registry.speciesOffset(host, species);
		}
		if (granularRegistry != null) for (var reaction : granularRegistry.reactions()) {
			int host = registry.materialHandle(ThermalMaterialDefinition.CarrierKind.GRANULAR,
					reaction.solidMaterialId());
			for (String species : reaction.solidComposition().keySet()) registry.speciesOffset(host, species);
		}
		if (!registry.gasLiquidPairs().isEmpty() && chemicalRegistry == null) {
			throw new IllegalArgumentException("Gas/liquid species pairs require a chemical registry");
		}
		for (var pair : registry.gasLiquidPairs()) {
			var component = gasRegistry.component(pair.gasComponentId());
			if (!component.allowsSpecies(pair.speciesId())) throw new IllegalArgumentException(
					"Gas/liquid pair " + pair.id() + " maps species outside gas component " + component.id());
			var phase = chemicalRegistry.phase(chemicalRegistry.phaseHandle(pair.liquidPhaseId()));
			if (!phase.allowedSpecies().contains(pair.speciesId())) throw new IllegalArgumentException(
					"Gas/liquid pair " + pair.id() + " maps species outside liquid phase " + phase.id());
			int gasHost = registry.materialHandle(ThermalMaterialDefinition.CarrierKind.GAS, component.id());
			int liquidHost = registry.materialHandle(ThermalMaterialDefinition.CarrierKind.LIQUID, phase.materialId());
			registry.speciesOffset(gasHost, pair.speciesId());
			registry.speciesOffset(liquidHost, pair.speciesId());
			double mappedGasKilograms = component.standardMassPerCubicMeter()
					* AirNetwork.cubicMeters(pair.gasStandardVolumeNanosPerBatch());
			if (Double.doubleToRawLongBits(mappedGasKilograms)
					!= Double.doubleToRawLongBits(pair.gasKilogramsPerBatch())) {
				throw new IllegalArgumentException("Gas/liquid pair " + pair.id()
						+ " gas nanos and kilograms do not close at the gas standard state");
			}
			double mappedLiquidKilograms = chemicalRegistry.species(
					chemicalRegistry.speciesHandle(pair.speciesId())).massKilogramsPerAu()
					.multiply(pair.liquidQuantaPerBatch()).divide(ChemicalLimits.QUANTA_PER_AU).toDouble();
			if (Double.doubleToRawLongBits(mappedLiquidKilograms)
					!= Double.doubleToRawLongBits(pair.liquidKilogramsPerBatch())) {
				throw new IllegalArgumentException("Gas/liquid pair " + pair.id()
						+ " liquid quanta and kilograms do not close against the chemical species mass");
			}
		}
	}

	void writeContainerTransfer(ContainerTransferJournal journal) throws IOException {
		containerTransfers.write(journal);
		indexedContainerTransfers.put(journal.transactionId(), journal);
		auditedContainerTransfers.remove(journal.transactionId());
		awaitingContainerActors.remove(journal.transactionId());
	}

	void deleteContainerTransfer(UUID transactionId) throws IOException {
		containerTransfers.delete(transactionId);
		indexedContainerTransfers.remove(transactionId);
		auditedContainerTransfers.remove(transactionId);
		awaitingContainerActors.remove(transactionId);
	}

	private void loadContainerTransferIndex() {
		indexedContainerTransfers.clear();
		auditedContainerTransfers.clear();
		awaitingContainerActors.clear();
		try {
			ContainerTransferStore.Scan scan = containerTransfers.scan();
			HashSet<String> issues = new HashSet<>(scan.issues());
			for (UUID transactionId : scan.transactions()) try {
				ContainerTransferJournal journal = containerTransfers.read(transactionId);
				indexedContainerTransfers.put(transactionId, journal);
			} catch (IOException | RuntimeException unreadable) {
				issues.add("unreadable indexed container-transfer WAL is preserved: "
						+ transactionId + " (" + unreadable.getMessage() + ")");
			}
			indexedContainerTransferIssues = Set.copyOf(issues);
		} catch (IOException | RuntimeException scanFailure) {
			indexedContainerTransferIssues = Set.of(
					"container-transfer boot scan failed: " + scanFailure.getMessage());
		}
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
		if (!indexedContainerTransferIssues.isEmpty()) return true;
		return indexedContainerTransfers.values().stream().anyMatch(journal -> journal.regionId().equals(regionId));
	}

	boolean hasPendingContainerTransfer(UUID regionId, ContainerTransferActor actor) {
		if (!indexedContainerTransferIssues.isEmpty()) return true;
		return indexedContainerTransfers.values().stream().anyMatch(journal ->
				journal.regionId().equals(regionId) || journal.actor().equals(actor));
	}

	boolean hasAnyContainerTransferLock() {
		return !indexedContainerTransferIssues.isEmpty() || !indexedContainerTransfers.isEmpty();
	}

	boolean hasContainerTransferStoreIssues() {
		return !indexedContainerTransferIssues.isEmpty();
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
		if (!indexedContainerTransferIssues.isEmpty()) {
			Set<String> currentIssues = indexedContainerTransferIssues;
			for (String issue : currentIssues) {
				if (!reportedContainerTransferStoreIssues.contains(issue)) {
					MmmMod.LOGGER.error("Container-transfer recovery store is globally LOCKED: {}", issue);
				}
			}
			reportedContainerTransferStoreIssues = currentIssues;
			return;
		}
		reportedContainerTransferStoreIssues = Set.of();
		reportedContainerTransferLocks.keySet().retainAll(indexedContainerTransfers.keySet());
		for (UUID transactionId : List.copyOf(indexedContainerTransfers.keySet())) {
			if (awaitingContainerActors.contains(transactionId)) {
				convergeAwaitingContainerActor(server, transactionId);
			} else if (!auditedContainerTransfers.contains(transactionId)) {
				processContainerTransfer(server, transactionId);
			}
		}
	}

	void cleanupContainerTransfers(MinecraftServer server) {
		recoverContainerTransfers(server);
	}

	private void processContainerTransfer(MinecraftServer server, UUID transactionId) {
		try {
			ContainerTransferJournal journal = indexedContainerTransfers.get(transactionId);
			if (journal == null) return;
			if (journal.requiresThermalRegistry()) {
				if (!thermalFeatureEnabled || thermalRegistry == null) {
					auditedContainerTransfers.add(transactionId);
					logContainerTransferLockOnce(transactionId,
							"Container transfer WAL " + transactionId
									+ " remains locked: thermal recovery requires the enabled authoritative world registry");
					return;
				}
				try {
					journal.validateThermalRegistry(thermalRegistry.fingerprint());
				} catch (RuntimeException foreignRegistry) {
					auditedContainerTransfers.add(transactionId);
					logContainerTransferLockOnce(transactionId,
							"Container transfer WAL " + transactionId
									+ " remains locked: thermal item registry identity is foreign or invalid ("
									+ foreignRegistry.getMessage() + ")");
					return;
				}
			}
			if (journal.requiresGranularRegistry()) {
				if (!granularFeatureEnabled || granularRegistry == null) {
					auditedContainerTransfers.add(transactionId);
					logContainerTransferLockOnce(transactionId,
							"Container transfer WAL " + transactionId
									+ " remains locked: granular recovery requires the enabled authoritative world registry");
					return;
				}
				try {
					journal.validateGranularRegistry(granularRegistry,
							chemistryFeatureEnabled ? chemicalRegistry : null);
				} catch (RuntimeException foreignRegistry) {
					auditedContainerTransfers.add(transactionId);
					logContainerTransferLockOnce(transactionId,
							"Container transfer WAL " + transactionId
									+ " remains locked: granular item registry identity is foreign or invalid ("
									+ foreignRegistry.getMessage() + ")");
					return;
				}
			}
			if (journal.requiresChemicalRegistry()) {
				if (!chemistryFeatureEnabled || chemicalRegistry == null) {
					auditedContainerTransfers.add(transactionId);
					logContainerTransferLockOnce(transactionId,
							"Container transfer WAL " + transactionId
									+ " remains locked: chemical recovery requires the enabled authoritative world registry");
					return;
				}
				try {
					journal.validateChemicalRegistry(chemicalRegistry);
				} catch (RuntimeException foreignRegistry) {
					auditedContainerTransfers.add(transactionId);
					logContainerTransferLockOnce(transactionId,
							"Container transfer WAL " + transactionId
									+ " remains locked: chemical item registry identity is foreign or invalid ("
									+ foreignRegistry.getMessage() + ")");
					return;
				}
			}
			long head = committedRegionGeneration(journal.regionId());
			long headChecksum = committedRegionManifestChecksum(journal.regionId());
			ContainerTransferRecovery.Decision decision = ContainerTransferRecovery.decide(
					head, headChecksum, journal, runtimeDirtyContainerTransfers.contains(transactionId));
			switch (decision) {
				case LOCK_LIVE_SOLVER_DIRTY -> {
					auditedContainerTransfers.add(transactionId);
					logContainerTransferLockOnce(transactionId,
							"Container transfer WAL " + transactionId + " remains locked: its live solver changed but region "
									+ journal.regionId() + " HEAD is still " + head
									+ "; restart is required to restore the base checkpoint");
				}
				case DISCARD_UNCOMMITTED -> {
					deleteContainerTransfer(transactionId);
					reportedContainerTransferLocks.remove(transactionId);
					MmmMod.LOGGER.warn("Discarded uncommitted container transfer WAL {} for region {}", transactionId, journal.regionId());
				}
				case CONVERGE_AFTER_ITEM -> {
					switch (containerActors.convergeAfterItem(server, journal)) {
						case APPLIED -> {
							completeContainerTransferCommit(server, journal);
							MmmMod.LOGGER.warn("Converged container transfer WAL {} to a durably saved after item", transactionId);
						}
						case UNAVAILABLE -> awaitingContainerActors.add(transactionId);
						case CONFLICT -> lockContainerActorConflict(transactionId, journal);
					}
				}
				case LOCK_TARGET_WITHOUT_AFTER_ITEM -> {
					auditedContainerTransfers.add(transactionId);
					logContainerTransferLockOnce(transactionId,
							"Container transfer WAL " + transactionId + " is locked: region " + journal.regionId()
								+ " HEAD committed target " + head + ", but WAL phase " + journal.phase()
								+ " contains no authoritative after-item");
				}
				case LOCK_CHECKSUM_MISMATCH -> {
					auditedContainerTransfers.add(transactionId);
					logContainerTransferLockOnce(transactionId,
							"Container transfer WAL " + transactionId + " is locked: target generation checksum "
								+ journal.targetManifestChecksum() + " does not match HEAD " + headChecksum);
				}
				case LOCK_GENERATION_MISMATCH -> {
					auditedContainerTransfers.add(transactionId);
					logContainerTransferLockOnce(transactionId,
							"Container transfer WAL " + transactionId + " is locked: region " + journal.regionId()
								+ " HEAD " + head + " is neither base " + journal.baseGeneration()
								+ " nor target " + journal.targetGeneration());
				}
			}
		} catch (IOException | RuntimeException exception) {
			auditedContainerTransfers.add(transactionId);
			MmmMod.LOGGER.error("Could not recover container transfer WAL {}", transactionId, exception);
		}
	}

	private void convergeAwaitingContainerActor(MinecraftServer server, UUID transactionId) {
		ContainerTransferJournal journal = indexedContainerTransfers.get(transactionId);
		if (journal == null) return;
		try {
			switch (containerActors.convergeAfterItem(server, journal)) {
				case UNAVAILABLE -> { return; }
				case CONFLICT -> lockContainerActorConflict(transactionId, journal);
				case APPLIED -> {
					completeContainerTransferCommit(server, journal);
					MmmMod.LOGGER.warn("Converged container transfer WAL {} after its actor became available", transactionId);
				}
			}
		} catch (IOException | RuntimeException exception) {
			awaitingContainerActors.remove(transactionId);
			auditedContainerTransfers.add(transactionId);
			MmmMod.LOGGER.error("Could not converge container transfer WAL {} after actor availability", transactionId, exception);
		}
	}

	private void lockContainerActorConflict(UUID transactionId, ContainerTransferJournal journal) {
		awaitingContainerActors.remove(transactionId);
		auditedContainerTransfers.add(transactionId);
		logContainerTransferLockOnce(transactionId,
				"Container transfer WAL " + transactionId + " is locked: actor " + journal.actor()
						+ " contains neither the authoritative before item nor after item");
	}

	private void logContainerTransferLockOnce(UUID transactionId, String message) {
		if (!message.equals(reportedContainerTransferLocks.put(transactionId, message))) {
			MmmMod.LOGGER.error(message);
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

	/** Event publication follows durable actor save and WAL deletion; it is never a commit input. */
	private void completeContainerTransferCommit(
			MinecraftServer server, ContainerTransferJournal journal
	) throws IOException {
		ContainerTransferCommittedEvent event = committedTransferEvent(journal);
		containerActors.persistAfterItem(server, journal.actor());
		adoptRecoveredContainerCheckpoint(journal);
		deleteContainerTransfer(journal.transactionId());
		reportedContainerTransferLocks.remove(journal.transactionId());
		committedEvents.publish(event);
	}

	private static ContainerTransferCommittedEvent committedTransferEvent(
			ContainerTransferJournal journal
	) {
		OptionalDouble energy = journal.thermalTransfer() == null
				? OptionalDouble.empty()
				: OptionalDouble.of(journal.thermalTransfer().internalEnergy());
		TransferQuantity quantity = switch (journal.resourceKind()) {
			case FLUID_NANOS -> new TransferQuantity.Liquid(journal.volumeNanos(), energy);
			case GRANULAR_SVQ -> new TransferQuantity.Granular(journal.solidVolumeSvq());
		};
		return new ContainerTransferCommittedEvent(
				journal.transactionId(), journal.dimensionId(), journal.regionId(), journal.targetGeneration(),
				journal.operation() == ContainerTransferJournal.Operation.INSERT
						? ContainerTransferCommittedEvent.Operation.INSERT
						: ContainerTransferCommittedEvent.Operation.EXTRACT,
				quantity);
	}

	public static synchronized FluidSimulationService get(MinecraftServer server) {
		return SERVICES.computeIfAbsent(server, FluidSimulationService::new);
	}

	/** Stable Fabric discovery can register listeners without constructing the world service. */
	public static synchronized MmmEventApi committedEvents(MinecraftServer server) {
		if (server == null) throw new IllegalArgumentException("Minecraft server is missing");
		return committedEventHub(server);
	}

	private static CommittedEventHub committedEventHub(MinecraftServer server) {
		return COMMITTED_EVENTS.computeIfAbsent(server, ignored -> new CommittedEventHub());
	}

	/** Read-only lookup used by external APIs; unlike get(), this never initializes world state. */
	public static synchronized FluidSimulationService find(MinecraftServer server) {
		return SERVICES.get(server);
	}




	public static synchronized void removeServer(MinecraftServer server) {
		FluidSimulationService service = SERVICES.remove(server);
		if (service != null) {
			service.checkpointForShutdown();
			service.restoreTakeover(server);
		}
		CommittedEventHub events = COMMITTED_EVENTS.remove(server);
		if (events != null) events.clear();
	}

	public static void recoverTakeoverBackup(MinecraftServer server) {
		FluidSimulationService service = get(server);
		for (String issue : service.regionOperations.issues()) MmmMod.LOGGER.error("Matter, Motion & Mechanics persistent region issue: {}", issue);
		RestorationStats result = service.restoreTakeover(server);
		if (result.completed() && result.journalFound()) {
			MmmMod.LOGGER.warn(
					"Recovered unfinished Matter, Motion & Mechanics takeover: restored {} blocks, already restored {}, skipped {} occupied positions",
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
			MmmMod.LOGGER.error("Matter, Motion & Mechanics world ownership recovery failed: {}", failure);
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
		return null;
	}

	public boolean materialFeatureEnabled() {
		return materialFeatureEnabled;
	}

	/** Stable read-only API surface; construction performs no world or region I/O. */
	public MmmQueryApi queryApi(MinecraftServer server) {
		return new FabricFluidQueryApi(server, regionManager, capabilityProfile != null);
	}

	public boolean chemistryFeatureEnabled() {
		return chemistryFeatureEnabled;
	}
	public boolean granularFeatureEnabled() { return granularFeatureEnabled; }
	public boolean precipitationFeatureEnabled() { return precipitationFeatureEnabled; }
	public boolean thermalFeatureEnabled() { return thermalFeatureEnabled; }
	public boolean phaseChangeFeatureEnabled() { return phaseChangeFeatureEnabled; }

	/** Adds authoritative U to an administrator-created mass-bearing liquid source at standard temperature. */
	public void attachDebugLiquidThermalContents(ItemStack stack) {
		if (!thermalFeatureEnabled) return;
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
				energy += stateFunction.internalEnergyAtTemperature(handle, group.massKilograms(), species,
						GasRegistry.STANDARD_TEMPERATURE_K);
			}
		} else {
			FluidMixture mixture = ContainerItemStacks.fluidMixture(stack);
			if (mixture == null) throw new IllegalArgumentException("Debug material liquid inventory is malformed");
			for (FluidMixture.Component component : mixture.components()) {
				int handle = thermalRegistry.materialHandle(
						ThermalMaterialDefinition.CarrierKind.LIQUID, component.materialId());
				energy += stateFunction.internalEnergyAtTemperature(handle, component.mass(), Map.of(),
						GasRegistry.STANDARD_TEMPERATURE_K);
			}
		}
		if (!Double.isFinite(energy)) throw new IllegalArgumentException("Debug liquid internal energy overflow");
		ContainerItemStacks.attachThermal(stack, thermalRegistry.fingerprint(), energy);
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

	boolean authoritativeWritesReady() {
		return capabilityStartup.state() == WorldCapabilityStartup.State.READY;
	}

	private String authoritativeWriteFailure(String operation) {
		return operation + "失败：world capability startup 为 " + capabilityStartup.state()
				+ "；只允许读取诊断与恢复既有状态";
	}

	public String worldCapabilityStatus(WorldCapability capability) {
		if (capability == null) throw new IllegalArgumentException("World capability is missing");
		if (capabilityProfile == null) {
			return "世界能力 " + capability.stableId() + "：不可用；启动状态 " + capabilityStartup.state()
					+ (capabilityStartup.issue().isEmpty() ? "" : "；" + capabilityStartup.issue());
		}
		return "世界能力 " + capability.stableId() + "："
				+ (capabilityProfile.hasCapability(capability) ? "已冻结启用" : "已冻结未启用")
				+ "；layout=" + capabilityProfile.regionLayoutId()
				+ "；profile=" + capabilityProfile.profileFingerprint().substring(0, 12)
				+ "；startup=" + capabilityStartup.state();
	}

	public WorldCapabilityProfile worldCapabilityProfile() {
		return capabilityProfile;
	}

	public boolean configuredWorldBridgeFeatureEnabled() {
		return !serverConfigLocked && serverConfig.worldBridgeEnabled();
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
		if (!authoritativeWritesReady()) {
			return ControllerActivation.failed(authoritativeWriteFailure("创建控制器区域"));
		}
		if (!controllerFeatureEnabled) return ControllerActivation.DISABLED;
		if (hasContainerTransferStoreIssues()) {
			return ControllerActivation.failed("container-transfer 恢复目录含未知或损坏内容；已全局锁住新控制器，详见日志");
		}
		if (regionOperations.creationLocked() || !ownershipIssues.isEmpty()) {
			return ControllerActivation.failed("存在未解决或超预算的 region-state/ownership；已锁住新控制器区域，详见 /mmm regions list、/mmm ownership 与日志");
		}

		FluidBoundaryScanner.ScanResult scan = FluidControllerRegions.scan(
				level, controllerPosition, (int) Math.max(1L, Math.min(Integer.MAX_VALUE,
						regionManager.limits().maximumCellsPerRegion() / CELL_VOLUMES_PER_BLOCK)
				)
		);
		if (!scan.succeeded()) return ControllerActivation.failed(scan.failure());
		FluidRegionFootprint footprint = scan.footprint();
		if (!worldBridgeFeatureEnabled && footprint.interiorBlocks().stream()
				.map(BlockPos::of).anyMatch(position -> level.getBlockState(position).is(Blocks.WATER))) {
			return ControllerActivation.failed("worldbridge 策略关闭；拒绝接管壳内原版水，请先排空或启用策略并重启");
		}
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
		if (worldBridgeFeatureEnabled) FluidRegionFactory.captureVanillaWater(level, candidate);
		bindConfiguredState(candidate.domain());
		candidate.setPaused(false);
		candidate.wakeForActivity();
		try {
			if (worldBridgeFeatureEnabled) {
				regionOperations.acquireOwnership(level, candidate);
			} else {
				regionOperations.checkpoint(candidate);
			}
		} catch (IOException | RuntimeException exception) {
			regionManager.remove(candidate.id());
			candidate.setAvailabilityFailure("initial checkpoint failed: " + exception.getMessage());
			MmmMod.LOGGER.error("Controller region {} was rejected after its initial checkpoint failed", candidate.id(), exception);
			return ControllerActivation.failed("区域未创建；初始 checkpoint/ownership 事务失败：" + exception.getMessage());
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
		if (!authoritativeWritesReady()) {
			lastTakeoverFailure = authoritativeWriteFailure("创建快速控制器 fixture");
			return null;
		}
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
		if (!authoritativeWritesReady()) {
			lastTakeoverFailure = authoritativeWriteFailure("创建表面张力 fixture");
			return false;
		}
		if (!materialFeatureEnabled) {
			lastTakeoverFailure = "材料功能门未在本次启动启用；先保存配置并重启";
			return false;
		}
		if (chemistryFeatureEnabled) {
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
			if (!authoritativeWritesReady()) {
				lastTakeoverFailure = authoritativeWriteFailure("创建气体 fixture");
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
			placeAirDemoScaffolding(level, newOrigin);
			refreshCollisions(level);
			FlipFluidDomain domain = domain();
			fillAirDemoWater(domain);
			bindConfiguredState(domain);
			domain.step();
			AirNetwork network = domain.airNetwork();
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
		if (replaced != null) snapshots.sendRemoval(level.getServer(), replaced);
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
		if (!authoritativeWritesReady()) {
			lastTakeoverFailure = authoritativeWriteFailure("创建流体 fixture");
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
		bindConfiguredState(domain);
		activeRegion().setPaused(true);
		sendSnapshot(level);
		return true;
	}

	public CaptureResult captureNearbyWater(ServerPlayer player, boolean takeover) {
		if (!authoritativeWritesReady()) {
			return new CaptureResult(0, -1, authoritativeWriteFailure("捕获原版水"));
		}
		if (takeover && !worldBridgeFeatureEnabled) {
			return new CaptureResult(0, -1, "worldbridge 策略关闭；拒绝新的原版水接管");
		}
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
		if (replaced != null) snapshots.sendRemoval(level.getServer(), replaced);
	}

	public boolean setPaused(boolean value, ServerLevel level) {
		if (!authoritativeWritesReady()) return false;
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
		if (!authoritativeWritesReady()) return -1;
		if (!hasRegionIn(level)) return 0;
		FluidRegion region = activeRegion();
		if (!ensureRegionAvailable(level, region)) return -1;
		region.wakeForActivity();
		try {
			for (int i = 0; i < count; i++) stepOnce(region);
		} catch (FlipFluidDomain.ThermalTransportBlockedException blocked) {
			blockThermal(level, region, "thermal-transport PAUSED：" + blocked.getMessage(), blocked);
			return -1;
		} catch (FlipFluidDomain.GasThermalBlockedException blocked) {
			blockThermal(level, region, "gas-thermal PAUSED：" + blocked.getMessage(), blocked);
			return -1;
		} catch (FlipFluidDomain.ThermalPhaseBlockedException blocked) {
			blockThermal(level, region, "phase-change PAUSED：" + blocked.getMessage(), blocked);
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
					region.setAvailabilityFailure("dimension is not loaded; unresolved container transfer WAL prevents unload checkpoint");
					continue;
				}
				regionOperations.unloadForFailure(server, region, "dimension is not loaded", snapshots::sendRemoval);
				continue;
			}
			tickRegion(level, region);
		}
	}

	private void tickRegion(ServerLevel level, FluidRegion region) {
		if (!ensureRegionAvailable(level, region)) return;
		if (!authoritativeWritesReady()) return;
		long tick = region.incrementTick();
		boolean collisionsChanged = false;
		if (tick % COLLISION_REFRESH_INTERVAL_TICKS == 0) {
			try { collisionsChanged = refreshCollisions(level, region).changedCells() > 0; }
			catch (FlipFluidDomain.CollisionEditBlockedException blocked) {
				blockCollisionEdit(level, region, blocked);
				return;
			}
		}
		FluidRegion.EntityCouplingStats entityCoupling = region.entityPhysicsEnabled()
				? MinecraftEntityFluidInteraction.applyForces(level, region)
				: FluidRegion.EntityCouplingStats.EMPTY;
		region.setLastEntityCoupling(entityCoupling);
		int affectedEntities = entityCoupling.affectedEntities();
		if (collisionsChanged || affectedEntities > 0) {
			region.wakeForActivity();
			region.wakeThermal(ThermalActivity.WakeReason.BOUNDARY_CHANGE);
		}
		boolean motionStep = region.canStep();
		boolean thermalStep = thermalFeatureEnabled && region.canStepThermal();
		if (!motionStep && !thermalStep) {
			if (collisionsChanged) sendSnapshot(level, region);
			return;
		}
		if (tick % region.stepIntervalTicks() != 0) {
			if (collisionsChanged) sendSnapshot(level, region);
			return;
		}
		ThermalAuditLedger thermalBefore = thermalFeatureEnabled ? region.domain().thermalAudit() : null;
		try {
			if (motionStep) {
				region.wakeThermal(ThermalActivity.WakeReason.CARRIER_MOTION);
				stepOnce(region);
			} else {
				stepThermalOnce(region);
			}
		} catch (ChemicalReactionEngine.BudgetExceededException budget) {
			String failure = "chemistry-blocked PAUSED：" + budget.getMessage();
			region.blockChemistry(failure);
			MmmMod.LOGGER.error("Region {} exhausted a frozen chemistry budget; parcel commit was rejected and the region is paused",
					region.id(), budget);
			for (ServerPlayer player : level.players()) {
				player.sendSystemMessage(Component.literal(
						"物质、运动与力学化学步骤已整体拒绝，区域被安全暂停：" + budget.getMessage()
								+ "；未提交部分 parcel 修改，请查看 /mmm status"));
			}
			sendSnapshot(level, region);
			return;
		} catch (GranularPhaseTransferEngine.BlockedException blocked) {
			String failure = "granular-phase-blocked PAUSED：" + blocked.getMessage();
			region.blockGranular(failure);
			MmmMod.LOGGER.error("Region {} rejected a granular phase-transfer plan and is paused",
					region.id(), blocked);
			for (ServerPlayer player : level.players()) {
				player.sendSystemMessage(Component.literal(
						"物质、运动与力学固液相转移被安全暂停：" + blocked.getMessage()
								+ "；当前 plan 未提交，先前已提交 cell 保持，请查看 /mmm status"));
			}
			sendSnapshot(level, region);
			return;
		} catch (GranularMechanics.ValidityExceededException invalid) {
			String failure = "granular-validity-blocked PAUSED：" + invalid.getMessage();
			region.blockGranular(failure);
			MmmMod.LOGGER.error("Region {} exceeded a frozen granular mechanics validity range and is paused",
					region.id(), invalid);
			for (ServerPlayer player : level.players()) player.sendSystemMessage(Component.literal(
					"物质、运动与力学颗粒力学超出定义有效范围，区域被安全暂停：" + invalid.getMessage()
							+ "；本次速度提交未开始，请查看 /mmm status"));
			sendSnapshot(level, region);
			return;
		} catch (FlipFluidDomain.ThermalTransportBlockedException blocked) {
			blockThermal(level, region, "thermal-transport PAUSED：" + blocked.getMessage(), blocked);
			return;
		} catch (FlipFluidDomain.GasThermalBlockedException blocked) {
			blockThermal(level, region, "gas-thermal PAUSED：" + blocked.getMessage(), blocked);
			return;
		} catch (FlipFluidDomain.ThermalPhaseBlockedException blocked) {
			blockThermal(level, region, "phase-change PAUSED：" + blocked.getMessage(), blocked);
			return;
		}
		updateStepPacing(region);
		if (thermalFeatureEnabled) observeThermalStep(region, thermalBefore, motionStep);
		FlipFluidDomain.StepStats stats = region.lastStats();
		float residualLimit = Math.max(1.0E-5F, region.domain().config().pressureTolerance() * 4.0F);
		boolean hydrostaticCandidate = !collisionsChanged && affectedEntities == 0
				&& region.domain().maximumParticleSpeed() <= HYDROSTATIC_MAX_MARKER_SPEED
				&& region.domain().granularReadyForHydrostaticSleep()
				&& stats != null && stats.pressureResidual() <= residualLimit;
		if (motionStep && region.lifecycle().observeCompletedStep(hydrostaticCandidate, HYDROSTATIC_QUIET_STEPS)) {
			float exactVolume = region.domain().totalVolume();
			int markerCount = region.domain().particleCount();
			region.domain().commitHydrostaticSleep();
			if (markerCount != region.domain().particleCount() || exactVolume != region.domain().totalVolume()) {
				throw new IllegalStateException("Hydrostatic sleep changed the exact marker ledger for region " + region.id());
			}
			MmmMod.LOGGER.info("Region {} entered hydrostatic sleep with {} markers and {} exact cell volume",
					region.id(), markerCount, exactVolume);
		}
		if (region.hardOverrunStreak() >= HARD_PAUSE_STREAK) {
			region.setPaused(true);
			MmmMod.LOGGER.warn("Auto-paused fluid region {} after {} consecutive steps above {} ms",
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

		boolean available = regionOperations.ensureAvailable(
				level, region, true, this::refreshCollisions, this::sendSnapshot,
				snapshots::sendRemoval
		);
		if (available && thermalFeatureEnabled && region.solverLoaded() && region.domain().hasThermalState()) {
			region.enableThermalActivity();
			if (region.domain().hasPendingPhaseVolumeWork()) region.wakeForActivity();
		}
		return available;
	}

	private void stepOnce(FluidRegion region) {
		long start = System.nanoTime();
		FlipFluidDomain.StepStats stats = region.domain().step(
				phaseChangeFeatureEnabled, serverConfig.maximumPhaseOperationsPerStep());
		region.recordStep(stats, (System.nanoTime() - start) / 1_000_000.0);
	}

	private void stepThermalOnce(FluidRegion region) {
		long start = System.nanoTime();
		region.domain().stepThermalAxis(phaseChangeFeatureEnabled, serverConfig.maximumPhaseOperationsPerStep());
		region.recordThermalStep((System.nanoTime() - start) / 1_000_000.0);
	}

	private void observeThermalStep(
			FluidRegion region, ThermalAuditLedger before, boolean carrierMotion
	) {
		FlipFluidDomain domain = region.domain();
		ThermalAuditLedger after = domain.thermalAudit();
		boolean gasActivity = !after.externalSource().equals(before.externalSource())
				|| !after.externalSink().equals(before.externalSink())
				|| !after.mechanicalWork().equals(before.mechanicalWork())
				|| !after.finiteDeviceExchange().equals(before.finiteDeviceExchange());
		boolean chemistryDirty = domain.lastChemicalReactionStats().changedMarkers() != 0;
		boolean granularDirty = domain.lastPhaseTransferStats().transfers() != 0
				|| carrierMotion && (domain.lastGranularDepositStats().settledParcels() != 0
				|| domain.lastGranularDepositStats().relaxedMoves() != 0
				|| domain.lastGranularDepositStats().wokenParcels() != 0
				|| domain.lastGranularDepositStats().dirtyColumns() != 0);
		boolean phaseDirty = domain.lastThermalPhaseStats().operations() != 0
				|| domain.lastGasThermalPhaseStats().operations() != 0;
		boolean auditClosed = domain.exactInternalEnergy().equals(after.expectedInventory());
		boolean pendingVolumeOrWork = domain.hasPendingPhaseVolumeWork();
		region.observeThermal(new ThermalActivity.Observation(domain.lastThermalTransportStats(),
				chemistryDirty, granularDirty, phaseDirty, gasActivity, carrierMotion,
				pendingVolumeOrWork, auditClosed),
				THERMAL_QUIET_STEPS);
		if (!carrierMotion && (chemistryDirty || granularDirty || phaseDirty || gasActivity
				|| pendingVolumeOrWork)) {
			region.wakeForActivity();
		}
	}

	private void blockThermal(ServerLevel level, FluidRegion region, String failure, RuntimeException blocked) {
		region.blockThermal(failure);
		MmmMod.LOGGER.error("Region {} rejected a thermal logical step and is paused: {}",
				region.id(), failure, blocked);
		for (ServerPlayer player : level.players()) player.sendSystemMessage(Component.literal(
				"物质、运动与力学热学步骤被安全拒绝，区域已暂停：" + failure + "；请查看 /mmm status"));
		sendSnapshot(level, region);
	}

	private static void updateStepPacing(FluidRegion region) {
		double lastStepMilliseconds = region.lastStepMilliseconds();
		if (lastStepMilliseconds > HARD_PAUSE_MILLIS) region.setHardOverrunStreak(region.hardOverrunStreak() + 1);
		else region.setHardOverrunStreak(0);

		int requiredInterval = Mth.clamp((int) Math.ceil(lastStepMilliseconds / STEP_BUDGET_MILLIS), 1, MAX_STEP_INTERVAL_TICKS);
		if (requiredInterval > region.stepIntervalTicks()) {
			region.setStepIntervalTicks(requiredInterval);
			region.setRecoveryStreak(0);
			MmmMod.LOGGER.info("Reduced fluid simulation rate to approximately {} Hz after a {} ms step",
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
		if (!authoritativeWritesReady()) return WaterEditResult.blocked(authoritativeWriteFailure("注入测试水"));
		if (!hasRegionIn(player.level())) return WaterEditResult.NO_DOMAIN;
		FluidRegion region = activeRegion();
		if (!ensureRegionAvailable(player.level(), region)) {
			return WaterEditResult.blocked(region.availabilityFailure());
		}
		FluidWorldTargetResolver.CellTarget resolved = targets.aimedCellForEdit(player, region, false);
		if (resolved == null) return WaterEditResult.NO_TARGET;
		CellTarget target = new CellTarget(resolved.x(), resolved.y(), resolved.z());
		float requested = buckets * CELL_VOLUMES_PER_BLOCK;
		FlipFluidDomain.VolumeAddition addition = domain().addVolumeNearBudgeted(
				target.x(), target.y(), target.z(), requested, 6
		);
		if (addition.addedVolume() > 0.0F) region.wakeForActivity();
		sendSnapshot(player.level());
		return new WaterEditResult(true, target, requested, addition.addedVolume(), addition.markerBudgetLimited(), null);
	}

	public WaterEditResult drainWater(ServerPlayer player, int buckets) {
		if (!authoritativeWritesReady()) return WaterEditResult.blocked(authoritativeWriteFailure("排出测试水"));
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
		if (!authoritativeWritesReady()) return FluidRegion.CollisionStats.EMPTY;
		FluidRegion region = activeRegion();
		if (region == null || !level.dimension().equals(region.dimension())) return FluidRegion.CollisionStats.EMPTY;
		if (!ensureRegionAvailable(level, region)) return region.lastCollisionStats();
		try { return refreshCollisions(level, region); }
		catch (FlipFluidDomain.CollisionEditBlockedException blocked) {
			blockCollisionEdit(level, region, blocked);
			throw blocked;
		}
	}

	private void blockCollisionEdit(
			ServerLevel level, FluidRegion region, FlipFluidDomain.CollisionEditBlockedException blocked
	) {
		String detail = blocked.getCause() == null ? blocked.getMessage() : blocked.getCause().getMessage();
		region.blockCollision("collision-edit-blocked PAUSED：" + detail);
		MmmMod.LOGGER.error("Region {} could not reconcile a Minecraft collision edit with authoritative inventory and is paused",
				region.id(), blocked);
		for (ServerPlayer player : level.players()) player.sendSystemMessage(Component.literal(
				"物质、运动与力学无法把方块碰撞编辑与物质账本联合提交，区域已安全暂停：" + detail
						+ "；世界方块优先，区域代次保持可恢复，请查看 /mmm status"));
		sendSnapshot(level, region);
	}

	private FluidRegion.CollisionStats refreshCollisions(ServerLevel level, FluidRegion region) {
		FluidRegion.CollisionStats stats = FluidRegionWorldInteraction.refreshCollisions(
				level, region, CELLS_PER_BLOCK
		);
		boolean thermalBoundaryChanged = false;
		if (thermalBoundaryResolver != null) try {
			thermalBoundaryChanged = thermalBoundaryResolver.refresh(level, region, CELLS_PER_BLOCK);
		} catch (RuntimeException invalidBoundary) {
			blockThermal(level, region, "thermal-definition LOCKED：" + invalidBoundary.getMessage(), invalidBoundary);
		}
		if (stats.changedCells() > 0 || stats.displacedCellVolume() > 0.0F
				|| stats.escapedCellVolume() > 0.0F || stats.markerBudgetLimited()) {
			region.setLastCollisionStats(stats);
		}
		region.markCollisionRefreshed();
		if (stats.changedCells() > 0) region.wakeForActivity();
		if (thermalBoundaryChanged) region.wakeThermal(ThermalActivity.WakeReason.BOUNDARY_CHANGE);
		return stats;
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
		snapshots.clearAll(server);
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
		if (!authoritativeWritesReady()) return authoritativeWriteFailure("发布 checkpoint");
		FluidRegion selected = activeRegion();
		if (selected != null && hasPendingContainerTransfer(selected.id())) {
			return "区域存在未完成或不可识别的容器转移 WAL；拒绝发布额外 checkpoint";
		}
		return regionOperations.checkpointSelected();
	}

	public String emptySelectedRegion(ServerLevel level) {
		if (!authoritativeWritesReady()) return authoritativeWriteFailure("清空区域库存");
		FluidRegion region = activeRegion();
		if (region == null || !level.dimension().equals(region.dimension())) return "没有活动流体域或位于其他维度";
		if (!ensureRegionAvailable(level, region)) return "清空失败：控制器域当前不可用：" + region.availabilityFailure();
		if (hasPendingContainerTransfer(region.id())) {
			return "清空失败：区域存在未完成或不可识别的容器转移 WAL；拒绝清空";
		}
		FlipFluidDomain domain = region.domain();
		if (domain.hasPendingPhaseVolumeWork()) {
			return "清空失败：区域存在尚未由下一次 pressure projection 消费的相变体积/功记录";
		}
		float beforeBlocks = domain.totalVolume() / CELL_VOLUMES_PER_BLOCK;
		int beforeParticles = domain.particleCount();
		FlipFluidDomain.OperatorClearPlan clearPlan;
		try {
			clearPlan = domain.planOperatorClear();
		} catch (RuntimeException failure) {
			return "清空失败：联合液体/coherent/颗粒预检未通过，活动库存未改变：" + failure.getMessage();
		}
		FlipFluidDomain.OperatorClear cleared;
		try {
			cleared = domain.commitOperatorClear(clearPlan);
		} catch (RuntimeException failure) {
			region.setPaused(true);
			region.setAvailabilityFailure("empty 联合提交违反已预检不变量：" + failure.getMessage());
			return "清空联合提交失败；区域已暂停：" + failure.getMessage();
		}
		region.wakeForActivity();
		FlipFluidDomain.CondensedClear condensed = cleared.condensed();
		FlipFluidDomain.GranularClear granular = cleared.granular();
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
		FluidRegion.EntityCouplingStats entityCoupling = region.lastEntityCoupling();
		FluidRegionFootprint footprint = region.footprint();
		String regionSource = footprint == null
				? "command"
				: "controller " + footprint.controllerPosition().toShortString() + ", boundary "
						+ footprint.boundaryBlocks().size() + ", chunks " + footprint.requiredChunks().size();
		String availability = region.availabilityFailure() == null ? "ready" : "blocked: " + region.availabilityFailure();
		String gas = gasSummary(domain);
		String thermal = thermalFeatureEnabled ? thermalSummary(region, domain) : "";
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

	private String thermalSummary(FluidRegion region, FlipFluidDomain domain) {
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
		FlipFluidDomain.ThermalPhaseStats condensed = domain.lastThermalPhaseStats();
		FlipFluidDomain.GasThermalPhaseStats gas = domain.lastGasThermalPhaseStats();
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
	private static String gasSummary(FlipFluidDomain domain) {
		AirNetwork network = domain.airNetwork();
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

	/** Applies the bounded indicator/eraser primitive to the actually aimed region. */
	public IndicatorUseResult editIndicatorAtAim(ServerPlayer player, boolean indicated) {
		if (!authoritativeWritesReady()) return IndicatorUseResult.rejected(authoritativeWriteFailure("修改 indicator"));
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

	/** Administrator-only exact granular inventory/audit report; display snapshots never feed this path. */
	public String granularInventoryStatus(ServerLevel level) {
		if (!granularFeatureEnabled) return "颗粒功能门本次启动未开启";
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

	private void bindDefaultMaterialState(FlipFluidDomain domain) {
		if (domain.hasMaterialState()) return;
		if (materialRegistry == null || materialRegistryDefinitions == null) {
			throw new IllegalStateException("Committed world material registry is unavailable");
		}
		FlipParticleState pure = domain.exportParticleState();
		float[] positionVolume = pure.copyPositionVolume();
		double[] markerMass = new double[pure.particleCount()];
		int[] material = new int[pure.particleCount()];
		int defaultHandle = materialRegistry.handle(materialRegistryDefinitions.defaultMaterialId());
		java.util.Arrays.fill(material, defaultHandle);
		FluidMaterial defaultMaterial = materialRegistry.material(defaultHandle);
		double totalVolume = 0.0, totalMass = 0.0;
		for (int marker = 0; marker < pure.particleCount(); marker++) {
			double volume = positionVolume[marker * 4 + 3];
			markerMass[marker] = volume * defaultMaterial.referenceDensity();
			totalVolume += volume;
			totalMass += markerMass[marker];
		}
		domain.restoreMaterialParticleState(new FlipMaterialParticleState(
				pure.sizeX(), pure.sizeY(), pure.sizeZ(), pure.cellSize(), pure.particleCount(), totalVolume, totalMass,
				materialRegistry.materials(), positionVolume, pure.copyParticleVelocities(), pure.copySolidMask(), markerMass, material
		));
	}

	private void bindConfiguredState(FlipFluidDomain domain) {
		if (materialFeatureEnabled) bindDefaultMaterialState(domain);
		if (chemistryFeatureEnabled && !domain.hasChemistryState()) domain.bindChemistry(chemicalRegistry);
		if (granularFeatureEnabled && !domain.hasGranularState()) {
			domain.bindGranular(granularRegistry, domain.materialRegistry());
		}
		if (precipitationFeatureEnabled) domain.setPrecipitationEnabled(true);
		if (thermalFeatureEnabled && !domain.hasThermalState()) {
			domain.bindGasRegistry(gasRegistry);
			domain.bindThermal(thermalRegistry, domain.materialRegistry(), GasRegistry.STANDARD_TEMPERATURE_K,
					serverConfig.maximumThermalSubstepsPerStep());
		}
	}

	private static void assignLayeredDemoMaterials(FlipFluidDomain domain, float interfaceY) {
		FlipMaterialParticleState state = domain.exportMaterialParticleState();
		float[] positionVolume = state.copyPositionVolume();
		double[] markerMass = state.copyParticleMass();
		int[] materials = state.copyMaterialPaletteIndex();
		double totalMass = 0.0;
		int water = domain.materialRegistry().handle(BuiltinFluidMaterials.WATER.id());
		int oil = domain.materialRegistry().handle(BuiltinFluidMaterials.OIL.id());
		for (int marker = 0; marker < state.particleCount(); marker++) {
			int material = positionVolume[marker * 4 + 1] >= interfaceY ? oil : water;
			materials[marker] = material;
			markerMass[marker] = positionVolume[marker * 4 + 3]
					* domain.materialRegistry().material(material).referenceDensity();
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
