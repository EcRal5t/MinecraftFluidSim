package mmm.api.query;

import java.util.UUID;

/** World-space point query. A null region id means resolve the unique loaded owner at the point. */
public record FluidQueryRequest(
		String dimensionId,
		UUID regionId,
		double worldXMetres,
		double worldYMetres,
		double worldZMetres
) { }
