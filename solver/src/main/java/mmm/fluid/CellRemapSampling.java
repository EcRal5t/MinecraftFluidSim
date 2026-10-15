package mmm.fluid;

import java.util.ArrayList;
import mmm.units.SignedDyadic;

/** Positive joint-moment sampling with explicit support approximation on transported axes. */
final class CellRemapSampling {
	private CellRemapSampling() { }
	record Prepared(ArrayList<Float> volumes, double[] masses, CellRemapSpatialMoments.Result result) { }
	private static final class Search { String lastFailure; }
	static Prepared prepare(FluidDomain domain, int cell, int support, SignedDyadic volume, double mass, double density,
			ArrayList<Float> volumes, CellRemapSpatialMoments moments, double[][] preferred, double[] target,
			double[] storage, int movingAxes, double maximumTranslation, double[] projection, long maximumNodes) {
		if (maximumNodes < 1 || !(Double.isFinite(maximumTranslation) && maximumTranslation >= 0))
			throw new IllegalArgumentException("Liquid sampling requires positive node budget and finite fill translation");
		var search = new Search();
		var masses = masses(mass, density, volume, volumes);
		var result = attempt(domain, cell, support, masses, prepareFit(moments, masses), moments, moments, preferred, target, storage, 0, null, null, search);
		if (result != null) return new Prepared(volumes, masses, result);
		var lower = new double[3]; var upper = new double[3]; var mean = moments.centroid();
		var bounded = boundedCentroid(support, mean, movingAxes, maximumTranslation, lower, upper);
		var selected = new CellRemapSpatialMoments(); selected.add(moments);
		var rule = selected.boxRule(volume, lower, upper);
		if (rule == null && movingAxes != 0) { selected.setCentroid(bounded); rule = selected.boxRule(volume, lower, upper); }
		if (rule != null && rule.volumes().size() <= maximumNodes) {
			var actualTarget = new double[3]; var selectedMean = selected.centroid(); var sourceMean = moments.centroid();
			for (int a = 0; a < 3; a++) actualTarget[a] = selectedMean[a] == sourceMean[a] ? target[a] : mass * selectedMean[a];
			var ruleMasses = masses(mass, density, volume, rule.volumes());
			result = attempt(domain, cell, support, ruleMasses, prepareFit(selected, ruleMasses), selected, moments,
					rule.positions(), actualTarget, storage, 0, null, null, search);
			if (result != null) {
				for (int a = 0; a < 3; a++) projection[a] = actualTarget[a] - target[a];
				return new Prepared(rule.volumes(), ruleMasses, result);
			}
		}

		return new Prepared(volumes, masses, fitSupport(domain, cell, support, masses, moments, preferred, target,
				storage, movingAxes, maximumTranslation, projection, search));
	}
	private static double[] masses(double mass, double density, SignedDyadic volume, ArrayList<Float> volumes) {
		var weights = new SignedDyadic[volumes.size()];
		for (int p = 0; p < weights.length; p++) weights[p] = SignedDyadic.of(volumes.get(p));
		return CellRemapMassAllocation.allocate(mass, volume, weights, density);
	}
	private static double[] boundedCentroid(int support, double[] mean, int movingAxes, double maximumTranslation,
			double[] lower, double[] upper) {
		var result = mean.clone();
		for (int axis = 0; axis < 3; axis++) {
			boolean low = false, high = false; double center = 0;
			for (int micro = 0; micro < 8; micro++) if ((support & 1 << micro) != 0) {
				if ((micro & 1 << axis) == 0) low = true; else high = true;
				center += (micro & 1 << axis) == 0 ? -.25 : .25;
			}
			center /= Integer.bitCount(support); lower[axis] = low ? -.5 : 0; upper[axis] = high ? .5 : 0;
			if ((movingAxes & 1 << axis) != 0) result[axis] = Math.max(lower[axis], Math.min(upper[axis],
					Math.max(center - maximumTranslation, Math.min(center + maximumTranslation, mean[axis]))));
		}
		return result;
	}


	private static CellRemapSpatialMoments.Result fitSupport(FluidDomain domain, int cell, int support, double[] masses,
			CellRemapSpatialMoments moments, double[][] preferred, double[] targetMoment, double[] storage,
			int movingAxes, double maximumTranslation, double[] projection, Search search) {
		CellRemapSpatialMoments.Result result;
		if (movingAxes != 0) {
			var projected = new CellRemapSpatialMoments(); projected.add(moments);
			var lower = new double[3]; var upper = new double[3]; var target = targetMoment.clone();
			var mean = boundedCentroid(support, moments.centroid(), movingAxes, maximumTranslation, lower, upper);
			var original = moments.centroid();
			for (int a = 0; a < 3; a++) if (mean[a] != original[a]) target[a] = moments.mass() * mean[a];
			projected.setCentroid(mean);
			result = attempt(domain, cell, support, masses, prepareFit(projected, masses), projected, moments, preferred, target, storage, movingAxes, lower, upper, search);
			if (result != null) {
				for (int axis = 0; axis < 3; axis++) projection[axis] = target[axis] - targetMoment[axis];
				return result;
			}
		}
		double[] centroid = moments.centroid();
		for (int axis = 0; axis < 3; axis++) {
			double availableVariance = .25 - centroid[axis] * centroid[axis];
			double variance = moments.spatialVariance(axis);
			if (Math.abs(centroid[axis]) > .5 || variance > availableVariance
					+ 128 * (Math.ulp(variance) + Math.ulp(Math.abs(availableVariance))))
				throw new MechanicalTimeRefinement(2, "Liquid spatial mean and variance exceed the receiving cell support on axis " + axis
						+ " in cell " + cell + ": mean=" + centroid[axis] + ", variance=" + variance + ", availableVariance=" + availableVariance + failureSuffix(search));
		}
		throw new MechanicalTimeRefinement(2, "Liquid joint covariance has no supported positive spatial rule in cell " + cell
				+ ": mean=" + java.util.Arrays.toString(centroid) + ", variance="
				+ java.util.Arrays.toString(new double[]{moments.spatialVariance(0), moments.spatialVariance(1), moments.spatialVariance(2)})
				+ ", nodes=" + masses.length + ", required=" + moments.minimumCount() + failureSuffix(search));
	}

	private static CellRemapSpatialMoments.PreparedFit prepareFit(CellRemapSpatialMoments moments, double[] masses) {
		try { return moments.prepareFit(masses); }
		catch (CellRemapSpatialMoments.FitFailure failure) {
			throw new MechanicalTimeRefinement(2, "Liquid joint quadrature is unrepresentable: " + failure.failure);
		}
	}

	private static CellRemapSpatialMoments.Result attempt(FluidDomain domain, int cell, int support, double[] masses,
			CellRemapSpatialMoments.PreparedFit fit, CellRemapSpatialMoments moments, CellRemapSpatialMoments mechanical, double[][] preferred, double[] targetMoment, double[] storage,
			int movingAxes, double[] lower, double[] upper, Search search) {
		CellRemapSpatialMoments.Result result;
		try { result = fit.fit(preferred); }
		catch (CellRemapSpatialMoments.FitFailure failure) {
			if (failure.failure != CellRemapSpatialMoments.Failure.UNREPRESENTABLE)
				throw new MechanicalTimeRefinement(2, "Liquid joint quadrature is unrepresentable: " + failure.failure);
			search.lastFailure = failure.getMessage(); return null;
		}
		boolean changed = !java.util.Arrays.equals(moments.centroid(), mechanical.centroid());
		if (movingAxes != 0) {
			double[] mean = moments.centroid();
			for (int axis = 0; axis < 3; axis++) if ((movingAxes & 1 << axis) != 0) {
				double scale = 1;
				for (double[] point : result.positions()) {
					double offset = point[axis] - mean[axis];
					if (offset > 0) scale = Math.min(scale, (upper[axis] - mean[axis]) / offset);
					else if (offset < 0) scale = Math.min(scale, (mean[axis] - lower[axis]) / -offset);
				}
				changed |= scale != 1;
				for (double[] point : result.positions()) point[axis] = Math.max(lower[axis], Math.min(upper[axis], mean[axis] + scale * (point[axis] - mean[axis])));
			}
		}
		if (changed) {
			var velocity = mechanical.fitMechanical(masses, result.positions(), result.velocities());
			if (!velocity.successful()) { search.lastFailure = velocity.failure().reason; return null; }
			result = new CellRemapSpatialMoments.Result(result.positions(), velocity.velocities());
		}
		int[] coordinate = {cell % domain.sizeX, cell / domain.sizeX % domain.sizeY, cell / (domain.sizeX * domain.sizeY)};
		double[][] published = new double[masses.length][3]; double[] stored = new double[3];
		double h = domain.config.cellSize();
		for (int p = 0; p < masses.length; p++) {
			float[] point = publishPoint(domain, coordinate, support, result.positions()[p]);
			if (point == null) { search.lastFailure = "Published node " + p + " leaves its receiving component"; return null; }
			for (int a = 0; a < 3; a++) {
				published[p][a] = point[a] / h - coordinate[a] - .5;
				stored[a] += masses[p] * published[p][a];
			}
		}
		for (int a = 0; a < 3; a++) storage[a] = stored[a] - targetMoment[a];
		return new CellRemapSpatialMoments.Result(published, result.velocities());
	}

	private static String failureSuffix(Search search) {
		return search.lastFailure == null ? "" : "; last fit failure: " + search.lastFailure;
	}

	private static float[] publishPoint(FluidDomain domain, int[] coordinate, int support, double[] offset) {
		for (double value : offset) if (!(Double.isFinite(value) && value >= -.5 && value <= .5)) return null;
		float h = domain.config.cellSize(); float[] point = new float[3]; int preferredMicro = 0;
		for (int a = 0; a < 3; a++) {
			point[a] = nearestInside((float) ((coordinate[a] + .5 + offset[a]) * h), h, coordinate[a]);
			if (!Float.isFinite(point[a])) return null;
			preferredMicro |= ((int) Math.floor(point[a] / (h / 2)) - 2 * coordinate[a]) << a;
		}
		if ((support & 1 << preferredMicro) != 0 && !domain.geometry.cutCells.pointInsideSolid(point[0], point[1], point[2], h)) return point;
		// A mathematical point on a micro face admits either neighboring open micro box.
		for (int micro = 0; micro < 8; micro++) if ((support & 1 << micro) != 0) {
			boolean compatible = true;
			for (int a = 0; a < 3; a++) {
				int half = micro >>> a & 1;
				if (offset[a] < 0 && half != 0 || offset[a] > 0 && half != 1) { compatible = false; break; }
				point[a] = nearestInside((float) ((coordinate[a] + .5 + offset[a]) * h), h / 2, 2 * coordinate[a] + half);
				if (!Float.isFinite(point[a])) { compatible = false; break; }
			}
			if (compatible && !domain.geometry.cutCells.pointInsideSolid(point[0], point[1], point[2], h)) return point;
		}
		return null;
	}

	/** Corrects storage rounding at closed mathematical faces using the actual float classifier. */
	private static float nearestInside(float point, float spacing, int coordinate) {
		if (!Float.isFinite(point)) return Float.NaN;
		while (Math.floor(point / spacing) < coordinate) {
			float next = Math.nextUp(point);
			if (Math.floor(next / spacing) > coordinate) return Float.NaN;
			point = next;
		}
		while (Math.floor(point / spacing) > coordinate) {
			float next = Math.nextDown(point);
			if (Math.floor(next / spacing) < coordinate) return Float.NaN;
			point = next;
		}
		return point;
	}
}
