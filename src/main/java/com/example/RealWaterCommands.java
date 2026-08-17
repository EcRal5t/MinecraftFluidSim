package com.example;

import com.example.simulation.FluidSimulationService;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class RealWaterCommands {
	private RealWaterCommands() { }

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> dispatcher.register(
				Commands.literal("realwater")
						.then(Commands.literal("demo").executes(context -> createDemo(context.getSource())))
						.then(Commands.literal("capture").executes(context -> capture(context.getSource())))
						.then(Commands.literal("pour")
								.executes(context -> editWater(context.getSource(), 1, true))
								.then(Commands.argument("buckets", IntegerArgumentType.integer(1, 16))
										.executes(context -> editWater(context.getSource(), IntegerArgumentType.getInteger(context, "buckets"), true))))
						.then(Commands.literal("drain")
								.executes(context -> editWater(context.getSource(), 1, false))
								.then(Commands.argument("buckets", IntegerArgumentType.integer(1, 16))
										.executes(context -> editWater(context.getSource(), IntegerArgumentType.getInteger(context, "buckets"), false))))
						.then(Commands.literal("refresh").executes(context -> refreshCollisions(context.getSource())))
						.then(Commands.literal("entities")
								.executes(context -> entityPhysicsStatus(context.getSource()))
								.then(Commands.argument("enabled", BoolArgumentType.bool())
										.executes(context -> setEntityPhysics(context.getSource(), BoolArgumentType.getBool(context, "enabled")))))
						.then(Commands.literal("debug")
								.executes(context -> debugStatus(context.getSource()))
								.then(Commands.argument("enabled", BoolArgumentType.bool())
										.executes(context -> setDebug(context.getSource(), BoolArgumentType.getBool(context, "enabled")))))
						.then(Commands.literal("run").executes(context -> setPaused(context.getSource(), false)))
						.then(Commands.literal("pause").executes(context -> setPaused(context.getSource(), true)))
						.then(Commands.literal("step")
								.executes(context -> step(context.getSource(), 1))
								.then(Commands.argument("count", IntegerArgumentType.integer(1, 20))
										.executes(context -> step(context.getSource(), IntegerArgumentType.getInteger(context, "count")))))
						.then(Commands.literal("status").executes(context -> status(context.getSource())))
						.then(Commands.literal("remove").executes(context -> remove(context.getSource())))
		));
	}

	private static int createDemo(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
		service.createDemo(player);
		source.sendSuccess(() -> Component.literal("已创建暂停的 24×16×24 溃坝测试域；使用 /realwater step 或 /realwater run"), false);
		return 1;
	}

	private static int capture(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
		int cells = service.captureNearbyWater(player);
		source.sendSuccess(() -> Component.literal("已捕获附近水体：" + cells + " 个非空子格；测试域当前暂停"), false);
		return cells;
	}

	private static int setPaused(CommandSourceStack source, boolean paused) {
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
		if (!service.exists()) return fail(source, "请先使用 /realwater demo 或 /realwater capture");
		service.setPaused(paused);
		source.sendSuccess(() -> Component.literal(paused ? "流体模拟已暂停" : "流体模拟开始运行"), false);
		return 1;
	}

	private static int editWater(CommandSourceStack source, int buckets, boolean pour)
			throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
		FluidSimulationService.WaterEditResult result = pour
				? service.pourWater(player, buckets)
				: service.drainWater(player, buckets);
		if (!result.domainExists()) return fail(source, "请先创建流体域，并处于相同维度");
		if (result.target() == null) {
			return fail(source, pour ? "请把准星对准黄色模拟域内的水面、地面或障碍物" : "准星方向没有模拟水体");
		}
		String action = pour ? "注入" : "抽走";
		source.sendSuccess(() -> Component.literal(String.format(
				"已在子格 %d,%d,%d %s %.3f 方块水%s",
				result.target().x(), result.target().y(), result.target().z(), action, result.changedBlocks(),
				result.changedCellVolume() + 1.0E-4F < result.requestedCellVolume() ? "（附近容量不足）" : ""
		)), false);
		return result.changedCellVolume() > 0.0F ? 1 : 0;
	}

	private static int refreshCollisions(CommandSourceStack source) {
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
		if (!service.exists()) return fail(source, "请先使用 /realwater demo 或 /realwater capture");
		FluidSimulationService.CollisionStats stats = service.refreshCollisions(source.getLevel());
		service.sendSnapshot(source.getLevel());
		source.sendSuccess(() -> Component.literal(String.format(
				"碰撞已刷新：变化 %d 个子格，挤出 %.3f 方块水，域容量不足损失 %.3f 方块水",
				stats.changedCells(), stats.displacedCellVolume() / 8.0F, stats.escapedCellVolume() / 8.0F
		)), false);
		return 1;
	}

	private static int entityPhysicsStatus(CommandSourceStack source) {
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
		source.sendSuccess(() -> Component.literal("实体水动力：" + (service.entityPhysicsEnabled() ? "开启" : "关闭")), false);
		return service.entityPhysicsEnabled() ? 1 : 0;
	}

	private static int setEntityPhysics(CommandSourceStack source, boolean enabled) {
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
		service.setEntityPhysicsEnabled(enabled);
		source.sendSuccess(() -> Component.literal("实体水动力已" + (enabled ? "开启" : "关闭")), false);
		return 1;
	}

	private static int debugStatus(CommandSourceStack source) {
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
		source.sendSuccess(() -> Component.literal("碰撞与边界调试层：" + (service.debugOverlays() ? "显示" : "隐藏")), false);
		return service.debugOverlays() ? 1 : 0;
	}

	private static int setDebug(CommandSourceStack source, boolean enabled) {
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
		if (!service.exists()) return fail(source, "请先使用 /realwater demo 或 /realwater capture");
		service.setDebugOverlays(enabled, source.getLevel());
		source.sendSuccess(() -> Component.literal("碰撞与边界调试层已" + (enabled ? "显示" : "隐藏")), false);
		return 1;
	}

	private static int step(CommandSourceStack source, int count) {
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
		if (!service.exists()) return fail(source, "请先使用 /realwater demo 或 /realwater capture");
		int completed = service.step(source.getLevel(), count);
		if (completed == 0) return fail(source, "活动流体域位于其他维度");
		source.sendSuccess(() -> Component.literal("已执行 " + completed + " 个流体步；" + service.status()), false);
		return completed;
	}

	private static int status(CommandSourceStack source) {
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
		source.sendSuccess(() -> Component.literal(service.status()), false);
		return service.exists() ? 1 : 0;
	}

	private static int remove(CommandSourceStack source) {
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
		service.remove(source.getServer());
		source.sendSuccess(() -> Component.literal("已移除活动流体域和客户端调试快照"), false);
		return 1;
	}

	private static int fail(CommandSourceStack source, String message) {
		source.sendFailure(Component.literal(message));
		return 0;
	}
}
