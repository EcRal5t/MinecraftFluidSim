package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.granular.GranularLimits;
import cc.ecisr.mmm.thermal.ThermalLimits;
import cc.ecisr.mmm.thermal.ThermalTransport;

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
import java.util.Set;

/**
 * World-local admission policy and hard runtime budgets.
 *
 * <p>Inventory layout is not configurable here: the committed world capability bundle decides which
 * capabilities, registries and region layout a world carries. This file only decides whether the world
 * admits controllers and how much it may spend doing so.
 */
public record FluidServerConfig(
		boolean controllersEnabled,
		boolean worldBridgeEnabled,
		long maximumGranularParcels,
		long maximumGranularDepositGroups,
		int maximumThermalSubstepsPerStep,
		int maximumPhaseOperationsPerStep,
		long maximumQuarantineEntries,
		long maximumQuarantineBytes,
		FluidRegionManager.Limits limits
) {
	private static final String SCHEMA = "controller-policy";
	private static final int DEFAULT_REGIONS = 4;
	private static final long DEFAULT_CELLS = 9_216L;
	private static final long DEFAULT_MARKERS = 73_728L;
	private static final long DEFAULT_QUARANTINE_ENTRIES = 4_096L;
	private static final long DEFAULT_QUARANTINE_BYTES = 1_073_741_824L;
	private static final long MAXIMUM_QUARANTINE_ENTRIES = 1_000_000L;
	private static final long MAXIMUM_QUARANTINE_BYTES = 1L << 40;
	private static final Set<String> KEYS = Set.of(
			"schema", "controllersEnabled", "worldBridgeEnabled", "maximumGranularParcels",
			"maximumGranularDepositGroups", "maximumThermalSubstepsPerStep", "maximumPhaseOperationsPerStep",
			"maximumQuarantineEntries", "maximumQuarantineBytes", "maximumRegions", "maximumCellsPerRegion",
			"maximumMarkersPerRegion", "maximumTotalCells", "maximumTotalMarkers");

	public FluidServerConfig {
		Objects.requireNonNull(limits, "limits");
		if (maximumGranularParcels <= 0L || maximumGranularParcels > GranularLimits.MAX_ACTIVE_PARCELS_PER_SERVER
				|| maximumGranularDepositGroups <= 0L
				|| maximumGranularDepositGroups > GranularLimits.MAX_DEPOSIT_GROUPS_PER_SERVER) {
			throw new IllegalArgumentException("Granular server budgets are outside hard limits");
		}
		if (maximumThermalSubstepsPerStep <= 0
				|| maximumThermalSubstepsPerStep > ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS
				|| maximumPhaseOperationsPerStep <= 0
				|| maximumPhaseOperationsPerStep > ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP) {
			throw new IllegalArgumentException("Thermal server budgets are outside hard limits");
		}
		if (maximumQuarantineEntries < 0L || maximumQuarantineEntries > MAXIMUM_QUARANTINE_ENTRIES
				|| maximumQuarantineBytes < 0L || maximumQuarantineBytes > MAXIMUM_QUARANTINE_BYTES) {
			throw new IllegalArgumentException("Quarantine admission budgets are outside hard limits");
		}
	}

	public FluidServerConfig(boolean controllersEnabled, boolean worldBridgeEnabled, FluidRegionManager.Limits limits) {
		this(controllersEnabled, worldBridgeEnabled, GranularLimits.MAX_ACTIVE_PARCELS_PER_SERVER,
				GranularLimits.MAX_DEPOSIT_GROUPS_PER_SERVER, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS,
				ThermalLimits.MAX_PHASE_OPERATIONS_PER_STEP, DEFAULT_QUARANTINE_ENTRIES,
				DEFAULT_QUARANTINE_BYTES, limits);
	}

	public static FluidServerConfig defaults() {
		return new FluidServerConfig(true, false, new FluidRegionManager.Limits(DEFAULT_REGIONS, DEFAULT_CELLS,
				DEFAULT_MARKERS, DEFAULT_CELLS * DEFAULT_REGIONS, DEFAULT_MARKERS * DEFAULT_REGIONS));
	}

	public static Path path(Path worldRoot) {
		return worldRoot.toAbsolutePath().normalize().resolve("mmm").resolve("server.properties");
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
			String schema = required(properties, "schema");
			if (!schema.equals(SCHEMA)) {
				throw new IOException("Unsupported Matter, Motion & Mechanics server config schema " + schema);
			}
			for (String key : properties.stringPropertyNames()) {
				if (!KEYS.contains(key)) throw new IOException("Unknown Matter, Motion & Mechanics config key " + key);
			}
			long granularParcels = positiveLong(properties, "maximumGranularParcels");
			long granularDeposits = positiveLong(properties, "maximumGranularDepositGroups");
			int thermalSubsteps = positiveInteger(properties, "maximumThermalSubstepsPerStep");
			int phaseOperations = positiveInteger(properties, "maximumPhaseOperationsPerStep");
			long quarantineEntries = nonNegativeLong(properties, "maximumQuarantineEntries");
			long quarantineBytes = nonNegativeLong(properties, "maximumQuarantineBytes");
			FluidRegionManager.Limits limits = new FluidRegionManager.Limits(
					integer(properties, "maximumRegions"), positiveLong(properties, "maximumCellsPerRegion"),
					positiveLong(properties, "maximumMarkersPerRegion"), positiveLong(properties, "maximumTotalCells"),
					positiveLong(properties, "maximumTotalMarkers"));
			return new FluidServerConfig(flag(properties, "controllersEnabled"), flag(properties, "worldBridgeEnabled"),
					granularParcels, granularDeposits, thermalSubsteps, phaseOperations, quarantineEntries,
					quarantineBytes, limits);
		} catch (IllegalArgumentException exception) {
			throw new IOException("Invalid Matter, Motion & Mechanics server config: " + exception.getMessage(), exception);
		}
	}

	public void save(Path worldRoot) throws IOException {
		Path target = path(worldRoot), temporary = target.resolveSibling(target.getFileName() + ".tmp");
		Files.createDirectories(target.getParent());
		Properties properties = new Properties();
		properties.setProperty("schema", SCHEMA);
		properties.setProperty("controllersEnabled", Boolean.toString(controllersEnabled));
		properties.setProperty("worldBridgeEnabled", Boolean.toString(worldBridgeEnabled));
		properties.setProperty("maximumGranularParcels", Long.toString(maximumGranularParcels));
		properties.setProperty("maximumGranularDepositGroups", Long.toString(maximumGranularDepositGroups));
		properties.setProperty("maximumThermalSubstepsPerStep", Integer.toString(maximumThermalSubstepsPerStep));
		properties.setProperty("maximumPhaseOperationsPerStep", Integer.toString(maximumPhaseOperationsPerStep));
		properties.setProperty("maximumQuarantineEntries", Long.toString(maximumQuarantineEntries));
		properties.setProperty("maximumQuarantineBytes", Long.toString(maximumQuarantineBytes));
		properties.setProperty("maximumRegions", Integer.toString(limits.maximumRegions()));
		properties.setProperty("maximumCellsPerRegion", Long.toString(limits.maximumCellsPerRegion()));
		properties.setProperty("maximumMarkersPerRegion", Long.toString(limits.maximumMarkersPerRegion()));
		properties.setProperty("maximumTotalCells", Long.toString(limits.maximumTotalCells()));
		properties.setProperty("maximumTotalMarkers", Long.toString(limits.maximumTotalMarkers()));
		try {
			try (OutputStream output = Files.newOutputStream(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
				properties.store(output, "Matter, Motion & Mechanics world-local admission policy; budget changes apply after restart");
			}
			try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
			Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException exception) {
			try { Files.deleteIfExists(temporary); } catch (IOException cleanup) { exception.addSuppressed(cleanup); }
			throw exception;
		}
	}

	public FluidServerConfig withControllersEnabled(boolean enabled) {
		return new FluidServerConfig(enabled, worldBridgeEnabled, maximumGranularParcels, maximumGranularDepositGroups,
				maximumThermalSubstepsPerStep, maximumPhaseOperationsPerStep, maximumQuarantineEntries,
				maximumQuarantineBytes, limits);
	}

	public FluidServerConfig withWorldBridgeEnabled(boolean enabled) {
		return new FluidServerConfig(controllersEnabled, enabled, maximumGranularParcels, maximumGranularDepositGroups,
				maximumThermalSubstepsPerStep, maximumPhaseOperationsPerStep, maximumQuarantineEntries,
				maximumQuarantineBytes, limits);
	}

	private static boolean flag(Properties properties, String key) throws IOException {
		String value = required(properties, key);
		if (!value.equals("true") && !value.equals("false")) throw new IOException(key + " must be true or false");
		return Boolean.parseBoolean(value);
	}

	private static String required(Properties properties, String key) throws IOException {
		String value = properties.getProperty(key);
		if (value == null || value.isBlank()) throw new IOException("Missing Matter, Motion & Mechanics config key " + key);
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

	private static long nonNegativeLong(Properties properties, String key) throws IOException {
		try {
			long value = Long.parseLong(required(properties, key));
			if (value < 0L) throw new IOException(key + " must not be negative");
			return value;
		} catch (NumberFormatException exception) { throw new IOException("Invalid integer for " + key, exception); }
	}

	private static int positiveInteger(Properties properties, String key) throws IOException {
		int value = integer(properties, key);
		if (value <= 0) throw new IOException(key + " must be positive");
		return value;
	}
}
