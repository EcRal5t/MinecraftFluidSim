package mmm.fluid;

import mmm.chemistry.BuiltinChemicalDefinitions;
import mmm.chemistry.ChemicalLimits;
import mmm.chemistry.ChemicalMixtureRecord;
import mmm.chemistry.ChemicalRegistry;
import mmm.chemistry.ChemicalTransferLedger;
import mmm.chemistry.ExactRational;
import mmm.thermal.LiquidThermalExpansion;
import mmm.thermal.ThermalMaterialDefinition;
import mmm.thermal.ThermalRegistry;
import mmm.thermal.ThermalSpeciesOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Actual cell/MAC/thermal pipeline with composition and thermal forcing audited separately. */
final class LiquidStratificationValidation {
	private static final ChemicalRegistry CHEMISTRY = BuiltinChemicalDefinitions.REGISTRY;
	private static final FluidMaterialRegistry LIQUIDS = new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER));
	private static final double T_REF = 293.15;
	private LiquidStratificationValidation() { }

	public static void main(String[] args) {
		validityAndCommittedDefinitions();
		stableLayers();
		unstableLayersRespond();
	}

	static void validityAndCommittedDefinitions() {
		isolatedSupportReceivesUniformBuoyancy();
		var expansionLaw = new LiquidThermalExpansion.Definition("test:volume_expansion", 300, .001);
		for (double temperature : new double[]{1, 250, 300, 350, 1000, 1e6}) {
			double densityRatio = 1 - expansionLaw.relativeBuoyancy(temperature);
			check(densityRatio > 0 && Math.abs(densityRatio - 1 / (1 + .001 * (temperature - 300))) <= 2 * Math.ulp(1.0),
					"Wide-temperature expansion lost its positive density relation");
		}
		check(Math.abs(expansionLaw.relativeBuoyancy(300.001) / 1e-6 - 1) < 2e-6,
				"Small temperature changes lost the linear expansion response");
		for (double invalid : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
			try { expansionLaw.relativeBuoyancy(invalid); throw new AssertionError("Invalid liquid temperature accepted"); }
			catch (IllegalArgumentException expected) { }
		}
		try { new LiquidThermalExpansion.Definition("test:invalid", 300, .01).relativeBuoyancy(100);
			throw new AssertionError("Nonpositive constitutive volume accepted");
		} catch (IllegalArgumentException expected) { }
		var expansion = LiquidThermalExpansion.require("mmm:water");
		var changed = new LiquidThermalExpansion.Definition("mmm:water", T_REF, 2 * expansion.volumetricCoefficientPerK());
		var baseline = BuiltinFluidMaterials.createRegistry();
		var definitions = new MaterialRegistry(List.of(new MaterialRegistry.MaterialEntry(BuiltinFluidMaterials.WATER,
				baseline.entry("mmm:water").viscosity(), changed)), List.of());
		FluidDomain domain = fixture(0, false, false, 0, definitions);
		// This independent fixture has its own immutable definition set, bound before evaluation.
		FluidDomain custom = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
		custom.setThermalTemperatureInCell(2, 1, 1, T_REF + 10);
		double[] energies = custom.exportThermalParticleState().copyInternalEnergy();
		// One interior axis and two wall-adjacent axes: (3/4)*(7/8)*(7/8) of this cell's support is heated.
		double expected = changed.relativeBuoyancy(T_REF + 10);
		check(Math.abs(custom.thermalBuoyancyProperties().relativeBuoyancy(custom.cellIndex(2, 1, 1)) - expected) < 1e-12,
				"thermal property batch did not use the bound expansion");
		check(!baseline.fingerprint().equals(definitions.fingerprint()), "expansion missing from material identity");
		check(Arrays.equals(energies, custom.exportThermalParticleState().copyInternalEnergy()), "buoyancy observation changed carrier U");
		var snapshot = custom.captureAuthoritativeState();
		FluidDomain outside = fixture(0, false, false, 0);
		outside.thermalState.resetMarkerTemperature(0, T_REF + 300, outside.thermalMarkerContext(), outside.exactInternalEnergy());
		var before = outside.exportThermalParticleState();
		var energyBefore = outside.exactInternalEnergy();
		var properties = outside.thermalBuoyancyProperties();
		check(Double.isFinite(properties.relativeBuoyancy(outside.particleCellIndex(0))),
				"Wide-temperature carrier did not produce a finite buoyancy");
		check(Arrays.equals(before.copyInternalEnergy(), outside.exportThermalParticleState().copyInternalEnergy()),
				"Wide-temperature observation changed the live thermal owner");
		var candidate = new CpuFluidSolverBackend().evaluate(new FluidStepRequest(0, outside.captureAuthoritativeState(), List.of(), false, 0));
		var advanced = candidate.claimPreparedCpuDomain();
		check(advanced.exactInternalEnergy().equals(advanced.thermalAudit().expectedInventory())
				&& outside.exactInternalEnergy().equals(energyBefore), "Wide-temperature mechanical step lost thermal accounting or source isolation");
		check(snapshot.materialDefinitions().fingerprint().equals(definitions.fingerprint()), "handoff lost committed expansion identity");
		var missing = new MaterialRegistry(List.of(new MaterialRegistry.MaterialEntry(BuiltinFluidMaterials.WATER,
				baseline.entry("mmm:water").viscosity(), null)), List.of());
		try {
			fixture(0, false, false, 0, missing).thermalBuoyancyProperties();
			throw new AssertionError("missing committed expansion silently used built-in data");
		} catch (IllegalStateException expectedMissing) { }
	}

	private static void isolatedSupportReceivesUniformBuoyancy() {
		var config = new FluidSolverConfig(0.5F, 1000, 9.81F, 0.05F, 200, 1e-6F, 0.4F, 0.002F, 0);
		var domain = new FluidDomain(6, 6, 6, config);
		domain.setVolume(3, 3, 3, 0.125F);
		domain.setMarkerMaterial(0, LIQUIDS, 0);
		domain.bindMaterialDefinitions(BuiltinFluidMaterials.createRegistry());
		var caloric = new ThermalMaterialDefinition("test:thermal/isolated_buoyancy", ThermalMaterialDefinition.CarrierKind.LIQUID,
				"mmm:water", T_REF, 0, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(250, 600, 4184)));
		domain.bindThermal(new ThermalRegistry(List.of(caloric)), LIQUIDS, T_REF + 20);
		var energy = domain.exactInternalEnergy();
		domain.ensureGrid();
		domain.bodyForceStage.applyGravity(config.timeStep());
		domain.bodyForceStage.applyThermalBuoyancy(config.timeStep());
		double relative = domain.materialDefinitions.entry("mmm:water").thermalExpansion().relativeBuoyancy(T_REF + 20);
		float expected = -config.gravity() * config.timeStep() + (float) ((double) config.gravity() * config.timeStep() * relative);
		int active = 0;
		for (int z = 0; z < 6; z++) for (int y = 1; y < 6; y++) for (int x = 0; x < 6; x++) {
			int face = domain.yFaceIndex(x, y, z);
			if (!domain.carrierFaceActive(domain.cellIndex(x, y - 1, z), domain.cellIndex(x, y, z), 1)) continue;
			check(Math.abs(domain.mac.velocityY[face] - expected) < 1e-5,
					"uniform thermal acceleration changed on an active liquid face: " + domain.mac.velocityY[face]);
			active++;
		}
		check(active > 0, "isolated buoyancy fixture has no active liquid face");
		check(energy.equals(domain.exactInternalEnergy()), "thermal body force changed carrier U");
	}

	static void stableLayers() {
		compensatedThermalCompositionRemainsHydrostatic();
		for (double[] state : new double[][] {{0.10, 0, 0}, {0, -20, 0}, {0.01, 10, 0}, {0, -20, 100}}) {
			var definitions = state[2] == 0 ? BuiltinFluidMaterials.createRegistry()
					: new MaterialRegistry(List.of(new MaterialRegistry.MaterialEntry(BuiltinFluidMaterials.WATER,
							List.of(new MaterialRegistry.ViscositySegment(250, 600, state[2], state[2])))), List.of());
			FluidDomain domain = fixture(state[0], false, false, state[1], definitions);
			Inventory initial = Inventory.capture(domain);
			double composition = densityContrast(domain);
			double[] initialDepths=columnDepths(domain);
			double maximum = 0, viscous = 0;
			for (int i = 0; i < 40; i++) {
				var candidate = advance(domain);
				domain = candidate.claimPreparedCpuDomain();
				var stats = candidate.stats();
				viscous += stats.viscosity().dissipatedJoules();
				initial.check(domain);
				maximum = Math.max(maximum, domain.maximumParticleSpeed());
			}
			checkHydrostaticMotion(domain,initialDepths,maximum,40);
			System.out.printf("  stable liquid layers composition=%g thermal=%g maxSpeed=%g m/s viscous=%g J%n",
					composition, -domain.materialDefinitions.entry("mmm:water").thermalExpansion().relativeBuoyancy(T_REF + state[1]), maximum, viscous);
		}
	}

	private static void compensatedThermalCompositionRemainsHydrostatic() {
		FluidDomain domain = fixture(0.01, false, true, 0);
		var expansion = domain.materialDefinitions.entry("mmm:water").thermalExpansion();
		double minimumDensity = Double.POSITIVE_INFINITY, maximumDensity = 0;
		for (int marker = 0; marker < domain.particleCount(); marker++) {
			var stock=domain.liquidCells.physicalStockAt(marker);
			double rho=stock.massKilograms()/(stock.volumeFraction()*domain.unitScale.cubicMetersPerNormalizedVolume());
			minimumDensity = Math.min(minimumDensity, rho); maximumDensity = Math.max(maximumDensity, rho);
			double temperature = T_REF + (rho / domain.config.density() - 1) / expansion.volumetricCoefficientPerK();
			domain.thermalState.resetMarkerTemperature(marker, temperature, domain.thermalMarkerContext(), domain.exactInternalEnergy());
		}
		// Mixing conserves mass-weighted heat; its nonlinear density relation has a second-order contrast error.
		double mixingDensityBound = Math.pow(maximumDensity - minimumDensity, 2) / (4 * minimumDensity);
		// Advect composition and temperature within cells, then require both sampled density terms to change together.
		var advected = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
		advected.ensureGrid();
		var cellVolumes = advected.markerGrid.exactCellVolumeInventory.clone();
		double[] densities = new double[advected.cellCount];
		var beforeProperties = advected.thermalBuoyancyProperties();
		for (int cell = 0; cell < densities.length; cell++) densities[cell] = beforeProperties.density(cell);
		// A discrete stream function transports heat and composition with the same cell face flux.
		for(int z=1;z<3;z++)for(int y=1;y<5;y++)for(int x=1;x<=5;x++)
			advected.mac.velocityX[advected.xFaceIndex(x,y,z)]=(float)((compensatedStream(x,y+1)-compensatedStream(x,y))/.5);
		for(int z=1;z<3;z++)for(int y=1;y<=5;y++)for(int x=1;x<5;x++)
			advected.mac.velocityY[advected.yFaceIndex(x,y,z)]=(float)((compensatedStream(x,y)-compensatedStream(x+1,y))/.5);
		advected.cellMacStage.publishMomentum();
		Inventory advectedInventory=Inventory.capture(advected);
		advected.cellMacStage.transport(.001F);advected.ensureGrid();
		var afterProperties = advected.thermalBuoyancyProperties();
		double maximumDensityChange = 0;
		// Integer cell inventories undergo donor and target allocation before buoyancy is observed.
		double densityResolution = 2 * mmm.units.Mass.kilograms(1)
				/ domain.unitScale.cubicMetersPerNormalizedVolume();
		for (int cell = 0; cell < densities.length; cell++) if (advected.liquidCells.exactCellVolume(cell).signum() > 0) {
			maximumDensityChange = Math.max(maximumDensityChange, Math.abs(afterProperties.density(cell) - densities[cell]));
			check(Math.abs(afterProperties.density(cell) * (1 - afterProperties.relativeBuoyancy(cell)) - domain.config.density()) < densityResolution + mixingDensityBound,
					"subcell motion sampled heat and composition on different supports: densityResidual=" + (afterProperties.density(cell) * (1 - afterProperties.relativeBuoyancy(cell)) - domain.config.density()));
		}
		check(maximumDensityChange>1e-5&&advected.exactInternalEnergy().equals(advected.thermalAudit().expectedInventory()),
				"Compensated cell transport was stationary or lost its energy account");
		check(Arrays.equals(cellVolumes,advected.markerGrid.exactCellVolumeInventory),"Compensated transport changed full-cell volume");
		advectedInventory.check(advected);
		Inventory initial = Inventory.capture(domain);
		double[] initialDepths=columnDepths(domain);
		double maximum = 0;
		for (int i = 0; i < 40; i++) {
			var candidate = advance(domain);
			domain = candidate.claimPreparedCpuDomain();
			var stats = candidate.stats();
			initial.check(domain);
			maximum = Math.max(maximum, domain.maximumParticleSpeed());
			check(Math.abs(stats.mechanicalAudit().closureResidualJoules()) < 1e-6,
					"compensated heat/salt mechanical energy did not close");
		}
		checkHydrostaticMotion(domain,initialDepths,maximum,40);
		System.out.printf("  compensated heat/salt maxSpeed=%g m/s subcellDensityChange=%g kg/m3%n",
				maximum, maximumDensityChange);
	}

	private static double[] columnDepths(FluidDomain domain) {
		var depth=new double[domain.sizeX*domain.sizeZ];
		for(var stock:domain.liquidCells.stocks()) {
			int cell=stock.key().cell(),column=cell%domain.sizeX+cell/(domain.sizeX*domain.sizeY)*domain.sizeX;
			depth[column]+=stock.volumeFraction()*domain.config.cellSize();
		}
		return depth;
	}
	private static void checkHydrostaticMotion(FluidDomain domain,double[] initialDepths,double maximumSpeed,int steps) {
		// Permit at most 1% of one cell of motion over this scenario's complete duration.
		double displacement=.01*domain.config.cellSize();
		double speedLimit=displacement/(steps*domain.config.timeStep());
		check(Double.isFinite(maximumSpeed)&&maximumSpeed<=speedLimit,
				"Hydrostatic motion exceeds the cell/time-scale allowance: "+maximumSpeed+" > "+speedLimit);
		var depths=columnDepths(domain);
		for(int column=0;column<depths.length;column++) check(Math.abs(depths[column]-initialDepths[column])<=displacement,
				"Hydrostatic column changed depth by more than 1% of a cell");
	}

	static void unstableLayersRespond() {
		var backend = new CpuFluidSolverBackend();
		for (double[] state : new double[][] {{0.10, 0}, {0, 20}, {0.001, 20}}) {
			boolean brineOnTop = state[1] == 0;
			FluidDomain domain = fixture(state[0], brineOnTop, true, state[1]);
			Inventory initial = Inventory.capture(domain);
			double composition = densityContrast(domain);
			double initialMoment = state[0] == 0 ? heatCenter(domain) : saltCenter(domain);
			double extremeMoment = initialMoment;
			double viscous = 0, p2g = 0, g2p = 0;
			for (int step = 0; step < 9600; step++) {
				var candidate = backend.evaluate(new FluidStepRequest(step, domain.captureAuthoritativeState(), List.of(), false, 0, true));
				var stats = candidate.stats();
				domain = candidate.claimPreparedCpuDomain();
				initial.check(domain);
				check(Math.abs(stats.mechanicalAudit().closureResidualJoules()) < 1e-6,
						"stratification mechanical energy stages do not close");
				viscous += stats.viscosity().dissipatedJoules();
				p2g += stats.mechanicalAudit().particleToGridTransferJoules();
				g2p += stats.mechanicalAudit().gridToParticleTransferJoules();
				double moment = state[0] == 0 ? heatCenter(domain) : saltCenter(domain);
				extremeMoment = brineOnTop ? Math.min(extremeMoment, moment) : Math.max(extremeMoment, moment);
				if (brineOnTop ? extremeMoment < 1.5 : extremeMoment - initialMoment > 0.02) break;
			}
			check(brineOnTop ? extremeMoment < 1.5 : extremeMoment - initialMoment > 0.02,
					"unstable/competing layers did not produce the required center shift: salt=" + state[0] + " dT=" + state[1]
							+ " center=" + initialMoment + " -> " + extremeMoment);
			System.out.printf("  unstable liquid layers composition=%g thermal=%g center=%.4f -> %.4f; viscous=%g P2G=%g G2P=%g J%n",
					composition, -domain.materialDefinitions.entry("mmm:water").thermalExpansion().relativeBuoyancy(T_REF + state[1]),
					initialMoment, extremeMoment, viscous, p2g, g2p);
		}
	}

	private static FluidDomain fixture(double saltFraction, boolean brineOnTop, boolean perturb, double warmBottomK) {
		return fixture(saltFraction, brineOnTop, perturb, warmBottomK, BuiltinFluidMaterials.createRegistry());
	}

	private static FluidDomain fixture(double saltFraction, boolean brineOnTop, boolean perturb, double warmBottomK,
			MaterialRegistry definitions) {
		var config = new FluidSolverConfig(0.5F, 1000, 9.81F, 0.05F, 300, 1e-6F, 0.4F, 0.002F, 0);
		FluidDomain seed = new FluidDomain(6, 6, 4, config, 2048);
		for (int z = 0; z < 4; z++) for (int y = 0; y < 6; y++) for (int x = 0; x < 6; x++) {
			boolean shell = x == 0 || x == 5 || y == 0 || y == 5 || z == 0 || z == 3;
			seed.setSolid(x, y, z, shell);
			if (!shell) seed.setVolume(x, y, z, 1);
		}
		seed.setMarkerMaterial(0, LIQUIDS, 0);
		seed.bindChemistry(CHEMISTRY);
		var original = seed.exportMaterialParticleState();
		float[] positions = original.copyPositionVolume();
		long[] mass = original.copyParticleMassUnits();
		var mixtures = new ArrayList<ChemicalMixtureRecord>();
		for (int marker = 0; marker < seed.particleCount(); marker++) {
			int x = (int) (positions[4 * marker] / 0.5), y = (int) (positions[4 * marker + 1] / 0.5);
			boolean bottom = y <= 2;
			if (perturb && (y == 2 || y == 3) && x <= 2) bottom = !bottom;
			var initial = seed.liquidCells.physicalStockAt(marker).mixture();
			var amounts = new java.util.TreeMap<>(initial.stableAmounts(CHEMISTRY));
			if (saltFraction > 0 && bottom != brineOnTop) {
				long salt = Math.round(mmm.units.Mass.kilograms(mass[marker]) * saltFraction * ChemicalLimits.QUANTA_PER_MOL / .058);
				amounts.merge(BuiltinChemicalDefinitions.NA_PLUS, salt, Math::addExact);
				amounts.merge(BuiltinChemicalDefinitions.CL_MINUS, salt, Math::addExact);
			}
			var mixture = ChemicalMixtureRecord.fromStableAmounts(CHEMISTRY, amounts, initial.massResidualUnits());
			mixtures.add(mixture);
			mass[marker] = mixture.massUnits(CHEMISTRY);
		}
		long total = mmm.units.Mass.sum(mass);
		var materials = new FlipMaterialParticleState(6, 6, 4, 0.5F, seed.particleCount(), original.totalVolume(), total,
				original.materials(), positions, original.copyParticleVelocities(), original.copySolidMask(), mass,
				original.copyMaterialPaletteIndex());
		var chemical = FlipChemicalParticleState.capture(materials, CHEMISTRY, mixtures, new byte[mass.length],
				ChemicalTransferLedger.EMPTY, ChemicalTransferLedger.EMPTY);
		FluidDomain domain = new FluidDomain(6, 6, 4, config, 2048);
		domain.restoreChemicalParticleState(chemical, CHEMISTRY);
		domain.bindMaterialDefinitions(definitions);
		domain.sealDomainBoundary();
		var water = new ThermalMaterialDefinition("test:thermal/stratification", ThermalMaterialDefinition.CarrierKind.LIQUID,
				"mmm:water", T_REF, 0, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(250, 600, 4184)));
		var air = new ThermalMaterialDefinition("test:thermal/stratification_air", ThermalMaterialDefinition.CarrierKind.GAS,
				"mmm:air", T_REF, 0, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(150, 1500, 718)));
		var offsets = List.of(new ThermalSpeciesOffset("test:offset/water", BuiltinChemicalDefinitions.H2O, water.id(), ExactRational.ZERO),
				new ThermalSpeciesOffset("test:offset/sodium", BuiltinChemicalDefinitions.NA_PLUS, water.id(), ExactRational.ZERO),
				new ThermalSpeciesOffset("test:offset/chloride", BuiltinChemicalDefinitions.CL_MINUS, water.id(), ExactRational.ZERO));
		domain.bindThermal(new ThermalRegistry(List.of(water, air), offsets, List.of(), CHEMISTRY.fingerprint(), ""), domain.materialRegistry, T_REF);
		for (int z = 1; z < 3; z++) for (int y = 1; y < 5; y++) for (int x = 1; x < 5; x++) {
			boolean bottom = y <= 2;
			if (perturb && (y == 2 || y == 3) && x <= 2) bottom = !bottom;
			if (bottom) domain.setThermalTemperatureInCell(x, y, z, T_REF + warmBottomK);
		}
		return domain;
	}

	private static double compensatedStream(int x,int y){return .1/Math.PI*Math.sin(Math.PI*(x-1)/4)*Math.sin(Math.PI*(y-1)/4);}

	private static double saltCenter(FluidDomain domain) {
		int salt = CHEMISTRY.speciesHandle(BuiltinChemicalDefinitions.NA_PLUS);
		double sum = 0, moment = 0;
		for (int marker = 0; marker < domain.particleCount(); marker++) {
			long amount = domain.liquidCells.physicalStockAt(marker).mixture().amount(salt);
			sum += amount;
			moment += amount * (domain.liquidCells.physicalStockAt(marker).key().cell()/domain.sizeX%domain.sizeY+.5)*domain.config.cellSize();
		}
		return moment / sum;
	}

	private static double densityContrast(FluidDomain domain) {
		double minimum = Double.POSITIVE_INFINITY, maximum = 0;
		for (int z = 1; z < 3; z++) for (int y = 1; y < 5; y++) for (int x = 1; x < 5; x++) {
			double rho = domain.densityInCell(x, y, z);
			minimum = Math.min(minimum, rho);
			maximum = Math.max(maximum, rho);
		}
		return maximum / minimum - 1;
	}

	private static double heatCenter(FluidDomain domain) {
		double sum = 0, moment = 0;
		for (int marker = 0; marker < domain.particleCount(); marker++) {
			double energy = domain.thermalState.liquidInternalEnergy(marker);
			sum += energy;
			moment += energy * (domain.liquidCells.physicalStockAt(marker).key().cell()/domain.sizeX%domain.sizeY+.5)*domain.config.cellSize();
		}
		return moment / sum;
	}

	private record Inventory(mmm.units.SignedDyadic volume, long mass, long[] species, mmm.thermal.ThermalAuditLedger audit) {
		static Inventory capture(FluidDomain domain) {
			return new Inventory(domain.liquidCells.exactVolume(), domain.liquidCells.massUnits(),
					speciesTotals(domain), domain.thermalAudit());
		}
		void check(FluidDomain domain) {
			LiquidStratificationValidation.check(domain.liquidCells.exactVolume().equals(volume) && domain.liquidCells.massUnits() == mass,
					"stratification changed volume or material mass");
			LiquidStratificationValidation.check(Arrays.equals(species, speciesTotals(domain)),
					"stratification changed exact species");
			LiquidStratificationValidation.check(domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
					"stratification thermal audit does not close");
			var after = domain.thermalAudit();
			LiquidStratificationValidation.check(after.externalSource().equals(audit.externalSource())
					&& after.externalSink().equals(audit.externalSink())
					&& after.mechanicalWork().subtract(audit.mechanicalWork()).signum() >= 0
					&& after.finiteDeviceExchange().equals(audit.finiteDeviceExchange())
					&& domain.exactInternalEnergy().subtract(audit.expectedInventory())
							.subtract(after.mechanicalWork().subtract(audit.mechanicalWork()))
							.equals(after.roundoffResidual().subtract(audit.roundoffResidual())),
					"isolated stratification changed the thermal source/sink/work ledger");
		}
	}

	static long[] speciesTotals(FluidDomain domain) {
		var totals=new long[domain.chemicalParcels.registry().species().size()];
		for(var stock:domain.liquidCells.stocks())if(stock.mixture()!=null)
			for(int entry=0;entry<stock.mixture().entryCount();entry++){
				int species=stock.mixture().speciesHandle(entry);
				totals[species]=Math.addExact(totals[species],stock.mixture().amountAt(entry));
			}
		return totals;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
	private static FluidStepCandidate advance(FluidDomain domain) {
		return new CpuFluidSolverBackend().evaluate(new FluidStepRequest(
				0, domain.captureAuthoritativeState(), List.of(), false, 0, true));
	}

}
