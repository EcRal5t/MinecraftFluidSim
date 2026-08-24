package com.example.fluid;

import com.example.thermal.SignedDyadic;
import com.example.thermal.CoherentPhaseStore;
import com.example.thermal.ThermalAuditLedger;
import com.example.thermal.ThermalLimits;

import java.util.List;

/** Orthogonal in-memory M9 handoff; it is not a region-state or wire codec. */
public final class FlipThermalParticleState {
	private final int sizeX, sizeY, sizeZ, particleCount;
	private final float cellSize;
	private final String thermalRegistryFingerprint;
	private final double[] internalEnergy;
	private final List<CoherentPhaseStore.Group> coherentGroups;
	private final ThermalAuditLedger audit;

	public FlipThermalParticleState(
			int sizeX, int sizeY, int sizeZ, float cellSize, int particleCount,
			String thermalRegistryFingerprint, double[] internalEnergy, ThermalAuditLedger audit
	) {
		this(sizeX, sizeY, sizeZ, cellSize, particleCount, thermalRegistryFingerprint,
				internalEnergy, List.of(), audit);
	}

	public FlipThermalParticleState(
			int sizeX, int sizeY, int sizeZ, float cellSize, int particleCount,
			String thermalRegistryFingerprint, double[] internalEnergy,
			List<CoherentPhaseStore.Group> coherentGroups, ThermalAuditLedger audit
	) {
		if (sizeX < 2 || sizeY < 2 || sizeZ < 2 || !(Float.isFinite(cellSize) && cellSize > 0.0F)
				|| particleCount < 0 || internalEnergy == null || internalEnergy.length != particleCount
				|| coherentGroups == null) {
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
		CoherentPhaseStore canonical = new CoherentPhaseStore(
				cellCount, ThermalLimits.MAX_COHERENT_GROUPS_PER_REGION);
		canonical.restore(coherentGroups);
		SignedDyadic inventory = SignedDyadic.sum(internalEnergy).add(canonical.exactEnergy());
		if (!inventory.equals(audit.expectedInventory())) {
			throw new IllegalArgumentException("Thermal carrier handoff does not close its audit ledger");
		}
		this.sizeX = sizeX; this.sizeY = sizeY; this.sizeZ = sizeZ; this.cellSize = cellSize;
		this.particleCount = particleCount;
		this.thermalRegistryFingerprint = thermalRegistryFingerprint;
		this.internalEnergy = internalEnergy.clone();
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
	public List<CoherentPhaseStore.Group> coherentGroups() { return coherentGroups; }
	public ThermalAuditLedger audit() { return audit; }
}
