package mmm.gpu;

import mmm.fluid.FluidMaterialRegistry;
import mmm.fluid.MaterialRegistry;
import org.lwjgl.vulkan.VkCommandBuffer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Conservative MAC normal/shear stresses, fixed walls and prescribed-body tangential contact. */
final class VulkanLiquidViscosity implements AutoCloseable {
    private enum Stage {
        PREPARE_CELLS,
        PREPARE_FACES,
        GATHER_HEAT,
        CLEAR_CONTACTS,
        COMPILE_CELL_STRAIN,
        PREPARE_FACE_SCALE,
        SCALE_STRAINS,
        APPLY_FACES,
        APPLY_STRAIN_HEAT,
        REFRESH_MATERIAL
    }

    /** Header words shared with the viscosity shader. */
    private enum Header {
        SIZE_X, SIZE_Y, SIZE_Z, CELL_COUNT, FACE_COUNT, CELLS, FACES, WALL_GEOMETRY, TEMPERATURE, ACTIVITY,
        NO_SLIP, MATERIAL_COUNT, CONTACT_FACE_OFFSETS, CONTACT_BODY_HANDLES, CONTACT_AREA_OVER_DISTANCE,
        CONTACT_VELOCITIES, CONTACT_CAPACITY, BODY_COUNT, ACTIVITY_CELLS,
        AQUEOUS_MATERIAL, WATER_SPECIES, SODIUM_SPECIES, CHLORIDE_SPECIES, SALT_SPECIES
    }
    private static final int STOCK_FIELDS = 24, WORK_FIELDS = 48, HEADER_WORDS = 128;
    private static String shaderLayout() {
        var definitions = new StringBuilder();
        for (var stage : Stage.values())
            definitions.append("#define STAGE_").append(stage.name()).append(' ').append(stage.ordinal()).append('\n');
        for (var header : Header.values())
            definitions.append("#define H_").append(header.name()).append(' ').append(header.ordinal()).append('\n');
        definitions.append("#define STOCK_FIELDS ").append(STOCK_FIELDS).append('\n');
        definitions.append("#define WORK_FIELDS ").append(WORK_FIELDS).append('\n');
        definitions.append("#define H_ETHANOL_SPECIES 111\n#define H_ETHANOL_VISCOSITY 112\n#define H_ETHANOL_MOLAR_MASS 114\n#define H_WATER_MOLAR_MASS 116\n#define H_SALT_A 118\n#define H_SALT_B 120\n#define H_SALT_MAX_MOLALITY 122\n#define H_SALT_REFERENCE_TEMPERATURE 124\n#define H_SALT_TEMPERATURE_TOLERANCE 126\n");
        definitions.append("#define H_BED_OFFSETS 90\n#define H_BED_ENTRIES 91\n#define H_BED_PARTICLES 92\n#define H_BED_VOLUME 93\n#define H_BED_POROSITY 94\n#define H_BED_MATERIAL 95\n#define H_BED_PROPERTIES 96\n#define H_BED_MASKS 97\n");
        for (var field : VulkanLiquidStateLayout.Field.values())
            definitions.append("#define STOCK_").append(field.name()).append(' ').append(field.ordinal()).append('\n');
        for (var field : Field.values())
            definitions.append("#define FIELD_").append(field.name()).append(' ').append(field.ordinal()).append('\n');
        return definitions.toString();
    }
    static String convectionShaderLayout() {
        return "#define VISC_H_CONTACT_FACE_OFFSETS " + Header.CONTACT_FACE_OFFSETS.ordinal() + "\n"
                + "#define VISC_H_CONTACT_AREA_OVER_DISTANCE " + Header.CONTACT_AREA_OVER_DISTANCE.ordinal() + "\n"
                + "#define VISC_H_CONTACT_VELOCITIES " + Header.CONTACT_VELOCITIES.ordinal() + "\n"
                + "#define VISC_H_CONTACT_CAPACITY " + Header.CONTACT_CAPACITY.ordinal() + "\n";
    }
    record Tables(int[] offsets,double[] segments,int aqueousMaterial,int waterSpecies,int sodiumSpecies,int chlorideSpecies,int saltSpecies,int ethanolSpecies,double ethanolMolarMass,double waterMolarMass,double ethanolViscosity,mmm.chemistry.ChemicalPhaseDefinition.ViscosityCorrection correction) {
        Tables(int[] offsets,double[] segments){this(offsets,segments,-1,-1,-1,-1,-1,-1,0,0,0,null);}
        Tables(int[] offsets,double[] segments,int aqueousMaterial,int waterSpecies,int sodiumSpecies,int chlorideSpecies,int saltSpecies){
            this(offsets,segments,aqueousMaterial,waterSpecies,sodiumSpecies,chlorideSpecies,saltSpecies,-1,0,0,0,null);
        }
    }
    static Tables tables(FluidMaterialRegistry fluids,MaterialRegistry materials) {
        return tables(fluids,materials,null);
    }
    static Tables tables(FluidMaterialRegistry fluids,MaterialRegistry materials,mmm.chemistry.ChemicalRegistry chemistry) {
        int[] offsets=new int[fluids.materials().size()+1];var values=new ArrayList<Double>();
        for(int material=0;material<fluids.materials().size();material++) {
            var entry=materials.entry(fluids.material(material).id());
            if(!entry.declaresViscosity())throw new IllegalArgumentException("Liquid viscosity is missing for "+entry.material().id());
            for(var s:entry.viscosity()){values.add(s.minimumTemperatureK());values.add(s.maximumTemperatureK());values.add(s.dynamicViscosityPascalSecondsAtMinimum());values.add(s.dynamicViscosityPascalSecondsAtMaximum());}
            offsets[material+1]=values.size()/4;
        }
        var ids=chemistry==null?List.<String>of():chemistry.species().stream().map(mmm.chemistry.ChemicalSpecies::id).toList();
        int aqueous=-1;for(int i=0;i<fluids.materials().size();i++)if(fluids.material(i).id().equals("mmm:water"))aqueous=i;
        var ethanol=chemistry!=null&&chemistry.hasSpecies(mmm.chemistry.BuiltinChemicalDefinitions.ETHANOL)
                ?chemistry.species(chemistry.speciesHandle(mmm.chemistry.BuiltinChemicalDefinitions.ETHANOL)):null;
        var water=chemistry!=null&&chemistry.hasSpecies(mmm.chemistry.BuiltinChemicalDefinitions.H2O)
                ?chemistry.species(chemistry.speciesHandle(mmm.chemistry.BuiltinChemicalDefinitions.H2O)):null;
        var liquid=ethanol==null?null:ethanol.liquidProperties();
        var correction=chemistry==null?null:chemistry.phases().stream().filter(p->p.materialId().equals("mmm:water"))
                .map(mmm.chemistry.ChemicalPhaseDefinition::viscosityCorrection).filter(java.util.Objects::nonNull).findFirst().orElse(null);
        return new Tables(offsets,values.stream().mapToDouble(Double::doubleValue).toArray(),aqueous,
                ids.indexOf(mmm.chemistry.BuiltinChemicalDefinitions.H2O),ids.indexOf(mmm.chemistry.BuiltinChemicalDefinitions.NA_PLUS),
                ids.indexOf(mmm.chemistry.BuiltinChemicalDefinitions.CL_MINUS),ids.indexOf(mmm.chemistry.BuiltinChemicalDefinitions.NACL),
                liquid==null?-1:ids.indexOf(mmm.chemistry.BuiltinChemicalDefinitions.ETHANOL),
                liquid==null?0:ethanol.molarMassKgPerMol().toDouble(),water==null?0:water.molarMassKgPerMol().toDouble(),
                liquid==null?0:liquid.viscosityPaS(),correction);
    }
    /** Face CSR stores geometry area/distance in metres; body velocity is a three-component SI vector.
     * The contact geometry producer includes only tangential wet area and uses stable body order. */
    record PrescribedContacts(int capacity,int bodies,VulkanLiquidStateLayout.Column faceOffsets,
                              VulkanLiquidStateLayout.Column bodyHandles,VulkanLiquidStateLayout.Column areaOverDistance,
                              VulkanLiquidStateLayout.Column velocities) { }
    enum Field {
        MU(8), EXTRAPOLATED(4), WALL_CONDUCTANCE(8), STRESS_HEAT(8), WALL_HEAT(8), CONTACT_FACE_HEAT(8),
        CONTACT_FACE_WORK(8), SOURCE_CELL_HEAT(8), SOURCE_CELL_WORK(8), SOURCE_FACE_VELOCITY(8), SOURCE_FACE_K(8),
        FINAL_FACE_K(8), CELL_FIXED_IMPULSE(8), WALL_FACE_IMPULSE(8), CONTACT_IMPULSE(8), CONTACT_WORK(8),
        MATERIAL_OFFSETS(4), VISCOSITY_SEGMENTS(8), STRAIN_CONDUCTANCE(8), FACE_INVERSE_MASS(8),
        FACE_SCALE(8), STRAIN_CLASS(4), BED_CONDUCTANCE(8);
        final int width;Field(int width){this.width=width;}
    }
    static final class Layout {
        private final VulkanLiquidStateLayout source;
        private final VulkanLiquidStateLayout.Column header;
        private final EnumMap<Field,VulkanLiquidStateLayout.Column> columns=new EnumMap<>(Field.class);
        private final Tables tables;private final PrescribedContacts contacts;
        private final int cellsWord,facesWord,wallsWord,temperatureWord,activityWord,activityCellsWord,faces;
        private final boolean noSlip;private final long endOffset;
        private int[] beds;
        Layout(VulkanLiquidStateLayout source,VulkanLiquidStateLayout.Column cells,VulkanLiquidStateLayout.Column faces,
               VulkanLiquidStateLayout.Column walls,int carrierTemperatureWord,int faceActivityWord,Tables tables,
               PrescribedContacts contacts,boolean noSlip,long scratchBase,long alignment) {
            this(source,cells,faces,walls,carrierTemperatureWord,faceActivityWord,-1,tables,contacts,noSlip,scratchBase,alignment);
        }
        Layout(VulkanLiquidStateLayout source,VulkanLiquidStateLayout.Column cells,VulkanLiquidStateLayout.Column faces,
               VulkanLiquidStateLayout.Column walls,int carrierTemperatureWord,int faceActivityWord,int cellActivityWord,Tables tables,
               PrescribedContacts contacts,boolean noSlip,long scratchBase,long alignment) {
            this.source=source;this.tables=tables;this.contacts=contacts;this.noSlip=noSlip;
            cellsWord=word(cells);facesWord=word(faces);wallsWord=word(walls);temperatureWord=carrierTemperatureWord;activityWord=faceActivityWord;activityCellsWord=cellActivityWord;
            this.faces=faces.count()/4;
            if(scratchBase<source.bytes()||alignment<8||(alignment&(alignment-1))!=0||cells.count()!=4*source.cellCount()||carrierTemperatureWord<0)
                throw new IllegalArgumentException("Viscous fields differ from resident liquid");
            long[] cursor={scratchBase};header=VulkanLiquidViscosity.column(cursor,HEADER_WORDS,4,alignment);int n=source.cellCount();
            for(Field field:Field.values()) {
                int count=switch(field){
                    case WALL_CONDUCTANCE,CELL_FIXED_IMPULSE,BED_CONDUCTANCE->3*n;case STRESS_HEAT->6*n;
                    case WALL_HEAT,CONTACT_FACE_HEAT,CONTACT_FACE_WORK,SOURCE_FACE_VELOCITY,SOURCE_FACE_K,FINAL_FACE_K,WALL_FACE_IMPULSE,FACE_INVERSE_MASS,FACE_SCALE->this.faces;
                    case CONTACT_IMPULSE,CONTACT_WORK->contacts==null?0:contacts.capacity();
                    case STRAIN_CONDUCTANCE,STRAIN_CLASS->6*n;
                    case MATERIAL_OFFSETS->tables.offsets().length;case VISCOSITY_SEGMENTS->tables.segments().length;default->n;
                };
                columns.put(field,VulkanLiquidViscosity.column(cursor,count,field.width,alignment));
            }
            endOffset=align(cursor[0],alignment);
        }
        VulkanLiquidStateLayout source(){return source;}
        VulkanLiquidStateLayout.Column header(){return header;}
        VulkanLiquidStateLayout.Column column(Field field){return columns.get(field);}
        int faces(){return faces;}long endOffset(){return endOffset;}
        void bindBeds(VulkanGranularGeometry.Layout geometry,VulkanGranularTransitions.Layout transitions,VulkanLiquidStateLayout.Column masks) {
            var state=geometry.state();
            beds=new int[]{geometry.word(VulkanGranularGeometry.Field.OFFSETS),geometry.word(VulkanGranularGeometry.Field.ENTRIES),
                    state.particleCapacity(),state.word(VulkanGranularStateLayout.Field.SOLID_VOLUME_SVQ),
                    state.word(VulkanGranularStateLayout.Field.DEPOSIT_POROSITY),state.word(VulkanGranularStateLayout.Field.MATERIAL),
                    transitions.word(VulkanGranularTransitions.Field.MATERIALS),word(masks)};
        }
        List<VulkanEnergyLedger.Stream> externalWorkRoundoffStreams(){
            return List.of(new VulkanEnergyLedger.Stream(1,word(column(Field.SOURCE_CELL_WORK)),source.cellCount(),2,1),
                    new VulkanEnergyLedger.Stream(1,word(column(Field.CONTACT_WORK)),contacts==null?0:contacts.capacity(),2,-1));
        }
        ByteBuffer headerBytes(){return headerBytes(source);}
        ByteBuffer headerBytes(VulkanLiquidStateLayout sourceLayout){
            var out=ByteBuffer.allocateDirect(Math.toIntExact(header.bytes())).order(ByteOrder.LITTLE_ENDIAN);int[] data=new int[HEADER_WORDS];
            data[Header.SIZE_X.ordinal()]=source.sizeX();data[Header.SIZE_Y.ordinal()]=source.sizeY();data[Header.SIZE_Z.ordinal()]=source.sizeZ();data[Header.CELL_COUNT.ordinal()]=source.cellCount();data[Header.FACE_COUNT.ordinal()]=faces;
            data[Header.CELLS.ordinal()]=cellsWord;data[Header.FACES.ordinal()]=facesWord;data[Header.WALL_GEOMETRY.ordinal()]=wallsWord;data[Header.TEMPERATURE.ordinal()]=temperatureWord;data[Header.ACTIVITY.ordinal()]=activityWord;data[Header.NO_SLIP.ordinal()]=noSlip?1:0;
            data[Header.ACTIVITY_CELLS.ordinal()]=activityCellsWord;
            data[90]=-1;if(beds!=null)System.arraycopy(beds,0,data,90,beds.length);
            data[Header.AQUEOUS_MATERIAL.ordinal()]=tables.aqueousMaterial();data[Header.WATER_SPECIES.ordinal()]=tables.waterSpecies();
            data[Header.SODIUM_SPECIES.ordinal()]=tables.sodiumSpecies();data[Header.CHLORIDE_SPECIES.ordinal()]=tables.chlorideSpecies();data[Header.SALT_SPECIES.ordinal()]=tables.saltSpecies();
            data[111]=tables.ethanolSpecies();data[Header.MATERIAL_COUNT.ordinal()]=tables.offsets().length-1;
            data[Header.CONTACT_FACE_OFFSETS.ordinal()]=contacts==null?-1:word(contacts.faceOffsets());data[Header.CONTACT_BODY_HANDLES.ordinal()]=contacts==null?0:word(contacts.bodyHandles());
            data[Header.CONTACT_AREA_OVER_DISTANCE.ordinal()]=contacts==null?0:word(contacts.areaOverDistance());data[Header.CONTACT_VELOCITIES.ordinal()]=contacts==null?0:word(contacts.velocities());
            data[Header.CONTACT_CAPACITY.ordinal()]=contacts==null?0:contacts.capacity();data[Header.BODY_COUNT.ordinal()]=contacts==null?0:contacts.bodies();
            for(var field:VulkanLiquidStateLayout.Field.values())data[STOCK_FIELDS+field.ordinal()]=word(sourceLayout.column(field));
            for(var field:Field.values())data[WORK_FIELDS+field.ordinal()]=word(column(field));
            for(int i=0;i<data.length;i++)out.putInt(4*i,data[i]);
            out.putDouble(4*112,tables.ethanolViscosity()).putDouble(4*114,tables.ethanolMolarMass()).putDouble(4*116,tables.waterMolarMass());
            var correction=tables.correction();
            if(correction!=null)out.putDouble(4*118,correction.a()).putDouble(4*120,correction.b()).putDouble(4*122,correction.maximumMolality()).putDouble(4*124,correction.referenceTemperatureK()).putDouble(4*126,correction.temperatureToleranceK());return out;
        }
        void initializeHeaderAndTables(ByteBuffer arena){
            arena.duplicate().position(Math.toIntExact(header.offset())).put(headerBytes());
            for(int i=0;i<tables.offsets().length;i++)arena.putInt(Math.toIntExact(column(Field.MATERIAL_OFFSETS).offset()+4L*i),tables.offsets()[i]);
            for(int i=0;i<tables.segments().length;i++)arena.putDouble(Math.toIntExact(column(Field.VISCOSITY_SEGMENTS).offset()+8L*i),tables.segments()[i]);
        }
    }
    private final FlipKernelBindings[] bindings=new FlipKernelBindings[Stage.values().length];
    VulkanLiquidViscosity(VulkanDeviceContext context){
        try{String source=shader();for(var stage:Stage.values())bindings[stage.ordinal()]=new FlipKernelBindings(context,3,source,32,stage.ordinal());}
        catch(RuntimeException|Error failure){close();throw failure;}
    }
    void rebindRanges(FlipKernelBindings.BufferRange source,FlipKernelBindings.BufferRange candidate,FlipKernelBindings.BufferRange errors){var ranges=List.of(source,candidate,errors);for(var pipeline:bindings)pipeline.rebindRanges(ranges);}

    void recordMaterial(VkCommandBuffer command,Layout layout,double cellSize,int temperatureWord){
        stage(command,layout,Stage.REFRESH_MATERIAL.ordinal(),layout.source.cellCount(),cellSize,0,temperatureWord);
    }
    /** Apply all viscous stresses with shared, finite face impulses. */
    void record(VkCommandBuffer command,Layout layout,double cellSize,double duration){
        prepareFields(command,layout,cellSize,duration);
        stage(command,layout,Stage.PREPARE_FACE_SCALE.ordinal(),layout.faces,cellSize,duration);
        stage(command,layout,Stage.SCALE_STRAINS.ordinal(),Math.multiplyExact(6,layout.source.cellCount()),cellSize,duration);
        stage(command,layout,Stage.APPLY_FACES.ordinal(),layout.faces,cellSize,duration);
        stage(command,layout,Stage.APPLY_STRAIN_HEAT.ordinal(),layout.source.cellCount(),cellSize,duration);
        stage(command,layout,Stage.GATHER_HEAT.ordinal(),layout.source.cellCount(),cellSize,duration);
    }
    private void prepareFields(VkCommandBuffer command,Layout layout,double cellSize,double duration){
        if(!(cellSize>0&&Double.isFinite(cellSize)&&duration>0&&Double.isFinite(duration)))throw new IllegalArgumentException("Invalid viscous step");

        stage(command,layout,Stage.PREPARE_CELLS.ordinal(),layout.source.cellCount(),cellSize,duration);
        stage(command,layout,Stage.PREPARE_FACES.ordinal(),layout.faces,cellSize,duration);
        if(layout.contacts!=null)stage(command,layout,Stage.CLEAR_CONTACTS.ordinal(),layout.contacts.capacity(),cellSize,duration);
        stage(command,layout,Stage.COMPILE_CELL_STRAIN.ordinal(),Math.multiplyExact(6,layout.source.cellCount()),cellSize,duration);
    }
    private void stage(VkCommandBuffer command,Layout layout,int stage,int count,double h,double dt){
        stage(command,layout,stage,count,h,dt,0);
    }
    private void stage(VkCommandBuffer command,Layout layout,int stage,int count,double h,double dt,int temperatureWord){
        if(count==0)return;int groups=(count+127)/128;
        try(var stack=stackPush()){
            var push=stack.malloc(32).putInt(word(layout.header)).putInt(0).putInt(temperatureWord).putInt(count).putDouble(h).putDouble(dt).flip();
            var pipeline=bindings[stage];pipeline.bind(command,stack);
            vkCmdPushConstants(command,pipeline.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,push);
            vkCmdDispatch(command,Math.min(groups,65535),(groups+65534)/65535,1);
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
        }
    }
    private static int word(VulkanLiquidStateLayout.Column column){return Math.toIntExact(column.offset()/4);}
    private static long align(long n,long alignment){return Math.addExact(n,alignment-1)&-alignment;}
    private static VulkanLiquidStateLayout.Column column(long[] cursor,int count,int width,long alignment){var column=new VulkanLiquidStateLayout.Column(align(cursor[0],alignment),count,width);cursor[0]=column.offset()+column.bindingBytes();return column;}
    private static String shader(){try(var input=VulkanLiquidViscosity.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/liquid-viscosity.comp")){
        if(input==null)throw new IllegalStateException("Viscosity shader missing");return new String(input.readAllBytes(),StandardCharsets.UTF_8).replace("// HOST_LAYOUT", shaderLayout()).replace("// SERIES_CONDUCTANCE_LIBRARY", VulkanLiquidPhaseRows.resource("series-conductance.glsl"));
    }catch(IOException failure){throw new IllegalStateException("Viscosity shader unavailable",failure);}}
    @Override public void close(){for(var pipeline:bindings)if(pipeline!=null)pipeline.close();}
}
