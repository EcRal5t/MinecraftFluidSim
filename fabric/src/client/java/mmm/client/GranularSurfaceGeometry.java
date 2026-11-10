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
        buildGeometry(camera, vertices, frame, textured, sprites, position -> {
            var client = Minecraft.getInstance();
            var level = client == null ? null : client.level;
            return level == null ? 0 : level.getBrightness(LightLayer.BLOCK,position)<<4
                    | level.getBrightness(LightLayer.SKY,position)<<20;
        });
    }

    static void buildGeometry(Vec3 camera, VertexConsumer vertices,
            ClientRegionSnapshotStore.GranularFrame frame, boolean textured,
            java.util.function.Function<String, TextureAtlasSprite> sprites,
            java.util.function.ToIntFunction<BlockPos> lights) {
		FluidGranularSnapshotPayload payload = frame.payload();
		FluidMaterialSnapshotPayload state = payload.material();
		Emitter emitter = new Emitter(vertices, camera, state, textured, sprites, lights);
		float h = state.cellSize();
		var grid = state.geometry().grid();
        byte[] masks=payload.fixedMasks();
        for (int cell = 0; cell < grid.cellCount(); cell++) {
            int x=grid.x(cell),y=grid.y(cell),z=grid.z(cell);
            float fill = Byte.toUnsignedInt(frame.bulkFill()[cell]) / 255.0F;
            if (fill <= 0.0F) continue;
            int material = frame.topMaterial()[cell];
            int color = payload.granularMaterials().get(material).argb();
            float porosity = Byte.toUnsignedInt(frame.porosity()[cell]) / 255.0F;
            renderDepositCell(emitter, state, frame.bulkFill(), masks, x, y, z, h, fill, color,
                    0.94F + 0.04F * (1.0F - porosity));
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

    private static void renderDepositCell(Emitter emitter,FluidMaterialSnapshotPayload state,byte[] bulk,byte[] masks,
            int x,int y,int z,float h,float fill,int argb,float alpha) {
        int cell=state.geometry().grid().cell(x,y,z),mask=Byte.toUnsignedInt(masks[cell]);
        if(mask==0 && openNeighbors(state,masks,x,y,z)) {
            renderOpenDepositCell(emitter,state,bulk,x,y,z,h,fill,argb,alpha);
            return;
        }
        float top=(y+bedHeight(mask,fill))*h,micro=h*.5F;
        float red=((argb>>>16)&255)/255F,green=((argb>>>8)&255)/255F,blue=(argb&255)/255F;
        for(int bit=0;bit<8;bit++) {
            if((mask&(1<<bit))!=0)continue;
            int mx=x*2+(bit&1),my=y*2+((bit>>1)&1),mz=z*2+((bit>>2)&1);
            float x0=mx*micro,y0=my*micro,z0=mz*micro,x1=x0+micro,y1=Math.min(y0+micro,top),z1=z0+micro;
            if(y1<=y0)continue;
            for(int side=0;side<2;side++) {
                int step=side==0?-1:1;boolean positive=side==1;
                var nx=neighbor(state,bulk,masks,mx+step,my,mz,h);
                float start=Math.max(y0,nx.top());
                if(!nx.solid() && start<y1)quadX(emitter,positive?x1:x0,start,y1,z0,z1,positive,red,green,blue,alpha);
                var nz=neighbor(state,bulk,masks,mx,my,mz+step,h);
                start=Math.max(y0,nz.top());
                if(!nz.solid() && start<y1)quadZ(emitter,positive?z1:z0,x0,x1,start,y1,positive,red,green,blue,alpha);
            }
            var below=neighbor(state,bulk,masks,mx,my-1,mz,h);
            if(!below.solid() && below.top()<y0)quadY(emitter,y0,x0,x1,z0,z1,false,red,green,blue,alpha);
            if(y1<y0+micro)quadY(emitter,y1,x0,x1,z0,z1,true,red,green,blue,alpha);
            else {
                var above=neighbor(state,bulk,masks,mx,my+1,mz,h);
                if(!above.solid() && above.top()<=y1)quadY(emitter,y1,x0,x1,z0,z1,true,red,green,blue,alpha);
            }
        }
    }
    private static boolean openNeighbors(FluidMaterialSnapshotPayload state,byte[] masks,int x,int y,int z) {
        var grid=state.geometry().grid();
        for(var face:net.minecraft.core.Direction.values()) {
            int cell=grid.cell(x+face.getStepX(),y+face.getStepY(),z+face.getStepZ());
            if(cell>=0 && masks[cell]!=0)return false;
        }
        return true;
    }
    private static void renderOpenDepositCell(Emitter emitter,FluidMaterialSnapshotPayload state,byte[] bulk,
            int x,int y,int z,float h,float fill,int argb,float alpha) {
        float r=((argb>>>16)&255)/255F,g=((argb>>>8)&255)/255F,b=(argb&255)/255F;
        float x0=x*h,y0=y*h,z0=z*h,x1=x0+h,y1=y0+h*fill,z1=z0+h;
        float left=granularFill(state,bulk,x-1,y,z)*h,right=granularFill(state,bulk,x+1,y,z)*h;
        float back=granularFill(state,bulk,x,y,z-1)*h,front=granularFill(state,bulk,x,y,z+1)*h;
        if(left<h*fill)quadX(emitter,x0,y0+left,y1,z0,z1,false,r,g,b,alpha);
        if(right<h*fill)quadX(emitter,x1,y0+right,y1,z0,z1,true,r,g,b,alpha);
        if(back<h*fill)quadZ(emitter,z0,x0,x1,y0+back,y1,false,r,g,b,alpha);
        if(front<h*fill)quadZ(emitter,z1,x0,x1,y0+front,y1,true,r,g,b,alpha);
        if(fill<1 || granularFill(state,bulk,x,y+1,z)==0)quadY(emitter,y1,x0,x1,z0,z1,true,r,g,b,alpha);
        if(granularFill(state,bulk,x,y-1,z)==0)quadY(emitter,y0,x0,x1,z0,z1,false,r,g,b,alpha);
    }
    private static float granularFill(FluidMaterialSnapshotPayload state,byte[] bulk,int x,int y,int z) {
        int cell=state.geometry().grid().cell(x,y,z);
        return cell<0?0:Byte.toUnsignedInt(bulk[cell])/255F;
    }
    private record Neighbor(boolean solid,float top) { }
    private static Neighbor neighbor(FluidMaterialSnapshotPayload state,byte[] bulk,byte[] masks,int mx,int my,int mz,float h) {
        int x=Math.floorDiv(mx,2),y=Math.floorDiv(my,2),z=Math.floorDiv(mz,2);
        int cell=state.geometry().grid().cell(x,y,z);
        if(cell<0)return new Neighbor(false,Float.NEGATIVE_INFINITY);
        int mask=Byte.toUnsignedInt(masks[cell]);int bit=Math.floorMod(mx,2)+2*Math.floorMod(my,2)+4*Math.floorMod(mz,2);
        return new Neighbor((mask&(1<<bit))!=0,(y+bedHeight(mask,Byte.toUnsignedInt(bulk[cell])/255F))*h);
    }
    static float bedHeight(int fixedMask,float bulk) {
        int open=(~fixedMask)&255;
        float lower=Integer.bitCount(open&0x33)/8F,upper=Integer.bitCount(open&0xcc)/8F;
        float amount=Math.clamp(bulk,0,lower+upper);
        return amount<=lower?(lower>0?.5F*amount/lower:0):.5F+(upper>0?.5F*(amount-lower)/upper:0);
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
        final java.util.function.ToIntFunction<BlockPos> lights;
        final BlockPos anchor;
        final double ox,oy,oz;

        Emitter(VertexConsumer vertices, Vec3 camera, FluidMaterialSnapshotPayload state, boolean textured,
                java.util.function.Function<String, TextureAtlasSprite> sprites,
                java.util.function.ToIntFunction<BlockPos> lights) {
            this.lights=lights;
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
            int light=lights.applyAsInt(position);
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
