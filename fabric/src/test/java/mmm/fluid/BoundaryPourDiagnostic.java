package mmm.fluid;

import java.util.Arrays;
import mmm.units.SignedDyadic;

/** Bounded first-pour diagnostic using the production mechanical substep and explicit pressure diagnostic views. */
public final class BoundaryPourDiagnostic {
	private BoundaryPourDiagnostic() { }

	public static void main(String[] args) {
		boolean fluxOnly = false, lShape = false;
		for (String arg : args) {
			if (arg.equals("--flux") && !fluxOnly) fluxOnly = true;
			else if (arg.equals("--l-shape") && !lShape) lShape = true;
			else throw new IllegalArgumentException("Boundary pour diagnostic accepts --flux and --l-shape");
		}
		run(fluxOnly, lShape);
	}

	private static void run(boolean fluxOnly, boolean lShape) {
		var initial = lShape ? CellRemapMomentsValidation.lShapedTranslationSource() : pouredSource();
		var config = initial.config();
		System.out.println("POUR fixture=" + (lShape ? "l-shape" : "sealed-pour"));
		FluidDomainState saved = null;
		GasRuntimeGeometry previousGas = null;
		RuntimeException firstFailure = null;
		for (int logical = 0; logical < 4; logical++) {
			if (!fluxOnly) inspectInput(initial, "logical=" + logical + " before-capture");
			long captureStarted = System.nanoTime();
			saved = initial.captureAuthoritativeState();
			long captureNanos = System.nanoTime() - captureStarted;
			if (!fluxOnly) inspectGas(initial, previousGas, logical);
			previousGas = initial.gasState.frame;
			if (!fluxOnly) inspectInput(initial, "logical=" + logical + " after-capture");
			long restoreStarted = System.nanoTime();
			var restored = FluidDomain.restoreAuthoritativeState(saved);
			long restoreNanos = System.nanoTime() - restoreStarted;
			if (!fluxOnly) inspectInput(restored, "logical=" + logical + " restored-input");
			long evaluationStarted = System.nanoTime();
			FluidStepCandidate candidate;
			try {
				candidate = new CpuFluidSolverBackend().evaluate(new FluidStepRequest(0, saved, java.util.List.of(), false, 0));
			} catch (RuntimeException failed) {
				firstFailure = failed;
				System.out.printf("POUR full logical=%d stage=FULL_STEP captureMs=%.3f restoreMs=%.3f evaluateMs=%.3f failure=%s%n",
						logical, captureNanos / 1e6, restoreNanos / 1e6, (System.nanoTime() - evaluationStarted) / 1e6, failed);
				break;
			}
			double evaluateMs = (System.nanoTime() - evaluationStarted) / 1e6;
			initial = candidate.claimPreparedCpuDomain();
			System.out.printf("POUR full logical=%d stage=COMPLETE completed=%d reserved=%d captureMs=%.3f restoreMs=%.3f evaluateMs=%.3f actualSpeed=%g markers=%d%n",
					logical, candidate.stats().substeps(), candidate.mechanicalResolution().reservedSubsteps(), captureNanos / 1e6,
					restoreNanos / 1e6, evaluateMs, initial.maximumParticleSpeed(), initial.particleCount());
			snapshot(initial, logical, -1, config.timeStep(), "after-full-step", fluxOnly);
			initial = FluidDomain.restoreAuthoritativeState(initial.captureAuthoritativeState());
			if (logical == 3) { System.out.println("POUR diagnostic completed four logical steps"); return; }
		}
		int minimum = 1; long spent = 0, completed = 0, computeNanos = 0;
		String lastRefinement = "";
		for (int attempt = 0; attempt < 16; attempt++) {
			long preparationStarted = System.nanoTime();
			var domain = FluidDomain.restoreAuthoritativeState(saved);
			domain.minimumMechanicalSubsteps = minimum;
			int count = domain.calculateSubsteps(config.timeStep());
			computeNanos += System.nanoTime() - preparationStarted;
			spent += count;
			System.out.printf("POUR attempt=%d stage=SUBSTEP_RESERVATION count=%d completed=%d reserved=%d computeMs=%.3f sourceMarkers=%d gasPa=%g lastRefinement=%s%n",
					attempt, count, completed, spent, computeNanos / 1e6, domain.particleCount(), domain.gasBoundary().gaugePressure(1), lastRefinement);
			if (spent > CpuFluidSolverBackend.MAXIMUM_CUMULATIVE_SUBSTEPS) {
				System.out.printf("POUR replay stage=SUBSTEP_BUDGET completed=%d reserved=%d computeMs=%.3f originalFailure=%s%n", completed, spent, computeNanos / 1e6, firstFailure);
				throw firstFailure;
			}
			if (!fluxOnly) inspectInput(domain, "failed-source before-substeps attempt=" + attempt);
			float dt = config.timeStep() / count;
			domain.carrierPropertiesWorkspace.beginStep();
			boolean refined = false;
			for (int substep = 0; substep < count; substep++) {
				long started = System.nanoTime();
				try {
					domain.mechanicalPipeline.substep(dt, substep, false, null);
				} catch (MechanicalTimeRefinement finer) {
					long elapsed = System.nanoTime() - started; computeNanos += elapsed;
					replayProgress(domain, attempt, substep, completed, spent, elapsed, computeNanos, finer);
					failureSnapshot(domain, attempt, substep, dt, "refine=" + finer.getMessage(), fluxOnly, finer);
					minimum = CpuFluidSolverBackend.refinedSubsteps(count, finer);
					lastRefinement = finer.getMessage();
					refined = true; break;
				} catch (RuntimeException failed) {
					long elapsed = System.nanoTime() - started; computeNanos += elapsed;
					replayProgress(domain, attempt, substep, completed, spent, elapsed, computeNanos, failed);
					failureSnapshot(domain, attempt, substep, dt, "failed=" + failed, fluxOnly, failed);
					throw failed;
				}
				long elapsed = System.nanoTime() - started; computeNanos += elapsed; completed++;
				if (substep < 4 || substep % 128 == 0 || substep == count - 1) {
					replayProgress(domain, attempt, substep, completed, spent, elapsed, computeNanos, null);
					snapshot(domain, attempt, substep, dt, "complete", fluxOnly);
				}
			}
			if (!refined) {
				System.out.println("POUR mechanical replay completed; full-step failure retained: " + firstFailure);
				throw firstFailure;
			}
		}
		System.out.printf("POUR replay stage=ATTEMPT_LIMIT completed=%d reserved=%d computeMs=%.3f originalFailure=%s%n", completed, spent, computeNanos / 1e6, firstFailure);
		throw firstFailure;
	}

	private static FluidDomain pouredSource() {
		var config = new FluidSolverConfig(.5F, 1000, 9.81F, .05F, 120, 1e-4F, .40F, .002F, .80F);
		var domain = new FluidDomain(8, 8, 8, config, 4096);
		domain.sealDomainBoundary(); domain.bootstrapGasState(null); domain.setGasNodeStandardVolume(1, 34);
		var addition = domain.addVolumeNearBudgeted(6, 0, 0, 128, 6);
		if (addition.addedVolume() != 128 || addition.markerBudgetLimited()) throw new AssertionError("Pour diagnostic fixture differs");
		return domain;
	}

	private static void replayProgress(FluidDomain domain, int attempt, int substep, long completed,
	                                   long reserved, long elapsedNanos, long computeNanos, RuntimeException failure) {
		System.out.printf("POUR replay attempt=%d substep=%d stage=%s completed=%d reserved=%d substepMs=%.3f computeMs=%.3f outcome=%s%n",
				attempt, substep, domain.mechanicalPipeline.diagnosticStage(), completed, reserved,
				elapsedNanos / 1e6, computeNanos / 1e6, failure == null ? "complete" : failure);
	}

	private static void failureSnapshot(FluidDomain domain, int attempt, int substep, float dt,
	                                    String outcome, boolean fluxOnly, RuntimeException original) {
		try { snapshot(domain, attempt, substep, dt, outcome, fluxOnly); }
		catch (RuntimeException | AssertionError diagnosticFailure) {
			original.addSuppressed(diagnosticFailure);
			System.out.println("POUR additional snapshot failed: " + diagnosticFailure);
		}
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
		System.out.printf("POUR source cell=%d rank=%d reliableRank=%d modeCount=%d count=%d kg=%g rawV=%g fraction=%g normal=[%g,%g,%g] offset=%g%n", cell,
				basis.rank(block), basis.reliableRank(block), basis.modeCount(block), basis.blockMarkerCount(block), mass, volume,
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
		if (outcome.startsWith("failed=") && !d.pressureSystem.markerProjection().diagnosticView().solved())
			inspectPressureFailure(d.pressureSystem.markerProjection());
		if (fluxOnly) {
			System.out.printf("POUR flux snapshot attempt=%d substep=%d dt=%g %s%n", attempt, substep, dt, outcome);
			if (outcome.startsWith("failed=")) {
				if (!d.liquidShapes.isEmpty()) {
					System.out.println("POUR flux unavailable: saved-shape transport prepares a successor partition before general face-flux planning");
				} else {
					try { fluxReplay(d, dt); }
					catch (RuntimeException | AssertionError failure) { System.out.println("POUR flux replay unverified; interpretation stopped: " + failure); }
				}
			}
			return;
		}
		var projection = d.pressureSystem.markerProjection();
		var view = projection.diagnosticView();
		CellAffineVelocity basis = view.basis();
		CellAffineVelocityField field = view.candidateField();
		LiquidPressureGeometry geometry = view.geometry();
		System.out.printf("POUR snapshot attempt=%d substep=%d dt=%g actualSpeed=%g actualMarkers=%d %s%n",
				attempt, substep, dt, d.maximumParticleSpeed(), d.particleCount(), outcome);
		inspectVolumeMoments(d, "snapshot attempt=" + attempt + " substep=" + substep + " " + outcome);
		if (basis == null) return;
		if (!view.active()) {
			var coefficient = new double[3][4 * basis.blockCount()];
			var velocity = new double[3][basis.markerCount()];
			for (int p = 0; p < basis.markerCount(); p++) {
				velocity[0][p] = d.particles.velocityX(p); velocity[1][p] = d.particles.velocityY(p); velocity[2][p] = d.particles.velocityZ(p);
			}
			basis.fit(velocity, coefficient);
			for (int block = 0, shown = 0; block < basis.blockCount() && shown < 4; block++) if (basis.blockMarkerCount(block) > 0
					&& (basis.reliableRank(block) + 1 < basis.rank(block) || !basis.hasRepresentableBasis(block))) {
				inputCell(d, basis, coefficient, block); shown++;
			}
			return;
		}
		boolean hasCandidate = view.solved() || view.published();
		for (String name : new String[]{"originalVelocity", "predictor", "wallPredictor", "candidate", "publishedVelocity"}) {
			if (!hasCandidate && (name.equals("candidate") || name.equals("publishedVelocity"))) continue;
			double[][] velocity = switch (name) {
				case "originalVelocity" -> view.originalVelocity();
				case "predictor" -> view.predictor();
				case "wallPredictor" -> view.wallPredictor();
				case "candidate" -> view.candidate();
				case "publishedVelocity" -> view.publishedVelocity();
				default -> throw new AssertionError(name);
			};
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
		if (!field.usesBasis(basis)) {
			System.out.println("POUR candidate field belongs to an earlier prepare; stopping snapshot before field comparison");
			return;
		}
		System.out.printf("POUR wallNormalWork=%g wallRepresentationWork=%g%n", view.wallNormalWork(), view.wallRepresentationWork());
		pressureWork(projection, basis, view.dt());
		volumeVelocityMeans(projection, basis, field, geometry);
		double[][] coefficient = field.diagnosticCoefficients();
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
		var view = projection.diagnosticView();
		if (!view.published()) return;
		var volumes = view.originalVolume();
		var velocities = view.publishedVelocity();
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
		var view = projection.diagnosticView();
		if (!view.active()) return;
		var operator = projection.operator();
		var rhs = view.rhs();
		var pressure = view.pressure();
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
		var origin = view.pressureOrigin();
		if (origin != null) {
			var source = operator.referencedSource(view.wallPredictor(), view.dt(),
					view.gasInitialPressure(), origin);
			var residual = new double[rhs.length]; operator.residual(view.pressureCorrection(),
					view.pressureCorrectionLow(), source, residual);
			double actual = 0; for (double value : residual) actual = Math.hypot(actual, value);
			System.out.printf("POUR pressure referencedResidual=%g%n", actual / Math.sqrt(Math.max(1, rhs.length)));
		}
		try {
			var basis = operator.rowBasis(); boolean[] observed = new boolean[rhs.length];
			var liquids = view.liquidRows(); var gases = view.gasRows();
			for (int row : liquids) if (row >= 0) observed[row] = true;
			for (int row : gases) if (row >= 0) observed[row] = true;
			int anchor = view.pressureAnchor();
			boolean commonGauge = !view.hasFreeSurface() && anchor >= 0
					&& anchor < liquids.length && liquids[anchor] >= 0;
			var rate = new double[rhs.length]; operator.volumeRate(view.wallPredictor(), rate);
			System.out.printf("POUR pressure exactRows=%d dependencies=%d observationNeutral=%s compatibleSource=%s%n",
					basis.size(), basis.dependencyCount(), basis.observationNeutral(observed, diagonal, commonGauge),
					basis.compatibleSource(rhs, rate, view.dt()));
		} catch (PressureRowBasis.Unavailable unavailable) {
			System.out.println("POUR pressure exact certificate unavailable: " + unavailable.getMessage());
		}
	}

	private static void pressureWork(MarkerPressureProjection projection, CellAffineVelocity basis, float dt) {
		var view = projection.diagnosticView();
		var before = view.wallPredictor();
		var after = view.candidate();
		var pressure = view.pressure();
		var origin = view.pressureOrigin();
		var correction = view.pressureCorrection();
		var correctionLow = view.pressureCorrectionLow();
		var storage = view.storage();
		var initial = view.gasInitialPressure();
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
		var view = projection.diagnosticView();
		int cell = basis.physicalCell(block); var profile = geometry.componentSnapshot(cell, basis.blockSupport(block));
		double[] minimum = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
		double[] maximum = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
		double totalMass = 0;
		for (int k = 0; k < basis.blockMarkerCount(block); k++) {
			int marker = basis.blockMarker(block, k); totalMass += basis.massKilograms(marker);
			for (int a = 0; a < 3; a++) { minimum[a] = Math.min(minimum[a], basis.position(marker, a)); maximum[a] = Math.max(maximum[a], basis.position(marker, a)); }
		}
		System.out.printf("POUR cell=%d rank=%d reliableRank=%d modeCount=%d count=%d massKg=%g geometricVolume=%g centroid=%s positionMin=%s max=%s%n",
				cell, basis.rank(block), basis.reliableRank(block), basis.modeCount(block), basis.blockMarkerCount(block), totalMass,
				profile == null ? 0 : profile.volume(), profile == null ? "none" : profile.centroid(), Arrays.toString(minimum), Arrays.toString(maximum));
		for (int a = 0; a < 3; a++) System.out.printf("POUR cell=%d axis=%d coefficients=%s%n", cell, a, Arrays.toString(Arrays.copyOfRange(coefficients[a], 4 * block, 4 * block + 4)));
		double[] rawMass = view.originalMass(), rawVolume = view.originalVolume();
		double[][] velocity = view.publishedVelocity();
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
		var view = projection.diagnosticView();
		var field = view.candidateField();
		var basis = view.basis();
		var old = view.gasFrame();
		var network = view.gasNetworkOwner();
		if (!view.published() || field == null || !field.usesBasis(basis))
			throw new IllegalStateException("Failed substep has no current published pressure field");
		if (view.dt() != dt) throw new IllegalStateException("Pressure snapshot timestep differs from failure");
		var position = view.originalPosition();
		var velocity = view.publishedVelocity();
		var mass = view.originalMass();
		var volume = view.originalVolume();
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
				(float) total.toDouble(), points, speeds, view.geometryMasks()));
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
		var transportView = failed.cellLiquidVolumeTransport.diagnosticView();
		var actual = transportView.transport();
		var actualVolume = transportView.exactVolume();
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
		requireSameCells("ballistic reservation", actual.diagnosticReservedVolume(), ballistic.reserved);
		double normalizedDt = dt / source.config.cellSize();
		var stages = replay.diagnosticPlan(observed, source.geometry.solid, normalizedDt, exact);
		double[] continuous = stages.continuous(), scaled = stages.supplyScaled();
		SignedDyadic[] floor = stages.floor(), floorLoss = stages.floorLoss();
		if (actual.exactFlux.length != replay.exactFlux.length) throw new IllegalStateException("Replay transport channel count differs");
		for (int face = 0; face < replay.exactFlux.length; face++)
			if (actual.left[face] != replay.left[face] || actual.right[face] != replay.right[face] || actual.axis[face] != replay.axis[face]
					|| !actual.exactFlux[face].equals(replay.exactFlux[face]))
				throw new IllegalStateException("Replay final exact flux differs on channel " + face);
		System.out.printf("POUR flux replay verified sourceV/M1+PLIC channels=%d markers=%d%n", replay.exactFlux.length, count);
		var next = GasRuntimeGeometry.capture(failed, null, old.occupiedSvq(), old.sealedExterior());
		var gasRows = view.gasRows();
		var storage = view.storage();
		var initialPressure = view.gasInitialPressure();
		var pressure = view.pressure();
		double reference = view.pressureReference();
		double[] candidateRate = new double[pressure.length], publishedRate = new double[pressure.length];
		projection.operator().volumeRate(view.candidate(), candidateRate);
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
		var capacity = flux.diagnosticCapacity(cell);
		var remaining = capacity.subtract(initial).subtract(net);
		System.out.printf("POUR fluxCell cell=%d stage=%s initial=%s net=%s remainingGap=%s gapValue=%.17g%n",
				cell, stage, initial, net, remaining, remaining.toDouble());
	}

	private static void requireSameCells(String label, SignedDyadic[] expected, SignedDyadic[] replay) {
		if (expected.length != replay.length) throw new IllegalStateException("Replay " + label + " cell count differs");
		for (int cell = 0; cell < expected.length; cell++) if (!expected[cell].equals(replay[cell]))
			throw new IllegalStateException("Replay " + label + " differs at cell " + cell + ": actual=" + expected[cell]
					+ ", replay=" + replay[cell] + ", actualValue=" + expected[cell].toDouble() + ", replayValue=" + replay[cell].toDouble());
	}

}
