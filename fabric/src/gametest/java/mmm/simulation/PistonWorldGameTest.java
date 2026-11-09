package mmm.simulation;

import mmm.block.FluidPortBlock;
import mmm.block.FluidPortBlockEntity;
import mmm.block.MaterialBoxBlockEntity;
import mmm.block.MmmBlocks;
import mmm.item.PartialWaterBucketItem;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;

/** Real controller, finite box transfer, redstone piston and resident fluid state. */
public final class PistonWorldGameTest {
    @GameTest(structure="mmm_gametest:piston",maxTicks=200)
    public void stickyPistonAndFinitePort(GameTestHelper test) {
        var level=test.getLevel();
        var runtime=MmmRuntimeRegistry.require(level.getServer());
        var manager=runtime.worldUpdates().regions();
        var controller=new BlockPos(1,2,2);
        var portPosition=new BlockPos(6,2,2);
        var boxPosition=new BlockPos(7,2,2);
        var piston=new BlockPos(2,2,3);
        var source=new BlockPos(3,2,3);
        var destination=new BlockPos(4,2,3);
        var power=new BlockPos(2,2,2);
        for(int z=1;z<=4;z++)for(int y=1;y<=4;y++)for(int x=1;x<=6;x++)
            if(x==1||x==6||y==1||y==4||z==1||z==4)test.setBlock(x,y,z,MmmBlocks.FLUID_BOUNDARY);
        test.setBlock(controller,MmmBlocks.FLUID_CONTROLLER);
        test.setBlock(portPosition,MmmBlocks.FLUID_PORT.defaultBlockState().setValue(FluidPortBlock.FACING,Direction.EAST));
        test.setBlock(boxPosition,MmmBlocks.MATERIAL_BOX);
        test.setBlock(piston,Blocks.STICKY_PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING,Direction.EAST));
        test.setBlock(source,Blocks.STONE);
        var player=test.makeMockServerPlayerInLevel();
        var activation=runtime.regionActivation().activate(player,test.absolutePos(controller),false);
        test.assertTrue(activation.succeeded(),"Controller activation failed: "+activation.failure());
        var region=manager.selected();
        test.assertTrue(region!=null&&region.solverLoaded(),"Controller did not publish its region");
        region.setEntityPhysicsEnabled(false);
        var initial=region.inventorySummary();
        test.assertTrue(initial!=null&&initial.gasMassUnits()>0,"Piston chamber has no finite gas");
        long[] gas={initial.gasMassUnits()};
        var box=test.getBlockEntity(boxPosition,MaterialBoxBlockEntity.class);
        var port=test.getBlockEntity(portPosition,FluidPortBlockEntity.class);
        ItemStack water=new ItemStack(Items.WATER_BUCKET);
        runtime.worldRegistries().attachLiquidThermalContents(water,293.15);
        box.setItem(0,MaterialBoxBlockEntity.newBatch(water));
        port.configure(FluidPortBlockEntity.Mode.INPUT,FluidPortBlockEntity.Phase.LIQUID,50_000_000L,false);
        int[] stage={0};long[] liquid={0},solids={0},audit={0,0};boolean[] moving={false,false},coupled={false,false};
        test.onEachTick(()->{
            if(stage[0]==0&&port.status().equals("运行")){
                port.configure(FluidPortBlockEntity.Mode.STOPPED,FluidPortBlockEntity.Phase.LIQUID,50_000_000L,false);
                long remaining=PartialWaterBucketItem.volume(box.getItem(0));
                long accepted=1_000_000_000L-remaining;
                test.assertTrue(remaining>0&&accepted>0&&accepted<=50_000_000L,"Material box transfer is outside its rate: "+remaining);
                var summary=region.inventorySummary();
                test.assertTrue(summary.liquidMassUnits()>0&&Math.abs(summary.liquidVolumeCubicMetres()-accepted*1e-9)<1e-8,
                        "Box withdrawal differs from the region inventory");
                liquid[0]=summary.liquidMassUnits();
                gas[0]=summary.gasMassUnits();
                solids[0]=Math.addExact(summary.granularMassUnits(),summary.coherentMassUnits());
                var gasState=region.hostSnapshot().captureAuthoritativeState().spatialGas();
                audit[0]=gasAuditMass(gasState.externalSource());audit[1]=gasAuditMass(gasState.escapedSink());
                assertInventory(test,region,liquid[0],gas[0],solids[0],audit);
                reportPhases("feed",region);
                stage[0]=1;test.setBlock(power,Blocks.REDSTONE_BLOCK);
            } else if(stage[0]==1||stage[0]==2){
                int direction=stage[0]-1;
                if(level.getBlockEntity(test.absolutePos(destination)) instanceof PistonMovingBlockEntity
                        ||level.getBlockEntity(test.absolutePos(source)) instanceof PistonMovingBlockEntity)moving[direction]=true;
                if(region.entityBodyState().bodies().stream().anyMatch(body->MinecraftWorldUpdates.worldBody(body.bodyId())))coupled[direction]=true;
                var expected=direction==0?destination:source;
                if(moving[direction]&&coupled[direction]&&level.getBlockState(test.absolutePos(expected)).is(Blocks.STONE)
                        &&region.entityBodyState().bodies().stream().noneMatch(body->MinecraftWorldUpdates.worldBody(body.bodyId()))){
                    assertInventory(test,region,liquid[0],gas[0],solids[0],audit);
                    reportPhases(direction==0?"extended":"retracted",region);
                    int x=direction==0?4:2;
                    test.assertTrue(region.solidMicroMask(x,0,2)==255,"Piston did not publish its final static shape");
                    if(direction==0){stage[0]=2;test.setBlock(power,Blocks.AIR);}
                    else {manager.remove(region.id());test.succeed();stage[0]=3;}
                }
            }
        });
    }

    private static void assertInventory(GameTestHelper test,FluidRegion region,long liquid,long gas,long solids,long[] audit) {
        var summary=region.inventorySummary();
        test.assertTrue(region.availabilityFailure()==null,"Region paused during piston motion: "+region.availabilityFailure());
        if(summary==null||Math.addExact(Math.addExact(summary.liquidMassUnits(),summary.gasMassUnits()),
                Math.addExact(summary.granularMassUnits(),summary.coherentMassUnits()))!=Math.addExact(Math.addExact(liquid,gas),solids)) {
            var state=region.hostSnapshot().captureAuthoritativeState().spatialGas();
            test.assertTrue(false,"Piston fluid inventory: liquid="+liquid+" -> "+(summary==null?"unavailable":summary.liquidMassUnits())
                    +" gas="+gas+" -> "+(summary==null?"unavailable":summary.gasMassUnits())
                    +" granular="+(summary==null?"unavailable":summary.granularMassUnits())
                    +" coherent="+(summary==null?"unavailable":summary.coherentMassUnits())
                    +" gasSource="+audit[0]+" -> "+gasAuditMass(state.externalSource())
                    +" gasSink="+audit[1]+" -> "+gasAuditMass(state.escapedSink()));
        }
        test.assertTrue(region.thermalAuditClosed(),"Piston motion did not close the thermal ledger");
    }
    private static long gasAuditMass(mmm.fluid.air.GasAuditLedger ledger) {
        return ledger.entries().stream().mapToLong(mmm.fluid.air.GasAuditLedger.Entry::massUnits).sum();
    }
    private static void reportPhases(String stage,FluidRegion region) {
        var host=region.hostSnapshot();var state=host.captureAuthoritativeState();var grid=region.grid();
        var gas=state.spatialGas();double liquidU=0,iceU=0;
        double low=Double.POSITIVE_INFINITY,high=Double.NEGATIVE_INFINITY,maxPressure=0;
        for(int cell=0;cell<grid.cellCount();cell++) {
            int x=grid.x(cell),y=grid.y(cell),z=grid.z(cell);
            if(host.volume(x,y,z)<=0)continue;
            double temperature=host.thermalTemperatureInCell(x,y,z);
            low=Math.min(low,temperature);high=Math.max(high,temperature);
            maxPressure=Math.max(maxPressure,host.pressure(x,y,z)+mmm.thermal.MixtureVolumeModel.REFERENCE_PRESSURE_PA);
        }
        for(var stock:state.liquid().physicalStocks()) {
            int cell=stock.key().cell(),x=grid.x(cell),y=grid.y(cell),z=grid.z(cell);liquidU+=stock.internalEnergy();

        }
        var function=new mmm.thermal.ThermalStateFunction(state.thermalRegistry());
        var phases=new java.util.ArrayList<String>();
        for(var group:state.thermalOwner().coherentGroups()) {
            iceU+=group.internalEnergy();
            var species=new java.util.TreeMap<String,Long>();
            if(group.composition()!=null)for(var component:group.composition().components())species.put(component.speciesId(),component.amountQuanta());
            int handle=state.thermalRegistry().materialHandle(mmm.thermal.ThermalMaterialDefinition.CarrierKind.COHERENT_SOLID,group.phaseId());
            double temperature=function.stateForInternalEnergy(handle,group.mass(),species,group.internalEnergy()).temperatureK();
            phases.add(group.phaseId()+":"+group.massUnits()+"@"+temperature);
        }
        System.out.println("piston_phases stage="+stage+" liquidTemperature="+low+".."+high
                +" maxPressurePa="+maxPressure+" kineticJ="+host.liquidKineticEnergyJoules()+" liquidU="+liquidU
                +" coherentU="+iceU+" gasU="+java.util.Arrays.stream(gas.internalEnergy()).sum()
                +" deviceExchange="+region.thermalAudit().finiteDeviceExchange().toDouble()
                +" mechanicalWork="+region.thermalAudit().mechanicalWork().toDouble()+" coherent="+phases);
    }
}
