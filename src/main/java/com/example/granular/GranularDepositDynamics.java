package com.example.granular;

/**
 * Bounded local scheduler for parcel settling, angle-of-repose relaxation and shear wake-up.
 * Deposit inventory mutations remain exact and are delegated to {@link GranularInventory}.
 */
public final class GranularDepositDynamics {
	public static final int SETTLE_CONTACT_STEPS = 3;
	public static final int SLEEP_LOGICAL_STEPS = 20;
	private static final long MAX_RELAXATION_SVQ_PER_OPERATION = SolidVolume.SVQ_PER_CELL / 16L;
	private static final int[] NEIGHBOR_X = {-1, 1, 0, 0, -1, -1, 1, 1};
	private static final int[] NEIGHBOR_Z = {0, 0, -1, 1, -1, 1, -1, 1};

	public record Velocity(float x, float y, float z) {
		public Velocity {
			if (!(Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z))) {
				throw new IllegalArgumentException("Granular wake velocity is not finite");
			}
		}
	}

	public record Stats(int settledParcels, int relaxedMoves, int wokenParcels,
	                    int processedColumns, int dirtyColumns) { }

	public interface Environment {
		long geometricOpenSvq(int cell);
		boolean horizontalPathOpen(int fromCell, int directionX, int directionZ);
		double shields(int surfaceCell, ParticulateMaterial material);
		Velocity wakeVelocity(int surfaceCell);
	}

	private final GranularRegistry registry;
	private final GranularParcelStore parcels;
	private final GranularDepositStore deposits;
	private final GranularInventory inventory;
	private final int sizeX, sizeY, sizeZ, columnCount;
	private final float cellSize;
	private final int[] dirtyQueue, stableAge;
	private final boolean[] queued;
	private int queueHead, queueSize;
	private long logicalStep;

	public GranularDepositDynamics(
			GranularRegistry registry, GranularParcelStore parcels, GranularDepositStore deposits,
			GranularInventory inventory, int sizeX, int sizeY, int sizeZ, float cellSize
	) {
		if (registry == null || parcels == null || deposits == null || inventory == null) {
			throw new IllegalArgumentException("Granular deposit dynamics dependencies are missing");
		}
		if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0 || deposits.cellCount() != sizeX * sizeY * sizeZ
				|| !(Float.isFinite(cellSize) && cellSize > 0.0F)) {
			throw new IllegalArgumentException("Granular deposit dynamics geometry is invalid");
		}
		this.registry = registry; this.parcels = parcels; this.deposits = deposits; this.inventory = inventory;
		this.sizeX = sizeX; this.sizeY = sizeY; this.sizeZ = sizeZ; this.cellSize = cellSize;
		this.columnCount = Math.multiplyExact(sizeX, sizeZ);
		this.dirtyQueue = new int[columnCount]; this.stableAge = new int[columnCount];
		this.queued = new boolean[columnCount];
	}

	/** Marks only the affected surface column and its one-cell slope neighborhood. */
	public void disturbCell(int cell) {
		requireCell(cell);
		int x = cell % sizeX, z = cell / (sizeX * sizeY);
		disturbColumn(x, z);
		for (int neighbor = 0; neighbor < NEIGHBOR_X.length; neighbor++) {
			disturbColumn(x + NEIGHBOR_X[neighbor], z + NEIGHBOR_Z[neighbor]);
		}
	}

	public int dirtyColumnCount() { return queueSize; }

	/** Explicit diagnostic; normal logical steps do not scan sleeping deposit columns. */
	public int sleepingColumnCount() {
		int sleeping = 0;
		for (int column = 0; column < columnCount; column++) {
			if (columnBulkSvq(column) > 0L && stableAge[column] >= SLEEP_LOGICAL_STEPS) sleeping++;
		}
		return sleeping;
	}

	public int occupiedColumnCount() {
		int occupied = 0;
		for (int column = 0; column < columnCount; column++) if (columnBulkSvq(column) > 0L) occupied++;
		return occupied;
	}

	public Stats step(Environment environment, float dt) {
		if (environment == null) throw new IllegalArgumentException("Granular deposit environment is missing");
		if (!(Float.isFinite(dt) && dt > 0.0F)) throw new IllegalArgumentException("Granular deposit dt is invalid");
		int operations = 0, settled = 0, relaxed = 0, woken = 0;

		for (int parcelIndex = 0; parcelIndex < parcels.count()
				&& operations < GranularLimits.MAX_OPERATIONS_PER_LOGICAL_STEP;) {
			GranularParcelStore.Parcel parcel = parcels.parcel(parcelIndex);
			if ((parcel.flags() & GranularParcelStore.FLAG_SUPPORTED) == 0
					|| parcel.contactAge() < SETTLE_CONTACT_STEPS) {
				parcelIndex++;
				continue;
			}
			int x = coordinate(parcel.x(), sizeX), yStart = coordinate(parcel.y(), sizeY);
			int z = coordinate(parcel.z(), sizeZ);
			boolean deposited = false;
			for (int y = yStart; y < sizeY; y++) {
				int cell = cell(x, y, z);
				long open = environment.geometricOpenSvq(cell);
				if (open <= 0L) continue;
				if (inventory.depositParcel(parcelIndex, cell, open)) {
					disturbCell(cell);
					deposited = true; settled++; operations++;
					break;
				}
			}
			if (!deposited) parcelIndex++;
		}

		int scheduled = Math.min(queueSize, GranularLimits.MAX_OPERATIONS_PER_LOGICAL_STEP - operations);
		for (int visit = 0; visit < scheduled; visit++) {
			int column = pollColumn();
			if (columnBulkSvq(column) == 0L) { stableAge[column] = 0; continue; }
			int surfaceCell = surfaceCell(column);
			GranularDepositStore.Group exposed = deposits.dominantGroup(surfaceCell);
			if (exposed == null) { stableAge[column] = 0; continue; }
			ParticulateMaterial material = registry.material(exposed.materialHandle());
			boolean changed = tryWake(column, surfaceCell, exposed, material, environment, dt);
			if (changed) woken++;
			else if (tryRelax(column, surfaceCell, exposed, material, environment)) {
				changed = true; relaxed++;
			}
			operations++;
			if (changed) {
				int x = column % sizeX, z = column / sizeX;
				disturbColumn(x, z);
				for (int neighbor = 0; neighbor < NEIGHBOR_X.length; neighbor++) {
					disturbColumn(x + NEIGHBOR_X[neighbor], z + NEIGHBOR_Z[neighbor]);
				}
			} else {
				stableAge[column] = Math.min(SLEEP_LOGICAL_STEPS, stableAge[column] + 1);
				if (stableAge[column] < SLEEP_LOGICAL_STEPS) enqueue(column);
			}
		}
		logicalStep++;
		return new Stats(settled, relaxed, woken, scheduled, queueSize);
	}

	private boolean tryWake(int column, int surfaceCell, GranularDepositStore.Group exposed,
	                        ParticulateMaterial material, Environment environment, float dt) {
		double shields = environment.shields(surfaceCell, material);
		if (!(Double.isFinite(shields) && shields > material.criticalShields())) return false;
		long erosion = Math.max(1L, (long) Math.floor(material.erosionRateSvqPerSecond() * (double) dt));
		erosion = Math.min(erosion, Math.min(exposed.solidVolumeSvq(), material.maximumActiveParcelSvq()));
		Velocity velocity = environment.wakeVelocity(surfaceCell);
		int x = column % sizeX, z = column / sizeX;
		float height = (float) ((double) columnSurfaceSvq(column) / SolidVolume.SVQ_PER_CELL * cellSize);
		float px = (x + 0.5F) * cellSize, py = Math.min(sizeY * cellSize - cellSize * 0.05F,
				height + cellSize * 0.05F), pz = (z + 0.5F) * cellSize;
		try {
			return inventory.wake(surfaceCell, exposed.materialHandle(), exposed.solidCompositionHandle(), erosion,
					px, py, pz, velocity.x(), velocity.y(), velocity.z());
		} catch (IllegalArgumentException compositionSplitUnavailable) {
			return false;
		}
	}

	private boolean tryRelax(int column, int surfaceCell, GranularDepositStore.Group exposed,
	                         ParticulateMaterial material, Environment environment) {
		int x = column % sizeX, z = column / sizeX;
		double height = (double) columnSurfaceSvq(column) / SolidVolume.SVQ_PER_CELL;
		int bestColumn = -1;
		double bestExcess = 0.0;
		int directionStart = (int) ((column * 0x9E3779B9L + logicalStep) & 7L);
		for (int offset = 0; offset < NEIGHBOR_X.length; offset++) {
			int neighbor = (directionStart + offset) & 7;
			int dx = NEIGHBOR_X[neighbor], dz = NEIGHBOR_Z[neighbor], nx = x + dx, nz = z + dz;
			if (!insideColumn(nx, nz) || !environment.horizontalPathOpen(surfaceCell, dx, dz)) continue;
			int candidate = nz * sizeX + nx;
			double distance = neighbor < 4 ? 1.0 : Math.sqrt(2.0);
			double excess = height - (double) columnSurfaceSvq(candidate) / SolidVolume.SVQ_PER_CELL
					- distance * Math.tan(material.reposeAngleRadians());
			if (excess > bestExcess) { bestExcess = excess; bestColumn = candidate; }
		}
		if (bestColumn < 0) return false;
		long requestedBulk = Math.max(1L, (long) Math.floor(bestExcess * 0.5 * SolidVolume.SVQ_PER_CELL));
		long requestedSkeleton = requestedBulk
				* (GranularLimits.POROSITY_SCALE - (long) deposits.porosityQ(surfaceCell))
				/ GranularLimits.POROSITY_SCALE;
		requestedSkeleton = Math.min(MAX_RELAXATION_SVQ_PER_OPERATION,
				Math.min(exposed.solidVolumeSvq(), requestedSkeleton));
		if (requestedSkeleton <= 0L) return false;
		int receiverX = bestColumn % sizeX, receiverZ = bestColumn / sizeX;
		for (int y = 0; y < sizeY; y++) {
			int receiver = cell(receiverX, y, receiverZ);
			long open = environment.geometricOpenSvq(receiver);
			if (open <= 0L) continue;
			if (deposits.tryMove(surfaceCell, receiver, exposed.materialHandle(),
					exposed.solidCompositionHandle(), requestedSkeleton, open)) {
				disturbColumn(receiverX, receiverZ);
				return true;
			}
		}
		return false;
	}

	private long columnBulkSvq(int column) {
		int x = column % sizeX, z = column / sizeX;
		long total = 0L;
		for (int y = 0; y < sizeY; y++) total = Math.addExact(total, deposits.cellBulkVolumeSvq(cell(x, y, z)));
		return total;
	}

	private long columnSurfaceSvq(int column) {
		int x = column % sizeX, z = column / sizeX;
		long surface = 0L;
		for (int y = 0; y < sizeY; y++) {
			long bulk = deposits.cellBulkVolumeSvq(cell(x, y, z));
			if (bulk > 0L) surface = Math.max(surface, Math.addExact(Math.multiplyExact((long) y,
					SolidVolume.SVQ_PER_CELL), bulk));
		}
		return surface;
	}

	private int surfaceCell(int column) {
		int x = column % sizeX, z = column / sizeX;
		for (int y = sizeY - 1; y >= 0; y--) {
			int cell = cell(x, y, z);
			if (deposits.cellSolidVolumeSvq(cell) > 0L) return cell;
		}
		throw new IllegalStateException("Occupied granular column has no surface cell");
	}

	private void disturbColumn(int x, int z) {
		if (!insideColumn(x, z)) return;
		int column = z * sizeX + x;
		stableAge[column] = 0;
		enqueue(column);
	}

	private void enqueue(int column) {
		if (queued[column]) return;
		if (queueSize == columnCount) throw new IllegalStateException("Granular dirty queue exceeded column budget");
		int tail = (queueHead + queueSize) % columnCount;
		dirtyQueue[tail] = column; queued[column] = true; queueSize++;
	}

	private int pollColumn() {
		int column = dirtyQueue[queueHead];
		queueHead = (queueHead + 1) % columnCount; queueSize--; queued[column] = false;
		return column;
	}

	private int coordinate(float position, int size) {
		return Math.min(size - 1, Math.max(0, (int) Math.floor(position / cellSize)));
	}

	private boolean insideColumn(int x, int z) { return x >= 0 && x < sizeX && z >= 0 && z < sizeZ; }
	private int cell(int x, int y, int z) { return (z * sizeY + y) * sizeX + x; }
	private void requireCell(int cell) {
		if (cell < 0 || cell >= deposits.cellCount()) throw new IllegalArgumentException("Unknown granular cell " + cell);
	}
}
