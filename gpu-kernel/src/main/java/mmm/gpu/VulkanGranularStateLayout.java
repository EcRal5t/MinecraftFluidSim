package mmm.gpu;

import java.util.EnumMap;
import java.util.List;
import java.util.ArrayList;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import static org.lwjgl.vulkan.VK10.vkCmdCopyBuffer;
import static org.lwjgl.vulkan.VK10.*;

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
        DEPOSIT_CELL(4), DEPOSIT_POROSITY(4), LOGICAL_STEP(8), STABLE_AGE(4);
        final int width; Field(int width){this.width=width;}
    }
    record Definitions(String registryFingerprint,String chemicalRegistryFingerprint,
                       List<String> materialIds,List<String> speciesIds){
        Definitions{materialIds=List.copyOf(materialIds);speciesIds=List.copyOf(speciesIds);}
    }
    record Row(int material,long solidVolumeSvq,long massUnits,boolean mixture,long massResidualUnits,
               int[] species,long[] amounts,double internalEnergy){
        Row{species=species.clone();amounts=amounts.clone();if(species.length!=amounts.length)throw new IllegalArgumentException("Granular species columns differ");}
    }
    record Particle(Row inventory,double x,double y,double z,double vx,double vy,double vz,int contactAge,int flags,
                    int[] supportCells,long[] supportQuotas){
        Particle{supportCells=supportCells.clone();supportQuotas=supportQuotas.clone();
            if(supportCells.length!=8||supportQuotas.length!=8)throw new IllegalArgumentException("Granular support needs eight slots");}
    }
    record Deposit(Row inventory,int cell,float porosity){}
    record Configuration(State initial,mmm.granular.GranularRegistry registry,VulkanLiquidThermodynamics.Tables thermal,
                         double[] materialReferences,double[] speciesReferences,int speciesStride,int contactCapacity){
        Configuration{materialReferences=materialReferences.clone();speciesReferences=speciesReferences.clone();
            if(initial==null||registry==null||thermal==null||speciesStride<1||contactCapacity<0
                    ||materialReferences.length!=initial.definitions().materialIds().size()||speciesReferences.length!=materialReferences.length*speciesStride)
                throw new IllegalArgumentException("Invalid resident granular configuration");}
        static Configuration fromRegistries(State state,mmm.granular.GranularRegistry registry,mmm.chemistry.ChemicalRegistry chemistry,
                                             mmm.thermal.ThermalRegistry thermal,int contactCapacity){
            int materials=state.definitions().materialIds().size(),stride=Math.max(1,state.definitions().speciesIds().size());
            double[] references=new double[materials],species=new double[materials*stride];java.util.Arrays.fill(species,Double.NaN);
            for(int material=0;material<materials;material++){
                int host=thermal.materialHandle(mmm.thermal.ThermalMaterialDefinition.CarrierKind.GRANULAR,state.definitions().materialIds().get(material));
                references[material]=thermal.material(host).referenceSpecificEnergyJPerKg();
                for(int k=0;k<state.definitions().speciesIds().size();k++){String id=state.definitions().speciesIds().get(k);
                    if(thermal.hasSpeciesOffset(host,id))species[material*stride+k]=thermal.speciesOffset(host,id).referenceInternalEnergyJPerMol().divide(mmm.chemistry.ChemicalLimits.QUANTA_PER_MOL).toDouble();}
            }
            if(!state.definitions().registryFingerprint().equals(registry.fingerprint())||!state.definitions().chemicalRegistryFingerprint().isEmpty()
                    &&!state.definitions().chemicalRegistryFingerprint().equals(chemistry.fingerprint()))
                throw new IllegalArgumentException("Granular configuration registry fingerprints differ");
            return new Configuration(state,registry,VulkanLiquidThermodynamics.tables(state.definitions(),thermal),references,species,stride,contactCapacity);
        }
        int speciesCapacity(){int result=0;for(var p:initial.particles())result=Math.addExact(result,p.inventory().species().length);
            for(var d:initial.deposits())result=Math.addExact(result,d.inventory().species().length);
            for(var row:initial.externalSource())result=Math.addExact(result,row.species().length);for(var row:initial.escapedSink())result=Math.addExact(result,row.species().length);return result;}
    }
    /** Explicit lossless checkpoint; parcel positions, velocities, U and selected integer support retain their stored bits. */
    record Dynamics(long logicalStep,int[] stableAge){
        Dynamics{stableAge=stableAge.clone();if(logicalStep<0)throw new IllegalArgumentException("Negative granular logical step");}
        @Override public int[] stableAge(){return stableAge.clone();}
    }
    record State(Definitions definitions,List<Particle> particles,List<Deposit> deposits,List<Row> externalSource,List<Row> escapedSink,Dynamics dynamics){
        State(Definitions definitions,List<Particle> particles,List<Deposit> deposits,List<Row> externalSource,List<Row> escapedSink){this(definitions,particles,deposits,externalSource,escapedSink,new Dynamics(0,new int[0]));}
        State{particles=List.copyOf(particles);deposits=List.copyOf(deposits);externalSource=List.copyOf(externalSource);escapedSink=List.copyOf(escapedSink);}
        State withDynamics(mmm.granular.GranularDepositDynamics.State scheduler){return new State(definitions,particles,deposits,externalSource,escapedSink,new Dynamics(scheduler.logicalStep(),scheduler.stableAge()));}
        mmm.granular.GranularDepositDynamics.State schedulerState(int sizeX,int sizeY,int sizeZ){
            int[] ages=dynamics.stableAge().length==0?new int[sizeX*sizeZ]:dynamics.stableAge();
            if(ages.length!=sizeX*sizeZ)throw new IllegalArgumentException("Granular scheduler dimensions differ");
            boolean[] occupied=new boolean[ages.length];for(var d:deposits)occupied[d.cell()%sizeX+d.cell()/(sizeX*sizeY)*sizeX]=true;
            int[] queue=java.util.stream.IntStream.range(0,ages.length).filter(i->occupied[i]&&ages[i]<mmm.granular.GranularDepositDynamics.SLEEP_LOGICAL_STEPS).toArray();
            return new mmm.granular.GranularDepositDynamics.State(dynamics.logicalStep(),ages,queue);
        }
        double[] activeEnergy(){return particles.stream().mapToDouble(p->p.inventory().internalEnergy()).toArray();}
        /** Matches the canonical deposit order of toSnapshot. */
        double[] depositEnergy(){return deposits.stream().sorted((a,b)->{
            int order=Integer.compare(a.cell(),b.cell());if(order==0)order=Integer.compare(a.inventory().material(),b.inventory().material());
            if(order==0){var ca=composition(a.inventory());var cb=composition(b.inventory());order=ca==null?(cb==null?0:-1):cb==null?1:compareComposition(ca,cb);}
            return order;
        }).mapToDouble(d->d.inventory().internalEnergy()).toArray();}
        private static int compareComposition(mmm.granular.GranularStateSnapshot.StableComposition a,mmm.granular.GranularStateSnapshot.StableComposition b){
            int compare=Integer.compare(a.components().size(),b.components().size());
            for(int i=0;compare==0&&i<a.components().size();i++){
                compare=a.components().get(i).speciesId().compareTo(b.components().get(i).speciesId());
                if(compare==0)compare=Long.compare(a.components().get(i).amountQuanta(),b.components().get(i).amountQuanta());
            }
            return compare!=0?compare:Long.compareUnsigned(a.massResidualUnits(),b.massResidualUnits());
        }
        /** CPU handoff uses its float parcel geometry; the lossless resident checkpoint above retains binary64. */
        mmm.granular.GranularStateSnapshot toSnapshot(int sizeX,int sizeY,int sizeZ,double cellSize){
            if(cellSize!=.5)throw new IllegalArgumentException("CPU granular snapshots use 0.5 m cells");
            var order=(java.util.Comparator<mmm.granular.GranularStateSnapshot.StableComposition>)State::compareComposition;
            var unique=new java.util.TreeSet<mmm.granular.GranularStateSnapshot.StableComposition>(order);
            var activeComposition=new ArrayList<mmm.granular.GranularStateSnapshot.StableComposition>();
            var bedComposition=new ArrayList<mmm.granular.GranularStateSnapshot.StableComposition>();
            for(var p:particles){var c=composition(p.inventory());activeComposition.add(c);if(c!=null)unique.add(c);}
            for(var d:deposits){var c=composition(d.inventory());bedComposition.add(c);if(c!=null)unique.add(c);}
            var compositions=List.copyOf(unique);var active=new ArrayList<mmm.granular.GranularStateSnapshot.ActiveParcel>();
            var beds=new ArrayList<mmm.granular.GranularStateSnapshot.DepositGroup>();var totals=new java.util.TreeMap<String,Long>();
            long volume=0,mass=0;var kernel=new mmm.granular.GranularKernel(sizeX,sizeY,sizeZ,cellSize);
            for(int i=0;i<particles.size();i++){
                var p=particles.get(i);var row=p.inventory();float x=(float)p.x(),y=(float)p.y(),z=(float)p.z();
                kernel.capture(x,y,z,row.solidVolumeSvq());int mask=0;
                for(int k=0;k<kernel.size();k++){
                    long selected=-1;for(int q=0;q<8;q++)if(p.supportCells()[q]==kernel.cell(k))selected=p.supportQuotas()[q];
                    long delta=selected-kernel.floor(k);
                    if(delta<0||delta>1)throw new IllegalStateException("Device granular support cannot be represented at parcel "+i+" cell "+kernel.cell(k)+": device="+selected+", hostFloor="+kernel.floor(k));
                    if(delta==1)mask|=1<<k;
                }
                for(int q=0;q<8;q++)if(p.supportQuotas()[q]>0){boolean found=false;
                    for(int k=0;k<kernel.size();k++)if(p.supportCells()[q]==kernel.cell(k)){found=true;break;}
                    if(!found)throw new IllegalStateException("Device granular support lies outside host support at parcel "+i+" cell "+p.supportCells()[q]);}
                try{kernel.requireMask(mask);}catch(IllegalArgumentException failure){throw new IllegalStateException("Device granular support is invalid at parcel "+i,failure);}
                active.add(new mmm.granular.GranularStateSnapshot.ActiveParcel(x,y,z,(float)p.vx(),(float)p.vy(),(float)p.vz(),
                        row.solidVolumeSvq(),row.massUnits(),row.material(),activeComposition.get(i)==null?0:compositions.indexOf(activeComposition.get(i))+1,
                        p.contactAge(),(byte)p.flags(),mask));
                volume=Math.addExact(volume,row.solidVolumeSvq());mass=Math.addExact(mass,row.massUnits());speciesTotals(totals,row);
            }
            for(int i=0;i<deposits.size();i++){
                var d=deposits.get(i);var row=d.inventory();
                beds.add(new mmm.granular.GranularStateSnapshot.DepositGroup(d.cell(),Math.round(d.porosity()*mmm.granular.GranularLimits.POROSITY_SCALE),
                        row.material(),bedComposition.get(i)==null?0:compositions.indexOf(bedComposition.get(i))+1,row.solidVolumeSvq(),row.massUnits()));
                volume=Math.addExact(volume,row.solidVolumeSvq());mass=Math.addExact(mass,row.massUnits());speciesTotals(totals,row);
            }
            beds.sort(java.util.Comparator.comparingInt(mmm.granular.GranularStateSnapshot.DepositGroup::cell)
                    .thenComparingInt(mmm.granular.GranularStateSnapshot.DepositGroup::materialPaletteIndex)
                    .thenComparingInt(mmm.granular.GranularStateSnapshot.DepositGroup::solidCompositionIndex));
            return new mmm.granular.GranularStateSnapshot(sizeX,sizeY,sizeZ,definitions.registryFingerprint(),definitions.chemicalRegistryFingerprint(),
                    definitions.materialIds(),compositions,totals,active,beds,volume,mass,toAudit(externalSource),toAudit(escapedSink));
        }
        private mmm.granular.GranularStateSnapshot.StableComposition composition(Row row){
            if(!row.mixture())return null;var components=new ArrayList<mmm.granular.GranularStateSnapshot.CompositionComponent>();
            for(int k=0;k<row.species().length;k++)if(row.amounts()[k]>0)components.add(new mmm.granular.GranularStateSnapshot.CompositionComponent(definitions.speciesIds().get(row.species()[k]),row.amounts()[k]));
            components.sort(java.util.Comparator.comparing(mmm.granular.GranularStateSnapshot.CompositionComponent::speciesId));
            return new mmm.granular.GranularStateSnapshot.StableComposition(components,row.massResidualUnits());
        }
        private void speciesTotals(java.util.Map<String,Long> totals,Row row){for(int i=0;i<row.species().length;i++)if(row.amounts()[i]>0)totals.merge(definitions.speciesIds().get(row.species()[i]),row.amounts()[i],Math::addExact);}
        private mmm.granular.GranularAuditLedger toAudit(List<Row> rows){var entries=new ArrayList<mmm.granular.GranularAuditLedger.Entry>();
            for(var row:rows){var c=composition(row);entries.add(new mmm.granular.GranularAuditLedger.Entry(definitions.materialIds().get(row.material()),row.solidVolumeSvq(),row.massUnits(),row.massResidualUnits(),
                    c==null?List.of():c.components().stream().map(v->new mmm.granular.GranularAuditLedger.SpeciesAmount(v.speciesId(),v.amountQuanta())).toList()));}
            return new mmm.granular.GranularAuditLedger(entries);
        }
        static State fromSnapshot(mmm.granular.GranularStateSnapshot snapshot,List<String> speciesIds,double[] activeEnergy,double[] depositEnergy,double cellSize){
            if(activeEnergy.length!=snapshot.activeParcels().size()||depositEnergy.length!=snapshot.depositGroups().size())throw new IllegalArgumentException("Granular thermal rows differ from inventory");
            var definitions=new Definitions(snapshot.registryFingerprint(),snapshot.chemicalRegistryFingerprint(),snapshot.materialPalette(),speciesIds);
            var ps=new ArrayList<Particle>();var ds=new ArrayList<Deposit>();
            var kernel=new mmm.granular.GranularKernel(snapshot.sizeX(),snapshot.sizeY(),snapshot.sizeZ(),cellSize);
            for(int i=0;i<activeEnergy.length;i++){var p=snapshot.activeParcels().get(i);kernel.capture(p.x(),p.y(),p.z(),p.solidVolumeSvq());
                int[] cells=new int[8];long[] quotas=new long[8];java.util.Arrays.fill(cells,-1);
                for(int k=0;k<kernel.size();k++){cells[k]=kernel.cell(k);quotas[k]=kernel.floor(k)+((p.ceilMask()>>>k)&1);}
                var row=fromComposition(definitions,p.materialPaletteIndex(),p.solidVolumeSvq(),p.massUnits(),p.solidCompositionIndex()==0?null:snapshot.solidCompositions().get(p.solidCompositionIndex()-1),activeEnergy[i]);
                ps.add(new Particle(row,p.x(),p.y(),p.z(),p.velocityX(),p.velocityY(),p.velocityZ(),p.contactAge(),p.flags(),cells,quotas));}
            for(int i=0;i<depositEnergy.length;i++){var d=snapshot.depositGroups().get(i);
                var row=fromComposition(definitions,d.materialPaletteIndex(),d.solidVolumeSvq(),d.massUnits(),d.solidCompositionIndex()==0?null:snapshot.solidCompositions().get(d.solidCompositionIndex()-1),depositEnergy[i]);
                ds.add(new Deposit(row,d.cell(),(float)d.porosityQ()/mmm.granular.GranularLimits.POROSITY_SCALE));}
            return new State(definitions,ps,ds,audit(definitions,snapshot.externalSource()),audit(definitions,snapshot.escapedSink()));
        }
        private static Row fromComposition(Definitions definitions,int material,long volume,long mass,mmm.granular.GranularStateSnapshot.StableComposition composition,double energy){
            if(material<0||material>=definitions.materialIds().size())throw new IllegalArgumentException("Granular material is absent from definitions");
            int size=composition==null?0:composition.components().size();int[] ids=new int[size];long[] amounts=new long[size];
            for(int k=0;k<size;k++){var value=composition.components().get(k);ids[k]=definitions.speciesIds().indexOf(value.speciesId());amounts[k]=value.amountQuanta();
                if(ids[k]<0)throw new IllegalArgumentException("Granular chemical species is absent: "+value.speciesId());}
            return new Row(material,volume,mass,composition!=null,composition==null?0:composition.massResidualUnits(),ids,amounts,energy);
        }
        private static List<Row> audit(Definitions definitions,mmm.granular.GranularAuditLedger ledger){
            var result=new ArrayList<Row>();for(var e:ledger.entries()){
                var composition=e.reactive()?new mmm.granular.GranularStateSnapshot.StableComposition(e.composition().stream()
                        .map(v->new mmm.granular.GranularStateSnapshot.CompositionComponent(v.speciesId(),v.amountQuanta())).toList(),e.massResidualUnits()):null;
                result.add(fromComposition(definitions,definitions.materialIds().indexOf(e.materialId()),e.solidVolumeSvq(),e.massUnits(),composition,0));}
            return result;
        }
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
                case LOGICAL_STEP->1;
                case STABLE_AGE->Math.multiplyExact(sizeX,sizeZ);
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
    void importState(ByteBuffer destination,State state){
        if(!definitions.equals(state.definitions())||state.particles().size()>particles||state.deposits().size()>deposits)
            throw new IllegalArgumentException("Granular input exceeds layout or changes definitions");
        var b=view(destination);int[] speciesCursor={0};
        if(state.dynamics().stableAge().length!=0&&state.dynamics().stableAge().length!=sizeX*sizeZ)throw new IllegalArgumentException("Granular dynamics dimensions differ");
        putLong(b,Field.LOGICAL_STEP,0,state.dynamics().logicalStep());
        var ages=state.dynamics().stableAge();for(int i=0;i<ages.length;i++)putInt(b,Field.STABLE_AGE,i,ages[i]);
        putInt(b,Field.COUNTS,0,state.particles().size());putInt(b,Field.COUNTS,1,state.deposits().size());
        for(int row=0;row<rows;row++){
            Row inventory=null;
            if(row<state.particles().size())inventory=state.particles().get(row).inventory();
            else if(row>=particles&&row<particles+state.deposits().size())inventory=state.deposits().get(row-particles).inventory();
            else if(row>=particles+deposits){boolean escaped=row>=particles+deposits+definitions.materialIds().size();
                int material=row-particles-deposits-(escaped?definitions.materialIds().size():0);
                for(var value:escaped?state.escapedSink():state.externalSource())if(value.material()==material){inventory=value;break;}}
            putInt(b,Field.SPECIES_OFFSETS,row,speciesCursor[0]);if(inventory!=null)writeRow(b,row,inventory,speciesCursor);
        }
        putInt(b,Field.SPECIES_OFFSETS,rows,speciesCursor[0]);putInt(b,Field.COUNTS,2,speciesCursor[0]);
        for(int i=0;i<state.particles().size();i++){var p=state.particles().get(i);
            vector(b,Field.POSITION,i,new double[]{p.x(),p.y(),p.z()});vector(b,Field.VELOCITY,i,new double[]{p.vx(),p.vy(),p.vz()});
            putInt(b,Field.CONTACT_AGE,i,p.contactAge());putInt(b,Field.FLAGS,i,p.flags());
            for(int k=0;k<8;k++){putInt(b,Field.SUPPORT_CELLS,8*i+k,p.supportCells()[k]);putLong(b,Field.SUPPORT_QUOTAS,8*i+k,p.supportQuotas()[k]);}}
        for(int i=0;i<state.deposits().size();i++){var d=state.deposits().get(i);putInt(b,Field.DEPOSIT_CELL,i,d.cell());putInt(b,Field.DEPOSIT_POROSITY,i,Float.floatToRawIntBits(d.porosity()));}
    }
    State exportState(ByteBuffer source){
        var b=view(source);int active=getInt(b,Field.COUNTS,0),beds=getInt(b,Field.COUNTS,1);
        if(active<0||active>particles||beds<0||beds>deposits)throw new IllegalStateException("Granular logical count exceeds layout");
        var ps=new ArrayList<Particle>();var ds=new ArrayList<Deposit>();var external=new ArrayList<Row>();var escaped=new ArrayList<Row>();
        for(int i=0;i<active;i++){int[] cells=new int[8];long[] quota=new long[8];for(int k=0;k<8;k++){cells[k]=getInt(b,Field.SUPPORT_CELLS,8*i+k);quota[k]=getLong(b,Field.SUPPORT_QUOTAS,8*i+k);}
            ps.add(new Particle(readRow(b,i),number(b,Field.POSITION,3*i),number(b,Field.POSITION,3*i+1),number(b,Field.POSITION,3*i+2),
                    number(b,Field.VELOCITY,3*i),number(b,Field.VELOCITY,3*i+1),number(b,Field.VELOCITY,3*i+2),getInt(b,Field.CONTACT_AGE,i),getInt(b,Field.FLAGS,i),cells,quota));}
        for(int i=0;i<beds;i++)ds.add(new Deposit(readRow(b,particles+i),getInt(b,Field.DEPOSIT_CELL,i),Float.intBitsToFloat(getInt(b,Field.DEPOSIT_POROSITY,i))));
        for(int m=0;m<definitions.materialIds().size();m++){int a=auditRow(false,m),c=auditRow(true,m);if(getLong(b,Field.SOLID_VOLUME_SVQ,a)>0)external.add(readRow(b,a));if(getLong(b,Field.SOLID_VOLUME_SVQ,c)>0)escaped.add(readRow(b,c));}
        int[] ages=new int[sizeX*sizeZ];for(int i=0;i<ages.length;i++)ages[i]=getInt(b,Field.STABLE_AGE,i);
        return new State(definitions,ps,ds,external,escaped,new Dynamics(getLong(b,Field.LOGICAL_STEP,0),ages));
    }
    void copyColumns(VkCommandBuffer command,GpuBuffer input,VulkanGranularStateLayout old,GpuBuffer output,MemoryStack stack){
        if(particles<old.particles||deposits<old.deposits||species<old.species||!definitions.equals(old.definitions)
                ||sizeX!=old.sizeX||sizeY!=old.sizeY||sizeZ!=old.sizeZ)
            throw new IllegalArgumentException("Granular migration shrinks inventory capacity or changes definitions");
        if(particles==old.particles&&deposits==old.deposits){
            var copies=VkBufferCopy.calloc(Field.values().length,stack);int count=0;for(var f:Field.values()){
                var from=old.column(f);if(from.bytes()>0)copies.get(count++).srcOffset(from.offset()).dstOffset(column(f).offset()).size(from.bytes());}
            copies.limit(count);vkCmdCopyBuffer(command,input.handle(),output.handle(),copies);
            if(species>old.species){vkCmdFillBuffer(command,output.handle(),column(Field.SPECIES_HANDLES).offset()+4L*old.species,4L*(species-old.species),0);
                vkCmdFillBuffer(command,output.handle(),column(Field.SPECIES_AMOUNTS).offset()+8L*old.species,8L*(species-old.species),0);}
            return;
        }
        for(var f:Field.values())vkCmdFillBuffer(command,output.handle(),column(f).offset(),column(f).bindingBytes(),0);
        VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT|VK_ACCESS_TRANSFER_WRITE_BIT,stack);
        var regions=new ArrayList<long[]>();int audit=2*definitions.materialIds().size();
        for(var f:Field.values()){
            var from=old.column(f);var to=column(f);if(from.bytes()==0)continue;
            switch(f){
                case MATERIAL,SOLID_VOLUME_SVQ,MASS_UNITS,MIXTURE_PRESENT,MASS_RESIDUAL_UNITS,INTERNAL_ENERGY->{
                    copyRegion(regions,from.offset(),to.offset(),(long)old.particles*f.width);
                    copyRegion(regions,from.offset()+(long)old.particles*f.width,to.offset()+(long)particles*f.width,(long)old.deposits*f.width);
                    copyRegion(regions,from.offset()+(long)(old.particles+old.deposits)*f.width,to.offset()+(long)(particles+deposits)*f.width,(long)audit*f.width);
                }
                case SPECIES_OFFSETS->{
                    copyRegion(regions,from.offset(),to.offset(),4L*old.particles);
                    copyRegion(regions,from.offset()+4L*old.particles,to.offset()+4L*particles,4L*old.deposits);
                    copyRegion(regions,from.offset()+4L*(old.particles+old.deposits),to.offset()+4L*(particles+deposits),4L*(audit+1));
                    if(particles>old.particles)copyRegion(regions,from.offset()+4L*old.particles,to.offset()+4L*old.particles,4);
                    if(deposits>old.deposits)copyRegion(regions,from.offset()+4L*(old.particles+old.deposits),to.offset()+4L*(particles+old.deposits),4);
                }
                default->copyRegion(regions,from.offset(),to.offset(),from.bytes());
            }
        }
        var copies=VkBufferCopy.calloc(regions.size(),stack);for(int i=0;i<regions.size();i++){var r=regions.get(i);copies.get(i).srcOffset(r[0]).dstOffset(r[1]).size(r[2]);}
        vkCmdCopyBuffer(command,input.handle(),output.handle(),copies);
        repeatOffset(command,output,column(Field.SPECIES_OFFSETS).offset()+4L*old.particles,particles-old.particles,stack);
        repeatOffset(command,output,column(Field.SPECIES_OFFSETS).offset()+4L*(particles+old.deposits),deposits-old.deposits,stack);
    }
    private static void copyRegion(List<long[]> regions,long from,long to,long bytes){if(bytes>0)regions.add(new long[]{from,to,bytes});}
    /** Expands a device word by disjoint transfer copies; no CSR endpoint is read back to the host. */
    private static void repeatOffset(VkCommandBuffer command,GpuBuffer output,long offset,int count,MemoryStack stack){
        for(int filled=1;filled<count;){int copied=Math.min(filled,count-filled);
            VulkanRegionStorage.barrier(command,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_ACCESS_TRANSFER_READ_BIT|VK_ACCESS_TRANSFER_WRITE_BIT,stack);
            var region=VkBufferCopy.calloc(1,stack).srcOffset(offset).dstOffset(offset+4L*filled).size(4L*copied);
            vkCmdCopyBuffer(command,output.handle(),output.handle(),region);filled+=copied;
        }
    }
    private void writeRow(ByteBuffer b,int row,Row value,int[] cursor){
        if(value.material()<0||value.material()>=definitions.materialIds().size()||cursor[0]+value.species().length>species)
            throw new IllegalArgumentException("Granular material or species exceeds layout");
        putInt(b,Field.MATERIAL,row,value.material());putLong(b,Field.SOLID_VOLUME_SVQ,row,value.solidVolumeSvq());putLong(b,Field.MASS_UNITS,row,value.massUnits());
        putInt(b,Field.MIXTURE_PRESENT,row,value.mixture()?1:0);putLong(b,Field.MASS_RESIDUAL_UNITS,row,value.massResidualUnits());putLong(b,Field.INTERNAL_ENERGY,row,Double.doubleToRawLongBits(value.internalEnergy()));
        for(int k=0;k<value.species().length;k++){putInt(b,Field.SPECIES_HANDLES,cursor[0],value.species()[k]);putLong(b,Field.SPECIES_AMOUNTS,cursor[0]++,value.amounts()[k]);}
    }
    private Row readRow(ByteBuffer b,int row){int start=getInt(b,Field.SPECIES_OFFSETS,row),end=getInt(b,Field.SPECIES_OFFSETS,row+1);
        if(start<0||end<start||end>species)throw new IllegalStateException("Granular species CSR exceeds layout");int[] ids=new int[end-start];long[] amounts=new long[end-start];
        for(int k=start;k<end;k++){ids[k-start]=getInt(b,Field.SPECIES_HANDLES,k);amounts[k-start]=getLong(b,Field.SPECIES_AMOUNTS,k);}
        return new Row(getInt(b,Field.MATERIAL,row),getLong(b,Field.SOLID_VOLUME_SVQ,row),getLong(b,Field.MASS_UNITS,row),getInt(b,Field.MIXTURE_PRESENT,row)!=0,getLong(b,Field.MASS_RESIDUAL_UNITS,row),ids,amounts,number(b,Field.INTERNAL_ENERGY,row));}
    private void vector(ByteBuffer b,Field f,int i,double[] v){for(int a=0;a<3;a++)putLong(b,f,3*i+a,Double.doubleToRawLongBits(v[a]));}
    private ByteBuffer view(ByteBuffer buffer){
        if(buffer.remaining()>=endOffset)return buffer.slice(Math.toIntExact(baseOffset),Math.toIntExact(bytes())).order(ByteOrder.LITTLE_ENDIAN);
        if(buffer.remaining()==bytes())return buffer.slice().order(ByteOrder.LITTLE_ENDIAN);
        throw new IllegalArgumentException("Granular checkpoint buffer differs from its layout");
    }
    private int address(Field f,int i){return Math.toIntExact(column(f).offset()-baseOffset+(long)i*column(f).elementBytes());}
    private void putInt(ByteBuffer b,Field f,int i,int v){b.putInt(address(f,i),v);}private int getInt(ByteBuffer b,Field f,int i){return b.getInt(address(f,i));}
    private void putLong(ByteBuffer b,Field f,int i,long v){b.putLong(address(f,i),v);}private long getLong(ByteBuffer b,Field f,int i){return b.getLong(address(f,i));}
    private double number(ByteBuffer b,Field f,int i){return Double.longBitsToDouble(getLong(b,f,i));}
    private long align(long value){return Math.addExact(value,alignment-1)&-alignment;}
}
