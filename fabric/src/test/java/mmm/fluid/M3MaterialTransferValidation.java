package mmm.fluid;

import mmm.inventory.LiquidCellInventory;

import java.util.Arrays;
import java.util.List;

/** Actual material inventory, shared MAC inertia and variable-density hydrostatic contracts. */
final class M3MaterialTransferValidation {

	private M3MaterialTransferValidation() { }

	static Summary validate() {
		FluidMaterialRegistry registry = registry();
		validateRegistry(registry);
		validatePartialExtractionMassResolution();
		Summary result=validateActiveMacMomentum(registry);
		validateActivePhaseAggregation(registry);validateActiveVariableDensityPressure(registry);
		return result;
	}

	private static void validatePartialExtractionMassResolution() {
		for(long mass:new long[]{1,4}) {
			var domain=new FluidDomain(3,3,3,new FluidSolverConfig(1,1000,0,.05F,100,1e-5F,.002F));
			domain.bindMaterialRegistry(new FluidMaterialRegistry(BuiltinFluidMaterials.DEFAULT_PALETTE));
			var key=new LiquidCellInventory.Key(domain.domainFlowFields.cellIndex(1,1,1),0,255);
			domain.liquidCells.planAdditions(List.of(new LiquidCellInventory.Stock(key,mmm.units.SignedDyadic.of(.125),mass,null,0))).commit();
			domain.markCellInventoryChanged();
			var transfer=domain.tryExtractExactMixture(1,1,1,.0625F,0);
			check(transfer.transferred()&&domain.liquidCells.exactVolume().equals(mmm.units.SignedDyadic.of(.0625))
					&&domain.totalMassUnits()+transfer.portions().stream().mapToLong(FluidDomain.MaterialPortion::massUnits).sum()==mass,
					"Partial extraction lost integer mass or its geometric tail");
			if(mass==4)check(domain.totalMassUnits()==2&&transfer.portions().getFirst().massUnits()==2,
					"Representable partial extraction must divide actual mass consistently");
		}
	}

	private static Summary validateActiveMacMomentum(FluidMaterialRegistry registry) {
		int a=registry.handle("mmm:material_a"),b=registry.handle("mmm:material_b"),c=registry.handle("mmm:material_c");
		var equal=materialShear(registry,a,b);var variable=materialShear(registry,a,c);
		int face=equal.domainFlowFields.xFaceIndex(2,1,1);
		checkClose(equal.mac.velocityX[face],.5,"Equal-density shared velocity");
		check(Math.abs(variable.mac.velocityX[face]-800.0/1800)<=4*Math.ulp((float)(800.0/1800)),"Actual mass-weighted shared velocity exceeded float storage precision");
		checkClose(variable.cellMacStage.massX[face],900,"Actual SI shared inertia");
		checkClose(variable.totalMass(),1800,"Material mass ledger");
		var state=variable.captureAuthoritativeState();var restored=FluidDomain.restoreAuthoritativeState(state);
		check(restored.exportLiquidCellState().stocks().equals(state.liquid().stocks())
				&&Arrays.equals(restored.exportMacMomentum().copyX(),state.momentum().copyX()),"Material handoff changed inventory or MAC momentum");
		checkClose(restored.domainCollision.materialInventory(a).mass(),1000,"Restored material A mass");
		checkClose(restored.domainCollision.materialInventory(c).mass(),800,"Restored material C mass");
		return new Summary(variable.totalVolumeExact(),variable.totalMass(),Arrays.stream(variable.mac.momentumX).sum(),
				Math.abs(variable.mac.velocityX[face]-equal.mac.velocityX[face]));
	}
	private static FluidDomain materialShear(FluidMaterialRegistry registry,int left,int right) {
		var domain=new FluidDomain(4,4,4,new FluidSolverConfig(1,1000,0,.05F,100,1e-5F,.002F));
		domain.bindMaterialRegistry(registry);float[] volume=new float[domain.cellCount];int[] material=new int[domain.cellCount];
		volume[domain.domainFlowFields.cellIndex(1,1,1)]=volume[domain.domainFlowFields.cellIndex(2,1,1)]=1;
		material[domain.domainFlowFields.cellIndex(1,1,1)]=left;material[domain.domainFlowFields.cellIndex(2,1,1)]=right;
		domain.setVolumes(volume,material);domain.domainFlowFields.preparePhysicalState();
		double half=.5*domain.liquidCells.stocksInCell(domain.domainFlowFields.cellIndex(2,1,1)).getFirst().massKilograms();
		domain.mac.momentumX[domain.domainFlowFields.xFaceIndex(2,1,1)]=half;domain.mac.momentumX[domain.domainFlowFields.xFaceIndex(3,1,1)]=half;
		domain.velocityGridDirty=true;domain.domainFlowFields.preparePhysicalState();return domain;
	}
	private static FluidDomain materialCell(FluidMaterialRegistry registry,int[] materials,double[] volumes) {
		var domain=new FluidDomain(4,4,4,new FluidSolverConfig(1,1000,0,.05F,100,1e-5F,.002F));
		domain.bindMaterialRegistry(registry);var stocks=new java.util.ArrayList<LiquidCellInventory.Stock>();
		for(int i=0;i<materials.length;i++)stocks.add(new LiquidCellInventory.Stock(new LiquidCellInventory.Key(domain.domainFlowFields.cellIndex(1,1,1),materials[i],255),
				mmm.units.SignedDyadic.of(volumes[i]),mmm.units.Mass.fromKilograms(volumes[i]*registry.material(materials[i]).referenceDensity()),null,0));
		domain.liquidCells.planAdditions(stocks).commit();domain.markCellInventoryChanged();return domain;
	}

	private static void validateActivePhaseAggregation(FluidMaterialRegistry registry) {
		int a = registry.handle("mmm:material_a");
		int b = registry.handle("mmm:material_b");
		int c = registry.handle("mmm:material_c");
		int d = registry.handle("mmm:material_d");
		FluidDomain miscible = materialCell(registry,new int[]{a,d},new double[]{.5,.5});
		check(miscible.domainCollision.phaseCountInCell(1, 1, 1) == 1,
				"miscible materials did not aggregate into one local liquid phase");
		checkClose(miscible.domainCollision.materialInventory(a).mass(), 500.0, "miscible material A mass");
		checkClose(miscible.domainCollision.materialInventory(d).mass(), 525.0, "miscible material D mass");
		int mixedCell = 1 + 4 * (1 + 4);
		FluidMaterialRenderSnapshot mixedSnapshot = miscible.copyMaterialRenderSnapshot();
		check(mixedSnapshot.phaseFraction(registry.phaseHandle(a), mixedCell) == 255,
				"miscible render snapshot did not expose one complete phase");
		check(mixedSnapshot.materialFraction(a, mixedCell) == 128
				&& mixedSnapshot.materialFraction(d, mixedCell) == 127,
				"miscible render fractions were not deterministically normalized");
		FluidDomain.StepStats miscibleStats = miscible.domainStep.step();
		check(miscible.domainCollision.phaseCountInCell(1, 1, 1) == 1,
				"miscible phase split during an idle active step");
		check(miscibleStats.relativeVolumeError() <= 1.0E-7F && miscibleStats.relativeMassError() <= 1.0E-12,
				"miscible active step changed physical inventories");

		FluidDomain junction = materialCell(registry,new int[]{a,b,c},new double[]{.375,.375,.25});
		check(junction.domainCollision.phaseCountInCell(1, 1, 1) == 3,
				"three-phase junction was truncated to the old two-phase cache");
		FluidMaterialRenderSnapshot junctionSnapshot = junction.copyMaterialRenderSnapshot();
		int phaseA = registry.phaseHandle(a), phaseB = registry.phaseHandle(b), phaseC = registry.phaseHandle(c);
		check(Math.abs(junctionSnapshot.phaseFraction(phaseA,mixedCell)-255*.375)<=1
				&&Math.abs(junctionSnapshot.phaseFraction(phaseB,mixedCell)-255*.375)<=1
				&&Math.abs(junctionSnapshot.phaseFraction(phaseC,mixedCell)-255*.25)<=1,"Rendered phase fractions exceeded one display quantum");
		FluidDomain.StepStats junctionStats = junction.domainStep.step();
		check(junction.domainCollision.phaseCountInCell(1, 1, 1) == 3,
				"three-phase junction was lost during pressure/reseeding");
		checkClose(junction.totalMass(), 950.0, "three-phase marker mass");
		check(junctionStats.relativeVolumeError() <= 1.0E-7F && junctionStats.relativeMassError() <= 1.0E-12,
				"three-phase active step changed physical inventories");

		FluidMaterialRegistry wrongDefault = new FluidMaterialRegistry(List.of(registry.material(c)));
		expectIllegal(() -> singleCellDomain().domainCollision.setMarkerMaterial(0, wrongDefault, 0));
	}

	private static FluidDomain singleCellDomain() {
		FluidSolverConfig config = new FluidSolverConfig(
				1.0F, 1_000.0F, 0.0F, 1.0F / 20.0F,
				100, 1.0E-5F, 2.0E-3F);
		FluidDomain domain = new FluidDomain(4, 4, 4, config);
		domain.setVolume(1, 1, 1, 1.0F);
		return domain;
	}

	private static FluidMaterialRegistry registry() {
		return new FluidMaterialRegistry(List.of(
				new FluidMaterial("mmm:material_a", 1_000.0, "mmm:aqueous", 0.0, Math.PI / 2.0),
				new FluidMaterial("mmm:material_b", 1_000.0, "mmm:immiscible_b", 0.0, Math.PI / 2.0),
				new FluidMaterial("mmm:material_c", 800.0, "mmm:immiscible_c", 0.0, Math.PI / 2.0),
				new FluidMaterial("mmm:material_d", 1_050.0, "mmm:aqueous", 0.0, Math.PI / 2.0),
				new FluidMaterial("mmm:material_e", 13_600.0, "mmm:immiscible_e", 0.0, Math.PI / 2.0)
		));
	}

	private static void validateRegistry(FluidMaterialRegistry registry) {
		check(registry.size() == 5, "material registry size changed");
		int a = registry.handle("mmm:material_a");
		int b = registry.handle("mmm:material_b");
		int c = registry.handle("mmm:material_c");
		int d = registry.handle("mmm:material_d");
		int e = registry.handle("mmm:material_e");
		check(a == 0 && b == 1 && c == 2 && d == 3 && e == 4,
				"runtime handles are not declaration-order dense");
		check(registry.areMiscible(a, d), "same miscibility group was rejected");
		check(!registry.areMiscible(a, b) && !registry.areMiscible(a, c), "unlike miscibility groups were merged");
		check(registry.phaseHandle(a) == registry.phaseHandle(d), "miscible materials did not share a phase handle");
		check(registry.phaseHandle(a) != registry.phaseHandle(b), "immiscible materials shared a phase handle");
		expectIllegal(() -> new FluidMaterial("Bad Id", 1_000.0, "mmm:aqueous", 0.0, Math.PI / 2.0));
		expectIllegal(() -> new FluidMaterial("mmm:bad", 0.0, "mmm:aqueous", 0.0, Math.PI / 2.0));
		expectIllegal(() -> new FluidMaterial("mmm:bad", 1_000.0, "mmm:aqueous", -1.0, Math.PI / 2.0));
		expectIllegal(() -> new FluidMaterial("mmm:bad", 1_000.0, "mmm:aqueous", 0.0, 0.0));
		expectIllegal(() -> new FluidMaterial("mmm:bad", 1_000.0, "mmm:aqueous", 0.0, Math.PI));
		expectIllegal(() -> new FluidMaterialRegistry(List.of(
				registry.material(a), registry.material(a))));
		expectIllegal(() -> registry.material(-1));
		expectIllegal(() -> registry.handle("mmm:missing"));
	}

	private static void validateActiveVariableDensityPressure(FluidMaterialRegistry registry) {
		validateActiveHydrostaticColumn(registry, "water-oil-1.25:1",
				registry.handle("mmm:material_a"), registry.handle("mmm:material_c"));
		validateActiveHydrostaticColumn(registry, "mercury-water-13.6:1",
				registry.handle("mmm:material_e"), registry.handle("mmm:material_a"));
	}

	private static void validateActiveHydrostaticColumn(FluidMaterialRegistry registry, String name,
	                                                    int heavyHandle, int lightHandle) {
		FluidSolverConfig config = new FluidSolverConfig(
				1.0F, 1_000.0F, 9.81F, 1.0F / 40.0F,
				200, 1.0E-6F, 2.0E-3F);
		FluidDomain domain = new FluidDomain(4, 7, 4, config);
		for (int z = 0; z < domain.sizeZ(); z++) for (int y = 0; y < domain.sizeY(); y++) {
			for (int x = 0; x < domain.sizeX(); x++) domain.setSolid(x, y, z, true);
		}
		for (int y = 1; y <= 5; y++) domain.setSolid(1, y, 1, false);
		for (int y = 1; y <= 4; y++) domain.setVolume(1, y, 1, 1.0F);
		for (int y = 1; y <= 2; y++) domain.domainCollision.setMaterialInCell(1, y, 1, registry, heavyHandle);
		for (int y = 3; y <= 4; y++) domain.domainCollision.setMaterialInCell(1, y, 1, registry, lightHandle);

		double heavyDensity = registry.material(heavyHandle).referenceDensity();
		double lightDensity = registry.material(lightHandle).referenceDensity();
		FluidDomain.StepStats stats = domain.domainStep.step();
		double[] expected = {
				config.gravity() * (1.5 * lightDensity + 0.5 * (heavyDensity + lightDensity) + heavyDensity),
				config.gravity() * (1.5 * lightDensity + 0.5 * (heavyDensity + lightDensity)),
				config.gravity() * 1.5 * lightDensity,
				config.gravity() * 0.5 * lightDensity
		};
		double maximumExpected = expected[0], maximumError = 0.0;
		double[] actual = new double[4];
		for (int y = 1; y <= 4; y++) {
			actual[y - 1] = domain.pressure(1, y, 1);
			maximumError = Math.max(maximumError, Math.abs(actual[y - 1] - expected[y - 1]));
		}
		double pressureError = maximumError / maximumExpected;
		check(pressureError <= 2.0E-5, name + " active pressure missed analytic hydrostatics: " + pressureError
				+ ", actual=" + Arrays.toString(actual) + ", expected=" + Arrays.toString(expected));
		check(Float.isFinite(stats.pressureResidual()) && stats.pressureResidual() <= 1.0E-3F,
				name + " active pressure residual is too large");
		check(stats.pressureIterations()>=0&&stats.pressureIterations()<=config.maxPressureIterations()*stats.substeps(),
				name+" active pressure exceeded its configured iteration budget");
		check(domain.maximumParticleSpeed() <= 2.0E-4F, name + " active projection left parasitic marker velocity");
		check(stats.relativeVolumeError() <= 1.0E-7F && stats.relativeMassError() <= 1.0E-12,
				name + " active step changed physical inventories");
		System.out.printf("  active %-18s pressureErr=%.3g PCG=%d residual=%.3g maxSpeed=%.3g%n",
				name, pressureError, stats.pressureIterations(), stats.pressureResidual(), domain.maximumParticleSpeed());
	}

	private static void checkClose(double actual, double expected, String message) {
		double tolerance = 1.0E-12 * Math.max(1.0, Math.abs(expected));
		check(Math.abs(actual - expected) <= tolerance, message + ": expected=" + expected + ", actual=" + actual);
	}

	private static void expectIllegal(Runnable action) {
		try {
			action.run();
			throw new AssertionError("Expected invalid material state to be rejected");
		} catch (IllegalArgumentException expected) {
			// Expected.
		}
	}

	private static void expectIllegalState(Runnable action) {
		try {
			action.run();
			throw new AssertionError("Expected unsupported multi-material handoff to fail closed");
		} catch (IllegalStateException expected) {
			// Expected.
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	record Summary(double volume, double mass, double momentumX, double maximumWeightingDifference) { }

}
