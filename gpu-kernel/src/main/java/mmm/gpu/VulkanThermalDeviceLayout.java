package mmm.gpu;

import mmm.thermal.FiniteThermalBodyState;
import mmm.thermal.FiniteThermalDeviceAudit;
import mmm.thermal.FiniteThermalDeviceInventory;
import mmm.thermal.FiniteThermalDeviceState;
import mmm.thermal.ThermalMaterialDefinition;
import mmm.thermal.ThermalRegistry;
import mmm.units.SignedDyadic;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import static org.lwjgl.vulkan.VK10.vkCmdCopyBuffer;

/** Finite thermal bodies and captured physical ports share their fluid candidate's device generation. */
final class VulkanThermalDeviceLayout {
    static final int DYADIC_WORDS=72;
    enum Field {
        COUNTS(4),GENERATION(8),REQUEST_GENERATION(8),MATERIAL(4),MASS_UNITS(8),INTERNAL_ENERGY(8),SPECIES_OFFSETS(4),SPECIES_HANDLES(4),SPECIES_AMOUNTS(8),
        PORT_DEVICE(4),PORT_CELL(4),PORT_DIRECTION(4),PORT_WALL_THICKNESS(8),PORT_WALL_K(8),PORT_FILM_THICKNESS(8),
        AUDIT(4),DEVICE_ENERGY_CHANGE(8),FLUID_EXCHANGE(4),EXCHANGE_ROUNDOFF(4),SURFACE_AMOUNTS(8),SURFACE_RESIDUALS(8),SURFACE_ENERGY_INTERCEPT(8),SURFACE_HEAT_CAPACITY(8);
        final int width;Field(int width){this.width=width;}
    }
    enum Audit { INITIAL_INVENTORY,EXTERNAL_SOURCE,EXTERNAL_SINK,SOLVER_EXCHANGE,ROUNDOFF_RESIDUAL }
    record Identity(String providerId,String objectId,String deviceTypeId) {
        static Identity of(FiniteThermalDeviceState value){return new Identity(value.providerId(),value.objectId(),value.deviceTypeId());}
    }
    record SurfaceSlot(int device,mmm.thermal.FiniteThermalDeviceDefinition.LocalFace face,String materialId,long loadingMassUnits,
                       double referenceTemperature,double referenceSpecificEnergy,double specificHeat,double areaFactor,
                       List<mmm.thermal.SurfaceMaterialDefinition.Adsorbate> adsorbates,List<mmm.thermal.SurfaceMaterialDefinition.Catalysis> catalysts,
                       mmm.thermal.SurfaceMaterialDefinition definition){
        SurfaceSlot{adsorbates=List.copyOf(adsorbates);catalysts=List.copyOf(catalysts);}
    }
    record Definitions(String thermalRegistryFingerprint,List<String> materialIds,List<Identity> identities,List<SurfaceSlot> surfaces){
        Definitions(String fingerprint,List<String> materials,List<Identity> identities){this(fingerprint,materials,identities,List.of());}
        Definitions{materialIds=List.copyOf(materialIds);identities=List.copyOf(identities);surfaces=List.copyOf(surfaces);}
        int surfaceSpeciesCount(){return surfaces.stream().mapToInt(value->value.adsorbates().size()).sum();}
    }
    /** Captured port direction is -X,+X,-Y,+Y,-Z,+Z. Exposed area is derived by the thermal contact producer. */
    record Port(String providerId,String objectId,int cell,int direction,double wallThickness,double wallConductivity,double filmThickness){
        Port{if(providerId==null||objectId==null||cell<0||direction<0||direction>5||!Double.isFinite(wallThickness)||wallThickness<0
                ||!Double.isFinite(wallConductivity)||wallConductivity<=0||!Double.isFinite(filmThickness)||filmThickness<=0)
            throw new IllegalArgumentException("Invalid finite thermal device port");}
    }
    record State(FiniteThermalDeviceInventory inventory,List<Port> ports){State{java.util.Objects.requireNonNull(inventory);ports=List.copyOf(ports);}}
    record Configuration(Definitions definitions,State initial,VulkanLiquidThermodynamics.Tables thermal,double[] materialReferences){
        Configuration{materialReferences=materialReferences.clone();}
        static Configuration fromRegistries(State initial,ThermalRegistry registry){
            initial.inventory().requireCompatible(registry);
            var materials=registry.materials().stream().filter(m->m.carrierKind()==ThermalMaterialDefinition.CarrierKind.DEVICE).toList();
            var surfaces=new ArrayList<SurfaceSlot>();
            for(int i=0;i<initial.inventory().devices().size();i++)for(var surface:initial.inventory().devices().get(i).surfaces()){
                var material=registry.surfaceMaterial(surface.materialId());
                surfaces.add(new SurfaceSlot(i,surface.face(),surface.materialId(),surface.loadingMassUnits(),material.referenceTemperatureK(),
                        material.referenceSpecificEnergyJPerKg(),material.specificHeatJPerKgK(),material.effectiveAreaFactor(),material.adsorbates(),material.catalysis(),material));
            }
            var definitions=new Definitions(registry.fingerprint(),materials.stream().map(ThermalMaterialDefinition::id).toList(),
                    initial.inventory().devices().stream().map(Identity::of).toList(),surfaces);
            return new Configuration(definitions,initial,VulkanLiquidThermodynamics.tables(materials),
                    materials.stream().mapToDouble(ThermalMaterialDefinition::referenceSpecificEnergyJPerKg).toArray());
        }
    }
    /** Endpoint device changes, signed heat entering fluid, and their exact combined roundoff are separate outputs. */
    record ExchangeOutputs(VulkanLiquidStateLayout.Column deviceChanges,VulkanLiquidStateLayout.Column fluidExchange,VulkanLiquidStateLayout.Column roundoff){}
    private final int cells,devices,ports;
    private final Definitions definitions;
    private final Map<DeviceKey,Integer> identityIndices;
    private record DeviceKey(String provider,String object){}
    private final long baseOffset,endOffset,alignment;
    private final EnumMap<Field,VulkanLiquidStateLayout.Column> columns=new EnumMap<>(Field.class);
    VulkanThermalDeviceLayout(int cells,int deviceCapacity,int portCapacity,Definitions definitions,long base,long alignment){
        if(cells<1||deviceCapacity<definitions.identities().size()||portCapacity<0||base<0||alignment<8||(alignment&(alignment-1))!=0)
            throw new IllegalArgumentException("Invalid finite thermal device layout");
        this.cells=cells;devices=deviceCapacity;ports=portCapacity;this.definitions=definitions;this.alignment=alignment;
        var indices=new HashMap<DeviceKey,Integer>();
        for(int i=0;i<definitions.identities().size();i++){var id=definitions.identities().get(i);
            if(indices.put(new DeviceKey(id.providerId(),id.objectId()),i)!=null)throw new IllegalArgumentException("Duplicate finite thermal identity");}
        identityIndices=Map.copyOf(indices);baseOffset=align(base);long cursor=baseOffset;
        for(var f:Field.values()){
            int count=switch(f){case COUNTS->2;case SPECIES_OFFSETS->devices+1;case SPECIES_HANDLES,SPECIES_AMOUNTS->0;
                case PORT_DEVICE,PORT_CELL,PORT_DIRECTION,PORT_WALL_THICKNESS,PORT_WALL_K,PORT_FILM_THICKNESS->ports;
                case AUDIT->DYADIC_WORDS*Audit.values().length;case DEVICE_ENERGY_CHANGE->2*devices;case FLUID_EXCHANGE,EXCHANGE_ROUNDOFF->DYADIC_WORDS;
                case SURFACE_AMOUNTS,SURFACE_RESIDUALS->definitions.surfaceSpeciesCount();default->devices;};
            var column=new VulkanLiquidStateLayout.Column(align(cursor),count,f.width);columns.put(f,column);cursor=column.offset()+column.bindingBytes();
        }endOffset=align(cursor);Math.toIntExact(endOffset/4);
    }
    int cells(){return cells;}int deviceCapacity(){return devices;}int portCapacity(){return ports;}
    Definitions definitions(){return definitions;}long baseOffset(){return baseOffset;}long endOffset(){return endOffset;}long bytes(){return endOffset-baseOffset;}
    VulkanLiquidStateLayout.Column column(Field field){return columns.get(field);}int word(Field field){return Math.toIntExact(column(field).offset()/4);}
    VulkanLiquidStateLayout.Column audit(Audit audit){return new VulkanLiquidStateLayout.Column(column(Field.AUDIT).offset()+4L*DYADIC_WORDS*audit.ordinal(),DYADIC_WORDS,4);}
    ExchangeOutputs exchangeOutputs(){return new ExchangeOutputs(column(Field.DEVICE_ENERGY_CHANGE),column(Field.FLUID_EXCHANGE),column(Field.EXCHANGE_ROUNDOFF));}
    void importState(ByteBuffer target,State state){
        var inventory=state.inventory();if(!inventory.thermalRegistryFingerprint().equals(definitions.thermalRegistryFingerprint())||inventory.devices().size()>devices||state.ports().size()>ports
                ||!inventory.devices().stream().map(Identity::of).toList().equals(definitions.identities()))throw new IllegalArgumentException("Finite device input changes identities or exceeds layout");
        var b=view(target);putInt(b,Field.COUNTS,0,inventory.devices().size());putInt(b,Field.COUNTS,1,state.ports().size());
        for(int i=0;i<=devices;i++)putInt(b,Field.SPECIES_OFFSETS,i,0);
        for(int i=0;i<inventory.devices().size();i++){var device=inventory.devices().get(i);var body=device.body();int material=definitions.materialIds().indexOf(body.thermalMaterialId());
            if(material<0)throw new IllegalArgumentException("Finite thermal material is absent: "+body.thermalMaterialId());
            putLong(b,Field.GENERATION,i,body.generation());putLong(b,Field.REQUEST_GENERATION,i,body.generation());putInt(b,Field.MATERIAL,i,material);putLong(b,Field.MASS_UNITS,i,body.massUnits());putLong(b,Field.INTERNAL_ENERGY,i,Double.doubleToRawLongBits(body.internalEnergyJ()));
            putLong(b,Field.DEVICE_ENERGY_CHANGE,2*i,0);putLong(b,Field.DEVICE_ENERGY_CHANGE,2*i+1,0);
        }
        int surfaceIndex=0,speciesIndex=0;
        for(int i=0;i<devices;i++){putDouble(b,Field.SURFACE_ENERGY_INTERCEPT,i,0);putDouble(b,Field.SURFACE_HEAT_CAPACITY,i,0);}
        for(int i=0;i<inventory.devices().size();i++)for(var surface:inventory.devices().get(i).surfaces()){
            if(surfaceIndex>=definitions.surfaces().size())throw new IllegalArgumentException("Unexpected fixed surface");
            var slot=definitions.surfaces().get(surfaceIndex++);
            if(slot.device()!=i||slot.face()!=surface.face()||!slot.materialId().equals(surface.materialId())||slot.loadingMassUnits()!=surface.loadingMassUnits())
                throw new IllegalArgumentException("Fixed surface identities changed");
            double loading=mmm.units.Mass.kilograms(slot.loadingMassUnits());
            putDouble(b,Field.SURFACE_ENERGY_INTERCEPT,i,number(b,Field.SURFACE_ENERGY_INTERCEPT,i)+loading*(slot.referenceSpecificEnergy()-slot.specificHeat()*slot.referenceTemperature()));
            putDouble(b,Field.SURFACE_HEAT_CAPACITY,i,number(b,Field.SURFACE_HEAT_CAPACITY,i)+loading*slot.specificHeat());
            for(var adsorbate:slot.adsorbates()){
                long amount=surface.speciesQuanta().getOrDefault(adsorbate.speciesId(),0L);
                putLong(b,Field.SURFACE_AMOUNTS,speciesIndex,amount);
                putDouble(b,Field.SURFACE_RESIDUALS,speciesIndex++,surface.residualQuanta().getOrDefault(adsorbate.speciesId(),0.0));
                double moles=amount/(double)mmm.chemistry.ChemicalLimits.QUANTA_PER_MOL;
                putDouble(b,Field.SURFACE_ENERGY_INTERCEPT,i,number(b,Field.SURFACE_ENERGY_INTERCEPT,i)+moles*(adsorbate.referenceEnergyJPerMol()-adsorbate.heatCapacityJPerMolK()*adsorbate.referenceTemperatureK()));
                putDouble(b,Field.SURFACE_HEAT_CAPACITY,i,number(b,Field.SURFACE_HEAT_CAPACITY,i)+moles*adsorbate.heatCapacityJPerMolK());
            }
        }
        if(surfaceIndex!=definitions.surfaces().size())throw new IllegalArgumentException("Fixed surface is missing");
        var seen=new java.util.HashSet<String>();
        for(int i=0;i<state.ports().size();i++){var port=state.ports().get(i);int device=identity(port.providerId(),port.objectId());
            if(device<0||port.cell()>=cells||!seen.add(device+":"+port.cell()+":"+port.direction()))throw new IllegalArgumentException("Finite device port is absent, repeated or outside the region");
            putInt(b,Field.PORT_DEVICE,i,device);putInt(b,Field.PORT_CELL,i,port.cell());putInt(b,Field.PORT_DIRECTION,i,port.direction());
            putDouble(b,Field.PORT_WALL_THICKNESS,i,port.wallThickness());putDouble(b,Field.PORT_WALL_K,i,port.wallConductivity());putDouble(b,Field.PORT_FILM_THICKNESS,i,port.filmThickness());
        }
        var audit=inventory.audit();SignedDyadic[] entries={audit.initialInventory(),audit.externalSource(),audit.externalSink(),audit.solverExchange(),audit.roundoffResidual()};
        for(var field:Audit.values())writeDyadic(b,relative(audit(field).offset()),entries[field.ordinal()]);
        writeDyadic(b,address(Field.FLUID_EXCHANGE,0),SignedDyadic.ZERO);writeDyadic(b,address(Field.EXCHANGE_ROUNDOFF,0),SignedDyadic.ZERO);
    }
    /** The caller exports inventory and both exchange ledgers from the same completed candidate. */
    State exportState(ByteBuffer source){
        var b=view(source);int count=getInt(b,Field.COUNTS,0),portCount=getInt(b,Field.COUNTS,1);
        if(count!=definitions.identities().size()||count>devices||portCount<0||portCount>ports)throw new IllegalStateException("Finite device checkpoint counts differ from identities");
        var result=new ArrayList<FiniteThermalDeviceState>();var capturedPorts=new ArrayList<Port>();
        int surfaceIndex=0,speciesIndex=0;
        for(int i=0;i<count;i++){var id=definitions.identities().get(i);int material=getInt(b,Field.MATERIAL,i);if(material<0||material>=definitions.materialIds().size())throw new IllegalStateException("Finite thermal material handle exceeds definitions");
            var body=new FiniteThermalBodyState(getLong(b,Field.GENERATION,i),definitions.thermalRegistryFingerprint(),definitions.materialIds().get(material),getLong(b,Field.MASS_UNITS,i),Double.longBitsToDouble(getLong(b,Field.INTERNAL_ENERGY,i)));
            var surfaces=new ArrayList<mmm.thermal.FixedSurfaceState>();
            while(surfaceIndex<definitions.surfaces().size()&&definitions.surfaces().get(surfaceIndex).device()==i){
                var slot=definitions.surfaces().get(surfaceIndex++);var amounts=new java.util.TreeMap<String,Long>();var residuals=new java.util.TreeMap<String,Double>();
                for(var adsorbate:slot.adsorbates()){long amount=getLong(b,Field.SURFACE_AMOUNTS,speciesIndex);double residual=number(b,Field.SURFACE_RESIDUALS,speciesIndex++);if(amount>0)amounts.put(adsorbate.speciesId(),amount);else if(amount<0)throw new IllegalStateException("Negative adsorbed inventory");if(residual!=0)residuals.put(adsorbate.speciesId(),residual);}
                surfaces.add(new mmm.thermal.FixedSurfaceState(slot.face(),slot.materialId(),slot.loadingMassUnits(),amounts,residuals));
            }
            result.add(new FiniteThermalDeviceState(id.providerId(),id.objectId(),id.deviceTypeId(),body,surfaces));}
        for(int i=0;i<portCount;i++){int device=getInt(b,Field.PORT_DEVICE,i);if(device<0||device>=count)throw new IllegalStateException("Finite device port handle exceeds identities");var id=definitions.identities().get(device);
            capturedPorts.add(new Port(id.providerId(),id.objectId(),getInt(b,Field.PORT_CELL,i),getInt(b,Field.PORT_DIRECTION,i),number(b,Field.PORT_WALL_THICKNESS,i),number(b,Field.PORT_WALL_K,i),number(b,Field.PORT_FILM_THICKNESS,i)));}
        var audit=new FiniteThermalDeviceAudit(readAudit(b,Audit.INITIAL_INVENTORY),readAudit(b,Audit.EXTERNAL_SOURCE),readAudit(b,Audit.EXTERNAL_SINK),readAudit(b,Audit.SOLVER_EXCHANGE),readAudit(b,Audit.ROUNDOFF_RESIDUAL));
        return new State(new FiniteThermalDeviceInventory(definitions.thermalRegistryFingerprint(),result,audit),capturedPorts);
    }
    SignedDyadic fluidExchange(ByteBuffer source){return readDyadic(view(source),address(Field.FLUID_EXCHANGE,0));}
    SignedDyadic exchangeRoundoff(ByteBuffer source){return readDyadic(view(source),address(Field.EXCHANGE_ROUNDOFF,0));}
    void copyColumns(VkCommandBuffer command,GpuBuffer input,VulkanThermalDeviceLayout old,GpuBuffer output,MemoryStack stack){
        if(cells!=old.cells||devices<old.devices||ports<old.ports||!definitions.equals(old.definitions))throw new IllegalArgumentException("Finite device migration changes identities or shrinks capacity");
        var copies=VkBufferCopy.calloc(Field.values().length,stack);int count=0;for(var field:Field.values()){var from=old.column(field);if(from.bytes()>0)copies.get(count++).srcOffset(from.offset()).dstOffset(column(field).offset()).size(from.bytes());}
        copies.limit(count);vkCmdCopyBuffer(command,input.handle(),output.handle(),copies);
    }
    /** Called once at request preparation; private substeps preserve this column with the candidate. */
    void recordBeginRequest(VkCommandBuffer command,GpuBuffer source,VulkanThermalDeviceLayout old,GpuBuffer candidate,MemoryStack stack){
        if(!definitions.equals(old.definitions))throw new IllegalArgumentException("Finite device request changes identities");
        var copy=VkBufferCopy.calloc(1,stack).srcOffset(old.column(Field.GENERATION).offset())
                .dstOffset(column(Field.REQUEST_GENERATION).offset()).size(old.column(Field.GENERATION).bytes());
        if(old.column(Field.GENERATION).bytes()>0)vkCmdCopyBuffer(command,source.handle(),candidate.handle(),copy);
    }
    /** Settlement uses actual device endpoint changes; bath and fluid solve residuals remain in the common thermal ledger. */
    record Settlement(VulkanEnergyLedger.Layout exchange,VulkanEnergyLedger.Layout cumulative,long endOffset){
        void initializeHeaders(ByteBuffer arena){exchange.initializeHeader(arena);cumulative.initializeHeader(arena);}
    }
    Settlement settlement(long base,long alignment){
        if(base<endOffset)throw new IllegalArgumentException("Finite device settlement overlaps inventory");
        var change=column(Field.DEVICE_ENERGY_CHANGE);
        var positive=new VulkanEnergyLedger.Stream(1,Math.toIntExact(change.offset()/4),change.count(),2,1);
        var negative=new VulkanEnergyLedger.Stream(1,Math.toIntExact(change.offset()/4),change.count(),2,-1);
        var exchange=VulkanEnergyLedger.layout(List.of(negative),base,alignment);
        var cumulative=VulkanEnergyLedger.layout(List.of(positive),exchange.endOffset(),alignment);
        return new Settlement(exchange,cumulative,cumulative.endOffset());
    }
    /** Contacts publish writes U, generation and [newU,-oldU] per device before this call.
     * The ledger must be bound to immutable source and completed candidate arenas. */
    void recordSettle(VkCommandBuffer command,VulkanThermalDeviceLayout source,Settlement settlement,
                      VulkanEnergyLedger ledger,GpuBuffer candidate){
        recordSettle(command,source,settlement,ledger,candidate,false);
    }
    void recordSettle(VkCommandBuffer command,VulkanThermalDeviceLayout source,Settlement settlement,VulkanEnergyLedger ledger,GpuBuffer candidate,boolean continuation){
        if(!definitions.equals(source.definitions))throw new IllegalArgumentException("Finite device settlement changes identities");
        ledger.record(command,settlement.exchange());
        if(continuation)ledger.recordAccumulate(command,settlement.exchange(),source.word(Field.FLUID_EXCHANGE));
        ledger.record(command,settlement.cumulative());
        ledger.recordAccumulate(command,settlement.cumulative(),Math.toIntExact(source.audit(Audit.SOLVER_EXCHANGE).offset()/4));
        try(var stack=MemoryStack.stackPush()){
            VulkanRegionStorage.barrier(command,org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_WRITE_BIT,
                    org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_READ_BIT|org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT,stack);
            var copies=VkBufferCopy.calloc(2,stack);
            copies.get(0).srcOffset(settlement.exchange().result().offset()).dstOffset(column(Field.FLUID_EXCHANGE).offset()).size(4L*DYADIC_WORDS);
            copies.get(1).srcOffset(settlement.cumulative().result().offset()).dstOffset(audit(Audit.SOLVER_EXCHANGE).offset()).size(4L*DYADIC_WORDS);
            vkCmdCopyBuffer(command,candidate.handle(),candidate.handle(),copies);
            VulkanRegionStorage.barrier(command,org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,org.lwjgl.vulkan.VK10.VK_ACCESS_TRANSFER_WRITE_BIT,
                    org.lwjgl.vulkan.VK10.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_READ_BIT|org.lwjgl.vulkan.VK10.VK_ACCESS_SHADER_WRITE_BIT,stack);
        }
    }
    private int identity(String provider,String object){return identityIndices.getOrDefault(new DeviceKey(provider,object),-1);}
    private SignedDyadic readAudit(ByteBuffer b,Audit value){return readDyadic(b,relative(audit(value).offset()));}
    private static void writeDyadic(ByteBuffer b,int address,SignedDyadic value){
        var magnitude=value.significand().abs();int limbs=(magnitude.bitLength()+31)/32;if(limbs>DYADIC_WORDS-4)throw new IllegalArgumentException("Finite device audit exceeds resident dyadic width");
        b.putInt(address,value.signum()).putInt(address+4,value.exponent()).putInt(address+8,limbs).putInt(address+12,0);
        for(int i=0;i<DYADIC_WORDS-4;i++)b.putInt(address+16+4*i,i<limbs?magnitude.shiftRight(32*i).intValue():0);
    }
    private static SignedDyadic readDyadic(ByteBuffer b,int address){
        int sign=b.getInt(address),limbs=b.getInt(address+8);if(Math.abs(sign)>1||limbs<0||limbs>DYADIC_WORDS-4)throw new IllegalStateException("Finite device dyadic checkpoint is invalid");
        BigInteger magnitude=BigInteger.ZERO;for(int i=limbs-1;i>=0;i--)magnitude=magnitude.shiftLeft(32).add(BigInteger.valueOf(Integer.toUnsignedLong(b.getInt(address+16+4*i))));
        return sign==0?SignedDyadic.ZERO:new SignedDyadic(sign<0?magnitude.negate():magnitude,b.getInt(address+4));
    }
    private ByteBuffer view(ByteBuffer b){if(b.remaining()>=endOffset)return b.slice(Math.toIntExact(baseOffset),Math.toIntExact(bytes())).order(ByteOrder.LITTLE_ENDIAN);
        if(b.remaining()==bytes())return b.slice().order(ByteOrder.LITTLE_ENDIAN);throw new IllegalArgumentException("Finite device checkpoint buffer differs from layout");}
    private int relative(long address){return Math.toIntExact(address-baseOffset);}private int address(Field f,int i){return relative(column(f).offset()+(long)i*f.width);}
    private void putInt(ByteBuffer b,Field f,int i,int v){b.putInt(address(f,i),v);}private int getInt(ByteBuffer b,Field f,int i){return b.getInt(address(f,i));}
    private void putLong(ByteBuffer b,Field f,int i,long v){b.putLong(address(f,i),v);}private long getLong(ByteBuffer b,Field f,int i){return b.getLong(address(f,i));}
    private void putDouble(ByteBuffer b,Field f,int i,double v){putLong(b,f,i,Double.doubleToRawLongBits(v));}private double number(ByteBuffer b,Field f,int i){return Double.longBitsToDouble(getLong(b,f,i));}
    private long align(long n){return Math.addExact(n,alignment-1)&-alignment;}
}
