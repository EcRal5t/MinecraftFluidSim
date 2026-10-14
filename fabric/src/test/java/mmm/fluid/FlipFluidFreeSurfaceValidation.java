package mmm.fluid;

/** Deterministic M2.5 gates for PLIC volume reconstruction and ghost-fluid pressure weights. */
public final class FlipFluidFreeSurfaceValidation {
	private FlipFluidFreeSurfaceValidation() { }

	public static void main(String[] args) {
		if (java.util.Arrays.asList(args).contains("--extension")) {
			airExtensionRetainsLiquidFacesAndResolvesMarkerFringe();
			System.out.println("Massless velocity extension validation passed"); return;
		}
		if (java.util.Arrays.asList(args).contains("--geometry")) {
			planeVolumeInversionIsAccurate(); liquidPlaneRetainsSmallInventoryGeometry(); sharedWetFacesControlTransportOnly(); momentNormalMatchesAnalyticShapes();
			System.out.println("Liquid-plane geometry validation passed"); return;
		}
		closedFaceValidityMatchesTheTransferSpace();
		freeSurfaceTransferSeparatesIndependentSupport();
		closedTransferSeparatesIndependentSupport();
		resolvedTransferRequiresExactAvailableCapacity();
		transferScratchTracksPressureSpaceChanges();
		finiteGasPressureConnectsTransferSupport();
		zeroResolvedInputKeepsUnresolvedVelocity();
		if (java.util.Arrays.asList(args).contains("--transfer")) {
			System.out.println("Free-surface transfer support validation passed");
			return;
		}
		boolean fast = java.util.Arrays.asList(args).contains("--fast");
		planeVolumeInversionIsAccurate();
		liquidPlaneRetainsSmallInventoryGeometry();
		sharedWetFacesControlTransportOnly();
		momentNormalMatchesAnalyticShapes();
		LiquidVolumeTransportValidation.main(fast ? new String[]{"--fast"} : new String[0]);
		axisAlignedInterfaceDistanceUsesExactCellLedger();
		pressurePhaseUsesConservativeFootprintVolume();
		closedComponentsUseExactLiquidCapacity();
		partialWallsKeepComponentFootprintsSeparate();
		divergenceConformingAdvectionMatchesMacDivergence();
		closedWallProfilesRetainZeroNormalVelocity();
		energyStableFlipTransferUsesTheLargestAdmissibleIncrement();
		resolvedVelocityPreservesSmoothMotionAndRejectsSamplingNoise();
		airExtensionRetainsLiquidFacesAndResolvesMarkerFringe();
		bodyOverlapResolvesContinuousSubmergence();
		pureWaterMarkerMassLedgerTracksVolume();
		fractionalHydrostaticColumnsBalanceGravity();
		liquidPressureGeometryConservesShapeMoments();
		pressureNonConvergenceRejectsStep();
		thinLayerRegularizationRemainsStable();
		isolatedDropletReceivesUndilutedGravity();
		if (!fast) {
			longRunHydrostaticSurfaceDoesNotSink();
			if (CellLiquidVolumeTransport.enabled()) MarkerDistributionExperiment.damDistribution();
			waterDescendsAStairSlope();
		}
		System.out.println("FlipFluid free-surface validation passed" + (fast ? " (short; hydrostatic/dam soaks and 300-step stair flow skipped)" : ""));
	}

	private static void momentNormalMatchesAnalyticShapes() {
		for (boolean cutBox : new boolean[] {false, true}) {
			for (int corner : new int[] {0, 7}) for (int axis = -1; axis < 3; axis++) {
				var cut = new FlipCutCellGeometry(3, 3, 3); int cell = 13;
				if (cutBox) cut.replaceMask(cell, 255 ^ (1 << corner));
				double side = cutBox ? .5 : 1, height = side / 4;
				double volume = axis < 0 ? height * height * height / 6 : height * side * side;
				double[] centre = new double[3];
				for (int a = 0; a < 3; a++) {
					boolean high = (corner & 1 << a) != 0;
					double lower = cutBox && high ? .5 : 0, upper = cutBox && !high ? .5 : 1;
					centre[a] = axis < 0 ? (high ? upper - height / 4 : lower + height / 4)
							: axis == a ? (high ? upper - height / 2 : lower + height / 2) : (lower + upper) / 2;
				}
				var markers = new MarkerGridIndex(27, 1); var particles = new LiquidParticleStore(1);
				java.util.Arrays.fill(markers.cellMarkerHead, -1); markers.cellMarkerHead[cell] = 0; markers.particleCellNext[0] = -1;
				particles.setCount(1); particles.setVolume(0, (float) volume);
				particles.setPositionX(0, (float) (1 + centre[0])); particles.setPositionY(0, (float) (1 + centre[1]));
				particles.setPositionZ(0, (float) (1 + centre[2])); markers.exactCellMarkerVolume[cell] = particles.volume(0);
				double[] volumes = new double[27]; volumes[cell] = particles.volume(0);
				var phase = new FlipPressurePhaseGeometry(3, 3, 3);
				phase.rebuild(new boolean[27], volumes, cut, null, new boolean[27], markers, particles, 1);
				double[] integral = new double[4];
				new LiquidFluxGeometry(cut).sweptMoments(cell, 14, 0, 1, true, phase.planeNormal(cell, 0),
						phase.planeNormal(cell, 1), phase.planeNormal(cell, 2), phase.planeOffset(cell), integral);
				check(Math.abs(integral[0] - volumes[cell]) <= 2e-14, "Moment-normal reconstruction changed its retained volume");
				for (int a = 0; a < 3; a++) check(Math.abs(integral[a + 1] / integral[0] - (centre[a] - .5)) <= 2e-7,
						"Axis slab or corner tetrahedron did not retain its analytic centroid");
			}
			var cut = new FlipCutCellGeometry(3, 3, 3); int cell = 13;
			if (cutBox) cut.replaceMask(cell, 254);
			var markers = new MarkerGridIndex(27, 1); var particles = new LiquidParticleStore(1);
			java.util.Arrays.fill(markers.cellMarkerHead, -1); markers.cellMarkerHead[cell] = 0; markers.particleCellNext[0] = -1;
			particles.setCount(1); particles.setVolume(0, .0625F); markers.exactCellMarkerVolume[cell] = .0625;
			float centre = cutBox ? 1.25F : 1.5F;
			particles.setPositionX(0, centre); particles.setPositionY(0, centre); particles.setPositionZ(0, centre);
			double[] volumes = new double[27]; volumes[cell] = .0625;
			var gradient = new FlipPressurePhaseGeometry(3, 3, 3); var moment = new FlipPressurePhaseGeometry(3, 3, 3);
			gradient.rebuild(new boolean[27], volumes, cut, null, new boolean[27]);
			moment.rebuild(new boolean[27], volumes, cut, null, new boolean[27], markers, particles, 1);
			for (int a = 0; a < 3; a++) check(gradient.planeNormal(cell, a) == moment.planeNormal(cell, a),
					"Zero eccentricity changed the existing fraction-gradient fallback");
		}
	}

	private static void closedFaceValidityMatchesTheTransferSpace() {
		var config = new FluidSolverConfig(.5F, 1000, 0, .05F, 120, 1e-4F, .4F, .002F, .8F);
		for (int normal = 0; normal < 3; normal++) for (boolean high : new boolean[] {false, true}) {
			for (boolean full : new boolean[] {false, true}) for (boolean interior : new boolean[] {false, true}) {
				byte[] masks = new byte[64];
				if (interior) for (int c = 0; c < 64; c++) {
					int coordinate = normal == 0 ? c % 4 : normal == 1 ? c / 4 % 4 : c / 16;
					if (coordinate == (high ? 3 : 0)) masks[c] = (byte) 255;
				}
				float[] point = {.625F, .625F, .625F};
				point[normal] = high ? (interior ? 1.375F : 1.875F) : (interior ? .625F : .125F);
				float[] velocity = new float[3]; velocity[normal] = high ? -1 : 1;
				velocity[(normal + 1) % 3] = 2; velocity[(normal + 2) % 3] = -.5F;
				var domain = new FluidDomain(4, 4, 4, config, 512);
				domain.restoreParticleState(new FlipParticleState(4, 4, 4, .5F, 1, .125F,
						new float[] {point[0], point[1], point[2], .125F}, velocity, masks));
				domain.sealDomainBoundary();
				if (full) {
					for (int c = 0; c < 64; c++) if (masks[c] == 0) domain.setVolume(c % 4, c / 4 % 4, c / 16, 1);
					for (int p = 0; p < domain.particleCount(); p++) {
						domain.particles.setVelocityX(p, velocity[0]); domain.particles.setVelocityY(p, velocity[1]);
						domain.particles.setVelocityZ(p, velocity[2]);
					}
				}
				domain.ensureGrid();
				float[][] fields = {domain.mac.velocityX, domain.mac.velocityY, domain.mac.velocityZ};
				boolean[][] valid = {domain.mac.velocityValidX, domain.mac.velocityValidY, domain.mac.velocityValidZ};
				var sourceSample = domain.sampleVelocity(point[0], point[1], point[2]);
				check(sourceSample.x() == velocity[0] && sourceSample.y() == velocity[1] && sourceSample.z() == velocity[2],
						"Current marker field did not reproduce its source quadrature velocity");
				float[] wallPoint = point.clone(); wallPoint[normal] = high ? (interior ? 1.5F : 2) : (interior ? .5F : 0);
				var publicWallSample = domain.sampleVelocity(wallPoint[0], wallPoint[1], wallPoint[2]);
				check((normal == 0 ? publicWallSample.x() : normal == 1 ? publicWallSample.y() : publicWallSample.z()) == 0,
						"Public wall sample lost its zero normal velocity");
				// The exact solid plane can belong to either cell; inspect the open cell's physical trace.
				var wallSample = domain.pressureSystem.markerVelocityField().sample(
						domain.particleCellIndex(point[0], point[1], point[2]), wallPoint[0], wallPoint[1], wallPoint[2]);
				float[] wallVelocity = {wallSample.x(), wallSample.y(), wallSample.z()};
				for (int a = 0; a < 3; a++) check(wallVelocity[a] == (a == normal ? 0 : velocity[a]),
						"Current marker wall trace lost zero normal velocity or free-slip tangential velocity: "
								+ normal + ", high=" + high + ", full=" + full + ", interior=" + interior + ", axis=" + a);
				for (int a = 0; a < 3; a++) {
					float[] aperture = domain.geometry.cutCells.openFaceField(a);
					for (int f = 0; f < aperture.length; f++) if (aperture[f] == 0) check(valid[a][f] == full && fields[a][f] == 0,
							"Closed face validity disagreed with the free/full transfer space on axis " + a);
					if (full && a != normal) continue;
					float[] constant = new float[fields[a].length];
					for (int f = 0; f < aperture.length; f++) if (aperture[f] > 0) constant[f] = velocity[a];
					float sampled = a == 0 ? domain.sampleX(constant, point[0], point[1], point[2])
							: a == 1 ? domain.sampleY(constant, point[0], point[1], point[2])
							: domain.sampleZ(constant, point[0], point[1], point[2]);
					// Open-stencil constants reproduce themselves. Full space retains the 3/4 known wall-zero weight.
					float expected = velocity[a] * (full ? .25F : 1);
					check(sampled == expected, "Wall translation, reflection or axis permutation changed the sampling rule: "
							+ normal + ", high=" + high + ", full=" + full + ", interior=" + interior + ", axis=" + a + ": " + sampled);
				}
			}
		}
	}

	private static void freeSurfaceTransferSeparatesIndependentSupport() {
		for (boolean wall : new boolean[] {false, true}) {
			double[] alone = disconnectedTransfer(false, wall ? 6 : 10, wall, false);
			double[] accompanied = disconnectedTransfer(true, wall ? 6 : 10, wall, false);
			check(java.util.Arrays.equals(alone, accompanied),
					"A disconnected carrier changed another component's energy-admissible velocity");
		}
		// The intervening extrapolated output has no weight and is sampled by neither carrier.
		check(java.util.Arrays.equals(disconnectedTransfer(false, 7, false, false), disconnectedTransfer(true, 7, false, false)),
				"An unused extrapolated output coupled its independent sources");
		// Adjacent carriers share interpolation/extrapolation dependencies and retain the joint budget.
		disconnectedTransfer(true, 5, false, true);
	}

	private static void closedTransferSeparatesIndependentSupport() {
		var alone = closedTransferFixture(0);
		int targetCount = alone.particleCount();
		transferCurrentGrid(alone);
		for (int remoteMode : new int[] {1, 2}) {
			var joint = closedTransferFixture(remoteMode);
			transferCurrentGrid(joint);
			for (int p = 0; p < targetCount; p++) check(alone.particles.velocityX(p) == joint.particles.velocityX(p)
					&& alone.particles.velocityY(p) == joint.particles.velocityY(p)
					&& alone.particles.velocityZ(p) == joint.particles.velocityZ(p),
					"Remote free/full liquid changed a closed support's resolved velocity: " + remoteMode + ", marker " + p);
		}
	}

	private static FluidDomain closedTransferFixture(int remoteMode) {
		var domain = new FluidDomain(9, 4, 2,
				new FluidSolverConfig(1, 1000, 0, 0.005F, 200, 1e-5F, 0.4F, 0.002F, 0.8F), 576);
		for (int z = 0; z < 2; z++) for (int y = 0; y < 4; y++) for (int x = 4; x < 9; x++)
			if (x == 4 || remoteMode == 0) domain.setSolid(x, y, z, true);
		for (int z = 0; z < 2; z++) for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) domain.setVolume(x, y, z, 1);
		if (remoteMode == 1) domain.setVolume(7, 1, 0, 0.01F);
		if (remoteMode == 2) for (int z = 0; z < 2; z++) for (int y = 0; y < 4; y++) for (int x = 5; x < 9; x++) domain.setVolume(x, y, z, 1);
		for (int p = 0; p < domain.particleCount(); p++) {
			if (domain.particles.positionX(p) < 4) {
				double x = Math.PI * domain.particles.positionX(p) / 4, y = Math.PI * domain.particles.positionY(p) / 4;
				domain.particles.setVelocityX(p, (float) (0.02 * Math.sin(x) * Math.cos(y)));
				domain.particles.setVelocityY(p, (float) (-0.02 * Math.cos(x) * Math.sin(y)));
			} else {
				// A different amplitude and unresolved noise require separate Gram iterations and budgets.
				domain.particles.setVelocityX(p, ((p * 7) % 11 - 5) * 0.13F);
				domain.particles.setVelocityZ(p, ((p * 3) % 7 - 3) * 0.17F);
			}
		}
		return domain;
	}

	private static void resolvedTransferRequiresExactAvailableCapacity() {
		for (boolean occupied : new boolean[] {false, true}) {
			float fill = occupied ? 0.75F : 1;
			var domain = new FluidDomain(2, 2, 2,
					new FluidSolverConfig(1, 1000, 0, 0.005F, 200, 1e-5F, 0.4F, 0.002F, 0.8F), 128);
			for (int z = 0; z < 2; z++) for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) domain.setVolume(x, y, z, fill);
			var coupling = new mmm.granular.GranularCouplingFields(2, 2, 2, 1);
			long[] solid = new long[8];
			if (occupied) java.util.Arrays.fill(solid, mmm.granular.SolidVolume.SVQ_PER_CELL / 4);
			coupling.setFixedCellSvq(solid);
			for (boolean deficit : new boolean[] {false, true}) {
				if (deficit) {
					// The true gap is smaller than a double ULP of the component capacity.
					var snapshot = domain.exportParticleState(); int count = snapshot.particleCount() + 1;
					float[] positions = java.util.Arrays.copyOf(snapshot.copyPositionVolume(), count * 4);
					float original = positions[3]; positions[3] = Math.nextDown(original);
					System.arraycopy(positions, 0, positions, 4 * (count - 1), 3);
					positions[4 * (count - 1) + 3] = Math.nextDown(original - positions[3]);
					domain.restoreParticleState(new FlipParticleState(2, 2, 2, 1, count, 8 * fill, positions, new float[count * 3], new byte[8]));
				}
				domain.rebuildGridFromParticles();
				// Exercise the same pressure refresh used by previewed granular occupancy.
				domain.pressureGranularCoupling = occupied ? coupling : null;
				domain.particleToGridStage.rebuildPressureGeometry();
				for (boolean classified : domain.projection.pressureFluid) check(classified, "Tiny-gap fixture lost its complete pressure classification");
				domain.gridToParticleStage.capturePreProjectionEnergy(); domain.gridToParticleStage.extrapolateAndTransfer();
				check(transferBudget(domain).anyResolved() == !deficit,
						"Resolved velocity ignored exact available capacity or a real sub-ULP deficit: occupied=" + occupied);
			}
		}
	}

	private static MarkerTransferBudget transferBudget(FluidDomain domain) {
		try {
			var field = GridToParticleStage.class.getDeclaredField("componentBudget"); field.setAccessible(true);
			return (MarkerTransferBudget) field.get(domain.gridToParticleStage);
		} catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
	}

	private static void zeroResolvedInputKeepsUnresolvedVelocity() {
		var domain = closedTransferFixture(1);
		transferCurrentGrid(domain);
		var support = transferBudget(domain);
		var projection = new ResolvedMarkerVelocity(domain.sizeX, domain.sizeY, domain.sizeZ,
				domain.config.cellSize(), domain.geometry.cutCells);
		float[] velocity = new float[domain.particleCount()];
		int resolved = 0, unresolved = 0;
		for (int p = 0; p < velocity.length; p++) {
			if (support.resolved(p)) { resolved++; velocity[p] = -0.0F; }
			else { unresolved++; velocity[p] = (p & 1) == 0 ? 2 : Float.intBitsToFloat(0x7fc01234); }
		}
		check(resolved > 0 && unresolved > 0, "Zero-input fixture must contain both transfer spaces");
		float[] initial = velocity.clone();
		for (int pass = 0; pass < 3; pass++) {
			for (int p = 0; p < velocity.length; p++) if (support.resolved(p)) velocity[p] = pass == 1 ? Float.MIN_NORMAL : -0.0F;
			projection.project(domain.particles, 0, domain.mac.velocityValidX, velocity, support);
			boolean nonzero = false;
			for (int p = 0; p < velocity.length; p++) {
				if (!support.resolved(p)) check(Float.floatToRawIntBits(velocity[p]) == Float.floatToRawIntBits(initial[p]),
						"Resolved projection changed an unresolved velocity or NaN payload");
				else if (pass != 1) check(Float.floatToRawIntBits(velocity[p]) == 0, "Zero projection did not produce positive zero");
				else { check(Float.isFinite(velocity[p]), "Small nonzero projection became nonfinite"); nonzero |= velocity[p] != 0; }
			}
			if (pass == 1) check(nonzero, "Small nonzero resolved input was treated as zero");
		}
	}

	private static void transferScratchTracksPressureSpaceChanges() {
		var domain = new FluidDomain(4, 3, 3,
				new FluidSolverConfig(1, 1000, 0, 0.05F, 200, 1e-5F, 0.45F, 0.002F, 0.8F), 512);
		for (int z = 0; z < 3; z++) for (int y = 0; y < 3; y++) for (int x = 0; x < 4; x++) domain.setVolume(x, y, z, 1);
		for (int pass = 0; pass < 4; pass++) {
			domain.setVolume(1, 1, 1, pass % 2 == 0 ? 1 : 0.25F);
			for (int p = 0; p < domain.particleCount(); p++) {
				domain.particles.setVelocityX(p, ((p * 7 + pass) % 11 - 5) * 0.01F);
				domain.particles.setVelocityY(p, ((p * 3 + pass) % 7 - 3) * 0.02F);
			}
			var fresh = FluidDomain.restoreAuthoritativeState(domain.captureAuthoritativeState());
			transferCurrentGrid(domain); transferCurrentGrid(fresh);
			try {
				var field = GridToParticleStage.class.getDeclaredField("completePressureSpace"); field.setAccessible(true);
				check(field.getBoolean(domain.gridToParticleStage) == (pass % 2 == 0), "Scratch fixture did not change pressure space");
			} catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
			for (int p = 0; p < domain.particleCount(); p++) check(domain.particles.velocityX(p) == fresh.particles.velocityX(p)
					&& domain.particles.velocityY(p) == fresh.particles.velocityY(p) && domain.particles.velocityZ(p) == fresh.particles.velocityZ(p),
					"Reused transfer scratch differed from fresh state after a pressure-space change");
		}
	}

	private static void finiteGasPressureConnectsTransferSupport() {
		for (boolean sealed : new boolean[] {false, true}) {
			var domain = new FluidDomain(14, 5, 5,
					new FluidSolverConfig(1, 1000, 0, 0.05F, 200, 1e-5F, 0.45F, 0.002F, 0.8F), 512);
			domain.setVolume(4, 2, 2, 1); domain.setVolume(10, 2, 2, 1);
			if (sealed) { domain.sealDomainBoundary(); domain.bootstrapGasState(null); }
			domain.ensureGrid(); domain.gasTopologyCoordinator.refresh(); domain.gridToParticleStage.capturePreProjectionEnergy();
			domain.pressureStage.solve(domain.config.timeStep()); domain.pressureStage.projectVelocities(domain.config.timeStep());
			domain.gridToParticleStage.extrapolateAndTransfer();
			check((domain.projection.gasUnknownCount > 0) == sealed, "Finite-gas support fixture did not assemble its gas row");
			try {
				var plan = domain.pressureSystem.markerProjection();
				var field = MarkerPressureProjection.class.getDeclaredField("gasRows"); field.setAccessible(true);
				boolean shared = false;
				for (int row : (int[]) field.get(plan)) if (row >= 0) {
					double[] pressure = new double[plan.operator().rowCount()]; pressure[row] = 1;
					double[][] force = new double[3][domain.particleCount()]; plan.operator().force(pressure, force);
					boolean first = false, second = false;
					for (int p = 0; p < domain.particleCount(); p++) for (int a = 0; a < 3; a++) if (force[a][p] != 0) {
						int cell = plan.basis().physicalCell(plan.basis().markerBlock(p));
						first |= cell == domain.cellIndex(4, 2, 2); second |= cell == domain.cellIndex(10, 2, 2);
					}
					shared |= first && second;
				}
				check(shared == sealed, "Actual finite-gas pressure row did not retain its common liquid-force support");
			} catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
		}
	}

	private static void transferCurrentGrid(FluidDomain domain) {
		domain.rebuildGridFromParticles();
		float[][] fields = {domain.mac.velocityX, domain.mac.velocityY, domain.mac.velocityZ};
		float[][] previous = {domain.mac.p2gVelocityX, domain.mac.p2gVelocityY, domain.mac.p2gVelocityZ};
		for (int a = 0; a < 3; a++) System.arraycopy(fields[a], 0, previous[a], 0, fields[a].length);
		domain.gridToParticleStage.capturePreProjectionEnergy(); domain.gridToParticleStage.extrapolateAndTransfer();
	}

	private static double[] disconnectedTransfer(boolean remote, int remoteX, boolean wall, boolean checkJointBudget) {
		var domain = new FluidDomain(14, 5, 5,
				new FluidSolverConfig(1, 1000, 0, 0.05F, 200, 1e-5F, 0.45F, 0.002F, 0.8F), 512);
		byte[] masks = new byte[14 * 5 * 5];
		if (wall) for (int y = 0; y < 5; y++) for (int z = 0; z < 5; z++) masks[(z * 5 + y) * 14 + 5] = (byte) 255;
		int sources = remote ? 4 : 2; float[] positions = new float[4 * sources], velocities = new float[3 * sources];
		for (int p = 0; p < sources; p++) {
			positions[4 * p] = (p < 2 ? 4 : remoteX) + ((p & 1) == 0 ? .25F : .75F);
			positions[4 * p + 1] = 2.5F; positions[4 * p + 2] = 2.5F; positions[4 * p + 3] = .125F;
			velocities[3 * p + (p < 2 ? 0 : 1)] = (p & 1) == 0 ? 1 : -1;
		}
		domain.restoreParticleState(new FlipParticleState(14, 5, 5, 1, sources, sources * .125F, positions, velocities, masks));
		int[] target = new int[2]; int count = 0;
		for (int p = 0; p < domain.particles.count(); p++) {
			if (domain.particleCellIndex(p) == domain.cellIndex(4, 2, 2)) {
				target[count++] = p;
			}
		}
		check(count == 2, "Transfer isolation fixture needs two target points");
		domain.rebuildGridFromParticles();
		float[][] fields = {domain.mac.velocityX, domain.mac.velocityY, domain.mac.velocityZ};
		float[][] previous = {domain.mac.p2gVelocityX, domain.mac.p2gVelocityY, domain.mac.p2gVelocityZ};
		for (int a = 0; a < 3; a++) System.arraycopy(fields[a], 0, previous[a], 0, fields[a].length);
		domain.gridToParticleStage.capturePreProjectionEnergy();
		float[][] expected = checkJointBudget ? originalJointTransfer(domain) : null;
		domain.gridToParticleStage.extrapolateAndTransfer();
		if (expected != null) for (int p = 0; p < domain.particles.count(); p++) {
			check(domain.particles.velocityX(p) == expected[0][p] && domain.particles.velocityY(p) == expected[1][p]
					&& domain.particles.velocityZ(p) == expected[2][p], "One support component changed the established transfer budget");
		}
		return new double[] {domain.particles.velocityX(target[0]), domain.particles.velocityX(target[1])};
	}

	/** Independent evaluation of the established one-component energy polynomial. */
	private static float[][] originalJointTransfer(FluidDomain domain) {
		domain.gridToParticleStage.extrapolateVelocityIntoAir();
		float[][] values = {domain.mac.velocityX, domain.mac.velocityY, domain.mac.velocityZ};
		float[][] old = {domain.mac.p2gVelocityX, domain.mac.p2gVelocityY, domain.mac.p2gVelocityZ};
		boolean[][] valid = {domain.mac.velocityValidX, domain.mac.velocityValidY, domain.mac.velocityValidZ};
		double[][] weights = {domain.mac.p2gWeightX, domain.mac.p2gWeightY, domain.mac.p2gWeightZ};
		int n = domain.particles.count(); float[][] pic = new float[3][n], delta = new float[3][n];
		double quadratic = 0, linear = 0, constant = 0, budget = 0;
		for (int a = 0; a < 3; a++) {
			double faceEnergy = 0;
			for (int f = 0; f < values[a].length; f++) faceEnergy += 0.5 * domain.config.density() * weights[a][f] * values[a][f] * (double) values[a][f];
			budget += faceEnergy;
		}
		float[] sample = new float[2];
		for (int p = 0; p < n; p++) {
			float[] incoming = {domain.particles.velocityX(p), domain.particles.velocityY(p), domain.particles.velocityZ(p)};
			for (int a = 0; a < 3; a++) {
				FluidDomainMechanicalFields.trilinearValidPair(values[a], old[a], valid[a], 14 + (a == 0 ? 1 : 0),
						5 + (a == 1 ? 1 : 0), 5 + (a == 2 ? 1 : 0), domain.particles.positionX(p) - (a == 0 ? 0 : 0.5F),
						domain.particles.positionY(p) - (a == 1 ? 0 : 0.5F), domain.particles.positionZ(p) - (a == 2 ? 0 : 0.5F), sample);
				pic[a][p] = sample[0]; delta[a][p] = incoming[a] - sample[1];
			}
			double m = domain.particles.mass(p);
			quadratic += 0.5 * m * (delta[0][p] * (double) delta[0][p] + delta[1][p] * (double) delta[1][p] + delta[2][p] * (double) delta[2][p]);
			linear += m * (pic[0][p] * (double) delta[0][p] + pic[1][p] * (double) delta[1][p] + pic[2][p] * (double) delta[2][p]);
			constant += 0.5 * m * (pic[0][p] * (double) pic[0][p] + pic[1][p] * (double) pic[1][p] + pic[2][p] * (double) pic[2][p]);
		}
		double alpha = FlipTransferScheme.maximumEnergyStableTransfer(quadratic, linear, constant, Math.max(constant, budget));
		for (int p = 0; p < n; p++) for (int a = 0; a < 3; a++) pic[a][p] = (float) (pic[a][p] + alpha * delta[a][p]);
		return pic;
	}

	private static void bodyOverlapResolvesContinuousSubmergence() {
		FluidSolverConfig config = FluidSolverConfig.prototypeDefaults();
		FluidDomain domain = new FluidDomain(6, 6, 6, config, 6 * 6 * 6 * 8);
		for (int z = 0; z < 6; z++) for (int x = 0; x < 6; x++) {
			domain.setVolume(x, 0, z, 1.0F);
			domain.setVolume(x, 1, z, 1.0F);
			domain.setVolume(x, 2, z, 1.0F);
		}
		double width = 0.60;
		double height = 1.80;
		double expectedFraction = 0.72;
		double surfaceY = 3.0 * config.cellSize();
		double minimumY = surfaceY - height * expectedFraction;
		FluidDomain.BodyFluidSample sample = domain.sampleAxisAlignedBody(
				1.20, minimumY, 1.20, 1.20 + width, minimumY + height, 1.20 + width);
		double bodyVolume = width * height * width;
		check(Math.abs(sample.displacedVolumeCubicMetres() / bodyVolume - expectedFraction) < 1.0E-6,
				"body overlap quantized continuous neutral submergence: "
						+ sample.displacedVolumeCubicMetres() / bodyVolume);
		check(sample.centerYMetres() >= minimumY && sample.centerYMetres() <= surfaceY,
				"body overlap centroid escaped the wet AABB segment: " + sample.centerYMetres());

		FluidDomain.BodyFluidSample shifted = domain.sampleAxisAlignedBody(
				1.20, minimumY + 0.01, 1.20, 1.20 + width, minimumY + height + 0.01, 1.20 + width);
		check(Math.abs((sample.displacedVolumeCubicMetres() - shifted.displacedVolumeCubicMetres())
				- width * width * 0.01) < 1.0E-6,
				"body overlap did not respond continuously to a sub-sample vertical displacement");
	}

	private static void resolvedVelocityPreservesSmoothMotionAndRejectsSamplingNoise() {
		var config = new FluidSolverConfig(0.5F, 1000, 0, 0.001F, 300, 1e-7F, 0.4F, 0.002F, 0);
		var domain = new FluidDomain(8, 8, 2, config, 2048);
		for (int z = 0; z < 2; z++) for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) domain.setVolume(x, y, z, 1);
		for (int p = 0; p < domain.particleCount(); p++) {
			double x = Math.PI * domain.particles.positionX(p) / 4, y = Math.PI * domain.particles.positionY(p) / 4;
			domain.particles.setVelocityX(p, (float) (-0.02 * Math.sin(x) * Math.cos(y)));
			domain.particles.setVelocityY(p, (float) (0.02 * Math.cos(x) * Math.sin(y)));
		}
		double rawEnergy = domain.particleKineticEnergyJoules();
		domain.ensureGrid(); domain.gasTopologyCoordinator.refresh();
		System.arraycopy(domain.mac.velocityX, 0, domain.mac.p2gVelocityX, 0, domain.mac.velocityX.length);
		System.arraycopy(domain.mac.velocityY, 0, domain.mac.p2gVelocityY, 0, domain.mac.velocityY.length);
		System.arraycopy(domain.mac.velocityZ, 0, domain.mac.p2gVelocityZ, 0, domain.mac.velocityZ.length);
		domain.pressureStage.solve(config.timeStep());
		var admission = domain.pressureSystem.markerProjection();
		var original = admission.predictor(); var continuous = admission.candidate();
		for (int p = 0; p < domain.particleCount(); p++)
			check(original[0][p] == domain.particles.velocityX(p) && original[1][p] == domain.particles.velocityY(p)
					&& original[2][p] == domain.particles.velocityZ(p), "unforced initial pressure predictor changed the sine source");
		var target = admission.targetVolumeRate(); var pressure = admission.pressure();
		double[] rates = new double[target.length];
		admission.operator().volumeRate(continuous, rates);
		double residualSquared = 0;
		var residualWork = mmm.units.SignedDyadic.ZERO;
		for (int row = 0; row < rates.length; row++) {
			check(target[row] == 0, "closed unforced admission acquired a volume source");
			residualSquared += rates[row] * rates[row];
			residualWork = residualWork.add(mmm.units.SignedDyadic.of(config.timeStep())
					.multiply(mmm.units.SignedDyadic.of(Math.abs(pressure[row])))
					.multiply(mmm.units.SignedDyadic.of(Math.abs(rates[row]))));
		}
		double unit = Math.pow(config.cellSize(), 3) / config.density();
		double admissionResidual = Math.sqrt(residualSquared / rates.length) / (config.timeStep() * unit);
		check(admissionResidual <= config.pressureTolerance(), "initial full pressure residual: " + admissionResidual);
		domain.pressureStage.projectVelocities(config.timeStep());
		var rawK = mmm.units.SignedDyadic.ZERO; var projectedK = rawK; var removedK = rawK; var roundingWork = rawK;
		for (int p = 0; p < domain.particleCount(); p++) {
			var mass = mmm.units.SignedDyadic.of(domain.unitScale.kilograms(domain.particles.mass(p)));
			double[] stored = {domain.particles.velocityX(p), domain.particles.velocityY(p), domain.particles.velocityZ(p)};
			for (int axis = 0; axis < 3; axis++) {
				var u = mmm.units.SignedDyadic.of(original[axis][p]); var v = mmm.units.SignedDyadic.of(stored[axis]);
				var difference = u.subtract(v); var halfMass = mass.multiply(mmm.units.SignedDyadic.of(0.5));
				rawK = rawK.add(halfMass.multiply(u).multiply(u));
				projectedK = projectedK.add(halfMass.multiply(v).multiply(v));
				removedK = removedK.add(halfMass.multiply(difference).multiply(difference));
				// Publication changes v*u-v*v by at most e*(|u|+2|v_continuous|+e).
				var e = mmm.units.SignedDyadic.of(0.5 * Math.ulp((float) continuous[axis][p]));
				var scale = mmm.units.SignedDyadic.of(Math.abs(original[axis][p]))
						.add(mmm.units.SignedDyadic.of(Math.abs(continuous[axis][p])).multiply(mmm.units.SignedDyadic.of(2.0))).add(e);
				roundingWork = roundingWork.add(mass.multiply(e).multiply(scale));
			}
		}
		var identityError = rawK.subtract(projectedK).subtract(removedK);
		if (identityError.signum() < 0) identityError = mmm.units.SignedDyadic.ZERO.subtract(identityError);
		check(identityError.compareTo(residualWork.add(roundingWork)) <= 0,
				"initial pressure mass-orthogonal energy identity: " + identityError.toDouble());
		double initial = domain.particleKineticEnergyJoules();
		check(initial > 0 && initial <= rawEnergy + roundingWork.toDouble(), "initial pressure projection increased kinetic energy");
		System.out.println("Smooth-motion admission: rawK=" + rawEnergy + ", projectedK=" + initial
				+ ", removedK=" + removedK.toDouble() + ", fullResidual=" + admissionResidual);
		double volumeTransfer = 0;
		for (int step = 0; step < 40; step++) {
			var audit = domain.stepWithMechanicalAudit(false, 0).mechanicalAudit();
			volumeTransfer += audit.volumeTransportTransferJoules();
			check(audit.particleCollisionWorkJoules() == 0 && audit.closureResidualJoules() == 0,
					"unforced closed transport acquired collision work or lost mechanical closure");
		}
		double ratio = domain.particleKineticEnergyJoules() / initial;
		double transportBound = 2 * 0.02 * (40 * config.timeStep()) / config.cellSize() * Math.pow(Math.PI * config.cellSize() / 4, 2);
		check(ratio <= 1 + 64 * Math.ulp(1.0F) && Math.abs(ratio - 1 - volumeTransfer / initial) <= 40 * 16 * Math.ulp(1.0F)
				&& Math.abs(volumeTransfer / initial) <= transportBound,
				"unforced resolved motion lost energy beyond float transfer error: " + ratio);
		System.out.println("Smooth-motion 40 steps: energyRatio=" + ratio + ", volumeTransfer=" + volumeTransfer);
		domain.ensureGrid();
		float[] raw = new float[domain.particleCount()], projected = new float[raw.length], residual = new float[raw.length];
		for (int p = 0; p < raw.length; p++) raw[p] = projected[p] = ((p * 17) % 29 - 14) / 14.0F;
		var projection = new ResolvedMarkerVelocity(8, 8, 2, 0.5F, domain.geometry.cutCells);
		projection.project(domain.particles, 0, domain.mac.velocityValidX, projected);
		double cross = 0, norm = 0;
		for (int p = 0; p < raw.length; p++) {
			residual[p] = raw[p] - projected[p];
			cross += domain.particles.mass(p) * projected[p] * residual[p];
			norm += domain.particles.mass(p) * raw[p] * raw[p];
		}
		check(Math.abs(cross) <= 32 * Math.ulp(1.0F) * norm, "resolved velocity projection is not mass-orthogonal");
		projection.project(domain.particles, 0, domain.mac.velocityValidX, residual);
		double remainder = 0;
		for (int p = 0; p < raw.length; p++) remainder += domain.particles.mass(p) * residual[p] * residual[p];
		check(remainder <= Math.pow(64 * Math.ulp(1.0F), 2) * norm,
				"MAC-invisible velocity survived resolved transfer: " + remainder / norm);
	}

	private static void airExtensionRetainsLiquidFacesAndResolvesMarkerFringe() {
		var domain = new FluidDomain(6, 4, 2, FluidSolverConfig.prototypeDefaults());
		boolean[] fluid = new boolean[domain.cellCount];
		int[] markers = new int[domain.cellCount];
		for (int z = 0; z < 2; z++) for (int y = 0; y < 4; y++) for (int x = 0; x < 6; x++) {
			int c = domain.cellIndex(x, y, z); fluid[c] = x < 3; markers[c] = x <= 3 ? 8 : 0;
		}
		for (int z = 0; z < 2; z++) for (int y = 0; y < 4; y++) for (int x = 1; x < 6; x++)
			domain.mac.velocityX[domain.xFaceIndex(x, y, z)] = 0.125F;
		for (int z = 0; z < 2; z++) for (int y = 1; y < 4; y++) {
			int face = domain.yFaceIndex(3, y, z);
			domain.mac.velocityY[face] = 0.1F * y;
			domain.mac.p2gWeightY[face] = 2;
		}
		// A tensor stencil reaches a face whose two adjacent cells contain no marker centre.
		int remoteMassFace = domain.yFaceIndex(5, 1, 0);
		domain.mac.velocityY[remoteMassFace] = -0.375F;
		domain.mac.p2gWeightY[remoteMassFace] = 3;
		float[] original = domain.mac.velocityX.clone();
		float[] originalY = domain.mac.velocityY.clone();
		domain.pressureStage.projectVelocities(domain.config.timeStep());
		for (int f = 0; f < originalY.length; f++) if (domain.mac.p2gWeightY[f] > 0)
			check(Float.floatToRawIntBits(domain.mac.velocityY[f]) == Float.floatToRawIntBits(originalY[f]),
					"pressure projection cleared a positive-mass face outside its pressure support");
		System.arraycopy(original, 0, domain.mac.velocityX, 0, original.length);
		domain.velocityExtrapolator.rebuildAndExtrapolate(domain.geometry.solid, markers, fluid, false, domain.mac, null);
		for (int f = 0; f < originalY.length; f++) if (domain.mac.p2gWeightY[f] > 0)
			check(Float.floatToRawIntBits(domain.mac.velocityY[f]) == Float.floatToRawIntBits(originalY[f])
					&& domain.mac.velocityValidY[f], "velocity extrapolation replaced a positive-mass stencil face");
		double[] volume = new double[domain.cellCount];
		for (int c = 0; c < volume.length; c++) volume[c] = fluid[c] ? 1 : markers[c] > 0 ? .25 : 0;
		domain.pressurePhaseGeometry.rebuild(domain.geometry.solid, volume, domain.geometry.cutCells, null, new boolean[domain.cellCount]);
		var extension = new DivergenceFreeVelocityExtension(6, 4, 2, domain.geometry.cutCells, domain.pressurePhaseGeometry);
		extension.extend(domain.mac, fluid, domain.geometry.solid, markers);
		for (int f = 0; f < originalY.length; f++) if (domain.mac.p2gWeightY[f] > 0)
			check(Float.floatToRawIntBits(domain.mac.velocityY[f]) == Float.floatToRawIntBits(originalY[f]),
					"air extension changed a positive-mass face, its momentum and kinetic energy");
		for (int z = 0; z < 2; z++) for (int y = 0; y < 4; y++) {
			for (int x = 1; x <= 3; x++) check(domain.mac.velocityX[domain.xFaceIndex(x, y, z)] == original[domain.xFaceIndex(x, y, z)],
					"air extension changed a prescribed liquid face");
			double div = domain.mac.velocityX[domain.xFaceIndex(4, y, z)] - domain.mac.velocityX[domain.xFaceIndex(3, y, z)]
					+ domain.mac.velocityY[domain.yFaceIndex(3, y + 1, z)] - domain.mac.velocityY[domain.yFaceIndex(3, y, z)]
					+ domain.mac.velocityZ[domain.zFaceIndex(3, y, z + 1)] - domain.mac.velocityZ[domain.zFaceIndex(3, y, z)];
			check(Math.abs(div) < 8 * Math.ulp(1.0F), "marker fringe acquired compressible motion: " + div);
		}
		java.util.Arrays.fill(fluid, true);
		int pocket = domain.cellIndex(3, 1, 0); fluid[pocket] = false;
		original = domain.mac.velocityX.clone();
		extension.extend(domain.mac, fluid, domain.geometry.solid, markers);
		check(java.util.Arrays.equals(original, domain.mac.velocityX) && extension.advectionCells[pocket],
				"closed gas component received an artificial incompressible outlet");
	}

	private static void pureWaterMarkerMassLedgerTracksVolume() {
		FluidSolverConfig config = FluidSolverConfig.prototypeDefaults();
		FluidDomain domain = new FluidDomain(4, 4, 4, config, 128);
		domain.setVolume(1, 1, 1, 1.0F);
		domain.setVolume(2, 1, 1, 0.375F);
		check(Math.abs(domain.totalMass() - (double) config.density() * domain.totalVolume()) <= 1.0E-9,
				"pure-water marker mass did not match the exact volume ledger");
		domain.removeVolumeNear(2, 1, 1, 0.125F, 0);
		check(Math.abs(domain.totalMass() - (double) config.density() * domain.totalVolume()) <= 1.0E-9,
				"partial marker drain did not remove proportional mass");
		domain.step();
		check(Math.abs(domain.totalMass() - (double) config.density() * domain.totalVolume()) <= 1.0E-9,
				"pure-water reseeding did not preserve the mass/volume relation");
	}

	private static void planeVolumeInversionIsAccurate() {
		float[][] normals = {
				{1.0F, 0.0F, 0.0F},
				{0.0F, -1.0F, 0.0F},
				{0.70710677F, 0.70710677F, 0.0F},
				{0.125F, -1.0F, 0.0F},
				{0.0F, -0.125F, 1.0F},
				{-1.0F, 0.0F, 0.125F},
				{-0.26726124F, 0.5345225F, 0.8017837F}
		};
		float[] fractions = {0.001F, 0.05F, 0.0625F, 0.10F, 0.25F, 0.50F, 0.75F, 0.90F, 0.9375F, 0.95F, 0.999F};
		for (float[] normal : normals) for (float fraction : fractions) {
			float offset = FlipFreeSurfaceGeometry.solvePlaneOffset(normal[0], normal[1], normal[2], fraction);
			float reconstructed = FlipFreeSurfaceGeometry.unitCubeLiquidFraction(
					normal[0], normal[1], normal[2], offset
			);
			check(Math.abs(reconstructed - fraction) < 2.0E-6F,
					"PLIC inversion drifted: expected=" + fraction + ", reconstructed=" + reconstructed);
		}
	}

	private static void liquidPlaneRetainsSmallInventoryGeometry() {
		for (float amount : new float[] {Float.MIN_VALUE, Math.scalb(1.0F, -25), Math.nextUp(.125F), Math.nextDown(1.0F)}) {
			var domain = new FluidDomain(2, 2, 2, strictConfig());
			int count = ConservativeMarkerSampling.targetMarkers(amount);
			float[] positions = new float[4 * count]; float h = domain.config.cellSize();
			for (int p = 0; p < count; p++) {
				positions[4 * p] = h * (.25F + .5F * (p & 1));
				positions[4 * p + 1] = h * (.25F + .5F * (p >> 1 & 1));
				positions[4 * p + 2] = h * (.25F + .5F * (p >> 2 & 1));
				positions[4 * p + 3] = amount / count;
			}
			domain.restoreParticleState(new FlipParticleState(2, 2, 2, h, count, amount, positions, new float[3 * count], new byte[8]));
			check(domain.particleCount() == ConservativeMarkerSampling.targetMarkers(amount), "Positive initial inventory lost its carrier");
			check(mmm.units.SignedDyadic.of(amount).equals(mmm.units.SignedDyadic.sum(
					domain.particles.directArrays().volume, domain.particleCount())), "Cell initialization discarded positive inventory");
			check(domain.totalMass() == (double) amount * domain.config.density(), "Cell initialization changed inventory density");
			if (amount >= Float.MIN_NORMAL) {
				for (int p = 0; p < domain.particleCount(); p++) domain.particles.setVelocityY(p, -.5F);
				domain.rebuildGridFromParticles();
				float[] sums = new float[domain.mac.velocityY.length], weights = new float[sums.length];
				for (int p = 0; p < domain.particleCount(); p++) {
					float x = domain.particles.positionX(p), y = domain.particles.positionY(p), z = domain.particles.positionZ(p);
					domain.splatFaceField(sums, weights, 1, x / h, y / h, z / h, -.5F, domain.particles.volume(p));
					check(domain.sampleVelocity(x, y, z).y() == -.5F, "Current marker field diluted its positive low-weight source velocity");
				}
				FluidDomainStepCoordinator.normalize(sums, weights); boolean observed = false;
				for (int f = 0; f < weights.length; f++)
					if (f / domain.sizeX % (domain.sizeY + 1) > 0 && f / domain.sizeX % (domain.sizeY + 1) < domain.sizeY
							&& weights[f] > 0 && domain.geometry.cutCells.openFaceField(1)[f] > 0) {
						observed = true;
						check(Math.abs(sums[f] + .5F) <= 2 * Math.ulp(.5F),
								"Positive low-weight P2G support diluted a uniform carrier velocity");
					}
				check(observed, "Positive low-weight P2G fixture has no open interior face support");
			}
		}
		for (double scale : new double[] {1, 1e-7, 1e-11, 1e-30}) {
			// A vertex tetrahedron has exact volume offset^3/(6*nx*ny*nz).
			double fraction = 1e-7, distance = Math.cbrt(6 * fraction);
			double offset = FlipFreeSurfaceGeometry.solvePlaneOffset(scale, scale, scale, fraction);
			check(Math.abs(offset / scale - distance) <= 8 * Math.ulp(distance), "Plane inversion changed with normal scale");
			double recovered = FlipFreeSurfaceGeometry.unitCubeLiquidFraction(scale, scale, scale, scale * distance);
			check(Math.abs(recovered - fraction) <= 16 * Math.ulp(fraction), "Tetrahedron volume changed with normal scale");
			float stored = FlipFreeSurfaceGeometry.unitCubeLiquidFraction((float) scale, (float) scale, (float) scale, (float) (scale * distance));
			check(Math.abs(stored - fraction) <= 8 * Math.ulp((float) fraction), "Float plane used an absolute normal cutoff");
		}
		for (double transverse : new double[] {0, 1e-8, 1e-12}) {
			double volume = Math.scalb(1.0, -25);
			double off = FlipFreeSurfaceGeometry.solvePlaneOffset(-1.0, -transverse, 0.0, volume);
			double recovered = FlipFreeSurfaceGeometry.unitCubeLiquidFraction(-1.0, -transverse, 0.0, off);
			double swept = 0.015625 * FlipFreeSurfaceGeometry.unitCubeLiquidFraction(-0.015625, -transverse, 0.0, off + 1 - 0.015625);
			check(Math.abs(recovered - volume) <= 8 * Math.ulp(1.0), "Small liquid inventory was lost in plane offset storage");
			check(Math.abs(swept - volume) <= 8 * Math.ulp(1.0), "A containing face sweep changed the small liquid inventory");
		}
	}

	private static void sharedWetFacesControlTransportOnly() {
		var domain = new FluidDomain(4, 4, 2, FluidSolverConfig.prototypeDefaults());
		double[] volume = new double[domain.cellCount];
		int[] markers = new int[domain.cellCount];
		boolean[] fluid = new boolean[domain.cellCount];
		for (int z = 0; z < 2; z++) for (int y = 0; y < 4; y++) for (int x = 0; x < 3; x++) {
			int c = domain.cellIndex(x, y, z); volume[c] = x == 0 ? 1 : x == 1 ? .25 : Math.scalb(1.0, -25); markers[c] = 1;
		}
		domain.pressurePhaseGeometry.rebuild(domain.geometry.solid, volume, domain.geometry.cutCells, null, fluid);
		var extension = new DivergenceFreeVelocityExtension(4, 4, 2, domain.geometry.cutCells, domain.pressurePhaseGeometry);
		extension.prepareAdvectionCells(fluid, domain.geometry.solid, markers);
		int bulk = domain.cellIndex(0, 1, 0), attached = bulk + 1, separate = bulk + 2;
		check(extension.advectionCells[attached] && !extension.advectionCells[separate], "Dry shared face admitted a disconnected carrier to cell transport");
		check(domain.pressurePhaseGeometry.sharedLiquidFaceArea(attached, separate, 0, domain.geometry.cutCells) == 0,
				"Disjoint PLICs were connected by face aperture alone");
		// Both sides wet part of a face, but their occupied intervals may be disjoint.
		for (double fill : new double[] {.25, .75}) {
			java.util.Arrays.fill(volume, 0);
			for (int z = 0; z < 2; z++) {
				for (int x = 0; x < 4; x++) volume[domain.cellIndex(x, 1, z)] = fill;
				volume[domain.cellIndex(1, 0, z)] = 1; volume[domain.cellIndex(2, 2, z)] = 1;
			}
			domain.pressurePhaseGeometry.rebuild(domain.geometry.solid, volume, domain.geometry.cutCells, null, fluid);
			double intersection = domain.pressurePhaseGeometry.sharedLiquidFaceArea(domain.cellIndex(1, 1, 0), domain.cellIndex(2, 1, 0), 0, domain.geometry.cutCells);
			check(Math.abs(intersection - Math.max(0, 2 * fill - 1)) <= 8 * Math.ulp(1.0),
					"Common wet face used separate face areas instead of their intersection: " + intersection);
		}
		// A continuous layer of arbitrarily small positive thickness has a positive common face.
		java.util.Arrays.fill(volume, 0);
		double thickness = Math.scalb(1.0, -25);
		for (int z = 0; z < 2; z++) for (int x = 0; x < 4; x++) volume[domain.cellIndex(x, 0, z)] = thickness;
		domain.pressurePhaseGeometry.rebuild(domain.geometry.solid, volume, domain.geometry.cutCells, null, fluid);
		double area = domain.pressurePhaseGeometry.sharedLiquidFaceArea(domain.cellIndex(1, 0, 0), domain.cellIndex(2, 0, 0), 0, domain.geometry.cutCells);
		check(Math.abs(area - thickness) <= 8 * Math.ulp(thickness), "Thin continuous layer lost its positive shared face: " + area);
		java.util.Arrays.fill(markers, 0);
		for (int z = 0; z < 2; z++) for (int x = 0; x < 4; x++) markers[domain.cellIndex(x, 0, z)] = 1;
		// The pressure seed is prescribed here to isolate connectivity at the smallest positive thickness.
		fluid[domain.cellIndex(0, 0, 0)] = true;
		extension.prepareAdvectionCells(fluid, domain.geometry.solid, markers);
		check(extension.advectionCells[domain.cellIndex(3, 0, 0)], "Wet transport connectivity truncated a positive thin layer");
		// Cut faces use the same open micro rectangles as their volume reconstruction.
		domain.geometry.cutCells.replaceMask(domain.cellIndex(1, 0, 0), 0xCC);
		domain.pressurePhaseGeometry.rebuild(domain.geometry.solid, volume, domain.geometry.cutCells, null, fluid);
		area = domain.pressurePhaseGeometry.sharedLiquidFaceArea(domain.cellIndex(1, 0, 0), domain.cellIndex(2, 0, 0), 0, domain.geometry.cutCells);
		check(Math.abs(area - thickness) <= 128 * Math.ulp(thickness), "Open cut face lost a continuous thin layer: " + area);
	}

	private static void axisAlignedInterfaceDistanceUsesExactCellLedger() {
		for (float fraction : new float[] {0.60F, 0.75F, 1.0F}) {
			int sizeX = 5, sizeY = 4, sizeZ = 5;
			int cellCount = sizeX * sizeY * sizeZ;
			boolean[] solid = new boolean[cellCount];
			FlipPressurePhaseGeometry geometry = new FlipPressurePhaseGeometry(sizeX, sizeY, sizeZ);
			FlipCutCellGeometry cutCells = new FlipCutCellGeometry(sizeX, sizeY, sizeZ);
			double[] exactVolume = new double[cellCount];
			boolean[] pressureFluid = new boolean[cellCount];
			for (int z = 0; z < sizeZ; z++) for (int x = 0; x < sizeX; x++) {
				int lower = index(sizeX, sizeY, x, 0, z);
				int surface = index(sizeX, sizeY, x, 1, z);
				exactVolume[lower] = 1.0F;
				exactVolume[surface] = fraction;
			}
			geometry.rebuild(solid, exactVolume, cutCells, null, pressureFluid);
			int surface = index(sizeX, sizeY, 2, 1, 2);
			float expected = fraction >= 1.0F - 1.0E-6F ? 0.5F : fraction - 0.5F;
			float actual = geometry.interfaceTheta(surface, 0, 1, 0);
			check(Math.abs(actual - expected) < 2.0E-5F,
					"axis-aligned theta did not follow the exact cell fraction: fraction=" + fraction + ", theta=" + actual);
			check(Math.abs(geometry.liquidFraction(surface) - fraction) < 1.0E-7F,
					"geometry did not retain the exact per-cell marker sum");
		}
	}

	private static void pressurePhaseUsesConservativeFootprintVolume() {
		int sizeX = 5, sizeY = 3, sizeZ = 3;
		int cellCount = sizeX * sizeY * sizeZ;
		boolean[] solid = new boolean[cellCount];
		boolean[] fluid = new boolean[cellCount];
		double[] reconstructed = new double[cellCount];
		int bulk = index(sizeX, sizeY, 1, 1, 1);
		int fringe = index(sizeX, sizeY, 2, 1, 1);
		FlipPressurePhaseGeometry pressure = new FlipPressurePhaseGeometry(sizeX, sizeY, sizeZ);
		FlipCutCellGeometry cutCells = new FlipCutCellGeometry(sizeX, sizeY, sizeZ);
		reconstructed[bulk] = 0.875F;
		reconstructed[fringe] = 0.125F;
		pressure.rebuild(solid, reconstructed, cutCells, null, fluid);
		check(fluid[bulk] && !fluid[fringe]
				&& Math.abs(pressure.liquidFraction(bulk) - 0.875F) < 1.0E-7F
				&& Math.abs(pressure.liquidFraction(fringe) - 0.125F) < 1.0E-7F,
				"pressure phase did not retain the conservative center-contained footprint");
		reconstructed[bulk] = 0.75F;
		reconstructed[fringe] = 0.25F;
		pressure.rebuild(solid, reconstructed, cutCells, null, fluid);
		check(fluid[bulk] && !fluid[fringe]
				&& Math.abs(pressure.liquidFraction(bulk) + pressure.liquidFraction(fringe) - 1.0F) < 1.0E-7F,
				"pressure footprint transfer did not preserve its source volume");
		int wall = index(sizeX, sizeY, 2, 2, 1);
		solid[wall] = true;
		cutCells.replaceMask(wall, FlipCutCellGeometry.FULL_MASK);
		pressure.rebuild(solid, reconstructed, cutCells, null, fluid);
		check(fluid[bulk] && !fluid[fringe],
				"a wall-adjacent subcentre film was promoted into a cell-centred pressure unknown");
	}

	private static void closedComponentsUseExactLiquidCapacity() {
		var domain = new FluidDomain(4, 4, 2, FluidSolverConfig.prototypeDefaults(), 512);
		for (int z = 0; z < 2; z++) for (int y = 0; y < 4; y++) {
			domain.setSolid(2, y, z, true);
			for (int x = 0; x < 2; x++) domain.setVolume(x, y, z, 1);
			domain.setVolume(3, y, z, 0.25F);
		}
		// The volume rule is exact even when the moving point quadrature is badly conditioned.
		for (int p = 0; p < domain.particleCount(); p++) if (domain.particles.positionX(p) < 2) {
			domain.particles.setPositionX(p, 0.875F);
			domain.particles.setPositionY(p, 0.875F);
			domain.particles.setPositionZ(p, 0.375F);
		}
		domain.gridDirty = true; domain.ensureInventoryGrid();
		double left = 0, right = 0;
		for (int z = 0; z < 2; z++) for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) {
			int c = domain.cellIndex(x, y, z);
			if (x < 2) {
				check(domain.projection.pressureFluid[c] && domain.projection.pressureVolume[c] == 1,
						"filled closed component acquired an internal free surface");
				left += domain.projection.pressureVolume[c];
			} else if (x == 3) right += domain.projection.pressureVolume[c];
		}
		check(left == 16 && Math.abs(right - 2) < 1e-12, "pressure reconstruction transferred volume across a solid component");
		check(!domain.projection.pressureFluid[domain.cellIndex(3, 3, 1)], "unfilled neighbour component was classified full");

		// A sub-double-ULP deficit must survive the fast reduction screen.
		var full = new FluidDomain(4, 4, 2, FluidSolverConfig.prototypeDefaults(), 512);
		for (int z = 0; z < 2; z++) for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) full.setVolume(x, y, z, 1);
		var state = full.exportParticleState(); int count = state.particleCount() + 1;
		float[] positions = java.util.Arrays.copyOf(state.copyPositionVolume(), count * 4);
		for (int axis = 0; axis < 3; axis++) positions[4 * (count - 1) + axis] = 0.375F;
		positions[3] = Math.nextDown(positions[3]);
		positions[4 * (count - 1) + 3] = Math.nextDown(Math.ulp(0.125F) / 2);
		full.restoreParticleState(new FlipParticleState(4, 4, 2, full.config.cellSize(), count, 32, positions, new float[count * 3], new byte[32]));
		for (int p = 0; p < count; p++) {
			full.particles.setPositionX(p, 0.375F); full.particles.setPositionY(p, 0.375F); full.particles.setPositionZ(p, 0.375F);
		}
		full.ensureInventoryGrid();
		check(!full.projection.pressureFluid[full.cellIndex(3, 3, 1)], "rounded volume equality erased a real capacity deficit");
	}

	private static void partialWallsKeepComponentFootprintsSeparate() {
		var domain = new FluidDomain(2, 2, 2, FluidSolverConfig.prototypeDefaults(), 128);
		float[] positions = new float[28 * 4]; byte[] masks = new byte[8]; int ordinal = 0;
		for (int z = 0; z < 2; z++) for (int y = 0; y < 2; y++) {
			masks[domain.cellIndex(0, y, z)] = (byte) 0x88;
			masks[domain.cellIndex(1, y, z)] = (byte) 0x11;
			for (int micro = 0; micro < 8; micro++) if ((0x88 & 1 << micro) == 0) {
				positions[ordinal++] = 0.25F + 0.5F * (micro & 1);
				positions[ordinal++] = y + 0.25F + 0.5F * (micro >> 1 & 1);
				positions[ordinal++] = z + 0.25F + 0.5F * (micro >> 2 & 1);
				positions[ordinal++] = 0.125F;
			}
			positions[ordinal++] = 1.75F; positions[ordinal++] = y + 0.25F;
			positions[ordinal++] = z + 0.25F; positions[ordinal++] = 0.125F;
		}
		// Move an admissible point next to the completely closed shared face.
		positions[4] = 0.98F;
		domain.restoreParticleState(new FlipParticleState(2, 2, 2, 1, 28, 3.5F, positions, new float[28 * 3], masks));
		domain.ensureGrid(); double left = 0, right = 0;
		for (int z = 0; z < 2; z++) for (int y = 0; y < 2; y++) {
			int a = domain.cellIndex(0, y, z), b = domain.cellIndex(1, y, z);
			check(domain.projection.pressureFluid[a] && domain.projection.pressureVolume[a] == 0.75,
					"filled partial-cell component lost its exact capacity");
			left += domain.projection.pressureVolume[a]; right += domain.projection.pressureVolume[b];
		}
		check(left == 3 && Math.abs(right - 0.5) < 1e-12, "pressure footprint crossed a closed partial face");
	}

	private static void divergenceConformingAdvectionMatchesMacDivergence() {
		int nx = 4, ny = 4, nz = 4;
		float h = 0.5F;
		MacVelocityGrid mac = new MacVelocityGrid(nx, ny, nz);
		FlipCutCellGeometry cutCells = new FlipCutCellGeometry(nx, ny, nz);
		for (int i = 0; i < mac.velocityX.length; i++) mac.velocityX[i] = (i * 17 % 23 - 11) * 0.03125F;
		for (int i = 0; i < mac.velocityY.length; i++) mac.velocityY[i] = (i * 13 % 19 - 9) * 0.02734375F;
		for (int i = 0; i < mac.velocityZ.length; i++) mac.velocityZ[i] = (i * 11 % 17 - 8) * 0.03515625F;
		int x = 1, y = 1, z = 1;
		float expected = (mac.velocityX[(x + 1) + (nx + 1) * (y + ny * z)]
				- mac.velocityX[x + (nx + 1) * (y + ny * z)]
				+ mac.velocityY[x + nx * ((y + 1) + (ny + 1) * z)]
				- mac.velocityY[x + nx * (y + (ny + 1) * z)]
				+ mac.velocityZ[x + nx * (y + ny * (z + 1))]
				- mac.velocityZ[x + nx * (y + ny * z)]) / h;
		float px = (x + 0.37F) * h, py = (y + 0.43F) * h, pz = (z + 0.61F) * h;
		float epsilon = h / 1_024.0F;
		DivergenceConformingVelocityField.Sample negative = new DivergenceConformingVelocityField.Sample();
		DivergenceConformingVelocityField.Sample positive = new DivergenceConformingVelocityField.Sample();
		DivergenceConformingVelocityField.sample(mac, cutCells, nx, ny, nz, h, px - epsilon, py, pz, negative);
		DivergenceConformingVelocityField.sample(mac, cutCells, nx, ny, nz, h, px + epsilon, py, pz, positive);
		float reconstructed = (positive.x - negative.x) / (2.0F * epsilon);
		DivergenceConformingVelocityField.sample(mac, cutCells, nx, ny, nz, h, px, py - epsilon, pz, negative);
		DivergenceConformingVelocityField.sample(mac, cutCells, nx, ny, nz, h, px, py + epsilon, pz, positive);
		reconstructed += (positive.y - negative.y) / (2.0F * epsilon);
		DivergenceConformingVelocityField.sample(mac, cutCells, nx, ny, nz, h, px, py, pz - epsilon, negative);
		DivergenceConformingVelocityField.sample(mac, cutCells, nx, ny, nz, h, px, py, pz + epsilon, positive);
		reconstructed += (positive.z - negative.z) / (2.0F * epsilon);
		check(Math.abs(reconstructed - expected) < 2.0E-4F,
				"H(div) reconstruction changed MAC divergence: expected=" + expected + ", actual=" + reconstructed);

	}

	private static void closedWallProfilesRetainZeroNormalVelocity() {
		for (int axis = 0; axis < 3; axis++) {
			int tangent = (axis + 1) % 3, third = (axis + 2) % 3;
			var domain = new FluidDomain(4, 4, 4, FluidSolverConfig.prototypeDefaults());
			int[] cell = new int[3]; cell[axis] = 2; cell[tangent] = 1;
			for (int k = 0; k < 4; k++) { cell[third] = k; domain.setSolid(cell[0], cell[1], cell[2], true); }
			// A discrete curl around one grid vertex has zero divergence and zero
			// average normal velocity on every solid face.
			float[] normal = axis == 0 ? domain.mac.velocityX : axis == 1 ? domain.mac.velocityY : domain.mac.velocityZ;
			float[] tangential = tangent == 0 ? domain.mac.velocityX : tangent == 1 ? domain.mac.velocityY : domain.mac.velocityZ;
			for (int k = 0; k < 4; k++) {
				cell[third] = k; cell[axis] = 2; cell[tangent] = 2; normal[face(domain, axis, cell)] = 1;
				cell[tangent] = 3; normal[face(domain, axis, cell)] = -1;
				cell[axis] = 1; tangential[face(domain, tangent, cell)] = -1;
				cell[axis] = 2; tangential[face(domain, tangent, cell)] = 1;
			}
			float[] point = new float[3]; point[axis] = 2; point[tangent] = 1.75F; point[third] = 1.5F;
			var sample = new DivergenceConformingVelocityField.Sample();
			DivergenceConformingVelocityField.sample(domain.mac, domain.geometry.cutCells, 4, 4, 4, 1,
					point[0], point[1], point[2], sample);
			float normalVelocity = axis == 0 ? sample.x : axis == 1 ? sample.y : sample.z;
			check(normalVelocity == 0, "closed wall profile leaked normal velocity on axis " + axis + ": " + normalVelocity);
			point[axis] = 1.37F; point[tangent] = 2.43F;
			float divergence = 0, epsilon = 1.0F / 2048;
			for (int component = 0; component < 3; component++) {
				point[component] -= epsilon;
				DivergenceConformingVelocityField.sample(domain.mac, domain.geometry.cutCells, 4, 4, 4, 1,
						point[0], point[1], point[2], sample);
				float negative = component == 0 ? sample.x : component == 1 ? sample.y : sample.z;
				point[component] += 2 * epsilon;
				DivergenceConformingVelocityField.sample(domain.mac, domain.geometry.cutCells, 4, 4, 4, 1,
						point[0], point[1], point[2], sample);
				float positive = component == 0 ? sample.x : component == 1 ? sample.y : sample.z;
				point[component] -= epsilon;
				divergence += (positive - negative) / (2 * epsilon);
			}
			check(Math.abs(divergence) < 4e-4F, "solid wall reconstruction changed MAC divergence: " + divergence);
		}
	}

	private static int face(FluidDomain domain, int axis, int[] cell) {
		return switch (axis) {
			case 0 -> domain.xFaceIndex(cell[0], cell[1], cell[2]);
			case 1 -> domain.yFaceIndex(cell[0], cell[1], cell[2]);
			default -> domain.zFaceIndex(cell[0], cell[1], cell[2]);
		};
	}

	private static void energyStableFlipTransferUsesTheLargestAdmissibleIncrement() {
		var random = new java.util.Random(0x5a17);
		float[] field = new float[60], previous = new float[60], pair = new float[2]; boolean[] valid = new boolean[60];
		for (int sample = 0; sample < 512; sample++) {
			for (int f = 0; f < field.length; f++) {
				field[f] = random.nextFloat() * 20 - 10; previous[f] = random.nextFloat() * 20 - 10;
				valid[f] = random.nextBoolean();
			}
			float x = random.nextFloat() * 7 - 1, y = random.nextFloat() * 5 - 1, z = random.nextFloat() * 6 - 1;
			FluidDomainMechanicalFields.trilinearValidPair(field, previous, valid, 5, 3, 4, x, y, z, pair);
			check(Float.floatToRawIntBits(pair[0]) == Float.floatToRawIntBits(FluidDomainMechanicalFields.trilinearValid(field, valid, 5, 3, 4, x, y, z))
					&& Float.floatToRawIntBits(pair[1]) == Float.floatToRawIntBits(FluidDomainMechanicalFields.trilinearValid(previous, valid, 5, 3, 4, x, y, z)),
					"paired velocity interpolation changed scalar float reduction");
		}

		double transfer = FlipTransferScheme.maximumEnergyStableTransfer(4.0, -2.0, 3.0, 3.0);
		check(Math.abs(transfer - 0.5) < 1.0E-12,
				"energy-stable G2P did not select the largest admissible FLIP increment: " + transfer);
		double resultingEnergy = 4.0 * transfer * transfer - 2.0 * transfer + 3.0;
		check(resultingEnergy <= 3.0 && Math.abs(resultingEnergy - 3.0) < 1.0E-12,
				"energy-stable G2P exceeded its projected-grid energy budget");
		check(FlipTransferScheme.maximumEnergyStableTransfer(1.0, -2.0, 1.0, 1.0) == 1.0,
				"an energetically admissible full FLIP increment was damped");
		for (int exponent : new int[] {-1000, -600, 0, 600, 1000}) {
			double energyScale = Math.scalb(1.0, exponent);
			check(Math.abs(FlipTransferScheme.maximumEnergyStableTransfer(
					4 * energyScale, -2 * energyScale, 3 * energyScale, 3 * energyScale) - 0.5) <= Math.ulp(0.5),
					"common energy scaling changed the admissible FLIP increment at exponent " + exponent);
			check(Math.abs(FlipTransferScheme.maximumEnergyStableTransfer(
					4 * energyScale, 0, 0, energyScale) - 0.5) <= Math.ulp(0.5),
					"zero-linear energy root lost its scale at exponent " + exponent);
		}
		double cancellationRoot = FlipTransferScheme.maximumEnergyStableTransfer(0.25, 1, 1, Math.nextUp(1.0));
		check(cancellationRoot > 0 && Math.abs(cancellationRoot / Math.ulp(1.0) - 1) < 8 * Math.ulp(1.0),
				"positive-linear energy root cancelled a representable admissible increment");
		check(Math.abs(FlipTransferScheme.maximumEnergyStableTransfer(1e200, 0, 0, 1e-200) / 1e-200 - 1) < 8 * Math.ulp(1.0),
				"square-root energy ratio underflowed before producing a representable increment");
	}

	private static FluidDomain pressureGeometryDomain() {
		return new FluidDomain(3,3,3,new FluidSolverConfig(1,1000,0,.001F,400,1e-5F,.45F,.002F,.8F),256);
	}
	private static void geometryClose(double actual,double expected,String name) {
		check(Double.isFinite(actual) && Math.abs(actual-expected)<=256*Math.ulp(Math.max(1,Math.abs(expected))),name+": "+actual+" != "+expected);
	}
	static void liquidPressureGeometryConservesShapeMoments() {
		microFaceAlignedInterfacesPreserveTraction();
		for (float h : new float[]{1, .3F}) for (int axis = 0; axis < 3; axis++) {
			var full = new FluidDomain(3, 3, 3, new FluidSolverConfig(h, 1000, 0, .05F, 100, 1e-6F, .45F, .002F, 0));
			int[] peer = {1, 1, 1}; peer[axis] = 2;
			full.setVolume(1, 1, 1, 1); full.setVolume(peer[0], peer[1], peer[2], 1); full.ensureGrid();
			var captured = LiquidPressureGeometry.capture(full); int minus = full.cellIndex(1, 1, 1), plus = full.cellIndex(peer[0], peer[1], peer[2]);
			int mask = 0, pieces = 0; LiquidPressureGeometry.SurfaceMoments first = null;
			for (var face : captured.sharedFaces()) if (face.minusCell() == minus && face.plusCell() == plus && face.axis() == axis) {
				mask |= 1 << face.microKey(); pieces++; if (first == null) first = face.moments();
			}
			check(mask == 15 && pieces == 4, "Whole physical wet face lacks its four distinct microfaces");
			var face = captured.faceMoments(minus, plus, axis);
			var scale = new java.math.BigDecimal((double) h); var area = scale.multiply(scale);
			double variance = area.multiply(area).divide(java.math.BigDecimal.valueOf(12), java.math.MathContext.DECIMAL128).doubleValue();
			check(face.area() == area.doubleValue(), "Whole wet face area differs from its exact SI rectangle");
			for (int a = 0; a < 3; a++) {
				double center = scale.multiply(java.math.BigDecimal.valueOf(a == axis ? 2 : 1.5)).doubleValue();
				check(face.centroid().component(a) == center, "Whole wet face centroid differs from its exact SI rectangle");
				for (int b = a; b < 3; b++) check(face.centralSecondMoment().component(a, b) == (a == b && a != axis ? variance : 0),
						"Whole wet face covariance differs from its exact SI rectangle");
			}
			check(first.add(first).fullFace() == null, "Overlapping microfaces retained a complete-face certificate");
			var triangle = LiquidPressureMoments.integrate(new double[][]{{0, .1, 0}, {0, 0, .2}}, axis, 1, minus, full);
			check(face.add(triangle).fullFace() == null, "A partial polygon retained a complete-face certificate");
		}
		var cube=pressureGeometryDomain();cube.setVolume(1,1,1,1);cube.setVolume(2,1,1,1);cube.ensureGrid();
		var geometry=LiquidPressureGeometry.capture(cube);int cell=cube.cellIndex(1,1,1);
		var whole=geometry.cellSnapshot(cell);geometryClose(whole.volume(),1,"Whole cube volume");
		for(int axis=0;axis<3;axis++)geometryClose(whole.centroid().component(axis),1.5,"Whole cube centroid");
		var shared=geometry.faceMoments(cell,cube.cellIndex(2,1,1),0);
		geometryClose(shared.area(),1,"Shared cube face");geometryClose(shared.centroid().x(),2,"Shared face coordinate");
		geometryClose(shared.centroid().y(),1.5,"Shared face tangent centroid");
		geometryClose(shared.centeredSecondMoment(1,1,shared.centroid()),1.0/12,"Shared face tangent variance");
		geometryClose(shared.centeredSecondMoment(1,2,shared.centroid()),0,"Shared face tangent cross moment");
		verifyPressureShapeGauss(whole);
		var slab=pressureGeometryDomain();float fill=.6F;
		int count=9*2*8,ordinal=0;float[] positions=new float[4*count];
		for(int z=0;z<3;z++)for(int y=0;y<2;y++)for(int x=0;x<3;x++)for(int slot=0;slot<8;slot++) {
			float height=y==0?1:fill;int base=4*ordinal++;
			positions[base]=x+.25F+.5F*(slot&1);positions[base+1]=y+height*(.25F+.5F*(slot>>1&1));
			positions[base+2]=z+.25F+.5F*(slot>>2&1);positions[base+3]=height/8;
		}
		slab.restoreParticleState(new FlipParticleState(3,3,3,1,count,(float)(9*(1+(double)fill)),positions,new float[3*count],new byte[27]));
		slab.ensureGrid();var slabs=LiquidPressureGeometry.capture(slab);var partial=slabs.cellSnapshot(cell);
		geometryClose(partial.volume(),fill,"Slab admitted volume");geometryClose(partial.centroid().y(),1+.5*fill,"Slab centroid");
		var wet=slabs.faceMoments(cell,slab.cellIndex(2,1,1),0);
		geometryClose(wet.area(),fill,"Shared slab wet face");geometryClose(wet.centroid().y(),1+.5*fill,"Shared slab centroid");
		geometryClose(wet.centeredSecondMoment(1,1,wet.centroid()),fill*(double)fill*fill/12,"Slab wet second moment");
		var normal=LiquidPressureGeometry.Vec3.ZERO;
		for(var fragment:partial.freeFragments())normal=normal.add(fragment.areaNormal());
		geometryClose(normal.x(),0,"Slab free normal X");geometryClose(normal.y(),1,"Slab free normal Y");geometryClose(normal.z(),0,"Slab free normal Z");
		verifyPressureShapeGauss(partial);
		var cut=pressureGeometryDomain();cut.setSolidMicroMask(1,1,1,1);cut.setVolume(1,1,1,.875F);cut.ensureGrid();
		var cutShape=LiquidPressureGeometry.capture(cut).cellSnapshot(cell);geometryClose(cutShape.volume(),.875,"Cut cube volume");
		for(int axis=0;axis<3;axis++)geometryClose(cutShape.centroid().component(axis),1+15.0/28,"Cut cube centroid");
		long walls=cutShape.facets().stream().filter(f->f.kind()==LiquidPressureGeometry.FacetKind.WALL && f.peerCell()==cell).count();
		check(walls==3,"Cut cube omitted internal solid microfacets");verifyPressureShapeGauss(cutShape);
		var slanted=pressureGeometryDomain();slanted.setVolume(1,1,1,5F/6);slanted.setVolume(0,1,1,1);
		slanted.setVolume(1,0,1,1);slanted.setVolume(1,1,0,1);
		for(int p=0;p<slanted.particles.count();p++)if(slanted.particleCellIndex(p)==cell) {
			slanted.particles.setPositionX(p,1.45F);slanted.particles.setPositionY(p,1.45F);slanted.particles.setPositionZ(p,1.45F);
		}
		slanted.rebuildGridFromParticles();
		var plane=slanted.pressurePhaseGeometry;double nx=plane.planeNormal(cell,0);
		check(nx>0 && nx==plane.planeNormal(cell,1) && nx==plane.planeNormal(cell,2),"Slanted analytic plane changed symmetry");
		double missingLength=3-plane.planeOffset(cell)/nx,missingVolume=missingLength*missingLength*missingLength/6;
		var tetra=LiquidPressureGeometry.capture(slanted).cellSnapshot(cell);
		geometryClose(tetra.volume(),1-missingVolume,"Slanted positive profile volume");
		for(int axis=0;axis<3;axis++)geometryClose(tetra.firstMoment().component(axis),1-missingVolume+.5-missingVolume*(1-missingLength/4),"Slanted profile first moment");
		verifyPressureShapeGauss(tetra);
		double[] freeArea=new double[3];double[][] freeFirst=new double[3][3];
		for(var fragment:tetra.freeFragments())for(int a=0;a<3;a++) {
			freeArea[a]+=fragment.areaNormal().component(a);
			for(int b=0;b<3;b++)freeFirst[a][b]+=fragment.normalFirstMoment().row(a).component(b);
		}
		for(int a=0;a<3;a++){geometryClose(freeArea[a],missingLength*missingLength/2,"Slanted free oriented area");
			for(int b=0;b<3;b++)geometryClose(freeFirst[a][b],missingLength*missingLength/2*(2-missingLength/3),"Slanted free normal first moment");}
		// A narrow triangle's central Gram must survive subtraction of much larger raw world moments.
		double delta=Math.scalb(1.0,-24);
		var narrow=LiquidPressureMoments.integrate(new double[][]{{.5,.5+delta,.5},{.5,.5,.5+delta}},0,1,cell,cube);
		double expected=narrow.area()*delta*delta/18;
		check(Math.abs(narrow.centeredSecondMoment(1,1,narrow.centroid())-expected)<=64*Math.ulp(expected),"Narrow wet trace lost its positive second moment");
		for(int axis=0;axis<3;axis++) {
			var polygon=new double[][]{{0,.2893649457745707,0},{0,0,.14825979318469462}};
			var face=LiquidPressureMoments.integrate(polygon,axis,1,cell,cube);
			var thin=LiquidPressureMoments.integrate(new double[][]{{.5,.5+delta,.5},{.5,.5,.5+delta}},axis,1,cell,cube);
			for(var piece:java.util.List.of(face,thin,face.add(thin))) {
				check(piece.centroid().component(axis)==2,"Planar face centroid left its constant normal coordinate");
				for(int other=0;other<3;other++) check(piece.centralSecondMoment().component(axis,other)==0,
						"Planar face introduced an independent normal direction in its tangent trace");
			}
		}
	}
	private static void microFaceAlignedInterfacesPreserveTraction() {
		for(int axis=0;axis<3;axis++)for(int sign:new int[]{-1,1}) {
			var d=pressureGeometryDomain();int count=9*2*8,marker=0;float[] positions=new float[4*count];
			for(int other=0;other<3;other++)for(int tangent=0;tangent<3;tangent++)for(int layer=0;layer<2;layer++)for(int slot=0;slot<8;slot++) {
				int coordinate=layer==0?(sign>0?0:2):1;float height=layer==0?1:.5F;int base=4*marker++;
				positions[base+axis]=coordinate+(sign<0&&layer==1?.5F:0)+height*(.25F+.5F*(slot>>axis&1));
				positions[base+(axis+1)%3]=tangent+.25F+.5F*(slot>>(axis+1)%3&1);
				positions[base+(axis+2)%3]=other+.25F+.5F*(slot>>(axis+2)%3&1);positions[base+3]=height/8;
			}
			d.restoreParticleState(new FlipParticleState(3,3,3,1,count,13.5F,positions,new float[3*count],new byte[27]));
			d.ensureGrid();int cell=d.cellIndex(1,1,1);var shape=LiquidPressureGeometry.capture(d).cellSnapshot(cell);
			geometryClose(d.pressurePhaseGeometry.planeNormal(cell,axis),sign,"Microface-aligned physical normal");
			geometryClose(d.pressurePhaseGeometry.planeOffset(cell),sign*.5,"Microface-aligned physical offset");
			check(shape.freeFragments().size()==4,"Microface-aligned liquid interface must have four unique wet-side pieces");
			var gas=GasComplementGeometry.capture(3,3,3,1,d.geometry.solid,d.geometry.cutCells,d.pressurePhaseGeometry);
			double area=0;double[] first=new double[3];
			for(var fragment:shape.freeFragments()) {
				check(gas.piece(8*cell+fragment.gasMicro())!=null && gas.piece(8*cell+fragment.microKey())==null,
						"Aligned liquid interface attributed gas pressure to its full-wet integration microbox");
				area+=fragment.areaNormal().component(axis);
				for(int b=0;b<3;b++)first[b]+=fragment.normalFirstMoment().row(axis).component(b);
			}
			geometryClose(area,sign,"Aligned free interface oriented area");
			for(int b=0;b<3;b++)geometryClose(first[b],sign*1.5,"Aligned free interface normal first moment");
			geometryClose(-10*area,-10*sign,"Ten-pascal interface traction");
			geometryClose(-area,-sign,"Finite-gas volume rate for uniform normal velocity");
			verifyPressureShapeGauss(shape);
		}
	}
	private static void verifyPressureShapeGauss(LiquidPressureGeometry.CellSnapshot shape) {
		double[] normal=new double[3];double[][] first=new double[3][3];
		for(var facet:shape.facets()) {
			normal[facet.axis()]+=facet.sign()*facet.moments().area();
			for(int b=0;b<3;b++)first[facet.axis()][b]+=facet.sign()*facet.moments().firstMoment().component(b);
		}
		for(var side:shape.sideWet()) {
			// Shared liquid contacts are absent from the external-facet list; add just that remainder.
			double area=side.moments().area();var moment=side.moments().firstMoment();
			for(var facet:shape.facets())if(facet.microKey()==side.microKey()){area-=facet.moments().area();moment=moment.add(facet.moments().firstMoment().scale(-1));}
			normal[side.axis()]+=side.sign()*area;
			for(int b=0;b<3;b++)first[side.axis()][b]+=side.sign()*moment.component(b);
		}
		for(var free:shape.freeFragments())for(int a=0;a<3;a++) {
			normal[a]+=free.areaNormal().component(a);
			for(int b=0;b<3;b++)first[a][b]+=free.normalFirstMoment().row(a).component(b);
		}
		for(int a=0;a<3;a++){geometryClose(normal[a],0,"Closed shape oriented area");for(int b=0;b<3;b++)geometryClose(first[a][b],a==b?shape.volume():0,"Closed shape normal first moment");}
	}

	private static void fractionalHydrostaticColumnsBalanceGravity() {
		for (float surfaceFraction : new float[] {0.60F, 0.75F, 1.0F}) {
			FluidSolverConfig config = strictConfig();
			FluidDomain domain = new FluidDomain(2, 5, 2, config);
			// Integrate the actual horizontal slabs, including the partial cell's spatial moment.
			int count = 2 * 3 * 2 * 8, particle = 0;
			float[] positions = new float[count * 4];
			for (int z = 0; z < 2; z++) for (int y = 0; y < 3; y++) for (int x = 0; x < 2; x++) {
				float fill = y == 2 ? surfaceFraction : 1;
				for (int slot = 0; slot < 8; slot++) {
					int base = 4 * particle++;
					positions[base] = (x + .25F + .5F * (slot & 1)) * config.cellSize();
					positions[base + 1] = (y + fill * (.25F + .5F * (slot >>> 1 & 1))) * config.cellSize();
					positions[base + 2] = (z + .25F + .5F * (slot >>> 2 & 1)) * config.cellSize();
					positions[base + 3] = fill / 8;
				}
			}
			domain.restoreParticleState(new FlipParticleState(2, 5, 2, config.cellSize(), count,
					4 * (2 + surfaceFraction), positions, new float[count * 3], new byte[20]));
			float exactVolume = domain.totalVolume();
			for (int step = 0; step < 200; step++) domain.step();
			float theta = surfaceFraction >= 1.0F - 1.0E-6F ? 0.5F : surfaceFraction - 0.5F;
			for (int y = 0; y <= 2; y++) {
				float expected = config.density() * config.gravity() * config.cellSize() * (2 - y + theta);
				float actual = domain.pressure(0, y, 0);
				float relativeError = Math.abs(actual - expected) / Math.max(1.0F, expected);
				check(relativeError < 2.0E-4F,
						"fractional hydrostatic pressure missed the analytic column: fraction=" + surfaceFraction
								+ ", y=" + y + ", expected=" + expected + ", actual=" + actual);
			}
			check(domain.maximumParticleSpeed() < 2.0E-4F,
					"fractional hydrostatic projection left parasitic marker velocity: " + domain.maximumParticleSpeed());
			check(Math.abs(domain.totalVolume() - exactVolume) < 1.0E-6F,
					"fractional pressure changed the exact marker ledger");
		}
	}

	private static void pressureNonConvergenceRejectsStep() {
		FluidSolverConfig config = new FluidSolverConfig(
				1.0F, 1_000.0F, 9.81F, 1.0F / 20.0F,
				1, 1.0E-12F, 0.45F, 2.0E-3F, 0.80F);
		FluidDomain domain = new FluidDomain(4, 6, 4, config);
		for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++) {
			domain.setVolume(x, 0, z, 1.0F);
			domain.setVolume(x, 1, z, 0.75F);
		}
		FlipParticleState before = domain.exportParticleState();
		try {
			domain.step();
			throw new AssertionError("unconverged pressure candidate was accepted");
		} catch (FluidDomain.PressureSolveBlockedException expected) {
			check(java.util.Arrays.equals(before.copyPositionVolume(),
						domain.exportParticleState().copyPositionVolume()),
					"rejected pressure candidate changed marker position/volume truth");
			check(java.util.Arrays.equals(before.copyParticleVelocities(),
						domain.exportParticleState().copyParticleVelocities()),
					"rejected pressure candidate changed marker momentum truth");
		}
	}

	private static void thinLayerRegularizationRemainsStable() {
		FluidSolverConfig config = strictConfig();
		FluidDomain domain = new FluidDomain(3, 3, 3, config);
		for (int z = 0; z < 3; z++) for (int x = 0; x < 3; x++) domain.setVolume(x, 0, z, 0.25F);
		float exactVolume = domain.totalVolume();
		for (int step = 0; step < 40; step++) {
			FluidDomain.StepStats stats = domain.step();
			check(Float.isFinite(stats.pressureResidual()), "thin-layer pressure residual became non-finite");
			check(stats.relativeVolumeError() < 1.0E-6F, "thin-layer step changed marker volume");
		}
		check(Math.abs(domain.totalVolume() - exactVolume) < 1.0E-6F, "thin layer lost exact marker volume");
		check(domain.maximumParticleSpeed() < 2.0E-3F,
				"theta regularization did not keep a sub-center thin layer stable: " + domain.maximumParticleSpeed());
	}

	private static void isolatedDropletReceivesUndilutedGravity() {
		isolatedDropletReceivesUndilutedGravity(false);
		isolatedDropletReceivesUndilutedGravity(true);
	}

	private static void isolatedDropletReceivesUndilutedGravity(boolean withPool) {
		float timeStep = 1.0F / 20.0F;
		FluidSolverConfig config = new FluidSolverConfig(
				0.5F, 1_000.0F, 9.81F, timeStep,
				200, 1.0E-5F, 0.45F, 2.0E-3F, 0.80F
		);
		FluidDomain domain = new FluidDomain(8, 32, 8, config);
		if (withPool) for (int z = 0; z < 8; z++) for (int y = 0; y < 3; y++) for (int x = 0; x < 8; x++) domain.setVolume(x, y, z, 1);
		domain.setVolume(4, 24, 4, 0.125F);
		float exactVolume = domain.totalVolume();
		float initialCenterY = dropletCenterAbovePool(domain);
		int steps = 8;
		for (int step = 0; step < steps; step++) domain.step();

		float expectedFall = config.gravity() * timeStep * timeStep * steps * (steps + 1) * 0.5F;
		float actualFall = initialCenterY - dropletCenterAbovePool(domain);
		check(Math.abs(actualFall - expectedFall) < 2.0E-5F,
				"isolated droplet gravity was diluted by undefined air faces: expected fall="
						+ expectedFall + ", actual=" + actualFall);
		check(Math.abs(domain.maximumParticleSpeed() - config.gravity() * timeStep * steps) < 2.0E-5F,
				"isolated droplet velocity did not accumulate gravity without damping: "
						+ domain.maximumParticleSpeed());
		check(Math.abs(domain.totalVolume() - exactVolume) < 1.0E-7F,
				"isolated droplet changed the exact marker ledger");
	}

	private static float dropletCenterAbovePool(FluidDomain domain) {
		double weighted = 0, volume = 0;
		for (int p = 0; p < domain.particleCount(); p++) if (domain.particles.positionY(p) > 6 * domain.config.cellSize()) {
			weighted += domain.particles.positionY(p) * domain.particles.volume(p); volume += domain.particles.volume(p);
		}
		check(volume > 0, "isolated droplet disappeared above its pool");
		return (float) (weighted / volume);
	}

	private static void longRunHydrostaticSurfaceDoesNotSink() {
		FluidSolverConfig config = strictConfig();
		FluidDomain domain = new FluidDomain(4, 8, 4, config, 4 * 8 * 4 * 8);
		for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++) domain.setSolid(x, 0, z, true);
		for (int z = 0; z < 4; z++) for (int y = 1; y <= 4; y++) for (int x = 0; x < 4; x++) {
			domain.setVolume(x, y, z, 1.0F);
		}
		FlipParticleState initial = domain.exportParticleState();
		float initialCenterY = centerOfMassY(initial);
		FluidDomain.VolumeDistribution initialDistribution = domain.volumeDistribution(8.0F / 255.0F);
		for (int step = 0; step < 5_000; step++) domain.step();

		FlipParticleState result = domain.exportParticleState();
		double[] layers = exactLayerVolumes(result);
		check(result.particleCount() == initial.particleCount(), "hydrostatic marker count drifted over 5,000 steps");
		check(Math.abs(centerOfMassY(result) - initialCenterY) < 5.0E-4F,
				"hydrostatic physical surface sank over 5,000 steps");
		for (int y = 1; y <= 4; y++) {
			check(Math.abs(layers[y] - 16.0) < 1.0E-7,
					"hydrostatic layer changed exact marker volume at y=" + y + ": " + layers[y]);
		}
		FluidDomain.VolumeDistribution resultDistribution = domain.volumeDistribution(8.0F / 255.0F);
		check(resultDistribution.equals(initialDistribution),
				"hydrostatic reconstructed surface changed over 5,000 steps: before="
						+ initialDistribution + ", after=" + resultDistribution);
		check(domain.maximumParticleSpeed() < 1.0E-4F,
				"hydrostatic drift correction created persistent marker motion: " + domain.maximumParticleSpeed());
	}

	private static void waterDescendsAStairSlope() {
		FluidSolverConfig config = new FluidSolverConfig(
				1.0F, 1_000.0F, 9.81F, 1.0F / 40.0F,
				200, 1.0E-5F, 0.35F, 2.0E-3F, 0.80F
		);
		FluidDomain domain = new FluidDomain(12, 8, 4, config);
		for (int z = 0; z < 4; z++) for (int x = 0; x < 12; x++) {
			int topSolid = x < 2 ? 3 : x < 4 ? 2 : 1;
			for (int y = 0; y <= topSolid; y++) domain.setSolid(x, y, z, true);
		}
		for (int z = 0; z < 4; z++) for (int x = 0; x < 4; x++) {
			int firstFluid = x < 2 ? 4 : 3;
			for (int y = firstFluid; y <= 6; y++) domain.setVolume(x, y, z, 1.0F);
		}
		float exactVolume = domain.totalVolume();
		float initialCenter = domain.centerOfMassX();
		CpuFluidSolverBackend backend = new CpuFluidSolverBackend();
		for (int step = 0; step < 300; step++) {
			var candidate = backend.evaluate(new FluidStepRequest(step, domain.captureAuthoritativeState(),
					java.util.List.of(), false, 0));
			FluidDomain.StepStats stats = candidate.stats();
			domain = candidate.claimPreparedCpuDomain();
			check(Float.isFinite(stats.pressureResidual()), "stair-slope pressure residual became non-finite");
			check(stats.relativeVolumeError() < 2.0E-6F, "stair-slope flow changed marker volume");
		}
		check(domain.centerOfMassX() > initialCenter + 1.0F,
				"water did not descend the fixed stair slope: before=" + initialCenter + ", after=" + domain.centerOfMassX());
		check(Math.abs(domain.totalVolume() - exactVolume) < 1.0E-4F,
				"stair-slope flow changed the exact particle ledger");
		domain.exportParticleState();
	}

	private static FluidSolverConfig strictConfig() {
		return new FluidSolverConfig(
				1.0F, 1_000.0F, 9.81F, 1.0F / 40.0F,
				300, 1.0E-7F, 0.40F, 2.0E-3F, 0.80F
		);
	}

	private static int index(int sizeX, int sizeY, int x, int y, int z) {
		return (z * sizeY + y) * sizeX + x;
	}

	private static float centerOfMassY(FlipParticleState state) {
		float[] positionVolume = state.copyPositionVolume();
		double weightedY = 0.0, volume = 0.0;
		for (int offset = 0; offset < positionVolume.length; offset += 4) {
			weightedY += positionVolume[offset + 1] * positionVolume[offset + 3];
			volume += positionVolume[offset + 3];
		}
		return (float) (weightedY / volume);
	}

	private static double[] exactLayerVolumes(FlipParticleState state) {
		double[] layers = new double[state.sizeY()];
		float[] positionVolume = state.copyPositionVolume();
		for (int offset = 0; offset < positionVolume.length; offset += 4) {
			int y = Math.min(state.sizeY() - 1,
					Math.max(0, (int) Math.floor(positionVolume[offset + 1] / state.cellSize())));
			layers[y] += positionVolume[offset + 3];
		}
		return layers;
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
