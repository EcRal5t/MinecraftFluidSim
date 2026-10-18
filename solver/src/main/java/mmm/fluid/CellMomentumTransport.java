package mmm.fluid;

import mmm.inventory.LiquidCellInventory;

import mmm.units.Mass;
import java.util.Arrays;

/** Actual integer mass fluxes lifted onto the same staggered dual masses used by pressure. */
final class CellMomentumTransport {
	private final FluidDomain domain;
	private final double[][] massFlux;
	private final double[] before, after;
	private final MacMomentumAdvection[] components = new MacMomentumAdvection[3];
	CellMomentumTransport(FluidDomain domain) {
		this.domain = domain;
		massFlux = new double[][]{new double[domain.mac.velocityX.length], new double[domain.mac.velocityY.length], new double[domain.mac.velocityZ.length]};
		before = new double[domain.cellCount]; after = before.clone();
		for (int axis = 0; axis < 3; axis++) components[axis] = new MacMomentumAdvection(domain.sizeX, domain.sizeY, domain.sizeZ, axis);
	}
	MacMomentumState prepare(CellLiquidVolumeTransport.Prepared liquid) {
		return prepare(liquid, domain.liquidCells, domain.mac.velocityX, domain.mac.velocityY, domain.mac.velocityZ);
	}
	MacMomentumState prepare(CellLiquidVolumeTransport.Prepared liquid, LiquidCellInventory source, float[] vx, float[] vy, float[] vz) {
		for (var column : massFlux) Arrays.fill(column, 0);
		var flux = liquid.volumeFlux();
		for (var transfer : liquid.inventory().transfers()) {
			if (transfer.massUnits() == 0) continue;
			int face = transfer.face(), axis = flux.axis[face], right = flux.right[face];
			int x = right % domain.sizeX, y = right / domain.sizeX % domain.sizeY, z = right / (domain.sizeX * domain.sizeY);
			int index = (z * (domain.sizeY + (axis == 1 ? 1 : 0)) + y) * (domain.sizeX + (axis == 0 ? 1 : 0)) + x;
			massFlux[axis][index] += Mass.kilograms(transfer.massUnits()) * (transfer.source().cell() == flux.left[face] ? 1 : -1);
		}
		cellMass(source, before); cellMass(liquid.inventory().future(), after);
		return new MacMomentumState(domain.sizeX, domain.sizeY, domain.sizeZ,
				components[0].transport(before, after, massFlux, vx),
				components[1].transport(before, after, massFlux, vy),
				components[2].transport(before, after, massFlux, vz));
	}
	private void cellMass(LiquidCellInventory inventory, double[] result) {
		for (int cell = 0; cell < result.length; cell++) {
			long mass = 0; for (var stock : inventory.stocksInCell(cell)) mass = Math.addExact(mass, stock.massUnits());
			result[cell] = Mass.kilograms(mass);
		}
	}
}
