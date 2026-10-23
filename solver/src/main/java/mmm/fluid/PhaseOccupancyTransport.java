package mmm.fluid;

import mmm.inventory.PhaseOccupancyState;

import mmm.inventory.LiquidCellInventory;

import mmm.units.SignedDyadic;

/** Persistent micro-VOF preparation, geometric phase flux and the common carrier transaction. */
final class PhaseOccupancyTransport {
	private PhaseOccupancyTransport() {
	}

	static void ensure(FluidDomain domain) {
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
	static void publish(FluidDomain domain,PhaseOccupancyState state){
		domain.phaseOccupancy=state;domain.phaseOccupancyDirty=false;
	}

	/** Reconstructs external edits and their gas source shares before publication. */
	static GasVolumeAllocation prepareEdit(FluidDomain domain, LiquidCellInventory future, long[] occupied, byte[] masks) {
		ensure(domain);
		var cut=domain.geometry.cutCells;
		if(masks!=null){cut=new FlipCutCellGeometry(domain.sizeX,domain.sizeY,domain.sizeZ);cut.restoreMicroMasks(masks);}
		var old=domain.gasState.frame;
		var capacity=GasComponentCapacity.prepare(cut,occupied,future.snapshot(),old==null?null:old.geometry().actualCapacity());
		FlipPressurePhaseGeometry rebuilt;
		if(old!=null&&capacity==old.geometry().actualCapacity())rebuilt=old.phase();
		else{
			rebuilt=new FlipPressurePhaseGeometry(domain.sizeX,domain.sizeY,domain.sizeZ);
			rebuilt.rebuildInventory(cut,future.snapshot(),capacity,old==null?null:old.phase());
		}
		var prepared=GasRuntimeGeometry.captureCellFuture(domain,future,occupied,domain.gasState.sealedDomainBoundary,
				masks,rebuilt.occupancyState(),rebuilt,null,capacity);
		var sources=new PhaseSourceColumns(prepared.nodeCount()+1);
		if(domain.gasState.frame!=null) {
			var geometry=prepared.geometry();var topology=prepared.topology();
			var components=geometry.actualCapacity().components;
			double[] targetVolumes=new double[prepared.nodeCount()+1];
			for(int i=0;i<components.size();i++) {
				var component=components.get(i);
				if(component.availableVolumeNormalized().signum()==0)continue;
				int target=topology.nodeOfComponent(component.cell(),component.supportMask());
				if(target<0)throw new GasTopologyBlockedException("Edited gas component has no dry representation");
				targetVolumes[target]+=component.availableVolumeNormalized().toDouble();
				// A coarse pore retains its gas even when its rebuilt plane moves every
				// dry micro piece. Source identity belongs to the whole fixed support.
				for(int support:CellRemapMicroTopology.components(old.geometry().collisionMask(component.cell()))) {
					var previousComponent=old.geometry().component(component.cell(),support);if(previousComponent==null)continue;
					int overlap=previousComponent.dryMicroMask()&component.supportMask();if(overlap==0)continue;
					double amount=0;
					if(overlap==previousComponent.dryMicroMask())amount=previousComponent.normalizedVolume();
					else for(int bits=overlap;bits!=0;bits&=bits-1)
						amount+=old.geometry().pieceNormalizedVolume(8*component.cell()+Integer.numberOfTrailingZeros(bits));
					int previous=old.topology().nodeOfComponent(component.cell(),support);
					if(previous>=0)sources.add(target,previous,amount);
				}
			}
			// Coarse supports in one future chamber share its source proportions.
			for(int target=0;target<targetVolumes.length;target++)if(targetVolumes[target]>0){
				if(sources.first(target)<0)sources.appendUnique(target,target==0?0:-1,targetVolumes[target]);
				else sources.scale(target,targetVolumes[target]);
			}
		}
		return GasVolumeAllocation.fromTargetSources(domain,future,prepared,sources);
	}

	static void requireCurrentGeometry(FluidDomain domain, PhaseOccupancyState state) {
		if (state == null || state.cellCount() != domain.cellCount) throw new IllegalArgumentException("Phase occupation dimensions differ from domain");
		domain.domainFlowFields.ensureInventoryGrid();
		long[] occupied = domain.occupiedSolidSvq();
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
		workspace.rebuildOccupancy(new PhaseOccupancyState(liquid), cut, pore,
				domain.gasState.frame==null?null:domain.gasState.frame.phase());
		// Persist the reconstruction input. The prepared frame and a cold restore must
		// derive the same PLIC, rather than invert its approximate integrated output again.
		return liquid;
	}
}
