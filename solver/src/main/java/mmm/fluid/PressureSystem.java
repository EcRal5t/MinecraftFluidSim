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
	private final double[] velocityWork,forceBaseline;
	private boolean forceStepPrepared;
	private double[] pressure = new double[0], referencePressure = new double[0];
	private float duration;
	private double pressureDatum;
	private double[] gasStorageRhs = new double[0];

	PressureSystem(FluidDomain domain) {
		this.domain = domain;
		solidFaces = new GranularFaceVolumes(domain); faces = new PressureFaceGraph(domain);
		granular = new GranularPressureCoupling(domain); drag = new GranularImplicitDrag(domain);
		transportVelocity=new double[faces.faceCount];
		velocityWork = new double[faces.faceCount];forceBaseline=new double[faces.faceCount];
		solver = new PressureLinearSolver(domain, matrix, this::buildRhs);
	}
	private void buildRhs(double[] result) {
		Arrays.fill(result, 0);
		readVelocity(velocityWork);
		faces.addDivergence(velocityWork, result, -domain.config.density() / duration);
		granular.addDivergence(result, -domain.config.density() / duration);
		addStorageRhs(result);
	}
	void solve(float dt) {
		var result=solveProjection(dt);
		domain.lastPressureIterations=result.iterations();domain.lastPressureResidual=(float)result.residualRms();
		if(!result.converged())throw new FluidDomain.PressureSolveBlockedException("Pressure projection did not converge: residual="
				+result.residualRms()+", iterations="+result.iterations());
	}
	/** The conservative force block exchanges its actual kinetic energy with the source inventory. */
	void beginForceStep(){readVelocity(forceBaseline);forceStepPrepared=true;}
	void beginGranularForceStep(){granular.beginMechanicalStep();}
	void finishGranularForceStep(){granular.finishMechanicalStep();}
	PressureProjectionResult solveProjection(float dt) {
		duration = dt; domain.domainFlowFields.ensureGrid();
		if(!forceStepPrepared)beginForceStep();
		solidFaces.rebuild(); faces.rebuild(dt);
		pressureDatum=faces.commonPressureDatum();faces.shiftPressureDatum(pressureDatum);
		var p = domain.projection;
		prepareGasStorage();
		Arrays.fill(p.pressure, 0); Arrays.fill(p.gasPressure, 0);
		int rows=domain.cellCount+p.gasNodeCount+1;
		if(referencePressure.length<rows)referencePressure=new double[rows];
		else Arrays.fill(referencePressure,0);
		for(int i=0;i<p.gasUnknownCount;i++){
			int node=p.gasUnknownNodes[i];
			referencePressure[domain.cellCount+node]=domain.pressureBoundaryView.gasPressureOfNode(node)-pressureDatum;
		}
		faces.seedLiquidPressures(referencePressure);
		granular.prepare(dt,pressureDatum);
		granular.project(dt,referencePressure);
		// Known cavity pressure and fixed interface loads share one prediction in
		// both phases. Drag sees that complete prediction before the residual solve.
		faces.pressureVelocity(referencePressure,velocityWork);
		for(int f=0;f<faces.faceCount;f++){
			int axis=faces.axis[f],local=faces.localFace[f];
			float[] velocity=axis==0?domain.mac.velocityX:axis==1?domain.mac.velocityY:domain.mac.velocityZ;
			velocity[local]+=dt*(faces.inverseMass[f]*faces.affine[f]+velocityWork[f]);
		}
		if(domain.granularState!=null&&domain.granularState.parcels.count()>0){drag.prepare(dt);drag.project();}
		assembleMatrix();solver.reset();
		// Resolve pressure to the same cell-volume scale as the phase geometry.
		// The RHS residual becomes normalized volume through dt²/rho; the RMS
		// factor keeps the resulting maximum row defect within that scale.
		double target=Math.max(domain.config.pressureTolerance(),
				FlipPressurePhaseGeometry.CELL_VOLUME_RESOLUTION*domain.config.density()
				/(dt*(double)dt*Math.sqrt(Math.max(1,p.fluidUnknownCount+p.gasUnknownCount))));
		// One semi-implicit pressure prediction. Bounded inventory transport owns
		// the actual pore endpoint; final EOS and compression work use that volume.
		var result = solver.solve(target,pressureDatum,referencePressure);
		pressure = solver.pressure();
		return result;
	}
	void beginVolumeTransport(){
		readVelocity(transportVelocity);
		for(int f=0;f<faces.faceCount;f++)if(!(faces.faceMass(f)>0))transportVelocity[f]=0;
	}
	double volumeFlux(int face,double dt){return faces.fluidArea(face)*transportVelocity[face]*dt/domain.config.cellSize();}
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
				// The known pressure equals P_old, so storage responds only to delta P.
				gasStorageRhs[node]=0;
			} else gasStorageRhs[node] = -factor * domain.gasState.network.availableVolume(node);
		}
	}

	void projectVelocities(float dt) {
		faces.pressureVelocity(pressure, velocityWork);
		for (int f = 0; f < faces.faceCount; f++) {
			int a = faces.axis[f], local = faces.localFace[f];
			float[] velocity = a == 0 ? domain.mac.velocityX : a == 1 ? domain.mac.velocityY : domain.mac.velocityZ;
			velocity[local]+=dt*velocityWork[f];
			double before=forceBaseline[f],after=velocity[local];
			double exchange=.5*faces.faceMass(f)*(before*before-after*after);
			if(exchange!=0)domain.cellMacStage.addMechanicalHeatAtFace(a,local,exchange);
		}
		granular.project(dt,pressure);forceStepPrepared=false;
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
	/** Conservative absolute-pressure upper bound for a bulk phase scan, without rebuilding wet faces. */
	double liquidPressureUpperBoundPa() {
		domain.domainFlowFields.ensureInventoryGrid();
		// Nonzero capillarity uses the current curvature field, whose height-function stencil
		// can exceed a unit-normal divergence bound. Zero-tension production fluids need no geometry here.
		if (domain.surfaceTensionActive) domain.domainFlowFields.ensurePressureGeometry();
		double residual = 0.0, density = 0.0, capillary = 0.0, gas = 0.0;
		for (int cell = 0; cell < domain.cellCount; cell++) {
			if (domain.liquidGrid.stockCount[cell] == 0) continue;
			residual = Math.max(residual, domain.projection.pressure[cell]);
			density = Math.max(density, domain.liquidGrid.cellDensity[cell]);
			capillary = Math.max(capillary, Math.abs(domain.tension.surfaceTensionPressure[cell]));
		}
		for (int node : domain.gasState.network.nodeIds())
			gas = Math.max(gas, domain.pressureBoundaryView.gasPressureOfNode(node));
		// Each vertical column is at most the domain height, at the largest current liquid density.
		double head = density * domain.config.gravity() * domain.config.cellSize() * domain.sizeY;
		return mmm.fluid.air.AirNetwork.REFERENCE_PRESSURE + residual + gas + head + capillary;
	}
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
		float[] actual=state.copyRelativeGaugePressurePa();
		if(domain.gasState.frame==null){
			for(int cell=0;cell<domain.cellCount;cell++)domain.projection.pressure[cell]=(float)(state.pressureReferencePa()+actual[cell]);
			return;
		}
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
