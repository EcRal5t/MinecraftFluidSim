package mmm;

import mmm.logging.MmmCommandLog;
import mmm.fluid.FluidMaterial;
import mmm.transfer.ContainerFluidLedger;
import mmm.transfer.ChemicalContents;
import mmm.transfer.FluidMixture;
import mmm.transfer.GranularContents;
import mmm.item.MmmItems;
import mmm.item.ContainerItemStacks;
import mmm.chemistry.BuiltinChemicalDefinitions;
import mmm.chemistry.ChemicalLimits;
import mmm.chemistry.ChemicalMixtureRecord;
import mmm.chemistry.ChemicalRegistry;
import mmm.chemistry.ChemicalTransferLedger;
import mmm.granular.BuiltinGranularDefinitions;
import mmm.granular.GranularTransferLedger;
import mmm.granular.SolidVolume;
import mmm.simulation.CaptureResult;
import mmm.simulation.MinecraftRegionOperatorActions;
import mmm.application.ContainerTransferCoordinator;
import mmm.simulation.MmmRuntimeRegistry;
import mmm.simulation.FluidRegion;
import mmm.simulation.RestorationStats;
import mmm.simulation.SelectionResult;
import mmm.worldprofile.WorldCapability;
import mmm.worldprofile.WorldCapabilityProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** Audited /mmm command handlers; remaining service-backed operator writes are removed with S21. */
final class MmmCommandHandlers {
	private MmmCommandHandlers() { }

	static int openDemos(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		return mmm.network.FluidProtocolServer.send(player, MmmRuntimeRegistry.require(source.getServer()).demos().open(player))
				? 1 : fail(source, "客户端无法打开演示面板");
	}

	static int createDemo(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		var fixtures = MmmRuntimeRegistry.require(source.getServer()).debugFixtures();
		if (!fixtures.createDemo(player)) {
			return fail(source, "无法替换活动域：" + fixtures.lastFailure());
		}
		return succeed(source, "已创建暂停的 24×16×24 溃坝测试域；使用 /mmm step 或 /mmm run", 1);
	}

	static int createMaterialDemo(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		return launchDemo(source, mmm.network.DemoPreset.MATERIAL);
	}

	static int createChemistryDemo(CommandSourceStack source) throws CommandSyntaxException {
		return launchDemo(source, mmm.network.DemoPreset.CHEMISTRY);
	}

	static int createControllerDemo(CommandSourceStack source) throws CommandSyntaxException {
		return launchDemo(source, mmm.network.DemoPreset.WATER);
	}
	private static int launchDemo(CommandSourceStack source, mmm.network.DemoPreset preset) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		var response = MmmRuntimeRegistry.require(source.getServer()).demos().launch(player, preset);
		mmm.network.FluidProtocolServer.send(player, response);
		return response.failure() == null ? 1 : fail(source, response.failure());
	}

	/** Explicit debug/creative source for exercising material controller transfers and mixtures. */
	static int giveMaterialBucket(CommandSourceStack source, String requested) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		var world = MmmRuntimeRegistry.require(source.getServer()).worldRegistries();
		if (!hasCapability(source, WorldCapability.MATERIALS)) return fail(source, "材料功能门本次启动未开启");
		ItemStack stack;
		String description;
		if ("empty".equals(requested)) {
			stack = new ItemStack(MmmItems.MATERIAL_BUCKET);
			description = "空材料桶";
		} else {
			String name = switch (requested) {
				case "water", "mmm:water" -> "water";
				case "oil", "mmm:oil" -> "oil";
				case "mercury", "mmm:mercury" -> "mercury";
				case "water_oil_mix" -> "water_oil";
				default -> null;
			};
			if (name == null) return fail(source, "未知材料：" + requested);
			stack = world.creativeMaterial(name);
			description = name + " 满材料桶";
		}
		if (!player.getInventory().add(stack)) player.drop(stack, false);
		player.getInventory().setChanged();
		return succeed(source, "已生成 " + description + "（显式 creative/debug 外部源）", 1);
	}

	/** Explicit debug source for exercising authoritative granular controller transfers. */
	static int giveGranularContainer(CommandSourceStack source, String requested) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		var world = MmmRuntimeRegistry.require(source.getServer()).worldRegistries();
		if (!hasCapability(source, WorldCapability.GRANULAR)) return fail(source, "颗粒功能门本次启动未开启");
		ItemStack stack; String description;
		if ("empty".equals(requested)) {
			stack = new ItemStack(MmmItems.GRANULAR_CONTAINER); description = "空干颗粒容器";
		} else {
			String materialId = switch (requested) {
				case "fine_sand", "sand", BuiltinGranularDefinitions.FINE_SAND -> BuiltinGranularDefinitions.FINE_SAND;
				case "fine_silt", BuiltinGranularDefinitions.FINE_SILT -> BuiltinGranularDefinitions.FINE_SILT;
				case "light_beads", "beads", BuiltinGranularDefinitions.LIGHT_BEADS -> BuiltinGranularDefinitions.LIGHT_BEADS;
				default -> null;
			};
			if (materialId == null) return fail(source, "未知或不可直接生成的颗粒材料：" + requested);
			var registry = world.granularRegistry();
			var material = registry.material(registry.materialHandle(materialId));
			if (material.reactive()) return fail(source, "反应性固体只能由守恒相转移或已有容器产生");
			long svq = SolidVolume.SVQ_PER_CUBIC_METER;
			double mass = svq / (double) SolidVolume.SVQ_PER_CUBIC_METER * material.referenceDensity();
			GranularTransferLedger ledger = new GranularTransferLedger(List.of(
					new GranularTransferLedger.Group(material.id(), svq, mmm.units.Mass.fromKilograms(mass), null)));
			stack = ContainerItemStacks.toStack(GranularContents.fromLedger(ledger, registry, null));
			world.bindActualContainerProfile(stack);
			description = material.id() + " 满干颗粒容器";
		}
		if (!player.getInventory().add(stack)) player.drop(stack, false);
		player.getInventory().setChanged();
		return succeed(source, "已生成 " + description + "（显式 creative/debug 外部源）", 1);
	}

	/** Explicit administrator source for exact chemical fixtures. */
	static int giveReagent(CommandSourceStack source, String requested) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		var world = MmmRuntimeRegistry.require(source.getServer()).worldRegistries();
		if (!hasCapability(source, WorldCapability.CHEMISTRY)) return fail(source, "化学功能门本次启动未开启");
		ItemStack stack;

		String species = switch (requested) {
			case "water" -> BuiltinChemicalDefinitions.H2O;
			case "ethanol" -> BuiltinChemicalDefinitions.ETHANOL;
			case "hcl" -> BuiltinChemicalDefinitions.HCL;
			case "naoh" -> BuiltinChemicalDefinitions.NAOH;
			case "nacl" -> BuiltinChemicalDefinitions.NACL;
			case "nano3" -> BuiltinChemicalDefinitions.NANO3;
			case "kcl" -> BuiltinChemicalDefinitions.KCL;
			case "kno3" -> BuiltinChemicalDefinitions.KNO3;
			default -> null;
		};
		if (species == null) return fail(source, "未知 reagent：" + requested);
		stack = world.creativeReagent(requested);
		if (!player.getInventory().add(stack)) player.drop(stack, false);
		player.getInventory().setChanged();
		return succeed(source, "已生成 " + requested + "（显式 creative/debug 化学外部源）", 1);
	}

	static int createTensionDemo(CommandSourceStack source, int angleDegrees)
			throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		var fixtures = MmmRuntimeRegistry.require(source.getServer()).debugFixtures();
		if (!fixtures.createTensionDemo(player, angleDegrees)) {
			return fail(source, "无法创建表面张力测试域：" + fixtures.lastFailure());
		}
		return succeed(source, "已创建暂停的表面张力球形冠液滴（演示用世界材料表面张力、g=0.981 m/s²，θ=" + angleDegrees + "°；同体积球冠，瞬态，不写 region-state）；各角度从相同半球开始，用 /mmm step 或 /mmm run 比较接触面的变化", 1);
	}

	static int createAirDemo(CommandSourceStack source, String mode)
			throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		var preset = switch (mode) {
			case "ambient" -> mmm.network.DemoPreset.AMBIENT;
			case "pressure" -> mmm.network.DemoPreset.PRESSURE;
			case "vacuum" -> mmm.network.DemoPreset.VACUUM;
			default -> null;
		};
		return preset == null ? fail(source, "airdemo：ambient / pressure / vacuum") : launchDemo(source, preset);
	}

	static int capture(CommandSourceStack source, boolean takeover) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		CaptureResult result = MmmRuntimeRegistry.require(source.getServer()).debugFixtures()
				.captureNearbyWater(player, takeover);
		if (result.failure() != null) {
			return fail(source, (takeover ? "接管" : "捕获") + "已取消：" + result.failure());
		}
		if (takeover) {
			return succeed(source, String.format(
					"已接管附近水体：%d 个非空子格，暂时移除 %d 个原版水方块；请保留世界备份，在 /mmm remove 完成且 WARNING 消失前不要卸载或降级 Mod；测试域当前暂停",
					result.wetCells(), result.removedVanillaBlocks()
			), result.wetCells());
		} else {
			return succeed(source, "已只读捕获附近水体：" + result.wetCells() + " 个非空子格；测试域当前暂停", result.wetCells());
		}
	}

	static int setPaused(CommandSourceStack source, boolean paused) {
		var runtime = MmmRuntimeRegistry.require(source.getServer());
		MinecraftRegionOperatorActions actions = runtime.operatorActions();
		if (!actions.exists()) return fail(source, "请先使用 /mmm demo 或 /mmm capture");
		if (!actions.setPaused(paused, source.getLevel())) {
			return fail(source, "流体域当前不能运行；" + runtime.regionDiagnostics().status(source.getServer()));
		}
		return succeed(source, paused ? "流体模拟已暂停" : "流体模拟开始运行", 1);
	}

	static int editWater(CommandSourceStack source, int buckets, boolean pour)
			throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		MinecraftRegionOperatorActions actions = MmmRuntimeRegistry.require(source.getServer()).operatorActions();
		MinecraftRegionOperatorActions.WaterEditResult result = pour
				? actions.pourWater(player, buckets)
				: actions.drainWater(player, buckets);
		if (!result.domainExists()) return fail(source, "请先创建流体域，并处于相同维度");
		if (result.failure() != null) return fail(source, "控制器域当前不可修改：" + result.failure());
		if (result.target() == null) {
			return fail(source, pour ? "请把准星对准黄色模拟域内的水面、地面或障碍物" : "准星方向没有模拟水体");
		}
		String action = pour ? "注入" : "抽走";
		int commandResult = result.changedVolumeCubicMeters() > 0.0F ? 1 : 0;
		return succeed(source, String.format(
				"已在子格 %d,%d,%d %s %s 水%s",
				result.target().x(), result.target().y(), result.target().z(), action, mmm.units.UnitFormat.volume(result.changedVolumeCubicMeters()),
				result.changedVolumeCubicMeters() + 1.25E-5 < result.requestedVolumeCubicMeters()
						? (result.markerBudgetLimited() ? "（marker 预算已满）" : "（附近空间不足）")
						: ""
		), commandResult);
	}

	static int emptyRegion(CommandSourceStack source) {
		MinecraftRegionOperatorActions actions = MmmRuntimeRegistry.require(source.getServer()).operatorActions();
		String result = actions.emptySelectedRegion(source.getLevel());
		if (result.contains("失败") || result.startsWith("没有")) return fail(source, result);
		return succeed(source, result, 1);
	}

	static int refreshCollisions(CommandSourceStack source) {
		MinecraftRegionOperatorActions actions = MmmRuntimeRegistry.require(source.getServer()).operatorActions();
		if (!actions.exists()) return fail(source, "请先使用 /mmm demo 或 /mmm capture");
		var stats = actions.refreshCollisions(source.getLevel());
		return succeed(source, String.format(
				"碰撞已刷新：变化 %d 个子格，挤出 %s 水，未能重放 %s 水%s",
				stats.changedCells(), mmm.units.UnitFormat.volume(stats.displacedVolumeCubicMeters()),
				mmm.units.UnitFormat.volume(stats.escapedVolumeCubicMeters()),
				stats.markerBudgetLimited() ? "（marker 预算受限）" : ""
		), 1);
	}

	static int entityPhysicsStatus(CommandSourceStack source) {
		MinecraftRegionOperatorActions actions = MmmRuntimeRegistry.require(source.getServer()).operatorActions();
		int result = actions.entityPhysicsEnabled() ? 1 : 0;
		return succeed(source, "实体水动力：" + (actions.entityPhysicsEnabled() ? "开启" : "关闭"), result);
	}

	static int setEntityPhysics(CommandSourceStack source, boolean enabled) {
		MmmRuntimeRegistry.require(source.getServer()).operatorActions().setEntityPhysicsEnabled(enabled);
		return succeed(source, "实体水动力已" + (enabled ? "开启" : "关闭"), 1);
	}

	static int controllerFeatureStatus(CommandSourceStack source) {
		MinecraftRegionOperatorActions actions = MmmRuntimeRegistry.require(source.getServer()).operatorActions();
		return succeed(source, "控制器功能门：" + (actions.controllerFeatureEnabled() ? "开启" : "关闭"),
				actions.controllerFeatureEnabled() ? 1 : 0);
	}

	static int setControllerFeature(CommandSourceStack source, boolean enabled) {
		String failure = MmmRuntimeRegistry.require(source.getServer()).operatorActions()
				.setControllerFeatureEnabled(enabled);
		if (failure != null) return fail(source, "控制器功能门未改变；配置原子发布失败：" + failure);
		return succeed(source, "控制器功能门已" + (enabled ? "开启" : "关闭；现有控制器域已暂停但未删除"), 1);
	}

	static int worldBridgeFeatureStatus(CommandSourceStack source) {
		MinecraftRegionOperatorActions actions = MmmRuntimeRegistry.require(source.getServer()).operatorActions();
		boolean live = actions.worldBridgeFeatureEnabled();
		boolean configured = actions.configuredWorldBridgeFeatureEnabled();
		String message = "世界桥功能门：本次启动" + (live ? "开启" : "关闭") + "，配置（下次重启生效）" + (configured ? "开启" : "关闭");
		return succeed(source, live == configured ? message : message + "；重启服务器后才会切换", live ? 1 : 0);
	}

	static int setWorldBridgeFeature(CommandSourceStack source, boolean enabled) {
		MinecraftRegionOperatorActions actions = MmmRuntimeRegistry.require(source.getServer()).operatorActions();
		String failure = actions.setWorldBridgeFeatureForRestart(enabled);
		if (failure != null) return fail(source, "世界桥功能门未改变；配置原子发布失败：" + failure);
		return succeed(source, "世界桥功能门配置已" + (enabled ? "开启" : "关闭")
				+ "；本次启动仍为" + (actions.worldBridgeFeatureEnabled() ? "开启" : "关闭")
				+ "，重启服务器后生效；开启后新激活的控制器区域将一次性移除内部原版水并建立可恢复 ownership", 1);
	}

	static int transferWalFaultStatus(CommandSourceStack source) {
		var runtime = MmmRuntimeRegistry.require(source.getServer());
		return succeed(source, "容器转移故障注入点：" + runtime.containerTransferFault(), 1);
	}

	static int setTransferWalFault(CommandSourceStack source, String pointName) {
		ContainerTransferCoordinator.FaultPoint point = switch (pointName) {
			case "none" -> ContainerTransferCoordinator.FaultPoint.NONE;
			case "beforePrepare" -> ContainerTransferCoordinator.FaultPoint.BEFORE_PREPARE;
			case "afterPrepare" -> ContainerTransferCoordinator.FaultPoint.AFTER_PREPARE;
			case "afterSolver" -> ContainerTransferCoordinator.FaultPoint.AFTER_SOLVER;
			case "afterStage" -> ContainerTransferCoordinator.FaultPoint.AFTER_STAGE;
			case "afterWal" -> ContainerTransferCoordinator.FaultPoint.AFTER_WAL;
			case "afterCheckpoint" -> ContainerTransferCoordinator.FaultPoint.AFTER_CHECKPOINT;
			default -> null;
		};
		if (point == null) return fail(source, "未知故障注入点：" + pointName);
		MmmRuntimeRegistry.require(source.getServer()).setContainerTransferFault(point);
		return succeed(source, "容器转移故障注入点已设为 " + point, 1);
	}

	static int ownershipReport(CommandSourceStack source) {
		return succeed(source, MmmRuntimeRegistry.require(source.getServer()).regionDiagnostics()
				.ownership(source.getServer()), 1);
	}

	/** Administrator-only exact chemical inventory/audit report. */
	static int chemistryInventoryStatus(CommandSourceStack source) {
		String inventory = MmmRuntimeRegistry.require(source.getServer()).regionDiagnostics()
				.chemistryInventory(source.getLevel());
		return succeed(source, inventory, inventory.startsWith("region ") ? 1 : 0);
	}

	/** Administrator-only exact granular inventory/audit report. */
	static int granularInventoryStatus(CommandSourceStack source) {
		String inventory = MmmRuntimeRegistry.require(source.getServer()).regionDiagnostics()
				.granularInventory(source.getLevel());
		return succeed(source, inventory, inventory.startsWith("region ") ? 1 : 0);
	}

	static int listRegions(CommandSourceStack source) {
		var runtime = MmmRuntimeRegistry.require(source.getServer());
		java.util.List<String> regions = runtime.regionAdministration().summaries();
		if (regions.isEmpty()) return succeed(source,
				"没有已登记区域；" + runtime.regionDiagnostics().status(source.getServer()), 0);
		return succeed(source, "区域列表（* 为当前选择）：\n" + String.join("\n", regions), regions.size());
	}

	static int selectRegion(CommandSourceStack source, String prefix) {
		SelectionResult result = MmmRuntimeRegistry.require(source.getServer()).regionAdministration().select(prefix);
		if (!result.succeeded()) return fail(source, result.failure());
		return succeed(source, "已选择区域 " + result.regionId() + "；区域保持原有暂停/运行状态", 1);
	}


	private static int processReply(CommandSourceStack source,String message) {
		return message.startsWith("已") || message.startsWith("持续投料已启动") || message.startsWith("工艺：") || message.startsWith("格 ")
				? succeed(source,message,1) : fail(source,message);
	}
	static int processStatus(CommandSourceStack source) throws CommandSyntaxException {
		return processReply(source, MmmRuntimeRegistry.require(source.getServer()).processes().status(source.getPlayerOrException()));
	}
	static int processDrive(CommandSourceStack source,int seconds) throws CommandSyntaxException {
		return processReply(source, MmmRuntimeRegistry.require(source.getServer()).processes().startDrive(source.getPlayerOrException(), seconds));
	}
	static int processStop(CommandSourceStack source) throws CommandSyntaxException {
		return processReply(source, MmmRuntimeRegistry.require(source.getServer()).processes().stop(source.getPlayerOrException()));
	}
	static int processSample(CommandSourceStack source) throws CommandSyntaxException {
		return processReply(source, MmmRuntimeRegistry.require(source.getServer()).processes().sample(source.getPlayerOrException()));
	}

	static int checkpointRegion(CommandSourceStack source) {
		String result = MmmRuntimeRegistry.require(source.getServer()).regionAdministration().checkpointSelected();
		if (result.contains("失败") || result.startsWith("没有")) return fail(source, result);
		return succeed(source, result, 1);
	}

	static int recoverPreviousRegion(CommandSourceStack source, long expectedCheckpoint) {
		String result = MmmRuntimeRegistry.require(source.getServer()).regionAdministration()
				.recoverPreviousSelected(expectedCheckpoint);
		if (result.contains("失败") || result.startsWith("没有") || result.startsWith("拒绝")) {
			return fail(source, result);
		}
		return succeed(source, result, 1);
	}

	static int debugStatus(CommandSourceStack source) {
		MinecraftRegionOperatorActions actions = MmmRuntimeRegistry.require(source.getServer()).operatorActions();
		int result = actions.debugOverlays() ? 1 : 0;
		return succeed(source, "碰撞与边界调试层：" + (actions.debugOverlays() ? "显示" : "隐藏"), result);
	}

	static int setDebug(CommandSourceStack source, boolean enabled) {
		MinecraftRegionOperatorActions actions = MmmRuntimeRegistry.require(source.getServer()).operatorActions();
		if (!actions.exists()) return fail(source, "请先使用 /mmm demo 或 /mmm capture");
		actions.setDebugOverlays(enabled, source.getLevel());
		return succeed(source, "碰撞与边界调试层已" + (enabled ? "显示" : "隐藏"), 1);
	}

	static int step(CommandSourceStack source, int count) {
		var runtime = MmmRuntimeRegistry.require(source.getServer());
		MinecraftRegionOperatorActions actions = runtime.operatorActions();
		if (!actions.exists()) return fail(source, "请先使用 /mmm demo 或 /mmm capture");
		int completed = actions.step(source.getLevel(), count);
		if (completed == 0) return fail(source, "活动流体域位于其他维度");
		if (completed < 0) return fail(source,
				"流体域当前不能手动步进；" + runtime.regionDiagnostics().status(source.getServer()));
		return succeed(source, "已执行 " + completed + " 个流体步；"
				+ runtime.regionDiagnostics().status(source.getServer()), completed);
	}

	static int status(CommandSourceStack source) {
		var runtime = MmmRuntimeRegistry.require(source.getServer());
		int result = runtime.regionObservation().exists() ? 1 : 0;
		return succeed(source, runtime.regionDiagnostics().status(source.getServer()), result);
	}

	static int logPath(CommandSourceStack source) {
		return succeed(source, "Matter, Motion & Mechanics 指令日志：" + MmmCommandLog.path(source), 1);
	}

	static int remove(CommandSourceStack source) {
		RestorationStats restoration = MmmRuntimeRegistry.require(source.getServer()).operatorActions()
				.remove(source.getServer());
		if (!restoration.completed()) {
			return fail(source, "移除已暂停：" + restoration.failure() + "；请保留 Mod 与世界备份并查看日志后重试");
		}
		String ownership = restoration.ownershipRestoredBlocks() == 0 && restoration.ownershipSkippedBlocks() == 0
				? ""
				: String.format("；ownership 释放恢复 %d 格原版水、跳过 %d 个玩家占用位置",
						restoration.ownershipRestoredBlocks(), restoration.ownershipSkippedBlocks());
		return succeed(source, String.format(
				"已移除活动流体域和客户端调试快照；恢复 %d 个原版水方块，确认 %d 个已恢复位置，跳过 %d 个已被玩家占用的位置；journal 与 WARNING 已删除%s",
				restoration.restoredBlocks(), restoration.alreadyRestoredBlocks(), restoration.skippedBlocks(), ownership
		), 1);
	}

	static int fail(CommandSourceStack source, String message) {
		MmmCommandLog.sendFailure(source, message);
		return 0;
	}

	static boolean hasCapability(CommandSourceStack source, WorldCapability capability) {
		WorldCapabilityProfile profile = MmmRuntimeRegistry.require(source.getServer()).worldProfile();
		return profile != null && profile.hasCapability(capability);
	}

	static int succeed(CommandSourceStack source, String message, int result) {
		MmmCommandLog.sendSuccess(source, message, false);
		return result;
	}
}
