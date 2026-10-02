package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.chemistry.BuiltinChemicalDefinitions;
import cc.ecisr.mmm.chemistry.ChemicalLimits;
import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.chemistry.ExactRational;
import cc.ecisr.mmm.thermal.ThermalCarrierContext;
import cc.ecisr.mmm.thermal.ThermalCellGrid;
import cc.ecisr.mmm.thermal.ThermalDomainState;
import cc.ecisr.mmm.thermal.ThermalMaterialDefinition;
import cc.ecisr.mmm.thermal.ThermalRegistry;
import cc.ecisr.mmm.thermal.ThermalSpeciesOffset;
import cc.ecisr.mmm.thermal.ThermalTransport;
import cc.ecisr.mmm.units.SignedDyadic;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Continuous read-only heat observations and the zero-conductivity Lagrangian transport limit. */
final class LiquidThermalAdvectionValidation extends ThermalValidationSupport {
	public static void main(String[] args) {
		subcellAdvection();
		caloricAndSpeciesObservation();
		insulatingCellsIgnoreUnrelatedFlux();
		geometryRestrictsObservation();
		conductingSpikeRemainsMonotone();
		finiteConductivityLimit();
		particleDiffusionConverges();
		conductingLinearWallProfileIsStationary();
	}

	static void subcellAdvection() {
		double coarseError = 0;
		for (int n : new int[] {8, 16}) {
			double error = 0, firstDerivative = 0;
			for (float dt : new float[] {0.001F, 0.002F}) for (double conductivity : new double[] {0, 1e-6}) {
				var domain = pool(n, n, 2, conductivity);
				double speed = 0.1, gradient = 2;
				for (int marker = 0; marker < domain.particleCount(); marker++)
					domain.thermalState.resetMarkerTemperature(marker, 300 + gradient * domain.particles.positionY(marker),
							domain.thermalMarkerContext(), domain.exactInternalEnergy());
				domain.ensureGrid();
				for (int z = 0; z < 2; z++) for (int y = 0; y < n; y++) for (int x = 0; x <= n; x++)
					domain.mac.velocityX[domain.xFaceIndex(x, y, z)] = (float) (-speed * Math.sin(Math.PI * x / n)
							* Math.cos(Math.PI * (y + 0.5) / n));
				for (int z = 0; z < 2; z++) for (int y = 0; y <= n; y++) for (int x = 0; x < n; x++)
					domain.mac.velocityY[domain.yFaceIndex(x, y, z)] = (float) (speed * Math.cos(Math.PI * (x + 0.5) / n)
							* Math.sin(Math.PI * y / n));
				int cell = domain.cellIndex(n / 4, n / 2 - 1, 0);
				domain.thermalState.prepareSpatialObservation(domain.thermalMarkerContext());
				double before = domain.thermalState.spatialTemperatureInCell(cell);
				var expansion = cc.ecisr.mmm.thermal.LiquidThermalExpansion.require("mmm:water");
				double beforeBuoyancy = domain.thermalBuoyancyProperties().relativeBuoyancy(cell);
				require(close(beforeBuoyancy, expansion.relativeBuoyancy(before)), "mechanical observation lost a linear thermal field");
				require(close(before, 300 + gradient * (n / 2 - 0.5) * 0.5), "spatial observation lost a linear field");
				double[] energies = domain.exportThermalParticleState().copyInternalEnergy();
				int[] cells = new int[domain.particleCount()];
				for (int marker = 0; marker < cells.length; marker++) cells[marker] = domain.particleCellIndex(marker);
				var audit = domain.thermalAudit(); double volume = domain.totalVolume(), mass = domain.totalMass();
				domain.particleAdvectionStage.advect(dt);
				domain.ensureGrid();
				var transport = domain.stepThermalOnly(dt);
				domain.thermalState.prepareSpatialObservation(domain.thermalMarkerContext());
				double derivative = (domain.thermalState.spatialTemperatureInCell(cell) - before) / dt;
				var properties = domain.thermalBuoyancyProperties();
				double buoyancyDerivative = (properties.relativeBuoyancy(cell) - beforeBuoyancy)
						/ expansion.volumetricCoefficientPerK() / dt;
				require(Math.abs(buoyancyDerivative - derivative) < 1e-8 && Math.abs(properties.density(cell) - 1000) < 16 * Math.ulp(1000.0),
						"mechanical buoyancy lost subcell advection: thermal=" + derivative + " buoyancy=" + buoyancyDerivative
								+ " rho=" + properties.density(cell));
				double expected = -gradient * speed * Math.cos(Math.PI * (n / 4 + 0.5) / n)
						* Math.sin(Math.PI * (n / 2 - 0.5) / n);
				error = Math.max(error, relativeError(derivative, expected));
				double[] currentEnergies = domain.exportThermalParticleState().copyInternalEnergy();
				require(error < 0.025 && domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory())
						&& domain.totalVolume() == volume && domain.totalMass() == mass,
						"thermal advection missed its continuous response or conservation: " + derivative);
				if (conductivity == 0) require(transport.maximumAbsoluteTransferEu() == 0 && Arrays.equals(energies, currentEnergies)
						&& audit.equals(domain.thermalAudit()), "zero-conductivity advection changed raw energy");
				else require(transport.maximumTemperatureChangeK() < 1e-6 && LiquidThermalConduction.contacts(domain) != null,
						"positive small conductivity lost continuous carrier transport");
				for (int marker = 0; marker < cells.length; marker++) require(cells[marker] == domain.particleCellIndex(marker),
						"subcell advection fixture crossed a cell");
				if (firstDerivative != 0) require(relativeError(derivative, firstDerivative) < 0.002,
						"subcell thermal response depends on the perturbation time");
				firstDerivative = derivative;
				var restored = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
				restored.ensureGrid(); restored.thermalState.prepareSpatialObservation(restored.thermalMarkerContext());
				require(Arrays.equals(currentEnergies, restored.exportThermalParticleState().copyInternalEnergy())
						&& domain.thermalAudit().equals(restored.thermalAudit()) && close(restored.thermalState.spatialTemperatureInCell(cell),
						domain.thermalState.spatialTemperatureInCell(cell)), "thermal advection handoff lost U or spatial response");
				System.out.printf("  thermal advection N=%d k=%g dt=%g derivative=%g expected=%g K/s error=%.4f%%%n",
						n, conductivity, dt, derivative, expected, 100 * relativeError(derivative, expected));
			}
			if (n == 8) coarseError = error;
			else require(error < 0.35 * coarseError, "subcell thermal observation failed spatial convergence");
		}
	}

	static void caloricAndSpeciesObservation() {
		var chemistry = BuiltinChemicalDefinitions.REGISTRY;
		var first = new ThermalMaterialDefinition("test:thermal/first", ThermalMaterialDefinition.CarrierKind.LIQUID,
				"test:first", 300, -10000, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(200, 300, 10),
				new ThermalMaterialDefinition.CaloricSegment(300, 400, 20)));
		var second = new ThermalMaterialDefinition("test:thermal/second", ThermalMaterialDefinition.CarrierKind.LIQUID,
				"test:second", 280, 20000, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(200, 320, 30),
				new ThermalMaterialDefinition.CaloricSegment(320, 400, 50)));
		var registry = new ThermalRegistry(List.of(first, second), List.of(
				new ThermalSpeciesOffset("test:offset/first", BuiltinChemicalDefinitions.H2O, first.id(), ExactRational.of(100000)),
				new ThermalSpeciesOffset("test:offset/second", BuiltinChemicalDefinitions.H2O, second.id(), ExactRational.of(-200000))),
				List.of(), chemistry.fingerprint(), "");
		var mixtures = new ChemicalMixtureRecord[] {
				ChemicalMixtureRecord.fromStableAmounts(chemistry, Map.of(BuiltinChemicalDefinitions.H2O, ChemicalLimits.QUANTA_PER_AU), 0),
				ChemicalMixtureRecord.fromStableAmounts(chemistry, Map.of(BuiltinChemicalDefinitions.H2O, 2 * ChemicalLimits.QUANTA_PER_AU), 0)};
		var markers = new ThermalCarrierContext() {
			@Override public int markerCount() { return 2; }
			@Override public int cell(int marker) { return marker; }
			@Override public int fluidMaterialHandle(int marker) { return marker; }
			@Override public double mass(int marker) { return mixtures[marker].massKilograms(chemistry); }
			@Override public double volume(int marker) { return 1; }
			@Override public ChemicalMixtureRecord mixture(int marker) { return mixtures[marker]; }
			@Override public ChemicalRegistry chemicalRegistry() { return chemistry; }
			@Override public void observationSupport(int marker, SupportConsumer consumer) {
				consumer.accept(0, marker == 0 ? 0.75 : 0.25); consumer.accept(1, marker == 0 ? 0.25 : 0.75);
			}
		};
		var thermal = new ThermalDomainState(registry, new int[] {0, 1}, new int[0], 300, 2, 2, 1, 1, 1, markers, null, 8);
		thermal.resetMarkerTemperature(0, 270, markers, thermal.exactEnergy(2));
		thermal.resetMarkerTemperature(1, 350, markers, thermal.exactEnergy(2));
		var before = thermal.exactEnergy(2); var audit = thermal.auditLedger();
		thermal.prepareSpatialObservation(markers);
		for (int cell = 0; cell < 2; cell++) {
			double w0 = cell == 0 ? 0.75 : 0.25, w1 = 1 - w0;
			double target = w0 * markers.mass(0) * first.sensibleSpecificEnergyAt(270)
					+ w1 * markers.mass(1) * second.sensibleSpecificEnergyAt(350);
			double t = thermal.spatialTemperatureInCell(cell);
			double modeled = w0 * markers.mass(0) * first.sensibleSpecificEnergyAt(t)
					+ w1 * markers.mass(1) * second.sensibleSpecificEnergyAt(t);
			require(t >= 270 && t <= 350 && relativeError(modeled, target) < 1e-12,
					"spatial observation lost heterogeneous caloric or species reference energy");
		}
		thermal.step(markers, (x, y, z, axis) -> 1, 1000);
		require(before.equals(thermal.exactEnergy(2)) && audit.equals(thermal.auditLedger()),
				"insulating heterogeneous carriers changed energy or the external ledger");
		// The contact processor reads the real integer-species reference energy, with no LTE handoff.
		for (double conductance : new double[] {1e-8, 1e6}) {
			thermal.stepCarrierConduction(markers, List.of(new cc.ecisr.mmm.thermal.ThermalCarrierTransport.Contact(
					0, 1, conductance, Double.NaN)), conductance < 1 ? 0.001 : 1000);
			for (int p = 0; p < 2; p++) require(thermal.temperature(p, markers) >= 270 && thermal.temperature(p, markers) <= 350,
					"carrier contact lost heterogeneous caloric or species reference energy");
			require(thermal.exactEnergy(2).equals(thermal.auditLedger().expectedInventory())
					&& audit.externalSource().equals(thermal.auditLedger().externalSource())
					&& audit.externalSink().equals(thermal.auditLedger().externalSink())
					&& markers.mixture(0).equals(mixtures[0]) && markers.mixture(1).equals(mixtures[1]),
					"carrier conduction changed species or external energy");
		}
	}

	static void geometryRestrictsObservation() {
		var domain = pool(2, 2, 2, 0);
		for (int z = 0; z < 2; z++) {
			domain.setVolume(1, 0, z, 0); domain.setVolume(0, 1, z, 0);
			domain.setThermalTemperatureInCell(0, 0, z, 270); domain.setThermalTemperatureInCell(1, 1, z, 350);
		}
		domain.ensureGrid(); domain.thermalState.prepareSpatialObservation(domain.thermalMarkerContext());
		require(close(domain.thermalState.spatialTemperatureInCell(0), 270)
				&& close(domain.thermalState.spatialTemperatureInCell(3), 350), "thermal observation crossed an empty diagonal gap");
		domain.setSolidMicroMask(0, 0, 0, 0x0f);
		domain.ensureGrid();
		for (int marker = 0; marker < domain.particleCount(); marker++) if (domain.particleCellIndex(marker) == 0)
			domain.thermalMarkerContext().observationSupport(marker, (cell, weight) ->
					require(cell == 0 && weight == 1, "partial cut-cell observation left its inventory cell"));
		var separated = new FluidDomain(2, 2, 2, domain.config, 128);
		for (int z = 0; z < 2; z++) for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) separated.setVolume(x, y, z, 1);
		var liquids = new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER, BuiltinFluidMaterials.OIL));
		for (int marker = 0; marker < separated.particleCount(); marker++)
			separated.setMarkerMaterial(marker, liquids, separated.particleCellIndex(marker) % 2);
		separated.ensureGrid();
		for (int marker = 0; marker < separated.particleCount(); marker++) {
			int own = separated.particleCellIndex(marker);
			separated.thermalMarkerContext().observationSupport(marker, (cell, weight) ->
					require(cell % 2 == own % 2 && weight > 0 && weight <= 1, "spatial observation mixed immiscible pressure phases"));
		}
	}

	static void insulatingCellsIgnoreUnrelatedFlux() {
		for (double dt : new double[] {0.05, 1000}) {
			var insulating = new ThermalMaterialDefinition("test:thermal/insulating", ThermalMaterialDefinition.CarrierKind.LIQUID,
					"test:insulating", 300, 0, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(1, 500, 10)));
			var conducting = new ThermalMaterialDefinition("test:thermal/conducting", ThermalMaterialDefinition.CarrierKind.LIQUID,
					"test:conducting", 300, 0, 1000, insulating.caloricSegments());
			double[] energies = {-2990, 500, 500, -2990};
			var grid = new ThermalCellGrid(3);
			grid.rebuild(new ThermalCellGrid.CarrierAccess() {
				@Override public int carrierCount() { return 4; }
				@Override public int cell(int carrier) { return Math.max(0, carrier - 1); }
				@Override public ThermalMaterialDefinition material(int carrier) { return carrier < 2 ? insulating : conducting; }
				@Override public double mass(int carrier) { return 1; }
				@Override public double volume(int carrier) { return 1; }
				@Override public double baseEnergy(int carrier) { return 0; }
				@Override public double internalEnergy(int carrier) { return energies[carrier]; }
				@Override public void setInternalEnergy(int carrier, double value) { energies[carrier] = value; }
			});
			var before = SignedDyadic.sum(energies);
			var stats = new ThermalTransport(3, 1, 1, 1, 8).step(grid, (x, y, z, axis) -> 1, dt);
			require(energies[0] == -2990 && energies[1] == 500 && energies[2] < 500 && energies[3] > -2990
					&& SignedDyadic.sum(energies).subtract(before).equals(stats.roundoffResidual()),
					"unrelated explicit/implicit contact equilibrated an insulating cell");
		}
	}

	static void conductingSpikeRemainsMonotone() {
		for (double dt : new double[] {0.05, 1000}) for (boolean hotSpike : new boolean[] {false, true}) {
			var domain = pool(6, 6, 6, 1000);
			for (int z = 0; z < 6; z++) for (int y = 0; y < 6; y++) for (int x = 0; x < 6; x++)
				domain.setThermalTemperatureInCell(x, y, z, hotSpike ? 1 : 350);
			domain.setThermalTemperatureInCell(3, 3, 3, hotSpike ? 350 : 1);
			var before = domain.thermalAudit();
			for (int step = 0; step < 3; step++) {
				domain.stepThermalOnly(dt);
				domain.thermalState.prepareSpatialObservation(domain.thermalMarkerContext());
				for (int marker = 0; marker < domain.particleCount(); marker++) {
					double t = domain.thermalState.temperature(marker, domain.thermalMarkerContext());
					require(t >= 1 - 1e-10 && t <= 350 + 1e-10, "conducting spike violated the carrier maximum principle: " + t);
				}
				for (int cell = 0; cell < domain.cellCount; cell++) {
					double t = domain.thermalState.spatialTemperatureInCell(cell);
					require(t >= 1 - 1e-10 && t <= 350 + 1e-10, "spatial observation exceeded its carrier temperature range");
				}
				require(domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory())
						&& before.externalSource().equals(domain.thermalAudit().externalSource())
						&& before.externalSink().equals(domain.thermalAudit().externalSink()), "adiabatic spike energy did not close");
			}
		}
	}

	static void finiteConductivityLimit() {
		double first = 0;
		for (double k : new double[] {0.001, 0.01}) {
			var domain = pool(4, 4, 4, k);
			var context = domain.thermalMarkerContext();
			domain.thermalState.resetMarkerTemperature(0, 270, context, domain.exactInternalEnergy());
			domain.thermalState.resetMarkerTemperature(1, 350, context, domain.exactInternalEnergy());
			double before = domain.thermalState.temperature(0, context);
			var audit = domain.thermalAudit();
			domain.stepThermalOnly(1);
			double change = domain.thermalState.temperature(0, context) - before;
			require(change > 0 && change < 0.001, "positive small k caused instantaneous intracell equilibration: " + change);
			if (first > 0) require(Math.abs(change / first - 10) < 0.001, "carrier conduction lost its linear k limit");
			first = change;
			require(domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory())
					&& audit.externalSource().equals(domain.thermalAudit().externalSource())
					&& audit.externalSink().equals(domain.thermalAudit().externalSink()), "finite-k inventory failed closure");
		}
	}

	static void particleDiffusionConverges() {
		double coarse = 0;
		for (int n : new int[] {8, 16}) {
			var domain = pool(4, n, 2, 4e6); // alpha = 1 m^2/s from the actual rho, cv and k.
			var context = domain.thermalMarkerContext();
			double h = domain.config.cellSize(), height = h * n, before = 0, norm = 0;
			for (int p = 0; p < domain.particleCount(); p++) {
				double mode = Math.cos(Math.PI * domain.particles.positionY(p) / height);
				domain.thermalState.resetMarkerTemperature(p, 300 + 2 * mode, context, domain.exactInternalEnergy());
				before += mode * (domain.thermalState.temperature(p, context) - 300); norm += mode * mode;
			}
			double dt = 0.0001;
			domain.stepThermalOnly(dt);
			double after = 0;
			for (int p = 0; p < domain.particleCount(); p++) after += Math.cos(Math.PI * domain.particles.positionY(p) / height)
					* (domain.thermalState.temperature(p, context) - 300);
			double normalizedRate = (1 - after / before) / dt * height * height;
			double error = Math.abs(normalizedRate / (Math.PI * Math.PI) - 1);
			require(error < 0.02 && Math.abs(before / norm - 2) < 1e-10, "particle diffusion has a mesh-independent diffusivity bias: rate=" + normalizedRate + " amplitude=" + before / norm);
			if (n == 8) coarse = error;
			else require(error < 0.3 * coarse, "particle conduction failed second-order diffusion convergence");
			System.out.printf("  finite-k diffusion N=%d normalizedRate=%g expected=%g error=%.4f%%%n", n, normalizedRate, Math.PI * Math.PI, 100 * error);
		}
	}

	static void conductingLinearWallProfileIsStationary() {
		var domain = pool(4, 6, 2, 4e6);
		double height = domain.sizeY * domain.config.cellSize();
		var context = domain.thermalMarkerContext();
		double[] boundary = new double[domain.cellCount * 6]; Arrays.fill(boundary, Double.NaN);
		for (int p = 0; p < domain.particleCount(); p++) domain.thermalState.resetMarkerTemperature(p,
				310 - 20 * domain.particles.positionY(p) / height, context, domain.exactInternalEnergy());
		for (int z = 0; z < domain.sizeZ; z++) for (int x = 0; x < domain.sizeX; x++) {
			boundary[6 * domain.cellIndex(x, 0, z) + 2] = 310;
			boundary[6 * domain.cellIndex(x, domain.sizeY - 1, z) + 3] = 290;
		}
		domain.replaceThermalBoundaryTemperatures(boundary);
		double[] energy = domain.exportThermalParticleState().copyInternalEnergy();
		var result = domain.stepThermalOnly(0.001);
		double maximum = 0;
		for (int p = 0; p < domain.particleCount(); p++) maximum = Math.max(maximum,
				Math.abs(domain.thermalState.temperature(p, context) - (310 - 20 * domain.particles.positionY(p) / height)));
		require(maximum < 1e-10 && result.maximumAbsoluteTransferEu() > 0
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()), "thermal wall closure lost the affine stationary solution: " + maximum);
		// A finite contact must have been evaluated while the net energy remained stationary.
		require(Math.abs(SignedDyadic.sum(domain.exportThermalParticleState().copyInternalEnergy()).subtract(SignedDyadic.sum(energy)).toDouble()) < 1e-7,
				"opposed fixed walls introduced net energy into a stationary profile");
	}

	private static FluidDomain pool(int nx, int ny, int nz, double conductivity) {
		var domain = new FluidDomain(nx, ny, nz,
				new FluidSolverConfig(0.5F, 1000, 0, 0.05F, 100, 1e-7F, 0.4F, 0.002F, 0), nx * ny * nz * 16);
		for (int z = 0; z < nz; z++) for (int y = 0; y < ny; y++) for (int x = 0; x < nx; x++) domain.setVolume(x, y, z, 1);
		var liquids = new FluidMaterialRegistry(List.of(BuiltinFluidMaterials.WATER));
		var caloric = new ThermalMaterialDefinition("test:thermal/advection", ThermalMaterialDefinition.CarrierKind.LIQUID,
				"mmm:water", 300, 0, conductivity, List.of(new ThermalMaterialDefinition.CaloricSegment(1, 500, 4000)));
		domain.bindThermal(new ThermalRegistry(List.of(caloric)), liquids, 300);
		return domain;
	}
}
