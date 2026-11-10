package mmm.simulation;

import mmm.block.FluidPortBlock;
import mmm.block.FluidPortBlockEntity;
import mmm.block.MaterialBoxBlockEntity;
import mmm.block.MmmBlocks;
import mmm.block.ValveBlock;
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

/** Two redstone valves and a vanilla sticky piston move a finite charge between two pools. */
public final class PistonPumpGameTest {
    @GameTest(structure="mmm_gametest:pump",maxTicks=1000)
    public void pumpsAcrossCheckpoint(GameTestHelper test) {
        var level=test.getLevel();
        var runtime=MmmRuntimeRegistry.require(level.getServer());
        var manager=runtime.worldUpdates().regions();
        var controller=new BlockPos(1,2,4);
        var portPos=new BlockPos(4,2,1);
        var boxPos=new BlockPos(4,2,0);
        var fillingPortPos=new BlockPos(4,4,4);
        var fillingBoxPos=new BlockPos(4,5,4);
        var piston=new BlockPos(2,2,4);
        var source=new BlockPos(3,2,4);
        var destination=new BlockPos(4,2,4);
        var inlet=new BlockPos(4,2,3);
        var outlet=new BlockPos(4,2,5);
        var drive=new BlockPos(2,3,4);
        var inletPower=new BlockPos(4,3,3);
        var outletPower=new BlockPos(4,3,5);
        for(int x=1;x<=7;x++)for(int y=1;y<=4;y++)for(int z=1;z<=7;z++)
            if(x==1||x==7||y==1||y==4||z==1||z==7)test.setBlock(x,y,z,MmmBlocks.FLUID_BOUNDARY);
        for(int y=2;y<=3;y++)for(int z=2;z<=6;z++) {
            test.setBlock(3,y,z,Blocks.STONE);
            test.setBlock(5,y,z,Blocks.STONE);
        }
        test.setBlock(controller,MmmBlocks.FLUID_CONTROLLER);
        test.setBlock(portPos,MmmBlocks.FLUID_PORT.defaultBlockState().setValue(FluidPortBlock.FACING,Direction.NORTH));
        test.setBlock(boxPos,MmmBlocks.MATERIAL_BOX);
        test.setBlock(fillingPortPos,MmmBlocks.FLUID_PORT.defaultBlockState().setValue(FluidPortBlock.FACING,Direction.UP));
        test.setBlock(fillingBoxPos,MmmBlocks.MATERIAL_BOX);
        test.setBlock(piston,Blocks.STICKY_PISTON.defaultBlockState().setValue(PistonBaseBlock.FACING,Direction.EAST));
        test.setBlock(source,Blocks.STONE);
        test.setBlock(inlet,MmmBlocks.VALVE.defaultBlockState().setValue(ValveBlock.FACING,Direction.SOUTH));
        test.setBlock(outlet,MmmBlocks.VALVE.defaultBlockState().setValue(ValveBlock.FACING,Direction.SOUTH));
        test.setBlock(4,3,3,Blocks.STONE);
        test.setBlock(4,3,5,Blocks.STONE);
        test.setBlock(drive,Blocks.STONE);
        var player=test.makeMockServerPlayerInLevel();
        var activation=runtime.regionActivation().activate(player,test.absolutePos(controller),false);
        test.assertTrue(activation.succeeded(),"Pump controller activation failed: "+activation.failure());
        var region=manager.selected();
        test.assertTrue(region!=null&&region.solverLoaded(),"Pump region did not load");
        region.setEntityPhysicsEnabled(false);
        var initial=region.inventorySummary();
        test.assertTrue(initial!=null&&initial.gasMassUnits()>0,"Pump chamber has no finite gas");
        long[] gas={initial.gasMassUnits()};
        var box=test.getBlockEntity(boxPos,MaterialBoxBlockEntity.class);
        var port=test.getBlockEntity(portPos,FluidPortBlockEntity.class);
        var fillingPort=test.getBlockEntity(fillingPortPos,FluidPortBlockEntity.class);
        var fillingBox=test.getBlockEntity(fillingBoxPos,MaterialBoxBlockEntity.class);
        ItemStack water=new ItemStack(Items.WATER_BUCKET);
        runtime.worldRegistries().attachLiquidThermalContents(water,293.15);
        box.setItem(0,MaterialBoxBlockEntity.newBatch(water));
        port.configure(FluidPortBlockEntity.Mode.STOPPED,FluidPortBlockEntity.Phase.LIQUID,1_000_000_000L,false);
        runtime.industrial().setValve(level,test.absolutePos(inlet),0);
        runtime.industrial().setValve(level,test.absolutePos(outlet),0);
        double[] compression={0,0,0,0};Chamber[] chamber={null};
        int[] stage={-3},ticks={0},cycles={0};long[] liquid={0},solids={Math.addExact(initial.granularMassUnits(),initial.coherentMassUnits())};double[] before={0,0};boolean[] moving={false};
        test.onEachTick(()->{
            ticks[0]++;
            if((stage[0]==-2||stage[0]==-1||stage[0]==2||stage[0]==3||stage[0]==15||stage[0]==17)
                    &&(level.getBlockEntity(test.absolutePos(source)) instanceof PistonMovingBlockEntity
                    ||level.getBlockEntity(test.absolutePos(destination)) instanceof PistonMovingBlockEntity))moving[0]=true;
            if(stage[0]==-3&&ticks[0]>=40) {
                assertInventory(test,region,0,gas[0],solids[0]);
                assertValves(test,inlet,outlet,0,0);
                chamber[0]=chamber(test,region);compression[0]=chamber[0].pressure();
                compression[1]=gasEnergy(region);
                compression[2]=region.thermalAudit().mechanicalWork().toDouble();
                compression[3]=blockCapacity(test,region,destination);
                setPower(test,drive,true);stage[0]=-2;ticks[0]=0;
            } else if(stage[0]==-2&&ticks[0]>=20&&moving[0]&&settled(test,runtime,region,destination)) {
                assertValves(test,inlet,outlet,0,0);
                var after=chamber(test,region);double pressure=after.pressure();
                double swept=compression[3]-blockCapacity(test,region,destination);
                test.assertTrue(Math.abs(swept-1.0)<1e-12,"Piston swept volume differs from its one-block stroke: "+swept);
                System.out.println("piston_compression swept_m3="+swept+" chamber="+chamber[0]+" -> "+after
                        +" gas_energy_change_j="+(gasEnergy(region)-compression[1])
                        +" mechanical_work_j="+(region.thermalAudit().mechanicalWork().toDouble()-compression[2]));
                test.assertTrue(pressure>compression[0],"Closed chamber pressure did not rise: "+compression[0]+" -> "+pressure
                        +"; gasEnergy="+compression[1]+" -> "+gasEnergy(region)
                        +"; work="+compression[2]+" -> "+region.thermalAudit().mechanicalWork().toDouble()
                        +"; chamber="+chamber[0]+" -> "+after);
                test.assertTrue(gasEnergy(region)>compression[1]
                        &&region.thermalAudit().mechanicalWork().toDouble()>compression[2],
                        "Piston compression did not transfer work into the finite gas");
                assertInventory(test,region,0,gas[0],solids[0]);
                setPower(test,drive,false);stage[0]=-1;ticks[0]=0;moving[0]=false;
            } else if(stage[0]==-1&&ticks[0]>=20&&moving[0]&&settled(test,runtime,region,source)) {
                assertInventory(test,region,0,gas[0],solids[0]);
                port.configure(FluidPortBlockEntity.Mode.INPUT,FluidPortBlockEntity.Phase.LIQUID,1_000_000_000L,false);
                stage[0]=0;ticks[0]=0;
            } else if(stage[0]==0&&port.status().equals("运行")) {
                port.configure(FluidPortBlockEntity.Mode.STOPPED,FluidPortBlockEntity.Phase.LIQUID,1_000_000_000L,false);
                long remaining=PartialWaterBucketItem.volume(box.getItem(0));
                long accepted=1_000_000_000L-remaining;
                test.assertTrue(remaining>0&&accepted>0&&accepted<=50_000_000L,"Invalid pump feed: "+accepted);
                liquid[0]=region.inventorySummary().liquidMassUnits();
                gas[0]=region.inventorySummary().gasMassUnits();
                solids[0]=Math.addExact(region.inventorySummary().granularMassUnits(),region.inventorySummary().coherentMassUnits());
                test.assertTrue(liquid[0]>0,"Pump feed did not enter the region");
                before[0]=poolVolume(test,region,4,6);
                before[1]=poolVolume(test,region,4,2);
                setPower(test,inletPower,true);setPower(test,outletPower,false);
                stage[0]=1;ticks[0]=0;
            } else if(stage[0]==1&&ticks[0]>=20&&valvesAt(test,inlet,outlet,4,0)) {
                setPower(test,inletPower,false);setPower(test,outletPower,true);stage[0]=9;ticks[0]=0;
            } else if(stage[0]==9&&valvesAt(test,inlet,outlet,0,4)) {
                setPower(test,drive,true);stage[0]=2;ticks[0]=0;moving[0]=false;
            } else if(stage[0]==2&&ticks[0]>=20&&moving[0]&&settled(test,runtime,region,destination)) {
                assertInventory(test,region,liquid[0],gas[0],solids[0]);
                setPower(test,outletPower,false);setPower(test,inletPower,true);stage[0]=10;ticks[0]=0;
            } else if(stage[0]==10&&valvesAt(test,inlet,outlet,4,0)) {
                setPower(test,drive,false);stage[0]=3;ticks[0]=0;moving[0]=false;
            } else if(stage[0]==3&&ticks[0]>=20&&moving[0]&&settled(test,runtime,region,source)) {
                assertInventory(test,region,liquid[0],gas[0],solids[0]);
                cycles[0]++;
                if(cycles[0]==1) {
                    setPower(test,inletPower,false);stage[0]=12;ticks[0]=0;
                } else if(cycles[0]>=4) {
                    double after=poolVolume(test,region,4,6);
                    test.assertTrue(after>before[0],"Pump delivered no water to its outlet pool: "+before[0]+" -> "+after);
                    double delivered=(after-before[0])*Math.pow(region.solverConfig().cellSize(),3);
                    System.out.println("piston_pump cycles="+cycles[0]+" delivered_m3="+delivered+" stroke_volume_m3=1.0"
                            +" initial_source_m3="+before[1]*Math.pow(region.solverConfig().cellSize(),3));
                    double sourceAfter=poolVolume(test,region,4,2);
                    test.assertTrue(sourceAfter<before[1],"Pump source pool did not decrease: "+before[1]+" -> "+sourceAfter);
                    setPower(test,inletPower,false);setPower(test,outletPower,false);stage[0]=11;ticks[0]=0;
                } else {stage[0]=1;ticks[0]=0;}
            } else if(stage[0]==12&&valvesAt(test,inlet,outlet,0,0)) {
                    region.setPaused(true);manager.select(region.id());
                    long oldGeneration=region.checkpointGeneration();
                    String result=runtime.regionAdministration().checkpointSelected();
                    test.assertTrue(region.checkpointGeneration()>oldGeneration,"Pump checkpoint failed: "+result);
                    test.assertTrue(region.releaseSolverAfterCheckpoint(region.checkpointGeneration()),"Pump solver did not unload");
                    var restored=runtime.regionActivation().activate(player,test.absolutePos(controller),false);
                    test.assertTrue(restored.succeeded()&&region.solverLoaded(),"Pump restore failed: "+restored.failure());
                    assertInventory(test,region,liquid[0],gas[0],solids[0]);
                    region.setPaused(false);
                    setPower(test,inletPower,true);
                    stage[0]=1;ticks[0]=0;
            } else if(stage[0]==11&&valvesAt(test,inlet,outlet,0,0)) {
                    fillBucket(runtime,fillingBox);
                    fillingPort.configure(FluidPortBlockEntity.Mode.INPUT,FluidPortBlockEntity.Phase.LIQUID,1_000_000_000L,false);
                    stage[0]=4;ticks[0]=0;
            } else if(stage[0]==4) {
                if(fillingBox.getItem(0).is(Items.BUCKET))fillBucket(runtime,fillingBox);
                if(ticks[0]%5==0&&poolVolume(test,region,4,4)*.125>.8) {
                    fillingPort.configure(FluidPortBlockEntity.Mode.STOPPED,FluidPortBlockEntity.Phase.LIQUID,1_000_000_000L,false);
                    test.setBlock(4,3,4,Blocks.STONE);stage[0]=8;ticks[0]=0;
                }
            } else if(stage[0]==8&&ticks[0]>=5&&region.solidMicroMask(4,2,4)==255) {
                    var summary=region.inventorySummary();liquid[0]=summary.liquidMassUnits();gas[0]=summary.gasMassUnits();
                    solids[0]=Math.addExact(summary.granularMassUnits(),summary.coherentMassUnits());
                    setPower(test,drive,true);stage[0]=5;ticks[0]=0;
            } else if(stage[0]==5&&region.availabilityFailure()!=null) {
                assertMass(test,region,liquid[0],gas[0],solids[0]);
                test.assertTrue(region.collisionRefreshRequired(),"Blocked piston discarded its geometry refresh");
                setPower(test,drive,false);stage[0]=6;ticks[0]=0;
            } else if(stage[0]==6&&ticks[0]>=20&&region.availabilityFailure()==null&&settled(test,runtime,region,source)) {
                assertInventory(test,region,liquid[0],gas[0],solids[0]);
                setPower(test,outletPower,true);stage[0]=14;ticks[0]=0;
            } else if(stage[0]==14&&valvesAt(test,inlet,outlet,0,4)) {
                before[0]=poolVolume(test,region,4,6);
                setPower(test,drive,true);stage[0]=15;ticks[0]=0;moving[0]=false;
            } else if(stage[0]==15&&ticks[0]>=20&&moving[0]&&settled(test,runtime,region,destination)
                    &&poolVolume(test,region,4,6)>before[0]) {
                assertInventory(test,region,liquid[0],gas[0],solids[0]);
                setPower(test,outletPower,false);setPower(test,inletPower,true);stage[0]=16;ticks[0]=0;
            } else if(stage[0]==16&&valvesAt(test,inlet,outlet,4,0)) {
                setPower(test,drive,false);stage[0]=17;ticks[0]=0;moving[0]=false;
            } else if(stage[0]==17&&ticks[0]>=20&&moving[0]&&settled(test,runtime,region,source)) {
                assertInventory(test,region,liquid[0],gas[0],solids[0]);
                manager.remove(region.id());test.succeed();stage[0]=7;
            }
        });
    }

    private static void setPower(GameTestHelper test,BlockPos position,boolean on) {
        test.setBlock(position,on?Blocks.REDSTONE_BLOCK:Blocks.STONE);
    }
    private static void fillBucket(MmmRuntime runtime,MaterialBoxBlockEntity box) {
        var water=new ItemStack(Items.WATER_BUCKET);runtime.worldRegistries().attachLiquidThermalContents(water,293.15);
        box.setItem(0,MaterialBoxBlockEntity.newBatch(water));
    }
    private static void assertValves(GameTestHelper test,BlockPos inlet,BlockPos outlet,int input,int output) {
        test.assertTrue(valvesAt(test,inlet,outlet,input,output),
                "Redstone did not set the requested pump valve openings");
    }
    private static boolean valvesAt(GameTestHelper test,BlockPos inlet,BlockPos outlet,int input,int output) {
        return test.getLevel().getBlockState(test.absolutePos(inlet)).getValue(ValveBlock.OPENING)==input
                &&test.getLevel().getBlockState(test.absolutePos(outlet)).getValue(ValveBlock.OPENING)==output;
    }
    private static boolean settled(GameTestHelper test,MmmRuntime runtime,FluidRegion region,BlockPos position) {
        return test.getLevel().getBlockState(test.absolutePos(position)).is(Blocks.STONE)
                && !(test.getLevel().getBlockEntity(test.absolutePos(position)) instanceof PistonMovingBlockEntity)
                && !runtime.worldUpdates().movingBlock(region,test.absolutePos(new BlockPos(2,2,4)))
                && !runtime.worldUpdates().movingBlock(region,test.absolutePos(new BlockPos(3,2,4)))
                && !runtime.worldUpdates().movingBlock(region,test.absolutePos(new BlockPos(4,2,4)))
                && region.entityBodyState().bodies().stream().noneMatch(body->MinecraftWorldUpdates.worldBody(body.bodyId()));
    }
    private static double poolVolume(GameTestHelper test,FluidRegion region,int x,int z) {
        var block=test.absolutePos(new BlockPos(x,2,z));var origin=region.origin();
        int cellX=(block.getX()-origin.getX())*2,cellY=(block.getY()-origin.getY())*2,cellZ=(block.getZ()-origin.getZ())*2;
        var state=region.hostSnapshot();double sum=0;
        for(int dx=0;dx<2;dx++)for(int dy=0;dy<4;dy++)for(int dz=0;dz<2;dz++)sum+=state.volume(cellX+dx,cellY+dy,cellZ+dz);
        return sum;
    }
    private static double blockCapacity(GameTestHelper test,FluidRegion region,BlockPos position) {
        var block=test.absolutePos(position);var origin=region.origin();
        int x=(block.getX()-origin.getX())*2,y=(block.getY()-origin.getY())*2,z=(block.getZ()-origin.getZ())*2;
        int open=0;
        for(int dx=0;dx<2;dx++)for(int dy=0;dy<2;dy++)for(int dz=0;dz<2;dz++)
            open+=Integer.bitCount((~region.solidMicroMask(x+dx,y+dy,z+dz))&255);
        return open*Math.pow(region.solverConfig().cellSize(),3)/8;
    }
    private record Chamber(double pressure,double gasVolume,long standardVolumeNanos,long gasMassUnits,double gasEnergy) { }
    private static Chamber chamber(GameTestHelper test,FluidRegion region) {
        var point=test.absolutePos(new BlockPos(4,2,4));var origin=region.origin();
        int x=(point.getX()-origin.getX())*2,y=(point.getY()-origin.getY())*2,z=(point.getZ()-origin.getZ())*2;
        var state=region.hostSnapshot();var gasState=state.captureAuthoritativeState().spatialGas();
        double weighted=0,volume=0,energy=0;long standard=0,mass=0;
        for(int dx=0;dx<2;dx++)for(int dy=0;dy<4;dy++)for(int dz=0;dz<2;dz++) {
            double gas=state.cellGasVolume(x+dx,y+dy,z+dz);
            if(gas>0){weighted+=gas*state.pressure(x+dx,y+dy,z+dz);volume+=gas;}
            int cell=region.grid().cell(x+dx,y+dy,z+dz);var stock=gasState.cells().get(cell);
            standard=Math.addExact(standard,stock.standardVolumeNanos());mass=Math.addExact(mass,stock.totalMassUnits());energy+=gasState.energyAt(cell);
        }
        if(!(volume>0))throw new IllegalStateException("Pump chamber contains no gas volume");
        return new Chamber(weighted/volume,volume,standard,mass,energy);
    }
    private static double gasEnergy(FluidRegion region) {
        return java.util.Arrays.stream(region.hostSnapshot().captureAuthoritativeState().spatialGas().internalEnergy()).sum();
    }
    private static void assertInventory(GameTestHelper test,FluidRegion region,long liquid,long gas,long solids) {
        test.assertTrue(region.availabilityFailure()==null,"Pump region paused: "+region.availabilityFailure());
        assertMass(test,region,liquid,gas,solids);
        test.assertTrue(region.thermalAuditClosed(),"Pump thermal ledger did not close");
    }
    private static void assertMass(GameTestHelper test,FluidRegion region,long liquid,long gas,long solids) {
        var summary=region.inventorySummary();
        test.assertTrue(summary!=null&&Math.addExact(Math.addExact(summary.liquidMassUnits(),summary.gasMassUnits()),
                Math.addExact(summary.granularMassUnits(),summary.coherentMassUnits()))==Math.addExact(Math.addExact(liquid,gas),solids),
                "Pump changed finite material inventory");
    }
}
