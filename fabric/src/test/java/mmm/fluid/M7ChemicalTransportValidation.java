package mmm.fluid;

import mmm.inventory.LiquidCellInventory;

import mmm.chemistry.BinaryVolumeAllocator;
import mmm.chemistry.BuiltinChemicalDefinitions;
import mmm.chemistry.ChemicalAuditLedgerCodec;
import mmm.chemistry.ChemicalLimits;
import mmm.chemistry.ChemicalMixtureRecord;
import mmm.chemistry.ChemicalParcelStore;
import mmm.chemistry.ChemicalPhaseDefinition;
import mmm.chemistry.ChemicalReaction;
import mmm.chemistry.ChemicalRegistry;
import mmm.chemistry.ChemicalSpecies;
import mmm.chemistry.ChemicalTransferLedger;
import mmm.chemistry.ExactRational;
import mmm.transfer.ChemicalContents;
import mmm.transfer.ContainerFluidLedger;
import mmm.simulation.FullCapabilityTestFixture;
import mmm.units.SolverUnitScale;
import mmm.units.SignedDyadic;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Active-domain M7 gate for marker ownership, reaction placement and conservative reseeding hooks. */
public final class M7ChemicalTransportValidation {
	private static final FluidSolverConfig STATIC_CONFIG = new FluidSolverConfig(
			1.0F, 1_000.0F, 0.0F, 0.01F, 64, 1.0E-6F, 0.45F, 0.002F, 0.0F);
	private static final FluidMaterialRegistry MATERIALS = new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE);
	private static final ChemicalRegistry CHEMISTRY = BuiltinChemicalDefinitions.REGISTRY;

	private M7ChemicalTransportValidation() { }

	public static void main(String[] args) throws Exception {
		boolean fast = args.length > 0 && args[0].equals("--fast");
		validateMixtureBudgetAndBinaryAllocation();
		validateInternalTransferLedgerDiversity();
		validateMissingPhaseRejectedBeforeBinding();
		validateExternalSourceBoundary();
		validateSameCellReaction();
		validateReactionKeepsTinyParcels();
		validateExactNoOpReactionPreservesParcels();
		validateAdjacentCellsDoNotReact();
		validateWallSeparatedCellsDoNotReact();
		validateImmiscibleMarkersDoNotReact();
		validateSplitAndIndicator();
		validateIndicatorFloodFill();
		LiquidCellInventoryValidation.main(new String[0]);
		validateCollisionDisplacement();
		validateChemicalHandoff();
		validateConcentrationControlsDensity();
		validateChemicalContainerRoundTrip();
		validateTransferResolutionIsAtomic();
		validateInsertionAccountsForDisplacedLiquid();
		if (!fast) validatePerformanceBudgets();
		System.out.println("M7 chemical transport gate passed: mixture budget, same-cell reaction, separation and reseeding conservation");
	}

	private static void validateMixtureBudgetAndBinaryAllocation() {
		ChemicalParcelStore maximum = new ChemicalParcelStore(
				CHEMISTRY, SolverUnitScale.forCellSize(1.0F), 73_728, 9_216);
		long residentBytes = Math.addExact(maximum.cpuBytes(), 2L * 73_728L * Integer.BYTES);
		check(residentBytes <= 64L * 1024L * 1024L,
				"maximum default-domain chemical heap model exceeds 64 MiB: " + residentBytes);
		check(maximum.pool().handleCapacity() == 73_729, "mixture handle cap is not reservedMarkerCapacity + 1");
		check(maximum.pool().speciesEntryCapacity() == 16 * 73_729, "species entry cap is not 16 * handle cap");
		check(maximum.pool().cpuBytes() < 32_768, "empty mixture pool allocates only its initial storage");
		var growing = new mmm.chemistry.ChemicalMixturePool(130);
		var record = new ChemicalMixtureRecord(new int[] {0}, new long[] {1}, 0L);
		try {
			growing.create(new ChemicalMixtureRecord(new int[] {ChemicalLimits.MAX_SPECIES}, new long[] {1}, 0L));
			throw new AssertionError("out-of-budget species was accepted");
		} catch (IllegalArgumentException expected) { }
		check(growing.liveCount() == 0, "invalid insertion preserves the pool budget");
		for (int handle = 1; handle <= 130; handle++) {
			check(growing.create(record) == handle, "pool growth preserves handle order");
		}
		try { growing.create(record); throw new AssertionError("full pool accepted another handle"); }
		catch (mmm.chemistry.ChemicalMixturePool.PoolBudgetExceededException expected) { }
		for (int handle = 1; handle <= 130; handle++) {
			check(growing.record(handle) == record, "pool growth preserves records");
			growing.release(handle);
		}
		for (int handle = 130; handle >= 1; handle--) {
			check(growing.create(record) == handle, "released handles retain stack order");
		}
		var replacement = new ChemicalMixtureRecord(new int[] {0, 1}, new long[] {2, 3},
				mmm.units.Mass.fromKilograms(-0.0));
		growing.overwrite(1, replacement);
		check(growing.record(1) == replacement && growing.record(2) == record,
				"replacing one owner preserves other handles sharing its previous immutable record");
		growing.release(1);
		check(!growing.isLive(1) && growing.liveCount() == 129, "release removes only its owner");
		try { growing.record(1); throw new AssertionError("released record remained readable"); }
		catch (IllegalArgumentException expected) { }
		try { growing.release(1); throw new AssertionError("double release was accepted"); }
		catch (IllegalArgumentException expected) { }
		check(growing.create(replacement) == 1 && growing.record(1).massResidualUnits()
				== 0L, "reused handle retains integer mass residual");
		long[] equal = BinaryVolumeAllocator.allocate(5L, new float[]{0.125F, 0.125F}, new int[]{7, 3});
		check(Arrays.equals(equal, new long[]{2L, 3L}), "largest remainder did not use marker ordinal tie-break");
		long[] unequal = BinaryVolumeAllocator.allocate(10L, new float[]{0.1F, 0.2F, 0.3F}, new int[]{0, 1, 2});
		check(Arrays.stream(unequal).sum() == 10L, "binary volume allocation did not close");
	}

	private static void validateInternalTransferLedgerDiversity() {
		java.util.TreeMap<String, Long> species = new java.util.TreeMap<>();
		for (int index = 0; index < 32; index++) species.put("test:species_" + index, 1L);
		ChemicalTransferLedger ledger = new ChemicalTransferLedger(List.of(new ChemicalTransferLedger.Group(
				"mmm:water", false, 1.0, mmm.units.Mass.fromKilograms(1.0), mmm.units.Mass.fromKilograms(0.0), species)));
		try {
			ChemicalAuditLedgerCodec.AuditLedgers decoded = ChemicalAuditLedgerCodec.decode(
					ChemicalAuditLedgerCodec.encode(ledger, ChemicalTransferLedger.EMPTY));
			check(decoded.externalSource().groups().equals(ledger.groups()),
					"complete internal transfer ledger was truncated to the per-parcel species budget");
		} catch (java.io.IOException invalid) {
			throw new AssertionError("complete internal transfer ledger did not round-trip", invalid);
		}

		ChemicalTransferLedger.Builder demo = ChemicalTransferLedger.builder(CHEMISTRY);
		long markerMassUnits = mmm.units.Mass.fromKilograms(15.625);
		ChemicalMixtureRecord acid = chemicalRecordAtMarkerMass(
				Map.of(BuiltinChemicalDefinitions.HCL, 1_000_000L), markerMassUnits);
		ChemicalMixtureRecord base = chemicalRecordAtMarkerMass(
				Map.of(BuiltinChemicalDefinitions.NAOH, 1_000_000L), markerMassUnits);
		for (int marker = 0; marker < 11_520; marker++) {
			demo.add(BuiltinFluidMaterials.WATER.id(), true, 0.125, markerMassUnits, acid);
		}
		for (int marker = 0; marker < 9_216; marker++) {
			demo.add(BuiltinFluidMaterials.WATER.id(), false, 0.125, markerMassUnits, base);
		}
		ChemicalParcelStore audit = new ChemicalParcelStore(CHEMISTRY,
				SolverUnitScale.forCellSize(0.5F), 1, 1);
		audit.requireCanRecordEscapedSink(demo.build());
	}

	private static ChemicalMixtureRecord chemicalRecordAtMarkerMass(
			Map<String, Long> stableAmounts, long markerMassUnits
	) {
		ChemicalMixtureRecord zeroResidual = ChemicalMixtureRecord.fromStableAmounts(
				CHEMISTRY, stableAmounts, 0L);
		return ChemicalMixtureRecord.fromStableAmounts(CHEMISTRY, stableAmounts,
				Math.subtractExact(markerMassUnits, zeroResidual.speciesMassUnits(CHEMISTRY)));
	}

	private static void validateMissingPhaseRejectedBeforeBinding() {
		FluidMaterialRegistry materials = new FluidMaterialRegistry(List.of(
				BuiltinFluidMaterials.DEFAULT_PALETTE.getFirst(),
				new FluidMaterial("test:missing_phase", 1_000.0, "test:missing_phase", 0.0, Math.PI / 2.0)));
		FluidDomain domain = new FluidDomain(4, 4, 4, STATIC_CONFIG, 64);
		domain.setVolume(1, 1, 1, 0.125F);
		domain.setMarkerMaterial(0, materials, 0);
		try {
			domain.bindChemistry(CHEMISTRY);
			throw new AssertionError("material palette with no chemical phase was accepted");
		} catch (IllegalArgumentException expected) {
			check(!domain.hasChemistryState(), "failed chemistry binding left a partial parcel store");
		}
	}

	private static void validateExternalSourceBoundary() {
		FluidDomain domain = domainWithCell(1, 1, 1, 0.125F);
		FluidDomain.ExactVolumeTransfer survival = domain.tryInsertExactVolume(1, 1, 1,
				0.125F, 0, 0, false);
		check(survival.transferred() && domain.chemicalParcels().externalSource().isEmpty(),
				"conservative insertion was recorded as an external source");
		FluidDomain.ExactVolumeTransfer creative = domain.tryInsertExactVolume(1, 1, 1,
				0.125F, 0, 0, true);
		check(creative.transferred() && !domain.chemicalParcels().externalSource().isEmpty(),
				"explicit external insertion was not recorded atomically");
	}

	private static void validateWallSeparatedCellsDoNotReact() {
		FluidDomain domain = new FluidDomain(5, 4, 4, STATIC_CONFIG, 64);
		domain.setVolume(1, 1, 1, 0.125F);
		domain.setVolume(3, 1, 1, 0.125F);
		domain.setSolid(2, 1, 1, true);
		domain.setMarkerMaterial(0, MATERIALS, 0);
		domain.bindChemistry(CHEMISTRY);
		domain.setMarkerChemical(markerInCell(domain, 1, 1, 1),
				Map.of(BuiltinChemicalDefinitions.HCL, 10_000L), false);
		domain.setMarkerChemical(markerInCell(domain, 3, 1, 1),
				Map.of(BuiltinChemicalDefinitions.NAOH, 10_000L), false);
		domain.step();
		long[] totals = domain.chemicalParcels().speciesTotals(domain.particleCount());
		check(totals[CHEMISTRY.speciesHandle(BuiltinChemicalDefinitions.H_PLUS)] == 10_000L
				&& totals[CHEMISTRY.speciesHandle(BuiltinChemicalDefinitions.OH_MINUS)] == 10_000L,
				"acid/base reacted through a solid wall");
	}

	private static void validateImmiscibleMarkersDoNotReact() {
		ChemicalSpecies acid = new ChemicalSpecies("test:h_plus", Map.of("H", 1), 1, ExactRational.ONE, 0xffff0000);
		ChemicalSpecies base = new ChemicalSpecies("test:oh_minus", Map.of("H", 1, "O", 1), -1,
				ExactRational.of(17), 0xff0000ff);
		ChemicalSpecies water = new ChemicalSpecies("test:h2o", Map.of("H", 2, "O", 1), 0,
				ExactRational.of(18), 0xff4e9cff);
		List<String> allowed = List.of(acid.id(), base.id(), water.id());
		ChemicalPhaseDefinition phaseA = new ChemicalPhaseDefinition("test:phase_a", "test:material_a",
				allowed, Map.of(water.id(), ExactRational.ONE), true);
		ChemicalPhaseDefinition phaseB = new ChemicalPhaseDefinition("test:phase_b", "test:material_b",
				allowed, Map.of(water.id(), ExactRational.ONE), true);
		ChemicalReaction neutralizeA = new ChemicalReaction("test:neutralize_a", ChemicalReaction.Kind.INSTANTANEOUS,
				phaseA.id(), Map.of(acid.id(), 1, base.id(), 1), Map.of(water.id(), 1), ExactRational.ZERO);
		ChemicalRegistry chemistry = new ChemicalRegistry(List.of(acid, base, water),
				List.of(phaseA, phaseB), List.of(neutralizeA));
		FluidMaterialRegistry materials = new FluidMaterialRegistry(List.of(
				new FluidMaterial("test:material_a", 1_000.0, "test:immiscible_a", 0.0, Math.PI / 2.0),
				new FluidMaterial("test:material_b", 1_000.0, "test:immiscible_b", 0.0, Math.PI / 2.0)));
		FluidDomain domain = new FluidDomain(4, 4, 4, STATIC_CONFIG, 64);
		domain.setVolume(1, 1, 1, 0.25F);
		domain.setMarkerMaterial(0, materials, 0);
		domain.setMarkerMaterial(1, materials, 1);
		domain.bindChemistry(chemistry);
		domain.setMarkerChemical(0, Map.of(acid.id(), 10_000L), false);
		domain.setMarkerChemical(1, Map.of(base.id(), 10_000L), false);
		domain.step();
		long[] totals = domain.chemicalParcels().speciesTotals(domain.particleCount());
		check(totals[chemistry.speciesHandle(acid.id())] == 10_000L
				&& totals[chemistry.speciesHandle(base.id())] == 10_000L,
				"immiscible chemical phases reacted within one grid cell");
	}

	private static void validateSameCellReaction() {
		FluidDomain domain = domainWithCell(1, 1, 1, 1.0F);
		domain.setMarkerChemical(0, Map.of(BuiltinChemicalDefinitions.HCL, 1_000_000L), true);
		domain.setMarkerChemical(1, Map.of(BuiltinChemicalDefinitions.NAOH, 1_000_000L), false);
		int markerCount = domain.particleCount();
		long[] residualBits = new long[markerCount];
		for (int marker = 0; marker < markerCount; marker++) {
			residualBits[marker] = domain.chemicalParcels().record(marker).massResidualUnits();
		}
		double massBefore = domain.totalMass();
		domain = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
		FluidDomain.StepStats stats = domain.step();
		long[] totals = domain.chemicalParcels().speciesTotals(domain.particleCount());
		check(totals[CHEMISTRY.speciesHandle(BuiltinChemicalDefinitions.H_PLUS)] == 0L, "same-cell reaction left H+");
		check(totals[CHEMISTRY.speciesHandle(BuiltinChemicalDefinitions.OH_MINUS)] == 0L, "same-cell reaction left OH-");
		check(totals[CHEMISTRY.speciesHandle(BuiltinChemicalDefinitions.NA_PLUS)] == 1_000_000L,
				"same-cell reaction lost Na+");
		check(totals[CHEMISTRY.speciesHandle(BuiltinChemicalDefinitions.CL_MINUS)] == 1_000_000L,
				"same-cell reaction lost Cl-");
		check(stats.chemistryDirtyCells() == 1 && stats.chemistryChangedMarkers() > 0,
				"logical chemistry did not run exactly on the dirty cell");
		check(domain.particleCount() == markerCount, "reaction-only step changed marker ownership");
		long residualBefore = mmm.units.Mass.sum(residualBits), residualAfter = 0;
		for (int marker = 0; marker < markerCount; marker++) residualAfter = Math.addExact(residualAfter,
				domain.chemicalParcels().record(marker).massResidualUnits());
		check(residualBefore == residualAfter, "same-cell reaction changed the total integer mass residual");
		check(Math.abs(domain.totalMass() - massBefore) <= Math.max(1.0E-9, Math.ulp(massBefore) * 8.0),
				"same-cell reaction changed marker mass truth");
		int indicated = 0;
		for (int marker = 0; marker < domain.particleCount(); marker++) if (domain.chemicalParcels().indicator(marker)) indicated++;
		check(indicated == 1, "reaction redistributed the indicator bit");
		domain = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
		check(domain.step().chemistryDirtyCells() == 0,
				"clean chemical backend handoff re-evaluated every occupied cell");
	}

	private static void validateReactionKeepsTinyParcels() {
		var inputs=List.of(ChemicalMixtureRecord.fromStableAmounts(CHEMISTRY,Map.of(BuiltinChemicalDefinitions.HCL,1_000_000L),0),
				ChemicalMixtureRecord.fromStableAmounts(CHEMISTRY,Map.of(BuiltinChemicalDefinitions.NAOH,1L),0),
				ChemicalMixtureRecord.fromStableAmounts(CHEMISTRY,Map.of(BuiltinChemicalDefinitions.H2O,1L),0));
		var fixture=new ChemicalReactionCandidateValidation.Fixture(CHEMISTRY,inputs,new int[]{21,21,21});long mass=fixture.totalMass();
		var stats=fixture.step();long[] totals=fixture.totals();
		check(stats.changedMarkers()>0&&fixture.totalMass()==mass&&totals[CHEMISTRY.speciesHandle(BuiltinChemicalDefinitions.H_PLUS)]==999999L
				&&totals[CHEMISTRY.speciesHandle(BuiltinChemicalDefinitions.CL_MINUS)]==1000000L&&totals[CHEMISTRY.speciesHandle(BuiltinChemicalDefinitions.NA_PLUS)]==1L
				&&totals[CHEMISTRY.speciesHandle(BuiltinChemicalDefinitions.H2O)]==2L,"Tiny cell reaction lost mass or products");
	}

	private static void validateExactNoOpReactionPreservesParcels() {
		var mixture=ChemicalMixtureRecord.fromStableAmounts(CHEMISTRY,Map.of(BuiltinChemicalDefinitions.H2O,1001L),mmm.units.Mass.fromKilograms(.125));
		var fixture=new ChemicalReactionCandidateValidation.Fixture(CHEMISTRY,List.of(mixture),new int[]{21});
		var old=fixture.inventory.physicalStockAt(0);
		fixture.inventory.planEdits(List.of(new LiquidCellInventory.Edit(old.key(),new LiquidCellInventory.Stock(old.key(),old.volume(),old.massUnits(),old.mixture(),0,true)))).commit();
		var before=fixture.inventory.snapshot();var stats=fixture.step();
		check(stats.dirtyCells()==1&&stats.evaluations()>0&&stats.changedMarkers()==0&&fixture.inventory.snapshot().rows()==before.rows()
				&&fixture.inventory.physicalStockAt(0).indicator(),"No-op reaction changed cell inventory or indicator provenance");
	}

	private static void validateAdjacentCellsDoNotReact() {
		FluidDomain domain = new FluidDomain(4, 4, 4, STATIC_CONFIG, 64);
		domain.setVolume(1, 1, 1, 0.125F);
		domain.setVolume(2, 1, 1, 0.125F);
		domain.setMarkerMaterial(0, MATERIALS, 0);
		domain.bindChemistry(CHEMISTRY);
		domain.setMarkerChemical(markerInCell(domain, 1, 1, 1),
				Map.of(BuiltinChemicalDefinitions.HCL, 10_000L), false);
		domain.setMarkerChemical(markerInCell(domain, 2, 1, 1),
				Map.of(BuiltinChemicalDefinitions.NAOH, 10_000L), false);
		domain.step();
		long[] totals = domain.chemicalParcels().speciesTotals(domain.particleCount());
		check(totals[CHEMISTRY.speciesHandle(BuiltinChemicalDefinitions.H_PLUS)] == 10_000L,
				"acid reacted across an adjacent-cell boundary");
		check(totals[CHEMISTRY.speciesHandle(BuiltinChemicalDefinitions.OH_MINUS)] == 10_000L,
				"base reacted across an adjacent-cell boundary");
	}

	private static void validateSplitAndIndicator() {
		for(long quantity:new long[]{1001,1}) {
			var domain=domainWithCell(1,1,1,.25F);domain.setMarkerChemical(0,Map.of(BuiltinChemicalDefinitions.H2O,quantity),true);
			long mass=domain.totalMassUnits();var volume=domain.liquidCells.exactVolume();
			for(int step=0;step<2;step++) {
				domain.step();domain=FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
				long total=0;for(var stock:domain.liquidCells.stocks()) {
					if(stock.mixture()!=null)total+=stock.mixture().totalQuanta();
					check(stock.indicator(),"Cell transport lost indicator provenance");
				}
				check(total==quantity&&domain.totalMassUnits()==mass&&domain.liquidCells.exactVolume().equals(volume),
						"Trace composition, actual mass or volume changed across transport and recovery");
			}
		}
	}

	private static void validateIndicatorFloodFill() {
		FluidDomain domain = new FluidDomain(4, 4, 4, STATIC_CONFIG, 64);
		domain.setVolume(1, 1, 1, 0.125F);
		domain.setVolume(2, 1, 1, 0.125F);
		domain.setMarkerMaterial(0, MATERIALS, 0);
		domain.bindChemistry(CHEMISTRY);
		int connectedMarkers = domain.particleCount();
		FluidDomain.IndicatorEditResult rejected = domain.editIndicatorFloodFill(1, 1, 1,
				true, 64, 0.1);
		check(!rejected.accepted(), "over-volume indicator fill was partially accepted");
		for (int marker = 0; marker < domain.particleCount(); marker++) {
			check(!domain.chemicalParcels().indicator(marker), "rejected indicator fill changed a marker");
		}
		FluidDomain.IndicatorEditResult added = domain.editIndicatorFloodFill(1, 1, 1,
				true, 64, 100.0);
		check(added.accepted() && added.changedMarkers() == connectedMarkers && added.affectedVolumeCubicMeters() == 0.25,
				"connected aqueous indicator fill did not cover its exact component");
		for (int marker = 0; marker < domain.particleCount(); marker++) {
			check(domain.chemicalParcels().indicator(marker), "connected indicated marker was skipped");
		}
		FluidDomain.IndicatorEditResult shallowAim = domain.editIndicatorFloodFill(1, 3, 1,
				false, 64, Double.POSITIVE_INFINITY);
		check(shallowAim.accepted() && shallowAim.changedMarkers() == connectedMarkers
				&& shallowAim.affectedVolumeCubicMeters() == 0.25,
				"shallow-surface aim did not snap to nearby chemical marker truth");
		for (int marker = 0; marker < domain.particleCount(); marker++) {
			check(!domain.chemicalParcels().indicator(marker), "shallow-surface eraser skipped a marker");
		}
		added = domain.editIndicatorFloodFill(1, 1, 1, true, 64, 100.0);
		check(added.accepted() && added.changedMarkers() == connectedMarkers
				&& added.affectedVolumeCubicMeters() == 0.25,
				"indicator could not be restored after shallow-aim regression");

		int preservedCell = domain.cellIndex(1, 1, 1);
		int preservedMarkers = 0;
		for (int marker = 0; marker < domain.particleCount(); marker++) {
			if (domain.particleCellIndex(marker) == preservedCell) preservedMarkers++;
		}
		// Replacement carriers keep their new unmarked provenance.
		domain.setVolume(2, 1, 1, 0.25F);
		int marked = 0, plain = 0;
		for (int marker = 0; marker < domain.particleCount(); marker++) {
			check(domain.chemicalParcels().indicator(marker) == (domain.particleCellIndex(marker) == preservedCell),
					"volume replacement changed indicator provenance outside its marker group");
			if (domain.chemicalParcels().indicator(marker)) marked++; else plain++;
		}
		check(marked == preservedMarkers && marked > 0 && plain > 0,
				"later mixed markers inherited indicator retroactively");
		FluidDomain.IndicatorEditResult erased = domain.editIndicatorFloodFill(1, 1, 1,
				false, 64, Double.POSITIVE_INFINITY);
		check(erased.accepted() && erased.changedMarkers() == marked && erased.affectedVolumeCubicMeters() == 0.375,
				"eraser did not clear only current marked provenance");
		for (int marker = 0; marker < domain.particleCount(); marker++) {
			check(!domain.chemicalParcels().indicator(marker), "eraser left an indicated marker");
		}

		FluidDomain wall = new FluidDomain(5, 4, 4, STATIC_CONFIG, 64);
		wall.setVolume(1, 1, 1, 0.125F);
		wall.setSolid(2, 1, 1, true);
		wall.setMarkerMaterial(0, MATERIALS, 0);
		wall.bindChemistry(CHEMISTRY);
		FluidDomain.IndicatorEditResult throughWall = wall.editIndicatorFloodFill(3, 1, 1,
				true, 64, 100.0);
		check(!throughWall.accepted(), "shallow-aim snapping crossed a closed cut face");
	}


	private static void validateCollisionDisplacement() {
		FluidDomain domain = domainWithCell(1, 1, 1, 1.0F);
		domain.setMarkerChemical(0, Map.of(
				BuiltinChemicalDefinitions.H2O, 100_000L,
				BuiltinChemicalDefinitions.NACL, 10_000L), true);
		long[] before = domain.chemicalParcels().speciesTotals(domain.particleCount());
		double volumeBefore = domain.totalVolumeExact();
		FluidDomain.ChemicalDisplacement displaced = domain.setSolidMicroMaskChemical(1, 1, 1, 0xff);
		check(domain.particleCount() == 0 && domain.chemicalParcels().totalQuanta() == 0L,
				"collision did not remove parcel ownership");
		check(displaced.ledger().totalQuanta() == Arrays.stream(before).sum(),
				"collision displacement ledger lost species");
		check(domain.chemicalParcels().escapedSink().isEmpty(),
				"recoverable collision displacement was recorded as escaped before restoration");
		domain.setSolidMicroMaskChemical(1, 1, 1, 0);
		FluidDomain.ExactChemicalTransfer restored = domain.tryInsertExactChemical(
				1, 1, 1, displaced.ledger(), 0);
		check(restored.transferred(), "exact collision chemistry could not be restored");
		check(Double.doubleToRawLongBits(domain.totalVolumeExact()) == Double.doubleToRawLongBits(volumeBefore),
				"collision restoration changed exact marker volume");
		check(Arrays.equals(before, domain.chemicalParcels().speciesTotals(domain.particleCount())),
				"collision restoration changed species inventory");
		int indicated = 0;
		for (int marker = 0; marker < domain.particleCount(); marker++) if (domain.chemicalParcels().indicator(marker)) indicated++;
		check(indicated > 0, "collision restoration lost indicator provenance");
	}

	private static void validateChemicalHandoff() {
		FluidDomain source = domainWithCell(1, 1, 1, 1.0F);
		source.setMarkerChemical(0, Map.of(
				BuiltinChemicalDefinitions.H2O, 123_457L,
				BuiltinChemicalDefinitions.NACL, 999L), true);
		var auditFixture = source.chemicalLedgerForMarkerRange(0, 1);
		source.chemicalParcels().recordExternalSource(auditFixture);
		source.chemicalParcels().recordEscapedSink(auditFixture);
		FlipChemicalParticleState state = source.exportChemicalParticleState();
		int[] mixtureIndices = state.copyMarkerMixtureIndex();
		check(state.mixtures().size() < mixtureIndices.length && mixtureIndices[0] != mixtureIndices[1],
				"handoff fixture must include shared and distinct compositions");
		FlipMaterialParticleState material = state.materials();
		long[] alteredMass = material.copyParticleMassUnits();
		alteredMass[1] += 1.0;
		alteredMass[2] -= 1.0;
		FlipMaterialParticleState altered = new FlipMaterialParticleState(
				material.sizeX(), material.sizeY(), material.sizeZ(), material.cellSize(), material.particleCount(),
				material.totalVolume(), mmm.units.SolverUnitScale.forCellSize(material.cellSize()).massUnitsForNormalized(material.totalMass()), material.materials(), material.copyPositionVolume(),
				material.copyParticleVelocities(), material.copySolidMask(), alteredMass, material.copyMaterialPaletteIndex());
		try {
			new FlipChemicalParticleState(altered, CHEMISTRY, state.mixtures(), mixtureIndices,
					state.copyIndicator(), state.copySpeciesTotals(), state.residualLedgerUnits(),
					state.externalSource(), state.escapedSink());
			throw new AssertionError("Per-marker mass validation must detect redistributed mass");
		} catch (IllegalArgumentException expected) {
			check(expected.getMessage().contains("mass relation"), "Expected per-marker mass rejection");
		}
		FlipChemicalParticleState decoded = new FlipChemicalParticleState(material, CHEMISTRY,
				state.mixtures(), mixtureIndices, state.copyIndicator(), state.copySpeciesTotals(),
				state.residualLedgerUnits(), state.externalSource(), state.escapedSink());
		var equivalent = new ChemicalRegistry(CHEMISTRY.species(), CHEMISTRY.phases(), CHEMISTRY.reactions());
		check(decoded.runtimeMixtures(equivalent).equals(state.runtimeMixtures(CHEMISTRY)),
				"decoded and captured chemical records disagree");
		var reversed = new java.util.ArrayList<>(state.mixtures());
		java.util.Collections.reverse(reversed);
		try {
			new FlipChemicalParticleState(material, CHEMISTRY, reversed, mixtureIndices,
					state.copyIndicator(), state.copySpeciesTotals(), state.residualLedgerUnits(),
					state.externalSource(), state.escapedSink());
			throw new AssertionError("Unsorted chemical handoff was accepted");
		} catch (IllegalArgumentException expected) { }
		FluidDomain restored = new FluidDomain(4, 4, 4, STATIC_CONFIG, 64);
		restored.restoreChemicalParticleState(state, CHEMISTRY);
		check(Arrays.equals(source.chemicalParcels().speciesTotals(source.particleCount()),
				restored.chemicalParcels().speciesTotals(restored.particleCount())),
				"chemical handoff changed species inventory");
		check(Arrays.equals(state.copyIndicator(), restored.exportChemicalParticleState().copyIndicator()),
				"chemical handoff changed indicator bits");
		check(state.mixtures().equals(restored.exportChemicalParticleState().mixtures()),
				"chemical handoff changed canonical mixture table");
		check(state.residualLedgerUnits() == restored.exportChemicalParticleState().residualLedgerUnits(),
				"chemical handoff changed raw residual ledger");
		check(state.externalSource().groups().equals(restored.exportChemicalParticleState().externalSource().groups())
				&& state.escapedSink().groups().equals(restored.exportChemicalParticleState().escapedSink().groups()),
				"chemical handoff changed external source/sink audit ledgers");
		var capturedRecord = state.runtimeMixtures(CHEMISTRY).get(mixtureIndices[0]);
		restored.setMarkerChemical(0, Map.of(BuiltinChemicalDefinitions.H2O, 27L), false);
		check(source.chemicalParcels().record(0).equals(capturedRecord)
				&& state.runtimeMixtures(CHEMISTRY).get(mixtureIndices[0]).equals(capturedRecord),
				"editing a restored store changed its source or snapshot");
		restored.restoreChemicalParticleState(decoded, equivalent);
		check(restored.exportChemicalParticleState().mixtures().equals(state.mixtures()),
				"cold record conversion failed after independent candidate mutation");
	}

	private static void validateConcentrationControlsDensity() {
		validateConcentrationControlsDensity(STATIC_CONFIG);
		validateConcentrationControlsDensity(new FluidSolverConfig(
				0.5F, 1_000.0F, 0.0F, 0.01F, 64, 1.0E-6F, 0.45F, 0.002F, 0.0F));
	}

	private static void validateConcentrationControlsDensity(FluidSolverConfig config) {
		FluidDomain source = new FluidDomain(4, 4, 4, config, 128);
		source.setVolume(1, 1, 1, 1.0F);
		source.setVolume(2, 1, 1, 1.0F);
		source.setMarkerMaterial(0, MATERIALS, 0);
		source.bindChemistry(CHEMISTRY);
		FlipMaterialParticleState original = source.exportMaterialParticleState();
		long[] mass = original.copyParticleMassUnits();
		float[] positionVolume = original.copyPositionVolume();
		SolverUnitScale unitScale = SolverUnitScale.forCellSize(config.cellSize());
		java.util.ArrayList<ChemicalMixtureRecord> mixtures = new java.util.ArrayList<>(original.particleCount());
		byte[] indicators = new byte[original.particleCount()];
		for (int marker = 0; marker < original.particleCount(); marker++) {
			ChemicalMixtureRecord initial = source.chemicalParcels().record(marker);
			java.util.TreeMap<String, Long> amounts = new java.util.TreeMap<>(initial.stableAmounts(CHEMISTRY));
			int cellX = (int) Math.floor(positionVolume[marker * 4] / config.cellSize());
			if (cellX == 2) {
				long saltQuanta = Math.round(mmm.units.Mass.kilograms(mass[marker]) * 0.10
						* ChemicalLimits.QUANTA_PER_MOL / CHEMISTRY.species(CHEMISTRY.speciesHandle(BuiltinChemicalDefinitions.NACL)).molarMassKgPerMol().toDouble());
				amounts.merge(BuiltinChemicalDefinitions.NACL, saltQuanta, Math::addExact);
			}
			ChemicalMixtureRecord mixture = ChemicalMixtureRecord.fromStableAmounts(
					CHEMISTRY, amounts, initial.massResidualUnits());
			mixtures.add(mixture);
			mass[marker] = mixture.massUnits(CHEMISTRY);
		}
		long totalMass = mmm.units.Mass.sum(mass);
		FlipMaterialParticleState materials = new FlipMaterialParticleState(
				original.sizeX(), original.sizeY(), original.sizeZ(), original.cellSize(), original.particleCount(),
				original.totalVolume(), totalMass, original.materials(), positionVolume,
				original.copyParticleVelocities(), original.copySolidMask(), mass,
				original.copyMaterialPaletteIndex());
		FlipChemicalParticleState chemical = FlipChemicalParticleState.capture(
				materials, CHEMISTRY, mixtures, indicators,
				mmm.chemistry.ChemicalTransferLedger.EMPTY,
				mmm.chemistry.ChemicalTransferLedger.EMPTY);
		FluidDomain stratified = new FluidDomain(4, 4, 4, config, 128);
		stratified.restoreChemicalParticleState(chemical, CHEMISTRY);
		double freshDensity = stratified.densityInCell(1, 1, 1);
		double brineDensity = stratified.densityInCell(2, 1, 1);
		FluidCellInventoryView brine = stratified.observeCellInventory(2, 1, 1);
		check(Math.abs(freshDensity - 1_000.0) < 1.0E-9
				&& brineDensity > 1_099.999 && brineDensity < 1_100.001
				&& brine != null && Math.abs(brine.densityKilogramsPerCubicMetre() - brineDensity) < 1.0E-9
				&& brine.species().stream().anyMatch(value -> value.speciesId().equals(BuiltinChemicalDefinitions.NACL)),
				"species mass/marker volume did not produce concentration-dependent cell density: fresh="
						+ freshDensity + " brine=" + brineDensity + " cellSize=" + config.cellSize());
	}

	private static void validateChemicalContainerRoundTrip() throws Exception {
		validateThermalChemicalExtractionAudit();
		FluidDomain source = domainWithCell(1, 1, 1, 0.25F);
		for (int marker = 0; marker < source.particleCount(); marker++) {
			source.setMarkerChemical(marker, Map.of(
					BuiltinChemicalDefinitions.H2O, 12_345_678L + marker,
					BuiltinChemicalDefinitions.NACL, 456_789L + marker), true);
		}
		long[] expectedSpecies = source.chemicalParcels().speciesTotals(source.particleCount());
		long expectedResidual = source.exportChemicalParticleState().residualLedgerUnits();
		long expectedMass = source.totalMassUnits();

		FluidDomain.ExactChemicalTransfer extracted = source.tryExtractExactChemical(1, 1, 1, 0.25F, 0);
		check(extracted.transferred() && source.particleCount() == 0,
				"chemical bucket extraction was not all-or-nothing");
		ChemicalContents contents = ChemicalContents.fromLedger(extracted.ledger(), CHEMISTRY,
				ContainerFluidLedger.cubicMetersToNanos(extracted.ledger().totalNormalizedVolume()));
		check(ChemicalContents.decode(contents.encode()).equals(contents),
				"chemical bucket canonical codec did not round-trip");
		ChemicalContents.Split partial = contents.split(123_456_789L, CHEMISTRY);
		check(partial.remaining() != null
				&& partial.inserted().totalVolumeNanos() + partial.remaining().totalVolumeNanos()
				== contents.totalVolumeNanos()
				&& partial.inserted().totalQuanta() + partial.remaining().totalQuanta() == contents.totalQuanta()
				&& mergedContentsSpecies(partial.inserted(), partial.remaining()).equals(contentsSpecies(contents))
				&& Math.abs(partial.inserted().totalMass() + partial.remaining().totalMass() - contents.totalMass())
				<= Math.max(1.0E-9, Math.ulp(contents.totalMass()) * 8.0),
				"partial chemical bucket split did not close volume/species/mass");
		partial.inserted().validateAgainst(CHEMISTRY);
		partial.remaining().validateAgainst(CHEMISTRY);
		FluidDomain restored = source;
		FluidDomain.ExactChemicalTransfer inserted = restored.tryInsertExactChemical(
				1, 1, 1, contents.toLedger(CHEMISTRY, restored.unitScale()), 0);
		check(inserted.transferred(), "canonical chemical bucket could not be inserted");
		check(Arrays.equals(expectedSpecies, restored.chemicalParcels().speciesTotals(restored.particleCount())),
				"chemical bucket round-trip changed species inventory");
		check(restored.exportChemicalParticleState().residualLedgerUnits() == expectedResidual,
				"chemical bucket round-trip changed raw mass residual ledger");
		check(restored.totalMassUnits() == expectedMass,
				"chemical bucket round-trip changed exact mass");
		for (int marker = 0; marker < restored.particleCount(); marker++) {
			check(restored.chemicalParcels().indicator(marker), "chemical bucket round-trip lost indicator provenance");
		}
		long[] beforeRefusedSpecies = restored.chemicalParcels().speciesTotals(restored.particleCount());
		double beforeRefusedVolume = restored.totalVolumeExact();
		FluidDomain.ExactChemicalTransfer refusedRepresentation = restored.tryExtractExactChemical(
				1, 1, 1, 0.125F, 0, ignored -> false);
		check(!refusedRepresentation.transferred()
				&& Double.doubleToRawLongBits(restored.totalVolumeExact()) == Double.doubleToRawLongBits(beforeRefusedVolume)
				&& Arrays.equals(beforeRefusedSpecies,
						restored.chemicalParcels().speciesTotals(restored.particleCount())),
				"unrepresentable chemical bucket extraction committed marker changes");

		ChemicalContents incompatible = new ChemicalContents(CHEMISTRY.fingerprint(),
				mmm.chemistry.ChemicalLimits.QUANTA_PER_MOL, List.of(new ChemicalContents.Group(
				BuiltinFluidMaterials.OIL.id(), false, 1L,
				mmm.units.Mass.fromKilograms(CHEMISTRY.species(CHEMISTRY.speciesHandle(BuiltinChemicalDefinitions.H2O))
						.molarMassKgPerMol().toDouble()), mmm.units.Mass.fromKilograms(0.0),
				List.of(new ChemicalContents.SpeciesAmount(BuiltinChemicalDefinitions.H2O,
						mmm.chemistry.ChemicalLimits.QUANTA_PER_MOL)))));
		try {
			incompatible.validateAgainst(CHEMISTRY);
			throw new AssertionError("Expected chemical phase incompatibility rejection");
		} catch (IllegalArgumentException expected) {
			// The item remains intact; no solver mutation is attempted.
		}
	}

	static FluidDomain thermalChemicalExtractionSource() throws Exception {
		FullCapabilityTestFixture.Registries registries = FullCapabilityTestFixture.load();
		FluidSolverConfig config = new FluidSolverConfig(
				0.5F, 1_000.0F, 9.81F, 0.05F, 64, 1.0E-6F, 0.45F, 0.002F, 0.0F);
		FluidDomain source = new FluidDomain(10, 8, 10, config, 8_192);
		for (int z = 0; z < source.sizeZ(); z++) for (int y = 0; y < 6; y++) {
			for (int x = 0; x < 4; x++) source.setVolume(x, y, z, 1.0F);
		}
		for (int marker = 0; marker < source.particleCount(); marker++) {
			source.setMarkerMaterial(marker, registries.liquids(), 0);
		}
		FullCapabilityTestFixture.bindFull(source, registries, 293.15, 8);
		float[] positionVolume = source.exportChemicalParticleState().materials().copyPositionVolume();
		for (int marker = 0; marker < source.particleCount(); marker++) {
			boolean acid = positionVolume[marker * 4] < 2.0F;
			var amounts = new java.util.TreeMap<>(source.chemicalParcels().record(marker).stableAmounts(registries.chemical()));
			amounts.put(acid ? BuiltinChemicalDefinitions.HCL : BuiltinChemicalDefinitions.NAOH, 1_000_000L);
			source.setMarkerChemical(marker, amounts, acid);
		}
		return source;
	}

	static mmm.world.MovingBodyFluidCoupling.Request thermalChemicalExtractionImpulse(FluidDomain source) {
		var config = source.config;
		// Set the disturbance from the body's actual liquid outflow response.
		FluidDomain couplingProbe = FluidDomain.restoreAuthoritativeState(source.captureAuthoritativeState());
		couplingProbe.applyMovingBodyCoupling(new mmm.world.MovingBodyFluidCoupling.Request(
				1.0F, 1.0F, 2.0F, 1.0, 0.0, 0.0, 0.125));
		double couplingRate = new CellLiquidVolumeTransport(couplingProbe).courantRate(0.0);
		check(couplingRate > 0.0, "moving-body fixture must produce grid outflow");
		return new mmm.world.MovingBodyFluidCoupling.Request(
				1.0F, 1.0F, 2.0F, 2.0 / (config.timeStep() * couplingRate), 0.0, 0.0, 0.125);
	}

	private static void validateThermalChemicalExtractionAudit() throws Exception {
		FluidDomain source = thermalChemicalExtractionSource();
		CpuFluidSolverBackend backend = new CpuFluidSolverBackend();
		var impulse = thermalChemicalExtractionImpulse(source);
		for (int step = 0; step < 40; step++) {
			FluidStepCandidate candidate = backend.evaluate(new FluidStepRequest(
					step, source.captureAuthoritativeState(), step == 0 ? List.of(impulse) : List.of(), false, 0));
			if (step == 0) check(candidate.movingBodyCoupling().coupledMarkers() > 0
					&& candidate.movingBodyCoupling().impulseXKilogramMetresPerSecond() > 0.0,
					"chemical transport fixture must receive the moving-body impulse");
			source = candidate.claimPreparedCpuDomain();
			check(source.exactInternalEnergy().equals(source.thermalAudit().expectedInventory())
					&& source.gasBoundary().auditClosed(), "chemical transport step audit diverged");
		}
		SignedDyadic liquidBefore = SignedDyadic.sum(source.exportThermalParticleState().copyInternalEnergy());
		FluidDomain.ExactChemicalTransfer extracted = source.tryExtractExactChemical(3, 4, 5, 8.0F, 12);
		check(extracted.transferred(), "thermal chemical bucket fixture could not be extracted");
		// Gas geometry may exchange heat or book volume work during the same extraction.
		// Compare the item with liquid columns, then check the complete liquid/gas audit.
		var after = source.exportThermalParticleState();
		SignedDyadic liquidRemoved = liquidBefore.subtract(SignedDyadic.sum(after.copyInternalEnergy()));
		check(Math.abs(liquidRemoved.toDouble() - extracted.internalEnergy())
				<= 8 * Math.ulp(extracted.internalEnergy()),
				"chemical bucket energy differs from actual liquid carrier removal");
		check(source.exactInternalEnergy().equals(source.thermalAudit().expectedInventory())
				&& source.gasBoundary().auditClosed(), "chemical extraction did not close liquid/gas energy and material ledgers");
	}

	/** Frozen M7.0 wall-time budgets, measured only by the explicit full chemistry selector. */
	private static void validatePerformanceBudgets() {
		FluidDomain baseline = benchmarkDomain(false);
		FluidDomain chemical = benchmarkDomain(true);
		for (int warmup = 0; warmup < 30; warmup++) {
			baseline.step();
			chemical.step();
		}
		long[] baselineSamples = new long[15];
		long[] chemicalSamples = new long[15];
		double[] pairedDeltaSamples = new double[15];
		for (int sample = 0; sample < baselineSamples.length; sample++) {
			if ((sample & 1) == 0) {
				baselineSamples[sample] = timedSteps(baseline, 20);
				chemicalSamples[sample] = timedSteps(chemical, 20);
			} else {
				chemicalSamples[sample] = timedSteps(chemical, 20);
				baselineSamples[sample] = timedSteps(baseline, 20);
			}
			pairedDeltaSamples[sample] = (double) chemicalSamples[sample] / baselineSamples[sample] - 1.0;
		}
		long baselineMedian = percentile(baselineSamples, 0.50);
		long chemicalMedian = percentile(chemicalSamples, 0.50);
		Arrays.sort(pairedDeltaSamples);
		double stepDelta = pairedDeltaSamples[pairedDeltaSamples.length / 2];
		System.out.printf("M7.0 clean-step raw median: baseline=%.3f ms chemistry=%.3f ms paired-delta=%.2f%%%n",
				baselineMedian / 20_000_000.0, chemicalMedian / 20_000_000.0, stepDelta * 100.0);
		check(stepDelta <= 0.05,
				"chemistry-on clean-step median overhead exceeds 5%: " + stepDelta * 100.0 + "%");

		long reactionP95 = benchmarkAcidBaseReactionP95();
		check(reactionP95 <= 10_000_000L,
				"typical acid/base chemistry p95 exceeds 10 ms: " + reactionP95 / 1_000_000.0 + " ms");
		long residentBytes = Math.addExact(new ChemicalParcelStore(
				CHEMISTRY, SolverUnitScale.forCellSize(1.0F), 73_728, 9_216).cpuBytes(),
				2L * 73_728L * Integer.BYTES);
		System.out.printf("M7.0 budgets: heap=%.2f MiB clean-step-median=%.3f/%.3f ms delta=%.2f%% acid-base-p95=%.3f ms%n",
				residentBytes / 1048576.0, baselineMedian / 20_000_000.0, chemicalMedian / 20_000_000.0,
				stepDelta * 100.0, reactionP95 / 1_000_000.0);
	}

	private static FluidDomain benchmarkDomain(boolean chemistry) {
		FluidDomain domain = new FluidDomain(10, 7, 10, STATIC_CONFIG, 4_096);
		for (int z = 1; z <= 8; z++) for (int y = 1; y <= 4; y++) for (int x = 1; x <= 8; x++) {
			domain.setVolume(x, y, z, 1.0F);
		}
		domain.setMarkerMaterial(0, MATERIALS, 0);
		if (chemistry) domain.bindChemistry(CHEMISTRY);
		return domain;
	}

	private static long timedSteps(FluidDomain domain, int count) {
		long start = System.nanoTime();
		for (int step = 0; step < count; step++) {
			FluidDomain.StepStats stats = domain.step();
			check(stats.chemistryDirtyCells() == 0, "clean-step benchmark became chemically dirty");
		}
		return System.nanoTime() - start;
	}

	private static long benchmarkAcidBaseReactionP95() {
		var acid=ChemicalMixtureRecord.fromStableAmounts(CHEMISTRY,Map.of(BuiltinChemicalDefinitions.HCL,1_000_003L),0);
		var base=ChemicalMixtureRecord.fromStableAmounts(CHEMISTRY,Map.of(BuiltinChemicalDefinitions.NAOH,1_000_003L),0);
		var inputs=new java.util.ArrayList<ChemicalMixtureRecord>();int[] cells=new int[64];Arrays.fill(cells,21);
		for(int i=0;i<64;i++)inputs.add((i&1)==0?acid:base);
		long[] samples=new long[100];
		for(int iteration=-20;iteration<samples.length;iteration++) {
			var fixture=new ChemicalReactionCandidateValidation.Fixture(CHEMISTRY,inputs,cells);long start=System.nanoTime();
			var stats=fixture.step();long elapsed=System.nanoTime()-start;
			check(stats.dirtyCells()==1&&stats.changedMarkers()>0,"Acid/base budget fixture did not execute its cell reaction");
			if(iteration>=0)samples[iteration]=elapsed;
		}
		return percentile(samples,.95);
	}

	private static long percentile(long[] values, double fraction) {
		long[] sorted = values.clone();
		Arrays.sort(sorted);
		int index = Math.max(0, Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * fraction) - 1));
		return sorted[index];
	}

	private static Map<String, Long> contentsSpecies(ChemicalContents... contents) {
		java.util.TreeMap<String, Long> totals = new java.util.TreeMap<>();
		for (ChemicalContents value : contents) for (ChemicalContents.Group group : value.groups()) {
			for (ChemicalContents.SpeciesAmount species : group.species()) {
				totals.merge(species.speciesId(), species.amountQuanta(), Math::addExact);
			}
		}
		return Map.copyOf(totals);
	}

	private static Map<String, Long> mergedContentsSpecies(ChemicalContents first, ChemicalContents second) {
		return contentsSpecies(first, second);
	}

	private static void validateTransferResolutionIsAtomic() throws Exception {
		LiquidCellInventoryValidation.main(new String[0]);
		var domain=domainWithCoarseMarkers(1);domain.setMarkerChemical(0,Map.of(BuiltinChemicalDefinitions.H2O,1L),false);
		var before=domain.captureAuthoritativeState();
		var extracted=domain.tryExtractExactChemical(1,1,1,.0625F,0);
		check(extracted.transferred(),"A trace component prevented a resolved partial volume transfer");
		long removed=extracted.ledger().totalQuanta(),retained=domain.chemicalParcels().totalQuanta();
		check(removed+retained==1&&domain.totalMassUnits()+extracted.ledger().totalMassUnits()==before.liquid().stocks().stream().mapToLong(LiquidCellInventory.Stock::massUnits).sum(),
				"Trace transfer did not conserve integer species and mass");
		var restored=FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
		check(restored.exportLiquidCellState().stocks().equals(domain.exportLiquidCellState().stocks()),"Trace transfer failed complete recovery");
	}

	private static void validateInsertionAccountsForDisplacedLiquid() {
		var domain=new FluidDomain(2,2,2,STATIC_CONFIG,256);
		domain.bindMaterialRegistry(MATERIALS);domain.bindChemistry(CHEMISTRY);
		float[] filled=new float[8];Arrays.fill(filled,1);filled[7]=0;domain.setVolumes(filled);
		var before=domain.captureAuthoritativeState();
		check(!domain.tryInsertExactMixture(1,1,1,List.of(new FluidDomain.MaterialPortion(0,2,
				domain.unitScale.massUnitsForNormalized(2000))),2,true).transferred(),"Insertion ignored occupied global capacity");
		FluidDomainStateAssertions.assertSameState(before,domain.captureAuthoritativeState(),"Global capacity rejection");
	}

	private static FluidDomain domainWithCell(int x, int y, int z, float volume) {
		FluidDomain domain = new FluidDomain(4, 4, 4, STATIC_CONFIG, 64);
		domain.setVolume(x, y, z, volume);
		domain.setMarkerMaterial(0, MATERIALS, 0);
		domain.bindChemistry(CHEMISTRY);
		return domain;
	}

	private static int markerInCell(FluidDomain domain, int x, int y, int z) {
		int cell = domain.cellIndex(x, y, z);
		for (int marker = 0; marker < domain.particleCount(); marker++) {
			if (domain.particleCellIndex(marker) == cell) return marker;
		}
		throw new AssertionError("chemical fixture has no marker in the requested cell");
	}

	private static FluidDomain domainWithCoarseMarkers(int count) {
		FluidDomain domain = new FluidDomain(4, 4, 4, STATIC_CONFIG, 64);
		float[] positionsAndVolumes = new float[4 * count];
		long[] masses = new long[count];
		for (int marker = 0; marker < count; marker++) {
			positionsAndVolumes[4 * marker] = count == 1 ? 1.5F : 1.25F + 0.5F * marker;
			positionsAndVolumes[4 * marker + 1] = 1.5F;
			positionsAndVolumes[4 * marker + 2] = 1.5F;
			positionsAndVolumes[4 * marker + 3] = 0.125F;
			masses[marker] = mmm.units.SolverUnitScale.forCellSize(STATIC_CONFIG.cellSize()).massUnitsForNormalized(125.0);
		}
		domain.restoreMaterialParticleState(new FlipMaterialParticleState(
				4, 4, 4, STATIC_CONFIG.cellSize(), count, 0.125 * count, mmm.units.SolverUnitScale.forCellSize(STATIC_CONFIG.cellSize()).massUnitsForNormalized(125.0 * count),
				MATERIALS.materials(), positionsAndVolumes, new float[3 * count], new byte[64], masses, new int[count]));
		domain.bindChemistry(CHEMISTRY);
		return domain;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
