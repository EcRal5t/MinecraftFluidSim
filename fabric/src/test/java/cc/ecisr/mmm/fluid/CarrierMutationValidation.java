package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.fluid.air.GasRegistry;
import cc.ecisr.mmm.fluid.air.GasInventory;
import cc.ecisr.mmm.fluid.air.GasComponentAmount;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;
import cc.ecisr.mmm.thermal.ThermalTransport;
import cc.ecisr.mmm.units.SignedDyadic;
import cc.ecisr.mmm.simulation.FullCapabilityTestFixture;

/**
 * Scenario coverage for the carrier mutation coordinator: append, swap-remove and wholesale
 * count transitions across the main column and the chemistry/thermal side columns, including
 * capacity-edge and stale-plan rejection leaving every column and the revision untouched.
 */
public final class CarrierMutationValidation {
	private CarrierMutationValidation() { }

	public static void main(String[] args) throws Exception {
		appendThenRemoveLastClosesLedgers();
		removeMiddleSwapsAllColumnsBitwise();
		sideColumnsSwapWithTheMainColumn();
		capacityEdgeRejectsBeforeAnyWrite();
		stalePlansRejectWithoutStateChange();
		wholesaleCountBindsRevisionAndBudget();
		chemicalRemapPublishesTheFinalBudget();
		readOnlyCellQueriesFollowCarrierEdits();
		volumeObservationRoundsTheExactInventoryOnce();
		nativeExtensiveQuantitiesSurviveHandoff();
		insertionNeverRoundsCapacityUp();
		insertionNeverRoundsTheRequestUp();
		independentWorkFinishesBeforeRejection();
		nativeSourceBatchMovesGeometryAndSideColumns();
		nativeExtractionMovesGeometryAndSideColumns();
		nativeGasMotionCarriesEnergy();
		nativeGasMergeRoundsCompleteMassOnce();
		nativePhaseReconstructionKeepsEnergy();
		nativeThermalObservationUsesOccupiedGeometry();
		nativeConductionPublishesCaloricInventory();
		nativeLocalInventoryCarriesMaterialAndEnergy();
		nativeLocalInventoryUsesMechanicalBackend();
		nativeMechanicalRegridCarriesChemicalInventory();
		nativePhysicalPhaseReconstructionCarriesInventories();
		nativePhysicalReconstructionPreservesGasOnlyState();
		System.out.println("Carrier mutation validation passed");
	}
	private static void nativeExtensiveQuantitiesSurviveHandoff() throws Exception {
		var midpoint = SignedDyadic.of(1).add(SignedDyadic.of(1, -24)).add(SignedDyadic.of(1, -2000));
		var single = new LiquidInventoryState(new SignedDyadic[]{midpoint}, new SignedDyadic[]{SignedDyadic.of(1000)});
		var observed = new FlipMaterialParticleState(2, 2, 2, 0.5F, 1, single.totalVolume().toDouble(), 1000,
				java.util.List.of(BuiltinFluidMaterials.WATER), new float[]{0.25F, 0.25F, 0.25F, midpoint.toFloat()}, new float[3],
				new byte[8], new double[]{1000}, new int[1], null, single);
		require(observed.particles().totalVolume() == Math.nextUp(1.0F), "handoff rounded its extensive volume through binary64");
		var v = SignedDyadic.of(0.25).add(SignedDyadic.of(1, -81));
		var m = SignedDyadic.of(250).add(SignedDyadic.of(1, -101));
		var columns = new SignedDyadic[]{v, v}; var quantities = new LiquidInventoryState(columns, new SignedDyadic[]{m, m});
		columns[0] = SignedDyadic.of(1);
		require(quantities.volume(0).equals(v), "extensive inventory retained mutable source columns");
		var extent = Fraction.of(v.add(v)).divide(Fraction.of(8));
		var occupied = LiquidPolyhedron.box(0, 0, 0, 1, 1, 1).clip(LiquidGeometryPlane.exact(java.util.List.of(
				extent.denominator(), java.math.BigInteger.ZERO, java.math.BigInteger.ZERO, extent.numerator())));
		var geometry = LiquidGeometryStateMapper.capture(java.util.List.of(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, occupied)), 1, new int[]{0, 0},
				new int[]{0}, new double[]{1, 0, 0});
		var positions = new float[]{(float) extent.divide(Fraction.of(2)).rounded(), 0.5F, 0.5F, v.toFloat(),
				(float) extent.divide(Fraction.of(2)).rounded(), 0.5F, 0.5F, v.toFloat()};
		var state = new FlipMaterialParticleState(2, 2, 2, 0.5F, 2, quantities.totalVolume().toDouble(), quantities.totalMass().toDouble(),
				java.util.List.of(BuiltinFluidMaterials.WATER), positions, new float[]{1, 0, 0, 1, 0, 0}, new byte[8],
				new double[]{m.toDouble(), m.toDouble()}, new int[2], geometry, quantities);
		var domain = new FluidDomain(2, 2, 2, new FluidSolverConfig(0.5F, 1000, 0, 0.05F, 100, 1e-7F, 0.4F, 0.002F, 0), 64); domain.restoreMaterialParticleState(state);
		domain.ensureParticleCapacity(32);
		var successor = new LiquidGeometryState(geometry.rootWidthMetres(), geometry.cells(), geometry.copyMarkerCells(), geometry.copyFragmentOwners(), geometry.copyControlCells());
		LiquidGeometryPublication.publish(domain, geometry, successor, java.util.List.of(new LiquidGeometryMotionPlan.InventoryMotion(0,
				new LiquidPolyhedron.Point(positions[0], positions[1], positions[2]), new LiquidPolyhedron.Point(1, 0, 0))));
		require(domain.liquidGeometryState == successor && domain.particles.exactVolume(0).equals(v) && domain.particles.exactMass(1).equals(m),
				"geometric observation publication rounded independent extensive quantities");
		var restored = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
		var actual = restored.exportMaterialParticleState().inventory();
		require(actual.totalVolume().equals(quantities.totalVolume()) && actual.totalMass().equals(quantities.totalMass())
				&& actual.volume(0).equals(v) && actual.mass(1).equals(m), "exact volume or mass remainder was lost across growth and cold handoff");
		var beforeVolume = domain.particles.inventory().totalVolume(); var beforeMass = domain.particles.inventory().totalMass();
		var transfer = domain.tryExtractExactVolume(0, 1, 1, 0.125F, 0);
		require(transfer.transferred() && domain.particles.exactVolume(0).equals(v) && domain.particles.exactMass(0).equals(m)
				&& domain.particles.exactVolume(1).equals(v.subtract(SignedDyadic.of(transfer.volume())))
				&& domain.particles.exactMass(1).equals(m.subtract(SignedDyadic.of(transfer.mass())))
				&& domain.particles.inventory().totalVolume().add(SignedDyadic.of(transfer.volume())).equals(beforeVolume)
				&& domain.particles.inventory().totalMass().add(SignedDyadic.of(transfer.mass())).equals(beforeMass),
				"partial extraction rounded an edited or untouched native quantity");
		LiquidGeometryStateMapper.requireCompatible(domain.liquidGeometryState, domain);
		beforeVolume = domain.particles.inventory().totalVolume(); beforeMass = domain.particles.inventory().totalMass();
		float removed = domain.removeVolumeNear(0, 0, 0, 0.0625F, 0);
		require(removed == 0.0625F && domain.particles.inventory().totalVolume().add(SignedDyadic.of(removed)).equals(beforeVolume)
				&& domain.particles.inventory().totalMass().add(SignedDyadic.of(62.5)).equals(beforeMass),
				"ordinary partial removal rounded native volume or mass precision remainders");
		LiquidGeometryStateMapper.requireCompatible(domain.liquidGeometryState, domain);
		var retainedGeometry = domain.liquidGeometryState; var retainedInventory = domain.particles.inventory();
		require(!domain.tryExtractExactVolume(0, 0, 0, 0.3125F, 2).transferred() && domain.liquidGeometryState == retainedGeometry
				&& domain.particles.inventory().totalVolume().equals(retainedInventory.totalVolume())
				&& domain.particles.inventory().totalMass().equals(retainedInventory.totalMass()),
				"unrepresentable complete extraction modified native precision remainders");
		FullCapabilityTestFixture.bindFull(restored, FullCapabilityTestFixture.load(), 320, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		var initialSpecies = restored.totalChemicalSpeciesQuanta(); var initialEnergy = restored.exactInternalEnergy();
		var roundoff = restored.thermalAudit().roundoffResidual();
		var chemical = restored.tryExtractExactChemical(0, 1, 1, 0.125F, 0);
		require(chemical.transferred() && restored.particles.inventory().totalVolume().add(SignedDyadic.of(chemical.ledger().totalVolume())).equals(quantities.totalVolume())
				&& restored.particles.inventory().totalMass().add(SignedDyadic.of(125)).equals(quantities.totalMass())
				&& restored.totalChemicalSpeciesQuanta() + chemical.ledger().totalQuanta() == initialSpecies
				&& restored.exactInternalEnergy().subtract(initialEnergy).add(SignedDyadic.of(chemical.internalEnergy()))
						.equals(restored.thermalAudit().roundoffResidual().subtract(roundoff)),
				"native chemical extraction lost quantities, species or thermal settlement");
		LiquidGeometryStateMapper.requireCompatible(restored.liquidGeometryState, restored);
		var clear = restored.planRemoveAllWaterExact(); var condensedClear = restored.planRemoveAllCondensedExact();
		var clearVolume = restored.particles.inventory().totalVolume(); var clearMass = restored.particles.inventory().totalMass();
		var sourceLedger = restored.chemicalLedgerForMarkerRange(0, restored.particles.count());
		require(sourceLedger.volumeQuantity().equals(clearVolume) && clear.drained().volumeQuantity().equals(clearVolume)
				&& sourceLedger.massKilogramsQuantity().equals(restored.unitScale.kilograms(clearMass))
				&& clear.drained().massKilogramsQuantity().equals(sourceLedger.massKilogramsQuantity()), "clear audit omitted native quantity tails");
		var beforeSink = restored.chemicalParcels().escapedSink(); var beforeThermalSink = restored.thermalAudit().externalSink();
		var sourceMass = restored.particles.exactMass(0); var sourceVolume = restored.particles.exactVolume(0);
		restored.particles.setQuantities(0, sourceVolume, sourceMass.add(SignedDyadic.of(1, -102)));
		try { restored.commitRemoveAllWaterExact(clear); throw new AssertionError("liquid clear accepted a hidden exact quantity change"); }
		catch (IllegalStateException expected) { }
		try { restored.commitRemoveAllCondensedExact(condensedClear); throw new AssertionError("condensed clear accepted a hidden exact quantity change"); }
		catch (IllegalStateException expected) { }
		require(restored.particles.count() == 2 && restored.chemicalParcels().escapedSink() == beforeSink
				&& restored.thermalAudit().externalSink().equals(beforeThermalSink), "stale clear changed inventory or audit ledgers");
		restored.particles.setQuantities(0, sourceVolume, sourceMass);
		var clearing = FluidDomain.restoreAuthoritativeState(restored.captureAuthoritativeState());
		var drainedEnergy = clearing.planRemoveAllWaterExact().internalEnergy(); var clearEnergy = clearing.exactInternalEnergy();
		clearing.removeAllWaterExact();
		var actualSink = clearing.chemicalParcels().escapedSink();
		require(clearing.particles.count() == 0 && actualSink.volumeQuantity().subtract(beforeSink.volumeQuantity()).equals(clearVolume)
				&& actualSink.massKilogramsQuantity().subtract(beforeSink.massKilogramsQuantity()).equals(restored.unitScale.kilograms(clearMass))
				&& actualSink.totalQuanta() - beforeSink.totalQuanta() == restored.totalChemicalSpeciesQuanta()
				&& clearing.exactInternalEnergy().add(drainedEnergy).equals(clearEnergy), "complete clear lost quantities, species or energy");
		var clearedRestore = FluidDomain.restoreAuthoritativeState(clearing.captureAuthoritativeState());
		require(clearedRestore.particles.count() == 0 && clearedRestore.chemicalParcels().escapedSink().groups().equals(actualSink.groups())
				&& clearedRestore.exactInternalEnergy().equals(clearing.exactInternalEnergy()), "native clear lost its complete cold checkpoint");
		var savedAudit = cc.ecisr.mmm.chemistry.ChemicalAuditLedgerCodec.decode(cc.ecisr.mmm.chemistry.ChemicalAuditLedgerCodec.encode(
				clearing.chemicalParcels().externalSource(), actualSink));
		require(savedAudit.escapedSink().groups().equals(actualSink.groups()), "complete clear quantities were lost in the audit checkpoint");
		var captured = new LiquidReconstructionInventories(restored);
		restored.particles.setQuantities(0, v, m.add(SignedDyadic.of(1, -102)));
		try { captured.requireSource(restored); throw new AssertionError("reconstruction accepted changed exact mass hidden by the same observation"); }
		catch (IllegalStateException expected) { }
		try { new FlipMaterialParticleState(2, 2, 2, 0.5F, 2, quantities.totalVolume().toDouble(), quantities.totalMass().toDouble(),
				java.util.List.of(BuiltinFluidMaterials.WATER), positions, new float[]{1, 0, 0, 1, 0, 0}, new byte[8],
				new double[]{Math.nextUp(m.toDouble()), m.toDouble()}, new int[2], geometry, quantities);
			throw new AssertionError("mismatched extensive mass observation was accepted");
		} catch (IllegalArgumentException expected) { }
		var store = new LiquidParticleStore(64); store.setQuantities(0, v, m); store.setQuantities(1, v.add(v), m.add(m)); store.setCount(2);
		store.ensureCapacity(32); store.swapRemove(0);
		require(store.count() == 1 && store.exactVolume(0).equals(v.add(v)) && store.exactMass(0).equals(m.add(m)), "swap-remove separated exact quantity columns");
	}
	private static void nativePhysicalReconstructionPreservesGasOnlyState() throws Exception {
		var registries = FullCapabilityTestFixture.load();
		var domain = new FluidDomain(2, 2, 2, new FluidSolverConfig(0.5F, 1000, 0, 0.05F, 100, 1e-7F, 0.4F, 0.002F, 0), 16);
		FullCapabilityTestFixture.bindEmptyMaterialState(domain, registries.liquids());
		domain.liquidGeometryState = new LiquidGeometryState(1, java.util.List.of(), new int[0]); domain.sealDomainBoundary();
		FullCapabilityTestFixture.bindFull(domain, registries, GasRegistry.STANDARD_TEMPERATURE_K, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		var gas = domain.exportGasState(registries.chemical().fingerprint()); var energy = domain.exactInternalEnergy();
		var plan = new LiquidGeometryReconstructionPlan(domain, java.util.List.of(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 1, 1, 1))),
				new LiquidMomentReconstruction.Tolerance(0.01, 0.03, 40), 0, new LiquidMomentReconstruction.Budget(1, 32, 4096));
		new ResolvedGasMotionPlan(domain, plan, 4096, 65536).publish(domain, plan);
		require(domain.particles.count() == 0 && domain.liquidGeometryState.cells().isEmpty() && domain.totalMass() == 0 && domain.totalVolumeExact() == 0
				&& domain.exactInternalEnergy().equals(energy) && domain.thermalAudit().expectedInventory().equals(energy)
				&& domain.exportGasState(registries.chemical().fingerprint()).nodes().equals(gas.nodes()), "gas-only physical regrid changed its extensive state");
		var restored = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
		require(restored.particles.count() == 0 && restored.exactInternalEnergy().equals(energy), "gas-only physical regrid lost its cold restored state");
	}
	private static void nativePhysicalPhaseReconstructionCarriesInventories() throws Exception {
		nativePhysicalPhaseReconstructionCarriesInventories("mmm:water", 1, -375);
		nativePhysicalPhaseReconstructionCarriesInventories("mmm:oil", 2, 0);
	}
	private static void nativePhysicalPhaseReconstructionCarriesInventories(String secondMaterial, int expectedControls, double expectedKineticChange) throws Exception {
		var registries = FullCapabilityTestFixture.load();
		int material = registries.liquids().handle(secondMaterial);
		var domain = new FluidDomain(2, 2, 2, new FluidSolverConfig(0.5F, 1000, 0, 0.05F, 100, 1e-7F, 0.4F, 0.002F, 0), 16);
		var cells = java.util.List.of(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 0.5, 1, 0.5)),
				new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0.5, 0, 0, 1, 1, 0.5)));
		var original = LiquidGeometryStateMapper.capture(cells, 1, new int[]{0, 1}, new int[]{0, 1}, new double[]{1, 0, 0, -1, 0, 0});
		domain.restoreMaterialParticleState(new FlipMaterialParticleState(2, 2, 2, 0.5F, 2, 4, 8000, registries.liquids().materials(),
				new float[]{0.25F, 0.5F, 0.25F, 2, 0.75F, 0.5F, 0.25F, 2}, new float[]{1, 0, 0, -1, 0, 0}, new byte[8],
				new double[]{2000, 6000}, new int[]{0, material}, original));
		domain.sealDomainBoundary();
		FullCapabilityTestFixture.bindFull(domain, registries, GasRegistry.STANDARD_TEMPERATURE_K, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		domain.thermalState.resetMarkerTemperature(0, 270, domain.thermalMarkers, domain.exactInternalEnergy());
		domain.thermalState.resetMarkerTemperature(1, 350, domain.thermalMarkers, domain.exactInternalEnergy());
		var sourceEnergy = domain.exactInternalEnergy(); var audit = domain.thermalAudit(); var species = domain.chemicalLedgerForMarkerRange(0, 2);
		var gas = domain.exportGasState(registries.chemical().fingerprint()); var kinetic = Fraction.of(domain.particleKineticEnergyJoules());
		var open = java.util.List.of(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 1, 1, 1)));
		var plan = new LiquidGeometryReconstructionPlan(domain, open, new LiquidMomentReconstruction.Tolerance(0.01, 0.03, 40), 1,
				new LiquidMomentReconstruction.Budget(1, 32, 4096));
		require(plan.leaves.size() == 1 && java.util.Arrays.stream(plan.geometry.copyControlCells()).distinct().count() == expectedControls
				&& plan.inventory.markers.size() == expectedControls && plan.inventory.kineticChange.equals(Fraction.of(expectedKineticChange)),
				"physical reconstruction retained an inventory interface or lost its mass-weighted projection defect");
		var gasPlan = new ResolvedGasMotionPlan(domain, plan, 4096, 65536); gasPlan.publish(domain, plan);
		require(domain.totalVolumeExact() == 4 && domain.totalMass() == 8000
				&& Fraction.of(domain.particleKineticEnergyJoules()).subtract(kinetic).equals(plan.inventory.kineticChange)
				&& species.groups().getFirst().speciesQuanta().equals(domain.chemicalLedgerForMarkerRange(0, domain.particles.count()).groups().getFirst().speciesQuanta()),
				"physical phase regrid changed mass, species, momentum or its measured kinetic change");
		require(domain.exactInternalEnergy().subtract(sourceEnergy).equals(plan.inventory.energyRoundoff)
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory())
				&& audit.externalSource().equals(domain.thermalAudit().externalSource()) && audit.externalSink().equals(domain.thermalAudit().externalSink())
				&& gas.nodes().stream().map(cc.ecisr.mmm.fluid.air.GasStateSnapshot.Node::inventory).toList()
						.equals(domain.exportGasState(registries.chemical().fingerprint()).nodes().stream().map(cc.ecisr.mmm.fluid.air.GasStateSnapshot.Node::inventory).toList()),
				"physical phase regrid changed caloric or gas inventory accounting");
		for (int p = 0; p < domain.particles.count(); p++) {
			boolean water = domain.particles.materialHandle(p) == 0;
			float velocity = expectedControls == 1 ? -0.5F : water ? 1 : -1;
			double temperature = expectedControls == 1 ? 330 : water ? 270 : 350;
			require(domain.particles.velocityX(p) == velocity && domain.particles.mass(p) == (expectedControls == 1 ? 8000 : water ? 2000 : 6000)
					&& domain.particles.materialHandle(p) == (water ? 0 : material)
					&& Math.abs(domain.thermalState.temperature(p, domain.thermalMarkers) - temperature) <= 8 * Math.ulp(temperature),
					"physical phase regrid lost the transported material, mass, velocity or temperature");
		}
		LiquidGeometryStateMapper.requireCompatible(domain.liquidGeometryState, domain);
		var restored = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
		require(restored.exactInternalEnergy().equals(domain.exactInternalEnergy()) && restored.totalChemicalSpeciesQuanta() == domain.totalChemicalSpeciesQuanta()
				&& restored.particleKineticEnergyJoules() == domain.particleKineticEnergyJoules(), "physical regrid lost its cold restored extensive state");
		var stale = new LiquidGeometryReconstructionPlan(domain, open, new LiquidMomentReconstruction.Tolerance(0.01, 0.03, 40), 1,
				new LiquidMomentReconstruction.Budget(1, 32, 4096));
		var obsolete = new ResolvedGasMotionPlan(domain, stale, 4096, 65536); var unchanged = domain.liquidGeometryState; var energy = domain.exactInternalEnergy();
		domain.chemicalParcels.assign(0, domain.chemicalParcels.record(0), !domain.chemicalParcels.indicator(0));
		try { obsolete.publish(domain, stale); throw new AssertionError("physical regrid accepted revised species input"); }
		catch (IllegalStateException expected) { require(domain.liquidGeometryState == unchanged && domain.particles.count() == expectedControls && domain.exactInternalEnergy().equals(energy),
				"obsolete scalar regrid partially published liquid or caloric state"); }
	}
	private static void nativeLocalInventoryUsesMechanicalBackend() throws Exception {
		var domain = localInventoryFixture(64); var energy = domain.exactInternalEnergy(); var ledger = domain.thermalAudit();
		require(domain.gasBoundary.nodeGaugePressurePa(0) == 0, "ambient pressure requested a finite gas energy carrier");
		long species = domain.totalChemicalSpeciesQuanta(); var geometry = domain.liquidGeometryState;
		var candidate = new CpuFluidSolverBackend().evaluate(FluidStepRequest.pendingPhaseSettlement(0, domain.captureAuthoritativeState()));
		var result = candidate.claimPreparedCpuDomain();
		require(result.totalVolumeExact() == 4 && result.totalMass() == 4200 && result.totalChemicalSpeciesQuanta() == species
				&& candidate.stats().geometry().inventory().repartitions() == 1 && result.cellLiquidVolumeTransport == null,
				"mechanical backend lost the independent geometric inventories");
		require(result.exactInternalEnergy().equals(result.thermalAudit().expectedInventory()) && ledger.externalSource().equals(result.thermalAudit().externalSource())
				&& ledger.externalSink().equals(result.thermalAudit().externalSink()) && domain.exactInternalEnergy().equals(energy) && domain.liquidGeometryState == geometry,
				"mechanical local inventory candidate changed external energy or its committed input");
		LiquidGeometryStateMapper.requireCompatible(result.liquidGeometryState, result);
	}
	private static void nativeMechanicalRegridCarriesChemicalInventory() throws Exception {
		var registries = FullCapabilityTestFixture.load();
		var liquids = new FluidMaterialRegistry(registries.liquids().materials().stream().map(material -> material.id().equals("mmm:water")
				? FluidMaterial.withSurfaceTension(material.id(), material.referenceDensity(), material.miscibilityGroup(), 0.125, 60) : material).toList());
		var definitions = new MaterialRegistry(registries.materials().entries().stream().map(entry -> entry.material().id().equals("mmm:water")
				? new MaterialRegistry.MaterialEntry(liquids.material(0), entry.viscosity()) : entry).toList(), registries.materials().pairTensions());
		registries = new FullCapabilityTestFixture.Registries(liquids, registries.chemical(), registries.granular(), registries.gas(), registries.thermal(), definitions);
		var cells = LiquidGeometryRefinement.children(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0.25, 0, 0.25, 0.75, 0.625, 0.75)));
		var geometry = LiquidGeometryStateMapper.capture(cells, 1, new int[]{0}, new int[cells.size()]);
		var domain = new FluidDomain(2, 2, 2, new FluidSolverConfig(0.5F, 1000, 0, 1.0F / 128, 512, 1e-7F, 0.45F, 0.002F, 0.8F));
		domain.restoreMaterialParticleState(new FlipMaterialParticleState(2, 2, 2, 0.5F, 1, 1.25, 1250, liquids.materials(),
				new float[]{0.5F, 0.3125F, 0.5F, 1.25F}, new float[3], new byte[8], new double[]{1250}, new int[]{0}, geometry));
		domain.sealDomainBoundary();
		FullCapabilityTestFixture.bindFull(domain, registries, GasRegistry.STANDARD_TEMPERATURE_K, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		var initial = domain.particles.inventory(); var species = domain.chemicalLedgerForMarkerRange(0, 1).groups().getFirst().speciesQuanta();
		var audit = domain.thermalAudit();
		for (int step = 0; step < 3; step++) {
			domain = new CpuFluidSolverBackend().evaluate(FluidStepRequest.pendingPhaseSettlement(step, domain.captureAuthoritativeState())).claimPreparedCpuDomain();
			try (var ignored = LiquidGeometryStateMapper.openReadScope()) {
				var plan = new LiquidGeometryReconstructionPlan(domain, new LiquidMomentReconstruction.Tolerance(0.01, 0.03), 0.001,
						new LiquidMomentReconstruction.Budget(65536, 65536, 65536));
				require(plan.leaves.size() == 1 && plan.maximumCentroidError <= 0.01 && plan.maximumOccupancyErrorLength <= 0.03
						&& plan.velocityErrors.stream().allMatch(error -> error.maximumRmsError() <= 0.001), "moving regrid retained auxiliary refinement despite resolved spatial and velocity errors");
				new ResolvedGasMotionPlan(domain, plan, 65536, 65536).publish(domain, plan);
			}
			require(initial.totalVolume().equals(domain.particles.inventory().totalVolume()) && initial.totalMass().equals(domain.particles.inventory().totalMass())
					&& species.equals(domain.chemicalLedgerForMarkerRange(0, domain.particles.count()).groups().getFirst().speciesQuanta())
					&& domain.particles.count() == java.util.Arrays.stream(domain.liquidGeometryState.copyControlCells()).distinct().count(),
					"moving chemical regrid lost exact quantities or created numerical-tail inventories");
			require(domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()) && audit.externalSource().equals(domain.thermalAudit().externalSource())
					&& audit.externalSink().equals(domain.thermalAudit().externalSink()), "moving chemical regrid changed its energy accounting");
			LiquidGeometryStateMapper.requireCompatible(domain.liquidGeometryState, domain);
		}
	}
	private static void nativeLocalInventoryCarriesMaterialAndEnergy() throws Exception {
		var domain = localInventoryFixture(16); var energy = domain.exactInternalEnergy(); var ledger = domain.thermalAudit();
		double sourceReferenceEnergy = 0;
		for (int p = 0; p < 2; p++) sourceReferenceEnergy += domain.thermalState.liquid().caloricInventory(0, domain.particles.mass(p),
				domain.chemicalParcels.record(p), domain.chemicalParcels.registry()).energy(293.15);
		var species = domain.chemicalLedgerForMarkerRange(0, 2); var plan = new LiquidInventoryRepartitionPlan(domain);
		require(plan.changed() && plan.markers.size() == 2 && plan.kineticChange.signum() == 0,
				"local inventory did not retain its mechanical field or independent control volumes");
		for (var delta : plan.momentumChange) require(delta.signum() == 0, "local inventory changed exact resolved momentum");
		domain.carrierMutations().publishGeometricRepartition(plan);
		require(domain.particles.count() == 2 && domain.totalVolumeExact() == 4 && domain.totalMass() == 4200
				&& species.groups().getFirst().speciesQuanta().equals(domain.chemicalLedgerForMarkerRange(0, 2).groups().getFirst().speciesQuanta()),
				"local inventory changed material or integer species totals");
		require(domain.exactInternalEnergy().subtract(energy).equals(plan.energyRoundoff)
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory())
				&& ledger.externalSource().equals(domain.thermalAudit().externalSource()) && ledger.externalSink().equals(domain.thermalAudit().externalSink()),
				"local inventory created heat or changed the external energy ledger");
		double expectedTemperature = (3000.0 * 270 + 1200.0 * 350) / 4200;
		double maximumTemperatureChange = 0;
		for (int p = 0; p < 2; p++) {
			var caloric = domain.thermalState.liquid().caloricInventory(0, domain.particles.mass(p), domain.chemicalParcels.record(p), domain.chemicalParcels.registry());
			// Integer allocation shifts formation energy between controls by a fraction of one species quantum.
			double temperature = expectedTemperature + (sourceReferenceEnergy / 2 - caloric.energy(293.15))
					/ (domain.unitScale.kilograms(domain.particles.mass(p)) * 4184);
			require(Math.abs(domain.thermalState.temperature(p, domain.thermalMarkers) - temperature) <= 8 * Math.ulp(temperature),
					"local inventory lost its caloric average or species formation-energy correction");
			maximumTemperatureChange = Math.max(maximumTemperatureChange, 350 - temperature);
		}
		require(Math.abs(plan.maximumTemperatureChange - maximumTemperatureChange) <= 8 * Math.ulp(expectedTemperature),
				"local inventory failed to report caloric coarsening error");
		require(java.util.Arrays.equals(domain.liquidGeometryState.copyFragmentOwners(), domain.liquidGeometryState.copyControlCells()),
				"mechanical controls still shared a nonlocal extensive inventory");
		var restored = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
		require(restored.exactInternalEnergy().equals(domain.exactInternalEnergy()) && restored.totalChemicalSpeciesQuanta() == domain.totalChemicalSpeciesQuanta()
				&& !new LiquidInventoryRepartitionPlan(restored).changed(), "local inventory lost identity across a cold handoff");
		var tagged = localInventoryFixture(16);
		tagged.chemicalParcels.assign(1, tagged.chemicalParcels.record(1), true);
		var taggedVolume = tagged.particles.exactVolume(1); var taggedMass = tagged.particles.exactMass(1);
		tagged.carrierMutations().publishGeometricRepartition(new LiquidInventoryRepartitionPlan(tagged));
		var taggedOutputVolume = SignedDyadic.ZERO; var taggedOutputMass = SignedDyadic.ZERO;
		for (int p = 0; p < tagged.particles.count(); p++) if (tagged.chemicalParcels.indicator(p)) {
			taggedOutputVolume = taggedOutputVolume.add(tagged.particles.exactVolume(p)); taggedOutputMass = taggedOutputMass.add(tagged.particles.exactMass(p));
		}
		require(tagged.particles.count() == 4 && taggedVolume.equals(taggedOutputVolume) && taggedMass.equals(taggedOutputMass),
				"local stock aggregation lost indicator provenance or its exact quantities");
		var limited = localInventoryFixture(2);
		limited.liquidGeometryState = LiquidGeometryStateMapper.capture(java.util.List.of(
				new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 0.25, 1, 0.5)),
				new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0.25, 0, 0, 0.5, 1, 0.5)),
				new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0.5, 0, 0, 1, 1, 0.5))),
				1, new int[]{0, 0}, new int[]{0, 0, 0}, new double[]{0.25, 0, 0, 0.25, 0, 0, 0.75, 0, 0});
		var original = limited.liquidGeometryState; var originalEnergy = limited.exactInternalEnergy();
		try { new LiquidInventoryRepartitionPlan(limited); throw new AssertionError("local inventory ignored its marker budget"); }
		catch (IllegalStateException expected) { require(limited.liquidGeometryState == original && limited.particles.count() == 2
				&& originalEnergy.equals(limited.exactInternalEnergy()), "local inventory budget rejection changed its input"); }
		var stale = new LiquidInventoryRepartitionPlan(limited = localInventoryFixture(16));
		limited.thermalState.resetMarkerTemperature(0, 280, limited.thermalMarkers, limited.exactInternalEnergy());
		var changedEnergy = limited.exactInternalEnergy();
		try { limited.carrierMutations().publishGeometricRepartition(stale); throw new AssertionError("local inventory accepted an obsolete thermal input"); }
		catch (IllegalStateException expected) { require(limited.liquidGeometryState == stale.input && limited.particles.count() == 2
				&& changedEnergy.equals(limited.exactInternalEnergy()), "stale local inventory changed its input"); }
		var uneven = localInventoryFixture(16, true);
		var originalMass = SignedDyadic.ZERO; for (int p = 0; p < 2; p++) originalMass = originalMass.add(uneven.particles.exactMass(p));
		var quantity = Fraction.of(originalMass);
		var kg = Fraction.of(uneven.unitScale.cubicMetersPerNormalizedVolume());
		var share = Fraction.of(7.0 / 16);
		var other = Fraction.of(9.0 / 16);
		var beforeMomentum = quantity.multiply(kg).multiply(share.multiply(Fraction.of(0.25)).add(other.multiply(Fraction.of(0.75))));
		var beforeKinetic = quantity.multiply(kg).multiply(share.multiply(Fraction.of(0.25 * 0.25 / 2))
				.add(other.multiply(Fraction.of(0.75 * 0.75 / 2))));
		var rounded = new LiquidInventoryRepartitionPlan(uneven); uneven.carrierMutations().publishGeometricRepartition(rounded);
		var afterMass = SignedDyadic.ZERO; var afterMomentum = Fraction.ZERO; var afterKinetic = afterMomentum;
		for (int p = 0; p < uneven.particles.count(); p++) {
			double velocity = uneven.particles.velocityX(p); afterMass = afterMass.add(uneven.particles.exactMass(p));
			var mass = Fraction.of(uneven.particles.exactMass(p)).multiply(kg);
			afterMomentum = afterMomentum.add(mass.multiply(Fraction.of(velocity)));
			afterKinetic = afterKinetic.add(mass.multiply(Fraction.of(velocity * velocity / 2)));
		}
		require(originalMass.equals(afterMass) && rounded.momentumChange[0].equals(afterMomentum.subtract(beforeMomentum))
				&& rounded.kineticChange.equals(afterKinetic.subtract(beforeKinetic)),
				"local mass allocation lost exact inventory or its independently integrated mechanical roundoff");
		require(rounded.momentumChange[0].absolute().compareTo(rounded.momentumBounds[0]) <= 0 && rounded.kineticChange.absolute().compareTo(rounded.kineticBound) <= 0
				&& uneven.exactInternalEnergy().equals(uneven.thermalAudit().expectedInventory()), "local inventory exceeded its physical-inertia or caloric roundoff certificate");
	}
	private static FluidDomain localInventoryFixture(int capacity) throws Exception {
		return localInventoryFixture(capacity, false);
	}
	private static FluidDomain localInventoryFixture(int capacity, boolean uneven) throws Exception {
		var registries = FullCapabilityTestFixture.load();
		var domain = new FluidDomain(2, 2, 2, new FluidSolverConfig(0.5F, 1000, 0, 0.05F, 100, 1e-7F, 0.4F, 0.002F, 0), capacity);
		double cut = uneven ? 7.0 / 16 : 0.5; float velocity = (float) (cut * 0.25 + (1 - cut) * 0.75);
		double[] masses = uneven ? new double[]{1000 * Math.PI, 400 * Math.E} : new double[]{3000, 1200};
		var cells = java.util.List.of(new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, cut, 1, 0.5)),
				new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(cut, 0, 0, 1, 1, 0.5)));
		var geometry = LiquidGeometryStateMapper.capture(cells, 1, new int[]{0, 0}, new int[]{0, 0}, new double[]{0.25, 0, 0, 0.75, 0, 0});
		domain.restoreMaterialParticleState(new FlipMaterialParticleState(2, 2, 2, 0.5F, 2, 4, SignedDyadic.sum(masses).toDouble(), registries.liquids().materials(),
				new float[]{0.5F, 0.5F, 0.25F, 3, 0.5F, 0.5F, 0.25F, 1}, new float[]{velocity, 0, 0, velocity, 0, 0}, new byte[8], masses, new int[]{0, 0}, geometry));
		domain.sealDomainBoundary();
		FullCapabilityTestFixture.bindFull(domain, registries, GasRegistry.STANDARD_TEMPERATURE_K, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		domain.thermalState.resetMarkerTemperature(0, 270, domain.thermalMarkers, domain.exactInternalEnergy());
		domain.thermalState.resetMarkerTemperature(1, 350, domain.thermalMarkers, domain.exactInternalEnergy());
		return domain;
	}
	private static void nativeConductionPublishesCaloricInventory() throws Exception {
		ResolvedLiquidConductionValidation.validate(localInventoryFixture(16));
	}
	private static void nativeThermalObservationUsesOccupiedGeometry() throws Exception {
		var registries = FullCapabilityTestFixture.load();
		var domain = new FluidDomain(4, 3, 2, new FluidSolverConfig(0.5F, 1000, 0, 0.05F, 100, 1e-7F, 0.4F, 0.002F, 0), 16);
		var cells = java.util.List.of(
				new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 0.875, 0.75, 0.5)),
				new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0.875, 0, 0, 1, 0.75, 0.5)));
		var geometry = LiquidGeometryStateMapper.capture(cells, 2, new int[]{0, 1});
		domain.restoreMaterialParticleState(new FlipMaterialParticleState(4, 3, 2, 0.5F, 2, 24, 24000,
				registries.liquids().materials(), new float[]{0.875F, 0.75F, 0.5F, 21, 1.875F, 0.75F, 0.5F, 3}, new float[6], new byte[24],
				new double[]{21000, 3000}, new int[]{0, 0}, geometry));
		FullCapabilityTestFixture.bindFull(domain, registries, GasRegistry.STANDARD_TEMPERATURE_K, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		domain.thermalState.resetMarkerTemperature(0, 270, domain.thermalMarkers, domain.exactInternalEnergy());
		domain.thermalState.resetMarkerTemperature(1, 350, domain.thermalMarkers, domain.exactInternalEnergy());
		var before = domain.captureAuthoritativeState();
		assertGeometricThermalObservation(domain);
		var restored = FluidDomain.restoreAuthoritativeState(before);
		assertGeometricThermalObservation(restored);
		require(domain.liquidGeometryState == geometry && domain.totalVolumeExact() == 24 && domain.totalMass() == 24000
				&& domain.exactInternalEnergy().equals(restored.exactInternalEnergy())
				&& domain.totalChemicalSpeciesQuanta() == restored.totalChemicalSpeciesQuanta()
				&& domain.thermalAudit().equals(restored.thermalAudit()), "geometric thermal observation changed inventory or the energy ledger");
		// Auxiliary fragment subdivision retains exactly the same occupied caloric support.
		var fragments = new java.util.ArrayList<LiquidGeometryRefinement.Cell>(); var owners = new java.util.ArrayList<Integer>();
		for (int c = 0; c < cells.size(); c++) for (var child : LiquidGeometryRefinement.children(cells.get(c))) { fragments.add(child); owners.add(c); }
		int second = owners.indexOf(1);
		domain.liquidGeometryState = LiquidGeometryStateMapper.capture(fragments, 2, new int[]{0, second}, owners.stream().mapToInt(Integer::intValue).toArray());
		assertGeometricThermalObservation(domain);
		for (var room : domain.availableCellVolumes()) require(room.signum() == 0, "resolved transfer capacity differs from occupied caloric geometry");
		require(domain.particleCellIndex(0) != domain.cellIndex(0, 0, 0), "remote geometric extraction fixture retained its marker centre");
		var extracted = domain.tryExtractExactVolume(0, 0, 0, 0.0625F, 0);
		require(extracted.transferred() && domain.totalVolumeExact() == 23.9375 && domain.totalMass() == 23937.5,
				"geometric extraction followed the marker centre instead of its actual occupied cell");
		var capacity = domain.availableCellVolumes();
		for (int c = 0; c < capacity.length; c++) require(capacity[c].equals(SignedDyadic.of(c == 0 ? 0.0625 : 0)),
				"remote extraction changed occupied volume outside its selected cell");
		LiquidGeometryStateMapper.requireCompatible(domain.liquidGeometryState, domain);
		domain.thermalState.prepareSpatialObservation(domain.thermalMarkers);
		for (int c = 0; c < 24; c++) require(Math.abs(domain.thermalState.spatialTemperatureInCell(c) - (c % 4 < 3 ? 270 : 310)) < 1e-10,
				"local extraction changed the remaining caloric distribution");
		require(domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()), "remote geometric extraction lost its energy sink");
	}
	private static void assertGeometricThermalObservation(FluidDomain domain) {
		var energy = domain.exactInternalEnergy(); var audit = domain.thermalAudit();
		domain.thermalState.prepareSpatialObservation(domain.thermalMarkers);
		require(domain.thermalMarkers.observationSupportSize(0) == 24, "resolved caloric support retained the eight-point observation limit");
		for (int p = 0; p < 2; p++) {
			int marker = p; var weights = new double[24];
			domain.thermalMarkers.observationSupport(p, (cell, weight) -> weights[cell] += weight);
			for (int c = 0; c < weights.length; c++) {
				double expected = marker == 0 ? (c % 4 < 3 ? 1.0 / 21 : 0.5 / 21) : (c % 4 == 3 ? 1.0 / 6 : 0);
				require(weights[c] == expected, "caloric support differs from exact occupied-cell volume");
			}
		}
		for (int c = 0; c < 24; c++) {
			double temperature = domain.thermalState.spatialTemperatureInCell(c);
			double expected = c % 4 < 3 ? 270 : 310;
			require(Math.abs(temperature - expected) < 1e-10, "resolved caloric observation lost its occupied position or shared-cell mixing");
		}
		require(energy.equals(domain.exactInternalEnergy()) && audit.equals(domain.thermalAudit()), "read-only caloric support changed internal energy");
	}
	private static void nativeGasMotionCarriesEnergy() throws Exception {
		var registries = FullCapabilityTestFixture.load();
		var movedDomain = new FluidDomain[1];
		LiquidCharacteristicValidation.movingLiquidWallTransportsItsGasContacts(domain -> {
			var liquid = domain.exportParticleState();
			domain.restoreMaterialParticleState(new FlipMaterialParticleState(2, 2, 2, 0.5F, 1, 1.0 / 256, 1000.0 / 256,
					registries.liquids().materials(), liquid.copyPositionVolume(), liquid.copyParticleVelocities(), liquid.copyCollisionMicroMasks(),
					new double[]{1000.0 / 256}, new int[]{0}, liquid.liquidGeometry()));
			FullCapabilityTestFixture.bindFull(domain, registries, GasRegistry.STANDARD_TEMPERATURE_K, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
			movedDomain[0] = domain;
		});
		var snapshot = movedDomain[0].captureAuthoritativeState();
		for (int mode = 0; mode < 3; mode++) {
			var clear = FluidDomain.restoreAuthoritativeState(snapshot);
			var before = clear.exportGasState(registries.chemical().fingerprint());
			require(before.nodes().size() == 2, "gas expansion fixture needs separate chambers");
			var expected = before.nodes().get(0).inventory().merge(before.nodes().get(1).inventory());
			var energy = clear.exactInternalEnergy(); var roundoff = clear.thermalAudit().roundoffResidual();
			var removed = clear.planRemoveAllWaterExact().internalEnergy();
			if (mode == 0) clear.removeAllWaterExact();
			else if (mode == 1) clear.commitRemoveAllCondensedExact(clear.planRemoveAllCondensedExact());
			else clear.commitOperatorClear(clear.planOperatorClear());
			var after = clear.exportGasState(registries.chemical().fingerprint());
			require(after.nodes().size() == 1 && after.nodes().getFirst().inventory().equals(expected)
					&& after.externalSource().equals(before.externalSource()) && after.escapedSink().equals(before.escapedSink())
					&& clear.exactInternalEnergy().add(removed).subtract(energy).equals(clear.thermalAudit().roundoffResidual().subtract(roundoff))
					&& clear.exactInternalEnergy().equals(clear.thermalAudit().expectedInventory()), "liquid clear changed merged gas stock or free-expansion energy");
			var restored = FluidDomain.restoreAuthoritativeState(clear.captureAuthoritativeState());
			require(restored.exportGasState(registries.chemical().fingerprint()).nodes().equals(after.nodes()), "merged gas clear lost its cold geometry");
		}
		var stale = FluidDomain.restoreAuthoritativeState(snapshot); var plan = stale.planRemoveAllWaterExact();
		stale.commitOperatorVentSealedGas(stale.planOperatorVentSealedGas());
		var energy = stale.exactInternalEnergy(); var sink = stale.chemicalParcels().escapedSink();
		try { stale.commitRemoveAllWaterExact(plan); throw new AssertionError("liquid clear accepted changed gas input"); }
		catch (IllegalStateException expected) { }
		require(stale.particles.count() == 1 && stale.exactInternalEnergy().equals(energy)
				&& stale.chemicalParcels().escapedSink() == sink, "obsolete gas plan partially cleared liquid stock");
		var edited = FluidDomain.restoreAuthoritativeState(snapshot); var obsolete = edited.planOperatorClear(); var retainedEnergy = edited.exactInternalEnergy();
		edited.geometry.cutCells.replaceMask(0, FlipCutCellGeometry.FULL_MASK);
		edited.geometry.solid[0] = true;
		try { edited.commitOperatorClear(obsolete); throw new AssertionError("gas expansion accepted changed collision support"); }
		catch (IllegalStateException expected) { }
		require(edited.particles.count() == 1 && edited.exactInternalEnergy().equals(retainedEnergy),
				"obsolete collision support partially cleared inventory");

	}
	private static void nativePhaseReconstructionKeepsEnergy() throws Exception {
		var registries = FullCapabilityTestFixture.load();
		LiquidJointReconstructionValidation.jointGasReconstructionPreservesMaterialChambers(domain -> {
			var liquid = domain.exportParticleState(); double mass = domain.totalMass();
			domain.restoreMaterialParticleState(new FlipMaterialParticleState(domain.sizeX(), domain.sizeY(), domain.sizeZ(), domain.config().cellSize(), 1,
					liquid.totalVolume(), mass, registries.liquids().materials(), liquid.copyPositionVolume(), liquid.copyParticleVelocities(), liquid.copyCollisionMicroMasks(),
					new double[]{mass}, new int[]{0}, liquid.liquidGeometry()));
			FullCapabilityTestFixture.bindFull(domain, registries, GasRegistry.STANDARD_TEMPERATURE_K, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		});
	}
	private static void nativeGasMergeRoundsCompleteMassOnce() throws Exception {
		var registries = FullCapabilityTestFixture.load();
		var domain = new FluidDomain(2, 2, 2, config(), 16);
		var cells = new java.util.ArrayList<LiquidGeometryRefinement.Cell>();
		for (double y : new double[]{0.25, 0.75}) cells.add(new LiquidGeometryRefinement.Cell(0, 0, 0, 0,
				LiquidPolyhedron.box(0, y - 1.0 / 1024, 0, 1, y + 1.0 / 1024, 1)));
		var geometry = LiquidGeometryStateMapper.capture(cells, 1, new int[]{0, 1});
		domain.restoreMaterialParticleState(new FlipMaterialParticleState(2, 2, 2, 0.5F, 2, 1.0 / 32, 1000.0 / 32,
				registries.liquids().materials(), new float[]{0.5F, 0.25F, 0.5F, 1.0F / 64, 0.5F, 0.75F, 0.5F, 1.0F / 64},
				new float[6], new byte[8], new double[]{1000.0 / 64, 1000.0 / 64}, new int[2], geometry));
		domain.sealDomainBoundary(); domain.bindChemistry(registries.chemical());
		domain.bindGasRegistry(registries.gas()); domain.bootstrapGasState(registries.chemical().fingerprint());
		require(domain.gasState.network.nodeCount() == 3, "complete gas mass fixture needs three chambers");
		var stocks = new java.util.ArrayList<GasInventory>(); var capacities = new double[3];
		for (int node = 1; node <= 3; node++) {
			var components = new java.util.ArrayList<>(domain.gasState.network.inventory(node).components());
			var old = components.getFirst(); double mass = node == 1 ? 1 : 0x1.0p-53;
			components.set(0, new GasComponentAmount(old.componentId(), old.standardVolumeNanos(), mass, old.species(), mass));
			stocks.add(new GasInventory(components)); capacities[node - 1] = domain.gasState.network.availableVolume(node);
		}
		domain.gasState.network.remapNodes(capacities, stocks, GasInventory.EMPTY, GasInventory.EMPTY);
		domain.bindThermal(registries.thermal(), domain.materialRegistry(), GasRegistry.STANDARD_TEMPERATURE_K);
		var before = domain.exportGasState(registries.chemical().fingerprint()); var energy = domain.exactInternalEnergy();
		var roundoff = domain.thermalAudit().roundoffResidual(); var work = domain.thermalAudit().mechanicalWork();
		var plan = domain.planRemoveAllWaterExact(); var removed = plan.internalEnergy(); domain.commitRemoveAllWaterExact(plan);
		var after = domain.exportGasState(registries.chemical().fingerprint());
		var expected = GasInventory.sum(stocks);
		require(after.nodes().size() == 1 && after.nodes().getFirst().inventory().equals(expected)
				&& expected.components().getFirst().mass() == Math.nextUp(1.0) && expected.components().getFirst().massResidual() == Math.nextUp(1.0),
				"joint geometry publication rounded gas masses before all three chamber contributions were available");
		require(before.externalSource().equals(after.externalSource()) && before.escapedSink().equals(after.escapedSink())
				&& domain.thermalAudit().mechanicalWork().equals(work) && domain.exactInternalEnergy().add(removed).subtract(energy)
				.equals(domain.thermalAudit().roundoffResidual().subtract(roundoff)) && domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"gas merge invented material source, sink or reversible expansion work");
		var saved = domain.captureAuthoritativeState(); var cold = FluidDomain.restoreAuthoritativeState(saved);
		FluidDomainStateAssertions.assertSameState(saved, cold.captureAuthoritativeState(), "complete gas merge cold restore");
	}

	private static void nativeExtractionMovesGeometryAndSideColumns() throws Exception {
		var domain = emptyMaterialDomain(4096);
		domain.liquidGeometryState = new LiquidGeometryState(2, java.util.List.of(), new int[0]);
		FullCapabilityTestFixture.bindFull(domain, FullCapabilityTestFixture.load(),
				GasRegistry.STANDARD_TEMPERATURE_K, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		int oil = domain.materialRegistry.handle("mmm:oil");
		for (int x : new int[]{1, 2}) require(domain.tryInsertExactMixture(x, 1, 1, java.util.List.of(
				new FluidDomain.MaterialPortion(0, 0.03125, 31.25),
				new FluidDomain.MaterialPortion(oil, 0.03125, 0.03125 * domain.materialRegistry.material(oil).referenceDensity())), 0, true).transferred(),
				"native extraction fixture source failed");
		var lastMixture = domain.chemicalParcels.record(3); double lastEnergy = domain.thermalState.liquidInternalEnergy(3);
		var removed = domain.tryExtractExactVolume(1, 1, 1, 0.03125F, 0);
		require(removed.transferred() && domain.particles.count() == 3
				&& domain.chemicalParcels.record(0).equals(lastMixture)
				&& domain.thermalState.liquidInternalEnergy(0) == lastEnergy, "native extraction lost swap-compacted side columns");
		LiquidGeometryStateMapper.requireCompatible(domain.liquidGeometryState, domain);
		var before = domain.exportMaterialParticleState(); var geometry = domain.liquidGeometryState;
		require(!domain.tryExtractExactChemical(1, 1, 1, 0.015625F, 0, ignored -> false).transferred()
				&& domain.liquidGeometryState == geometry
				&& java.util.Arrays.equals(before.copyPositionVolume(), domain.exportMaterialParticleState().copyPositionVolume()),
				"rejected native chemical ledger changed inventory geometry");
		long species = domain.totalChemicalSpeciesQuanta(); var energy = domain.exactInternalEnergy(); var roundoff = domain.thermalAudit().roundoffResidual();
		var chemical = domain.tryExtractExactChemical(1, 1, 1, 0.015625F, 0);
		require(chemical.transferred() && domain.totalChemicalSpeciesQuanta() + chemical.ledger().totalQuanta() == species,
				"native partial extraction lost species");
		require(domain.exactInternalEnergy().subtract(energy).add(cc.ecisr.mmm.units.SignedDyadic.of(chemical.internalEnergy()))
				.equals(domain.thermalAudit().roundoffResidual().subtract(roundoff)), "native partial extraction lost energy settlement");
		LiquidGeometryStateMapper.requireCompatible(domain.liquidGeometryState, domain);
		var restored = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
		require(restored.totalChemicalSpeciesQuanta() == domain.totalChemicalSpeciesQuanta()
				&& restored.exactInternalEnergy().equals(domain.exactInternalEnergy()), "native extraction lost complete candidate identity");
		species = domain.totalChemicalSpeciesQuanta(); long sink = domain.chemicalParcels.escapedSink().totalQuanta();
		require(domain.removeVolumeNear(2, 1, 1, 0.015625F, 0) == 0.015625F
				&& domain.totalChemicalSpeciesQuanta() + domain.chemicalParcels.escapedSink().totalQuanta() - sink == species,
				"ordinary native removal lost its actual volume or escaped species ledger");
		LiquidGeometryStateMapper.requireCompatible(domain.liquidGeometryState, domain);
		require(domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()), "ordinary native removal lost thermal settlement");
		species = domain.totalChemicalSpeciesQuanta(); sink = domain.chemicalParcels.escapedSink().totalQuanta();
		require(domain.removeParticlesInCell(2, 1, 1, Float.POSITIVE_INFINITY) == 0.046875F
				&& domain.totalChemicalSpeciesQuanta() + domain.chemicalParcels.escapedSink().totalQuanta() - sink == species,
				"single-cell native removal lost side columns during the descending swap sweep");
		LiquidGeometryStateMapper.requireCompatible(domain.liquidGeometryState, domain);
		require(domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()), "single-cell native removal lost thermal settlement");
	}

	private static void independentWorkFinishesBeforeRejection() {
		int[] visits = new int[8_192];
		var first = new IllegalStateException("first batch rejection");
		var last = new AssertionError("last batch rejection");
		try {
			cc.ecisr.mmm.compute.CpuWorkBatch.run(visits.length, (from, until) -> {
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
		cc.ecisr.mmm.compute.CpuWorkBatch.run(visits.length, (from, until) -> { for (int index = from; index < until; index++) { visits[index]++; } });
		for (int visit : visits) require(visit == 1, "independent work missed or repeated an index after rejection");
	}

	private static void nativeSourceBatchMovesGeometryAndSideColumns() throws Exception {
		var domain = emptyMaterialDomain(4096);
		domain.liquidGeometryState = new LiquidGeometryState(2, java.util.List.of(), new int[0]);
		FullCapabilityTestFixture.bindFull(domain, FullCapabilityTestFixture.load(),
				GasRegistry.STANDARD_TEMPERATURE_K, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		int oil = domain.materialRegistry.handle("mmm:oil");
		var source = java.util.List.of(new FluidDomain.MaterialPortion(0, 0.03125, 31.25),
				new FluidDomain.MaterialPortion(oil, 0.03125, 0.03125 * domain.materialRegistry.material(oil).referenceDensity()));
		require(domain.tryInsertExactMixture(1, 1, 1, source, 0, true).transferred(), "native material source was refused");
		LiquidGeometryStateMapper.requireCompatible(domain.liquidGeometryState, domain);
		var ledger = domain.chemicalLedgerForMarkerRange(0, domain.particles.count());
		long species = domain.totalChemicalSpeciesQuanta();
		var beforeEnergy = domain.exactInternalEnergy(); var beforeRoundoff = domain.thermalAudit().roundoffResidual();
		var beforeWork = domain.thermalAudit().mechanicalWork(); var gasBefore = domain.exportGasState("");
		double sourceEnergy = domain.thermalState.exactLiquidEnergy(domain.particles.count()).toDouble();
		require(domain.tryInsertExactChemical(2, 1, 1, ledger, 0, true, sourceEnergy).transferred(), "native chemical source was refused");
		LiquidGeometryStateMapper.requireCompatible(domain.liquidGeometryState, domain);
		require(domain.totalChemicalSpeciesQuanta() == 2 * species && domain.totalVolumeExact() == 0.125,
				"native source changed its exact species or volume allocation");
		require(domain.exactInternalEnergy().subtract(beforeEnergy).subtract(cc.ecisr.mmm.units.SignedDyadic.of(sourceEnergy))
				.subtract(domain.thermalAudit().mechanicalWork().subtract(beforeWork)).equals(domain.thermalAudit().roundoffResidual().subtract(beforeRoundoff)), "native source energy or roundoff ledger did not close");
		var gasAfter = domain.exportGasState("");
		require(gasAfter.geometry() != null && gasBefore.nodes().getFirst().inventory().equals(gasAfter.nodes().getFirst().inventory())
				&& gasBefore.externalSource().equals(gasAfter.externalSource()) && gasBefore.escapedSink().equals(gasAfter.escapedSink())
				&& domain.thermalAudit().mechanicalWork().subtract(beforeWork).signum() > 0
				&& domain.gasNodeGaugePressurePa(1) > 0 && domain.gasNodeTemperatureK(1) > GasRegistry.STANDARD_TEMPERATURE_K,
				"resolved injection lost gas stock or its adiabatic response");
		var captured = domain.captureAuthoritativeState(); var restored = FluidDomain.restoreAuthoritativeState(captured);
		require(restored.liquidGeometryState.markerCount() == domain.particles.count()
				&& restored.totalChemicalSpeciesQuanta() == domain.totalChemicalSpeciesQuanta()
				&& restored.exactInternalEnergy().equals(domain.exactInternalEnergy()), "native source side columns lost candidate identity");
		var limited = emptyMaterialDomain(1);
		limited.liquidGeometryState = new LiquidGeometryState(2, java.util.List.of(), new int[0]);
		require(limited.tryInsertExactMixture(1, 1, 1, java.util.List.of(new FluidDomain.MaterialPortion(0, 0.0625, 62.5)), 0, true).transferred(),
				"native source at its one-marker capacity was refused");
		var refined = LiquidGeometryStateMapper.restore(limited.liquidGeometryState).stream().flatMap(cell -> LiquidGeometryRefinement.children(cell).stream()).toList();
		limited.liquidGeometryState = LiquidGeometryStateMapper.capture(refined, 2, new int[]{0}, new int[refined.size()]);
		require(limited.liquidGeometryState.cells().size() > limited.particles.count(), "geometry subdivision depended on inventory marker count");
		LiquidGeometryStateMapper.requireCompatible(limited.liquidGeometryState, limited);
		var geometry = limited.liquidGeometryState; long revision = limited.carrierMutationRevision();
		try {
			new LiquidGeometryInsertionPlan(geometry, limited.sizeX, limited.sizeY, limited.sizeZ, limited.config.cellSize(),
					limited.geometry.cutCells.copyMicroMasks(), java.util.List.of(new LiquidGeometryInsertionPlan.Insertion(1, 1, 1, 0.03125F)), 1);
			throw new AssertionError("native geometry source ignored its work budget");
		} catch (IllegalStateException expected) {
			require(limited.particles.count() == 1 && limited.carrierMutationRevision() == revision && limited.liquidGeometryState == geometry,
					"native geometric preflight rejection changed inventory or geometry");
		}
		var beforeAtMost = domain.particles.inventory().totalVolume();
		var added = domain.addVolumeNearBudgeted(3, 1, 1, 0.03125F, 0);
		require(added.addedVolume() == 0.03125F && domain.particles.inventory().totalVolume().subtract(beforeAtMost).equals(SignedDyadic.of(0.03125))
				&& domain.exportGasState("").nodes().getFirst().inventory().equals(gasAfter.nodes().getFirst().inventory())
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()), "at-most injection changed gas or liquid extensive quantities");
		FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
		domain.removeAllWaterExact();
		require(domain.liquidGeometryState != null && domain.liquidGeometryState.cells().isEmpty()
				&& domain.liquidGeometryState.markerCount() == 0 && domain.totalChemicalSpeciesQuanta() == 0,
				"liquid clear retained native occupied geometry or side inventory");
		LiquidGeometryStateMapper.requireCompatible(domain.liquidGeometryState, domain);
		require(domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()), "native liquid clear lost its thermal sink");
		nativeInjectionSplitsGasAndRejectsZeroCapacity();
	}

	private static void nativeInjectionSplitsGasAndRejectsZeroCapacity() throws Exception {
		var registries = FullCapabilityTestFixture.load();
		var domain = new FluidDomain(2, 3, 2, config(), 64);
		var masks = new byte[12]; java.util.Arrays.fill(masks, (byte) FlipCutCellGeometry.FULL_MASK);
		masks[0] = masks[2] = masks[4] = 0;
		domain.restoreMaterialParticleState(new FlipMaterialParticleState(2, 3, 2, 0.5F, 0, 0, 0, registries.liquids().materials(),
				new float[0], new float[0], masks, new double[0], new int[0]));
		domain.liquidGeometryState = new LiquidGeometryState(2, java.util.List.of(), new int[0]); domain.sealDomainBoundary();
		FullCapabilityTestFixture.bindFull(domain, registries, GasRegistry.STANDARD_TEMPERATURE_K, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		var oldGas = domain.exportGasState("");
		require(domain.tryInsertExactVolume(0, 1, 0, 1, 0, 0, true).transferred(), "gas split injection was refused");
		var gas = domain.exportGasState("");
		require(gas.nodes().size() == 2 && gas.nodes().get(0).inventory().merge(gas.nodes().get(1).inventory()).equals(oldGas.nodes().getFirst().inventory())
				&& gas.externalSource().equals(oldGas.externalSource()) && gas.escapedSink().equals(oldGas.escapedSink())
				&& domain.gasNodeGaugePressurePa(1) > 0 && domain.gasNodeGaugePressurePa(2) > 0
				&& domain.thermalAudit().mechanicalWork().signum() > 0 && domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"geometric gas split lost stock, compression work or caloric accounting");
		var restored = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
		require(restored.exportGasState("").nodes().equals(gas.nodes()), "gas split injection lost its cold spatial support");
		var input = domain.captureAuthoritativeState(); var blocked = FluidDomain.restoreAuthoritativeState(input);
		var geometry = blocked.liquidGeometryState; int markers = blocked.particles.count(); var chemicalSource = blocked.chemicalParcels().externalSource();
		try { blocked.tryInsertExactVolume(0, 0, 0, 1, 0, 0, true); throw new AssertionError("finite gas was injected into zero available volume"); }
		catch (GasTopologyBlockedException expected) { }
		require(blocked.liquidGeometryState == geometry && blocked.particles.count() == markers
				&& blocked.chemicalParcels().externalSource() == chemicalSource, "zero-capacity gas rejection changed liquid or chemical stock");
		FluidDomainStateAssertions.assertSameState(input, blocked.captureAuthoritativeState(), "zero gas capacity injection");
		var open = new FluidDomain(3, 4, 3, config(), 64);
		var collision = new byte[36]; java.util.Arrays.fill(collision, (byte) FlipCutCellGeometry.FULL_MASK);
		for (int y = 0; y < 3; y++) collision[open.cellIndex(1, y, 1)] = 0;
		open.restoreMaterialParticleState(new FlipMaterialParticleState(3, 4, 3, 0.5F, 0, 0, 0, registries.liquids().materials(),
				new float[0], new float[0], collision, new double[0], new int[0]));
		open.liquidGeometryState = new LiquidGeometryState(2, java.util.List.of(), new int[0]);
		FullCapabilityTestFixture.bindFull(open, registries, GasRegistry.STANDARD_TEMPERATURE_K, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		require(open.exportGasState("").nodes().isEmpty(), "ambient capture fixture initialized a finite chamber");
		require(open.tryInsertExactVolume(1, 1, 1, 1, 0, 0, true).transferred(), "ambient capture injection was refused");
		var captured = open.exportGasState("");
		require(captured.nodes().size() == 1 && cc.ecisr.mmm.fluid.air.GasAuditLedger.of(captured.nodes().getFirst().inventory()).equals(captured.externalSource())
				&& captured.externalSource().standardVolumeNanos() == 125_000_000L && captured.escapedSink().entries().isEmpty()
				&& Math.abs(open.gasNodeGaugePressurePa(1)) < 1e-9
				&& open.exactInternalEnergy().equals(open.thermalAudit().expectedInventory()), "ambient capture lost actual capacity, source or energy accounting");
		var cold = FluidDomain.restoreAuthoritativeState(open.captureAuthoritativeState());
		require(cold.exportGasState("").nodes().equals(captured.nodes()), "ambient capture lost its cold finite chamber");
		cold.removeAllWaterExact();
		var vented = cold.exportGasState("");
		require(vented.nodes().isEmpty() && vented.escapedSink().equals(captured.externalSource())
				&& vented.externalSource().equals(captured.externalSource()) && cold.exactInternalEnergy().equals(SignedDyadic.ZERO)
				&& cold.thermalAudit().expectedInventory().equals(SignedDyadic.ZERO), "opening a captured chamber lost its gas or energy sink");
		FluidDomain.restoreAuthoritativeState(cold.captureAuthoritativeState());
		var vacuum = FluidDomain.restoreAuthoritativeState(input);
		vacuum.commitOperatorVentSealedGas(vacuum.planOperatorVentSealedGas());
		require(vacuum.tryInsertExactVolume(0, 0, 0, 2, 3, 0, true).transferred()
				&& vacuum.exportGasState("").nodes().isEmpty() && vacuum.exactInternalEnergy().equals(vacuum.thermalAudit().expectedInventory()),
				"injection into explicit vacuum recreated gas or charged fictitious work");
		var filled = FluidDomain.restoreAuthoritativeState(vacuum.captureAuthoritativeState()); filled.removeAllWaterExact();
		require(filled.exportGasState("").nodes().size() == 1 && filled.exportGasState("").nodes().getFirst().inventory().isEmpty()
				&& filled.exactInternalEnergy().equals(SignedDyadic.ZERO), "removing vacuum-filled liquid recreated ambient stock");


	}

	private static void insertionNeverRoundsTheRequestUp() {
		FluidDomain domain = emptyMaterialDomain(1024);
		for (int c = 0; c < domain.cellCount; c++) domain.setVolume(c % 4, c / 4 % 4, c / 16, (c % 13 + 1) / 17.0F);
		for (int round = 0; round < 12; round++) {
			var before = cc.ecisr.mmm.units.SignedDyadic.sum(domain.particles.directArrays().volume, domain.particles.count());
			float request = 1.3F;
			var receipt = domain.addVolumeNearBudgeted(1, 1, 1, request, 10);
			var inserted = cc.ecisr.mmm.units.SignedDyadic.sum(domain.particles.directArrays().volume, domain.particles.count()).subtract(before);
			require(inserted.compareTo(cc.ecisr.mmm.units.SignedDyadic.of(request)) <= 0,
					"cross-cell float subtraction increased the requested source inventory");
			require(Math.abs(inserted.toDouble() - receipt.addedVolume()) <= 0.5 * Math.ulp(receipt.addedVolume()),
					"insertion receipt lost more than its final binary32 rounding");
		}
	}

	private static void insertionNeverRoundsCapacityUp() {
		float edge = Math.nextDown(Math.nextDown(Math.nextDown(0.375F)));
		for (float initial : new float[] {edge, Math.scalb(1.0F, -60)}) {
			FluidDomain domain = emptyMaterialDomain(64);
			domain.carrierMutations().appendMarker(CarrierMutationPlan.append(domain.carrierMutationRevision(), 0),
					domain.cellIndex(1, 1, 1), 0.625F, 0.625F, 0.625F, initial, 0, 1000.0 * initial, null, false, false);
			domain.addVolumeNearBudgeted(1, 1, 1, 1, 0);
			var actual = cc.ecisr.mmm.units.SignedDyadic.sum(domain.particles.directArrays().volume, domain.particles.count());
			require(actual.compareTo(cc.ecisr.mmm.units.SignedDyadic.of(1)) <= 0 && actual.toDouble() >= 1 - 8 * Math.ulp(1.0F),
					"operator insertion rounded a physical capacity gap upward");
		}
	}

	private static void volumeObservationRoundsTheExactInventoryOnce() {
		float[] values = new float[258]; values[0] = 1;
		java.util.Arrays.fill(values, 1, values.length, Math.scalb(1.0F, -60));
		var exact = cc.ecisr.mmm.units.SignedDyadic.ZERO;
		for (float value : values) exact = exact.add(cc.ecisr.mmm.units.SignedDyadic.of(value));
		require(exact.toDouble() == Math.nextUp(1.0), "wide-range volume fixture missed its accumulated small parcels");
		float[] positions = new float[values.length * 4]; double[] masses = new double[values.length];
		for (int i = 0; i < values.length; i++) {
			positions[4 * i] = positions[4 * i + 1] = positions[4 * i + 2] = i == 0 ? 0.125F : 0.625F;
			positions[4 * i + 3] = values[i]; masses[i] = 1000 * (double) values[i];
		}
		FluidDomain domain = emptyMaterialDomain(512);
		var exactMass = cc.ecisr.mmm.units.SignedDyadic.ZERO;
		for (double mass : masses) exactMass = exactMass.add(cc.ecisr.mmm.units.SignedDyadic.of(mass));
		domain.restoreMaterialParticleState(new FlipMaterialParticleState(4, 4, 4, 0.5F, values.length,
				exact.toDouble(), exactMass.toDouble(), domain.materialRegistry.materials(),
				positions, new float[values.length * 3], new byte[64], masses, new int[values.length]));
		require(domain.totalVolumeExact() == exact.toDouble() && domain.exportMaterialParticleState().totalVolume() == exact.toDouble(),
				"material observation or handoff dropped small physical parcel volumes");
		require(domain.totalMass() == exactMass.toDouble() && domain.exportMaterialParticleState().totalMass() == exactMass.toDouble(),
				"material observation or handoff dropped small physical parcel masses");
		float[] varied = new float[1024]; var oracle = cc.ecisr.mmm.units.SignedDyadic.ZERO;
		for (int i = 0; i < varied.length; i++) {
			varied[i] = Float.intBitsToFloat((i % 255 << 23) | (i * 15485863 & 0x7fffff) | (i % 3 == 0 ? Integer.MIN_VALUE : 0));
			oracle = oracle.add(cc.ecisr.mmm.units.SignedDyadic.of(varied[i]));
		}
		require(cc.ecisr.mmm.units.SignedDyadic.sum(varied, varied.length).equals(oracle),
				"binary32 inventory bins missed cancellation, subnormal or extreme finite values");
	}

	private static void readOnlyCellQueriesFollowCarrierEdits() {
		FluidDomain domain = emptyMaterialDomain(256);
		domain.setVolume(1, 1, 1, 1);
		domain.setVolume(2, 1, 1, 0.5F);
		for (int round = 0; round < 3; round++) {
			for (int particle = 0; particle < domain.particles.count(); particle++)
				domain.particles.setVelocityX(particle, round + 0.5F);
			boolean dirty = domain.gridDirty;
			var before = domain.observeCellInventory(1, 1, 1);
			require(domain.gridDirty == dirty, "query preserves grid rebuild scheduling");
			float[] previousVelocity = domain.mac.velocityX.clone();
			domain.volume(1, 1, 1);
			require(!domain.gridDirty && domain.velocityGridDirty, "inventory query prepares geometry and leaves velocity pending");
			require(java.util.Arrays.equals(previousVelocity, domain.mac.velocityX), "inventory query changed the velocity grid");
			FluidDomain reference = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
			reference.ensureGrid();
			domain.ensureGrid();
			require(!domain.velocityGridDirty, "mechanical read left velocity pending");
			require(java.util.Arrays.equals(reference.mac.velocityX, domain.mac.velocityX)
					&& java.util.Arrays.equals(reference.mac.velocityY, domain.mac.velocityY)
					&& java.util.Arrays.equals(reference.mac.velocityZ, domain.mac.velocityZ),
					"deferred velocity transfer differs from a complete restored rebuild");
			var after = domain.observeCellInventory(1, 1, 1);
			require(java.util.Objects.equals(before, after), "indexed query matches the authoritative full scan");
			require(!domain.gridDirty, "indexed observation keeps the grid clean");
			domain.setVolume(1, 1, 1, round == 0 ? 0 : 0.75F);
		}
	}

	private static FluidSolverConfig config() {
		return new FluidSolverConfig(0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F,
				120, 1.0E-4F, 0.40F, 2.0E-3F, 0.80F);
	}

	private static FluidDomain emptyMaterialDomain(int maximumParticles) {
		FluidDomain domain = new FluidDomain(4, 4, 4, config(), maximumParticles);
		domain.sealDomainBoundary();
		FluidMaterialRegistry registry = new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE);
		domain.restoreMaterialParticleState(new FlipMaterialParticleState(
				4, 4, 4, 0.5F, 0, 0.0, 0.0, registry.materials(),
				new float[0], new float[0], new byte[64], new double[0], new int[0]));
		return domain;
	}

	private static FluidDomain fullCapabilityDomain(FullCapabilityTestFixture.Registries registries) {
		FluidDomain domain = emptyMaterialDomain(4_096);
		FullCapabilityTestFixture.bindFull(domain, registries,
				GasRegistry.STANDARD_TEMPERATURE_K, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		return domain;
	}

	private static void appendThenRemoveLastClosesLedgers() {
		FluidDomain domain = emptyMaterialDomain(64);
		long revision = domain.carrierMutationRevision();
		int ordinal = domain.carrierMutations().appendMarker(
				CarrierMutationPlan.append(revision, domain.particles.count()),
				domain.cellIndex(1, 1, 1), 0.6F, 0.6F, 0.6F, 0.125F, 0, 125.0, null, false, false);
		require(ordinal == 0 && domain.particles.count() == 1, "append did not create the first ordinal");
		require(domain.carrierMutationRevision() == revision + 1, "append did not advance the revision");
		require(domain.totalVolumeExact() == 0.125 && domain.totalMass() == 125.0, "append ledger mismatch");
		require(domain.gridDirty, "append did not invalidate the marker grid");
		domain.ensureGrid();

		double removedEnergy = domain.carrierMutations().swapRemoveMarker(
				CarrierMutationPlan.swapRemove(domain.carrierMutationRevision(), 1, 0), false);
		require(removedEnergy == 0.0, "external append must not report internal energy");
		require(domain.particles.count() == 0 && domain.totalVolumeExact() == 0.0
						&& domain.totalMass() == 0.0,
				"remove-last did not close the exact ledgers");
		require(domain.carrierMutationRevision() == revision + 2, "remove did not advance the revision exactly once");
	}

	private static void removeMiddleSwapsAllColumnsBitwise() {
		FluidDomain domain = emptyMaterialDomain(4_096);
		domain.addVolumeNearBudgeted(1, 1, 1, 0.5F, 1);
		domain.addVolumeNearBudgeted(2, 2, 2, 0.5F, 1);
		require(domain.particles.count() == 8, "fixture did not create eight markers");
		int removed = 3, last = 7;
		float lastX = domain.particles.positionX(last), lastY = domain.particles.positionY(last),
				lastZ = domain.particles.positionZ(last), lastV = domain.particles.volume(last);
		double lastMass = domain.particles.mass(last);
		int lastHandle = domain.particles.materialHandle(last);
		float untouchedX = domain.particles.positionX(0), untouchedV = domain.particles.volume(0);

		domain.carrierMutations().swapRemoveMarker(
				CarrierMutationPlan.swapRemove(domain.carrierMutationRevision(), 8, removed), false);

		require(domain.particles.count() == 7, "swap-remove did not shrink the count");
		require(Float.floatToRawIntBits(domain.particles.positionX(removed)) == Float.floatToRawIntBits(lastX)
						&& Float.floatToRawIntBits(domain.particles.positionY(removed)) == Float.floatToRawIntBits(lastY)
						&& Float.floatToRawIntBits(domain.particles.positionZ(removed)) == Float.floatToRawIntBits(lastZ)
						&& Float.floatToRawIntBits(domain.particles.volume(removed)) == Float.floatToRawIntBits(lastV)
						&& Double.doubleToRawLongBits(domain.particles.mass(removed)) == Double.doubleToRawLongBits(lastMass)
						&& domain.particles.materialHandle(removed) == lastHandle,
				"swap-remove did not move the last marker's main columns bitwise");
		require(Float.floatToRawIntBits(domain.particles.positionX(0)) == Float.floatToRawIntBits(untouchedX)
						&& Float.floatToRawIntBits(domain.particles.volume(0)) == Float.floatToRawIntBits(untouchedV),
				"swap-remove disturbed an unrelated marker");
	}

	private static void sideColumnsSwapWithTheMainColumn() throws Exception {
		FullCapabilityTestFixture.Registries registries = FullCapabilityTestFixture.load();
		FluidDomain domain = fullCapabilityDomain(registries);
		domain.addVolumeNearBudgeted(1, 1, 1, 0.5F, 1);
		domain.addVolumeNearBudgeted(2, 2, 2, 0.5F, 1);
		int removed = 3, last = domain.particles.count() - 1;
		ChemicalMixtureRecord removedRecord = domain.chemicalParcels.record(removed);
		ChemicalMixtureRecord lastRecord = domain.chemicalParcels.record(last);
		double removedMarkerEnergy = thermalLiquidEnergy(domain, removed);
		double lastEnergy = thermalLiquidEnergy(domain, last);
		long speciesBefore = domain.totalChemicalSpeciesQuanta();
		cc.ecisr.mmm.units.SignedDyadic energyBefore = domain.exactInternalEnergy();

		double removedEnergy = domain.carrierMutations().swapRemoveMarker(
				CarrierMutationPlan.swapRemove(domain.carrierMutationRevision(),
						domain.particles.count(), removed), true);

		require(removedEnergy == removedMarkerEnergy,
				"internal swap-remove did not return the removed marker's energy");
		require(thermalLiquidEnergy(domain, removed) == lastEnergy,
				"swap-remove did not move the thermal energy column with the marker");
		require(sameMixture(domain.chemicalParcels.record(removed), lastRecord),
				"swap-remove did not move the chemistry record with the marker");
		require(domain.totalChemicalSpeciesQuanta() == speciesBefore - removedRecord.totalQuanta(),
				"swap-remove did not subtract exactly the removed marker's species ledger");
		require(domain.exactInternalEnergy().equals(
						energyBefore.subtract(cc.ecisr.mmm.units.SignedDyadic.of(removedEnergy))),
				"swap-remove did not remove exactly the returned marker energy");
	}

	private static boolean sameMixture(ChemicalMixtureRecord a, ChemicalMixtureRecord b) {
		if (a.totalQuanta() != b.totalQuanta() || a.entryCount() != b.entryCount()) return false;
		for (int entry = 0; entry < a.entryCount(); entry++) {
			if (a.speciesHandle(entry) != b.speciesHandle(entry) || a.amountAt(entry) != b.amountAt(entry)) {
				return false;
			}
		}
		return true;
	}

	private static double thermalLiquidEnergy(FluidDomain domain, int marker) {
		return domain.thermalState.liquidInternalEnergy(marker);
	}

	private static void capacityEdgeRejectsBeforeAnyWrite() {
		FluidDomain domain = emptyMaterialDomain(1);
		domain.carrierMutations().appendMarker(
				CarrierMutationPlan.append(domain.carrierMutationRevision(), 0),
				domain.cellIndex(1, 1, 1), 0.6F, 0.6F, 0.6F, 0.125F, 0, 125.0, null, false, false);
		long revision = domain.carrierMutationRevision();
		try {
			domain.carrierMutations().appendMarker(
					CarrierMutationPlan.append(revision, 1),
					domain.cellIndex(1, 1, 1), 0.6F, 0.6F, 0.6F, 0.125F, 0, 125.0, null, false, false);
			throw new AssertionError("append beyond the marker budget was accepted");
		} catch (FluidDomain.ParticleBudgetExceededException expected) {
			require(domain.particles.count() == 1 && domain.carrierMutationRevision() == revision,
					"budget rejection changed the carrier state");
		}
	}

	private static void stalePlansRejectWithoutStateChange() throws Exception {
		FullCapabilityTestFixture.Registries registries = FullCapabilityTestFixture.load();
		FluidDomain domain = fullCapabilityDomain(registries);
		domain.addVolumeNearBudgeted(1, 1, 1, 0.5F, 1);
		long staleRevision = domain.carrierMutationRevision();
		int count = domain.particles.count();
		domain.carrierMutations().appendMarker(
				CarrierMutationPlan.append(staleRevision, count),
				domain.cellIndex(2, 2, 2), 0.6F, 0.6F, 0.6F, 0.125F, 0, 125.0, null, false, false);
		require(domain.particles.count() == count + 1, "control append failed");
		try {
			domain.carrierMutations().appendMarker(
					CarrierMutationPlan.append(staleRevision, count),
					domain.cellIndex(2, 2, 2), 0.6F, 0.6F, 0.6F, 0.125F, 0, 125.0, null, false, false);
			throw new AssertionError("stale append plan was accepted");
		} catch (IllegalStateException expected) {
			require(domain.particles.count() == count + 1,
					"stale plan rejection changed the marker count");
		}
		try {
			domain.carrierMutations().swapRemoveMarker(
					CarrierMutationPlan.swapRemove(staleRevision, count, 0), false);
			throw new AssertionError("stale swap-remove plan was accepted");
		} catch (IllegalStateException expected) {
			require(domain.particles.count() == count + 1,
					"stale remove rejection changed the marker count");
		}
	}

	private static void wholesaleCountBindsRevisionAndBudget() throws Exception {
		FullCapabilityTestFixture.Registries registries = FullCapabilityTestFixture.load();
		FluidDomain domain = fullCapabilityDomain(registries);
		domain.addVolumeNearBudgeted(1, 1, 1, 0.5F, 1);
		int count = domain.particles.count();
		long staleRevision = domain.carrierMutationRevision();
		domain.carrierMutations().appendMarker(
				CarrierMutationPlan.append(staleRevision, count),
				domain.cellIndex(2, 2, 2), 0.6F, 0.6F, 0.6F, 0.125F, 0, 125.0, null, false, false);
		require(domain.particles.count() == count + 1, "control append failed");
		try {
			domain.carrierMutations().adoptWholesaleCount(
					CarrierMutationPlan.wholesaleCount(staleRevision, count), 0);
			throw new AssertionError("wholesale adoption accepted a stale plan");
		} catch (IllegalStateException expected) {
			require(domain.particles.count() == count + 1, "stale wholesale rejection changed the count");
		}
		long revision = domain.carrierMutationRevision();
		domain.carrierMutations().adoptWholesaleCount(
				CarrierMutationPlan.wholesaleCount(revision, count + 1), 2);
		require(domain.particles.count() == 2 && domain.carrierMutationRevision() == revision + 1,
				"wholesale adoption did not commit exactly once");
		try {
			domain.carrierMutations().adoptWholesaleCount(
					CarrierMutationPlan.wholesaleCount(domain.carrierMutationRevision(), 2),
					domain.maximumParticleCount() + 1);
			throw new AssertionError("wholesale adoption accepted a count beyond the budget");
		} catch (IllegalArgumentException expected) {
			require(domain.particles.count() == 2, "budget rejection changed the count");
		}
	}

	private static void chemicalRemapPublishesTheFinalBudget() {
		var registry = cc.ecisr.mmm.chemistry.BuiltinChemicalDefinitions.REGISTRY;
		var store = new cc.ecisr.mmm.chemistry.ChemicalParcelStore(registry,
				new cc.ecisr.mmm.units.SolverUnitScale(1), 4, 64);
		long limit = cc.ecisr.mmm.chemistry.ChemicalLimits.MAX_REGION_TOTAL_QUANTA;
		var small = new ChemicalMixtureRecord(new int[] {0}, new long[] {1}, 0);
		var large = new ChemicalMixtureRecord(new int[] {0}, new long[] {limit - 1}, 0);
		store.assign(0, small, false); store.assign(1, large, true); store.markDirtyCell(9);
		int first = store.mixtureHandle(0), second = store.mixtureHandle(1);
		var replacements = new ChemicalMixtureRecord[] {large, small}; boolean[] indicators = {true, false};
		var plan = store.planMarkerRemap(2, replacements, indicators);
		replacements[0] = small; indicators[0] = false;
		store.commitMarkerRemap(plan);
		require(store.totalQuanta() == limit && store.record(0).equals(large) && store.indicator(0)
				&& store.mixtureHandle(0) == first && store.mixtureHandle(1) == second
				&& java.util.Arrays.equals(store.dirtyCells(), new int[] {9}),
				"whole chemical remap lost the final budget, immutable plan, handles or dirty cells");
		long revision = store.mutationRevision();
		try {
			store.planMarkerRemap(2, new ChemicalMixtureRecord[] {large, large}, new boolean[2]);
			throw new AssertionError("over-budget chemical remap was accepted");
		} catch (IllegalArgumentException expected) {
			require(store.mutationRevision() == revision && store.totalQuanta() == limit,
					"chemical remap preflight changed the store");
		}
		var stale = store.planMarkerRemap(2, new ChemicalMixtureRecord[] {small}, new boolean[1]);
		store.updateIndicator(0, false);
		try {
			store.commitMarkerRemap(stale); throw new AssertionError("stale chemical remap was accepted");
		} catch (IllegalStateException expected) {
			require(store.pool().liveCount() == 2 && store.totalQuanta() == limit, "stale chemical remap changed inventory");
		}
		store.commitMarkerRemap(store.planMarkerRemap(2, new ChemicalMixtureRecord[] {large}, new boolean[] {true}));
		require(store.pool().liveCount() == 1 && !store.isAssigned(1) && store.totalQuanta() == limit - 1,
				"chemical remap shrink did not release surplus handles");
		store.commitMarkerRemap(store.planMarkerRemap(1, new ChemicalMixtureRecord[] {large, small}, new boolean[2]));
		require(store.pool().liveCount() == 2 && store.totalQuanta() == limit && store.isAssigned(1),
				"chemical remap growth did not adopt aligned handles");
		store.commitMarkerRemap(store.planMarkerRemap(2, new ChemicalMixtureRecord[0], new boolean[0]));
		require(store.pool().liveCount() == 0 && store.totalQuanta() == 0, "empty chemical remap retained inventory");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
