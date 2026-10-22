package mmm.client;

import mmm.MmmMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Player-local display preferences shared by the console and all fluid surfaces. */
final class FluidDisplaySettings {
	static final FluidDisplaySettings INSTANCE = new FluidDisplaySettings();
	enum Mode { COMPOSITION, PH, TEMPERATURE }
	record View(Mode mode, double minimumK, double maximumK) { }
	private Path path;
	private volatile View view = new View(Mode.COMPOSITION, 273.15, 373.15);

	private FluidDisplaySettings() { }

	void load() {
		path = FabricLoader.getInstance().getConfigDir().resolve("mmm-fluid-display.properties");
		if (!Files.exists(path)) return;
		Properties values = new Properties();
		try (var input = Files.newInputStream(path)) {
			values.load(input);
			View loaded = new View(Mode.valueOf(values.getProperty("mode", "COMPOSITION")),
					Double.parseDouble(values.getProperty("minimumK", "273.15")),
					Double.parseDouble(values.getProperty("maximumK", "373.15")));
			if (validRange(loaded.minimumK(), loaded.maximumK())) view = loaded;
		} catch (IOException | IllegalArgumentException invalid) {
			MmmMod.LOGGER.warn("Cannot load fluid display preferences from {}", path, invalid);
		}
	}

	View view() { return view; }

	void setMode(Mode mode) { update(new View(mode, view.minimumK(), view.maximumK())); }
	void setTemperatureRange(double minimumK, double maximumK) {
		if (!validRange(minimumK, maximumK)) throw new IllegalArgumentException("Invalid temperature colour range");
		update(new View(view.mode(), minimumK, maximumK));
	}
	static boolean validRange(double minimumK, double maximumK) {
		return Double.isFinite(minimumK) && Double.isFinite(maximumK) && minimumK > 0 && maximumK > minimumK;
	}

	private void update(View next) {
		if (next.equals(view)) return;
		view = next;
		if (path == null) return;
		Properties values = new Properties();
		values.setProperty("mode", next.mode().name());
		values.setProperty("minimumK", Double.toString(next.minimumK()));
		values.setProperty("maximumK", Double.toString(next.maximumK()));
		try {
			Files.createDirectories(path.getParent());
			try (var output = Files.newOutputStream(path)) { values.store(output, "Fluid display preferences"); }
		} catch (IOException failure) {
			MmmMod.LOGGER.warn("Cannot save fluid display preferences to {}", path, failure);
		}
	}
}
