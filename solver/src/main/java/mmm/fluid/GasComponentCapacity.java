package mmm.fluid;

import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Separate authoritative pore capacity and exact PLIC gas volume in each fixed open component. */
final class GasComponentCapacity {
	/** Volumes are fractions of a whole cell; difference is authoritative capacity minus geometry. */
	record Component(int cell, int supportMask, SignedDyadic poreVolumeNormalized, SignedDyadic availableVolumeNormalized,
	                 ExactRational geometricGasVolumeNormalized, ExactRational geometricDifferenceNormalized,
	                 InverseErrorRange inverseErrorRange, List<GasPieceCapacity.Piece> pieces) {
		Component { pieces = List.copyOf(pieces); }
	}
	record InverseErrorRange(ExactRational lower,ExactRational upper) { }

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
		if (domain == null || geometry == null || geometry.sizeX() != domain.sizeX
				|| geometry.sizeY() != domain.sizeY || geometry.sizeZ() != domain.sizeZ
				|| occupiedSvq == null || occupiedSvq.length != domain.cellCount)
			throw new IllegalArgumentException("Gas component geometry dimensions differ from the domain");
		occupiedSvq = occupiedSvq.clone();
		double h = domain.config.cellSize();
		if (Double.doubleToRawLongBits(geometry.cellVolumeCubicMeters()) != Double.doubleToRawLongBits(h * h * h))
			throw new IllegalArgumentException("Gas component geometry metric differs from the domain");
		for (int cell = 0; cell < domain.cellCount; cell++)
			if (geometry.collisionMask(cell) != domain.geometry.cutCells.mask(cell))
				throw new IllegalArgumentException("Gas component geometry collision mask differs at cell " + cell);
		if (futureEdit != null && (futureEdit.cell() >= domain.cellCount
				|| futureEdit.collisionMask() != domain.geometry.cutCells.mask(futureEdit.cell())))
			throw new IllegalArgumentException("Future gas component stock differs from the collision geometry");

		// Read authoritative columns directly; a captured geometry does not make an old marker index current.
		var stock = new HashMap<Long, SignedDyadic>();
		if (liquidStock != null) {
			if (liquidStock.size()!=domain.cellCount) throw new IllegalArgumentException("Planned gas liquid stock is incomplete");
			for (int cell=0;cell<domain.cellCount;cell++) {
				var row=liquidStock.get(cell);
				if (row.cell()!=cell || row.collisionMask()!=geometry.collisionMask(cell))
					throw new IllegalArgumentException("Planned gas liquid stock differs from its geometry");
				for (var component:row.components()) stock.put(key(cell,component.supportMask()),component.volume());
			}
		} else for (int marker = 0; marker < domain.particles.count(); marker++) {
			int cell = domain.particleCellIndex(marker);
			if (futureEdit != null && cell == futureEdit.cell()) continue;
			int support = CellRemapMicroTopology.positionSupport(domain.geometry.cutCells.mask(cell), domain.config.cellSize(),
					domain.particles.positionX(marker), domain.particles.positionY(marker), domain.particles.positionZ(marker));
			long key = key(cell, support);
			stock.merge(key, SignedDyadic.of(domain.particles.volume(marker)), SignedDyadic::add);
		}
		if (futureEdit != null) for (var component : futureEdit.components())
			stock.put(key(component.cell(), component.supportMask()), component.volume());
		var result = new ArrayList<Component>();
		List<Component> previous = source == null ? List.of() : source.capacity().components();
		long[] previousOccupied = source == null ? null : source.occupiedSvq();
		int previousEnd = 0;
		for (int cell = 0; cell < domain.cellCount; cell++) {
			int previousStart = previousEnd;
			while (previousEnd < previous.size() && previous.get(previousEnd).cell() == cell) previousEnd++;
			int mask = domain.geometry.cutCells.mask(cell);
			int[] supports = CellRemapMicroTopology.components(mask);
			SignedDyadic open = SignedDyadic.of(Integer.bitCount(~mask & 255), -3);
			SignedDyadic solid = SignedDyadic.of(occupiedSvq[cell], -24);
			if (solid.signum() < 0 || solid.compareTo(open) > 0)
				throw blocked("Solid inventory exceeds fixed open capacity", cell);
			if (!geometry.poreVolume(cell).equals(open.subtract(solid)))
				throw blocked("Gas geometry pore capacity differs from current occupancy",cell);
			if (sameCertifiedCell(cell, geometry, occupiedSvq, liquidStock, source, previousOccupied)) {
				for (int component = previousStart; component < previousEnd; component++) result.add(previous.get(component));
				continue;
			}
			SignedDyadic[] capacities = CellRemapMicroTopology.capacities(mask, open.subtract(solid));
			for (int i = 0; i < supports.length; i++) {
				int support = supports[i];
				SignedDyadic available = capacities[i].subtract(stock.getOrDefault(key(cell, support), SignedDyadic.ZERO));
				if (available.signum() < 0) throw blocked("Liquid inventory exceeds its open component capacity", cell);
				try {
					ExactRational gas = ExactRational.ZERO;
					GasComplementGeometry.Piece representative = null;
					for (int micro = 0; micro < 8; micro++) if ((support & 1 << micro) != 0) {
						var piece = geometry.piece(8 * cell + micro);
						if (piece != null) {
							if (representative != null && (!java.util.Objects.equals(representative.dryPlane(),piece.dryPlane())
									|| !java.util.Objects.equals(representative.offsetBracket(),piece.offsetBracket())))
								throw blocked("Gas component contains inconsistent PLIC certificates",cell);
							representative = piece;gas = gas.add(piece.normalizedVolume());
						}
					}
					if (available.signum() > 0 && gas.isZero())
						throw blocked("Positive component capacity has no gas geometry", cell);
					ExactRational porosity = exact(capacities[i]).multiply(8).divide(Integer.bitCount(support));
					gas = gas.multiply(porosity);
					ExactRational difference = exact(available).subtract(gas);
					InverseErrorRange range = inverseRange(representative,support,capacities[i],available,porosity,gas,cell);
					if (difference.compareTo(range.lower())<0 || difference.compareTo(range.upper())>0)
						throw blocked("Gas component inventory differs beyond its PLIC inverse certificate",cell);
					result.add(new Component(cell, support, capacities[i], available, gas, difference,range,
							GasPieceCapacity.capture(cell,support,capacities[i],available,geometry)));
				} catch (IllegalArgumentException | ArithmeticException magnitude) {
					throw blocked("Gas component capacity exceeds exact representation budget", cell);
				}
			}
		}
		return List.copyOf(result);
	}

	/** Capacity certificates depend on local raw stock and planes, independently of chamber ownership. */
	private static boolean sameCertifiedCell(int cell, GasComplementGeometry geometry, long[] occupied,
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

	private static InverseErrorRange inverseRange(GasComplementGeometry.Piece piece,int support,SignedDyadic capacity,SignedDyadic available,
			ExactRational porosity,ExactRational gas,int cell) {
		if (piece == null || piece.dryPlane() == null) return new InverseErrorRange(ExactRational.ZERO,ExactRational.ZERO);
		var bracket = piece.offsetBracket();
		if (bracket == null) throw blocked("Partial gas component has no exact PLIC inverse certificate",cell);
		ExactRational open = ExactRational.of(Integer.bitCount(support)).divide(8);
		ExactRational target = PlicComponentGeometry.ratio(capacity.subtract(available),capacity).multiply(Integer.bitCount(support)).divide(8);
		if (!target.equals(bracket.targetVolume())) throw blocked("Gas component inventory differs from its captured PLIC target",cell);
		return new InverseErrorRange(open.subtract(bracket.upperVolume()).multiply(porosity).subtract(gas),
				open.subtract(bracket.lowerVolume()).multiply(porosity).subtract(gas));
	}

	private static long key(int cell, int support) { return ((long) cell << 8) | support; }
	private static ExactRational exact(SignedDyadic value) {
		return value.exponent() >= 0
				? new ExactRational(value.significand().shiftLeft(value.exponent()), BigInteger.ONE)
				: new ExactRational(value.significand(), BigInteger.ONE.shiftLeft(-value.exponent()));
	}
	private static GasTopologyBlockedException blocked(String message, int cell) {
		return new GasTopologyBlockedException(message + " at cell " + cell);
	}
}
