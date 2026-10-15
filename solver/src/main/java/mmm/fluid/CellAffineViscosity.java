package mmm.fluid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import org.ejml.data.DMatrixRMaj;
import org.ejml.dense.row.factory.DecompositionFactory_DDRM;

/** Symmetric interior-penalty Newtonian stress on the actual mass-weighted wet velocity space. */
final class CellAffineViscosity {
	private final FluidDomain domain;
	private final PressureSystem.SpatialInput spatial;
	private final CellAffineVelocity basis;
	private final Block[] blocks;
	private final List<Face> faces;
	private final int count, modes;
	private final double[] rootMass, inverseRootMass;
	private double[][] increment, faceIncrement;

	private static final class Block {
		final int id, start, size, degree;
		final int[] markers;
		final double[][] fields;
		final double[][] orthogonal, root;
		final List<VolumePoint> points = new ArrayList<>();
		final double[][] shape = new double[3][];
		final double[] offset = new double[3], volume;
		final List<TractionPoint> tractionPoints = new ArrayList<>();
		final double[] gradient = new double[9];
		double trace;
		Block(CellAffineVelocity basis, int id, int start) {
			this.id = id; this.start = start; size = basis.modeCount(id);
			markers = new int[basis.blockMarkerCount(id)];
			int p = 0;
			for (int i = 0; i < markers.length; i++) markers[i] = basis.blockMarker(id, i);
			fields = new double[size][12]; volume = new double[size * size];
			for (int axis = 0; axis < 3; axis++) {
				offset[axis] = basis.normalizedCenterOffsetExact(id, axis).toDouble();
				shape[axis] = basis.normalShape(id, axis);
				p = Math.max(p, shape[axis][2] != 0 ? 2 : shape[axis][1] != 0 ? 1 : 0);
			}
			degree = p;
			for (int mode = 0; mode < size; mode++) for (int axis = 0; axis < 3; axis++) for (int term = 0; term < 4; term++)
				fields[mode][4 * axis + term] = basis.modeCoefficientExact(id, mode, axis, term).toDouble();
			var transpose = new DMatrixRMaj(3 * markers.length, size);
			for (int axis = 0; axis < 3; axis++) for (int i = 0; i < markers.length; i++) for (int mode = 0; mode < size; mode++)
				transpose.set(axis * markers.length + i, mode, basis.modeWeightExact(markers[i], mode, axis).toDouble() / Math.sqrt(basis.massKilograms(markers[i])));
			var qr = DecompositionFactory_DDRM.qr(transpose.numRows, size);
			if (!qr.decompose(transpose)) throw new IllegalStateException("Unavailable viscous source coordinates");
			var q = qr.getQ(null, true); var r = qr.getR(null, true);
			orthogonal = new double[q.numRows][size]; root = new double[size][size];
			for (int i = 0; i < q.numRows; i++) for (int j = 0; j < size; j++) orthogonal[i][j] = q.get(i, j);
			for (int i = 0; i < size; i++) for (int j = i; j < size; j++) root[i][j] = r.get(i, j);
		}
		void evaluate(double x, double y, double z, double h, double[][] value, double[][] strain, double[][] traction, int axis, int sign) {
			double[] t = {x, y, z};
			for (int mode = 0; mode < size; mode++) {
				for (int a = 0; a < 3; a++) {
					int base = 4 * a; var q = shape[a];
					double affine = fields[mode][base];
					for (int b = 0; b < 3; b++) affine = Math.fma(fields[mode][base + b + 1], t[b] + offset[b], affine);
					double shapeValue = Math.fma(Math.fma(q[2], t[a], q[1]), t[a], q[0]);
					if (value != null) value[mode][a] = shapeValue * affine;
					for (int b = 0; b < 3; b++) gradient[3 * a + b] = (shapeValue * fields[mode][base + b + 1]
							+ (a == b ? Math.fma(2 * q[2], t[a], q[1]) * affine : 0)) / h;
				}
				if (strain != null) {
					for (int a = 0; a < 3; a++) strain[mode][a] = Math.sqrt(2) * gradient[4 * a];
					strain[mode][3] = gradient[1] + gradient[3];
					strain[mode][4] = gradient[2] + gradient[6];
					strain[mode][5] = gradient[5] + gradient[7];
				}
				if (traction != null) for (int a = 0; a < 3; a++) traction[mode][a] = sign * (gradient[3 * a + axis] + gradient[3 * axis + a]);
			}
		}
		void toModal(double[] coordinates, double[] modal) {
			for (int i = 0; i < size; i++) { double value = 0; for (int j = 0; j <= i; j++) value = Math.fma(root[j][i], coordinates[start + j], value); modal[start + i] = value; }
		}
		void fromModal(double[] modal, double[] coordinates) {
			for (int i = 0; i < size; i++) { double value = 0; for (int j = i; j < size; j++) value = Math.fma(root[i][j], modal[start + j], value); coordinates[start + i] = value; }
		}
	}
	private record VolumePoint(double weight, double[][] strain) { }
	private record TractionPoint(double weight,double[][] traction) { }
	private record FacePoint(double weight, double[][] jump, double[][] firstTraction, double[][] secondTraction) { }
	private static final class Face {
		final Block first, second;
		final int size;
		final double[] jump, firstCross, secondCross;
		final List<FacePoint> points = new ArrayList<>();
		Face(Block first, Block second) {
			this.first = first; this.second = second; size = first.size + (second == null ? 0 : second.size);
			jump = new double[size * size]; firstCross = new double[jump.length]; secondCross = new double[jump.length];
		}
	}

	CellAffineViscosity(FluidDomain domain, PressureSystem.SpatialInput spatial) {
		this.domain = domain; this.spatial = spatial; basis = spatial.basis(); count = basis.markerCount();
		blocks = new Block[basis.blockCount()]; int total = 0;
		rootMass = new double[count]; inverseRootMass = new double[count];
		for (int i = 0; i < count; i++) { rootMass[i] = Math.sqrt(basis.massKilograms(i)); inverseRootMass[i] = 1 / rootMass[i]; }
		for (int block = 0; block < blocks.length; block++) if (basis.modeCount(block) > 0) {
			var b = blocks[block] = new Block(basis, block, total); total += b.size;
			var quadrature = spatial.geometry().volumeQuadrature(basis.physicalCell(block), basis.blockSupport(block),2*b.degree);
			var strain = new double[b.size][6];
			for (int q = 0; q < quadrature.weight().length; q++) {
				b.evaluate(quadrature.x()[q], quadrature.y()[q], quadrature.z()[q], basis.cellSize(), null, strain, null, 0, 1);
				b.points.add(new VolumePoint(quadrature.weight()[q], copy(strain)));
				for (int i = 0; i < b.size; i++) for (int j = 0; j <= i; j++) {
					double value = 0; for (int a = 0; a < 6; a++) value = Math.fma(strain[i][a], strain[j][a], value);
					b.volume[i * b.size + j] += quadrature.weight()[q] * value;
				}
			}
			mirror(b.volume, b.size);
		}
		modes = total; var pending = new LinkedHashMap<Long, Face>(); var geometry = spatial.geometry();
		var shared = new LinkedHashMap<SharedGroup,List<LiquidPressureGeometry.SharedFace>>();
		for (var face : geometry.sharedFaces()) {
			int first = basis.blockForMicro(face.minusCell(),face.minusMicro()), second = basis.blockForMicro(face.plusCell(),face.plusMicro());
			if (activePair(first,second)) shared.computeIfAbsent(new SharedGroup(first,second,face.axis()),ignored -> new ArrayList<>()).add(face);
		}
		for (var entry : shared.entrySet()) {
			var group = entry.getKey();
			addFace(pending,group.first,group.second,group.axis,1,geometry.sharedQuadrature(List.copyOf(entry.getValue()),faceDegree(group.first,group.second)));
		}
		for (var face : geometry.interiorFaces()) {
			int first = basis.blockForMicro(face.cell(), face.minusMicro()), second = basis.blockForMicro(face.cell(), face.plusMicro());
			if (activePair(first,second)) addFace(pending, first, second, face.axis(), 1, geometry.interiorQuadrature(face,faceDegree(first,second)));
		}
		if (domain.config.viscousWallCondition() == FluidSolverConfig.WallCondition.NO_SLIP) {
			var walls = new LinkedHashMap<WallGroup,List<LiquidPressureGeometry.Facet>>();
			for (int cell = 0; cell < domain.cellCount; cell++) if (geometry.cellSnapshot(cell) != null)
				for (var face : geometry.cellSnapshot(cell).facets()) if (face.kind() == LiquidPressureGeometry.FacetKind.WALL) {
					int block = basis.blockForMicro(cell,face.microKey()/6);
					if (activePair(block,-1)) walls.computeIfAbsent(new WallGroup(block,face.axis(),face.sign(),face.microKey()/6>>face.axis()&1),ignored -> new ArrayList<>()).add(face);
				}
			for (var entry : walls.entrySet()) {
				var group = entry.getKey();
				addFace(pending,group.block,-1,group.axis,group.sign,geometry.wallQuadrature(List.copyOf(entry.getValue()),faceDegree(group.block,-1)));
			}
		}
		faces = List.copyOf(pending.values());
		for (var face : faces) { mirror(face.jump, face.size); mirror(face.firstCross, face.size); mirror(face.secondCross, face.size); }
		for (var block : blocks) if (block != null) { block.trace = trace(block); block.tractionPoints.clear(); }
	}
	private record SharedGroup(int first,int second,int axis) { }
	private record WallGroup(int block,int axis,int sign,int half) { }
	private boolean activePair(int first,int second) {
		return first >= 0 && first != second && blocks[first] != null && (second < 0 || blocks[second] != null);
	}
	private int faceDegree(int first,int second) { return 2*(1+Math.max(blocks[first].degree,second < 0 ? 0 : blocks[second].degree)); }
	private void addFace(LinkedHashMap<Long, Face> pending, int firstId, int secondId,
			int axis, int sign, LiquidPressureGeometry.Quadrature q) {
		if (firstId < 0 || secondId == firstId) return;
		var first = blocks[firstId]; var second = secondId < 0 ? null : blocks[secondId];
		if (first == null || secondId >= 0 && second == null) return;
		long key = (long) firstId << 32 | (secondId + 1L) & 0xffffffffL;
		var face = pending.computeIfAbsent(key, ignored -> new Face(first, second));
		double average = second == null ? 1 : .5;
		var value = new double[face.size][3]; var tractionFirst = new double[first.size][3];
		var tractionSecond = second == null ? null : new double[second.size][3];
		var peerValue = second == null ? null : new double[second.size][3];
		for (int point = 0; point < q.weight().length; point++) {
			first.evaluate(q.x()[point], q.y()[point], q.z()[point], basis.cellSize(), value, null, tractionFirst, axis, sign);
			if (second != null) {
				int a = basis.physicalCell(firstId), b = basis.physicalCell(secondId);
				double dx = a % domain.sizeX - b % domain.sizeX, dy = a / domain.sizeX % domain.sizeY - b / domain.sizeX % domain.sizeY;
				double dz = a / (domain.sizeX * domain.sizeY) - b / (domain.sizeX * domain.sizeY);
				second.evaluate(q.x()[point] + dx, q.y()[point] + dy, q.z()[point] + dz, basis.cellSize(), peerValue, null, tractionSecond, axis, sign);
				for (int i = 0; i < second.size; i++) for (int d = 0; d < 3; d++) value[first.size + i][d] = -peerValue[i][d];
			}
			face.points.add(new FacePoint(q.weight()[point], copy(value), copy(tractionFirst), tractionSecond == null ? null : copy(tractionSecond)));
			var captured = face.points.getLast();
			first.tractionPoints.add(new TractionPoint(q.weight()[point]*4*average*average,captured.firstTraction));
			if (second != null) second.tractionPoints.add(new TractionPoint(q.weight()[point]*4*average*average,captured.secondTraction));
			for (int i = 0; i < face.size; i++) for (int j = 0; j <= i; j++) {
				double jump = 0, left = 0, right = 0;
				for (int a = 0; a < 3; a++) {
					jump += value[i][a] * value[j][a];
					left += (i < first.size ? tractionFirst[i][a] * value[j][a] : 0)
							+ (j < first.size ? value[i][a] * tractionFirst[j][a] : 0);
					if (second != null) right += (i >= first.size ? tractionSecond[i - first.size][a] * value[j][a] : 0)
							+ (j >= first.size ? value[i][a] * tractionSecond[j - first.size][a] : 0);
				}
				int entry = i * face.size + j; double weight = q.weight()[point];
				face.jump[entry] += weight * jump; face.firstCross[entry] -= weight * average * left; face.secondCross[entry] -= weight * average * right;
			}
		}
	}
	/** Actual discrete trace norm on the represented strain image; rigid motion has zero strain. */
	private static double trace(Block block) {
		if (block.points.isEmpty()) return 0;
		var strain = new DMatrixRMaj(6*block.points.size(),block.size);
		for (int p = 0; p < block.points.size(); p++) {
			var point = block.points.get(p); double root = Math.sqrt(point.weight);
			for (int i = 0; i < block.size; i++) for (int a = 0; a < 6; a++) strain.set(6*p+a,i,root*point.strain[i][a]);
		}
		var svd = DecompositionFactory_DDRM.svd(strain.numRows,strain.numCols,false,true,true);
		if (!svd.decompose(strain)) throw new IllegalStateException("Unavailable viscous strain coordinates");
		var sigma = svd.getSingularValues(); double largest = 0;
		for (int i = 0; i < svd.numberOfSingularValues(); i++) largest = Math.max(largest,sigma[i]);
		if (largest == 0) return 0;
		double error = Math.max(strain.numRows,strain.numCols)*Math.ulp(largest);
		var vectors = svd.getV(null,false); var kept = new ArrayList<Integer>();
		for (int i = 0; i < svd.numberOfSingularValues(); i++) if (sigma[i] > error) kept.add(i);
		if (kept.isEmpty()) return 0;
		var gram = new DMatrixRMaj(kept.size(),kept.size()); var mapped = new double[kept.size()][3];
		for (var point : block.tractionPoints) {
			for (int i = 0; i < kept.size(); i++) for (int a = 0; a < 3; a++) {
				int singular = kept.get(i); double value = 0;
				for (int k = 0; k < block.size; k++) value = Math.fma(point.traction[k][a],vectors.get(k,singular),value);
				mapped[i][a] = value/sigma[singular];
			}
			for (int i = 0; i < kept.size(); i++) for (int j = 0; j <= i; j++) {
				double value = 0; for (int a = 0; a < 3; a++) value = Math.fma(mapped[i][a],mapped[j][a],value);
				gram.add(i,j,point.weight*value);
			}
		}
		for (int i = 0; i < kept.size(); i++) for (int j = 0; j < i; j++) gram.set(j,i,gram.get(i,j));
		var eig = DecompositionFactory_DDRM.eig(kept.size(),false,true);
		if (!eig.decompose(gram)) throw new IllegalStateException("Unavailable viscous trace norm");
		double result = 0;
		for (int i = 0; i < kept.size(); i++) result = Math.max(result,eig.getEigenvalue(i).getReal());
		if (!Double.isFinite(result)) throw new IllegalStateException("Unrepresentable viscous trace norm");
		return result == 0 ? 0 : Math.nextUp(result);
	}
	private static void mirror(double[] matrix, int size) { for (int i = 0; i < size; i++) for (int j = 0; j < i; j++) matrix[j * size + i] = matrix[i * size + j]; }
	private static double[][] copy(double[][] values) { var result = new double[values.length][]; for (int i = 0; i < values.length; i++) result[i] = values[i].clone(); return result; }

	double[][] increment() { return increment; }
	double[][] faceIncrement() { return faceIncrement; }
	FluidDomain.ViscousStepStats advance(float dt) {
		if (!(dt > 0 && Float.isFinite(dt))) throw new IllegalArgumentException("Invalid Newtonian timestep");
		var viscosity = new double[blocks.length];
		for (var block : blocks) if (block != null) {
			double mu = domain.carrierPropertiesWorkspace.viscosityPaPerS(basis.physicalCell(block.id));
			if (!(mu > 0 && Double.isFinite(mu))) throw new IllegalStateException("Invalid actual-mass viscosity");
			viscosity[block.id] = mu;
		}
		var matrices = new ArrayList<double[]>(); var owners = new ArrayList<int[]>();
		for (var b : blocks) if (b != null) {
			var matrix = b.volume.clone(); for (int i = 0; i < matrix.length; i++) matrix[i] *= viscosity[b.id];
			matrices.add(matrix); owners.add(new int[]{b.id});
		}
		for (var f : faces) {
			double firstMu = viscosity[f.first.id], secondMu = f.second == null ? 0 : viscosity[f.second.id];
			// Incident trace sums and Young's inequality leave at least half the volume energy.
			double penalty = 2 * Math.max(firstMu * f.first.trace, f.second == null ? 0 : secondMu * f.second.trace);
			var matrix = new double[f.jump.length];
			for (int i = 0; i < matrix.length; i++) matrix[i] = Math.fma(penalty, f.jump[i], Math.fma(firstMu, f.firstCross[i], secondMu * f.secondCross[i]));
			matrices.add(matrix); owners.add(f.second == null ? new int[]{f.first.id} : new int[]{f.first.id, f.second.id});
		}
		var operator = new Stress(matrices, owners, viscosity);
		var original = new double[3 * count];
		for (int i = 0; i < count; i++) { original[i] = domain.particles.velocityX(i); original[count + i] = domain.particles.velocityY(i); original[2 * count + i] = domain.particles.velocityZ(i); }
		var rhs = new double[modes];
		for (var block : blocks) if (block != null) for (int mode = 0; mode < block.size; mode++) {
			double value = 0;
			for (int axis = 0; axis < 3; axis++) for (int i = 0; i < block.markers.length; i++) {
				int marker = block.markers[i];
				value = Math.fma(block.orthogonal[axis * block.markers.length + i][mode], rootMass[marker] * original[axis * count + marker], value);
			}
			rhs[block.start + mode] = value;
		}
		var force = new double[modes];
		var solution = new double[rhs.length]; var residual = rhs.clone(); var direction = new double[rhs.length];
		var image = new double[rhs.length]; var response = new double[rhs.length];
		// Choose the better actual starting residual of the previous velocity and rest.
		operator.apply(rhs,force);
		for (int i = 0; i < image.length; i++) image[i] = -dt*force[i];
		if (norm(image) < norm(residual)) { System.arraycopy(rhs,0,solution,0,rhs.length); System.arraycopy(image,0,residual,0,image.length); }
		var diagonal = operator.diagonal(); double spectral = 0;
		for (int i = 0; i < diagonal.length; i++) { spectral += diagonal[i]; diagonal[i] = 1 + dt * diagonal[i]; if (!(diagonal[i] > 0 && Double.isFinite(diagonal[i]))) throw new IllegalStateException("Invalid viscous inertia"); }
		double tolerance = 0;
		for (int i = 0; i < original.length; i++) tolerance = Math.hypot(tolerance, .5 * Math.ulp((float) original[i]) * rootMass[i % count]);
		double product = 0;
		for (int i = 0; i < rhs.length; i++) { direction[i] = response[i] = residual[i] / diagonal[i]; product += residual[i] * response[i]; }
		int iteration = 0;
		while (iteration < domain.config.maxPressureIterations()) {
			if (norm(residual) <= tolerance) {
				operator.apply(solution, force);
				for (int i = 0; i < residual.length; i++) residual[i] = Math.fma(-dt, force[i], rhs[i] - solution[i]);
				if (norm(residual) <= tolerance) break;
				product = 0;
				for (int i = 0; i < direction.length; i++) { direction[i] = residual[i] / diagonal[i]; product += residual[i] * direction[i]; }
			}
			operator.apply(direction, force);
			for (int i = 0; i < image.length; i++) image[i] = Math.fma(dt, force[i], direction[i]);
			double denominator = dot(direction, image), alpha = product / denominator;
			if (!(denominator > 0 && alpha > 0 && Double.isFinite(alpha))) throw new IllegalStateException("Invalid implicit Newtonian direction");
			for (int i = 0; i < solution.length; i++) { solution[i] = Math.fma(alpha, direction[i], solution[i]); residual[i] = Math.fma(-alpha, image[i], residual[i]); }
			double next = 0;
			for (int i = 0; i < response.length; i++) { response[i] = residual[i] / diagonal[i]; next += residual[i] * response[i]; }
			double beta = next / product;
			for (int i = 0; i < direction.length; i++) direction[i] = Math.fma(beta, direction[i], response[i]);
			product = next; iteration++;
		}
		operator.apply(solution, force);
		for (int i = 0; i < residual.length; i++) residual[i] = Math.fma(-dt, force[i], rhs[i] - solution[i]);
		double remaining = norm(residual);
		if (!(Double.isFinite(remaining) && remaining <= tolerance)) throw new FluidDomain.PressureSolveBlockedException(
				"Implicit Newtonian stress did not converge within " + iteration + " iterations: residual=" + remaining + ", tolerance=" + tolerance);
		increment = new double[3][count]; var nextVelocity = new double[3][count]; double dissipated = 0; var impulse = new double[3];
		for (var block : blocks) if (block != null) for (int axis = 0; axis < 3; axis++) for (int i = 0; i < block.markers.length; i++) {
			double change = 0;
			for (int mode = 0; mode < block.size; mode++) change = Math.fma(block.orthogonal[axis * block.markers.length + i][mode],
					solution[block.start + mode] - rhs[block.start + mode], change);
			increment[axis][block.markers[i]] = change * inverseRootMass[block.markers[i]];
		}
		for (int axis = 0; axis < 3; axis++) for (int i = 0; i < count; i++) {
			int index = axis * count + i; double change = increment[axis][i], next = original[index] + change;
			if (!Double.isFinite(next)) throw new FluidDomain.PressureSolveBlockedException("Unrepresentable Newtonian velocity");
			increment[axis][i] = change; nextVelocity[axis][i] = next;
			impulse[axis] += basis.massKilograms(i) * change;
			dissipated -= .5 * basis.massKilograms(i) * change * (next + original[index]);
		}
		var coefficient = new double[3][4 * basis.blockCount()]; basis.fit(nextVelocity, coefficient);
		faceIncrement = new double[][]{new double[domain.mac.velocityX.length], new double[domain.mac.velocityY.length], new double[domain.mac.velocityZ.length]};
		spatial.transfer().apply(coefficient, faceIncrement);
		float[][] mac = {domain.mac.velocityX, domain.mac.velocityY, domain.mac.velocityZ};
		for (int axis = 0; axis < 3; axis++) for (int face = 0; face < mac[axis].length; face++) {
			if (!(spatial.transfer().wetArea(axis, face) > 0)) { faceIncrement[axis][face] = 0; continue; }
			float next = (float) faceIncrement[axis][face];
			if (!Float.isFinite(next)) throw new FluidDomain.PressureSolveBlockedException("Unrepresentable Newtonian MAC velocity");
			faceIncrement[axis][face] = (double) next - mac[axis][face]; mac[axis][face] = next;
		}
		return new FluidDomain.ViscousStepStats(modes + 3 * faces.size(), spectral, impulse[0], impulse[1], impulse[2], dissipated, 0, 0, 0);
	}
	private final class Stress {
		final List<double[]> matrices; final List<int[]> owners;
		final double[] viscosity;
		final double[] modal = new double[modes], load = new double[modes];
		final double[] strain = new double[6], jump = new double[3], traction = new double[3], peerTraction = new double[3];
		Stress(List<double[]> matrices, List<int[]> owners, double[] viscosity) { this.matrices = matrices; this.owners = owners; this.viscosity = viscosity; }
		void apply(double[] velocity, double[] result) {
			for (var block : blocks) if (block != null) block.toModal(velocity, modal);
			Arrays.fill(load, 0);
			for (var block : blocks) if (block != null) for (var point : block.points) {
				Arrays.fill(strain, 0);
				for (int mode = 0; mode < block.size; mode++) for (int a = 0; a < 6; a++) strain[a] = Math.fma(point.strain[mode][a], modal[block.start + mode], strain[a]);
				for (int mode = 0; mode < block.size; mode++) {
					double value = 0; for (int a = 0; a < 6; a++) value = Math.fma(point.strain[mode][a], strain[a], value);
					load[block.start + mode] += point.weight * viscosity[block.id] * value;
				}
			}
			for (var face : faces) {
				double firstMu = viscosity[face.first.id], secondMu = face.second == null ? 0 : viscosity[face.second.id];
				double average = face.second == null ? 1 : .5;
				double penalty = 2 * Math.max(firstMu * face.first.trace, face.second == null ? 0 : secondMu * face.second.trace);
				for (var point : face.points) {
					Arrays.fill(jump, 0); Arrays.fill(traction, 0); Arrays.fill(peerTraction, 0);
					for (int mode = 0; mode < face.size; mode++) {
						boolean first = mode < face.first.size;
						int local = first ? mode : mode - face.first.size;
						int global = first ? face.first.start + local : face.second.start + local;
						var part = first ? point.firstTraction[local] : point.secondTraction[local];
						for (int a = 0; a < 3; a++) {
							jump[a] = Math.fma(point.jump[mode][a], modal[global], jump[a]);
							if (first) traction[a] = Math.fma(part[a], modal[global], traction[a]);
							else peerTraction[a] = Math.fma(part[a], modal[global], peerTraction[a]);
						}
					}
					for (int a = 0; a < 3; a++) traction[a] = average * Math.fma(firstMu, traction[a], secondMu * peerTraction[a]);
					for (int mode = 0; mode < face.size; mode++) {
						boolean first = mode < face.first.size; int local = first ? mode : mode - face.first.size;
						int global = first ? face.first.start + local : face.second.start + local;
						var part = first ? point.firstTraction[local] : point.secondTraction[local];
						double value = 0;
						for (int a = 0; a < 3; a++) value += point.jump[mode][a] * (penalty * jump[a] - traction[a])
								- average * (first ? firstMu : secondMu) * part[a] * jump[a];
						load[global] += point.weight * value;
					}
				}
			}
			for (var block : blocks) if (block != null) block.fromModal(load, result);
		}
		double[] diagonal() {
			var local = new double[blocks.length][];
			for (var block : blocks) if (block != null) local[block.id] = new double[block.size * block.size];
			for (int group = 0; group < matrices.size(); group++) {
				var ids = owners.get(group); int size = 0, offset = 0;
				for (int id : ids) size += blocks[id].size;
				for (int id : ids) {
					var b = blocks[id]; for (int i = 0; i < b.size; i++) for (int j = 0; j < b.size; j++) local[id][i * b.size + j] += matrices.get(group)[(offset + i) * size + offset + j];
					offset += b.size;
				}
			}
			var result = new double[modes];
			for (var block : blocks) if (block != null) for (int coordinate = 0; coordinate < block.size; coordinate++) {
				double value = 0;
				for (int i = coordinate; i < block.size; i++) for (int j = coordinate; j < block.size; j++) value += block.root[coordinate][i] * local[block.id][i * block.size + j] * block.root[coordinate][j];
				result[block.start + coordinate] = value;
			}
			return result;
		}
	}
	private static double dot(double[] first, double[] second) { double value = 0; for (int i = 0; i < first.length; i++) value = Math.fma(first[i], second[i], value); return value; }
	private static double norm(double[] vector) { double value = 0; for (double entry : vector) value = Math.hypot(value, entry); return value; }
}
