package mmm.thermal;

/** Loader-neutral server-authored tuning state for one world-owned infinite thermal reservoir. */
public record ThermalReservoirSummary(
		long summaryGeneration,
		String dimensionId,
		int blockX, int blockY, int blockZ,
		double temperatureK,
		boolean temperatureValid,
		double minimumTemperatureK,
		double maximumTemperatureK
) {
	public ThermalReservoirSummary {
		if (summaryGeneration <= 0L || dimensionId == null || dimensionId.isBlank()
				|| !(Double.isFinite(minimumTemperatureK) && Double.isFinite(maximumTemperatureK)
				&& minimumTemperatureK >= ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K
				&& maximumTemperatureK > minimumTemperatureK)) {
			throw new IllegalArgumentException("Thermal reservoir summary is invalid");
		}
		boolean derivedValid = Double.isFinite(temperatureK) && temperatureK >= minimumTemperatureK
				&& temperatureK <= maximumTemperatureK;
		if (temperatureValid != derivedValid) {
			throw new IllegalArgumentException("Thermal reservoir validity flag disagrees with its setpoint/range");
		}
	}
}
