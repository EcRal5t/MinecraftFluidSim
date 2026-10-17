package mmm.fluid;

import mmm.units.SignedDyadic;

/** Complete prospective columns and coupled plans accepted by the common remap publisher. */
sealed interface CellRemapCandidate permits CellCarrierRemapPlan {
	CellRemapMarkerColumns markers();
	SignedDyadic energyRoundoff();
	default SignedDyadic dissipatedEnergy() { return SignedDyadic.ZERO; }
	boolean[] changedSpeciesCells();
	GasTopologyCoordinator.GasTopologyPlan gasTopology();
	boolean commitGranularCollision();
	CellRemapMoments.Audit momentAudit();
}
