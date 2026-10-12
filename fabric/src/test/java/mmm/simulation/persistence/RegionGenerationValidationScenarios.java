package mmm.simulation.persistence;

import mmm.simulation.RegionCheckpointFixtures;
import mmm.simulation.MinecraftRegionCheckpointSource;

import mmm.model.region.RegionExtension;
import mmm.model.region.RegionManifest;
import mmm.model.region.RegionSectionState;
import mmm.model.region.RegionStateLayout;

import mmm.application.RegionCatalog;
import mmm.fluid.FlipParticleState;
import mmm.simulation.FluidRegion;
import mmm.simulation.FluidRegionLifecycle;
import mmm.simulation.FluidRegionManager;
import mmm.simulation.FluidServerConfig;
import mmm.worldprofile.RegistryFingerprintSet;
import mmm.worldprofile.WorldCapabilityProfile;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

abstract class RegionGenerationValidationScenarios extends RegionPersistenceValidationSupport {
	static void validateExactRoundTripAndMetadataUnload() throws Exception {
		Path root = Files.createTempDirectory(Path.of("."), "roundtrip-");
		FluidRegion region = populatedRegion(new UUID(11L, 12L));
		FlipParticleState before = region.domain().exportParticleState();
		RegionState state = RegionCheckpointFixtures.capture(region, 1L);
		require(state.sections().size() == 4, "cross-chunk controller was not split into four complete sections");
		RegionGenerationStore files = new RegionGenerationStore(root);
		require(files.publish(state) == 1L, "first checkpoint generation was not published");
		RegionManifest manifest = files.readManifest(region.id());
		require(manifest.totalMarkerCount() == before.particleCount()
				&& Float.floatToRawIntBits((float) manifest.totalVolume()) == Float.floatToRawIntBits(before.totalVolume()),
				"manifest did not retain the exact marker ledger");
		FlipParticleState after = restorer(files.read(region.id())).reconstruct();
		assertParticleStateEquals(before, after);
		FluidRegion startupMetadata = FluidRegion.unloaded(manifest.regionId(), DIMENSION,
				MinecraftRegionCheckpointSource.blockPosition(manifest.origin()), manifest.sizeX(),
				manifest.sizeY(), manifest.sizeZ(), manifest.solverConfig(), manifest.reservedMarkerCapacity(),
				MinecraftRegionCheckpointSource.minecraftFootprint(manifest.footprint()),
				manifest.networkGeneration(), manifest.checkpointGeneration(), manifest.totalMarkerCount(),
				(float) manifest.totalVolume(), MinecraftRegionCheckpointSource.persistedRuntime(manifest.runtime()));
		require(!startupMetadata.solverLoaded() && startupMetadata.lifecycle().state() == FluidRegionLifecycle.State.UNLOADED,
				"startup metadata allocated solver arrays");

		require(region.recordPublishedCheckpoint(1L), "live region rejected published generation");
		FluidRegionManager manager = new FluidRegionManager(new RegionCatalog.Limits(2, 256L, 512L, 512L, 1_024L));
		require(manager.add(region).accepted(), "loaded fixture was not admitted");
		RegionCatalog.BudgetUsage reserved = manager.usage();
		require(region.releaseSolverAfterCheckpoint(1L), "checkpointed solver was not released");
		require(!region.solverLoaded() && region.lifecycle().state() == FluidRegionLifecycle.State.UNLOADED,
				"released region did not become metadata-only UNLOADED");
		require(manager.usage().equals(reserved), "UNLOADED region escaped its hard reservation budget");
		region.restoreSolver(after, 1L);
		require(region.solverLoaded() && region.lifecycle().operatorPaused() && region.collisionRefreshRequired(),
				"restore bypassed operator pause or current-world collision refresh");
		assertParticleStateEquals(before, region.domain().exportParticleState());
		for (long generation = 2L; generation <= 4L; generation++) {
			RegionState repeated = RegionCheckpointFixtures.capture(region, generation);
			files.publish(repeated);
			require(region.recordPublishedCheckpoint(generation), "repeated checkpoint was not recorded");
			require(region.releaseSolverAfterCheckpoint(generation), "repeated unload was rejected");
			FlipParticleState restored = restorer(files.read(region.id())).reconstruct();
			region.restoreSolver(restored, generation);
			assertParticleStateEquals(before, region.domain().exportParticleState());
		}
	}

	static void validateUnknownExtensions() throws Exception {
		FluidRegion region = populatedRegion(new UUID(21L, 22L));
		RegionState original = RegionCheckpointFixtures.capture(region, 1L);
		RegionExtension extension = new RegionExtension("future:test", new byte[] {1, 2, 3, 4});
		RegionManifest base = original.manifest();
		RegionManifest unsigned = new RegionManifest(base.regionId(), base.dimensionId(), base.checkpointGeneration(),
				base.networkGeneration(), base.origin(), base.sizeX(), base.sizeY(), base.sizeZ(), base.solverConfig(),
				base.reservedMarkerCapacity(), base.runtime(), base.footprint(), base.totalMarkerCount(), base.totalVolumeBits(),
				base.sections(), List.of(extension), 0L);
		RegionManifest signed = new RegionManifest(unsigned.regionId(), unsigned.dimensionId(), unsigned.checkpointGeneration(),
				unsigned.networkGeneration(), unsigned.origin(), unsigned.sizeX(), unsigned.sizeY(), unsigned.sizeZ(), unsigned.solverConfig(),
				unsigned.reservedMarkerCapacity(), unsigned.runtime(), unsigned.footprint(), unsigned.totalMarkerCount(), unsigned.totalVolumeBits(),
				unsigned.sections(), unsigned.extensions(), RegionStateIntegrity.manifestChecksum(unsigned));
		RegionStateCodec codec = new RegionStateCodec();
		RegionManifest decoded = codec.decodeManifest(codec.encodeManifest(signed));
		require(decoded.extensions().equals(List.of(extension)), "unknown length-delimited manifest extension was not retained");
		RegionSectionState section = original.sections().getFirst();
		RegionSectionState unsignedSection = new RegionSectionState(section.regionId(), section.dimensionId(), section.checkpointGeneration(),
				section.sectionX(), section.sectionY(), section.sectionZ(), section.localStartX(), section.localStartY(), section.localStartZ(),
				section.sizeX(), section.sizeY(), section.sizeZ(), section.markerOrdinals(), section.positionVolume(), section.velocities(),
				section.solidMask(), List.of(extension), 0L);
		RegionSectionState signedSection = signedSection(unsignedSection);
		require(codec.decodeSection(codec.encodeSection(signedSection)).extensions().equals(List.of(extension)),
				"unknown length-delimited section extension was not retained");
		CompoundTag future = codec.encodeManifest(signed);
		future.putString("Layout", "future");
		expectIOException(() -> codec.decodeManifest(future), "unknown region-state layout was accepted");
	}

	static void validateFaultMatrix() throws Exception {
		for (RegionGenerationStore.FaultPoint point : RegionGenerationStore.FaultPoint.values()) {
			Path root = Files.createTempDirectory(Path.of("."), "fault-" + point.name().toLowerCase() + "-");
			FluidRegion region = populatedRegion(UUID.randomUUID());
			RegionGenerationStore files = new RegionGenerationStore(root);
			RegionState first = RegionCheckpointFixtures.capture(region, 1L);
			files.publish(first);
			FlipParticleState firstParticles = restorer(first).reconstruct();
			FluidRegion nextRegion = populatedRegion(region.id());
			nextRegion.domain().setVolume(1, 1, 1, 0.5F);
			RegionState second = RegionCheckpointFixtures.capture(nextRegion, 2L);
			FlipParticleState secondParticles = restorer(second).reconstruct();
			try {
				files.publish(second, (observed, path) -> { if (observed == point) throw new IOException("injected " + point); });
				throw new AssertionError("Fault point did not interrupt publication: " + point);
			} catch (IOException expected) { }
			RegionState reopened = new RegionGenerationStore(root).read(region.id());
			long expectedGeneration = point == RegionGenerationStore.FaultPoint.AFTER_HEAD_PUBLISH ? 2L : 1L;
			require(reopened.manifest().checkpointGeneration() == expectedGeneration,
					"fault exposed a mixed or unexpected generation at " + point);
			require(reopened.manifest().layout() == RegionStateLayout.PURE, "fault changed the current pure-water layout");
			assertParticleStateEquals(expectedGeneration == 1L ? firstParticles : secondParticles, restorer(reopened).reconstruct());
		}
	}

	static void validateCorruptionAndAtomicMoveFailure() throws Exception {
		Path root = Files.createTempDirectory(Path.of("."), "corrupt-");
		FluidRegion region = populatedRegion(UUID.randomUUID());
		RegionGenerationStore files = new RegionGenerationStore(root);
		RegionState original = RegionCheckpointFixtures.capture(region, 1L);
		RegionSectionState first = original.sections().getFirst();
		RegionSectionState wrongOwner = signedSection(new RegionSectionState(UUID.randomUUID(), first.dimensionId(),
				first.checkpointGeneration(), first.sectionX(), first.sectionY(), first.sectionZ(), first.localStartX(), first.localStartY(),
				first.localStartZ(), first.sizeX(), first.sizeY(), first.sizeZ(), first.markerOrdinals(), first.positionVolume(),
				first.velocities(), first.solidMask(), first.extensions(), 0L));
		List<RegionSectionState> mixedSections = new ArrayList<>(original.sections());
		mixedSections.set(0, wrongOwner);
		expectIOException(() -> new RegionState(original.manifest(), mixedSections).validate(), "mixed section owner was accepted");
		files.publish(original);
		require(region.recordPublishedCheckpoint(1L), "corruption fixture did not advance generation");
		region.domain().setVolume(3, 2, 3, 0.5F);
		RegionState second = RegionCheckpointFixtures.capture(region, 2L);
		RegionGenerationStore unsupported = new RegionGenerationStore(root, (source, target, replace) -> {
			throw new AtomicMoveNotSupportedException(source.toString(), target.toString(), "validation");
		});
		expectIOException(() -> unsupported.publish(second), "unsupported atomic move did not fail closed");
		require(files.read(region.id()).manifest().checkpointGeneration() == 1L,
				"failed slot publication changed the committed generation");

		Path regionDir = root.toAbsolutePath().normalize().resolve("mmm").resolve("region-state")
				.resolve(region.id().toString());
		CompoundTag head = NbtIo.readCompressed(regionDir.resolve("HEAD.nbt"), NbtAccounter.unlimitedHeap());
		Path slot = regionDir.resolve(head.getStringOr("Slot", ""));
		Path section = Files.list(slot).filter(path -> path.getFileName().toString().startsWith("section_")).findFirst().orElseThrow();
		Files.delete(section);
		expectIOException(() -> files.read(region.id()), "missing section was accepted");
		RegionGenerationStore.Discovery missingSection = files.discover();
		require(missingSection.manifests().isEmpty() && missingSection.issues().containsKey(regionDir),
				"discovery admitted a manifest whose committed section was missing");

		Path headRoot = Files.createTempDirectory(Path.of("."), "head-corrupt-");
		FluidRegion headRegion = populatedRegion(UUID.randomUUID());
		RegionGenerationStore headFiles = new RegionGenerationStore(headRoot);
		headFiles.publish(RegionCheckpointFixtures.capture(headRegion, 1L));
		Path headPath = headRoot.resolve("mmm").resolve("region-state").resolve(headRegion.id().toString()).resolve("HEAD.nbt");
		CompoundTag corruptHead = NbtIo.readCompressed(headPath, NbtAccounter.unlimitedHeap());
		corruptHead.putLong("Checksum", corruptHead.getLongOr("Checksum", 0L) + 1L);
		NbtIo.writeCompressed(corruptHead, headPath);
		expectIOException(() -> headFiles.read(headRegion.id()), "corrupt HEAD checksum was accepted");

		Path unknownRoot = Files.createTempDirectory(Path.of("."), "inactive-unknown-");
		FluidRegion unknownRegion = populatedRegion(UUID.randomUUID());
		RegionGenerationStore unknownFiles = new RegionGenerationStore(unknownRoot);
		RegionState unknownFirst = RegionCheckpointFixtures.capture(unknownRegion, 1L);
		unknownFiles.publish(unknownFirst);
		Path unknownInactive = unknownRoot.resolve("mmm").resolve("region-state")
				.resolve(unknownRegion.id().toString()).resolve("slot-b");
		Files.createDirectory(unknownInactive);
		Path foreignEvidence = unknownInactive.resolve("foreign.evidence");
		Files.writeString(foreignEvidence, "retain");
		unknownRegion.domain().setVolume(2, 2, 2, 0.25F);
		RegionState unknownSecond = RegionCheckpointFixtures.capture(unknownRegion, 2L);
		expectIOException(() -> unknownFiles.publish(unknownSecond),
				"unknown inactive-slot evidence was overwritten by a checkpoint");
		require(Files.readString(foreignEvidence).equals("retain")
				&& unknownFiles.read(unknownRegion.id()).manifest().checkpointGeneration() == 1L,
				"unknown inactive-slot evidence was not retained with the old HEAD authoritative");

	}

	static void validateRemovedHeadAndQuarantine() throws Exception {
		Path root = Files.createTempDirectory(Path.of("."), "removed-head-");
		FluidRegion region = populatedRegion(UUID.randomUUID());
		WorldCapabilityProfile profile = WorldCapabilityProfile.of(Set.of(), RegistryFingerprintSet.unbound());
		RegionState active = RegionCheckpointFixtures.capture(region, 1L);
		RegionGenerationStore files = new RegionGenerationStore(root);
		files.publish(active);
		UUID transaction = UUID.randomUUID();
		RegionGenerationStore.PreparedRemoval prepared = files.prepareRemoval(region.id(), 1L,
				active.manifest().checksum(), 2L, transaction, profile.profileFingerprint(),
				profile.aggregateFingerprint(), 10_000L, 64L * 1024L * 1024L);
		require(files.discover().manifests().size() == 1 && files.discover().removed().isEmpty(),
				"ACTIVE restart did not discard exact uncommitted REMOVED staging");
		prepared = files.prepareRemoval(region.id(), 1L, active.manifest().checksum(), 2L, transaction,
				profile.profileFingerprint(), profile.aggregateFingerprint(), 10_000L, 64L * 1024L * 1024L);
		require(files.read(region.id()).manifest().checksum() == active.manifest().checksum(),
				"staged REMOVED payload became authoritative before HEAD");
		RemovedRegionManifest removed = files.commitRemoval(prepared);
		require(removed.baseGeneration() == 1L && removed.targetGeneration() == 2L
				&& removed.oldManifestChecksum() == active.manifest().checksum()
				&& removed.networkTombstoneGeneration() == 2L
				&& removed.quarantineTransactionId().equals(transaction)
				&& removed.capabilityProfileFingerprint().equals(profile.profileFingerprint())
				&& removed.registryAggregateFingerprint().equals(profile.aggregateFingerprint()),
				"REMOVED HEAD lost active/profile/quarantine/network identity");
		RegionGenerationStore.Discovery discovery = files.discover();
		require(discovery.manifests().isEmpty() && discovery.removed().equals(List.of(removed))
				&& discovery.issues().isEmpty(), "REMOVED HEAD was admitted as an ACTIVE solver");
		expectIOException(() -> files.read(region.id()), "REMOVED HEAD reconstructed an empty solver");
		Path expectedQuarantine = root.resolve("mmm").resolve("quarantine").resolve("removed-region")
				.resolve(region.id().toString()).resolve("2");
		Files.createDirectories(expectedQuarantine);
		Path quarantine = files.convergeRemovalQuarantine(region.id());
		require(Files.isDirectory(quarantine.resolve("payload"))
				&& Files.isRegularFile(quarantine.resolve("quarantine.nbt"))
				&& files.convergeRemovalQuarantine(region.id()).equals(quarantine),
				"REMOVED quarantine did not converge idempotently after HEAD");
		expectIOException(() -> files.publish(RegionCheckpointFixtures.capture(region, 2L)),
				"REMOVED region UUID was reused by an ACTIVE publication");

		Path strictRoot = Files.createTempDirectory(Path.of("."), "removed-strict-");
		FluidRegion strictRegion = populatedRegion(UUID.randomUUID());
		RegionState strictActive = RegionCheckpointFixtures.capture(strictRegion, 1L);
		RegionGenerationStore strictFiles = new RegionGenerationStore(strictRoot);
		strictFiles.publish(strictActive);
		RegionGenerationStore.PreparedRemoval strictPrepared = strictFiles.prepareRemoval(strictRegion.id(), 1L,
				strictActive.manifest().checksum(), 2L, UUID.randomUUID(), profile.profileFingerprint(),
				profile.aggregateFingerprint(), 10_000L, 64L * 1024L * 1024L);
		strictFiles.commitRemoval(strictPrepared);
		Path strictRemovedPath = strictRoot.resolve("mmm").resolve("region-state")
				.resolve(strictRegion.id().toString()).resolve(strictPrepared.tombstoneSlot()).resolve("removed.nbt");
		CompoundTag strictRemoved = NbtIo.readCompressed(strictRemovedPath, NbtAccounter.unlimitedHeap());
		strictRemoved.putLong("Unknown", 1L);
		NbtIo.writeCompressed(strictRemoved, strictRemovedPath);
		RegionGenerationStore.Discovery strictDiscovery = strictFiles.discover();
		require(strictDiscovery.removed().isEmpty() && !strictDiscovery.issues().isEmpty(),
				"REMOVED manifest accepted an extra authoritative field");

		Path partialRoot = Files.createTempDirectory(Path.of("."), "removed-partial-");
		FluidRegion partialRegion = populatedRegion(UUID.randomUUID());
		RegionState partialActive = RegionCheckpointFixtures.capture(partialRegion, 1L);
		RegionGenerationStore partialFiles = new RegionGenerationStore(partialRoot);
		partialFiles.publish(partialActive);
		RegionGenerationStore.PreparedRemoval partialPrepared = partialFiles.prepareRemoval(partialRegion.id(), 1L,
				partialActive.manifest().checksum(), 2L, UUID.randomUUID(), profile.profileFingerprint(),
				profile.aggregateFingerprint(), 10_000L, 64L * 1024L * 1024L);
		partialFiles.commitRemoval(partialPrepared);
		Path partialRegionRoot = partialRoot.resolve("mmm").resolve("region-state").resolve(partialRegion.id().toString());
		Path partialDestination = partialRoot.resolve("mmm").resolve("quarantine").resolve("removed-region")
				.resolve(partialRegion.id().toString()).resolve("2");
		Files.createDirectories(partialDestination);
		Files.move(partialRegionRoot.resolve(partialPrepared.activeSlot()), partialDestination.resolve("payload"));
		partialFiles.convergeRemovalQuarantine(partialRegion.id());
		require(Files.isRegularFile(partialDestination.resolve("quarantine.nbt")),
				"REMOVED recovery did not resume after payload move and before quarantine manifest");

		Path thirdRoot = Files.createTempDirectory(Path.of("."), "removed-third-");
		FluidRegion thirdRegion = populatedRegion(UUID.randomUUID());
		RegionState thirdActive = RegionCheckpointFixtures.capture(thirdRegion, 1L);
		RegionGenerationStore thirdFiles = new RegionGenerationStore(thirdRoot);
		thirdFiles.publish(thirdActive);
		RegionGenerationStore.PreparedRemoval thirdPrepared = thirdFiles.prepareRemoval(thirdRegion.id(), 1L,
				thirdActive.manifest().checksum(), 2L, UUID.randomUUID(), profile.profileFingerprint(),
				profile.aggregateFingerprint(), 10_000L, 64L * 1024L * 1024L);
		thirdFiles.commitRemoval(thirdPrepared);
		Path thirdSource = thirdRoot.resolve("mmm").resolve("region-state").resolve(thirdRegion.id().toString())
				.resolve(thirdPrepared.activeSlot());
		Path thirdPayload = thirdRoot.resolve("mmm").resolve("quarantine").resolve("removed-region")
				.resolve(thirdRegion.id().toString()).resolve("2").resolve("payload");
		Files.createDirectories(thirdPayload);
		try (var entries = Files.newDirectoryStream(thirdSource)) {
			for (Path entry : entries) Files.copy(entry, thirdPayload.resolve(entry.getFileName()));
		}
		expectIOException(() -> thirdFiles.convergeRemovalQuarantine(thirdRegion.id()),
				"REMOVED recovery accepted simultaneous source and quarantine payloads");

		Path baseThirdRoot = Files.createTempDirectory(Path.of("."), "removed-base-third-");
		FluidRegion baseThirdRegion = populatedRegion(UUID.randomUUID());
		RegionState baseThirdActive = RegionCheckpointFixtures.capture(baseThirdRegion, 1L);
		RegionGenerationStore baseThirdFiles = new RegionGenerationStore(baseThirdRoot);
		baseThirdFiles.publish(baseThirdActive);
		baseThirdFiles.prepareRemoval(baseThirdRegion.id(), 1L, baseThirdActive.manifest().checksum(),
				2L, UUID.randomUUID(), profile.profileFingerprint(), profile.aggregateFingerprint(),
				10_000L, 64L * 1024L * 1024L);
		Files.createDirectories(baseThirdRoot.resolve("mmm").resolve("quarantine").resolve("removed-region")
				.resolve(baseThirdRegion.id().toString()).resolve("2"));
		RegionGenerationStore.Discovery baseThirdDiscovery = baseThirdFiles.discover();
		require(baseThirdDiscovery.manifests().isEmpty() && !baseThirdDiscovery.issues().isEmpty(),
				"ACTIVE HEAD discarded staged removal after quarantine reached a third state");

		Path unknownRoot = Files.createTempDirectory(Path.of("."), "removed-unknown-");
		FluidRegion unknownRegion = populatedRegion(UUID.randomUUID());
		RegionState unknownActive = RegionCheckpointFixtures.capture(unknownRegion, 1L);
		RegionGenerationStore unknownFiles = new RegionGenerationStore(unknownRoot);
		unknownFiles.publish(unknownActive);
		Path unknownInactive = unknownRoot.resolve("mmm").resolve("region-state")
				.resolve(unknownRegion.id().toString()).resolve("slot-b");
		Files.createDirectory(unknownInactive);
		Path unknownEvidence = unknownInactive.resolve("foreign.evidence");
		Files.writeString(unknownEvidence, "retain");
		expectIOException(() -> unknownFiles.prepareRemoval(unknownRegion.id(), 1L,
				unknownActive.manifest().checksum(), 2L, UUID.randomUUID(), profile.profileFingerprint(),
				profile.aggregateFingerprint(), 10_000L, 64L * 1024L * 1024L),
				"REMOVED preparation overwrote unknown inactive-slot evidence");
		require(Files.readString(unknownEvidence).equals("retain")
				&& unknownFiles.read(unknownRegion.id()).manifest().checkpointGeneration() == 1L,
				"REMOVED preparation did not preserve unknown evidence with the ACTIVE HEAD authoritative");
	}

	static void validateWorldConfig() throws Exception {
		Path root = Files.createTempDirectory(Path.of("."), "config-");
		FluidServerConfig defaults = FluidServerConfig.loadOrCreate(root);
		require(defaults.controllersEnabled() && !defaults.worldBridgeEnabled()
				&& defaults.limits().maximumRegions() == 4
				&& defaults.maximumQuarantineEntries() > 0L && defaults.maximumQuarantineBytes() > 0L,
				"playable default config changed");
		FluidServerConfig enabled = defaults.withControllersEnabled(true).withWorldBridgeEnabled(true);
		enabled.save(root);
		require(FluidServerConfig.loadOrCreate(root).equals(enabled), "world-local config did not round-trip");

		String valid = Files.readString(FluidServerConfig.path(root));
		Files.writeString(FluidServerConfig.path(root), valid + "materialsEnabled=true\n");
		expectIOException(() -> FluidServerConfig.loadOrCreate(root),
				"a removed feature gate key was silently accepted by the admission policy");

		Files.writeString(FluidServerConfig.path(root), valid.replace("schema=controller-policy", "schema=unknown"));
		expectIOException(() -> FluidServerConfig.loadOrCreate(root), "unknown config version was silently replaced");
	}

	/** prepare/commit/abort split: staging must stay unreadable until HEAD publishes, and a moved base must fail closed. */
	static void validateStagedGeneration() throws Exception {
		Path root = Files.createTempDirectory(Path.of("."), "staged-");
		RegionGenerationStore files = new RegionGenerationStore(root);
		UUID id = UUID.randomUUID();

		RegionState first = RegionCheckpointFixtures.capture(populatedRegion(id), 1L);
		RegionGenerationStore.PreparedGeneration preparedFirst = files.prepare(first, (point, path) -> { });
		require(!preparedFirst.alreadyCommitted(), "first staging was marked idempotent");
		expectIOException(() -> files.read(id), "uncommitted staging exposed a readable generation");
		files.commit(preparedFirst, (point, path) -> { });
		require(files.read(id).manifest().checkpointGeneration() == 1L, "first staged commit did not publish");

		RegionState second = RegionCheckpointFixtures.capture(populatedRegion(id), 2L);
		RegionGenerationStore.PreparedGeneration preparedSecond = files.prepare(second, (point, path) -> { });
		require(files.read(id).manifest().checkpointGeneration() == 1L, "staging exposed the uncommitted generation");
		files.abort(preparedSecond);
		require(files.read(id).manifest().checkpointGeneration() == 1L, "abort changed the committed generation");
		files.publish(second);
		require(files.read(id).manifest().checkpointGeneration() == 2L, "publish after abort did not advance");

		RegionGenerationStore.PreparedGeneration stale = files.prepare(RegionCheckpointFixtures.capture(populatedRegion(id), 3L), (point, path) -> { });
		files.publish(RegionCheckpointFixtures.capture(populatedRegion(id), 4L));
		expectIOException(() -> files.commit(stale, (point, path) -> { }), "commit on a moved base was accepted");
		require(files.read(id).manifest().checkpointGeneration() == 4L, "moved-base failure changed the committed generation");

		RegionGenerationStore.PreparedGeneration repeat = files.prepare(RegionCheckpointFixtures.capture(populatedRegion(id), 4L), (point, path) -> { });
		require(repeat.alreadyCommitted() && files.commit(repeat, (point, path) -> { }) == 4L,
				"idempotent re-staging was not recognized");
	}

	static void validateExplicitPreviousGenerationActivation() throws Exception {
		Path root = Files.createTempDirectory(Path.of("."), "previous-generation-");
		RegionGenerationStore files = new RegionGenerationStore(root);
		UUID id = UUID.randomUUID();
		FluidRegion region = populatedRegion(id);
		RegionState first = RegionCheckpointFixtures.capture(region, 1L);
		files.publish(first);
		region.domain().setVolume(3, 2, 3, 0.5F);
		RegionState second = RegionCheckpointFixtures.capture(region, 2L);
		files.publish(second);

		RegionGenerationStore.PreviousGeneration previous = files.readPrevious(id, 2L);
		require(previous.state().manifest().checkpointGeneration() == 1L
				&& previous.state().manifest().checksum() == first.manifest().checksum(),
				"explicit previous-generation probe did not bind the inactive complete slot");
		expectIOException(() -> files.readPrevious(id, 3L),
				"previous-generation probe ignored its explicit current-generation guard");
		Path headTemporary = root.resolve("mmm").resolve("region-state").resolve(id.toString()).resolve("HEAD.nbt.tmp");
		Files.writeString(headTemporary, "foreign evidence");
		expectIOException(() -> files.activatePrevious(previous),
				"previous-generation activation overwrote unknown HEAD staging");
		require(Files.readString(headTemporary).equals("foreign evidence")
				&& files.read(id).manifest().checkpointGeneration() == 2L,
				"previous-generation activation did not preserve unknown staging with the current HEAD authoritative");
		Files.delete(headTemporary);
		require(files.activatePrevious(previous) == 1L
				&& files.read(id).manifest().checkpointGeneration() == 1L
				&& files.read(id).manifest().checksum() == first.manifest().checksum(),
				"explicit previous-generation activation did not atomically move HEAD");

		files.publish(second);
		require(files.read(id).manifest().checkpointGeneration() == 2L
				&& files.read(id).manifest().checksum() == second.manifest().checksum(),
				"publication after explicit rollback did not replace the retained rejected slot");
	}

}
