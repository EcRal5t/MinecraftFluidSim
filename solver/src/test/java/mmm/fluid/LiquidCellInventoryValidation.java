package mmm.fluid;

import mmm.inventory.LiquidCellInventory;

import mmm.chemistry.BuiltinChemicalDefinitions;
import mmm.chemistry.ChemicalMixtureRecord;
import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Short finite-volume inventory transaction checks, with no mechanical integration loop. */
public final class LiquidCellInventoryValidation {
	public static void main(String[] args) {
		sharedFluxPreservesAllQuantities();
		bulkVolumeReplacement();
		collisionPreservesGeometricTail();
		chemicalTailRetainsComposition();
		preparedRowsAndRecoveryAreAtomic();
		gasGeometryContinuesAcrossHandoff();
		targetEnergyRoundsOnce();
		explicitMechanicalHeatIsOneAtomicCandidate();
		closedCutFacesRejectBeforeMutation();
		sharedFaceFluxUsesCellInventory();
		System.out.println("Liquid cell inventory validation passed: six-face quantities, atomic rows, recovery and U rounding");
	}
	private static void bulkVolumeReplacement() {
		var config=new FluidSolverConfig(.5F,1000,0,.05F,120,1e-5F,.4F,.002F,.8F);
		var bulk=new FluidDomain(3,3,3,config);var single=new FluidDomain(3,3,3,config);
		byte[] masks=new byte[27];masks[0]=126;
		bulk.geometry.cutCells.restoreMicroMasks(masks);single.geometry.cutCells.restoreMicroMasks(masks);
		float[] volumes=new float[27];for(int c=0;c<27;c++)volumes[c]=c%4==0?1:.25F;
		long revision=bulk.liquidCells.revision();bulk.setVolumes(volumes);
		for(int c=0;c<27;c++)single.setVolume(c%3,c/3%3,c/9,volumes[c]);
		require(bulk.liquidCells.snapshot().rows().equals(single.liquidCells.snapshot().rows())
				&& bulk.liquidCells.revision()==revision+1 && bulk.gasState.frame==null,
				"Bulk replacement differs from cell targets or prepared gas before initialization");
		var before=bulk.liquidCells.snapshot();volumes[26]=Float.NaN;boolean rejected=false;
		try{bulk.setVolumes(volumes);}catch(IllegalArgumentException expected){rejected=true;}
		require(rejected && before.rows().equals(bulk.liquidCells.snapshot().rows()),"Invalid bulk target partially published");
		var bound=new FluidDomain(3,3,3,config);
		bound.bindMaterialRegistry(new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE));
		bound.bindChemistry(BuiltinChemicalDefinitions.REGISTRY);
		var materials=new int[27];java.util.Arrays.fill(volumes,.5F);for(int c=0;c<27;c++)materials[c]=c%2;
		bound.setVolumes(volumes,materials);
		for(var stock:bound.liquidCells.stocks())require(stock.massUnits()==bound.unitScale.massUnitsForNormalized(.5*bound.materialRegistry.material(stock.key().material()).referenceDensity())
				&& stock.mixture().massUnits(BuiltinChemicalDefinitions.REGISTRY)==stock.massUnits(),"Bulk material quantities did not use actual phase density");
		var restored=FluidDomain.restoreAuthoritativeState(bound.captureAuthoritativeState());
		require(restored.liquidCells.snapshot().rows().equals(bound.liquidCells.snapshot().rows()),"Bulk cell state did not restore");
	}

	private static void collisionPreservesGeometricTail() {
		var domain = new FluidDomain(3,3,3,new FluidSolverConfig(.5F,1000,0,.05F,120,1e-5F,.4F,.002F,.8F));
		domain.materialRegistry = new FluidMaterialRegistry(List.of(new FluidMaterial("test:tail",1000,"test:tail",0,Math.PI/2)));
		var key = new LiquidCellInventory.Key(13,0,255); var volume = SignedDyadic.of(1,-60);
		domain.liquidCells.planAdditions(List.of(new LiquidCellInventory.Stock(key,volume,0,null,0,false))).commit();
		domain.markCellInventoryChanged();
		var displaced = domain.setSolidMicroMaskMaterial(1,1,1,255);
		require(displaced.portions().size()==1 && displaced.portions().getFirst().massUnits()==0
				&& SignedDyadic.of(displaced.portions().getFirst().volume()).equals(volume), "Collision lost a zero-mass geometric tail");
		var restored = domain.tryInsertExactMixture(0,1,1,displaced.portions(),0,false,0);
		require(restored.transferred() && domain.liquidCells.exactVolume().equals(volume)
				&& domain.totalMassUnits()==0, "Collision tail could not return through the common inventory transaction");
	}
	private static void chemicalTailRetainsComposition() {
		var domain = new FluidDomain(3,3,3,new FluidSolverConfig(.5F,1000,0,.05F,120,1e-5F,.4F,.002F,.8F));
		domain.materialRegistry = new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER));
		var chemistry = BuiltinChemicalDefinitions.REGISTRY;
		domain.bindChemistry(chemistry);
		var mixture = ChemicalMixtureRecord.fromStableAmounts(chemistry,Map.of(BuiltinChemicalDefinitions.H2O,1L),0);
		mixture = mixture.withMassResidualUnits(-mixture.speciesMassUnits(chemistry));
		var key = new LiquidCellInventory.Key(13,0,255); var volume = SignedDyadic.of(1,-60);
		domain.liquidCells.planAdditions(List.of(new LiquidCellInventory.Stock(key,volume,0,mixture,0,false))).commit();
		domain.markCellInventoryChanged();
		var displaced = domain.setSolidMicroMaskChemical(1,1,1,255);
		var before = domain.liquidCells.snapshot(); boolean rejected = false;
		try { domain.tryInsertExactChemical(0,1,1,displaced.ledger(),0,false,1); }
		catch(IllegalArgumentException expected) { rejected=true; }
		require(rejected && domain.liquidCells.snapshot().rows().equals(before.rows()),"Geometric chemical tail accepted nonzero U");
		var restored = domain.tryInsertExactChemical(0,1,1,displaced.ledger(),0,false,0);
		require(restored.transferred() && domain.liquidCells.exactVolume().equals(volume)
				&& domain.totalMassUnits()==0 && domain.liquidCells.physicalSize()==0
				&& domain.liquidCells.stocks().getFirst().mixture().equals(mixture),"Chemical geometric tail lost species or signed residual");
	}

	private static void sharedFluxPreservesAllQuantities() {
		var chemistry = BuiltinChemicalDefinitions.REGISTRY;
		var cells = new LiquidCellInventory(27, chemistry);
		var water = new LiquidCellInventory.Key(13, 0, 255);
		var oil = new LiquidCellInventory.Key(13, 1, 255);
		var mixture = ChemicalMixtureRecord.fromStableAmounts(chemistry, Map.of(BuiltinChemicalDefinitions.H2O, 7L), 0);
		cells.planEdits(List.of(new LiquidCellInventory.Edit(water,
				new LiquidCellInventory.Stock(water, SignedDyadic.of(.75), 17, mixture, .1, true)),
				new LiquidCellInventory.Edit(oil, new LiquidCellInventory.Stock(oil, SignedDyadic.of(.25), 3, null, -.2, false)))).commit();
		var initial = cells.snapshot();
		var fluxes = new ArrayList<LiquidCellInventory.FaceFlux>();
		int[] neighbors = {12, 14, 10, 16, 4, 22};
		for (int face = 0; face < neighbors.length; face++) {
			fluxes.add(new LiquidCellInventory.FaceFlux(face, 13, neighbors[face], 255, 255, 0, SignedDyadic.of(.075)));
			fluxes.add(new LiquidCellInventory.FaceFlux(face, 13, neighbors[face], 255, 255, 1, SignedDyadic.of(.025)));
		}
		var plan = cells.planTransport(fluxes, 3, 3, new int[27]);
		require(cells.snapshot().rows().equals(initial.rows()), "Flux preparation mutated source inventory");
		plan.commit();
		require(cells.massUnits() == 20 && cells.exactVolume().equals(SignedDyadic.of(1))
				&& plan.energyChange().equals(plan.energyRoundoff())
				&& cells.exactInternalEnergy().subtract(SignedDyadic.of(.1).add(SignedDyadic.of(-.2))).equals(plan.energyRoundoff()),
				"Six-face transport did not preserve mass, volume and carried U");
		long species = 0;
		for (var stock : cells.stocks()) if (stock.mixture() != null) {
			species += stock.mixture().amount(chemistry.speciesHandle(BuiltinChemicalDefinitions.H2O));
			require(stock.mixture().massUnits(chemistry) == stock.massUnits(), "Mixture residual differs from actual integer mass");
		}
		require(species == 7 && cells.stocks().stream().anyMatch(stock -> stock.massUnits() == 0)
				&& cells.physicalStocks().stream().allMatch(stock -> stock.massUnits() > 0),
				"Trace species or zero-mass geometric tails were lost or became physical carriers");
		long transported = plan.transfers().stream().mapToLong(LiquidCellInventory.Transfer::massUnits).sum();
		require(transported + cells.stocksInCell(13).stream().mapToLong(LiquidCellInventory.Stock::massUnits).sum() == 20,
				"MAC transfer mass differs from source integer mass removal");
	}
	private static void preparedRowsAndRecoveryAreAtomic() {
		var cells = new LiquidCellInventory(8, null); var key = new LiquidCellInventory.Key(0, 0, 255);
		cells.planEdits(List.of(new LiquidCellInventory.Edit(key, new LiquidCellInventory.Stock(key, SignedDyadic.of(.5), 10, null, 3, false)))).commit();
		var snapshot = cells.snapshot(); var stale = cells.planEnergyUpdate(new double[]{4});
		long geometryRevision = cells.geometryRevision();
		cells.planEnergyUpdate(new double[]{5}).commit();
		boolean rejected = false; try { stale.commit(); } catch (IllegalStateException expected) { rejected = true; }
		require(rejected && cells.stockAt(0).internalEnergy() == 5 && snapshot.stockAt(0).internalEnergy() == 3
				&& cells.geometryRevision() == geometryRevision,
				"A stale plan changed committed stock or an immutable snapshot");
		var restored = new LiquidCellInventory(snapshot, null);
		require(restored.ordinalOf(key) == 0 && restored.stockAt(0).equals(snapshot.stockAt(0)), "Recovery changed cell inventory ordinals");
		var next = restored.planTransport(List.of(new LiquidCellInventory.FaceFlux(0, 0, 1, 255, 255, 0, SignedDyadic.of(.125))), 2, 2, new int[8]);
		next.commit(); require(restored.massUnits() == 10 && restored.exactVolume().equals(SignedDyadic.of(.5)), "Recovered transport changed inventory");
		boolean immutable = false; try { snapshot.rows().get(0).clear(); } catch (UnsupportedOperationException expected) { immutable = true; }
		require(immutable, "Liquid snapshot exposes a mutable cell row");
	}
	private static void sharedFaceFluxUsesCellInventory() {
		var cells = new LiquidCellInventory(12, null); var key = new LiquidCellInventory.Key(0, 0, 255);
		cells.planEdits(List.of(new LiquidCellInventory.Edit(key,
				new LiquidCellInventory.Stock(key, SignedDyadic.of(1), 100, null, 10, false)))).commit();
		var flux = new ConservativeLiquidFlux(3, 2, 2);
		var domain = new FluidDomain(3,2,2,new FluidSolverConfig(1,1000,0,.05F,120,1e-5F,.4F,.002F,.8F)); domain.liquidCells = cells;
		domain.markCellInventoryChanged(); domain.ensurePressureGeometry();
		flux.geometry(new LiquidFluxGeometry(domain));
		var volume = new double[12]; var exact = new SignedDyadic[12];
		for (int cell = 0; cell < 12; cell++) volume[cell] = (exact[cell] = cells.exactCellVolume(cell)).toDouble();
		for (int face = 0; face < flux.flux.length; face++) if (flux.axis[face] == 0) flux.velocity[face] = .2;
		flux.plan(volume, new boolean[12], .1, exact);
		var transfers = new ArrayList<LiquidCellInventory.FaceFlux>();
		for (int face = 0; face < flux.flux.length; face++) if (flux.exactFlux[face].signum() > 0)
			transfers.add(new LiquidCellInventory.FaceFlux(face, flux.donor(face), flux.receiver(face), 255, 255, 0, flux.exactFlux[face]));
		var plan = cells.planTransport(transfers, 3, 2, new int[12]);
		require(!transfers.isEmpty() && cells.size() == 1, "Shared face preparation bypassed transport or modified its source");
		plan.commit();
		for (int cell = 0; cell < 12; cell++) require(cells.exactCellVolume(cell).equals(flux.resultingVolumes()[cell])
				&& cells.exactCellVolume(cell).compareTo(SignedDyadic.of(1)) <= 0, "Shared face and material inventory disagree on future volume");
		require(cells.massUnits() == 100 && cells.exactVolume().equals(SignedDyadic.of(1))
				&& cells.exactInternalEnergy().subtract(SignedDyadic.of(10)).equals(plan.energyRoundoff()),
				"Shared face transport changed exact inventory or unbooked U");
	}
	private static void explicitMechanicalHeatIsOneAtomicCandidate() {
		var cells = new LiquidCellInventory(2, null); var key = new LiquidCellInventory.Key(0,0,255);
		cells.planEdits(List.of(new LiquidCellInventory.Edit(key,new LiquidCellInventory.Stock(key,SignedDyadic.of(.5),10,null,3,false)))).commit();
		var before = cells.snapshot();
		var prepared = cells.planTransport(List.of(new LiquidCellInventory.FaceFlux(0,0,1,255,255,0,SignedDyadic.of(.25))),2,1,new int[2]);
		var heated = prepared.withMechanicalHeat(Map.of(0,SignedDyadic.of(.1),1,SignedDyadic.of(.2)));
		require(cells.snapshot().rows().equals(before.rows()), "Mechanical heat changed its source before publication");
		require(heated.mechanicalHeat().equals(SignedDyadic.of(.1).add(SignedDyadic.of(.2)))
				&& heated.energyChange().equals(heated.mechanicalHeat().add(heated.energyRoundoff()))
				&& heated.transfers().equals(prepared.transfers()), "Explicit heat was mixed with mass transfer or roundoff");
		heated.commit();
		require(cells.massUnits()==10 && cells.exactInternalEnergy().subtract(SignedDyadic.of(3)).equals(heated.energyChange()),"Mechanical heat candidate did not publish once");
		boolean rejected=false; try { prepared.commit(); } catch (IllegalStateException expected) { rejected=true; }
		require(rejected,"An unheated predecessor could publish after the complete heated candidate");
	}
	private static void gasGeometryContinuesAcrossHandoff() {
		var domain = new FluidDomain(3,3,3,new FluidSolverConfig(.5F,1000,0,.05F,120,1e-5F,.4F,.002F,.8F));
		domain.setVolume(1,1,1,.5F); domain.gasTopologyCoordinator.refresh();
		domain.setVolume(1,1,1,.375F);
		var frame = domain.gasState.frame; var before = domain.captureAuthoritativeState();
		var restored = FluidDomain.restoreAuthoritativeState(before);
		restored.gasTopologyCoordinator.refresh();
		require(restored.gasState.frame == frame && restored.gasBoundary().auditClosed(),
				"Backend handoff rebuilt unchanged validated gas geometry");
		restored.liquidCells.planEnergyUpdate(new double[]{10}).commit(); restored.markCellInventoryChanged();
		restored.gasTopologyCoordinator.refresh();
		require(restored.gasState.frame == frame && restored.totalMassUnits() == domain.totalMassUnits()
				&& restored.liquidCells.exactVolume().equals(domain.liquidCells.exactVolume()),
				"Energy-only publication changed geometry or physical inventory");
		restored.setVolume(1,1,1,.25F);
		require(restored.gasState.frame != frame && restored.gasBoundary().auditClosed()
				&& restored.liquidCells.exactVolume().equals(SignedDyadic.of(.25)),
				"A volume edit retained stale gas geometry");
	}
	private static void targetEnergyRoundsOnce() {
		var cells = new LiquidCellInventory(3, null); var edits = new ArrayList<LiquidCellInventory.Edit>();
		for (int cell = 0; cell < 3; cell++) {
			var key = new LiquidCellInventory.Key(cell, 0, 255);
			edits.add(new LiquidCellInventory.Edit(key, new LiquidCellInventory.Stock(key, SignedDyadic.of(.25), 10, null, cell == 1 ? 1e16 : 1, false)));
		}
		cells.planEdits(edits).commit();
		var plan = cells.planTransport(List.of(
				new LiquidCellInventory.FaceFlux(0, 0, 1, 255, 255, 0, SignedDyadic.of(.25)),
				new LiquidCellInventory.FaceFlux(1, 2, 1, 255, 255, 0, SignedDyadic.of(.25))), 3, 1, new int[3]);
		plan.commit();
		require(cells.size() == 1 && cells.stockAt(0).internalEnergy() == 1e16 + 2 && plan.energyRoundoff().signum() == 0,
				"Incoming U was rounded per transfer instead of once per target stock");
	}
	private static void closedCutFacesRejectBeforeMutation() {
		var cells = new LiquidCellInventory(2, null); var key = new LiquidCellInventory.Key(0, 0, 1);
		cells.planEdits(List.of(new LiquidCellInventory.Edit(key, new LiquidCellInventory.Stock(key, SignedDyadic.of(.125), 1, null, 1, false)))).commit();
		var before = cells.snapshot(); boolean rejected = false;
		try { cells.planTransport(List.of(new LiquidCellInventory.FaceFlux(0, 0, 1, 1, 2, 0, SignedDyadic.of(.0625))), 2, 1, new int[]{254, 253}); }
		catch (IllegalArgumentException expected) { rejected = true; }
		require(rejected && before.rows().equals(cells.snapshot().rows()), "A closed cut face moved inventory");
	}
	private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

