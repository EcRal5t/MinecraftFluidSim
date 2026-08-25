package cc.ecisr.mmm.api.query;

/** Closed failure/absence vocabulary; callers must not infer inventory from Minecraft block appearance. */
public enum FluidQueryStatus {
	OK,
	EMPTY,
	INVALID_REQUEST,
	WRONG_THREAD,
	NO_REGION,
	REGION_NOT_LOADED,
	REGION_UNAVAILABLE,
	OUTSIDE_INTERIOR,
	STATE_NOT_READY
}
