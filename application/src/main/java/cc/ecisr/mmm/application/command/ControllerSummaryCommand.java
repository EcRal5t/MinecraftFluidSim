package cc.ecisr.mmm.application.command;

import java.util.Objects;
import java.util.UUID;

/** Authorized controller read after the Minecraft adapter has checked viewer and block facts. */
public record ControllerSummaryCommand(UUID viewerId, UUID regionId) {
	public ControllerSummaryCommand {
		Objects.requireNonNull(viewerId, "viewerId");
		Objects.requireNonNull(regionId, "regionId");
	}
}
