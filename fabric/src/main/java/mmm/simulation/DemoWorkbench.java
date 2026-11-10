package mmm.simulation;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import mmm.block.FluidPortBlock;
import mmm.block.MmmBlocks;
import mmm.controller.ControllerConsoleSessions;
import mmm.item.MmmItems;
import mmm.network.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** One repeatable demonstration per player and dimension, using production region operations. */
public final class DemoWorkbench {
	private record Viewer(UUID player, String dimension) { }
	private static final class Session {
		final UUID id = UUID.randomUUID();
		DemoPreset preset = DemoPreset.WATER;
		UUID region;
		BlockPos anchor;
		Direction facing;
		ControllerDemoLayout tankLayout;
		boolean recipeReady;
	}
	private final Map<Viewer, Session> sessions = new HashMap<>();
	private final ControllerConsoleSessions tokens = new ControllerConsoleSessions();
	private final FluidRegionManager regions;
	private final MinecraftDebugFixtures fixtures;
	private final MinecraftRegionOperatorActions actions;
	private final DemoTankOperations tanks;
	private final WorldStartupCoordinator.Result startup;
	private final FluidRegionOperations operations;
	private final DemoWorkbenchStore store;

	/** World operations used by the shared request state machine. */
	interface Host {
		UUID playerId(); String dimension(); BlockPos position(); Direction facing();
		boolean serverThread(); boolean administrator(); boolean writesReady();
		boolean near(FluidRegion region); boolean nearController(BlockPos controller);
		void registerTank(FluidRegion region);
		void preflightReplacement(FluidRegion previous, ControllerDemoLayout oldTank, BlockPos anchor, Direction facing, DemoPreset preset);
		void createTank(BlockPos anchor, Direction facing);
		void createTransient(BlockPos origin, DemoPreset preset);
		void applyTank(FluidRegion region, DemoPreset preset);
		void removeTank(FluidRegion region); void removeTransient();
		void clearShell(ControllerDemoLayout layout); void verifyRemoved(UUID region);
		void giveSupplies(); boolean setPaused(boolean paused); int step();
	}

	DemoWorkbench(FluidRegionManager regions, MinecraftDebugFixtures fixtures, MinecraftRegionOperatorActions actions,
			DemoTankOperations tanks, WorldStartupCoordinator.Result startup, FluidRegionOperations operations) {
		this.regions = regions; this.fixtures = fixtures; this.actions = actions; this.tanks = tanks; this.startup = startup;
		this.operations = operations;
		this.store = new DemoWorkbenchStore(startup.worldRoot());
		try { loadSessions(store); } catch (java.io.IOException error) { throw new IllegalStateException("无法读取演示水箱记录", error); }
	}
	DemoWorkbench(FluidRegionManager regions, DemoWorkbenchStore store) {
		this.regions = Objects.requireNonNull(regions); this.store = Objects.requireNonNull(store);
		this.fixtures = null; this.actions = null; this.tanks = null; this.startup = null; this.operations = null;
		try { loadSessions(store); } catch (java.io.IOException error) { throw new IllegalStateException("无法读取演示水箱记录", error); }
	}
	private void loadSessions(DemoWorkbenchStore store) throws java.io.IOException {
		for (var saved : store.loadAll()) {
			Session session = new Session();
			session.region = saved.region(); session.anchor = saved.anchor(); session.facing = saved.facing();
			session.preset = saved.preset(); session.recipeReady = saved.recipeReady();
			session.tankLayout = ControllerDemoLayout.at(saved.anchor(), saved.facing());
			sessions.put(new Viewer(saved.player(), saved.dimension()), session);
		}
	}
	public DemoWorkbenchPayload open(ServerPlayer player) {
		return open(new MinecraftHost(player));
	}
	DemoWorkbenchPayload open(Host host) {
		Session session = session(host);
		String failure = tankMismatch(host, session) ? "演示水箱记录与区域布局不同"
				: session.region != null && current(session) == null && session.tankLayout != null
				? "演示区域尚未恢复；若已删除，请在附近重置或移除"
				: session.region != null && !session.recipeReady ? "演示配方未完成，请重置" : null;
		return response(host, session, 0, failure);
	}
	public DemoWorkbenchPayload launch(ServerPlayer player, DemoPreset preset) {
		var opened = open(player);
		var result = execute(player, new DemoActionPayload(opened.sessionId(), opened.generation(), opened.regionId(), preset, DemoAction.RESET));
		return new DemoWorkbenchPayload(result.sessionId(), 0, result.generation(), result.dimensionId(), result.preset(),
				result.regionId(), result.paused(), result.failure(), true, result.blocked());
	}
	public DemoWorkbenchPayload execute(ServerPlayer player, DemoActionPayload request) {
		return execute(new MinecraftHost(player), request);
	}
	DemoWorkbenchPayload execute(Host host, DemoActionPayload request) {
		Session session = session(host);
		String failure = null;
		try {
			if (!host.administrator())
				throw new IllegalStateException("演示操作需要管理员权限");
			if (!host.writesReady()) throw new IllegalStateException("世界尚未准备好");
			if (tankMismatch(host, session)) throw new IllegalStateException("演示水箱记录与区域布局不同");
			if (!session.id.equals(request.sessionId())
					|| !tokens.consume(host.playerId(), session.id, request.generation()))
				throw new IllegalStateException("操作已过期，请重试");
			FluidRegion current = current(session);
			if (!Objects.equals(request.regionId(), session.region)) throw new IllegalStateException("演示已改变，请重试");
			boolean removedElsewhere = false;
			if (current == null && session.region != null && session.tankLayout != null) {
				if (request.action() != DemoAction.START && request.action() != DemoAction.RESET
						&& request.action() != DemoAction.REMOVE) throw new IllegalStateException("演示区域暂不可用，请先恢复或重置");
				reconcileRemovedTank(host, session);
				removedElsewhere = true;
			}
			if (current != null) {
				if (!current.dimension().identifier().toString().equals(host.dimension()) || !host.near(current))
					throw new IllegalStateException("请回到演示附近");
				regions.select(current.id());
			}
			switch (request.action()) {
				case START, RESET -> {
					if (request.action() == DemoAction.RESET || current == null || !session.recipeReady || session.preset != request.preset())
						prepare(host, session, request.preset());
					if (!host.setPaused(request.action() == DemoAction.RESET))
						throw new IllegalStateException("演示暂时不可用，请刷新或重置");
				}
				case PAUSE, STEP, REMOVE -> {
					if (removedElsewhere && request.action() == DemoAction.REMOVE) { session.anchor = null; break; }
					if (current == null || request.preset() != session.preset) throw new IllegalStateException("请先开始此演示");
					if (request.action() == DemoAction.PAUSE) {
						if (!host.setPaused(true)) throw new IllegalStateException("演示暂时不可用，请刷新或重置");
					}
					else if (request.action() == DemoAction.STEP) {
						if (!current.paused()) throw new IllegalStateException("请先暂停");
						if (host.step() != 1) throw new IllegalStateException("单步未完成，请检查区域状态");
					} else { remove(host, session); session.anchor = null; }
				}
			}
		} catch (RuntimeException error) {
			failure = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
			mmm.MmmMod.LOGGER.warn("Demo action {} failed: {}", request.action(), failure, error);
		}
		try { persist(); }
		catch (java.io.IOException error) {
			failure = "演示状态记录失败：" + error.getMessage();
			mmm.MmmMod.LOGGER.error(failure, error);
		}
		if (failure == null) {
			FluidRegion region = current(session);
			mmm.MmmMod.LOGGER.info("Demo {} {} committed: region={}, paused={}", request.preset(), request.action(),
					session.region, region == null || region.paused());
		}
		return response(host, session, request.generation(), failure);
	}
	private void prepare(Host host, Session session, DemoPreset preset) {
		BlockPos anchor = session.anchor == null ? host.position().immutable() : session.anchor;
		Direction facing = session.anchor == null ? host.facing() : session.facing;
		FluidRegion old = current(session);
		if (!preset.tank() && regions.regions().stream().anyMatch(region -> !region.hasSelectedVolume()
				&& !region.id().equals(session.region)))
			throw new IllegalStateException("已有临时场景，请先移除它");
		if (old != null && (old.hasSelectedVolume() != preset.tank())) {
			host.preflightReplacement(old, session.tankLayout, anchor, facing, preset);
			remove(host, session);
		}
		if (preset.tank()) {
			session.recipeReady = false;
			if (current(session) == null) {
				host.createTank(anchor, facing);
				session.region = regions.selected().id();
				session.anchor = anchor; session.facing = facing;
				session.tankLayout = ControllerDemoLayout.at(anchor, facing);
				host.registerTank(regions.selected());
				session.preset = DemoPreset.WATER;
				try { persist(); } catch (java.io.IOException error) { throw new IllegalStateException("无法记录演示水箱身份", error); }
			}
			try { persist(); } catch (java.io.IOException error) { throw new IllegalStateException("无法记录演示配方准备状态", error); }
			host.applyTank(current(session), preset);
			host.giveSupplies();
		} else {
			BlockPos origin = transientOrigin(anchor, facing, preset);
			host.createTransient(origin, preset);
			session.region = regions.selected().id();
			session.anchor = anchor; session.facing = facing;
		}
		session.preset = preset;
		session.recipeReady = true;
	}
	private void remove(Host host, Session session) {
		FluidRegion region = current(session);
		if (region == null) return;
		regions.select(region.id());
		if (region.hasSelectedVolume()) {
			host.removeTank(region);
			if (session.tankLayout != null) host.clearShell(session.tankLayout);
			session.tankLayout = null;
		} else {
			host.removeTransient();
		}
		session.region = null;
	}
	private Session session(Host host) {
		if (!host.serverThread()) throw new IllegalStateException("Demo actions require server thread");
		Session session = sessions.computeIfAbsent(new Viewer(host.playerId(), host.dimension()), key -> new Session());
		FluidRegion region = current(session);
		if (region != null && session.tankLayout != null && !tankMismatch(host, session)) host.registerTank(region);
		return session;
	}
	private boolean tankMismatch(Host host, Session session) {
		FluidRegion region = current(session);
		return region != null && session.tankLayout != null && !matchesTank(region, session.tankLayout, host.dimension());
	}
	private void reconcileRemovedTank(Host host, Session session) {
		var position = session.tankLayout.controller();
		if (!host.nearController(position))
			throw new IllegalStateException("请回到演示区域附近");
		host.verifyRemoved(session.region);
		host.clearShell(session.tankLayout);
		session.region = null; session.tankLayout = null; session.recipeReady = false;
		try { persist(); }
		catch (java.io.IOException failure) { throw new IllegalStateException("无法保存演示移除状态", failure); }
	}

	private static void clearUnchangedShell(ServerPlayer player, ControllerDemoLayout layout) {
		var level = player.level();
		for (BlockPos position : layout.shell()) if (!level.hasChunkAt(position))
			throw new IllegalStateException("演示壳体所在区块尚未加载");
		for (BlockPos position : layout.shell()) {
			var expected = shellState(layout, position);
			if (level.getBlockState(position).equals(expected)
					&& !level.setBlock(position, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL))
				throw new IllegalStateException("无法清理演示壳体 " + position.toShortString());
		}
	}
	static net.minecraft.world.level.block.state.BlockState shellState(ControllerDemoLayout layout, BlockPos position) {
		return position.equals(layout.controller()) ? Blocks.IRON_BLOCK.defaultBlockState()
				: position.equals(layout.port()) ? MmmBlocks.FLUID_PORT.defaultBlockState()
						.setValue(FluidPortBlock.FACING, layout.portOutward()) : Blocks.GLASS.defaultBlockState();
	}
	static BlockPos transientOrigin(BlockPos anchor, Direction facing, DemoPreset preset) {
		boolean flow = preset == DemoPreset.FLOW;
		BlockPos center = anchor.relative(facing, 9);
		return center.offset(-(flow ? DemoSceneLayout.FLOW_SIZE_X : DemoSceneLayout.AIR_SIZE_X) / 2,
				0, -(flow ? DemoSceneLayout.FLOW_SIZE_Z : DemoSceneLayout.AIR_SIZE_Z) / 2);
	}

	static boolean matchesTank(FluidRegion region, ControllerDemoLayout layout, String dimensionId) {
		if (!region.hasSelectedVolume() || !region.dimension().identifier().toString().equals(dimensionId)) return false;
		var footprint = region.footprint();
        return footprint.kind() == mmm.model.region.RegionGeometry.RegionKind.SELECTED
                && footprint.minimumInterior().equals(layout.minimum())
                && footprint.maximumInterior().equals(layout.maximum());
	}

	private void persist() throws java.io.IOException {
		var saved = new java.util.ArrayList<DemoWorkbenchStore.Entry>();
		for (var entry : sessions.entrySet()) {
			Session session = entry.getValue();
			if (session.region != null && session.tankLayout != null)
				saved.add(new DemoWorkbenchStore.Entry(entry.getKey().player(), entry.getKey().dimension(), session.region,
						session.anchor, session.facing, session.preset, session.recipeReady));
		}
		store.save(saved);
	}
	private FluidRegion current(Session session) {
		FluidRegion region = session.region == null ? null : regions.region(session.region);
		if (region == null && session.tankLayout == null) session.region = null;
		return region;
	}
	private DemoWorkbenchPayload response(Host host, Session session, long request, String failure) {
		FluidRegion region = current(session);
		String blocked = region == null ? (session.region == null ? null : "演示区域暂不可用，请先恢复或重置")
				: region.availabilityFailure();
		if (region != null && !region.solverLoaded() && blocked == null) blocked = "演示区域尚未加载";
		if (blocked != null) failure = failure == null ? blocked : failure + "; " + blocked;
		return new DemoWorkbenchPayload(session.id, request, tokens.issue(host.playerId(), session.id),
				host.dimension(), session.preset, session.region,
				region != null && region.paused(), failure, false, blocked != null);
	}
	private final class MinecraftHost implements Host {
		private final ServerPlayer player;
		MinecraftHost(ServerPlayer player) { this.player = Objects.requireNonNull(player); }
		public UUID playerId() { return player.getUUID(); }
		public String dimension() { return player.level().dimension().identifier().toString(); }
		public BlockPos position() { return player.blockPosition(); }
		public Direction facing() { return player.getDirection(); }
		public boolean serverThread() { return player.level().getServer().isSameThread(); }
		public boolean administrator() { return player.createCommandSourceStack().permissions().hasPermission(Permissions.COMMANDS_ADMIN); }
		public boolean writesReady() { return startup.authoritativeWritesReady(); }
		public boolean near(FluidRegion region) { return DemoWorkbench.near(player, region); }
		public boolean nearController(BlockPos controller) {
			return player.distanceToSqr(controller.getX() + .5, controller.getY() + .5, controller.getZ() + .5) <= 64;
		}
		public void registerTank(FluidRegion region) { tanks.register(region); }
		public void preflightReplacement(FluidRegion previous, ControllerDemoLayout oldTank, BlockPos anchor, Direction facing, DemoPreset preset) {
			if (preset.tank()) {
				if (!actions.controllerFeatureEnabled()) throw new IllegalStateException("区域运行未启用");
				if (operations.creationLocked()) throw new IllegalStateException("区域存档尚需恢复，无法创建区域");
				String failure = tanks.recipeCapabilityFailure(preset);
				if (failure != null) throw new IllegalStateException(failure);
			}
			fixtures.preflightReplacement(player, previous, oldTank, anchor, facing, preset);
		}
		public void createTank(BlockPos anchor, Direction facing) {
			if (fixtures.createControllerDemo(player, anchor, facing) == null) throw new IllegalStateException(fixtures.lastFailure());
		}
		public void createTransient(BlockPos origin, DemoPreset preset) {
			boolean created = preset == DemoPreset.FLOW ? fixtures.createFlowDemo(player, origin) : fixtures.createAirDemo(player, origin,
					switch (preset) {
						case AMBIENT -> DebugScenarioInitialization.AirPressure.AMBIENT;
						case PRESSURE -> DebugScenarioInitialization.AirPressure.PRESSURE;
						case VACUUM -> DebugScenarioInitialization.AirPressure.VACUUM;
						default -> throw new IllegalStateException("Unknown pressure preset");
					});
			if (!created) throw new IllegalStateException(fixtures.lastFailure());
		}
		public void applyTank(FluidRegion region, DemoPreset preset) {
			var recipe = switch (preset) {
				case WATER -> DemoTankOperations.Recipe.WATER;
				case MATERIAL -> DemoTankOperations.Recipe.OIL_WATER;
				case CHEMISTRY -> DemoTankOperations.Recipe.ACID_BASE;
				default -> throw new IllegalStateException("Unknown tank recipe");
			};
			String failure = tanks.apply(player.level(), region, recipe);
			if (failure != null) throw new IllegalStateException(failure);
		}
		public void removeTank(FluidRegion region) {
			String failure = tanks.remove(player, region);
			if (failure != null) throw new IllegalStateException(failure);
		}
		public void removeTransient() {
			var removed = actions.remove(player.level().getServer());
			if (!removed.completed()) throw new IllegalStateException(removed.failure());
		}
		public void clearShell(ControllerDemoLayout layout) { clearUnchangedShell(player, layout); }
		public void verifyRemoved(UUID region) {
			try { operations.readRemoved(region); }
			catch (java.io.IOException failure) { throw new IllegalStateException("演示区域尚未恢复或删除状态无法确认", failure); }
		}
		public void giveSupplies() {
			giveIfAbsent(player, Items.WATER_BUCKET); giveIfAbsent(player, Items.BUCKET);
			giveIfAbsent(player, MmmItems.MATERIAL_BUCKET); giveIfAbsent(player, MmmItems.FIELD_PROBE);
		}
		public boolean setPaused(boolean paused) { return actions.setPaused(paused, player.level()); }
		public int step() { return actions.step(player.level(), 1); }
	}
	private static boolean near(ServerPlayer player, FluidRegion region) {
		double cell = region.solverConfig().cellSize();
		BlockPos origin = region.origin();
		double x = Math.clamp(player.getX(), origin.getX(), origin.getX() + region.sizeX() * cell);
		double y = Math.clamp(player.getY(), origin.getY(), origin.getY() + region.sizeY() * cell);
		double z = Math.clamp(player.getZ(), origin.getZ(), origin.getZ() + region.sizeZ() * cell);
		return player.distanceToSqr(x, y, z) <= 64;
	}
	private static void giveIfAbsent(ServerPlayer player, Item item) {
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) if (player.getInventory().getItem(i).is(item)) return;
		ItemStack stack = new ItemStack(item);
		if (!player.addItem(stack)) player.drop(stack, false);
	}
}
