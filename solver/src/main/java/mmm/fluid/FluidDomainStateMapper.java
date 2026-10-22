package mmm.fluid;

import mmm.inventory.LiquidCellInventory;

import mmm.granular.GranularStateSnapshot;

import java.util.List;

/** Maps the complete authoritative aggregate to and from the immutable backend handoff state. */
final class FluidDomainStateMapper {
	private FluidDomainStateMapper() { }

	static FluidDomainState capture(FluidDomain domain) {
		if (domain == null) throw new IllegalArgumentException("Domain is missing");
		if (domain.domainPhaseInventory.collisionTransactionActive()) {
			throw new IllegalStateException("Cannot capture while a collision transaction is active");
		}
		var chemistry = domain.chemistry == null ? null : domain.chemistry.registry();
		GranularStateSnapshot granular = domain.granularState == null ? null : domain.exportGranularState();
		domain.ensurePhaseOccupancy();
		var gas = domain.gasState.frame == null ? null : FluidDomainState.GasContinuation.runtime(
				domain.gasState.frame,domain.gasState.network.runtimeSnapshot(),domain.gasState.network.registry().fingerprint());
		var captured = new FluidDomainState(domain.sizeX,domain.sizeY,domain.sizeZ,domain.config,
				domain.gasState.sealedDomainBoundary,domain.liquidCells.snapshot(),domain.phaseOccupancy,domain.mac.captureMomentum(),domain.geometry.cutCells.snapshot(),
				domain.materialRegistry == null ? List.of() : domain.materialRegistry.materials(),chemistry,
				domain.chemistry == null ? mmm.chemistry.ChemicalTransferLedger.EMPTY : domain.chemistry.externalSource(),
				domain.chemistry == null ? mmm.chemistry.ChemicalTransferLedger.EMPTY : domain.chemistry.escapedSink(),
				domain.chemistry == null ? null : domain.chemistry.dirtyCells(),
				domain.chemistry == null ? 0 : domain.chemistry.reactionCursor(),
				domain.granularState == null ? null : domain.granularState.registry,granular,
				domain.granularDepositDynamics == null ? null : domain.granularDepositDynamics.captureState(),
				domain.precipitationEnabled(),domain.gasState.network.registry(),gas,
				domain.thermalState == null ? null : domain.thermalState.registry(),
				domain.thermalState == null ? null : domain.thermalState.exportOwnerState(),
				domain.thermalState == null ? 0 : domain.thermalState.maximumTransportSubsteps(),
				domain.thermalState == null ? null : domain.exportPendingPhaseMotionState(),domain.thermalBoundaryTemperatureK,
				domain.thermalState == null ? null : domain.pressureSystem.captureLiquidPressure(),domain.materialDefinitions);
		return captured;
	}

	static FluidDomainState.Layout layout(FluidDomain domain) {
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

	static FluidDomain restore(FluidDomainState state) {
		if (state == null) throw new IllegalArgumentException("Authoritative state is missing");
		FluidDomain domain = new FluidDomain(state.sizeX(), state.sizeY(), state.sizeZ(),
				state.config(),state.fixedGeometry());
		if (state.sealedDomainBoundary()) domain.sealDomainBoundary();
		if (!state.materialPalette().isEmpty()) domain.materialRegistry = new FluidMaterialRegistry(state.materialPalette());
		if (state.chemicalRegistry() != null) {
			domain.chemistry = new mmm.chemistry.RegionChemistry(state.chemicalRegistry(),domain.cellCount);
			domain.chemistry.restoreAuditLedgers(state.chemicalSource(),state.chemicalSink());
			domain.chemistry.restoreDirtyCells(state.copyChemicalDirtyCells());
			domain.chemistry.restoreReactionCursor(state.chemicalReactionCursor());
		}
		domain.liquidCells = LiquidCellInventory.fromValidatedSnapshot(state.liquid(),state.chemicalRegistry());
		domain.markCellInventoryChanged();
		domain.mac.restoreMomentum(state.momentum());

		if (state.materialDefinitions() != null) domain.bindMaterialDefinitions(state.materialDefinitions());
		if (state.granular() != null) {
			domain.restoreGranularState(state.granular(), state.granularRegistry(), domain.materialRegistry(),
					state.materialDefinitions());
			domain.granularDepositDynamics.restoreState(state.granularDynamics());
		}
		if (state.gasRuntimeGeometry() != null) PhaseOccupancyTransport.publish(domain,state.phaseOccupancy());
		else domain.restorePhaseOccupancyState(state.phaseOccupancy());
		if (!state.hasGas()) domain.bindGasRegistry(state.gasRegistry());
		else if (state.gasRuntimeGeometry() != null) domain.gasTopologyCoordinator.restoreRuntime(state.gasRuntimeGeometry(),state.gasRuntimeNetwork());
		else domain.restoreGasState(state.gas(),state.gasRegistry(),state.thermalOwner());
		if (state.thermalOwner() != null) {
			domain.restoreThermalOwnerState(state.thermalOwner(), state.thermalRegistry(),
					state.maximumThermalTransportSubsteps());
			domain.restorePendingPhaseMotionState(state.pendingPhaseMotion());
			double[] thermalBoundary = state.copyThermalBoundaryTemperaturesK();
			if (thermalBoundary != null) domain.replaceThermalBoundaryTemperatures(thermalBoundary);
		}
		if (state.precipitationEnabled()) domain.setPrecipitationEnabled(true);
		// Stored actual pressure is converted back to the solver's residual using the
		// current hydrostatic and capillary references. Velocity remains lazy.
		if(state.condensedPhasePressure()!=null){
			if(domain.gasState.frame!=null)domain.pressureSystem.prepareGeometry();
			domain.pressureSystem.restoreLiquidPressure(state.condensedPhasePressure());
		}
		return domain;
	}
}
