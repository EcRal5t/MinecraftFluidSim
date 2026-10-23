package mmm.fluid;

import mmm.granular.BuiltinGranularDefinitions;
import mmm.granular.SolidVolume;
import mmm.simulation.FullCapabilityTestFixture;
import mmm.thermal.ThermalTransport;
import mmm.thermal.ThermalLimits;
import mmm.world.MovingBodyFluidCoupling;

import java.util.List;

/** One released parcel settles onto a static bed before weak shear, erosion and re-settling through the backend. */
public final class GranularBedWakeValidation {
	private GranularBedWakeValidation() { }

	public static void main(String[] args) throws Exception {
		if (args.length == 0) validate();
		else if (args.length == 1 && args[0].equals("--energy")) { macLiftPaysPotentialAndRecoil(); wallLawLimits(); System.out.println("MAC bed wake energy validation passed"); }
		else if (args.length == 1 && args[0].equals("--startup")) startupContinuesAfterRestore();
		else throw new IllegalArgumentException("Bed validation accepts --startup or --energy");
	}

	/** Isolates the bed-to-active potential change and its MAC traction; no pressure step. */
	private static void macLiftPaysPotentialAndRecoil() {
		var config=new FluidSolverConfig(.5F,1000,9.81F,.01F,120,1e-6F,.002F);
		var domain=new FluidDomain(3,3,3,config);
		int cell=domain.domainFlowFields.cellIndex(1,1,1);
		domain.setVolume(1,1,1,.5F);domain.setVolume(1,2,1,.5F);domain.domainFlowFields.preparePhysicalState();
		for(int f=0;f<domain.mac.velocityX.length;f++)if(domain.cellMacStage.massX[f]>0)domain.mac.velocityX[f]=2;
		for(int f=0;f<domain.mac.velocityY.length;f++)if(domain.cellMacStage.massY[f]>0)domain.mac.velocityY[f]=.25F;
		for(int f=0;f<domain.mac.velocityZ.length;f++)if(domain.cellMacStage.massZ[f]>0)domain.mac.velocityZ[f]=-1;
		domain.cellMacStage.publishMomentum();
		var registry=BuiltinGranularDefinitions.createRegistry();int sand=registry.materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		var deposits=new mmm.granular.GranularDepositStore(registry,27,8);
		long svq=SolidVolume.SVQ_PER_CELL/64;
		long mass=mmm.units.Mass.fromKilograms(registry.material(sand).referenceDensity()*svq/SolidVolume.SVQ_PER_CUBIC_METER);
		var portion=new mmm.granular.GranularDepositStore.SolidPortion(sand,0,svq,mass);
		deposits.commitAdditions(deposits.planAdditions(cell,List.of(portion),SolidVolume.SVQ_PER_CELL));
		var parcels=new mmm.granular.GranularParcelStore(registry,8,3,3,3,.5);
		var inventory=new mmm.granular.GranularInventory(parcels,deposits);
		var wake=inventory.planWake(cell,sand,0,svq,.75F,1.1F,.75F,0,0,0);
		var work=mmm.granular.GranularBedPotential.wakePotentialChange(deposits,cell,wake.moved(),wake.parcel().y(),.5F,3,3,config.gravity());
		require(work.signum()>0,"Bed work fixture has no lift potential");
		var beforeEnergy=macHorizontalEnergy(domain);double beforeX=sum(domain.mac.momentumX),beforeZ=sum(domain.mac.momentumZ);
		var vertical=domain.mac.momentumY.clone();var liquid=domain.exportLiquidCellState().stocks();
		var environment=domain.domainStep.granularDepositEnvironment(new long[27]);
		require(environment.planWakeWork(cell,work),"Resolved MAC flow could not pay its bed lift");
		environment.requireWakeWorkCurrent();inventory.requireCurrent(wake);
		inventory.commitWake(wake);environment.commitWakeWork();
		var removed=beforeEnergy.subtract(macHorizontalEnergy(domain));
		require(removed.equals(work.add(environment.wakeWorkRounding())),"Bed lift lost its explicit mechanical rounding");
		require(Math.abs(removed.toDouble()-work.toDouble())<=32*Math.ulp(beforeEnergy.toDouble()),"Bed lift missed its gravitational work");
		require(Math.abs(domain.lastGranularBoundaryImpulseX-(beforeX-sum(domain.mac.momentumX)))<=16*Math.ulp(Math.abs(beforeX))
				&&Math.abs(domain.lastGranularBoundaryImpulseZ-(beforeZ-sum(domain.mac.momentumZ)))<=16*Math.ulp(Math.abs(beforeZ)),"Bed traction reaction differs from actual MAC impulse");
		require(java.util.Arrays.equals(vertical,domain.mac.momentumY)&&liquid.equals(domain.exportLiquidCellState().stocks()),"Bed traction changed vertical momentum or material inventory");
		require(parcels.count()==1&&parcels.parcel(0).velocityX()==0&&parcels.parcel(0).velocityY()==0&&parcels.parcel(0).velocityZ()==0,"Bed release invented launch momentum");
		var current=macHorizontalEnergy(domain);
		require(GranularBedShear.planLift(domain,cell,current.add(mmm.units.SignedDyadic.of(1)))==null,"Bed lift spent unavailable MAC energy");
		var stale=GranularBedShear.planLift(domain,cell,1);int face=domain.domainFlowFields.xFaceIndex(1,1,1);domain.mac.momentumX[face]+=.25;
		var x=domain.mac.momentumX.clone();var z=domain.mac.momentumZ.clone();boolean rejected=false;
		try{stale.apply(domain);}catch(IllegalStateException expected){rejected=true;}
		require(rejected&&java.util.Arrays.equals(x,domain.mac.momentumX)&&java.util.Arrays.equals(z,domain.mac.momentumZ),"Stale bed traction changed a MAC face");
	}
	private static mmm.units.SignedDyadic macHorizontalEnergy(FluidDomain domain){
		var result=mmm.units.SignedDyadic.ZERO;
		for(int axis:new int[]{0,2}){
			double[] momentum=axis==0?domain.mac.momentumX:domain.mac.momentumZ,mass=axis==0?domain.cellMacStage.massX:domain.cellMacStage.massZ;
			for(int f=0;f<momentum.length;f++)if(mass[f]>0)result=result.add(mmm.units.SignedDyadic.of(.5*momentum[f]*(momentum[f]/mass[f])));
		}
		return result;
	}
	private static double sum(double[] values){double result=0;for(double value:values)result+=value;return result;}

	private static void startupContinuesAfterRestore() throws Exception {
		var domain = fixture(); var backend = new CpuFluidSolverBackend();
		long solid = domain.totalGranularSolidVolumeSvq(); double mass = domain.totalGranularMass();
		for (int step = 0; step < 3; step++) {
			long started = System.nanoTime();
			var source = domain.captureAuthoritativeState();
			long captured = System.nanoTime();
			var candidate = backend.evaluate(new FluidStepRequest(step, source, List.of(),
					true, ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP));
			long evaluated = System.nanoTime();
			domain = candidate.claimPreparedCpuDomain();
			long adopted = System.nanoTime();
			domain = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
			long restored = System.nanoTime();
			double fullMs = (restored - started) / 1e6;
			jointCapacity(domain);
			require(domain.totalGranularSolidVolumeSvq() == solid && domain.totalGranularMass() == mass,
					"Bed startup changed solid inventory");
			require(domain.gasBoundary().auditClosed() && domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
					"Bed startup broke gas/energy audit");
			System.out.printf("BED startup step=%d fullMs=%.3f productionMs=%.3f captureMs=%.3f evaluateMs=%.3f adoptMs=%.3f roundTripMs=%.3f substeps=%d refinements=%d reserved=%d pressureIterations=%d markers=%d phaseOperations=%d active=%d deposits=%d%n",
					step, fullMs, (adopted - started) / 1e6, (captured - started) / 1e6,
					(evaluated - captured) / 1e6, (adopted - evaluated) / 1e6, (restored - adopted) / 1e6,
					candidate.stats().substeps(), candidate.mechanicalResolution().timeRefinements(),
					candidate.mechanicalResolution().reservedSubsteps(), candidate.stats().pressureIterations(), domain.particleCount(),
					candidate.stats().gasPhase().operations() + candidate.stats().condensedPhase().operations(),
					domain.granularParcelCount(), domain.granularDepositCellCount());
		}
		System.out.println("Bed startup completed three phase-on backend steps and restoration");
	}

	public static void validate() throws Exception {
		macLiftPaysPotentialAndRecoil();
		carrierTemperatureRefreshAndDiagnostics();
		wallLawLimits();
		var domain = fixture();
		var backend = new CpuFluidSolverBackend();
		long inventory = domain.totalGranularSolidVolumeSvq();
		long massUnits = Double.doubleToRawLongBits(domain.totalGranularMass());
		var initialLedger=domain.exportGranularState();
		long settled = 0, woken = 0, contacts = 0;
		long weakWakeBaseline=0,weakContactBaseline=0,strongWakeBaseline=0;
		double totalStepMs = 0, maximumStepMs = 0; int completedSteps = 0;
		int peakActive = 0;
		FluidDomainState sleeping = null;
		double initialMoment = 0;
		int settlingSteps = settlingStepLimit(domain);
		int bedSleepStarted = -1;
		double weakSpeed=0;
		int[] surfaces=new int[domain.sizeX*domain.sizeZ];
		int phase = 0, phaseStep = 0;
		for (int step = 0; step < 2 * (settlingSteps + mmm.granular.GranularDepositDynamics.SLEEP_LOGICAL_STEPS) + 90; step++, phaseStep++) {
			double speed = phase == 1 ? weakSpeed : phase == 2 ? 3.0 : 0;
			var requests=List.of(MovingBodyFluidCoupling.prescribedCube(2,.75F,2,speed,0,0,.75));
			double beforeU=domain.exactInternalEnergy().toDouble(),beforeK=domain.domainStep.mechanicalCarrierKineticEnergyJoules(),beforePE=domain.domainStep.mechanicalPotentialEnergyJoules();
            long stepStarted = System.nanoTime();
			var source=domain.captureAuthoritativeState();FluidStepCandidate candidate;
			try{candidate=backend.evaluate(new FluidStepRequest(step,source,requests,true,ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP));}
			catch(RuntimeException failure){
				System.err.printf("BED failed step=%d phase=%d phaseStep=%d speed=%g oldMaximumSpeed=%g initialSubsteps=%d active=%d deposits=%d%n",step,phase,phaseStep,speed,domain.maximumParticleSpeed(),domain.domainStep.calculateSubsteps(domain.config.timeStep()),domain.granularParcelCount(),domain.granularDepositCellCount());
				throw failure;
			}
			domain = candidate.claimPreparedCpuDomain();
            double energyError=domain.exactInternalEnergy().toDouble()-beforeU
                +domain.domainStep.mechanicalCarrierKineticEnergyJoules()-beforeK
                +domain.domainStep.mechanicalPotentialEnergyJoules()-beforePE-candidate.movingBodyCoupling().externalWorkJoules();
            require(Math.abs(energyError)<.01,"Bed complete step changed total U+K+PE: step="+step+", errorJ="+energyError);
			double fullMs = (System.nanoTime() - stepStarted) / 1e6;
			totalStepMs += fullMs; maximumStepMs = Math.max(maximumStepMs, fullMs); completedSteps++;
			jointCapacity(domain);
			settled += candidate.stats().granularTransition().settledParcels();
			woken += candidate.stats().granularTransition().wokenParcels();
			contacts += candidate.movingBodyCoupling().coupledMarkers();
			if (phase >= 2) peakActive = Math.max(peakActive, domain.granularState.parcels.count());
			require(domain.totalGranularSolidVolumeSvq() == inventory, "Bed transport changed solid volume");
			require(Double.doubleToRawLongBits(domain.totalGranularMass()) == massUnits, "Bed transport changed sand mass");
			var granular=domain.exportGranularState();
			require(granular.externalSource().equals(initialLedger.externalSource())
					&&granular.escapedSink().equals(initialLedger.escapedSink()),"Internal bed transitions changed granular source/sink ledgers");
			require(domain.gasBoundary().auditClosed() && domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory()),
					"Bed transport broke gas/energy audit");
			java.util.Arrays.fill(surfaces,-1);
			for(var deposit:granular.depositGroups()){
				int cell=deposit.cell(),column=cell/(domain.sizeX*domain.sizeY)*domain.sizeX+cell%domain.sizeX;
				surfaces[column]=Math.max(surfaces[column],cell);
			}
			double maxShields=0,maxShieldsRatio=0;
			for(int cell:surfaces)if(cell>=0){
				var material=domain.granularState.registry.material(domain.granularState.deposits.dominantGroup(cell).materialHandle());
				double shields=GranularBedShear.shields(domain,cell,material);
				maxShields=Math.max(maxShields,shields);maxShieldsRatio=Math.max(maxShieldsRatio,shields/material.criticalShields());
			}
			boolean atRest = domain.granularState.parcels.count() == 0
					&& domain.domainStep.granularDepositDynamics().dirtyColumnCount() == 0;
			if (phase == 0 || phase == 3) {
				// Terminal settling bounds suspended material; the committed bed then
				// completes the scheduler's own stability window before sleeping.
				if (domain.granularState.parcels.count() != 0) {
					bedSleepStarted = -1;
					require(phaseStep < settlingSteps, "Bed exceeded the registry-derived settling time: phase=" + phase);
				} else {
					if (bedSleepStarted < 0) bedSleepStarted = phaseStep;
					require(atRest || phaseStep - bedSleepStarted < mmm.granular.GranularDepositDynamics.SLEEP_LOGICAL_STEPS,
							"Deposited bed did not complete its stability window: phase=" + phase);
				}
			}
			if (phase == 0 && atRest) {
				require(settled > 0 && domain.granularState.deposits.groupCount()>0, "Initial bed did not settle and sleep");
				require(maxShieldsRatio<1,"Resting-bed fixture retained supercritical initial flow");
				sleeping = domain.captureAuthoritativeState();
				weakSpeed=weakDriveSpeed(domain,surfaces);
				System.out.printf("bed weak readiness step=%d maximumShieldsRatio=%.6f derivedSpeed=%.6f m/s%n",step,maxShieldsRatio,weakSpeed);
				weakWakeBaseline=woken;weakContactBaseline=contacts;
				phase = 1; phaseStep = -1;
			}
			if(phase==1&&phaseStep>=0)require(maxShieldsRatio<1,
					"Registry-derived weak flow exceeded actual bed initiation stress: ratio="+maxShieldsRatio);
			if (phase == 1 && phaseStep == 29) {
				require(woken-weakWakeBaseline == 0 && contacts-weakContactBaseline > 0,
						"Subcritical real liquid caused new erosion or missed the body");
				strongWakeBaseline=woken;initialMoment=depositMoment(domain);
				phase = 2; phaseStep = -1;
			} else if (phase == 2 && phaseStep == 59) {
				require(woken-strongWakeBaseline>0&&peakActive>0,"Strong flow never woke deposited sand");
				phase = 3; phaseStep = -1; bedSleepStarted = -1;
			} else if (phase == 3 && atRest) break;
			if (step % 20 == 19) System.out.printf("bed step=%d speed=%.2f active=%d deposits=%d sleeping=%d settled=%d woken=%d theta=%.6f contacts=%d fullMs=%.3f%n",
					step, speed, domain.granularState.parcels.count(), domain.granularState.deposits.groupCount(),
					domain.domainStep.granularDepositDynamics().sleepingColumnCount(), settled, woken, maxShields, candidate.movingBodyCoupling().coupledMarkers(), fullMs);
		}
		require(sleeping != null, "Initial bed exceeded the physical settling budget");
		require(woken-strongWakeBaseline > 0 && peakActive > 0, "Real water never woke deposited sand during strong flow");
		require(domain.granularState.parcels.count() == 0 && domain.domainStep.granularDepositDynamics().dirtyColumnCount() == 0,
				"Sand did not re-settle and sleep after reducing flow");
		require(Math.abs(depositMoment(domain) - initialMoment) > .01, "Released sand did not move horizontally");
		wakeStartsAtRest(sleeping);
		System.out.printf("Bed lifecycle passed: initialWakes=%d strongAndResettlingWakes=%d peakActive=%d horizontalMomentChange=%.6f kg*m steps=%d meanMs=%.3f maxMs=%.3f%n",
				weakWakeBaseline, woken-strongWakeBaseline, peakActive, depositMoment(domain) - initialMoment, completedSteps, totalStepMs / completedSteps, maximumStepMs);
	}
	/** A weak drive has one quarter of the critical friction velocity in the production wall law. */
	private static double weakDriveSpeed(FluidDomain domain,int[] surfaces){
		double result=Double.POSITIVE_INFINITY,h=domain.config.cellSize();
		for(int cell:surfaces)if(cell>=0){
			var material=domain.granularState.registry.material(domain.granularState.deposits.dominantGroup(cell).materialHandle());
			int x=cell%domain.sizeX,y=cell/domain.sizeX%domain.sizeY,z=cell/(domain.sizeX*domain.sizeY);
			var liquid=domain.granularMechanicalStage.sample((float)((x+.5)*h),(float)((y+1.5)*h),(float)((z+.5)*h));
			if(!liquid.hasLiquid())continue;
			double stress=material.criticalShields()*Math.max(0,material.referenceDensity()-liquid.density())
					*domain.config.gravity()*material.primaryGrainDiameterMeters()/(16*liquid.submergedFraction());
			double bed=(y+domain.granularState.deposits.cellBulkVolumeSvq(cell)/(double)SolidVolume.SVQ_PER_CELL)*h;
			double distance=(y+1.5)*h-bed,low=0,high=stress*distance/liquid.dynamicViscosity();
			for(int iteration=0;iteration<40;iteration++){
				double speed=.5*(low+high);
				if(GranularBedShear.shearStress(speed,distance,liquid.density(),liquid.dynamicViscosity())>stress)high=speed;else low=speed;
			}
			result=Math.min(result,.5*(low+high));
		}
		require(Double.isFinite(result),"Weak-flow fixture has no wet bed surface");return result;
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
		return (int) Math.ceil(4 * domain.config().cellSize() / high / domain.config.timeStep());
	}
	private static void carrierTemperatureRefreshAndDiagnostics() throws Exception {
		var domain = new FluidDomain(4, 4, 4,
				new FluidSolverConfig(.5F, 1000, 0, .05F, 200, 1e-5F, .002F));
		var registries = FullCapabilityTestFixture.load();
		FullCapabilityTestFixture.bindFull(domain, registries, 293.15, 4);
		domain.setVolume(1, 1, 1, .5F);
		int cell = domain.domainFlowFields.cellIndex(1, 1, 1);
		for (double temperature : new double[] {1, 5000, 353.15}) {
			domain.setThermalTemperatureInCell(1, 1, 1, temperature);
			var before = domain.exactInternalEnergy();
			double expected = registries.materials().dynamicViscosityExtendingAt("mmm:water", temperature);
			for (int repeat = 0; repeat < 3; repeat++) {
				require(Math.abs(domain.carrierViscosityPascalSeconds(cell) - expected) < expected * 1e-12,
						"Carrier properties retained an obsolete thermal state");
				require(Math.abs(domain.granularMechanicalStage.sample(.75F, .75F, .75F).dynamicViscosity() - expected)
						< expected * 1e-12, "Drag and bed shear must use the refreshed viscosity");
				require(Math.abs(domain.domainPhaseInventory.derivedCarrierState().viscosityPascalSeconds(cell) - expected) < expected * 1e-12,
						"Darcy damping must use the same refreshed viscosity");
			}
			require(domain.exactInternalEnergy().equals(before), "Property observation changed authoritative energy");
		}
		var stats = domain.carrierPropertiesWorkspace.finishStep();
		require(!stats.extrapolated(), "Completed diagnostics must describe the current carrier temperature");
		domain.setThermalTemperatureInCell(1, 1, 1, 5000);
		for (int repeat = 0; repeat < 3; repeat++) require(domain.carrierViscosityStats().equals(stats),
				"Reading diagnostics must retain the last completed step");
		domain.carrierViscosityPascalSeconds(cell);
		require(domain.carrierViscosityStats().equals(stats), "An unfinished attempt published diagnostics");
		var hotStats=domain.carrierPropertiesWorkspace.finishStep();
		require(hotStats.extrapolatedCells()==1&&hotStats.sample().contains("mmm:water"),
				"Carrier diagnostics must count current cells rather than repeated property builds");
		domain.setThermalTemperatureInCell(1, 1, 1, 293.15);
		domain.carrierViscosityPascalSeconds(cell);
		require(!domain.carrierPropertiesWorkspace.finishStep().extrapolated(),
				"Retried step inherited a discarded attempt's excursions");
		int sand = registries.granular().materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		long svq = mmm.granular.GranularLimits.MAX_PARCEL_VOLUME_SVQ;
		double mass = registries.granular().material(sand).referenceDensity() * svq / SolidVolume.SVQ_PER_CUBIC_METER;
		require(domain.tryInsertExactGranular(1, 0, 1, sand, 0, svq, mmm.units.Mass.fromKilograms(mass)).inserted(), "Occupied wake fixture rejected its retained parcel");
		domain.domainFlowFields.preparePhysicalState();
		occupiedWakePointKeepsState(domain);
		var inventory = domain.granularState.inventory; int bedCell = domain.domainFlowFields.cellIndex(1, 0, 1);
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
		long sourceMass = domain.granularState.deposits.dominantGroup(bedCell).massUnits();
		long takenMass = domain.granularState.deposits.peekTake(bedCell, sand, 0, taken).massUnits();
		double movedEnergy = domain.thermalState.previewDepositTake(key, takenMass, sourceMass);
		double remainingEnergy = domain.thermalState.granularColumn().previewDepositRemaining(key, takenMass, sourceMass);
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
		require(!domain.domainStep.granularDepositEnvironment(planned).planWake(domain.domainFlowFields.cellIndex(1, 0, 1), wakeSvq, active.x(), active.y(), active.z())
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
				moment[0] += mmm.units.Mass.kilograms(mass) * (cell % domain.sizeX + .5) * domain.config().cellSize());
		return moment[0];
	}

	private static void wakeStartsAtRest(FluidDomainState sleeping) {
		var domain = FluidDomain.restoreAuthoritativeState(sleeping);
		require(domain.granularCoupling.totalSolidSvq() == domain.totalGranularSolidVolumeSvq()
				+ domain.totalCoherentSolidVolumeSvq(), "Thermal restore lost granular joint occupancy");
		jointCapacity(domain);
		domain.domainFlowFields.preparePhysicalState();
		for(int f=0;f<domain.mac.velocityX.length;f++)if(domain.cellMacStage.massX[f]>0)domain.mac.velocityX[f]=2;
		java.util.Arrays.fill(domain.mac.velocityY,0);java.util.Arrays.fill(domain.mac.velocityZ,0);
		domain.cellMacStage.publishMomentum();
		var beforeU = domain.exactInternalEnergy();
		var beforeAudit = domain.thermalAudit();
		double beforeK=macHorizontalEnergy(domain).toDouble(),beforeMass=domain.totalGranularMass();
		int beforeCount=domain.mac.momentumX.length+domain.mac.momentumZ.length;
		var stats = domain.granularTransitionStage.step().depositStats();
		require(stats.wokenParcels() > 0, "Sleeping bed ignored actual liquid velocity");
		require(domain.exactInternalEnergy().subtract(beforeU).equals(
				domain.thermalAudit().mechanicalWork().subtract(beforeAudit.mechanicalWork())
						.add(domain.thermalAudit().roundoffResidual().subtract(beforeAudit.roundoffResidual())))
				&& domain.thermalAudit().finiteDeviceExchange().equals(beforeAudit.finiteDeviceExchange())
				&& domain.thermalAudit().externalSource().equals(beforeAudit.externalSource())
				&& domain.thermalAudit().externalSink().equals(beforeAudit.externalSink())
				&& domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory())
				&& macHorizontalEnergy(domain).toDouble() + domain.thermalAudit().mechanicalWork().subtract(beforeAudit.mechanicalWork()).toDouble()
						<= beforeK + beforeCount * Math.ulp(beforeK)
				&& domain.totalGranularMass() == beforeMass, "Bed release created energy or changed inventory: dU=" + domain.exactInternalEnergy().subtract(beforeU)
						+ " dK=" + (macHorizontalEnergy(domain).toDouble() - beforeK) + " dMass=" + (domain.totalGranularMass() - beforeMass));
		domain.domainFlowFields.preparePhysicalState();
		jointCapacity(domain);
		for (int p = 0; p < domain.granularState.parcels.count(); p++) {
			var parcel = domain.granularState.parcels.parcel(p);
			require(parcel.velocityX() == 0 && parcel.velocityY() == 0 && parcel.velocityZ() == 0,
					"Bed release invented a launch impulse");
			require(domain.granularCollision.positionValid(parcel.x(), parcel.y(), parcel.z(), parcel.solidVolumeSvq()),
					"Released parcel overlaps collision geometry");
		}
		GranularCarrierValidation.requireNonOverlappingRelease(domain.exportGranularState(), domain.config().cellSize());
		var group = domain.exportGranularState().depositGroups().getFirst();
		int cell = group.cell(), above = cell + domain.sizeX;
		var material = domain.granularState.registry.material(domain.granularState.deposits.dominantGroup(cell).materialHandle());
		require(GranularBedShear.shields(domain, cell, material) > material.criticalShields(), "Wet bed shear missing");
		domain.setVolume(cell%domain.sizeX,cell/domain.sizeX%domain.sizeY,cell/(domain.sizeX*domain.sizeY),0);
		require(GranularBedShear.shields(domain,cell,material)==0,"Liquid separated by an actually dry bed cell eroded the bed");
		domain.setVolume(above%domain.sizeX,above/domain.sizeX%domain.sizeY,above/(domain.sizeX*domain.sizeY),0);
		require(GranularBedShear.shields(domain,cell,material)==0,"Dry-cell velocity eroded the bed");
	}

	private static FluidDomain fixture() throws Exception {
		var r = FullCapabilityTestFixture.load();
		var domain = new FluidDomain(8, 8, 8,
				new FluidSolverConfig(.5F, 1000, 9.81F, .05F, 400, 1e-5F, .002F));
		domain.sealDomainBoundary();
		FullCapabilityTestFixture.bindEmptyMaterialState(domain, r.liquids());
		domain.bindChemistry(r.chemical());
		int sand=r.granular().materialHandle(BuiltinGranularDefinitions.FINE_SAND);
		var material=r.granular().material(sand);
		long svq=SolidVolume.SVQ_PER_CELL/4;
		long bedMass=mmm.units.Mass.fromKilograms(material.referenceDensity()*svq/SolidVolume.SVQ_PER_CUBIC_METER);
		var beds=new java.util.ArrayList<mmm.granular.GranularStateSnapshot.DepositGroup>();
		for (int z = 2; z < 6; z++) for (int x = 2; x < 6; x++) {
			beds.add(new mmm.granular.GranularStateSnapshot.DepositGroup(domain.domainFlowFields.cellIndex(x,0,z),
					material.restPorosityQ(),sand,0,svq,bedMass));
		}
		// One released representative exercises actual settling without exciting the
		// entire bed's gravitational energy before the weak-shear observation.
		long fallingSvq=mmm.granular.GranularLimits.MAX_PARCEL_VOLUME_SVQ;
		long fallingMass=mmm.units.Mass.fromKilograms(material.referenceDensity()*fallingSvq/SolidVolume.SVQ_PER_CUBIC_METER);
		var kernel=new mmm.granular.GranularKernel(8,8,8,.5F);kernel.capture(2.25F,.75F,2.25F,fallingSvq);
		var parcel=new mmm.granular.GranularStateSnapshot.ActiveParcel(2.25F,.75F,2.25F,0,0,0,
				fallingSvq,fallingMass,sand,0,0,(byte)0,kernel.canonicalMask());
		domain.restoreGranularState(new mmm.granular.GranularStateSnapshot(8,8,8,r.granular().fingerprint(),
				r.granular().materials().stream().map(mmm.granular.ParticulateMaterial::id).toList(),List.of(parcel),beds,
				Math.addExact(svq*beds.size(),fallingSvq),Math.addExact(bedMass*beds.size(),fallingMass)),
				r.granular(),domain.materialRegistry,r.materials());
		int water = r.liquids().handle("mmm:water");
		FullCapabilityTestFixture.bindFull(domain, r, 293.15, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
		domain.domainFlowFields.preparePhysicalState();
		for (int z = 0; z < 8; z++) for (int y = 0; y < 4; y++) for (int x = 0; x < 8; x++) {
			float open = domain.domainCollision.effectiveOpenCellVolume(domain.domainFlowFields.cellIndex(x, y, z));
			require(domain.domainInventory.tryInsertExactVolume(x, y, z, open, 0, water).transferred(), "Pore water rejected");
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
		domain.domainFlowFields.ensureInventoryGrid();
		for (int c = 0; c < domain.cellCount; c++) require(domain.liquidGrid.exactCellVolumeInventory[c].compareTo(
				mmm.units.SignedDyadic.of(domain.domainCollision.effectiveOpenCellVolume(c))) <= 0,
				"Published bed state overlaps liquid and skeleton at cell " + c);
	}

	private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}


