package com.example.gpuprobe;

import com.example.logging.RealWaterCommandLog;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/** Optional M1 probe. It owns no world blocks, chunk tickets, or save files. */
public final class RealWaterGpuProbeMod implements ModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("realwater_gpu_probe");
	private static volatile VulkanCapabilityProbe.ProbeReport lastReport;

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> dispatcher.register(
				Commands.literal("realwatergpu")
						.then(probeCommand())
						.then(Commands.literal("demo")
								.executes(context -> audit(context, () -> createDemo(context.getSource(), 0)))
								.then(Commands.argument("device", IntegerArgumentType.integer(0, 15))
										.executes(context -> audit(context, () -> createDemo(context.getSource(), IntegerArgumentType.getInteger(context, "device"))))))
						.then(Commands.literal("worlddemo")
								.executes(context -> audit(context, () -> createWorldDemo(context.getSource(), 0)))
								.then(Commands.argument("device", IntegerArgumentType.integer(0, 15))
										.executes(context -> audit(context, () -> createWorldDemo(context.getSource(), IntegerArgumentType.getInteger(context, "device"))))))
						.then(Commands.literal("run").executes(context -> audit(context, () -> setPaused(context.getSource(), false))))
						.then(Commands.literal("pause").executes(context -> audit(context, () -> setPaused(context.getSource(), true))))
						.then(Commands.literal("step")
								.executes(context -> audit(context, () -> step(context.getSource(), 1)))
								.then(Commands.argument("count", IntegerArgumentType.integer(1, 20))
										.executes(context -> audit(context, () -> step(context.getSource(), IntegerArgumentType.getInteger(context, "count"))))))
						.then(Commands.literal("benchmark")
								.executes(context -> audit(context, () -> benchmark(context.getSource(), 12, 0, VulkanFlipPipeline.PressureSolver.JACOBI)))
								.then(Commands.argument("blocks", IntegerArgumentType.integer(12, 32))
										.executes(context -> audit(context, () -> benchmark(context.getSource(), IntegerArgumentType.getInteger(context, "blocks"), 0, VulkanFlipPipeline.PressureSolver.JACOBI)))
										.then(Commands.argument("device", IntegerArgumentType.integer(0, 15))
												.executes(context -> audit(context, () -> benchmark(context.getSource(), IntegerArgumentType.getInteger(context, "blocks"), IntegerArgumentType.getInteger(context, "device"), VulkanFlipPipeline.PressureSolver.JACOBI)))
												.then(Commands.argument("solver", StringArgumentType.word())
														.executes(context -> audit(context, () -> benchmark(context.getSource(), IntegerArgumentType.getInteger(context, "blocks"), IntegerArgumentType.getInteger(context, "device"), StringArgumentType.getString(context, "solver"))))))))
						.then(Commands.literal("status").executes(context -> audit(context, () -> status(context.getSource()))))
						.then(Commands.literal("remove").executes(context -> audit(context, () -> remove(context.getSource()))))
		));
		ServerTickEvents.END_SERVER_TICK.register(server -> GpuProbeService.get(server).tick(server));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			GpuProbeService.removeServer(server);
			lastReport = null;
		});
		LOGGER.info("Real Water optional GPU probe initialized; Vulkan remains lazy and CPU Real Water remains authoritative");
	}

	static LiteralArgumentBuilder<CommandSourceStack> probeCommand() {
		return LiteralArgumentBuilder.<CommandSourceStack>literal("probe")
				.executes(context -> audit(context, () -> runProbe(context.getSource(), 0)))
				.then(RequiredArgumentBuilder.<CommandSourceStack, Integer>argument("device", IntegerArgumentType.integer(0, 15))
						.executes(context -> audit(context, () -> runProbe(context.getSource(), IntegerArgumentType.getInteger(context, "device")))));
	}

	private static int audit(CommandContext<CommandSourceStack> context, RealWaterCommandLog.Action action) throws CommandSyntaxException {
		return RealWaterCommandLog.run(context, action);
	}

	private static int createDemo(net.minecraft.commands.CommandSourceStack source, int deviceIndex)
			throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		GpuProbeService.CreationResult result = GpuProbeService.get(source.getServer()).createDemo(player, deviceIndex);
		if (!result.created()) return fail(source, result.detail());
		String backend = result.gpu() ? "Vulkan GPU（device-local + staging）" : "CPU fallback（" + result.detail() + "）";
		return succeed(source, "已创建暂停的独立 24×16×24 溃坝 probe；backend " + backend
				+ "；不读取碰撞、不修改方块或存档；使用 /realwatergpu step 或 /realwatergpu run", 1);
	}

	private static int createWorldDemo(net.minecraft.commands.CommandSourceStack source, int deviceIndex)
			throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		GpuProbeService.CreationResult result = GpuProbeService.get(source.getServer()).createWorldDemo(player, deviceIndex);
		if (!result.created()) return fail(source, result.detail());
		String backend = result.gpu() ? "Vulkan GPU（device-local + staging + MG2）" : "CPU fallback（" + result.detail() + "）";
		return succeed(source, "已创建暂停的 24×16×24 只读世界碰撞 probe；backend " + backend
				+ "；仅捕获一次已加载区块碰撞并冻结使用，不申请区块票、不修改方块或存档；"
				+ GpuProbeService.get(source.getServer()).status(source.getServer()), 1);
	}

	private static int runProbe(net.minecraft.commands.CommandSourceStack source, int deviceIndex) {
		try {
			lastReport = VulkanCapabilityProbe.run(deviceIndex);
			String summary = lastReport.oneLine();
			RealWaterCommandLog.sendSuccess(source, summary, false);
			RealWaterCommandLog.detail(lastReport.format());
			LOGGER.info("{}", lastReport.format());
			return lastReport.success() ? 1 : 0;
		} catch (RuntimeException | LinkageError exception) {
			lastReport = VulkanCapabilityProbe.ProbeReport.failure(exception);
			LOGGER.error("Windowless Vulkan GPU probe failed; CPU fallback remains active", exception);
			return fail(source, "GPU probe 失败，主 Mod 继续使用 CPU：" + exception.getMessage());
		}
	}

	private static int status(net.minecraft.commands.CommandSourceStack source) {
		GpuProbeService service = GpuProbeService.get(source.getServer());
		if (service.exists()) {
			return succeed(source, service.status(source.getServer()), 1);
		}
		VulkanCapabilityProbe.ProbeReport report = lastReport;
		String message = report == null ? "GPU probe 尚未运行；主 Real Water 始终保留 CPU 后端" : report.oneLine();
		return succeed(source, message, report != null && report.success() ? 1 : 0);
	}

	private static int setPaused(net.minecraft.commands.CommandSourceStack source, boolean paused) {
		GpuProbeService service = GpuProbeService.get(source.getServer());
		if (!service.exists()) return fail(source, "请先使用 /realwatergpu demo 或 /realwatergpu worlddemo");
		service.setPaused(paused);
		return succeed(source, paused ? "GPU probe 已暂停" : "GPU probe 开始运行", 1);
	}

	private static int step(net.minecraft.commands.CommandSourceStack source, int count) {
		GpuProbeService service = GpuProbeService.get(source.getServer());
		if (!service.exists()) return fail(source, "请先使用 /realwatergpu demo 或 /realwatergpu worlddemo");
		int completed = service.step(source.getLevel(), count);
		if (completed == 0) return fail(source, "probe 步进失败、位于其他维度，或主 Real Water 域正在运行；" + service.status(source.getServer()));
		return succeed(source, "已执行 " + completed + " 个 probe 步；" + service.status(source.getServer()), completed);
	}

	private static int benchmark(net.minecraft.commands.CommandSourceStack source, int blocks, int device, String solverName) {
		VulkanFlipPipeline.PressureSolver solver = switch (solverName.toLowerCase(Locale.ROOT)) {
			case "jacobi" -> VulkanFlipPipeline.PressureSolver.JACOBI;
			case "rbgs", "sor", "rbgs_sor" -> VulkanFlipPipeline.PressureSolver.RBGS_SOR;
			case "pcg" -> VulkanFlipPipeline.PressureSolver.PCG;
			case "pcgdiag", "pcg_diagonal", "diagonal" -> VulkanFlipPipeline.PressureSolver.PCG_DIAGONAL;
			case "mg", "mg2", "multigrid" -> VulkanFlipPipeline.PressureSolver.MULTIGRID_TWO_LEVEL;
			case "mgh", "mgfull", "hierarchy" -> VulkanFlipPipeline.PressureSolver.MULTIGRID_HIERARCHY;
			default -> null;
		};
		if (solver == null) return fail(source, "pressure solver 只支持 jacobi、rbgs、pcg、pcgdiag、mg2 或 mgh");
		return benchmark(source, blocks, device, solver);
	}

	private static int benchmark(net.minecraft.commands.CommandSourceStack source, int blocks, int device,
	                             VulkanFlipPipeline.PressureSolver solver) {
		if (blocks != 12 && blocks != 32) return fail(source, "benchmark 方块边长只支持 12 或 32");
		RealWaterCommandLog.sendSuccess(source, "开始无窗口 GPU benchmark（pressure " + solver.label()
				+ "）；32 方块档会让服务器主线程停顿数秒，但不访问世界或存档", false);
		try {
			String report = GpuProbeService.benchmark(device, blocks, solver);
			RealWaterCommandLog.sendSuccess(source, report, false);
			LOGGER.info("GPU benchmark: {}", report);
			return 1;
		} catch (RuntimeException | LinkageError exception) {
			LOGGER.error("GPU benchmark failed", exception);
			return fail(source, "GPU benchmark 失败，主 Mod 继续使用 CPU：" + exception.getMessage());
		}
	}

	private static int remove(net.minecraft.commands.CommandSourceStack source) {
		GpuProbeService service = GpuProbeService.get(source.getServer());
		service.remove(source.getServer());
		return succeed(source, "已释放独立 GPU probe 内存和可视化快照；世界与存档没有被修改", 1);
	}

	private static int fail(net.minecraft.commands.CommandSourceStack source, String message) {
		RealWaterCommandLog.sendFailure(source, message);
		return 0;
	}

	private static int succeed(net.minecraft.commands.CommandSourceStack source, String message, int result) {
		RealWaterCommandLog.sendSuccess(source, message, false);
		return result;
	}
}
