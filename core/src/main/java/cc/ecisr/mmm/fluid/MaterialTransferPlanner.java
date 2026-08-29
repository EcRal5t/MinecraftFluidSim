package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.thermal.LiquidEnergyColumn;

import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.chemistry.ChemicalParcelStore;
import cc.ecisr.mmm.chemistry.ChemicalLimits;
import cc.ecisr.mmm.chemistry.ChemicalTransferLedger;
import cc.ecisr.mmm.units.SignedDyadic;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

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
		if (domain.hasPendingPhaseVolumeWork()) return new FlipFluidDomain.ExactMixtureTransfer(false, List.of());
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
		LiquidEnergyColumn.ExternalLiquidInsertionPlan thermalPlan = null;
		if (domain.thermalState != null && internalEnergy != null) {
			List<LiquidEnergyColumn.LiquidInsertion> thermalInsertions = new ArrayList<>(insertPieces.size());
			for (MaterialInsertPiece piece : insertPieces) thermalInsertions.add(new LiquidEnergyColumn.LiquidInsertion(
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

	/**
	 * Plans the at-most pure-volume insertion against per-cell capacity, the marker budget and
	 * the region chemical budget before any marker is written, then commits the pre-validated
	 * pieces through the spawn path. With chemistry bound the inserted default parcels are
	 * booked as an external chemical source, the audit twin of the external thermal energy the
	 * spawn path already records; without chemistry there is no chemical truth to book.
	 */
	FlipFluidDomain.VolumeAddition insertVolumeAtMost(
			int centerX, int centerY, int centerZ, float amount, int maxRadius) {
		domain.validateVolumeEdit(amount, maxRadius);
		if (domain.hasPendingPhaseVolumeWork()) return new FlipFluidDomain.VolumeAddition(0.0F, false);
		double[] exactCellVolume = domain.exactCellVolumes();
		float remaining = amount;
		boolean markerBudgetLimited = false;
		List<MaterialInsertPiece> insertPieces = new ArrayList<>();
		long plannedChemicalQuanta = 0L;
		try {
			cellLoop:
			for (FlipFluidDomain.CellCandidate candidate : domain.nearbyCells(centerX, centerY, centerZ, maxRadius)) {
				int index = candidate.index();
				int x = index % domain.sizeX, yz = index / domain.sizeX, y = yz % domain.sizeY, z = yz / domain.sizeY;
				float requestedForCell = (float) Math.min(remaining,
						Math.max(0.0, domain.effectiveOpenCellVolume(index) - exactCellVolume[index]));
				int availableMarkers = domain.maximumParticleCount - domain.particles.count() - insertPieces.size();
				float markerCapacity = availableMarkers * domain.PARTICLE_VOLUME;
				float added = Math.min(requestedForCell, markerCapacity);
				if (added <= 1.0E-6F) {
					if (requestedForCell > 1.0E-6F && availableMarkers == 0) markerBudgetLimited = true;
					if (markerBudgetLimited) break;
					continue;
				}
				int cell = domain.cellIndex(x, y, z);
				float left = added, plannedInCell = 0.0F;
				for (int slot = domain.firstOpenSlot(cell, 0); left > 1.0E-6F;
						slot = domain.firstOpenSlot(cell, slot + 1)) {
					if (slot < 0) throw new IllegalStateException("Cut-cell open volume was exhausted while spawning markers");
					float piece = Math.min(domain.PARTICLE_VOLUME, left);
					double pieceMass = (double) piece * (domain.materialRegistry == null
							? domain.config.density() : domain.materialRegistry.material(0).referenceDensity());
					ChemicalMixtureRecord chemical = domain.chemicalParcels == null ? null
							: domain.chemicalParcels.defaultRecord(
									domain.materialRegistry.material(0).id(), piece, domain.config.cellSize(), pieceMass, false);
					if (chemical != null) {
						long pieceQuanta = chemical.totalQuanta();
						if (Math.addExact(domain.chemicalParcels.totalQuanta(),
								Math.addExact(plannedChemicalQuanta, pieceQuanta))
								> cc.ecisr.mmm.chemistry.ChemicalLimits.MAX_REGION_TOTAL_QUANTA) {
							markerBudgetLimited = true;
							remaining -= plannedInCell;
							break cellLoop;
						}
						plannedChemicalQuanta = Math.addExact(plannedChemicalQuanta, pieceQuanta);
					}
					insertPieces.add(new MaterialInsertPiece(x, y, z, slot, piece, 0, pieceMass, chemical));
					plannedInCell += piece;
					left -= piece;
				}
				exactCellVolume[index] += added;
				remaining -= added;
				if (added + 1.0E-6F < requestedForCell) {
					markerBudgetLimited = true;
					break;
				}
				if (remaining <= 1.0E-6F) break;
			}
		} catch (IllegalArgumentException | ArithmeticException refusedPlan) {
			return new FlipFluidDomain.VolumeAddition(0.0F, false);
		}
		ChemicalTransferLedger externalAddition = ChemicalTransferLedger.EMPTY;
		if (domain.chemicalParcels != null && !insertPieces.isEmpty()) {
			try {
				ChemicalTransferLedger.Builder source = ChemicalTransferLedger.builder(domain.chemicalParcels.registry());
				for (MaterialInsertPiece piece : insertPieces) source.add(
						domain.materialRegistry.material(piece.materialHandle()).id(), false,
						piece.volume(), domain.unitScale.kilograms(piece.mass()), piece.chemical());
				externalAddition = source.build();
				domain.chemicalParcels.requireCanRecordExternalSource(externalAddition);
			} catch (IllegalArgumentException | ArithmeticException refusedAudit) {
				return new FlipFluidDomain.VolumeAddition(0.0F, false);
			}
		}
		for (MaterialInsertPiece piece : insertPieces) {
			domain.spawnParticle(piece.cellX(), piece.cellY(), piece.cellZ(), piece.slot(), piece.volume(),
					piece.materialHandle(), piece.mass(), piece.chemical(), false, true);
		}
		if (!externalAddition.isEmpty()) domain.chemicalParcels.recordExternalSource(externalAddition);
		domain.gridDirty = true;
		return new FlipFluidDomain.VolumeAddition(amount - remaining, markerBudgetLimited);
	}

	float removeVolumeNear(int centerX, int centerY, int centerZ, float amount, int maxRadius) {
		domain.validateVolumeEdit(amount, maxRadius);
		if (domain.hasPendingPhaseVolumeWork()) return 0.0F;
		float removed = domain.removeVolumeAcrossCells(domain.nearbyCells(centerX, centerY, centerZ, maxRadius), amount);
		domain.gridDirty = true;
		return removed;
	}

	FlipFluidDomain.ExactChemicalTransfer extractChemical(
			int centerX, int centerY, int centerZ, float volume, int maxRadius,
			java.util.function.Predicate<ChemicalTransferLedger> acceptsLedger
	) {
		if (domain.chemicalParcels == null) throw new IllegalStateException("Chemical extraction requires chemistry");
		if (acceptsLedger == null) throw new IllegalArgumentException("Chemical extraction validator is missing");
		domain.validateVolumeEdit(volume, maxRadius);
		if (volume <= 0.0F) return new FlipFluidDomain.ExactChemicalTransfer(volume == 0.0F, ChemicalTransferLedger.EMPTY);
		if (domain.hasPendingPhaseVolumeWork()) return new FlipFluidDomain.ExactChemicalTransfer(false, ChemicalTransferLedger.EMPTY);
		List<FlipFluidDomain.CellCandidate> cells = domain.nearbyCells(centerX, centerY, centerZ, maxRadius);
		boolean[] near = new boolean[domain.sizeX * domain.sizeY * domain.sizeZ]; for (FlipFluidDomain.CellCandidate candidate : cells) near[candidate.index()] = true;
		double available = 0.0;
		for (int marker = 0; marker < domain.particles.count(); marker++) if (near[domain.particleCellIndex(marker)]) {
			available += domain.particles.volume(marker);
		}
		if (available < volume) return new FlipFluidDomain.ExactChemicalTransfer(false, ChemicalTransferLedger.EMPTY);

		double remaining = volume, settlementTolerance = Math.ulp(volume);
		List<ExtractionAllocation> plan = new ArrayList<>();
		ChemicalTransferLedger.Builder ledger = ChemicalTransferLedger.builder(domain.chemicalParcels.registry());
		for (FlipFluidDomain.CellCandidate candidate : cells) {
			if (remaining <= 0.0) break;
			for (int marker = domain.particles.count() - 1; marker >= 0 && remaining > 0.0; marker--) {
				if (domain.particleCellIndex(marker) != candidate.index()) continue;
				float previousVolume = domain.particles.volume(marker);
				double take = Math.min(previousVolume, remaining);
				float newVolume = (float) (previousVolume - take);
				double actual = previousVolume - newVolume;
				if (actual > remaining && newVolume < previousVolume) {
					newVolume = Math.nextUp(newVolume); actual = previousVolume - newVolume;
					settlementTolerance = Math.max(settlementTolerance, Math.ulp(previousVolume));
				}
				if (actual <= 0.0) continue;
				double previousMass = domain.particles.mass(marker);
				double newMass = newVolume == 0.0F ? 0.0 : previousMass * newVolume / previousVolume;
				double removedMass = previousMass - newMass;
				ChemicalParcelStore.SplitPlan chemicalPlan = domain.chemicalParcels.planTruncate(
						marker, newVolume, previousVolume - newVolume, newMass, removedMass);
				ledger.add(domain.materialRegistry.material(domain.particles.materialHandle(marker)).id(), domain.chemicalParcels.indicator(marker),
						actual, domain.unitScale.kilograms(removedMass), chemicalPlan.removed());
				LiquidEnergyColumn.Truncation thermalPlan = domain.planThermalTruncate(marker, newMass,
						chemicalPlan.kept(), domain.thermalState == null ? Double.NaN : domain.thermalMarkerTemperature(marker));
				plan.add(new ExtractionAllocation(marker, newVolume, newMass, chemicalPlan, thermalPlan));
				remaining -= actual;
			}
		}
		if (remaining > settlementTolerance) return new FlipFluidDomain.ExactChemicalTransfer(false, ChemicalTransferLedger.EMPTY);
		ChemicalTransferLedger removed = ledger.build();
		if (removed.totalQuanta() > ChemicalLimits.MAX_BUCKET_TOTAL_QUANTA) {
			return new FlipFluidDomain.ExactChemicalTransfer(false, ChemicalTransferLedger.EMPTY);
		}
		if (!acceptsLedger.test(removed)) return new FlipFluidDomain.ExactChemicalTransfer(false, ChemicalTransferLedger.EMPTY);
		for (ExtractionAllocation allocation : plan) {
			if (allocation.chemicalPlan().kept() != null) {
				domain.chemicalParcels.commitTruncate(allocation.particle(), allocation.chemicalPlan());
			}
			domain.particles.setVolume(allocation.particle(), allocation.newVolume());
			domain.particles.setMass(allocation.particle(), allocation.newMass());
			domain.commitThermalTruncate(allocation.particle(), allocation.thermalPlan());
		}
		for (int marker = domain.particles.count() - 1; marker >= 0; marker--) if (domain.particles.volume(marker) == 0.0F) domain.removeParticle(marker);
		domain.gridDirty = true;
		double removedEnergy = domain.thermalState == null ? 0.0 : SignedDyadic.sum(plan.stream()
				.mapToDouble(allocation -> allocation.thermalPlan().removedEnergy()).toArray()).toDouble();
		return new FlipFluidDomain.ExactChemicalTransfer(true, removed, removedEnergy);
	}
	FlipFluidDomain.ExactMixtureTransfer extractMixture(
			int centerX, int centerY, int centerZ, float volume, int maxRadius, int requiredMaterialHandle
	) {
		if (domain.hasPendingPhaseVolumeWork()) return new FlipFluidDomain.ExactMixtureTransfer(false, List.of());
		java.util.List<FlipFluidDomain.CellCandidate> cells = domain.nearbyCells(centerX, centerY, centerZ, maxRadius);
		boolean[] near = new boolean[domain.sizeX * domain.sizeY * domain.sizeZ];
		for (FlipFluidDomain.CellCandidate candidate : cells) near[candidate.index()] = true;
		float h = domain.config.cellSize();
		double available = 0.0;
		for (int i = 0; i < domain.particles.count(); i++) {
			if (requiredMaterialHandle >= 0 && domain.particles.materialHandle(i) != requiredMaterialHandle) continue;
			if (domain.chemicalParcels != null && !domain.chemicalParcels.isCanonicalDefaultCarrier(
					i, domain.materialRegistry.material(domain.particles.materialHandle(i)).id())) continue;
			if (!near[domain.cellIndex((int) Math.floor(domain.particles.positionX(i) / h), (int) Math.floor(domain.particles.positionY(i) / h),
					(int) Math.floor(domain.particles.positionZ(i) / h))]) continue;
			available += domain.particles.volume(i);
		}
		if (available < volume) return new FlipFluidDomain.ExactMixtureTransfer(false, List.of());

		double remaining = volume;
		double settlementTolerance = Math.ulp(volume);
		java.util.List<ExtractionAllocation> plan = new java.util.ArrayList<>();
		int ledgerSize = domain.materialRegistry == null ? 1 : domain.materialRegistry.size();
		double[] volumeBefore = new double[ledgerSize];
		double[] massBefore = new double[ledgerSize];
		for (int particle = 0; particle < domain.particles.count(); particle++) {
			volumeBefore[domain.particles.materialHandle(particle)] += domain.particles.volume(particle);
			massBefore[domain.particles.materialHandle(particle)] += domain.particles.mass(particle);
		}
		java.util.Set<Integer> changedHandles = new java.util.TreeSet<>();
		for (FlipFluidDomain.CellCandidate candidate : cells) {
			if (remaining <= 0.0) break;
			int cell = candidate.index();
			for (int i = domain.particles.count() - 1; i >= 0; i--) {
				if (remaining <= 0.0) break;
				if (domain.particleCellIndex(i) != cell
						|| requiredMaterialHandle >= 0 && domain.particles.materialHandle(i) != requiredMaterialHandle) continue;
				if (domain.chemicalParcels != null && !domain.chemicalParcels.isCanonicalDefaultCarrier(
						i, domain.materialRegistry.material(domain.particles.materialHandle(i)).id())) continue;
				float previousVolume = domain.particles.volume(i);
				double take = Math.min(previousVolume, remaining);
				float newVolume = (float) (previousVolume - take);
				double actual = previousVolume - newVolume;
				// A nearest-float remainder may round downward and remove more than requested.
				// Keep the authoritative marker delta at or below the caller's remaining cap;
				// a later marker can settle any still-representable remainder.
				if (actual > remaining && newVolume < previousVolume) {
					newVolume = Math.nextUp(newVolume);
					actual = previousVolume - newVolume;
					settlementTolerance = Math.max(settlementTolerance, Math.ulp(previousVolume));
				}
				if (actual <= 0.0) continue;
				double previousMass = domain.particles.mass(i);
				double newMass = newVolume == 0.0F ? 0.0 : previousMass * newVolume / previousVolume;
				int handle = domain.particles.materialHandle(i);
				ChemicalParcelStore.SplitPlan chemicalPlan = domain.chemicalParcels == null ? null
						: domain.chemicalParcels.planTruncate(i, newVolume, previousVolume - newVolume,
						newMass, previousMass - newMass);
				LiquidEnergyColumn.Truncation thermalPlan = domain.planThermalTruncate(i, newMass,
						chemicalPlan == null ? null : chemicalPlan.kept(),
						domain.thermalState == null ? Double.NaN : domain.thermalMarkerTemperature(i));
				plan.add(new ExtractionAllocation(i, newVolume, newMass, chemicalPlan, thermalPlan));
				changedHandles.add(handle);
				remaining -= actual;
			}
		}
		if (remaining > settlementTolerance) return new FlipFluidDomain.ExactMixtureTransfer(false, List.of());
		for (ExtractionAllocation allocation : plan) {
			if (domain.chemicalParcels != null && allocation.chemicalPlan().kept() != null) {
				domain.chemicalParcels.commitTruncate(allocation.particle(), allocation.chemicalPlan());
			}
			domain.particles.setVolume(allocation.particle(), allocation.newVolume());
			domain.particles.setMass(allocation.particle(), allocation.newMass());
			domain.commitThermalTruncate(allocation.particle(), allocation.thermalPlan());
		}
		// Do not compact during indexed writes: removeParticle swaps the last marker into the current
		// index and could invalidate a later planned ordinal. Compact only after every ledger edit.
		for (int particle = domain.particles.count() - 1; particle >= 0; particle--) {
			if (domain.particles.volume(particle) == 0.0F) domain.removeParticle(particle);
		}
		domain.gridDirty = true;
		double[] volumeAfter = new double[ledgerSize];
		double[] massAfter = new double[ledgerSize];
		for (int particle = 0; particle < domain.particles.count(); particle++) {
			volumeAfter[domain.particles.materialHandle(particle)] += domain.particles.volume(particle);
			massAfter[domain.particles.materialHandle(particle)] += domain.particles.mass(particle);
		}
		List<FlipFluidDomain.MaterialPortion> result = new java.util.ArrayList<>(changedHandles.size());
		for (int handle : changedHandles) {
			double removedVolume = volumeBefore[handle] - volumeAfter[handle];
			double removedMass = massBefore[handle] - massAfter[handle];
			if (removedVolume > 0.0 && removedMass > 0.0) {
				result.add(new FlipFluidDomain.MaterialPortion(handle, removedVolume, removedMass));
			}
		}
		double removedEnergy = domain.thermalState == null ? 0.0 : SignedDyadic.sum(plan.stream()
				.mapToDouble(allocation -> allocation.thermalPlan().removedEnergy()).toArray()).toDouble();
		return new FlipFluidDomain.ExactMixtureTransfer(true, List.copyOf(result), removedEnergy);
	}

	/**
	 * Plans and restores an exact chemical ledger without substituting a default carrier:
	 * per-species binary volume allocation across whole markers, registry compatibility for
	 * every produced mixture, and wholesale rejection when any piece cannot be represented.
	 */
	FlipFluidDomain.ExactChemicalTransfer insertChemical(
			int centerX, int centerY, int centerZ, ChemicalTransferLedger ledger, int maxRadius,
			boolean externalSource, Double internalEnergy
	) {
		if (domain.chemicalParcels == null) throw new IllegalStateException("Chemical insertion requires chemistry");
		if (ledger == null || ledger.isEmpty()) return new FlipFluidDomain.ExactChemicalTransfer(ledger != null, ChemicalTransferLedger.EMPTY);
		if (maxRadius < 0) throw new IllegalArgumentException("maxRadius cannot be negative");
		if (domain.hasPendingPhaseVolumeWork()) return new FlipFluidDomain.ExactChemicalTransfer(false, ChemicalTransferLedger.EMPTY);
		try {
			if (Math.addExact(domain.chemicalParcels.totalQuanta(), ledger.totalQuanta())
					> cc.ecisr.mmm.chemistry.ChemicalLimits.MAX_REGION_TOTAL_QUANTA) {
				return new FlipFluidDomain.ExactChemicalTransfer(false, ChemicalTransferLedger.EMPTY);
			}
		} catch (ArithmeticException overflow) {
			return new FlipFluidDomain.ExactChemicalTransfer(false, ChemicalTransferLedger.EMPTY);
		}
		double[] exactCellVolume = domain.exactCellVolumes();
		List<FlipFluidDomain.CellCandidate> nearby = domain.nearbyCells(centerX, centerY, centerZ, maxRadius);
		List<ChemicalInsertPiece> pieces = new ArrayList<>();
		try {
		for (ChemicalTransferLedger.Group group : ledger.groups()) {
			int material = domain.materialRegistry.handle(group.materialId());
			double remaining = group.volume();
			List<ChemicalPieceDraft> groupPieces = new ArrayList<>();
			for (FlipFluidDomain.CellCandidate candidate : nearby) {
				if (remaining <= 0.0) break;
				double capacity = Math.max(0.0,
						domain.effectiveOpenCellVolume(candidate.index()) - exactCellVolume[candidate.index()]);
				double allocation = Math.min(capacity, remaining);
				if (allocation <= 0.0) continue;
				int cellX = candidate.index() % domain.sizeX;
				int yz = candidate.index() / domain.sizeX;
				int cellY = yz % domain.sizeY, cellZ = yz / domain.sizeY;
				double left = allocation;
				while (left > 0.0) {
					float piece = (float) Math.min(domain.PARTICLE_VOLUME, left);
					while (piece > left) piece = Math.nextDown(piece);
					if (!(piece > 0.0F)) return new FlipFluidDomain.ExactChemicalTransfer(false, ChemicalTransferLedger.EMPTY);
					groupPieces.add(new ChemicalPieceDraft(cellX, cellY, cellZ, piece, material));
					if ((long) domain.particles.count() + pieces.size() + groupPieces.size() > domain.maximumParticleCount) {
						return new FlipFluidDomain.ExactChemicalTransfer(false, ChemicalTransferLedger.EMPTY);
					}
					left -= piece;
				}
				exactCellVolume[candidate.index()] += allocation;
				remaining -= allocation;
			}
			if (remaining > 0.0) return new FlipFluidDomain.ExactChemicalTransfer(false, ChemicalTransferLedger.EMPTY);
			float[] weights = new float[groupPieces.size()];
			int[] ordinals = new int[groupPieces.size()];
			for (int piece = 0; piece < groupPieces.size(); piece++) {
				weights[piece] = groupPieces.get(piece).volume();
				ordinals[piece] = domain.particles.count() + pieces.size() + piece;
			}
			@SuppressWarnings("unchecked")
			TreeMap<String, Long>[] distributed = new TreeMap[groupPieces.size()];
			for (int piece = 0; piece < distributed.length; piece++) distributed[piece] = new TreeMap<>();
			for (Map.Entry<String, Long> species : group.speciesQuanta().entrySet()) {
				long[] allocation = cc.ecisr.mmm.chemistry.BinaryVolumeAllocator.allocate(species.getValue(), weights, ordinals);
				for (int piece = 0; piece < allocation.length; piece++) if (allocation[piece] > 0L) {
					distributed[piece].put(species.getKey(), allocation[piece]);
				}
			}
			double volumeLeft = group.volume(), massLeft = domain.unitScale.solverMass(group.massKilograms());
			for (int piece = 0; piece < groupPieces.size(); piece++) {
				if (distributed[piece].isEmpty()) return new FlipFluidDomain.ExactChemicalTransfer(false, ChemicalTransferLedger.EMPTY);
				ChemicalPieceDraft draft = groupPieces.get(piece);
				boolean last = piece + 1 == groupPieces.size();
				double mass = last ? massLeft : massLeft * draft.volume() / volumeLeft;
				ChemicalMixtureRecord withoutResidual = ChemicalMixtureRecord.fromStableAmounts(
						domain.chemicalParcels.registry(), distributed[piece], 0.0);
				ChemicalMixtureRecord record = ChemicalMixtureRecord.fromStableAmounts(
						domain.chemicalParcels.registry(), distributed[piece],
						domain.unitScale.kilograms(mass)
								- withoutResidual.exactSpeciesMassKilograms(domain.chemicalParcels.registry()).toDouble());
				domain.chemicalParcels.requireCompatible(record, group.materialId(), group.indicator());
				pieces.add(new ChemicalInsertPiece(draft.cellX(), draft.cellY(), draft.cellZ(),
						draft.volume(), draft.materialHandle(), mass, record, group.indicator()));
				volumeLeft -= draft.volume();
				massLeft -= mass;
			}
		}
		} catch (IllegalArgumentException | ArithmeticException
				| cc.ecisr.mmm.chemistry.ChemicalReactionEngine.BudgetExceededException refusedPlan) {
			return new FlipFluidDomain.ExactChemicalTransfer(false, ChemicalTransferLedger.EMPTY);
		}
		if (externalSource) try {
			domain.chemicalParcels.requireCanRecordExternalSource(ledger);
		} catch (IllegalArgumentException | ArithmeticException refusedAudit) {
			return new FlipFluidDomain.ExactChemicalTransfer(false, ChemicalTransferLedger.EMPTY);
		}
		LiquidEnergyColumn.ExternalLiquidInsertionPlan thermalPlan = null;
		if (domain.thermalState != null && internalEnergy != null) {
			List<LiquidEnergyColumn.LiquidInsertion> thermalInsertions = new ArrayList<>(pieces.size());
			for (ChemicalInsertPiece piece : pieces) thermalInsertions.add(new LiquidEnergyColumn.LiquidInsertion(
					piece.materialHandle(), piece.mass(), piece.mixture()));
			thermalPlan = domain.thermalState.planExternalLiquidInsertions(thermalInsertions, internalEnergy,
					domain.thermalMarkerContext());
		}
		int firstInsertedOrdinal = domain.particles.count();
		for (ChemicalInsertPiece piece : pieces) {
			int slot = domain.firstOpenSlot(domain.cellIndex(piece.cellX(), piece.cellY(), piece.cellZ()), 0);
			if (slot < 0) throw new IllegalStateException("Chemical insertion lost its planned open slot");
			domain.spawnParticle(piece.cellX(), piece.cellY(), piece.cellZ(), slot, piece.volume(),
					piece.materialHandle(), piece.mass(), piece.mixture(), piece.indicator(), thermalPlan == null);
		}
		if (thermalPlan != null) domain.thermalState.commitExternalLiquidInsertions(firstInsertedOrdinal, thermalPlan);
		if (externalSource) domain.chemicalParcels.recordExternalSource(ledger);
		domain.gridDirty = true;
		return new FlipFluidDomain.ExactChemicalTransfer(true, ledger, internalEnergy == null ? 0.0 : internalEnergy);
	}

	private record ExtractionAllocation(
			int particle, float newVolume, double newMass, ChemicalParcelStore.SplitPlan chemicalPlan,
			LiquidEnergyColumn.Truncation thermalPlan
	) { }

	private record ChemicalPieceDraft(int cellX, int cellY, int cellZ, float volume, int materialHandle) { }
	private record ChemicalInsertPiece(
			int cellX, int cellY, int cellZ, float volume, int materialHandle, double mass,
			ChemicalMixtureRecord mixture, boolean indicator
	) { }

	private record InsertAllocation(
			int x, int y, int z, double amount, int materialHandle, int portionIndex
	) { }

	private record MaterialInsertPiece(
			int cellX, int cellY, int cellZ, int slot, float volume, int materialHandle,
			double mass, ChemicalMixtureRecord chemical
	) { }
}
