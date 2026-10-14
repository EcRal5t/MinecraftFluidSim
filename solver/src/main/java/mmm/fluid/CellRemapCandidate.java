package mmm.fluid;

import mmm.units.SignedDyadic;

/** Complete prospective columns and coupled plans accepted by the common remap publisher. */
sealed interface CellRemapCandidate permits CellCarrierRemapPlan, CellTranslationRemap {
	CellRemapMarkerColumns markers();
	SignedDyadic energyRoundoff();
	boolean[] changedSpeciesCells();
	GasTopologyCoordinator.GasTopologyPlan gasTopology();
	boolean commitGranularCollision();
	CellRemapMoments.Audit momentAudit();
}
