package mmm.fluid;

import mmm.fluid.air.GasInventory;
import mmm.inventory.LiquidCellInventory;
import mmm.inventory.PhaseOccupancyState;
import java.util.List;

/** Phase-advection provenance, published with its authoritative future micro occupation. */
final class GasVolumeAllocation {
    enum SourceKind { EXISTING, AMBIENT, EXPLICIT_VACUUM }
    record Share(int sourceNode,int targetPiece,double volume,SourceKind kind){
        Share {
            if(targetPiece<0||!(volume>0&&Double.isFinite(volume))||kind==null
                    ||kind==SourceKind.EXISTING&&sourceNode<=0||kind==SourceKind.AMBIENT&&sourceNode!=0
                    ||kind==SourceKind.EXPLICIT_VACUUM&&sourceNode!=-1)
                throw new IllegalArgumentException("Invalid transported gas source share");
        }
    }
    private final GasRuntimeGeometry sourceGeometry;
    private final mmm.fluid.air.AirNetwork sourceNetwork;
    private final long networkRevision;
    private final PhaseOccupancyState sourceOccupancy,futureOccupancy;
    private final LiquidCellInventory.Snapshot sourceInventory,futureInventory;
    private final List<Share> shares;
    private final FlipPressurePhaseGeometry preparedPhase;
    private final GasComplementGeometry preparedGeometry;
    GasVolumeAllocation(FluidDomain domain,LiquidCellInventory future,PhaseOccupancyState occupancy,List<Share> shares){
        this(domain,future,occupancy,shares,null,null);
    }
    GasVolumeAllocation(FluidDomain domain,LiquidCellInventory future,PhaseOccupancyState occupancy,List<Share> shares,
                        FlipPressurePhaseGeometry phase,GasComplementGeometry geometry){
        sourceGeometry=domain.gasState.frame;sourceNetwork=domain.gasState.network;networkRevision=sourceNetwork.revision();
        sourceInventory=domain.liquidCells.snapshot();futureInventory=future.snapshot();sourceOccupancy=domain.phaseOccupancy;futureOccupancy=occupancy;this.shares=List.copyOf(shares);
        preparedPhase=phase;preparedGeometry=geometry;
        if(occupancy==null||occupancy.cellCount()!=domain.cellCount)throw new IllegalArgumentException("Gas allocation requires complete future phase occupation");
        for(var share:this.shares)if(share.targetPiece()>=8*domain.cellCount||share.kind()==SourceKind.EXISTING&&(sourceGeometry==null||share.sourceNode()>sourceGeometry.nodeCount()))throw new IllegalArgumentException("Gas allocation names an absent source or target");
    }
    GasRuntimeGeometry sourceGeometry(){return sourceGeometry;}
    long networkRevision(){return networkRevision;}
    LiquidCellInventory.Snapshot futureInventory(){return futureInventory;}
    PhaseOccupancyState sourceOccupancy(){return sourceOccupancy;}
    PhaseOccupancyState futureOccupancy(){return futureOccupancy;}
    List<Share> shares(){return shares;}
    FlipPressurePhaseGeometry preparedPhase(){return preparedPhase;}
    GasComplementGeometry preparedGeometry(){return preparedGeometry;}
    void requireCurrent(FluidDomain domain,LiquidCellInventory future){
        if(domain.gasState.frame!=sourceGeometry||domain.gasState.network!=sourceNetwork||sourceNetwork.revision()!=networkRevision
                ||domain.phaseOccupancy!=sourceOccupancy||!domain.liquidCells.hasSameGeometry(sourceInventory)||!future.hasSameGeometry(futureInventory))
            throw new IllegalStateException("Gas phase source or future inventory changed");
    }
    static GasVolumeAllocation prepare(FluidDomain domain,LiquidCellInventory future,long[] occupied,byte[] masks){return prepare(domain,future,occupied,masks,null);}
    static GasVolumeAllocation prepare(FluidDomain domain,LiquidCellInventory future,long[] occupied,byte[] masks,List<GasInventory> effectiveInventories){
        return PhaseOccupancyTransport.prepareEdit(domain,future,occupied,masks,effectiveInventories);
    }
}
