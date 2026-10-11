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
		batchSettlementPreservesInventoryAndComposition();
		multiTargetSettlementIsAtomic();
		settlementDoesNotRaiseGravitationalPotential();
		bedTransfersDoNotRaiseGravitationalPotential();
		parcelDepositCyclesDoNotDrift(fast ? 1_000 : 10_000);
		mechanicsMatchesTerminalVelocity();
		twoWayDragPreservesMomentum();
		cc.ecisr.mmm.fluid.GranularCarrierValidation.validate();
		couplingOccupancyResumsExactly();
		porousBedPreservesHydrostatics();
		settlingRelaxationSleepAndWakeAreLocal();
		blockedSettlementPreservesKineticEnergy();
		settlementAndRelaxationRespectVerticalPartitions();
		bedGrowthPreservesActiveSphereGeometry();
		adjacentTargetBedClosureIsAtomic();
		bedAdmissionIndexFollowsSwapsAndWake();
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
		var initial = store.parcel(0);
		store.overwrite(0, new GranularParcelStore.Parcel(initial.x(), initial.y(), initial.z(),
				initial.velocityX(), initial.velocityY(), initial.velocityZ(), volume, mass, material, 0, 3, (byte) 3));
		require(store.parcel(0).contactAge() == 3 && store.parcel(0).flags() == 3,
				"same-volume mechanical writes must preserve newly computed contact state");
		int split = store.split(0, volume / 3L);
		require(store.totalSolidVolumeSvq() == volume, "parcel split changed SVQ");
		require(rawClose(store.totalMass(), mass), "parcel split changed mass");
		for (int index = 0; index < store.count(); index++) {
			var child = store.parcel(index);
			require(child.x() == initial.x() && child.y() == initial.y() && child.z() == initial.z(),
					"parcel split must preserve each child's position");
			require(child.contactAge() == 0 && child.flags() == 2,
					"split children must reacquire support for their smaller representative spheres");
			store.overwrite(index, new GranularParcelStore.Parcel(child.x(), child.y(), child.z(),
					child.velocityX(), child.velocityY(), child.velocityZ(), child.solidVolumeSvq(), child.mass(),
					material, 0, 3, (byte) 3));
		}
		store.merge(0, split);
		require(store.count() == 1 && store.totalSolidVolumeSvq() == volume, "parcel merge changed SVQ");
		require(rawClose(store.totalMass(), mass), "parcel merge changed mass");
		require(store.parcel(0).contactAge() == 0 && store.parcel(0).flags() == 2,
				"generic merge must reacquire support while preserving unrelated flags");
		var merged = store.parcel(0);
		store.overwrite(0, new GranularParcelStore.Parcel(merged.x(), merged.y(), merged.z(),
				merged.velocityX(), merged.velocityY(), merged.velocityZ(), volume, mass, material, 0, 3, (byte) 3));
		store.overwrite(0, new GranularParcelStore.Parcel(merged.x(), merged.y(), merged.z(),
				merged.velocityX(), merged.velocityY(), merged.velocityZ(), volume / 8, mass / 8, material, 0, 3, (byte) 3));
		require(store.parcel(0).contactAge() == 0 && store.parcel(0).flags() == 2,
				"partial removal must invalidate support at its old radius");
		store.removeSwap(0);
		for (int index = 0; index < 2; index++) store.add(new GranularParcelStore.Parcel(
				0.5F, 0.5F, 0.5F, 0, 0, 0, volume / 4, mass / 4, material, 0, 3, (byte) 3));
		GranularDepositStore deposits = new GranularDepositStore(registry, 1, 2);
		GranularInventory inventory = new GranularInventory(store, deposits);
		var compacted = inventory.compactActive(index -> 0, (left, right, replacement) -> {
			require(replacement.contactAge() == 0 && replacement.flags() == 2,
					"active compaction preflight must expose invalidated contact geometry");
			return true;
		}, 0, 4);
		require(compacted.merges() == 1 && store.parcel(0).contactAge() == 0 && store.parcel(0).flags() == 2,
				"active compaction commit must reacquire support");
		store.removeSwap(0);
		float originalRadius = GranularMechanics.representativeRadius(volume, 0.5F);
		store.add(new GranularParcelStore.Parcel(0.25F, originalRadius, 0.25F, 0, 0, 0,
				volume, mass, material, 0, 3, GranularParcelStore.FLAG_SUPPORTED));
		store.overwrite(0, new GranularParcelStore.Parcel(0.25F, originalRadius, 0.25F, 0, 0, 0,
				volume / 8, mass / 8, material, 0, 3, GranularParcelStore.FLAG_SUPPORTED));
		GranularDepositDynamics dynamics = new GranularDepositDynamics(registry, store, deposits,
				inventory, 1, 1, 1, 0.5F);
		// Radius halves while the center stays fixed: this particle is now above its former support.
		require(originalRadius > GranularMechanics.representativeRadius(volume / 8, 0.5F)
				&& dynamics.step(new TestDepositEnvironment(1, 1, 1, 0), 0.05F).settledParcels() == 0
				&& store.count() == 1,
				"radius reduction must wait for a fresh mechanical contact before settling");
		for (double[] masses : new double[][] {{2.5625, 2.5}, {2.5, 2.5625}, {2.5, 2.5}}) {
			GranularParcelStore pair = new GranularParcelStore(registry, 2);
			pair.add(new GranularParcelStore.Parcel(0.625F, 0.625F, 0.625F, 0.125F, 0.25F, -0.5F,
					volume / 2, masses[0], material, 0, 0, (byte) 0));
			pair.add(new GranularParcelStore.Parcel(0.875F, 0.875F, 0.875F, 0.125F, 0.25F, -0.5F,
					volume / 2, masses[1], material, 0, 0, (byte) 0));
			GranularDepositStore emptyBed = new GranularDepositStore(registry, 1, 2);
			GranularInventory pairInventory = new GranularInventory(pair, emptyBed);
			var left = pair.parcel(0); var right = pair.parcel(1);
			long revision = pair.mutationRevision();
			var audit = GranularAuditLedger.captureInventory(registry, pair, emptyBed, null);
			double totalMass = masses[0] + masses[1];
			SignedDyadic momentBefore = SignedDyadic.of(masses[0]).multiply(SignedDyadic.of(0.625))
					.add(SignedDyadic.of(masses[1]).multiply(SignedDyadic.of(0.875)));
			GranularParcelStore directMerge = new GranularParcelStore(registry, 2);
			directMerge.add(left); directMerge.add(right); directMerge.merge(0, 1);
			var directResult = directMerge.parcel(0);
			require(Math.abs(SignedDyadic.of(directResult.mass()).multiply(SignedDyadic.of((double) directResult.y()))
					.subtract(momentBefore).toDouble()) <= totalMass * Math.ulp(directResult.y()) * 0.5
					&& directResult.x() == directResult.y() && directResult.z() == directResult.y()
					&& audit.equals(GranularAuditLedger.captureInventory(registry, directMerge, emptyBed, null)),
					"direct parcel merge must preserve mass first moments and exact stock");
			var rejected = pairInventory.compactActive(index -> 0, (first, second, candidate) -> {
				SignedDyadic error = SignedDyadic.of(totalMass).multiply(SignedDyadic.of((double) candidate.y()))
						.subtract(momentBefore);
				require(Math.abs(error.toDouble()) <= totalMass * Math.ulp(candidate.y()) * 0.5,
						"compaction geometry must inspect the mass-weighted candidate position");
				return false;
			}, 0, 4);
			require(rejected.merges() == 0 && pair.count() == 2 && pair.mutationRevision() == revision
					&& pair.parcel(0).equals(left) && pair.parcel(1).equals(right),
					"rejected mass-weighted compaction must preserve both source parcels");
			require(pairInventory.compactActive(index -> 0, candidate -> true, 0, 4).merges() == 1,
					"compatible unequal-density parcels must compact after geometry admission");
			var result = pair.parcel(0);
			SignedDyadic error = SignedDyadic.of(result.mass()).multiply(SignedDyadic.of((double) result.y()))
					.subtract(momentBefore);
			require(Math.abs(error.toDouble()) <= totalMass * Math.ulp(result.y()) * 0.5
					&& result.x() == result.y() && result.z() == result.y(),
					"active compaction must preserve mass first moments within binary32 position rounding");
			require(result.velocityX() == left.velocityX() && result.velocityY() == left.velocityY()
					&& result.velocityZ() == left.velocityZ() && result.mass() == totalMass
					&& audit.equals(GranularAuditLedger.captureInventory(registry, pair, emptyBed, null)),
					"compaction must preserve momentum, kinetic energy and exact stock for common velocities");
			if (masses[0] == masses[1]) require(result.y() == 0.75F,
					"equal-density compaction must retain its original volume-weighted position");
		}
	}

	private static void batchSettlementPreservesInventoryAndComposition() {
		GranularRegistry registry = BuiltinGranularDefinitions.createRegistry();
		int sand = registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		int beads = registry.materialHandle(BuiltinGranularDefinitions.LIGHT_BEADS);
		GranularParcelStore budgetParcels = new GranularParcelStore(registry, 4);
		budgetParcels.add(parcel(sand, 1, 1)); budgetParcels.add(parcel(beads, 1, 2));
		GranularDepositStore limited = new GranularDepositStore(registry, 1, 1);
		GranularInventory budgetInventory = new GranularInventory(budgetParcels, limited);
		long revision = budgetParcels.mutationRevision();
		require(budgetInventory.planSettlement(new int[] {0, 1}, 0, SolidVolume.SVQ_PER_CELL) == null
				&& budgetParcels.count() == 2 && budgetParcels.mutationRevision() == revision
				&& limited.groupCount() == 0 && limited.mutationRevision() == 0,
				"combined new group budget must reject a complete cohort without partial deposit");
		GranularParcelStore parcels = new GranularParcelStore(registry, 8);
		for (double mass : new double[] {11, 0.1, 33, 0.2, 55}) parcels.add(parcel(sand, 1, mass));
		GranularDepositStore deposits = new GranularDepositStore(registry, 1, 4);
		GranularInventory inventory = new GranularInventory(parcels, deposits);
		SignedDyadic beforeMass = inventory.exactMass();
		require(inventory.planSettlement(new int[] {1, 3}, 0, SolidVolume.SVQ_PER_CELL, cost -> false) == null
				&& inventory.exactMass().equals(beforeMass) && deposits.groupCount() == 0,
				"source planning budget rejection must preserve the complete cohort");
		GranularInventory.SettlementPlan plan = inventory.planSettlement(new int[] {1, 3}, 0, SolidVolume.SVQ_PER_CELL);
		require(plan != null && Arrays.equals(plan.parcelIndices(), new int[] {3, 1})
				&& plan.parcels().getFirst().mass() == 0.2 && parcels.count() == 5 && deposits.groupCount() == 0,
				"settlement plan must preserve source state and descending snapshots");
		inventory.commitSettlement(plan);
		require(parcels.count() == 3 && parcels.parcel(0).mass() == 11 && parcels.parcel(1).mass() == 55
				&& parcels.parcel(2).mass() == 33 && deposits.dominantGroup(0).solidVolumeSvq() == 2
				&& inventory.exactMass().subtract(beforeMass).equals(plan.massStorageDelta())
				&& plan.massStorageDelta().compareTo(plan.massStorageBound()) <= 0
				&& plan.massStorageDelta().negate().compareTo(plan.massStorageBound()) <= 0,
				"batch descending swaps or quantified mass rounding changed inventory");
		GranularParcelStore packingParcels = new GranularParcelStore(registry, 4);
		for (double mass : new double[] {0.1, 0.2, 0.3}) packingParcels.add(parcel(sand, 1, mass));
		GranularDepositStore packingDeposits = new GranularDepositStore(registry, 1, 4);
		GranularInventory packingInventory = new GranularInventory(packingParcels, packingDeposits);
		var packing = packingInventory.planSettlement(new int[] {0, 1, 2}, 0, 5);
		require(packing != null && packing.bulkVolumeSvq() == 5,
				"joint packing must add exact rest bulk volumes before one ceil");
		packingInventory.commitSettlement(packing);
		require(packingDeposits.groupCount() == 1 && packingDeposits.cellBulkVolumeSvq(0) == 5,
				"joint packing commit changed its capacity candidate");
		GranularParcelStore overflowParcels = new GranularParcelStore(registry, 4);
		overflowParcels.add(parcel(sand, 1, Double.MAX_VALUE)); overflowParcels.add(parcel(sand, 1, Double.MAX_VALUE));
		GranularDepositStore overflowDeposits = new GranularDepositStore(registry, 1, 4);
		GranularInventory overflowInventory = new GranularInventory(overflowParcels, overflowDeposits);
		require(overflowInventory.planSettlement(new int[] {0, 1}, 0, SolidVolume.SVQ_PER_CELL) == null
				&& overflowParcels.count() == 2 && overflowDeposits.groupCount() == 0,
				"combined mechanical mass overflow must preserve every source");
		int reactive = registry.materialHandle(BuiltinGranularDefinitions.PRECIPITATE);
		SolidCompositionPool pool = new SolidCompositionPool(BuiltinChemicalDefinitions.REGISTRY, 4);
		SolidCompositionRecord equal = SolidCompositionRecord.fromStableAmounts(BuiltinChemicalDefinitions.REGISTRY,
				java.util.Map.of(BuiltinChemicalDefinitions.NA_PLUS, 640L, BuiltinChemicalDefinitions.CL_MINUS, 640L), 0);
		SolidCompositionRecord doubled = equal.plus(equal);
		SolidCompositionRecord different = SolidCompositionRecord.fromStableAmounts(BuiltinChemicalDefinitions.REGISTRY,
				java.util.Map.of(BuiltinChemicalDefinitions.NA_PLUS, 640L, BuiltinChemicalDefinitions.CL_MINUS, 1280L), 0);
		int retained = pool.create(equal);
		GranularParcelStore reactiveParcels = new GranularParcelStore(registry, 4);
		for (SolidCompositionRecord record : List.of(equal, doubled, different)) reactiveParcels.add(new GranularParcelStore.Parcel(
				0.25F, 0.25F, 0.25F, 0, 0, 0, record == doubled ? 20 : 10,
				record.totalMass(BuiltinChemicalDefinitions.REGISTRY), reactive, pool.create(record), 3, GranularParcelStore.FLAG_SUPPORTED));
		GranularDepositStore reactiveDeposits = new GranularDepositStore(registry, 1, 4);
		require(reactiveDeposits.tryAdd(0, new GranularDepositStore.SolidPortion(reactive, retained, 10,
				equal.totalMass(BuiltinChemicalDefinitions.REGISTRY)), SolidVolume.SVQ_PER_CELL), "reactive bed fixture failed");
		GranularInventory reactiveInventory = new GranularInventory(reactiveParcels, reactiveDeposits);
		reactiveInventory.bindSolidCompositions(pool);
		long[] speciesBefore = pool.speciesTotals();
		int[] planningCost = {0, 0};
		require(reactiveInventory.planSettlement(new int[] {0, 2, 1}, 0, SolidVolume.SVQ_PER_CELL, cost -> {
			planningCost[0]++; planningCost[1] += cost; return planningCost[0] == 1;
		}) == null && planningCost[0] == 2 && planningCost[1] == 12
				&& reactiveParcels.count() == 3 && pool.liveCount() == 4 && reactiveDeposits.groupCount() == 1
				&& Arrays.equals(speciesBefore, pool.speciesTotals()),
				"global reactive ownership scan must charge its records and defer atomically");
		var reactivePlan = reactiveInventory.planSettlement(new int[] {0, 2, 1}, 0, SolidVolume.SVQ_PER_CELL);
		require(reactivePlan != null && pool.liveCount() == 4, "full composition pool must reuse cohort handles");
		reactiveInventory.commitSettlement(reactivePlan);
		var state = GranularStateSnapshot.capture(1, 1, 1, registry, reactiveParcels, reactiveDeposits, pool);
		state.validateSolidCompositionMasses(BuiltinChemicalDefinitions.REGISTRY);
		GranularAuditLedger.captureInventory(registry, reactiveParcels, reactiveDeposits, pool)
				.validateAgainst(registry, BuiltinChemicalDefinitions.REGISTRY);
		require(reactiveParcels.count() == 0 && reactiveDeposits.groupCount() == 2 && pool.liveCount() == 2
				&& Arrays.equals(speciesBefore, pool.speciesTotals()) && pool.isLive(retained)
				&& reactiveDeposits.matchingGroup(0, reactive, group -> group.solidCompositionHandle() == retained).solidVolumeSvq() == 40,
				"proportional reactive cohort must retain existing ownership and keep distinct composition groups");
		SolidCompositionPool cancellationPool = new SolidCompositionPool(BuiltinChemicalDefinitions.REGISTRY, 3);
		SolidCompositionRecord largeSpecies = SolidCompositionRecord.fromStableAmounts(BuiltinChemicalDefinitions.REGISTRY,
				java.util.Map.of(BuiltinChemicalDefinitions.MERCURY_CARRIER, 500_000_000_000_000L), 0);
		double base = largeSpecies.totalMass(BuiltinChemicalDefinitions.REGISTRY);
		SolidCompositionRecord cancellation = SolidCompositionRecord.fromStableAmounts(BuiltinChemicalDefinitions.REGISTRY,
				largeSpecies.stableAmounts(BuiltinChemicalDefinitions.REGISTRY), -base + Math.ulp(base));
		GranularParcelStore cancellationParcels = new GranularParcelStore(registry, 3);
		for (int index = 0; index < 3; index++) cancellationParcels.add(new GranularParcelStore.Parcel(
				0.25F, 0.25F, 0.25F, 0, 0, 0, 10, cancellation.totalMass(BuiltinChemicalDefinitions.REGISTRY),
				reactive, cancellationPool.create(cancellation), 3, GranularParcelStore.FLAG_SUPPORTED));
		GranularDepositStore cancellationDeposits = new GranularDepositStore(registry, 1, 4);
		GranularInventory cancellationInventory = new GranularInventory(cancellationParcels, cancellationDeposits);
		cancellationInventory.bindSolidCompositions(cancellationPool);
		GranularStateSnapshot.capture(1, 1, 1, registry, cancellationParcels, cancellationDeposits, cancellationPool)
				.validateSolidCompositionMasses(BuiltinChemicalDefinitions.REGISTRY);
		require(cancellationInventory.planSettlement(new int[] {0, 1, 2}, 0, SolidVolume.SVQ_PER_CELL) == null
				&& cancellationParcels.count() == 3 && cancellationPool.liveCount() == 3 && cancellationDeposits.groupCount() == 0,
				"joint residual rounding must defer a final composition/mass mismatch");
		GranularParcelStore staleParcels = new GranularParcelStore(registry, 2); staleParcels.add(parcel(sand, 1, 1));
		GranularDepositStore staleDeposits = new GranularDepositStore(registry, 1, 2);
		GranularInventory staleInventory = new GranularInventory(staleParcels, staleDeposits);
		var stalePlan = staleInventory.planSettlement(new int[] {0}, 0, SolidVolume.SVQ_PER_CELL);
		staleParcels.overwrite(0, parcel(sand, 1, 2));
		boolean rejected = false; try { staleInventory.commitSettlement(stalePlan); } catch (IllegalStateException expected) { rejected = true; }
		require(rejected && staleParcels.count() == 1 && staleParcels.parcel(0).mass() == 2 && staleDeposits.groupCount() == 0,
				"stale inventory plan must reject before any bed or composition changes");
	}

	private static void multiTargetSettlementIsAtomic() {
		var registry = BuiltinGranularDefinitions.createRegistry();
		int sand = registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		long open = SolidVolume.SVQ_PER_CELL;
		var limitedParcels = new GranularParcelStore(registry, 4);
		limitedParcels.add(parcel(sand, 1, 1)); limitedParcels.add(parcel(sand, 1, 2));
		var limitedDeposits = new GranularDepositStore(registry, 2, 1);
		var limitedInventory = new GranularInventory(limitedParcels, limitedDeposits);
		require(limitedInventory.planSettlement(new int[] {0, 1}, new int[] {0, 1}, new long[] {open, open}, ignored -> true) == null
				&& limitedParcels.count() == 2 && limitedDeposits.groupCount() == 0 && limitedDeposits.mutationRevision() == 0,
				"multi-cell new groups must share one global spare preflight");
		var parcels = new GranularParcelStore(registry, 8);
		for (double mass : new double[] {11, 22, 33, 44, 55, 66}) parcels.add(parcel(sand, 1, mass));
		var deposits = new GranularDepositStore(registry, 2, 4);
		var inventory = new GranularInventory(parcels, deposits);
		var before = inventory.exactMass();
		var plan = inventory.planSettlement(new int[] {3, 1, 4}, new int[] {1, 0, 0}, new long[] {open, open, open}, ignored -> true);
		require(plan != null && Arrays.equals(plan.parcelIndices(), new int[] {4, 3, 1})
				&& Arrays.equals(plan.targetCells(), new int[] {0, 1, 0}) && plan.targets().size() == 2
				&& plan.targets().get(0).cell() == 0 && plan.targets().get(0).addedSolidVolumeSvq() == 2
				&& plan.targets().get(0).addedMass().equals(SignedDyadic.of(77.0))
				&& plan.targets().get(1).cell() == 1 && plan.targets().get(1).addedMass().equals(SignedDyadic.of(44.0))
				&& GranularBedPotential.allowsSettlement(plan, .5F, 2, 1, 9.81),
				"source sorting must preserve target pairs and canonical complete receiving snapshots");
		inventory.commitSettlement(plan);
		require(parcels.count() == 3 && parcels.parcel(0).mass() == 11 && parcels.parcel(1).mass() == 66
				&& parcels.parcel(2).mass() == 33 && deposits.groupCount() == 2 && deposits.mutationRevision() == 1
				&& deposits.dominantGroup(0).mass() == 77 && deposits.dominantGroup(1).mass() == 44
				&& inventory.exactMass().subtract(before).equals(plan.massStorageDelta()),
				"multi-cell commit must apply checked groups at one revision and keep descending swaps");
		var stale = inventory.planSettlement(new int[] {0, 2}, new int[] {0, 1}, new long[] {open, open}, ignored -> true);
		require(deposits.tryAdd(1, new GranularDepositStore.SolidPortion(sand, 0, 1, 1), open), "stale target fixture failed");
		long revision = deposits.mutationRevision();
		boolean rejected = false; try { inventory.commitSettlement(stale); } catch (IllegalStateException expected) { rejected = true; }
		require(rejected && parcels.count() == 3 && deposits.dominantGroup(0).mass() == 77
				&& deposits.dominantGroup(1).mass() == 45 && deposits.mutationRevision() == revision,
				"any stale target must reject before changing another receiving cell");
		int reactive = registry.materialHandle(BuiltinGranularDefinitions.PRECIPITATE);
		var composition = SolidCompositionRecord.fromStableAmounts(BuiltinChemicalDefinitions.REGISTRY,
				java.util.Map.of(BuiltinChemicalDefinitions.NA_PLUS, 640L, BuiltinChemicalDefinitions.CL_MINUS, 640L), 0);
		var pool = new SolidCompositionPool(BuiltinChemicalDefinitions.REGISTRY, 5);
		var reactiveParcels = new GranularParcelStore(registry, 4);
		for (int index = 0; index < 4; index++) reactiveParcels.add(new GranularParcelStore.Parcel(.25F, .25F, .25F,
				0, 0, 0, 10, composition.totalMass(BuiltinChemicalDefinitions.REGISTRY), reactive, pool.create(composition), 3, GranularParcelStore.FLAG_SUPPORTED));
		var reactiveDeposits = new GranularDepositStore(registry, 2, 4);
		var reactiveInventory = new GranularInventory(reactiveParcels, reactiveDeposits); reactiveInventory.bindSolidCompositions(pool);
		var reactivePlan = reactiveInventory.planSettlement(new int[] {0, 1, 2, 3}, new int[] {1, 0, 1, 0},
				new long[] {open, open, open, open}, ignored -> true);
		require(reactivePlan != null, "multi-cell reactive fixture failed");
		reactiveInventory.commitSettlement(reactivePlan);
		require(reactiveParcels.count() == 0 && reactiveDeposits.groupCount() == 2 && pool.liveCount() == 2
				&& reactiveDeposits.dominantGroup(0).solidCompositionHandle() == 2
				&& reactiveDeposits.dominantGroup(1).solidCompositionHandle() == 1,
				"equal reactive compositions must merge only within their paired receiving cell");
		GranularAuditLedger.captureInventory(registry, reactiveParcels, reactiveDeposits, pool)
				.validateAgainst(registry, BuiltinChemicalDefinitions.REGISTRY);
		long grainSvq = GranularLimits.MAX_PARCEL_VOLUME_SVQ;
		double grainMass = grainSvq / (double) SolidVolume.SVQ_PER_BV * registry.material(sand).referenceDensity();
		float r = GranularMechanics.representativeRadius(grainSvq, .5F);
		var crossParcels = new GranularParcelStore(registry, 16);
		for (int index = 0; index < 14; index++) crossParcels.add(new GranularParcelStore.Parcel(index == 13 ? .75F : .25F,
				index == 13 ? .5F + r : r, .25F, 0, 0, 0, grainSvq, grainMass, sand, 0, 3, GranularParcelStore.FLAG_SUPPORTED));
		var crossInventory = new GranularInventory(crossParcels, new GranularDepositStore(registry, 4, 4));
		int[] crossTargets = new int[14]; crossTargets[13] = 3; long[] crossOpen = new long[14]; Arrays.fill(crossOpen, open);
		var crossPlan = crossInventory.planSettlement(java.util.stream.IntStream.range(0, 14).toArray(), crossTargets, crossOpen, ignored -> true);
		require(crossPlan != null && GranularBedPotential.allowsSettlement(crossPlan, .5F, 2, 2, 9.81),
				"cross-cell energy admission must sum actual target heights and allow a net potential decrease");
	}

	private static void settlementDoesNotRaiseGravitationalPotential() {
		GranularRegistry registry = BuiltinGranularDefinitions.createRegistry();
		int sand = registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		long svq = GranularLimits.MAX_PARCEL_VOLUME_SVQ;
		double mass = svq / (double) SolidVolume.SVQ_PER_BV * registry.material(sand).referenceDensity();
		float radius = GranularMechanics.representativeRadius(svq, .5F);
		for (int count : new int[] {12, 13, 16}) {
			GranularParcelStore parcels = new GranularParcelStore(registry, 32);
			GranularDepositStore deposits = new GranularDepositStore(registry, 1, 8);
			for (int index = 0; index < count; index++) parcels.add(new GranularParcelStore.Parcel(
					.25F, radius, .25F, 0, 0, 0, svq, mass, sand, 0, 3, GranularParcelStore.FLAG_SUPPORTED));
			GranularInventory inventory = new GranularInventory(parcels, deposits);
			int[] indices = java.util.stream.IntStream.range(0, count).toArray();
			var plan = inventory.planSettlement(indices, 0, SolidVolume.SVQ_PER_CELL);
			require(plan != null && plan.oldBedMass().equals(SignedDyadic.ZERO) && plan.oldBedBulkVolumeSvq() == 0
					&& GranularBedPotential.allowsSettlement(plan, .5F, 1, 1, 9.81) == (count == 12)
					&& GranularBedPotential.allowsSettlement(plan, .5F, 1, 1, 0),
					"uniform bed admission must compare exact mass first moments and skip zero gravity");
			var dynamics = new GranularDepositDynamics(registry, parcels, deposits, inventory, 1, 1, 1, .5F);
			var environment = new TestDepositEnvironment(1, 1, 1, 0) {
				@Override public double gravity() { return 9.81; }
			};
			long parcelRevision = parcels.mutationRevision(), depositRevision = deposits.mutationRevision();
			var before = inventory.exactMass();
			var first = dynamics.step(environment, .05F);
			if (count == 12) {
				require(first.settledParcels() == 12 && parcels.count() == 0 && inventory.exactMass().equals(before),
						"lower-potential cohort must still settle completely");
			} else {
				var second = dynamics.step(environment, .05F);
				require(first.settledParcels() == 0 && second.settledParcels() == 0 && dynamics.dirtyColumnCount() > 0
						&& parcels.count() == count && deposits.groupCount() == 0
						&& parcels.mutationRevision() == parcelRevision && deposits.mutationRevision() == depositRevision
						&& inventory.exactMass().equals(before) && parcels.parcel(0).y() == radius,
						"positive-potential closure must defer unchanged and retain its scheduled column");
			}
		}
		GranularParcelStore parcels = new GranularParcelStore(registry, 4);
		GranularDepositStore deposits = new GranularDepositStore(registry, 1, 8);
		require(deposits.tryAdd(0, new GranularDepositStore.SolidPortion(sand, 0, svq * 4, mass * 4), SolidVolume.SVQ_PER_CELL),
				"old-bed potential fixture failed");
		long oldBulk = deposits.cellBulkVolumeSvq(0);
		float y = Math.nextUp((float) (oldBulk * Math.scalb(.5, -24) + radius));
		parcels.add(new GranularParcelStore.Parcel(.25F, y, .25F, 0, 0, 0, svq, mass, sand, 0, 3, GranularParcelStore.FLAG_SUPPORTED));
		GranularInventory inventory = new GranularInventory(parcels, deposits);
		var plan = inventory.planSettlement(new int[] {0}, 0, SolidVolume.SVQ_PER_CELL);
		require(plan != null && plan.oldBedMass().equals(SignedDyadic.of(mass * 4)) && plan.oldBedBulkVolumeSvq() == oldBulk
				&& GranularBedPotential.allowsSettlement(plan, .5F, 1, 1, 9.81), "ordinary existing-bed settlement must reduce potential");
		var dynamics = new GranularDepositDynamics(registry, parcels, deposits, inventory, 1, 1, 1, .5F);
		require(dynamics.step(new TestDepositEnvironment(1, 1, 1, 0) {
			@Override public double gravity() { return 9.81; }
		}, .05F).settledParcels() == 1 && parcels.count() == 0,
				"potential guard must preserve supported single-grain bed conversion");
	}

	private static void bedTransfersDoNotRaiseGravitationalPotential() {
		var registry = BuiltinGranularDefinitions.createRegistry();
		int light = registry.materialHandle(BuiltinGranularDefinitions.LIGHT_BEADS), sand = registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		long open = SolidVolume.SVQ_PER_CELL;
		var preview = new GranularDepositStore(registry, 1, 4);
		require(preview.tryAdd(0, new GranularDepositStore.SolidPortion(light, 0, 100, 1), open)
				&& preview.tryAdd(0, new GranularDepositStore.SolidPortion(sand, 0, 71, 2), open), "mixed take preview fixture failed");
		long revision = preview.mutationRevision(), partial = preview.previewCellBulkVolumeAfterTake(0, light, 0, 37);
		require(preview.mutationRevision() == revision && preview.dominantGroup(0).solidVolumeSvq() == 100,
				"mixed take preview must preserve sparse inventory");
		preview.take(0, light, 0, 37);
		require(preview.cellBulkVolumeSvq(0) == partial, "partial take must use the same exact mixed packing as its preview");
		long full = preview.previewCellBulkVolumeAfterTake(0, light, 0, 63); preview.take(0, light, 0, 63);
		require(preview.groupCount() == 1 && preview.cellBulkVolumeSvq(0) == full,
				"whole group deletion must use the same remaining mixed packing as its preview");
		for (double gravity : new double[] {9.81, 0}) {
			var beds = new GranularDepositStore(registry, 2, 8); var parcels = new GranularParcelStore(registry, 4);
			for (int cell = 0; cell < 2; cell++) {
				int material = cell == 0 ? light : sand; long volume = (long) Math.floor((cell == 0 ? .6 : .372) * open);
				double mass = volume / (double) SolidVolume.SVQ_PER_BV * registry.material(material).referenceDensity();
				require(beds.tryAdd(cell, new GranularDepositStore.SolidPortion(material, 0, volume, mass), open), "mixed gravity fixture failed");
			}
			var moved = beds.peekTake(0, light, 0, 1_000);
			require(GranularBedPotential.allowsRelaxation(beds, 0, 1, moved, .5F, 2, 1, gravity) == (gravity == 0),
					"lighter material addition must not raise the dense receiver's first moment without work");
			var inventory = new GranularInventory(parcels, beds); var before = inventory.exactMass(); revision = beds.mutationRevision();
			var dynamics = new GranularDepositDynamics(registry, parcels, beds, inventory, 2, 1, 1, .5F); dynamics.disturbCell(0);
			var result = dynamics.step(new TestDepositEnvironment(2, 1, 1, 0) {
				@Override public double gravity() { return gravity; }
			}, .05F);
			require(inventory.exactMass().equals(before) && (gravity == 0 ? result.relaxedMoves() == 1
					: result.relaxedMoves() == 0 && beds.mutationRevision() == revision && dynamics.dirtyColumnCount() > 0),
					"mixed relaxation must defer unchanged in gravity and preserve the zero-gravity transfer");
		}
		var pure = new GranularDepositStore(registry, 2, 4);
		long volume = (long) Math.floor(.62 * open); double mass = volume / (double) SolidVolume.SVQ_PER_BV * registry.material(sand).referenceDensity();
		require(pure.tryAdd(0, new GranularDepositStore.SolidPortion(sand, 0, volume, mass), open), "pure sand fixture failed");
		require(GranularBedPotential.allowsRelaxation(pure, 0, 1, pure.peekTake(0, sand, 0, 1_000), .5F, 2, 1, 9.81),
				"same-material downhill relaxation must remain admissible");
		for (double gravity : new double[] {9.81, 0}) {
			var beds = new GranularDepositStore(registry, 1, 4); var parcels = new GranularParcelStore(registry, 4);
			long wakeVolume = open / 4; double wakeMass = wakeVolume / (double) SolidVolume.SVQ_PER_BV * registry.material(sand).referenceDensity();
			require(beds.tryAdd(0, new GranularDepositStore.SolidPortion(sand, 0, wakeVolume, wakeMass), open), "wake gravity fixture failed");
			long erosion = GranularLimits.MAX_PARCEL_VOLUME_SVQ; var moved = beds.peekTake(0, sand, 0, erosion);
			float targetY = (float) (beds.cellBulkVolumeSvq(0) * Math.scalb(.5, -24))
					+ GranularMechanics.representativeRadius(erosion, .5F) + .5F * 1e-5F;
			require(GranularBedPotential.allowsWake(beds, 0, moved, targetY, .5F, 1, 1, gravity) == (gravity == 0),
					"raising a resting bed portion to an active sphere requires modeled gravity work");
			var inventory = new GranularInventory(parcels, beds); var before = inventory.exactMass(); revision = beds.mutationRevision();
			var dynamics = new GranularDepositDynamics(registry, parcels, beds, inventory, 1, 1, 1, .5F); dynamics.disturbCell(0);
			var result = dynamics.step(new TestDepositEnvironment(1, 1, 1, 1) {
				@Override public double gravity() { return gravity; }
			}, .05F);
			require(inventory.exactMass().equals(before) && (gravity == 0 ? result.wokenParcels() == 1 && parcels.count() == 1
					: result.wokenParcels() == 0 && parcels.count() == 0 && beds.mutationRevision() == revision && dynamics.dirtyColumnCount() > 0),
					"positive-gravity wake must retain the bed while zero gravity preserves release");
		}
	}

	private static void depositCapacityAndGroupBudgetAreAtomic() {
		mixedDepositPackingIsAdditive();
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
		long beforeBulk = deposits.cellBulkVolumeSvq(0), beforeRevision = deposits.mutationRevision();
		int beforePorosity = deposits.porosityQ(0);
		require(!deposits.tryAdd(0, new GranularDepositStore.SolidPortion(reactive, 9, portion, 1.0),
				SolidVolume.SVQ_PER_CELL), "ninth deposit group accepted");
		require(deposits.totalSolidVolumeSvq() == beforeVolume
				&& Double.doubleToRawLongBits(deposits.totalMass()) == Double.doubleToRawLongBits(beforeMass)
				&& deposits.cellBulkVolumeSvq(0) == beforeBulk && deposits.porosityQ(0) == beforePorosity
				&& deposits.mutationRevision() == beforeRevision,
				"deposit group rejection left residue");

		int sand = registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		GranularDepositStore tiny = new GranularDepositStore(registry, 2, 2);
		require(tiny.tryAdd(0, new GranularDepositStore.SolidPortion(sand, 0, 3, Double.MIN_VALUE),
				SolidVolume.SVQ_PER_CELL), "subnormal deposit fixture was rejected");
		long tinyRevision = tiny.mutationRevision();
		boolean partialRejected = false;
		try { tiny.take(0, sand, 0, 2); }
		catch (IllegalArgumentException unrepresentable) { partialRejected = true; }
		require(partialRejected && !tiny.tryMove(0, 1, sand, 0, 2, SolidVolume.SVQ_PER_CELL)
				&& tiny.mutationRevision() == tinyRevision && tiny.cellSolidVolumeSvq(0) == 3
				&& tiny.cellSolidVolumeSvq(1) == 0 && tiny.totalMass() == Double.MIN_VALUE,
				"unrepresentable partial deposit must retain both inventories and revision");
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

	private static void mixedDepositPackingIsAdditive() {
		GranularRegistry registry = BuiltinGranularDefinitions.createRegistry();
		int sand = registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		int beads = registry.materialHandle(BuiltinGranularDefinitions.LIGHT_BEADS);
		long sandVolume = 1_999_913, beadsVolume = 2_000_113;
		var sandPortion = new GranularDepositStore.SolidPortion(sand, 0, sandVolume, 2.0);
		var beadsPortion = new GranularDepositStore.SolidPortion(beads, 0, beadsVolume, 1.0);
		GranularDepositStore deposits = new GranularDepositStore(registry, 4, 8);
		require(deposits.tryAdd(0, sandPortion, SolidVolume.SVQ_PER_CELL), "mixed sand rejected");
		long numerator = sandVolume * 150 + beadsVolume * 155; // 50/31 sand + 5/3 beads.
		long expectedBulk = (numerator + 92) / 93;
		int expectedPorosity = (int) ((1_000_000L * (numerator - (sandVolume + beadsVolume) * 93)
				+ numerator / 2) / numerator);
		long revision = deposits.mutationRevision(), originalBulk = deposits.cellBulkVolumeSvq(0);
		require(deposits.previewCellBulkVolumeAfterAdd(0, beadsPortion) == expectedBulk
				&& !deposits.tryAdd(0, beadsPortion, expectedBulk - 1)
				&& deposits.mutationRevision() == revision && deposits.cellBulkVolumeSvq(0) == originalBulk
				&& deposits.groupCount() == 1 && deposits.cellSolidVolumeSvq(0) == sandVolume
				&& deposits.totalMass() == 2.0, "mixed bulk preflight changed rejected inventory or cache");
		require(deposits.tryAdd(0, beadsPortion, expectedBulk)
				&& deposits.cellBulkVolumeSvq(0) == expectedBulk && deposits.porosityQ(0) == expectedPorosity,
				"mixed bulk preview, independent fraction oracle and commit disagree");
		require(deposits.tryAdd(1, beadsPortion, SolidVolume.SVQ_PER_CELL)
				&& deposits.tryAdd(1, sandPortion, SolidVolume.SVQ_PER_CELL)
				&& deposits.cellBulkVolumeSvq(1) == expectedBulk && deposits.porosityQ(1) == expectedPorosity,
				"mixed packing depends on insertion order");
		for (long piece : new long[] {1, sandVolume - 1}) require(deposits.tryAdd(2,
				new GranularDepositStore.SolidPortion(sand, 0, piece, 1.0),
				SolidVolume.SVQ_PER_CELL), "partitioned sand rejected");
		require(deposits.tryAdd(2, beadsPortion, SolidVolume.SVQ_PER_CELL)
				&& deposits.cellBulkVolumeSvq(2) == expectedBulk && deposits.porosityQ(2) == expectedPorosity,
				"same-material partition changed mixed packing");
		for (int material : new int[] {sand, beads}) {
			long before = deposits.cellBulkVolumeSvq(0);
			var removed = deposits.take(0, material, 0, 1);
			require(deposits.cellBulkVolumeSvq(0) < before, "removing skeleton did not reduce mixed bed volume");
			require(deposits.previewCellBulkVolumeAfterAdd(0, removed) == before
					&& deposits.tryAdd(0, removed, SolidVolume.SVQ_PER_CELL)
					&& deposits.cellBulkVolumeSvq(0) == before, "readding skeleton changed mixed bed volume");
		}
		require(deposits.tryAdd(3, new GranularDepositStore.SolidPortion(sand, 0, 1, 1.0), SolidVolume.SVQ_PER_CELL)
				&& deposits.tryAdd(3, new GranularDepositStore.SolidPortion(beads, 0, 2, 1.0), SolidVolume.SVQ_PER_CELL)
				&& deposits.cellBulkVolumeSvq(3) == 5, "bulk rounded each component before summation");
		var snapshot = GranularStateSnapshot.capture(2, 2, 1, registry, new GranularParcelStore(registry, 1), deposits);
		var restored = new GranularDepositStore(registry, 4, 8);
		snapshot.restoreInto(registry, new GranularParcelStore(registry, 1), restored, ignored -> SolidVolume.SVQ_PER_CELL);
		for (int cell = 0; cell < 4; cell++) require(restored.cellBulkVolumeSvq(cell) == deposits.cellBulkVolumeSvq(cell)
				&& restored.porosityQ(cell) == deposits.porosityQ(cell), "snapshot changed derived mixed packing");
		var extreme = new GranularRegistry(List.of(packingMaterial("mmm:low_porosity", 1),
				packingMaterial("mmm:high_porosity", 999_999)), registry.carriers());
		var limits = new GranularDepositStore(extreme, 1, 2);
		int low = extreme.materialHandle("mmm:low_porosity"), high = extreme.materialHandle("mmm:high_porosity");
		require(limits.tryAdd(0, new GranularDepositStore.SolidPortion(high, 0, 1, 1.0), SolidVolume.SVQ_PER_CELL)
				&& limits.cellBulkVolumeSvq(0) == 1_000_000 && limits.porosityQ(0) == 999_999,
				"high-porosity exact packing failed");
		var overflow = new GranularDepositStore.SolidPortion(high, 0, 16, 1.0);
		revision = limits.mutationRevision();
		require(limits.previewCellBulkVolumeAfterAdd(0, overflow) == 17_000_000
				&& !limits.tryAdd(0, overflow, SolidVolume.SVQ_PER_CELL)
				&& limits.mutationRevision() == revision && limits.cellBulkVolumeSvq(0) == 1_000_000,
				"extreme porosity capacity rejection changed cached packing");
		require(limits.tryAdd(0, new GranularDepositStore.SolidPortion(low, 0, 1, 1.0), SolidVolume.SVQ_PER_CELL)
				&& limits.cellBulkVolumeSvq(0) == 1_000_002 && limits.porosityQ(0) == 999_998,
				"extreme mixed porosity failed");
		limits.take(0, high, 0, 1);
		require(limits.cellBulkVolumeSvq(0) == 2 && limits.porosityQ(0) == 1, "take retained stale packing");
		limits.take(0, low, 0, 1);
		require(limits.cellBulkVolumeSvq(0) == 0 && limits.porosityQ(0) == 0, "last take retained cached packing");
		require(limits.tryAdd(0, new GranularDepositStore.SolidPortion(low, 0, 1, 1.0), SolidVolume.SVQ_PER_CELL)
				&& limits.cellBulkVolumeSvq(0) == 2 && limits.porosityQ(0) == 1, "reused entry retained stale packing");
		limits.clear((cell, phi, material, composition, volume, mass) -> { });
		require(limits.cellBulkVolumeSvq(0) == 0 && limits.porosityQ(0) == 0, "clear retained cached packing");
	}

	private static ParticulateMaterial packingMaterial(String id, int porosity) {
		return new ParticulateMaterial(id, 1_000, 0.0005, porosity, Math.toRadians(30),
				0.6, 0.45, 0.1, 0.05, 1, 1, 0, id, false);
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
		parcels.add(new GranularParcelStore.Parcel(0.75F, 0.05F, 0.75F, 0.01F, 0.0F, 0.0F,
				parcelSvq, parcelMass, sand, 0, GranularDepositDynamics.SETTLE_CONTACT_STEPS,
				GranularParcelStore.FLAG_SUPPORTED));
		TestDepositEnvironment calm = new TestDepositEnvironment(sizeX, sizeY, sizeZ, 0.0);
		GranularDepositDynamics.Stats settled = dynamics.step(calm, 0.05F);
		require(settled.settledParcels() == 1 && parcels.count() == 0, "supported granular parcel did not settle");
		require(calm.impulseX == parcelMass * (double) 0.01F && calm.impulseY == 0 && calm.impulseZ == 0,
				"settling must transfer the removed parcel momentum to its fixed bed");
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
			TestDepositEnvironment environment = new TestDepositEnvironment(3, 1, 1, 0) {
				@Override public boolean planSettlement(GranularInventory.SettlementPlan plan) { return false; }
			};
			dynamics.step(environment, 0.05F);
			var after = parcels.parcel(0);
			require(Math.abs(mass * after.velocityX() + environment.impulseX - mass * original.velocityX())
					<= 4 * Math.ulp(mass * (double) original.velocityX())
					&& Math.abs(mass * after.velocityY() + environment.impulseY - mass * original.velocityY())
					<= 4 * Math.ulp(mass * (double) original.velocityY())
					&& Math.abs(mass * after.velocityZ() + environment.impulseZ - mass * original.velocityZ())
					<= 4 * Math.ulp(mass * (double) original.velocityZ()),
					"blocked settlement steering must close momentum with the fixed bed impulse");
			if (slope <= 0 || !moving) require(after.equals(original),
					"blocked settlement cannot accelerate a resting parcel or steer it across a flat bed");
			else require(after.velocityZ() == 0 && Math.abs(Math.abs(after.velocityX()) - 0.13F) <= Math.ulp(0.13F)
						&& after.velocityY() == 0 && after.flags() == 0,
					"rolling reorients existing kinetic energy toward a lower bed surface");
		}
	}

	private static void settlementAndRelaxationRespectVerticalPartitions() {
		GranularRegistry registry = BuiltinGranularDefinitions.createRegistry();
		int sand = registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		for (boolean partition : new boolean[]{false, true}) {
			GranularParcelStore parcels = new GranularParcelStore(registry, 8);
			GranularDepositStore deposits = new GranularDepositStore(registry, 8, 16);
			GranularInventory inventory = new GranularInventory(parcels, deposits);
			GranularDepositDynamics dynamics = new GranularDepositDynamics(registry, parcels, deposits, inventory, 2, 4, 1, 0.5F);
			TestDepositEnvironment environment = new TestDepositEnvironment(2, 4, 1, 0) {
				@Override public long geometricOpenSvq(int cell) {
					return cell / 2 % 4 == 1 ? SolidVolume.SVQ_PER_CELL / 2 : SolidVolume.SVQ_PER_CELL;
				}
				@Override public boolean verticalPathOpen(float x, float fromY, float z, float toY, long svq) {
					float radius = GranularMechanics.representativeRadius(svq, 0.5F);
					return !partition || Math.min(fromY, toY) - radius >= 1.0F
							|| Math.max(fromY, toY) + radius <= 0.75F;
				}
			};
			long svq = SolidVolume.SVQ_PER_CELL * 3 / 5;
			double mass = svq / (double) SolidVolume.SVQ_PER_BV * registry.material(sand).referenceDensity();
			require(deposits.tryAdd(cellIndex(0, 2, 0, 2, 4),
					new GranularDepositStore.SolidPortion(sand, 0, svq, mass), SolidVolume.SVQ_PER_CELL),
					"vertical relaxation fixture must fit its source cell");
			dynamics.disturbCell(cellIndex(0, 2, 0, 2, 4));
			var stats = dynamics.step(environment, 0.05F);
			require(partition ? deposits.cellSolidVolumeSvq(1) == 0 : stats.relaxedMoves() > 0,
					"repose relaxation must retain an intervening slab, while an open column can receive material");
			require(inventory.totalSolidVolumeSvq() == svq && rawClose(inventory.totalMass(), mass),
					"vertical relaxation path check changed inventory");
		}
		GranularParcelStore parcels = new GranularParcelStore(registry, 8);
		GranularDepositStore deposits = new GranularDepositStore(registry, 4, 8);
		GranularInventory inventory = new GranularInventory(parcels, deposits);
		GranularDepositDynamics dynamics = new GranularDepositDynamics(registry, parcels, deposits, inventory, 1, 4, 1, 0.5F);
		long svq = GranularLimits.MAX_PARCEL_VOLUME_SVQ;
		double mass = svq / (double) SolidVolume.SVQ_PER_BV * registry.material(sand).referenceDensity();
		float y = Math.nextUp(1 + GranularMechanics.representativeRadius(svq, 0.5F));
		parcels.add(new GranularParcelStore.Parcel(0.25F, y, 0.25F, 0, 0, 0, svq, mass, sand, 0, 3,
				GranularParcelStore.FLAG_SUPPORTED));
		var stats = dynamics.step(new TestDepositEnvironment(1, 4, 1, 0) {
			@Override public long geometricOpenSvq(int cell) {
				return cell == 1 ? SolidVolume.SVQ_PER_CELL / 2 : SolidVolume.SVQ_PER_CELL;
			}
			@Override public boolean verticalPathOpen(float x, float fromY, float z, float toY, long volume) {
				return Math.min(fromY, toY) - GranularMechanics.representativeRadius(volume, 0.5F) >= 1;
			}
		}, 0.05F);
		require(stats.settledParcels() == 1 && deposits.cellSolidVolumeSvq(2) == svq
				&& deposits.cellSolidVolumeSvq(0) == 0 && deposits.cellSolidVolumeSvq(1) == 0,
				"supported parcel must settle in the reachable layer above a slab");
		require(inventory.totalSolidVolumeSvq() == svq && rawClose(inventory.totalMass(), mass),
				"partitioned settlement changed inventory");
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

	private static void bedGrowthPreservesActiveSphereGeometry() {
		var registry = BuiltinGranularDefinitions.createRegistry();
		int sand = registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		long svq = GranularLimits.MAX_PARCEL_VOLUME_SVQ;
		double mass = 2_600.0 * svq / SolidVolume.SVQ_PER_BV;
		var parcels = new GranularParcelStore(registry, 4);
		var deposits = new GranularDepositStore(registry, 1, 8);
		var inventory = new GranularInventory(parcels, deposits);
		long skeleton = (long) (SolidVolume.SVQ_PER_CELL * .62 * .6);
		require(deposits.tryAdd(0, new GranularDepositStore.SolidPortion(sand, 0, skeleton,
				2_600.0 * skeleton / SolidVolume.SVQ_PER_BV), SolidVolume.SVQ_PER_CELL), "bed admission fixture");
		long beforeBulk = deposits.cellBulkVolumeSvq(0);
		float y = Math.nextUp((float) (beforeBulk * .5 / SolidVolume.SVQ_PER_CELL
				+ GranularMechanics.representativeRadius(svq, .5F)));
		parcels.add(new GranularParcelStore.Parcel(.25F, y, .25F, .003F, .004F, .012F,
				svq, mass, sand, 0, 3, GranularParcelStore.FLAG_SUPPORTED));
		parcels.add(new GranularParcelStore.Parcel(.25F, y, .25F, 0, 0, 0,
				svq - 1, 2_600.0 * (svq - 1) / SolidVolume.SVQ_PER_BV, sand, 0, 0, (byte) 0));
		var dynamics = new GranularDepositDynamics(registry, parcels, deposits, inventory, 1, 1, 1, .5F);
		var calm = new TestDepositEnvironment(1, 1, 1, 0);
		long total = inventory.totalSolidVolumeSvq(); double totalMass = inventory.totalMass();
		require(dynamics.step(calm, .05F).settledParcels() == 0 && parcels.count() == 2
				&& deposits.cellBulkVolumeSvq(0) == beforeBulk, "settlement must not swallow a remaining active sphere");
		var second = parcels.parcel(1);
		parcels.overwrite(1, new GranularParcelStore.Parcel(second.x(), second.y(), second.z(), 0, 0, 0,
				second.solidVolumeSvq(), second.mass(), sand, 0, 3, GranularParcelStore.FLAG_SUPPORTED));
		require(dynamics.step(calm, .05F).settledParcels() == 2 && parcels.count() == 0,
				"overlapping eligible representatives must commit one geometrically closed bed conversion");
		require(inventory.totalSolidVolumeSvq() == total && rawClose(inventory.totalMass(), totalMass)
				&& calm.impulseX == mass * (double) .003F && calm.impulseY == mass * (double) .004F
				&& calm.impulseZ == mass * (double) .012F,
				"joint bed admission must preserve inventory and transfer actual source momentum to the fixed bed");

		parcels = new GranularParcelStore(registry, 4); deposits = new GranularDepositStore(registry, 2, 8);
		inventory = new GranularInventory(parcels, deposits);
		long fullBed = (long) (SolidVolume.SVQ_PER_CELL * .62 * .97);
		require(deposits.tryAdd(0, new GranularDepositStore.SolidPortion(sand, 0, fullBed,
				2_600.0 * fullBed / SolidVolume.SVQ_PER_BV), SolidVolume.SVQ_PER_CELL), "joint capacity fixture");
		y = Math.nextUp((float) (deposits.cellBulkVolumeSvq(0) * .5 / SolidVolume.SVQ_PER_CELL
				+ GranularMechanics.representativeRadius(svq, .5F)));
		for (int i = 0; i < 2; i++) parcels.add(new GranularParcelStore.Parcel(.25F, y, .25F, 0, 0, 0,
				svq, mass, sand, 0, 3, GranularParcelStore.FLAG_SUPPORTED));
		dynamics = new GranularDepositDynamics(registry, parcels, deposits, inventory, 1, 2, 1, .5F);
		require(dynamics.step(new TestDepositEnvironment(1, 2, 1, 0), .05F).settledParcels() == 0
				&& parcels.count() == 2 && deposits.cellSolidVolumeSvq(0) == fullBed
				&& deposits.cellSolidVolumeSvq(1) == 0, "a jointly overfull closure must retain every source at its lowest legal layer");
		var alternate = parcels.parcel(1);
		parcels.removeSwap(1);
		parcels.add(new GranularParcelStore.Parcel(alternate.x(), alternate.y(), alternate.z(), 0, 0, 0,
				svq - 1, 2_600.0 * (svq - 1) / SolidVolume.SVQ_PER_BV, sand, 0, 3, GranularParcelStore.FLAG_SUPPORTED));
		require(dynamics.step(new TestDepositEnvironment(1, 2, 1, 0) {
			@Override public boolean verticalPathOpen(float x, float fromY, float z, float toY, long volume) {
				return volume == svq || toY > .57F;
			}
		}, .05F).settledParcels() == 2 && parcels.count() == 0 && deposits.cellSolidVolumeSvq(0) == fullBed + svq
				&& deposits.cellSolidVolumeSvq(1) == svq - 1,
				"blocking eligible spheres must retain their distinct lowest reachable receivers in one joint conversion");

		parcels = new GranularParcelStore(registry, 4); deposits = new GranularDepositStore(registry, 2, 8);
		inventory = new GranularInventory(parcels, deposits);
		long source = (long) (SolidVolume.SVQ_PER_CELL * .62 * .9), receiver = skeleton / 12;
		for (int cell = 0; cell < 2; cell++) {
			long volume = cell == 0 ? source : receiver;
			require(deposits.tryAdd(cell, new GranularDepositStore.SolidPortion(sand, 0, volume,
					2_600.0 * volume / SolidVolume.SVQ_PER_BV), SolidVolume.SVQ_PER_CELL), "relaxation admission fixture");
		}
		y = Math.nextUp((float) (deposits.cellBulkVolumeSvq(1) * .5 / SolidVolume.SVQ_PER_CELL
				+ GranularMechanics.representativeRadius(svq, .5F)));
		parcels.add(new GranularParcelStore.Parcel(.75F, y, .25F, 0, 0, 0, svq, mass, sand, 0, 0, (byte) 0));
		dynamics = new GranularDepositDynamics(registry, parcels, deposits, inventory, 2, 1, 1, .5F);
		dynamics.disturbCell(0);
		require(dynamics.step(new TestDepositEnvironment(2, 1, 1, 0), .05F).relaxedMoves() == 0
				&& deposits.cellSolidVolumeSvq(0) == source && deposits.cellSolidVolumeSvq(1) == receiver,
				"repose transfer must retain both inventories when the receiving bed would engulf a sphere");
		parcels.removeSwap(0);
		require(dynamics.step(new TestDepositEnvironment(2, 1, 1, 0), .05F).relaxedMoves() > 0,
				"clear receiving geometry must permit the same repose transfer");
	}

	private static void adjacentTargetBedClosureIsAtomic() throws ReflectiveOperationException {
		jointReposeAndColumnBudgetAreAtomic();
		var registry = BuiltinGranularDefinitions.createRegistry();
		int sand = registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		long svq = GranularLimits.MAX_PARCEL_VOLUME_SVQ;
		double mass = registry.material(sand).referenceDensity() * svq / SolidVolume.SVQ_PER_BV;
		float radius = GranularMechanics.representativeRadius(svq, .5F);
		// Two actual wet-state positions, at rest on the outer floor in adjacent receiver cells.
		for (int mode = 0; mode < 4; mode++) {
			var parcels = new GranularParcelStore(registry, 8);
			var deposits = new GranularDepositStore(registry, 512, 16);
			var inventory = new GranularInventory(parcels, deposits);
			parcels.add(new GranularParcelStore.Parcel(3.0236728F, radius, 1.4827497F, 0, 0, 0,
					svq, mass, sand, 0, 352, GranularParcelStore.FLAG_SUPPORTED));
			var untouched = new GranularParcelStore.Parcel(.75F, 1.25F, .75F, 0, 0, 0, svq, mass, sand, 0, 0, (byte) 0);
			parcels.add(untouched);
			parcels.add(new GranularParcelStore.Parcel(2.9902692F, radius, 1.0118865F, 0, 0, 0,
					svq, mass, sand, 0, mode == 1 ? 2 : 350, mode == 2 ? 0 : GranularParcelStore.FLAG_SUPPORTED));
			int selectedMode = mode;
			var environment = new TestDepositEnvironment(8, 8, 8, 0) {
				@Override public double gravity() { return 9.81; }
				@Override public boolean planSettlement(GranularInventory.SettlementPlan plan) {
					require(Arrays.equals(plan.parcelIndices(), new int[] {2, 0})
							&& Arrays.equals(plan.targetCells(), new int[] {133, 134})
							&& plan.targets().size() == 2 && plan.targets().get(0).cell() == 133
							&& plan.targets().get(1).cell() == 134, "source-target pairing and canonical receiver order must survive sorting");
					return selectedMode != 3;
				}
			};
			var coupling = new GranularCouplingFields(8, 8, 8, .5F); coupling.rebuild(parcels, deposits);
			long[] before = coupling.copySolidSvq();
			var snapshots = List.of(parcels.parcel(0), parcels.parcel(1), parcels.parcel(2));
			var dynamics = new GranularDepositDynamics(registry, parcels, deposits, inventory, 8, 8, 8, .5F);
			var stats = dynamics.step(environment, .05F);
			if (mode == 0) {
				require(stats.settledParcels() == 2 && parcels.count() == 1 && parcels.parcel(0).equals(untouched)
						&& deposits.cellSolidVolumeSvq(133) == svq && deposits.cellSolidVolumeSvq(134) == svq
						&& environment.impulseX == 0 && environment.impulseY == 0 && environment.impulseZ == 0,
						"mutually blocked adjacent beds must commit together, preserve the unselected swap successor, and close momentum");
				coupling.rebuild(parcels, deposits);
				require(coupling.totalSolidSvq() == 3 * svq && inventory.totalMass() == 3 * mass,
						"multi-receiver settlement must preserve exact volume and mass");
			} else {
				require(stats.settledParcels() == 0 && parcels.count() == 3 && deposits.groupCount() == 0
						&& parcels.parcel(0).equals(snapshots.get(0)) && parcels.parcel(1).equals(snapshots.get(1))
						&& parcels.parcel(2).equals(snapshots.get(2)) && Arrays.equals(before, coupling.copySolidSvq()),
						"young or unsupported blockers and rejected joint capacity must defer every receiver unchanged");
			}
		}
	}

	private static void jointReposeAndColumnBudgetAreAtomic() throws ReflectiveOperationException {
		var registry = BuiltinGranularDefinitions.createRegistry();
		int sand = registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		int beads = registry.materialHandle(BuiltinGranularDefinitions.LIGHT_BEADS);
		long svq = GranularLimits.MAX_PARCEL_VOLUME_SVQ;
		double mass = registry.material(beads).referenceDensity() * svq / SolidVolume.SVQ_PER_BV;
		float radius = GranularMechanics.representativeRadius(svq, .5F);
		var parcels = new GranularParcelStore(registry, 4);
		var beds = new GranularDepositStore(registry, 2, 8);
		long base = (long) (SolidVolume.SVQ_PER_CELL * .62 * .38);
		require(beds.tryAdd(0, new GranularDepositStore.SolidPortion(sand, 0, base,
				registry.material(sand).referenceDensity() * base / SolidVolume.SVQ_PER_BV), SolidVolume.SVQ_PER_CELL), "joint repose fixture");
		double top = beds.cellBulkVolumeSvq(0) * .5 / SolidVolume.SVQ_PER_CELL;
		float yA = (float) (top + radius), yB = (float) (top + Math.sqrt((double) radius * radius - .01 * .01));
		if (yA < top + radius) yA = Math.nextUp(yA);
		if (yB < top + Math.sqrt((double) radius * radius - .01 * .01)) yB = Math.nextUp(yB);
		parcels.add(new GranularParcelStore.Parcel(.49F, yA, .25F, 0, 0, 0, svq, mass, beads, 0, 3, GranularParcelStore.FLAG_SUPPORTED));
		for (int i = 0; i < 2; i++) parcels.add(new GranularParcelStore.Parcel(.51F, yB, .25F, 0, 0, 0, svq, mass, beads, 0, 3, GranularParcelStore.FLAG_SUPPORTED));
		var inventory = new GranularInventory(parcels, beds);
		var dynamics = new GranularDepositDynamics(registry, parcels, beds, inventory, 2, 1, 1, .5F);
		var environment = new TestDepositEnvironment(2, 1, 1, 0) { @Override public double gravity() { return 9.81; } };
		var before = inventory.exactMass();
		require(dynamics.step(environment, .05F).settledParcels() == 3 && parcels.count() == 0
				&& beds.cellSolidVolumeSvq(0) == base + svq && beds.cellSolidVolumeSvq(1) == 2 * svq
				&& inventory.exactMass().equals(before), "a stable sand bed with lower-repose incoming beads must evaluate the complete neighboring cohort");
		double rise = (beds.cellBulkVolumeSvq(0) - beds.cellBulkVolumeSvq(1)) / (double) SolidVolume.SVQ_PER_CELL;
		require(rise < Math.tan(registry.material(beads).reposeAngleRadians())
				&& environment.impulseX == 0 && environment.impulseY == 0 && environment.impulseZ == 0,
				"the joint candidate must satisfy the incoming repose angle and exact resting momentum closure");

		parcels = new GranularParcelStore(registry, 4); beds = new GranularDepositStore(registry, 36, 8);
		for (int i = 0; i < 2; i++) parcels.add(new GranularParcelStore.Parcel(.75F, radius, .75F, 0, 0, 0, svq, mass, beads, 0, 3, GranularParcelStore.FLAG_SUPPORTED));
		inventory = new GranularInventory(parcels, beds);
		dynamics = new GranularDepositDynamics(registry, parcels, beds, inventory, 3, 4, 3, .5F);
		var plan = inventory.planSettlement(new int[] {0, 1}, 13, SolidVolume.SVQ_PER_CELL);
		var indexField = GranularDepositDynamics.class.getDeclaredField("admission"); indexField.setAccessible(true);
		var index = (GranularBedAdmission) indexField.get(dynamics);
		var slope = GranularDepositDynamics.class.getDeclaredMethod("settlementSlopeAllowed", GranularInventory.SettlementPlan.class, GranularDepositDynamics.Environment.class);
		slope.setAccessible(true);
		index.rebuild(); index.reserveLogicalOperations(GranularLimits.MAX_OPERATIONS_PER_LOGICAL_STEP - 35);
		long parcelRevision = parcels.mutationRevision(), bedRevision = beds.mutationRevision();
		require(!(boolean) slope.invoke(dynamics, plan, new TestDepositEnvironment(3, 4, 3, 0)) && index.budgetBlocked()
				&& parcels.mutationRevision() == parcelRevision && beds.mutationRevision() == bedRevision,
				"missing one neighbor-column scan allowance must defer the entire candidate without mutation");
		index.rebuild(); index.reserveLogicalOperations(GranularLimits.MAX_OPERATIONS_PER_LOGICAL_STEP - 36);
		require((boolean) slope.invoke(dynamics, plan, new TestDepositEnvironment(3, 4, 3, 0)) && index.visits() == 36,
				"each of nine columns must scan four layers once, shared by both sources and all neighbor checks");
	}

	private static void bedAdmissionIndexFollowsSwapsAndWake() {
		var registry = BuiltinGranularDefinitions.createRegistry();
		int sand = registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		var parcels = new GranularParcelStore(registry, 8);
		var deposits = new GranularDepositStore(registry, 3, 8);
		long svq = 1_024; double mass = 2_600.0 * svq / SolidVolume.SVQ_PER_BV;
		for (float x : new float[]{.25F, 1.25F, .75F})
			parcels.add(new GranularParcelStore.Parcel(x, .1F, .25F, 0, 0, 0, svq, mass, sand, 0, 0, (byte) 0));
		var index = new GranularBedAdmission(parcels, deposits, 3, 1, 1, .5F); index.rebuild();
		parcels.removeSwap(0); index.removedSwap(0);
		parcels.removeSwap(0); index.removedSwap(0);
		require(!index.allows(2, SolidVolume.SVQ_PER_CELL / 2, -1)
				&& index.allows(2, SolidVolume.SVQ_PER_CELL / 2, 0), "two swap removals must retain the moved sphere's cell links");
		require(deposits.tryAdd(0, new GranularDepositStore.SolidPortion(sand, 0, svq * 2, mass * 2),
				SolidVolume.SVQ_PER_CELL), "wake index fixture");
		var inventory = new GranularInventory(parcels, deposits);
		require(inventory.wake(0, sand, 0, svq, .25F, .1F, .25F, 0, 0, 0), "wake index fixture must append");
		index.append(parcels.count() - 1);
		require(!index.allows(0, SolidVolume.SVQ_PER_CELL / 2, -1)
				&& index.allows(1, SolidVolume.SVQ_PER_CELL / 2, -1), "wake append must become visible without linking an unrelated cell");
		index.reserveLogicalOperations(GranularLimits.MAX_OPERATIONS_PER_LOGICAL_STEP - index.visits());
		require(!index.allows(0, SolidVolume.SVQ_PER_CELL / 2, -1) && index.budgetBlocked(),
				"exhausted query work must defer conversion before touching inventory");

		var reactiveParcels = new GranularParcelStore(registry, 4);
		var reactiveBeds = new GranularDepositStore(registry, 1, 4);
		var compositions = new SolidCompositionPool(BuiltinChemicalDefinitions.REGISTRY, 3);
		int precipitate = registry.materialHandle(BuiltinGranularDefinitions.PRECIPITATE);
		var record = SolidCompositionRecord.fromStableAmounts(BuiltinChemicalDefinitions.REGISTRY,
				java.util.Map.of(BuiltinChemicalDefinitions.NA_PLUS, 640L, BuiltinChemicalDefinitions.CL_MINUS, 640L), 0);
		int bedHandle = compositions.create(record);
		require(reactiveBeds.tryAdd(0, new GranularDepositStore.SolidPortion(precipitate, bedHandle, 10,
				record.totalMass(BuiltinChemicalDefinitions.REGISTRY)), SolidVolume.SVQ_PER_CELL), "reactive closure budget fixture");
		float contact = Math.nextUp((float) (reactiveBeds.cellBulkVolumeSvq(0) * .5 / SolidVolume.SVQ_PER_CELL
				+ GranularMechanics.representativeRadius(10, .5F)));
		for (int i = 0; i < 2; i++) reactiveParcels.add(new GranularParcelStore.Parcel(.25F, contact, .25F, 0, 0, 0,
				10, record.totalMass(BuiltinChemicalDefinitions.REGISTRY), precipitate, compositions.create(record), 3,
				GranularParcelStore.FLAG_SUPPORTED));
		var reactiveInventory = new GranularInventory(reactiveParcels, reactiveBeds);
		reactiveInventory.bindSolidCompositions(compositions);
		var reactiveIndex = new GranularBedAdmission(reactiveParcels, reactiveBeds, 1, 1, 1, .5F);
		var closure = new GranularSettlementGeometryPlan(reactiveInventory, reactiveIndex, reactiveParcels.capacity());
		long[] species = compositions.speciesTotals(); var originalSources = List.of(reactiveParcels.parcel(0), reactiveParcels.parcel(1));
		reactiveIndex.rebuild(); reactiveIndex.reserveLogicalOperations(GranularLimits.MAX_OPERATIONS_PER_LOGICAL_STEP - 8);
		require(closure.find(0, 0, ignored -> 0, ignored -> SolidVolume.SVQ_PER_CELL) == null && reactiveIndex.budgetBlocked()
				&& reactiveBeds.totalSolidVolumeSvq() == 10 && compositions.liveCount() == 3
				&& Arrays.equals(species, compositions.speciesTotals()) && reactiveParcels.count() == 2
				&& reactiveParcels.parcel(0).equals(originalSources.get(0)) && reactiveParcels.parcel(1).equals(originalSources.get(1)),
				"reactive ownership scan must consume the shared quota before changing any cohort participant");
		reactiveIndex.rebuild();
		require(closure.find(0, 0, ignored -> 0, ignored -> SolidVolume.SVQ_PER_CELL) != null && reactiveIndex.visits() > 8,
				"renewed work quota must permit the same reactive closure with all ownership checks included");

		parcels = new GranularParcelStore(registry, 8); deposits = new GranularDepositStore(registry, 3, 8);
		var untouched = new GranularParcelStore.Parcel(1.25F, .1F, .25F, 0, 0, 0, svq, mass, sand, 0, 0, (byte) 0);
		float floor = GranularMechanics.representativeRadius(svq, .5F);
		parcels.add(new GranularParcelStore.Parcel(.25F, floor, .25F, 0, 0, 0, svq, mass, sand, 0, 3, GranularParcelStore.FLAG_SUPPORTED));
		parcels.add(untouched);
		parcels.add(new GranularParcelStore.Parcel(.25F, floor, .25F, 0, 0, 0, svq, mass, sand, 0, 3, GranularParcelStore.FLAG_SUPPORTED));
		parcels.add(new GranularParcelStore.Parcel(.75F, floor, .25F, 0, 0, 0, svq, mass, sand, 0, 3, GranularParcelStore.FLAG_SUPPORTED));
		inventory = new GranularInventory(parcels, deposits);
		var continued = new GranularDepositDynamics(registry, parcels, deposits, inventory, 3, 1, 1, .5F);
		var chained = continued.step(new TestDepositEnvironment(3, 1, 1, 0) {
			@Override public double shields(int cell, ParticulateMaterial material) { return cell == 0 ? 1 : 0; }
		}, .05F);
		require(chained.settledParcels() == 3 && chained.wokenParcels() == 1 && parcels.count() == 2
				&& parcels.parcel(0).equals(untouched) && parcels.parcel(1).x() == .25F
				&& inventory.totalSolidVolumeSvq() == 4 * svq,
				"non-contiguous cohort removals must retain the unselected sphere and process the swapped successor before wake append");

		parcels = new GranularParcelStore(registry, GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION);
		deposits = new GranularDepositStore(registry, 2, 8);
		for (int i = 0; i < parcels.capacity(); i++) parcels.add(new GranularParcelStore.Parcel(
				i < 10 ? .25F : .75F, .4F, .25F, 0, 0, 0, 1, 2_600.0 / SolidVolume.SVQ_PER_BV,
				sand, 0, i < 10 ? 3 : 0, i < 10 ? GranularParcelStore.FLAG_SUPPORTED : (byte) 0));
		inventory = new GranularInventory(parcels, deposits);
		var dynamics = new GranularDepositDynamics(registry, parcels, deposits, inventory, 2, 1, 1, .5F);
		var stats = dynamics.step(new TestDepositEnvironment(2, 1, 1, 1), .05F);
		require(stats.settledParcels() > 0 && stats.settledParcels() < 10 && stats.wokenParcels() == 0
				&& stats.processedColumns() == 0 && stats.dirtyColumns() > 0
				&& inventory.totalSolidVolumeSvq() == GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION,
				"local query quota must defer remaining conversions and dirty work before a later wake");
	}

	private static class TestDepositEnvironment implements GranularDepositDynamics.Environment {
		private final int sizeX, sizeY, sizeZ;
		private final double shields;
		double impulseX, impulseY, impulseZ;

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
		@Override public void receiveContactImpulse(double x, double y, double z) {
			impulseX += x; impulseY += y; impulseZ += z;
		}

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
