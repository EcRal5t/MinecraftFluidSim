package mmm.application.port;

import mmm.application.result.RegionStepStatus;
import mmm.fluid.FluidDomainState;
import mmm.fluid.FluidStepCandidate;
import mmm.fluid.FluidThermalStepCandidate;

import java.util.Objects;
import java.util.UUID;

/** Narrow runtime repository: immutable step capture and atomic complete-candidate adoption only. */
public interface RegionRepository {
	/** Live owner could not produce a complete immutable base; the owner itself was not mutated. */
	final class StateCaptureRejectedException extends RuntimeException {
		public StateCaptureRejectedException(String message, Throwable cause) { super(message, cause); }
	}

	Capture captureForStep(UUID regionId);
	RegionStepStatus adoptMotion(UUID regionId, FluidStepCandidate candidate, String expectedBackendId);
	RegionStepStatus adoptThermal(UUID regionId, FluidThermalStepCandidate candidate, String expectedBackendId);

	enum CaptureStatus { READY, REGION_NOT_FOUND, REGION_NOT_LOADED }

	record Capture(CaptureStatus status, long solverGeneration, FluidDomainState state) {
		public Capture {
			Objects.requireNonNull(status, "status");
			if (solverGeneration < 0L || (status == CaptureStatus.READY) != (state != null)) {
				throw new IllegalArgumentException("Region step capture status/state disagree");
			}
		}

		public static Capture ready(long solverGeneration, FluidDomainState state) {
			return new Capture(CaptureStatus.READY, solverGeneration, Objects.requireNonNull(state, "state"));
		}

		public static Capture unavailable(CaptureStatus status) {
			if (status == CaptureStatus.READY) throw new IllegalArgumentException("Ready capture requires state");
			return new Capture(status, 0L, null);
		}
	}
}
