package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.thermal.ThermalDomainState;

import cc.ecisr.mmm.chemistry.ChemicalParcelStore;
import cc.ecisr.mmm.granular.GranularCouplingFields;

/** Constructs the mechanical pipeline from stage-specific state and capability seams. */
final class MechanicalStageAssembly {
	record Stages(
			PressureProjectionStage pressure, GranularMechanicalStage granular,
			BodyForceStage bodyForce, GridToParticleStage gridToParticle,
			ParticleAdvectionStage advection, ParticleToGridStage particleToGrid,
			MechanicalStepPipeline pipeline
	) { }

	private MechanicalStageAssembly() { }

	static Stages create(
			FluidDomain domain, PressureSystem pressureSystem, PressureBoundaryView pressureBoundary
	) {
		PressureProjectionStage pressure = new PressureProjectionStage(
				new PressureProjectionStage.Workspace(
						domain.sizeX, domain.sizeY, domain.sizeZ, domain.config, domain.geometry, domain.mac,
						domain.projection),
				pressureAccess(domain), pressureSystem, pressureBoundary);
		GranularMechanicalStage granular = new GranularMechanicalStage(
				new GranularMechanicalStage.Workspace(
						domain.config, domain.geometry, domain.markerGrid, domain.mac, domain.unitScale,
						domain.sizeX, domain.sizeY, domain.sizeZ),
				granularAccess(domain));
		BodyForceStage bodyForce = new BodyForceStage(
				new BodyForceStage.Workspace(
						domain.sizeX, domain.sizeY, domain.sizeZ, domain.cellCount, domain.config,
						domain.geometry, domain.mac, domain.markerGrid, domain.particles, domain.tension),
				bodyForceAccess(domain));
		GridToParticleStage gridToParticle = new GridToParticleStage(
				new GridToParticleStage.Workspace(
						domain.geometry, domain.markerGrid, domain.mac, domain.projection.pressureFluid, domain.particles, domain.velocityExtrapolator,
						domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize()),
				gridToParticleAccess(domain));
		ParticleAdvectionStage advection = new ParticleAdvectionStage(
				new ParticleAdvectionStage.Workspace(
						domain.particles, domain.particleCollision, domain.mac, domain.geometry,
						gridToParticle.advectionCells(), domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize()),
				advectionAccess(domain));
		ParticleToGridStage particleToGrid = new ParticleToGridStage(
				new ParticleToGridStage.Workspace(
						domain.volume, domain.projection, domain.freeSurfaceGeometry, domain.mac,
						domain.geometry, domain.markerGrid, domain.pressurePhaseGeometry),
				particleToGridAccess(domain), bodyForce, gridToParticle);
		MechanicalStepPipeline pipeline = new MechanicalStepPipeline(
				new MechanicalStepPipeline.Workspace(
						domain.gasTopologyCoordinator, domain.mac, domain.pendingPhaseCellVolume,
						domain.config),
				pipelineAccess(domain), granular, bodyForce, pressure, new LiquidViscosityStage(domain), gridToParticle, advection);
		return new Stages(pressure, granular, bodyForce, gridToParticle, advection, particleToGrid, pipeline);
	}

	private static PressureProjectionStage.Access pressureAccess(FluidDomain domain) {
		return new PressureProjectionStage.Access() {
			@Override public void rebuildPressureBoundaryLineage() { domain.rebuildPressureBoundaryLineage(); }
			@Override public int cellIndex(int x, int y, int z) { return domain.cellIndex(x, y, z); }
			@Override public int xFaceIndex(int x, int y, int z) { return domain.xFaceIndex(x, y, z); }
			@Override public int yFaceIndex(int x, int y, int z) { return domain.yFaceIndex(x, y, z); }
			@Override public int zFaceIndex(int x, int y, int z) { return domain.zFaceIndex(x, y, z); }
			@Override public float pressureXFaceOpen(int x, int y, int z) { return domain.pressureXFaceOpen(x, y, z); }
			@Override public float pressureYFaceOpen(int x, int y, int z) { return domain.pressureYFaceOpen(x, y, z); }
			@Override public float pressureZFaceOpen(int x, int y, int z) { return domain.pressureZFaceOpen(x, y, z); }
			@Override public boolean isFluid(int cell) { return domain.isFluid(cell); }
			@Override public boolean isAir(int cell) { return domain.isAir(cell); }
			@Override public boolean carrierFaceActive(int first, int second, float openArea) {
				return domain.carrierFaceActive(first, second, openArea);
			}
			@Override public float surfaceTensionBoundaryDivergence(int cell, float dt) {
				return domain.surfaceTensionBoundaryDivergence(cell, dt);
			}
			@Override public PressureFaceCoupling pressureFaceCoupling(
					int first, int second, int dx, int dy, int dz
			) {
				return domain.pressureFaceCoupling(first, second, dx, dy, dz);
			}
			@Override public void pressureAnchor(int cell) { domain.pressureAnchor = cell; }
			@Override public void pressureReference(float value) { domain.pressureReference = value; }
			@Override public float pressureReference() { return domain.pressureReference; }
			@Override public void pressureResult(int iterations, float residual) {
				domain.lastPressureIterations = iterations;
				domain.lastPressureResidual = residual;
			}
		};
	}

	private static GranularMechanicalStage.Access granularAccess(FluidDomain domain) {
		return new GranularMechanicalStage.Access() {
			@Override public boolean massWeightedP2G() { return domain.variableDensityActive; }
			@Override public boolean granularCarrierFaceActive(int axis, int x, int y, int z) {
				return domain.granularCarrierFaceActive(axis, x, y, z);
			}
			@Override public boolean hasGranularInventory() { return domain.hasGranularInventory(); }
			@Override public GranularDomainState granularState() { return domain.granularState; }
			@Override public FlipGranularCollision granularCollision() { return domain.granularCollision; }
			@Override public GranularCouplingFields granularCoupling() { return domain.granularCoupling; }
			@Override public GranularCouplingFields granularNextCoupling() { return domain.granularNextCoupling; }
			@Override public boolean granularOccupancyFitsGeometry(GranularCouplingFields coupling) {
				return domain.granularOccupancyFitsGeometry(coupling);
			}
			@Override public LiquidCarrierProperties liquidCarrierProperties() { return domain.derivedCarrierState(); }
			@Override public float effectiveOpenCellVolume(int cell) { return domain.effectiveOpenCellVolume(cell); }
			@Override public float trilinearWeight(int ox, int oy, int oz, float fx, float fy, float fz) {
				return domain.trilinearWeight(ox, oy, oz, fx, fy, fz);
			}
		};
	}

	private static BodyForceStage.Access bodyForceAccess(FluidDomain domain) {
		return new BodyForceStage.Access() {
			@Override public GranularDomainState granularState() { return domain.granularState; }
			@Override public CarrierProperties granularCarrierProperties(int cell) { return domain.granularCarrierProperties(cell); }
			@Override public int cellIndex(int x, int y, int z) { return domain.cellIndex(x, y, z); }
			@Override public int xFaceIndex(int x, int y, int z) { return domain.xFaceIndex(x, y, z); }
			@Override public int yFaceIndex(int x, int y, int z) { return domain.yFaceIndex(x, y, z); }
			@Override public int zFaceIndex(int x, int y, int z) { return domain.zFaceIndex(x, y, z); }
			@Override public boolean carrierFaceActive(int first, int second, float openArea) {
				return domain.carrierFaceActive(first, second, openArea);
			}
			@Override public float pressureXFaceOpen(int x, int y, int z) { return domain.pressureXFaceOpen(x, y, z); }
			@Override public float pressureYFaceOpen(int x, int y, int z) { return domain.pressureYFaceOpen(x, y, z); }
			@Override public float pressureZFaceOpen(int x, int y, int z) { return domain.pressureZFaceOpen(x, y, z); }
			@Override public boolean thermalActive() { return domain.thermalState != null; }
			@Override public LiquidCarrierProperties thermalBuoyancyProperties() { return domain.thermalBuoyancyProperties(); }
			@Override public double thermalFaceRelativeBuoyancy(int first, int second, LiquidCarrierProperties properties) {
				return domain.thermalFaceRelativeBuoyancy(first, second, properties);
			}
			@Override public FluidMaterialRegistry materialRegistry() { return domain.materialRegistry; }
			@Override public boolean liquidGasTensionActive() { return domain.surfaceTensionActive; }
			@Override public boolean liquidPairTensionActive() { return domain.liquidPairTensionActive; }
			@Override public double phaseFraction(int cell, int phase) { return domain.phaseFraction(cell, phase); }
			@Override public void smoothSurfaceTensionColor() { domain.smoothSurfaceTensionColor(); }
			@Override public void rebuildSurfaceTensionNormals() { domain.rebuildSurfaceTensionNormals(); }
			@Override public double surfaceTensionMeanCurvature(int x, int y, int z) {
				return domain.surfaceTensionMeanCurvature(x, y, z);
			}
			@Override public boolean isFluid(int cell) { return domain.isFluid(cell); }
			@Override public boolean variableDensityActive() { return domain.variableDensityActive; }
			@Override public float pressureDensityScale(int first, int second) {
				return domain.pressureDensityScale(first, second);
			}
			@Override public float carrierDensityScale(int first, int second) {
				return domain.carrierDensityScale(first, second);
			}
		};
	}

	private static GridToParticleStage.Access gridToParticleAccess(FluidDomain domain) {
		return new GridToParticleStage.Access() {
			@Override public void samplePair(int axis, float[] field, float[] previous, float x, float y, float z, float[] result) {
				float h = domain.config.cellSize();
				boolean[] valid = axis == 0 ? domain.mac.velocityValidX : axis == 1 ? domain.mac.velocityValidY : domain.mac.velocityValidZ;
				FluidDomainMechanicalFields.trilinearValidPair(field, previous, valid,
						domain.sizeX + (axis == 0 ? 1 : 0), domain.sizeY + (axis == 1 ? 1 : 0), domain.sizeZ + (axis == 2 ? 1 : 0),
						x / h - (axis == 0 ? 0 : 0.5F), y / h - (axis == 1 ? 0 : 0.5F), z / h - (axis == 2 ? 0 : 0.5F), result);
			}
			@Override public boolean variableDensityActive() { return domain.variableDensityActive; }
			@Override public float referenceDensity() { return domain.config.density(); }
		};
	}

	private static ParticleAdvectionStage.Access advectionAccess(FluidDomain domain) {
		return new ParticleAdvectionStage.Access() {
			@Override public boolean advectConservativeVolume(float dt) {
				if (!CellLiquidVolumeTransport.enabled()) return false;
				boolean connected = false;
				for (int p = 0; p < domain.particles.count(); p++)
					if (domain.gridToParticleStage.advectionCells()[domain.particleCellIndex(p)]) { connected = true; break; }
				if (!connected) {
					if (domain.cellLiquidVolumeTransport != null) domain.cellLiquidVolumeTransport.publishedQuadrature = false;
					return false;
				}
				if (domain.cellLiquidVolumeTransport == null) domain.cellLiquidVolumeTransport = new CellLiquidVolumeTransport(domain);
				domain.cellLiquidVolumeTransport.advect(dt);
				return true;
			}
			@Override public double conservativeCollisionWorkJoules() { return domain.cellLiquidVolumeTransport.collisionWorkJoules; }
			@Override public ChemicalParcelStore chemicalParcels() { return domain.chemicalParcels; }
			@Override public int[] chemicalPreviousCells() { return domain.chemicalPreviousCells; }
			@Override public int[] chemicalMovingMarkers() { return domain.chemicalMovingMarkers; }
			@Override public int particleCellIndex(int marker) { return domain.particleCellIndex(marker); }
			@Override public void markGridDirty() { domain.gridDirty = true; }
		};
	}

	private static ParticleToGridStage.Access particleToGridAccess(FluidDomain domain) {
		return new ParticleToGridStage.Access() {
			@Override public void setVariableDensityActive(boolean active) { domain.variableDensityActive = active; }
			@Override public void setSurfaceTensionActive(boolean active) { domain.surfaceTensionActive = active; }
			@Override public void setLiquidPairTensionActive(boolean active) { domain.liquidPairTensionActive = active; }
			@Override public boolean usesMassWeightedP2G() { return domain.usesMassWeightedP2G(); }
			@Override public boolean hasAnySurfaceTension() { return domain.hasAnySurfaceTension(); }
			@Override public boolean hasAnyLiquidPairTension() { return domain.hasAnyLiquidPairTension(); }
			@Override public void buildMarkerGrid() { MarkerGridBuilder.build(domain); }
			@Override public void scatterVelocities() { MarkerGridBuilder.scatterVelocities(domain); }
			@Override public void constrainClosedLiquidVolume() {
				domain.closedLiquidVolume.apply(domain.projection.pressureVolume, domain.particles, domain.pressureGranularCoupling, domain.markerGrid);
			}
			@Override public void normalize(float[] values, float[] weights) { domain.normalize(values, weights); }
			@Override public GranularCouplingFields granularCoupling() { return domain.granularCoupling; }
			@Override public boolean hasGranularInventory() { return domain.hasGranularInventory(); }
			@Override public boolean hasCoherentInventory() {
				return domain.thermalState != null && domain.thermalState.coherentPhases().groupCount() != 0;
			}
			@Override public GranularDomainState granularState() { return domain.granularState; }
			@Override public void setPressureGranularCoupling(GranularCouplingFields coupling) {
				domain.pressureGranularCoupling = coupling;
			}
			@Override public GranularCouplingFields pressureGranularCoupling() { return domain.pressureGranularCoupling; }
			@Override public void rebuildPressureBoundaryLineage() { domain.rebuildPressureBoundaryLineage(); }
			@Override public void rebuildSurfaceTensionPressure() { domain.rebuildSurfaceTensionPressure(); }
			@Override public void markGridClean() { domain.gridDirty = false; domain.velocityGridDirty = true; }
			@Override public void markVelocityGridClean() { domain.velocityGridDirty = false; }
		};
	}

	private static MechanicalStepPipeline.Access pipelineAccess(FluidDomain domain) {
		var resolved = new ResolvedLiquidMechanicalStage(domain);
		return new MechanicalStepPipeline.Access() {
			@Override public MechanicalStepPipeline.SubstepOutcome advanceResolvedGeometry(float dt, boolean captureAudit, MechanicalAuditAccumulator audit) {
				return domain.liquidGeometryState == null ? null : resolved.advance(dt, captureAudit, audit);
			}
			@Override public double projectedCourant(float dt) {
				if (!CellLiquidVolumeTransport.enabled()) {
					double[] maximum = new double[3];
					float[][] velocity = {domain.mac.velocityX, domain.mac.velocityY, domain.mac.velocityZ};
					for (int axis = 0; axis < 3; axis++) for (float value : velocity[axis]) maximum[axis] = Math.max(maximum[axis], Math.abs(value));
					return Math.sqrt(maximum[0] * maximum[0] + maximum[1] * maximum[1] + maximum[2] * maximum[2])
						* dt / (domain.config.cellSize() * domain.config.maxCourantNumber());
				}
				if (domain.cellLiquidVolumeTransport == null) domain.cellLiquidVolumeTransport = new CellLiquidVolumeTransport(domain);
				domain.gridToParticleStage.prepareAdvectionCells();
				double courant = domain.cellLiquidVolumeTransport.courantRate(0) * dt;
				for (int p = 0; p < domain.particles.count(); p++) {
					int c = domain.particleCellIndex(p);
					if (domain.projection.pressureFluid[c] || domain.gridToParticleStage.advectionCells()[c]) continue;
					double x = domain.particles.velocityX(p), y = domain.particles.velocityY(p), z = domain.particles.velocityZ(p);
					courant = Math.max(courant, Math.sqrt(x * x + y * y + z * z) * dt
							/ (domain.config.cellSize() * domain.config.maxCourantNumber()));
				}
				return courant;
			}
			@Override public void ensureGrid() { domain.ensureGrid(); }
			@Override public void prepareCarrierProperties() {
				if (domain.materialDefinitions == null) return;
				domain.carrierPropertiesWorkspace.invalidate();
				domain.derivedCarrierState();
			}
			@Override public double gridKineticEnergyJoules() { return domain.gridKineticEnergyJoules(); }
			@Override public double particleKineticEnergyJoules() { return domain.particleKineticEnergyJoules(); }
			@Override public int lastPressureIterations() { return domain.lastPressureIterations; }
			@Override public ThermalDomainState thermalState() { return domain.thermalState; }
			@Override public boolean hasPendingPhaseVolumeWork() { return domain.hasPendingPhaseVolumeWork(); }
		};
	}
}
