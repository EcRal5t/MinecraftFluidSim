package mmm.fluid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import mmm.chemistry.ChemicalLimits;
import mmm.chemistry.ChemicalMixtureRecord;
import mmm.chemistry.ChemicalTransferLedger;
import mmm.chemistry.GasLiquidMassTransferDefinition;
import mmm.chemistry.RegionChemistry;
import mmm.fluid.air.GasAuditLedger;
import mmm.fluid.air.GasComponentAmount;
import mmm.fluid.air.GasInventory;
import mmm.fluid.air.GasRegistry;
import mmm.inventory.LiquidCellInventory;
import mmm.thermal.GasThermalModel;
import mmm.thermal.ThermalDomainState;
import mmm.thermal.ThermalStateFunction;
import mmm.units.Mass;
import mmm.units.SignedDyadic;

/** Finite-rate dilute solute transfer on the physical gas contact area, after ordinary reactions. */
final class GasLiquidMassTransferStage {
	private final FluidDomain domain;
	private record Key(LiquidCellInventory.Key stock, String rule) implements Comparable<Key> {
		public int compareTo(Key other) {
			int order = stock.compareTo(other.stock);
			return order == 0 ? rule.compareTo(other.rule) : order;
		}
	}
	private static final class Work {
		final GasLiquidMassTransferDefinition rule;
		final List<GasLiquidContactGeometry.Contact> contacts = new ArrayList<>();
		double seconds, residual;
		Work(GasLiquidMassTransferDefinition rule) { this.rule = rule; }
	}
	private static final class Cell {
		final int id;
		final ThermalDomainState.PhaseCandidateCell thermal;
		final TreeMap<LiquidCellInventory.Key, LiquidCellInventory.Stock> stocks = new TreeMap<>();
		double gasEnergyChange;
		Cell(int id, ThermalDomainState.PhaseCandidateCell thermal, List<LiquidCellInventory.Stock> rows) {
			this.id = id; this.thermal = thermal;
			for (var row : rows) stocks.put(row.key(), row);
		}
	}

	GasLiquidMassTransferStage(FluidDomain domain) { this.domain = domain; }

	FluidDomain.GasLiquidMassTransferStats step(double dt) {
		return step(dt, ChemicalLimits.MAX_REACTION_EVALUATIONS_PER_STEP);
	}

	FluidDomain.GasLiquidMassTransferStats step(double dt, int maximumEvaluations) {
		if (!(Double.isFinite(dt) && dt >= 0) || maximumEvaluations < 0)
			throw new IllegalArgumentException("Invalid gas/liquid mass transfer time or evaluation budget");
		if (domain.chemistry == null || domain.thermalState == null || !domain.thermalState.gasModelBound()
				|| domain.chemistry.registry().gasLiquidMassTransfers().isEmpty()) return new FluidDomain.GasLiquidMassTransferStats(false, false);
		domain.gasTopologyCoordinator.refresh();
		var chemistry = domain.chemistry;
		var registry = chemistry.registry();
		var work = new TreeMap<Key, Work>();
		for (var contact : GasLiquidContactGeometry.capture(domain)) {
			var stock = domain.liquidCells.stock(contact.key());
			if (stock == null || stock.mixture() == null || !(contact.areaM2() > 0)) continue;
			String material = domain.materialRegistry.material(stock.key().material()).id();
			int phase = registry.phaseHandleForMaterial(material);
			for (var rule : registry.gasLiquidMassTransfers()) if (phase == registry.phaseHandle(rule.liquidPhaseId())) {
				work.computeIfAbsent(new Key(stock.key(), rule.id()), ignored -> new Work(rule)).contacts.add(contact);
			}
		}
		var oldPending = new HashMap<Key, RegionChemistry.MassTransferPending>();
		for (var pending : chemistry.massTransferPending()) oldPending.put(
				new Key(new LiquidCellInventory.Key(pending.cell(), pending.material(), pending.supportMask()), pending.definitionId()), pending);
		for (var entry : work.entrySet()) {
			var pending = oldPending.remove(entry.getKey());
			entry.getValue().seconds = dt + (pending == null ? 0 : pending.pendingSeconds());
			entry.getValue().residual = pending == null ? 0 : pending.residualMoles();
		}
		var network = domain.gasState.network;
		var thermal = domain.thermalState;
		var gasModel = new GasThermalModel(thermal.registry(), network.registry());
		var gas = new ArrayList<GasInventory>();
		double[] energy = new double[network.nodeCount()];
		for (int node : network.nodeIds()) { gas.add(network.inventory(node)); energy[node - 1] = thermal.gasNodeEnergy(node, network); }
		var cells = new TreeMap<Integer, Cell>();
		var removedMass = new TreeMap<Integer, Long>();
		var pending = new ArrayList<RegionChemistry.MassTransferPending>();
		var gasSource = GasAuditLedger.EMPTY;
		var gasSink = GasAuditLedger.EMPTY;
		var externalSource = SignedDyadic.ZERO;
		var externalSink = SignedDyadic.ZERO;
		var finiteGasTransfer = SignedDyadic.ZERO;
		var chemicalSource = ChemicalTransferLedger.EMPTY;
		var chemicalSink = ChemicalTransferLedger.EMPTY;
		int evaluations = 0;
		boolean active = false;
		var order = new ArrayList<>(work.entrySet());
		order.sort(java.util.Comparator.<Map.Entry<Key, Work>>comparingDouble(entry -> entry.getValue().seconds)
				.reversed().thenComparing(Map.Entry::getKey));
		for (var entry : order) {
			var key = entry.getKey(); var item = entry.getValue();
			if (item.contacts.size() > maximumEvaluations - evaluations) {
				active = true;
				pending.add(pending(key, item.seconds, item.residual)); continue;
			}
			evaluations += item.contacts.size();
			var original = domain.liquidCells.stock(key.stock());
			var cell = cells.computeIfAbsent(key.stock().cell(), id -> new Cell(id,
					thermal.preparePhaseCandidateCell(id, domain.domainCapabilities.thermalMarkerContext()),
					domain.liquidCells.stocksInCell(id)));
			double remainingResidual = item.residual;
			double area = item.contacts.stream().mapToDouble(GasLiquidContactGeometry.Contact::areaM2).sum();
			for (var contact : item.contacts) {
				var stock = cell.stocks.get(key.stock());
				var rule = item.rule;
				int species = registry.speciesHandle(rule.speciesId());
				double liquidT = thermal.stateFunction().stateForInternalEnergy(
						thermal.thermalMaterialHandleForFluid(original.key().material()), original.massKilograms(),
						original.mixture().stableAmounts(registry), original.internalEnergy()).temperatureK();
				int node = contact.node();
				var donor = node == 0 ? network.registry().createAmbient(1_000_000_000L) : gas.get(node - 1);
				var component = donor.component(rule.gasComponentId());
				double gasT = node == 0 || donor.isEmpty() ? GasRegistry.STANDARD_TEMPERATURE_K
						: gasModel.state(donor, energy[node - 1]).temperatureK();
				double p = component == null ? 0 : node == 0
						? component.standardVolumeNanos() * 1.0e-9 * GasRegistry.STANDARD_PRESSURE_PA
						: component.standardVolumeNanos() * 1.0e-9 * GasRegistry.STANDARD_PRESSURE_PA
								* gasT / GasRegistry.STANDARD_TEMPERATURE_K / network.availableVolume(node);
				double h = rule.henrySolubilityAt(liquidT);
				double conductance = rule.overallLiquidFilmVelocityAt(liquidT, GasRegistry.MOLAR_GAS_CONSTANT) * contact.areaM2();
				double volume = contact.liquidVolumeM3();
				double liquidMoles = stock.mixture().amount(species) * 1.0e-6;
				double response = 1 / volume + (node == 0 ? 0 : h * GasRegistry.MOLAR_GAS_CONSTANT * gasT / network.availableVolume(node));
				// Half a species quantum is the resolution of the attainable equilibrium stock.
				double equilibriumMoles = (h * p - liquidMoles / volume) / response;
				boolean donorPresent = equilibriumMoles < 0 ? stock.mixture().amount(species) > 0
						: component != null && component.species().stream().anyMatch(s -> s.speciesId().equals(rule.speciesId()) && s.amountQuanta() > 0);
				active |= donorPresent && Math.abs(equilibriumMoles) >= .5 / ChemicalLimits.QUANTA_PER_MOL;
				double transfer = item.seconds * conductance * (h * p - liquidMoles / volume)
						/ (1 + item.seconds * conductance * response) + item.residual * contact.areaM2() / area;
				boolean dissolve = transfer > 0;
				long quanta = (long) Math.floor(Math.abs(transfer) * 1.0e6);
				if (!dissolve) quanta = Math.min(quanta, stock.mixture().amount(species));
				remainingResidual += transfer - item.residual * contact.areaM2() / area;
				if (quanta == 0) continue;
				GasInventory moved, after;
				double carried;
				if (dissolve) {
					if (component == null) continue;
					long available = component.species().stream().filter(s -> s.speciesId().equals(rule.speciesId()))
							.mapToLong(GasComponentAmount.SpeciesAmount::amountQuanta).sum();
					if (available == 0) continue;
					long nanos = node == 0
							? Math.max(1, Math.round(quanta * 1.0e-6 * GasRegistry.MOLAR_GAS_CONSTANT
									* GasRegistry.STANDARD_TEMPERATURE_K / GasRegistry.STANDARD_PRESSURE_PA * 1.0e9))
							: Math.min(component.standardVolumeNanos(), Math.max(1,
									Math.round(component.standardVolumeNanos() * Math.min(quanta, available) / (double) available)));
					if (node == 0) {
						moved = pureGas(rule, quanta);
						after = donor;
						carried = gasModel.internalEnergyAtTemperature(moved, gasT);
					} else {
						var split = donor.splitComponent(rule.gasComponentId(), nanos);
						moved = split.first(); after = split.second();
						carried = gasModel.partitionEnergy(donor, energy[node - 1], List.of(moved, after))[0];
					}
				} else {
					moved = pureGas(rule, quanta);
					after = node == 0 ? donor : donor.merge(moved);
					carried = gasModel.internalEnergyAtTemperature(moved, liquidT);
				}
				if (moved.isEmpty()) continue;
				long movedQuanta = moved.components().getFirst().species().stream()
						.filter(s -> s.speciesId().equals(rule.speciesId())).mapToLong(GasComponentAmount.SpeciesAmount::amountQuanta).sum();
				if (movedQuanta == 0) continue;
				double signedMoles = (dissolve ? 1 : -1) * movedQuanta * 1.0e-6;
				remainingResidual -= signedMoles;
				var replacement = replace(stock, moved, dissolve);
				cell.stocks.put(stock.key(), replacement);
				double gasDelta = dissolve ? -carried : carried;
				cell.gasEnergyChange += gasDelta;
				if (node > 0) {
					gas.set(node - 1, after);
					finiteGasTransfer = finiteGasTransfer.add(SignedDyadic.of(gasDelta));
					energy[node - 1] = SignedDyadic.of(energy[node - 1]).add(SignedDyadic.of(gasDelta)).toDouble();
					if (after.isEmpty()) {
						double remainder = energy[node - 1];
						cell.gasEnergyChange -= remainder;
						finiteGasTransfer = finiteGasTransfer.subtract(SignedDyadic.of(remainder));
						energy[node - 1] = 0;
					}
					if (dissolve) gasSink = gasSink.add(moved); else gasSource = gasSource.add(moved);
				} else {
					var ledger = ledger(stock.key(), moved);
					if (dissolve) { externalSource = externalSource.add(SignedDyadic.of(carried)); chemicalSource = chemicalSource.plus(ledger); }
					else { externalSink = externalSink.add(SignedDyadic.of(carried)); chemicalSink = chemicalSink.plus(ledger); }
				}
				if (!dissolve) removedMass.merge(stock.key().cell(), moved.totalMassUnits(), Math::addExact);
			}
			pending.add(pending(key, 0, remainingResidual));
		}
		if (cells.values().stream().noneMatch(c -> c.gasEnergyChange != 0 || c.stocks.values().stream()
				.anyMatch(s -> s != domain.liquidCells.stock(s.key())))) {
			chemistry.restoreMassTransferPending(pending); return new FluidDomain.GasLiquidMassTransferStats(active, false);
		}
		publish(cells, removedMass, gas, energy, gasSource, gasSink, externalSource, externalSink, finiteGasTransfer,
				chemistry.planAuditExchange(chemicalSource, chemicalSink));
		chemistry.restoreMassTransferPending(pending);
		return new FluidDomain.GasLiquidMassTransferStats(active, true);
	}

	private RegionChemistry.MassTransferPending pending(Key key, double seconds, double residual) {
		return new RegionChemistry.MassTransferPending(key.stock().cell(), key.stock().material(),
				key.stock().supportMask(), key.rule(), seconds, residual);
	}

	private GasInventory pureGas(GasLiquidMassTransferDefinition rule, long quanta) {
		var registry = domain.chemistry.registry();
		long mass = ChemicalMixtureRecord.fromStableAmounts(registry, Map.of(rule.speciesId(), quanta), 0).speciesMassUnits(registry);
		long nanos = Math.max(1, Math.round(quanta * 1.0e-6 * GasRegistry.MOLAR_GAS_CONSTANT
				* GasRegistry.STANDARD_TEMPERATURE_K / GasRegistry.STANDARD_PRESSURE_PA * 1.0e9));
		return new GasInventory(List.of(new GasComponentAmount(rule.gasComponentId(), nanos, mass,
				List.of(new GasComponentAmount.SpeciesAmount(rule.speciesId(), quanta)), 0)));
	}

	private LiquidCellInventory.Stock replace(LiquidCellInventory.Stock stock, GasInventory moved, boolean add) {
		var registry = domain.chemistry.registry();
		var amounts = new TreeMap<>(stock.mixture().stableAmounts(registry));
		int sign = add ? 1 : -1;
		for (var component : moved.components()) for (var species : component.species()) {
			long value = Math.addExact(amounts.getOrDefault(species.speciesId(), 0L), sign * species.amountQuanta());
			if (value == 0) amounts.remove(species.speciesId()); else amounts.put(species.speciesId(), value);
		}
		long mass = Math.addExact(stock.massUnits(), sign * moved.totalMassUnits());
		long residual = stock.mixture().massResidualUnits();
		for (var component : moved.components()) residual = Math.addExact(residual, sign * component.massResidualUnits());
		return new LiquidCellInventory.Stock(stock.key(), stock.volume(), mass,
				ChemicalMixtureRecord.fromStableAmounts(registry, amounts, residual), stock.internalEnergy());
	}

	private ChemicalTransferLedger ledger(LiquidCellInventory.Key key, GasInventory moved) {
		var species = new TreeMap<String, Long>(); long residual = 0;
		for (var component : moved.components()) {
			residual = Math.addExact(residual, component.massResidualUnits());
			for (var amount : component.species()) species.merge(amount.speciesId(), amount.amountQuanta(), Math::addExact);
		}
		return new ChemicalTransferLedger(List.of(new ChemicalTransferLedger.Group(
				domain.materialRegistry.material(key.material()).id(), 0, moved.totalMassUnits(), residual, species)));
	}

	private void publish(TreeMap<Integer, Cell> cells, Map<Integer, Long> removedMass,
			List<GasInventory> gas, double[] energy, GasAuditLedger source, GasAuditLedger sink,
			SignedDyadic externalSource, SignedDyadic externalSink, SignedDyadic gasTransfer, RegionChemistry.AuditExchange chemicalAudit) {
		var thermal = domain.thermalState;
		var edits = new ArrayList<LiquidCellInventory.Edit>();
		for (var cell : cells.values()) for (var stock : cell.stocks.values()) edits.add(new LiquidCellInventory.Edit(stock.key(), stock));
		var quantities = domain.liquidCells.planEdits(edits);
		var momentum = domain.cellMacStage.preparePhaseMomentum(quantities, removedMass);
		for (int id : momentum.heatByCell().keySet()) cells.computeIfAbsent(id, cell -> new Cell(cell,
				thermal.preparePhaseCandidateCell(cell, domain.domainCapabilities.thermalMarkerContext()), domain.liquidCells.stocksInCell(cell)));
		var trials = new ArrayList<ThermalDomainState.PhaseCellTrial>();
		int[] counts = new int[cells.size()]; int ordinal = 0;
		var mechanical = SignedDyadic.ZERO;
		for (var cell : cells.values()) {
			var heat = momentum.heatByCell().getOrDefault(cell.id, SignedDyadic.ZERO)
					.add(momentum.removedKineticEnergyByCell().getOrDefault(cell.id, SignedDyadic.ZERO));
			long massDelta = 0;
			for (var stock : cell.stocks.values()) massDelta = Math.addExact(massDelta,
					stock.massUnits() - domain.liquidCells.stock(stock.key()).massUnits());
			heat = heat.subtract(SignedDyadic.of(Mass.kilograms(massDelta) * domain.config.gravity()
					* (cell.id / domain.sizeX % domain.sizeY + .5) * domain.config.cellSize()));
			mechanical = mechanical.add(heat);
			var portions = new ArrayList<ThermalStateFunction.InventoryPortion>();
			for (var stock : cell.stocks.values()) if (stock.massUnits() > 0) portions.add(
					new ThermalStateFunction.InventoryPortion(thermal.thermalMaterialHandleForFluid(stock.key().material()),
							stock.massKilograms(), stock.mixture().stableAmounts(domain.chemistry.registry())));
			counts[ordinal++] = portions.size();
			trials.add(thermal.trialPhaseCellEnergyWithHeat(cell.thermal, portions, cell.gasEnergyChange, heat));
		}
		var gasEnergy = thermal.planGasInventoryEnergy(domain.gasState.network, domain.gasState.network.nodeIds(), gas, energy, gasTransfer);
		var cellEnergy = thermal.planMassTransferBatchEnergy(trials, counts, gasEnergy.roundoff(), mechanical,
				externalSource, externalSink, domain.domainCapabilities.thermalMarkerContext());
		var energies = new TreeMap<LiquidCellInventory.Key, Double>();
		for (var cell : cells.values()) {
			double[] values = cellEnergy.liquidEnergies(cell.id); int index = 0;
			for (var stock : cell.stocks.values()) energies.put(stock.key(), stock.massUnits() == 0 ? 0 : values[index++]);
		}
		var inventory = quantities.withInternalEnergiesByStock(energies);
		var batch = new GasTopologyCoordinator.PhaseBatch(gas, source, sink, gasEnergy, cellEnergy, gas, energy, List.of());
		var topology = domain.gasTopologyCoordinator.planAfterPhaseBatch(inventory.future(), batch);
		domain.cellMacStage.publishMaterialChange(inventory, () -> domain.cellMacStage.commitMomentum(momentum),
				topology, null, () -> {
					thermal.commitPhaseBatchEnergy(cellEnergy, domain.domainCapabilities.thermalMarkerContext());
					domain.chemistry.commitAuditExchange(chemicalAudit);
				}, () -> {
					domain.gasTopologyCoordinator.requireAfterPhaseBatchCurrent(topology, batch);
					domain.chemistry.requireCurrentAuditExchange(chemicalAudit);
				});
	}
}
