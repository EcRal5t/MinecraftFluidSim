package mmm.simulation;

import mmm.block.FluidPortBlock;
import mmm.block.FluidPortBlockEntity;
import mmm.block.MaterialBoxBlockEntity;
import mmm.block.MmmBlocks;
import mmm.block.ProcessSensorBlockEntity;
import mmm.block.ScreenBlock;
import mmm.block.ScreenBlockEntity;
import mmm.item.PartialWaterBucketItem;
import mmm.item.MmmComponents;
import mmm.item.MmmItems;
import mmm.thermal.FiniteThermalDeviceState;
import mmm.thermal.SurfaceCoatingContents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.Blocks;

/** Real sensor redstone, finite box transfers and block-entity settings. */
public final class IndustrialControlGameTest {
    @GameTest(structure="mmm_gametest:piston",maxTicks=1200)
    public void sensorControlsFinitePort(GameTestHelper test) {
        var level=test.getLevel();
        var runtime=MmmRuntimeRegistry.require(level.getServer());
        var controller=new BlockPos(1,2,2);
        var portPosition=new BlockPos(6,2,2);
        var sensorPosition=new BlockPos(6,3,2);
        var boxPosition=new BlockPos(7,2,2);
        var screenPosition=new BlockPos(3,2,2);
        var wallPosition=new BlockPos(2,2,3);
        for(int z=1;z<=4;z++)for(int y=1;y<=4;y++)for(int x=1;x<=6;x++)
            if(x==1||x==6||y==1||y==4||z==1||z==4)test.setBlock(x,y,z,MmmBlocks.FLUID_BOUNDARY);
        test.setBlock(wallPosition,Blocks.IRON_BLOCK);
        test.setBlock(controller,MmmBlocks.FLUID_CONTROLLER);
        test.setBlock(portPosition,MmmBlocks.FLUID_PORT.defaultBlockState().setValue(FluidPortBlock.FACING,Direction.EAST));
        test.setBlock(sensorPosition,MmmBlocks.PROCESS_SENSOR);
        test.setBlock(boxPosition,MmmBlocks.MATERIAL_BOX);
        var player=test.makeMockServerPlayerInLevel();
        var activation=runtime.regionActivation().activate(player,test.absolutePos(controller),false);
        test.assertTrue(activation.succeeded(),"Controller activation failed: "+activation.failure());
        var region=runtime.worldUpdates().regions().selected();
        test.assertTrue(region!=null&&region.solverLoaded(),"Controller did not publish its region");
        region.setEntityPhysicsEnabled(false);
        var box=test.getBlockEntity(boxPosition,MaterialBoxBlockEntity.class);
        var sensors=new ProcessSensorBlockEntity[]{test.getBlockEntity(sensorPosition,ProcessSensorBlockEntity.class)};
        var ports=new FluidPortBlockEntity[]{test.getBlockEntity(portPosition,FluidPortBlockEntity.class)};
        var first=test.absolutePos(new BlockPos(2,2,2));var second=test.absolutePos(new BlockPos(5,3,3));
        sensors[0].configure(ProcessSensorBlockEntity.Measure.LEVEL,.0001,.001,true,false,first,second);
        ports[0].configure(FluidPortBlockEntity.Mode.INPUT,FluidPortBlockEntity.Phase.LIQUID,50_000_000L,true);
        int[] stage={0};long[] accepted={0};
        var wall=test.absolutePos(wallPosition);
        var thermal=runtime.worldRegistries().thermalRegistry();
        SurfaceCoatingContents[] removed={null};
        test.onEachTick(()->{
            var sensor=sensors[0];var port=ports[0];
            if(stage[0]==0 && sensor.output()==15 && port.status().equals("等待供料")) {
                ItemStack water=new ItemStack(Items.WATER_BUCKET);
                runtime.worldRegistries().attachLiquidThermalContents(water,293.15);
                box.setItem(0,MaterialBoxBlockEntity.newBatch(water));
                stage[0]=1;
            } else if(stage[0]==1 && sensor.output()==0 && port.status().equals("等待红石")) {
                long remaining=PartialWaterBucketItem.volume(box.getItem(0));
                accepted[0]=1_000_000_000L-remaining;
                test.assertTrue(accepted[0]>0 && remaining>0,"Level control did not stop a partial input");
                var restoredSensor=(ProcessSensorBlockEntity)BlockEntity.loadStatic(test.absolutePos(sensorPosition),
                        level.getBlockState(test.absolutePos(sensorPosition)),sensor.saveWithFullMetadata(level.registryAccess()),level.registryAccess());
                var restoredPort=(FluidPortBlockEntity)BlockEntity.loadStatic(test.absolutePos(portPosition),
                        level.getBlockState(test.absolutePos(portPosition)),port.saveWithFullMetadata(level.registryAccess()),level.registryAccess());
                test.assertTrue(restoredSensor!=null && restoredSensor.measure()==ProcessSensorBlockEntity.Measure.LEVEL
                                && restoredSensor.low()==.0001 && restoredSensor.high()==.001 && restoredSensor.below()
                                && first.equals(restoredSensor.first()) && second.equals(restoredSensor.second()),
                        "Sensor settings did not survive block-entity storage");
                test.assertTrue(restoredPort!=null && restoredPort.mode()==FluidPortBlockEntity.Mode.INPUT
                                && restoredPort.phase()==FluidPortBlockEntity.Phase.LIQUID
                                && restoredPort.nanosPerSecond()==50_000_000L && restoredPort.redstone(),
                        "Port settings did not survive block-entity storage");
                level.setBlockEntity(restoredSensor);level.setBlockEntity(restoredPort);
                sensors[0]=restoredSensor;ports[0]=restoredPort;
                restoredSensor.configure(ProcessSensorBlockEntity.Measure.TEMPERATURE,270,280,false,false,first,second);
                stage[0]=2;
            } else if(stage[0]==2 && sensor.output()==15 && port.status().equals("运行")
                    && 1_000_000_000L-PartialWaterBucketItem.volume(box.getItem(0))>accepted[0]) {
                sensor.configure(ProcessSensorBlockEntity.Measure.TEMPERATURE,270,280,true,false,first,second);
                stage[0]=3;
            } else if(stage[0]==3 && sensor.output()==0 && port.status().equals("等待红石")) {
                sensor.configure(ProcessSensorBlockEntity.Measure.TEMPERATURE,270,280,false,false,first,second);
                port.configure(FluidPortBlockEntity.Mode.OUTPUT,FluidPortBlockEntity.Phase.LIQUID,50_000_000L,true);
                for(int i=0;i<box.getContainerSize();i++)box.setItem(i,MaterialBoxBlockEntity.newBatch(new ItemStack(Items.WATER_BUCKET)));
                stage[0]=4;
            } else if(stage[0]==4 && sensor.output()==15 && port.status().equals("等待可用批次或接触物料")) {
                box.setItem(0,MaterialBoxBlockEntity.newBatch(new ItemStack(mmm.item.MmmItems.MATERIAL_BUCKET)));
                stage[0]=5;
            } else if(stage[0]==5 && port.status().equals("运行")) {
                test.assertTrue(!mmm.item.MaterialBucketItem.isEmptyMaterialBucket(box.getItem(0)),"Output did not resume after freeing a box slot");
                port.configure(FluidPortBlockEntity.Mode.STOPPED,FluidPortBlockEntity.Phase.LIQUID,50_000_000L,true);
                test.setBlock(screenPosition,MmmBlocks.SCREEN.defaultBlockState().setValue(ScreenBlock.FACING,Direction.EAST));
                test.getBlockEntity(screenPosition,ScreenBlockEntity.class).configure(.0005,.4);
                stage[0]=6;
            } else if(stage[0]==6 && matchesScreen(region,test.absolutePos(screenPosition),level,.0005,.4)) {
                test.setBlock(screenPosition,MmmBlocks.SCREEN.defaultBlockState().setValue(ScreenBlock.FACING,Direction.NORTH));
                stage[0]=7;
            } else if(stage[0]==7 && matchesScreen(region,test.absolutePos(screenPosition),level,.0005,.4)) {
                test.setBlock(screenPosition,Blocks.AIR);
                stage[0]=8;
            } else if(stage[0]==8 && region.fixedGeometry().screens().isEmpty() && wallAt(region,thermal.fingerprint(),wall)!=null) {
                region.setPaused(true);
                long generation=region.solverGeneration();
                var candidate=region.beginHostEdit();
                var adjacent=wall.relative(Direction.EAST);
                int n=(int)Math.round(1.0/region.solverConfig().cellSize());
                int x=(adjacent.getX()-region.origin().getX())*n;
                int y=(adjacent.getY()-region.origin().getY())*n;
                int z=(adjacent.getZ()-region.origin().getZ())*n;
                for(int a=0;a<n;a++)for(int b=0;b<n;b++) {
                    int cell=region.grid().cell(x,y+a,z+b);
                    candidate.editGas(cell+1,"mmm:co2",100_000_000L,293.15,false);
                }
                region.commitHostEdit(generation,candidate);
                player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(MmmItems.CO2_ADSORBENT));
                test.assertTrue(runtime.finiteHeatExchangers().surface(player,InteractionHand.MAIN_HAND,wall,Direction.EAST,false)==InteractionResult.SUCCESS,
                        "Could not install surface material");
                test.assertTrue(player.getMainHandItem().isEmpty(),"Installed surface remains in the player's hand");
                region.setPaused(false);stage[0]=9;
            } else if(stage[0]==9 && wallAt(region,thermal.fingerprint(),wall).surfaces().stream()
                    .anyMatch(surface->surface.speciesQuanta().getOrDefault("mmm:co2",0L)>0)) {
                region.setPaused(true);
                var beforeInventory=region.finiteThermalDevices(thermal.fingerprint());
                var before=wallAt(region,thermal.fingerprint(),wall);
                var surface=before.surfaces().getFirst();
                test.assertTrue(runtime.finiteHeatExchangers().surface(player,InteractionHand.MAIN_HAND,wall,Direction.EAST,true)==InteractionResult.SUCCESS,
                        "Could not remove loaded surface");
                removed[0]=SurfaceCoatingContents.decode(player.getMainHandItem().get(MmmComponents.SURFACE_COATING));
                var after=wallAt(region,thermal.fingerprint(),wall);
                test.assertTrue(after.surfaces().isEmpty() && removed[0].surface().equals(surface),
                        "Removed adsorbates did not move to the item");
                var afterInventory=region.finiteThermalDevices(thermal.fingerprint());
                assertEnergyTransfer(test,beforeInventory,afterInventory,removed[0].internalEnergyJ(),true);
                test.assertTrue(runtime.finiteHeatExchangers().surface(player,InteractionHand.MAIN_HAND,wall,Direction.EAST,false)==InteractionResult.SUCCESS,
                        "Could not reinstall loaded surface");
                var reinstalled=wallAt(region,thermal.fingerprint(),wall);
                test.assertTrue(player.getMainHandItem().isEmpty() && reinstalled.surfaces().getFirst().equals(surface),
                        "Reinstalled adsorbates have the wrong owner");
                var reinstalledInventory=region.finiteThermalDevices(thermal.fingerprint());
                assertEnergyTransfer(test,afterInventory,reinstalledInventory,removed[0].internalEnergyJ(),false);
                var refill=new mmm.thermal.FixedSurfaceState(surface.face(),surface.materialId(),surface.loadingMassUnits(),java.util.Map.of());
                double refillEnergy=mmm.thermal.SurfaceThermalState.surfaceEnergyAtTemperature(thermal,refill,
                        thermal.surfaceMaterial(surface.materialId()).referenceTemperatureK());
                player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(MmmItems.CO2_ADSORBENT));
                runtime.finiteHeatExchangers().setFault(FiniteHeatExchangerOperations.FaultPoint.AFTER_HEAD);
                test.assertTrue(runtime.finiteHeatExchangers().surface(player,InteractionHand.MAIN_HAND,wall,Direction.EAST,false)==InteractionResult.FAIL,
                        "Surface HEAD interruption was not exercised");
                runtime.finiteHeatExchangers().recoverIndexed();
                var recoveredWall=wallAt(region,thermal.fingerprint(),wall);
                var recovered=recoveredWall.surfaces().getFirst();
                test.assertTrue(player.getMainHandItem().isEmpty()
                                && recovered.loadingMassUnits()==2*surface.loadingMassUnits()
                                && recovered.speciesQuanta().equals(surface.speciesQuanta()),
                        "Surface WAL recovery duplicated or lost the refill or adsorbates");
                var recoveredInventory=region.finiteThermalDevices(thermal.fingerprint());
                assertEnergyTransfer(test,reinstalledInventory,recoveredInventory,refillEnergy,false);
                runtime.worldUpdates().regions().select(region.id());
                long oldGeneration=region.checkpointGeneration();
                String result=runtime.regionAdministration().checkpointSelected();
                test.assertTrue(region.checkpointGeneration()>oldGeneration,"Surface checkpoint failed: "+result);
                test.assertTrue(region.releaseSolverAfterCheckpoint(region.checkpointGeneration()),"Surface solver did not unload");
                var restored=runtime.regionActivation().activate(player,test.absolutePos(controller),false);
                test.assertTrue(restored.succeeded() && region.solverLoaded(),"Surface checkpoint did not restore: "+restored.failure());
                var restoredWall=wallAt(region,thermal.fingerprint(),wall);
                test.assertTrue(restoredWall.surfaces().getFirst().equals(recovered)
                                && restoredWall.body().internalEnergyJ()==recoveredWall.body().internalEnergyJ(),
                        "Surface checkpoint changed the recovered inventory or energy");
                runtime.worldUpdates().regions().remove(region.id());
                test.succeed();stage[0]=10;
            }
        });
    }

    private static void assertEnergyTransfer(GameTestHelper test,mmm.thermal.FiniteThermalDeviceInventory before,
                                             mmm.thermal.FiniteThermalDeviceInventory after,double carried,boolean removed) {
        var energy=mmm.units.SignedDyadic.of(carried);
        var sourceChange=after.audit().externalSource().subtract(before.audit().externalSource());
        var sinkChange=after.audit().externalSink().subtract(before.audit().externalSink());
        test.assertTrue(sourceChange.equals(removed?mmm.units.SignedDyadic.ZERO:energy)
                        && sinkChange.equals(removed?energy:mmm.units.SignedDyadic.ZERO),
                "Surface item energy differs from the device transaction");
        test.assertTrue(after.audit().acceptsInventory(after.exactInternalEnergy()),
                "Surface transaction did not preserve the finite-device energy and roundoff ledger");
    }

    private static FiniteThermalDeviceState wallAt(FluidRegion region,String fingerprint,BlockPos position) {
        String prefix=position.asLong()+"@";
        return region.finiteThermalDevices(fingerprint).devices().stream()
                .filter(device->device.providerId().equals(OrdinaryThermalWalls.PROVIDER) && device.objectId().startsWith(prefix))
                .findFirst().orElse(null);
    }

    private static boolean matchesScreen(FluidRegion region,BlockPos position,net.minecraft.server.level.ServerLevel level,
                                         double pore,double openness) {
        var expected=IndustrialOperations.screenFaces(region,position,level.getBlockState(position));
        var actual=region.fixedGeometry().screens();
        if(expected.length==0 || actual.size()!=expected.length)return false;
        for(int face:expected)if(actual.stream().noneMatch(value->value.face()==face
                && value.poreDiameter()==pore && value.openFraction()==openness))return false;
        return true;
    }
}
