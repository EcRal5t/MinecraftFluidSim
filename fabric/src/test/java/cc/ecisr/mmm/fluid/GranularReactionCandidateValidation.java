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
		phaseTransferPreservesUnchangedMass();
		phaseTransferRequiresActualMassChange();
		phaseTransferPreservesMomentum();
		precipitationPreservesAvailableMassMoment();
		precipitationUsesCompleteThermalInventory();
		precipitationPreflightsRawThermalAssignment();
		precipitationMergePreservesMassMoment();
		precipitationMergeRetainsRotationalMotion();
		phaseTransferFitsLiquidAndSolidKernel();
		carrierPropertiesFollowChemicalComposition();
		for (String mode : List.of("irreversible", "huge-capacity", "surface-budget", "marker-budget", "region-budget")) {
			ruleCandidate(mode);
		}
	}

	public static void main(String[] args) { validate(); }

	private static void phaseTransferPreservesUnchangedMass() {
		var chemistry = BuiltinChemicalDefinitions.REGISTRY;
		var scale = SolverUnitScale.forCellSize(.5F);
		var first = ChemicalMixtureRecord.fromStableAmounts(chemistry, Map.of(
				BuiltinChemicalDefinitions.NA_PLUS, 10_000_000_000L, BuiltinChemicalDefinitions.CL_MINUS, 10_000_000_000L), 0);
		double firstMass = scale.solverMass(first.massKilograms(chemistry));
		double unchangedMass = 57.426859368108516;
		var palette = new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE);
		var domain = new FluidDomain(3, 3, 3,
				new FluidSolverConfig(.5F, 1000, 0, .05F, 100, 1E-5F, .45F, .002F, 0), 64);
		domain.restoreMaterialParticleState(new FlipMaterialParticleState(3, 3, 3, .5F, 2, .25,
				cc.ecisr.mmm.units.SignedDyadic.sum(new double[]{firstMass, unchangedMass}).toDouble(), palette.materials(),
				new float[]{.75F, .75F, .75F, .125F, .75F, .75F, .75F, .125F}, new float[6], new byte[27],
				new double[]{firstMass, unchangedMass}, new int[]{0, 0}));
		domain.bindGranular(BuiltinGranularDefinitions.createRegistry(), domain.materialRegistry(),
				BuiltinFluidMaterials.createRegistry());
		domain.bindChemistry(chemistry);
		domain.setMarkerChemical(0, first.stableAmounts(chemistry), false);
		domain.setMarkerChemical(1, Map.of(BuiltinChemicalDefinitions.NA_PLUS, 1_000_000_000L,
				BuiltinChemicalDefinitions.CL_MINUS, 1_000_000_000L), false);
		FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
		var second = domain.chemicalParcels.record(1);
		var rule = new SolidLiquidReaction("test:unchanged_mass", BuiltinChemicalDefinitions.AQUEOUS,
				Map.of(BuiltinChemicalDefinitions.NA_PLUS, 1, BuiltinChemicalDefinitions.CL_MINUS, 1),
				BuiltinGranularDefinitions.PRECIPITATE,
				Map.of(BuiltinChemicalDefinitions.NA_PLUS, 1, BuiltinChemicalDefinitions.CL_MINUS, 1),
				ExactRational.ZERO, 1, 1, 1, true);
		var plan = new GranularPhaseTransferEngine(chemistry, scale).plan(rule, List.of(
				new GranularPhaseTransferEngine.LiquidParcel(0, .125F, firstMass, domain.chemicalParcels.record(0)),
				new GranularPhaseTransferEngine.LiquidParcel(1, .125F, unchangedMass, second)), 0, 1);
		require(plan.changed() && plan.markerUpdates().get(1).mixture().equals(second)
				&& Double.doubleToRawLongBits(plan.markerUpdates().get(1).mass()) == Double.doubleToRawLongBits(unchangedMass),
				"zero species mass delta preserves the authoritative solver mass bit for bit");
		var delta = plan.markerUpdates().getFirst().mixture().exactSpeciesMassKilograms(chemistry)
				.subtract(domain.chemicalParcels.record(0).exactSpeciesMassKilograms(chemistry));
		var exactIncrement = new java.math.BigDecimal(delta.numerator()).divide(
				new java.math.BigDecimal(delta.denominator()).multiply(
						new java.math.BigDecimal(scale.cubicMetersPerNormalizedVolume())), new java.math.MathContext(100));
		double expectedMass = new java.math.BigDecimal(firstMass).add(exactIncrement).doubleValue();
		require(plan.markerUpdates().getFirst().mass() == expectedMass && domain.particles.mass(0) == firstMass
				&& domain.particles.mass(1) == unchangedMass && domain.chemicalParcels.record(1).equals(second),
				"nonzero species delta is rounded once in solver units without changing the source during planning");
	}

	private static void phaseTransferRequiresActualMassChange() {
		var granular = BuiltinGranularDefinitions.createRegistry();
		var liquids = new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE);
		for (double initialKg : new double[]{1.0E18, Math.scalb(1.0, 48)}) {
			var domain = new FluidDomain(3, 3, 3,
					new FluidSolverConfig(.5F, 1_000, 0, .05F, 100, 1E-5F, .45F, .002F, .8F), 216);
			domain.restoreParticleState(new FlipParticleState(3, 3, 3, .5F, 1, .125F,
					new float[]{.75F, .75F, .75F, .125F}, new float[3], new byte[27]));
			domain.bindGranular(granular, liquids, BuiltinFluidMaterials.createRegistry());
			domain.bindChemistry(BuiltinChemicalDefinitions.REGISTRY);
			domain.setPrecipitationEnabled(true);
			domain.particles.setMass(0, domain.unitScale.solverMass(initialKg));
			domain.setMarkerChemical(0, Map.of(BuiltinChemicalDefinitions.NA_PLUS, 8_320_000L,
					BuiltinChemicalDefinitions.CL_MINUS, 8_320_000L), false);
			domain.captureAuthoritativeState();
			var original = domain.chemicalParcels.record(0);
			double originalMass = domain.particles.mass(0);
			float originalVolume = domain.particles.volume(0);
			var stats = domain.runThermalReactionPhaseAxis(false, 0);
			require(stats.granularPhase().precipitations() == 0 && domain.granularState.parcels.count() == 0
					&& domain.particles.mass(0) == originalMass && domain.particles.volume(0) == originalVolume
					&& domain.chemicalParcels.record(0).equals(original),
					"precipitation defers when its actual liquid mass change cannot represent the solid transfer");
			var restored = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
			require(restored.particles.mass(0) == originalMass && restored.chemicalParcels.record(0).equals(original),
					"deferred mass transfer preserves a valid, restorable inventory");
		}
	}

	private static void precipitationPreflightsRawThermalAssignment() {
		for (boolean unavailable : new boolean[]{true, false}) {
			var liquids = new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE);
			var granular = BuiltinGranularDefinitions.createRegistry();
			var domain = new FluidDomain(3, 3, 3,
					new FluidSolverConfig(.5F, 1000, 0, .05F, 100, 1E-5F, .45F, .002F, 0), 64);
			domain.restoreMaterialParticleState(new FlipMaterialParticleState(3, 3, 3, .5F, 3, .375,
					cc.ecisr.mmm.units.SignedDyadic.sum(new double[]{.8, .8, .8}).toDouble(), liquids.materials(),
					new float[]{.75F, .75F, .75F, .125F, .75F, .75F, .75F, .125F, .75F, .75F, .75F, .125F},
					new float[9], new byte[27], new double[]{.8, .8, .8}, new int[]{0, 1, 2}));
			liquids = domain.materialRegistry();
			domain.bindGranular(granular, liquids, BuiltinFluidMaterials.createRegistry());
			domain.bindChemistry(BuiltinChemicalDefinitions.REGISTRY);
			domain.setPrecipitationEnabled(true);
			domain.setMarkerChemical(0, Map.of(BuiltinChemicalDefinitions.NA_PLUS, 8_320_000L,
					BuiltinChemicalDefinitions.CL_MINUS, 8_320_000L), false);
			var base = ThermochemistryValidationScenarios.reactiveGranularThermalRegistry(liquids, granular);
			var materials = new java.util.ArrayList<cc.ecisr.mmm.thermal.ThermalMaterialDefinition>();
			for (var material : base.materials()) {
				double reference = 0, cv = 1;
				if (material.carrierKind() == cc.ecisr.mmm.thermal.ThermalMaterialDefinition.CarrierKind.LIQUID) {
					if (unavailable) reference = material.carrierId().equals("mmm:water") ? 1280
							: material.carrierId().equals("mmm:oil") ? 1E19 : -1E19;
					cv = material.carrierId().equals("mmm:water") ? 1 : 10;
				} else if (unavailable && material.carrierId().equals(BuiltinGranularDefinitions.PRECIPITATE))
					reference = 257 / .084488832;
				materials.add(new cc.ecisr.mmm.thermal.ThermalMaterialDefinition(material.id(), material.carrierKind(),
						material.carrierId(), 300, reference, 0, List.of(
						new cc.ecisr.mmm.thermal.ThermalMaterialDefinition.CaloricSegment(150, 800, cv))));
			}
			var offsets = base.speciesOffsets().stream().map(offset -> new cc.ecisr.mmm.thermal.ThermalSpeciesOffset(
					offset.id(), offset.speciesId(), offset.hostThermalMaterialId(), ExactRational.ZERO)).toList();
			domain.bindThermal(new cc.ecisr.mmm.thermal.ThermalRegistry(materials, offsets, List.of(),
					base.chemicalRegistryFingerprint(), base.granularRegistryFingerprint()), liquids, 300);
			FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
			var energy = domain.exactInternalEnergy();
			var audit = domain.thermalAudit();
			var original = domain.chemicalParcels.record(0);
			var revision = domain.thermalState.thermalRevision();
			var stats = domain.stepThermalAxis(false, 0);
			if (unavailable) {
				require(stats.granularPhase().precipitations() == 0 && domain.granularState.parcels.count() == 0
						&& domain.granularState.compositions.liveCount() == 0 && domain.particles.mass(0) == .8
						&& domain.particles.volume(0) == .125F && domain.chemicalParcels.record(0).equals(original)
						&& domain.exactInternalEnergy().equals(energy) && domain.thermalAudit().equals(audit)
						&& domain.thermalState.thermalRevision().liquid() == revision.liquid()
						&& domain.thermalState.thermalRevision().granular() == revision.granular()
						&& Arrays.equals(domain.chemicalParcels.dirtyCells(), new int[]{13}),
						"unrepresentable raw LTE assignment must defer before species, solid handles or mass are written");
			} else require(stats.granularPhase().precipitations() == 1 && domain.granularState.parcels.count() == 1,
					"representable raw thermal assignment must preserve the normal precipitation path");
			require(domain.exactInternalEnergy().subtract(energy).equals(
					domain.thermalAudit().roundoffResidual().subtract(audit.roundoffResidual()))
					&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
					"reaction raw-energy assignment must publish its prepared roundoff exactly");
			var restored = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
			require(restored.exactInternalEnergy().equals(domain.exactInternalEnergy())
					&& restored.thermalAudit().equals(domain.thermalAudit())
					&& restored.chemicalParcels.record(0).equals(domain.chemicalParcels.record(0)),
					"reaction thermal preflight preserves a valid capture/restore handoff");
		}
	}

	private static void precipitationUsesCompleteThermalInventory() {
		for (int count : new int[]{256, 300}) {
			var granular = BuiltinGranularDefinitions.createRegistry();
			var liquids = new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE);
			var domain = new FluidDomain(3, 3, 3,
					new FluidSolverConfig(.5F, 1_000, 0, .05F, 100, 1E-5F, .45F, .002F, .8F), 512);
			float[] positions = new float[4 * count], velocities = new float[3 * count];
			for (int marker = 0; marker < count; marker++) {
				Arrays.fill(positions, 4 * marker, 4 * marker + 3, .75F);
				positions[4 * marker + 3] = 1F / 1_024;
				Arrays.fill(velocities, 3 * marker, 3 * marker + 3, .01F);
			}
			domain.restoreParticleState(new FlipParticleState(3, 3, 3, .5F, count, count / 1_024F,
					positions, velocities, new byte[27]));
			domain.bindGranular(granular, liquids, BuiltinFluidMaterials.createRegistry());
			domain.bindChemistry(BuiltinChemicalDefinitions.REGISTRY);
			domain.setPrecipitationEnabled(true);
			for (int marker = 0; marker < count; marker++) domain.setMarkerChemical(marker,
					Map.of(BuiltinChemicalDefinitions.NA_PLUS, 8_320_000L,
							BuiltinChemicalDefinitions.CL_MINUS, 8_320_000L), false);
			domain.bindThermal(ThermochemistryValidationScenarios.reactiveGranularThermalRegistry(liquids, granular), liquids, 300);
			domain.captureAuthoritativeState();
			var energy = domain.exactInternalEnergy();
			var audit = domain.thermalAudit();
			var initialVolume = cc.ecisr.mmm.units.SignedDyadic.of(domain.totalVolumeExact());
			var initialMass = cc.ecisr.mmm.units.SignedDyadic.ZERO;
			for (int marker = 0; marker < count; marker++) initialMass = initialMass.add(
					cc.ecisr.mmm.units.SignedDyadic.of(domain.unitScale.kilograms(domain.particles.mass(marker))));
			long[] initialSpecies = domain.chemicalParcels.speciesTotals(count);
			var stats = domain.stepThermalAxis(false, 0);
			require(stats.granularPhase().precipitations() == 1 && domain.granularState.parcels.count() == 1
					&& domain.granularState.compositions.liveCount() == 1,
					"complete LTE inventories above 256 carriers permit a valid precipitation");
			var solid = domain.granularState.parcels.parcel(0);
			var finalMass = cc.ecisr.mmm.units.SignedDyadic.of(solid.mass());
			for (int marker = 0; marker < count; marker++) finalMass = finalMass.add(
					cc.ecisr.mmm.units.SignedDyadic.of(domain.unitScale.kilograms(domain.particles.mass(marker))));
			require(Math.abs(finalMass.subtract(initialMass).toDouble()) <= 16 * Math.ulp(initialMass.toDouble()),
					"complete thermal reaction retains actual liquid and solid kilograms to storage precision");
			long[] finalSpecies = domain.chemicalParcels.speciesTotals(count);
			var composition = domain.granularState.compositions.record(solid.solidCompositionHandle());
			for (int entry = 0; entry < composition.entryCount(); entry++) finalSpecies[composition.speciesHandle(entry)] =
					Math.addExact(finalSpecies[composition.speciesHandle(entry)], composition.amountAt(entry));
			require(Arrays.equals(initialSpecies, finalSpecies), "complete thermal reaction preserves each integer species inventory");
			var finalVolume = cc.ecisr.mmm.units.SignedDyadic.of(domain.totalVolumeExact()).add(
					new cc.ecisr.mmm.units.SignedDyadic(java.math.BigInteger.valueOf(solid.solidVolumeSvq()), -24));
			require(finalVolume.equals(initialVolume) && solid.solidVolumeSvq() == stats.granularPhase().signedSolidSvq(),
					"complete thermal reaction preserves exact liquid plus solid volume");
			require(domain.exactInternalEnergy().equals(energy) && domain.thermalAudit().equals(audit),
					"complete thermal reaction preserves actual U and its audit");
			var restored = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
			require(restored.particleCount() == count && restored.granularState.parcels.parcel(0).equals(solid)
					&& restored.exactInternalEnergy().equals(energy) && restored.thermalAudit().equals(audit)
					&& Arrays.equals(restored.chemicalParcels.speciesTotals(count), domain.chemicalParcels.speciesTotals(count)),
					"complete thermal reaction restores all liquid, solid and thermal owners");
			restored.copyThermalDisplaySnapshot();
			requireCombinedCapacity(domain);
		}
	}

	private static void precipitationMergePreservesMassMoment() {
		var registry = BuiltinGranularDefinitions.createRegistry();
		long svq = 4_050, extent = svq / 50 * 281;
		var species = Map.of(BuiltinChemicalDefinitions.NA_PLUS, extent * 64,
				BuiltinChemicalDefinitions.CL_MINUS, extent * 64);
		double reactionMass = SolidCompositionRecord.fromStableAmounts(
				BuiltinChemicalDefinitions.REGISTRY, species, 0).totalMass(BuiltinChemicalDefinitions.REGISTRY);
		for (double residualScale : new double[]{0, 2, -.5}) {
			var domain = new FluidDomain(3, 3, 3,
					new FluidSolverConfig(.5F, 1_000, 0, .05F, 100, 1E-5F, .45F, .002F, .8F), 216);
			domain.setVolume(1, 1, 1, .1F);
			domain.bindGranular(registry, new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE),
					BuiltinFluidMaterials.createRegistry());
			domain.bindChemistry(BuiltinChemicalDefinitions.REGISTRY);
			domain.setPrecipitationEnabled(true);
			domain.setMarkerChemical(0, Map.of(BuiltinChemicalDefinitions.NA_PLUS, 8_320_000L,
					BuiltinChemicalDefinitions.CL_MINUS, 8_320_000L), false);
			var composition = SolidCompositionRecord.fromStableAmounts(BuiltinChemicalDefinitions.REGISTRY,
					species, residualScale * reactionMass);
			var original = new GranularParcelStore.Parcel(.625F, .7F, .8F, 0, 0, 0,
					svq, composition.totalMass(BuiltinChemicalDefinitions.REGISTRY),
					registry.materialHandle(BuiltinGranularDefinitions.PRECIPITATE),
					domain.granularState.compositions.create(composition), 0, (byte) 0);
			domain.granularState.parcels.add(original);
			domain.gridDirty = true;
			int cell = domain.cellIndex(1, 1, 1);
			double oldLiquidMass = domain.unitScale.kilograms(domain.particles.mass(0));
			float[] sourcePosition = {domain.particles.positionX(0), domain.particles.positionY(0), domain.particles.positionZ(0)};
			require(domain.runThermalReactionPhaseAxis(false, 0).granularPhase().precipitations() == 1
					&& domain.granularState.parcels.count() == 1, "mass moment fixture must merge actual precipitation");
			var merged = domain.granularState.parcels.parcel(0);
			double[] oldPosition = {original.x(), original.y(), original.z()};
			double transferredMass = oldLiquidMass - domain.unitScale.kilograms(domain.particles.mass(0));
			float[] finalPosition = {merged.x(), merged.y(), merged.z()};
			for (int axis = 0; axis < 3; axis++) {
				double expectedMoment = original.mass() * oldPosition[axis] + transferredMass * sourcePosition[axis];
				double storageBound = merged.mass() * .5 * Math.ulp(finalPosition[axis])
						+ 8 * Math.ulp(expectedMoment);
				require(Math.abs(merged.mass() * finalPosition[axis] - expectedMoment) <= storageBound,
						"precipitation merge must preserve mass moment through final float position rounding");
			}
			require(merged.solidVolumeSvq() == 2 * svq && merged.mass() == original.mass() + reactionMass,
					"mass moment merge must retain independently stored SVQ and kg");
			requireCombinedCapacity(domain);
		}
	}

	private static void precipitationMergeRetainsRotationalMotion() {
		var registry = BuiltinGranularDefinitions.createRegistry();
		var liquids = new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE);
		var domain = new FluidDomain(3, 3, 3,
				new FluidSolverConfig(.5F, 1_000, 0, .05F, 100, 1E-5F, .45F, .002F, .8F), 216);
		domain.setVolume(1, 1, 1, .1F);
		domain.bindGranular(registry, liquids, BuiltinFluidMaterials.createRegistry());
		domain.bindChemistry(BuiltinChemicalDefinitions.REGISTRY);
		domain.setPrecipitationEnabled(true);
		domain.setMarkerChemical(0, Map.of(BuiltinChemicalDefinitions.NA_PLUS, 8_320_000L,
				BuiltinChemicalDefinitions.CL_MINUS, 8_320_000L), false);
		long svq = 4_050, extent = svq / 50 * 281;
		var composition = SolidCompositionRecord.fromStableAmounts(BuiltinChemicalDefinitions.REGISTRY,
				Map.of(BuiltinChemicalDefinitions.NA_PLUS, extent * 64,
						BuiltinChemicalDefinitions.CL_MINUS, extent * 64), 0);
		var existing = new GranularParcelStore.Parcel(.625F, .7F, .8F, 0, .001F, 0,
				svq, composition.totalMass(BuiltinChemicalDefinitions.REGISTRY),
				registry.materialHandle(BuiltinGranularDefinitions.PRECIPITATE),
				domain.granularState.compositions.create(composition), 0, (byte) 0);
		domain.granularState.parcels.add(existing);
		domain.gridDirty = true;
		domain.bindThermal(ThermochemistryValidationScenarios.reactiveGranularThermalRegistry(liquids, registry), liquids, 300);
		double[] before = spatialMoments(domain);
		var energy = domain.exactInternalEnergy();
		require(domain.runThermalReactionPhaseAxis(false, 0).granularPhase().precipitations() == 1
				&& domain.granularState.parcels.count() == 2
				&& domain.granularState.parcels.parcel(0).equals(existing),
				"precipitation retains distinct parcels when merging would erase relative rotation");
		double[] after = spatialMoments(domain);
		for (int axis = 3; axis < 6; axis++) require(before[axis] == after[axis],
				"zero-velocity precipitation leaves the existing granular angular momentum unchanged");
		require(domain.exactInternalEnergy().equals(energy) && energy.equals(domain.thermalAudit().expectedInventory()),
				"separate precipitation parcels preserve the complete thermal inventory");
		requireCombinedCapacity(domain);
	}

	private static void precipitationPreservesAvailableMassMoment() {
		var granular = BuiltinGranularDefinitions.createRegistry();
		var liquids = new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE);
		var domain = new FluidDomain(3, 3, 3,
				new FluidSolverConfig(.5F, 1_000, 0, .05F, 100, 1E-5F, .45F, .002F, .8F), 216);
		domain.setVolume(1, 1, 1, .25F);
		domain.bindGranular(granular, liquids, BuiltinFluidMaterials.createRegistry());
		domain.bindChemistry(BuiltinChemicalDefinitions.REGISTRY);
		domain.setPrecipitationEnabled(true);
		float[] velocity = {1, .5F, -.25F};
		for (int marker = 0; marker < domain.particleCount(); marker++) {
			domain.setMarkerChemical(marker, Map.of(BuiltinChemicalDefinitions.NA_PLUS, 8_320_000L,
					BuiltinChemicalDefinitions.CL_MINUS, 8_320_000L), false);
			domain.particles.setVelocityX(marker, velocity[0]);
			domain.particles.setVelocityY(marker, velocity[1]);
			domain.particles.setVelocityZ(marker, velocity[2]);
		}
		domain.bindThermal(ThermochemistryValidationScenarios.reactiveGranularThermalRegistry(liquids, granular), liquids, 300);
		double[] before = spatialMoments(domain);
		var energy = domain.exactInternalEnergy();
		require(domain.runThermalReactionPhaseAxis(false, 0).granularPhase().precipitations() == 1
				&& domain.granularState.parcels.count() == 1, "spatial moment fixture actually precipitates");
		var solid = domain.granularState.parcels.parcel(0);
		require(solid.x() == .75F && solid.y() == .625F && solid.z() == .625F,
				"available transferred-mass centre is used without sub-cell jitter");
		double[] after = spatialMoments(domain);
		float[] position = {solid.x(), solid.y(), solid.z()};
		double[] storageBound = new double[3];
		for (int axis = 0; axis < 3; axis++) {
			storageBound[axis] = solid.mass() * .5 * Math.ulp(position[axis])
					+ 32 * Math.ulp(Math.max(Math.abs(before[axis]), Math.abs(after[axis])));
			require(Math.abs(after[axis] - before[axis]) <= storageBound[axis],
					"precipitation preserves actual liquid/solid mass moment axis " + axis);
		}
		for (int axis = 0; axis < 3; axis++) {
			int j = (axis + 1) % 3, k = (axis + 2) % 3;
			double bound = storageBound[j] * Math.abs(velocity[k]) + storageBound[k] * Math.abs(velocity[j])
					+ 32 * Math.ulp(Math.max(Math.abs(before[axis + 3]), Math.abs(after[axis + 3])));
			require(Math.abs(after[axis + 3] - before[axis + 3]) <= bound,
					"common-velocity precipitation preserves angular momentum axis " + axis);
		}
		require(domain.exactInternalEnergy().equals(energy)
				&& energy.equals(domain.thermalAudit().expectedInventory()), "spatial transfer preserves actual internal energy");
		requireCombinedCapacity(domain);
	}

	private static double[] spatialMoments(FluidDomain domain) {
		double[] result = new double[6];
		for (int marker = 0; marker < domain.particleCount(); marker++) addSpatialMoments(result,
				domain.unitScale.kilograms(domain.particles.mass(marker)),
				new float[]{domain.particles.positionX(marker), domain.particles.positionY(marker), domain.particles.positionZ(marker)},
				new float[]{domain.particles.velocityX(marker), domain.particles.velocityY(marker), domain.particles.velocityZ(marker)});
		for (int ordinal = 0; ordinal < domain.granularState.parcels.count(); ordinal++) {
			var parcel = domain.granularState.parcels.parcel(ordinal);
			addSpatialMoments(result, parcel.mass(), new float[]{parcel.x(), parcel.y(), parcel.z()},
					new float[]{parcel.velocityX(), parcel.velocityY(), parcel.velocityZ()});
		}
		return result;
	}

	private static void addSpatialMoments(double[] result, double mass, float[] position, float[] velocity) {
		for (int axis = 0; axis < 3; axis++) {
			int j = (axis + 1) % 3, k = (axis + 2) % 3;
			result[axis] += mass * position[axis];
			result[axis + 3] += mass * (position[j] * (double) velocity[k] - position[k] * (double) velocity[j]);
		}
	}

	private static void phaseTransferFitsLiquidAndSolidKernel() {
		var granular = BuiltinGranularDefinitions.createRegistry();
		var liquids = new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE);
		var domain = new FluidDomain(3, 3, 3,
				new FluidSolverConfig(.5F, 1_000, 0, .05F, 100, 1E-5F, .45F, .002F, .8F), 216);
		for (int z = 0; z < 3; z++) for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++) domain.setVolume(x, y, z, 1);
		domain.bindGranular(granular, liquids, BuiltinFluidMaterials.createRegistry());
		domain.bindChemistry(BuiltinChemicalDefinitions.REGISTRY);
		domain.bindThermal(ThermochemistryValidationScenarios.reactiveGranularThermalRegistry(liquids, granular), liquids, 300);
		domain.setPrecipitationEnabled(true);
		int source = domain.cellIndex(1, 1, 1);
		for (int marker = 0; marker < domain.particleCount(); marker++) if (domain.particleCellIndex(marker) == source)
			domain.setMarkerChemical(marker, Map.of(BuiltinChemicalDefinitions.NA_PLUS, 8_320_000L,
					BuiltinChemicalDefinitions.CL_MINUS, 8_320_000L), false);
		require(domain.stepThermalAxis(false, 0).granularPhase().precipitations() == 1,
				"equal-volume precipitation has a valid representation inside a full liquid pool");
		var parcel = domain.granularState.parcels.parcel(0);
		require(parcel.x() == .75F && parcel.y() == .75F && parcel.z() == .75F,
				"full neighbors require a collision-valid source-cell kernel");
		requireCombinedCapacity(domain);

		// A moving parcel releases solid volume across two cells, while dissolved liquid
		// is produced in its source cell. Saturated local capacity must defer the batch.
		domain.granularState.parcels.overwrite(0, new GranularParcelStore.Parcel(
				.875F, parcel.y(), parcel.z(), 0, 0, 0, parcel.solidVolumeSvq(), parcel.mass(),
				parcel.materialHandle(), parcel.solidCompositionHandle(), 0, (byte) 0));
		domain.gridDirty = true;
		for (int z = 0; z < 3; z++) for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++) domain.setVolume(x, y, z, 1);
		for (int marker = 0; marker < domain.particleCount(); marker++) if (domain.particleCellIndex(marker) == source) setWater(domain, marker, 100_000);
		long solidBefore = domain.totalGranularSolidVolumeSvq();
		double liquidBefore = domain.totalVolumeExact();
		require(domain.stepThermalAxis(false, 0).granularPhase().dissolutions() == 0
				&& domain.totalGranularSolidVolumeSvq() == solidBefore && domain.totalVolumeExact() == liquidBefore,
				"dissolution keeps both inventories when its source cell cannot receive the full batch");
		requireCombinedCapacity(domain);
		domain.setVolume(1, 1, 1, .5F);
		require(domain.stepThermalAxis(false, 0).granularPhase().dissolutions() == 1,
				"a deferred dissolution resumes when source-cell capacity becomes available");
		requireCombinedCapacity(domain);
	}

	private static void requireCombinedCapacity(FluidDomain domain) {
		domain.ensureInventoryGrid();
		for (int cell = 0; cell < domain.cellCount; cell++) {
			var occupied = domain.markerGrid.exactCellVolumeInventory[cell].add(
					new cc.ecisr.mmm.units.SignedDyadic(java.math.BigInteger.valueOf(domain.granularCoupling.solidSvq(cell)), -24));
			require(occupied.compareTo(cc.ecisr.mmm.units.SignedDyadic.of(domain.geometry.cutCells.openCellVolume(cell))) <= 0,
					"thermal-axis phase transfer exceeds joint liquid/solid capacity at cell " + cell);
		}
	}

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
			if (mode.equals("region-budget")) {
				require(domain.runThermalReactionPhaseAxis(false, 0).granularPhase().dissolutions() == 0
						&& domain.chemicalParcels.record(0).equals(before),
						"dissolution retains the source when free composition capacity still leaves an unrepresentable mass transfer");
				setWater(domain, 0, 100_000L);
			}
			require(domain.runThermalReactionPhaseAxis(false, 0).granularPhase().dissolutions() == 1,
					"dissolution resumes when marker capacity, region capacity and actual mass representation are available");
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
