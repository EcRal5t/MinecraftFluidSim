package mmm.gpuprobe;

import java.util.EnumMap;
import java.util.List;

/** Resident solid inventory and motion. Active rows precede reserved deposit rows, then two
 * per-material audit rows (external source, escaped sink). Integer mass, SVQ and species are
 * authoritative; POSITION/VELOCITY and raw internal energy use binary64. SUPPORT_QUOTAS sums
 * exactly to each active row's SVQ and remains unchanged while its position is unchanged.
 * COUNTS = active parcels, deposit groups, species entries. No live count is inferred from capacity. */
final class VulkanGranularStateLayout {
    enum Field {
        COUNTS(4), POSITION(8), VELOCITY(8), MATERIAL(4), SOLID_VOLUME_SVQ(8), MASS_UNITS(8),
        MIXTURE_PRESENT(4), MASS_RESIDUAL_UNITS(8), SPECIES_OFFSETS(4), SPECIES_HANDLES(4), SPECIES_AMOUNTS(8),
        INTERNAL_ENERGY(8), CONTACT_AGE(4), FLAGS(4), SUPPORT_CELLS(4), SUPPORT_QUOTAS(8),
        DEPOSIT_CELL(4), DEPOSIT_POROSITY(4);
        final int width; Field(int width){this.width=width;}
    }
    record Definitions(String registryFingerprint,String chemicalRegistryFingerprint,
                       List<String> materialIds,List<String> speciesIds){
        Definitions{materialIds=List.copyOf(materialIds);speciesIds=List.copyOf(speciesIds);}
    }
    private final int sizeX,sizeY,sizeZ,particles,deposits,species,rows;
    private final Definitions definitions;
    private final long baseOffset,endOffset,alignment;
    private final EnumMap<Field,VulkanLiquidStateLayout.Column> columns=new EnumMap<>(Field.class);
    VulkanGranularStateLayout(int sizeX,int sizeY,int sizeZ,int particleCapacity,int depositCapacity,
                             int speciesCapacity,Definitions definitions,long baseOffset,long alignment){
        if(sizeX<=0||sizeY<=0||sizeZ<=0||particleCapacity<0||depositCapacity<0||speciesCapacity<0||definitions==null
                ||baseOffset<0||alignment<8||(alignment&(alignment-1))!=0)
            throw new IllegalArgumentException("Invalid resident granular layout");
        this.sizeX=sizeX;this.sizeY=sizeY;this.sizeZ=sizeZ;particles=particleCapacity;deposits=depositCapacity;
        species=speciesCapacity;this.definitions=definitions;this.alignment=alignment;
        rows=Math.addExact(Math.addExact(particles,deposits),Math.multiplyExact(2,definitions.materialIds().size()));
        this.baseOffset=align(baseOffset);long cursor=this.baseOffset;
        for(Field field:Field.values()){
            int count=switch(field){
                case COUNTS->3;
                case POSITION,VELOCITY->Math.multiplyExact(3,particles);
                case CONTACT_AGE,FLAGS->particles;
                case SUPPORT_CELLS,SUPPORT_QUOTAS->Math.multiplyExact(8,particles);
                case SPECIES_OFFSETS->rows+1;
                case SPECIES_HANDLES,SPECIES_AMOUNTS->species;
                case DEPOSIT_CELL,DEPOSIT_POROSITY->deposits;
                default->rows;
            };
            var column=new VulkanLiquidStateLayout.Column(align(cursor),count,field.width);
            columns.put(field,column);cursor=Math.addExact(column.offset(),column.bindingBytes());
        }
        endOffset=align(cursor);Math.toIntExact(endOffset/4);
    }
    VulkanLiquidStateLayout.Column column(Field field){return columns.get(field);}
    int word(Field field){return Math.toIntExact(column(field).offset()/4);}
    int sizeX(){return sizeX;}int sizeY(){return sizeY;}int sizeZ(){return sizeZ;}
    int cells(){return Math.multiplyExact(Math.multiplyExact(sizeX,sizeY),sizeZ);}
    int particleCapacity(){return particles;}int depositCapacity(){return deposits;}int speciesCapacity(){return species;}
    int rowCapacity(){return rows;}int depositRow(int ordinal){return particles+ordinal;}
    int auditRow(boolean escaped,int material){return particles+deposits+(escaped?definitions.materialIds().size():0)+material;}
    Definitions definitions(){return definitions;}long alignment(){return alignment;}
    long baseOffset(){return baseOffset;}long endOffset(){return endOffset;}long bytes(){return endOffset-baseOffset;}
    private long align(long value){return Math.addExact(value,alignment-1)&-alignment;}
}
