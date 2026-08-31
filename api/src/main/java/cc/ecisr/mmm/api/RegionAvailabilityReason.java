package cc.ecisr.mmm.api;

/** Stable fail-closed region reason shared by Query, protocol and presentation adapters. */
public enum RegionAvailabilityReason {
	PROFILE_LOCKED,
	REGISTRY_MISMATCH,
	HEAD_INVALID,
	BOUNDARY_OPEN,
	CHUNK_MISSING,
	OVERLAP,
	BODY_OVERLAP,
	BUDGET,
	PENDING_WAL,
	PENDING_PHASE_WORK,
	GAS_NOT_EMPTY,
	FOREIGN_PROFILE,
	PERMISSION_DENIED,
	NUMERICAL_PAUSE,
	PERFORMANCE_PAUSE,
	POLICY_DISABLED
}
