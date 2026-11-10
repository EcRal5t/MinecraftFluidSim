package mmm.simulation;

import java.util.UUID;
import mmm.block.MmmBlocks;
import mmm.block.ProcessSensorBlockEntity;
import mmm.network.ProcessTrendNetwork;
import mmm.network.ProcessTrendRequest;
import mmm.network.SampleAnalysisRequest;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Exercises request processing with two server players in a real server world. */
public final class MultiplayerObservationGameTest {
    @GameTest(structure="mmm_gametest:piston",maxTicks=100)
    public void independentObservers(GameTestHelper test) {
        var pos=new BlockPos(2,2,2); test.setBlock(pos,MmmBlocks.PROCESS_SENSOR);
        var absolute=test.absolutePos(pos); var sensor=test.getBlockEntity(pos,ProcessSensorBlockEntity.class);
        sensor.history().sample(0,1); sensor.history().sample(.05,2);
        var a=test.makeMockServerPlayerInLevel(); var b=test.makeMockServerPlayerInLevel();
        a.setPos(absolute.getX()+1,absolute.getY()+1,absolute.getZ()+1);
        b.setPos(absolute.getX()+2,absolute.getY()+1,absolute.getZ()+1);
        var dimension=test.getLevel().dimension().identifier().toString();
        var first=UUID.randomUUID(); var second=UUID.randomUUID();
        var requestA=new ProcessTrendRequest(absolute,dimension,first,0,true);
        var requestB=new ProcessTrendRequest(absolute,dimension,second,3,false);
        var responseA=ProcessTrendNetwork.response(a,requestA); var responseB=ProcessTrendNetwork.response(b,requestB);
        test.assertTrue(responseA.available() && responseB.available() && responseA.points().equals(responseB.points())
            && responseA.session().equals(first) && responseB.session().equals(second) && responseB.sequence()==3,
            "Concurrent observers did not receive independent sessions and shared samples");
        test.assertTrue(ProcessTrendNetwork.response(a,new ProcessTrendRequest(absolute,"minecraft:the_nether",first,0,true))==null,
            "Request crossed dimensions");
        b.setPos(absolute.getX()+40,absolute.getY(),absolute.getZ());
        test.assertTrue(!ProcessTrendNetwork.response(b,requestB).available(),"Distant observer retained access");
        a.setPos(absolute.getX(),absolute.getY()+8,absolute.getZ());
        b.setPos(absolute.getX()+1,absolute.getY()+8,absolute.getZ());
        a.setXRot(-90); b.setXRot(-90); a.setShiftKeyDown(true); b.setShiftKeyDown(true);
        a.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.WATER_BUCKET));
        b.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.WATER_BUCKET));
        var sampleA=SampleAnalysis.response(a,new SampleAnalysisRequest(first,dimension,InteractionHand.MAIN_HAND));
        var sampleB=SampleAnalysis.response(b,new SampleAnalysisRequest(second,dimension,InteractionHand.MAIN_HAND));
        test.assertTrue(sampleA!=null && sampleB!=null && sampleA.session().equals(first) && sampleB.session().equals(second),
            "Sample requests did not preserve each observer session");
        b.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.STONE));
        test.assertTrue(SampleAnalysis.response(b,new SampleAnalysisRequest(second,dimension,InteractionHand.MAIN_HAND))==null,
            "Unsupported held item was accepted");
        test.assertTrue(SampleAnalysis.response(a,new SampleAnalysisRequest(first,"minecraft:the_nether",InteractionHand.MAIN_HAND))==null,
            "Sample request crossed dimensions");
        test.succeed();
    }
}
