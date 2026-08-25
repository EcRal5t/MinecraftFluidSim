package cc.ecisr.mmm.fluid.air;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Immutable independent state columns for one component portion. */
public record GasComponentAmount(
		String componentId,
		long standardVolumeNanos,
		double mass,
		List<SpeciesAmount> species,
		double massResidual
) {
	public record SpeciesAmount(String speciesId, long amountQuanta) {
		public SpeciesAmount {
			GasIds.require(speciesId, "gas species id");
			if (amountQuanta <= 0L) throw new IllegalArgumentException("Gas species amount must be positive");
		}
	}

	public GasComponentAmount {
		GasIds.require(componentId, "gas component id");
		if (standardVolumeNanos <= 0L) throw new IllegalArgumentException("Gas component volume must be positive");
		if (!(Double.isFinite(mass) && mass > 0.0) || !Double.isFinite(massResidual)) {
			throw new IllegalArgumentException("Gas component mass columns are invalid");
		}
		if (species == null || species.size() > GasLimits.MAX_SPECIES_PER_COMPONENT) {
			throw new IllegalArgumentException("Gas component species are missing or over budget");
		}
		ArrayList<SpeciesAmount> canonical = new ArrayList<>(species);
		if (canonical.stream().anyMatch(java.util.Objects::isNull)) {
			throw new IllegalArgumentException("Gas component species contain null");
		}
		canonical.sort(Comparator.comparing(SpeciesAmount::speciesId));
		for (int index = 1; index < canonical.size(); index++) {
			if (canonical.get(index - 1).speciesId().equals(canonical.get(index).speciesId())) {
				throw new IllegalArgumentException("Duplicate gas component species " + canonical.get(index).speciesId());
			}
		}
		species = List.copyOf(canonical);
	}
}
