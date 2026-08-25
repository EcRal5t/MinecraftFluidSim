package cc.ecisr.mmm.api.query;

/**
 * Synchronous read-only observation API.
 * Calls are server-thread-only and never load chunks, allocate a solver, wake a region or advance physics.
 */
public interface MmmQueryApi {
	String UNIT_SYSTEM = "SI+mmm-quanta";

	FluidQueryResult query(FluidQueryRequest request);
}
