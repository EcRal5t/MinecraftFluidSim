package mmm.fluid;

import mmm.inventory.LiquidCellInventory;

import mmm.granular.GranularStateSnapshot;

import java.util.List;

/** Maps the complete authoritative aggregate to and from the immutable backend handoff state. */
final class FluidDomainStateMapper {
	private FluidDomainStateMapper() { }

	static FluidDomainState capture(HostFluidState domain) {
		if (domain == null) throw new IllegalArgumentException("Domain is missing");
		if (domain.domainPhaseInventory.collisionTransactionActive()) {
			throw new IllegalStateException("Cannot capture while a collision transaction is active");
		}
		var chemistry = domain.chemistry == null ? null : domain.chemistry.registry();
		GranularStateSnapshot granular = domain.granularState == null ? null : domain.exportGranularState();
		if(domain.gasState.spatial==null)domain.domainCapabilities.initializeSpatialGas();
		domain.ensurePhaseOccupancy();
		var gas = FluidDomainState.GasContinuation.spatial(domain.gasState.spatial);
		var captured = new FluidDomainState(domain.sizeX,domain.sizeY,domain.sizeZ,domain.config,
				domain.gasState.sealedDomainBoundary,domain.liquidCells.snapshot(),domain.phaseOccupancy,domain.mac.captureMomentum(),domain.geometry.cutCells.snapshot(),
				domain.entityBodies,domain.materialRegistry == null ? List.of() : domain.materialRegistry.materials(),chemistry,
				domain.chemistry == null ? mmm.chemistry.ChemicalTransferLedger.EMPTY : domain.chemistry.externalSource(),
				domain.chemistry == null ? mmm.chemistry.ChemicalTransferLedger.EMPTY : domain.chemistry.escapedSink(),
				domain.chemistry == null ? null : domain.chemistry.dirtyCells(),
				domain.chemistry == null ? 0 : domain.chemistry.reactionCursor(),
				domain.chemistry == null ? List.of() : domain.chemistry.massTransferPending(),
				domain.granularState == null ? null : domain.granularState.registry,granular,
				domain.granularDepositDynamics == null ? null : domain.granularDepositDynamics.captureState(),
				domain.precipitationEnabled(),domain.gasState.network.registry(),gas,
				domain.thermalState == null ? null : domain.thermalState.registry(),
				domain.thermalState == null ? null : domain.thermalState.exportOwnerState(),
				domain.thermalState == null ? 0 : domain.thermalState.maximumTransportSubsteps(),
				domain.thermalState == null ? null : domain.exportPendingPhaseMotionState(),domain.thermalBoundaryTemperatureK,
				domain.thermalState == null ? null : domain.pressureSystem.captureRuntimePressure(),domain.materialDefinitions,domain.activityGroups.capture());
		return captured;
	}

	static FluidDomainState.Layout layout(HostFluidState domain) {
		if (domain == null) throw new IllegalArgumentException("Domain is missing");
		FluidDomainState.CarrierKind carrierKind = domain.chemistry != null
				? FluidDomainState.CarrierKind.CHEMICAL
				: domain.materialRegistry != null ? FluidDomainState.CarrierKind.MATERIAL
				: FluidDomainState.CarrierKind.PURE;
		return new FluidDomainState.Layout(domain.config, domain.sizeX, domain.sizeY, domain.sizeZ, domain.gasState.sealedDomainBoundary, carrierKind,
				domain.materialRegistry == null ? List.of() : domain.materialRegistry.materials(),
				domain.chemistry == null ? "" : domain.chemistry.registry().fingerprint(),
				domain.granularState == null ? "" : domain.granularState.registry.fingerprint(),
				domain.precipitationEnabled(), domain.gasState.network.registry().fingerprint(),
				domain.thermalState == null ? "" : domain.thermalState.registry().fingerprint(),
				domain.thermalState == null ? 0 : domain.thermalState.insertionTemperatureK(),
				domain.thermalState == null ? 0 : domain.thermalState.maximumTransportSubsteps(),
				domain.materialDefinitions == null ? "" : domain.materialDefinitions.fingerprint());
	}

	/** Sampling uses the same liquid/gas face inertia without running pressure or thermal preparation. */
	static HostFluidState samplingState(FluidDomainState state){
		if(state.thermalOwner()!=null)return restore(state);
		var domain=new HostFluidState(state.sizeX(),state.sizeY(),state.sizeZ(),state.config(),state.fixedGeometry());
		if(!state.materialPalette().isEmpty())domain.materialRegistry=new FluidMaterialRegistry(state.materialPalette());
		if(state.chemicalRegistry()!=null){
			domain.chemistry=new mmm.chemistry.RegionChemistry(state.chemicalRegistry(),domain.cellCount);
			domain.chemistry.restoreAuditLedgers(state.chemicalSource(),state.chemicalSink());
		}
		domain.liquidCells=LiquidCellInventory.fromValidatedSnapshot(state.liquid(),state.chemicalRegistry());
		domain.gasState.network=new mmm.fluid.air.AirNetwork(state.gasRegistry());
		domain.gasState.spatial=state.spatialGas();domain.gasState.sealedDomainBoundary=state.sealedDomainBoundary();
		domain.markCellInventoryChanged();domain.mac.restoreMomentum(state.momentum());
		return domain;
	}

	static HostFluidState restore(FluidDomainState state) {
		if (state == null) throw new IllegalArgumentException("Authoritative state is missing");
		HostFluidState domain = new HostFluidState(state.sizeX(), state.sizeY(), state.sizeZ(),
				state.config(),state.fixedGeometry());
		domain.restoreEntityBodyState(state.entityBodyState());
		if (state.sealedDomainBoundary()) domain.sealDomainBoundary();
		if (!state.materialPalette().isEmpty()) domain.materialRegistry = new FluidMaterialRegistry(state.materialPalette());
		if (state.chemicalRegistry() != null) {
			domain.chemistry = new mmm.chemistry.RegionChemistry(state.chemicalRegistry(),domain.cellCount);
			domain.chemistry.restoreAuditLedgers(state.chemicalSource(),state.chemicalSink());
			domain.chemistry.restoreDirtyCells(state.copyChemicalDirtyCells());
			domain.chemistry.restoreReactionCursor(state.chemicalReactionCursor());
			domain.chemistry.restoreMassTransferPending(state.chemicalMassTransferPending());
		}
		domain.liquidCells = LiquidCellInventory.fromValidatedSnapshot(state.liquid(),state.chemicalRegistry());
		domain.markCellInventoryChanged();
		domain.mac.restoreMomentum(state.momentum());
		if (state.pendingPhaseMotion() != null) domain.restorePendingPhaseMotionState(state.pendingPhaseMotion());

		if (state.materialDefinitions() != null) domain.bindMaterialDefinitions(state.materialDefinitions());
		if(state.spatialGas()!=null){domain.gasState.spatial=state.spatialGas();domain.gasState.network=new mmm.fluid.air.AirNetwork(state.gasRegistry());domain.gasState.pressureActive=state.spatialGas().cells().stream().anyMatch(g->!g.isEmpty());}
		if (state.granular() != null) {
			domain.restoreGranularState(state.granular(), state.granularRegistry(), domain.materialRegistry(),
					state.materialDefinitions());
			domain.granularDepositDynamics.restoreState(state.granularDynamics());
		}
		var liquidVolumes = new mmm.units.SignedDyadic[domain.cellCount];
		for (int cell = 0; cell < domain.cellCount; cell++) liquidVolumes[cell] = domain.liquidCells.exactCellVolume(cell);
		DomainPhaseInventory.requireJointCapacity(domain, liquidVolumes, domain.occupiedSolidSvq());
		if (state.spatialGas()!=null) PhaseOccupancyTransport.publish(domain,state.phaseOccupancy());
		else domain.restorePhaseOccupancyState(state.phaseOccupancy());
		if(state.spatialGas()!=null) { }
		else if (!state.hasGas()) domain.bindGasRegistry(state.gasRegistry());
		else domain.restoreGasState(state.gas(),state.gasRegistry(),state.thermalOwner());
		if (state.thermalOwner() != null) {
			domain.restoreThermalOwnerState(state.thermalOwner(), state.thermalRegistry(),
					state.maximumThermalTransportSubsteps());
			double[] thermalBoundary = state.copyThermalBoundaryTemperaturesK();
			if (thermalBoundary != null) domain.replaceThermalBoundaryTemperatures(thermalBoundary);
		}
		if(state.spatialGas()!=null)PhaseOccupancyTransport.requireCurrentGeometry(domain,state.phaseOccupancy());
		if (state.precipitationEnabled()) domain.setPrecipitationEnabled(true);
		if(state.pressureContinuation()!=null)domain.pressureSystem.restoreLiquidPressure(state.pressureContinuation());
		domain.activityGroups.restore(state.activityState());
		return domain;
	}
}
