package mmm.fluid;

import mmm.units.SignedDyadic;

/**
 * Per-stage mechanical energy bookkeeping for one audited logical step. The pipeline adds
 * each stage's kinetic-energy delta; inventory transitions complete the accounting
 * after the mechanical loop.
 */
final class MechanicalAuditAccumulator {
	final double initialParticleKineticEnergyJoules;
	final double initialGravitationalPotentialEnergyJoules;
	double porousBoundaryWorkJoules;
	double gravityWorkJoules;
	double thermalBuoyancyWorkJoules;
	double surfaceTensionWorkJoules;
	double viscousWorkJoules;
	double fixedBoundaryWorkJoules;
	double pressureProjectionWorkJoules;
	double contactWorkJoules;
	double volumeTransportTransferJoules;
	double carrierInventoryTransferJoules;

	MechanicalAuditAccumulator(
			double initialParticleKineticEnergyJoules,
			double initialGravitationalPotentialEnergyJoules
	) {
		this.initialParticleKineticEnergyJoules = initialParticleKineticEnergyJoules;
		this.initialGravitationalPotentialEnergyJoules = initialGravitationalPotentialEnergyJoules;
	}

	FluidDomain.MechanicalStepAudit finish(
			double finalParticleKineticEnergyJoules,
			double finalGravitationalPotentialEnergyJoules,
			SignedDyadic thermalInventoryMechanicalWork
	) {
		double accounted = porousBoundaryWorkJoules + gravityWorkJoules + thermalBuoyancyWorkJoules
				+ surfaceTensionWorkJoules + viscousWorkJoules + fixedBoundaryWorkJoules
				+ pressureProjectionWorkJoules + contactWorkJoules + volumeTransportTransferJoules + carrierInventoryTransferJoules;
		double residual = finalParticleKineticEnergyJoules
				- initialParticleKineticEnergyJoules - accounted;
		return new FluidDomain.MechanicalStepAudit(true,
				initialParticleKineticEnergyJoules, finalParticleKineticEnergyJoules,
				initialGravitationalPotentialEnergyJoules, finalGravitationalPotentialEnergyJoules,
				porousBoundaryWorkJoules,
				gravityWorkJoules, thermalBuoyancyWorkJoules, surfaceTensionWorkJoules, viscousWorkJoules,
				fixedBoundaryWorkJoules, pressureProjectionWorkJoules,contactWorkJoules,
				volumeTransportTransferJoules,carrierInventoryTransferJoules,
				thermalInventoryMechanicalWork, residual);
	}
}
