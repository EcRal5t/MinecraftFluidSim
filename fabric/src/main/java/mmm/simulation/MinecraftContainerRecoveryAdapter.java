package mmm.simulation;

import mmm.MmmMod;
import mmm.api.event.ContainerTransferCommittedEvent;
import mmm.api.transfer.TransferQuantity;
import mmm.application.ContainerRecoveryCoordinator;
import mmm.chemistry.ChemicalRegistry;
import mmm.granular.GranularRegistry;
import mmm.recovery.CrossDomainRecovery;
import mmm.thermal.ThermalRegistry;
import mmm.transfer.ContainerTransferActor;
import mmm.transfer.ContainerTransferJournal;
import mmm.worldprofile.WorldCapability;
import mmm.worldprofile.WorldCapabilityProfile;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.util.OptionalDouble;
import java.util.UUID;

/** Minecraft actor, registry, region-HEAD, and event ports for loader-neutral container recovery. */
final class MinecraftContainerRecoveryAdapter implements ContainerRecoveryCoordinator.RecoveryPort {
	private final MinecraftServer server;
	private final WorldStartupCoordinator.Result startup;
	private final WorldCapabilityProfile profile;
	private final ChemicalRegistry chemicalRegistry;
	private final GranularRegistry granularRegistry;
	private final ThermalRegistry thermalRegistry;
	private final FluidRegionManager regions;
	private final FluidRegionOperations regionOperations;
	private final CommittedEventHub committedEvents;
	private final MinecraftContainerActorAdapter actors = new MinecraftContainerActorAdapter();

	MinecraftContainerRecoveryAdapter(
			MinecraftServer server,
			WorldStartupCoordinator.Result startup,
			RegistryBundleLoader.RegistryBundle registries,
			FluidRegionManager regions,
			FluidRegionOperations regionOperations,
			CommittedEventHub committedEvents
	) {
		this.server = java.util.Objects.requireNonNull(server, "server");
		this.startup = java.util.Objects.requireNonNull(startup, "startup");
		this.profile = startup.profile();
		this.chemicalRegistry = registries == null ? null : registries.chemical();
		this.granularRegistry = registries == null ? null : registries.granular();
		this.thermalRegistry = registries == null ? null : registries.thermal();
		this.regions = java.util.Objects.requireNonNull(regions, "regions");
		this.regionOperations = java.util.Objects.requireNonNull(regionOperations, "regionOperations");
		this.committedEvents = java.util.Objects.requireNonNull(committedEvents, "committedEvents");
	}

	@Override
	public void validate(ContainerTransferJournal journal) {
		journal.validateProfileFingerprint(startup.profileProven() ? profile.profileFingerprint() : null);
		if (journal.requiresThermalRegistry()) {
			if (!profile.hasCapability(WorldCapability.THERMAL) || thermalRegistry == null) {
				throw new IllegalArgumentException(
						"thermal recovery requires the enabled authoritative world registry");
			}
			journal.validateThermalRegistry(thermalRegistry.fingerprint());
		}
		if (journal.requiresGranularRegistry()) {
			if (!profile.hasCapability(WorldCapability.GRANULAR) || granularRegistry == null) {
				throw new IllegalArgumentException(
						"granular recovery requires the enabled authoritative world registry");
			}
			journal.validateGranularRegistry(granularRegistry,
					profile.hasCapability(WorldCapability.CHEMISTRY) ? chemicalRegistry : null);
		}
		if (journal.requiresChemicalRegistry()) {
			if (!profile.hasCapability(WorldCapability.CHEMISTRY) || chemicalRegistry == null) {
				throw new IllegalArgumentException(
						"chemical recovery requires the enabled authoritative world registry");
			}
			journal.validateChemicalRegistry(chemicalRegistry);
		}
	}

	@Override
	public long committedGeneration(UUID regionId) throws IOException {
		return regionOperations.committedGeneration(regionId);
	}

	@Override
	public long committedManifestChecksum(UUID regionId) throws IOException {
		return regionOperations.committedManifestChecksum(regionId);
	}

	@Override
	public CrossDomainRecovery.ExternalTruth classifyActor(ContainerTransferJournal journal) {
		return actors.classify(server, journal);
	}

	@Override
	public ContainerRecoveryCoordinator.Convergence applyAfterItem(ContainerTransferJournal journal) {
		return actors.applyAfterItem(server, journal);
	}

	@Override
	public void persistAfterItem(ContainerTransferActor actor) throws IOException {
		actors.persistAfterItem(server, actor);
	}

	@Override
	public CrossDomainRecovery.ExternalTruth verifyAfterItem(ContainerTransferJournal journal) {
		return actors.verifyAfterItem(server, journal);
	}

	@Override
	public void adoptRecoveredCheckpoint(ContainerTransferJournal journal) throws IOException {
		for (FluidRegion region : regions.regions()) {
			if (!region.id().equals(journal.regionId())) continue;
			regionOperations.adoptCommittedContainerCheckpoint(
					region, journal.baseGeneration(), journal.targetGeneration());
			return;
		}
	}

	@Override
	public void publishCommitted(ContainerTransferJournal journal) {
		OptionalDouble energy = journal.thermalTransfer() == null
				? OptionalDouble.empty()
				: OptionalDouble.of(journal.thermalTransfer().internalEnergy());
		TransferQuantity quantity = switch (journal.resourceKind()) {
			case FLUID_NANOS -> new TransferQuantity.Liquid(journal.volumeNanos(), energy);
			case GRANULAR_SVQ -> new TransferQuantity.Granular(journal.solidVolumeSvq());
		};
		committedEvents.publish(new ContainerTransferCommittedEvent(
				journal.transactionId(), journal.dimensionId(), journal.regionId(), journal.targetGeneration(),
				journal.operation() == ContainerTransferJournal.Operation.INSERT
						? ContainerTransferCommittedEvent.Operation.INSERT
						: ContainerTransferCommittedEvent.Operation.EXTRACT,
				quantity));
	}

	@Override
	public void report(ContainerRecoveryCoordinator.Report level, String message, Throwable failure) {
		if (level == ContainerRecoveryCoordinator.Report.WARNING) {
			if (failure == null) MmmMod.LOGGER.warn(message);
			else MmmMod.LOGGER.warn(message, failure);
		} else {
			if (failure == null) MmmMod.LOGGER.error(message);
			else MmmMod.LOGGER.error(message, failure);
		}
	}
}
