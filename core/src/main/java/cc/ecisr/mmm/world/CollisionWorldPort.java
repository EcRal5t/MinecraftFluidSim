package cc.ecisr.mmm.world;

/** World-reader port implemented by a loader adapter; capture must be complete or fail. */
@FunctionalInterface
public interface CollisionWorldPort {
	CollisionField capture(CollisionRegion region);
}
