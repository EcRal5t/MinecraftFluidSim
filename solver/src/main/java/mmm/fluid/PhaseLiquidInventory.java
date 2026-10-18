package mmm.fluid;

import mmm.chemistry.ChemicalMixtureRecord;
import mmm.chemistry.ChemicalParcelStore;
import mmm.granular.SolidVolume;
import mmm.thermal.ThermalStateFunction;
import mmm.units.SignedDyadic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static mmm.fluid.PhaseChangeStageSupport.*;

/** One cell's actual liquid source, shared by phase trials and their final publication. */
final class PhaseLiquidInventory {
	private record Marker(int ordinal, int material, int thermal, int support, float volume, long massUnits,
	                      double kilograms, float x, float y, float z, long wholeSvq, long upperSvq,
	                      ChemicalMixtureRecord mixture, boolean indicator,
	                      ThermalStateFunction.InventoryPortion portion) { }
	private record Future(int ordinal, ThermalStateFunction.InventoryPortion portion) { }
	private final PhaseChangeStageSupport support;
	private final int cell, count;
	private final List<Marker> markers;

	PhaseLiquidInventory(PhaseChangeStageSupport support, int cell) {
		this.support = support; this.cell = cell;
		var state = support.state(); var particles = state.particles(); var chemistry = support.chemistry();
		count = particles.count();
		var captured = new ArrayList<Marker>();
		for (int ordinal = 0; ordinal < count; ordinal++) if (support.particleCell(ordinal) == cell) {
			int material = particles.materialHandle(ordinal), thermal = support.thermal().thermalMaterialHandleForFluid(material);
			float volume = particles.volume(ordinal), x = particles.positionX(ordinal), y = particles.positionY(ordinal), z = particles.positionZ(ordinal);
			long mass = particles.massUnits(ordinal); double kilograms = mmm.units.Mass.kilograms(mass);
			var mixture = chemistry == null ? null : chemistry.record(ordinal);
			var portion = new ThermalStateFunction.InventoryPortion(thermal, kilograms,
					mixture == null ? Map.of() : mixture.stableAmounts(chemistry.registry()));
			captured.add(new Marker(ordinal, material, thermal,
					CellRemapMicroTopology.positionSupport(state.geometry().cutCells.mask(cell), state.cellSize(), x, y, z),
					volume, mass, kilograms, x, y, z, SolidVolume.cellVolumeSvqAtMost(volume),
					SolidVolume.cellVolumeSvqAtLeast(volume), mixture, chemistry != null && chemistry.indicator(ordinal), portion));
		}
		markers = List.copyOf(captured);
	}
	List<ThermalStateFunction.InventoryPortion> inventory() { return markers.stream().map(Marker::portion).toList(); }

	Removal remove(int liquidHandle, int supportMask, long requestedSvq, long requestedMass,
	               boolean[] volatileSpecies, boolean selective) {
		var chemistry = support.chemistry();
		var allocations = new ArrayList<PhaseMarkerAllocation>();
		var moved = chemistry == null ? null : new PhaseCompositionBuilder(chemistry);
		var changes = new PhaseMarkerAllocation[markers.size()];
		long actualRemovedMass = 0;
		long remainingSvq = requestedSvq;
		long remainingMass = requestedMass;
		for (int index = markers.size() - 1; index >= 0 && remainingSvq > 0; index--) {
			var marker = markers.get(index);
			if (marker.material() != liquidHandle || supportMask >= 0 && marker.support() != supportMask
					|| marker.indicator() || marker.wholeSvq() == 0) continue;
			long takeSvq = Math.min(marker.wholeSvq(), remainingSvq);
			if (selective && chemistry != null) {
				boolean volatilePresent = false, retainedPresent = false;
				for (int entry = 0; entry < marker.mixture().entryCount(); entry++) {
					if (volatileSpecies[marker.mixture().speciesHandle(entry)]) volatilePresent = true;
					else retainedPresent = true;
				}
				if (!volatilePresent) continue;
				if (takeSvq == marker.wholeSvq() && retainedPresent) {
					if (marker.wholeSvq() <= 1) continue;
					takeSvq--;
				}
			}
			long takeMass = takeSvq == remainingSvq ? remainingMass : mmm.units.Mass.portion(marker.massUnits(), takeSvq, marker.wholeSvq());
			if (!(Double.isFinite(takeMass) && takeMass > 0 && takeMass <= remainingMass))
				throw new PhaseCandidateUnavailableException("Phase batch mass cannot be allocated across its liquid donors");
			long takeSolverMass = takeMass;
			boolean full = takeSvq == marker.wholeSvq() && marker.wholeSvq() == marker.upperSvq();
			float volume = full ? 0 : adjustedMarkerVolume(marker.volume(), -takeSvq, "Phase removal");
			long mass = Math.subtractExact(marker.massUnits(), takeSolverMass);
			if (full ? mass != 0 : !(mass > 0))
				throw new PhaseCandidateUnavailableException("Phase batch mass/volume split is not representable");
			if (full) mass = 0;
			support.representedMassTransfer(marker.massUnits(), mass, -takeMass);
			if (!selective) actualRemovedMass = Math.addExact(actualRemovedMass, takeMass);
			ChemicalParcelStore.SplitPlan chemical;
			try {
				chemical = chemistry == null ? null : selective
						? chemistry.planSelectivePhaseTruncate(marker.ordinal(), volume, SolidVolume.toCellVolume(takeSvq), mass, takeSolverMass, volatileSpecies)
						: chemistry.planTruncate(marker.ordinal(), volume, SolidVolume.toCellVolume(takeSvq), mass, takeSolverMass);
			} catch (mmm.chemistry.ChemicalReactionEngine.BudgetExceededException unavailable) {
				throw new PhaseCandidateUnavailableException("Phase batch is below the chemical carrier resolution", unavailable);
			}
			if (chemical != null) moved.add(chemical.removed());
			var allocation = new PhaseMarkerAllocation(marker.ordinal(), volume, mass, full, chemical);
			changes[index] = allocation; allocations.add(allocation);
			remainingSvq -= takeSvq; remainingMass -= takeMass;
		}
		if (remainingSvq != 0 || remainingMass != 0)
			throw new PhaseCandidateUnavailableException("Phase batch cannot be allocated to its liquid inventory");
		if (!selective) representedPhysicalMassTransfer(0L, actualRemovedMass, requestedMass);
		var plan = new PhaseRemovalPlan(List.copyOf(allocations), chemistry == null ? PhaseComposition.EMPTY : moved.build());
		int[] ordinals = new int[markers.size()];
		float[] volumes = new float[markers.size()];
		for (int index = 0; index < markers.size(); index++) {
			var allocation = changes[index];
			ordinals[index] = allocation != null && allocation.fullRemoval() ? -1 : markers.get(index).ordinal();
			volumes[index] = allocation == null ? markers.get(index).volume() : allocation.newVolume();
		}
		int remainingCount = count;
		for (var allocation : allocations) if (allocation.fullRemoval()) {
			int last = --remainingCount;
			for (int index = 0; index < ordinals.length; index++) if (ordinals[index] == last) ordinals[index] = allocation.marker();
		}
		var future = new ArrayList<Future>();
		for (int index = 0; index < markers.size(); index++) if (ordinals[index] >= 0) {
			var marker = markers.get(index); var allocation = changes[index];
			future.add(new Future(ordinals[index], allocation == null ? marker.portion() : portion(marker,
					allocation.newMassUnits(), chemistry == null ? null : allocation.chemicalPlan().kept())));
		}
		future.sort(Comparator.comparingInt(Future::ordinal));
		return new Removal(plan, future.stream().map(Future::portion).toList(), volumes);
	}

	final class Removal {
		private final PhaseRemovalPlan plan;
		private final List<ThermalStateFunction.InventoryPortion> inventory;
		private final float[] volumes;
		private PhaseLiquidGeometryEdit geometry;
		private Removal(PhaseRemovalPlan plan, List<ThermalStateFunction.InventoryPortion> inventory, float[] volumes) {
			this.plan = plan; this.inventory = inventory; this.volumes = volumes;
		}
		PhaseRemovalPlan plan() { return plan; }
		List<ThermalStateFunction.InventoryPortion> inventory() { return inventory; }
		PhaseLiquidGeometryEdit geometry() {
			if (geometry == null) {
				var future = new ArrayList<PhaseLiquidGeometryEdit.InsertedMarker>();
				for (int index = 0; index < markers.size(); index++) if (volumes[index] > 0) {
					var marker = markers.get(index);
					future.add(new PhaseLiquidGeometryEdit.InsertedMarker(volumes[index], marker.x(), marker.y(), marker.z()));
				}
				geometry = PhaseLiquidGeometryEdit.fromMarkers(support.state(), cell, future);
			}
			return geometry;
		}
	}

	List<ThermalStateFunction.InventoryPortion> afterCondensation(int liquidHandle, int target, long targetMass,
	                                                           ChemicalMixtureRecord mixture, long appendedPhysicalMass) {
		var result = new ArrayList<ThermalStateFunction.InventoryPortion>();
		boolean found = target < 0;
		for (var marker : markers) {
			if (marker.ordinal() == target) found = true;
			result.add(marker.ordinal() == target ? portion(marker, targetMass, mixture) : marker.portion());
		}
		if (!found) throw new IllegalArgumentException("Phase liquid receiver belongs to another source cell");
		if (target < 0) result.add(new ThermalStateFunction.InventoryPortion(support.thermal().thermalMaterialHandleForFluid(liquidHandle),
				mmm.units.Mass.kilograms(appendedPhysicalMass),
				mixture == null ? Map.of() : mixture.stableAmounts(support.chemistry().registry())));
		return List.copyOf(result);
	}

	PhaseLiquidGeometryEdit geometryAfterCondensation(int target, float targetVolume, float appendedVolume, float[] appendedPosition) {
		var future = new ArrayList<PhaseLiquidGeometryEdit.InsertedMarker>();
		boolean found = target < 0;
		for (var marker : markers) {
			if (marker.ordinal() == target) found = true;
			future.add(new PhaseLiquidGeometryEdit.InsertedMarker(
					marker.ordinal() == target ? targetVolume : marker.volume(), marker.x(), marker.y(), marker.z()));
		}
		if (!found) throw new IllegalArgumentException("Phase liquid receiver belongs to another source cell");
		if (target < 0) future.add(new PhaseLiquidGeometryEdit.InsertedMarker(appendedVolume,
				appendedPosition[0], appendedPosition[1], appendedPosition[2]));
		return PhaseLiquidGeometryEdit.fromMarkers(support.state(), cell, future);
	}

	private ThermalStateFunction.InventoryPortion portion(Marker marker, long massUnits, ChemicalMixtureRecord mixture) {
		return new ThermalStateFunction.InventoryPortion(marker.thermal(), mmm.units.Mass.kilograms(massUnits),
				mixture == null ? Map.of() : mixture.stableAmounts(support.chemistry().registry()));
	}
}
