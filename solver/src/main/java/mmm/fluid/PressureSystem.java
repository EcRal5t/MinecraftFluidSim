package mmm.fluid;

import mmm.thermal.MixtureVolumeModel;

/** Shared host geometry and physical-pressure observations over the committed mixture. */
final class PressureSystem {
    private final HostFluidState domain;
    private FlipPressurePhaseGeometry phaseGeometry;
    private boolean geometryDirty=true;
    private final float[] liquidPressure;
    private FlipPressurePhaseGeometry preparedPhase;
    private mmm.granular.GranularCouplingFields preparedSolid;
    private mmm.granular.GranularParcelStore preparedParcels;
    private mmm.granular.GranularDepositStore preparedDeposits;
    private long preparedQuantityRevision=-1,preparedParcelRevision=-1,preparedDepositRevision=-1,preparedPhaseRevision=-1;
    final GranularFaceVolumes solidFaces;
    final FixedGeometrySnapshot.FaceIncidence faces;

    PressureSystem(HostFluidState domain){
        this.domain=domain;liquidPressure=new float[domain.cellCount];
        solidFaces=new GranularFaceVolumes(domain);faces=domain.geometry.cutCells.snapshot().faceIncidence();
    }
    FlipPressurePhaseGeometry phaseGeometry(){
        if(phaseGeometry==null)phaseGeometry=new FlipPressurePhaseGeometry(domain.sizeX,domain.sizeY,domain.sizeZ);
        return phaseGeometry;
    }
    void invalidateGeometry(){geometryDirty=true;}
    void clearLiquidPressure(int cell){liquidPressure[cell]=0;}
    void prepareGeometry(){
        domain.domainFlowFields.ensureInventoryGrid();
        if(!geometryDirty)return;
        domain.ensurePhaseOccupancy();
        phaseGeometry().rebuildOccupancy(domain.phaseOccupancy,domain.geometry.cutCells,
                PhaseOccupancyTransport.poreVolumes(domain,domain.occupiedSolidSvq(),null),null);
		var parcels=domain.granularState==null?null:domain.granularState.parcels;
		var deposits=domain.granularState==null?null:domain.granularState.deposits;
		long parcelRevision=parcels==null?-1:parcels.mutationRevision(),depositRevision=deposits==null?-1:deposits.mutationRevision();
		if(preparedPhase!=phaseGeometry||preparedPhaseRevision!=phaseGeometry.revision()||preparedQuantityRevision!=domain.cellMacStage.quantityRevision()
				||preparedSolid!=domain.granularCoupling||preparedParcels!=parcels||preparedDeposits!=deposits
				||preparedParcelRevision!=parcelRevision||preparedDepositRevision!=depositRevision){
			solidFaces.rebuild();
			preparedPhase=phaseGeometry;preparedPhaseRevision=phaseGeometry.revision();preparedQuantityRevision=domain.cellMacStage.quantityRevision();preparedSolid=domain.granularCoupling;
			preparedParcels=parcels;preparedDeposits=deposits;preparedParcelRevision=parcelRevision;preparedDepositRevision=depositRevision;
		}
		domain.tension.rebuildSurfaceTensionPressure();geometryDirty=false;
	}

    CondensedPhasePressureField captureRuntimePressure(){return captureLiquidPressure();}
    CondensedPhasePressureField captureLiquidPressure(){
        float[] values=liquidPressure.clone();
        if(domain.gasState.spatial!=null){
            var gas=domain.gasState.spatial;long[] occupied=domain.occupiedSolidSvq();
            for(int cell=0;cell<values.length;cell++)values[cell]=(float)(DomainGasInventory.mixture(domain,cell,
                    gas.cells().get(cell),gas.energyAt(cell),domain.liquidCells,occupied,null).absolutePressurePa()-MixtureVolumeModel.REFERENCE_PRESSURE_PA);
        }
        return new CondensedPhasePressureField(0,values);
    }
    void restoreLiquidPressure(CondensedPhasePressureField state){
        float[] values=state.copyRelativeGaugePressurePa();
        for(int cell=0;cell<values.length;cell++)liquidPressure[cell]=state.pressureReferencePa()+values[cell];
    }
    double liquidPressureAt(int cell,int support){
        var gas=domain.gasState.spatial;
        return gas==null?liquidPressure[cell]:DomainGasInventory.mixture(domain,cell,gas.cells().get(cell),gas.energyAt(cell)).absolutePressurePa()-MixtureVolumeModel.REFERENCE_PRESSURE_PA;
    }
}
