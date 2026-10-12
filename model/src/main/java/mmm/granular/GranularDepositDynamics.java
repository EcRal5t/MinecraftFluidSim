package mmm.granular;

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

	public record Stats(int settledParcels, int relaxedMoves, int wokenParcels,
	                    int processedColumns, int dirtyColumns) { }

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

	public interface Environment {
		long geometricOpenSvq(int cell);
		default double gravity() { return 0.0; }
		boolean horizontalPathOpen(int fromCell, int directionX, int directionZ);
		default boolean verticalPathOpen(float x, float fromY, float z, float toY, long solidSvq) { return true; }
		/** Candidate bed-cell visits charged before the pure geometric query. */
		default int verticalPathWork(float x, float fromY, float z, float toY, long solidSvq) { return 0; }
		/** Fixed local cut geometry work is included in each candidate target's logical charge. */
		default boolean bedGeometryFits(int cell, long bulkSvq) { return true; }
		default boolean horizontalTransferPathOpen(float x, float y, float z, float toX, float toZ, long solidSvq) { return true; }
		default int horizontalTransferPathWork(float x, float y, float z, float toX, float toZ, long solidSvq) { return 0; }
		double shields(int surfaceCell, ParticulateMaterial material);
		default void receiveContactImpulse(double x, double y, double z) { }
		default boolean planSettlement(GranularInventory.SettlementPlan plan) { return true; }
		default void cancelSettlement(GranularInventory.SettlementPlan plan) { }
		default boolean planRelaxation(int fromCell, int toCell, long solidSvq) { return true; }
		default void cancelRelaxation(int fromCell, int toCell, long solidSvq) { }
		default void requireThermalRelaxation(
				int fromCell, int toCell, int material, int composition, long movedSvq, long sourceTotalSvq
		) { }
		default void commitThermalRelaxation(
				int fromCell, int toCell, int material, int composition, long movedSvq, long sourceTotalSvq
		) { }
		default boolean planWake(int cell, long solidSvq, float x, float y, float z) { return true; }
		default void cancelWake(int cell, long solidSvq, float x, float y, float z) { }
		default void onSolidChanged(int cell) { }
	}

	private final GranularRegistry registry;
	private final GranularParcelStore parcels;
	private final GranularDepositStore deposits;
	private final GranularInventory inventory;
	private final GranularBedAdmission admission;
	private final GranularSettlementGeometryPlan settlement;
	private final int sizeX, sizeY, sizeZ, columnCount;
	private final float cellSize;
	private final int[] dirtyQueue, stableAge;
	private final boolean[] queued, energyDeferred;
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
		this.admission = new GranularBedAdmission(parcels, deposits, sizeX, sizeY, sizeZ, cellSize);
		this.settlement = new GranularSettlementGeometryPlan(inventory, admission, parcels.capacity());
		this.sizeX = sizeX; this.sizeY = sizeY; this.sizeZ = sizeZ; this.cellSize = cellSize;
		this.columnCount = Math.multiplyExact(sizeX, sizeZ);
		this.dirtyQueue = new int[columnCount]; this.stableAge = new int[columnCount];
		this.queued = new boolean[columnCount];
		this.energyDeferred = new boolean[columnCount];
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

	public Stats step(Environment environment, float dt) {
		if (environment == null) throw new IllegalArgumentException("Granular deposit environment is missing");
		if (!(Float.isFinite(dt) && dt > 0.0F)) throw new IllegalArgumentException("Granular deposit dt is invalid");
		int operations = 0, settled = 0, relaxed = 0, woken = 0;
		admission.rebuild();
		java.util.Arrays.fill(energyDeferred, false);

		for (int parcelIndex = 0; parcelIndex < parcels.count()
				&& operations + admission.visits() < GranularLimits.MAX_OPERATIONS_PER_LOGICAL_STEP;) {
			admission.reserveLogicalOperations(operations + 1);
			GranularParcelStore.Parcel parcel = parcels.parcel(parcelIndex);
			if ((parcel.flags() & GranularParcelStore.FLAG_SUPPORTED) == 0
					|| parcel.contactAge() < SETTLE_CONTACT_STEPS) {
				parcelIndex++;
				continue;
			}
			boolean deposited = false;
			int cell = settlementTarget(parcel, environment);
			if (cell >= 0) {
				var plan = settlement.find(parcelIndex, cell, index -> eligibleTarget(index, environment), environment::geometricOpenSvq);
				if (plan != null) {
					int[] indices = plan.parcelIndices();
					admission.reserveLogicalOperations(operations + indices.length);
					if (admission.spend(2 * indices.length)) {
						if (!settlementBedsFit(plan, environment)) {
							for (var target : plan.targets()) disturbCell(target.cell());
						} else if (!GranularBedPotential.allowsSettlement(plan, cellSize, sizeX, sizeY, environment.gravity())) {
							for (var target : plan.targets()) {
								int destination = target.cell();
								energyDeferred[(destination / (sizeX * sizeY)) * sizeX + destination % sizeX] = true;
								disturbCell(destination);
							}
						} else if (settlementSlopeAllowed(plan, environment) && environment.planSettlement(plan)) {
							commitSettlement(plan, indices, environment);
							deposited = true; settled += indices.length; operations += indices.length;
							parcelIndex = indices[indices.length - 1];
						} else if (!admission.budgetBlocked() && rollParcelDownhill(parcelIndex, parcel, cell, environment)) operations++;
					}
				}
				if (admission.budgetBlocked()) disturbCell(cell);
			}
			if (!deposited) parcelIndex++;
			if (admission.budgetBlocked()) break;
		}

		// Resting geometry can sleep while the carrier changes. Inspect only occupied surfaces on
		// motion steps; a supercritical local flow re-enqueues its bed without waking distant slopes.
		for (int column = 0; column < columnCount; column++) {
			if (queued[column]) continue;
			int surface = surfaceCell(column);
			if (surface < 0) continue;
			ParticulateMaterial material = registry.material(deposits.dominantGroup(surface).materialHandle());
			double shields = environment.shields(surface, material);
			if (Double.isFinite(shields) && shields > material.criticalShields()) {
				stableAge[column] = 0;
				enqueue(column);
			}
		}
		int scheduled = admission.budgetBlocked() ? 0
				: Math.min(queueSize, GranularLimits.MAX_OPERATIONS_PER_LOGICAL_STEP - operations - admission.visits());
		int processed = 0;
		for (int visit = 0; visit < scheduled
				&& operations + admission.visits() < GranularLimits.MAX_OPERATIONS_PER_LOGICAL_STEP; visit++) {
			admission.reserveLogicalOperations(operations + 1);
			int column = pollColumn();
			processed++;
			if (columnBulkSvq(column) == 0L) {
				stableAge[column] = 0;
				if (energyDeferred[column]) enqueue(column);
				continue;
			}
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
			if (admission.budgetBlocked()) { stableAge[column] = 0; enqueue(column); break; }
			if (changed) {
				int x = column % sizeX, z = column / sizeX;
				disturbColumn(x, z);
				for (int neighbor = 0; neighbor < NEIGHBOR_X.length; neighbor++) {
					disturbColumn(x + NEIGHBOR_X[neighbor], z + NEIGHBOR_Z[neighbor]);
				}
			} else if (energyDeferred[column]) {
				stableAge[column] = 0; enqueue(column);
			} else {
				stableAge[column] = Math.min(SLEEP_LOGICAL_STEPS, stableAge[column] + 1);
				if (stableAge[column] < SLEEP_LOGICAL_STEPS) enqueue(column);
			}
		}
		logicalStep++;
		return new Stats(settled, relaxed, woken, processed, queueSize);
	}

	private void commitSettlement(GranularInventory.SettlementPlan plan, int[] indices, Environment environment) {
		try { inventory.commitSettlement(plan); }
		catch (RuntimeException failure) { environment.cancelSettlement(plan); throw failure; }
		double impulseX = 0, impulseY = 0, impulseZ = 0;
		for (int index = 0; index < indices.length; index++) {
			admission.removedSwap(indices[index]);
			var source = plan.parcels().get(index);
			impulseX += source.mass() * source.velocityX(); impulseY += source.mass() * source.velocityY();
			impulseZ += source.mass() * source.velocityZ();
			environment.onSolidChanged(cell(coordinate(source.x(), sizeX), coordinate(source.y(), sizeY), coordinate(source.z(), sizeZ)));
		}
		environment.receiveContactImpulse(impulseX, impulseY, impulseZ);
		for (var target : plan.targets()) { environment.onSolidChanged(target.cell()); disturbCell(target.cell()); }
	}

	/** Lowest bulk-capable, vertically reachable layer; later admission cannot select an upper void. */
	private int settlementTarget(GranularParcelStore.Parcel parcel, Environment environment) {
		int x = coordinate(parcel.x(), sizeX), z = coordinate(parcel.z(), sizeZ);
		var addition = new GranularDepositStore.SolidPortion(parcel.materialHandle(), parcel.solidCompositionHandle(), parcel.solidVolumeSvq(), parcel.mass());
		for (int y = 0; y <= coordinate(parcel.y(), sizeY); y++) {
			if (!admission.spend(1)) return -1;
			int target = cell(x, y, z); long open = environment.geometricOpenSvq(target);
			if (open <= 0) continue;
			long bulk = deposits.previewCellBulkVolumeAfterAdd(target, addition);
			if (bulk <= open && environment.bedGeometryFits(target, bulk)
					&& verticalPathOpen(environment, parcel.x(), parcel.y(), parcel.z(),
						settlementCenterY(target, parcel.solidVolumeSvq()), parcel.solidVolumeSvq())) return target;
		}
		return -1;
	}

	private boolean settlementBedsFit(GranularInventory.SettlementPlan plan, Environment environment) {
		for (var target : plan.targets()) if (!environment.bedGeometryFits(target.cell(), target.bulkVolumeSvq())) return false;
		return true;
	}

	private boolean verticalPathOpen(Environment environment, float x, float fromY, float z, float toY, long volume) {
		return admission.spend(environment.verticalPathWork(x, fromY, z, toY, volume))
				&& environment.verticalPathOpen(x, fromY, z, toY, volume);
	}

	private int eligibleTarget(int index, Environment environment) {
		if (!admission.spend(1)) return -1;
		var parcel = parcels.parcel(index);
		if ((parcel.flags() & GranularParcelStore.FLAG_SUPPORTED) == 0 || parcel.contactAge() < SETTLE_CONTACT_STEPS) return -1;
		return settlementTarget(parcel, environment);
	}

	private boolean settlementSlopeAllowed(GranularInventory.SettlementPlan plan, Environment environment) {
		var bulk = new java.util.HashMap<Integer, Long>();
		var previous = new java.util.HashMap<Integer, Long>();
		for (var target : plan.targets()) {
			int cell = target.cell(), column = cell / (sizeX * sizeY) * sizeX + cell % sizeX;
			if (!cacheColumnSurface(previous, column)) return false;
			int x = cell % sizeX, z = cell / (sizeX * sizeY);
			for (int neighbor = 0; neighbor < NEIGHBOR_X.length; neighbor++) {
				int dx = NEIGHBOR_X[neighbor], dz = NEIGHBOR_Z[neighbor];
				if (insideColumn(x + dx, z + dz) && environment.horizontalPathOpen(cell, dx, dz)
						&& !cacheColumnSurface(previous, (z + dz) * sizeX + x + dx)) return false;
			}
			bulk.put(cell, target.bulkVolumeSvq());
		}
		var surfaces = new java.util.HashMap<>(previous);
		for (var target : plan.targets()) {
			int cell = target.cell(), column = cell / (sizeX * sizeY) * sizeX + cell % sizeX;
			long top = (long) (cell / sizeX % sizeY) * SolidVolume.SVQ_PER_CELL + target.bulkVolumeSvq();
			surfaces.put(column, Math.max(surfaces.get(column), top));
		}
		int[] destinations = plan.targetCells();
		for (int index = 0; index < destinations.length; index++) if (!additionSlopeAllowed(destinations[index], bulk.get(destinations[index]),
				registry.material(plan.parcels().get(index).materialHandle()), environment,
				previous.get(destinations[index] / (sizeX * sizeY) * sizeX + destinations[index] % sizeX), surfaces::get)) return false;
		return true;
	}

	private boolean cacheColumnSurface(java.util.Map<Integer, Long> cache, int column) {
		if (cache.containsKey(column)) return true;
		if (!admission.spend(sizeY)) return false;
		cache.put(column, columnSurfaceSvq(column));
		return true;
	}

	private boolean tryWake(int column, int surfaceCell, GranularDepositStore.Group exposed,
	                        ParticulateMaterial material, Environment environment, float dt) {
		double shields = environment.shields(surfaceCell, material);
		if (!(Double.isFinite(shields) && shields > material.criticalShields())) return false;
		double excess = material.criticalShields() == 0 ? Double.POSITIVE_INFINITY
				: shields / material.criticalShields() - 1;
		long erosion = (long) Math.floor(material.erosionRateSvqPerSecond() * (double) dt * excess);
		erosion = Math.min(erosion, Math.min(exposed.solidVolumeSvq(), material.maximumActiveParcelSvq()));
		if (erosion <= 0) return false;
		int x = column % sizeX, z = column / sizeX;
		float height = (float) ((double) columnSurfaceSvq(column) / SolidVolume.SVQ_PER_CELL * cellSize);
		float px = (x + 0.5F) * cellSize,
				py = height + GranularMechanics.representativeRadius(erosion, cellSize) + cellSize * 1e-5F,
				pz = (z + 0.5F) * cellSize;
		GranularDepositStore.SolidPortion moved;
		try { moved = deposits.peekTake(surfaceCell, exposed.materialHandle(), exposed.solidCompositionHandle(), erosion); }
		catch (IllegalArgumentException unavailableSplit) { return false; }
		if (!GranularBedPotential.allowsWake(deposits, surfaceCell, moved, py,
				cellSize, sizeX, sizeY, environment.gravity())) {
			energyDeferred[column] = true;
			return false;
		}
		if (!environment.planWake(surfaceCell, erosion, px, py, pz)) return false;
		try {
			// Bed material starts at rest; the next coupled mechanical solve supplies acceleration
			// and its equal liquid reaction. Copying carrier velocity here would create momentum.
			boolean woken = inventory.wake(surfaceCell, exposed.materialHandle(), exposed.solidCompositionHandle(), erosion,
					px, py, pz, 0, 0, 0);
			if (woken) { admission.append(parcels.count() - 1); environment.onSolidChanged(surfaceCell); }
			else environment.cancelWake(surfaceCell, erosion, px, py, pz);
			return woken;
		} catch (IllegalArgumentException compositionSplitUnavailable) {
			environment.cancelWake(surfaceCell, erosion, px, py, pz);
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
		GranularDepositStore.SolidPortion moved;
		try { moved = deposits.peekTake(surfaceCell, exposed.materialHandle(), exposed.solidCompositionHandle(), requestedSkeleton); }
		catch (IllegalArgumentException unavailableSplit) { return false; }
		float radius = GranularMechanics.representativeRadius(requestedSkeleton, cellSize);
		float pathY = Math.nextUp((float) (height * cellSize + radius));
		float fromX = (x + .5F) * cellSize, fromZ = (z + .5F) * cellSize;
		float toX = (receiverX + .5F) * cellSize, toZ = (receiverZ + .5F) * cellSize;
		if (!admission.spend(environment.horizontalTransferPathWork(fromX, pathY, fromZ, toX, toZ, requestedSkeleton))
				|| !environment.horizontalTransferPathOpen(fromX, pathY, fromZ, toX, toZ, requestedSkeleton)) return false;
		for (int y = 0; y < sizeY; y++) {
			if (!admission.spend(1)) return false;
			int receiver = cell(receiverX, y, receiverZ);
			long open = environment.geometricOpenSvq(receiver);
			if (open <= 0L) continue;
			long candidateBulk = deposits.previewCellBulkVolumeAfterAdd(receiver, moved);
			if (candidateBulk > open || !environment.bedGeometryFits(receiver, candidateBulk)) continue;
			if (!verticalPathOpen(environment, (receiverX + 0.5F) * cellSize,
					pathY, (receiverZ + 0.5F) * cellSize,
					settlementCenterY(receiver, requestedSkeleton), requestedSkeleton)) continue;
			if (!GranularBedPotential.allowsRelaxation(deposits, surfaceCell, receiver, moved,
					cellSize, sizeX, sizeY, environment.gravity())) {
				energyDeferred[column] = true;
				return false;
			}
			if (!additionSlopeAllowed(receiver, moved, material, environment)) return false;
			if (!admission.allows(receiver, candidateBulk, -1)) return false;
			if (!environment.planRelaxation(surfaceCell, receiver, requestedSkeleton)) return false;
			environment.requireThermalRelaxation(surfaceCell, receiver, exposed.materialHandle(),
					exposed.solidCompositionHandle(), requestedSkeleton, exposed.solidVolumeSvq());
			if (deposits.tryMove(surfaceCell, receiver, exposed.materialHandle(),
					exposed.solidCompositionHandle(), requestedSkeleton, open)) {
				environment.commitThermalRelaxation(surfaceCell, receiver, exposed.materialHandle(),
						exposed.solidCompositionHandle(), requestedSkeleton, exposed.solidVolumeSvq());
				environment.onSolidChanged(surfaceCell);
				environment.onSolidChanged(receiver);
				disturbColumn(receiverX, receiverZ);
				return true;
			}
			environment.cancelRelaxation(surfaceCell, receiver, requestedSkeleton);
			return false;
		}
		return false;
	}

	private boolean additionSlopeAllowed(
			int cell, GranularDepositStore.SolidPortion addition,
			ParticulateMaterial material, Environment environment
	) {
		return additionSlopeAllowed(cell, deposits.previewCellBulkVolumeAfterAdd(cell, addition), material, environment);
	}

	private boolean additionSlopeAllowed(int cell, long nextCellBulk, ParticulateMaterial material, Environment environment) {
		return additionSlopeAllowed(cell, nextCellBulk, material, environment,
				columnSurfaceSvq(cell / (sizeX * sizeY) * sizeX + cell % sizeX), this::columnSurfaceSvq);
	}

	private boolean additionSlopeAllowed(int cell, long nextCellBulk, ParticulateMaterial material, Environment environment,
	                                    long previousSurface, java.util.function.IntToLongFunction neighborSurface) {
		int x = cell % sizeX, yz = cell / sizeX, y = yz % sizeY, z = yz / sizeY;
		long previousCellBulk = deposits.cellBulkVolumeSvq(cell);
		long addedCellSurface = Math.addExact(Math.multiplyExact((long) y, SolidVolume.SVQ_PER_CELL), nextCellBulk);
		long candidateSurface = Math.max(previousSurface, addedCellSurface);
		long discreteAllowance = nextCellBulk - previousCellBulk;
		for (int neighbor = 0; neighbor < NEIGHBOR_X.length; neighbor++) {
			int dx = NEIGHBOR_X[neighbor], dz = NEIGHBOR_Z[neighbor];
			int neighborX = x + dx, neighborZ = z + dz;
			if (!insideColumn(neighborX, neighborZ) || !environment.horizontalPathOpen(cell, dx, dz)) continue;
			double distance = neighbor < 4 ? 1.0 : Math.sqrt(2.0);
			long reposeRise = (long) Math.floor(distance * Math.tan(material.reposeAngleRadians())
					* SolidVolume.SVQ_PER_CELL);
			long adjacentSurface = neighborSurface.applyAsLong(neighborZ * sizeX + neighborX);
			if (candidateSurface - adjacentSurface > Math.addExact(reposeRise, discreteAllowance)) return false;
		}
		return true;
	}

	/** Low-cost surface flow for a supported parcel whose addition would exceed the local repose envelope. */
	private boolean rollParcelDownhill(
			int parcelIndex, GranularParcelStore.Parcel parcel, int surfaceCell, Environment environment
	) {
		int x = surfaceCell % sizeX, yz = surfaceCell / sizeX, z = yz / sizeY;
		int bestNeighbor = -1;
		long bestSurface = columnSurfaceSvq(z * sizeX + x);
		int directionStart = (int) ((parcelIndex * 0x9E3779B9L + logicalStep) & 7L);
		for (int offset = 0; offset < NEIGHBOR_X.length; offset++) {
			int neighbor = (directionStart + offset) & 7;
			int dx = NEIGHBOR_X[neighbor], dz = NEIGHBOR_Z[neighbor];
			int neighborX = x + dx, neighborZ = z + dz;
			if (!insideColumn(neighborX, neighborZ)
					|| !environment.horizontalPathOpen(surfaceCell, dx, dz)) continue;
			long height = columnSurfaceSvq(neighborZ * sizeX + neighborX);
			if (height < bestSurface) { bestSurface = height; bestNeighbor = neighbor; }
		}
		if (bestNeighbor < 0) return false;
		double speed = Math.sqrt(parcel.velocityX() * (double) parcel.velocityX()
				+ parcel.velocityY() * (double) parcel.velocityY()
				+ parcel.velocityZ() * (double) parcel.velocityZ());
		if (speed == 0) return false;
		float dx = NEIGHBOR_X[bestNeighbor], dz = NEIGHBOR_Z[bestNeighbor];
		double inverseLength = dx != 0.0F && dz != 0.0F ? 1.0 / Math.sqrt(2.0) : 1.0;
		// Reorient existing motion along a descending bed. Gravity has already been integrated
		// by mechanics; adding a prescribed rolling speed here would create kinetic energy.
		parcels.overwrite(parcelIndex, new GranularParcelStore.Parcel(
				parcel.x(), parcel.y(), parcel.z(), (float) (dx * inverseLength * speed), 0.0F,
				(float) (dz * inverseLength * speed), parcel.solidVolumeSvq(), parcel.mass(), parcel.materialHandle(),
				parcel.solidCompositionHandle(), 0,
				(byte) (parcel.flags() & ~GranularParcelStore.FLAG_SUPPORTED)));
		GranularParcelStore.Parcel redirected = parcels.parcel(parcelIndex);
		environment.receiveContactImpulse(parcel.mass() * (parcel.velocityX() - (double) redirected.velocityX()),
				parcel.mass() * (parcel.velocityY() - (double) redirected.velocityY()),
				parcel.mass() * (parcel.velocityZ() - (double) redirected.velocityZ()));
		return true;
	}

	private float settlementCenterY(int cell, long solidSvq) {
		int y = cell / sizeX % sizeY;
		double bedHeight = (y + deposits.cellBulkVolumeSvq(cell) / (double) SolidVolume.SVQ_PER_CELL) * cellSize;
		return Math.nextUp((float) (bedHeight + GranularMechanics.representativeRadius(solidSvq, cellSize)));
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
		return -1;
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
