package mmm.gpu;

import org.lwjgl.vulkan.VkCommandBuffer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import static mmm.gpu.VulkanRegionStorage.barrier;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Actual inventory owners, spatial thermal samples, and finite-volume conduction contacts. */
final class VulkanThermalContacts implements AutoCloseable {
    static final int LIQUID_CALORIC_WORD=159;
    /** Header word positions shared with the shader. */
    private enum Header {
        LIQUID_CAPACITY(0),
        GAS_CAPACITY(1),
        PARTICLE_CAPACITY(2),
        DEPOSIT_CAPACITY(3),
        CELL_COUNT(4),
        SIZE_X(5),
        SIZE_Y(6),
        SIZE_Z(7),
        SAMPLE_CAPACITY(8),
        OWNER_CAPACITY(9),
        FIXED_MASKS(11),
        BATH_TEMPERATURES(12),
        FACES(13),
        LIQUID_COUNTS(14),
        GAS_COUNTS(15),
        GRANULAR_COUNTS(16),
        LIQUID_CELL_OFFSETS(17),
        LIQUID_MATERIALS(18),
        LIQUID_SUPPORT_MASKS(19),
        LIQUID_VOLUMES(20),
        LIQUID_MASS(21),
        LIQUID_ENERGY(22),
        LIQUID_TEMPERATURE(23),
        GAS_COMPONENT_OFFSETS(24),
        GAS_COMPONENT_HANDLES(25),
        GAS_STANDARD_VOLUME(26),
        GAS_MASS(27),
        GAS_ENERGY(28),
        GAS_STATES(29),
        GAS_CELL_NODES(30),
        GAS_DRY_VOLUMES(31),
        GAS_DRY_MASKS(32),
        GRANULAR_MATERIAL(33),
        GRANULAR_VOLUME(34),
        GRANULAR_MASS(35),
        GRANULAR_ENERGY(36),
        GRANULAR_TEMPERATURE(37),
        GRANULAR_SUPPORT_CELLS(38),
        GRANULAR_SUPPORT_QUOTAS(39),
        DEPOSIT_CELL(40),
        LIQUID_MATERIAL_COUNT(41),
        GAS_MATERIAL_COUNT(42),
        GRANULAR_MATERIAL_COUNT(43),
        COHERENT_MATERIAL_COUNT(44),
        COHERENT_CAPACITY(45),
        COHERENT_COUNTS(46),
        DEVICE_MATERIAL_COUNT(47),
        CONDUCTION_COUNTS(96), CONDUCTION_ENERGY(97), CONDUCTION_INITIAL_T(98), CONDUCTION_TERM_OFFSETS(99),
        CONDUCTION_TERM_MATERIAL(100), CONDUCTION_TERM_MASS(101), CONDUCTION_CONTACT_A(102),
        CONDUCTION_CONTACT_B(103), CONDUCTION_CONTACT_G(104), CONDUCTION_BATH_T(105),
        CONDUCTION_OWNER_CAPACITY(106),
        CONDUCTION_TERM_CAPACITY(107),
        CONDUCTION_CONTACT_CAPACITY(108),
        COHERENT_CELL(109),
        COHERENT_MATERIAL(110),
        COHERENT_VOLUME(111),
        COHERENT_MASS(112),
        COHERENT_ENERGY(113),
        COHERENT_TEMPERATURE(114),
        CELL_SIZE(128),
        DEVICE_CAPACITY(132),
        DEVICE_COUNTS(133),
        DEVICE_ENERGY(134),
        DEVICE_MASS(135),
        DEVICE_MATERIAL(136),
        DEVICE_TEMPERATURE(137),
        DEVICE_GENERATION(138),
        DEVICE_REQUEST_GENERATION(139),
        DEVICE_ENERGY_CHANGE(140),
        PORT_DEVICE(141),
        PORT_CELL(142),
        PORT_DIRECTION(143),
        PORT_WALL_THICKNESS(144),
        PORT_WALL_CONDUCTIVITY(145),
        PORT_FILM_THICKNESS(146),
        PORT_CAPACITY(147),
        PLIC_PLANES(148),
        PLIC_CELLS(149), SCREEN_FACES(150),
        CONDUCTION_HEADER(151),
        ACTIVITY_CELLS(152), LIQUID_SPECIES_OFFSETS(153), LIQUID_SPECIES_HANDLES(154),
        LIQUID_SPECIES_AMOUNTS(155), AQUEOUS_MATERIAL(156), ETHANOL_SPECIES(157), PLIC_WALL_GEOMETRY(158), LIQUID_CALORIC(LIQUID_CALORIC_WORD);
        final int word;
        Header(int word) { this.word = word; }
    }
    private static String headerDefines() {
        var result = new StringBuilder();
        for (var field : Header.values())
            result.append("#define H_").append(field.name()).append(' ').append(field.word).append('\n');
        return result.toString();
    }

    private static final int WORK_FIELDS = 48;
    private static String shaderLayout() {
        return headerDefines() + VulkanLiquidThermodynamics.shaderLayout() + "#define WORK_FIELDS " + WORK_FIELDS + "\n" + VulkanShaderLayout.fields("FIELD_", Field.values())
            + "#define CONDUCTION_FIELDS " + VulkanThermalConduction.WORK_FIELDS + "\n"
            + VulkanShaderLayout.fields("CONDUCTION_", VulkanThermalConduction.Field.values());
    }

    record Inputs(VulkanLiquidStateLayout liquid,VulkanLiquidThermodynamics.Layout liquidThermal,
                  VulkanGasStateLayout gas,VulkanGasThermodynamics.Layout gasThermal,VulkanGasGeometry.Layout gasGeometry,
                  VulkanGranularStateLayout granular,VulkanLiquidThermodynamics.Layout granularThermal,
                  VulkanCoherentStateLayout coherent,VulkanLiquidThermodynamics.Layout coherentThermal,
                  VulkanThermalDeviceLayout device,VulkanLiquidThermodynamics.Layout deviceThermal,
                  int fixedMasksWord,int bathTemperaturesWord,int facesWord,double cellSize,int activityCellWord,
                  int plicPlanesWord,int plicCellsWord,int screenFacesWord){
        Inputs(VulkanLiquidStateLayout liquid,VulkanLiquidThermodynamics.Layout liquidThermal,
               VulkanGasStateLayout gas,VulkanGasThermodynamics.Layout gasThermal,VulkanGasGeometry.Layout gasGeometry,
               VulkanGranularStateLayout granular,VulkanLiquidThermodynamics.Layout granularThermal,
               VulkanCoherentStateLayout coherent,VulkanLiquidThermodynamics.Layout coherentThermal,
               VulkanThermalDeviceLayout device,VulkanLiquidThermodynamics.Layout deviceThermal,
               int fixedMasksWord,int bathTemperaturesWord,int facesWord,double cellSize,int activityCellWord,
               int plicPlanesWord,int plicCellsWord){
            this(liquid,liquidThermal,gas,gasThermal,gasGeometry,granular,granularThermal,coherent,coherentThermal,
                    device,deviceThermal,fixedMasksWord,bathTemperaturesWord,facesWord,cellSize,activityCellWord,plicPlanesWord,plicCellsWord,-1);
        }
        Inputs(VulkanLiquidStateLayout liquid,VulkanLiquidThermodynamics.Layout liquidThermal,
               VulkanGasStateLayout gas,VulkanGasThermodynamics.Layout gasThermal,VulkanGasGeometry.Layout gasGeometry,
               VulkanGranularStateLayout granular,VulkanLiquidThermodynamics.Layout granularThermal,
               VulkanCoherentStateLayout coherent,VulkanLiquidThermodynamics.Layout coherentThermal,
               VulkanThermalDeviceLayout device,VulkanLiquidThermodynamics.Layout deviceThermal,
               int fixedMasksWord,int bathTemperaturesWord,int facesWord,double cellSize,int activityCellWord){
            this(liquid,liquidThermal,gas,gasThermal,gasGeometry,granular,granularThermal,coherent,coherentThermal,
                    device,deviceThermal,fixedMasksWord,bathTemperaturesWord,facesWord,cellSize,activityCellWord,
                    gasGeometry==null?-1:gasGeometry.planesWordOffset(),gasGeometry==null?-1:gasGeometry.cellsWordOffset());
        }
        Inputs(VulkanLiquidStateLayout liquid,VulkanLiquidThermodynamics.Layout liquidThermal,
               VulkanGasStateLayout gas,VulkanGasThermodynamics.Layout gasThermal,VulkanGasGeometry.Layout gasGeometry,
               VulkanGranularStateLayout granular,VulkanLiquidThermodynamics.Layout granularThermal,
               VulkanCoherentStateLayout coherent,VulkanLiquidThermodynamics.Layout coherentThermal,
               VulkanThermalDeviceLayout device,VulkanLiquidThermodynamics.Layout deviceThermal,
               int fixedMasksWord,int bathTemperaturesWord,int facesWord,double cellSize){
            this(liquid,liquidThermal,gas,gasThermal,gasGeometry,granular,granularThermal,coherent,coherentThermal,
                    device,deviceThermal,fixedMasksWord,bathTemperaturesWord,facesWord,cellSize,-1);
        }
        Inputs(VulkanLiquidStateLayout liquid,VulkanLiquidThermodynamics.Layout liquidThermal,VulkanGasStateLayout gas,
               VulkanGasThermodynamics.Layout gasThermal,VulkanGasGeometry.Layout gasGeometry,VulkanGranularStateLayout granular,
               VulkanLiquidThermodynamics.Layout granularThermal,VulkanCoherentStateLayout coherent,VulkanLiquidThermodynamics.Layout coherentThermal,
               int fixedMasksWord,int bathTemperaturesWord,int facesWord,double cellSize){
            this(liquid,liquidThermal,gas,gasThermal,gasGeometry,granular,granularThermal,coherent,coherentThermal,null,null,fixedMasksWord,bathTemperaturesWord,facesWord,cellSize);
        }
        Inputs(VulkanLiquidStateLayout liquid,VulkanLiquidThermodynamics.Layout liquidThermal,VulkanGasStateLayout gas,
               VulkanGasThermodynamics.Layout gasThermal,VulkanGasGeometry.Layout gasGeometry,VulkanGranularStateLayout granular,
               VulkanLiquidThermodynamics.Layout granularThermal,int fixedMasksWord,int bathTemperaturesWord,int facesWord,double cellSize){
            this(liquid,liquidThermal,gas,gasThermal,gasGeometry,granular,granularThermal,null,null,null,null,fixedMasksWord,bathTemperaturesWord,facesWord,cellSize);
        }
        Inputs{if(liquid==null||liquidThermal==null||fixedMasksWord<0||!(Double.isFinite(cellSize)&&cellSize>0)
                ||(gas==null)!=(gasThermal==null)||(gas==null)!=(gasGeometry==null)||(granular==null)!=(granularThermal==null)||(coherent==null)!=(coherentThermal==null)||(device==null)!=(deviceThermal==null))
            throw new IllegalArgumentException("Invalid thermal inventory inputs");}
        int owners(){return Math.addExact((coherent==null?0:coherent.groupCapacity())+(device==null?0:device.deviceCapacity()),Math.addExact(liquid.stockCapacity(),Math.addExact(gas==null?0:gas.nodeCapacity(),granular==null?0:Math.addExact(granular.particleCapacity(),granular.depositCapacity()))));}
        int samples(){return Math.addExact(coherent==null?0:coherent.groupCapacity(),Math.addExact(liquid.stockCapacity(),Math.addExact(gas==null?0:liquid.cellCount(),granular==null?0:Math.addExact(8*granular.particleCapacity(),granular.depositCapacity()))));}
        int terms(){return Math.addExact((coherent==null?0:coherent.groupCapacity())+(device==null?0:device.deviceCapacity()),Math.addExact(liquid.stockCapacity(),Math.addExact(gas==null?0:gas.componentCapacity(),granular==null?0:Math.addExact(granular.particleCapacity(),granular.depositCapacity()))));}
        long endOffset(){return Math.max(device==null?0:Math.max(device.endOffset(),deviceThermal.endOffset()),Math.max(coherent==null?0:Math.max(coherent.endOffset(),coherentThermal.endOffset()),Math.max(liquidThermal.endOffset(),Math.max(gasThermal==null?0:Math.max(gasThermal.endOffset(),gasGeometry.endOffset()),granularThermal==null?0:Math.max(granular.endOffset(),granularThermal.endOffset())))));}
    }
    /** Indices refer to the conduction solver's common material table; conductivity remains SI W/(m K). */
    record Materials(int[] liquid,int[] gas,int[] granular,int[] coherent,int[] device,double[] conductivity){
        Materials(int[] liquid,int[] gas,int[] granular,double[] conductivity){this(liquid,gas,granular,new int[0],new int[0],conductivity);}
        Materials(int[] liquid,int[] gas,int[] granular,int[] coherent,double[] conductivity){this(liquid,gas,granular,coherent,new int[0],conductivity);}
        Materials{liquid=liquid.clone();gas=gas.clone();granular=granular.clone();coherent=coherent.clone();device=device.clone();conductivity=conductivity.clone();
            for(double k:conductivity)if(!(Double.isFinite(k)&&k>=0))throw new IllegalArgumentException("Invalid thermal conductivity");
            for(int[] map:new int[][]{liquid,gas,granular,coherent,device})for(int i:map)if(i<0||i>=conductivity.length)throw new IllegalArgumentException("Thermal material mapping out of range");}
    }
    enum Field{OWNER_FLAGS(4),OWNER_PREFIX(4),OWNER_KIND(4),OWNER_INDEX(4),OWNER_K(8),TERM_COUNTS(4),
        SAMPLE_FLAGS(4),SAMPLE_PREFIX(4),SAMPLE_OWNER(4),SAMPLE_CELL(4),SAMPLE_MASK(4),SAMPLE_FRACTION(8),
        CELL_COUNTS(4),CELL_OFFSETS(4),CELL_CURSORS(4),CELL_SAMPLES(4),COVERAGE(8),CONTACT_COUNTS(4),CONTACT_OFFSETS(4),
        LIQUID_MATERIAL(4),GAS_MATERIAL(4),GRANULAR_MATERIAL(4),CONDUCTIVITY(8),REQUIREMENTS(4),SCAN(4),COHERENT_MATERIAL(4),DEVICE_MATERIAL(4),PORT_COUNTS(4),PORT_OFFSETS(4),PORT_CURSORS(4),PORT_ROWS(4),MF_POW(4),BATH_COUNTS(4),BATH_OFFSETS(4),BATH_G(8),BATH_T(8),MF_DISPATCH(4),FACE_FACTORS(8);
        final int width;Field(int width){this.width=width;}}
    static final class Layout{
        final Inputs input;final Materials materials;final VulkanThermalConduction.Layout conduction;
        int wallGeometryWord=-1;
        final int owners,samples,cells;final VulkanLiquidStateLayout.Column header;final EnumMap<Field,VulkanLiquidStateLayout.Column> columns=new EnumMap<>(Field.class);final long endOffset;
        Layout(Inputs input,Materials materials,VulkanThermalConduction.Layout conduction,long base,long a){
            this.input=input;this.materials=materials;this.conduction=conduction;owners=input.owners();samples=input.samples();cells=input.liquid().cellCount();
            if(base<Math.max(input.endOffset(),conduction.endOffset())||a<8||(a&(a-1))!=0||materials.conductivity().length!=conduction.tables.materials())throw new IllegalArgumentException("Invalid thermal contacts layout");
            long[] cursor={base};header=allocate(cursor,160,4,a);
            for(Field f:Field.values()){
                int n=switch(f){                    case FACE_FACTORS->Math.multiplyExact(15,cells);case MF_DISPATCH->3;
                    case MF_POW,BATH_COUNTS->samples;case BATH_OFFSETS->samples+1;case BATH_G,BATH_T->conduction.baths;
                    case OWNER_FLAGS->owners;case OWNER_PREFIX->owners+1;
                    case OWNER_KIND,OWNER_INDEX,OWNER_K,TERM_COUNTS->conduction.owners;
                    case SAMPLE_FLAGS,SAMPLE_OWNER,SAMPLE_CELL,SAMPLE_MASK,SAMPLE_FRACTION,CELL_SAMPLES,CONTACT_COUNTS->samples;
                    case SAMPLE_PREFIX,CONTACT_OFFSETS->samples+1;case PORT_COUNTS,PORT_CURSORS,CELL_COUNTS,CELL_CURSORS->cells;case PORT_OFFSETS->cells+1;case PORT_ROWS->input.device()==null?0:input.device().portCapacity();case CELL_OFFSETS->cells+1;case COVERAGE->Math.addExact(8*cells,input.device()==null?0:24*input.device().portCapacity());
                    case LIQUID_MATERIAL->materials.liquid().length;case GAS_MATERIAL->materials.gas().length;case GRANULAR_MATERIAL->materials.granular().length;case COHERENT_MATERIAL->materials.coherent().length;case DEVICE_MATERIAL->materials.device().length;case CONDUCTIVITY->materials.conductivity().length;
                    case REQUIREMENTS->5;case SCAN->Math.toIntExact(VulkanPrefixScan.workspaceBytes(Math.max(Math.addExact(Math.multiplyExact(4,cells),input.device()==null?0:input.device().portCapacity()),Math.max(Math.addExact(samples,input.device()==null?0:input.device().portCapacity()),Math.max(owners,Math.max(cells,conduction.owners)))))/4);};
                columns.put(f,allocate(cursor,n,f.width,a));
            }endOffset=align(cursor[0],a);Math.toIntExact(endOffset/4);
        }
        VulkanLiquidStateLayout.Column column(Field f){return columns.get(f);}int word(Field f){return Math.toIntExact(column(f).offset()/4);}
        int headerWord(){return Math.toIntExact(header.offset()/4);}long endOffset(){return endOffset;}
        ByteBuffer headerBytes(){
            int[] d=new int[160];var l=input.liquid();var g=input.gas();var s=input.granular();
            d[Header.LIQUID_CAPACITY.word]=l.stockCapacity();d[Header.GAS_CAPACITY.word]=g==null?0:g.nodeCapacity();d[Header.PARTICLE_CAPACITY.word]=s==null?0:s.particleCapacity();d[Header.DEPOSIT_CAPACITY.word]=s==null?0:s.depositCapacity();
            d[Header.CELL_COUNT.word]=cells;d[Header.SIZE_X.word]=l.sizeX();d[Header.SIZE_Y.word]=l.sizeY();d[Header.SIZE_Z.word]=l.sizeZ();d[Header.SAMPLE_CAPACITY.word]=samples;d[Header.OWNER_CAPACITY.word]=owners;d[Header.FIXED_MASKS.word]=input.fixedMasksWord();d[Header.BATH_TEMPERATURES.word]=input.bathTemperaturesWord();d[Header.FACES.word]=input.facesWord();
            d[Header.LIQUID_COUNTS.word]=lw(l,VulkanLiquidStateLayout.Field.COUNTS);d[Header.GAS_COUNTS.word]=g==null?-1:gw(g,VulkanGasStateLayout.Field.COUNTS);d[Header.GRANULAR_COUNTS.word]=s==null?-1:s.word(VulkanGranularStateLayout.Field.COUNTS);
            d[Header.LIQUID_CELL_OFFSETS.word]=lw(l,VulkanLiquidStateLayout.Field.CELL_OFFSETS);d[Header.LIQUID_MATERIALS.word]=lw(l,VulkanLiquidStateLayout.Field.MATERIALS);d[Header.LIQUID_SUPPORT_MASKS.word]=lw(l,VulkanLiquidStateLayout.Field.SUPPORT_MASKS);
            d[Header.LIQUID_VOLUMES.word]=lw(l,VulkanLiquidStateLayout.Field.NUMERIC_VOLUMES);d[Header.LIQUID_MASS.word]=lw(l,VulkanLiquidStateLayout.Field.MASS_UNITS);d[Header.LIQUID_ENERGY.word]=lw(l,VulkanLiquidStateLayout.Field.ENERGY_BITS);d[Header.LIQUID_TEMPERATURE.word]=VulkanThermalContacts.columnWord(input.liquidThermal().temperatures());
            if(g!=null){d[Header.GAS_COMPONENT_OFFSETS.word]=gw(g,VulkanGasStateLayout.Field.ROW_COMPONENT_OFFSETS);d[Header.GAS_COMPONENT_HANDLES.word]=gw(g,VulkanGasStateLayout.Field.COMPONENT_HANDLES);d[Header.GAS_STANDARD_VOLUME.word]=gw(g,VulkanGasStateLayout.Field.STANDARD_VOLUME_NANOS);d[Header.GAS_MASS.word]=gw(g,VulkanGasStateLayout.Field.MASS_UNITS);d[Header.GAS_ENERGY.word]=gw(g,VulkanGasStateLayout.Field.INTERNAL_ENERGY);d[Header.GAS_STATES.word]=VulkanThermalContacts.columnWord(input.gasThermal().states());d[Header.GAS_CELL_NODES.word]=VulkanThermalContacts.columnWord(input.gasGeometry().cellNodes());d[Header.GAS_DRY_VOLUMES.word]=VulkanThermalContacts.columnWord(input.gasGeometry().dryVolumes());d[Header.GAS_DRY_MASKS.word]=VulkanThermalContacts.columnWord(input.gasGeometry().dryMasks());}
            if(s!=null){d[Header.GRANULAR_MATERIAL.word]=s.word(VulkanGranularStateLayout.Field.MATERIAL);d[Header.GRANULAR_VOLUME.word]=s.word(VulkanGranularStateLayout.Field.SOLID_VOLUME_SVQ);d[Header.GRANULAR_MASS.word]=s.word(VulkanGranularStateLayout.Field.MASS_UNITS);d[Header.GRANULAR_ENERGY.word]=s.word(VulkanGranularStateLayout.Field.INTERNAL_ENERGY);d[Header.GRANULAR_TEMPERATURE.word]=VulkanThermalContacts.columnWord(input.granularThermal().temperatures());d[Header.GRANULAR_SUPPORT_CELLS.word]=s.word(VulkanGranularStateLayout.Field.SUPPORT_CELLS);d[Header.GRANULAR_SUPPORT_QUOTAS.word]=s.word(VulkanGranularStateLayout.Field.SUPPORT_QUOTAS);d[Header.DEPOSIT_CELL.word]=s.word(VulkanGranularStateLayout.Field.DEPOSIT_CELL);}
            d[Header.LIQUID_MATERIAL_COUNT.word]=materials.liquid().length;d[Header.GAS_MATERIAL_COUNT.word]=materials.gas().length;d[Header.GRANULAR_MATERIAL_COUNT.word]=materials.granular().length;
            d[Header.COHERENT_MATERIAL_COUNT.word]=materials.coherent().length;var c=input.coherent();d[Header.COHERENT_CAPACITY.word]=c==null?0:c.groupCapacity();d[Header.COHERENT_COUNTS.word]=c==null?-1:c.word(VulkanCoherentStateLayout.Field.COUNTS);
            if(c!=null){d[Header.COHERENT_CELL.word]=c.word(VulkanCoherentStateLayout.Field.CELL);d[Header.COHERENT_MATERIAL.word]=c.word(VulkanCoherentStateLayout.Field.MATERIAL);d[Header.COHERENT_VOLUME.word]=c.word(VulkanCoherentStateLayout.Field.OCCUPIED_VOLUME_SVQ);d[Header.COHERENT_MASS.word]=c.word(VulkanCoherentStateLayout.Field.MASS_UNITS);d[Header.COHERENT_ENERGY.word]=c.word(VulkanCoherentStateLayout.Field.INTERNAL_ENERGY);d[Header.COHERENT_TEMPERATURE.word]=VulkanThermalContacts.columnWord(input.coherentThermal().temperatures());}
            var v=input.device();d[Header.DEVICE_MATERIAL_COUNT.word]=materials.device().length;d[Header.DEVICE_CAPACITY.word]=v==null?0:v.deviceCapacity();d[Header.DEVICE_COUNTS.word]=v==null?-1:v.word(VulkanThermalDeviceLayout.Field.COUNTS);
            if(v!=null){var fields=new VulkanThermalDeviceLayout.Field[]{VulkanThermalDeviceLayout.Field.INTERNAL_ENERGY,VulkanThermalDeviceLayout.Field.MASS_UNITS,VulkanThermalDeviceLayout.Field.MATERIAL};
                for(int i=0;i<fields.length;i++)d[134+i]=v.word(fields[i]);d[Header.DEVICE_TEMPERATURE.word]=columnWord(input.deviceThermal().temperatures());
                var more=new VulkanThermalDeviceLayout.Field[]{VulkanThermalDeviceLayout.Field.GENERATION,VulkanThermalDeviceLayout.Field.REQUEST_GENERATION,VulkanThermalDeviceLayout.Field.DEVICE_ENERGY_CHANGE,VulkanThermalDeviceLayout.Field.PORT_DEVICE,VulkanThermalDeviceLayout.Field.PORT_CELL,VulkanThermalDeviceLayout.Field.PORT_DIRECTION,VulkanThermalDeviceLayout.Field.PORT_WALL_THICKNESS,VulkanThermalDeviceLayout.Field.PORT_WALL_K,VulkanThermalDeviceLayout.Field.PORT_FILM_THICKNESS};
                for(int i=0;i<more.length;i++)d[138+i]=v.word(more[i]);d[Header.PORT_CAPACITY.word]=v.portCapacity();}
            d[Header.PLIC_PLANES.word]=input.plicPlanesWord();d[Header.PLIC_CELLS.word]=input.plicCellsWord();
            d[Header.SCREEN_FACES.word]=input.screenFacesWord();
            d[Header.CONDUCTION_HEADER.word]=conduction.headerWordOffset();
            d[Header.ACTIVITY_CELLS.word]=input.activityCellWord();
            d[Header.LIQUID_SPECIES_OFFSETS.word]=lw(l,VulkanLiquidStateLayout.Field.SPECIES_OFFSETS);
            d[Header.LIQUID_SPECIES_HANDLES.word]=lw(l,VulkanLiquidStateLayout.Field.SPECIES_HANDLES);
            d[Header.LIQUID_SPECIES_AMOUNTS.word]=lw(l,VulkanLiquidStateLayout.Field.SPECIES_AMOUNTS);
            d[Header.AQUEOUS_MATERIAL.word]=input.liquidThermal().tables().aqueousMaterial();
            d[Header.ETHANOL_SPECIES.word]=input.liquidThermal().tables().ethanolSpecies();
            d[Header.PLIC_WALL_GEOMETRY.word]=wallGeometryWord;
            d[Header.LIQUID_CALORIC.word]=Math.toIntExact(input.liquidThermal().header().offset()/4);
            for(Field f:Field.values())d[WORK_FIELDS+f.ordinal()]=word(f);
            var fields=new VulkanThermalConduction.Field[]{VulkanThermalConduction.Field.COUNTS,VulkanThermalConduction.Field.OWNER_ENERGY,VulkanThermalConduction.Field.OWNER_INITIAL_T,VulkanThermalConduction.Field.TERM_OFFSETS,VulkanThermalConduction.Field.TERM_MATERIAL,VulkanThermalConduction.Field.TERM_MASS,VulkanThermalConduction.Field.CONTACT_A,VulkanThermalConduction.Field.CONTACT_B,VulkanThermalConduction.Field.CONTACT_G,VulkanThermalConduction.Field.CONTACT_BATH_T};
            for(int i=0;i<fields.length;i++)d[Header.CONDUCTION_COUNTS.word+i]=conduction.word(fields[i]);d[Header.CONDUCTION_OWNER_CAPACITY.word]=conduction.owners;d[Header.CONDUCTION_TERM_CAPACITY.word]=conduction.terms;d[Header.CONDUCTION_CONTACT_CAPACITY.word]=conduction.contacts;
            var out=ByteBuffer.allocateDirect(640).order(ByteOrder.LITTLE_ENDIAN);for(int i=0;i<d.length;i++)out.putInt(4*i,d[i]);out.putDouble(4 * Header.CELL_SIZE.word,input.cellSize());return out;
        }
        void initializeHeaderAndTables(ByteBuffer out){out.duplicate().position(Math.toIntExact(header.offset())).put(headerBytes());for(var chunk:tableChunks())out.duplicate().position(Math.toIntExact(chunk.offset())).put(chunk.bytes().duplicate());}
        List<VulkanGasThermodynamics.TableChunk> tableChunks(){
            var out=new ArrayList<VulkanGasThermodynamics.TableChunk>();ints(out,Field.LIQUID_MATERIAL,materials.liquid());ints(out,Field.GAS_MATERIAL,materials.gas());ints(out,Field.GRANULAR_MATERIAL,materials.granular());ints(out,Field.COHERENT_MATERIAL,materials.coherent());ints(out,Field.DEVICE_MATERIAL,materials.device());
            var b=ByteBuffer.allocateDirect(8*materials.conductivity().length).order(ByteOrder.LITTLE_ENDIAN);for(double x:materials.conductivity())b.putDouble(x);b.flip();if(b.hasRemaining())out.add(new VulkanGasThermodynamics.TableChunk(column(Field.CONDUCTIVITY).offset(),b));return List.copyOf(out);
        }
        private void ints(List<VulkanGasThermodynamics.TableChunk> out,Field f,int[] values){if(values.length==0)return;var b=ByteBuffer.allocateDirect(4*values.length).order(ByteOrder.LITTLE_ENDIAN);for(int x:values)b.putInt(x);b.flip();out.add(new VulkanGasThermodynamics.TableChunk(column(f).offset(),b));}
    }
    static Layout layout(Inputs input,Materials materials,VulkanThermalConduction.Layout conduction,long base,long alignment){return new Layout(input,materials,conduction,base,alignment);}
    private final FlipKernelBindings bindings;private final VulkanPrefixScan scan;private FlipKernelBindings.BufferRange arena;
    VulkanThermalContacts(VulkanDeviceContext context){bindings=new FlipKernelBindings(context,2,shader(),32);try{scan=new VulkanPrefixScan(context);}catch(RuntimeException|LinkageError failure){bindings.close();throw failure;}}
    void rebindRanges(FlipKernelBindings.BufferRange arena,FlipKernelBindings.BufferRange errors){this.arena=arena;bindings.rebindRanges(List.of(arena,errors));scan.rebindRanges(arena,arena,arena,errors);}
    void recordPrepare(VkCommandBuffer command,Layout l){recordSource(command,l,false);recordGraphEdges(command,l);stage(command,l,13,1);}
    private void recordSource(VkCommandBuffer command,Layout l,boolean matrixFree){
        stage(command,l,0,Math.max(l.owners,Math.max(l.samples,Math.max(l.cells,l.conduction.owners))),matrixFree?2:1,0);
        stage(command,l,1,l.owners);prefix(command,l,l.owners,Field.OWNER_FLAGS,Field.OWNER_PREFIX);
        stage(command,l,2,l.owners);scan.record(command,l.conduction.owners,l.word(Field.TERM_COUNTS),l.conduction.word(VulkanThermalConduction.Field.TERM_OFFSETS),l.word(Field.SCAN));
        stage(command,l,3,l.owners);stage(command,l,4,l.samples);prefix(command,l,l.samples,Field.SAMPLE_FLAGS,Field.SAMPLE_PREFIX);
        stage(command,l,5,l.samples);prefix(command,l,l.cells,Field.CELL_COUNTS,Field.CELL_OFFSETS);stage(command,l,6,l.samples);
        if(l.input.device()!=null){stage(command,l,11,l.input.device().portCapacity());prefix(command,l,l.cells,Field.PORT_COUNTS,Field.PORT_OFFSETS);stage(command,l,12,l.input.device().portCapacity());}
        stage(command,l,7,l.cells);
        stage(command,l,25,Math.max(l.cells,l.input.device()==null?0:l.input.device().portCapacity()));
    }
    void recordSpatial(VkCommandBuffer command,Layout l){
        recordSource(command,l,true);
        stage(command,l,16,l.samples);
        scan.record(command,l.samples,l.word(Field.BATH_COUNTS),l.word(Field.BATH_OFFSETS),l.word(Field.SCAN),l.word(Field.SAMPLE_PREFIX)+l.samples);
        stage(command,l,17,Math.max(1,l.samples));
    }
    void recordContactConductances(VkCommandBuffer command,Layout l,double duration){
        stage(command,l,24,1,-1,duration);stage(command,l,18,l.samples,0,duration);
    }
    void recordLimitedFlux(VkCommandBuffer command,Layout l,double duration){
        stage(command,l,18,l.samples,1,duration);
    }
    void recordGraphEdges(VkCommandBuffer command,Layout l){
        stage(command,l,21,l.conduction.owners);stage(command,l,8,l.samples);
        prefix(command,l,l.samples,Field.CONTACT_COUNTS,Field.CONTACT_OFFSETS);stage(command,l,9,Math.max(1,l.samples));stage(command,l,22,1);
    }
    /** The solver has already combined every spatial sample at its unique owner. */
    void recordPublish(VkCommandBuffer command,Layout l){stage(command,l,10,l.conduction.owners);}
    private void prefix(VkCommandBuffer command,Layout l,int n,Field input,Field output){scan.record(command,n,l.word(input),l.word(output),l.word(Field.SCAN));}
    private void stage(VkCommandBuffer command,Layout l,int stage,int count){stage(command,l,stage,count,0,0);}
    private void stage(VkCommandBuffer command,Layout l,int stage,int count,int step,double dt){if(count==0)return;try(var stack=stackPush()){
        bindings.bind(command,stack);var push=stack.malloc(32).putInt(l.headerWord()).putInt(stage).putInt(count).putInt(step).putDouble(dt).putDouble(0).flip();
        vkCmdPushConstants(command,bindings.pipelineLayout(),VK_SHADER_STAGE_COMPUTE_BIT,0,push);
        int groups=(count+127)/128;
        if(stage==18||stage==43){
            barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT,VK_ACCESS_INDIRECT_COMMAND_READ_BIT,stack);
            vkCmdDispatchIndirect(command,arena.buffer().handle(),arena.offset()+l.column(Field.MF_DISPATCH).offset());
        }else vkCmdDispatch(command,Math.min(groups,65535),(groups+65534)/65535,1);
        barrier(command,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT,stack);
    }}
    private static int lw(VulkanLiquidStateLayout l,VulkanLiquidStateLayout.Field f){return columnWord(l.column(f));}
    private static int gw(VulkanGasStateLayout l,VulkanGasStateLayout.Field f){return Math.toIntExact(l.column(f).offset()/4);}
    private static int columnWord(VulkanLiquidStateLayout.Column c){return Math.toIntExact(c.offset()/4);}
    private static long align(long n,long a){return Math.addExact(n,a-1)&-a;}
    private static VulkanLiquidStateLayout.Column allocate(long[] cursor,int n,int width,long a){var c=new VulkanLiquidStateLayout.Column(align(cursor[0],a),n,width);cursor[0]=Math.addExact(c.offset(),c.bindingBytes());return c;}
    private static String shader(){try(var in=VulkanThermalContacts.class.getResourceAsStream("/assets/mmm_gpu_probe/shaders/thermal-contacts.comp")){if(in==null)throw new IllegalStateException("Thermal contact shader missing");return new String(in.readAllBytes(),StandardCharsets.UTF_8).replace("// HOST_LAYOUT", shaderLayout()).replace("// SERIES_CONDUCTANCE_LIBRARY", VulkanLiquidPhaseRows.resource("series-conductance.glsl")).replace("// PLIC_FACE_LIBRARY", VulkanLiquidPhaseRows.resource("plic-face.glsl"));}catch(IOException e){throw new IllegalStateException("Thermal contact shader unavailable",e);}}
    @Override public void close(){try{scan.close();}finally{bindings.close();}}
}
