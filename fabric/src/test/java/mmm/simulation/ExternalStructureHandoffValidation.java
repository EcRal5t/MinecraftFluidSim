package mmm.simulation;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import mmm.api.motion.MovingStructureApi;
import mmm.api.motion.MovingStructureApi.Box;
import mmm.api.motion.MovingStructureApi.FixedBlock;
import mmm.api.motion.MovingStructureApi.Handoff;
import mmm.api.motion.MovingStructureApi.HandoffKind;
import mmm.api.motion.MovingStructureApi.Piece;
import mmm.api.motion.MovingStructureApi.Pose;
import mmm.api.motion.MovingStructureApi.Structure;
import mmm.fluid.FluidMaterialRegistry;
import mmm.fluid.FluidSolverConfig;
import mmm.fluid.HostFluidState;
import mmm.world.CollisionField;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;

/** Host-level fixed/moving structure handoff with source-world evidence. */
public final class ExternalStructureHandoffValidation {
    private static final String DIMENSION="test:handoff";
    private static final String PROVIDER="test:structure";
    private static final UUID STRUCTURE=UUID.fromString("00000000-0000-0000-0000-000000000201");
    private static final UUID ENTITY=UUID.fromString("00000000-0000-0000-0000-000000000202");
    private static final Pose ANCHOR=new Pose(11,65,-3,true);
    private static final FixedBlock BLOCK=new FixedBlock("first",11,65,-3,"minecraft:stone");
    private static final List<Piece> MICROS=List.of(
            new Piece("left",new Box(0,0,0,.25,.25,.25)),
            new Piece("right",new Box(.25,0,0,.5,.25,.25)));
    private static final class Source implements MovingStructureApi.Provider {
        Structure structure;
        Handoff handoff;
        @Override public List<Structure> sample(String dimension) {
            return DIMENSION.equals(dimension) && structure!=null ? List.of(structure) : List.of();
        }
        @Override public List<Handoff> handoffs(String dimension) {
            return DIMENSION.equals(dimension) && handoff!=null ? List.of(handoff) : List.of();
        }
        void set(HandoffKind kind,long revision,List<Piece> pieces,List<FixedBlock> blocks) {
            structure=new Structure(STRUCTURE,ENTITY,revision,ANCHOR,ANCHOR,.05,pieces,false);
            handoff=new Handoff(kind,structure,blocks);
        }
    }
    private record Fixture(FluidRegion region, ExternalMovingStructures bridge, Source source) { }
    private record Snapshot(long generation, byte[] masks, mmm.fluid.EntityBodyState bodies,
                            long mass, double volume, double energy) { }
    private ExternalStructureHandoffValidation() { }
    public static void main(String[] args) {
        microPiecesAndUnrelatedBits();
        halfBlock();
        equalVolumeWrongPosition();
        misalignedAndOverlappingPieces();
        unavailableAndIncompleteWorld();
        restartAndMissingProvider();
        movementAndRestoredProgress();
        completeBodyBudget();
        System.out.println("ExternalStructureHandoffValidation passed");
    }
    private static Fixture fixture(int maskAtFirst,int maskAtSecond) {
        var config=new FluidSolverConfig(.5f,9.81f,.05f,48,1e-4f,.002f);
        var definitions=mmm.fluid.TestFluidDefinitions.load();
        var host=new HostFluidState(8,8,8,config,definitions);
        var fluids=new FluidMaterialRegistry(List.of(definitions.material().entry("mmm:water").material()));
        host.bindMaterialRegistry(fluids);host.bindMaterialDefinitions(definitions.material());
        if(maskAtFirst!=0)host.setSolidMicroMask(2,2,2,maskAtFirst);
        if(maskAtSecond!=0)host.setSolidMicroMask(4,2,2,maskAtSecond);
        host.setVolume(1,1,1,.4f);host.sealDomainBoundary();
        host.bindGasRegistry(definitions.gas());host.bindThermal(definitions.thermal(),fluids,300);
        var dimension=ResourceKey.create(Registries.DIMENSION,Identifier.parse(DIMENSION));
        var region=new FluidRegion(UUID.randomUUID(),dimension,new BlockPos(10,64,-4),host);
        var bridge=new ExternalMovingStructures(() -> true);var source=new Source();bridge.register(PROVIDER,source);
        return new Fixture(region,bridge,source);
    }
    private static CollisionField observed(Fixture f,int first,int second) {
        byte[] masks=f.region.fixedGeometry().copyMasks();
        masks[f.region.grid().cell(2,2,2)]=(byte)first;
        masks[f.region.grid().cell(4,2,2)]=(byte)second;
        return new CollisionField(f.region.grid(),masks);
    }
    private static void apply(Fixture f,CollisionField field,Predicate<FixedBlock> available,
                              Function<FixedBlock,String> blockIdAt) {
        FluidRegionWorldInteraction.applyCapturedCollisions(f.region,field,2,f.bridge,available,blockIdAt);
    }
    private static void microPiecesAndUnrelatedBits() {
        var f=fixture(131,0);var initial=snapshot(f.region);
        f.source.set(HandoffKind.ASSEMBLE,1,MICROS,List.of(BLOCK));
        apply(f,observed(f,128,0),block -> true,block -> "minecraft:air");
        check(f.region.solidMicroMask(2,2,2)==128,"assembly changed unrelated fixed microcell");
        check(f.region.entityBodyState().bodies().size()==2,"assembly lost a moving piece");
        conserved(f.region,initial,"assembly");
        long generation=f.region.solverGeneration();var bodies=f.region.entityBodyState();
        f.source.set(HandoffKind.ASSEMBLE,1,List.of(MICROS.get(1),MICROS.get(0)),List.of(BLOCK));
        apply(f,observed(f,128,0),block -> true,block -> "minecraft:air");
        check(f.region.solverGeneration()==generation && f.region.entityBodyState().equals(bodies),"reordered assembly replay changed state");
        f.source.set(HandoffKind.DISASSEMBLE,2,MICROS,List.of(BLOCK));
        apply(f,observed(f,131,0),block -> true,FixedBlock::blockId);
        check(f.region.solidMicroMask(2,2,2)==131 && f.region.entityBodyState().bodies().isEmpty(),"disassembly lost fixed shape");
        conserved(f.region,initial,"disassembly");
        generation=f.region.solverGeneration();
        apply(f,observed(f,131,0),block -> true,FixedBlock::blockId);
        check(f.region.solverGeneration()==generation,"disassembly replay changed state");
    }
    private static void halfBlock() {
        var f=fixture(0,0);var host=f.region.editableHost();
        for(int x=2;x<=3;x++)for(int z=2;z<=3;z++)host.setSolidMicroMask(x,2,z,255);
        var initial=snapshot(f.region);
        var half=List.of(new Piece("half",new Box(0,0,0,1,.5,1)));
        f.source.set(HandoffKind.ASSEMBLE,1,half,List.of(BLOCK));
        byte[] masks=f.region.fixedGeometry().copyMasks();
        for(int x=2;x<=3;x++)for(int z=2;z<=3;z++)masks[f.region.grid().cell(x,2,z)]=0;
        apply(f,new CollisionField(f.region.grid(),masks),block -> true,block -> "minecraft:air");
        check(f.region.entityBodyState().bodies().size()==1,"half-block assembly lost its body");
        conserved(f.region,initial,"half-block assembly");
        f.source.set(HandoffKind.DISASSEMBLE,2,half,List.of(BLOCK));
        for(int x=2;x<=3;x++)for(int z=2;z<=3;z++)masks[f.region.grid().cell(x,2,z)]=(byte)255;
        apply(f,new CollisionField(f.region.grid(),masks),block -> true,FixedBlock::blockId);
        check(f.region.entityBodyState().bodies().isEmpty(),"half-block disassembly retained a body");
        check(Arrays.equals(initial.masks(),f.region.fixedGeometry().copyMasks()),"half-block disassembly changed fixed geometry");
        conserved(f.region,initial,"half-block disassembly");
    }
    private static void equalVolumeWrongPosition() {
        var f=fixture(131,0);var before=snapshot(f.region);
        f.source.set(HandoffKind.ASSEMBLE,1,MICROS,List.of(BLOCK));
        expectRejected(f,observed(f,2,0),block -> true,block -> "minecraft:air",before,"equal-volume misplaced microcells");
    }
    private static void misalignedAndOverlappingPieces() {
        var f=fixture(131,0);var before=snapshot(f.region);
        var misaligned=List.of(new Piece("skew",new Box(0,0,0,.26,.25,.25)));
        f.source.set(HandoffKind.ASSEMBLE,1,misaligned,List.of(BLOCK));
        expectRejected(f,observed(f,128,0),block -> true,block -> "minecraft:air",before,"non-micro-aligned piece");
        var overlapping=List.of(MICROS.getFirst(),new Piece("overlap",MICROS.getFirst().bounds()));
        f.source.set(HandoffKind.ASSEMBLE,2,overlapping,List.of(BLOCK));
        expectRejected(f,observed(f,128,0),block -> true,block -> "minecraft:air",before,"overlapping pieces");
    }
    private static void unavailableAndIncompleteWorld() {
        var f=fixture(129,1);var before=snapshot(f.region);
        var second=new FixedBlock("second",12,65,-3,"minecraft:stone");
        var pieces=List.of(MICROS.getFirst(),new Piece("second-piece",new Box(1,0,0,1.25,.25,.25)));
        f.source.set(HandoffKind.ASSEMBLE,1,pieces,List.of(BLOCK,second));
        expectRejected(f,observed(f,128,0),block -> !block.key().equals("second"),block -> "minecraft:air",before,"unloaded block");
        expectRejected(f,observed(f,128,1),block -> true,
                block -> block.key().equals("second") ? "minecraft:stone" : "minecraft:air",before,"partially completed world removal");
        var pending=f.source.handoff;
        var replacement=new FixedBlock("replacement",13,65,-3,"minecraft:stone");
        f.source.set(HandoffKind.ASSEMBLE,2,pieces,List.of(BLOCK,replacement));
        expectRejected(f,observed(f,128,1),block -> true,
                block -> block.key().equals("second") ? "minecraft:stone" : "minecraft:air",before,"changed pending handoff");
        f.source.structure=pending.structure();
        f.source.handoff=null;
        expectRejected(f,observed(f,128,1),block -> true,
                block -> block.key().equals("second") ? "minecraft:stone" : "minecraft:air",before,"missing pending handoff frame");
        f.source.handoff=pending;
        apply(f,observed(f,128,0),block -> true,block -> "minecraft:air");
        check(f.region.solidMicroMask(2,2,2)==128 && f.region.solidMicroMask(4,2,2)==0
                && f.region.entityBodyState().bodies().size()==2,"completed pending handoff did not assemble both blocks");
        conserved(f.region,before,"completed pending handoff");
        long generation=f.region.solverGeneration();
        f.source.set(HandoffKind.ASSEMBLE,1,List.of(pieces.get(1),pieces.get(0)),List.of(second,BLOCK));
        apply(f,observed(f,128,0),block -> true,block -> "minecraft:air");
        check(f.region.solverGeneration()==generation,"reordered blocks and pieces changed replay generation");
    }
    private static void restartAndMissingProvider() {
        var f=fixture(131,0);f.source.set(HandoffKind.ASSEMBLE,1,MICROS,List.of(BLOCK));
        apply(f,observed(f,128,0),block -> true,block -> "minecraft:air");
        var saved=snapshot(f.region);
        var unavailable=new ExternalMovingStructures(() -> true);
        try {
            FluidRegionWorldInteraction.applyCapturedCollisions(f.region,observed(f,128,0),2,unavailable,block -> true,block -> "minecraft:air");
            throw new AssertionError("missing provider allowed world refresh");
        } catch(ExternalMovingStructures.InputRejectedException expected) { }
        unchanged(f.region,saved,"missing provider after restart");
        var restarted=new ExternalMovingStructures(() -> true);restarted.register(PROVIDER,f.source);
        FluidRegionWorldInteraction.applyCapturedCollisions(f.region,observed(f,128,0),2,restarted,block -> true,block -> "minecraft:air");
        unchanged(f.region,saved,"restored assembly replay");
        f.source.set(HandoffKind.DISASSEMBLE,2,MICROS,List.of(BLOCK));
        FluidRegionWorldInteraction.applyCapturedCollisions(f.region,observed(f,131,0),2,restarted,block -> true,FixedBlock::blockId);
        check(f.region.entityBodyState().bodies().isEmpty() && f.region.solidMicroMask(2,2,2)==131,
                "restored structure could not disassemble");
        conserved(f.region,saved,"restored disassembly");
    }
    private static void movementAndRestoredProgress() {
        var f=fixture(131,0);
        f.source.set(HandoffKind.ASSEMBLE,1,MICROS,List.of(BLOCK));
        apply(f,observed(f,128,0),block -> true,block -> "minecraft:air");
        var assembly=f.source.handoff;
        var target=new Pose(12,65,-3,true);
        f.source.structure=new Structure(STRUCTURE,ENTITY,2,ANCHOR,target,.1,MICROS,false);
        f.source.handoff=null;
        commitMotion(f.bridge,f.region);
        var halfway=snapshot(f.region);
        var destination=new FixedBlock("destination",12,65,-3,"minecraft:stone");
        f.source.handoff=new Handoff(HandoffKind.DISASSEMBLE,
                new Structure(STRUCTURE,ENTITY,3,target,target,.05,MICROS,false),List.of(destination));
        expectRejected(f,observed(f,128,3),block -> true,FixedBlock::blockId,halfway,"disassembly before motion endpoint");

        // A fresh bridge sees the saved moving bodies, assembly intent, and current motion frame.
        f.source.handoff=assembly;
        var restored=new ExternalMovingStructures(() -> true);
        restored.register(PROVIDER,f.source);
        FluidRegionWorldInteraction.applyCapturedCollisions(f.region,observed(f,128,0),2,restored,
                block -> true,block -> "minecraft:air");
        unchanged(f.region,halfway,"restored assembly intent");
        var next=restored.capture(f.region);
        check(next.target().stream().allMatch(body -> Math.abs(body.bounds().minimumX()-2)<.26),
                "restored motion did not advance from saved body position");
        f.region.editableHost().restoreEntityBodyState(new mmm.fluid.EntityBodyState(next.target()));
        restored.accepted(f.region.id());
        var progress=restored.status(PROVIDER,f.region.id()).structures().get(STRUCTURE);
        check(progress!=null && progress.revision()==2 && Math.abs(progress.fraction()-1)<1e-6
                && Math.abs(progress.accepted().x()-12)<1e-6,"restored motion did not reach its endpoint");
        f.source.structure=null;
        f.source.handoff=new Handoff(HandoffKind.DISASSEMBLE,
                new Structure(STRUCTURE,ENTITY,3,target,target,.05,MICROS,false),List.of(destination));
        FluidRegionWorldInteraction.applyCapturedCollisions(f.region,observed(f,128,3),2,restored,
                block -> true,FixedBlock::blockId);
        check(f.region.entityBodyState().bodies().isEmpty() && f.region.solidMicroMask(4,2,2)==3,
                "moved structure did not return to fixed geometry at its destination");
        conserved(f.region,halfway,"moving structure disassembly");
        check(restored.capture(f.region).target().isEmpty(),"disassembled structure remained in motion capture");
        restored.discarded(f.region.id());
    }
    private static void commitMotion(ExternalMovingStructures bridge,FluidRegion region) {
        var captured=bridge.capture(region);
        region.editableHost().restoreEntityBodyState(new mmm.fluid.EntityBodyState(captured.target()));
        bridge.accepted(region.id());
    }
    private static void completeBodyBudget() {
        var f=fixture(131,0);
        var bodies=new java.util.ArrayList<mmm.world.MovingBodyFluidCoupling.Request>();
        var bounds=new mmm.world.MovingBodyFluidCoupling.Bounds(0,0,0,.1,.1,.1);
        for(int i=0;i<255;i++) bodies.add(new mmm.world.MovingBodyFluidCoupling.Request(new UUID(1,i+1),bounds,0,0,0));
        f.region.editableHost().restoreEntityBodyState(new mmm.fluid.EntityBodyState(bodies));
        var before=snapshot(f.region);
        f.source.set(HandoffKind.ASSEMBLE,1,MICROS,List.of(BLOCK));
        expectRejected(f,observed(f,128,0),block -> true,block -> "minecraft:air",before,"complete body budget");
    }
    private static Snapshot snapshot(FluidRegion region) {
        var host=region.editableHost();
        return new Snapshot(region.solverGeneration(),region.fixedGeometry().copyMasks(),region.entityBodyState(),
                host.totalMassUnits(),host.totalVolumeExact(),host.totalInternalEnergy());
    }
    private static void conserved(FluidRegion region,Snapshot before,String operation) {
        var host=region.editableHost();
        check(host.totalMassUnits()==before.mass() && host.totalVolumeExact()==before.volume()
                && Double.doubleToRawLongBits(host.totalInternalEnergy())==Double.doubleToRawLongBits(before.energy()),
                operation+" changed mass, volume or internal energy");
    }
    private static void unchanged(FluidRegion region,Snapshot before,String operation) {
        check(region.solverGeneration()==before.generation() && Arrays.equals(region.fixedGeometry().copyMasks(),before.masks())
                && region.entityBodyState().equals(before.bodies()),operation+" changed committed geometry or generation");
        conserved(region,before,operation);
    }
    private static void expectRejected(Fixture f,CollisionField field,Predicate<FixedBlock> available,
                                       Function<FixedBlock,String> blockIdAt,Snapshot before,String description) {
        try { apply(f,field,available,blockIdAt); throw new AssertionError(description+" was accepted"); }
        catch(ExternalMovingStructures.InputRejectedException expected) { }
        unchanged(f.region,before,description);
    }
    private static void check(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
}
