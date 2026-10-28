package mmm.thermal;

import mmm.units.SignedDyadic;
import java.util.List;
import java.util.Objects;

/** Immutable non-liquid thermal owners; liquid internal energy remains in the liquid cell inventory. */
public final class ThermalOwnerSnapshot {
	private final String registryFingerprint;
	private final double insertionTemperatureK;
	private final double[] active, deposits, gas;
	private final List<CoherentPhaseStore.Group> coherent;
	private final ThermalAuditLedger audit;
	private boolean prepared;
	public ThermalOwnerSnapshot(String registryFingerprint, double insertionTemperatureK,
			double[] active, double[] deposits, double[] gas, List<CoherentPhaseStore.Group> coherent, ThermalAuditLedger audit) {
		if (registryFingerprint == null || !registryFingerprint.matches("[0-9a-f]{64}")
				|| !(Double.isFinite(insertionTemperatureK) && insertionTemperatureK > 0))
			throw new IllegalArgumentException("Invalid thermal owner identity");
		this.registryFingerprint = registryFingerprint; this.insertionTemperatureK = insertionTemperatureK;
		this.active = finiteCopy(active); this.deposits = finiteCopy(deposits); this.gas = finiteCopy(gas);
		this.coherent = List.copyOf(coherent); this.audit = Objects.requireNonNull(audit, "Thermal audit");
	}
	static ThermalOwnerSnapshot prepared(String fingerprint,double insertion,double[] active,double[] deposits,double[] gas,
			List<CoherentPhaseStore.Group> coherent,ThermalAuditLedger audit) {
		var result = new ThermalOwnerSnapshot(fingerprint,insertion,active,deposits,gas,coherent,audit); result.prepared = true; return result;
	}
	public boolean prepared() { return prepared; }

	private static double[] finiteCopy(double[] values) {
		double[] result = values.clone();
		for (double value : result) if (!Double.isFinite(value)) throw new IllegalArgumentException("Thermal owner U is not finite");
		return result;
	}
	public String registryFingerprint() { return registryFingerprint; }
	public double insertionTemperatureK() { return insertionTemperatureK; }
	public double[] copyActiveParcelEnergy() { return active.clone(); }
	public double[] copyDepositEnergy() { return deposits.clone(); }
	public double[] copyGasNodeEnergy() { return gas.clone(); }
	public List<CoherentPhaseStore.Group> coherentGroups() { return coherent; }
	public ThermalAuditLedger audit() { return audit; }
	public void requireClosed(SignedDyadic liquidEnergy, int cellCount) {
		var total = liquidEnergy.add(SignedDyadic.sum(active)).add(SignedDyadic.sum(deposits)).add(SignedDyadic.sum(gas))
				.add(CoherentPhaseStore.exactEnergyOfCanonicalSnapshot(cellCount, ThermalLimits.MAX_COHERENT_GROUPS_PER_REGION, coherent));
		if (!audit.acceptsInventory(total)) throw new IllegalArgumentException("Thermal owners and cell inventory do not close their audit");
	}
}
