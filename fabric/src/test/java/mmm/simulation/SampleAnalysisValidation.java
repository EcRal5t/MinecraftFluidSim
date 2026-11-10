package mmm.simulation;

import io.netty.buffer.Unpooled;
import java.nio.file.Files;
import java.util.EnumSet;
import java.util.List;
import mmm.chemistry.BuiltinChemicalDefinitions;
import mmm.chemistry.ChemicalLimits;
import mmm.item.ContainerItemStacks;
import mmm.network.SampleAnalysisPayload;
import mmm.transfer.ChemicalContents;
import mmm.units.Mass;
import mmm.worldprofile.WorldCapability;
import net.minecraft.network.FriendlyByteBuf;

/** Sample quantities are based on the whole container and its saved thermal energy. */
public final class SampleAnalysisValidation {
    private SampleAnalysisValidation() { }
    public static void main(String[] args) throws Exception {
        GpuControllerFixture.initializeItems();
        var definitions = ProductionRegistries.load();
        var startup = WorldStartupCoordinator.start(Files.createTempDirectory("mmm-sample-analysis-"),
                () -> new mmm.simulation.persistence.WorldCapabilityBundleStore.Definitions(
                        EnumSet.allOf(WorldCapability.class), definitions.material(), definitions.chemical(),
                        definitions.granular(), definitions.gas(), definitions.thermal()));
        if (startup.creationLocked()) throw new AssertionError(startup.blockingSummary());
        var world = new CommittedWorldRegistries(startup);
        var contents = new ChemicalContents(world.chemicalRegistry().fingerprint(), ChemicalLimits.QUANTA_PER_MOL,
                List.of(new ChemicalContents.Group("mmm:water", 500_000_000L, Mass.fromKilograms(.156), 0,
                        List.of(new ChemicalContents.SpeciesAmount(BuiltinChemicalDefinitions.ETHANOL, 3_000_000),
                                new ChemicalContents.SpeciesAmount(BuiltinChemicalDefinitions.H2O, 1_000_000)))));
        var stack = world.bindActualContainerProfile(ContainerItemStacks.toStack(contents));
        world.attachLiquidThermalContents(stack, 320);
        var before = stack.copy();
        var sample = SampleAnalysis.analyze(stack, world);
        require(sample.phase() == SampleAnalysisPayload.Phase.LIQUID, "incorrect sample phase");
        require(sample.species().size() == 2 && sample.materials().size() == 1, "missing typed composition rows");
        var ethanol = sample.species().stream().filter(row -> row.id().equals(BuiltinChemicalDefinitions.ETHANOL)).findFirst().orElseThrow();
        var water = sample.species().stream().filter(row -> row.id().equals(BuiltinChemicalDefinitions.H2O)).findFirst().orElseThrow();
        require(Math.abs(ethanol.massFraction() - .8846153846) < 1e-6 && Math.abs(water.massFraction() - .1153846154) < 1e-6,
                "incorrect mass fraction denominators");
        require(ethanol.moleFraction() == .75 && water.moleFraction() == .25, "incorrect mole fractions");
        require(Math.abs(sample.temperatureK() - 320) < .001, "temperature did not use saved internal energy");
        require(sample.volumeM3() == .5 && ethanol.amountQuanta() == 3_000_000 && water.amountQuanta() == 1_000_000,
                "concentration source did not retain whole-container volume and amounts");
        try {
            new SampleAnalysisPayload.Species("mmm:h2o", 1, 1.1, .5);
            throw new AssertionError("out-of-range mass fraction accepted");
        } catch (IllegalArgumentException expected) { }
        require(net.minecraft.world.item.ItemStack.matches(before, stack), "analysis changed the held container");
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            SampleAnalysisPayload.CODEC.encode(buffer, sample);
            require(sample.equals(SampleAnalysisPayload.CODEC.decode(buffer)), "sample packet changed during round-trip");
        } finally { buffer.release(); }
        var oversized = new FriendlyByteBuf(Unpooled.buffer());
        try {
            oversized.writeUUID(new java.util.UUID(0,0)); oversized.writeUtf("minecraft:overworld"); oversized.writeVarInt(0);
            for (int i = 0; i < 4; i++) oversized.writeDouble(1);
            oversized.writeBoolean(false); oversized.writeVarInt(513);
            try { SampleAnalysisPayload.CODEC.decode(oversized); throw new AssertionError("oversized packet accepted"); }
            catch (IllegalArgumentException expected) { }
        } finally { oversized.release(); }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
