package mmm.controller;

/** Ordinary controller-console actions; destructive lifecycle operations use a separate preview contract. */
public enum ControllerConsoleAction {
	SELECT,
	PAUSE,
	RESUME,
	REFRESH_AVAILABILITY
}
