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
		phaseTransferPreservesMomentum();
		carrierPropertiesFollowChemicalComposition();
		for (String mode : List.of("irreversible", "huge-capacity", "surface-budget", "marker-budget", "region-budget")) {
			ruleCandidate(mode);
		}
	}

	public static void main(String[] args) { validate(); }

	private static void carrierPropertiesFollowChemicalComposition() {
		var granular = BuiltinGranularDefinitions.createRegistry();
		var liquids = new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE);
		var domain = new FluidDomain(3, 3, 3,
				new FluidSolverConfig(0.5F, 1_000.0F, 0.0F, 0.05F, 100, 1.0e-5F, 0.45F, 0.002F, 0.8F), 216);
		domain.setVolume(1, 1, 1, 0.125F);
		domain.bindGranular(granular, liquids, BuiltinFluidMaterials.createRegistry());
		domain.bindChemistry(BuiltinChemicalDefinitions.REGISTRY);
		var base = ThermochemistryValidationScenarios.reactiveGranularThermalRegistry(liquids, granular);
		var offsets = base.speciesOffsets().stream().map(offset -> new cc.ecisr.mmm.thermal.ThermalSpeciesOffset(
				offset.id(), offset.speciesId(), offset.hostThermalMaterialId(),
				offset.speciesId().equals(BuiltinChemicalDefinitions.HCL) ? ExactRational.of(10_000) : ExactRational.ZERO)).toList();
		domain.bindThermal(new cc.ecisr.mmm.thermal.ThermalRegistry(base.materials(), offsets, List.of(),
				base.chemicalRegistryFingerprint(), base.granularRegistryFingerprint()), liquids, 300);
		int cell = domain.cellIndex(1, 1, 1);
		var before = domain.thermalBuoyancyProperties();
		double previousViscosity = before.viscosityPaPerS(cell), previousBuoyancy = before.relativeBuoyancy(cell);
		var originalEnergy = domain.exactInternalEnergy();
		long originalCarrierRevision = domain.carrierMutationRevision;
		var originalThermalRevision = domain.thermalState.thermalRevision();
		var finitePlan = domain.planFiniteThermalCellExchange(List.of(new FluidDomain.ThermalCellEnergyDelta(1, 1, 1, 1)));
		domain.setMarkerChemical(0, Map.of(BuiltinChemicalDefinitions.HCL, ChemicalLimits.QUANTA_PER_AU), false);
		require(domain.carrierMutationRevision == originalCarrierRevision
				&& domain.thermalState.matchesThermalRevision(originalThermalRevision),
				"chemical edit fixture retains carrier structure and thermal owner state");
		boolean staleRejected = false;
		try { domain.commitFiniteThermalCellExchange(finitePlan); }
		catch (IllegalArgumentException stale) { staleRejected = true; }
		require(staleRejected && domain.exactInternalEnergy().equals(originalEnergy),
				"finite cell plan must reject changed species reference energy before writing U");
		var edited = domain.thermalBuoyancyProperties();
		double temperature = domain.thermalState.temperature(0, domain.thermalMarkerContext());
		require(edited.viscosityPaPerS(cell) != previousViscosity && edited.relativeBuoyancy(cell) != previousBuoyancy
				&& edited.viscosityPaPerS(cell) == BuiltinFluidMaterials.createRegistry().dynamicViscosityAt("mmm:water", temperature),
				"fixed-energy species edits must refresh carrier temperature, viscosity and buoyancy");
		require(domain.runThermalReactionPhaseAxis(false, 0).chemistry().changedMarkers() == 1,
				"cache fixture must commit the chemical reaction");
		var reacted = domain.thermalBuoyancyProperties();
		require(reacted.viscosityPaPerS(cell) == previousViscosity && reacted.relativeBuoyancy(cell) == previousBuoyancy,
				"reaction commit refreshes carrier temperature and buoyancy with unchanged total energy");
		domain.setMarkerChemical(0, Map.of(BuiltinChemicalDefinitions.HCL, ChemicalLimits.QUANTA_PER_AU), false);
		domain.thermalBuoyancyProperties();
		// A planned composition replacement also changes the state function while retaining U and structure.
		var water = ChemicalMixtureRecord.fromStableAmounts(BuiltinChemicalDefinitions.REGISTRY,
				Map.of(BuiltinChemicalDefinitions.H2O, ChemicalLimits.QUANTA_PER_AU), 0);
		water = water.withMassResidualKilograms(domain.unitScale.kilograms(domain.particles.mass(0))
				- water.exactSpeciesMassKilograms(BuiltinChemicalDefinitions.REGISTRY).toDouble());
		domain.chemicalParcels.commitPlannedReplacements(new int[]{0}, new ChemicalMixtureRecord[]{water});
		var replaced = domain.thermalBuoyancyProperties();
		require(replaced.viscosityPaPerS(cell) == previousViscosity && replaced.relativeBuoyancy(cell) == previousBuoyancy
				&& domain.exactInternalEnergy().equals(originalEnergy),
				"atomic species replacement refreshes carrier properties with conserved internal energy");
	}

	private static void phaseTransferPreservesMomentum() {
		var registry = BuiltinGranularDefinitions.createRegistry();
		var domain = new FluidDomain(3, 3, 3,
				new FluidSolverConfig(0.5F, 1_000.0F, 0.0F, 0.05F, 100, 1.0e-5F, 0.45F, 0.002F, 0.8F), 216);
		domain.setVolume(1, 1, 1, 0.25F);
		domain.bindGranular(registry, new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE),
				BuiltinFluidMaterials.createRegistry());
		domain.bindChemistry(BuiltinChemicalDefinitions.REGISTRY);
		domain.setPrecipitationEnabled(true);
		require(domain.particleCount() >= 2, "momentum fixture requires distinct liquid carrier velocities");
		for (int marker = 0; marker < domain.particleCount(); marker++) {
			domain.setMarkerChemical(marker, Map.of(BuiltinChemicalDefinitions.NA_PLUS, 8_320_000L,
					BuiltinChemicalDefinitions.CL_MINUS, 8_320_000L), false);
			domain.particles.setVelocityX(marker, marker % 2 == 0 ? 2 : -1);
			domain.particles.setVelocityY(marker, marker % 2 == 0 ? -3 : 1);
			domain.particles.setVelocityZ(marker, marker % 2 == 0 ? 1 : -2);
		}
		double[] before = momentumAndEnergy(domain);
		require(domain.runThermalReactionPhaseAxis(false, 0).granularPhase().precipitations() == 1,
				"momentum fixture must actually precipitate");
		requireMomentumAndDissipation(before, momentumAndEnergy(domain));
		for (int marker = 0; marker < domain.particleCount(); marker++) setWater(domain, marker, 100_000L);
		var solid = domain.granularState.parcels.parcel(0);
		domain.granularState.parcels.overwrite(0, new GranularParcelStore.Parcel(
				solid.x(), solid.y(), solid.z(), -4, 3, 2, solid.solidVolumeSvq(), solid.mass(),
				solid.materialHandle(), solid.solidCompositionHandle(), 0, (byte) 0));
		before = momentumAndEnergy(domain);
		require(domain.runThermalReactionPhaseAxis(false, 0).granularPhase().dissolutions() == 1,
				"momentum fixture must actually dissolve moving solid");
		requireMomentumAndDissipation(before, momentumAndEnergy(domain));
	}

	private static double[] momentumAndEnergy(FluidDomain domain) {
		double[] result = new double[4];
		for (int marker = 0; marker < domain.particleCount(); marker++) addMomentumAndEnergy(result,
				domain.unitScale.kilograms(domain.particles.mass(marker)), domain.particles.velocityX(marker),
				domain.particles.velocityY(marker), domain.particles.velocityZ(marker));
		for (int parcel = 0; parcel < domain.granularState.parcels.count(); parcel++) {
			var solid = domain.granularState.parcels.parcel(parcel);
			addMomentumAndEnergy(result, solid.mass(), solid.velocityX(), solid.velocityY(), solid.velocityZ());
		}
		return result;
	}

	private static void addMomentumAndEnergy(double[] result, double mass, double x, double y, double z) {
		result[0] += mass * x; result[1] += mass * y; result[2] += mass * z;
		result[3] += mass * (x * x + y * y + z * z) / 2;
	}

	private static void requireMomentumAndDissipation(double[] before, double[] after) {
		for (int axis = 0; axis < 3; axis++) {
			double tolerance = 8 * Math.ulp((float) Math.max(Math.abs(before[axis]), Math.abs(after[axis])));
			require(Math.abs(before[axis] - after[axis]) <= tolerance,
					"solid/liquid phase transfer preserves momentum axis " + axis + ": " + before[axis] + " -> " + after[axis]);
		}
		require(after[3] <= before[3] + 8 * Math.ulp((float) before[3]),
				"solid/liquid mass mixing cannot increase kinetic energy");
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
		domain.bindGranular(registry, new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE), BuiltinFluidMaterials.createRegistry());
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
