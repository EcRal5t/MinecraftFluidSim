package mmm.fluid;

import mmm.thermal.ThermalDomainState;

import mmm.chemistry.ChemicalLimits;
import mmm.chemistry.ChemicalMixtureRecord;
import mmm.chemistry.ChemicalParcelStore;
import mmm.chemistry.ChemicalRegistry;
import mmm.chemistry.ExactRational;
import mmm.fluid.air.GasComponentAmount;
import mmm.granular.GranularCouplingFields;
import mmm.granular.SolidVolume;
import mmm.thermal.CoherentPhaseStore;
import mmm.thermal.ThermalCarrierContext;
import mmm.units.SignedDyadic;
import mmm.units.SolverUnitScale;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Shared exact inventory and carrier operations used by both phase-change stages. */
final class PhaseChangeStageSupport {
	record Workspace(
			int sizeX, int sizeY, int sizeZ, int cellCount, int maximumParticleCount,
			float cellSize, SolverUnitScale unitScale, DomainGeometry geometry, MarkerGridIndex markerGrid,
			PressureProjectionWorkspace projection, LiquidParticleStore particles,
			GasDomainState gasState, PendingPhaseVolumeWork pendingPhaseVolume,
			ThermalCarrierContext thermalMarkers
	) { }

	interface Access {
		ThermalDomainState thermalState();
		ChemicalParcelStore chemistry();
		FluidMaterialRegistry materialRegistry();
		GranularDomainState granularState();
		float pressureReference();
		int particleCell(int marker);
		int firstOpenSlot(int cell, int first);
		float[] markerSlotPosition(int cell, int slot);
		void removeParticle(int marker);
		void spawnParticle(
				int cellX, int cellY, int cellZ, int slot, float volume, int materialHandle,
				long massUnits, ChemicalMixtureRecord mixture
		);
		void ensureInventoryGrid();
		void refreshGasTopology();
		GasRuntimeGeometry.PhaseSource prepareGasGeometrySource(int editedCell);
		GasTopologyCoordinator.GasTopologyPlan planGasGeometry(PhaseLiquidGeometryEdit edit, int node,
				mmm.fluid.air.AirNetwork.PhaseMutationPlan gas, double energy, ThermalDomainState.PhaseCellEnergyPlan thermal,
				GasRuntimeGeometry.PhaseSource source);
		void requireGasGeometryCurrent(GasTopologyCoordinator.GasTopologyPlan topology,
				mmm.fluid.air.AirNetwork.PhaseMutationPlan gas, ThermalDomainState.PhaseCellEnergyPlan thermal);
		void commitGasGeometry(GasTopologyCoordinator.GasTopologyPlan topology);
		void refreshCoherentOccupancy();
		void markGridDirty();
		boolean canAddCarrierVolume(double volume);
		double effectiveOpenCellVolume(int cell);
	}

	private final Workspace state;
	private final Access access;
	private final GranularCouplingFields phaseGranularOccupancy;

	PhaseChangeStageSupport(Workspace state, Access access) {
		this.state = state;
		this.access = access;
		this.phaseGranularOccupancy = new GranularCouplingFields(
				state.sizeX(), state.sizeY(), state.sizeZ(), state.cellSize());
	}

	Workspace state() { return state; }
	ThermalDomainState thermal() { return access.thermalState(); }
	ChemicalParcelStore chemistry() { return access.chemistry(); }
	FluidMaterialRegistry materialRegistry() { return access.materialRegistry(); }
	GasDomainState gasState() { return state.gasState(); }
	float pressureReference() { return access.pressureReference(); }
	int particleCell(int marker) { return access.particleCell(marker); }
	int firstOpenSlot(int cell) { return access.firstOpenSlot(cell, 0); }
	int firstOpenSlot(int cell, int first) { return access.firstOpenSlot(cell, first); }
	float[] markerSlotPosition(int cell, int slot) { return access.markerSlotPosition(cell, slot); }
	void ensureInventoryGrid() { access.ensureInventoryGrid(); }
	void refreshGasTopology() { access.refreshGasTopology(); }
	GasRuntimeGeometry.PhaseSource prepareGasGeometrySource(int editedCell) { return access.prepareGasGeometrySource(editedCell); }
	GasTopologyCoordinator.GasTopologyPlan planGasGeometry(PhaseLiquidGeometryEdit edit, int node,
			mmm.fluid.air.AirNetwork.PhaseMutationPlan gas, double energy, ThermalDomainState.PhaseCellEnergyPlan thermal,
			GasRuntimeGeometry.PhaseSource source) {
		var capacities = CellRemapMicroTopology.capacities(edit.collisionMask(),SignedDyadic.of(access.effectiveOpenCellVolume(edit.cell())));
		for(int i=0;i<capacities.length;i++) if(edit.components().get(i).volume().compareTo(capacities[i])>0)
			throw new PhaseCandidateUnavailableException("Phase liquid inventory exceeds its connected pore capacity");
		return access.planGasGeometry(edit, node, gas, energy, thermal, source);
	}
	void requireGasGeometryCurrent(GasTopologyCoordinator.GasTopologyPlan topology,
			mmm.fluid.air.AirNetwork.PhaseMutationPlan gas, ThermalDomainState.PhaseCellEnergyPlan thermal) {
		access.requireGasGeometryCurrent(topology, gas, thermal);
	}
	void commitGasGeometry(GasTopologyCoordinator.GasTopologyPlan topology) { access.commitGasGeometry(topology); }
	void refreshCoherentOccupancy() { access.refreshCoherentOccupancy(); }
	void markGridDirty() { access.markGridDirty(); }
	boolean canExpandCondensedVolume(long svq) {
		return svq <= 0 || access.canAddCarrierVolume(svq / (double) SolidVolume.SVQ_PER_CELL);
	}

	int fluidMaterialHandle(String stableId) {
		FluidMaterialRegistry registry = access.materialRegistry();
		for (int handle = 0; handle < registry.size(); handle++) {
			if (registry.material(handle).id().equals(stableId)) return handle;
		}
		throw new FluidDomain.ThermalPhaseBlockedException(
				"Thermal transition liquid carrier is not bound");
	}

	long granularSvq(int cell) {
		return phaseGranularOccupancy.solidSvq(cell);
	}

	/** Phase capacity uses the same positioned SVQ attribution as mechanics and restore. */
	void prepareGranularOccupancy() {
		GranularDomainState granular = access.granularState();
		if (granular == null) phaseGranularOccupancy.clear();
		else phaseGranularOccupancy.rebuild(granular.parcels, granular.deposits);
	}

	void commitLiquidRemoval(int cell, PhaseRemovalPlan plan) {
		ChemicalParcelStore chemistry = access.chemistry();
		for (PhaseMarkerAllocation allocation : plan.allocations()) {
			if (allocation.fullRemoval()) {
				access.removeParticle(allocation.marker());
			} else {
				if (chemistry != null) {
					chemistry.markDirtyCell(cell);
					chemistry.commitTruncate(allocation.marker(), allocation.chemicalPlan());
				}
				state.particles().setVolume(allocation.marker(), allocation.newVolume());
				state.particles().setMassUnits(allocation.marker(), allocation.newMassUnits());
			}
		}
	}

	void spawnParticle(
			int cell, int slot, float volume, int materialHandle, long massUnits,
			ChemicalMixtureRecord mixture
	) {
		int x = cell % state.sizeX();
		int yz = cell / state.sizeX();
		int y = yz % state.sizeY();
		int z = yz / state.sizeY();
		access.spawnParticle(x, y, z, slot, volume, materialHandle, massUnits, mixture);
	}

	void commitPendingVolume(int cell, long signedSvq) {
		if (access.thermalState() == null || signedSvq == 0L) return;
		SignedDyadic delta = new SignedDyadic(java.math.BigInteger.valueOf(signedSvq), -24);
		state.pendingPhaseVolume().add(cell, delta);
	}

	void representedMassTransfer(long beforeUnits, long afterUnits, long requestedUnits) {
		representedPhysicalMassTransfer(beforeUnits, afterUnits, requestedUnits);
	}
	void representedGasMassTransfer(int node, mmm.fluid.air.GasInventory after,
			String componentId, long requestedUnits) {
		GasComponentAmount beforeComponent = gasState().network.inventory(node).component(componentId);
		GasComponentAmount afterComponent = after.component(componentId);
		representedPhysicalMassTransfer(beforeComponent == null ? 0L : beforeComponent.massUnits(),
				afterComponent == null ? 0L : afterComponent.massUnits(), requestedUnits);
	}
	static void representedPhysicalMassTransfer(long beforeUnits, long afterUnits, long requestedUnits) {
		if (beforeUnits < 0 || afterUnits < 0 || Math.subtractExact(afterUnits, beforeUnits) != requestedUnits)
			throw new PhaseCandidateUnavailableException("Phase transfer mass counts do not close");
	}

	static double massTolerance(double mass) {
		return Math.max(1.0E-12, Math.ulp(mass) * 32.0);
	}

	static float adjustedMarkerVolume(float volume, long signedSvq, String operation) {
		if (!(Float.isFinite(volume) && volume > 0))
			throw new IllegalArgumentException("Phase marker volume must be finite and positive");
		try {
			return SolidVolume.addCellVolumeSvqExact(volume, signedSvq);
		} catch (IllegalArgumentException unrepresentableTail) {
			throw new PhaseCandidateUnavailableException(
					operation + " SVQ is not exactly representable in the marker volume", unrepresentableTail);
		}
	}

	record PhaseCellInventory(
			long lowerVolumeSvq, long upperVolumeSvq, long lowerMassUnits, long upperMassUnits,
			long freezableUpperSvq, long freezableUpperMassUnits
	) {
		double lowerMass() { return mmm.units.Mass.kilograms(lowerMassUnits); }
		double upperMass() { return mmm.units.Mass.kilograms(upperMassUnits); }
		double freezableUpperMass() { return mmm.units.Mass.kilograms(freezableUpperMassUnits); }
	}

	record PhaseMarkerAllocation(
			int marker, float newVolume, long newMassUnits, boolean fullRemoval,
			ChemicalParcelStore.SplitPlan chemicalPlan
	) { }

	record PhaseRemovalPlan(
			List<PhaseMarkerAllocation> allocations, PhaseComposition movedComposition
	) {
		boolean carrierTopologyChanged() {
			for (PhaseMarkerAllocation allocation : allocations) {
				if (allocation.fullRemoval()) return true;
			}
			return false;
		}
	}

	/** Exact species quanta plus the independently carried mass residual across a phase boundary. */
	record PhaseComposition(Map<String, Long> amounts, long massResidualUnits) {
		static final PhaseComposition EMPTY = new PhaseComposition(Map.of(), 0L);

		PhaseComposition {
			if (amounts == null || amounts.size() > ChemicalLimits.MAX_SPECIES) {
				throw new IllegalArgumentException("Condensed phase composition is invalid");
			}
			TreeMap<String, Long> canonical = new TreeMap<>();
			long total = 0L;
			for (Map.Entry<String, Long> entry : amounts.entrySet()) {
				if (entry.getKey() == null || entry.getValue() == null || entry.getValue() <= 0L) {
					throw new IllegalArgumentException("Condensed phase species amount is invalid");
				}
				total = Math.addExact(total, entry.getValue());
				canonical.put(entry.getKey(), entry.getValue());
			}
			amounts = Map.copyOf(canonical);
		}

		double massResidual() { return mmm.units.Mass.kilograms(massResidualUnits); }
		boolean isEmpty() { return amounts.isEmpty(); }

		PhaseComposition plus(PhaseComposition other) {
			TreeMap<String, Long> combined = new TreeMap<>(amounts);
			for (Map.Entry<String, Long> entry : other.amounts.entrySet()) {
				combined.merge(entry.getKey(), entry.getValue(), Math::addExact);
			}
			long residual = Math.addExact(massResidualUnits, other.massResidualUnits);
			if (!Double.isFinite(residual)) throw new ArithmeticException("Condensed phase residual overflow");
			return combined.isEmpty() && residual == 0.0 ? EMPTY : new PhaseComposition(combined, residual);
		}

		PhaseComposition minus(PhaseComposition other) {
			TreeMap<String, Long> remaining = new TreeMap<>(amounts);
			for (Map.Entry<String, Long> entry : other.amounts.entrySet()) {
				long current = remaining.getOrDefault(entry.getKey(), 0L);
				if (current < entry.getValue()) throw new FluidDomain.ThermalPhaseBlockedException(
						"Condensed phase species subtraction underflow");
				long result = current - entry.getValue();
				if (result == 0L) remaining.remove(entry.getKey());
				else remaining.put(entry.getKey(), result);
			}
			long residual = Math.subtractExact(massResidualUnits, other.massResidualUnits);
			if (!Double.isFinite(residual)) throw new ArithmeticException("Condensed phase residual overflow");
			if (remaining.isEmpty()) {
				if (residual != 0) {
					throw new FluidDomain.ThermalPhaseBlockedException(
							"Empty condensed phase retains a mass residual");
				}
				return EMPTY;
			}
			return new PhaseComposition(remaining, residual);
		}

		CoherentPhaseStore.Composition toCoherent() {
			if (isEmpty()) return null;
			requireParcelCapacity();
			ArrayList<CoherentPhaseStore.Component> components = new ArrayList<>(amounts.size());
			for (Map.Entry<String, Long> entry : new TreeMap<>(amounts).entrySet()) {
				components.add(new CoherentPhaseStore.Component(entry.getKey(), entry.getValue()));
			}
			return new CoherentPhaseStore.Composition(
					components, massResidualUnits);
		}

		ChemicalMixtureRecord toMixture(ChemicalRegistry registry) {
			if (isEmpty()) throw new FluidDomain.ThermalPhaseBlockedException(
					"Chemical melt produced an empty liquid parcel");
			requireParcelCapacity();
			return ChemicalMixtureRecord.fromStableAmounts(registry, amounts, massResidualUnits);
		}

		private void requireParcelCapacity() {
			long total = 0L;
			for (long amount : amounts.values()) total = Math.addExact(total, amount);
			if (amounts.size() > ChemicalLimits.MAX_SPECIES_PER_PARCEL
					|| total > ChemicalLimits.MAX_REGION_TOTAL_QUANTA) {
				throw new PhaseCandidateUnavailableException("Phase transfer awaits carrier composition capacity");
			}
		}

		static PhaseComposition fromCoherent(CoherentPhaseStore.Composition composition) {
			return composition == null ? EMPTY
					: new PhaseComposition(composition.stableAmounts(), composition.massResidualUnits());
		}

		static PhaseComposition fromMixture(ChemicalMixtureRecord mixture, ChemicalRegistry registry) {
			if (mixture == null || registry == null) {
				throw new IllegalArgumentException("Liquid phase composition is missing");
			}
			return new PhaseComposition(mixture.stableAmounts(registry), mixture.massResidualUnits());
		}

		static PhaseComposition fromGas(GasComponentAmount component) {
			if (component == null) throw new IllegalArgumentException("Gas phase composition is missing");
			TreeMap<String, Long> amounts = new TreeMap<>();
			for (GasComponentAmount.SpeciesAmount amount : component.species()) {
				amounts.put(amount.speciesId(), amount.amountQuanta());
			}
			long residual = component.massResidualUnits();
			return amounts.isEmpty() && residual == 0.0 ? EMPTY : new PhaseComposition(amounts, residual);
		}

		ExactRational exactSpeciesMass(ChemicalRegistry registry) {
			ExactRational result = ExactRational.ZERO;
			for (Map.Entry<String, Long> entry : amounts.entrySet()) {
				result = result.add(registry.species(registry.speciesHandle(entry.getKey())).molarMassKgPerMol()
						.multiply(entry.getValue()).divide(ChemicalLimits.QUANTA_PER_MOL));
			}
			return result;
		}
	}

	static final class PhaseCompositionBuilder {
		private final ChemicalParcelStore chemistry;
		private final TreeMap<String, Long> amounts = new TreeMap<>();
		private long massResidualUnits;

		PhaseCompositionBuilder(ChemicalParcelStore chemistry) {
			if (chemistry == null) throw new IllegalArgumentException("Chemical phase state is missing");
			this.chemistry = chemistry;
		}

		void add(ChemicalMixtureRecord record) {
			if (record == null) throw new IllegalArgumentException("Chemical condensed phase record is missing");
			for (Map.Entry<String, Long> entry : record.stableAmounts(chemistry.registry()).entrySet()) {
				amounts.merge(entry.getKey(), entry.getValue(), Math::addExact);
			}
			massResidualUnits = Math.addExact(massResidualUnits, record.massResidualUnits());

		}

		PhaseComposition build() {
			return amounts.isEmpty() && massResidualUnits == 0
					? PhaseComposition.EMPTY : new PhaseComposition(amounts, massResidualUnits);
		}
	}

	static final class PhaseCandidateUnavailableException extends IllegalArgumentException {
		PhaseCandidateUnavailableException(String message) { super(message); }
		PhaseCandidateUnavailableException(String message, Throwable cause) { super(message, cause); }
	}
}
