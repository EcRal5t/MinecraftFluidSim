package mmm.fluid;


import mmm.units.SignedDyadic;

import java.util.ArrayList;
import java.util.List;

/** Authoritative pore capacity and floating PLIC volume in each fixed open component. */
final class GasComponentCapacity {
	/** Volumes are fractions of a whole cell; difference is authoritative capacity minus geometry. */
	record Component(int cell, int supportMask, SignedDyadic poreVolumeNormalized, SignedDyadic availableVolumeNormalized,
	                 double geometricGasVolumeNormalized, double geometricDifferenceNormalized,
	                 List<GasPieceCapacity.Piece> pieces) {
		Component { pieces = List.copyOf(pieces); }
	}

	private GasComponentCapacity() { }

	static List<Component> capture(FluidDomain domain, GasComplementGeometry geometry, PhaseLiquidGeometryEdit futureEdit) {
		if (domain == null) throw new IllegalArgumentException("Gas component domain is missing");
		long[] occupied = domain.granularCoupling == null ? new long[domain.cellCount] : domain.granularCoupling.copySolidSvq();
		return capture(domain, geometry, futureEdit, occupied);
	}

	/** Reads a complete current or planned solid-occupancy snapshot, including incoming coherent stock. */
	static List<Component> capture(FluidDomain domain, GasComplementGeometry geometry,
	                               PhaseLiquidGeometryEdit futureEdit, long[] occupiedSvq) {
		return captureStock(domain,geometry,futureEdit,occupiedSvq,null);
	}

	/** Complete planned liquid tuples in cell order, including every zero-stock fixed component. */
	static List<Component> capture(FluidDomain domain, GasComplementGeometry geometry,
	                               long[] occupiedSvq, List<PhaseLiquidGeometryEdit> liquidStock) {
		return capture(domain, geometry, occupiedSvq, liquidStock, null);
	}

	static List<Component> capture(FluidDomain domain, GasComplementGeometry geometry,
	                               long[] occupiedSvq, List<PhaseLiquidGeometryEdit> liquidStock,
	                               GasRuntimeGeometry source) {
		if (liquidStock == null) throw new IllegalArgumentException("Planned gas capacity requires complete liquid stock");
		return captureStock(domain,geometry,null,occupiedSvq,List.copyOf(liquidStock),source);
	}

	private static List<Component> captureStock(FluidDomain domain, GasComplementGeometry geometry,
	                                           PhaseLiquidGeometryEdit futureEdit, long[] occupiedSvq,
	                                           List<PhaseLiquidGeometryEdit> liquidStock) {
		return captureStock(domain, geometry, futureEdit, occupiedSvq, liquidStock, null);
	}

	private static List<Component> captureStock(FluidDomain domain, GasComplementGeometry geometry,
	                                           PhaseLiquidGeometryEdit futureEdit, long[] occupiedSvq,
	                                           List<PhaseLiquidGeometryEdit> liquidStock, GasRuntimeGeometry source) {
		validateInputs(domain, geometry, occupiedSvq);
		occupiedSvq = occupiedSvq.clone();
		if (liquidStock == null) {
			var current = new ArrayList<>(PhaseLiquidGeometryEdit.fromInventory(domain, domain.liquidCells));
			if (futureEdit != null) current.set(futureEdit.cell(), futureEdit);
			liquidStock = List.copyOf(current);
		}
		if (liquidStock.size() != domain.cellCount) throw new IllegalArgumentException("Planned gas liquid stock is incomplete");
		for (int cell = 0; cell < domain.cellCount; cell++) {
			var row = liquidStock.get(cell);
			if (row.cell() != cell || row.collisionMask() != geometry.collisionMask(cell))
				throw new IllegalArgumentException("Planned gas liquid stock differs from its geometry");
		}
		var result = new ArrayList<Component>();
		List<Component> previous = source == null ? List.of() : source.capacity().components();
		long[] previousOccupied = source == null ? null : source.occupiedSvq();
		int previousEnd = 0;
		for (int cell = 0; cell < domain.cellCount; cell++) {
			int previousStart = previousEnd;
			while (previousEnd < previous.size() && previous.get(previousEnd).cell() == cell) previousEnd++;
			if (sameCapacityCell(cell, geometry, occupiedSvq, liquidStock, source, previousOccupied)) {
				for (int component = previousStart; component < previousEnd; component++) result.add(previous.get(component));
				continue;
			}
			appendCell(domain, geometry, cell, occupiedSvq[cell], liquidStock.get(cell), result);
		}
		return List.copyOf(result);
	}

	private static void validateInputs(FluidDomain domain, GasComplementGeometry geometry, long[] occupied) {
		if (domain == null || geometry == null || geometry.sizeX() != domain.sizeX
				|| geometry.sizeY() != domain.sizeY || geometry.sizeZ() != domain.sizeZ
				|| occupied == null || occupied.length != domain.cellCount)
			throw new IllegalArgumentException("Gas component geometry dimensions differ from the domain");
		double h = domain.config.cellSize();
		if (Double.doubleToRawLongBits(geometry.cellVolumeCubicMeters()) != Double.doubleToRawLongBits(h * h * h))
			throw new IllegalArgumentException("Gas component geometry metric differs from the domain");
	}

	private static void appendCell(FluidDomain domain, GasComplementGeometry geometry, int cell, long occupied,
	                               PhaseLiquidGeometryEdit liquid,
	                               List<Component> result) {
		if (liquid.cell() != cell || liquid.collisionMask() != geometry.collisionMask(cell))
			throw new IllegalArgumentException("Planned gas liquid stock differs from its geometry");
		int mask = geometry.collisionMask(cell);
		int[] supports = CellRemapMicroTopology.components(mask);
		SignedDyadic open = SignedDyadic.of(Integer.bitCount(~mask & 255), -3);
		SignedDyadic solid = SignedDyadic.of(occupied, -24);
		if (solid.signum() < 0 || solid.compareTo(open) > 0)
			throw blocked("Solid inventory exceeds fixed open capacity", cell);
		if (!geometry.poreVolume(cell).equals(open.subtract(solid)))
			throw blocked("Gas geometry pore capacity differs from current occupancy",cell);
		SignedDyadic[] capacities = CellRemapMicroTopology.capacities(mask, open.subtract(solid));
		for (int i = 0; i < supports.length; i++) {
			int support = supports[i];
			SignedDyadic volume = liquid.components().get(i).volume();
			SignedDyadic available = capacities[i].subtract(volume);
			if (available.signum() < 0) throw blocked("Liquid inventory exceeds its open component capacity", cell);
            double gas = 0;
            for (int micro = 0; micro < 8; micro++) if ((support & 1 << micro) != 0) {
                var piece = geometry.piece(8 * cell + micro);
                if (piece != null) gas += piece.normalizedVolume();
            }
            if (available.signum() > 0 && gas == 0)
                throw blocked("Positive component capacity has no gas geometry; available="+available+", pore="+capacities[i]+", liquid="+volume, cell);
            double porosity = capacities[i].toDouble() * 8 / Integer.bitCount(support);
            gas *= porosity;
            double difference = available.toDouble() - gas;
            if (Math.abs(difference) > FlipPressurePhaseGeometry.CELL_VOLUME_RESOLUTION * capacities[i].toDouble())
                throw blocked("Gas geometry differs from inventory beyond float cell precision", cell);
            result.add(new Component(cell, support, capacities[i], available, gas, difference,
                    GasPieceCapacity.capture(cell, support, available, geometry)));
		}
	}

	/** Capacity reuse depends on local raw stock and planes, independently of chamber ownership. */
	private static boolean sameCapacityCell(int cell, GasComplementGeometry geometry, long[] occupied,
	                                        List<PhaseLiquidGeometryEdit> stock, GasRuntimeGeometry source,
	                                        long[] previousOccupied) {
		if (source == null || stock == null || previousOccupied.length != occupied.length
				|| source.liquidStock().size() != stock.size()) return false;
		GasComplementGeometry before = source.geometry();
		if (before.sizeX() != geometry.sizeX() || before.sizeY() != geometry.sizeY() || before.sizeZ() != geometry.sizeZ()
				|| Double.doubleToRawLongBits(before.cellVolumeCubicMeters())
						!= Double.doubleToRawLongBits(geometry.cellVolumeCubicMeters())
				|| previousOccupied[cell] != occupied[cell]
				|| before.collisionMask(cell) != geometry.collisionMask(cell)
				|| !before.poreVolume(cell).equals(geometry.poreVolume(cell))
				|| !source.liquidStock().get(cell).equals(stock.get(cell))) return false;
		for (int micro = 0; micro < 8; micro++)
			if (!java.util.Objects.equals(before.piece(8 * cell + micro), geometry.piece(8 * cell + micro))) return false;
		return true;
	}



	private static GasTopologyBlockedException blocked(String message, int cell) {
		return new GasTopologyBlockedException(message + " at cell " + cell);
	}
}
