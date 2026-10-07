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
	private final FluidDomain domain;

	MaterialTransferPlanner(FluidDomain domain) {
		this.domain = domain;
	}

	private static double observedSpace(SignedDyadic room) {
		double value = room.toDouble();
		return SignedDyadic.of(value).compareTo(room) > 0 ? Math.nextDown(value) : value;
	}

	FluidDomain.ExactMixtureTransfer insertMixture(
			int centerX, int centerY, int centerZ, List<FluidDomain.MaterialPortion> portions, int maxRadius,
			boolean externalSource, Double internalEnergy) {
		if (portions == null || portions.isEmpty()) throw new IllegalArgumentException("Mixture insertion is empty");
		if (maxRadius < 0) throw new IllegalArgumentException("maxRadius cannot be negative");
		if (domain.hasPendingPhaseVolumeWork()) return new FluidDomain.ExactMixtureTransfer(false, List.of());
		java.util.Set<Integer> materialHandles = new java.util.HashSet<>();
		for (FluidDomain.MaterialPortion portion : portions) {
			domain.validateMaterialPortion(portion);
			if (!materialHandles.add(portion.materialHandle())) {
				throw new IllegalArgumentException("Mixture insertion contains duplicate material handle "
						+ portion.materialHandle());
			}
		}
		if (!domain.canAddCarrierVolume(portions.stream().mapToDouble(FluidDomain.MaterialPortion::volume).sum())) {
			return new FluidDomain.ExactMixtureTransfer(false, List.of());
		}
		SignedDyadic[] availableCellVolume = domain.availableCellVolumes();
		java.util.List<InsertAllocation> plan = new java.util.ArrayList<>();
		long requiredMarkers = 0L;
		List<FluidDomain.CellCandidate> nearby = domain.nearbyCells(centerX, centerY, centerZ, maxRadius);
		for (int portionIndex = 0; portionIndex < portions.size(); portionIndex++) {
			FluidDomain.MaterialPortion portion = portions.get(portionIndex);
			double remaining = portion.volume();
			for (FluidDomain.CellCandidate candidate : nearby) {
				if (remaining <= 0.0) break;
				int index = candidate.index();
				double capacity = observedSpace(availableCellVolume[index]);
				double allocate = Math.min(capacity, remaining);
				float represented = (float) allocate;
				if (represented > allocate) represented = Math.nextDown(represented);
				allocate = represented;
				if (allocate <= 0.0) continue;
				if (domain.chemicalParcels != null && domain.chemicalParcels.tryDefaultRecord(
						domain.materialRegistry.material(portion.materialHandle()).id(), (float) allocate,
						domain.config.cellSize(), portion.mass() * allocate / portion.volume(), false) == null) continue;
				requiredMarkers += (long) Math.ceil(allocate / domain.PARTICLE_VOLUME);
				if (domain.particles.count() + requiredMarkers > domain.maximumParticleCount) {
					return new FluidDomain.ExactMixtureTransfer(false, List.of());
				}
				int x = index % domain.sizeX, yz = index / domain.sizeX, y = yz % domain.sizeY, z = yz / domain.sizeY;
				plan.add(new InsertAllocation(x, y, z, allocate, portion.materialHandle(), portionIndex));
				availableCellVolume[index] = availableCellVolume[index].subtract(SignedDyadic.of(allocate));
				remaining -= allocate;
			}
			if (remaining > 0.0) return new FluidDomain.ExactMixtureTransfer(false, List.of());
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
				// Split the last two markers evenly so a tiny remainder has a real carrier.
				if (left > domain.PARTICLE_VOLUME && left < 2.0 * domain.PARTICLE_VOLUME) {
					piece = (float) (left * 0.5);
				}
				if (slot < 0) throw new IllegalStateException("Cut-cell open volume was exhausted while spawning markers");
				boolean finalPortionPiece = volumeLeft[portionIndex] == piece;
				double pieceMass = finalPortionPiece ? massLeft[portionIndex]
						: massLeft[portionIndex] * piece / volumeLeft[portionIndex];
				ChemicalMixtureRecord chemical = domain.chemicalParcels == null ? null : domain.chemicalParcels.tryDefaultRecord(
						domain.materialRegistry.material(allocation.materialHandle()).id(), piece, domain.config.cellSize(), pieceMass, false);
				if (domain.chemicalParcels != null && chemical == null) {
					return new FluidDomain.ExactMixtureTransfer(false, List.of());
				}
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
			return new FluidDomain.ExactMixtureTransfer(false, List.of());
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
				return new FluidDomain.ExactMixtureTransfer(false, List.of());
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
		var geometric = planGeometry(insertPieces.stream().map(p -> new LiquidGeometryInsertionPlan.Insertion(p.cellX(), p.cellY(), p.cellZ(), p.volume())).toList());
		int firstInsertedOrdinal = domain.particles.count(); int insertion = 0;
		for (MaterialInsertPiece piece : insertPieces) {
			domain.spawnParticle(piece.cellX(), piece.cellY(), piece.cellZ(), piece.slot(), piece.volume(),
					piece.materialHandle(), piece.mass(), piece.chemical(), false, thermalPlan == null, geometric == null ? null : geometric.liquid().positions.get(insertion++));
		}
		if (geometric != null) geometric.publish(domain);
		if (thermalPlan != null) domain.thermalState.commitExternalLiquidInsertions(firstInsertedOrdinal, thermalPlan);
		if (!externalAddition.isEmpty()) domain.chemicalParcels.recordExternalSource(externalAddition);
		domain.gridDirty = true;
		List<FluidDomain.MaterialPortion> result = new java.util.ArrayList<>(portions.size());
		for (int i = 0; i < portions.size(); i++) {
			result.add(new FluidDomain.MaterialPortion(portions.get(i).materialHandle(), inserted[i], insertedMass[i]));
		}
		return new FluidDomain.ExactMixtureTransfer(true, List.copyOf(result), internalEnergy == null ? 0.0 : internalEnergy);
	}

	/**
	 * Plans the at-most pure-volume insertion against per-cell capacity, the marker budget and
	 * the region chemical budget before any marker is written, then commits the pre-validated
	 * pieces through the spawn path. With chemistry bound the inserted default parcels are
	 * booked as an external chemical source, the audit twin of the external thermal energy the
	 * spawn path already records; without chemistry there is no chemical truth to book.
	 */
	FluidDomain.VolumeAddition insertVolumeAtMost(
			int centerX, int centerY, int centerZ, float amount, int maxRadius) {
		domain.validateVolumeEdit(amount, maxRadius);
		if (domain.hasPendingPhaseVolumeWork()) return new FluidDomain.VolumeAddition(0.0F, false);
		float capacity = (float) Math.min(amount, Math.max(0.0, domain.remainingCarrierVolumeExact()));
		if (!domain.canAddCarrierVolume(capacity)) capacity = Math.nextDown(capacity);
		if (!(capacity > 0.0F)) return new FluidDomain.VolumeAddition(0.0F, false);
		SignedDyadic[] availableCellVolume = domain.availableCellVolumes();
		SignedDyadic remaining = SignedDyadic.of(capacity);
		boolean markerBudgetLimited = false;
		List<MaterialInsertPiece> insertPieces = new ArrayList<>();
		long plannedChemicalQuanta = 0L;
		try {
			cellLoop:
			for (FluidDomain.CellCandidate candidate : domain.nearbyCells(centerX, centerY, centerZ, maxRadius)) {
				int index = candidate.index();
				int x = index % domain.sizeX, yz = index / domain.sizeX, y = yz % domain.sizeY, z = yz / domain.sizeY;
				double room = observedSpace(availableCellVolume[index]);
				double permitted = Math.min(observedSpace(remaining), room);
				float requestedForCell = (float) permitted;
				if (requestedForCell > permitted) requestedForCell = Math.nextDown(requestedForCell);
				int availableMarkers = domain.maximumParticleCount - domain.particles.count() - insertPieces.size();
				float markerCapacity = availableMarkers * domain.PARTICLE_VOLUME;
				float added = Math.min(requestedForCell, markerCapacity);
				if (!(added > 0)) {
					if (requestedForCell > 0 && availableMarkers == 0) markerBudgetLimited = true;
					if (markerBudgetLimited) break;
					continue;
				}
				int cell = domain.cellIndex(x, y, z);
				double left = added, plannedInCell = 0.0;
				for (int slot = domain.firstOpenSlot(cell, 0); left > 0;
						slot = domain.firstOpenSlot(cell, slot + 1)) {
					if (slot < 0) throw new IllegalStateException("Cut-cell open volume was exhausted while spawning markers");
					float piece = (float) Math.min(domain.PARTICLE_VOLUME, left);
					double pieceMass = (double) piece * (domain.materialRegistry == null
							? domain.config.density() : domain.materialRegistry.material(0).referenceDensity());
					ChemicalMixtureRecord chemical = domain.chemicalParcels == null ? null
							: domain.chemicalParcels.tryDefaultRecord(
									domain.materialRegistry.material(0).id(), piece, domain.config.cellSize(), pieceMass, false);
					if (domain.chemicalParcels != null && chemical == null) break;
					if (chemical != null) {
						long pieceQuanta = chemical.totalQuanta();
						if (Math.addExact(domain.chemicalParcels.totalQuanta(),
								Math.addExact(plannedChemicalQuanta, pieceQuanta))
								> cc.ecisr.mmm.chemistry.ChemicalLimits.MAX_REGION_TOTAL_QUANTA) {
							markerBudgetLimited = true;
							remaining = remaining.subtract(SignedDyadic.of(plannedInCell));
							break cellLoop;
						}
						plannedChemicalQuanta = Math.addExact(plannedChemicalQuanta, pieceQuanta);
					}
					insertPieces.add(new MaterialInsertPiece(x, y, z, slot, piece, 0, pieceMass, chemical));
					plannedInCell += piece;
					left -= piece;
				}
				availableCellVolume[index] = availableCellVolume[index].subtract(SignedDyadic.of(plannedInCell));
				remaining = remaining.subtract(SignedDyadic.of(plannedInCell));
				if (added < requestedForCell) {
					markerBudgetLimited = true;
					break;
				}
				if (remaining.significand().signum() == 0) break;
			}
		} catch (IllegalArgumentException | ArithmeticException refusedPlan) {
			return new FluidDomain.VolumeAddition(0.0F, false);
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
				return new FluidDomain.VolumeAddition(0.0F, false);
			}
		}
		var geometric = planGeometry(insertPieces.stream().map(p -> new LiquidGeometryInsertionPlan.Insertion(p.cellX(), p.cellY(), p.cellZ(), p.volume())).toList());
		int insertion = 0;
		for (MaterialInsertPiece piece : insertPieces) {
			domain.spawnParticle(piece.cellX(), piece.cellY(), piece.cellZ(), piece.slot(), piece.volume(),
					piece.materialHandle(), piece.mass(), piece.chemical(), false, true, geometric == null ? null : geometric.liquid().positions.get(insertion++));
		}
		if (geometric != null) geometric.publish(domain);
		if (!externalAddition.isEmpty()) domain.chemicalParcels.recordExternalSource(externalAddition);
		domain.gridDirty = true;
		return new FluidDomain.VolumeAddition((float) SignedDyadic.of(capacity).subtract(remaining).toDouble(), markerBudgetLimited);
	}

	float removeVolumeNear(int centerX, int centerY, int centerZ, float amount, int maxRadius) {
		domain.validateVolumeEdit(amount, maxRadius);
		if (domain.hasPendingPhaseVolumeWork()) return 0.0F;
		float removed = domain.removeVolumeAcrossCells(domain.nearbyCells(centerX, centerY, centerZ, maxRadius), amount);
		domain.gridDirty = true;
		return removed;
	}

	FluidDomain.ExactChemicalTransfer extractChemical(
			int centerX, int centerY, int centerZ, float volume, int maxRadius,
			java.util.function.Predicate<ChemicalTransferLedger> acceptsLedger
	) {
		if (domain.chemicalParcels == null) throw new IllegalStateException("Chemical extraction requires chemistry");
		if (acceptsLedger == null) throw new IllegalArgumentException("Chemical extraction validator is missing");
		domain.validateVolumeEdit(volume, maxRadius);
		if (volume <= 0.0F) return new FluidDomain.ExactChemicalTransfer(volume == 0.0F, ChemicalTransferLedger.EMPTY);
		if (domain.hasPendingPhaseVolumeWork()) return new FluidDomain.ExactChemicalTransfer(false, ChemicalTransferLedger.EMPTY);
		List<FluidDomain.CellCandidate> cells = domain.nearbyCells(centerX, centerY, centerZ, maxRadius);
		var geometric = new LiquidGeometryExtractionPlan(domain, cells);
		if (!geometric.hasVolume(volume, ignored -> true)) return new FluidDomain.ExactChemicalTransfer(false, ChemicalTransferLedger.EMPTY);

		var remaining = SignedDyadic.of(volume); double settlementTolerance = Math.ulp(volume);
		List<ExtractionAllocation> plan = new ArrayList<>();
		ChemicalTransferLedger.Builder ledger = ChemicalTransferLedger.builder(domain.chemicalParcels.registry());
		for (int marker : geometric.markers) {
			if (remaining.signum() == 0) break;
			float previousVolume = domain.particles.volume(marker);
			double take = Math.min(geometric.volume(marker), observedSpace(remaining));
			float newVolume = (float) (previousVolume - take);
			double actual = (double) previousVolume - newVolume;
			double markerTolerance = Math.ulp(volume);
			if (actual > take && newVolume < previousVolume) {
				newVolume = Math.nextUp(newVolume); actual = (double) previousVolume - newVolume;
				markerTolerance = Math.ulp(previousVolume);
			}
			if (actual <= 0.0) continue;
			double previousMass = domain.particles.mass(marker);
			double newMass = newVolume == 0.0F ? 0.0 : previousMass * newVolume / previousVolume;
			var quantities = LiquidGeometryExtractionPlan.Remainder.plan(domain, marker, newVolume, newMass, take);
			if (quantities == null) continue;
			var removedVolume = domain.particles.exactVolume(marker).subtract(quantities.volume());
			var removedMassQuantity = domain.particles.exactMass(marker).subtract(quantities.mass());
			actual = removedVolume.toDouble();
			newVolume = quantities.observedVolume(); newMass = quantities.observedMass();
			double removedMass = removedMassQuantity.toDouble();
			if (!(removedMass > 0.0)) continue;
			ChemicalParcelStore.SplitPlan chemicalPlan = domain.chemicalParcels.tryPlanTruncate(
					marker, newVolume, removedVolume.toFloat(), newMass, removedMass);
			if (chemicalPlan == null) continue;
			ledger.add(domain.materialRegistry.material(domain.particles.materialHandle(marker)).id(), domain.chemicalParcels.indicator(marker),
					removedVolume, domain.unitScale.kilograms(removedMassQuantity), chemicalPlan.removed());
			LiquidEnergyColumn.Truncation thermalPlan = domain.planThermalTruncate(marker, quantities.mass(),
					chemicalPlan.kept(), domain.thermalState == null ? Double.NaN : domain.thermalMarkerTemperature(marker));
			plan.add(new ExtractionAllocation(quantities, chemicalPlan, thermalPlan));
			geometric.reserve(quantities);
			settlementTolerance = Math.max(settlementTolerance, markerTolerance);
			remaining = remaining.subtract(removedVolume);
		}
		if (plan.isEmpty() || remaining.compareTo(SignedDyadic.of(settlementTolerance)) > 0) {
			return new FluidDomain.ExactChemicalTransfer(false, ChemicalTransferLedger.EMPTY);
		}
		ChemicalTransferLedger removed = ledger.build();
		if (removed.totalQuanta() > ChemicalLimits.MAX_BUCKET_TOTAL_QUANTA) {
			return new FluidDomain.ExactChemicalTransfer(false, ChemicalTransferLedger.EMPTY);
		}
		if (!acceptsLedger.test(removed)) return new FluidDomain.ExactChemicalTransfer(false, ChemicalTransferLedger.EMPTY);
		geometric.finish(plan.stream().map(ExtractionAllocation::quantities).toList());
		SignedDyadic extractionEnergy = exactExtractionEnergy(plan);
		for (ExtractionAllocation allocation : plan) {
			if (allocation.chemicalPlan().kept() != null) {
				domain.chemicalParcels.commitTruncate(allocation.particle(), allocation.chemicalPlan());
			}
			domain.particles.setQuantities(allocation.particle(), allocation.quantities().volume(), allocation.quantities().mass());
			domain.commitThermalTruncate(allocation.particle(), allocation.thermalPlan());
		}
		for (int marker = domain.particles.count() - 1; marker >= 0; marker--) if (domain.particles.exactVolume(marker).signum() == 0) domain.removeParticle(marker);
		geometric.publish();
		domain.gridDirty = true;
		double removedEnergy = extractionEnergy.toDouble();
		if (domain.thermalState != null) {
			domain.thermalState.settleExternalExtraction(extractionEnergy, removedEnergy);
		}
		return new FluidDomain.ExactChemicalTransfer(true, removed, removedEnergy);
	}
	FluidDomain.ExactMixtureTransfer extractMixture(
			int centerX, int centerY, int centerZ, float volume, int maxRadius, int requiredMaterialHandle
	) {
		if (domain.hasPendingPhaseVolumeWork()) return new FluidDomain.ExactMixtureTransfer(false, List.of());
		java.util.List<FluidDomain.CellCandidate> cells = domain.nearbyCells(centerX, centerY, centerZ, maxRadius);
		var geometric = new LiquidGeometryExtractionPlan(domain, cells);
		if (!geometric.hasVolume(volume, p -> matchesMaterialExtraction(p, requiredMaterialHandle))) return new FluidDomain.ExactMixtureTransfer(false, List.of());

		var remaining = SignedDyadic.of(volume);
		double settlementTolerance = Math.ulp(volume);
		java.util.List<ExtractionAllocation> plan = new java.util.ArrayList<>();
		int ledgerSize = domain.materialRegistry == null ? 1 : domain.materialRegistry.size();
		SignedDyadic[] removedVolumes = new SignedDyadic[ledgerSize];
		SignedDyadic[] removedMasses = new SignedDyadic[ledgerSize];
		java.util.Arrays.fill(removedVolumes, SignedDyadic.ZERO);
		java.util.Arrays.fill(removedMasses, SignedDyadic.ZERO);
		java.util.Set<Integer> changedHandles = new java.util.TreeSet<>();
		for (int i : geometric.markers) {
			if (remaining.signum() == 0) break;
			if (!matchesMaterialExtraction(i, requiredMaterialHandle)) continue;
			float previousVolume = domain.particles.volume(i);
			double take = Math.min(geometric.volume(i), observedSpace(remaining));
			float newVolume = (float) (previousVolume - take);
			double actual = (double) previousVolume - newVolume;
			double markerTolerance = Math.ulp(volume);
			// A nearest-float remainder may round downward and remove more than requested.
			// Keep the authoritative marker delta at or below the caller's remaining cap;
			// a later marker can settle any still-representable remainder.
			if (actual > take && newVolume < previousVolume) {
				newVolume = Math.nextUp(newVolume);
				actual = (double) previousVolume - newVolume;
				markerTolerance = Math.ulp(previousVolume);
			}
			if (actual <= 0.0) continue;
			double previousMass = domain.particles.mass(i);
			double newMass = newVolume == 0.0F ? 0.0 : previousMass * newVolume / previousVolume;
			var quantities = LiquidGeometryExtractionPlan.Remainder.plan(domain, i, newVolume, newMass, take);
			if (quantities == null) continue;
			newVolume = quantities.observedVolume(); newMass = quantities.observedMass();
			var removedVolume = domain.particles.exactVolume(i).subtract(quantities.volume());
			var removedMass = domain.particles.exactMass(i).subtract(quantities.mass());
			actual = removedVolume.toDouble();
			if (!(removedMass.toDouble() > 0.0)) continue;
			int handle = domain.particles.materialHandle(i);
			ChemicalParcelStore.SplitPlan chemicalPlan = domain.chemicalParcels == null ? null
					: domain.chemicalParcels.tryPlanTruncate(i, newVolume, removedVolume.toFloat(),
					newMass, removedMass.toDouble());
			if (domain.chemicalParcels != null && chemicalPlan == null) continue;
			LiquidEnergyColumn.Truncation thermalPlan = domain.planThermalTruncate(i, quantities.mass(),
					chemicalPlan == null ? null : chemicalPlan.kept(),
					domain.thermalState == null ? Double.NaN : domain.thermalMarkerTemperature(i));
			plan.add(new ExtractionAllocation(quantities, chemicalPlan, thermalPlan));
			geometric.reserve(quantities);
			changedHandles.add(handle);
			removedVolumes[handle] = removedVolumes[handle].add(removedVolume);
			removedMasses[handle] = removedMasses[handle].add(removedMass);
			settlementTolerance = Math.max(settlementTolerance, markerTolerance);
			remaining = remaining.subtract(removedVolume);
		}
		if (plan.isEmpty() || remaining.compareTo(SignedDyadic.of(settlementTolerance)) > 0) return new FluidDomain.ExactMixtureTransfer(false, List.of());
		// Derive settlement from planned carrier deltas before committing. Subtracting rounded
		// whole-domain totals can erase a small extraction or include unrelated summation drift.
		List<FluidDomain.MaterialPortion> result = new ArrayList<>(changedHandles.size());
		for (int handle : changedHandles) {
			double removedVolume = removedVolumes[handle].toDouble();
			double removedMass = removedMasses[handle].toDouble();
			if (!(Double.isFinite(removedVolume) && removedVolume > 0.0
					&& Double.isFinite(removedMass) && removedMass > 0.0)) {
				return new FluidDomain.ExactMixtureTransfer(false, List.of());
			}
			result.add(new FluidDomain.MaterialPortion(handle, removedVolume, removedMass));
		}
		geometric.finish(plan.stream().map(ExtractionAllocation::quantities).toList());
		SignedDyadic extractionEnergy = exactExtractionEnergy(plan);
		for (ExtractionAllocation allocation : plan) {
			if (domain.chemicalParcels != null && allocation.chemicalPlan().kept() != null) {
				domain.chemicalParcels.commitTruncate(allocation.particle(), allocation.chemicalPlan());
			}
			domain.particles.setQuantities(allocation.particle(), allocation.quantities().volume(), allocation.quantities().mass());
			domain.commitThermalTruncate(allocation.particle(), allocation.thermalPlan());
		}
		// Do not compact during indexed writes: removeParticle swaps the last marker into the current
		// index and could invalidate a later planned ordinal. Compact only after every ledger edit.
		for (int particle = domain.particles.count() - 1; particle >= 0; particle--) {
			if (domain.particles.exactVolume(particle).signum() == 0) domain.removeParticle(particle);
		}
		geometric.publish();
		domain.gridDirty = true;
		double removedEnergy = extractionEnergy.toDouble();
		if (domain.thermalState != null) {
			domain.thermalState.settleExternalExtraction(extractionEnergy, removedEnergy);
		}
		return new FluidDomain.ExactMixtureTransfer(true, List.copyOf(result), removedEnergy);
	}

	private boolean matchesMaterialExtraction(int marker, int requiredMaterialHandle) {
		return (requiredMaterialHandle < 0 || domain.particles.materialHandle(marker) == requiredMaterialHandle)
				&& (domain.chemicalParcels == null || domain.chemicalParcels.isCanonicalDefaultCarrier(
						marker, domain.materialRegistry.material(domain.particles.materialHandle(marker)).id()));
	}
	private SignedDyadic exactExtractionEnergy(List<ExtractionAllocation> plan) {
		if (domain.thermalState == null) return SignedDyadic.ZERO;
		SignedDyadic carrierEnergy = SignedDyadic.ZERO;
		for (ExtractionAllocation allocation : plan) {
			LiquidEnergyColumn.Truncation thermal = allocation.thermalPlan();
			carrierEnergy = carrierEnergy.add(SignedDyadic.of(
					domain.thermalState.liquidInternalEnergy(allocation.particle()))
					.subtract(SignedDyadic.of(thermal.newEnergy())));
		}
		return carrierEnergy;
	}

	/**
	 * Plans and restores an exact chemical ledger without substituting a default carrier:
	 * per-species binary volume allocation across whole markers, registry compatibility for
	 * every produced mixture, and wholesale rejection when any piece cannot be represented.
	 */
	FluidDomain.ExactChemicalTransfer insertChemical(
			int centerX, int centerY, int centerZ, ChemicalTransferLedger ledger, int maxRadius,
			boolean externalSource, Double internalEnergy
	) {
		if (domain.chemicalParcels == null) throw new IllegalStateException("Chemical insertion requires chemistry");
		if (ledger == null || ledger.isEmpty()) return new FluidDomain.ExactChemicalTransfer(ledger != null, ChemicalTransferLedger.EMPTY);
		if (maxRadius < 0) throw new IllegalArgumentException("maxRadius cannot be negative");
		if (domain.hasPendingPhaseVolumeWork()) return new FluidDomain.ExactChemicalTransfer(false, ChemicalTransferLedger.EMPTY);
		if (!domain.canAddCarrierVolume(ledger.totalVolume())) {
			return new FluidDomain.ExactChemicalTransfer(false, ChemicalTransferLedger.EMPTY);
		}
		try {
			if (Math.addExact(domain.chemicalParcels.totalQuanta(), ledger.totalQuanta())
					> cc.ecisr.mmm.chemistry.ChemicalLimits.MAX_REGION_TOTAL_QUANTA) {
				return new FluidDomain.ExactChemicalTransfer(false, ChemicalTransferLedger.EMPTY);
			}
		} catch (ArithmeticException overflow) {
			return new FluidDomain.ExactChemicalTransfer(false, ChemicalTransferLedger.EMPTY);
		}
		SignedDyadic[] availableCellVolume = domain.availableCellVolumes();
		List<FluidDomain.CellCandidate> nearby = domain.nearbyCells(centerX, centerY, centerZ, maxRadius);
		List<ChemicalInsertPiece> pieces = new ArrayList<>();
		try {
		for (ChemicalTransferLedger.Group group : ledger.groups()) {
			if (!group.volumeQuantity().equals(SignedDyadic.of(group.volume()))
					|| !group.massKilogramsQuantity().equals(SignedDyadic.of(group.massKilograms())))
				return new FluidDomain.ExactChemicalTransfer(false, ChemicalTransferLedger.EMPTY);
			int material = domain.materialRegistry.handle(group.materialId());
			double remaining = group.volume();
			List<ChemicalPieceDraft> groupPieces = new ArrayList<>();
			for (FluidDomain.CellCandidate candidate : nearby) {
				if (remaining <= 0.0) break;
				double capacity = observedSpace(availableCellVolume[candidate.index()]);
				double allocation = Math.min(capacity, remaining);
				if (allocation <= 0.0) continue;
				int cellX = candidate.index() % domain.sizeX;
				int yz = candidate.index() / domain.sizeX;
				int cellY = yz % domain.sizeY, cellZ = yz / domain.sizeY;
				double left = allocation;
				while (left > 0.0) {
					float piece = (float) Math.min(domain.PARTICLE_VOLUME, left);
					while (piece > left) piece = Math.nextDown(piece);
					if (!(piece > 0.0F)) return new FluidDomain.ExactChemicalTransfer(false, ChemicalTransferLedger.EMPTY);
					groupPieces.add(new ChemicalPieceDraft(cellX, cellY, cellZ, piece, material));
					if ((long) domain.particles.count() + pieces.size() + groupPieces.size() > domain.maximumParticleCount) {
						return new FluidDomain.ExactChemicalTransfer(false, ChemicalTransferLedger.EMPTY);
					}
					left -= piece;
				}
				availableCellVolume[candidate.index()] = availableCellVolume[candidate.index()].subtract(SignedDyadic.of(allocation));
				remaining -= allocation;
			}
			if (remaining > 0.0) return new FluidDomain.ExactChemicalTransfer(false, ChemicalTransferLedger.EMPTY);
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
				if (distributed[piece].isEmpty()) return new FluidDomain.ExactChemicalTransfer(false, ChemicalTransferLedger.EMPTY);
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
			return new FluidDomain.ExactChemicalTransfer(false, ChemicalTransferLedger.EMPTY);
		}
		if (externalSource) try {
			domain.chemicalParcels.requireCanRecordExternalSource(ledger);
		} catch (IllegalArgumentException | ArithmeticException refusedAudit) {
			return new FluidDomain.ExactChemicalTransfer(false, ChemicalTransferLedger.EMPTY);
		}
		LiquidEnergyColumn.ExternalLiquidInsertionPlan thermalPlan = null;
		if (domain.thermalState != null && internalEnergy != null) {
			List<LiquidEnergyColumn.LiquidInsertion> thermalInsertions = new ArrayList<>(pieces.size());
			for (ChemicalInsertPiece piece : pieces) thermalInsertions.add(new LiquidEnergyColumn.LiquidInsertion(
					piece.materialHandle(), piece.mass(), piece.mixture()));
			thermalPlan = domain.thermalState.planExternalLiquidInsertions(thermalInsertions, internalEnergy,
					domain.thermalMarkerContext());
		}
		var geometric = planGeometry(pieces.stream().map(p -> new LiquidGeometryInsertionPlan.Insertion(p.cellX(), p.cellY(), p.cellZ(), p.volume())).toList());
		int firstInsertedOrdinal = domain.particles.count(); int insertion = 0;
		for (ChemicalInsertPiece piece : pieces) {
			int slot = domain.firstOpenSlot(domain.cellIndex(piece.cellX(), piece.cellY(), piece.cellZ()), 0);
			if (slot < 0) throw new IllegalStateException("Chemical insertion lost its planned open slot");
			domain.spawnParticle(piece.cellX(), piece.cellY(), piece.cellZ(), slot, piece.volume(),
					piece.materialHandle(), piece.mass(), piece.mixture(), piece.indicator(), thermalPlan == null, geometric == null ? null : geometric.liquid().positions.get(insertion++));
		}
		if (geometric != null) geometric.publish(domain);
		if (thermalPlan != null) domain.thermalState.commitExternalLiquidInsertions(firstInsertedOrdinal, thermalPlan);
		if (externalSource) domain.chemicalParcels.recordExternalSource(ledger);
		domain.gridDirty = true;
		return new FluidDomain.ExactChemicalTransfer(true, ledger, internalEnergy == null ? 0.0 : internalEnergy);
	}

	private record ExtractionAllocation(
			LiquidGeometryExtractionPlan.Remainder quantities, ChemicalParcelStore.SplitPlan chemicalPlan,
			LiquidEnergyColumn.Truncation thermalPlan
	) { int particle() { return quantities.marker(); } }

	private InsertionGeometryPlan planGeometry(List<LiquidGeometryInsertionPlan.Insertion> insertions) {
		if (domain.liquidGeometryState == null || insertions.isEmpty()) return null;
		LiquidGeometryStateMapper.requireCompatible(domain.liquidGeometryState, domain);
		var liquid = new LiquidGeometryInsertionPlan(domain.liquidGeometryState, domain.sizeX, domain.sizeY, domain.sizeZ,
				domain.config.cellSize(), domain.geometry.cutCells.copyMicroMasks(), insertions, LiquidGeometryInsertionPlan.MAXIMUM_SOURCE_REGIONS);
		return new InsertionGeometryPlan(liquid, domain.gasState.geometry == null ? null : new ResolvedGasVolumeEditPlan(domain, liquid.geometry));
	}

	private record InsertionGeometryPlan(LiquidGeometryInsertionPlan liquid, ResolvedGasVolumeEditPlan gas) {
		void publish(FluidDomain domain) {
			if (gas == null) domain.liquidGeometryState = liquid.geometry;
			else gas.publish(domain);
		}
	}

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
