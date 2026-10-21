package mmm.validation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Verifies that production code publishes liquid quantities through the cell inventory. */
public final class LiquidInventoryAuthorityValidation {
	private static final String[] SOURCE_ROOTS = {
			"api/src/main/java",
			"model/src/main/java",
			"solver/src/main/java",
			"app/src/main/java",
			"core/src/main/java",
			"fabric/src/main/java",
			"fabric/src/client/java",
			"gpu-kernel/src/main/java",
			"gpu-probe/src/main/java",
	};

	private static final List<String> FORBIDDEN_CALLS = List.of(
			"particles.setCount(", "particles.incrementCount(", "particles.swapRemove(",
			"thermalState.removeExternalAndSwap(", "thermalState.removeInternalAndSwap(",
			"thermalState.addExternalMarker(", "thermalState.addInternalMarker(");

	private LiquidInventoryAuthorityValidation() { }

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
					for (String call : FORBIDDEN_CALLS) if (text.contains(call))
						violations.add(root.relativize(path) + " -> " + call);
				});
			}
		}
		if (!violations.isEmpty()) {
			System.err.println("Liquid authority violations (" + violations.size() + "):");
			for (String violation : violations) System.err.println("  - " + violation);
			throw new AssertionError("Liquid authority validation failed");
		}
		System.out.println("Liquid inventory authority passed: production writes use cell inventories.");
	}
}
