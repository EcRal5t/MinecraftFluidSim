package mmm.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Section-aligned camera anchor shared by vertex positions and Iris block coordinates. */
record FluidRenderCoordinates(BlockPos anchor, Vec3 camera) {
	static FluidRenderCoordinates at(Vec3 camera) {
		return new FluidRenderCoordinates(new BlockPos(
                Math.floorDiv((int)Math.floor(camera.x), 16) * 16,
                Math.floorDiv((int)Math.floor(camera.y), 16) * 16,
                Math.floorDiv((int)Math.floor(camera.z), 16) * 16), camera);
	}

	float cameraX() { return (float) (camera.x - anchor.getX()); }
	float cameraY() { return (float) (camera.y - anchor.getY()); }
	float cameraZ() { return (float) (camera.z - anchor.getZ()); }
}
