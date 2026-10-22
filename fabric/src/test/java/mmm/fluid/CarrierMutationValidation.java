package mmm.fluid;

import mmm.fluid.air.GasRegistry;
import mmm.thermal.ThermalTransport;
import mmm.simulation.FullCapabilityTestFixture;
import java.util.List;

/** External inventory edits, joint capacity, source/sink accounting and complete recovery. */
public final class CarrierMutationValidation {
	private CarrierMutationValidation() { }
	public static void main(String[] args) throws Exception {
		phaseMomentumFollowsEachRemovedCell();
		insertExtractAndRestoreKeepQuantities();
		defaultInsertionsConserveThermalInventory();
		explicitInsertionsRequirePositiveTemperature();
		restoreRequiresConnectedOpenCapacity();
		readOnlyCellQueriesFollowCarrierEdits();
		volumeObservationRoundsTheExactInventoryOnce();
		insertionNeverRoundsCapacityUp();insertionNeverRoundsTheRequestUp();
		externalInsertionsRouteAroundLiquidSealedGas();
		System.out.println("Cell inventory mutation validation passed");
	}
	private static void phaseMomentumFollowsEachRemovedCell() {
		var domain=new FluidDomain(6,3,3,new FluidSolverConfig(.5F,1000,0,.05F,120,1e-5F,.002F));
		domain.setVolume(1,1,1,.5F);domain.setVolume(2,1,1,.5F);
		domain.domainFlowFields.ensureVelocityGrid();
		int shared=domain.domainFlowFields.xFaceIndex(2,1,1);
		domain.mac.velocityX[shared]=2;domain.cellMacStage.publishMomentum();
		int source=domain.domainFlowFields.cellIndex(1,1,1),receiver=domain.domainFlowFields.cellIndex(2,1,1);
		var first=domain.liquidCells.stocksInCell(source).getFirst();var second=domain.liquidCells.stocksInCell(receiver).getFirst();
		long moved=first.massUnits()/2;
		var edit=domain.liquidCells.planEdits(List.of(
				new mmm.inventory.LiquidCellInventory.Edit(first.key(),new mmm.inventory.LiquidCellInventory.Stock(first.key(),mmm.units.SignedDyadic.of(.25),first.massUnits()-moved,null,0,false)),
				new mmm.inventory.LiquidCellInventory.Edit(second.key(),new mmm.inventory.LiquidCellInventory.Stock(second.key(),mmm.units.SignedDyadic.of(.75),second.massUnits()+moved,null,0,false))));
		double before=domain.exportMacMomentum().copyX()[shared];
		var momentum=domain.cellMacStage.preparePhaseMomentum(edit,java.util.Map.of(source,moved));
		require(Math.abs(momentum.momentum().copyX()[shared]-.75*before)<1e-12
				&&Math.abs(momentum.removedKineticEnergyByCell().get(source).toDouble()-mmm.units.Mass.kilograms(moved))<1e-12,
				"Equal neighboring removal and stationary insertion hid the source momentum or kinetic energy");

		var separate=new FluidDomain(6,3,3,domain.config());
		separate.setVolume(1,1,1,.5F);separate.setVolume(4,1,1,.5F);separate.setSolid(3,1,1,true);
		separate.domainFlowFields.ensureVelocityGrid();
		separate.mac.velocityX[separate.domainFlowFields.xFaceIndex(1,1,1)]=2;
		separate.mac.velocityX[separate.domainFlowFields.xFaceIndex(5,1,1)]=4;
		separate.cellMacStage.publishMomentum();
		var edits=new java.util.ArrayList<mmm.inventory.LiquidCellInventory.Edit>();var removals=new java.util.TreeMap<Integer,Long>();
		for(int x:new int[]{1,4}) {
			int cell=separate.domainFlowFields.cellIndex(x,1,1);var stock=separate.liquidCells.stocksInCell(cell).getFirst();long mass=stock.massUnits()/2;
			removals.put(cell,mass);edits.add(new mmm.inventory.LiquidCellInventory.Edit(stock.key(),new mmm.inventory.LiquidCellInventory.Stock(stock.key(),mmm.units.SignedDyadic.of(.25),stock.massUnits()-mass,null,0,false)));
		}
		var prepared=separate.cellMacStage.preparePhaseMomentum(separate.liquidCells.planEdits(edits),removals);
		double slow=prepared.removedKineticEnergyByCell().get(separate.domainFlowFields.cellIndex(1,1,1)).toDouble();
		double fast=prepared.removedKineticEnergyByCell().get(separate.domainFlowFields.cellIndex(4,1,1)).toDouble();
		require(slow>0&&Math.abs(fast-4*slow)<1e-12,"Separate phase sources shared their kinetic energy by mass instead of source motion");
	}

	private static void insertExtractAndRestoreKeepQuantities() throws Exception {
		var registries=FullCapabilityTestFixture.load();
		var domain=emptyMaterialDomain(4096);
		domain.bindChemistry(registries.chemical());
		domain.bindGranular(registries.granular(),domain.materialRegistry,registries.materials());
		domain.bindGasRegistry(registries.gas());
		domain.setVolume(1,1,1,.5F);domain.setVolume(2,2,2,.25F);
		int target=domain.liquidCells.physicalStocks().stream().filter(stock->stock.key().cell()==domain.domainFlowFields.cellIndex(2,2,2)).mapToInt(stock->domain.liquidCells.ordinalOf(stock.key())).findFirst().orElseThrow();
		domain.setMarkerChemical(target,java.util.Map.of(mmm.chemistry.BuiltinChemicalDefinitions.H2O,7L),true);
		// Initial composition owns its reference energy before the 300 K state is created.
		domain.bindThermal(registries.thermal(),domain.materialRegistry,300,ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		var retained=domain.liquidCells.stocksInCell(domain.domainFlowFields.cellIndex(2,2,2));
		var before=domain.liquidCells.exactVolume();long mass=domain.totalMassUnits();
		require(domain.tryExtractExactVolume(1,1,1,.125F,0,0).transferred(),"Partial cell extraction failed");
		require(before.subtract(domain.liquidCells.exactVolume()).equals(mmm.units.SignedDyadic.of(.125))&&domain.totalMassUnits()<mass,"Extraction failed to remove actual volume and integer mass");
		require(retained.equals(domain.liquidCells.stocksInCell(domain.domainFlowFields.cellIndex(2,2,2))),"Extraction edited an independent cell's composition or energy");
		require(domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),"Extraction energy audit does not close");
		var captured=domain.captureAuthoritativeState();var restored=FluidDomain.restoreAuthoritativeState(captured);
		require(captured.liquid().stocks().equals(restored.exportLiquidCellState().stocks())&&java.util.Arrays.equals(captured.momentum().copyY(),restored.exportMacMomentum().copyY()),"Mutation recovery changed cell inventory or momentum");
		domain.setVolume(1,1,1,0);domain.setVolume(2,2,2,0);
		require(domain.totalMassUnits()==0&&domain.liquidCells.exactVolume().signum()==0&&domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),"Removing final liquid retained mass or lost carried energy");
	}

	private static void externalInsertionsRouteAroundLiquidSealedGas() throws Exception {
		var registries = FullCapabilityTestFixture.load();
		for (int mode = 0; mode < 3; mode++) {
			var domain = new FluidDomain(5, 3, 3, new FluidSolverConfig(.5F, 1000, 0, .05F, 120, 1e-5F, .002F));
			FullCapabilityTestFixture.bindEmptyMaterialState(domain, registries.liquids());
			for (int z = 0; z < 3; z++) for (int y = 0; y < 3; y++) for (int x = 0; x < 5; x++)
				if (!(y == 1 && z == 1 && x >= 1)) domain.setSolid(x, y, z, true);
			domain.sealDomainBoundary();
			domain.setVolume(2, 1, 1, 1);
			FullCapabilityTestFixture.bindFull(domain, registries, 293.15, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
			int isolated = domain.gasState.frame.commonCellNode(domain.domainFlowFields.cellIndex(1, 1, 1));
			var inventory = domain.gasState.network.inventory(isolated);
			double energy = domain.thermalState.gasNodeEnergy(isolated, domain.gasState.network);
			int water = registries.liquids().handle(BuiltinFluidMaterials.WATER.id());
			long mass = domain.unitScale.massUnitsForNormalized(1000);
			var chemical = domain.chemistry.defaultRecord(BuiltinFluidMaterials.WATER.id(), 1, .5F, mass, false);
			var ledger = mmm.chemistry.ChemicalTransferLedger.builder(registries.chemical())
					.add(BuiltinFluidMaterials.WATER.id(), false, 1, mass, chemical).build();
			boolean transferred = switch (mode) {
				case 0 -> domain.addVolumeNear(1, 1, 1, 1, 3) == 1;
				case 1 -> domain.domainInventory.tryInsertExactVolume(1, 1, 1, 1, 3, water, true).transferred();
				default -> domain.domainInventory.tryInsertExactChemical(1, 1, 1, ledger, 3, true).transferred();
			};
			require(transferred && domain.volume(1, 1, 1) == 0 && domain.totalVolumeExact() == 2,
					"External insertion failed to use reachable space around a liquid-sealed cavity: mode=" + mode
							+ ", transferred=" + transferred + ", isolatedVolume=" + domain.volume(1, 1, 1) + ", total=" + domain.totalVolumeExact());
			isolated = domain.gasState.frame.commonCellNode(domain.domainFlowFields.cellIndex(1, 1, 1));
			require(domain.gasState.network.inventory(isolated).equals(inventory)
					&& domain.gasState.network.availableVolume(isolated) == .125
					&& domain.thermalState.gasNodeEnergy(isolated, domain.gasState.network) == energy,
					"External insertion compressed or transferred the protected isolated gas inventory");
			long beforeMass = domain.totalMassUnits();
			var beforeEnergy = domain.exactInternalEnergy();
			require(!domain.domainInventory.tryInsertExactVolume(1, 1, 1, 1, 3, water, true).transferred(),
					"Insertion filled the last real support of a finite gas inventory");
			require(domain.totalVolumeExact() == 2 && domain.totalMassUnits() == beforeMass
					&& domain.exactInternalEnergy().equals(beforeEnergy),
					"Insufficient gas-compatible capacity changed the liquid source");
			var restored = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
			require(restored.totalVolumeExact() == domain.totalVolumeExact()
					&& restored.totalMassUnits() == domain.totalMassUnits()
					&& restored.exactInternalEnergy().equals(domain.exactInternalEnergy()),
					"Gas-aware external allocation changed inventory or energy after restore");
		}
	}

	private static void defaultInsertionsConserveThermalInventory() throws Exception {
		var registries = FullCapabilityTestFixture.load();
		FluidDomain valid = fullCapabilityDomain(registries);
		var water = valid.chemistry.defaultRecord(valid.materialRegistry.material(0).id(),
				.13F, valid.config.cellSize(), mmm.units.SolverUnitScale.forCellSize(valid.config.cellSize()).massUnitsForNormalized((double) .13F * 1000), false);
		var chemical = mmm.chemistry.ChemicalTransferLedger.builder(registries.chemical())
				.add(valid.materialRegistry.material(0).id(), false, .13F, valid.unitScale.massUnitsForNormalized((double) .13F * 1000), water).build();
		for (Runnable insertion : java.util.List.<Runnable>of(
				() -> require(valid.addVolumeNearBudgeted(1, 1, 1, .13F, 0).addedVolume() == .13F, "default volume insertion failed"),
				() -> require(valid.domainInventory.tryInsertExactMixture(1, 1, 1, java.util.List.of(
						new FluidDomain.MaterialPortion(1, .13F, mmm.units.SolverUnitScale.forCellSize(.5F).massUnitsForNormalized((double) .13F * 850))), 0, true).transferred(), "default material insertion failed"),
				() -> require(valid.domainInventory.tryInsertExactChemical(1, 1, 1, chemical, 0, true).transferred(), "default chemical insertion failed"))) {
			var liquidBefore=valid.liquidCells.exactInternalEnergy();long beforeQ=totalQuanta(valid);
			long sourceQ = valid.chemistry.externalSource().totalQuanta();
			var sourceU = valid.thermalState.auditLedger().externalSource();
			insertion.run();
			var addedU=valid.liquidCells.exactInternalEnergy().subtract(liquidBefore);
			for(int p=0;p<valid.liquidCells.physicalSize();p++)require(valid.thermalState.temperature(p,valid.domainCapabilities.thermalMarkerContext())>0,"Insertion stored invalid temperature");
			require(valid.thermalState.auditLedger().externalSource().subtract(sourceU).equals(addedU)
					&& valid.exactInternalEnergy().equals(valid.thermalState.auditLedger().expectedInventory()),
					"default source did not retain the exact sum of actual stored energies");
			require(totalQuanta(valid)-beforeQ == valid.chemistry.externalSource().totalQuanta() - sourceQ,
					"default insertion chemical source did not close");
		}
		FluidDomain granular = emptyMaterialDomain(4096);
		for (int z = 0; z < 2; z++) for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++)
			granular.setVolume(x, y, z, x + y + z == 0 ? Math.nextDown(1F) : 1F);
		FullCapabilityTestFixture.bindFull(granular, registries, 300, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		int vacuum = granular.gasState.frame.commonCellNode(0);
		require(vacuum > 0, "Granular allocation initialization lost its one-SVQ cavity");
		granular.setGasNodeStandardVolume(vacuum, 0);
		var source = granular.gasState.frame;
		var sand = registries.granular().material(registries.granular().materialHandle(mmm.granular.BuiltinGranularDefinitions.FINE_SAND));
		double mass = sand.referenceDensity() / mmm.granular.SolidVolume.SVQ_PER_CUBIC_METER;
		double liquidVolumeBefore = granular.totalVolumeExact();
		long liquidMassBefore = granular.exportMaterialParticleState().totalMassUnits();
		var gasInventoryBefore = granular.gasState.network.sealedInventory();
		var packet = new mmm.granular.GranularTransferLedger(java.util.List.of(
				new mmm.granular.GranularTransferLedger.Group(sand.id(), 1, mmm.units.Mass.fromKilograms(mass), null)));
		var granularAuditBefore = granular.thermalAudit();
		require(granular.tryInsertExactGranular(1, 1, 1, packet, 0, true).inserted(),
				"Granular initialization failed to prepare its joint liquid/solid successor");
		var parcel = granular.granularState.parcels.parcel(0);
		require(granular.thermalAudit().externalSource().subtract(granularAuditBefore.externalSource())
				.equals(mmm.units.SignedDyadic.of(granular.thermalState.granularColumn().activeEnergy(0))),
				"Liquid displacement overwrote or repeated the imported granular energy");
		var kernel = new mmm.granular.GranularKernel(4, 4, 4, .5F);
		kernel.capture(parcel.x(), parcel.y(), parcel.z(), 1);
		kernel.requireMask(parcel.ceilMask());
		for (int cell = 0; cell < granular.cellCount; cell++) require(
				granular.liquidGrid.exactCellVolumeInventory[cell].add(mmm.units.SignedDyadic.of(granular.granularCoupling.solidSvq(cell), -24))
						.compareTo(mmm.units.SignedDyadic.of(granular.geometry.cutCells.openCellVolume(cell))) <= 0,
				"Granular initialization exceeded joint liquid/solid capacity");
		require(granular.granularCoupling.totalSolidSvq() == 1 && granular.gasState.frame != source
				&& granular.totalVolumeExact() == liquidVolumeBefore && granular.exportMaterialParticleState().totalMassUnits() == liquidMassBefore
				&& granular.gasState.network.sealedInventory().equals(gasInventoryBefore) && granular.gasState.network.auditClosed()
				&& granular.exactInternalEnergy().equals(granular.thermalState.auditLedger().expectedInventory()),
				"Granular initialization failed to publish conservative liquid displacement and gas/thermal stock");
		var restored = FluidDomain.restoreAuthoritativeState(granular.captureAuthoritativeState());
		require(restored.granularState.parcels.parcel(0).equals(parcel)
				&& restored.exactInternalEnergy().equals(granular.exactInternalEnergy())
				&& restored.thermalAudit().equals(granular.thermalAudit())
				&& restored.exactInternalEnergy().equals(restored.thermalAudit().expectedInventory()),
				"Granular initialization lost its actual integer choice during restore");
		FluidDomain finite = fullCapabilityDomain(registries);
		var finiteSource = finite.gasState.frame;
		double oldVolume = finite.gasState.network.availableVolume(1), oldEnergy = finite.thermalState.gasNodeEnergy(1, finite.gasState.network);
		var finiteAuditBefore = finite.thermalAudit();var finiteEnergyBefore = finite.exactInternalEnergy();
		var finiteGasBefore = finite.thermalState.exactGasEnergy();
		long amount = mmm.granular.GranularLimits.MAX_PARCEL_VOLUME_SVQ;
		packet = new mmm.granular.GranularTransferLedger(java.util.List.of(
				new mmm.granular.GranularTransferLedger.Group(sand.id(), amount, mmm.units.Mass.fromKilograms(amount * mass), null)));
		require(finite.tryInsertExactGranular(1, 1, 1, packet, 0, true).inserted()
				&& finite.gasState.frame != finiteSource && finite.gasState.network.availableVolume(1) < oldVolume
				&& finite.thermalState.gasNodeEnergy(1, finite.gasState.network) > oldEnergy
				&& finite.exactInternalEnergy().equals(finite.thermalState.auditLedger().expectedInventory()),
				"Granular insertion failed to publish finite-gas compression and its audited work");
		var imported = mmm.units.SignedDyadic.of(finite.thermalState.granularColumn().activeEnergy(0));
		var gasChange = finite.thermalState.exactGasEnergy().subtract(finiteGasBefore);
		require(finite.thermalAudit().externalSource().subtract(finiteAuditBefore.externalSource()).equals(imported)
				&& finite.thermalAudit().externalSink().equals(finiteAuditBefore.externalSink())
				&& finite.thermalAudit().mechanicalWork().subtract(finiteAuditBefore.mechanicalWork())
					.add(finite.thermalAudit().roundoffResidual().subtract(finiteAuditBefore.roundoffResidual())).equals(gasChange)
				&& finite.exactInternalEnergy().subtract(finiteEnergyBefore).equals(imported.add(gasChange)),
				"Granular source energy and gas compression were not booked exactly once");
		var finiteRestored = FluidDomain.restoreAuthoritativeState(finite.captureAuthoritativeState());
		require(finiteRestored.thermalAudit().equals(finite.thermalAudit())
				&& finiteRestored.exactInternalEnergy().equals(finite.exactInternalEnergy()),
				"Restoring granular insertion changed its energy transfers");
		var extractionAuditBefore = finiteRestored.thermalAudit();
		var extractionEnergyBefore = finiteRestored.exactInternalEnergy();
		var extractionGasBefore = finiteRestored.thermalState.exactGasEnergy();
		var extraction = finiteRestored.tryExtractExactGranular(1,1,1,amount,0,true);
		require(extraction.transferred() && extraction.ledger().totalSolidVolumeSvq()==amount,
				"Restored granular stock could not be extracted");
		var exported = mmm.units.SignedDyadic.of(extraction.internalEnergy());
		gasChange = finiteRestored.thermalState.exactGasEnergy().subtract(extractionGasBefore);
		require(finiteRestored.thermalAudit().externalSource().equals(extractionAuditBefore.externalSource())
				&& finiteRestored.thermalAudit().externalSink().subtract(extractionAuditBefore.externalSink()).equals(exported)
				&& finiteRestored.exactInternalEnergy().subtract(extractionEnergyBefore).equals(gasChange.subtract(exported))
				&& finiteRestored.thermalAudit().mechanicalWork().subtract(extractionAuditBefore.mechanicalWork())
					.add(finiteRestored.thermalAudit().roundoffResidual().subtract(extractionAuditBefore.roundoffResidual())).equals(gasChange)
				&& finiteRestored.exactInternalEnergy().equals(finiteRestored.thermalAudit().expectedInventory()),
				"Granular exported energy and gas expansion were not booked exactly once");
		var extractedRestored = FluidDomain.restoreAuthoritativeState(finiteRestored.captureAuthoritativeState());
		require(extractedRestored.granularState.parcels.count()==0
				&& extractedRestored.thermalAudit().equals(finiteRestored.thermalAudit())
				&& extractedRestored.exactInternalEnergy().equals(finiteRestored.exactInternalEnergy()),
				"Restoring granular extraction changed its stock or thermal ledger");
	}

	private static void explicitInsertionsRequirePositiveTemperature() throws Exception {
		var registries=FullCapabilityTestFixture.load();var domain=emptyMaterialDomain(64);
		domain.bindChemistry(registries.chemical());domain.bindGasRegistry(registries.gas());
		var definitions=registries.thermal().materials().stream().map(material->
				material.carrierKind()!=mmm.thermal.ThermalMaterialDefinition.CarrierKind.LIQUID?material
				:new mmm.thermal.ThermalMaterialDefinition(material.id(),material.carrierKind(),material.carrierId(),
				300,0,0,List.of(new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(1,1000,1000)))).toList();
		var offsets=registries.thermal().speciesOffsets().stream().map(offset->new mmm.thermal.ThermalSpeciesOffset(
				offset.id(),offset.speciesId(),offset.hostThermalMaterialId(),mmm.chemistry.ExactRational.ZERO)).toList();
		var registry=new mmm.thermal.ThermalRegistry(definitions,offsets,List.of(),List.of(),List.of(),
				registries.chemical().fingerprint(),"",registries.gas().fingerprint());
		domain.setVolume(2,2,2,.001F);domain.bindThermal(registry,domain.materialRegistry,300);
		var pieces=List.of(new FluidDomain.MaterialPortion(0,.125,domain.unitScale.massUnitsForNormalized(125)),
				new FluidDomain.MaterialPortion(1,.125,domain.unitScale.massUnitsForNormalized(106.25)));
		var builder=mmm.chemistry.ChemicalTransferLedger.builder(registries.chemical());
		var portions=new java.util.ArrayList<mmm.thermal.ThermalStateFunction.InventoryPortion>();
		for(var piece:pieces){
			String material=domain.materialRegistry.material(piece.materialHandle()).id();
			builder.add(material,false,piece.volume(),piece.massUnits(),domain.chemistry.defaultRecord(
					material,(float)piece.volume(),domain.config.cellSize(),piece.massUnits(),false));
			portions.add(new mmm.thermal.ThermalStateFunction.InventoryPortion(
					registry.materialHandle(material),mmm.units.Mass.kilograms(piece.massUnits()),java.util.Map.of()));
		}
		var chemical=builder.build();var function=new mmm.thermal.ThermalStateFunction(registry);
		double invalid=function.inventoryEnergyAtTemperature(portions,mmm.thermal.ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K)-1000;
		for(var insertion:List.<java.util.function.BooleanSupplier>of(
				()->domain.domainInventory.tryInsertExactMixture(1,1,1,pieces,0,true,invalid).transferred(),
				()->domain.domainInventory.tryInsertExactChemical(1,1,1,chemical,0,true,invalid).transferred())){
			var before=replacementState(domain);
			require(!insertion.getAsBoolean(),"Explicit insertion accepted energy below its positive-temperature range");
			requireUnchanged(domain,before);
		}
		double requestedEnergy=function.inventoryEnergyAtTemperature(portions,100);
		for(Runnable insertion:List.<Runnable>of(
				()->require(domain.domainInventory.tryInsertExactMixture(1,1,1,pieces,0,true,requestedEnergy).transferred(),"Valid cold material insertion failed"),
				()->require(domain.domainInventory.tryInsertExactChemical(1,1,1,chemical,0,true,requestedEnergy).transferred(),"Valid cold chemical insertion failed"))){
			var beforeLiquid=domain.liquidCells.exactInternalEnergy();var before=domain.thermalState.auditLedger();var oldU=domain.exactInternalEnergy();
			var oldGasU=domain.thermalState.exactGasEnergy();insertion.run();var after=domain.thermalState.auditLedger();
			var stored=domain.liquidCells.exactInternalEnergy().subtract(beforeLiquid);
			for(int owner=0;owner<domain.liquidCells.physicalSize();owner++)
				require(domain.thermalState.temperature(owner,domain.domainCapabilities.thermalMarkerContext())>0,"Explicit insertion published invalid temperature");
			var requested=mmm.units.SignedDyadic.of(requestedEnergy);var gasDelta=domain.thermalState.exactGasEnergy().subtract(oldGasU);
			var gasRoundoff=gasDelta.subtract(after.mechanicalWork().subtract(before.mechanicalWork()));
			require(after.externalSource().subtract(before.externalSource()).equals(requested)
					&&after.roundoffResidual().subtract(before.roundoffResidual()).equals(stored.subtract(requested).add(gasRoundoff))
					&&domain.exactInternalEnergy().subtract(oldU).equals(stored.add(gasDelta))
					&&domain.exactInternalEnergy().equals(after.expectedInventory()),"Explicit source U, gas work and storage roundoff did not close");
		}
	}

	private static void restoreRequiresConnectedOpenCapacity() throws Exception {
		var config = new FluidSolverConfig(.5F,1000,0,.01F,200,1e-5F,1e-4F);
		var d = new FluidDomain(2,2,2,config); d.setVolume(1,1,1,.25F);
		byte[] masks = new byte[8]; java.util.Arrays.fill(masks,(byte)255); masks[0]=(byte)254;
		var before = d.exportParticleState(); long revision = d.domainFlowFields.carrierMutationRevision();
		try {
			d.restoreParticleState(new FlipParticleState(2,2,2,Math.nextUp(.5F),1,.125F,
					new float[]{.125F,.125F,.125F,.125F},new float[3], mmm.units.Mass.createLiquid(Math.nextUp(.5F), 1000.0, new float[]{.125F,.125F,.125F,.125F}),new byte[8]));
			throw new AssertionError("Restore silently changed physical volume and mass through another cell metric");
		} catch (IllegalArgumentException expected) {
			require(d.domainFlowFields.carrierMutationRevision()==revision && java.util.Arrays.equals(before.copyPositionVolume(),d.exportParticleState().copyPositionVolume()),
					"Metric-rejected restore changed the original stock");
		}
		try {
			new FlipParticleState(2,2,2,Float.POSITIVE_INFINITY,0,0,new float[0],new float[0], mmm.units.Mass.createLiquid(Float.POSITIVE_INFINITY, 1000.0, new float[0]),new byte[8]);
			throw new AssertionError("Particle handoff accepted an infinite cell metric");
		} catch (IllegalArgumentException expected) { }
		for (float[] points : java.util.List.of(new float[]{.125F,.125F,.125F,.25F},
				new float[]{.125F,.125F,.125F,.125F,.125F,.125F,.125F,.5F * Math.ulp(.125F)})) {
			var invalid = new FlipParticleState(2,2,2,.5F,points.length/4,points.length==4?.25F:.125F,
					points,new float[3*(points.length/4)], mmm.units.Mass.createLiquid(.5F, 1000.0, points),masks);
			try { d.restoreParticleState(invalid); throw new AssertionError("Restore accepted over-capacity connected liquid stock"); }
			catch (IllegalArgumentException expected) { }
			var after = d.exportParticleState();
			require(d.domainFlowFields.carrierMutationRevision()==revision && java.util.Arrays.equals(before.copyPositionVolume(),after.copyPositionVolume())
					&& java.util.Arrays.equals(before.copyParticleVelocities(),after.copyParticleVelocities())
					&& java.util.Arrays.equals(before.copyCollisionMicroMasks(),after.copyCollisionMicroMasks()),
					"Capacity-rejected restore changed original marker or collision state");
		}
		masks[7]=(byte)127;
		float[] separatePoints={.125F,.125F,.125F,.125F,.875F,.875F,.875F,.125F};
		d.restoreParticleState(new FlipParticleState(2,2,2,.5F,2,.25F,separatePoints,new float[6],mmm.units.Mass.createLiquid(.5F,1000,separatePoints),masks));
		require(d.totalVolumeExact()==.25,"Restore rejected separately filled spatial compartments");
		d.restoreParticleState(new FlipParticleState(2,2,2,.5F,1,1,new float[]{.125F,.125F,.125F,1},new float[3], mmm.units.Mass.createLiquid(.5F, 1000.0, new float[]{.125F,.125F,.125F,1}),new byte[8]));
		require(d.totalVolumeExact()==1,"Restore limited a connected component to one microbox");
		var floatGrid = new FluidDomain(4,4,4,new FluidSolverConfig(.1F,1000,0,.01F,200,1e-5F,1e-4F));
		byte[] floatMasks=new byte[64];java.util.Arrays.fill(floatMasks,(byte)255);floatMasks[42]=(byte)127;
		floatGrid.restoreParticleState(new FlipParticleState(4,4,4,.1F,1,.125F,new float[]{.25F,.25F,.25F,.125F},new float[3], mmm.units.Mass.createLiquid(.1F, 1000.0, new float[]{.25F,.25F,.25F,.125F}),floatMasks));
		require(floatGrid.liquidCells.physicalStockAt(0).key().supportMask()==128,"Restore changed actual binary32 micro-face ownership");

		var material = fullCapabilityDomain(FullCapabilityTestFixture.load()); material.setVolume(1,1,1,.25F);
		ReplacementState materialBefore=replacementState(material); byte[] originalMasks=material.geometry.cutCells.copyMicroMasks();
		byte[] materialMasks=originalMasks.clone();
		materialMasks[0]=(byte)254;
		var mixed = new FlipMaterialParticleState(4,4,4,.5F,2,.1875, mmm.units.SolverUnitScale.forCellSize(.5F).massUnitsForNormalized(178.125),material.materialRegistry.materials(),
				new float[]{.125F,.125F,.125F,.09375F,.125F,.125F,.125F,.09375F},new float[6],materialMasks, java.util.Arrays.stream(new double[]{93.75,84.375}).mapToLong(m -> mmm.units.SolverUnitScale.forCellSize(.5F).massUnitsForNormalized(m)).toArray(),new int[]{0,1});
		try { material.restoreMaterialParticleState(mixed); throw new AssertionError("Restore gave each material an independent component capacity"); }
		catch (IllegalArgumentException expected) { }
		requireUnchanged(material,materialBefore);
		require(java.util.Arrays.equals(material.geometry.cutCells.copyMicroMasks(),originalMasks),
				"Capacity-rejected material restore changed collision geometry");
		var builtin=mmm.chemistry.BuiltinChemicalDefinitions.REGISTRY;
		var waterOnly=new mmm.chemistry.ChemicalRegistry(builtin.species(),builtin.phases().stream()
				.filter(phase->phase.materialId().equals("mmm:water")).toList(),builtin.reactions());
		var emptyIncoming=new FlipMaterialParticleState(4,4,4,.5F,0,0, mmm.units.SolverUnitScale.forCellSize(.5F).massUnitsForNormalized(0),material.materialRegistry.materials(),
				new float[0],new float[0],new byte[64], java.util.Arrays.stream(new double[0]).mapToLong(m -> mmm.units.SolverUnitScale.forCellSize(.5F).massUnitsForNormalized(m)).toArray(),new int[0]);
		var emptyChemical=FlipChemicalParticleState.capture(emptyIncoming,waterOnly,java.util.List.of(),new byte[0],
				mmm.chemistry.ChemicalTransferLedger.EMPTY,mmm.chemistry.ChemicalTransferLedger.EMPTY);
		var emptyRestored=emptyMaterialDomain(64);emptyRestored.restoreChemicalParticleState(emptyChemical,waterOnly);
		require(emptyRestored.liquidCells.exactVolume().signum()==0 && emptyRestored.totalMassUnits()==0
				&& emptyRestored.materialRegistry.materials().equals(material.materialRegistry.materials()),
				"Empty chemical input required capabilities for unused palette materials");
		int oil=material.materialRegistry.handle(BuiltinFluidMaterials.OIL.id());
		long oilMass=material.unitScale.massUnitsForNormalized(.125*BuiltinFluidMaterials.OIL.referenceDensity());
		var oilIncoming=new FlipMaterialParticleState(4,4,4,.5F,1,.125,oilMass,material.materialRegistry.materials(),
				new float[]{.125F,.125F,.125F,.125F},new float[3],new byte[64],new long[]{oilMass},new int[]{oil});
		var incompatible=FlipChemicalParticleState.capture(oilIncoming,waterOnly,List.of(
				mmm.chemistry.ChemicalMixtureRecord.fromStableAmounts(waterOnly,java.util.Map.of(),oilMass)),new byte[1],
				mmm.chemistry.ChemicalTransferLedger.EMPTY,mmm.chemistry.ChemicalTransferLedger.EMPTY);
		try { material.restoreChemicalParticleState(incompatible,waterOnly); throw new AssertionError("Restore accepted unsupported chemical material phases"); }
		catch (IllegalArgumentException expected) { }
		requireUnchanged(material,materialBefore);
		var valid=material.exportChemicalParticleState();
		var restored=new FluidDomain(4,4,4,material.config());
		restored.restoreChemicalParticleState(valid,material.chemistry.registry());
		require(restored.totalVolumeExact()==material.totalVolumeExact() && restored.totalMass()==material.totalMass()
				&& java.util.Arrays.equals(speciesTotals(restored),valid.copySpeciesTotals())
				&& java.util.Arrays.equals(restored.exportChemicalParticleState().materials().copyPositionVolume(),valid.materials().copyPositionVolume()),
				"Prepared chemical restore changed valid carrier inventory or positions");
	}

	private static void insertionNeverRoundsTheRequestUp() {
		FluidDomain domain = emptyMaterialDomain(1024);
		for (int c = 0; c < domain.cellCount; c++) domain.setVolume(c % 4, c / 4 % 4, c / 16, (c % 13 + 1) / 17.0F);
		for (int round = 0; round < 12; round++) {
			var before=domain.liquidCells.exactVolume();
			float request = 1.3F;
			var receipt = domain.addVolumeNearBudgeted(1, 1, 1, request, 10);
			var inserted=domain.liquidCells.exactVolume().subtract(before);
			require(inserted.compareTo(mmm.units.SignedDyadic.of(request)) <= 0,
					"cross-cell float subtraction increased the requested source inventory");
			require(Math.abs(inserted.toDouble() - receipt.addedVolume()) <= 0.5 * Math.ulp(receipt.addedVolume()),
					"insertion receipt lost more than its final binary32 rounding");
		}
	}

	private static void insertionNeverRoundsCapacityUp() {
		float edge = Math.nextDown(Math.nextDown(Math.nextDown(0.375F)));
		for (float initial : new float[] {edge, Math.scalb(1.0F, -24)}) {
			FluidDomain domain = emptyMaterialDomain(64);
			domain.setVolume(1,1,1,initial);
			domain.addVolumeNearBudgeted(1, 1, 1, 1, 0);
			var actual=domain.liquidCells.exactVolume();
			require(actual.compareTo(mmm.units.SignedDyadic.of(1)) <= 0 && actual.toDouble() >= 1 - 8 * Math.ulp(1.0F),
					"operator insertion rounded a physical capacity gap upward");
		}
	}

	private static void volumeObservationRoundsTheExactInventoryOnce() {
		float[] values = new float[258]; values[0] = 1;
		java.util.Arrays.fill(values, 1, values.length, Math.scalb(1.0F, -24));
		var exact = mmm.units.SignedDyadic.ZERO;
		for (float value : values) exact = exact.add(mmm.units.SignedDyadic.of(value));
		require(exact.toDouble() == 1 + 257 * Math.scalb(1.0, -24), "wide-range volume fixture missed its accumulated small parcels");
		float[] positions = new float[values.length * 4]; double[] masses = new double[values.length];
		for (int i = 0; i < values.length; i++) {
			positions[4 * i] = positions[4 * i + 1] = positions[4 * i + 2] = i == 0 ? 0.125F : 0.625F;
			positions[4 * i + 3] = values[i]; masses[i] = 1000 * (double) values[i];
		}
		FluidDomain domain = emptyMaterialDomain(512);
        long[] storedMass = java.util.Arrays.stream(masses).mapToLong(domain.unitScale::massUnitsForNormalized).toArray();
        long totalMass = mmm.units.Mass.sum(storedMass,storedMass.length);
        domain.restoreMaterialParticleState(new FlipMaterialParticleState(4,4,4,.5F,values.length,
                exact.toDouble(),totalMass,domain.materialRegistry.materials(),positions,new float[values.length * 3],new byte[64],storedMass,new int[values.length]));
        require(domain.totalVolumeExact() == exact.toDouble() && domain.totalVolume() == (float)exact.toDouble()
                && domain.exportMaterialParticleState().totalVolume() == exact.toDouble(),
                "Material volume observation did not round the complete legal inventory once");
        require(domain.totalMass() == domain.unitScale.normalizedMass(totalMass)
                && domain.exportMaterialParticleState().totalMassUnits() == totalMass,
                "Material handoff changed the exact integer mass inventory");
	}

	private static void readOnlyCellQueriesFollowCarrierEdits(){
		var domain=emptyMaterialDomain(256);domain.setVolume(1,1,1,1);domain.setVolume(2,1,1,.5F);
		for(int round=0;round<3;round++){
			domain.domainFlowFields.preparePhysicalState();for(int face=0;face<domain.mac.velocityX.length;face++)if(domain.cellMacStage.massX[face]>0)domain.mac.velocityX[face]=round+.5F;
			domain.cellMacStage.publishMomentum();var state=domain.captureAuthoritativeState();
			var observed=domain.observeCellInventory(1,1,1);domain.volume(1,1,1);domain.densityInCell(1,1,1);
			require(state.liquid().stocks().equals(domain.liquidCells.stocks())&&java.util.Arrays.equals(state.momentum().copyX(),domain.mac.momentumX),"Read-only observation changed inventory or momentum");
			var restored=FluidDomain.restoreAuthoritativeState(state);
			require(java.util.Objects.equals(observed,restored.observeCellInventory(1,1,1)),"Restored inventory query differs from committed state");
			domain.setVolume(1,1,1,round==0?0:.75F);
			require(java.util.Objects.equals(domain.observeCellInventory(1,1,1),FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState()).observeCellInventory(1,1,1)),"Inventory query did not follow committed edits");
		}
	}

	private static FluidSolverConfig config() {
		return new FluidSolverConfig(0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F,
				120, 1.0E-4F, 2.0E-3F);
	}

	private static FluidDomain fullCapabilityDomain(FullCapabilityTestFixture.Registries registries) {
		FluidDomain domain = emptyMaterialDomain(4_096);
		FullCapabilityTestFixture.bindFull(domain, registries,
				GasRegistry.STANDARD_TEMPERATURE_K, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		return domain;
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static FluidDomain emptyMaterialDomain(int maximumParticles){
		var domain=new FluidDomain(4,4,4,config());domain.sealDomainBoundary();
		domain.materialRegistry=new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE);
		domain.bindMaterialDefinitions(BuiltinFluidMaterials.createRegistry());return domain;
	}
	private record ReplacementState(String owners,Object source,Object sink,Object thermalAudit) { }
	private static ReplacementState replacementState(FluidDomain domain){
		String state=domain.liquidCells.revision()+":"+domain.liquidCells.stocks()+":"+java.util.Arrays.toString(domain.mac.momentumX)+":"+java.util.Arrays.toString(domain.mac.momentumY)+":"+java.util.Arrays.toString(domain.mac.momentumZ)+":"+java.util.Arrays.toString(domain.geometry.cutCells.copyMicroMasks());
		if(domain.thermalState!=null)state+=":"+domain.thermalState.thermalRevision();
		return new ReplacementState(state,domain.chemistry==null?null:domain.chemistry.externalSource(),domain.chemistry==null?null:domain.chemistry.escapedSink(),domain.thermalState==null?null:domain.thermalState.auditLedger());
	}
	private static void requireUnchanged(FluidDomain domain,ReplacementState before){
		var after=replacementState(domain);require(before.equals(after),"Rejected edit changed inventory, momentum or audit");
	}
	private static long totalQuanta(FluidDomain domain){long total=0;for(var stock:domain.liquidCells.stocks())if(stock.mixture()!=null)for(int i=0;i<stock.mixture().entryCount();i++)total=Math.addExact(total,stock.mixture().amountAt(i));return total;}
	private static long[] speciesTotals(FluidDomain domain){var result=new long[domain.chemistry.registry().species().size()];for(var stock:domain.liquidCells.stocks())if(stock.mixture()!=null)for(int entry=0;entry<stock.mixture().entryCount();entry++)result[stock.mixture().speciesHandle(entry)]=Math.addExact(result[stock.mixture().speciesHandle(entry)],stock.mixture().amountAt(entry));return result;}

}
