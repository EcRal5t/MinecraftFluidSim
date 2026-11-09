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
	private final HostFluidState domain;
	MaterialTransferPlanner(HostFluidState domain) { this.domain = domain; }
	private record Input(int material, SignedDyadic volume, long mass, ChemicalMixtureRecord mixture) { }
	private record Placement(LiquidCellInventory.Key key, SignedDyadic volume, int input) { }
	private record Extraction(LiquidCellInventory.Plan inventory, List<FluidDomainContracts.MaterialPortion> portions,
	                          ChemicalTransferLedger ledger, double energy, SignedDyadic volume) { }

	FluidDomainContracts.ExactMixtureTransfer insertMixture(int x, int y, int z, List<FluidDomainContracts.MaterialPortion> portions,
	                                             int radius, boolean externalSource, Double energy) {
		if (portions == null || portions.isEmpty()) throw new IllegalArgumentException("Mixture insertion is empty");
		var inputs = new ArrayList<Input>(); var seen = new java.util.HashSet<Integer>();
		for (var portion : portions) {
			domain.domainFlowFields.validateMaterialPortion(portion);
			if (externalSource && portion.massUnits() == 0) throw new IllegalArgumentException("External liquid source requires actual positive mass");
			if (!seen.add(portion.materialHandle())) throw new IllegalArgumentException("Repeated insertion material");
			inputs.add(defaultInput(portion.materialHandle(), SignedDyadic.of(portion.volume()), portion.massUnits()));
		}
		boolean inserted = insert(inputs, domain.domainFlowFields.editableNearbyCells(x, y, z, radius), externalSource, energy);
		return new FluidDomainContracts.ExactMixtureTransfer(inserted, inserted ? List.copyOf(portions) : List.of(), inserted ? suppliedEnergy(inputs, energy) : 0);
	}
	FluidDomainContracts.ExactChemicalTransfer insertChemical(int x, int y, int z, ChemicalTransferLedger ledger,
	                                                int radius, boolean externalSource, Double energy) {
		if (domain.chemistry == null) throw new IllegalStateException("Chemical insertion requires chemistry");
		if (ledger == null) throw new IllegalArgumentException("Chemical ledger is missing");
		if (ledger.isEmpty()) return new FluidDomainContracts.ExactChemicalTransfer(true, ledger);
		var inputs = new ArrayList<Input>(); var chemistry = domain.chemistry.registry();
		for (var group : ledger.groups()) {
			if (externalSource && group.massUnits() <= 0) throw new IllegalArgumentException("External liquid source requires actual positive mass");
			int material = domain.materialRegistry.handle(group.materialId());
			var mixture = ChemicalMixtureRecord.fromStableAmounts(chemistry, group.speciesQuanta(), group.massResidualUnits());
			if (mixture.massUnits(chemistry) != group.massUnits()) throw new IllegalArgumentException("Chemical source mass differs from its species and residual");
			domain.chemistry.requireCompatible(mixture, group.materialId());
			inputs.add(new Input(material, SignedDyadic.of(group.normalizedVolume()), group.massUnits(), mixture));
		}
		boolean inserted = insert(inputs, domain.domainFlowFields.editableNearbyCells(x, y, z, radius), externalSource, energy);
		return new FluidDomainContracts.ExactChemicalTransfer(inserted, inserted ? ledger : ChemicalTransferLedger.EMPTY, inserted ? suppliedEnergy(inputs, energy) : 0);
	}
	FluidDomainContracts.VolumeAddition insertVolumeAtMost(int x, int y, int z, float amount, int radius) {
		domain.domainFlowFields.validateVolumeEdit(amount, radius);
		if (amount <= 0) return new FluidDomainContracts.VolumeAddition(0, false);
		var nearby = domain.domainFlowFields.editableNearbyCells(x, y, z, radius); var room = SignedDyadic.ZERO;
		if(domain.gasState.spatial!=null)room=SignedDyadic.of(amount);
		else for (var candidate : nearby) room = room.add(available(candidate.index()));
		var requested = SignedDyadic.of(amount); var accepted = minimum(room, requested);
		float represented = (float) accepted.toDouble();
		if (SignedDyadic.of(represented).compareTo(accepted) > 0) represented = Math.nextDown(represented);
		if (!(represented > 0)) return new FluidDomainContracts.VolumeAddition(0, false);
		var volume = SignedDyadic.of(represented);
		double density = domain.materialRegistry == null ? domain.definitions().material().entry(BuiltinFluidMaterials.WATER).material().referenceDensity() : domain.materialRegistry.material(0).referenceDensity();
		long mass = domain.unitScale.massUnitsForNormalized(volume.toDouble() * density);
		if (mass == 0) return new FluidDomainContracts.VolumeAddition(0, false);
		boolean inserted = insert(List.of(defaultInput(0, volume, mass)), nearby, true, null);
		return new FluidDomainContracts.VolumeAddition(inserted ? represented : 0, false);
	}
	private Input defaultInput(int material, SignedDyadic volume, long mass) {
		if (mass < 0) throw new IllegalArgumentException("Liquid packet mass must be non-negative");
		var mixture = domain.chemistry == null ? null : domain.chemistry.defaultRecord(
				materialId(material), volume.toDouble(), domain.config.cellSize(), mass);
		return new Input(material, volume, mass, mixture);
	}
	private boolean insert(List<Input> inputs, List<FluidDomainContracts.CellCandidate> nearby, boolean externalSource, Double energy) {
		if (domain.activityGroups.pendingInGroups(nearby)) return false;
		domain.domainFlowFields.ensureInventoryGrid();
		var requested = SignedDyadic.ZERO; long mass = domain.liquidCells.massUnits();
		for (var input : inputs) { requested = requested.add(input.volume()); mass = Math.addExact(mass, input.mass()); }
		if(domain.gasState.spatial==null && !domain.domainCollision.canAddCarrierVolume(requested.toDouble()))return false;
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
					placements.add(new Placement(new LiquidCellInventory.Key(cell, inputs.get(input).material(), support), take, input));
					rooms.put(identity, free.subtract(take)); remaining = remaining.subtract(take);
				}
			}
			if(remaining.signum()>0){
				if(domain.gasState.spatial==null||nearby.isEmpty()||nearby.getFirst().distanceSquared()!=0)return false;
				int cell=nearby.getFirst().index();
				if(!(domain.domainCollision.effectiveOpenCellVolume(cell)>0))return false;
				int support=~domain.geometry.cutCells.mask(cell)&255;
				placements.add(new Placement(new LiquidCellInventory.Key(cell,inputs.get(input).material(),support),remaining,input));
			}
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
		var nearby = domain.domainFlowFields.editableNearbyCells(x, y, z, radius); var available = SignedDyadic.ZERO;
		for (var cell : nearby) available = available.add(domain.liquidCells.exactCellVolume(cell.index()));
		var extraction = extract(nearby, minimum(available, SignedDyadic.of(amount)), -1, false);
		if (extraction == null) return 0;
		prepare(extraction.inventory(), ChemicalTransferLedger.EMPTY, extraction.ledger(), SignedDyadic.ZERO, SignedDyadic.of(extraction.energy())).commit();
		return (float) extraction.volume().toDouble();
	}
	FluidDomainContracts.ExactMixtureTransfer extractMixture(int x, int y, int z, float amount, int radius, int material) {
		domain.domainFlowFields.validateVolumeEdit(amount, radius);
		var extraction = extract(domain.domainFlowFields.editableNearbyCells(x, y, z, radius), SignedDyadic.of(amount), material, true);
		if (extraction == null) return new FluidDomainContracts.ExactMixtureTransfer(false, List.of());
		prepare(extraction.inventory(), ChemicalTransferLedger.EMPTY, extraction.ledger(), SignedDyadic.ZERO, SignedDyadic.of(extraction.energy())).commit();
		return new FluidDomainContracts.ExactMixtureTransfer(true, extraction.portions(), extraction.energy());
	}
	FluidDomainContracts.ExactChemicalTransfer extractChemical(int x, int y, int z, float amount, int radius,
	                                                java.util.function.Predicate<ChemicalTransferLedger> accepts) {
		if (domain.chemistry == null) throw new IllegalStateException("Chemical extraction requires chemistry");
		if (accepts == null) throw new IllegalArgumentException("Chemical extraction validator is missing");
		domain.domainFlowFields.validateVolumeEdit(amount, radius);
		var extraction = extract(domain.domainFlowFields.editableNearbyCells(x, y, z, radius), SignedDyadic.of(amount), -1, false);
		if (extraction == null || extraction.ledger().groups().stream().anyMatch(group -> group.massUnits() == 0)
				|| !accepts.test(extraction.ledger())) return new FluidDomainContracts.ExactChemicalTransfer(false, ChemicalTransferLedger.EMPTY);
		prepare(extraction.inventory(), ChemicalTransferLedger.EMPTY, extraction.ledger(), SignedDyadic.ZERO, SignedDyadic.of(extraction.energy())).commit();
		return new FluidDomainContracts.ExactChemicalTransfer(true, extraction.ledger(), extraction.energy());
	}
	FluidDomainContracts.ContactWaterTransfer extractContactWater(List<FluidDomainContracts.WaterContactFace> faces, long requestedNanos) {
		if (requestedNanos <= 0) throw new IllegalArgumentException("Water contact extraction requires positive nanos");
		var contacts = WaterContactGeometry.capture(domain, faces);
		var keys = new java.util.HashSet<LiquidCellInventory.Key>();
		var cells = new java.util.TreeSet<Integer>();
		for (var contact : contacts) {
			keys.add(new LiquidCellInventory.Key(contact.cell(), contact.materialHandle(), contact.supportMask()));
			cells.add(contact.cell());
		}
		if (keys.isEmpty()) return FluidDomainContracts.ContactWaterTransfer.EMPTY;
		double cubicMetres = requestedNanos / 1_000_000_000.0;
		double normalized = domain.unitScale.normalizedVolume(cubicMetres);
		while (domain.unitScale.cubicMeters(normalized) > cubicMetres) normalized = Math.nextDown(normalized);
		var nearby = cells.stream().map(cell -> new FluidDomainContracts.CellCandidate(cell, 0)).toList();
		var extraction = extract(nearby, SignedDyadic.of(normalized), -1, false, stock -> keys.contains(stock.key()));
		if (extraction == null || extraction.portions().isEmpty()) return FluidDomainContracts.ContactWaterTransfer.EMPTY;
		long actualNanos = Math.round(domain.unitScale.cubicMeters(extraction.volume().toDouble()) * 1_000_000_000.0);
		if (actualNanos != requestedNanos) return FluidDomainContracts.ContactWaterTransfer.EMPTY;
		prepare(extraction.inventory(), ChemicalTransferLedger.EMPTY, extraction.ledger(),
				SignedDyadic.ZERO, SignedDyadic.of(extraction.energy())).commit();
		return new FluidDomainContracts.ContactWaterTransfer(true, actualNanos, extraction.portions(), extraction.ledger(), extraction.energy());
	}

	/** Whole source blocks can represent reference water at rest; all other stocks stay in MMM. */
	boolean returnNativeWaterCells(List<Integer> cells) {
		if (cells.isEmpty() || domain.hasPendingPhaseMotionWork()) return false;
		domain.domainFlowFields.ensureVelocityGrid();
		long[] solids = domain.occupiedSolidSvq();
		var actualEnergy = SignedDyadic.ZERO; var referenceEnergy = SignedDyadic.ZERO;
		for (int cell : cells) {
			if (cell < 0 || cell >= domain.cellCount || solids[cell] != 0 || domain.geometry.cutCells.mask(cell) != 0) return false;
			var stocks = domain.liquidCells.stocksInCell(cell);
			if (stocks.size() != 1) return false;
			var stock = stocks.getFirst();
			if (!materialId(stock.key().material()).equals("mmm:water") || !stock.volume().equals(SignedDyadic.of(1.0))
					|| stock.massUnits() != domain.unitScale.massUnitsForNormalized(domain.definitions().material().entry(BuiltinFluidMaterials.WATER).material().referenceDensity())) return false;
			var amounts = stable(stock.mixture());
			if (stock.mixture() != null && (amounts.size() != 1 || !amounts.containsKey("mmm:h2o"))) return false;
			if (domain.thermalState != null) {
				if (domain.thermalState.coherentPhases().snapshot().stream().anyMatch(group -> group.cell() == cell)) return false;
				double reference = modeledEnergy(stock.key().material(), stock.massUnits(), stock.mixture(), 293.15);
				actualEnergy = actualEnergy.add(SignedDyadic.of(stock.internalEnergy()));
				referenceEnergy = referenceEnergy.add(SignedDyadic.of(reference));
			} else if (stock.internalEnergy() != 0) return false;
			double[][] momentum={domain.mac.momentumX,domain.mac.momentumY,domain.mac.momentumZ};
			for(int axis=0;axis<3;axis++)for(int side=0;side<2;side++)
				if(momentum[axis][domain.grid.face(cell,2*axis+side)-domain.grid.faceOffset(axis)]!=0)return false;
		}
		if (!actualEnergy.equals(referenceEnergy)) return false;
		var nearby = cells.stream().distinct().map(cell -> new FluidDomainContracts.CellCandidate(cell, 0)).toList();
		var extraction = extract(nearby, SignedDyadic.of((double) nearby.size()), -1, false);
		if (extraction == null) return false;
		prepare(extraction.inventory(), ChemicalTransferLedger.EMPTY, extraction.ledger(), SignedDyadic.ZERO,
				SignedDyadic.of(extraction.energy())).commit();
		for (int cell : cells) domain.setSolid(domain.grid.x(cell),domain.grid.y(cell),domain.grid.z(cell),true);
		return true;
	}

	/** The same eligible stocks that an actual container extraction can remove. */
	double extractableVolume(int x, int y, int z, int radius, int material) {
		var nearby = domain.domainFlowFields.editableNearbyCells(x, y, z, radius);
		if (domain.activityGroups.pendingInGroups(nearby)) return 0.0;
		var total = SignedDyadic.ZERO;
		for (var cell : nearby)
			for (var stock : domain.liquidCells.stocksInCell(cell.index()))
				if (eligibleForExtraction(stock, material, material >= 0)) total = total.add(stock.volume());
		return total.toDouble();
	}

	private boolean eligibleForExtraction(LiquidCellInventory.Stock stock, int material, boolean canonicalOnly) {
		return (material < 0 || stock.key().material() == material) && (!canonicalOnly || canonical(stock));
	}

	private Extraction extract(List<FluidDomainContracts.CellCandidate> nearby, SignedDyadic requested, int material, boolean canonical) {
		return extract(nearby, requested, material, canonical, stock -> true);
	}

	private Extraction extract(List<FluidDomainContracts.CellCandidate> nearby, SignedDyadic requested, int material,
	                           boolean canonical, java.util.function.Predicate<LiquidCellInventory.Stock> selected) {
		if (requested.signum() < 0 || domain.activityGroups.pendingInGroups(nearby)) return null;
		domain.domainFlowFields.ensureInventoryGrid();
		var remaining = requested; var edits = new ArrayList<LiquidCellInventory.Edit>(); var removed = new LinkedHashMap<Integer, Removed>();
		var removedEnergy = SignedDyadic.ZERO;
		for (var cell : nearby) if(domain.groupAvailable(cell.index())) for (var stock : domain.liquidCells.stocksInCell(cell.index())) {
			if (remaining.signum() == 0) break;
			if (!selected.test(stock) || !eligibleForExtraction(stock, material, canonical)) continue;
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
		var portions = new ArrayList<FluidDomainContracts.MaterialPortion>(); var groups = new ArrayList<ChemicalTransferLedger.Group>();
		for (var group : removed.values()) {
			if (canonical && group.mass == 0) return null;
			if (group.mass > 0) portions.add(new FluidDomainContracts.MaterialPortion(group.material, group.volume.toDouble(), group.mass));
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
			double density=domain.materialRegistry==null?domain.definitions().material().entry(BuiltinFluidMaterials.WATER).material().referenceDensity():domain.materialRegistry.material(material).referenceDensity();
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
		var edit = HostMixtureEdit.prepare(domain,original,domain.occupiedSolidSvq(),null,original.changedCells());
		var momentum = edit.momentum();
		var inventory = edit.inventory();
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
		var localGas=edit.gas();
		if(after!=null&&localGas!=null)after=localGas.applyAudit(after);
		if(after!=null)after.expectedInventory();
		return new Transaction(inventory, momentum, localGas, chemical, before, after);
	}
	private final class Transaction {
		final LiquidCellInventory.Plan inventory; final CellMacStage.ExternalMomentum momentum;
		final SpatialGasEdits.Plan localGas; final RegionChemistry.AuditExchange chemical; final ThermalAuditLedger before, after;
		Transaction(LiquidCellInventory.Plan inventory, CellMacStage.ExternalMomentum momentum,
		            SpatialGasEdits.Plan localGas, RegionChemistry.AuditExchange chemical, ThermalAuditLedger before, ThermalAuditLedger after) {
			this.inventory = inventory; this.momentum = momentum; this.localGas=localGas; this.chemical = chemical; this.before = before; this.after = after;
		}
		void commit() {
			inventory.requireCurrent();
			if (chemical != null) domain.chemistry.requireCurrentAuditExchange(chemical);
			if (before != null && domain.thermalState.auditLedger() != before) throw new IllegalStateException("Liquid external thermal audit changed after preparation");
			if(localGas!=null)localGas.requireCurrent(domain);
			domain.cellMacStage.publishMaterialChange(inventory,()->domain.cellMacStage.commitMomentum(momentum),()->{
                if(chemical!=null)domain.chemistry.commitAuditExchange(chemical);
                if(localGas!=null)localGas.commit(domain);
                if(after!=null)domain.thermalState.restoreAuditLedger(after);
            });
		}
	}
	private SignedDyadic available(int cell) { return SignedDyadic.of(domain.domainCollision.effectiveOpenCellVolume(cell)).subtract(domain.liquidCells.exactCellVolume(cell)); }
	private String materialId(int material) { return domain.materialRegistry == null ? BuiltinFluidMaterials.WATER : domain.materialRegistry.material(material).id(); }
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
