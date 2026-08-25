package cc.ecisr.mmm.api.query;

/**
 * Synchronous read-only observation API v1.
 * Calls are server-thread-only and never load chunks, allocate a solver, wake a region or advance physics.
 */
public interface MmmQueryApiV1 {
	String VERSION = "1";
	String UNIT_SYSTEM = "SI+mmm-quanta-v1";

	FluidQueryResult query(FluidQueryRequest request);
}
