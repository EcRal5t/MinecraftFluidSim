package mmm.client;

import mmm.fluid.FluidMaterialSurfaceMesher;
import mmm.fluid.FluidMaterialRenderSnapshot;
import mmm.network.FluidMaterialSnapshotPayload;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.List;

import static mmm.client.QuadGeometry.vertex;

/** Stateless material-interface and chemical-colour geometry. */
final class MaterialSurfaceGeometry {
	private MaterialSurfaceGeometry() { }

	static void buildGeometry(
			LevelRenderContext context, VertexConsumer vertices,
			ClientRegionSnapshotStore.MaterialFrame frame
	) {
		FluidMaterialSnapshotPayload state = frame.payload();
		PoseStack poses = context.poseStack();
		Vec3 camera = context.levelState().cameraRenderState.pos;
		poses.pushPose();
		poses.translate(-camera.x, -camera.y, -camera.z);
		Matrix4fc matrix = poses.last().pose();
		for (ColoredTriangle triangle : frame.triangles()) {
			emitMaterialTriangle(matrix, vertices, state, triangle.triangle(), triangle.argb(), triangle.alpha());
		}
		poses.popPose();
	}

	static List<ColoredTriangle> prepare(FluidMaterialSnapshotPayload state, FluidMaterialRenderSnapshot snapshot,
			FluidMaterialSurfaceMesher.Mesh mesh, ClientRegionSnapshotStore.ChemicalFrame chemical,
			short[] temperatures, FluidDisplaySettings.View view) {
		ArrayList<ColoredTriangle> triangles = new ArrayList<>(mesh.outerTriangles().size() + mesh.interfaceTriangles().size());
		for (FluidMaterialSurfaceMesher.Triangle triangle : mesh.outerTriangles()) {
			int material = FluidMaterialSurfaceMesher.dominantMaterialAt(snapshot, triangle.centroid());
			if (material < 0) continue;
			var entry = state.materials().get(material);
			int color = color(view, chemical, temperatures, state, triangle.centroid(),
					state.phaseGroups().indexOf(entry.phaseGroup()), entry.argb());
			triangles.add(new ColoredTriangle(triangle, color, 0.62F));
		}
		for (FluidMaterialSurfaceMesher.InterfaceTriangle owned : mesh.interfaceTriangles()) {
			int first = color(view, chemical, temperatures, state, owned.triangle().centroid(),
					owned.firstPhase(), phaseArgb(state, owned.firstPhase()));
			int second = color(view, chemical, temperatures, state, owned.triangle().centroid(),
					owned.secondPhase(), phaseArgb(state, owned.secondPhase()));
			int color = FluidColorMapping.blend(first, second, 0.5);
			triangles.add(new ColoredTriangle(owned.triangle(), color, 0.48F));
		}
		return List.copyOf(triangles);
	}

	record ColoredTriangle(FluidMaterialSurfaceMesher.Triangle triangle, int argb, float alpha) { }

	private static int color(FluidDisplaySettings.View view,
			ClientRegionSnapshotStore.ChemicalFrame chemical, short[] temperatures,
			FluidMaterialSnapshotPayload state, FluidMaterialSurfaceMesher.Point point, int phase, int materialArgb) {
		int x = Math.clamp((int) Math.floor(point.x() / state.cellSize()), 0, state.sizeX() - 1);
		int y = Math.clamp((int) Math.floor(point.y() / state.cellSize()), 0, state.sizeY() - 1);
		int z = Math.clamp((int) Math.floor(point.z() / state.cellSize()), 0, state.sizeZ() - 1);
		return FluidColorMapping.color(view, chemical, temperatures, state,
				(z * state.sizeY() + y) * state.sizeX() + x, phase, materialArgb);
	}

	private static int phaseArgb(FluidMaterialSnapshotPayload state, int phase) {
		String group = state.phaseGroups().get(phase);
		for (FluidMaterialSnapshotPayload.MaterialEntry material : state.materials()) {
			if (material.phaseGroup().equals(group)) return material.argb();
		}
		return 0xffff00ff;
	}

	private static void emitMaterialTriangle(
			Matrix4fc matrix, VertexConsumer vertices, FluidMaterialSnapshotPayload state,
			FluidMaterialSurfaceMesher.Triangle triangle, int argb, float alpha
	) {
		FluidMaterialSurfaceMesher.Point normal = triangle.normal();
		double length = Math.sqrt(normal.x() * normal.x() + normal.y() * normal.y() + normal.z() * normal.z());
		float vertical = length <= 1.0E-9 ? 0.0F : (float) Math.abs(normal.y() / length);
		float light = 0.78F + 0.22F * vertical;
		float red = ((argb >>> 16) & 0xff) / 255.0F * light;
		float green = ((argb >>> 8) & 0xff) / 255.0F * light;
		float blue = (argb & 0xff) / 255.0F * light;
		emitMaterialVertex(matrix, vertices, state, triangle.a(), red, green, blue, alpha);
		emitMaterialVertex(matrix, vertices, state, triangle.b(), red, green, blue, alpha);
		emitMaterialVertex(matrix, vertices, state, triangle.c(), red, green, blue, alpha);
		emitMaterialVertex(matrix, vertices, state, triangle.c(), red, green, blue, alpha);
	}

	private static void emitMaterialVertex(
			Matrix4fc matrix, VertexConsumer vertices, FluidMaterialSnapshotPayload state,
			FluidMaterialSurfaceMesher.Point point, float red, float green, float blue, float alpha
	) {
		vertex(vertices, matrix,
				state.origin().getX() + (float) point.x(), state.origin().getY() + (float) point.y(),
				state.origin().getZ() + (float) point.z(), red, green, blue, alpha);
	}
}
