package mmm.fluid;

import java.util.Arrays;

/** Owns pressure geometry preparation, unknown rows, reference loads and result publication.
 * The face graph prepares spatial incidence; the linear solver advances only the supplied rows.
 */
final class PressureSystem {
	private final FluidDomain domain;
	private FlipPressurePhaseGeometry phaseGeometry;
	private boolean geometryDirty=true;
	private final float[] liquidPressure; // Reduced liquid pressure; queries restore hydrostatic and capillary loads.
	private final int[] fluidUnknownCells;
	private int fluidUnknownCount,gasUnknownCount,gasNodeCount;
	private int[] gasUnknownNodes;
	boolean[] gasPressureUnknown;
	private boolean[] activeGasNodes=new boolean[0];
	float[] gasPressure;
	double[] gasStorageCoefficient;
	private final boolean[] anchored;
	private final double[] seedPressureSum,seedPressureWeight;
	private PressureProjectionResult lastResult=new PressureProjectionResult(0,0,true);
	private FlipPressurePhaseGeometry preparedPhase;
	private mmm.granular.GranularCouplingFields preparedSolid;
	private mmm.granular.GranularParcelStore preparedParcels;
	private mmm.granular.GranularDepositStore preparedDeposits;
	private long preparedQuantityRevision=-1,preparedParcelRevision=-1,preparedDepositRevision=-1;
	private final GranularPressureCoupling granular;
	private final GranularImplicitDrag drag;
	private final double[] transportVelocity;
	private final PressureLinearSolver solver;
	final PressureSparseMatrix matrix = new PressureSparseMatrix();
	final GranularFaceVolumes solidFaces;
	final PressureFaceGraph faces;
	private final double[] forceBaseline;
	private boolean forceStepPrepared;
	private double[] pressure = new double[0], referencePressure = new double[0],pressureLoad=new double[0];
	private double[] freeVelocity=new double[0],predictedVelocity=new double[0],jointResponse=new double[0];
	private int jointSize;
	private float duration;
	private double pressureDatum;
	private double[] gasStorageRhs = new double[0];
    private GasRuntimeGeometry mechanicalGasSource;
    private boolean[] mechanicalGasModes=new boolean[0];

	PressureSystem(FluidDomain domain) {
		this.domain = domain;
		liquidPressure=new float[domain.cellCount];fluidUnknownCells=new int[domain.cellCount];
		gasPressureUnknown=new boolean[domain.cellCount+1];gasUnknownNodes=new int[domain.cellCount];
		gasPressure=new float[domain.cellCount+1];gasStorageCoefficient=new double[domain.cellCount+1];
		anchored=new boolean[domain.cellCount];seedPressureSum=new double[domain.cellCount];seedPressureWeight=new double[domain.cellCount];
		solidFaces = new GranularFaceVolumes(domain); faces = new PressureFaceGraph(domain,anchored);
		granular = new GranularPressureCoupling(domain); drag = new GranularImplicitDrag(domain);
		transportVelocity=new double[faces.faceCount];
		forceBaseline=new double[faces.faceCount];
		solver = new PressureLinearSolver(domain.cellCount,domain.config, matrix, this::buildRhs,this::multiplyPressure);
	}

	FlipPressurePhaseGeometry phaseGeometry(){
        if(phaseGeometry==null)phaseGeometry=new FlipPressurePhaseGeometry(domain.sizeX,domain.sizeY,domain.sizeZ);
        return phaseGeometry;
    }
	PressureProjectionResult lastResult(){return lastResult;}
	void invalidateGeometry(){geometryDirty=true;}
	void clearLiquidPressure(int cell){liquidPressure[cell]=0;}
	/** Prepare committed inventory, gas phase geometry, wall supports and capillary fields in order. */
	void prepareGeometry(){
		domain.domainFlowFields.ensureInventoryGrid();
		if(!geometryDirty)return;
		domain.gasTopologyCoordinator.refresh();
		phaseGeometry=domain.gasState.frame.phase();
		faces.classify();
		var parcels=domain.granularState==null?null:domain.granularState.parcels;
		var deposits=domain.granularState==null?null:domain.granularState.deposits;
		long parcelRevision=parcels==null?-1:parcels.mutationRevision(),depositRevision=deposits==null?-1:deposits.mutationRevision();
		if(preparedPhase!=phaseGeometry||preparedQuantityRevision!=domain.cellMacStage.quantityRevision()
				||preparedSolid!=domain.granularCoupling||preparedParcels!=parcels||preparedDeposits!=deposits
				||preparedParcelRevision!=parcelRevision||preparedDepositRevision!=depositRevision){
			solidFaces.rebuild();domain.cellMacStage.rebuildWallSupport();
			preparedPhase=phaseGeometry;preparedQuantityRevision=domain.cellMacStage.quantityRevision();preparedSolid=domain.granularCoupling;
			preparedParcels=parcels;preparedDeposits=deposits;preparedParcelRevision=parcelRevision;preparedDepositRevision=depositRevision;
		}
		domain.tension.rebuildSurfaceTensionPressure();geometryDirty=false;
	}

	/** Prepare geometry, pressure rows and face loads without changing velocity. */
	void prepareOperator(){
		prepareGeometry();
		int nodes=domain.gasState.network.nodeCount();ensureGasCapacity(nodes);
		Arrays.fill(gasPressureUnknown,false);gasUnknownCount=fluidUnknownCount=0;
		Arrays.fill(gasStorageCoefficient,0);Arrays.fill(anchored,false);
		if(activeGasNodes.length<nodes+1)activeGasNodes=new boolean[nodes+1];
		else Arrays.fill(activeGasNodes,false);
		var frame=domain.gasState.frame;
		for(var component:frame.capacity().components())if(domain.activityGroups.mechanicsActive(component.cell())){
			int node=frame.topology().nodeOfComponent(component.cell(),component.supportMask());
			if(node>0)activeGasNodes[node]=true;
		}
		for(int node=1;node<=nodes;node++)if(activeGasNodes[node]){gasPressureUnknown[node]=true;gasUnknownNodes[gasUnknownCount++]=node;}
		faces.rebuild(liquidPressure);
		for (int c = 0; c < domain.cellCount; c++) if (domain.activityGroups.mechanicsActive(c) && domain.domainFlowFields.isFluid(c)) {
			int group = faces.root(c);
			if (!faces.constrained[group] && !anchored[group]) { anchored[group] = true; anchored[c] = true; }
			else fluidUnknownCells[fluidUnknownCount++] = c;
		}
		faces.excludeAnchoredRows();
	}
	private void ensureGasCapacity(int nodes){
		if(nodes<0||nodes>mmm.fluid.air.GasLimits.MAX_NODES_PER_REGION)
			throw new IllegalArgumentException("Gas pressure node count exceeds its representation budget");
		gasNodeCount=nodes;if(nodes<gasPressure.length)return;
		int length=Math.addExact(nodes,1);
		gasPressureUnknown=Arrays.copyOf(gasPressureUnknown,length);gasUnknownNodes=Arrays.copyOf(gasUnknownNodes,nodes);
		gasPressure=Arrays.copyOf(gasPressure,length);gasStorageCoefficient=Arrays.copyOf(gasStorageCoefficient,length);
	}
	/** Closed pressure systems solve increments about the dominant finite cavity's pressure. */
	private double commonPressureDatum() {
		if(!domain.gasState.frame.sealedExterior())return 0;
		// An independently anchored liquid component retains its ambient pressure convention.
		for(boolean value:anchored)if(value)return 0;
		int largest=0;double volume=0;
		for(int node:domain.gasState.network.nodeIds())if(!domain.gasState.network.inventory(node).isEmpty()){
			double available=domain.gasState.network.availableVolume(node);
			if(available>volume){largest=node;volume=available;}
		}
		return largest==0?0:domain.pressureBoundaryView.gasPressureOfNode(largest);
	}
	/** p = datum + dp on every variable row; fixed pressures keep their physical values. */
	private void shiftPressureDatum(double datum) {
		if(datum==0)return;
		for(int face=0;face<faces.faceCount;face++){
			double sum=0;for(int e=faces.start[face];e<faces.start[face+1];e++)if(faces.rowAt(e)>=0)sum+=faces.coefficientAt(e);
			faces.affine[face]+=datum*sum;
		}
	}
	/** Seed each connected full-liquid volume with its contacting finite cavities' mean pressure. */
	private void seedLiquidPressures(double[] pressure){
		Arrays.fill(seedPressureSum,0);Arrays.fill(seedPressureWeight,0);
		for(int face=0;face<faces.faceCount;face++){
			double total=0,value=0;
			for(int i=faces.start[face];i<faces.start[face+1];i++)if(faces.rowAt(i)>=domain.cellCount){
				double weight=Math.abs(faces.coefficientAt(i))*gasStorageCoefficient[faces.rowAt(i)-domain.cellCount];
				total+=weight;value+=weight*pressure[faces.rowAt(i)];
			}
			if(total==0)continue;
			for(int i=faces.start[face];i<faces.start[face+1];i++)if(faces.rowAt(i)>=0&&faces.rowAt(i)<domain.cellCount){
				int group=faces.root(faces.rowAt(i));double weight=Math.abs(faces.coefficientAt(i))*total;
				seedPressureSum[group]+=weight*(value/total);seedPressureWeight[group]+=weight;
			}
		}
		for(int i=0;i<fluidUnknownCount;i++){
			int cell=fluidUnknownCells[i],group=faces.root(cell);
			if(seedPressureWeight[group]>0)pressure[cell]=seedPressureSum[group]/seedPressureWeight[group];
		}
		if(!domain.tension.hasAnySurfaceTension())return;
		Arrays.fill(seedPressureSum,0);Arrays.fill(seedPressureWeight,0);
		for(int face=0;face<faces.faceCount;face++){
			double sum=0,scale=0,liquidWeight=0,interfaceWeight=0;
			for(int i=faces.start[face];i<faces.start[face+1];i++){
				double coefficient=faces.coefficientAt(i),weight=Math.abs(coefficient);
				sum+=coefficient;scale=Math.max(scale,weight);
				if(faces.rowAt(i)>=domain.cellCount)interfaceWeight+=weight;
				else if(faces.rowAt(i)>=0)liquidWeight+=weight;
			}
			// Fixed ambient contact has no variable gas row. Its missing coefficient
			// still contributes the same interface area to the Laplace reference.
			if(Math.abs(sum)>16*Math.ulp(scale))interfaceWeight+=Math.abs(sum);
			if(!(interfaceWeight>0&&liquidWeight>0))continue;
			for(int i=faces.start[face];i<faces.start[face+1];i++)if(faces.rowAt(i)>=0&&faces.rowAt(i)<domain.cellCount){
				int cell=faces.rowAt(i),neighbor=cell==faces.firstCell[face]?faces.secondCell[face]:faces.firstCell[face];
				int surface=neighbor>=0&&domain.liquidGrid.cellStockVolume[neighbor]>0?neighbor:cell;
				int group=faces.root(cell);double weight=Math.abs(faces.coefficientAt(i))*interfaceWeight/liquidWeight;
				seedPressureSum[group]+=weight*domain.tension.surfaceTensionPressure[surface];
				seedPressureWeight[group]+=weight;
			}
		}
		// p_L = pi + J. A constant-curvature interface therefore has the
		// equilibrium pi = J_interface - J_cell, including the deep liquid where
		// curvature is zero. Put that reference through the same C/CT prediction;
		// variable-curvature loads remain in the residual pressure solve.
		for(int i=0;i<fluidUnknownCount;i++){
			int cell=fluidUnknownCells[i],group=faces.root(cell);
			if(seedPressureWeight[group]>0)pressure[cell]+=seedPressureSum[group]/seedPressureWeight[group]
					-domain.tension.surfaceTensionPressure[cell];
		}
	}
	void pressureVelocity(double[] pressure, double[] velocity) {
		for (int f = 0; f < faces.faceCount; f++) {
			double value = 0; for (int i = faces.start[f]; i < faces.start[f + 1]; i++) if (faces.rowAt(i) >= 0) value += faces.coefficientAt(i) * pressure[faces.rowAt(i)];
			velocity[f] = faces.inverseMass[f] * value;
		}
	}
	void addDivergence(double[] velocity, double[] result, double factor) {
		for (int f = 0; f < faces.faceCount; f++) for (int i = faces.start[f]; i < faces.start[f + 1]; i++)
			if (faces.rowAt(i) >= 0) result[faces.rowAt(i)] += factor * faces.coefficientAt(i) * velocity[f];
	}
	private void buildRhs(double[] result) {
        Arrays.fill(result,0);
        addDivergence(predictedVelocity,result,-domain.config.density()/duration);
        granular.addDivergence(predictedVelocity,faces.faceCount,result,-domain.config.density()/duration);
        domain.entityBodyCoupling.addDivergence(result,-domain.config.density()/duration);
        addStorageRhs(result);
    }
    /** The inertia/drag inverse used here is also used by prediction and final velocity publication. */
    void multiplyPressure(double[] values,double[] result){
        if(!drag.active()){matrix.multiply(values,result);return;}
        int rows=domain.cellCount+gasNodeCount+1;
        for(int row=0;row<rows;row++)pressureLoad[row]=matrix.active(row)?values[row]:0;
        pressureVelocity(pressureLoad,jointResponse);granular.pressureVelocity(pressureLoad,jointResponse,faces.faceCount);
        drag.applyResponse(jointResponse,jointResponse);
        Arrays.fill(result,0);
        addDivergence(jointResponse,result,domain.config.density());
        granular.addDivergence(jointResponse,faces.faceCount,result,domain.config.density());
        for(int i=0;i<gasUnknownCount;i++){
            int node=gasUnknownNodes[i],row=domain.cellCount+node;
            result[row]+=gasStorageCoefficient[node]*values[row];
        }
        for(int row=0;row<rows;row++)if(!matrix.active(row))result[row]=0;
    }
    private void preparePrediction(float dt){
        int parcels=domain.granularState==null?0:domain.granularState.parcels.count();
        jointSize=faces.faceCount+3*parcels;
        int rows=domain.cellCount+gasNodeCount+1;if(pressureLoad.length<rows)pressureLoad=new double[rows];
        if(freeVelocity.length<jointSize){
            freeVelocity=new double[jointSize];predictedVelocity=new double[jointSize];jointResponse=new double[jointSize];
        }
        readJointVelocity(freeVelocity);
        for(int f=0;f<faces.faceCount;f++)freeVelocity[f]+=dt*faces.inverseMass[f]*faces.affine[f];
        granular.addAffineVelocity(dt,freeVelocity,faces.faceCount);
        pressureVelocity(referencePressure,jointResponse);granular.pressureVelocity(referencePressure,jointResponse,faces.faceCount);
        for(int i=0;i<jointSize;i++)predictedVelocity[i]=freeVelocity[i]+dt*jointResponse[i];
        drag.applyResponse(predictedVelocity,predictedVelocity);
    }

	void solve(float dt) {
		var result=solveProjection(dt);
		if(!result.converged())throw new FluidDomain.PressureSolveBlockedException("Pressure projection did not converge: residual="
				+result.residualRms()+", iterations="+result.iterations());
	}
	/** The conservative force block exchanges its actual kinetic energy with the source inventory. */
	void beginForceStep(){domain.mechanicalEnergy.beginLiquidForceStep();readVelocity(forceBaseline);forceStepPrepared=true;}
	void beginGranularForceStep(){granular.beginMechanicalStep();}
	void finishGranularForceStep(){granular.finishMechanicalStep();}
	PressureProjectionResult solveProjection(float dt) {
		duration = dt; domain.domainFlowFields.preparePhysicalState();
		if(!forceStepPrepared)beginForceStep();
		prepareOperator();
		pressureDatum=commonPressureDatum();shiftPressureDatum(pressureDatum);
		prepareGasStorage();
		for(int cell=0;cell<domain.cellCount;cell++)if(domain.activityGroups.mechanicsActive(cell))liquidPressure[cell]=0;
		for(int node=1;node<=gasNodeCount;node++)if(activeGasNodes[node])gasPressure[node]=0;
		int rows=domain.cellCount+gasNodeCount+1;
		if(referencePressure.length<rows)referencePressure=new double[rows];
		else Arrays.fill(referencePressure,0);
		for(int i=0;i<gasUnknownCount;i++){
			int node=gasUnknownNodes[i];double existing=domain.pressureBoundaryView.gasPressureOfNode(node);
			referencePressure[domain.cellCount+node]=existing-pressureDatum;
			// Rows without an independent pressure mode still publish their physical EOS state.
			gasPressure[node]=(float)existing;
		}
		seedLiquidPressures(referencePressure);
		granular.prepare(dt,pressureDatum);
        domain.entityBodyCoupling.preparePressure(pressureDatum);
        // Constitutive resistance reads source slip before any known pressure impulse.
        drag.prepare(dt);preparePrediction(dt);
		retainCoupledGasRows();
        mechanicalGasSource=domain.gasState.frame;
        mechanicalGasModes=Arrays.copyOf(gasPressureUnknown,gasNodeCount+1);
		assembleMatrix();solver.reset(rows);
		// Resolve pressure to the same cell-volume scale as the phase geometry.
		// The RHS residual becomes normalized volume through dt²/rho; the RMS
		// factor keeps the resulting maximum row defect within that scale.
		double target=Math.max(domain.config.pressureTolerance(),
				FlipPressurePhaseGeometry.CELL_VOLUME_RESOLUTION*domain.config.density()
				/(dt*(double)dt*Math.sqrt(Math.max(1,fluidUnknownCount+gasUnknownCount))));
		// One semi-implicit pressure prediction. Bounded inventory transport owns
		// the actual pore endpoint; final EOS and compression work use that volume.
		var result = solver.solve(target,pressureDatum,referencePressure,fluidUnknownCells,fluidUnknownCount,gasUnknownNodes,gasUnknownCount);
		pressure = solver.pressure();
		for(int i=0;i<fluidUnknownCount;i++){
			int cell=fluidUnknownCells[i];liquidPressure[cell]=(float)(pressure[cell]+referencePressure[cell]+pressureDatum);
		}
		for(int i=0;i<gasUnknownCount;i++){
			int node=gasUnknownNodes[i];gasPressure[node]=(float)(pressure[domain.cellCount+node]+referencePressure[domain.cellCount+node]+pressureDatum);
		}
		lastResult=result;return result;
	}
	void beginVolumeTransport(){
		readVelocity(transportVelocity);
		for(int f=0;f<faces.faceCount;f++)if(!domain.activityGroups.mechanicsFaceActive(faces.firstCell[f],faces.secondCell[f])||!(faces.faceMass(f)>0))transportVelocity[f]=0;
	}
	double volumeFlux(int face,double dt){
		if(!domain.activityGroups.mechanicsFaceActive(faces.firstCell[face],faces.secondCell[face]))return 0;
		return faces.fluidArea(face)*transportVelocity[face]*dt/domain.config.cellSize();
	}
	private void prepareGasStorage() {
		int count = domain.gasState.network.nodeCount();
		gasStorageRhs = new double[count + 1];
		double factor = domain.config.density()
				/ (duration * (double) duration * domain.unitScale.cubicMetersPerNormalizedVolume());
		for (int i = 0; i < gasUnknownCount; i++) {
			int node = gasUnknownNodes[i];
			if (domain.pressureBoundaryView.gasNodeHasContinuumState(node)) {
				gasStorageCoefficient[node] = factor
						* domain.pressureBoundaryView.gasNodeVolumeCompliance(node,faces.gasPressureLoad[node]);
				// The known pressure equals P_old, so storage responds only to delta P.
				gasStorageRhs[node]=0;
			} else gasStorageRhs[node] = -factor * domain.gasState.network.availableVolume(node);
		}
	}

	void projectVelocities(float dt) {
        int rows=domain.cellCount+gasNodeCount+1;
        if(pressureLoad.length<rows)pressureLoad=new double[rows];
        for(int row=0;row<rows;row++)pressureLoad[row]=referencePressure[row]+pressure[row];
        // Combine the physical pressure before applying R, so a large p0 and its
        // cancelling increment never become an intermediate float velocity pulse.
        pressureVelocity(pressureLoad,jointResponse);granular.pressureVelocity(pressureLoad,jointResponse,faces.faceCount);
        for(int i=0;i<jointSize;i++)predictedVelocity[i]=freeVelocity[i]+dt*jointResponse[i];
        drag.applyResponse(predictedVelocity,predictedVelocity);
        publishVelocity(predictedVelocity);
        domain.entityBodyCoupling.finishPressure(pressureLoad,dt);
        for(int f=0;f<faces.faceCount;f++){
            if(!domain.activityGroups.mechanicsFaceActive(faces.firstCell[f],faces.secondCell[f]))continue;
            int axis=faces.axis[f],local=faces.localFace[f];
            float[] velocity=axis==0?domain.mac.velocityX:axis==1?domain.mac.velocityY:domain.mac.velocityZ;
            double before=forceBaseline[f],after=velocity[local];
            domain.mechanicalEnergy.recordLiquidExchange(f,.5*faces.faceMass(f)*(before*before-after*after));
        }
        forceStepPrepared=false;
    }
    /** A joint column has MAC faces followed by three velocity components per active parcel. */
    void readJointVelocity(double[] target){
        readVelocity(target);int parcels=domain.granularState==null?0:domain.granularState.parcels.count();
        for(int p=0;p<parcels;p++){
            var parcel=domain.granularState.parcels.parcel(p);int index=faces.faceCount+3*p;
            target[index]=parcel.velocityX();target[index+1]=parcel.velocityY();target[index+2]=parcel.velocityZ();
        }
    }
    void publishVelocity(double[] values){
        for(int f=0;f<faces.faceCount;f++){
            if(!domain.cellMacStage.mechanicsFaceActive(faces.axis[f],faces.localFace[f]))continue;
            float[] field=switch(faces.axis[f]){case 0->domain.mac.velocityX;case 1->domain.mac.velocityY;default->domain.mac.velocityZ;};
            field[faces.localFace[f]]=(float)values[f];
        }
        granular.publishVelocity(values,faces.faceCount);
    }

	/** Unresolved dry capacity retains its inventory without an independent mechanical mode. */
	private void retainCoupledGasRows() {
		Arrays.fill(gasPressureUnknown,false);
		for(int face=0;face<faces.faceCount;face++)if(faces.inverseMass[face]>0)
			for(int i=faces.start[face];i<faces.start[face+1];i++)
				if(faces.rowAt(i)>=domain.cellCount&&faces.coefficientAt(i)!=0)
					gasPressureUnknown[faces.rowAt(i)-domain.cellCount]=true;
		granular.markGasPressureContacts(gasPressureUnknown);
        domain.entityBodyCoupling.markGasRows(gasPressureUnknown);
		gasUnknownCount=0;
		for(int node=1;node<=gasNodeCount;node++){
			gasPressureUnknown[node]&=activeGasNodes[node];
			if(gasPressureUnknown[node])gasUnknownNodes[gasUnknownCount++]=node;
		}
	}
    boolean hasGasMechanicalModes(GasRuntimeGeometry source){return source!=null&&source==mechanicalGasSource;}
    /** Modes belong to the old frame on which a pressure step actually acted. */
    boolean sourceGasDoesVolumeWork(GasRuntimeGeometry source,int node){
        if(!hasGasMechanicalModes(source))throw new IllegalStateException("Mechanical gas modes belong to a different source frame");
        return node>0&&node<mechanicalGasModes.length&&mechanicalGasModes[node];
    }

	private void assembleMatrix() {
		matrix.begin(domain.cellCount + gasNodeCount + 1, fluidUnknownCount + gasUnknownCount);
		for (int i = 0; i < fluidUnknownCount; i++) matrix.activate(fluidUnknownCells[i]);
		for (int i = 0; i < gasUnknownCount; i++) matrix.activate(domain.cellCount + gasUnknownNodes[i]);
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
		for (int i = 0; i < gasUnknownCount; i++) {
			int node = gasUnknownNodes[i];
			matrix.add(domain.cellCount + node, domain.cellCount + node, gasStorageCoefficient[node]);
		}
		matrix.finish();
	}
	private void readVelocity(double[] target) {
		float[][] fields = {domain.mac.velocityX, domain.mac.velocityY, domain.mac.velocityZ};
		for (int a = 0; a < 3; a++) for (int i = 0; i < fields[a].length; i++) target[faces.offsets[a] + i] = fields[a][i];
	}
	private void addStorageRhs(double[] result) {
		for (int i = 0; i < gasUnknownCount; i++) {
			int node = gasUnknownNodes[i]; result[domain.cellCount + node] += gasStorageRhs[node];
		}
	}
	/** Conservative absolute-pressure upper bound for a bulk phase scan, without rebuilding wet faces. */
	double liquidPressureUpperBoundPa() {
		domain.domainFlowFields.ensureInventoryGrid();
		// Nonzero capillarity uses the current curvature field, whose height-function stencil
		// can exceed a unit-normal divergence bound. Zero-tension production fluids need no geometry here.
		if (domain.tension.hasAnySurfaceTension()) prepareGeometry();
		double residual = 0.0, density = 0.0, capillary = 0.0, gas = 0.0;
		for (int cell = 0; cell < domain.cellCount; cell++) {
			if (domain.liquidGrid.stockCount[cell] == 0) continue;
			residual = Math.max(residual, liquidPressure[cell]);
			density = Math.max(density, domain.liquidGrid.cellDensity[cell]);
			capillary = Math.max(capillary, Math.abs(domain.tension.surfaceTensionPressure[cell]));
		}
		for (int node : domain.gasState.network.nodeIds())
			gas = Math.max(gas, domain.pressureBoundaryView.gasPressureOfNode(node));
		// Each vertical column is at most the domain height, at the largest current liquid density.
		double head = density * domain.config.gravity() * domain.config.cellSize() * domain.sizeY;
		return mmm.fluid.air.AirNetwork.REFERENCE_PRESSURE + residual + gas + head + capillary;
	}
	/** Normalize every cell to the same physical-pressure reference before private handoff. */
	CondensedPhasePressureField captureRuntimePressure() {
		float[] residual=new float[domain.cellCount];
		if(domain.gasState.frame==null) {
			for(int cell=0;cell<residual.length;cell++)residual[cell]=liquidPressure[cell]+domain.tension.surfaceTensionPressure[cell];
		} else {
			prepareGeometry();
			for(int cell=0;cell<residual.length;cell++) {
				int support=~domain.geometry.cutCells.mask(cell)&255;
				float actual=(float)liquidPressureAt(cell,support);
				residual[cell]=(float)(actual-domain.tension.surfaceTensionPressure[cell]-faces.referencePressure(cell,support));
			}
		}
		return CondensedPhasePressureField.runtime(residual);
	}
	CondensedPhasePressureField captureLiquidPressure(){
		float[] actual=new float[domain.cellCount];
		if(domain.gasState.frame==null){
			// Before the first gas/mechanical preparation, preserve the last physical
			// pressure sample without initializing a topology during snapshot capture.
			for(int cell=0;cell<actual.length;cell++)actual[cell]=liquidPressure[cell]+domain.tension.surfaceTensionPressure[cell];
			return new CondensedPhasePressureField(0,actual);
		}
		prepareGeometry();
		for(int cell=0;cell<actual.length;cell++)actual[cell]=(float)liquidPressureAt(cell,~domain.geometry.cutCells.mask(cell)&255);
		return new CondensedPhasePressureField(0,actual);
	}
	void restoreLiquidPressure(CondensedPhasePressureField state){
		if(state.isRuntimeResidual()) {
			state.copyRuntimeResidualTo(liquidPressure);return;
		}
		if(domain.gasState.frame!=null)prepareGeometry();
		float[] actual=state.copyRelativeGaugePressurePa();
		if(domain.gasState.frame==null){
			for(int cell=0;cell<domain.cellCount;cell++)liquidPressure[cell]=(float)(state.pressureReferencePa()+actual[cell]);
			return;
		}
		for(int cell=0;cell<domain.cellCount;cell++)liquidPressure[cell]=
				(float)(state.pressureReferencePa()+actual[cell]-domain.tension.surfaceTensionPressure[cell]
						-faces.referencePressure(cell,~domain.geometry.cutCells.mask(cell)&255));
	}

	/** Gauge pressure at the retained liquid support, including a partially filled cell's gas contact. */
	double liquidPressureAt(int cell, int support) {
		prepareGeometry();
		if ((faces.pressureSupports[cell] & support) == support)
			return liquidPressure[cell]+domain.tension.surfaceTensionPressure[cell]+faces.referencePressure(cell,support);
		var frame = domain.gasState.frame; double total = 0, weighted = 0;
		for (int micro = 0; micro < 8; micro++) if ((support & 1 << micro) != 0) {
			int node = frame.nodeOfPiece(8 * cell + micro); if (node < 0) continue;
			double volume = frame.geometry().pieceNormalizedVolume(8 * cell + micro);
			total += volume;
			if (node > 0) weighted += volume * domain.pressureBoundaryView.gasPressureOfNode(node);
		}
		return (total > 0 ? weighted / total : liquidPressure[cell])
				+ domain.tension.surfaceTensionPressure[cell] + faces.hydrostaticOffset(cell,support);
	}
}
