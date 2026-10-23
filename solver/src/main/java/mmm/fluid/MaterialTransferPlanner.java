package mmm.fluid;

import mmm.inventory.LiquidCellInventory;

import mmm.chemistry.ChemicalLimits;
import mmm.chemistry.ChemicalMixtureRecord;
import mmm.chemistry.RegionChemistry;
import mmm.chemistry.ChemicalTransferLedger;
import mmm.thermal.ThermalAuditLedger;
import mmm.thermal.ThermalStateFunction;
import mmm.units.Mass;
import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Exact external quantity transactions over the same persistent cells used by fluid transport. */
final class MaterialTransferPlanner {
	private final FluidDomain domain;
	private double externalKineticSinkJoules;
	MaterialTransferPlanner(FluidDomain domain) { this.domain = domain; }
	double externalKineticSinkJoules() { return externalKineticSinkJoules; }
	private record Input(int material, SignedDyadic volume, long mass, ChemicalMixtureRecord mixture) { }
	private record Placement(LiquidCellInventory.Key key, SignedDyadic volume, int input) { }
	private record Extraction(LiquidCellInventory.Plan inventory, List<FluidDomain.MaterialPortion> portions,
	                          ChemicalTransferLedger ledger, double energy, SignedDyadic volume) { }

	FluidDomain.ExactMixtureTransfer insertMixture(int x, int y, int z, List<FluidDomain.MaterialPortion> portions,
	                                             int radius, boolean externalSource, Double energy) {
		if (portions == null || portions.isEmpty()) throw new IllegalArgumentException("Mixture insertion is empty");
		var inputs = new ArrayList<Input>(); var seen = new java.util.HashSet<Integer>();
		for (var portion : portions) {
			domain.domainFlowFields.validateMaterialPortion(portion);
			if (externalSource && portion.massUnits() == 0) throw new IllegalArgumentException("External liquid source requires actual positive mass");
			if (!seen.add(portion.materialHandle())) throw new IllegalArgumentException("Repeated insertion material");
			inputs.add(defaultInput(portion.materialHandle(), SignedDyadic.of(portion.volume()), portion.massUnits()));
		}
		boolean inserted = insert(inputs, domain.domainFlowFields.nearbyCells(x, y, z, radius), externalSource, energy);
		return new FluidDomain.ExactMixtureTransfer(inserted, inserted ? List.copyOf(portions) : List.of(), inserted ? suppliedEnergy(inputs, energy) : 0);
	}
	FluidDomain.ExactChemicalTransfer insertChemical(int x, int y, int z, ChemicalTransferLedger ledger,
	                                                int radius, boolean externalSource, Double energy) {
		if (domain.chemistry == null) throw new IllegalStateException("Chemical insertion requires chemistry");
		if (ledger == null) throw new IllegalArgumentException("Chemical ledger is missing");
		if (ledger.isEmpty()) return new FluidDomain.ExactChemicalTransfer(true, ledger);
		var inputs = new ArrayList<Input>(); var chemistry = domain.chemistry.registry();
		for (var group : ledger.groups()) {
			if (externalSource && group.massUnits() <= 0) throw new IllegalArgumentException("External liquid source requires actual positive mass");
			int material = domain.materialRegistry.handle(group.materialId());
			var mixture = ChemicalMixtureRecord.fromStableAmounts(chemistry, group.speciesQuanta(), group.massResidualUnits());
			if (mixture.massUnits(chemistry) != group.massUnits()) throw new IllegalArgumentException("Chemical source mass differs from its species and residual");
			domain.chemistry.requireCompatible(mixture, group.materialId());
			inputs.add(new Input(material, SignedDyadic.of(group.normalizedVolume()), group.massUnits(), mixture));
		}
		boolean inserted = insert(inputs, domain.domainFlowFields.nearbyCells(x, y, z, radius), externalSource, energy);
		return new FluidDomain.ExactChemicalTransfer(inserted, inserted ? ledger : ChemicalTransferLedger.EMPTY, inserted ? suppliedEnergy(inputs, energy) : 0);
	}
	FluidDomain.VolumeAddition insertVolumeAtMost(int x, int y, int z, float amount, int radius) {
		domain.domainFlowFields.validateVolumeEdit(amount, radius);
		if (amount <= 0 || domain.hasPendingPhaseMotionWork()) return new FluidDomain.VolumeAddition(0, false);
		var nearby = domain.domainFlowFields.nearbyCells(x, y, z, radius); var room = SignedDyadic.ZERO;
		for (var candidate : nearby) room = room.add(available(candidate.index()));
		var requested = SignedDyadic.of(amount); var accepted = minimum(room, requested);
		float represented = (float) accepted.toDouble();
		if (SignedDyadic.of(represented).compareTo(accepted) > 0) represented = Math.nextDown(represented);
		if (!(represented > 0)) return new FluidDomain.VolumeAddition(0, false);
		var volume = SignedDyadic.of(represented);
		double density = domain.materialRegistry == null ? domain.config.density() : domain.materialRegistry.material(0).referenceDensity();
		long mass = domain.unitScale.massUnitsForNormalized(volume.toDouble() * density);
		if (mass == 0) return new FluidDomain.VolumeAddition(0, false);
		boolean inserted = insert(List.of(defaultInput(0, volume, mass)), nearby, true, null);
		return new FluidDomain.VolumeAddition(inserted ? represented : 0, false);
	}
	private Input defaultInput(int material, SignedDyadic volume, long mass) {
		if (mass < 0) throw new IllegalArgumentException("Liquid packet mass must be non-negative");
		var mixture = domain.chemistry == null ? null : domain.chemistry.defaultRecord(
				materialId(material), volume.toDouble(), domain.config.cellSize(), mass);
		return new Input(material, volume, mass, mixture);
	}
	private boolean insert(List<Input> inputs, List<FluidDomain.CellCandidate> nearby, boolean externalSource, Double energy) {
		if (domain.hasPendingPhaseMotionWork()) return false;
		domain.domainFlowFields.ensureInventoryGrid();
		var requested = SignedDyadic.ZERO; long mass = domain.liquidCells.massUnits();
		for (var input : inputs) { requested = requested.add(input.volume()); mass = Math.addExact(mass, input.mass()); }
		if (!domain.domainCollision.canAddCarrierVolume(requested.toDouble())) return false;
		var routing = new LiquidInsertionGasPlan.CapacityRouting(domain);
		var rooms = new LinkedHashMap<Long, SignedDyadic>(); var placements = new ArrayList<Placement>();
		for (int input = 0; input < inputs.size(); input++) {
			var remaining = inputs.get(input).volume();
			for (var candidate : nearby) {
				int cell = candidate.index(); if (remaining.signum() == 0) break;
				int mask = domain.geometry.cutCells.mask(cell);
				var supports = LiquidCellInventory.connectedSupports(mask);
				var capacities = CellRemapMicroTopology.capacities(mask, SignedDyadic.of(domain.domainCollision.effectiveOpenCellVolume(cell)));
				for (int component = 0; component < supports.size() && remaining.signum() > 0; component++) {
					int support = supports.get(component); long identity = (long) cell << 8 | support;
					var free = rooms.get(identity);
					if (free == null) { free = capacities[component]; for (var stock : domain.liquidCells.stocksInCell(cell))
						if (stock.key().supportMask() == support) free = free.subtract(stock.volume()); rooms.put(identity, free); }
					var take = minimum(free, remaining); if (take.signum() <= 0) continue;
					if (take.equals(availableFromRooms(cell, rooms)) && !routing.canFill(cell)) continue;
					placements.add(new Placement(new LiquidCellInventory.Key(cell, inputs.get(input).material(), support), take, input));
					rooms.put(identity, free.subtract(take)); remaining = remaining.subtract(take);
					if (availableFromRooms(cell, rooms).signum() == 0) routing.filled(cell);
				}
			}
			if (remaining.signum() > 0) return false;
		}
		Transaction candidate;
		try {
			var pieces = insertionStocks(inputs, placements, energy);
			var plan = domain.liquidCells.planAdditions(pieces);
			var sourceLedger = externalSource ? ledger(inputs) : ChemicalTransferLedger.EMPTY;
			var sourceEnergy = domain.thermalState == null ? SignedDyadic.ZERO : SignedDyadic.of(suppliedEnergy(inputs, energy));
			candidate = prepare(plan, sourceLedger, ChemicalTransferLedger.EMPTY, sourceEnergy, SignedDyadic.ZERO);
		} catch (ThermalStateFunction.EnergyOutsideValidityException | ThermalStateFunction.EnergyRepresentationException unavailable) {
			return false;
		}
		candidate.commit();
		return true;
	}
	private List<LiquidCellInventory.Stock> insertionStocks(List<Input> inputs, List<Placement> placements, Double energy) {
		double temperature = domain.thermalState == null ? Double.NaN : domain.thermalState.insertionTemperatureK();
		if (inputs.stream().allMatch(input -> input.mass() == 0) && energy != null && energy != 0)
			throw new IllegalArgumentException("A geometric tail cannot carry internal energy");
		if (domain.thermalState != null && energy != null && inputs.stream().anyMatch(input -> input.mass() > 0)) {
			var inventory = new ArrayList<ThermalStateFunction.InventoryPortion>();
			for (var input : inputs) if (input.mass() > 0) inventory.add(new ThermalStateFunction.InventoryPortion(
					domain.thermalState.thermalMaterialHandleForFluid(input.material()), Mass.kilograms(input.mass()), stable(input.mixture())));
			temperature = domain.thermalState.stateFunction().stateForInventory(inventory, energy).temperatureK();
		}
		var result = new ArrayList<LiquidCellInventory.Stock>();
		for (int source = 0; source < inputs.size(); source++) {
			int sourceIndex = source; var input = inputs.get(source);
			var selected = placements.stream().filter(piece -> piece.input() == sourceIndex).toList();
			var weights = new SignedDyadic[selected.size()]; for (int i = 0; i < weights.length; i++) weights[i] = selected.get(i).volume();
			var masses = LiquidCellInventory.allocateQuantities(input.mass(), weights);
			long[][] species = new long[input.mixture() == null ? 0 : input.mixture().entryCount()][];
			for (int i = 0; i < species.length; i++) species[i] = LiquidCellInventory.allocateSpecies(input.mixture().amountAt(i),masses,weights);
			for (int i = 0; i < selected.size(); i++) {
				var mixture = mixturePart(input.mixture(), species, i, masses[i]);
				double internal = masses[i] == 0 ? 0 : domain.thermalState == null
						? energy == null ? 0 : energy * masses[i] / inputs.stream().mapToDouble(Input::mass).sum()
						: modeledEnergy(input.material(), masses[i], mixture, temperature);
				result.add(new LiquidCellInventory.Stock(selected.get(i).key(), weights[i], masses[i], mixture, internal));
			}
		}
		if (!result.isEmpty()) {
			int largest = 0; var stored = SignedDyadic.ZERO;
			for (int i = 0; i < result.size(); i++) { stored = stored.add(SignedDyadic.of(result.get(i).internalEnergy())); if (result.get(i).massUnits() > result.get(largest).massUnits()) largest = i; }
			var stock = result.get(largest); var target = SignedDyadic.of(suppliedEnergy(inputs, energy));
			double corrected = SignedDyadic.of(stock.internalEnergy()).add(target.subtract(stored)).toDouble();
			result.set(largest, new LiquidCellInventory.Stock(stock.key(), stock.volume(), stock.massUnits(), stock.mixture(), corrected));
		}
		return result;
	}
	private double suppliedEnergy(List<Input> inputs, Double explicit) {
		if (explicit != null) return explicit;
		if (domain.thermalState == null) return 0;
		var sum = SignedDyadic.ZERO;
		for (var input : inputs) if (input.mass() > 0) sum = sum.add(SignedDyadic.of(modeledEnergy(input.material(), input.mass(), input.mixture(), domain.thermalState.insertionTemperatureK())));
		return sum.toDouble();
	}
	float removeVolumeNear(int x, int y, int z, float amount, int radius) {
		domain.domainFlowFields.validateVolumeEdit(amount, radius); if (amount <= 0) return 0;
		var nearby = domain.domainFlowFields.nearbyCells(x, y, z, radius); var available = SignedDyadic.ZERO;
		for (var cell : nearby) available = available.add(domain.liquidCells.exactCellVolume(cell.index()));
		var extraction = extract(nearby, minimum(available, SignedDyadic.of(amount)), -1, false);
		if (extraction == null) return 0;
		prepare(extraction.inventory(), ChemicalTransferLedger.EMPTY, extraction.ledger(), SignedDyadic.ZERO, SignedDyadic.of(extraction.energy())).commit();
		return (float) extraction.volume().toDouble();
	}
	FluidDomain.ExactMixtureTransfer extractMixture(int x, int y, int z, float amount, int radius, int material) {
		domain.domainFlowFields.validateVolumeEdit(amount, radius);
		var extraction = extract(domain.domainFlowFields.nearbyCells(x, y, z, radius), SignedDyadic.of(amount), material, true);
		if (extraction == null) return new FluidDomain.ExactMixtureTransfer(false, List.of());
		prepare(extraction.inventory(), ChemicalTransferLedger.EMPTY, extraction.ledger(), SignedDyadic.ZERO, SignedDyadic.of(extraction.energy())).commit();
		return new FluidDomain.ExactMixtureTransfer(true, extraction.portions(), extraction.energy());
	}
	FluidDomain.ExactChemicalTransfer extractChemical(int x, int y, int z, float amount, int radius,
	                                                java.util.function.Predicate<ChemicalTransferLedger> accepts) {
		if (domain.chemistry == null) throw new IllegalStateException("Chemical extraction requires chemistry");
		if (accepts == null) throw new IllegalArgumentException("Chemical extraction validator is missing");
		domain.domainFlowFields.validateVolumeEdit(amount, radius);
		var extraction = extract(domain.domainFlowFields.nearbyCells(x, y, z, radius), SignedDyadic.of(amount), -1, false);
		if (extraction == null || extraction.ledger().groups().stream().anyMatch(group -> group.massUnits() == 0)
				|| !accepts.test(extraction.ledger())) return new FluidDomain.ExactChemicalTransfer(false, ChemicalTransferLedger.EMPTY);
		prepare(extraction.inventory(), ChemicalTransferLedger.EMPTY, extraction.ledger(), SignedDyadic.ZERO, SignedDyadic.of(extraction.energy())).commit();
		return new FluidDomain.ExactChemicalTransfer(true, extraction.ledger(), extraction.energy());
	}
	private Extraction extract(List<FluidDomain.CellCandidate> nearby, SignedDyadic requested, int material, boolean canonical) {
		if (requested.signum() < 0 || domain.hasPendingPhaseMotionWork()) return null;
		domain.domainFlowFields.ensureInventoryGrid();
		var remaining = requested; var edits = new ArrayList<LiquidCellInventory.Edit>(); var removed = new LinkedHashMap<Integer, Removed>();
		var removedEnergy = SignedDyadic.ZERO;
		for (var cell : nearby) for (var stock : domain.liquidCells.stocksInCell(cell.index())) {
			if (remaining.signum() == 0) break;
			if (material >= 0 && stock.key().material() != material || canonical && !canonical(stock)) continue;
			var taken = minimum(remaining, stock.volume()); var kept = stock.volume().subtract(taken);
			long keptMass = kept.signum() == 0 ? 0 : Mass.portion(stock.massUnits(), kept, stock.volume());
			long takenMass = stock.massUnits() - keptMass;
			var masses=new long[]{keptMass,takenMass};var volumes=new SignedDyadic[]{kept,taken};
			var species=new long[stock.mixture()==null?0:stock.mixture().entryCount()][];
			for(int entry=0;entry<species.length;entry++)species[entry]=LiquidCellInventory.allocateSpecies(stock.mixture().amountAt(entry),masses,volumes);
			var keptMixture=mixturePart(stock.mixture(),species,0,keptMass);
			var takenMixture=mixturePart(stock.mixture(),species,1,takenMass);
			var energyParts=LiquidCellInventory.partitionEnergy(stock,masses,
					new ChemicalMixtureRecord[]{keptMixture,takenMixture},FluidThermalBinding.liquidEnergyPartitioner(domain));
			double keptEnergy=energyParts[0];
			var after = kept.signum() == 0 ? null : new LiquidCellInventory.Stock(stock.key(), kept, keptMass, keptMixture, keptEnergy);
			edits.add(new LiquidCellInventory.Edit(stock.key(), after));
			removedEnergy = removedEnergy.add(SignedDyadic.of(stock.internalEnergy()).subtract(SignedDyadic.of(keptEnergy)));
			removed.computeIfAbsent(stock.key().material(), Removed::new)
					.add(taken, takenMass, takenMixture);
			remaining = remaining.subtract(taken);
		}
		if (remaining.signum() != 0) return null;
		var portions = new ArrayList<FluidDomain.MaterialPortion>(); var groups = new ArrayList<ChemicalTransferLedger.Group>();
		for (var group : removed.values()) {
			if (canonical && group.mass == 0) return null;
			if (group.mass > 0) portions.add(new FluidDomain.MaterialPortion(group.material, group.volume.toDouble(), group.mass));
			if (domain.chemistry != null) groups.add(group.group());
		}
		return new Extraction(domain.liquidCells.planEdits(edits), List.copyOf(portions), new ChemicalTransferLedger(groups), removedEnergy.toDouble(), requested);
	}
	LiquidCellInventory.Plan planLiquidClear() {
		return domain.liquidCells.planEdits(domain.liquidCells.stocks().stream().map(stock -> new LiquidCellInventory.Edit(stock.key(), null)).toList());
	}
	void clearLiquid(LiquidCellInventory.Plan inventory, ChemicalTransferLedger drained, SignedDyadic energy) {
		prepare(inventory, ChemicalTransferLedger.EMPTY, drained, SignedDyadic.ZERO, energy).commit();
	}
	void replaceCellVolumes(int[] cells, float[] requestedVolumes, int[] materials) {
		if (cells.length != requestedVolumes.length || materials != null && materials.length != cells.length) throw new IllegalArgumentException("Cell volume arrays differ");
		for (int i=0;i<cells.length;i++) {
			if (cells[i]<0 || cells[i]>=domain.cellCount || !Float.isFinite(requestedVolumes[i]))
				throw new IllegalArgumentException("Cell replacement is outside the grid or nonfinite");
			if (domain.geometry.cutCells.fullySolid(cells[i]) && requestedVolumes[i]>0)
				throw new IllegalStateException("Cannot place fluid in a solid cell");
		}
		if (domain.granularCoupling != null) domain.domainFlowFields.ensureInventoryGrid();
		var removed = new LinkedHashMap<Integer, Removed>();
		var sink = SignedDyadic.ZERO; var source = SignedDyadic.ZERO;
		var edits = new LinkedHashMap<LiquidCellInventory.Key, LiquidCellInventory.Edit>();
		var sourceGroups = new ArrayList<Input>();
		for (int i=0;i<cells.length;i++) {
			int cell=cells[i], material=materials==null?0:materials[i];
			if (material<0 || domain.materialRegistry==null && material!=0) throw new IllegalArgumentException("Cell material requires its bound palette");
			double density=domain.materialRegistry==null?domain.config.density():domain.materialRegistry.material(material).referenceDensity();
			for (var stock : domain.liquidCells.stocksInCell(cell)) {
				removed.computeIfAbsent(stock.key().material(),Removed::new)
						.add(stock.volume(),stock.massUnits(),stock.mixture());
				sink=sink.add(SignedDyadic.of(stock.internalEnergy()));
				edits.put(stock.key(),new LiquidCellInventory.Edit(stock.key(),null));
			}
			var capacity=SignedDyadic.of(domain.geometry.cutCells.openCellVolume(cell));
			if (domain.granularCoupling!=null) capacity=capacity.subtract(SignedDyadic.of(domain.granularCoupling.solidSvq(cell),-24));
			if (capacity.signum()<0) throw new IllegalStateException("Solid inventory exceeds cell capacity");
			var volume=minimum(SignedDyadic.of(Math.max(0,Math.min(1,requestedVolumes[i]))),capacity);
			if (volume.signum()==0) continue;
			var input=defaultInput(material,volume,domain.unitScale.massUnitsForNormalized(volume.toDouble()*density));
			sourceGroups.add(input);source=source.add(SignedDyadic.of(suppliedEnergy(List.of(input),null)));
			var supports=LiquidCellInventory.connectedSupports(domain.geometry.cutCells.mask(cell));
			var capacities=CellRemapMicroTopology.capacities(domain.geometry.cutCells.mask(cell),capacity);
			var parts=LiquidCellInventory.allocateVolumes(volume,capacities,java.util.Arrays.stream(capacities).mapToDouble(SignedDyadic::toDouble).toArray());
			var placements=new ArrayList<Placement>();
			for (int component=0;component<parts.length;component++) if(parts[component].signum()>0)
				placements.add(new Placement(new LiquidCellInventory.Key(cell,material,supports.get(component)),parts[component],0));
			for(var stock:insertionStocks(List.of(input),placements,null)) edits.put(stock.key(),new LiquidCellInventory.Edit(stock.key(),stock));
		}
		var sinkGroups=new ArrayList<ChemicalTransferLedger.Group>();
		if(domain.chemistry!=null) for(var group:removed.values()) sinkGroups.add(group.group());
		prepare(domain.liquidCells.planEdits(new ArrayList<>(edits.values())),ledger(sourceGroups),new ChemicalTransferLedger(sinkGroups),source,sink).commit();
	}

	private Transaction prepare(LiquidCellInventory.Plan original, ChemicalTransferLedger source, ChemicalTransferLedger sink,
	                            SignedDyadic sourceEnergy, SignedDyadic sinkEnergy) {
		var momentum = domain.cellMacStage.prepareExternalMomentum(original);
		var inventory = original.withMechanicalHeat(momentum.heatByCell());
		long quanta = 0; for (var stock : inventory.future().stocks()) if (stock.mixture() != null) quanta = Math.addExact(quanta, stock.mixture().totalQuanta());
		if (quanta > ChemicalLimits.MAX_REGION_TOTAL_QUANTA) throw new IllegalArgumentException("Liquid chemical inventory is over budget");
		if (domain.thermalState != null) for (int cell : inventory.changedCells())
			for (var stock : inventory.future().physicalStocksInCell(cell))
				domain.thermalState.stateFunction().stateForInternalEnergy(domain.thermalState.thermalMaterialHandleForFluid(stock.key().material()),
						stock.massKilograms(), stable(stock.mixture()), stock.internalEnergy());
		var chemical = domain.chemistry == null ? null : domain.chemistry.planAuditExchange(source, sink);
		var before = domain.thermalState == null ? null : domain.thermalState.auditLedger();
		var after = before == null ? null : before.withExternalSourceEnergy(sourceEnergy).withExternalSinkEnergy(sinkEnergy)
				.withMechanicalWork(inventory.mechanicalHeat()).withRoundoff(inventory.energyChange().subtract(sourceEnergy).add(sinkEnergy).subtract(inventory.mechanicalHeat()));
		long[] occupied = domain.occupiedSolidSvq();
		var gas = domain.gasState.frame == null ? null : domain.gasTopologyCoordinator.planAfterCarrierEdit(inventory.future(), occupied);
		if (gas != null) domain.gasTopologyCoordinator.requireBeforeCarrierCommit(gas);
		if (after != null) {
			var combined = after; var thermal = gas == null ? null : gas.thermalPlan();
			if (thermal != null) combined = combined.withExternalSourceEnergy(thermal.sourceEnergy()).withExternalSinkEnergy(thermal.sinkEnergy())
					.withMechanicalWork(thermal.mechanicalWork()).withRoundoff(thermal.roundoff());
			combined.expectedInventory();
		}
		return new Transaction(inventory, momentum, momentum.removedKineticEnergy(), gas, chemical, before, after);
	}
	private final class Transaction {
		final LiquidCellInventory.Plan inventory; final CellMacStage.ExternalMomentum momentum; final double removedKinetic;
		final GasTopologyCoordinator.GasTopologyPlan gas; final RegionChemistry.AuditExchange chemical; final ThermalAuditLedger before, after;
		Transaction(LiquidCellInventory.Plan inventory, CellMacStage.ExternalMomentum momentum, double removedKinetic,
		            GasTopologyCoordinator.GasTopologyPlan gas, RegionChemistry.AuditExchange chemical, ThermalAuditLedger before, ThermalAuditLedger after) {
			this.inventory = inventory; this.momentum = momentum; this.removedKinetic = removedKinetic; this.gas = gas; this.chemical = chemical; this.before = before; this.after = after;
		}
		void commit() {
			inventory.requireCurrent();
			if (chemical != null) domain.chemistry.requireCurrentAuditExchange(chemical);
			if (before != null && domain.thermalState.auditLedger() != before) throw new IllegalStateException("Liquid external thermal audit changed after preparation");
			domain.cellMacStage.publishMaterialChange(inventory,()->domain.cellMacStage.commitMomentum(momentum),gas,null,()->{
                if(chemical!=null)domain.chemistry.commitAuditExchange(chemical);
                if(after!=null)domain.thermalState.restoreAuditLedger(after);
            });
            externalKineticSinkJoules+=removedKinetic;
		}
	}
	private SignedDyadic available(int cell) { return SignedDyadic.of(domain.domainCollision.effectiveOpenCellVolume(cell)).subtract(domain.liquidCells.exactCellVolume(cell)); }
	private SignedDyadic availableFromRooms(int cell, Map<Long, SignedDyadic> rooms) {
		var total = available(cell);
		for (var entry : rooms.entrySet()) if ((int) (entry.getKey() >>> 8) == cell) {
			int support = (int) (entry.getKey() & 255); var original = SignedDyadic.ZERO;
			var supports = LiquidCellInventory.connectedSupports(domain.geometry.cutCells.mask(cell));
			var capacities = CellRemapMicroTopology.capacities(domain.geometry.cutCells.mask(cell), SignedDyadic.of(domain.domainCollision.effectiveOpenCellVolume(cell)));
			for (int i = 0; i < supports.size(); i++) if (supports.get(i) == support) original = capacities[i];
			for (var stock : domain.liquidCells.stocksInCell(cell)) if (stock.key().supportMask() == support) original = original.subtract(stock.volume());
			total = total.subtract(original.subtract(entry.getValue()));
		}
		return total;
	}
	private String materialId(int material) { return domain.materialRegistry == null ? BuiltinFluidMaterials.WATER.id() : domain.materialRegistry.material(material).id(); }
	private Map<String, Long> stable(ChemicalMixtureRecord mixture) { return mixture == null ? Map.of() : mixture.stableAmounts(domain.chemistry.registry()); }
	private double modeledEnergy(int material, long mass, ChemicalMixtureRecord mixture, double temperature) {
		return domain.thermalState.stateFunction().internalEnergyAtTemperature(domain.thermalState.thermalMaterialHandleForFluid(material), Mass.kilograms(mass), stable(mixture), temperature);
	}
	private boolean canonical(LiquidCellInventory.Stock stock) {
		if (stock.mixture() == null) return true;
		var registry = domain.chemistry.registry(); var phase = registry.phase(registry.phaseHandleForMaterial(materialId(stock.key().material())));
		for (int i = 0; i < stock.mixture().entryCount(); i++) if (!phase.defaultConcentrationMolPerCubicMeter().containsKey(registry.species(stock.mixture().speciesHandle(i)).id())) return false;
		return true;
	}
	private ChemicalTransferLedger ledger(List<Input> inputs) {
		if (domain.chemistry == null) return ChemicalTransferLedger.EMPTY;
		var groups = new ArrayList<ChemicalTransferLedger.Group>();
		for (var input : inputs) groups.add(new ChemicalTransferLedger.Group(materialId(input.material()), input.volume().toDouble(),
				input.mass(), input.mixture().massResidualUnits(), stable(input.mixture())));
		return new ChemicalTransferLedger(groups);
	}
	private ChemicalMixtureRecord mixturePart(ChemicalMixtureRecord source, long[][] species, int index, long mass) {
		if (source == null) return null; var amounts = new TreeMap<Integer, Long>();
		for (int i = 0; i < species.length; i++) if (species[i][index] > 0) amounts.put(source.speciesHandle(i), species[i][index]);
		return mixture(amounts, mass);
	}
	private ChemicalMixtureRecord mixture(TreeMap<Integer, Long> amounts, long mass) {
		int[] handles = new int[amounts.size()]; long[] quantities = new long[amounts.size()]; int i = 0;
		for (var entry : amounts.entrySet()) { handles[i] = entry.getKey(); quantities[i++] = entry.getValue(); }
		var record = new ChemicalMixtureRecord(handles, quantities, 0);
		return record.withMassResidualUnits(Math.subtractExact(mass, record.speciesMassUnits(domain.chemistry.registry())));
	}
	private final class Removed {
		final int material; SignedDyadic volume = SignedDyadic.ZERO; long mass; final TreeMap<String, Long> species = new TreeMap<>();
		Removed(int material) { this.material = material; }
		void add(SignedDyadic volume, long mass, ChemicalMixtureRecord mixture) {
			this.volume = this.volume.add(volume); this.mass = Math.addExact(this.mass, mass);
			if (mixture != null) stable(mixture).forEach((id, amount) -> species.merge(id, amount, Math::addExact));
		}
		ChemicalTransferLedger.Group group() {
			var record = ChemicalMixtureRecord.fromStableAmounts(domain.chemistry.registry(), species, 0);
			return new ChemicalTransferLedger.Group(materialId(material), volume.toDouble(), mass,
					Math.subtractExact(mass, record.speciesMassUnits(domain.chemistry.registry())), species);
		}
	}
	private static SignedDyadic minimum(SignedDyadic first, SignedDyadic second) { return first.compareTo(second) < 0 ? first : second; }
}
