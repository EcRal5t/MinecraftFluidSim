package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.world.MovingBodyStepResult;
import cc.ecisr.mmm.world.MovingBodySurfaceSnapshot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntPredicate;
import java.util.function.IntToDoubleFunction;

/** Runtime-only analytic AABB occupancy and symmetric pressure/body coupling. */
final class MovingBodyBoundary {
	private static final int MAXIMUM_SURFACE_PATCHES = 262_144;
	private static final double GEOMETRY_ULPS = 16.0;

	private record Patch(int body, int cell, double normalX, double normalY, double normalZ,
	                     double area, double normalDistance) { }
	private record ViscousCoupling(int body, int cell, int axis, double conductance) { }
	record ViscousFluidImpulse(double[] cellX, double[] cellY, double[] cellZ,
	                          int substeps, int coupledPairs, double maximumRatePerSecond) { }

	private final MovingBodySurfaceSnapshot snapshot;
	private final int sizeX, sizeY, sizeZ, cellCount;
	private final double cellSize, cellArea, cellVolume;
	private final double[] blockedCellFraction;
	private final double[] blockedFaceX;
	private final double[] blockedFaceY;
	private final double[] blockedFaceZ;
	private final double[] initialVelocityX, initialVelocityY, initialVelocityZ;
	private final double[] velocityX, velocityY, velocityZ;
	private final double[] pressureImpulseX, pressureImpulseY, pressureImpulseZ;
	private final double[] viscousImpulseX, viscousImpulseY, viscousImpulseZ;
	private final double[] displacementWork;
	private final MovingBodySurfaceSnapshot.BoxPose[] previousPose;
	private final MovingBodySurfaceSnapshot.BoxPose[] currentPose;
	private final ArrayList<Patch> patches = new ArrayList<>();

	MovingBodyBoundary(MovingBodySurfaceSnapshot snapshot, int sizeX, int sizeY, int sizeZ,
			double cellSize, double logicalTimeStep, FlipCutCellGeometry staticGeometry) {
		if (snapshot == null || staticGeometry == null || sizeX < 2 || sizeY < 2 || sizeZ < 2
				|| !(Double.isFinite(cellSize) && cellSize > 0.0)
				|| !(Double.isFinite(logicalTimeStep) && logicalTimeStep > 0.0)) {
			throw new IllegalArgumentException("Moving-body boundary layout is invalid");
		}
		this.snapshot = snapshot;
		this.sizeX = sizeX; this.sizeY = sizeY; this.sizeZ = sizeZ;
		this.cellCount = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
		this.cellSize = cellSize;
		this.cellArea = cellSize * cellSize;
		this.cellVolume = cellArea * cellSize;
		blockedCellFraction = new double[cellCount];
		blockedFaceX = new double[Math.multiplyExact(Math.multiplyExact(sizeX + 1, sizeY), sizeZ)];
		blockedFaceY = new double[Math.multiplyExact(Math.multiplyExact(sizeX, sizeY + 1), sizeZ)];
		blockedFaceZ = new double[Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ + 1)];
		int bodies = snapshot.bodies().size();
		initialVelocityX = new double[bodies]; initialVelocityY = new double[bodies]; initialVelocityZ = new double[bodies];
		velocityX = new double[bodies]; velocityY = new double[bodies]; velocityZ = new double[bodies];
		pressureImpulseX = new double[bodies]; pressureImpulseY = new double[bodies]; pressureImpulseZ = new double[bodies];
		viscousImpulseX = new double[bodies]; viscousImpulseY = new double[bodies]; viscousImpulseZ = new double[bodies];
		displacementWork = new double[bodies];
		previousPose = new MovingBodySurfaceSnapshot.BoxPose[bodies];
		currentPose = new MovingBodySurfaceSnapshot.BoxPose[bodies];
		for (int body = 0; body < bodies; body++) {
			MovingBodySurfaceSnapshot.Body definition = snapshot.bodies().get(body);
			requirePoseVelocityConsistency(definition, logicalTimeStep);
			validatePose(definition.beforePose(), staticGeometry);
			validatePose(definition.afterPose(), staticGeometry);
			initialVelocityX[body] = velocityX[body] = definition.velocityXMetresPerSecond();
			initialVelocityY[body] = velocityY[body] = definition.velocityYMetresPerSecond();
			initialVelocityZ[body] = velocityZ[body] = definition.velocityZMetresPerSecond();
		}
	}

	boolean isEmpty() { return snapshot.bodies().isEmpty(); }

	void rebuild(double previousFraction, double currentFraction, FlipCutCellGeometry staticGeometry) {
		if (!(Double.isFinite(previousFraction) && Double.isFinite(currentFraction)
				&& previousFraction >= 0.0 && previousFraction <= currentFraction && currentFraction <= 1.0)) {
			throw new IllegalArgumentException("Moving-body substep fraction is invalid");
		}
		Arrays.fill(blockedCellFraction, 0.0);
		Arrays.fill(blockedFaceX, 0.0);
		Arrays.fill(blockedFaceY, 0.0);
		Arrays.fill(blockedFaceZ, 0.0);
		patches.clear();
		for (int body = 0; body < snapshot.bodies().size(); body++) {
			previousPose[body] = snapshot.bodies().get(body).atFraction(previousFraction).afterPose();
			MovingBodySurfaceSnapshot.BoxPose pose = snapshot.bodies().get(body).atFraction(currentFraction).afterPose();
			currentPose[body] = pose;
			validatePose(pose, staticGeometry);
			accumulateVolume(pose);
			accumulateGridFaceBlockage(pose);
			accumulateSurfacePatches(body, pose);
		}
		for (double fractionBlocked : blockedCellFraction) if (fractionBlocked > 1.0 + 1.0E-12) {
			throw new IllegalStateException("Moving-body occupancy overlaps within a solver cell");
		}
		for (double blocked : blockedFaceX) requireFaceBudget(blocked);
		for (double blocked : blockedFaceY) requireFaceBudget(blocked);
		for (double blocked : blockedFaceZ) requireFaceBudget(blocked);
	}

	double openCellFraction(int cell, double staticOpenFraction) {
		return requireOpen(staticOpenFraction - blockedCellFraction[cell], "cell");
	}

	float xFaceOpen(int x, int y, int z, float staticOpen) {
		return (float) requireOpen(staticOpen - blockedFaceX[xFaceIndex(x, y, z)] / cellArea, "x face");
	}

	float yFaceOpen(int x, int y, int z, float staticOpen) {
		return (float) requireOpen(staticOpen - blockedFaceY[yFaceIndex(x, y, z)] / cellArea, "y face");
	}

	float zFaceOpen(int x, int y, int z, float staticOpen) {
		return (float) requireOpen(staticOpen - blockedFaceZ[zFaceIndex(x, y, z)] / cellArea, "z face");
	}

	double blockedCellFraction(int cell) { return blockedCellFraction[cell]; }

	boolean markerPositionAllowed(double x, double y, double z) {
		for (MovingBodySurfaceSnapshot.BoxPose pose : currentPose) if (pose != null && contains(pose, x, y, z)) return false;
		return true;
	}

	ParticleResolution resolveParticle(double beforeX, double beforeY, double beforeZ,
			double afterX, double afterY, double afterZ,
			double markerVelocityX, double markerVelocityY, double markerVelocityZ) {
		double resolvedX = afterX, resolvedY = afterY, resolvedZ = afterZ;
		double resolvedVelocityX = markerVelocityX, resolvedVelocityY = markerVelocityY,
				resolvedVelocityZ = markerVelocityZ;
		for (int body = 0; body < currentPose.length; body++) {
			MovingBodySurfaceSnapshot.BoxPose start = previousPose[body], end = currentPose[body];
			double startMinX = start.minimumX(), startMinY = start.minimumY(), startMinZ = start.minimumZ();
			double endMinX = end.minimumX(), endMinY = end.minimumY(), endMinZ = end.minimumZ();
			double r0x = beforeX - startMinX, r0y = beforeY - startMinY, r0z = beforeZ - startMinZ;
			double r1x = resolvedX - endMinX, r1y = resolvedY - endMinY, r1z = resolvedZ - endMinZ;
			double extentX = start.maximumX() - start.minimumX();
			double extentY = start.maximumY() - start.minimumY();
			double extentZ = start.maximumZ() - start.minimumZ();
			if (insideBox(r0x, r0y, r0z, extentX, extentY, extentZ)) {
				throw new FlipFluidDomain.MovingBodyBlockedException(
						FlipFluidDomain.MovingBodyBlockedException.Reason.OVERLAP,
						"Moving body begins a substep overlapping a fluid marker");
			}
			Intersection hit = segmentBoxIntersection(r0x, r0y, r0z, r1x, r1y, r1z,
					extentX, extentY, extentZ);
			if (hit == null) continue;
			double dx = r1x - r0x, dy = r1y - r0y, dz = r1z - r0z;
			double hitX = Math.fma(hit.time, dx, r0x), hitY = Math.fma(hit.time, dy, r0y),
					hitZ = Math.fma(hit.time, dz, r0z);
			double remainX = (1.0 - hit.time) * dx, remainY = (1.0 - hit.time) * dy,
					remainZ = (1.0 - hit.time) * dz;
			double inward = remainX * hit.normalX + remainY * hit.normalY + remainZ * hit.normalZ;
			if (inward < 0.0) {
				remainX -= inward * hit.normalX; remainY -= inward * hit.normalY; remainZ -= inward * hit.normalZ;
			}
			double surfaceX = hitX + remainX, surfaceY = hitY + remainY, surfaceZ = hitZ + remainZ;
			if (hit.normalX != 0.0) surfaceX = Math.nextAfter(surfaceX, hit.normalX > 0.0 ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY);
			if (hit.normalY != 0.0) surfaceY = Math.nextAfter(surfaceY, hit.normalY > 0.0 ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY);
			if (hit.normalZ != 0.0) surfaceZ = Math.nextAfter(surfaceZ, hit.normalZ > 0.0 ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY);
			resolvedX = endMinX + surfaceX; resolvedY = endMinY + surfaceY; resolvedZ = endMinZ + surfaceZ;
			double relativeNormalVelocity = (resolvedVelocityX - velocityX[body]) * hit.normalX
					+ (resolvedVelocityY - velocityY[body]) * hit.normalY
					+ (resolvedVelocityZ - velocityZ[body]) * hit.normalZ;
			if (relativeNormalVelocity < 0.0) {
				resolvedVelocityX -= relativeNormalVelocity * hit.normalX;
				resolvedVelocityY -= relativeNormalVelocity * hit.normalY;
				resolvedVelocityZ -= relativeNormalVelocity * hit.normalZ;
			}
		}
		return new ParticleResolution(resolvedX, resolvedY, resolvedZ,
				resolvedVelocityX, resolvedVelocityY, resolvedVelocityZ);
	}

	double targetDivergence(int cell) {
		double value = 0.0;
		for (Patch patch : patches) if (patch.cell == cell) {
			value += patch.area / cellVolume * (patch.normalX * velocityX[patch.body]
					+ patch.normalY * velocityY[patch.body] + patch.normalZ * velocityZ[patch.body]);
		}
		return value;
	}

	void applyKnownGasPressure(IntPredicate fluidCell, IntToDoubleFunction gasGaugePressure,
			double timeStepSeconds) {
		double[] impulseX = new double[velocityX.length], impulseY = new double[velocityX.length],
				impulseZ = new double[velocityX.length];
		for (Patch patch : patches) if (!fluidCell.test(patch.cell)) {
			double pressure = gasGaugePressure.applyAsDouble(patch.cell);
			if (!Double.isFinite(pressure)) throw new IllegalStateException("Moving-body gas pressure is invalid");
			double scale = -pressure * patch.area * timeStepSeconds;
			impulseX[patch.body] += scale * patch.normalX;
			impulseY[patch.body] += scale * patch.normalY;
			impulseZ[patch.body] += scale * patch.normalZ;
		}
		for (int body = 0; body < velocityX.length; body++) applyPressureImpulse(
				body, impulseX[body], impulseY[body], impulseZ[body]);
	}

	void addPressureRhs(float[] rhs, IntPredicate fluidCell, int pressureAnchor,
			double density, double timeStepSeconds) {
		double scale = density / timeStepSeconds;
		for (int cell = 0; cell < cellCount; cell++) if (cell != pressureAnchor && fluidCell.test(cell)) {
			rhs[cell] += (float) (scale * targetDivergence(cell));
		}
	}

	void applyPressureSchur(float[] source, float[] destination, IntPredicate fluidCell,
			int pressureAnchor, double density) {
		double[] qx = new double[velocityX.length], qy = new double[velocityX.length], qz = new double[velocityX.length];
		for (Patch patch : patches) if (patch.cell != pressureAnchor && fluidCell.test(patch.cell)) {
			double qPressure = patch.area / cellVolume * source[patch.cell];
			qx[patch.body] += patch.normalX * qPressure;
			qy[patch.body] += patch.normalY * qPressure;
			qz[patch.body] += patch.normalZ * qPressure;
		}
		for (Patch patch : patches) {
			MovingBodySurfaceSnapshot.Body body = snapshot.bodies().get(patch.body);
			if (body.couplingMode() != MovingBodySurfaceSnapshot.CouplingMode.DYNAMIC
					|| patch.cell == pressureAnchor || !fluidCell.test(patch.cell)) continue;
			double q = patch.area / cellVolume;
			double lowRank = patch.normalX * qx[patch.body] + patch.normalY * qy[patch.body]
					+ patch.normalZ * qz[patch.body];
			destination[patch.cell] += (float) (density * cellVolume / body.massKilograms() * q * lowRank);
		}
	}

	double pressureSchurDiagonal(int cell, IntPredicate fluidCell, double density) {
		if (!fluidCell.test(cell)) return 0.0;
		double diagonal = 0.0;
		for (Patch patch : patches) if (patch.cell == cell) {
			MovingBodySurfaceSnapshot.Body body = snapshot.bodies().get(patch.body);
			if (body.couplingMode() != MovingBodySurfaceSnapshot.CouplingMode.DYNAMIC) continue;
			double q = patch.area / cellVolume;
			diagonal += density * cellVolume / body.massKilograms() * q * q
					* (patch.normalX * patch.normalX + patch.normalY * patch.normalY + patch.normalZ * patch.normalZ);
		}
		return diagonal;
	}

	void settleFluidPressure(float[] pressure, IntPredicate fluidCell, double timeStepSeconds) {
		double[] impulseX = new double[velocityX.length], impulseY = new double[velocityX.length],
				impulseZ = new double[velocityX.length];
		for (Patch patch : patches) if (fluidCell.test(patch.cell)) {
			double scale = -pressure[patch.cell] * patch.area * timeStepSeconds;
			impulseX[patch.body] += scale * patch.normalX;
			impulseY[patch.body] += scale * patch.normalY;
			impulseZ[patch.body] += scale * patch.normalZ;
		}
		for (int body = 0; body < velocityX.length; body++) applyPressureImpulse(
				body, impulseX[body], impulseY[body], impulseZ[body]);
	}

	List<MovingBodyStepResult> results() {
		ArrayList<MovingBodyStepResult> results = new ArrayList<>(snapshot.bodies().size());
		for (int body = 0; body < snapshot.bodies().size(); body++) {
			MovingBodySurfaceSnapshot.Body definition = snapshot.bodies().get(body);
			boolean external = definition.couplingMode() == MovingBodySurfaceSnapshot.CouplingMode.KINEMATIC_EXTERNAL;
			double impulseX = pressureImpulseX[body] + viscousImpulseX[body];
			double impulseY = pressureImpulseY[body] + viscousImpulseY[body];
			double impulseZ = pressureImpulseZ[body] + viscousImpulseZ[body];
			if (external) {
				requireAuditClose(displacementWork[body], impulseX * initialVelocityX[body]
						+ impulseY * initialVelocityY[body] + impulseZ * initialVelocityZ[body],
						"kinematic boundary work");
			} else {
				double mass = definition.massKilograms();
				requireAuditClose(mass * (velocityX[body] - initialVelocityX[body]), impulseX,
						"dynamic body x momentum");
				requireAuditClose(mass * (velocityY[body] - initialVelocityY[body]), impulseY,
						"dynamic body y momentum");
				requireAuditClose(mass * (velocityZ[body] - initialVelocityZ[body]), impulseZ,
						"dynamic body z momentum");
				double kineticChange = 0.5 * mass * ((velocityX[body] * velocityX[body]
						+ velocityY[body] * velocityY[body] + velocityZ[body] * velocityZ[body])
						- (initialVelocityX[body] * initialVelocityX[body]
						+ initialVelocityY[body] * initialVelocityY[body]
						+ initialVelocityZ[body] * initialVelocityZ[body]));
				requireAuditClose(displacementWork[body], kineticChange, "dynamic body work");
			}
			double unacceptedX = external ? pressureImpulseX[body] + viscousImpulseX[body] : 0.0;
			double unacceptedY = external ? pressureImpulseY[body] + viscousImpulseY[body] : 0.0;
			double unacceptedZ = external ? pressureImpulseZ[body] + viscousImpulseZ[body] : 0.0;
			results.add(new MovingBodyStepResult(definition.transientId(), definition.couplingMode(),
					initialVelocityX[body], initialVelocityY[body], initialVelocityZ[body],
					velocityX[body], velocityY[body], velocityZ[body],
					pressureImpulseX[body], pressureImpulseY[body], pressureImpulseZ[body],
					viscousImpulseX[body], viscousImpulseY[body], viscousImpulseZ[body], displacementWork[body],
					unacceptedX, unacceptedY, unacceptedZ));
		}
		return List.copyOf(results);
	}

	ViscousFluidImpulse applyViscosity(double[] cellMass, double[] cellVelocityX,
			double[] cellVelocityY, double[] cellVelocityZ, double[] dynamicViscosity,
			double timeStepSeconds, int maximumSubsteps) {
		if (cellMass.length != cellCount || cellVelocityX.length != cellCount || cellVelocityY.length != cellCount
				|| cellVelocityZ.length != cellCount || dynamicViscosity.length != cellCount
				|| !(Double.isFinite(timeStepSeconds) && timeStepSeconds > 0.0) || maximumSubsteps <= 0) {
			throw new IllegalArgumentException("Moving-body viscosity input is invalid");
		}
		ArrayList<ViscousCoupling> couplings = new ArrayList<>();
		double[][] cellRate = {new double[cellCount], new double[cellCount], new double[cellCount]};
		double[][] bodyRate = {new double[velocityX.length], new double[velocityX.length], new double[velocityX.length]};
		double maximumRate = 0.0;
		for (Patch patch : patches) {
			if (!(cellMass[patch.cell] > 0.0)) continue;
			double mu = dynamicViscosity[patch.cell];
			if (!(Double.isFinite(mu) && mu > 0.0 && Double.isFinite(patch.normalDistance)
					&& patch.normalDistance > 0.0)) {
				throw new IllegalStateException("Moving-body viscous surface state is invalid");
			}
			double conductance = mu * patch.area / patch.normalDistance;
			for (int axis = 0; axis < 3; axis++) {
				double normal = axis == 0 ? patch.normalX : axis == 1 ? patch.normalY : patch.normalZ;
				if (normal != 0.0) continue;
				couplings.add(new ViscousCoupling(patch.body, patch.cell, axis, conductance));
				cellRate[axis][patch.cell] += conductance / cellMass[patch.cell];
				MovingBodySurfaceSnapshot.Body body = snapshot.bodies().get(patch.body);
				if (body.couplingMode() == MovingBodySurfaceSnapshot.CouplingMode.DYNAMIC) {
					bodyRate[axis][patch.body] += conductance / body.massKilograms();
				}
			}
		}
		for (int axis = 0; axis < 3; axis++) {
			for (double rate : cellRate[axis]) maximumRate = Math.max(maximumRate, rate);
			for (double rate : bodyRate[axis]) maximumRate = Math.max(maximumRate, rate);
		}
		double required = Math.ceil(timeStepSeconds * maximumRate);
		if (!Double.isFinite(required) || required > maximumSubsteps) {
			throw new IllegalStateException("Moving-body viscosity exceeds its explicit substep budget");
		}
		int substeps = Math.max(1, (int) required);
		double substep = timeStepSeconds / substeps;
		double[][] fluidVelocity = {cellVelocityX.clone(), cellVelocityY.clone(), cellVelocityZ.clone()};
		double[][] fluidDelta = {new double[cellCount], new double[cellCount], new double[cellCount]};
		double[][] bodyDelta = {new double[velocityX.length], new double[velocityX.length], new double[velocityX.length]};
		double[][] bodyImpulse = {new double[velocityX.length], new double[velocityX.length], new double[velocityX.length]};
		double[][] stepBodyImpulse = {new double[velocityX.length], new double[velocityX.length], new double[velocityX.length]};
		for (int step = 0; step < substeps; step++) {
			for (double[] values : fluidDelta) Arrays.fill(values, 0.0);
			for (double[] values : bodyDelta) Arrays.fill(values, 0.0);
			for (double[] values : stepBodyImpulse) Arrays.fill(values, 0.0);
			for (ViscousCoupling coupling : couplings) {
				double bodyVelocity = bodyVelocity(coupling.body, coupling.axis);
				double impulse = coupling.conductance * (bodyVelocity - fluidVelocity[coupling.axis][coupling.cell]) * substep;
				fluidDelta[coupling.axis][coupling.cell] += impulse / cellMass[coupling.cell];
				bodyImpulse[coupling.axis][coupling.body] -= impulse;
				stepBodyImpulse[coupling.axis][coupling.body] -= impulse;
				MovingBodySurfaceSnapshot.Body body = snapshot.bodies().get(coupling.body);
				if (body.couplingMode() == MovingBodySurfaceSnapshot.CouplingMode.DYNAMIC) {
					bodyDelta[coupling.axis][coupling.body] -= impulse / body.massKilograms();
				}
			}
			for (int axis = 0; axis < 3; axis++) for (int cell = 0; cell < cellCount; cell++) {
				fluidVelocity[axis][cell] += fluidDelta[axis][cell];
			}
			for (int body = 0; body < velocityX.length; body++) {
				double beforeX = velocityX[body], beforeY = velocityY[body], beforeZ = velocityZ[body];
				if (snapshot.bodies().get(body).couplingMode() == MovingBodySurfaceSnapshot.CouplingMode.DYNAMIC) {
					velocityX[body] += bodyDelta[0][body]; velocityY[body] += bodyDelta[1][body];
					velocityZ[body] += bodyDelta[2][body];
					displacementWork[body] += snapshot.bodies().get(body).massKilograms() * 0.5
							* ((velocityX[body] * velocityX[body] + velocityY[body] * velocityY[body]
							+ velocityZ[body] * velocityZ[body])
							- (beforeX * beforeX + beforeY * beforeY + beforeZ * beforeZ));
				} else {
					displacementWork[body] += stepBodyImpulse[0][body] * beforeX
							+ stepBodyImpulse[1][body] * beforeY + stepBodyImpulse[2][body] * beforeZ;
				}
			}
		}
		double[] impulseX = new double[cellCount], impulseY = new double[cellCount], impulseZ = new double[cellCount];
		for (int cell = 0; cell < cellCount; cell++) {
			impulseX[cell] = cellMass[cell] * (fluidVelocity[0][cell] - cellVelocityX[cell]);
			impulseY[cell] = cellMass[cell] * (fluidVelocity[1][cell] - cellVelocityY[cell]);
			impulseZ[cell] = cellMass[cell] * (fluidVelocity[2][cell] - cellVelocityZ[cell]);
		}
		for (int body = 0; body < velocityX.length; body++) {
			viscousImpulseX[body] += bodyImpulse[0][body];
			viscousImpulseY[body] += bodyImpulse[1][body];
			viscousImpulseZ[body] += bodyImpulse[2][body];
		}
		requirePairwiseMomentum(impulseX, bodyImpulse[0], "x");
		requirePairwiseMomentum(impulseY, bodyImpulse[1], "y");
		requirePairwiseMomentum(impulseZ, bodyImpulse[2], "z");
		return new ViscousFluidImpulse(impulseX, impulseY, impulseZ, substeps, couplings.size(), maximumRate);
	}

	private void requirePairwiseMomentum(double[] fluidImpulse, double[] bodyImpulse, String axis) {
		double fluid = 0.0, body = 0.0;
		for (double value : fluidImpulse) fluid += value;
		for (double value : bodyImpulse) body += value;
		requireAuditClose(fluid + body, 0.0, "viscous " + axis + " momentum");
	}

	private static void requireAuditClose(double actual, double expected, String label) {
		double scale = Math.max(Math.abs(actual), Math.abs(expected));
		double tolerance = Math.ulp(scale == 0.0 ? 1.0 : scale) * 128.0;
		if (!(Double.isFinite(actual) && Double.isFinite(expected) && Math.abs(actual - expected) <= tolerance)) {
			throw new FlipFluidDomain.MovingBodyBlockedException(
					FlipFluidDomain.MovingBodyBlockedException.Reason.NUMERICAL,
					"Moving-body " + label + " audit failed: actual=" + actual + ", expected=" + expected);
		}
	}

	private double bodyVelocity(int body, int axis) {
		return axis == 0 ? velocityX[body] : axis == 1 ? velocityY[body] : velocityZ[body];
	}

	private void applyPressureImpulse(int body, double impulseX, double impulseY, double impulseZ) {
		double beforeX = velocityX[body], beforeY = velocityY[body], beforeZ = velocityZ[body];
		pressureImpulseX[body] += impulseX;
		pressureImpulseY[body] += impulseY;
		pressureImpulseZ[body] += impulseZ;
		MovingBodySurfaceSnapshot.Body definition = snapshot.bodies().get(body);
		if (definition.couplingMode() == MovingBodySurfaceSnapshot.CouplingMode.DYNAMIC) {
			velocityX[body] += impulseX / definition.massKilograms();
			velocityY[body] += impulseY / definition.massKilograms();
			velocityZ[body] += impulseZ / definition.massKilograms();
			displacementWork[body] += impulseX * 0.5 * (beforeX + velocityX[body])
					+ impulseY * 0.5 * (beforeY + velocityY[body])
					+ impulseZ * 0.5 * (beforeZ + velocityZ[body]);
		} else {
			displacementWork[body] += impulseX * beforeX + impulseY * beforeY + impulseZ * beforeZ;
		}
	}

	private void requirePoseVelocityConsistency(MovingBodySurfaceSnapshot.Body body, double dt) {
		double dx = body.afterPose().minimumX() - body.beforePose().minimumX();
		double dy = body.afterPose().minimumY() - body.beforePose().minimumY();
		double dz = body.afterPose().minimumZ() - body.beforePose().minimumZ();
		requireClose(dx, body.velocityXMetresPerSecond() * dt, "x displacement");
		requireClose(dy, body.velocityYMetresPerSecond() * dt, "y displacement");
		requireClose(dz, body.velocityZMetresPerSecond() * dt, "z displacement");
	}

	private void validatePose(MovingBodySurfaceSnapshot.BoxPose pose, FlipCutCellGeometry staticGeometry) {
		double maximumX = sizeX * cellSize, maximumY = sizeY * cellSize, maximumZ = sizeZ * cellSize;
		if (!(pose.minimumX() > 0.0 && pose.minimumY() > 0.0 && pose.minimumZ() > 0.0
				&& pose.maximumX() < maximumX && pose.maximumY() < maximumY && pose.maximumZ() < maximumZ)) {
			throw new IllegalArgumentException("Moving body must remain strictly inside the solver domain");
		}
		int minimumCellX = cellFloor(pose.minimumX()), maximumCellX = cellFloor(Math.nextDown(pose.maximumX()));
		int minimumCellY = cellFloor(pose.minimumY()), maximumCellY = cellFloor(Math.nextDown(pose.maximumY()));
		int minimumCellZ = cellFloor(pose.minimumZ()), maximumCellZ = cellFloor(Math.nextDown(pose.maximumZ()));
		for (int z = minimumCellZ; z <= maximumCellZ; z++) for (int y = minimumCellY; y <= maximumCellY; y++) {
			for (int x = minimumCellX; x <= maximumCellX; x++) {
				int mask = staticGeometry.mask(cellIndex(x, y, z));
				for (int microZ = 0; microZ < 2; microZ++) for (int microY = 0; microY < 2; microY++) {
					for (int microX = 0; microX < 2; microX++) {
						int bit = (microZ * 2 + microY) * 2 + microX;
						if ((mask & 1 << bit) == 0) continue;
						double half = cellSize * 0.5;
						double minX = x * cellSize + microX * half, minY = y * cellSize + microY * half,
								minZ = z * cellSize + microZ * half;
						if (overlap(pose.minimumX(), pose.maximumX(), minX, minX + half) > 0.0
								&& overlap(pose.minimumY(), pose.maximumY(), minY, minY + half) > 0.0
								&& overlap(pose.minimumZ(), pose.maximumZ(), minZ, minZ + half) > 0.0) {
							throw new IllegalStateException("Moving body overlaps frozen world cut geometry");
						}
					}
				}
			}
		}
	}

	private void accumulateVolume(MovingBodySurfaceSnapshot.BoxPose pose) {
		forEachOverlappedCell(pose, (x, y, z, minX, maxX, minY, maxY, minZ, maxZ) -> {
			double volume = overlap(pose.minimumX(), pose.maximumX(), minX, maxX)
					* overlap(pose.minimumY(), pose.maximumY(), minY, maxY)
					* overlap(pose.minimumZ(), pose.maximumZ(), minZ, maxZ);
			blockedCellFraction[cellIndex(x, y, z)] += volume / cellVolume;
		});
	}

	private void accumulateGridFaceBlockage(MovingBodySurfaceSnapshot.BoxPose pose) {
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 1; x < sizeX; x++) {
			double plane = x * cellSize;
			if (plane < pose.minimumX() || plane > pose.maximumX()) continue;
			blockedFaceX[xFaceIndex(x, y, z)] += overlap(pose.minimumY(), pose.maximumY(), y * cellSize, (y + 1) * cellSize)
					* overlap(pose.minimumZ(), pose.maximumZ(), z * cellSize, (z + 1) * cellSize);
		}
		for (int z = 0; z < sizeZ; z++) for (int y = 1; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			double plane = y * cellSize;
			if (plane < pose.minimumY() || plane > pose.maximumY()) continue;
			blockedFaceY[yFaceIndex(x, y, z)] += overlap(pose.minimumX(), pose.maximumX(), x * cellSize, (x + 1) * cellSize)
					* overlap(pose.minimumZ(), pose.maximumZ(), z * cellSize, (z + 1) * cellSize);
		}
		for (int z = 1; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			double plane = z * cellSize;
			if (plane < pose.minimumZ() || plane > pose.maximumZ()) continue;
			blockedFaceZ[zFaceIndex(x, y, z)] += overlap(pose.minimumX(), pose.maximumX(), x * cellSize, (x + 1) * cellSize)
					* overlap(pose.minimumY(), pose.maximumY(), y * cellSize, (y + 1) * cellSize);
		}
	}

	private void accumulateSurfacePatches(int body, MovingBodySurfaceSnapshot.BoxPose pose) {
		addFacePatches(body, 0, -1.0, pose.minimumX(), pose.minimumY(), pose.maximumY(), pose.minimumZ(), pose.maximumZ());
		addFacePatches(body, 0, 1.0, pose.maximumX(), pose.minimumY(), pose.maximumY(), pose.minimumZ(), pose.maximumZ());
		addFacePatches(body, 1, -1.0, pose.minimumY(), pose.minimumX(), pose.maximumX(), pose.minimumZ(), pose.maximumZ());
		addFacePatches(body, 1, 1.0, pose.maximumY(), pose.minimumX(), pose.maximumX(), pose.minimumZ(), pose.maximumZ());
		addFacePatches(body, 2, -1.0, pose.minimumZ(), pose.minimumX(), pose.maximumX(), pose.minimumY(), pose.maximumY());
		addFacePatches(body, 2, 1.0, pose.maximumZ(), pose.minimumX(), pose.maximumX(), pose.minimumY(), pose.maximumY());
	}

	private void addFacePatches(int body, int axis, double normal, double plane,
			double firstMinimum, double firstMaximum, double secondMinimum, double secondMaximum) {
		int normalCell = normal < 0.0 ? cellFloor(Math.nextDown(plane)) : cellFloor(plane);
		int firstStart = cellFloor(firstMinimum), firstEnd = cellFloor(Math.nextDown(firstMaximum));
		int secondStart = cellFloor(secondMinimum), secondEnd = cellFloor(Math.nextDown(secondMaximum));
		for (int second = secondStart; second <= secondEnd; second++) for (int first = firstStart; first <= firstEnd; first++) {
			double area = overlap(firstMinimum, firstMaximum, first * cellSize, (first + 1) * cellSize)
					* overlap(secondMinimum, secondMaximum, second * cellSize, (second + 1) * cellSize);
			int x = axis == 0 ? normalCell : first;
			int y = axis == 1 ? normalCell : axis == 0 ? first : second;
			int z = axis == 2 ? normalCell : second;
			if (!inside(x, y, z) || !(area > 0.0)) throw new IllegalStateException("Moving-body surface left the domain");
			if (patches.size() == MAXIMUM_SURFACE_PATCHES) {
				throw new IllegalStateException("Moving-body surface patch budget exhausted");
			}
			double cellMinimum = normalCell * cellSize, cellMaximum = cellMinimum + cellSize;
			double normalDistance = normal < 0.0 ? 0.5 * (plane - cellMinimum)
					: 0.5 * (cellMaximum - plane);
			patches.add(new Patch(body, cellIndex(x, y, z), axis == 0 ? normal : 0.0,
					axis == 1 ? normal : 0.0, axis == 2 ? normal : 0.0, area, normalDistance));
		}
	}

	private void forEachOverlappedCell(MovingBodySurfaceSnapshot.BoxPose pose, CellConsumer consumer) {
		int minX = cellFloor(pose.minimumX()), maxX = cellFloor(Math.nextDown(pose.maximumX()));
		int minY = cellFloor(pose.minimumY()), maxY = cellFloor(Math.nextDown(pose.maximumY()));
		int minZ = cellFloor(pose.minimumZ()), maxZ = cellFloor(Math.nextDown(pose.maximumZ()));
		for (int z = minZ; z <= maxZ; z++) for (int y = minY; y <= maxY; y++) for (int x = minX; x <= maxX; x++) {
			consumer.accept(x, y, z, x * cellSize, (x + 1) * cellSize,
					y * cellSize, (y + 1) * cellSize, z * cellSize, (z + 1) * cellSize);
		}
	}

	private void requireClose(double actual, double expected, String label) {
		double tolerance = GEOMETRY_ULPS * Math.max(Math.ulp(actual), Math.ulp(expected));
		if (Math.abs(actual - expected) > tolerance) {
			throw new IllegalArgumentException("Moving-body " + label + " disagrees with surface velocity");
		}
	}

	private static double overlap(double firstMinimum, double firstMaximum, double secondMinimum, double secondMaximum) {
		return Math.max(0.0, Math.min(firstMaximum, secondMaximum) - Math.max(firstMinimum, secondMinimum));
	}

	private static boolean contains(MovingBodySurfaceSnapshot.BoxPose pose, double x, double y, double z) {
		return x > pose.minimumX() && x < pose.maximumX() && y > pose.minimumY() && y < pose.maximumY()
				&& z > pose.minimumZ() && z < pose.maximumZ();
	}

	private static boolean insideBox(double x, double y, double z, double extentX, double extentY, double extentZ) {
		return x > 0.0 && x < extentX && y > 0.0 && y < extentY && z > 0.0 && z < extentZ;
	}

	private static Intersection segmentBoxIntersection(double x0, double y0, double z0,
			double x1, double y1, double z1, double extentX, double extentY, double extentZ) {
		double[] start = {x0, y0, z0}, direction = {x1 - x0, y1 - y0, z1 - z0}, extent = {extentX, extentY, extentZ};
		double enter = 0.0, exit = 1.0, normalX = 0.0, normalY = 0.0, normalZ = 0.0;
		for (int axis = 0; axis < 3; axis++) {
			if (direction[axis] == 0.0) {
				if (start[axis] < 0.0 || start[axis] > extent[axis]) return null;
				continue;
			}
			double first = (0.0 - start[axis]) / direction[axis];
			double second = (extent[axis] - start[axis]) / direction[axis];
			double near = Math.min(first, second), far = Math.max(first, second);
			if (near > enter) {
				enter = near;
				double normal = direction[axis] > 0.0 ? -1.0 : 1.0;
				normalX = axis == 0 ? normal : 0.0;
				normalY = axis == 1 ? normal : 0.0;
				normalZ = axis == 2 ? normal : 0.0;
			}
			exit = Math.min(exit, far);
			if (enter > exit) return null;
		}
		return enter >= 0.0 && enter <= 1.0 ? new Intersection(enter, normalX, normalY, normalZ) : null;
	}

	private static double requireOpen(double value, String kind) {
		if (value < -1.0E-12 || value > 1.0 + 1.0E-12) {
			throw new IllegalStateException("Moving-body " + kind + " occupancy overlaps frozen geometry");
		}
		return Math.max(0.0, Math.min(1.0, value));
	}

	private void requireFaceBudget(double blocked) {
		if (blocked > cellArea + 1.0E-12) throw new IllegalStateException("Moving bodies overlap on a grid face");
	}

	private int cellFloor(double coordinate) { return (int) Math.floor(coordinate / cellSize); }
	private boolean inside(int x, int y, int z) { return x >= 0 && x < sizeX && y >= 0 && y < sizeY && z >= 0 && z < sizeZ; }
	private int cellIndex(int x, int y, int z) { return (z * sizeY + y) * sizeX + x; }
	private int xFaceIndex(int x, int y, int z) { return (z * sizeY + y) * (sizeX + 1) + x; }
	private int yFaceIndex(int x, int y, int z) { return (z * (sizeY + 1) + y) * sizeX + x; }
	private int zFaceIndex(int x, int y, int z) { return (z * sizeY + y) * sizeX + x; }

	@FunctionalInterface
	private interface CellConsumer {
		void accept(int x, int y, int z, double minX, double maxX, double minY, double maxY, double minZ, double maxZ);
	}

	record ParticleResolution(double x, double y, double z, double velocityX, double velocityY, double velocityZ) { }
	private record Intersection(double time, double normalX, double normalY, double normalZ) { }
}
