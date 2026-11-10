package mmm.client;

import java.util.UUID;

/** Tracks one client-initiated screen opening across network and world transitions. */
final class ObservationOpen {
    private UUID session;
    private String dimension;
    UUID start(String dimension) { this.dimension=dimension; return session=UUID.randomUUID(); }
    boolean accept(UUID candidate,String currentDimension) {
        if(session==null || !session.equals(candidate) || !dimension.equals(currentDimension)) return false;
        clear(); return true;
    }
    void update(String currentDimension,boolean screenOpen) {
        if(screenOpen || currentDimension==null || !currentDimension.equals(dimension)) clear();
    }
    static boolean newerReply(UUID active, long sent, long applied, UUID candidate, long sequence) {
        return active.equals(candidate) && sequence>applied && sequence<=sent;
    }
    void clear() { session=null; dimension=null; }
}
