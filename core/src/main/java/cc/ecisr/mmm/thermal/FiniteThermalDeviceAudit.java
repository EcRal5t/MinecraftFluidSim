package cc.ecisr.mmm.thermal;

import java.util.Objects;

/** Exact audit for device energy; solver exchange is signed energy entering device bodies. */
public record FiniteThermalDeviceAudit(
		SignedDyadic initialInventory,
		SignedDyadic externalSource,
		SignedDyadic externalSink,
		SignedDyadic solverExchange,
		SignedDyadic roundoffResidual
) {
	public FiniteThermalDeviceAudit {
		Objects.requireNonNull(initialInventory, "initialInventory");
		Objects.requireNonNull(externalSource, "externalSource");
		Objects.requireNonNull(externalSink, "externalSink");
		Objects.requireNonNull(solverExchange, "solverExchange");
		Objects.requireNonNull(roundoffResidual, "roundoffResidual");
	}

	public static FiniteThermalDeviceAudit initialized(SignedDyadic inventory) {
		return new FiniteThermalDeviceAudit(Objects.requireNonNull(inventory, "inventory"),
				SignedDyadic.ZERO, SignedDyadic.ZERO, SignedDyadic.ZERO, SignedDyadic.ZERO);
	}

	public FiniteThermalDeviceAudit withFluidExchange(
			SignedDyadic energyEnteringFluid, SignedDyadic exchangeRoundoff
	) {
		Objects.requireNonNull(energyEnteringFluid, "energyEnteringFluid");
		Objects.requireNonNull(exchangeRoundoff, "exchangeRoundoff");
		return new FiniteThermalDeviceAudit(initialInventory, externalSource, externalSink,
				solverExchange.subtract(energyEnteringFluid), roundoffResidual.add(exchangeRoundoff));
	}

	public SignedDyadic expectedInventory() {
		return initialInventory.add(externalSource).subtract(externalSink)
				.add(solverExchange).add(roundoffResidual);
	}
}
