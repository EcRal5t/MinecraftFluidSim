package mmm.client;

import mmm.fluid.FluidMaterialSurfaceMesher;
import mmm.fluid.FluidMaterialRenderSnapshot;
import mmm.network.FluidMaterialSnapshotPayload;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;

/** Material surfaces retain their source cell, both materials and outward orientation. */
final class MaterialSurfaceGeometry {
    private MaterialSurfaceGeometry() { }

    static void buildGeometry(Vec3 camera, VertexConsumer vertices,
            ClientRegionSnapshotStore.MaterialFrame frame, boolean shadow, boolean textured) {
        buildGeometry(camera, vertices, frame, shadow, textured, FluidRenderBackend::sprite);
    }

    static void buildGeometry(Vec3 camera, VertexConsumer vertices,
            ClientRegionSnapshotStore.MaterialFrame frame, boolean shadow, boolean textured,
            java.util.function.Function<String, net.minecraft.client.renderer.texture.TextureAtlasSprite> sprites) {
        var state = frame.payload();
        var anchor = FluidRenderCoordinates.at(camera).anchor();
        double ox = state.origin().getX() - anchor.getX();
        double oy = state.origin().getY() - anchor.getY();
        double oz = state.origin().getZ() - anchor.getZ();
        for (var colored : frame.triangles()) {
            var triangle = colored.triangle();
            var center = triangle.centroid();
            var normal = triangle.normal();
            boolean back = normal.x() * (camera.x - state.origin().getX() - center.x())
                    + normal.y() * (camera.y - state.origin().getY() - center.y())
                    + normal.z() * (camera.z - state.origin().getZ() - center.z()) < 0;
            int material = back && colored.secondMaterial() >= 0 ? colored.secondMaterial() : colored.firstMaterial();
            String id = state.materials().get(material).id();
            boolean composition = textured;

            int argb = shadow ? state.materials().get(material).argb() : back && colored.secondMaterial() >= 0 ? colored.secondArgb() : colored.argb();
            float alpha = composition ? ((state.materials().get(material).argb() >>> 24) & 255) / 255.0F : 0.92F;
            var position = state.origin().offset((int) Math.floor(center.x()), (int) Math.floor(center.y()), (int) Math.floor(center.z()));
            int light = light(position);
            FluidRenderBackend.beginMaterial(vertices, composition ? id : "", position.getX() - anchor.getX(),
                    position.getY() - anchor.getY(), position.getZ() - anchor.getZ());
            var sprite = composition ? sprites.apply(id) : null;
            double length = Math.sqrt(normal.x()*normal.x()+normal.y()*normal.y()+normal.z()*normal.z());
            float sign = back ? -1 : 1;
            float nx = (float)(normal.x()/length)*sign, ny = (float)(normal.y()/length)*sign, nz = (float)(normal.z()/length)*sign;
            // Coordinates within the source block keep atlas sampling bounded across arbitrary PLIC slopes.
            boolean projectX = Math.abs(nx) >= Math.abs(ny) && Math.abs(nx) >= Math.abs(nz);
            boolean projectY = !projectX && Math.abs(ny) >= Math.abs(nz);
            double uBase = Math.floor(projectX ? center.z() : center.x());
            double vBase = Math.floor(projectY ? center.z() : center.y());
            var points = back ? new FluidMaterialSurfaceMesher.Point[] {triangle.c(),triangle.b(),triangle.a(),triangle.a()}
                    : new FluidMaterialSurfaceMesher.Point[] {triangle.a(),triangle.b(),triangle.c(),triangle.c()};
            for (var point : points) {
                double u = (projectX ? point.z() : point.x()) - uBase;
                double v = (projectY ? point.z() : point.y()) - vBase;
                var vertex = vertices.addVertex((float)(ox+point.x()),(float)(oy+point.y()),(float)(oz+point.z()))
                        .setColor(((argb>>>16)&255)/255f,((argb>>>8)&255)/255f,(argb&255)/255f,alpha);
                if (composition) vertex.setUv(sprite.getU((float)Math.clamp(u,0,1)),sprite.getV((float)Math.clamp(v,0,1)))
                        .setUv2(light & 0xffff,light >>> 16).setNormal(nx,ny,nz);
            }
        }
    }

    private static int light(BlockPos position) {
        var client = Minecraft.getInstance();
        var level = client == null ? null : client.level;
        return level == null ? 0 : level.getBrightness(LightLayer.BLOCK, position) << 4
                | level.getBrightness(LightLayer.SKY, position) << 20;
    }

    static List<ColoredTriangle> prepare(FluidMaterialSnapshotPayload state, FluidMaterialRenderSnapshot snapshot,
            FluidMaterialSurfaceMesher.Mesh mesh, ClientRegionSnapshotStore.ChemicalFrame chemical,
            short[] temperatures, FluidDisplaySettings.View view) {
        ArrayList<ColoredTriangle> triangles = new ArrayList<>(mesh.surfaces().size());
        for (var surface : mesh.surfaces()) {
            int first = FluidColorMapping.color(view, chemical, temperatures, state, surface.cell(), surface.firstPhase(),
                    state.materials().get(surface.firstMaterial()).argb());
            int second = surface.secondMaterial() < 0 ? first : FluidColorMapping.color(view, chemical, temperatures,
                    state, surface.cell(), surface.secondPhase(), state.materials().get(surface.secondMaterial()).argb());
            triangles.add(new ColoredTriangle(surface.triangle(), first, second, surface.firstMaterial(), surface.secondMaterial()));
        }
        return List.copyOf(triangles);
    }

    record ColoredTriangle(FluidMaterialSurfaceMesher.Triangle triangle, int argb, int secondArgb,
            int firstMaterial, int secondMaterial) { }
}


