package mmm.fluid;

import java.util.Arrays;

/** Prepares geometry and reconstructs retained liquid pressure for host operations. */
final class PressureSystem {
	private final HostFluidState domain;
	private FlipPressurePhaseGeometry phaseGeometry;
	private boolean geometryDirty=true;
	private final float[] liquidPressure; // Reduced liquid pressure; queries restore hydrostatic and capillary loads.

	boolean[] gasPressureUnknown;

	private final boolean[] anchored;

	private FlipPressurePhaseGeometry preparedPhase;
	private mmm.granular.GranularCouplingFields preparedSolid;
	private mmm.granular.GranularParcelStore preparedParcels;
	private mmm.granular.GranularDepositStore preparedDeposits;
	private long preparedQuantityRevision=-1,preparedParcelRevision=-1,preparedDepositRevision=-1;

	private final double[] transportVelocity;

	final GranularFaceVolumes solidFaces;
	final PressureFaceGraph faces;

	PressureSystem(HostFluidState domain) {
		this.domain = domain;
		liquidPressure=new float[domain.cellCount];
		gasPressureUnknown=new boolean[domain.cellCount+1];
		anchored=new boolean[domain.cellCount];
		solidFaces = new GranularFaceVolumes(domain); faces = new PressureFaceGraph(domain,anchored);
		transportVelocity=new double[faces.faceCount];
	}

	FlipPressurePhaseGeometry phaseGeometry(){
        if(phaseGeometry==null)phaseGeometry=new FlipPressurePhaseGeometry(domain.sizeX,domain.sizeY,domain.sizeZ);
        return phaseGeometry;
    }

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

	void beginVolumeTransport(){
		readVelocity(transportVelocity);
		for(int f=0;f<faces.faceCount;f++)if(!domain.activityGroups.mechanicsFaceActive(faces.firstCell[f],faces.secondCell[f])||!(faces.faceMass(f)>0))transportVelocity[f]=0;
	}
	double volumeFlux(int face,double dt){
		if(!domain.activityGroups.mechanicsFaceActive(faces.firstCell[face],faces.secondCell[face]))return 0;
		return faces.fluidArea(face)*transportVelocity[face]*dt/domain.config.cellSize();
	}
	private void readVelocity(double[] target) {
		float[][] fields = {domain.mac.velocityX, domain.mac.velocityY, domain.mac.velocityZ};
		for (int a = 0; a < 3; a++) for (int i = 0; i < fields[a].length; i++) target[faces.offsets[a] + i] = fields[a][i];
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
