package mmm.gpu;

import mmm.chemistry.*;
import mmm.granular.GranularRegistry;
import mmm.thermal.*;
import mmm.fluid.air.GasRegistry;
import mmm.units.Mass;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/** Immutable registry tables shared by inventory chemistry, saturation transfer and thermal conversion.
 * All offsets in the uploaded header are relative uint32 word offsets from the table base. */
final class VulkanReactionDefinitions {
    enum Kind { CHEMICAL, SOLID_LIQUID, GAS_LIQUID_FILM, THERMAL_PHASE }
    record Identity(String chemicalFingerprint,String granularFingerprint,String thermalFingerprint,String gasFingerprint,
                    List<String> species,List<String> elements,List<String> phases,List<String> thermalMaterials,
                    List<String> granularMaterials,List<String> gasComponents,List<String> rules){
        Identity{species=List.copyOf(species);elements=List.copyOf(elements);phases=List.copyOf(phases);
            thermalMaterials=List.copyOf(thermalMaterials);granularMaterials=List.copyOf(granularMaterials);
            gasComponents=List.copyOf(gasComponents);rules=List.copyOf(rules);}
    }
    record Tables(Identity identity,int[] words){
        Tables{words=words.clone();}
        @Override public int[] words(){return words.clone();}
        Layout layout(long base,long alignment){return new Layout(this,base,alignment);}
    }
    static final class Layout {
        private final Tables tables;private final VulkanLiquidStateLayout.Column data;private final long endOffset;
        Layout(Tables tables,long base,long alignment){
            if(base<0||alignment<8||(alignment&(alignment-1))!=0)throw new IllegalArgumentException("Invalid reaction table layout");
            this.tables=tables;long start=Math.addExact(base,alignment-1)&-alignment;
            data=new VulkanLiquidStateLayout.Column(start,tables.words.length,4);
            endOffset=Math.addExact(start+data.bindingBytes(),alignment-1)&-alignment;Math.toIntExact(endOffset/4);
        }
        Identity identity(){return tables.identity();}VulkanLiquidStateLayout.Column data(){return data;}
        int speciesCount(){return tables.identity().species().size();}
        boolean hasChemicalRules(){int[] data=tables.words;for(int i=0;i<data[4];i++)if(data[data[5]+2*i]==Kind.CHEMICAL.ordinal())return true;return false;}
        int word(){return Math.toIntExact(data.offset()/4);}long endOffset(){return endOffset;}
        ByteBuffer bytes(){var out=ByteBuffer.allocateDirect(Math.toIntExact(data.bytes())).order(ByteOrder.LITTLE_ENDIAN);out.asIntBuffer().put(tables.words);return out;}
        void initialize(ByteBuffer arena){arena.duplicate().position(Math.toIntExact(data.offset())).put(bytes());}
    }
    /** Header: species count/table, phase count/table, rule count/table, element count.
     * Species rows: charge, exact mass units/quantum (int64), element CSR offset/count.
     * Phase rows: allowed-species CSR offset/count, default species/rational-concentration CSR offset/count.
     * Rule directory rows: kind, payload offset. Payloads are documented at their producer below. */
    static Tables fromRegistries(ChemicalRegistry chemical,GranularRegistry granular,ThermalRegistry thermal,GasRegistry gas){
        Objects.requireNonNull(chemical);Objects.requireNonNull(granular);Objects.requireNonNull(thermal);Objects.requireNonNull(gas);
        if(granular.hasReactiveRules()&&!granular.chemicalRegistryFingerprint().equals(chemical.fingerprint()))
            throw new IllegalArgumentException("Reaction registries use different chemical definitions");
        var species=chemical.species().stream().map(ChemicalSpecies::id).toList();
        var elements=chemical.species().stream().flatMap(s->s.composition().keySet().stream()).distinct().sorted().toList();
        var thermalIds=thermal.materials().stream().map(ThermalMaterialDefinition::id).toList();
        var granularIds=granular.materials().stream().map(m->m.id()).toList();
        var gasIds=gas.components().stream().map(c->c.id()).toList();
        var ruleIds=new ArrayList<String>();var directory=new ArrayList<int[]>();var b=new Words();int header=b.reserve(16);
        int speciesTable=b.reserve(5*species.size());
        for(int i=0;i<species.size();i++){
            var value=chemical.species(i);var mass=value.molarMassKgPerMol().divide(ChemicalLimits.QUANTA_PER_MOL);
            b.set(speciesTable+5*i,value.charge());b.setLong(speciesTable+5*i+1,Mass.exactUnits(mass.numerator(),mass.denominator()));
            b.set(speciesTable+5*i+3,b.size());b.set(speciesTable+5*i+4,value.composition().size());
            value.composition().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->{b.add(elements.indexOf(e.getKey()));b.add(e.getValue());});
        }
        int phaseTable=b.reserve(4*chemical.phases().size());
        for(int i=0;i<chemical.phases().size();i++){var phase=chemical.phases().get(i);b.set(phaseTable+4*i,b.size());b.set(phaseTable+4*i+1,phase.allowedSpecies().size());
            phase.allowedSpecies().forEach(s->b.add(chemical.speciesHandle(s)));
            int defaults=b.reserve(2*phase.defaultConcentrationMolPerCubicMeter().size());b.set(phaseTable+4*i+2,defaults);b.set(phaseTable+4*i+3,phase.defaultConcentrationMolPerCubicMeter().size());
            int entry=0;for(var term:new TreeMap<>(phase.defaultConcentrationMolPerCubicMeter()).entrySet()){
                b.set(defaults+2*entry,chemical.speciesHandle(term.getKey()));b.set(defaults+2*entry+1,b.rational(term.getValue()));entry++;}}
        int gasTable=b.reserve(2*gasIds.size());for(int i=0;i<gasIds.size();i++){var component=gas.components().get(i);b.set(gasTable+2*i,b.size());b.set(gasTable+2*i+1,component.allowedSpecies().size());
            component.allowedSpecies().forEach(id->b.add(chemical.speciesHandle(id)));}
        int thermalTable=b.reserve(2*thermalIds.size());for(int i=0;i<thermalIds.size();i++){var material=thermal.material(i);b.set(thermalTable+2*i,material.carrierKind().ordinal());
            int carrier=-1;if(material.carrierKind()==ThermalMaterialDefinition.CarrierKind.GAS)carrier=handle(gasIds,material.carrierId());
            if(material.carrierKind()==ThermalMaterialDefinition.CarrierKind.LIQUID)for(int j=0;j<chemical.phases().size();j++)if(chemical.phases().get(j).materialId().equals(material.carrierId()))carrier=j;
            b.set(thermalTable+2*i+1,carrier);}
        for(var rule:chemical.reactionOrder()){
            // phase, dissociation/instantaneous kind, reactant CSR offset/count, product CSR offset/count.
            int row=b.reserve(6);b.set(row,chemical.phaseHandle(rule.phaseId()));b.set(row+1,rule.kind().ordinal());
            side(b,row+2,rule.reactants(),chemical);side(b,row+4,rule.products(),chemical);
            add(directory,ruleIds,Kind.CHEMICAL,rule.id(),row);
        }
        for(var rule:granular.reactions()){
            // phase, solid material, reversible; exact extent, SVQ, accessible surface; rational saturation; two stoichiometric CSRs.
            int row=b.reserve(15);b.set(row,chemical.phaseHandle(rule.liquidPhaseId()));b.set(row+1,granular.materialHandle(rule.solidMaterialId()));b.set(row+2,rule.reversible()?1:0);
            b.setLong(row+3,rule.solidExtentPerBatch());b.setLong(row+5,rule.solidVolumeSvqPerBatch());b.setLong(row+7,rule.accessibleSurfaceSvqPerCellStep());
            b.set(row+9,b.rational(rule.saturationConcentrationMolPerCubicMeter()));
            side(b,row+10,rule.aqueousStoichiometry(),chemical);side(b,row+12,rule.solidComposition(),chemical);
            long mass=0;for(var term:rule.solidComposition().entrySet()){
                var m=chemical.species(chemical.speciesHandle(term.getKey())).molarMassKgPerMol().divide(ChemicalLimits.QUANTA_PER_MOL);
                mass=Math.addExact(mass,Math.multiplyExact(Mass.exactUnits(m.numerator(),m.denominator()),term.getValue()));}
            b.set(row+14,b.size());b.addLong(Math.multiplyExact(mass,rule.solidExtentPerBatch()));add(directory,ruleIds,Kind.SOLID_LIQUID,rule.id(),row);
        }
        for(var rule:chemical.gasLiquidMassTransfers()){
            // species, liquid phase, gas component; SI fit coefficients in declared temperature interval.
            int row=b.size();b.add(chemical.speciesHandle(rule.speciesId()));b.add(chemical.phaseHandle(rule.liquidPhaseId()));b.add(handle(gasIds,rule.gasComponentId()));
            for(double value:new double[]{rule.referenceTemperatureK(),rule.henrySolubilityMolPerM3Pa(),rule.temperatureSlopeK(),
                    rule.liquidFilmVelocityMPerS(),rule.gasFilmVelocityMPerS(),rule.minimumTemperatureK(),rule.maximumTemperatureK()})b.addDouble(value);
            add(directory,ruleIds,Kind.GAS_LIQUID_FILM,rule.id(),row);
        }
        var phaseModel=new GasLiquidPhaseModel(thermal,gas);
        for(var rule:thermal.transitions()){
            // lower/upper thermal materials, interface, exact mass+SVQ per batch, equilibrium curve CSR, gas component/nanos.
            int row=b.reserve(13);b.set(row,thermal.materialHandle(rule.lowerEnergyMaterialId()));b.set(row+1,thermal.materialHandle(rule.upperEnergyMaterialId()));
            b.set(row+2,rule.interfaceRequirement().ordinal());b.setLong(row+3,Mass.exactUnits(rule.massKgPerBatch().numerator(),rule.massKgPerBatch().denominator()));
            b.setLong(row+5,rule.lowerVolumeSvqPerBatch());b.setLong(row+7,rule.upperVolumeSvqPerBatch());b.set(row+9,b.size());b.set(row+10,rule.equilibriumCurve().size());
            for(var point:rule.equilibriumCurve()){b.addDouble(point.pressurePa());b.addDouble(point.temperatureK());}
            b.set(row+11,-1);b.set(row+12,b.size());
            if(rule.interfaceRequirement()==ThermalPhaseTransition.InterfaceRequirement.LIQUID_GAS){var batch=phaseModel.gasBatch(rule,1).components().getFirst();b.set(row+11,handle(gasIds,batch.componentId()));b.addLong(batch.standardVolumeNanos());}
            else b.addLong(0);
            add(directory,ruleIds,Kind.THERMAL_PHASE,rule.id(),row);
        }
        int ruleTable=b.size();for(var entry:directory){b.add(entry[0]);b.add(entry[1]);}
        b.set(header,species.size());b.set(header+1,speciesTable);b.set(header+2,chemical.phases().size());b.set(header+3,phaseTable);
        b.set(header+4,directory.size());b.set(header+5,ruleTable);b.set(header+6,elements.size());b.set(header+7,gasIds.size());b.set(header+8,gasTable);b.set(header+9,thermalIds.size());b.set(header+10,thermalTable);
        return new Tables(new Identity(chemical.fingerprint(),granular.fingerprint(),thermal.fingerprint(),gas.fingerprint(),species,elements,
                chemical.phases().stream().map(ChemicalPhaseDefinition::id).toList(),thermalIds,granularIds,gasIds,ruleIds),b.array());
    }
    private static void add(List<int[]> directory,List<String> ids,Kind kind,String id,int offset){directory.add(new int[]{kind.ordinal(),offset});ids.add(id);}
    private static int handle(List<String> ids,String id){int result=ids.indexOf(id);if(result<0)throw new IllegalArgumentException("Absent reaction carrier: "+id);return result;}
    private static void side(Words b,int row,Map<String,Integer> side,ChemicalRegistry chemical){b.set(row,b.size());b.set(row+1,side.size());
        side.entrySet().stream().sorted(Comparator.comparingInt(e->chemical.speciesHandle(e.getKey()))).forEach(e->{b.add(chemical.speciesHandle(e.getKey()));b.add(e.getValue());});}
    private static final class Words {
        private final ArrayList<Integer> values=new ArrayList<>();int size(){return values.size();}void add(int n){values.add(n);}void set(int i,int n){values.set(i,n);}
        int reserve(int count){int at=size();for(int i=0;i<count;i++)add(0);return at;}
        void setLong(int i,long n){set(i,(int)n);set(i+1,(int)(n>>>32));}void addLong(long n){add((int)n);add((int)(n>>>32));}void addDouble(double n){addLong(Double.doubleToRawLongBits(n));}
        int rational(ExactRational value){int at=size();integer(value.numerator());integer(value.denominator());return at;}
        void integer(BigInteger value){add(value.signum());int limbs=(value.abs().bitLength()+31)/32;add(limbs);for(int i=0;i<limbs;i++)add(value.abs().shiftRight(32*i).intValue());}
        int[] array(){return values.stream().mapToInt(Integer::intValue).toArray();}
    }
}
