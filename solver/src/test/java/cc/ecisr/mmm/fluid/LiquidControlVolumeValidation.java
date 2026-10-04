package cc.ecisr.mmm.fluid;

import java.util.ArrayList;
import java.util.List;

/** Pressure behavior is tied to liquid geometry, including controls thinner than half a base cell. */
final class LiquidControlVolumeValidation {
	private static final LiquidPolyhedron.Point GRAVITY = new LiquidPolyhedron.Point(0, -9.81, 0);
	static void validate() {
		for (double size : new double[] {0.5, 0.125, 1.0 / 1024}) freeLiquidReceivesGravity(size);
		for (double height : new double[] {0.5, 0.125, 1.0 / 1024}) thinHydrostaticColumn(height);
		for (double size : new double[] {0.125, 1e-4, 1e-7}) smallHydrostaticCube(size);
		densityJumpUsesBothLiquidDistances();
		refinedHydrostaticColumn();
		refinedAffinePressure();
		refinedViscosityPreservesRigidMotionAndMomentum();
		finiteGasPressureSharesLiquidProjection();
		pressureAndViscosityShareMomentumAndEnergy();
		sharedVelocityReconstructionPreservesTranslation();
		vanishingContactAreaRemovesInteractionContinuously();
		movingSolidPressureClosesWorkAndImpulse();
		sharedVelocityTrajectoriesCrossRefinementContinuously();
		interfaceExtensionPreservesLiquidTracesAndGasVolume();
		conservativeCoordinatesPreserveConstraintsAndVirtualWork();
		pressureReferencePreservesSmallResolvedForces();
		interfacePressureJumpPreservesGasPressure();
		System.out.println("Liquid control-volume pressure and viscosity validation passed");
	}

	private static void interfacePressureJumpPreservesGasPressure() {
		var geometry = LiquidPolyhedron.box(0, 0, 0, 1, 1, 1);
		var faces = geometry.surfaces().stream().map(s -> new LiquidControlVolumeProjection.Face(0, -1, s, 102000)).toList();
		var result = new LiquidControlVolumeProjection(List.of(new LiquidControlVolumeProjection.Cell(geometry, 1000)), faces,
				List.of(new LiquidControlVolumeProjection.GasNode(100000, 1e-6)), new int[faces.size()])
				.project(new double[3], new LiquidPolyhedron.Point(0, 0, 0), 0.025, 1e-11, 128);
		close(result.pressure()[0], 102000, 1e-9); close(result.gasPressure()[0], 100000, 0);
		close(result.gasPressureChange()[0], 0, 0); close(result.gasVolumeChange()[0], 0, 0);
		for (double velocity : result.cellVelocity()) close(velocity, 0, 0); requireExactContinuity(1, faces, result);
	}

	private static void pressureReferencePreservesSmallResolvedForces() {
		for (double size : new double[] {0.125, 1e-4, 1e-7, 1e-10, 1e-12}) for (double reference : new double[] {0, 100000}) {
			var geometry = LiquidPolyhedron.box(0, 0, 0, size, size, size); var faces = new ArrayList<LiquidControlVolumeProjection.Face>();
			for (var surface : geometry.surfaces()) faces.add(new LiquidControlVolumeProjection.Face(0,
					surface.areaVector().y() > 0 ? -1 : -2, surface, surface.areaVector().y() > 0 ? reference : 0));
			var result = new LiquidControlVolumeProjection(List.of(new LiquidControlVolumeProjection.Cell(geometry, 1000)), faces)
					.project(new double[3], GRAVITY, 0.025, 1e-11, 128);
			requireExactContinuity(1, faces, result);
			for (double velocity : result.cellVelocity()) close(velocity, 0, 0);
			close(result.pressureGradient()[0].y(), 1000 * GRAVITY.y(), 1e-9, "Absolute gas pressure destroyed a resolved hydrostatic gradient");
			double expected = -1000 * geometry.moments().volume() * GRAVITY.y() * 0.025;
			close(result.boundaryImpulse().y() / expected, 1, 1e-11, "Absolute gas pressure destroyed a small resolved pressure impulse");
			close(result.boundaryWorkJoules(), 0, 0);
		}
	}

	private static void conservativeCoordinatesPreserveConstraintsAndVirtualWork() {
		var zero = cc.ecisr.mmm.units.SignedDyadic.ZERO;
		int[] first = {0, 1, 2, 0, 0, 1, 2}, second = {1, 2, 0, 3, 4, 4, 4};
		var fixed = new cc.ecisr.mmm.units.SignedDyadic[first.length]; fixed[6] = cc.ecisr.mmm.units.SignedDyadic.of(0.125);
		var basis = new ConservativeFluxCoordinates(5, first, second, new boolean[] {true, true, true, false, false}, fixed);
		var source = new cc.ecisr.mmm.units.SignedDyadic[] {zero, zero, zero, zero, zero};
		var exact = new cc.ecisr.mmm.units.SignedDyadic[basis.size()]; double[] variable = new double[basis.size()], dual = new double[first.length];
		for (int c = 0; c < exact.length; c++) { exact[c] = cc.ecisr.mmm.units.SignedDyadic.of(c % 2 == 0 ? 1 : -1, -100 * c); variable[c] = Math.sin(c + 0.7); }
		var rates = basis.liftExact(exact, source); var divergence = source.clone();
		for (int f = 0; f < rates.length; f++) { divergence[first[f]] = divergence[first[f]].add(rates[f]); divergence[second[f]] = divergence[second[f]].subtract(rates[f]); dual[f] = Math.cos(f + 0.3); }
		for (int c = 0; c < 3; c++) if (divergence[c].signum() != 0) throw new AssertionError("Flux coordinates lost continuity across disparate exact rates");
		if (!rates[6].equals(fixed[6])) throw new AssertionError("Flux coordinates changed a prescribed wall rate");
		var lifted = basis.lift(variable, new double[5], false); var transpose = basis.transpose(dual); double firstWork = 0, secondWork = 0;
		for (int f = 0; f < dual.length; f++) firstWork += lifted[f] * dual[f];
		for (int c = 0; c < variable.length; c++) secondWork += variable[c] * transpose[c];
		close(firstWork, secondWork, 1e-14, "Conservative flux gradient changed virtual work");
		var closed = new ConservativeFluxCoordinates(2, new int[] {0}, new int[] {1}, new boolean[] {true, true}, new cc.ecisr.mmm.units.SignedDyadic[] {fixed[6]});
		try {
			closed.liftExact(new cc.ecisr.mmm.units.SignedDyadic[0], new cc.ecisr.mmm.units.SignedDyadic[] {zero, zero});
			throw new AssertionError("Closed component accepted an incompatible prescribed volume rate");
		} catch (IllegalArgumentException expected) { }
		var compatible = closed.liftExact(new cc.ecisr.mmm.units.SignedDyadic[0], new cc.ecisr.mmm.units.SignedDyadic[] {fixed[6], fixed[6].negate()});
		if (!compatible[0].equals(fixed[6])) throw new AssertionError("Prescribed volume source changed its compatible face rate");
		var weights = new cc.ecisr.mmm.units.SignedDyadic[] {cc.ecisr.mmm.units.SignedDyadic.of(1), cc.ecisr.mmm.units.SignedDyadic.of(2), cc.ecisr.mmm.units.SignedDyadic.of(4)};
		var shares = ExactVolumeAllocation.allocate(cc.ecisr.mmm.units.SignedDyadic.of(1), weights, -60); var total = zero;
		for (int i = 0; i < shares.length; i++) {
			total = total.add(shares[i]); var units = shares[i].significand().shiftLeft(shares[i].exponent() + 60);
			var numeratorError = units.multiply(java.math.BigInteger.valueOf(7)).subtract(java.math.BigInteger.ONE.shiftLeft(60).multiply(java.math.BigInteger.valueOf(1 << i))).abs();
			if (numeratorError.compareTo(java.math.BigInteger.valueOf(7)) >= 0) throw new AssertionError("Gas source allocation exceeded its declared quantum");
		}
		if (!total.equals(cc.ecisr.mmm.units.SignedDyadic.of(1))) throw new AssertionError("Gas source allocation lost component displacement");
	}

	private static cc.ecisr.mmm.units.SignedDyadic[] faceRates(LiquidControlVolumeMesh mesh, double[] traces) {
		var rates = new cc.ecisr.mmm.units.SignedDyadic[traces.length];
		for (int f = 0; f < rates.length; f++) rates[f] = cc.ecisr.mmm.units.SignedDyadic.of(mesh.faces.get(f).surface().area() * traces[f]);
		return rates;
	}
	private static void requireExactExtensionContinuity(LiquidControlVolumeMesh mesh, LiquidInterfaceVelocityExtension.Result result) {
		var balance = new cc.ecisr.mmm.units.SignedDyadic[mesh.cells.size()]; java.util.Arrays.fill(balance, cc.ecisr.mmm.units.SignedDyadic.ZERO);
		for (int f = 0; f < mesh.faces.size(); f++) {
			var face = mesh.faces.get(f); balance[face.first()] = balance[face.first()].add(result.volumeRate()[f]);
			if (face.second() >= 0) balance[face.second()] = balance[face.second()].subtract(result.volumeRate()[f]);
		}
		for (int c = 0; c < balance.length; c++) if (!balance[c].equals(result.volumeSource()[c]))
			throw new AssertionError("Interface extension lost exact control-volume continuity");
		if (!(result.divergenceResidual() <= result.divergenceRoundoffBound())) throw new AssertionError("Observed extension exceeded roundoff bound");
	}
	private static void interfaceExtensionPreservesLiquidTracesAndGasVolume() {
		var cube = LiquidPolyhedron.box(0, 0, 0, 1, 1, 1);
		var root = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, cube);
		var regions = LiquidPhasePartition.build(root, List.of(LiquidPolyhedron.box(0.25, 0.25, 0.25, 0.75, 0.75, 0.75)), 64);
		int[] liquid = regions.stream().mapToInt(LiquidPhasePartition.Region::liquid).toArray();
		for (double scale : new double[] {1, 0.125, 1.0 / 1024}) for (double speed : new double[] {0, 1}) {
			var mesh = new LiquidControlVolumeMesh(regions.stream().map(LiquidPhasePartition.Region::cell).toList(), scale, 1000,
					(c, surface) -> new LiquidControlVolumeMesh.Boundary(false, 0));
			var pressureFaces = new ArrayList<LiquidControlVolumeProjection.Face>(); var sharedFaces = new ArrayList<Integer>();
			for (int f = 0; f < mesh.faces.size(); f++) {
				var face = mesh.faces.get(f); boolean firstLiquid = liquid[face.first()] >= 0;
				if (!firstLiquid && !(face.second() >= 0 && liquid[face.second()] >= 0)) continue;
				var surface = face.surface(); var vector = surface.areaVector();
				if (!firstLiquid) surface = new LiquidPolyhedron.Surface(surface.area(), new LiquidPolyhedron.Point(-vector.x(), -vector.y(), -vector.z()), surface.centroid());
				pressureFaces.add(new LiquidControlVolumeProjection.Face(0, -1, surface, 100000)); sharedFaces.add(f);
			}
			var projected = new LiquidControlVolumeProjection(List.of(mesh.cells.getFirst()), pressureFaces)
					.project(new double[3], new LiquidPolyhedron.Point(speed, 0, 0), 1, 1e-11, 128);
			var submitted = new cc.ecisr.mmm.units.SignedDyadic[mesh.faces.size()]; java.util.Arrays.fill(submitted, cc.ecisr.mmm.units.SignedDyadic.ZERO);
			double[] primary = new double[3 * regions.size()]; System.arraycopy(projected.cellVelocity(), 0, primary, 0, 3);
			for (int i = 0; i < sharedFaces.size(); i++) {
				int f = sharedFaces.get(i); submitted[f] = liquid[mesh.faces.get(f).first()] >= 0 ? projected.volumeRate()[i] : projected.volumeRate()[i].negate();
			}
			var extended = LiquidInterfaceVelocityExtension.extend(mesh, liquid, submitted, primary, 1e-11, 512);
			requireExactExtensionContinuity(mesh, extended);
			for (int f = 0; f < submitted.length; f++) {
				var face = mesh.faces.get(f); double expected = speed * face.surface().areaVector().x() / face.surface().area();
				close(extended.normalVelocity()[f], expected, 1e-10, "Interface extension changed rigid translation");
				if (liquid[face.first()] >= 0 || face.second() >= 0 && liquid[face.second()] >= 0)
					if (!extended.volumeRate()[f].equals(submitted[f])) throw new AssertionError("Interface extension changed a submitted pressure rate");
			}
			for (int c = 0; c < regions.size(); c++) for (int a = 0; a < 3; a++) close(extended.cellVelocity()[3 * c + a], a == 0 ? speed : 0,
					1e-10, "Interface extension changed cell translation");
			var velocity = LiquidControlVolumeVelocity.reconstruct(mesh, extended.normalVelocity(), extended.cellVelocity());
			var trajectory = new LiquidVelocityTrajectory(mesh, velocity); var start = new LiquidPolyhedron.Point(0.6 * scale, 0.45 * scale, 0.4 * scale);
			for (double duration : new double[] {0, 1e-6 * scale, 0.3 * scale}) {
				var moved = trajectory.advance(0, start, duration, 256);
				for (int a = 0; a < 3; a++) close(moved.position().coordinate(a) / scale, start.coordinate(a) / scale + (a == 0 ? speed * duration / scale : 0),
						1e-10, "Liquid characteristic failed across its free interface");
				if (speed > 0 && duration == 0.3 * scale && liquid[moved.cell()] >= 0) throw new AssertionError("Liquid characteristic did not enter gas velocity support");
			}
		}
		var sheet = cube.clip(0, 1, 0, 0.501).clip(0, -1, 0, -0.499);
		var sealed = new ArrayList<LiquidPhasePartition.Region>();
		var leaves = new ArrayList<LiquidGeometryRefinement.Cell>();
		for (var parent : LiquidGeometryRefinement.children(root)) leaves.addAll(LiquidGeometryRefinement.children(parent));
		for (var leaf : leaves) {
			var local = sheet;
			for (int a = 0; a < 3; a++) local = local.clip(a == 0 ? 1 : 0, a == 1 ? 1 : 0, a == 2 ? 1 : 0, leaf.minimum(a) + leaf.width())
					.clip(a == 0 ? -1 : 0, a == 1 ? -1 : 0, a == 2 ? -1 : 0, -leaf.minimum(a));
			sealed.addAll(LiquidPhasePartition.build(leaf, local.moments().volume() > 0 ? List.of(local) : List.of(), 16));
		}
		var mesh = new LiquidControlVolumeMesh(sealed.stream().map(LiquidPhasePartition.Region::cell).toList(), 1, 1000,
				(c, surface) -> new LiquidControlVolumeMesh.Boundary(true, 0));
		int[] labels = sealed.stream().mapToInt(LiquidPhasePartition.Region::liquid).toArray();
		double[] traces = new double[mesh.faces.size()], primary = new double[3 * sealed.size()];
		for (int c = 0; c < sealed.size(); c++) if (labels[c] >= 0) primary[3 * c + 1] = mesh.cells.get(c).centroid().x() < 0.5 ? 0.05 : 0.15;
		for (int f = 0; f < traces.length; f++) {
			var face = mesh.faces.get(f);
			if (face.second() >= 0 && (labels[face.first()] >= 0 || labels[face.second()] >= 0)) {
				int source = labels[face.first()] >= 0 ? face.first() : face.second();
				traces[f] = primary[3 * source + 1] * face.surface().areaVector().y() / face.surface().area();
			}
		}
		var submittedSheetRates = faceRates(mesh, traces);
		var extended = LiquidInterfaceVelocityExtension.extend(mesh, labels, submittedSheetRates, primary, 1e-11, 512);
		requireExactExtensionContinuity(mesh, extended);
		if (extended.iterations() == 0) throw new AssertionError("Nonuniform interface displacement did not redistribute gas extension flux");
		double lowerMoment = 0, upperMoment = 0, lowerDisplacement = 0, upperDisplacement = 0;
		for (int c = 0; c < sealed.size(); c++) if (labels[c] < 0) {
			var cell = mesh.cells.get(c); double y = cell.centroid().y(), volume = cell.geometry().moments().volume();
			double expected = y < 0.25 ? 0.4 : y > 0.75 ? -0.4 : 0;
			close(extended.gasDilatation()[c], expected, 1e-10, "Closed gas velocity support lost its submitted volume displacement");
			if (y < 0.5) { lowerMoment += volume * extended.cellVelocity()[3 * c + 1]; lowerDisplacement += volume * extended.gasDilatation()[c]; }
			else { upperMoment += volume * extended.cellVelocity()[3 * c + 1]; upperDisplacement += volume * extended.gasDilatation()[c]; }
			for (int f : mesh.cellFaces[c]) {
				var face = mesh.faces.get(f); int neighbor = face.first() == c ? face.second() : face.first();
				if (neighbor >= 0 && labels[neighbor] >= 0) {
					if (extended.gasDilatation()[c] != 0) throw new AssertionError("Gas compression entered conservative liquid velocity support");
					if (!extended.volumeRate()[f].equals(submittedSheetRates[f])) throw new AssertionError("Gas extension changed a submitted liquid trace");
				}
			}
		}
		close(lowerMoment, 0.0374, 1e-10, "Lower gas first moment does not match interface displacement");
		close(upperMoment, 0.0374, 1e-10, "Upper gas first moment does not match interface displacement");
		close(lowerDisplacement, 0.1, 1e-10, "Lower gas displacement does not close");
		close(upperDisplacement, -0.1, 1e-10, "Upper gas displacement does not close");
		var velocity = LiquidControlVolumeVelocity.reconstruct(mesh, extended.normalVelocity(), extended.cellVelocity());
		for (int c = 0; c < velocity.length; c++) for (var tetra : velocity[c].tetrahedra())
			close(3 * tetra.isotropicGradient(), extended.gasDilatation()[c], 1e-9, "Extended tetrahedral velocity lost its prescribed gas dilatation");
		var trajectory = new LiquidVelocityTrajectory(mesh, velocity);
		var start = new LiquidPolyhedron.Point(0.375, 0.5005, 0.125);
		int source = -1;
		for (int c = 0; c < sealed.size(); c++) if (labels[c] >= 0 && mesh.cells.get(c).centroid().x() == 0.375
				&& mesh.cells.get(c).centroid().y() > 0.5 && mesh.cells.get(c).centroid().z() == 0.125) source = c;
		if (source < 0) throw new AssertionError("Missing resolved liquid support");
		var moved = trajectory.advance(source, start, 0.05, 256);
		if (labels[moved.cell()] >= 0 || extended.gasDilatation()[moved.cell()] != 0)
			throw new AssertionError("Moving liquid interface entered gas compression support");
		var coarse = LiquidPhasePartition.build(root, List.of(sheet), 16);
		var unresolved = new LiquidControlVolumeMesh(coarse.stream().map(LiquidPhasePartition.Region::cell).toList(), 1, 1000,
				(c, surface) -> new LiquidControlVolumeMesh.Boundary(true, 0));
		double[] coarseTraces = new double[unresolved.faces.size()], coarseVelocity = new double[3 * coarse.size()]; coarseVelocity[1] = 0.1;
		for (int f = 0; f < coarseTraces.length; f++) if (unresolved.faces.get(f).second() >= 0)
			coarseTraces[f] = 0.1 * unresolved.faces.get(f).surface().areaVector().y() / unresolved.faces.get(f).surface().area();
		try {
			LiquidInterfaceVelocityExtension.extend(unresolved, coarse.stream().mapToInt(LiquidPhasePartition.Region::liquid).toArray(), faceRates(unresolved, coarseTraces), coarseVelocity, 1e-11, 512);
			throw new AssertionError("Unresolved gas compression was allowed into liquid transport support");
		} catch (IllegalStateException expected) { }
	}

	private static void finiteGasPressureSharesLiquidProjection() {
		var geometry = LiquidPolyhedron.box(0, 0, 0, 1, 1, 1);
		var faces = new ArrayList<LiquidControlVolumeProjection.Face>(); var nodes = new ArrayList<Integer>();
		for (var surface : geometry.surfaces()) {
			int node = surface.areaVector().x() < 0 ? 0 : surface.areaVector().x() > 0 ? 1 : -1;
			faces.add(new LiquidControlVolumeProjection.Face(0, node < 0 ? -2 : -1, surface, node == 0 ? 120_000 : node == 1 ? 100_000 : 0));
			nodes.add(node);
		}
		double dt = 0.01, compliance = 1e-6;
		var gas = List.of(new LiquidControlVolumeProjection.GasNode(120_000, compliance), new LiquidControlVolumeProjection.GasNode(100_000, compliance));
		var projection = new LiquidControlVolumeProjection(List.of(new LiquidControlVolumeProjection.Cell(geometry, 1000)), faces,
				gas, nodes.stream().mapToInt(Integer::intValue).toArray());
		var result = projection.project(new double[3], new LiquidPolyhedron.Point(0, 0, 0), dt, 1e-11, 256);
		requireExactContinuity(1, faces, result);
		double velocity = dt * 20_000 / (1000 + dt * dt * 2 / compliance);
		close(result.pressure()[0], 110_000, 1e-7);
		close(result.gasPressure()[0], 120_000 - dt * velocity / compliance, 1e-7);
		close(result.gasPressure()[1], 100_000 + dt * velocity / compliance, 1e-7);
		close(result.gasVolumeChange()[0], dt * velocity, 1e-12); close(result.gasVolumeChange()[1], -dt * velocity, 1e-12);
		for (int f = 0; f < faces.size(); f++) close(result.normalVelocity()[f], velocity * faces.get(f).surface().areaVector().x(), 1e-10);
		close(-dt * result.pressureGradient()[0].x() / 1000, velocity, 1e-10);
	}

	private static void refinedViscosityPreservesRigidMotionAndMomentum() {
		var root = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 1, 1, 1));
		var leaves = new ArrayList<>(LiquidGeometryRefinement.children(root));
		var refined = leaves.removeFirst(); leaves.addAll(LiquidGeometryRefinement.children(refined));
		var mesh = new LiquidControlVolumeMesh(leaves, 1, 1000, (cell, surface) -> new LiquidControlVolumeMesh.Boundary(false, 0));
		double[] viscosity = new double[mesh.cells.size()]; java.util.Arrays.fill(viscosity, 20);
		var operator = new LiquidControlVolumeViscosity(mesh.cells, mesh.faces, viscosity, FluidSolverConfig.WallCondition.FREE_SLIP);
		for (int mode = 0; mode < 3; mode++) {
			double[] incoming = new double[3 * mesh.cells.size()], faces = new double[3 * mesh.faces.size()];
			for (int c = 0; c < mesh.cells.size(); c++) velocity(incoming, c, mesh.cells.get(c).centroid(), mode);
			for (int f = 0; f < mesh.faces.size(); f++) velocity(faces, f, mesh.faces.get(f).surface().centroid(), mode);
			var result = operator.advance(incoming, faces, 0.05, 1e-9, 1024);
			double before = 0, after = 0; double[] momentum = new double[3];
			for (int c = 0; c < mesh.cells.size(); c++) {
				double mass = 1000 * mesh.cells.get(c).geometry().moments().volume();
				for (int a = 0; a < 3; a++) {
					int id = 3 * c + a; double next = result.cellVelocity()[id];
					before += 0.5 * mass * incoming[id] * incoming[id]; after += 0.5 * mass * next * next;
					momentum[a] += mass * (next - incoming[id]);
					if (mode < 2) close(next, incoming[id], 1e-11);
				}
			}
			for (double change : momentum) close(change, 0, 1e-8);
			close(before - after, result.dissipatedJoules() + result.temporalDissipationJoules(), 1e-8);
			if (mode < 2) close(result.dissipatedJoules(), 0, 1e-20);
			else if (!(result.dissipatedJoules() > 0 && after < before)) throw new AssertionError("Liquid viscous shear failed to dissipate");
		}
	}

	private static void velocity(double[] target, int index, LiquidPolyhedron.Point p, int mode) {
		target[3 * index] = mode == 0 ? 3 : mode == 1 ? 3 - 2 * p.y() : p.y();
		target[3 * index + 1] = mode == 1 ? -2 + 2 * p.x() : mode == 0 ? -2 : 0;
		target[3 * index + 2] = mode < 2 ? 1 : 0;
	}

	private static void sharedVelocityReconstructionPreservesTranslation() {
		var root = new LiquidGeometryRefinement.Cell(0, 0, 0, 0,
				LiquidPolyhedron.box(0, 0, 0, 1, 1, 1).clip(1, 0.7, 0.3, 1.21));
		var leaves = new ArrayList<>(LiquidGeometryRefinement.children(root));
		leaves.addAll(LiquidGeometryRefinement.children(leaves.removeFirst()));
		for (double scale : new double[] {1, 0.125, 1.0 / 1024}) {
			var mesh = new LiquidControlVolumeMesh(leaves, scale, 1000,
					(cell, surface) -> new LiquidControlVolumeMesh.Boundary(false, 0));
			var translation = new LiquidPolyhedron.Point(3, -2, 1); double[] traces = new double[mesh.faces.size()];
			for (int f = 0; f < traces.length; f++) traces[f] = translation.dot(mesh.faces.get(f).surface().areaVector()) / mesh.faces.get(f).surface().area();
			for (int c = 0; c < mesh.cells.size(); c++) {
				var field = LiquidControlVolumeVelocity.reconstruct(mesh, c, traces, translation);
				for (var tetra : field.tetrahedra()) for (int a = 0; a < 3; a++) close(tetra.velocity().coordinate(a), translation.coordinate(a), 1e-10);
				close(field.squaredVelocityVariation(), 0, 1e-20);
			}
		}
	}

	private static void vanishingContactAreaRemovesInteractionContinuously() {
		double previous = Double.POSITIVE_INFINITY;
		for (double overlap : new double[] {0.125, 0.03125, 0.0078125, 0.001953125, 0}) {
			var leaves = List.of(new LiquidGeometryRefinement.Cell(1, 0, 0, 0,
					LiquidPolyhedron.box(0.25, 0, 0.125, 0.5, 0.25, 0.375)),
					new LiquidGeometryRefinement.Cell(1, 1, 0, 0,
							LiquidPolyhedron.box(0.5, 0.25 - overlap, 0.125, 0.75, 0.5 - overlap, 0.375)));
			var mesh = new LiquidControlVolumeMesh(leaves, 1, 1000,
					(cell, surface) -> new LiquidControlVolumeMesh.Boundary(false, 0));
			double area = 0; for (var face : mesh.faces) if (face.second() >= 0) area += face.surface().area();
			close(area, 0.25 * overlap, 1e-14);
			double[] incoming = {1, 0, 0, -1, 0, 0};
			var projected = new LiquidControlVolumeProjection(mesh.cells, mesh.faces).project(incoming,
					new LiquidPolyhedron.Point(0, 0, 0), 0.025, 1e-11, 1024);
			double change = Math.abs(projected.cellVelocity()[0] - incoming[0]);
			if (!(change < previous)) throw new AssertionError("Liquid contact impulse failed to vanish with its shared area");
			previous = change;
			if (overlap == 0) close(change, 0, 1e-12);
			else if (!(change > 0)) throw new AssertionError("Resolved liquid contact transmitted no pressure impulse");
			close(projected.cellVelocity()[0] + projected.cellVelocity()[3], 0, 1e-10);
		}
	}

	private static void movingSolidPressureClosesWorkAndImpulse() {
		var geometry = LiquidPolyhedron.box(0, 0, 0, 1, 1, 1); var faces = new ArrayList<LiquidControlVolumeProjection.Face>();
		for (var surface : geometry.surfaces()) faces.add(new LiquidControlVolumeProjection.Face(0,
				surface.areaVector().x() < 0 ? -1 : -2, surface, surface.areaVector().x() < 0 ? 100_000 : surface.areaVector().x() > 0 ? 1 : 0));
		var result = new LiquidControlVolumeProjection(List.of(new LiquidControlVolumeProjection.Cell(geometry, 1000)), faces)
				.project(new double[3], new LiquidPolyhedron.Point(0, 0, 0), 0.025, 1e-11, 128);
		close(result.cellVelocity()[0], 1, 1e-12); close(result.cellVelocity()[1], 0, 1e-12); close(result.cellVelocity()[2], 0, 1e-12);
		close(result.pressure()[0], 80_000, 1e-7); close(result.boundaryImpulse().x(), 1000, 1e-7);
		close(result.boundaryImpulse().y(), 0, 1e-10); close(result.boundaryImpulse().z(), 0, 1e-10);
		close(result.boundaryWorkJoules(), 1000, 1e-7); close(result.pressureDissipationJoules(), 500, 1e-7);
	}

	private static void sharedVelocityTrajectoriesCrossRefinementContinuously() {
		var root = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 1, 1, 1));
		var leaves = new ArrayList<>(LiquidGeometryRefinement.children(root));
		leaves.addAll(LiquidGeometryRefinement.children(leaves.removeFirst()));
		var velocity = new LiquidPolyhedron.Point(1, -0.1, 0.2);
		for (double scale : new double[] {1, 0.125, 1.0 / 1024}) {
			var mesh = new LiquidControlVolumeMesh(leaves, scale, 1000,
					(cell, surface) -> new LiquidControlVolumeMesh.Boundary(false, 0));
			double[] traces = new double[mesh.faces.size()], cellVelocity = new double[3 * mesh.cells.size()];
			for (int f = 0; f < traces.length; f++) traces[f] = velocity.dot(mesh.faces.get(f).surface().areaVector()) / mesh.faces.get(f).surface().area();
			for (int c = 0; c < mesh.cells.size(); c++) for (int a = 0; a < 3; a++) cellVelocity[3 * c + a] = velocity.coordinate(a);
			var trajectory = new LiquidVelocityTrajectory(mesh, LiquidControlVolumeVelocity.reconstruct(mesh, traces, cellVelocity));
			int source = 0; for (int c = 0; c < leaves.size(); c++) if (leaves.get(c).level() == 2 && leaves.get(c).x() == 1
					&& leaves.get(c).y() == 1 && leaves.get(c).z() == 1) source = c;
			var initial = new LiquidPolyhedron.Point(0.35 * scale, 0.3 * scale, 0.4 * scale);
			for (double duration : new double[] {0, 1e-6, 0.01, 0.3}) {
				double dt = duration * scale; var result = trajectory.advance(source, initial, dt, 512);
				for (int a = 0; a < 3; a++) close(result.position().coordinate(a), initial.coordinate(a) + dt * velocity.coordinate(a), 1e-11 * scale);
				if (duration == 0.3 && !(result.faceEvents() > 0 && result.cell() != source)) throw new AssertionError("Liquid trajectory failed to cross its refined support");
			}
		}
	}

	private static void pressureAndViscosityShareMomentumAndEnergy() {
		var root = new LiquidGeometryRefinement.Cell(0, 0, 0, 0,
				LiquidPolyhedron.box(0, 0, 0, 1, 1, 1).clip(1, 0.7, 0.3, 1.21));
		var leaves = new ArrayList<>(LiquidGeometryRefinement.children(root));
		leaves.addAll(LiquidGeometryRefinement.children(leaves.removeFirst()));
		var mesh = new LiquidControlVolumeMesh(leaves, 1, 1000,
				(cell, surface) -> new LiquidControlVolumeMesh.Boundary(false, 0));
		double[] incoming = new double[3 * mesh.cells.size()], viscosity = new double[mesh.cells.size()];
		java.util.Arrays.fill(viscosity, 20);
		for (int c = 0; c < mesh.cells.size(); c++) for (int a = 0; a < 3; a++)
			incoming[3 * c + a] = Math.sin(0.7 + 3.1 * c + 1.3 * a);
		var viscous = new LiquidControlVolumeViscosity(mesh.cells, mesh.faces, viscosity,
				FluidSolverConfig.WallCondition.FREE_SLIP).advance(incoming, new double[3 * mesh.faces.size()], 0.025, 1e-9, 2048);
		var projected = new LiquidControlVolumeProjection(mesh.cells, mesh.faces).project(viscous.cellVelocity(),
				new LiquidPolyhedron.Point(0, 0, 0), 0.025, 1e-11, 2048);
		requireExactContinuity(mesh.cells.size(), mesh.faces, projected);
		double before = 0, after = 0; double[] momentum = new double[3];
		for (int c = 0; c < mesh.cells.size(); c++) for (int a = 0; a < 3; a++) {
			int id = 3 * c + a; double mass = mesh.cells.get(c).density() * mesh.cells.get(c).geometry().moments().volume();
			before += 0.5 * mass * incoming[id] * incoming[id];
			after += 0.5 * mass * projected.cellVelocity()[id] * projected.cellVelocity()[id];
			momentum[a] += mass * (projected.cellVelocity()[id] - incoming[id]);
		}
		for (double change : momentum) close(change, 0, 1e-8);
		close(before - after + projected.boundaryWorkJoules() + viscous.boundaryWorkJoules(),
				viscous.dissipatedJoules() + viscous.temporalDissipationJoules() + projected.pressureDissipationJoules(), 1e-8);
		if (!(projected.pressureDissipationJoules() > 0)) throw new AssertionError("Unresolved pressure modes have no dissipative energy");
		for (int c = 0; c < mesh.cells.size(); c++) {
			var target = new LiquidPolyhedron.Point(projected.cellVelocity()[3 * c], projected.cellVelocity()[3 * c + 1], projected.cellVelocity()[3 * c + 2]);
			var field = LiquidControlVolumeVelocity.reconstruct(mesh, c, projected.normalVelocity(), target);
			double volume = 0; double[] mean = new double[3];
			for (var tetra : field.tetrahedra()) {
				volume += tetra.volume();
				for (int a = 0; a < 3; a++) mean[a] += tetra.volume() * tetra.velocity().coordinate(a);
				var triangle = LiquidPolyhedron.surface(tetra.vertices().subList(1, 4));
				var face = mesh.faces.get(tetra.boundaryFace());
				close(tetra.sample(triangle.centroid()).dot(face.surface().areaVector()) / face.surface().area(),
						projected.normalVelocity()[tetra.boundaryFace()], 1e-9);
			}
			close(volume, mesh.cells.get(c).geometry().moments().volume(), 1e-12);
			for (int a = 0; a < 3; a++) close(mean[a] / volume, target.coordinate(a), 1e-9);
			close(field.divergenceResidual(), 0, 1e-9);
		}
	}

	private static void refinedHydrostaticColumn() {
		var root = new LiquidGeometryRefinement.Cell(0, 0, 0, 0, LiquidPolyhedron.box(0, 0, 0, 1, 1, 1));
		var leaves = new ArrayList<>(LiquidGeometryRefinement.children(root));
		var refined = leaves.removeFirst();
		var children = new ArrayList<>(LiquidGeometryRefinement.children(refined));
		var smallest = children.removeFirst(); leaves.addAll(children); leaves.addAll(LiquidGeometryRefinement.children(smallest));
		var mesh = new LiquidControlVolumeMesh(leaves, 1, 1000, (cell, surface) ->
				new LiquidControlVolumeMesh.Boundary(!(surface.areaVector().y() > 0 && surface.centroid().y() == 1), 0));
		var result = new LiquidControlVolumeProjection(mesh.cells, mesh.faces).project(new double[3 * mesh.cells.size()], GRAVITY, 0.025, 1e-11, 512);
		for (int c = 0; c < mesh.cells.size(); c++) close(result.pressure()[c], 1000 * 9.81 * (1 - mesh.cells.get(c).centroid().y()), 1e-7);
		for (double velocity : result.normalVelocity()) close(velocity, 0, 1e-10);
	}

	private static void refinedAffinePressure() {
		var root = new LiquidGeometryRefinement.Cell(0, 0, 0, 0,
				LiquidPolyhedron.box(0, 0, 0, 1, 1, 1).clip(1, 0.7, 0.3, 1.21));
		var leaves = new ArrayList<>(LiquidGeometryRefinement.children(root));
		var refined = leaves.removeFirst(); leaves.addAll(LiquidGeometryRefinement.children(refined));
		var mesh = new LiquidControlVolumeMesh(leaves, 1, 1000, (cell, surface) ->
				new LiquidControlVolumeMesh.Boundary(false, 1000 * 9.81 * (1 - surface.centroid().y())));
		var result = new LiquidControlVolumeProjection(mesh.cells, mesh.faces).project(new double[3 * mesh.cells.size()], GRAVITY, 0.025, 1e-11, 1024);
		for (int c = 0; c < mesh.cells.size(); c++) close(result.pressure()[c], 1000 * 9.81 * (1 - mesh.cells.get(c).centroid().y()), 1e-7);
		for (double velocity : result.normalVelocity()) close(velocity, 0, 1e-10);
	}

	private static void freeLiquidReceivesGravity(double size) {
		var geometry = LiquidPolyhedron.box(0.125, 0.25, 0.375, 0.125 + size, 0.25 + size, 0.375 + size);
		var faces = new ArrayList<LiquidControlVolumeProjection.Face>();
		for (var face : geometry.surfaces()) faces.add(new LiquidControlVolumeProjection.Face(0, -1, face, 100_000));
		var projection = new LiquidControlVolumeProjection(List.of(new LiquidControlVolumeProjection.Cell(geometry, 1000)), faces);
		double dt = 0.025;
		var result = projection.project(new double[3], GRAVITY, dt, 1e-12, 64);
		close(result.pressure()[0], 100_000, 1e-9);
		for (int f = 0; f < faces.size(); f++) {
			var surface = faces.get(f).surface();
			close(result.normalVelocity()[f], dt * surface.areaVector().dot(GRAVITY) / surface.area(), 1e-10);
		}
	}

	private static void thinHydrostaticColumn(double height) {
		var geometry = LiquidPolyhedron.box(0, 0, 0, 1, height, 1);
		var faces = new ArrayList<LiquidControlVolumeProjection.Face>();
		for (var surface : geometry.surfaces()) faces.add(new LiquidControlVolumeProjection.Face(0,
				surface.areaVector().y() > 0 ? -1 : -2, surface, 0));
		var projection = new LiquidControlVolumeProjection(List.of(new LiquidControlVolumeProjection.Cell(geometry, 1000)), faces);
		var result = projection.project(new double[3], GRAVITY, 0.025, 1e-12, 64);
		close(result.pressure()[0], 1000 * 9.81 * height / 2, 1e-10);
		for (double velocity : result.normalVelocity()) close(velocity, 0, 1e-12);
	}

	private static void smallHydrostaticCube(double size) {
		var geometry = LiquidPolyhedron.box(0, 0, 0, size, size, size);
		var faces = new ArrayList<LiquidControlVolumeProjection.Face>();
		for (var surface : geometry.surfaces()) faces.add(new LiquidControlVolumeProjection.Face(0,
				surface.areaVector().y() > 0 ? -1 : -2, surface, 0));
		var result = new LiquidControlVolumeProjection(List.of(new LiquidControlVolumeProjection.Cell(geometry, 1000)), faces)
				.project(new double[3], GRAVITY, 0.025, 1e-10, 128);
		close(result.pressure()[0] / size, 1000 * 9.81 / 2, 1e-6);
		for (double velocity : result.cellVelocity()) close(velocity, 0, 0);
		for (double velocity : result.normalVelocity()) close(velocity, 0, 0);
	}

	private static void densityJumpUsesBothLiquidDistances() {
		var lower = LiquidPolyhedron.box(0, 0, 0, 1, 0.25, 1);
		var upper = LiquidPolyhedron.box(0, 0.25, 0, 1, 0.375, 1);
		var faces = new ArrayList<LiquidControlVolumeProjection.Face>();
		for (var surface : lower.surfaces()) faces.add(new LiquidControlVolumeProjection.Face(0,
				surface.areaVector().y() > 0 ? 1 : -2, surface, 0));
		for (var surface : upper.surfaces()) if (!(surface.areaVector().y() < 0))
			faces.add(new LiquidControlVolumeProjection.Face(1, surface.areaVector().y() > 0 ? -1 : -2, surface, 0));
		var cells = List.of(new LiquidControlVolumeProjection.Cell(lower, 2000), new LiquidControlVolumeProjection.Cell(upper, 1000));
		var result = new LiquidControlVolumeProjection(cells, faces).project(new double[3 * cells.size()], GRAVITY, 0.025, 1e-12, 64);
		close(result.pressure()[0], 9.81 * (2000 * 0.125 + 1000 * 0.125), 1e-10);
		close(result.pressure()[1], 9.81 * 1000 * 0.0625, 1e-10);
		for (double velocity : result.normalVelocity()) close(velocity, 0, 1e-12);
	}

	private static void close(double actual, double expected, double tolerance) {
		if (!Double.isFinite(actual) || Math.abs(actual - expected) > tolerance)
			throw new AssertionError("Liquid control-volume value: actual=" + actual + " expected=" + expected);
	}
	private static void requireExactContinuity(int count, List<LiquidControlVolumeProjection.Face> faces, LiquidControlVolumeProjection.Result result) {
		var rates = result.volumeRate(); var divergence = new cc.ecisr.mmm.units.SignedDyadic[count];
		java.util.Arrays.fill(divergence, cc.ecisr.mmm.units.SignedDyadic.ZERO);
		if (rates.length != faces.size()) throw new AssertionError("Conservative pressure returned incomplete face rates");
		for (int f = 0; f < rates.length; f++) {
			var face = faces.get(f); divergence[face.first()] = divergence[face.first()].add(rates[f]);
			if (face.second() >= 0) divergence[face.second()] = divergence[face.second()].subtract(rates[f]);
		}
		for (var rate : divergence) if (rate.signum() != 0) throw new AssertionError("Pressure face rates violate exact control-volume continuity");
	}
	private static void close(double actual, double expected, double tolerance, String message) {
		if (!(Math.abs(actual - expected) <= tolerance)) throw new AssertionError(message + ": actual=" + actual + " expected=" + expected);
	}
	public static void main(String[] args) { validate(); }
}
