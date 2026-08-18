package com.example;

import com.example.logging.RealWaterCommandLog;
import com.example.simulation.FluidSimulationService;
import com.example.simulation.FluidRegion;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

public final class RealWaterCommands {
	private RealWaterCommands() { }

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> dispatcher.register(
				Commands.literal("realwater")
						.then(Commands.literal("demo").executes(context -> audit(context, () -> createDemo(context.getSource()))))
						.then(Commands.literal("capture").executes(context -> audit(context, () -> capture(context.getSource(), false))))
						.then(Commands.literal("takeover").executes(context -> audit(context, () -> capture(context.getSource(), true))))
						.then(Commands.literal("pour")
								.executes(context -> audit(context, () -> editWater(context.getSource(), 1, true)))
								.then(Commands.argument("buckets", IntegerArgumentType.integer(1, 16))
										.executes(context -> audit(context, () -> editWater(context.getSource(), IntegerArgumentType.getInteger(context, "buckets"), true)))))
						.then(Commands.literal("drain")
								.executes(context -> audit(context, () -> editWater(context.getSource(), 1, false)))
								.then(Commands.argument("buckets", IntegerArgumentType.integer(1, 16))
										.executes(context -> audit(context, () -> editWater(context.getSource(), IntegerArgumentType.getInteger(context, "buckets"), false)))))
						.then(Commands.literal("refresh").executes(context -> audit(context, () -> refreshCollisions(context.getSource()))))
						.then(Commands.literal("entities")
								.executes(context -> audit(context, () -> entityPhysicsStatus(context.getSource())))
								.then(Commands.argument("enabled", BoolArgumentType.bool())
										.executes(context -> audit(context, () -> setEntityPhysics(context.getSource(), BoolArgumentType.getBool(context, "enabled"))))))
						.then(Commands.literal("debug")
								.executes(context -> audit(context, () -> debugStatus(context.getSource())))
								.then(Commands.argument("enabled", BoolArgumentType.bool())
										.executes(context -> audit(context, () -> setDebug(context.getSource(), BoolArgumentType.getBool(context, "enabled"))))))
						.then(Commands.literal("run").executes(context -> audit(context, () -> setPaused(context.getSource(), false))))
						.then(Commands.literal("pause").executes(context -> audit(context, () -> setPaused(context.getSource(), true))))
						.then(Commands.literal("step")
								.executes(context -> audit(context, () -> step(context.getSource(), 1)))
								.then(Commands.argument("count", IntegerArgumentType.integer(1, 20))
										.executes(context -> audit(context, () -> step(context.getSource(), IntegerArgumentType.getInteger(context, "count"))))))
						.then(Commands.literal("status").executes(context -> audit(context, () -> status(context.getSource()))))
						.then(Commands.literal("logpath").executes(context -> audit(context, () -> logPath(context.getSource()))))
						.then(Commands.literal("remove").executes(context -> audit(context, () -> remove(context.getSource()))))
		));
	}

	private static int audit(CommandContext<CommandSourceStack> context, RealWaterCommandLog.Action action) throws CommandSyntaxException {
		return RealWaterCommandLog.run(context, action);
	}

	private static int createDemo(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
		if (!service.createDemo(player)) {
			return fail(source, "无法替换活动域：" + service.lastTakeoverFailure());
		}
		return succeed(source, "已创建暂停的 24×16×24 溃坝测试域；使用 /realwater step 或 /realwater run", 1);
	}

	private static int capture(CommandSourceStack source, boolean takeover) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
		FluidSimulationService.CaptureResult result = service.captureNearbyWater(player, takeover);
		if (result.failure() != null) {
			return fail(source, (takeover ? "接管" : "捕获") + "已取消：" + result.failure());
		}
		if (takeover) {
			return succeed(source, String.format(
					"已接管附近水体：%d 个非空子格，暂时移除 %d 个原版水方块；请保留世界备份，在 /realwater remove 完成且 WARNING 消失前不要卸载或降级 Mod；测试域当前暂停",
					result.wetCells(), result.removedVanillaBlocks()
			), result.wetCells());
		} else {
			return succeed(source, "已只读捕获附近水体：" + result.wetCells() + " 个非空子格；测试域当前暂停", result.wetCells());
		}
	}

	private static int setPaused(CommandSourceStack source, boolean paused) {
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
		if (!service.exists()) return fail(source, "请先使用 /realwater demo 或 /realwater capture");
		service.setPaused(paused);
		return succeed(source, paused ? "流体模拟已暂停" : "流体模拟开始运行", 1);
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
		int commandResult = result.changedCellVolume() > 0.0F ? 1 : 0;
		return succeed(source, String.format(
				"已在子格 %d,%d,%d %s %.3f 方块水%s",
				result.target().x(), result.target().y(), result.target().z(), action, result.changedBlocks(),
				result.changedCellVolume() + 1.0E-4F < result.requestedCellVolume()
						? (result.markerBudgetLimited() ? "（marker 预算已满）" : "（附近空间不足）")
						: ""
		), commandResult);
	}

	private static int refreshCollisions(CommandSourceStack source) {
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
		if (!service.exists()) return fail(source, "请先使用 /realwater demo 或 /realwater capture");
		FluidRegion.CollisionStats stats = service.refreshCollisions(source.getLevel());
		service.sendSnapshot(source.getLevel());
		return succeed(source, String.format(
				"碰撞已刷新：变化 %d 个子格，挤出 %.3f 方块水，未能重放 %.3f 方块水%s",
				stats.changedCells(), stats.displacedCellVolume() / 8.0F, stats.escapedCellVolume() / 8.0F,
				stats.markerBudgetLimited() ? "（marker 预算受限）" : ""
		), 1);
	}

	private static int entityPhysicsStatus(CommandSourceStack source) {
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
		int result = service.entityPhysicsEnabled() ? 1 : 0;
		return succeed(source, "实体水动力：" + (service.entityPhysicsEnabled() ? "开启" : "关闭"), result);
	}

	private static int setEntityPhysics(CommandSourceStack source, boolean enabled) {
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
		service.setEntityPhysicsEnabled(enabled);
		return succeed(source, "实体水动力已" + (enabled ? "开启" : "关闭"), 1);
	}

	private static int debugStatus(CommandSourceStack source) {
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
		int result = service.debugOverlays() ? 1 : 0;
		return succeed(source, "碰撞与边界调试层：" + (service.debugOverlays() ? "显示" : "隐藏"), result);
	}

	private static int setDebug(CommandSourceStack source, boolean enabled) {
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
		if (!service.exists()) return fail(source, "请先使用 /realwater demo 或 /realwater capture");
		service.setDebugOverlays(enabled, source.getLevel());
		return succeed(source, "碰撞与边界调试层已" + (enabled ? "显示" : "隐藏"), 1);
	}

	private static int step(CommandSourceStack source, int count) {
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
		if (!service.exists()) return fail(source, "请先使用 /realwater demo 或 /realwater capture");
		int completed = service.step(source.getLevel(), count);
		if (completed == 0) return fail(source, "活动流体域位于其他维度");
		return succeed(source, "已执行 " + completed + " 个流体步；" + service.status(source.getServer()), completed);
	}

	private static int status(CommandSourceStack source) {
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
		int result = service.exists() ? 1 : 0;
		return succeed(source, service.status(source.getServer()), result);
	}

	private static int logPath(CommandSourceStack source) {
		return succeed(source, "Real Water 指令日志：" + RealWaterCommandLog.path(source), 1);
	}

	private static int remove(CommandSourceStack source) {
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
		FluidSimulationService.RestorationStats restoration = service.remove(source.getServer());
		if (!restoration.completed()) {
			return fail(source, "移除已暂停：" + restoration.failure() + "；请保留 Mod 与世界备份并查看日志后重试");
		}
		return succeed(source, String.format(
				"已移除活动流体域和客户端调试快照；恢复 %d 个原版水方块，确认 %d 个已恢复位置，跳过 %d 个已被玩家占用的位置；journal 与 WARNING 已删除",
				restoration.restoredBlocks(), restoration.alreadyRestoredBlocks(), restoration.skippedBlocks()
		), 1);
	}

	private static int fail(CommandSourceStack source, String message) {
		RealWaterCommandLog.sendFailure(source, message);
		return 0;
	}

	private static int succeed(CommandSourceStack source, String message, int result) {
		RealWaterCommandLog.sendSuccess(source, message, false);
		return result;
	}
}
