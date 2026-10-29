package mmm.fluid;

import mmm.inventory.LiquidCellInventory;
import mmm.inventory.PhaseOccupancyState;
import mmm.units.SignedDyadic;
import mmm.fluid.air.GasPartitionWeights;

/** One immutable future geometry and sparse old-node -> future-node source weights. */
final class GasVolumeAllocation {
    private final GasRuntimeGeometry sourceGeometry,preparedFrame;
    private final mmm.fluid.air.AirNetwork sourceNetwork;
    private final long networkRevision;
    private final PhaseOccupancyState sourceOccupancy;
    private final LiquidCellInventory.Snapshot sourceInventory,futureInventory;
    // Row source+1: explicit vacuum=-1, ambient=0, then finite gas nodes.
    private final PhaseSourceColumns sources;
    private final SignedDyadic[] ambientControls;

    private GasVolumeAllocation(HostFluidState domain,LiquidCellInventory future,GasRuntimeGeometry frame,PhaseSourceColumns sources,SignedDyadic[] ambientControls){
        sourceGeometry=domain.gasState.frame;preparedFrame=frame;
        sourceNetwork=domain.gasState.network;networkRevision=sourceNetwork.revision();
        sourceInventory=domain.liquidCells.snapshot();futureInventory=future.snapshot();sourceOccupancy=domain.phaseOccupancy;
        this.sources=sources;this.ambientControls=ambientControls;
    }
    /** Receiver aggregation is transposed once; inventory, U, control and EOS work share its ordering. */
    static GasVolumeAllocation fromTargetSources(HostFluidState domain,LiquidCellInventory future,GasRuntimeGeometry frame,
                                                 PhaseSourceColumns targetSources){
        int oldCount=domain.gasState.frame==null?0:domain.gasState.frame.nodeCount();
        var bySource=new PhaseSourceColumns(oldCount+2);
        SignedDyadic[] ambientControls=null;
        if(targetSources.rowCount()!=frame.nodeCount()+1)throw new IllegalArgumentException("Gas source rows differ from future topology");
        // Descending receivers produce ascending destination chains in each source row.
        for(int target=frame.nodeCount();target>=0;target--){
            if(target>0&&domain.gasState.frame!=null&&targetSources.first(target)<0)
                throw new IllegalStateException("Future gas occupation has no transported or explicit source: node="+target
                        +" volumeM3="+frame.capacity().chambers().get(target-1).cubicMeters()
                        +" minimumPiece="+frame.minimumPiece(target)+" oldNodes="+oldCount);
            if(target>0&&targetSources.quantity(target,0)>0){
                int count=0,ambient=-1;for(int entry=targetSources.first(target);entry>=0;entry=targetSources.next(entry))count++;
                var weights=new double[count];int part=0;
                for(int entry=targetSources.first(target);entry>=0;entry=targetSources.next(entry)){
                    if(targetSources.key(entry)==0)ambient=part;weights[part++]=targetSources.amount(entry);
                }
                if(ambientControls==null){ambientControls=new SignedDyadic[frame.nodeCount()+1];java.util.Arrays.fill(ambientControls,SignedDyadic.ZERO);}
                ambientControls[target]=count==1?frame.capacity().normalizedVolume(target):
                        GasPartitionWeights.of(weights).partitionExact(frame.capacity().normalizedVolume(target))[ambient];
            }
            for(int entry=targetSources.first(target);entry>=0;entry=targetSources.next(entry)){
                int source=(int)targetSources.key(entry);double amount=targetSources.amount(entry);
                if(source< -1||source>oldCount)throw new IllegalArgumentException("Gas allocation names an absent source: "+source);
                if(amount>0)bySource.appendUnique(source+1,target,amount);
            }
        }
        return new GasVolumeAllocation(domain,future,frame,bySource,ambientControls);
    }
    /** Energy and composition settlement can rebind an unchanged volume successor. */
    GasVolumeAllocation rebindFuture(HostFluidState domain,LiquidCellInventory future){
        requireSourceCurrent(domain);
        return new GasVolumeAllocation(domain,future,preparedFrame.rebindInventory(future),sources,ambientControls);
    }
    GasRuntimeGeometry preparedFrame(){return preparedFrame;}
    PhaseSourceColumns sources(){return sources;}
    SignedDyadic ambientControl(int node){return ambientControls==null?SignedDyadic.ZERO:ambientControls[node];}
    private void requireSourceCurrent(HostFluidState domain){
        if(domain.gasState.frame!=sourceGeometry||domain.gasState.network!=sourceNetwork||sourceNetwork.revision()!=networkRevision)
            throw new IllegalStateException("Gas allocation source frame/network changed: plannedNetworkRevision="+networkRevision
                    +" actualNetworkRevision="+domain.gasState.network.revision());
        if(domain.phaseOccupancy!=sourceOccupancy||!GasRuntimeGeometry.sameLiquidVolumes(domain.liquidCells,sourceInventory))
            throw new IllegalStateException("Gas allocation source phase/support volume changed: plannedInventoryRevision="
                    +sourceInventory.revision()+" actualInventoryRevision="+domain.liquidCells.revision());
    }
    void requireCurrent(HostFluidState domain,LiquidCellInventory future){
        requireSourceCurrent(domain);
        if(!GasRuntimeGeometry.sameLiquidVolumes(future,futureInventory))
            throw new IllegalStateException("Gas allocation future support volume changed: plannedInventoryRevision="
                    +futureInventory.revision()+" actualInventoryRevision="+future.revision());
    }
    static GasVolumeAllocation prepare(HostFluidState domain,LiquidCellInventory future,long[] occupied,byte[] masks){
        PhaseOccupancyTransport.ensure(domain);
        var retained=domain.gasState.frame;
        if(retained!=null&&retained.sealedExterior()==domain.gasState.sealedDomainBoundary
                &&future.hasSameGeometry(retained.liquidCells())&&retained.matchesCarrierGeometry(domain,occupied)
                &&(masks==null||retained.geometry().matchesCollisionMasks(masks))){
            // Unchanged actual space has one exact identity source per finite chamber.
            // N/U settlement needs fresh transaction endpoints, not another geometric remap.
            var frame=retained.continueInventory(domain,future);
            var sources=new PhaseSourceColumns(retained.nodeCount()+2);
            for(int node=1;node<=retained.nodeCount();node++)
                sources.appendUnique(node+1,node,retained.capacity().normalizedVolume(node).toDouble());
            return new GasVolumeAllocation(domain,future,frame,sources,null);
        }
        return PhaseOccupancyTransport.prepareEdit(domain,future,occupied,masks);
    }
}
