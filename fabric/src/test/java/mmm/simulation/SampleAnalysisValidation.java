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
        String text = String.join("\n", sample.lines());
        require(sample.phase() == SampleAnalysisPayload.Phase.LIQUID, "incorrect sample phase");
        require(sample.fractions().size() == 2, "missing composition fractions");
        require(sample.fractions().stream().anyMatch(f -> Math.abs(f.value() - .8846153846) < 1e-6)
                && sample.fractions().stream().anyMatch(f -> Math.abs(f.value() - .1153846154) < 1e-6), "incorrect visual fractions");
        require(sample.fractions().stream().allMatch(f -> sample.lines().get(f.line()).startsWith("质量分数：")),
                "fraction metadata points to the wrong report line");
        try {
            new SampleAnalysisPayload(SampleAnalysisPayload.Phase.GAS, List.of("x"), List.of(new SampleAnalysisPayload.Fraction(1, .5)));
            throw new AssertionError("out-of-range fraction line accepted");
        } catch (IllegalArgumentException expected) { }
        require(text.contains("320.00 K"), "temperature did not use saved internal energy: " + text);
        require(text.contains("88.462% · 摩尔分数：75.000%"), "ethanol fractions use incorrect denominators: " + text);
        require(text.contains("11.538% · 摩尔分数：25.000%"), "water fractions use incorrect denominators: " + text);
        require(text.contains("6.0000 mol/m³") && text.contains("2.0000 mol/m³"), "concentration did not use total volume: " + text);
        require(net.minecraft.world.item.ItemStack.matches(before, stack), "analysis changed the held container");
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            SampleAnalysisPayload.CODEC.encode(buffer, sample);
            require(sample.equals(SampleAnalysisPayload.CODEC.decode(buffer)), "sample packet changed during round-trip");
        } finally { buffer.release(); }
        var oversized = new FriendlyByteBuf(Unpooled.buffer());
        try {
            oversized.writeUUID(new java.util.UUID(0,0)); oversized.writeUtf("minecraft:overworld"); oversized.writeVarInt(0); oversized.writeVarInt(2049);
            try { SampleAnalysisPayload.CODEC.decode(oversized); throw new AssertionError("oversized packet accepted"); }
            catch (IllegalArgumentException expected) { }
        } finally { oversized.release(); }
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
