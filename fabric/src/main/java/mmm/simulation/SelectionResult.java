package mmm.simulation;

import java.util.UUID;

/** Exact result of resolving a unique region UUID prefix. */
public record SelectionResult(boolean succeeded, UUID regionId, String failure) {
	static SelectionResult failed(String failure) {
		return new SelectionResult(false, null, failure);
	}
}
