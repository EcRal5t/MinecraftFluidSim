package mmm.client;

import mmm.network.FluidGranularSnapshotPayload;
import mmm.network.FluidMaterialSnapshotPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.world.phys.Vec3;

/** Stateless active-parcel and deposit geometry. */
final class GranularSurfaceGeometry {
	private GranularSurfaceGeometry() { }

	static void buildGeometry(
			Vec3 camera, VertexConsumer vertices,
			ClientRegionSnapshotStore.GranularFrame frame, boolean textured
	) {
		buildGeometry(camera, vertices, frame, textured, FluidRenderBackend::sprite);
	}

	static void buildGeometry(Vec3 camera, VertexConsumer vertices,
			ClientRegionSnapshotStore.GranularFrame frame, boolean textured,
			java.util.function.Function<String, TextureAtlasSprite> sprites) {
		FluidGranularSnapshotPayload payload = frame.payload();
		FluidMaterialSnapshotPayload state = payload.material();
		Emitter emitter = new Emitter(vertices, camera, state, textured, sprites);
		float h = state.cellSize();
		for (int z = 0; z < state.sizeZ(); z++) for (int y = 0; y < state.sizeY(); y++) {
			for (int x = 0; x < state.sizeX(); x++) {
				int cell = granularIndex(state, x, y, z);
				float fill = Byte.toUnsignedInt(frame.bulkFill()[cell]) / 255.0F;
				if (fill <= 0.0F) continue;
				int material = frame.topMaterial()[cell];
				int color = payload.granularMaterials().get(material).argb();
				float porosity = Byte.toUnsignedInt(frame.porosity()[cell]) / 255.0F;
				renderDepositCell(emitter, state, frame.bulkFill(), x, y, z, h, fill, color,
						0.94F + 0.04F * (1.0F - porosity));
			}
		}
		for (var sample : payload.activeParcels()) {
			float centerX = sample.xQ() / 65_535.0F * state.sizeX() * h;
			float centerY = sample.yQ() / 65_535.0F * state.sizeY() * h;
			float centerZ = sample.zQ() / 65_535.0F * state.sizeZ() * h;
			float half = Math.max(h / 64.0F, sample.diameterQ() / 255.0F * h * 0.5F);
			int color = payload.granularMaterials().get(sample.materialIndex()).argb();
			box(emitter, centerX - half, centerY - half, centerZ - half,
					centerX + half, centerY + half, centerZ + half,
					((color >>> 16) & 0xff) / 255.0F, ((color >>> 8) & 0xff) / 255.0F,
					(color & 0xff) / 255.0F, 0.92F);
		}
	}

	private static void renderDepositCell(
			Emitter emitter, FluidMaterialSnapshotPayload state, byte[] bulk,
			int x, int y, int z, float h, float fill, int argb, float alpha
	) {
		float red = ((argb >>> 16) & 0xff) / 255.0F;
		float green = ((argb >>> 8) & 0xff) / 255.0F;
		float blue = (argb & 0xff) / 255.0F;
		float minX = x * h;
		float minY = y * h;
		float minZ = z * h;
		float maxX = minX + h;
		float maxY = minY + h * fill;
		float maxZ = minZ + h;
		float left = granularFill(state, bulk, x - 1, y, z) * h;
		if (left < h * fill) quadX(emitter, minX, minY + left, maxY, minZ, maxZ,
				false, red, green, blue, alpha);
		float right = granularFill(state, bulk, x + 1, y, z) * h;
		if (right < h * fill) quadX(emitter, maxX, minY + right, maxY, minZ, maxZ,
				true, red, green, blue, alpha);
		float back = granularFill(state, bulk, x, y, z - 1) * h;
		if (back < h * fill) quadZ(emitter, minZ, minX, maxX, minY + back, maxY,
				false, red, green, blue, alpha);
		float front = granularFill(state, bulk, x, y, z + 1) * h;
		if (front < h * fill) quadZ(emitter, maxZ, minX, maxX, minY + front, maxY,
				true, red, green, blue, alpha);
		if (fill < 1.0F || granularFill(state, bulk, x, y + 1, z) == 0.0F) {
			quadY(emitter, maxY, minX, maxX, minZ, maxZ, true, red, green, blue, alpha);
		}
		if (granularFill(state, bulk, x, y - 1, z) == 0.0F) {
			quadY(emitter, minY, minX, maxX, minZ, maxZ, false,
					red, green, blue, alpha);
		}
	}

	private static float granularFill(
			FluidMaterialSnapshotPayload state, byte[] bulk, int x, int y, int z
	) {
		if (x < 0 || x >= state.sizeX() || y < 0 || y >= state.sizeY() || z < 0 || z >= state.sizeZ()) {
			return 0.0F;
		}
		return Byte.toUnsignedInt(bulk[granularIndex(state, x, y, z)]) / 255.0F;
	}

	private static int granularIndex(FluidMaterialSnapshotPayload state, int x, int y, int z) {
		return (z * state.sizeY() + y) * state.sizeX() + x;
	}

    private static void box(Emitter emitter,float x0,float y0,float z0,float x1,float y1,float z1,
            float r,float g,float b,float alpha) {
        quadX(emitter,x0,y0,y1,z0,z1,false,r,g,b,alpha);
        quadX(emitter,x1,y0,y1,z0,z1,true,r,g,b,alpha);
        quadY(emitter,y0,x0,x1,z0,z1,false,r,g,b,alpha);
        quadY(emitter,y1,x0,x1,z0,z1,true,r,g,b,alpha);
        quadZ(emitter,z0,x0,x1,y0,y1,false,r,g,b,alpha);
        quadZ(emitter,z1,x0,x1,y0,y1,true,r,g,b,alpha);
    }

    private static void quadX(Emitter emitter,float x,float y0,float y1,float z0,float z1,boolean positive,
            float r,float g,float b,float alpha) {
        emitter.face(0,x,y0,y1,z0,z1,positive,r,g,b,alpha);
    }
    private static void quadY(Emitter emitter,float y,float x0,float x1,float z0,float z1,boolean positive,
            float r,float g,float b,float alpha) {
        emitter.face(1,y,z0,z1,x0,x1,positive,r,g,b,alpha);
    }
    private static void quadZ(Emitter emitter,float z,float x0,float x1,float y0,float y1,boolean positive,
            float r,float g,float b,float alpha) {
        emitter.face(2,z,x0,x1,y0,y1,positive,r,g,b,alpha);
    }

    /** Coordinates remain region local until the vertex write. */
    private static final class Emitter {
        final VertexConsumer vertices;
        final FluidMaterialSnapshotPayload state;
        final boolean textured;
        final java.util.function.Function<String, TextureAtlasSprite> sprites;
        TextureAtlasSprite sprite;
        final BlockPos anchor;
        final double ox,oy,oz;

        Emitter(VertexConsumer vertices, Vec3 camera, FluidMaterialSnapshotPayload state, boolean textured,
                java.util.function.Function<String, TextureAtlasSprite> sprites) {
            this.vertices=vertices;
            this.state=state;
            this.textured=textured;
            this.sprites=sprites;
            anchor=FluidRenderCoordinates.at(camera).anchor();
            ox=state.origin().getX()-anchor.getX();
            oy=state.origin().getY()-anchor.getY();
            oz=state.origin().getZ()-anchor.getZ();
        }

        void face(int axis,float plane,float minU,float maxU,float minV,float maxV,boolean positive,
                float r,float g,float b,float alpha) {
            if (textured && sprite == null) sprite = sprites.apply("mmm:granular");
            float centerU=(minU+maxU)*0.5F, centerV=(minV+maxV)*0.5F;
            BlockPos position=BlockPos.containing(state.origin().getX()+(axis==0 ? plane : axis==1 ? centerV : centerU),
                    state.origin().getY()+(axis==0 ? centerU : axis==1 ? plane : centerV),
                    state.origin().getZ()+(axis==0 ? centerV : axis==1 ? centerU : plane));
            var client=Minecraft.getInstance();
            var level=client==null ? null : client.level;
            int light=level==null ? 0 : level.getBrightness(LightLayer.BLOCK,position)<<4
                    | level.getBrightness(LightLayer.SKY,position)<<20;
            FluidRenderBackend.beginMaterial(vertices,"mmm:granular",position.getX()-anchor.getX(),
                    position.getY()-anchor.getY(),position.getZ()-anchor.getZ());
            for (int i=0;i<4;i++) {
                int corner=positive ? i : 3-i;
                boolean upperU=corner==1 || corner==2, upperV=corner>=2;
                float u=upperU ? maxU : minU, v=upperV ? maxV : minV;
                float x=axis==0 ? plane : axis==1 ? v : u;
                float y=axis==0 ? u : axis==1 ? plane : v;
                float z=axis==0 ? v : axis==1 ? u : plane;
                float normal=positive ? 1 : -1;
                var vertex = vertices.addVertex((float)(ox+x),(float)(oy+y),(float)(oz+z))
                        .setColor(r,g,b,alpha);
                if (textured) vertex.setUv(sprite.getU(upperU ? 1 : 0),sprite.getV(upperV ? 1 : 0))
                        .setUv2(light&0xffff,light>>>16)
                        .setNormal(axis==0 ? normal : 0,axis==1 ? normal : 0,axis==2 ? normal : 0);
            }
        }
    }
}
