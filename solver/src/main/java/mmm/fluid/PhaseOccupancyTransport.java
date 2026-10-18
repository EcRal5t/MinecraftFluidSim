package mmm.fluid;

import mmm.inventory.PhaseOccupancyState;

import mmm.inventory.LiquidCellInventory;

import mmm.fluid.air.GasInventory;

import java.util.ArrayList;

import java.util.List;

import java.util.HashMap;

import java.util.TreeMap;

import mmm.units.SignedDyadic;

/** Persistent micro-VOF preparation, geometric phase flux and the common carrier transaction. */
final class PhaseOccupancyTransport {
	private PhaseOccupancyTransport() {
	}

	static void ensure(FluidDomain domain) {
		if (domain.phaseOccupancy != null) return;
		domain.ensureInventoryGrid();
		long[] occupied = domain.granularCoupling == null ? new long[domain.cellCount] : domain.granularCoupling.copySolidSvq();
		double[] pore = poreVolumes(domain, occupied, null);
		double[] liquid = reconstruct(domain, domain.liquidCells, pore, null, new FlipPressurePhaseGeometry(domain.sizeX, domain.sizeY, domain.sizeZ));
		domain.phaseOccupancy = new PhaseOccupancyState(liquid);
	}

	static GasVolumeAllocation prepareEdit(FluidDomain domain, LiquidCellInventory future, long[] occupied, byte[] masks, List<GasInventory> effectiveInventories) {
		return prepareEdit(domain, future, occupied, masks, effectiveInventories, null);
	}

	/** Reconstructs external edits and their gas source shares before publication. */
	static GasVolumeAllocation prepareEdit(FluidDomain domain, LiquidCellInventory future, long[] occupied, byte[] masks, List<GasInventory> effectiveInventories, PhaseOccupancyState prescribed) {
		ensure(domain);
		double[] pore = poreVolumes(domain, occupied, masks);
		var cut = domain.geometry.cutCells;
		if (masks != null) {
			cut = new FlipCutCellGeometry(domain.sizeX, domain.sizeY, domain.sizeZ);
			cut.restoreMicroMasks(masks);
		}
		var rebuilt = new FlipPressurePhaseGeometry(domain.sizeX, domain.sizeY, domain.sizeZ);
		double[] liquid = reconstruct(domain, future, pore, masks, rebuilt);
		var state = new PhaseOccupancyState(liquid);
		var shares = new ArrayList<GasVolumeAllocation.Share>();
		FlipPressurePhaseGeometry phase = null;
		GasComplementGeometry geometry = null;
		if (domain.gasState.frame != null) {
			phase = rebuilt;
			phase.setExactPoreVolumes(cut, occupied);
			boolean[] solid = new boolean[domain.cellCount];
			for (int cell = 0; cell<solid.length; cell++) solid[cell] = cut.fullySolid(cell);
			geometry = GasComplementGeometry.capture(domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize(), solid, cut, phase, null, null, future.snapshot());
			int[] parent = new int[8 * domain.cellCount];
			for (int p = 0; p<parent.length; p++) parent[p] = p;
			for(int cell=0;cell<domain.cellCount;cell++)for(int support:CellRemapMicroTopology.components(cut.mask(cell))){
				int first=-1;
				for(int bits=support;bits!=0;bits&=bits-1){int p=8*cell+Integer.numberOfTrailingZeros(bits);if(geometry.piece(p)==null)continue;if(first<0)first=p;else union(parent,first,p);}
			}
			for (var port : geometry.ports()) if (port.fullCutConnection()) union(parent, port.first(), port.second());
			var sources = new HashMap<Integer, TreeMap<Integer, Double >> ();
			for (var piece : geometry.pieces()) {
				int previous = domain.gasState.frame.nodeOfPiece(piece.id());
				if (previous >= 0 && domain.gasState.frame.geometry().piece(piece.id()) != null) sources.computeIfAbsent(root(parent, piece.id()), ignored -> new TreeMap<>()).merge(previous, piece.normalizedVolume(), Double::sum);
			}
			if (!domain.gasState.sealedDomainBoundary) for (var port : geometry.exteriorPorts()) sources.computeIfAbsent(root(parent, port.piece()), ignored -> new TreeMap<>()).putIfAbsent(0, 1.0);
			for (var piece : geometry.pieces()) {
				double amount = pore[piece.id()] - liquid[piece.id()];
				if (amount <= 0) continue;
				var origins = sources.get(root(parent, piece.id()));
				if (origins == null || origins.isEmpty()) shares.add(new GasVolumeAllocation.Share( - 1, piece.id(), amount, GasVolumeAllocation.SourceKind.EXPLICIT_VACUUM));
				else {
					double total = origins.values().stream().mapToDouble(Double::doubleValue).sum();
					for (var source : origins.entrySet()) {
						double part = amount * (source.getValue() / total);
						if (part> 0) shares.add(new GasVolumeAllocation.Share(source.getKey(), piece.id(), part, source.getKey() == 0 ? GasVolumeAllocation.SourceKind.AMBIENT : GasVolumeAllocation.SourceKind.EXISTING));
					}
				}
			}
		}
		return new GasVolumeAllocation(domain, future, state, shares, phase, geometry);
	}

	private static int root(int[] parent, int node) {
		while (parent[node] != node) {
			parent[node] = parent[parent[node]];
			node = parent[node];
		}
		return node;
	}

	private static void union(int[] parent, int first, int second) {
		int a = root(parent, first), b = root(parent, second);
		if (a != b) parent[a] = b;
	}

	static void requireCurrentGeometry(FluidDomain domain, PhaseOccupancyState state) {
		if (state == null || state.cellCount() != domain.cellCount) throw new IllegalArgumentException("Phase occupation dimensions differ from domain");
		domain.ensureInventoryGrid();
		long[] occupied = domain.granularCoupling == null ? new long[domain.cellCount] : domain.granularCoupling.copySolidSvq();
		requireGeometry(domain, state, occupied);
	}

	static void requireGeometry(FluidDomain domain, PhaseOccupancyState state, long[] occupied) {
		if (state == null || state.cellCount() != domain.cellCount) throw new IllegalArgumentException("Phase occupation dimensions differ from domain");
		double[] pore = poreVolumes(domain, occupied, null);
		for (int cell = 0; cell<domain.cellCount; cell++) {
			int mask = domain.geometry.cutCells.mask(cell);
			for (int micro = 0; micro<8; micro++) {
				double volume = state.liquidVolume(8 * cell + micro), capacity = pore[8 * cell + micro];
				if ((mask & 1 << micro) != 0 && volume != 0 || volume> capacity + PhaseOccupancyState.VOLUME_RESOLUTION) throw new IllegalArgumentException("Restored phase occupation exceeds actual micro pore at piece " + (8 * cell + micro));
			}
			for (int support : CellRemapMicroTopology.components(mask)) {
				double amount = 0, error = 0, capacity = 0;
				for (int micro = 0; micro<8; micro++) if ((support & 1 << micro) != 0) {
					double volume = state.liquidVolume(8 * cell + micro);
					amount += volume;
					error += Math.ulp(volume) + Math.ulp(amount);
					capacity += pore[8 * cell + micro];
				}
				double expected = componentLiquid(domain, cell, support).toDouble();
				error += PhaseOccupancyState.VOLUME_RESOLUTION;
				if (Math.abs(amount - expected)> error) throw new IllegalArgumentException("Restored phase occupation differs from liquid stock at cell " + cell + " support " + support);
			}
		}
	}

	/** Distributes actual fixed and solid pore capacity over the open microboxes. */
	static double[] poreVolumes(FluidDomain domain, long[] occupied, byte[] masks) {
		if (occupied == null || occupied.length != domain.cellCount || masks != null && masks.length != domain.cellCount) throw new IllegalArgumentException("Micro pore columns differ from domain");
		var result = new double[8 * domain.cellCount];
		for (int cell = 0; cell<domain.cellCount; cell++) {
			int mask = masks == null ? domain.geometry.cutCells.mask(cell) : Byte.toUnsignedInt(masks[cell]);
			if (occupied[cell] == 0) {
				for (int micro = 0; micro<8; micro++) if ((mask & 1 << micro) == 0) result[8 * cell + micro] = .125;
				continue;
			}
			var caps = CellRemapMicroTopology.capacities(mask, SignedDyadic.of(Integer.bitCount(~mask & 255), - 3).subtract(SignedDyadic.of(occupied[cell], - 24)));
			int j = 0;
			for (int support : CellRemapMicroTopology.components(mask)) {
				double volume = caps[j++].toDouble() / Integer.bitCount(support);
				for (int micro = 0; micro<8; micro++) if ((support & 1 << micro) != 0) result[8 * cell + micro] = volume;
			}
		}
		return result;
	}

	private static SignedDyadic componentLiquid(FluidDomain domain, int cell, int support) {
		var result = SignedDyadic.ZERO;
		for (var stock : domain.liquidCells.stocksInCell(cell)) if (stock.key().supportMask() == support) result = result.add(stock.volume());
		return result;
	}

	/** One coarse component PLIC supplies all eight micro views. */
	static double[] reconstruct(FluidDomain domain, LiquidCellInventory inventory, double[] pore, byte[] masks, FlipPressurePhaseGeometry workspace) {
		double[] liquid = new double[pore.length];
		for (int cell = 0; cell<domain.cellCount; cell++) {
			int mask = masks == null ? domain.geometry.cutCells.mask(cell) : Byte.toUnsignedInt(masks[cell]);
			for (int support : CellRemapMicroTopology.components(mask)) {
				double capacity = 0, amount = 0;
				for (int m = 0; m<8; m++) if ((support & 1 << m) != 0) capacity += pore[8 * cell + m];
				for (var stock : inventory.stocksInCell(cell)) if (stock.key().supportMask() == support) amount += stock.volumeFraction();
				double fraction = capacity> 0 ? Math.clamp(amount / capacity, 0, 1) : 0;
				for (int m = 0; m<8; m++) if ((support & 1 << m) != 0) liquid[8 * cell + m] = fraction * pore[8 * cell + m];
			}
		}
		var cut = domain.geometry.cutCells;
		if (masks != null) {
			cut = new FlipCutCellGeometry(domain.sizeX, domain.sizeY, domain.sizeZ);
			cut.restoreMicroMasks(masks);
		}
		workspace.rebuildOccupancy(new PhaseOccupancyState(liquid), cut, pore);
		workspace.copyLiquidMicroVolumes(liquid);
		return liquid;
	}
}
