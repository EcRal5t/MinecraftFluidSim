package cc.ecisr.mmm.gpuprobe;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/** Windowless 3D PIC/FLIP probe backend with an explicit device-local production path and host-visible baseline. */
final class VulkanFlipPipeline implements AutoCloseable {
	private static final int LOCAL_SIZE = 256;
	private static final int PHYSICAL_SUBSTEPS = 4;
	private static final int MG_PRE_SMOOTHING_SWEEPS = 2;
	private static final int MG_COARSE_ITERATIONS = 24;
	private static final int MG_POST_SMOOTHING_SWEEPS = 3;
	private static final int MGH_LEVEL_SMOOTHING_ITERATIONS = 2;
	private static final int MGH_BOTTOM_ITERATIONS = 4;
	private static final CellSelector NO_CELLS = (x, y, z) -> false;

	private static final int POSITIONS = 0;
	private static final int VELOCITIES = 1;
	private static final int DENSITY = 2;
	private static final int MARKERS = 3;
	private static final int U = 4;
	private static final int U_WEIGHT = 5;
	private static final int U_OLD = 6;
	private static final int V = 7;
	private static final int V_WEIGHT = 8;
	private static final int V_OLD = 9;
	private static final int W = 10;
	private static final int W_WEIGHT = 11;
	private static final int W_OLD = 12;
	private static final int PRESSURE_A = 13;
	private static final int PRESSURE_B = 14;
	private static final int DIVERGENCE = 15;
	private static final int SOLIDS = 16;
	private static final int SNAPSHOT = 17;
	private static final int DIAGNOSTICS = 18;
	private static final int COARSE_RHS = 19;
	private static final int COARSE_MASK = 20;
	private static final int COARSE_PRESSURE_A = 21;
	private static final int COARSE_PRESSURE_B = 22;
	private static final int COARSE_DIAGONAL = 23;
	private static final int COARSE_X_WEIGHT = 24;
	private static final int COARSE_Y_WEIGHT = 25;
	private static final int COARSE_Z_WEIGHT = 26;

	private static final int STAGE_CLEAR = 0;
	private static final int STAGE_P2G = 1;
	private static final int STAGE_GRID_PREP = 2;
	private static final int STAGE_DIVERGENCE = 3;
	private static final int STAGE_JACOBI = 4;
	private static final int STAGE_PROJECT = 5;
	private static final int STAGE_G2P = 6;
	private static final int STAGE_ADVECT = 7;
	private static final int STAGE_SNAPSHOT = 8;
	private static final int STAGE_CLEAR_SURFACE = 9;
	private static final int STAGE_SURFACE_P2G = 10;
	private static final int STAGE_DIAGNOSTICS = 11;
	private static final int STAGE_RBGS = 12;
	private static final int STAGE_PCG_INIT = 13;
	private static final int STAGE_PCG_CLEAR = 14;
	private static final int STAGE_PCG_APPLY = 15;
	private static final int STAGE_PCG_REDUCE = 16;
	private static final int STAGE_PCG_UPDATE_XR = 17;
	private static final int STAGE_PCG_UPDATE_P = 18;
	private static final int STAGE_MG_RESTRICT = 19;
	private static final int STAGE_MG_COARSE_JACOBI = 20;
	private static final int STAGE_MG_PROLONG = 21;
	private static final int STAGE_MGH_RESTRICT_FINE = 22;
	private static final int STAGE_MGH_RESTRICT_COARSE = 23;
	private static final int STAGE_MGH_JACOBI = 24;
	private static final int STAGE_MGH_PROLONG_FINE = 25;
	private static final int STAGE_MGH_PROLONG_COARSE = 26;

	private final Config config;
	private final MemoryMode memoryMode;
	private final PressureSolver pressureSolver;
	private final List<Long> capturedGpuStepNanos = new ArrayList<>();
	private VulkanDeviceContext deviceContext;
	private FlipGpuResources resources;
	private FlipKernelBindings bindings;
	private GpuStepScheduler scheduler;
	private GpuReadback readback;
	private VkCommandBuffer recordingCommandBuffer;
	private float recordingTimeStep;
	private int particleCount;
	private float particleVolumeLedger;
	private volatile Diagnostics lastDiagnostics = new Diagnostics(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, Float.NaN);
	private volatile long lastStepNanos;
	private volatile long lastGpuStepNanos = -1L;
	private boolean captureGpuStepTimings;

	VulkanFlipPipeline(Config config, int deviceIndex) {
		this(config, deviceIndex, MemoryMode.DEVICE_LOCAL_STAGING, PressureSolver.JACOBI);
	}

	VulkanFlipPipeline(Config config, int deviceIndex, MemoryMode memoryMode) {
		this(config, deviceIndex, memoryMode, PressureSolver.JACOBI);
	}

	VulkanFlipPipeline(Config config, int deviceIndex, MemoryMode memoryMode, PressureSolver pressureSolver) {
		this.config = config;
		this.memoryMode = memoryMode;
		this.pressureSolver = pressureSolver;
		try {
			deviceContext = new VulkanDeviceContext(deviceIndex);
			resources = new FlipGpuResources(deviceContext, config, memoryMode, multigridCellCount());
			bindings = new FlipKernelBindings(deviceContext, resources);
			scheduler = new GpuStepScheduler(deviceContext, resources, buffer(DIAGNOSTICS).size());
			readback = new GpuReadback(deviceContext, resources, scheduler, memoryMode);
		} catch (RuntimeException | LinkageError exception) {
			try { close(); }
			catch (RuntimeException | LinkageError closeFailure) { exception.addSuppressed(closeFailure); }
			throw exception;
		}
	}

	Config config() { return config; }
	String deviceName() { return deviceContext.deviceName(); }
	MemoryMode memoryMode() { return memoryMode; }
	PressureSolver pressureSolver() { return pressureSolver; }
	int particleCount() { return particleCount; }
	double lastStepMilliseconds() { return lastStepNanos / 1_000_000.0; }
	double lastGpuStepMilliseconds() {
		awaitSubmittedWork();
		return lastGpuStepNanos < 0L ? Double.NaN : lastGpuStepNanos / 1_000_000.0;
	}
	boolean hasTimestampQueries() { return deviceContext.hasTimestampQueries(); }
	int submissionRingSize() { return scheduler.ringSize(); }
	int maximumOutstandingSubmissions() { return scheduler.maximumOutstandingSubmissions(); }
	int physicalSubstepsPerStep() { return PHYSICAL_SUBSTEPS; }
	long regularStagingBytes() { return scheduler.regularStagingBytes(); }
	float totalParticleVolume() { return particleVolumeLedger; }

	void beginGpuTimingCapture() {
		awaitSubmittedWork();
		synchronized (capturedGpuStepNanos) {
			capturedGpuStepNanos.clear();
			captureGpuStepTimings = true;
		}
	}

	long[] endGpuTimingCapture() {
		awaitSubmittedWork();
		synchronized (capturedGpuStepNanos) {
			captureGpuStepTimings = false;
			long[] result = new long[capturedGpuStepNanos.size()];
			for (int i = 0; i < result.length; i++) result[i] = capturedGpuStepNanos.get(i);
			capturedGpuStepNanos.clear();
			return result;
		}
	}

	void initializeDamBreak() {
		initialize((x, y, z) -> x < config.nx() * 2 / 5 && y < config.ny() * 3 / 4, NO_CELLS);
	}

	void initializeDamBreakWithSolids(byte[] solidCells) {
		if (solidCells.length != config.cellCount()) throw new IllegalArgumentException("solid mask size does not match GPU grid");
		initialize((x, y, z) -> x < config.nx() * 2 / 5 && y < config.ny() * 3 / 4,
				(x, y, z) -> solidCells[x + config.nx() * (y + config.ny() * z)] != 0);
	}

	void initializeColumn() {
		initialize((x, y, z) -> x >= config.nx() / 3 && x < config.nx() * 2 / 3
				&& z >= config.nz() / 3 && z < config.nz() * 2 / 3 && y < config.ny() * 3 / 4, NO_CELLS);
	}

	void initializeFull() {
		initialize((x, y, z) -> true, NO_CELLS);
	}

	void initializeOccupancy(int occupiedCells, int markersPerCell) {
		if (occupiedCells < 1 || occupiedCells > config.cellCount()) {
			throw new IllegalArgumentException("Occupied-cell count is outside the GPU grid");
		}
		if (markersPerCell < 1 || markersPerCell > 8) {
			throw new IllegalArgumentException("Occupancy benchmark requires one to eight markers per cell");
		}
		initialize((x, y, z) -> x + config.nx() * (y + config.ny() * z) < occupiedCells,
				NO_CELLS, markersPerCell);
	}

	void initializeObstacleDamBreak() {
		initialize((x, y, z) -> x < config.nx() * 3 / 4 && y < config.ny() * 3 / 4,
				(x, y, z) -> {
					boolean centralZ = z >= config.nz() / 4 && z < config.nz() * 3 / 4;
					boolean thinWall = x == config.nx() / 2 && y < config.ny() * 3 / 4 && centralZ;
					boolean shelf = y == config.ny() / 2 && x >= config.nx() / 3 && x < config.nx() * 2 / 3 && centralZ;
					return thinWall || shelf;
				});
	}

	private void initialize(CellSelector selector, CellSelector solidSelector) {
		initialize(selector, solidSelector, 8);
	}

	private void initialize(CellSelector selector, CellSelector solidSelector, int markersPerCell) {
		GpuStepScheduler.SubmissionSlot uploadSlot = memoryMode == MemoryMode.HOST_VISIBLE ? null : scheduler.acquire();
		GpuBuffer upload = memoryMode == MemoryMode.HOST_VISIBLE ? buffer(POSITIONS) : readback.transferBuffer();
		MemoryUtil.memSet(upload.mappedAddress(), 0, buffer(POSITIONS).size());
		FloatBuffer positions = upload.floats(buffer(POSITIONS).size());
		if (memoryMode == MemoryMode.HOST_VISIBLE) {
			MemoryUtil.memSet(buffer(VELOCITIES).mappedAddress(), 0, buffer(VELOCITIES).size());
		}
		particleCount = 0;
		for (int z = 0; z < config.nz(); z++) for (int y = 0; y < config.ny(); y++) for (int x = 0; x < config.nx(); x++) {
			if (!selector.filled(x, y, z) || solidSelector.filled(x, y, z)) continue;
			for (int slot = 0; slot < markersPerCell; slot++) {
				if (particleCount >= config.maxParticles()) throw new IllegalStateException("GPU marker capacity exceeded");
				float ox = (slot & 1) == 0 ? 0.25F : 0.75F;
				float oy = (slot & 2) == 0 ? 0.25F : 0.75F;
				float oz = (slot & 4) == 0 ? 0.25F : 0.75F;
				if (x == 0) ox = Math.max(ox, 0.45F); if (x + 1 == config.nx()) ox = Math.min(ox, 0.55F);
				if (y == 0) oy = Math.max(oy, 0.45F); if (y + 1 == config.ny()) oy = Math.min(oy, 0.55F);
				if (z == 0) oz = Math.max(oz, 0.45F); if (z + 1 == config.nz()) oz = Math.min(oz, 0.55F);
				int base = particleCount * 4;
				positions.put(base, (x + ox) * config.cellSize());
				positions.put(base + 1, (y + oy) * config.cellSize());
				positions.put(base + 2, (z + oz) * config.cellSize());
				positions.put(base + 3, 0.125F);
				particleCount++;
			}
		}
		particleVolumeLedger = particleCount * 0.125F;
		if (memoryMode == MemoryMode.DEVICE_LOCAL_STAGING) uploadInitialState(uploadSlot);
		GpuStepScheduler.SubmissionSlot solidUploadSlot = memoryMode == MemoryMode.HOST_VISIBLE ? null : scheduler.acquire();
		GpuBuffer solidUpload = memoryMode == MemoryMode.HOST_VISIBLE ? buffer(SOLIDS) : readback.transferBuffer();
		int solidCells = populateSolidMask(solidSelector, solidUpload);
		if (memoryMode == MemoryMode.DEVICE_LOCAL_STAGING && solidCells > 0) uploadSolidMask(solidUploadSlot);
		rebuildSnapshot();
	}

	void rebuildSnapshot() {
		recordAndSubmit(false);
	}

	void step() {
		long started = System.nanoTime();
		recordAndSubmit(true);
		lastStepNanos = System.nanoTime() - started;
	}

	SnapshotData snapshot() {
		IntBuffer packed = readableBuffer(SNAPSHOT).order(ByteOrder.nativeOrder()).asIntBuffer();
		byte[] density = new byte[config.cellCount()];
		byte[] markers = new byte[config.cellCount()];
		byte[] solids = new byte[config.cellCount()];
		for (int i = 0; i < config.cellCount(); i++) {
			int value = packed.get(i);
			density[i] = (byte) (value & 0xff);
			markers[i] = (byte) ((value >>> 8) & 1);
			solids[i] = (byte) ((value >>> 9) & 1);
		}
		return new SnapshotData(density, markers, solids);
	}

	GpuProbeCheckpoint captureCheckpoint(long generation) {
		awaitSubmittedWork();
		float[] positionVolume = new float[Math.multiplyExact(particleCount, 4)];
		float[] particleVelocities = new float[Math.multiplyExact(particleCount, 3)];
		if (particleCount > 0) {
			copiedBuffer(POSITIONS, (long) positionVolume.length * Float.BYTES).asFloatBuffer().get(positionVolume);
			FloatBuffer gpuVelocities = copiedBuffer(VELOCITIES,
					(long) particleCount * 4L * Float.BYTES).asFloatBuffer();
			for (int particle = 0; particle < particleCount; particle++) {
				int gpuBase = particle * 4, cpuBase = particle * 3;
				particleVelocities[cpuBase] = gpuVelocities.get(gpuBase);
				particleVelocities[cpuBase + 1] = gpuVelocities.get(gpuBase + 1);
				particleVelocities[cpuBase + 2] = gpuVelocities.get(gpuBase + 2);
			}
		}
		IntBuffer gpuSolids = copiedBuffer(SOLIDS).asIntBuffer();
		byte[] solidMask = new byte[config.cellCount()];
		for (int cell = 0; cell < solidMask.length; cell++) {
			int solid = gpuSolids.get(cell);
			if (solid != 0 && solid != 1) throw new IllegalStateException("GPU checkpoint contains invalid solid state");
			solidMask[cell] = (byte) solid;
		}
		return new GpuProbeCheckpoint(generation, config.nx(), config.ny(), config.nz(), config.cellSize(),
				particleCount, particleVolumeLedger, positionVolume, particleVelocities, solidMask);
	}

	int markerCellCountFromParticles() {
		FloatBuffer positions = copiedBuffer(POSITIONS).asFloatBuffer();
		boolean[] occupied = new boolean[config.cellCount()];
		int count = 0;
		for (int i = 0; i < particleCount; i++) {
			int base = i * 4;
			int x = Math.clamp((int) Math.floor(positions.get(base) / config.cellSize()), 0, config.nx() - 1);
			int y = Math.clamp((int) Math.floor(positions.get(base + 1) / config.cellSize()), 0, config.ny() - 1);
			int z = Math.clamp((int) Math.floor(positions.get(base + 2) / config.cellSize()), 0, config.nz() - 1);
			int cell = x + config.nx() * (y + config.ny() * z);
			if (!occupied[cell]) { occupied[cell] = true; count++; }
		}
		return count;
	}

	int solidCellCount() {
		IntBuffer solids = copiedBuffer(SOLIDS).asIntBuffer();
		int count = 0;
		for (int i = 0; i < config.cellCount(); i++) if (solids.get(i) != 0) count++;
		return count;
	}

	int particlesInsideSolidCount() {
		FloatBuffer positions = copiedBuffer(POSITIONS).asFloatBuffer();
		IntBuffer solids = copiedBuffer(SOLIDS).asIntBuffer();
		int count = 0;
		for (int i = 0; i < particleCount; i++) {
			int base = i * 4;
			int x = Math.clamp((int) Math.floor(positions.get(base) / config.cellSize()), 0, config.nx() - 1);
			int y = Math.clamp((int) Math.floor(positions.get(base + 1) / config.cellSize()), 0, config.ny() - 1);
			int z = Math.clamp((int) Math.floor(positions.get(base + 2) / config.cellSize()), 0, config.nz() - 1);
			if (solids.get(x + config.nx() * (y + config.ny() * z)) != 0) count++;
		}
		return count;
	}

	float[] coarseXWeights() {
		FloatBuffer weights = copiedBuffer(COARSE_X_WEIGHT).asFloatBuffer();
		float[] copy = new float[coarseCellCount()];
		weights.get(copy);
		return copy;
	}

	float[] packedHierarchyXWeights() {
		FloatBuffer weights = copiedBuffer(COARSE_X_WEIGHT).asFloatBuffer();
		float[] copy = new float[multigridCellCount()];
		weights.get(copy);
		return copy;
	}

	HierarchyLevelStats[] hierarchyLevelStats() {
		FloatBuffer rhs = copiedBuffer(COARSE_RHS).asFloatBuffer();
		IntBuffer mask = copiedBuffer(COARSE_MASK).asIntBuffer();
		FloatBuffer pressure = copiedBuffer(COARSE_PRESSURE_A).asFloatBuffer();
		FloatBuffer diagonal = copiedBuffer(COARSE_DIAGONAL).asFloatBuffer();
		FloatBuffer xWeight = copiedBuffer(COARSE_X_WEIGHT).asFloatBuffer();
		FloatBuffer yWeight = copiedBuffer(COARSE_Y_WEIGHT).asFloatBuffer();
		FloatBuffer zWeight = copiedBuffer(COARSE_Z_WEIGHT).asFloatBuffer();
		HierarchyLevelStats[] result = new HierarchyLevelStats[multigridLevelCount()];
		for (int level = 1; level <= result.length; level++) {
			int offset = multigridLevelOffset(level), count = multigridLevelCellCount(level), fluid = 0;
			float maximumRhs = 0.0F, maximumPressure = 0.0F, minimumPositiveDiagonal = Float.POSITIVE_INFINITY;
			float maximumDiagonal = 0.0F, maximumWeight = 0.0F;
			for (int i = offset; i < offset + count; i++) {
				if ((mask.get(i) & 1) != 0) fluid++;
				maximumRhs = Math.max(maximumRhs, Math.abs(rhs.get(i)));
				maximumPressure = Math.max(maximumPressure, Math.abs(pressure.get(i)));
				float d = diagonal.get(i);
				if (d > 0.0F) minimumPositiveDiagonal = Math.min(minimumPositiveDiagonal, d);
				maximumDiagonal = Math.max(maximumDiagonal, d);
				maximumWeight = Math.max(maximumWeight, Math.max(xWeight.get(i), Math.max(yWeight.get(i), zWeight.get(i))));
			}
			result[level - 1] = new HierarchyLevelStats(level, count, fluid, maximumRhs, maximumPressure,
					minimumPositiveDiagonal, maximumDiagonal, maximumWeight);
		}
		return result;
	}

	float reconstructedDensityVolume() {
		FloatBuffer density = copiedBuffer(DENSITY).asFloatBuffer();
		double total = 0.0;
		for (int i = 0; i < config.cellCount(); i++) total += density.get(i);
		return (float) total;
	}

	Diagnostics diagnostics() {
		awaitSubmittedWork();
		return lastDiagnostics;
	}

	PressureProfile pressureProfile() {
		int pressureIndex = pressureSolver == PressureSolver.JACOBI && (config.pressureIterations() & 1) != 0 ? PRESSURE_B : PRESSURE_A;
		FloatBuffer pressure = copiedBuffer(pressureIndex).asFloatBuffer();
		IntBuffer markers = copiedBuffer(MARKERS).asIntBuffer();
		double[] sums = new double[config.ny()];
		int[] counts = new int[config.ny()];
		for (int z = 0; z < config.nz(); z++) for (int y = 0; y < config.ny(); y++) for (int x = 0; x < config.nx(); x++) {
			int index = x + config.nx() * (y + config.ny() * z);
			if (markers.get(index) == 0) continue;
			sums[y] += pressure.get(index);
			counts[y]++;
		}
		float[] averages = new float[config.ny()];
		for (int y = 0; y < config.ny(); y++) averages[y] = counts[y] == 0 ? Float.NaN : (float) (sums[y] / counts[y]);
		return new PressureProfile(averages, counts);
	}

	ReadbackStats measureReadback() {
		GpuReadback.Measurements measurements = readback.measure(SNAPSHOT);
		return new ReadbackStats(measurements.fullBytes(), measurements.compactBytes(), measurements.fullNanos(),
				measurements.compactNanos(), measurements.fullChecksum(), measurements.compactChecksum());
	}

	private void recordAndSubmit(boolean fullStep) {
		long started = System.nanoTime();
		GpuStepScheduler.SubmissionSlot slot = scheduler.acquire();
		int physicalSubsteps = fullStep ? PHYSICAL_SUBSTEPS : 1;
		recordingTimeStep = config.timeStep() / physicalSubsteps;
		try (MemoryStack stack = stackPush()) {
			recordingCommandBuffer = scheduler.begin(slot, "step-ring");
			if (fullStep && slot.timestampQueryPool() != VK_NULL_HANDLE) {
				vkCmdResetQueryPool(recordingCommandBuffer, slot.timestampQueryPool(), 0, 2);
				vkCmdWriteTimestamp(recordingCommandBuffer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, slot.timestampQueryPool(), 0);
			}
			bindings.bind(recordingCommandBuffer, stack);
			VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
			barrier.get(0).sType$Default().srcAccessMask(VK_ACCESS_HOST_WRITE_BIT | VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT)
					.dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
			vkCmdPipelineBarrier(recordingCommandBuffer, VK_PIPELINE_STAGE_HOST_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
					VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, barrier, null, null);
			ByteBuffer push = stack.malloc(FlipKernelBindings.PUSH_BYTES);
			for (int physicalSubstep = 0; physicalSubstep < physicalSubsteps; physicalSubstep++) {
				dispatch(push, STAGE_CLEAR, 0, maxGridCount());
				computeBarrier(barrier);
				dispatch(push, STAGE_P2G, 0, particleCount);
				computeBarrier(barrier);
				if (!fullStep) break;
				dispatch(push, STAGE_GRID_PREP, 0, maxFaceCount());
				computeBarrier(barrier);
				dispatch(push, STAGE_DIVERGENCE, 0, 1, config.cellCount());
				computeBarrier(barrier);
				solvePressure(push, barrier);
				dispatch(push, STAGE_PROJECT, 0, maxFaceCount());
				computeBarrier(barrier);
				// Diagnostics must observe the projected field, not the pressure RHS.
				dispatch(push, STAGE_DIVERGENCE, 0, 2, config.cellCount());
				computeBarrier(barrier);
				dispatch(push, STAGE_G2P, 0, particleCount);
				computeBarrier(barrier);
				dispatch(push, STAGE_ADVECT, 0, particleCount);
				computeBarrier(barrier);
				if (physicalSubstep + 1 < physicalSubsteps) continue;
				// The network surface represents the final post-advection substep. Pressure markers above
				// remain the exact occupancy used by the final pressure solve for diagnostics.
				dispatch(push, STAGE_CLEAR_SURFACE, 0, config.cellCount());
				computeBarrier(barrier);
				dispatch(push, STAGE_SURFACE_P2G, 0, particleCount);
				computeBarrier(barrier);
			}
			dispatch(push, STAGE_SNAPSHOT, 0, fullStep ? 1 : 0, config.cellCount());
			computeBarrier(barrier);
			dispatch(push, STAGE_DIAGNOSTICS, 0, Math.max(config.cellCount(), particleCount));
			barrier.get(0).srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
			vkCmdPipelineBarrier(recordingCommandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, barrier, null, null);
			copyBuffer(stack, buffer(DIAGNOSTICS), slot.stagingBuffer(), buffer(DIAGNOSTICS).size());
			barrier.get(0).srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_HOST_READ_BIT);
			vkCmdPipelineBarrier(recordingCommandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, barrier, null, null);
			if (fullStep && slot.timestampQueryPool() != VK_NULL_HANDLE) {
				vkCmdWriteTimestamp(recordingCommandBuffer, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, slot.timestampQueryPool(), 1);
			}
			scheduler.submitAsync(stack, slot, "step-ring", completed -> completeSubmission(completed, fullStep));
		}
		finally {
			recordingCommandBuffer = null;
			recordingTimeStep = 0.0F;
		}
		long schedulingNanos = System.nanoTime() - started;
		if (fullStep) lastStepNanos = schedulingNanos;
	}

	private void completeSubmission(GpuStepScheduler.SubmissionSlot slot, boolean fullStep) {
		if (fullStep && slot.timestampQueryPool() != VK_NULL_HANDLE) {
			long gpuStepNanos = scheduler.readTimestampNanos(slot);
			lastGpuStepNanos = gpuStepNanos;
			synchronized (capturedGpuStepNanos) {
				if (captureGpuStepTimings) capturedGpuStepNanos.add(gpuStepNanos);
			}
		}
		updateCachedDiagnostics(slot.stagingBuffer().bytes(buffer(DIAGNOSTICS).size()));
	}

	private void solvePressure(ByteBuffer push, VkMemoryBarrier.Buffer barrier) {
		switch (pressureSolver) {
			case JACOBI -> {
				for (int iteration = 0; iteration < config.pressureIterations(); iteration++) {
					dispatch(push, STAGE_JACOBI, iteration, config.cellCount());
					computeBarrier(barrier);
				}
			}
			case RBGS_SOR -> {
				for (int iteration = 0; iteration < config.pressureIterations(); iteration++) {
					dispatch(push, STAGE_RBGS, iteration, 0, config.cellCount());
					computeBarrier(barrier);
					dispatch(push, STAGE_RBGS, iteration, 1, config.cellCount());
					computeBarrier(barrier);
				}
			}
			case PCG, PCG_DIAGONAL -> {
				dispatch(push, STAGE_PCG_INIT, 0, config.cellCount());
				computeBarrier(barrier);
				for (int iteration = 0; iteration < config.pressureIterations(); iteration++) {
					dispatch(push, STAGE_PCG_CLEAR, iteration, 0, 1);
					computeBarrier(barrier);
					dispatch(push, STAGE_PCG_APPLY, iteration, config.cellCount());
					computeBarrier(barrier);
					dispatch(push, STAGE_PCG_REDUCE, iteration, 0, config.cellCount());
					computeBarrier(barrier);
					dispatch(push, STAGE_PCG_UPDATE_XR, iteration, config.cellCount());
					computeBarrier(barrier);
					dispatch(push, STAGE_PCG_CLEAR, iteration, 1, 1);
					computeBarrier(barrier);
					dispatch(push, STAGE_PCG_REDUCE, iteration, 1, config.cellCount());
					computeBarrier(barrier);
					dispatch(push, STAGE_PCG_UPDATE_P, iteration, config.cellCount());
					computeBarrier(barrier);
				}
			}
			case MULTIGRID_TWO_LEVEL -> {
				for (int cycle = 0; cycle < config.pressureIterations(); cycle++) {
					for (int sweep = 0; sweep < MG_PRE_SMOOTHING_SWEEPS; sweep++) smoothFineGrid(push, barrier, sweep);
					dispatch(push, STAGE_MG_RESTRICT, cycle, coarseCellCount());
					computeBarrier(barrier);
					for (int iteration = 0; iteration < MG_COARSE_ITERATIONS; iteration++) {
						dispatch(push, STAGE_MG_COARSE_JACOBI, iteration, coarseCellCount());
						computeBarrier(barrier);
					}
					dispatch(push, STAGE_MG_PROLONG, cycle, MG_COARSE_ITERATIONS & 1, config.cellCount());
					computeBarrier(barrier);
					for (int sweep = 0; sweep < MG_POST_SMOOTHING_SWEEPS; sweep++) smoothFineGrid(push, barrier, sweep);
				}
			}
			case MULTIGRID_HIERARCHY -> {
				int levels = multigridLevelCount();
				for (int cycle = 0; cycle < config.pressureIterations(); cycle++) {
					for (int sweep = 0; sweep < MG_PRE_SMOOTHING_SWEEPS; sweep++) smoothFineGrid(push, barrier, sweep);
					dispatchHierarchy(push, STAGE_MGH_RESTRICT_FINE, cycle, 1, 1, 0, multigridLevelCellCount(1));
					computeBarrier(barrier);
					for (int level = 1; level < levels; level++) {
						smoothHierarchyLevel(push, barrier, level, MGH_LEVEL_SMOOTHING_ITERATIONS);
						dispatchHierarchy(push, STAGE_MGH_RESTRICT_COARSE, cycle, level + 1,
								level + 1, level, multigridLevelCellCount(level + 1));
						computeBarrier(barrier);
					}
					smoothHierarchyLevel(push, barrier, levels, levels == 1 ? MG_COARSE_ITERATIONS : MGH_BOTTOM_ITERATIONS);
					for (int level = levels - 1; level >= 1; level--) {
						dispatchHierarchy(push, STAGE_MGH_PROLONG_COARSE, cycle, level,
								level, level + 1, multigridLevelCellCount(level));
						computeBarrier(barrier);
						smoothHierarchyLevel(push, barrier, level, MGH_LEVEL_SMOOTHING_ITERATIONS);
					}
					dispatchHierarchy(push, STAGE_MGH_PROLONG_FINE, cycle, 0, 0, 1, config.cellCount());
					computeBarrier(barrier);
					for (int sweep = 0; sweep < MG_POST_SMOOTHING_SWEEPS; sweep++) smoothFineGrid(push, barrier, sweep);
				}
			}
		}
	}

	private void smoothFineGrid(ByteBuffer push, VkMemoryBarrier.Buffer barrier, int sweep) {
		dispatch(push, STAGE_RBGS, sweep, 0, config.cellCount());
		computeBarrier(barrier);
		dispatch(push, STAGE_RBGS, sweep, 1, config.cellCount());
		computeBarrier(barrier);
	}

	private void smoothHierarchyLevel(ByteBuffer push, VkMemoryBarrier.Buffer barrier, int level, int iterations) {
		for (int iteration = 0; iteration < iterations; iteration++) {
			dispatchHierarchy(push, STAGE_MGH_JACOBI, iteration, level, level, 0,
					multigridLevelCellCount(level));
			computeBarrier(barrier);
		}
	}

	private void uploadInitialState(GpuStepScheduler.SubmissionSlot slot) {
		try (MemoryStack stack = stackPush()) {
			recordingCommandBuffer = scheduler.begin(slot, "upload");
			VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
			barrier.get(0).sType$Default().srcAccessMask(VK_ACCESS_HOST_WRITE_BIT).dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
			vkCmdPipelineBarrier(recordingCommandBuffer, VK_PIPELINE_STAGE_HOST_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, barrier, null, null);
			copyBuffer(stack, readback.transferBuffer(), buffer(POSITIONS), buffer(POSITIONS).size());
			vkCmdFillBuffer(recordingCommandBuffer, buffer(VELOCITIES).handle(), 0, buffer(VELOCITIES).size(), 0);
			vkCmdFillBuffer(recordingCommandBuffer, buffer(SOLIDS).handle(), 0, buffer(SOLIDS).size(), 0);
			barrier.get(0).srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
			vkCmdPipelineBarrier(recordingCommandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, barrier, null, null);
			scheduler.submitAndWait(stack, slot, "upload");
		}
		finally { recordingCommandBuffer = null; }
	}

	private int populateSolidMask(CellSelector solidSelector, GpuBuffer target) {
		MemoryUtil.memSet(target.mappedAddress(), 0, buffer(SOLIDS).size());
		IntBuffer mask = target.bytes(buffer(SOLIDS).size()).asIntBuffer();
		int count = 0;
		for (int z = 0; z < config.nz(); z++) for (int y = 0; y < config.ny(); y++) for (int x = 0; x < config.nx(); x++) {
			if (!solidSelector.filled(x, y, z)) continue;
			mask.put(x + config.nx() * (y + config.ny() * z), 1);
			count++;
		}
		return count;
	}

	private void uploadSolidMask(GpuStepScheduler.SubmissionSlot slot) {
		try (MemoryStack stack = stackPush()) {
			recordingCommandBuffer = scheduler.begin(slot, "solids");
			VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
			barrier.get(0).sType$Default().srcAccessMask(VK_ACCESS_HOST_WRITE_BIT).dstAccessMask(VK_ACCESS_TRANSFER_READ_BIT);
			vkCmdPipelineBarrier(recordingCommandBuffer, VK_PIPELINE_STAGE_HOST_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, barrier, null, null);
			copyBuffer(stack, readback.transferBuffer(), buffer(SOLIDS), buffer(SOLIDS).size());
			barrier.get(0).srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
			vkCmdPipelineBarrier(recordingCommandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, barrier, null, null);
			scheduler.submitAndWait(stack, slot, "solids");
		}
		finally { recordingCommandBuffer = null; }
	}

	private ByteBuffer readableBuffer(int index) { return readback.readableBuffer(index); }
	private ByteBuffer readableBuffer(int index, long byteCount) { return readback.readableBuffer(index, byteCount); }
	private ByteBuffer copiedBuffer(int index) { return readback.copiedBuffer(index); }
	private ByteBuffer copiedBuffer(int index, long byteCount) { return readback.copiedBuffer(index, byteCount); }

	private void copyBuffer(MemoryStack stack, GpuBuffer source, GpuBuffer destination, long bytes) {
		GpuReadback.recordCopy(requireRecordingCommandBuffer(), stack, source, destination, bytes);
	}

	private void awaitSubmittedWork() { scheduler.awaitSubmittedWork(); }

	private VkCommandBuffer requireRecordingCommandBuffer() {
		if (recordingCommandBuffer == null) throw new IllegalStateException("No Vulkan command buffer is being recorded");
		return recordingCommandBuffer;
	}

	private void updateCachedDiagnostics(ByteBuffer bytes) {
		IntBuffer diagnostics = bytes.duplicate().order(ByteOrder.nativeOrder()).asIntBuffer();
		float pre = Float.intBitsToFloat(diagnostics.get(0));
		float post = Float.intBitsToFloat(diagnostics.get(1));
		float maxPressure = Float.intBitsToFloat(diagnostics.get(2));
		float sumSquared = Float.intBitsToFloat(diagnostics.get(3));
		float volume = Float.intBitsToFloat(diagnostics.get(4));
		float weightedX = Float.intBitsToFloat(diagnostics.get(5));
		float rms = (float) Math.sqrt(sumSquared / config.cellCount());
		float centerX = volume == 0.0F ? Float.NaN : weightedX / volume;
		if (!Float.isFinite(pre) || !Float.isFinite(post) || !Float.isFinite(maxPressure)
				|| !Float.isFinite(rms) || (particleCount > 0 && (!Float.isFinite(volume) || !Float.isFinite(centerX)))) {
			throw new IllegalStateException("Non-finite compact GPU diagnostics");
		}
		lastDiagnostics = new Diagnostics(pre, post, rms, maxPressure, volume, centerX);
	}

	private void dispatch(ByteBuffer push, int stage, int iteration, int workItems) {
		dispatch(push, stage, iteration, 0, workItems);
	}

	private void dispatch(ByteBuffer push, int stage, int iteration, int flags, int workItems) {
		dispatchHierarchy(push, stage, iteration, flags, 0, 0, workItems);
	}

	private void dispatchHierarchy(ByteBuffer push, int stage, int iteration, int flags,
	                               int currentLevel, int otherLevel, int workItems) {
		push.clear();
		push.putInt(stage).putInt(config.nx()).putInt(config.ny()).putInt(config.nz());
		push.putInt(particleCount).putInt(iteration).putInt(config.pressureIterations()).putInt(flags);
		push.putFloat(recordingTimeStep).putFloat(config.cellSize()).putFloat(config.gravity()).putFloat(config.density());
		push.putInt(pressureSolver.code());
		putHierarchyLevel(push, currentLevel);
		putHierarchyLevel(push, otherLevel);
		push.putInt(multigridCellCount());
		push.flip();
		VkCommandBuffer commandBuffer = requireRecordingCommandBuffer();
		vkCmdPushConstants(commandBuffer, bindings.pipelineLayout(), VK_SHADER_STAGE_COMPUTE_BIT, 0, push);
		vkCmdDispatch(commandBuffer, groups(workItems), 1, 1);
	}

	private void putHierarchyLevel(ByteBuffer push, int level) {
		if (level == 0) {
			push.putInt(0).putInt(0).putInt(0).putInt(0);
			return;
		}
		push.putInt(multigridLevelDimension(config.nx(), level));
		push.putInt(multigridLevelDimension(config.ny(), level));
		push.putInt(multigridLevelDimension(config.nz(), level));
		push.putInt(multigridLevelOffset(level));
	}

	private void computeBarrier(VkMemoryBarrier.Buffer barrier) {
		VkCommandBuffer commandBuffer = requireRecordingCommandBuffer();
		barrier.get(0).srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
		vkCmdPipelineBarrier(commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, barrier, null, null);
	}

	private static int groups(int workItems) { return Math.max(1, (workItems + LOCAL_SIZE - 1) / LOCAL_SIZE); }
	private int maxFaceCount() { return Math.max(config.uCount(), Math.max(config.vCount(), config.wCount())); }
	private int maxGridCount() { return Math.max(multigridCellCount(), Math.max(config.cellCount(), maxFaceCount())); }
	private int coarseCellCount() {
		return Math.multiplyExact(Math.multiplyExact((config.nx() + 1) / 2, (config.ny() + 1) / 2), (config.nz() + 1) / 2);
	}

	int multigridLevelCount() {
		int nx = config.nx(), ny = config.ny(), nz = config.nz();
		int floorSize = Math.max(nx, Math.max(ny, nz)) <= 32 ? 8 : 4;
		for (int level = 1; level <= 16; level++) {
			nx = (nx + 1) / 2; ny = (ny + 1) / 2; nz = (nz + 1) / 2;
			if (nx <= floorSize || ny <= floorSize || nz <= floorSize) return level;
		}
		throw new IllegalStateException("multigrid hierarchy exceeds 16 levels");
	}

	private int multigridLevelCellCount(int level) {
		int nx = multigridLevelDimension(config.nx(), level);
		int ny = multigridLevelDimension(config.ny(), level);
		int nz = multigridLevelDimension(config.nz(), level);
		return Math.multiplyExact(Math.multiplyExact(nx, ny), nz);
	}

	private static int multigridLevelDimension(int dimension, int level) {
		for (int i = 0; i < level; i++) dimension = (dimension + 1) / 2;
		return dimension;
	}

	private int multigridLevelOffset(int level) {
		int offset = 0;
		for (int current = 1; current < level; current++) offset = Math.addExact(offset, multigridLevelCellCount(current));
		return offset;
	}

	private int multigridCellCount() {
		int total = 0;
		for (int level = 1; level <= multigridLevelCount(); level++) total = Math.addExact(total, multigridLevelCellCount(level));
		return total;
	}

	private GpuBuffer buffer(int index) { return resources.buffer(index); }

	@Override
	public void close() {
		Throwable failure = null;
		if (scheduler != null) {
			try { scheduler.close(); }
			catch (RuntimeException | LinkageError exception) { failure = exception; }
			scheduler = null;
		}
		if (readback != null) {
			try { readback.close(); }
			catch (RuntimeException | LinkageError exception) { failure = append(failure, exception); }
			readback = null;
		}
		if (bindings != null) {
			try { bindings.close(); }
			catch (RuntimeException | LinkageError exception) { failure = append(failure, exception); }
			bindings = null;
		}
		if (resources != null) {
			try { resources.close(); }
			catch (RuntimeException | LinkageError exception) { failure = append(failure, exception); }
			resources = null;
		}
		if (deviceContext != null) {
			try { deviceContext.close(); }
			catch (RuntimeException | LinkageError exception) { failure = append(failure, exception); }
			deviceContext = null;
		}
		if (failure instanceof RuntimeException runtime) throw runtime;
		if (failure instanceof LinkageError linkage) throw linkage;
	}

	private static Throwable append(Throwable current, Throwable addition) {
		if (current == null) return addition;
		current.addSuppressed(addition);
		return current;
	}

	record Config(int nx, int ny, int nz, float cellSize, float timeStep, float gravity, float density, int pressureIterations, int maxParticles) {
		Config {
			if (nx <= 0 || ny <= 0 || nz <= 0 || cellSize <= 0 || timeStep <= 0 || pressureIterations <= 0 || maxParticles <= 0) throw new IllegalArgumentException("Invalid GPU FLIP config");
		}
		int cellCount() { return Math.multiplyExact(Math.multiplyExact(nx, ny), nz); }
		int uCount() { return Math.multiplyExact(Math.multiplyExact(nx + 1, ny), nz); }
		int vCount() { return Math.multiplyExact(Math.multiplyExact(nx, ny + 1), nz); }
		int wCount() { return Math.multiplyExact(Math.multiplyExact(nx, ny), nz + 1); }
	}

	record SnapshotData(byte[] density, byte[] markers, byte[] solids) { }
	record Diagnostics(float preProjectionMaximumDivergence, float maximumDivergence, float rmsDivergence,
	                   float maximumPressure, float particleVolume, float centerOfMassX) { }
	record PressureProfile(float[] averageByY, int[] fluidCellsByY) { }
	record ReadbackStats(long fullBytes, long compactBytes, long fullNanos, long compactNanos, long fullChecksum, long compactChecksum) { }
	record HierarchyLevelStats(int level, int cells, int fluidCells, float maximumRhs, float maximumPressure,
	                           float minimumPositiveDiagonal, float maximumDiagonal, float maximumWeight) { }
	enum MemoryMode {
		DEVICE_LOCAL_STAGING("device-local+staging"), HOST_VISIBLE("host-visible baseline");
		private final String label;
		MemoryMode(String label) { this.label = label; }
		String label() { return label; }
	}
	enum PressureSolver {
		JACOBI(0, "Jacobi"), RBGS_SOR(1, "RBGS/SOR(omega=1.65)"), PCG(2, "PCG(identity)"),
		PCG_DIAGONAL(3, "PCG(diagonal)"), MULTIGRID_TWO_LEVEL(4, "MG2(V-cycle 2+24+3)"),
		MULTIGRID_HIERARCHY(5, "MGH(full V-cycle)");
		private final int code;
		private final String label;
		PressureSolver(int code, String label) { this.code = code; this.label = label; }
		int code() { return code; }
		String label() { return label; }
	}
	@FunctionalInterface private interface CellSelector { boolean filled(int x, int y, int z); }
}
