package mmm.fluid;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import mmm.world.MovingBodyFluidCoupling;

/** Accepted prescribed bodies; occupancy and face motion are derived from the same immutable table. */
public record EntityBodyState(List<MovingBodyFluidCoupling.Request> bodies) {
	public static final EntityBodyState EMPTY = new EntityBodyState(List.of());

	public EntityBodyState {
		Objects.requireNonNull(bodies, "Entity body state is missing");
		if (bodies.size() > MovingBodyFluidCoupling.MAXIMUM_REQUESTS_PER_STEP)
			throw new IllegalArgumentException("Entity body state exceeds the region body budget");
		var ordered = new ArrayList<>(List.copyOf(bodies));
		ordered.sort(Comparator.comparing(MovingBodyFluidCoupling.Request::bodyId));
		for (int i = 1; i < ordered.size(); i++) if (ordered.get(i - 1).bodyId().equals(ordered.get(i).bodyId()))
			throw new IllegalArgumentException("Duplicate body identity: " + ordered.get(i).bodyId());
		bodies = List.copyOf(ordered);
	}
}
