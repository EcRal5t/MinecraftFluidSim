package mmm.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/** Checks that material and granular vertices use the same anchor as their model transform. */
final class FluidRenderCoordinatesValidation {
	static void run(ClientRegionSnapshotStore.MaterialFrame material, ClientRegionSnapshotStore.GranularFrame granular) {
		Vec3 camera = new Vec3(2.25, -1.5, -3.75);
		var coordinates = FluidRenderCoordinates.at(camera);
		require(coordinates.anchor().getX() == 2 && coordinates.anchor().getY() == -2
				&& coordinates.anchor().getZ() == -4, "Camera anchor uses floor for negative coordinates");
		check(material, granular, Vec3.ZERO, camera, coordinates);
	}

	private static void check(ClientRegionSnapshotStore.MaterialFrame material,
			ClientRegionSnapshotStore.GranularFrame granular, Vec3 origin, Vec3 camera, FluidRenderCoordinates coordinates) {
		var baseline = new Bounds();
		var anchored = new Bounds();
		MaterialSurfaceGeometry.buildGeometry(origin, baseline, material, false, false, id -> null);
		GranularSurfaceGeometry.buildGeometry(origin, baseline, granular, false, id -> null);
		MaterialSurfaceGeometry.buildGeometry(camera, anchored, material, false, false, id -> null);
		GranularSurfaceGeometry.buildGeometry(camera, anchored, granular, false, id -> null);
		require(baseline.count > 0 && baseline.count == anchored.count, "Coordinate change preserves emitted vertices");
		for (int axis = 0; axis < 3; axis++) {
			double offset = axis == 0 ? camera.x : axis == 1 ? camera.y : camera.z;
			float fraction = axis == 0 ? coordinates.cameraX() : axis == 1 ? coordinates.cameraY() : coordinates.cameraZ();
			require(Math.abs(anchored.min[axis] - fraction - (baseline.min[axis] - offset)) < 1e-5
					&& Math.abs(anchored.max[axis] - fraction - (baseline.max[axis] - offset)) < 1e-5,
					"Anchored vertices and transform produce camera-relative positions");
		}
		var transformed = new Matrix4f().translate(-coordinates.cameraX(), -coordinates.cameraY(), -coordinates.cameraZ())
				.transform(new Vector4f(4.5F, -2.5F, 6.5F, 1));
		require(Math.abs(transformed.x - 4.25F) < 1e-6 && Math.abs(transformed.y + 3.0F) < 1e-6
				&& Math.abs(transformed.z - 6.25F) < 1e-6, "Model transform applies the fractional camera offset");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static final class Bounds implements VertexConsumer {
		final float[] min = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
		final float[] max = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
		int count;
		public VertexConsumer addVertex(float x, float y, float z) {
			float[] p = {x, y, z};
			for (int i = 0; i < 3; i++) { min[i] = Math.min(min[i], p[i]); max[i] = Math.max(max[i], p[i]); }
			count++;
			return this;
		}
		public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
		public VertexConsumer setColor(int color) { return this; }
		public VertexConsumer setUv(float u, float v) { return this; }
		public VertexConsumer setUv1(int u, int v) { return this; }
		public VertexConsumer setUv2(int u, int v) { return this; }
		public VertexConsumer setNormal(float x, float y, float z) { return this; }
		public VertexConsumer setLineWidth(float width) { return this; }
	}
}
