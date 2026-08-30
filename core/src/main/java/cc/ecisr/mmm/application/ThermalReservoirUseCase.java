package cc.ecisr.mmm.application;

import cc.ecisr.mmm.thermal.ThermalRegistry;
import cc.ecisr.mmm.thermal.ThermalReservoirSummary;
import cc.ecisr.mmm.thermal.WorldPositionActionSessions;

import java.util.Objects;
import java.util.UUID;

/** Loader-neutral summary and one-use authorization policy for a world-owned thermal reservoir. */
public final class ThermalReservoirUseCase {
	public enum Decision {
		AUTHORIZED,
		TOKEN_EXPIRED,
		PERMISSION_DENIED,
		WRITE_NOT_READY,
		TARGET_MISSING,
		REGISTRY_UNAVAILABLE,
		TEMPERATURE_OUT_OF_RANGE
	}

	public record Target(UUID viewerId, String dimensionId, int blockX, int blockY, int blockZ) {
		public Target {
			Objects.requireNonNull(viewerId, "viewerId");
			if (dimensionId == null || dimensionId.isBlank()) {
				throw new IllegalArgumentException("Thermal reservoir dimension is missing");
			}
		}
	}

	private final WorldPositionActionSessions sessions = new WorldPositionActionSessions();

	public ThermalReservoirSummary summary(
			Target target, double temperatureK, ThermalRegistry.TemperatureRange range
	) {
		Objects.requireNonNull(target, "target");
		Objects.requireNonNull(range, "range");
		boolean valid = range.contains(temperatureK);
		ThermalReservoirSummary validated = project(1L, target, temperatureK, valid, range);
		long generation = sessions.issue(target.viewerId(), target.dimensionId(),
				target.blockX(), target.blockY(), target.blockZ());
		return generation == 1L ? validated : project(generation, target, temperatureK, valid, range);
	}

	/** Consumes the token before evaluating mutable permission/startup/target facts, matching action semantics. */
	public Decision authorize(
			Target target, long summaryGeneration, boolean permissionGranted,
			boolean writesReady, boolean targetPresent, boolean registryAvailable
	) {
		Objects.requireNonNull(target, "target");
		if (!sessions.consume(target.viewerId(), target.dimensionId(), target.blockX(), target.blockY(),
				target.blockZ(), summaryGeneration)) return Decision.TOKEN_EXPIRED;
		if (!permissionGranted) return Decision.PERMISSION_DENIED;
		if (!writesReady) return Decision.WRITE_NOT_READY;
		if (!targetPresent) return Decision.TARGET_MISSING;
		return registryAvailable ? Decision.AUTHORIZED : Decision.REGISTRY_UNAVAILABLE;
	}

	public Decision validateSetpoint(double temperatureK, ThermalRegistry.TemperatureRange range) {
		Objects.requireNonNull(range, "range");
		return range.contains(temperatureK) ? Decision.AUTHORIZED : Decision.TEMPERATURE_OUT_OF_RANGE;
	}

	public void forgetViewer(UUID viewerId) {
		sessions.forget(viewerId);
	}

	private static ThermalReservoirSummary project(
			long generation, Target target, double temperatureK, boolean valid,
			ThermalRegistry.TemperatureRange range
	) {
		return new ThermalReservoirSummary(generation, target.dimensionId(), target.blockX(), target.blockY(),
				target.blockZ(), temperatureK, valid, range.minimumK(), range.maximumK());
	}
}
