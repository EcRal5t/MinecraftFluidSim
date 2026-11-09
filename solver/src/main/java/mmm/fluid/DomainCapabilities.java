package mmm.fluid;

import mmm.fluid.air.GasRegistry;

import mmm.inventory.LiquidCellInventory;
import mmm.thermal.ThermalDomainState;
import mmm.thermal.ThermalCarrierContext;
import mmm.chemistry.RegionChemistry;
import mmm.granular.GranularCouplingFields;
import mmm.granular.GranularPhaseTransferEngine;
import java.util.List;
import mmm.units.SignedDyadic;
import mmm.chemistry.ChemicalMixtureRecord;
import mmm.chemistry.ChemicalTransferLedger;
import java.util.ArrayList;
import java.util.Comparator;
import mmm.granular.GranularInventory;
import mmm.granular.GranularParcelStore;
import mmm.granular.SolidVolume;
import mmm.thermal.GranularEnergyColumn;
import mmm.thermal.ThermalLimits;
import mmm.thermal.ThermalTransport;
import mmm.granular.GranularDepositStore;
import mmm.thermal.CoherentPhaseStore;
import mmm.chemistry.ChemicalRegistry;
import java.util.Map;
import mmm.thermal.FiniteDeviceExchangeCoordinator;
import mmm.thermal.ThermalOwnerSnapshot;
import mmm.chemistry.ChemicalDisplaySnapshot;
import mmm.granular.GranularLimits;
import mmm.granular.SolidCompositionPool;
import mmm.thermal.FiniteThermalExchange;
import mmm.thermal.ThermalRegistry;
import mmm.thermal.ThermalDisplaySnapshot;
import static mmm.fluid.HostFluidState.*;
import static mmm.fluid.FluidDomainContracts.*;

/** Binds chemistry and thermal capabilities and exposes their actual inventory owners. */
final class DomainCapabilities {
	private final HostFluidState domain;
	private GasRegistry cachedGasRegistry;
	private ThermalRegistry cachedThermalRegistry;
	private mmm.thermal.GasThermalModel cachedGasModel;
	DomainCapabilities(HostFluidState domain) { this.domain = domain; }
	public FluidMaterialRegistry materialRegistry() { return domain.materialRegistry; }
	public RegionChemistry chemistry() {
		if (domain.chemistry == null) throw new IllegalStateException("Chemistry is not bound");
		return domain.chemistry;
	}
	public ChemicalDisplaySnapshot copyChemicalDisplaySnapshot() { return domain.carriers.copyChemicalDisplaySnapshot(); }
	public ChemicalTransferLedger chemicalLedgerForMarkerRange(int fromInclusive, int toExclusive) {
		if (domain.chemistry == null) throw new IllegalStateException("Chemistry is not bound");
		if (fromInclusive < 0 || toExclusive < fromInclusive || toExclusive > domain.liquidCells.physicalSize()) {
			throw new IndexOutOfBoundsException("Invalid chemical marker range");
		}
		ChemicalTransferLedger.Builder result = ChemicalTransferLedger.builder(domain.chemistry.registry());
		for (int marker = fromInclusive; marker < toExclusive; marker++) {
			result.add(domain.materialRegistry.material(domain.liquidCells.physicalStockAt(marker).key().material()).id(),
					(float) domain.liquidCells.physicalStockAt(marker).volumeFraction(), domain.liquidCells.physicalStockAt(marker).massUnits(), domain.liquidCells.physicalStockAt(marker).mixture());
		}
		return result.build();
	}
	public void bindChemistry(ChemicalRegistry registry) {
		if (domain.thermalState != null) {
			throw new IllegalStateException("Chemistry must be bound before transient thermal initialization");
		}
		if (domain.materialRegistry == null) throw new IllegalStateException("Chemistry requires a material registry");
		if (domain.chemistry != null) {
			if (!domain.chemistry.registry().fingerprint().equals(registry.fingerprint())) {
				throw new IllegalArgumentException("A domain cannot change its chemical registry");
			}
			return;
		}
		if (domain.granularState != null && domain.granularState.registry.hasReactiveRules()
				&& !domain.granularState.registry.chemicalRegistryFingerprint().equals(registry.fingerprint())) {
			throw new IllegalArgumentException("Granular registry chemical dependency mismatch");
		}
		var localGas=domain.gasState.spatial;
		if(localGas!=null)for(var gas:localGas.cells())for(var component:gas.components())for(var species:component.species())
			if(!registry.hasSpecies(species.speciesId()))throw new IllegalArgumentException("Gas species is absent from chemical registry: "+species.speciesId());
		RegionChemistry candidate = new RegionChemistry(registry,domain.cellCount);
		var additions = new ArrayList<LiquidCellInventory.Stock>();
		for (var stock : domain.liquidCells.stocks()) {
			var mixture = candidate.defaultRecord(domain.materialRegistry.material(stock.key().material()).id(),
					stock.volumeFraction(), domain.config.cellSize(), stock.massUnits());
			additions.add(new LiquidCellInventory.Stock(stock.key(), stock.volume(), stock.massUnits(), mixture, stock.internalEnergy()));
		}
		var inventory = new LiquidCellInventory(domain.cellCount, registry);
		inventory.planAdditions(additions).commit();
		domain.liquidCells = inventory;
		domain.chemistry = candidate;
		if(localGas!=null)domain.gasState.spatial=new mmm.fluid.air.SpatialGasState(localGas.grid(),localGas.registryFingerprint(),registry.fingerprint(),
				localGas.cells(),localGas.internalEnergy(),localGas.externalSource(),localGas.escapedSink(),localGas.phaseSource(),localGas.phaseSink());
		for (var stock : domain.liquidCells.stocks()) domain.chemistry.markDirtyCell(stock.key().cell());
		domain.markCellInventoryChanged();
		bindSolidCompositionPoolIfReady();
	}
	public void bindThermal(
			ThermalRegistry registry, FluidMaterialRegistry liquidRegistry, double initialTemperatureK
	) {
		bindThermal(registry, liquidRegistry, initialTemperatureK, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
	}
	public void bindThermal(
			ThermalRegistry registry, FluidMaterialRegistry liquidRegistry, double initialTemperatureK,
			int maximumTransportSubsteps
	) {
		if (!prepareThermalBinding(registry, liquidRegistry, initialTemperatureK)) return;
		if (domain.gasState.frame != null) domain.gasInitialization.refresh();
		domain.thermalState = FluidThermalBinding.create(registry, domain.materialRegistry, initialTemperatureK,
				domain.liquidCells.physicalSize(), domain.grid, domain.config.cellSize(), thermalMarkerContext(), domain.gasState.network,
				maximumTransportSubsteps);
		finishThermalBinding();
		if(domain.gasState.spatial==null) initializeSpatialGas();
		else bindSpatialGasEnergy(initialTemperatureK);
	}
	void initializeSpatialGas() {
		if (domain.gasState.spatial != null) return;
		var network = domain.gasState.network;
		domain.ensurePhaseOccupancy();
		long[] occupied = domain.occupiedSolidSvq();
		double[] dry = new double[domain.cellCount];
		double h = domain.config.cellSize(), h3 = h * h * h;
		for (int cell = 0; cell < dry.length; cell++) {
			double liquid = 0;
			for (int micro = 0; micro < 8; micro++) liquid += domain.phaseOccupancy.liquidVolume(8 * cell + micro);
			dry[cell] = Math.max(0, (Integer.bitCount(~domain.geometry.cutCells.mask(cell) & 255) / 8.0
					- Math.scalb((double) occupied[cell], -24) - liquid) * h3);
		}
		var model = spatialGasModel();
		double temperature = domain.thermalState==null?GasRegistry.STANDARD_TEMPERATURE_K:domain.thermalState.insertionTemperatureK();
		double[] oldEnergy = domain.thermalState==null?new double[network.nodeCount()]:domain.thermalState.gas().nodeEnergies();
		if(domain.thermalState==null)for(int node=0;node<oldEnergy.length;node++)oldEnergy[node]=model.internalEnergyAtTemperature(network.inventory(node+1),temperature);
		mmm.thermal.SpatialGasInitialization.Result initialized;
		if (domain.gasState.frame != null) {
			var snapshot = domain.gasInitialization.export(domain.chemistry == null ? "" : domain.chemistry.registry().fingerprint());
			initialized = mmm.thermal.SpatialGasInitialization.fromChambers(snapshot, oldEnergy, dry, network.registry(),domain.chemistry==null?null:domain.chemistry.registry(),
					model, GasRegistry.STANDARD_PRESSURE_PA, temperature);
		} else {
			var inventories = new java.util.ArrayList<mmm.fluid.air.GasInventory>(dry.length);
			var cached = new java.util.HashMap<Double, mmm.fluid.air.GasInventory>();
			double[] energies = new double[dry.length];
			for (int cell = 0; cell < dry.length; cell++) {
				var stock = dry[cell] == 0 ? mmm.fluid.air.GasInventory.EMPTY : cached.computeIfAbsent(dry[cell],
						volume -> model.inventoryAtPressure(volume, GasRegistry.STANDARD_PRESSURE_PA, temperature, network.registry().ambient()));
				inventories.add(stock);
				energies[cell] = stock.isEmpty() ? 0 : model.internalEnergyAtTemperature(stock, temperature);
			}
			var state = new mmm.fluid.air.SpatialGasState(domain.grid,
					network.registry().fingerprint(), domain.chemistry == null ? "" : domain.chemistry.registry().fingerprint(),
					inventories, energies, mmm.fluid.air.GasRegionAudit.aggregate(inventories), mmm.fluid.air.GasAuditLedger.EMPTY,
					mmm.fluid.air.GasAuditLedger.EMPTY, mmm.fluid.air.GasAuditLedger.EMPTY);
			initialized = new mmm.thermal.SpatialGasInitialization.Result(state, SignedDyadic.sum(energies).toDouble());
		}
		if(domain.thermalState!=null){
			var source = SignedDyadic.of(initialized.capturedAmbientEnergy());
			var roundoff = SignedDyadic.sum(initialized.state().internalEnergy()).subtract(SignedDyadic.sum(oldEnergy)).subtract(source);
			var audit = domain.thermalState.auditLedger().withExternalSourceEnergy(source).withRoundoff(roundoff);
			domain.thermalState.gas().clearNodeEnergies();
			domain.thermalState.restoreAuditLedger(audit);
		}
		domain.gasState.spatial = initialized.state();
		domain.gasState.network = new mmm.fluid.air.AirNetwork(network.registry());
		domain.gasState.frame = null;
		domain.gasState.pressureActive = true;
		domain.phaseOccupancyDirty=true;domain.velocityGridDirty=true;domain.pressureSystem.invalidateGeometry();
	}
	mmm.thermal.GasThermalModel spatialGasModel(){
		var registry=domain.gasState.network.registry();
		var thermal=domain.definitions.thermal();
		if(cachedGasModel!=null&&registry==cachedGasRegistry&&thermal==cachedThermalRegistry)return cachedGasModel;
		cachedGasRegistry=registry;cachedThermalRegistry=thermal;
		return cachedGasModel=new mmm.thermal.GasThermalModel(thermal,registry);
	}
	private void bindSpatialGasEnergy(double temperature){
		var old=domain.gasState.spatial;var model=spatialGasModel();double[] energies=new double[old.cellCount()];
		for(int cell=0;cell<energies.length;cell++)if(!old.cells().get(cell).isEmpty())energies[cell]=model.internalEnergyAtTemperature(old.cells().get(cell),temperature);
		domain.gasState.spatial=new mmm.fluid.air.SpatialGasState(old.grid(),old.registryFingerprint(),old.chemicalRegistryFingerprint(),
				old.cells(),energies,old.externalSource(),old.escapedSink(),old.phaseSource(),old.phaseSink());
		domain.thermalState.restoreAuditLedger(domain.thermalState.auditLedger().withExternalSourceEnergy(SignedDyadic.sum(energies)));
		domain.phaseOccupancyDirty=true;domain.pressureSystem.invalidateGeometry();
	}
	private boolean prepareThermalBinding(
			ThermalRegistry registry, FluidMaterialRegistry liquidRegistry, double initialTemperatureK
	) {
		if (registry == null || liquidRegistry == null) throw new IllegalArgumentException("Thermal/liquid registry is missing");
		if (!Double.isFinite(initialTemperatureK)) throw new IllegalArgumentException("Initial thermal temperature must be finite");
		if (Float.floatToRawIntBits(domain.config.cellSize()) != Float.floatToRawIntBits(0.5F)) {
			throw new IllegalArgumentException("Thermal state requires a 0.5 m solver cell");
		}
		domain.bindMaterialRegistry(liquidRegistry);
		if (domain.thermalState != null) {
			if (!domain.thermalState.registry().fingerprint().equals(registry.fingerprint())) {
				throw new IllegalArgumentException("A domain cannot change its thermal registry");
			}
			return false;
		}
		if (domain.chemistry != null && !registry.chemicalRegistryFingerprint()
				.equals(domain.chemistry.registry().fingerprint())) {
			throw new IllegalArgumentException("Thermal registry chemical dependency mismatch");
		}
		return true;
	}
	private void finishThermalBinding() {
		if (domain.granularCoupling == null) {
			domain.granularCoupling = new GranularCouplingFields(domain.grid,domain.config.cellSize(),domain.geometry.cutCells::mask);
			domain.granularNextCoupling = new GranularCouplingFields(domain.grid,domain.config.cellSize(),domain.geometry.cutCells::mask);
		}
		refreshCoherentOccupancy();
		domain.gridDirty = domain.velocityGridDirty = true;domain.pressureSystem.invalidateGeometry();
		if (domain.granularState != null) domain.granularState.inventory.bindThermalEnergy(thermalGranularAdapter());
	}
	GranularInventory.EnergyAdapter thermalGranularAdapter() {
		return new GranularInventory.EnergyAdapter() {
			@Override public GranularInventory.EnergyPlan planActiveToDeposits(
					java.util.List<GranularInventory.DepositTransfer> transfers) {
				return domain.thermalState.planActiveToDeposits(transfers);
			}
			GranularEnergyColumn.DepositKey key(int cell, int material, int composition) {
				return new GranularEnergyColumn.DepositKey(cell, material, composition);
			}
			@Override public boolean canActiveMerge(int first, int second, GranularParcelStore.Parcel merged,
					mmm.granular.SolidCompositionRecord composition, SignedDyadic heat) {
				return domain.thermalState.canActiveMerge(first, second, merged.materialHandle(), merged.mass(),
						composition == null ? Map.of() : composition.stableAmounts(domain.granularState.compositions.registry()), heat);
			}
			@Override public void commitActiveMerge(int first, int second, SignedDyadic heat) {
				domain.thermalState.commitActiveMerge(first, second, heat);
			}
			@Override public GranularInventory.EnergyPlan planDepositWake(
					int cell, int material, int composition, long takenMassUnits, long totalMassUnits
			) {
				return domain.thermalState.planDepositWake(key(cell, material, composition), takenMassUnits, totalMassUnits);
			}
			@Override public void clearExternal() { domain.thermalState.clearGranularExternal(); }
		};
	}
	public void restoreThermalParticleState(
			FlipThermalParticleState state, ThermalRegistry registry
	) {
		restoreThermalParticleState(state, registry, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
	}
	public void restoreThermalParticleState(
			FlipThermalParticleState state, ThermalRegistry registry, int maximumTransportSubsteps
	) {
		if (state == null || registry == null) throw new IllegalArgumentException("Thermal restore state/registry is missing");
		if (domain.materialRegistry == null) throw new IllegalStateException("Thermal restore requires restored material state");
		state.requireCompatible(domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize(), domain.liquidCells.physicalSize());
		state=domain.gasInitialization.remapRestoredThermal(state);
		if (!state.thermalRegistryFingerprint().equals(registry.fingerprint())) {
			throw new IllegalArgumentException("Thermal handoff registry identity mismatch");
		}
		validateCoherentRestoreCapacity(state.coherentGroups());
		double initialTemperature = state.insertionTemperatureK();
		// Restore must retain the gas ordinals already derived from the complete checkpoint.
		if (!prepareThermalBinding(registry, domain.materialRegistry, initialTemperature)) {
			throw new IllegalStateException("Thermal restore target is already bound");
		}
		var liquidEnergy = domain.liquidCells.planPhysicalEnergyUpdate(state.copyInternalEnergy());
		ThermalDomainState candidate = FluidThermalBinding.createOwnerRestoreTarget(registry, domain.materialRegistry, initialTemperature,
				domain.liquidCells.physicalSize(), domain.grid, domain.config.cellSize(), thermalMarkerContext(), domain.gasState.network,
				maximumTransportSubsteps);
		FluidThermalBinding.restore(candidate, state, thermalMarkerContext(), domain.gasState.network);
		liquidEnergy.commit();
		domain.thermalState = candidate;
		try { finishThermalBinding(); domain.gasInitialization.completeThermalRestore(); }
		catch (RuntimeException failure) {
			domain.thermalState = null;
			try { refreshCoherentOccupancy(); }
			catch (RuntimeException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
			domain.gridDirty = true;
			throw failure;
		}
	}
	public void restoreThermalOwnerState(ThermalOwnerSnapshot state, ThermalRegistry registry, int maximumTransportSubsteps) {
		if (state == null || registry == null || domain.materialRegistry == null)
			throw new IllegalArgumentException("Thermal owner restore requires liquid cells and registries");
		state=domain.gasInitialization.remapRestoredThermal(state);
		if (!state.registryFingerprint().equals(registry.fingerprint()))
			throw new IllegalArgumentException("Thermal owner registry identity differs");
		validateCoherentRestoreCapacity(state.coherentGroups());
		if (!prepareThermalBinding(registry, domain.materialRegistry, state.insertionTemperatureK()))
			throw new IllegalStateException("Thermal restore target is already bound");
		var candidate = FluidThermalBinding.createOwnerRestoreTarget(registry, domain.materialRegistry, state.insertionTemperatureK(),
				domain.liquidCells.physicalSize(), domain.grid, domain.config.cellSize(), thermalMarkerContext(), domain.gasState.network,
				maximumTransportSubsteps);
		candidate.restoreOwnerState(state, thermalMarkerContext(), domain.gasState.network,
				domain.gasState.spatial==null?SignedDyadic.ZERO:SignedDyadic.sum(domain.gasState.spatial.internalEnergy()));
		domain.thermalState = candidate;
		finishThermalBinding();
		domain.gasInitialization.completeThermalRestore();
	}
	public void restoreThermalOwnerState(ThermalOwnerSnapshot state, ThermalRegistry registry) {
		restoreThermalOwnerState(state, registry, ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS);
	}
	public ThermalOwnerSnapshot exportThermalOwnerState() {
		if (domain.thermalState == null) throw new IllegalStateException("Thermal owners are not bound");
		return domain.thermalState.exportOwnerState();
	}
	void validateCoherentRestoreCapacity(List<CoherentPhaseStore.Group> groups) {
		if(groups.isEmpty()&&(domain.granularState==null
				||domain.granularState.parcels.count()==0&&domain.granularState.deposits.groupCount()==0))return;
		long[] fixed = new long[domain.cellCount];
		for (CoherentPhaseStore.Group group : groups) {
			if (group.cell() < 0 || group.cell() >= domain.cellCount) {
				throw new IllegalArgumentException("Restored coherent phase cell is outside the domain");
			}
			fixed[group.cell()] = Math.addExact(fixed[group.cell()], group.occupiedVolumeSvq());
		}
		GranularCouplingFields restoredOccupancy = new GranularCouplingFields(domain.grid, domain.config.cellSize(),domain.geometry.cutCells::mask);
		restoredOccupancy.setFixedCellSvq(fixed);
		if (domain.granularState == null) restoredOccupancy.rebuildFixed();
		else restoredOccupancy.rebuild(domain.granularState.parcels, domain.granularState.deposits);
		for (int cell = 0; cell < domain.cellCount; cell++) {
			long geometric = SolidVolume.fromCellVolumeExact(domain.geometry.cutCells.openCellVolume(cell));
			// FLIP marker clustering may transiently overfill a cell and is resolved by pressure after restore.
			// Solid occupancy must use the same exact positioned-parcel projection as runtime pressure coupling.
			if (restoredOccupancy.solidSvq(cell) > geometric) {
				throw new IllegalArgumentException("Restored coherent/granular inventory exceeds exact cell capacity");
			}
		}
	}
	public FlipThermalParticleState exportThermalParticleState() { return domain.carriers.exportThermalParticleState(); }
	public void setThermalTemperatureInCell(int x, int y, int z, double temperatureK) {
		if (domain.thermalState == null) throw new IllegalStateException("Thermal state is not bound");
		domain.thermalState.setTemperatureInCell(domain.domainFlowFields.cellIndexChecked(x, y, z), temperatureK, thermalMarkerContext());
	}
	public double thermalTemperatureInCell(int x, int y, int z) { return domain.cellInventory.thermalTemperatureInCell(x, y, z); }
	public List<FiniteThermalExchange.Contact> finiteThermalContacts(List<ThermalContactRequest> requests) {
		if (domain.thermalState == null) throw new IllegalStateException("Thermal state is not bound");
		if (requests == null) throw new IllegalArgumentException("Finite thermal contact requests are missing");
		ArrayList<FiniteDeviceExchangeCoordinator.CellContactRequest> mapped = new ArrayList<>(requests.size());
		for (ThermalContactRequest request : requests) {
			if (request == null) throw new IllegalArgumentException("Finite thermal contact requests contain null");
			mapped.add(new FiniteDeviceExchangeCoordinator.CellContactRequest(
					domain.domainFlowFields.cellIndexChecked(request.cellX(), request.cellY(), request.cellZ()), request.ordinal(),
					request.conductanceWPerK()));
		}
		return domain.thermalState.finiteDeviceContacts(mapped, thermalMarkerContext());
	}
	public FiniteThermalCellPlan planFiniteThermalCellExchange(List<ThermalCellEnergyDelta> deltas) {
		if (domain.thermalState == null) throw new IllegalStateException("Thermal state is not bound");
		if (deltas == null) throw new IllegalArgumentException("Finite thermal cell deltas are missing");
		ArrayList<FiniteDeviceExchangeCoordinator.CellEnergyDelta> mapped = new ArrayList<>(deltas.size());
		for (ThermalCellEnergyDelta delta : deltas) {
			if (delta == null) throw new IllegalArgumentException("Finite thermal cell deltas contain null");
			mapped.add(new FiniteDeviceExchangeCoordinator.CellEnergyDelta(
					domain.domainFlowFields.cellIndexChecked(delta.cellX(), delta.cellY(), delta.cellZ()), delta.internalEnergyDeltaJ(),delta.carriers()));
		}
		return new FiniteThermalCellPlan(domain.thermalState.planFiniteDeviceCellExchange(mapped, thermalMarkerContext()));
	}
	public void commitFiniteThermalCellExchange(FiniteThermalCellPlan plan) {
		if (domain.thermalState == null || plan == null) {
			throw new IllegalArgumentException("Finite thermal cell exchange is unavailable");
		}
		domain.thermalState.commitFiniteDeviceCellExchange(plan.delegate, thermalMarkerContext());
	}
	public ThermalDisplaySnapshot copyThermalDisplaySnapshot() { return domain.carriers.copyThermalDisplaySnapshot(); }
	public void addCoherentPhaseExternal(
			int cellX, int cellY, int cellZ, String phaseId, long occupiedVolumeSvq,
			long massUnits, CoherentPhaseStore.Composition composition, double internalEnergy
	) {
		if (domain.thermalState == null) throw new IllegalStateException("Thermal state is not bound");
		int cell = domain.domainFlowFields.cellIndexChecked(cellX, cellY, cellZ);
		if (occupiedVolumeSvq <= 0L) throw new IllegalArgumentException("Coherent phase volume is invalid");
		domain.domainFlowFields.ensureInventoryGrid();
		long geometric = SolidVolume.fromCellVolumeExact(domain.geometry.cutCells.openCellVolume(cell));
		long previousCoherent = domain.thermalState.coherentPhases().cellOccupiedSvq(cell);
		long occupied = domain.granularCoupling.solidSvq(cell);
		long granular = occupied - previousCoherent;
		if (granular < 0L || Math.addExact(occupied, occupiedVolumeSvq) > geometric) {
			throw new IllegalStateException("Coherent phase exceeds exact joint cell occupancy");
		}
		if (!domain.domainCollision.canAddCarrierVolume(occupiedVolumeSvq / (double) SolidVolume.SVQ_PER_CELL)) {
			throw new IllegalStateException("Sealed region has no combined liquid/solid capacity");
		}
		var addition = new CoherentPhaseStore.Group(cell, phaseId, occupiedVolumeSvq, massUnits, composition, internalEnergy);
		addition.requireCompositionMass(domain.chemistry == null ? null : domain.chemistry.registry());
		var thermal = domain.thermalState.planCoherentExternal(addition);
		long[] before = domain.entityBodyCoupling.addTo(domain.granularCoupling.copySolidSvq()), after = before.clone();
		after[cell] = Math.addExact(after[cell], occupiedVolumeSvq);
		var displacement = GranularLiquidDisplacement.prepareInsertion(domain, before, after);
		var audit = thermal.auditAfter().withMechanicalWork(displacement.inventory.mechanicalHeat())
				.withRoundoff(displacement.energyRoundoff);
		if(displacement.localGas!=null)audit=displacement.localGas.applyAudit(audit);
		audit.expectedInventory();
		thermal.requireCurrent();
		GranularLiquidDisplacement.publish(domain, displacement, () -> {
			thermal.commit();
			refreshCoherentOccupancy();
		});
	}
	public long totalCoherentSolidVolumeSvq() {
		return domain.thermalState == null ? 0L : domain.thermalState.coherentPhases().totalOccupiedSvq();
	}
	public double totalCoherentMass() {
		return domain.thermalState == null ? 0.0 : domain.thermalState.coherentPhases().totalMass();
	}
	public boolean replaceThermalBoundaryTemperatures(double[] temperaturesK) {
		if (domain.thermalState == null) throw new IllegalStateException("Thermal state is not bound");
		if (temperaturesK == null || temperaturesK.length != Math.multiplyExact(domain.cellCount, 6)) {
			throw new IllegalArgumentException("Thermal boundary field dimensions changed");
		}
		for (double temperature : temperaturesK) if (!Double.isNaN(temperature)
				&& (!(Double.isFinite(temperature)) || temperature < ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K)) {
			throw new IllegalArgumentException("Thermal boundary temperature is invalid");
		}
		if (domain.thermalBoundaryTemperatureK != null
				&& java.util.Arrays.equals(domain.thermalBoundaryTemperatureK, temperaturesK)) return false;
		domain.thermalBoundaryTemperatureK = temperaturesK.clone();
		return true;
	}
	ThermalCarrierContext thermalMarkerContext() { return domain.thermalMarkers; }
	ThermalCarrierContext createThermalMarkerContext() {
		return new ThermalCarrierContext() {
			@Override public mmm.fluid.air.AirNetwork gasNetwork() { return domain.gasState.spatial==null?domain.gasState.network:null; }
			@Override public List<ThermalCarrierContext.GasSupport> gasThermalSupports() {
				if(domain.gasState.spatial!=null)return List.of();
				domain.gasInitialization.refresh();
				var frame=domain.gasState.frame;
				var result=new ArrayList<ThermalCarrierContext.GasSupport>();
				for(var component:frame.geometry().components()) {
					int node=frame.topology().nodeOfComponent(component.cell(),component.supportMask());
					if(node>0 && component.normalizedVolume()>0) result.add(new ThermalCarrierContext.GasSupport(
							component.cell(),node,component.supportMask(),component.normalizedVolume()));
				}
				return result;
			}
			private LiquidCellInventory.Stock stock(int ordinal) { return domain.liquidCells.physicalStocks().get(ordinal); }
			@Override public int markerCount() { return domain.liquidCells.physicalStocks().size(); }
			@Override public double liquidInternalEnergy(int ordinal) { return stock(ordinal).internalEnergy(); }
			@Override public long liquidInventoryRevision() { return domain.liquidCells.revision(); }
			@Override public void setLiquidInternalEnergy(int ordinal, double energy) {
				domain.liquidCells.planPhysicalEnergyUpdate(ordinal, energy).commit();
			}
			@Override public void setLiquidInternalEnergies(double[] energies) {
				if (energies == null) throw new IllegalArgumentException("Liquid energy column is missing");
				domain.liquidCells.planPhysicalEnergyUpdate(energies).commit();
			}
			@Override public int cell(int ordinal) { return stock(ordinal).key().cell(); }
			@Override public int nextMarkerInCell(int cell, int after) {
				var stocks = domain.liquidCells.physicalStocks();
				int low = Math.max(0, after + 1), high = stocks.size();
				if (after < 0) while (low < high) {
					int middle = (low + high) >>> 1;
					if (stocks.get(middle).key().cell() < cell) low = middle + 1; else high = middle;
				}
				return low < stocks.size() && stocks.get(low).key().cell() == cell ? low : -1;
			}
			@Override public int fluidMaterialHandle(int ordinal) { return stock(ordinal).key().material(); }
			@Override public double mass(int ordinal) { return domain.unitScale.normalizedMass(stock(ordinal).massUnits()); }
			@Override public double volume(int ordinal) { return stock(ordinal).volumeFraction(); }
			@Override public ChemicalMixtureRecord mixture(int ordinal) { return stock(ordinal).mixture(); }
			@Override public ChemicalRegistry chemicalRegistry() { return domain.liquidCells.chemicalRegistry(); }
			@Override public int granularMaterialCount() {
				return domain.granularState == null ? 0 : domain.granularState.registry.materials().size();
			}
			@Override public String granularRegistryFingerprint() {
				return domain.granularState == null ? "" : domain.granularState.registry.fingerprint();
			}
			@Override public String granularMaterialId(int materialHandle) {
				if (domain.granularState == null) throw new IllegalArgumentException("Granular state is not bound");
				return domain.granularState.registry.material(materialHandle).id();
			}
			@Override public int activeParcelCount() {
				return domain.granularState == null ? 0 : domain.granularState.parcels.count();
			}
			@Override public int activeParcelCell(int ordinal) {
				return domain.domainInventoryGeometry.granularParcelCell(domain.granularState.parcels.parcel(ordinal));
			}
			@Override public int activeParcelMaterial(int ordinal) {
				return domain.granularState.parcels.parcel(ordinal).materialHandle();
			}
			@Override public int activeParcelComposition(int ordinal) {
				return domain.granularState.parcels.parcel(ordinal).solidCompositionHandle();
			}
			@Override public long activeParcelSvq(int ordinal) {
				return domain.granularState.parcels.parcel(ordinal).solidVolumeSvq();
			}
			@Override public double activeParcelMass(int ordinal) {
				return domain.granularState.parcels.parcel(ordinal).mass();
			}
			@Override public List<GranularEnergyColumn.DepositKey> depositKeys() {
				if (domain.granularState == null) return List.of();
				ArrayList<GranularEnergyColumn.DepositKey> keys = new ArrayList<>(domain.granularState.deposits.groupCount());
				domain.granularState.deposits.forEachGroup((cell, porosity, material, composition, volume, mass) ->
						keys.add(new GranularEnergyColumn.DepositKey(cell, material, composition)));
				keys.sort(Comparator.naturalOrder());
				return List.copyOf(keys);
			}
			@Override public List<GranularEnergyColumn.DepositKey> depositKeys(int cell) {
				if (domain.granularState == null) return List.of();
				ArrayList<GranularEnergyColumn.DepositKey> keys = new ArrayList<>();
				domain.granularState.deposits.forEachGroupInCell(cell,
						(groupCell, porosity, material, composition, volume, mass) ->
								keys.add(new GranularEnergyColumn.DepositKey(groupCell, material, composition)));
				keys.sort(Comparator.naturalOrder());
				return List.copyOf(keys);
			}
			@Override public long depositSvq(GranularEnergyColumn.DepositKey key) {
				return thermalDepositGroup(key).solidVolumeSvq();
			}
			@Override public double depositMass(GranularEnergyColumn.DepositKey key) {
				return thermalDepositGroup(key).mass();
			}
			@Override public Map<String, Long> solidComposition(int compositionHandle) {
				if (compositionHandle == 0) return Map.of();
				if (domain.granularState.compositions == null || domain.chemistry == null) {
					throw new IllegalArgumentException("Solid thermal composition state is unavailable");
				}
				return domain.granularState.compositions.record(compositionHandle).stableAmounts(domain.chemistry.registry());
			}
		};
	}
	GranularDepositStore.Group thermalDepositGroup(GranularEnergyColumn.DepositKey key) {
		if (domain.granularState == null || key == null) throw new IllegalArgumentException("Granular deposit state is unavailable");
		GranularDepositStore.Group group = domain.granularState.deposits.matchingGroup(key.cell(), key.materialHandle(),
				candidate -> candidate.solidCompositionHandle() == key.compositionHandle());
		if (group == null) throw new IllegalArgumentException("Unknown granular thermal deposit identity");
		return group;
	}
	double thermalMarkerTemperature(int marker) {
		return domain.thermalState.temperature(marker, thermalMarkerContext());
	}
	double thermalOpenFaceFraction(int x, int y, int z, int direction) {
		return switch (direction) {
			case 0 -> domain.geometry.cutCells.xFaceOpen(x, y, z);
			case 1 -> domain.geometry.cutCells.xFaceOpen(x + 1, y, z);
			case 2 -> domain.geometry.cutCells.yFaceOpen(x, y, z);
			case 3 -> domain.geometry.cutCells.yFaceOpen(x, y + 1, z);
			case 4 -> domain.geometry.cutCells.zFaceOpen(x, y, z);
			case 5 -> domain.geometry.cutCells.zFaceOpen(x, y, z + 1);
			default -> throw new IllegalArgumentException("Unknown thermal boundary direction");
		};
	}
	void clearThermalBinding() {
		domain.thermalState = null;
		domain.thermalBoundaryTemperatureK = null;
		domain.pendingPhaseMotion.clear();
		if (domain.granularState == null) {
			domain.granularCoupling = null; domain.granularNextCoupling = null;
		}
	}
	void refreshCoherentOccupancy() {
		if (domain.granularCoupling == null || domain.granularNextCoupling == null) return;
		long[] fixed = new long[domain.cellCount];
		if (domain.thermalState != null) for (CoherentPhaseStore.Group group : domain.thermalState.coherentPhases().snapshot()) {
			fixed[group.cell()] = Math.addExact(fixed[group.cell()], group.occupiedVolumeSvq());
		}
		domain.granularCoupling.setFixedCellSvq(fixed);
		domain.granularNextCoupling.setFixedCellSvq(fixed);
		if (domain.domainPhaseInventory.hasGranularInventory()) {
			domain.granularCoupling.rebuild(domain.granularState.parcels, domain.granularState.deposits);
			domain.granularNextCoupling.rebuild(domain.granularState.parcels, domain.granularState.deposits);
		}
	}
	void bindSolidCompositionPoolIfReady() {
		if (domain.granularState == null || domain.granularState.compositions != null || domain.chemistry == null
				|| !domain.granularState.registry.hasReactiveRules()) return;
		if (!domain.granularState.registry.chemicalRegistryFingerprint().equals(domain.chemistry.registry().fingerprint())) {
			throw new IllegalArgumentException("Granular registry chemical dependency mismatch");
		}
		int depositCapacity = Math.min(GranularLimits.MAX_DEPOSIT_GROUPS_PER_REGION,
				Math.multiplyExact(domain.cellCount, GranularLimits.MAX_DEPOSIT_GROUPS_PER_CELL));
		domain.granularState.bindCompositions(new SolidCompositionPool(domain.chemistry.registry(),
				Math.addExact(GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION, depositCapacity)));
		domain.granularPhaseTransfer = new GranularPhaseTransferEngine(domain.chemistry.registry(), domain.unitScale);
	}
}
