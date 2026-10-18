package mmm.fluid;

import mmm.inventory.LiquidCellInventory;

import mmm.chemistry.*;
import mmm.fluid.air.GasComponentAmount;
import mmm.granular.GranularCouplingFields;
import mmm.thermal.*;
import mmm.units.SolverUnitScale;
import java.util.*;

/** Shared cell inventory and single-publication operations for condensed and gas phase changes. */
final class PhaseChangeStageSupport {
	record Workspace(int sizeX,int sizeY,int sizeZ,int cellCount,float cellSize,SolverUnitScale unitScale,
	                 DomainGeometry geometry,PressureProjectionWorkspace projection,GasDomainState gasState,
	                 PendingPhaseVolumeWork pendingPhaseVolume,ThermalCarrierContext thermalMarkers) { }
	interface Access {
		ThermalDomainState thermalState();
		ChemicalParcelStore chemistry();
		FluidMaterialRegistry materialRegistry();
		GranularDomainState granularState();
		float pressureReference();
		double liquidPressureAt(int cell,int support);
		LiquidCellInventory liquidCells();
		CellMacStage.ExternalMomentum preparePhaseMomentum(LiquidCellInventory.Plan plan);
		void commitPhaseMomentum(MacMomentumState momentum);
		void refreshGasTopology();
		GasTopologyCoordinator.GasTopologyPlan planGasBatchGeometry(LiquidCellInventory future,GasTopologyCoordinator.PhaseBatch phase);
		void requireGasBatchGeometryCurrent(GasTopologyCoordinator.GasTopologyPlan topology,GasTopologyCoordinator.PhaseBatch phase);
		void commitGasGeometry(GasTopologyCoordinator.GasTopologyPlan topology);
		GasTopologyCoordinator.GasTopologyPlan planCondensedGeometry(LiquidCellInventory future,List<CoherentPhaseStore.Group> coherent);
		void requireGeometryCurrent(GasTopologyCoordinator.GasTopologyPlan plan);
		void refreshCoherentOccupancy();
		void markGridDirty();
	}
	private final Workspace state;
	private final Access access;
	private final GranularCouplingFields phaseGranularOccupancy;
	PhaseChangeStageSupport(Workspace state,Access access) {
		this.state=state;this.access=access;
		phaseGranularOccupancy=new GranularCouplingFields(state.sizeX(),state.sizeY(),state.sizeZ(),state.cellSize());
	}
	Workspace state(){return state;}
	ThermalDomainState thermal(){return access.thermalState();}
	ChemicalParcelStore chemistry(){return access.chemistry();}
	FluidMaterialRegistry materialRegistry(){return access.materialRegistry();}
	GasDomainState gasState(){return state.gasState();}
	float pressureReference(){return access.pressureReference();}
	double liquidPressureAt(int cell,int support){return access.liquidPressureAt(cell,support);}
	LiquidCellInventory liquidCells(){return access.liquidCells();}
	CellMacStage.ExternalMomentum preparePhaseMomentum(LiquidCellInventory.Plan plan){return access.preparePhaseMomentum(plan);}
	void commitPhaseMomentum(MacMomentumState momentum){access.commitPhaseMomentum(momentum);}
	void refreshGasTopology(){access.refreshGasTopology();}
	GasTopologyCoordinator.GasTopologyPlan planGasBatchGeometry(LiquidCellInventory future,GasTopologyCoordinator.PhaseBatch phase){return access.planGasBatchGeometry(future,phase);}
	void requireGasBatchGeometryCurrent(GasTopologyCoordinator.GasTopologyPlan plan,GasTopologyCoordinator.PhaseBatch phase){access.requireGasBatchGeometryCurrent(plan,phase);}
	void commitGasGeometry(GasTopologyCoordinator.GasTopologyPlan plan){access.commitGasGeometry(plan);}
	GasTopologyCoordinator.GasTopologyPlan planCondensedGeometry(LiquidCellInventory future,List<CoherentPhaseStore.Group> coherent){return access.planCondensedGeometry(future,coherent);}
	void requireGeometryCurrent(GasTopologyCoordinator.GasTopologyPlan plan){access.requireGeometryCurrent(plan);}
	void refreshCoherentOccupancy(){access.refreshCoherentOccupancy();}
	void markGridDirty(){access.markGridDirty();}
	int fluidMaterialHandle(String id){
		for(int handle=0;handle<materialRegistry().size();handle++)if(materialRegistry().material(handle).id().equals(id))return handle;
		throw new FluidDomain.ThermalPhaseBlockedException("Thermal transition liquid is not bound");
	}
	long granularSvq(int cell){return phaseGranularOccupancy.solidSvq(cell);}
	void prepareGranularOccupancy(){
		var granular=access.granularState();
		if(granular==null)phaseGranularOccupancy.clear();else phaseGranularOccupancy.rebuild(granular.parcels,granular.deposits);
	}
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
			return combined.isEmpty() && residual == 0L ? EMPTY : new PhaseComposition(combined, residual);
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
			return amounts.isEmpty() && residual == 0L ? EMPTY : new PhaseComposition(amounts, residual);
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

	static final class PhaseCandidateUnavailableException extends IllegalArgumentException {
		PhaseCandidateUnavailableException(String message) { super(message); }
		PhaseCandidateUnavailableException(String message, Throwable cause) { super(message, cause); }
	}
}
