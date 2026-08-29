package cc.ecisr.mmm.thermal;

import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * Owns the thermal audit ledger of one region and the monotonic thermal revision that
 * thermal-bearing plans bind for staleness. Every ledger mutation is one atomic
 * replacement, so a plan can never observe an intermediate ledger.
 */
public final class ThermalAuditState {
	private ThermalAuditLedger ledger;
	private long revision;

	public ThermalAuditState(ThermalAuditLedger initial) {
		ledger = Objects.requireNonNull(initial, "initial");
	}

	public ThermalAuditLedger ledger() { return ledger; }

	public long revision() { return revision; }

	/** Applies one ledger mutation as a single replacement, preserving the exact chain. */
	public void update(UnaryOperator<ThermalAuditLedger> mutation) {
		ThermalAuditLedger next = mutation.apply(ledger);
		replace(next);
	}

	/** Replaces the whole ledger wholesale (restore path). */
	public void replace(ThermalAuditLedger next) {
		if (next == null) throw new IllegalArgumentException("Thermal audit ledger is missing");
		if (next != ledger) revision++;
		ledger = next;
	}
}
