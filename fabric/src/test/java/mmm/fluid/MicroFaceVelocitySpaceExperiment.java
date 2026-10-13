package mmm.fluid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import static mmm.fluid.MarkerVelocitySpaceExperiment.near;
import static mmm.fluid.MarkerVelocitySpaceExperiment.rank;
import static mmm.fluid.MarkerVelocitySpaceExperiment.solve;

/** Test-only shared microface space: actual marker inertia and separate wall/gas pieces. */
final class MicroFaceVelocitySpaceExperiment {
	private record FaceKey(int axis, int plusPiece) { }
	private record Face(int axis, int minus, int plus, int gasNode) { }
	private record Piece(int key, int[] coordinate) { }
	private record Wall(int piece, int axis, int sign) { }
	private record Balance(double[] force, double[] torque) { }
	record Projection(double[] coefficients, double[] liquidPressure, double[] gasPressure) { }
	private MicroFaceVelocitySpaceExperiment() { }

	static void validateSupport() {
		var failures = new AssertionError("Microface-only velocity lacks the retained spatial support");
		checkSupport("single marker", new double[][]{{.5, .5, .5}}, failures);
		checkSupport("collinear markers", new double[][]{{.25, .5, .5}, {.75, .5, .5}}, failures);
		checkSupport("coplanar markers", new double[][]{{.25, .25, .5}, {.75, .25, .5}, {.25, .75, .5}, {.75, .75, .5}}, failures);
		checkSupport("near-coplanar markers", new double[][]{{.25, .25, .5}, {.75, .25, .5}, {.25, .75, .5}, {.75, .75, Math.nextUp(.5F)}}, failures);
		var fixture = MarkerVelocitySpaceExperiment.componentFixture();
		var model = new Model(fixture.domain());
		double energy = 0;
		for (int i = 0; i < fixture.domain().particleCount(); i++) for (int axis = 0; axis < 3; axis++)
			energy += .5 * fixture.domain().unitScale.kilograms(fixture.domain().particles.mass(i)) * Math.pow(fixture.velocity()[axis][i], 2);
		System.out.printf("Isolated microbox circulation: markers=%d, face DOFs=%d, inertia rank=%d, incoming kinetic energy=%g J%n",
				fixture.domain().particleCount(), model.faces.size(), rank(model.sample), energy);
		if (model.faces.isEmpty() && energy > 0)
			failures.addSuppressed(new AssertionError("Eight markers in each disconnected microbox resolve a wall-tangent circulation that has no shared-face mean"));
		if (failures.getSuppressed().length != 0) throw failures;
	}
	private static void checkSupport(String name, double[][] points, AssertionError failures) {
		var d = domain(2, 2, 2);
		var positions = new float[4 * points.length]; var masks = new byte[8]; Arrays.fill(masks, (byte) 255); masks[0] = 0;
		for (int i = 0; i < points.length; i++) {
			for (int axis = 0; axis < 3; axis++) positions[4 * i + axis] = (float) points[i][axis];
			positions[4 * i + 3] = 1F / points.length;
		}
		d.restoreParticleState(new FlipParticleState(2, 2, 2, 1, points.length, 1, positions, new float[3 * points.length], masks));
		d.ensureInventoryGrid(); var model = new Model(d);
		var joint = new double[model.sample.length + model.divergence.length][];
		System.arraycopy(model.sample, 0, joint, 0, model.sample.length);
		System.arraycopy(model.divergence, 0, joint, model.sample.length, model.divergence.length);
		int support = rank(model.sample), inertia = rank(model.mass), pressure = rank(model.divergence), jointRank = rank(joint);
		System.out.printf("Microface support %s: DOFs=%d, sample/mass ranks=%d/%d, pressure rank=%d, joint rank=%d%n",
				name, model.faces.size(), support, inertia, pressure, jointRank);
		if (inertia != support) failures.addSuppressed(new AssertionError(name + ": rounded mass Gram rank differs from actual velocity support"));
		if (jointRank > support) failures.addSuppressed(new AssertionError(name + ": a zero-inertia velocity changes liquid volume; pressure cannot use an arbitrary mass pseudoinverse"));
	}

	static void validate() {
		int cycles = 0;
		for (int mask = 0; mask < 256; mask++) for (boolean heterogeneous : new boolean[]{false, true}) for (boolean shifted : new boolean[]{false, true}) {
			var d = closedMask(mask);
			if (heterogeneous) for (int i = 0; i < d.particleCount(); i++) d.particles.setMass(i, d.particles.mass(i) * (1 + i / 8.0));
			if (shifted) {
				for (int i = 0; i < d.particleCount(); i++) {
					d.particles.setPositionX(i, d.particles.positionX(i) + (i % 3 - 1) / 32F);
					d.particles.setPositionY(i, d.particles.positionY(i) + ((i + 1) % 3 - 1) / 32F);
					d.particles.setPositionZ(i, d.particles.positionZ(i) + ((i + 2) % 3 - 1) / 32F);
				}
				d.gridDirty = true; d.ensureInventoryGrid();
			}
			var model = new Model(d);
			model.verifyRigidModes(); model.verifyWallSupport();
			int independent = model.pieces.size() - model.componentCount;
			if (model.faces.isEmpty()) {
				if (independent != 0) throw new AssertionError("Closed micro topology omitted an internal face");
				model.verifyFaces(new double[0]);
				continue;
			}
			if (rank(model.mass) != model.faces.size() || rank(model.sample) != model.faces.size())
				throw new AssertionError("Microface velocity has unsupported inertia at mask " + mask);
			if (rank(model.divergence) != independent) throw new AssertionError("Micro pressure rank differs from actual component topology at mask " + mask);
			cycles += heterogeneous || shifted ? 0 : model.faces.size() - independent;
			var predicted = new double[model.faces.size()];
			for (int face = 0; face < predicted.length; face++) predicted[face] = Math.sin(.37 * face + mask);
			var projected = model.project(predicted, .001);
			for (double residual : multiply(model.divergence, projected.coefficients())) near(residual, 0, 1e-11, "Micro projection continuity");
			var removed = subtract(predicted, projected.coefficients());
			near(energy(model.mass, predicted) - energy(model.mass, projected.coefficients()), energy(model.mass, removed), 1e-10,
					"Micro actual-mass projection energy identity");
			model.verifyFaces(projected.coefficients());
			model.verifyVirtualWork();
			var pressure = new double[model.pieces.size()]; for (int i = 0; i < pressure.length; i++) pressure[i] = Math.cos(.39 * i);
			assertBalance(model.balance(pressure, new double[1]));
		}
		closedCirculations();
		partialWallGasPressures();
		validateOffCenter();
		System.out.printf("PASS microface candidate: 256 masks x two mass layouts x two position layouts, component pressure ranks, %d closed graph circulation modes, continuity, rigid modes, wall traces and virtual work%n", cycles);
	}

	static void validateOffCenter() {
		var d = closedMask(0);
		d.particles.setPositionX(0, d.particles.positionX(0) + 1F / 16);
		d.gridDirty = true; d.ensureInventoryGrid();
		var model = new Model(d); var pressure = new double[model.pieces.size()]; pressure[0] = 1;
		var balance = model.balance(pressure, new double[1]);
		System.out.printf("Off-centre microface pressure: force residual=%s N, torque residual=%s N m%n",
				Arrays.toString(balance.force()), Arrays.toString(balance.torque()));
		assertBalance(balance);
	}
	private static void assertBalance(Balance balance) {
		for (double value : balance.force()) near(value, 0, 1e-10, "Actual pressure impulse closes with gas traction and fixed-wall reaction");
		for (double value : balance.torque()) near(value, 0, 1e-10, "Actual pressure torque closes with gas traction and fixed-wall reaction");
	}

	static FluidDomain closedMask(int mask) {
		var d = domain(2, 2, 2);
		for (int cell = 0; cell < d.cellCount; cell++) d.setSolid(cell % 2, cell / 2 % 2, cell / 4, true);
		d.setSolidMicroMask(0, 0, 0, mask);
		if (mask != 255) d.setVolume(0, 0, 0, (8 - Integer.bitCount(mask)) / 8F);
		d.ensureGrid(); return d;
	}
	static FluidDomain domain(int x, int y, int z) {
		return new FluidDomain(x, y, z, new FluidSolverConfig(1, 1000, 0, .001F, 400, 1e-5F, .45F, .002F, 0));
	}

	private static void closedCirculations() {
		var d = closedMask(0); var model = new Model(d);
		for (int axis = 0; axis < 3; axis++) {
			var markerVelocity = new double[3 * d.particleCount()];
			int a = (axis + 1) % 3, b = (axis + 2) % 3;
			for (int i = 0; i < d.particleCount(); i++) {
				double[] p = {d.particles.positionX(i), d.particles.positionY(i), d.particles.positionZ(i)};
				markerVelocity[a * d.particleCount() + i] = p[a] * (1 - p[a]) * (1 - 2 * p[b]);
				markerVelocity[b * d.particleCount() + i] = -p[b] * (1 - p[b]) * (1 - 2 * p[a]);
			}
			var input = model.fit(markerVelocity); var projected = model.project(input, .001);
			var sampled = multiply(model.sample, projected.coefficients());
			for (int i = 0; i < sampled.length; i++) near(sampled[i], markerVelocity[i], 1e-13, "Shared microfaces retain closed circulation");
			near(energy(model.mass, projected.coefficients()), 8.7890625, 1e-12, "Closed circulation retains actual kinetic energy");
		}
		System.out.println("PASS microface closed circulation: three axes retain 8.7890625 J rather than being cleared by whole-plane walls");
	}

	private static void partialWallGasPressures() {
		var d = domain(3, 3, 3);
		for (int cell = 0; cell < d.cellCount; cell++) d.setSolid(cell % 3, cell / 3 % 3, cell / 9, true);
		d.setSolid(0, 1, 1, false); d.setSolid(2, 1, 1, false); d.setSolidMicroMask(1, 1, 1, 126);
		d.setVolume(0, 1, 1, 1); d.setVolume(2, 1, 1, 1); d.sealDomainBoundary(); d.ensureGrid(); d.gasTopologyCoordinator.refresh();
		var frame = d.gasState.frame; int center = d.cellIndex(1, 1, 1);
		int first = frame.nodeOfPiece(8 * center), second = frame.nodeOfPiece(8 * center + 7);
		d.gasState.network.setStandardVolume(first, 1.5 * d.gasState.network.availableVolume(first));
		d.gasState.network.setStandardVolume(second, .75 * d.gasState.network.availableVolume(second));
		var model = new Model(d);
		if (model.faces.size() != 26 || model.gasRows.size() != 2 || rank(model.mass) != 26)
			throw new AssertionError("Partially blocked faces lost their independent gas-side velocity support");
		var result = model.project(new double[model.faces.size()], .001);
		for (int piece = 0; piece < model.pieces.size(); piece++) {
			int node = model.pieces.get(piece).key() / 8 == 12 ? first : second;
			near(result.liquidPressure()[piece], d.gasState.network.gaugePressure(node), 1e-5, "Micro liquid pressure equals its actual gas contact");
		}
		near(result.gasPressure()[first], 50662.5, 1e-5, "First gas pressure");
		near(result.gasPressure()[second], -25331.25, 1e-5, "Second gas pressure");
		for (double velocity : result.coefficients()) near(velocity, 0, 1e-12, "Independent stationary compartments have zero flow");
		model.verifyFaces(result.coefficients()); model.verifyVirtualWork();
		assertBalance(model.balance(result.liquidPressure(), result.gasPressure()));
		System.out.println("PASS partial-wall microface pressure: liquid/gas 50662.5 Pa and -25331.25 Pa, zero velocity, separate quarter-face ports");
	}

	static final class Model {
		final FluidDomain domain;
		final ArrayList<Piece> pieces = new ArrayList<>();
		final ArrayList<Face> faces = new ArrayList<>();
		final ArrayList<Wall> walls = new ArrayList<>();
		final Map<Integer, Integer> index = new LinkedHashMap<>(), gasRows = new LinkedHashMap<>();
		final int[][] low, high;
		final double[][] sample, mass, divergence;
		final double[][] fullSample;
		final double[][][] slopes;
		final int[][] fullLow, fullHigh;
		final int[] components;
		final int componentCount;
		final double h, area;

		Model(FluidDomain domain) {
			this.domain = domain; h = domain.config.cellSize() / 2.0; area = h * h;
			for (int cell = 0; cell < domain.cellCount; cell++) for (int micro = 0; micro < 8; micro++) {
				if ((domain.geometry.cutCells.mask(cell) & 1 << micro) != 0) continue;
				double fraction = domain.pressurePhaseGeometry.liquidFractionExact(cell, micro);
				if (fraction == 0) continue;
				if (fraction != 1) throw new IllegalArgumentException("Microface prototype requires fully wet microboxes");
				int key = 8 * cell + micro; index.put(key, pieces.size());
				pieces.add(new Piece(key, new int[]{2 * (cell % domain.sizeX) + (micro & 1),
						2 * (cell / domain.sizeX % domain.sizeY) + (micro >> 1 & 1),
						2 * (cell / (domain.sizeX * domain.sizeY)) + (micro >> 2 & 1)}));
			}
			low = new int[pieces.size()][3]; high = new int[pieces.size()][3];
			for (int[] row : low) Arrays.fill(row, -1); for (int[] row : high) Arrays.fill(row, -1);
			var canonical = new LinkedHashMap<FaceKey, Integer>();
			for (int piece = 0; piece < pieces.size(); piece++) for (int axis = 0; axis < 3; axis++) for (int sign : new int[]{-1, 1}) {
				int[] neighbor = pieces.get(piece).coordinate().clone(); neighbor[axis] += sign;
				int key = pieceKey(neighbor);
				if (key < 0 || (domain.geometry.cutCells.mask(key / 8) & 1 << (key % 8)) != 0) {
					walls.add(new Wall(piece, axis, sign)); continue;
				}
				int peer = index.getOrDefault(key, -1), node = -1;
				if (peer < 0) {
					if (domain.gasState.frame == null) throw new IllegalArgumentException("A free microface requires actual gas ownership");
					node = domain.gasState.frame.nodeOfPiece(key);
					if (node <= 0 || domain.gasState.network.inventory(node).isEmpty())
						throw new IllegalArgumentException("Microface prototype requires finite sealed gas contacts");
					gasRows.putIfAbsent(node, gasRows.size());
				}
				var identity = new FaceKey(axis, sign > 0 ? key : pieces.get(piece).key());
				int face = canonical.getOrDefault(identity, -1);
				if (face < 0) {
					face = faces.size(); canonical.put(identity, face);
					faces.add(new Face(axis, sign > 0 ? piece : peer, sign > 0 ? peer : piece, node));
				}
				(sign > 0 ? high : low)[piece][axis] = face;
			}
			components = new int[pieces.size()]; Arrays.fill(components, -1);
			int count = 0; int[] queue = new int[pieces.size()];
			for (int start = 0; start < pieces.size(); start++) if (components[start] < 0) {
				int head = 0, tail = 0; queue[tail++] = start; components[start] = count;
				while (head < tail) {
					int piece = queue[head++];
					for (Face face : faces) if (face.minus() == piece || face.plus() == piece) {
						int peer = face.minus() == piece ? face.plus() : face.minus();
						if (peer >= 0 && components[peer] < 0) { components[peer] = count; queue[tail++] = peer; }
					}
				}
				count++;
			}
			componentCount = count;
			fullLow = new int[pieces.size()][3]; fullHigh = new int[pieces.size()][3];
			for (int p = 0; p < pieces.size(); p++) { fullLow[p] = low[p].clone(); fullHigh[p] = high[p].clone(); }
			for (int w = 0; w < walls.size(); w++) {
				var wall = walls.get(w); (wall.sign() > 0 ? fullHigh : fullLow)[wall.piece()][wall.axis()] = faces.size() + w;
			}
			slopes = captureSlopes();
			fullSample = new double[3 * domain.particleCount()][faces.size() + walls.size() + 6 * componentCount];
			sample = new double[3 * domain.particleCount()][faces.size()];
			mass = new double[faces.size()][faces.size()]; divergence = new double[pieces.size()][faces.size()];
			for (int marker = 0; marker < domain.particleCount(); marker++) {
				double[] position = {domain.particles.positionX(marker), domain.particles.positionY(marker), domain.particles.positionZ(marker)};
				int[] micro = {(int) Math.floor(position[0] / h), (int) Math.floor(position[1] / h), (int) Math.floor(position[2] / h)};
				Integer piece = index.get(pieceKey(micro)); if (piece == null) throw new AssertionError("Actual marker lacks its wet microbox");
				var local = new double[3]; for (int a = 0; a < 3; a++) local[a] = position[a] / h - micro[a];
				for (int axis = 0; axis < 3; axis++) {
					var row = fieldRow(piece, axis, local); fullSample[axis * domain.particleCount() + marker] = row;
					System.arraycopy(row, 0, sample[axis * domain.particleCount() + marker], 0, faces.size());
				}
			}
			for (int row = 0; row < sample.length; row++) {
				double actualMass = domain.unitScale.kilograms(domain.particles.mass(row % domain.particleCount()));
				for (int a = 0; a < faces.size(); a++) for (int b = 0; b < faces.size(); b++) mass[a][b] += actualMass * sample[row][a] * sample[row][b];
			}
			for (int piece = 0; piece < pieces.size(); piece++) for (int axis = 0; axis < 3; axis++) {
				if (low[piece][axis] >= 0) divergence[piece][low[piece][axis]] -= area;
				if (high[piece][axis] >= 0) divergence[piece][high[piece][axis]] += area;
			}
		}
		double[] fieldRow(int piece, int axis, double[] local) {
			var row = new double[faces.size() + walls.size() + 6 * componentCount];
			row[fullLow[piece][axis]] += 1 - local[axis]; row[fullHigh[piece][axis]] += local[axis];
			for (int slot = 0; slot < 2; slot++) {
				int tangent = (axis + slot + 1) % 3;
				addScaled(row, slopes[fullLow[piece][axis]][slot], (1 - local[axis]) * (local[tangent] - .5));
				addScaled(row, slopes[fullHigh[piece][axis]][slot], local[axis] * (local[tangent] - .5));
				int tangentSlot = axis == (tangent + 1) % 3 ? 0 : 1;
				double bubble = .5 * local[axis] * (1 - local[axis]);
				addScaled(row, slopes[fullHigh[piece][tangent]][tangentSlot], bubble);
				addScaled(row, slopes[fullLow[piece][tangent]][tangentSlot], -bubble);
			}
			return row;
		}

		int fullAxis(int face) { return face < faces.size() ? faces.get(face).axis() : walls.get(face - faces.size()).axis(); }
		int fullPiece(int face) {
			if (face >= faces.size()) return walls.get(face - faces.size()).piece();
			var f = faces.get(face); return f.minus() >= 0 ? f.minus() : f.plus();
		}
		int fullSign(int face) {
			return face >= faces.size() ? walls.get(face - faces.size()).sign() : faces.get(face).minus() >= 0 ? 1 : -1;
		}
		int theta(int component, int axis, int slot) { return faces.size() + walls.size() + 6 * component + 2 * axis + slot; }
		/** Each fixed-face gradient is driven by fixed controls; missing directions remain explicit wall moments. */
		double[][][] captureSlopes() {
			int n = faces.size() + walls.size(), columns = n + 6 * componentCount;
			var result = new double[n][2][columns];
			for (int f = 0; f < n; f++) {
				int axis = fullAxis(f), component = components[fullPiece(f)];
				var coord = pieces.get(fullPiece(f)).coordinate(); int plane = coord[axis] + (fullSign(f) > 0 ? 1 : 0);
				var donors = new ArrayList<Integer>(); double tSum = 0, uSum = 0, tt = 0, tu = 0, uu = 0;
				for (int g = 0; g < n; g++) {
					var peer = pieces.get(fullPiece(g)).coordinate();
					if (fullAxis(g) != axis || components[fullPiece(g)] != component || peer[axis] + (fullSign(g) > 0 ? 1 : 0) != plane
							|| f >= faces.size() && g < faces.size()) continue;
					donors.add(g); double t = peer[(axis + 1) % 3], u = peer[(axis + 2) % 3];
					tSum += t; uSum += u; tt += t*t; tu += t*u; uu += u*u;
				}
				int count = donors.size(); double s00 = count*tt-tSum*tSum, s01 = count*tu-tSum*uSum, s11 = count*uu-uSum*uSum;
				var inverse = inverse2(s00, s01, s11);
				for (int g : donors) {
					var peer = pieces.get(fullPiece(g)).coordinate(); double t = peer[(axis + 1) % 3] - tSum / count, u = peer[(axis + 2) % 3] - uSum / count;
					result[f][0][g] = count * (inverse[0]*t+inverse[1]*u);
					result[f][1][g] = count * (inverse[1]*t+inverse[2]*u);
				}
				if (s00*s11-s01*s01 == 0) {
					result[f][0][theta(component, axis, 0)] = 1 - (inverse[0]*s00+inverse[1]*s01);
					result[f][0][theta(component, axis, 1)] = -(inverse[0]*s01+inverse[1]*s11);
					result[f][1][theta(component, axis, 0)] = -(inverse[1]*s00+inverse[2]*s01);
					result[f][1][theta(component, axis, 1)] = 1 - (inverse[1]*s01+inverse[2]*s11);
				}
			}
			return result;
		}

		int pieceKey(int[] coordinate) {
			if (coordinate[0] < 0 || coordinate[1] < 0 || coordinate[2] < 0 || coordinate[0] >= 2 * domain.sizeX
					|| coordinate[1] >= 2 * domain.sizeY || coordinate[2] >= 2 * domain.sizeZ) return -1;
			int cell = domain.cellIndex(coordinate[0] / 2, coordinate[1] / 2, coordinate[2] / 2);
			return 8 * cell + (coordinate[0] & 1) + 2 * (coordinate[1] & 1) + 4 * (coordinate[2] & 1);
		}

		double[] fit(double[] markerVelocity) {
			var right = new double[faces.size()];
			for (int row = 0; row < sample.length; row++) {
				double m = domain.unitScale.kilograms(domain.particles.mass(row % domain.particleCount()));
				for (int face = 0; face < faces.size(); face++) right[face] += m * sample[row][face] * markerVelocity[row];
			}
			return solve(mass, right);
		}

		Projection project(double[] predictor, double dt) {
			return project(predictor, dt, mass, divergence);
		}
		Projection project(double[] predictor, double dt, double[][] inertia, double[][] volumeDivergence) {
			boolean[] anchored = new boolean[componentCount];
			for (Face face : faces) if (face.gasNode() >= 0) anchored[components[face.minus() >= 0 ? face.minus() : face.plus()]] = true;
			var rows = new ArrayList<double[]>(); var owners = new ArrayList<Integer>();
			for (int piece = 0; piece < pieces.size(); piece++) {
				if (!anchored[components[piece]]) { anchored[components[piece]] = true; continue; }
				rows.add(volumeDivergence[piece].clone()); owners.add(piece);
			}
			int liquidRows = rows.size();
			for (int i = 0; i < gasRows.size(); i++) rows.add(new double[predictor.length]);
			for (int f = 0; f < faces.size(); f++) {
				var face = faces.get(f); if (face.gasNode() < 0) continue;
				rows.get(liquidRows + gasRows.get(face.gasNode()))[f] = face.minus() >= 0 ? -area : area;
			}
			var c = rows.toArray(double[][]::new); var response = new double[c.length][predictor.length];
			var schur = new double[c.length][c.length]; var right = new double[c.length];
			for (int row = 0; row < c.length; row++) response[row] = solve(inertia, c[row]);
			for (int row = 0; row < c.length; row++) {
				right[row] = -dot(c[row], predictor) / dt;
				for (int column = 0; column < c.length; column++) schur[row][column] = dot(c[row], response[column]);
			}
			for (var gas : gasRows.entrySet()) {
				int node = gas.getKey(), row = liquidRows + gas.getValue();
				double pressure = domain.gasState.network.gaugePressure(node);
				double storage = domain.gasState.network.availableVolume(node) / (101325 + pressure) / (dt * dt);
				schur[row][row] += storage; right[row] += storage * pressure;
			}
			var pressure = solve(schur, right); var velocity = predictor.clone();
			for (int column = 0; column < predictor.length; column++) for (int row = 0; row < c.length; row++) velocity[column] += dt * response[row][column] * pressure[row];
			var liquid = new double[pieces.size()]; var gas = new double[domain.gasState.network.nodeCount() + 1];
			for (int row = 0; row < liquidRows; row++) liquid[owners.get(row)] = pressure[row];
			for (var entry : gasRows.entrySet()) gas[entry.getKey()] = pressure[liquidRows + entry.getValue()];
			return new Projection(velocity, liquid, gas);
		}

		void verifyFaces(double[] velocity) {
			var full = new double[faces.size() + walls.size() + 6 * componentCount]; System.arraycopy(velocity, 0, full, 0, velocity.length);
			for (int piece = 0; piece < pieces.size(); piece++) for (int axis = 0; axis < 3; axis++) for (int sign : new int[]{-1, 1}) {
				int face = (sign > 0 ? fullHigh : fullLow)[piece][axis];
				for (double t : new double[]{0, .5, 1}) for (double u : new double[]{0, .5, 1}) {
					var p = new double[]{.5, .5, .5}; p[axis] = sign > 0 ? 1 : 0; p[(axis + 1) % 3] = t; p[(axis + 2) % 3] = u;
					double trace = full[face] + (t-.5)*dot(slopes[face][0], full) + (u-.5)*dot(slopes[face][1], full);
					near(dot(fieldRow(piece, axis, p), full), trace, 1e-12, "Actual field retains its canonical normal trace");
					if (face >= faces.size()) near(trace, 0, 0, "Fixed wall has pointwise zero normal trace");
				}
			}
			for (var face : faces) if (face.minus() >= 0 && face.plus() >= 0)
				near(value(face.minus(), face.axis(), 1, velocity), value(face.plus(), face.axis(), 0, velocity), 0, "Shared microface is canonical");
			var rate = multiply(divergence, velocity);
			for (int piece = 0; piece < pieces.size(); piece++) for (double x : new double[]{.125, .5, .875}) {
				var p = new double[]{x, .25, .75}; double derivative = 0;
				for (int axis = 0; axis < 3; axis++) {
					var plus = p.clone(); var minus = p.clone(); plus[axis] += 1.0/32; minus[axis] -= 1.0/32;
					derivative += (dot(fieldRow(piece, axis, plus), full)-dot(fieldRow(piece, axis, minus), full)) * 16 / h;
				}
				near(derivative, rate[piece] / (h*h*h), 1e-11, "Pointwise H(div) reconstruction matches physical micro volume rate");
			}
		}
		void verifyRigidModes() {
			for (int mode = 0; mode < 6; mode++) {
				var controls = new double[faces.size() + walls.size() + 6 * componentCount];
				for (int f = 0; f < faces.size() + walls.size(); f++)
					controls[f] = rigid(faceCenter(pieces.get(fullPiece(f)).coordinate(), fullAxis(f), fullSign(f)), fullAxis(f), mode);
				for (int component = 0; component < componentCount; component++) for (int axis = 0; axis < 3; axis++) for (int slot = 0; slot < 2; slot++) {
					var p = new double[3]; p[(axis+slot+1)%3] = h;
					controls[theta(component, axis, slot)] = rigid(p, axis, mode) - rigid(new double[3], axis, mode);
				}
				var predicted = multiply(fullSample, controls);
				for (int marker = 0; marker < domain.particleCount(); marker++) {
					var p = new double[]{domain.particles.positionX(marker), domain.particles.positionY(marker), domain.particles.positionZ(marker)};
					for (int axis = 0; axis < 3; axis++) near(predicted[axis * domain.particleCount() + marker], rigid(p, axis, mode), 1e-13, "Full virtual field reproduces rigid motion at actual marker positions");
				}
				for (int piece = 0; piece < pieces.size(); piece++) {
					double rate = 0; for (int axis = 0; axis < 3; axis++) rate += area * (controls[fullHigh[piece][axis]] - controls[fullLow[piece][axis]]);
					near(rate, 0, 0, "Rigid micro field has no volume source");
				}
			}
		}
		double[] wallSlopeGram(int component, int axis) {
			var result = new double[3];
			for (int w = 0; w < walls.size(); w++) if (components[walls.get(w).piece()] == component && walls.get(w).axis() == axis)
				for (int slot = 0; slot < 2; slot++) {
					var s = slopes[faces.size() + w]; double a = s[slot][theta(component, axis, 0)], b = s[slot][theta(component, axis, 1)];
					result[0] += a*a; result[1] += a*b; result[2] += b*b;
				}
			return result;
		}
		void verifyWallSupport() {
			for (int component = 0; component < componentCount; component++) for (int axis = 0; axis < 3; axis++) {
				var k = wallSlopeGram(component, axis); var inverse = inverse2(k[0], k[1], k[2]);
				double n00 = 1 - inverse[0]*k[0] - inverse[1]*k[1], n01 = -inverse[0]*k[1] - inverse[1]*k[2];
				double n10 = -inverse[1]*k[0] - inverse[2]*k[1], n11 = 1 - inverse[1]*k[1] - inverse[2]*k[2];
				for (var row : fullSample) {
					double a = row[theta(component, axis, 0)], b = row[theta(component, axis, 1)];
					near(a*n00+b*n10, 0, 1e-13, "An unrestrained tangent direction has no actual inertia");
					near(a*n01+b*n11, 0, 1e-13, "An unrestrained tangent direction has no actual inertia");
				}
			}
		}
		double value(int piece, int axis, double x, double[] velocity) {
			return (low[piece][axis] < 0 ? 0 : (1 - x) * velocity[low[piece][axis]])
					+ (high[piece][axis] < 0 ? 0 : x * velocity[high[piece][axis]]);
		}
		void verifyVirtualWork() {
			var v = new double[faces.size()]; var p = new double[pieces.size()];
			for (int f = 0; f < v.length; f++) v[f] = Math.sin(.27 * f); for (int i = 0; i < p.length; i++) p[i] = Math.cos(.39 * i);
			var load = new double[faces.size()];
			for (int row = 0; row < p.length; row++) for (int face = 0; face < faces.size(); face++) load[face] += divergence[row][face] * p[row];
			var response = solve(mass, load); var sampled = multiply(sample, v); var acceleration = multiply(sample, response);
			double work = 0;
			for (int row = 0; row < sample.length; row++) work += domain.unitScale.kilograms(domain.particles.mass(row % domain.particleCount())) * sampled[row] * acceleration[row];
			near(work, dot(p, multiply(divergence, v)), 1e-12, "Actual marker / micro pressure virtual work");
		}

		Balance balance(double[] pressure, double[] gasPressure) {
			var load = new double[faces.size()];
			for (int piece = 0; piece < pieces.size(); piece++) for (int f = 0; f < faces.size(); f++) load[f] += divergence[piece][f] * pressure[piece];
			for (int f = 0; f < faces.size(); f++) {
				var face = faces.get(f);
				if (face.gasNode() >= 0) load[f] -= (face.minus() >= 0 ? area : -area) * gasPressure[face.gasNode()];
			}
			return balanceActual(multiply(sample, solve(mass, load)), pressure, gasPressure);
		}
		void verifyPressureReaction(double[] acceleration, double[] pressure) {
			assertBalance(balanceActual(acceleration, pressure, new double[domain.gasState.network.nodeCount() + 1]));
		}
		private Balance balanceActual(double[] acceleration, double[] pressure, double[] gasPressure) {
			var actual = new double[3][domain.particleCount()];
			var force = new double[3]; var torque = new double[3];
			for (int marker = 0; marker < domain.particleCount(); marker++) {
				var p = new double[]{domain.particles.positionX(marker), domain.particles.positionY(marker), domain.particles.positionZ(marker)};
				var f = new double[3];
				for (int axis = 0; axis < 3; axis++) f[axis] = actual[axis][marker] = domain.unitScale.kilograms(domain.particles.mass(marker)) * acceleration[axis * domain.particleCount() + marker];
				add(force, f); add(torque, cross(p, f));
			}
			// Fixed normal means and unresolved wall moments are kinematic controls, not added inertial DOFs.
			var reaction = new double[faces.size() + walls.size() + 6 * componentCount];
			for (int row = 0; row < fullSample.length; row++) for (int column = faces.size(); column < reaction.length; column++)
				reaction[column] += fullSample[row][column] * actual[row / domain.particleCount()][row % domain.particleCount()];
			for (int w = 0; w < walls.size(); w++) reaction[faces.size() + w] -= walls.get(w).sign() * area * pressure[walls.get(w).piece()];
			var wallMoment = new double[walls.size()][2];
			for (int component = 0; component < componentCount; component++) for (int axis = 0; axis < 3; axis++) {
				var k = wallSlopeGram(component, axis); double k00 = k[0], k01 = k[1], k11 = k[2];
				var inverse = inverse2(k00, k01, k11);
				double a = reaction[theta(component, axis, 0)], b = reaction[theta(component, axis, 1)];
				double z0 = inverse[0]*a+inverse[1]*b, z1 = inverse[1]*a+inverse[2]*b;
				near(k00*z0+k01*z1, a, 1e-10, "Missing tangent direction has actual fixed-wall support");
				near(k01*z0+k11*z1, b, 1e-10, "Missing tangent direction has actual fixed-wall support");
				for (int w = 0; w < walls.size(); w++) if (components[walls.get(w).piece()] == component && walls.get(w).axis() == axis)
					for (int slot = 0; slot < 2; slot++) wallMoment[w][slot] = slopes[faces.size() + w][slot][theta(component, axis, 0)] * z0
							+ slopes[faces.size() + w][slot][theta(component, axis, 1)] * z1;
			}
			for (int w = 0; w < walls.size(); w++) {
				var wall = walls.get(w); double mean = reaction[faces.size() + w];
				for (int g = 0; g < walls.size(); g++) for (int slot = 0; slot < 2; slot++)
					mean -= slopes[faces.size() + g][slot][faces.size() + w] * wallMoment[g][slot];
				var f = new double[3]; f[wall.axis()] = -mean;
				add(force, f); add(torque, cross(faceCenter(pieces.get(wall.piece()).coordinate(), wall.axis(), wall.sign()), f));
				for (int slot = 0; slot < 2; slot++) {
					var tangent = new double[3]; tangent[(wall.axis() + slot + 1) % 3] = h;
					Arrays.fill(f, 0); f[wall.axis()] = -wallMoment[w][slot]; add(torque, cross(tangent, f));
				}
			}
			for (Face face : faces) if (face.gasNode() >= 0) {
				int sign = face.minus() >= 0 ? 1 : -1, piece = face.minus() >= 0 ? face.minus() : face.plus();
				var f = new double[3]; f[face.axis()] = sign * area * gasPressure[face.gasNode()];
				add(force, f); add(torque, cross(faceCenter(pieces.get(piece).coordinate(), face.axis(), sign), f));
			}
			return new Balance(force, torque);
		}
		double[] faceCenter(int[] coordinate, int axis, int sign) {
			var p = new double[3]; for (int a = 0; a < 3; a++) p[a] = h * (coordinate[a] + (a == axis ? sign > 0 ? 1 : 0 : .5)); return p;
		}
	}

	private static double energy(double[][] mass, double[] v) { return .5 * dot(v, multiply(mass, v)); }
	private static double[] subtract(double[] a, double[] b) { var result = a.clone(); for (int i = 0; i < result.length; i++) result[i] -= b[i]; return result; }
	private static double[] multiply(double[][] matrix, double[] v) { var result = new double[matrix.length]; for (int i = 0; i < result.length; i++) result[i] = dot(matrix[i], v); return result; }
	private static double dot(double[] a, double[] b) { double sum = 0; for (int i = 0; i < a.length; i++) sum += a[i] * b[i]; return sum; }
	private static void add(double[] a, double[] b) { for (int i = 0; i < 3; i++) a[i] += b[i]; }
	private static double[] cross(double[] a, double[] b) { return new double[]{a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]}; }
	private static void addScaled(double[] target, double[] source, double scale) { for (int i = 0; i < target.length; i++) target[i] += scale * source[i]; }
	private static double[] inverse2(double a, double b, double c) {
		double determinant = a*c-b*b, trace = a+c;
		if (determinant > 0) return new double[]{c/determinant, -b/determinant, a/determinant};
		if (trace > 0) return new double[]{a/(trace*trace), b/(trace*trace), c/(trace*trace)};
		return new double[3];
	}
	private static double rigid(double[] p, int axis, int mode) {
		if (mode < 3) return axis == mode ? 1 : 0;
		int rotation = mode - 3;
		return axis == (rotation + 1) % 3 ? -p[(rotation + 2) % 3] : axis == (rotation + 2) % 3 ? p[(rotation + 1) % 3] : 0;
	}
}
