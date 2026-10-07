package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.units.SignedDyadic;

/** Integer species allocation observes the same volume shares as local mass and caloric state. */
final class LiquidInventorySpeciesShares {
	private LiquidInventorySpeciesShares() { }
	static ChemicalMixtureRecord[] allocate(FluidDomain domain, int marker, SignedDyadic[] weights) {
		if (domain.chemicalParcels == null) return null;
		var source = domain.chemicalParcels.record(marker); var shares = new long[source.entryCount()][];
		for (int e = 0; e < shares.length; e++) shares[e] = ExactVolumeAllocation.allocate(source.amountAt(e), weights);
		var result = new ChemicalMixtureRecord[weights.length];
		for (int q = 0; q < result.length; q++) {
			int count = 0; for (var row : shares) if (row[q] > 0) count++;
			if (count == 0) throw new IllegalStateException("Local liquid inventory exceeds integer species resolution");
			var handles = new int[count]; var amounts = new long[count]; int entry = 0;
			for (int e = 0; e < shares.length; e++) if (shares[e][q] > 0) { handles[entry] = source.speciesHandle(e); amounts[entry++] = shares[e][q]; }
			result[q] = new ChemicalMixtureRecord(handles, amounts, 0);
		}
		return result;
	}
}
