package mmm.simulation.persistence;

import java.io.*;
import java.math.BigInteger;
import java.util.*;
import mmm.chemistry.*;
import mmm.fluid.air.*;
import mmm.granular.*;
import mmm.inventory.LiquidCellInventory;
import mmm.pipe.*;
import mmm.units.SignedDyadic;

/** Exact pipe inventories use stable material/species IDs in the world checkpoint. */
final class PipeStateCodec {
    private static final int MAX_SEGMENTS=100000, MAX_ROWS=1000000;
    private PipeStateCodec() { }
    static void write(DataOutputStream out,PipeDimensionState dimension,ChemicalRegistry chemistry) throws IOException {
        out.writeLong(dimension.generation());
        var audit=dimension.audit();
        for(var value:List.of(audit.initialEnergy(),audit.suppliedEnergy(),audit.removedEnergy(),
                audit.externalWork(),audit.environmentHeat(),audit.roundoff()))writeDyadic(out,value);
        var network=dimension.network();
        out.writeInt(network.materialIds().size());for(var id:network.materialIds())out.writeUTF(id);
        out.writeInt(network.segments().size());
        for(var segment:network.segments()) {
            out.writeUTF(segment.id());out.writeDouble(segment.elevationM());out.writeDouble(segment.capacityM3());
            writeGas(out,segment.gas());out.writeDouble(segment.gasInternalEnergy());
            out.writeInt(segment.deposits().size());
            for(var deposit:segment.deposits()) {
                var group=deposit.inventory();out.writeUTF(group.materialId());out.writeLong(group.solidVolumeSvq());out.writeLong(group.massUnits());
                var mixture=group.composition();out.writeBoolean(mixture!=null);
                if(mixture!=null) {
                    out.writeLong(mixture.massResidualUnits());out.writeInt(mixture.entryCount());
                    for(int i=0;i<mixture.entryCount();i++){out.writeUTF(chemistry.species(mixture.speciesHandle(i)).id());out.writeLong(mixture.amountAt(i));}
                }
                out.writeDouble(deposit.internalEnergy());
            }
        }
        for(var row:network.liquids().rows()) {
            out.writeInt(row.size());
            for(var stock:row) {
                out.writeInt(stock.key().material());writeDyadic(out,stock.volume());out.writeLong(stock.massUnits());out.writeDouble(stock.internalEnergy());
                var mixture=stock.mixture();out.writeBoolean(mixture!=null);
                if(mixture!=null) {
                    out.writeLong(mixture.massResidualUnits());out.writeInt(mixture.entryCount());
                    for(int i=0;i<mixture.entryCount();i++){out.writeUTF(chemistry.species(mixture.speciesHandle(i)).id());out.writeLong(mixture.amountAt(i));}
                }
            }
        }
        out.writeInt(network.connections().size());
        for(var edge:network.connections()) {
            out.writeInt(edge.first());out.writeInt(edge.second());out.writeDouble(edge.lengthM());out.writeDouble(edge.areaM2());
            out.writeDouble(edge.riseM());out.writeDouble(edge.opening());out.writeDouble(edge.sieveOpeningM());
        }
        out.writeInt(dimension.installations().size());
        for(var entry:new TreeMap<>(dimension.installations()).entrySet()) {
            out.writeUTF(entry.getKey());out.writeUTF(entry.getValue().blockId());
            out.writeInt(entry.getValue().properties().size());
            for(var property:new TreeMap<>(entry.getValue().properties()).entrySet()) {
                out.writeUTF(property.getKey());out.writeUTF(property.getValue());
            }
        }
    }
    static PipeDimensionState read(DataInputStream in,ChemicalRegistry chemistry) throws IOException {
        long generation=in.readLong();
        var audit=new PipeAudit(readDyadic(in),readDyadic(in),readDyadic(in),readDyadic(in),readDyadic(in),readDyadic(in));
        int materials=count(in,4096);var palette=new ArrayList<String>();for(int i=0;i<materials;i++)palette.add(in.readUTF());
        int size=count(in,MAX_SEGMENTS);var segments=new ArrayList<PipeSegmentState>();
        for(int i=0;i<size;i++) {
            String id=in.readUTF();double elevation=in.readDouble(),capacity=in.readDouble();
            var gas=readGas(in);double energy=in.readDouble();int deposits=count(in,4096);
            var groups=new ArrayList<mmm.granular.GranularTransferLedger.Portion>();
            for(int j=0;j<deposits;j++) {
                String material=in.readUTF();long volume=in.readLong(),mass=in.readLong();SolidCompositionRecord mixture=null;
                if(in.readBoolean()){long residual=in.readLong();mixture=SolidCompositionRecord.fromStableAmounts(chemistry,readSpecies(in),residual);}
                groups.add(new mmm.granular.GranularTransferLedger.Portion(new GranularTransferLedger.Group(material,volume,mass,mixture),in.readDouble()));
            }
            segments.add(new PipeSegmentState(id,elevation,capacity,gas,energy,groups));
        }
        var rows=new ArrayList<List<LiquidCellInventory.Stock>>();int total=0;
        for(int cell=0;cell<size;cell++) {
            int n=count(in,MAX_ROWS);total=Math.addExact(total,n);if(total>MAX_ROWS)throw new IOException("Pipe liquid row budget exceeded");
            var row=new ArrayList<LiquidCellInventory.Stock>();
            for(int j=0;j<n;j++) {
                int material=in.readInt();var volume=readDyadic(in);long mass=in.readLong();double energy=in.readDouble();ChemicalMixtureRecord mixture=null;
                if(in.readBoolean()){long residual=in.readLong();mixture=ChemicalMixtureRecord.fromStableAmounts(chemistry,readSpecies(in),residual);}
                row.add(new LiquidCellInventory.Stock(new LiquidCellInventory.Key(cell,material,255),volume,mass,mixture,energy));
            }
            rows.add(row);
        }
        int edges=count(in,MAX_SEGMENTS*6);var connections=new ArrayList<PipeConnection>();
        for(int i=0;i<edges;i++)connections.add(new PipeConnection(in.readInt(),in.readInt(),in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble(),in.readDouble()));
        int installed=count(in,MAX_SEGMENTS);var installations=new TreeMap<String,PipeInstallation>();
        for(int i=0;i<installed;i++) {
            String id=in.readUTF(),block=in.readUTF();int n=count(in,64);var properties=new TreeMap<String,String>();
            for(int j=0;j<n;j++)if(properties.put(in.readUTF(),in.readUTF())!=null)throw new IOException("Duplicate pipe property");
            if(installations.put(id,new PipeInstallation(block,properties))!=null)throw new IOException("Duplicate pipe installation");
        }
        return new PipeDimensionState(generation,new PipeNetworkState(segments,connections,palette,new LiquidCellInventory.Snapshot(rows)),audit,installations);
    }
    private static Map<String,Long> readSpecies(DataInputStream in) throws IOException {
        int n=count(in,4096);var result=new TreeMap<String,Long>();
        for(int i=0;i<n;i++)if(result.put(in.readUTF(),in.readLong())!=null)throw new IOException("Duplicate pipe species");
        return result;
    }
    private static void writeGas(DataOutputStream out,GasInventory gas) throws IOException {
        out.writeInt(gas.components().size());
        for(var component:gas.components()) {
            out.writeUTF(component.componentId());out.writeLong(component.standardVolumeNanos());out.writeLong(component.massUnits());out.writeLong(component.massResidualUnits());
            out.writeInt(component.species().size());
            for(var species:component.species()){out.writeUTF(species.speciesId());out.writeLong(species.amountQuanta());}
        }
    }
    private static GasInventory readGas(DataInputStream in) throws IOException {
        int n=count(in,GasLimits.MAX_COMPONENTS_PER_NODE);var components=new ArrayList<GasComponentAmount>();
        for(int i=0;i<n;i++) {
            String id=in.readUTF();long volume=in.readLong(),mass=in.readLong(),residual=in.readLong();
            int species=count(in,GasLimits.MAX_SPECIES_PER_COMPONENT);var amounts=new ArrayList<GasComponentAmount.SpeciesAmount>();
            for(int j=0;j<species;j++)amounts.add(new GasComponentAmount.SpeciesAmount(in.readUTF(),in.readLong()));
            components.add(new GasComponentAmount(id,volume,mass,amounts,residual));
        }
        return new GasInventory(components);
    }
    private static void writeDyadic(DataOutputStream out,SignedDyadic value) throws IOException {
        byte[] bytes=value.significand().toByteArray();out.writeInt(value.exponent());out.writeInt(bytes.length);out.write(bytes);
    }
    private static SignedDyadic readDyadic(DataInputStream in) throws IOException {
        int exponent=in.readInt(),size=count(in,SignedDyadic.MAX_SIGNIFICAND_BITS/8+2);
        if(size==0)throw new IOException("Empty pipe quantity");var bytes=in.readNBytes(size);if(bytes.length!=size)throw new EOFException();
        return new SignedDyadic(new BigInteger(bytes),exponent);
    }
    private static int count(DataInputStream in,int maximum) throws IOException {
        int n=in.readInt();if(n<0||n>maximum)throw new IOException("Pipe checkpoint count outside budget");return n;
    }
}
