package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.thermal.ThermalCarrierContext;

/** Cell-centred trilinear observations through connected, fully open cells containing liquid. */
final class LiquidThermalSupport {
	private LiquidThermalSupport() { }

	static void observe(FluidDomainStepCoordinator domain, int marker, ThermalCarrierContext.SupportConsumer consumer) {
		int own = domain.particleCellIndex(marker);
		if (!wetOpen(domain, own)) { consumer.accept(own, 1.0); return; }
		double gx = domain.particles.positionX(marker) / (double) domain.config.cellSize() - 0.5;
		double gy = domain.particles.positionY(marker) / (double) domain.config.cellSize() - 0.5;
		double gz = domain.particles.positionZ(marker) / (double) domain.config.cellSize() - 0.5;
		int bx = (int) Math.floor(gx), by = (int) Math.floor(gy), bz = (int) Math.floor(gz);
		double fx = gx - bx, fy = gy - by, fz = gz - bz, sum = 0.0;
		int reachable = 0;
		for (int corner = 0; corner < 8; corner++) {
			int x = bx + (corner & 1), y = by + ((corner >> 1) & 1), z = bz + (corner >> 2);
			if (connected(domain, own, x, y, z)) {
				reachable |= 1 << corner;
				sum += weight(corner, fx, fy, fz);
			}
		}
		if (!(sum > 0.0)) throw new IllegalStateException("Liquid marker has no thermal observation support");
		for (int corner = 0; corner < 8; corner++) if ((reachable & (1 << corner)) != 0) {
			double contribution = weight(corner, fx, fy, fz);
			if (contribution > 0.0) consumer.accept(domain.cellIndex(bx + (corner & 1),
					by + ((corner >> 1) & 1), bz + (corner >> 2)), contribution / sum);
		}
	}

	private static double weight(int corner, double fx, double fy, double fz) {
		return ((corner & 1) == 0 ? 1 - fx : fx) * ((corner & 2) == 0 ? 1 - fy : fy)
				* ((corner & 4) == 0 ? 1 - fz : fz);
	}

	private static boolean wetOpen(FluidDomainStepCoordinator domain, int cell) {
		return domain.markerGrid.markerCount[cell] > 0 && domain.geometry.cutCells.openCellVolume(cell) == 1.0F;
	}

	/** At most three face crossings, trying all axis orders; every traversed cell is wet and fully open. */
	private static boolean connected(FluidDomainStepCoordinator domain, int own, int tx, int ty, int tz) {
		if (tx < 0 || tx >= domain.sizeX || ty < 0 || ty >= domain.sizeY || tz < 0 || tz >= domain.sizeZ) return false;
		if (!wetOpen(domain, domain.cellIndex(tx, ty, tz))) return false;
		int x = own % domain.sizeX, y = own / domain.sizeX % domain.sizeY, z = own / (domain.sizeX * domain.sizeY);
		if (x == tx && y == ty && z == tz) return true;
		var cut = domain.geometry.cutCells;
		if (x != tx && cut.xFaceOpen(Math.max(x, tx), y, z) == 1.0F
				&& wetOpen(domain, domain.cellIndex(tx, y, z)) && connected(domain, domain.cellIndex(tx, y, z), tx, ty, tz)) return true;
		if (y != ty && cut.yFaceOpen(x, Math.max(y, ty), z) == 1.0F
				&& wetOpen(domain, domain.cellIndex(x, ty, z)) && connected(domain, domain.cellIndex(x, ty, z), tx, ty, tz)) return true;
		return z != tz && cut.zFaceOpen(x, y, Math.max(z, tz)) == 1.0F
				&& wetOpen(domain, domain.cellIndex(x, y, tz)) && connected(domain, domain.cellIndex(x, y, tz), tx, ty, tz);
	}
}
