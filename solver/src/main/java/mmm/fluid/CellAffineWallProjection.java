package mmm.fluid;

/** Actual-mass projection onto the admitted wall-shaped polynomial fields. */
final class CellAffineWallProjection {
	private final CellAffineVelocity basis;

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

	CellAffineWallProjection(FluidDomain domain, CellAffineVelocity basis, LiquidPressureGeometry geometry) {
		if (basis == null || geometry == null) throw new IllegalArgumentException("Missing shaped wall space");
		this.basis = basis;
	}

	/** The admitted q fields already satisfy every supported wet wall plane. */
	CellVelocityConstraintOperator.Row transform(CellVelocityConstraintOperator.Row row) { return row; }

	/** Actual-mass orthogonal projection on constrained axes; unconstrained axes retain raw velocity. */
	void project(double[][] input, double[][] result, double[][] impulse) {
		var coefficient = new double[3][4 * basis.blockCount()]; basis.fit(input, coefficient);
		var projected = new double[3][basis.markerCount()]; basis.predict(coefficient, projected);
		for (int axis = 0; axis < 3; axis++) for (int marker = 0; marker < basis.markerCount(); marker++) {
			double velocity = basis.hasNormalShape(basis.markerBlock(marker), axis) ? projected[axis][marker] : input[axis][marker];
			double change = velocity - input[axis][marker];
			result[axis][marker] = velocity; impulse[axis][marker] = basis.massKilograms(marker) * change;
			if (!Double.isFinite(velocity) || !Double.isFinite(impulse[axis][marker]))
				throw new FluidDomain.PressureSolveBlockedException("Unrepresentable shaped-wall projection at marker " + marker);
		}
	}
}
