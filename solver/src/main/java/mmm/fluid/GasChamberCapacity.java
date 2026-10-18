package mmm.fluid;

import mmm.inventory.LiquidCellInventory;

import java.util.ArrayList;
import java.util.List;

/** Actual coarse pore remainders summed directly on their gas nodes, with SI rounding bounds. */
final class GasChamberCapacity {
	record Chamber(int node, int minimumPiece, double cubicMeters, double roundingErrorBound) { }
	private final GasComplementGeometry geometry;
	private final List<GasComponentCapacity.Component> components;
	private final List<Chamber> chambers;
	private final double ambientCubicMeters, ambientRoundingErrorBound;

	private GasChamberCapacity(GasComplementGeometry geometry, List<GasComponentCapacity.Component> components,
	                          List<Chamber> chambers,
	                          double ambientCubicMeters, double ambientRoundingErrorBound) {
		this.geometry = geometry;
		this.chambers = List.copyOf(chambers);
		this.components = List.copyOf(components);
		this.ambientCubicMeters = ambientCubicMeters; this.ambientRoundingErrorBound = ambientRoundingErrorBound;
	}

	static GasChamberCapacity capture(FluidDomain domain, GasComplementGeometry geometry,
	                                 GasChamberTopology topology, PhaseLiquidGeometryEdit futureEdit) {
		if (domain == null) throw new IllegalArgumentException("Gas chamber domain is missing");
		long[] occupied = domain.granularCoupling == null ? new long[domain.cellCount] : domain.granularCoupling.copySolidSvq();
		return capture(domain, geometry, topology, futureEdit, occupied);
	}

	static GasChamberCapacity capture(FluidDomain domain, GasComplementGeometry geometry,
	                                 GasChamberTopology topology, PhaseLiquidGeometryEdit futureEdit, long[] occupiedSvq) {
		requireTopology(domain, geometry, topology);
		var components = GasComponentCapacity.capture(domain, geometry, futureEdit, occupiedSvq);
		return assemble(components,geometry,topology);
	}

	static GasChamberCapacity capture(FluidDomain domain, GasComplementGeometry geometry,
	                                 GasChamberTopology topology, long[] occupiedSvq,
	                                 LiquidCellInventory.Snapshot cells) {
		return capture(domain, geometry, topology, occupiedSvq, cells, null);
	}

	static GasChamberCapacity capture(FluidDomain domain, GasComplementGeometry geometry,
	                                 GasChamberTopology topology, long[] occupiedSvq,
	                                 LiquidCellInventory.Snapshot cells, GasRuntimeGeometry source) {
		requireTopology(domain, geometry, topology);
		return assemble(GasComponentCapacity.capture(domain,geometry,occupiedSvq,cells,source),geometry,topology);
	}

	private static void requireTopology(FluidDomain domain, GasComplementGeometry geometry, GasChamberTopology topology) {
		if (domain == null || geometry == null || topology == null || !topology.matchesGeometry(geometry))
			throw new IllegalArgumentException("Gas chamber capacity requires its matching captured geometry");
	}
	private static double[] totals(int count) { return new double[count + 1]; }
	private static GasChamberCapacity assemble(List<GasComponentCapacity.Component> components,
	                                           GasComplementGeometry geometry, GasChamberTopology topology) {
		int count = topology.chambers().size();
		var capacities = totals(count); var errors = totals(count);
		// Every dry micro piece in one fixed pore support has the same coarse gas owner.
		// Sum the actual component remainder once; distributing it over eight pieces
		// before summing the same node only adds allocation and rounding work.
		for (var component : components) if(component.availableVolumeNormalized().signum()>0) {
			int node=-1;
			for(int bits=component.supportMask();bits!=0;bits&=bits-1) {
				node=topology.nodeOfPiece(8*component.cell()+Integer.numberOfTrailingZeros(bits));
				if(node>=0)break;
			}
			if(node<0)throw new GasTopologyBlockedException("Positive component capacity has no gas owner");
			double value=component.availableVolumeNormalized().toDouble()*geometry.cellVolumeCubicMeters();
			capacities[node]+=value;errors[node]+=16*Math.ulp(value)+Math.ulp(capacities[node]);
		}
		return finish(components, geometry, topology, capacities, errors);
	}
	private static GasChamberCapacity finish(List<GasComponentCapacity.Component> components,
	                                         GasComplementGeometry geometry, GasChamberTopology topology,
	                                         double[] capacities, double[] errors) {
		var chambers = new ArrayList<Chamber>(topology.chambers().size());
		for (var chamber : topology.chambers()) {
			int node = chamber.node(); double capacity = positive(capacities[node]);
			chambers.add(new Chamber(node, chamber.minimumPiece(), capacity, bound(errors[node], capacity)));
		}
		double ambient = capacities[0] == 0 ? 0 : positive(capacities[0]);
		return new GasChamberCapacity(geometry, components, chambers, ambient, bound(errors[0], ambient));
	}

    private static double positive(double value) {
        if (!(Double.isFinite(value) && value > 0)) throw new GasTopologyBlockedException("Gas chamber raw capacity is not representable");
        return value;
    }
    private static double bound(double leaves, double capacity) {
        return capacity == 0 ? 0 : Math.nextUp(leaves + Math.ulp(capacity));
    }
	/** Fine geometric capacity is prepared only by callers that actually need a micro view. */
	List<GasPieceCapacity.Piece> pieces() {
		var result=new ArrayList<GasPieceCapacity.Piece>();
		for(var component:components)result.addAll(GasPieceCapacity.capture(component.cell(),component.supportMask(),component.availableVolumeNormalized(),geometry));
		result.sort(java.util.Comparator.comparingInt(GasPieceCapacity.Piece::id));
		return List.copyOf(result);
	}
	List<GasComponentCapacity.Component> components() { return components; }
	List<Chamber> chambers() { return chambers; }
	double ambientCubicMeters() { return ambientCubicMeters; }
	double ambientRoundingErrorBound() { return ambientRoundingErrorBound; }
}
