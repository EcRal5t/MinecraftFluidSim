package cc.ecisr.mmm.validation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Pins the carrier structural mutation inventory: marker count transitions and per-marker
 * side-column moves may only be issued by {@code CarrierMutationCoordinator}. Restore paths
 * rebuild side columns wholesale through a fresh local store or the thermal restore entry,
 * which is a replacement, not an ordinal mutation, and is deliberately absent from the rules.
 */
public final class CarrierMutationPointInventoryValidation {
	private static final String[] SOURCE_ROOTS = {
			"api/src/main/java",
			"model/src/main/java",
			"solver/src/main/java",
			"application/src/main/java",
			"core/src/main/java",
			"fabric/src/main/java",
			"fabric/src/client/java",
			"gpu-probe-kernel/src/main/java",
			"gpu-probe/src/main/java",
	};

	private static final Map<String, String> RULES = Map.ofEntries(
			Map.entry("particles.setCount(", "CarrierMutationCoordinator"),
			Map.entry("particles.incrementCount(", "CarrierMutationCoordinator"),
			Map.entry("particles.swapRemove(", "CarrierMutationCoordinator"),
			Map.entry("chemicalParcels.swapRemove(", "CarrierMutationCoordinator"),
			Map.entry("chemicalParcels.clear(", "CarrierMutationCoordinator"),
			Map.entry("chemicalParcels.assignDefault(", "CarrierMutationCoordinator"),
			Map.entry("thermalState.removeExternalAndSwap(", "CarrierMutationCoordinator"),
			Map.entry("thermalState.removeInternalAndSwap(", "CarrierMutationCoordinator"),
			Map.entry("thermalState.addExternalMarker(", "CarrierMutationCoordinator"),
			Map.entry("thermalState.addInternalMarker(", "CarrierMutationCoordinator"));

	private CarrierMutationPointInventoryValidation() { }

	public static void main(String[] args) throws Exception {
		Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
		List<String> violations = new ArrayList<>();
		for (String relativeRoot : SOURCE_ROOTS) {
			Path sourceRoot = root.resolve(relativeRoot);
			if (!Files.isDirectory(sourceRoot)) continue;
			try (Stream<Path> files = Files.walk(sourceRoot)) {
				files.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
					String text;
					try {
						text = Files.readString(path);
					} catch (IOException failure) {
						throw new IllegalStateException("Cannot read " + path, failure);
					}
					for (Map.Entry<String, String> rule : RULES.entrySet()) {
						if (!text.contains(rule.getKey())) continue;
						String simpleName = path.getFileName().toString()
								.substring(0, path.getFileName().toString().length() - ".java".length());
						if (!simpleName.equals(rule.getValue())) {
							violations.add("structural carrier call outside coordinator: "
									+ simpleName + " -> " + rule.getKey());
						}
					}
				});
			}
		}
		if (!violations.isEmpty()) {
			System.err.println("Carrier mutation inventory violations (" + violations.size() + "):");
			for (String violation : violations) System.err.println("  - " + violation);
			throw new AssertionError("Carrier mutation inventory gate failed");
		}
		System.out.println("Carrier mutation point inventory passed: all structural calls are coordinator-owned.");
	}
}
