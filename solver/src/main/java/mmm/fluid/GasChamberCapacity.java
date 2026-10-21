package mmm.fluid;

import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Actual dry capacity is summed in normalized inventory units before one SI conversion. */
final class GasChamberCapacity {
    record Chamber(int node,int minimumPiece,SignedDyadic normalizedVolume,double cubicMeters,double roundingErrorBound) { }
    private final List<GasComponentCapacity.Component> components;
    private final List<Chamber> chambers;
    private final SignedDyadic ambientNormalized;
    private final double ambientCubicMeters,ambientRoundingErrorBound;

    private GasChamberCapacity(List<GasComponentCapacity.Component> components,List<Chamber> chambers,
                               SignedDyadic ambientNormalized,double ambientCubicMeters,double ambientError){
        this.components=List.copyOf(components);this.chambers=List.copyOf(chambers);
        this.ambientNormalized=ambientNormalized;this.ambientCubicMeters=ambientCubicMeters;ambientRoundingErrorBound=ambientError;
    }
    static GasChamberCapacity capturePrepared(GasComplementGeometry geometry,GasChamberTopology topology){
        var cached=topology.preparedCapacity();if(cached!=null)return cached;
        var components=geometry.actualCapacity().components;
        var amounts=new SignedDyadic[topology.chambers().size()+1];Arrays.fill(amounts,SignedDyadic.ZERO);
        for(var component:components)if(component.availableVolumeNormalized().signum()>0){
            int node=topology.nodeOfComponent(component.cell(),component.supportMask());
            if(node<0)throw new GasTopologyBlockedException("Positive component capacity has no gas owner at cell "
                    +component.cell()+" support="+component.supportMask()+" volume="+component.availableVolumeNormalized());
            amounts[node]=amounts[node].add(component.availableVolumeNormalized());
        }
        var scale=SignedDyadic.of(geometry.cellVolumeCubicMeters());
        var chambers=new ArrayList<Chamber>(topology.chambers().size());
        for(var chamber:topology.chambers()){
            int node=chamber.node();double volume=amounts[node].multiply(scale).toDouble();
            if(!(volume>0&&Double.isFinite(volume)))throw new GasTopologyBlockedException(
                    "Gas chamber has no representable actual capacity: node="+node+" minimumPiece="+chamber.minimumPiece()+" capacity="+amounts[node]);
            chambers.add(new Chamber(node,chamber.minimumPiece(),amounts[node],volume,Math.ulp(volume)));
        }
        double ambient=amounts[0].multiply(scale).toDouble();
        if(!(ambient>=0&&Double.isFinite(ambient))||ambient==0&&amounts[0].signum()!=0)
            throw new GasTopologyBlockedException("Ambient gas capacity has no finite SI representation");
        var result=new GasChamberCapacity(components,chambers,amounts[0],ambient,ambient==0?0:Math.ulp(ambient));
        topology.preparedCapacity(result);return result;
    }
    SignedDyadic normalizedVolume(int node){return node==0?ambientNormalized:chambers.get(node-1).normalizedVolume();}
    List<GasComponentCapacity.Component> components(){return components;}
    List<Chamber> chambers(){return chambers;}
    double ambientCubicMeters(){return ambientCubicMeters;}
    double ambientRoundingErrorBound(){return ambientRoundingErrorBound;}
}
