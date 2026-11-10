package mmm.client;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** Owns one persistent vertex batch and its camera-sorted index buffer. */
final class FluidRenderBatch implements AutoCloseable {
    private final String name;
    private final ByteBufferBuilder vertexBuilder = new ByteBufferBuilder(65536);
    private final ByteBufferBuilder indexBuilder = new ByteBufferBuilder(16384);
    private final Map<BlockPos, Integer> lighting = new HashMap<>();
    private List<ClientRegionSnapshotStore.MaterialFrame> materials = List.of();
    private List<ClientRegionSnapshotStore.GranularFrame> granular = List.of();
    private BlockPos anchor;
    private Object level;
    private Object resources;
    private VertexFormat format;
    private boolean textured;
    private Vec3 sortedCamera;
    private Object projection;
    private MeshData.SortState sortState;
    GpuBuffer vertices, indices;
    IndexType indexType;
    int indexCount;
    long buildNanos, uploadNanos, sortNanos, builds, sorts, vertexBytes, indexBytes;

    FluidRenderBatch(String name) { this.name = name; }

    void prepare(List<ClientRegionSnapshotStore.MaterialFrame> nextMaterials,
            List<ClientRegionSnapshotStore.GranularFrame> nextGranular, FluidRenderCoordinates coordinates,
            VertexFormat nextFormat, Object nextResources, boolean nextTextured, boolean shadow) {
        var client = Minecraft.getInstance();
        boolean changed = !Objects.equals(anchor, coordinates.anchor()) || format != nextFormat
                || !Objects.equals(resources, nextResources) || level != client.level || textured != nextTextured
                || !sameFrames(materials, nextMaterials, granular, nextGranular) || !sameLighting();
        materials = nextMaterials;
        granular = nextGranular;
        resources = nextResources;
        if (changed) {
            releaseBuffers();
            anchor = coordinates.anchor(); format = nextFormat; level = client.level; textured = nextTextured;
            lighting.clear();
            long start = System.nanoTime();
            var builder = new BufferBuilder(vertexBuilder, PrimitiveTopology.QUADS, format);
            for (var frame : materials)
                MaterialSurfaceGeometry.buildGeometry(coordinates.camera(), builder, frame, shadow, textured,
                        FluidRenderBackend::sprite, this::sampleLight);
            for (var frame : granular) {
                MaterialSurfaceGeometry.buildGeometry(coordinates.camera(), builder, frame.material(), shadow, textured,
                        FluidRenderBackend::sprite, this::sampleLight);
                GranularSurfaceGeometry.buildGeometry(coordinates.camera(), builder, frame, textured,
                        FluidRenderBackend::sprite, this::sampleLight);
            }
            try (var mesh = builder.build()) {
                if (mesh != null) {
                    sortState = mesh.sortQuads(indexBuilder, sorting(coordinates));
                    indexType = mesh.drawState().indexType(); indexCount = mesh.drawState().indexCount();
                    long upload = System.nanoTime(); buildNanos += upload - start; builds++;
                    var device = RenderSystem.getDevice();
                    vertices = device.createBuffer(() -> "MMM " + name + " vertices", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
                    indices = device.createBuffer(() -> "MMM " + name + " indices",
                            GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, mesh.indexBuffer());
                    vertexBytes += mesh.vertexBuffer().remaining(); indexBytes += mesh.indexBuffer().remaining();
                    uploadNanos += System.nanoTime() - upload;
                    sortedCamera = coordinates.camera(); projection = RenderSystem.getProjectionType();
                }
            }
            vertexBuilder.clear(); indexBuilder.clear();
        } else if (vertices != null && (!coordinates.camera().equals(sortedCamera)
                || projection != RenderSystem.getProjectionType())) {
            long start = System.nanoTime();
            try (var result = sortState.buildSortedIndexBuffer(indexBuilder, sorting(coordinates))) {
                long upload = System.nanoTime(); sortNanos += upload - start; sorts++;
                var data = result.byteBuffer();
                RenderSystem.getDevice().createCommandEncoder().writeToBuffer(indices.slice(0, data.remaining()), data);
                indexBytes += data.remaining(); uploadNanos += System.nanoTime() - upload;
            }
            indexBuilder.clear(); sortedCamera = coordinates.camera(); projection = RenderSystem.getProjectionType();
        }
    }

    private static VertexSorting sorting(FluidRenderCoordinates coordinates) {
        var sorting = RenderSystem.getProjectionType().vertexSorting();
        return sorting == VertexSorting.DISTANCE_TO_ORIGIN
                ? VertexSorting.byDistance(coordinates.cameraX(), coordinates.cameraY(), coordinates.cameraZ()) : sorting;
    }

    private int sampleLight(BlockPos position) { return lighting.computeIfAbsent(position.immutable(), FluidRenderBatch::light); }
    private static int light(BlockPos position) {
        var level = Minecraft.getInstance().level;
        return level == null ? 0 : level.getBrightness(LightLayer.BLOCK, position) << 4
                | level.getBrightness(LightLayer.SKY, position) << 20;
    }
    private boolean sameLighting() {
        for (var entry : lighting.entrySet()) if (entry.getValue() != light(entry.getKey())) return false;
        return true;
    }

    static boolean sameFrames(List<ClientRegionSnapshotStore.MaterialFrame> a,
            List<ClientRegionSnapshotStore.MaterialFrame> b, List<ClientRegionSnapshotStore.GranularFrame> ga,
            List<ClientRegionSnapshotStore.GranularFrame> gb) {
        if (a.size() != b.size() || ga.size() != gb.size()) return false;
        for (int i = 0; i < a.size(); i++) if (!sameMaterial(a.get(i), b.get(i))) return false;
        for (int i = 0; i < ga.size(); i++) {
            var x = ga.get(i); var y = gb.get(i);
            if (x == y) continue;
            if (!sameMaterial(x.material(), y.material()) || !Arrays.equals(x.bulkFill(), y.bulkFill())
                    || !Arrays.equals(x.porosity(), y.porosity()) || !Arrays.equals(x.topMaterial(), y.topMaterial())
                    || !x.payload().granularMaterials().equals(y.payload().granularMaterials())
                    || !x.payload().activeParcels().equals(y.payload().activeParcels())) return false;
        }
        return true;
    }
    private static boolean sameMaterial(ClientRegionSnapshotStore.MaterialFrame a, ClientRegionSnapshotStore.MaterialFrame b) {
        return a == b || a.mesh() == b.mesh() && a.payload().origin().equals(b.payload().origin())
                && a.payload().dimension().equals(b.payload().dimension())
                && a.payload().materials().equals(b.payload().materials()) && a.triangles().equals(b.triangles());
    }
    void invalidate() { resources = null; }
    void clear() {
        releaseBuffers(); materials = List.of(); granular = List.of(); lighting.clear();
        anchor = null; resources = null; level = null;
    }
    private void releaseBuffers() {
        if (vertices != null) vertices.close();
        if (indices != null) indices.close();
        vertices = indices = null; sortState = null; indexCount = 0;
    }
    void resetCosts() { buildNanos = uploadNanos = sortNanos = builds = sorts = vertexBytes = indexBytes = 0; }
    @Override public void close() { clear(); vertexBuilder.close(); indexBuilder.close(); }
}
