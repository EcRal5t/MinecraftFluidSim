package mmm.fluid;

import mmm.inventory.LiquidCellInventory;
import mmm.inventory.LiquidTransportRoutes;

import mmm.thermal.ThermalCellGrid;
import mmm.thermal.ThermalMaterialDefinition;
import mmm.thermal.ThermalRegistry;
import mmm.thermal.ThermalTransport;
import mmm.units.SignedDyadic;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Cell temperature advection, finite conduction and exact owner-energy publication. */
final class LiquidThermalAdvectionValidation extends ThermalValidationSupport {
	public static void main(String[] args) throws Exception {
		integerSpeciesCarriesReferenceEnergy();
		if(args.length==1&&args[0].equals("--reference")){System.out.println("Liquid integer reference-energy transport passed");return;}
		bulkReplacementPublishesThermalInventory();
		advectedTemperatureRemainsBounded();
		zeroFluxRetainsCaloricReference();
		finiteConductivityLimit();
		disconnectedSupportsInsulate();
		cellDiffusionConverges();
		conductingLinearWallProfileIsStationary();
		insulatingCellsIgnoreUnrelatedFlux();
		System.out.println("Cell thermal advection passed: bounded temperature, carried U, finite conduction and recovery");
	}
	private static void integerSpeciesCarriesReferenceEnergy() throws Exception {
		var r=mmm.simulation.FullCapabilityTestFixture.load();
		var d=new FluidDomain(2,2,2,new FluidSolverConfig(.5F,1000,0,.05F,100,1e-5F,.002F));
		d.sealDomainBoundary();mmm.simulation.FullCapabilityTestFixture.bindEmptyMaterialState(d,r.liquids());
		mmm.simulation.FullCapabilityTestFixture.bindFull(d,r,293.15,ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		int water=r.liquids().handle("mmm:water");
		require(d.domainInventory.tryInsertExactVolume(0,0,0,1,0,water,true).transferred(),"Reference-energy source insertion failed");
		var before=d.liquidCells.snapshot();var volume=d.liquidCells.exactVolume();long mass=d.liquidCells.massUnits();
		var sourceKey=new LiquidCellInventory.Key(0,water,255);var movedVolume=SignedDyadic.of(1,-32);
		var routes=LiquidTransportRoutes.builder(1,2);routes.source(d.liquidCells.stock(sourceKey));
		routes.route(0,1,255,movedVolume);
		var plan=d.liquidCells.planRoutes(routes.build(),FluidThermalBinding.liquidEnergyPartitioner(d));
		require(before.rows().equals(d.liquidCells.snapshot().rows()),"Caloric transport changed committed rows during preparation");
		for(var stock:plan.future().physicalStocks()){
			double t=d.thermalState.stateFunction().stateForInternalEnergy(d.thermalState.thermalMaterialHandleForFluid(water),stock.massKilograms(),
					stock.mixture().stableAmounts(r.chemical()),stock.internalEnergy()).temperatureK();
			require(Math.abs(t-293.15)<1e-4,"Integer chemical reference energy changed transported temperature: "+t);
		}
		var gas=d.gasTopologyCoordinator.planAfterCarrierEdit(plan.future(),new long[d.cellCount]);
		plan.commit();d.thermalState.recordRoundoff(plan.energyRoundoff());d.markCellInventoryChanged();
		d.gasTopologyCoordinator.commitPrepared(gas,new long[d.cellCount]);
		require(d.liquidCells.exactVolume().equals(volume)&&d.liquidCells.massUnits()==mass&&d.exactInternalEnergy().equals(d.thermalAudit().expectedInventory()),"Caloric transport changed exact stock or audit");
		var restored=FluidDomain.restoreAuthoritativeState(d.captureAuthoritativeState());restored.thermalState.preparePhaseScan(restored.domainCapabilities.thermalMarkerContext());
		require(restored.liquidCells.snapshot().rows().equals(d.liquidCells.snapshot().rows()),"Caloric transport changed during recovery");
		// A sub-mass-quantum outflow still carries geometric volume. Its chemical
		// reference follows the accepted mass, keeping the remaining caloric owner valid.
		var tinyCells=new LiquidCellInventory(2,r.chemical());
		var tinyMixture=mmm.chemistry.ChemicalMixtureRecord.fromStableAmounts(r.chemical(),Map.of(mmm.chemistry.BuiltinChemicalDefinitions.H2O,2L),0);
		tinyMixture=tinyMixture.withMassResidualUnits(1-tinyMixture.speciesMassUnits(r.chemical()));
		int thermalMaterial=d.thermalState.thermalMaterialHandleForFluid(water);
		double tinyEnergy=d.thermalState.stateFunction().internalEnergyAtTemperature(thermalMaterial,mmm.units.Mass.kilograms(1),tinyMixture,r.chemical(),293.15);
		tinyCells.planAdditions(List.of(new LiquidCellInventory.Stock(sourceKey,SignedDyadic.of(1),1,tinyMixture,tinyEnergy))).commit();
		var tinyRoutes=LiquidTransportRoutes.builder(1,1);tinyRoutes.source(tinyCells.stock(sourceKey));tinyRoutes.route(0,1,255,SignedDyadic.of(.5));
		var tinyPlan=tinyCells.planRoutes(tinyRoutes.build(),FluidThermalBinding.liquidEnergyPartitioner(d));
		long tinySpecies=0;
		for(var stock:tinyPlan.future().stocks()){
			tinySpecies+=stock.mixture().amount(r.chemical().speciesHandle(mmm.chemistry.BuiltinChemicalDefinitions.H2O));
			if(stock.massUnits()==0){require(stock.internalEnergy()==0,"Zero-mass geometry acquired caloric energy");continue;}
			double t=d.thermalState.stateFunction().stateForInternalEnergy(thermalMaterial,stock.massKilograms(),stock.mixture().stableAmounts(r.chemical()),stock.internalEnergy()).temperatureK();
			require(Math.abs(t-293.15)<1e-4,"Sub-mass-quantum outflow detached chemical reference from its caloric carrier: "+t);
		}
		require(tinySpecies==2&&tinyPlan.future().massUnits()==1&&tinyPlan.future().exactVolume().equals(SignedDyadic.of(1))
				&&tinyPlan.future().exactInternalEnergy().subtract(SignedDyadic.of(tinyEnergy)).equals(tinyPlan.energyRoundoff()),
				"Sub-mass-quantum outflow changed exact mass, species, volume or U accounting");
	}
	private static void bulkReplacementPublishesThermalInventory() {
		var d=pool(3,3,3,0,false,0);
		for(int z=0;z<3;z++)for(int y=0;y<3;y++)for(int x=0;x<3;x++)d.setThermalTemperatureInCell(x,y,z,305);
		d.domainFlowFields.preparePhysicalState();Arrays.fill(d.mac.velocityX,.25F);d.cellMacStage.publishMomentum();
		float[] targets=new float[d.cellCount];Arrays.fill(targets,.5F);
		long revision=d.liquidCells.revision();d.setVolumes(targets);
		require(d.liquidCells.revision()==revision+1 && d.exactInternalEnergy().equals(d.thermalAudit().expectedInventory()),"Bulk source/sink U did not publish once");
		for(var stock:d.liquidCells.physicalStocks())require(Math.abs(temperature(d,stock)-300)<1e-4,"Bulk replacement retained old external U");
		d.domainFlowFields.preparePhysicalState();double before=d.thermalAudit().mechanicalWork().toDouble();Arrays.fill(targets,.75F);d.setVolumes(targets);
		require(d.thermalAudit().mechanicalWork().toDouble()>before && d.exactInternalEnergy().equals(d.thermalAudit().expectedInventory()),"Stationary bulk injection lost MAC mixing heat or gas energy");
		var restored=FluidDomain.restoreAuthoritativeState(d.captureAuthoritativeState());
		require(restored.liquidCells.snapshot().rows().equals(d.liquidCells.snapshot().rows()) && restored.exactInternalEnergy().equals(restored.thermalAudit().expectedInventory()),"Bulk thermal initialization did not restore");
	}

	private static void advectedTemperatureRemainsBounded() {
		for(boolean segmented:new boolean[]{false,true}) {
			var d=pool(6,3,2,0,segmented,0);
			for(int z=0;z<2;z++)for(int y=0;y<3;y++)for(int x=0;x<6;x++)d.setThermalTemperatureInCell(x,y,z,x<3?295:305);
			for(int z=0;z<2;z++)for(int y=0;y<3;y++){d.setVolume(0,y,z,0);d.setVolume(5,y,z,0);}
			var initialVolume=d.liquidCells.exactVolume();long initialMass=d.liquidCells.massUnits();
			for(int step=0;step<3;step++) {
				d.domainFlowFields.preparePhysicalState();Arrays.fill(d.mac.velocityX,.125F);d.cellMacStage.publishMomentum();
				var prior=d.liquidCells.snapshot();var plan=d.cellMacStage.prepareLiquid(.05F);
				require(!plan.inventory().transfers().isEmpty(),"Prescribed thermal translation produced no shared face transport");
				require(d.liquidCells.snapshot().rows().equals(prior.rows()),"Thermal transport modified source before publication");
				for(var stock:plan.inventory().future().physicalStocks()) {
					double t=temperature(d,stock);require(t>=295-1e-4&&t<=305+1e-4,"Temperature advection exceeded the caloric resolution and resolved mechanical heat: "+t);
				}
				d.cellMacStage.transport(.05F);
				require(d.liquidCells.exactVolume().equals(initialVolume)&&d.liquidCells.massUnits()==initialMass,"Temperature advection changed material inventory");
				require(d.exactInternalEnergy().equals(d.thermalAudit().expectedInventory()),"Carried energy or U rounding did not close");
				d=FluidDomain.restoreAuthoritativeState(d.captureAuthoritativeState());
			}
		}
	}
	private static void zeroFluxRetainsCaloricReference() {
		for(double reference:new double[]{0,3e6,-3e6}) {
			var d=pool(3,2,2,0,false,reference);d.setThermalTemperatureInCell(1,0,0,428);
			var before=d.liquidCells.snapshot();var plan=d.cellMacStage.prepareLiquid(.05F);
			require(plan.inventory().future().snapshot().rows().equals(before.rows()),"Stationary cell transport reconstructed caloric owners");
			plan.inventory().commit();require(Math.abs(d.thermalTemperatureInCell(1,0,0)-428)<1e-4,"Stationary reference energy changed temperature");
		}
	}
	private static void finiteConductivityLimit() {
		double previous=0;
		for(double conductivity:new double[]{0,.001,.01}) {
			var d=pool(2,2,2,conductivity,false,0);d.setThermalTemperatureInCell(0,0,0,270);d.setThermalTemperatureInCell(1,0,0,350);
			var before=d.liquidCells.snapshot();d.stepThermalOnly(1);double change=d.thermalTemperatureInCell(0,0,0)-270;
			if(conductivity==0)require(d.liquidCells.snapshot().rows().equals(before.rows()),"Zero conductivity changed cell U");
			else {require(change>0&&change<.001,"Finite conductivity caused instantaneous mixing: "+change);if(previous>0)require(Math.abs(change/previous-10)<.001,"Conduction lost linear small-k response");}
			previous=change;require(d.exactInternalEnergy().equals(d.thermalAudit().expectedInventory()),"Finite conduction audit failed");
		}
	}
	private static void disconnectedSupportsInsulate() {
		var d=new FluidDomain(2,2,2,new FluidSolverConfig(.5F,1000,0,.05F,100,1e-5F,.002F));
		var masks=new byte[8];Arrays.fill(masks,(byte)255);masks[0]=(byte)254;masks[7]=(byte)127;d.geometry.cutCells.restoreMicroMasks(masks);
		var edits=new java.util.ArrayList<LiquidCellInventory.Edit>();
		for(int support:new int[]{1,128}){var key=new LiquidCellInventory.Key(support==1?0:7,0,support);edits.add(new LiquidCellInventory.Edit(key,new LiquidCellInventory.Stock(key,SignedDyadic.of(.125),d.unitScale.massUnitsForNormalized(125),null,0)));}
		d.liquidCells.planEdits(edits).commit();d.markCellInventoryChanged();
		var caloric=new ThermalMaterialDefinition("test:thermal/cut",ThermalMaterialDefinition.CarrierKind.LIQUID,"mmm:water",300,0,1000,List.of(new ThermalMaterialDefinition.CaloricSegment(1,600,4000)));
		d.bindThermal(ThermalValidationSupport.thermalRegistry(thermalMaterials(List.of(caloric))),new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER)),300);
		for(int ordinal=0;ordinal<2;ordinal++)d.thermalState.resetMarkerTemperature(ordinal,ordinal==0?270:350,d.domainCapabilities.thermalMarkerContext(),d.exactInternalEnergy());
		var before=d.liquidCells.snapshot();d.stepThermalOnly(1000);
		require(d.liquidCells.snapshot().rows().equals(before.rows()),"Disjoint cut supports conducted through a cell-average shortcut");
	}
	private static void cellDiffusionConverges() {
		double coarse=0;
		for(int n:new int[]{8,16}) {
			var d=pool(2,n,2,4e6,false,0);double h=d.config.cellSize(),height=n*h,before=0;
			for(int z=0;z<2;z++)for(int y=0;y<n;y++)for(int x=0;x<2;x++)d.setThermalTemperatureInCell(x,y,z,300+2*Math.cos(Math.PI*(y+.5)*h/height));
			for(var stock:d.liquidCells.physicalStocks())before+=Math.cos(Math.PI*(stock.key().cell()/2%n+.5)*h/height)*(temperature(d,stock)-300);
			double dt=.0001;d.stepThermalOnly(dt);double after=0;
			for(var stock:d.liquidCells.physicalStocks())after+=Math.cos(Math.PI*(stock.key().cell()/2%n+.5)*h/height)*(temperature(d,stock)-300);
			double rate=(1-after/before)/dt*height*height,error=Math.abs(rate/(Math.PI*Math.PI)-1);
			require(error<.02,"Cell conduction has a macroscopic diffusivity bias: "+rate);if(n==8)coarse=error;else require(error<.3*coarse,"Cell conduction failed spatial convergence");
			require(d.exactInternalEnergy().equals(d.thermalAudit().expectedInventory()),"Diffusion owner-energy audit failed");
			System.out.printf("cell thermal diffusion N=%d normalizedRate=%g error=%g%n",n,rate,error);
		}
	}
	private static void conductingLinearWallProfileIsStationary() {
		var d=pool(2,6,2,4e6,false,0);double h=d.config.cellSize(),height=6*h;
		var boundary=new double[d.cellCount*6];Arrays.fill(boundary,Double.NaN);
		for(int z=0;z<2;z++)for(int x=0;x<2;x++){boundary[6*d.domainFlowFields.cellIndex(x,0,z)+2]=310;boundary[6*d.domainFlowFields.cellIndex(x,5,z)+3]=290;}
		for(int z=0;z<2;z++)for(int y=0;y<6;y++)for(int x=0;x<2;x++)d.setThermalTemperatureInCell(x,y,z,310-20*(y+.5)*h/height);
		d.replaceThermalBoundaryTemperatures(boundary);var result=d.stepThermalOnly(.001);double maximum=0;
		for(var stock:d.liquidCells.physicalStocks())maximum=Math.max(maximum,Math.abs(temperature(d,stock)-(310-20*(stock.key().cell()/2%6+.5)*h/height)));
		require(maximum<1e-4&&result.maximumAbsoluteTransferJ()>0&&d.exactInternalEnergy().equals(d.thermalAudit().expectedInventory()),"Conduction lost its affine wall solution: "+maximum);
	}
	private static double temperature(FluidDomain d,LiquidCellInventory.Stock stock) {
		return d.thermalState.stateFunction().stateForInternalEnergy(d.thermalState.thermalMaterialHandleForFluid(stock.key().material()),stock.massKilograms(),java.util.Map.of(),stock.internalEnergy()).temperatureK();
	}
	private static FluidDomain pool(int nx,int ny,int nz,double conductivity,boolean segmented,double reference) {
		var d=new FluidDomain(nx,ny,nz,new FluidSolverConfig(.5F,1000,0,.05F,100,1e-5F,.002F));
		for(int z=0;z<nz;z++)for(int y=0;y<ny;y++)for(int x=0;x<nx;x++)d.setVolume(x,y,z,1);
		var caloric=new ThermalMaterialDefinition("test:thermal/advection",ThermalMaterialDefinition.CarrierKind.LIQUID,"mmm:water",300,reference,conductivity,
				segmented?List.of(new ThermalMaterialDefinition.CaloricSegment(1,300,1000),new ThermalMaterialDefinition.CaloricSegment(300,600,100000)):List.of(new ThermalMaterialDefinition.CaloricSegment(1,600,4000)));
		d.bindThermal(ThermalValidationSupport.thermalRegistry(thermalMaterials(List.of(caloric))),new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER)),300);return d;
	}
	static void insulatingCellsIgnoreUnrelatedFlux() {
		for (double dt : new double[] {0.05, 1000}) {
			var insulating = new ThermalMaterialDefinition("test:thermal/insulating", ThermalMaterialDefinition.CarrierKind.LIQUID,
					"test:insulating", 300, 0, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(1, 500, 10)));
			var conducting = new ThermalMaterialDefinition("test:thermal/conducting", ThermalMaterialDefinition.CarrierKind.LIQUID,
					"test:conducting", 300, 0, 1000, insulating.caloricSegments());
			double[] energies = {-2990, 500, 500, -2990};
			var grid = new ThermalCellGrid(3);
			var owners=new ThermalCellGrid.CarrierAccess() {
				@Override public int carrierCount() { return 4; }
				@Override public int cell(int carrier) { return Math.max(0, carrier - 1); }
				@Override public ThermalMaterialDefinition material(int carrier) { return carrier < 2 ? insulating : conducting; }
				@Override public double mass(int carrier) { return 1; }
				@Override public double volume(int carrier) { return 1; }
				@Override public double baseEnergy(int carrier) { return 0; }
				@Override public double internalEnergy(int carrier) { return energies[carrier]; }
				@Override public void setInternalEnergy(int carrier, double value) { energies[carrier] = value; }
			};
			grid.rebuild(owners);
			var before = SignedDyadic.sum(energies);
			var stats = mmm.thermal.ThermalCarrierTransport.step(owners,List.of(new mmm.thermal.ThermalCarrierTransport.Contact(2,3,1000,Double.NaN)),dt,8);
			require(energies[0] == -2990 && energies[1] == 500 && energies[2] < 500 && energies[3] > -2990
					&& SignedDyadic.sum(energies).subtract(before).equals(stats.roundoffResidual()),
					"unrelated explicit/implicit contact equilibrated an insulating cell");
		}
	}

}
