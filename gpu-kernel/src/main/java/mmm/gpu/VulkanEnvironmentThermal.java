package mmm.gpu;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.vulkan.VkCommandBuffer;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Device-resident finite-volume heat exchange over a static world-space graph. */
final class VulkanEnvironmentThermal implements AutoCloseable {
    record Node(double energy, double[] intercept, double[] capacity, double[] upperEnergy, double[] upperTemperature) {
        Node(double energy, double intercept, double capacity) {
            this(energy, new double[]{intercept}, new double[]{capacity}, new double[]{Double.POSITIVE_INFINITY}, new double[]{0});
        }
        Node {
            intercept = intercept.clone(); capacity = capacity.clone(); upperEnergy = upperEnergy.clone(); upperTemperature = upperTemperature.clone();
            if (!Double.isFinite(energy) || capacity.length == 0 || capacity.length != intercept.length || capacity.length != upperEnergy.length || capacity.length != upperTemperature.length)
                throw new IllegalArgumentException("Invalid environment node");
            for (int i = 0; i < capacity.length; i++) if (!Double.isFinite(intercept[i]) || !(capacity[i] > 0)
                    || !Double.isFinite(capacity[i]) || Double.isNaN(upperEnergy[i]) || i > 0 && upperEnergy[i] <= upperEnergy[i-1])
                throw new IllegalArgumentException("Invalid environment caloric curve");
            if (upperEnergy[upperEnergy.length-1] != Double.POSITIVE_INFINITY)
                throw new IllegalArgumentException("Environment caloric curve needs a final segment");
        }
    }
    /** Endpoint b == -1 denotes a prescribed far-field temperature. */
    record Edge(int a, int b, double conductance, double farTemperature) {
        Edge {
            if (a < 0 || b < -1 || a == b || !(conductance > 0) || !Double.isFinite(conductance)
                    || (b == -1 && (!(farTemperature > 0) || !Double.isFinite(farTemperature))))
                throw new IllegalArgumentException("Invalid environment edge");
        }
    }
    enum Field { ENERGY(8), INTERCEPT(8), CAPACITY(8), TEMPERATURE(8), RATE(8),
        EDGE_A(4), EDGE_B(4), CONDUCTANCE(8), FAR_TEMPERATURE(8), FLUX(8),
        INCIDENT_OFFSETS(4), INCIDENT_EDGES(4), ENERGY_CHANGE(8), FAR_SOURCE(8), FAR_SINK(8), SEGMENT_COUNTS(4), UPPER_ENERGY(8), REGION_BEFORE(8), REGION_AFTER(8),
        UPPER_TEMPERATURE(8), SURFACE_INTERCEPT(8), SURFACE_CAPACITY(8), SURFACE_AMOUNTS(8), SURFACE_RESIDUALS(8);
        final int width; Field(int width) { this.width = width; }
    }
    static final class Layout {
        final int nodes, edges, segments;
        final double maximumRate;
        final long[] offsets = new long[Field.values().length];
        final int[] counts = new int[Field.values().length];
        final long bytes;
        Layout(int nodes, int edges, int incidents, int segments, int surfaceSpecies, double maximumRate, long base, long alignment) {
            if (nodes < 1 || edges < 0 || incidents < 0 || base < 0 || alignment < 8 || (alignment & (alignment - 1)) != 0)
                throw new IllegalArgumentException("Invalid environment layout");
            this.nodes = nodes; this.edges = edges; this.segments = segments; this.maximumRate = maximumRate;
            long cursor = base;
            for (var field : Field.values()) {
                cursor = align(cursor, alignment); offsets[field.ordinal()] = cursor;
                int count = switch (field) {
                    case EDGE_A, EDGE_B, CONDUCTANCE, FAR_TEMPERATURE, FLUX, FAR_SOURCE, FAR_SINK -> edges;
                    case INCIDENT_OFFSETS -> nodes + 1;
                    case INCIDENT_EDGES -> incidents;
                    case ENERGY_CHANGE -> Math.multiplyExact(nodes, 2);
                    case INTERCEPT, CAPACITY, UPPER_ENERGY, UPPER_TEMPERATURE -> Math.multiplyExact(nodes, segments);
                    case SURFACE_AMOUNTS, SURFACE_RESIDUALS -> surfaceSpecies;
                    default -> nodes;
                };
                counts[field.ordinal()] = count;
                cursor = Math.addExact(cursor, Math.max(4, Math.multiplyExact((long) count, field.width)));
            }
            bytes = align(cursor, alignment);
            Math.toIntExact(bytes / 4);
        }
        int word(Field f) { return Math.toIntExact(offsets[f.ordinal()] / 4); }
        long offset(Field f) { return offsets[f.ordinal()]; }
        VulkanLiquidStateLayout.Column column(Field f) { return new VulkanLiquidStateLayout.Column(offset(f), counts[f.ordinal()], f.width); }
        void initialize(ByteBuffer b, List<Node> nodeList, List<Edge> edgeList) {
            if (nodeList.size() != nodes || edgeList.size() != edges || b.capacity() < bytes) throw new IllegalArgumentException("Environment graph differs from layout");
            b.order(ByteOrder.LITTLE_ENDIAN);
            var incidents = new ArrayList<List<Integer>>(nodes);
            for (int n = 0; n < nodes; n++) {
                var node = nodeList.get(n); incidents.add(new ArrayList<>());
                putDouble(b, Field.ENERGY, n, node.energy()); putInt(b, Field.SEGMENT_COUNTS, n, node.capacity().length);
                for (int part=0; part<node.capacity().length; part++) {
                    int at=n*segments+part; putDouble(b, Field.INTERCEPT, at, node.intercept()[part]);
                    putDouble(b, Field.CAPACITY, at, node.capacity()[part]); putDouble(b, Field.UPPER_ENERGY, at, node.upperEnergy()[part]);
                    putDouble(b, Field.UPPER_TEMPERATURE, at, node.upperTemperature()[part]);
                }
            }
            for (int e = 0; e < edges; e++) {
                var edge = edgeList.get(e);
                if (edge.a() >= nodes || edge.b() >= nodes) throw new IllegalArgumentException("Environment endpoint exceeds nodes");
                putInt(b, Field.EDGE_A, e, edge.a()); putInt(b, Field.EDGE_B, e, edge.b());
                putDouble(b, Field.CONDUCTANCE, e, edge.conductance()); putDouble(b, Field.FAR_TEMPERATURE, e, edge.farTemperature());
                incidents.get(edge.a()).add(e + 1); if (edge.b() >= 0) incidents.get(edge.b()).add(-e - 1);
            }
            int cursor = 0;
            for (int n = 0; n < nodes; n++) {
                putInt(b, Field.INCIDENT_OFFSETS, n, cursor);
                for (int edge : incidents.get(n)) putInt(b, Field.INCIDENT_EDGES, cursor++, edge);
            }
            putInt(b, Field.INCIDENT_OFFSETS, nodes, cursor);
            if (cursor != counts[Field.INCIDENT_EDGES.ordinal()]) throw new IllegalArgumentException("Environment incidence count differs");
        }
        private void putInt(ByteBuffer b, Field f, int i, int value) { b.putInt(Math.toIntExact(offset(f) + 4L * i), value); }
        private void putDouble(ByteBuffer b, Field f, int i, double value) { b.putDouble(Math.toIntExact(offset(f) + 8L * i), value); }
    }
    static Layout layout(List<Node> nodes, List<Edge> edges, long base, long alignment) {
        return layout(nodes, edges, 0, base, alignment);
    }
    static Layout layout(List<Node> nodes, List<Edge> edges, int surfaceSpecies, long base, long alignment) {
        int incidents = 0; for (var edge : edges) incidents = Math.addExact(incidents, edge.b() < 0 ? 1 : 2);
        int segments = nodes.stream().mapToInt(node -> node.capacity().length).max().orElse(1);
        double[] degrees=new double[nodes.size()];
        for(var edge:edges){degrees[edge.a()]+=edge.conductance();if(edge.b()>=0)degrees[edge.b()]+=edge.conductance();}
        double maximumRate=0;
        for(int i=0;i<nodes.size();i++) {
            double minimum=java.util.Arrays.stream(nodes.get(i).capacity()).min().orElseThrow();
            maximumRate=Math.max(maximumRate,degrees[i]/minimum);
        }
        return new Layout(nodes.size(), edges.size(), incidents, segments, surfaceSpecies, maximumRate, base, alignment);
    }
    record Accounting(VulkanEnergyLedger.Layout source, VulkanEnergyLedger.Layout sink, VulkanEnergyLedger.Layout roundoff,
                      VulkanEnergyLedger.Layout region, long bytes) {
        void initialize(ByteBuffer destination) { source.initializeHeader(destination); sink.initializeHeader(destination); roundoff.initializeHeader(destination); region.initializeHeader(destination); }
    }
    static Accounting accounting(Layout layout, long alignment) {
        var changes = new VulkanEnergyLedger.Stream(1, layout.word(Field.ENERGY), layout.nodes, 2, 1);
        var previous = new VulkanEnergyLedger.Stream(1, layout.word(Field.ENERGY_CHANGE), layout.nodes, 4, -1);
        var source = new VulkanEnergyLedger.Stream(1, layout.word(Field.FAR_SOURCE), layout.edges, 2, 1);
        var negativeSource = new VulkanEnergyLedger.Stream(1, layout.word(Field.FAR_SOURCE), layout.edges, 2, -1);
        var sink = new VulkanEnergyLedger.Stream(1, layout.word(Field.FAR_SINK), layout.edges, 2, 1);
        var sourceLedger = VulkanEnergyLedger.layout(List.of(source), layout.bytes, alignment);
        var sinkLedger = VulkanEnergyLedger.layout(List.of(sink), sourceLedger.endOffset(), alignment);
        var roundoff = VulkanEnergyLedger.layout(List.of(changes, previous, negativeSource, sink), sinkLedger.endOffset(), alignment);
        var oldRegion = new VulkanEnergyLedger.Stream(1, layout.word(Field.REGION_BEFORE), layout.nodes, 2, -1);
        var newRegion = new VulkanEnergyLedger.Stream(1, layout.word(Field.REGION_AFTER), layout.nodes, 2, 1);
        var region = VulkanEnergyLedger.layout(List.of(oldRegion, newRegion), roundoff.endOffset(), alignment);
        return new Accounting(sourceLedger, sinkLedger, roundoff, region, region.endOffset());
    }
    private static long align(long n, long a) { return Math.addExact(n, a - 1) & -a; }
    private final FlipKernelBindings bindings;
    VulkanEnvironmentThermal(VulkanDeviceContext context) {
        bindings = new FlipKernelBindings(context, 2, shader(), 128);
    }
    void record(VkCommandBuffer command, Layout layout, GpuBuffer arena, GpuBuffer errors, double duration) {
        if (!(duration > 0) || !Double.isFinite(duration)) throw new IllegalArgumentException("Invalid environment duration");
        bindings.rebind(List.of(arena, errors));
        stage(command, layout, 0, layout.nodes, duration);
        stage(command, layout, 1, layout.edges, duration);
        stage(command, layout, 2, layout.nodes, duration);
    }
    void recordAccounting(VkCommandBuffer command, Accounting accounting, VulkanEnergyLedger ledger,
                          GpuBuffer source, GpuBuffer candidate, GpuBuffer errors) {
        ledger.rebindRanges(new FlipKernelBindings.BufferRange(source), new FlipKernelBindings.BufferRange(candidate),
                new FlipKernelBindings.BufferRange(errors));
        ledger.record(command, accounting.source());
        ledger.recordAccumulate(command, accounting.source(), Math.toIntExact(accounting.source().result().offset() / 4));
        ledger.record(command, accounting.sink());
        ledger.recordAccumulate(command, accounting.sink(), Math.toIntExact(accounting.sink().result().offset() / 4));
        ledger.record(command, accounting.roundoff());
        ledger.recordAccumulate(command, accounting.roundoff(), Math.toIntExact(accounting.roundoff().result().offset() / 4));
    }
    private void stage(VkCommandBuffer command, Layout l, int stage, int count, double duration) {
        if (count == 0) return;
        try (var stack = stackPush()) {
            bindings.bind(command, stack);
            var push = stack.malloc(128).order(ByteOrder.nativeOrder());
            push.putInt(stage).putInt(count).putDouble(duration);
            for (var field : Field.values()) push.putInt(l.word(field));
            push.putInt(l.segments).putInt(0).putDouble(l.maximumRate);
            push.flip();
            vkCmdPushConstants(command, bindings.pipelineLayout(), VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
            int groups = (count + 127) / 128;
            vkCmdDispatch(command, Math.min(groups, 65535), (groups + 65534) / 65535, 1);
            VulkanRegionStorage.barrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, stack);
        }
    }
    private static String shader() { return VulkanLiquidPhaseRows.resource("environment-thermal.comp"); }
    @Override public void close() { bindings.close(); }
}
