package com.example.fluid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * A bounded three-dimensional PIC/FLIP liquid domain.
 *
 * <p>The update order mirrors the user's Godot compute implementation: P2G,
 * grid forces, pressure projection, G2P, then particle advection with solid
 * collision. Eight marker particles represent one full grid cell. Particles
 * carry volume, so the liquid mass cannot diffuse into a low-density VOF haze.</p>
 */
public final class FlipFluidDomain {
	private static final int PARTICLES_PER_FULL_CELL = 8;
	private static final float PARTICLE_VOLUME = 1.0F / PARTICLES_PER_FULL_CELL;
	private static final float FLIP_RATIO = 0.90F;
	private static final int MAX_PARTICLES = 262_144;

	private final int sizeX;
	private final int sizeY;
	private final int sizeZ;
	private final int cellCount;
	private final FluidSolverConfig config;
	private final boolean[] solid;
	private final int[] markerCount;
	private final float[] volume;
	private final float[] pressure;
	private final float[] divergence;

	private final float[] velocityX;
	private final float[] velocityY;
	private final float[] velocityZ;
	private final float[] p2gVelocityX;
	private final float[] p2gVelocityY;
	private final float[] p2gVelocityZ;
	private final float[] velocityWeightX;
	private final float[] velocityWeightY;
	private final float[] velocityWeightZ;

	private final float[] rhs;
	private final float[] residual;
	private final float[] preconditioned;
	private final float[] direction;
	private final float[] matrixDirection;

	private float[] particleX = new float[16_384];
	private float[] particleY = new float[16_384];
	private float[] particleZ = new float[16_384];
	private float[] particleVelocityX = new float[16_384];
	private float[] particleVelocityY = new float[16_384];
	private float[] particleVelocityZ = new float[16_384];
	private float[] particleVolume = new float[16_384];
	private int particleCount;
	private boolean gridDirty = true;
	private int pressureAnchor = -1;
	private int lastPressureIterations;
	private float lastPressureResidual;

	public FlipFluidDomain(int sizeX, int sizeY, int sizeZ, FluidSolverConfig config) {
		if (sizeX < 2 || sizeY < 2 || sizeZ < 2) throw new IllegalArgumentException("All dimensions must be at least two cells");
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.cellCount = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
		this.config = config;
		this.solid = new boolean[cellCount];
		this.markerCount = new int[cellCount];
		this.volume = new float[cellCount];
		this.pressure = new float[cellCount];
		this.divergence = new float[cellCount];
		this.velocityX = new float[(sizeX + 1) * sizeY * sizeZ];
		this.velocityY = new float[sizeX * (sizeY + 1) * sizeZ];
		this.velocityZ = new float[sizeX * sizeY * (sizeZ + 1)];
		this.p2gVelocityX = new float[velocityX.length];
		this.p2gVelocityY = new float[velocityY.length];
		this.p2gVelocityZ = new float[velocityZ.length];
		this.velocityWeightX = new float[velocityX.length];
		this.velocityWeightY = new float[velocityY.length];
		this.velocityWeightZ = new float[velocityZ.length];
		this.rhs = new float[cellCount];
		this.residual = new float[cellCount];
		this.preconditioned = new float[cellCount];
		this.direction = new float[cellCount];
		this.matrixDirection = new float[cellCount];
	}

	public int sizeX() { return sizeX; }
	public int sizeY() { return sizeY; }
	public int sizeZ() { return sizeZ; }
	public int particleCount() { return particleCount; }
	public FluidSolverConfig config() { return config; }

	public void setSolid(int x, int y, int z, boolean value) {
		int index = cellIndexChecked(x, y, z);
		if (solid[index] == value) return;
		solid[index] = value;
		pressure[index] = 0.0F;
		if (value && removeParticlesInCell(x, y, z, Float.POSITIVE_INFINITY) > 0.0F) gridDirty = true;
		volume[index] = 0.0F;
	}

	public boolean isSolid(int x, int y, int z) { return solid[cellIndexChecked(x, y, z)]; }

	public void setVolume(int x, int y, int z, float value) {
		int index = cellIndexChecked(x, y, z);
		if (solid[index] && value > 0.0F) throw new IllegalStateException("Cannot place fluid in a solid cell");
		removeParticlesInCell(x, y, z, Float.POSITIVE_INFINITY);
		spawnCellVolume(x, y, z, clamp01(value));
		gridDirty = true;
	}

	public float volume(int x, int y, int z) {
		ensureGrid();
		return volume[cellIndexChecked(x, y, z)];
	}

	public float pressure(int x, int y, int z) { return pressure[cellIndexChecked(x, y, z)]; }

	public float totalVolume() {
		double result = 0.0;
		for (int i = 0; i < particleCount; i++) result += particleVolume[i];
		return (float) result;
	}

	public float maximumPressure() {
		float maximum = 0.0F;
		for (float value : pressure) maximum = Math.max(maximum, value);
		return maximum;
	}

	public byte[] copyQuantizedVolume() {
		ensureGrid();
		byte[] result = new byte[cellCount];
		for (int i = 0; i < cellCount; i++) result[i] = (byte) Math.round(clamp01(volume[i]) * 255.0F);
		return result;
	}

	public byte[] copySolidMask() {
		byte[] result = new byte[cellCount];
		for (int i = 0; i < cellCount; i++) result[i] = solid[i] ? (byte) 1 : (byte) 0;
		return result;
	}

	public VolumeDistribution volumeDistribution(float visibleThreshold) {
		ensureGrid();
		int active = 0, visible = 0, dense = 0;
		for (float value : volume) {
			if (value > config.fluidEpsilon()) active++;
			if (value >= visibleThreshold) visible++;
			if (value >= 0.90F) dense++;
		}
		return new VolumeDistribution(active, visible, dense);
	}

	public boolean containsFluidAt(float localX, float localY, float localZ) {
		ensureGrid();
		float h = config.cellSize();
		int x = (int) Math.floor(localX / h), y = (int) Math.floor(localY / h), z = (int) Math.floor(localZ / h);
		if (!inside(x, y, z)) return false;
		int index = cellIndex(x, y, z);
		if (solid[index] || volume[index] <= config.fluidEpsilon()) return false;
		return localY - y * h < clamp01(volume[index]) * h;
	}

	public VelocitySample sampleVelocity(float localX, float localY, float localZ) {
		ensureGrid();
		if (localX < 0.0F || localX > sizeX * config.cellSize()
				|| localY < 0.0F || localY > sizeY * config.cellSize()
				|| localZ < 0.0F || localZ > sizeZ * config.cellSize()) return VelocitySample.ZERO;
		return new VelocitySample(
				sampleX(velocityX, localX, localY, localZ),
				sampleY(velocityY, localX, localY, localZ),
				sampleZ(velocityZ, localX, localY, localZ)
		);
	}

	public float centerOfMassX() {
		double weighted = 0.0, mass = 0.0;
		for (int i = 0; i < particleCount; i++) {
			weighted += particleX[i] / config.cellSize() * particleVolume[i];
			mass += particleVolume[i];
		}
		return mass == 0.0 ? Float.NaN : (float) (weighted / mass);
	}

	public float addVolumeNear(int centerX, int centerY, int centerZ, float amount, int maxRadius) {
		validateVolumeEdit(amount, maxRadius);
		ensureGrid();
		float remaining = amount;
		for (CellCandidate candidate : nearbyCells(centerX, centerY, centerZ, maxRadius)) {
			int index = candidate.index();
			int x = index % sizeX, yz = index / sizeX, y = yz % sizeY, z = yz / sizeY;
			float added = Math.min(remaining, Math.max(0.0F, 1.0F - volume[index]));
			spawnCellVolume(x, y, z, added);
			remaining -= added;
			if (remaining <= 1.0E-6F) break;
		}
		gridDirty = true;
		return amount - remaining;
	}

	public float removeVolumeNear(int centerX, int centerY, int centerZ, float amount, int maxRadius) {
		validateVolumeEdit(amount, maxRadius);
		float remaining = amount;
		for (CellCandidate candidate : nearbyCells(centerX, centerY, centerZ, maxRadius)) {
			int index = candidate.index();
			int x = index % sizeX, yz = index / sizeX, y = yz % sizeY, z = yz / sizeY;
			remaining -= removeParticlesInCell(x, y, z, remaining);
			if (remaining <= 1.0E-6F) break;
		}
		gridDirty = true;
		return amount - remaining;
	}

	public StepStats step() {
		float before = totalVolume();
		int substeps = calculateSubsteps(config.timeStep());
		float dt = config.timeStep() / substeps;
		int pressureIterations = 0;
		for (int substep = 0; substep < substeps; substep++) {
			rebuildGridFromParticles();
			System.arraycopy(velocityX, 0, p2gVelocityX, 0, velocityX.length);
			System.arraycopy(velocityY, 0, p2gVelocityY, 0, velocityY.length);
			System.arraycopy(velocityZ, 0, p2gVelocityZ, 0, velocityZ.length);
			applyGravity(dt);
			enforceSolidFaceVelocities();
			solvePressure(dt);
			pressureIterations += lastPressureIterations;
			projectVelocity(dt);
			enforceSolidFaceVelocities();
			gridToParticles();
			advectParticles(dt);
		}
		rebuildGridFromParticles();
		return new StepStats(substeps, pressureIterations, lastPressureResidual, before, totalVolume());
	}

	private void rebuildGridFromParticles() {
		Arrays.fill(volume, 0.0F);
		Arrays.fill(markerCount, 0);
		Arrays.fill(velocityX, 0.0F);
		Arrays.fill(velocityY, 0.0F);
		Arrays.fill(velocityZ, 0.0F);
		Arrays.fill(velocityWeightX, 0.0F);
		Arrays.fill(velocityWeightY, 0.0F);
		Arrays.fill(velocityWeightZ, 0.0F);
		for (int i = 0; i < particleCount; i++) {
			markerCount[particleCellIndex(i)]++;
			splatCenter(particleX[i], particleY[i], particleZ[i], particleVolume[i]);
			splatFaceField(velocityX, velocityWeightX, 0, particleX[i], particleY[i], particleZ[i], particleVelocityX[i], particleVolume[i]);
			splatFaceField(velocityY, velocityWeightY, 1, particleX[i], particleY[i], particleZ[i], particleVelocityY[i], particleVolume[i]);
			splatFaceField(velocityZ, velocityWeightZ, 2, particleX[i], particleY[i], particleZ[i], particleVelocityZ[i], particleVolume[i]);
		}
		normalize(velocityX, velocityWeightX);
		normalize(velocityY, velocityWeightY);
		normalize(velocityZ, velocityWeightZ);
		enforceSolidFaceVelocities();
		gridDirty = false;
	}

	private int particleCellIndex(int particle) {
		float h = config.cellSize();
		int x = Math.min(sizeX - 1, Math.max(0, (int) Math.floor(particleX[particle] / h)));
		int y = Math.min(sizeY - 1, Math.max(0, (int) Math.floor(particleY[particle] / h)));
		int z = Math.min(sizeZ - 1, Math.max(0, (int) Math.floor(particleZ[particle] / h)));
		return cellIndex(x, y, z);
	}

	private void splatCenter(float px, float py, float pz, float particleAmount) {
		float h = config.cellSize();
		float gx = px / h - 0.5F, gy = py / h - 0.5F, gz = pz / h - 0.5F;
		int x0 = (int) Math.floor(gx), y0 = (int) Math.floor(gy), z0 = (int) Math.floor(gz);
		float fx = gx - x0, fy = gy - y0, fz = gz - z0;
		float validWeight = 0.0F;
		for (int oz = 0; oz <= 1; oz++) for (int oy = 0; oy <= 1; oy++) for (int ox = 0; ox <= 1; ox++) {
			int x = x0 + ox, y = y0 + oy, z = z0 + oz;
			if (!inside(x, y, z) || solid[cellIndex(x, y, z)]) continue;
			validWeight += trilinearWeight(ox, oy, oz, fx, fy, fz);
		}
		if (validWeight <= 1.0E-8F) return;
		float scale = particleAmount / validWeight;
		for (int oz = 0; oz <= 1; oz++) for (int oy = 0; oy <= 1; oy++) for (int ox = 0; ox <= 1; ox++) {
			int x = x0 + ox, y = y0 + oy, z = z0 + oz;
			if (!inside(x, y, z)) continue;
			int index = cellIndex(x, y, z);
			if (!solid[index]) volume[index] += scale * trilinearWeight(ox, oy, oz, fx, fy, fz);
		}
	}

	private static float trilinearWeight(int ox, int oy, int oz, float fx, float fy, float fz) {
		return (ox == 0 ? 1.0F - fx : fx) * (oy == 0 ? 1.0F - fy : fy) * (oz == 0 ? 1.0F - fz : fz);
	}

	private void splatFaceField(float[] sums, float[] weights, int axis, float px, float py, float pz, float value, float particleAmount) {
		float h = config.cellSize();
		int nx = axis == 0 ? sizeX + 1 : sizeX;
		int ny = axis == 1 ? sizeY + 1 : sizeY;
		int nz = axis == 2 ? sizeZ + 1 : sizeZ;
		float gx = px / h - (axis == 0 ? 0.0F : 0.5F);
		float gy = py / h - (axis == 1 ? 0.0F : 0.5F);
		float gz = pz / h - (axis == 2 ? 0.0F : 0.5F);
		splatTrilinear(gx, gy, gz, nx, ny, nz, (index, weight) -> {
			float weighted = weight * particleAmount;
			sums[index] += value * weighted;
			weights[index] += weighted;
		});
	}

	private static void splatTrilinear(float gx, float gy, float gz, int nx, int ny, int nz, SplatConsumer consumer) {
		int x0 = (int) Math.floor(gx), y0 = (int) Math.floor(gy), z0 = (int) Math.floor(gz);
		float fx = gx - x0, fy = gy - y0, fz = gz - z0;
		for (int oz = 0; oz <= 1; oz++) for (int oy = 0; oy <= 1; oy++) for (int ox = 0; ox <= 1; ox++) {
			int x = x0 + ox, y = y0 + oy, z = z0 + oz;
			if (x < 0 || x >= nx || y < 0 || y >= ny || z < 0 || z >= nz) continue;
			float weight = (ox == 0 ? 1.0F - fx : fx) * (oy == 0 ? 1.0F - fy : fy) * (oz == 0 ? 1.0F - fz : fz);
			consumer.accept((z * ny + y) * nx + x, weight);
		}
	}

	private static void normalize(float[] values, float[] weights) {
		for (int i = 0; i < values.length; i++) values[i] = weights[i] > 1.0E-8F ? values[i] / weights[i] : 0.0F;
	}

	private int calculateSubsteps(float dt) {
		float maximum = 0.0F;
		for (int i = 0; i < particleCount; i++) {
			maximum = Math.max(maximum, Math.abs(particleVelocityX[i]) + Math.abs(particleVelocityY[i]) + Math.abs(particleVelocityZ[i]));
		}
		maximum += config.gravity() * dt;
		return Math.max(1, (int) Math.ceil(maximum * dt / (config.cellSize() * config.maxCourantNumber())));
	}

	private void applyGravity(float dt) {
		float impulse = config.gravity() * dt;
		for (int z = 0; z < sizeZ; z++) for (int y = 1; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int below = cellIndex(x, y - 1, z), above = cellIndex(x, y, z);
			if (faceActive(below, above)) velocityY[yFaceIndex(x, y, z)] -= impulse;
		}
	}

	private void solvePressure(float dt) {
		Arrays.fill(pressure, 0.0F);
		Arrays.fill(rhs, 0.0F);
		pressureAnchor = findPressureAnchor();
		float inverseCell = 1.0F / config.cellSize();
		float rhsScale = -config.density() / dt;
		int fluidCells = 0;
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int index = cellIndex(x, y, z);
			if (!isFluid(index) || index == pressureAnchor) { divergence[index] = 0.0F; continue; }
			float div = (velocityX[xFaceIndex(x + 1, y, z)] - velocityX[xFaceIndex(x, y, z)]
					+ velocityY[yFaceIndex(x, y + 1, z)] - velocityY[yFaceIndex(x, y, z)]
					+ velocityZ[zFaceIndex(x, y, z + 1)] - velocityZ[zFaceIndex(x, y, z)]) * inverseCell;
			divergence[index] = div;
			rhs[index] = rhsScale * div;
			fluidCells++;
		}
		if (fluidCells == 0) { lastPressureIterations = 0; lastPressureResidual = 0.0F; return; }
		System.arraycopy(rhs, 0, residual, 0, cellCount);
		applyPreconditioner(residual, preconditioned);
		System.arraycopy(preconditioned, 0, direction, 0, cellCount);
		double rz = dot(residual, preconditioned);
		double target = config.pressureTolerance() * Math.max(1.0, Math.sqrt(dot(rhs, rhs) / fluidCells));
		lastPressureIterations = 0;
		for (int iteration = 0; iteration < config.maxPressureIterations(); iteration++) {
			applyPressureMatrix(direction, matrixDirection);
			double denominator = dot(direction, matrixDirection);
			if (!(denominator > 1.0E-20)) break;
			float alpha = (float) (rz / denominator);
			for (int i = 0; i < cellCount; i++) { pressure[i] += alpha * direction[i]; residual[i] -= alpha * matrixDirection[i]; }
			lastPressureIterations = iteration + 1;
			if (Math.sqrt(dot(residual, residual) / fluidCells) <= target) break;
			applyPreconditioner(residual, preconditioned);
			double nextRz = dot(residual, preconditioned);
			if (!(Math.abs(rz) > 1.0E-30)) break;
			float beta = (float) (nextRz / rz);
			for (int i = 0; i < cellCount; i++) direction[i] = preconditioned[i] + beta * direction[i];
			rz = nextRz;
		}
		lastPressureResidual = (float) Math.sqrt(dot(residual, residual) / fluidCells);
	}

	private void applyPressureMatrix(float[] source, float[] destination) {
		Arrays.fill(destination, 0.0F);
		float inverseCellSquared = 1.0F / (config.cellSize() * config.cellSize());
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int index = cellIndex(x, y, z);
			if (!isFluid(index) || index == pressureAnchor) continue;
			float value = 0.0F;
			int diagonal = 0;
			if (x > 0) { diagonal += faceDiagonal(cellIndex(x - 1, y, z)); value -= neighborValue(cellIndex(x - 1, y, z), source); }
			if (x + 1 < sizeX) { diagonal += faceDiagonal(cellIndex(x + 1, y, z)); value -= neighborValue(cellIndex(x + 1, y, z), source); }
			if (y > 0) { diagonal += faceDiagonal(cellIndex(x, y - 1, z)); value -= neighborValue(cellIndex(x, y - 1, z), source); }
			if (y + 1 < sizeY) { diagonal += faceDiagonal(cellIndex(x, y + 1, z)); value -= neighborValue(cellIndex(x, y + 1, z), source); }
			if (z > 0) { diagonal += faceDiagonal(cellIndex(x, y, z - 1)); value -= neighborValue(cellIndex(x, y, z - 1), source); }
			if (z + 1 < sizeZ) { diagonal += faceDiagonal(cellIndex(x, y, z + 1)); value -= neighborValue(cellIndex(x, y, z + 1), source); }
			destination[index] = (diagonal * source[index] + value) * inverseCellSquared;
		}
	}

	private int faceDiagonal(int neighbor) { return solid[neighbor] ? 0 : 1; }
	private float neighborValue(int neighbor, float[] source) { return isFluid(neighbor) && neighbor != pressureAnchor ? source[neighbor] : 0.0F; }

	private void applyPreconditioner(float[] source, float[] destination) {
		float cellSquared = config.cellSize() * config.cellSize();
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int index = cellIndex(x, y, z);
			if (!isFluid(index) || index == pressureAnchor) { destination[index] = 0.0F; continue; }
			int diagonal = 0;
			if (x > 0 && !solid[cellIndex(x - 1, y, z)]) diagonal++;
			if (x + 1 < sizeX && !solid[cellIndex(x + 1, y, z)]) diagonal++;
			if (y > 0 && !solid[cellIndex(x, y - 1, z)]) diagonal++;
			if (y + 1 < sizeY && !solid[cellIndex(x, y + 1, z)]) diagonal++;
			if (z > 0 && !solid[cellIndex(x, y, z - 1)]) diagonal++;
			if (z + 1 < sizeZ && !solid[cellIndex(x, y, z + 1)]) diagonal++;
			destination[index] = diagonal == 0 ? 0.0F : source[index] * cellSquared / diagonal;
		}
	}

	private int findPressureAnchor() {
		int firstFluid = -1;
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int index = cellIndex(x, y, z);
			if (!isFluid(index)) continue;
			if (firstFluid < 0) firstFluid = index;
			if (hasAirNeighbor(x, y, z)) return -1;
		}
		return firstFluid;
	}

	private boolean hasAirNeighbor(int x, int y, int z) {
		return (x > 0 && isAir(cellIndex(x - 1, y, z))) || (x + 1 < sizeX && isAir(cellIndex(x + 1, y, z)))
				|| (y > 0 && isAir(cellIndex(x, y - 1, z))) || (y + 1 < sizeY && isAir(cellIndex(x, y + 1, z)))
				|| (z > 0 && isAir(cellIndex(x, y, z - 1))) || (z + 1 < sizeZ && isAir(cellIndex(x, y, z + 1)));
	}

	private void projectVelocity(float dt) {
		float scale = dt / (config.density() * config.cellSize());
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 1; x < sizeX; x++) {
			int left = cellIndex(x - 1, y, z), right = cellIndex(x, y, z), face = xFaceIndex(x, y, z);
			if (!faceActive(left, right)) velocityX[face] = 0.0F;
			else velocityX[face] -= scale * (fluidPressure(right) - fluidPressure(left));
		}
		for (int z = 0; z < sizeZ; z++) for (int y = 1; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int below = cellIndex(x, y - 1, z), above = cellIndex(x, y, z), face = yFaceIndex(x, y, z);
			if (!faceActive(below, above)) velocityY[face] = 0.0F;
			else velocityY[face] -= scale * (fluidPressure(above) - fluidPressure(below));
		}
		for (int z = 1; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int back = cellIndex(x, y, z - 1), front = cellIndex(x, y, z), face = zFaceIndex(x, y, z);
			if (!faceActive(back, front)) velocityZ[face] = 0.0F;
			else velocityZ[face] -= scale * (fluidPressure(front) - fluidPressure(back));
		}
	}

	private void gridToParticles() {
		for (int i = 0; i < particleCount; i++) {
			float x = particleX[i], y = particleY[i], z = particleZ[i];
			float picX = sampleX(velocityX, x, y, z), picY = sampleY(velocityY, x, y, z), picZ = sampleZ(velocityZ, x, y, z);
			float deltaX = picX - sampleX(p2gVelocityX, x, y, z);
			float deltaY = picY - sampleY(p2gVelocityY, x, y, z);
			float deltaZ = picZ - sampleZ(p2gVelocityZ, x, y, z);
			particleVelocityX[i] = lerp(picX, particleVelocityX[i] + deltaX, FLIP_RATIO);
			particleVelocityY[i] = lerp(picY, particleVelocityY[i] + deltaY, FLIP_RATIO);
			particleVelocityZ[i] = lerp(picZ, particleVelocityZ[i] + deltaZ, FLIP_RATIO);
		}
	}

	private void advectParticles(float dt) {
		float h = config.cellSize(), radius = h * 0.45F;
		float minX = radius, minY = radius, minZ = radius;
		float maxX = sizeX * h - radius, maxY = sizeY * h - radius, maxZ = sizeZ * h - radius;
		for (int i = 0; i < particleCount; i++) {
			float nx = clamp(particleX[i] + particleVelocityX[i] * dt, minX, maxX);
			if (!centerInSolid(nx, particleY[i], particleZ[i])) particleX[i] = nx; else particleVelocityX[i] = 0.0F;
			float ny = clamp(particleY[i] + particleVelocityY[i] * dt, minY, maxY);
			if (!centerInSolid(particleX[i], ny, particleZ[i])) particleY[i] = ny; else particleVelocityY[i] = 0.0F;
			float nz = clamp(particleZ[i] + particleVelocityZ[i] * dt, minZ, maxZ);
			if (!centerInSolid(particleX[i], particleY[i], nz)) particleZ[i] = nz; else particleVelocityZ[i] = 0.0F;
			constrainToSolidFaces(i, radius);
		}
		gridDirty = true;
	}

	private boolean centerInSolid(float px, float py, float pz) {
		float h = config.cellSize();
		int x = Math.min(sizeX - 1, Math.max(0, (int) Math.floor(px / h)));
		int y = Math.min(sizeY - 1, Math.max(0, (int) Math.floor(py / h)));
		int z = Math.min(sizeZ - 1, Math.max(0, (int) Math.floor(pz / h)));
		return solid[cellIndex(x, y, z)];
	}

	/**
	 * Mirrors the Godot CCD contact pass: constrain only the coordinate normal
	 * to a neighboring solid face. Tangential motion remains free, including
	 * along a wall edge or inside a corner.
	 */
	private void constrainToSolidFaces(int particle, float radius) {
		float h = config.cellSize();
		int x = Math.min(sizeX - 1, Math.max(0, (int) Math.floor(particleX[particle] / h)));
		int y = Math.min(sizeY - 1, Math.max(0, (int) Math.floor(particleY[particle] / h)));
		int z = Math.min(sizeZ - 1, Math.max(0, (int) Math.floor(particleZ[particle] / h)));
		float localX = particleX[particle] - x * h;
		float localY = particleY[particle] - y * h;
		float localZ = particleZ[particle] - z * h;
		float contactMinimum = radius + h * 1.0E-3F;
		float contactMaximum = h - contactMinimum;

		if (x > 0 && localX < radius && solid[cellIndex(x - 1, y, z)]) {
			particleX[particle] = x * h + contactMinimum;
			particleVelocityX[particle] = Math.max(0.0F, particleVelocityX[particle]);
		}
		if (x + 1 < sizeX && localX > h - radius && solid[cellIndex(x + 1, y, z)]) {
			particleX[particle] = x * h + contactMaximum;
			particleVelocityX[particle] = Math.min(0.0F, particleVelocityX[particle]);
		}
		if (y > 0 && localY < radius && solid[cellIndex(x, y - 1, z)]) {
			particleY[particle] = y * h + contactMinimum;
			particleVelocityY[particle] = Math.max(0.0F, particleVelocityY[particle]);
		}
		if (y + 1 < sizeY && localY > h - radius && solid[cellIndex(x, y + 1, z)]) {
			particleY[particle] = y * h + contactMaximum;
			particleVelocityY[particle] = Math.min(0.0F, particleVelocityY[particle]);
		}
		if (z > 0 && localZ < radius && solid[cellIndex(x, y, z - 1)]) {
			particleZ[particle] = z * h + contactMinimum;
			particleVelocityZ[particle] = Math.max(0.0F, particleVelocityZ[particle]);
		}
		if (z + 1 < sizeZ && localZ > h - radius && solid[cellIndex(x, y, z + 1)]) {
			particleZ[particle] = z * h + contactMaximum;
			particleVelocityZ[particle] = Math.min(0.0F, particleVelocityZ[particle]);
		}
	}

	private void enforceSolidFaceVelocities() {
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) {
			velocityX[xFaceIndex(0, y, z)] = 0.0F; velocityX[xFaceIndex(sizeX, y, z)] = 0.0F;
			for (int x = 1; x < sizeX; x++) if (solid[cellIndex(x - 1, y, z)] || solid[cellIndex(x, y, z)]) velocityX[xFaceIndex(x, y, z)] = 0.0F;
		}
		for (int z = 0; z < sizeZ; z++) for (int x = 0; x < sizeX; x++) {
			velocityY[yFaceIndex(x, 0, z)] = 0.0F; velocityY[yFaceIndex(x, sizeY, z)] = 0.0F;
			for (int y = 1; y < sizeY; y++) if (solid[cellIndex(x, y - 1, z)] || solid[cellIndex(x, y, z)]) velocityY[yFaceIndex(x, y, z)] = 0.0F;
		}
		for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			velocityZ[zFaceIndex(x, y, 0)] = 0.0F; velocityZ[zFaceIndex(x, y, sizeZ)] = 0.0F;
			for (int z = 1; z < sizeZ; z++) if (solid[cellIndex(x, y, z - 1)] || solid[cellIndex(x, y, z)]) velocityZ[zFaceIndex(x, y, z)] = 0.0F;
		}
	}

	private void spawnCellVolume(int x, int y, int z, float amount) {
		float remaining = amount;
		int slot = 0;
		while (remaining > 1.0E-6F) {
			float piece = Math.min(PARTICLE_VOLUME, remaining);
			spawnParticle(x, y, z, slot++ & 7, piece);
			remaining -= piece;
		}
	}

	private void spawnParticle(int cellX, int cellY, int cellZ, int slot, float amount) {
		if (particleCount >= MAX_PARTICLES) throw new IllegalStateException("FLIP particle limit exceeded");
		ensureParticleCapacity(particleCount + 1);
		float h = config.cellSize();
		float radiusFraction = 0.45F;
		float ox = (slot & 1) == 0 ? 0.25F : 0.75F;
		float oy = (slot & 2) == 0 ? 0.25F : 0.75F;
		float oz = (slot & 4) == 0 ? 0.25F : 0.75F;
		if (cellX == 0 || solid[cellIndex(cellX - 1, cellY, cellZ)]) ox = Math.max(ox, radiusFraction);
		if (cellX + 1 == sizeX || solid[cellIndex(cellX + 1, cellY, cellZ)]) ox = Math.min(ox, 1.0F - radiusFraction);
		if (cellY == 0 || solid[cellIndex(cellX, cellY - 1, cellZ)]) oy = Math.max(oy, radiusFraction);
		if (cellY + 1 == sizeY || solid[cellIndex(cellX, cellY + 1, cellZ)]) oy = Math.min(oy, 1.0F - radiusFraction);
		if (cellZ == 0 || solid[cellIndex(cellX, cellY, cellZ - 1)]) oz = Math.max(oz, radiusFraction);
		if (cellZ + 1 == sizeZ || solid[cellIndex(cellX, cellY, cellZ + 1)]) oz = Math.min(oz, 1.0F - radiusFraction);
		particleX[particleCount] = (cellX + ox) * h;
		particleY[particleCount] = (cellY + oy) * h;
		particleZ[particleCount] = (cellZ + oz) * h;
		particleVelocityX[particleCount] = 0.0F;
		particleVelocityY[particleCount] = 0.0F;
		particleVelocityZ[particleCount] = 0.0F;
		particleVolume[particleCount] = amount;
		particleCount++;
	}

	private float removeParticlesInCell(int cellX, int cellY, int cellZ, float maximum) {
		float h = config.cellSize(), removed = 0.0F;
		for (int i = particleCount - 1; i >= 0 && removed < maximum; i--) {
			if ((int) Math.floor(particleX[i] / h) != cellX || (int) Math.floor(particleY[i] / h) != cellY || (int) Math.floor(particleZ[i] / h) != cellZ) continue;
			float take = Math.min(particleVolume[i], maximum - removed);
			particleVolume[i] -= take;
			removed += take;
			if (particleVolume[i] <= 1.0E-6F) removeParticle(i);
		}
		return removed;
	}

	private void removeParticle(int index) {
		int last = --particleCount;
		if (index == last) return;
		particleX[index] = particleX[last]; particleY[index] = particleY[last]; particleZ[index] = particleZ[last];
		particleVelocityX[index] = particleVelocityX[last]; particleVelocityY[index] = particleVelocityY[last]; particleVelocityZ[index] = particleVelocityZ[last];
		particleVolume[index] = particleVolume[last];
	}

	private void ensureParticleCapacity(int required) {
		if (required <= particleX.length) return;
		int capacity = Math.min(MAX_PARTICLES, Math.max(required, particleX.length * 2));
		particleX = Arrays.copyOf(particleX, capacity); particleY = Arrays.copyOf(particleY, capacity); particleZ = Arrays.copyOf(particleZ, capacity);
		particleVelocityX = Arrays.copyOf(particleVelocityX, capacity); particleVelocityY = Arrays.copyOf(particleVelocityY, capacity); particleVelocityZ = Arrays.copyOf(particleVelocityZ, capacity);
		particleVolume = Arrays.copyOf(particleVolume, capacity);
	}

	private List<CellCandidate> nearbyCells(int centerX, int centerY, int centerZ, int maxRadius) {
		ensureGrid();
		List<CellCandidate> result = new ArrayList<>();
		int radiusSquared = maxRadius * maxRadius;
		for (int z = Math.max(0, centerZ - maxRadius); z <= Math.min(sizeZ - 1, centerZ + maxRadius); z++)
			for (int y = Math.max(0, centerY - maxRadius); y <= Math.min(sizeY - 1, centerY + maxRadius); y++)
				for (int x = Math.max(0, centerX - maxRadius); x <= Math.min(sizeX - 1, centerX + maxRadius); x++) {
					int dx = x - centerX, dy = y - centerY, dz = z - centerZ, index = cellIndex(x, y, z);
					if (dx * dx + dy * dy + dz * dz <= radiusSquared && !solid[index]) result.add(new CellCandidate(index, dx * dx + dy * dy + dz * dz));
				}
		result.sort(Comparator.comparingInt(CellCandidate::distanceSquared));
		return result;
	}

	private void ensureGrid() { if (gridDirty) rebuildGridFromParticles(); }
	private boolean isFluid(int index) { return !solid[index] && markerCount[index] > 0; }
	private boolean isAir(int index) { return !solid[index] && !isFluid(index); }
	private boolean faceActive(int first, int second) { return !solid[first] && !solid[second] && (isFluid(first) || isFluid(second)); }
	private float fluidPressure(int index) { return isFluid(index) ? pressure[index] : 0.0F; }
	private boolean inside(int x, int y, int z) { return x >= 0 && x < sizeX && y >= 0 && y < sizeY && z >= 0 && z < sizeZ; }
	private int cellIndex(int x, int y, int z) { return (z * sizeY + y) * sizeX + x; }
	private int xFaceIndex(int x, int y, int z) { return (z * sizeY + y) * (sizeX + 1) + x; }
	private int yFaceIndex(int x, int y, int z) { return (z * (sizeY + 1) + y) * sizeX + x; }
	private int zFaceIndex(int x, int y, int z) { return (z * sizeY + y) * sizeX + x; }
	private int cellIndexChecked(int x, int y, int z) { if (!inside(x, y, z)) throw new IndexOutOfBoundsException(); return cellIndex(x, y, z); }

	private float sampleX(float[] field, float x, float y, float z) {
		float h = config.cellSize(); return trilinear(field, sizeX + 1, sizeY, sizeZ, x / h, y / h - 0.5F, z / h - 0.5F);
	}
	private float sampleY(float[] field, float x, float y, float z) {
		float h = config.cellSize(); return trilinear(field, sizeX, sizeY + 1, sizeZ, x / h - 0.5F, y / h, z / h - 0.5F);
	}
	private float sampleZ(float[] field, float x, float y, float z) {
		float h = config.cellSize(); return trilinear(field, sizeX, sizeY, sizeZ + 1, x / h - 0.5F, y / h - 0.5F, z / h);
	}

	private static float trilinear(float[] values, int nx, int ny, int nz, float x, float y, float z) {
		float cx = clamp(x, 0.0F, nx - 1.0F), cy = clamp(y, 0.0F, ny - 1.0F), cz = clamp(z, 0.0F, nz - 1.0F);
		int x0 = (int) Math.floor(cx), y0 = (int) Math.floor(cy), z0 = (int) Math.floor(cz);
		int x1 = Math.min(x0 + 1, nx - 1), y1 = Math.min(y0 + 1, ny - 1), z1 = Math.min(z0 + 1, nz - 1);
		float fx = cx - x0, fy = cy - y0, fz = cz - z0;
		float c00 = lerp(values[(z0 * ny + y0) * nx + x0], values[(z0 * ny + y0) * nx + x1], fx);
		float c10 = lerp(values[(z0 * ny + y1) * nx + x0], values[(z0 * ny + y1) * nx + x1], fx);
		float c01 = lerp(values[(z1 * ny + y0) * nx + x0], values[(z1 * ny + y0) * nx + x1], fx);
		float c11 = lerp(values[(z1 * ny + y1) * nx + x0], values[(z1 * ny + y1) * nx + x1], fx);
		return lerp(lerp(c00, c10, fy), lerp(c01, c11, fy), fz);
	}

	private void validateVolumeEdit(float amount, int maxRadius) {
		if (!Float.isFinite(amount) || amount < 0.0F) throw new IllegalArgumentException("amount must be finite and non-negative");
		if (maxRadius < 0) throw new IllegalArgumentException("maxRadius cannot be negative");
	}
	private static double dot(float[] first, float[] second) { double sum = 0.0; for (int i = 0; i < first.length; i++) sum += (double) first[i] * second[i]; return sum; }
	private static float clamp01(float value) { return clamp(value, 0.0F, 1.0F); }
	private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
	private static float lerp(float first, float second, float t) { return first + (second - first) * t; }

	@FunctionalInterface private interface SplatConsumer { void accept(int index, float weight); }
	private record CellCandidate(int index, int distanceSquared) { }
	public record VelocitySample(float x, float y, float z) { private static final VelocitySample ZERO = new VelocitySample(0.0F, 0.0F, 0.0F); }
	public record VolumeDistribution(int activeCells, int visibleCells, int denseCells) { }
	public record StepStats(int substeps, int pressureIterations, float pressureResidual, float volumeBefore, float volumeAfter) {
		public float relativeVolumeError() { return volumeBefore == 0.0F ? 0.0F : Math.abs(volumeAfter - volumeBefore) / volumeBefore; }
	}
}
