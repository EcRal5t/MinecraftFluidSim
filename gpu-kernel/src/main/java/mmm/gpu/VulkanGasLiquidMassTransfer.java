package mmm.gpu;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import org.lwjgl.vulkan.VkCommandBuffer;

/** Shared local gas/liquid exchange with actual batch mass, energy and common face momentum. */
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
        ACTIVITY_FACES(31),
        PHASE_ENABLED(32),
        MAXIMUM_PHASE_BATCHES(33), AMBIENT_MASS_UNITS(34), WALL_COUNTS(36), WALL_PORT_DEVICE(37), WALL_PORT_CELL(38), WALL_PORT_DIRECTION(39), WALL_TEMPERATURE(40), WALL_CALORIC(41), WALL_MATERIAL(42), WALL_MASS(43), WALL_ENERGY(44), WALL_GENERATION(45), WALL_REQUEST_GENERATION(46), WALL_ENERGY_CHANGE(47), WALL_PORT_CAPACITY(48), WALL_CAPACITY(49),
        SURFACE_RULE_COUNT(50),SURFACE_AMOUNTS(51),SURFACE_RESIDUALS(52),SURFACE_INTERCEPT(53),SURFACE_CAPACITY(54),SCREEN_FACES(55);
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

    private static final int WORK_FIELDS = 64;
    private static String shaderLayout() {
        return VulkanGasGeometry.shaderLayout() + VulkanGasThermodynamics.shaderLayout() + VulkanLiquidThermodynamics.shaderLayout() + headerDefines() + "#define WORK_FIELDS " + WORK_FIELDS + "\n"
            + "#define MIXTURE_STRIDE " + VulkanMixtureState.Quantity.values().length + "\n"
            + "#define LIQUID_BULK " + mmm.thermal.MixtureVolumeModel.LIQUID_BULK_PA + "LF\n"
            + "#define LIQUID_REFERENCE_PRESSURE " + mmm.thermal.MixtureVolumeModel.REFERENCE_PRESSURE_PA + "LF\n"
            + VulkanShaderLayout.fields("MIXTURE_", VulkanMixtureState.Quantity.values())
            + VulkanShaderLayout.fields("FIELD_", Field.values());
    }

    enum Field {
        HEADER(4),
        RULES(4), SURFACE_RULES(4),
        CONTROL(4),
        HEAD(4),
        NEXT(4),
        GAS_TO_LIQUID_MASS(8),
        LIQUID_TO_GAS_MASS(8),
        KG(8),
        TEMP(8),
        GAS_TEMP(8),
        OLD_MASS(8),
        NEW_MASS(8),
        OLD_GAS_U(8),
        NEW_GAS_U(8),
        GAS_TRANSFER(8),
        GAS_TRANSFER_TARGET(4),
        OLD_K(8),
        NEW_K(8),
        FACE_HEAT(8),
        FACE_P(8),
        PE_WORK(8),
        PENDING_TIME(8),
        PENDING_RESIDUAL(8),
        PENDING_SLOT(4),
        ORPHAN_ENERGY(8),
        ORPHAN_RECEIVER(4), WALL_RESERVATION(8), WALL_DELTA(8), WALL_PENDING(4), SURFACE_TEMPERATURE(8), SURFACE_CHANGED(4), SURFACE_REMOVED_MASS(8), SURFACE_RECEIVER(4),
        LIQUID_QUANTA(8), LIQUID_REFERENCE(8), GAS_NANOS(8), LIQUID_REFERENCE_NEXT(8);
        final int width;
        Field(int width) {
            this.width = width;
        }
    }
    record Inputs(VulkanGasThermodynamics.Layout gas, VulkanGasGeometry.Layout geometry,
        VulkanLiquidThermodynamics.Layout liquid, double gravity, long ambientMassUnits,
        VulkanThermalDeviceLayout device,
        VulkanLiquidThermodynamics.Layout deviceCaloric,int screenFacesWord) {
        Inputs(VulkanGasThermodynamics.Layout gas,VulkanGasGeometry.Layout geometry,VulkanLiquidThermodynamics.Layout liquid,double gravity,long ambientMassUnits,
               VulkanThermalDeviceLayout device,VulkanLiquidThermodynamics.Layout deviceCaloric){this(gas,geometry,liquid,gravity,ambientMassUnits,device,deviceCaloric,-1);}
    }
    static final class Layout {
        int activityCellsWord = -1, activityFacesWord = -1;
        final VulkanLiquidPhaseRows.Layout rows;
        final Inputs inputs;
        final VulkanReactionProgressLayout progress;
        final int rules, surfaceRules, groups, cells, faces, dryProposals, scratchProposals, wallDevices;
        final ByteBuffer ruleBytes,surfaceRuleBytes;
        final EnumMap<Field, VulkanLiquidStateLayout.Column> columns = new EnumMap<>(Field.class);
        final long endOffset;
        Layout(VulkanRegionReactions.Configuration config, VulkanLiquidPhaseRows.Layout rows,
            Inputs inputs, VulkanReactionProgressLayout progress, long base, long alignment) {
            this.rows = rows;
            this.inputs = inputs;
            this.progress = progress;
            cells = rows.source.cellCount();
            if(inputs.geometry().mixtureStatesWordOffset()<0 || inputs.gas().gas().nodeCapacity()<cells)
                throw new IllegalArgumentException("Gas-liquid transfer requires local mixture state for every cell");
            var carriers=config.fluids().materials().stream().map(m->m.id()).collect(java.util.stream.Collectors.toSet());
            var henryRules=config.chemistry().gasLiquidMassTransfers().stream().filter(r->carriers.contains(config.chemistry().phases().get(config.chemistry().phaseHandle(r.liquidPhaseId())).materialId())).toList();
            var vaporRules=config.thermal().transitions().stream().filter(t->t.interfaceRequirement()==mmm.thermal.ThermalPhaseTransition.InterfaceRequirement.LIQUID_GAS && carriers.contains(config.thermal().material(config.thermal().materialHandle(t.lowerEnergyMaterialId())).carrierId())).toList();
            rules = Math.addExact(henryRules.size(),vaporRules.size());
            surfaceRules=inputs.device()==null?0:inputs.device().definitions().surfaceSpeciesCount();
            surfaceRuleBytes=ByteBuffer.allocateDirect(Math.multiplyExact(112,surfaceRules)).order(ByteOrder.LITTLE_ENDIAN);
            if(inputs.device()!=null){int index=0,slotIndex=0;for(var slot:inputs.device().definitions().surfaces()){
                var material=slot.definition();
                int direction=switch(slot.face()){case WEST->1;case EAST->0;case DOWN->3;case UP->2;case NORTH->5;case SOUTH->4;};
                for(var adsorbate:slot.adsorbates()){
                    int component=inputs.gas().gas().definitions().componentIds().indexOf(adsorbate.componentId());
                    int species=config.chemistry().speciesHandle(adsorbate.speciesId());
                    if(component<0||!inputs.gas().gas().definitions().allowedSpecies().get(component).contains(adsorbate.speciesId()))
                        throw new IllegalArgumentException("Surface gas species is unavailable");
                    long unit=config.chemistry().speciesMassUnits(species,1);
                    surfaceRuleBytes.putInt(slot.device()).putInt(direction).putInt(component).putInt(species).putInt(slotIndex).putInt(index++).putLong(unit);
                    for(double value:new double[]{mmm.units.Mass.kilograms(slot.loadingMassUnits())*material.siteCapacityMolesPerKg()*1e6,
                            material.exchangeMolesPerSquareMeterSecond()*material.effectiveAreaFactor()*1e6,
                            adsorbate.affinityPerPa(),adsorbate.referenceTemperatureK(),adsorbate.adsorptionHeatJPerMol(),
                            adsorbate.referenceEnergyJPerMol(),adsorbate.heatCapacityJPerMolK(),material.minimumTemperatureK(),
                            material.maximumTemperatureK(),material.maximumPressurePa()})surfaceRuleBytes.putDouble(value);
                }slotIndex++;}}
            surfaceRuleBytes.flip();
            dryProposals=inputs.device()==null?0:Math.multiplyExact(inputs.device().portCapacity(),rules);
            scratchProposals=Math.max(Math.multiplyExact(cells,rules),dryProposals);
            wallDevices=inputs.device()==null?0:inputs.device().deviceCapacity();
            if (progress.pendingCapacity() < Math.multiplyExact(cells, rules))
                throw new IllegalArgumentException("Film pending capacity is smaller than cells times rules");
            faces = rows.source.column(VulkanLiquidStateLayout.Field.MOMENTUM_X).count()
                + rows.source.column(VulkanLiquidStateLayout.Field.MOMENTUM_Y).count()
                + rows.source.column(VulkanLiquidStateLayout.Field.MOMENTUM_Z).count();
            ruleBytes = ByteBuffer.allocateDirect(128 * rules).order(ByteOrder.LITTLE_ENDIAN);
            for (var r : henryRules) {
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
                ruleBytes.putInt(material)
                    .putInt(component)
                    .putInt(species)
                    .putInt(config.definitions().identity().rules().indexOf(r.id()))
                    .putLong(mass);
                for (double v : new double[] {r.referenceTemperatureK(), r.henrySolubilityMolPerM3Pa(),
                         r.temperatureSlopeK(), r.liquidFilmVelocityMPerS(), r.gasFilmVelocityMPerS(),
                         r.minimumTemperatureK(), r.maximumTemperatureK(), 0, 0})
                    ruleBytes.putDouble(v);
                for(int i=0;i<4;i++)ruleBytes.putLong(0);
            }
            var phaseModel=new mmm.thermal.GasLiquidPhaseModel(config.thermal(),config.gas());
            for(var t:vaporRules){
                var liquid=config.thermal().material(config.thermal().materialHandle(t.lowerEnergyMaterialId()));
                var vapor=config.thermal().material(config.thermal().materialHandle(t.upperEnergyMaterialId()));
                int material=config.fluids().handle(liquid.carrierId()),component=inputs.gas().gas().definitions().componentIds().indexOf(vapor.carrierId());
                int species=config.chemistry().speciesHandle(t.speciesId());
                if(component<0||!inputs.gas().gas().definitions().allowedSpecies().get(component).contains(t.speciesId()))throw new IllegalArgumentException("Vapor component does not declare transition species: "+t.id());
                var molar=config.chemistry().species(species).molarMassKgPerMol().divide(mmm.chemistry.ChemicalLimits.QUANTA_PER_MOL);
                long unit=mmm.units.Mass.exactUnits(molar.numerator(),molar.denominator());
                var batch=phaseModel.gasBatch(t,1).components().getFirst();
                long quantum=Math.max(1,Math.round((double)batch.massUnits()/unit/t.lowerVolumeSvqPerBatch()));
                double reference=t.equilibriumTemperatureK(101325),slope=phaseModel.saturationSlopePaPerKelvin(t,reference,101325)*reference*reference/101325;
                ruleBytes.putInt(material).putInt(component).putInt(species).putInt(config.definitions().identity().rules().indexOf(t.id())).putLong(unit);
                ruleBytes.putDouble(reference).putDouble(slope).putDouble(t.transferVelocityMPerS()).putDouble(1).putDouble(100000).putDouble(0).putDouble(0).putDouble(0);
                ruleBytes.putInt(1).putInt(0).putLong(quantum).putLong(1).putLong(0).putLong(0);
            }
            ruleBytes.flip();
            groups = inputs.gas().gas().nodeCapacity();
            long cursor = base;
            for (var f : Field.values()) {
                int count = switch (f) {
                    case HEADER -> 160;
                    case RULES -> 32 * rules;
                    case SURFACE_RULES -> 28 * surfaceRules;
                    case CONTROL -> 8;
                    case HEAD -> groups;
                    case GAS_TO_LIQUID_MASS, LIQUID_TO_GAS_MASS -> Math.multiplyExact(cells, 7);
                    case OLD_GAS_U, NEW_GAS_U -> inputs.gas().gas().nodeCapacity();
                    case OLD_K, NEW_K, FACE_P -> faces;
                    case FACE_HEAT -> 2 * faces;
                    case PENDING_TIME, PENDING_RESIDUAL, PENDING_SLOT -> Math.multiplyExact(cells, rules);
                    case LIQUID_QUANTA -> rows.rows;
                    case NEXT, KG, TEMP, GAS_TEMP -> scratchProposals;
                    case WALL_RESERVATION, SURFACE_TEMPERATURE, SURFACE_CHANGED -> wallDevices;
                    case WALL_DELTA -> Math.multiplyExact(2,wallDevices);
                    case WALL_PENDING -> dryProposals==0?0:Math.multiplyExact(cells,rules);
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
            h[Header.SURFACE_RULE_COUNT.word]=surfaceRules;
            h[Header.SCREEN_FACES.word]=inputs.screenFacesWord();
            h[Header.FACE_COUNT.word] = faces;
            h[Header.MATERIAL_COUNT.word] = rows.source.materialCount();
            h[Header.SPECIES_COUNT.word] = rows.species;
            h[Header.PENDING_COUNT.word] = progress.word(VulkanReactionProgressLayout.Field.PENDING_COUNT);
            h[Header.PROPOSAL_CAPACITY.word] = progress.pendingCapacity();
            h[Header.PENDING_CELL.word] = progress.word(VulkanReactionProgressLayout.Field.PENDING_CELL);
            h[Header.PENDING_MATERIAL.word] = progress.word(VulkanReactionProgressLayout.Field.PENDING_MATERIAL);
            h[Header.PENDING_SUPPORT.word] = progress.word(VulkanReactionProgressLayout.Field.PENDING_SUPPORT);
            h[Header.PENDING_RULE.word] = progress.word(VulkanReactionProgressLayout.Field.PENDING_RULE);
            h[Header.PENDING_SECONDS.word] = progress.word(VulkanReactionProgressLayout.Field.PENDING_SECONDS);
            h[Header.RESIDUAL_MOLES.word] = progress.word(VulkanReactionProgressLayout.Field.RESIDUAL_MOLES);
            if(inputs.device()!=null){
                var device=inputs.device();
                h[Header.WALL_COUNTS.word]=device.word(VulkanThermalDeviceLayout.Field.COUNTS);
                h[Header.WALL_PORT_CAPACITY.word]=device.portCapacity();
                h[Header.WALL_CAPACITY.word]=device.deviceCapacity();
                h[Header.WALL_PORT_DEVICE.word]=device.word(VulkanThermalDeviceLayout.Field.PORT_DEVICE);
                h[Header.WALL_PORT_CELL.word]=device.word(VulkanThermalDeviceLayout.Field.PORT_CELL);
                h[Header.WALL_PORT_DIRECTION.word]=device.word(VulkanThermalDeviceLayout.Field.PORT_DIRECTION);
                h[Header.WALL_TEMPERATURE.word]=Math.toIntExact(inputs.deviceCaloric().temperatures().offset()/4);
                h[Header.WALL_CALORIC.word]=Math.toIntExact(inputs.deviceCaloric().header().offset()/4);
                h[Header.WALL_MATERIAL.word]=device.word(VulkanThermalDeviceLayout.Field.MATERIAL);
                h[Header.WALL_MASS.word]=device.word(VulkanThermalDeviceLayout.Field.MASS_UNITS);
                h[Header.WALL_ENERGY.word]=device.word(VulkanThermalDeviceLayout.Field.INTERNAL_ENERGY);
                h[Header.WALL_GENERATION.word]=device.word(VulkanThermalDeviceLayout.Field.GENERATION);
                h[Header.WALL_REQUEST_GENERATION.word]=device.word(VulkanThermalDeviceLayout.Field.REQUEST_GENERATION);
                h[Header.WALL_ENERGY_CHANGE.word]=device.word(VulkanThermalDeviceLayout.Field.DEVICE_ENERGY_CHANGE);
                h[Header.SURFACE_AMOUNTS.word]=device.word(VulkanThermalDeviceLayout.Field.SURFACE_AMOUNTS);
                h[Header.SURFACE_RESIDUALS.word]=device.word(VulkanThermalDeviceLayout.Field.SURFACE_RESIDUALS);
                h[Header.SURFACE_INTERCEPT.word]=device.word(VulkanThermalDeviceLayout.Field.SURFACE_ENERGY_INTERCEPT);
                h[Header.SURFACE_CAPACITY.word]=device.word(VulkanThermalDeviceLayout.Field.SURFACE_HEAT_CAPACITY);
            } else h[Header.WALL_COUNTS.word]=-1;
            for (var f : Field.values())
                h[WORK_FIELDS + f.ordinal()] = word(f);
            h[Header.ACTIVITY_CELLS.word] = activityCellsWord;
            h[Header.ACTIVITY_FACES.word] = activityFacesWord;
            for (int i = 0; i < h.length; i++)
                b.putInt(4 * i, h[i]);
            b.putDouble(4 * Header.CELL_SIZE.word, inputs.geometry().cellSize())
                .putDouble(4 * Header.GRAVITY.word, inputs.gravity())
                .putLong(4 * Header.AMBIENT_MASS_UNITS.word, inputs.ambientMassUnits());
            return b;
        }
        List<VulkanGasThermodynamics.TableChunk> headers() {
            return List.of(new VulkanGasThermodynamics.TableChunk(column(Field.HEADER).offset(), headerBytes()),
                new VulkanGasThermodynamics.TableChunk(
                    rows.column(VulkanLiquidPhaseRows.Field.HEADER).offset(), rows.headerBytes()));
        }
        List<VulkanGasThermodynamics.TableChunk> tables() {
            var chunks=new ArrayList<VulkanGasThermodynamics.TableChunk>();
            if(ruleBytes.hasRemaining())chunks.add(new VulkanGasThermodynamics.TableChunk(column(Field.RULES).offset(),ruleBytes.duplicate()));
            if(surfaceRuleBytes.hasRemaining())chunks.add(new VulkanGasThermodynamics.TableChunk(column(Field.SURFACE_RULES).offset(),surfaceRuleBytes.duplicate()));
            return List.copyOf(chunks);
        }
        List<VulkanEnergyLedger.Stream> roundoffStreams() {
            var a = new ArrayList<>(rows.energyChangeStreams());
            if(wallDevices>0)a.add(stream(Field.WALL_DELTA,1));
            a.add(stream(Field.NEW_GAS_U, 1));
            a.add(stream(Field.OLD_GAS_U, -1));
            a.add(stream(Field.NEW_K, 1));
            a.add(stream(Field.OLD_K, -1));
            a.add(stream(Field.PE_WORK, -1));
            return List.copyOf(a);
        }
        List<VulkanEnergyLedger.Stream> mechanicalWorkStreams() {
            return List.of(stream(Field.OLD_K, 1), stream(Field.NEW_K, -1), stream(Field.PE_WORK, 1));
        }
        private VulkanEnergyLedger.Stream stream(Field f, int sign) {
            return new VulkanEnergyLedger.Stream(1, word(f), column(f).count(), 2, sign);
        }
    }
    private final FlipKernelBindings bindings;
    private final FlipKernelBindings proposeBindings;
    private final FlipKernelBindings exchangeBindings;
    private final VulkanLiquidPhaseRows rows;
    private VulkanStageTimings timings;
    private int completedPhaseBatches;
    int completedPhaseBatches(){return completedPhaseBatches;}
    private final VulkanCommandTemplates templates;
    VulkanGasLiquidMassTransfer(VulkanDeviceContext c) {
        templates = new VulkanCommandTemplates(c);
        var shader = VulkanLiquidPhaseRows.resource("gas-liquid-mass-transfer.comp")
                .replace("// HOST_LAYOUT", shaderLayout()).replace("// SERIES_CONDUCTANCE_LIBRARY", VulkanLiquidPhaseRows.resource("series-conductance.glsl"))
                .replace("// PHASE_ROWS_LIBRARY", VulkanLiquidPhaseRows.resource("liquid-phase-rows.glsl"))
                .replace("// DYADIC_LIBRARY", VulkanLiquidPhaseRows.resource("liquid-dyadic.glsl"))
                .replace("// MIXTURE_PRESSURE_EOS", VulkanLiquidPhaseRows.resource("mixture-pressure-eos.glsl"))
                .replace("// PLIC_FACE_LIBRARY", VulkanLiquidPhaseRows.resource("plic-face.glsl"));
        bindings = new FlipKernelBindings(c, 3, shader, 16, -1);
        try {
            proposeBindings = new FlipKernelBindings(c, 3, shader, 16, 1);
        } catch (RuntimeException | Error e) {
            bindings.close();
            throw e;
        }
        try {
            exchangeBindings = new FlipKernelBindings(c, 3, shader, 16, 4);
        } catch (RuntimeException | Error e) {
            proposeBindings.close();
            bindings.close();
            throw e;
        }
        try {
            rows = new VulkanLiquidPhaseRows(c);
        } catch (RuntimeException | Error e) {
            exchangeBindings.close();
            proposeBindings.close();
            bindings.close();
            throw e;
        }
        try {
            if(Boolean.getBoolean("mmm.gpuMechanicalStages") && c.hasTimestampQueries())timings=new VulkanStageTimings(c,24);
        } catch (RuntimeException | Error e) {
            rows.close();
            exchangeBindings.close();
            proposeBindings.close();
            bindings.close();
            throw e;
        }
    }
    void rebindRanges(FlipKernelBindings.BufferRange arena, FlipKernelBindings.BufferRange errors) {
        bindings.rebindRanges(List.of(arena, errors, arena));
        proposeBindings.rebindRanges(List.of(arena, errors, arena));
        exchangeBindings.rebindRanges(List.of(arena, errors, arena));
        rows.rebindRanges(arena, errors);
    }
    int execute(VulkanRegionStorage.Candidate candidate, Layout l, double duration, boolean phaseEnabled,int maximumPhaseBatches,VulkanGasThermodynamics.Layout eos,
        VulkanGasGeometry.Layout geometry) {
        if (!(Double.isFinite(duration) && duration >= 0))
            throw new IllegalArgumentException("Invalid film duration");
        var prepared = candidate.continueBatch((command, input, output, errors, staging, stack) -> {
            rebindRanges(new FlipKernelBindings.BufferRange(output), new FlipKernelBindings.BufferRange(errors));
            if(timings!=null)timings.reset(command);
            var h = l.headerBytes();
            h.putInt(4 * Header.GAS_HEADER.word, eos.headerWordOffset())
                .putInt(4 * Header.GEOMETRY_HEADER.word, geometry.headerWordOffset())
                .putInt(4 * Header.PHASE_ENABLED.word,phaseEnabled?1:0).putInt(4 * Header.MAXIMUM_PHASE_BATCHES.word,maximumPhaseBatches)
                .putDouble(4 * Header.DURATION.word, duration);
            vkCmdUpdateBuffer(command, output.handle(), l.column(Field.HEADER).offset(), h);
            VulkanRegionStorage.barrier(command, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT, stack);
            if(timings!=null)recordPrepare(command,l);
            else templates.record(command,new VulkanCommandTemplates.Key(0,output,errors,0,0,l),
                    secondary->recordPrepare(secondary,l));
        }, l.rows.feedbackOffset(), 16);
        if (prepared.error() != 0)
            return prepared.error();
        if (prepared.feedback().getInt(0) == 1)
            throw new VulkanRegionReactions.CapacityGrowth(
                prepared.feedback().getInt(8), prepared.feedback().getInt(12), 0, 0, 0);
        if (prepared.feedback().getInt(0) != 2)
            throw new IllegalStateException("Film liquid successor is incomplete");
        var result = candidate
            .continueBatch(
                (command, input, output, errors, staging, stack) -> recordPublish(command, l), l.feedbackOffset(), 28);
        completedPhaseBatches=result.error()==0?result.feedback().getInt(24):0;
        if(timings!=null){
            double contact=0;
            for(var sample:timings.read()){
                if(sample.stage().equals("gas-transfer-contact"))contact+=sample.milliseconds();
                else System.out.printf("mechanical_kernel=%s gpu_ms=%.3f%n",sample.stage(),sample.milliseconds());
            }
            System.out.printf("mechanical_kernel=gas-transfer-contact gpu_ms=%.3f%n",contact);
        }
        return result.error();
    }
    void recordPrepare(VkCommandBuffer c, Layout l) {
        timed(c,"gas-transfer-import",()->rows.recordImport(c,l.rows));
        timed(c,"gas-transfer-clear-pending",()->{
            stage(c,l,0,Math.max(Math.multiplyExact(7,l.cells),Math.max(Math.multiplyExact(l.cells,l.rules),Math.max(l.wallDevices,Math.max(l.groups,l.inputs.gas().gas().nodeCapacity())))));
            stage(c,l,9,l.progress.pendingCapacity());
        });
        for(int pass=0;pass<7;pass++){
            final int current=pass;
            if(pass==6)stage(c,l,15,l.cells,pass);
            timed(c,"gas-transfer-contact",()->stage(c,l,1,l.cells,current));
        }
        if(l.dryProposals>0){
            timed(c,"gas-transfer-dry-wall",()->{
                stage(c,l,13,l.dryProposals);
                stage(c,l,4,l.groups,7);
            });
        }
        if(l.surfaceRules>0)stage(c,l,16,1);
        timed(c,"gas-transfer-momentum-heat",()->{
            stage(c,l,5,l.faces);
            stage(c,l,6,l.cells);
            stage(c,l,7,l.inputs.gas().gas().nodeCapacity());
            stage(c,l,11,l.cells);
        });
        if(l.dryProposals>0 || l.surfaceRules>0)stage(c,l,14,l.wallDevices);
        timed(c,"gas-transfer-assemble",()->rows.recordAssemble(c,l.rows));
    }
    void recordPublish(VkCommandBuffer c, Layout l) {
        timed(c,"gas-transfer-publish",()->{
            rows.recordPublish(c,l.rows);
            stage(c,l,8,Math.max(l.faces,Math.multiplyExact(l.cells,l.rules)));
        });
    }
    private void timed(VkCommandBuffer c,String label,Runnable commands){
        if(timings==null)commands.run();else timings.record(c,label,commands);
    }
    private void stage(VkCommandBuffer c, Layout l, int stage, int count) { stage(c,l,stage,count,0); }
    private void stage(VkCommandBuffer c, Layout l, int stage, int count, int pass) {
        if (count == 0)
            return;
        try (var s = stackPush()) {
            var selected=stage==1?proposeBindings:stage==4?exchangeBindings:bindings;
            selected.bind(c, s);
            vkCmdPushConstants(c, selected.pipelineLayout(), VK_SHADER_STAGE_COMPUTE_BIT, 0,
                s.ints(l.word(Field.HEADER), stage, count, pass));
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
            templates.close();
            if(timings!=null)timings.close();
        } finally {
            try { rows.close(); } finally {
                try { exchangeBindings.close(); } finally {
                    try { proposeBindings.close(); } finally { bindings.close(); }
                }
            }
        }
    }
}
