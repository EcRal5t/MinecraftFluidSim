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
		if (domain.phaseOccupancy != null && !domain.phaseOccupancyDirty) return;
		domain.domainFlowFields.ensureInventoryGrid();
		long[] occupied = domain.granularCoupling == null ? new long[domain.cellCount] : domain.granularCoupling.copySolidSvq();
		var frame=domain.gasState.frame;
		if(frame!=null&&frame.matchesCarrierInventory(domain,occupied)){
			publish(domain,frame.occupancy());return;
		}
		double[] pore = poreVolumes(domain, occupied, null);
		double[] liquid = reconstruct(domain, domain.liquidCells, pore, null, new FlipPressurePhaseGeometry(domain.sizeX, domain.sizeY, domain.sizeZ));
		publish(domain,new PhaseOccupancyState(liquid));
	}
	static void publish(FluidDomain domain,PhaseOccupancyState state){
		domain.phaseOccupancy=state;domain.phaseOccupancyDirty=false;
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
			var sourceFrame=domain.gasState.frame;boolean[] changed=new boolean[domain.cellCount];
			for(int cell=0;cell<changed.length;cell++)changed[cell]=!phase.sameCellGeometry(cell,sourceFrame.phase());
			geometry = GasComplementGeometry.capture(domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize(), solid, cut, phase,
					sourceFrame.geometry(), changed, future.snapshot());
			var topology=GasChamberTopology.capture(geometry,domain.gasState.sealedDomainBoundary);
			var components=geometry.actualCapacity().components;
			var sources=new HashMap<Integer,TreeMap<Integer,Double>>();
			int[] representative=new int[components.size()],owner=new int[components.size()];
			java.util.Arrays.fill(representative,-1);
			var old=domain.gasState.frame;
			for(int i=0;i<components.size();i++) {
				var component=components.get(i);
				if(component.availableVolumeNormalized().signum()==0)continue;
				for(int bits=component.supportMask();bits!=0;bits&=bits-1) {
					int piece=8*component.cell()+Integer.numberOfTrailingZeros(bits);
					if(geometry.piece(piece)!=null){representative[i]=piece;owner[i]=topology.nodeOfPiece(piece);break;}
				}
				if(representative[i]<0)throw new GasTopologyBlockedException("Edited gas component has no dry representation");
				var origins=sources.computeIfAbsent(owner[i],ignored->new TreeMap<>());
				// A coarse pore retains its gas even when its rebuilt plane moves every
				// dry micro piece. Source identity belongs to the whole fixed support.
				for(int bits=component.supportMask();bits!=0;bits&=bits-1) {
					int piece=8*component.cell()+Integer.numberOfTrailingZeros(bits),previous=old.nodeOfPiece(piece);
					var oldPiece=old.geometry().piece(piece);
					if(previous>=0&&oldPiece!=null)origins.merge(previous,oldPiece.normalizedVolume(),Double::sum);
				}
			}
			for(int i=0;i<components.size();i++)if(representative[i]>=0) {
				double amount=components.get(i).availableVolumeNormalized().toDouble();
				var origins=sources.get(owner[i]);
				if(origins.isEmpty()) {
					boolean ambient=owner[i]==0;
					shares.add(new GasVolumeAllocation.Share(ambient?0:-1,representative[i],amount,
							ambient?GasVolumeAllocation.SourceKind.AMBIENT:GasVolumeAllocation.SourceKind.EXPLICIT_VACUUM));
				}else {
					double total=0;for(double value:origins.values())total+=value;
					for(var source:origins.entrySet()) {
						double part=amount*(source.getValue()/total);
						if(part>0)shares.add(new GasVolumeAllocation.Share(source.getKey(),representative[i],part,
								source.getKey()==0?GasVolumeAllocation.SourceKind.AMBIENT:GasVolumeAllocation.SourceKind.EXISTING));
					}
				}
			}
		}
		return new GasVolumeAllocation(domain, future, state, shares, phase, geometry);
	}

	static void requireCurrentGeometry(FluidDomain domain, PhaseOccupancyState state) {
		if (state == null || state.cellCount() != domain.cellCount) throw new IllegalArgumentException("Phase occupation dimensions differ from domain");
		domain.domainFlowFields.ensureInventoryGrid();
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
		// Persist the reconstruction input. The prepared frame and a cold restore must
		// derive the same PLIC, rather than invert its approximate integrated output again.
		return liquid;
	}
}
