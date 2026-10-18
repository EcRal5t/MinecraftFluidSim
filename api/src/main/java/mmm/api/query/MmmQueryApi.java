package mmm.api.query;

/**
 * Synchronous read-only observation API.
 * Calls are server-thread-only and never load chunks, allocate a solver, wake a region or advance physics.
 */
public interface MmmQueryApi {
	String UNIT_SYSTEM = "SI;chemical-quantum=1umol;volume-nanos=1e-9m3;SVQ=2^-27m3";

	FluidQueryResult query(FluidQueryRequest request);
}
