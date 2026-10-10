package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.units.SignedDyadic;
import java.util.Arrays;

/** Prescribed translation through the default transport and freshly rebuilt classification. */
public final class LiquidHandoffContinuityValidation {
	private static final float[] VELOCITY = {0.5F, 0.375F, 0.25F};
	private LiquidHandoffContinuityValidation() { }

	public static void main(String[] args) {
		if (!CellLiquidVolumeTransport.enabled()) return;
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
