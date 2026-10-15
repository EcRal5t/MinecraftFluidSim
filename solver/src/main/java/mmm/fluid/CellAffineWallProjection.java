package mmm.fluid;

/** Actual-mass projection onto the admitted wall-shaped polynomial fields. */
final class CellAffineWallProjection {
	private final CellAffineVelocity basis;
	private double normalWork, representationWork;

	/** Enforces fixed walls on the actual remapped velocity, before any later observation. */
	static double projectTransport(FluidDomain domain) {
		domain.ensureInventoryGrid();
		var geometry = LiquidPressureGeometry.capture(domain);
		var basis = CellAffineVelocity.capture(domain, geometry);
		var projection = new CellAffineWallProjection(basis);
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
		var coefficient = new double[3][4 * basis.blockCount()]; basis.fit(after, coefficient);
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

	CellAffineWallProjection(CellAffineVelocity basis) {
		this.basis = basis;
	}

	/** Projects coupled wall blocks; only constrained normal components contribute wall impulse. */
	void project(double[][] input, double[][] result, double[][] impulse) {
		normalWork = representationWork = 0;
		var coefficient = new double[3][4 * basis.blockCount()]; basis.fit(input, coefficient);
		var projected = new double[3][basis.markerCount()]; basis.predict(coefficient, projected);
		for (int axis = 0; axis < 3; axis++) for (int marker = 0; marker < basis.markerCount(); marker++) {
			int block = basis.markerBlock(marker);
			boolean shaped = basis.hasNormalShape(block, 0) || basis.hasNormalShape(block, 1) || basis.hasNormalShape(block, 2);
			boolean normal = basis.hasNormalShape(block, axis);
			double velocity = shaped ? projected[axis][marker] : input[axis][marker];
			double change = velocity - input[axis][marker];
			double work = .5 * basis.massKilograms(marker) * change * (velocity + input[axis][marker]);
			if (normal) normalWork += work; else representationWork += work;
			result[axis][marker] = velocity;
			impulse[axis][marker] = normal ? basis.massKilograms(marker) * change : 0;
			if (!Double.isFinite(velocity) || !Double.isFinite(impulse[axis][marker]))
				throw new FluidDomain.PressureSolveBlockedException("Unrepresentable shaped-wall projection at marker " + marker);
		}
	}
	double normalWork() { return normalWork; }
	double representationWork() { return representationWork; }
}
