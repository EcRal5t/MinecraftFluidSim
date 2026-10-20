package mmm.fluid;

import mmm.inventory.LiquidCellInventory;

import java.util.List;

/** Shared upwind face transport directly between persistent liquid cell inventories. */
final class CellLiquidVolumeTransport {
	static final class Prepared {
        private final LiquidCellInventory.Plan inventory;
        private final ConservativeLiquidFlux volumeFlux;
        private final GasVolumeAllocation gasAllocation;
        private final LiquidCellInventory sourceInventory;
        private List<LiquidCellInventory.FaceFlux> faceFluxes;
        Prepared(LiquidCellInventory.Plan inventory,ConservativeLiquidFlux volumeFlux,GasVolumeAllocation gasAllocation,LiquidCellInventory sourceInventory){
            this.inventory=inventory;this.volumeFlux=volumeFlux;this.gasAllocation=gasAllocation;this.sourceInventory=sourceInventory;
        }
        LiquidCellInventory.Plan inventory(){return inventory;}
        ConservativeLiquidFlux volumeFlux(){return volumeFlux;}
        GasVolumeAllocation gasAllocation(){return gasAllocation;}
        LiquidCellInventory sourceInventory(){return sourceInventory;}
        CellMomentumTransport.Prepared preparedMomentum(){return volumeFlux.preparedMomentum;}
        List<LiquidCellInventory.FaceFlux> faceFluxes(){if(faceFluxes==null)faceFluxes=volumeFlux.faceFluxes(inventory);return faceFluxes;}
    }
    private final FluidDomain domain;
    private final ConservativeLiquidFlux transport;
    CellLiquidVolumeTransport(FluidDomain domain){this.domain=domain;transport=new ConservativeLiquidFlux(domain);}

    double courantRate(double verticalPrediction){return transport.courantRate(verticalPrediction);}
    double projectedCourantRate(){return transport.courantRate(0);}

	/** Prepares quantities and carried U; the coupled publisher owns commit. */
	Prepared prepare(float dt) {
		return prepareFlux(prepareVolumeFlux(dt),dt);
	}
    ConservativeLiquidFlux prepareVolumeFlux(float dt) {
        if(!(Float.isFinite(dt)&&dt>0))throw new IllegalArgumentException("Liquid transport duration must be positive");
        domain.ensurePressureGeometry();
        long[] occupied=domain.granularCoupling==null?new long[domain.cellCount]:domain.granularState!=null&&domain.granularState.parcels.count()>0?domain.granularNextCoupling.copySolidSvq():domain.granularCoupling.copySolidSvq();
        transport.prepare(dt,occupied,quantities->{
            transport.preparedMomentum=domain.cellMacStage.prepareMomentumTransport(transport,quantities);
            return transport.preparedMomentum.heatByCell();
        });return transport;
    }
	Prepared prepareFlux(ConservativeLiquidFlux transport, float dt) { return prepareFlux(transport,dt,domain.liquidCells); }
	Prepared prepareFlux(ConservativeLiquidFlux transport, float dt, LiquidCellInventory source) {
        var inventory=transport.prepareInventory(source);
		return new Prepared(inventory,transport,transport.gasAllocation(inventory.future()),transport.sourceInventory());
	}
}
