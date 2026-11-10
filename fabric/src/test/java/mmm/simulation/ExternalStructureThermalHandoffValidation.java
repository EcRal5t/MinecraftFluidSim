package mmm.simulation;

import java.util.List;
import java.util.UUID;
import mmm.api.motion.MovingStructureApi.*;
import mmm.thermal.EnvironmentThermalState;
import mmm.thermal.EnvironmentThermalState.*;
import mmm.fluid.air.GasInventory;
import net.minecraft.core.BlockPos;

/** World thermal inventory follows a structure without adding or losing energy. */
public final class ExternalStructureThermalHandoffValidation {
    private static final UUID ID=UUID.fromString("00000000-0000-0000-0000-000000000456");
    private static final String PROVIDER="test:structure";
    public static void main(String[] args) {
        var source=new BlockPos(1,2,3); var target=new BlockPos(4,2,3);
        var key=new Key(1,2,3,Kind.SOLID,0);
        var wall=new Node(key,"mmm:screen",1234,.125,GasInventory.EMPTY,98.125,List.of(),"X");
        var before=EnvironmentThermalState.initialized("test:world","a".repeat(64),List.of(wall),List.of());
        check(!ExternalStructureThermalHandoff.hasCarried(before,PROVIDER,ID),"fixed wall reported as carried");
        var assembled=ExternalStructureThermalHandoff.plan(before,List.of(item(HandoffKind.ASSEMBLE,source)),b->null);
        check(ExternalStructureThermalHandoff.hasCarried(assembled,PROVIDER,ID),"carried wall missing");
        check(assembled.exactEnergy().equals(before.exactEnergy()) && assembled.audit().equals(before.audit()),"assembly changed energy");
        try { ExternalStructureThermalHandoff.plan(assembled,List.of(item(HandoffKind.DISASSEMBLE,target)),b->"Y");
            throw new AssertionError("changed screen axis accepted"); } catch(IllegalArgumentException expected) { }
        var restored=ExternalStructureThermalHandoff.plan(assembled,List.of(item(HandoffKind.DISASSEMBLE,target)),b->"X");
        check(!ExternalStructureThermalHandoff.hasCarried(restored,PROVIDER,ID),"restored wall remained carried");
        check(restored.nodes().getFirst().key().equals(new Key(4,2,3,Kind.SOLID,0)) && restored.nodes().getFirst().owner().equals("X") && restored.exactEnergy().equals(before.exactEnergy())
                && restored.audit().equals(before.audit()),"disassembly changed inventory");
        System.out.println("ExternalStructureThermalHandoffValidation passed");
    }
    private static ExternalMovingStructures.CompletedHandoff item(HandoffKind kind,BlockPos pos) {
        var pose=new Pose(0,0,0,true);
        var structure=new Structure(ID,null,1,pose,pose,.05,List.of(new Piece("block",new Box(0,0,0,1,1,1))),false);
        return new ExternalMovingStructures.CompletedHandoff(PROVIDER,new Handoff(kind,structure,List.of(new FixedBlock("block",pos.getX(),pos.getY(),pos.getZ(),"mmm:screen"))));
    }
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
}
