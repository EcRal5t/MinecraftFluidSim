package mmm.fluid;

import java.util.Arrays;
import mmm.units.SignedDyadic;

/** Actual marker inertia, independent cell-flux assembly, pressure work and finite gas storage. */
public final class MarkerPressureValidation {
	private record Layout(FluidDomain domain, MarkerVelocityTransfer transfer, MarkerPressureOperator operator,
	                      int[] cells) { }
	private MarkerPressureValidation() { }
	public static void main(String[] args) {
		if (args.length == 1 && args[0].equals("--shared-trace")) {
			thinSharedFacePreservesPhysicalTrace();
			System.out.println("MarkerPressureValidation PASS (thin shared trace)");
		} else if (args.length == 1 && args[0].equals("--thin-layer")) {
			thinLayerFitPreservesPhysicalMoments();
			System.out.println("MarkerPressureValidation PASS (thin coupled velocity fit)");
		} else if (args.length == 1 && args[0].equals("--pressure-gauge")) {
			partiallyFilledPressureRetainsReferenceGauge();
			System.out.println("MarkerPressureValidation PASS (partial liquid pressure reference)");
		} else if (args.length == 1 && args[0].equals("--spatial-input")) {
			validateKernel();
			LiquidSpatialInputValidation.validate();
		} else if (args.length == 1 && args[0].equals("--micro-velocity-moments")) {
			MicroFaceVelocityEnrichmentExperiment.validateMomentSupport();
		} else if (args.length == 1 && args[0].equals("--micro-velocity-enrichment")) {
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
		} else if (args.length == 1 && args[0].equals("--chemical-pressure")) {
			fixedWallProjectionUsesActualMass();
			PressureConditionValidation.validateChemicalSource();
		} else if (args.length == 1 && args[0].equals("--kernel")) {
			validateKernel();
			System.out.println("MarkerPressureValidation PASS (kernel; production capillary/wall transport skipped)");
		} else {
			if (args.length != 0) throw new IllegalArgumentException("Unknown marker pressure validation entry");
			validate();
			System.out.println("MarkerPressureValidation PASS");
		}
	}

	/** A pressure reference shift preserves physical force on the admitted partially filled space. */
	private static void partiallyFilledPressureRetainsReferenceGauge() {
		for (int cells = 1; cells <= 2; cells++) partiallyFilledPressureRetainsReferenceGauge(cells);
	}
	private static void partiallyFilledPressureRetainsReferenceGauge(int cells) {
		var points = new float[8 * cells][3];
		for (int p = 0; p < points.length; p++) {
			points[p][0] = .125F + .25F * (p & 1) + .5F * (p / 8);
			points[p][1] = .05F + .10F * (p >> 1 & 1);
			points[p][2] = .125F + .25F * (p >> 2 & 1);
		}
		var d = affineDomain(points);
		d.sealDomainBoundary(); d.bootstrapGasState(null); d.ensureGrid(); d.gasTopologyCoordinator.refresh();
		d.gasState.network.setStandardVolume(1, 1.23456789 * d.gasState.network.availableVolume(1));
		d.bodyForceStage.applyGravity(.001F);
		var plan = d.pressureSystem.markerProjection(); plan.prepare(.001F);
		var basis = plan.basis(); int block = basis.markerBlock(0), cell = basis.physicalCell(block);
		var profile = plan.geometry().cellSnapshot(cell);
		if (!basis.hasRepresentableBasis(block) || profile == null || !(profile.volume() > 0 && profile.volume() < Math.pow(basis.cellSize(), 3))
				|| d.gasState.network.nodeCount() != 1 || d.gasState.network.inventory(1).isEmpty())
			throw new AssertionError("Partial pressure gauge fixture lost its supported liquid and finite gas");
		var constant = new double[plan.operator().rowCount()];
		int liquidRow = plan.rowOfLiquidBlock(block);
		int liquidCount = 0;
		for (int c = 0; c < cells; c++) {
			int row = plan.rowOfLiquidBlock(basis.markerBlock(8 * c));
			if (row >= 0) { constant[row] = 1; liquidCount++; }
		}
		if (cells == 2 && plan.sharedRowEnd()-plan.sharedRowStart() != 3) throw new AssertionError("Adjacent partial cells lost their three shared trace rows");
		for (int row = plan.sharedRowStart(); row < plan.sharedRowEnd(); row += 3) constant[row] = -1;
		constant[constant.length - 1] = 1;
		var initialized = plan.pressure(); double common = initialized[initialized.length - 1];
		if (common == 0) throw new AssertionError("Pressure initializer fixture lost its finite reference rounding difference");
		for (int row = 0; row < constant.length; row++) near(initialized[row], common * constant[row], 0, "Common pressure initialization follows the complete Gauss mode");
		var shiftedForce = new double[3][basis.markerCount()]; plan.operator().force(constant, shiftedForce);
		var bulk = new java.util.ArrayList<CellVelocityConstraintOperator.Block>();
		for (int axis = 0; axis < 3; axis++) {
			var coefficient = new double[4]; coefficient[axis + 1] = profile.volume() / basis.cellSize();
			bulk.add(new CellVelocityConstraintOperator.Block(block, axis, coefficient));
		}
		var bulkOperator = new CellVelocityConstraintOperator(basis, java.util.List.of(new CellVelocityConstraintOperator.Row(bulk, 0)));
		var missingBulkForce = new double[3][basis.markerCount()]; bulkOperator.force(new double[]{liquidRow < 0 ? 1 : 0}, missingBulkForce);
		double magnitude = 0, bulkMagnitude = 0;
		for (int axis = 0; axis < 3; axis++) for (int p = 0; p < basis.markerCount(); p++) {
			near(shiftedForce[axis][p] + missingBulkForce[axis][p], 0, 2e-13,
					"Constant pressure force differs by the omitted partial-liquid volume row");
			magnitude = Math.hypot(magnitude, shiftedForce[axis][p]);
			bulkMagnitude = Math.hypot(bulkMagnitude, missingBulkForce[axis][p]);
		}
		System.out.printf("Partial pressure gauge: cells=%d fraction=%g rank=%d liquidRow=%d constantForceNorm=%g missingVolumeForceNorm=%g deltaForceFor1000Pa=%g N%n",
				cells, profile.volume() / Math.pow(basis.cellSize(), 3), basis.rank(block), liquidRow, magnitude, bulkMagnitude, 1000 * magnitude);
		near(magnitude, 0, 2e-13, "Changing only the pressure reference changed actual carrier force");
		if (!plan.solve(d.config.pressureTolerance()).converged())
			throw new AssertionError("Stationary partial-liquid pressure did not converge");
		plan.publish();
		for (int c = 0; c < cells; c++) near(d.pressure(c, 0, 0), d.projection.pressure[basis.physicalCell(basis.markerBlock(8 * c))] + d.pressureReference, 0,
				"Partial-liquid public pressure includes its reference");
		near(d.maximumPressure(), d.pressure(0, 0, 0), 0, "Maximum pressure includes partial-liquid observation");
	}
	public static void validate() {
		validateKernel();
		MarkerVelocitySpaceExperiment.validateProduction();
		unresolvedCapillaryTractionIsAppliedOnce();
		transportPreservesFixedWallVelocity();
	}
	private static void validateKernel() {
		partiallyFilledPressureRetainsReferenceGauge();
		pressureUsesCurrentMicroGasGeometry();
		cellAffineVelocityPreservesResolvedMotion();
		cellWetFaceAndConstraintWork();
		thinSharedFacePreservesPhysicalTrace();
		sharedTraceMassConditioning();
		fixedWallProjectionUsesActualMass();
		scaledClosedRotationRowsKeepKernel();
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
		d.gasState.network.setStandardVolume(second, 0);
		plan.prepare(.001F);
		near(d.pressureReference, -101325, 0, "Vacuum pressure reference");
		if (!plan.solve(d.config.pressureTolerance()).converged()) throw new AssertionError("Finite gas and vacuum pressure failed to solve");
		plan.publish();
		near(d.pressureSystem.gasPressure(first) + d.pressureReference, p1, 1e-3, "Finite-gas initial pressure survives the common-pressure solve");
		near(d.pressureSystem.gasPressure(second) + d.pressureReference, -101325, 1e-3, "Vacuum compartment retains its own physical pressure");
		near(d.maximumParticleSpeed(), 0, 1e-6, "Common gas pressure initialization preserves stationary compartments");
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
		var geometry=LiquidPressureGeometry.capture(d); var basis = CellAffineVelocity.capture(d,geometry); var wall = new CellAffineWallProjection(basis);
		int n = basis.markerCount(); var before = new double[3][n]; var after = new double[3][n]; var impulse = new double[3][n];
		for (int i = 0; i < n; i++) {
			before[0][i] = Math.sin(.3 * i + .2);
			before[1][i] = 2 + 3 * (basis.position(i, 1) - .5) + 4 * (basis.position(i, 0) - 1.5);
			before[2][i] = Math.cos(.4 * i);
		}
		var features=new double[n][4]; var gram=new double[4][4]; var right=new double[4];
		for(int i=0;i<n;i++) {
			double y=basis.position(i,1);
			features[i]=new double[]{y,y*(basis.position(i,0)-1.5),y*(y-.5),y*(basis.position(i,2)-1.5)};
			for(int a=0;a<4;a++) {
				right[a]+=basis.massKilograms(i)*features[i][a]*before[1][i];
				for(int b=0;b<4;b++) gram[a][b]+=basis.massKilograms(i)*features[i][a]*features[i][b];
			}
		}
		var reference=MarkerVelocitySpaceExperiment.solve(gram,right);
		wall.project(before, after, impulse); double removed = 0, energyChange = 0;
		for (int i = 0; i < n; i++) {
			double expected=0; for(int term=0;term<4;term++) expected+=features[i][term]*reference[term];
			near(after[1][i], expected, 2e-14, "Independent actual-mass wall-admissible normal mode");
			for (int axis = 0; axis < 3; axis++) {
				double delta = before[axis][i] - after[axis][i];
				removed += basis.massKilograms(i) * delta * delta;
				energyChange += basis.massKilograms(i) * (before[axis][i] * before[axis][i] - after[axis][i] * after[axis][i]);
				near(impulse[axis][i], axis == 1 ? -basis.massKilograms(i) * delta : 0, axis == 1 ? 2e-13 : 0,
						"Wall impulse contains only constrained normal response");
			}
		}
		near(energyChange, removed, 2e-12, "Mass-orthogonal wall projection work");
		near(wall.normalWork() + wall.representationWork(), -.5 * energyChange, 2e-12,
				"Wall and representation work sum to the actual total kinetic change");
		for (int mode = 0; mode < basis.modeCount(basis.markerBlock(0)); mode++) {
			int block = basis.markerBlock(0); var direction = new double[3][4 * basis.blockCount()];
			for (int axis = 0; axis < 3; axis++) for (int term = 0; term < 4; term++)
				direction[axis][4 * block + term] = basis.modeCoefficientExact(block, mode, axis, term).toDouble();
			var sampled = new double[3][n]; basis.predict(direction, sampled); double virtualWork = 0;
			for (int axis = 0; axis < 3; axis++) for (int marker = 0; marker < n; marker++)
				virtualWork += basis.massKilograms(marker) * (before[axis][marker] - after[axis][marker]) * sampled[axis][marker];
			near(virtualWork, 0, 2e-12, "Wall projection residual is orthogonal to every admitted coupled mode");
		}
		var twice = new double[3][n]; wall.project(after, twice, impulse);
		for (int axis = 0; axis < 3; axis++) for (int i = 0; i < n; i++) near(twice[axis][i], after[axis][i], 2e-14, "Fixed-wall projection idempotence");
		meanCompleteLocalPressureUsesActualMass(d, geometry);
	}
	private static void meanCompleteLocalPressureUsesActualMass(FluidDomain d, LiquidPressureGeometry geometry) {
		for (int i = 0; i < d.particleCount(); i++) d.particles.setMass(i, d.particles.mass(i) * (1 + .125 * i));
		var basis = CellAffineVelocity.capture(d, geometry); int block = basis.markerBlock(0);
		if (!basis.hasRepresentableBasis(block) || !basis.hasNormalShape(block, 1) || basis.normalShape(block, 1)[2] != 0)
			throw new AssertionError("Mean/internal matrix lost its single-wall velocity space");
		var mean = new java.util.ArrayList<CellVelocityConstraintOperator.Block>();
		mean.add(new CellVelocityConstraintOperator.Block(block, 0, new double[]{0, 1, 0, 0}));
		mean.add(new CellVelocityConstraintOperator.Block(block, 1, new double[]{1, 1.5 - basis.center(block, 0), 1 - basis.center(block, 1), 1.5 - basis.center(block, 2)}));
		mean.add(new CellVelocityConstraintOperator.Block(block, 2, new double[]{0, 0, 0, 1}));
		var definitions = new java.util.ArrayList<CellVelocityConstraintOperator.Row>();
		definitions.add(new CellVelocityConstraintOperator.Row(mean, 0));
		for (int term = 1; term < 4; term++) {
			double[] coefficient = new double[4]; coefficient[term] = term == 2 ? 2 : 1;
			definitions.add(new CellVelocityConstraintOperator.Row(java.util.List.of(new CellVelocityConstraintOperator.Block(block, 1, coefficient)), 0));
		}
		var operator = new CellVelocityConstraintOperator(basis, definitions); var reference = new double[4][4];
		var velocity = new double[3][basis.markerCount()];
		for (int axis = 0; axis < 3; axis++) for (int marker = 0; marker < basis.markerCount(); marker++) velocity[axis][marker] = Math.sin(axis + .3 * marker);
		verifyFactoredConstraint(basis, definitions, velocity, new double[]{.7, -1.3, .25, -.5});
		// Integrate the physical q-aware source features independently of the stored fit and its pullback.
		for (int axis = 0; axis < 3; axis++) {
			var massGram = new double[4][4]; var derivative = new double[4][4];
			for (int i = 0; i < basis.markerCount(); i++) {
				double[] feature = new double[4];
				basis.pointWeights(block, basis.position(i, 0), basis.position(i, 1), basis.position(i, 2), feature);
				double q = axis == 1 ? basis.position(i, 1) : 1;
				for (int a = 0; a < 4; a++) for (int b = 0; b < 4; b++) massGram[a][b] += basis.massKilograms(i) * q * q * feature[a] * feature[b];
			}
			for (int row = 0; row < 4; row++) for (var part : definitions.get(row).blocks())
				if (part.axis() == axis) derivative[row] = part.coefficients();
			for (int col = 0; col < 4; col++) {
				double[] resolved = MarkerVelocitySpaceExperiment.solve(massGram, derivative[col]);
				for (int row = 0; row < 4; row++) reference[row][col] += dot(derivative[row], resolved);
			}
		}
		double[] diagonal = new double[4], unit = new double[4], applied = new double[4]; operator.diagonal(diagonal);
		for (int col = 0; col < 4; col++) {
			Arrays.fill(unit, 0); unit[col] = 1; operator.apply(unit, applied);
			for (int row = 0; row < 4; row++) near(applied[row], reference[row][col], 2e-12, "Independent mean/internal actual-mass Gram");
		}
		var preconditioner = operator.preconditioner(diagonal, new double[4], java.util.List.of(new int[]{0, 1, 2, 3}));
		double[] first = {2, -3, .7, 1.1}, second = {-.7, 1.1, 2, -3}, firstResult = new double[4], secondResult = new double[4];
		preconditioner.apply(first, firstResult); preconditioner.apply(second, secondResult);
		double[] expected = MarkerVelocitySpaceExperiment.solve(reference, first);
		for (int row = 0; row < 4; row++) near(firstResult[row], expected[row], 2e-10, "Independent complete local divergence inverse");
		near(dot(first, secondResult), dot(second, firstResult), 2e-10, "Mean/internal balanced symmetry");
		if (!(dot(first, firstResult) > 0)) throw new AssertionError("Mean/internal inverse lost positive actual-mass work");
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
		var coefficients = new double[3][4 * basis.blockCount()]; coefficients[0][4 * left] = 2; coefficients[0][4 * right] = 4;
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
		var cellLoad = new double[3][4 * basis.blockCount()]; transfer.transpose(faceLoad, cellLoad);
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
		verifyFactoredConstraint(basis, definitions, velocity, pressure);
		var force = new double[3][count]; constraint.force(pressure, force);
		near(rate[0] * pressure[0] + rate[1] * pressure[1], work(velocity, force), 2e-12, "Constraint and actual-marker force work");
		double[] applied = new double[2]; double positiveWork=constraint.applyWithWork(pressure, applied); double kinetic = 0;
		for (int axis = 0; axis < 3; axis++) for (int i = 0; i < count; i++) kinetic += force[axis][i] * force[axis][i] / basis.massKilograms(i);
		near(pressure[0] * applied[0] + pressure[1] * applied[1], kinetic + .125 * pressure[1] * pressure[1], 2e-12, "Actual mass Gram and gas storage");
		near(positiveWork,kinetic+.125*pressure[1]*pressure[1],2e-12,"Positive actual-mass pressure work");
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
		double[] commonMode = {1, -1}; var balanced = constraint.preconditioner(diagonal, commonMode);
		balanced.apply(first, firstResult); balanced.apply(second, secondResult);
		near(dot(first, secondResult), dot(second, firstResult), 2e-12, "Balanced common-pressure symmetry");
		if (!(dot(first, firstResult) > 0)) throw new AssertionError("Balanced common-pressure inverse lost positive work");
		constraint.apply(commonMode, applied); balanced.apply(applied, firstResult);
		for (int row = 0; row < 2; row++) near(firstResult[row], commonMode[row], 2e-12, "Exact common-pressure coarse solve");
		var incompressible = new CellVelocityConstraintOperator(basis, java.util.List.of(definitions.get(0),
				new CellVelocityConstraintOperator.Row(definitions.get(1).blocks(), 0)));
		incompressible.diagonal(diagonal); incompressible.preconditioner(diagonal).apply(first, firstResult);
		incompressible.preconditioner(diagonal, commonMode).apply(first, secondResult);
		for (int row = 0; row < 2; row++) near(firstResult[row], secondResult[row], 0, "Zero-storage common mode keeps SGS");
		var local = incompressible.preconditioner(diagonal, new double[2], java.util.List.of(new int[]{0, 1}));
		local.apply(first, firstResult); local.apply(second, secondResult);
		near(dot(first, secondResult), dot(second, firstResult), 2e-12, "Balanced local pressure symmetry");
		if (!(dot(first, firstResult) > 0)) throw new AssertionError("Balanced local pressure lost positive work");
		for (int row = 0; row < 2; row++) {
			Arrays.fill(unit, 0); unit[row] = 1; incompressible.apply(unit, applied); local.apply(applied, firstResult);
			for (int other = 0; other < 2; other++) near(firstResult[other], unit[other], 2e-12, "Local coarse pressure exact response");
		}
	}

	static void cellAffineVelocityPreservesResolvedMotion() {
		float[][] cube = new float[8][3];
		for (int i = 0; i < 8; i++) for (int a = 0; a < 3; a++) cube[i][a] = (i >> a & 1) == 0 ? .125F : .375F;
		FluidDomain domain = affineDomain(cube); CellAffineVelocity basis = CellAffineVelocity.capture(domain);
		if (!basis.hasRepresentableBasis(0) || !basis.canRepresentPoint(0, .5, .5, .5))
			throw new AssertionError("Full spatial quadrature lost its affine velocity capacity");
		verifyConstraintRowRelations(basis, 0);
		verifyAffineDotRounding(basis);
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
		var velocity = new double[3][cube.length]; var load = new double[3][4 * basis.blockCount()];
		for (int a = 0; a < 3; a++) {
			for (int i = 0; i < cube.length; i++) velocity[a][i] = Math.sin(.37 * i + a);
			for (int term = 0; term < 4; term++) load[a][term] = Math.cos(.23 * term + a);
		}
		var fitted = new double[3][4 * basis.blockCount()]; var force = new double[3][cube.length]; basis.fit(velocity, fitted); basis.transposeLoad(load, force);
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
			var sparseDomain = affineDomain(sparse[k]); var lowRank = CellAffineVelocity.capture(sparseDomain);
			if (lowRank.rank(0) != k + 1 || !lowRank.hasRepresentableBasis(0)
					|| !lowRank.canRepresentPoint(0, sparse[k][0][0], sparse[k][0][1], sparse[k][0][2])
					|| lowRank.canRepresentPoint(0, .125, .125, k == 2 ? Math.nextUp(.25) : .375))
				throw new AssertionError("Sparse actual geometry was assigned an invented affine direction");
			verifyAffineRigidMotion(lowRank);
			if (k == 0) verifySparsePressureBasis(lowRank);
			sparseDomain.sealDomainBoundary(); sparseDomain.bootstrapGasState(null); sparseDomain.ensureGrid();
			sparseDomain.gasTopologyCoordinator.refresh(); sparseDomain.bodyForceStage.applyGravity(.001F);
			var sparsePlan = sparseDomain.pressureSystem.markerProjection(); sparsePlan.prepare(.001F);
			var common = new double[sparsePlan.operator().rowCount()];
			for(int block=0;block<sparsePlan.basis().blockCount();block++) {
				int row=sparsePlan.rowOfLiquidBlock(block); if(row>=0)common[row]=1;
			}
			for(int row=sparsePlan.sharedRowStart();row<sparsePlan.sharedRowEnd();row+=3)common[row]=-1;
			common[common.length-1]=1;
			var commonForce = new double[3][lowRank.markerCount()]; sparsePlan.operator().force(common, commonForce);
			for (double[] axis : commonForce) for (double value : axis) near(value, 0, 2e-13, "Sparse production Gauss force");
			if (!sparsePlan.solve(sparseDomain.config.pressureTolerance()).converged())
				throw new AssertionError("Stationary restricted-hull pressure did not converge at rank " + lowRank.rank(0));
		}
		thinLayerFitPreservesPhysicalMoments();
		double savedMass = basis.massKilograms(0), savedCentre = basis.center(0, 0);
		var savedCoefficient = basis.coefficientExact(0, 0, 0, 0);
		domain.particles.setMass(0, 200); domain.particles.setPositionX(0, .25F);
		near(basis.massKilograms(0), savedMass, 0, "Captured affine mass ownership");
		near(basis.center(0, 0), savedCentre, 0, "Captured affine centre ownership");
		if (!basis.coefficientExact(0, 0, 0, 0).equals(savedCoefficient))
			throw new AssertionError("Captured coupled affine map changed with its source");
	}
	private static void verifyAffineDotRounding(CellAffineVelocity basis) {
		var velocity = new double[3][basis.markerCount()]; var fitted = new double[3][4 * basis.blockCount()];
		var load = new double[3][4 * basis.blockCount()]; var force = new double[3][basis.markerCount()];
		for (int mode = 0; mode < 2; mode++) {
			for (int axis = 0; axis < 3; axis++) {
				for (int i = 0; i < basis.markerCount(); i++) velocity[axis][i] = mode == 0 ? 1 : Math.scalb(Math.sin(.37 * i + axis), 27);
				for (int i = 0; i < load[axis].length; i++) load[axis][i] = Math.scalb(Math.cos(.23 * i + axis), 27);
			}
			basis.fit(velocity, fitted); basis.transposeLoad(load, force);
			for (int block = 0; block < basis.blockCount(); block++) if (basis.blockMarkerCount(block) > 0)
				for (int outputAxis = 0; outputAxis < 3; outputAxis++) for (int term = 0; term < 4; term++) {
					var sum = SignedDyadic.ZERO;
					for (int i = 0; i < basis.blockMarkerCount(block); i++) {
						int marker = basis.blockMarker(block, i);
						for (int inputAxis = 0; inputAxis < 3; inputAxis++)
							sum = sum.add(basis.coefficientExact(marker, outputAxis, inputAxis, term)
								.multiply(SignedDyadic.of(velocity[inputAxis][marker])));
					}
					near(fitted[outputAxis][4 * block + term], sum.toDouble(), 0, "Coupled affine fit exact dot");
					if (mode == 0 && term > 0) near(fitted[outputAxis][4 * block + term], 0, 0, "Constant velocity has exactly zero gradient");
				}
			for (int marker = 0; marker < basis.markerCount(); marker++) for (int inputAxis = 0; inputAxis < 3; inputAxis++) {
				var sum = SignedDyadic.ZERO;
				for (int outputAxis = 0; outputAxis < 3; outputAxis++) for (int term = 0; term < 4; term++)
					sum = sum.add(basis.coefficientExact(marker, outputAxis, inputAxis, term)
							.multiply(SignedDyadic.of(load[outputAxis][4 * basis.markerBlock(marker) + term])));
				near(force[inputAxis][marker], sum.toDouble(), 0, "Coupled affine transpose exact dot");
			}
		}
	}
	static void thinLayerFitPreservesPhysicalMoments() {
		for (int normal = 0; normal < 3; normal++) for (float half : new float[]{.125F, 0x1p-22F, 0}) {
			var points = new float[8][3];
			for (int p = 0; p < points.length; p++) for (int axis = 0; axis < 3; axis++)
				points[p][axis] = .25F + ((p >>> axis & 1) == 0 ? -1 : 1) * (axis == normal ? half : .125F);
			var basis = CellAffineVelocity.capture(affineDomain(points)); int block = basis.markerBlock(0);
			if (!basis.hasRepresentableBasis(block)) throw new AssertionError("Thin source lost its actual velocity support");
			if (half < .125F && basis.reliableRank(block) != 2)
				throw new AssertionError("Storage-scale normal thickness retained an unreliable strain direction");
			verifyAffineDotRounding(basis); verifyAffineRigidMotion(basis);
			for (int mode = 0; mode < 6; mode++) {
				var input = new double[3][basis.markerCount()];
				for (int p = 0; p < basis.markerCount(); p++) {
					for (int axis = 0; axis < 3; axis++) input[axis][p] = mode == 0 ? new double[]{1.25, -.75, .125}[axis]
							: mode <= 3 ? (axis == mode % 3 ? -(basis.position(p, (axis + 1) % 3) - basis.center(block, (axis + 1) % 3))
							: axis == (mode + 1) % 3 ? basis.position(p, (axis + 2) % 3) - basis.center(block, (axis + 2) % 3) : 0)
							: axis == normal ? ((p >>> normal & 1) == 0 ? -1e-4 : 1e-4) : mode == 5 ? .2 * (basis.position(p, axis) - basis.center(block, axis)) : 0;
				}
				var coefficient = new double[3][4 * basis.blockCount()]; var output = new double[3][basis.markerCount()];
				basis.fit(input, coefficient); basis.predict(coefficient, output);
				var before = mechanicalMoments(basis, input); var after = mechanicalMoments(basis, output);
				for (int quantity = 0; quantity < 6; quantity++) {
					double budget = 128 * basis.markerCount() * Math.ulp(Math.max(1, Math.max(Math.abs(before[quantity]), Math.abs(after[quantity]))));
					near(after[quantity], before[quantity], budget, "Thin fitted actual momentum/angular momentum");
				}
				double energyBudget = 128 * basis.markerCount() * Math.ulp(Math.max(1, before[6]));
				if (after[6] > before[6] + energyBudget) throw new AssertionError("Thin fitted velocity increased actual kinetic energy");
				if (mode == 0) for (int axis = 0; axis < 3; axis++) for (int term = 1; term < 4; term++)
					near(coefficient[axis][4 * block + term], 0, 0, "Thin constant velocity generated a gradient");
				if (half < .125F && mode >= 4) {
					double speed = 0; for (double[] component : input) for (double value : component) speed = Math.max(speed, Math.abs(value));
					double resolvedScale = .125 / basis.cellSize();
					if (Math.abs(coefficient[normal][4 * block + normal + 1]) > 2 * speed / resolvedScale)
						throw new AssertionError("Thin normal strain grew with the inverse layer thickness");
				}
				var row = new CellVelocityConstraintOperator.Row(java.util.List.of(
						new CellVelocityConstraintOperator.Block(block, normal, new double[]{.7, -.3, .4, .2})), .125);
				verifyFactoredConstraint(basis, java.util.List.of(row), input, new double[]{.75});
			}
			System.out.printf("Thin coupled fit normal=%d half=%g rawRank=%d reliableRank=%d modes=%d%n",
					normal, half, basis.rank(block), basis.reliableRank(block), basis.modeCount(block));
		}
	}
	private static double[] mechanicalMoments(CellAffineVelocity basis, double[][] velocity) {
		var result = new double[7];
		for (int p = 0; p < basis.markerCount(); p++) for (int axis = 0; axis < 3; axis++) {
			double m = basis.massKilograms(p), v = velocity[axis][p];
			result[axis] += m * v; result[6] += .5 * m * v * v;
			int next = (axis + 1) % 3, last = (axis + 2) % 3;
			result[3 + axis] += m * (basis.position(p, next) * velocity[last][p] - basis.position(p, last) * velocity[next][p]);
		}
		return result;
	}

	private static void verifyConstraintRowRelations(CellAffineVelocity basis, int block) {
		for (int axis = 0; axis < 3; axis++) for (int term = 0; term < 4; term++) {
			double coefficient = 1 + 0x1.0p-49;
			var rows = new java.util.ArrayList<CellVelocityConstraintOperator.Row>();
			for (int multiple = 1; multiple <= 3; multiple++) {
				var declared = new double[4]; declared[term] = multiple * coefficient;
				rows.add(new CellVelocityConstraintOperator.Row(java.util.List.of(
						new CellVelocityConstraintOperator.Block(block, axis, declared)), 0));
			}
			var operator = new CellVelocityConstraintOperator(basis, rows); var coordinates = operator.rowBasis();
			if (coordinates.size() > 0 && (coordinates.size() != 1 || coordinates.dependencyCount() != 2))
				throw new AssertionError("Declared pressure row relations were not retained by the physical mode basis");
			var force = new double[3][basis.markerCount()]; var image = new double[3];
			operator.force(new double[]{1, 1, -1}, force);
			for (double[] component : force) for (double value : component) near(value, 0, 0, "Exact declared pressure row relation");
			near(operator.applyWithWork(new double[]{1, 1, -1}, image), 0, 0, "Dependent pressure rows have zero mass work");
			for (double value : image) near(value, 0, 0, "Dependent pressure rows have zero operator image");
			var velocity = new double[3][basis.markerCount()];
			for (int a = 0; a < 3; a++) for (int marker = 0; marker < basis.markerCount(); marker++) velocity[a][marker] = Math.sin(a + .3 * marker);
			verifyFactoredConstraint(basis, rows, velocity, new double[]{.7, -1.3, .25});
			float dt = .03F; var inverseDt = SignedDyadic.of(1.0 / (double) dt);
			var source = operator.referencedSource(velocity, dt, new double[3], new double[3]);
			if (!source[0].add(source[1]).equals(source[2])) throw new AssertionError("Common predictor source broke an exact pressure row relation");
			int otherTerm = (term + 1) % 4; double epsilon = 0x1.0p-50;
			var perturbed = new double[4]; perturbed[term] = 3 * coefficient; perturbed[otherTerm] = epsilon;
			var nearRows = new java.util.ArrayList<>(rows);
			nearRows.set(2, new CellVelocityConstraintOperator.Row(java.util.List.of(new CellVelocityConstraintOperator.Block(block, axis, perturbed)), 0));
			var nearOperator = new CellVelocityConstraintOperator(basis, nearRows);
			var nearSource = nearOperator.referencedSource(velocity, dt, new double[3], new double[3]);
			var sourceRelation = SignedDyadic.ZERO;
			for (int marker = 0; marker < basis.markerCount(); marker++) if (basis.markerBlock(marker) == block)
				for (int inputAxis = 0; inputAxis < 3; inputAxis++) sourceRelation = sourceRelation.add(
						basis.coefficientExact(marker, axis, inputAxis, otherTerm).multiply(SignedDyadic.of(velocity[inputAxis][marker])));
			sourceRelation = sourceRelation.multiply(SignedDyadic.of(epsilon)).multiply(inverseDt);
			if (!nearSource[0].add(nearSource[1]).subtract(nearSource[2]).equals(sourceRelation))
				throw new AssertionError("Common predictor source lost its independent physical row component");
			operator.force(new double[]{1, 0, 0}, force);
			for (int marker = 0; marker < basis.markerCount(); marker++) for (int inputAxis = 0; inputAxis < 3; inputAxis++) {
				var expected = basis.markerBlock(marker) == block ? basis.coefficientExact(marker, axis, inputAxis, term)
						.multiply(SignedDyadic.of(coefficient)) : SignedDyadic.ZERO;
				near(force[inputAxis][marker], expected.toDouble(), 0, "Full coupled constraint transpose");
			}
		}
	}

	private static void verifySparsePressureBasis(CellAffineVelocity basis) {
		// A modularly invisible component can change an earlier exact pivot and add a later independent row.
		long prime = 2147483647L;
		long[][] values = {{prime, 0, 1}, {1, 1, prime}, {prime + 2, 2, 1 + 2 * prime}, {0, prime, 0}, {prime + 1, prime + 1, prime + 1}, {0, 0, 0}};
		int[] starts = new int[values.length + 1]; var sparseIndices = new java.util.ArrayList<Integer>(); var sparseValues = new java.util.ArrayList<SignedDyadic>();
		var inertia = new SignedDyadic[][]{new SignedDyadic[9]}; Arrays.fill(inertia[0], SignedDyadic.ZERO);
		for (int axis = 0; axis < 3; axis++) inertia[0][4 * axis] = SignedDyadic.of(1);
		for (int row = 0; row < values.length; row++) {
			for (int axis = 0; axis < 3; axis++) if (values[row][axis] != 0) {
				sparseIndices.add(axis); sparseValues.add(SignedDyadic.of(values[row][axis]));
			}
			if (row == values.length - 1) { sparseIndices.add(0); sparseValues.add(SignedDyadic.ZERO); }
			starts[row + 1] = sparseValues.size();
		}
		var modular = new PressureRowBasis(starts, sparseIndices.stream().mapToInt(Integer::intValue).toArray(),
				sparseValues.toArray(SignedDyadic[]::new), inertia, 3, new double[values.length], new double[]{5, 4, 3, 2, 1, 0});
		if (modular.size() != 3 || modular.dependencyCount() != 3 || modular.position(3) < 0)
			throw new AssertionError("Prime-hidden pressure row lost its independent exact component");
		var exactRate = new double[values.length]; var exactSource = new double[values.length];
		for (int row = 0; row < values.length; row++) {
			for (int axis = 0; axis < 3; axis++) exactRate[row] += values[row][axis] * (axis + 1.0);
			exactSource[row] = -exactRate[row];
		}
		if (!modular.compatibleSource(exactSource, exactRate, 1))
			throw new AssertionError("Nonmonotone exact pivots changed a compatible full-column source");
		exactSource[2] += 1; exactRate[2] -= 1;
		if (modular.compatibleSource(exactSource, exactRate, 1))
			throw new AssertionError("Replayed pressure dependency accepted incompatible physical source");
		// The modular relation uses only row 0; its exact completion also needs selected row 1.
		var local = new PressureRowBasis(new int[]{0, 2, 3, 4}, new int[]{0, 1, 1, 0},
				new SignedDyadic[]{SignedDyadic.of(1), SignedDyadic.of(prime), SignedDyadic.of(1), SignedDyadic.of(1)},
				new SignedDyadic[][]{{SignedDyadic.of(1), SignedDyadic.ZERO, SignedDyadic.ZERO, SignedDyadic.of(1)}},
				2, new double[3], new double[]{3, 2, 1});
		var localRate = new double[]{1 + 2 * prime, 2, 1}; var localSource = new double[]{-localRate[0], -2, -1};
		if (local.size() != 2 || local.dependencyCount() != 1 || !local.compatibleSource(localSource, localRate, 1))
			throw new AssertionError("Local modular support failed to expand to its complete exact pressure relation");
		localRate[2] += 1; localSource[2] -= 1;
		if (local.compatibleSource(localSource, localRate, 1))
			throw new AssertionError("Expanded local pressure relation accepted an incompatible full-column source");
		int block = basis.markerBlock(0);
		var definitions = java.util.List.of(
				new CellVelocityConstraintOperator.Row(java.util.List.of(new CellVelocityConstraintOperator.Block(block, 0, new double[]{1, 0, 0, 0})), 0),
				new CellVelocityConstraintOperator.Row(java.util.List.of(new CellVelocityConstraintOperator.Block(block, 0, new double[]{2, 0, 0, 0})), 0),
				new CellVelocityConstraintOperator.Row(java.util.List.of(new CellVelocityConstraintOperator.Block(block, 0, new double[]{0, 1, 0, 0})), .25));
		var operator = new CellVelocityConstraintOperator(basis, definitions); var coordinates = operator.rowBasis();
		if (coordinates.size() != 2 || coordinates.dependencyCount() != 1)
			throw new AssertionError("Restricted actual inertia and positive storage lost their exact pressure rank");
		if (coordinates.position(0) >= 0 || coordinates.position(1) < 0 || coordinates.position(2) < 0)
			throw new AssertionError("Low-rank pressure coordinates lost the stronger row or positive storage");
		var velocity = new double[3][basis.markerCount()]; Arrays.fill(velocity[0], .125);
		verifyFactoredConstraint(basis, definitions, velocity, new double[]{1, -2, 3});
		double[] rhs = new double[3], diagonal = new double[3], pressure = {7, 8, 9}; float dt = .05F;
		operator.volumeRate(velocity, rhs); for (int row = 0; row < 3; row++) rhs[row] = -rhs[row] / dt; operator.diagonal(diagonal);
		var direct = SparsePressureFactor.solve(operator, rhs, pressure, diagonal, new boolean[3], false, velocity, dt, 1e-9, 64);
		if (!direct.converged() || direct.iterations() != 1 || direct.residualRms() > 1e-9)
			throw new AssertionError("Certified low-rank principal LU did not satisfy every physical row");
		double[] actual = new double[3]; operator.apply(pressure, actual); for (int row = 0; row < 3; row++) near(actual[row], rhs[row], 1e-9, "Full-row direct pressure residual");
		double[] saved = pressure.clone();
		double[] savedSource = rhs.clone();
		var settled = SparsePressureFactor.solve(operator, rhs, pressure, diagonal, new boolean[3], false, velocity, dt, 1e-9, 64);
		if (!settled.converged() || settled.iterations() != 0 || !Arrays.equals(saved, pressure))
			throw new AssertionError("Certified converged pressure spent another direct solve or changed its reference");
		if (direct.residualRms() > Double.MIN_VALUE) {
			var warmSeed = new double[]{7, 8, 9}; var warmLow = new double[3]; var seedSource = new SignedDyadic[3];
			for (int row = 0; row < 3; row++) seedSource[row] = SignedDyadic.of(rhs[row]);
			operator.residual(warmSeed, seedSource, actual); double before = 0;
			for (double value : actual) before = Math.hypot(before, value); before /= Math.sqrt(3);
			var incomplete = SparsePressureFactor.solve(operator, seedSource, warmSeed, warmLow, diagonal, new boolean[3], false, velocity, dt,
					Math.nextDown(direct.residualRms()), 1, rhs);
			operator.residual(warmSeed, warmLow, seedSource, actual); double after = 0;
			for (double value : actual) after = Math.hypot(after, value); after /= Math.sqrt(3);
			if (incomplete.converged() || incomplete.iterations() != 1 || !(after < before) || after != incomplete.residualRms())
				throw new AssertionError("Incomplete direct solve lost its certified improving full-row PCG seed or budget");
		}
		var pressureLow = new double[3]; var exactRhs = new SignedDyadic[3];
		for (int row = 0; row < 3; row++) { pressureLow[row] = Math.ulp(pressure[row]) / 4; exactRhs[row] = SignedDyadic.of(rhs[row]); }
		var savedLow = pressureLow.clone();
		var noBudget = SparsePressureFactor.solve(operator, exactRhs, pressure, pressureLow, diagonal, new boolean[3], false, velocity, dt, 1e-9, 0, rhs);
		if (noBudget.iterations() != 0 || noBudget.converged() || !Arrays.equals(saved, pressure) || !Arrays.equals(savedLow, pressureLow) || !Arrays.equals(savedSource, rhs))
			throw new AssertionError("Zero direct solve budget changed the same-source iterative fallback");
		PressureRowBasis.withWorkLimitForValidation(0, () -> {
			if (SparsePressureFactor.solve(operator, exactRhs, pressure, pressureLow, diagonal, new boolean[3], false, velocity, dt, 1e-9, 64, rhs).converged()
					|| !Arrays.equals(saved, pressure) || !Arrays.equals(savedLow, pressureLow) || !Arrays.equals(savedSource, rhs))
				throw new AssertionError("Unavailable exact work budget changed the iterative fallback pressure or source");
		});
		if (SparsePressureFactor.solve(operator, exactRhs, pressure, pressureLow, diagonal, new boolean[]{true, false, false}, false, velocity, dt, 1e-9, 64, rhs).converged())
			throw new AssertionError("An observation-changing multiplier relation selected an arbitrary gauge");
		if (!Arrays.equals(saved, pressure) || !Arrays.equals(savedLow, pressureLow)) throw new AssertionError("Rejected pressure gauge changed the iterative fallback input");
		rhs[1] += 1; exactRhs[1] = SignedDyadic.of(rhs[1]);
		if (SparsePressureFactor.solve(operator, exactRhs, pressure, pressureLow, diagonal, new boolean[3], false, velocity, dt, 1e-9, 64, rhs).converged()
				|| !Arrays.equals(saved, pressure) || !Arrays.equals(savedLow, pressureLow))
			throw new AssertionError("Incompatible source changed the iterative fallback input");
		if (coordinates.observationNeutral(new boolean[]{true, true, false}, diagonal, true))
			throw new AssertionError("A nonuniform pressure dependency changed the closed observation gauge");
		var closed = new CellVelocityConstraintOperator(basis, java.util.List.of(definitions.getFirst(),
				new CellVelocityConstraintOperator.Row(java.util.List.of(new CellVelocityConstraintOperator.Block(block, 0, new double[]{-1, 0, 0, 0})), 0),
				definitions.get(2)));
		closed.volumeRate(velocity, rhs); for (int row = 0; row < 3; row++) rhs[row] = -rhs[row] / dt; closed.diagonal(diagonal);
		if (!SparsePressureFactor.solve(closed, rhs, pressure, diagonal, new boolean[]{true, true, false}, true, velocity, dt, 1e-9, 64).converged())
			throw new AssertionError("A uniform closed pressure gauge prevented the certified physical solve");
		near(pressure[0] - pressure[1], rhs[0] / diagonal[0], 1e-9, "Closed pressure difference is independent of the common gauge");
		var coupled = new CellVelocityConstraintOperator(basis, java.util.List.of(
				new CellVelocityConstraintOperator.Row(definitions.getFirst().blocks(), 88182.2),
				new CellVelocityConstraintOperator.Row(java.util.List.of(new CellVelocityConstraintOperator.Block(block, 0, new double[]{-1, 0, 0, 0})), 88182.2)));
		var initial = new double[]{71915.1, 71915.1}; var correction = new double[2]; var correctionLow = new double[2]; var rate = new double[2]; var nativeSource = new double[2];
		Arrays.fill(velocity[0], 1e-11); coupled.volumeRate(velocity, rate); float coupledDt = 8.60585e-5F;
		var source = coupled.referencedSource(velocity, coupledDt, initial, initial);
		for (int row = 0; row < 2; row++) nativeSource[row] = 88182.2 * initial[row] - rate[row] / coupledDt;
		if (nativeSource[0] != nativeSource[1]) throw new AssertionError("Gas storage fixture lost its sub-ULP physical source");
		var coupledDiagonal = new double[2]; coupled.diagonal(coupledDiagonal);
		boolean aliased = false; var beforeAlias = correction.clone();
		try { SparsePressureFactor.solve(coupled, source, correction, correction, coupledDiagonal, new boolean[]{true, true}, false,
				velocity, coupledDt, 1e-16, 0, nativeSource); }
		catch (IllegalArgumentException alias) { aliased = alias.getMessage().contains("Aliased pressure accumulator"); }
		if (!aliased || !Arrays.equals(beforeAlias, correction)) throw new AssertionError("Aliased pressure accumulators changed the fallback source");
		if (!SparsePressureFactor.solve(coupled, source, correction, correctionLow, coupledDiagonal, new boolean[]{true, true}, false,
				velocity, coupledDt, 1e-16, 64, nativeSource).converged()) throw new AssertionError("Referenced gas storage lost its small pressure response");
		var residual = new double[2]; coupled.residual(correction, correctionLow, source, residual);
		for (double value : residual) near(value, 0, 1e-16, "Exact referenced gas storage residual");
		if (!(correction[0] < 0 && correction[1] > 0) || initial[0] + correction[0] != initial[0]
				|| initial[1] + correction[1] != initial[1]) throw new AssertionError("Gas storage lost its sub-ULP pressure increment");
		var pairedForce = new double[3][basis.markerCount()]; var incrementForce = new double[3][basis.markerCount()];
		coupled.force(correction, initial, correctionLow, pairedForce); coupled.force(correction, new double[2], correctionLow, incrementForce);
		double forceNorm = 0;
		for (int axis = 0; axis < 3; axis++) for (int marker = 0; marker < basis.markerCount(); marker++) {
			near(pairedForce[axis][marker], incrementForce[axis][marker], 0, "Pressure reference cancels before force rounding");
			forceNorm = Math.hypot(forceNorm, pairedForce[axis][marker]);
		}
		if (!(forceNorm > 0)) throw new AssertionError("Referenced pressure lost its actual carrier force");
	}

	/** Independent full C assembly checks the factor action without reading its cached D or H. */
	private static void verifyFactoredConstraint(CellAffineVelocity basis, java.util.List<CellVelocityConstraintOperator.Row> definitions,
	                                           double[][] velocity, double[] pressure) {
		for (int block = 0; block < basis.blockCount(); block++) if (basis.blockMarkerCount(block) > 0) verifyDivergenceSpan(basis, block);
		int count = basis.markerCount(), rows = definitions.size();
		var matrix = new SignedDyadic[rows][3 * count];
		for (var row : matrix) Arrays.fill(row, SignedDyadic.ZERO);
		for (int row = 0; row < rows; row++) for (var block : definitions.get(row).blocks()) {
			for (int ordinal = 0; ordinal < basis.blockMarkerCount(block.velocityBlock()); ordinal++) {
				int marker = basis.blockMarker(block.velocityBlock(), ordinal);
				for (int inputAxis = 0; inputAxis < 3; inputAxis++) for (int term = 0; term < 4; term++) {
					int index = inputAxis * count + marker;
					matrix[row][index] = matrix[row][index].add(block.coefficientExact(term)
							.multiply(basis.coefficientExact(marker, block.axis(), inputAxis, term)));
				}
			}
		}
		var operator = new CellVelocityConstraintOperator(basis, definitions);
		var force = new double[3][count]; var exactForce = new SignedDyadic[3 * count]; Arrays.fill(exactForce, SignedDyadic.ZERO);
		for (int index = 0; index < exactForce.length; index++) for (int row = 0; row < rows; row++)
			exactForce[index] = exactForce[index].add(matrix[row][index].multiply(SignedDyadic.of(pressure[row])));
		operator.force(pressure, force);
		for (int index = 0; index < exactForce.length; index++)
			near(force[index / count][index % count], exactForce[index].toDouble(), 0, "Factored/full-C force equality");
		double[] rate = new double[rows], image = new double[rows], diagonal = new double[rows];
		operator.volumeRate(velocity, rate); operator.diagonal(diagonal); double work = operator.applyWithWork(pressure, image);
		SignedDyadic exactWork = SignedDyadic.ZERO;
		for (int index = 0; index < exactForce.length; index++) exactWork = exactWork.add(exactForce[index].multiply(exactForce[index])
				.multiply(SignedDyadic.of(1 / basis.massKilograms(index % count))));
		for (int row = 0; row < rows; row++) {
			SignedDyadic storage = SignedDyadic.of(definitions.get(row).storage()), load = SignedDyadic.of(pressure[row]);
			SignedDyadic expectedRate = SignedDyadic.ZERO, expectedImage = storage.multiply(load), expectedDiagonal = storage;
			for (int index = 0; index < exactForce.length; index++) {
				SignedDyadic coefficient = matrix[row][index], inertia = SignedDyadic.of(1 / basis.massKilograms(index % count));
				expectedRate = expectedRate.add(coefficient.multiply(SignedDyadic.of(velocity[index / count][index % count])));
				expectedImage = expectedImage.add(coefficient.multiply(exactForce[index]).multiply(inertia));
				expectedDiagonal = expectedDiagonal.add(coefficient.multiply(coefficient).multiply(inertia));
			}
			near(rate[row], expectedRate.toDouble(), 0, "Factored/full-C volume equality");
			near(image[row], expectedImage.toDouble(), 0, "Factored/full-C inertia equality");
			if (Double.doubleToRawLongBits(diagonal[row]) != Double.doubleToRawLongBits(expectedDiagonal.toDouble()))
				throw new AssertionError("Factored/full-C diagonal rounding differs");
			exactWork = exactWork.add(storage.multiply(load).multiply(load));
		}
		near(work, exactWork.toDouble(), 0, "Factored/full-C positive-work equality");
		var coordinates = operator.rowBasis(); var principal = operator.principalGram(coordinates);
		int entry = 0;
		for (int row = 0; row < coordinates.size(); row++) for (int column = 0; column <= row; column++) {
			int first = coordinates.row(row), second = coordinates.row(column);
			SignedDyadic value = first == second ? SignedDyadic.of(definitions.get(first).storage()) : SignedDyadic.ZERO;
			for (int index = 0; index < matrix[first].length; index++) value = value.add(matrix[first][index].multiply(matrix[second][index])
					.multiply(SignedDyadic.of(1 / basis.massKilograms(index % count))));
			SignedDyadic stored = entry < principal.values().length && principal.rows()[entry] == row && principal.columns()[entry] == column
					? principal.values()[entry++] : SignedDyadic.ZERO;
			if (!stored.equals(value)) throw new AssertionError("Principal D/H Gram differs from independent full C mass work");
		}
		operator.preconditioner(diagonal);
		var expanded = PressureConditionValidation.expandedConstraint(operator);
		var offsets = PressureConditionValidation.value(expanded, "offsets", int[].class);
		var indices = PressureConditionValidation.value(expanded, "indices", int[].class);
		var high = PressureConditionValidation.value(expanded, "coefficients", double[].class);
		var low = PressureConditionValidation.value(expanded, "coefficientLow", double[].class);
		var exact = PressureConditionValidation.value(expanded, "exactCoefficients", SignedDyadic[].class);
		for (int row = 0; row < rows; row++) {
			int next = offsets[row];
			for (int index = 0; index < matrix[row].length; index++) if (matrix[row][index].signum() != 0) {
				if (next >= offsets[row + 1] || indices[next] != index || !exact[next].equals(matrix[row][index]))
					throw new AssertionError("Preconditioner C differs from independently assembled physical coefficients");
				double expectedHigh = matrix[row][index].toDouble();
				double expectedLow = matrix[row][index].subtract(SignedDyadic.of(expectedHigh)).toDouble();
				if (Double.doubleToRawLongBits(high[next]) != Double.doubleToRawLongBits(expectedHigh)
						|| Double.doubleToRawLongBits(low[next]) != Double.doubleToRawLongBits(expectedLow))
					throw new AssertionError("Preconditioner C high/low rounding differs");
				next++;
			}
			if (next != offsets[row + 1]) throw new AssertionError("Preconditioner C has an extra physical coefficient");
		}
	}
	/** Independent SVD verifies every physical divergence coefficient, including rows omitted by local QR. */
	private static void verifyDivergenceSpan(CellAffineVelocity basis, int block) {
		int modes = basis.modeCount(block); if (modes == 0) return;
		var exact = new SignedDyadic[9][modes]; for (var row : exact) Arrays.fill(row, SignedDyadic.ZERO);
		var polynomial = new double[10];
		for (int axis = 0; axis < 3; axis++) for (int term = 0; term < 4; term++) {
			basis.divergencePolynomial(block, axis, term, polynomial);
			for (int monomial = 1; monomial < 10; monomial++) for (int mode = 0; mode < modes; mode++)
				exact[monomial - 1][mode] = exact[monomial - 1][mode].add(SignedDyadic.of(polynomial[monomial])
						.multiply(basis.modeCoefficientExact(block, mode, axis, term)));
		}
		var all = new double[9][modes];
		for (int row = 0; row < 9; row++) {
			double norm = 0;
			for (int mode = 0; mode < modes; mode++) { all[row][mode] = exact[row][mode].toDouble(); norm = Math.hypot(norm, all[row][mode]); }
			if (norm > 0) for (int mode = 0; mode < modes; mode++) all[row][mode] /= norm;
		}
		int[] selected = CellPressurePolynomialSpace.independentMonomials(basis, block);
		org.ejml.data.DMatrixRMaj directions = null;
		if (selected.length > 0) {
			var matrix = new org.ejml.data.DMatrixRMaj(selected.length, modes);
			for (int row = 0; row < selected.length; row++) for (int mode = 0; mode < modes; mode++) matrix.set(row, mode, all[selected[row] - 1][mode]);
			var svd = org.ejml.dense.row.factory.DecompositionFactory_DDRM.svd(selected.length, modes, false, true, false);
			if (!svd.decompose(matrix)) throw new AssertionError("Independent divergence span decomposition failed");
			directions = svd.getV(null, false);
		}
		for (int monomial = 0; monomial < 9; monomial++) {
			var residual = all[monomial].clone();
			for (int k = 0; k < selected.length; k++) {
				double projection = 0; for (int mode = 0; mode < modes; mode++) projection += all[monomial][mode] * directions.get(mode, k);
				for (int mode = 0; mode < modes; mode++) residual[mode] -= projection * directions.get(mode, k);
			}
			double norm = 0; for (double value : residual) norm = Math.hypot(norm, value);
			if (norm > 64 * Math.max(9, modes) * Math.ulp(1.0)) throw new AssertionError("Selected pressure rows omitted physical divergence: block="
					+ block + " monomial=" + (monomial + 1) + " residual=" + norm);
		}
	}

	private static void scaledClosedRotationRowsKeepKernel() {
		for (float fraction : new float[]{.9F, .95F, .99F}) partialClosedRotationRowsKeepKernel(fraction);
		var d = new FluidDomain(3, 3, 3, new FluidSolverConfig(.7F, 1000, 0, .001F, 100, 1e-5F, .45F, .002F, 0));
		for (int cell = 0; cell < d.cellCount; cell++) d.setSolid(cell % 3, cell / 3 % 3, cell / 9, cell != 13);
		d.setVolume(1, 1, 1, 1);
		for (int marker = 0; marker < d.particleCount(); marker++) d.particles.setMass(marker, d.particles.mass(marker) * (1 + .125 * marker));
		d.ensureInventoryGrid(); var geometry = LiquidPressureGeometry.capture(d); var basis = CellAffineVelocity.capture(d, geometry);
		int block = basis.markerBlock(0); var rows = new java.util.ArrayList<CellVelocityConstraintOperator.Row>();
		double scale = geometry.componentSnapshot(13, basis.blockSupport(block)).volume() / basis.cellSize();
		if (basis.center(block, 0) == 1.5 * basis.cellSize()) throw new AssertionError("Closed rotation fixture has no mass-centre offset");
		var polynomial = new double[3][4][10];
		for (int axis = 0; axis < 3; axis++) for (int term = 0; term < 4; term++) basis.divergencePolynomial(block, axis, term, polynomial[axis][term]);
		for (int monomial = 1; monomial < 10; monomial++) {
			var parts = new java.util.ArrayList<CellVelocityConstraintOperator.Block>();
			for (int axis = 0; axis < 3; axis++) {
				var coefficient = new double[4]; for (int term = 0; term < 4; term++) coefficient[term] = polynomial[axis][term][monomial];
				parts.add(new CellVelocityConstraintOperator.Block(block, axis, coefficient, scale));
			}
			rows.add(new CellVelocityConstraintOperator.Row(parts, 0));
		}
		int rotations = 0;
		for (int mode = 0; mode < basis.modeCount(block); mode++) {
			boolean skew = true, nonzero = false;
			for (int axis = 0; axis < 3; axis++) for (int other = 0; other < 3; other++) {
				var a = basis.modeCoefficientExact(block, mode, axis, other + 1);
				var b = basis.modeCoefficientExact(block, mode, other, axis + 1);
				skew &= a.add(b).signum() == 0; nonzero |= a.signum() != 0;
			}
			if (!skew || !nonzero) continue;
			rotations++;
			for (var row : rows) {
				var exact = SignedDyadic.ZERO;
				for (var part : row.blocks()) for (int term = 0; term < 4; term++)
					exact = exact.add(part.coefficientExact(term).multiply(basis.modeCoefficientExact(block, mode, part.axis(), term)));
				if (exact.signum() != 0) throw new AssertionError("Literal closed q rotation has a polynomial divergence");
			}
		}
		if (rotations < 3) throw new AssertionError("Closed q space lost a physical rotation");
		var velocity = new double[3][basis.markerCount()];
		for (int axis = 0; axis < 3; axis++) for (int marker = 0; marker < basis.markerCount(); marker++) velocity[axis][marker] = Math.sin(axis + .3 * marker);
		var pressure = new double[rows.size()]; for (int row = 0; row < pressure.length; row++) pressure[row] = Math.cos(.2 * row);
		verifyFactoredConstraint(basis, rows, velocity, pressure);
	}


	/** Real partial PLIC support must retain the exact rotation kernel through its volume integral. */
	private static void partialClosedRotationRowsKeepKernel(float fraction) {
		var d = new FluidDomain(3, 3, 3, new FluidSolverConfig(1, 1000, 0, .001F, 100, 1e-5F, .45F, .002F, 0));
		for (int cell = 0; cell < d.cellCount; cell++) d.setSolid(cell % 3, cell / 3 % 3, cell / 9, cell != 13);
		d.setVolume(1, 1, 1, fraction);
		for (int marker = 0; marker < d.particleCount(); marker++) {
			d.particles.setMass(marker, d.particles.mass(marker) * (1 + .0137 * marker));
			d.particles.setPositionX(marker, d.particles.positionX(marker) + .043F);
			d.particles.setPositionZ(marker, d.particles.positionZ(marker) - .027F);
		}
		d.ensureGrid(); d.gasTopologyCoordinator.refresh();
		var plan = d.pressureSystem.markerProjection(); plan.prepare(.001F);
		var basis = plan.basis(); int block = basis.markerBlock(0);
		var profile = plan.geometry().componentSnapshot(13, basis.blockSupport(block));
		if (profile.freeFragments().isEmpty() || plan.rowOfLiquidBlock(block) < 0)
			throw new AssertionError("Partial rotation fixture lost its PLIC volume row");
		java.util.List<CellVelocityConstraintOperator.Row> rows;
		try {
			var field = MarkerPressureProjection.class.getDeclaredField("rows"); field.setAccessible(true);
			@SuppressWarnings("unchecked") var declared = (java.util.List<CellVelocityConstraintOperator.Row>) field.get(plan);
			rows = declared;
		} catch (ReflectiveOperationException failed) { throw new AssertionError("Cannot inspect actual pressure rows", failed); }
		int rotations = 0;
		for (int mode = 0; mode < basis.modeCount(block); mode++) {
			boolean skew = true, nonzero = false;
			for (int axis = 0; axis < 3; axis++) for (int other = 0; other < 3; other++) {
				var a = basis.modeCoefficientExact(block, mode, axis, other + 1);
				var b = basis.modeCoefficientExact(block, mode, other, axis + 1);
				skew &= a.add(b).signum() == 0; nonzero |= a.signum() != 0;
			}
			if (!skew || !nonzero) continue;
			rotations++;
			for (int row = 0; row < rows.size(); row++) {
				var residual = SignedDyadic.ZERO;
				for (var part : rows.get(row).blocks()) if (part.velocityBlock() == block)
					for (int term = 0; term < 4; term++) residual = residual.add(part.coefficientExact(term)
							.multiply(basis.modeCoefficientExact(block, mode, part.axis(), term)));
				if (residual.signum() != 0) throw new AssertionError("Partial PLIC pressure row changed literal rotation: fraction="
						+ fraction + " row=" + row + " mode=" + mode + " residual=" + residual);
			}
		}
		if (rotations < 3) throw new AssertionError("Partial q space lost a physical rotation");
		var velocity = new double[3][basis.markerCount()];
		for (int axis = 0; axis < 3; axis++) for (int marker = 0; marker < basis.markerCount(); marker++) velocity[axis][marker] = Math.sin(axis + .3 * marker);
		var pressure = new double[rows.size()]; for (int row = 0; row < pressure.length; row++) pressure[row] = Math.cos(.2 * row);
		verifyFactoredConstraint(basis, rows, velocity, pressure);
	}

	/** A slanted positive-area strip retains both physical slopes as its transverse width shrinks. */
	private static void thinSharedFacePreservesPhysicalTrace() {
		for (int normal = 0; normal < 3; normal++) for (double halfWidth : new double[]{0x1p-4, 0x1p-20}) {
			var d = new FluidDomain(4, 4, 4, new FluidSolverConfig(1, 1000, 0, .001F, 100, 1e-5F, .45F, .002F, 0), 32);
			int[] peer = {1, 1, 1}; peer[normal]++;
			d.setVolume(1, 1, 1, 1); d.setVolume(peer[0], peer[1], peer[2], 1);
			for (int p = 0; p < d.particleCount(); p++) d.particles.setMass(p, d.particles.mass(p) * (1 + p / 16.0));
			d.ensureGrid(); d.gasTopologyCoordinator.refresh();
			var plan = d.pressureSystem.markerProjection(); plan.prepare(.001F); var basis = plan.basis();
			int first = basis.blockForCell(d.cellIndex(1, 1, 1), 0), second = basis.blockForCell(d.cellIndex(peer[0], peer[1], peer[2]), 0);
			int tangent = (normal + 1) % 3, other = (normal + 2) % 3;
			double[] center = {1.5, 1.5, 1.5}; center[normal] = 2;
			double area = halfWidth, variance = (.0625 + halfWidth * halfWidth) / 6, cross = (.0625 - halfWidth * halfWidth) / 6;
			var centroid = new LiquidPressureGeometry.Vec3(center[0], center[1], center[2]);
			var entries = new double[6]; int entry = 0;
			for (int a = 0; a < 3; a++) for (int b = a; b < 3; b++)
				entries[entry++] = a == normal || b == normal ? 0 : area * (a == b ? variance : cross);
			var central = new LiquidPressureGeometry.SecondMoment(entries[0], entries[1], entries[2], entries[3], entries[4], entries[5]);
			var face = new LiquidPressureGeometry.SurfaceMoments(area, centroid.scale(area),
					central.add(LiquidPressureGeometry.SecondMoment.outer(centroid, area)), centroid, central);
			var rows = new java.util.ArrayList<CellVelocityConstraintOperator.Row>();
			try {
				var trace = MarkerPressureProjection.class.getDeclaredMethod("trace", int.class, int.class, LiquidPressureGeometry.SurfaceMoments.class, int.class, double.class);
				trace.setAccessible(true);
				for (int test = 0; test < 3; test++) rows.add(new CellVelocityConstraintOperator.Row(java.util.List.of(
						new CellVelocityConstraintOperator.Block(first, normal, (double[]) trace.invoke(plan, first, normal, face, test, 1.0)),
						new CellVelocityConstraintOperator.Block(second, normal, (double[]) trace.invoke(plan, second, normal, face, test, -1.0))), 0));
			} catch (ReflectiveOperationException failure) { throw new AssertionError("Cannot inspect production shared traces", failure); }
			var operator = new CellVelocityConstraintOperator(basis, rows); int count = basis.markerCount();
			var input = new double[3][count];
			for (int a = 0; a < 3; a++) for (int p = 0; p < count; p++) input[a][p] = Math.sin(.37 * p + a);
			verifyFactoredConstraint(basis, rows, input, new double[]{.7, -1.3, .25});
			var rates = new double[3]; operator.volumeRate(input, rates);
			var gram = new double[3][3]; var unit = new double[3]; var image = new double[3];
			for (int column = 0; column < 3; column++) {
				Arrays.fill(unit, 0); unit[column] = 1; operator.apply(unit, image);
				for (int row = 0; row < 3; row++) gram[row][column] = image[row];
			}
			var pressure = MarkerVelocitySpaceExperiment.solve(gram, rates); var force = new double[3][count];
			operator.force(pressure, force); near(dot(rates, pressure), work(input, force), 2e-12, "Thin shared trace virtual work");
			var output = new double[3][count];
			for (int a = 0; a < 3; a++) for (int p = 0; p < count; p++) output[a][p] = input[a][p] - force[a][p] / basis.massKilograms(p);
			var before = mechanicalMoments(basis, input); var after = mechanicalMoments(basis, output);
			for (int quantity = 0; quantity < 6; quantity++) near(after[quantity], before[quantity],
					128 * count * Math.ulp(Math.max(1, Math.max(Math.abs(before[quantity]), Math.abs(after[quantity])))), "Thin shared projection actual P/L");
			if (after[6] > before[6] + 128 * count * Math.ulp(Math.max(1, before[6]))) throw new AssertionError("Shared pressure projection created kinetic energy");
			var coefficients = new double[3][4 * basis.blockCount()]; basis.fit(output, coefficients);
			for (int axis : new int[]{tangent, other}) near(coefficients[normal][4 * first + axis + 1], coefficients[normal][4 * second + axis + 1], 2e-12, "Thin face physical tangent slope continuity");
			for (double u : new double[]{-.25, 0, .25}) for (double v : new double[]{-halfWidth, halfWidth}) {
				var point = center.clone(); point[tangent] += (u + v) / Math.sqrt(2); point[other] += (u - v) / Math.sqrt(2);
				near(basis.value(coefficients, normal, first, point[0], point[1], point[2]),
						basis.value(coefficients, normal, second, point[0], point[1], point[2]), 2e-12, "Thin shared face pointwise continuity");
			}
			for (int rotation = 0; rotation < 3; rotation++) {
				var rigid = new double[3][count]; int a = (rotation + 1) % 3, b = (rotation + 2) % 3;
				for (int p = 0; p < count; p++) { rigid[a][p] = -basis.position(p, b); rigid[b][p] = basis.position(p, a); }
				operator.volumeRate(rigid, rates);
				for (double rate : rates) near(rate / area, 0, 2e-12, "Thin shared trace retains rigid rotation kernel");
			}
			System.out.printf("THIN_SHARED axis=%d halfWidth=%g covarianceCondition=%g actualDeltaK=%g%n", normal, halfWidth, .0625 / (halfWidth * halfWidth), after[6] - before[6]);
		}
	}

	private static void sharedTraceMassConditioning() {
		for (int cells : new int[]{2, 4, 8}) for (float epsilon : new float[]{1 / 16F, 1 / 1024F}) {
			var config = new FluidSolverConfig(1, 1000, 0, .001F, 64, 1e-6F, .45F, .002F, 0);
			var domain = new FluidDomain(cells + 2, 3, 3, config, 16 * cells);
			float[] positions = new float[32 * cells]; double[] mass = new double[8 * cells]; Arrays.fill(mass, 125);
			for (int cell = 0; cell < cells; cell++) for (int point = 0; point < 8; point++) {
				int base = 4 * (8 * cell + point); float along = (point & 2) == 0 ? -.25F : .25F, thin = (point & 4) == 0 ? -epsilon : epsilon;
				positions[base] = cell + 1.5F + ((point & 1) == 0 ? -.25F : .25F);
				positions[base + 1] = 1.5F + along + thin; positions[base + 2] = 1.5F + along - thin; positions[base + 3] = .125F;
			}
			domain.restoreMaterialParticleState(new FlipMaterialParticleState(cells + 2, 3, 3, 1, mass.length, cells, 1000.0 * cells,
					java.util.List.of(new FluidMaterial("mmm:water", 1000, "mmm:water", 0, Math.PI / 2)),
					positions, new float[3 * mass.length], new byte[domain.cellCount], mass, new int[mass.length]));
			var basis = CellAffineVelocity.capture(domain); var definitions = new java.util.ArrayList<CellVelocityConstraintOperator.Row>();
			for (int cell = 0; cell < cells; cell++) {
				var blocks = new java.util.ArrayList<CellVelocityConstraintOperator.Block>();
				for (int axis = 0; axis < 3; axis++) {
					double[] declared = new double[4]; declared[axis + 1] = 1;
					blocks.add(new CellVelocityConstraintOperator.Block(basis.markerBlock(8 * cell), axis, declared));
				}
				definitions.add(new CellVelocityConstraintOperator.Row(blocks, 0));
			}
			var groups = new java.util.ArrayList<int[]>();
			for (int face = 0; face < cells - 1; face++) {
				groups.add(new int[]{definitions.size(), definitions.size() + 1, definitions.size() + 2});
				for (int test = 0; test < 3; test++) {
					var left = new double[4]; var right = new double[4];
					if (test == 0) { left[0] = 1; right[0] = -1; left[1] = right[1] = .5; }
					else { left[test + 1] = 1.0 / 12; right[test + 1] = -1.0 / 12; }
					definitions.add(new CellVelocityConstraintOperator.Row(java.util.List.of(
							new CellVelocityConstraintOperator.Block(basis.markerBlock(8 * face), 0, left),
							new CellVelocityConstraintOperator.Block(basis.markerBlock(8 * (face + 1)), 0, right)), 0));
				}
			}
			var operator = new CellVelocityConstraintOperator(basis, definitions); int rows = operator.rowCount();
			double[] mode = new double[rows], image = new double[rows], diagonal = new double[rows]; operator.diagonal(diagonal);
			var velocity = new double[3][mass.length];
			for (int marker = 0; marker < mass.length; marker++) velocity[0][marker] = (marker / 8 + 1) * (positions[4 * marker + 1] + positions[4 * marker + 2] - 3);
			for (int face = 0; face < cells - 1; face++) mode[cells + 3 * face + 1] = mode[cells + 3 * face + 2] = Math.sin(Math.PI * (face + 1) / cells);
			verifyFactoredConstraint(basis, definitions, velocity, mode); operator.apply(mode, image);
			// Symmetric sources make the entire mean/normal coarse space mass-orthogonal to this shear mode.
			for (int row = 0; row < rows; row++) if (row < cells || (row - cells) % 3 == 0)
				near(image[row] / Math.sqrt(diagonal[row]), 0, 1e-12, "Mean/normal coarse space misses tangent shear");
			double[] unit = new double[rows], column = new double[rows]; unit[cells + 1] = 1; operator.apply(unit, column);
			double a = column[cells + 1], b = column[cells + 2], condition = (a - b) / (a + b);
			near(condition / (.0625 / (epsilon * epsilon)), 1, 1e-9, "Actual tangent Gram follows source covariance");
			double eigenvalue = (a + b) * (1 - Math.cos(Math.PI / cells));
			for (int row = cells; row < rows; row++) if ((row - cells) % 3 != 0)
				near(image[row], eigenvalue * mode[row], 1e-12, "Independent tangent-chain eigenmode");
			var local = operator.preconditioner(diagonal, new double[rows], groups);
			if (cells == 2) for (int test = 1; test <= 2; test++) {
				Arrays.fill(unit, 0); unit[cells + test] = 1; operator.apply(unit, column); local.apply(column, image);
				for (int row = 0; row < rows; row++) near(image[row], unit[row], config.pressureTolerance(), "Face three-moment inverse removes thin-source conditioning");
			}
			double[] rhs = new double[rows]; operator.apply(mode, rhs);
			double pressureUnit = 1.0 / config.density();
			var result = PressureConditionValidation.comparePressure(operator, local::apply, rhs, new double[rows],
					config.maxPressureIterations(), config.pressureTolerance() * pressureUnit, pressureUnit);
			if (!result.converged()) throw new AssertionError("Shared-trace matrix exceeded the original pressure budget: " + result);
			System.out.printf("TRACE cells=%d epsilon=%g tangentCondition=%g chainCondition=%g result=%s%n", cells, epsilon, condition,
					(1 + Math.cos(Math.PI / cells)) / (1 - Math.cos(Math.PI / cells)), result);
		}
	}

	private static void verifyAffineRigidMotion(CellAffineVelocity basis) {
		for (int mode = 0; mode < 4; mode++) {
			var velocity = new double[3][basis.markerCount()]; var coefficient = new double[3][4 * basis.blockCount()];
			for (int i = 0; i < basis.markerCount(); i++) for (int a = 0; a < 3; a++) velocity[a][i] = mode == 0
					? new double[]{1.25, -.75, .125}[a] : a == mode % 3 ? -basis.position(i, (a + 1) % 3)
					: a == (mode + 1) % 3 ? basis.position(i, (a + 2) % 3) : 0;
			basis.fit(velocity, coefficient); var reconstructed = new double[3][basis.markerCount()]; basis.predict(coefficient, reconstructed);
			for (int a = 0; a < 3; a++) for (int i = 0; i < basis.markerCount(); i++)
				near(reconstructed[a][i], velocity[a][i], 2e-14, "Constant/rigid rotation on actual affine support");
			near(basis.divergence(coefficient, 0), 0, 2e-14, "Rigid rotation affine divergence");
			double[] point = {.45, .3, .2};
			if (basis.canRepresentPoint(0, point[0], point[1], point[2])) {
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
		c.apply(new double[]{liquidNew,gasNew},h);near(h[0],-.05/dt,2e-12,"SI liquid RHS");
		double gasRhs = .05/dt + storage*gasInitial;
		// Positive marker reduction and the six products/divisions forming this one-face response.
		int operations = j.markerCount() + 6;
		double roundoff = operations * Math.scalb(1.0, -53) / (1 - operations * Math.scalb(1.0, -53));
		near(h[1],gasRhs,roundoff*gasRhs,"SI finite gas RHS");
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
			near(ConstraintNumerics.rescaledResponse(sign*0x1.b9f88e001b9f8p-77,0x1.04p500,0x1.24p600),
					sign*Double.MAX_VALUE,0,"Final pressure response overflow midpoint");
			near(ConstraintNumerics.rescaledResponse(sign*0x1.e18ce7228170bp24,0x1.04p-1000,0x1.0cp-100),
					sign*Double.MIN_VALUE,0,"Final pressure response underflow midpoint");
			near(MarkerPressureOperator.scaledPressureDifference(sign*0x1.f07c1f07c1f07p-1,0x1.04p1023,-0x1.0cp1023),
					sign*Double.MAX_VALUE,0,"Final scaled pressure difference midpoint");
		}
		if(ConstraintNumerics.rescaledResponse(1,Double.MAX_VALUE,2)!=Double.POSITIVE_INFINITY
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
