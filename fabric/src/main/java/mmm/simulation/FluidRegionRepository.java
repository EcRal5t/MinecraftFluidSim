package mmm.simulation;

import mmm.application.port.RegionRepository;
import mmm.application.result.RegionStepStatus;
import mmm.fluid.FluidStepCandidate;
import mmm.fluid.FluidThermalStepCandidate;

import java.util.Objects;
import java.util.UUID;

/** Server-thread adapter for immutable solver capture and complete candidate adoption. */
final class FluidRegionRepository implements RegionRepository {
	private final FluidRegionManager regions;

	FluidRegionRepository(FluidRegionManager regions) {
		this.regions = Objects.requireNonNull(regions, "regions");
	}

	@Override public Capture captureForStep(UUID regionId) {
		FluidRegion region = regions.region(regionId);
		if (region == null) return Capture.unavailable(CaptureStatus.REGION_NOT_FOUND);
		if (!region.solverLoaded()) return Capture.unavailable(CaptureStatus.REGION_NOT_LOADED);
		return Capture.ready(region.solverGeneration(), region.domain().captureAuthoritativeState());
	}

	@Override public RegionStepStatus adoptMotion(
			UUID regionId, FluidStepCandidate candidate, String expectedBackendId
	) {
		FluidRegion region = regions.region(regionId);
		RegionStepStatus rejected = validate(region, candidate.backendId(), candidate.baseGeneration(),
				candidate.candidateGeneration(), candidate.layout(), expectedBackendId);
		if (rejected != null) return rejected;
		region.adoptStepCandidate(candidate, expectedBackendId);
		return RegionStepStatus.ACCEPTED;
	}

	@Override public RegionStepStatus adoptThermal(
			UUID regionId, FluidThermalStepCandidate candidate, String expectedBackendId
	) {
		FluidRegion region = regions.region(regionId);
		RegionStepStatus rejected = validate(region, candidate.backendId(), candidate.baseGeneration(),
				candidate.candidateGeneration(), candidate.layout(), expectedBackendId);
		if (rejected != null) return rejected;
		region.adoptThermalStepCandidate(candidate, expectedBackendId);
		return RegionStepStatus.ACCEPTED;
	}

	private static RegionStepStatus validate(
			FluidRegion region, String backendId, long baseGeneration, long candidateGeneration,
			mmm.fluid.FluidDomainState.Layout candidateLayout, String expectedBackendId
	) {
		if (region == null) return RegionStepStatus.REGION_NOT_FOUND;
		if (!region.solverLoaded()) return RegionStepStatus.REGION_NOT_LOADED;
		if (!Objects.equals(expectedBackendId, backendId)) return RegionStepStatus.BACKEND_MISMATCH;
		if (baseGeneration != region.solverGeneration()
				|| candidateGeneration != Math.addExact(region.solverGeneration(), 1L)) {
			return RegionStepStatus.STALE_GENERATION;
		}
		return candidateLayout.equals(region.domain().authoritativeLayout())
				? null : RegionStepStatus.LAYOUT_MISMATCH;
	}
}
