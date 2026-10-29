package mmm.granular;

/**
 * Persistent dirty-column and sleep scheduling state for granular inventories.
 */
public final class GranularDepositDynamics {
	public static final int SETTLE_CONTACT_STEPS = 3;
	public static final int SLEEP_LOGICAL_STEPS = 20;
	private static final int[] NEIGHBOR_X = {-1, 1, 0, 0, -1, -1, 1, 1};
	private static final int[] NEIGHBOR_Z = {0, 0, -1, 1, -1, 1, -1, 1};

	public record Stats(int settledParcels, int relaxedMoves, int wokenParcels,
	                    int processedColumns, int dirtyColumns,
	                    mmm.units.SignedDyadic physicalHeat, mmm.units.SignedDyadic mechanicalRounding) {
		public Stats(int settledParcels, int relaxedMoves, int wokenParcels, int processedColumns, int dirtyColumns) {
			this(settledParcels, relaxedMoves, wokenParcels, processedColumns, dirtyColumns,
					mmm.units.SignedDyadic.ZERO, mmm.units.SignedDyadic.ZERO);
		}
		public Stats withCompaction(GranularInventory.CompactionStats compaction) {
			return new Stats(settledParcels, relaxedMoves, wokenParcels, processedColumns, dirtyColumns,
					physicalHeat.add(compaction.physicalHeat()), mechanicalRounding.add(compaction.mechanicalRounding()));
		}
	}

	/** Ephemeral deterministic scheduler state carried between private backend candidates, never inventory. */
	public record State(long logicalStep, int[] stableAge, int[] queuedColumns) {
		public State {
			if (logicalStep < 0L || stableAge == null || queuedColumns == null) {
				throw new IllegalArgumentException("Granular scheduler state is invalid");
			}
			stableAge = stableAge.clone(); queuedColumns = queuedColumns.clone();
			for (int age : stableAge) if (age < 0 || age > SLEEP_LOGICAL_STEPS) {
				throw new IllegalArgumentException("Granular scheduler stable age is invalid");
			}
			boolean[] seen = new boolean[stableAge.length];
			for (int column : queuedColumns) {
				if (column < 0 || column >= stableAge.length || seen[column]) {
					throw new IllegalArgumentException("Granular scheduler queue is invalid");
				}
				seen[column] = true;
			}
		}

		@Override public int[] stableAge() { return stableAge.clone(); }
		@Override public int[] queuedColumns() { return queuedColumns.clone(); }
	}

	private final GranularDepositStore deposits;
	private final int sizeX, sizeY, sizeZ, columnCount;
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
		this.deposits = deposits;
		this.sizeX = sizeX; this.sizeY = sizeY; this.sizeZ = sizeZ;
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

	public State captureState() {
		int[] queue = new int[queueSize];
		for (int index = 0; index < queueSize; index++) {
			queue[index] = dirtyQueue[(queueHead + index) % columnCount];
		}
		return new State(logicalStep, stableAge, queue);
	}

	public void restoreState(State state) {
		if (state == null || state.stableAge().length != columnCount) {
			throw new IllegalArgumentException("Granular scheduler state dimensions changed");
		}
		int[] ages = state.stableAge(); int[] queue = state.queuedColumns();
		java.util.Arrays.fill(dirtyQueue, 0); java.util.Arrays.fill(queued, false);
		System.arraycopy(ages, 0, stableAge, 0, columnCount);
		for (int index = 0; index < queue.length; index++) {
			dirtyQueue[index] = queue[index]; queued[queue[index]] = true;
		}
		queueHead = 0; queueSize = queue.length; logicalStep = state.logicalStep();
	}

	/** Explicit diagnostic; the shear check in a motion step does not change subcritical sleep ages. */
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

	/** Clears transient dirty/sleep scheduling after the authoritative deposit inventory is erased. */
	public void clear() {
		java.util.Arrays.fill(dirtyQueue, 0); java.util.Arrays.fill(stableAge, 0);
		java.util.Arrays.fill(queued, false); queueHead = 0; queueSize = 0;
	}

	private long columnBulkSvq(int column) {
		int x = column % sizeX, z = column / sizeX;
		long total = 0L;
		for (int y = 0; y < sizeY; y++) total = Math.addExact(total, deposits.cellBulkVolumeSvq(cell(x, y, z)));
		return total;
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

	private boolean insideColumn(int x, int z) { return x >= 0 && x < sizeX && z >= 0 && z < sizeZ; }
	private int cell(int x, int y, int z) { return (z * sizeY + y) * sizeX + x; }
	private void requireCell(int cell) {
		if (cell < 0 || cell >= deposits.cellCount()) throw new IllegalArgumentException("Unknown granular cell " + cell);
	}
}
