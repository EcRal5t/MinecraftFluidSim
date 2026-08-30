package cc.ecisr.mmm.application;

import cc.ecisr.mmm.api.RegionAvailabilityReason;

import java.util.Objects;

/** Loader-neutral ordering and classification for runtime region admission facts. */
public final class RegionAdmissionService {
	public enum Disposition { AVAILABLE, PROCEED, BLOCK_IN_PLACE, UNLOAD }
	public enum BoundaryStatus { READY, CHUNK_MISSING, OPEN_BOUNDARY }

	public record CatalogFact(boolean accepted, RegionAvailabilityReason reason, String failure) {
		public CatalogFact {
			if (accepted ? reason != null || failure != null : reason == null || failure == null) {
				throw new IllegalArgumentException("Catalog admission fact is inconsistent");
			}
		}

	}

	public record InitialFacts(
			String chemistryFailure,
			String granularFailure,
			String thermalFailure,
			String numericalFailure,
			boolean controllerManaged
	) { }

	public record PolicyFacts(String ownershipFailure, boolean controllersEnabled) { }

	public record BoundaryFact(BoundaryStatus status, String failure) {
		public BoundaryFact {
			Objects.requireNonNull(status, "status");
			if ((status == BoundaryStatus.READY) != (failure == null)) {
				throw new IllegalArgumentException("Boundary admission fact is inconsistent");
			}
		}
	}

	public record Decision(
			Disposition disposition, RegionAvailabilityReason reason, String failure
	) {
		public Decision {
			Objects.requireNonNull(disposition, "disposition");
			boolean blocked = disposition == Disposition.BLOCK_IN_PLACE || disposition == Disposition.UNLOAD;
			if (blocked ? reason == null || failure == null : reason != null || failure != null) {
				throw new IllegalArgumentException("Runtime admission decision is inconsistent");
			}
		}
	}

	public Decision initial(InitialFacts facts) {
		Objects.requireNonNull(facts, "facts");
		String intrinsicFailure = firstNonNull(facts.chemistryFailure(), facts.granularFailure(),
				facts.thermalFailure(), facts.numericalFailure());
		if (intrinsicFailure != null) return blockedInPlace(RegionAvailabilityReason.NUMERICAL_PAUSE,
				intrinsicFailure);
		if (!facts.controllerManaged()) return new Decision(Disposition.AVAILABLE, null, null);
		return new Decision(Disposition.PROCEED, null, null);
	}

	public Decision policy(PolicyFacts facts) {
		Objects.requireNonNull(facts, "facts");
		if (facts.ownershipFailure() != null) return blockedInPlace(RegionAvailabilityReason.PENDING_WAL,
				facts.ownershipFailure());
		if (!facts.controllersEnabled()) return unload(RegionAvailabilityReason.POLICY_DISABLED,
				"controller admission policy is disabled");
		return new Decision(Disposition.PROCEED, null, null);
	}

	public Decision catalog(CatalogFact fact) {
		Objects.requireNonNull(fact, "fact");
		return fact.accepted() ? new Decision(Disposition.PROCEED, null, null)
				: unload(fact.reason(), fact.failure());
	}

	public Decision boundary(BoundaryFact fact) {
		Objects.requireNonNull(fact, "fact");
		return switch (fact.status()) {
			case READY -> new Decision(Disposition.PROCEED, null, null);
			case CHUNK_MISSING -> unload(RegionAvailabilityReason.CHUNK_MISSING, fact.failure());
			case OPEN_BOUNDARY -> unload(RegionAvailabilityReason.BOUNDARY_OPEN, fact.failure());
		};
	}

	private static Decision blockedInPlace(RegionAvailabilityReason reason, String failure) {
		return new Decision(Disposition.BLOCK_IN_PLACE, reason, failure);
	}

	private static Decision unload(RegionAvailabilityReason reason, String failure) {
		return new Decision(Disposition.UNLOAD, reason, failure);
	}

	private static String firstNonNull(String... values) {
		for (String value : values) if (value != null) return value;
		return null;
	}
}
