package mmm.fluid;

import mmm.inventory.LiquidCellInventory;
import mmm.inventory.PhaseOccupancyState;

/** One immutable future geometry and sparse old-node -> future-node source weights. */
final class GasVolumeAllocation {
    private final GasRuntimeGeometry sourceGeometry,preparedFrame;
    private final mmm.fluid.air.AirNetwork sourceNetwork;
    private final long networkRevision;
    private final PhaseOccupancyState sourceOccupancy;
    private final LiquidCellInventory.Snapshot sourceInventory,futureInventory;
    // Row source+1: explicit vacuum=-1, ambient=0, then finite gas nodes.
    private final PhaseSourceColumns sources;

    private GasVolumeAllocation(FluidDomain domain,LiquidCellInventory future,GasRuntimeGeometry frame,PhaseSourceColumns sources){
        sourceGeometry=domain.gasState.frame;preparedFrame=frame;
        sourceNetwork=domain.gasState.network;networkRevision=sourceNetwork.revision();
        sourceInventory=domain.liquidCells.snapshot();futureInventory=future.snapshot();sourceOccupancy=domain.phaseOccupancy;
        this.sources=sources;
    }
    /** Receiver aggregation is transposed once; inventory, U, control and EOS work share its ordering. */
    static GasVolumeAllocation fromTargetSources(FluidDomain domain,LiquidCellInventory future,GasRuntimeGeometry frame,
                                                 PhaseSourceColumns targetSources){
        int oldCount=domain.gasState.frame==null?0:domain.gasState.frame.nodeCount();
        var bySource=new PhaseSourceColumns(oldCount+2);
        if(targetSources.rowCount()!=frame.nodeCount()+1)throw new IllegalArgumentException("Gas source rows differ from future topology");
        // Descending receivers produce ascending destination chains in each source row.
        for(int target=frame.nodeCount();target>=0;target--){
            if(target>0&&domain.gasState.frame!=null&&targetSources.first(target)<0)
                throw new IllegalStateException("Future gas occupation has no transported or explicit source: node="+target
                        +" volumeM3="+frame.capacity().chambers().get(target-1).cubicMeters()
                        +" minimumPiece="+frame.minimumPiece(target)+" oldNodes="+oldCount);
            for(int entry=targetSources.first(target);entry>=0;entry=targetSources.next(entry)){
                int source=(int)targetSources.key(entry);double amount=targetSources.amount(entry);
                if(source< -1||source>oldCount)throw new IllegalArgumentException("Gas allocation names an absent source: "+source);
                if(amount>0)bySource.appendUnique(source+1,target,amount);
            }
        }
        return new GasVolumeAllocation(domain,future,frame,bySource);
    }
    /** Energy and composition settlement can rebind an unchanged volume successor. */
    GasVolumeAllocation rebindFuture(FluidDomain domain,LiquidCellInventory future){
        requireCurrent(domain,future);
        return new GasVolumeAllocation(domain,future,preparedFrame,sources);
    }
    GasRuntimeGeometry preparedFrame(){return preparedFrame;}
    PhaseSourceColumns sources(){return sources;}
    void requireCurrent(FluidDomain domain,LiquidCellInventory future){
        if(domain.gasState.frame!=sourceGeometry||domain.gasState.network!=sourceNetwork||sourceNetwork.revision()!=networkRevision
                ||domain.phaseOccupancy!=sourceOccupancy||!domain.liquidCells.hasSameGeometry(sourceInventory)||!future.hasSameGeometry(futureInventory))
            throw new IllegalStateException("Gas phase source or future inventory changed");
    }
    static GasVolumeAllocation prepare(FluidDomain domain,LiquidCellInventory future,long[] occupied,byte[] masks){
        return PhaseOccupancyTransport.prepareEdit(domain,future,occupied,masks);
    }
}
