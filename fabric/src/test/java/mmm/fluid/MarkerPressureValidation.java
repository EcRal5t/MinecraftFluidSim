package mmm.fluid;

import java.util.Arrays;

/** Actual marker inertia, independent cell-flux assembly, pressure work and finite gas storage. */
public final class MarkerPressureValidation {
	private record Layout(FluidDomain domain, MarkerVelocityTransfer transfer, MarkerPressureOperator operator,
	                      int[] cells) { }
	private MarkerPressureValidation() { }
	public static void main(String[] args) {
		if (args.length == 1 && args[0].equals("--micro-velocity-enrichment")) {
			MicroFaceVelocityEnrichmentExperiment.validate();
		} else if (args.length == 1 && args[0].equals("--micro-velocity-support")) {
			MicroFaceVelocitySpaceExperiment.validateSupport();
		} else if (args.length == 1 && args[0].equals("--micro-velocity-candidate")) {
			MicroFaceVelocitySpaceExperiment.validate();
		} else if (args.length == 1 && args[0].equals("--velocity-space")) {
			MarkerVelocitySpaceExperiment.validateCandidate();
			MarkerVelocitySpaceExperiment.validateProduction();
		} else if (args.length == 1 && args[0].equals("--velocity-candidate")) {
			MarkerVelocitySpaceExperiment.validateCandidate();
		} else if (args.length == 1 && args[0].equals("--gas-geometry")) {
			pressureUsesCurrentMicroGasGeometry();
			System.out.println("MarkerPressureValidation PASS (current micro gas geometry)");
		} else if (args.length == 1 && args[0].equals("--kernel")) {
			validateKernel();
			System.out.println("MarkerPressureValidation PASS (kernel; production capillary/wall transport skipped)");
		} else {
			if (args.length != 0) throw new IllegalArgumentException("Unknown marker pressure validation entry");
			validate();
			System.out.println("MarkerPressureValidation PASS");
		}
	}
	public static void validate() {
		validateKernel();
		MarkerVelocitySpaceExperiment.validateProduction();
		pressureUsesCurrentMicroGasGeometry();
		unresolvedCapillaryTractionIsAppliedOnce();
		transportPreservesFixedWallVelocity();
	}
	private static void validateKernel() {
		cellAffineVelocityPreservesResolvedMotion();
		cellWetFaceAndConstraintWork();
		fixedWallProjectionUsesActualMass();
		var free = layout(false); verifyIndependentFluxAndGram(free); projectFreeSurface(free);
		var closed = layout(true); verifyIndependentFluxAndGram(closed); closedPressureKernel(closed);
		finiteGasStorage();
		tinyMassPressureDiagonal();
		largePressureDifferences();
		pressureResponseAvoidsIntermediateOverflow();
		pressureExtremeRoundingAndCancellation();
	}

	private static void pressureUsesCurrentMicroGasGeometry() {
		pressureUsesCurrentMicroGasGeometry(false);
	}
	static void pressureUsesCurrentMicroGasGeometry(boolean verifyLiquidPressure) {
		var d = new FluidDomain(3, 3, 3, new FluidSolverConfig(1, 1000, 0, .001F, 400, 1e-5F, .45F, .002F, 0));
		for (int c = 0; c < d.cellCount; c++) d.setSolid(c % 3, c / 3 % 3, c / 9, true);
		d.setSolid(0, 1, 1, false); d.setSolid(2, 1, 1, false);
		d.setSolidMicroMask(1, 1, 1, 126);
		d.setVolume(0, 1, 1, 1); d.setVolume(2, 1, 1, 1);
		d.sealDomainBoundary(); d.ensureGrid(); d.gasTopologyCoordinator.refresh();
		var frame = d.gasState.frame;
		int dry = d.cellIndex(1, 1, 1), first = frame.nodeOfPiece(8 * dry), second = frame.nodeOfPiece(8 * dry + 7);
		if (first <= 0 || second <= 0 || first == second || frame.commonCellNode(dry) != -1)
			throw new AssertionError("Pressure fixture lost its two independent gas owners");
		d.gasState.network.setStandardVolume(first, 1.5 * d.gasState.network.availableVolume(first));
		d.gasState.network.setStandardVolume(second, .75 * d.gasState.network.availableVolume(second));
		var geometry = frame.liquidPressureGeometry(d); boolean[] contacts = new boolean[frame.nodeCount() + 1];
		geometry.forEachGasPiece(piece -> {
			int owner = frame.nodeOfPiece(piece);
			if (owner != first && owner != second) throw new AssertionError("Wet face used a gas owner outside its actual microbox");
			contacts[owner] = true;
		});
		if (!contacts[first] || !contacts[second]) throw new AssertionError("Pressure geometry omitted a contacted gas owner");
		double p1 = d.gasState.network.gaugePressure(first), p2 = d.gasState.network.gaugePressure(second);
		double c1 = d.gasState.network.availableVolume(first) / (101325 + p1);
		double c2 = d.gasState.network.availableVolume(second) / (101325 + p2);
		System.arraycopy(d.mac.velocityX, 0, d.mac.p2gVelocityX, 0, d.mac.velocityX.length);
		System.arraycopy(d.mac.velocityY, 0, d.mac.p2gVelocityY, 0, d.mac.velocityY.length);
		System.arraycopy(d.mac.velocityZ, 0, d.mac.p2gVelocityZ, 0, d.mac.velocityZ.length);
		d.bodyForceStage.applyGravity(.001F);
		d.pressureStage.solve(.001F); d.pressureStage.projectVelocities(.001F);
		near(d.pressureReference, (p1 * c1 + p2 * c2) / (c1 + c2), 1e-3, "Actual-contact gas pressure gauge");
		if (d.projection.gasUnknownCount != 2 || !d.projection.gasPressureUnknown[first] || !d.projection.gasPressureUnknown[second])
			throw new AssertionError("Pressure preparation merged independently contacted gas nodes");
		near(d.pressureSystem.gasPressure(first) + d.pressureReference, p1, 1e-4, "First stationary cavity retains its pressure");
		near(d.pressureSystem.gasPressure(second) + d.pressureReference, p2, 1e-4, "Second stationary cavity retains its pressure");
		near(d.maximumParticleSpeed(), 0, 1e-6, "Closed stationary compartments retain zero motion");
		if (verifyLiquidPressure) {
			near(d.projection.pressure[12] + d.pressureReference, p1, 1e-3, "First stationary liquid pressure equals its contacted cavity pressure");
			near(d.projection.pressure[14] + d.pressureReference, p2, 1e-3, "Second stationary liquid pressure equals its contacted cavity pressure");
		}
		float x = d.particles.positionX(0); d.particles.setPositionX(0, Math.nextUp(x));
		boolean rejected = false;
		try { frame.liquidPressureGeometry(d); } catch (GasTopologyBlockedException expected) { rejected = true; }
		if (!rejected) throw new AssertionError("Pressure geometry accepted a stale liquid first moment");
		d.particles.setPositionX(0, x);
		if (!frame.liquidPressureGeometry(d).cellSnapshot(12).equals(geometry.cellSnapshot(12)))
			throw new AssertionError("Repeated geometry capture changed the immutable physical profile");
		var plan = d.pressureSystem.markerProjection(); plan.prepare(.001F);
		if (!plan.solve(d.config.pressureTolerance()).converged()) throw new AssertionError("Current pressure input failed to solve");
		d.gasTopologyCoordinator.refresh();
		rejected = false;
		try { frame.liquidPressureGeometry(d); } catch (GasTopologyBlockedException expected) { rejected = true; }
		if (!rejected) throw new AssertionError("Pressure geometry reused a replaced gas frame");
		rejected = false;
		try { plan.publish(); } catch (IllegalStateException expected) { rejected = true; }
		if (!rejected) throw new AssertionError("Pressure candidate published after its gas frame was replaced");
		near(d.maximumParticleSpeed(), 0, 1e-6, "Rejected pressure candidate retains current velocities");
		d.setVolume(0, 1, 1, 0); d.setVolume(2, 1, 1, 0);
		d.stepMechanicsOnly();
		var emptyField = d.pressureSystem.markerVelocityField();
		for (int cell = 0; cell < d.cellCount; cell++) if (emptyField != null && emptyField.hasCell(cell))
			throw new AssertionError("Empty liquid pressure retained a populated velocity cell");
		if (d.pressureAnchor != -1 || d.pressureReference != 0
				|| d.projection.fluidUnknownCount != 0 || d.projection.gasUnknownCount != 0)
			throw new AssertionError("Empty liquid pressure reused an earlier projection or velocity field");
	}

	static void transportPreservesFixedWallVelocity() {
		var d = new FluidDomain(4, 4, 4, new FluidSolverConfig(.5F, 1000, 0, .001F, 400, 1e-5F, .45F, .002F, .8F), 2048);
		for (int z = 1; z < 3; z++) for (int y = 1; y < 3; y++) for (int x = 0; x < 2; x++) d.setVolume(x, y, z, .6F);
		d.sealDomainBoundary();
		d.applyMovingBodyCoupling(new mmm.world.MovingBodyFluidCoupling.Request(.35F, .95F, 1.05F, 1, .2, -.1, .1));
		double mass = d.totalMass(), volume = d.totalVolume();
		var stats = d.stepWithMechanicalAudit(false, 0);
		near(stats.mechanicalAudit().closureResidualJoules(), 0, 1e-10, "Transported wall response contributes its actual kinetic work");
		near(d.totalMass(), mass, 2e-12, "Wall response preserves actual mass");
		near(d.totalVolume(), volume, 0, "Wall response preserves volume");
		for (var observed : new FluidDomain[]{d, FluidDomain.restoreAuthoritativeState(d.captureAuthoritativeState())}) {
			near(observed.sampleVelocity(0, .75F, .75F).x(), 0, 32 * Math.ulp(observed.maximumParticleSpeed()),
					"Transport and state restoration preserve the fixed-wall normal trace");
			near(observed.mac.velocityX[observed.xFaceIndex(0, 1, 1)], 0, 0, "Fixed-wall MAC trace");
		}
	}

	static void fixedWallProjectionUsesActualMass() {
		var d = new FluidDomain(3, 3, 3, new FluidSolverConfig(1, 1000, 0, .001F, 100, 1e-5F, .45F, .002F, 0));
		d.setVolume(1, 0, 1, 1); d.ensureGrid();
		var basis = CellAffineVelocity.capture(d); var wall = new CellAffineWallProjection(d, basis, LiquidPressureGeometry.capture(d));
		int n = basis.markerCount(); var before = new double[3][n]; var after = new double[3][n]; var impulse = new double[3][n];
		for (int i = 0; i < n; i++) before[1][i] = 2 + 3 * (basis.position(i, 1) - .5) + 4 * (basis.position(i, 0) - 1.5);
		wall.project(before, after, impulse); double removed = 0, energyChange = 0;
		for (int i = 0; i < n; i++) {
			near(after[1][i], 3.8 * basis.position(i, 1), 2e-14, "Independent actual-mass wall-admissible normal mode");
			double delta = before[1][i] - after[1][i];
			removed += basis.massKilograms(i) * delta * delta;
			energyChange += basis.massKilograms(i) * (before[1][i] * before[1][i] - after[1][i] * after[1][i]);
			near(impulse[1][i], -basis.massKilograms(i) * delta, 2e-13, "Actual fixed-wall impulse");
		}
		near(energyChange, removed, 2e-12, "Mass-orthogonal wall projection work");
		var twice = new double[3][n]; wall.project(after, twice, impulse);
		for (int axis = 0; axis < 3; axis++) for (int i = 0; i < n; i++) near(twice[axis][i], after[axis][i], 2e-14, "Fixed-wall projection idempotence");
	}

	static void unresolvedCapillaryTractionIsAppliedOnce() {
		var d = new FluidDomain(4, 4, 4, new FluidSolverConfig(1, 1000, 0, .001F, 400, 1e-7F, .45F, .002F, .8F), 128);
		d.setVolume(1, 1, 1, .4F); d.setVolume(2, 1, 1, .2F);
		var registry = new FluidMaterialRegistry(java.util.List.of(new FluidMaterial("mmm:cap", 1000, "mmm:cap", .0728, Math.PI / 2)));
		for (int marker = 0; marker < d.particleCount(); marker++) d.setMarkerMaterial(marker, registry, 0);
		d.ensureGrid();
		if (d.isFluid(d.cellIndex(1, 1, 1)) || d.isFluid(d.cellIndex(2, 1, 1)))
			throw new AssertionError("Sub-centre capillary fixture became a resolved pressure cell");
		d.gasTopologyCoordinator.refresh();
		System.arraycopy(d.mac.velocityX, 0, d.mac.p2gVelocityX, 0, d.mac.velocityX.length);
		System.arraycopy(d.mac.velocityY, 0, d.mac.p2gVelocityY, 0, d.mac.velocityY.length);
		System.arraycopy(d.mac.velocityZ, 0, d.mac.p2gVelocityZ, 0, d.mac.velocityZ.length);
		d.bodyForceStage.applyGravity(.001F); d.bodyForceStage.applyLiquidGasSurfaceTensionForce(.001F);
		var plan = d.pressureSystem.markerProjection(); plan.prepare(.001F);
		var predictor = plan.predictor(); var impulse = plan.knownImpulse(); double magnitude = 0;
		for (int axis = 0; axis < 3; axis++) for (int marker = 0; marker < d.particleCount(); marker++) {
			near(predictor[axis][marker], impulse[axis][marker] / d.unitScale.kilograms(d.particles.mass(marker)), 1e-15,
					"One natural capillary traction in the actual-mass predictor");
			magnitude = Math.hypot(magnitude, impulse[axis][marker]);
		}
		if (!(magnitude > 0)) throw new AssertionError("Capillary fixture omitted its natural surface traction");
	}

	/** Shared and exposed wet portions use their own traces, with actual-mass virtual work. */
	static void cellWetFaceAndConstraintWork() {
		var d = new FluidDomain(3, 3, 3, new FluidSolverConfig(1, 1000, 0, .001F, 400, 1e-5F, .45F, .002F, .8F), 256);
		float fraction = .6F; int count = 144, marker = 0; float[] position = new float[4 * count]; double volume = 0;
		for (int z = 0; z < 3; z++) for (int y = 0; y < 2; y++) for (int x = 0; x < 3; x++) for (int slot = 0; slot < 8; slot++) {
			float height = y == 0 || x == 0 ? 1 : fraction; int base = 4 * marker++;
			position[base] = x + .25F + .5F * (slot & 1);
			position[base + 1] = y + height * (.25F + .5F * (slot >> 1 & 1));
			position[base + 2] = z + .25F + .5F * (slot >> 2 & 1);
			position[base + 3] = height / 8; volume += height / 8;
		}
		d.restoreParticleState(new FlipParticleState(3, 3, 3, 1, count, (float) volume, position, new float[3 * count], new byte[27]));
		d.ensureGrid(); var basis = CellAffineVelocity.capture(d);
		var transfer = CellAffineMacTransfer.capture(d, basis, LiquidPressureGeometry.capture(d));
		int left = d.cellIndex(0, 1, 1), right = d.cellIndex(1, 1, 1), face = d.xFaceIndex(1, 1, 1);
		var coefficients = new double[3][4 * d.cellCount]; coefficients[0][4 * left] = 2; coefficients[0][4 * right] = 4;
		var mapped = new double[][]{new double[transfer.faceCount(0)], new double[transfer.faceCount(1)], new double[transfer.faceCount(2)]};
		transfer.apply(coefficients, mapped);
		near(transfer.wetArea(0, face), 1, 2e-14, "Shared plus exposed wet area");
		near(mapped[0][face], 2 + (double) fraction, 2e-14, "Shared half traces plus exposed owner trace");
		var moments = new double[][]{new double[3 * transfer.faceCount(0)], new double[3 * transfer.faceCount(1)], new double[3 * transfer.faceCount(2)]};
		transfer.applyMoments(coefficients, moments);
		near(moments[0][3 * face], 2 + (double) fraction, 2e-14, "Wet normal flux");
		near(moments[0][3 * face + 1], fraction * ((double) fraction - 1) / 2, 2e-14, "Wet normal flux first moment");
		near(moments[0][3 * face + 2], 0, 2e-14, "Symmetric other wet flux moment");
		var faceLoad = new double[][]{mapped[0].clone(), mapped[1].clone(), mapped[2].clone()};
		for (int axis = 0; axis < 3; axis++) for (int f = 0; f < faceLoad[axis].length; f++) faceLoad[axis][f] = Math.sin((axis + 1) * .2 + f * .1);
		var cellLoad = new double[3][4 * d.cellCount]; transfer.transpose(faceLoad, cellLoad);
		near(work(mapped, faceLoad), work(coefficients, cellLoad), 2e-12, "Wet-face map and transpose work");
		var definitions = java.util.List.of(
				new CellVelocityConstraintOperator.Row(java.util.List.of(
						new CellVelocityConstraintOperator.Block(left, 0, new double[]{1, 2, 3, 4}),
						new CellVelocityConstraintOperator.Block(left, 0, new double[]{-.5, 0, 0, 0})), 0),
				new CellVelocityConstraintOperator.Row(java.util.List.of(
						new CellVelocityConstraintOperator.Block(left, 0, new double[]{2, -1, 0, .5}),
						new CellVelocityConstraintOperator.Block(right, 1, new double[]{1, 0, 2, 1})), .125));
		var constraint = new CellVelocityConstraintOperator(basis, definitions);
		var velocity = new double[3][count];
		for (int axis = 0; axis < 3; axis++) for (int i = 0; i < count; i++) velocity[axis][i] = Math.sin(.2 * i + axis);
		double[] rate = new double[2], pressure = {.7, -1.3}; constraint.volumeRate(velocity, rate);
		var force = new double[3][count]; constraint.force(pressure, force);
		near(rate[0] * pressure[0] + rate[1] * pressure[1], work(velocity, force), 2e-12, "Constraint and actual-marker force work");
		double[] applied = new double[2]; constraint.apply(pressure, applied); double kinetic = 0;
		for (int axis = 0; axis < 3; axis++) for (int i = 0; i < count; i++) kinetic += force[axis][i] * force[axis][i] / basis.massKilograms(i);
		near(pressure[0] * applied[0] + pressure[1] * applied[1], kinetic + .125 * pressure[1] * pressure[1], 2e-12, "Actual mass Gram and gas storage");
		double[] diagonal = new double[2], unit = new double[2]; constraint.diagonal(diagonal);
		for (int row = 0; row < 2; row++) {
			Arrays.fill(unit, 0); unit[row] = 1; constraint.apply(unit, applied);
			near(diagonal[row], applied[row], 2e-14, "Merged constraint block diagonal");
		}
		unit[0] = 1; unit[1] = 0; constraint.apply(unit, applied);
		double coupling = applied[1], scale = Math.sqrt(diagonal[0] * diagonal[1]);
		double normalized = coupling / scale;
		var preconditioner = constraint.preconditioner(diagonal); double[] first = {2, -3}, second = {-.7, 1.1};
		double[] firstResult = new double[2], secondResult = new double[2];
		preconditioner.apply(first, firstResult); preconditioner.apply(second, secondResult);
		near(firstResult[0], (1 + normalized * normalized) * first[0] / diagonal[0] - normalized * first[1] / scale,
				2e-12, "Independent two-row symmetric Gauss-Seidel inverse");
		near(firstResult[1], first[1] / diagonal[1] - normalized * first[0] / scale, 2e-12,
				"Independent two-row backward solve");
		near(dot(first, secondResult), dot(second, firstResult), 2e-12, "Symmetric actual-Gram preconditioner");
		if (!(dot(first, firstResult) > 0)) throw new AssertionError("Actual-Gram preconditioner lost positive work");
	}

	static void cellAffineVelocityPreservesResolvedMotion() {
		float[][] cube = new float[8][3];
		for (int i = 0; i < 8; i++) for (int a = 0; a < 3; a++) cube[i][a] = (i >> a & 1) == 0 ? .125F : .375F;
		FluidDomain domain = affineDomain(cube); CellAffineVelocity basis = CellAffineVelocity.capture(domain);
		if (basis.rank(0) != 4 || !basis.hasRepresentableBasis(0) || !basis.canRepresentPoint(0, .5, .5, .5))
			throw new AssertionError("Full spatial quadrature lost its affine velocity capacity");
		for (int a = 0; a < 3; a++) {
			java.math.BigDecimal mass = java.math.BigDecimal.ZERO, first = java.math.BigDecimal.ZERO;
			for (int i = 0; i < cube.length; i++) {
				var m = new java.math.BigDecimal(basis.massKilograms(i)); mass = mass.add(m);
				first = first.add(m.multiply(new java.math.BigDecimal((double) cube[i][a])));
			}
			near(basis.center(0, a), first.divide(mass, java.math.MathContext.DECIMAL128).doubleValue(), 0,
					"Actual mass first-moment centre");
		}
		verifyAffineRigidMotion(basis);
		var velocity = new double[3][cube.length]; var load = new double[3][4 * basis.cellCount()];
		for (int a = 0; a < 3; a++) {
			for (int i = 0; i < cube.length; i++) velocity[a][i] = Math.sin(.37 * i + a);
			for (int term = 0; term < 4; term++) load[a][term] = Math.cos(.23 * term + a);
		}
		var fitted = new double[3][4 * basis.cellCount()]; var force = new double[3][cube.length]; basis.fit(velocity, fitted); basis.transposeLoad(load, force);
		near(work(velocity, force), work(fitted, load), 2e-14, "Cell affine map/adjoint virtual work");
		var resolved = new double[3][cube.length]; basis.predict(fitted, resolved);
		for (int a = 0; a < 3; a++) for (int term = 0; term < 4; term++) {
			double residual = 0;
			for (int i = 0; i < cube.length; i++) residual += basis.massKilograms(i) * (velocity[a][i] - resolved[a][i])
					* (term == 0 ? 1 : (cube[i][term - 1] - basis.center(0, term - 1)) / basis.cellSize());
			near(residual, 0, 2e-13, "Mass-orthogonal unresolved cell velocity");
		}
		float[][][] sparse = {
				{{.125F, .125F, .125F}},
				{{.125F, .125F, .125F}, {.375F, .375F, .375F}},
				{{.125F, .125F, .25F}, {.375F, .125F, .25F}, {.125F, .375F, .25F}}
		};
		for (int k = 0; k < sparse.length; k++) {
			var lowRank = CellAffineVelocity.capture(affineDomain(sparse[k]));
			if (lowRank.rank(0) != k + 1 || !lowRank.hasRepresentableBasis(0)
					|| !lowRank.canRepresentPoint(0, sparse[k][0][0], sparse[k][0][1], sparse[k][0][2])
					|| lowRank.canRepresentPoint(0, .125, .125, k == 2 ? Math.nextUp(.25) : .375))
				throw new AssertionError("Sparse actual geometry was assigned an invented affine direction");
			verifyAffineRigidMotion(lowRank);
		}
		var tilted = new float[][]{{.125F, .125F, .125F}, {.375F, .125F, .125F}, {.125F, .375F, .125F}, {.375F, .375F, Math.nextUp(.125F)}};
		if (CellAffineVelocity.capture(affineDomain(tilted)).rank(0) != 4)
			throw new AssertionError("Exact geometric rank discarded a real binary32 spatial direction");
		var closePoints = new FluidDomain(12, 8, 4, new FluidSolverConfig(1, 1000, 0, .001F, 100, 1e-5F, .45F, .002F, 0));
		float[] saved = {7.9979296F, 3.0201921F, .5030171F, 1.6467273e-4F,
				7.9978232F, 3.0137305F, .50301975F, 3.2901764e-5F,
				7.421645F, 3.3454382F, .49268323F, .0026631951F};
		closePoints.restoreParticleState(new FlipParticleState(12, 8, 4, 1, 3, .0028607696F, saved, new float[9], new byte[384]));
		var closeBasis = CellAffineVelocity.capture(closePoints);
		int closeCell = closeBasis.markerCell(0);
		if (closeBasis.rank(closeCell) != 3 || !closeBasis.hasRepresentableBasis(closeCell))
			throw new AssertionError("Separated binary32 points lost their certified affine map through rounded refinement residuals");
		var affineVelocity = new double[3][3]; var closeCoefficients = new double[3][4 * closeBasis.cellCount()];
		for (int axis = 0; axis < 3; axis++) for (int i = 0; i < 3; i++) affineVelocity[axis][i] = 1 + closeBasis.position(i, axis);
		closeBasis.fit(affineVelocity, closeCoefficients); var closePrediction = new double[3][3]; closeBasis.predict(closeCoefficients, closePrediction);
		for (int axis = 0; axis < 3; axis++) for (int i = 0; i < 3; i++)
			near(closePrediction[axis][i], affineVelocity[axis][i], 2e-12, "Certified affine field on separated near-collinear points");
		var thinTetrahedron = new FluidDomain(12, 8, 4, new FluidSolverConfig(1, 1000, 0, .001F, 100, 1e-5F, .45F, .002F, 0));
		float[] thin = {3.880811F, 3.0868871F, 3.0868871F, 2.6449561e-5F,
				3.852336F, 3.086874F, 3.086874F, 8.146465e-5F,
				3.2086852F, 3.4979026F, 3.4938903F, .08683321F,
				3.733526F, 3.4979026F, 3.4938903F, .08683321F};
		thinTetrahedron.restoreParticleState(new FlipParticleState(12, 8, 4, 1, 4, .17377433F, thin, new float[12], new byte[384]));
		var thinBasis = CellAffineVelocity.capture(thinTetrahedron); int thinCell = thinBasis.markerCell(0);
		if (thinBasis.rank(thinCell) != 4 || !thinBasis.hasRepresentableBasis(thinCell) || !thinBasis.expandedCell(thinCell))
			throw new AssertionError("Near-degenerate actual tetrahedron lost the precision required by its affine moment certificate");
		var thinVelocity = new double[3][4]; var thinCoefficients = new double[3][4 * thinBasis.cellCount()];
		for (int axis = 0; axis < 3; axis++) for (int i = 0; i < 4; i++) thinVelocity[axis][i] = 1 + thinBasis.position(i, axis);
		thinBasis.fit(thinVelocity, thinCoefficients); var thinPrediction = new double[3][4]; thinBasis.predict(thinCoefficients, thinPrediction);
		for (int axis = 0; axis < 3; axis++) for (int i = 0; i < 4; i++)
			near(thinPrediction[axis][i], thinVelocity[axis][i], 2e-14, "Expanded affine fit reproduces the actual resolved field");
		var thinConstraint = new CellVelocityConstraintOperator(thinBasis, java.util.List.of(new CellVelocityConstraintOperator.Row(
				java.util.List.of(new CellVelocityConstraintOperator.Block(thinCell, 0, new double[]{0, 1, 0, 0})), 0)));
		var translation = new double[3][4]; Arrays.fill(translation[0], 1); double[] translationRate = new double[1];
		thinConstraint.volumeRate(translation, translationRate);
		near(translationRate[0], 0, 2e-14, "Expanded constraint preserves its constant-velocity kernel");
		double savedMass = basis.massKilograms(0), savedCentre = basis.center(0, 0), savedCoefficient = basis.coefficient(0, 0);
		domain.particles.setMass(0, 200); domain.particles.setPositionX(0, .25F);
		near(basis.massKilograms(0), savedMass, 0, "Captured affine mass ownership");
		near(basis.center(0, 0), savedCentre, 0, "Captured affine centre ownership");
		near(basis.coefficient(0, 0), savedCoefficient, 0, "Captured affine map ownership");
	}

	private static void verifyAffineRigidMotion(CellAffineVelocity basis) {
		for (int mode = 0; mode < 4; mode++) {
			var velocity = new double[3][basis.markerCount()]; var coefficient = new double[3][4 * basis.cellCount()];
			for (int i = 0; i < basis.markerCount(); i++) for (int a = 0; a < 3; a++) velocity[a][i] = mode == 0
					? new double[]{1.25, -.75, .125}[a] : a == mode % 3 ? -basis.position(i, (a + 1) % 3)
					: a == (mode + 1) % 3 ? basis.position(i, (a + 2) % 3) : 0;
			basis.fit(velocity, coefficient); var reconstructed = new double[3][basis.markerCount()]; basis.predict(coefficient, reconstructed);
			for (int a = 0; a < 3; a++) for (int i = 0; i < basis.markerCount(); i++)
				near(reconstructed[a][i], velocity[a][i], 2e-14, "Constant/rigid rotation on actual affine support");
			near(basis.divergence(coefficient, 0), 0, 2e-14, "Rigid rotation affine divergence");
			if (basis.rank(0) == 4) {
				double[] point = {.45, .3, .2};
				for (int a = 0; a < 3; a++) {
					double expected = mode == 0 ? new double[]{1.25, -.75, .125}[a] : a == mode % 3 ? -point[(a + 1) % 3]
							: a == (mode + 1) % 3 ? point[(a + 2) % 3] : 0;
					near(basis.value(coefficient, a, 0, point[0], point[1], point[2]), expected, 2e-14,
							"Full-rank rigid field at a face trace point");
				}
			}
		}
	}

	private static FluidDomain affineDomain(float[][] points) {
		var domain = new FluidDomain(2, 2, 2, new FluidSolverConfig(.5F, 1000, 0, .001F, 100, 1e-5F, .45F, .002F, 0), 32);
		var positions = new float[4 * points.length]; var mass = new double[points.length];
		for (int i = 0; i < points.length; i++) {
			for (int a = 0; a < 3; a++) positions[4 * i + a] = points[i][a];
			positions[4 * i + 3] = .0625F; mass[i] = 100 * (1 + .125 * i);
		}
		domain.restoreMaterialParticleState(new FlipMaterialParticleState(2, 2, 2, .5F, points.length, points.length / 16.0,
				mmm.units.SignedDyadic.sum(mass, mass.length).toDouble(),
				java.util.List.of(new FluidMaterial("mmm:water", 1000, "mmm:water", 0, Math.PI / 2)),
				positions, new float[3 * points.length], new byte[8], mass, new int[points.length]));
		return domain;
	}

	private static Layout layout(boolean full) {
		var d = new FluidDomain(4, 4, 4, new FluidSolverConfig(.5F, 1000, 0, .001F, 400, 1e-5F, .45F, .002F, .8F), 2048);
		for (int z = full ? 0 : 1; z < (full ? 4 : 3); z++) for (int y = full ? 0 : 1; y < (full ? 4 : 3); y++)
			for (int x = full ? 0 : 1; x < (full ? 4 : 3); x++) d.setVolume(x, y, z, full ? 1 : .6F);
		if (full) for(int marker=0;marker<d.particles.count();marker++)d.particles.setMass(marker,d.particles.mass(marker)*(1+.0137*marker));
		d.ensureGrid(); var j = new MarkerVelocityTransfer(); j.capture(d);
		int[] cells = java.util.stream.IntStream.range(0, d.cellCount).filter(d::isFluid).toArray();
		int[] cellRows = new int[d.cellCount]; Arrays.fill(cellRows, -1);
		for (int row = 0; row < cells.length; row++) cellRows[cells[row]] = row;
		int[][] first = new int[3][], second = new int[3][]; double[][] area = faces(j);
		for (int axis = 0; axis < 3; axis++) {
			first[axis] = new int[j.faceCount(axis)]; second[axis] = new int[j.faceCount(axis)];
			Arrays.fill(first[axis], -1); Arrays.fill(second[axis], -1);
			for (int z = 0; z < d.sizeZ; z++) for (int y = 0; y < d.sizeY; y++) for (int x = 0; x < d.sizeX; x++) {
				if ((axis == 0 && x == 0) || (axis == 1 && y == 0) || (axis == 2 && z == 0)) continue;
				int hi = d.cellIndex(x,y,z), lo = d.cellIndex(x-(axis==0?1:0), y-(axis==1?1:0), z-(axis==2?1:0));
				int face = axis == 0 ? d.xFaceIndex(x,y,z) : axis == 1 ? d.yFaceIndex(x,y,z) : d.zFaceIndex(x,y,z);
				first[axis][face] = cellRows[lo]; second[axis][face] = cellRows[hi];
				if (first[axis][face] != -1 || second[axis][face] != -1) area[axis][face] = .25 * d.geometry.cutCells.openFaceField(axis)[face];
			}
		}
		return new Layout(d, j, new MarkerPressureOperator(j, cells.length, first, second, area, new double[cells.length]), cells);
	}

	private static double[][] markers(MarkerVelocityTransfer j) { return new double[3][j.markerCount()]; }
	private static double[][] faces(MarkerVelocityTransfer j) {
		return new double[][]{new double[j.faceCount(0)],new double[j.faceCount(1)],new double[j.faceCount(2)]};
	}
	private static void near(double actual, double expected, double tolerance, String label) {
		if (!(Double.isFinite(actual) && Math.abs(actual-expected) <= tolerance)) throw new AssertionError(label+": "+actual+" != "+expected);
	}
	private static double dot(double[] a, double[] b) { double value=0; for (int i=0;i<a.length;i++) value+=a[i]*b[i]; return value; }
	private static double work(double[][] a, double[][] b) { double value=0; for(int axis=0;axis<3;axis++) value+=dot(a[axis],b[axis]); return value; }
	private static double kinetic(MarkerVelocityTransfer j, double[][] velocity) {
		double result=0; for(int axis=0;axis<3;axis++) for(int marker=0;marker<j.markerCount();marker++) result+=.5*j.massKilograms(marker)*velocity[axis][marker]*velocity[axis][marker]; return result;
	}
	private static double[] cellFlux(Layout l, double[][] velocity) {
		var mac=faces(l.transfer); l.transfer.apply(velocity,mac);return macFlux(l,mac);
	}
	private static double[] macFlux(Layout l, double[][] mac) {
		var d=l.domain; double[] result=new double[l.cells.length];
		for(int row=0;row<result.length;row++) {
			int cell=l.cells[row], x=cell%d.sizeX, y=cell/d.sizeX%d.sizeY, z=cell/(d.sizeX*d.sizeY);
			result[row]=.25*(d.pressureXFaceOpen(x+1,y,z)*mac[0][d.xFaceIndex(x+1,y,z)]-d.pressureXFaceOpen(x,y,z)*mac[0][d.xFaceIndex(x,y,z)]
					+d.pressureYFaceOpen(x,y+1,z)*mac[1][d.yFaceIndex(x,y+1,z)]-d.pressureYFaceOpen(x,y,z)*mac[1][d.yFaceIndex(x,y,z)]
					+d.pressureZFaceOpen(x,y,z+1)*mac[2][d.zFaceIndex(x,y,z+1)]-d.pressureZFaceOpen(x,y,z)*mac[2][d.zFaceIndex(x,y,z)]);
		}
		return result;
	}

	private static void verifyIndependentFluxAndGram(Layout l) {
		var j=l.transfer; var c=l.operator; int n=c.rowCount();
		if(c.unsupportedFaceCount()!=0) throw new AssertionError("Fixture has unrepresented pressure faces");
		var v=markers(j); for(int a=0;a<3;a++) for(int i=0;i<j.markerCount();i++) v[a][i]=Math.sin(.37*i+a);
		double[] actual=new double[n]; c.volumeRate(v,actual); var expected=cellFlux(l,v);
		for(int r=0;r<n;r++) near(actual[r],expected[r],2e-15,"Independent cell flux");
		// Independent dense C columns come from MAC divergence of basis marker velocities.
		double[][] dense=new double[n][3*j.markerCount()]; var basis=markers(j);
		for(int a=0;a<3;a++) for(int i=0;i<j.markerCount();i++) {
			basis[a][i]=1; var column=cellFlux(l,basis); basis[a][i]=0;
			for(int r=0;r<n;r++) dense[r][a*j.markerCount()+i]=column[r];
		}
		double[] p=new double[n]; for(int r=0;r<n;r++) p[r]=Math.cos(.73*r);
		var force=markers(j); c.force(p,force); near(work(v,force),dot(actual,p),3e-14,"Pressure virtual work");
		for(int a=0;a<3;a++) for(int i=0;i<j.markerCount();i++) {
			double expectedForce=0; for(int r=0;r<n;r++) expectedForce+=dense[r][a*j.markerCount()+i]*p[r];
			near(force[a][i],expectedForce,2e-15,"Independent adjoint force");
		}
		double[] diagonal=new double[n]; c.diagonal(diagonal);
		for(int col=0;col<n;col++) {
			double[] unit=new double[n], applied=new double[n]; unit[col]=1; c.apply(unit,applied);
			for(int r=0;r<n;r++) {
				double expectedGram=0; for(int a=0;a<3;a++) for(int i=0;i<j.markerCount();i++) expectedGram+=dense[r][a*j.markerCount()+i]*dense[col][a*j.markerCount()+i]/j.massKilograms(i);
				near(applied[r],expectedGram,3e-17,"Independent pressure inertia");
			}
			near(diagonal[col],applied[col],3e-17,"Merged row diagonal");
		}
	}

	private static void projectFreeSurface(Layout l) {
		var j=l.transfer; var c=l.operator; int n=c.rowCount(); double dt=.001;
		var v=markers(j); for(int a=0;a<3;a++) for(int i=0;i<j.markerCount();i++) v[a][i]=Math.sin(.37*i+a);
		double[] rhs=new double[n]; c.volumeRate(v,rhs); for(int r=0;r<n;r++) rhs[r]/=-dt;
		double[][] matrix=new double[n][n];
		for(int col=0;col<n;col++) { double[] unit=new double[n], value=new double[n]; unit[col]=1;c.apply(unit,value);for(int r=0;r<n;r++) matrix[r][col]=value[r]; }
		// Independent small Cholesky oracle; no diagonal regularization or invented inertia.
		for(int r=0;r<n;r++) for(int col=0;col<=r;col++) {
			double sum=matrix[r][col]; for(int k=0;k<col;k++) sum-=matrix[r][k]*matrix[col][k];
			if(r==col) { if(!(sum>0)) throw new AssertionError("Free-surface pressure lost rank");matrix[r][col]=Math.sqrt(sum); }
			else matrix[r][col]=sum/matrix[col][col];
		}
		double[] p=rhs.clone(); for(int r=0;r<n;r++) {for(int k=0;k<r;k++) p[r]-=matrix[r][k]*p[k];p[r]/=matrix[r][r];}
		for(int r=n-1;r>=0;r--) {for(int k=r+1;k<n;k++) p[r]-=matrix[k][r]*p[k];p[r]/=matrix[r][r];}
		var force=markers(j); c.force(p,force); var next=markers(j); var midpoint=markers(j);
		for(int a=0;a<3;a++) for(int i=0;i<j.markerCount();i++) {next[a][i]=v[a][i]+dt*force[a][i]/j.massKilograms(i);midpoint[a][i]=.5*(v[a][i]+next[a][i]);}
		double[] residual=new double[n]; c.volumeRate(next,residual);
		for(double value:residual) near(value,0,8e-16,"Actual marker continuity");
		for(int a=0;a<3;a++) {double impulse=0;for(int i=0;i<j.markerCount();i++) impulse+=j.massKilograms(i)*(next[a][i]-v[a][i]);near(impulse,0,2e-12,"Closed pressure surface impulse");}
		double change=kinetic(j,next)-kinetic(j,v); near(change,dt*work(midpoint,force),5e-13,"Finite pressure impulse work");
		double[] hp=new double[n];c.apply(p,hp);near(change,-.5*dt*dt*dot(p,hp),5e-13,"Projection dissipation");
		for(int a=0;a<3;a++) for(int i=0;i<j.markerCount();i++) next[a][i]=(float)next[a][i];
		var roundedFlux=cellFlux(l,next); for(double value:roundedFlux) near(value,0,2e-8,"Float marker publication continuity");
		float[][] published=new float[][]{new float[j.faceCount(0)],new float[j.faceCount(1)],new float[j.faceCount(2)]};j.publish(next,published,faces(j));
		var mac=faces(j);j.apply(next,mac);
		for(int a=0;a<3;a++)for(int face=0;face<j.faceCount(a);face++) {
			if(published[a][face]!=(float)mac[a][face])throw new AssertionError("MAC publication differs from actual float markers");
			mac[a][face]=published[a][face];
		}
		for(double value:macFlux(l,mac))near(value,0,8e-8,"Published MAC continuity");
	}

	private static void closedPressureKernel(Layout l) {
		double[] p=new double[l.operator.rowCount()], result=new double[p.length];Arrays.fill(p,1e200);l.operator.apply(p,result);
		for(double value:result) near(value,0,1e-17,"Closed constant-pressure kernel");
		var force=markers(l.transfer);l.operator.force(p,force);for(double[] axis:force)for(double value:axis)near(value,0,1e-15,"Closed constant-pressure force");
	}

	private static void finiteGasStorage() {
		var l=layout(false); var j=l.transfer; int[][] first=new int[3][],second=new int[3][];var area=faces(j);
		for(int a=0;a<3;a++){first[a]=new int[j.faceCount(a)];second[a]=new int[j.faceCount(a)];Arrays.fill(first[a],-1);Arrays.fill(second[a],-1);}
		int face=l.domain.xFaceIndex(2,1,1);first[0][face]=0;second[0][face]=1;area[0][face]=.25;
		double dt=.001, compliance=2e-6, storage=compliance/(dt*dt), gasInitial=1500;
		var c=new MarkerPressureOperator(j,2,first,second,area,new double[]{0,storage});
		var v=markers(j);Arrays.fill(v[0],.2);double[] rate=new double[2];c.volumeRate(v,rate);
		double[] unit={1,0}, h=new double[2];c.apply(unit,h);double gram=h[0];
		double gasNew=gasInitial, liquidNew=gasNew-rate[0]/(dt*gram);
		var force=markers(j);c.force(new double[]{liquidNew,gasNew},force);
		for(int a=0;a<3;a++)for(int i=0;i<j.markerCount();i++)v[a][i]+=dt*force[a][i]/j.massKilograms(i);
		c.volumeRate(v,rate);near(rate[0],0,2e-16,"Liquid/gas shared flux");near(rate[1]+compliance*(gasNew-gasInitial)/dt,0,2e-16,"Finite gas storage sign");
		c.apply(new double[]{liquidNew,gasNew},h);near(h[0],-.05/dt,2e-12,"SI liquid RHS");near(h[1],.05/dt+storage*gasInitial,2e-12,"SI finite gas RHS");
		// Prescribed liquid pressure, finite gas: incoming liquid compresses the gas.
		first[0][face]=-1;second[0][face]=0;
		var gas=new MarkerPressureOperator(j,1,first,second,area,new double[]{storage});
		v=markers(j);Arrays.fill(v[0],.2);double[] gasRate=new double[1],response=new double[1];gas.volumeRate(v,gasRate);gas.apply(new double[]{1},response);
		double compressedPressure=-gasRate[0]/(dt*response[0]);
		if(!(compressedPressure>0))throw new AssertionError("Incoming liquid did not raise gas pressure");
		gas.force(new double[]{compressedPressure},force);
		for(int a=0;a<3;a++)for(int i=0;i<j.markerCount();i++)v[a][i]+=dt*force[a][i]/j.massKilograms(i);
		gas.volumeRate(v,gasRate);near(gasRate[0]+compliance*compressedPressure/dt,0,2e-16,"Gas compression storage equation");
		// Nonzero prescribed liquid pressure and capillary jump balance the initial gas pressure.
		var knownFaceLoad=faces(j);knownFaceLoad[0][face]=.25*(120-20);
		var knownForce=markers(j);j.transpose(knownFaceLoad,knownForce);v=markers(j);
		for(int a=0;a<3;a++)for(int i=0;i<j.markerCount();i++)v[a][i]=dt*knownForce[a][i]/j.massKilograms(i);
		gas.volumeRate(v,gasRate);gas.apply(new double[]{1},response);
		double balancedPressure=(-gasRate[0]/dt+storage*100)/response[0];near(balancedPressure,100,2e-13,"Known pressure and jump balance");
		gas.force(new double[]{balancedPressure},force);
		for(int a=0;a<3;a++)for(int i=0;i<j.markerCount();i++)near(v[a][i]+dt*force[a][i]/j.massKilograms(i),0,2e-16,"Balanced gas velocity");
	}

	private static void tinyMassPressureDiagonal() {
		var d=new FluidDomain(4,4,4,new FluidSolverConfig(.5F,1000,0,.001F,400,1e-5F,.45F,.002F,.8F),2048);
		d.restoreMaterialParticleState(new FlipMaterialParticleState(4,4,4,.5F,2,.25,125,
				java.util.List.of(new FluidMaterial("mmm:water",1000,"mmm:water",0,Math.PI/2),new FluidMaterial("mmm:tiny",8e-180,"mmm:tiny",0,Math.PI/2)),
				new float[]{.75F,.75F,.75F,.125F,.5F,.75F,.75F,.125F},new float[6],new byte[64],new double[]{125,1e-180},new int[]{0,1}));
		var j=new MarkerVelocityTransfer();j.capture(d);
		int[][] first=new int[3][],second=new int[3][];var area=faces(j);
		for(int a=0;a<3;a++){first[a]=new int[j.faceCount(a)];second[a]=new int[j.faceCount(a)];Arrays.fill(first[a],-1);Arrays.fill(second[a],-1);}
		int low=d.xFaceIndex(1,1,1),high=d.xFaceIndex(2,1,1);first[0][low]=0;second[0][high]=0;area[0][low]=area[0][high]=.25;
		var c=new MarkerPressureOperator(j,1,first,second,area,new double[]{0});
		double[] diagonal=new double[1], applied=new double[1];c.diagonal(diagonal);c.apply(new double[]{1},applied);
		if(!(diagonal[0]>0&&Double.isFinite(diagonal[0])&&Double.doubleToRawLongBits(diagonal[0])==Double.doubleToRawLongBits(applied[0])))throw new AssertionError("Tiny actual mass lost pressure rank");
		var force=markers(j);c.force(new double[]{1},force);near(force[0][0],0,0,"Major carrier cancellation");
		if(force[0][1]*force[0][1]!=0)throw new AssertionError("Tiny-mass fixture does not expose square-first underflow");
	}

	private static void largePressureDifferences() {
		var l=layout(false);var j=l.transfer;int[][] first=new int[3][],second=new int[3][];var area=faces(j);
		for(int a=0;a<3;a++){first[a]=new int[j.faceCount(a)];second[a]=new int[j.faceCount(a)];Arrays.fill(first[a],-1);Arrays.fill(second[a],-1);}
		int face=l.domain.xFaceIndex(2,1,1);first[0][face]=0;second[0][face]=1;area[0][face]=.25;
		var c=new MarkerPressureOperator(j,2,first,second,area,new double[]{0,0});var force=markers(j);
		c.force(new double[]{Double.MAX_VALUE,-Double.MAX_VALUE},force);
		for(int marker=0;marker<j.markerCount();marker++) {
			double coefficient=0;
			for(int slot=0;slot<j.coefficientCount(0,marker);slot++)if(j.faceIndex(0,marker,slot)==face)coefficient=j.coefficient(0,marker,slot);
			// Exact dyadic area: 0.25*(MAX-(-MAX)) = 0.5*MAX remains representable.
			double expected=(.5*coefficient)*Double.MAX_VALUE;
			near(force[0][marker],expected,4*Math.ulp(expected),"Finite force from overflowing pressure difference");
			near(force[1][marker],0,0,"Isolated pressure Y force");near(force[2][marker],0,0,"Isolated pressure Z force");
		}
		double[] response=new double[2];c.apply(new double[]{Double.MAX_VALUE,-Double.MAX_VALUE},response);
		if(!(Double.isFinite(response[0])&&response[0]>0&&response[1]==-response[0]))throw new AssertionError("Representable high-pressure Gram response overflowed");
	}

	private static void pressureResponseAvoidsIntermediateOverflow() {
		var d=new FluidDomain(4,4,4,new FluidSolverConfig(.5F,1000,0,.001F,400,1e-5F,.45F,.002F,.8F),2048);
		d.restoreMaterialParticleState(new FlipMaterialParticleState(4,4,4,.5F,1,.125,1.25e-201,
				java.util.List.of(new FluidMaterial("mmm:water",1000,"mmm:water",0,Math.PI/2),new FluidMaterial("mmm:tiny",1e-200,"mmm:tiny",0,Math.PI/2)),
				new float[]{.75F,.75F,.75F,.125F},new float[3],new byte[64],new double[]{1.25e-201},new int[]{1}));
		var j=new MarkerVelocityTransfer();j.capture(d);int[][] first=new int[3][],second=new int[3][];var area=faces(j);
		for(int a=0;a<3;a++){first[a]=new int[j.faceCount(a)];second[a]=new int[j.faceCount(a)];Arrays.fill(first[a],-1);Arrays.fill(second[a],-1);}
		int face=d.xFaceIndex(2,1,1);first[0][face]=0;second[0][face]=1;area[0][face]=.25;
		var c=new MarkerPressureOperator(j,2,first,second,area,new double[]{0,0});double[] response=new double[2];
		c.apply(new double[]{2e107,0},response);
		// The acceleration overflows, whereas C M^-1 C^T p remains finite.
		double expected=(.0625/j.massKilograms(0))*2e107;
		near(response[0],expected,4*Math.ulp(expected),"Representable pressure response");
		near(response[1],-expected,4*Math.ulp(expected),"Opposite shared pressure response");
	}

	private static void pressureExtremeRoundingAndCancellation() {
		// Independently evaluated exact binary products round to these literal endpoints.
		for (double sign : new double[]{1,-1}) {
			near(MarkerPressureOperator.rescaledResponse(sign*0x1.b9f88e001b9f8p-77,0x1.04p500,0x1.24p600),
					sign*Double.MAX_VALUE,0,"Final pressure response overflow midpoint");
			near(MarkerPressureOperator.rescaledResponse(sign*0x1.e18ce7228170bp24,0x1.04p-1000,0x1.0cp-100),
					sign*Double.MIN_VALUE,0,"Final pressure response underflow midpoint");
			near(MarkerPressureOperator.scaledPressureDifference(sign*0x1.f07c1f07c1f07p-1,0x1.04p1023,-0x1.0cp1023),
					sign*Double.MAX_VALUE,0,"Final scaled pressure difference midpoint");
		}
		if(MarkerPressureOperator.rescaledResponse(1,Double.MAX_VALUE,2)!=Double.POSITIVE_INFINITY
				||MarkerPressureOperator.scaledPressureDifference(1,Double.MAX_VALUE,-Double.MAX_VALUE)!=Double.POSITIVE_INFINITY)
			throw new AssertionError("True exact pressure overflow was hidden");
		if(!Double.isNaN(MarkerPressureOperator.scaledPressureDifference(1,Double.NaN,0)))
			throw new AssertionError("Invalid pressure was hidden");
		near(MarkerPressureOperator.scaledPressureDifference(1,Double.MAX_VALUE,Double.MAX_VALUE),0,0,"Maximum closed constant pressure");
		var d=new FluidDomain(4,4,4,new FluidSolverConfig(1,1000,0,.001F,400,1e-5F,.45F,.002F,.8F),2048);
		d.restoreMaterialParticleState(new FlipMaterialParticleState(4,4,4,1,1,.125,125,
				java.util.List.of(new FluidMaterial("mmm:water",1000,"mmm:water",0,Math.PI/2)),
				new float[]{1.5F,1.5F,1.5F,.125F},new float[3],new byte[64],new double[]{125},new int[]{0}));
		var j=new MarkerVelocityTransfer();j.capture(d);int[][] first=new int[3][],second=new int[3][];var area=faces(j);
		for(int a=0;a<3;a++){first[a]=new int[j.faceCount(a)];second[a]=new int[j.faceCount(a)];Arrays.fill(first[a],-1);Arrays.fill(second[a],-1);}
		int low=d.xFaceIndex(1,1,1),high=d.xFaceIndex(2,1,1);
		first[0][low]=0;second[0][low]=1;first[0][high]=1;second[0][high]=0;area[0][low]=area[0][high]=1;
		var c=new MarkerPressureOperator(j,2,first,second,area,new double[]{0,0});var force=markers(j);
		c.force(new double[]{Double.MAX_VALUE,-Double.MAX_VALUE},force);
		for(double[] axis:force)for(double value:axis)near(value,0,0,"Opposing unrepresentable face forces cancel exactly");
		c.force(new double[]{Double.NaN,0},force);
		if(!Double.isNaN(force[0][0]))throw new AssertionError("Marker cancellation hid invalid pressure");
	}
}
