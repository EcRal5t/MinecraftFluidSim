package mmm.fluid;

import mmm.inventory.LiquidCellInventory;

import mmm.chemistry.*;
import mmm.thermal.*;
import mmm.units.SignedDyadic;
import mmm.units.SolverUnitScale;
import java.util.*;

/** Production cell chemistry: conservation, finite budgets, local reactions and thermal recovery. */
final class ChemicalReactionCandidateValidation {
	private ChemicalReactionCandidateValidation() { }
	static void validate() {
		cellInventoryTransactions();lowQuantityTransactions();reactionHeatChangesTemperature();evaluationBudgetRotates();
	}
	static void cellInventoryTransactions() {
		for(boolean unavailable:new boolean[]{false,true}) {
			String a="test:cell/a",b="test:cell/b",liquid="test:cell/liquid",material="test:cell/thermal";
			var chemistry=new ChemicalRegistry(List.of(new ChemicalSpecies(a,Map.of("X",1),0,ExactRational.of(3),0),
					new ChemicalSpecies(b,Map.of("X",1),0,ExactRational.of(3),0)),List.of(
					new ChemicalPhaseDefinition("test:cell/phase",liquid,List.of(a,b),Map.of(a,ExactRational.ONE))),List.of(
					new ChemicalReaction("test:cell/isomer",ChemicalReaction.Kind.INSTANTANEOUS,"test:cell/phase",Map.of(a,1),Map.of(b,1),ExactRational.ZERO)));
			var liquids=new FluidMaterialRegistry(List.of(FluidMaterial.withSurfaceTension(liquid,1000,"test:cell/miscible",0,90)));
			var domain=new FluidDomain(3,3,3,new FluidSolverConfig(.5F,1000,0,.05F,100,1e-5F,.002F));
			domain.bindMaterialRegistry(liquids);domain.setVolume(1,1,1,.125F);domain.bindChemistry(chemistry);
			var air=new ThermalMaterialDefinition("test:cell/air",ThermalMaterialDefinition.CarrierKind.GAS,mmm.fluid.air.BuiltinGasDefinitions.AIR,300,0,.025,
					List.of(new ThermalMaterialDefinition.CaloricSegment(150,800,718)));
			var thermal=new ThermalRegistry(List.of(identityThermalMaterial(material,liquid),air),List.of(
					new ThermalSpeciesOffset("test:cell/oa",a,material,ExactRational.ZERO),
					new ThermalSpeciesOffset("test:cell/ob",b,material,unavailable?ExactRational.of(1_000_000_000):ExactRational.ZERO)),List.of(),chemistry.fingerprint(),"");
			domain.bindThermal(thermal,liquids,300);
			var before=domain.liquidCells.physicalStockAt(0);var u=domain.exactInternalEnergy();var momentum=domain.mac.captureMomentum();
			for(int pass=0;pass<2;pass++) {
				var stats=domain.domainStep.runThermalReactionPhaseAxis(false,0).chemistry();var actual=domain.liquidCells.stock(before.key());
				require(stats.changedStocks()==(pass>0?0:1),"Cell reaction progress or retry changed");
				require(actual.massUnits()==before.massUnits()&&actual.volume().equals(before.volume())
						&&actual.mixture().exactSpeciesMassKilograms(chemistry).equals(before.mixture().exactSpeciesMassKilograms(chemistry))
						&&actual.mixture().massResidualUnits()==before.mixture().massResidualUnits()
						&&actual.mixture().totalQuanta()==before.mixture().totalQuanta(),
						"Cell chemistry changed physical mass, residual or stoichiometric inventory");
				long remaining=actual.mixture().amount(chemistry.speciesHandle(a)),products=actual.mixture().amount(chemistry.speciesHandle(b));
				require(products>0&&(unavailable?remaining>0:remaining==0)&&domain.thermalTemperatureInCell(1,1,1)>0,
						"Energy-limited chemistry failed to publish a positive-temperature partial extent");
				require(domain.exactInternalEnergy().equals(u)&&domain.thermalAudit().expectedInventory().equals(u)
						&&Arrays.equals(momentum.copyX(),domain.mac.captureMomentum().copyX())
						&&Arrays.equals(momentum.copyY(),domain.mac.captureMomentum().copyY())
						&&Arrays.equals(momentum.copyZ(),domain.mac.captureMomentum().copyZ()),"Cell reaction changed energy or MAC momentum");
				require(domain.chemistry.hasDirtyCells()==unavailable,"Energy-limited reaction lost its retry state");
			}
			if(unavailable){
				long remaining=domain.liquidCells.stock(before.key()).mixture().amount(chemistry.speciesHandle(a));
				domain.setThermalTemperatureInCell(1,1,1,300);
				require(domain.domainStep.runThermalReactionPhaseAxis(false,0).chemistry().changedStocks()>0
						&&domain.liquidCells.stock(before.key()).mixture().amount(chemistry.speciesHandle(a))<remaining
						&&domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
						"Energy-limited reaction did not resume after finite reheating");
			}
		}
	}
	private static ChemicalMixtureRecord mixture(ChemicalRegistry registry, String id, long amount, double residual) {
		return ChemicalMixtureRecord.fromStableAmounts(registry, Map.of(id, amount), mmm.units.Mass.fromKilograms(residual));
	}

	private static ChemicalRegistry products(int count, ExactRational mass) {
		ArrayList<ChemicalSpecies> species = new ArrayList<>();
		ArrayList<String> ids = new ArrayList<>();
		species.add(new ChemicalSpecies("test:source", Map.of("A", count), 0, mass.multiply(count), 0));
		ids.add("test:source");
		TreeMap<String, Integer> products = new TreeMap<>();
		for (int i = 0; i < count; i++) {
			String id = "test:p" + i;
			ids.add(id);
			species.add(new ChemicalSpecies(id, Map.of("A", 1), 0, mass, 0));
			products.put(id, 1);
		}
		return new ChemicalRegistry(species, List.of(new ChemicalPhaseDefinition("test:phase", "test:liquid",
				ids, Map.of("test:source", ExactRational.ONE))), List.of(new ChemicalReaction(
				"test:reaction", ChemicalReaction.Kind.INSTANTANEOUS, "test:phase",
				Map.of("test:source", 1), products, ExactRational.ZERO)));
	}


	static void lowQuantityTransactions() {
		var registry=BuiltinChemicalDefinitions.REGISTRY;
		for(var source:List.of(List.of(mixture(registry,BuiltinChemicalDefinitions.H_PLUS,1,0),mixture(registry,BuiltinChemicalDefinitions.OH_MINUS,1,0)),
				List.of(mixture(registry,BuiltinChemicalDefinitions.H_PLUS,2,-1.9e-9),mixture(registry,BuiltinChemicalDefinitions.OH_MINUS,1,-1.69e-8)))) {
			var fixture=new Fixture(registry,source,new int[source.size()]);long mass=fixture.totalMass();
			require(fixture.step().changedStocks()>0&&!fixture.scheduler.hasDirtyCells(),"Low-quantity cell reaction did not finish");
			require(fixture.totalMass()==mass&&fixture.totals()[registry.speciesHandle(BuiltinChemicalDefinitions.H2O)]==1,"Low-quantity cell reaction lost mass or product");
		}
		var split=new Fixture(registry,List.of(mixture(registry,BuiltinChemicalDefinitions.H_PLUS,1,0),mixture(registry,BuiltinChemicalDefinitions.OH_MINUS,1,0)),new int[]{0,0},new int[]{1,128});
		require(split.step().changedStocks()==0&&split.totals()[registry.speciesHandle(BuiltinChemicalDefinitions.H2O)]==0,"Reaction crossed a fixed solid partition");
		var noop=new Fixture(registry,List.of(mixture(registry,BuiltinChemicalDefinitions.H2O,1001,.125)),new int[]{0});
		var before=noop.inventory.snapshot();require(noop.step().changedStocks()==0&&noop.inventory.snapshot().rows()==before.rows(),"No-op chemistry rewrote persistent inventory");
		var wide=products(17,ExactRational.ONE);var deferred=new Fixture(wide,List.of(mixture(wide,"test:source",1,0)),new int[]{0});
		before=deferred.inventory.snapshot();require(deferred.step().changedStocks()==0&&deferred.scheduler.hasDirtyCells()&&deferred.inventory.snapshot().rows()==before.rows(),"Species-capacity deferral changed its source");
		var tiny=products(2,ExactRational.parse("0.0001"));var small=new Fixture(tiny,List.of(mixture(tiny,"test:source",1,0)),new int[]{0});
		require(small.step().changedStocks()==1&&small.inventory.physicalStockAt(0).mixture().entryCount()==2,"Small integer mass reaction did not finish");
		var region=new Fixture(registry,List.of(mixture(registry,BuiltinChemicalDefinitions.HCL,750_000_000_000_000L,0),mixture(registry,BuiltinChemicalDefinitions.HCL,750_000_000_000_000L,0)),new int[]{0,1});
		require(region.step().changedStocks()==1&&region.scheduler.hasDirtyCells(),"Combined region species capacity did not admit its available partial extent");
		var old=region.inventory.stocksInCell(1).getFirst();var water=mixture(registry,BuiltinChemicalDefinitions.H2O,1,0);
		region.inventory.planEdits(List.of(new LiquidCellInventory.Edit(old.key(),new LiquidCellInventory.Stock(old.key(),old.volume(),water.massUnits(registry),water,0)))).commit();
		require(region.step().changedStocks()==1&&!region.scheduler.hasDirtyCells(),"Deferred cell did not finish after capacity became available");
	}

	static void reactionHeatChangesTemperature() {
		String a="test:heat/a",b="test:heat/b",liquid="test:heat/liquid",material="test:heat/thermal";
		var chemistry=new ChemicalRegistry(List.of(new ChemicalSpecies(a,Map.of("X",1),0,ExactRational.parse("0.125"),0),new ChemicalSpecies(b,Map.of("X",1),0,ExactRational.parse("0.125"),0)),
				List.of(new ChemicalPhaseDefinition("test:heat/phase",liquid,List.of(a,b),Map.of(a,ExactRational.ONE))),List.of(
				new ChemicalReaction("test:heat/reaction",ChemicalReaction.Kind.INSTANTANEOUS,"test:heat/phase",Map.of(a,1),Map.of(b,1),ExactRational.ZERO)));
		var fluids=new FluidMaterialRegistry(List.of(FluidMaterial.withSurfaceTension(liquid,1,"test:heat/miscible",0,90)));
		var domain=new FluidDomain(2,2,2,new FluidSolverConfig(.5F,1,0,.05F,100,1e-5F,.002F));
		domain.bindMaterialRegistry(fluids);domain.setVolume(0,0,0,1);domain.bindChemistry(chemistry);domain.setMarkerChemical(0,Map.of(a,ChemicalLimits.QUANTA_PER_MOL));
		var thermal=new ThermalRegistry(List.of(new ThermalMaterialDefinition(material,ThermalMaterialDefinition.CarrierKind.LIQUID,liquid,300,0,0,List.of(new ThermalMaterialDefinition.CaloricSegment(200,500,10))),
				new ThermalMaterialDefinition("test:heat/air",ThermalMaterialDefinition.CarrierKind.GAS,mmm.fluid.air.BuiltinGasDefinitions.AIR,300,0,.025,List.of(new ThermalMaterialDefinition.CaloricSegment(150,800,718)))),
				List.of(new ThermalSpeciesOffset("test:heat/oa",a,material,ExactRational.ZERO),new ThermalSpeciesOffset("test:heat/ob",b,material,ExactRational.of(-100))),List.of(),chemistry.fingerprint(),"");
		domain.bindThermal(thermal,fluids,300);var energy=domain.exactInternalEnergy();
		require(domain.domainStep.runThermalReactionPhaseAxis(false,0).chemistry().changedStocks()==1&&domain.exactInternalEnergy().equals(energy)
				&&Math.abs(domain.thermalState.temperature(0,domain.domainCapabilities.thermalMarkerContext())-380)<1e-9,"Reaction heat did not follow the conserved cell energy");
	}

	private static void evaluationBudgetRotates() {
		var species=new ArrayList<ChemicalSpecies>();var reactions=new ArrayList<ChemicalReaction>();var ids=new ArrayList<String>();
		for(int i=0;i<256;i++) {String id="test:s"+i;ids.add(id);species.add(new ChemicalSpecies(id,Map.of("A",1),0,ExactRational.ONE,0));
			if(i>0)reactions.add(new ChemicalReaction("test:r"+i,ChemicalReaction.Kind.INSTANTANEOUS,"test:phase",Map.of("test:s"+(i-1),1),Map.of(id,1),ExactRational.ZERO));}
		var registry=new ChemicalRegistry(species,List.of(new ChemicalPhaseDefinition("test:phase","test:liquid",ids,Map.of("test:s0",ExactRational.ONE))),reactions);
		int count=ChemicalLimits.MAX_REACTION_EVALUATIONS_PER_STEP/reactions.size()+1;var input=new ArrayList<ChemicalMixtureRecord>();int[] cells=new int[count];
		for(int i=0;i<count;i++){input.add(mixture(registry,"test:s0",1,0));cells[i]=i;}
		var fixture=new Fixture(registry,input,cells);
		require(fixture.step().changedStocks()==count-1&&fixture.scheduler.reactionCursor()==count-1,"Cell reaction budget did not save its next cursor");
		for(int i=0;i<count-1;i++)fixture.scheduler.markDirtyCell(i);fixture.step();
		require(fixture.inventory.stocksInCell(count-1).getFirst().mixture().amount(registry.speciesHandle("test:s255"))==1,"Rotating reaction budget starved the tail cell");
	}

	static final class Fixture {
		final ChemicalRegistry registry;
		final LiquidCellInventory inventory;
		final RegionChemistry scheduler;
		private final ChemicalReactionStage stage;
		Fixture(ChemicalRegistry registry,List<ChemicalMixtureRecord> inputs,int[] cells) {this(registry,inputs,cells,null);}
		Fixture(ChemicalRegistry registry,List<ChemicalMixtureRecord> inputs,int[] cells,int[] supports) {
			this.registry=registry;int sizeX=Math.max(2,Arrays.stream(cells).max().orElse(0)+1);
			int cellCount=Math.multiplyExact(sizeX,4);
			inventory=new LiquidCellInventory(cellCount,registry);scheduler=new RegionChemistry(registry,cellCount);
			for(int i=0;i<inputs.size();i++) {
				var key=new LiquidCellInventory.Key(cells[i],0,supports==null?255:supports[i]);var input=inputs.get(i);var old=inventory.stock(key);
				long mass=input.massUnits(registry);var amounts=new TreeMap<>(input.stableAmounts(registry));SignedDyadic volume=SignedDyadic.of(.125);
				if(old!=null){mass=Math.addExact(mass,old.massUnits());volume=volume.add(old.volume());for(var e:old.mixture().stableAmounts(registry).entrySet())amounts.merge(e.getKey(),e.getValue(),Math::addExact);}
				var mixture=ChemicalMixtureRecord.fromStableAmounts(registry,amounts,0);mixture=mixture.withMassResidualUnits(mass-mixture.speciesMassUnits(registry));
				inventory.planEdits(List.of(new LiquidCellInventory.Edit(key,new LiquidCellInventory.Stock(key,volume,mass,mixture,0)))).commit();scheduler.markDirtyCell(cells[i]);
			}
			var fluids=new FluidMaterialRegistry(List.of(FluidMaterial.withSurfaceTension(registry.phases().getFirst().materialId(),1000,"test:miscible",0,90)));
			var domain=new FluidDomain(sizeX,2,2,new FluidSolverConfig(1,1000,0,.05F,100,1e-5F,.002F));
			domain.bindMaterialRegistry(fluids);domain.liquidCells=inventory;domain.chemistry=scheduler;
			stage=new ChemicalReactionStage(domain);
		}
		RegionChemistry.ReactionStats step(){return stage.step();}
		long totalMass(){long mass=0;for(var stock:inventory.stocks())mass=Math.addExact(mass,stock.massUnits());return mass;}
		long[] totals(){long[] totals=new long[registry.species().size()];for(var stock:inventory.stocks())for(int i=0;i<stock.mixture().entryCount();i++)totals[stock.mixture().speciesHandle(i)]=Math.addExact(totals[stock.mixture().speciesHandle(i)],stock.mixture().amountAt(i));return totals;}
	}
	private static ThermalMaterialDefinition identityThermalMaterial(String id,String carrier){return new ThermalMaterialDefinition(id,ThermalMaterialDefinition.CarrierKind.LIQUID,carrier,300,0,0,List.of(new ThermalMaterialDefinition.CaloricSegment(100,500,1000)));}
	private static void require(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
