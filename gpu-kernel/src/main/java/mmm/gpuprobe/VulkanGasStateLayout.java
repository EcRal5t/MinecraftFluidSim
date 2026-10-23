package mmm.gpuprobe;

import mmm.fluid.air.GasAuditLedger;
import mmm.fluid.air.GasComponentAmount;
import mmm.fluid.air.GasInventory;
import mmm.fluid.air.GasStateSnapshot;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.TreeSet;

/** Device-addressable gas inventory, topology, audit ownership and node internal energy.
 * Inventory rows [0,nodeCount) belong to nodes, row nodeCount is externalSource,
 * and row nodeCount+1 is escapedSink. Both nested row tables use CSR element offsets.
 * String identities and registry fingerprints belong to immutable Definitions.
 */
public final class VulkanGasStateLayout {
    public enum Field {
        TOPOLOGY_KEYS(4), PIECE_NODE_ORDINAL(4), ROW_COMPONENT_OFFSETS(4), COMPONENT_HANDLES(4),
        STANDARD_VOLUME_NANOS(8), MASS_UNITS(8), MASS_RESIDUAL_UNITS(8),
        COMPONENT_SPECIES_OFFSETS(4), SPECIES_HANDLES(4), SPECIES_AMOUNTS(8),
        ENERGY_PRESENT(4), INTERNAL_ENERGY(8);
        final int width;
        Field(int width) { this.width = width; }
        public int elementBytes() { return width; }
    }
    public record Column(long offset, int count, int elementBytes) {
        public long bytes() { return (long) count * elementBytes; }
        public long bindingBytes() { return Math.max(elementBytes, bytes()); }
    }
    public record Definitions(String registryFingerprint, String chemicalRegistryFingerprint,
                              List<String> componentIds, List<String> speciesIds) {
        public Definitions { componentIds = List.copyOf(componentIds); speciesIds = List.copyOf(speciesIds); }
    }
    /** Energy is indexed by the same canonical node ordinal as snapshot.nodes(). */
    public record State(GasStateSnapshot snapshot, double[] nodeEnergy) { }

    private final int sizeX, sizeY, sizeZ, nodes, components, species;
    private final Definitions definitions;
    private final long alignment, bytes;
    private final EnumMap<Field, Column> columns = new EnumMap<>(Field.class);

    public VulkanGasStateLayout(int sizeX, int sizeY, int sizeZ, int nodeCount, int componentCount,
                                int speciesCount, Definitions definitions, long storageAlignment) {
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0 || nodeCount < 0 || componentCount < 0 || speciesCount < 0
                || definitions == null || storageAlignment <= 0 || (storageAlignment & (storageAlignment - 1)) != 0)
            throw new IllegalArgumentException("Invalid resident gas layout");
        this.sizeX = sizeX; this.sizeY = sizeY; this.sizeZ = sizeZ;
        nodes = nodeCount; components = componentCount; species = speciesCount; this.definitions = definitions;
        alignment = Math.max(8, storageAlignment);
        long cursor = 0;
        for (Field field : Field.values()) {
            int count = switch (field) {
                case PIECE_NODE_ORDINAL -> Math.multiplyExact(8, Math.multiplyExact(Math.multiplyExact(sizeX,sizeY),sizeZ));
                case ROW_COMPONENT_OFFSETS -> Math.addExact(nodes, 3);
                case ENERGY_PRESENT -> 1;
                case COMPONENT_SPECIES_OFFSETS -> Math.addExact(components, 1);
                case COMPONENT_HANDLES, STANDARD_VOLUME_NANOS, MASS_UNITS, MASS_RESIDUAL_UNITS -> components;
                case SPECIES_HANDLES, SPECIES_AMOUNTS -> species;
                default -> nodes;
            };
            cursor = align(cursor);
            var column = new Column(cursor, count, field.width); columns.put(field, column);
            cursor = Math.addExact(cursor, column.bindingBytes());
        }
        bytes = align(cursor);
    }

    public static VulkanGasStateLayout forState(GasStateSnapshot snapshot, long storageAlignment) {
        var componentIds = new TreeSet<String>(); var speciesIds = new TreeSet<String>();
        int components = 0, species = 0;
        for (var row : rows(snapshot)) for (var entry : row) {
            components = Math.addExact(components, 1); componentIds.add(entry.componentId());
            species = Math.addExact(species, entry.species().size());
            for (var amount : entry.species()) speciesIds.add(amount.speciesId());
        }
        var definitions = new Definitions(snapshot.registryFingerprint(), snapshot.chemicalRegistryFingerprint(),
                List.copyOf(componentIds), List.copyOf(speciesIds));
        return new VulkanGasStateLayout(snapshot.sizeX(), snapshot.sizeY(), snapshot.sizeZ(), snapshot.nodes().size(),
                components, species, definitions, storageAlignment);
    }

    public long bytes() { return bytes; }
    public long alignment() { return alignment; }
    public int nodeCount() { return nodes; }
    public int componentCount() { return components; }
    public int speciesCount() { return species; }
    public int sourceRow() { return nodes; }
    public int sinkRow() { return nodes + 1; }
    public Definitions definitions() { return definitions; }
    public Column column(Field field) { return columns.get(field); }
    List<FlipKernelBindings.BufferRange> ranges(GpuBuffer buffer, Field... fields) {
        return java.util.Arrays.stream(fields).map(field -> {
            var column = column(field);
            return new FlipKernelBindings.BufferRange(buffer, column.offset(), column.bindingBytes());
        }).toList();
    }

    /** Imports matching ThermalOwnerSnapshot.copyGasNodeEnergy(), or null when thermal gas is unbound.
     * Buffer position is preserved; internal-energy bits round-trip without arithmetic. */
    public void importState(ByteBuffer destination, GasStateSnapshot snapshot, double[] nodeEnergy) {
        if (snapshot.sizeX()!=sizeX || snapshot.sizeY()!=sizeY || snapshot.sizeZ()!=sizeZ || snapshot.nodes().size()!=nodes
                || !snapshot.registryFingerprint().equals(definitions.registryFingerprint())
                || !snapshot.chemicalRegistryFingerprint().equals(definitions.chemicalRegistryFingerprint()))
            throw new IllegalArgumentException("Gas identity or dimensions differ from resident layout");
        var rows = rows(snapshot);
        int[] offsets = new int[nodes+3], handles = new int[components], speciesOffsets = new int[components+1];
        int[] speciesHandles = new int[species]; long[] nanos = new long[components], mass = new long[components];
        long[] residual = new long[components], amounts = new long[species]; int c=0,s=0;
        for (int r=0;r<rows.size();r++) {
            for (var entry : rows.get(r)) {
                if (c>=components) throw new IllegalArgumentException("Gas component count differs");
                handles[c]=handle(definitions.componentIds(),entry.componentId()); nanos[c]=entry.standardVolumeNanos();
                mass[c]=entry.massUnits(); residual[c]=entry.massResidualUnits();
                for (var amount : entry.species()) {
                    if(s>=species) throw new IllegalArgumentException("Gas species count differs");
                    speciesHandles[s]=handle(definitions.speciesIds(),amount.speciesId()); amounts[s++]=amount.amountQuanta();
                }
                speciesOffsets[++c]=s;
            }
            offsets[r+1]=c;
        }
        if(c!=components || s!=species) throw new IllegalArgumentException("Gas column counts differ");
        ByteBuffer out=view(destination);
        put(out,Field.TOPOLOGY_KEYS,snapshot.nodes().stream().mapToInt(GasStateSnapshot.Node::topologyKey).toArray());
        put(out,Field.PIECE_NODE_ORDINAL,snapshot.pieceNodeOrdinal()); put(out,Field.ROW_COMPONENT_OFFSETS,offsets);
        put(out,Field.COMPONENT_HANDLES,handles); put(out,Field.STANDARD_VOLUME_NANOS,nanos); put(out,Field.MASS_UNITS,mass);
        put(out,Field.MASS_RESIDUAL_UNITS,residual); put(out,Field.COMPONENT_SPECIES_OFFSETS,speciesOffsets);
        put(out,Field.SPECIES_HANDLES,speciesHandles); put(out,Field.SPECIES_AMOUNTS,amounts);
        put(out,Field.INTERNAL_ENERGY,nodeEnergy==null?new double[nodes]:nodeEnergy);
        put(out,Field.ENERGY_PRESENT,new int[]{nodeEnergy==null?0:1});
    }

    public State exportState(ByteBuffer source) {
        ByteBuffer in=view(source);
        int[] offsets=ints(in,Field.ROW_COMPONENT_OFFSETS), handles=ints(in,Field.COMPONENT_HANDLES);
        int[] speciesOffsets=ints(in,Field.COMPONENT_SPECIES_OFFSETS), speciesHandles=ints(in,Field.SPECIES_HANDLES);
        long[] nanos=longs(in,Field.STANDARD_VOLUME_NANOS),mass=longs(in,Field.MASS_UNITS),residual=longs(in,Field.MASS_RESIDUAL_UNITS);
        long[] amounts=longs(in,Field.SPECIES_AMOUNTS);int[] keys=ints(in,Field.TOPOLOGY_KEYS);
        var rows=new ArrayList<List<GasAuditLedger.Entry>>(nodes+2);
        for(int r=0;r<nodes+2;r++) {
            var row=new ArrayList<GasAuditLedger.Entry>();
            for(int c=offsets[r];c<offsets[r+1];c++) {
                var composition=new ArrayList<GasComponentAmount.SpeciesAmount>();
                for(int s=speciesOffsets[c];s<speciesOffsets[c+1];s++) composition.add(new GasComponentAmount.SpeciesAmount(
                        definitions.speciesIds().get(speciesHandles[s]),amounts[s]));
                row.add(new GasAuditLedger.Entry(definitions.componentIds().get(handles[c]),nanos[c],mass[c],composition,residual[c]));
            }
            rows.add(row);
        }
        var result=new ArrayList<GasStateSnapshot.Node>(nodes);
        for(int n=0;n<nodes;n++) result.add(new GasStateSnapshot.Node(keys[n],new GasInventory(rows.get(n).stream()
                .map(e->new GasComponentAmount(e.componentId(),e.standardVolumeNanos(),e.massUnits(),e.species(),e.massResidualUnits())).toList())));
        var snapshot=new GasStateSnapshot(sizeX,sizeY,sizeZ,definitions.registryFingerprint(),definitions.chemicalRegistryFingerprint(),
                result,ints(in,Field.PIECE_NODE_ORDINAL),new GasAuditLedger(rows.get(sourceRow())),new GasAuditLedger(rows.get(sinkRow())));
        double[] energy=null;
        if(ints(in,Field.ENERGY_PRESENT)[0]!=0) {
            energy=new double[nodes];at(in,Field.INTERNAL_ENERGY,nodes).asDoubleBuffer().get(energy);
        }
        return new State(snapshot,energy);
    }

    private static List<List<GasAuditLedger.Entry>> rows(GasStateSnapshot snapshot) {
        var result=new ArrayList<List<GasAuditLedger.Entry>>(snapshot.nodes().size()+2);
        for(var node:snapshot.nodes()) result.add(GasAuditLedger.of(node.inventory()).entries());
        result.add(snapshot.externalSource().entries());result.add(snapshot.escapedSink().entries());return result;
    }
    private static int handle(List<String> ids,String id) {
        int result=ids.indexOf(id);if(result<0)throw new IllegalArgumentException("Gas definition is absent: "+id);return result;
    }
    private ByteBuffer view(ByteBuffer buffer) {
        if(bytes>buffer.remaining())throw new IllegalArgumentException("Resident gas buffer is too small");
        return buffer.slice().order(ByteOrder.LITTLE_ENDIAN);
    }
    private ByteBuffer at(ByteBuffer buffer,Field field,int count) {
        var c=column(field);if(count!=c.count())throw new IllegalArgumentException("Gas column count differs: "+field);
        return buffer.slice(Math.toIntExact(c.offset()),Math.toIntExact(c.bytes())).order(ByteOrder.LITTLE_ENDIAN);
    }
    private void put(ByteBuffer b,Field f,int[] v){at(b,f,v.length).asIntBuffer().put(v);}
    private void put(ByteBuffer b,Field f,long[] v){at(b,f,v.length).asLongBuffer().put(v);}
    private void put(ByteBuffer b,Field f,double[] v){at(b,f,v.length).asDoubleBuffer().put(v);}
    private int[] ints(ByteBuffer b,Field f){int[] v=new int[column(f).count()];at(b,f,v.length).asIntBuffer().get(v);return v;}
    private long[] longs(ByteBuffer b,Field f){long[] v=new long[column(f).count()];at(b,f,v.length).asLongBuffer().get(v);return v;}
    private long align(long value){return Math.addExact(value,alignment-1)&-alignment;}
}
