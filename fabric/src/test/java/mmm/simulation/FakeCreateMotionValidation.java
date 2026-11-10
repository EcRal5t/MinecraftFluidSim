package mmm.simulation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import mmm.api.motion.MovingStructureApi;
import mmm.api.motion.MovingStructureApi.Box;
import mmm.api.motion.MovingStructureApi.Piece;
import mmm.api.motion.MovingStructureApi.Pose;
import mmm.api.motion.MovingStructureApi.State;
import mmm.api.motion.MovingStructureApi.Structure;
import mmm.fluid.EntityBodyState;
import mmm.fluid.FluidSolverConfig;
import mmm.fluid.HostFluidState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Create-shaped test source feeding the actual loader-neutral motion interface. */
public final class FakeCreateMotionValidation {
    private static final String PROVIDER="test:fake_create";
    private static final String DIMENSION="test:factory";
    private static final UUID STRUCTURE=UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID ENTITY=UUID.fromString("00000000-0000-0000-0000-000000000102");
    private static final double DT=.05;
    private static final List<Piece> PIECES=List.of(
            new Piece("0,0,0/a",new Box(0,0,0,.04,.04,.04)),
            new Piece("0,0,0/b",new Box(0,.1,0,.04,.14,.04)));
    private static final class FakeCreateSource implements MovingStructureApi.Provider {
        Structure frame;
        boolean missing, failing;
        Structure extra;
        @Override public List<Structure> sample(String dimensionId) {
            if(failing)throw new IllegalStateException("provider unavailable");
            if(!DIMENSION.equals(dimensionId) || missing)return List.of();
            return extra==null?List.of(frame):List.of(frame,extra);
        }
        void segment(long revision,double start,double distance,int ticks,List<Piece> pieces) {
            frame=new Structure(STRUCTURE,ENTITY,revision,pose(start),pose(start+distance),ticks*DT,pieces,false);
        }
    }
    private record Fixture(ExternalMovingStructures bridge,FluidRegion region,FakeCreateSource source) { }
    private FakeCreateMotionValidation() { }
    public static void main(String[] args) {
        for(int ticks:new int[]{1,2,5}) sameSpeedAtDifferentIntervals(ticks);
        earlySegmentWaitsAndRetryRetainsProgress();
        outsideSegmentAdvancesBeforeEntry();
        outsideEndpointsStillSweepRegion();
        rejectsRotationAndDiscontinuity();
        checkpointAndProviderFailures();
        bodyBudgetAndUnavailableSweep();
        retireAndStaleRevision();
        completedAndMultipleStructuresReportTruthfully();
        System.out.println("FakeCreateMotionValidation passed");
    }
    private static Fixture fixture() {
        var dimension=ResourceKey.create(Registries.DIMENSION,Identifier.parse(DIMENSION));
        var config=new FluidSolverConfig(.5f,9.81f,.05f,32,1e-4f,.002f);
        var region=new FluidRegion(UUID.randomUUID(),dimension,new BlockPos(10,64,-4),
                new HostFluidState(8,8,8,config,mmm.fluid.TestFluidDefinitions.load()));
        var bridge=new ExternalMovingStructures(() -> true);
        var source=new FakeCreateSource(); bridge.register(PROVIDER,source);
        return new Fixture(bridge,region,source);
    }
    private static Pose pose(double x) { return new Pose(x,64.5,-3.5,true); }
    private static void sameSpeedAtDifferentIntervals(int ticks) {
        var f=fixture(); double start=9.96; f.source.segment(1,start,ticks*DT,ticks,PIECES);
        var first=f.bridge.capture(f.region);
        check(first.excludedEntities().contains(ENTITY),"entity was not excluded from ordinary entity processing");
        check(first.target().size()==2,"pieces were not emitted at entry");
        var ids=first.target().stream().map(body -> body.bodyId()).collect(java.util.stream.Collectors.toSet());
        check(ids.size()==2,"distinct pieces share an identity");
        for(var body:first.target())check(close(body.velocityXMetresPerSecond(),1),"first-step velocity changed with segment duration");
        f.bridge.discarded(f.region.id());
        check(progress(f)==null,"discarded first candidate became an accepted segment");
        var retry=f.bridge.capture(f.region);
        check(retry.target().equals(first.target()),"retry did not reproduce unaccepted motion");
        commit(f,retry);
        for(int step=1;step<ticks;step++) {
            var reversed=new ArrayList<>(PIECES); java.util.Collections.reverse(reversed);
            f.source.segment(1,start,ticks*DT,ticks,reversed);
            var next=f.bridge.capture(f.region);
            check(next.target().stream().map(body -> body.bodyId()).collect(java.util.stream.Collectors.toSet()).equals(ids),
                    "piece traversal order changed identity");
            for(var body:next.target()) {
                check(close(body.velocityXMetresPerSecond(),1),"step velocity changed with sampling interval");
                check(close(body.bounds().minimumX(),start+(step+1)*DT-10),"world-to-region position changed");
            }
            commit(f,next);
        }
        var progress=progress(f);
        check(close(progress.fraction(),1) && close(progress.accepted().x(),start+ticks*DT),"segment endpoint differs");
    }
    private static void earlySegmentWaitsAndRetryRetainsProgress() {
        var f=fixture(); double start=9.96;
        f.source.segment(1,start,.25,5,PIECES);
        var first=f.bridge.capture(f.region); commit(f,first);
        f.source.segment(2,start+.25,-.05,1,PIECES);
        var waiting=f.bridge.capture(f.region);
        check(f.bridge.status(PROVIDER,f.region.id()).state()==State.BUSY,"early next segment was not reported busy");
        check(progress(f).revision()==1 && close(progress(f).fraction(),.2),"early revision replaced accepted progress");
        f.bridge.discarded(f.region.id());
        check(close(progress(f).fraction(),.2),"rejected motion advanced old segment");
        for(int i=1;i<5;i++)commit(f,f.bridge.capture(f.region));
        check(close(progress(f).accepted().x(),start+.25),"earlier segment did not reach its endpoint");
        var reverse=f.bridge.capture(f.region);
        check(reverse.target().stream().allMatch(body -> close(body.velocityXMetresPerSecond(),-1)),"next segment did not reverse at the accepted endpoint");
        commit(f,reverse);
        check(progress(f).revision()==2 && close(progress(f).accepted().x(),start+.20),"reverse segment ended at the wrong pose");
    }
    private static void outsideSegmentAdvancesBeforeEntry() {
        var f=fixture(); f.source.segment(1,9.70,.35,7,PIECES);
        for(int i=0;i<5;i++) {
            var outside=f.bridge.capture(f.region);
            check(outside.target().isEmpty(),"body was created before approaching the region");
            commit(f,outside);
        }
        check(progress(f).fraction()>0,"outside segment never advanced toward the region");
        boolean entered=false;
        for(int i=5;i<7;i++) { var next=f.bridge.capture(f.region); entered|=!next.target().isEmpty(); commit(f,next); }
        check(entered && close(progress(f).accepted().x(),10.05),"outside source did not reach the region");
    }
    private static void outsideEndpointsStillSweepRegion() {
        var f=fixture(); f.source.segment(1,9,6,1,PIECES);
        var crossing=f.bridge.capture(f.region);
        check(crossing.source().size()==2 && crossing.target().size()==2,
                "motion crossing the region was skipped because both endpoints were outside");
        check(crossing.target().stream().allMatch(body -> close(body.velocityXMetresPerSecond(),120)),
                "crossing velocity did not use its segment duration");
        commit(f,crossing);
        check(close(progress(f).accepted().x(),15),"crossing target was not accepted");
    }
    private static void rejectsRotationAndDiscontinuity() {
        var f=fixture(); double start=9.96; f.source.segment(1,start,.05,1,PIECES);
        commit(f,f.bridge.capture(f.region));
        f.source.segment(2,start+.2,.05,1,PIECES);
        try { f.bridge.capture(f.region); throw new AssertionError("discontinuous segment accepted"); }
        catch(IllegalStateException expected) { }
        check(progress(f).revision()==1 && close(progress(f).accepted().x(),start+.05),"invalid segment changed accepted pose");
        f.source.frame=new Structure(STRUCTURE,ENTITY,2,pose(start+.05),new Pose(start+.1,64.5,-3.5,false),DT,PIECES,false);
        try { f.bridge.capture(f.region); throw new AssertionError("unsupported rotation accepted"); }
        catch(IllegalStateException expected) { }
        check(progress(f).revision()==1,"rotation changed accepted progress");
    }
    private static void checkpointAndProviderFailures() {
        var f=fixture(); f.source.segment(1,9.96,.25,5,PIECES);
        commit(f,f.bridge.capture(f.region));
        var saved=f.region.entityBodyState();
        var unregistered=new ExternalMovingStructures(() -> true);
        try { unregistered.capture(f.region); throw new AssertionError("saved structure accepted without provider"); }
        catch(IllegalStateException expected) { }
        check(f.region.entityBodyState().equals(saved),"missing provider removed saved bodies");
        check(unregistered.status(PROVIDER,f.region.id()).state()==State.REJECTED,"missing provider was not reported");
        var restored=new ExternalMovingStructures(() -> true);
        restored.register(PROVIDER,f.source);
        var identified=restored.capture(f.region);
        check(identified.excludedEntities().contains(ENTITY),"restored provider did not exclude its generic entity");
        commit(new Fixture(restored,f.region,f.source),identified);
        saved=f.region.entityBodyState();
        f.source.failing=true;
        var failed=restored.capture(f.region);
        check(failed.excludedEntities().contains(ENTITY) && sameBodies(saved,failed.target()),
                "provider failure lost saved shape or generic entity exclusion");
        check(restored.status(PROVIDER,f.region.id()).state()==State.REJECTED,"provider exception was not reported");
        restored.discarded(f.region.id());
        f.source.failing=false; f.source.missing=true;
        var missing=restored.capture(f.region);
        check(missing.excludedEntities().contains(ENTITY) && sameBodies(saved,missing.target()),
                "missing frame lost saved shape or generic entity exclusion");
        check(f.region.entityBodyState().equals(saved),"missing frame changed accepted bodies");
        restored.discarded(f.region.id());
        f.source.missing=false;
        var recovered=restored.capture(f.region);
        check(recovered.excludedEntities().contains(ENTITY),"recovered provider did not exclude its generic entity");
        restored.discarded(f.region.id());
    }
    private static boolean sameBodies(EntityBodyState saved,List<mmm.world.MovingBodyFluidCoupling.Request> actual) {
        return saved.bodies().size()==actual.size() && saved.bodies().stream().allMatch(before -> actual.stream()
                .anyMatch(after -> after.bodyId().equals(before.bodyId()) && after.bounds().equals(before.bounds())));
    }
    private static void bodyBudgetAndUnavailableSweep() {
        var budget=fixture(); budget.source.segment(1,9.96,.05,1,PIECES);
        var candidate=budget.bridge.capture(budget.region);
        var ordinary=new ArrayList<mmm.world.MovingBodyFluidCoupling.Request>();
        var bounds=new mmm.world.MovingBodyFluidCoupling.Bounds(0,0,0,.1,.1,.1);
        for(int i=0;i<255;i++) ordinary.add(new mmm.world.MovingBodyFluidCoupling.Request(new UUID(1,i+1),bounds,0,0,0));
        try {
            MinecraftRegionInputs.integrateExternal(budget.region,candidate,ordinary,ignored -> true);
            throw new AssertionError("ordinary plus external bodies exceeded the 256-body budget");
        } catch(RuntimeException expected) { budget.bridge.rejected(budget.region.id(),expected.getMessage()); }
        check(budget.region.entityBodyState().bodies().isEmpty() && progress(budget)==null,
                "body budget failure advanced part of a structure");
        check(budget.bridge.status(PROVIDER,budget.region.id()).state()==State.REJECTED,"body budget failure was not reported");
        var retry=budget.bridge.capture(budget.region);
        check(retry.target().equals(candidate.target()),"body budget retry changed unaccepted motion");
        budget.bridge.discarded(budget.region.id());
        var corrected=fixture(); corrected.source.segment(1,9.96,.05,1,PIECES);
        var overBudget=corrected.bridge.capture(corrected.region);
        var crowded=new ArrayList<mmm.world.MovingBodyFluidCoupling.Request>();
        for(int i=0;i<255;i++) crowded.add(new mmm.world.MovingBodyFluidCoupling.Request(new UUID(2,i+1),bounds,0,0,0));
        try {
            MinecraftRegionInputs.integrateExternal(corrected.region,overBudget,crowded,ignored -> true);
            throw new AssertionError("over-budget first segment was accepted");
        } catch(RuntimeException expected) { corrected.bridge.rejected(corrected.region.id(),expected.getMessage()); }
        corrected.source.segment(2,9.96,.05,1,PIECES);
        var replacement=corrected.bridge.capture(corrected.region);
        check(corrected.bridge.status(PROVIDER,corrected.region.id()).state()!=State.BUSY,
                "rejected first segment blocked its corrected revision");
        var room=new ArrayList<mmm.world.MovingBodyFluidCoupling.Request>();
        for(int i=0;i<254;i++) room.add(new mmm.world.MovingBodyFluidCoupling.Request(new UUID(3,i+1),bounds,0,0,0));
        MinecraftRegionInputs.integrateExternal(corrected.region,replacement,room,ignored -> true);
        check(room.size()==256,"corrected segment did not fit the complete body budget");
        commit(corrected,replacement);
        check(progress(corrected).revision()==2 && close(progress(corrected).fraction(),1),
                "corrected segment did not advance after rejection");
        var blocked=fixture(); blocked.source.segment(1,9.96,.05,1,PIECES);
        var sweep=blocked.bridge.capture(blocked.region);
        try {
            MinecraftRegionInputs.integrateExternal(blocked.region,sweep,new ArrayList<>(),box -> box.minimumY()<.59);
            throw new AssertionError("structure entered an unavailable activity group");
        } catch(IllegalStateException expected) { blocked.bridge.rejected(blocked.region.id(),expected.getMessage()); }
        check(blocked.region.entityBodyState().bodies().isEmpty() && progress(blocked)==null,
                "unavailable group advanced an unblocked piece");
        var unblocked=blocked.bridge.capture(blocked.region);
        check(unblocked.target().equals(sweep.target()),"unavailable group retry changed target");
        var requests=new ArrayList<mmm.world.MovingBodyFluidCoupling.Request>();
        MinecraftRegionInputs.integrateExternal(blocked.region,unblocked,requests,box -> true);
        check(requests.size()==2,"unblocked retry omitted a piece");
        commit(blocked,unblocked);
        check(close(progress(blocked).fraction(),1),"unblocked retry did not advance all pieces");
    }
    private static void retireAndStaleRevision() {
        var f=fixture(); double start=9.96;
        f.source.segment(1,start,.05,1,PIECES); commit(f,f.bridge.capture(f.region));
        f.source.segment(2,start+.05,-.05,1,PIECES); commit(f,f.bridge.capture(f.region));
        f.source.segment(1,start,.05,1,PIECES);
        try { f.bridge.capture(f.region); throw new AssertionError("stale revision was replayed"); }
        catch(IllegalStateException expected) { }
        check(progress(f).revision()==2 && close(progress(f).accepted().x(),start),"stale revision changed accepted pose");
        var retired=fixture(); retired.source.frame=new Structure(STRUCTURE,ENTITY,1,pose(start),pose(start+.05),DT,PIECES,true);
        try { retired.bridge.capture(retired.region); throw new AssertionError("in-region retirement accepted"); }
        catch(IllegalStateException expected) { }
        check(retired.region.entityBodyState().bodies().isEmpty(),"invalid retirement created bodies");
        retired.source.segment(1,start,.05,1,PIECES); commit(retired,retired.bridge.capture(retired.region));
        retired.source.frame=new Structure(STRUCTURE,ENTITY,2,pose(start+.05),pose(14.1),DT,PIECES,true);
        commit(retired,retired.bridge.capture(retired.region));
        var removal=retired.bridge.capture(retired.region);
        check(removal.target().isEmpty(),"retired body outside the region was retained");
        commit(retired,removal);
        check(retired.region.entityBodyState().bodies().isEmpty(),"accepted retirement did not release the body");
        var repeated=retired.bridge.capture(retired.region);
        check(repeated.target().isEmpty() && !repeated.advancing(),"repeated retired frame recreated a body");
        retired.bridge.accepted(retired.region.id());
        check(retired.bridge.status(PROVIDER,retired.region.id()).state()==State.ACCEPTED,
                "repeated retired frame did not remain accepted");
        retired.source.frame=new Structure(STRUCTURE,ENTITY,2,pose(start+.05),pose(14.1),DT,PIECES,false);
        try { retired.bridge.capture(retired.region); throw new AssertionError("retired revision reused for active structure"); }
        catch(IllegalStateException expected) { }
        retired.source.segment(1,9.96,.05,1,PIECES);
        try { retired.bridge.capture(retired.region); throw new AssertionError("older revision revived retired structure"); }
        catch(IllegalStateException expected) { }
        retired.source.segment(3,14.1,-.2,1,PIECES);
        var reentry=retired.bridge.capture(retired.region);
        check(reentry.target().size()==2,"higher revision did not reenter from outside");
        commit(retired,reentry);
        check(progress(retired).revision()==3,"new structure revision did not advance after retirement");
    }
    private static void completedAndMultipleStructuresReportTruthfully() {
        var complete=fixture(); complete.source.segment(1,9.96,.05,1,PIECES);
        commit(complete,complete.bridge.capture(complete.region));
        check(complete.bridge.status(PROVIDER,complete.region.id()).state()==State.ACCEPTED,"completed segment was not accepted");
        complete.bridge.capture(complete.region); complete.bridge.discarded(complete.region.id());
        check(complete.bridge.status(PROVIDER,complete.region.id()).state()==State.ACCEPTED,
                "discarding an unchanged complete frame changed its accepted status");
        var multiple=fixture(); multiple.source.segment(1,9.96,.05,1,PIECES);
        var other=UUID.fromString("00000000-0000-0000-0000-000000000103");
        multiple.source.extra=new Structure(other,null,1,new Pose(9.96,65.5,-3.5,true),
                new Pose(10.21,65.5,-3.5,true),.25,PIECES,false);
        commit(multiple,multiple.bridge.capture(multiple.region));
        var status=multiple.bridge.status(PROVIDER,multiple.region.id());
        check(close(status.structures().get(STRUCTURE).fraction(),1)
                && close(status.structures().get(other).fraction(),.2),"multiple segment progress differs");
        check(status.state()==State.LAGGING,"completed structure hid another unfinished segment");
    }
    private static MovingStructureApi.Progress progress(Fixture f) {
        return f.bridge.status(PROVIDER,f.region.id()).structures().get(STRUCTURE);
    }
    private static void commit(Fixture f,ExternalMovingStructures.Capture capture) {
        f.region.editableHost().restoreEntityBodyState(new EntityBodyState(capture.target()));
        f.bridge.accepted(f.region.id());
    }
    private static boolean close(double a,double b) { return Math.abs(a-b)<1e-5; }
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
}
