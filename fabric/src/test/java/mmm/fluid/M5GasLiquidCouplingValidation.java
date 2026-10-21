package mmm.fluid;

import mmm.fluid.air.AirNetwork;

/**
 * M5 active-solver gas-liquid coupling gate: sealed-cavity compression, negative-gauge suction,
 * orifice equalization through geometric edges and exact gas/liquid ledgers. The transient
 * fixtures use zero surface tension; one static fixture deliberately combines finite gas
 * compliance and a sharp capillary jump on the same faces.
 */
public final class M5GasLiquidCouplingValidation {
	private static final float DENSITY = 1_000.0F;
	private static final float GRAVITY = 9.81F;

	public static void main(String[] args) {
		if(java.util.Arrays.asList(args).contains("--orifice")){
			validateOrificeEqualization();
			System.out.println("M5 finite orifice equalization passed");return;
		}
		if(java.util.Arrays.asList(args).contains("--interfaces")){
			validateSealedGasAndCapillaryJumpShareFaces();PressureCapacityValidation.cutHydrostatics();
			System.out.println("M5 capillary and cut hydrostatic reconstruction PASS");return;
		}
		PressureCapacityValidation.main(new String[]{"--algebra"});
		PressureCapacityValidation.cutHydrostatics();
		validateSealedGasAndCapillaryJumpShareFaces();
		validateSplitRetainsSealedShareWhenPeerVents();
		validateClosedCavityAfterBootstrapStartsAsVacuum();
		validateEmptyControllerBoundaryPourRetainsLineage();
		validateFiniteClosedGasWithoutCapacityBlocksTyped();
		validateFiniteGasNodeUsesEquationOfStateCompliance();
		if (java.util.Arrays.asList(args).contains("--fast")) {
			validateShortCouplingSmoke();
			System.out.println("M5 gas-liquid coupling validation passed (short; equilibrium soaks skipped)");
			return;
		}
		validateOpenTubesStayAmbient();
		validateAnalyticStaticHead(1);
		validateAnalyticStaticHead(-1);
		validateSealedCompression();
		validateNegativeGaugeSuction();
		validateOrificeEqualization();
		System.out.println("M5 gas-liquid coupling gate passed: analytic positive/negative hydrostatics, "
				+ "sealed compression, negative-gauge suction, finite-rate orifice equalization and exact ledgers");
	}

	private static void validateSealedGasAndCapillaryJumpShareFaces() {
		for(float amount:new float[]{.25F,.7F,1}){
		FluidSolverConfig config = new FluidSolverConfig(
				1.0F, DENSITY, 0.0F, 1.0F / 20.0F, 100, 1.0E-6F, 2.0E-3F, 0.80F);
		FluidDomain domain = new FluidDomain(4, 4, 4, config, 64);
		domain.setVolume(1, 1, 1, amount);
		FluidMaterialRegistry registry = new FluidMaterialRegistry(java.util.List.of(
				new FluidMaterial("mmm:gas_contact_test", DENSITY, "mmm:gas_contact_test",
						0.0728, Math.PI / 2.0)));
		for (int marker = 0; marker < domain.particleCount(); marker++) {
			domain.domainCollision.setMarkerMaterial(marker, registry, 0);
		}
		domain.sealDomainBoundary();
		domain.bootstrapGasState(null);
		var gasInventory = domain.gasBoundary().sealedInventory();
		int gasNode=domain.airNodeAt(1,3,1);
		domain.domainStep.step();
		float jump = domain.domainCollision.surfaceTensionPressureAt(1, 1, 1);
		double capillaryScale=Math.max(Math.abs(jump),registry.material(0).surfaceTension()/config.cellSize());
		float solvedGasPressure = domain.projection.gasPressure[gasNode];
		check(domain.projection.gasPressureUnknown[gasNode]
				&& Math.abs(domain.pressure(1, 1, 1) - solvedGasPressure - jump) <= .01*capillaryScale,
				"finite gas compliance and capillary jump did not share one balanced interface face");
		FluidPhysicalAssertions.staticMotion(domain,capillaryScale,FluidPhysicalAssertions.SETTLED_ENERGY_FRACTION,"Static gas/capillary contact");
		check(domain.gasBoundary().sealedInventory().equals(gasInventory)
				&& domain.gasBoundary().auditClosed(),
				"static sealed gas/capillary contact changed gas inventory truth");
		}
	}

	private static void validateEmptyControllerBoundaryPourRetainsLineage() {
		int n = 8;
		FluidSolverConfig config = new FluidSolverConfig(
				0.5F, DENSITY, GRAVITY, 1.0F / 20.0F, 120, 1.0E-4F, 2.0E-3F, 0.80F);
		FluidDomain domain = new FluidDomain(n, n, n, config, 4_096);
		domain.sealDomainBoundary();
		domain.bootstrapGasState(null);
		domain.setGasNodeStandardVolume(domain.airNodeAt(1,n-1,1), 34.0);
		long gas = domain.gasBoundary().sealedInventoryNanos();
		FluidDomain.VolumeAddition addition = domain.addVolumeNearBudgeted(6, 0, 0, 128.0F, 6);
		check(addition.addedVolume() == 128.0F && !addition.markerBudgetLimited(),
				"empty controller boundary pour did not add 16 m³");
		for (int step = 0; step < 200; step++) {
			try { domain = advance(domain).claimPreparedCpuDomain(); }
			catch (RuntimeException failure) {
				throw new IllegalStateException("empty controller negative-pressure boundary pour failed at step " + step,
						failure);
			}
			check(domain.gasBoundary().sealedInventoryNanos() == gas && domain.gasBoundary().auditClosed(),
					"empty controller boundary pour changed closed gas inventory at step " + step);
			try {
				domain = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
			} catch (RuntimeException failure) {
				throw new IllegalStateException("empty controller partial-bubble handoff failed at step " + step,
						failure);
			}
		}
	}

	private static void validateFiniteClosedGasWithoutCapacityBlocksTyped() {
		FluidSolverConfig config = new FluidSolverConfig(
				0.5F, DENSITY, GRAVITY, 1.0F / 20.0F, 120, 1.0E-4F, 2.0E-3F, 0.80F);
		FluidDomain domain = new FluidDomain(2, 2, 2, config, 128);
		domain.sealDomainBoundary();
		domain.bootstrapGasState(null);
		for (int z = 0; z < 2; z++) for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) {
			if(x!=1||y!=1||z!=1)domain.setVolume(x, y, z, 1.0F);
		}
		var before=domain.captureAuthoritativeState();
		try {
			domain.setVolume(1,1,1,1);
			throw new AssertionError("finite closed gas without capacity did not reject its liquid edit candidate");
		} catch (GasTopologyBlockedException expected) {
			check(domain.gasBoundary().sealedInventoryNanos() == 1_000_000_000L
					&& domain.gasBoundary().auditClosed(),
					"typed gas-topology rejection changed the committed inventory");
		}
		FluidDomainStateAssertions.assertSameState(before,domain.captureAuthoritativeState(),"Rejected finite-gas capacity edit");
		domain.synchronizeGasTopologyForPersistence();
		var restored=FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
		FluidDomainStateAssertions.assertSameState(before,restored.captureAuthoritativeState(),"Finite-gas recovery after rejected edit");
	}

	private static void validateFiniteGasNodeUsesEquationOfStateCompliance() {
		FluidDomain domain = utubeDomain(true);
		domain.domainStep.step();
		int gasNode=sealedColumnNode(domain);
		double volume = domain.gasBoundary().availableVolume(gasNode);
		domain.setGasNodeStandardVolume(gasNode, 1.5 * volume);
		double absolutePressure = AirNetwork.REFERENCE_PRESSURE + domain.gasBoundary().gaugePressure(gasNode);
		double dt = domain.config.timeStep();
		double h = domain.config().cellSize();
		float expectedStorage = (float) (domain.config.density() * volume / absolutePressure
				/ (dt * dt * h * h * h));
		domain.domainStep.step();
		// Matrix columns refer to this solve's source geometry, before transport renumbers nodes.
		check(domain.projection.gasPressureUnknown[gasNode],
				"finite sealed gas touching liquid stayed a lagged Dirichlet boundary");
		check(Math.abs(domain.projection.gasStorageCoefficient[gasNode] - expectedStorage)
				<= .001*expectedStorage,
				"isothermal cavity pressure row did not use V/p absolute EoS compliance");
		check(domain.pressureSystem.matrix.diagonal(domain.cellCount+gasNode) > domain.projection.gasStorageCoefficient[gasNode],
				"compressible gas row did not share any symmetric liquid interface face");
		check(Float.isFinite(domain.projection.gasPressure[gasNode])
				&& domain.projection.gasPressure[gasNode] != 0.0F,
				"compressible gas pressure unknown did not respond to liquid interface motion");
	}

	private static void validateClosedCavityAfterBootstrapStartsAsVacuum() {
		int n = 4;
		FluidSolverConfig config = new FluidSolverConfig(
				0.5F, DENSITY, 0.0F, 1.0F / 20.0F, 100, 1.0E-6F, 2.0E-3F, 0.80F);
		FluidDomain domain = new FluidDomain(n, n, n, config, n * n * n * 8);
		domain.sealDomainBoundary();
		for (int z = 0; z < n; z++) for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
			domain.setVolume(x, y, z, 1.0F);
		}
		domain.bootstrapGasState(null);
		check(domain.gasBoundary().nodeCount() == 0
				&& domain.gasBoundary().externalSource().entries().isEmpty(),
				"fully occupied closed bootstrap created gas inventory");
		FluidDomain.ExactVolumeTransfer extracted = domain.tryExtractExactVolume(1, 1, 1, 1.0F, 0);
		check(extracted.transferred(), "closed vacuum fixture could not extract one cell volume");
		domain.synchronizeGasTopologyForPersistence();
		int vacuum=domain.airNodeAt(1,1,1);
		check(domain.gasBoundary().nodeCount() == 1
				&&vacuum>0&&domain.gasBoundary().inventory(vacuum).isEmpty()
				&& domain.gasBoundary().externalSource().entries().isEmpty()
				&& domain.gasBoundary().escapedSink().entries().isEmpty()
				&& domain.gasBoundary().gaugePressure(vacuum) == -AirNetwork.REFERENCE_PRESSURE
				&& domain.gasBoundary().auditClosed(),
				"post-bootstrap closed cavity was filled from ambient instead of remaining vacuum");
		var liquids=new FluidMaterialRegistry(java.util.List.of(BuiltinFluidMaterials.WATER));
		domain.materialRegistry=liquids;
		var thermal=new mmm.thermal.ThermalRegistry(java.util.List.of(
				new mmm.thermal.ThermalMaterialDefinition("test:vacuum_water",mmm.thermal.ThermalMaterialDefinition.CarrierKind.LIQUID,
						BuiltinFluidMaterials.WATER.id(),300,0,0,java.util.List.of(new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(1,2000,4184))),
				new mmm.thermal.ThermalMaterialDefinition("test:vacuum_air",mmm.thermal.ThermalMaterialDefinition.CarrierKind.GAS,
						mmm.fluid.air.BuiltinGasDefinitions.AIR,300,0,0,java.util.List.of(new mmm.thermal.ThermalMaterialDefinition.CaloricSegment(1,2000,718)))));
		domain.bindThermal(thermal,liquids,300);
		var volume=domain.liquidCells.exactVolume();long mass=domain.liquidCells.massUnits();var energy=domain.exactInternalEnergy();
		for(int step=0;step<2;step++) {
			domain=new CpuFluidSolverBackend().evaluate(new FluidStepRequest(step,domain.captureAuthoritativeState(),
					java.util.List.of(),false,0,true)).claimPreparedCpuDomain();
			check(domain.liquidCells.exactVolume().equals(volume)&&domain.liquidCells.massUnits()==mass
					&&domain.gasBoundary().sealedInventory().isEmpty()&&domain.gasBoundary().externalSource().isEmpty()
					&&domain.gasBoundary().escapedSink().isEmpty()&&domain.gasBoundary().auditClosed(),
					"Explicit vacuum normal successor changed liquid or gas inventory");
			// A cell-volume residual corresponds to this face displacement over dt.
			// Its mass-weighted motion/heat scale follows the common FV resolution.
			var motion=FluidPhysicalAssertions.motion(domain);
			double quietSpeed=mmm.inventory.PhaseOccupancyState.VOLUME_RESOLUTION*config.cellSize()/config.timeStep();
			double noiseBudget=.5*motion.massKilograms()*quietSpeed*quietSpeed;
			double heat=Math.abs(domain.exactInternalEnergy().subtract(energy).toDouble());
			check(domain.exactGasInternalEnergy().equals(mmm.units.SignedDyadic.ZERO)
					&&domain.exactInternalEnergy().equals(domain.thermalAudit().expectedInventory())
					&&Double.isFinite(motion.kineticJoules())&&motion.kineticJoules()+heat<=noiseBudget,
					"Unforced closed vacuum exceeded the resolved motion/heat scale: RMS="+motion.rmsSpeed()+", heat="+heat+", budget="+noiseBudget);
			if(step==0) {
				var saved=domain.captureAuthoritativeState();
				domain=FluidDomain.restoreAuthoritativeState(coldVacuumState(saved));
				FluidDomainStateAssertions.assertSameState(saved,domain.captureAuthoritativeState(),"Explicit vacuum cold restore");
			}
		}
	}
	private static FluidDomainState coldVacuumState(FluidDomainState s) {
		return new FluidDomainState(s.sizeX(),s.sizeY(),s.sizeZ(),s.config(),s.maximumParticleCount(),s.sealedDomainBoundary(),
				new mmm.inventory.LiquidCellInventory.Snapshot(s.liquid().rows()),new mmm.inventory.PhaseOccupancyState(s.phaseOccupancy().copyLiquidMicroVolumes()),
				s.momentum(),s.copyCollisionMasks(),s.materialPalette(),s.chemicalRegistry(),s.chemicalSource(),s.chemicalSink(),
				s.copyChemicalDirtyCells(),s.chemicalReactionCursor(),s.granularRegistry(),s.granular(),s.granularDynamics(),s.precipitationEnabled(),
				s.gasRegistry(),s.gas(),s.thermalRegistry(),s.thermalOwner(),s.maximumThermalTransportSubsteps(),s.pendingPhaseVolume(),
				s.copyThermalBoundaryTemperaturesK(),s.condensedPhasePressure(),s.materialDefinitions());
	}

	private static void validateAnalyticStaticHead(int sign) {
		FluidDomain domain = utubeDomain(true);
		for (int z = 1; z <= 2; z++) for (int y = 9; y <= 12; y++) {
			int fromX = sign > 0 ? 7 : 1;
			int toX = sign > 0 ? 10 : 5;
			for (int x = fromX; x <= toX; x++) domain.setVolume(x, y, z, 1.0F);
		}
		domain.bootstrapGasState(null);
		double expected = sign * DENSITY * GRAVITY * 4.0;
		int gasNode=sealedColumnNode(domain);
		double available = domain.gasBoundary().availableVolume(gasNode);
		domain.setGasNodeStandardVolume(gasNode, available * (1.0 + expected / AirNetwork.REFERENCE_PRESSURE));
		double liquidVolume = domain.totalVolumeExact();
		long liquidMass=domain.totalMassUnits();
		FluidDomain.StepStats stats = null;
		double peakRms = 0.0;
		for (int step = 0; step < 1_000; step++) {
			var candidate = advance(domain);
			domain = candidate.claimPreparedCpuDomain();
			stats = candidate.stats();
			var motion=FluidPhysicalAssertions.staticMotion(domain,expected,FluidPhysicalAssertions.TRANSIENT_ENERGY_FRACTION,"Hydrostatic transient");
			peakRms=Math.max(peakRms,motion.rmsSpeed());
			check(Float.isFinite(stats.pressureResidual()), "analytic hydrostatic residual became non-finite");
			check(domain.totalVolumeExact() == liquidVolume&&domain.totalMassUnits()==liquidMass, "analytic hydrostatic head changed liquid volume or integer mass");
			checkLedger(domain.gasBoundary(), "analytic hydrostatic step " + sign + "/" + step);
		}
		double gasPressure = domain.gasBoundary().gaugePressure(sealedColumnNode(domain));
		double measuredHead = DENSITY * GRAVITY *domain.config.cellSize()
				* (columnSurface(domain, 7, 10, 8) - columnSurface(domain, 1, 5, 10));
		double bottomMismatch = Math.abs(domain.pressure(5, 1, 1) - domain.pressure(7, 1, 1));
		double relativeHeadError = Math.abs(gasPressure - measuredHead) / Math.abs(gasPressure);
		var finalMotion=FluidPhysicalAssertions.staticMotion(domain,expected,FluidPhysicalAssertions.SETTLED_ENERGY_FRACTION,"Hydrostatic equilibrium");
		System.out.printf("  analytic %s-gauge head: p=%.3f Pa measured=%.3f Pa error=%.6g%% "
				+ "RMS peak/final=%.6g/%.6g m/s bottomMismatch=%.6g Pa%n",
				sign > 0 ? "positive" : "negative", gasPressure, measuredHead, relativeHeadError * 100.0,
				peakRms,finalMotion.rmsSpeed(), bottomMismatch);
		FluidPhysicalAssertions.hydrostaticPressure(domain,gasPressure,expected,"Analytic gas pressure");
		FluidPhysicalAssertions.hydrostaticPressure(domain,gasPressure,measuredHead,"Gas pressure and liquid head");
		FluidPhysicalAssertions.hydrostaticPressure(domain,bottomMismatch,0,"U-tube bottom pressure");
		checkLedger(domain.gasBoundary(),"analytic hydrostatic final gas inventory");
	}

	/** A simultaneous wall insertion and vent opening must not vent a newly isolated sibling cavity. */
	private static void validateSplitRetainsSealedShareWhenPeerVents() {
		int n = 8;
		FluidSolverConfig config = new FluidSolverConfig(
				1.0F, DENSITY, 0.0F, 1.0F / 20.0F, 100, 1.0E-6F, 2.0E-3F, 0.80F);
		FluidDomain domain = new FluidDomain(n, n, n, config, n * n * n * 8);
		for (int z = 0; z < n; z++) for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
			boolean shell = x == 0 || x == n - 1 || y == 0 || y == n - 1 || z == 0 || z == n - 1;
			if (shell) domain.setSolid(x, y, z, true);
		}
		domain.bootstrapGasState(null);
		check(domain.gasBoundary().nodeCount() == 1
				&& domain.gasBoundary().sealedInventoryNanos() == 216_000_000_000L,
				"mixed split fixture did not begin as one ambient-equilibrium cavity");

		for (int z = 1; z < n - 1; z++) for (int y = 1; y < n - 1; y++) {
			domain.setSolid(4, y, z, true);
		}
		domain.setSolid(6, n - 1, 3, false);
		domain.synchronizeGasTopologyForPersistence();

		GasPressureBoundary network = domain.gasBoundary();
		check(network.nodeCount() == 1, "mixed sealed/ambient split did not retain one sealed child");
		check(network.standardVolumeNanos(domain.airNodeAt(2,2,2)) == 129_600_000_000L,
				"mixed split failed to retain the sealed child's available-volume share");
		check(network.ventedNanos() == 86_400_000_000L,
				"mixed split did not vent exactly the ambient child's share");
		checkLedger(network, "mixed sealed/ambient topology split");
	}

	/** One-step wiring gate; quantitative equilibrium and long-run stability remain in the soak tier. */
	private static void validateShortCouplingSmoke() {
		validateOpenTubesStayAmbient();
		FluidDomain sealed = utubeDomain(true);
		sealed = advance(sealed).claimPreparedCpuDomain();
		int sealedNode=sealedColumnNode(sealed);
		double available = sealed.gasBoundary().availableVolume(sealedNode);
		sealed.setGasNodeStandardVolume(sealedNode, 0.75 * available);
		double exactVolume = sealed.totalVolumeExact();
		double exactMass = sealed.totalMass();
		var candidate = advance(sealed);
		sealed = candidate.claimPreparedCpuDomain();
		FluidDomain.StepStats stats = candidate.stats();
		check(sealed.gasPressureActive() && sealed.gasBoundary().gaugePressure(sealedColumnNode(sealed)) < 0.0,
				"sealed gas pressure was not consumed by the active solver");
		check(Float.isFinite(stats.pressureResidual()), "short coupling residual became non-finite");
		check(sealed.totalVolumeExact() == exactVolume && sealed.totalMass() == exactMass,
				"short coupling smoke changed liquid ledgers");
		checkLedger(sealed.gasBoundary(), "short sealed-cavity smoke");

		FluidDomain chambers = chamberPairDomain();
		chambers = advance(chambers).claimPreparedCpuDomain();
		GasPressureBoundary network = chambers.gasBoundary();
		int left=chambers.airNodeAt(1,1,1),right=chambers.airNodeAt(6,1,1);
		requireOrificeConnection(chambers,left,right);
		chambers.setGasNodeStandardVolume(left, 2.0 * network.availableVolume(left));
		chambers.setGasNodeStandardVolume(right, network.availableVolume(right));
		double before = Math.abs(network.gaugePressure(left) - network.gaugePressure(right));
		var inventoryBefore=network.sealedInventory();
		chambers = advance(chambers).claimPreparedCpuDomain();
		network = chambers.gasBoundary();
		double after = Math.abs(network.gaugePressure(chambers.airNodeAt(1,1,1)) - network.gaugePressure(chambers.airNodeAt(6,1,1)));
		check(after > 0.0 && after < before, "geometric orifice edge did not begin finite-rate equalization");
		check(network.sealedInventory().equals(inventoryBefore),"Short closed orifice flow changed its complete inventory");
		checkLedger(network, "short orifice smoke");
	}

	private static void validateOpenTubesStayAmbient() {
		FluidDomain domain = utubeDomain(false);
		domain.domainStep.step();
		for(int z=1;z<=2;z++)for(int y=9;y<domain.sizeY;y++)for(int x=1;x<=10;x++)if(x!=6)
			check(domain.airNodeAt(x,y,z)==0,"Open tube headspace lost its connection to ambient");
		check(domain.gasBoundary().sealedInventory().isEmpty()&&domain.gasBoundary().externalSource().isEmpty()
				&&domain.gasBoundary().escapedSink().isEmpty(),"Open tubes created finite gas stock or external gas transfers");
		var frame=domain.gasState.frame;
		for(var component:frame.capacity().components())if(frame.topology().nodeOfComponent(component.cell(),component.supportMask())>0)
			check(component.availableVolumeNormalized().toDouble()<=mmm.inventory.PhaseOccupancyState.VOLUME_RESOLUTION,
					"Open tubes trapped a resolved gas cavity below their free surface");
		checkLedger(domain.gasBoundary(), "open tubes");
	}

	private static void validateSealedCompression() {
		FluidDomain domain = utubeDomain(true);
		domain = advance(domain).claimPreparedCpuDomain();
		double initialLiquidVolume = domain.totalVolumeExact();
		double initialLiquidMass = domain.totalMass();
		// Staged compression: top the right column up without entraining air below either surface.
		double addedTotal = 0.0;
		double maximumSpeed = 0.0;
		for (int fill = 0; fill < 48; fill++) {
			addedTotal += domain.addVolumeNear(9, 6, 1, 2.0F, 12);
			for (int settle = 0; settle < 150; settle++) {
				var candidate = advance(domain);
				domain = candidate.claimPreparedCpuDomain();
				FluidDomain.StepStats stats = candidate.stats();
				check(stats.relativeVolumeError() <= 1.0E-7F, "staged fill changed exact marker volume");
				check(Float.isFinite(stats.pressureResidual()), "staged fill residual became non-finite");
				maximumSpeed = Math.max(maximumSpeed, domain.maximumParticleSpeed());
				check(maximumSpeed <= 100.0, "staged compression agitation diverged");
				checkLedger(domain.gasBoundary(), "staged fill step " + fill + "/" + settle);
			}
			int gasNode=sealedColumnNode(domain);
			double pressure = domain.gasBoundary().gaugePressure(gasNode);
			double available = domain.gasBoundary().availableVolume(gasNode);
			double standard = domain.gasBoundary().standardVolumeNanos(gasNode) / 1_000_000_000.0;
			double expectedPressure = AirNetwork.REFERENCE_PRESSURE * (standard / available - 1.0);
			check(Math.abs(pressure - expectedPressure) <= Math.max(1.0E-8, Math.abs(expectedPressure) * 1.0E-12),
					"staged compression pressure did not match finite inventory/capacity EoS at fill " + fill);
		}
		check(Math.abs(addedTotal - 96.0F) <= 1.0E-3F, "staged fill must add the requested water, got " + addedTotal);
		checkLedger(domain.gasBoundary(),"sealed compression final gas inventory");
		double volumeClosure = domain.totalVolumeExact() - initialLiquidVolume - addedTotal;
		check(Math.abs(volumeClosure) <= 1.0E-6,
				"staged compression did not close liquid source volume: " + volumeClosure);
		double insertedVolume = domain.totalVolumeExact() - initialLiquidVolume;
		double massClosure = domain.totalMass() - initialLiquidMass - insertedVolume * DENSITY;
		check(Math.abs(massClosure) <= Math.ulp(domain.totalMass()),
				"staged compression mass diverged from density times authoritative inserted volume: " + massClosure);
		check(domain.gasBoundary().availableVolume(sealedColumnNode(domain))>0,"Sealed compression lost its upper gas chamber");
		System.out.printf("  staged sealed compression: added=%.3f m³ p=%+.3f Pa maxSpeed=%.6g m/s%n",
				addedTotal, domain.gasBoundary().gaugePressure(sealedColumnNode(domain)), maximumSpeed);
	}

	private static void validateNegativeGaugeSuction() {
		FluidDomain domain = utubeDomain(true);
		domain = advance(domain).claimPreparedCpuDomain();
		int gasNode=sealedColumnNode(domain);
		double initialAvailable = domain.gasBoundary().availableVolume(gasNode);
		domain.setGasNodeStandardVolume(gasNode, 0.75 * initialAvailable);
		double exactVolume = domain.totalVolumeExact();
		double exactMass = domain.totalMass();
		double maximumSpeed = 0.0;
		for (int steps = 0; steps < 1_000; steps++) {
			var candidate = advance(domain);
			domain = candidate.claimPreparedCpuDomain();
			FluidDomain.StepStats stats = candidate.stats();
			check(stats.relativeVolumeError() <= 1.0E-7F, "negative-gauge suction changed exact marker volume");
			check(Float.isFinite(stats.pressureResidual()), "negative-gauge residual became non-finite");
			maximumSpeed = Math.max(maximumSpeed, domain.maximumParticleSpeed());
			check(maximumSpeed <= 100.0, "negative-gauge agitation diverged");
			checkLedger(domain.gasBoundary(), "negative-gauge step " + steps);
		}
		check(domain.gasBoundary().gaugePressure(sealedColumnNode(domain)) < 0.0, "negative-gauge suction lost its pressure sign");
		checkLedger(domain.gasBoundary(),"negative-gauge final gas inventory");
		check(domain.totalVolumeExact() == exactVolume && domain.totalMass() == exactMass,
				"negative-gauge suction changed exact liquid ledgers");
		System.out.printf("  negative-gauge transient: p=%+.3f Pa max/finalSpeed=%.6g/%.6g m/s%n",
				domain.gasBoundary().gaugePressure(sealedColumnNode(domain)), maximumSpeed, domain.maximumParticleSpeed());
	}

	private static void validateOrificeEqualization() {
		FluidDomain domain = chamberPairDomain();
		domain = advance(domain).claimPreparedCpuDomain();
		GasPressureBoundary network = domain.gasBoundary();
		int left=domain.airNodeAt(1,1,1),right=domain.airNodeAt(6,1,1);
		requireOrificeConnection(domain,left,right);
		double leftAvailable = network.availableVolume(left);
		double rightAvailable = network.availableVolume(right);
		domain.setGasNodeStandardVolume(left, 2.0 * leftAvailable);
		domain.setGasNodeStandardVolume(right, rightAvailable);
		double initialDelta = Math.abs(network.gaugePressure(left) - network.gaugePressure(right));
		var inventoryBefore=network.sealedInventory();var sourceBefore=network.externalSource();var sinkBefore=network.escapedSink();
		check(initialDelta > 0.9 * AirNetwork.REFERENCE_PRESSURE, "pressurized chamber must start with a large delta");
		double previousDelta = initialDelta;
		int steps = 0;
		double delta = initialDelta;
		while (delta > 0.01 * initialDelta && steps < 20_000) {
			domain = advance(domain).claimPreparedCpuDomain();
			network = domain.gasBoundary();
			steps++;
			delta = Math.abs(network.gaugePressure(domain.airNodeAt(1,1,1)) - network.gaugePressure(domain.airNodeAt(6,1,1)));
			check(Double.isFinite(delta) && delta <= previousDelta * 1.001,
					"orifice chain equalization must decay monotonically, step " + steps);
			checkLedger(network, "orifice chain step " + steps);
			check(network.sealedInventory().equals(inventoryBefore)&&network.externalSource().equals(sourceBefore)
					&&network.escapedSink().equals(sinkBefore),"Closed orifice flow changed its inventory or external ledgers");
			if (steps == 5) check(delta > 0.2 * initialDelta, "orifice chain must not equalize instantly");
			previousDelta = Math.min(previousDelta, delta);
		}
		System.out.printf("  orifice chain equalized to %.2f%% of initial delta in %d steps%n",
				100.0 * delta / initialDelta, steps);
		check(delta <= 0.01 * initialDelta, "orifice chain did not equalize, residual " + delta);
	}

	/** Both chambers share one water body through a bottom opening; the left top can be sealed. */
	private static FluidDomain utubeDomain(boolean sealLeft) {
		int sizeX = 12, sizeY = 24, sizeZ = 4;
		FluidSolverConfig config = new FluidSolverConfig(
				1.0F, DENSITY, GRAVITY, 1.0F / 20.0F, 400, 1.0E-6F, 2.0E-3F, 0.80F);
		FluidDomain domain = new FluidDomain(sizeX, sizeY, sizeZ, config, sizeX * sizeY * sizeZ * 8);
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			boolean shell = x == 0 || x == sizeX - 1 || z == 0 || z == sizeZ - 1 || y == 0;
			boolean divider = x == 6 && y >= 5;
			boolean roof = sealLeft && y == sizeY - 1 && x <= 5;
			if (shell || divider || roof) domain.setSolid(x, y, z, true);
		}
		for (int z = 1; z <= 2; z++) for (int y = 1; y <= 8; y++) {
			for (int x = 1; x <= 5; x++) domain.setVolume(x, y, z, 1.0F);
			for (int x = 7; x <= 10; x++) domain.setVolume(x, y, z, 1.0F);
		}
		// the bottom passage under the divider must be water-filled, otherwise it traps a sealed air lock
		for (int z = 1; z <= 2; z++) for (int y = 1; y <= 4; y++) domain.setVolume(6, y, z, 1.0F);
		return domain;
	}

	/** Two dry sealed chambers separated by a solid wall whose one cell keeps a quarter-open gap. */
	private static FluidDomain chamberPairDomain() {
		int n = 8;
		FluidSolverConfig config = new FluidSolverConfig(
				1.0F, DENSITY, 0.0F, 1.0F / 20.0F, 100, 1.0E-6F, 2.0E-3F, 0.80F);
		FluidDomain domain = new FluidDomain(n, n, n, config, n * n * n * 8);
		for (int z = 0; z < n; z++) for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
			boolean shell = x == 0 || x == n - 1 || z == 0 || z == n - 1 || y == 0 || y == n - 1;
			boolean divider = x == 4 && y >= 1 && y < n - 1 && z >= 1 && z < n - 1;
			if (shell || divider) domain.setSolid(x, y, z, true);
		}
		// Adjacent microboxes 0 and 1 form a continuous x-axis duct with quarter-cell end faces.
		domain.setSolidMicroMask(4, 1, 1, 0xFC);
		return domain;
	}
	private static void requireOrificeConnection(FluidDomain domain,int left,int right){
		int throat=domain.airNodeAt(4,1,1);
		check(left>0&&right>0&&throat>0&&left!=right&&throat!=left&&throat!=right,
				"The fixed partition must retain two separate pressure owners and its finite duct");
		boolean leftPort=false,rightPort=false;double fullArea=domain.config.cellSize()*domain.config.cellSize();
		for(var edge:domain.gasState.frame.topology().connections()){
			boolean restricted=edge.area()>0&&edge.area()<fullArea;
			leftPort|=restricted&&(edge.firstNode()==left&&edge.secondNode()==throat||edge.firstNode()==throat&&edge.secondNode()==left);
			rightPort|=restricted&&(edge.firstNode()==right&&edge.secondNode()==throat||edge.firstNode()==throat&&edge.secondNode()==right);
		}
		check(leftPort&&rightPort,"The actual gas ports did not connect both main chambers through the finite duct");
	}

	/** Mean water surface height in cells over a column range from the committed liquid stock. */
	private static double columnSurface(FluidDomain domain, int fromX, int toX, int crossSection) {
		double volume = 0.0;
		for(var stock:domain.exportLiquidCellState().stocks()){
			int cellX=stock.key().cell()%domain.sizeX;
			if(cellX>=fromX&&cellX<=toX)volume+=stock.volumeFraction();
		}
		check(volume > 0.0, "column " + fromX + ".." + toX + " has no water");
		return volume / crossSection;
	}

	private static void checkLedger(GasPressureBoundary network, String context) {
		var sealed = network.sealedInventory();
		if (!sealed.add(network.escapedSink()).equals(network.externalSource())) {
			throw new AssertionError("gas ledger identity broken after " + context + ": sealed=" + sealed.standardVolumeNanos()
					+ " vented=" + network.ventedNanos() + " drawn=" + network.drawnFromAmbientNanos());
		}
	}
	private static int sealedColumnNode(FluidDomain domain){
		int node=domain.airNodeAt(2,domain.sizeY-2,1);
		check(node>0,"The upper sealed column lost its finite gas owner");return node;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static FluidStepCandidate advance(FluidDomain domain) {
		return new CpuFluidSolverBackend().evaluate(new FluidStepRequest(
				0, domain.captureAuthoritativeState(), java.util.List.of(), false, 0));
	}
}
