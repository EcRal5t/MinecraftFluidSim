package cc.ecisr.mmm.application.command;

import java.util.Objects;
import java.util.UUID;

/** Authorized controller read after the Minecraft adapter has checked player and block facts. */
public record ControllerSummaryCommand(UUID regionId, long summaryGeneration) {
	public ControllerSummaryCommand {
		Objects.requireNonNull(regionId, "regionId");
		if (summaryGeneration <= 0L) throw new IllegalArgumentException("Summary generation must be positive");
	}
}
