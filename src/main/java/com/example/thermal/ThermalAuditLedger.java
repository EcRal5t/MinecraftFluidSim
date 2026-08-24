package com.example.thermal;

import java.util.Objects;

/** Immutable signed-energy audit. Source/sink are directions; roundoff is never reclassified as either. */
public record ThermalAuditLedger(
		SignedDyadic initialInventory,
		SignedDyadic externalSource,
		SignedDyadic externalSink,
		SignedDyadic mechanicalWork,
		SignedDyadic roundoffResidual
) {
	public ThermalAuditLedger {
		Objects.requireNonNull(initialInventory, "initialInventory");
		Objects.requireNonNull(externalSource, "externalSource");
		Objects.requireNonNull(externalSink, "externalSink");
		Objects.requireNonNull(mechanicalWork, "mechanicalWork");
		Objects.requireNonNull(roundoffResidual, "roundoffResidual");
	}

	public static ThermalAuditLedger initialized(SignedDyadic inventory) {
		return new ThermalAuditLedger(inventory, SignedDyadic.ZERO, SignedDyadic.ZERO,
				SignedDyadic.ZERO, SignedDyadic.ZERO);
	}

	public ThermalAuditLedger withRoundoff(SignedDyadic delta) {
		return new ThermalAuditLedger(initialInventory, externalSource, externalSink, mechanicalWork,
				roundoffResidual.add(delta));
	}

	public ThermalAuditLedger withMechanicalWork(SignedDyadic workIntoInventory) {
		Objects.requireNonNull(workIntoInventory, "workIntoInventory");
		return new ThermalAuditLedger(initialInventory, externalSource, externalSink,
				mechanicalWork.add(workIntoInventory), roundoffResidual);
	}

	public ThermalAuditLedger withHeatExchange(double deltaEnergy) {
		if (!Double.isFinite(deltaEnergy)) throw new IllegalArgumentException("Thermal exchange must be finite");
		if (deltaEnergy >= 0.0) return new ThermalAuditLedger(initialInventory,
				externalSource.add(SignedDyadic.of(deltaEnergy)), externalSink, mechanicalWork, roundoffResidual);
		return new ThermalAuditLedger(initialInventory, externalSource,
				externalSink.add(SignedDyadic.of(-deltaEnergy)), mechanicalWork, roundoffResidual);
	}

	public ThermalAuditLedger withHeatExchange(SignedDyadic deltaEnergy) {
		Objects.requireNonNull(deltaEnergy, "deltaEnergy");
		if (deltaEnergy.compareTo(SignedDyadic.ZERO) >= 0) return new ThermalAuditLedger(initialInventory,
				externalSource.add(deltaEnergy), externalSink, mechanicalWork, roundoffResidual);
		return new ThermalAuditLedger(initialInventory, externalSource,
				externalSink.add(deltaEnergy.negate()), mechanicalWork, roundoffResidual);
	}

	/** Material entering the ownership boundary may carry signed reference energy. */
	public ThermalAuditLedger withExternalSourceEnergy(SignedDyadic importedEnergy) {
		return new ThermalAuditLedger(initialInventory, externalSource.add(importedEnergy), externalSink,
				mechanicalWork, roundoffResidual);
	}

	/** Material leaving the ownership boundary may carry signed reference energy. */
	public ThermalAuditLedger withExternalSinkEnergy(SignedDyadic exportedEnergy) {
		return new ThermalAuditLedger(initialInventory, externalSource, externalSink.add(exportedEnergy),
				mechanicalWork, roundoffResidual);
	}

	public SignedDyadic expectedInventory() {
		return initialInventory.add(externalSource).subtract(externalSink)
				.add(mechanicalWork).add(roundoffResidual);
	}
}
