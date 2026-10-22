package mmm.thermal;

import mmm.units.SignedDyadic;
/** Shared transport diagnostics and numerical budgets. */
public final class ThermalTransport {
	private ThermalTransport() { }
	public static final int DEFAULT_MAXIMUM_SUBSTEPS=8;
	public static final double CONDUCTION_RESOLUTION_K=.001;
	public record Stats(int substeps, int evaluatedContacts, double maximumTemperatureChangeK,
	                    double maximumAbsoluteTransferJ,
	                    SignedDyadic roundoffResidual, SignedDyadic reservoirExchange) {
		public Stats(int substeps, int evaluatedContacts, double maximumTemperatureChangeK,
		             SignedDyadic roundoffResidual, SignedDyadic reservoirExchange) {
			this(substeps, evaluatedContacts, maximumTemperatureChangeK, 0.0, roundoffResidual, reservoirExchange);
		}
	}

}
