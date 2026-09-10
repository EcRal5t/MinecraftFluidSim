package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.chemistry.*;
import cc.ecisr.mmm.units.SolverUnitScale;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Candidate representation and retry cases shared by the chemical allocation gate. */
final class ChemicalReactionCandidateValidation {
	private ChemicalReactionCandidateValidation() { }

	static void validate() {
		evaluationBudgetRotates();
		ChemicalRegistry builtin = BuiltinChemicalDefinitions.REGISTRY;
		checkDeferred(builtin, List.of(
				mixture(builtin, BuiltinChemicalDefinitions.H_PLUS, 1, 0),
				mixture(builtin, BuiltinChemicalDefinitions.OH_MINUS, 1, 0)));
		checkDeferred(builtin, List.of(
				mixture(builtin, BuiltinChemicalDefinitions.HCL, 1_500_000_000_000_000L, 0)));
		checkDeferred(builtin, List.of(
				mixture(builtin, BuiltinChemicalDefinitions.H_PLUS, 2, -1.999e-9),
				mixture(builtin, BuiltinChemicalDefinitions.OH_MINUS, 1, -1.6999e-8)));
		ChemicalRegistry wide = products(17, ExactRational.ONE);
		checkDeferred(wide, List.of(mixture(wide, "test:source", 1, 0)));

		ChemicalRegistry tiny = products(2, ExactRational.parse("1/10000000000000000000000000000000000000000"));
		Fixture small = new Fixture(tiny, List.of(mixture(tiny, "test:source", 1, 0)), new float[]{0.5F});
		require(small.step().changedMarkers() == 1 && small.store.record(0).entryCount() == 2,
				"binary64 mass weights retain representable reaction progress");

		Fixture region = new Fixture(builtin, List.of(
				mixture(builtin, BuiltinChemicalDefinitions.HCL, 750_000_000_000_000L, 0),
				mixture(builtin, BuiltinChemicalDefinitions.HCL, 750_000_000_000_000L, 0)), new float[]{0.5F, 1.5F});
		require(region.step().changedMarkers() == 0, "combined region capacity preserves both candidates");
		// Reducing another cell's inventory makes the deferred result representable on the next call.
		region.store.assign(1, mixture(builtin, BuiltinChemicalDefinitions.H2O, 1, 0), false);
		region.mass[1] = region.store.record(1).massKilograms(builtin);
		require(region.step().changedMarkers() == 1 && !region.store.hasDirtyCells(),
				"region capacity retry commits after capacity becomes available");
	}

	private static void evaluationBudgetRotates() {
		List<ChemicalSpecies> species = new ArrayList<>();
		List<ChemicalReaction> reactions = new ArrayList<>();
		List<String> ids = new ArrayList<>();
		for (int i = 0; i < 256; i++) {
			String id = "test:s" + i;
			ids.add(id);
			species.add(new ChemicalSpecies(id, Map.of("A", 1), 0, ExactRational.ONE, 0));
			if (i > 0) reactions.add(new ChemicalReaction("test:r" + i, ChemicalReaction.Kind.INSTANTANEOUS,
					"test:phase", Map.of("test:s" + (i - 1), 1), Map.of(id, 1), ExactRational.ZERO));
		}
		ChemicalRegistry registry = new ChemicalRegistry(species, List.of(new ChemicalPhaseDefinition(
				"test:phase", "test:liquid", ids, Map.of("test:s0", ExactRational.ONE), false)), reactions);
		int count = ChemicalLimits.MAX_REACTION_EVALUATIONS_PER_STEP / reactions.size() + 1;
		ChemicalParcelStore store = new ChemicalParcelStore(registry, SolverUnitScale.forCellSize(1), count, count);
		float[] x = new float[count], zero = new float[count];
		double[] mass = new double[count];
		for (int i = 0; i < count; i++) {
			store.assign(i, mixture(registry, "test:s0", 1, 0), false);
			x[i] = i + 0.5F; mass[i] = store.record(i).massKilograms(registry); store.markDirtyCell(i);
		}
		var first = store.reactDirtyCells(count, 1, 1, 1, x, zero, zero, mass, new int[count], h -> "test:liquid", count);
		require(first.changedMarkers() == count - 1 && store.reactionCursor() == count - 1,
				"bounded chemical pass commits complete cells and saves the next cell");
		for (int i = 0; i < count - 1; i++) store.markDirtyCell(i);
		store.reactDirtyCells(count, 1, 1, 1, x, zero, zero, mass, new int[count], h -> "test:liquid", count);
		require(store.record(count - 1).amount(registry.speciesHandle("test:s255")) == 1L,
				"rotating budget reaches the tail even while earlier cells keep waking");
	}

	private static void checkDeferred(ChemicalRegistry registry, List<ChemicalMixtureRecord> source) {
		float[] positions = new float[source.size() + 1];
		Arrays.fill(positions, 0.5F);
		positions[source.size()] = 1.5F;
		ArrayList<ChemicalMixtureRecord> input = new ArrayList<>(source);
		boolean builtin = registry == BuiltinChemicalDefinitions.REGISTRY;
		input.add(mixture(registry, builtin ? BuiltinChemicalDefinitions.HCL : "test:p0", 10, 0));
		Fixture fixture = new Fixture(registry, input, positions);
		double[] originalMass = fixture.mass.clone();
		for (int pass = 0; pass < 2; pass++) {
			ChemicalParcelStore.ReactionStats stats = fixture.step();
			require(stats.changedMarkers() == (builtin && pass == 0 ? 1 : 0), "independent cell makes progress");
			for (int i = 0; i < source.size(); i++) require(fixture.store.record(i).equals(source.get(i))
					&& Double.doubleToRawLongBits(fixture.mass[i]) == Double.doubleToRawLongBits(originalMass[i]),
					"deferred reaction preserves source composition and mass");
			require(Arrays.equals(fixture.store.dirtyCells(), new int[]{0}), "only deferred cell remains scheduled");
		}
	}

	private static ChemicalMixtureRecord mixture(ChemicalRegistry registry, String id, long amount, double residual) {
		return ChemicalMixtureRecord.fromStableAmounts(registry, Map.of(id, amount), residual);
	}

	private static ChemicalRegistry products(int count, ExactRational mass) {
		ArrayList<ChemicalSpecies> species = new ArrayList<>();
		ArrayList<String> ids = new ArrayList<>();
		species.add(new ChemicalSpecies("test:source", Map.of("A", count), 0, mass.multiply(count), 0));
		ids.add("test:source");
		TreeMap<String, Integer> products = new TreeMap<>();
		for (int i = 0; i < count; i++) {
			String id = "test:p" + i;
			ids.add(id);
			species.add(new ChemicalSpecies(id, Map.of("A", 1), 0, mass, 0));
			products.put(id, 1);
		}
		return new ChemicalRegistry(species, List.of(new ChemicalPhaseDefinition("test:phase", "test:liquid",
				ids, Map.of("test:source", ExactRational.ONE), false)), List.of(new ChemicalReaction(
				"test:reaction", ChemicalReaction.Kind.INSTANTANEOUS, "test:phase",
				Map.of("test:source", 1), products, ExactRational.ZERO)));
	}

	private static final class Fixture {
		final ChemicalRegistry registry;
		final ChemicalParcelStore store;
		final float[] x;
		final double[] mass;
		Fixture(ChemicalRegistry registry, List<ChemicalMixtureRecord> input, float[] x) {
			this.registry = registry;
			this.x = x;
			mass = new double[input.size()];
			store = new ChemicalParcelStore(registry, SolverUnitScale.forCellSize(1), input.size(), 2);
			for (int i = 0; i < input.size(); i++) {
				store.assign(i, input.get(i), false);
				mass[i] = input.get(i).massKilograms(registry);
				store.markDirtyCell((int) x[i]);
			}
		}
		ChemicalParcelStore.ReactionStats step() {
			return store.reactDirtyCells(2, 1, 1, 1, x, new float[x.length], new float[x.length], mass,
					new int[x.length], ignored -> registry.phases().getFirst().materialId(), x.length);
		}
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
