package mmm.simulation;

import mmm.MmmMod;
import mmm.model.region.RegionManifest;
import mmm.model.region.RegionStateLayout;
import mmm.model.region.RegionSectionState;

import mmm.application.RegionCatalog;
import mmm.chemistry.ChemicalRegistry;
import mmm.fluid.HostFluidState;
import mmm.fluid.FluidDefinitions;
import mmm.fluid.MaterialRegistry;
import mmm.fluid.air.GasRegistry;
import mmm.granular.GranularRegistry;
import mmm.simulation.ownership.OwnershipCommitted;
import mmm.simulation.ownership.OwnershipFiles;
import mmm.simulation.ownership.OwnershipStore;
import mmm.simulation.persistence.GenerationSequence;
import mmm.simulation.persistence.EnvironmentThermalStore;
import mmm.simulation.persistence.RegionCheckpointAssembler;
import mmm.simulation.persistence.RegionCheckpointRestorer;
import mmm.simulation.persistence.RegionState;
import mmm.simulation.persistence.RegionGenerationStore;
import mmm.simulation.persistence.RemovedRegionManifest;
import mmm.thermal.ThermalRegistry;
import mmm.thermal.ThermalTransport;
import mmm.worldprofile.WorldCapability;
import mmm.worldprofile.WorldCapabilityProfile;
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
    private final EnvironmentThermalStore worldStore;
    private EnvironmentThermalStore.Snapshot worldSnapshot;
    private WorldThermalEnvironment environment;
    private FluidRegionManager manager;
    private java.util.function.Supplier<java.util.Map<String,mmm.pipe.PipeDimensionState>> pipes;
    private boolean mirroringWorld;
    private java.util.function.Predicate<UUID> worldCheckpointBarrier = id -> false;
    private final java.util.Map<UUID,mmm.thermal.EnvironmentThermalState> stagedEnvironment = new java.util.HashMap<>();
    private final java.util.Set<UUID> externalEnvironmentCandidates = new java.util.HashSet<>();
    private final java.util.Map<UUID,List<RegionGenerationStore.PreparedRemoval>> mergeRemovals = new java.util.HashMap<>();
	private final OwnershipStore ownershipStore;
	private final WorldCapabilityProfile profile;
	private final FluidDefinitions definitions;
	private final ChemicalRegistry chemicalRegistry;
	private final GranularRegistry granularRegistry;
	private final ThermalRegistry thermalRegistry;
	private final GasRegistry gasRegistry;
	/** World material properties from the committed capability bundle; required for material-bound liquid domains. */
	private final MaterialRegistry materialDefinitions;
	private final int maximumThermalSubsteps;
	private final java.util.Map<UUID, PendingRestore> pendingRestores = new java.util.HashMap<>();
	private final java.util.Map<UUID, Long> sectionReadTicks = new java.util.HashMap<>();

	RegionRepositoryAdapter(
			Path worldRoot, WorldCapabilityProfile profile, ChemicalRegistry chemicalRegistry,
			GranularRegistry granularRegistry, ThermalRegistry thermalRegistry, GasRegistry gasRegistry,
			MaterialRegistry materialDefinitions
	) {
		this(worldRoot, profile, chemicalRegistry, granularRegistry, thermalRegistry, gasRegistry,
				ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS, materialDefinitions);
	}

	RegionRepositoryAdapter(
			Path worldRoot, WorldCapabilityProfile profile, ChemicalRegistry chemicalRegistry,
			GranularRegistry granularRegistry, ThermalRegistry thermalRegistry, GasRegistry gasRegistry,
			int maximumThermalSubsteps, MaterialRegistry materialDefinitions
	) {
		Objects.requireNonNull(profile, "profile");
		definitions = new FluidDefinitions(materialDefinitions, chemicalRegistry, gasRegistry, granularRegistry, thermalRegistry);
		var fingerprints=profile.registryFingerprints();
		if (!fingerprints.material().equals(materialDefinitions.fingerprint())
				|| !fingerprints.chemical().equals(chemicalRegistry.fingerprint())
				|| !fingerprints.granular().equals(granularRegistry.fingerprint())
				|| !fingerprints.gas().equals(gasRegistry.fingerprint())
				|| !fingerprints.thermal().equals(thermalRegistry.fingerprint()))
			throw new IllegalArgumentException("World definitions differ from the committed profile");
		files = switch (profile.regionLayoutId()) {
			case "pure", "material" -> new RegionGenerationStore(worldRoot);
			case "chemical" -> new RegionGenerationStore(worldRoot, chemicalRegistry);
			case "granular" -> new RegionGenerationStore(worldRoot, chemicalRegistry, granularRegistry);
			case "thermal" -> new RegionGenerationStore(worldRoot, chemicalRegistry, granularRegistry, thermalRegistry,
					gasRegistry);
			default -> throw new IllegalArgumentException("Unknown region layout " + profile.regionLayoutId());
		};
        worldStore = new EnvironmentThermalStore(worldRoot, chemicalRegistry, granularRegistry, thermalRegistry, gasRegistry);
		ownershipStore = new OwnershipStore(worldRoot, chemicalRegistry, granularRegistry, thermalRegistry, gasRegistry);
		this.profile = profile;
		this.chemicalRegistry = chemicalRegistry;
		this.granularRegistry = granularRegistry;
		this.thermalRegistry = thermalRegistry;
		this.gasRegistry = gasRegistry;
		this.materialDefinitions = materialDefinitions;
		if (maximumThermalSubsteps <= 0
				|| maximumThermalSubsteps > ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS) {
			throw new IllegalArgumentException("Thermal transport substep budget is invalid");
		}
		this.maximumThermalSubsteps = maximumThermalSubsteps;
	}

    void worldCheckpointBarrier(java.util.function.Predicate<UUID> barrier) { worldCheckpointBarrier=Objects.requireNonNull(barrier); }
    void environment(WorldThermalEnvironment environment, FluidRegionManager manager) throws IOException {
        this.environment=Objects.requireNonNull(environment); this.manager=Objects.requireNonNull(manager);
        if(worldSnapshot!=null) environment.restore(worldSnapshot.environments());
        files.commitHook(new RegionGenerationStore.CommitHook() {
            public void beforeCommit(RegionState target) throws IOException { publishWorldCheckpoint(target,null); }
            public void beforeRemoval(RemovedRegionManifest removed) throws IOException { publishWorldCheckpoint(null,removed); }
            public void beforePrepare(UUID id) throws IOException { reconcileWorldMirror(id); }
            public RegionGenerationStore.CommittedIdentity committed(UUID id) {
                if(worldSnapshot==null || mirroringWorld)return null;
                var state=worldSnapshot.regions().get(id);
                if(state!=null)return new RegionGenerationStore.CommittedIdentity(state.manifest().checkpointGeneration(),state.manifest().checksum());
                var removed=worldSnapshot.removals().get(id);
                return removed==null?new RegionGenerationStore.CommittedIdentity(0L,-1L):new RegionGenerationStore.CommittedIdentity(removed.generation(),removed.checksum());
            }
        });
    }

    private void reconcileWorldMirror(UUID id) throws IOException {
        if(mirroringWorld || worldSnapshot==null)return;
        var removal=worldSnapshot.removals().get(id);
        var state=worldSnapshot.regions().get(id);
        mirroringWorld=true;
        try {
            if(removal!=null) files.completeWorldRemoval(id,removal.generation(),removal.checksum());
            else if(state!=null) {
                long generation=files.physicalCommittedGeneration(id);
                if(generation<state.manifest().checkpointGeneration()) files.publish(state);
                else if(generation!=state.manifest().checkpointGeneration()
                        || files.physicalCommittedManifestChecksum(id)!=state.manifest().checksum())
                    throw new IOException("Region mirror differs from world checkpoint "+id);
            }
        } finally { mirroringWorld=false; }
    }

    void checkpointWorld() throws IOException {
        if(environment!=null)publishWorldCheckpoint(null,null);
    }

    java.util.Map<String,mmm.pipe.PipeDimensionState> recoveredPipes() {
        return worldSnapshot==null?java.util.Map.of():worldSnapshot.pipes();
    }
    void pipes(java.util.function.Supplier<java.util.Map<String,mmm.pipe.PipeDimensionState>> value) {
        pipes=Objects.requireNonNull(value);
    }

    private void publishWorldCheckpoint(RegionState target, RemovedRegionManifest removal) throws IOException {
        if(mirroringWorld || environment==null)return;
        UUID targetId=target!=null?target.manifest().regionId():removal!=null?removal.regionId():null;
        var regions = new java.util.HashMap<UUID,RegionState>();
        var removals = new java.util.HashMap<UUID,EnvironmentThermalStore.Removal>();
        if(worldSnapshot!=null){regions.putAll(worldSnapshot.regions()); removals.putAll(worldSnapshot.removals());}
        var retiring=mergeRemovals.getOrDefault(targetId,List.of());
        var retiredIds=retiring.stream().map(r->r.manifest().regionId()).collect(java.util.stream.Collectors.toSet());
        var live = new java.util.ArrayList<FluidRegion>();
        for(var region:manager.regions()) {
            if(!region.hasSelectedVolume() || region.id().equals(targetId) || retiredIds.contains(region.id()))continue;
            if(worldCheckpointBarrier.test(region.id()) || ownershipLockDescription(region)!=null)
                throw new IOException("Another region has a pending world transaction: "+region.id());
            if(region.solverLoaded()) {
                long generation=GenerationSequence.next(region.checkpointGeneration(),"World checkpoint");
                regions.put(region.id(),capture(region,generation)); live.add(region);
            } else if(!regions.containsKey(region.id())) regions.put(region.id(),files.read(region.id()));
        }
        if(target!=null) { regions.put(targetId,target); removals.remove(targetId); }
        else if(removal!=null) { regions.remove(targetId); removals.put(targetId,new EnvironmentThermalStore.Removal(removal.targetGeneration(),removal.checksum())); }
        for(var prepared:retiring) {
            var retired=prepared.manifest(); regions.remove(retired.regionId());
            removals.put(retired.regionId(),new EnvironmentThermalStore.Removal(retired.targetGeneration(),retired.checksum()));
        }
        var environments=new java.util.HashMap<>(environment.capture());
        var override=stagedEnvironment.get(targetId);
        if(override!=null) {
            var base=environments.get(override.dimensionId());
            if(base==null || override.generation()!=Math.addExact(base.generation(),1L))
                throw new IOException("Environment candidate is stale before world checkpoint");
            environments.put(override.dimensionId(),override);
        }
        boolean external=externalEnvironmentCandidates.contains(targetId);
        try(var prepared = override == null || external ? null : environment.prepareCheckpoint(override)) {
            var candidate=worldStore.prepare(environments,regions,removals,pipes==null?recoveredPipes():pipes.get());
            if(prepared!=null && !prepared.isCurrent())throw new IOException("Environment candidate changed before world publication");
            try { worldStore.commit(candidate); }
            catch (IOException failure) {
                if(!worldStore.isPublished(candidate))throw failure;
                MmmMod.LOGGER.warn("World checkpoint published despite a post-publication failure",failure);
            }
            worldSnapshot=candidate.snapshot();
            if(prepared!=null) environment.adoptCheckpoint(override,prepared);
        }
        for(var region:live) {
            var state=regions.get(region.id());
            if(!region.recordPublishedCheckpoint(state.manifest().checkpointGeneration()))
                throw new IOException("World checkpoint was not accepted by region "+region.id());
        }
        mirroringWorld=true;
        try {
            for(var region:live) files.publish(regions.get(region.id()));
        } finally { mirroringWorld=false; }
        stagedEnvironment.remove(targetId); externalEnvironmentCandidates.remove(targetId);
    }

    /** Prepares retirement records; the ownership commit publishes them with the replacement in one world HEAD. */
    void prepareMerge(FluidRegion candidate,List<FluidRegion> sources,long maximumEntries,long maximumBytes) throws IOException {
        if(environment==null)throw new IOException("Region joining requires the shared world checkpoint");
        if(mergeRemovals.containsKey(candidate.id()))throw new IOException("Region joining is already pending");
        var staged=new ArrayList<RegionGenerationStore.PreparedRemoval>();
        try {
            for(var source:sources) {
                if(worldCheckpointBarrier.test(source.id()) || ownershipLockDescription(source)!=null)
                    throw new IOException("Region has an unfinished transaction: "+source.id());
                reconcileWorldMirror(source.id());
                if(!source.id().equals(candidate.id()))staged.add(prepareRemoval(source,
                        Math.addExact(source.networkGeneration(),1),UUID.randomUUID(),maximumEntries,maximumBytes));
            }
            mergeRemovals.put(candidate.id(),List.copyOf(staged));
        } catch(IOException|RuntimeException failure) {
            for(var prepared:staged)try{files.abortRemoval(prepared);}catch(IOException cleanup){failure.addSuppressed(cleanup);}
            throw failure;
        }
    }

    /** Completes or discards only unpublished staging; a published world HEAD remains authoritative. */
    void finishMerge(UUID target) throws IOException {
        var staged=mergeRemovals.remove(target);
        if(staged==null)return;
        IOException failure=null;
        for(var prepared:staged)try {
            var manifest=prepared.manifest();
            var durable=worldSnapshot==null?null:worldSnapshot.removals().get(manifest.regionId());
            if(durable!=null && durable.generation()==manifest.targetGeneration() && durable.checksum()==manifest.checksum()) {
                reconcileWorldMirror(manifest.regionId());
                files.convergeRemovalQuarantine(manifest.regionId());
                deleteRetiredOwnership(manifest.regionId());
            } else files.abortRemoval(prepared);
        } catch(IOException issue) { if(failure==null)failure=issue;else failure.addSuppressed(issue); }
        if(failure!=null)throw failure;
    }

    private void deleteRetiredOwnership(UUID id) throws IOException {
        var ownership=ownershipStore.files(id);
        ownership.assertUnlockedForTransition();
        ownership.deleteStore();
    }

	OwnershipTransactionCoordinator newOwnershipTransactions() {
		return new OwnershipTransactionCoordinator(this, files, ownershipStore);
	}

	mmm.thermal.FiniteThermalDeviceInventory readFiniteDevices(UUID id) throws IOException {
        if(worldSnapshot!=null && worldSnapshot.regions().containsKey(id))
            return new RegionCheckpointRestorer(worldSnapshot.regions().get(id).checkpoint()).reconstructFiniteThermalDevices(thermalRegistry);
        return files.readFiniteDevices(id,thermalRegistry);
    }

	List<String> loadMetadata(FluidRegionManager manager) {
		List<String> issues = new ArrayList<>();
		RegionGenerationStore.Discovery discovery;
		try {
            worldSnapshot = worldStore.read();
            if (worldSnapshot != null) for (var entry : worldSnapshot.removals().entrySet()) {
                files.completeWorldRemoval(entry.getKey(),entry.getValue().generation(),entry.getValue().checksum());
                deleteRetiredOwnership(entry.getKey());
            }
            if (worldSnapshot != null) for (var state : worldSnapshot.regions().values()) {
                long mirror = files.committedGeneration(state.manifest().regionId());
                if (mirror < state.manifest().checkpointGeneration()) files.publish(state);
                else if (mirror != state.manifest().checkpointGeneration()
                        || files.committedManifestChecksum(state.manifest().regionId()) != state.manifest().checksum())
                    throw new IOException("Region mirror differs from world checkpoint " + state.manifest().regionId());
            }
			files.recoverKnownFirstPublicationStaging();
			discovery = files.discover();
		} catch (IOException exception) {
			return List.of("无法恢复或扫描 region-state：" + exception.getMessage());
		}
		for (var issue : discovery.issues().entrySet()) issues.add(issue.getKey().getFileName() + " 已锁定：" + issue.getValue());
        var manifests = worldSnapshot == null ? discovery.manifests()
                : worldSnapshot.regions().values().stream().map(RegionState::manifest).toList();
		for (RegionManifest manifest : manifests) {
			try {
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
				validateFeatureShape(manifest);
				validateControllerGeometry(manifest);
				Identifier dimensionId = Identifier.tryParse(manifest.dimensionId());
				if (dimensionId == null) throw new IOException("invalid dimension");
				ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
				FluidRegion region = FluidRegion.unloaded(manifest.regionId(), dimension,
						MinecraftRegionCheckpointSource.blockPosition(manifest.origin()),
						manifest.sizeX(), manifest.sizeY(), manifest.sizeZ(), manifest.solverConfig(),
						MinecraftRegionCheckpointSource.minecraftFootprint(manifest.footprint()),
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
		if (!region.hasSelectedVolume() || !region.solverLoaded()) throw new IOException("Region has no persistent live solver");
		String ownershipLock = ownershipLockDescription(region);
		if (ownershipLock != null) {
			throw new IOException("Unresolved world ownership state blocks checkpoint: " + ownershipLock);
		}
		long generation = GenerationSequence.next(region.checkpointGeneration(), "Region checkpoint");
		RegionState state = capture(region, generation);
		stageEnvironment(region);
		try {
			return publishAndRecord(region, generation, state.manifest().checksum(),
					() -> files.publish(state), "Published checkpoint was not accepted by the live region");
		} finally { clearStagedEnvironment(region.id()); }
	}

	void stageEnvironment(FluidRegion region) {
		if (region.environmentCandidate() != null) {
            stagedEnvironment.put(region.id(), region.environmentCandidate());
            if(region.environmentCandidateExternal())externalEnvironmentCandidates.add(region.id());
        }
	}
	void clearStagedEnvironment(UUID regionId) { stagedEnvironment.remove(regionId); externalEnvironmentCandidates.remove(regionId); }

	boolean worldCheckpointContains(FluidRegion candidate, long generation) {
		if (worldSnapshot == null) return false;
		var saved = worldSnapshot.regions().get(candidate.id());
		return saved != null && saved.manifest().checkpointGeneration() == generation
				&& saved.manifest().networkGeneration() == candidate.networkGeneration()
				&& saved.manifest().footprint().equals(MinecraftRegionCheckpointSource.footprint(candidate.footprint()));
	}

	/**
	 * Publishes HEAD and adopts the durable result even when the publish call itself fails.
	 *
	 * <p>HEAD moves before the store reads the generation back, so a readback failure can leave the
	 * disk one generation ahead of the live region. Recording the durable generation keeps the next
	 * capture aligned with its own published HEAD instead of colliding with it forever.</p>
	 */
	private long publishAndRecord(
			FluidRegion region, long generation, long manifestChecksum,
			Publisher publisher, String rejection
	) throws IOException {
		long published;
		try {
			published = publisher.publish();
		} catch (IOException | RuntimeException failure) {
			try {
				long durableGeneration = committedGeneration(region.id());
				if (durableGeneration != generation
						|| committedManifestChecksum(region.id()) != manifestChecksum) throw failure;
				published = durableGeneration;
                clearStagedEnvironment(region.id());
				MmmMod.LOGGER.warn("Region HEAD {} for region {} committed despite a post-publish exception",
						published, region.id(), failure);
			} catch (IOException | RuntimeException inspectionFailure) {
				if (inspectionFailure != failure) failure.addSuppressed(inspectionFailure);
				throw failure;
			}
		}
		if (!region.recordPublishedCheckpoint(published)) throw new IOException(rejection);
		return published;
	}

	private interface Publisher {
		long publish() throws IOException;
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
        stageEnvironment(region);
        try {
            RegionGenerationStore.PreparedGeneration prepared = files.prepare(capture(region, targetGeneration), (point, path) -> { });
            return new StagedCheckpoint(prepared, prepared.manifestChecksum());
        } catch (IOException | RuntimeException failure) {
            clearStagedEnvironment(region.id()); throw failure;
        }
	}

	/** Stages a private lifecycle result from its own complete carrier geometry. */
	StagedCheckpoint stageLifecycleCheckpoint(
			FluidRegion region, HostFluidState candidateDomain, long targetGeneration
	) throws IOException {
		return stageCandidateCheckpoint(region, candidateDomain, targetGeneration, true);
	}

	/** Stages a complete private solver successor while preserving the supplied operator intent. */
	StagedCheckpoint stageCandidateCheckpoint(
			FluidRegion region, HostFluidState candidateDomain, long targetGeneration, boolean operatorPaused
	) throws IOException {
		if (!GenerationSequence.isNext(region.checkpointGeneration(), targetGeneration)) {
			throw new IOException("Candidate checkpoint target does not advance the live region by one generation");
		}
		String ownershipLock = ownershipLockDescription(region);
		if (ownershipLock != null) throw new IOException("Unresolved world ownership state blocks candidate staging: " + ownershipLock);
		FluidRegion candidate = region.lifecycleCandidate(candidateDomain, operatorPaused);
		RegionGenerationStore.PreparedGeneration prepared = files.prepare(
				captureForFeatureGate(candidate, targetGeneration), (point, path) -> { });
		return new StagedCheckpoint(prepared, prepared.manifestChecksum());
	}

	/** Publishes only HEAD; the caller then applies its already-validated live assignment and records the generation. */
	long commitStagedLifecycleCheckpoint(StagedCheckpoint staged) throws IOException {
        try { return files.commit(staged.prepared(), (point, path) -> { }); }
        catch(IOException | RuntimeException failure) {
            if(committedGeneration(staged.prepared().id())==staged.prepared().generation()
                    && committedManifestChecksum(staged.prepared().id())==staged.manifestChecksum()) {
                clearStagedEnvironment(staged.prepared().id());
                return staged.prepared().generation();
            }
            throw failure;
        }
	}

	long commitStagedCheckpoint(FluidRegion region, StagedCheckpoint staged) throws IOException {
		return publishAndRecord(region, staged.prepared().generation(), staged.manifestChecksum(),
				() -> files.commit(staged.prepared(), (point, path) -> { }),
				"Published staged checkpoint was not accepted");
	}

	void abortStagedCheckpoint(StagedCheckpoint staged) throws IOException {
        clearStagedEnvironment(staged.prepared().id());
        if(worldSnapshot!=null) {
            var committed=worldSnapshot.regions().get(staged.prepared().id());
            if(committed!=null && committed.manifest().checkpointGeneration()==staged.prepared().generation()
                    && committed.manifest().checksum()==staged.manifestChecksum())return;
        }
		files.abort(staged.prepared());
	}

	record StagedCheckpoint(RegionGenerationStore.PreparedGeneration prepared, long manifestChecksum) { }

	/** Per-region fail-closed guard used for both loaded stepping and every ordinary checkpoint path. */
	String ownershipLockDescription(FluidRegion region) {
		if (!region.hasSelectedVolume()) return null;
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

	/** Explicit transactions finish the same pinned read synchronously. */
	void restore(FluidRegion region) throws IOException {
		advanceRestore(region, Integer.MAX_VALUE);
	}

	record RestoreProgress(int loadedSections, int totalSections, boolean restored) { }

	RestoreProgress restoreIncrementally(FluidRegion region, long serviceTick, int sectionBudget) throws IOException {
		if (sectionBudget <= 0) throw new IllegalArgumentException("Section read budget must be positive");
		Long previousTick = sectionReadTicks.put(region.id(), serviceTick);
		return advanceRestore(region, previousTick != null && previousTick == serviceTick ? 0 : sectionBudget);
	}

	void cancelRestore(UUID regionId) { pendingRestores.remove(regionId); }
	void cancelAllRestores() { pendingRestores.clear(); sectionReadTicks.clear(); }

	void retainRestores(FluidRegionManager manager) {
		pendingRestores.entrySet().removeIf(entry -> manager.region(entry.getKey()) != entry.getValue().region
				|| entry.getValue().region.solverLoaded());
		sectionReadTicks.keySet().removeIf(id -> manager.region(id) == null);
	}

	private RestoreProgress advanceRestore(FluidRegion region, int budget) throws IOException {
		if (region.solverLoaded() || region.lifecycle().state() != FluidRegionLifecycle.State.UNLOADED) {
			cancelRestore(region.id());
			return new RestoreProgress(0, 0, region.solverLoaded());
		}
		try {
			var pending = pendingRestores.get(region.id());
			if (pending == null || pending.region != region) {
                reconcileWorldMirror(region.id());
				pending = new PendingRestore(region, new SectionRead(region.id()));
				validateRestoreManifest(region, pending.read.view.manifest());
				pendingRestores.put(region.id(), pending);
			}
			pending.read.advance(budget);
			int loaded = pending.read.sections.size(), total = pending.read.view.manifest().sections().size();
			if (loaded != total) return new RestoreProgress(loaded, total, false);
			RegionState state = pending.read.complete();
			validateRestoreManifest(region, state.manifest());
			files.verifyReadView(pending.read.view);
			restoreFeatureGeneration(region, state, pending.read.view);
			cancelRestore(region.id());
			return new RestoreProgress(loaded, total, true);
		} catch (IOException | RuntimeException failure) {
			cancelRestore(region.id());
			throw failure;
		}
	}

	private static final class PendingRestore {
		final FluidRegion region;
		final SectionRead read;
		PendingRestore(FluidRegion region, SectionRead read) { this.region = region; this.read = read; }
	}

	private final class SectionRead {
		final RegionGenerationStore.ReadView view;
		final ArrayList<RegionSectionState> sections = new ArrayList<>();
		SectionRead(UUID regionId) throws IOException { view = files.openRead(regionId); }
		void advance(int budget) throws IOException {
			var descriptors = view.manifest().sections();
			int count = Math.min(budget, descriptors.size() - sections.size());
			for (int i = 0; i < count; i++) {
				var descriptor = descriptors.get(sections.size());
				sections.add(files.readSection(view, new RegionSectionState.SectionKey(
						descriptor.sectionX(), descriptor.sectionY(), descriptor.sectionZ())));
			}
		}
		RegionState complete() throws IOException { return files.completeRead(view, sections); }
	}

	private RegionState readComplete(UUID regionId) throws IOException {
        if(worldSnapshot!=null && worldSnapshot.regions().containsKey(regionId))return worldSnapshot.regions().get(regionId);
		var read = new SectionRead(regionId);
		read.advance(Integer.MAX_VALUE);
		return read.complete();
	}

	private void validateRestoreManifest(FluidRegion region, RegionManifest manifest) throws IOException {
		validateFeatureShape(manifest);
		if (manifest.checkpointGeneration() != region.checkpointGeneration() || manifest.networkGeneration() != region.networkGeneration()
				|| !manifest.dimensionId().equals(region.dimension().identifier().toString())
				|| !manifest.origin().equals(MinecraftRegionCheckpointSource.position(region.origin())) || manifest.sizeX() != region.sizeX()
				|| manifest.sizeY() != region.sizeY() || manifest.sizeZ() != region.sizeZ()
				|| !manifest.footprint().equals(MinecraftRegionCheckpointSource.footprint(region.footprint()))) {
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
		RegionState state = readComplete(region.id());
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
		region.adoptFiniteDeviceCandidate(candidate, targetGeneration);
	}

	private static void validateRecoveryIdentity(FluidRegion region, RegionManifest manifest) throws IOException {
		var expectedFootprint = MinecraftRegionCheckpointSource.footprint(region.footprint());
		var actualFootprint = manifest.footprint();
		if (expectedFootprint.kind() == mmm.model.region.RegionGeometry.RegionKind.POOL
				&& actualFootprint.kind() == expectedFootprint.kind()
				&& actualFootprint.returnedBlocks().containsAll(expectedFootprint.returnedBlocks())) {
			expectedFootprint = new mmm.model.region.RegionGeometry.SelectedVolume(expectedFootprint.minimumInterior(), expectedFootprint.maximumInterior(), expectedFootprint.interiorBlocks(),
					expectedFootprint.requiredChunks(), expectedFootprint.kind(), expectedFootprint.poolShellBlocks(),
					expectedFootprint.atmosphereBlocks(), actualFootprint.returnedBlocks(), expectedFootprint.name());
		}
		if (manifest.networkGeneration() != region.networkGeneration()
				|| !manifest.dimensionId().equals(region.dimension().identifier().toString())
				|| !manifest.origin().equals(MinecraftRegionCheckpointSource.position(region.origin())) || manifest.sizeX() != region.sizeX()
				|| manifest.sizeY() != region.sizeY() || manifest.sizeZ() != region.sizeZ()
				|| !manifest.solverConfig().equals(region.solverConfig())
				|| !manifest.footprint().equals(expectedFootprint)) {
			throw new IOException("Previous checkpoint does not match the admitted region identity");
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
				MinecraftRegionCheckpointSource.minecraftFootprint(manifest.footprint()),
				manifest.networkGeneration(),
				manifest.checkpointGeneration(), manifest.totalMarkerCount(),
				(float) manifest.totalVolume(), MinecraftRegionCheckpointSource.persistedRuntime(manifest.runtime()));
	}

	RegionState capture(FluidRegion region, long generation) throws IOException {
		return captureForFeatureGate(region, generation);
	}

	private RegionState captureForFeatureGate(
			FluidRegion region, long generation
	) throws IOException {
		HostFluidState host = region.deviceResident() ? region.hostSnapshot() : region.editableHost();
		if (host.hasMaterialState() && !host.authoritativeLayout()
				.materialDefinitionsFingerprint().isEmpty() && !host.authoritativeLayout()
				.materialDefinitionsFingerprint().equals(materialDefinitions.fingerprint())) {
			throw new IOException("Region material definitions differ from the committed world bundle");
		}
		if (host.hasMaterialState()) host.bindMaterialDefinitions(materialDefinitions);
		MinecraftRegionCheckpointSource source = new MinecraftRegionCheckpointSource(region, host);
		if (profile.hasCapability(WorldCapability.THERMAL)) {
			if (thermalRegistry == null || gasRegistry == null) {
				throw new IOException("Authoritative thermal/gas registry is unavailable");
			}
			if (!host.hasMaterialState()) throw new IOException("Thermal state requires a material-bound domain");
			if (profile.hasCapability(WorldCapability.CHEMISTRY)) {
				if (chemicalRegistry == null) throw new IOException("Authoritative chemical registry is unavailable");
				if (!host.hasChemistryState()) host.bindChemistry(chemicalRegistry);
			}
			if (profile.hasCapability(WorldCapability.GRANULAR)) {
				if (granularRegistry == null) throw new IOException("Authoritative granular registry is unavailable");
				if (!host.hasGranularState()) {
					host.bindGranular(granularRegistry, host.materialRegistry(), materialDefinitions);
				}
				enablePrecipitationIfConfigured(host);
			}
			if (!host.hasThermalState()) {
				throw new IOException("Thermal state is unavailable for the active feature layout");
			}
			return new RegionState(RegionCheckpointAssembler.captureThermal(source, generation));
		}
		if (profile.hasCapability(WorldCapability.GRANULAR)) {
			if (granularRegistry == null) throw new IOException("Authoritative granular registry is unavailable");
			if (!host.hasMaterialState()) throw new IOException("Granular state requires a material-bound domain");
			if (profile.hasCapability(WorldCapability.CHEMISTRY)) {
				if (chemicalRegistry == null) throw new IOException("Authoritative chemical registry is unavailable");
				if (!host.hasChemistryState()) host.bindChemistry(chemicalRegistry);
			}
			if (!host.hasGranularState()) {
				host.bindGranular(granularRegistry, host.materialRegistry(), materialDefinitions);
			}
			enablePrecipitationIfConfigured(host);
			return new RegionState(RegionCheckpointAssembler.captureGranular(source, generation));
		}
		if (profile.hasCapability(WorldCapability.CHEMISTRY)) {
			if (chemicalRegistry == null) throw new IOException("Authoritative chemical registry is unavailable");
			if (!host.hasMaterialState()) throw new IOException("Chemistry requires a material-bound domain");
			if (!host.hasChemistryState()) host.bindChemistry(chemicalRegistry);
			return new RegionState(RegionCheckpointAssembler.captureChemical(source, generation));
		}
		return new RegionState(host.hasMaterialState()
				? RegionCheckpointAssembler.captureMaterial(source, generation)
				: RegionCheckpointAssembler.capture(source, generation));
	}

	private void restoreFeatureGeneration(FluidRegion region,RegionState state)throws IOException {
		restoreFeatureGeneration(region, state, null);
	}

	private void restoreFeatureGeneration(FluidRegion region, RegionState state, RegionGenerationStore.ReadView readView) throws IOException {
		var restorer=new RegionCheckpointRestorer(state.checkpoint());long generation=state.manifest().checkpointGeneration();
		try {
			var authoritative=restorer.reconstructAuthoritativeState(definitions);
			if (authoritative.sealedDomainBoundary())
				throw new IOException("Saved gas exterior does not match the region kind");
			if (readView != null) files.verifyReadView(readView);
			region.restoreAuthoritativeSolver(authoritative,generation);
			if(authoritative.thermalOwner()!=null)region.restoreFiniteThermalDevices(restorer.reconstructFiniteThermalDevices(thermalRegistry),thermalRegistry,generation);
			enablePrecipitationIfConfigured(region.editableHost());
			if(authoritative.thermalOwner()!=null&&!region.lifecycle().operatorPaused()&&region.lifecycle().state()==FluidRegionLifecycle.State.HYDROSTATIC_SLEEP)region.wakeForActivity();
		}catch(IOException|RuntimeException failure){if(region.solverLoaded()&&region.checkpointGeneration()==generation)region.releaseSolverAfterCheckpoint(generation);throw failure;}
	}

	private void enablePrecipitationIfConfigured(HostFluidState host) {
		if (profile.hasCapability(WorldCapability.PRECIPITATION)) host.setPrecipitationEnabled(true);
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
				|| profile.hasCapability(WorldCapability.THERMAL) && !manifest.hasGasState()) {
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
}
