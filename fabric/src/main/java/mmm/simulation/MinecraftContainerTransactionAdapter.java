package mmm.simulation;

import mmm.MmmMod;
import mmm.application.ContainerRecoveryCoordinator;
import mmm.application.ContainerTransferCoordinator;
import mmm.application.ContainerTransferUseCase;
import mmm.item.ContainerItemMapper;
import mmm.item.ContainerItemStacks;
import mmm.item.MmmComponents;
import mmm.fluid.FluidDomain;
import mmm.transfer.ContainerTransferActor;
import mmm.transfer.ContainerTransferJournal;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/** Maps one resolved Minecraft item transfer onto the loader-neutral durable transaction use case. */
final class MinecraftContainerTransactionAdapter {
	private final FluidRegionManager regions;
	private final FluidRegionOperations regionOperations;
	private final ContainerRecoveryCoordinator recovery;
	private final ContainerTransferCoordinator.FaultControl faults =
			new ContainerTransferCoordinator.FaultControl();
	private final String profileFingerprint;
	private final String thermalRegistryFingerprint;

	MinecraftContainerTransactionAdapter(
			FluidRegionManager regions,
			FluidRegionOperations regionOperations,
			ContainerRecoveryCoordinator recovery,
			String profileFingerprint,
			String thermalRegistryFingerprint
	) {
		this.regions = Objects.requireNonNull(regions, "regions");
		this.regionOperations = Objects.requireNonNull(regionOperations, "regionOperations");
		this.recovery = Objects.requireNonNull(recovery, "recovery");
		this.profileFingerprint = Objects.requireNonNull(profileFingerprint, "profileFingerprint");
		this.thermalRegistryFingerprint = thermalRegistryFingerprint;
	}

	Result execute(
			FluidRegion region,
			ServerLevel level,
			ContainerTransferActor actor,
			ItemStack beforeStack,
			ContainerTransferJournal.Operation operation,
			long requestedNanos,
			long baseVolumeNanos,
			TransferExecutor executor,
			FinalItemResolver finalItemResolver
	) {
		return execute(region, level, actor, beforeStack, beforeStack, operation,
				ContainerTransferJournal.ResourceKind.FLUID_NANOS, requestedNanos, baseVolumeNanos,
				executor, finalItemResolver);
	}

	Result execute(
			FluidRegion region,
			ServerLevel level,
			ContainerTransferActor actor,
			ItemStack beforeStack,
			ContainerTransferJournal.Operation operation,
			ContainerTransferJournal.ResourceKind resourceKind,
			long requestedAmount,
			long baseAmount,
			TransferExecutor executor,
			FinalItemResolver finalItemResolver
	) {
		return execute(region, level, actor, beforeStack, beforeStack, operation, resourceKind,
				requestedAmount, baseAmount, executor, finalItemResolver);
	}

	/** Creative descriptor is the actor truth while a server-resolved actual stack supplies source truth. */
	Result executeCreativeSource(
			FluidRegion region,
			ServerLevel level,
			ContainerTransferActor actor,
			ItemStack descriptorStack,
			ItemStack resolvedSourceStack,
			ContainerTransferJournal.ResourceKind resourceKind,
			long requestedAmount,
			TransferExecutor executor,
			FinalItemResolver finalItemResolver
	) {
		return execute(region, level, actor, descriptorStack, resolvedSourceStack,
				ContainerTransferJournal.Operation.INSERT, resourceKind, requestedAmount, 0L,
				executor, finalItemResolver);
	}

	private Result execute(
			FluidRegion region,
			ServerLevel level,
			ContainerTransferActor actor,
			ItemStack beforeStack,
			ItemStack sourceStack,
			ContainerTransferJournal.Operation operation,
			ContainerTransferJournal.ResourceKind resourceKind,
			long requestedAmount,
			long baseAmount,
			TransferExecutor executor,
			FinalItemResolver finalItemResolver
	) {
		Objects.requireNonNull(region, "region");
		Objects.requireNonNull(level, "level");
		Objects.requireNonNull(executor, "executor");
		Objects.requireNonNull(finalItemResolver, "finalItemResolver");
		ContainerTransferJournal.ItemFingerprint before;
		ContainerTransferJournal.ItemFingerprint source;
		try {
			before = ContainerItemMapper.fingerprint(beforeStack);
			source = ContainerItemMapper.fingerprint(sourceStack);
		} catch (IllegalArgumentException invalidItem) {
			return failed(operation, beforeStack, invalidItem.getMessage(), null);
		}

		ContainerTransferCoordinator.RegionPort regionPort = new RegionPort(
				region, beforeStack, executor, finalItemResolver);
		ContainerTransferCoordinator coordinator = new ContainerTransferCoordinator(recovery, regionPort, faults);
		ContainerTransferUseCase useCase = new ContainerTransferUseCase(coordinator);
		ContainerTransferUseCase.Result result = useCase.execute(new ContainerTransferUseCase.Command(
				region.id(), level.dimension().identifier().toString(), region.checkpointGeneration(),
				region.domain().hasThermalState(), profileFingerprint, thermalRegistryFingerprint,
				actor, before, source, operation, resourceKind, requestedAmount, baseAmount));
		ItemStack after;
		try {
			after = ContainerItemMapper.toStack(result.afterItem());
		} catch (RuntimeException invalidResult) {
			return failed(operation, beforeStack,
					"容器事务返回无法重建的 after item：" + invalidResult.getMessage(), result.transactionId());
		}
		return new Result(new MinecraftContainerTransferOperations.TransferOutcome(
				result.committed(), result.filled(), after, result.diagnostic(), result.actualAmount(),
				result.thermalEnergy()), result.transactionId(), result.committedGeneration());
	}

	void setFault(ContainerTransferCoordinator.FaultPoint point) { faults.set(point); }

	ContainerTransferCoordinator.FaultPoint fault() { return faults.get(); }

	void completeCommitted(UUID transactionId) { recovery.retryTransaction(transactionId); }

	private ItemStack bindActualProfile(ItemStack stack) {
		if (stack.get(MmmComponents.FLUID_VOLUME) != null
				|| stack.get(MmmComponents.CHEMICAL_CONTENTS) != null
				|| stack.get(MmmComponents.GRANULAR_CONTENTS) != null) {
			ContainerItemStacks.bindProfile(stack, profileFingerprint);
		}
		return stack;
	}

	private static Result failed(
			ContainerTransferJournal.Operation operation, ItemStack before, String diagnostic, UUID transactionId
	) {
		return new Result(new MinecraftContainerTransferOperations.TransferOutcome(
				false, operation == ContainerTransferJournal.Operation.INSERT, before, diagnostic),
				transactionId, -1L);
	}

	record Result(
			MinecraftContainerTransferOperations.TransferOutcome outcome,
			UUID transactionId,
			long committedGeneration
	) { }

	@FunctionalInterface
	interface FinalItemResolver {
		ItemStack resolve(ItemStack rawAfter, ItemStack before);
	}

	@FunctionalInterface
	interface TransferExecutor {
		MinecraftContainerTransferOperations.TransferOutcome execute(
				FluidRegion candidate, long requestedAmount, long baseAmount);
	}

	private final class RegionPort implements ContainerTransferCoordinator.RegionPort {
		private final FluidRegion region;
		private final ItemStack beforeStack;
		private final TransferExecutor executor;
		private final FinalItemResolver finalItemResolver;
		private FluidDomain candidateDomain;
		private boolean candidateOperatorPaused;

		private RegionPort(
				FluidRegion region,
				ItemStack beforeStack,
				TransferExecutor executor,
				FinalItemResolver finalItemResolver
		) {
			this.region = region;
			this.beforeStack = beforeStack;
			this.executor = executor;
			this.finalItemResolver = finalItemResolver;
		}

		@Override
		public ContainerTransferCoordinator.Execution execute(ContainerTransferUseCase.Command command) {
			if (regions.region(command.regionId()) != region || !region.solverLoaded()
					|| region.checkpointGeneration() != command.baseGeneration()) {
				return new ContainerTransferCoordinator.Execution(false, null,
						"容器转移目标在 WAL 准备前后发生变化", 0L, null);
			}
			candidateDomain = FluidDomain.restoreAuthoritativeState(
					region.domain().captureAuthoritativeState());
			candidateOperatorPaused = region.paused();
			FluidRegion candidate = region.lifecycleCandidate(candidateDomain, candidateOperatorPaused);
			MinecraftContainerTransferOperations.TransferOutcome raw =
					executor.execute(candidate, command.requestedAmount(), command.baseAmount());
			if (raw == null || !raw.transferred()) {
				candidateDomain = null;
				return new ContainerTransferCoordinator.Execution(false, null,
						raw == null ? "转移未执行" : raw.message(), 0L, null);
			}
			ItemStack resolved = bindActualProfile(finalItemResolver.resolve(raw.stack(), beforeStack));
			return new ContainerTransferCoordinator.Execution(true, ContainerItemMapper.fingerprint(resolved),
					raw.message(), raw.resourceAmount(), raw.thermalEnergy());
		}

		@Override
		public ContainerTransferCoordinator.StagedCheckpoint stage(
				UUID transactionId, UUID regionId, long targetGeneration
		) throws IOException {
			if (!region.id().equals(regionId)) throw new IOException("Container stage region identity changed");
			if (candidateDomain == null) throw new IOException("Container stage has no complete private candidate");
			return new Staged(regionOperations.stageContainerCheckpoint(
					region, candidateDomain, targetGeneration, candidateOperatorPaused));
		}

		@Override
		public void abort(ContainerTransferCoordinator.StagedCheckpoint staged) throws IOException {
			regionOperations.abortStagedCheckpoint(((Staged) staged).checkpoint);
		}

		@Override
		public long commit(ContainerTransferCoordinator.StagedCheckpoint staged) throws IOException {
			if (candidateDomain == null) throw new IOException("Container commit has no complete private candidate");
			Staged concrete = (Staged) staged;
			long targetGeneration = concrete.checkpoint.prepared().generation();
			long published;
			try {
				published = regionOperations.commitStagedLifecycleCheckpoint(concrete.checkpoint);
			} catch (IOException | RuntimeException failure) {
				try {
					long durableGeneration = regionOperations.committedGeneration(region.id());
					long durableChecksum = regionOperations.committedManifestChecksum(region.id());
					if (durableGeneration != targetGeneration
							|| durableChecksum != concrete.checkpoint.manifestChecksum()) throw failure;
					published = durableGeneration;
					MmmMod.LOGGER.warn(
							"Container HEAD {} for region {} committed despite a post-publish exception",
							published, region.id(), failure);
				} catch (IOException | RuntimeException inspectionFailure) {
					if (inspectionFailure != failure) failure.addSuppressed(inspectionFailure);
					throw failure;
				}
			}
			region.adoptContainerCandidateDomain(candidateDomain, published, candidateOperatorPaused);
			candidateDomain = null;
			return published;
		}

		@Override
		public void lock(UUID regionId, String diagnostic) {
			if (region.id().equals(regionId)) {
				region.setAvailabilityFailure(mmm.api.RegionAvailabilityReason.PENDING_WAL, diagnostic);
			}
		}
	}

	private record Staged(RegionRepositoryAdapter.StagedCheckpoint checkpoint)
			implements ContainerTransferCoordinator.StagedCheckpoint {
		@Override
		public long manifestChecksum() { return checkpoint.manifestChecksum(); }
	}
}
