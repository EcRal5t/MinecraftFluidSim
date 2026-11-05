package mmm.thermal;

import mmm.chemistry.ExactRational;
import mmm.fluid.air.*;
import java.util.List;

/** Gas equation of state and external inventory edits. */
public final class GasTransferValidation {
	private GasTransferValidation() { }
	public static void main(String[] args) {
		if (args.length == 1 && args[0].equals("--external")) {
			localExternalGasEdits(); System.out.println("Local external gas editing validation passed"); return;
		}
		if (args.length == 1 && args[0].equals("--eos")) {
			pressureVolumeWork(); System.out.println("Gas pressure-volume EOS validation passed"); return;
		}
		if (args.length != 0) throw new IllegalArgumentException("Expected no arguments, --eos or --external");
		validate(); System.out.println("Global gas flow validation passed");
	}
	public static void validate() {
		pressureVolumeWork();
		localExternalGasEdits();
	}

	/** Player gas edits retain all integer columns and share the thermal source/sink transaction. */
	private static void localExternalGasEdits() {
		mmm.simulation.FullCapabilityTestFixture.Registries registries;
		try { registries = mmm.simulation.FullCapabilityTestFixture.load(); }
		catch (Exception failure) { throw new IllegalStateException("Cannot load gas edit fixture", failure); }
		var domain = new mmm.fluid.HostFluidState(4, 4, 4,
				new mmm.fluid.FluidSolverConfig(.5F, 1000, 0, .025F, 200, 1e-5F, .002F));
		domain.sealDomainBoundary();
		mmm.simulation.FullCapabilityTestFixture.bindEmptyMaterialState(domain, registries.liquids());
		domain.setVolume(1, 1, 1, .5F);
		mmm.simulation.FullCapabilityTestFixture.bindFull(domain, registries, 293.15, 8);
		int node = domain.gasNodeAt(.625, .875, .625);
		require(node > 0 && domain.gasNodeAt(.625, .55, .625) == -1,
				"Local gas editing did not distinguish the dry and wet PLIC sides");
		long before = domain.gasBoundary().standardVolumeNanos(node);
		var sourceBefore = domain.thermalAudit().externalSource();
		var inserted = domain.editGas(node, BuiltinGasDefinitions.OXYGEN, 123_456_789, 500, false);
		require(inserted.moved().standardVolumeNanos() == 123_456_789
				&& domain.gasBoundary().standardVolumeNanos(node) == before + 123_456_789
				&& domain.thermalAudit().externalSource().subtract(sourceBefore).equals(inserted.internalEnergy()),
				"Gas insertion lost quantity or source energy");
		var inventory = domain.gasBoundary().inventory(node);
		var energy = domain.exactInternalEnergy();
		var audit = domain.thermalAudit();
		boolean rejected = false;
		try { domain.editGas(node, BuiltinGasDefinitions.OXYGEN, 100, Double.NaN, false); }
		catch (IllegalArgumentException expected) { rejected = true; }
		require(rejected && inventory.equals(domain.gasBoundary().inventory(node))
				&& domain.exactInternalEnergy().equals(energy) && domain.thermalAudit().equals(audit),
				"Rejected gas insertion published inventory or energy");
		var oxygen = inventory.component(BuiltinGasDefinitions.OXYGEN);
		var sinkBefore = audit.externalSink();
		var removed = domain.editGas(node, BuiltinGasDefinitions.OXYGEN, Long.MAX_VALUE, 293.15, true);
		require(removed.moved().components().equals(List.of(oxygen))
				&& domain.gasBoundary().inventory(node).component(BuiltinGasDefinitions.OXYGEN) == null
				&& domain.thermalAudit().externalSink().subtract(sinkBefore).equals(removed.internalEnergy()),
				"Component extraction exceeded its inventory or changed carried columns");
		var mixed = domain.gasBoundary().inventory(node);
		var beforeVacuum=domain.captureAuthoritativeState().spatialGas();
		var emptied = domain.editGas(node, null, Long.MAX_VALUE, 293.15, true);
		var afterVacuum=domain.captureAuthoritativeState().spatialGas();
		require(emptied.moved().equals(mixed) && domain.gasBoundary().inventory(node).isEmpty()
				&& afterVacuum.energyAt(node-1)==0,
				"Complete mixture extraction did not leave a zero-energy vacuum");
		for(int cell=0;cell<beforeVacuum.cellCount();cell++)if(cell!=node-1)
			require(beforeVacuum.cells().get(cell).equals(afterVacuum.cells().get(cell))
					&& beforeVacuum.energyAt(cell)==afterVacuum.energyAt(cell),
					"Local mixture extraction changed another gas cell");
		domain.editGas(node, null, 1_000_000_000, 293.15, false);
		require(domain.gasBoundary().auditClosed()
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"Local gas source/sink and thermal audit do not close");
	}

	/** Local pressure, storage and finite-step work use the same bounded EOS. */
	private static void pressureVolumeWork() {
		var gas = new GasRegistry(List.of(new GasComponentDefinition("test:gas",1,List.of(),List.of())),
				List.of(new GasRegistry.AmbientFraction("test:gas",ExactRational.ONE)));
		var inventory=gas.createAmbient(1_000_000_000L);
		double volume0=.75,temperature0=300;
		double r=GasRegistry.STANDARD_PRESSURE_PA/GasRegistry.STANDARD_TEMPERATURE_K;
		var model=caloricModel(gas,List.of(new ThermalMaterialDefinition.CaloricSegment(1,400,718),
				new ThermalMaterialDefinition.CaloricSegment(400,2000,900)));
		double energy0=model.internalEnergyAtTemperature(inventory,temperature0);
		var prescribed=model.inventoryAtPressure(.125,5_000_000,temperature0,gas.ambient());
		close(model.absolutePressure(prescribed,.125,model.internalEnergyAtTemperature(prescribed,temperature0)),
				5_000_000,"Created gas pressure differs from the common EOS");
		double pressure0=model.absolutePressure(inventory,volume0,energy0);
		var thermal=model.state(inventory,energy0);
		for(double liquidReference:new double[]{0,.12,.125}) {
			for(double advectedVolume:new double[]{volume0,.01}) {
				double cv=thermal.heatCapacityJPerK();
				var work=MixtureVolumeModel.gasWorkState(.125,liquidReference,r,cv,temperature0,advectedVolume);
				var settled=MixtureVolumeModel.evaluate(.125,liquidReference,0,r,work.temperatureK(),1.4,.05,.5);
				close(settled.absolutePressurePa(),work.absolutePressurePa(),"Joint gas work pressure differs from EOS");
				close(settled.gasVolumeM3(),work.gasVolumeM3(),"Joint gas work volume differs from EOS");
				checkAdiabatic(work,advectedVolume,temperature0,r,cv);
			}
		}
		double traceR=1e-4,traceCv=2.5e-4;
		var compressed=MixtureVolumeModel.gasWorkState(.125,.125,traceR,traceCv,300,.0625);
		checkAdiabatic(compressed,.0625,300,traceR,traceCv);
		require(compressed.temperatureK()>300 && compressed.temperatureK()<1e8,
				"Trace gas compression produced an invalid temperature");
		double limit=mmm.thermal.MixtureVolumeModel.PRESSURE_LIMIT_PA;
		var tiny=mmm.thermal.MixtureVolumeModel.evaluate(1e-12,.125,125,r,temperature0,1.4,.05,.5);
		require(tiny.absolutePressurePa()>pressure0&&tiny.absolutePressurePa()<=limit
				&&tiny.gasVolumeM3()>0&&tiny.liquidVolumeM3()>0,
				"Compressed mixture did not retain finite positive gas and liquid volumes");
		close(tiny.gasVolumeM3()+tiny.liquidVolumeM3(),1e-12,"Compressed mixture did not fill its capacity");
	}

	private static void checkAdiabatic(MixtureVolumeModel.GasWorkState state,double volume0,
			double temperature0,double gasConstant,double heatCapacity) {
		double bulk=MixtureVolumeModel.LIQUID_BULK_PA,limit=MixtureVolumeModel.PRESSURE_LIMIT_PA;
		double a=heatCapacity*bulk/(limit*gasConstant);
		double b=heatCapacity*gasConstant/(heatCapacity*bulk+limit*gasConstant);
		double residual=Math.log((state.gasVolumeM3()+b*state.temperatureK())/(volume0+b*temperature0))
				+a*Math.log(state.temperatureK()/temperature0);
		require(Double.isFinite(residual)&&Math.abs(residual)<=1e-7,"Gas adiabatic invariant differs: "+residual);
	}

	private static GasThermalModel caloricModel(GasRegistry gas, List<ThermalMaterialDefinition.CaloricSegment> segments) {
		return new GasThermalModel(new ThermalRegistry(List.of(new ThermalMaterialDefinition("test:thermal/gas",
				ThermalMaterialDefinition.CarrierKind.GAS, "test:gas", 300, 10_000, 0, segments))), gas);
	}
	private static void close(double actual, double expected, String message) {
		require(Double.isFinite(actual) && Math.abs(actual - expected) <= 1e-9 * Math.max(Math.abs(expected), 1e-12),
				message + ": " + actual + " versus " + expected);
	}

	private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
