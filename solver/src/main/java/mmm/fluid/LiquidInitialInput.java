package mmm.fluid;

import mmm.inventory.LiquidCellInventory;

import mmm.chemistry.ChemicalMixtureRecord;
import mmm.chemistry.ChemicalRegistry;
import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.List;

/** Converts an explicitly supplied point fixture once into cell inventory and MAC momentum. */
final class LiquidInitialInput {
	private LiquidInitialInput() { }
	static void restore(FluidDomain domain, FlipParticleState input, FluidMaterialRegistry palette,
	                    int[] materials, List<ChemicalMixtureRecord> mixtures, byte[] indicated,
	                    ChemicalRegistry chemistry) {
		input.requireCompatible(domain.sizeX, domain.sizeY, domain.sizeZ, domain.config.cellSize());
		CellRemapMicroTopology.requireRestoreCapacity(input);
		var inventory = new LiquidCellInventory(domain.cellCount, chemistry);
		var additions = new ArrayList<LiquidCellInventory.Stock>();
		float[] points = input.positionVolume(), velocity = input.particleVelocities();
		long[] masses = input.massUnits(); byte[] masks = input.collisionMicroMasks();
		double[][] momentum = {new double[domain.mac.velocityX.length], new double[domain.mac.velocityY.length], new double[domain.mac.velocityZ.length]};
		float h = domain.config.cellSize();
		for (int p = 0; p < input.particleCount(); p++) {
			int x = (int) Math.floor(points[4 * p] / h), y = (int) Math.floor(points[4 * p + 1] / h), z = (int) Math.floor(points[4 * p + 2] / h);
			int cell = domain.cellIndex(x, y, z);
			int support = CellRemapMicroTopology.positionSupport(Byte.toUnsignedInt(masks[cell]), h, points[4 * p], points[4 * p + 1], points[4 * p + 2]);
			int material = materials == null ? 0 : materials[p];
			if (palette != null) palette.material(material);
			additions.add(new LiquidCellInventory.Stock(new LiquidCellInventory.Key(cell, material, support),
					SignedDyadic.of(points[4 * p + 3]), masses[p], mixtures == null ? null : mixtures.get(p), 0,
					indicated != null && indicated[p] != 0));
			double halfMass = .5 * mmm.units.Mass.kilograms(masses[p]);
			momentum[0][domain.xFaceIndex(x, y, z)] += halfMass * velocity[3 * p];
			momentum[0][domain.xFaceIndex(x + 1, y, z)] += halfMass * velocity[3 * p];
			momentum[1][domain.yFaceIndex(x, y, z)] += halfMass * velocity[3 * p + 1];
			momentum[1][domain.yFaceIndex(x, y + 1, z)] += halfMass * velocity[3 * p + 1];
			momentum[2][domain.zFaceIndex(x, y, z)] += halfMass * velocity[3 * p + 2];
			momentum[2][domain.zFaceIndex(x, y, z + 1)] += halfMass * velocity[3 * p + 2];
		}
		inventory.planAdditions(additions).commit();
		// All input validation precedes publication of the new owners.
		domain.clearThermalBinding();
		domain.geometry.cutCells.restoreMicroMasks(masks);
		for (int cell = 0; cell < masks.length; cell++) domain.geometry.solid[cell] = Byte.toUnsignedInt(masks[cell]) == 255;
		domain.liquidCells = inventory; domain.materialRegistry = palette;
		domain.mac.restoreMomentum(new MacMomentumState(domain.sizeX, domain.sizeY, domain.sizeZ, momentum[0], momentum[1], momentum[2]));
		domain.markCellInventoryChanged();
	}
}
