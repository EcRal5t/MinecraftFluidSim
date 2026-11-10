package mmm.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import mmm.MmmMod;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Quantities from one held container; formatting belongs to the client. */
public record SampleAnalysisPayload(UUID session, String dimension, Phase phase,
        double volumeM3, double massKg, double temperatureK, double internalEnergyJ,
        boolean completeSpecies, List<Material> materials, List<Species> species) implements CustomPacketPayload {
    private static final int MAX_ROWS = 512;
    public enum Phase { LIQUID, GAS, SOLID }
    public record Material(String id, double volumeM3, double massKg) {
        public Material { identifier(id); nonnegative(volumeM3); nonnegative(massKg); }
    }
    public record Species(String id, long amountQuanta, double massFraction, double moleFraction) {
        public Species {
            identifier(id);
            if (amountQuanta < 0) throw new IllegalArgumentException("Negative sample amount");
            fraction(massFraction); fraction(moleFraction);
        }
    }
    public static final Type<SampleAnalysisPayload> TYPE = new Type<>(MmmMod.id("sample_analysis_v3"));
    public static final StreamCodec<FriendlyByteBuf, SampleAnalysisPayload> CODEC = StreamCodec.of(
            SampleAnalysisPayload::encode, SampleAnalysisPayload::decode);
    public SampleAnalysisPayload {
        Objects.requireNonNull(session); Objects.requireNonNull(dimension); Objects.requireNonNull(phase);
        if (dimension.length() > 256) throw new IllegalArgumentException("Sample dimension too long");
        nonnegative(volumeM3); nonnegative(massKg);
        if (!(Double.isNaN(temperatureK) || Double.isFinite(temperatureK) && temperatureK > 0)
                || Double.isInfinite(internalEnergyJ)) throw new IllegalArgumentException("Invalid sample thermal state");
        materials = List.copyOf(materials); species = List.copyOf(species);
        if (materials.size() > MAX_ROWS || species.size() > MAX_ROWS)
            throw new IllegalArgumentException("Sample exceeds row budget");
        if (materials.stream().map(Material::id).distinct().count() != materials.size()
                || species.stream().map(Species::id).distinct().count() != species.size())
            throw new IllegalArgumentException("Duplicate sample component");
    }
    public SampleAnalysisPayload(Phase phase, double volumeM3, double massKg, double temperatureK, double internalEnergyJ,
            boolean completeSpecies, List<Material> materials, List<Species> species) {
        this(new UUID(0, 0), "", phase, volumeM3, massKg, temperatureK, internalEnergyJ, completeSpecies, materials, species);
    }
    public SampleAnalysisPayload withSession(UUID session, String dimension) {
        return new SampleAnalysisPayload(session, dimension, phase, volumeM3, massKg, temperatureK, internalEnergyJ,
                completeSpecies, materials, species);
    }
    public static SampleAnalysisPayload empty(Phase phase) {
        return new SampleAnalysisPayload(phase, 0, 0, Double.NaN, Double.NaN, false, List.of(), List.of());
    }
    @Override public Type<SampleAnalysisPayload> type() { return TYPE; }
    private static void encode(FriendlyByteBuf b, SampleAnalysisPayload p) {
        b.writeUUID(p.session); b.writeUtf(p.dimension, 256); b.writeEnum(p.phase);
        b.writeDouble(p.volumeM3); b.writeDouble(p.massKg); b.writeDouble(p.temperatureK); b.writeDouble(p.internalEnergyJ);
        b.writeBoolean(p.completeSpecies); b.writeVarInt(p.materials.size());
        for (var m : p.materials) { b.writeUtf(m.id, 128); b.writeDouble(m.volumeM3); b.writeDouble(m.massKg); }
        b.writeVarInt(p.species.size());
        for (var s : p.species) { b.writeUtf(s.id, 128); b.writeVarLong(s.amountQuanta); b.writeDouble(s.massFraction); b.writeDouble(s.moleFraction); }
    }
    private static SampleAnalysisPayload decode(FriendlyByteBuf b) {
        var session = b.readUUID(); String dimension = b.readUtf(256); Phase phase = b.readEnum(Phase.class);
        double volume = b.readDouble(), mass = b.readDouble(), temperature = b.readDouble(), energy = b.readDouble();
        boolean complete = b.readBoolean();
        int count = count(b);
        var materials = new ArrayList<Material>(count);
        for (int i = 0; i < count; i++) materials.add(new Material(b.readUtf(128), b.readDouble(), b.readDouble()));
        count = count(b);
        var species = new ArrayList<Species>(count);
        for (int i = 0; i < count; i++) species.add(new Species(b.readUtf(128), b.readVarLong(), b.readDouble(), b.readDouble()));
        return new SampleAnalysisPayload(session, dimension, phase, volume, mass, temperature, energy, complete, materials, species);
    }
    private static int count(FriendlyByteBuf b) {
        int value = b.readVarInt();
        if (value < 0 || value > MAX_ROWS) throw new IllegalArgumentException("Invalid sample row count");
        return value;
    }
    private static void identifier(String id) {
        if (id == null || id.isBlank() || id.length() > 128) throw new IllegalArgumentException("Invalid sample component id");
    }
    private static void nonnegative(double value) {
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException("Invalid sample quantity");
    }
    private static void fraction(double value) {
        if (!(Double.isNaN(value) || Double.isFinite(value) && value >= 0 && value <= 1))
            throw new IllegalArgumentException("Invalid sample fraction");
    }
}
