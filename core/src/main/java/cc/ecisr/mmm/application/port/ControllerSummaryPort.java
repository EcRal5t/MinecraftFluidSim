package cc.ecisr.mmm.application.port;

import cc.ecisr.mmm.controller.ControllerSummary;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Use-case-specific bounded controller read model; it never exposes a live region or solver. */
public interface ControllerSummaryPort {
	Optional<View> read(UUID regionId);

	record View(
			UUID regionId,
			String dimensionId,
			String profileFingerprint,
			long checkpointGeneration,
			long networkGeneration,
			ControllerSummary.Footprint footprint,
			boolean selected,
			ControllerSummary.OperatorIntent operatorIntent,
			ControllerSummary.MotionState motionState,
			ControllerSummary.ThermalState thermalState,
			ControllerSummary.Availability availability,
			ControllerSummary.Usage usage,
			ControllerSummary.Inventory inventory,
			ControllerSummary.Gates gates,
			ControllerSummary.Diagnostics diagnostics
	) {
		public View {
			Objects.requireNonNull(regionId, "regionId");
			Objects.requireNonNull(dimensionId, "dimensionId");
			Objects.requireNonNull(profileFingerprint, "profileFingerprint");
			Objects.requireNonNull(footprint, "footprint");
			Objects.requireNonNull(operatorIntent, "operatorIntent");
			Objects.requireNonNull(motionState, "motionState");
			Objects.requireNonNull(thermalState, "thermalState");
			Objects.requireNonNull(availability, "availability");
			Objects.requireNonNull(usage, "usage");
			Objects.requireNonNull(inventory, "inventory");
			Objects.requireNonNull(gates, "gates");
			Objects.requireNonNull(diagnostics, "diagnostics");
		}
	}
}
