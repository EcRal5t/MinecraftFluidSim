package cc.ecisr.mmm;

import cc.ecisr.mmm.block.MmmBlocks;
import cc.ecisr.mmm.block.ThermalReservoirBlockEntity;
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
import cc.ecisr.mmm.simulation.FluidSimulationService;
import cc.ecisr.mmm.simulation.FluidRegion;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import cc.ecisr.mmm.worldprofile.WorldCapability;
import cc.ecisr.mmm.worldprofile.WorldCapabilityProfile;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.List;

public final class MmmCommands {
	private MmmCommands() { }

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> dispatcher.register(
				Commands.literal("mmm")
						.then(Commands.literal("demo").executes(context -> audit(context, () -> createDemo(context.getSource()))))
						.then(Commands.literal("materialdemo").executes(context -> audit(context, () -> createMaterialDemo(context.getSource()))))
						.then(Commands.literal("chemistrydemo").executes(context -> audit(context, () -> createChemistryDemo(context.getSource()))))
						.then(Commands.literal("controllerdemo")
								.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
								.executes(context -> audit(context, () -> createControllerDemo(context.getSource()))))
						.then(Commands.literal("materialbucket")
								.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
								.then(Commands.argument("material", StringArgumentType.word())
										.suggests((context, builder) -> {
											builder.suggest("empty");
											builder.suggest("water");
											builder.suggest("oil");
											builder.suggest("mercury");
											builder.suggest("water_oil_mix");
											return builder.buildFuture();
										})
										.executes(context -> audit(context, () -> giveMaterialBucket(
												context.getSource(), StringArgumentType.getString(context, "material"))))))
						.then(Commands.literal("granularcontainer")
								.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
								.then(Commands.argument("material", StringArgumentType.word())
										.suggests((context, builder) -> {
											builder.suggest("empty"); builder.suggest("fine_sand"); builder.suggest("light_beads");
											return builder.buildFuture();
										})
										.executes(context -> audit(context, () -> giveGranularContainer(
												context.getSource(), StringArgumentType.getString(context, "material"))))))
						.then(Commands.literal("reagent")
								.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
								.then(Commands.argument("kind", StringArgumentType.word())
										.suggests((context, builder) -> {
											for (String value : List.of("hcl", "naoh", "nacl", "nano3", "kcl", "kno3",
													"water", "indicator", "eraser")) builder.suggest(value);
											return builder.buildFuture();
										})
										.executes(context -> audit(context, () -> giveReagent(
												context.getSource(), StringArgumentType.getString(context, "kind"))))))
						.then(Commands.literal("tensiondemo")
								.executes(context -> audit(context, () -> createTensionDemo(context.getSource(), 30)))
								.then(Commands.argument("angle", IntegerArgumentType.integer(1, 179))
										.executes(context -> audit(context, () -> createTensionDemo(
												context.getSource(), IntegerArgumentType.getInteger(context, "angle"))))))
						.then(Commands.literal("airdemo")
								.executes(context -> audit(context, () -> createAirDemo(context.getSource(), "pressure")))
								.then(Commands.argument("mode", StringArgumentType.word())
										.suggests((context, builder) -> {
											builder.suggest("pressure");
											builder.suggest("vacuum");
											return builder.buildFuture();
										})
										.executes(context -> audit(context, () -> createAirDemo(
												context.getSource(), StringArgumentType.getString(context, "mode"))))))
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
						.then(Commands.literal("empty").executes(context -> audit(context, () -> emptyRegion(context.getSource()))))

						.then(Commands.literal("refresh").executes(context -> audit(context, () -> refreshCollisions(context.getSource()))))
						.then(Commands.literal("entities")
								.executes(context -> audit(context, () -> entityPhysicsStatus(context.getSource())))
								.then(Commands.argument("enabled", BoolArgumentType.bool())
										.executes(context -> audit(context, () -> setEntityPhysics(context.getSource(), BoolArgumentType.getBool(context, "enabled"))))))
						.then(Commands.literal("controllers")
								.executes(context -> audit(context, () -> controllerFeatureStatus(context.getSource())))
								.then(Commands.argument("enabled", BoolArgumentType.bool())
										.executes(context -> audit(context, () -> setControllerFeature(context.getSource(), BoolArgumentType.getBool(context, "enabled"))))))
						.then(Commands.literal("worldbridge")
								.executes(context -> audit(context, () -> worldBridgeFeatureStatus(context.getSource())))
								.then(Commands.argument("enabled", BoolArgumentType.bool())
										.executes(context -> audit(context, () -> setWorldBridgeFeature(context.getSource(), BoolArgumentType.getBool(context, "enabled"))))))
						.then(Commands.literal("transferwal")
								.executes(context -> audit(context, () -> transferWalFaultStatus(context.getSource())))
								.then(Commands.literal("fault")
										.executes(context -> audit(context, () -> transferWalFaultStatus(context.getSource())))
										.then(Commands.argument("point", StringArgumentType.word())
												.suggests((context, builder) -> {
													builder.suggest("none");
													builder.suggest("beforePrepare");
													builder.suggest("afterPrepare");
											builder.suggest("afterSolver");
											builder.suggest("afterStage");
													builder.suggest("afterWal");
													builder.suggest("afterCheckpoint");
													return builder.buildFuture();
												})
												.executes(context -> audit(context, () -> setTransferWalFault(context.getSource(),
														StringArgumentType.getString(context, "point")))))))

						.then(Commands.literal("ownership").executes(context -> audit(context, () -> ownershipReport(context.getSource()))))
						.then(Commands.literal("regions")
								.then(Commands.literal("list").executes(context -> audit(context, () -> listRegions(context.getSource()))))
								.then(Commands.literal("select")
										.then(Commands.argument("uuid", StringArgumentType.word())
												.executes(context -> audit(context, () -> selectRegion(context.getSource(), StringArgumentType.getString(context, "uuid"))))))
								.then(Commands.literal("checkpoint").executes(context -> audit(context, () -> checkpointRegion(context.getSource()))))
								.then(Commands.literal("recoverprevious")
										.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
										.then(Commands.argument("expectedCheckpoint", LongArgumentType.longArg(1L))
												.executes(context -> audit(context, () -> recoverPreviousRegion(
														context.getSource(), LongArgumentType.getLong(context, "expectedCheckpoint")))))))
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

	private static int audit(CommandContext<CommandSourceStack> context, MmmCommandLog.Action action) throws CommandSyntaxException {
		return MmmCommandLog.run(context, action);
	}

	private static int createDemo(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
		if (!service.createDemo(player)) {
			return fail(source, "无法替换活动域：" + service.lastTakeoverFailure());
		}
		return succeed(source, "已创建暂停的 24×16×24 溃坝测试域；使用 /mmm step 或 /mmm run", 1);
	}

	private static int createMaterialDemo(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
		if (!service.createMaterialDemo(player)) {
			return fail(source, "无法创建多材料测试域：" + service.lastTakeoverFailure());
		}
		return succeed(source, "已创建暂停的上油下水溃坝测试域（瞬态，不写 region-state）；使用 /mmm step 或 /mmm run", 1);
	}

	private static int createChemistryDemo(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
		if (!service.createChemistryDemo(player)) {
			return fail(source, "无法创建酸碱化学测试域：" + service.lastTakeoverFailure());
		}
		return succeed(source, "已创建暂停的 HCl/NaOH 对半化学域；左半带 indicator，使用 /mmm step 或 /mmm run 观察混合中和", 1);
	}

	private static int createControllerDemo(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
		net.minecraft.core.BlockPos controller = service.createControllerDemo(player);
		if (controller == null) {
			return fail(source, "无法创建快速控制器域：" + service.lastTakeoverFailure());
		}
		return succeed(source, "已在 " + controller.toShortString()
				+ " 创建并激活内部 4×4×4 的空闭壳；区域当前暂停，可直接对壳面中心使用桶", 1);
	}

	/** Explicit debug/creative source for exercising material controller transfers and mixtures. */
	private static int giveMaterialBucket(CommandSourceStack source, String requested) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
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
		if (!"empty".equals(requested)) service.attachDebugLiquidThermalContents(stack);
		if (!player.getInventory().add(stack)) player.drop(stack, false);
		player.getInventory().setChanged();
		return succeed(source, "已生成 " + description + "（显式 creative/debug 外部源）", 1);
	}

	/** Explicit debug source for exercising authoritative granular controller transfers. */
	private static int giveGranularContainer(CommandSourceStack source, String requested) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
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
			var registry = service.granularRegistry();
			var material = registry.material(registry.materialHandle(materialId));
			if (material.reactive()) return fail(source, "反应性固体只能由守恒相转移或已有容器产生");
			long svq = SolidVolume.SVQ_PER_BV;
			double mass = svq / (double) SolidVolume.SVQ_PER_BV * material.referenceDensity();
			GranularTransferLedger ledger = new GranularTransferLedger(List.of(
					new GranularTransferLedger.Group(material.id(), svq, mass, null)));
			stack = ContainerItemStacks.toStack(GranularContents.fromLedger(ledger, registry, null));
			description = material.id() + " 满干颗粒容器";
		}
		if (!player.getInventory().add(stack)) player.drop(stack, false);
		player.getInventory().setChanged();
		return succeed(source, "已生成 " + description + "（显式 creative/debug 外部源）", 1);
	}

	/** Explicit administrator source for exact chemical fixtures and indicator tools. */
	private static int giveReagent(CommandSourceStack source, String requested) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
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
			ChemicalRegistry registry = service.chemicalRegistry();
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
			service.attachDebugLiquidThermalContents(stack);
		}
		if (!player.getInventory().add(stack)) player.drop(stack, false);
		player.getInventory().setChanged();
		return succeed(source, "已生成 " + requested + "（显式 creative/debug 化学外部源）", 1);
	}

	private static int createTensionDemo(CommandSourceStack source, int angleDegrees)
			throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
		if (!service.createTensionDemo(player, angleDegrees)) {
			return fail(source, "无法创建表面张力测试域：" + service.lastTakeoverFailure());
		}
		return succeed(source, "已创建暂停的表面张力球形冠液滴（σ=100，θ=" + angleDegrees + "°；同体积球冠，瞬态，不写 region-state）；先比较 θ=30° 与 θ=120° 的暂停形状，再用 /mmm step 或 /mmm run", 1);
	}

	private static int createAirDemo(CommandSourceStack source, String mode)
			throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		boolean vacuum = "vacuum".equals(mode);
		if (!vacuum && !"pressure".equals(mode)) {
			return fail(source, "airdemo 模式必须是 pressure 或 vacuum");
		}
		ServerPlayer player = source.getPlayerOrException();
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
		if (!service.createAirDemo(player, vacuum)) {
			return fail(source, "无法创建气压测试域：" + service.lastTakeoverFailure());
		}
		return succeed(source, "已在准星方向搭好玻璃 U 型管并创建暂停的气压演示域（"
				+ (vacuum ? "封顶侧气腔预置负压，运行后封顶侧液面被吸升约 1.5 格"
						: "封顶侧气腔预置正压，运行后封顶侧液面被压低约 2 格")
				+ "；玻璃只替换空气，remove 时恢复；瞬态不写 region-state）；"
				+ "用 /mmm run 观察，/mmm status 查看气压，/mmm debug true 以灰色显示密闭气体", 1);
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
					"已接管附近水体：%d 个非空子格，暂时移除 %d 个原版水方块；请保留世界备份，在 /mmm remove 完成且 WARNING 消失前不要卸载或降级 Mod；测试域当前暂停",
					result.wetCells(), result.removedVanillaBlocks()
			), result.wetCells());
		} else {
			return succeed(source, "已只读捕获附近水体：" + result.wetCells() + " 个非空子格；测试域当前暂停", result.wetCells());
		}
	}

	private static int setPaused(CommandSourceStack source, boolean paused) {
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
		if (!service.exists()) return fail(source, "请先使用 /mmm demo 或 /mmm capture");
		if (!service.setPaused(paused, source.getLevel())) {
			return fail(source, "流体域当前不能运行；" + service.status(source.getServer()));
		}
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

	private static int emptyRegion(CommandSourceStack source) {
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
		String result = service.emptySelectedRegion(source.getLevel());
		if (result.contains("失败") || result.startsWith("没有")) return fail(source, result);
		return succeed(source, result, 1);
	}


	private static int refreshCollisions(CommandSourceStack source) {
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
		if (!service.exists()) return fail(source, "请先使用 /mmm demo 或 /mmm capture");
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

	private static int controllerFeatureStatus(CommandSourceStack source) {
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
		return succeed(source, "控制器功能门：" + (service.controllerFeatureEnabled() ? "开启" : "关闭"),
				service.controllerFeatureEnabled() ? 1 : 0);
	}

	private static int setControllerFeature(CommandSourceStack source, boolean enabled) {
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
		String failure = service.setControllerFeatureEnabled(enabled);
		if (failure != null) return fail(source, "控制器功能门未改变；配置原子发布失败：" + failure);
		return succeed(source, "控制器功能门已" + (enabled ? "开启" : "关闭；现有控制器域已暂停但未删除"), 1);
	}

	private static int worldBridgeFeatureStatus(CommandSourceStack source) {
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
		boolean live = service.worldBridgeFeatureEnabled();
		boolean configured = service.configuredWorldBridgeFeatureEnabled();
		String message = "世界桥功能门：本次启动" + (live ? "开启" : "关闭") + "，配置（下次重启生效）" + (configured ? "开启" : "关闭");
		return succeed(source, live == configured ? message : message + "；重启服务器后才会切换", live ? 1 : 0);
	}

	private static int setWorldBridgeFeature(CommandSourceStack source, boolean enabled) {
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
		String failure = service.setWorldBridgeFeatureForRestart(enabled);
		if (failure != null) return fail(source, "世界桥功能门未改变；配置原子发布失败：" + failure);
		return succeed(source, "世界桥功能门配置已" + (enabled ? "开启" : "关闭")
				+ "；本次启动仍为" + (service.worldBridgeFeatureEnabled() ? "开启" : "关闭")
				+ "，重启服务器后生效；开启后新激活的控制器区域将一次性移除内部原版水并建立可恢复 ownership", 1);
	}


	private static int transferWalFaultStatus(CommandSourceStack source) {
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
		return succeed(source, "容器转移故障注入点：" + service.containerTransferFault(), 1);
	}

	private static int setTransferWalFault(CommandSourceStack source, String pointName) {
		FluidSimulationService.ContainerTransferFaultPoint point = switch (pointName) {
			case "none" -> FluidSimulationService.ContainerTransferFaultPoint.NONE;
			case "beforePrepare" -> FluidSimulationService.ContainerTransferFaultPoint.BEFORE_PREPARE;
			case "afterPrepare" -> FluidSimulationService.ContainerTransferFaultPoint.AFTER_PREPARE;
			case "afterSolver" -> FluidSimulationService.ContainerTransferFaultPoint.AFTER_SOLVER;
			case "afterStage" -> FluidSimulationService.ContainerTransferFaultPoint.AFTER_STAGE;
			case "afterWal" -> FluidSimulationService.ContainerTransferFaultPoint.AFTER_WAL;
			case "afterCheckpoint" -> FluidSimulationService.ContainerTransferFaultPoint.AFTER_CHECKPOINT;
			default -> null;
		};
		if (point == null) return fail(source, "未知故障注入点：" + pointName);
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
		service.setContainerTransferFault(point);
		return succeed(source, "容器转移故障注入点已设为 " + point, 1);
	}

	private static int ownershipReport(CommandSourceStack source) {
		return succeed(source, FluidSimulationService.get(source.getServer()).ownershipReport(source.getServer()), 1);
	}

	private static int listRegions(CommandSourceStack source) {
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
		java.util.List<String> regions = service.regionSummaries();
		if (regions.isEmpty()) return succeed(source, "没有已登记区域；" + service.status(source.getServer()), 0);
		return succeed(source, "区域列表（* 为当前选择）：\n" + String.join("\n", regions), regions.size());
	}

	private static int selectRegion(CommandSourceStack source, String prefix) {
		FluidSimulationService.SelectionResult result = FluidSimulationService.get(source.getServer()).selectRegion(prefix);
		if (!result.succeeded()) return fail(source, result.failure());
		return succeed(source, "已选择区域 " + result.regionId() + "；区域保持原有暂停/运行状态", 1);
	}

	private static int checkpointRegion(CommandSourceStack source) {
		String result = FluidSimulationService.get(source.getServer()).checkpointSelected();
		if (result.contains("失败") || result.startsWith("没有")) return fail(source, result);
		return succeed(source, result, 1);
	}

	private static int recoverPreviousRegion(CommandSourceStack source, long expectedCheckpoint) {
		String result = FluidSimulationService.get(source.getServer())
				.recoverSelectedPreviousCheckpoint(expectedCheckpoint);
		if (result.contains("失败") || result.startsWith("没有") || result.startsWith("拒绝")) {
			return fail(source, result);
		}
		return succeed(source, result, 1);
	}

	private static int thermalSourceStatus(CommandSourceStack source) throws CommandSyntaxException {
		ThermalReservoirBlockEntity reservoir = aimedThermalReservoir(source);
		if (reservoir == null) return fail(source, "请让准星对准 8 格内的温度源方块");
		if (!reservoir.hasValidTemperature()) return fail(source, "温度源保存的设定值无效；请显式设置新温度");
		return succeed(source, String.format(java.util.Locale.ROOT,
				"温度源 %s 的恒温设定为 %.6f K", reservoir.getBlockPos().toShortString(), reservoir.temperatureK()), 1);
	}

	private static int setThermalSource(CommandSourceStack source, double temperatureK) throws CommandSyntaxException {
		if (!Double.isFinite(temperatureK)) return fail(source, "温度必须是有限数值");
		ThermalReservoirBlockEntity reservoir = aimedThermalReservoir(source);
		if (reservoir == null) return fail(source, "请让准星对准 8 格内的温度源方块");
		reservoir.setTemperatureK(temperatureK);
		return succeed(source, String.format(java.util.Locale.ROOT,
				"已将温度源 %s 设为 %.6f K；相邻热区域会在下一次边界扫描时精确唤醒；"
						+ "设定值不钳制，若载体候选状态越出 world thermal registry 有效域将安全暂停",
				reservoir.getBlockPos().toShortString(), temperatureK), 1);
	}

	private static ThermalReservoirBlockEntity aimedThermalReservoir(CommandSourceStack source)
			throws CommandSyntaxException {
		ServerPlayer player = source.getPlayerOrException();
		HitResult hit = player.pick(8.0, 1.0F, false);
		if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) return null;
		if (!player.level().getBlockState(blockHit.getBlockPos()).is(MmmBlocks.THERMAL_RESERVOIR)) return null;
		return player.level().getBlockEntity(blockHit.getBlockPos()) instanceof ThermalReservoirBlockEntity reservoir
				? reservoir : null;
	}

	private static int debugStatus(CommandSourceStack source) {
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
		int result = service.debugOverlays() ? 1 : 0;
		return succeed(source, "碰撞与边界调试层：" + (service.debugOverlays() ? "显示" : "隐藏"), result);
	}

	private static int setDebug(CommandSourceStack source, boolean enabled) {
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
		if (!service.exists()) return fail(source, "请先使用 /mmm demo 或 /mmm capture");
		service.setDebugOverlays(enabled, source.getLevel());
		return succeed(source, "碰撞与边界调试层已" + (enabled ? "显示" : "隐藏"), 1);
	}

	private static int step(CommandSourceStack source, int count) {
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
		if (!service.exists()) return fail(source, "请先使用 /mmm demo 或 /mmm capture");
		int completed = service.step(source.getLevel(), count);
		if (completed == 0) return fail(source, "活动流体域位于其他维度");
		if (completed < 0) return fail(source, "控制器域未完整加载或边界已打开；" + service.status(source.getServer()));
		return succeed(source, "已执行 " + completed + " 个流体步；" + service.status(source.getServer()), completed);
	}

	private static int status(CommandSourceStack source) {
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
		int result = service.exists() ? 1 : 0;
		return succeed(source, service.status(source.getServer()), result);
	}

	private static int logPath(CommandSourceStack source) {
		return succeed(source, "Matter, Motion & Mechanics 指令日志：" + MmmCommandLog.path(source), 1);
	}

	private static int remove(CommandSourceStack source) {
		FluidSimulationService service = FluidSimulationService.get(source.getServer());
		FluidSimulationService.RestorationStats restoration = service.remove(source.getServer());
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

	private static int fail(CommandSourceStack source, String message) {
		MmmCommandLog.sendFailure(source, message);
		return 0;
	}

	private static boolean hasCapability(CommandSourceStack source, WorldCapability capability) {
		WorldCapabilityProfile profile = FluidSimulationService.get(source.getServer()).worldCapabilityProfile();
		return profile != null && profile.hasCapability(capability);
	}

	private static int succeed(CommandSourceStack source, String message, int result) {
		MmmCommandLog.sendSuccess(source, message, false);
		return result;
	}
}
