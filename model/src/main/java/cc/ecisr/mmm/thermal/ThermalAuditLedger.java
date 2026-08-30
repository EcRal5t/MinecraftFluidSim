package cc.ecisr.mmm.thermal;

import cc.ecisr.mmm.units.SignedDyadic;

import java.util.Objects;

/** Immutable signed-energy audit. Source/sink are directions; roundoff is never reclassified as either. */
public record ThermalAuditLedger(
		SignedDyadic initialInventory,
		SignedDyadic externalSource,
		SignedDyadic externalSink,
		SignedDyadic mechanicalWork,
		SignedDyadic finiteDeviceExchange,
		SignedDyadic roundoffResidual
) {
	public ThermalAuditLedger {
		Objects.requireNonNull(initialInventory, "initialInventory");
		Objects.requireNonNull(externalSource, "externalSource");
		Objects.requireNonNull(externalSink, "externalSink");
		Objects.requireNonNull(mechanicalWork, "mechanicalWork");
		Objects.requireNonNull(finiteDeviceExchange, "finiteDeviceExchange");
		Objects.requireNonNull(roundoffResidual, "roundoffResidual");
	}

	public static ThermalAuditLedger initialized(SignedDyadic inventory) {
		return new ThermalAuditLedger(inventory, SignedDyadic.ZERO, SignedDyadic.ZERO,
				SignedDyadic.ZERO, SignedDyadic.ZERO, SignedDyadic.ZERO);
	}

	public ThermalAuditLedger withRoundoff(SignedDyadic delta) {
		return new ThermalAuditLedger(initialInventory, externalSource, externalSink, mechanicalWork,
				finiteDeviceExchange,
				roundoffResidual.add(delta));
	}

	public ThermalAuditLedger withMechanicalWork(SignedDyadic workIntoInventory) {
		Objects.requireNonNull(workIntoInventory, "workIntoInventory");
		return new ThermalAuditLedger(initialInventory, externalSource, externalSink,
				mechanicalWork.add(workIntoInventory), finiteDeviceExchange, roundoffResidual);
	}

	public ThermalAuditLedger withHeatExchange(double deltaEnergy) {
		if (!Double.isFinite(deltaEnergy)) throw new IllegalArgumentException("Thermal exchange must be finite");
		if (deltaEnergy >= 0.0) return new ThermalAuditLedger(initialInventory,
				externalSource.add(SignedDyadic.of(deltaEnergy)), externalSink, mechanicalWork,
				finiteDeviceExchange, roundoffResidual);
		return new ThermalAuditLedger(initialInventory, externalSource,
				externalSink.add(SignedDyadic.of(-deltaEnergy)), mechanicalWork,
				finiteDeviceExchange, roundoffResidual);
	}

	public ThermalAuditLedger withHeatExchange(SignedDyadic deltaEnergy) {
		Objects.requireNonNull(deltaEnergy, "deltaEnergy");
		if (deltaEnergy.compareTo(SignedDyadic.ZERO) >= 0) return new ThermalAuditLedger(initialInventory,
				externalSource.add(deltaEnergy), externalSink, mechanicalWork, finiteDeviceExchange, roundoffResidual);
		return new ThermalAuditLedger(initialInventory, externalSource,
				externalSink.add(deltaEnergy.negate()), mechanicalWork, finiteDeviceExchange, roundoffResidual);
	}

	/** Material entering the ownership boundary may carry signed reference energy. */
	public ThermalAuditLedger withExternalSourceEnergy(SignedDyadic importedEnergy) {
		return new ThermalAuditLedger(initialInventory, externalSource.add(importedEnergy), externalSink,
				mechanicalWork, finiteDeviceExchange, roundoffResidual);
	}

	/** Material leaving the ownership boundary may carry signed reference energy. */
	public ThermalAuditLedger withExternalSinkEnergy(SignedDyadic exportedEnergy) {
		return new ThermalAuditLedger(initialInventory, externalSource, externalSink.add(exportedEnergy),
				mechanicalWork, finiteDeviceExchange, roundoffResidual);
	}

	public ThermalAuditLedger withFiniteDeviceExchange(SignedDyadic energyEnteringFluid) {
		Objects.requireNonNull(energyEnteringFluid, "energyEnteringFluid");
		return new ThermalAuditLedger(initialInventory, externalSource, externalSink, mechanicalWork,
				finiteDeviceExchange.add(energyEnteringFluid), roundoffResidual);
	}

	public SignedDyadic expectedInventory() {
		return initialInventory.add(externalSource).subtract(externalSink)
				.add(mechanicalWork).add(finiteDeviceExchange).add(roundoffResidual);
	}
}
