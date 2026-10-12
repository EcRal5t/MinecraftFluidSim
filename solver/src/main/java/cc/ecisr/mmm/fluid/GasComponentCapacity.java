package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.chemistry.ExactRational;
import cc.ecisr.mmm.units.SignedDyadic;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Separate authoritative pore capacity and exact PLIC gas volume in each fixed open component. */
final class GasComponentCapacity {
	/** Volumes are fractions of a whole cell; difference is authoritative capacity minus geometry. */
	record Component(int cell, int supportMask, SignedDyadic availableVolumeNormalized,
	                 ExactRational geometricGasVolumeNormalized, ExactRational geometricDifferenceNormalized) { }

	private GasComponentCapacity() { }

	static List<Component> capture(FluidDomain domain, GasComplementGeometry geometry, PhaseLiquidGeometryEdit futureEdit) {
		if (domain == null || geometry == null || geometry.sizeX() != domain.sizeX
				|| geometry.sizeY() != domain.sizeY || geometry.sizeZ() != domain.sizeZ)
			throw new IllegalArgumentException("Gas component geometry dimensions differ from the domain");
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
		for (int marker = 0; marker < domain.particles.count(); marker++) {
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
		for (int cell = 0; cell < domain.cellCount; cell++) {
			int mask = domain.geometry.cutCells.mask(cell);
			int[] supports = CellRemapMicroTopology.components(mask);
			SignedDyadic open = SignedDyadic.of(Integer.bitCount(~mask & 255), -3);
			SignedDyadic solid = domain.granularCoupling == null ? SignedDyadic.ZERO
					: SignedDyadic.of(domain.granularCoupling.solidSvq(cell), -24);
			if (solid.signum() < 0 || solid.compareTo(open) > 0)
				throw blocked("Solid inventory exceeds fixed open capacity", cell);
			SignedDyadic[] capacities = CellRemapMicroTopology.capacities(mask, open.subtract(solid));
			for (int i = 0; i < supports.length; i++) {
				int support = supports[i];
				SignedDyadic available = capacities[i].subtract(stock.getOrDefault(key(cell, support), SignedDyadic.ZERO));
				if (available.signum() < 0) throw blocked("Liquid inventory exceeds its open component capacity", cell);
				try {
					ExactRational gas = ExactRational.ZERO;
					for (int micro = 0; micro < 8; micro++) if ((support & 1 << micro) != 0) {
						var piece = geometry.piece(8 * cell + micro);
						if (piece != null) gas = gas.add(piece.normalizedVolume());
					}
					if (available.signum() > 0 && gas.isZero())
						throw blocked("Positive component capacity has no gas geometry", cell);
					ExactRational porosity = exact(capacities[i]).multiply(8).divide(Integer.bitCount(support));
					gas = gas.multiply(porosity);
					result.add(new Component(cell, support, available, gas, exact(available).subtract(gas)));
				} catch (IllegalArgumentException | ArithmeticException magnitude) {
					throw blocked("Gas component capacity exceeds exact representation budget", cell);
				}
			}
		}
		return List.copyOf(result);
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
