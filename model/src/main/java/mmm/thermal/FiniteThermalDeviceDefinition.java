package mmm.thermal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Registry-frozen finite-device body defaults and local thermal-port geometry. */
public record FiniteThermalDeviceDefinition(
		String id,
		String thermalMaterialId,
		double bodyMassKilograms,
		double initialTemperatureK,
		List<Port> ports
) {
	/** Block-local face names are loader-neutral and mapped to world directions only by Fabric. */
	public enum LocalFace { DOWN, UP, NORTH, SOUTH, WEST, EAST }

	/** One declared contact face; actual area comes from the world face and cut aperture. */
	public record Port(
			String id,
			String contactGroupId,
			LocalFace localFace,
			double wallThicknessMeters,
			double fluidFilmThicknessMeters
	) {
		public Port {
			ThermalIds.requireId(id, "finite thermal port id");
			ThermalIds.requireId(contactGroupId, "finite thermal contact group id");
			if (localFace == null) throw new IllegalArgumentException("Finite thermal port face is missing");
			if (!(Double.isFinite(wallThicknessMeters) && wallThicknessMeters > 0.0)
					|| !(Double.isFinite(fluidFilmThicknessMeters) && fluidFilmThicknessMeters > 0.0)) {
				throw new IllegalArgumentException("Finite thermal port thickness is invalid");
			}
		}
	}

	public FiniteThermalDeviceDefinition {
		ThermalIds.requireId(id, "finite thermal device type id");
		ThermalIds.requireId(thermalMaterialId, "finite thermal device material id");
		if (!(Double.isFinite(bodyMassKilograms) && bodyMassKilograms > 0.0)) {
			throw new IllegalArgumentException("Finite thermal device body mass is invalid");
		}
		if (!(Double.isFinite(initialTemperatureK)
				&& initialTemperatureK >= ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K)) {
			throw new IllegalArgumentException("Finite thermal device initial temperature is invalid");
		}
		if (ports == null || ports.isEmpty() || ports.size() > ThermalLimits.MAX_FINITE_DEVICE_CONTACTS) {
			throw new IllegalArgumentException("Finite thermal device ports are empty or over budget");
		}
		ArrayList<Port> canonical = new ArrayList<>(ports);
		if (canonical.stream().anyMatch(java.util.Objects::isNull)) {
			throw new IllegalArgumentException("Finite thermal device ports contain null");
		}
		canonical.sort(Comparator.comparing(Port::id));
		Set<LocalFace> faces = new HashSet<>();
		for (int index = 0; index < canonical.size(); index++) {
			Port port = canonical.get(index);
			if (index > 0 && canonical.get(index - 1).id().equals(port.id())) {
				throw new IllegalArgumentException("Duplicate finite thermal port id " + port.id());
			}
			if (!faces.add(port.localFace())) {
				throw new IllegalArgumentException("Multiple finite thermal ports claim face " + port.localFace());
			}
		}
		ports = List.copyOf(canonical);
	}
}
