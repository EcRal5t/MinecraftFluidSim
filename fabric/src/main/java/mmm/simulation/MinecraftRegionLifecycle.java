package mmm.simulation;

import mmm.MmmMod;
import mmm.api.RegionAvailabilityReason;
import mmm.application.ControllerQueryUseCase;
import mmm.application.RegionLifecycleUseCase;
import mmm.controller.LifecycleOperation;
import mmm.controller.LifecycleOperationPreview;
import mmm.controller.LifecycleOperationSessions;
import mmm.fluid.FluidDomain;
import mmm.fluid.air.AirNetwork;
import mmm.simulation.persistence.GenerationSequence;
import mmm.thermal.ThermalRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/** Owns Minecraft facts, authorization and durable convergence for controller lifecycle operations. */
public final class MinecraftRegionLifecycle {
	private final RegionLifecycleUseCase useCase = new RegionLifecycleUseCase();
	private final WorldStartupCoordinator.Result startup;
	private final FluidRegionManager regions;
	private final FluidRegionOperations operations;
	private final MinecraftRegionAvailability availability;
	private final MinecraftPendingPhaseWorkBarrier phaseWorkBarrier;
	private final FluidSnapshotPublisher snapshots;
	private final FluidWorldTargetResolver targets;
	private final ControllerQueryUseCase controllerQueries;
	private final ThermalRegistry thermalRegistry;
	private final Predicate<UUID> pendingContainerTransfer;

	MinecraftRegionLifecycle(
			WorldStartupCoordinator.Result startup,
			FluidRegionManager regions,
			FluidRegionOperations operations,
			MinecraftRegionAvailability availability,
			MinecraftPendingPhaseWorkBarrier phaseWorkBarrier,
			FluidSnapshotPublisher snapshots,
			FluidWorldTargetResolver targets,
			ControllerQueryUseCase controllerQueries,
			ThermalRegistry thermalRegistry,
			Predicate<UUID> pendingContainerTransfer
	) {
		this.startup = Objects.requireNonNull(startup, "World startup result");
		this.regions = Objects.requireNonNull(regions, "Region manager");
		this.operations = Objects.requireNonNull(operations, "Region operations");
		this.availability = Objects.requireNonNull(availability, "Region availability");
		this.phaseWorkBarrier = Objects.requireNonNull(phaseWorkBarrier, "Pending phase-work barrier");
		this.snapshots = Objects.requireNonNull(snapshots, "Snapshot publisher");
		this.targets = Objects.requireNonNull(targets, "World target resolver");
		this.controllerQueries = Objects.requireNonNull(controllerQueries, "Controller query use case");
		this.thermalRegistry = thermalRegistry;
		this.pendingContainerTransfer = Objects.requireNonNull(
				pendingContainerTransfer, "Pending container-transfer query");
	}

	Component changeRunIntent(ServerPlayer player, FluidRegion region, boolean pause) {
		RegionLifecycleUseCase.RunIntentDecision decision = useCase.decideRunIntent(
				new RegionLifecycleUseCase.RunIntentFacts(pause, player.isCreative(), startup.authoritativeWritesReady(),
						region.paused()));
		if (decision != RegionLifecycleUseCase.RunIntentDecision.ACCEPTED) return switch (decision) {
			case PERMISSION_DENIED -> Component.translatable("message.mmm.controller.creative_required");
			case WRITE_NOT_READY -> Component.translatable("message.mmm.controller.write_not_ready",
					authoritativeWriteFailure("修改控制器运行意图"));
			case UNCHANGED -> Component.translatable("message.mmm.controller.intent_changed");
			case ACCEPTED -> throw new IllegalStateException("Accepted run intent entered failure mapping");
		};
		region.setPaused(pause);
		if (!pause && region.solverLoaded()) region.wakeForActivity();
		if (region.solverLoaded()) snapshots.sendToLevel(player.level(), region);
		return null;
	}

	/** Issues a bounded destructive-operation preview after consuming the displayed console generation. */
	PreviewResult preview(
			ServerPlayer player, UUID requestedRegionId, BlockPos controllerPosition,
			long summaryGeneration, LifecycleOperation operation
	) {
		ResolvedController resolved = resolveInteractiveController(player, requestedRegionId, controllerPosition);
		if (resolved.failure() != null) return PreviewResult.failed(resolved.failure());
		FluidRegion region = resolved.region();
		if (!controllerQueries.consumeActionToken(player.getUUID(), region.id(), summaryGeneration)) {
			return PreviewResult.failed(Component.translatable("message.mmm.controller.summary_expired"));
		}
		prepareInventoryDecrease(player.level(), region);
		LifecycleOperationPreview preview = useCase.preview(lifecycleFacts(player, region, operation));
		return new PreviewResult(preview, null);
	}

	/** Consumes one same-player preview and rechecks all commit gates before changing authoritative state. */
	CommitResult confirm(
			ServerPlayer player, UUID requestedRegionId, BlockPos controllerPosition,
			UUID nonce, LifecycleOperation operation
	) {
		ResolvedController resolved = resolveInteractiveController(player, requestedRegionId, controllerPosition);
		if (resolved.failure() != null) return CommitResult.failedDetail(resolved.failure());
		FluidRegion region = resolved.region();
		prepareInventoryDecrease(player.level(), region);
		RegionLifecycleUseCase.CommitAuthorization authorization = useCase.authorizeCommit(
				nonce, lifecycleFacts(player, region, operation));
		if (authorization.decision() == RegionLifecycleUseCase.CommitDecision.TOKEN_INVALID) {
			return CommitResult.failed("message.mmm.lifecycle.preview_expired");
		}
		if (authorization.decision() == RegionLifecycleUseCase.CommitDecision.BLOCKED) {
			return CommitResult.failed("message.mmm.lifecycle.blocked_confirm",
					lifecycleBlockers(authorization.blockers()));
		}
		return switch (operation) {
			case EMPTY_CONDENSED, VENT_SEALED_GAS -> commitInventory(player.level(), region, operation);
			case RELEASE_OWNERSHIP -> commitOwnershipRelease(player.level().getServer(), region);
			case REMOVE_EMPTY_REGION -> commitRemoval(player.level().getServer(), region);
		};
	}

	void forgetViewer(UUID playerId) { useCase.forgetViewer(playerId); }

	private void prepareInventoryDecrease(ServerLevel level, FluidRegion region) {
		if (availability.ensureForInventoryDecrease(level, region) && region.solverLoaded()
				&& region.domain().hasPendingPhaseVolumeWork()
				&& phaseWorkBarrier.settle(level, region)) {
			try {
				snapshots.sendToLevel(level, region);
			} catch (RuntimeException failure) {
				MmmMod.LOGGER.warn("Settled lifecycle phase work for region {} but its snapshot failed",
						region.id(), failure);
			}
		}
	}

	private CommitResult commitInventory(ServerLevel level, FluidRegion region, LifecycleOperation operation) {
		FluidDomain candidate;
		try {
			candidate = FluidDomain.restoreAuthoritativeState(region.domain().captureAuthoritativeState());
			if (operation == LifecycleOperation.EMPTY_CONDENSED) {
				candidate.commitOperatorClear(candidate.planOperatorClear());
			} else {
				candidate.commitOperatorVentSealedGas(candidate.planOperatorVentSealedGas());
			}
		} catch (RuntimeException failure) {
			return CommitResult.failed("message.mmm.lifecycle.candidate_failed", failure.getMessage());
		}

		RegionRepositoryAdapter.StagedCheckpoint staged = null;
		long published;
		try {
			long targetGeneration = GenerationSequence.next(region.checkpointGeneration(), "Lifecycle checkpoint");
			staged = operations.stageLifecycleCheckpoint(region, candidate, targetGeneration);
			published = operations.commitStagedLifecycleCheckpoint(staged);
		} catch (IOException | RuntimeException failure) {
			if (staged == null) return CommitResult.failed(
					"message.mmm.lifecycle.commit_failed", failure.getMessage());
			try {
				long durableGeneration = operations.committedGeneration(region.id());
				long durableChecksum = operations.committedManifestChecksum(region.id());
				if (durableGeneration == staged.prepared().generation()
						&& durableChecksum == staged.manifestChecksum()) {
					published = durableGeneration;
					MmmMod.LOGGER.warn("Lifecycle HEAD {} for region {} committed despite a post-publish exception",
							published, region.id(), failure);
				} else if (staged.prepared().baseGeneration() != null
						&& durableGeneration == staged.prepared().baseGeneration()
						&& durableChecksum == staged.prepared().baseManifestChecksum()) {
					operations.abortStagedCheckpoint(staged);
					return CommitResult.failed("message.mmm.lifecycle.commit_failed", failure.getMessage());
				} else {
					region.setPaused(true);
					region.setAvailabilityFailure(RegionAvailabilityReason.HEAD_INVALID,
							"lifecycle commit 后 durable HEAD 无法分类：" + failure.getMessage());
					return CommitResult.failed("message.mmm.lifecycle.commit_unknown", failure.getMessage());
				}
			} catch (IOException | RuntimeException inspectionFailure) {
				failure.addSuppressed(inspectionFailure);
				region.setPaused(true);
				region.setAvailabilityFailure(RegionAvailabilityReason.HEAD_INVALID,
						"lifecycle commit 后无法回读 durable HEAD：" + failure.getMessage());
				return CommitResult.failed("message.mmm.lifecycle.commit_unknown", failure.getMessage());
			}
		}

		try {
			region.adoptLifecycleCandidateDomain(candidate, published, true);
		} catch (RuntimeException failure) {
			region.setPaused(true);
			region.setAvailabilityFailure(RegionAvailabilityReason.HEAD_INVALID,
					"lifecycle HEAD 已提交但 live candidate 未收敛；必须从 HEAD 恢复：" + failure.getMessage());
			return CommitResult.committed("message.mmm.lifecycle.commit_recovery_pending",
					published, failure.getMessage());
		}
		try {
			snapshots.sendToLevel(level, region);
		} catch (RuntimeException failure) {
			MmmMod.LOGGER.error("Lifecycle checkpoint {} committed for region {} but its client snapshot failed",
					published, region.id(), failure);
			return CommitResult.committed("message.mmm.lifecycle.snapshot_pending", published);
		}
		return CommitResult.committed(operation == LifecycleOperation.EMPTY_CONDENSED
				? "message.mmm.lifecycle.empty_committed" : "message.mmm.lifecycle.vent_committed", published);
	}

	private CommitResult commitOwnershipRelease(MinecraftServer server, FluidRegion region) {
		region.setPaused(true);
		OwnershipTransactionCoordinator.ReleaseAttempt release = operations.releaseOwnership(server, region);
		if (release.failure() != null) return CommitResult.failedDetail(release.failure());
		return CommitResult.committed("message.mmm.lifecycle.release_committed",
				release.restoredBlocks(), release.skippedBlocks());
	}

	private CommitResult commitRemoval(MinecraftServer server, FluidRegion region) {
		long maximumQuarantineEntries = startup.config().maximumQuarantineEntries();
		long maximumQuarantineBytes = startup.config().maximumQuarantineBytes();
		long tombstoneGeneration;
		try {
			tombstoneGeneration = GenerationSequence.next(region.networkGeneration(), "Network tombstone");
		} catch (IOException invalid) {
			return CommitResult.failedDetail(invalid.getMessage());
		}
		mmm.simulation.persistence.RegionGenerationStore.PreparedRemoval prepared = null;
		boolean committed = false;
		try {
			prepared = operations.prepareRemoval(region, tombstoneGeneration, UUID.randomUUID(),
					maximumQuarantineEntries, maximumQuarantineBytes);
			var removed = operations.commitRemoval(prepared);
			committed = true;
			ArrayList<String> pending = new ArrayList<>();
			try {
				snapshots.sendRemoval(server, region, removed.networkTombstoneGeneration());
			} catch (RuntimeException networkFailure) {
				pending.add("network tombstone: " + networkFailure.getMessage());
			}
			FluidRegion runtimeOwner = regions.remove(region.id());
			if (runtimeOwner != region) pending.add("runtime owner identity changed");
			try {
				operations.convergeRemovalQuarantine(region.id());
			} catch (IOException quarantineFailure) {
				pending.add("quarantine: " + quarantineFailure.getMessage());
			}
			if (pending.isEmpty()) return CommitResult.committed(
					"message.mmm.lifecycle.remove_committed", removed.targetGeneration());
			String detail = String.join("; ", pending);
			operations.recordIssue("REMOVED " + region.id() + " 收敛待恢复：" + detail);
			return CommitResult.committed("message.mmm.lifecycle.remove_convergence_pending", detail);
		} catch (IOException | RuntimeException failure) {
			if (!committed && prepared != null) {
				try {
					committed = operations.readRemoved(region.id()).equals(prepared.manifest());
				} catch (IOException | RuntimeException notRemoved) {
					failure.addSuppressed(notRemoved);
				}
			}
			if (!committed && prepared != null) {
				try { operations.abortRemoval(prepared); }
				catch (IOException abortFailure) { failure.addSuppressed(abortFailure); }
			}
			if (committed) {
				operations.recordIssue("REMOVED " + region.id() + " 收敛待恢复：" + failure.getMessage());
				try {
					snapshots.sendRemoval(server, region, prepared.manifest().networkTombstoneGeneration());
				} catch (RuntimeException networkFailure) {
					failure.addSuppressed(networkFailure);
				}
				regions.remove(region.id());
				try {
					operations.convergeRemovalQuarantine(region.id());
				} catch (IOException quarantineFailure) {
					failure.addSuppressed(quarantineFailure);
				}
				return CommitResult.committed("message.mmm.lifecycle.remove_convergence_pending",
						failure.getMessage());
			}
			return CommitResult.failed("message.mmm.lifecycle.remove_failed", failure.getMessage());
		}
	}

	private String authoritativeWriteFailure(String operation) {
		if (!startup.authoritativeWritesReady() && startup.state() != WorldStartupCoordinator.State.READY) {
			return operation + "失败：world capability startup=" + startup.state()
					+ "；不可证明的 profile 不会创建或改写区域";
		}
		return operation + "失败：server.properties 未通过校验；原文件已保留，请修复后重启服务器";
	}

	private RegionLifecycleUseCase.PreviewFacts lifecycleFacts(
			ServerPlayer player, FluidRegion region, LifecycleOperation operation
	) {
		long serverTick = Integer.toUnsignedLong(player.level().getServer().getTickCount());
		LifecycleOperationPreview.PermissionDecision permission = player.createCommandSourceStack().permissions()
				.hasPermission(Permissions.COMMANDS_ADMIN)
				? LifecycleOperationPreview.PermissionDecision.BUILTIN_ADMIN_GRANTED
				: LifecycleOperationPreview.PermissionDecision.BUILTIN_ADMIN_DENIED;
		boolean pendingWal = pendingContainerTransfer.test(region.id());
		String deviceIdentity = thermalRegistry == null || !region.solverLoaded() ? "none"
				: region.finiteThermalDevices(thermalRegistry.fingerprint()).toString();
		String availabilityIdentity = (startup.authoritativeWritesReady() ? "STARTUP_READY" : "STARTUP_LOCKED")
				+ "|" + region.availabilityDescription() + "|loaded=" + region.solverLoaded()
				+ "|solver=" + region.solverGeneration() + "|devices=" + deviceIdentity;
		LifecycleOperationSessions.Context context = new LifecycleOperationSessions.Context(player.getUUID(), region.id(),
				region.checkpointGeneration(), operation, permission, pendingWal, availabilityIdentity, serverTick);
		ArrayList<LifecycleOperationPreview.Blocker> blockers = new ArrayList<>();
		if (permission == LifecycleOperationPreview.PermissionDecision.BUILTIN_ADMIN_DENIED) {
			blockers.add(LifecycleOperationPreview.Blocker.PERMISSION_DENIED);
		}
		if (!startup.authoritativeWritesReady()) blockers.add(LifecycleOperationPreview.Blocker.STARTUP_NOT_READY);
		if (region.availabilityFailure() != null
				&& !MinecraftRegionAvailability.inventoryDecreaseMayBypass(region.availabilityReason())) {
			blockers.add(LifecycleOperationPreview.Blocker.REGION_UNAVAILABLE);
		}
		if (!region.solverLoaded()) blockers.add(LifecycleOperationPreview.Blocker.REGION_UNLOADED);
		if (pendingWal || operations.ownershipLockDescription(region) != null) {
			blockers.add(LifecycleOperationPreview.Blocker.PENDING_WAL);
		}

		int ownershipBlocks = 0;
		try {
			ownershipBlocks = operations.committedOwnershipBlockCount(region);
		} catch (IOException | RuntimeException unavailable) {
			addBlocker(blockers, LifecycleOperationPreview.Blocker.PENDING_WAL);
		}
		if (!region.solverLoaded()) return new RegionLifecycleUseCase.PreviewFacts(
				context, LifecycleOperationPreview.Removal.none(),
				LifecycleOperationPreview.GasState.none(), ownershipBlocks, List.copyOf(blockers));

		FluidDomain domain = region.domain();
		if (domain.hasPendingPhaseVolumeWork()) blockers.add(LifecycleOperationPreview.Blocker.PENDING_PHASE_WORK);
		boolean auditClosed = domain.gasBoundary().auditClosed()
				&& (!domain.hasThermalState()
				|| domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()));
		try {
			if (operation == LifecycleOperation.EMPTY_CONDENSED) domain.planOperatorClear();
			if (operation == LifecycleOperation.VENT_SEALED_GAS) domain.planOperatorVentSealedGas();
			if (operation == LifecycleOperation.RELEASE_OWNERSHIP
					|| operation == LifecycleOperation.REMOVE_EMPTY_REGION) domain.planOperatorClear();
		} catch (RuntimeException open) {
			auditClosed = false;
		}
		if (!auditClosed) blockers.add(LifecycleOperationPreview.Blocker.AUDIT_OPEN);

		boolean condensedEmpty = domain.particleCount() == 0
				&& domain.totalGranularSolidVolumeSvq() == 0L
				&& domain.totalCoherentSolidVolumeSvq() == 0L;
		boolean gasEmpty = domain.gasBoundary().nodeCount() == 0 && domain.gasBoundary().sealedInventory().isEmpty()
				&& domain.exactGasInternalEnergy().equals(mmm.units.SignedDyadic.ZERO);
		boolean devicesEmpty = thermalRegistry == null
				|| region.finiteThermalDevices(thermalRegistry.fingerprint()).devices().isEmpty();
		if ((operation == LifecycleOperation.RELEASE_OWNERSHIP || operation == LifecycleOperation.REMOVE_EMPTY_REGION)
				&& !condensedEmpty) blockers.add(LifecycleOperationPreview.Blocker.CONDENSED_NOT_EMPTY);
		if ((operation == LifecycleOperation.RELEASE_OWNERSHIP || operation == LifecycleOperation.REMOVE_EMPTY_REGION)
				&& !gasEmpty) blockers.add(LifecycleOperationPreview.Blocker.GAS_NOT_EMPTY);
		if ((operation == LifecycleOperation.RELEASE_OWNERSHIP || operation == LifecycleOperation.REMOVE_EMPTY_REGION)
				&& !devicesEmpty) blockers.add(LifecycleOperationPreview.Blocker.FINITE_DEVICE_NOT_EMPTY);
		if (operation == LifecycleOperation.REMOVE_EMPTY_REGION && ownershipBlocks != 0) {
			blockers.add(LifecycleOperationPreview.Blocker.OWNERSHIP_NOT_RELEASED);
		}
		if (operation == LifecycleOperation.REMOVE_EMPTY_REGION) {
			try {
				operations.requireRemovalQuarantineCapacity(region,
						startup.config().maximumQuarantineEntries(), startup.config().maximumQuarantineBytes());
			} catch (IOException | RuntimeException unavailable) {
				blockers.add(LifecycleOperationPreview.Blocker.QUARANTINE_UNAVAILABLE);
			}
		}

		GasSummary gas = lifecycleGasSummary(domain);
		LifecycleOperationPreview.GasState gasState = new LifecycleOperationPreview.GasState(gas.nanos(), gas.mass(),
				gas.speciesQuanta(), domain.gasBoundary().nodeCount(), domain.hasAmbientGasCells());
		LifecycleOperationPreview.Removal removal = switch (operation) {
			case EMPTY_CONDENSED -> new LifecycleOperationPreview.Removal(
					AirNetwork.nanos(domain.physicalVolumeCubicMetres(domain.totalVolumeExact())),
					domain.physicalMassKilograms(domain.totalMass()), domain.totalChemicalSpeciesQuanta(),
					domain.totalGranularSolidVolumeSvq(), domain.totalGranularMass(),
					domain.totalCoherentSolidVolumeSvq(), domain.totalCoherentMass(),
					0L, 0.0, 0L, domain.hasThermalState(), domain.hasThermalState()
					? domain.exactInternalEnergy().subtract(domain.exactGasInternalEnergy()).toDouble() : Double.NaN);
			case VENT_SEALED_GAS -> new LifecycleOperationPreview.Removal(
					0L, 0.0, 0L, 0L, 0.0, 0L, 0.0, gas.nanos(), gas.mass(), gas.speciesQuanta(),
					domain.hasThermalState(), domain.hasThermalState()
					? domain.exactGasInternalEnergy().toDouble() : Double.NaN);
			case RELEASE_OWNERSHIP, REMOVE_EMPTY_REGION -> LifecycleOperationPreview.Removal.none();
		};
		return new RegionLifecycleUseCase.PreviewFacts(
				context, removal, gasState, ownershipBlocks, List.copyOf(blockers));
	}

	private static GasSummary lifecycleGasSummary(FluidDomain domain) {
		long nanos = 0L, species = 0L;
		mmm.units.SignedDyadic exactMass = mmm.units.SignedDyadic.ZERO;
		for (var component : domain.gasBoundary().sealedInventory().entries()) {
			nanos = Math.addExact(nanos, component.standardVolumeNanos());
			exactMass = exactMass.add(component.mass());
			for (var amount : component.species()) species = Math.addExact(species, amount.amountQuanta());
		}
		double mass = exactMass.toDouble();
		if (!Double.isFinite(mass)) throw new ArithmeticException("Lifecycle gas mass summary overflow");
		return new GasSummary(nanos, mass, species);
	}

	private ResolvedController resolveInteractiveController(
			ServerPlayer player, UUID requestedRegionId, BlockPos controllerPosition
	) {
		if (player == null || requestedRegionId == null || controllerPosition == null) {
			return ResolvedController.failed("message.mmm.controller.request_identity_missing");
		}
		ServerLevel level = player.level();
		if (!level.getServer().isSameThread()) throw new IllegalStateException("Lifecycle actions are server-thread-only");
		if (player.distanceToSqr(controllerPosition.getX() + 0.5,
				controllerPosition.getY() + 0.5, controllerPosition.getZ() + 0.5) > 64.0) {
			return ResolvedController.failed("message.mmm.controller.too_far");
		}
		if (!level.getBlockState(controllerPosition).is(mmm.block.MmmBlocks.FLUID_CONTROLLER)) {
			return ResolvedController.failed("message.mmm.controller.target_gone");
		}
		FluidRegion region = targets.controllerRegionAt(level, controllerPosition);
		if (region == null || !region.id().equals(requestedRegionId)) {
			return ResolvedController.failed("message.mmm.controller.owner_changed");
		}
		return new ResolvedController(region, null);
	}

	private static void addBlocker(
			ArrayList<LifecycleOperationPreview.Blocker> blockers, LifecycleOperationPreview.Blocker blocker
	) {
		if (!blockers.contains(blocker)) blockers.add(blocker);
	}

	private static Component lifecycleBlockers(List<LifecycleOperationPreview.Blocker> blockers) {
		var result = Component.empty();
		for (int index = 0; index < blockers.size(); index++) {
			if (index > 0) result.append(Component.literal(" · "));
			result.append(Component.translatable("screen.mmm.blocker."
					+ blockers.get(index).name().toLowerCase(java.util.Locale.ROOT)));
		}
		return result;
	}

	public record PreviewResult(LifecycleOperationPreview preview, Component failure) {
		public PreviewResult {
			if ((preview == null) == (failure == null)) {
				throw new IllegalArgumentException("Lifecycle preview result is ambiguous");
			}
		}

		private static PreviewResult failed(Component failure) { return new PreviewResult(null, failure); }
	}

	public record CommitResult(boolean committed, Component message) {
		public CommitResult {
			if (message == null) throw new IllegalArgumentException("Lifecycle result message is missing");
		}

		private static CommitResult committed(String key, Object... arguments) {
			return new CommitResult(true, Component.translatable(key, arguments));
		}

		private static CommitResult failed(String key, Object... arguments) {
			return new CommitResult(false, Component.translatable(key, arguments));
		}

		private static CommitResult failedDetail(String detail) {
			return failed("message.mmm.lifecycle.detail", detail);
		}

		private static CommitResult failedDetail(Component detail) {
			return new CommitResult(false, detail);
		}
	}

	private record GasSummary(long nanos, double mass, long speciesQuanta) { }

	private record ResolvedController(FluidRegion region, Component failure) {
		private static ResolvedController failed(String key) {
			return new ResolvedController(null, Component.translatable(key));
		}
	}
}
