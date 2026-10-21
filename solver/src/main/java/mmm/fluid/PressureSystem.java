package mmm.fluid;

import java.util.Arrays;

/** One capacity/gas pressure operator with the same face inertia and explicit granular couplings. */
final class PressureSystem {
	private final FluidDomain domain;
	private final GranularPressureCoupling granular;
	private final GranularImplicitDrag drag;
	private final double[] transportVelocity;
	private final PressureLinearSolver solver;
	final PressureSparseMatrix matrix = new PressureSparseMatrix();
	final GranularFaceVolumes solidFaces;
	final PressureFaceGraph faces;
	private final double[] velocityWork;
	private double[] pressure = new double[0];
	private float duration;
	private double[] gasStorageRhs = new double[0];

	PressureSystem(FluidDomain domain) {
		this.domain = domain;
		solidFaces = new GranularFaceVolumes(domain); faces = new PressureFaceGraph(domain);
		granular = new GranularPressureCoupling(domain); drag = new GranularImplicitDrag(domain);
		transportVelocity=new double[faces.faceCount];
		velocityWork = new double[faces.faceCount];
		solver = new PressureLinearSolver(domain, matrix, this::buildRhs);
	}
	private void buildRhs(double[] result) {
		Arrays.fill(result, 0);
		readVelocity(velocityWork);
		faces.addDivergence(velocityWork, result, -domain.config.density() / duration);
		granular.addDivergence(result, -domain.config.density() / duration);
		addStorageRhs(result);
	}
	PressureProjectionResult solveProjection(float dt) {
		duration = dt; domain.domainFlowFields.ensureGrid(); solidFaces.rebuild(); faces.rebuild(dt);
		var p = domain.projection;
		prepareGasStorage();
		Arrays.fill(p.pressure, 0); Arrays.fill(p.gasPressure, 0);
		granular.prepare(dt);
		// Fixed interface loads and the paired parcel reaction join the same prediction.
		for (int f = 0; f < faces.faceCount; f++) {
			int a = faces.axis[f], local = faces.localFace[f];
			float[] velocity = a == 0 ? domain.mac.velocityX : a == 1 ? domain.mac.velocityY : domain.mac.velocityZ;
			velocity[local] += dt * faces.inverseMass[f] * faces.affine[f];
		}
		assembleMatrix();
		solver.reset();
		pressure = solver.pressure();
		for (int i = 0; i < p.gasUnknownCount; i++) {
			int node = p.gasUnknownNodes[i];
			pressure[domain.cellCount + node] = domain.pressureBoundaryView.gasPressureOfNode(node);
		}
		faces.seedLiquidPressures(pressure);
		// Resolve pressure to the same cell-volume scale as the phase geometry.
		// The RHS residual becomes normalized volume through dt²/rho; the RMS
		// factor keeps the resulting maximum row defect within that scale.
		double target=Math.max(domain.config.pressureTolerance(),
				FlipPressurePhaseGeometry.CELL_VOLUME_RESOLUTION*domain.config.density()
				/(dt*(double)dt*Math.sqrt(Math.max(1,p.fluidUnknownCount+p.gasUnknownCount))));
		// One semi-implicit pressure prediction. Bounded inventory transport owns
		// the actual pore endpoint; final EOS and compression work use that volume.
		var result = solver.solve(target);
		pressure = solver.pressure();
		return result;
	}
	void predictDrag(float dt){
		if(domain.granularState==null||domain.granularState.parcels.count()==0)return;
		domain.domainFlowFields.ensureGrid();faces.rebuild(dt);drag.prepare(dt);drag.project();
	}
	void beginVolumeTransport(){
		readVelocity(transportVelocity);
		for(int f=0;f<faces.faceCount;f++)if(!(faces.faceMass(f)>0))transportVelocity[f]=0;
	}
	double volumeFlux(int face,double dt){return faces.fluidArea(face)*transportVelocity[face]*dt/domain.config.cellSize();}
	double transportVelocity(int face){return transportVelocity[face];}
	private void prepareGasStorage() {
		int count = domain.gasState.network.nodeCount();
		gasStorageRhs = new double[count + 1];
		double factor = domain.config.density()
				/ (duration * (double) duration * domain.unitScale.cubicMetersPerNormalizedVolume());
		for (int i = 0; i < domain.projection.gasUnknownCount; i++) {
			int node = domain.projection.gasUnknownNodes[i];
			if (domain.pressureBoundaryView.gasNodeHasContinuumState(node)) {
				domain.projection.gasStorageCoefficient[node] = factor
						* domain.pressureBoundaryView.gasNodeVolumeCompliance(node,faces.gasPressureLoad[node]);
				// V(P) = V_old - C_secant * (P - P_old). Both endpoints
				// of the load interval lie on the positive-volume EOS curve.
				gasStorageRhs[node] = domain.projection.gasStorageCoefficient[node]
						* domain.pressureBoundaryView.gasPressureOfNode(node);
			} else gasStorageRhs[node] = -factor * domain.gasState.network.availableVolume(node);
		}
	}

	void projectVelocities(float dt) {
		faces.pressureVelocity(pressure, velocityWork);
		for (int f = 0; f < faces.faceCount; f++) {
			int a = faces.axis[f], local = faces.localFace[f];
			float[] velocity = a == 0 ? domain.mac.velocityX : a == 1 ? domain.mac.velocityY : domain.mac.velocityZ;
			velocity[local] += dt * velocityWork[f];
		}
		granular.project(dt,pressure);
	}
	private void assembleMatrix() {
		var p = domain.projection;
		matrix.begin(domain.cellCount + p.gasNodeCount + 1, p.fluidUnknownCount + p.gasUnknownCount);
		for (int i = 0; i < p.fluidUnknownCount; i++) matrix.activate(p.fluidUnknownCells[i]);
		for (int i = 0; i < p.gasUnknownCount; i++) matrix.activate(domain.cellCount + p.gasUnknownNodes[i]);
		for (int f = 0; f < faces.faceCount; f++) {
			double response = domain.config.density() * faces.inverseMass[f];
			if (response == 0) continue;
			for (int i = faces.start[f]; i < faces.start[f + 1]; i++) {
				int row = faces.rowAt(i);
				if (!matrix.active(row)) continue;
				double weight = response * faces.coefficientAt(i);
				for (int j = faces.start[f]; j < faces.start[f + 1]; j++) {
					matrix.add(row, faces.rowAt(j), weight * faces.coefficientAt(j));
				}
			}
		}
		granular.assembleMatrix(matrix, domain.config.density());
		for (int i = 0; i < p.gasUnknownCount; i++) {
			int node = p.gasUnknownNodes[i];
			matrix.add(domain.cellCount + node, domain.cellCount + node, p.gasStorageCoefficient[node]);
		}
		matrix.finish();
	}
	private void readVelocity(double[] target) {
		float[][] fields = {domain.mac.velocityX, domain.mac.velocityY, domain.mac.velocityZ};
		for (int a = 0; a < 3; a++) for (int i = 0; i < fields[a].length; i++) target[faces.offsets[a] + i] = fields[a][i];
	}
	private void addStorageRhs(double[] result) {
		var p = domain.projection;
		for (int i = 0; i < p.gasUnknownCount; i++) {
			int node = p.gasUnknownNodes[i]; result[domain.cellCount + node] += gasStorageRhs[node];
		}
	}
	double gasPressure(int node) { return pressure.length > domain.cellCount + node ? pressure[domain.cellCount + node] : domain.pressureBoundaryView.gasPressureOfNode(node); }
	CondensedPhasePressureField captureLiquidPressure(){
		float[] actual=new float[domain.cellCount];
		if(domain.gasState.frame==null){
			// Before the first gas/mechanical preparation, preserve the last physical
			// pressure sample without initializing a topology during snapshot capture.
			for(int cell=0;cell<actual.length;cell++)actual[cell]=domain.projection.pressure[cell]+domain.tension.surfaceTensionPressure[cell];
			return new CondensedPhasePressureField(0,actual);
		}
		domain.domainFlowFields.ensurePressureGeometry();
		for(int cell=0;cell<actual.length;cell++)actual[cell]=(float)liquidPressureAt(cell,~domain.geometry.cutCells.mask(cell)&255);
		return new CondensedPhasePressureField(0,actual);
	}
	void restoreLiquidPressure(CondensedPhasePressureField state){
		float[] actual=state.copyRelativeGaugePressurePa();domain.pressureReference=0;
		for(int cell=0;cell<domain.cellCount;cell++)domain.projection.pressure[cell]=
				(float)(state.pressureReferencePa()+actual[cell]-domain.tension.surfaceTensionPressure[cell]
						-faces.referencePressure(cell,~domain.geometry.cutCells.mask(cell)&255));
	}

	/** Gauge pressure at the retained liquid support, including a partially filled cell's gas contact. */
	double liquidPressureAt(int cell, int support) {
		domain.domainFlowFields.ensurePressureGeometry();
		if ((faces.pressureSupports[cell] & support) == support)
			return domain.projection.pressure[cell]+domain.tension.surfaceTensionPressure[cell]+faces.referencePressure(cell,support);
		var frame = domain.gasState.frame; double total = 0, weighted = 0;
		for (int micro = 0; micro < 8; micro++) if ((support & 1 << micro) != 0) {
			int node = frame.nodeOfPiece(8 * cell + micro); if (node < 0) continue;
			double volume = frame.geometry().pieceNormalizedVolume(8 * cell + micro);
			total += volume;
			if (node > 0) weighted += volume * domain.pressureBoundaryView.gasPressureOfNode(node);
		}
		return (total > 0 ? weighted / total : domain.projection.pressure[cell])
				+ domain.tension.surfaceTensionPressure[cell] + faces.hydrostaticOffset(cell,support);
	}
}
