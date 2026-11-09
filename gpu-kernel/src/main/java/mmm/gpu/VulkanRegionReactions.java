package mmm.gpu;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

import java.util.ArrayList;
import java.util.List;
import mmm.chemistry.ChemicalRegistry;
import mmm.fluid.FluidMaterialRegistry;
import mmm.granular.GranularRegistry;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Chemistry and solid/liquid successors remain private until their complete inventory fits. */
final class VulkanRegionReactions implements AutoCloseable {
    record Configuration(VulkanReactionDefinitions.Tables definitions, FluidMaterialRegistry fluids,
        ChemicalRegistry chemistry, VulkanReactionProgressLayout.State initial, double gravity,
        boolean precipitationEnabled, mmm.thermal.ThermalRegistry thermal, mmm.fluid.air.GasRegistry gas) {
        Configuration(
            VulkanReactionDefinitions.Tables definitions, FluidMaterialRegistry fluids, ChemicalRegistry chemistry) {
            this(definitions, fluids, chemistry, VulkanReactionProgressLayout.State.empty(), 0, true, null, null);
        }
        Configuration(VulkanReactionDefinitions.Tables definitions, FluidMaterialRegistry fluids,
            ChemicalRegistry chemistry, VulkanReactionProgressLayout.State initial) {
            this(definitions, fluids, chemistry, initial, 0, true, null, null);
        }
        Configuration(VulkanReactionDefinitions.Tables definitions, FluidMaterialRegistry fluids,
            ChemicalRegistry chemistry, VulkanReactionProgressLayout.State initial, double gravity,
            boolean precipitationEnabled) {
            this(definitions, fluids, chemistry, initial, gravity, precipitationEnabled, null, null);
        }
        Configuration {
            if (!Double.isFinite(gravity) || gravity < 0)
                throw new IllegalArgumentException("Invalid reaction gravity");
        }
        boolean enabled() {
            return !definitions.identity().rules().isEmpty() || !initial.pending().isEmpty()
                || initial.phaseMotionCells().length > 0
                || thermal != null && !thermal.surfaceMaterials().isEmpty();
        }
    }
    /** A separate transition workspace preserves the earlier mechanical transition ledger. */
    record SolidInputs(VulkanGranularTransitions.Layout transitions, VulkanLiquidThermodynamics.Layout solidCaloric,
        GranularRegistry registry, double cellSize, int faceGeometryWord) {}
    static final class CapacityGrowth extends IllegalStateException {
        final int species, limbs, particles, deposits, solidSpecies, coherentGroups, coherentSpecies;
        CapacityGrowth(int species) {
            this(species, 0, 0, 0, 0);
        }
        CapacityGrowth(int species, int limbs, int particles, int deposits, int solidSpecies) {
            this(species, limbs, particles, deposits, solidSpecies, 0, 0);
        }
        CapacityGrowth(int species, int limbs, int particles, int deposits, int solidSpecies, int coherentGroups,
            int coherentSpecies) {
            this.species = species;
            this.limbs = limbs;
            this.particles = particles;
            this.deposits = deposits;
            this.solidSpecies = solidSpecies;
            this.coherentGroups = coherentGroups;
            this.coherentSpecies = coherentSpecies;
        }
    }
    record Layout(VulkanReactionDefinitions.Layout definitions, VulkanReactionProgressLayout progress,
        VulkanLiquidReactions.Layout liquid, SolidInputs solidInputs, VulkanSolidLiquidReactions.Layout solid,
        VulkanCondensedPhaseChange.Layout condensedPhase,
        VulkanGasLiquidMassTransfer.Layout massTransfer, VulkanLiquidDiffusion.Layout diffusion, long endOffset) {
        List<VulkanGasThermodynamics.TableChunk> headers() {
            var result = new ArrayList<VulkanGasThermodynamics.TableChunk>();
            result.add(new VulkanGasThermodynamics.TableChunk(
                progress.column(VulkanReactionProgressLayout.Field.HEADER).offset(), progress.headerBytes()));
            result.add(new VulkanGasThermodynamics.TableChunk(liquid.header().offset(), liquid.headerBytes()));
            if (solid != null) {
                result.add(new VulkanGasThermodynamics.TableChunk(
                    solidInputs.transitions().header.offset(), solidInputs.transitions().headerBytes()));
                result.add(new VulkanGasThermodynamics.TableChunk(
                    solid.column(VulkanSolidLiquidReactions.Field.HEADER).offset(), solid.headerBytes()));
                result.add(new VulkanGasThermodynamics.TableChunk(
                    solid.phaseRows.column(VulkanLiquidPhaseRows.Field.HEADER).offset(),
                    solid.phaseRows.headerBytes()));
            }
            if (condensedPhase != null)
                result.addAll(condensedPhase.headers());
            if (massTransfer != null)
                result.addAll(massTransfer.headers());
            if (diffusion != null) result.addAll(diffusion.headers());
            return List.copyOf(result);
        }
        List<VulkanGasThermodynamics.TableChunk> tables() {
            var result = new ArrayList<VulkanGasThermodynamics.TableChunk>();
            result.add(new VulkanGasThermodynamics.TableChunk(definitions.data().offset(), definitions.bytes()));
            result.addAll(liquid.tableChunks());
            if (solid != null) {
                result.add(new VulkanGasThermodynamics.TableChunk(
                    solidInputs.transitions().column(VulkanGranularTransitions.Field.MATERIALS).offset(),
                    solidInputs.transitions().materialBytes(solidInputs.registry())));
                result.addAll(solid.tableChunks());
            }
            if (condensedPhase != null)
                result.addAll(condensedPhase.tableChunks());
            if (massTransfer != null)
                result.addAll(massTransfer.tables());
            if (diffusion != null) result.addAll(diffusion.tables());
            return List.copyOf(result);
        }
        List<VulkanEnergyLedger.Stream> roundoffStreams() {
            var result = new ArrayList<>(liquid.roundoffStreams());
            if (solid != null) {
                result.addAll(solid.roundoffStreams());
                result.addAll(solidInputs.transitions().roundoffStreams());
            }
            if (condensedPhase != null)
                result.addAll(condensedPhase.roundoffStreams());
            if (massTransfer != null)
                result.addAll(massTransfer.roundoffStreams());
            if (diffusion != null) result.addAll(diffusion.roundoffStreams());
            return List.copyOf(result);
        }
        List<VulkanEnergyLedger.Stream> mechanicalWorkStreams() {
            var result = new ArrayList<VulkanEnergyLedger.Stream>();
            if (solid != null)
                result.addAll(solid.mechanicalWorkStreams());
            if (condensedPhase != null)
                result.addAll(condensedPhase.mechanicalWorkStreams());
            if (massTransfer != null)
                result.addAll(massTransfer.mechanicalWorkStreams());
            if (diffusion != null) result.addAll(diffusion.mechanicalWorkStreams());
            return List.copyOf(result);
        }
        List<VulkanLiquidStateLayout.Column> postingColumns() {
            var ranges = new ArrayList<long[]>();
            var streams = new ArrayList<>(roundoffStreams());
            streams.addAll(mechanicalWorkStreams());
            for (var stream : streams)
                if (stream.count() > 0) {
                    if (stream.bank() != 1)
                        throw new IllegalStateException("Reaction posting refers to source bank");
                    long start = 4L * stream.wordOffset(),
                         end = start + 4L * ((long) (stream.count() - 1) * stream.strideWords() + 2);
                    ranges.add(new long[] {start, end});
                }
            ranges.sort(java.util.Comparator.comparingLong(a -> a[0]));
            var result = new ArrayList<VulkanLiquidStateLayout.Column>();
            long start = -1, end = 0;
            for (var range : ranges) {
                if (start < 0) {
                    start = range[0];
                    end = range[1];
                } else if (range[0] <= end)
                    end = Math.max(end, range[1]);
                else {
                    result.add(new VulkanLiquidStateLayout.Column(start, Math.toIntExact((end - start) / 4), 4));
                    start = range[0];
                    end = range[1];
                }
            }
            if (start >= 0)
                result.add(new VulkanLiquidStateLayout.Column(start, Math.toIntExact((end - start) / 4), 4));
            return List.copyOf(result);
        }
        List<VulkanLiquidStateLayout.Column> stateColumns() {
            var result = new ArrayList<VulkanLiquidStateLayout.Column>();
            for (var f : VulkanReactionProgressLayout.Field.values())
                if (f.name().startsWith("PENDING_") || f == VulkanReactionProgressLayout.Field.RESIDUAL_MOLES
                    || f == VulkanReactionProgressLayout.Field.CURSOR
                    || f == VulkanReactionProgressLayout.Field.REQUEST_GENERATION
                    || f == VulkanReactionProgressLayout.Field.DIRTY_CELLS
                    || f == VulkanReactionProgressLayout.Field.PHASE_MOTION_CELLS
                    || f == VulkanReactionProgressLayout.Field.PHASE_PENDING_SUMMARY)
                    result.add(progress.column(f));
            return result;
        }
        List<VulkanLiquidStateLayout.Column> persistentColumns() {
            var result = new ArrayList<VulkanLiquidStateLayout.Column>();
            for (var chunk : headers())
                result.add(new VulkanLiquidStateLayout.Column(chunk.offset(), chunk.bytes().remaining() / 4, 4));
            for (var chunk : tables())
                result.add(new VulkanLiquidStateLayout.Column(chunk.offset(), chunk.bytes().remaining() / 4, 4));
            result.addAll(stateColumns());
            return result;
        }
    }
    static Layout layout(Configuration configuration, VulkanLiquidStateLayout liquid,
        VulkanLiquidThermodynamics.Layout caloric, long base, long alignment) {
        return layout(configuration, liquid, caloric, null, base, alignment);
    }
    static Layout layout(Configuration configuration, VulkanLiquidStateLayout liquid,
        VulkanLiquidThermodynamics.Layout caloric, SolidInputs solid, long base, long alignment) {
        return layout(configuration, liquid, caloric, solid, null, base, alignment);
    }
    static Layout layout(Configuration configuration, VulkanLiquidStateLayout liquid,
        VulkanLiquidThermodynamics.Layout caloric, SolidInputs solid, VulkanGasLiquidMassTransfer.Inputs gas, long base,
        long alignment) {
        return layout(configuration, liquid, caloric, solid, gas, null, base, alignment);
    }
    static Layout layout(Configuration configuration, VulkanLiquidStateLayout liquid,
        VulkanLiquidThermodynamics.Layout caloric, SolidInputs solid, VulkanGasLiquidMassTransfer.Inputs gas,
        VulkanCondensedPhaseChange.Inputs condensed, long base, long alignment) {
        return layout(configuration, liquid, caloric, solid, gas, condensed, base, alignment, -1, -1);
    }
    static Layout layout(Configuration configuration, VulkanLiquidStateLayout liquid,
        VulkanLiquidThermodynamics.Layout caloric, SolidInputs solid, VulkanGasLiquidMassTransfer.Inputs gas,
        VulkanCondensedPhaseChange.Inputs condensed, long base, long alignment, int activityCellsWord,
        int activityFacesWord) {
        return layout(configuration,liquid,caloric,solid,gas,condensed,base,alignment,activityCellsWord,activityFacesWord,-1,
                gas!=null?gas.geometry().cellSize():solid!=null?solid.cellSize():1.0);
    }
    static Layout layout(Configuration configuration, VulkanLiquidStateLayout liquid,
        VulkanLiquidThermodynamics.Layout caloric, SolidInputs solid, VulkanGasLiquidMassTransfer.Inputs gas,
        VulkanCondensedPhaseChange.Inputs condensed, long base, long alignment, int activityCellsWord,
        int activityFacesWord, int faceGeometryWord, double cellSize) {
        return layout(configuration,liquid,caloric,solid,gas,condensed,base,alignment,activityCellsWord,activityFacesWord,faceGeometryWord,cellSize,null);
    }
    static Layout layout(Configuration configuration, VulkanLiquidStateLayout liquid,
        VulkanLiquidThermodynamics.Layout caloric, SolidInputs solid, VulkanGasLiquidMassTransfer.Inputs gas,
        VulkanCondensedPhaseChange.Inputs condensed, long base, long alignment, int activityCellsWord,
        int activityFacesWord, int faceGeometryWord, double cellSize,VulkanLiquidReactions.SurfaceInputs surfaces) {
        var definitions = configuration.definitions().layout(base, alignment);
        int rules = 0;
        int[] words = configuration.definitions().words();
        if (solid != null)
            for (int i = 0; i < words[4]; i++)
                if (words[words[5] + 2 * i] == VulkanReactionDefinitions.Kind.SOLID_LIQUID.ordinal())
                    rules++;
        int kineticRules=Math.toIntExact(configuration.chemistry().reactions().stream()
                .filter(rule->rule.kind()==mmm.chemistry.ChemicalReaction.Kind.FINITE_RATE).count());
        int diffusionRules=VulkanLiquidDiffusion.supportedRules(configuration).size();
        int vaporRules=configuration.thermal()==null?0:Math.toIntExact(configuration.thermal().transitions().stream()
                .filter(rule->rule.interfaceRequirement()==mmm.thermal.ThermalPhaseTransition.InterfaceRequirement.LIQUID_GAS).count());
        int pendingRules = Math.addExact(Math.addExact(configuration.chemistry().gasLiquidMassTransfers().size(),vaporRules),
                Math.addExact(diffusionRules,Math.addExact(kineticRules,rules)));
        int pendingCapacity=Math.multiplyExact(liquid.cellCount(),pendingRules);
        int solidProposals=solid==null?0:Math.multiplyExact(Math.multiplyExact(solid.transitions().source.rowCapacity(),rules),8);
        int pending = Math.max(1, Math.max(configuration.initial().pending().size(), pendingCapacity));
        var progress = new VulkanReactionProgressLayout(
            liquid.cellCount(), pending, solidProposals, definitions, definitions.endOffset(), alignment);
        boolean hasSurface=gas!=null && gas.device()!=null && gas.device().definitions().surfaceSpeciesCount()>0;
        boolean hasRows = rules > 0 || condensed != null || gas != null && configuration.thermal() != null
                && (!configuration.chemistry().gasLiquidMassTransfers().isEmpty() || vaporRules > 0 || hasSurface)
                || faceGeometryWord >= 0 && diffusionRules > 0;
        var sharedRows = hasRows ? new VulkanLiquidPhaseRows.Layout(liquid, definitions, cellSize, progress.endOffset(), alignment) : null;
        var reaction = VulkanLiquidReactions.layout(liquid, definitions, caloric, progress, configuration.fluids(),
            configuration.chemistry(), hasRows ? sharedRows.endOffset : progress.endOffset(), alignment, activityCellsWord,surfaces);
        var exchange = rules == 0
            ? null
            : new VulkanSolidLiquidReactions.Layout(sharedRows, liquid, solid.transitions().source, definitions, caloric,
                  solid.solidCaloric(), progress, configuration.fluids(), configuration.chemistry(), solid.registry(),
                  solid.cellSize(), configuration.gravity(), solid.faceGeometryWord(),
                  configuration.precipitationEnabled(), reaction.endOffset(), alignment);
        long end = exchange == null ? reaction.endOffset() : exchange.endOffset;
        VulkanCondensedPhaseChange.Layout condensedPhase = null;
        if (condensed != null) {
            var input = new VulkanCondensedPhaseChange.Inputs(liquid, condensed.coherent(), caloric,
                condensed.coherentCaloric(), definitions, progress, condensed.pressureWord(), condensed.pressureStride(),
                condensed.fixedMasksWord(), condensed.occupiedAllSvqWord(), condensed.cellSize(), condensed.gravity());
            condensedPhase = new VulkanCondensedPhaseChange.Layout(
                sharedRows, input, configuration.fluids(), configuration.chemistry(), configuration.thermal(), end, alignment);
            end = condensedPhase.endOffset();
        }
        VulkanGasLiquidMassTransfer.Layout massTransfer = null;
        if (gas != null && configuration.thermal() != null
            && (!configuration.chemistry().gasLiquidMassTransfers().isEmpty() || vaporRules > 0 || hasSurface)) {
            var rows = new VulkanLiquidPhaseRows.Layout(sharedRows, end, alignment);
            var candidateTransfer =
                new VulkanGasLiquidMassTransfer.Layout(configuration, rows, gas, progress, rows.endOffset, alignment);
            if(candidateTransfer.rules > 0 || candidateTransfer.surfaceRules>0) { massTransfer=candidateTransfer; end=massTransfer.endOffset; }
        }
        VulkanLiquidDiffusion.Layout diffusion = null;
        if (faceGeometryWord >= 0 && diffusionRules > 0) {
            var rows = new VulkanLiquidPhaseRows.Layout(sharedRows, end, alignment);
            diffusion = new VulkanLiquidDiffusion.Layout(configuration,rows,caloric,progress,faceGeometryWord,
                    cellSize,activityCellsWord,activityFacesWord,rows.endOffset,alignment);
            end = diffusion.endOffset;
        }
        if (exchange != null) {
            exchange.configureGas(gas == null ? null : gas.gas().gas(), gas == null ? 0 : gas.ambientMassUnits());
            exchange.activityCellsWord = activityCellsWord;
            exchange.activityFacesWord = activityFacesWord;
        }
        if (condensedPhase != null) {
            condensedPhase.configureGas(gas == null ? null : gas.gas().gas(), gas == null ? 0 : gas.ambientMassUnits(), faceGeometryWord);
            condensedPhase.activityCellsWord = activityCellsWord;
            condensedPhase.activityFacesWord = activityFacesWord;
        }
        if (massTransfer != null) {
            massTransfer.activityCellsWord = activityCellsWord;
            massTransfer.activityFacesWord = activityFacesWord;
        }
        return new Layout(
            definitions, progress, reaction, solid, exchange, condensedPhase, massTransfer, diffusion, end);
    }
    private final VulkanLiquidReactions kernel;
    private final VulkanLiquidDiffusion diffusionKernel;
    private final VulkanLiquidThermodynamics caloric;
    private final VulkanGasLiquidMassTransfer massTransferKernel;
    private final VulkanCondensedPhaseChange condensedKernel;
    private final VulkanSolidLiquidReactions exchange;
    private final VulkanGranularTransitions transitions;
    VulkanRegionReactions(VulkanDeviceContext context, ChemicalRegistry chemistry) {
        var allocated = new ArrayList<AutoCloseable>();
        try {
            kernel = own(allocated, new VulkanLiquidReactions(context, chemistry));
            diffusionKernel = own(allocated, new VulkanLiquidDiffusion(context));
            caloric = own(allocated, new VulkanLiquidThermodynamics(context));
            exchange = own(allocated, new VulkanSolidLiquidReactions(context));
            transitions = own(allocated, new VulkanGranularTransitions(context));
            condensedKernel = own(allocated, new VulkanCondensedPhaseChange(context));
            massTransferKernel = own(allocated, new VulkanGasLiquidMassTransfer(context));
        } catch (RuntimeException | Error failure) {
            for (var resource : allocated.reversed())
                try {
                    resource.close();
                } catch (Exception | LinkageError closing) {
                    failure.addSuppressed(closing);
                }
            throw failure;
        }
    }
    private static <T extends AutoCloseable> T own(List<AutoCloseable> resources, T value) {
        resources.add(value);
        return value;
    }
    void recordClearPostings(VkCommandBuffer command, Layout layout, GpuBuffer arena) {
        try (var stack = stackPush()) {
            VulkanRegionStorage.barrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_ACCESS_TRANSFER_WRITE_BIT, stack);
            var clear = new ArrayList<>(layout.postingColumns());
            if (layout.condensedPhase() != null)
                clear.add(layout.condensedPhase().column(VulkanCondensedPhaseChange.Field.CONTROL));
            if (layout.massTransfer() != null)
                clear.add(layout.massTransfer().column(VulkanGasLiquidMassTransfer.Field.CONTROL));
            for (var column : clear)
                vkCmdFillBuffer(command, arena.handle(), column.offset(), column.bytes(), 0);
            VulkanRegionStorage.barrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT, stack);
        }
    }
    int execute(VulkanRegionStorage.Candidate candidate, Layout layout) { return execute(candidate,layout,0.05,1.0); }
    private static long stageStart() { return Boolean.getBoolean("mmm.gpuMechanicalStages") ? System.nanoTime() : 0; }
    private static long stageTime(String stage, long started) {
        if (started == 0) return 0;
        long now=System.nanoTime();
        System.out.printf("reaction_stage=%s wall_ms=%.3f%n",stage,(now-started)*1e-6);
        return now;
    }
    void recordLiquidActivity(VkCommandBuffer command,Layout layout,GpuBuffer output,GpuBuffer errors) {
        kernel.rebindRanges(new FlipKernelBindings.BufferRange(output),new FlipKernelBindings.BufferRange(errors));
        int solidCounts=layout.solid()==null?-1:Math.toIntExact(layout.solidInputs().transitions().source.column(VulkanGranularStateLayout.Field.COUNTS).offset()/4);
        kernel.recordActivity(command,layout.liquid(),false,solidCounts);
    }
    int execute(VulkanRegionStorage.Candidate candidate, Layout layout, double duration, double cellSize) {
        return execute(candidate,layout,duration,cellSize,true,true);
    }
    private int execute(VulkanRegionStorage.Candidate candidate, Layout layout, double duration, double cellSize,
                        boolean chemistryActive,boolean sourceSolidActive) {
        long stage=stageStart();
        if(!chemistryActive) {
            if(layout.solid()==null || !sourceSolidActive)return 0;
            var solidStatus=candidate.continueBatch((command,input,output,errors,staging,stack)->{
                var arena=new FlipKernelBindings.BufferRange(output);var err=new FlipKernelBindings.BufferRange(errors);
                caloric.rebindRanges(arena,err);transitions.rebindRanges(arena,err);exchange.rebindRanges(arena,err);
                transitions.recordCopySource(command,layout.solidInputs().transitions(),output);
                exchange.recordPrepare(command,layout.solid(),duration);
            },layout.solid().feedbackOffset(),48);
            return finishSolid(candidate,layout,solidStatus,stage);
        }
        var status = candidate.continueBatch((command, input, output, errors, staging, stack) -> {
            var arena = new FlipKernelBindings.BufferRange(output);
            var err = new FlipKernelBindings.BufferRange(errors);
            kernel.rebindRanges(arena, err);
            caloric.rebindRanges(arena, err);
            kernel.recordPrepare(command, layout.liquid(), duration, cellSize);
            if(layout.solid()!=null)
                kernel.recordActivity(command,layout.liquid(),true,Math.toIntExact(layout.solidInputs().transitions().source.column(VulkanGranularStateLayout.Field.COUNTS).offset()/4));
        }, layout.liquid().feedbackOffset(), layout.solid()==null?12:32);
        stage=stageTime("liquid-prepare",stage);
        if (status.error() != 0)
            return status.error();
        if (status.feedback().getInt(0) == VulkanLiquidReactions.NEED_CAPACITY)
            throw new CapacityGrowth(status.feedback().getInt(8));
        if (status.feedback().getInt(0) != VulkanLiquidReactions.READY)
            throw new IllegalStateException("Liquid reactions did not prepare a complete successor");
        if (layout.solid() == null || status.feedback().getInt(28)==0)
            return candidate
                .continueBatch(
                    (command, input, output, errors, staging, stack)
                        -> {
                        kernel.recordPublish(command, layout.liquid());
                        caloric.record(command, layout.liquid().caloric());
                    },
                    0, 0)
                .error();
        status = candidate.continueBatch((command, input, output, errors, staging, stack) -> {
            kernel.recordPublish(command, layout.liquid());
            caloric.record(command, layout.liquid().caloric());
            var arena = new FlipKernelBindings.BufferRange(output);
            var err = new FlipKernelBindings.BufferRange(errors);
            transitions.rebindRanges(arena, err);
            exchange.rebindRanges(arena, err);
            transitions.recordCopySource(command, layout.solidInputs().transitions(), output);
            exchange.recordPrepare(command, layout.solid(), duration);
        }, layout.solid().feedbackOffset(), 48);
        return finishSolid(candidate,layout,status,stage);
    }
    private int finishSolid(VulkanRegionStorage.Candidate candidate,Layout layout,
                            VulkanRegionStorage.Candidate.BatchResult status,long stage) {
        stage=stageTime("solid-prepare",stage);
        if (status.error() != 0)
            return status.error();
        var feedback = status.feedback();
        if (feedback.getInt(0) == VulkanLiquidReactions.NEED_CAPACITY)
            throw new CapacityGrowth(
                feedback.getInt(8), feedback.getInt(32), 0, feedback.getInt(4), feedback.getInt(12));
        if (feedback.getInt(0) != VulkanLiquidReactions.READY)
            throw new IllegalStateException("Solid/liquid reactions did not prepare a complete successor");
        status = candidate.continueBatch((command, input, output, errors, staging, stack) -> {
            exchange.recordPublish(command, layout.solid());
            transitions.recordReassemble(command, layout.solidInputs().transitions(), layout.solidInputs().cellSize());
            caloric.record(command, layout.liquid().caloric());
            caloric.record(command, layout.solidInputs().solidCaloric());
        }, layout.solidInputs().transitions().column(VulkanGranularTransitions.Field.CONTROL).offset(), 12);
        stageTime("solid-publish",stage);
        if (status.error() == 64)
            throw new CapacityGrowth(
                0, 0, status.feedback().getInt(0), status.feedback().getInt(4), status.feedback().getInt(8));
        return status.error();
    }
    int execute(VulkanRegionStorage.Candidate candidate, Layout layout, boolean phaseEnabled, int maximum,
        VulkanGasThermodynamics.Layout eos, VulkanGasGeometry.Layout geometry, double duration) {
        return execute(candidate,layout,phaseEnabled,maximum,eos,geometry,duration,7);
    }
    int execute(VulkanRegionStorage.Candidate candidate, Layout layout, boolean phaseEnabled, int maximum,
        VulkanGasThermodynamics.Layout eos, VulkanGasGeometry.Layout geometry, double duration, int activity) {
        int error = 0;
        int remaining = maximum;
        long stage=stageStart();
        if (layout.diffusion() != null && (activity&1)!=0) {
            error = diffusionKernel.execute(candidate,layout.diffusion(),duration);
            stage=stageTime("diffusion",stage);
            if(error!=0)return error;
            var refreshed=candidate.continueBatch((command,input,output,errors,staging,stack)->{
                caloric.rebindRanges(new FlipKernelBindings.BufferRange(output),new FlipKernelBindings.BufferRange(errors));
                caloric.record(command,layout.liquid().caloric());
                recordLiquidActivity(command,layout,output,errors);
            },layout.progress().column(VulkanReactionProgressLayout.Field.CONTROL).offset()+28,4);
            error=refreshed.error();
            stage=stageTime("diffusion-caloric",stage);
            if(error!=0)return error;
            activity=refreshed.feedback().getInt(0);
        }
        error = execute(candidate, layout, duration, geometry != null ? geometry.cellSize() : layout.solidInputs() != null ? layout.solidInputs().cellSize() : 1.0,
                (activity&2)!=0,(activity&4)!=0);
        stage=stageTime("liquid-solid-total",stage);
        if (error != 0)
            return error;
        boolean condensedActive=false;
        if (layout.massTransfer() != null) {
            error = massTransferKernel.execute(candidate, layout.massTransfer(), duration, phaseEnabled, maximum, eos, geometry);
            stage=stageTime("gas-liquid",stage);
            if (error != 0)
                return error;
            remaining -= massTransferKernel.completedPhaseBatches();
            boolean inspectCondensed=phaseEnabled && remaining>0 && layout.condensedPhase()!=null && layout.condensedPhase().enabled();
            if (inspectCondensed) {
            var refreshed = candidate
                        .continueBatch(
                            (command, input, output, errors, staging, stack)
                                -> {
                                caloric.rebindRanges(new FlipKernelBindings.BufferRange(output),new FlipKernelBindings.BufferRange(errors));
                                caloric.record(command, layout.liquid().caloric());
                                condensedKernel.recordActivity(command,layout.condensedPhase(),output,errors);
                            },
                            layout.condensedPhase().feedbackOffset(), 4);
            error=refreshed.error();
            condensedActive=refreshed.feedback().getInt(0)!=0;
            stage=stageTime("gas-liquid-caloric",stage);
            if (error != 0)
                return error;
            }
        }
        if (!phaseEnabled || maximum == 0)
            return 0;
        if(layout.massTransfer()==null && remaining>0 && layout.condensedPhase()!=null && layout.condensedPhase().enabled()) {
            var condensedActivity=candidate.continueBatch((command,input,output,errors,staging,stack)->
                    condensedKernel.recordActivity(command,layout.condensedPhase(),output,errors),layout.condensedPhase().feedbackOffset(),4);
            if(condensedActivity.error()!=0)return condensedActivity.error();
            condensedActive=condensedActivity.feedback().getInt(0)!=0;
        }
        if (condensedActive) {
            error = candidate
                        .continueBatch(
                            (command, input, output, errors, staging, stack)
                                -> {
                                caloric.rebindRanges(new FlipKernelBindings.BufferRange(output),new FlipKernelBindings.BufferRange(errors));
                                caloric.record(command, layout.condensedPhase().input.coherentCaloric());
                            },
                            0, 0)
                        .error();
            stage=stageTime("condensed-caloric",stage);
            if (error != 0)
                return error;
            try {
                error = condensedKernel.execute(candidate, layout.condensedPhase(), remaining);
            } catch (VulkanCondensedPhaseChange.CapacityGrowth growth) {
                throw new CapacityGrowth(
                    growth.liquidSpecies, growth.liquidLimbs, 0, 0, 0, growth.coherentGroups, growth.coherentSpecies);
            }
            stage=stageTime("condensed-phase",stage);
            if (error != 0)
                return error;
        }
        return 0;
    }
    void recordFinalCaloric(VkCommandBuffer command, Layout layout, GpuBuffer output, GpuBuffer errors) {
        boolean condensed = layout.condensedPhase() != null && layout.condensedPhase().enabled();
        if (layout.massTransfer() == null && !condensed) return;
        caloric.rebindRanges(new FlipKernelBindings.BufferRange(output), new FlipKernelBindings.BufferRange(errors));
        caloric.record(command, layout.liquid().caloric());
        if (condensed) caloric.record(command, layout.condensedPhase().input.coherentCaloric());
    }
    @Override
    public void close() {
        diffusionKernel.close();
        massTransferKernel.close();
        condensedKernel.close();
        try {
            transitions.close();
        } finally {
            try {
                exchange.close();
            } finally {
                try {
                    caloric.close();
                } finally {
                    kernel.close();
                }
            }
        }
    }
}
