package mmm.fluid;

import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;
import java.util.ArrayList;

/** Actual-mass projection onto affine fields with zero normal velocity on fixed wall planes. */
final class CellAffineWallProjection {
	private static final byte FREE = 0, NORMAL_MODE = 1, ZERO = 2;
	private final CellAffineVelocity basis;
	private final byte[] kinds;
	private final double[] centerOffsets, weights;

	/** Enforces fixed walls on the actual remapped velocity, before any later observation. */
	static double projectTransport(FluidDomain domain) {
		domain.ensureInventoryGrid();
		var geometry = LiquidPressureGeometry.capture(domain);
		var basis = CellAffineVelocity.capture(domain, geometry);
		var projection = new CellAffineWallProjection(domain, basis, geometry);
		var before = new double[3][basis.markerCount()];
		for (int marker = 0; marker < basis.markerCount(); marker++) {
			before[0][marker] = domain.particles.velocityX(marker);
			before[1][marker] = domain.particles.velocityY(marker);
			before[2][marker] = domain.particles.velocityZ(marker);
		}
		var after = new double[3][basis.markerCount()]; var impulse = new double[3][basis.markerCount()];
		projection.project(before, after, impulse);
		double work = 0;
		for (int axis = 0; axis < 3; axis++) for (int marker = 0; marker < basis.markerCount(); marker++) {
			after[axis][marker] = (float) after[axis][marker];
			if (!Double.isFinite(after[axis][marker])) throw new FluidDomain.PressureSolveBlockedException("Unrepresentable transported wall velocity");
			work += .5 * basis.massKilograms(marker) * (after[axis][marker] - before[axis][marker]) * (after[axis][marker] + before[axis][marker]);
		}
		if (!Double.isFinite(work)) throw new FluidDomain.PressureSolveBlockedException("Unrepresentable transported wall work");
		var coefficient = new double[3][4 * basis.cellCount()]; basis.fit(after, coefficient);
		var field = new CellAffineVelocityField(basis, coefficient);
		var transfer = CellAffineMacTransfer.capture(domain, basis, geometry);
		var mapped = new double[][]{new double[transfer.faceCount(0)], new double[transfer.faceCount(1)], new double[transfer.faceCount(2)]};
		transfer.apply(coefficient, mapped);
		for (double[] axis : mapped) for (double value : axis)
			if (!Float.isFinite((float) value)) throw new FluidDomain.PressureSolveBlockedException("Unrepresentable transported wall MAC velocity");
		for (int marker = 0; marker < basis.markerCount(); marker++) {
			domain.particles.setVelocityX(marker, (float) after[0][marker]);
			domain.particles.setVelocityY(marker, (float) after[1][marker]);
			domain.particles.setVelocityZ(marker, (float) after[2][marker]);
		}
		domain.pressureSystem.updateMarkerVelocityField(field);
		domain.velocityGridDirty = true;
		return work;
	}

	CellAffineWallProjection(FluidDomain domain, CellAffineVelocity basis, LiquidPressureGeometry geometry) {
		this.basis = basis;
		kinds = new byte[3 * basis.cellCount()]; centerOffsets = new double[kinds.length]; weights = new double[4 * kinds.length];
		for (int cell = 0; cell < basis.cellCount(); cell++) {
			var profile = geometry.cellSnapshot(cell); if (profile == null) continue;
			int[] planes = CellAffineVelocity.wallPlanes(profile);
			int[] index = {cell % domain.sizeX, cell / domain.sizeX % domain.sizeY, cell / (domain.sizeX * domain.sizeY)};
			for (int axis = 0; axis < 3; axis++) {
				int key = 3 * cell + axis;
				if (planes[axis] == 0) continue;
				if (Integer.bitCount(planes[axis]) > 1) { kinds[key] = ZERO; continue; }
				double wall = (index[axis] + .5 * Integer.numberOfTrailingZeros(planes[axis])) * basis.cellSize();
				var normal = new double[4];
				if (!basis.wallNormalWeights(cell, axis, wall, normal)) { kinds[key] = ZERO; continue; }
				centerOffsets[key] = normal[0];
				captureMassProjection(cell, axis, key); kinds[key] = NORMAL_MODE;
			}
		}
	}

	private void captureMassProjection(int cell, int axis, int key) {
		SignedDyadic denominator = SignedDyadic.ZERO;
		SignedDyadic[] numerator = {SignedDyadic.ZERO, SignedDyadic.ZERO, SignedDyadic.ZERO, SignedDyadic.ZERO};
		for (int ordinal = 0; ordinal < basis.cellMarkerCount(cell); ordinal++) {
			int marker = basis.cellMarker(cell, ordinal); double[] feature = new double[4];
			basis.pointWeights(cell, basis.position(marker, 0), basis.position(marker, 1), basis.position(marker, 2), feature);
			SignedDyadic q = SignedDyadic.of(centerOffsets[key] + feature[axis + 1]);
			SignedDyadic massTimesQ = SignedDyadic.of(basis.massKilograms(marker)).multiply(q);
			denominator = denominator.add(massTimesQ.multiply(q));
			for (int term = 0; term < 4; term++) numerator[term] = numerator[term].add(massTimesQ.multiply(SignedDyadic.of(feature[term])));
		}
		if (denominator.signum() <= 0) throw new FluidDomain.PressureSolveBlockedException("Wall-admissible field lacks actual inertia at cell " + cell);
		for (int term = 0; term < 4; term++) {
			int shift = numerator[term].exponent() - denominator.exponent();
			weights[4 * key + term] = new ExactRational(numerator[term].significand().shiftLeft(Math.max(0, shift)),
					denominator.significand().shiftLeft(Math.max(0, -shift))).toDouble();
			if (!Double.isFinite(weights[4 * key + term])) throw new FluidDomain.PressureSolveBlockedException("Unrepresentable wall projection at cell " + cell);
		}
	}

	/** A S, before the fixed cell fit L pulls the row back to actual marker velocities. */
	CellVelocityConstraintOperator.Row transform(CellVelocityConstraintOperator.Row row) {
		var blocks = new ArrayList<CellVelocityConstraintOperator.Block>();
		for (var block : row.blocks()) {
			int key = 3 * block.cell() + block.axis(); double[] coefficient = block.coefficients();
			if (kinds[key] == ZERO) continue;
			if (kinds[key] == NORMAL_MODE) {
				double amplitude = Math.fma(coefficient[0], centerOffsets[key], coefficient[block.axis() + 1]);
				for (int term = 0; term < 4; term++) coefficient[term] = amplitude * weights[4 * key + term];
			}
			blocks.add(new CellVelocityConstraintOperator.Block(block.cell(), block.axis(), coefficient));
		}
		return new CellVelocityConstraintOperator.Row(blocks, row.storage());
	}

	/** Retains the unresolved residual and records the fixed-wall impulse once. */
	void project(double[][] input, double[][] result, double[][] impulse) {
		var coefficient = new double[3][4 * basis.cellCount()]; basis.fit(input, coefficient);
		var before = new double[3][basis.markerCount()]; basis.predict(coefficient, before);
		for (int cell = 0; cell < basis.cellCount(); cell++) for (int axis = 0; axis < 3; axis++) {
			int key = 3 * cell + axis; if (kinds[key] == FREE) continue;
			double amplitude = 0;
			if (kinds[key] == NORMAL_MODE) for (int term = 0; term < 4; term++)
				amplitude = Math.fma(weights[4 * key + term], coefficient[axis][4 * cell + term], amplitude);
			for (int term = 0; term < 4; term++) coefficient[axis][4 * cell + term] = 0;
			coefficient[axis][4 * cell] = amplitude * centerOffsets[key];
			coefficient[axis][4 * cell + axis + 1] = amplitude;
		}
		var after = new double[3][basis.markerCount()]; basis.predict(coefficient, after);
		for (int axis = 0; axis < 3; axis++) for (int marker = 0; marker < basis.markerCount(); marker++) {
			double change = kinds[3 * basis.markerCell(marker) + axis] == FREE ? 0 : after[axis][marker] - before[axis][marker];
			result[axis][marker] = input[axis][marker] + change;
			impulse[axis][marker] = basis.massKilograms(marker) * change;
			if (!Double.isFinite(result[axis][marker]) || !Double.isFinite(impulse[axis][marker]))
				throw new FluidDomain.PressureSolveBlockedException("Unrepresentable fixed-wall projection at marker " + marker);
		}
	}
}
