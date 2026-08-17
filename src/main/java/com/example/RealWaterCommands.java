package com.example;

import com.example.simulation.FluidSimulationService;
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
