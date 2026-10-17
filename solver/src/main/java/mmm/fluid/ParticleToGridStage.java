package mmm.fluid;

import mmm.granular.GranularCouplingFields;

import java.util.Arrays;

/** Rebuilds marker inventory, pressure geometry and MAC velocity at their respective use sites. */
final class ParticleToGridStage {
	record Workspace(
			float[] volume, PressureProjectionWorkspace projection,
			FlipFreeSurfaceGeometry freeSurfaceGeometry, MacVelocityGrid mac,
			DomainGeometry geometry, MarkerGridIndex markerGrid,
			FlipPressurePhaseGeometry pressurePhaseGeometry, LiquidParticleStore particles, float cellSize
	) { }

	interface Access {
		void setVariableDensityActive(boolean active);
		void setSurfaceTensionActive(boolean active);
		void setLiquidPairTensionActive(boolean active);
		boolean usesMassWeightedP2G();
		boolean hasAnySurfaceTension();
		boolean hasAnyLiquidPairTension();
		void buildMarkerGrid();
		void scatterVelocities();
		void normalize(float[] values, float[] weights);
		GranularCouplingFields granularCoupling();
		boolean hasGranularInventory();
		boolean hasCoherentInventory();
		GranularDomainState granularState();
		void setPressureGranularCoupling(GranularCouplingFields coupling);
		GranularCouplingFields pressureGranularCoupling();
		void rebuildPressureBoundaryLineage();
		void rebuildSurfaceTensionPressure();
		void constrainClosedLiquidVolume();
		void markInventoryClean();
		void markPressureGeometryClean();
		void markVelocityGridClean();
	}

	private final Workspace state;
	private final Access access;
	private final BodyForceStage bodyForceStage;
	private final GridToParticleStage gridToParticleStage;

	ParticleToGridStage(
			Workspace state, Access access,
			BodyForceStage bodyForceStage, GridToParticleStage gridToParticleStage
	) {
		this.state = state;
		this.access = access;
		this.bodyForceStage = bodyForceStage;
		this.gridToParticleStage = gridToParticleStage;
	}

	void rebuild() { rebuildInventory(); rebuildPressureGeometry(); rebuildVelocity(); }

	/** Current marker indices, exact cell quantities, material columns and occupied volume. */
	void rebuildInventory() {
		PressureProjectionWorkspace projection = state.projection;
		Arrays.fill(state.volume, 0.0F);
		Arrays.fill(projection.pressureVolume, 0.0);
		state.freeSurfaceGeometry.clearExactVolumes();
		access.setVariableDensityActive(access.usesMassWeightedP2G());
		access.setSurfaceTensionActive(access.hasAnySurfaceTension());
		access.setLiquidPairTensionActive(access.hasAnyLiquidPairTension());
		access.buildMarkerGrid();
		GranularCouplingFields granularCoupling = access.granularCoupling();
		DomainGeometry geometry = state.geometry;
		MarkerGridIndex markerGrid = state.markerGrid;
		if (granularCoupling == null || !access.hasGranularInventory() && !access.hasCoherentInventory()) {
			if (granularCoupling != null) granularCoupling.clear();
			state.freeSurfaceGeometry.rebuild(geometry.solid, markerGrid.markerCount, geometry.cutCells);
			access.setPressureGranularCoupling(null);
		} else {
			if (access.hasGranularInventory()) granularCoupling.rebuild(
					access.granularState().parcels, access.granularState().deposits);
			else granularCoupling.rebuildFixed();
			access.setPressureGranularCoupling(granularCoupling);
			state.freeSurfaceGeometry.rebuild(
					geometry.solid, markerGrid.markerCount, geometry.cutCells, granularCoupling);
		}
		access.markInventoryClean();
		markerGrid.inventoryRevision++;
	}

	void rebuildVelocity() {
		MacVelocityGrid mac = state.mac;
		Arrays.fill(mac.velocityX, 0.0F);
		Arrays.fill(mac.velocityY, 0.0F);
		Arrays.fill(mac.velocityZ, 0.0F);
		Arrays.fill(mac.velocityWeightX, 0.0F);
		Arrays.fill(mac.velocityWeightY, 0.0F);
		Arrays.fill(mac.velocityWeightZ, 0.0F);
		access.scatterVelocities();
		access.normalize(mac.velocityX, mac.velocityWeightX);
		access.normalize(mac.velocityY, mac.velocityWeightY);
		access.normalize(mac.velocityZ, mac.velocityWeightZ);
		bodyForceStage.enforceSolidFaceVelocities();
		gridToParticleStage.extrapolateVelocityIntoAir();
		access.markVelocityGridClean();
	}

	/** Refreshes pressure-derived views against the current solid occupancy and retained marker footprint. */
	void rebuildPressureGeometry() {
		access.constrainClosedLiquidVolume();
		state.pressurePhaseGeometry.rebuildLiquidEdits(
				state.geometry.solid, state.projection.pressureVolume, state.geometry.cutCells,
				access.pressureGranularCoupling(), state.projection.pressureFluid,
				state.markerGrid, state.particles, state.cellSize, java.util.List.of());
		access.rebuildPressureBoundaryLineage();
		access.rebuildSurfaceTensionPressure();
		access.markPressureGeometryClean();
	}
}
