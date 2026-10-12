package mmm;

import mmm.logging.MmmCommandLog;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.permissions.Permissions;

import java.util.List;

import static mmm.MmmCommandHandlers.*;

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
						.then(Commands.literal("chemstatus")
								.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
								.executes(context -> audit(context, () -> chemistryInventoryStatus(context.getSource()))))
						.then(Commands.literal("granstatus")
								.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_ADMIN))
								.executes(context -> audit(context, () -> granularInventoryStatus(context.getSource()))))
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

}
