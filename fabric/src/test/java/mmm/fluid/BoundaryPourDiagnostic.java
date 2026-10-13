package mmm.fluid;

import java.lang.reflect.Field;
import java.util.Arrays;

/** Bounded first-pour diagnostic using the production mechanical substep and private pressure snapshots. */
public final class BoundaryPourDiagnostic {
	private BoundaryPourDiagnostic() { }

	public static void main(String[] args) {
		if (args.length != 0) throw new IllegalArgumentException("Boundary pour diagnostic accepts no arguments");
		run();
	}

	private static void run() {
		var config = new FluidSolverConfig(.5F, 1000, 9.81F, .05F, 120, 1e-4F, .40F, .002F, .80F);
		var initial = new FluidDomain(8, 8, 8, config, 4096);
		initial.sealDomainBoundary(); initial.bootstrapGasState(null); initial.setGasNodeStandardVolume(1, 34);
		var addition = initial.addVolumeNearBudgeted(6, 0, 0, 128, 6);
		if (addition.addedVolume() != 128 || addition.markerBudgetLimited()) throw new AssertionError("Pour diagnostic fixture differs");
		FluidDomainState saved = null;
		GasRuntimeGeometry previousGas = null;
		for (int logical = 0; logical < 4; logical++) {
			inspectInput(initial, "logical=" + logical + " before-capture");
			saved = initial.captureAuthoritativeState();
			inspectGas(initial, previousGas, logical);
			previousGas = initial.gasState.frame;
			inspectInput(initial, "logical=" + logical + " after-capture");
			var restored = FluidDomain.restoreAuthoritativeState(saved);
			inspectInput(restored, "logical=" + logical + " restored-input");
			try {
				var candidate = new CpuFluidSolverBackend().evaluate(new FluidStepRequest(0, saved, java.util.List.of(), false, 0));
				initial = candidate.claimPreparedCpuDomain();
				System.out.printf("POUR full logical=%d complete actualSpeed=%g markers=%d%n", logical, initial.maximumParticleSpeed(), initial.particleCount());
				snapshot(initial, logical, -1, config.timeStep(), "after-full-step");
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
			inspectInput(domain, "failed-source before-CFL attempt=" + attempt);
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
					if (substep < 4 || substep % 128 == 0) snapshot(domain, attempt, substep, dt, "complete");
				} catch (MechanicalTimeRefinement finer) {
					snapshot(domain, attempt, substep, dt, "refine=" + finer.getMessage());
					minimum = Math.max(Math.multiplyExact(minimum, 2), finer.minimumSubsteps);
					refined = true; break;
				} catch (RuntimeException failed) {
					snapshot(domain, attempt, substep, dt, "failed=" + failed);
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
		var basis = CellAffineVelocity.capture(d);
		var velocity = new double[3][d.particleCount()];
		for (int p = 0; p < d.particleCount(); p++) {
			velocity[0][p] = d.particles.velocityX(p); velocity[1][p] = d.particles.velocityY(p); velocity[2][p] = d.particles.velocityZ(p);
		}
		var coefficients = new double[3][4 * d.cellCount]; basis.fit(velocity, coefficients);
		var field = new CellAffineVelocityField(basis, coefficients);
		double gradient = 0, trace = 0; int gradientCell = -1, traceCell = -1;
		for (int cell = 0; cell < d.cellCount; cell++) if (field.hasCell(cell)) for (int axis = 0; axis < 3; axis++) {
			for (int term = 1; term < 4; term++) {
				double value = Math.abs(coefficients[axis][4 * cell + term] / basis.cellSize());
				if (value > gradient) { gradient = value; gradientCell = cell; }
			}
			for (boolean positive : new boolean[]{false, true}) {
				var q = new double[3]; field.faceTrace(cell, axis, positive, d.sizeX, d.sizeY, q);
				for (int t = 0; t < 2; t++) for (int u = 0; u < 2; u++) {
					double value = Math.abs(q[0] + t * q[1] + u * q[2]);
					if (value > trace) { trace = value; traceCell = cell; }
				}
			}
		}
		System.out.printf("POUR input=%s actualSpeed=%g markers=%d freshGradient=%g cell=%d fullTrace=%g cell=%d gridDirty=%s velocityDirty=%s%n",
				stage, d.maximumParticleSpeed(), d.particleCount(), gradient, gradientCell, trace, traceCell, d.gridDirty, d.velocityGridDirty);
		if (gradientCell >= 0) inputCell(d, basis, coefficients, gradientCell);
		if (traceCell >= 0 && traceCell != gradientCell) inputCell(d, basis, coefficients, traceCell);
	}

	private static void inputCell(FluidDomain d, CellAffineVelocity basis, double[][] coefficient, int cell) {
		double mass = 0, volume = 0;
		for (int k = 0; k < basis.cellMarkerCount(cell); k++) {
			int p = basis.cellMarker(cell, k); mass += basis.massKilograms(p); volume += d.particles.volume(p);
		}
		System.out.printf("POUR source cell=%d rank=%d expanded=%s count=%d kg=%g rawV=%g fraction=%g normal=[%g,%g,%g] offset=%g%n", cell,
				basis.rank(cell), basis.expandedCell(cell), basis.cellMarkerCount(cell), mass, volume,
				d.pressurePhaseGeometry.liquidFractionExact(cell), d.pressurePhaseGeometry.planeNormal(cell, 0),
				d.pressurePhaseGeometry.planeNormal(cell, 1), d.pressurePhaseGeometry.planeNormal(cell, 2), d.pressurePhaseGeometry.planeOffset(cell));
		for (int a = 0; a < 3; a++) System.out.printf("POUR source cell=%d axis=%d coefficients=%s%n", cell, a, Arrays.toString(Arrays.copyOfRange(coefficient[a], 4 * cell, 4 * cell + 4)));
		for (int k = 0; k < Math.min(12, basis.cellMarkerCount(cell)); k++) {
			int p = basis.cellMarker(cell, k);
			System.out.printf("POUR source marker=%d pos=[%.12g,%.12g,%.12g] V=%.12g M=%.12g v=[%.12g,%.12g,%.12g]%n", p,
					basis.position(p, 0), basis.position(p, 1), basis.position(p, 2), d.particles.volume(p), d.particles.mass(p),
					d.particles.velocityX(p), d.particles.velocityY(p), d.particles.velocityZ(p));
		}
	}

	private static void snapshot(FluidDomain d, int attempt, int substep, float dt, String outcome) {
		var projection = d.pressureSystem.markerProjection();
		CellAffineVelocity basis = value(projection, "basis", CellAffineVelocity.class);
		CellAffineVelocityField field = value(projection, "candidateField", CellAffineVelocityField.class);
		LiquidPressureGeometry geometry = value(projection, "geometry", LiquidPressureGeometry.class);
		System.out.printf("POUR snapshot attempt=%d substep=%d dt=%g actualSpeed=%g actualMarkers=%d %s%n",
				attempt, substep, dt, d.maximumParticleSpeed(), d.particleCount(), outcome);
		if (basis == null || !value(projection, "active", Boolean.class)) return;
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
			System.out.printf("POUR velocity=%s max=%g KE=%g marker=%d cell=%d%n", name, maximum, energy, marker, marker < 0 ? -1 : basis.markerCell(marker));
		}
		if (!hasCandidate || field == null || geometry == null) return;
		if (value(field, "basis", CellAffineVelocity.class) != basis) {
			System.out.println("POUR candidate field belongs to an earlier prepare; stopping snapshot before field comparison");
			return;
		}
		pressureWork(projection, basis, value(projection, "dt", Float.class));
		double[][] coefficient = value(field, "coefficients", double[][].class);
		double largest = 0; int worstCell = -1, worstAxis = -1, worstTerm = -1;
		for (int cell = 0; cell < d.cellCount; cell++) if (field.hasCell(cell))
			for (int axis = 0; axis < 3; axis++) for (int term = 1; term < 4; term++) {
				double derivative = Math.abs(coefficient[axis][4 * cell + term] / basis.cellSize());
				if (derivative > largest) { largest = derivative; worstCell = cell; worstAxis = axis; worstTerm = term; }
			}
		System.out.printf("POUR affineGradient=%g cell=%d axis=%d term=%d%n", largest, worstCell, worstAxis, worstTerm);
		var faces = new ConservativeLiquidFlux(d.sizeX, d.sizeY, d.sizeZ);
		var flow = new LiquidAffineFaceFlow(d, faces, field);
		double traceMaximum = 0; int traceCell = -1, traceAxis = -1; boolean tracePositive = false;
		for (int face = 0; face < faces.physicalFaceCount; face++) for (boolean positive : new boolean[]{false, true}) {
			int donor = positive ? faces.left[face] : faces.right[face];
			if (d.geometry.solid[faces.left[face]] || d.geometry.solid[faces.right[face]]) continue;
			double speed = flow.maximumOutwardSpeed(face, positive, 0);
			if (speed > traceMaximum) { traceMaximum = speed; traceCell = donor; traceAxis = faces.axis[face]; tracePositive = positive; }
		}
		System.out.printf("POUR donorTraceBound=%g cell=%d axis=%d positive=%s courantRate=%g%n", traceMaximum,
				traceCell, traceAxis, tracePositive, d.cellLiquidVolumeTransport == null ? Double.NaN : d.cellLiquidVolumeTransport.courantRate(0));
		double wetMaximum = 0; int wetCell = -1; String surface = "none";
		for (int cell = 0; cell < d.cellCount; cell++) {
			var profile = geometry.cellSnapshot(cell); if (profile == null || !field.hasCell(cell)) continue;
			for (var facet : profile.facets()) {
				var center = facet.moments().centroid();
				double speed = Math.abs(field.value(facet.axis(), cell, center.x(), center.y(), center.z()));
				if (speed > wetMaximum) { wetMaximum = speed; wetCell = cell; surface = facet.kind() + " axis=" + facet.axis() + " sign=" + facet.sign() + " micro=" + facet.microKey() / 6 + " area=" + facet.moments().area(); }
			}
		}
		System.out.printf("POUR wetFacetCentroidSpeed=%g cell=%d surface=%s%n", wetMaximum, wetCell, surface);
		if (worstCell >= 0) cell(d, projection, basis, geometry, coefficient, worstCell);
		if (wetCell >= 0 && wetCell != worstCell) cell(d, projection, basis, geometry, coefficient, wetCell);
		if (traceCell >= 0 && traceCell != worstCell && traceCell != wetCell) cell(d, projection, basis, geometry, coefficient, traceCell);
	}

	private static void pressureWork(MarkerPressureProjection projection, CellAffineVelocity basis, float dt) {
		var before = value(projection, "wallPredictor", double[][].class);
		var after = value(projection, "candidate", double[][].class);
		var pressure = value(projection, "pressure", double[].class);
		var storage = value(projection, "storage", double[].class);
		var initial = value(projection, "gasInitialPressure", double[].class);
		var rhs = value(projection, "rhs", double[].class);
		var operator = projection.operator();
		var force = new double[3][basis.markerCount()]; operator.force(pressure, force);
		var rate = new double[pressure.length]; operator.volumeRate(after, rate);
		var applied = new double[pressure.length]; operator.apply(pressure, applied);
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
			var p = mmm.units.SignedDyadic.of(pressure[row]); var s = mmm.units.SignedDyadic.of(storage[row]);
			gasWork = gasWork.add(s.multiply(p).multiply(mmm.units.SignedDyadic.of(initial[row]).subtract(p)).multiply(mmm.units.SignedDyadic.of((double) dt * dt)));
			residualWork = residualWork.add(p.multiply(mmm.units.SignedDyadic.of(rhs[row]).subtract(mmm.units.SignedDyadic.of(applied[row]))).multiply(mmm.units.SignedDyadic.of((double) dt * dt)));
			maximumResidual = Math.max(maximumResidual, Math.abs(rhs[row] - applied[row]));
			maximumRateDefect = Math.max(maximumRateDefect, Math.abs(rate[row] - dt * storage[row] * (initial[row] - pressure[row])));
			if (Math.abs(pressure[row]) > maximumPressure) { maximumPressure = Math.abs(pressure[row]); worst = row; }
			if (storage[row] > 0) System.out.printf("POUR gasRow=%d storage=%g p0=%g p=%g gasWork=%g%n", row, storage[row], initial[row], pressure[row],
					(double) dt * dt * storage[row] * pressure[row] * (initial[row] - pressure[row]));
		}
		System.out.printf("POUR pressureWork deltaKE=%g removedImpulseKE=%g gasWork=%g energyDefect=%g actualForceWork=%g pressureTimesResidual=%g maxResidual=%g maxRateDefect=%g maxPressure=%g row=%d%n",
				change.toDouble(), impulseEnergy.toDouble(), gasWork.toDouble(), change.add(impulseEnergy).subtract(gasWork).toDouble(),
				actualWork.toDouble(), residualWork.toDouble(), maximumResidual, maximumRateDefect, maximumPressure, worst);
	}

	private static void cell(FluidDomain d, MarkerPressureProjection projection, CellAffineVelocity basis,
	                         LiquidPressureGeometry geometry, double[][] coefficients, int cell) {
		var profile = geometry.cellSnapshot(cell);
		double[] minimum = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
		double[] maximum = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
		double totalMass = 0;
		for (int k = 0; k < basis.cellMarkerCount(cell); k++) {
			int marker = basis.cellMarker(cell, k); totalMass += basis.massKilograms(marker);
			for (int a = 0; a < 3; a++) { minimum[a] = Math.min(minimum[a], basis.position(marker, a)); maximum[a] = Math.max(maximum[a], basis.position(marker, a)); }
		}
		System.out.printf("POUR cell=%d rank=%d expanded=%s count=%d massKg=%g geometricVolume=%g centroid=%s positionMin=%s max=%s%n",
				cell, basis.rank(cell), basis.expandedCell(cell), basis.cellMarkerCount(cell), totalMass,
				profile == null ? 0 : profile.volume(), profile == null ? "none" : profile.centroid(), Arrays.toString(minimum), Arrays.toString(maximum));
		for (int a = 0; a < 3; a++) System.out.printf("POUR cell=%d axis=%d coefficients=%s%n", cell, a, Arrays.toString(Arrays.copyOfRange(coefficients[a], 4 * cell, 4 * cell + 4)));
		double[] rawMass = value(projection, "originalMass", double[].class), rawVolume = value(projection, "originalVolume", double[].class);
		double[][] velocity = value(projection, "publishedVelocity", double[][].class);
		for (int k = 0; k < Math.min(12, basis.cellMarkerCount(cell)); k++) {
			int marker = basis.cellMarker(cell, k);
			System.out.printf("POUR marker=%d position=[%g,%g,%g] rawV=%g rawM=%g velocity=[%g,%g,%g]%n", marker,
					basis.position(marker, 0), basis.position(marker, 1), basis.position(marker, 2), rawVolume[marker], rawMass[marker],
					velocity[0][marker], velocity[1][marker], velocity[2][marker]);
		}
	}

	private static <T> T value(Object object, String name, Class<T> type) {
		try {
			Field field = object.getClass().getDeclaredField(name); field.setAccessible(true);
			return type.cast(field.get(object));
		} catch (ReflectiveOperationException failure) { throw new AssertionError("Missing pressure diagnostic snapshot: " + name, failure); }
	}
}
