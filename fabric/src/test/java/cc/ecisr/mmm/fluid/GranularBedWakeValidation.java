package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.granular.BuiltinGranularDefinitions;
import cc.ecisr.mmm.granular.GranularTransferLedger;
import cc.ecisr.mmm.granular.SolidVolume;
import cc.ecisr.mmm.simulation.FullCapabilityTestFixture;
import cc.ecisr.mmm.thermal.ThermalTransport;
import cc.ecisr.mmm.thermal.ThermalLimits;
import cc.ecisr.mmm.world.MovingBodyFluidCoupling;

import java.util.List;

/** Real liquid -> sleeping deposit -> active parcel path, using production registries and backend handoff. */
public final class GranularBedWakeValidation {
	private GranularBedWakeValidation() { }

	public static void main(String[] args) throws Exception { validate(); }

	public static void validate() throws Exception {
		carrierTemperatureRefreshAndDiagnostics();
		wallLawLimits();
		var domain = fixture();
		var backend = new CpuFluidSolverBackend();
		long inventory = domain.totalGranularSolidVolumeSvq();
		long massBits = Double.doubleToRawLongBits(domain.totalGranularMass());
		long settled = 0, woken = 0, contacts = 0;
		int peakActive = 0;
		FluidDomainState sleeping = null;
		double initialMoment = 0;
		for (int step = 0; step < 400; step++) {
			double speed = step < 100 ? 0 : step < 130 ? .03 : step < 190 ? 3.0 : 0;
			var requests = step < 100 ? List.<MovingBodyFluidCoupling.Request>of()
					: List.of(new MovingBodyFluidCoupling.Request(2, .75F, 2, speed, 0, 0, .75));
			var candidate = backend.evaluate(new FluidStepRequest(step, domain.captureAuthoritativeState(), requests,
					true, ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP));
			domain = candidate.claimPreparedCpuDomain();
			settled += candidate.stats().granularTransition().settledParcels();
			woken += candidate.stats().granularTransition().wokenParcels();
			contacts += candidate.movingBodyCoupling().coupledMarkers();
			if (step >= 130) peakActive = Math.max(peakActive, domain.granularState.parcels.count());
			require(domain.totalGranularSolidVolumeSvq() == inventory, "Bed transport changed solid volume");
			require(Double.doubleToRawLongBits(domain.totalGranularMass()) == massBits, "Bed transport changed sand mass");
			require(domain.gasBoundary().auditClosed() && domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
					"Bed transport broke gas/energy audit");
			if (step == 99) {
				require(settled > 0 && woken == 0 && domain.granularState.parcels.count() == 0
						&& domain.granularDepositDynamics().dirtyColumnCount() == 0, "Initial bed did not settle and sleep");
				sleeping = domain.captureAuthoritativeState();
				initialMoment = depositMoment(domain);
			}
			if (step == 129) require(woken == 0 && contacts > 0, "Subcritical real liquid caused erosion or missed the body");
			double maxShields = 0;
			for (var deposit : domain.exportGranularState().depositGroups()) maxShields = Math.max(maxShields,
					GranularBedShear.shields(domain, deposit.cell(), domain.granularState.registry.material(domain.granularState.deposits.dominantGroup(deposit.cell()).materialHandle())));
			if (step % 20 == 19) System.out.printf("bed step=%d speed=%.2f active=%d deposits=%d sleeping=%d settled=%d woken=%d theta=%.6f contacts=%d%n",
					step, speed, domain.granularState.parcels.count(), domain.granularState.deposits.groupCount(),
					domain.granularDepositDynamics().sleepingColumnCount(), settled, woken, maxShields, candidate.movingBodyCoupling().coupledMarkers());
		}
		require(woken > 0 && peakActive > 0, "Real water never woke deposited sand");
		require(domain.granularState.parcels.count() == 0 && domain.granularDepositDynamics().dirtyColumnCount() == 0,
				"Sand did not re-settle and sleep after reducing flow");
		require(Math.abs(depositMoment(domain) - initialMoment) > .01, "Released sand did not move horizontally");
		wakeStartsAtRest(sleeping);
		System.out.printf("Bed lifecycle passed: wakes=%d peakActive=%d horizontalMomentChange=%.6f kg*m%n",
				woken, peakActive, depositMoment(domain) - initialMoment);
	}

	private static void carrierTemperatureRefreshAndDiagnostics() throws Exception {
		var domain = new FluidDomain(4, 4, 4,
				new FluidSolverConfig(.5F, 1000, 0, .05F, 200, 1e-5F, .45F, .002F, .8F), 512);
		var registries = FullCapabilityTestFixture.load();
		FullCapabilityTestFixture.bindFull(domain, registries, 293.15, 4);
		domain.setVolume(1, 1, 1, .5F);
		int cell = domain.cellIndex(1, 1, 1);
		domain.carrierPropertiesWorkspace.beginStep();
		for (double temperature : new double[] {1, 5000, 353.15}) {
			domain.setThermalTemperatureInCell(1, 1, 1, temperature);
			var before = domain.exactInternalEnergy();
			double expected = registries.materials().dynamicViscosityExtendingAt("mmm:water", temperature);
			for (int repeat = 0; repeat < 3; repeat++) {
				require(Math.abs(domain.carrierViscosityPaPerS(cell) - expected) < expected * 1e-12,
						"Carrier properties retained an obsolete thermal state");
				require(Math.abs(domain.granularMechanicalStage.sample(.75F, .75F, .75F).dynamicViscosity() - expected)
						< expected * 1e-12, "Drag and bed shear must use the refreshed viscosity");
				require(Math.abs(domain.granularCarrierProperties(cell).viscosity() - expected) < expected * 1e-12,
						"Darcy damping must use the same refreshed viscosity");
			}
			require(domain.exactInternalEnergy().equals(before), "Property observation changed authoritative energy");
		}
		var stats = domain.carrierPropertiesWorkspace.finishStep();
		require(stats.extrapolatedCells() == 2 && stats.sample().contains("mmm:water"),
				"Step diagnostics must retain earlier excursions and count each build once");
		domain.setThermalTemperatureInCell(1, 1, 1, 5000);
		for (int repeat = 0; repeat < 3; repeat++) require(domain.carrierViscosityStats().equals(stats),
				"Reading diagnostics must retain the last completed step");
		domain.carrierPropertiesWorkspace.beginStep();
		domain.carrierViscosityPaPerS(cell);
		require(domain.carrierViscosityStats().equals(stats), "An unfinished attempt published diagnostics");
		domain.carrierPropertiesWorkspace.beginStep();
		domain.setThermalTemperatureInCell(1, 1, 1, 293.15);
		domain.carrierViscosityPaPerS(cell);
		require(!domain.carrierPropertiesWorkspace.finishStep().extrapolated(),
				"Retried step inherited a discarded attempt's excursions");
	}

	private static double depositMoment(FluidDomain domain) {
		double[] moment = {0};
		domain.granularState.deposits.forEachGroup((cell, porosity, material, composition, svq, mass) ->
				moment[0] += mass * (cell % domain.sizeX + .5) * domain.config.cellSize());
		return moment[0];
	}

	private static void wakeStartsAtRest(FluidDomainState sleeping) {
		var domain = FluidDomain.restoreAuthoritativeState(sleeping);
		for (int p = 0; p < domain.particles.count(); p++) {
			domain.particles.setVelocityX(p, 2); domain.particles.setVelocityY(p, 0); domain.particles.setVelocityZ(p, 0);
		}
		domain.gridDirty = true; domain.ensureGrid();
		var beforeU = domain.exactInternalEnergy();
		double beforeK = domain.particleKineticEnergyJoules(), beforeMass = domain.totalGranularMass();
		var stats = domain.granularTransitionStage.step();
		require(stats.wokenParcels() > 0, "Sleeping bed ignored actual liquid velocity");
		require(domain.exactInternalEnergy().equals(beforeU) && domain.particleKineticEnergyJoules() == beforeK
				&& domain.totalGranularMass() == beforeMass, "Bed release created energy or changed inventory");
		for (int p = 0; p < domain.granularState.parcels.count(); p++) {
			var parcel = domain.granularState.parcels.parcel(p);
			require(parcel.velocityX() == 0 && parcel.velocityY() == 0 && parcel.velocityZ() == 0,
					"Bed release invented a launch impulse");
			require(domain.granularCollision.positionValid(parcel.x(), parcel.y(), parcel.z(), parcel.solidVolumeSvq()),
					"Released parcel overlaps collision geometry");
		}
		var group = domain.exportGranularState().depositGroups().getFirst();
		int cell = group.cell(), above = cell + domain.sizeX;
		var material = domain.granularState.registry.material(domain.granularState.deposits.dominantGroup(cell).materialHandle());
		require(GranularBedShear.shields(domain, cell, material) > material.criticalShields(), "Wet bed shear missing");
		int count = domain.markerGrid.markerCount[above];
		int bedCount = domain.markerGrid.markerCount[cell];
		domain.markerGrid.markerCount[cell] = 0;
		require(GranularBedShear.shields(domain, cell, material) == 0, "Liquid separated by a dry cell eroded the bed");
		domain.markerGrid.markerCount[cell] = bedCount;
		domain.markerGrid.markerCount[above] = 0;
		require(GranularBedShear.shields(domain, cell, material) == 0, "Extrapolated dry-cell velocity eroded the bed");
		domain.markerGrid.markerCount[above] = count;
		domain.geometry.cutCells.replaceMask(above, 0xff);
		require(GranularBedShear.shields(domain, cell, material) == 0, "Flow across a solid barrier eroded the bed");
	}

	private static FluidDomain fixture() throws Exception {
		var r = FullCapabilityTestFixture.load();
		var domain = new FluidDomain(8, 8, 8,
				new FluidSolverConfig(.5F, 1000, 9.81F, .05F, 400, 1e-5F, .45F, .002F, .8F), 4096);
		domain.sealDomainBoundary();
		FullCapabilityTestFixture.bindEmptyMaterialState(domain, r.liquids());
		int water = r.liquids().handle("mmm:water");
		for (int z = 0; z < 8; z++) for (int y = 0; y < 4; y++) for (int x = 0; x < 8; x++) {
			require(domain.tryInsertExactVolume(x, y, z, 1, 0, water).transferred(), "Water rejected");
		}
		FullCapabilityTestFixture.bindFull(domain, r, 293.15, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		long svq = SolidVolume.SVQ_PER_CELL / 4;
		double density = r.granular().material(r.granular().materialHandle(BuiltinGranularDefinitions.FINE_SAND)).referenceDensity();
		var ledger = new GranularTransferLedger(List.of(new GranularTransferLedger.Group(
				BuiltinGranularDefinitions.FINE_SAND, svq, density * svq / SolidVolume.SVQ_PER_BV, null)));
		for (int z = 2; z < 6; z++) for (int x = 2; x < 6; x++) {
			require(domain.tryInsertExactGranular(x, 0, z, ledger, 0, true).inserted(), "Sand rejected");
		}
		return domain;
	}

	private static void wallLawLimits() {
		double previous = 0;
		for (double speed = 1e-9; speed < 10; speed *= 1.5) {
			double stress = GranularBedShear.shearStress(speed, .25, 1000, .001002);
			require(Double.isFinite(stress) && stress > previous, "Bed shear must grow continuously with speed");
			previous = stress;
		}
		double viscous = GranularBedShear.shearStress(1e-8, .001, 1000, .001002);
		require(Math.abs(viscous / (.001002 * 1e-8 / .001) - 1) < 1e-8, "Low Reynolds bed shear lost viscous limit");
		double criticalStress = .05 * (2600 - 1000) * 9.81 * .0005;
		require(GranularBedShear.shearStress(.05, .25, 1000, .001002) < criticalStress
				&& GranularBedShear.shearStress(1, .25, 1000, .001002) > criticalStress,
				"Water/sand initiation scale is inconsistent with wall shear");
	}

	private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}


