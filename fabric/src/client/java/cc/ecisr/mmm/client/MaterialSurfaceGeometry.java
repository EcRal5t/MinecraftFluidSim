package cc.ecisr.mmm.client;

import cc.ecisr.mmm.chemistry.ChemicalDisplaySnapshot;
import cc.ecisr.mmm.fluid.FluidMaterialSurfaceMesher;
import cc.ecisr.mmm.fluid.FluidMaterialRenderSnapshot;
import cc.ecisr.mmm.network.FluidChemicalSnapshotPayload;
import cc.ecisr.mmm.network.FluidMaterialSnapshotPayload;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.List;

import static cc.ecisr.mmm.client.QuadGeometry.vertex;

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
			FluidMaterialSurfaceMesher.Mesh mesh, ClientRegionSnapshotStore.ChemicalFrame chemical) {
		ArrayList<ColoredTriangle> triangles = new ArrayList<>(mesh.outerTriangles().size() + mesh.interfaceTriangles().size());
		for (FluidMaterialSurfaceMesher.Triangle triangle : mesh.outerTriangles()) {
			int material = FluidMaterialSurfaceMesher.dominantMaterialAt(snapshot, triangle.centroid());
			if (material < 0) continue;
			int color = chemicalArgb(chemical, state, triangle.centroid(), state.materials().get(material).argb());
			triangles.add(new ColoredTriangle(triangle, color, 0.62F));
		}
		for (FluidMaterialSurfaceMesher.InterfaceTriangle owned : mesh.interfaceTriangles()) {
			int first = phaseArgb(state, owned.firstPhase());
			int second = phaseArgb(state, owned.secondPhase());
			int color = chemicalArgb(chemical, state, owned.triangle().centroid(), blendArgb(first, second));
			triangles.add(new ColoredTriangle(owned.triangle(), color, 0.48F));
		}
		return List.copyOf(triangles);
	}

	record ColoredTriangle(FluidMaterialSurfaceMesher.Triangle triangle, int argb, float alpha) { }

	private static int chemicalArgb(
			ClientRegionSnapshotStore.ChemicalFrame chemical, FluidMaterialSnapshotPayload state,
			FluidMaterialSurfaceMesher.Point point,
			int materialArgb
	) {
		if (chemical == null) return materialArgb;
		FluidChemicalSnapshotPayload payload = chemical.payload();
		int x = Math.max(0, Math.min(state.sizeX() - 1, (int) Math.floor(point.x() / state.cellSize())));
		int y = Math.max(0, Math.min(state.sizeY() - 1, (int) Math.floor(point.y() / state.cellSize())));
		int z = Math.max(0, Math.min(state.sizeZ() - 1, (int) Math.floor(point.z() / state.cellSize())));
		int cell = (z * state.sizeY() + y) * state.sizeX() + x;
		short[] indices = chemical.topSpecies();
		byte[] weights = chemical.topWeights();
		int red = ((materialArgb >>> 16) & 0xff) * Byte.toUnsignedInt(chemical.residualWeights()[cell]);
		int green = ((materialArgb >>> 8) & 0xff) * Byte.toUnsignedInt(chemical.residualWeights()[cell]);
		int blue = (materialArgb & 0xff) * Byte.toUnsignedInt(chemical.residualWeights()[cell]);
		int weightTotal = Byte.toUnsignedInt(chemical.residualWeights()[cell]);
		for (int slot = 0; slot < ChemicalDisplaySnapshot.TOP_SPECIES; slot++) {
			int offset = cell * ChemicalDisplaySnapshot.TOP_SPECIES + slot;
			int species = indices[offset], weight = Byte.toUnsignedInt(weights[offset]);
			if (species < 0 || weight == 0) continue;
			int argb = payload.species().get(species).argb();
			red += ((argb >>> 16) & 0xff) * weight;
			green += ((argb >>> 8) & 0xff) * weight;
			blue += (argb & 0xff) * weight;
			weightTotal += weight;
		}
		if (weightTotal > 0) {
			red /= weightTotal;
			green /= weightTotal;
			blue /= weightTotal;
		}
		int indicator = Byte.toUnsignedInt(chemical.indicatorFraction()[cell]);
		if (indicator > 0) {
			double value = Byte.toUnsignedInt(chemical.ph()[cell]) * 14.0 / 255.0;
			int phColor = value < 7.0 ? 0xffff3048 : value > 7.0 ? 0xff305cff : 0xff35d06f;
			double blend = indicator / 255.0 * Math.min(1.0, Math.abs(value - 7.0) / 7.0 + 0.25);
			red = (int) Math.round(red * (1.0 - blend) + ((phColor >>> 16) & 0xff) * blend);
			green = (int) Math.round(green * (1.0 - blend) + ((phColor >>> 8) & 0xff) * blend);
			blue = (int) Math.round(blue * (1.0 - blend) + (phColor & 0xff) * blend);
		}
		return 0xff000000 | red << 16 | green << 8 | blue;
	}

	private static int phaseArgb(FluidMaterialSnapshotPayload state, int phase) {
		String group = state.phaseGroups().get(phase);
		for (FluidMaterialSnapshotPayload.MaterialEntry material : state.materials()) {
			if (material.phaseGroup().equals(group)) return material.argb();
		}
		return 0xffff00ff;
	}

	private static int blendArgb(int first, int second) {
		int red = (((first >>> 16) & 0xff) + ((second >>> 16) & 0xff)) / 2;
		int green = (((first >>> 8) & 0xff) + ((second >>> 8) & 0xff)) / 2;
		int blue = ((first & 0xff) + (second & 0xff)) / 2;
		return 0xff000000 | red << 16 | green << 8 | blue;
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
