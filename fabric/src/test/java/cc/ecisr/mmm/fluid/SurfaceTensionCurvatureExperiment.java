package cc.ecisr.mmm.fluid;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.function.DoubleUnaryOperator;

/**
 * Test-side analytic interface geometry: smoothed-normal reference, exact planar fractions and integrated sphere
 * fractions. Resolved geometry also runs through the production curvature, pressure and inventory transport stages.
 */
final class SurfaceTensionCurvatureExperiment {
	private static final int SUB_SAMPLES = 4;

	private SurfaceTensionCurvatureExperiment() { }

	/** Actual curvature dispatch and one production step on accurately integrated sphere inventories. */
	static void validateResolvedGeometry() {
		validateQuarterCylinderWalls();
		FluidSolverConfig cfg = new FluidSolverConfig(1, 1000, 0, .01F, 800, 1e-7F, .45F, .002F, .8F);
		for (double[] n : new double[][] {{1, 2, 0}, {1, 2, 3}, {1, 1, 1}}) for (double phase : new double[] {.25, .75})
				for (boolean reverse : new boolean[] {false, true}) {
			FluidDomain d = new FluidDomain(20, 20, 20, cfg, 64);
			double offset = 10 * (n[0] + n[1] + n[2]) + phase, maximum = 0;
			for (int z = 0; z < 20; z++) for (int y = 0; y < 20; y++) for (int x = 0; x < 20; x++) {
				double fraction = planeFraction(n, offset - n[0] * x - n[1] * y - n[2] * z);
				d.tension.surfaceTensionColor[d.cellIndex(x, y, z)] = reverse ? 1 - fraction : fraction;
			}
			d.smoothSurfaceTensionColor(); d.rebuildSurfaceTensionNormals();
			for (int z = 6; z < 14; z++) for (int y = 6; y < 14; y++) for (int x = 6; x < 14; x++) {
				double c = d.tension.surfaceTensionColor[d.cellIndex(x, y, z)];
				if (!(c > 0 && c < 1)) continue;
				double k = HeightFunctionCurvature.compute(d, x, y, z);
				check(Double.isFinite(k), "complete plane height column rejected");
				maximum = Math.max(maximum, Math.abs(d.liquidGasMeanCurvature(x, y, z)));
			}
			check(maximum <= 1e-12, "analytic inclined plane gained curvature " + maximum);
		}
		SphereError coarse = resolvedSphere(.25F), fine = resolvedSphere(.125F);
		check(fine.curvature < coarse.curvature, "sphere height curvature did not refine");
		check(coarse.pressure <= .06 && fine.pressure < coarse.pressure, "sphere Laplace pressure did not refine");
		check(fine.momentum < coarse.momentum && fine.angularMomentum < coarse.angularMomentum,
				"closed-sphere capillary impulse approximation did not refine");
	}

	private static SphereError resolvedSphere(float h) {
		int size = Math.round(4 / h);
		double[] center = {2.037, 1.947, 2.071};
		double[] colour = accurateSphereColour(size, h, center, 1);
		double integratedVolume = Arrays.stream(colour).sum() * h * h * h;
		check(Math.abs(integratedVolume - 4 * Math.PI / 3) < 1e-9, "sphere volume integration failed");
		FluidSolverConfig cfg = new FluidSolverConfig(h, 1000, 0, .01F, 800, 1e-7F, .45F, .002F, .8F);
		FluidDomain d = new FluidDomain(size, size, size, cfg, size * size * size * 8);
		List<float[]> points = new ArrayList<>(); double total = 0;
		for (int z = 0; z < size; z++) for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
			float fraction = (float) colour[index(size, x, y, z)]; if (fraction <= 0) continue;
			for (int slot = 0; slot < 8; slot++) {
				float volume = fraction / 8;
				points.add(new float[] {(x + .25F + .5F * (slot & 1)) * h, (y + .25F + .5F * (slot >> 1 & 1)) * h,
						(z + .25F + .5F * (slot >> 2 & 1)) * h, volume}); total += volume;
			}
		}
		float[] pv = new float[points.size() * 4];
		for (int i = 0; i < points.size(); i++) System.arraycopy(points.get(i), 0, pv, 4 * i, 4);
		d.restoreParticleState(new FlipParticleState(size, size, size, h, points.size(), (float) total,
				pv, new float[points.size() * 3], new byte[size * size * size]));
		FluidMaterialRegistry registry = new FluidMaterialRegistry(List.of(new FluidMaterial("mmm:test", 1000, "mmm:test", .0728, Math.PI / 2)));
		for (int p = 0; p < d.particleCount(); p++) d.setMarkerMaterial(p, registry, 0);
		d.particleToGridStage.rebuild();
		double error = 0; int valid = 0, fallback = 0;
		for (int z = 0; z < size; z++) for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
			double c = d.tension.surfaceTensionColor[d.cellIndex(x, y, z)]; if (!(c > 0 && c < 1)) continue;
			double k = HeightFunctionCurvature.compute(d, x, y, z);
			if (Double.isFinite(k)) { error += Math.abs(k - 2); valid++; } else fallback++;
		}
		check(valid > 0, "sphere has no resolved height columns");
		check(HeightFunctionCurvature.compute(d, size / 2, size / 2, size / 2) == 0, "deep liquid curvature must vanish");
		double volume = d.totalVolumeExact(), mass = d.totalMass();
		d.step();
		check(d.totalVolumeExact() == volume && d.totalMass() == mass, "resolved sphere changed exact inventories");
		check(d.particleCount() <= d.cellCount * 8, "resolved sphere carrier scale grew beyond cell quadrature");
		double pressure = 0; int cells = 0;
		for (int c = 0; c < d.cellCount; c++) if (d.projection.pressureFluid[c]) { pressure += d.projection.pressure[c]; cells++; }
		double[] linear = new double[3], angular = new double[3];
		for (int p = 0; p < d.particleCount(); p++) {
			double m = d.unitScale.kilograms(d.particles.mass(p));
			double qx = m * d.particles.velocityX(p), qy = m * d.particles.velocityY(p), qz = m * d.particles.velocityZ(p);
			double x = d.particles.positionX(p) - center[0], y = d.particles.positionY(p) - center[1], z = d.particles.positionZ(p) - center[2];
			linear[0] += qx; linear[1] += qy; linear[2] += qz;
			angular[0] += y * qz - z * qy; angular[1] += z * qx - x * qz; angular[2] += x * qy - y * qx;
		}
		double pressureError = Math.abs(pressure / cells - .1456) / .1456;
		double net = Math.sqrt(Arrays.stream(linear).map(v -> v * v).sum()), torque = Math.sqrt(Arrays.stream(angular).map(v -> v * v).sum());
		System.out.printf("  resolved sphere h=%g HF=%d fallback=%d meanAbsKappaError=%g pressure=%g pressureError=%g netP=%g kg*m/s netL=%g kg*m^2/s markers=%d%n",
				h, valid, fallback, error / valid, pressure / cells, pressureError, net, torque, d.particleCount());
		return new SphereError(error / valid, pressureError, net, torque);
	}

	private record SphereError(double curvature, double pressure, double momentum, double angularMomentum) { }

	/** A circular cylinder meeting exterior free-slip walls orthogonally has kappa=1/R at ends and mirrors. */
	private static void validateQuarterCylinderWalls() {
		double[] coarse = quarterCylinder(.25F, false, Math.PI / 2);
		double[] fine = quarterCylinder(.125F, false, Math.PI / 2);
		double[] upper = quarterCylinder(.125F, true, Math.PI / 2);
		for (int region = 0; region < 3; region++) {
			check(fine[region] < coarse[region], "wall cylinder curvature did not refine in region " + region);
			check(Math.abs(upper[region] - fine[region]) < 1e-10, "upper/lower cylinder reflection differs");
		}
		quarterCylinder(.125F, false, Math.PI / 6);
		quarterCylinder(.125F, false, 2 * Math.PI / 3);
		FluidDomain d = new FluidDomain(12, 12, 12,
				new FluidSolverConfig(.25F, 1000, 0, .01F, 800, 1e-7F, .45F, .002F, .8F), 64);
		Arrays.fill(d.tension.surfaceTensionColor, .5);
		for (boolean high : new boolean[] {false, true}) {
			for (int c = 0; c < d.cellCount; c++) {
				double nx = ((c % d.sizeX) + .5 - (high ? d.sizeX : 0)) / (2 * d.sizeX);
				d.tension.surfaceTensionNormalX[c] = nx;
				d.tension.surfaceTensionNormalY[c] = Math.sqrt(1 - nx * nx);
			}
			int x = high ? d.sizeX - 1 : 0;
			check(Math.abs(d.liquidGasMeanCurvature(x, 6, 6) + 1.0 / 6) < 1e-14,
					"fallback normal reflection lost its normal derivative");
			check(Math.abs(d.surfaceTensionMeanCurvature(x, 6, 6) + 1.0 / 12) < 1e-14,
					"liquid-pair smoothed curvature stencil changed");
		}
	}

	private static double[] quarterCylinder(float h, boolean upper, double angle) {
		int size = Math.round(3 / h);
		FluidDomain d = new FluidDomain(size, size, size,
				new FluidSolverConfig(h, 1000, 0, .01F, 800, 1e-7F, .45F, .002F, .8F), size * size * size * 8);
		double[] colour = new double[d.cellCount]; List<float[]> points = new ArrayList<>(); double total = 0;
		for (int z = 0; z < size; z++) for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
			double a = (upper ? size - 1 - y : y) * (double) h, b = (upper ? size - 1 - z : z) * (double) h;
			double f = a * a + b * b >= 1 ? 0 : (a + h) * (a + h) + (b + h) * (b + h) <= 1 ? 1
					: Math.clamp((circlePrimitive(a + h, b + h, 1) - circlePrimitive(a, b + h, 1)
					- circlePrimitive(a + h, b, 1) + circlePrimitive(a, b, 1)) / (h * h), 0, 1);
			colour[d.cellIndex(x, y, z)] = f;
			if (f > 0) for (int slot = 0; slot < 8; slot++) {
				float v = (float) f / 8; total += v;
				points.add(new float[] {(x + .25F + .5F * (slot & 1)) * h,
						(y + .25F + .5F * (slot >> 1 & 1)) * h, (z + .25F + .5F * (slot >> 2 & 1)) * h, v});
			}
		}
		float[] pv = new float[4 * points.size()];
		for (int p = 0; p < points.size(); p++) System.arraycopy(points.get(p), 0, pv, 4 * p, 4);
		d.restoreParticleState(new FlipParticleState(size, size, size, h, points.size(), (float) total,
				pv, new float[3 * points.size()], new byte[d.cellCount]));
		var registry = new FluidMaterialRegistry(List.of(new FluidMaterial("mmm:wall", 1000, "mmm:wall", .0728, angle)));
		for (int p = 0; p < d.particleCount(); p++) d.setMarkerMaterial(p, registry, 0);
		d.particleToGridStage.rebuild();
		System.arraycopy(colour, 0, d.tension.surfaceTensionColor, 0, colour.length);
		d.smoothSurfaceTensionColor(); d.rebuildSurfaceTensionNormals();
		double[] errors = new double[3]; int[] counts = new int[3], heights = new int[3];
		for (int z = 0; z < size; z++) for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
			double f = colour[d.cellIndex(x, y, z)]; if (!(f > 0 && f < 1)) continue;
			int my = upper ? size - 1 - y : y, mz = upper ? size - 1 - z : z;
			int region = x < 2 || x >= size - 2 ? 1 : my < 2 || mz < 2 ? 2 : 0;
			counts[region]++; if (Double.isFinite(HeightFunctionCurvature.compute(d, x, y, z))) heights[region]++;
			errors[region] += Math.abs(d.liquidGasMeanCurvature(x, y, z) - 1);
			if (angle != Math.PI / 2 && (x == 0 || x == size - 1))
				check(!Double.isFinite(HeightFunctionCurvature.compute(d, x, y, z)), "non-90 wall accepted even height reflection");
		}
		for (int region = 0; region < 3; region++) {
			check(counts[region] > 0, "cylinder region is empty"); errors[region] /= counts[region];
			if (angle == Math.PI / 2 && h == .125F) check(heights[region] == counts[region], "resolved cylinder height rejected");
		}
		System.out.printf("  quarter cylinder h=%g upper=%s angle=%g partial=%s HF=%s core/end/mirror meanAbsKappaError=%s%n",
				h, upper, angle, Arrays.toString(counts), Arrays.toString(heights), Arrays.toString(errors));
		return errors;
	}
	private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }

	/** Smooths the colour function once with a 3x3x3 box kernel so the interface gradient is non-degenerate. */
	static double[] smooth(double[] colour, int size) {
		double[] result = new double[colour.length];
		for (int z = 0; z < size; z++) for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
			double sum = 0.0;
			int count = 0;
			for (int dz = -1; dz <= 1; dz++) for (int dy = -1; dy <= 1; dy++) for (int dx = -1; dx <= 1; dx++) {
				int sx = x + dx, sy = y + dy, sz = z + dz;
				if (sx < 0 || sx >= size || sy < 0 || sy >= size || sz < 0 || sz >= size) continue;
				sum += colour[index(size, sx, sy, sz)];
				count++;
			}
			result[index(size, x, y, z)] = sum / count;
		}
		return result;
	}

	/** Unit interface normal at a cell centre, pointing from gas into liquid. */
	static double[] interfaceNormal(double[] colour, int size, int x, int y, int z, double cellSize) {
		double gradientX = centralDifference(colour, size, x, y, z, 1, 0, 0);
		double gradientY = centralDifference(colour, size, x, y, z, 0, 1, 0);
		double gradientZ = centralDifference(colour, size, x, y, z, 0, 0, 1);
		double length = Math.sqrt(gradientX * gradientX + gradientY * gradientY + gradientZ * gradientZ);
		double epsilon = 1.0E-6 / cellSize;
		if (length <= epsilon) return new double[] {0.0, 0.0, 1.0};
		return new double[] {gradientX / length, gradientY / length, gradientZ / length};
	}

	/**
	 * Mean curvature {@code kappa = -div(n)} at a cell centre. Laplace convention: a liquid sphere surrounded by
	 * gas has positive curvature ({@code 2/R}).
	 */
	static double meanCurvature(double[] colour, int size, int x, int y, int z, double cellSize) {
		double inverseTwoCell = 0.5 / cellSize;
		double nxPlus = interfaceNormal(colour, size, Math.min(size - 1, x + 1), y, z, cellSize)[0];
		double nxMinus = interfaceNormal(colour, size, Math.max(0, x - 1), y, z, cellSize)[0];
		double nyPlus = interfaceNormal(colour, size, x, Math.min(size - 1, y + 1), z, cellSize)[1];
		double nyMinus = interfaceNormal(colour, size, x, Math.max(0, y - 1), z, cellSize)[1];
		double nzPlus = interfaceNormal(colour, size, x, y, Math.min(size - 1, z + 1), cellSize)[2];
		double nzMinus = interfaceNormal(colour, size, x, y, Math.max(0, z - 1), cellSize)[2];
		return -((nxPlus - nxMinus) + (nyPlus - nyMinus) + (nzPlus - nzMinus)) * inverseTwoCell;
	}

	/** Colour function for a liquid sphere of the given radius centred in the grid; 1 inside liquid, 0 in gas. */
	static double[] sphereColour(int size, double radius, double cellSize) {
		double[] colour = new double[size * size * size];
		double center = 0.5 * (size - 1) * cellSize;
		for (int z = 0; z < size; z++) for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
			colour[index(size, x, y, z)] = fraction(x, y, z, cellSize, (sx, sy, sz) -> {
				double dx = sx - center, dy = sy - center, dz = sz - center;
				return dx * dx + dy * dy + dz * dz <= radius * radius;
			});
		}
		return colour;
	}

	/** Colour function for a liquid slab filling z below the interface; 1 inside liquid, 0 in gas. */
	static double[] slabColour(int size, double cellSize, double interfaceZ) {
		double[] colour = new double[size * size * size];
		for (int z = 0; z < size; z++) for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
			colour[index(size, x, y, z)] = fraction(x, y, z, cellSize, (sx, sy, sz) -> sz < interfaceZ);
		}
		return colour;
	}

	/** Exact half-space/box fraction, by the distribution of weighted unit intervals. */
	static double planeFraction(double[] normal, double offset) {
		double[] n = Arrays.stream(normal).filter(v -> v != 0).toArray();
		double sum = 0, denominator = 1;
		for (double v : n) denominator *= v;
		for (int k = 2; k <= n.length; k++) denominator *= k;
		for (int mask = 0; mask < (1 << n.length); mask++) {
			double t = offset;
			for (int k = 0; k < n.length; k++) if ((mask & (1 << k)) != 0) t -= n[k];
			sum += (Integer.bitCount(mask) % 2 == 0 ? 1 : -1) * Math.pow(Math.max(0, t), n.length);
		}
		return Math.clamp(sum / denominator, 0, 1);
	}

	/** Sphere fractions from analytic circle/rectangle sections and adaptive one-dimensional integration. */
	static double[] accurateSphereColour(int size, double h, double[] center, double radius) {
		double[] colour = new double[size * size * size];
		for (int z = 0; z < size; z++) for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
			double[] lo = {x * h - center[0], y * h - center[1], z * h - center[2]};
			double min = 0, max = 0;
			for (double a : lo) {
				double b = a + h, near = a > 0 ? a : b < 0 ? b : 0;
				min += near * near; max += Math.max(a * a, b * b);
			}
			double volume = min >= radius * radius ? 0 : max <= radius * radius ? h * h * h
					: sphereCellVolume(lo, h, radius, 1e-10 * h * h * h);
			colour[index(size, x, y, z)] = Math.clamp(volume / (h * h * h), 0, 1);
		}
		return colour;
	}

	private static double sphereCellVolume(double[] lo, double h, double radius, double tolerance) {
		double a = Math.max(-radius, lo[0]), b = Math.min(radius, lo[0] + h);
		if (a >= b) return 0;
		DoubleUnaryOperator section = x -> {
			double r = Math.sqrt(Math.max(0, radius * radius - x * x));
			return Math.max(0, circlePrimitive(lo[1] + h, lo[2] + h, r)
					- circlePrimitive(lo[1], lo[2] + h, r)
					- circlePrimitive(lo[1] + h, lo[2], r) + circlePrimitive(lo[1], lo[2], r));
		};
		TreeSet<Double> splits = new TreeSet<>(); splits.add(a); splits.add(b);
		for (double y : new double[] {0, lo[1], lo[1] + h}) for (double z : new double[] {0, lo[2], lo[2] + h}) {
			double q = radius * radius - y * y - z * z;
			if (q <= 0) continue;
			double v = Math.sqrt(q);
			if (v > a && v < b) splits.add(v);
			if (-v > a && -v < b) splits.add(-v);
		}
		double sum = 0, last = a;
		for (double next : splits) if (next > last) {
			double fa = section.applyAsDouble(last), fm = section.applyAsDouble(.5 * (last + next)), fb = section.applyAsDouble(next);
			sum += integrateSection(section, last, next, fa, fm, fb, (next - last) * (fa + 4 * fm + fb) / 6,
					tolerance / (splits.size() - 1), 24);
			last = next;
		}
		return Math.clamp(sum, 0, h * h * h);
	}

	private static double integrateSection(DoubleUnaryOperator f, double a, double b, double fa, double fm,
	                                      double fb, double whole, double tolerance, int depth) {
		double m = .5 * (a + b), fl = f.applyAsDouble(.5 * (a + m)), fr = f.applyAsDouble(.5 * (m + b));
		double left = (m - a) * (fa + 4 * fl + fm) / 6, right = (b - m) * (fm + 4 * fr + fb) / 6;
		double delta = left + right - whole;
		if (depth == 0 || Math.abs(delta) <= 15 * tolerance) return left + right + delta / 15;
		return integrateSection(f, a, m, fa, fl, fm, left, tolerance / 2, depth - 1)
				+ integrateSection(f, m, b, fm, fr, fb, right, tolerance / 2, depth - 1);
	}

	private static double circlePrimitive(double y, double z, double r) {
		if (r == 0) return 0;
		double a = Math.min(Math.abs(y), r), b = Math.min(Math.abs(z), r);
		double t = Math.sqrt(Math.max(0, r * r - b * b));
		double q = a <= t ? a * b : b * t + circleArc(a, r) - circleArc(t, r);
		return Math.signum(y) * Math.signum(z) * q;
	}

	private static double circleArc(double a, double r) {
		return .5 * (a * Math.sqrt(Math.max(0, r * r - a * a)) + r * r * Math.asin(Math.clamp(a / r, -1, 1)));
	}

	/** Curvature averaged over the interface band where the smoothed colour is between 0.25 and 0.75. */
	static double interfaceMeanCurvature(double[] colour, int size, double cellSize) {
		double[] smoothed = smooth(colour, size);
		double sum = 0.0;
		int count = 0;
		for (int z = 1; z < size - 1; z++) for (int y = 1; y < size - 1; y++) for (int x = 1; x < size - 1; x++) {
			double value = smoothed[index(size, x, y, z)];
			if (value <= 0.25 || value >= 0.75) continue;
			sum += meanCurvature(smoothed, size, x, y, z, cellSize);
			count++;
		}
		return count == 0 ? 0.0 : sum / count;
	}

	private static double fraction(int x, int y, int z, double cellSize, InsidePredicate predicate) {
		double px = x * cellSize, py = y * cellSize, pz = z * cellSize;
		double hits = 0.0;
		for (int subZ = 0; subZ < SUB_SAMPLES; subZ++) for (int subY = 0; subY < SUB_SAMPLES; subY++) {
			for (int subX = 0; subX < SUB_SAMPLES; subX++) {
				double sx = px + (subX + 0.5) * cellSize / SUB_SAMPLES;
				double sy = py + (subY + 0.5) * cellSize / SUB_SAMPLES;
				double sz = pz + (subZ + 0.5) * cellSize / SUB_SAMPLES;
				if (predicate.inside(sx, sy, sz)) hits += 1.0;
			}
		}
		return hits / (SUB_SAMPLES * SUB_SAMPLES * SUB_SAMPLES);
	}

	private static double centralDifference(double[] colour, int size, int x, int y, int z, int dx, int dy, int dz) {
		int xPlus = Math.min(size - 1, x + dx), xMinus = Math.max(0, x - dx);
		int yPlus = Math.min(size - 1, y + dy), yMinus = Math.max(0, y - dy);
		int zPlus = Math.min(size - 1, z + dz), zMinus = Math.max(0, z - dz);
		return 0.5 * (colour[index(size, xPlus, yPlus, zPlus)] - colour[index(size, xMinus, yMinus, zMinus)]);
	}

	private static int index(int size, int x, int y, int z) {
		return (z * size + y) * size + x;
	}

	@FunctionalInterface private interface InsidePredicate {
		boolean inside(double x, double y, double z);
	}
}
