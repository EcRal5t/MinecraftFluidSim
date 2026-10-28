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

	DemoWorkbench(FluidRegionManager regions, MinecraftDebugFixtures fixtures, MinecraftRegionOperatorActions actions,
			DemoTankOperations tanks, WorldStartupCoordinator.Result startup, FluidRegionOperations operations) {
		this.regions = regions; this.fixtures = fixtures; this.actions = actions; this.tanks = tanks; this.startup = startup;
		this.operations = operations;
		this.store = new DemoWorkbenchStore(startup.worldRoot());
		try {
			for (var saved : store.loadAll()) {
				Session session = new Session();
				session.region = saved.region(); session.anchor = saved.anchor(); session.facing = saved.facing();
				session.preset = saved.preset(); session.recipeReady = saved.recipeReady();
				session.tankLayout = ControllerDemoLayout.at(saved.anchor(), saved.facing());
				sessions.put(new Viewer(saved.player(), saved.dimension()), session);
			}
		} catch (java.io.IOException error) { throw new IllegalStateException("无法读取演示水箱记录", error); }
	}
	public DemoWorkbenchPayload open(ServerPlayer player) {
		Session session = session(player);
		String failure = session.region != null && current(session) == null && session.tankLayout != null
				? "演示区域尚未恢复；若已删除，请在附近重置或移除"
				: session.region != null && !session.recipeReady ? "演示配方未完成，请重置" : null;
		return response(player, session, 0, failure);
	}
	public DemoWorkbenchPayload launch(ServerPlayer player, DemoPreset preset) {
		var opened = open(player);
		var result = execute(player, new DemoActionPayload(opened.sessionId(), opened.generation(), opened.regionId(), preset, DemoAction.RESET));
		return new DemoWorkbenchPayload(result.sessionId(), 0, result.generation(), result.dimensionId(), result.preset(),
				result.regionId(), result.paused(), result.failure(), true, result.blocked());
	}
	public DemoWorkbenchPayload execute(ServerPlayer player, DemoActionPayload request) {
		Session session = session(player);
		String failure = null;
		try {
			if (!player.createCommandSourceStack().permissions().hasPermission(Permissions.COMMANDS_ADMIN))
				throw new IllegalStateException("演示操作需要管理员权限");
			if (!startup.authoritativeWritesReady()) throw new IllegalStateException("世界尚未准备好");
			if (!session.id.equals(request.sessionId())
					|| !tokens.consume(player.getUUID(), session.id, request.generation()))
				throw new IllegalStateException("操作已过期，请重试");
			FluidRegion current = current(session);
			if (!Objects.equals(request.regionId(), session.region)) throw new IllegalStateException("演示已改变，请重试");
			boolean removedElsewhere = false;
			if (current == null && session.region != null && session.tankLayout != null) {
				if (request.action() != DemoAction.START && request.action() != DemoAction.RESET
						&& request.action() != DemoAction.REMOVE) throw new IllegalStateException("演示区域暂不可用，请先恢复或重置");
				reconcileRemovedTank(player, session);
				removedElsewhere = true;
			}
			if (current != null) {
				if (!current.dimension().equals(player.level().dimension()) || !near(player, current))
					throw new IllegalStateException("请回到演示附近");
				regions.select(current.id());
			}
			switch (request.action()) {
				case START, RESET -> {
					if (request.action() == DemoAction.RESET || current == null || !session.recipeReady || session.preset != request.preset())
						prepare(player, session, request.preset());
					if (!actions.setPaused(request.action() == DemoAction.RESET, player.level()))
						throw new IllegalStateException("演示暂时不可用，请刷新或重置");
				}
				case PAUSE, STEP, REMOVE -> {
					if (removedElsewhere && request.action() == DemoAction.REMOVE) { session.anchor = null; break; }
					if (current == null || request.preset() != session.preset) throw new IllegalStateException("请先开始此演示");
					if (request.action() == DemoAction.PAUSE) actions.setPaused(true, player.level());
					else if (request.action() == DemoAction.STEP) {
						if (!current.paused()) throw new IllegalStateException("请先暂停");
						if (actions.step(player.level(), 1) != 1) throw new IllegalStateException("单步未完成，请检查区域状态");
					} else { remove(player, session); session.anchor = null; }
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
		return response(player, session, request.generation(), failure);
	}
	private void prepare(ServerPlayer player, Session session, DemoPreset preset) {
		if (session.anchor == null) { session.anchor = player.blockPosition().immutable(); session.facing = player.getDirection(); }
		FluidRegion old = current(session);
		if (!preset.tank() && regions.regions().stream().anyMatch(region -> !region.controllerManaged()
				&& !region.id().equals(session.region)))
			throw new IllegalStateException("已有临时场景，请先移除它");
		if (old != null && (old.controllerManaged() != preset.tank())) remove(player, session);
		session.recipeReady = false;
		if (preset.tank()) {
			if (current(session) == null) {
				BlockPos controller = fixtures.createControllerDemo(player, session.anchor, session.facing);
				if (controller == null) throw new IllegalStateException(fixtures.lastFailure());
				session.region = regions.selected().id();
				session.tankLayout = ControllerDemoLayout.at(session.anchor, session.facing);
				tanks.register(regions.selected());
				session.preset = DemoPreset.WATER;
				try { persist(); } catch (java.io.IOException error) { throw new IllegalStateException("无法记录演示水箱身份", error); }
			}
			DemoTankOperations.Recipe recipe = switch (preset) {
				case WATER -> DemoTankOperations.Recipe.WATER;
				case MATERIAL -> DemoTankOperations.Recipe.OIL_WATER;
				case CHEMISTRY -> DemoTankOperations.Recipe.ACID_BASE;
				default -> throw new IllegalStateException("Unknown tank recipe");
			};
			try { persist(); } catch (java.io.IOException error) { throw new IllegalStateException("无法记录演示配方准备状态", error); }
			String failure = tanks.apply(player.level(), current(session), recipe);
			if (failure != null) throw new IllegalStateException(failure);
			giveIfAbsent(player, Items.WATER_BUCKET); giveIfAbsent(player, Items.BUCKET);
			giveIfAbsent(player, MmmItems.MATERIAL_BUCKET); giveIfAbsent(player, MmmItems.FIELD_PROBE);
		} else {
			boolean flow = preset == DemoPreset.FLOW;
			BlockPos center = session.anchor.relative(session.facing, 9);
			BlockPos origin = center.offset(-(flow ? DemoSceneLayout.FLOW_SIZE_X : MinecraftDebugFixtures.BLOCK_SIZE_X) / 2,
					0, -(flow ? DemoSceneLayout.FLOW_SIZE_Z : MinecraftDebugFixtures.BLOCK_SIZE_Z) / 2);
			boolean created = flow ? fixtures.createFlowDemo(player, origin) : fixtures.createAirDemo(player, origin,
					switch (preset) {
						case AMBIENT -> DebugScenarioInitialization.AirPressure.AMBIENT;
						case PRESSURE -> DebugScenarioInitialization.AirPressure.PRESSURE;
						case VACUUM -> DebugScenarioInitialization.AirPressure.VACUUM;
						default -> throw new IllegalStateException("Unknown pressure preset");
					});
			if (!created) throw new IllegalStateException(fixtures.lastFailure());
			session.region = regions.selected().id();
		}
		session.preset = preset;
		session.recipeReady = true;
	}
	private void remove(ServerPlayer player, Session session) {
		FluidRegion region = current(session);
		if (region == null) return;
		regions.select(region.id());
		if (region.controllerManaged()) {
			String failure = tanks.remove(player, region);
			if (failure != null) throw new IllegalStateException(failure);
			if (session.tankLayout != null) clearUnchangedShell(player, session.tankLayout);
			session.tankLayout = null;
		} else {
			var removed = actions.remove(player.level().getServer());
			if (!removed.completed()) throw new IllegalStateException(removed.failure());
		}
		session.region = null;
	}
	private Session session(ServerPlayer player) {
		if (!player.level().getServer().isSameThread()) throw new IllegalStateException("Demo actions require server thread");
		Session session = sessions.computeIfAbsent(new Viewer(player.getUUID(), player.level().dimension().identifier().toString()), key -> new Session());
		FluidRegion region = current(session);
		if (region != null && session.tankLayout != null) {
			if (!matchesTank(region, session.tankLayout, player.level().dimension().identifier().toString()))
				throw new IllegalStateException("演示水箱记录与区域布局不同");
			tanks.register(region);
		}
		return session;
	}
	private void reconcileRemovedTank(ServerPlayer player, Session session) {
		var position = session.tankLayout.controller();
		if (player.distanceToSqr(position.getX() + .5, position.getY() + .5, position.getZ() + .5) > 64)
			throw new IllegalStateException("请回到演示控制器附近");
		try { operations.readRemoved(session.region); }
		catch (java.io.IOException failure) { throw new IllegalStateException("演示区域尚未恢复或删除状态无法确认", failure); }
		clearUnchangedShell(player, session.tankLayout);
		session.region = null; session.tankLayout = null; session.recipeReady = false;
		try { persist(); }
		catch (java.io.IOException failure) { throw new IllegalStateException("无法保存演示移除状态", failure); }
	}

	private static void clearUnchangedShell(ServerPlayer player, ControllerDemoLayout layout) {
		var level = player.level();
		for (BlockPos position : layout.shell()) if (!level.hasChunkAt(position))
			throw new IllegalStateException("演示壳体所在区块尚未加载");
		for (BlockPos position : layout.shell()) {
			var expected = position.equals(layout.controller()) ? MmmBlocks.FLUID_CONTROLLER.defaultBlockState()
					: position.equals(layout.port()) ? MmmBlocks.FLUID_PORT.defaultBlockState()
							.setValue(FluidPortBlock.FACING, layout.portOutward()) : MmmBlocks.FLUID_BOUNDARY.defaultBlockState();
			if (level.getBlockState(position).equals(expected)
					&& !level.setBlock(position, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL))
				throw new IllegalStateException("无法清理演示壳体 " + position.toShortString());
		}
	}

	static boolean matchesTank(FluidRegion region, ControllerDemoLayout layout, String dimensionId) {
		if (!region.controllerManaged() || !region.dimension().identifier().toString().equals(dimensionId)) return false;
		var footprint = region.footprint();
		if (!footprint.controllerPosition().equals(layout.controller())
				|| footprint.kind() != mmm.model.region.RegionGeometry.RegionKind.CLOSED
				|| !footprint.returnedBlocks().isEmpty() || !footprint.atmosphereBlocks().isEmpty()) return false;
		var shell = new java.util.HashSet<Long>();
		for (var block : layout.shell()) shell.add(block.asLong());
		var interior = new java.util.HashSet<Long>();
		for (var block : layout.occupied()) if (!shell.contains(block.asLong())) interior.add(block.asLong());
		var boundary = new java.util.HashSet<Long>();
		for (long packed : interior) for (Direction direction : Direction.values()) {
			long neighbor = BlockPos.of(packed).relative(direction).asLong();
			if (shell.contains(neighbor)) boundary.add(neighbor);
		}
		return footprint.minimumInterior().equals(layout.minimum().offset(1, 1, 1))
				&& footprint.maximumInterior().equals(layout.minimum().offset(2, 2, 2))
				&& footprint.interiorBlocks().equals(interior) && footprint.boundaryBlocks().equals(boundary);
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
	private DemoWorkbenchPayload response(ServerPlayer player, Session session, long request, String failure) {
		FluidRegion region = current(session);
		String blocked = region == null ? (session.region == null ? null : "演示区域暂不可用，请先恢复或重置")
				: region.availabilityFailure();
		if (region != null && !region.solverLoaded() && blocked == null) blocked = "演示区域尚未加载";
		if (blocked != null) failure = failure == null ? blocked : failure + "; " + blocked;
		return new DemoWorkbenchPayload(session.id, request, tokens.issue(player.getUUID(), session.id),
				player.level().dimension().identifier().toString(), session.preset, session.region,
				region != null && region.paused(), failure, false, blocked != null);
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
