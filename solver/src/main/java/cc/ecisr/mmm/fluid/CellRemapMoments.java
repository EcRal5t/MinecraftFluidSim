package cc.ecisr.mmm.fluid;

import java.util.ArrayList;

/** Bounded first-moment reconstruction with fixed positive inventory weights. */
final class CellRemapMoments {
	private CellRemapMoments() { }

	/** Mass errors use SI kg*m; volume errors use raw cell fraction*m. */
	record Audit(double[] massProjection, double[] massStorage, double[] volumeResidual,
			double maximumCentroidProjection, int projectedGroups, double[] unadvancedMassMoment,
			double[] signedMassResidual, int reconstructedGroups) {
		static Audit empty() { return new Audit(new double[3], new double[3], new double[3], 0, 0, new double[3], new double[3], 0); }
		FluidDomain.LiquidTransportStats stats() {
			if (reconstructedGroups == 0 && unadvancedMassMoment[0] == 0 && unadvancedMassMoment[1] == 0 && unadvancedMassMoment[2] == 0)
				return FluidDomain.LiquidTransportStats.INACTIVE;
			return new FluidDomain.LiquidTransportStats(moment(massProjection), moment(massStorage), moment(volumeResidual),
					moment(unadvancedMassMoment), moment(signedMassResidual), maximumCentroidProjection, projectedGroups, reconstructedGroups);
		}
		private static FluidDomain.Moment3 moment(double[] a) { return new FluidDomain.Moment3(a[0], a[1], a[2]); }
	}

	/** Projects the centroid into its fill-constrained support, contracting only axes that cannot retain their original span. */
	static boolean fit(FluidDomain domain, int cell, ArrayList<double[]> offsets, double[] masses,
			double[] target, double[] projection, double[] storage, double maximumTranslation) {
		return fit(domain, cell, offsets, masses, target, projection, storage, maximumTranslation, ~domain.geometry.cutCells.mask(cell) & 255);
	}
	static boolean fit(FluidDomain domain, int cell, ArrayList<double[]> offsets, double[] masses,
			double[] target, double[] projection, double[] storage, double maximumTranslation, int support) {
		if (!(Double.isFinite(maximumTranslation) && maximumTranslation >= 0))
			throw new IllegalArgumentException("Liquid quadrature translation requires finite available capacity");
		double h = domain.config.cellSize(); int mask = domain.geometry.cutCells.mask(cell);
		int[] coordinate = {cell % domain.sizeX, cell / domain.sizeX % domain.sizeY, cell / (domain.sizeX * domain.sizeY)};
		int[] micro = mask == 0 ? null : new int[masses.length];
		if (micro != null) for (int p = 0; p < masses.length; p++) for (int a = 0; a < 3; a++)
			if (offsets.get(p)[a] >= 0) micro[p] |= 1 << a;
		if (micro != null) for (int assigned : micro) if ((support & 1 << assigned) == 0)
			throw new IllegalStateException("Liquid quadrature is outside its assigned micro component");
		boolean moved = false;
		double[] lower = new double[2], upper = new double[2];
		for (int axis = 0; axis < 3; axis++) {
			if (!Double.isFinite(target[axis])) throw new IllegalStateException("Liquid first moment is not finite");
			projection[axis] = 0;
			double current = 0, magnitude = 0, mass = 0;
			for (int p = 0; p < masses.length; p++) {
				double moment = masses[p] * offsets.get(p)[axis]; current += moment; magnitude += Math.abs(moment); mass += masses[p];
			}
			// Keep the exact standard tensor rule when its moment already agrees to reduction precision.
			if (Math.abs(target[axis] - current) <= 8 * Math.ulp(magnitude)) continue;
			// Saturated support retains its canonical rule; unreachable moments remain visible.
			if (maximumTranslation == 0) { projection[axis] = current - target[axis]; continue; }
			double minimumShift = -maximumTranslation, maximumShift = maximumTranslation;
			// Every node on an axis shares one whole-cell interval or one of two micro intervals.
			for (int half = 0; half < (mask == 0 ? 1 : 2); half++) {
				double l = mask == 0 || half == 0 ? -0.5 : 0, u = mask == 0 || half == 1 ? 0.5 : 0;
				double physicalLower = (coordinate[axis] + 0.5 + l) * h;
				double physicalUpper = (coordinate[axis] + 0.5 + u) * h;
				float lf = (float) physicalLower, uf = (float) physicalUpper;
				if (lf < physicalLower) lf = Math.nextUp(lf);
				if (uf >= physicalUpper) uf = Math.nextDown(uf);
				// Cell and micro-cell classifiers divide binary32 coordinates in binary32.
				float spacing = mask == 0 ? (float) h : (float) h / 2;
				int expected = mask == 0 ? coordinate[axis] : 2 * coordinate[axis] + half;
				while (Math.floor(lf / spacing) < expected) lf = Math.nextUp(lf);
				while (Math.floor(uf / spacing) > expected) uf = Math.nextDown(uf);
				if (!Float.isFinite(lf) || !Float.isFinite(uf) || lf > uf
						|| Math.floor(lf / spacing) != expected || Math.floor(uf / spacing) != expected)
					throw new IllegalStateException("Open liquid support has no binary32 position in its assigned cell");
				lower[half] = lf / h - coordinate[axis] - 0.5;
				upper[half] = uf / h - coordinate[axis] - 0.5;
			}
			int commonHalf = mask == 0 || offsets.getFirst()[axis] < 0 ? 0 : 1;
			boolean commonInterval = true;
			for (int p = 0; p < masses.length; p++) {
				double q = offsets.get(p)[axis]; int half = mask == 0 || q < 0 ? 0 : 1;
				minimumShift = Math.max(minimumShift, lower[half] - q); maximumShift = Math.min(maximumShift, upper[half] - q);
				if (half != commonHalf) commonInterval = false;
			}
			if (minimumShift > maximumShift) throw new IllegalStateException("Open liquid support has no common binary32 translation");
			double desiredShift = (target[axis] - current) / mass;
			double shift = Math.max(minimumShift, Math.min(maximumShift, desiredShift));
			double scale = 1, center = current / mass;
			boolean projected = desiredShift < minimumShift || desiredShift > maximumShift;
			if (commonInterval && (desiredShift < minimumShift || desiredShift > maximumShift)) {
				// Largest positive affine rule at the feasible centroid. Tangential spans remain unchanged.
				shift = Math.max(-maximumTranslation, Math.min(maximumTranslation, desiredShift));
				double centroid = Math.max(lower[commonHalf], Math.min(upper[commonHalf], center + shift));
				projected = desiredShift < -maximumTranslation || desiredShift > maximumTranslation
						|| center + desiredShift < lower[commonHalf] || center + desiredShift > upper[commonHalf];
				shift = centroid - center;
				for (double[] offset : offsets) {
					double r = offset[axis] - center;
					if (r > 0) scale = Math.min(scale, (upper[commonHalf] - centroid) / r);
					else if (r < 0) scale = Math.min(scale, (centroid - lower[commonHalf]) / -r);
				}
			}
			double feasible = current + mass * shift;
			projection[axis] = projected ? feasible - target[axis] : 0;
			if (shift != 0 || scale != 1) {
				if (!moved) {
					for (int p = 0; p < offsets.size(); p++) offsets.set(p, offsets.get(p).clone());
					moved = true;
				}
				for (double[] offset : offsets) offset[axis] = scale == 1 ? offset[axis] + shift
						: center + shift + scale * (offset[axis] - center);
			}
		}
		double[] stored = new double[3]; float[] point = new float[3];
		for (int p = 0; p < masses.length; p++) {
			for (int a = 0; a < 3; a++) {
				point[a] = (float) ((coordinate[a] + 0.5 + offsets.get(p)[a]) * h);
				if (!Float.isFinite(point[a]) || Math.floor(point[a] / domain.config.cellSize()) != coordinate[a])
					throw new IllegalStateException("Published liquid position left its assigned cell coordinate");
				stored[a] += masses[p] * (point[a] / h - coordinate[a] - 0.5);
				if (micro != null && Math.floor(point[a] / (domain.config.cellSize() / 2)) != 2 * coordinate[a] + (micro[p] >>> a & 1))
					throw new IllegalStateException("Published liquid position left its assigned open micro box");
			}
			if (domain.particleCellIndex(point[0], point[1], point[2]) != cell
					|| domain.geometry.cutCells.pointInsideSolid(point[0], point[1], point[2], domain.config.cellSize()))
				throw new IllegalStateException("Published liquid position left its assigned open cell");
			if (micro != null && (support & 1 << micro[p]) == 0)
				throw new IllegalStateException("Published liquid position left its assigned micro component");
		}
		for (int a = 0; a < 3; a++) storage[a] = stored[a] - target[a] - projection[a];
		return moved;
	}
}
