package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.chemistry.*;
import cc.ecisr.mmm.granular.*;
import cc.ecisr.mmm.units.SolverUnitScale;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Representation, direction and resource checks used by the existing solid/liquid transfer gate. */
public final class GranularReactionCandidateValidation {
	private GranularReactionCandidateValidation() { }

	public static void validate() {
		allocationCases();
		for (String mode : List.of("irreversible", "huge-capacity", "surface-budget", "marker-budget", "region-budget")) {
			ruleCandidate(mode);
		}
	}

	private static void ruleCandidate(String mode) {
		var chemistry = BuiltinChemicalDefinitions.REGISTRY;
		var builtin = BuiltinGranularDefinitions.createRegistry();
		var original = builtin.reactions().getFirst();
		var rule = new SolidLiquidReaction(original.id(), original.liquidPhaseId(), original.aqueousStoichiometry(),
				original.solidMaterialId(), original.solidComposition(), mode.equals("huge-capacity")
				? ExactRational.parse("1000000000000000000000000000000") : original.saturationCapacityExtentPerBv(),
				original.solidExtentPerBatch(), original.solidVolumeSvqPerBatch(),
				mode.equals("surface-budget") ? 1L : original.accessibleSurfaceSvqPerCellStep(),
				!mode.equals("irreversible"));
		var registry = new GranularRegistry(builtin.materials(), builtin.carriers(), List.of(rule), chemistry);
		var domain = new FluidDomain(3, 3, 3,
				new FluidSolverConfig(0.5F, 1_000.0F, 0.0F, 0.05F, 100, 1.0e-5F, 0.45F, 0.002F, 0.8F), 216);
		domain.setVolume(1, 1, 1, 0.1F);
		if (mode.equals("region-budget")) domain.setVolume(2, 1, 1, 0.1F);
		domain.bindGranular(registry, new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE));
		domain.bindChemistry(chemistry);
		domain.setPrecipitationEnabled(true);
		long amount = mode.equals("marker-budget") ? ChemicalLimits.MAX_REGION_TOTAL_QUANTA
				: mode.equals("region-budget") ? ChemicalLimits.MAX_REGION_TOTAL_QUANTA / 2 : 100_000L;
		for (int marker = 0; marker < domain.particleCount(); marker++) setWater(domain, marker, amount);
		var composition = SolidCompositionRecord.fromStableAmounts(chemistry, Map.of(
				BuiltinChemicalDefinitions.NA_PLUS, 17_984L, BuiltinChemicalDefinitions.CL_MINUS, 17_984L), 0.0);
		int handle = domain.granularState.compositions.create(composition);
		int material = registry.materialHandle(rule.solidMaterialId());
		domain.granularState.parcels.add(new GranularParcelStore.Parcel(0.75F, 0.75F, 0.75F, 0, 0, 0,
				50L, composition.totalMass(chemistry), material, handle, 0, (byte) 0));
		domain.chemicalParcels.markDirtyCell(13);
		var before = domain.chemicalParcels.record(0);
		var result = domain.runThermalReactionPhaseAxis(false, 0);
		require(result.granularPhase().dissolutions() == (mode.equals("huge-capacity") ? 1 : 0),
				"rule capacity and direction govern the commit");
		if (mode.equals("marker-budget") || mode.equals("region-budget")) {
			require(domain.chemicalParcels.record(0).equals(before)
							&& Arrays.equals(domain.chemicalParcels.dirtyCells(), new int[]{13}),
					"deferred dissolution retains source inventory and scheduling");
			setWater(domain, mode.equals("region-budget") ? 1 : 0, 100_000L);
			require(domain.runThermalReactionPhaseAxis(false, 0).granularPhase().dissolutions() == 1,
					"dissolution resumes when marker and region capacity are available");
		}
		domain.chemicalParcels.restoreReactionCursor(13);
		require(FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState())
						.chemicalParcels().reactionCursor() == 13,
				"backend handoff preserves the chemical scheduling cursor");
	}

	private static void setWater(FluidDomain domain, int marker, long amount) {
		var mixture = ChemicalMixtureRecord.fromStableAmounts(domain.chemicalParcels.registry(),
				Map.of(BuiltinChemicalDefinitions.H2O, amount), 0.0);
		domain.chemicalParcels.assign(marker, mixture, false);
		domain.particles.setMass(marker, domain.unitScale.solverMass(mixture.massKilograms(domain.chemicalParcels.registry())));
	}

	private static void allocationCases() {
		SolverUnitScale unitScale = SolverUnitScale.forCellSize(0.5F);
		ChemicalMixtureRecord clusteredMixture = ChemicalMixtureRecord.fromStableAmounts(
				BuiltinChemicalDefinitions.REGISTRY, java.util.Map.of(
						BuiltinChemicalDefinitions.NA_PLUS, 10_000_000L,
						BuiltinChemicalDefinitions.CL_MINUS, 10_000_000L), 0.0);
		SolidLiquidReaction clusteredRule = new SolidLiquidReaction("test:clustered_precipitation",
				BuiltinChemicalDefinitions.AQUEOUS, java.util.Map.of(
						BuiltinChemicalDefinitions.NA_PLUS, 64,
						BuiltinChemicalDefinitions.CL_MINUS, 64), BuiltinGranularDefinitions.PRECIPITATE,
				java.util.Map.of(BuiltinChemicalDefinitions.NA_PLUS, 64,
						BuiltinChemicalDefinitions.CL_MINUS, 64), ExactRational.ZERO,
				281L, 50L, 4_096L, true);
		double clusteredMass = clusteredMixture.massKilograms(BuiltinChemicalDefinitions.REGISTRY);
		GranularPhaseTransferEngine.Plan clusteredPlan = new GranularPhaseTransferEngine(
				BuiltinChemicalDefinitions.REGISTRY, unitScale).plan(clusteredRule, List.of(
				new GranularPhaseTransferEngine.LiquidParcel(
						0, 1.1F, unitScale.solverMass(clusteredMass), clusteredMixture),
				new GranularPhaseTransferEngine.LiquidParcel(
						1, 1.1F, unitScale.solverMass(clusteredMass), clusteredMixture)), 0L, 22_761L);
		long clusteredLiquidDelta = clusteredPlan.markerUpdates().stream().mapToLong(update ->
				Math.round(Math.scalb(1.1 - update.cellVolume(), 24))).sum();
		require(clusteredPlan.changed() && clusteredPlan.markerUpdates().stream().allMatch(update -> {
			float before = 1.1F;
			long delta = Math.round(Math.scalb((double) before - update.cellVolume(), 24));
			return delta > 0L && SolidVolume.addCellVolumeSvqExact(before, -delta) == update.cellVolume();
		}) && clusteredLiquidDelta == clusteredPlan.signedSolidSvq(),
				"clustered liquid did not choose an exact marker-representable precipitation allocation");
		ChemicalMixtureRecord oneSodium = ChemicalMixtureRecord.fromStableAmounts(
				BuiltinChemicalDefinitions.REGISTRY,
				java.util.Map.of(BuiltinChemicalDefinitions.NA_PLUS, 1L), 0.0);
		SolidLiquidReaction sparseRule = new SolidLiquidReaction("test:sparse_precipitation",
				BuiltinChemicalDefinitions.AQUEOUS,
				java.util.Map.of(BuiltinChemicalDefinitions.NA_PLUS, 1),
				BuiltinGranularDefinitions.PRECIPITATE,
				java.util.Map.of(BuiltinChemicalDefinitions.NA_PLUS, 1), ExactRational.ZERO,
				1L, 1L, 1L, true);
		double sodiumMass = oneSodium.massKilograms(BuiltinChemicalDefinitions.REGISTRY);
		GranularPhaseTransferEngine.Plan sparsePlan = new GranularPhaseTransferEngine(
				BuiltinChemicalDefinitions.REGISTRY, unitScale).plan(sparseRule, List.of(
				new GranularPhaseTransferEngine.LiquidParcel(
						0, 0.125F, unitScale.solverMass(sodiumMass), oneSodium),
				new GranularPhaseTransferEngine.LiquidParcel(
						1, 0.125F, unitScale.solverMass(sodiumMass), oneSodium)), 0L, 1L);
		require(!sparsePlan.changed() && sparsePlan.retryRequired(),
				"quanta-starved phase-transfer candidate should defer without pausing the valid base");
 }
 private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
