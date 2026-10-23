package mmm.fluid;

import mmm.inventory.LiquidCellInventory;

import mmm.chemistry.BinaryVolumeAllocator;
import mmm.chemistry.BuiltinChemicalDefinitions;
import mmm.chemistry.ChemicalAuditLedgerCodec;
import mmm.chemistry.ChemicalLimits;
import mmm.chemistry.ChemicalMixtureRecord;
import mmm.chemistry.RegionChemistry;
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

/** Cell-stock chemistry, material separation, conservative transfers and complete recovery. */
public final class M7ChemicalTransportValidation {
	private static final FluidSolverConfig STATIC_CONFIG = new FluidSolverConfig(
			1.0F, 1_000.0F, 0.0F, 0.01F, 64, 1.0E-6F, 0.002F);
	private static final FluidMaterialRegistry MATERIALS = new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE);
	private static final ChemicalRegistry CHEMISTRY = BuiltinChemicalDefinitions.REGISTRY;

	private M7ChemicalTransportValidation() { }

	public static void main(String[] args) throws Exception {
		boolean fast = args.length > 0 && args[0].equals("--fast");
		ChemicalReactionCandidateValidation.validate();
		validateInternalTransferLedgerDiversity();
		validateMissingPhaseRejectedBeforeBinding();
		validateExternalSourceBoundary();
		validateSameCellReaction();
		
		
		validateAdjacentCellsDoNotReact();
		validateWallSeparatedCellsDoNotReact();
		validateImmiscibleStocksDoNotReact();
		validateSplitComposition();
		validateCollisionDisplacement();
		validateChemicalHandoff();
		validateConcentrationControlsDensity();
		validateChemicalContainerRoundTrip();
		validateTransferResolutionIsAtomic();
		validateInsertionAccountsForDisplacedLiquid();
		validateCleanChemistryUsesCommonLiquidStep();
		if (!fast) validateReactionBudget();
		System.out.println("Cell chemical transport passed: local mixing, material separation, inventory and recovery");
	}

	

private static void validateInternalTransferLedgerDiversity() {
		java.util.TreeMap<String, Long> species = new java.util.TreeMap<>();
		for (int index = 0; index < 32; index++) species.put("test:species_" + index, 1L);
		ChemicalTransferLedger ledger = new ChemicalTransferLedger(List.of(new ChemicalTransferLedger.Group(
				"mmm:water", 1.0, mmm.units.Mass.fromKilograms(1.0), mmm.units.Mass.fromKilograms(0.0), species)));
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
			demo.add(BuiltinFluidMaterials.WATER.id(), 0.125, markerMassUnits, acid);
		}
		for (int marker = 0; marker < 9_216; marker++) {
			demo.add(BuiltinFluidMaterials.WATER.id(), 0.125, markerMassUnits, base);
		}
		RegionChemistry audit = new RegionChemistry(CHEMISTRY,1);
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
		FluidDomain domain = new FluidDomain(4, 4, 4, STATIC_CONFIG);
		domain.setVolume(1, 1, 1, 0.125F);
		domain.bindMaterialRegistry(materials);
		domain.domainCollision.setMarkerMaterial(0, materials, 1);
		try {
			domain.bindChemistry(CHEMISTRY);
			throw new AssertionError("occupied material with no chemical phase was accepted");
		} catch (IllegalArgumentException expected) {
			check(!domain.hasChemistryState(), "failed chemistry binding left a partial parcel store");
		}
	}

	private static void validateExternalSourceBoundary() {
		FluidDomain domain = domainWithCell(1, 1, 1, 0.125F);
		FluidDomain.ExactVolumeTransfer survival = domain.domainInventory.tryInsertExactVolume(1, 1, 1,
				0.125F, 0, 0, false);
		check(survival.transferred() && domain.chemistry().externalSource().isEmpty(),
				"conservative insertion was recorded as an external source");
		FluidDomain.ExactVolumeTransfer creative = domain.domainInventory.tryInsertExactVolume(1, 1, 1,
				0.125F, 0, 0, true);
		check(creative.transferred() && !domain.chemistry().externalSource().isEmpty(),
				"explicit external insertion was not recorded atomically");
	}

	private static void validateWallSeparatedCellsDoNotReact() {
		FluidDomain domain = new FluidDomain(5, 4, 4, STATIC_CONFIG);
		domain.setVolume(1, 1, 1, 0.125F);
		domain.setVolume(3, 1, 1, 0.125F);
		domain.setSolid(2, 1, 1, true);
		domain.bindMaterialRegistry(MATERIALS);
		domain.bindChemistry(CHEMISTRY);
		domain.setMarkerChemical(stockOrdinalInCell(domain,1,1,1,0),
				Map.of(BuiltinChemicalDefinitions.HCL, 10_000L));
		domain.setMarkerChemical(stockOrdinalInCell(domain,3,1,1,0),
				Map.of(BuiltinChemicalDefinitions.NAOH, 10_000L));
		domain.domainStep.step();
		long[] totals = speciesTotals(domain);
		check(totals[CHEMISTRY.speciesHandle(BuiltinChemicalDefinitions.H_PLUS)] == 10_000L
				&& totals[CHEMISTRY.speciesHandle(BuiltinChemicalDefinitions.OH_MINUS)] == 10_000L,
				"acid/base reacted through a solid wall");
	}

	private static void validateImmiscibleStocksDoNotReact() {
		var acid=new ChemicalSpecies("test:h_plus",Map.of("H",1),1,ExactRational.ONE,0xffff0000);
		var base=new ChemicalSpecies("test:oh_minus",Map.of("H",1,"O",1),-1,ExactRational.of(17),0xff0000ff);
		var water=new ChemicalSpecies("test:h2o",Map.of("H",2,"O",1),0,ExactRational.of(18),0xff4e9cff);
		var allowed=List.of(acid.id(),base.id(),water.id());
		var phaseA=new ChemicalPhaseDefinition("test:phase_a","test:material_a",allowed,Map.of(water.id(),ExactRational.ONE));
		var phaseB=new ChemicalPhaseDefinition("test:phase_b","test:material_b",allowed,Map.of(water.id(),ExactRational.ONE));
		var chemistry=new ChemicalRegistry(List.of(acid,base,water),List.of(phaseA,phaseB),List.of(
				new ChemicalReaction("test:neutralize_a",ChemicalReaction.Kind.INSTANTANEOUS,phaseA.id(),
						Map.of(acid.id(),1,base.id(),1),Map.of(water.id(),1),ExactRational.ZERO)));
		var materials=new FluidMaterialRegistry(List.of(new FluidMaterial("test:material_a",1000,"test:immiscible_a",0,Math.PI/2),
				new FluidMaterial("test:material_b",1000,"test:immiscible_b",0,Math.PI/2)));
		var domain=new FluidDomain(4,4,4,STATIC_CONFIG);domain.bindMaterialRegistry(materials);domain.bindChemistry(chemistry);
		int cell=domain.domainFlowFields.cellIndex(1,1,1);var additions=new java.util.ArrayList<LiquidCellInventory.Stock>();
		for(int material=0;material<2;material++){
			long mass=domain.unitScale.massUnitsForNormalized(125);
			var mixture=ChemicalMixtureRecord.fromStableAmounts(chemistry,Map.of(material==0?acid.id():base.id(),10_000L),0);
			mixture=mixture.withMassResidualUnits(mass-mixture.speciesMassUnits(chemistry));
			additions.add(new LiquidCellInventory.Stock(new LiquidCellInventory.Key(cell,material,255),SignedDyadic.of(.125),mass,mixture,0));
		}
		domain.liquidCells.planAdditions(additions).commit();domain.markCellInventoryChanged();
		domain.chemistry.markDirtyCell(cell);long mass=domain.totalMassUnits();var volume=domain.liquidCells.exactVolume();
		domain.step();domain=FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
		var totals=speciesTotals(domain);
		check(totals[chemistry.speciesHandle(acid.id())]==10_000L&&totals[chemistry.speciesHandle(base.id())]==10_000L
				&&domain.totalMassUnits()==mass&&domain.liquidCells.exactVolume().equals(volume),
				"Immiscible material stocks reacted or changed their physical inventory");
	}

private static void validateSameCellReaction() {
		var domain=domainWithCell(1,1,1,1);
		setCellChemical(domain,1,1,1,0,Map.of(BuiltinChemicalDefinitions.HCL,1_000_000L,
				BuiltinChemicalDefinitions.NAOH,1_000_000L));
		var volume=domain.liquidCells.exactVolume();long mass=domain.totalMassUnits();
		domain=FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
		check(domain.step().chemistryChangedStocks()>0,"Mixed liquid stock did not react");
		var totals=speciesTotals(domain);
		check(totals[CHEMISTRY.speciesHandle(BuiltinChemicalDefinitions.H_PLUS)]==0
				&&totals[CHEMISTRY.speciesHandle(BuiltinChemicalDefinitions.OH_MINUS)]==0
				&&totals[CHEMISTRY.speciesHandle(BuiltinChemicalDefinitions.NA_PLUS)]==1_000_000L
				&&totals[CHEMISTRY.speciesHandle(BuiltinChemicalDefinitions.CL_MINUS)]==1_000_000L,
				"Mixed stock reaction lost spectator ions or left acid/base unreacted");
		check(domain.totalMassUnits()==mass&&domain.liquidCells.exactVolume().equals(volume),
				"Reaction changed actual mass or volume");
		domain=FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());domain.step();
		check(Arrays.equals(totals,speciesTotals(domain))&&domain.totalMassUnits()==mass
				&&domain.liquidCells.exactVolume().equals(volume),"Reacted liquid inventory changed on its normal successor");
	}

private static void validateAdjacentCellsDoNotReact() {
		FluidDomain domain = new FluidDomain(4, 4, 4, STATIC_CONFIG);
		domain.setVolume(1, 1, 1, 0.125F);
		domain.setVolume(2, 1, 1, 0.125F);
		domain.bindMaterialRegistry(MATERIALS);
		domain.bindChemistry(CHEMISTRY);
		domain.setMarkerChemical(stockOrdinalInCell(domain,1,1,1,0),
				Map.of(BuiltinChemicalDefinitions.HCL, 10_000L));
		domain.setMarkerChemical(stockOrdinalInCell(domain,2,1,1,0),
				Map.of(BuiltinChemicalDefinitions.NAOH, 10_000L));
		domain.domainStep.step();
		long[] totals = speciesTotals(domain);
		check(totals[CHEMISTRY.speciesHandle(BuiltinChemicalDefinitions.H_PLUS)] == 10_000L,
				"acid reacted across an adjacent-cell boundary");
		check(totals[CHEMISTRY.speciesHandle(BuiltinChemicalDefinitions.OH_MINUS)] == 10_000L,
				"base reacted across an adjacent-cell boundary");
	}

	private static void validateSplitComposition() {
		for(long quantity:new long[]{1001,1}) {
			var domain=domainWithCell(1,1,1,.25F);setCellChemical(domain,1,1,1,0,Map.of(BuiltinChemicalDefinitions.H2O,quantity));
			long mass=domain.totalMassUnits();var volume=domain.liquidCells.exactVolume();
			for(int step=0;step<2;step++) {
				domain.domainStep.step();domain=FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
				long total=0;for(var stock:domain.liquidCells.stocks()) {
					if(stock.mixture()!=null)total+=stock.mixture().totalQuanta();
				}
				check(total==quantity&&domain.totalMassUnits()==mass&&domain.liquidCells.exactVolume().equals(volume),
						"Trace composition, actual mass or volume changed across transport and recovery");
			}
		}
	}



private static void validateCollisionDisplacement() {
		FluidDomain domain = domainWithCell(1, 1, 1, 1.0F);
		setCellChemical(domain,1,1,1,0,Map.of(
				BuiltinChemicalDefinitions.H2O, 100_000L,
				BuiltinChemicalDefinitions.NACL, 10_000L));
		long[] before = speciesTotals(domain);
		var volumeBefore=domain.liquidCells.exactVolume();
		FluidDomain.ChemicalDisplacement displaced = domain.setSolidMicroMaskChemical(1, 1, 1, 0xff);
		check(domain.exportLiquidCellState().stocks().isEmpty()&&domain.totalChemicalSpeciesQuanta()==0,
				"Collision did not remove liquid inventory");
		check(displaced.ledger().totalQuanta() == Arrays.stream(before).sum(),
				"collision displacement ledger lost species");
		check(domain.chemistry().escapedSink().isEmpty(),
				"recoverable collision displacement was recorded as escaped before restoration");
		domain.setSolidMicroMaskChemical(1, 1, 1, 0);
		FluidDomain.ExactChemicalTransfer restored = domain.domainInventory.tryInsertExactChemical(
				1, 1, 1, displaced.ledger(), 0);
		check(restored.transferred(), "exact collision chemistry could not be restored");
		check(domain.liquidCells.exactVolume().equals(volumeBefore),"Collision restoration changed exact inventory volume");
		check(Arrays.equals(before, speciesTotals(domain)),
				"collision restoration changed species inventory");
	}

	private static void validateChemicalHandoff() {
		var source=domainWithCell(1,1,1,1);
		setCellChemical(source,1,1,1,0,Map.of(BuiltinChemicalDefinitions.H2O,123_457L,BuiltinChemicalDefinitions.NACL,999L));
		var auditFixture=source.chemicalLedgerForMarkerRange(0,source.liquidCells.physicalSize());
		source.chemistry().recordExternalSource(auditFixture);source.chemistry().recordEscapedSink(auditFixture);
		var state=source.captureAuthoritativeState();var before=source.exportLiquidCellState();
		var restored=FluidDomain.restoreAuthoritativeState(state);
		check(restored.exportLiquidCellState().rows().equals(before.rows())&&Arrays.equals(speciesTotals(source),speciesTotals(restored))
				&&source.chemistry().externalSource().equals(restored.chemistry().externalSource())
				&&source.chemistry().escapedSink().equals(restored.chemistry().escapedSink()),
				"Complete chemical recovery changed stock quantities or source/sink inventory");
		setCellChemical(restored,1,1,1,0,Map.of(BuiltinChemicalDefinitions.H2O,27L));
		check(source.exportLiquidCellState().rows().equals(before.rows())&&state.liquid().rows().equals(before.rows()),
				"Editing a restored stock changed its source or saved snapshot");
	}

private static void validateConcentrationControlsDensity() {
		for(float h:new float[]{1,.5F}){
			var config=new FluidSolverConfig(h,1000,0,.01F,64,1e-6F,.002F);
			var domain=new FluidDomain(4,4,4,config);domain.bindMaterialRegistry(MATERIALS);
			domain.setVolume(1,1,1,1);domain.setVolume(2,1,1,1);domain.bindChemistry(CHEMISTRY);
			int cell=domain.domainFlowFields.cellIndex(2,1,1);var old=domain.liquidCells.stocksInCell(cell).getFirst();
			var amounts=new java.util.TreeMap<>(old.mixture().stableAmounts(CHEMISTRY));
			long salt=Math.round(.1*old.massKilograms()*ChemicalLimits.QUANTA_PER_MOL
					/CHEMISTRY.species(CHEMISTRY.speciesHandle(BuiltinChemicalDefinitions.NACL)).molarMassKgPerMol().toDouble());
			amounts.put(BuiltinChemicalDefinitions.NACL,salt);
			var mixture=ChemicalMixtureRecord.fromStableAmounts(CHEMISTRY,amounts,old.mixture().massResidualUnits());
			domain.liquidCells.planEdits(List.of(new LiquidCellInventory.Edit(old.key(),new LiquidCellInventory.Stock(
					old.key(),old.volume(),mixture.massUnits(CHEMISTRY),mixture,old.internalEnergy())))).commit();
			domain.markCellInventoryChanged();domain=FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
			double fresh=domain.densityInCell(1,1,1),brine=domain.densityInCell(2,1,1);
			var observation=domain.observeCellInventory(2,1,1);
			check(Math.abs(fresh/1000-1)<.001&&brine/fresh>1.09&&brine/fresh<1.11&&observation!=null
					&&observation.species().stream().anyMatch(value->value.speciesId().equals(BuiltinChemicalDefinitions.NACL)),
					"Actual stock mass and volume lost the concentration-dependent density: fresh="+fresh+", brine="+brine);
		}
	}

private static void validateChemicalContainerRoundTrip() throws Exception {
		validateThermalChemicalExtractionAudit();
		FluidDomain source = domainWithCell(1, 1, 1, 0.25F);
		setCellChemical(source,1,1,1,0,Map.of(BuiltinChemicalDefinitions.H2O,12_345_678L,
				BuiltinChemicalDefinitions.NACL,456_789L));
		long[] expectedSpecies = speciesTotals(source);
		long expectedResidual=residualMassInventory(source);
		long expectedMass = source.totalMassUnits();

		FluidDomain.ExactChemicalTransfer extracted = source.tryExtractExactChemical(1, 1, 1, 0.25F, 0);
		check(extracted.transferred()&&source.exportLiquidCellState().stocks().isEmpty(),
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
		FluidDomain.ExactChemicalTransfer inserted = restored.domainInventory.tryInsertExactChemical(
				1, 1, 1, contents.toLedger(CHEMISTRY, restored.unitScale()), 0);
		check(inserted.transferred(), "canonical chemical bucket could not be inserted");
		check(Arrays.equals(expectedSpecies, speciesTotals(restored)),
				"chemical bucket round-trip changed species inventory");
		check(residualMassInventory(restored)==expectedResidual,
				"chemical bucket round-trip changed raw mass residual ledger");
		check(restored.totalMassUnits() == expectedMass,
				"chemical bucket round-trip changed exact mass");
		long[] beforeRefusedSpecies = speciesTotals(restored);
		double beforeRefusedVolume = restored.totalVolumeExact();
		FluidDomain.ExactChemicalTransfer refusedRepresentation = restored.tryExtractExactChemical(
				1, 1, 1, 0.125F, 0, ignored -> false);
		check(!refusedRepresentation.transferred()
				&& Double.doubleToRawLongBits(restored.totalVolumeExact()) == Double.doubleToRawLongBits(beforeRefusedVolume)
				&& Arrays.equals(beforeRefusedSpecies,
						speciesTotals(restored)),
				"unrepresentable chemical bucket extraction committed marker changes");

		ChemicalContents incompatible = new ChemicalContents(CHEMISTRY.fingerprint(),
				mmm.chemistry.ChemicalLimits.QUANTA_PER_MOL, List.of(new ChemicalContents.Group(
				BuiltinFluidMaterials.OIL.id(), 1L,
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
				0.5F, 1_000.0F, 9.81F, 0.05F, 64, 1.0E-6F, 0.002F);
		FluidDomain source = new FluidDomain(10, 8, 10, config);
		source.bindMaterialRegistry(registries.liquids());
		for (int z = 0; z < source.sizeZ(); z++) for (int y = 0; y < 6; y++) {
			for (int x = 0; x < 4; x++) source.setVolume(x, y, z, 1.0F);
		}
		FullCapabilityTestFixture.bindFull(source, registries, 293.15, 8);
		for(var stock:source.exportLiquidCellState().physicalStocks()){
			int cell=stock.key().cell(),x=cell%source.sizeX(),y=cell/source.sizeX()%source.sizeY(),z=cell/(source.sizeX()*source.sizeY());
			boolean acid=(x+.5)*config.cellSize()<2;
			var mixture=mmm.chemistry.ChemicalMixtureRecord.fromSolutesAndSolventMass(registries.chemical(),
					Map.of(acid ? BuiltinChemicalDefinitions.HCL : BuiltinChemicalDefinitions.NAOH, 1_000_000L),
					BuiltinChemicalDefinitions.H2O, stock.massUnits());
			setCellChemical(source,x,y,z,stock.key().material(),mixture.stableAmounts(registries.chemical()));
		}
		return source;
	}

	static mmm.world.MovingBodyFluidCoupling.Request thermalChemicalExtractionImpulse(FluidDomain source) {
		var config = source.config;
		// Set the disturbance from the body's actual liquid outflow response.
		FluidDomain couplingProbe = FluidDomain.restoreAuthoritativeState(source.captureAuthoritativeState());
		couplingProbe.applyMovingBodyCoupling(mmm.world.MovingBodyFluidCoupling.prescribedCube(
				1.0F, 1.0F, 2.0F, 1.0, 0.0, 0.0, 0.125));
		double peakVelocity=0;
		for(var axis:new float[][]{couplingProbe.mac.velocityX,couplingProbe.mac.velocityY,couplingProbe.mac.velocityZ})
			for(float velocity:axis)peakVelocity=Math.max(peakVelocity,Math.abs(velocity));
		double couplingRate=peakVelocity/config.cellSize();
		check(couplingRate > 0.0, "moving-body fixture must produce grid outflow");
		return mmm.world.MovingBodyFluidCoupling.prescribedCube(
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
		SignedDyadic liquidBefore=liquidEnergyInventory(source);
		FluidDomain.ExactChemicalTransfer extracted = source.tryExtractExactChemical(3, 4, 5, 8.0F, 12);
		check(extracted.transferred(), "thermal chemical bucket fixture could not be extracted");
		// Gas geometry may exchange heat or book volume work during the same extraction.
		// Compare the item with liquid columns, then check the complete liquid/gas audit.
		SignedDyadic liquidRemoved=liquidBefore.subtract(liquidEnergyInventory(source));
		check(Math.abs(liquidRemoved.toDouble() - extracted.internalEnergy())
				<= 8 * Math.ulp(extracted.internalEnergy()),
				"chemical bucket energy differs from actual liquid carrier removal");
		check(source.exactInternalEnergy().equals(source.thermalAudit().expectedInventory())
				&& source.gasBoundary().auditClosed(), "chemical extraction did not close liquid/gas energy and material ledgers");
	}

	/** Binding inactive chemistry preserves the common liquid calculation. */
	private static void validateCleanChemistryUsesCommonLiquidStep() {
		FluidDomain baseline = cleanFlowDomain(false);
		FluidDomain chemical = cleanFlowDomain(true);
		for (var domain : new FluidDomain[]{baseline, chemical}) {
			domain.domainFlowFields.preparePhysicalState();
			Arrays.fill(domain.mac.velocityX, 0.1F);
			domain.cellMacStage.publishMomentum();
		}
		for (int step = 0; step < 4; step++) {
			var plainStats = baseline.domainStep.step();
			var chemicalStats = chemical.domainStep.step();
			check(plainStats.chemistryVisitedCells() == 0 && chemicalStats.chemistryVisitedCells() == 0,
					"Inactive chemistry created reaction work");
			check(baseline.liquidCells.massUnits() == chemical.liquidCells.massUnits()
					&& baseline.liquidCells.exactVolume().equals(chemical.liquidCells.exactVolume()),
					"Inactive chemistry changed common liquid inventory");
			check(Arrays.equals(baseline.mac.velocityX, chemical.mac.velocityX)
					&& Arrays.equals(baseline.mac.velocityY, chemical.mac.velocityY)
					&& Arrays.equals(baseline.mac.velocityZ, chemical.mac.velocityZ),
					"Inactive chemistry changed the common liquid flow");
		}
	}

	private static void validateReactionBudget() {
		long reactionP95 = benchmarkAcidBaseReactionP95();
		check(reactionP95 <= 10_000_000L,
				"typical acid/base chemistry p95 exceeds 10 ms: " + reactionP95 / 1_000_000.0 + " ms");
		System.out.printf("Acid/base reaction p95=%.3f ms%n", reactionP95 / 1_000_000.0);
	}

	private static FluidDomain cleanFlowDomain(boolean chemistry) {
		FluidDomain domain = new FluidDomain(10, 7, 10, STATIC_CONFIG);
		for (int z = 1; z <= 8; z++) for (int y = 1; y <= 4; y++) for (int x = 1; x <= 8; x++) {
			domain.setVolume(x, y, z, 1.0F);
		}
		domain.bindMaterialRegistry(MATERIALS);
		if (chemistry) domain.bindChemistry(CHEMISTRY);
		return domain;
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
			check(stats.visitedCells()==1&&stats.changedStocks()>0,"Acid/base budget fixture did not execute its cell reaction");
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
		var domain=domainWithCell(1,1,1,.125F);setCellChemical(domain,1,1,1,0,Map.of(BuiltinChemicalDefinitions.H2O,1L));
		var before=domain.captureAuthoritativeState();
		var extracted=domain.tryExtractExactChemical(1,1,1,.0625F,0);
		check(extracted.transferred(),"A trace component prevented a resolved partial volume transfer");
		long removed=extracted.ledger().totalQuanta(),retained=domain.totalChemicalSpeciesQuanta();
		check(removed+retained==1&&domain.totalMassUnits()+extracted.ledger().totalMassUnits()==before.liquid().stocks().stream().mapToLong(LiquidCellInventory.Stock::massUnits).sum(),
				"Trace transfer did not conserve integer species and mass");
		var captured=domain.captureAuthoritativeState();
		check(Arrays.stream(captured.phaseOccupancy().copyLiquidMicroVolumes()).sum()==.0625,"Extraction capture retained the source phase occupation");
		var restored=FluidDomain.restoreAuthoritativeState(captured);
		check(restored.exportLiquidCellState().stocks().equals(domain.exportLiquidCellState().stocks()),"Trace transfer failed complete recovery");
	}

	private static void validateInsertionAccountsForDisplacedLiquid() {
		var domain=new FluidDomain(2,2,2,STATIC_CONFIG);
		domain.bindMaterialRegistry(MATERIALS);domain.bindChemistry(CHEMISTRY);
		float[] filled=new float[8];Arrays.fill(filled,1);filled[7]=0;domain.setVolumes(filled);
		var before=domain.captureAuthoritativeState();
		check(!domain.domainInventory.tryInsertExactMixture(1,1,1,List.of(new FluidDomain.MaterialPortion(0,2,
				domain.unitScale.massUnitsForNormalized(2000))),2,true).transferred(),"Insertion ignored occupied global capacity");
		FluidDomainStateAssertions.assertSameState(before,domain.captureAuthoritativeState(),"Global capacity rejection");
	}

	private static FluidDomain domainWithCell(int x, int y, int z, float volume) {
		FluidDomain domain = new FluidDomain(4, 4, 4, STATIC_CONFIG);
		domain.setVolume(x, y, z, volume);
		domain.bindMaterialRegistry(MATERIALS);
		domain.bindChemistry(CHEMISTRY);
		return domain;
	}

	private static int stockOrdinalInCell(FluidDomain domain,int x,int y,int z,int material) {
		var key=new LiquidCellInventory.Key(domain.domainFlowFields.cellIndex(x,y,z),material,255);
		var stocks=domain.exportLiquidCellState().physicalStocks();
		for(int i=0;i<stocks.size();i++)if(stocks.get(i).key().equals(key))return i;
		throw new AssertionError("Chemical fixture has no stock at "+key);
	}
	private static void setCellChemical(FluidDomain domain,int x,int y,int z,int material,Map<String,Long> amounts){
		domain.setMarkerChemical(stockOrdinalInCell(domain,x,y,z,material),amounts);
	}

private static long[] speciesTotals(FluidDomain domain){
		var result=new long[domain.chemistry().registry().species().size()];
		for(var stock:domain.exportLiquidCellState().stocks())if(stock.mixture()!=null){
			var mixture=stock.mixture();for(int entry=0;entry<mixture.entryCount();entry++){
				int species=mixture.speciesHandle(entry);result[species]=Math.addExact(result[species],mixture.amountAt(entry));
			}
		}
		return result;
	}
	private static long residualMassInventory(FluidDomain domain){
		long residual=0;for(var stock:domain.exportLiquidCellState().stocks())if(stock.mixture()!=null)
			residual=Math.addExact(residual,stock.mixture().massResidualUnits());return residual;
	}
	private static SignedDyadic liquidEnergyInventory(FluidDomain domain){
		var energy=SignedDyadic.ZERO;for(var stock:domain.exportLiquidCellState().stocks())energy=energy.add(SignedDyadic.of(stock.internalEnergy()));return energy;
	}
	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
