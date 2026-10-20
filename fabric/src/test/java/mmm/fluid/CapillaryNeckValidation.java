package mmm.fluid;

import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

/** Sustained major-end separation in a fixed finite free-slip wall domain. */
public final class CapillaryNeckValidation {
	private static final double R = 1.0 / 64, LENGTH = 9 * R, EPS = .25, RHO = 1000, SIGMA = .0728;
	private static final double TC = Math.sqrt(RHO * R * R * R / SIGMA);
	private static final float BASE_DT = 1F / 256;
	private CapillaryNeckValidation() { }
	public static void main(String[] args) { validate(); }

	static void validate() {
		Result coarse = run(4, BASE_DT), fine = run(8, BASE_DT), timeFine = run(8, BASE_DT / 2);
		check(fine.persistent && timeFine.persistent, "Both fine capillary-neck time resolutions must sustain major-end separation");
		System.out.printf("fixed wall neck comparison coarse=%s fine=%s timeFine=%s fineStartDifference=%g s fineConfirmationDifference=%g s spatialScopeRoverH=4,8 temporalScopeDt=1/256,1/512%n",
				coarse.persistent, fine.persistent, timeFine.persistent, timeFine.start - fine.start, timeFine.confirmation - fine.confirmation);
	}

	private static Result run(int resolution, float dt) {
		long caseStart = System.nanoTime(); FluidDomain d = initialize(resolution, dt); Observer observer = new Observer(d.cellCount);
		SignedDyadic v0 = d.liquidCells.exactVolume();
		SignedDyadic m0 = SignedDyadic.of(d.liquidCells.massUnits(), 0);
		System.out.printf("fixed wall neck initialPhysicalStateHash=%s excludesDt=true cellVolumesMassMaterialAndMacMomentum=true%n", initialHash(d));
		int factor = dt == BASE_DT ? 1 : 2, search = (int) Math.floor(8 * TC / BASE_DT) * factor, confirmation = 32 * factor;
		int limit = search + confirmation, candidateStart = -1, first = -1, episodes = 0, reconnections = 0, longest = 0;
		int peakMarkers = d.liquidCells.physicalSize(), peakCells = 0, peakCellMarkers = 0, completed = 0;
		boolean persistent = false;
		double[] costs = new double[limit]; TreeMap<Integer, Integer> substeps = new TreeMap<>(); var backend = new CpuFluidSolverBackend();
		System.out.printf("fixed wall neck fixture R/h=%d h=%g dt=%g R=%g lambda=%g epsilon=%g sigma=%g mu=.001002 rho=%g transverseWidth=%g tc=%g search=%d confirmation=%d confirmationSeconds=%g thermalBound=false backend=mmm:cpu-flip%n",
				resolution, d.config.cellSize(), dt, R, LENGTH, EPS, SIGMA, RHO, 2.5 * R, TC, search, confirmation, confirmation * (double) dt);
		for (int step = 0; step <= limit; step++) {
			completed = step;
			check(v0.equals(d.liquidCells.exactVolume()), "Neck exact V changed at logical step " + step);
			SignedDyadic mass = SignedDyadic.of(d.liquidCells.massUnits(), 0);

			check(mass.equals(m0),"Neck changed exact integer mass");
			d.domainFlowFields.ensureInventoryGrid(); Topology topology = observer.observe(d); Mechanics mechanics = mechanics(d);
			peakMarkers = Math.max(peakMarkers, d.liquidCells.physicalSize()); peakCells = Math.max(peakCells, topology.occupied);
			peakCellMarkers = Math.max(peakCellMarkers, topology.maxCellMarkers);
			if (step == 0) { check(!topology.separated, "Neck started separated"); observer.report(d, topology, mechanics, "initial", step, dt); }
			if (step == Math.round(1 / dt)) observer.report(d, topology, mechanics, "common1s", step, dt);
			if (topology.separated) {
				if (candidateStart < 0 && step <= search) {
					candidateStart = step; episodes++; if (first < 0) first = step;
					observer.report(d, topology, mechanics, "candidateStart", step, dt);
				}
				if (candidateStart >= 0) { longest = Math.max(longest, step - candidateStart); persistent = step - candidateStart >= confirmation; }
			} else if (candidateStart >= 0) {
				reconnections++;
				System.out.printf("fixed wall neck reconnected R/h=%d dt=%g step=%d candidateStart=%d separatedCompleteSteps=%d%n", resolution, dt, step, candidateStart, step - candidateStart - 1);
				candidateStart = -1;
			}
			if (persistent || step == limit || step >= search && candidateStart < 0) {
				observer.report(d, topology, mechanics, persistent ? "confirmation" : "final", step, dt); break;
			}
			long begin = System.nanoTime();
			var evaluated = backend.evaluate(new FluidStepRequest(step, d.captureAuthoritativeState(), List.of(), false, 0, true));
			var stats = evaluated.stats(); d = evaluated.claimPreparedCpuDomain(); costs[step] = (System.nanoTime() - begin) / 1e6;
			check(d != null, "CPU backend omitted the prepared neck candidate"); substeps.merge(stats.substeps(), 1, Integer::sum);
		}
		cost(resolution, dt, "all", costs, 0, completed); cost(resolution, dt, "first5LogicalSteps", costs, 0, Math.min(5, completed)); cost(resolution, dt, "afterFirst5LogicalSteps", costs, Math.min(5, completed), completed);
		SignedDyadic deltaM = SignedDyadic.of(d.liquidCells.massUnits(), 0).subtract(m0);
		System.out.printf("fixed wall neck result R/h=%d dt=%g persistent=%s firstStep=%d persistentStart=%d confirmationStep=%d episodes=%d reconnections=%d longestCompleteSteps=%d stocks=%d peakStocks=%d peakOccupied=%d peakCellStocks=%d deltaM=%g kg acceptedSubstepHistogram=%s caseWallMs=%g snapshotAndRetriesIncludedInStepCost=true observerExcludedFromStepCost=true discardedAttemptSubstepsUnavailable=true%n",
				resolution, dt, persistent, first, candidateStart, completed, episodes, reconnections, longest, d.liquidCells.physicalSize(), peakMarkers, peakCells, peakCellMarkers,
				deltaM.toDouble()*mmm.units.Mass.kilograms(1), substeps, (System.nanoTime() - caseStart) / 1e6);
		return new Result(persistent, candidateStart < 0 ? Double.NaN : candidateStart * (double) dt, persistent ? completed * (double) dt : Double.NaN);
	}

	private static FluidDomain initialize(int resolution, float dt) {
		float h = (float) (R / resolution); int nx = 9 * resolution, ny = 5 * resolution / 2;
		FluidDomain d = new FluidDomain(nx, ny, ny, new FluidSolverConfig(h, 1000, 0, dt, 800, 1e-7F, .002F, .8F), nx * ny * ny * 8);
		float[] fractions=new float[d.cellCount];double integrated=0;
		for(int z=0;z<ny;z++)for(int y=0;y<ny;y++)for(int x=0;x<nx;x++) {
			double volume=box(x*(double)h,y*(double)h,z*(double)h,h)[0];integrated+=volume;
			fractions[d.domainFlowFields.cellIndex(x,y,z)]=(float)Math.clamp(volume/(h*(double)h*h),0,1);
		}
		var water = new FluidMaterial("mmm:water", RHO, "mmm:aqueous", SIGMA, Math.PI / 2);
		var entry = BuiltinFluidMaterials.createRegistry().entries().stream().filter(e -> e.material().id().equals(water.id())).findFirst().orElseThrow();
		d.bindMaterialRegistry(new FluidMaterialRegistry(List.of(water)));
		d.bindMaterialDefinitions(new MaterialRegistry(List.of(new MaterialRegistry.MaterialEntry(water, entry.viscosity(), entry.thermalExpansion())), List.of()));
		d.setVolumes(fractions);
		check(entry.dynamicViscosityAt(293.15) == .001002, "Neck water viscosity definition changed");
		double analytic = Math.PI * R * R * LENGTH * (1 + EPS * EPS / 2) / 4;
		System.out.printf("fixed wall neck initialization R/h=%d stocks=%d integratedVerror=%g m3 publishedVerror=%g m3%n", resolution, d.liquidCells.physicalSize(), integrated - analytic,
				d.unitScale.cubicMeters(d.liquidCells.exactVolume().toDouble()) - analytic);
		return d;
	}

	private static final class Observer {
		private final int[] parent; private final double[] volume; private final boolean[] left, right;
		Observer(int cells) { parent = new int[cells]; volume = new double[cells]; left = new boolean[cells]; right = new boolean[cells]; }
		Topology observe(FluidDomain d) {
			Arrays.fill(parent, -1); Arrays.fill(volume, 0); Arrays.fill(left, false); Arrays.fill(right, false); int occupied = 0, max = 0;
			SignedDyadic full = SignedDyadic.of(1);
			for (int c = 0; c < d.cellCount; c++) {
				var v = d.liquidGrid.exactCellVolumeInventory[c]; double open = d.domainCollision.effectiveOpenCellVolume(c);
				check(v.compareTo(open == 1 ? full : SignedDyadic.of(open)) <= 0, "Neck exceeded actual open cell capacity");
				if (v.signum() > 0) { parent[c] = c; occupied++; max = Math.max(max, d.liquidGrid.stockCount[c]); }
			}
			for (int c = 0; c < d.cellCount; c++) if (parent[c] >= 0) {
				int x = c % d.sizeX, y = c / d.sizeX % d.sizeY, z = c / (d.sizeX * d.sizeY);
				for (int axis = 0; axis < 3; axis++) {
					int xx = x + (axis == 0 ? 1 : 0), yy = y + (axis == 1 ? 1 : 0), zz = z + (axis == 2 ? 1 : 0);
					if (!d.domainFlowFields.inside(xx, yy, zz)) continue; int next = d.domainFlowFields.cellIndex(xx, yy, zz);
					if (parent[next] >= 0 && d.pressurePhaseGeometry.sharedLiquidFaceArea(c, next, axis, d.geometry.cutCells) > 0) parent[root(next)] = root(c);
				}
			}
			for (int c = 0; c < d.cellCount; c++) if (parent[c] >= 0) {
				int r = root(c); volume[r] += d.unitScale.cubicMeters(d.liquidGrid.exactCellVolumeInventory[c].toDouble()); left[r] |= c % d.sizeX == 0; right[r] |= c % d.sizeX == d.sizeX - 1;
			}
			int count = 0, l = -1, r = -1, first = -1, second = -1;
			for (int c = 0; c < d.cellCount; c++) if (volume[c] > 0) {
				count++; if (left[c] && (l < 0 || volume[c] > volume[l])) l = c; if (right[c] && (r < 0 || volume[c] > volume[r])) r = c;
				if (first < 0 || volume[c] > volume[first]) { second = first; first = c; } else if (second < 0 || volume[c] > volume[second]) second = c;
			}
			boolean separated = l >= 0 && r >= 0 && l != r && (l == first && r == second || l == second && r == first);
			return new Topology(separated, count, occupied, max, first, second, first < 0 ? 0 : volume[first], second < 0 ? 0 : volume[second]);
		}
		int root(int c) { while (parent[c] != c) { parent[c] = parent[parent[c]]; c = parent[c]; } return c; }
		void report(FluidDomain d, Topology t, Mechanics m, String event, int step, float dt) {
			SignedDyadic tailV = SignedDyadic.ZERO, outerV = SignedDyadic.ZERO; long tailMass=0,outerMass=0;
			double totalArea = 0, mainArea = 0, h = d.config.cellSize(); int pressureCells = 0;
			for (int c = 0; c < d.cellCount; c++) if (parent[c] >= 0) {
				int r = root(c), y = c / d.sizeX % d.sizeY, z = c / (d.sizeX * d.sizeY);
				if (r != t.first && r != t.second) tailV = tailV.add(d.liquidGrid.exactCellVolumeInventory[c]);
				if (y == d.sizeY - 1 || z == d.sizeZ - 1) { outerV = outerV.add(d.liquidGrid.exactCellVolumeInventory[c]); if (d.domainFlowFields.isFluid(c)) pressureCells++; }
				double area = (y == d.sizeY - 1 ? wetFace(d, c, 1) : 0) + (z == d.sizeZ - 1 ? wetFace(d, c, 2) : 0);
				totalArea += area * h * h; if (r == t.first) mainArea += area * h * h;
			}
			for(var stock:d.liquidCells.stocks()) {
				int c=stock.key().cell(),r=root(c),y=c/d.sizeX%d.sizeY,z=c/(d.sizeX*d.sizeY);
				if(r!=t.first&&r!=t.second)tailMass=Math.addExact(tailMass,stock.massUnits());
				if(y==d.sizeY-1||z==d.sizeZ-1)outerMass=Math.addExact(outerMass,stock.massUnits());
			}
			var tm=SignedDyadic.of(tailMass,0);var om=SignedDyadic.of(outerMass,0);
			System.out.printf("fixed wall neck sample R/h=%d event=%s step=%d t=%g dt=%g majorEndsSeparated=%s largestV=%g m3 secondV=%g m3 components=%d tailScope=outsideLargestTwo tailV=%g m3 tailM=%g kg P=%s kg*m/s K=%g J CM=%s m outerPressureCellsUnique=%d outerWetAreaTotal=%g m2 outerWetAreaLargest=%g m2 outerExactVRaw=%s outerExactMRaw=%s outerV=%g m3 outerM=%g kg%n",
					Math.round(R / h), event, step, step * (double) dt, dt, t.separated, t.largestV, t.secondV, t.components, d.unitScale.cubicMeters(tailV.toDouble()), mmm.units.Mass.kilograms(tailMass),
					Arrays.toString(m.momentum), m.kinetic, Arrays.toString(m.centroid), pressureCells, totalArea, mainArea, outerV, om, d.unitScale.cubicMeters(outerV.toDouble()), mmm.units.Mass.kilograms(outerMass));
		}
	}

	private static Mechanics mechanics(FluidDomain d) {
		d.domainFlowFields.ensureGrid();double[] cm=new double[3];double mass=0,h=d.config.cellSize();
		for(var stock:d.liquidCells.physicalStocks()) {
			int cell=stock.key().cell();double m=stock.massKilograms();mass+=m;
			cm[0]+=m*(cell%d.sizeX+.5)*h;cm[1]+=m*(cell/d.sizeX%d.sizeY+.5)*h;cm[2]+=m*(cell/(d.sizeX*d.sizeY)+.5)*h;
		}
		double[] momentum={Arrays.stream(d.mac.momentumX).sum(),Arrays.stream(d.mac.momentumY).sum(),Arrays.stream(d.mac.momentumZ).sum()};
		for(int axis=0;axis<3;axis++){cm[axis]/=mass;check(Double.isFinite(cm[axis])&&Double.isFinite(momentum[axis]),"Neck momentum or center became nonfinite");}
		double kinetic=d.liquidKineticEnergyJoules();check(Double.isFinite(kinetic),"Neck kinetic energy became nonfinite");
		return new Mechanics(momentum,cm,kinetic);
	}

	/** Exterior high face clipped by the actual pressure PLIC, with no area tolerance. */
	private static double wetFace(FluidDomain d, int c, int axis) {
		if (d.tension.surfaceTensionColor[c] == 1) return 1; var g = d.pressurePhaseGeometry;
		double a = g.planeNormal(c, (axis + 1) % 3), b = g.planeNormal(c, (axis + 2) % 3), off = g.planeOffset(c) - g.planeNormal(c, axis);
		List<double[]> polygon = new ArrayList<>(); double[][] square = {{0, 0}, {1, 0}, {1, 1}, {0, 1}}; double[] old = square[3]; double previous = a * old[0] + b * old[1] - off;
		for (double[] q : square) { double current = a * q[0] + b * q[1] - off;
			if ((previous <= 0) != (current <= 0)) { double t = previous / (previous - current); polygon.add(new double[] {old[0] + t * (q[0] - old[0]), old[1] + t * (q[1] - old[1])}); }
			if (current <= 0) polygon.add(q); old = q; previous = current;
		}
		double twice = 0; for (int i = 0; i < polygon.size(); i++) { double[] u = polygon.get(i), v = polygon.get((i + 1) % polygon.size()); twice += u[0] * v[1] - u[1] * v[0]; } return Math.abs(twice) / 2;
	}
	private static void cost(int resolution, float dt, String scope, double[] values, int from, int to) {
		int over200 = 0, consecutive = 0, longest = 0;
		for (int i = from; i < to; i++) {
			if (values[i] > 200) { over200++; longest = Math.max(longest, ++consecutive); }
			else consecutive = 0;
		}
		double[] sorted = Arrays.copyOfRange(values, from, to); Arrays.sort(sorted); double sum = Arrays.stream(sorted).sum();
		System.out.printf("fixed wall neck completeStepCost R/h=%d dt=%g scope=%s steps=%d sumMs=%g meanMs=%g p95Ms=%g maxMs=%g over200=%d maxConsecutiveOver200=%d snapshotAndBackendRetriesIncluded=true observerExcluded=true warmPolicy=first5LogicalSteps noJvmColdClaim=true%n", resolution, dt, scope, sorted.length, sum,
				sorted.length == 0 ? Double.NaN : sum / sorted.length, sorted.length == 0 ? Double.NaN : sorted[Math.max(0, (int) Math.ceil(.95 * sorted.length) - 1)], sorted.length == 0 ? Double.NaN : sorted[sorted.length - 1], over200, longest);
	}
	private record Topology(boolean separated, int components, int occupied, int maxCellMarkers, int first, int second, double largestV, double secondV) { }
	private record Mechanics(double[] momentum, double[] centroid, double kinetic) { }
	private record Result(boolean persistent, double start, double confirmation) { }
	private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
	private static String initialHash(FluidDomain d) {
		try {
			var hash=java.security.MessageDigest.getInstance("SHA-256");
			for(var stock:d.liquidCells.stocks())hash.update(stock.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
			var p=d.exportMacMomentum();for(double[] axis:new double[][]{p.copyX(),p.copyY(),p.copyZ()})for(double value:axis)
				hash.update(java.nio.ByteBuffer.allocate(8).putLong(Double.doubleToRawLongBits(value)).array());
			return java.util.HexFormat.of().formatHex(hash.digest());
		}catch(java.security.NoSuchAlgorithmException unavailable){throw new AssertionError(unavailable);}
	}

	// Current-session analytic quarter-circle/box integral of volume and all three first moments.
	private static double radius(double x) { return R * (1 + EPS * Math.cos(2 * Math.PI * x / LENGTH)); }
	private static double[] box(double x, double y, double z, double w) {
		double r0 = radius(x), r1 = radius(x + w), min = Math.min(r0, r1), max = Math.max(r0, r1);
		if (y * y + z * z >= max * max) return new double[4];
		if ((y + w) * (y + w) + (z + w) * (z + w) <= min * min) { double v = w * w * w; return new double[] {v, .5 * v, .5 * v, .5 * v}; }
		double[] a = section(x, x, y, z, w), m = section(x + w / 2, x, y, z, w), b = section(x + w, x, y, z, w);
		return integrate(x, x + w, x, y, z, w, a, m, b, simpson(a, m, b, w), 1e-12 * w * w * w, 20);
	}
	private static double[] section(double at, double x, double y, double z, double w) {
		double r = radius(at), a = primitive(y + w, z + w, r, 0) - primitive(y, z + w, r, 0) - primitive(y + w, z, r, 0) + primitive(y, z, r, 0);
		double my = primitive(y + w, z + w, r, 1) - primitive(y, z + w, r, 1) - primitive(y + w, z, r, 1) + primitive(y, z, r, 1);
		double mz = primitive(z + w, y + w, r, 1) - primitive(z, y + w, r, 1) - primitive(z + w, y, r, 1) + primitive(z, y, r, 1);
		return new double[] {a, a * (at - x) / w, (my - y * a) / w, (mz - z * a) / w};
	}
	private static double primitive(double y, double z, double r, int moment) {
		double a = Math.min(y, r), b = Math.min(z, r), t = Math.sqrt(Math.max(0, r * r - b * b));
		if (moment == 1) return a <= t ? b * a * a / 2 : b * t * t / 2 + (Math.pow(r * r - t * t, 1.5) - Math.pow(Math.max(0, r * r - a * a), 1.5)) / 3;
		return a <= t ? a * b : b * t + arc(a, r) - arc(t, r);
	}
	private static double arc(double a, double r) { return .5 * (a * Math.sqrt(Math.max(0, r * r - a * a)) + r * r * Math.asin(Math.clamp(a / r, -1, 1))); }
	private static double[] simpson(double[] a, double[] m, double[] b, double width) { double[] v = new double[4]; for (int i = 0; i < 4; i++) v[i] = width * (a[i] + 4 * m[i] + b[i]) / 6; return v; }
	private static double[] integrate(double a, double b, double x, double y, double z, double w, double[] fa, double[] fm, double[] fb, double[] whole, double tolerance, int depth) {
		double m = (a + b) / 2; double[] fl = section((a + m) / 2, x, y, z, w), fr = section((m + b) / 2, x, y, z, w), left = simpson(fa, fl, fm, m - a), right = simpson(fm, fr, fb, b - m); double max = 0;
		for (int i = 0; i < 4; i++) max = Math.max(max, Math.abs(left[i] + right[i] - whole[i]));
		if (depth == 0 || max <= 15 * tolerance) { for (int i = 0; i < 4; i++) left[i] += right[i] + (left[i] + right[i] - whole[i]) / 15; return left; }
		left = integrate(a, m, x, y, z, w, fa, fl, fm, left, tolerance / 2, depth - 1); right = integrate(m, b, x, y, z, w, fm, fr, fb, right, tolerance / 2, depth - 1);
		for (int i = 0; i < 4; i++) left[i] += right[i]; return left;
	}
}
