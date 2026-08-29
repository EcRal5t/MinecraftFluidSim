package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.chemistry.ChemicalDisplaySnapshot;
import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.granular.GranularDisplaySnapshot;
import cc.ecisr.mmm.granular.GranularStateSnapshot;
import cc.ecisr.mmm.thermal.ThermalDisplaySnapshot;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Read-only carrier state export and display snapshot construction. Every method allocates a
 * fresh immutable snapshot from the authoritative arrays and never exposes the domain's own
 * storage; capture/restore and renderer adapters consume these as values.
 */
final class CarrierInventoryReader {
	private final FlipFluidDomain domain;

	CarrierInventoryReader(FlipFluidDomain domain) {
		this.domain = domain;
	}

	FlipParticleState exportParticleState() {
		if (domain.hasNonDefaultMaterialState()) {
			throw new IllegalStateException("Multi-material markers cannot use the pure-water particle handoff");
		}
		int particleCount = domain.particles.count();
		float[] positionVolume = new float[particleCount * 4];
		float[] velocities = new float[particleCount * 3];
		byte[] solidMask = domain.geometry.cutCells.copyMicroMasks();
		for (int particle = 0; particle < particleCount; particle++) {
			int positionBase = particle * 4;
			int velocityBase = particle * 3;
			positionVolume[positionBase] = domain.particles.positionX(particle);
			positionVolume[positionBase + 1] = domain.particles.positionY(particle);
			positionVolume[positionBase + 2] = domain.particles.positionZ(particle);
			positionVolume[positionBase + 3] = domain.particles.volume(particle);
			velocities[velocityBase] = domain.particles.velocityX(particle);
			velocities[velocityBase + 1] = domain.particles.velocityY(particle);
			velocities[velocityBase + 2] = domain.particles.velocityZ(particle);
		}
		return new FlipParticleState(
				domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize(), particleCount,
				domain.totalVolume(), positionVolume, velocities, solidMask
		);
	}

	/** Exports exact multi-material marker state; runtime handles are replaced by the stable ordered palette. */
	FlipMaterialParticleState exportMaterialParticleState() {
		if (domain.materialRegistry == null) {
			throw new IllegalStateException("Material particle handoff requires a bound registry");
		}
		int particleCount = domain.particles.count();
		float[] positionVolume = new float[particleCount * 4];
		float[] velocities = new float[particleCount * 3];
		for (int particle = 0; particle < particleCount; particle++) {
			int positionBase = particle * 4, velocityBase = particle * 3;
			positionVolume[positionBase] = domain.particles.positionX(particle);
			positionVolume[positionBase + 1] = domain.particles.positionY(particle);
			positionVolume[positionBase + 2] = domain.particles.positionZ(particle);
			positionVolume[positionBase + 3] = domain.particles.volume(particle);
			velocities[velocityBase] = domain.particles.velocityX(particle);
			velocities[velocityBase + 1] = domain.particles.velocityY(particle);
			velocities[velocityBase + 2] = domain.particles.velocityZ(particle);
		}
		return new FlipMaterialParticleState(
				domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize(), particleCount,
				domain.totalVolumeExact(), domain.totalMass(), domain.materialRegistry.materials(),
				positionVolume, velocities, domain.geometry.cutCells.copyMicroMasks(),
				domain.particles.copyMass(),
				domain.particles.copyMaterialHandles()
		);
	}

	/** Exports canonical stable-ID chemical state; runtime mixture handles never cross this boundary. */
	FlipChemicalParticleState exportChemicalParticleState() {
		if (domain.chemicalParcels == null) {
			throw new IllegalStateException("Chemical particle handoff requires chemistry");
		}
		ArrayList<ChemicalMixtureRecord> mixtures = new ArrayList<>(domain.particles.count());
		byte[] indicator = new byte[domain.particles.count()];
		for (int marker = 0; marker < domain.particles.count(); marker++) {
			mixtures.add(domain.chemicalParcels.record(marker));
			indicator[marker] = domain.chemicalParcels.indicator(marker) ? (byte) 1 : 0;
		}
		return FlipChemicalParticleState.capture(
				exportMaterialParticleState(), domain.chemicalParcels.registry(), mixtures, indicator,
				domain.chemicalParcels.externalSource(), domain.chemicalParcels.escapedSink());
	}

	FlipThermalParticleState exportThermalParticleState() {
		if (domain.thermalState == null) {
			throw new IllegalStateException("Thermal particle handoff requires thermal state");
		}
		return domain.thermalState.capture(domain.sizeX, domain.sizeY, domain.sizeZ,
				domain.config.cellSize(), domain.particles.count());
	}

	GranularStateSnapshot exportGranularState() {
		if (domain.granularState == null) throw new IllegalStateException("Granular state is not bound");
		return GranularStateSnapshot.capture(domain.sizeX, domain.sizeY, domain.sizeZ, domain.granularState.registry,
				domain.granularState.parcels, domain.granularState.deposits, domain.granularState.compositions,
				domain.granularState.externalSource, domain.granularState.escapedSink);
	}

	GranularDisplaySnapshot copyGranularDisplaySnapshot() {
		if (domain.granularState == null) throw new IllegalStateException("Granular state is not bound");
		return GranularDisplaySnapshot.build(domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize(),
				domain.granularState.registry, domain.granularState.parcels, domain.granularState.deposits);
	}

	ChemicalDisplaySnapshot copyChemicalDisplaySnapshot() {
		if (domain.chemicalParcels == null) throw new IllegalStateException("Chemistry is not bound");
		LiquidParticleStore.DirectArrays arrays = domain.particles.directArrays();
		return ChemicalDisplaySnapshot.build(domain.chemicalParcels.registry(), domain.chemicalParcels,
				domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize(),
				arrays.positionX, arrays.positionY, arrays.positionZ, arrays.volume, domain.particles.count());
	}

	ThermalDisplaySnapshot copyThermalDisplaySnapshot() {
		if (domain.thermalState == null) throw new IllegalStateException("Thermal state is not bound");
		return domain.thermalState.displaySnapshot(domain.thermalMarkerContext(), domain.airNetwork);
	}

	/** Read-only material rendering reconstruction; it contains no exact inventory and is not a wire format. */
	FluidMaterialRenderSnapshot copyMaterialRenderSnapshot() {
		domain.ensureGrid();
		if (domain.materialRegistry == null) {
			throw new IllegalStateException("Material render snapshot requires a bound registry");
		}
		LiquidParticleStore.DirectArrays arrays = domain.particles.directArrays();
		return FluidMaterialRenderSnapshot.capture(
				domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize(), domain.materialRegistry,
				copyQuantizedVolume(), copyMarkerMask(), copyBinarySolidMask(),
				arrays.positionX, arrays.positionY, arrays.positionZ, arrays.volume,
				arrays.materialHandle, domain.particles.count(), domain.markerGrid.exactCellMarkerVolume
		);
	}

	byte[] copyQuantizedVolume() {
		domain.ensureGrid();
		byte[] result = new byte[domain.cellCount];
		for (int i = 0; i < domain.cellCount; i++) {
			result[i] = (byte) Math.round(FlipFluidDomain.clamp01(domain.volume[i]) * 255.0F);
		}
		return result;
	}

	/** Binary mask: 1 where a cell is entirely solid. Not interchangeable with copyCollisionMicroMasks(). */
	byte[] copyBinarySolidMask() {
		return domain.geometry.cutCells.copyBinaryFullMask();
	}

	/**
	 * The 2x2x2 cut-cell occupancy this domain actually resolves against. A particle state round-trips
	 * collision through this encoding, so handing it a binary mask silently drops every solid cell:
	 * the collision field is decoded as micro bits and nothing reads back as solid.
	 */
	byte[] copyCollisionMicroMasks() {
		return domain.geometry.cutCells.copyMicroMasks();
	}

	/** Actual pressure-phase occupancy, separate from the smooth P2G density. */
	byte[] copyMarkerMask() {
		domain.ensureGrid();
		byte[] result = new byte[domain.cellCount];
		for (int i = 0; i < domain.cellCount; i++) result[i] = domain.markerGrid.markerCount[i] > 0 ? (byte) 1 : (byte) 0;
		return result;
	}
}
