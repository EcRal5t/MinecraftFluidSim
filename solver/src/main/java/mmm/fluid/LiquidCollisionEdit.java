package mmm.fluid;

import mmm.inventory.LiquidCellInventory;

import mmm.chemistry.ChemicalMixtureRecord;
import mmm.chemistry.ChemicalTransferLedger;
import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** One prepared cell inventory and gas successor for an entire fixed collision refresh. */
final class LiquidCollisionEdit {
	final LiquidCellInventory.Plan inventory;
	final CellMacStage.ExternalMomentum momentum;
	final GasTopologyCoordinator.GasTopologyPlan gas;
	final FixedGeometrySnapshot fixedGeometry;
	final List<List<LiquidCellInventory.Stock>> displaced;
	final SignedDyadic removedEnergy, energyRoundoff;
	private LiquidCollisionEdit(LiquidCellInventory.Plan inventory, CellMacStage.ExternalMomentum momentum,
	                            GasTopologyCoordinator.GasTopologyPlan gas, FixedGeometrySnapshot fixedGeometry,
	                            List<List<LiquidCellInventory.Stock>> displaced) {
		this.inventory = inventory; this.momentum = momentum; this.gas = gas;
		this.fixedGeometry = fixedGeometry; this.displaced = List.copyOf(displaced);
		var observed = SignedDyadic.ZERO;
		for (var row : displaced) for (var stock : row) observed = observed.add(SignedDyadic.of(stock.internalEnergy()));
		this.removedEnergy = observed;
		energyRoundoff = inventory.energyChange().add(observed).subtract(inventory.mechanicalHeat());
	}

	static LiquidCollisionEdit prepare(FluidDomain domain, List<FluidDomain.CollisionEdit> edits,
	                                  FluidDomain.GranularCollisionPlan granular) {
		byte[] masks = domain.geometry.cutCells.copyMicroMasks();
		long[] occupied = domain.granularCoupling == null ? new long[domain.cellCount] : domain.granularCoupling.copySolidSvq();
		if (granular != null) {
			for (int i = 0; i < granular.selectedParcels().length; i++) if (granular.selectedParcels()[i]) {
				var parcel = domain.granularState.parcels.parcel(i);
				domain.granularCoupling.removePlannedParcel(occupied, domain.parcelPosition(parcel), parcel.solidVolumeSvq());
			}
			boolean[] cells = new boolean[domain.cellCount];
			for (var group : granular.depositGroups()) cells[group.cell()] = true;
			for (int cell = 0; cell < cells.length; cell++) if (cells[cell])
				occupied[cell] -= domain.granularState.deposits.cellBulkVolumeSvq(cell);
		}
		var candidate = domain.liquidCells.copy();
		var removed = new ArrayList<List<LiquidCellInventory.Stock>>();
		var changedKeys = new java.util.TreeSet<LiquidCellInventory.Key>();
		var eraseAll = new ArrayList<LiquidCellInventory.Edit>();
		var retainedAll = new ArrayList<LiquidCellInventory.Stock>();
		for (var edit : edits) {
			int cell = domain.domainFlowFields.cellIndex(edit.x(), edit.y(), edit.z()); masks[cell] = (byte) edit.mask();
			var source = domain.liquidCells.stocksInCell(cell);
			var components = LiquidCellInventory.connectedSupports(edit.mask());
			var capacities = CellRemapMicroTopology.capacities(edit.mask(), SignedDyadic.of(Integer.bitCount(~edit.mask() & 255), -3)
					.subtract(SignedDyadic.of(occupied[cell], -24)));
			var proposed = new SignedDyadic[source.size()][components.size()];
			for (int i = 0; i < source.size(); i++) {
				var stock = source.get(i); int mask = stock.key().supportMask();
				var weights = new double[components.size() + 1]; var bounds = new SignedDyadic[weights.length];
				Arrays.fill(bounds, stock.volume());
				for (int j = 0; j < components.size(); j++) weights[j] = Integer.bitCount(mask & components.get(j));
				weights[components.size()] = Integer.bitCount(mask & edit.mask());
				var shares = LiquidCellInventory.allocateVolumes(stock.volume(), bounds, weights);
				for (int j = 0; j < components.size(); j++) proposed[i][j] = shares[j];
			}
			// Joint component limits include the retained solid occupancy before any phase is partitioned.
			for (int j = 0; j < components.size(); j++) {
				var total = SignedDyadic.ZERO; var bounds = new SignedDyadic[source.size()]; var weights = new double[source.size()];
				for (int i = 0; i < source.size(); i++) { bounds[i] = proposed[i][j]; total = total.add(bounds[i]); weights[i] = bounds[i].toDouble(); }
				if (total.compareTo(capacities[j]) > 0) {
					var shares = LiquidCellInventory.allocateVolumes(capacities[j], bounds, weights);
					for (int i = 0; i < source.size(); i++) proposed[i][j] = shares[i];
				}
			}
			var erase = new ArrayList<LiquidCellInventory.Edit>(); var retained = new ArrayList<LiquidCellInventory.Stock>();
			var escaped = new ArrayList<LiquidCellInventory.Stock>();
			for (int i = 0; i < source.size(); i++) {
				var stock = source.get(i); erase.add(new LiquidCellInventory.Edit(stock.key(), null)); changedKeys.add(stock.key());
				var amounts = Arrays.copyOf(proposed[i], components.size() + 1); var kept = SignedDyadic.ZERO;
				for (int j = 0; j < components.size(); j++) kept = kept.add(amounts[j]);
				amounts[components.size()] = stock.volume().subtract(kept);
				long[] masses = LiquidCellInventory.allocateQuantities(stock.massUnits(), amounts);
				long[][] species = new long[stock.mixture() == null ? 0 : stock.mixture().entryCount()][];
				for (int entry = 0; entry < species.length; entry++) species[entry] = LiquidCellInventory.allocateSpecies(stock.mixture().amountAt(entry),masses,amounts);
				var mixtures=new ChemicalMixtureRecord[amounts.length];
				for (int j = 0; j < amounts.length; j++) {
					ChemicalMixtureRecord mixture = null;
					if (stock.mixture() != null) {
						var handles = new ArrayList<Integer>(); var quanta = new ArrayList<Long>();
						for (int entry = 0; entry < species.length; entry++) if (species[entry][j] > 0) {
							handles.add(stock.mixture().speciesHandle(entry)); quanta.add(species[entry][j]);
						}
						mixture = new ChemicalMixtureRecord(handles.stream().mapToInt(Integer::intValue).toArray(), quanta.stream().mapToLong(Long::longValue).toArray(), 0);
						mixture = mixture.withMassResidualUnits(Math.subtractExact(masses[j], mixture.speciesMassUnits(domain.liquidCells.chemicalRegistry())));
					}
					mixtures[j]=mixture;
				}
				var energies=LiquidCellInventory.partitionEnergy(stock,masses,mixtures,FluidThermalBinding.liquidEnergyPartitioner(domain));
				for (int j = 0; j < amounts.length; j++) if (amounts[j].signum() > 0) {
					var key = j < components.size() ? new LiquidCellInventory.Key(cell, stock.key().material(), components.get(j)) : stock.key();
					var part = new LiquidCellInventory.Stock(key, amounts[j], masses[j], mixtures[j], energies[j]);
					if (j < components.size()) { retained.add(part); changedKeys.add(key); }
					else escaped.add(part);
				}
			}
			eraseAll.addAll(erase); retainedAll.addAll(retained); removed.add(List.copyOf(escaped));
		}
		candidate.planEdits(eraseAll).commit(); candidate.planAdditions(retainedAll).commit();
		// Public displacement payloads are validated before any collision owner is published.
		var totalChemistry = domain.chemistry == null ? null : ChemicalTransferLedger.builder(domain.chemistry.registry());
		for (var row : removed) {
			var chemical = totalChemistry == null ? null : ChemicalTransferLedger.builder(domain.chemistry.registry());
			var material = domain.materialRegistry == null ? null : new FluidDomain.RemovedMaterialLedger(domain.materialRegistry.size());
			for (var stock : row) {
				if (chemical != null) chemical.add(domain.materialRegistry.material(stock.key().material()).id(), stock.volumeFraction(), stock.massUnits(), stock.mixture());
				if (material != null) material.add(stock.key().material(), stock.volumeFraction(), stock.massUnits());
			}
			if (chemical != null) totalChemistry.add(chemical.build());
			if (material != null) material.build();
		}
		if (totalChemistry != null) domain.chemistry.requireCanRecordEscapedSink(totalChemistry.build());
		var inventoryEdits = new ArrayList<LiquidCellInventory.Edit>();
		for (var key : changedKeys) inventoryEdits.add(new LiquidCellInventory.Edit(key, candidate.stock(key)));
		var plan = domain.liquidCells.planEdits(inventoryEdits);
		var momentum = domain.cellMacStage.prepareExternalMomentum(plan,masks);
		plan = plan.withMechanicalHeat(momentum.heatByCell());
		var gas = domain.gasTopologyCoordinator.planAfterCollisionCells(plan.future(), occupied, masks);
		return new LiquidCollisionEdit(plan, momentum, gas, gas.frame().geometry().actualCapacity().fixedGeometry, removed);
	}

	float report(int index, FluidDomain domain, ChemicalTransferLedger.Builder chemistry,
	             FluidDomain.RemovedMaterialLedger materials, FluidDomain.RemovedThermalEnergy energy) {
		double volume = 0;
		for (var stock : displaced.get(index)) {
			volume += stock.volumeFraction();
			if (chemistry != null) chemistry.add(domain.materialRegistry.material(stock.key().material()).id(), stock.volumeFraction(), stock.massUnits(), stock.mixture());
			if (materials != null) materials.add(stock.key().material(), stock.volumeFraction(), stock.massUnits());
			if (energy != null) energy.add(stock.internalEnergy());
		}
		return (float) volume;
	}
}
