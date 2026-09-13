package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.thermal.ThermalDomainState;

import cc.ecisr.mmm.chemistry.ChemicalLimits;
import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.chemistry.ChemicalParcelStore;
import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.chemistry.ExactRational;
import cc.ecisr.mmm.fluid.air.GasComponentAmount;
import cc.ecisr.mmm.granular.GranularCouplingFields;
import cc.ecisr.mmm.granular.SolidVolume;
import cc.ecisr.mmm.thermal.CoherentPhaseStore;
import cc.ecisr.mmm.thermal.LiquidEnergyColumn;
import cc.ecisr.mmm.thermal.ThermalCarrierContext;
import cc.ecisr.mmm.units.SignedDyadic;
import cc.ecisr.mmm.units.SolverUnitScale;

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
			GasDomainState gasState, SignedDyadic[] pendingPhaseCellVolume,
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
		void removeParticle(int marker);
		void spawnParticle(
				int cellX, int cellY, int cellZ, int slot, float volume, int materialHandle,
				double mass, ChemicalMixtureRecord mixture
		);
		SignedDyadic exactInternalEnergy();
		void ensureGrid();
		void refreshGasTopology();
		void refreshGasGeometry();
		void refreshCoherentOccupancy();
		void markGridDirty();
		boolean canAddCarrierVolume(double volume);
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
	void ensureGrid() { access.ensureGrid(); }
	void refreshGasTopology() { access.refreshGasTopology(); }
	void refreshGasGeometry() { access.refreshGasGeometry(); }
	void refreshCoherentOccupancy() { access.refreshCoherentOccupancy(); }
	void markGridDirty() { access.markGridDirty(); }
	boolean canExpandCondensedVolume(long svq) {
		return svq <= 0 || access.canAddCarrierVolume(svq / (double) SolidVolume.SVQ_PER_CELL);
	}
	SignedDyadic exactInternalEnergy() { return access.exactInternalEnergy(); }

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
				state.particles().setMass(allocation.marker(), allocation.newMass());
				access.thermalState().commitInternalTruncate(allocation.marker(), allocation.thermalPlan());
			}
		}
	}

	void spawnParticle(
			int cell, int slot, float volume, int materialHandle, double mass,
			ChemicalMixtureRecord mixture
	) {
		int x = cell % state.sizeX();
		int yz = cell / state.sizeX();
		int y = yz % state.sizeY();
		int z = yz / state.sizeY();
		access.spawnParticle(x, y, z, slot, volume, materialHandle, mass, mixture);
	}

	void commitPendingVolume(int cell, long signedSvq) {
		if (access.thermalState() == null || signedSvq == 0L) return;
		SignedDyadic delta = new SignedDyadic(java.math.BigInteger.valueOf(signedSvq), -24);
		state.pendingPhaseCellVolume()[cell] = state.pendingPhaseCellVolume()[cell].add(delta);
	}

	LiquidEnergyColumn.Truncation planThermalTruncate(
			int marker, double newMass, ChemicalMixtureRecord keptMixture, double previousTemperatureK
	) {
		return access.thermalState().planTruncate(
				marker, newMass, keptMixture, previousTemperatureK, state.thermalMarkers());
	}

	boolean inside(int x, int y, int z) {
		return x >= 0 && y >= 0 && z >= 0
				&& x < state.sizeX() && y < state.sizeY() && z < state.sizeZ();
	}

	int cellIndex(int x, int y, int z) {
		return x + state.sizeX() * (y + state.sizeY() * z);
	}

	static double massTolerance(double mass) {
		return Math.max(1.0E-12, Math.ulp(mass) * 32.0);
	}

	static float adjustedMarkerVolume(float volume, long signedSvq, String operation) {
		try {
			return SolidVolume.addCellVolumeSvqExact(volume, signedSvq);
		} catch (IllegalArgumentException unrepresentableTail) {
			throw new PhaseCandidateUnavailableException(
					operation + " SVQ is not exactly representable in the marker volume", unrepresentableTail);
		}
	}

	record PhaseCellInventory(
			long lowerVolumeSvq, long upperVolumeSvq, double lowerMass, double upperMass,
			double internalEnergy, PhaseComposition lowerComposition, PhaseComposition upperComposition,
			long freezableUpperSvq, double freezableUpperMass
	) { }

	record PhaseMarkerAllocation(
			int marker, float newVolume, double newMass, boolean fullRemoval,
			ChemicalParcelStore.SplitPlan chemicalPlan, LiquidEnergyColumn.Truncation thermalPlan
	) { }

	record PhaseRemovalPlan(
			List<PhaseMarkerAllocation> allocations, PhaseComposition movedComposition, double movedEnergy
	) {
		boolean carrierTopologyChanged() {
			for (PhaseMarkerAllocation allocation : allocations) {
				if (allocation.fullRemoval()) return true;
			}
			return false;
		}
	}

	/** Exact species quanta plus the independently carried mass residual across a phase boundary. */
	record PhaseComposition(Map<String, Long> amounts, double massResidual) {
		static final PhaseComposition EMPTY = new PhaseComposition(Map.of(), 0.0);

		PhaseComposition {
			if (amounts == null || amounts.size() > ChemicalLimits.MAX_SPECIES
					|| !Double.isFinite(massResidual)) {
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

		boolean isEmpty() { return amounts.isEmpty(); }

		PhaseComposition plus(PhaseComposition other) {
			TreeMap<String, Long> combined = new TreeMap<>(amounts);
			for (Map.Entry<String, Long> entry : other.amounts.entrySet()) {
				combined.merge(entry.getKey(), entry.getValue(), Math::addExact);
			}
			double residual = massResidual + other.massResidual;
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
			double residual = massResidual - other.massResidual;
			if (!Double.isFinite(residual)) throw new ArithmeticException("Condensed phase residual overflow");
			if (remaining.isEmpty()) {
				if (Math.abs(residual) > massTolerance(
						Math.max(Math.abs(massResidual), Math.abs(other.massResidual)))) {
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
					components, Double.doubleToRawLongBits(massResidual));
		}

		ChemicalMixtureRecord toMixture(ChemicalRegistry registry) {
			if (isEmpty()) throw new FluidDomain.ThermalPhaseBlockedException(
					"Chemical melt produced an empty liquid parcel");
			requireParcelCapacity();
			return ChemicalMixtureRecord.fromStableAmounts(registry, amounts, massResidual);
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
					: new PhaseComposition(composition.stableAmounts(), composition.massResidual());
		}

		static PhaseComposition fromMixture(ChemicalMixtureRecord mixture, ChemicalRegistry registry) {
			if (mixture == null || registry == null) {
				throw new IllegalArgumentException("Liquid phase composition is missing");
			}
			return new PhaseComposition(mixture.stableAmounts(registry), mixture.massResidualKilograms());
		}

		static PhaseComposition fromGas(GasComponentAmount component) {
			if (component == null) throw new IllegalArgumentException("Gas phase composition is missing");
			TreeMap<String, Long> amounts = new TreeMap<>();
			for (GasComponentAmount.SpeciesAmount amount : component.species()) {
				amounts.put(amount.speciesId(), amount.amountQuanta());
			}
			double residual = component.massResidual();
			return amounts.isEmpty() && residual == 0.0 ? EMPTY : new PhaseComposition(amounts, residual);
		}

		ExactRational exactSpeciesMass(ChemicalRegistry registry) {
			ExactRational result = ExactRational.ZERO;
			for (Map.Entry<String, Long> entry : amounts.entrySet()) {
				result = result.add(registry.species(registry.speciesHandle(entry.getKey())).massKilogramsPerAu()
						.multiply(entry.getValue()).divide(ChemicalLimits.QUANTA_PER_AU));
			}
			return result;
		}
	}

	static final class PhaseCompositionBuilder {
		private final ChemicalParcelStore chemistry;
		private final TreeMap<String, Long> amounts = new TreeMap<>();
		private double massResidual;

		PhaseCompositionBuilder(ChemicalParcelStore chemistry) {
			if (chemistry == null) throw new IllegalArgumentException("Chemical phase state is missing");
			this.chemistry = chemistry;
		}

		void add(ChemicalMixtureRecord record) {
			if (record == null) throw new IllegalArgumentException("Chemical condensed phase record is missing");
			for (Map.Entry<String, Long> entry : record.stableAmounts(chemistry.registry()).entrySet()) {
				amounts.merge(entry.getKey(), entry.getValue(), Math::addExact);
			}
			massResidual += record.massResidualKilograms();
			if (!Double.isFinite(massResidual)) throw new ArithmeticException("Condensed phase residual overflow");
		}

		PhaseComposition build() {
			return amounts.isEmpty() && massResidual == 0.0
					? PhaseComposition.EMPTY : new PhaseComposition(amounts, massResidual);
		}
	}

	static final class PhaseCandidateUnavailableException extends IllegalArgumentException {
		PhaseCandidateUnavailableException(String message) { super(message); }
		PhaseCandidateUnavailableException(String message, Throwable cause) { super(message, cause); }
	}
}
