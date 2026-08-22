package com.example.simulation;

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
		FluidRegionManager.Limits limits
) {
	private static final int FORMAT_VERSION = 3;
	private static final int DEFAULT_REGIONS = 4;
	private static final long DEFAULT_CELLS = 9_216L;
	private static final long DEFAULT_MARKERS = 73_728L;

	public FluidServerConfig {
		Objects.requireNonNull(limits, "limits");
	}

	public FluidServerConfig(boolean controllersEnabled, boolean materialsEnabled, FluidRegionManager.Limits limits) {
		this(controllersEnabled, materialsEnabled, false, limits);
	}

	public FluidServerConfig(boolean controllersEnabled, FluidRegionManager.Limits limits) {
		this(controllersEnabled, false, false, limits);
	}

	public static FluidServerConfig defaults() {
		return new FluidServerConfig(false, false, false, new FluidRegionManager.Limits(DEFAULT_REGIONS, DEFAULT_CELLS, DEFAULT_MARKERS,
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
			FluidRegionManager.Limits limits = new FluidRegionManager.Limits(
					integer(properties, "maximumRegions"), positiveLong(properties, "maximumCellsPerRegion"),
					positiveLong(properties, "maximumMarkersPerRegion"), positiveLong(properties, "maximumTotalCells"),
					positiveLong(properties, "maximumTotalMarkers"));
			return new FluidServerConfig(Boolean.parseBoolean(controllers), Boolean.parseBoolean(materials),
					Boolean.parseBoolean(worldBridge), limits);
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
		return new FluidServerConfig(enabled, materialsEnabled, worldBridgeEnabled, limits);
	}

	public FluidServerConfig withMaterialsEnabled(boolean enabled) {
		return new FluidServerConfig(controllersEnabled, enabled, worldBridgeEnabled, limits);
	}

	public FluidServerConfig withWorldBridgeEnabled(boolean enabled) {
		return new FluidServerConfig(controllersEnabled, materialsEnabled, enabled, limits);
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
