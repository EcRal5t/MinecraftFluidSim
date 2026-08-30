package cc.ecisr.mmm.validation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Static architecture gate over production sources: dependency direction, static service
 * locators, package cycles and class size. JDK-only source scanning; it never compiles or
 * executes production code. Allowlist entries must name the restructuring slice that removes
 * them; the final state is an empty allowlist.
 */
public final class ArchitectureDependencyValidation {
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
	private static final Set<String> LOADER_NEUTRAL_MODULES = Set.of(
			"api/src/main/java", "model/src/main/java", "solver/src/main/java",
			"application/src/main/java", "core/src/main/java", "gpu-probe-kernel/src/main/java");

	/** Live runtime types that persistence codecs/stores must never import. */
	private static final Set<String> LIVE_REGION_TYPES = Set.of(
			"FluidRegion", "FluidRegionLifecycle", "FluidRegionFootprint", "FluidRegionManager",
			"FluidRegionOperations", "ThermalActivity");

	/** Solver mutable domain types that UI/adapter packages must never import. */
	private static final Set<String> SOLVER_MUTABLE_TYPES = Set.of("FluidDomain", "FlipThermalState");

	/** Thermal mutable state owners that UI/adapter packages must never import (S10). */
	private static final Set<String> THERMAL_MUTABLE_OWNERS = Set.of(
			"ThermalDomainState", "LiquidEnergyColumn", "GasEnergyStore", "GranularEnergyState",
			"ThermalAuditState", "FiniteDeviceExchangeCoordinator");

	/** Classes allowed to keep a static MinecraftServer-keyed locator, with the removing slice. */
	private static final Map<String, String> LOCATOR_ALLOWLIST = Map.of(
			"MmmRuntimeRegistry", "server composition root");

	/** Temporary direct imports of the S21 god service, each named by its removal slice. */
	private static final Map<String, String> SERVICE_IMPORT_ALLOWLIST = Map.of(
			"MmmCommandHandlers", "S21");

	/** Production classes above the final 1000-line limit, with the slice that closes each. */
	private static final Map<String, String> SIZE_ALLOWLIST = Map.of(
			"FluidDomain", "S21");
	private static final int SIZE_LIMIT = 1000;
	private static final int SIZE_WARNING = 600;

	/** S17 closed the last persistence-to-live-runtime exception. */
	private static final Set<String> PERSISTENCE_RUNTIME_ALLOWLIST = Set.of();

	record SourceFile(
			String module, String simpleName, String packageName, int lines, List<String> imports, boolean hasLocator
	) { }

	private static final Pattern PACKAGE = Pattern.compile("^package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);
	/** Captures the full import name: package plus type, or a {@code package.*} wildcard. */
	private static final Pattern IMPORT = Pattern.compile("^import\\s+(?:static\\s+)?([\\w.]+(?:\\.\\*)?)\\s*;", Pattern.MULTILINE);
	private static final Pattern LOCATOR = Pattern.compile("Map<MinecraftServer[, ]");

	public static void main(String[] args) throws Exception {
		ArchitectureGateSelfCheck.run();
		Path root = Path.of(args.length > 0 ? args[0] : ".").toAbsolutePath().normalize();
		List<SourceFile> sources = scan(root);
		if (sources.isEmpty()) throw new IllegalStateException("No production sources found under " + root);

		List<String> violations = new ArrayList<>();
		checkApiPurity(sources, violations);
		checkLoaderNeutralModules(sources, violations);
		checkApplicationPurity(sources, violations);
		checkGpuProbeKernel(sources, violations);
		checkGpuProbeAdapterHostBoundary(sources, violations);
		checkPersistenceDoesNotReferenceRuntime(sources, violations);
		checkAdaptersDoNotReferenceSolverDomain(sources, violations);
		checkAdaptersDoNotImportSimulationService(sources, violations);
		checkStaticLocators(sources, violations);
		checkClassSize(sources, violations);
		checkLoaderNeutralPackageCycles(sources, violations);

		if (!violations.isEmpty()) {
			System.err.println("Architecture violations (" + violations.size() + "):");
			for (String violation : violations) System.err.println("  - " + violation);
			throw new AssertionError("Architecture gate failed with " + violations.size() + " violation(s)");
		}
		System.out.println("Architecture gate passed: " + sources.size() + " production sources scanned.");
		CarrierMutationPointInventoryValidation.main(new String[] {root.toString()});
	}

	/** Optional probe code reaches host runtime/network implementations only through its one adapter. */
	static void checkGpuProbeAdapterHostBoundary(List<SourceFile> sources, List<String> violations) {
		for (SourceFile source : sources) {
			if (!source.module().equals("gpu-probe/src/main/java")
					|| source.simpleName().equals("MmmGpuProbeHostIntegration")) continue;
			for (String imported : source.imports()) {
				if (!imported.startsWith("cc.ecisr.mmm.simulation")
						&& !imported.startsWith("cc.ecisr.mmm.network")) continue;
				violations.add("gpu-probe adapter bypasses host integration: "
						+ source.simpleName() + " -> " + imported);
			}
		}
	}

	private static List<SourceFile> scan(Path root) throws IOException {
		List<SourceFile> sources = new ArrayList<>();
		for (String relativeRoot : SOURCE_ROOTS) {
			Path sourceRoot = root.resolve(relativeRoot);
			if (!Files.isDirectory(sourceRoot)) continue;
			try (Stream<Path> files = Files.walk(sourceRoot)) {
				files.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
					String text = read(path);
					String packageName = match(PACKAGE, text);
					List<String> imports = new ArrayList<>();
					Matcher matcher = IMPORT.matcher(text);
					while (matcher.find()) imports.add(matcher.group(1));
					int lines = text.split("\n", -1).length;
					String fileName = path.getFileName().toString();
					sources.add(new SourceFile(relativeRoot, fileName.substring(0, fileName.length() - ".java".length()),
							packageName, lines, List.copyOf(imports), LOCATOR.matcher(text).find()));
				});
			}
		}
		return sources;
	}

	private static String read(Path path) {
		try {
			return Files.readString(path);
		} catch (IOException failure) {
			throw new IllegalStateException("Cannot read " + path, failure);
		}
	}

	private static String match(Pattern pattern, String text) {
		Matcher matcher = pattern.matcher(text);
		return matcher.find() ? matcher.group(1) : "";
	}

	/** Package part of a full import name: strips the trailing {@code .*} or the last segment. */
	private static String packageOf(String imported) {
		if (imported.endsWith(".*")) return imported.substring(0, imported.length() - 2);
		int dot = imported.lastIndexOf('.');
		return dot < 0 ? "" : imported.substring(0, dot);
	}

	/** api may import only JDK types and its own package. */
	private static void checkApiPurity(List<SourceFile> sources, List<String> violations) {
		for (SourceFile source : sources) {
			if (!source.module().equals("api/src/main/java")) continue;
			for (String imported : source.imports()) {
				if (imported.startsWith("java.") || imported.startsWith("cc.ecisr.mmm.api")) continue;
				violations.add("api imports non-JDK internal type: " + source.simpleName() + " -> " + imported);
			}
		}
	}

	/** Physical loader-neutral modules must not reference Minecraft, Fabric or Mojang mappings. */
	private static void checkLoaderNeutralModules(List<SourceFile> sources, List<String> violations) {
		Set<String> forbidden = Set.of("net.minecraft", "net.fabricmc", "com.mojang");
		for (SourceFile source : sources) {
			if (!LOADER_NEUTRAL_MODULES.contains(source.module())) continue;
			for (String imported : source.imports()) {
				String importedPackage = packageOf(imported);
				if (forbidden.stream().noneMatch(top ->
						importedPackage.equals(top) || importedPackage.startsWith(top + "."))) continue;
				violations.add("loader-neutral module imports loader type: "
						+ source.simpleName() + " -> " + imported);
			}
		}
	}

	/** Application commands/use cases may depend on model/solver, never Minecraft adapter or payload packages. */
	static void checkApplicationPurity(List<SourceFile> sources, List<String> violations) {
		for (SourceFile source : sources) {
			if (!source.module().equals("application/src/main/java")
					|| !source.packageName().startsWith("cc.ecisr.mmm.application")) continue;
			for (String imported : source.imports()) {
				if (imported.startsWith("cc.ecisr.mmm.simulation")
						|| imported.startsWith("cc.ecisr.mmm.network")
						|| imported.startsWith("cc.ecisr.mmm.block")
						|| imported.startsWith("cc.ecisr.mmm.item")) {
					violations.add("application imports Minecraft adapter type: "
							+ source.simpleName() + " -> " + imported);
				}
			}
		}
	}

	/** The physical gpu-probe kernel must not reach simulation/network/Fabric integration. */
	private static void checkGpuProbeKernel(List<SourceFile> sources, List<String> violations) {
		for (SourceFile source : sources) {
			if (!source.module().equals("gpu-probe-kernel/src/main/java")) continue;
			for (String imported : source.imports()) {
				if (imported.startsWith("cc.ecisr.mmm.simulation") || imported.startsWith("cc.ecisr.mmm.network")
						|| imported.startsWith("cc.ecisr.mmm.logging") || imported.startsWith("net.minecraft")
						|| imported.startsWith("net.fabricmc")) {
					violations.add("gpu-probe kernel imports integration type: "
							+ source.simpleName() + " -> " + imported);
				}
			}
		}
	}

	/** persistence codecs and stores must not hold live region runtime types. */
	static void checkPersistenceDoesNotReferenceRuntime(List<SourceFile> sources, List<String> violations) {
		for (SourceFile source : sources) {
			if (!source.module().equals("fabric/src/main/java")
					|| !source.packageName().startsWith("cc.ecisr.mmm.simulation.persistence")) {
				continue;
			}
			for (String imported : source.imports()) {
				String leaf = imported.endsWith(".*") ? "*" : imported.substring(imported.lastIndexOf('.') + 1);
				boolean referencesLiveType = LIVE_REGION_TYPES.contains(leaf)
						|| imported.equals("cc.ecisr.mmm.simulation.*");
				if (imported.startsWith("cc.ecisr.mmm.simulation.") && referencesLiveType
						&& !PERSISTENCE_RUNTIME_ALLOWLIST.contains(source.simpleName())) {
					violations.add("persistence references live runtime type: "
							+ source.simpleName() + " -> " + imported);
				}
			}
		}
	}

	/** network/command/item/client adapters must not import the mutable solver aggregate. */
	static void checkAdaptersDoNotReferenceSolverDomain(List<SourceFile> sources, List<String> violations) {
		for (SourceFile source : sources) {
			boolean adapterModule = source.module().equals("fabric/src/client/java")
					|| source.module().equals("fabric/src/main/java")
					&& (source.packageName().startsWith("cc.ecisr.mmm.network")
						|| source.packageName().startsWith("cc.ecisr.mmm.item")
						|| source.packageName().startsWith("cc.ecisr.mmm.block"));
			if (!adapterModule) continue;
			for (String imported : source.imports()) {
				String leaf = imported.endsWith(".*") ? "*" : imported.substring(imported.lastIndexOf('.') + 1);
				if (imported.startsWith("cc.ecisr.mmm.fluid.") && (SOLVER_MUTABLE_TYPES.contains(leaf)
						|| imported.equals("cc.ecisr.mmm.fluid.*"))) {
					violations.add("adapter imports solver mutable domain: "
							+ source.simpleName() + " -> " + imported);
				}
				if (imported.startsWith("cc.ecisr.mmm.thermal.") && THERMAL_MUTABLE_OWNERS.contains(leaf)) {
					violations.add("adapter imports thermal mutable owner: "
							+ source.simpleName() + " -> " + imported);
				}
			}
		}
	}

	/** External adapters must depend on runtime semantic owners, never the transitional god service. */
	static void checkAdaptersDoNotImportSimulationService(List<SourceFile> sources, List<String> violations) {
		for (SourceFile source : sources) {
			boolean guarded = source.module().equals("fabric/src/client/java")
					|| source.module().equals("gpu-probe/src/main/java")
					|| source.module().equals("fabric/src/main/java")
					&& (source.packageName().startsWith("cc.ecisr.mmm.network")
						|| source.packageName().startsWith("cc.ecisr.mmm.item")
						|| source.packageName().startsWith("cc.ecisr.mmm.block")
						|| source.packageName().startsWith("cc.ecisr.mmm.api")
						|| source.simpleName().equals("MmmCommands")
						|| source.simpleName().equals("MmmCommandHandlers"));
			if (!guarded || SERVICE_IMPORT_ALLOWLIST.containsKey(source.simpleName())) continue;
			if (source.imports().contains("cc.ecisr.mmm.simulation.FluidSimulationService")) {
				violations.add("external adapter imports simulation god service: " + source.simpleName());
			}
		}
	}

	/** Only the allowlisted composition roots may keep a MinecraftServer-keyed static map. */
	private static void checkStaticLocators(List<SourceFile> sources, List<String> violations) {
		for (SourceFile source : sources) {
			if (!source.hasLocator() || LOCATOR_ALLOWLIST.containsKey(source.simpleName())) continue;
			violations.add("static MinecraftServer locator outside allowlist: "
					+ source.simpleName() + " (" + source.module() + ")");
		}
	}

	private static void checkClassSize(List<SourceFile> sources, List<String> violations) {
		for (SourceFile source : sources) {
			if (source.lines() > SIZE_LIMIT && !SIZE_ALLOWLIST.containsKey(source.simpleName())) {
				violations.add("production class exceeds " + SIZE_LIMIT + " lines without allowlist entry: "
						+ source.simpleName() + " (" + source.lines() + " lines, " + source.module() + ")");
			} else if (source.lines() > SIZE_WARNING && !SIZE_ALLOWLIST.containsKey(source.simpleName())) {
				System.out.printf(Locale.ROOT, "architecture warning: %s has %d lines (limit %d)%n",
						source.simpleName(), source.lines(), SIZE_LIMIT);
			}
		}
	}

	private static void checkLoaderNeutralPackageCycles(List<SourceFile> sources, List<String> violations) {
		Map<String, Set<String>> graph = new TreeMap<>();
		for (SourceFile source : sources) {
			if (!LOADER_NEUTRAL_MODULES.contains(source.module())) continue;
			for (String imported : source.imports()) {
				if (!imported.startsWith("cc.ecisr.mmm")) continue;
				String target = packageOf(imported);
				if (target.equals(source.packageName()) || target.startsWith(source.packageName() + ".")) continue;
				graph.computeIfAbsent(source.packageName(), key -> new TreeSet<>()).add(target);
			}
		}
		for (Set<String> component : stronglyConnectedComponents(new TreeMap<>(graph))) {
			if (component.size() < 2) continue;
			violations.add("loader-neutral package cycle: " + String.join("<->", component));
		}
	}

	/** Tarjan SCC over package-level import edges. */
	private static List<Set<String>> stronglyConnectedComponents(Map<String, Set<String>> graph) {
		Map<String, Integer> index = new HashMap<>();
		Map<String, Integer> low = new HashMap<>();
		List<Set<String>> components = new ArrayList<>();
		List<String> stack = new ArrayList<>();
		Set<String> onStack = new TreeSet<>();
		int[] counter = {0};
		for (String node : graph.keySet()) {
			if (!index.containsKey(node)) tarjan(node, graph, index, low, stack, onStack, components, counter);
		}
		return components;
	}

	private static void tarjan(String node, Map<String, Set<String>> graph, Map<String, Integer> index,
	                           Map<String, Integer> low, List<String> stack, Set<String> onStack,
	                           List<Set<String>> components, int[] counter) {
		index.put(node, counter[0]);
		low.put(node, counter[0]);
		counter[0]++;
		stack.add(node);
		onStack.add(node);
		for (String successor : graph.getOrDefault(node, Set.of())) {
			if (!index.containsKey(successor)) {
				tarjan(successor, graph, index, low, stack, onStack, components, counter);
				low.put(node, Math.min(low.get(node), low.get(successor)));
			} else if (onStack.contains(successor)) {
				low.put(node, Math.min(low.get(node), index.get(successor)));
			}
		}
		if (low.get(node).equals(index.get(node))) {
			Set<String> component = new TreeSet<>();
			String member;
			do {
				member = stack.remove(stack.size() - 1);
				onStack.remove(member);
				component.add(member);
			} while (!member.equals(node));
			components.add(component);
		}
	}
}
