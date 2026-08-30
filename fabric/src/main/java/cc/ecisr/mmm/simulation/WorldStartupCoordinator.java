package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.simulation.persistence.WorldCapabilityBundleStore;
import cc.ecisr.mmm.simulation.persistence.WorldCapabilityStartup;
import cc.ecisr.mmm.worldprofile.RegistryFingerprintSet;
import cc.ecisr.mmm.worldprofile.WorldCapability;
import cc.ecisr.mmm.worldprofile.WorldCapabilityProfile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;

/** Coordinates staged world startup without logging; typed decisions are consumed by the composition adapter. */
public final class WorldStartupCoordinator {
	private static final WorldCapabilityProfile UNPROVEN_PROFILE = WorldCapabilityProfile.of(
			EnumSet.noneOf(WorldCapability.class), new RegistryFingerprintSet("", "", "", "", ""));

	private WorldStartupCoordinator() { }

	public static Result start(Path worldRoot, WorldCapabilityStartup.DefinitionSource definitions) {
		Objects.requireNonNull(worldRoot, "World root");
		Objects.requireNonNull(definitions, "Registry definition source");
		Path normalizedRoot = worldRoot.toAbsolutePath().normalize();
		List<Issue> issues = new ArrayList<>();
		FluidServerConfig config;
		boolean policyReadable = true;
		try {
			config = FluidServerConfig.loadOrCreate(normalizedRoot);
		} catch (IOException | RuntimeException unreadablePolicy) {
			config = FluidServerConfig.defaults();
			policyReadable = false;
			issues.add(Issue.error(Stage.POLICY, Code.POLICY_UNREADABLE, message(unreadablePolicy)));
		}

		WorldCapabilityStartup.DefinitionSource validatedDefinitions = () ->
				RegistryBundleLoader.validateDefinitions(definitions.load());
		WorldCapabilityStartup.Result capability = WorldCapabilityStartup.start(
				new WorldCapabilityBundleStore(normalizedRoot, new WorldCapabilityBundleStore.QuarantineAdmission(
						config.maximumQuarantineEntries(), config.maximumQuarantineBytes())),
				validatedDefinitions, policyReadable);
		if (capability.definitionMismatch()) {
			issues.add(Issue.warning(Stage.DEFINITIONS, Code.DEFINITION_DRIFT,
					"current definitions differ from the committed world capability bundle"));
		}
		if (!capability.issue().isBlank()) {
			boolean admitted = capability.admitted();
			issues.add(new Issue(admitted ? Severity.WARNING : Severity.ERROR, Stage.CAPABILITY,
					admitted ? Code.DEFINITION_SOURCE_UNAVAILABLE : Code.CAPABILITY_UNPROVEN,
					capability.issue()));
		}

		State state = switch (capability.state()) {
			case READY -> State.READY;
			case POLICY_RESTRICTED -> State.POLICY_RESTRICTED;
			case LOCKED_PROFILE -> State.LOCKED_PROFILE;
			case UNINITIALIZED -> State.UNINITIALIZED;
		};
		RegistryBundleLoader.RegistryBundle registries = null;
		if (capability.bundle() != null) try {
			registries = RegistryBundleLoader.validateAndExtract(capability.bundle());
		} catch (RuntimeException invalidRegistryBundle) {
			state = State.LOCKED_PROFILE;
			issues.add(Issue.error(Stage.REGISTRY, Code.REGISTRY_BUNDLE_INVALID, message(invalidRegistryBundle)));
		}
		if (state != State.READY && issues.stream().noneMatch(Issue::blocksCreation)) {
			issues.add(Issue.error(Stage.CAPABILITY, Code.ADMISSION_RESTRICTED,
					"world capability startup is " + state));
		}
		return new Result(normalizedRoot, state, config, policyReadable, capability,
				registries, List.copyOf(issues));
	}

	private static String message(Throwable failure) {
		String message = failure.getMessage();
		return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
	}

	public enum State { UNINITIALIZED, READY, LOCKED_PROFILE, POLICY_RESTRICTED }
	public enum Severity { WARNING, ERROR }
	public enum Stage { POLICY, CAPABILITY, DEFINITIONS, REGISTRY }
	public enum Code {
		POLICY_UNREADABLE,
		CAPABILITY_UNPROVEN,
		ADMISSION_RESTRICTED,
		DEFINITION_DRIFT,
		DEFINITION_SOURCE_UNAVAILABLE,
		REGISTRY_BUNDLE_INVALID
	}

	public record Issue(Severity severity, Stage stage, Code code, String detail) {
		public Issue {
			Objects.requireNonNull(severity, "Startup issue severity");
			Objects.requireNonNull(stage, "Startup issue stage");
			Objects.requireNonNull(code, "Startup issue code");
			if (detail == null || detail.isBlank()) throw new IllegalArgumentException("Startup issue detail is missing");
		}

		static Issue warning(Stage stage, Code code, String detail) {
			return new Issue(Severity.WARNING, stage, code, detail);
		}

		static Issue error(Stage stage, Code code, String detail) {
			return new Issue(Severity.ERROR, stage, code, detail);
		}

		public boolean blocksCreation() { return severity == Severity.ERROR; }
	}

	public record Result(
			Path worldRoot,
			State state,
			FluidServerConfig config,
			boolean policyReadable,
			WorldCapabilityStartup.Result capability,
			RegistryBundleLoader.RegistryBundle registries,
			List<Issue> issues
	) {
		public Result {
			Objects.requireNonNull(worldRoot, "World root");
			Objects.requireNonNull(state, "World startup state");
			Objects.requireNonNull(config, "World policy");
			Objects.requireNonNull(capability, "Capability startup result");
			issues = List.copyOf(issues);
			if ((registries != null) != capability.admitted() && registries != null) {
				throw new IllegalArgumentException("Registry bundle is not backed by an admitted capability bundle");
			}
			if (state == State.READY && (!policyReadable || registries == null)) {
				throw new IllegalArgumentException("READY startup lacks readable policy or proven registries");
			}
		}

		public boolean profileProven() { return registries != null; }
		public WorldCapabilityProfile profile() {
			return registries == null ? UNPROVEN_PROFILE : registries.profile();
		}
		public boolean creationLocked() { return state != State.READY || !policyReadable; }
		public boolean authoritativeWritesReady() {
			return state == State.READY && policyReadable && registries != null;
		}
		public String blockingSummary() {
			return issues.stream().filter(Issue::blocksCreation).map(issue -> issue.code() + ": " + issue.detail())
					.findFirst().orElse("world capability startup is " + state);
		}
	}
}
