package mmm.simulation;

import java.util.*;
import mmm.block.*;
import mmm.fluid.TestFluidDefinitions;
import mmm.inventory.LiquidCellInventory;
import mmm.pipe.*;
import mmm.thermal.GasThermalModel;
import mmm.units.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

public final class PipeTopologyValidation {
    public static void main(String[] args) throws Exception {
        GpuControllerFixture.initializeItems();GpuControllerFixture.initializeIndustrialBlocks();
        run();PipeRegionExchangeValidation.validate();
        System.out.println("Pipe topology and finite region exchange passed");
    }
    static void run() {
        var defs=TestFluidDefinitions.load();var gasModel=new GasThermalModel(defs.thermal(),defs.gas());
        var first=new BlockPos(0,64,0);var second=first.east();
        var state=MmmBlocks.METAL_PIPE.defaultBlockState();
        for(var face:Direction.values())state=state.setValue(PipeBlock.port(face),face.getAxis()==Direction.Axis.X?PipeBlock.Port.CONNECTED:PipeBlock.Port.SEALED);
        double capacity=PipeWorldGraph.capacity(state);var gas=gasModel.inventoryAtPressure(capacity,101325,325,defs.gas().ambient());
        var air=new PipeTopologyEdit.Air(gas,gasModel.internalEnergyAtTemperature(gas,325));
        var created=PipeTopologyEdit.plan(null,Map.of(first,state,second,state),Map.of(first,PipeTopologyEdit.Contents.air(air),second,PipeTopologyEdit.Contents.air(air)),p->true,p->0,p->1,defs).state();
        require(created.network().connections().size()==1,"adjacent opposing ports connect");
        require(created.network().segments().stream().mapToLong(s->s.gas().totalMassUnits()).sum()==2*gas.totalMassUnits(),"acquired gas retained");
        for(var installation:created.installations().values())require(PipeWorldGraph.installation(PipeWorldGraph.state(installation)).equals(installation),"accepted geometry stable roundtrip");
        var disconnected=PipeTopologyEdit.plan(created,Map.of(first,state,second,state),Map.of(),p->p.equals(first),p->0,p->1,defs).state();
        require(disconnected.network().connections().isEmpty(),"unloaded segment blocks exchange");
        require(disconnected.network().segments().get(1).gas().equals(created.network().segments().get(1).gas()),"unloaded inventory retained");
        var removed=PipeTopologyEdit.plan(created,Map.of(first,state),Map.of(),p->true,p->0,p->1,defs);
        require(removed.returnedAir().get(second).inventory().equals(gas),"removed cavity returns exact gas");
        var firstRow=new LiquidCellInventory.Stock(new LiquidCellInventory.Key(0,0,255),SignedDyadic.of(.0625),Mass.fromKilograms(62.5),null,0);
        var occupied=new PipeDimensionState(created.generation(),new PipeNetworkState(created.network().segments(),created.network().connections(),
                List.of("mmm:water"),new LiquidCellInventory.Snapshot(List.of(List.of(firstRow),List.of()))),created.audit(),created.installations());
        require(!PipeRuntime.allowsBlockChange(occupied,first,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()),
                "filled pipe must reject block destruction before drops");
        require(PipeRuntime.allowsBlockChange(occupied,second,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()),
                "gas-only pipe must permit removal and air return");
        try { PipeTopologyEdit.plan(occupied,Map.of(second,state),Map.of(),p->true,p->0,p->1,defs);throw new AssertionError("filled pipe removal accepted"); }
        catch(IllegalArgumentException expected) { }
        var ledger=removed.state().audit();
        var expected=ledger.initialEnergy().add(ledger.suppliedEnergy()).subtract(ledger.removedEnergy()).add(ledger.externalWork())
                .add(ledger.environmentHeat()).add(ledger.roundoff());
        require(PipeNetworkSolver.energy(removed.state().network(),defs,9.81).equals(expected),"topology energy ledger closes");
    }
    private static void require(boolean value,String message) { if(!value)throw new AssertionError(message); }
}
