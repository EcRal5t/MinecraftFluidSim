package mmm.fluid;

import mmm.fluid.air.GasRegistry;
import mmm.thermal.ThermalTransport;
import mmm.simulation.FullCapabilityTestFixture;
import java.util.List;

/** External inventory edits, joint capacity, source/sink accounting and complete recovery. */
public final class CarrierMutationValidation {
	private CarrierMutationValidation() { }
	public static void main(String[] args) throws Exception {
		LiquidCellInventoryValidation.main(new String[0]);
		insertExtractAndRestoreKeepQuantities();
		defaultInsertionsPreflightStoredThermalState();
		explicitInsertionsPreflightStoredThermalState();
		explicitInsertionsPreflightCompleteThermalLedger();
		restoreRequiresConnectedOpenCapacity();
		readOnlyCellQueriesFollowCarrierEdits();
		volumeObservationRoundsTheExactInventoryOnce();
		insertionNeverRoundsCapacityUp();insertionNeverRoundsTheRequestUp();
		externalInsertionsRouteAroundLiquidSealedGas();independentWorkFinishesBeforeRejection();
		System.out.println("Cell inventory mutation validation passed");
	}
	private static void insertExtractAndRestoreKeepQuantities() throws Exception {
		var domain=fullCapabilityDomain(FullCapabilityTestFixture.load());
		domain.setVolume(1,1,1,.5F);domain.setVolume(2,2,2,.25F);
		int target=domain.liquidCells.physicalStocks().stream().filter(stock->stock.key().cell()==domain.cellIndex(2,2,2)).mapToInt(stock->domain.liquidCells.ordinalOf(stock.key())).findFirst().orElseThrow();
		domain.setMarkerChemical(target,java.util.Map.of(mmm.chemistry.BuiltinChemicalDefinitions.H2O,7L),true);
		var retained=domain.liquidCells.stocksInCell(domain.cellIndex(2,2,2));
		var before=domain.liquidCells.exactVolume();long mass=domain.totalMassUnits();
		require(domain.tryExtractExactVolume(1,1,1,.125F,0,0).transferred(),"Partial cell extraction failed");
		require(before.subtract(domain.liquidCells.exactVolume()).equals(mmm.units.SignedDyadic.of(.125))&&domain.totalMassUnits()<mass,"Extraction failed to remove actual volume and integer mass");
		require(retained.equals(domain.liquidCells.stocksInCell(domain.cellIndex(2,2,2))),"Extraction edited an independent cell's composition or energy");
		require(domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),"Extraction energy audit does not close");
		var captured=domain.captureAuthoritativeState();var restored=FluidDomain.restoreAuthoritativeState(captured);
		require(captured.liquid().stocks().equals(restored.exportLiquidCellState().stocks())&&java.util.Arrays.equals(captured.momentum().copyY(),restored.exportMacMomentum().copyY()),"Mutation recovery changed cell inventory or momentum");
		domain.setVolume(1,1,1,0);domain.setVolume(2,2,2,0);
		require(domain.totalMassUnits()==0&&domain.liquidCells.exactVolume().signum()==0&&domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),"Removing final liquid retained mass or lost carried energy");
	}

	private static void externalInsertionsRouteAroundLiquidSealedGas() throws Exception {
		var registries = FullCapabilityTestFixture.load();
		for (int mode = 0; mode < 3; mode++) {
			var domain = new FluidDomain(5, 3, 3, new FluidSolverConfig(.5F, 1000, 0, .05F, 120, 1e-5F, .4F, .002F, 0), 128);
			FullCapabilityTestFixture.bindEmptyMaterialState(domain, registries.liquids());
			for (int z = 0; z < 3; z++) for (int y = 0; y < 3; y++) for (int x = 0; x < 5; x++)
				if (!(y == 1 && z == 1 && x >= 1)) domain.setSolid(x, y, z, true);
			domain.sealDomainBoundary();
			domain.setVolume(2, 1, 1, 1);
			FullCapabilityTestFixture.bindFull(domain, registries, 293.15, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
			int isolated = domain.gasState.frame.commonCellNode(domain.cellIndex(1, 1, 1));
			var inventory = domain.gasState.network.inventory(isolated);
			double energy = domain.thermalState.gasNodeEnergy(isolated, domain.gasState.network);
			int water = registries.liquids().handle(BuiltinFluidMaterials.WATER.id());
			long mass = domain.unitScale.massUnitsForNormalized(1000);
			var chemical = domain.chemicalParcels.tryDefaultRecord(BuiltinFluidMaterials.WATER.id(), 1, .5F, mass, false);
			var ledger = mmm.chemistry.ChemicalTransferLedger.builder(registries.chemical())
					.add(BuiltinFluidMaterials.WATER.id(), false, 1, mass, chemical).build();
			boolean transferred = switch (mode) {
				case 0 -> domain.addVolumeNear(1, 1, 1, 1, 3) == 1;
				case 1 -> domain.tryInsertExactVolume(1, 1, 1, 1, 3, water, true).transferred();
				default -> domain.tryInsertExactChemical(1, 1, 1, ledger, 3, true).transferred();
			};
			require(transferred && domain.volume(1, 1, 1) == 0 && domain.totalVolumeExact() == 2,
					"External insertion failed to use reachable space around a liquid-sealed cavity: mode=" + mode
							+ ", transferred=" + transferred + ", isolatedVolume=" + domain.volume(1, 1, 1) + ", total=" + domain.totalVolumeExact());
			isolated = domain.gasState.frame.commonCellNode(domain.cellIndex(1, 1, 1));
			require(domain.gasState.network.inventory(isolated).equals(inventory)
					&& domain.gasState.network.availableVolume(isolated) == .125
					&& domain.thermalState.gasNodeEnergy(isolated, domain.gasState.network) == energy,
					"External insertion compressed or transferred the protected isolated gas inventory");
			long beforeMass = domain.totalMassUnits();
			var beforeEnergy = domain.exactInternalEnergy();
			require(!domain.tryInsertExactVolume(1, 1, 1, 1, 3, water, true).transferred(),
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

	private static void defaultInsertionsPreflightStoredThermalState() throws Exception {
		var registries = FullCapabilityTestFixture.load();
		FluidDomain invalid = emptyMaterialDomain(64); invalid.setVolume(1, 1, 1, .001F);
		invalid.bindChemistry(registries.chemical()); invalid.bindGasRegistry(registries.gas());
		var definitions = registries.thermal().materials().stream().map(material ->
				material.carrierKind() != mmm.thermal.ThermalMaterialDefinition.CarrierKind.LIQUID ? material
				: new mmm.thermal.ThermalMaterialDefinition(material.id(), material.carrierKind(), material.carrierId(),
				300, 0, 0, java.util.List.of(new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(1, 1000,
						Double.MAX_VALUE / 8)))).toList();
		invalid.bindThermal(new mmm.thermal.ThermalRegistry(definitions, registries.thermal().speciesOffsets(),
				java.util.List.of(), java.util.List.of(), java.util.List.of(), registries.chemical().fingerprint(), "",
				registries.gas().fingerprint()), invalid.materialRegistry, 300);
		require(invalid.thermalState.temperature(0, invalid.thermalMarkerContext()) == 300, "initial insertion owner was invalid");
		var record = invalid.chemicalParcels.tryDefaultRecord(invalid.materialRegistry.material(0).id(),
				.125F, invalid.config.cellSize(), mmm.units.SolverUnitScale.forCellSize(invalid.config.cellSize()).massUnitsForNormalized(125), false);
		var ledger = mmm.chemistry.ChemicalTransferLedger.builder(registries.chemical())
				.add(invalid.materialRegistry.material(0).id(), false, .125, invalid.unitScale.massUnitsForNormalized(125), record).build();
		// The mixed batch's first small carrier is valid; the second carrier overflows m*cv at finite U.
		for (var insertion : java.util.List.<java.util.function.BooleanSupplier>of(
				() -> invalid.addVolumeNearBudgeted(1, 1, 1, .125F, 0).addedVolume() > 0,
				() -> invalid.tryInsertExactMixture(1, 1, 1, java.util.List.of(
						new FluidDomain.MaterialPortion(0, .001F, mmm.units.SolverUnitScale.forCellSize(.5F).massUnitsForNormalized((double) .001F * 1000)),
						new FluidDomain.MaterialPortion(1, .125, mmm.units.SolverUnitScale.forCellSize(.5F).massUnitsForNormalized(106.25))), 0, true).transferred(),
				() -> invalid.tryInsertExactChemical(1, 1, 1, ledger, 0, true).transferred())) {
			ReplacementState before = replacementState(invalid);
			require(!insertion.getAsBoolean(), "default insertion accepted a finite U with overflowing heat capacity");
			requireUnchanged(invalid, before);
		}
		FluidDomain valid = fullCapabilityDomain(registries);
		var water = valid.chemicalParcels.tryDefaultRecord(valid.materialRegistry.material(0).id(),
				.13F, valid.config.cellSize(), mmm.units.SolverUnitScale.forCellSize(valid.config.cellSize()).massUnitsForNormalized((double) .13F * 1000), false);
		var chemical = mmm.chemistry.ChemicalTransferLedger.builder(registries.chemical())
				.add(valid.materialRegistry.material(0).id(), false, .13F, valid.unitScale.massUnitsForNormalized((double) .13F * 1000), water).build();
		for (Runnable insertion : java.util.List.<Runnable>of(
				() -> require(valid.addVolumeNearBudgeted(1, 1, 1, .13F, 0).addedVolume() == .13F, "default volume insertion failed"),
				() -> require(valid.tryInsertExactMixture(1, 1, 1, java.util.List.of(
						new FluidDomain.MaterialPortion(1, .13F, mmm.units.SolverUnitScale.forCellSize(.5F).massUnitsForNormalized((double) .13F * 850))), 0, true).transferred(), "default material insertion failed"),
				() -> require(valid.tryInsertExactChemical(1, 1, 1, chemical, 0, true).transferred(), "default chemical insertion failed"))) {
			var liquidBefore=valid.liquidCells.exactInternalEnergy();long beforeQ=totalQuanta(valid);
			long sourceQ = valid.chemicalParcels.externalSource().totalQuanta();
			var sourceU = valid.thermalState.auditLedger().externalSource();
			insertion.run();
			var addedU=valid.liquidCells.exactInternalEnergy().subtract(liquidBefore);
			for(int p=0;p<valid.liquidCells.physicalSize();p++)require(valid.thermalState.temperature(p,valid.thermalMarkerContext())>0,"Insertion stored invalid temperature");
			require(valid.thermalState.auditLedger().externalSource().subtract(sourceU).equals(addedU)
					&& valid.exactInternalEnergy().equals(valid.thermalState.auditLedger().expectedInventory()),
					"default source did not retain the exact sum of actual stored energies");
			require(totalQuanta(valid)-beforeQ == valid.chemicalParcels.externalSource().totalQuanta() - sourceQ,
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
		require(granular.tryInsertExactGranular(1, 1, 1, packet, 0, true).inserted(),
				"Granular initialization failed to prepare its joint liquid/solid successor");
		var parcel = granular.granularState.parcels.parcel(0);
		var kernel = new mmm.granular.GranularKernel(4, 4, 4, .5F);
		kernel.capture(parcel.x(), parcel.y(), parcel.z(), 1);
		kernel.requireMask(parcel.ceilMask());
		for (int cell = 0; cell < granular.cellCount; cell++) require(
				granular.markerGrid.exactCellVolumeInventory[cell].add(mmm.units.SignedDyadic.of(granular.granularCoupling.solidSvq(cell), -24))
						.compareTo(mmm.units.SignedDyadic.of(granular.geometry.cutCells.openCellVolume(cell))) <= 0,
				"Granular initialization exceeded joint liquid/solid capacity");
		require(granular.granularCoupling.totalSolidSvq() == 1 && granular.gasState.frame != source
				&& granular.totalVolumeExact() == liquidVolumeBefore && granular.exportMaterialParticleState().totalMassUnits() == liquidMassBefore
				&& granular.gasState.network.sealedInventory().equals(gasInventoryBefore) && granular.gasState.network.auditClosed()
				&& granular.exactInternalEnergy().equals(granular.thermalState.auditLedger().expectedInventory()),
				"Granular initialization failed to publish conservative liquid displacement and gas/thermal stock");
		var restored = FluidDomain.restoreAuthoritativeState(granular.captureAuthoritativeState());
		require(restored.granularState.parcels.parcel(0).equals(parcel)
				&& restored.exactInternalEnergy().equals(granular.exactInternalEnergy()),
				"Granular initialization lost its actual integer choice during restore");
		FluidDomain finite = fullCapabilityDomain(registries);
		var finiteSource = finite.gasState.frame;
		double oldVolume = finite.gasState.network.availableVolume(1), oldEnergy = finite.thermalState.gasNodeEnergy(1, finite.gasState.network);
		long amount = mmm.granular.GranularLimits.MAX_PARCEL_VOLUME_SVQ;
		packet = new mmm.granular.GranularTransferLedger(java.util.List.of(
				new mmm.granular.GranularTransferLedger.Group(sand.id(), amount, mmm.units.Mass.fromKilograms(amount * mass), null)));
		require(finite.tryInsertExactGranular(1, 1, 1, packet, 0, true).inserted()
				&& finite.gasState.frame != finiteSource && finite.gasState.network.availableVolume(1) < oldVolume
				&& finite.thermalState.gasNodeEnergy(1, finite.gasState.network) > oldEnergy
				&& finite.exactInternalEnergy().equals(finite.thermalState.auditLedger().expectedInventory()),
				"Granular insertion failed to publish finite-gas compression and its audited work");
	}

	private static void explicitInsertionsPreflightStoredThermalState() throws Exception {
		var registries = FullCapabilityTestFixture.load();
		FluidDomain domain = emptyMaterialDomain(64);
		domain.bindChemistry(registries.chemical()); domain.bindGasRegistry(registries.gas());
		var definitions = registries.thermal().materials().stream().map(material ->
				material.carrierKind() != mmm.thermal.ThermalMaterialDefinition.CarrierKind.LIQUID ? material
				: new mmm.thermal.ThermalMaterialDefinition(material.id(), material.carrierKind(), material.carrierId(),
				200, material.carrierId().equals("mmm:water") ? 0x1p61 : material.carrierId().equals("mmm:oil") ? -0x1p61 : 0,
				0, java.util.List.of(new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(1, 1000, 1)))).toList();
		var offsets = registries.thermal().speciesOffsets().stream().map(offset -> new mmm.thermal.ThermalSpeciesOffset(
				offset.id(), offset.speciesId(), offset.hostThermalMaterialId(), mmm.chemistry.ExactRational.ZERO)).toList();
		var registry = new mmm.thermal.ThermalRegistry(definitions, offsets,
				java.util.List.of(), java.util.List.of(), java.util.List.of(), registries.chemical().fingerprint(), "", registries.gas().fingerprint());
		domain.bindThermal(registry, domain.materialRegistry, 300);
		domain.setVolume(2,2,2,.001F);
		require(domain.thermalState.temperature(0, domain.thermalMarkerContext()) > 0, "initial explicit-insertion owner was invalid");
		var pieces = java.util.List.of(new FluidDomain.MaterialPortion(0, .125, mmm.units.SolverUnitScale.forCellSize(.5F).massUnitsForNormalized(8)), new FluidDomain.MaterialPortion(1, .125, mmm.units.SolverUnitScale.forCellSize(.5F).massUnitsForNormalized(8)));
		var common = new mmm.thermal.ThermalStateFunction(registry).stateForInventory(java.util.List.of(
				new mmm.thermal.ThermalStateFunction.InventoryPortion(registry.materialHandle("mmm:water"), 1, java.util.Map.of()),
				new mmm.thermal.ThermalStateFunction.InventoryPortion(registry.materialHandle("mmm:oil"), 1, java.util.Map.of())), -398);
		require(common.temperatureK() == 1 && common.heatCapacityJPerK() == 2,
				"explicit fixture did not have an exact positive common-temperature request");
		var builder = mmm.chemistry.ChemicalTransferLedger.builder(registries.chemical());
		for (var piece : pieces) builder.add(domain.materialRegistry.material(piece.materialHandle()).id(), false,
				piece.volume(), piece.massUnits(), domain.chemicalParcels.defaultRecord(
						domain.materialRegistry.material(piece.materialHandle()).id(), .125F, .5F, mmm.units.SolverUnitScale.forCellSize(.5F).massUnitsForNormalized(8), false));
		var chemical = builder.build();
		// At requested T=1, water U rounds to 2^61-256: T(U)=200-256=-56 despite finite U.
		for (var insertion : java.util.List.<java.util.function.BooleanSupplier>of(
				() -> domain.tryInsertExactMixture(1, 1, 1, pieces, 0, true, -398).transferred(),
				() -> domain.tryInsertExactChemical(1, 1, 1, chemical, 0, true, -398).transferred())) {
			ReplacementState before = replacementState(domain);
			require(!insertion.getAsBoolean(), "explicit insertion accepted an invalid rounded carrier energy");
			requireUnchanged(domain, before);
		}
		// T=100 rounds both carriers back to their references, whose T(U)=200 is valid.
		for (Runnable insertion : java.util.List.<Runnable>of(
				() -> require(domain.tryInsertExactMixture(1, 1, 1, pieces, 0, true, -200).transferred(), "valid explicit material rounding failed"),
				() -> require(domain.tryInsertExactChemical(1, 1, 1, chemical, 0, true, -200).transferred(), "valid explicit chemical rounding failed"))) {
			var beforeLiquid=domain.liquidCells.exactInternalEnergy();var before=domain.thermalState.auditLedger();var oldU=domain.exactInternalEnergy();
			var oldGasU = domain.thermalState.exactGasEnergy();
			insertion.run(); var after = domain.thermalState.auditLedger();
			var stored=domain.liquidCells.exactInternalEnergy().subtract(beforeLiquid);
			for(int p=0;p<domain.liquidCells.physicalSize();p++)require(domain.thermalState.temperature(p,domain.thermalMarkerContext())>0,"Explicit insertion stored invalid temperature");
			var requested = mmm.units.SignedDyadic.of(-200);
			var gasDelta = domain.thermalState.exactGasEnergy().subtract(oldGasU);
			var gasRoundoff = gasDelta.subtract(after.mechanicalWork().subtract(before.mechanicalWork()));
			require(after.externalSource().subtract(before.externalSource()).equals(requested)
					&& after.roundoffResidual().subtract(before.roundoffResidual()).equals(stored.subtract(requested).add(gasRoundoff))
					&& domain.exactInternalEnergy().subtract(oldU).equals(stored.add(gasDelta))
					&& domain.exactInternalEnergy().equals(after.expectedInventory()), "valid explicit source/roundoff did not close actual stored energy");
		}
	}

	private static void explicitInsertionsPreflightCompleteThermalLedger() throws Exception {
		var registries = FullCapabilityTestFixture.load();
		var huge = new mmm.units.SignedDyadic(java.math.BigInteger.ONE, 40_000);
		var zero = mmm.units.SignedDyadic.ZERO;
		for (boolean expectedSumOverflow : new boolean[] {false, true}) {
			FluidDomain domain = fullCapabilityDomain(registries);
			domain.setVolume(2, 2, 2, .001F);
			var actual = domain.exactInternalEnergy();
			// Both ledgers close exactly; the second requires cancellation before a bounded final sum.
			var ledger = expectedSumOverflow
					? new mmm.thermal.ThermalAuditLedger(huge, zero, huge, actual, zero, zero)
					: new mmm.thermal.ThermalAuditLedger(zero, huge, huge, actual, zero, zero);
			require(ledger.expectedInventory().equals(actual), "restored explicit audit did not close inventory");
			domain.thermalState.restoreAuditLedger(ledger);
			var mixture = domain.chemicalParcels.defaultRecord("mmm:water", .125F, .5F, mmm.units.SolverUnitScale.forCellSize(.5F).massUnitsForNormalized(125), false);
			double energy = domain.thermalState.liquid().modeledEnergy(0, 125, mixture,
					GasRegistry.STANDARD_TEMPERATURE_K, domain.chemicalParcels.registry());
			var packet = mmm.chemistry.ChemicalTransferLedger.builder(registries.chemical())
					.add("mmm:water", false, .125, domain.unitScale.massUnitsForNormalized(125), mixture).build();
			var insertions = new java.util.ArrayList<Runnable>(java.util.List.of(
					() -> domain.tryInsertExactMixture(1, 1, 1, java.util.List.of(new FluidDomain.MaterialPortion(0, .125, mmm.units.SolverUnitScale.forCellSize(.5F).massUnitsForNormalized(125))), 0, true, energy),
					() -> domain.tryInsertExactChemical(1, 1, 1, packet, 0, true, energy)));
			if (expectedSumOverflow) insertions.addAll(java.util.List.of(
					() -> domain.tryInsertExactMixture(1, 1, 1, java.util.List.of(new FluidDomain.MaterialPortion(0, .125, mmm.units.SolverUnitScale.forCellSize(.5F).massUnitsForNormalized(125))), 0, true),
					() -> domain.tryInsertExactChemical(1, 1, 1, packet, 0, true),
					() -> domain.addVolumeNearBudgeted(1, 1, 1, .125F, 0),
					() -> domain.setVolume(3, 3, 3, .5F)));
			for (Runnable insertion : insertions) {
				if (expectedSumOverflow) {
					var count=domain.liquidCells.exactVolume();insertion.run();
					require(domain.liquidCells.exactVolume().compareTo(count)>0 && domain.thermalState.auditLedger().expectedInventory().equals(domain.exactInternalEnergy()),
							"representable final audit was rejected or failed to close actual insertion energy");
				} else {
					ReplacementState before = replacementState(domain);
					insertion.run();
					requireUnchanged(domain, before);
				}
			}
		}
		for (boolean operator : new boolean[]{false, true}) {
			FluidDomain cleared = fullCapabilityDomain(registries); cleared.setVolume(2, 2, 2, .001F);
			cleared.commitOperatorVentSealedGas(cleared.planOperatorVentSealedGas());
			var initial = cleared.exactInternalEnergy();
			cleared.thermalState.restoreAuditLedger(new mmm.thermal.ThermalAuditLedger(huge, zero, zero, huge.negate(), zero, initial));
			if (operator) cleared.commitOperatorClear(cleared.planOperatorClear());
			else cleared.setVolume(2, 2, 2, 0);
			require(cleared.particleCount() == 0 && cleared.thermalState.auditLedger().expectedInventory().equals(cleared.exactInternalEnergy()),
					"clear rejected representable final cancellation or lost the exact sink energy");
		}
	}

	private static void restoreRequiresConnectedOpenCapacity() throws Exception {
		var config = new FluidSolverConfig(.5F,1000,0,.01F,200,1e-5F,.5F,1e-4F,0);
		var d = new FluidDomain(2,2,2,config,16); d.setVolume(1,1,1,.25F);
		byte[] masks = new byte[8]; java.util.Arrays.fill(masks,(byte)255); masks[0]=126;
		var before = d.exportParticleState(); long revision = d.carrierMutationRevision();
		try {
			d.restoreParticleState(new FlipParticleState(2,2,2,Math.nextUp(.5F),1,.125F,
					new float[]{.125F,.125F,.125F,.125F},new float[3], mmm.units.Mass.createLiquid(Math.nextUp(.5F), 1000.0, new float[]{.125F,.125F,.125F,.125F}),new byte[8]));
			throw new AssertionError("Restore silently changed physical volume and mass through another cell metric");
		} catch (IllegalArgumentException expected) {
			require(d.carrierMutationRevision()==revision && java.util.Arrays.equals(before.copyPositionVolume(),d.exportParticleState().copyPositionVolume()),
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
			require(d.carrierMutationRevision()==revision && java.util.Arrays.equals(before.copyPositionVolume(),after.copyPositionVolume())
					&& java.util.Arrays.equals(before.copyParticleVelocities(),after.copyParticleVelocities())
					&& java.util.Arrays.equals(before.copyCollisionMicroMasks(),after.copyCollisionMicroMasks()),
					"Capacity-rejected restore changed original marker or collision state");
		}
		d.restoreParticleState(new FlipParticleState(2,2,2,.5F,2,.25F,
				new float[]{.125F,.125F,.125F,.125F,.375F,.375F,.375F,.125F},new float[6], mmm.units.Mass.createLiquid(.5F, 1000.0, new float[]{.125F,.125F,.125F,.125F,.375F,.375F,.375F,.125F}),masks));
		require(d.totalVolumeExact()==.25,"Restore rejected legitimate separately filled micro components");
		d.restoreParticleState(new FlipParticleState(2,2,2,.5F,1,1,new float[]{.125F,.125F,.125F,1},new float[3], mmm.units.Mass.createLiquid(.5F, 1000.0, new float[]{.125F,.125F,.125F,1}),new byte[8]));
		require(d.totalVolumeExact()==1,"Restore limited a connected component to one microbox");
		var floatGrid = new FluidDomain(4,4,4,new FluidSolverConfig(.1F,1000,0,.01F,200,1e-5F,.5F,1e-4F,0),16);
		byte[] floatMasks=new byte[64];java.util.Arrays.fill(floatMasks,(byte)255);floatMasks[42]=126;
		floatGrid.restoreParticleState(new FlipParticleState(4,4,4,.1F,1,.125F,new float[]{.25F,.25F,.25F,.125F},new float[3], mmm.units.Mass.createLiquid(.1F, 1000.0, new float[]{.25F,.25F,.25F,.125F}),floatMasks));
		require(floatGrid.liquidCells.physicalStockAt(0).key().supportMask()==128,"Restore changed actual binary32 micro-face ownership");

		var material = fullCapabilityDomain(FullCapabilityTestFixture.load()); material.setVolume(1,1,1,.25F);
		ReplacementState materialBefore=replacementState(material); byte[] originalMasks=material.geometry.cutCells.copyMicroMasks();
		byte[] materialMasks=originalMasks.clone();
		materialMasks[0]=126;
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
		var incompatible=FlipChemicalParticleState.capture(emptyIncoming,waterOnly,java.util.List.of(),new byte[0],
				mmm.chemistry.ChemicalTransferLedger.EMPTY,mmm.chemistry.ChemicalTransferLedger.EMPTY);
		try { material.restoreChemicalParticleState(incompatible,waterOnly); throw new AssertionError("Restore accepted unsupported chemical material phases"); }
		catch (IllegalArgumentException expected) { }
		requireUnchanged(material,materialBefore);
		var valid=material.exportChemicalParticleState();
		var restored=new FluidDomain(4,4,4,material.config(),material.maximumParticleCount());
		restored.restoreChemicalParticleState(valid,material.chemicalParcels.registry());
		require(restored.totalVolumeExact()==material.totalVolumeExact() && restored.totalMass()==material.totalMass()
				&& java.util.Arrays.equals(speciesTotals(restored),valid.copySpeciesTotals())
				&& java.util.Arrays.equals(restored.exportChemicalParticleState().materials().copyPositionVolume(),valid.materials().copyPositionVolume()),
				"Prepared chemical restore changed valid carrier inventory or positions");
	}

	private static void independentWorkFinishesBeforeRejection() {
		int[] visits = new int[8_192];
		var first = new IllegalStateException("first batch rejection");
		var last = new AssertionError("last batch rejection");
		try {
			mmm.compute.CpuWorkBatch.run(visits.length, (from, until) -> {
				for (int index = from; index < until; index++) {
					if (index == 0) throw first;
					if (index == visits.length - 1) throw last;
					visits[index]++;
				}
			});
			throw new AssertionError("worker rejection was lost");
		} catch (IllegalStateException rejected) {
			require(rejected == first, "worker rejection changed identity or canonical batch order");
			if (Runtime.getRuntime().availableProcessors() > 3)
				require(visits[visits.length - 2] == 1, "rejection returned while another batch was unfinished");
			if (Runtime.getRuntime().availableProcessors() > 5)
				require(visits[visits.length / 2 - 1] == 1, "rejection returned before an independent batch finished");
		}
		java.util.Arrays.fill(visits, 0);
		mmm.compute.CpuWorkBatch.run(visits.length, (from, until) -> { for (int index = from; index < until; index++) { visits[index]++; } });
		for (int visit : visits) require(visit == 1, "independent work missed or repeated an index after rejection");
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
			domain.ensureGrid();for(int face=0;face<domain.mac.velocityX.length;face++)if(domain.cellMacStage.massX[face]>0)domain.mac.velocityX[face]=round+.5F;
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
				120, 1.0E-4F, 0.40F, 2.0E-3F, 0.80F);
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
		var domain=new FluidDomain(4,4,4,config(),maximumParticles);domain.sealDomainBoundary();
		domain.materialRegistry=new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE);
		domain.bindMaterialDefinitions(BuiltinFluidMaterials.createRegistry());return domain;
	}
	private record ReplacementState(String owners,Object source,Object sink,Object thermalAudit) { }
	private static ReplacementState replacementState(FluidDomain domain){
		String state=domain.liquidCells.revision()+":"+domain.liquidCells.stocks()+":"+java.util.Arrays.toString(domain.mac.momentumX)+":"+java.util.Arrays.toString(domain.mac.momentumY)+":"+java.util.Arrays.toString(domain.mac.momentumZ)+":"+java.util.Arrays.toString(domain.geometry.cutCells.copyMicroMasks());
		if(domain.thermalState!=null)state+=":"+domain.thermalState.thermalRevision();
		return new ReplacementState(state,domain.chemicalParcels==null?null:domain.chemicalParcels.externalSource(),domain.chemicalParcels==null?null:domain.chemicalParcels.escapedSink(),domain.thermalState==null?null:domain.thermalState.auditLedger());
	}
	private static void requireUnchanged(FluidDomain domain,ReplacementState before){
		var after=replacementState(domain);require(before.equals(after),"Rejected edit changed inventory, momentum or audit");
	}
	private static long totalQuanta(FluidDomain domain){long total=0;for(var stock:domain.liquidCells.stocks())if(stock.mixture()!=null)for(int i=0;i<stock.mixture().entryCount();i++)total=Math.addExact(total,stock.mixture().amountAt(i));return total;}
	private static long[] speciesTotals(FluidDomain domain){var result=new long[domain.chemicalParcels.registry().species().size()];for(var stock:domain.liquidCells.stocks())if(stock.mixture()!=null)for(int entry=0;entry<stock.mixture().entryCount();entry++)result[stock.mixture().speciesHandle(entry)]=Math.addExact(result[stock.mixture().speciesHandle(entry)],stock.mixture().amountAt(entry));return result;}

}
