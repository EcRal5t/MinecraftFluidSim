package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.thermal.ThermalDomainState;

import cc.ecisr.mmm.thermal.ThermalCarrierContext;
import cc.ecisr.mmm.thermal.ThermalTransport;

/** Executes the finite-volume conduction part of the logical thermal axis. */
final class ThermalTransportStage {
	record Workspace(ThermalCarrierContext markers) { }

	interface Access {
		void ensureGrid();
		ThermalDomainState thermalState();
		ResolvedLiquidConductionPlan resolvedConduction(double dtSeconds);
		double faceArea(int x, int y, int z, int direction);
		ThermalTransport.FixedBoundary fixedBoundary(int x, int y, int z, int direction);
		java.util.List<cc.ecisr.mmm.thermal.ThermalCarrierTransport.Contact> carrierContacts();
	}

	private final Workspace state;
	private final Access access;

	ThermalTransportStage(Workspace state, Access access) {
		this.state = state;
		this.access = access;
	}

	ThermalTransport.Stats step(double dtSeconds) {
		ThermalDomainState thermal = access.thermalState();
		if (thermal == null) throw new IllegalStateException("Thermal state is not bound");
		try {
			var resolved = access.resolvedConduction(dtSeconds);
			if (resolved != null) return resolved.publish();
		} catch (IllegalArgumentException | IllegalStateException | ArithmeticException invalid) {
			throw new FluidDomain.ThermalTransportBlockedException(invalid.getMessage(), invalid);
		}
		access.ensureGrid();
		var contacts = access.carrierContacts();
		if (contacts != null) return thermal.stepCarrierConduction(state.markers(), contacts, dtSeconds);
		return thermal.step(state.markers(), access::faceArea, access::fixedBoundary, dtSeconds);
	}
}
