package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.units.SignedDyadic;

/**
 * Per-stage mechanical energy bookkeeping for one audited logical step. The pipeline adds
 * each stage's grid/particle kinetic-energy delta; the domain adds the carrier-transfer and
 * reseed deltas after the mechanical loop and closes the audit with the existing ULP
 * residual bound.
 */
final class MechanicalAuditAccumulator {
	final double initialParticleKineticEnergyJoules;
	final double initialGravitationalPotentialEnergyJoules;
	double particleToGridTransferJoules;
	double granularCouplingWorkJoules;
	double porousBoundaryWorkJoules;
	double gravityWorkJoules;
	double thermalBuoyancyWorkJoules;
	double surfaceTensionWorkJoules;
	double fixedBoundaryWorkJoules;
	double pressureProjectionWorkJoules;
	double gridToParticleTransferJoules;
	double particleCollisionWorkJoules;
	double carrierInventoryTransferJoules;
	double reseedTransferJoules;

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
		double accounted = particleToGridTransferJoules + granularCouplingWorkJoules
				+ porousBoundaryWorkJoules + gravityWorkJoules + thermalBuoyancyWorkJoules
				+ surfaceTensionWorkJoules + fixedBoundaryWorkJoules
				+ pressureProjectionWorkJoules + gridToParticleTransferJoules
				+ particleCollisionWorkJoules + carrierInventoryTransferJoules
				+ reseedTransferJoules;
		double residual = finalParticleKineticEnergyJoules
				- initialParticleKineticEnergyJoules - accounted;
		double scale = Math.abs(initialParticleKineticEnergyJoules)
				+ Math.abs(finalParticleKineticEnergyJoules)
				+ Math.abs(particleToGridTransferJoules) + Math.abs(granularCouplingWorkJoules)
				+ Math.abs(porousBoundaryWorkJoules) + Math.abs(gravityWorkJoules)
				+ Math.abs(thermalBuoyancyWorkJoules) + Math.abs(surfaceTensionWorkJoules)
				+ Math.abs(fixedBoundaryWorkJoules) + Math.abs(pressureProjectionWorkJoules)
				+ Math.abs(gridToParticleTransferJoules) + Math.abs(particleCollisionWorkJoules)
				+ Math.abs(carrierInventoryTransferJoules) + Math.abs(reseedTransferJoules);
		if (!Double.isFinite(residual) || !Double.isFinite(scale)
				|| Math.abs(residual) > Math.ulp(scale == 0.0 ? 1.0 : scale) * 128.0) {
			throw new ArithmeticException("Mechanical step energy audit does not close: residual="
					+ residual + ", scale=" + scale);
		}
		return new FluidDomain.MechanicalStepAudit(true,
				initialParticleKineticEnergyJoules, finalParticleKineticEnergyJoules,
				initialGravitationalPotentialEnergyJoules, finalGravitationalPotentialEnergyJoules,
				particleToGridTransferJoules, granularCouplingWorkJoules, porousBoundaryWorkJoules,
				gravityWorkJoules, thermalBuoyancyWorkJoules, surfaceTensionWorkJoules,
				fixedBoundaryWorkJoules, pressureProjectionWorkJoules,
				gridToParticleTransferJoules, particleCollisionWorkJoules,
				carrierInventoryTransferJoules, reseedTransferJoules,
				thermalInventoryMechanicalWork, residual);
	}
}
