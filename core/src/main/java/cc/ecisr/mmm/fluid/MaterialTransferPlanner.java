package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.chemistry.ChemicalTransferLedger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Plans exact liquid/material/mixture insertion against shared cell capacity before any
 * marker is written, then commits the pre-validated pieces through the spawn path backed by
 * the carrier mutation coordinator, settling chemistry and thermal ledgers afterwards.
 * Every rejection happens during planning; the commit loop itself has no business refusals.
 */
final class MaterialTransferPlanner {
	private final FlipFluidDomain domain;

	MaterialTransferPlanner(FlipFluidDomain domain) {
		this.domain = domain;
	}

	FlipFluidDomain.ExactMixtureTransfer insertMixture(
			int centerX, int centerY, int centerZ, List<FlipFluidDomain.MaterialPortion> portions, int maxRadius,
			boolean externalSource, Double internalEnergy) {
		if (portions == null || portions.isEmpty()) throw new IllegalArgumentException("Mixture insertion is empty");
		if (maxRadius < 0) throw new IllegalArgumentException("maxRadius cannot be negative");
		java.util.Set<Integer> materialHandles = new java.util.HashSet<>();
		for (FlipFluidDomain.MaterialPortion portion : portions) {
			domain.validateMaterialPortion(portion);
			if (!materialHandles.add(portion.materialHandle())) {
				throw new IllegalArgumentException("Mixture insertion contains duplicate material handle "
						+ portion.materialHandle());
			}
		}
		double[] exactCellVolume = domain.exactCellVolumes();
		java.util.List<InsertAllocation> plan = new java.util.ArrayList<>();
		long requiredMarkers = 0L;
		List<FlipFluidDomain.CellCandidate> nearby = domain.nearbyCells(centerX, centerY, centerZ, maxRadius);
		for (int portionIndex = 0; portionIndex < portions.size(); portionIndex++) {
			FlipFluidDomain.MaterialPortion portion = portions.get(portionIndex);
			double remaining = portion.volume();
			for (FlipFluidDomain.CellCandidate candidate : nearby) {
				if (remaining <= 0.0) break;
				int index = candidate.index();
				double capacity = Math.max(0.0, domain.effectiveOpenCellVolume(index) - exactCellVolume[index]);
				double allocate = Math.min(capacity, remaining);
				if (allocate <= 0.0) continue;
				requiredMarkers += (long) Math.ceil(allocate / domain.PARTICLE_VOLUME);
				if (domain.particles.count() + requiredMarkers > domain.maximumParticleCount) {
					return new FlipFluidDomain.ExactMixtureTransfer(false, List.of());
				}
				int x = index % domain.sizeX, yz = index / domain.sizeX, y = yz % domain.sizeY, z = yz / domain.sizeY;
				plan.add(new InsertAllocation(x, y, z, allocate, portion.materialHandle(), portionIndex));
				exactCellVolume[index] += allocate;
				remaining -= allocate;
			}
			if (remaining > 0.0) return new FlipFluidDomain.ExactMixtureTransfer(false, List.of());
		}

		double[] inserted = new double[portions.size()];
		double[] insertedMass = new double[portions.size()];
		double[] volumeLeft = new double[portions.size()];
		double[] massLeft = new double[portions.size()];
		for (int i = 0; i < portions.size(); i++) {
			volumeLeft[i] = portions.get(i).volume();
			massLeft[i] = portions.get(i).mass();
		}
		List<MaterialInsertPiece> insertPieces = new ArrayList<>();
		long plannedChemicalQuanta = 0L;
		for (InsertAllocation allocation : plan) {
			int portionIndex = allocation.portionIndex();
			double left = allocation.amount();
			int slot = domain.firstOpenSlot(domain.cellIndex(allocation.x(), allocation.y(), allocation.z()), 0);
			while (left > 0.0) {
				boolean allocationRemainder = left < domain.PARTICLE_VOLUME;
				float piece = allocationRemainder ? (float) left : domain.PARTICLE_VOLUME;
				if (slot < 0) throw new IllegalStateException("Cut-cell open volume was exhausted while spawning markers");
				boolean finalPortionPiece = volumeLeft[portionIndex] - piece
						<= Math.ulp((float) volumeLeft[portionIndex]);
				double pieceMass = finalPortionPiece ? massLeft[portionIndex]
						: massLeft[portionIndex] * piece / volumeLeft[portionIndex];
				ChemicalMixtureRecord chemical = domain.chemicalParcels == null ? null : domain.chemicalParcels.defaultRecord(
						domain.materialRegistry.material(allocation.materialHandle()).id(), piece, domain.config.cellSize(), pieceMass, false);
				if (chemical != null) plannedChemicalQuanta = Math.addExact(plannedChemicalQuanta, chemical.totalQuanta());
				insertPieces.add(new MaterialInsertPiece(allocation.x(), allocation.y(), allocation.z(), slot, piece,
						allocation.materialHandle(), pieceMass, chemical));
				inserted[portionIndex] += piece;
				insertedMass[portionIndex] += pieceMass;
				left = allocationRemainder ? 0.0 : left - piece;
				if (finalPortionPiece) {
					volumeLeft[portionIndex] = 0.0;
					massLeft[portionIndex] = 0.0;
				} else {
					volumeLeft[portionIndex] -= piece;
					massLeft[portionIndex] -= pieceMass;
				}
				slot = domain.firstOpenSlot(domain.cellIndex(allocation.x(), allocation.y(), allocation.z()), slot + 1);
			}
		}
		if (domain.chemicalParcels != null && Math.addExact(domain.chemicalParcels.totalQuanta(), plannedChemicalQuanta)
				> cc.ecisr.mmm.chemistry.ChemicalLimits.MAX_REGION_TOTAL_QUANTA) {
			return new FlipFluidDomain.ExactMixtureTransfer(false, List.of());
		}
		ChemicalTransferLedger externalAddition = ChemicalTransferLedger.EMPTY;
		if (externalSource && domain.chemicalParcels != null) {
			try {
				ChemicalTransferLedger.Builder source = ChemicalTransferLedger.builder(domain.chemicalParcels.registry());
				for (MaterialInsertPiece piece : insertPieces) source.add(
						domain.materialRegistry.material(piece.materialHandle()).id(), false,
						piece.volume(), domain.unitScale.kilograms(piece.mass()), piece.chemical());
				externalAddition = source.build();
				domain.chemicalParcels.requireCanRecordExternalSource(externalAddition);
			} catch (IllegalArgumentException | ArithmeticException refusedAudit) {
				return new FlipFluidDomain.ExactMixtureTransfer(false, List.of());
			}
		}
		FlipThermalState.ExternalLiquidInsertionPlan thermalPlan = null;
		if (domain.thermalState != null && internalEnergy != null) {
			List<FlipThermalState.LiquidInsertion> thermalInsertions = new ArrayList<>(insertPieces.size());
			for (MaterialInsertPiece piece : insertPieces) thermalInsertions.add(new FlipThermalState.LiquidInsertion(
					piece.materialHandle(), piece.mass(), piece.chemical()));
			thermalPlan = domain.thermalState.planExternalLiquidInsertions(thermalInsertions, internalEnergy,
					domain.thermalMarkerContext());
		}
		int firstInsertedOrdinal = domain.particles.count();
		for (MaterialInsertPiece piece : insertPieces) {
			domain.spawnParticle(piece.cellX(), piece.cellY(), piece.cellZ(), piece.slot(), piece.volume(),
					piece.materialHandle(), piece.mass(), piece.chemical(), false, thermalPlan == null);
		}
		if (thermalPlan != null) domain.thermalState.commitExternalLiquidInsertions(firstInsertedOrdinal, thermalPlan);
		if (!externalAddition.isEmpty()) domain.chemicalParcels.recordExternalSource(externalAddition);
		domain.gridDirty = true;
		List<FlipFluidDomain.MaterialPortion> result = new java.util.ArrayList<>(portions.size());
		for (int i = 0; i < portions.size(); i++) {
			result.add(new FlipFluidDomain.MaterialPortion(portions.get(i).materialHandle(), inserted[i], insertedMass[i]));
		}
		return new FlipFluidDomain.ExactMixtureTransfer(true, List.copyOf(result), internalEnergy == null ? 0.0 : internalEnergy);
	}

	private record InsertAllocation(
			int x, int y, int z, double amount, int materialHandle, int portionIndex
	) { }

	private record MaterialInsertPiece(
			int cellX, int cellY, int cellZ, int slot, float volume, int materialHandle,
			double mass, ChemicalMixtureRecord chemical
	) { }
}
