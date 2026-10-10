package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.units.SignedDyadic;
import java.util.Arrays;
import java.util.List;

/** Prescribed translation through the default transport and freshly rebuilt classification. */
public final class LiquidHandoffContinuityValidation {
	private static final float[] VELOCITY = {0.5F, 0.375F, 0.25F};
	private LiquidHandoffContinuityValidation() { }

	/** Two one-metre water cubes; their two-metre gap closes nominally at t=2 s. */
	private static void opposingWaterCubesJoin() {
		JoinResult coarse = runJoin(0.5F, 0.025F), timeFine = runJoin(0.5F, 0.0125F);
		JoinResult spaceFine = runJoin(0.25F, 0.025F), bothFine = runJoin(0.25F, 0.0125F);
		joinDifference("time", coarse, timeFine); joinDifference("time", spaceFine, bothFine);
		joinDifference("space", coarse, spaceFine); joinDifference("space", timeFine, bothFine);
	}

	private static FluidDomain joiningWater(float h, float dt) {
		int nx = Math.round(12 / h), ny = Math.round(6 / h), count = 16 * Math.round(1 / (h * h * h));
		var domain = new FluidDomain(nx, ny, ny,
				new FluidSolverConfig(h, 1000, 0, dt, 500, 1e-6F, 0.45F, 0.002F, 0.8F), nx * ny * ny * 8);
		float[] points = new float[4 * count], velocities = new float[3 * count]; int p = 0;
		// Aligned boxes preserve the probe's z/y/x/slot order and exact dyadic coordinates.
		for (int z = Math.round(2.5F / h); z < Math.round(3.5F / h); z++)
			for (int y = Math.round(2.5F / h); y < Math.round(3.5F / h); y++)
				for (int x = Math.round(3 / h); x < Math.round(7 / h); x++) {
					if (x >= Math.round(4 / h) && x < Math.round(6 / h)) continue;
					for (int slot = 0; slot < 8; slot++, p++) {
						points[4 * p] = (x + 0.25F + 0.5F * (slot & 1)) * h;
						points[4 * p + 1] = (y + 0.25F + 0.5F * (slot >>> 1 & 1)) * h;
						points[4 * p + 2] = (z + 0.25F + 0.5F * (slot >>> 2 & 1)) * h;
						points[4 * p + 3] = 0.125F; velocities[3 * p] = x * h < 5 ? 0.5F : -0.5F;
					}
				}
		check(p == count, "Aligned join fixture changed its marker count");
		domain.restoreParticleState(new FlipParticleState(nx, ny, ny, h, count, count * 0.125F,
				points, velocities, new byte[domain.cellCount]));
		FluidMaterial builtin = BuiltinFluidMaterials.WATER;
		var water = new FluidMaterial(builtin.id(), builtin.referenceDensity(), builtin.miscibilityGroup(), 0.0728, Math.PI / 2);
		var liquids = new FluidMaterialRegistry(List.of(water));
		for (p = 0; p < count; p++) domain.setMarkerMaterial(p, liquids, 0);
		MaterialRegistry base = BuiltinFluidMaterials.createRegistry();
		var definitions = new MaterialRegistry(base.entries().stream().map(entry -> entry.material().id().equals(water.id())
				? new MaterialRegistry.MaterialEntry(water, entry.viscosity(), entry.thermalExpansion()) : entry).toList(), base.pairTensions());
		domain.bindMaterialDefinitions(definitions);
		check(definitions.dynamicViscosityAt(water.id(), 293.15) == 0.001002, "Water join viscosity differs from its definition");
		System.out.printf("default join fixture h=%g dt=%g duration=2.5 s rho=%g mu=%g declaredWaterSigma=%g propertyTemperature=293.15 K thermalBound=false%n",
				h, dt, water.referenceDensity(), definitions.dynamicViscosityAt(water.id(), 293.15), water.surfaceTension());
		return domain;
	}

	private static JoinResult runJoin(float h, float dt) {
		FluidDomain domain = joiningWater(h, dt); var topology = new JoinTopology(domain);
		SignedDyadic initialV = SignedDyadic.sum(domain.particles.directArrays().volume, domain.particleCount());
		SignedDyadic initialM = SignedDyadic.sum(domain.particles.directArrays().mass, domain.particleCount());
		check(domain.unitScale.cubicMeters(initialV.toDouble()) == 2 && domain.unitScale.kilograms(initialM.toDouble()) == 2000,
				"Two water cubes changed their analytic inventory");
		int steps = Math.round(2.5F / dt), peak = domain.particleCount(), separations = 0, finalJoinStep = steps + 1;
		double firstJoin = Double.NaN, finalJoin = Double.NaN, massBound = 0, massUpper = 2 * initialM.toDouble();
		double minimumLargestV = Double.POSITIVE_INFINITY, maximumTailV = 0;
		boolean previous = false, exactMass = true; JoinObservation observed = null; SignedDyadic massDelta = SignedDyadic.ZERO;
		long stepNanos = 0;
		for (int step = 0; step <= steps; step++) {
			check(initialV.equals(SignedDyadic.sum(domain.particles.directArrays().volume, domain.particleCount())), "Joining water changed exact volume at step " + step);
			massDelta = SignedDyadic.sum(domain.particles.directArrays().mass, domain.particleCount()).subtract(initialM);
			exactMass &= massDelta.signum() == 0;
			check(Math.abs(massDelta.toDouble()) <= massBound, "Joining water exceeded its accumulated mass storage bound");
			check(domain.particleCount() <= domain.maximumParticleCount, "Joining water exceeded its hard carrier budget");
			domain.ensureInventoryGrid(); observed = topology.observe(); peak = Math.max(peak, domain.particleCount());
			// Observe the existing complete logical-step endpoints from nominal contact through the final sample.
			if (step >= Math.round(2 / dt)) { minimumLargestV = Math.min(minimumLargestV, observed.largestV); maximumTailV = Math.max(maximumTailV, observed.tailV); }
			if (step == 0) check(!observed.connected && observed.components >= 2, "Water cubes started connected");
			if (observed.connected && !previous) {
				finalJoinStep = step;
				finalJoin = step * (double) dt;
				if (Double.isNaN(firstJoin)) firstJoin = finalJoin;
			}
			if (previous && !observed.connected) separations++;
			if (previous != observed.connected) System.out.printf("default join event h=%g dt=%g t=%g connected=%s centerWetArea=%g m2 largestV=%g m3 secondV=%g m3%n",
					h, dt, step * (double) dt, observed.connected, observed.centerArea, observed.largestV, observed.secondV);
			previous = observed.connected;
			if (step == steps) break;
			long start = System.nanoTime(); var stats = domain.step(); stepNanos += System.nanoTime() - start;
			// The remap input/output storage bounds and final reseed reduction use the actual hard budget.
			massBound += (stats.substeps() + 1.0) * 2 * domain.maximumParticleCount * Math.ulp(massUpper);
			check(massBound < initialM.toDouble(), "Join mass bound exhausted its total-mass premise");
		}
		check(observed.connected && finalJoinStep <= Math.round(2 / dt), "Major water components did not remain connected from nominal contact through 2.5 seconds");
		Moments end = moments(domain); double scale = h * h * h;
		check(Double.isFinite(end.kinetic + Arrays.stream(end.momentum).sum() + Arrays.stream(end.massCentroid).sum()), "Nonfinite water join mechanics");
		System.out.printf("default join result h=%g dt=%g firstJoin=%g s sustainedFinalJoin=%g s temporaryBridgeEpisodes=%d separationEvents=%d finalMajorConnected=%s largestV=%g m3 secondV=%g m3 tailCount=%d tailV=%g m3 centerWetArea=%g m2 window=2..2.5s logicalStepEnds=true minimumLargestV=%g m3 maximumTailV=%g m3 exactMassAllSteps=%s deltaM=%g kg massStorageBound=%g kg P=%s kg*m/s CM=%s m K=%g J markers=%d peak=%d meanStepMs=%g%n",
				h, dt, firstJoin, finalJoin, separations, separations, observed.connected, observed.largestV, observed.secondV, observed.tails, observed.tailV, observed.centerArea,
				minimumLargestV, maximumTailV, exactMass, domain.unitScale.signedKilograms(massDelta.toDouble()), domain.unitScale.kilograms(massBound),
				Arrays.toString(Arrays.stream(end.momentum).map(value -> value * scale).toArray()), Arrays.toString(end.massCentroid), end.kinetic * scale,
				domain.particleCount(), peak, stepNanos / 1e6 / steps);
		return new JoinResult(h, dt, firstJoin, finalJoin, observed, end, domain.particleCount());
	}

	private static final class JoinTopology {
		private final FluidDomain domain;
		private final int[] component, queue;
		private final double[] volume, leftVolume, rightVolume;
		JoinTopology(FluidDomain domain) {
			this.domain = domain; component = new int[domain.cellCount]; queue = new int[domain.cellCount];
			volume = new double[domain.cellCount]; leftVolume = new double[domain.cellCount]; rightVolume = new double[domain.cellCount];
		}
		JoinObservation observe() {
			Arrays.fill(component, -1); Arrays.fill(volume, 0); Arrays.fill(leftVolume, 0); Arrays.fill(rightVolume, 0);
			int count = 0; double centerArea = 0, h = domain.config.cellSize();
			for (int p = 0; p < domain.particleCount(); p++) {
				int seed = domain.particleCellIndex(p); if (component[seed] >= 0) continue;
				int head = 0, tail = 0; queue[tail++] = seed; component[seed] = count;
				while (head < tail) {
					int cell = queue[head++], x = cell % domain.sizeX, y = cell / domain.sizeX % domain.sizeY, z = cell / (domain.sizeX * domain.sizeY);
					check(domain.markerGrid.exactCellVolumeInventory[cell].compareTo(SignedDyadic.of(1)) <= 0, "Joining water exceeded cell capacity");
					double v = domain.unitScale.cubicMeters(domain.markerGrid.exactCellMarkerVolume[cell]); volume[count] += v;
					if ((x + 0.5) * h < 5) leftVolume[count] += v; else rightVolume[count] += v;
					for (int axis = 0; axis < 3; axis++) for (int sign = -1; sign <= 1; sign += 2) {
						int xx = x + (axis == 0 ? sign : 0), yy = y + (axis == 1 ? sign : 0), zz = z + (axis == 2 ? sign : 0);
						if (!domain.inside(xx, yy, zz)) continue;
						int other = domain.cellIndex(xx, yy, zz); if (domain.markerGrid.exactCellVolumeInventory[other].signum() <= 0) continue;
						double area = domain.pressurePhaseGeometry.sharedLiquidFaceArea(sign > 0 ? cell : other, sign > 0 ? other : cell, axis, domain.geometry.cutCells);
						if (axis == 0 && sign > 0 && (x + 1) * h == 5) centerArea += area * h * h;
						if (area > 0 && component[other] < 0) { component[other] = count; queue[tail++] = other; }
					}
				}
				count++;
			}
			int left = -1, right = -1, tails = 0; double largest = 0, second = 0, tailV = 0;
			for (int c = 0; c < count; c++) {
				if (leftVolume[c] > 0 && (left < 0 || leftVolume[c] > leftVolume[left])) left = c;
				if (rightVolume[c] > 0 && (right < 0 || rightVolume[c] > rightVolume[right])) right = c;
				if (volume[c] > largest) { second = largest; largest = volume[c]; } else if (volume[c] > second) second = volume[c];
			}
			for (int c = 0; c < count; c++) if (c != left && c != right) { tails++; tailV += volume[c]; }
			return new JoinObservation(count, left >= 0 && left == right, largest, second, tails, tailV, centerArea);
		}
	}
	private record JoinObservation(int components, boolean connected, double largestV, double secondV, int tails, double tailV, double centerArea) { }
	private record JoinResult(float h, float dt, double firstJoin, double finalJoin, JoinObservation topology, Moments moments, int markers) { }
	private static void joinDifference(String kind, JoinResult a, JoinResult b) {
		double[] momentum = new double[3], centroid = new double[3]; double av = a.h * a.h * a.h, bv = b.h * b.h * b.h;
		for (int axis = 0; axis < 3; axis++) { momentum[axis] = b.moments.momentum[axis] * bv - a.moments.momentum[axis] * av; centroid[axis] = b.moments.massCentroid[axis] - a.moments.massCentroid[axis]; }
		System.out.printf("default join difference kind=%s a=[h=%g,dt=%g] b=[h=%g,dt=%g] firstJoin=%g s sustainedFinalJoin=%g s P=%s kg*m/s CM=%s m K=%g J largestV=%g m3 tailV=%g m3 tailCount=%d markers=%d%n",
				kind, a.h, a.dt, b.h, b.dt, b.firstJoin - a.firstJoin, b.finalJoin - a.finalJoin, Arrays.toString(momentum), Arrays.toString(centroid),
				b.moments.kinetic * bv - a.moments.kinetic * av, b.topology.largestV - a.topology.largestV, b.topology.tailV - a.topology.tailV, b.topology.tails - a.topology.tails, b.markers - a.markers);
	}

	public static void main(String[] args) {
		if (!CellLiquidVolumeTransport.enabled()) return;
		opposingWaterCubesJoin();
		if (Arrays.asList(args).contains("--join")) {
			System.out.println("Default-path liquid joining validation passed");
			return;
		}
		for (String shape : new String[] {"drop", "thin", "pool"}) {
			Result coarse = run(shape, 0.5F, 0.03125F, 1), timeFine = run(shape, 0.5F, 0.015625F, 1);
			Result spaceFine = run(shape, 0.25F, 0.03125F, 1), bothFine = run(shape, 0.25F, 0.015625F, 1);
			check(coarse.volume == timeFine.volume && coarse.volume == spaceFine.volume && coarse.volume == bothFine.volume,
					"Translation refinement changed its physical liquid inventory");
			if (shape.equals("pool")) {
				check(spaceFine.spatialErrorSquared < coarse.spatialErrorSquared && bothFine.spatialErrorSquared < timeFine.spatialErrorSquared,
						"Grid refinement did not reduce the translated cube's centroid/covariance error");
				check(spaceFine.volumeFieldL1 < coarse.volumeFieldL1 && bothFine.volumeFieldL1 < timeFine.volumeFieldL1,
						"Grid refinement did not reduce the translated cube's cell-volume error");
			} else {
				double rounding = coarse.positionBound + spaceFine.positionBound;
				check(spaceFine.finalCentroidError <= coarse.finalCentroidError + rounding
						&& bothFine.finalCentroidError <= timeFine.finalCentroidError + timeFine.positionBound + bothFine.positionBound,
						"Grid refinement increased the default-path translation centroid error: " + shape);
			}
		}
		Result longCoarse = run("pool", 0.5F, 0.03125F, 8), longFine = run("pool", 0.25F, 0.03125F, 8);
		check(longCoarse.volume == longFine.volume, "Long cube translation refinement changed its physical inventory");
		check(longFine.weightedTailDistance < longCoarse.weightedTailDistance,
				"Grid refinement did not reduce volume-weighted distance outside the translated cube over eight seconds");
		check(longFine.farTailVolumeRatio < longCoarse.farTailVolumeRatio,
				"Grid refinement did not reduce liquid volume more than half a metre outside the translated cube over eight seconds");
		Result switching = run("classification", 0.5F, 0.03125F, 3);
		check(switching.fvToBallistic > 0 && switching.ballisticToFv > 0,
				"Continuous translation did not exercise both directions of the transport handoff");
		wetFaceOpensAndCloses();
		System.out.println("Default-path liquid continuity validation passed");
	}

	/** Existing cut-cell edits change geometric connectivity while carriers remain admissible. */
	private static void wetFaceOpensAndCloses() {
		var domain = new FluidDomain(2, 2, 2, FluidSolverConfig.prototypeDefaults(), 64);
		for (int z = 0; z < 2; z++) for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) {
			domain.setSolidMicroMask(x, y, z, x == 0 ? 0x88 : 0x11);
			domain.setVolume(x, y, z, 0.75F);
		}
		Moments initial = moments(domain); int count = domain.particleCount();
		for (int state = 0; state < 3; state++) {
			if (state > 0) for (int z = 0; z < 2; z++) for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++)
				check(domain.setSolidMicroMask(x, y, z, state == 1 ? 0 : x == 0 ? 0x88 : 0x11) == 0,
						"Wet-face geometry edit displaced an admissible carrier");
			domain.rebuildGridFromParticles(); domain.gridToParticleStage.prepareAdvectionCells();
			double wetArea = 0;
			for (int z = 0; z < 2; z++) for (int y = 0; y < 2; y++) {
				int left = domain.cellIndex(0, y, z), right = domain.cellIndex(1, y, z);
				wetArea += domain.geometry.cutCells.xFaceOpen(1, y, z) * Math.min(
						domain.freeSurfaceGeometry.liquidFaceFraction(left, 1), domain.freeSurfaceGeometry.liquidFaceFraction(right, 0));
			}
			check(state == 1 ? wetArea > 0 : wetArea == 0, "Shared wet-face geometry did not join and separate");
			domain.gridToParticleStage.capturePreProjectionEnergy();
			domain.pressureStage.solve(0.03125F); domain.pressureStage.projectVelocities(0.03125F);
			domain.gridToParticleStage.extrapolateAndTransfer();
			for (float pressure : domain.projection.pressure) check(pressure == 0, "Stationary geometric connectivity introduced pressure work");
			domain.particleAdvectionStage.advect(0.03125F);
			Moments current = moments(domain);
			check(current.volume.equals(initial.volume) && current.mass.equals(initial.mass) && domain.particleCount() == count,
					"Wet-face connectivity changed its stationary carrier inventory or representation");
			check(Arrays.equals(current.centroid, initial.centroid) && Arrays.equals(current.massCentroid, initial.massCentroid)
					&& Arrays.equals(current.covariance, initial.covariance) && current.kinetic == 0,
					"Wet-face connectivity displaced the zero-flux carrier rule");
			System.out.printf("default wet-face state=%d sharedWetArea=%g cellFaceUnits markers=%d V=%s mass=%s%n",
					state, wetArea, count, current.volume, current.mass);
		}
	}

	private record Result(double volume, double finalCentroidError, double positionBound, int fvToBallistic, int ballisticToFv,
			double spatialErrorSquared, double volumeFieldL1, double weightedTailDistance, double outsideVolumeRatio, double farTailVolumeRatio) { }
	private record Moments(SignedDyadic volume, SignedDyadic mass, double[] centroid, double[] massCentroid, double[] covariance, double[] momentum, double kinetic) { }

	private static Result run(String shape, float h, float dt, float duration) {
		int n = Math.round(8 / h), steps = Math.round(duration / dt);
		var domain = new FluidDomain(n, n, n,
				new FluidSolverConfig(h, 1000, 0, dt, 200, 1e-5F, 0.45F, 0.002F, 0.8F), n * n * n * 8);
		if (shape.equals("drop")) {
			int cell = (int) (1.25F / h); domain.setVolume(cell, cell, cell, 0.001953125F / (h * h * h));
			for (int p = 0; p < domain.particleCount(); p++) {
				domain.particles.setPositionX(p, 1.25F); domain.particles.setPositionY(p, 1.25F); domain.particles.setPositionZ(p, 1.25F);
			}
		} else if (shape.equals("classification")) domain.setVolume(2, 2, 2, 0.75F);
		else {
			for (int z = Math.round(1 / h); z < Math.round(2 / h); z++)
				for (int y = Math.round(1 / h); y < (shape.equals("thin") ? Math.round(1 / h) + 1 : Math.round(2 / h)); y++)
					for (int x = Math.round(1 / h); x < Math.round(2 / h); x++) domain.setVolume(x, y, z, shape.equals("thin") ? 0.0625F / h : 1);
			if (shape.equals("thin")) for (int p = 0; p < domain.particleCount(); p++) {
				int cell = domain.particleCellIndex(p);
				domain.particles.setPositionY(p, 1.28125F);
				domain.particles.setPositionZ(p, (cell / (n * n) + 0.5F) * h);
			}
		}
		for (int p = 0; p < domain.particleCount(); p++) {
			domain.particles.setVelocityX(p, VELOCITY[0]); domain.particles.setVelocityY(p, VELOCITY[1]); domain.particles.setVelocityZ(p, VELOCITY[2]);
		}
		Moments initial = moments(domain); double massRounding = 0, maximumCentroid = 0, finalCentroid = 0, covarianceError = 0, kineticRounding = 0;
		double[] maximumAxis = new double[3], maximumMassAxis = new double[3], momentumRounding = new double[3], finalMomentum = new double[3];
		double finalKinetic = 0, finalMassError = 0;
		int initialCount = domain.particleCount(), peak = initialCount, minFv = Integer.MAX_VALUE, maxFv = 0, minBallistic = Integer.MAX_VALUE, maxBallistic = 0;
		int occupiedCount = 0, peakOccupied = 0, maximumPerCell = 0;
		boolean previousFv = false; int fvToBallistic = 0, ballisticToFv = 0, occupiedChanges = 0; boolean[] previousOccupied = null;
		int[] initialMin = {n, n, n}, initialMax = {-1, -1, -1}; boolean[] crossedAxis = new boolean[3];
		long start = System.nanoTime();
		for (int step = 0; step <= steps; step++) {
			domain.rebuildGridFromParticles(); domain.gridToParticleStage.prepareAdvectionCells();
			int fv = 0, ballistic = 0; boolean[] occupied = new boolean[domain.cellCount];
			for (int p = 0; p < domain.particleCount(); p++) {
				int cell = domain.particleCellIndex(p); occupied[cell] = true;
				int[] coordinates = {cell % n, cell / n % n, cell / (n * n)};
				for (int a = 0; a < 3; a++) {
					if (step == 0) { initialMin[a] = Math.min(initialMin[a], coordinates[a]); initialMax[a] = Math.max(initialMax[a], coordinates[a]); }
					else crossedAxis[a] |= coordinates[a] < initialMin[a] || coordinates[a] > initialMax[a];
				}
				float[] point = {domain.particles.positionX(p), domain.particles.positionY(p), domain.particles.positionZ(p)};
				for (float coordinate : point) check(coordinate > h && coordinate < (n - 1) * h,
						"Translation fixture entered a wall-adjacent cell: " + shape + " h=" + h + " dt=" + dt + " step=" + step + " coordinate=" + coordinate);
				if (domain.gridToParticleStage.advectionCells()[cell]) fv++; else ballistic++;
				massRounding += Math.ulp(domain.particles.mass(p));
				float[] velocity = {domain.particles.velocityX(p), domain.particles.velocityY(p), domain.particles.velocityZ(p)};
				for (int a = 0; a < 3; a++) {
					double error = domain.particles.mass(p) * Math.ulp(velocity[a]); momentumRounding[a] += error;
					kineticRounding += error * (Math.abs(velocity[a]) + Math.ulp(velocity[a]));
				}
			}
			minFv = Math.min(minFv, fv); maxFv = Math.max(maxFv, fv); minBallistic = Math.min(minBallistic, ballistic); maxBallistic = Math.max(maxBallistic, ballistic);
			if (step > 0 && (fv > 0) != previousFv) { if (fv > 0) ballisticToFv++; else fvToBallistic++; }
			previousFv = fv > 0;
			if (previousOccupied != null) for (int c = 0; c < occupied.length; c++) if (occupied[c] != previousOccupied[c]) occupiedChanges++;
			previousOccupied = occupied; peak = Math.max(peak, domain.particleCount());
			occupiedCount = 0;
			for (int cell = 0; cell < occupied.length; cell++) if (occupied[cell]) {
				occupiedCount++;
				maximumPerCell = Math.max(maximumPerCell, domain.markerGrid.markerCount[cell]);
				check(domain.markerGrid.exactCellVolumeInventory[cell].compareTo(SignedDyadic.of(1)) <= 0, "Continuous liquid transport exceeded cell capacity");
			}
			peakOccupied = Math.max(peakOccupied, occupiedCount);
			check(domain.particleCount() <= domain.maximumParticleCount, "Continuous liquid transport exceeded its hard carrier budget");
			Moments current = moments(domain);
			check(current.volume.equals(initial.volume), "Continuous default transport changed exact volume");
			check(Math.abs(current.mass.subtract(initial.mass).toDouble()) <= 2 * massRounding,
					"Continuous default transport changed mass beyond accumulated storage roundoff");
			finalMassError = current.mass.subtract(initial.mass).toDouble() * h * h * h;
			finalCentroid = 0;
			for (int a = 0; a < 3; a++) {
				double error = Math.abs(current.centroid[a] - initial.centroid[a] - step * (double) dt * VELOCITY[a]);
				maximumAxis[a] = Math.max(maximumAxis[a], error); finalCentroid = Math.max(finalCentroid, error);
				maximumMassAxis[a] = Math.max(maximumMassAxis[a], Math.abs(current.massCentroid[a] - initial.massCentroid[a] - step * (double) dt * VELOCITY[a]));
				finalMomentum[a] = (current.momentum[a] - initial.momentum[a]) * h * h * h;
				check(Math.abs(current.momentum[a] - initial.momentum[a]) <= momentumRounding[a] + 2 * massRounding * Math.abs(VELOCITY[a]) + 8 * Math.ulp(initial.momentum[a]),
						"Continuous default transport changed linear momentum: " + shape + " h=" + h + " dt=" + dt + " step=" + step + " axis=" + a
						+ " error=" + Math.abs(current.momentum[a] - initial.momentum[a]) + " massUlpBound=" + 2 * massRounding * Math.abs(VELOCITY[a]));
			}
			maximumCentroid = Math.max(maximumCentroid, finalCentroid);
			finalKinetic = (current.kinetic - initial.kinetic) * h * h * h;
			for (int c = 0; c < 6; c++) covarianceError = Math.max(covarianceError, Math.abs(current.covariance[c] - initial.covariance[c]));
			check(Math.abs(current.kinetic - initial.kinetic) <= kineticRounding + massRounding * (VELOCITY[0] * VELOCITY[0] + VELOCITY[1] * VELOCITY[1] + VELOCITY[2] * VELOCITY[2]) + 8 * Math.ulp(initial.kinetic),
					"Uniform default transport changed kinetic energy");
			if (step == steps) break;
			Arrays.fill(domain.mac.velocityX, VELOCITY[0]); Arrays.fill(domain.mac.velocityY, VELOCITY[1]); Arrays.fill(domain.mac.velocityZ, VELOCITY[2]);
			domain.particleAdvectionStage.advect(dt);
			check(domain.particleAdvectionStage.usedConservativeVolume() == (fv > 0), "Default transport did not follow its current carrier classification");
		}
		double positionBound = (steps + 1.0) * 2 * Math.ulp(8.0F), physicalVolume = initial.volume.toDouble() * h * h * h;
		// Each centered coordinate changes by at most 2e; its absolute value is below eight metres.
		if (!shape.equals("pool") && !shape.equals("classification")) check(maximumCentroid <= positionBound && covarianceError <= 32 * positionBound + 4 * positionBound * positionBound,
				"Isolated liquid translation exceeded position storage roundoff");
		check(occupiedChanges > 0, "Three-axis fixture did not cross integration cells");
		for (boolean crossed : crossedAxis) check(crossed, "Prescribed translation did not cross cells on every axis");
		System.out.printf("default continuity shape=%s h=%g dt=%g steps=%d physicalV=%g deltaM=%g kg massStorageBound=%g kg maxVolumeCM=%s m maxMassCM=%s m finalCM=%g m covarianceError=%g m2 deltaP=%s kg*m/s deltaK=%g J markers=%d->%d peak=%d cells=%d peakCells=%d maxPerCell=%d FV=%d..%d ballistic=%d..%d FV->ballistic=%d ballistic->FV=%d occupiedChanges=%d transportRebuildMs=%g%n",
				shape, h, dt, steps, physicalVolume, finalMassError, 2 * massRounding * h * h * h, Arrays.toString(maximumAxis), Arrays.toString(maximumMassAxis), finalCentroid, covarianceError, Arrays.toString(finalMomentum), finalKinetic, initialCount, domain.particleCount(), peak, occupiedCount, peakOccupied, maximumPerCell,
				minFv, maxFv, minBallistic, maxBallistic, fvToBallistic, ballisticToFv, occupiedChanges, (System.nanoTime() - start) / 1e6);
		double[] spatial = shape.equals("pool") ? translatedCubeError(domain, h, duration, physicalVolume) : new double[5];
		return new Result(physicalVolume, finalCentroid, positionBound, fvToBallistic, ballisticToFv, spatial[0], spatial[1], spatial[2], spatial[3], spatial[4]);
	}

	/** Continuous one-metre cube: E^2=|CM-CMref|^2+||Cov-Covref||F^2/L^2 with L=1 metre. */
	private static double[] translatedCubeError(FluidDomain domain, float h, float duration, double physicalVolume) {
		Moments current = moments(domain); double squared = 0, fieldL1 = 0, weightedTailDistance = 0;
		SignedDyadic outsideVolume = SignedDyadic.ZERO, farTailVolume = SignedDyadic.ZERO;
		double[] covarianceError = current.covariance.clone();
		double[] lower = new double[3];
		for (int a = 0; a < 3; a++) {
			lower[a] = 1 + VELOCITY[a] * (double) duration;
			double error = current.centroid[a] - (1.5 + VELOCITY[a] * (double) duration); squared += error * error;
		}
		int component = 0;
		for (int a = 0; a < 3; a++) for (int b = a; b < 3; b++) {
			covarianceError[component] -= a == b ? 1.0 / 12 : 0;
			squared += (a == b ? 1 : 2) * covarianceError[component] * covarianceError[component]; component++;
		}
		for (int cell = 0; cell < domain.cellCount; cell++) {
			int[] coordinates = {cell % domain.sizeX, cell / domain.sizeX % domain.sizeY, cell / (domain.sizeX * domain.sizeY)};
			double analytic = 1;
			for (int a = 0; a < 3; a++) {
				analytic *= Math.max(0, Math.min((coordinates[a] + 1) * (double) h, lower[a] + 1)
						- Math.max(coordinates[a] * (double) h, lower[a])) / h;
			}
			fieldL1 += Math.abs(domain.markerGrid.exactCellMarkerVolume[cell] - analytic) * h * h * h;
		}
		for (int p = 0; p < domain.particleCount(); p++) {
			double[] point = {domain.particles.positionX(p), domain.particles.positionY(p), domain.particles.positionZ(p)};
			double distanceSquared = 0;
			for (int a = 0; a < 3; a++) {
				double distance = Math.max(0, Math.max(lower[a] - point[a], point[a] - lower[a] - 1));
				distanceSquared += distance * distance;
			}
			double distance = Math.sqrt(distanceSquared), volume = domain.particles.volume(p);
			weightedTailDistance += volume * distance;
			if (distance > 0) outsideVolume = outsideVolume.add(SignedDyadic.of(volume));
			if (distance > 0.5) farTailVolume = farTailVolume.add(SignedDyadic.of(volume));
		}
		weightedTailDistance /= current.volume.toDouble();
		double outsideRatio = outsideVolume.toDouble() / current.volume.toDouble(), farTailRatio = farTailVolume.toDouble() / current.volume.toDouble();
		System.out.printf("default pool spatial h=%g duration=%g s E=%g m E2=%g m2 covarianceError(xx,xy,xz,yy,yz,zz)=%s m2 volumeFieldL1/V=%g weightedTailDistance=%g m outsideVolume/V=%g beyondHalfMetreVolume/V=%g%n",
				h, duration, Math.sqrt(squared), squared, Arrays.toString(covarianceError), fieldL1 / physicalVolume, weightedTailDistance, outsideRatio, farTailRatio);
		return new double[] {squared, fieldL1 / physicalVolume, weightedTailDistance, outsideRatio, farTailRatio};
	}

	private static Moments moments(FluidDomain domain) {
		SignedDyadic volume = SignedDyadic.ZERO, mass = SignedDyadic.ZERO; double amount = 0, kinetic = 0;
		double[] centroid = new double[3], massCentroid = new double[3], covariance = new double[6], momentum = new double[3];
		for (int p = 0; p < domain.particleCount(); p++) {
			double v = domain.particles.volume(p), m = domain.particles.mass(p); amount += v;
			volume = volume.add(SignedDyadic.of(v)); mass = mass.add(SignedDyadic.of(m));
			double[] point = {domain.particles.positionX(p), domain.particles.positionY(p), domain.particles.positionZ(p)};
			double[] velocity = {domain.particles.velocityX(p), domain.particles.velocityY(p), domain.particles.velocityZ(p)};
			for (int a = 0; a < 3; a++) { centroid[a] += v * point[a]; massCentroid[a] += m * point[a]; momentum[a] += m * velocity[a]; kinetic += 0.5 * m * velocity[a] * velocity[a]; }
		}
		for (int a = 0; a < 3; a++) { centroid[a] /= amount; massCentroid[a] /= mass.toDouble(); }
		for (int p = 0; p < domain.particleCount(); p++) {
			double[] q = {domain.particles.positionX(p) - centroid[0], domain.particles.positionY(p) - centroid[1], domain.particles.positionZ(p) - centroid[2]};
			int c = 0; for (int a = 0; a < 3; a++) for (int b = a; b < 3; b++) covariance[c++] += domain.particles.volume(p) / amount * q[a] * q[b];
		}
		return new Moments(volume, mass, centroid, massCentroid, covariance, momentum, kinetic);
	}
	private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
