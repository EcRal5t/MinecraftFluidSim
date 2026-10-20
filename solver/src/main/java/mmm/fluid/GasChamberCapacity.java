package mmm.fluid;

import java.util.ArrayList;
import java.util.List;

/** Actual coarse pore remainders summed directly on their gas nodes, with SI rounding bounds. */
final class GasChamberCapacity {
	record Chamber(int node, int minimumPiece, double cubicMeters, double roundingErrorBound) { }
	private final List<GasComponentCapacity.Component> components;
	private final List<Chamber> chambers;
	private final double ambientCubicMeters, ambientRoundingErrorBound;

	private GasChamberCapacity(List<GasComponentCapacity.Component> components,
	                          List<Chamber> chambers,
	                          double ambientCubicMeters, double ambientRoundingErrorBound) {
		this.chambers = List.copyOf(chambers);
		this.components = List.copyOf(components);
		this.ambientCubicMeters = ambientCubicMeters; this.ambientRoundingErrorBound = ambientRoundingErrorBound;
	}

	private static double[] totals(int count) { return new double[count + 1]; }
	/** The frame's geometry already owns the jointly prepared stock and solid capacities. */
	static GasChamberCapacity capturePrepared(GasComplementGeometry geometry,GasChamberTopology topology){
		var cached=topology.preparedCapacity();if(cached!=null)return cached;
		var result=assemble(geometry.actualCapacity().components,geometry,topology);
		topology.preparedCapacity(result);return result;
	}
	private static GasChamberCapacity assemble(List<GasComponentCapacity.Component> components,
	                                           GasComplementGeometry geometry, GasChamberTopology topology) {
		int count = topology.chambers().size();
		var capacities = totals(count); var errors = totals(count);
		// Topology assigns an owner to the whole fixed-pore support. Geometry names
		// its dry pieces; the stock remainder supplies its actual thermodynamic volume.
		for (var component : components) if(component.availableVolumeNormalized().signum()>0) {
			int node=topology.nodeOfComponent(component.cell(),component.supportMask());
			if(node<0)throw new GasTopologyBlockedException("Positive component capacity has no gas owner at cell "
					+component.cell()+" support="+component.supportMask()+" volume="+component.availableVolumeNormalized());
			double value=component.availableVolumeNormalized().toDouble()*geometry.cellVolumeCubicMeters();
			capacities[node]+=value;errors[node]+=16*Math.ulp(value)+Math.ulp(capacities[node]);
		}
		return finish(components, topology, capacities, errors);
	}
	private static GasChamberCapacity finish(List<GasComponentCapacity.Component> components,
	                                         GasChamberTopology topology,
	                                         double[] capacities, double[] errors) {
		var chambers = new ArrayList<Chamber>(topology.chambers().size());
		for (var chamber : topology.chambers()) {
			int node = chamber.node(); double capacity = capacities[node];
			if(!(capacity>0&&Double.isFinite(capacity)))throw new GasTopologyBlockedException(
					"Gas chamber has no representable actual capacity: node="+node+" minimumPiece="
					+chamber.minimumPiece()+" capacity="+capacity);
			chambers.add(new Chamber(node, chamber.minimumPiece(), capacity, bound(errors[node], capacity)));
		}
		double ambient = capacities[0] == 0 ? 0 : positive(capacities[0]);
		return new GasChamberCapacity(components, chambers, ambient, bound(errors[0], ambient));
	}

    private static double positive(double value) {
        if (!(Double.isFinite(value) && value > 0)) throw new GasTopologyBlockedException("Gas chamber raw capacity is not representable");
        return value;
    }
    private static double bound(double leaves, double capacity) {
        return capacity == 0 ? 0 : Math.nextUp(leaves + Math.ulp(capacity));
    }
	List<GasComponentCapacity.Component> components() { return components; }
	List<Chamber> chambers() { return chambers; }
	double ambientCubicMeters() { return ambientCubicMeters; }
	double ambientRoundingErrorBound() { return ambientRoundingErrorBound; }
}
