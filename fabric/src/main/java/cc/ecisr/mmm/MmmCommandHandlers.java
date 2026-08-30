package cc.ecisr.mmm;

import cc.ecisr.mmm.logging.MmmCommandLog;
import cc.ecisr.mmm.fluid.BuiltinFluidMaterials;
import cc.ecisr.mmm.fluid.FluidMaterial;
import cc.ecisr.mmm.transfer.ContainerFluidLedger;
import cc.ecisr.mmm.transfer.ChemicalContents;
import cc.ecisr.mmm.transfer.FluidMixture;
import cc.ecisr.mmm.transfer.GranularContents;
import cc.ecisr.mmm.item.MmmItems;
import cc.ecisr.mmm.item.ContainerItemStacks;
import cc.ecisr.mmm.chemistry.BuiltinChemicalDefinitions;
import cc.ecisr.mmm.chemistry.ChemicalLimits;
import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.chemistry.ChemicalTransferLedger;
import cc.ecisr.mmm.granular.BuiltinGranularDefinitions;
import cc.ecisr.mmm.granular.GranularTransferLedger;
import cc.ecisr.mmm.granular.SolidVolume;
import cc.ecisr.mmm.simulation.CaptureResult;
import cc.ecisr.mmm.simulation.FluidSimulationService;
import cc.ecisr.mmm.application.ContainerTransferCoordinator;
import cc.ecisr.mmm.simulation.MmmRuntimeRegistry;
import cc.ecisr.mmm.simulation.FluidRegion;
import cc.ecisr.mmm.simulation.RestorationStats;
import cc.ecisr.mmm.simulation.SelectionResult;
import cc.ecisr.mmm.worldprofile.WorldCapability;
import cc.ecisr.mmm.worldprofile.WorldCapabilityProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/** Audited /mmm command handlers; remaining service-backed operator writes are removed with S21. */
final class MmmCommandHandlers {
	private MmmCommandHandlers() { }

	static int createDemo(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		var fixtures = MmmRuntimeRegistry.require(source.getServer()).debugFixtures();
		if (!fixtures.createDemo(player)) {
			return fail(source, "无法替换活动域：" + fixtures.lastFailure());
		}
		return succeed(source, "已创建暂停的 24×16×24 溃坝测试域；使用 /mmm step 或 /mmm run", 1);
	}

	static int createMaterialDemo(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		var fixtures = MmmRuntimeRegistry.require(source.getServer()).debugFixtures();
		if (!fixtures.createMaterialDemo(player)) {
			return fail(source, "无法创建多材料测试域：" + fixtures.lastFailure());
		}
		return succeed(source, "已创建暂停的上油下水溃坝测试域（瞬态，不写 region-state）；使用 /mmm step 或 /mmm run", 1);
	}

	static int createChemistryDemo(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		var fixtures = MmmRuntimeRegistry.require(source.getServer()).debugFixtures();
		if (!fixtures.createChemistryDemo(player)) {
			return fail(source, "无法创建酸碱化学测试域：" + fixtures.lastFailure());
		}
		return succeed(source, "已创建暂停的 HCl/NaOH 对半化学域；左半带 indicator，使用 /mmm step 或 /mmm run 观察混合中和", 1);
	}

	static int createControllerDemo(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		var fixtures = MmmRuntimeRegistry.require(source.getServer()).debugFixtures();
		net.minecraft.core.BlockPos controller = fixtures.createControllerDemo(player);
		if (controller == null) {
			return fail(source, "无法创建快速控制器域：" + fixtures.lastFailure());
		}
		return succeed(source, "已在 " + controller.toShortString()
				+ " 创建并激活内部 4×4×4 的空闭壳；区域当前暂停，可直接对壳面中心使用桶", 1);
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
		} else if ("water_oil_mix".equals(requested)) {
			stack = ContainerItemStacks.toStack(new FluidMixture(List.of(
					new FluidMixture.Component(BuiltinFluidMaterials.WATER.id(),
							ContainerFluidLedger.NANOS_PER_BLOCK / 2L,
							0.5 * BuiltinFluidMaterials.WATER.referenceDensity()),
					new FluidMixture.Component(BuiltinFluidMaterials.OIL.id(),
							ContainerFluidLedger.NANOS_PER_BLOCK / 2L,
							0.5 * BuiltinFluidMaterials.OIL.referenceDensity())
			)));
			description = "半水半油混合材料桶";
		} else {
			FluidMaterial material = switch (requested) {
				case "water", "mmm:water" -> BuiltinFluidMaterials.WATER;
				case "oil", "mmm:oil" -> BuiltinFluidMaterials.OIL;
				case "mercury", "mmm:mercury" -> BuiltinFluidMaterials.MERCURY;
				default -> null;
			};
			if (material == null) return fail(source, "未知材料：" + requested);
			stack = ContainerItemStacks.toStack(new FluidMixture(List.of(new FluidMixture.Component(material.id(),
					ContainerFluidLedger.NANOS_PER_BLOCK, material.referenceDensity()))));
			description = material.id() + " 满材料桶";
		}
		if (!"empty".equals(requested)) {
			world.bindActualContainerProfile(stack);
			world.attachDebugLiquidThermalContents(stack);
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
				case "light_beads", "beads", BuiltinGranularDefinitions.LIGHT_BEADS -> BuiltinGranularDefinitions.LIGHT_BEADS;
				default -> null;
			};
			if (materialId == null) return fail(source, "未知或不可直接生成的颗粒材料：" + requested);
			var registry = world.granularRegistry();
			var material = registry.material(registry.materialHandle(materialId));
			if (material.reactive()) return fail(source, "反应性固体只能由守恒相转移或已有容器产生");
			long svq = SolidVolume.SVQ_PER_BV;
			double mass = svq / (double) SolidVolume.SVQ_PER_BV * material.referenceDensity();
			GranularTransferLedger ledger = new GranularTransferLedger(List.of(
					new GranularTransferLedger.Group(material.id(), svq, mass, null)));
			stack = ContainerItemStacks.toStack(GranularContents.fromLedger(ledger, registry, null));
			world.bindActualContainerProfile(stack);
			description = material.id() + " 满干颗粒容器";
		}
		if (!player.getInventory().add(stack)) player.drop(stack, false);
		player.getInventory().setChanged();
		return succeed(source, "已生成 " + description + "（显式 creative/debug 外部源）", 1);
	}

	/** Explicit administrator source for exact chemical fixtures and indicator tools. */
	static int giveReagent(CommandSourceStack source, String requested) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		var world = MmmRuntimeRegistry.require(source.getServer()).worldRegistries();
		if (!hasCapability(source, WorldCapability.CHEMISTRY)) return fail(source, "化学功能门本次启动未开启");
		ItemStack stack;
		if ("indicator".equals(requested)) {
			stack = new ItemStack(MmmItems.CHEMICAL_INDICATOR);
		} else if ("eraser".equals(requested)) {
			stack = new ItemStack(MmmItems.CHEMICAL_ERASER);
		} else {
			String species = switch (requested) {
				case "water" -> BuiltinChemicalDefinitions.H2O;
				case "hcl" -> BuiltinChemicalDefinitions.HCL;
				case "naoh" -> BuiltinChemicalDefinitions.NAOH;
				case "nacl" -> BuiltinChemicalDefinitions.NACL;
				case "nano3" -> BuiltinChemicalDefinitions.NANO3;
				case "kcl" -> BuiltinChemicalDefinitions.KCL;
				case "kno3" -> BuiltinChemicalDefinitions.KNO3;
				default -> null;
			};
			if (species == null) return fail(source, "未知 reagent：" + requested);
			ChemicalRegistry registry = world.chemicalRegistry();
			java.util.TreeMap<String, Long> amounts = new java.util.TreeMap<>();
			if (species.equals(BuiltinChemicalDefinitions.H2O)) {
				amounts.put(species, 55_555_555_556L);
			} else {
				amounts.put(BuiltinChemicalDefinitions.H2O, 50_000_000_000L);
				amounts.put(species, ChemicalLimits.QUANTA_PER_AU);
			}
			double mass = 1_000.0;
			ChemicalMixtureRecord exact = ChemicalMixtureRecord.fromStableAmounts(registry, amounts, 0.0);
			ChemicalMixtureRecord mixture = ChemicalMixtureRecord.fromStableAmounts(registry, amounts,
					mass - exact.exactSpeciesMassKilograms(registry).toDouble());
			ChemicalTransferLedger ledger = new ChemicalTransferLedger(List.of(new ChemicalTransferLedger.Group(
					BuiltinFluidMaterials.WATER.id(), false, 1.0, mass,
					mixture.massResidualKilogramsBits(),
					mixture.stableAmounts(registry))));
			stack = ContainerItemStacks.toStack(
					ChemicalContents.fromLedger(ledger, registry, ContainerFluidLedger.NANOS_PER_BLOCK));
			world.bindActualContainerProfile(stack);
			world.attachDebugLiquidThermalContents(stack);
		}
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
		return succeed(source, "已创建暂停的表面张力球形冠液滴（σ=100，θ=" + angleDegrees + "°；同体积球冠，瞬态，不写 region-state）；先比较 θ=30° 与 θ=120° 的暂停形状，再用 /mmm step 或 /mmm run", 1);
	}

	static int createAirDemo(CommandSourceStack source, String mode)
			throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		boolean vacuum = "vacuum".equals(mode);
		if (!vacuum && !"pressure".equals(mode)) {
			return fail(source, "airdemo 模式必须是 pressure 或 vacuum");
		}
		ServerPlayer player = source.getPlayerOrException();
		var fixtures = MmmRuntimeRegistry.require(source.getServer()).debugFixtures();
		if (!fixtures.createAirDemo(player, vacuum)) {
			return fail(source, "无法创建气压测试域：" + fixtures.lastFailure());
		}
		return succeed(source, "已在准星方向搭好玻璃 U 型管并创建暂停的气压演示域（"
				+ (vacuum ? "封顶侧气腔预置负压，运行后封顶侧液面被吸升约 1.5 格"
						: "封顶侧气腔预置正压，运行后封顶侧液面被压低约 2 格")
				+ "；玻璃只替换空气，remove 时恢复；瞬态不写 region-state）；"
				+ "用 /mmm run 观察，/mmm status 查看气压，/mmm debug true 以灰色显示密闭气体", 1);
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
		FluidSimulationService service = runtime.simulation();
		if (!service.exists()) return fail(source, "请先使用 /mmm demo 或 /mmm capture");
		if (!service.setPaused(paused, source.getLevel())) {
			return fail(source, "流体域当前不能运行；" + runtime.regionDiagnostics().status(source.getServer()));
		}
		return succeed(source, paused ? "流体模拟已暂停" : "流体模拟开始运行", 1);
	}

	static int editWater(CommandSourceStack source, int buckets, boolean pour)
			throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		FluidSimulationService service = MmmRuntimeRegistry.require(source.getServer()).simulation();
		FluidSimulationService.WaterEditResult result = pour
				? service.pourWater(player, buckets)
				: service.drainWater(player, buckets);
		if (!result.domainExists()) return fail(source, "请先创建流体域，并处于相同维度");
		if (result.failure() != null) return fail(source, "控制器域当前不可修改：" + result.failure());
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

	static int emptyRegion(CommandSourceStack source) {
		FluidSimulationService service = MmmRuntimeRegistry.require(source.getServer()).simulation();
		String result = service.emptySelectedRegion(source.getLevel());
		if (result.contains("失败") || result.startsWith("没有")) return fail(source, result);
		return succeed(source, result, 1);
	}


	static int refreshCollisions(CommandSourceStack source) {
		FluidSimulationService service = MmmRuntimeRegistry.require(source.getServer()).simulation();
		if (!service.exists()) return fail(source, "请先使用 /mmm demo 或 /mmm capture");
		FluidRegion.CollisionStats stats = service.refreshCollisions(source.getLevel());
		service.sendSnapshot(source.getLevel());
		return succeed(source, String.format(
				"碰撞已刷新：变化 %d 个子格，挤出 %.3f 方块水，未能重放 %.3f 方块水%s",
				stats.changedCells(), stats.displacedCellVolume() / 8.0F, stats.escapedCellVolume() / 8.0F,
				stats.markerBudgetLimited() ? "（marker 预算受限）" : ""
		), 1);
	}

	static int entityPhysicsStatus(CommandSourceStack source) {
		FluidSimulationService service = MmmRuntimeRegistry.require(source.getServer()).simulation();
		int result = service.entityPhysicsEnabled() ? 1 : 0;
		return succeed(source, "实体水动力：" + (service.entityPhysicsEnabled() ? "开启" : "关闭"), result);
	}

	static int setEntityPhysics(CommandSourceStack source, boolean enabled) {
		FluidSimulationService service = MmmRuntimeRegistry.require(source.getServer()).simulation();
		service.setEntityPhysicsEnabled(enabled);
		return succeed(source, "实体水动力已" + (enabled ? "开启" : "关闭"), 1);
	}

	static int controllerFeatureStatus(CommandSourceStack source) {
		FluidSimulationService service = MmmRuntimeRegistry.require(source.getServer()).simulation();
		return succeed(source, "控制器功能门：" + (service.controllerFeatureEnabled() ? "开启" : "关闭"),
				service.controllerFeatureEnabled() ? 1 : 0);
	}

	static int setControllerFeature(CommandSourceStack source, boolean enabled) {
		FluidSimulationService service = MmmRuntimeRegistry.require(source.getServer()).simulation();
		String failure = service.setControllerFeatureEnabled(enabled);
		if (failure != null) return fail(source, "控制器功能门未改变；配置原子发布失败：" + failure);
		return succeed(source, "控制器功能门已" + (enabled ? "开启" : "关闭；现有控制器域已暂停但未删除"), 1);
	}

	static int worldBridgeFeatureStatus(CommandSourceStack source) {
		FluidSimulationService service = MmmRuntimeRegistry.require(source.getServer()).simulation();
		boolean live = service.worldBridgeFeatureEnabled();
		boolean configured = service.configuredWorldBridgeFeatureEnabled();
		String message = "世界桥功能门：本次启动" + (live ? "开启" : "关闭") + "，配置（下次重启生效）" + (configured ? "开启" : "关闭");
		return succeed(source, live == configured ? message : message + "；重启服务器后才会切换", live ? 1 : 0);
	}

	static int setWorldBridgeFeature(CommandSourceStack source, boolean enabled) {
		FluidSimulationService service = MmmRuntimeRegistry.require(source.getServer()).simulation();
		String failure = service.setWorldBridgeFeatureForRestart(enabled);
		if (failure != null) return fail(source, "世界桥功能门未改变；配置原子发布失败：" + failure);
		return succeed(source, "世界桥功能门配置已" + (enabled ? "开启" : "关闭")
				+ "；本次启动仍为" + (service.worldBridgeFeatureEnabled() ? "开启" : "关闭")
				+ "，重启服务器后生效；开启后新激活的控制器区域将一次性移除内部原版水并建立可恢复 ownership", 1);
	}


	static int transferWalFaultStatus(CommandSourceStack source) {
		FluidSimulationService service = MmmRuntimeRegistry.require(source.getServer()).simulation();
		return succeed(source, "容器转移故障注入点：" + service.containerTransferFault(), 1);
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
		FluidSimulationService service = MmmRuntimeRegistry.require(source.getServer()).simulation();
		service.setContainerTransferFault(point);
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
		FluidSimulationService service = MmmRuntimeRegistry.require(source.getServer()).simulation();
		int result = service.debugOverlays() ? 1 : 0;
		return succeed(source, "碰撞与边界调试层：" + (service.debugOverlays() ? "显示" : "隐藏"), result);
	}

	static int setDebug(CommandSourceStack source, boolean enabled) {
		FluidSimulationService service = MmmRuntimeRegistry.require(source.getServer()).simulation();
		if (!service.exists()) return fail(source, "请先使用 /mmm demo 或 /mmm capture");
		service.setDebugOverlays(enabled, source.getLevel());
		return succeed(source, "碰撞与边界调试层已" + (enabled ? "显示" : "隐藏"), 1);
	}

	static int step(CommandSourceStack source, int count) {
		var runtime = MmmRuntimeRegistry.require(source.getServer());
		FluidSimulationService service = runtime.simulation();
		if (!service.exists()) return fail(source, "请先使用 /mmm demo 或 /mmm capture");
		int completed = service.step(source.getLevel(), count);
		if (completed == 0) return fail(source, "活动流体域位于其他维度");
		if (completed < 0) return fail(source,
				"控制器域未完整加载或边界已打开；" + runtime.regionDiagnostics().status(source.getServer()));
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
		FluidSimulationService service = MmmRuntimeRegistry.require(source.getServer()).simulation();
		RestorationStats restoration = service.remove(source.getServer());
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

