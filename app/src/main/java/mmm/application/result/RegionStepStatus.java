package mmm.application.result;

/** Closed application outcome for capture/evaluate/adopt. Numerical solver refusals remain typed exceptions. */
public enum RegionStepStatus {
	ACCEPTED,
	REGION_NOT_FOUND,
	REGION_NOT_LOADED,
	STALE_GENERATION,
	BACKEND_MISMATCH,
	LAYOUT_MISMATCH
}
