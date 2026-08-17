package com.example.fluid;

import java.util.Arrays;

/**
 * A fixed-size, single-density, free-surface 3D MAC-grid fluid domain.
 *
 * <p>This class deliberately has no Minecraft or rendering dependencies. Cell
 * volume is represented by a conservative volume fraction in [0, 1]. Velocity
 * is stored on cell faces, pressure at cell centers, and solid cells are static
 * no-flow boundaries. Velocity advection is semi-Lagrangian; volume advection
 * is an explicitly conservative bounded upwind transport.</p>
 */
public final class FluidDomain {
	private final int sizeX;
	private final int sizeY;
	private final int sizeZ;
	private final int cellCount;
	private final FluidSolverConfig config;

	private final boolean[] solid;
	private float[] volume;
	private float[] volumeScratch;
	private final float[] pressure;
	private final float[] divergence;

	// Staggered face velocities.
	private float[] velocityX;
	private float[] velocityY;
	private float[] velocityZ;
	private float[] velocityXScratch;
	private float[] velocityYScratch;
	private float[] velocityZScratch;

	// PCG work vectors.
	private final float[] rhs;
	private final float[] residual;
	private final float[] preconditioned;
	private final float[] direction;
	private final float[] matrixDirection;

	// Conservative transport work buffers.
	private final float[] fluxX;
	private final float[] fluxY;
	private final float[] fluxZ;
	private final float[] outgoing;
	private final float[] incoming;
	private final float[] donorScale;
	private final float[] receiverScale;

	private int pressureAnchor = -1;
	private int lastPressureIterations;
	private float lastPressureResidual;

	public FluidDomain(int sizeX, int sizeY, int sizeZ, FluidSolverConfig config) {
		if (sizeX < 2 || sizeY < 2 || sizeZ < 2) {
			throw new IllegalArgumentException("All domain dimensions must be at least two cells");
		}
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.cellCount = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
		this.config = config;

		this.solid = new boolean[cellCount];
		this.volume = new float[cellCount];
		this.volumeScratch = new float[cellCount];
		this.pressure = new float[cellCount];
		this.divergence = new float[cellCount];

		this.velocityX = new float[(sizeX + 1) * sizeY * sizeZ];
		this.velocityY = new float[sizeX * (sizeY + 1) * sizeZ];
		this.velocityZ = new float[sizeX * sizeY * (sizeZ + 1)];
		this.velocityXScratch = new float[velocityX.length];
		this.velocityYScratch = new float[velocityY.length];
		this.velocityZScratch = new float[velocityZ.length];

		this.rhs = new float[cellCount];
		this.residual = new float[cellCount];
		this.preconditioned = new float[cellCount];
		this.direction = new float[cellCount];
		this.matrixDirection = new float[cellCount];

		this.fluxX = new float[velocityX.length];
		this.fluxY = new float[velocityY.length];
		this.fluxZ = new float[velocityZ.length];
		this.outgoing = new float[cellCount];
		this.incoming = new float[cellCount];
		this.donorScale = new float[cellCount];
		this.receiverScale = new float[cellCount];
	}

	public int sizeX() { return sizeX; }
	public int sizeY() { return sizeY; }
	public int sizeZ() { return sizeZ; }
	public FluidSolverConfig config() { return config; }

	public void setSolid(int x, int y, int z, boolean value) {
		int index = cellIndexChecked(x, y, z);
		solid[index] = value;
		if (value) {
			volume[index] = 0.0F;
			pressure[index] = 0.0F;
		}
	}

	public boolean isSolid(int x, int y, int z) {
		return solid[cellIndexChecked(x, y, z)];
	}

	public void setVolume(int x, int y, int z, float value) {
		int index = cellIndexChecked(x, y, z);
		if (solid[index] && value > 0.0F) {
			throw new IllegalStateException("Cannot place fluid in a solid cell");
		}
		volume[index] = clamp01(value);
	}

	public float volume(int x, int y, int z) {
		return volume[cellIndexChecked(x, y, z)];
	}

	public float pressure(int x, int y, int z) {
		return pressure[cellIndexChecked(x, y, z)];
	}

	public float velocityX(int faceX, int y, int z) {
		return velocityX[xFaceIndexChecked(faceX, y, z)];
	}

	public float velocityY(int x, int faceY, int z) {
		return velocityY[yFaceIndexChecked(x, faceY, z)];
	}

	public float velocityZ(int x, int y, int faceZ) {
		return velocityZ[zFaceIndexChecked(x, y, faceZ)];
	}

	public float totalVolume() {
		double sum = 0.0;
		for (float value : volume) sum += value;
		return (float) sum;
	}

	public float maximumPressure() {
		float maximum = 0.0F;
		for (float value : pressure) maximum = Math.max(maximum, value);
		return maximum;
	}

	/** Quantizes cell volume to an unsigned byte for compact debug snapshots. */
	public byte[] copyQuantizedVolume() {
		byte[] result = new byte[cellCount];
		for (int i = 0; i < cellCount; i++) result[i] = (byte) Math.round(clamp01(volume[i]) * 255.0F);
		return result;
	}

	public float centerOfMassX() {
		double weighted = 0.0;
		double mass = 0.0;
		for (int z = 0; z < sizeZ; z++) {
			for (int y = 0; y < sizeY; y++) {
				for (int x = 0; x < sizeX; x++) {
					float cellVolume = volume[cellIndex(x, y, z)];
					weighted += (x + 0.5) * cellVolume;
					mass += cellVolume;
				}
			}
		}
		return mass == 0.0 ? Float.NaN : (float) (weighted / mass);
	}

	public StepStats step() {
		float before = totalVolume();
		int substeps = calculateSubsteps(config.timeStep());
		float substepTime = config.timeStep() / substeps;
		int iterationTotal = 0;

		for (int substep = 0; substep < substeps; substep++) {
			advectVelocities(substepTime);
			applyGravity(substepTime);
			enforceSolidFaceVelocities();
			solvePressure(substepTime);
			iterationTotal += lastPressureIterations;
			projectVelocity(substepTime);
			enforceSolidFaceVelocities();
			advectVolume(substepTime);
		}

		return new StepStats(substeps, iterationTotal, lastPressureResidual, before, totalVolume());
	}

	private int calculateSubsteps(float dt) {
		float xMax = maxAbs(velocityX);
		float yMax = maxAbs(velocityY) + config.gravity() * dt;
		float zMax = maxAbs(velocityZ);
		float directionalCourant = (xMax + yMax + zMax) * dt / config.cellSize();
		return Math.max(1, (int) Math.ceil(directionalCourant / config.maxCourantNumber()));
	}

	private void advectVelocities(float dt) {
		float cellSize = config.cellSize();

		Arrays.fill(velocityXScratch, 0.0F);
		for (int z = 0; z < sizeZ; z++) {
			for (int y = 0; y < sizeY; y++) {
				for (int x = 1; x < sizeX; x++) {
					int left = cellIndex(x - 1, y, z);
					int right = cellIndex(x, y, z);
					if (!faceActive(left, right)) continue;
					float px = x * cellSize;
					float py = (y + 0.5F) * cellSize;
					float pz = (z + 0.5F) * cellSize;
					float vx = sampleX(px, py, pz);
					float vy = sampleY(px, py, pz);
					float vz = sampleZ(px, py, pz);
					velocityXScratch[xFaceIndex(x, y, z)] = sampleX(px - vx * dt, py - vy * dt, pz - vz * dt);
				}
			}
		}

		Arrays.fill(velocityYScratch, 0.0F);
		for (int z = 0; z < sizeZ; z++) {
			for (int y = 1; y < sizeY; y++) {
				for (int x = 0; x < sizeX; x++) {
					int below = cellIndex(x, y - 1, z);
					int above = cellIndex(x, y, z);
					if (!faceActive(below, above)) continue;
					float px = (x + 0.5F) * cellSize;
					float py = y * cellSize;
					float pz = (z + 0.5F) * cellSize;
					float vx = sampleX(px, py, pz);
					float vy = sampleY(px, py, pz);
					float vz = sampleZ(px, py, pz);
					velocityYScratch[yFaceIndex(x, y, z)] = sampleY(px - vx * dt, py - vy * dt, pz - vz * dt);
				}
			}
		}

		Arrays.fill(velocityZScratch, 0.0F);
		for (int z = 1; z < sizeZ; z++) {
			for (int y = 0; y < sizeY; y++) {
				for (int x = 0; x < sizeX; x++) {
					int back = cellIndex(x, y, z - 1);
					int front = cellIndex(x, y, z);
					if (!faceActive(back, front)) continue;
					float px = (x + 0.5F) * cellSize;
					float py = (y + 0.5F) * cellSize;
					float pz = z * cellSize;
					float vx = sampleX(px, py, pz);
					float vy = sampleY(px, py, pz);
					float vz = sampleZ(px, py, pz);
					velocityZScratch[zFaceIndex(x, y, z)] = sampleZ(px - vx * dt, py - vy * dt, pz - vz * dt);
				}
			}
		}

		float[] oldX = velocityX;
		velocityX = velocityXScratch;
		velocityXScratch = oldX;
		float[] oldY = velocityY;
		velocityY = velocityYScratch;
		velocityYScratch = oldY;
		float[] oldZ = velocityZ;
		velocityZ = velocityZScratch;
		velocityZScratch = oldZ;
	}

	private void applyGravity(float dt) {
		float impulse = config.gravity() * dt;
		for (int z = 0; z < sizeZ; z++) {
			for (int y = 1; y < sizeY; y++) {
				for (int x = 0; x < sizeX; x++) {
					int below = cellIndex(x, y - 1, z);
					int above = cellIndex(x, y, z);
					if (faceActive(below, above)) velocityY[yFaceIndex(x, y, z)] -= impulse;
				}
			}
		}
	}

	private void solvePressure(float dt) {
		Arrays.fill(pressure, 0.0F);
		Arrays.fill(rhs, 0.0F);
		pressureAnchor = findPressureAnchor();
		float inverseCell = 1.0F / config.cellSize();
		float rhsScale = -config.density() / dt;
		int fluidCells = 0;

		for (int z = 0; z < sizeZ; z++) {
			for (int y = 0; y < sizeY; y++) {
				for (int x = 0; x < sizeX; x++) {
					int index = cellIndex(x, y, z);
					if (!isFluid(index) || index == pressureAnchor) {
						divergence[index] = 0.0F;
						continue;
					}
					float div = (
							velocityX[xFaceIndex(x + 1, y, z)] - velocityX[xFaceIndex(x, y, z)]
							+ velocityY[yFaceIndex(x, y + 1, z)] - velocityY[yFaceIndex(x, y, z)]
							+ velocityZ[zFaceIndex(x, y, z + 1)] - velocityZ[zFaceIndex(x, y, z)]
					) * inverseCell;
					divergence[index] = div;
					rhs[index] = rhsScale * div;
					fluidCells++;
				}
			}
		}

		if (fluidCells == 0) {
			lastPressureIterations = 0;
			lastPressureResidual = 0.0F;
			return;
		}

		System.arraycopy(rhs, 0, residual, 0, cellCount);
		applyPreconditioner(residual, preconditioned);
		System.arraycopy(preconditioned, 0, direction, 0, cellCount);
		double rz = dot(residual, preconditioned);
		double rhsNorm = Math.sqrt(dot(rhs, rhs) / fluidCells);
		double target = config.pressureTolerance() * Math.max(1.0, rhsNorm);
		lastPressureIterations = 0;

		for (int iteration = 0; iteration < config.maxPressureIterations(); iteration++) {
			applyPressureMatrix(direction, matrixDirection);
			double denominator = dot(direction, matrixDirection);
			if (!(denominator > 1.0E-20)) break;
			float alpha = (float) (rz / denominator);
			for (int i = 0; i < cellCount; i++) {
				pressure[i] += alpha * direction[i];
				residual[i] -= alpha * matrixDirection[i];
			}
			lastPressureIterations = iteration + 1;
			double rms = Math.sqrt(dot(residual, residual) / fluidCells);
			if (rms <= target) break;

			applyPreconditioner(residual, preconditioned);
			double nextRz = dot(residual, preconditioned);
			float beta = (float) (nextRz / rz);
			for (int i = 0; i < cellCount; i++) direction[i] = preconditioned[i] + beta * direction[i];
			rz = nextRz;
		}

		lastPressureResidual = (float) Math.sqrt(dot(residual, residual) / fluidCells);
	}

	private void applyPressureMatrix(float[] source, float[] destination) {
		Arrays.fill(destination, 0.0F);
		float inverseCellSquared = 1.0F / (config.cellSize() * config.cellSize());
		for (int z = 0; z < sizeZ; z++) {
			for (int y = 0; y < sizeY; y++) {
				for (int x = 0; x < sizeX; x++) {
					int index = cellIndex(x, y, z);
					if (!isFluid(index) || index == pressureAnchor) continue;
					float value = 0.0F;
					int diagonal = 0;
					if (x > 0) { diagonal += pressureFaceDiagonal(cellIndex(x - 1, y, z)); value -= neighborValue(cellIndex(x - 1, y, z), source); }
					if (x + 1 < sizeX) { diagonal += pressureFaceDiagonal(cellIndex(x + 1, y, z)); value -= neighborValue(cellIndex(x + 1, y, z), source); }
					if (y > 0) { diagonal += pressureFaceDiagonal(cellIndex(x, y - 1, z)); value -= neighborValue(cellIndex(x, y - 1, z), source); }
					if (y + 1 < sizeY) { diagonal += pressureFaceDiagonal(cellIndex(x, y + 1, z)); value -= neighborValue(cellIndex(x, y + 1, z), source); }
					if (z > 0) { diagonal += pressureFaceDiagonal(cellIndex(x, y, z - 1)); value -= neighborValue(cellIndex(x, y, z - 1), source); }
					if (z + 1 < sizeZ) { diagonal += pressureFaceDiagonal(cellIndex(x, y, z + 1)); value -= neighborValue(cellIndex(x, y, z + 1), source); }
					destination[index] = (diagonal * source[index] + value) * inverseCellSquared;
				}
			}
		}
	}

	private int pressureFaceDiagonal(int neighbor) {
		return solid[neighbor] ? 0 : 1;
	}

	private float neighborValue(int neighbor, float[] source) {
		return isFluid(neighbor) && neighbor != pressureAnchor ? source[neighbor] : 0.0F;
	}

	private void applyPreconditioner(float[] source, float[] destination) {
		float cellSquared = config.cellSize() * config.cellSize();
		for (int z = 0; z < sizeZ; z++) {
			for (int y = 0; y < sizeY; y++) {
				for (int x = 0; x < sizeX; x++) {
					int index = cellIndex(x, y, z);
					if (!isFluid(index) || index == pressureAnchor) {
						destination[index] = 0.0F;
						continue;
					}
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
		}
	}

	private int findPressureAnchor() {
		int firstFluid = -1;
		for (int z = 0; z < sizeZ; z++) {
			for (int y = 0; y < sizeY; y++) {
				for (int x = 0; x < sizeX; x++) {
					int index = cellIndex(x, y, z);
					if (!isFluid(index)) continue;
					if (firstFluid < 0) firstFluid = index;
					if (hasAirNeighbor(x, y, z)) return -1;
				}
			}
		}
		return firstFluid;
	}

	private boolean hasAirNeighbor(int x, int y, int z) {
		return (x > 0 && isAir(cellIndex(x - 1, y, z)))
				|| (x + 1 < sizeX && isAir(cellIndex(x + 1, y, z)))
				|| (y > 0 && isAir(cellIndex(x, y - 1, z)))
				|| (y + 1 < sizeY && isAir(cellIndex(x, y + 1, z)))
				|| (z > 0 && isAir(cellIndex(x, y, z - 1)))
				|| (z + 1 < sizeZ && isAir(cellIndex(x, y, z + 1)));
	}

	private void projectVelocity(float dt) {
		float scale = dt / (config.density() * config.cellSize());
		for (int z = 0; z < sizeZ; z++) {
			for (int y = 0; y < sizeY; y++) {
				for (int x = 1; x < sizeX; x++) {
					int left = cellIndex(x - 1, y, z);
					int right = cellIndex(x, y, z);
					int face = xFaceIndex(x, y, z);
					if (!faceActive(left, right)) velocityX[face] = 0.0F;
					else velocityX[face] -= scale * (fluidPressure(right) - fluidPressure(left));
				}
			}
		}
		for (int z = 0; z < sizeZ; z++) {
			for (int y = 1; y < sizeY; y++) {
				for (int x = 0; x < sizeX; x++) {
					int below = cellIndex(x, y - 1, z);
					int above = cellIndex(x, y, z);
					int face = yFaceIndex(x, y, z);
					if (!faceActive(below, above)) velocityY[face] = 0.0F;
					else velocityY[face] -= scale * (fluidPressure(above) - fluidPressure(below));
				}
			}
		}
		for (int z = 1; z < sizeZ; z++) {
			for (int y = 0; y < sizeY; y++) {
				for (int x = 0; x < sizeX; x++) {
					int back = cellIndex(x, y, z - 1);
					int front = cellIndex(x, y, z);
					int face = zFaceIndex(x, y, z);
					if (!faceActive(back, front)) velocityZ[face] = 0.0F;
					else velocityZ[face] -= scale * (fluidPressure(front) - fluidPressure(back));
				}
			}
		}
	}

	private void advectVolume(float dt) {
		Arrays.fill(fluxX, 0.0F);
		Arrays.fill(fluxY, 0.0F);
		Arrays.fill(fluxZ, 0.0F);
		Arrays.fill(outgoing, 0.0F);
		Arrays.fill(incoming, 0.0F);
		float scale = dt / config.cellSize();

		forEachTransportFace((axis, face, lower, upper, speed) -> {
			int donor = speed >= 0.0F ? lower : upper;
			if (!isFluid(donor)) return;
			float amount = Math.abs(speed) * scale * volume[donor];
			setFlux(axis, face, Math.copySign(amount, speed));
			outgoing[donor] += amount;
		});

		for (int i = 0; i < cellCount; i++) {
			donorScale[i] = outgoing[i] > volume[i] ? volume[i] / outgoing[i] : 1.0F;
		}

		Arrays.fill(outgoing, 0.0F);
		forEachStoredFlux((axis, face, lower, upper, signedAmount) -> {
			int donor = signedAmount >= 0.0F ? lower : upper;
			int receiver = signedAmount >= 0.0F ? upper : lower;
			float amount = Math.abs(signedAmount) * donorScale[donor];
			outgoing[donor] += amount;
			incoming[receiver] += amount;
		});

		for (int i = 0; i < cellCount; i++) {
			float capacityAfterOutflow = 1.0F - volume[i] + outgoing[i];
			receiverScale[i] = incoming[i] > capacityAfterOutflow
					? Math.max(0.0F, capacityAfterOutflow / incoming[i])
					: 1.0F;
		}

		System.arraycopy(volume, 0, volumeScratch, 0, cellCount);
		forEachStoredFlux((axis, face, lower, upper, signedAmount) -> {
			int donor = signedAmount >= 0.0F ? lower : upper;
			int receiver = signedAmount >= 0.0F ? upper : lower;
			float accepted = Math.abs(signedAmount) * donorScale[donor] * receiverScale[receiver];
			volumeScratch[donor] -= accepted;
			volumeScratch[receiver] += accepted;
		});

		for (int i = 0; i < cellCount; i++) {
			volumeScratch[i] = solid[i] ? 0.0F : clamp01(volumeScratch[i]);
		}
		float[] old = volume;
		volume = volumeScratch;
		volumeScratch = old;
	}

	private void forEachTransportFace(FaceConsumer consumer) {
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 1; x < sizeX; x++) {
			int lower = cellIndex(x - 1, y, z), upper = cellIndex(x, y, z);
			if (!solid[lower] && !solid[upper]) consumer.accept(0, xFaceIndex(x, y, z), lower, upper, velocityX[xFaceIndex(x, y, z)]);
		}
		for (int z = 0; z < sizeZ; z++) for (int y = 1; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int lower = cellIndex(x, y - 1, z), upper = cellIndex(x, y, z);
			if (!solid[lower] && !solid[upper]) consumer.accept(1, yFaceIndex(x, y, z), lower, upper, velocityY[yFaceIndex(x, y, z)]);
		}
		for (int z = 1; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int lower = cellIndex(x, y, z - 1), upper = cellIndex(x, y, z);
			if (!solid[lower] && !solid[upper]) consumer.accept(2, zFaceIndex(x, y, z), lower, upper, velocityZ[zFaceIndex(x, y, z)]);
		}
	}

	private void forEachStoredFlux(FaceConsumer consumer) {
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 1; x < sizeX; x++) {
			int face = xFaceIndex(x, y, z); float amount = fluxX[face];
			if (amount != 0.0F) consumer.accept(0, face, cellIndex(x - 1, y, z), cellIndex(x, y, z), amount);
		}
		for (int z = 0; z < sizeZ; z++) for (int y = 1; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int face = yFaceIndex(x, y, z); float amount = fluxY[face];
			if (amount != 0.0F) consumer.accept(1, face, cellIndex(x, y - 1, z), cellIndex(x, y, z), amount);
		}
		for (int z = 1; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			int face = zFaceIndex(x, y, z); float amount = fluxZ[face];
			if (amount != 0.0F) consumer.accept(2, face, cellIndex(x, y, z - 1), cellIndex(x, y, z), amount);
		}
	}

	private void setFlux(int axis, int index, float value) {
		if (axis == 0) fluxX[index] = value;
		else if (axis == 1) fluxY[index] = value;
		else fluxZ[index] = value;
	}

	private void enforceSolidFaceVelocities() {
		for (int z = 0; z < sizeZ; z++) for (int y = 0; y < sizeY; y++) {
			velocityX[xFaceIndex(0, y, z)] = 0.0F;
			velocityX[xFaceIndex(sizeX, y, z)] = 0.0F;
			for (int x = 1; x < sizeX; x++) if (solid[cellIndex(x - 1, y, z)] || solid[cellIndex(x, y, z)]) velocityX[xFaceIndex(x, y, z)] = 0.0F;
		}
		for (int z = 0; z < sizeZ; z++) for (int x = 0; x < sizeX; x++) {
			velocityY[yFaceIndex(x, 0, z)] = 0.0F;
			velocityY[yFaceIndex(x, sizeY, z)] = 0.0F;
			for (int y = 1; y < sizeY; y++) if (solid[cellIndex(x, y - 1, z)] || solid[cellIndex(x, y, z)]) velocityY[yFaceIndex(x, y, z)] = 0.0F;
		}
		for (int y = 0; y < sizeY; y++) for (int x = 0; x < sizeX; x++) {
			velocityZ[zFaceIndex(x, y, 0)] = 0.0F;
			velocityZ[zFaceIndex(x, y, sizeZ)] = 0.0F;
			for (int z = 1; z < sizeZ; z++) if (solid[cellIndex(x, y, z - 1)] || solid[cellIndex(x, y, z)]) velocityZ[zFaceIndex(x, y, z)] = 0.0F;
		}
	}

	private float sampleX(float worldX, float worldY, float worldZ) {
		float h = config.cellSize();
		return trilinear(velocityX, sizeX + 1, sizeY, sizeZ, worldX / h, worldY / h - 0.5F, worldZ / h - 0.5F);
	}

	private float sampleY(float worldX, float worldY, float worldZ) {
		float h = config.cellSize();
		return trilinear(velocityY, sizeX, sizeY + 1, sizeZ, worldX / h - 0.5F, worldY / h, worldZ / h - 0.5F);
	}

	private float sampleZ(float worldX, float worldY, float worldZ) {
		float h = config.cellSize();
		return trilinear(velocityZ, sizeX, sizeY, sizeZ + 1, worldX / h - 0.5F, worldY / h - 0.5F, worldZ / h);
	}

	private static float trilinear(float[] values, int nx, int ny, int nz, float x, float y, float z) {
		x = Math.max(0.0F, Math.min(nx - 1.0F, x));
		y = Math.max(0.0F, Math.min(ny - 1.0F, y));
		z = Math.max(0.0F, Math.min(nz - 1.0F, z));
		int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y), z0 = (int) Math.floor(z);
		int x1 = Math.min(x0 + 1, nx - 1), y1 = Math.min(y0 + 1, ny - 1), z1 = Math.min(z0 + 1, nz - 1);
		float tx = x - x0, ty = y - y0, tz = z - z0;
		float c00 = lerp(values[(z0 * ny + y0) * nx + x0], values[(z0 * ny + y0) * nx + x1], tx);
		float c10 = lerp(values[(z0 * ny + y1) * nx + x0], values[(z0 * ny + y1) * nx + x1], tx);
		float c01 = lerp(values[(z1 * ny + y0) * nx + x0], values[(z1 * ny + y0) * nx + x1], tx);
		float c11 = lerp(values[(z1 * ny + y1) * nx + x0], values[(z1 * ny + y1) * nx + x1], tx);
		return lerp(lerp(c00, c10, ty), lerp(c01, c11, ty), tz);
	}

	private boolean faceActive(int first, int second) {
		return !solid[first] && !solid[second] && (isFluid(first) || isFluid(second));
	}

	private boolean isFluid(int index) {
		return !solid[index] && volume[index] > config.fluidEpsilon();
	}

	private boolean isAir(int index) {
		return !solid[index] && !isFluid(index);
	}

	private float fluidPressure(int index) {
		return isFluid(index) ? pressure[index] : 0.0F;
	}

	private int cellIndex(int x, int y, int z) { return (z * sizeY + y) * sizeX + x; }
	private int xFaceIndex(int x, int y, int z) { return (z * sizeY + y) * (sizeX + 1) + x; }
	private int yFaceIndex(int x, int y, int z) { return (z * (sizeY + 1) + y) * sizeX + x; }
	private int zFaceIndex(int x, int y, int z) { return (z * sizeY + y) * sizeX + x; }

	private int cellIndexChecked(int x, int y, int z) {
		if (x < 0 || x >= sizeX || y < 0 || y >= sizeY || z < 0 || z >= sizeZ) throw new IndexOutOfBoundsException();
		return cellIndex(x, y, z);
	}

	private int xFaceIndexChecked(int x, int y, int z) {
		if (x < 0 || x > sizeX || y < 0 || y >= sizeY || z < 0 || z >= sizeZ) throw new IndexOutOfBoundsException();
		return xFaceIndex(x, y, z);
	}

	private int yFaceIndexChecked(int x, int y, int z) {
		if (x < 0 || x >= sizeX || y < 0 || y > sizeY || z < 0 || z >= sizeZ) throw new IndexOutOfBoundsException();
		return yFaceIndex(x, y, z);
	}

	private int zFaceIndexChecked(int x, int y, int z) {
		if (x < 0 || x >= sizeX || y < 0 || y >= sizeY || z < 0 || z > sizeZ) throw new IndexOutOfBoundsException();
		return zFaceIndex(x, y, z);
	}

	private static float maxAbs(float[] values) {
		float max = 0.0F;
		for (float value : values) max = Math.max(max, Math.abs(value));
		return max;
	}

	private static double dot(float[] first, float[] second) {
		double sum = 0.0;
		for (int i = 0; i < first.length; i++) sum += (double) first[i] * second[i];
		return sum;
	}

	private static float clamp01(float value) { return Math.max(0.0F, Math.min(1.0F, value)); }
	private static float lerp(float first, float second, float t) { return first + (second - first) * t; }

	@FunctionalInterface
	private interface FaceConsumer {
		void accept(int axis, int faceIndex, int lowerCell, int upperCell, float amountOrSpeed);
	}

	public record StepStats(
			int substeps,
			int pressureIterations,
			float pressureResidual,
			float volumeBefore,
			float volumeAfter
	) {
		public float relativeVolumeError() {
			return volumeBefore == 0.0F ? 0.0F : Math.abs(volumeAfter - volumeBefore) / volumeBefore;
		}
	}
}
