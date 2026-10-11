package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.chemistry.*;
import cc.ecisr.mmm.thermal.ThermalMaterialDefinition;
import cc.ecisr.mmm.thermal.ThermalRegistry;
import cc.ecisr.mmm.thermal.ThermalSpeciesOffset;
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
		reactionMassTransfersMomentum();
		reactionRetainsActualMassAndDefersUnrepresentableEnergy();
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

	private static void reactionRetainsActualMassAndDefersUnrepresentableEnergy() {
		for (int sample = 0; sample < 4; sample++) {
			boolean energyUnavailable = sample == 3;
			double density = sample == 0 ? 1000.1 : sample == 1 ? 1000.3 : 1024;
			String a = "test:identity/a", b = "test:identity/b", liquidId = "test:identity/liquid", thermalId = "test:identity/thermal";
			var chemistry = new ChemicalRegistry(List.of(
					new ChemicalSpecies(a, Map.of("X", 1), 0, ExactRational.of(3), 0),
					new ChemicalSpecies(b, Map.of("X", 1), 0, ExactRational.of(3), 0)), List.of(
					new ChemicalPhaseDefinition("test:basephase", "test:base", List.of(a, b), Map.of(a, ExactRational.ONE), false),
					new ChemicalPhaseDefinition("test:phase", liquidId, List.of(a, b), Map.of(a, ExactRational.ONE), false)), List.of(
					new ChemicalReaction("test:isomer", ChemicalReaction.Kind.INSTANTANEOUS, "test:phase",
							Map.of(a, 1), Map.of(b, 1), ExactRational.ZERO)));
			var liquids = new FluidMaterialRegistry(List.of(FluidMaterial.withSurfaceTension("test:base", 64, "test:miscible", 0, 90),
					FluidMaterial.withSurfaceTension(liquidId, density, "test:miscible", 0, 90)));
			var domain = new FluidDomain(3, 3, 3, new FluidSolverConfig(.5F, 64, 0, .05F, 100, 1e-5F, .45F, .002F, 0), 64);
			domain.bindMaterialRegistry(liquids); domain.setVolume(1, 1, 1, .125F);
			domain.setMarkerMaterial(0, liquids, 1); domain.bindChemistry(chemistry);
			long amount = 16 * ChemicalLimits.QUANTA_PER_AU;
			domain.setMarkerChemical(0, Map.of(a, amount), false);
			var thermal = new ThermalRegistry(List.of(
					identityThermalMaterial("test:base_thermal", "test:base"), identityThermalMaterial(thermalId, liquidId)), List.of(
					new ThermalSpeciesOffset("test:baseoa", a, "test:base_thermal", ExactRational.ZERO),
					new ThermalSpeciesOffset("test:baseob", b, "test:base_thermal", ExactRational.ZERO),
					new ThermalSpeciesOffset("test:oa", a, thermalId, ExactRational.ZERO),
					new ThermalSpeciesOffset("test:ob", b, thermalId,
							energyUnavailable ? ExactRational.parse("1" + "0".repeat(308)) : ExactRational.ZERO)),
					List.of(), chemistry.fingerprint(), "");
			domain.bindThermal(thermal, liquids, 300); domain.captureAuthoritativeState();
			long massBits = Double.doubleToRawLongBits(domain.particles.mass(0));
			var initial = domain.chemicalParcels.record(0); var energy = domain.exactInternalEnergy(); var audit = domain.thermalAudit();
			for (int pass = 0; pass < 2; pass++) {
				var result = domain.runThermalReactionPhaseAxis(false, 0).chemistry();
				var actual = domain.chemicalParcels.record(0);
				require(result.changedMarkers() == (energyUnavailable || pass == 1 ? 0 : 1)
						&& domain.chemicalParcels.hasDirtyCells() == energyUnavailable,
						"Reaction representation changed progress or lost its retry state");
				require(Double.doubleToRawLongBits(domain.particles.mass(0)) == massBits
						&& actual.massResidualKilogramsBits() == initial.massResidualKilogramsBits()
						&& actual.exactSpeciesMassKilograms(chemistry).equals(initial.exactSpeciesMassKilograms(chemistry))
						&& actual.amount(chemistry.speciesHandle(energyUnavailable ? a : b)) == amount
						&& domain.exactInternalEnergy().equals(energy) && domain.thermalAudit().equals(audit),
						"Reaction altered actual mass, residual or energy despite unchanged exact species mass");
				domain.captureAuthoritativeState();
			}
		}
	}

	private static ThermalMaterialDefinition identityThermalMaterial(String id, String carrier) {
		return new ThermalMaterialDefinition(id,
				ThermalMaterialDefinition.CarrierKind.LIQUID, carrier, 300, 0, 0,
				List.of(new ThermalMaterialDefinition.CaloricSegment(100, 500, 1000)));
	}

	private static void reactionMassTransfersMomentum() {
		var random = new java.util.Random(0x4d415353L);
		for (float cellSize : new float[]{0.25F, 0.5F, 1.0F}) for (int sample = 0; sample < 64; sample++) {
			long acid = sample == 0 ? 2L : 2L + random.nextLong(10_000_000_000L);
			long base = sample == 0 ? 2L : 2L + random.nextLong(10_000_000_000L);
			float[][] velocity = {{10, -3, 2}, {0, 1, -4}};
			if (sample != 0) for (float[] v : velocity) for (int axis = 0; axis < 3; axis++) {
				v[axis] = (random.nextFloat() - 0.5F) * 2_000;
			}
			for (boolean reverse : new boolean[]{false, true}) {
				var domain = new FluidDomain(3, 3, 3, new FluidSolverConfig(
						cellSize, 1_000, 0, 0.05F, 100, 1e-5F, 0.45F, 0.002F, 0.8F), 216);
				domain.setVolume(1, 1, 1, 0.25F);
				domain.bindMaterialRegistry(new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE));
				domain.bindChemistry(BuiltinChemicalDefinitions.REGISTRY);
				require(domain.particleCount() == 2, "reaction momentum fixture requires two carriers");
				for (int marker = 0; marker < 2; marker++) {
					int source = reverse ? 1 - marker : marker;
					var record = mixture(BuiltinChemicalDefinitions.REGISTRY,
							source == 0 ? BuiltinChemicalDefinitions.H_PLUS : BuiltinChemicalDefinitions.OH_MINUS,
							source == 0 ? acid : base, sample % 2 == 0 ? 0 : source * 0.001);
					domain.chemicalParcels.assign(marker, record, false);
					domain.particles.setMass(marker, domain.unitScale.solverMass(record.massKilograms(BuiltinChemicalDefinitions.REGISTRY)));
					domain.particles.setVelocityX(marker, velocity[source][0]);
					domain.particles.setVelocityY(marker, velocity[source][1]);
					domain.particles.setVelocityZ(marker, velocity[source][2]);
				}
				domain.chemicalParcels.markDirtyCell(13);
				double[] before = momentumEnergyAndScale(domain);
				require(domain.runThermalReactionPhaseAxis(false, 0).chemistry().changedMarkers() == 2,
						"momentum fixture must commit the reaction allocation");
				double[] after = momentumEnergyAndScale(domain);
				for (int axis = 0; axis < 3; axis++) require(Math.abs(after[axis] - before[axis])
						<= 8 * Math.ulp((float) Math.max(before[4], after[4])),
						"reaction mass allocation preserves momentum at cellSize " + cellSize + " sample " + sample);
				require(after[3] <= before[3] + 8 * Math.ulp((float) before[3]),
						"reaction mass mixing cannot increase kinetic energy");
			}
		}
	}

	private static double[] momentumEnergyAndScale(FluidDomain domain) {
		double[] result = new double[5];
		for (int marker = 0; marker < domain.particleCount(); marker++) {
			double mass = domain.unitScale.kilograms(domain.particles.mass(marker));
			double[] v = {domain.particles.velocityX(marker), domain.particles.velocityY(marker), domain.particles.velocityZ(marker)};
			for (int axis = 0; axis < 3; axis++) {
				result[axis] += mass * v[axis];
				result[3] += mass * v[axis] * v[axis] / 2;
				result[4] += Math.abs(mass * v[axis]);
			}
		}
		return result;
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
