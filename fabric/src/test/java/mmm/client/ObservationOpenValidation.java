package mmm.client;

public final class ObservationOpenValidation {
    public static void main(String[] args) {
        var alice=new ObservationOpen(); var bob=new ObservationOpen();
        var a=alice.start("minecraft:overworld"); var b=bob.start("minecraft:overworld");
        require(!alice.accept(b,"minecraft:overworld") && !bob.accept(a,"minecraft:overworld"),"player sessions crossed");
        require(alice.accept(a,"minecraft:overworld") && bob.accept(b,"minecraft:overworld"),"independent openings");
        require(!alice.accept(a,"minecraft:overworld"),"duplicate opening");
        require(ObservationOpen.newerReply(a,3,1,a,2),"delayed valid update");
        require(!ObservationOpen.newerReply(a,3,2,a,1) && !ObservationOpen.newerReply(a,3,2,b,3)
            && !ObservationOpen.newerReply(a,3,2,a,4),"stale or foreign update");
        var old=alice.start("minecraft:overworld"); var next=alice.start("minecraft:overworld");
        require(!alice.accept(old,"minecraft:overworld") && alice.accept(next,"minecraft:overworld"),"late opening");
        old=alice.start("minecraft:overworld"); alice.update("minecraft:the_nether",false);
        require(!alice.accept(old,"minecraft:overworld"),"dimension transition");
        old=alice.start("minecraft:overworld"); alice.update("minecraft:overworld",true);
        require(!alice.accept(old,"minecraft:overworld"),"screen changed");
        old=alice.start("minecraft:overworld"); alice.clear();
        require(!alice.accept(old,"minecraft:overworld"),"disconnect");
    }
    private static void require(boolean value,String name){if(!value)throw new AssertionError(name);}
}
