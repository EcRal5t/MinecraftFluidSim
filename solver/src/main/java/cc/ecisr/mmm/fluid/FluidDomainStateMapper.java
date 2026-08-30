package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.fluid.air.GasStateSnapshot;
import cc.ecisr.mmm.granular.GranularStateSnapshot;

import java.util.List;

/** Maps the complete authoritative aggregate to and from the immutable backend handoff state. */
final class FluidDomainStateMapper {
	private FluidDomainStateMapper() { }

	static FluidDomainState capture(FluidDomain domain) {
		if (domain == null) throw new IllegalArgumentException("Domain is missing");
		if (domain.collisionTransactionActive()) {
			throw new IllegalStateException("Cannot capture while a collision transaction is active");
		}
		if (domain.gasState.cellNodeId != null) domain.gasTopologyCoordinator.synchronizeForPersistence();

		FlipParticleState pure = null;
		FlipMaterialParticleState materials = null;
		FlipChemicalParticleState chemicals = null;
		ChemicalRegistry chemistry = null;
		if (domain.chemicalParcels != null) {
			chemicals = domain.exportChemicalParticleState();
			chemistry = domain.chemicalParcels.registry();
		} else if (domain.materialRegistry != null) {
			materials = domain.exportMaterialParticleState();
		} else {
			pure = domain.exportParticleState();
		}

		GranularStateSnapshot granular = domain.granularState == null ? null : domain.exportGranularState();
		String chemicalFingerprint = chemistry == null ? "" : chemistry.fingerprint();
		GasStateSnapshot gas = domain.gasState.cellNodeId == null
				? null : domain.exportGasState(chemicalFingerprint);
		FlipThermalParticleState thermal = domain.thermalState == null
				? null : domain.exportThermalParticleState();
		return new FluidDomainState(
				domain.config, domain.maximumParticleCount, domain.gasState.sealedDomainBoundary,
				pure, materials, chemicals, chemistry,
				domain.granularState == null ? null : domain.granularState.registry,
				granular, domain.precipitationEnabled(), domain.gasState.network.registry(), gas,
				domain.thermalState == null ? null : domain.thermalState.registry(), thermal,
				domain.thermalState == null ? 0 : domain.thermalState.maximumTransportSubsteps(),
				domain.thermalState == null ? null : domain.exportPendingPhaseVolumeState());
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
				domain.thermalState == null ? 0 : domain.thermalState.maximumTransportSubsteps());
	}

	static FluidDomain restore(FluidDomainState state) {
		if (state == null) throw new IllegalArgumentException("Authoritative state is missing");
		FluidDomain domain = new FluidDomain(state.sizeX(), state.sizeY(), state.sizeZ(),
				state.config(), state.maximumParticleCount());
		if (state.sealedDomainBoundary()) domain.sealDomainBoundary();
		switch (state.carrierKind()) {
			case PURE -> domain.restoreParticleState(state.particles());
			case MATERIAL -> domain.restoreMaterialParticleState(state.materials());
			case CHEMICAL -> domain.restoreChemicalParticleState(state.chemicals(), state.chemicalRegistry());
		}
		if (state.granular() != null) {
			domain.restoreGranularState(state.granular(), state.granularRegistry(), domain.materialRegistry());
		}
		if (state.gas() == null) domain.bindGasRegistry(state.gasRegistry());
		else domain.restoreGasState(state.gas(), state.gasRegistry());
		if (state.thermal() != null) {
			domain.restoreThermalParticleState(state.thermal(), state.thermalRegistry(),
					state.maximumThermalTransportSubsteps());
			domain.restorePendingPhaseVolumeState(state.pendingPhaseVolume());
		}
		if (state.precipitationEnabled()) domain.setPrecipitationEnabled(true);
		return domain;
	}
}
