package mmm.gpu;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import mmm.chemistry.ChemicalRegistry;
import mmm.fluid.FluidMaterialRegistry;
import mmm.granular.GranularRegistry;
import mmm.units.SignedDyadic;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Integer saturation exchange over a private granular successor and the current liquid generation. */
final class VulkanSolidLiquidReactions implements AutoCloseable {
    /** Header word positions shared with the shader. */
    private enum Header {
        CELL_COUNT(0),
        LIQUID_SLOTS(1),
        SOLID_CAPACITY(2),
        SPECIES_COUNT(3),
        DEFINITIONS(4),
        LIQUID_CALORIC(5),
        SOLID_CALORIC(6),
        PARTICLE_CAPACITY(7),
        DEPOSIT_CAPACITY(8),
        SIZE_X(9),
        SIZE_Y(10),
        SIZE_Z(11),
        LIQUID_SPECIES_CAPACITY(12),
        SOLID_SPECIES_CAPACITY(13),
        LIQUID_LIMB_CAPACITY(14),
        PROPOSAL_CAPACITY(15), PENDING_CAPACITY(180),
        VOLUME_SIGNIFICAND(160),
        VOLUME_EXPONENT(162),
        FACE_GEOMETRY(163),
        CELL_SIZE(164),
        GRAVITY(166),
        FACE_COUNT(168),
        MATERIAL_COUNT(169),
        STOCK_CAPACITY(170),
        PRECIPITATION_ENABLED(171),
        PHASE_ROWS_HEADER(172),
        ACTIVITY_CELLS(173),
        ACTIVITY_FACES(174), GAS_MASS(175), GAS_COMPONENTS(176), AMBIENT_MASS(178);
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

    private static final int LIQUID_FIELDS = 64, GRANULAR_FIELDS = 88, PROGRESS_FIELDS = 120;
    private static final int WORK_FIELDS = 16;
    private static String shaderLayout() {
        return VulkanLiquidThermodynamics.shaderLayout() + headerDefines() + "#define WORK_FIELDS " + WORK_FIELDS + "\n"
            + VulkanShaderLayout.fields("FIELD_", Field.values())
            + "#define LIQUID_FIELDS " + LIQUID_FIELDS + "\n"
            + VulkanShaderLayout.fields("LIQUID_", VulkanLiquidStateLayout.Field.values())
            + "#define GRANULAR_FIELDS " + GRANULAR_FIELDS + "\n"
            + VulkanShaderLayout.fields("GRANULAR_", VulkanGranularStateLayout.Field.values())
            + "#define PROGRESS_FIELDS " + PROGRESS_FIELDS + "\n"
            + VulkanShaderLayout.fields("PROGRESS_", VulkanReactionProgressLayout.Field.values());
    }

    enum Field {
        HEADER(4),
        PHASES(4),
        POROSITIES(4),
        HEAD(4),
        NEXT(4),
        CELL(4),
        LIQUID_SPECIES(8),
        SOLID_SPECIES(8),
        LIQUID_MASS(8),
        LIQUID_ENERGY(8),
        LIQUID_DELTA(8),
        LIQUID_OLD_ENERGY(8),
        SOLID_OLD_ENERGY(8),
        SOLID_NEW_ENERGY(8),
        SOLID_COUNTS(4),
        SOLID_OFFSETS(4),
        SOLID_HANDLES(4),
        SOLID_AMOUNTS(8),
        DELTA_LIMBS(4),
        SCAN(4),
        CONTROL(4),
        CELL_OLD_MASS(8),
        CELL_NEW_MASS(8),
        POTENTIAL_WORK(8),
        PROPOSAL_HEAD(4),
        PROPOSAL_NEXT(4),
        FACE_MOMENTUM(8),
        FACE_HEAT(8),
        WALL_IMPULSE(8),
        FACE_OLD_K(8),
        FACE_NEW_K(8),
        SOLID_OLD_K(8),
        SOLID_NEW_K(8),
        CELL_OLD_PE(8),
        CELL_NEW_PE(8),
        SOLID_SOURCE_MASS(8),
        SOLID_WITHDRAWN_MASS(8),
        MATERIAL_DIAMETER(8),
        PENDING_SLOTS(4);
        final int width;
        Field(int width) {
            this.width = width;
        }
    }
    static final class Layout {
        int activityCellsWord = -1, activityFacesWord = -1;
        VulkanGasStateLayout gas; long ambientMassUnits;
        void configureGas(VulkanGasStateLayout gas, long ambientMassUnits) { this.gas = gas; this.ambientMassUnits = ambientMassUnits; }
        final VulkanLiquidStateLayout liquid;
        final VulkanGranularStateLayout solid;
        final VulkanReactionDefinitions.Layout definitions;
        final VulkanLiquidThermodynamics.Layout liquidCaloric, solidCaloric;
        final VulkanReactionProgressLayout progress;
        final VulkanLiquidPhaseRows.Layout phaseRows;
        final int species, faces, faceGeometryWord;
        final int[] phases, porosities;
        final double[] diameters;
        final double cellSize, gravity;
        final boolean precipitationEnabled;
        final SignedDyadic volumeUnit;
        final EnumMap<Field, VulkanLiquidStateLayout.Column> columns = new EnumMap<>(Field.class);
        final long endOffset;
        Layout(VulkanLiquidPhaseRows.Layout sharedRows, VulkanLiquidStateLayout liquid, VulkanGranularStateLayout solid,
            VulkanReactionDefinitions.Layout definitions, VulkanLiquidThermodynamics.Layout liquidCaloric,
            VulkanLiquidThermodynamics.Layout solidCaloric, VulkanReactionProgressLayout progress,
            FluidMaterialRegistry fluid, ChemicalRegistry chemical, GranularRegistry granular, double cellSize,
            double gravity, int faceGeometryWord, boolean precipitationEnabled, long base, long alignment) {
            if (base < Math.max(progress.endOffset(), Math.max(liquidCaloric.endOffset(), solidCaloric.endOffset()))
                || cellSize <= 0 || !Double.isFinite(cellSize) || alignment < 8 || (alignment & (alignment - 1)) != 0
                || definitions.speciesCount() > 256
                || solid.definitions().speciesIds().size() != chemical.species().size()
                || liquidCaloric.speciesStride() < chemical.species().size()
                || solidCaloric.speciesStride() < chemical.species().size()
                || !solid.definitions().materialIds().equals(definitions.identity().granularMaterials())
                || !Double.isFinite(gravity) || gravity < 0 || faceGeometryWord < -1)
                throw new IllegalArgumentException("Invalid solid/liquid reaction layout");
            this.liquid = liquid;
            this.solid = solid;
            this.definitions = definitions;
            this.liquidCaloric = liquidCaloric;
            this.solidCaloric = solidCaloric;
            this.progress = progress;
            this.cellSize = cellSize;
            this.gravity = gravity;
            this.precipitationEnabled = precipitationEnabled;
            this.faceGeometryWord = faceGeometryWord;
            faces = liquid.column(VulkanLiquidStateLayout.Field.MOMENTUM_X).count()
                + liquid.column(VulkanLiquidStateLayout.Field.MOMENTUM_Y).count()
                + liquid.column(VulkanLiquidStateLayout.Field.MOMENTUM_Z).count();
            species = definitions.speciesCount();
            volumeUnit = SignedDyadic.of(Math.scalb(1.0, -27) / (cellSize * cellSize * cellSize));
            if (volumeUnit.significand().bitLength() > 63)
                throw new IllegalArgumentException("SVQ conversion exceeds exact phase multiplier");
            phases = new int[fluid.size()];
            java.util.Arrays.fill(phases, -1);
            for (int m = 0; m < fluid.size(); m++)
                for (int p = 0; p < chemical.phases().size(); p++)
                    if (fluid.material(m).id().equals(chemical.phases().get(p).materialId()))
                        phases[m] = p;
            porosities = new int[solid.definitions().materialIds().size()];
            diameters = new double[porosities.length];
            for (int m = 0; m < porosities.length; m++) {
                porosities[m] = Float.floatToRawIntBits(
                    (float) granular.material(granular.materialHandle(solid.definitions().materialIds().get(m)))
                        .restPorosityQ()
                    / mmm.granular.GranularLimits.POROSITY_SCALE);
                diameters[m]=granular.material(granular.materialHandle(solid.definitions().materialIds().get(m))).primaryGrainDiameterMeters();
            }
            for (int s = 0; s < species; s++)
                if (!solid.definitions().speciesIds().get(s).equals(chemical.species(s).id()))
                    throw new IllegalArgumentException("Solid/liquid species handles differ");
            phaseRows = new VulkanLiquidPhaseRows.Layout(sharedRows, base, alignment);
            long cursor = phaseRows.endOffset;
            for (var f : Field.values()) {
                var shared = switch (f) {
                    case LIQUID_SPECIES -> VulkanLiquidPhaseRows.Field.SPECIES;
                    case LIQUID_MASS -> VulkanLiquidPhaseRows.Field.MASS;
                    case LIQUID_ENERGY -> VulkanLiquidPhaseRows.Field.ENERGY;
                    case LIQUID_DELTA -> VulkanLiquidPhaseRows.Field.DELTA_SVQ;
                    case LIQUID_OLD_ENERGY -> VulkanLiquidPhaseRows.Field.OLD_ENERGY;
                    default -> null;
                };
                if (shared != null) {
                    columns.put(f, phaseRows.column(shared));
                    continue;
                }
                int count = switch (f) {
                    case HEADER -> 192;
                    case PHASES -> phases.length;
                    case POROSITIES -> porosities.length;
                    case MATERIAL_DIAMETER -> diameters.length;
                    case PENDING_SLOTS -> Math.multiplyExact(Math.multiplyExact(liquid.cellCount(),liquid.materialCount()),definitions.identity().rules().size());
                    case HEAD, CELL_OLD_MASS, CELL_NEW_MASS, POTENTIAL_WORK, PROPOSAL_HEAD, CELL_OLD_PE, CELL_NEW_PE ->
                        liquid.cellCount();
                    case NEXT, CELL, SOLID_OLD_ENERGY, SOLID_NEW_ENERGY, SOLID_COUNTS, SOLID_OLD_K, SOLID_NEW_K,
                        SOLID_SOURCE_MASS, SOLID_WITHDRAWN_MASS ->
                        solid.rowCapacity();
                    case PROPOSAL_NEXT -> progress.solidProposalCapacity();
                    case FACE_MOMENTUM, FACE_OLD_K, FACE_NEW_K -> faces;
                    case FACE_HEAT -> Math.multiplyExact(2, faces);
                    case WALL_IMPULSE -> Math.multiplyExact(3, liquid.cellCount());
                    case LIQUID_SPECIES -> Math.multiplyExact(liquid.slotCount(), species);
                    case SOLID_SPECIES -> Math.multiplyExact(solid.rowCapacity(), species);
                    case SOLID_OFFSETS -> solid.rowCapacity() + 1;
                    case SOLID_HANDLES, SOLID_AMOUNTS -> solid.speciesCapacity();
                    case DELTA_LIMBS -> Math.multiplyExact(liquid.slotCount(), 4);
                    case SCAN ->
                        Math.toIntExact(
                            VulkanPrefixScan.workspaceBytes(Math.max(liquid.slotCount(), solid.rowCapacity())) / 4);
                    case CONTROL -> 12;
                    default -> liquid.slotCount();
                };
                long start = align(cursor, alignment);
                var c = new VulkanLiquidStateLayout.Column(start, count, f.width);
                columns.put(f, c);
                cursor = start + c.bindingBytes();
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
            var out = ByteBuffer.allocateDirect(768).order(ByteOrder.LITTLE_ENDIAN);
            int[] h = new int[192];
            h[Header.CELL_COUNT.word] = liquid.cellCount();
            h[Header.LIQUID_SLOTS.word] = liquid.slotCount();
            h[Header.SOLID_CAPACITY.word] = solid.rowCapacity();
            h[Header.SPECIES_COUNT.word] = species;
            h[Header.DEFINITIONS.word] = definitions.word();
            h[Header.LIQUID_CALORIC.word] = Math.toIntExact(liquidCaloric.header().offset() / 4);
            h[Header.SOLID_CALORIC.word] = Math.toIntExact(solidCaloric.header().offset() / 4);
            h[Header.PARTICLE_CAPACITY.word] = solid.particleCapacity();
            h[Header.DEPOSIT_CAPACITY.word] = solid.depositCapacity();
            h[Header.SIZE_X.word] = liquid.sizeX();
            h[Header.SIZE_Y.word] = liquid.sizeY();
            h[Header.SIZE_Z.word] = liquid.sizeZ();
            h[Header.LIQUID_SPECIES_CAPACITY.word] = liquid.speciesCapacity();
            h[Header.SOLID_SPECIES_CAPACITY.word] = solid.speciesCapacity();
            h[Header.LIQUID_LIMB_CAPACITY.word] = liquid.limbCapacity();
            h[Header.PROPOSAL_CAPACITY.word] = progress.solidProposalCapacity();
            h[Header.PENDING_CAPACITY.word] = progress.pendingCapacity();
            for (var f : Field.values())
                h[WORK_FIELDS + f.ordinal()] = word(f);
            for (var f : VulkanLiquidStateLayout.Field.values())
                h[LIQUID_FIELDS + f.ordinal()] = Math.toIntExact(liquid.column(f).offset() / 4);
            for (var f : VulkanGranularStateLayout.Field.values())
                h[GRANULAR_FIELDS + f.ordinal()] = solid.word(f);
            for (var f : VulkanReactionProgressLayout.Field.values())
                h[PROGRESS_FIELDS + f.ordinal()] = progress.word(f);
            h[Header.PHASE_ROWS_HEADER.word] = phaseRows.headerWord();
            h[Header.PRECIPITATION_ENABLED.word] = precipitationEnabled ? 1 : 0;
            h[Header.MATERIAL_COUNT.word] = liquid.materialCount();
            h[Header.STOCK_CAPACITY.word] = liquid.stockCapacity();
            h[Header.FACE_GEOMETRY.word] = faceGeometryWord;
            h[Header.FACE_COUNT.word] = faces;
            h[Header.ACTIVITY_CELLS.word] = activityCellsWord;
            h[Header.ACTIVITY_FACES.word] = activityFacesWord;
            h[Header.GAS_MASS.word] = gas == null ? -1 : Math.toIntExact(gas.column(VulkanGasStateLayout.Field.MASS_UNITS).offset() / 4);
            h[Header.GAS_COMPONENTS.word] = gas == null ? 0 : gas.definitions().componentIds().size();
            for (int i = 0; i < h.length; i++)
                out.putInt(4 * i, h[i]);
            out.putLong(4 * Header.AMBIENT_MASS.word, ambientMassUnits);
            out.putLong(4 * Header.VOLUME_SIGNIFICAND.word, volumeUnit.significand().longValueExact())
                .putInt(4 * Header.VOLUME_EXPONENT.word, volumeUnit.exponent())
                .putDouble(4 * Header.CELL_SIZE.word, cellSize)
                .putDouble(4 * Header.GRAVITY.word, gravity);
            return out;
        }
        List<VulkanGasThermodynamics.TableChunk> tableChunks() {
            var a = ByteBuffer.allocateDirect(4 * phases.length).order(ByteOrder.LITTLE_ENDIAN);
            var b = ByteBuffer.allocateDirect(4 * porosities.length).order(ByteOrder.LITTLE_ENDIAN);
            var d = ByteBuffer.allocateDirect(8 * diameters.length).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < phases.length; i++)
                a.putInt(4 * i, phases[i]);
            for (int i = 0; i < porosities.length; i++)
                b.putInt(4 * i, porosities[i]);
            for (int i = 0; i < diameters.length; i++) d.putDouble(8*i,diameters[i]);
            return List.of(new VulkanGasThermodynamics.TableChunk(column(Field.PHASES).offset(), a),
                new VulkanGasThermodynamics.TableChunk(column(Field.POROSITIES).offset(), b),
                new VulkanGasThermodynamics.TableChunk(column(Field.MATERIAL_DIAMETER).offset(), d));
        }
        void initialize(ByteBuffer arena) {
            phaseRows.initializeHeader(arena);
            arena.duplicate().position(Math.toIntExact(column(Field.HEADER).offset())).put(headerBytes());
            for (var c : tableChunks())
                arena.duplicate().position(Math.toIntExact(c.offset())).put(c.bytes().duplicate());
        }
        List<VulkanEnergyLedger.Stream> roundoffStreams() {
            var streams = new ArrayList<>(phaseRows.energyChangeStreams());
            streams.addAll(List.of(stream(Field.SOLID_NEW_ENERGY, 1),
                stream(Field.SOLID_OLD_ENERGY, -1), stream(Field.FACE_NEW_K, 1),
                stream(Field.FACE_OLD_K, -1), stream(Field.SOLID_NEW_K, 1), stream(Field.SOLID_OLD_K, -1),
                stream(Field.CELL_NEW_PE, 1), stream(Field.CELL_OLD_PE, -1)));
            return List.copyOf(streams);
        }
        List<VulkanEnergyLedger.Stream> mechanicalWorkStreams() {
            return List.of(stream(Field.FACE_OLD_K, 1), stream(Field.FACE_NEW_K, -1), stream(Field.SOLID_OLD_K, 1),
                stream(Field.SOLID_NEW_K, -1), stream(Field.CELL_OLD_PE, 1), stream(Field.CELL_NEW_PE, -1));
        }
        private VulkanEnergyLedger.Stream stream(Field f, int sign) {
            return new VulkanEnergyLedger.Stream(1, word(f), column(f).count(), 2, sign);
        }
    }
    private final FlipKernelBindings bindings;
    private final VulkanPrefixScan scan;
    private final VulkanLiquidPhaseRows phaseKernel;
    VulkanSolidLiquidReactions(VulkanDeviceContext context) {
        bindings = new FlipKernelBindings(context, 3,
            resource("solid-liquid-reactions.comp")
                .replace("// HOST_LAYOUT", shaderLayout())
                .replace("// DYADIC_LIBRARY", resource("liquid-dyadic.glsl")),
            24);
        try {
            scan = new VulkanPrefixScan(context);
        } catch (RuntimeException | LinkageError e) {
            bindings.close();
            throw e;
        }
        try {
            phaseKernel = new VulkanLiquidPhaseRows(context);
        } catch (RuntimeException | LinkageError e) {
            scan.close();
            bindings.close();
            throw e;
        }
    }
    void rebindRanges(FlipKernelBindings.BufferRange arena, FlipKernelBindings.BufferRange errors) {
        bindings.rebindRanges(List.of(arena, errors, arena));
        scan.rebindRanges(arena, arena, arena, errors);
        phaseKernel.rebindRanges(arena, errors);
    }
    /** The caller first copies granular owners to its transition layout's private source. */
    void recordPrepare(VkCommandBuffer command, Layout l, double duration) {
        int initialCount=Math.max(l.liquid.cellCount(),Math.max(l.liquid.slotCount(),l.solid.rowCapacity()));
        initialCount=Math.max(initialCount,l.progress.pendingCapacity());
        initialCount=Math.max(initialCount,l.column(Field.PENDING_SLOTS).count());
        stage(command,l,0,initialCount);
        stage(command,l,12,l.progress.pendingCapacity());
        phaseKernel.recordImport(command, l.phaseRows);
        stage(command, l, 1, Math.max(l.liquid.slotCount(), l.solid.rowCapacity()));
        stage(command, l, 2, l.liquid.cellCount(),duration);
        stage(command, l, 3, Math.max(l.liquid.slotCount(), l.solid.rowCapacity()));
        scan.record(command, l.solid.rowCapacity(), l.word(Field.SOLID_COUNTS), l.word(Field.SOLID_OFFSETS),
            l.word(Field.SCAN));

        stage(command, l, 4, 1);
        stage(command, l, 5, l.solid.rowCapacity());
        stage(command, l, 8, l.faces);
        stage(command, l, 9, l.liquid.cellCount());
        stage(command, l, 10, l.solid.rowCapacity());
        phaseKernel.recordAssemble(command, l.phaseRows);
        stage(command, l, 11, 1);
    }
    /** Publishes liquid columns and the private solid composition, then the caller reassembles solid owners. */
    void recordPublish(VkCommandBuffer command, Layout l) {
        phaseKernel.recordPublish(command, l.phaseRows);
        stage(command, l, 7, Math.max(l.faces, Math.max(l.solid.rowCapacity() + 1, l.solid.speciesCapacity())));
    }
    private void stage(VkCommandBuffer command, Layout l, int stage, int n) {
        stage(command,l,stage,n,0);
    }
    private void stage(VkCommandBuffer command, Layout l, int stage, int n,double duration) {
        if (n == 0)
            return;
        try (var stack = stackPush()) {
            bindings.bind(command, stack);
            vkCmdPushConstants(command, bindings.pipelineLayout(), VK_SHADER_STAGE_COMPUTE_BIT, 0,
                stack.malloc(24).putInt(l.word(Field.HEADER)).putInt(stage).putInt(n).putInt(0).putDouble(duration).flip());
            int g = (n + 127) / 128;
            vkCmdDispatch(command, Math.min(g, 65535), (g + 65534) / 65535, 1);
            VulkanRegionStorage.barrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, stack);
        }
    }
    private static long align(long x, long a) {
        return Math.addExact(x, a - 1) & -a;
    }
    private static String resource(String n) {
        try (var in = VulkanSolidLiquidReactions.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/" + n)) {
            if (in == null)
                throw new IllegalStateException("Missing reaction shader " + n);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
    @Override
    public void close() {
        try {
            phaseKernel.close();
        } finally {
            scan.close();
            bindings.close();
        }
    }
}
