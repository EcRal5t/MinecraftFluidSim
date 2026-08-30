package cc.ecisr.mmm.validation;

import java.util.ArrayList;
import java.util.List;

/**
 * Locks the import-boundary rules of {@link ArchitectureDependencyValidation} itself: the
 * full import name must reach the leaf-type match, so deliberately violating fixtures are
 * caught while same-package non-boundary imports stay clean. A regression back to
 * package-only capture would make those checks green on anything and fail here.
 */
final class ArchitectureGateSelfCheck {
	private ArchitectureGateSelfCheck() { }

	static void run() {
		List<ArchitectureDependencyValidation.SourceFile> sources = List.of(
				persistence("RegionCodecFixture", "cc.ecisr.mmm.simulation.FluidRegion"),
				persistence("RegionWildcardFixture", "cc.ecisr.mmm.simulation.*"),
				persistence("RegionCleanFixture", "cc.ecisr.mmm.simulation.FluidProtocolServer"),
				adapter("SolverImportFixture", "cc.ecisr.mmm.fluid.FluidDomain"),
				adapter("SolverCleanFixture", "cc.ecisr.mmm.fluid.AirNetwork"),
				adapter("ServiceImportFixture", "cc.ecisr.mmm.simulation.FluidSimulationService"),
				application("PayloadImportFixture", "cc.ecisr.mmm.network.ControllerSummaryPayload"));
		List<String> violations = new ArrayList<>();
		ArchitectureDependencyValidation.checkPersistenceDoesNotReferenceRuntime(sources, violations);
		ArchitectureDependencyValidation.checkAdaptersDoNotReferenceSolverDomain(sources, violations);
		ArchitectureDependencyValidation.checkAdaptersDoNotImportSimulationService(sources, violations);
		ArchitectureDependencyValidation.checkApplicationPurity(sources, violations);

		List<String> expected = List.of(
				"persistence references live runtime type: RegionCodecFixture",
				"persistence references live runtime type: RegionWildcardFixture",
				"adapter imports solver mutable domain: SolverImportFixture",
				"external adapter imports simulation god service: ServiceImportFixture",
				"application imports Minecraft adapter type: PayloadImportFixture");
		if (violations.size() != expected.size()) {
			throw new AssertionError("Architecture gate self-check expected " + expected.size()
					+ " fixture violations but found " + violations.size() + ": " + violations);
		}
		for (String expectedPrefix : expected) {
			if (violations.stream().noneMatch(violation -> violation.startsWith(expectedPrefix))) {
				throw new AssertionError("Architecture gate self-check missed fixture violation: "
						+ expectedPrefix + "; got " + violations);
			}
		}
	}

	private static ArchitectureDependencyValidation.SourceFile persistence(
			String simpleName, String imported) {
		return new ArchitectureDependencyValidation.SourceFile("fabric/src/main/java", simpleName,
				"cc.ecisr.mmm.simulation.persistence", 10, List.of(imported), false);
	}

	private static ArchitectureDependencyValidation.SourceFile adapter(
			String simpleName, String imported) {
		return new ArchitectureDependencyValidation.SourceFile("fabric/src/main/java", simpleName,
				"cc.ecisr.mmm.network", 10, List.of(imported), false);
	}

	private static ArchitectureDependencyValidation.SourceFile application(
			String simpleName, String imported) {
		return new ArchitectureDependencyValidation.SourceFile("core/src/main/java", simpleName,
				"cc.ecisr.mmm.application", 10, List.of(imported), false);
	}
}
