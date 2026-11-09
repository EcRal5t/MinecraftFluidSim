package mmm.thermal;

import java.util.Map;
import java.util.TreeMap;

/** One installed face and its actual adsorbate inventory; energy belongs to its finite wall. */
public record FixedSurfaceState(FiniteThermalDeviceDefinition.LocalFace face, String materialId,
		long loadingMassUnits, Map<String, Long> speciesQuanta, Map<String, Double> residualQuanta) {
	public FixedSurfaceState(FiniteThermalDeviceDefinition.LocalFace face, String materialId,
			long loadingMassUnits, Map<String, Long> speciesQuanta) {
		this(face, materialId, loadingMassUnits, speciesQuanta, Map.of());
	}
	public FixedSurfaceState {
		if (face == null) throw new IllegalArgumentException("Surface face is missing");
		ThermalIds.requireId(materialId, "surface material id");
		if (loadingMassUnits <= 0) throw new IllegalArgumentException("Surface loading must be positive");
		var canonical = new TreeMap<String, Long>();
		for (var entry : speciesQuanta.entrySet()) {
			ThermalIds.requireId(entry.getKey(), "adsorbed species id");
			if (entry.getValue() == null || entry.getValue() <= 0)
				throw new IllegalArgumentException("Adsorbed species amount must be positive");
			canonical.put(entry.getKey(), entry.getValue());
		}
		speciesQuanta = java.util.Collections.unmodifiableMap(canonical);
		var residuals = new TreeMap<String, Double>();
		for (var entry : residualQuanta.entrySet()) {
			ThermalIds.requireId(entry.getKey(), "surface residual species id");
			if (entry.getValue() == null || !Double.isFinite(entry.getValue()))
				throw new IllegalArgumentException("Invalid surface exchange residual");
			if (entry.getValue() != 0) residuals.put(entry.getKey(), entry.getValue());
		}
		residualQuanta = java.util.Collections.unmodifiableMap(residuals);
	}
}
