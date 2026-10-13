package mmm.fluid;

import mmm.thermal.ThermalDomainState;

import mmm.chemistry.ChemicalParcelStore;
import mmm.units.SignedDyadic;

/** Preserves conservative cell quadrature or merges/splits markers at the logical-step tail. */
final class ParticleReseedStage {
	record Workspace(
			DomainGeometry geometry, LiquidParticleStore particles, FlipParticleReseeder reseeder
	) { }

	interface Access {
		default boolean hasConservativeCellQuadrature() { return false; }
		void ensureParticleCapacity(int required);
		ChemicalParcelStore chemicalParcels();
		ThermalDomainState thermalState();
		FlipParticleReseeder.ThermalAdapter thermalAdapter();
		boolean massWeightedDynamics();
		boolean markerPositionAllowed(float x, float y, float z);
		long carrierMutationRevision();
		CarrierMutationCoordinator carrierMutations();
		double particleKineticEnergyJoules();
		SignedDyadic exactInternalEnergy();
		void markGridDirty();
		void rebuildGrid();
		void ensureInventoryGrid();
	}

	record Outcome(FlipParticleReseeder.Result result, double initialKineticEnergy, double finalKineticEnergy) { }

	private final Workspace state;
	private final Access access;

	ParticleReseedStage(Workspace state, Access access) {
		this.state = state;
		this.access = access;
	}

	Outcome step(boolean captureMechanicalAudit) {
		double initialKinetic = captureMechanicalAudit ? access.particleKineticEnergyJoules() : 0.0;
		if (access.hasConservativeCellQuadrature()) {
			access.ensureInventoryGrid();
			return new Outcome(new FlipParticleReseeder.Result(state.particles().count(), 0, 0, 0, 0, 0, 0, 0, 0), initialKinetic, initialKinetic);
		}
		access.ensureParticleCapacity(state.reseeder().capacityUpperBound(state.particles().count()));
		ThermalDomainState thermal = access.thermalState();
		SignedDyadic thermalBefore = thermal == null ? SignedDyadic.ZERO : access.exactInternalEnergy();
		LiquidParticleStore.DirectArrays markers = state.particles().directArrays();
		FlipParticleReseeder.Result result = state.reseeder().reseed(
				state.geometry().solid,
				markers.positionX, markers.positionY, markers.positionZ,
				markers.velocityX, markers.velocityY, markers.velocityZ,
				markers.volume, markers.mass, markers.materialHandle, markers.phaseLabel,
				access.chemicalParcels(), thermal == null ? null : access.thermalAdapter(),
				state.particles().count(), access.massWeightedDynamics(), access::markerPositionAllowed);
		access.carrierMutations().adoptWholesaleCount(
				CarrierMutationPlan.wholesaleCount(
						access.carrierMutationRevision(), state.particles().count()),
				result.particleCount());
		double finalKinetic = captureMechanicalAudit ? access.particleKineticEnergyJoules() : 0.0;
		if (thermal != null) thermal.recordRoundoff(access.exactInternalEnergy().subtract(thermalBefore));
		access.markGridDirty();
		access.rebuildGrid();
		return new Outcome(result, initialKinetic, finalKinetic);
	}
}
