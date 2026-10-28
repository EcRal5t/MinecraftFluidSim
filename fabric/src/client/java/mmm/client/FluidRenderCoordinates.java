package mmm.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** Integer camera anchor shared by vertex positions and Iris block coordinates. */
record FluidRenderCoordinates(BlockPos anchor, Vec3 camera) {
	static FluidRenderCoordinates at(Vec3 camera) {
		return new FluidRenderCoordinates(BlockPos.containing(camera), camera);
	}

	float cameraX() { return (float) (camera.x - anchor.getX()); }
	float cameraY() { return (float) (camera.y - anchor.getY()); }
	float cameraZ() { return (float) (camera.z - anchor.getZ()); }
}
