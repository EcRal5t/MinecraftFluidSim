package mmm.fluid;

import mmm.thermal.ThermalDomainState;
import mmm.fluid.air.AirNetwork;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;
import static mmm.fluid.CellVelocityConstraintOperator.*;

/** Actual-mass cell affine pressure, fixed wet geometry and one marker/MAC publication. */
final class MarkerPressureProjection {
	private final FluidDomain domain;
	private final PressureBoundaryView boundary;
	private CellAffineVelocity basis;
	private LiquidPressureGeometry geometry;
	private CellAffineMacTransfer transfer;
	private CellAffineVelocityField candidateField;
	private CellAffineWallProjection wallProjection;
	private CellVelocityConstraintOperator operator;
	private List<Row> rows;
	private List<int[]> internalCoarseRows;
	private record SharedBlocks(int minus, int plus, int axis) implements Comparable<SharedBlocks> {
		@Override public int compareTo(SharedBlocks other) {
			int order=Integer.compare(axis,other.axis);
			if(order==0)order=Integer.compare(plus,other.plus);
			return order==0 ? Integer.compare(minus,other.minus) : order;
		}
	}
	private int[] liquidRows, gasRows;
	private int sharedStart, sharedEnd;
	private double[][] originalVelocity, originalPosition, predictor, wallPredictor, wallImpulse, candidate, publishedVelocity, knownImpulse, faceCandidate, knownCellLoad;
	private double[] originalMass, originalVolume, originalFraction, originalPlaneOffset, pressure, commonPressureMode, rhs, storage, gasInitialPressure, targetVolumeRate, observedPressure;
	private double[][] originalPlaneNormal;
	private double[] nodeStorage;
	private double[] diagnosticGasDiagonal, diagnosticGasRhs;
	private boolean[] originalPressureFluid;
	private float[][] originalMacVelocity, originalP2gVelocity;
	private byte[] geometryMasks;
	private long carrierRevision, gasRevision;
	private AirNetwork gasNetworkOwner;
	private GasRuntimeGeometry gasFrame;
	private long pressurePhaseRevision;
	private ThermalDomainState thermalOwner;
	private ThermalDomainState.Revision thermalRevision;
	private int pressureAnchor;
	private float pressureReference;
	private boolean gridDirty, velocityGridDirty;
	private float dt;
	private boolean active, solved, published, hasFreeSurface;

	MarkerPressureProjection(FluidDomain domain) { this.domain = domain; boundary = new PressureBoundaryView(domain); }

	boolean prepare(float dt) {
		if (!(Float.isFinite(dt) && dt > 0)) throw new IllegalArgumentException("Invalid marker pressure timestep");
		active = solved = published = hasFreeSurface = false; this.dt = dt;
		if (!domain.pressureSystem.usesMarkerPressure()) throw new IllegalStateException("Cell pressure requires liquid-only spatial inventory");
		if (domain.particles.count() == 0) return false;
		gasFrame = domain.gasBoundary.frame();
		if (gasFrame == null) throw blocked("Marker pressure requires current gas geometry");
		geometry = gasFrame.liquidPressureGeometry(domain); basis = CellAffineVelocity.capture(domain, geometry);
		boolean[] gasContacts = new boolean[domain.gasState.network.nodeCount() + 1];
		geometry.forEachGasPiece(piece -> gasContacts[gasNode(piece)] = true);
		pressureAnchor = -1;
		for (int cell = 0; cell < domain.cellCount; cell++) {
			var profile = geometry.cellSnapshot(cell);
			if (profile != null && profile.volume() > 0) { pressureAnchor = cell; break; }
		}
		for (boolean contact : gasContacts) if (contact) { pressureAnchor = -1; break; }
		pressureReference = boundary.pressureReference(gasContacts);
		domain.pressureAnchor = pressureAnchor; domain.pressureReference = pressureReference;
		wallProjection = new CellAffineWallProjection(domain, basis, geometry);
		transfer = CellAffineMacTransfer.capture(domain, basis, geometry);
		int count = basis.markerCount();
		originalVelocity = markers(count); originalPosition = markers(count); originalMass = new double[count]; originalVolume = new double[count];
		for (int i = 0; i < count; i++) {
			originalPosition[0][i] = domain.particles.positionX(i); originalPosition[1][i] = domain.particles.positionY(i); originalPosition[2][i] = domain.particles.positionZ(i);
			originalVelocity[0][i] = domain.particles.velocityX(i); originalVelocity[1][i] = domain.particles.velocityY(i); originalVelocity[2][i] = domain.particles.velocityZ(i);
			originalMass[i] = domain.particles.mass(i); originalVolume[i] = domain.particles.volume(i);
		}
		originalFraction = new double[domain.cellCount]; originalPlaneOffset = new double[domain.cellCount]; originalPlaneNormal = new double[3][domain.cellCount];
		originalPressureFluid = domain.projection.pressureFluid.clone();
		for (int cell = 0; cell < domain.cellCount; cell++) {
			originalFraction[cell] = domain.pressurePhaseGeometry.liquidFractionExact(cell); originalPlaneOffset[cell] = domain.pressurePhaseGeometry.planeOffset(cell);
			for (int axis = 0; axis < 3; axis++) originalPlaneNormal[axis][cell] = domain.pressurePhaseGeometry.planeNormal(cell, axis);
		}
		carrierRevision = domain.carrierMutationRevision; gasRevision = domain.gasState.network.revision();
		gasNetworkOwner = domain.gasState.network; gasFrame = domain.gasBoundary.frame();
		pressurePhaseRevision = domain.pressurePhaseGeometry.revision();
		thermalOwner = domain.thermalState; thermalRevision = thermalOwner == null ? null : thermalOwner.thermalRevision();
		pressureAnchor = domain.pressureAnchor; pressureReference = domain.pressureReference;
		gridDirty = domain.gridDirty; velocityGridDirty = domain.velocityGridDirty; geometryMasks = domain.geometry.cutCells.copyMicroMasks();
		float[][] current = {domain.mac.velocityX, domain.mac.velocityY, domain.mac.velocityZ};
		float[][] previous = {domain.mac.p2gVelocityX, domain.mac.p2gVelocityY, domain.mac.p2gVelocityZ};
		originalMacVelocity = copy(current); originalP2gVelocity = copy(previous);
		int gasNodeCount = domain.gasState.network.nodeCount();
		domain.projection.ensureGasCapacity(gasNodeCount);
		liquidRows = new int[basis.blockCount()]; gasRows = new int[gasNodeCount + 1];
		Arrays.fill(liquidRows, -1); Arrays.fill(gasRows, -1);
		nodeStorage = new double[gasRows.length]; Arrays.fill(nodeStorage, Double.NaN);
		var definitions = new ArrayList<Row>(); var gasBlocks = new TreeMap<Integer, List<Block>>();
		internalCoarseRows=new ArrayList<>();
		knownCellLoad = cellVectors();
		for (int block = 0; block < basis.blockCount(); block++) {
			if (basis.blockMarkerCount(block)==0) continue;
			var profile = blockProfile(block);
			if (profile == null || !(profile.volume() > 0)) throw blocked("Liquid velocity block lacks positive wet geometry at block " + block);
			appendLiquidRows(block, profile, definitions);
		}
		// Aggregate only microfaces sharing both actual velocity blocks.
		var shared = new TreeMap<SharedBlocks, LiquidPressureGeometry.SurfaceMoments>();
		for (var face : geometry.sharedFaces()) {
			var key = new SharedBlocks(ownerBlock(face.minusCell(),face.minusMicro()),ownerBlock(face.plusCell(),face.plusMicro()),face.axis());
			shared.merge(key, face.moments(), LiquidPressureGeometry.SurfaceMoments::add);
		}
		for (var face : geometry.interiorFaces()) {
			int minus=ownerBlock(face.cell(),face.minusMicro()), plus=ownerBlock(face.cell(),face.plusMicro());
			if (minus != plus) shared.merge(new SharedBlocks(minus,plus,face.axis()),face.moments(),LiquidPressureGeometry.SurfaceMoments::add);
		}
		sharedStart = definitions.size();
		for (var entry : shared.entrySet()) {
			var face = entry.getKey();
			for (int test = 0; test < 3; test++) definitions.add(new Row(List.of(
					new Block(face.minus(), face.axis(), trace(face.minus(), face.axis(), entry.getValue(), test, 1)),
					new Block(face.plus(), face.axis(), trace(face.plus(), face.axis(), entry.getValue(), test, -1))), 0));
		}
		sharedEnd = definitions.size();
		for (int cell = 0; cell < domain.cellCount; cell++) {
			var profile = geometry.cellSnapshot(cell); if (profile == null) continue;
			for (var facet : profile.facets()) {
				if (facet.kind() == LiquidPressureGeometry.FacetKind.GAS) {
					int block=ownerBlock(cell,facet.microKey()/6);
					freeBoundary(cell, gasNode(facet.gasPiece()), List.of(new Block(block, facet.axis(), trace(block, facet.axis(), facet.moments(), 0, facet.sign()))), gasBlocks);
				}
			}
			for (var fragment : profile.freeFragments()) {
				int block=ownerBlock(cell,fragment.microKey());
				var blocks = new ArrayList<Block>();
				for (int axis = 0; axis < 3; axis++) {
					double[] coefficient = freeTrace(block, axis, fragment);
					blocks.add(new Block(block, axis, coefficient));
				}
				freeBoundary(cell, gasNode(fragment.gasPiece()), blocks, gasBlocks);
			}
		}
		for (var entry : gasBlocks.entrySet()) {
			int node = entry.getKey(); double s = nodeStorage[node];
			gasRows[node] = definitions.size(); definitions.add(new Row(entry.getValue(), s));
		}
		rows = definitions.stream().map(wallProjection::transform).toList(); operator = new CellVelocityConstraintOperator(basis, rows);
		int rowCount = rows.size(); storage = new double[rowCount]; gasInitialPressure = new double[rowCount];
		for (int row = 0; row < rowCount; row++) storage[row] = rows.get(row).storage();
		for (int node = 1; node < gasRows.length; node++) if (gasRows[node] >= 0) gasInitialPressure[gasRows[node]] = boundary.gasPressureOfNode(node) - pressureReference;
		var body = domain.bodyForceStage.takeMarkerPrediction();
		double[][] external = faces(); double[][] weights = {domain.mac.p2gWeightX, domain.mac.p2gWeightY, domain.mac.p2gWeightZ};
		for (int axis = 0; axis < 3; axis++) for (int face = 0; face < current[axis].length; face++) {
			double delta = (double) current[axis][face] - previous[axis][face] - (axis == 1 ? body.faceVerticalIncrement()[face] : 0);
			external[axis][face] = domain.unitScale.kilograms(weights[axis][face]) * delta;
			if (!Double.isFinite(external[axis][face])) throw blocked("Unrepresentable pre-pressure face impulse");
		}
		double[][] cellLoad = cellVectors(), impulse = markers(count); transfer.transpose(external, cellLoad); basis.transposeLoad(cellLoad, impulse);
		knownImpulse = markers(count); basis.transposeLoad(knownCellLoad, knownImpulse); predictor = markers(count);
		for (int axis = 0; axis < 3; axis++) for (int marker = 0; marker < count; marker++) {
			knownImpulse[axis][marker] *= dt;
			predictor[axis][marker] = originalVelocity[axis][marker] + (axis == 1 ? body.verticalIncrement()[marker] : 0)
					+ (impulse[axis][marker] + knownImpulse[axis][marker]) / basis.massKilograms(marker);
			if (!Double.isFinite(predictor[axis][marker])) throw blocked("Unrepresentable actual marker predictor");
		}
		pressure = new double[rowCount]; rhs = new double[rowCount]; targetVolumeRate = new double[rowCount]; observedPressure = new double[domain.cellCount];
		initializeCommonPressure(sharedStart, sharedEnd);
		wallPredictor = markers(count); wallImpulse = markers(count); wallProjection.project(predictor, wallPredictor, wallImpulse);
		operator.volumeRate(wallPredictor, rhs);
		for (int row = 0; row < rowCount; row++) { rhs[row] = storage[row] * gasInitialPressure[row] - rhs[row] / dt; if (!Double.isFinite(rhs[row])) throw blocked("Unrepresentable cell pressure source"); }
		active = true; return true;
	}

	private void appendLiquidRows(int block, LiquidPressureGeometry.Profile profile, List<Row> definitions) {
		liquidRows[block] = definitions.size();
		boolean shaped=false;
		for(int axis=0;axis<3;axis++) shaped |= basis.hasNormalShape(block,axis);
		var blocks=new ArrayList<Block>();
		if(!shaped) {
			for(int axis=0;axis<3;axis++) {
				double[] coefficient=new double[4]; basis.volumeDivergenceWeights(block,axis,profile.volume(),coefficient);
				blocks.add(new Block(block,axis,coefficient));
			}
			definitions.add(new Row(blocks,0)); return;
		}
		boolean completeMicroBoxes=profile.freeFragments().isEmpty();
		double[] moments=completeMicroBoxes ? null : geometry.supportMoments(basis.physicalCell(block),basis.blockSupport(block));
		double[][][] polynomial=new double[3][4][10];
		for(int axis=0;axis<3;axis++) {
			double[] coefficient=completeMicroBoxes ? boxDivergence(block,axis) : new double[4];
			for(int term=0;term<4;term++) {
				basis.divergencePolynomial(block,axis,term,polynomial[axis][term]);
				if(!completeMicroBoxes) coefficient[term]=dot(polynomial[axis][term],moments)/basis.cellSize();
			}
			blocks.add(new Block(block,axis,coefficient));
		}
		definitions.add(new Row(blocks,0));
		int[] selected=CellPressurePolynomialSpace.independentMonomials(basis,block);
		var coarseRows=new ArrayList<Integer>();
		// The nonconstant divergence coefficients enforce incompressibility inside the wet volume.
		for(int monomial=1;monomial<10;monomial++) {
			blocks=new ArrayList<Block>(); boolean positive=false;
			for(int axis=0;axis<3;axis++) {
				double[] coefficient=new double[4];
				for(int term=0;term<4;term++) {
					coefficient[term]=profile.volume()/basis.cellSize()*polynomial[axis][term][monomial];
					positive |= coefficient[term] != 0;
				}
				blocks.add(new Block(block,axis,coefficient));
			}
			if(positive) {
				if(Arrays.binarySearch(selected,monomial)>=0) coarseRows.add(definitions.size());
				definitions.add(new Row(blocks,0));
			}
		}
		if(!coarseRows.isEmpty()) internalCoarseRows.add(coarseRows.stream().mapToInt(Integer::intValue).toArray());
	}

	private double[] boxDivergence(int block,int axis) {
		int cell=basis.physicalCell(block), bit=1<<axis;
		int support=basis.blockSupport(block)&geometry.wetMicroMask(cell);
		double[] coefficient=new double[4];
		// Internal microfaces cancel geometrically; wall traces retain their exact shape zeros.
		for(int micro=0;micro<8;micro++) if((support&(1<<micro))!=0) {
			for(var face:geometry.microFaces(cell,micro)) if(face.axis()==axis && face.moments().area()>0) {
				boolean inside=(micro&bit)==0 ? face.sign()>0 : face.sign()<0;
				if(inside && (support&(1<<(micro^bit)))!=0) continue;
				double[] side=trace(block,axis,face.moments(),0,face.sign());
				for(int term=0;term<4;term++) coefficient[term]+=side[term];
			}
		}
		return coefficient;
	}

	private double[] freeTrace(int block,int axis,LiquidPressureGeometry.FreeFragment fragment) {
		double n=fragment.areaNormal().component(axis), h=basis.cellSize();
		double[] coefficient=new double[4]; coefficient[0]=n;
		for(int b=0;b<3;b++) coefficient[b+1]=(fragment.normalFirstMoment().row(axis).component(b)-n*basis.center(block,b))/h;
		if(!basis.hasNormalShape(block,axis)) return coefficient;
		if(fragment.gasMicro()!=fragment.microKey()) {
			double shape=n==0 ? 0 : basis.shapeValue(block,axis,fragment.normalFirstMoment().row(axis).component(axis)/n);
			for(int term=0;term<4;term++) coefficient[term]*=shape;
			return coefficient;
		}
		int cell=basis.physicalCell(block), micro=fragment.microKey();
		double[] moments=geometry.microMoments(cell,micro), polynomial=new double[10];
		for(int term=0;term<4;term++) {
			basis.divergencePolynomial(block,axis,term,polynomial);
			coefficient[term]=dot(polynomial,moments)/h;
		}
		for(var face:geometry.microFaces(cell,micro)) if(face.axis()==axis && face.moments().area()>0) {
			double[] side=trace(block,axis,face.moments(),0,face.sign());
			for(int term=0;term<4;term++) coefficient[term]-=side[term];
		}
		return coefficient;
	}

	private void initializeCommonPressure(int sharedStart, int sharedEnd) {
		commonPressureMode = new double[pressure.length];
		double largest = 0;
		for (int row : gasRows) if (row >= 0) largest = Math.max(largest, storage[row]);
		if (largest == 0) return;
		double weight = 0, mean = 0;
		for (int row : gasRows) if (row >= 0) {
			double added = storage[row] / largest, total = weight + added;
			if (added == 0) continue;
			mean += added / total * (gasInitialPressure[row] - mean); weight = total;
		}
		for (int row : liquidRows) if (row >= 0) { pressure[row] = mean; commonPressureMode[row] = 1; }
		for (int row = sharedStart; row < sharedEnd; row += 3) { pressure[row] = -mean; commonPressureMode[row] = -1; }
		for (int row : gasRows) if (row >= 0) { pressure[row] = mean; commonPressureMode[row] = 1; }
	}

	private double[] trace(int cell, int axis, LiquidPressureGeometry.SurfaceMoments face, int test, double sign) {
		double[] coefficient = new double[4]; double h = basis.cellSize();
		if (test == 0) {
			coefficient[0] = sign * face.area();
			for (int b = 0; b < 3; b++) coefficient[b + 1] = sign * face.area() * (face.centroid().component(b) - basis.center(cell, b)) / h;
		} else {
			int tangent = (axis + test) % 3;
			for (int b = 0; b < 3; b++) coefficient[b + 1] = sign * face.centralSecondMoment().component(tangent, b) / (h * h);
		}
		double shape=basis.shapeValue(cell,axis,face.centroid().component(axis));
		for(int term=0;term<4;term++) coefficient[term]*=shape;
		return coefficient;
	}
	private int gasNode(int piece) {
		int node = domain.gasBoundary.nodeOfPiece(piece);
		if (node < 0) throw blocked("Free liquid surface lacks current gas ownership at piece " + piece);
		return node;
	}
	private int ownerBlock(int cell,int micro) {
		int block=basis.blockForMicro(cell,micro);
		if(block<0)throw blocked("Wet microface lacks actual component inertia at cell " + cell + ", micro " + micro);
		return block;
	}
	private LiquidPressureGeometry.Profile blockProfile(int block) {
		return geometry.componentSnapshot(basis.physicalCell(block),basis.blockSupport(block));
	}
	private void freeBoundary(int cell, int node, List<Block> blocks, TreeMap<Integer, List<Block>> gasBlocks) {
		hasFreeSurface = true;
		double jump = domain.tension.surfaceTensionPressure[cell];
		boolean finite = finiteGasRow(node);
		double known = jump + (finite ? 0 : node > 0 ? boundary.gasPressureOfNode(node) - pressureReference : -pressureReference);
		for (var block : blocks) {
			double[] coefficient = block.coefficients();
			for (int term = 0; term < 4; term++) knownCellLoad[block.axis()][4 * block.velocityBlock() + term] -= known * coefficient[term];
			if (finite) {
				for (int term = 0; term < 4; term++) coefficient[term] = -coefficient[term];
				gasBlocks.computeIfAbsent(node, ignored -> new ArrayList<>()).add(new Block(block.velocityBlock(), block.axis(), coefficient));
			}
		}
	}
	private boolean finiteGasRow(int node) {
		if (!boundary.pressureActive() || !boundary.gasNodeHasContinuumState(node)) return false;
		if (Double.isNaN(nodeStorage[node])) {
			double s = boundary.gasNodeVolumeCompliance(node) / ((double) dt * dt);
			if (!(s > 0) || Double.isNaN(s)) throw blocked("Invalid finite gas pressure storage at node " + node);
			double diagnostic = s / (Math.pow(basis.cellSize(), 3) / domain.config.density());
			double p0 = boundary.gasPressureOfNode(node) - pressureReference;
			// A valid near-vacuum EoS can have an unrepresentable compliance row;
			// its actual gas pressure remains the prescribed surface traction.
			nodeStorage[node] = Double.isFinite(s) && Double.isFinite(diagnostic)
					&& Double.isFinite(s * p0) && Double.isFinite(diagnostic * p0) ? s : 0;
		}
		return nodeStorage[node] > 0;
	}
	/** Compatible semidefinite rows are solved without artificial inertia or a diagonal offset. */
	PressureProjectionResult solve(double target) {
		if (!active) return new PressureProjectionResult(0, 0, true);
		if (!(target > 0 && Double.isFinite(target))) throw new IllegalArgumentException("Invalid pressure residual target");
		requireCurrent(); solved = false;
		int n = rhs.length; double[] diagonal = new double[n], r = new double[n], z = new double[n], direction = new double[n], applied = new double[n];
		operator.diagonal(diagonal);
		double h = domain.config.cellSize(), unit = h * h * h / domain.config.density(), tolerance = target * unit;
		for (int i = 0; i < n; i++) {
			if (!(diagonal[i] >= 0 && Double.isFinite(diagonal[i]))) throw blocked("Nonfinite actual pressure diagonal at row " + i);
			if (diagonal[i] == 0 && rhs[i] != 0) throw blocked("Pressure source has no actual marker velocity support at row " + i);
			if (diagonal[i] == 0) pressure[i] = 0;
		}
		operator.apply(pressure, applied);
		for (int i = 0; i < n; i++) r[i] = rhs[i] - applied[i];
		var preconditioner = operator.preconditioner(diagonal, commonPressureMode,internalCoarseRows); preconditioner.apply(r, z); System.arraycopy(z, 0, direction, 0, n);
		double residual = rms(r), rz = dot(r, z); int iterations = 0;
		for (; residual > tolerance && iterations < domain.config.maxPressureIterations();) {
			double denominator = operator.applyWithWork(direction, applied);
			if (!(denominator > 0 && Double.isFinite(denominator) && rz > 0 && Double.isFinite(rz))) break;
			double alpha = rz / denominator;
			if (!Double.isFinite(alpha)) break;
			for (int i = 0; i < n; i++) { pressure[i] += alpha * direction[i]; r[i] -= alpha * applied[i]; }
			iterations++; residual = rms(r);
			if (residual <= tolerance) {
				operator.apply(pressure, applied);
				for (int i = 0; i < n; i++) r[i] = rhs[i] - applied[i];
				residual = rms(r);
				if (residual <= tolerance) break;
				// A fresh residual restarts the compatible-range iteration.
				preconditioner.apply(r, z); System.arraycopy(z, 0, direction, 0, n);
				rz = dot(r, z); continue;
			}
			preconditioner.apply(r, z);
			double nextRz = dot(r, z), beta = nextRz / rz;
			if (!(nextRz > 0 && Double.isFinite(nextRz) && Double.isFinite(beta))) break;
			for (int i = 0; i < n; i++) direction[i] = z[i] + beta * direction[i];
			rz = nextRz;
		}
		operator.apply(pressure, applied);
		for (int i = 0; i < n; i++) r[i] = rhs[i] - applied[i];
		residual = rms(r);
		if (!Double.isFinite(residual) || residual > tolerance) return new PressureProjectionResult(iterations, residual / unit, false);
		double[][] force = markers(basis.markerCount()); operator.force(pressure, force);
		candidate = markers(basis.markerCount()); publishedVelocity = markers(basis.markerCount());
		for (int a = 0; a < 3; a++) for (int i = 0; i < basis.markerCount(); i++) {
			double velocity = wallPredictor[a][i] + dt * force[a][i] / basis.massKilograms(i);
			if (!Float.isFinite((float) velocity)) throw blocked("Unrepresentable projected marker velocity at marker " + i);
			candidate[a][i] = velocity; publishedVelocity[a][i] = (float) velocity;
		}
		double[][] candidateCoefficients = cellVectors(); basis.fit(publishedVelocity, candidateCoefficients);
		candidateField = new CellAffineVelocityField(basis, candidateCoefficients);
		faceCandidate = faces(); transfer.apply(candidateCoefficients, faceCandidate);
		for (double[] axis : faceCandidate) for (double value : axis) if (!Float.isFinite((float) value)) throw blocked("Unrepresentable projected MAC velocity");
		for (int i = 0; i < n; i++) {
			if (!Double.isFinite(pressure[i])) throw blocked("Unrepresentable projected pressure at row " + i);
			targetVolumeRate[i] = dt * storage[i] * (gasInitialPressure[i] - pressure[i]);
		}
		diagnosticGasDiagonal = new double[gasRows.length]; diagnosticGasRhs = new double[gasRows.length];
		for (int node = 1; node < gasRows.length; node++) if (gasRows[node] >= 0) {
			diagnosticGasDiagonal[node] = diagonal[gasRows[node]] / unit;
			diagnosticGasRhs[node] = rhs[gasRows[node]] / unit;
			if (!Double.isFinite(diagnosticGasDiagonal[node]) || !Double.isFinite(diagnosticGasRhs[node]))
				throw blocked("Unrepresentable coupled gas pressure diagnostics at node " + node);
		}
		preparePressureObservation(force);
		solved = true; return new PressureProjectionResult(iterations, residual / unit, true);
	}

	private void preparePressureObservation(double[][] force) {
		Arrays.fill(observedPressure,0);
		double[][] traction = new double[3][basis.blockCount()];
		for (int axis = 0; axis < 3; axis++) for (int marker = 0; marker < basis.markerCount(); marker++)
			traction[axis][basis.markerBlock(marker)] += force[axis][marker] + (knownImpulse[axis][marker] + wallImpulse[axis][marker]) / dt;
		double[] volume = new double[domain.cellCount];
		for (int block = 0; block < basis.blockCount(); block++) if (liquidRows[block] >= 0) {
			int cell=basis.physicalCell(block);
			var profile = blockProfile(block); double value = pressure[liquidRows[block]];
			int x = cell % domain.sizeX, y = cell / domain.sizeX % domain.sizeY, z = cell / (domain.sizeX * domain.sizeY);
			for (int axis = 0; axis < 3; axis++) {
				double center = ((axis == 0 ? x : axis == 1 ? y : z) + .5) * basis.cellSize();
				value -= traction[axis][block] / profile.volume() * (center - profile.centroid().component(axis));
			}
			observedPressure[cell] += value * profile.volume(); volume[cell] += profile.volume();
		}
		for(int cell=0;cell<domain.cellCount;cell++)if(volume[cell]>0)observedPressure[cell]/=volume[cell];
		// The closed-system pressure gauge changes observation, never the solved force.
		double gauge = !hasFreeSurface && pressureAnchor >= 0 && pressureAnchor < liquidRows.length && liquidRows[pressureAnchor] >= 0 ? observedPressure[pressureAnchor] : 0;
		for (int cell = 0; cell < domain.cellCount; cell++) if (volume[cell] > 0) {
			observedPressure[cell] -= gauge;
			if (!Float.isFinite((float) observedPressure[cell])) throw blocked("Unrepresentable resolved cell pressure");
		}
		for (int node = 1; node < gasRows.length; node++) if (gasRows[node] >= 0 && !Float.isFinite((float) pressure[gasRows[node]])) throw blocked("Unrepresentable finite gas pressure");
		double unit = Math.pow(basis.cellSize(), 3) / domain.config.density();
		for (int node = 1; node < gasRows.length; node++) if (gasRows[node] >= 0 && !Double.isFinite(storage[gasRows[node]] / unit)) throw blocked("Unrepresentable finite gas storage diagnostic");
	}

	/** Every physical and diagnostic value has been checked before the first write. */
	void publish() {
		if (!active || !solved) throw new IllegalStateException("Cell pressure has no solved candidate");
		requireCurrent();
		for (int marker = 0; marker < basis.markerCount(); marker++) {
			domain.particles.setVelocityX(marker, (float) publishedVelocity[0][marker]); domain.particles.setVelocityY(marker, (float) publishedVelocity[1][marker]); domain.particles.setVelocityZ(marker, (float) publishedVelocity[2][marker]);
		}
		float[][] mac = {domain.mac.velocityX, domain.mac.velocityY, domain.mac.velocityZ};
		for (int axis = 0; axis < 3; axis++) for (int face = 0; face < mac[axis].length; face++) mac[axis][face] = (float) faceCandidate[axis][face];
		domain.pressureSystem.updateMarkerVelocityField(candidateField);
		domain.projection.fluidUnknownCount = 0;
		for (int cell = 0; cell < domain.cellCount; cell++) if (geometry.cellSnapshot(cell)!=null) {
			domain.projection.pressure[cell] = (float) observedPressure[cell];
			domain.projection.fluidUnknownCells[domain.projection.fluidUnknownCount++] = cell;
		}
		Arrays.fill(domain.projection.gasPressureUnknown, false); Arrays.fill(domain.projection.gasStorageCoefficient, 0);
		System.arraycopy(diagnosticGasDiagonal, 0, domain.projection.gasMatrixDiagonal, 0, gasRows.length);
		System.arraycopy(diagnosticGasRhs, 0, domain.projection.gasRhs, 0, gasRows.length);
		domain.projection.gasUnknownCount = 0;
		double unit = Math.pow(basis.cellSize(), 3) / domain.config.density();
		for (int node = 1; node < gasRows.length; node++) if (gasRows[node] >= 0) {
			domain.projection.gasPressure[node] = (float) pressure[gasRows[node]]; domain.projection.gasPressureUnknown[node] = true;
			domain.projection.gasUnknownNodes[domain.projection.gasUnknownCount++] = node; domain.projection.gasStorageCoefficient[node] = storage[gasRows[node]] / unit;
		}
		solved = false; published = true;
	}

	private void requireCurrent() {
		float[][] current = {domain.mac.velocityX, domain.mac.velocityY, domain.mac.velocityZ};
		float[][] previous = {domain.mac.p2gVelocityX, domain.mac.p2gVelocityY, domain.mac.p2gVelocityZ};
		if (!Arrays.deepEquals(current, originalMacVelocity) || !Arrays.deepEquals(previous, originalP2gVelocity))
			throw new IllegalStateException("Marker pressure input velocity field changed");
		if (carrierRevision != domain.carrierMutationRevision || gasNetworkOwner != domain.gasState.network
				|| gasFrame != domain.gasBoundary.frame() || gasRevision != domain.gasState.network.revision()
				|| thermalOwner != domain.thermalState || thermalOwner != null && !thermalRevision.equals(thermalOwner.thermalRevision())
				|| gridDirty != domain.gridDirty || velocityGridDirty != domain.velocityGridDirty
				|| pressureAnchor != domain.pressureAnchor || pressureReference != domain.pressureReference
				|| basis.markerCount() != domain.particles.count()
				|| !Arrays.equals(geometryMasks, domain.geometry.cutCells.copyMicroMasks())) throw new IllegalStateException("Stale marker pressure candidate");
		if (!Arrays.equals(originalPressureFluid, domain.projection.pressureFluid)) throw new IllegalStateException("Pressure classification changed");
		for (int cell = 0; cell < domain.cellCount; cell++) {
			if (pressurePhaseRevision != domain.pressurePhaseGeometry.revision() || originalFraction[cell] != domain.pressurePhaseGeometry.liquidFractionExact(cell) || originalPlaneOffset[cell] != domain.pressurePhaseGeometry.planeOffset(cell)) throw new IllegalStateException("Pressure spatial profile changed");
			for (int axis = 0; axis < 3; axis++) if (originalPlaneNormal[axis][cell] != domain.pressurePhaseGeometry.planeNormal(cell, axis)) throw new IllegalStateException("Pressure spatial normal changed");
		}
		for (int i = 0; i < basis.markerCount(); i++) if (originalMass[i] != domain.particles.mass(i) || originalVolume[i] != domain.particles.volume(i)
				|| originalPosition[0][i] != domain.particles.positionX(i) || originalPosition[1][i] != domain.particles.positionY(i) || originalPosition[2][i] != domain.particles.positionZ(i)
				|| originalVelocity[0][i] != domain.particles.velocityX(i) || originalVelocity[1][i] != domain.particles.velocityY(i) || originalVelocity[2][i] != domain.particles.velocityZ(i))
			throw new IllegalStateException("Marker pressure source changed at marker " + i);
	}
	boolean active() { return active; }
	boolean published() { return published; }
	double liquidPressure(int cell) { return observedPressure[cell]; }
	int rowOfLiquidBlock(int block) { return liquidRows[block]; }
	int sharedRowStart() { return sharedStart; }
	int sharedRowEnd() { return sharedEnd; }
	double gasPressure(int node) { return gasRows[node] < 0 ? 0 : pressure[gasRows[node]]; }
	CellVelocityConstraintOperator operator() { return operator; }
	CellAffineMacTransfer transfer() { return transfer; }
	CellAffineMacTransfer macTransfer() { return transfer; }
	CellAffineVelocity basis() { return basis; }
	LiquidPressureGeometry geometry() { return geometry; }
	double[][] predictor() { return copy(predictor); }
	/** Unrounded solution; publication fits the one binary32 marker result and applies the same Q. */
	double[][] candidate() { return copy(candidate); }
	double[] pressure() { return pressure.clone(); }
	double[][] knownImpulse() { return copy(knownImpulse); }
	double[][] wallImpulse() { return copy(wallImpulse); }
	double[] targetVolumeRate() { return targetVolumeRate.clone(); }
	double predictorKineticEnergy() { return kinetic(predictor); }
	double candidateKineticEnergy() { return kinetic(publishedVelocity); }
	private double kinetic(double[][] velocity) {
		double result = 0; for (int a = 0; a < 3; a++) for (int i = 0; i < basis.markerCount(); i++) result += (.5 * basis.massKilograms(i) * velocity[a][i]) * velocity[a][i];
		if (!Double.isFinite(result)) throw blocked("Unrepresentable actual marker kinetic energy"); return result;
	}
	private double[][] faces() { return new double[][]{new double[transfer.faceCount(0)], new double[transfer.faceCount(1)], new double[transfer.faceCount(2)]}; }
	private double[][] cellVectors() { return new double[3][4 * basis.blockCount()]; }
	private static double[][] markers(int count) { return new double[3][count]; }
	private static double[][] copy(double[][] value) { return new double[][]{value[0].clone(), value[1].clone(), value[2].clone()}; }
	private static float[][] copy(float[][] value) { return new float[][]{value[0].clone(), value[1].clone(), value[2].clone()}; }
	private static double dot(double[] first, double[] second) { double result = 0; for (int i = 0; i < first.length; i++) result += first[i] * second[i]; return result; }
	private static double rms(double[] value) { double norm = 0; for (double v : value) norm = Math.hypot(norm, v); return norm / Math.sqrt(Math.max(1, value.length)); }
	private static FluidDomain.PressureSolveBlockedException blocked(String message) { return new FluidDomain.PressureSolveBlockedException(message); }
}
