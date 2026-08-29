package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.units.SignedDyadic;
import cc.ecisr.mmm.thermal.CoherentPhaseStore;
import cc.ecisr.mmm.thermal.GranularEnergyColumn;
import cc.ecisr.mmm.thermal.ThermalAuditLedger;
import cc.ecisr.mmm.thermal.ThermalLimits;

import java.util.List;

/** Orthogonal in-memory thermal handoff; it is not a region-state or wire codec. */
public final class FlipThermalParticleState {
	private final int sizeX, sizeY, sizeZ, particleCount;
	private final float cellSize;
	private final String thermalRegistryFingerprint;
	private final double[] internalEnergy;
	private final double[] activeParcelEnergy;
	private final double[] depositEnergy;
	private final double[] gasNodeEnergy;
	private final List<CoherentPhaseStore.Group> coherentGroups;
	private final ThermalAuditLedger audit;

	public FlipThermalParticleState(
			int sizeX, int sizeY, int sizeZ, float cellSize, int particleCount,
			String thermalRegistryFingerprint, double[] internalEnergy, ThermalAuditLedger audit
	) {
		this(sizeX, sizeY, sizeZ, cellSize, particleCount, thermalRegistryFingerprint,
				internalEnergy, new double[0], new double[0], List.of(), new double[0], audit);
	}

	public FlipThermalParticleState(
			int sizeX, int sizeY, int sizeZ, float cellSize, int particleCount,
			String thermalRegistryFingerprint, double[] internalEnergy,
			List<CoherentPhaseStore.Group> coherentGroups, ThermalAuditLedger audit
	) {
		this(sizeX, sizeY, sizeZ, cellSize, particleCount, thermalRegistryFingerprint,
				internalEnergy, new double[0], new double[0], coherentGroups, new double[0], audit);
	}

	public FlipThermalParticleState(
			int sizeX, int sizeY, int sizeZ, float cellSize, int particleCount,
			String thermalRegistryFingerprint, double[] internalEnergy, double[] activeParcelEnergy,
			double[] depositEnergy,
			List<CoherentPhaseStore.Group> coherentGroups, ThermalAuditLedger audit
	) {
		this(sizeX, sizeY, sizeZ, cellSize, particleCount, thermalRegistryFingerprint, internalEnergy,
				activeParcelEnergy, depositEnergy, coherentGroups, new double[0], audit);
	}

	public FlipThermalParticleState(
			int sizeX, int sizeY, int sizeZ, float cellSize, int particleCount,
			String thermalRegistryFingerprint, double[] internalEnergy, double[] activeParcelEnergy,
			double[] depositEnergy, List<CoherentPhaseStore.Group> coherentGroups,
			double[] gasNodeEnergy, ThermalAuditLedger audit
	) {
		if (sizeX < 2 || sizeY < 2 || sizeZ < 2 || !(Float.isFinite(cellSize) && cellSize > 0.0F)
				|| particleCount < 0 || internalEnergy == null || internalEnergy.length != particleCount
				|| activeParcelEnergy == null || depositEnergy == null || coherentGroups == null || gasNodeEnergy == null) {
			throw new IllegalArgumentException("Invalid thermal particle handoff shape");
		}
		if (thermalRegistryFingerprint == null || !thermalRegistryFingerprint.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException("Invalid thermal registry fingerprint");
		}
		if (audit == null) throw new IllegalArgumentException("Thermal audit is missing");
		for (double energy : internalEnergy) if (!Double.isFinite(energy)) {
			throw new IllegalArgumentException("Thermal particle energy must be finite");
		}
		int cellCount = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
		SignedDyadic coherentEnergy = CoherentPhaseStore.exactEnergyOfCanonicalSnapshot(
				cellCount, ThermalLimits.MAX_COHERENT_GROUPS_PER_REGION, coherentGroups);
		if (activeParcelEnergy.length > cc.ecisr.mmm.granular.GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION
				|| depositEnergy.length > cc.ecisr.mmm.granular.GranularLimits.MAX_DEPOSIT_GROUPS_PER_REGION) {
			throw new IllegalArgumentException("Granular thermal handoff exceeds hard budgets");
		}
		for (double energy : activeParcelEnergy) if (!Double.isFinite(energy)) {
			throw new IllegalArgumentException("Active granular energy is not finite");
		}
		for (double energy : depositEnergy) if (!Double.isFinite(energy)) {
			throw new IllegalArgumentException("Deposit granular energy is not finite");
		}
		if (gasNodeEnergy.length > ThermalLimits.MAX_GAS_NODES_PER_REGION) {
			throw new IllegalArgumentException("Gas thermal handoff exceeds node budget");
		}
		for (double energy : gasNodeEnergy) if (!Double.isFinite(energy)) {
			throw new IllegalArgumentException("Gas node energy is not finite");
		}
		SignedDyadic inventory = SignedDyadic.sum(internalEnergy).add(coherentEnergy)
				.add(SignedDyadic.sum(activeParcelEnergy)).add(SignedDyadic.sum(depositEnergy))
				.add(SignedDyadic.sum(gasNodeEnergy));
		if (!inventory.equals(audit.expectedInventory())) {
			throw new IllegalArgumentException("Thermal carrier handoff does not close its audit ledger");
		}
		this.sizeX = sizeX; this.sizeY = sizeY; this.sizeZ = sizeZ; this.cellSize = cellSize;
		this.particleCount = particleCount;
		this.thermalRegistryFingerprint = thermalRegistryFingerprint;
		this.internalEnergy = internalEnergy.clone();
		this.activeParcelEnergy = activeParcelEnergy.clone();
		this.depositEnergy = depositEnergy.clone();
		this.gasNodeEnergy = gasNodeEnergy.clone();
		this.coherentGroups = List.copyOf(coherentGroups);
		this.audit = audit;
	}

	public void requireCompatible(int requiredX, int requiredY, int requiredZ, float requiredCellSize, int requiredCount) {
		if (sizeX != requiredX || sizeY != requiredY || sizeZ != requiredZ
				|| Float.floatToRawIntBits(cellSize) != Float.floatToRawIntBits(requiredCellSize)
				|| particleCount != requiredCount) {
			throw new IllegalArgumentException("Thermal particle handoff is incompatible with the domain");
		}
	}

	public int particleCount() { return particleCount; }
	public String thermalRegistryFingerprint() { return thermalRegistryFingerprint; }
	public double[] copyInternalEnergy() { return internalEnergy.clone(); }
	public double[] copyActiveParcelEnergy() { return activeParcelEnergy.clone(); }
	public double[] copyDepositEnergy() { return depositEnergy.clone(); }
	public double[] copyGasNodeEnergy() { return gasNodeEnergy.clone(); }
	public List<CoherentPhaseStore.Group> coherentGroups() { return coherentGroups; }
	public ThermalAuditLedger audit() { return audit; }
}
