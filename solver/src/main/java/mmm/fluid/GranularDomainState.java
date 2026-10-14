package mmm.fluid;

import mmm.granular.GranularAuditLedger;
import mmm.granular.GranularDepositStore;
import mmm.granular.GranularInventory;
import mmm.granular.GranularLimits;
import mmm.granular.GranularParcelStore;
import mmm.granular.GranularRegistry;
import mmm.granular.SolidCompositionPool;

/**
 * Sole owner of granular truth: the runtime registry, the active parcel and deposit stores,
 * the solid composition pool, the aggregated inventory and the mass/species audit ledgers.
 * Algorithm classes (phase transfer, deposit dynamics, collision, precipitation) keep
 * operating on the stores handed to them; ownership changes and audit recording flow
 * through this class. The composition pool binds later than the stores because it also
 * depends on the region chemical registry.
 */
final class GranularDomainState {
	final GranularRegistry registry;
	final GranularParcelStore parcels;
	final GranularDepositStore deposits;
	final GranularInventory inventory;
	SolidCompositionPool compositions;
	GranularAuditLedger externalSource = GranularAuditLedger.EMPTY;
	GranularAuditLedger escapedSink = GranularAuditLedger.EMPTY;

	GranularDomainState(GranularRegistry registry, int sizeX, int sizeY, int sizeZ, float cellSize) {
		int cellCount = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
		this.registry = registry;
		this.parcels = new GranularParcelStore(registry, GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION, sizeX, sizeY, sizeZ, cellSize);
		this.deposits = new GranularDepositStore(registry, cellCount,
				Math.min(GranularLimits.MAX_DEPOSIT_GROUPS_PER_REGION,
						Math.multiplyExact(cellCount, GranularLimits.MAX_DEPOSIT_GROUPS_PER_CELL)));
		this.inventory = new GranularInventory(parcels, deposits);
	}

	/** Staleness counter over granular structural mutations; lifecycle plans re-verify it before commit. */
	long mutationRevision() {
		return parcels.mutationRevision() + deposits.mutationRevision();
	}

	/** Binds the deferred composition pool once the region chemical registry is known. */
	void bindCompositions(SolidCompositionPool pool) {
		compositions = pool;
		inventory.bindSolidCompositions(pool);
	}
}
