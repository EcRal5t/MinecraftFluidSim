package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.units.SignedDyadic;

/** Integer species share the positive volume map and aggregate before creating chemical carriers. */
final class LiquidInventorySpeciesShares {
	private LiquidInventorySpeciesShares() { }
	static void accumulate(FluidDomain domain, int marker, SignedDyadic[] weights, LiquidInventoryStock[] targets) {
		if (domain.chemicalParcels == null) return;
		var source = domain.chemicalParcels.record(marker);
		for (int e = 0; e < source.entryCount(); e++) {
			var amounts = ExactVolumeAllocation.allocate(source.amountAt(e), weights);
			for (int q = 0; q < amounts.length; q++) if (amounts[q] > 0)
				targets[q].species.merge(source.speciesHandle(e), amounts[q], Math::addExact);
		}
	}
	static ChemicalMixtureRecord mixture(FluidDomain domain, LiquidInventoryStock stock) {
		if (domain.chemicalParcels == null) return null;
		if (stock.species.isEmpty()) throw new IllegalStateException("Resolved chemical inventory exceeds species quantum resolution");
		var handles = stock.species.keySet().stream().mapToInt(Integer::intValue).toArray();
		var amounts = stock.species.values().stream().mapToLong(Long::longValue).toArray();
		var mixture = new ChemicalMixtureRecord(handles, amounts, 0);
		return mixture.withMassResidualKilograms(domain.unitScale.kilograms(stock.mass.toDouble())
				- mixture.exactSpeciesMassKilograms(domain.chemicalParcels.registry()).toDouble());
	}
}
