package mmm.fluid;

import mmm.inventory.LiquidCellInventory;
import mmm.inventory.LiquidTransportRoutes;

import mmm.chemistry.ChemicalMixtureRecord;
import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** One prepared cell inventory and gas successor for an entire fixed collision refresh. */
final class LiquidCollisionEdit {
	final LiquidCellInventory.Plan inventory;
	final CellMacStage.ExternalMomentum momentum;
	final SpatialGasEdits.Plan localGas;
	final FixedGeometrySnapshot fixedGeometry;
	final List<List<LiquidCellInventory.Stock>> displaced;
	final SignedDyadic energyRoundoff;
	private LiquidCollisionEdit(LiquidCellInventory.Plan inventory, CellMacStage.ExternalMomentum momentum,
	                            SpatialGasEdits.Plan localGas, FixedGeometrySnapshot fixedGeometry,
	                            List<List<LiquidCellInventory.Stock>> displaced) {
		this.inventory = inventory; this.momentum = momentum; this.localGas=localGas;
		this.fixedGeometry = fixedGeometry; this.displaced = List.copyOf(displaced);
		energyRoundoff = inventory.energyChange().subtract(inventory.mechanicalHeat());
	}

	static LiquidCollisionEdit prepare(HostFluidState domain, List<FluidDomainContracts.CollisionEdit> edits,
	                                  FluidDomainContracts.GranularCollisionPlan granular) {
		byte[] masks = domain.geometry.cutCells.copyMicroMasks();
		long[] occupied = domain.occupiedSolidSvq();
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
		for (var edit : edits) masks[domain.domainFlowFields.cellIndex(edit.x(), edit.y(), edit.z())] = (byte) edit.mask();
		for (var edit : edits) {
			int cell = domain.domainFlowFields.cellIndex(edit.x(), edit.y(), edit.z());
			var source = domain.liquidCells.stocksInCell(cell);
			var components = LiquidCellInventory.connectedSupports(edit.mask());
			var capacities = CellRemapMicroTopology.capacities(edit.mask(), SignedDyadic.of(Integer.bitCount(~edit.mask() & 255), -3)
					.subtract(SignedDyadic.of(occupied[cell], -24)));
			var proposed = new SignedDyadic[source.size()][components.size()];
			for (int i = 0; i < source.size(); i++) {
				if (!components.isEmpty()) proposed[i][0] = source.get(i).volume();
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
		routeDisplaced(domain, candidate, masks, occupied, removed, changedKeys);
		var inventoryEdits = new ArrayList<LiquidCellInventory.Edit>();
		for (var key : changedKeys) inventoryEdits.add(new LiquidCellInventory.Edit(key, candidate.stock(key)));
		var plan = domain.liquidCells.planEdits(inventoryEdits);
		var changed=new java.util.TreeSet<Integer>(plan.changedCells());
		for(var edit:edits)changed.add(domain.domainFlowFields.cellIndex(edit.x(),edit.y(),edit.z()));
		long[] beforeOccupied=domain.occupiedSolidSvq();for(int cell=0;cell<domain.cellCount;cell++)if(beforeOccupied[cell]!=occupied[cell])changed.add(cell);
		var edit=HostMixtureEdit.prepare(domain,plan,occupied,masks,List.copyOf(changed));
		plan=edit.inventory();var momentum=edit.momentum();var localGas=edit.gas();
		var fixed=FixedGeometrySnapshot.fromMasks(domain.grid,masks);
		return new LiquidCollisionEdit(plan, momentum, localGas, fixed, removed);
	}

	private static void routeDisplaced(HostFluidState domain, LiquidCellInventory candidate, byte[] masks,
	                                   long[] occupied, List<List<LiquidCellInventory.Stock>> displaced,
	                                   java.util.Set<LiquidCellInventory.Key> changedKeys) {
		var sources = displaced.stream().flatMap(List::stream).toList();
		if (sources.isEmpty()) return;
		var transit = new LiquidCellInventory(domain.cellCount, domain.liquidCells.chemicalRegistry());
		transit.planAdditions(sources).commit();
		var routes = LiquidTransportRoutes.builder(sources.size(), sources.size());
		var rooms = new java.util.HashMap<Long, SignedDyadic>();
		for (var source : transit.stocks()) {
			int start = source.key().cell();
			var remaining = source.volume();
			int[] firstFace = new int[domain.cellCount]; Arrays.fill(firstFace, -1);
			boolean[] seen = new boolean[domain.cellCount]; seen[start] = true;
			var queue = new java.util.ArrayDeque<Integer>(); queue.add(start);
			routes.source(source);
			while (!queue.isEmpty() && remaining.signum() > 0) {
				int cell = queue.removeFirst();
				if (cell != start && domain.groupAvailable(cell)) {
					int mask = Byte.toUnsignedInt(masks[cell]);
					var supports = LiquidCellInventory.connectedSupports(mask);
					var capacity = CellRemapMicroTopology.capacities(mask, SignedDyadic.of(Integer.bitCount(~mask & 255), -3)
							.subtract(SignedDyadic.of(occupied[cell], -24)));
					for (int i = 0; i < supports.size() && remaining.signum() > 0; i++) {
						int support = supports.get(i); long key = (long) cell << 8 | support;
						var room = rooms.get(key);
						if (room == null) {
							room = capacity[i];
							for (var stock : candidate.stocksInCell(cell)) if (stock.key().supportMask() == support) room = room.subtract(stock.volume());
						}
						var amount = room.compareTo(remaining) < 0 ? room : remaining;
						if (amount.signum() > 0) {
							routes.route(firstFace[cell], cell, support, amount);
							changedKeys.add(new LiquidCellInventory.Key(cell, source.key().material(), support));
							remaining = remaining.subtract(amount); room = room.subtract(amount);
						}
						rooms.put(key, room);
					}
				}
				int x = domain.grid.x(cell), y = domain.grid.y(cell), z = domain.grid.z(cell);
				for (int side = 0; side < 6; side++) {
					int next = domain.grid.neighbor(cell, side);
					if (next < 0 || seen[next] || !domain.groupAvailable(next)) continue;
					int dx = domain.grid.x(next)-x, dy = domain.grid.y(next)-y, dz = domain.grid.z(next)-z;
					if (domain.domainFlowFields.pressureOpenFaceFromCell(cell, next, dx, dy, dz) <= 0) continue;
					seen[next] = true;
					firstFace[next] = cell == start ? domain.grid.face(cell, side) : firstFace[cell];
					queue.addLast(next);
				}
			}
			if (remaining.signum() != 0)
				throw new FluidDomainContracts.CollisionEditBlockedException("Collision displacement has no reachable capacity", null);
		}
		var routed = transit.planRoutes(routes.build(), FluidThermalBinding.liquidEnergyPartitioner(domain)).future();
		candidate.planAdditions(routed.stocks()).commit();
	}

	float report(int index) {
		double volume = 0;
		for (var stock : displaced.get(index)) {
			volume += stock.volumeFraction();
		}
		return (float) volume;
	}
}
