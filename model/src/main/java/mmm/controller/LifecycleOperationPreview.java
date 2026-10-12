package mmm.controller;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Bounded server-authored preview. It is an in-memory confirmation capability, never a commit point. */
public record LifecycleOperationPreview(
		UUID nonce,
		UUID playerId,
		UUID regionId,
		long regionGeneration,
		LifecycleOperation operation,
		PermissionDecision permissionDecision,
		long expiresAtServerTick,
		Removal removal,
		GasState gasState,
		int ownershipBlocks,
		List<Blocker> blockers
) {
	public static final int MAX_BLOCKERS = 16;

	public LifecycleOperationPreview {
		Objects.requireNonNull(nonce, "nonce");
		Objects.requireNonNull(playerId, "playerId");
		Objects.requireNonNull(regionId, "regionId");
		Objects.requireNonNull(operation, "operation");
		Objects.requireNonNull(permissionDecision, "permissionDecision");
		Objects.requireNonNull(removal, "removal");
		Objects.requireNonNull(gasState, "gasState");
		if (regionGeneration <= 0L || expiresAtServerTick < 0L || ownershipBlocks < 0
				|| blockers == null || blockers.size() > MAX_BLOCKERS
				|| blockers.stream().anyMatch(Objects::isNull)) {
			throw new IllegalArgumentException("Lifecycle operation preview is invalid");
		}
		blockers = List.copyOf(blockers);
	}

	public boolean ready() { return blockers.isEmpty(); }

	public enum PermissionDecision { BUILTIN_ADMIN_GRANTED, BUILTIN_ADMIN_DENIED }

	public enum Blocker {
		PERMISSION_DENIED,
		STARTUP_NOT_READY,
		REGION_UNAVAILABLE,
		REGION_UNLOADED,
		PENDING_WAL,
		PENDING_PHASE_WORK,
		AUDIT_OPEN,
		CONDENSED_NOT_EMPTY,
		GAS_NOT_EMPTY,
		FINITE_DEVICE_NOT_EMPTY,
		OWNERSHIP_NOT_RELEASED,
		QUARANTINE_UNAVAILABLE
	}

	/** Actual authoritative quantities affected if the displayed operation commits. */
	public record Removal(
			long liquidVolumeNanos,
			double liquidMassKilograms,
			long liquidSpeciesQuanta,
			long granularSolidVolumeSvq,
			double granularMassKilograms,
			long coherentSolidVolumeSvq,
			double coherentMassKilograms,
			long sealedGasStandardVolumeNanos,
			double sealedGasMassKilograms,
			long sealedGasSpeciesQuanta,
			boolean internalEnergyAvailable,
			double internalEnergyEu
	) {
		public Removal {
			if (liquidVolumeNanos < 0L || !finiteNonNegative(liquidMassKilograms) || liquidSpeciesQuanta < 0L
					|| granularSolidVolumeSvq < 0L || !finiteNonNegative(granularMassKilograms)
					|| coherentSolidVolumeSvq < 0L || !finiteNonNegative(coherentMassKilograms)
					|| sealedGasStandardVolumeNanos < 0L || !finiteNonNegative(sealedGasMassKilograms)
					|| sealedGasSpeciesQuanta < 0L
					|| internalEnergyAvailable != Double.isFinite(internalEnergyEu)) {
				throw new IllegalArgumentException("Lifecycle operation removal summary is invalid");
			}
		}

		public static Removal none() {
			return new Removal(0L, 0.0, 0L, 0L, 0.0, 0L, 0.0,
					0L, 0.0, 0L, false, Double.NaN);
		}

		private static boolean finiteNonNegative(double value) {
			return Double.isFinite(value) && value >= 0.0;
		}
	}

	/** Current sealed truth is shown even when an operation preserves it. */
	public record GasState(
			long standardVolumeNanos, double massKilograms, long speciesQuanta,
			int sealedNodes, boolean ambientBoundaryPresent
	) {
		public GasState {
			if (standardVolumeNanos < 0L || !Removal.finiteNonNegative(massKilograms)
					|| speciesQuanta < 0L || sealedNodes < 0) {
				throw new IllegalArgumentException("Lifecycle gas summary is invalid");
			}
		}
		public static GasState none() { return new GasState(0L, 0.0, 0L, 0, false); }
	}
}
