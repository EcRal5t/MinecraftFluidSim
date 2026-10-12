package mmm.controller;

/** Destructive controller operations. Every request requires a server-authored preview token. */
public enum LifecycleOperation {
	EMPTY_CONDENSED,
	VENT_SEALED_GAS,
	RELEASE_OWNERSHIP,
	REMOVE_EMPTY_REGION
}
