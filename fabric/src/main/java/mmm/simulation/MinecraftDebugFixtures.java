package mmm.simulation;

import mmm.application.RegionCatalog;
import mmm.block.MmmBlocks;
import mmm.block.FluidPortBlock;
import mmm.fluid.HostFluidState;
import mmm.network.DemoPreset;
import mmm.worldprofile.WorldCapability;
import mmm.worldprofile.WorldCapabilityProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Creates persistent controller tanks and transient flow, pressure, wetting and capture scenes. */
public final class MinecraftDebugFixtures {
	static final int BLOCK_SIZE_X = 12;
	static final int BLOCK_SIZE_Y = 8;
	static final int BLOCK_SIZE_Z = 12;

	/** Region-runtime operations the scenarios need; owned here so the surface stays narrow. */
	interface Runtime {
		/** Re-checks availability, then re-samples collision and thermal boundaries. */
		FluidRegion.CollisionStats refreshCollisions(ServerLevel level);

		/** Runs one synchronous logical motion step through the complete candidate boundary. */
		void stepMotion(FluidRegion region);

		/** Commits phase-driven displacement before editing gas inventory. */
		void settlePendingPhaseWork(FluidRegion region);

		/** Binds the committed world capability registries to a freshly allocated domain. */
		void bindCapabilityState(HostFluidState domain);
		CommittedWorldRegistries worldRegistries();

		void bindTensionPalette(HostFluidState domain, int angleDegrees);
		void bindTensionCapabilityState(HostFluidState domain, int angleDegrees);

		/** Activates or re-selects a controller; returns null on success or the failure text. */
		ControllerActivation activateController(ServerPlayer player, BlockPos minimum, BlockPos maximum);
	}

	record ControllerActivation(String failure, boolean retainShell) { }

	private final FluidRegionManager regions;
	private final FluidSnapshotPublisher snapshots;
	private final MinecraftTakeoverAdapter takeover;
	private final MinecraftDebugScaffolding scaffolding;
	private final Runtime runtime;
	private final WorldCapabilityProfile profile;
	private final BooleanSupplier defaultEntityPhysics;
	private final BooleanSupplier defaultDebugOverlays;
	private String lastFailure;
	private UUID scaffoldRegionId;

	MinecraftDebugFixtures(
			FluidRegionManager regions,
			FluidSnapshotPublisher snapshots,
			MinecraftTakeoverAdapter takeover,
			MinecraftDebugScaffolding scaffolding,
			Runtime runtime,
			WorldCapabilityProfile profile,
			BooleanSupplier defaultEntityPhysics,
			BooleanSupplier defaultDebugOverlays
	) {
		this.regions = regions;
		this.snapshots = snapshots;
		this.takeover = takeover;
		this.scaffolding = scaffolding;
		this.runtime = runtime;
		this.profile = profile;
		this.defaultEntityPhysics = defaultEntityPhysics;
		this.defaultDebugOverlays = defaultDebugOverlays;
	}

	public String lastFailure() {
		return lastFailure;
	}

	/** Restores the world blocks the gas-demo glass scaffold replaced; player-edited positions are left alone. */
	public void restoreAirDemoScaffolding(MinecraftServer server) {
		scaffolding.restore(server);
		scaffoldRegionId = null;
	}

	/**
	 * Safely builds and activates the small persistent controller fixture used by container and
	 * chemistry demonstrations. The complete occupied volume must already be loaded and air;
	 * failed placement or activation restores every block written by this method.
	 */
	BlockPos createControllerDemo(ServerPlayer player, BlockPos anchor, net.minecraft.core.Direction facing) {
		ServerLevel level = player.level();
		ControllerDemoLayout layout = ControllerDemoLayout.at(anchor, facing);
		try { for (BlockPos position : layout.occupied()) scaffolding.requireAir(level, position, ignored -> false); }
		catch (IllegalStateException failure) { lastFailure = failure.getMessage(); return null; }

		List<BlockPos> placed = new ArrayList<>();
		try {
			for (BlockPos position : layout.shell()) {
				BlockState state = position.equals(layout.controller())
						? Blocks.IRON_BLOCK.defaultBlockState()
						: position.equals(layout.port()) ? MmmBlocks.FLUID_PORT.defaultBlockState()
								.setValue(FluidPortBlock.FACING, layout.portOutward())
						: Blocks.GLASS.defaultBlockState();
				if (!level.setBlock(position, state, Block.UPDATE_ALL)) {
					throw new IllegalStateException("无法放置壳块 " + position.toShortString());
				}
				placed.add(position.immutable());
			}
		} catch (RuntimeException failure) {
			rollbackControllerDemoBlocks(level, placed);
			lastFailure = "演示容器已回滚：" + failure.getMessage();
			return null;
		}

		ControllerActivation activation;
		try {
			activation = runtime.activateController(player, layout.minimum(), layout.maximum());
		} catch (RuntimeException failure) {
			// An unchecked activation failure may have reached persistent region-state. Keep the
			// visible shell so recovery never has to guess whether removing it is safe.
			lastFailure = "演示区域创建异常；为保护可能已发布的 region-state，壳体已保留："
					+ failure.getMessage();
			return null;
		}
		if (activation.failure() != null) {
			if (!activation.retainShell()) rollbackControllerDemoBlocks(level, placed);
			lastFailure = (activation.retainShell() ? "演示容器已保留，等待恢复：" : "演示容器已回滚：")
					+ activation.failure();
			return null;
		}
		lastFailure = null;
		return layout.controller();
	}

	/** Checks the destination while the old demo and its inventory still exist. */
	void preflightReplacement(ServerPlayer player, FluidRegion previous, ControllerDemoLayout oldTank,
			BlockPos anchor, net.minecraft.core.Direction facing, DemoPreset preset) {
		ServerLevel level = player.level();
		var oldShell = new java.util.HashSet<BlockPos>();
		if (oldTank != null) oldShell.addAll(oldTank.shell());
		java.util.function.Predicate<BlockPos> willClear = position ->
				oldTank != null && oldShell.contains(position)
						&& level.getBlockState(position).equals(DemoWorkbench.shellState(oldTank, position))
				|| previous.id().equals(scaffoldRegionId) && scaffolding.restoresAir(level, position);
		var target = preflightSpace(anchor, facing, preset, position -> scaffolding.requireAir(level, position, willClear));
		var preview = regions.previewReplacement(level.dimension(), target.origin(), target.sizeX(), target.sizeY(), target.sizeZ(), target.cells());
		if (!preview.accepted()) throw new IllegalStateException(preview.failure());
	}
	record ReplacementTarget(BlockPos origin, int sizeX, int sizeY, int sizeZ, long cells) { }
	static ReplacementTarget preflightSpace(BlockPos anchor, net.minecraft.core.Direction facing, DemoPreset preset,
			java.util.function.Consumer<BlockPos> check) {
		if (preset.tank()) {
			var layout = ControllerDemoLayout.at(anchor, facing);
			for (BlockPos position : layout.occupied()) check.accept(position);
			return new ReplacementTarget(layout.minimum().offset(1, 1, 1), 2, 2, 2, 4L * 4 * 4);
		}
		boolean flow = preset == DemoPreset.FLOW;
		int x = flow ? DemoSceneLayout.FLOW_SIZE_X : DemoSceneLayout.AIR_SIZE_X;
		int y = flow ? DemoSceneLayout.FLOW_SIZE_Y : DemoSceneLayout.AIR_SIZE_Y;
		int z = flow ? DemoSceneLayout.FLOW_SIZE_Z : DemoSceneLayout.AIR_SIZE_Z;
		BlockPos origin = DemoWorkbench.transientOrigin(anchor, facing, preset);
		for (int bz = 0; bz < z; bz++) for (int by = 0; by < y; by++) for (int bx = 0; bx < x; bx++)
			check.accept(origin.offset(bx, by, bz));
		if (flow) for (BlockPos step : DemoSceneLayout.entrySteps()) check.accept(origin.offset(step));
		return new ReplacementTarget(origin, x, y, z,
				(long) x * y * z * FluidRegionFactory.CELL_VOLUMES_PER_BLOCK);
	}

	private static void rollbackControllerDemoBlocks(ServerLevel level, List<BlockPos> placed) {
		for (int index = placed.size() - 1; index >= 0; index--) {
			BlockPos position = placed.get(index);
			if (level.getBlockState(position).isCollisionShapeFullBlock(level, position)) {
				level.setBlock(position, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			}
		}
	}

	public boolean createTensionDemo(ServerPlayer player, int angleDegrees) {
		if (!profile.hasCapability(WorldCapability.MATERIALS)) {
			lastFailure = "材料功能未在本次启动启用；先保存配置并重启";
			return false;
		}
		ServerLevel level = player.level();
		BlockPos origin = aimedOrigin(player);
		return runTransient(level, origin, TransientKind.TENSION, context -> {
			scaffolding.placeTensionFloor(level, origin);
			runtime.refreshCollisions(level);
			long generation = regions.selected().solverGeneration();
			HostFluidState domain = regions.selected().beginHostEdit();
			for (int z = 0; z < domain.sizeZ(); z++) for (int x = 0; x < domain.sizeX(); x++) {
				if (!domain.isSolid(x, 1, z)) reject("液滴演示需要完整实心地板；请在已加载的空旷区域重试");
			}
			runtime.bindTensionPalette(domain, angleDegrees);
			DebugScenarioInitialization.fillTensionCap(domain);
			if (domain.totalVolume() <= 0.0F) reject("液滴演示没有可装水的开放空间；请在空旷处重试");
			runtime.bindTensionCapabilityState(domain, angleDegrees);
			regions.selected().commitHostEdit(generation, domain);
		});
	}

	boolean createAirDemo(ServerPlayer player, BlockPos origin, DebugScenarioInitialization.AirPressure pressure) {
		ServerLevel level = player.level();
		return runTransient(level, origin, TransientKind.AIR, context -> {
			scaffolding.requireAir(level, origin, DemoSceneLayout.AIR_SIZE_X, DemoSceneLayout.AIR_SIZE_Y, DemoSceneLayout.AIR_SIZE_Z);
			scaffolding.placeAirDemo(level, origin);
			runtime.refreshCollisions(level);
			long generation = regions.selected().solverGeneration();
			HostFluidState domain = regions.selected().beginHostEdit();
			DebugScenarioInitialization.fillAirWater(domain);
			runtime.bindCapabilityState(domain);
			regions.selected().commitHostEdit(generation, domain);
			runtime.stepMotion(regions.selected());
			runtime.settlePendingPhaseWork(regions.selected());
			generation = regions.selected().solverGeneration();
			domain = regions.selected().beginHostEdit();
			if (DebugScenarioInitialization.presetAirPressure(domain, pressure) < 0)
				reject("左侧气腔未密闭；请清理模拟区域后重试");
			regions.selected().commitHostEdit(generation, domain);
		});
	}

	boolean createFlowDemo(ServerPlayer player, BlockPos origin) {
		ServerLevel level = player.level();
		return runTransient(level, origin, TransientKind.FLOW, context -> {
			scaffolding.placeFlowDemo(level, origin);
			runtime.refreshCollisions(level);
			long generation = regions.selected().solverGeneration();
			HostFluidState domain = regions.selected().beginHostEdit();
			DemoSceneInitialization.fillFlow(domain);
			runtime.bindCapabilityState(domain);
			regions.selected().commitHostEdit(generation, domain);
			regions.selected().setEntityPhysicsEnabled(true);
		});
	}

	private static BlockPos aimedOrigin(ServerPlayer player) {
		Vec3 look = player.getLookAngle();
		BlockPos center = BlockPos.containing(player.getX() + look.x * 9.0, player.getY(), player.getZ() + look.z * 9.0);
		return center.offset(-BLOCK_SIZE_X / 2, 0, -BLOCK_SIZE_Z / 2);
	}

	public boolean createDemo(ServerPlayer player) {
		ServerLevel level = player.level();
		return runTransient(level, aimedOrigin(player), TransientKind.NORMAL, context -> {
			runtime.refreshCollisions(level);
			long generation = regions.selected().solverGeneration();
			HostFluidState domain = regions.selected().beginHostEdit();
			runtime.bindCapabilityState(domain);
			DebugScenarioInitialization.fillDam(domain, false);
			if (domain.totalVolume() <= 0.0F) reject("溃坝区域没有可装水的开放空间；请在空旷处重试");
			runtime.bindCapabilityState(domain);
			regions.selected().commitHostEdit(generation, domain);
		});
	}

	public CaptureResult captureNearbyWater(ServerPlayer player, boolean takeOver) {
		ServerLevel level = player.level();
		BlockPos origin = player.blockPosition().offset(-BLOCK_SIZE_X / 2, -2, -BLOCK_SIZE_Z / 2);
		int[] counts = new int[2];
		boolean created = runTransient(level, origin, TransientKind.NORMAL, context -> {
			runtime.refreshCollisions(level);
			counts[0] = FluidRegionFactory.captureVanillaWater(level, regions.selected(), takeOver);
			if (counts[0] == 0) reject("附近没有可采集的原版水；请靠近水体重试");
			long generation = regions.selected().solverGeneration();
			HostFluidState edit = regions.selected().beginHostEdit();
			runtime.bindCapabilityState(edit);
			regions.selected().commitHostEdit(generation, edit);
			if (takeOver) {
				MinecraftTakeoverAdapter.BeginResult result = takeover.begin(level, regions.selected(), FluidRegionFactory.CELLS_PER_BLOCK);
				counts[1] = result.removedBlocks();
				if (counts[1] <= 0) reject(counts[1] < 0 ? result.failure() : "未找到可接管的原版水方块");
				context.takeoverStarted = true;
			}
		});
		return new CaptureResult(counts[0], counts[1], created ? null : lastFailure);
	}

	private enum TransientKind { NORMAL, AIR, TENSION, FLOW }
	@FunctionalInterface
	private interface TransientWork { void initialize(TransientContext context); }
	private static final class TransientContext { boolean takeoverStarted; }
	private static final class FixtureRejected extends RuntimeException {
		FixtureRejected(String message) { super(message); }
	}
	private static void reject(String message) { throw new FixtureRejected(message); }

	/** Prepares one candidate and either publishes it or restores the previous selection and scaffold. */
	private boolean runTransient(ServerLevel level, BlockPos origin, TransientKind kind, TransientWork work) {
		FluidRegion previous = regions.selected();
		FluidRegion replaced = previous != null && !previous.hasSelectedVolume() ? previous
				: regions.regions().stream().filter(region -> !region.hasSelectedVolume()).findFirst().orElse(null);
		boolean replacing = replaced != null;
		if (takeover.pendingBlockCount() > 0) {
			lastFailure = "请先使用 /mmm remove 完成现有水体接管的恢复";
			return false;
		}
		boolean ownsScaffold = replacing && replaced.id().equals(scaffoldRegionId);
		boolean otherScaffold = scaffolding.active() && !ownsScaffold;
		if (otherScaffold && kind != TransientKind.NORMAL) {
			lastFailure = "请先选择并替换已有气压或液滴演示，以恢复其脚手架";
			return false;
		}
		HostFluidState domain = switch (kind) {
			case FLOW -> FluidRegionFactory.createDomain(DemoSceneLayout.FLOW_SIZE_X, DemoSceneLayout.FLOW_SIZE_Y, DemoSceneLayout.FLOW_SIZE_Z, runtime.worldRegistries());
			case AIR -> FluidRegionFactory.createAirDomain(runtime.worldRegistries());
			case TENSION -> FluidRegionFactory.createTensionDomain(runtime.worldRegistries());
			case NORMAL -> FluidRegionFactory.createDomain(BLOCK_SIZE_X, BLOCK_SIZE_Y, BLOCK_SIZE_Z, runtime.worldRegistries());
		};
		FluidRegion candidate = new FluidRegion(UUID.randomUUID(), level.dimension(), origin, domain);
		candidate.setEntityPhysicsEnabled(kind != TransientKind.AIR && defaultEntityPhysics.getAsBoolean());
		candidate.setDebugOverlays(defaultDebugOverlays.getAsBoolean());
		if (replacing) regions.select(replaced.id());
		RegionCatalog.AdmissionResult preview = replacing ? regions.previewReplacement(domain.grid().cellCount())
				: regions.previewAdd(candidate);
		if (!preview.accepted()) {
			if (previous != null) regions.select(previous.id());
			lastFailure = preview.failure(); return false;
		}
		RestorationStats restoration = takeover.restore(level.getServer());
		if (!restoration.completed()) {
			if (previous != null) regions.select(previous.id());
			lastFailure = restoration.failure(); return false;
		}
		MinecraftDebugScaffolding.Saved saved = scaffolding.snapshot();
		UUID savedScaffoldRegionId = scaffoldRegionId;
		boolean admitted = false;
		TransientContext context = new TransientContext();
		try {
			if (ownsScaffold) scaffolding.restore(level.getServer());
			RegionCatalog.AdmissionResult admission = replacing ? regions.replaceSelected(candidate) : regions.addAndSelect(candidate);
			if (!admission.accepted()) reject(admission.failure());
			admitted = true;
			work.initialize(context);
			candidate.ensureDeviceResident();
			candidate.setPaused(true);
			sendSnapshot(level);
			if (replacing) snapshots.sendRemoval(level.getServer(), replaced);
			if (!otherScaffold) scaffoldRegionId = scaffolding.active() ? candidate.id() : null;
			lastFailure = null;
		} catch (RuntimeException | Error failure) {
			if (context.takeoverStarted) try {
				RestorationStats rollback = takeover.restore(level.getServer());
				if (!rollback.completed()) throw new IllegalStateException(rollback.failure());
			} catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
			if (admitted) {
				if (!otherScaffold) try { scaffolding.restore(level.getServer()); }
				catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
				try {
					if (replacing) {
						var rollback = regions.replaceSelected(replaced);
						if (!rollback.accepted()) throw new IllegalStateException(rollback.failure());
					} else {
						regions.remove(candidate.id());
						if (previous != null) regions.select(previous.id());
					}
					if (previous != null) regions.select(previous.id());
					snapshots.sendRemoval(level.getServer(), candidate);
					if (replacing) {
						ServerLevel oldLevel = level.getServer().getLevel(replaced.dimension());
						if (oldLevel != null) snapshots.sendToLevel(oldLevel, replaced);
					}
				} catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
			}
			if (admitted && regions.region(candidate.id()) != candidate) try { candidate.discardTransient(); }
			catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
			if (!admitted && previous != null) regions.select(previous.id());
			if (ownsScaffold) try { scaffolding.reapply(level.getServer(), saved); }
			catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
			scaffoldRegionId = savedScaffoldRegionId;
			if (failure instanceof FixtureRejected && failure.getSuppressed().length == 0) {
				lastFailure = failure.getMessage();
				return false;
			}
			throw failure;
		}
		if (replacing) replaced.discardTransient();
		return true;
	}

	private void sendSnapshot(ServerLevel level) {
		FluidRegion region = regions.selected();
		if (region != null && level.dimension().equals(region.dimension())) snapshots.sendToLevel(level, region);
	}
}
