package mmm.fluid;

import mmm.granular.BuiltinGranularDefinitions;
import mmm.granular.GranularTransferLedger;
import mmm.granular.SolidVolume;
import mmm.simulation.FullCapabilityTestFixture;
import mmm.thermal.ThermalTransport;
import mmm.thermal.ThermalLimits;
import mmm.world.MovingBodyFluidCoupling;

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
		int settlingSteps = settlingStepLimit(domain);
		int phase = 0, phaseStep = 0;
		for (int step = 0; step < 2 * settlingSteps + 90; step++, phaseStep++) {
			double speed = phase == 1 ? .03 : phase == 2 ? 3.0 : 0;
			var requests = phase == 0 ? List.<MovingBodyFluidCoupling.Request>of()
					: List.of(new MovingBodyFluidCoupling.Request(2, .75F, 2, speed, 0, 0, .75));
			var candidate = backend.evaluate(new FluidStepRequest(step, domain.captureAuthoritativeState(), requests,
					true, ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP));
			domain = candidate.claimPreparedCpuDomain();
			jointCapacity(domain);
			settled += candidate.stats().granularTransition().settledParcels();
			woken += candidate.stats().granularTransition().wokenParcels();
			contacts += candidate.movingBodyCoupling().coupledMarkers();
			if (phase >= 2) peakActive = Math.max(peakActive, domain.granularState.parcels.count());
			require(domain.totalGranularSolidVolumeSvq() == inventory, "Bed transport changed solid volume");
			require(Double.doubleToRawLongBits(domain.totalGranularMass()) == massBits, "Bed transport changed sand mass");
			require(domain.gasBoundary().auditClosed() && domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
					"Bed transport broke gas/energy audit");
			boolean atRest = domain.granularState.parcels.count() == 0
					&& domain.granularDepositDynamics().dirtyColumnCount() == 0;
			if (phase == 0 && atRest) {
				require(settled > 0 && woken == 0 && domain.granularState.parcels.count() == 0
						&& domain.granularDepositDynamics().dirtyColumnCount() == 0, "Initial bed did not settle and sleep");
				sleeping = domain.captureAuthoritativeState();
				initialMoment = depositMoment(domain);
				phase = 1; phaseStep = -1;
			}
			if (phase == 1 && phaseStep == 29) {
				require(woken == 0 && contacts > 0, "Subcritical real liquid caused erosion or missed the body");
				phase = 2; phaseStep = -1;
			} else if (phase == 2 && phaseStep == 59) {
				phase = 3; phaseStep = -1;
			} else if (phase == 3 && atRest) break;
			if ((phase == 0 || phase == 3) && phaseStep >= settlingSteps)
				throw new AssertionError("Bed exceeded the registry-derived settling time: phase=" + phase);
			double maxShields = 0;
			for (var deposit : domain.exportGranularState().depositGroups()) maxShields = Math.max(maxShields,
					GranularBedShear.shields(domain, deposit.cell(), domain.granularState.registry.material(domain.granularState.deposits.dominantGroup(deposit.cell()).materialHandle())));
			if (step % 20 == 19) System.out.printf("bed step=%d speed=%.2f active=%d deposits=%d sleeping=%d settled=%d woken=%d theta=%.6f contacts=%d%n",
					step, speed, domain.granularState.parcels.count(), domain.granularState.deposits.groupCount(),
					domain.granularDepositDynamics().sleepingColumnCount(), settled, woken, maxShields, candidate.movingBodyCoupling().coupledMarkers());
		}
		require(sleeping != null, "Initial bed exceeded the physical settling budget");
		require(woken > 0 && peakActive > 0, "Real water never woke deposited sand");
		require(domain.granularState.parcels.count() == 0 && domain.granularDepositDynamics().dirtyColumnCount() == 0,
				"Sand did not re-settle and sleep after reducing flow");
		require(Math.abs(depositMoment(domain) - initialMoment) > .01, "Released sand did not move horizontally");
		wakeStartsAtRest(sleeping);
		System.out.printf("Bed lifecycle passed: wakes=%d peakActive=%d horizontalMomentChange=%.6f kg*m%n",
				woken, peakActive, depositMoment(domain) - initialMoment);
	}

	private static int settlingStepLimit(FluidDomain domain) {
		var sand = domain.granularState.registry.material(domain.granularState.registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND));
		double rho = domain.config.density(), rhoSolid = sand.referenceDensity(), d = sand.primaryGrainDiameterMeters();
		double mu = domain.materialDefinitions.dynamicViscosityAt("mmm:water", 293.15);
		double stokesSpeed = (rhoSolid - rho) * domain.config.gravity() * d * d / (18 * mu);
		double low = 0, high = stokesSpeed;
		for (int i = 0; i < 64; i++) {
			double speed = (low + high) / 2;
			double resistance = speed * mmm.granular.GranularMechanics.dragResistanceFactor(rho * d * speed / mu);
			if (resistance > stokesSpeed) high = speed; else low = speed;
		}
		// Allow a grain to traverse the entire water depth at its registry-derived terminal speed.
		return (int) Math.ceil(4 * domain.config.cellSize() / high / domain.config.timeStep());
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
		int sand = registries.granular().materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		long svq = mmm.granular.GranularLimits.MAX_PARCEL_VOLUME_SVQ;
		double mass = registries.granular().material(sand).referenceDensity() * svq / SolidVolume.SVQ_PER_BV;
		require(domain.tryInsertExactGranular(1, 0, 1, sand, 0, svq, mass).inserted(), "Occupied wake fixture rejected its retained parcel");
		domain.ensureGrid();
		occupiedWakePointKeepsState(domain);
		var inventory = domain.granularState.inventory; int bedCell = domain.cellIndex(1, 0, 1);
		require(inventory.depositParcel(0, bedCell, SolidVolume.SVQ_PER_CELL), "thermal wake plan fixture failed to retain its bed energy");
		var plan = inventory.planWake(bedCell, sand, 0, svq, .75F, .25F, .75F, 0, 0, 0);
		domain.setThermalTemperatureInCell(1, 1, 1, 303.15);
		long bedRevision = domain.granularState.deposits.mutationRevision(), parcelRevision = domain.granularState.parcels.mutationRevision();
		var energy = domain.exactInternalEnergy(); var audit = domain.thermalAudit(); boolean stale = false;
		try { inventory.commitWake(plan); } catch (IllegalStateException expected) { stale = true; }
		require(stale && domain.granularState.deposits.mutationRevision() == bedRevision
				&& domain.granularState.parcels.mutationRevision() == parcelRevision && domain.exactInternalEnergy().equals(energy)
				&& domain.thermalAudit().equals(audit), "changed liquid thermal generation published a stale bed wake");
		var fresh = inventory.planWake(bedCell, sand, 0, svq, .75F, .25F, .75F, 0, 0, 0);
		inventory.requireCurrent(fresh); inventory.commitWake(fresh);
		require(domain.granularState.parcels.count() == 1 && domain.granularState.deposits.groupCount() == 0
				&& domain.exactInternalEnergy().equals(energy) && domain.thermalAudit().equals(audit),
				"fresh thermal wake plan changed whole-source energy or its audit");
		require(inventory.depositParcel(0, bedCell, SolidVolume.SVQ_PER_CELL), "partial thermal wake fixture failed");
		var key = new mmm.thermal.GranularEnergyColumn.DepositKey(bedCell, sand, 0); long taken = svq / 3;
		double movedEnergy = domain.thermalState.previewDepositTake(key, taken, svq);
		double remainingEnergy = domain.thermalState.granularColumn().previewDepositRemaining(key, taken, svq);
		energy = domain.exactInternalEnergy(); audit = domain.thermalAudit();
		inventory.commitWake(inventory.planWake(bedCell, sand, 0, taken, .75F, .25F, .75F, 0, 0, 0));
		require(Double.doubleToRawLongBits(domain.thermalState.granularColumn().activeEnergy(0)) == Double.doubleToRawLongBits(movedEnergy)
				&& Double.doubleToRawLongBits(domain.thermalState.granularColumn().depositEnergy(key)) == Double.doubleToRawLongBits(remainingEnergy)
				&& domain.exactInternalEnergy().subtract(energy).equals(domain.thermalAudit().roundoffResidual().subtract(audit.roundoffResidual()))
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
				"partial wake changed its prechecked split, append energy or roundoff audit");
	}

	private static void occupiedWakePointKeepsState(FluidDomain domain) {
		var active = domain.granularState.parcels.parcel(0);
		long wakeSvq = active.solidVolumeSvq();
		var planned = domain.granularCoupling.copySolidSvq(); var beforePlanned = planned.clone();
		var beforeBlocked = domain.exportGranularState(); var blockedEnergy = domain.exactInternalEnergy();
		var blockedAudit = domain.thermalAudit(); long blockedRevision = domain.granularState.parcels.mutationRevision();
		require(domain.granularCollision.positionValid(active.x(), active.y(), active.z(), wakeSvq),
				"Blocked wake fixture requires a fixed-geometry-valid release point");
		require(!domain.granularDepositEnvironment(planned).planWake(domain.cellIndex(1, 0, 1), wakeSvq, active.x(), active.y(), active.z())
				&& java.util.Arrays.equals(planned, beforePlanned)
				&& domain.granularState.parcels.mutationRevision() == blockedRevision
				&& domain.exportGranularState().activeParcels().equals(beforeBlocked.activeParcels())
				&& domain.exportGranularState().depositGroups().equals(beforeBlocked.depositGroups())
				&& domain.exactInternalEnergy().equals(blockedEnergy) && domain.thermalAudit().equals(blockedAudit),
				"Occupied wake point changed its planned occupancy, carriers, bed or thermal owners");
	}

	private static double depositMoment(FluidDomain domain) {
		double[] moment = {0};
		domain.granularState.deposits.forEachGroup((cell, porosity, material, composition, svq, mass) ->
				moment[0] += mass * (cell % domain.sizeX + .5) * domain.config.cellSize());
		return moment[0];
	}

	private static void wakeStartsAtRest(FluidDomainState sleeping) {
		var domain = FluidDomain.restoreAuthoritativeState(sleeping);
		require(domain.granularCoupling.totalSolidSvq() == domain.totalGranularSolidVolumeSvq()
				+ domain.totalCoherentSolidVolumeSvq(), "Thermal restore lost granular joint occupancy");
		jointCapacity(domain);
		for (int p = 0; p < domain.particles.count(); p++) {
			domain.particles.setVelocityX(p, 2); domain.particles.setVelocityY(p, 0); domain.particles.setVelocityZ(p, 0);
		}
		domain.gridDirty = true; domain.ensureGrid();
		var beforeU = domain.exactInternalEnergy();
		var beforeAudit = domain.thermalAudit();
		double beforeK = domain.particleKineticEnergyJoules(), beforeMass = domain.totalGranularMass();
		int beforeCount = domain.particles.count();
		var stats = domain.granularTransitionStage.step().depositStats();
		require(stats.wokenParcels() > 0, "Sleeping bed ignored actual liquid velocity");
		require(domain.exactInternalEnergy().subtract(beforeU).equals(
				domain.thermalAudit().roundoffResidual().subtract(beforeAudit.roundoffResidual()))
				&& domain.thermalAudit().externalSource().equals(beforeAudit.externalSource())
				&& domain.thermalAudit().externalSink().equals(beforeAudit.externalSink())
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory())
				&& domain.particleKineticEnergyJoules() <= beforeK + (beforeCount + domain.particles.count()) * Math.ulp(beforeK)
				&& domain.totalGranularMass() == beforeMass, "Bed release created energy or changed inventory: dU=" + domain.exactInternalEnergy().subtract(beforeU)
						+ " dK=" + (domain.particleKineticEnergyJoules() - beforeK) + " dMass=" + (domain.totalGranularMass() - beforeMass));
		domain.ensureGrid();
		jointCapacity(domain);
		for (int p = 0; p < domain.granularState.parcels.count(); p++) {
			var parcel = domain.granularState.parcels.parcel(p);
			require(parcel.velocityX() == 0 && parcel.velocityY() == 0 && parcel.velocityZ() == 0,
					"Bed release invented a launch impulse");
			require(domain.granularCollision.positionValid(parcel.x(), parcel.y(), parcel.z(), parcel.solidVolumeSvq()),
					"Released parcel overlaps collision geometry");
		}
		GranularCarrierValidation.requireNonOverlappingRelease(domain.exportGranularState(), domain.config.cellSize());
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
		FullCapabilityTestFixture.bindFull(domain, r, 293.15, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		long svq = SolidVolume.SVQ_PER_CELL / 4;
		double density = r.granular().material(r.granular().materialHandle(BuiltinGranularDefinitions.FINE_SAND)).referenceDensity();
		var ledger = new GranularTransferLedger(List.of(new GranularTransferLedger.Group(
				BuiltinGranularDefinitions.FINE_SAND, svq, density * svq / SolidVolume.SVQ_PER_BV, null)));
		for (int z = 2; z < 6; z++) for (int x = 2; x < 6; x++) {
			require(domain.tryInsertExactGranular(x, 0, z, ledger, 0, true).inserted(), "Sand rejected");
		}
		domain.ensureGrid();
		for (int z = 0; z < 8; z++) for (int y = 0; y < 4; y++) for (int x = 0; x < 8; x++) {
			float open = domain.effectiveOpenCellVolume(domain.cellIndex(x, y, z));
			require(domain.tryInsertExactVolume(x, y, z, open, 0, water).transferred(), "Pore water rejected");
		}
		float displaced = (float) (256 - domain.totalVolumeExact());
		require(domain.addVolumeNear(4, 4, 4, displaced, 12) == displaced, "Displaced pore water rejected");
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

	private static void jointCapacity(FluidDomain domain) {
		if (!CellLiquidVolumeTransport.enabled()) return;
		for (int c = 0; c < domain.cellCount; c++) require(domain.markerGrid.exactCellVolumeInventory[c].compareTo(
				mmm.units.SignedDyadic.of(domain.effectiveOpenCellVolume(c))) <= 0,
				"Published bed state overlaps liquid and skeleton at cell " + c);
	}

	private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}


