package com.example.fluid.air;

/**
 * One finite-rate flow path (hole, gap or orifice) between two gas nodes.
 *
 * @param firstNode           first node id; {@link AirNetwork#AMBIENT_NODE} is the ambient reservoir
 * @param secondNode          second node id, distinct from the first
 * @param areaSquareMeters    open flow area in m²
 * @param dischargeCoefficient dimensionless discharge coefficient in (0, 1]
 */
public record AirEdge(int firstNode, int secondNode, double areaSquareMeters, double dischargeCoefficient) {
	public AirEdge {
		if (firstNode == secondNode) throw new IllegalArgumentException("An edge needs two distinct nodes");
		if (!(areaSquareMeters > 0.0) || Double.isNaN(areaSquareMeters)) {
			throw new IllegalArgumentException("Edge area must be positive and finite");
		}
		if (!(dischargeCoefficient > 0.0 && dischargeCoefficient <= 1.0)) {
			throw new IllegalArgumentException("Discharge coefficient must be in (0, 1]");
		}
	}

	public boolean touches(int node) { return firstNode == node || secondNode == node; }
}
