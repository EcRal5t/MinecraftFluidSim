package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.model.region.RegionManifest;
import cc.ecisr.mmm.model.region.RegionStateLayout;

import cc.ecisr.mmm.application.RegionCatalog;
import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.fluid.FluidDomain;
import cc.ecisr.mmm.fluid.FluidMaterialRegistry;
import cc.ecisr.mmm.fluid.air.GasRegistry;
import cc.ecisr.mmm.granular.GranularRegistry;
import cc.ecisr.mmm.simulation.ownership.OwnershipCommitted;
import cc.ecisr.mmm.simulation.ownership.OwnershipFiles;
import cc.ecisr.mmm.simulation.ownership.OwnershipStore;
import cc.ecisr.mmm.simulation.persistence.GenerationSequence;
import cc.ecisr.mmm.simulation.persistence.RegionCheckpointAssembler;
import cc.ecisr.mmm.simulation.persistence.RegionCheckpointRestorer;
import cc.ecisr.mmm.simulation.persistence.RegionState;
import cc.ecisr.mmm.simulation.persistence.RegionGenerationStore;
import cc.ecisr.mmm.simulation.persistence.RemovedRegionManifest;
import cc.ecisr.mmm.thermal.ThermalRegistry;
import cc.ecisr.mmm.thermal.ThermalTransport;
import cc.ecisr.mmm.worldprofile.WorldCapability;
import cc.ecisr.mmm.worldprofile.WorldCapabilityProfile;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Minecraft repository adapter for checkpoint assembly, generation storage and live restore. */
final class RegionRepositoryAdapter {
	private final RegionGenerationStore files;
	private final OwnershipStore ownershipStore;
	private final WorldCapabilityProfile profile;
	private final ChemicalRegistry chemicalRegistry;
	private final GranularRegistry granularRegistry;
	private final ThermalRegistry thermalRegistry;
	private final GasRegistry gasRegistry;
	private final int maximumThermalSubsteps;

	RegionRepositoryAdapter(
			Path worldRoot, WorldCapabilityProfile profile, ChemicalRegistry chemicalRegistry,
			GranularRegistry granularRegistry, ThermalRegistry thermalRegistry, GasRegistry gasRegistry
	) {
		this(worldRoot, profile, chemicalRegistry, granularRegistry, thermalRegistry, gasRegistry,
				ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
	}

	RegionRepositoryAdapter(
			Path worldRoot, WorldCapabilityProfile profile, ChemicalRegistry chemicalRegistry,
			GranularRegistry granularRegistry, ThermalRegistry thermalRegistry, GasRegistry gasRegistry,
			int maximumThermalSubsteps
	) {
		Objects.requireNonNull(profile, "profile");
		requireRegistry(profile, WorldCapability.CHEMISTRY, chemicalRegistry);
		requireRegistry(profile, WorldCapability.GRANULAR, granularRegistry);
		if (profile.hasCapability(WorldCapability.THERMAL)) {
			if (thermalRegistry == null || gasRegistry == null) {
				throw new IllegalArgumentException("Thermal capability requires thermal and gas registries");
			}
		} else if (thermalRegistry != null || gasRegistry != null) {
			throw new IllegalArgumentException("Thermal registries are present without the thermal capability");
		}
		files = switch (profile.regionLayoutId()) {
			case "pure", "material" -> new RegionGenerationStore(worldRoot);
			case "chemical" -> new RegionGenerationStore(worldRoot, chemicalRegistry);
			case "granular" -> new RegionGenerationStore(worldRoot, chemicalRegistry, granularRegistry);
			case "thermal" -> new RegionGenerationStore(worldRoot, chemicalRegistry, granularRegistry, thermalRegistry,
					gasRegistry);
			default -> throw new IllegalArgumentException("Unknown region layout " + profile.regionLayoutId());
		};
		ownershipStore = new OwnershipStore(worldRoot, chemicalRegistry, granularRegistry, thermalRegistry, gasRegistry);
		this.profile = profile;
		this.chemicalRegistry = chemicalRegistry;
		this.granularRegistry = granularRegistry;
		this.thermalRegistry = thermalRegistry;
		this.gasRegistry = gasRegistry;
		if (maximumThermalSubsteps <= 0
				|| maximumThermalSubsteps > ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS) {
			throw new IllegalArgumentException("Thermal transport substep budget is invalid");
		}
		this.maximumThermalSubsteps = maximumThermalSubsteps;
	}

	OwnershipTransactionCoordinator newOwnershipTransactions() {
		return new OwnershipTransactionCoordinator(this, files, ownershipStore);
	}

	List<String> loadMetadata(FluidRegionManager manager) {
		List<String> issues = new ArrayList<>();
		RegionGenerationStore.Discovery discovery;
		try {
			files.recoverKnownFirstPublicationStaging();
			discovery = files.discover();
		} catch (IOException exception) {
			return List.of("无法恢复或扫描 region-state：" + exception.getMessage());
		}
		for (var issue : discovery.issues().entrySet()) issues.add(issue.getKey().getFileName() + " 已锁定：" + issue.getValue());
		for (RegionManifest manifest : discovery.manifests()) {
			try {
				validateFeatureShape(manifest);
				if (manifest.hasChemicalState()) {
					if (!profile.hasCapability(WorldCapability.CHEMISTRY) || chemicalRegistry == null) {
						throw new IOException("chemical region-state requires the committed chemistry capability and registry snapshot");
					}
					if (!manifest.registryFingerprint().equals(chemicalRegistry.fingerprint())) {
						throw new IOException("region-state chemical registry fingerprint does not match the world snapshot");
					}
				}
				if (manifest.hasGranularState() && (!profile.hasCapability(WorldCapability.GRANULAR) || granularRegistry == null)) {
					throw new IOException("granular region-state requires the committed granular capability and registry snapshot");
				}
				if (manifest.hasThermalState() && (!profile.hasCapability(WorldCapability.THERMAL) || thermalRegistry == null || gasRegistry == null)) {
					throw new IOException("thermal region-state requires the committed thermal capability and thermal/gas registry snapshots");
				}
				validateControllerGeometry(manifest);
				Identifier dimensionId = Identifier.tryParse(manifest.dimensionId());
				if (dimensionId == null) throw new IOException("invalid dimension");
				ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
				FluidRegion region = FluidRegion.unloaded(manifest.regionId(), dimension,
						MinecraftRegionCheckpointSource.blockPosition(manifest.origin()),
						manifest.sizeX(), manifest.sizeY(), manifest.sizeZ(), manifest.solverConfig(),
						manifest.reservedMarkerCapacity(), MinecraftRegionCheckpointSource.minecraftFootprint(manifest.footprint()),
						manifest.networkGeneration(), manifest.checkpointGeneration(), manifest.totalMarkerCount(),
						(float) manifest.totalVolume(), MinecraftRegionCheckpointSource.persistedRuntime(manifest.runtime()));
				RegionCatalog.AdmissionResult registration = manager.registerDiscovered(region);
				if (!registration.accepted()) {
					throw new IOException("committed metadata registration failed: " + registration.failure());
				}
			} catch (IOException | IllegalArgumentException exception) {
				issues.add(manifest.regionId() + " 已锁定：" + exception.getMessage());
			}
		}
		for (FluidRegion region : manager.regions()) {
			if (region.solverLoaded()) continue;
			RegionCatalog.AdmissionResult runtimeAdmission = manager.runtimeAdmission(region);
			if (!runtimeAdmission.accepted()) {
				region.setAvailabilityFailure(runtimeAdmission.reason(), runtimeAdmission.failure());
			}
		}
		return List.copyOf(issues);
	}

	long checkpoint(FluidRegion region) throws IOException {
		if (!region.controllerManaged() || !region.solverLoaded()) throw new IOException("Region has no persistent live solver");
		String ownershipLock = ownershipLockDescription(region);
		if (ownershipLock != null) {
			throw new IOException("Unresolved world ownership state blocks checkpoint: " + ownershipLock);
		}
		long generation = GenerationSequence.next(region.checkpointGeneration(), "Region checkpoint");
		long published = files.publish(capture(region, generation));
		if (!region.recordPublishedCheckpoint(published)) throw new IOException("Published checkpoint was not accepted by the live region");
		return published;
	}

	long committedGeneration(UUID regionId) throws IOException {
		return files.committedGeneration(regionId);
	}

	long committedManifestChecksum(UUID regionId) throws IOException {
		return files.committedManifestChecksum(regionId);
	}

	void requireRemovalQuarantineCapacity(FluidRegion region, long maximumEntries, long maximumBytes) throws IOException {
		files.requireRemovalQuarantineCapacity(region.id(), maximumEntries, maximumBytes);
	}

	RegionGenerationStore.PreparedRemoval prepareRemoval(
			FluidRegion region, long networkTombstoneGeneration, UUID quarantineTransactionId,
			long maximumEntries, long maximumBytes
	) throws IOException {
		return files.prepareRemoval(region.id(), region.checkpointGeneration(),
				files.committedManifestChecksum(region.id()), networkTombstoneGeneration,
				quarantineTransactionId, profile.profileFingerprint(), profile.aggregateFingerprint(),
				maximumEntries, maximumBytes);
	}

	RemovedRegionManifest commitRemoval(RegionGenerationStore.PreparedRemoval prepared) throws IOException {
		return files.commitRemoval(prepared);
	}

	RemovedRegionManifest readRemoved(UUID regionId) throws IOException { return files.readRemoved(regionId); }

	void abortRemoval(RegionGenerationStore.PreparedRemoval prepared) throws IOException { files.abortRemoval(prepared); }

	Path convergeRemovalQuarantine(UUID regionId) throws IOException {
		return files.convergeRemovalQuarantine(regionId);
	}

	List<String> recoverRemovedQuarantines() {
		ArrayList<String> failures = new ArrayList<>();
		try {
			for (RemovedRegionManifest removed : files.discover().removed()) {
				if (!removed.capabilityProfileFingerprint().equals(profile.profileFingerprint())
						|| !removed.registryAggregateFingerprint().equals(profile.aggregateFingerprint())) {
					failures.add("REMOVED " + removed.regionId() + " world capability identity 不匹配");
					continue;
				}
				try { files.convergeRemovalQuarantine(removed.regionId()); }
				catch (IOException failure) { failures.add("REMOVED " + removed.regionId() + " quarantine 未收敛：" + failure.getMessage()); }
			}
		} catch (IOException failure) {
			failures.add("REMOVED tombstone discovery 失败：" + failure.getMessage());
		}
		return List.copyOf(failures);
	}

	StagedCheckpoint stageCheckpoint(FluidRegion region, long targetGeneration) throws IOException {
		if (!GenerationSequence.isNext(region.checkpointGeneration(), targetGeneration)) {
			throw new IOException("Staged checkpoint target does not advance the live region by one generation");
		}
		String ownershipLock = ownershipLockDescription(region);
		if (ownershipLock != null) throw new IOException("Unresolved world ownership state blocks staging: " + ownershipLock);
		RegionGenerationStore.PreparedGeneration prepared = files.prepare(
				capture(region, targetGeneration), (point, path) -> { });
		return new StagedCheckpoint(prepared, prepared.manifestChecksum());
	}

	/** Stages a private lifecycle result from its own complete carrier geometry. */
	StagedCheckpoint stageLifecycleCheckpoint(
			FluidRegion region, FluidDomain candidateDomain, long targetGeneration
	) throws IOException {
		return stageCandidateCheckpoint(region, candidateDomain, targetGeneration, true);
	}

	/** Stages a complete private solver successor while preserving the supplied operator intent. */
	StagedCheckpoint stageCandidateCheckpoint(
			FluidRegion region, FluidDomain candidateDomain, long targetGeneration, boolean operatorPaused
	) throws IOException {
		if (!GenerationSequence.isNext(region.checkpointGeneration(), targetGeneration)) {
			throw new IOException("Candidate checkpoint target does not advance the live region by one generation");
		}
		String ownershipLock = ownershipLockDescription(region);
		if (ownershipLock != null) throw new IOException("Unresolved world ownership state blocks candidate staging: " + ownershipLock);
		FluidRegion candidate = region.lifecycleCandidate(candidateDomain, operatorPaused);
		RegionGenerationStore.PreparedGeneration prepared = files.prepare(
				captureForFeatureGate(candidate, targetGeneration, true), (point, path) -> { });
		return new StagedCheckpoint(prepared, prepared.manifestChecksum());
	}

	/** Publishes only HEAD; the caller then applies its already-validated live assignment and records the generation. */
	long commitStagedLifecycleCheckpoint(StagedCheckpoint staged) throws IOException {
		return files.commit(staged.prepared(), (point, path) -> { });
	}

	long commitStagedCheckpoint(FluidRegion region, StagedCheckpoint staged) throws IOException {
		long published = files.commit(staged.prepared(), (point, path) -> { });
		if (!region.recordPublishedCheckpoint(published)) throw new IOException("Published staged checkpoint was not accepted");
		return published;
	}

	void abortStagedCheckpoint(StagedCheckpoint staged) throws IOException {
		files.abort(staged.prepared());
	}

	record StagedCheckpoint(RegionGenerationStore.PreparedGeneration prepared, long manifestChecksum) { }

	/** Per-region fail-closed guard used for both loaded stepping and every ordinary checkpoint path. */
	String ownershipLockDescription(FluidRegion region) {
		if (!region.controllerManaged()) return null;
		OwnershipFiles ownershipFiles = ownershipStore.files(region.id());
		if (ownershipFiles.isLocked()) return ownershipFiles.lockDescription();
		if (!ownershipFiles.hasCommitted()) return null;
		try {
			OwnershipCommitted committed = ownershipFiles.readCommitted();
			if (!committed.regionId().equals(region.id())) {
				return "committed ownership 记录了其他区域 " + committed.regionId();
			}
			if (!committed.dimensionId().equals(region.dimension().identifier().toString())) {
				return "committed ownership 维度与区域不一致";
			}
			if (committed.generation() > region.checkpointGeneration()) {
				return "committed ownership generation " + committed.generation()
						+ " 超前于活动区域 checkpoint " + region.checkpointGeneration();
			}
			return null;
		} catch (IOException | RuntimeException invalid) {
			return "ownership committed 状态不可读：" + invalid.getMessage();
		}
	}

	void checkpointAndUnload(FluidRegion region) throws IOException {
		long generation = checkpoint(region);
		if (!region.releaseSolverAfterCheckpoint(generation)) throw new IOException("Published checkpoint did not release region solver");
	}

	void restore(FluidRegion region) throws IOException {
		if (region.solverLoaded() || region.lifecycle().state() != FluidRegionLifecycle.State.UNLOADED) return;
		RegionState state = files.read(region.id());
		RegionManifest manifest = state.manifest();
		validateFeatureShape(manifest);
		if (manifest.checkpointGeneration() != region.checkpointGeneration() || manifest.networkGeneration() != region.networkGeneration()
				|| !manifest.dimensionId().equals(region.dimension().identifier().toString())
				|| !manifest.origin().equals(MinecraftRegionCheckpointSource.position(region.origin())) || manifest.sizeX() != region.sizeX()
				|| manifest.sizeY() != region.sizeY() || manifest.sizeZ() != region.sizeZ()
				|| manifest.reservedMarkerCapacity() != region.reservedMarkerCapacity()) {
			throw new IOException("Committed checkpoint no longer matches admitted region metadata");
		}
		OwnershipFiles ownershipFiles = ownershipStore.files(region.id());
		if (ownershipFiles.isLocked()) {
			throw new IOException("Unresolved world ownership transition; recovery must complete first");
		}
		if (ownershipFiles.hasCommitted()) {
			OwnershipCommitted committed = ownershipFiles.readCommitted();
			if (!committed.regionId().equals(region.id())
					|| !committed.dimensionId().equals(region.dimension().identifier().toString())
					|| committed.generation() > manifest.checkpointGeneration()) {
				throw new IOException("Committed world ownership does not match the region checkpoint");
			}
		}
		restoreFeatureGeneration(region, state);
	}

	/** Replaces an in-process base solver with the exact already-committed container target. */
	void adoptCommittedContainerCheckpoint(
			FluidRegion region, long baseGeneration, long targetGeneration
	) throws IOException {
		if (!region.solverLoaded()) {
			if (region.checkpointGeneration() == targetGeneration) return;
			throw new IOException("Unloaded region metadata does not identify the committed container target");
		}
		if (region.checkpointGeneration() == targetGeneration) return;
		if (region.checkpointGeneration() != baseGeneration) {
			throw new IOException("Live region is neither the container base nor target generation");
		}
		RegionState state = files.read(region.id());
		RegionManifest manifest = state.manifest();
		validateFeatureShape(manifest);
		validateRecoveryIdentity(region, manifest);
		if (manifest.checkpointGeneration() != targetGeneration) {
			throw new IOException("Committed container HEAD no longer identifies its target generation");
		}
		String ownershipLock = ownershipLockDescription(region);
		if (ownershipLock != null) {
			throw new IOException("Unresolved world ownership state blocks container target adoption: "
					+ ownershipLock);
		}
		FluidRegion candidate = unloadedRegion(manifest);
		restoreFeatureGeneration(candidate, state);
		if (!candidate.solverLoaded()) {
			throw new IOException("Committed container target did not restore a complete private solver");
		}
		region.adoptContainerCandidateDomain(
				candidate.domain(), targetGeneration, manifest.runtime().operatorPaused());
	}

	/**
	 * Operator-only recovery for a HEAD that is locally valid but cannot bind its persisted gas
	 * topology to its own marker geometry. The inactive generation is fully reconstructed into a
	 * throwaway solver before HEAD moves. Startup never calls this path and never guesses a fallback.
	 */
	long recoverPreviousGeneration(FluidRegion region, long expectedCurrentGeneration) throws IOException {
		if (!profile.hasCapability(WorldCapability.THERMAL) || thermalRegistry == null || gasRegistry == null) {
			throw new IOException("Previous-generation recovery requires the active thermal/gas registries");
		}
		if (!region.controllerManaged() || region.solverLoaded()
				|| region.lifecycle().state() != FluidRegionLifecycle.State.UNLOADED) {
			throw new IOException("Previous-generation recovery requires a metadata-only unloaded controller region");
		}
		if (!region.paused()) throw new IOException("Previous-generation recovery requires an operator-paused region");
		if (region.checkpointGeneration() != expectedCurrentGeneration) {
			throw new IOException("Selected region metadata is not at expected checkpoint " + expectedCurrentGeneration);
		}
		String ownershipLock = ownershipLockDescription(region);
		if (ownershipLock != null) {
			throw new IOException("Unresolved world ownership state blocks previous-generation recovery: " + ownershipLock);
		}

		RegionGenerationStore.PreviousGeneration previous = files.readPrevious(region.id(), expectedCurrentGeneration);
		RegionManifest manifest = previous.state().manifest();
		if (manifest.layout() != RegionStateLayout.THERMAL) {
			throw new IOException("Previous slot does not use the current thermal/gas region layout");
		}
		validateFeatureShape(manifest);
		validateRecoveryIdentity(region, manifest);
		OwnershipFiles ownershipFiles = ownershipStore.files(region.id());
		if (ownershipFiles.hasCommitted()) {
			OwnershipCommitted committed = ownershipFiles.readCommitted();
			if (!committed.regionId().equals(region.id())
					|| !committed.dimensionId().equals(region.dimension().identifier().toString())
					|| committed.generation() > manifest.checkpointGeneration()) {
				throw new IOException("Previous checkpoint predates committed world ownership truth");
			}
		}

		FluidRegion probe = unloadedRegion(manifest);
		restoreThermalGeneration(probe, previous.state());
		if (!probe.solverLoaded()
				|| !probe.domain().exactInternalEnergy().equals(probe.domain().thermalAudit().expectedInventory())) {
			throw new IOException("Previous checkpoint probe did not restore a closed thermal inventory");
		}
		if (!probe.releaseSolverAfterCheckpoint(manifest.checkpointGeneration())) {
			throw new IOException("Previous checkpoint probe could not release its temporary solver");
		}
		return files.activatePrevious(previous);
	}

	private static void validateRecoveryIdentity(FluidRegion region, RegionManifest manifest) throws IOException {
		if (manifest.networkGeneration() != region.networkGeneration()
				|| !manifest.dimensionId().equals(region.dimension().identifier().toString())
				|| !manifest.origin().equals(MinecraftRegionCheckpointSource.position(region.origin())) || manifest.sizeX() != region.sizeX()
				|| manifest.sizeY() != region.sizeY() || manifest.sizeZ() != region.sizeZ()
				|| !manifest.solverConfig().equals(region.solverConfig())
				|| manifest.reservedMarkerCapacity() != region.reservedMarkerCapacity()
				|| !manifest.footprint().equals(MinecraftRegionCheckpointSource.footprint(region.footprint()))) {
			throw new IOException("Previous checkpoint does not match the admitted controller identity");
		}
	}

	private static FluidRegion unloadedRegion(RegionManifest manifest) throws IOException {
		validateControllerGeometry(manifest);
		Identifier dimensionId = Identifier.tryParse(manifest.dimensionId());
		if (dimensionId == null) throw new IOException("Previous checkpoint has an invalid dimension");
		return FluidRegion.unloaded(manifest.regionId(),
				ResourceKey.create(Registries.DIMENSION, dimensionId),
				MinecraftRegionCheckpointSource.blockPosition(manifest.origin()),
				manifest.sizeX(), manifest.sizeY(), manifest.sizeZ(), manifest.solverConfig(),
				manifest.reservedMarkerCapacity(), MinecraftRegionCheckpointSource.minecraftFootprint(manifest.footprint()),
				manifest.networkGeneration(),
				manifest.checkpointGeneration(), manifest.totalMarkerCount(),
				(float) manifest.totalVolume(), MinecraftRegionCheckpointSource.persistedRuntime(manifest.runtime()));
	}

	RegionState capture(FluidRegion region, long generation) throws IOException {
		return captureForFeatureGate(region, generation, true);
	}

	private RegionState captureForFeatureGate(
			FluidRegion region, long generation, boolean synchronizeGasTopology
	) throws IOException {
		MinecraftRegionCheckpointSource source = new MinecraftRegionCheckpointSource(region);
		if (profile.hasCapability(WorldCapability.THERMAL)) {
			if (thermalRegistry == null || gasRegistry == null) {
				throw new IOException("Authoritative thermal/gas registry is unavailable");
			}
			if (!region.domain().hasMaterialState()) throw new IOException("Thermal state requires a material-bound domain");
			if (profile.hasCapability(WorldCapability.CHEMISTRY)) {
				if (chemicalRegistry == null) throw new IOException("Authoritative chemical registry is unavailable");
				if (!region.domain().hasChemistryState()) region.domain().bindChemistry(chemicalRegistry);
			}
			if (profile.hasCapability(WorldCapability.GRANULAR)) {
				if (granularRegistry == null) throw new IOException("Authoritative granular registry is unavailable");
				if (!region.domain().hasGranularState()) {
					region.domain().bindGranular(granularRegistry, region.domain().materialRegistry());
				}
				enablePrecipitationIfConfigured(region);
			}
			if (!region.domain().hasThermalState()) {
				throw new IOException("Thermal state is unavailable for the active feature layout");
			}
			if (synchronizeGasTopology) {
				try {
					region.domain().synchronizeGasTopologyForPersistence();
				} catch (RuntimeException invalidTopology) {
					throw new IOException("Current carrier geometry cannot be joined to gas/thermal truth: "
							+ invalidTopology.getMessage() + "; previous HEAD remains authoritative",
							invalidTopology);
				}
			}
			return new RegionState(RegionCheckpointAssembler.captureThermal(source, generation));
		}
		if (profile.hasCapability(WorldCapability.GRANULAR)) {
			if (granularRegistry == null) throw new IOException("Authoritative granular registry is unavailable");
			if (!region.domain().hasMaterialState()) throw new IOException("Granular state requires a material-bound domain");
			if (profile.hasCapability(WorldCapability.CHEMISTRY)) {
				if (chemicalRegistry == null) throw new IOException("Authoritative chemical registry is unavailable");
				if (!region.domain().hasChemistryState()) region.domain().bindChemistry(chemicalRegistry);
			}
			if (!region.domain().hasGranularState()) {
				region.domain().bindGranular(granularRegistry, region.domain().materialRegistry());
			}
			enablePrecipitationIfConfigured(region);
			return new RegionState(RegionCheckpointAssembler.captureGranular(source, generation));
		}
		if (profile.hasCapability(WorldCapability.CHEMISTRY)) {
			if (chemicalRegistry == null) throw new IOException("Authoritative chemical registry is unavailable");
			if (!region.domain().hasMaterialState()) throw new IOException("Chemistry requires a material-bound domain");
			if (!region.domain().hasChemistryState()) region.domain().bindChemistry(chemicalRegistry);
			return new RegionState(RegionCheckpointAssembler.captureChemical(source, generation));
		}
		return new RegionState(region.domain().hasMaterialState()
				? RegionCheckpointAssembler.captureMaterial(source, generation)
				: RegionCheckpointAssembler.capture(source, generation));
	}

	private void restoreThermalGeneration(FluidRegion region, RegionState state) throws IOException {
		if (thermalRegistry == null || gasRegistry == null) {
			throw new IOException("Authoritative thermal/gas registry is unavailable");
		}
		RegionManifest manifest = state.manifest();
		RegionCheckpointRestorer restorer = new RegionCheckpointRestorer(state.checkpoint());
		if (manifest.layout() != RegionStateLayout.THERMAL) {
			throw new IOException("Checkpoint does not use the current thermal region layout");
		}
		long generation = manifest.checkpointGeneration();
		try {
			if (manifest.hasChemicalState()) {
				if (!profile.hasCapability(WorldCapability.CHEMISTRY) || chemicalRegistry == null) {
					throw new IOException("Chemical thermal checkpoint requires the chemistry registry and gate");
				}
				region.restoreChemicalSolver(restorer.reconstructChemical(chemicalRegistry), chemicalRegistry, generation);
			} else {
				region.restoreMaterialSolver(restorer.reconstructMaterial(), generation);
			}
			boolean hasGranular = manifest.hasGranularState();
			if (hasGranular) {
				if (!profile.hasCapability(WorldCapability.GRANULAR) || granularRegistry == null) {
					throw new IOException("Granular thermal checkpoint requires the granular registry and gate");
				}
				region.restoreGranularState(restorer.reconstructGranular(granularRegistry), granularRegistry, generation);
			} else if (profile.hasCapability(WorldCapability.GRANULAR)) {
				throw new IOException("Thermal checkpoint omits granular truth while the granular gate is enabled");
			}
			var thermal = restorer.reconstructThermal(thermalRegistry, gasRegistry, chemicalRegistry);
			region.restoreGasState(restorer.reconstructGas(gasRegistry, chemicalRegistry), gasRegistry, generation, thermal);
			region.restoreThermalState(thermal,
					thermalRegistry, generation, maximumThermalSubsteps);
			region.restoreFiniteThermalDevices(
					restorer.reconstructFiniteThermalDevices(thermalRegistry), thermalRegistry, generation);
			region.domain().restorePendingPhaseVolumeState(restorer.reconstructPendingPhaseVolume());
			enablePrecipitationIfConfigured(region);
			// Pressure is deliberately not checkpoint truth. A running thermal region that was
			// hydrostatically asleep must rebuild one real mechanical projection before the
			// thermal-only axis consumes local pressure for condensed equilibrium.
			if (!region.lifecycle().operatorPaused()
					&& region.lifecycle().state() == FluidRegionLifecycle.State.HYDROSTATIC_SLEEP) {
				region.wakeForActivity();
			}
		} catch (IOException | RuntimeException failure) {
			if (region.solverLoaded() && region.checkpointGeneration() == generation) {
				region.releaseSolverAfterCheckpoint(generation);
			}
			throw failure;
		}
	}

	private void restoreGranularGeneration(FluidRegion region, RegionState state) throws IOException {
		if (granularRegistry == null) throw new IOException("Authoritative granular registry is unavailable");
		RegionManifest manifest = state.manifest();
		RegionCheckpointRestorer restorer = new RegionCheckpointRestorer(state.checkpoint());
		long generation = manifest.checkpointGeneration();
		if (manifest.hasChemicalState()) {
			if (chemicalRegistry == null) throw new IOException("Authoritative chemical registry is unavailable");
			region.restoreChemicalSolver(restorer.reconstructChemical(chemicalRegistry), chemicalRegistry, generation);
		} else {
			region.restoreMaterialSolver(restorer.reconstructMaterial(), generation);
		}
		region.restoreGranularState(restorer.reconstructGranular(granularRegistry), granularRegistry, generation);
		enablePrecipitationIfConfigured(region);
	}

	private void restoreFeatureGeneration(FluidRegion region, RegionState state) throws IOException {
		RegionManifest manifest = state.manifest();
		RegionCheckpointRestorer restorer = new RegionCheckpointRestorer(state.checkpoint());
		if (profile.hasCapability(WorldCapability.THERMAL)) {
			restoreThermalGeneration(region, state);
			return;
		}
		if (profile.hasCapability(WorldCapability.GRANULAR)) {
			restoreGranularGeneration(region, state);
			return;
		}
		if (profile.hasCapability(WorldCapability.CHEMISTRY)) {
			if (chemicalRegistry == null) throw new IOException("Authoritative chemical registry is unavailable");
			region.restoreChemicalSolver(restorer.reconstructChemical(chemicalRegistry), chemicalRegistry,
					manifest.checkpointGeneration());
			return;
		}
		if (profile.hasCapability(WorldCapability.MATERIALS)) {
			region.restoreMaterialSolver(restorer.reconstructMaterial(), manifest.checkpointGeneration());
		} else {
			region.restoreSolver(restorer.reconstruct(), manifest.checkpointGeneration());
		}
	}

	private void enablePrecipitationIfConfigured(FluidRegion region) {
		if (profile.hasCapability(WorldCapability.PRECIPITATION)) region.domain().setPrecipitationEnabled(true);
	}

	private void validateFeatureShape(RegionManifest manifest) throws IOException {
		RegionStateLayout expectedLayout = profile.hasCapability(WorldCapability.THERMAL) ? RegionStateLayout.THERMAL
				: profile.hasCapability(WorldCapability.GRANULAR) ? RegionStateLayout.GRANULAR
				: profile.hasCapability(WorldCapability.CHEMISTRY) ? RegionStateLayout.CHEMICAL
				: profile.hasCapability(WorldCapability.MATERIALS) ? RegionStateLayout.MATERIAL : RegionStateLayout.PURE;
		if (manifest.layout() != expectedLayout
				|| manifest.hasChemicalState() != profile.hasCapability(WorldCapability.CHEMISTRY)
				|| manifest.hasGranularState() != profile.hasCapability(WorldCapability.GRANULAR)
				|| manifest.hasThermalState() != profile.hasCapability(WorldCapability.THERMAL)
				|| manifest.hasGasState() != profile.hasCapability(WorldCapability.THERMAL)) {
			throw new IOException("Persisted region layout does not match the committed world capability profile");
		}
	}

	private static void validateControllerGeometry(RegionManifest manifest) throws IOException {
		int cellsPerBlock = Math.round(1.0F / manifest.solverConfig().cellSize());
		if (cellsPerBlock <= 0 || Math.abs(cellsPerBlock * manifest.solverConfig().cellSize() - 1.0F) > 1.0E-6F
				|| manifest.sizeX() != Math.multiplyExact(manifest.footprint().blockSizeX(), cellsPerBlock)
				|| manifest.sizeY() != Math.multiplyExact(manifest.footprint().blockSizeY(), cellsPerBlock)
				|| manifest.sizeZ() != Math.multiplyExact(manifest.footprint().blockSizeZ(), cellsPerBlock)) {
			throw new IOException("Persisted solver grid does not match its visible controller footprint");
		}
	}
	private static void requireRegistry(WorldCapabilityProfile profile, WorldCapability capability, Object registry) {
		if (profile.hasCapability(capability) != (registry != null)) {
			throw new IllegalArgumentException("Registry " + capability.stableId()
					+ " does not match the world capability profile");
		}
	}
}
