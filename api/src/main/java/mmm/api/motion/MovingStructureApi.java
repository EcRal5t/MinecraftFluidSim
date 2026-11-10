package mmm.api.motion;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Prescribed axis-aligned translation observed on the server thread. Each accepted motion step
 * consumes at most its duration from the current segment. The resulting velocity describes the
 * prescribed step, while the provider remains free to move independently. A provider retains the
 * same revision until accepted, then supplies a strictly greater revision starting at the accepted
 * endpoint. Missing observations preserve the last accepted geometry and report lag.
 */
public interface MovingStructureApi {
	void register(String providerId, Provider provider);
	Status status(String providerId, UUID regionId);

	@FunctionalInterface
	interface Provider {
		/** Complete current structures for this dimension, including retired structures until accepted. */
		List<Structure> sample(String dimensionId);
	}

	/**
	 * Source and target are world-space translations of fixed local boxes. The source of a new
	 * segment must match the accepted endpoint. Initial structures start wholly outside the region;
	 * retired structures end wholly outside it. Static assembly and rotation are unsupported.
	 */
	record Structure(UUID id, UUID entityId, long revision, Pose source, Pose target,
			double durationSeconds, List<Piece> pieces, boolean retired) {
		public Structure { pieces = List.copyOf(pieces); }
	}

	/** World translation; local boxes are world-axis-aligned and false rejects rotation. */
	record Pose(double x, double y, double z, boolean axisAligned) { }
	record Piece(String key, Box bounds) { }
	record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) { }

	enum State { WAITING, ACCEPTED, LAGGING, BUSY, REJECTED }
	record Progress(long revision, Pose accepted, double fraction) { }
	record Status(State state, String detail, Map<UUID, Progress> structures) {
		public Status { structures = Map.copyOf(structures); }
	}
}
