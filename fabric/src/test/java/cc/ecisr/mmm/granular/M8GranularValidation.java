package cc.ecisr.mmm.granular;

import cc.ecisr.mmm.chemistry.BuiltinChemicalDefinitions;
import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.chemistry.ExactRational;
import cc.ecisr.mmm.fluid.BuiltinFluidMaterials;
import cc.ecisr.mmm.fluid.CpuFluidSolverBackend;
import cc.ecisr.mmm.fluid.FluidDomain;
import cc.ecisr.mmm.fluid.FluidDomainState;
import cc.ecisr.mmm.fluid.FluidMaterialRegistry;
import cc.ecisr.mmm.fluid.FluidSolverConfig;
import cc.ecisr.mmm.fluid.FluidStepCandidate;
import cc.ecisr.mmm.fluid.FluidStepRequest;
import cc.ecisr.mmm.fluid.MaterialRegistry;
import cc.ecisr.mmm.simulation.FullCapabilityTestFixture;
import cc.ecisr.mmm.units.SignedDyadic;
import cc.ecisr.mmm.units.SolverUnitScale;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** M8 granular definition and exact inventory gate. Mechanics are added to this selector incrementally. */
public final class M8GranularValidation {
	private M8GranularValidation() { }

	public static void main(String[] args) throws Exception {
		boolean fast = Arrays.asList(args).contains("--fast");
		registryIsCanonicalAndBounded();
		registryDefinitionsAndWorldSnapshotAreStrict();
		carrierBindingIdentityIsEnforced();
		carrierViscosityFollowsCellTemperature();
		solidCompositionStateIsIndependentAndPersistent();
		phaseTransferPrecipitatesAndDissolves();
		exactDryContainerTransferIsAtomic();
		solidVolumeConversionsAreConservative();
		parcelSplitMergePreservesInventory();
		depositCapacityAndGroupBudgetAreAtomic();
		parcelDepositCyclesDoNotDrift(fast ? 1_000 : 10_000);
		mechanicsMatchesTerminalVelocity();
		twoWayDragPreservesMomentum();
		cc.ecisr.mmm.fluid.GranularCarrierValidation.validate();
		couplingOccupancyResumsExactly();
		porousBedPreservesHydrostatics();
		settlingRelaxationSleepAndWakeAreLocal();
		blockedSettlementPreservesKineticEnergy();
		cc.ecisr.mmm.fluid.GranularBedWakeValidation.validate();
		solverCouplingPreservesInventories(fast ? 8 : 80);
		wholeBucketCongestionRemainsLocal(fast ? 40 : 200);
		if (!fast) singleBucketPourSettlesWithinTenSeconds();
		emptyGranularGateHasBoundedWallTime(fast ? 8 : 24);
		System.out.println("M8 granular core gate passed: exact inventory, coupling, repose, sleep and local wake");
	}

	/**
	 * Granular drag evaluates material viscosity from cell temperature, so a domain may only bind
	 * liquid palettes that the world material definitions actually cover — and it may not swap those
	 * definitions later. Every rejection must leave the domain untouched.
	 */
	private static void carrierBindingIdentityIsEnforced() {		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F,
				100, 1.0e-5F, 0.45F, 2.0e-3F, 0.8F);
		GranularRegistry granular = BuiltinGranularDefinitions.createRegistry();
		FluidMaterialRegistry palette = new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE);
		MaterialRegistry definitions = BuiltinFluidMaterials.createRegistry();

		// A liquid the definitions do not mention cannot participate in carrier coupling.
		FluidDomain unknownLiquid = new FluidDomain(3, 3, 3, config, 3 * 3 * 3 * 8);
		FluidMaterialRegistry extended = new FluidMaterialRegistry(List.of(
				BuiltinFluidMaterials.WATER, BuiltinFluidMaterials.OIL, BuiltinFluidMaterials.MERCURY,
				new cc.ecisr.mmm.fluid.FluidMaterial("mmm:ghost", 900.0, "mmm:ghost", 0.0, Math.PI / 2.0)));
		expectIllegal("has no world material definition",
				() -> unknownLiquid.bindGranular(granular, extended, definitions));
		require(!unknownLiquid.hasGranularState() && !unknownLiquid.hasMaterialState(),
				"a rejected binding must not leave granular or material state behind");

		// A liquid that disagrees with its definition on base properties is a second source of truth.
		FluidDomain conflicting = new FluidDomain(3, 3, 3, config, 3 * 3 * 3 * 8);
		FluidMaterialRegistry wrongDensity = new FluidMaterialRegistry(List.of(
				new cc.ecisr.mmm.fluid.FluidMaterial("mmm:water", 1_050.0, "mmm:aqueous", 0.0, Math.PI / 2.0),
				BuiltinFluidMaterials.OIL, BuiltinFluidMaterials.MERCURY));
		expectIllegal("disagrees with its world material definition",
				() -> conflicting.bindGranular(granular, wrongDensity, definitions));
		require(!conflicting.hasGranularState(), "a rejected binding must not install granular state");

		// A liquid whose definition declares no viscosity cannot be coupled at any temperature.
		FluidDomain undeclared = new FluidDomain(3, 3, 3, config, 3 * 3 * 3 * 8);
		MaterialRegistry withoutViscosity = new MaterialRegistry(List.of(
				new MaterialRegistry.MaterialEntry(BuiltinFluidMaterials.WATER, List.of()),
				new MaterialRegistry.MaterialEntry(BuiltinFluidMaterials.OIL,
						definitions.entry("mmm:oil").viscosity()),
				new MaterialRegistry.MaterialEntry(BuiltinFluidMaterials.MERCURY,
						definitions.entry("mmm:mercury").viscosity())), List.of());
		expectIllegal("does not declare a dynamic viscosity domain",
				() -> undeclared.bindGranular(granular, palette, withoutViscosity));
		require(!undeclared.hasGranularState(), "a rejected binding must not install granular state");

		// Rebinding the same definitions stays legal, including after material state already exists.
		FluidDomain bound = new FluidDomain(3, 3, 3, config, 3 * 3 * 3 * 8);
		bound.bindGranular(granular, palette, definitions);
		long granularRevisionBefore = bound.totalGranularSolidVolumeSvq();
		bound.bindGranular(granular, palette, definitions);
		require(bound.hasGranularState() && bound.totalGranularSolidVolumeSvq() == granularRevisionBefore,
				"rebinding identical definitions must be idempotent");
		expectIllegal("cannot change its world material definitions",
				() -> bound.bindGranular(granular, palette, FullCapabilityTestFixture.doubledWaterViscosity()));
		require(bound.totalGranularSolidVolumeSvq() == granularRevisionBefore,
				"a rejected rebinding must not change granular inventory");
	}

	/**
	 * Carrier drag must follow the world material's declared mu(T) at the cell's authoritative
	 * temperature, and the same workspace must serve both the mechanical stage and Darcy damping.
	 */
	private static void carrierViscosityFollowsCellTemperature() throws Exception {
		FullCapabilityTestFixture.Registries registries = FullCapabilityTestFixture.load();
		MaterialRegistry definitions = registries.materials();
		for (double temperatureK : new double[] {293.15, 353.15}) {
			FluidDomain domain = new FluidDomain(4, 4, 4, new FluidSolverConfig(0.5F, 1_000.0F, 0.0F,
					1.0F / 20.0F, 200, 1.0e-5F, 0.45F, 2.0e-3F, 0.8F), 4 * 4 * 4 * 8);
			FullCapabilityTestFixture.bindFull(domain, registries, temperatureK, 4);
			domain.setVolume(1, 1, 1, 1.0F);
			domain.setThermalTemperatureInCell(1, 1, 1, temperatureK);
			int cell = (1 * domain.sizeY() + 1) * domain.sizeX() + 1;
			Double sampled = domain.carrierViscosityPaPerS(cell);
			require(sampled != null, "the wet carrier cell must be visible to the derived carrier workspace");
			double expected = definitions.dynamicViscosityAt("mmm:water", temperatureK);
			double relative = Math.abs(sampled - expected) / expected;
			require(relative <= 1.0e-6, "carrier viscosity must follow the declared mu(T): expected " + expected
					+ " but sampled " + sampled + " at " + temperatureK + " K");
			// The shared workspace, not a second derivation, answers Darcy damping for the same cell.
			cc.ecisr.mmm.fluid.FluidDomain.CarrierViscosityStats stats = domain.carrierViscosityStats();
			require(!stats.extrapolated(), "an in-domain temperature must not report a viscosity excursion");
		}
	}

	private static void exactDryContainerTransferIsAtomic() throws Exception {		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F,
				100, 1.0e-5F, 0.45F, 2.0e-3F, 0.8F);
		GranularRegistry registry = BuiltinGranularDefinitions.createRegistry();
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE);
		FluidDomain domain = new FluidDomain(6, 6, 6, config, 6 * 6 * 6 * 8);
		domain.bindGranular(registry, liquids, BuiltinFluidMaterials.createRegistry()); domain.bindChemistry(BuiltinChemicalDefinitions.REGISTRY);
		long reactiveSvq = 4_096L;
		SolidCompositionRecord reactive = SolidCompositionRecord.fromStableAmounts(
				BuiltinChemicalDefinitions.REGISTRY, java.util.Map.of(
						BuiltinChemicalDefinitions.CL_MINUS, reactiveSvq * 64L,
						BuiltinChemicalDefinitions.NA_PLUS, reactiveSvq * 64L), 0.0);
		GranularTransferLedger input = new GranularTransferLedger(List.of(
				new GranularTransferLedger.Group(BuiltinGranularDefinitions.FINE_SAND,
						SolidVolume.SVQ_PER_CELL + 65_536L, 2_600.25, null),
				new GranularTransferLedger.Group(BuiltinGranularDefinitions.PRECIPITATE, reactiveSvq,
						reactive.totalMass(BuiltinChemicalDefinitions.REGISTRY), reactive)));
		FluidDomain.GranularInsertion inserted = domain.tryInsertExactGranular(2, 2, 2, input, 3, true);
		require(inserted.inserted() && domain.totalGranularSolidVolumeSvq() == input.totalSolidVolumeSvq()
				&& rawClose(domain.totalGranularMass(), input.totalMass()),
				"dry granular insertion did not preserve exact volume/mass across nearby cells");
		FullCapabilityTestFixture.Registries production = FullCapabilityTestFixture.load();
		domain.bindGasRegistry(production.gas());
		domain.bindThermal(production.thermal(), liquids, 300.0);
		domain = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
		require(domain.totalGranularSolidVolumeSvq() == input.totalSolidVolumeSvq()
				&& rawClose(domain.totalGranularMass(), input.totalMass()),
				"thermal backend handoff changed a valid positioned granular container inventory");
		GranularDisplaySnapshot display = domain.copyGranularDisplaySnapshot();
		require(display.registryFingerprint().equals(registry.fingerprint())
				&& display.materials().size() == registry.materials().size()
				&& !display.activeParcels().isEmpty()
				&& display.activeParcels().size() <= GranularLimits.MAX_DISPLAY_PARCELS_PER_REGION
				&& display.depositBulkFill().length == 6 * 6 * 6,
				"bounded granular display snapshot lost its identity, geometry or active sample");
		long distinctPositions = display.activeParcels().stream().map(sample ->
				((long) sample.xQ() << 32) | ((long) sample.yQ() << 16) | sample.zQ()).distinct().count();
		require(distinctPositions >= 32L && display.activeParcels().stream().allMatch(sample -> sample.diameterQ() <= 10),
				"dry granular insertion collapsed representative parcels onto shared cell centers");
		FluidDomain.GranularExtraction extracted = domain.tryExtractExactGranular(
				2, 2, 2, SolidVolume.SVQ_PER_BV, 3, true);
		require(extracted.transferred() && extracted.ledger().totalSolidVolumeSvq() == input.totalSolidVolumeSvq()
				&& rawClose(extracted.ledger().totalMass(), input.totalMass())
				&& domain.totalGranularSolidVolumeSvq() == 0L,
				"dry granular extraction did not close the active inventory");
		GranularTransferLedger.Group extractedReactive = extracted.ledger().groups().stream()
				.filter(group -> group.materialId().equals(BuiltinGranularDefinitions.PRECIPITATE)).findFirst().orElseThrow();
		require(extractedReactive.composition().equals(reactive),
				"dry granular extraction changed reactive solid composition");
		require(domain.granularExternalSource().entries().size() == 2
				&& domain.granularEscapedSink().entries().size() == 2
				&& domain.granularExternalSource().entries().stream().mapToLong(GranularAuditLedger.Entry::solidVolumeSvq).sum()
				== input.totalSolidVolumeSvq(), "dry granular boundary audit did not close source/sink SVQ");

		FluidDomain sealed = new FluidDomain(3, 3, 3, config, 3 * 3 * 3 * 8);
		for (int z = 0; z < 3; z++) for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++) sealed.setVolume(x, y, z, 1.0F);
		sealed.bindGranular(registry, liquids, BuiltinFluidMaterials.createRegistry()); sealed.bindChemistry(BuiltinChemicalDefinitions.REGISTRY);
		GranularTransferLedger small = new GranularTransferLedger(List.of(new GranularTransferLedger.Group(
				BuiltinGranularDefinitions.FINE_SAND, 1_024L, 1.0, null)));
		require(!sealed.tryInsertExactGranular(1, 1, 1, small, 1).inserted()
				&& sealed.totalGranularSolidVolumeSvq() == 0L,
				"sealed dry granular insertion was not rejected atomically");

		FluidDomain collision = new FluidDomain(3, 3, 3, config, 3 * 3 * 3 * 8);
		collision.bindGranular(registry, liquids, BuiltinFluidMaterials.createRegistry()); collision.bindChemistry(BuiltinChemicalDefinitions.REGISTRY);
		require(collision.tryInsertExactGranular(1, 1, 1, small, 0, true).inserted(),
				"collision displacement fixture could not insert its local parcel");
		FluidDomain.CollisionEditPlan collisionPlan = collision.planCollisionEdits(List.of(
				new FluidDomain.CollisionEdit(1, 1, 1, 0xff),
				new FluidDomain.CollisionEdit(2, 1, 1, 0xff)));
		collision.beginCollisionEdits(collisionPlan);
		collision.setSolid(1, 1, 1, true);
		collision.setSolid(2, 1, 1, true);
		long collisionSink = collision.granularEscapedSink().entries().stream()
				.mapToLong(GranularAuditLedger.Entry::solidVolumeSvq).sum();
		require(collision.totalGranularSolidVolumeSvq() == 0L
				&& collisionSink == small.totalSolidVolumeSvq(),
				"runtime collision edit did not conservatively move overlapping granular truth to escaped sink");
	}

	private static void phaseTransferPrecipitatesAndDissolves() {
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F,
				100, 1.0e-5F, 0.45F, 2.0e-3F, 0.8F);
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE);
		GranularRegistry granular = BuiltinGranularDefinitions.createRegistry();
		SolverUnitScale unitScale = SolverUnitScale.forCellSize(config.cellSize());
		cc.ecisr.mmm.fluid.GranularReactionCandidateValidation.validate();
		FluidDomain domain = new FluidDomain(3, 3, 3, config, 3 * 3 * 3 * 8);
		// Real transported/reseeded marker volumes are arbitrary IEEE-754 dyadics, not necessarily
		// integer SVQ. Exercise the production bridge with such a tail instead of an aligned full cell.
		domain.setVolume(1, 1, 1, 0.1F);
		domain.bindGranular(granular, liquids, BuiltinFluidMaterials.createRegistry());
		domain.bindChemistry(BuiltinChemicalDefinitions.REGISTRY);
		domain.setPrecipitationEnabled(true);
		domain.setMarkerChemical(0, java.util.Map.of(
				BuiltinChemicalDefinitions.NA_PLUS, 8_320_000L,
				BuiltinChemicalDefinitions.CL_MINUS, 8_320_000L), true);
		long[] combinedBefore = combinedSpecies(domain);
		double massBefore = domain.physicalMassKilograms(domain.totalMass()) + domain.totalGranularMass();
		double occupiedBefore = domain.totalVolumeExact()
				+ SolidVolume.toCellVolume(domain.totalGranularSolidVolumeSvq());
		domain.step();
		require(domain.lastPhaseTransferStats().precipitations() == 1
				&& domain.lastPhaseTransferStats().signedSolidSvq() == 4_050L,
				"supersaturated aqueous inventory did not precipitate to the bounded target");
		require(java.util.Arrays.equals(combinedBefore, combinedSpecies(domain)),
				"precipitation changed combined aqueous+solid species");
		require(rawClose(massBefore, domain.physicalMassKilograms(domain.totalMass()) + domain.totalGranularMass()),
				"precipitation changed combined liquid+solid mass");
		require(rawClose(occupiedBefore, domain.totalVolumeExact()
				+ SolidVolume.toCellVolume(domain.totalGranularSolidVolumeSvq())),
				"precipitation did not exchange liquid and solid volume exactly");
		GranularStateSnapshot.ActiveParcel precipitated = domain.exportGranularState().activeParcels().getFirst();
		require(Float.floatToRawIntBits(precipitated.x()) != Float.floatToRawIntBits(0.75F)
				|| Float.floatToRawIntBits(precipitated.y()) != Float.floatToRawIntBits(0.75F)
				|| Float.floatToRawIntBits(precipitated.z()) != Float.floatToRawIntBits(0.75F),
				"precipitation collapsed every new parcel onto the solver-cell center");
		domain.step();
		require(domain.totalGranularSolidVolumeSvq() == 8_100L
				&& domain.exportGranularState().activeParcels().size() == 1,
				"repeated same-cell precipitation did not merge compatible active parcels: svq="
						+ domain.totalGranularSolidVolumeSvq() + ", active="
						+ domain.exportGranularState().activeParcels().size() + ", deposits="
						+ domain.granularDepositCellCount() + ", parcels="
						+ domain.exportGranularState().activeParcels());
		require(java.util.Arrays.equals(combinedBefore, combinedSpecies(domain))
				&& rawClose(massBefore, domain.physicalMassKilograms(domain.totalMass()) + domain.totalGranularMass())
				&& rawClose(occupiedBefore, domain.totalVolumeExact()
				+ SolidVolume.toCellVolume(domain.totalGranularSolidVolumeSvq())),
				"merged precipitation changed combined aqueous/solid truth");

		for (int marker = 0; marker < domain.particleCount(); marker++) {
			domain.setMarkerChemical(marker, java.util.Map.of(BuiltinChemicalDefinitions.H2O, 1_000_000L),
					(marker & 1) == 0);
		}
		long[] beforeDissolution = combinedSpecies(domain);
		double massBeforeDissolution = domain.physicalMassKilograms(domain.totalMass())
				+ domain.totalGranularMass();
		double occupiedBeforeDissolution = domain.totalVolumeExact()
				+ SolidVolume.toCellVolume(domain.totalGranularSolidVolumeSvq());
		long solidBeforeDissolution = domain.totalGranularSolidVolumeSvq();
		boolean dissolved = false;
		for (int step = 0; step < 16 && domain.totalGranularSolidVolumeSvq() > 0L; step++) {
			domain.step(); dissolved |= domain.lastPhaseTransferStats().dissolutions() > 0;
		}
		require(dissolved && domain.totalGranularSolidVolumeSvq() < solidBeforeDissolution,
				"undersaturated liquid did not dissolve toward its same-cell equilibrium");
		require(java.util.Arrays.equals(beforeDissolution, combinedSpecies(domain)),
				"dissolution changed combined aqueous+solid species");
		require(rawClose(massBeforeDissolution,
				domain.physicalMassKilograms(domain.totalMass()) + domain.totalGranularMass()),
				"dissolution changed combined liquid+solid mass");
		require(rawClose(occupiedBeforeDissolution, domain.totalVolumeExact()
				+ SolidVolume.toCellVolume(domain.totalGranularSolidVolumeSvq())),
				"dissolution did not exchange liquid and solid volume exactly");
		boolean indicated = false;
		for (int marker = 0; marker < domain.particleCount(); marker++) indicated |= domain.chemicalParcels().indicator(marker);
		require(indicated, "dissolution erased carrier-marker indicator provenance");
	}

	private static long[] combinedSpecies(FluidDomain domain) {
		long[] result = domain.chemicalParcels().speciesTotals(domain.particleCount());
		if (domain.totalGranularSolidVolumeSvq() == 0L) return result;
		long[] solid = domain.solidCompositions().speciesTotals();
		for (int species = 0; species < result.length; species++) result[species] = Math.addExact(result[species], solid[species]);
		return result;
	}

	private static void solidCompositionStateIsIndependentAndPersistent() {
		GranularRegistry registry = BuiltinGranularDefinitions.createRegistry();
		int precipitate = registry.materialHandle(BuiltinGranularDefinitions.PRECIPITATE);
		SolidCompositionPool compositions = new SolidCompositionPool(BuiltinChemicalDefinitions.REGISTRY, 4);
		SolidCompositionRecord activeRecord = SolidCompositionRecord.fromStableAmounts(
				BuiltinChemicalDefinitions.REGISTRY, java.util.Map.of(
						BuiltinChemicalDefinitions.NA_PLUS, 640L,
						BuiltinChemicalDefinitions.CL_MINUS, 640L), 0.0);
		SolidCompositionPool rollbackPool = new SolidCompositionPool(BuiltinChemicalDefinitions.REGISTRY, 4);
		SolidCompositionPool growing = new SolidCompositionPool(BuiltinChemicalDefinitions.REGISTRY, 130);
		for (int handle = 1; handle <= 130; handle++) require(growing.create(activeRecord) == handle,
				"solid composition growth preserves handle order");
		require(!growing.hasCapacity(1), "solid composition pool retains its hard budget");
		for (int handle = 1; handle <= 130; handle++) {
			require(growing.record(handle).equals(activeRecord), "solid composition growth preserves records");
			growing.release(handle);
		}
		require(growing.hasCapacity(130), "releases restore the complete budget");
		for (int handle = 130; handle >= 1; handle--) require(growing.create(activeRecord) == handle,
				"solid composition releases retain stack order");
		int firstReserved = rollbackPool.create(activeRecord);
		int secondReserved = rollbackPool.create(activeRecord);
		rollbackPool.release(secondReserved);
		rollbackPool.release(firstReserved);
		require(rollbackPool.create(activeRecord) == firstReserved,
				"reverse rollback did not restore deterministic composition allocation order");
		int activeComposition = compositions.create(activeRecord);
		GranularParcelStore parcels = new GranularParcelStore(registry, 4);
		parcels.add(new GranularParcelStore.Parcel(0.5F, 0.5F, 0.5F, 0.0F, 0.0F, 0.0F,
				10L, activeRecord.totalMass(BuiltinChemicalDefinitions.REGISTRY), precipitate, activeComposition,
				0, (byte) 0));
		SolidCompositionRecord depositRecord = SolidCompositionRecord.fromStableAmounts(
				BuiltinChemicalDefinitions.REGISTRY, java.util.Map.of(
						BuiltinChemicalDefinitions.NA_PLUS, 1_280L,
						BuiltinChemicalDefinitions.CL_MINUS, 1_280L), 0.0);
		int depositComposition = compositions.create(depositRecord);
		GranularDepositStore deposits = new GranularDepositStore(registry, 8, 4);
		require(deposits.tryAdd(0, new GranularDepositStore.SolidPortion(precipitate, depositComposition, 20L,
				depositRecord.totalMass(BuiltinChemicalDefinitions.REGISTRY)), SolidVolume.SVQ_PER_CELL),
				"reactive deposit fixture was rejected");
		GranularStateSnapshot snapshot = GranularStateSnapshot.capture(2, 2, 2, registry, parcels, deposits, compositions);
		snapshot.validateSolidCompositionMasses(BuiltinChemicalDefinitions.REGISTRY);
		require(snapshot.solidSpeciesTotals().get(BuiltinChemicalDefinitions.NA_PLUS) == 1_920L
				&& snapshot.solidSpeciesTotals().get(BuiltinChemicalDefinitions.CL_MINUS) == 1_920L,
				"solid species totals did not close");
		GranularAuditLedger clearedAudit = GranularAuditLedger.captureInventory(
				registry, parcels, deposits, compositions);
		GranularInventory clearingInventory = new GranularInventory(parcels, deposits);
		clearingInventory.bindSolidCompositions(compositions);
		GranularInventory.ClearStats cleared = clearingInventory.clear();
		require(cleared.solidVolumeSvq() == 30L && cleared.activeParcels() == 1
				&& cleared.depositGroups() == 1 && parcels.count() == 0 && deposits.groupCount() == 0
				&& compositions.liveCount() == 0 && clearedAudit.requiresChemistry()
				&& clearedAudit.entries().getFirst().solidVolumeSvq() == 30L,
				"reactive granular clear lost inventory, audit or composition ownership");

		GranularParcelStore restoredParcels = new GranularParcelStore(registry, 4);
		GranularDepositStore restoredDeposits = new GranularDepositStore(registry, 8, 4);
		SolidCompositionPool restoredCompositions = new SolidCompositionPool(BuiltinChemicalDefinitions.REGISTRY, 4);
		snapshot.restoreInto(registry, restoredParcels, restoredDeposits, restoredCompositions,
				ignored -> SolidVolume.SVQ_PER_CELL);
		GranularStateSnapshot restored = GranularStateSnapshot.capture(
				2, 2, 2, registry, restoredParcels, restoredDeposits, restoredCompositions);
		require(restored.solidSpeciesTotals().equals(snapshot.solidSpeciesTotals())
				&& restored.totalSolidVolumeSvq() == snapshot.totalSolidVolumeSvq()
				&& restored.totalMassBits() == snapshot.totalMassBits(),
				"solid composition snapshot round-trip changed inventory");

		SolidCompositionPool mergeCompositions = new SolidCompositionPool(BuiltinChemicalDefinitions.REGISTRY, 4);
		GranularParcelStore mergeParcels = new GranularParcelStore(registry, 4);
		GranularDepositStore mergeDeposits = new GranularDepositStore(registry, 1, 4);
		GranularInventory mergeInventory = new GranularInventory(mergeParcels, mergeDeposits);
		mergeInventory.bindSolidCompositions(mergeCompositions);
		for (int parcel = 0; parcel < 2; parcel++) {
			int composition = mergeCompositions.create(activeRecord);
			mergeParcels.add(new GranularParcelStore.Parcel(0.25F, 0.25F, 0.25F, 0.0F, 0.0F, 0.0F,
					10L, activeRecord.totalMass(BuiltinChemicalDefinitions.REGISTRY), precipitate, composition,
					GranularDepositDynamics.SETTLE_CONTACT_STEPS, GranularParcelStore.FLAG_SUPPORTED));
			require(mergeInventory.depositParcel(0, 0, SolidVolume.SVQ_PER_CELL),
					"compatible reactive parcel did not enter its deposit group");
		}
		GranularStateSnapshot merged = GranularStateSnapshot.capture(
				1, 1, 1, registry, mergeParcels, mergeDeposits, mergeCompositions);
		require(mergeDeposits.groupCount() == 1 && mergeCompositions.liveCount() == 1
				&& merged.depositGroups().size() == 1
				&& merged.solidSpeciesTotals().get(BuiltinChemicalDefinitions.NA_PLUS) == 1_280L,
				"proportional reactive deposits did not merge into one persistent canonical group");

		SolidCompositionPool compactCompositions = new SolidCompositionPool(BuiltinChemicalDefinitions.REGISTRY, 8);
		GranularParcelStore compactParcels = new GranularParcelStore(registry, 8);
		GranularInventory compactInventory = new GranularInventory(
				compactParcels, new GranularDepositStore(registry, 1, 4));
		compactInventory.bindSolidCompositions(compactCompositions);
		for (int parcel = 0; parcel < 4; parcel++) compactParcels.add(new GranularParcelStore.Parcel(
				0.20F + parcel * 0.05F, 0.25F, 0.25F, 0.0F, 0.0F, 0.0F, 10L,
				activeRecord.totalMass(BuiltinChemicalDefinitions.REGISTRY), precipitate,
				compactCompositions.create(activeRecord), 0, (byte) 0));
		GranularInventory.CompactionStats firstCompaction = compactInventory.compactActive(
				ignored -> 0, ignored -> true, 1.0F, GranularLimits.MAX_OPERATIONS_PER_LOGICAL_STEP);
		GranularInventory.CompactionStats secondCompaction = compactInventory.compactActive(
				ignored -> 0, ignored -> true, 1.0F, GranularLimits.MAX_OPERATIONS_PER_LOGICAL_STEP);
		require(firstCompaction.merges() == 2 && secondCompaction.merges() == 1
				&& compactParcels.count() == 1 && compactCompositions.liveCount() == 1
				&& compactCompositions.speciesTotals()[BuiltinChemicalDefinitions.REGISTRY.speciesHandle(
						BuiltinChemicalDefinitions.NA_PLUS)] == 2_560L,
				"bounded active compaction did not preserve reactive composition ownership");

		int bulkCount = GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION;
		GranularParcelStore bulkParcels = new GranularParcelStore(registry, bulkCount);
		SolidCompositionPool bulkCompositions = new SolidCompositionPool(
				BuiltinChemicalDefinitions.REGISTRY, bulkCount);
		SolidCompositionRecord bulkRecordWithoutResidual = SolidCompositionRecord.fromStableAmounts(
				BuiltinChemicalDefinitions.REGISTRY, java.util.Map.of(
						BuiltinChemicalDefinitions.NA_PLUS, 1L,
						BuiltinChemicalDefinitions.CL_MINUS, 1L), 0.0);
		double bulkMass = 1.0;
		SolidCompositionRecord bulkRecord = SolidCompositionRecord.fromStableAmounts(
				BuiltinChemicalDefinitions.REGISTRY, bulkRecordWithoutResidual.stableAmounts(
						BuiltinChemicalDefinitions.REGISTRY),
				bulkMass - bulkRecordWithoutResidual.exactSpeciesMass(
						BuiltinChemicalDefinitions.REGISTRY).toDouble());
		for (int parcel = 0; parcel < bulkCount; parcel++) {
			bulkParcels.add(new GranularParcelStore.Parcel(0.25F, 0.25F, 0.25F,
					0.0F, 0.0F, 0.0F, 1L, bulkMass, precipitate,
					bulkCompositions.create(bulkRecord), 0, (byte) 0));
		}
		GranularAuditLedger bulkAudit = GranularAuditLedger.captureInventory(
				registry, bulkParcels, new GranularDepositStore(registry, 1, 1), bulkCompositions);
		bulkAudit.validateAgainst(registry, BuiltinChemicalDefinitions.REGISTRY);
	}

	private static void registryDefinitionsAndWorldSnapshotAreStrict() throws Exception {
		String materialJson = "{\"reference_density\":\"2600.0\",\"primary_grain_diameter_meters\":\"0.0005\","
				+ "\"rest_porosity_q\":\"380000\",\"repose_angle_degrees\":\"32.0\",\"static_friction\":\"0.6\","
				+ "\"dynamic_friction\":\"0.45\",\"restitution\":\"0.1\",\"critical_shields\":\"0.05\","
				+ "\"erosion_rate_svq_per_second\":\"4194304\",\"maximum_active_parcel_svq\":\"262144\","
				+ "\"base_argb\":\"#ffc2a065\",\"display_texture\":\"mmm:fine_sand\",\"reactive\":false}";
		ParticulateMaterial decoded = GranularDefinitionCodec.decodeMaterial(
				BuiltinGranularDefinitions.FINE_SAND, new StringReader(materialJson));
		require(decoded.equals(BuiltinGranularDefinitions.createRegistry().material(
				BuiltinGranularDefinitions.createRegistry().materialHandle(BuiltinGranularDefinitions.FINE_SAND))),
				"granular material JSON codec changed the frozen definition");
		expectFailure(() -> GranularDefinitionCodec.decodeMaterial("test:bad",
				new StringReader(materialJson.replace("\"2600.0\"", "2600.0"))),
				"JSON number bypassed granular decimal-string codec");

		var rootUrl = M8GranularValidation.class.getClassLoader().getResource("data/mmm/mmm");
		require(rootUrl != null && rootUrl.getProtocol().equals("file"), "bundled granular resource root is missing");
		Path resourceRoot = Path.of(rootUrl.toURI());
		GranularRegistry bundled = new GranularRegistry(
				decodeDirectory(resourceRoot.resolve("particulate_materials"), GranularDefinitionCodec::decodeMaterial),
				decodeDirectory(resourceRoot.resolve("granular_carriers"), GranularDefinitionCodec::decodeCarrier),
				decodeDirectory(resourceRoot.resolve("solid_liquid_reactions"), GranularDefinitionCodec::decodeReaction),
				BuiltinChemicalDefinitions.REGISTRY);
		GranularRegistry frozen = BuiltinGranularDefinitions.createRegistry();
		require(bundled.fingerprint().equals(frozen.fingerprint()),
				"bundled granular datapack definitions differ from the frozen fixture registry");
		SolidLiquidReaction reaction = frozen.reactions().getFirst();
		expectFailure(() -> new GranularRegistry(frozen.materials(), frozen.carriers(), List.of(
				new SolidLiquidReaction(reaction.id(), reaction.liquidPhaseId(), reaction.aqueousStoichiometry(),
						reaction.solidMaterialId(), reaction.solidComposition(), reaction.saturationCapacityExtentPerBv(),
						1L, 1L, reaction.accessibleSurfaceSvqPerCellStep(), reaction.reversible())),
				BuiltinChemicalDefinitions.REGISTRY),
				"solid-liquid rule accepted a declared SVQ volume inconsistent with exact mass/reference density");

		Path world = Files.createTempDirectory("mmm-granular-registry-");
		try {
			Path snapshot = world.resolve("granular-registry.nbt");
			GranularRegistryCodec.writeSnapshot(snapshot, bundled);
			GranularRegistry rest = GranularRegistryCodec.readSnapshot(snapshot, BuiltinChemicalDefinitions.REGISTRY);
			require(rest.fingerprint().equals(bundled.fingerprint())
							&& Arrays.equals(rest.canonicalBytes(), bundled.canonicalBytes()),
					"granular registry snapshot did not round-trip canonically");
			List<ParticulateMaterial> changedMaterials = new ArrayList<>(bundled.materials());
			ParticulateMaterial original = changedMaterials.getFirst();
			changedMaterials.set(0, new ParticulateMaterial(original.id(), original.referenceDensity(),
					original.primaryGrainDiameterMeters(), original.restPorosityQ(), original.reposeAngleRadians(),
					original.staticFriction(), original.dynamicFriction(), original.restitution(), original.criticalShields(),
					original.erosionRateSvqPerSecond(), original.maximumActiveParcelSvq(), original.baseArgb() ^ 0x00010101,
					original.displayTextureId(), original.reactive()));
			GranularRegistry changed = new GranularRegistry(changedMaterials, bundled.carriers(), bundled.reactions(),
					BuiltinChemicalDefinitions.REGISTRY);
			require(!changed.fingerprint().equals(bundled.fingerprint()),
					"changed granular material colour must change the fingerprint");
			require(!Arrays.equals(GranularRegistryCodec.snapshotBytes(changed),
							GranularRegistryCodec.snapshotBytes(bundled)),
					"changed granular definitions produced identical snapshot bytes");
			var registryTag = net.minecraft.nbt.NbtIo.readCompressed(snapshot,
					net.minecraft.nbt.NbtAccounter.create(4L * 1024L * 1024L));
			registryTag.putString("UnknownField", "must-lock");
			net.minecraft.nbt.NbtIo.writeCompressed(registryTag, snapshot);
			try {
				GranularRegistryCodec.readSnapshot(snapshot, BuiltinChemicalDefinitions.REGISTRY);
				throw new AssertionError("unknown granular registry field was ignored");
			} catch (java.io.IOException expected) { }
			registryTag.remove("UnknownField");
			net.minecraft.nbt.NbtIo.writeCompressed(registryTag, snapshot);
			GranularRegistryCodec.writeSnapshot(snapshot, changed);
			registryTag = net.minecraft.nbt.NbtIo.readCompressed(snapshot,
					net.minecraft.nbt.NbtAccounter.create(4L * 1024L * 1024L));
			registryTag.putString("Fingerprint", bundled.fingerprint());
			net.minecraft.nbt.NbtIo.writeCompressed(registryTag, snapshot);
			try {
				GranularRegistryCodec.readSnapshot(snapshot, BuiltinChemicalDefinitions.REGISTRY);
				throw new AssertionError("granular registry whose content does not match its fingerprint was accepted");
			} catch (java.io.IOException expected) { }
		} finally {
			try (var paths = Files.walk(world)) {
				for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
			}
		}
	}

	private static <T> List<T> decodeDirectory(Path directory, FileDecoder<T> decoder) throws Exception {
		ArrayList<T> result = new ArrayList<>();
		try (var files = Files.walk(directory)) {
			for (Path file : files.filter(path -> path.toString().endsWith(".json")).sorted().toList()) {
				String relative = directory.relativize(file).toString().replace('\\', '/');
				String id = "mmm:" + relative.substring(0, relative.length() - 5);
				try (var reader = Files.newBufferedReader(file)) { result.add(decoder.decode(id, reader)); }
			}
		}
		return List.copyOf(result);
	}

	@FunctionalInterface
	private interface FileDecoder<T> { T decode(String stableId, java.io.Reader reader); }

	private static void registryIsCanonicalAndBounded() {
		GranularRegistry builtins = BuiltinGranularDefinitions.createRegistry();
		GranularRegistry reordered = new GranularRegistry(
				List.of(builtins.materials().get(2), builtins.materials().get(0), builtins.materials().get(1)),
				List.of(builtins.carriers().get(1), builtins.carriers().get(2), builtins.carriers().get(0)),
				builtins.reactions(), BuiltinChemicalDefinitions.REGISTRY);
		require(builtins.fingerprint().equals(reordered.fingerprint()), "registry fingerprint depends on input order");
		expectFailure(() -> new GranularRegistry(List.of(builtins.materials().get(0), builtins.materials().get(0)),
				builtins.carriers()), "duplicate material accepted");
		expectFailure(() -> new ParticulateMaterial("BAD", 1.0, 0.001, 400_000, 0.5,
				0.5, 0.4, 0.1, 0.05, 1L, 1L, 0, "mmm:bad", false), "bad id accepted");
	}

	private static void solidVolumeConversionsAreConservative() {
		for (long svq : new long[] {0L, 1L, SolidVolume.SVQ_PER_CELL / 8L, SolidVolume.SVQ_PER_CELL,
				SolidVolume.SVQ_PER_BV}) {
			require(SolidVolume.fromCellVolumeExact(SolidVolume.toCellVolume(svq)) == svq,
					"SVQ/cell-volume conversion drifted");
		}
		for (long nanos : new long[] {0L, 1L, 100_000L, 123_456_789L, SolidVolume.NANOS_PER_BV}) {
			long svq = SolidVolume.nanosToSvqAtMost(nanos);
			long settled = SolidVolume.svqToNanosAtMost(svq);
			require(settled <= nanos && nanos - settled <= 8L, "SVQ/nanovolume conversion exceeded bound");
		}
	}

	private static void parcelSplitMergePreservesInventory() {
		GranularRegistry registry = BuiltinGranularDefinitions.createRegistry();
		int material = registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		GranularParcelStore store = new GranularParcelStore(registry, 4);
		long volume = registry.material(material).maximumActiveParcelSvq();
		double mass = 7.25;
		store.add(parcel(material, volume, mass));
		int split = store.split(0, volume / 3L);
		require(store.totalSolidVolumeSvq() == volume, "parcel split changed SVQ");
		require(rawClose(store.totalMass(), mass), "parcel split changed mass");
		store.merge(0, split);
		require(store.count() == 1 && store.totalSolidVolumeSvq() == volume, "parcel merge changed SVQ");
		require(rawClose(store.totalMass(), mass), "parcel merge changed mass");
	}

	private static void depositCapacityAndGroupBudgetAreAtomic() {
		GranularRegistry registry = BuiltinGranularDefinitions.createRegistry();
		int reactive = registry.materialHandle(BuiltinGranularDefinitions.PRECIPITATE);
		GranularDepositStore deposits = new GranularDepositStore(registry, 2, 8);
		long portion = SolidVolume.SVQ_PER_CELL / 32L;
		for (int composition = 1; composition <= GranularLimits.MAX_DEPOSIT_GROUPS_PER_CELL; composition++) {
			require(deposits.tryAdd(0, new GranularDepositStore.SolidPortion(reactive, composition, portion, 1.0),
					SolidVolume.SVQ_PER_CELL), "valid deposit group rejected");
		}
		long beforeVolume = deposits.totalSolidVolumeSvq();
		double beforeMass = deposits.totalMass();
		require(!deposits.tryAdd(0, new GranularDepositStore.SolidPortion(reactive, 9, portion, 1.0),
				SolidVolume.SVQ_PER_CELL), "ninth deposit group accepted");
		require(deposits.totalSolidVolumeSvq() == beforeVolume
				&& Double.doubleToRawLongBits(deposits.totalMass()) == Double.doubleToRawLongBits(beforeMass),
				"deposit group rejection left residue");

		int sand = registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		GranularDepositStore full = new GranularDepositStore(registry, 1, 8);
		long tooMuchSkeleton = SolidVolume.SVQ_PER_CELL * 3L / 4L;
		require(!full.tryAdd(0, new GranularDepositStore.SolidPortion(sand, 0, tooMuchSkeleton, 5.0),
				SolidVolume.SVQ_PER_CELL), "bulk-capacity overflow accepted");
		require(full.groupCount() == 0 && full.totalSolidVolumeSvq() == 0L, "capacity rejection left residue");

		GranularDepositStore reordered = new GranularDepositStore(registry, 3, 3);
		require(reordered.tryAdd(2, new GranularDepositStore.SolidPortion(sand, 0, 1L, 1.0e16),
				SolidVolume.SVQ_PER_CELL), "high-cell order fixture was rejected");
		require(reordered.tryAdd(0, new GranularDepositStore.SolidPortion(sand, 0, 1L, 1.0),
				SolidVolume.SVQ_PER_CELL), "first low-cell order fixture was rejected");
		require(reordered.tryAdd(1, new GranularDepositStore.SolidPortion(sand, 0, 1L, 1.0),
				SolidVolume.SVQ_PER_CELL), "second low-cell order fixture was rejected");
		GranularStateSnapshot reorderedSnapshot = GranularStateSnapshot.capture(3, 1, 1, registry,
				new GranularParcelStore(registry, 1), reordered);
		require(reorderedSnapshot.totalMassBits() == Double.doubleToRawLongBits(
				SignedDyadic.sum(new double[] {1.0e16, 1.0, 1.0}).toDouble()),
				"granular snapshot mass depended on sparse deposit allocation order");
	}

	private static void parcelDepositCyclesDoNotDrift(int iterations) {
		GranularRegistry registry = BuiltinGranularDefinitions.createRegistry();
		int material = registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		GranularParcelStore parcels = new GranularParcelStore(registry, 2);
		GranularDepositStore deposits = new GranularDepositStore(registry, 1, 2);
		GranularInventory inventory = new GranularInventory(parcels, deposits);
		long volume = GranularLimits.MAX_PARCEL_VOLUME_SVQ;
		double mass = 2.75;
		parcels.add(parcel(material, volume, mass));
		for (int iteration = 0; iteration < iterations; iteration++) {
			require(inventory.depositParcel(0, 0, SolidVolume.SVQ_PER_CELL), "parcel deposit failed");
			require(inventory.wake(0, material, 0, volume, 0.5F, 0.5F, 0.5F, 0.0F, 0.0F, 0.0F),
					"deposit wake failed");
		}
		require(inventory.totalSolidVolumeSvq() == volume, "parcel/deposit cycles changed SVQ");
		require(rawClose(inventory.totalMass(), mass), "parcel/deposit cycles changed mass");
	}

	private static GranularParcelStore.Parcel parcel(int material, long volume, double mass) {
		return new GranularParcelStore.Parcel(0.5F, 0.5F, 0.5F, 0.25F, -0.5F, 0.125F,
				volume, mass, material, 0, 0, (byte) 0);
	}

	private static void mechanicsMatchesTerminalVelocity() {
		GranularRegistry registry = BuiltinGranularDefinitions.createRegistry();
		for (String id : List.of(BuiltinGranularDefinitions.LIGHT_BEADS, BuiltinGranularDefinitions.FINE_SAND)) {
			int handle = registry.materialHandle(id);
			ParticulateMaterial material = registry.material(handle);
			GranularParcelStore store = new GranularParcelStore(registry, 1);
			long svq = material.maximumActiveParcelSvq();
			double mass = svq / (double) SolidVolume.SVQ_PER_BV * material.referenceDensity();
			store.add(new GranularParcelStore.Parcel(0.5F, 0.5F, 0.5F, 0.0F, 0.0F, 0.0F,
					svq, mass, handle, material.reactive() ? 1 : 0, 0, (byte) 0));
			GranularMechanics.Environment water = constantWater(0.0, 0.0, 0.0, null);
			for (int step = 0; step < 10_000; step++) {
				GranularMechanics.stepVelocities(store, registry, water, 1.0 / 120.0);
			}
			double expected = terminalVelocity(material.referenceDensity(), 1_000.0,
					1.002e-3, material.primaryGrainDiameterMeters());
			double actual = store.parcel(0).velocityY();
			double tolerance = Math.max(2.0e-3, Math.abs(expected) * 0.05);
			require(Math.abs(actual - expected) <= tolerance, "granular terminal velocity mismatch");
		}
		int sand = registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		GranularParcelStore invalid = new GranularParcelStore(registry, 1);
		invalid.add(parcel(sand, 1_024L, 1.0));
		GranularParcelStore.Parcel before = invalid.parcel(0);
		GranularMechanics.Environment narrowCarrier = new GranularMechanics.Environment() {
			@Override public GranularMechanics.FluidSample sample(float x, float y, float z) {
				return new GranularMechanics.FluidSample(true, -10.0, 0.0, 0.0,
						1_000.0, 1.002e-3, 1.0, 1.0, 1.0, 1.0, 1.0);
			}
			@Override public GranularMechanics.FluidImpulseApplication addFluidImpulse(
					float x, float y, float z, double ix, double iy, double iz) {
				return new GranularMechanics.FluidImpulseApplication(ix, iy, iz, 0.0, 0.0, 0.0);
			}
		};
		GranularMechanics.stepVelocities(invalid, registry, narrowCarrier, 1.0 / 20.0);
		require(invalid.parcel(0).velocityX() < before.velocityX()
				&& invalid.parcel(0).velocityX() > -10.0,
				"continued Newton drag must relax finite high-Reynolds slip without overshooting");


	}

	private static void twoWayDragPreservesMomentum() {
		GranularRegistry registry = neutralRegistry();
		int handle = registry.materialHandle("mmm:neutral_probe");
		GranularParcelStore store = new GranularParcelStore(registry, 1);
		long svq = GranularLimits.MAX_PARCEL_VOLUME_SVQ;
		double mass = SolidVolume.toCellVolume(svq) * 1_000.0;
		store.add(new GranularParcelStore.Parcel(0.5F, 0.5F, 0.5F, 1.0F, 0.0F, 0.0F,
				svq, mass, handle, 0, 0, (byte) 0));
		double[] reaction = new double[3];
		GranularMechanics.Environment environment = constantWater(-0.25, 0.0, 0.0, reaction);
		double before = mass * store.parcel(0).velocityX();
		GranularMechanics.stepVelocities(store, registry, environment, 1.0 / 60.0);
		double after = mass * store.parcel(0).velocityX() + reaction[0];
		require(Math.abs(after - before) <= mass * Math.ulp(store.parcel(0).velocityX()),
				"two-way granular drag changed total momentum");
		GranularParcelStore thin = new GranularParcelStore(registry, 1);
		thin.add(new GranularParcelStore.Parcel(0.5F, 0.5F, 0.5F, 0.0F, 1.0F, 0.0F,
				svq, mass, handle, 0, 0, (byte) 0));
		double[] thinReaction = new double[3];
		var thinStats = GranularMechanics.stepVelocities(thin, registry,
				constantWater(0, 0, 0, 1, 1.0e-12, thinReaction), 9.81, 0.05);
		double closedMomentum = mass * thin.parcel(0).velocityY() + thinReaction[1]
				+ thinStats.boundaryImpulseY();
		double expectedMomentum = mass * (1.0 - 9.81 * 0.05);
		require(Math.abs(thinReaction[1] / 1.0e-12) < 1.0
				&& Math.abs(closedMomentum - expectedMomentum) <= 8.0 * Math.ulp(expectedMomentum),
				"thin carrier impulse follows the integrated two-body response");

		GranularParcelStore full = new GranularParcelStore(registry, 1);
		GranularParcelStore shallow = new GranularParcelStore(registry, 1);
		GranularParcelStore.Parcel initial = new GranularParcelStore.Parcel(
				0.5F, 0.5F, 0.5F, 1.0F, 0.0F, 0.0F,
				GranularLimits.MAX_PARCEL_VOLUME_SVQ, mass, handle, 0, 0, (byte) 0);
		full.add(initial); shallow.add(initial);
		double[] fullReaction = new double[3], shallowReaction = new double[3];
		GranularMechanics.stepVelocities(full, registry,
				constantWater(0.0, 0.0, 0.0, 1.0, fullReaction), 0.0, 1.0 / 60.0);
		GranularMechanics.stepVelocities(shallow, registry,
				constantWater(0.0, 0.0, 0.0, 0.125, shallowReaction), 0.0, 1.0 / 60.0);
		require(shallow.parcel(0).velocityX() > full.parcel(0).velocityX()
				&& Math.abs(shallowReaction[0]) < Math.abs(fullReaction[0]),
				"partial immersion applied full-parcel drag to a shallow liquid carrier");

		int parcelCount = 32;
		double parcelMass = 20.0, carrierMass = 1.0;
		GranularParcelStore crowded = new GranularParcelStore(registry, parcelCount);
		for (int parcel = 0; parcel < parcelCount; parcel++) crowded.add(new GranularParcelStore.Parcel(
				0.5F, 0.5F, 0.5F, 1.0F, 0.0F, 0.0F,
				GranularLimits.MAX_PARCEL_VOLUME_SVQ, parcelMass, handle, 0, 0, (byte) 0));
		double[] crowdedReaction = new double[3];
		double crowdedMomentumBefore = parcelCount * parcelMass;
		double crowdedEnergyBefore = 0.5 * parcelCount * parcelMass;

		GranularMechanics.stepVelocities(crowded, registry,
				constantWater(0.0, 0.0, 0.0, 1.0,
						carrierMass, crowdedReaction), 0.0, 1.0 / 20.0);
		double crowdedMomentumAfter = crowdedReaction[0];
		double crowdedEnergyAfter = 0.5 * carrierMass
				* Math.pow(crowdedReaction[0] / carrierMass, 2.0);
		for (int parcel = 0; parcel < crowded.count(); parcel++) {
			crowdedMomentumAfter += parcelMass * crowded.parcel(parcel).velocityX();
			crowdedEnergyAfter += 0.5 * parcelMass * Math.pow(crowded.parcel(parcel).velocityX(), 2.0);
		}
		require(Math.abs(crowdedMomentumAfter - crowdedMomentumBefore) <= 1.0e-5
				&& crowdedEnergyAfter <= crowdedEnergyBefore + 1.0e-7,
				"many parcels coupled to one finite carrier injected momentum or kinetic energy");
	}

	private static void couplingOccupancyResumsExactly() {
		GranularRegistry registry = BuiltinGranularDefinitions.createRegistry();
		int sand = registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		GranularParcelStore parcels = new GranularParcelStore(registry, 2);
		GranularDepositStore deposits = new GranularDepositStore(registry, 4 * 4 * 4, 4);
		long parcelVolume = GranularLimits.MAX_PARCEL_VOLUME_SVQ - 17L;
		parcels.add(new GranularParcelStore.Parcel(0.73F, 0.91F, 1.12F, 0.0F, 0.0F, 0.0F,
				parcelVolume, 1.0, sand, 0, 0, (byte) 0));
		require(deposits.tryAdd(0, new GranularDepositStore.SolidPortion(sand, 0, 123_457L, 0.5),
				SolidVolume.SVQ_PER_CELL), "fixture deposit rejected");
		GranularCouplingFields fields = new GranularCouplingFields(4, 4, 4, 0.5);
		fields.rebuild(parcels, deposits);
		long[] before = fields.copySolidSvq();
		var previousPosition = new GranularCouplingFields.Position(0.73F, 0.91F, 1.12F);
		for (int trial = 0; trial < 40; trial++) {
			var replacement = new GranularCouplingFields.Position(0.05F + trial % 7 * 0.3F,
					0.05F + trial % 5 * 0.4F, 0.05F + trial % 6 * 0.3F);
			long limit = trial % 2 == 0 ? 100 : SolidVolume.SVQ_PER_CELL;
			long[] planned = before.clone();
			fields.removePlannedParcel(planned, previousPosition, parcelVolume);
			boolean expected = fields.tryPlanParcel(planned, replacement, parcelVolume + 19, cell -> limit);
			require(fields.canReplaceParcel(previousPosition, parcelVolume, replacement, parcelVolume + 19, cell -> limit)
					== expected && fields.matchesSolidSvq(before), "local replacement matches copied occupancy planning");
			planned = before.clone();
			expected = fields.tryPlanParcel(planned, replacement, parcelVolume, cell -> limit);
			require(fields.canReplaceParcel(null, 0, replacement, parcelVolume, cell -> limit) == expected
					&& fields.matchesSolidSvq(before), "local insertion keeps occupancy read-only");
		}
		require(fields.totalSolidSvq() == parcelVolume + 123_457L, "coupling field lost SVQ");
		GranularParcelStore.Parcel old = parcels.parcel(0);
		parcels.overwrite(0, new GranularParcelStore.Parcel(1.23F, 0.61F, 0.72F,
				old.velocityX(), old.velocityY(), old.velocityZ(), old.solidVolumeSvq(), old.mass(),
				old.materialHandle(), old.solidCompositionHandle(), old.contactAge(), old.flags()));
		fields.rebuild(parcels, deposits);
		long[] after = fields.copySolidSvq();
		long delta = 0L;
		for (int cell = 0; cell < before.length; cell++) delta = Math.addExact(delta, after[cell] - before[cell]);
		require(delta == 0L, "moving granular occupancy does not exact re-sum");
		require(fields.faceVoidScale(0, 1.0F, 1, 1.0F) == fields.faceVoidScale(1, 1.0F, 0, 1.0F),
				"granular face void scale is asymmetric");
		long[] fixed = new long[before.length]; fixed[3] = 79L; fixed[11] = 113L;
		fields.setFixedCellSvq(fixed);
		fixed[3] = 0L;
		fields.rebuild(parcels, deposits);
		require(fields.totalSolidSvq() == parcelVolume + 123_457L + 192L,
				"active occupancy lost fixed solid or retained a caller-owned array");
		fields.clear(); fields.clear();
		var emptyParcels = new GranularParcelStore(registry, 2);
		var emptyDeposits = new GranularDepositStore(registry, before.length, 4);
		fields.rebuild(emptyParcels, emptyDeposits);
		require(fields.totalSolidSvq() == 192L && fields.solidSvq(3) == 79L && fields.solidSvq(11) == 113L,
				"empty granular rebuild did not restore the exact fixed-only occupancy");
		fields.setFixedCellSvq(new long[before.length]);
		fields.rebuild(emptyParcels, emptyDeposits);
		require(fields.totalSolidSvq() == 0L && fields.matchesSolidSvq(new long[before.length]),
				"replacing fixed occupancy left a stale empty-store cache");
	}

	private static GranularRegistry neutralRegistry() {
		return new GranularRegistry(List.of(new ParticulateMaterial("mmm:neutral_probe", 1_000.0, 0.0005,
				400_000, Math.toRadians(30.0), 0.6, 0.4, 0.1, 0.05, 1L,
				GranularLimits.MAX_PARCEL_VOLUME_SVQ, 0xffffffff, "mmm:neutral_probe", false)),
				List.of(new GranularCarrier("mmm:water", 100_000.0)));
	}

	private static void porousBedPreservesHydrostatics() {
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F,
				200, 1.0e-6F, 0.45F, 2.0e-3F, 0.8F);
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE);
		GranularRegistry registry = BuiltinGranularDefinitions.createRegistry();
		int sizeX = 2, sizeY = 5, sizeZ = 2;
		FluidDomain domain = new FluidDomain(sizeX, sizeY, sizeZ, config, sizeX * sizeY * sizeZ * 8);
		for (int z = 0; z < sizeZ; z++) for (int x = 0; x < sizeX; x++) {
			domain.setVolume(x, 0, z, 0.75F);
			domain.setVolume(x, 1, z, 1.0F);
			domain.setVolume(x, 2, z, 0.75F);
		}
		int sand = registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		long cellSvq = SolidVolume.SVQ_PER_CELL / 4L;
		double cellMass = cellSvq / (double) SolidVolume.SVQ_PER_BV
				* registry.material(sand).referenceDensity();
		ArrayList<GranularStateSnapshot.DepositGroup> groups = new ArrayList<>();
		for (int z = 0; z < sizeZ; z++) for (int x = 0; x < sizeX; x++) groups.add(
				new GranularStateSnapshot.DepositGroup(cellIndex(x, 0, z, sizeX, sizeY),
						registry.material(sand).restPorosityQ(), sand, 0, cellSvq, cellMass));
		long totalSvq = Math.multiplyExact(cellSvq, groups.size());
		double totalMass = cellMass * groups.size();
		domain.restoreGranularState(new GranularStateSnapshot(sizeX, sizeY, sizeZ, registry.fingerprint(),
				registry.materials().stream().map(ParticulateMaterial::id).toList(), List.of(), groups,
				totalSvq, Double.doubleToRawLongBits(totalMass)), registry, liquids, BuiltinFluidMaterials.createRegistry());

		FluidDomain.StepStats stats = null;
		for (int step = 0; step < 200; step++) stats = domain.step();
		float[] actualPressure = {domain.pressure(0, 0, 0), domain.pressure(0, 1, 0), domain.pressure(0, 2, 0)};
		float expectedGradient = config.density() * config.gravity() * config.cellSize();
		for (int y = 0; y < 2; y++) {
			float actualGradient = actualPressure[y] - actualPressure[y + 1];
			float relativeError = Math.abs(actualGradient - expectedGradient) / expectedGradient;
			require(relativeError < 3.0e-4F, "porous granular bed missed analytic hydrostatic gradient: y="
					+ y + ", pressure=" + Arrays.toString(actualPressure) + ", expectedGradient="
					+ expectedGradient + ", speed=" + domain.maximumParticleSpeed() + ", error=" + relativeError);
		}
		require(stats.pressureIterations() > 0 && Float.isFinite(stats.pressureResidual())
				&& domain.maximumParticleSpeed() < 3.0e-4F,
				"porous granular bed left a non-hydrostatic liquid state: speed="
						+ domain.maximumParticleSpeed() + ", PCG=" + stats.pressureIterations()
						+ "/" + stats.pressureResidual());
		require(domain.totalGranularSolidVolumeSvq() == totalSvq && rawClose(domain.totalGranularMass(), totalMass),
				"porous hydrostatic projection changed granular truth");
	}

	private static void settlingRelaxationSleepAndWakeAreLocal() {
		GranularRegistry registry = BuiltinGranularDefinitions.createRegistry();
		int sand = registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		int sizeX = 5, sizeY = 2, sizeZ = 3, cellCount = sizeX * sizeY * sizeZ;
		GranularParcelStore parcels = new GranularParcelStore(registry, 8);
		GranularDepositStore deposits = new GranularDepositStore(registry, cellCount, 32);
		GranularInventory inventory = new GranularInventory(parcels, deposits);
		GranularDepositDynamics dynamics = new GranularDepositDynamics(
				registry, parcels, deposits, inventory, sizeX, sizeY, sizeZ, 0.5F);
		long initialSvq = SolidVolume.SVQ_PER_CELL * 3L / 5L;
		double initialMass = initialSvq / (double) SolidVolume.SVQ_PER_BV
				* registry.material(sand).referenceDensity();
		long parcelSvq = GranularLimits.MAX_PARCEL_VOLUME_SVQ;
		double parcelMass = initialMass * parcelSvq / initialSvq;
		parcels.add(new GranularParcelStore.Parcel(0.75F, 0.05F, 0.75F, 0.0F, 0.0F, 0.0F,
				parcelSvq, parcelMass, sand, 0, GranularDepositDynamics.SETTLE_CONTACT_STEPS,
				GranularParcelStore.FLAG_SUPPORTED));
		TestDepositEnvironment calm = new TestDepositEnvironment(sizeX, sizeY, sizeZ, 0.0);
		GranularDepositDynamics.Stats settled = dynamics.step(calm, 0.05F);
		require(settled.settledParcels() == 1 && parcels.count() == 0, "supported granular parcel did not settle");
		int centerCell = cellIndex(1, 0, 1, sizeX, sizeY);
		require(deposits.tryAdd(centerCell, new GranularDepositStore.SolidPortion(
				sand, 0, initialSvq - parcelSvq, initialMass - parcelMass), SolidVolume.SVQ_PER_CELL),
				"repose fixture deposit was rejected");
		dynamics.disturbCell(centerCell);
		require(inventory.totalSolidVolumeSvq() == initialSvq && rawClose(inventory.totalMass(), initialMass),
				"settling changed exact granular inventory");

		for (int step = 0; step < 128 && dynamics.dirtyColumnCount() > 0; step++) dynamics.step(calm, 0.05F);
		require(dynamics.dirtyColumnCount() == 0, "stable granular surface did not leave the dirty queue");
		require(dynamics.sleepingColumnCount() * 5 >= dynamics.occupiedColumnCount() * 4,
				"less than 80% of a stable deposit reached sleep");
		assertReposeBound(deposits, registry.material(sand).reposeAngleRadians(), sizeX, sizeY, sizeZ);

		int remoteCell = cellIndex(4, 0, 1, sizeX, sizeY);
		long remoteSvq = GranularLimits.MAX_PARCEL_VOLUME_SVQ;
		double remoteMass = remoteSvq / (double) SolidVolume.SVQ_PER_BV
				* registry.material(sand).referenceDensity();
		require(deposits.tryAdd(remoteCell, new GranularDepositStore.SolidPortion(sand, 0, remoteSvq, remoteMass),
				SolidVolume.SVQ_PER_CELL), "remote wake fixture deposit was rejected");
		long beforeSubcritical = inventory.totalSolidVolumeSvq();
		double massBeforeWake = inventory.totalMass();
		long remoteBefore = deposits.cellSolidVolumeSvq(remoteCell);
		dynamics.disturbCell(centerCell);
		GranularDepositDynamics.Stats quiet = dynamics.step(calm, 0.05F);
		require(quiet.wokenParcels() == 0 && parcels.count() == 0
				&& inventory.totalSolidVolumeSvq() == beforeSubcritical, "subcritical flow caused granular creep");

		TestDepositEnvironment eroding = new TestDepositEnvironment(sizeX, sizeY, sizeZ, 1.0) {
			@Override public double shields(int cell, ParticulateMaterial material) { return cell == remoteCell ? 0 : 1; }
		};
		GranularDepositDynamics.Stats wake = dynamics.step(eroding, 0.05F);
		require(wake.wokenParcels() > 0 && parcels.count() == wake.wokenParcels(),
				"supercritical exposed deposit did not wake locally");
		require(deposits.cellSolidVolumeSvq(remoteCell) == remoteBefore,
				"local shear wake touched a sleeping remote deposit");
		require(inventory.totalSolidVolumeSvq() == beforeSubcritical && rawClose(inventory.totalMass(), massBeforeWake),
				"local granular wake changed exact inventory");
	}

	private static void blockedSettlementPreservesKineticEnergy() {
		var registry = BuiltinGranularDefinitions.createRegistry();
		int sand = registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		long svq = 1_024L;
		double mass = svq / (double) SolidVolume.SVQ_PER_BV * registry.material(sand).referenceDensity();
		for (int slope : new int[]{-1, 0, 1}) for (boolean moving : new boolean[]{false, true}) {
			var parcels = new GranularParcelStore(registry, 2);
			var deposits = new GranularDepositStore(registry, 3, 4);
			var inventory = new GranularInventory(parcels, deposits);
			var dynamics = new GranularDepositDynamics(registry, parcels, deposits, inventory, 3, 1, 1, 0.5F);
			for (int cell = 0; cell < 3; cell++) if (slope > 0 && cell == 1 || slope < 0 && cell != 1) {
				require(deposits.tryAdd(cell, new GranularDepositStore.SolidPortion(sand, 0,
						SolidVolume.SVQ_PER_CELL / 4, 2_600.0 / 32), SolidVolume.SVQ_PER_CELL),
						"rolling fixture requires its declared bed slope");
			}
			var original = new GranularParcelStore.Parcel(0.75F, 0.3F, 0.25F,
					moving ? 0.03F : 0, moving ? 0.04F : 0, moving ? 0.12F : 0,
					svq, mass, sand, 0, GranularDepositDynamics.SETTLE_CONTACT_STEPS, GranularParcelStore.FLAG_SUPPORTED);
			parcels.add(original);
			dynamics.step(new TestDepositEnvironment(3, 1, 1, 0) {
				@Override public boolean planSettlement(GranularParcelStore.Parcel parcel, int cell) { return false; }
			}, 0.05F);
			var after = parcels.parcel(0);
			if (slope <= 0 || !moving) require(after.equals(original),
					"blocked settlement cannot accelerate a resting parcel or steer it across a flat bed");
			else require(after.velocityZ() == 0 && Math.abs(Math.abs(after.velocityX()) - 0.13F) <= Math.ulp(0.13F)
						&& after.velocityY() == 0 && after.flags() == 0,
					"rolling reorients existing kinetic energy toward a lower bed surface");
		}
	}

	private static void assertReposeBound(GranularDepositStore deposits, double repose,
	                                      int sizeX, int sizeY, int sizeZ) {
		long[] bulk = new long[sizeX * sizeZ];
		for (int z = 0; z < sizeZ; z++) for (int x = 0; x < sizeX; x++) {
			for (int y = 0; y < sizeY; y++) {
				bulk[z * sizeX + x] += deposits.cellBulkVolumeSvq(cellIndex(x, y, z, sizeX, sizeY));
			}
		}
		for (int z = 0; z < sizeZ; z++) for (int x = 0; x < sizeX; x++) {
			for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
				if (dx == 0 && dz == 0 || x + dx < 0 || x + dx >= sizeX || z + dz < 0 || z + dz >= sizeZ) continue;
				double difference = Math.abs((double) bulk[z * sizeX + x] - bulk[(z + dz) * sizeX + x + dx])
						/ SolidVolume.SVQ_PER_CELL;
				double limit = Math.hypot(dx, dz) * Math.tan(repose) + 2.0e-6;
				require(difference <= limit, "granular deposit exceeded its eight-neighbor repose angle");
			}
		}
	}

	private static int cellIndex(int x, int y, int z, int sizeX, int sizeY) {
		return (z * sizeY + y) * sizeX + x;
	}

	private static class TestDepositEnvironment implements GranularDepositDynamics.Environment {
		private final int sizeX, sizeY, sizeZ;
		private final double shields;

		private TestDepositEnvironment(int sizeX, int sizeY, int sizeZ, double shields) {
			this.sizeX = sizeX; this.sizeY = sizeY; this.sizeZ = sizeZ; this.shields = shields;
		}

		@Override public long geometricOpenSvq(int cell) { return SolidVolume.SVQ_PER_CELL; }
		@Override public boolean horizontalPathOpen(int fromCell, int directionX, int directionZ) {
			int x = fromCell % sizeX, z = fromCell / (sizeX * sizeY);
			return x + directionX >= 0 && x + directionX < sizeX
					&& z + directionZ >= 0 && z + directionZ < sizeZ;
		}
		@Override public double shields(int surfaceCell, ParticulateMaterial material) { return shields; }

	}

	private static void solverCouplingPreservesInventories(int steps) {
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F,
				200, 1.0e-5F, 0.45F, 2.0e-3F, 0.8F);
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE);
		GranularRegistry granular = BuiltinGranularDefinitions.createRegistry();
		FluidDomain domain = new FluidDomain(4, 8, 4, config, 4 * 8 * 4 * 8);
		for (int z = 0; z < 4; z++) for (int y = 0; y < 3; y++) for (int x = 0; x < 4; x++) {
			domain.setVolume(x, y, z, 1.0F);
		}
		domain.bindGranular(granular, liquids, BuiltinFluidMaterials.createRegistry());
		int sand = granular.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		long svq = GranularLimits.MAX_PARCEL_VOLUME_SVQ;
		double mass = svq / (double) SolidVolume.SVQ_PER_BV * granular.material(sand).referenceDensity();
		FluidDomain.GranularInsertion inserted = domain.tryInsertExactGranular(2, 5, 2, sand, 0, svq, mass);
		require(inserted.inserted(), "solver granular insertion was rejected: " + inserted.failure());
		double waterBefore = domain.totalVolumeExact();
		var backend = new cc.ecisr.mmm.fluid.CpuFluidSolverBackend();
		for (int step = 0; step < steps; step++) {
			var candidate = backend.evaluate(new cc.ecisr.mmm.fluid.FluidStepRequest(
					step, domain.captureAuthoritativeState(), List.of(), false, 0));
			domain = candidate.claimPreparedCpuDomain();
			FluidDomain.StepStats stats = candidate.stats();
			require(Float.isFinite(stats.pressureResidual()), "granular pressure coupling produced non-finite residual");
		}
		require(Double.doubleToRawLongBits(domain.totalVolumeExact()) == Double.doubleToRawLongBits(waterBefore),
				"granular coupling changed exact liquid volume");
		require(domain.totalGranularSolidVolumeSvq() == svq, "solver granular coupling changed solid SVQ");
		require(rawClose(domain.totalGranularMass(), mass), "solver granular coupling changed solid mass");

		FluidDomain sealed = new FluidDomain(3, 3, 3, config, 3 * 3 * 3 * 8);
		for (int z = 0; z < 3; z++) for (int y = 0; y < 3; y++) for (int x = 0; x < 3; x++) {
			sealed.setVolume(x, y, z, 1.0F);
		}
		sealed.bindGranular(granular, liquids, BuiltinFluidMaterials.createRegistry());
		FluidDomain.GranularInsertion refused = sealed.tryInsertExactGranular(1, 1, 1, sand, 0, svq, mass);
		require(!refused.inserted() && sealed.totalGranularSolidVolumeSvq() == 0L,
				"sealed granular insertion did not refuse atomically");
	}

	private static void wholeBucketCongestionRemainsLocal(int steps) throws Exception {
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F,
				100, 1.0e-5F, 0.45F, 2.0e-3F, 0.8F);
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE);
		GranularRegistry granular = BuiltinGranularDefinitions.createRegistry();
		FluidDomain domain = new FluidDomain(8, 8, 8, config, 8 * 8 * 8 * 8);
		domain.bindGranular(granular, liquids, BuiltinFluidMaterials.createRegistry());
		double density = granular.material(granular.materialHandle(BuiltinGranularDefinitions.FINE_SAND))
				.referenceDensity();
		GranularTransferLedger bucket = new GranularTransferLedger(List.of(new GranularTransferLedger.Group(
				BuiltinGranularDefinitions.FINE_SAND, SolidVolume.SVQ_PER_BV,
				density, null)));
		require(domain.tryInsertExactGranular(4, 6, 4, bucket, 3).inserted()
				&& domain.tryInsertExactGranular(4, 6, 4, bucket, 3).inserted(),
				"two-bucket granular congestion fixture could not be inserted");
		FullCapabilityTestFixture.Registries production = FullCapabilityTestFixture.load();
		domain.bindGasRegistry(production.gas());
		domain.bindThermal(production.thermal(), liquids, 300.0);
		domain = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
		require(domain.totalGranularSolidVolumeSvq() == 2L * SolidVolume.SVQ_PER_BV,
				"thermal backend handoff rejected or changed a two-bucket positioned granular inventory");
		long releaseCells = domain.exportGranularState().activeParcels().stream()
				.map(parcel -> cellIndex(
						Math.min(7, (int) (parcel.x() / 0.5F)),
						Math.min(7, (int) (parcel.y() / 0.5F)),
						Math.min(7, (int) (parcel.z() / 0.5F)), 8, 8))
				.distinct().count();
		require(releaseCells >= 24L,
				"two-bucket release was packed into too few solver cells: " + releaseCells);
		require(!domain.granularReadyForHydrostaticSleep(),
				"active granular inventory incorrectly allowed hydrostatic sleep");
		long exactSvq = Math.multiplyExact(2L, SolidVolume.SVQ_PER_BV);
		double exactMass = 2.0 * bucket.totalMass();
		CpuFluidSolverBackend backend = new CpuFluidSolverBackend();
		FluidDomainState handoffState = domain.captureAuthoritativeState();
		long generation = 0L;
		long previous = granularMotionSignature(domain.exportGranularState());
		int unchangedSteps = 0;
		int emptyAt = -1;
		boolean moved = false, locallyHeld = false;
		for (int step = 0; step < steps; step++) {
			try {
				FluidStepCandidate candidate = backend.evaluate(new FluidStepRequest(
						generation, handoffState, List.of(), false, 0));
				handoffState = candidate.state();
				domain = FluidDomain.restoreAuthoritativeState(handoffState);
				generation++;
			} catch (RuntimeException failure) {
				GranularStateSnapshot failed = domain.exportGranularState();
				throw new AssertionError("whole-bucket congestion failed at logical step " + step
						+ ": active=" + failed.activeParcels().size() + ", deposits="
						+ failed.depositGroups().size() + ", held=" + domain.lastGranularCapacityHeld()
						+ ", dynamics=" + domain.lastGranularDepositStats(), failure);
			}
			GranularStateSnapshot state = domain.exportGranularState();
			if (emptyAt < 0 && state.activeParcels().isEmpty()) emptyAt = step + 1;
			assertTransientPileIsGroundedAndBounded(state,
					granular.material(granular.materialHandle(BuiltinGranularDefinitions.FINE_SAND)));
			long signature = granularMotionSignature(state);
			if (!state.activeParcels().isEmpty() && signature == previous) unchangedSteps++;
			else unchangedSteps = 0;
			moved |= signature != previous;
			locallyHeld |= !state.activeParcels().isEmpty() && !state.depositGroups().isEmpty();
			require(unchangedSteps < 6,
					"whole-bucket granular inventory froze globally while active parcels remained: step=" + step
							+ ", active=" + state.activeParcels().size() + ", deposits="
							+ state.depositGroups().size() + ", held=" + domain.lastGranularCapacityHeld());
			require(domain.totalGranularSolidVolumeSvq() == exactSvq
					&& rawClose(domain.totalGranularMass(), exactMass),
					"whole-bucket congestion changed exact granular inventory");
			previous = signature;
		}
		require(moved && locallyHeld,
				"whole-bucket fixture did not exercise moving and locally held parcels together");
		GranularStateSnapshot settled = domain.exportGranularState();
		require(!settled.depositGroups().isEmpty() && settled.activeParcels().size() < 1_024,
				"whole-bucket local contacts did not make progress into conservative deposits");
		if (steps >= 200) require(settled.activeParcels().size() <= 64,
				"two-bucket loose pour left too much active inventory after ten seconds");
		if (!settled.activeParcels().isEmpty()) require(!domain.granularReadyForHydrostaticSleep(),
				"unfinished granular settlement incorrectly allowed hydrostatic sleep");
		else require(domain.granularReadyForHydrostaticSleep() && domain.granularSleepingColumnCount() > 0,
				"settled granular inventory never reached sleep across production backend handoffs");
		long floorActive = settled.activeParcels().stream().filter(parcel -> parcel.y() < 0.2F).count();
		long movingActive = settled.activeParcels().stream().filter(parcel ->
				parcel.velocityX() * parcel.velocityX() + parcel.velocityY() * parcel.velocityY()
						+ parcel.velocityZ() * parcel.velocityZ() > 0.025F * 0.025F).count();
		long activeColumns = settled.activeParcels().stream().map(parcel ->
				Math.min(7, (int) (parcel.z() / 0.5F)) * 8 + Math.min(7, (int) (parcel.x() / 0.5F)))
				.distinct().count();
		System.out.printf("  two-bucket granular fixture after %d steps: active=%d emptyAt=%d floor=%d moving=%d columns=%d depositGroups=%d%n",
				steps, settled.activeParcels().size(), emptyAt, floorActive, movingActive, activeColumns,
				settled.depositGroups().size());
		FluidDomain.GranularClear cleared = domain.removeAllGranularExact();
		GranularStateSnapshot clearedState = domain.exportGranularState();
		require(cleared.solidVolumeSvq() == exactSvq && rawClose(cleared.mass(), exactMass)
				&& cleared.activeParcels() == settled.activeParcels().size()
				&& cleared.depositGroups() == settled.depositGroups().size()
				&& domain.totalGranularSolidVolumeSvq() == 0L && domain.granularDepositCellCount() == 0
				&& clearedState.totalSolidVolumeSvq() == 0L
				&& clearedState.escapedSink().entries().stream()
				.mapToLong(GranularAuditLedger.Entry::solidVolumeSvq).sum() == exactSvq
				&& domain.granularEscapedSink().entries().stream()
				.mapToLong(GranularAuditLedger.Entry::solidVolumeSvq).sum() == exactSvq,
				"mixed active/deposit operator clear did not close the escaped sink");
	}

	private static void singleBucketPourSettlesWithinTenSeconds() {
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F,
				100, 1.0e-5F, 0.45F, 2.0e-3F, 0.8F);
		FluidMaterialRegistry liquids = new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE);
		GranularRegistry granular = BuiltinGranularDefinitions.createRegistry();
		FluidDomain domain = new FluidDomain(8, 8, 8, config, 8 * 8 * 8 * 8);
		domain.bindGranular(granular, liquids, BuiltinFluidMaterials.createRegistry());
		double density = granular.material(granular.materialHandle(BuiltinGranularDefinitions.FINE_SAND))
				.referenceDensity();
		GranularTransferLedger bucket = new GranularTransferLedger(List.of(new GranularTransferLedger.Group(
				BuiltinGranularDefinitions.FINE_SAND, SolidVolume.SVQ_PER_BV,
				density, null)));
		require(domain.tryInsertExactGranular(4, 6, 4, bucket, 3, true).inserted(),
				"single-bucket pour fixture could not be inserted");
		ParticulateMaterial sand = granular.material(granular.materialHandle(BuiltinGranularDefinitions.FINE_SAND));
		int emptyAt = -1;
		for (int step = 0; step < 200; step++) {
			domain.step();
			GranularStateSnapshot state = domain.exportGranularState();
			assertTransientPileIsGroundedAndBounded(state, sand);
			if (emptyAt < 0 && state.activeParcels().isEmpty()) emptyAt = step + 1;
			require(domain.totalGranularSolidVolumeSvq() == SolidVolume.SVQ_PER_BV
					&& rawClose(domain.totalGranularMass(), bucket.totalMass()),
					"single-bucket pour changed exact granular inventory");
		}
		GranularStateSnapshot settled = domain.exportGranularState();
		require(emptyAt > 0 && emptyAt <= 200 && settled.activeParcels().isEmpty(),
				"single-bucket pour left active display parcels after ten seconds");
		require(!settled.depositGroups().isEmpty() && domain.granularReadyForHydrostaticSleep(),
				"completed single-bucket deposit did not reach granular sleep readiness");
		CpuFluidSolverBackend backend = new CpuFluidSolverBackend();
		FluidDomainState sleepingHandoff = domain.captureAuthoritativeState();
		for (int generation = 0; generation < 3; generation++) {
			FluidStepCandidate candidate = backend.evaluate(new FluidStepRequest(
					generation, sleepingHandoff, List.of(), false, 0));
			require(candidate.stats().granularTransition().processedColumns() == 0,
					"backend reconstruction re-dirtied an already sleeping deposit");
			sleepingHandoff = candidate.state();
			domain = FluidDomain.restoreAuthoritativeState(sleepingHandoff);
			require(domain.granularReadyForHydrostaticSleep(),
					"backend handoff discarded granular sleep progress");
		}
		long bulkSvq = 0L;
		for (GranularStateSnapshot.DepositGroup group : settled.depositGroups()) {
			bulkSvq = Math.addExact(bulkSvq,
					SolidVolume.bulkVolumeCeil(group.solidVolumeSvq(), group.porosityQ()));
		}
		long expectedBulkSvq = SolidVolume.bulkVolumeCeil(SolidVolume.SVQ_PER_BV, sand.restPorosityQ());
		require(bulkSvq >= expectedBulkSvq
				&& bulkSvq - expectedBulkSvq < settled.depositGroups().size(),
				"single-bucket derived bulk volume changed across deposit cells");
		System.out.printf("  single-bucket loose pour settled all active parcels at step %d, bulk=%.6f BV%n",
				emptyAt, bulkSvq / (double) SolidVolume.SVQ_PER_BV);
		FluidDomain.GranularClear cleared = domain.removeAllGranularExact();
		long sourceSvq = domain.granularExternalSource().entries().stream()
				.mapToLong(GranularAuditLedger.Entry::solidVolumeSvq).sum();
		long sinkSvq = domain.granularEscapedSink().entries().stream()
				.mapToLong(GranularAuditLedger.Entry::solidVolumeSvq).sum();
		require(cleared.solidVolumeSvq() == SolidVolume.SVQ_PER_BV
				&& rawClose(cleared.mass(), bucket.totalMass()) && sourceSvq == sinkSvq
				&& sinkSvq == SolidVolume.SVQ_PER_BV && domain.totalGranularSolidVolumeSvq() == 0L
				&& domain.copyGranularDisplaySnapshot().activeParcels().isEmpty()
				&& domain.granularDepositCellCount() == 0 && domain.granularReadyForHydrostaticSleep(),
				"single-bucket operator clear did not erase display/state or close source/sink audit");
	}

	private static void assertTransientPileIsGroundedAndBounded(
			GranularStateSnapshot state, ParticulateMaterial material
	) {
		int sizeX = state.sizeX(), sizeY = state.sizeY(), sizeZ = state.sizeZ();
		long[] skeleton = new long[sizeX * sizeY * sizeZ];
		int[] porosity = new int[skeleton.length];
		for (GranularStateSnapshot.DepositGroup group : state.depositGroups()) {
			skeleton[group.cell()] = Math.addExact(skeleton[group.cell()], group.solidVolumeSvq());
			if (porosity[group.cell()] != 0 && porosity[group.cell()] != group.porosityQ()) {
				throw new AssertionError("deposit groups disagree on cell porosity");
			}
			porosity[group.cell()] = group.porosityQ();
		}
		long[] surface = new long[sizeX * sizeZ];
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int cell = cellIndex(x, y, z, sizeX, sizeY);
			if (skeleton[cell] == 0L) continue;
			if (y > 0) require(skeleton[cellIndex(x, y - 1, z, sizeX, sizeY)] > 0L,
					"granular settlement created an unsupported floating deposit cell at "
							+ x + "," + y + "," + z + " with skeleton=" + skeleton[cell]);
			long bulk = SolidVolume.bulkVolumeCeil(skeleton[cell], porosity[cell]);
			surface[z * sizeX + x] = Math.max(surface[z * sizeX + x],
					Math.addExact(Math.multiplyExact((long) y, SolidVolume.SVQ_PER_CELL), bulk));
		}
		long discreteTolerance = 2L * SolidVolume.bulkVolumeCeil(
				GranularLimits.MAX_PARCEL_VOLUME_SVQ, material.restPorosityQ());
		for (int z = 0; z < sizeZ; z++) for (int x = 0; x < sizeX; x++) {
			for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
				if (dx == 0 && dz == 0 || x + dx < 0 || x + dx >= sizeX
						|| z + dz < 0 || z + dz >= sizeZ) continue;
				long rise = Math.abs(surface[z * sizeX + x] - surface[(z + dz) * sizeX + x + dx]);
				long repose = (long) Math.floor(Math.hypot(dx, dz) * Math.tan(material.reposeAngleRadians())
						* SolidVolume.SVQ_PER_CELL);
				require(rise <= repose + discreteTolerance,
						"transient granular settlement produced an over-steep deposit column at "
								+ x + "," + z + " toward " + (x + dx) + "," + (z + dz)
								+ ": rise=" + rise + ", limit=" + (repose + discreteTolerance));
			}
		}
	}

	private static long granularMotionSignature(GranularStateSnapshot state) {
		long signature = 0xcbf29ce484222325L;
		for (GranularStateSnapshot.ActiveParcel parcel : state.activeParcels()) {
			signature = (signature ^ Float.floatToRawIntBits(parcel.x())) * 0x100000001b3L;
			signature = (signature ^ Float.floatToRawIntBits(parcel.y())) * 0x100000001b3L;
			signature = (signature ^ Float.floatToRawIntBits(parcel.z())) * 0x100000001b3L;
		}
		for (GranularStateSnapshot.DepositGroup group : state.depositGroups()) {
			signature = (signature ^ group.cell()) * 0x100000001b3L;
			signature = (signature ^ group.solidVolumeSvq()) * 0x100000001b3L;
		}
		return signature;
	}

	private static void emptyGranularGateHasBoundedWallTime(int samples) {
		FluidSolverConfig config = new FluidSolverConfig(0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F,
				100, 1.0e-5F, 0.45F, 2.0e-3F, 0.8F);
		int sizeX = 24, sizeY = 16, sizeZ = 24, capacity = sizeX * sizeY * sizeZ * 8;
		FluidDomain baseline = new FluidDomain(sizeX, sizeY, sizeZ, config, capacity);
		FullCapabilityTestFixture.bindEmptyMaterialState(baseline,
				new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE));
		baseline.bindMaterialDefinitions(BuiltinFluidMaterials.createRegistry());
		FluidDomain granular = new FluidDomain(sizeX, sizeY, sizeZ, config, capacity);
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < 2; y++) for (int x = 0; x < sizeX; x++) {
			baseline.setVolume(x, y, z, 1.0F); granular.setVolume(x, y, z, 1.0F);
		}
		granular.bindGranular(BuiltinGranularDefinitions.createRegistry(),
				new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE), BuiltinFluidMaterials.createRegistry());
		for (int warmup = 0; warmup < 8; warmup++) { baseline.step(); granular.step(); }
		long[] baselineNanos = new long[samples], granularNanos = new long[samples];
		for (int sample = 0; sample < samples; sample++) {
			long start;
			if ((sample & 1) == 0) {
				start = System.nanoTime(); baseline.step(); baselineNanos[sample] = System.nanoTime() - start;
				start = System.nanoTime(); granular.step(); granularNanos[sample] = System.nanoTime() - start;
			} else {
				start = System.nanoTime(); granular.step(); granularNanos[sample] = System.nanoTime() - start;
				start = System.nanoTime(); baseline.step(); baselineNanos[sample] = System.nanoTime() - start;
			}
		}
		Arrays.sort(baselineNanos); Arrays.sort(granularNanos);
		long baselineMedian = baselineNanos[samples / 2], granularMedian = granularNanos[samples / 2];
		double overhead = baselineMedian == 0L ? Double.POSITIVE_INFINITY
				: (granularMedian - baselineMedian) * 100.0 / baselineMedian;
		System.out.printf("  empty 24x16x24 gate wall-time baseline=%.3f ms granular=%.3f ms overhead=%+.1f%%%n",
				baselineMedian / 1.0e6, granularMedian / 1.0e6, overhead);
		require(Double.doubleToRawLongBits(baseline.totalVolumeExact())
				== Double.doubleToRawLongBits(granular.totalVolumeExact())
				&& Float.floatToRawIntBits(baseline.maximumParticleSpeed())
				== Float.floatToRawIntBits(granular.maximumParticleSpeed()),
				"empty granular inventory changed the baseline liquid result");
		long relativeOrTimerBound = Math.max((long) Math.ceil(baselineMedian * 1.05), baselineMedian + 500_000L);
		require(granularMedian <= relativeOrTimerBound && granularMedian <= 40_000_000L,
				"empty granular gate exceeded its 5%/0.5 ms noise envelope or the existing 40 ms pacing boundary");
	}

	private static GranularMechanics.Environment constantWater(
			double velocityX, double velocityY, double velocityZ, double[] reaction
	) {
		return constantWater(velocityX, velocityY, velocityZ, 1.0, reaction);
	}

	private static GranularMechanics.Environment constantWater(
			double velocityX, double velocityY, double velocityZ, double submergedFraction, double[] reaction
	) {
		return constantWater(velocityX, velocityY, velocityZ, submergedFraction, 1.0e30, reaction);
	}

	private static GranularMechanics.Environment constantWater(
			double velocityX, double velocityY, double velocityZ, double submergedFraction,
			double carrierMass, double[] reaction
	) {
		return new GranularMechanics.Environment() {
			@Override public GranularMechanics.FluidSample sample(float x, float y, float z) {
				return new GranularMechanics.FluidSample(true,
						velocityX + (reaction == null ? 0.0 : reaction[0] / carrierMass),
						velocityY + (reaction == null ? 0.0 : reaction[1] / carrierMass),
						velocityZ + (reaction == null ? 0.0 : reaction[2] / carrierMass),
						1_000.0, 1.002e-3, 100_000.0, submergedFraction,
						carrierMass, carrierMass, carrierMass);
			}
			@Override public GranularMechanics.FluidImpulseApplication addFluidImpulse(
					float x, float y, float z, double ix, double iy, double iz) {
				if (reaction != null) { reaction[0] += ix; reaction[1] += iy; reaction[2] += iz; }
				return new GranularMechanics.FluidImpulseApplication(ix, iy, iz, 0.0, 0.0, 0.0);
			}
		};
	}

	private static double terminalVelocity(double particleDensity, double fluidDensity, double viscosity, double diameter) {
		if (particleDensity == fluidDensity) return 0.0;
		double direction = particleDensity > fluidDensity ? -1.0 : 1.0;
		double volume = Math.PI * diameter * diameter * diameter / 6.0;
		double area = Math.PI * diameter * diameter / 4.0;
		double force = Math.abs(particleDensity - fluidDensity) * volume * 9.81;
		double lower = 0.0, upper = 20.0;
		for (int iteration = 0; iteration < 100; iteration++) {
			double middle = 0.5 * (lower + upper);
			double reynolds = fluidDensity * diameter * middle / viscosity;
			double drag = 0.5 * GranularMechanics.dragCoefficient(reynolds)
					* fluidDensity * area * middle * middle;
			if (drag < force) lower = middle; else upper = middle;
		}
		return direction * 0.5 * (lower + upper);
	}

	private static boolean rawClose(double actual, double expected) {
		return Math.abs(actual - expected) <= Math.max(1.0e-12, 8.0 * Math.ulp(expected));
	}

	private static void expectFailure(Runnable action, String message) {
		try { action.run(); } catch (IllegalArgumentException expected) { return; }
		throw new AssertionError(message);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	/** Asserts a rejection whose message names the specific reason, and that nothing else was thrown. */
	private static void expectIllegal(String messagePart, Runnable action) {
		try {
			action.run();
		} catch (IllegalArgumentException | IllegalStateException expected) {
			if (!String.valueOf(expected.getMessage()).contains(messagePart)) {
				throw new AssertionError("unexpected rejection: " + expected.getMessage(), expected);
			}
			return;
		}
		throw new AssertionError("expected a rejection containing '" + messagePart + "'");
	}
}
