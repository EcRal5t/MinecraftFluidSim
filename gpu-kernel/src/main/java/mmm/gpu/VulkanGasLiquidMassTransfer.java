package mmm.gpu;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Local Henry/two-film exchange at unchanged liquid volume, with chamber-owned inventory updates. */
final class VulkanGasLiquidMassTransfer implements AutoCloseable {
    /** Header word positions shared with the shader. */
    private enum Header {
        CELL_COUNT(0),
        RULE_COUNT(1),
        PHASE_ROWS_HEADER(2),
        GAS_HEADER(3),
        LIQUID_CALORIC(4),
        GEOMETRY_HEADER(5),
        PHASE_MOTION_CELLS(6),
        PHASE_PENDING_SUMMARY(7),
        GROUP_COUNT(8),
        FACE_COUNT(9),
        MATERIAL_COUNT(10),
        SPECIES_COUNT(11),
        PENDING_COUNT(12),
        PROPOSAL_CAPACITY(13),
        CELL_SIZE(16),
        GRAVITY(18),
        PENDING_CELL(20),
        PENDING_MATERIAL(21),
        PENDING_SUPPORT(22),
        PENDING_RULE(23),
        PENDING_SECONDS(24),
        RESIDUAL_MOLES(25),
        DURATION(28),
        ACTIVITY_CELLS(30),
        ACTIVITY_FACES(31);
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

    private static final int WORK_FIELDS = 48;
    private static String shaderLayout() {
        return VulkanGasGeometry.shaderLayout() + VulkanGasThermodynamics.shaderLayout() + VulkanLiquidThermodynamics.shaderLayout() + headerDefines() + "#define WORK_FIELDS " + WORK_FIELDS + "\n"
            + VulkanShaderLayout.fields("FIELD_", Field.values());
    }

    enum Field {
        HEADER(4),
        RULES(4),
        CONTROL(4),
        HEAD(4),
        NEXT(4),
        RULE(4),
        NODE(4),
        BOIL(4),
        LIMIT(8),
        DESIRED(4),
        OFFSETS(4),
        BATCHES(8),
        SCORE(8),
        SATURATION(8),
        PARTIAL(8),
        RESPONSE(8),
        GAS_RESPONSE(8),
        KG(8),
        LOWER(8),
        UPPER(8),
        TEMP(8),
        GAS_TEMP(8),
        OLD_MASS(8),
        NEW_MASS(8),
        OLD_GAS_U(8),
        NEW_GAS_U(8),
        GAS_TRANSFER(8),
        OLD_K(8),
        NEW_K(8),
        FACE_HEAT(8),
        FACE_P(8),
        PE_WORK(8),
        BIRTH_NODE(4),
        PENDING_TIME(8),
        PENDING_RESIDUAL(8),
        SOURCE_ENERGY(8),
        SINK_ENERGY(8),
        AMBIENT_SOURCE_SPECIES(8),
        AMBIENT_SINK_SPECIES(8),
        SOURCE_MASS(8),
        SINK_MASS(8);
        final int width;
        Field(int width) {
            this.width = width;
        }
    }
    static final class Layout {
        int activityCellsWord = -1, activityFacesWord = -1;
        final VulkanLiquidPhaseRows.Layout rows;
        final VulkanGasPhaseChange.Inputs inputs;
        final VulkanReactionProgressLayout progress;
        final int rules, groups, cells, faces, proposals;
        final ByteBuffer ruleBytes;
        final EnumMap<Field, VulkanLiquidStateLayout.Column> columns = new EnumMap<>(Field.class);
        final long endOffset;
        Layout(VulkanRegionReactions.Configuration config, VulkanLiquidPhaseRows.Layout rows,
            VulkanGasPhaseChange.Inputs inputs, VulkanReactionProgressLayout progress, long base, long alignment) {
            this.rows = rows;
            this.inputs = inputs;
            this.progress = progress;
            cells = rows.source.cellCount();
            rules = config.chemistry().gasLiquidMassTransfers().size();
            proposals = Math.multiplyExact(cells, rules);
            if (progress.proposalCapacity() < proposals)
                throw new IllegalArgumentException("Film pending capacity is smaller than cells times rules");
            faces = rows.source.column(VulkanLiquidStateLayout.Field.MOMENTUM_X).count()
                + rows.source.column(VulkanLiquidStateLayout.Field.MOMENTUM_Y).count()
                + rows.source.column(VulkanLiquidStateLayout.Field.MOMENTUM_Z).count();
            ruleBytes = ByteBuffer.allocateDirect(96 * rules).order(ByteOrder.LITTLE_ENDIAN);
            for (var r : config.chemistry().gasLiquidMassTransfers()) {
                var phase = config.chemistry().phases().get(config.chemistry().phaseHandle(r.liquidPhaseId()));
                int material = config.fluids().handle(phase.materialId()),
                    component = inputs.gas().gas().definitions().componentIds().indexOf(r.gasComponentId()),
                    species = config.chemistry().speciesHandle(r.speciesId());
                if (component < 0
                    || !inputs.gas().gas().definitions().allowedSpecies().get(component).contains(r.speciesId()))
                    throw new IllegalArgumentException("Film component does not declare species: " + r.id());
                var molar = config.chemistry().species().get(species).molarMassKgPerMol().divide(
                    mmm.chemistry.ChemicalLimits.QUANTA_PER_MOL);
                long mass = mmm.units.Mass.exactUnits(molar.numerator(), molar.denominator());
                double fraction = 0;
                for (var a : config.gas().ambient())
                    if (a.componentId().equals(r.gasComponentId()))
                        fraction = a.fraction().toDouble();
                ruleBytes.putInt(material)
                    .putInt(component)
                    .putInt(species)
                    .putInt(config.definitions().identity().rules().indexOf(r.id()))
                    .putLong(mass);
                for (double v : new double[] {r.referenceTemperatureK(), r.henrySolubilityMolPerM3Pa(),
                         r.temperatureSlopeK(), r.liquidFilmVelocityMPerS(), r.gasFilmVelocityMPerS(),
                         r.minimumTemperatureK(), r.maximumTemperatureK(), fraction * 101325.0, 0})
                    ruleBytes.putDouble(v);
            }
            ruleBytes.flip();
            groups = Math.addExact(inputs.gas().gas().nodeCapacity(), 1);
            long cursor = base;
            for (var f : Field.values()) {
                int count = switch (f) {
                    case HEADER -> 160;
                    case RULES -> 24 * rules;
                    case CONTROL -> 8;
                    case HEAD -> groups;
                    case OLD_GAS_U, NEW_GAS_U -> inputs.gas().gas().nodeCapacity();
                    case OLD_K, NEW_K, FACE_P -> faces;
                    case FACE_HEAT -> 2 * faces;
                    case PENDING_TIME, PENDING_RESIDUAL -> proposals;
                    case AMBIENT_SOURCE_SPECIES, AMBIENT_SINK_SPECIES ->
                        Math.multiplyExact(rows.source.materialCount(), rows.species);
                    case SOURCE_MASS, SINK_MASS -> rows.source.materialCount();
                    default -> cells;
                };
                var col = new VulkanLiquidStateLayout.Column(align(cursor, alignment), count, f.width);
                columns.put(f, col);
                cursor = col.offset() + col.bindingBytes();
            }
            endOffset = align(cursor, alignment);
        }
        VulkanLiquidStateLayout.Column column(Field f) {
            return columns.get(f);
        }
        int word(Field f) {
            return Math.toIntExact(column(f).offset() / 4);
        }
        long feedbackOffset() {
            return column(Field.CONTROL).offset();
        }
        ByteBuffer headerBytes() {
            var b = ByteBuffer.allocateDirect(640).order(ByteOrder.LITTLE_ENDIAN);
            int[] h = new int[160];
            h[Header.CELL_COUNT.word] = cells;
            h[Header.RULE_COUNT.word] = rules;
            h[Header.PHASE_ROWS_HEADER.word] = rows.headerWord();
            h[Header.GAS_HEADER.word] = inputs.gas().headerWordOffset();
            h[Header.LIQUID_CALORIC.word] = Math.toIntExact(inputs.liquid().header().offset() / 4);
            h[Header.GEOMETRY_HEADER.word] = inputs.geometry().headerWordOffset();
            h[Header.PHASE_MOTION_CELLS.word] = progress.word(VulkanReactionProgressLayout.Field.PHASE_MOTION_CELLS);
            h[Header.PHASE_PENDING_SUMMARY.word] =
                progress.word(VulkanReactionProgressLayout.Field.PHASE_PENDING_SUMMARY);
            h[Header.GROUP_COUNT.word] = groups;
            h[Header.FACE_COUNT.word] = faces;
            h[Header.MATERIAL_COUNT.word] = rows.source.materialCount();
            h[Header.SPECIES_COUNT.word] = rows.species;
            h[Header.PENDING_COUNT.word] = progress.word(VulkanReactionProgressLayout.Field.PENDING_COUNT);
            h[Header.PROPOSAL_CAPACITY.word] = progress.proposalCapacity();
            h[Header.PENDING_CELL.word] = progress.word(VulkanReactionProgressLayout.Field.PENDING_CELL);
            h[Header.PENDING_MATERIAL.word] = progress.word(VulkanReactionProgressLayout.Field.PENDING_MATERIAL);
            h[Header.PENDING_SUPPORT.word] = progress.word(VulkanReactionProgressLayout.Field.PENDING_SUPPORT);
            h[Header.PENDING_RULE.word] = progress.word(VulkanReactionProgressLayout.Field.PENDING_RULE);
            h[Header.PENDING_SECONDS.word] = progress.word(VulkanReactionProgressLayout.Field.PENDING_SECONDS);
            h[Header.RESIDUAL_MOLES.word] = progress.word(VulkanReactionProgressLayout.Field.RESIDUAL_MOLES);
            for (var f : Field.values())
                h[WORK_FIELDS + f.ordinal()] = word(f);
            h[Header.ACTIVITY_CELLS.word] = activityCellsWord;
            h[Header.ACTIVITY_FACES.word] = activityFacesWord;
            for (int i = 0; i < h.length; i++)
                b.putInt(4 * i, h[i]);
            b.putDouble(4 * Header.CELL_SIZE.word, inputs.geometry().cellSize())
                .putDouble(4 * Header.GRAVITY.word, inputs.gravity());
            return b;
        }
        List<VulkanGasThermodynamics.TableChunk> headers() {
            return List.of(new VulkanGasThermodynamics.TableChunk(column(Field.HEADER).offset(), headerBytes()),
                new VulkanGasThermodynamics.TableChunk(
                    rows.column(VulkanLiquidPhaseRows.Field.HEADER).offset(), rows.headerBytes()));
        }
        List<VulkanGasThermodynamics.TableChunk> tables() {
            return List.of(new VulkanGasThermodynamics.TableChunk(column(Field.RULES).offset(), ruleBytes.duplicate()));
        }
        List<VulkanLiquidStateLayout.Column> stateColumns() {
            return List.of(column(Field.AMBIENT_SOURCE_SPECIES), column(Field.AMBIENT_SINK_SPECIES),
                column(Field.SOURCE_MASS), column(Field.SINK_MASS));
        }
        List<VulkanEnergyLedger.Stream> roundoffStreams() {
            var a = new ArrayList<>(rows.energyChangeStreams());
            a.add(stream(Field.NEW_GAS_U, 1));
            a.add(stream(Field.OLD_GAS_U, -1));
            a.add(stream(Field.NEW_K, 1));
            a.add(stream(Field.OLD_K, -1));
            a.add(stream(Field.PE_WORK, -1));
            a.add(stream(Field.SOURCE_ENERGY, -1));
            a.add(stream(Field.SINK_ENERGY, 1));
            return List.copyOf(a);
        }
        List<VulkanEnergyLedger.Stream> mechanicalWorkStreams() {
            return List.of(stream(Field.OLD_K, 1), stream(Field.NEW_K, -1), stream(Field.PE_WORK, 1));
        }
        List<VulkanEnergyLedger.Stream> sourceStreams() {
            return List.of(stream(Field.SOURCE_ENERGY, 1));
        }
        List<VulkanEnergyLedger.Stream> sinkStreams() {
            return List.of(stream(Field.SINK_ENERGY, 1));
        }
        private VulkanEnergyLedger.Stream stream(Field f, int sign) {
            return new VulkanEnergyLedger.Stream(1, word(f), column(f).count(), 2, sign);
        }
    }
    private final FlipKernelBindings bindings;
    private final VulkanLiquidPhaseRows rows;
    VulkanGasLiquidMassTransfer(VulkanDeviceContext c) {
        bindings = new FlipKernelBindings(c, 3,
            VulkanLiquidPhaseRows.resource("gas-liquid-mass-transfer.comp")
                .replace("// HOST_LAYOUT", shaderLayout())
                .replace("// PHASE_ROWS_LIBRARY", VulkanLiquidPhaseRows.resource("liquid-phase-rows.glsl")),
            16);
        try {
            rows = new VulkanLiquidPhaseRows(c);
        } catch (RuntimeException | Error e) {
            bindings.close();
            throw e;
        }
    }
    void rebindRanges(FlipKernelBindings.BufferRange arena, FlipKernelBindings.BufferRange errors) {
        bindings.rebindRanges(List.of(arena, errors, arena));
        rows.rebindRanges(arena, errors);
    }
    int execute(VulkanRegionStorage.Candidate candidate, Layout l, double duration, VulkanGasThermodynamics.Layout eos,
        VulkanGasGeometry.Layout geometry) {
        if (!(Double.isFinite(duration) && duration >= 0))
            throw new IllegalArgumentException("Invalid film duration");
        var prepared = candidate.continueBatch((command, input, output, errors, staging, stack) -> {
            rebindRanges(new FlipKernelBindings.BufferRange(output), new FlipKernelBindings.BufferRange(errors));
            var h = l.headerBytes();
            h.putInt(4 * Header.GAS_HEADER.word, eos.headerWordOffset())
                .putInt(4 * Header.GEOMETRY_HEADER.word, geometry.headerWordOffset())
                .putDouble(4 * Header.DURATION.word, duration);
            vkCmdUpdateBuffer(command, output.handle(), l.column(Field.HEADER).offset(), h);
            VulkanRegionStorage.barrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT, stack);
            recordPrepare(command, l);
        }, l.rows.feedbackOffset(), 16);
        if (prepared.error() != 0)
            return prepared.error();
        if (prepared.feedback().getInt(0) == 1)
            throw new VulkanRegionReactions.CapacityGrowth(
                prepared.feedback().getInt(8), prepared.feedback().getInt(12), 0, 0, 0);
        if (prepared.feedback().getInt(0) != 2)
            throw new IllegalStateException("Film liquid successor is incomplete");
        return candidate
            .continueBatch(
                (command, input, output, errors, staging, stack) -> recordPublish(command, l), l.feedbackOffset(), 16)
            .error();
    }
    void recordPrepare(VkCommandBuffer c, Layout l) {
        rows.recordImport(c, l.rows);
        stage(
            c, l, 0, Math.max(l.proposals, Math.max(l.groups, Math.max(l.cells, l.inputs.gas().gas().nodeCapacity()))));
        stage(c, l, 9, l.progress.proposalCapacity());
        stage(c, l, 1, l.cells);
        stage(c, l, 4, l.groups);
        stage(c, l, 5, l.faces);
        stage(c, l, 6, l.cells);
        stage(c, l, 7, l.inputs.gas().gas().nodeCapacity());
        rows.recordAssemble(c, l.rows);
    }
    void recordPublish(VkCommandBuffer c, Layout l) {
        rows.recordPublish(c, l.rows);
        stage(c, l, 10, 1);
        stage(c, l, 8, Math.max(l.faces, l.proposals));
    }
    private void stage(VkCommandBuffer c, Layout l, int stage, int count) {
        if (count == 0)
            return;
        try (var s = stackPush()) {
            bindings.bind(c, s);
            vkCmdPushConstants(c, bindings.pipelineLayout(), VK_SHADER_STAGE_COMPUTE_BIT, 0,
                s.ints(l.word(Field.HEADER), stage, count, 0));
            int g = (count + 127) / 128;
            vkCmdDispatch(c, Math.min(g, 65535), (g + 65534) / 65535, 1);
            VulkanRegionStorage.barrier(c, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, s);
        }
    }
    private static long align(long n, long a) {
        return Math.addExact(n, a - 1) & -a;
    }
    @Override
    public void close() {
        try {
            rows.close();
        } finally {
            bindings.close();
        }
    }
}
