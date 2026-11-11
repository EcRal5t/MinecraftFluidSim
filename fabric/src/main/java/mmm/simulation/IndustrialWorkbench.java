package mmm.simulation;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.ArrayList;
import java.util.Locale;
import mmm.block.FluidPortBlockEntity;
import mmm.block.MaterialBoxBlockEntity;
import mmm.block.ProcessSensorBlockEntity;
import mmm.block.ScreenBlockEntity;
import mmm.item.ContainerItemMapper;
import mmm.item.GasContainerItem;
import mmm.item.GranularContainerItem;
import mmm.item.MmmItems;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Builds industrial layouts through spatial selection and block entity entry points. */
public final class IndustrialWorkbench {
    private IndustrialWorkbench() { }
    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        var names=java.util.Arrays.stream(IndustrialLayout.Scenario.values()).map(value->value.name().toLowerCase(Locale.ROOT)).toList();
        return Commands.literal("plant").then(Commands.argument("scenario",StringArgumentType.word())
                .suggests((context,builder)->net.minecraft.commands.SharedSuggestionProvider.suggest(names,builder)).executes(context -> {
            var player=context.getSource().getPlayerOrException();
            if(!player.createCommandSourceStack().permissions().hasPermission(Permissions.COMMANDS_ADMIN))return 0;
            try {
                var scenario=IndustrialLayout.Scenario.valueOf(StringArgumentType.getString(context,"scenario").toUpperCase(Locale.ROOT));
                player.sendSystemMessage(Component.literal(build(player,scenario)));return 1;
            } catch(IllegalArgumentException invalid) {
                player.sendSystemMessage(Component.literal("场景："+String.join("、",names)));return 0;
            } catch(IllegalStateException failed) {
                player.sendSystemMessage(Component.literal(failed.getMessage()));return 0;
            }
        }));
    }
    public static String build(ServerPlayer player, IndustrialLayout.Scenario scenario) {
        var minimum=player.blockPosition().relative(player.getDirection(),6).above(2);
        var layouts=scenario==IndustrialLayout.Scenario.DISTILLATION
                ?java.util.List.of(IndustrialLayout.at(minimum,IndustrialLayout.Scenario.DISTILLATION_SINGLE),
                        IndustrialLayout.at(minimum.offset(8,0,0),IndustrialLayout.Scenario.DISTILLATION_SINGLE))
                :java.util.List.of(IndustrialLayout.at(minimum,scenario));
        for(var layout:layouts)requireBuildSpace(player.level(),layout);
        MmmRuntimeRegistry.require(player.level().getServer()).regionActivation()
                .preflightSelections(player.level(),layouts.stream().map(IndustrialWorkbench::selectionCorners).toList());
        var regions=new ArrayList<String>();
        for(var layout:layouts){buildUnit(player,layout);regions.add(layout.minimum().toShortString());}
        return "装置已建立："+scenario+"；区域起点 "+String.join("、",regions)
                +(layouts.size()==2?"；将第一台收集的馏分桶移入第二台进料箱":"");
    }
    private static void requireBuildSpace(net.minecraft.server.level.ServerLevel level,IndustrialLayout layout) {
        var blocks=layout.blocks();
        int minX=blocks.keySet().stream().mapToInt(BlockPos::getX).min().orElseThrow(),maxX=blocks.keySet().stream().mapToInt(BlockPos::getX).max().orElseThrow();
        int minY=blocks.keySet().stream().mapToInt(BlockPos::getY).min().orElseThrow(),maxY=blocks.keySet().stream().mapToInt(BlockPos::getY).max().orElseThrow();
        int minZ=blocks.keySet().stream().mapToInt(BlockPos::getZ).min().orElseThrow(),maxZ=blocks.keySet().stream().mapToInt(BlockPos::getZ).max().orElseThrow();
        for(int x=minX;x<=maxX;x++)for(int y=minY;y<=maxY;y++)for(int z=minZ;z<=maxZ;z++) {
            var p=new BlockPos(x,y,z);
            if(!level.hasChunkAt(p)||!level.isInWorldBounds(p)||!level.isInsideBuildHeight(p)||!level.isEmptyBlock(p))
                throw new IllegalStateException("请在已加载的空地建造："+p.toShortString());
        }
    }
    private static void buildUnit(ServerPlayer player,IndustrialLayout layout) {
        var level=player.level();var runtime=MmmRuntimeRegistry.require(level.getServer());var blocks=layout.blocks();
        var corners=selectionCorners(layout);
        int minX=corners[0].getX(),minY=corners[0].getY(),minZ=corners[0].getZ();
        int maxX=corners[1].getX(),maxY=corners[1].getY(),maxZ=corners[1].getZ();
        var placed=new ArrayList<BlockPos>();
        try {
            for(var entry:blocks.entrySet()) {
                if(entry.getValue().is(mmm.block.MmmBlocks.MATERIAL_BOX))continue;
                if(!level.setBlock(entry.getKey(),entry.getValue(),Block.UPDATE_ALL))throw new IllegalStateException("无法放置 "+entry.getKey().toShortString());
                placed.add(entry.getKey());
            }
            for(var wall:layout.walls()) if(blocks.get(wall.position()).is(mmm.block.MmmBlocks.THERMAL_RESERVOIR))
                ((mmm.block.ThermalReservoirBlockEntity)level.getBlockEntity(wall.position())).setTemperatureK(wall.temperatureK());
            var result=runtime.regionActivation().createSelection(player,new BlockPos(minX,minY,minZ),new BlockPos(maxX,maxY,maxZ));
            if(!result.succeeded()) {
                if(result.retainShell())placed.clear();
                throw new IllegalStateException("模拟区域启用失败："+result.failure());
            }
        } catch(RuntimeException failure) {
            for(var position:placed) level.setBlock(position,Blocks.AIR.defaultBlockState(),Block.UPDATE_ALL);
            throw failure;
        }
        for(var port:layout.ports()) {
            level.setBlock(port.box(),mmm.block.MmmBlocks.MATERIAL_BOX.defaultBlockState(),Block.UPDATE_ALL);
            var block=(FluidPortBlockEntity)level.getBlockEntity(port.position());
            block.configure(port.mode(),port.phase(),port.rate(),port.redstone());

        }
        for(var screen:layout.screens()) {
            var block=(ScreenBlockEntity)level.getBlockEntity(screen.position());
            block.configure(screen.poreDiameter(),screen.openFraction());
        }
        for(var sensor:layout.sensors()) {
            var block=(ProcessSensorBlockEntity)level.getBlockEntity(sensor.position());
            block.configure(sensor.measure(),sensor.low(),sensor.high(),sensor.below(),false,sensor.first(),sensor.second());
        }
        if(!layout.walls().isEmpty() || !layout.coatings().isEmpty()) {
            var region=runtime.industrial().regionAt(level,layout.controller().relative(net.minecraft.core.Direction.SOUTH));
            if(region==null)throw new IllegalStateException("装置区域不可用");
            var registry=runtime.worldRegistries().thermalRegistry();
            runtime.environment().refresh(level,region,FluidRegionFactory.CELLS_PER_BLOCK);
            initializeThermal(region,layout,registry,runtime.environment());
            for(var wall:layout.walls())if(blocks.get(wall.position()).is(mmm.block.MmmBlocks.FINITE_HEAT_EXCHANGER))
                ((mmm.block.FiniteHeatExchangerBlockEntity)level.getBlockEntity(wall.position())).bind(region.id(),Long.toString(wall.position().asLong()));
        }
        var held=player.getItemInHand(InteractionHand.MAIN_HAND);
        if(!held.isEmpty()&&held.getCount()==1) {
            try {
                ContainerItemMapper.fingerprint(held);
                var kind=MinecraftContainerTransferOperations.classify(held);
                boolean filled=kind==MinecraftContainerTransferOperations.TransferKind.POUR || kind==MinecraftContainerTransferOperations.TransferKind.MATERIAL_POUR
                        || kind==MinecraftContainerTransferOperations.TransferKind.GRANULAR_POUR || kind==MinecraftContainerTransferOperations.TransferKind.GAS_POUR;
                var phase=held.getItem() instanceof GasContainerItem?FluidPortBlockEntity.Phase.GAS:
                        held.getItem() instanceof GranularContainerItem?FluidPortBlockEntity.Phase.SOLID:FluidPortBlockEntity.Phase.LIQUID;
                var input=layout.ports().stream().filter(p->filled&&p.mode()==FluidPortBlockEntity.Mode.INPUT&&p.phase()==phase).findFirst();
                if(input.isPresent()) {
                    var box=(MaterialBoxBlockEntity)level.getBlockEntity(input.get().box());
                    box.setItem(0,MaterialBoxBlockEntity.newBatch(held));player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
                }
            } catch(IllegalArgumentException ignored) { }
        }
    }
    private static BlockPos[] selectionCorners(IndustrialLayout layout) {
        var selection=layout.blocks().entrySet().stream().filter(e->!e.getValue().is(mmm.block.MmmBlocks.MATERIAL_BOX)).map(java.util.Map.Entry::getKey).toList();
        int minX=selection.stream().mapToInt(BlockPos::getX).min().orElseThrow(),maxX=selection.stream().mapToInt(BlockPos::getX).max().orElseThrow();
        int minY=selection.stream().mapToInt(BlockPos::getY).min().orElseThrow(),maxY=selection.stream().mapToInt(BlockPos::getY).max().orElseThrow();
        int minZ=selection.stream().mapToInt(BlockPos::getZ).min().orElseThrow(),maxZ=selection.stream().mapToInt(BlockPos::getZ).max().orElseThrow();
        return new BlockPos[]{new BlockPos(minX,minY,minZ),new BlockPos(maxX,maxY,maxZ)};
    }
    static void initializeThermal(FluidRegion region,IndustrialLayout layout,mmm.thermal.ThermalRegistry registry,
                                  WorldThermalEnvironment environment) {
        var inventory=WorldThermalInventories.project(region.finiteThermalDevices(registry.fingerprint()),
                region.environmentBinding(),environment.current(region.dimension().identifier().toString()));
        for(var wall:layout.walls())if(layout.blocks().get(wall.position()).is(mmm.block.MmmBlocks.FINITE_HEAT_EXCHANGER))
            inventory=inventory.insertExternal(FiniteHeatExchangerOperations.newDevice(registry,wall.position(),wall.temperatureK()));
        var devices=new ArrayList<>(inventory.devices());
        for(var wall:layout.walls()) {
            if(layout.blocks().get(wall.position()).is(mmm.block.MmmBlocks.THERMAL_RESERVOIR)||layout.blocks().get(wall.position()).is(mmm.block.MmmBlocks.FINITE_HEAT_EXCHANGER))continue;
            int i=findWall(devices,wall.position());var device=devices.get(i);var body=device.body();
            double energy=mmm.thermal.SurfaceThermalState.energyAtTemperature(registry,device,wall.temperatureK());
            devices.set(i,new mmm.thermal.FiniteThermalDeviceState(device.providerId(),device.objectId(),device.deviceTypeId(),
                    new mmm.thermal.FiniteThermalBodyState(body.generation()+1,body.thermalRegistryFingerprint(),body.thermalMaterialId(),body.massUnits(),energy),device.surfaces()));
        }
        for(var coating:layout.coatings()) {
            int i=findWall(devices,coating.position());var device=devices.get(i);
            var surface=new mmm.thermal.FixedSurfaceState(mmm.thermal.FiniteThermalDeviceDefinition.LocalFace.valueOf(coating.face().name()),
                    coating.materialId(),mmm.units.Mass.fromKilograms(coating.loadingKg()),java.util.Map.of());
            double temperature=registry.surfaceMaterial(coating.materialId()).referenceTemperatureK();
            double energy=mmm.thermal.SurfaceThermalState.surfaceEnergyAtTemperature(registry,surface,temperature);
            devices.set(i,mmm.thermal.SurfaceThermalState.attach(registry,device,surface,energy));
        }
        var old=inventory.exactInternalEnergy();var now=mmm.units.SignedDyadic.ZERO;
        for(var device:devices)now=now.add(mmm.units.SignedDyadic.of(device.body().internalEnergyJ()));
        var delta=now.subtract(old);var audit=inventory.audit();
        var updated=new mmm.thermal.FiniteThermalDeviceInventory(registry.fingerprint(),devices,
                new mmm.thermal.FiniteThermalDeviceAudit(audit.initialInventory(),delta.signum()>0?audit.externalSource().add(delta):audit.externalSource(),
                        delta.signum()<0?audit.externalSink().subtract(delta):audit.externalSink(),audit.solverExchange(),audit.roundoffResidual()));
        try(var prepared=environment.prepareDeviceEdit(region.environmentBinding(),updated)) {
            region.editWorldThermalInventory(updated,registry,prepared);
        }
    }
    private static int findWall(java.util.List<mmm.thermal.FiniteThermalDeviceState> devices,BlockPos position) {
        String prefix=position.asLong()+"@";
        for(int i=0;i<devices.size();i++)if(devices.get(i).providerId().equals(WorldThermalEnvironment.PROVIDER)
                && devices.get(i).objectId().startsWith(prefix))return i;
        throw new IllegalStateException("热墙未连接区域："+position.toShortString());
    }
}
