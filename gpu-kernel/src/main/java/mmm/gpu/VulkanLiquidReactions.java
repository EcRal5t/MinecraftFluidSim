package mmm.gpu;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import mmm.chemistry.ChemicalRegistry;
import mmm.fluid.FluidMaterialRegistry;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Same-support liquid reactions prepare a private composition CSR before publishing owner columns. */
final class VulkanLiquidReactions implements AutoCloseable {
    /** Header word positions shared with the shader. */
    private enum Header {
        CELL_COUNT(0),
        STOCK_CAPACITY(1),
        SPECIES_COUNT(2),
        DEFINITIONS(3),
        CALORIC_HEADER(4),
        CONTROL(5),
        DIRTY_CELLS(6),
        PHASE_TABLE(7),
        DENSE(8),
        ROW_COUNTS(9),
        ROW_OFFSETS(10),
        HANDLES(11),
        AMOUNTS(12),
        RESIDUALS(13),
        ENERGIES(14),
        SPECIES_CAPACITY(15),
        TOTAL_QUANTA(40),
        INITIAL_ENERGIES(41),
        ACTIVITY_CELLS(42), PROGRESS(43), PENDING_INDEX(44), FINITE_RULE_COUNT(45);
        final int word;
        Header(int word) {
            this.word = word;
        }
    }
    private static String headerDefines() {
        var result = new StringBuilder();
        for (var field : Header.values())
            result.append("#define H_").append(field.name()).append(' ').append(field.word).append('\n');
        return result.toString();
    }

    static final int READY = 2, NEED_CAPACITY = 1;
    record Layout(VulkanLiquidStateLayout liquid, VulkanReactionDefinitions.Layout definitions,
        VulkanLiquidThermodynamics.Layout caloric, VulkanReactionProgressLayout progress, int[] phases,
        VulkanLiquidStateLayout.Column header, VulkanLiquidStateLayout.Column phaseTable,
        VulkanLiquidStateLayout.Column dense, VulkanLiquidStateLayout.Column rowCounts,
        VulkanLiquidStateLayout.Column rowOffsets, VulkanLiquidStateLayout.Column handles,
        VulkanLiquidStateLayout.Column amounts, VulkanLiquidStateLayout.Column residuals,
        VulkanLiquidStateLayout.Column energies, VulkanLiquidStateLayout.Column initialEnergies,
        VulkanLiquidStateLayout.Column totalQuanta, VulkanLiquidStateLayout.Column scan,
        VulkanLiquidStateLayout.Column pendingIndex, int finiteRuleCount, long endOffset, int activityCellsWord) {
        int word(VulkanLiquidStateLayout.Column c) {
            return Math.toIntExact(c.offset() / 4);
        }
        long feedbackOffset() {
            return progress.column(VulkanReactionProgressLayout.Field.CONTROL).offset();
        }
        ByteBuffer headerBytes() {
            var out = ByteBuffer.allocateDirect(256).order(ByteOrder.LITTLE_ENDIAN);
            int[] h = new int[64];
            h[Header.CELL_COUNT.word] = liquid.cellCount();
            h[Header.STOCK_CAPACITY.word] = liquid.stockCapacity();
            h[Header.SPECIES_COUNT.word] = definitions.speciesCount();
            h[Header.DEFINITIONS.word] = definitions.word();
            h[Header.CALORIC_HEADER.word] = word(caloric.header());
            h[Header.CONTROL.word] = progress.word(VulkanReactionProgressLayout.Field.CONTROL);
            h[Header.DIRTY_CELLS.word] = progress.word(VulkanReactionProgressLayout.Field.DIRTY_CELLS);
            h[Header.PHASE_TABLE.word] = word(phaseTable);
            h[Header.DENSE.word] = word(dense);
            h[Header.ROW_COUNTS.word] = word(rowCounts);
            h[Header.ROW_OFFSETS.word] = word(rowOffsets);
            h[Header.HANDLES.word] = word(handles);
            h[Header.AMOUNTS.word] = word(amounts);
            h[Header.RESIDUALS.word] = word(residuals);
            h[Header.ENERGIES.word] = word(energies);
            h[Header.SPECIES_CAPACITY.word] = liquid.speciesCapacity();
            h[Header.TOTAL_QUANTA.word] = word(totalQuanta);
            h[Header.INITIAL_ENERGIES.word] = word(initialEnergies);
            h[Header.ACTIVITY_CELLS.word] = activityCellsWord;
            h[Header.PROGRESS.word] = progress.word(VulkanReactionProgressLayout.Field.HEADER);
            h[Header.PENDING_INDEX.word] = word(pendingIndex);
            h[Header.FINITE_RULE_COUNT.word] = finiteRuleCount;
            for (var f : VulkanLiquidStateLayout.Field.values())
                h[16 + f.ordinal()] = Math.toIntExact(liquid.column(f).offset() / 4);
            for (int i = 0; i < h.length; i++)
                out.putInt(4 * i, h[i]);
            return out;
        }
        List<VulkanGasThermodynamics.TableChunk> tableChunks() {
            var data = ByteBuffer.allocateDirect(Math.multiplyExact(4, phases.length)).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < phases.length; i++)
                data.putInt(4 * i, phases[i]);
            return List.of(new VulkanGasThermodynamics.TableChunk(phaseTable.offset(), data));
        }
        List<VulkanEnergyLedger.Stream> roundoffStreams() {
            return List.of(new VulkanEnergyLedger.Stream(1, word(energies), energies.count(), 2, 1),
                new VulkanEnergyLedger.Stream(1, word(initialEnergies), initialEnergies.count(), 2, -1));
        }
        void initializeHeaderAndTables(ByteBuffer arena) {
            arena.duplicate().position(Math.toIntExact(header.offset())).put(headerBytes());
            for (var chunk : tableChunks())
                arena.duplicate().position(Math.toIntExact(chunk.offset())).put(chunk.bytes().duplicate());
        }
    }
    static Layout layout(VulkanLiquidStateLayout liquid, VulkanReactionDefinitions.Layout definitions,
        VulkanLiquidThermodynamics.Layout caloric, VulkanReactionProgressLayout progress, FluidMaterialRegistry fluid,
        ChemicalRegistry chemical, long base, long alignment) {
        return layout(liquid, definitions, caloric, progress, fluid, chemical, base, alignment, -1);
    }
    static Layout layout(VulkanLiquidStateLayout liquid, VulkanReactionDefinitions.Layout definitions,
        VulkanLiquidThermodynamics.Layout caloric, VulkanReactionProgressLayout progress, FluidMaterialRegistry fluid,
        ChemicalRegistry chemical, long base, long alignment, int activityCellsWord) {
        if (base < Math.max(progress.endOffset(), caloric.endOffset()) || alignment < 8
            || (alignment & (alignment - 1)) != 0 || definitions.speciesCount() > 256
            || caloric.speciesStride() < definitions.speciesCount()
            || !definitions.identity().chemicalFingerprint().equals(chemical.fingerprint()))
            throw new IllegalArgumentException("Invalid liquid reaction workspace");
        int[] phases = new int[fluid.materials().size()];
        java.util.Arrays.fill(phases, -1);
        for (int i = 0; i < phases.length; i++)
            for (int j = 0; j < chemical.phases().size(); j++)
                if (chemical.phases().get(j).materialId().equals(fluid.material(i).id()))
                    phases[i] = j;
        long[] c = {base};
        var header = column(c, 64, 4, alignment);
        var phase = column(c, phases.length, 4, alignment);
        var dense = column(c, Math.multiplyExact(liquid.stockCapacity(), definitions.speciesCount()), 8, alignment);
        var counts = column(c, liquid.stockCapacity(), 4, alignment);
        var offsets = column(c, liquid.stockCapacity() + 1, 4, alignment);
        var handles = column(c, liquid.speciesCapacity(), 4, alignment);
        var amounts = column(c, liquid.speciesCapacity(), 8, alignment);
        var residual = column(c, liquid.stockCapacity(), 8, alignment);
        var energy = column(c, liquid.stockCapacity(), 8, alignment);
        var initialEnergy = column(c, liquid.stockCapacity(), 8, alignment);
        var total = column(c, 1, 8, alignment);
        int finiteRuleCount=Math.toIntExact(chemical.reactions().stream()
            .filter(rule->rule.kind()==mmm.chemistry.ChemicalReaction.Kind.FINITE_RATE).count());
        var pendingIndex=column(c,Math.multiplyExact(liquid.stockCapacity(),finiteRuleCount),4,alignment);
        var scan =
            column(c, Math.toIntExact(VulkanPrefixScan.workspaceBytes(liquid.stockCapacity()) / 4), 4, alignment);
        return new Layout(liquid, definitions, caloric, progress, phases, header, phase, dense, counts, offsets,
            handles, amounts, residual, energy, initialEnergy, total, scan, pendingIndex, finiteRuleCount,
            align(c[0], alignment), activityCellsWord);
    }
    private final FlipKernelBindings bindings;
    private final VulkanPrefixScan scan;
    private FlipKernelBindings.BufferRange activityArena;
    VulkanLiquidReactions(VulkanDeviceContext context, ChemicalRegistry chemistry) {
        bindings = new FlipKernelBindings(context, 3, shader(chemistry.species().size(), chemistry.reactions().size()), 32);
        try {
            scan = new VulkanPrefixScan(context);
        } catch (RuntimeException | LinkageError failure) {
            bindings.close();
            throw failure;
        }
    }
    void rebindRanges(FlipKernelBindings.BufferRange arena, FlipKernelBindings.BufferRange errors) {
        activityArena=arena;
        bindings.rebindRanges(List.of(arena, errors, arena));
        scan.rebindRanges(arena, arena, arena, errors);
    }
    void recordPrepare(VkCommandBuffer command, Layout l) { recordPrepare(command, l, 0.05, 1.0); }
    void recordPrepare(VkCommandBuffer command, Layout l, double duration, double cellSize) {
        stage(command, l, 6, 1);
        stage(command, l, 0, l.liquid.stockCapacity());
        if (l.finiteRuleCount() > 0) {
            stage(command, l, 7, l.pendingIndex().count());
            stage(command, l, 8, l.progress().pendingCapacity());
        }
        stage(command, l, 1, l.liquid.cellCount(), duration, cellSize * cellSize * cellSize);
        stage(command, l, 2, l.liquid.stockCapacity());
        scan.record(command, l.liquid.stockCapacity(), l.word(l.rowCounts), l.word(l.rowOffsets), l.word(l.scan));
        stage(command, l, 3, 1);
        stage(command, l, 4, l.liquid.stockCapacity());
    }
    /** The activity result is a transient word in the existing progress control. */
    void recordActivity(VkCommandBuffer command, Layout l, boolean solid, int solidCountsWord) {
        if (!solid) {
            try (var stack = stackPush()) {
                VulkanRegionStorage.barrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_ACCESS_TRANSFER_WRITE_BIT, stack);
                vkCmdFillBuffer(command, activityArena.buffer().handle(),
                    activityArena.offset() + l.progress().column(VulkanReactionProgressLayout.Field.CONTROL).offset() + 28, 4, 0);
                VulkanRegionStorage.barrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, stack);
            }
        }
        stage(command, l, solid ? 10 : 9, l.liquid.stockCapacity(), 0, 1, solidCountsWord);
    }
    /** CONTROL uses the shared progress schema; index 2 reports required species capacity. */
    void recordPublish(VkCommandBuffer command, Layout l) {
        stage(command, l, 5, Math.max(l.liquid.stockCapacity() + 1, l.liquid.speciesCapacity()));
    }
    private void stage(VkCommandBuffer command, Layout l, int stage, int count) { stage(command, l, stage, count, 0, 1); }
    private void stage(VkCommandBuffer command, Layout l, int stage, int count, double duration, double volume) {
        stage(command,l,stage,count,duration,volume,0);
    }
    private void stage(VkCommandBuffer command, Layout l, int stage, int count, double duration, double volume, int extra) {
        if (count == 0)
            return;
        try (var stack = stackPush()) {
            bindings.bind(command, stack);
            vkCmdPushConstants(command, bindings.pipelineLayout(), VK_SHADER_STAGE_COMPUTE_BIT, 0,
                stack.calloc(32).putInt(0,l.word(l.header)).putInt(4,stage).putInt(8,count).putInt(12,extra).putDouble(16,duration).putDouble(24,volume));
            int groups = (count + 127) / 128;
            vkCmdDispatch(command, Math.min(groups, 65535), (groups + 65534) / 65535, 1);
            VulkanRegionStorage.barrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, stack);
        }
    }
    private static VulkanLiquidStateLayout.Column column(long[] c, int n, int width, long alignment) {
        var result = new VulkanLiquidStateLayout.Column(align(c[0], alignment), n, width);
        c[0] = result.offset() + result.bindingBytes();
        return result;
    }
    private static long align(long n, long a) {
        return Math.addExact(n, a - 1) & -a;
    }
    private static String shaderLayout() {
        return VulkanLiquidThermodynamics.shaderLayout() + headerDefines() + "#define STOCK_FIELDS 16\n"
            + VulkanShaderLayout.fields("STOCK_", VulkanLiquidStateLayout.Field.values())
            + VulkanShaderLayout.fields("PROGRESS_", VulkanReactionProgressLayout.Field.values());
    }
    private static String shader(int species, int rules) {
        try (var in = VulkanLiquidReactions.class.getResourceAsStream(
                 "/assets/mmm_gpu_probe/shaders/liquid-reactions.comp")) {
            if (in == null)
                throw new IllegalStateException("Liquid reaction shader missing");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("// HOST_LAYOUT", shaderLayout()+"\n#define SPECIES_WORKSPACE "+Math.max(1,species)+"\n#define REACTION_WORKSPACE "+Math.max(1,rules)+"\n");
        } catch (IOException failure) {
            throw new IllegalStateException("Liquid reaction shader unavailable", failure);
        }
    }
    @Override
    public void close() {
        scan.close();
        bindings.close();
    }
}
