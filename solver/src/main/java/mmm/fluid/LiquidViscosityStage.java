package mmm.fluid;

/** Diffuses incoming flow before body-force prediction; retries the private step when dt is too large. */
final class LiquidViscosityStage {
	private final FluidDomain domain;
	private ViscousMacDiscretization discretization;
	private final float[][] velocity;

	LiquidViscosityStage(FluidDomain domain) {
		this.domain = domain;
		velocity = new float[][] {domain.mac.velocityX, domain.mac.velocityY, domain.mac.velocityZ};
	}

	private ViscousMacDiscretization createDiscretization() {
		return new ViscousMacDiscretization(domain.sizeX, domain.sizeY, domain.sizeZ,
				domain.config.cellSize(), new ViscousMacDiscretization.Geometry() {
			@Override public boolean liquid(int cell) {
				return domain.isFluid(cell) && domain.carrierPropertiesWorkspace.hasLiquid(cell);
			}
			@Override public double volume(int cell) {
				return Math.min(domain.markerGrid.exactCellMarkerVolume[cell], domain.effectiveOpenCellVolume(cell));
			}
			@Override public double density(int cell) { return domain.carrierPropertiesWorkspace.density(cell); }
			@Override public double viscosity(int cell) { return domain.carrierPropertiesWorkspace.viscosityPaPerS(cell); }
			@Override public double aperture(int axis, int x, int y, int z) {
				return switch (axis) {
					case 0 -> domain.pressureXFaceOpen(x, y, z);
					case 1 -> domain.pressureYFaceOpen(x, y, z);
					default -> domain.pressureZFaceOpen(x, y, z);
				};
			}
			@Override public double wallArea(int cell, int normalAxis) { return domain.geometry.cutCells.solidWallArea(cell, normalAxis); }
		}, domain.config.viscousWallCondition());
	}

	FluidDomain.ViscousStepStats apply(float dt) {
		if (domain.materialDefinitions == null) return FluidDomain.ViscousStepStats.INACTIVE;
		if (discretization == null) discretization = createDiscretization();
		discretization.assemble(velocity);
		double stable = discretization.operator.stableTimeStep();
		if (!(stable > 0)) throw new IllegalStateException("Viscous stable time step is not representable");
		if (dt > stable) {
			double count = Math.ceil(domain.config.timeStep() / stable);
			if (!Double.isFinite(count) || count > CpuFluidSolverBackend.MAXIMUM_CUMULATIVE_SUBSTEPS) {
				throw new FluidSolverBackend.MechanicalWorkBudgetExceededException("Viscous spectral bound exceeds mechanical substep budget: " + count);
			}
			throw new MechanicalTimeRefinement((int) count, "Viscous stress matrix");
		}
		FluidDomain.ViscousStepStats stats = discretization.operator.advance(dt);
		return discretization.copyVelocityTo(velocity, stats);
	}
}
