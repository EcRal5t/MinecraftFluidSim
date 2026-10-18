package mmm.fluid;

import java.util.Arrays;

/** One capacity/gas pressure operator with the same face inertia and explicit granular couplings. */
final class PressureSystem {
	private final FluidDomain domain;
	private final GranularPressureCoupling granular;
	private final GranularImplicitDrag drag;
	private final double[] transportVelocity;
	private final PressureLinearSolver solver;
	private final PressureLinearSolver.Operator linearOperator;
	final GranularFaceVolumes solidFaces;
	final PressureFaceGraph faces;
	private final double[] velocityWork;
	private double[] pressure = new double[0];
	private float duration;
	private PressureBoundaryView.Compression[] gasCompression = new PressureBoundaryView.Compression[0];
	private double[] gasStorageRhs = new double[0];
	private double[] point = new double[0], trial = new double[0], nonlinearProduct = new double[0], nonlinearRhs = new double[0];
	private double liquidResidual, gasVolumeResidual;

	PressureSystem(FluidDomain domain) {
		this.domain = domain;
		solidFaces = new GranularFaceVolumes(domain); faces = new PressureFaceGraph(domain);
		granular = new GranularPressureCoupling(domain); drag = new GranularImplicitDrag(domain);
		transportVelocity=new double[faces.faceCount];
		velocityWork = new double[faces.faceCount];
		linearOperator = new PressureLinearSolver.Operator() {
			@Override public void buildRhs(double[] result) {
				Arrays.fill(result, 0); readVelocity(velocityWork);
				faces.addDivergence(velocityWork, result, -domain.config.density() / duration);
				var p = domain.projection;
				for (int i = 0; i < p.fluidUnknownCount; i++) { int cell = p.fluidUnknownCells[i]; result[cell] += p.rhs[cell]; }
				for (int i = 0; i < p.gasUnknownCount; i++) { int node = p.gasUnknownNodes[i];
					result[domain.cellCount + node] += p.gasRhs[node]; }
				addStorageRhs(result, duration);
			}
			@Override public void applyMatrix(double[] source, double[] result) {
				Arrays.fill(result, 0); faces.pressureVelocity(source, velocityWork);
				faces.addDivergence(velocityWork, result, domain.config.density());
				var p = domain.projection;
				for (int i = 0; i < p.gasUnknownCount; i++) { int node = p.gasUnknownNodes[i]; p.gasMatrixInput[node] = source[domain.cellCount + node]; }
				for (int i = 0; i < p.gasUnknownCount; i++) { int node = p.gasUnknownNodes[i];
					result[domain.cellCount + node] += p.gasStorageCoefficient[node] * source[domain.cellCount + node]; }
			}
		};
		solver = new PressureLinearSolver(domain, linearOperator);
	}
	PressureProjectionResult solveProjection(float dt) {
		duration = dt; domain.ensureGrid(); solidFaces.rebuild(); faces.rebuild(dt);
		var p = domain.projection; Arrays.fill(p.rhs, 0); Arrays.fill(p.gasRhs, 0);
		Arrays.fill(p.pressure, 0); Arrays.fill(p.gasPressure, 0); Arrays.fill(p.divergence, 0);
		prepareGasCompression();
		granular.prepare(dt); prepareEndpointRhs(dt);
		// Fixed interface loads and the paired parcel reaction join the same prediction.
		for (int f = 0; f < faces.faceCount; f++) {
			int a = faces.axis[f], local = faces.localFace[f];
			float[] velocity = a == 0 ? domain.mac.velocityX : a == 1 ? domain.mac.velocityY : domain.mac.velocityZ;
			velocity[local] += dt * faces.inverseMass[f] * faces.affine[f];
		}
		buildDiagonal(); solver.reset();
		pressure = solver.pressure();
		int size = pressure.length;
		point = new double[size]; trial = new double[size]; nonlinearProduct = new double[size]; nonlinearRhs = new double[size];
		for (int i = 0; i < p.gasUnknownCount; i++) {
			int node = p.gasUnknownNodes[i];
			pressure[domain.cellCount + node] = gasCompression[node]==null ? -mmm.fluid.air.AirNetwork.REFERENCE_PRESSURE
					:gasCompression[node].pressure()-mmm.fluid.air.AirNetwork.REFERENCE_PRESSURE;
		}
		// Resolve pressure to the same cell-volume scale as the phase geometry.
		// The RHS residual becomes normalized volume through dt²/rho; the RMS
		// factor keeps the resulting maximum row defect within that scale.
		double target=Math.max(domain.config.pressureTolerance(),
				FlipPressurePhaseGeometry.CELL_VOLUME_RESOLUTION*domain.config.density()
				/(dt*(double)dt*Math.sqrt(Math.max(1,p.fluidUnknownCount+p.gasUnknownCount))));
		int total = 0; double residual = Double.POSITIVE_INFINITY;
		for (int iteration = 0; iteration < 8; iteration++) {
			System.arraycopy(pressure, 0, point, 0, size);
			linearizeGas(point); buildDiagonal();
			PressureLinearSolver.Operator operator = linearOperator;
			double before = nonlinearResidual(operator, point);
			var result = solver.solve(target);
			total += result.iterations();
			pressure = solver.pressure();
			if (!result.converged()) { publishPressure(); return new PressureProjectionResult(total, result.residualRms(), false); }
			System.arraycopy(pressure, 0, trial, 0, size);
			double scale = 1;
			for (int i = 0; i < p.gasUnknownCount; i++) {
				int node=p.gasUnknownNodes[i];if(gasCompression[node]==null)continue;
				int row = domain.cellCount + node;
				double absolute = point[row] + mmm.fluid.air.AirNetwork.REFERENCE_PRESSURE;
				if (trial[row] + mmm.fluid.air.AirNetwork.REFERENCE_PRESSURE <= 0)
					scale = Math.min(scale, .8 * absolute / (point[row] - trial[row]));
			}
			for (int search = 0; search < 12; search++) {
				for (int row = 0; row < size; row++) pressure[row] = point[row] + scale * (trial[row] - point[row]);
				residual = nonlinearResidual(operator, pressure);
				if (residual <= Math.max(target, 2 * result.residualRms()) || residual < before) break;
				scale *= .5;
			}
			if (Boolean.getBoolean("mmm.pressure.trace")) System.out.printf("GAS_NEWTON i=%d before=%g after=%g liquid=%g gasVolume=%g linear=%g scale=%g%n",
					iteration, before, residual, liquidResidual, gasVolumeResidual, result.residualRms(), scale);
			if (liquidResidual <= Math.max(target, 2 * result.residualRms())
					&& gasVolumeResidual <= FlipPressurePhaseGeometry.CELL_VOLUME_RESOLUTION) {
				publishPressure(); return new PressureProjectionResult(total, residual, true);
			}
		}
		publishPressure();
		return new PressureProjectionResult(total, residual, false);
	}
	void predictDrag(float dt){
		if(domain.granularState==null||domain.granularState.parcels.count()==0)return;
		domain.ensureGrid();faces.rebuild(dt);drag.prepare(dt);drag.project();
	}
	private void prepareEndpointRhs(float dt){
		if(!domain.granularMechanicalStage.collisionPending())return;
		double scale=domain.config.density()/(dt*(double)dt);
		for(int cell=0;cell<domain.cellCount;cell++){
			double change=Math.scalb((double)(domain.granularNextCoupling.solidSvq(cell)-domain.granularCoupling.solidSvq(cell)),-24);
			if(change==0)continue;final double source=scale*change;
			faces.forEachCell(cell,(row,weight,fixed)->{if(row>=0){if(row<domain.cellCount)domain.projection.rhs[row]+=source*weight;else domain.projection.gasRhs[row-domain.cellCount]+=source*weight;}});
		}
	}
	void beginVolumeTransport(){
		readVelocity(transportVelocity);
		for(int f=0;f<faces.faceCount;f++)if(!(faces.faceMass(f)>0))transportVelocity[f]=0;
	}
	double volumeFlux(int face,double dt){return faces.fluidArea(face)*transportVelocity[face]*dt/domain.config.cellSize();}
	double transportVelocity(int face){return transportVelocity[face];}
	private void prepareGasCompression() {
		int count = domain.gasState.network.nodeCount();
		gasCompression = new PressureBoundaryView.Compression[count + 1];
		gasStorageRhs = new double[count + 1];
		for (int i = 0; i < domain.projection.gasUnknownCount; i++) {
			int node = domain.projection.gasUnknownNodes[i];
			if(domain.pressureBoundaryView.gasNodeHasContinuumState(node))gasCompression[node] = domain.pressureBoundaryView.prepareCompression(node);
		}
	}
	private double gasFactor() {
		return domain.config.density() / (duration * (double) duration * domain.unitScale.cubicMetersPerNormalizedVolume());
	}
	private void linearizeGas(double[] point) {
		var p = domain.projection; double factor = gasFactor();
		for (int i = 0; i < p.gasUnknownCount; i++) {
			int node = p.gasUnknownNodes[i]; double gauge = point[domain.cellCount + node];
			if(gasCompression[node]==null){p.gasStorageCoefficient[node]=0;gasStorageRhs[node]=-factor*domain.gasState.network.availableVolume(node);continue;}
			var state = gasCompression[node].atPressure(gauge + mmm.fluid.air.AirNetwork.REFERENCE_PRESSURE);
			p.gasStorageCoefficient[node] = factor * state.compliance();
			gasStorageRhs[node] = factor * (state.volume() - gasCompression[node].volume() + state.compliance() * gauge);
		}
	}
	private double nonlinearResidual(PressureLinearSolver.Operator operator, double[] source) {
		operator.applyMatrix(source, nonlinearProduct); operator.buildRhs(nonlinearRhs);
		var p = domain.projection; double norm = 0, factor = gasFactor();
		for (int i = 0; i < p.fluidUnknownCount; i++) {
			int row = p.fluidUnknownCells[i]; norm = Math.hypot(norm, nonlinearProduct[row] - nonlinearRhs[row]);
		}
		double liquidNorm=norm;int equations=p.fluidUnknownCount;
		gasVolumeResidual = 0;
		for (int i = 0; i < p.gasUnknownCount; i++) {
			int node = p.gasUnknownNodes[i], row = domain.cellCount + node;
			if(gasCompression[node]==null) {
				double residual=nonlinearProduct[row]-nonlinearRhs[row];
				if(source[row]<=-mmm.fluid.air.AirNetwork.REFERENCE_PRESSURE)residual=Math.min(0,residual);
				if(source[row]>-mmm.fluid.air.AirNetwork.REFERENCE_PRESSURE||residual<0)equations++;
				norm=Math.hypot(norm,residual);
				gasVolumeResidual=Math.max(gasVolumeResidual,Math.abs(residual)*duration*duration/domain.config.density());
				continue;
			}
			equations++;
			var state = gasCompression[node].atPressure(source[row] + mmm.fluid.air.AirNetwork.REFERENCE_PRESSURE);
			double residual = nonlinearProduct[row] - nonlinearRhs[row] - p.gasStorageCoefficient[node] * source[row]
					+ gasStorageRhs[node] + factor * (gasCompression[node].volume() - state.volume());
			norm = Math.hypot(norm, residual);
			gasVolumeResidual = Math.max(gasVolumeResidual, Math.abs(residual) * duration * duration / domain.config.density());
		}
		liquidResidual=liquidNorm/Math.sqrt(Math.max(1,equations));
		return norm / Math.sqrt(Math.max(1,equations));
	}

	void projectVelocities(float dt) {
		faces.pressureVelocity(pressure, velocityWork);
		for (int f = 0; f < faces.faceCount; f++) {
			int a = faces.axis[f], local = faces.localFace[f];
			float[] velocity = a == 0 ? domain.mac.velocityX : a == 1 ? domain.mac.velocityY : domain.mac.velocityZ;
			velocity[local] += dt * velocityWork[f];
		}
		var p = domain.projection;
		for (int i = 0; i < p.gasUnknownCount; i++) { int node = p.gasUnknownNodes[i]; p.gasMatrixInput[node] = pressure[domain.cellCount + node]; }
		granular.project(dt, pressure, p.gasMatrixInput);
	}
	private void publishPressure() {
		var p = domain.projection;
		for (int i = 0; i < p.fluidUnknownCount; i++) { int cell = p.fluidUnknownCells[i]; p.pressure[cell] = (float) pressure[cell]; }
		for (int i = 0; i < p.gasUnknownCount; i++) { int node = p.gasUnknownNodes[i]; p.gasPressure[node] = (float) pressure[domain.cellCount + node]; }
	}
	private void buildDiagonal() {
		var p = domain.projection; double h2 = domain.config.cellSize() * (double) domain.config.cellSize();
		double[][] neighbors = {p.matrixNegativeX, p.matrixPositiveX, p.matrixNegativeY, p.matrixPositiveY, p.matrixNegativeZ, p.matrixPositiveZ};
		for (var column : neighbors) Arrays.fill(column, 0); Arrays.fill(p.matrixDiagonal, 0);
		System.arraycopy(p.gasStorageCoefficient, 0, p.gasMatrixDiagonal, 0, p.gasStorageCoefficient.length);
		for (int f = 0; f < faces.faceCount; f++) {
			double response = domain.config.density() * faces.inverseMass[f];
			for (int i = faces.start[f]; i < faces.start[f + 1]; i++) {
				int row = faces.rowAt(i); if (row < 0) continue;
				double c = faces.coefficientAt(i), diagonal = response * c * c;
				if (row < domain.cellCount) p.matrixDiagonal[row] += diagonal * h2;
				else p.gasMatrixDiagonal[row - domain.cellCount] += diagonal;
				if (row >= domain.cellCount) continue;
				for (int j = faces.start[f]; j < faces.start[f + 1]; j++) {
					int other = faces.rowAt(j); if (other < 0 || other >= domain.cellCount || other == row) continue;
					int direction=-1,delta=other-row;
					if(Math.abs(delta)==1 && other/domain.sizeX==row/domain.sizeX)direction=delta>0?1:0;
					else if(Math.abs(delta)==domain.sizeX && other/(domain.sizeX*domain.sizeY)==row/(domain.sizeX*domain.sizeY))direction=delta>0?3:2;
					else if(Math.abs(delta)==domain.sizeX*domain.sizeY)direction=delta>0?5:4;
					if(direction>=0)neighbors[direction][row] -= response * c * faces.coefficientAt(j) * h2;
				}
			}
		}
	}
	private void readVelocity(double[] target) {
		float[][] fields = {domain.mac.velocityX, domain.mac.velocityY, domain.mac.velocityZ};
		for (int a = 0; a < 3; a++) for (int i = 0; i < fields[a].length; i++) target[faces.offsets[a] + i] = fields[a][i];
	}
	void addStorageRhs(double[] result, double dt) {
		var p = domain.projection;
		for (int i = 0; i < p.gasUnknownCount; i++) {
			int node = p.gasUnknownNodes[i]; result[domain.cellCount + node] += gasStorageRhs[node];
		}
	}
	double wallBoundaryWork() { return granular.wallWork(); }
	boolean hasLiquidPressure(int cell) { return domain.isFluid(cell); }
	double liquidPressure(int cell) { return pressure.length > cell ? pressure[cell] : 0; }
	double gasPressure(int node) { return pressure.length > domain.cellCount + node ? pressure[domain.cellCount + node] : domain.pressureBoundaryView.gasPressureOfNode(node); }
    double predictedGasVolumeNormalized(int node) {
        double volume=node<gasCompression.length&&gasCompression[node]!=null
                ?gasCompression[node].atPressure(gasPressure(node)+mmm.fluid.air.AirNetwork.REFERENCE_PRESSURE).volume()
                :domain.gasState.network.availableVolume(node);
        return volume/domain.unitScale.cubicMetersPerNormalizedVolume();
    }

	/** Gauge pressure at the retained liquid support, including a partially filled cell's gas contact. */
	double liquidPressureAt(int cell, int support) {
		domain.ensurePressureGeometry();
		if ((faces.fullSupports[cell] & support) == support) return domain.projection.pressure[cell];
		var frame = domain.gasState.frame; double total = 0, weighted = 0;
		for (int micro = 0; micro < 8; micro++) if ((support & 1 << micro) != 0) {
			int node = frame.nodeOfPiece(8 * cell + micro); if (node < 0) continue;
			double volume = frame.geometry().piece(8 * cell + micro).normalizedVolume();
			total += volume;
			if (node > 0) weighted += volume * domain.pressureBoundaryView.gasPressureOfNode(node);
		}
		return (total > 0 ? weighted / total : domain.projection.pressure[cell])
				+ domain.tension.surfaceTensionPressure[cell] + faces.hydrostaticOffset(cell);
	}
}
