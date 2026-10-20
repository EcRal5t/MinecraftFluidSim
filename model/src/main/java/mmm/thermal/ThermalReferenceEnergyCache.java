package mmm.thermal;

import mmm.chemistry.ChemicalLimits;

import java.util.IdentityHashMap;
import java.util.List;

/** Immutable SI reference coefficients prepared once for the registered species definitions. */
final class ThermalReferenceEnergyCache {
	private final IdentityHashMap<ThermalSpeciesOffset,Double> coefficients=new IdentityHashMap<>();
	ThermalReferenceEnergyCache(List<ThermalSpeciesOffset> offsets) {
		for(var offset:offsets){
			double coefficient=offset.referenceInternalEnergyJPerMol().divide(ChemicalLimits.QUANTA_PER_MOL).toDouble();
			if(!Double.isFinite(coefficient))throw new IllegalArgumentException("Species reference energy exceeds its SI coefficient representation");
			coefficients.put(offset,coefficient);
		}
	}

	double energy(ThermalSpeciesOffset offset, long quanta) {
		if (offset.referenceInternalEnergyJPerMol().isZero()) return 0;
		Double coefficient=coefficients.get(offset);
		if(coefficient==null)throw new IllegalArgumentException("Unregistered species reference energy");
		return coefficient*quanta;
	}
}
