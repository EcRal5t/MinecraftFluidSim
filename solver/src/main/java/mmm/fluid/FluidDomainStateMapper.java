package mmm.fluid;

import mmm.inventory.LiquidCellInventory;

import mmm.fluid.air.GasStateSnapshot;
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
		var chemistry = domain.chemicalParcels == null ? null : domain.chemicalParcels.registry();
		GranularStateSnapshot granular = domain.granularState == null ? null : domain.exportGranularState();
		domain.ensurePhaseOccupancy();
		GasStateSnapshot gas = domain.gasState.frame == null ? null : domain.exportGasState(chemistry == null ? "" : chemistry.fingerprint());
		var captured = new FluidDomainState(domain.sizeX,domain.sizeY,domain.sizeZ,domain.config,domain.maximumParticleCount,
				domain.gasState.sealedDomainBoundary,domain.liquidCells.snapshot(),domain.phaseOccupancy,domain.mac.captureMomentum(),domain.geometry.cutCells.copyMicroMasks(),
				domain.materialRegistry == null ? List.of() : domain.materialRegistry.materials(),chemistry,
				domain.chemicalParcels == null ? mmm.chemistry.ChemicalTransferLedger.EMPTY : domain.chemicalParcels.externalSource(),
				domain.chemicalParcels == null ? mmm.chemistry.ChemicalTransferLedger.EMPTY : domain.chemicalParcels.escapedSink(),
				domain.chemicalParcels == null ? null : domain.chemicalParcels.dirtyCells(),
				domain.chemicalParcels == null ? 0 : domain.chemicalParcels.reactionCursor(),
				domain.granularState == null ? null : domain.granularState.registry,granular,
				domain.granularDepositDynamics == null ? null : domain.granularDepositDynamics.captureState(),
				domain.precipitationEnabled(),domain.gasState.network.registry(),gas,
				domain.thermalState == null ? null : domain.thermalState.registry(),
				domain.thermalState == null ? null : domain.thermalState.exportOwnerState(),
				domain.thermalState == null ? 0 : domain.thermalState.maximumTransportSubsteps(),
				domain.thermalState == null ? null : domain.exportPendingPhaseVolumeState(),domain.thermalBoundaryTemperatureK,
				domain.thermalState == null ? null : domain.pressureSystem.captureLiquidPressure(),domain.materialDefinitions);
		if (gas != null) captured.attachGasRuntime(domain.gasState.frame,domain.gasState.network.runtimeSnapshot());
		return captured;
	}

	static FluidDomainState.Layout layout(FluidDomain domain) {
		if (domain == null) throw new IllegalArgumentException("Domain is missing");
		FluidDomainState.CarrierKind carrierKind = domain.chemicalParcels != null
				? FluidDomainState.CarrierKind.CHEMICAL
				: domain.materialRegistry != null ? FluidDomainState.CarrierKind.MATERIAL
				: FluidDomainState.CarrierKind.PURE;
		return new FluidDomainState.Layout(domain.config, domain.sizeX, domain.sizeY, domain.sizeZ,
				domain.maximumParticleCount, domain.gasState.sealedDomainBoundary, carrierKind,
				domain.materialRegistry == null ? List.of() : domain.materialRegistry.materials(),
				domain.chemicalParcels == null ? "" : domain.chemicalParcels.registry().fingerprint(),
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
				state.config(), state.maximumParticleCount());
		if (state.sealedDomainBoundary()) domain.sealDomainBoundary();
		domain.geometry.cutCells.restoreMicroMasks(state.copyCollisionMasks());
		for (int cell = 0; cell < domain.cellCount; cell++) domain.geometry.solid[cell] = domain.geometry.cutCells.mask(cell) == 255;
		if (!state.materialPalette().isEmpty()) domain.materialRegistry = new FluidMaterialRegistry(state.materialPalette());
		if (state.chemicalRegistry() != null) {
			domain.chemicalParcels = new mmm.chemistry.ChemicalParcelStore(state.chemicalRegistry(),domain.unitScale,state.maximumParticleCount(),domain.cellCount);
			domain.chemicalParcels.restoreAuditLedgers(state.chemicalSource(),state.chemicalSink());
			domain.chemicalParcels.restoreDirtyCells(state.copyChemicalDirtyCells());
			domain.chemicalParcels.restoreReactionCursor(state.chemicalReactionCursor());
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
		if (state.gas() == null) domain.bindGasRegistry(state.gasRegistry());
		else if (state.gasRuntimeGeometry() != null) domain.gasTopologyCoordinator.restoreRuntime(state.gasRuntimeGeometry(),state.gasRuntimeNetwork());
		else domain.restoreGasState(state.gas(),state.gasRegistry(),state.thermalOwner());
		if (state.thermalOwner() != null) {
			domain.restoreThermalOwnerState(state.thermalOwner(), state.thermalRegistry(),
					state.maximumThermalTransportSubsteps());
			domain.restorePendingPhaseVolumeState(state.pendingPhaseVolume());
			double[] thermalBoundary = state.copyThermalBoundaryTemperaturesK();
			if (thermalBoundary != null) domain.replaceThermalBoundaryTemperatures(thermalBoundary);
		}
		if (state.precipitationEnabled()) domain.setPrecipitationEnabled(true);
		domain.domainFlowFields.ensureGrid();
		if(state.condensedPhasePressure()!=null)domain.pressureSystem.restoreLiquidPressure(state.condensedPhasePressure());
		return domain;
	}
}
