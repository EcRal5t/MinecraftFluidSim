package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.MmmMod;
import cc.ecisr.mmm.api.RegionAvailabilityReason;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/** Owns the mutable, world-local runtime policy and its durable publication. */
final class MinecraftRuntimePolicy {
	private final Path worldRoot;
	private final boolean configLocked;
	private final boolean liveWorldBridgeEnabled;
	private final FluidRegionManager regions;
	private FluidServerConfig config;
	private boolean controllerEnabled;
	private boolean defaultEntityPhysicsEnabled = true;
	private boolean defaultDebugOverlays;

	MinecraftRuntimePolicy(WorldStartupCoordinator.Result startup, FluidRegionManager regions) {
		Objects.requireNonNull(startup, "World startup result");
		this.worldRoot = startup.worldRoot();
		this.config = startup.config();
		this.configLocked = !startup.policyReadable();
		this.controllerEnabled = startup.policyReadable() && config.controllersEnabled();
		this.liveWorldBridgeEnabled = startup.policyReadable() && config.worldBridgeEnabled();
		this.regions = Objects.requireNonNull(regions, "Region manager");
	}

	boolean controllerEnabled() { return controllerEnabled; }
	boolean liveWorldBridgeEnabled() { return liveWorldBridgeEnabled; }
	boolean configuredWorldBridgeEnabled() { return config.worldBridgeEnabled(); }
	boolean defaultEntityPhysicsEnabled() { return defaultEntityPhysicsEnabled; }
	boolean defaultDebugOverlays() { return defaultDebugOverlays; }
	int maximumPhaseOperationsPerStep() { return config.maximumPhaseOperationsPerStep(); }
	int maximumThermalSubstepsPerStep() { return config.maximumThermalSubstepsPerStep(); }

	String setControllerEnabled(boolean enabled) {
		if (configLocked) return unreadableConfigFailure();
		FluidServerConfig replacement = config.withControllersEnabled(enabled);
		String failure = publish(replacement, "controller feature flag");
		if (failure != null) return failure;
		config = replacement;
		controllerEnabled = enabled;
		if (!enabled) for (FluidRegion region : regions.regions()) {
			if (region.controllerManaged()) region.setAvailabilityFailure(
					RegionAvailabilityReason.POLICY_DISABLED, "controller admission policy is disabled");
		}
		return null;
	}

	String setWorldBridgeForRestart(boolean enabled) {
		if (configLocked) return unreadableConfigFailure();
		FluidServerConfig replacement = config.withWorldBridgeEnabled(enabled);
		String failure = publish(replacement, "world-bridge feature flag");
		if (failure != null) return failure;
		config = replacement;
		return null;
	}

	boolean entityPhysicsEnabled() {
		FluidRegion region = regions.selected();
		return region == null ? defaultEntityPhysicsEnabled : region.entityPhysicsEnabled();
	}

	void setEntityPhysicsEnabled(boolean enabled) {
		defaultEntityPhysicsEnabled = enabled;
		FluidRegion region = regions.selected();
		if (region != null) region.setEntityPhysicsEnabled(enabled);
	}

	boolean debugOverlays() {
		FluidRegion region = regions.selected();
		return region == null ? defaultDebugOverlays : region.debugOverlays();
	}

	void setDebugOverlays(boolean enabled) {
		defaultDebugOverlays = enabled;
		FluidRegion region = regions.selected();
		if (region != null) region.setDebugOverlays(enabled);
	}

	private String publish(FluidServerConfig replacement, String description) {
		try {
			replacement.save(worldRoot);
			return null;
		} catch (IOException exception) {
			MmmMod.LOGGER.error("Could not persist Matter, Motion & Mechanics " + description, exception);
			return exception.getMessage();
		}
	}

	private static String unreadableConfigFailure() {
		return "server.properties 未通过校验；原文件已保留，请修复后重启服务器";
	}
}
