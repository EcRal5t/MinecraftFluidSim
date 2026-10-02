package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.chemistry.BuiltinChemicalDefinitions;
import cc.ecisr.mmm.chemistry.ChemicalLimits;
import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.chemistry.ChemicalTransferLedger;
import cc.ecisr.mmm.chemistry.ExactRational;
import cc.ecisr.mmm.thermal.LiquidThermalExpansion;
import cc.ecisr.mmm.thermal.ThermalMaterialDefinition;
import cc.ecisr.mmm.thermal.ThermalRegistry;
import cc.ecisr.mmm.thermal.ThermalSpeciesOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Actual marker/pressure/thermal pipeline with composition and thermal forcing audited separately. */
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
		var bounded = new LiquidThermalExpansion.Definition("test:bounded", 300, 0.001, 0.05);
		check(bounded.relativeBuoyancy(350) == 0.05 && bounded.relativeBuoyancy(250) == -0.05,
				"Boussinesq closed validity interval rejected an endpoint");
		for (double invalid : new double[] {Math.nextUp(350.0), Math.nextDown(250.0)}) {
			try { bounded.relativeBuoyancy(invalid); throw new AssertionError("Boussinesq invalid temperature accepted"); }
			catch (LiquidThermalExpansion.ValidityExceededException expected) { }
		}
		var expansion = LiquidThermalExpansion.require("mmm:water");
		var changed = new LiquidThermalExpansion.Definition("mmm:water", T_REF, 2 * expansion.volumetricCoefficientPerK(), 0.01);
		var baseline = BuiltinFluidMaterials.createRegistry();
		var definitions = new MaterialRegistry(List.of(new MaterialRegistry.MaterialEntry(BuiltinFluidMaterials.WATER,
				baseline.entry("mmm:water").viscosity(), changed)), List.of());
		FluidDomain domain = fixture(0, false, false, 0, definitions);
		// This independent fixture has its own immutable definition set, bound before evaluation.
		FluidDomain custom = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
		custom.setThermalTemperatureInCell(2, 1, 1, T_REF + 10);
		double[] energies = custom.exportThermalParticleState().copyInternalEnergy();
		// One interior axis and two wall-adjacent axes: (3/4)*(7/8)*(7/8) of this cell's support is heated.
		double expected = (147.0 / 256) * changed.relativeBuoyancy(T_REF + 10);
		check(Math.abs(custom.thermalBuoyancyProperties().relativeBuoyancy(custom.cellIndex(2, 1, 1)) - expected) < 1e-12,
				"thermal property batch did not use the bound expansion");
		check(!baseline.fingerprint().equals(definitions.fingerprint()), "expansion missing from material identity");
		check(Arrays.equals(energies, custom.exportThermalParticleState().copyInternalEnergy()), "buoyancy observation changed carrier U");
		var snapshot = custom.captureAuthoritativeState();
		FluidDomain outside = fixture(0, false, false, 0);
		outside.thermalState.resetMarkerTemperature(0, T_REF + 300, outside.thermalMarkerContext(), outside.exactInternalEnergy());
		check(Math.abs(expansion.relativeBuoyancy(outside.thermalState.observeTemperatureInCell(
				outside.particleCellIndex(0), outside.thermalMarkerContext()))) < 0.05,
				"invalid-carrier fixture needs a valid cell mean");
		var before = outside.exportThermalParticleState();
		try {
			new CpuFluidSolverBackend().evaluate(new FluidStepRequest(0, outside.captureAuthoritativeState(), List.of(), false, 0));
			throw new AssertionError("out-of-domain Boussinesq candidate was published");
		} catch (FluidSolverBackend.CandidateEvaluationRejectedException rejected) {
			check(rejected.getCause() instanceof LiquidThermalExpansion.ValidityExceededException,
					"thermal approximation rejection lost its cause");
		}
		check(Arrays.equals(before.copyInternalEnergy(), outside.exportThermalParticleState().copyInternalEnergy()),
				"rejected thermal approximation changed the live owner");
		check(snapshot.materialDefinitions().fingerprint().equals(definitions.fingerprint()), "handoff lost committed expansion identity");
		var missing = new MaterialRegistry(List.of(new MaterialRegistry.MaterialEntry(BuiltinFluidMaterials.WATER,
				baseline.entry("mmm:water").viscosity(), null)), List.of());
		try {
			fixture(0, false, false, 0, missing).thermalBuoyancyProperties();
			throw new AssertionError("missing committed expansion silently used built-in data");
		} catch (LiquidThermalExpansion.ValidityExceededException expectedMissing) { }
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
			double maximum = 0, viscous = 0;
			for (int i = 0; i < 40; i++) {
				var stats = domain.stepWithMechanicalAudit(false, 0);
				viscous += stats.viscosity().dissipatedJoules();
				initial.check(domain);
				maximum = Math.max(maximum, domain.maximumParticleSpeed());
			}
			check(maximum < 1e-8, "stable stratification hydrostatic speed exceeds 1e-8 m/s: " + maximum);
			check(viscous < 1e-16, "hydrostatic prediction generated spurious viscous dissipation: " + viscous);
			System.out.printf("  stable liquid layers composition=%g thermal=%g maxSpeed=%g m/s viscous=%g J%n",
					composition, -domain.materialDefinitions.entry("mmm:water").thermalExpansion().relativeBuoyancy(T_REF + state[1]), maximum, viscous);
		}
	}

	private static void compensatedThermalCompositionRemainsHydrostatic() {
		FluidDomain domain = fixture(0.01, false, true, 0);
		var expansion = domain.materialDefinitions.entry("mmm:water").thermalExpansion();
		for (int marker = 0; marker < domain.particleCount(); marker++) {
			double rho = domain.particles.mass(marker) / domain.particles.volume(marker);
			double temperature = T_REF + (1 - domain.config.density() / rho) / expansion.volumetricCoefficientPerK();
			domain.thermalState.resetMarkerTemperature(marker, temperature, domain.thermalMarkerContext(), domain.exactInternalEnergy());
		}
		// Advect composition and temperature within cells, then require both sampled density terms to change together.
		var advected = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
		advected.ensureGrid();
		int[] cells = new int[advected.particleCount()];
		for (int marker = 0; marker < cells.length; marker++) cells[marker] = advected.particleCellIndex(marker);
		double[] densities = new double[advected.cellCount];
		var beforeProperties = advected.thermalBuoyancyProperties();
		for (int cell = 0; cell < densities.length; cell++) densities[cell] = beforeProperties.density(cell);
		for (int z = 1; z < 3; z++) for (int y = 1; y < 5; y++) for (int x = 1; x <= 5; x++)
			advected.mac.velocityX[advected.xFaceIndex(x, y, z)] = (float) (-0.1 * Math.sin(Math.PI * (x - 1) / 4)
					* Math.cos(Math.PI * (y - 0.5) / 4));
		for (int z = 1; z < 3; z++) for (int y = 1; y <= 5; y++) for (int x = 1; x < 5; x++)
			advected.mac.velocityY[advected.yFaceIndex(x, y, z)] = (float) (0.1 * Math.cos(Math.PI * (x - 0.5) / 4)
					* Math.sin(Math.PI * (y - 1) / 4));
		Inventory advectedInventory = Inventory.capture(advected);
		double[] energies = advected.exportThermalParticleState().copyInternalEnergy();
		advected.particleAdvectionStage.advect(0.001F);
		var afterProperties = advected.thermalBuoyancyProperties();
		double maximumDensityChange = 0;
		for (int cell = 0; cell < densities.length; cell++) if (advected.markerGrid.markerCount[cell] > 0) {
			maximumDensityChange = Math.max(maximumDensityChange, Math.abs(afterProperties.density(cell) - densities[cell]));
			check(Math.abs(afterProperties.density(cell) * (1 - afterProperties.relativeBuoyancy(cell)) - domain.config.density()) < 1e-9,
					"subcell motion sampled heat and composition on different supports");
		}
		check(maximumDensityChange > 1e-5 && Arrays.equals(energies, advected.exportThermalParticleState().copyInternalEnergy()),
				"subcell composition response was stationary or edited U");
		for (int marker = 0; marker < cells.length; marker++) check(cells[marker] == advected.particleCellIndex(marker),
				"compensated advection fixture crossed a cell");
		advectedInventory.check(advected);
		Inventory initial = Inventory.capture(domain);
		double maximum = 0;
		for (int i = 0; i < 40; i++) {
			var stats = domain.stepWithMechanicalAudit(false, 0);
			initial.check(domain);
			maximum = Math.max(maximum, domain.maximumParticleSpeed());
			check(Math.abs(stats.mechanicalAudit().closureResidualJoules()) < 1e-6,
					"compensated heat/salt mechanical energy did not close");
		}
		double velocityUlp = Math.ulp(domain.config.gravity() * domain.config.timeStep());
		check(maximum < velocityUlp, "compensated heat/salt speed exceeds one binary32 gravity impulse ulp: " + maximum);
		System.out.printf("  compensated heat/salt maxSpeed=%g m/s gravityImpulseUlp=%g m/s subcellDensityChange=%g kg/m3%n",
				maximum, velocityUlp, maximumDensityChange);
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
		double[] mass = original.copyParticleMass();
		var mixtures = new ArrayList<ChemicalMixtureRecord>();
		for (int marker = 0; marker < seed.particleCount(); marker++) {
			int x = (int) (positions[4 * marker] / 0.5), y = (int) (positions[4 * marker + 1] / 0.5);
			boolean bottom = y <= 2;
			if (perturb && (y == 2 || y == 3) && x <= 2) bottom = !bottom;
			var initial = seed.chemicalParcels().record(marker);
			var amounts = new java.util.TreeMap<>(initial.stableAmounts(CHEMISTRY));
			if (saltFraction > 0 && bottom != brineOnTop) {
				long salt = Math.round(seed.unitScale.kilograms(mass[marker]) * saltFraction * ChemicalLimits.QUANTA_PER_AU / 58);
				amounts.merge(BuiltinChemicalDefinitions.NA_PLUS, salt, Math::addExact);
				amounts.merge(BuiltinChemicalDefinitions.CL_MINUS, salt, Math::addExact);
			}
			var mixture = ChemicalMixtureRecord.fromStableAmounts(CHEMISTRY, amounts, initial.massResidualKilograms());
			mixtures.add(mixture);
			mass[marker] = seed.unitScale.solverMass(mixture.massKilograms(CHEMISTRY));
		}
		// State constructors validate the same ordered raw sums used by the marker inventory.
		double total = 0;
		for (double value : mass) total += value;
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

	private static double saltCenter(FluidDomain domain) {
		int salt = CHEMISTRY.speciesHandle(BuiltinChemicalDefinitions.NA_PLUS);
		double sum = 0, moment = 0;
		for (int marker = 0; marker < domain.particleCount(); marker++) {
			long amount = domain.chemicalParcels().record(marker).amount(salt);
			sum += amount;
			moment += amount * (double) domain.particles.positionY(marker);
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
			moment += energy * domain.particles.positionY(marker);
		}
		return moment / sum;
	}

	private record Inventory(double volume, double mass, long[] species, cc.ecisr.mmm.thermal.ThermalAuditLedger audit) {
		static Inventory capture(FluidDomain domain) {
			return new Inventory(domain.totalVolume(), domain.totalMass(),
					domain.chemicalParcels().speciesTotals(domain.particleCount()), domain.thermalAudit());
		}
		void check(FluidDomain domain) {
			LiquidStratificationValidation.check(domain.totalVolume() == volume && Math.abs(domain.totalMass() - mass) < 1e-8,
					"stratification changed volume or material mass");
			LiquidStratificationValidation.check(Arrays.equals(species, domain.chemicalParcels().speciesTotals(domain.particleCount())),
					"stratification changed exact species");
			LiquidStratificationValidation.check(domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
					"stratification thermal audit does not close");
			var after = domain.thermalAudit();
			LiquidStratificationValidation.check(after.externalSource().equals(audit.externalSource())
					&& after.externalSink().equals(audit.externalSink()) && after.mechanicalWork().equals(audit.mechanicalWork())
					&& after.finiteDeviceExchange().equals(audit.finiteDeviceExchange())
					&& domain.exactInternalEnergy().subtract(audit.expectedInventory())
							.equals(after.roundoffResidual().subtract(audit.roundoffResidual())),
					"isolated stratification changed the thermal source/sink/work ledger");
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
