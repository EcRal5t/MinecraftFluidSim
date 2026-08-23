package com.example.simulation;

import com.example.granular.GranularLimits;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Properties;
import java.util.Objects;

/** World-local controller/material/world-bridge feature gates and hard region budgets. */
public record FluidServerConfig(
		boolean controllersEnabled,
		boolean materialsEnabled,
		boolean worldBridgeEnabled,
		boolean chemistryEnabled,
		boolean granularEnabled,
		boolean precipitationEnabled,
		long maximumGranularParcels,
		long maximumGranularDepositGroups,
		FluidRegionManager.Limits limits
) {
	private static final int FORMAT_VERSION = 5;
	private static final int DEFAULT_REGIONS = 4;
	private static final long DEFAULT_CELLS = 9_216L;
	private static final long DEFAULT_MARKERS = 73_728L;

	public FluidServerConfig {
		Objects.requireNonNull(limits, "limits");
		if (chemistryEnabled && !materialsEnabled) {
			throw new IllegalArgumentException("chemistryEnabled=true requires materialsEnabled=true");
		}
		if (granularEnabled && !materialsEnabled) {
			throw new IllegalArgumentException("granularEnabled=true requires materialsEnabled=true");
		}
		if (precipitationEnabled && !(granularEnabled && chemistryEnabled)) {
			throw new IllegalArgumentException("precipitationEnabled=true requires granularEnabled=true and chemistryEnabled=true");
		}
		if (maximumGranularParcels <= 0L || maximumGranularParcels > GranularLimits.MAX_ACTIVE_PARCELS_PER_SERVER
				|| maximumGranularDepositGroups <= 0L
				|| maximumGranularDepositGroups > GranularLimits.MAX_DEPOSIT_GROUPS_PER_SERVER) {
			throw new IllegalArgumentException("Granular server budgets are outside hard limits");
		}
	}

	public FluidServerConfig(
			boolean controllersEnabled, boolean materialsEnabled, boolean worldBridgeEnabled,
			boolean chemistryEnabled, FluidRegionManager.Limits limits
	) {
		this(controllersEnabled, materialsEnabled, worldBridgeEnabled, chemistryEnabled, false, false,
				GranularLimits.MAX_ACTIVE_PARCELS_PER_SERVER, GranularLimits.MAX_DEPOSIT_GROUPS_PER_SERVER, limits);
	}

	public FluidServerConfig(
			boolean controllersEnabled, boolean materialsEnabled, boolean worldBridgeEnabled,
			FluidRegionManager.Limits limits
	) {
		this(controllersEnabled, materialsEnabled, worldBridgeEnabled, false, limits);
	}

	public FluidServerConfig(boolean controllersEnabled, boolean materialsEnabled, FluidRegionManager.Limits limits) {
		this(controllersEnabled, materialsEnabled, false, false, limits);
	}

	public FluidServerConfig(boolean controllersEnabled, FluidRegionManager.Limits limits) {
		this(controllersEnabled, false, false, false, limits);
	}

	public static FluidServerConfig defaults() {
		return new FluidServerConfig(false, false, false, false, new FluidRegionManager.Limits(DEFAULT_REGIONS, DEFAULT_CELLS, DEFAULT_MARKERS,
				DEFAULT_CELLS * DEFAULT_REGIONS, DEFAULT_MARKERS * DEFAULT_REGIONS));
	}

	public static Path path(Path worldRoot) {
		return worldRoot.toAbsolutePath().normalize().resolve("realwater").resolve("server.properties");
	}

	public static FluidServerConfig loadOrCreate(Path worldRoot) throws IOException {
		Path path = path(worldRoot);
		if (!Files.exists(path)) {
			FluidServerConfig defaults = defaults();
			defaults.save(worldRoot);
			return defaults;
		}
		Properties properties = new Properties();
		try (InputStream input = Files.newInputStream(path)) { properties.load(input); }
		try {
			int version = integer(properties, "formatVersion");
			if (version < 1 || version > FORMAT_VERSION) throw new IOException("Unsupported Real Water server config version " + version);
			String controllers = required(properties, "controllersEnabled");
			if (!controllers.equals("true") && !controllers.equals("false")) throw new IOException("controllersEnabled must be true or false");
			String materials = version == 1 ? "false" : required(properties, "materialsEnabled");
			if (!materials.equals("true") && !materials.equals("false")) throw new IOException("materialsEnabled must be true or false");
			String worldBridge = version >= 3 ? required(properties, "worldBridgeEnabled") : "false";
			if (!worldBridge.equals("true") && !worldBridge.equals("false")) throw new IOException("worldBridgeEnabled must be true or false");
			String chemistry = version >= 4 ? required(properties, "chemistryEnabled") : "false";
			if (!chemistry.equals("true") && !chemistry.equals("false")) throw new IOException("chemistryEnabled must be true or false");
			String granular = version >= 5 ? required(properties, "granularEnabled") : "false";
			if (!granular.equals("true") && !granular.equals("false")) throw new IOException("granularEnabled must be true or false");
			String precipitation = version >= 5 ? required(properties, "precipitationEnabled") : "false";
			if (!precipitation.equals("true") && !precipitation.equals("false")) throw new IOException("precipitationEnabled must be true or false");
			long granularParcels = version >= 5 ? positiveLong(properties, "maximumGranularParcels")
					: GranularLimits.MAX_ACTIVE_PARCELS_PER_SERVER;
			long granularDeposits = version >= 5 ? positiveLong(properties, "maximumGranularDepositGroups")
					: GranularLimits.MAX_DEPOSIT_GROUPS_PER_SERVER;
			FluidRegionManager.Limits limits = new FluidRegionManager.Limits(
					integer(properties, "maximumRegions"), positiveLong(properties, "maximumCellsPerRegion"),
					positiveLong(properties, "maximumMarkersPerRegion"), positiveLong(properties, "maximumTotalCells"),
					positiveLong(properties, "maximumTotalMarkers"));
			return new FluidServerConfig(Boolean.parseBoolean(controllers), Boolean.parseBoolean(materials),
					Boolean.parseBoolean(worldBridge), Boolean.parseBoolean(chemistry), Boolean.parseBoolean(granular),
					Boolean.parseBoolean(precipitation), granularParcels, granularDeposits, limits);
		} catch (IllegalArgumentException exception) {
			throw new IOException("Invalid Real Water server config: " + exception.getMessage(), exception);
		}
	}

	public void save(Path worldRoot) throws IOException {
		Path target = path(worldRoot), temporary = target.resolveSibling(target.getFileName() + ".tmp");
		Files.createDirectories(target.getParent());
		Properties properties = new Properties();
		properties.setProperty("formatVersion", Integer.toString(FORMAT_VERSION));
		properties.setProperty("controllersEnabled", Boolean.toString(controllersEnabled));
		properties.setProperty("materialsEnabled", Boolean.toString(materialsEnabled));
		properties.setProperty("worldBridgeEnabled", Boolean.toString(worldBridgeEnabled));
		properties.setProperty("chemistryEnabled", Boolean.toString(chemistryEnabled));
		properties.setProperty("granularEnabled", Boolean.toString(granularEnabled));
		properties.setProperty("precipitationEnabled", Boolean.toString(precipitationEnabled));
		properties.setProperty("maximumGranularParcels", Long.toString(maximumGranularParcels));
		properties.setProperty("maximumGranularDepositGroups", Long.toString(maximumGranularDepositGroups));
		properties.setProperty("maximumRegions", Integer.toString(limits.maximumRegions()));
		properties.setProperty("maximumCellsPerRegion", Long.toString(limits.maximumCellsPerRegion()));
		properties.setProperty("maximumMarkersPerRegion", Long.toString(limits.maximumMarkersPerRegion()));
		properties.setProperty("maximumTotalCells", Long.toString(limits.maximumTotalCells()));
		properties.setProperty("maximumTotalMarkers", Long.toString(limits.maximumTotalMarkers()));
		try {
			try (OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
				properties.store(output, "Real Water world-local server configuration; budget changes apply after restart");
			}
			try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
			Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException exception) {
			try { Files.deleteIfExists(temporary); } catch (IOException cleanup) { exception.addSuppressed(cleanup); }
			throw exception;
		}
	}

	public FluidServerConfig withControllersEnabled(boolean enabled) {
		return new FluidServerConfig(enabled, materialsEnabled, worldBridgeEnabled, chemistryEnabled, granularEnabled,
				precipitationEnabled, maximumGranularParcels, maximumGranularDepositGroups, limits);
	}

	public FluidServerConfig withMaterialsEnabled(boolean enabled) {
		return new FluidServerConfig(controllersEnabled, enabled, worldBridgeEnabled, chemistryEnabled, granularEnabled,
				precipitationEnabled, maximumGranularParcels, maximumGranularDepositGroups, limits);
	}

	public FluidServerConfig withWorldBridgeEnabled(boolean enabled) {
		return new FluidServerConfig(controllersEnabled, materialsEnabled, enabled, chemistryEnabled, granularEnabled,
				precipitationEnabled, maximumGranularParcels, maximumGranularDepositGroups, limits);
	}

	public FluidServerConfig withChemistryEnabled(boolean enabled) {
		return new FluidServerConfig(controllersEnabled, materialsEnabled, worldBridgeEnabled, enabled, granularEnabled,
				precipitationEnabled, maximumGranularParcels, maximumGranularDepositGroups, limits);
	}

	public FluidServerConfig withGranularEnabled(boolean enabled) {
		return new FluidServerConfig(controllersEnabled, materialsEnabled, worldBridgeEnabled, chemistryEnabled, enabled,
				precipitationEnabled, maximumGranularParcels, maximumGranularDepositGroups, limits);
	}

	public FluidServerConfig withPrecipitationEnabled(boolean enabled) {
		return new FluidServerConfig(controllersEnabled, materialsEnabled, worldBridgeEnabled, chemistryEnabled,
				granularEnabled, enabled, maximumGranularParcels, maximumGranularDepositGroups, limits);
	}

	private static String required(Properties properties, String key) throws IOException {
		String value = properties.getProperty(key);
		if (value == null || value.isBlank()) throw new IOException("Missing Real Water config key " + key);
		return value.trim();
	}

	private static int integer(Properties properties, String key) throws IOException {
		try { return Integer.parseInt(required(properties, key)); }
		catch (NumberFormatException exception) { throw new IOException("Invalid integer for " + key, exception); }
	}

	private static long positiveLong(Properties properties, String key) throws IOException {
		try {
			long value = Long.parseLong(required(properties, key));
			if (value <= 0L) throw new IOException(key + " must be positive");
			return value;
		} catch (NumberFormatException exception) { throw new IOException("Invalid integer for " + key, exception); }
	}
}
