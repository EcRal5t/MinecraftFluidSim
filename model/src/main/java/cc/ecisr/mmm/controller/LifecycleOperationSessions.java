package cc.ecisr.mmm.controller;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Owner-thread, non-persistent confirmation tokens for destructive lifecycle operations. */
public final class LifecycleOperationSessions {
	private final Map<UUID, Entry> issued = new HashMap<>();

	public LifecycleOperationPreview issue(Context context, long expiresAtServerTick,
			LifecycleOperationPreview.Removal removal, LifecycleOperationPreview.GasState gasState, int ownershipBlocks,
			java.util.List<LifecycleOperationPreview.Blocker> blockers) {
		if (context == null || expiresAtServerTick < context.serverTick()) {
			throw new IllegalArgumentException("Lifecycle preview expiry is invalid");
		}
		UUID nonce = UUID.randomUUID();
		LifecycleOperationPreview preview = new LifecycleOperationPreview(nonce, context.playerId(), context.regionId(),
				context.regionGeneration(), context.operation(), context.permissionDecision(), expiresAtServerTick,
				removal, gasState, ownershipBlocks, blockers);
		issued.put(nonce, new Entry(context, preview));
		return preview;
	}

	/** Wrong viewers cannot consume another player's token. Owner attempts consume or invalidate it exactly once. */
	public boolean consume(UUID nonce, Context current) {
		if (nonce == null || current == null) return false;
		Entry entry = issued.get(nonce);
		if (entry == null || !entry.context.playerId().equals(current.playerId())
				|| !entry.context.regionId().equals(current.regionId())) return false;
		issued.remove(nonce);
		return entry.context.sameCommitBoundary(current)
				&& current.serverTick() <= entry.preview.expiresAtServerTick();
	}

	public void forget(UUID playerId) {
		if (playerId == null) throw new IllegalArgumentException("Lifecycle preview player is missing");
		issued.values().removeIf(entry -> entry.context.playerId().equals(playerId));
	}

	public record Context(
			UUID playerId,
			UUID regionId,
			long regionGeneration,
			LifecycleOperation operation,
			LifecycleOperationPreview.PermissionDecision permissionDecision,
			boolean pendingWal,
			String availabilityIdentity,
			long serverTick
	) {
		public Context {
			if (playerId == null || regionId == null || regionGeneration <= 0L || operation == null
					|| permissionDecision == null || availabilityIdentity == null || serverTick < 0L) {
				throw new IllegalArgumentException("Lifecycle operation context is invalid");
			}
		}

		boolean sameCommitBoundary(Context other) {
			return regionGeneration == other.regionGeneration && operation == other.operation
					&& permissionDecision == other.permissionDecision && pendingWal == other.pendingWal
					&& availabilityIdentity.equals(other.availabilityIdentity);
		}
	}

	private record Entry(Context context, LifecycleOperationPreview preview) { }
}
