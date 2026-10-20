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
		continuousFluxMovesResolvedMinorityMaterial();
		continuousSlowFluxDoesNotStopAcrossSteps();
		sharedFluxPreservesAllQuantities();
		bulkVolumeReplacement();
		collisionPreservesGeometricTail();
		chemicalTailRetainsComposition();
		preparedRowsAndRecoveryAreAtomic();
		targetEnergyClosesAtStoredOwners();
		explicitMechanicalHeatIsOneAtomicCandidate();
		sharedFaceFluxUsesCellInventory();
		System.out.println("Liquid cell inventory validation passed: six-face quantities, atomic rows, recovery and U rounding");
	}
	private static void continuousFluxMovesResolvedMinorityMaterial() {
		var cells=new LiquidCellInventory(4,null);
		var stocks=new ArrayList<LiquidCellInventory.Stock>();
		for(int cell=0;cell<4;cell++){
			stocks.add(new LiquidCellInventory.Stock(new LiquidCellInventory.Key(cell,0,255),SignedDyadic.of(.99),990_000_000L,null,99,false));
			stocks.add(new LiquidCellInventory.Stock(new LiquidCellInventory.Key(cell,1,255),SignedDyadic.of(1).subtract(SignedDyadic.of(.99)),10_000_000L,null,1,false));
		}
		cells.planAdditions(stocks).commit();
		var plan=cells.planRoutes(circulatingComponentRoutes(cells,.001),null);
		for(int cell=0;cell<4;cell++){
			int source=cell;
			double oil=plan.transfers().stream().filter(t->t.source().cell()==source&&t.source().material()==1).mapToDouble(t->t.volume().toDouble()).sum();
			double water=plan.transfers().stream().filter(t->t.source().cell()==source&&t.source().material()==0).mapToDouble(t->t.volume().toDouble()).sum();
			require(oil>0&&Math.abs(oil/(oil+water)-.01)<.0001,"Resolved minority liquid stopped following its common component flow");
		}
		plan.commit();
		require(cells.exactVolume().equals(SignedDyadic.of(4))&&cells.massUnits()==4_000_000_000L,
				"Common material flux changed exact inventory");
	}
	private static void continuousSlowFluxDoesNotStopAcrossSteps() {
		var chemistry=BuiltinChemicalDefinitions.REGISTRY;var cells=new LiquidCellInventory(4,chemistry);
		var mixture=ChemicalMixtureRecord.fromStableAmounts(chemistry,Map.of(BuiltinChemicalDefinitions.H2O,1_000_000L),0);
		var stocks=new ArrayList<LiquidCellInventory.Stock>();
		for(int cell=0;cell<4;cell++)stocks.add(new LiquidCellInventory.Stock(new LiquidCellInventory.Key(cell,0,255),SignedDyadic.of(1),1_000_000_000L,mixture,100,false));
		cells.planAdditions(stocks).commit();
		double carried=0;var energy=SignedDyadic.ZERO;
		for(int step=0;step<16;step++){
			var plan=cells.planRoutes(circulatingComponentRoutes(cells,.00002),null);
			double volume=plan.transfers().stream().filter(t->t.source().cell()==0).mapToDouble(t->t.volume().toDouble()).sum();
			require(volume>0,"A positive slow component flux was rounded to zero");
			carried+=volume;energy=energy.add(plan.energyRoundoff());plan.commit();
		}
		long species=0;for(var stock:cells.stocks())species+=stock.mixture().totalQuanta();
		require(Math.abs(carried/(16*.00002)-1)<.01&&cells.exactVolume().equals(SignedDyadic.of(4))
				&&cells.massUnits()==4_000_000_000L&&species==4_000_000L
				&&cells.exactInternalEnergy().subtract(SignedDyadic.of(400)).equals(energy),
				"Continuous slow transport lost carried volume, integer mass, species or U");
	}
	private static List<LiquidCellInventory.SourceRoutes> circulatingComponentRoutes(LiquidCellInventory cells,double flow) {
		var routes=new PhaseQuantitySettlement.RouteBuffer();routes.reset(cells.stocks(),32);
		int[] successor={1,3,0,2};
		for(int source=0;source<routes.sourceCount();source++){
			int cell=routes.key(source).cell();
			routes.add(source,cell,successor[cell],255,flow);
			routes.add(source,-1,cell,255,routes.volume(source)-flow);
		}
		var capacities=new SignedDyadic[32];java.util.Arrays.fill(capacities,SignedDyadic.ZERO);
		for(int cell=0;cell<4;cell++)capacities[8*cell]=SignedDyadic.of(1);
		return PhaseQuantitySettlement.settle(routes,capacities,null,null,source->{throw new AssertionError("Balanced circulation expanded capacity alternatives");});
	}
	private static void bulkVolumeReplacement() {
		var config=new FluidSolverConfig(.5F,1000,0,.05F,120,1e-5F,.002F,.8F);
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
		var domain = new FluidDomain(3,3,3,new FluidSolverConfig(.5F,1000,0,.05F,120,1e-5F,.002F,.8F));
		domain.materialRegistry = new FluidMaterialRegistry(List.of(new FluidMaterial("test:tail",1000,"test:tail",0,Math.PI/2)));
		var key = new LiquidCellInventory.Key(13,0,255); var volume = SignedDyadic.of(1,-60);
		domain.liquidCells.planAdditions(List.of(new LiquidCellInventory.Stock(key,volume,0,null,0,false))).commit();
		domain.markCellInventoryChanged();
		var displaced = domain.setSolidMicroMaskMaterial(1,1,1,255);
		require(displaced.portions().size()==1 && displaced.portions().getFirst().massUnits()==0
				&& SignedDyadic.of(displaced.portions().getFirst().volume()).equals(volume), "Collision lost a zero-mass geometric tail");
		var restored = domain.domainInventory.tryInsertExactMixture(0,1,1,displaced.portions(),0,false,0);
		require(restored.transferred() && domain.liquidCells.exactVolume().equals(volume)
				&& domain.totalMassUnits()==0, "Collision tail could not return through the common inventory transaction");
	}
	private static void chemicalTailRetainsComposition() {
		var domain = new FluidDomain(3,3,3,new FluidSolverConfig(.5F,1000,0,.05F,120,1e-5F,.002F,.8F));
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
		try { domain.domainInventory.tryInsertExactChemical(0,1,1,displaced.ledger(),0,false,1); }
		catch(IllegalArgumentException expected) { rejected=true; }
		require(rejected && domain.liquidCells.snapshot().rows().equals(before.rows()),"Geometric chemical tail accepted nonzero U");
		var restored = domain.domainInventory.tryInsertExactChemical(0,1,1,displaced.ledger(),0,false,0);
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
		var waterRoutes = new ArrayList<LiquidCellInventory.TransportRoute>();
		var oilRoutes = new ArrayList<LiquidCellInventory.TransportRoute>();
		var waterRemaining=SignedDyadic.of(.75);var oilRemaining=SignedDyadic.of(.25);
		int[] neighbors = {12, 14, 10, 16, 4, 22};
		for (int face = 0; face < neighbors.length; face++) {
			var waterPart=SignedDyadic.of(.075);var oilPart=SignedDyadic.of(.025);
			waterRoutes.add(new LiquidCellInventory.TransportRoute(face,water,new LiquidCellInventory.Key(neighbors[face],0,255),waterPart));
			oilRoutes.add(new LiquidCellInventory.TransportRoute(face,oil,new LiquidCellInventory.Key(neighbors[face],1,255),oilPart));
			waterRemaining=waterRemaining.subtract(waterPart);oilRemaining=oilRemaining.subtract(oilPart);
		}
		waterRoutes.add(new LiquidCellInventory.TransportRoute(-1,water,water,waterRemaining));
		oilRoutes.add(new LiquidCellInventory.TransportRoute(-1,oil,oil,oilRemaining));
		var plan = cells.planRoutes(List.of(new LiquidCellInventory.SourceRoutes(cells.stock(water),waterRoutes),
				new LiquidCellInventory.SourceRoutes(cells.stock(oil),oilRoutes)),(source,masses,mixtures,remainder,energies)->{
			for(int i=0;i<masses.length;i++)if(i!=remainder)energies[i]=source.internalEnergy()*(masses[i]/(double)source.massUnits());
			// The provider's remainder slot is ignored and assigned by the inventory.
			energies[remainder]=Double.NaN;
		});
		require(cells.snapshot().rows().equals(initial.rows()), "Flux preparation mutated source inventory");
		plan.commit();
		require(cells.massUnits() == 20 && cells.exactVolume().equals(SignedDyadic.of(1))
				&& plan.energyChange().equals(plan.energyRoundoff())
				&&Math.abs(plan.energyRoundoff().toDouble())<=32*Math.ulp(.2)
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
		var next = restored.planRoutes(List.of(new LiquidCellInventory.SourceRoutes(restored.stock(key),List.of(
				new LiquidCellInventory.TransportRoute(0,key,new LiquidCellInventory.Key(1,0,255),SignedDyadic.of(.125)),
				new LiquidCellInventory.TransportRoute(-1,key,key,SignedDyadic.of(.375))))),null);
		next.commit(); require(restored.massUnits() == 10 && restored.exactVolume().equals(SignedDyadic.of(.5)), "Recovered transport changed inventory");
		boolean immutable = false; try { snapshot.rows().get(0).clear(); } catch (UnsupportedOperationException expected) { immutable = true; }
		require(immutable, "Liquid snapshot exposes a mutable cell row");
	}
	private static void sharedFaceFluxUsesCellInventory() {
		var cells = new LiquidCellInventory(12, null); var key = new LiquidCellInventory.Key(0, 0, 255);
		cells.planEdits(List.of(new LiquidCellInventory.Edit(key,
				new LiquidCellInventory.Stock(key, SignedDyadic.of(1), 100, null, 10, false)))).commit();
		var domain = new FluidDomain(3,2,2,new FluidSolverConfig(1,1000,0,.05F,120,1e-5F,.002F,.8F)); domain.liquidCells = cells;
		domain.markCellInventoryChanged();domain.domainFlowFields.ensureGrid();
		for(int face=0;face<domain.mac.velocityX.length;face++)if(domain.cellMacStage.massX[face]>0)domain.mac.velocityX[face]=.2F;
		domain.cellMacStage.publishMomentum();
		var before=cells.snapshot();
		var energyBefore=cells.exactInternalEnergy();
		var prepared=domain.cellMacStage.prepareLiquid(.1F);
		var plan=prepared.inventory();
		require(!prepared.inventory().transfers().isEmpty()&&cells.snapshot().rows().equals(before.rows()),
				"Shared face preparation bypassed transport or modified its source");
		plan.commit();
		for (int cell = 0; cell < 12; cell++) require(cells.exactCellVolume(cell).equals(plan.future().exactCellVolume(cell))
				&& cells.exactCellVolume(cell).compareTo(SignedDyadic.of(1)) <= 0, "Shared face and material inventory disagree on future volume");
		require(cells.massUnits() == 100 && cells.exactVolume().equals(SignedDyadic.of(1))
				// Joint MAC preparation includes mixing and this fixture's wall response heat.
				&& cells.exactInternalEnergy().subtract(energyBefore).equals(plan.mechanicalHeat().add(plan.energyRoundoff()))
				&&plan.energyChange().equals(plan.mechanicalHeat().add(plan.energyRoundoff())),
				"Shared face transport changed exact inventory or unbooked U");
	}
	private static void explicitMechanicalHeatIsOneAtomicCandidate() {
		var cells = new LiquidCellInventory(2, null); var key = new LiquidCellInventory.Key(0,0,255);
		cells.planEdits(List.of(new LiquidCellInventory.Edit(key,new LiquidCellInventory.Stock(key,SignedDyadic.of(.5),10,null,3,false)))).commit();
		var before = cells.snapshot();
		var prepared = cells.planRoutes(List.of(new LiquidCellInventory.SourceRoutes(cells.stock(key),List.of(
				new LiquidCellInventory.TransportRoute(0,key,new LiquidCellInventory.Key(1,0,255),SignedDyadic.of(.25)),
				new LiquidCellInventory.TransportRoute(-1,key,key,SignedDyadic.of(.25))))),null);
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
	private static void targetEnergyClosesAtStoredOwners() {
		var cells = new LiquidCellInventory(3, null); var edits = new ArrayList<LiquidCellInventory.Edit>();
		for (int cell = 0; cell < 3; cell++) {
			var key = new LiquidCellInventory.Key(cell, 0, 255);
			edits.add(new LiquidCellInventory.Edit(key, new LiquidCellInventory.Stock(key, SignedDyadic.of(.25), 10, null, cell == 1 ? 1e16 : 1, false)));
		}
		cells.planEdits(edits).commit();
		var sourceEnergy=cells.exactInternalEnergy();
		var plan = cells.planRoutes(List.of(
				new LiquidCellInventory.SourceRoutes(cells.stockAt(0),List.of(new LiquidCellInventory.TransportRoute(0,new LiquidCellInventory.Key(0,0,255),new LiquidCellInventory.Key(1,0,255),SignedDyadic.of(.25)))),
				new LiquidCellInventory.SourceRoutes(cells.stockAt(2),List.of(new LiquidCellInventory.TransportRoute(1,new LiquidCellInventory.Key(2,0,255),new LiquidCellInventory.Key(1,0,255),SignedDyadic.of(.25))))),null);
		plan.commit();
		require(cells.size() == 1 && Math.abs(cells.stockAt(0).internalEnergy()-sourceEnergy.toDouble())<=Math.ulp(sourceEnergy.toDouble())
				&&cells.exactInternalEnergy().subtract(sourceEnergy).equals(plan.energyRoundoff()),
				"Compensated target U changed energy outside floating resolution or its exact endpoint ledger");
	}
	private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

