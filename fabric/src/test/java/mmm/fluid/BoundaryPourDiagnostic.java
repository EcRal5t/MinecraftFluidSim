package mmm.fluid;

import java.lang.reflect.Field;
import java.util.Arrays;
import mmm.units.SignedDyadic;

/** Bounded first-pour diagnostic using the production mechanical substep and private pressure snapshots. */
public final class BoundaryPourDiagnostic {
	private BoundaryPourDiagnostic() { }

	public static void main(String[] args) {
		if (args.length > 1 || args.length == 1 && !args[0].equals("--flux"))
			throw new IllegalArgumentException("Boundary pour diagnostic accepts --flux");
		run(args.length == 1);
	}

	private static void run(boolean fluxOnly) {
		var config = new FluidSolverConfig(.5F, 1000, 9.81F, .05F, 120, 1e-4F, .40F, .002F, .80F);
		var initial = new FluidDomain(8, 8, 8, config, 4096);
		initial.sealDomainBoundary(); initial.bootstrapGasState(null); initial.setGasNodeStandardVolume(1, 34);
		var addition = initial.addVolumeNearBudgeted(6, 0, 0, 128, 6);
		if (addition.addedVolume() != 128 || addition.markerBudgetLimited()) throw new AssertionError("Pour diagnostic fixture differs");
		FluidDomainState saved = null;
		GasRuntimeGeometry previousGas = null;
		for (int logical = 0; logical < 4; logical++) {
			if (!fluxOnly) inspectInput(initial, "logical=" + logical + " before-capture");
			saved = initial.captureAuthoritativeState();
			if (!fluxOnly) inspectGas(initial, previousGas, logical);
			previousGas = initial.gasState.frame;
			if (!fluxOnly) inspectInput(initial, "logical=" + logical + " after-capture");
			var restored = FluidDomain.restoreAuthoritativeState(saved);
			if (!fluxOnly) inspectInput(restored, "logical=" + logical + " restored-input");
			try {
				var candidate = new CpuFluidSolverBackend().evaluate(new FluidStepRequest(0, saved, java.util.List.of(), false, 0));
				initial = candidate.claimPreparedCpuDomain();
				System.out.printf("POUR full logical=%d complete actualSpeed=%g markers=%d%n", logical, initial.maximumParticleSpeed(), initial.particleCount());
				snapshot(initial, logical, -1, config.timeStep(), "after-full-step", fluxOnly);
				initial = FluidDomain.restoreAuthoritativeState(initial.captureAuthoritativeState());
			} catch (RuntimeException failed) {
				System.out.printf("POUR full logical=%d failure=%s%n", logical, failed);
				break;
			}
			if (logical == 3) { System.out.println("POUR diagnostic completed four logical steps without reproducing the retained failure"); return; }
		}
		int minimum = 1; long spent = 0;
		for (int attempt = 0; attempt < 16; attempt++) {
			var domain = FluidDomain.restoreAuthoritativeState(saved);
			domain.minimumMechanicalSubsteps = minimum;
			if (!fluxOnly) inspectInput(domain, "failed-source before-CFL attempt=" + attempt);
			int count = domain.calculateSubsteps(config.timeStep());
			spent += count;
			System.out.printf("POUR attempt=%d count=%d cumulative=%d sourceMarkers=%d gasPa=%g%n",
					attempt, count, spent, domain.particleCount(), domain.gasBoundary().gaugePressure(1));
			if (spent > CpuFluidSolverBackend.MAXIMUM_CUMULATIVE_SUBSTEPS) {
				System.out.println("POUR diagnostic stopped at the production cumulative substep budget");
				return;
			}
			float dt = config.timeStep() / count;
			domain.carrierPropertiesWorkspace.beginStep();
			boolean refined = false;
			for (int substep = 0; substep < count; substep++) {
				try {
					domain.mechanicalPipeline.substep(dt, substep, false, null);
					if (substep < 4 || substep % 128 == 0) snapshot(domain, attempt, substep, dt, "complete", fluxOnly);
				} catch (MechanicalTimeRefinement finer) {
					snapshot(domain, attempt, substep, dt, "refine=" + finer.getMessage(), fluxOnly);
					minimum = CpuFluidSolverBackend.refinedSubsteps(count, finer);
					refined = true; break;
				} catch (RuntimeException failed) {
					snapshot(domain, attempt, substep, dt, "failed=" + failed, fluxOnly);
					throw failed;
				}
			}
			if (!refined) {
				System.out.println("POUR first mechanical logical step completed");
				return;
			}
		}
		throw new AssertionError("Pour diagnostic reached its attempt limit");
	}

	private static void inspectGas(FluidDomain d, GasRuntimeGeometry previous, int logical) {
		var frame = d.gasState.frame; var network = d.gasState.network;
		double vacuumVolume = 0; int vacuumCount = 0;
		for (int node = 1; node <= network.nodeCount(); node++) {
			if (!network.inventory(node).isEmpty()) continue;
			vacuumCount++; vacuumVolume += network.availableVolume(node);
			int pieces = 0, previouslyDry = 0;
			for (int key = 0; key < 8 * d.cellCount; key++) if (frame.nodeOfPiece(key) == node) {
				pieces++; if (previous != null && previous.nodeOfPiece(key) >= 0) previouslyDry++;
			}
			int key = frame.minimumPiece(node), cell = key / 8;
			if (vacuumCount <= 12) System.out.printf("POUR vacuum logical=%d node=%d volumeM3=%.12g minimumPiece=%d pieces=%d priorDryPieces=%d cellRawV=%.12g priorCellV=%.12g%n",
					logical, node, network.availableVolume(node), key, pieces, previouslyDry, frame.liquidStock().get(cell).volume().toDouble(),
					previous == null ? Double.NaN : previous.liquidStock().get(cell).volume().toDouble());
		}
		System.out.printf("POUR gas logical=%d nodes=%d vacuumNodes=%d vacuumVolumeM3=%.12g node1Volume=%.12g gaugePa=%.12g%n",
				logical, network.nodeCount(), vacuumCount, vacuumVolume, network.availableVolume(1), network.gaugePressure(1));
	}

	private static void inspectInput(FluidDomain d, String stage) {
		inspectVolumeMoments(d, stage);
		var basis = CellAffineVelocity.capture(d);
		var velocity = new double[3][d.particleCount()];
		for (int p = 0; p < d.particleCount(); p++) {
			velocity[0][p] = d.particles.velocityX(p); velocity[1][p] = d.particles.velocityY(p); velocity[2][p] = d.particles.velocityZ(p);
		}
		var coefficients = new double[3][4 * basis.blockCount()]; basis.fit(velocity, coefficients);
		var field = new CellAffineVelocityField(basis, coefficients);
		double gradient = 0, trace = 0; int gradientBlock = -1, traceBlock = -1;
		for (int block = 0; block < basis.blockCount(); block++) if (basis.blockMarkerCount(block) > 0) for (int axis = 0; axis < 3; axis++) {
			for (int term = 1; term < 4; term++) {
				double value = Math.abs(coefficients[axis][4 * block + term] / basis.cellSize());
				if (value > gradient) { gradient = value; gradientBlock = block; }
			}
		}
		for (int cell = 0; cell < d.cellCount; cell++) if (field.hasCell(cell)) for (int axis = 0; axis < 3; axis++)
			for (boolean positive : new boolean[]{false, true}) for (var part : field.faceTraces(cell, axis, positive, d.sizeX, d.sizeY)) {
				var q = part.q(); int block = basis.blockForMicro(cell, Integer.numberOfTrailingZeros(part.support()));
				for (int t = 0; t < 2; t++) for (int u = 0; u < 2; u++) {
					double value = Math.abs(q[0] + t * q[1] + u * q[2]);
					if (value > trace) { trace = value; traceBlock = block; }
				}
			}
		System.out.printf("POUR input=%s actualSpeed=%g markers=%d freshGradient=%g cell=%d fullTrace=%g cell=%d gridDirty=%s velocityDirty=%s%n",
				stage, d.maximumParticleSpeed(), d.particleCount(), gradient, gradientBlock < 0 ? -1 : basis.physicalCell(gradientBlock),
				trace, traceBlock < 0 ? -1 : basis.physicalCell(traceBlock), d.gridDirty, d.velocityGridDirty);
		if (gradientBlock >= 0) inputCell(d, basis, coefficients, gradientBlock);
		if (traceBlock >= 0 && traceBlock != gradientBlock) inputCell(d, basis, coefficients, traceBlock);
	}
	private static void inspectVolumeMoments(FluidDomain d, String stage) {
		var frame = d.gasState.frame; if (frame == null) return;
		int violations = 0, worstCell = -1, worstSupport = 0; double largest = 0;
		for (var cell : frame.liquidStock()) for (var component : cell.components()) {
			if (component.volume().signum() == 0) continue;
			var certificate = LiquidVolumeMomentSupport.evaluate(frame, cell.cell(), component.supportMask(), d.sizeX, d.sizeY);
			if (certificate.excess().signum() <= 0) continue;
			violations++; double displacement = certificate.excess().toDouble() / component.volume().toDouble();
			if (displacement > largest) { largest = displacement; worstCell = cell.cell(); worstSupport = component.supportMask(); }
		}
		System.out.printf("POUR volumeMoment stage=%s capturedComponentsOutsideSupport=%d largestNormalizedProjectedExcess=%g cell=%d support=%d%n",
				stage, violations, largest, worstCell, worstSupport);
	}

	private static void inputCell(FluidDomain d, CellAffineVelocity basis, double[][] coefficient, int block) {
		int cell = basis.physicalCell(block);
		double mass = 0, volume = 0;
		for (int k = 0; k < basis.blockMarkerCount(block); k++) {
			int p = basis.blockMarker(block, k); mass += basis.massKilograms(p); volume += d.particles.volume(p);
		}
		System.out.printf("POUR source cell=%d rank=%d expanded=%s count=%d kg=%g rawV=%g fraction=%g normal=[%g,%g,%g] offset=%g%n", cell,
				basis.rank(block), basis.expandedCell(block), basis.blockMarkerCount(block), mass, volume,
				d.pressurePhaseGeometry.liquidFractionExact(cell), d.pressurePhaseGeometry.planeNormal(cell, 0),
				d.pressurePhaseGeometry.planeNormal(cell, 1), d.pressurePhaseGeometry.planeNormal(cell, 2), d.pressurePhaseGeometry.planeOffset(cell));
		for (int a = 0; a < 3; a++) System.out.printf("POUR source cell=%d axis=%d coefficients=%s%n", cell, a, Arrays.toString(Arrays.copyOfRange(coefficient[a], 4 * block, 4 * block + 4)));
		for (int k = 0; k < Math.min(12, basis.blockMarkerCount(block)); k++) {
			int p = basis.blockMarker(block, k);
			System.out.printf("POUR source marker=%d pos=[%.12g,%.12g,%.12g] V=%.12g M=%.12g v=[%.12g,%.12g,%.12g]%n", p,
					basis.position(p, 0), basis.position(p, 1), basis.position(p, 2), d.particles.volume(p), d.particles.mass(p),
					d.particles.velocityX(p), d.particles.velocityY(p), d.particles.velocityZ(p));
		}
	}

	private static void snapshot(FluidDomain d, int attempt, int substep, float dt, String outcome, boolean fluxOnly) {
		if (outcome.startsWith("failed=") && !value(d.pressureSystem.markerProjection(), "solved", Boolean.class))
			inspectPressureFailure(d.pressureSystem.markerProjection());
		if (fluxOnly) {
			System.out.printf("POUR flux snapshot attempt=%d substep=%d dt=%g %s%n", attempt, substep, dt, outcome);
			if (outcome.startsWith("failed=")) {
				try { fluxReplay(d, dt); }
				catch (RuntimeException | AssertionError failure) { System.out.println("POUR flux replay unverified; interpretation stopped: " + failure); }
			}
			return;
		}
		var projection = d.pressureSystem.markerProjection();
		CellAffineVelocity basis = value(projection, "basis", CellAffineVelocity.class);
		CellAffineVelocityField field = value(projection, "candidateField", CellAffineVelocityField.class);
		LiquidPressureGeometry geometry = value(projection, "geometry", LiquidPressureGeometry.class);
		System.out.printf("POUR snapshot attempt=%d substep=%d dt=%g actualSpeed=%g actualMarkers=%d %s%n",
				attempt, substep, dt, d.maximumParticleSpeed(), d.particleCount(), outcome);
		inspectVolumeMoments(d, "snapshot attempt=" + attempt + " substep=" + substep + " " + outcome);
		if (basis == null) return;
		if (!value(projection, "active", Boolean.class)) {
			var coefficient = new double[3][4 * basis.blockCount()];
			var velocity = new double[3][basis.markerCount()];
			for (int p = 0; p < basis.markerCount(); p++) {
				velocity[0][p] = d.particles.velocityX(p); velocity[1][p] = d.particles.velocityY(p); velocity[2][p] = d.particles.velocityZ(p);
			}
			basis.fit(velocity, coefficient);
			for (int block = 0, shown = 0; block < basis.blockCount() && shown < 4; block++) if (basis.blockMarkerCount(block) > 0
					&& (basis.rank(block) < 4 || !basis.hasRepresentableBasis(block))) {
				inputCell(d, basis, coefficient, block); shown++;
			}
			return;
		}
		boolean hasCandidate = value(projection, "solved", Boolean.class) || value(projection, "published", Boolean.class);
		for (String name : new String[]{"originalVelocity", "predictor", "wallPredictor", "candidate", "publishedVelocity"}) {
			if (!hasCandidate && (name.equals("candidate") || name.equals("publishedVelocity"))) continue;
			double[][] velocity = value(projection, name, double[][].class);
			if (velocity == null) continue;
			double maximum = 0, energy = 0; int marker = -1;
			for (int p = 0; p < velocity[0].length; p++) {
				double speed = Math.hypot(Math.hypot(velocity[0][p], velocity[1][p]), velocity[2][p]);
				energy += .5 * basis.massKilograms(p) * speed * speed;
				if (speed > maximum) { maximum = speed; marker = p; }
			}
			System.out.printf("POUR velocity=%s max=%g KE=%g marker=%d cell=%d%n", name, maximum, energy, marker, marker < 0 ? -1 : basis.physicalCell(basis.markerBlock(marker)));
		}
		if (!hasCandidate || field == null || geometry == null) return;
		if (value(field, "basis", CellAffineVelocity.class) != basis) {
			System.out.println("POUR candidate field belongs to an earlier prepare; stopping snapshot before field comparison");
			return;
		}
		pressureWork(projection, basis, value(projection, "dt", Float.class));
		volumeVelocityMeans(projection, basis, field, geometry);
		double[][] coefficient = value(field, "coefficients", double[][].class);
		double largest = 0; int worstBlock = -1, worstAxis = -1, worstTerm = -1;
		for (int block = 0; block < basis.blockCount(); block++) if (basis.blockMarkerCount(block) > 0)
			for (int axis = 0; axis < 3; axis++) for (int term = 1; term < 4; term++) {
				double derivative = Math.abs(coefficient[axis][4 * block + term] / basis.cellSize());
				if (derivative > largest) { largest = derivative; worstBlock = block; worstAxis = axis; worstTerm = term; }
			}
		System.out.printf("POUR affineGradient=%g cell=%d axis=%d term=%d%n", largest, worstBlock < 0 ? -1 : basis.physicalCell(worstBlock), worstAxis, worstTerm);
		var faces = new ConservativeLiquidFlux(d.sizeX, d.sizeY, d.sizeZ);
		var flow = new LiquidAffineFaceFlow(d, faces, field);
		double traceMaximum = 0; int traceCell = -1, traceBlock = -1, traceAxis = -1; boolean tracePositive = false;
		for (int face = 0; face < faces.physicalFaceCount; face++) for (boolean positive : new boolean[]{false, true}) {
			int donor = positive ? faces.left[face] : faces.right[face];
			if (d.geometry.solid[faces.left[face]] || d.geometry.solid[faces.right[face]]) continue;
			double speed = flow.maximumOutwardSpeed(face, positive, 0);
			if (speed > traceMaximum) {
				traceMaximum = speed; traceCell = donor; traceAxis = faces.axis[face]; tracePositive = positive;
				double largestPart = -1;
				for (var part : field.faceTraces(donor, traceAxis, positive, d.sizeX, d.sizeY)) {
					var q = part.q(); double bound = positive ? q[0] + Math.max(0, q[1]) + Math.max(0, q[2])
							: -q[0] - Math.min(0, q[1]) - Math.min(0, q[2]);
					if (bound > largestPart) { largestPart = bound; traceBlock = basis.blockForMicro(donor, Integer.numberOfTrailingZeros(part.support())); }
				}
			}
		}
		System.out.printf("POUR donorTraceBound=%g cell=%d axis=%d positive=%s courantRate=%g%n", traceMaximum,
				traceCell, traceAxis, tracePositive, d.cellLiquidVolumeTransport == null ? Double.NaN : d.cellLiquidVolumeTransport.courantRate(0));
		double wetMaximum = 0; int wetCell = -1, wetBlock = -1; String surface = "none";
		for (int cell = 0; cell < d.cellCount; cell++) {
			var profile = geometry.cellSnapshot(cell); if (profile == null || !field.hasCell(cell)) continue;
			for (var facet : profile.facets()) {
				var center = facet.moments().centroid();
				int block = basis.blockForMicro(cell, facet.microKey() / 6);
				if (block < 0) throw new FluidDomain.PressureSolveBlockedException("Diagnostic wet facet lacks its source block");
				double speed = Math.abs(basis.value(coefficient, facet.axis(), block, center.x(), center.y(), center.z()));
				if (speed > wetMaximum) { wetMaximum = speed; wetCell = cell; wetBlock = block; surface = facet.kind() + " axis=" + facet.axis() + " sign=" + facet.sign() + " micro=" + facet.microKey() / 6 + " area=" + facet.moments().area(); }
			}
		}
		System.out.printf("POUR wetFacetCentroidSpeed=%g cell=%d surface=%s%n", wetMaximum, wetCell, surface);
		if (worstBlock >= 0) cell(d, projection, basis, geometry, coefficient, worstBlock);
		if (wetBlock >= 0 && wetBlock != worstBlock) cell(d, projection, basis, geometry, coefficient, wetBlock);
		if (traceBlock >= 0 && traceBlock != worstBlock && traceBlock != wetBlock) cell(d, projection, basis, geometry, coefficient, traceBlock);
	}
	private static void volumeVelocityMeans(MarkerPressureProjection projection, CellAffineVelocity basis,
	                                      CellAffineVelocityField field, LiquidPressureGeometry geometry) {
		if (!value(projection, "published", Boolean.class)) return;
		var volumes = value(projection, "originalVolume", double[].class);
		var velocities = value(projection, "publishedVelocity", double[][].class);
		if (velocities == null) return;
		double largest = 0; int worstCell = -1, worstSupport = 0, worstAxis = -1;
		for (int block = 0; block < basis.blockCount(); block++) {
			if (basis.blockMarkerCount(block) == 0) continue;
			int cell = basis.physicalCell(block), support = basis.blockSupport(block);
			var mean = field.meanWetVelocity(geometry, cell, support);
			var motion = new double[3]; double volume = 0;
			for (int i = 0; i < basis.blockMarkerCount(block); i++) {
				int p = basis.blockMarker(block, i); volume += volumes[p];
				for (int axis = 0; axis < 3; axis++) motion[axis] += volumes[p] * velocities[axis][p];
			}
			for (int axis = 0; axis < 3; axis++) {
				double defect = Math.abs(motion[axis] / volume - mean[axis]);
				if (defect > largest) { largest = defect; worstCell = cell; worstSupport = support; worstAxis = axis; }
			}
		}
		System.out.printf("POUR bulkVolumeMean largestRawWetVelocityDifferenceMps=%g cell=%d support=%d axis=%d%n",
				largest, worstCell, worstSupport, worstAxis);
	}

	private static void inspectPressureFailure(MarkerPressureProjection projection) {
		if (!value(projection, "active", Boolean.class)) return;
		var operator = projection.operator();
		var rhs = value(projection, "rhs", double[].class);
		var pressure = value(projection, "pressure", double[].class);
		var diagonal = new double[rhs.length]; var image = new double[rhs.length];
		operator.diagonal(diagonal); operator.apply(pressure, image);
		double norm = 0, largest = 0, lowest = Double.POSITIVE_INFINITY, highest = 0; int worst = -1;
		for (int row = 0; row < rhs.length; row++) {
			double residual = Math.abs(rhs[row] - image[row]); norm = Math.hypot(norm, residual);
			if (residual > largest) { largest = residual; worst = row; }
			if (diagonal[row] > 0) lowest = Math.min(lowest, diagonal[row]); highest = Math.max(highest, diagonal[row]);
		}
		System.out.printf("POUR pressure rows=%d rawResidual=%g worstRow=%d worstResidual=%g diagonalRange=[%g,%g] worstRhs=%g worstPressure=%g%n",
				rhs.length, norm / Math.sqrt(Math.max(1, rhs.length)), worst, largest, lowest, highest,
				worst < 0 ? 0 : rhs[worst], worst < 0 ? 0 : pressure[worst]);
		var origin = value(projection, "pressureOrigin", double[].class);
		if (origin != null) {
			var source = operator.referencedSource(value(projection, "wallPredictor", double[][].class), value(projection, "dt", Float.class),
					value(projection, "gasInitialPressure", double[].class), origin);
			var residual = new double[rhs.length]; operator.residual(value(projection, "pressureCorrection", double[].class),
					value(projection, "pressureCorrectionLow", double[].class), source, residual);
			double actual = 0; for (double value : residual) actual = Math.hypot(actual, value);
			System.out.printf("POUR pressure referencedResidual=%g%n", actual / Math.sqrt(Math.max(1, rhs.length)));
		}
		try {
			var basis = operator.rowBasis(); boolean[] observed = new boolean[rhs.length];
			var liquids = value(projection, "liquidRows", int[].class); var gases = value(projection, "gasRows", int[].class);
			for (int row : liquids) if (row >= 0) observed[row] = true;
			for (int row : gases) if (row >= 0) observed[row] = true;
			int anchor = value(projection, "pressureAnchor", Integer.class);
			boolean commonGauge = !value(projection, "hasFreeSurface", Boolean.class) && anchor >= 0
					&& anchor < liquids.length && liquids[anchor] >= 0;
			var rate = new double[rhs.length]; operator.volumeRate(value(projection, "wallPredictor", double[][].class), rate);
			System.out.printf("POUR pressure exactRows=%d dependencies=%d observationNeutral=%s compatibleSource=%s%n",
					basis.size(), basis.dependencyCount(), basis.observationNeutral(observed, diagonal, commonGauge),
					basis.compatibleSource(rhs, rate, value(projection, "dt", Float.class)));
		} catch (PressureRowBasis.Unavailable unavailable) {
			System.out.println("POUR pressure exact certificate unavailable: " + unavailable.getMessage());
		}
	}

	private static void pressureWork(MarkerPressureProjection projection, CellAffineVelocity basis, float dt) {
		var before = value(projection, "wallPredictor", double[][].class);
		var after = value(projection, "candidate", double[][].class);
		var pressure = value(projection, "pressure", double[].class);
		var origin = value(projection, "pressureOrigin", double[].class);
		var correction = value(projection, "pressureCorrection", double[].class);
		var correctionLow = value(projection, "pressureCorrectionLow", double[].class);
		var storage = value(projection, "storage", double[].class);
		var initial = value(projection, "gasInitialPressure", double[].class);
		var operator = projection.operator();
		var force = new double[3][basis.markerCount()]; operator.force(correction, origin, correctionLow, force);
		var rate = new double[pressure.length]; operator.volumeRate(after, rate);
		var residual = new double[pressure.length];
		operator.residual(correction, correctionLow, operator.referencedSource(before, dt, initial, origin), residual);
		var step = SignedDyadic.of(dt); var stepSquared = step.multiply(step);
		var change = mmm.units.SignedDyadic.ZERO; var impulseEnergy = mmm.units.SignedDyadic.ZERO;
		var actualWork = mmm.units.SignedDyadic.ZERO;
		for (int a = 0; a < 3; a++) for (int p = 0; p < basis.markerCount(); p++) {
			var m = mmm.units.SignedDyadic.of(basis.massKilograms(p));
			var u = mmm.units.SignedDyadic.of(before[a][p]); var v = mmm.units.SignedDyadic.of(after[a][p]);
			var delta = v.subtract(u);
			change = change.add(m.multiply(delta).multiply(v.add(u)).multiply(mmm.units.SignedDyadic.of(.5)));
			impulseEnergy = impulseEnergy.add(m.multiply(delta).multiply(delta).multiply(mmm.units.SignedDyadic.of(.5)));
			actualWork = actualWork.add(mmm.units.SignedDyadic.of(force[a][p]).multiply(v.add(u)).multiply(mmm.units.SignedDyadic.of(.5 * dt)));
		}
		var gasWork = mmm.units.SignedDyadic.ZERO; var residualWork = mmm.units.SignedDyadic.ZERO;
		double maximumPressure = 0, maximumResidual = 0, maximumRateDefect = 0; int worst = -1;
		for (int row = 0; row < pressure.length; row++) {
			var p = SignedDyadic.of(origin[row]).add(SignedDyadic.of(correction[row])).add(SignedDyadic.of(correctionLow[row]));
			var response = SignedDyadic.of(storage[row]).multiply(SignedDyadic.of(initial[row]).subtract(p));
			var rowWork = response.multiply(p).multiply(stepSquared);
			gasWork = gasWork.add(rowWork);
			residualWork = residualWork.add(p.multiply(SignedDyadic.of(residual[row])).multiply(stepSquared));
			maximumResidual = Math.max(maximumResidual, Math.abs(residual[row]));
			maximumRateDefect = Math.max(maximumRateDefect, Math.abs(rate[row] - step.multiply(response).toDouble()));
			if (Math.abs(pressure[row]) > maximumPressure) { maximumPressure = Math.abs(pressure[row]); worst = row; }
			if (storage[row] > 0) System.out.printf("POUR gasRow=%d storage=%g p0=%g p=%g gasWork=%g%n", row, storage[row], initial[row], pressure[row],
					rowWork.toDouble());
		}
		System.out.printf("POUR pressureWork deltaKE=%g removedImpulseKE=%g gasWork=%g energyDefect=%g actualForceWork=%g pressureTimesResidual=%g maxResidual=%g maxRateDefect=%g maxPressure=%g row=%d%n",
				change.toDouble(), impulseEnergy.toDouble(), gasWork.toDouble(), change.add(impulseEnergy).subtract(gasWork).toDouble(),
				actualWork.toDouble(), residualWork.toDouble(), maximumResidual, maximumRateDefect, maximumPressure, worst);
	}

	private static void cell(FluidDomain d, MarkerPressureProjection projection, CellAffineVelocity basis,
	                         LiquidPressureGeometry geometry, double[][] coefficients, int block) {
		int cell = basis.physicalCell(block); var profile = geometry.componentSnapshot(cell, basis.blockSupport(block));
		double[] minimum = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
		double[] maximum = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
		double totalMass = 0;
		for (int k = 0; k < basis.blockMarkerCount(block); k++) {
			int marker = basis.blockMarker(block, k); totalMass += basis.massKilograms(marker);
			for (int a = 0; a < 3; a++) { minimum[a] = Math.min(minimum[a], basis.position(marker, a)); maximum[a] = Math.max(maximum[a], basis.position(marker, a)); }
		}
		System.out.printf("POUR cell=%d rank=%d expanded=%s count=%d massKg=%g geometricVolume=%g centroid=%s positionMin=%s max=%s%n",
				cell, basis.rank(block), basis.expandedCell(block), basis.blockMarkerCount(block), totalMass,
				profile == null ? 0 : profile.volume(), profile == null ? "none" : profile.centroid(), Arrays.toString(minimum), Arrays.toString(maximum));
		for (int a = 0; a < 3; a++) System.out.printf("POUR cell=%d axis=%d coefficients=%s%n", cell, a, Arrays.toString(Arrays.copyOfRange(coefficients[a], 4 * block, 4 * block + 4)));
		double[] rawMass = value(projection, "originalMass", double[].class), rawVolume = value(projection, "originalVolume", double[].class);
		double[][] velocity = value(projection, "publishedVelocity", double[][].class);
		for (int k = 0; k < Math.min(12, basis.blockMarkerCount(block)); k++) {
			int marker = basis.blockMarker(block, k);
			System.out.printf("POUR marker=%d position=[%g,%g,%g] rawV=%g rawM=%g velocity=[%g,%g,%g]%n", marker,
					basis.position(marker, 0), basis.position(marker, 1), basis.position(marker, 2), rawVolume[marker], rawMass[marker],
					velocity[0][marker], velocity[1][marker], velocity[2][marker]);
		}
	}

	private static void fluxReplay(FluidDomain failed, float dt) {
		if (failed.chemicalParcels != null || failed.thermalState != null || failed.granularState != null)
			throw new IllegalStateException("Pure-liquid flux replay requires matching side-column reconstruction");
		var projection = failed.pressureSystem.markerProjection();
		var field = value(projection, "candidateField", CellAffineVelocityField.class);
		var basis = value(projection, "basis", CellAffineVelocity.class);
		var old = value(projection, "gasFrame", GasRuntimeGeometry.class);
		var network = value(projection, "gasNetworkOwner", mmm.fluid.air.AirNetwork.class);
		if (!value(projection, "published", Boolean.class) || field == null || value(field, "basis", CellAffineVelocity.class) != basis)
			throw new IllegalStateException("Failed substep has no current published pressure field");
		if (value(projection, "dt", Float.class) != dt) throw new IllegalStateException("Pressure snapshot timestep differs from failure");
		var position = value(projection, "originalPosition", double[][].class);
		var velocity = value(projection, "publishedVelocity", double[][].class);
		var mass = value(projection, "originalMass", double[].class);
		var volume = value(projection, "originalVolume", double[].class);
		int count = volume.length; float[] points = new float[4 * count], speeds = new float[3 * count];
		SignedDyadic total = SignedDyadic.ZERO;
		for (int p = 0; p < count; p++) {
			for (int a = 0; a < 3; a++) {
				points[4 * p + a] = (float) position[a][p]; speeds[3 * p + a] = (float) velocity[a][p];
				if (points[4 * p + a] != position[a][p] || speeds[3 * p + a] != velocity[a][p])
					throw new IllegalStateException("Pressure source position or published velocity is not binary32");
			}
			points[4 * p + 3] = (float) volume[p];
			if (points[4 * p + 3] != volume[p]) throw new IllegalStateException("Pressure source volume is not binary32");
			total = total.add(SignedDyadic.of(volume[p]));
		}
		var source = new FluidDomain(failed.sizeX, failed.sizeY, failed.sizeZ, failed.config, Math.max(1, count));
		source.restoreParticleState(new FlipParticleState(source.sizeX, source.sizeY, source.sizeZ, source.config.cellSize(), count,
				(float) total.toDouble(), points, speeds, value(projection, "geometryMasks", byte[].class)));
		for (int p = 0; p < count; p++) source.particles.setMass(p, mass[p]);
		source.ensureInventoryGrid();
		if (!old.matchesCarrierGeometry(source, new long[source.cellCount]))
			throw new IllegalStateException("Pressure source V/M1 does not match the old gas frame");
		for (int cell = 0; cell < source.cellCount; cell++) for (int micro = 0; micro < 8; micro++) {
			if (source.pressurePhaseGeometry.liquidFractionExact(cell, micro) != old.liquidFractionExact(cell, micro)
					|| source.pressurePhaseGeometry.planeOffset(cell, micro) != old.planeOffset(cell, micro))
				throw new IllegalStateException("Replay PLIC differs from the pressure frame at cell " + cell);
			for (int a = 0; a < 3; a++) if (source.pressurePhaseGeometry.planeNormal(cell, micro, a) != old.planeNormal(cell, micro, a))
				throw new IllegalStateException("Replay PLIC normal differs from the pressure frame at cell " + cell);
		}
		var actual = value(failed.cellLiquidVolumeTransport, "transport", ConservativeLiquidFlux.class);
		var actualVolume = value(failed.cellLiquidVolumeTransport, "exactVolume", SignedDyadic[].class);
		System.arraycopy(failed.gridToParticleStage.advectionCells(), 0, source.gridToParticleStage.advectionCells(), 0, source.cellCount);
		source.gasState.frame = old; source.gasState.network = network;
		// Transport saved its source classification before remap and wall projection rebuilt it.
		for (int cell = 0; cell < source.cellCount; cell++) {
			var stock = source.markerGrid.exactCellVolumeInventory[cell];
			if (stock.signum() > 0) {
				if (actualVolume[cell].signum() != 0 && !actualVolume[cell].equals(stock))
					throw new IllegalStateException("Replay source classification differs at cell " + cell
							+ ": rawInventory=" + stock + ", transportInventory=" + actualVolume[cell]);
				source.gridToParticleStage.advectionCells()[cell] = actualVolume[cell].signum() != 0;
			}
		}
		var replay = new ConservativeLiquidFlux(source.sizeX, source.sizeY, source.sizeZ, true);
		var geometry = new LiquidFluxGeometry(source);
		var flow = new LiquidAffineFaceFlow(source, replay, field);
		replay.geometry(geometry); replay.faceSweep(flow); flow.prepareVelocities(replay, geometry);
		var ballistic = new BallisticLiquidRemap(source, dt); replay.reserve(ballistic.reserved);
		SignedDyadic[] exact = new SignedDyadic[source.cellCount]; double[] observed = new double[source.cellCount];
		for (int cell = 0; cell < source.cellCount; cell++) {
			exact[cell] = source.gridToParticleStage.advectionCells()[cell] ? source.markerGrid.exactCellVolumeInventory[cell] : SignedDyadic.ZERO;
			observed[cell] = exact[cell].toDouble();
		}
		requireSameCells("source inventory", actualVolume, exact);
		requireSameCells("ballistic reservation", value(actual, "reserved", SignedDyadic[].class), ballistic.reserved);
		double normalizedDt = dt / source.config.cellSize();
		replay.plan(observed, source.geometry.solid, normalizedDt, exact);
		invokeStage(replay, "prepareGeometry", new Class<?>[]{boolean[].class, double.class, double[].class}, source.geometry.solid, normalizedDt, observed);
		invokeStage(replay, "reconstructPlanes", new Class<?>[]{double[].class, boolean[].class, SignedDyadic[].class}, observed, source.geometry.solid, exact);
		invokeStage(replay, "provisionalFluxes", new Class<?>[]{double[].class, double.class}, observed, normalizedDt);
		invokeStage(replay, "limitAntidiffusion", new Class<?>[]{double[].class, double.class}, observed, normalizedDt);
		double[] continuous = replay.flux.clone(), scaled = continuous.clone();
		double[] outgoing = value(replay, "outgoing", double[].class);
		for (int face = 0; face < scaled.length; face++) {
			int donor = replay.donor(face); scaled[face] *= outgoing[donor] <= observed[donor] ? 1 : observed[donor] / outgoing[donor];
		}
		invokeStage(replay, "quantizeDonorSupply", new Class<?>[]{double[].class, double.class}, observed, normalizedDt);
		invokeStage(replay, "limitChemicalShares", new Class<?>[0]);
		SignedDyadic[] floor = replay.exactFlux.clone(), floorLoss = value(replay, "faceFloorLoss", SignedDyadic[].class).clone();
		invokeStage(replay, "repairSharedFluxes", new Class<?>[]{SignedDyadic[].class, boolean[].class}, exact, source.geometry.solid);
		if (actual.exactFlux.length != replay.exactFlux.length) throw new IllegalStateException("Replay transport channel count differs");
		for (int face = 0; face < replay.exactFlux.length; face++)
			if (actual.left[face] != replay.left[face] || actual.right[face] != replay.right[face] || actual.axis[face] != replay.axis[face]
					|| !actual.exactFlux[face].equals(replay.exactFlux[face]))
				throw new IllegalStateException("Replay final exact flux differs on channel " + face);
		System.out.printf("POUR flux replay verified sourceV/M1+PLIC channels=%d markers=%d%n", replay.exactFlux.length, count);
		var next = GasRuntimeGeometry.capture(failed, null, old.occupiedSvq(), old.sealedExterior());
		var gasRows = value(projection, "gasRows", int[].class);
		var storage = value(projection, "storage", double[].class);
		var initialPressure = value(projection, "gasInitialPressure", double[].class);
		var pressure = value(projection, "pressure", double[].class);
		double reference = value(projection, "pressureReference", Float.class);
		double[] candidateRate = new double[pressure.length], publishedRate = new double[pressure.length];
		projection.operator().volumeRate(value(projection, "candidate", double[][].class), candidateRate);
		projection.operator().volumeRate(velocity, publishedRate);
		int closed = 0;
		for (int node = 1; node <= old.nodeCount(); node++) {
			if (network.inventory(node).isEmpty() || hasPositiveSuccessor(old, next, node)) continue;
			closed++;
			System.out.printf("POUR finiteNodeClosed node=%d stockNanos=%d oldGasM3=%.17g newPositiveOverlap=false minimumPiece=%d%n",
					node, network.inventory(node).standardVolumeNanos(), old.capacity().chambers().get(node - 1).cubicMeters(), old.minimumPiece(node));
			int row = gasRows[node];
			if (row >= 0) {
				double gasVolume = old.capacity().chambers().get(node - 1).cubicMeters();
				double linearRatio = 1 - (double) dt * dt * storage[row] * (pressure[row] - initialPressure[row]) / gasVolume;
				System.out.printf("POUR finiteNodePressure node=%d row=%d storage=%.17g p0=%.17g p=%.17g reference=%.17g p0Abs=%.17g pAbs=%.17g boundaryP0Abs=%.17g linearVolumeRatio=%.17g candidateCgDt=%.17g publishedCgDt=%.17g%n",
						node, row, storage[row], initialPressure[row], pressure[row], reference,
						initialPressure[row] + reference + mmm.fluid.air.AirNetwork.REFERENCE_PRESSURE,
						pressure[row] + reference + mmm.fluid.air.AirNetwork.REFERENCE_PRESSURE,
						network.gaugePressure(node) + mmm.fluid.air.AirNetwork.REFERENCE_PRESSURE, linearRatio,
						candidateRate[row] * dt, publishedRate[row] * dt);
			}
			var cells = new java.util.TreeSet<Integer>();
			for (int piece = 0; piece < 8 * source.cellCount; piece++) if (old.nodeOfPiece(piece) == node) cells.add(piece / 8);
			for (int cell : cells) {
				fluxCell(replay, cell, exact[cell], "continuousFCT", continuous, null);
				fluxCell(replay, cell, exact[cell], "scaledUnrounded", scaled, null);
				fluxCell(replay, cell, exact[cell], "floor", null, floor);
				fluxCell(replay, cell, exact[cell], "repair", null, replay.exactFlux);
				for (int face = 0; face < replay.flux.length; face++) if (replay.left[face] == cell || replay.right[face] == cell)
					System.out.printf("POUR fluxFace cell=%d channel=%d donor=%d receiver=%d continuous=%.17g scaled=%.17g floor=%s floorLoss=%s final=%s%n",
							cell, face, replay.donor(face), replay.receiver(face), Math.abs(continuous[face]), Math.abs(scaled[face]), floor[face], floorLoss[face], replay.exactFlux[face]);
			}
		}
		System.out.printf("POUR flux finiteNodesWithoutPositiveOverlap=%d%n", closed);
	}

	private static boolean hasPositiveSuccessor(GasRuntimeGeometry old, GasRuntimeGeometry next, int node) {
		for (var piece : old.capacity().pieces()) {
			if (old.nodeOfPiece(piece.id()) != node || next.nodeOfPiece(piece.id()) < 0) continue;
			var before = old.geometry().piece(piece.id()); var after = next.geometry().piece(piece.id());
			var planes = new java.util.ArrayList<GasGeometryOverlap.HalfSpace>(2);
			if (before.dryPlane() != null) planes.add(before.dryPlane());
			if (after.dryPlane() != null) planes.add(after.dryPlane());
			if (!GasGeometryOverlap.normalizedVolume(before.micro(), planes).isZero()) return true;
		}
		return false;
	}

	private static void fluxCell(ConservativeLiquidFlux flux, int cell, SignedDyadic initial, String stage,
	                             double[] approximate, SignedDyadic[] exact) {
		SignedDyadic net = SignedDyadic.ZERO;
		for (int face = 0; face < flux.flux.length; face++) {
			SignedDyadic amount = exact == null ? SignedDyadic.of(Math.abs(approximate[face])) : exact[face];
			if (flux.donor(face) == cell) net = net.subtract(amount);
			if (flux.receiver(face) == cell) net = net.add(amount);
		}
		var capacity = value(flux, "exactCapacity", SignedDyadic[].class)[cell];
		var remaining = capacity.subtract(initial).subtract(net);
		System.out.printf("POUR fluxCell cell=%d stage=%s initial=%s net=%s remainingGap=%s gapValue=%.17g%n",
				cell, stage, initial, net, remaining, remaining.toDouble());
	}

	private static void invokeStage(Object target, String name, Class<?>[] types, Object... arguments) {
		try {
			var method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true); method.invoke(target, arguments);
		} catch (java.lang.reflect.InvocationTargetException failure) {
			if (failure.getCause() instanceof RuntimeException cause) throw cause;
			throw new AssertionError("Flux replay stage failed: " + name, failure.getCause());
		} catch (ReflectiveOperationException failure) { throw new AssertionError("Missing flux replay stage: " + name, failure); }
	}

	private static void requireSameCells(String label, SignedDyadic[] expected, SignedDyadic[] replay) {
		if (expected.length != replay.length) throw new IllegalStateException("Replay " + label + " cell count differs");
		for (int cell = 0; cell < expected.length; cell++) if (!expected[cell].equals(replay[cell]))
			throw new IllegalStateException("Replay " + label + " differs at cell " + cell + ": actual=" + expected[cell]
					+ ", replay=" + replay[cell] + ", actualValue=" + expected[cell].toDouble() + ", replayValue=" + replay[cell].toDouble());
	}

	private static <T> T value(Object object, String name, Class<T> type) {
		try {
			Field field = object.getClass().getDeclaredField(name); field.setAccessible(true);
			return type.cast(field.get(object));
		} catch (ReflectiveOperationException failure) { throw new AssertionError("Missing pressure diagnostic snapshot: " + name, failure); }
	}
}
