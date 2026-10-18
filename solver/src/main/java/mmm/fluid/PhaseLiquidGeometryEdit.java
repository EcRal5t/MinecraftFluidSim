package mmm.fluid;

import mmm.inventory.LiquidCellInventory;

import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.List;

/** Future actual liquid stock in every open collision component of one phase-edited cell. */
record PhaseLiquidGeometryEdit(int cell, int collisionMask, List<LiquidComponentMoments> components) {

	PhaseLiquidGeometryEdit {
		if (cell < 0 || collisionMask < 0 || collisionMask > 255 || components == null)
			throw new IllegalArgumentException("Invalid phase liquid geometry edit");
		components = List.copyOf(components);
		int[] support = CellRemapMicroTopology.components(collisionMask);
		if (components.size() != support.length)
			throw new IllegalArgumentException("Phase liquid geometry must include every open component");
		for (int i = 0; i < support.length; i++) {
			var component = components.get(i);
			if (component.cell() != cell || component.supportMask() != support[i])
				throw new IllegalArgumentException("Phase liquid component geometry disagrees with its collision mask");
		}

	}

	int markerCount() {
		int count = 0;
		for (var component : components) count = Math.addExact(count, component.markerCount());
		return count;
	}

	SignedDyadic volume() {
		SignedDyadic volume = SignedDyadic.ZERO;
		for (var component : components) volume = volume.add(component.volume());
		return volume;
	}

	double firstMomentX() { return firstMoment(0); }
	double firstMomentY() { return firstMoment(1); }
	double firstMomentZ() { return firstMoment(2); }

	double firstMoment(int axis) {
		if (axis < 0 || axis > 2) throw new IllegalArgumentException("Invalid phase liquid moment axis");
		double moment = 0;
		for (var component : components) moment += component.firstMoment(axis);
		return moment;
	}

	/** The shared first-order cell geometry is derived once from persistent component inventories. */
	static List<PhaseLiquidGeometryEdit> fromInventory(FluidDomain domain, LiquidCellInventory inventory) {
		return fromInventory(domain, inventory, null);
	}
	static List<PhaseLiquidGeometryEdit> fromInventory(FluidDomain domain, LiquidCellInventory inventory, byte[] collisionMasks) {
		if (inventory == null || inventory.cellCount() != domain.cellCount)
			throw new IllegalArgumentException("Liquid geometry inventory shape differs");
		if (collisionMasks != null && collisionMasks.length != domain.cellCount)
			throw new IllegalArgumentException("Future collision shape differs");
		var result = new ArrayList<PhaseLiquidGeometryEdit>(domain.cellCount);
		for (int cell = 0; cell < domain.cellCount; cell++) {
			int mask = collisionMasks == null ? domain.geometry.cutCells.mask(cell) : Byte.toUnsignedInt(collisionMasks[cell]);
			var components = new ArrayList<LiquidComponentMoments>();
			for (int support : CellRemapMicroTopology.components(mask)) {
				SignedDyadic volume = SignedDyadic.ZERO; int count = 0;
				for (var stock : inventory.stocksInCell(cell)) if (stock.key().supportMask() == support) {
					volume = volume.add(stock.volume()); count++;
				}
				double[] center = new double[3];
				for (int micro = 0; micro < 8; micro++) if ((support & 1 << micro) != 0)
					for (int axis = 0; axis < 3; axis++) center[axis] += .25 + .5 * (micro >>> axis & 1);
				int pieces = Integer.bitCount(support);
				double h = domain.config.cellSize(), amount = volume.toDouble();
				components.add(new LiquidComponentMoments(cell, support, count, volume,
						amount * h * (cell % domain.sizeX + center[0] / pieces),
						amount * h * (cell / domain.sizeX % domain.sizeY + center[1] / pieces),
						amount * h * (cell / (domain.sizeX * domain.sizeY) + center[2] / pieces)));
			}
			for (var stock : inventory.stocksInCell(cell)) {
				boolean found = false;
				for (var component : components) found |= component.supportMask() == stock.key().supportMask();
				if (!found) throw new IllegalArgumentException("Liquid stock support differs from fixed collision geometry");
			}
			result.add(new PhaseLiquidGeometryEdit(cell, mask, components));
		}
		return List.copyOf(result);
	}

}
