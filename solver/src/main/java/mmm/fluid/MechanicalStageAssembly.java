package mmm.fluid;

import mmm.thermal.ThermalDomainState;

import mmm.chemistry.ChemicalParcelStore;
import mmm.granular.GranularCouplingFields;

/** Constructs the mechanical pipeline from stage-specific state and capability seams. */
final class MechanicalStageAssembly {
	record Stages(
			PressureProjectionStage pressure, GranularMechanicalStage granular,
			BodyForceStage bodyForce,
			MechanicalStepPipeline pipeline
	) { }

	private MechanicalStageAssembly() { }

	static Stages create(
			FluidDomain domain, PressureSystem pressureSystem, PressureBoundaryView pressureBoundary
	) {
		PressureProjectionStage pressure = new PressureProjectionStage(domain, pressureSystem);
		GranularMechanicalStage granular = new GranularMechanicalStage(
				new GranularMechanicalStage.Workspace(
						domain.config, domain.geometry, domain.markerGrid, domain.mac, domain.unitScale,
						domain.sizeX, domain.sizeY, domain.sizeZ),
				granularAccess(domain));
		BodyForceStage bodyForce = new BodyForceStage(
				new BodyForceStage.Workspace(
						domain.sizeX, domain.sizeY, domain.sizeZ, domain.cellCount, domain.config,
						domain.geometry, domain.mac, domain.markerGrid, domain.tension),
				bodyForceAccess(domain));
		MechanicalStepPipeline pipeline = new MechanicalStepPipeline(
				new MechanicalStepPipeline.Workspace(
						domain.gasTopologyCoordinator, domain.mac, domain.pendingPhaseVolume,
						domain.config),
				pipelineAccess(domain), granular, bodyForce, pressure, new LiquidViscosityStage(domain), domain.cellMacStage);
		return new Stages(pressure, granular, bodyForce, pipeline);
	}

	private static GranularMechanicalStage.Access granularAccess(FluidDomain domain) {
		return new GranularMechanicalStage.Access() {
			@Override public boolean granularCarrierFaceActive(int axis, int x, int y, int z) {
				return domain.granularCarrierFaceActive(axis, x, y, z);
			}
			@Override public boolean hasGranularInventory() { return domain.hasGranularInventory(); }
			@Override public GranularDomainState granularState() { return domain.granularState; }
			@Override public FlipGranularCollision granularCollision() { return domain.granularCollision; }
			@Override public GranularCouplingFields granularCoupling() { return domain.granularCoupling; }
			@Override public GranularCouplingFields granularNextCoupling() { return domain.granularNextCoupling; }
			@Override public void addBoundaryImpulse(FlipGranularCollision.BoundaryImpulse impulse) {
				domain.lastGranularBoundaryImpulseX += impulse.x();
				domain.lastGranularBoundaryImpulseY += impulse.y();
				domain.lastGranularBoundaryImpulseZ += impulse.z();
			}
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
			@Override public double surfaceTensionMeanCurvature(int x, int y, int z) {
				return domain.surfaceTensionMeanCurvature(x, y, z);
			}
			@Override public boolean isFluid(int cell) { return domain.isFluid(cell); }
			@Override public float pressureDensityScale(int first, int second) {
				return domain.pressureDensityScale(first, second);
			}
			@Override public float carrierDensityScale(int first, int second) {
				return domain.carrierDensityScale(first, second);
			}
		};
	}

	private static MechanicalStepPipeline.Access pipelineAccess(FluidDomain domain) {
		return new MechanicalStepPipeline.Access() {
			@Override public double projectedCourant(float dt) {
				if (domain.cellLiquidVolumeTransport == null) domain.cellLiquidVolumeTransport = new CellLiquidVolumeTransport(domain);
				return domain.cellLiquidVolumeTransport.projectedCourantRate() * dt;
			}
			@Override public void ensureGrid() { domain.ensureGrid(); }
			@Override public void prepareCarrierProperties() {
				if (domain.materialDefinitions == null) return;
				domain.carrierPropertiesWorkspace.invalidate();
				domain.derivedCarrierState();
			}
			@Override public double gridAndGranularKineticEnergyJoules() { return domain.mechanicalGridKineticEnergyJoules(); }
			@Override public double carrierKineticEnergyJoules() { return domain.mechanicalCarrierKineticEnergyJoules(); }
			@Override public double granularKineticEnergyJoules() { return domain.granularKineticEnergyJoules(); }
			@Override public int lastPressureIterations() { return domain.lastPressureIterations; }
			@Override public ThermalDomainState thermalState() { return domain.thermalState; }
		};
	}
}
