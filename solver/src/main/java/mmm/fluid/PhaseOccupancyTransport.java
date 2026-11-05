package mmm.fluid;

import mmm.inventory.PhaseOccupancyState;

import mmm.inventory.LiquidCellInventory;

import mmm.units.SignedDyadic;

/** Persistent micro-VOF preparation, geometric phase flux and the common carrier transaction. */
final class PhaseOccupancyTransport {
	private PhaseOccupancyTransport() {
	}

	static void ensure(HostFluidState domain) {
		if (domain.phaseOccupancy != null && !domain.phaseOccupancyDirty) return;
		domain.domainFlowFields.ensureInventoryGrid();
		long[] occupied = domain.occupiedSolidSvq();
		var frame=domain.gasState.frame;
		if(frame!=null&&frame.matchesCarrierInventory(domain,occupied)){
			publish(domain,frame.occupancy());return;
		}
		double[] pore = poreVolumes(domain, occupied, null);
		var phase=new FlipPressurePhaseGeometry(domain.sizeX,domain.sizeY,domain.sizeZ);
		reconstruct(domain,domain.liquidCells,pore,null,phase);
		publish(domain,phase.occupancyState());
	}
	static void publish(HostFluidState domain,PhaseOccupancyState state){
		domain.phaseOccupancy=state;domain.phaseOccupancyDirty=false;
	}

	static void requireCurrentGeometry(HostFluidState domain, PhaseOccupancyState state) {
		if (state == null || state.cellCount() != domain.cellCount) throw new IllegalArgumentException("Phase occupation dimensions differ from domain");
		domain.domainFlowFields.ensureInventoryGrid();
		long[] occupied = domain.occupiedSolidSvq();
		requireGeometry(domain, state, occupied);
	}

	static void requireGeometry(HostFluidState domain, PhaseOccupancyState state, long[] occupied) {
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
				if(domain.gasState.spatial!=null){var gas=domain.gasState.spatial;expected*=DomainGasInventory.mixture(domain,cell,gas.cells().get(cell),gas.energyAt(cell),domain.liquidCells,occupied,null).liquidScale();}
				error += PhaseOccupancyState.VOLUME_RESOLUTION;
				if (Math.abs(amount - expected)> error) throw new IllegalArgumentException("Restored phase occupation differs from liquid stock at cell " + cell + " support " + support + ": occupation=" + amount + ", inventory=" + expected + ", tolerance=" + error);
			}
		}
	}

	/** Distributes actual fixed and solid pore capacity over the open microboxes. */
	static double[] poreVolumes(HostFluidState domain, long[] occupied, byte[] masks) {
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

	private static SignedDyadic componentLiquid(HostFluidState domain, int cell, int support) {
		var result = SignedDyadic.ZERO;
		for (var stock : domain.liquidCells.stocksInCell(cell)) if (stock.key().supportMask() == support) result = result.add(stock.volume());
		return result;
	}

	/** One coarse component PLIC supplies all eight micro views. */
	static double[] reconstruct(HostFluidState domain, LiquidCellInventory inventory, double[] pore, byte[] masks, FlipPressurePhaseGeometry workspace) {
		double[] liquid = new double[pore.length];
		long[] occupied=domain.gasState.spatial==null?null:domain.occupiedSolidSvq();
		for (int cell = 0; cell<domain.cellCount; cell++) {
			int mask = masks == null ? domain.geometry.cutCells.mask(cell) : Byte.toUnsignedInt(masks[cell]);
			double scale=1;
			if(domain.gasState.spatial!=null){var gas=domain.gasState.spatial;scale=DomainGasInventory.mixture(domain,cell,gas.cells().get(cell),gas.energyAt(cell),inventory,occupied,masks).liquidScale();}
			for (int support : CellRemapMicroTopology.components(mask)) {
				double capacity = 0, amount = 0;
				for (int m = 0; m<8; m++) if ((support & 1 << m) != 0) capacity += pore[8 * cell + m];
				for (var stock : inventory.stocksInCell(cell)) if (stock.key().supportMask() == support) amount += stock.volumeFraction()*scale;
				double fraction = capacity> 0 ? Math.clamp(amount / capacity, 0, 1) : 0;
				for (int m = 0; m<8; m++) if ((support & 1 << m) != 0) liquid[8 * cell + m] = fraction * pore[8 * cell + m];
			}
		}
		var cut = domain.geometry.cutCells;
		if (masks != null) {
			cut = new FlipCutCellGeometry(domain.sizeX, domain.sizeY, domain.sizeZ);
			cut.restoreMicroMasks(masks);
		}
		workspace.rebuildOccupancy(new PhaseOccupancyState(liquid), cut, pore,
				domain.gasState.frame==null?null:domain.gasState.frame.phase());
		// Persist the reconstruction input. The prepared frame and a cold restore must
		// derive the same PLIC, rather than invert its approximate integrated output again.
		return liquid;
	}
}
