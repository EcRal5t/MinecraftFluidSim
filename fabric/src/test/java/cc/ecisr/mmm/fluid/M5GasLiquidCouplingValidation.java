package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.fluid.air.AirNetwork;

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
		validateSharedLiquidGasFaceAlgebra();
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

	/**
	 * Locks the one face law shared by a liquid unknown, a gas unknown and the current node-owned
	 * gas boundary. The 1000:1 density ratio is resolved by series mass resistance, not by a
	 * material branch, and reversing the face must only reverse its pressure jump.
	 */
	private static void validateSharedLiquidGasFaceAlgebra() {
		float referenceDensity = 1_000.0F;
		float liquidDensity = 1_000.0F;
		float gasDensity = 1.0F;
		float liquidDistance = 0.25F;
		float gasDistance = 0.75F;
		float pressureJump = 250.0F;
		float openArea = 0.625F;

		PressureFaceCoupling mixed = PressureFaceCoupling.twoSided(
				referenceDensity, liquidDensity, liquidDistance,
				gasDensity, gasDistance, pressureJump);
		float expectedMobility = referenceDensity
				/ (liquidDensity * liquidDistance + gasDensity * gasDistance);
		check(Math.abs(mixed.mobilityScale() - expectedMobility) <= Math.ulp(expectedMobility),
				"liquid/gas face did not use the two phase segments as series mass resistance");
		check(mixed.orientedPressureDifference(101_575.0F, 101_325.0F) == 0.0F,
				"Laplace-balanced liquid/gas contact produced a face impulse");
		PressureFaceCoupling reversed = mixed.reversed();
		check(reversed.mobilityScale() == mixed.mobilityScale()
				&& reversed.pressureJumpFirstMinusSecond() == -pressureJump
				&& reversed.orientedPressureDifference(101_325.0F, 101_575.0F) == 0.0F,
				"reversing a liquid/gas face changed its metric or static balance");
		check(mixed.firstJumpRhs(openArea) == -mixed.secondJumpRhs(openArea)
				&& mixed.matrixCoefficient(openArea) == reversed.matrixCoefficient(openArea),
				"two-sided interface assembly is not symmetric with antisymmetric jump sources");

		PressureFaceCoupling currentBoundary = PressureFaceCoupling.oneSided(
				referenceDensity / liquidDensity, liquidDistance, pressureJump);
		PressureFaceCoupling collapsedGasSegment = PressureFaceCoupling.twoSided(
				referenceDensity, liquidDensity, liquidDistance, gasDensity, 0.0, pressureJump);
		check(currentBoundary.mobilityScale() == collapsedGasSegment.mobilityScale()
				&& currentBoundary.firstDirichletRhs(openArea, 13_000.0F)
				== collapsedGasSegment.firstDirichletRhs(openArea, 13_000.0F),
				"node-owned gas boundary is not the exact eliminated-gas limit of the mixed face");
	}

	private static void validateSealedGasAndCapillaryJumpShareFaces() {
		FluidSolverConfig config = new FluidSolverConfig(
				1.0F, DENSITY, 0.0F, 1.0F / 20.0F, 100, 1.0E-6F, 0.45F, 2.0E-3F, 0.80F);
		FluidDomain domain = new FluidDomain(4, 4, 4, config, 64);
		domain.setVolume(1, 1, 1, 1.0F);
		FluidMaterialRegistry registry = new FluidMaterialRegistry(java.util.List.of(
				new FluidMaterial("mmm:gas_contact_test", DENSITY, "mmm:gas_contact_test",
						0.0728, Math.PI / 2.0)));
		for (int marker = 0; marker < domain.particleCount(); marker++) {
			domain.setMarkerMaterial(marker, registry, 0);
		}
		domain.sealDomainBoundary();
		domain.bootstrapGasState(null);
		long gasInventory = domain.gasBoundary().sealedInventoryNanos();
		domain.step();
		float jump = domain.surfaceTensionPressureAt(1, 1, 1);
		float solvedGasPressure = domain.pressureReference + domain.projection.gasPressure[1];
		check(domain.projection.gasPressureUnknown[1]
				&& Math.abs(domain.pressure(1, 1, 1) - solvedGasPressure - jump) <= 1.0E-6F,
				"finite gas compliance and capillary jump did not share one balanced interface face");
		check(domain.maximumParticleSpeed() <= 1.0E-8F,
				"static sealed gas/capillary contact produced parasitic liquid motion");
		check(domain.gasBoundary().sealedInventoryNanos() == gasInventory
				&& domain.gasBoundary().auditClosed(),
				"static sealed gas/capillary contact changed gas inventory truth");
	}

	private static void validateEmptyControllerBoundaryPourRetainsLineage() {
		int n = 8;
		FluidSolverConfig config = new FluidSolverConfig(
				0.5F, DENSITY, GRAVITY, 1.0F / 20.0F, 120, 1.0E-4F, 0.40F, 2.0E-3F, 0.80F);
		FluidDomain domain = new FluidDomain(n, n, n, config, 4_096);
		domain.sealDomainBoundary();
		domain.bootstrapGasState(null);
		domain.setGasNodeStandardVolume(1, 34.0);
		long gas = domain.gasBoundary().sealedInventoryNanos();
		FluidDomain.VolumeAddition addition = domain.addVolumeNearBudgeted(6, 0, 0, 128.0F, 6);
		check(addition.addedVolume() == 128.0F && !addition.markerBudgetLimited(),
				"empty controller boundary pour did not add 16 BV");
		for (int step = 0; step < 200; step++) {
			try { domain = advance(domain); }
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
				0.5F, DENSITY, GRAVITY, 1.0F / 20.0F, 120, 1.0E-4F, 0.40F, 2.0E-3F, 0.80F);
		FluidDomain domain = new FluidDomain(2, 2, 2, config, 128);
		domain.sealDomainBoundary();
		domain.bootstrapGasState(null);
		for (int z = 0; z < 2; z++) for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) {
			domain.setVolume(x, y, z, 1.0F);
		}
		try {
			domain.synchronizeGasTopologyForPersistence();
			throw new AssertionError("finite closed gas without capacity did not reject its topology candidate");
		} catch (GasTopologyBlockedException expected) {
			check(domain.gasBoundary().sealedInventoryNanos() == 1_000_000_000L
					&& domain.gasBoundary().auditClosed(),
					"typed gas-topology rejection changed the committed inventory");
		}
	}

	private static void validateFiniteGasNodeUsesEquationOfStateCompliance() {
		FluidDomain domain = utubeDomain(true);
		domain.step();
		double volume = domain.gasBoundary().availableVolume(1);
		domain.setGasNodeStandardVolume(1, 1.5 * volume);
		double absolutePressure = AirNetwork.REFERENCE_PRESSURE + domain.gasBoundary().gaugePressure(1);
		double dt = domain.config.timeStep();
		double h = domain.config.cellSize();
		float expectedStorage = (float) (domain.config.density() * volume / absolutePressure
				/ (dt * dt * h * h * h));
		domain.step();
		check(domain.projection.gasPressureUnknown[1],
				"finite sealed gas touching liquid stayed a lagged Dirichlet boundary");
		check(Math.abs(domain.projection.gasStorageCoefficient[1] - expectedStorage)
				<= Math.ulp(expectedStorage),
				"isothermal cavity pressure row did not use V/p absolute EoS compliance");
		check(domain.projection.gasMatrixDiagonal[1] > domain.projection.gasStorageCoefficient[1],
				"compressible gas row did not share any symmetric liquid interface face");
		check(Float.isFinite(domain.projection.gasPressure[1])
				&& domain.projection.gasPressure[1] != 0.0F,
				"compressible gas pressure unknown did not respond to liquid interface motion");
	}

	private static void validateClosedCavityAfterBootstrapStartsAsVacuum() {
		int n = 4;
		FluidSolverConfig config = new FluidSolverConfig(
				1.0F, DENSITY, 0.0F, 1.0F / 20.0F, 100, 1.0E-6F, 0.45F, 2.0E-3F, 0.80F);
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
		check(domain.gasBoundary().nodeCount() == 1
				&& domain.gasBoundary().inventory(1).isEmpty()
				&& domain.gasBoundary().externalSource().entries().isEmpty()
				&& domain.gasBoundary().escapedSink().entries().isEmpty()
				&& domain.gasBoundary().gaugePressure(1) == -AirNetwork.REFERENCE_PRESSURE
				&& domain.gasBoundary().auditClosed(),
				"post-bootstrap closed cavity was filled from ambient instead of remaining vacuum");
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
		double available = domain.gasBoundary().availableVolume(1);
		domain.setGasNodeStandardVolume(1, available * (1.0 + expected / AirNetwork.REFERENCE_PRESSURE));
		long gasNanos = domain.gasBoundary().standardVolumeNanos(1);
		double liquidVolume = domain.totalVolumeExact();
		FluidDomain.StepStats stats = null;
		double maximumSpeed = 0.0;
		for (int step = 0; step < 1_000; step++) {
			stats = domain.step();
			maximumSpeed = Math.max(maximumSpeed, domain.maximumParticleSpeed());
			check(Float.isFinite(stats.pressureResidual()), "analytic hydrostatic residual became non-finite");
			check(domain.totalVolumeExact() == liquidVolume, "analytic hydrostatic head changed liquid volume");
			checkLedger(domain.gasBoundary(), "analytic hydrostatic step " + sign + "/" + step);
		}
		double gasPressure = domain.gasBoundary().gaugePressure(1);
		double measuredHead = DENSITY * GRAVITY
				* (columnSurface(domain, 7, 10, 8) - columnSurface(domain, 1, 5, 10));
		double bottomMismatch = Math.abs(domain.pressure(5, 1, 1) - domain.pressure(7, 1, 1));
		double relativeHeadError = Math.abs(gasPressure - measuredHead) / Math.abs(gasPressure);
		System.out.printf("  analytic %s-gauge head: p=%.3f Pa measured=%.3f Pa error=%.6g%% "
				+ "speed max/final=%.6g/%.6g m/s bottomMismatch=%.6g Pa%n",
				sign > 0 ? "positive" : "negative", gasPressure, measuredHead, relativeHeadError * 100.0,
				maximumSpeed, domain.maximumParticleSpeed(), bottomMismatch);
		check(Math.abs(gasPressure - expected) <= Math.max(0.1, Math.abs(expected) * 1.0E-5),
				"analytic gas pressure changed from its imposed hydrostatic head");
		check(relativeHeadError <= 1.0E-5, "analytic gas pressure did not match authoritative liquid head");
		check(bottomMismatch <= 0.1, "analytic U-tube bottom pressure did not close");
		check(maximumSpeed <= 0.02 && domain.maximumParticleSpeed() <= 3.0E-4F,
				"analytic hydrostatic head self-excited");
		check(domain.gasBoundary().standardVolumeNanos(1) == gasNanos,
				"analytic hydrostatic head changed sealed gas inventory");
	}

	/** A simultaneous wall insertion and vent opening must not vent a newly isolated sibling cavity. */
	private static void validateSplitRetainsSealedShareWhenPeerVents() {
		int n = 8;
		FluidSolverConfig config = new FluidSolverConfig(
				1.0F, DENSITY, 0.0F, 1.0F / 20.0F, 100, 1.0E-6F, 0.45F, 2.0E-3F, 0.80F);
		FluidDomain domain = new FluidDomain(n, n, n, config, n * n * n * 8);
		for (int z = 0; z < n; z++) for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
			boolean shell = x == 0 || x == n - 1 || y == 0 || y == n - 1 || z == 0 || z == n - 1;
			if (shell) domain.setSolid(x, y, z, true);
		}
		domain.bootstrapGasState(null);
		check(domain.gasBoundary().nodeCount() == 1
				&& domain.gasBoundary().standardVolumeNanos(1) == 216_000_000_000L,
				"mixed split fixture did not begin as one ambient-equilibrium cavity");

		for (int z = 1; z < n - 1; z++) for (int y = 1; y < n - 1; y++) {
			domain.setSolid(4, y, z, true);
		}
		domain.setSolid(6, n - 1, 3, false);
		domain.synchronizeGasTopologyForPersistence();

		GasPressureBoundary network = domain.gasBoundary();
		check(network.nodeCount() == 1, "mixed sealed/ambient split did not retain one sealed child");
		check(network.standardVolumeNanos(1) == 129_600_000_000L,
				"mixed split failed to retain the sealed child's available-volume share");
		check(network.ventedNanos() == 86_400_000_000L,
				"mixed split did not vent exactly the ambient child's share");
		checkLedger(network, "mixed sealed/ambient topology split");
	}

	/** One-step wiring gate; quantitative equilibrium and long-run stability remain in the soak tier. */
	private static void validateShortCouplingSmoke() {
		validateOpenTubesStayAmbient();
		FluidDomain sealed = utubeDomain(true);
		sealed.step();
		double available = sealed.gasBoundary().availableVolume(1);
		sealed.setGasNodeStandardVolume(1, 0.75 * available);
		double exactVolume = sealed.totalVolumeExact();
		double exactMass = sealed.totalMass();
		FluidDomain.StepStats stats = sealed.step();
		check(sealed.gasPressureActive() && sealed.gasBoundary().gaugePressure(1) < 0.0,
				"sealed gas pressure was not consumed by the active solver");
		check(Float.isFinite(stats.pressureResidual()), "short coupling residual became non-finite");
		check(sealed.totalVolumeExact() == exactVolume && sealed.totalMass() == exactMass,
				"short coupling smoke changed liquid ledgers");
		checkLedger(sealed.gasBoundary(), "short sealed-cavity smoke");

		FluidDomain chambers = chamberPairDomain();
		chambers.step();
		GasPressureBoundary network = chambers.gasBoundary();
		chambers.setGasNodeStandardVolume(1, 2.0 * network.availableVolume(1));
		chambers.setGasNodeStandardVolume(3, network.availableVolume(3));
		double before = Math.abs(network.gaugePressure(1) - network.gaugePressure(3));
		chambers.step();
		double after = Math.abs(network.gaugePressure(1) - network.gaugePressure(3));
		check(after > 0.0 && after < before, "geometric orifice edge did not begin finite-rate equalization");
		checkLedger(network, "short orifice smoke");
	}

	private static void validateOpenTubesStayAmbient() {
		FluidDomain domain = utubeDomain(false);
		domain.step();
		check(!domain.gasPressureActive(), "open-topped tube pair must not create sealed gas");
		check(domain.gasBoundary().nodeCount() == 0, "open-topped tube pair must keep zero sealed nodes");
		checkLedger(domain.gasBoundary(), "open tubes");
	}

	private static void validateSealedCompression() {
		FluidDomain domain = utubeDomain(true);
		domain.step();
		long initialGasNanos = domain.gasBoundary().standardVolumeNanos(1);
		double initialLiquidVolume = domain.totalVolumeExact();
		double initialLiquidMass = domain.totalMass();
		// Staged compression: top the right column up without entraining air below either surface.
		float addedTotal = 0.0F;
		double maximumSpeed = 0.0;
		for (int fill = 0; fill < 48; fill++) {
			addedTotal += domain.addVolumeNear(9, 6, 1, 2.0F, 12);
			for (int settle = 0; settle < 150; settle++) {
				FluidDomain.StepStats stats = domain.step();
				check(stats.relativeVolumeError() <= 1.0E-7F, "staged fill changed exact marker volume");
				check(Float.isFinite(stats.pressureResidual()), "staged fill residual became non-finite");
				maximumSpeed = Math.max(maximumSpeed, domain.maximumParticleSpeed());
				check(maximumSpeed <= 100.0, "staged compression agitation diverged");
				checkLedger(domain.gasBoundary(), "staged fill step " + fill + "/" + settle);
			}
			double pressure = domain.gasBoundary().gaugePressure(1);
			double available = domain.gasBoundary().availableVolume(1);
			double standard = domain.gasBoundary().standardVolumeNanos(1) / 1_000_000_000.0;
			double expectedPressure = AirNetwork.REFERENCE_PRESSURE * (standard / available - 1.0);
			check(Math.abs(pressure - expectedPressure) <= Math.max(1.0E-8, Math.abs(expectedPressure) * 1.0E-12),
					"staged compression pressure did not match finite inventory/capacity EoS at fill " + fill);
		}
		check(Math.abs(addedTotal - 96.0F) <= 1.0E-3F, "staged fill must add the requested water, got " + addedTotal);
		check(domain.gasBoundary().standardVolumeNanos(1) == initialGasNanos,
				"sealed gas inventory must stay exactly constant without orifice edges");
		double volumeClosure = domain.totalVolumeExact() - initialLiquidVolume - addedTotal;
		check(Math.abs(volumeClosure) <= 1.0E-6,
				"staged compression did not close liquid source volume: " + volumeClosure);
		double insertedVolume = domain.totalVolumeExact() - initialLiquidVolume;
		double massClosure = domain.totalMass() - initialLiquidMass - insertedVolume * DENSITY;
		check(Math.abs(massClosure) <= Math.ulp(domain.totalMass()),
				"staged compression mass diverged from density times authoritative inserted volume: " + massClosure);
		check(domain.gasBoundary().nodeCount() == 1, "sealed compression must keep exactly one cavity");
		System.out.printf("  staged sealed compression: added=%.3f BV p=%+.3f Pa maxSpeed=%.6g m/s%n",
				addedTotal, domain.gasBoundary().gaugePressure(1), maximumSpeed);
	}

	private static void validateNegativeGaugeSuction() {
		FluidDomain domain = utubeDomain(true);
		domain.step();
		double initialAvailable = domain.gasBoundary().availableVolume(1);
		domain.setGasNodeStandardVolume(1, 0.75 * initialAvailable);
		long presetNanos = domain.gasBoundary().standardVolumeNanos(1);
		double exactVolume = domain.totalVolumeExact();
		double exactMass = domain.totalMass();
		double maximumSpeed = 0.0;
		for (int steps = 0; steps < 1_000; steps++) {
			FluidDomain.StepStats stats = domain.step();
			check(stats.relativeVolumeError() <= 1.0E-7F, "negative-gauge suction changed exact marker volume");
			check(Float.isFinite(stats.pressureResidual()), "negative-gauge residual became non-finite");
			maximumSpeed = Math.max(maximumSpeed, domain.maximumParticleSpeed());
			check(maximumSpeed <= 100.0, "negative-gauge agitation diverged");
			checkLedger(domain.gasBoundary(), "negative-gauge step " + steps);
		}
		check(domain.gasBoundary().gaugePressure(1) < 0.0, "negative-gauge suction lost its pressure sign");
		check(domain.gasBoundary().standardVolumeNanos(1) == presetNanos,
				"preset gas inventory must stay exactly constant");
		check(domain.totalVolumeExact() == exactVolume && domain.totalMass() == exactMass,
				"negative-gauge suction changed exact liquid ledgers");
		System.out.printf("  negative-gauge transient: p=%+.3f Pa max/finalSpeed=%.6g/%.6g m/s%n",
				domain.gasBoundary().gaugePressure(1), maximumSpeed, domain.maximumParticleSpeed());
	}

	private static void validateOrificeEqualization() {
		FluidDomain domain = chamberPairDomain();
		domain.step();
		GasPressureBoundary network = domain.gasBoundary();
		check(network.nodeCount() == 3, "dry chamber pair must expose two chambers and the sliver pocket");
		check(network.edgeCount() == 2, "the gap must chain both chambers through the sliver pocket, got "
				+ network.edgeCount());
		double leftAvailable = network.availableVolume(1);
		double rightAvailable = network.availableVolume(3);
		domain.setGasNodeStandardVolume(1, 2.0 * leftAvailable);
		domain.setGasNodeStandardVolume(3, rightAvailable);
		double initialDelta = Math.abs(network.gaugePressure(1) - network.gaugePressure(3));
		check(initialDelta > 0.9 * AirNetwork.REFERENCE_PRESSURE, "pressurized chamber must start with a large delta");
		double previousDelta = initialDelta;
		int steps = 0;
		double delta = initialDelta;
		while (delta > 0.01 * initialDelta && steps < 20_000) {
			domain.step();
			steps++;
			delta = Math.abs(network.gaugePressure(1) - network.gaugePressure(3));
			check(Double.isFinite(delta) && delta <= previousDelta * 1.001,
					"orifice chain equalization must decay monotonically, step " + steps);
			checkLedger(network, "orifice chain step " + steps);
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
				1.0F, DENSITY, GRAVITY, 1.0F / 20.0F, 400, 1.0E-6F, 0.45F, 2.0E-3F, 0.80F);
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
				1.0F, DENSITY, 0.0F, 1.0F / 20.0F, 100, 1.0E-6F, 0.45F, 2.0E-3F, 0.80F);
		FluidDomain domain = new FluidDomain(n, n, n, config, n * n * n * 8);
		for (int z = 0; z < n; z++) for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) {
			boolean shell = x == 0 || x == n - 1 || z == 0 || z == n - 1 || y == 0 || y == n - 1;
			boolean divider = x == 4 && y >= 1 && y < n - 1 && z >= 1 && z < n - 1;
			if (shell || divider) domain.setSolid(x, y, z, true);
		}
		// one wall cell keeps a single open quadrant on each side: a through-gap of two quarter-open faces
		domain.setSolidMicroMask(4, 1, 1, 0xDE);
		return domain;
	}

	/** Mean water surface height in cells over a column range from authoritative marker volume. */
	private static double columnSurface(FluidDomain domain, int fromX, int toX, int crossSection) {
		double volume = 0.0;
		FlipParticleState state = domain.exportParticleState();
		float[] positionVolume = state.copyPositionVolume();
		for (int offset = 0; offset < positionVolume.length; offset += 4) {
			int cellX = (int) Math.floor(positionVolume[offset] / state.cellSize());
			if (cellX >= fromX && cellX <= toX) volume += positionVolume[offset + 3];
		}
		check(volume > 0.0, "column " + fromX + ".." + toX + " has no water");
		return volume / crossSection;
	}

	private static void checkLedger(GasPressureBoundary network, String context) {
		long sealed = network.sealedInventoryNanos();
		if (sealed + network.ventedNanos() != network.drawnFromAmbientNanos()) {
			throw new AssertionError("gas ledger identity broken after " + context + ": sealed=" + sealed
					+ " vented=" + network.ventedNanos() + " drawn=" + network.drawnFromAmbientNanos());
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static FluidDomain advance(FluidDomain domain) {
		return new CpuFluidSolverBackend().evaluate(new FluidStepRequest(
				0, domain.captureAuthoritativeState(), java.util.List.of(), false, 0)).claimPreparedCpuDomain();
	}
}
