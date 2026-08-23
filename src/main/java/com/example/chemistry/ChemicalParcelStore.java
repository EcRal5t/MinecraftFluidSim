package com.example.chemistry;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.IntFunction;

/** Per-region marker-to-packed-mixture ownership, indicator state, dirty cells and logical reactions. */
public final class ChemicalParcelStore {
	private final ChemicalRegistry registry;
	private final PackedChemicalMixturePool pool;
	private final int[] markerHandles;
	private final long[] indicatorWords;
	private final long[] dirtyCellWords;
	private long totalQuanta;
	private ChemicalTransferLedger escapedSink = ChemicalTransferLedger.EMPTY;
	private ChemicalTransferLedger externalSource = ChemicalTransferLedger.EMPTY;

	public ChemicalParcelStore(ChemicalRegistry registry, int reservedMarkerCapacity, int cellCount) {
		if (cellCount <= 0) throw new IllegalArgumentException("Cell count must be positive");
		this.registry = java.util.Objects.requireNonNull(registry, "registry");
		this.pool = new PackedChemicalMixturePool(reservedMarkerCapacity);
		this.markerHandles = new int[reservedMarkerCapacity];
		this.indicatorWords = new long[(reservedMarkerCapacity + 63) >>> 6];
		this.dirtyCellWords = new long[(cellCount + 63) >>> 6];
	}

	public ChemicalRegistry registry() { return registry; }
	public PackedChemicalMixturePool pool() { return pool; }
	public long totalQuanta() { return totalQuanta; }
	public ChemicalTransferLedger escapedSink() { return escapedSink; }
	public ChemicalTransferLedger externalSource() { return externalSource; }
	public void recordEscapedSink(ChemicalTransferLedger ledger) {
		validateAuditLedger(ledger);
		escapedSink = escapedSink.plus(ledger);
	}
	public void recordExternalSource(ChemicalTransferLedger ledger) {
		validateAuditLedger(ledger);
		externalSource = externalSource.plus(ledger);
	}
	public void restoreAuditLedgers(ChemicalTransferLedger source, ChemicalTransferLedger sink) {
		if (!externalSource.isEmpty() || !escapedSink.isEmpty()) {
			throw new IllegalStateException("Chemical audit ledgers can only be restored into a fresh parcel store");
		}
		validateAuditLedger(source);
		validateAuditLedger(sink);
		externalSource = source;
		escapedSink = sink;
	}

	private void validateAuditLedger(ChemicalTransferLedger ledger) {
		if (ledger == null) throw new IllegalArgumentException("Chemical audit ledger is missing");
		for (ChemicalTransferLedger.Group group : ledger.groups()) {
			ChemicalPhaseDefinition phase = registry.phase(registry.phaseHandleForMaterial(group.materialId()));
			if (group.indicator() && !phase.acceptsIndicator()) throw new IllegalArgumentException("Audit indicator/phase mismatch");
			ChemicalMixtureRecord record = ChemicalMixtureRecord.fromStableAmounts(
					registry, group.speciesQuanta(), group.massResidual());
			requireCompatible(record, group.materialId(), group.indicator());
			double tolerance = Math.max(1.0E-9, Math.ulp(group.mass()) * 8.0);
			if (Math.abs(record.markerMass(registry) - group.mass()) > tolerance) {
				throw new IllegalArgumentException("Chemical audit species/mass ledger mismatch");
			}
		}
	}

	public void requireCompatible(ChemicalMixtureRecord record, String materialId, boolean indicator) {
		validateRecord(record);
		ChemicalPhaseDefinition phase = registry.phase(registry.phaseHandleForMaterial(materialId));
		if (indicator && !phase.acceptsIndicator()) throw new IllegalArgumentException("Chemical phase rejects indicator");
		for (int entry = 0; entry < record.entryCount(); entry++) {
			String speciesId = registry.species(record.speciesHandle(entry)).id();
			if (!phase.allowedSpecies().contains(speciesId)) {
				throw new IllegalArgumentException("Species " + speciesId + " is incompatible with " + phase.id());
			}
		}
	}

	public void assign(int marker, ChemicalMixtureRecord record, boolean indicator) {
		requireMarker(marker);
		validateRecord(record);
		long previous = markerHandles[marker] == 0 ? 0L : pool.record(markerHandles[marker]).totalQuanta();
		long candidate = Math.addExact(Math.subtractExact(totalQuanta, previous), record.totalQuanta());
		if (candidate > ChemicalLimits.MAX_REGION_TOTAL_QUANTA) throw new IllegalArgumentException("Region chemical inventory is over budget");
		if (markerHandles[marker] == 0) markerHandles[marker] = pool.create(record);
		else pool.overwrite(markerHandles[marker], record);
		setIndicator(marker, indicator);
		totalQuanta = candidate;
	}

	public void assignDefault(
			int marker, String materialId, float cellVolume, float cellSize, double markerMass, boolean indicator
	) {
		int phaseHandle = registry.phaseHandleForMaterial(materialId);
		ChemicalPhaseDefinition phase = registry.phase(phaseHandle);
		if (indicator && !phase.acceptsIndicator()) throw new IllegalArgumentException("Chemical phase rejects indicator");
		ExactRational blockVolume = ExactRational.ofFloat(cellVolume)
				.multiply(ExactRational.ofFloat(cellSize))
				.multiply(ExactRational.ofFloat(cellSize))
				.multiply(ExactRational.ofFloat(cellSize));
		TreeMap<String, Long> amounts = new TreeMap<>();
		for (Map.Entry<String, ExactRational> entry : phase.defaultInventoryPerBv().entrySet()) {
			ExactRational exactQuanta = entry.getValue().multiply(blockVolume).multiply(ChemicalLimits.QUANTA_PER_AU);
			long quanta = roundNonNegative(exactQuanta);
			if (quanta > 0L) amounts.put(entry.getKey(), quanta);
		}
		if (amounts.isEmpty()) throw new IllegalArgumentException("Marker is too small for one default carrier quantum");
		ChemicalMixtureRecord withoutResidual = ChemicalMixtureRecord.fromStableAmounts(registry, amounts, 0.0);
		double residual = markerMass - withoutResidual.exactSpeciesMass(registry).toDouble();
		assign(marker, ChemicalMixtureRecord.fromStableAmounts(registry, amounts, residual), indicator);
	}

	public ChemicalMixtureRecord record(int marker) {
		requireAssigned(marker);
		return pool.record(markerHandles[marker]);
	}
	public int mixtureHandle(int marker) { requireAssigned(marker); return markerHandles[marker]; }
	public boolean indicator(int marker) { requireAssigned(marker); return bit(indicatorWords, marker); }
	/** Changes only indicator provenance; species, mass and the packed mixture handle stay untouched. */
	public boolean updateIndicator(int marker, boolean value) {
		requireAssigned(marker);
		boolean previous = bit(indicatorWords, marker);
		if (previous != value) setIndicator(marker, value);
		return previous != value;
	}
	public boolean isAssigned(int marker) {
		requireMarker(marker);
		return markerHandles[marker] != 0 && pool.isLive(markerHandles[marker]);
	}

	public boolean isCanonicalDefaultCarrier(int marker, String materialId) {
		requireAssigned(marker);
		if (indicator(marker)) return false;
		ChemicalPhaseDefinition phase = registry.phase(registry.phaseHandleForMaterial(materialId));
		ChemicalMixtureRecord record = record(marker);
		for (int entry = 0; entry < record.entryCount(); entry++) {
			if (!phase.defaultInventoryPerBv().containsKey(registry.species(record.speciesHandle(entry)).id())) return false;
		}
		return true;
	}

	public boolean canMerge(int target, int source) {
		requireAssigned(target); requireAssigned(source);
		if (indicator(target) != indicator(source)) return false;
		ChemicalMixtureRecord left = record(target), right = record(source);
		int unique = left.entryCount();
		for (int entry = 0; entry < right.entryCount(); entry++) if (left.amount(right.speciesHandle(entry)) == 0L) unique++;
		return unique <= ChemicalLimits.MAX_SPECIES_PER_PARCEL;
	}

	public void merge(int target, int source, double mergedMass) {
		if (!canMerge(target, source)) throw new IllegalStateException("Chemical parcels cannot merge");
		ChemicalMixtureRecord left = record(target), right = record(source);
		TreeMap<Integer, Long> amounts = new TreeMap<>();
		copyAmounts(left, amounts); copyAmounts(right, amounts);
		ChemicalMixtureRecord combined = recordFromHandles(amounts, 0.0);
		double residual = mergedMass - combined.exactSpeciesMass(registry).toDouble();
		combined = recordFromHandles(amounts, residual);
		pool.overwrite(markerHandles[target], combined);
		pool.release(markerHandles[source]);
		markerHandles[source] = 0;
		setIndicator(source, false);
	}

	public void split(
			int source, int destination, float sourceVolume, float destinationVolume,
			double sourceMass, double destinationMass
	) {
		requireAssigned(source);
		if (markerHandles[destination] != 0) throw new IllegalStateException("Split destination already has chemical state");
		ChemicalMixtureRecord original = record(source);
		TreeMap<Integer, Long> first = new TreeMap<>(), second = new TreeMap<>();
		for (int entry = 0; entry < original.entryCount(); entry++) {
			long[] allocation = BinaryVolumeAllocator.allocate(original.amountAt(entry),
					new float[]{sourceVolume, destinationVolume}, new int[]{source, destination});
			if (allocation[0] > 0L) first.put(original.speciesHandle(entry), allocation[0]);
			if (allocation[1] > 0L) second.put(original.speciesHandle(entry), allocation[1]);
		}
		if (first.isEmpty() || second.isEmpty()) throw new IllegalStateException("Split produced an empty chemical parcel");
		ChemicalMixtureRecord firstRecord = recordFromHandles(first, 0.0);
		ChemicalMixtureRecord secondRecord = recordFromHandles(second, 0.0);
		firstRecord = recordFromHandles(first, sourceMass - firstRecord.exactSpeciesMass(registry).toDouble());
		secondRecord = recordFromHandles(second, destinationMass - secondRecord.exactSpeciesMass(registry).toDouble());
		int newHandle = pool.create(secondRecord);
		pool.overwrite(markerHandles[source], firstRecord);
		markerHandles[destination] = newHandle;
		setIndicator(destination, indicator(source));
	}

	public boolean canSplit(int source, int destination, float sourceVolume, float destinationVolume) {
		requireAssigned(source);
		if (markerHandles[destination] != 0) return false;
		ChemicalMixtureRecord original = record(source);
		long firstTotal = 0L, secondTotal = 0L;
		for (int entry = 0; entry < original.entryCount(); entry++) {
			long[] allocation = BinaryVolumeAllocator.allocate(original.amountAt(entry),
					new float[]{sourceVolume, destinationVolume}, new int[]{source, destination});
			firstTotal = Math.addExact(firstTotal, allocation[0]);
			secondTotal = Math.addExact(secondTotal, allocation[1]);
		}
		return firstTotal > 0L && secondTotal > 0L;
	}

	/** Transfers marker ownership during stable compaction; destination must be empty or the same ordinal. */
	public void move(int source, int destination) {
		requireAssigned(source);
		if (source == destination) return;
		if (markerHandles[destination] != 0) throw new IllegalStateException("Compaction destination still owns a parcel");
		markerHandles[destination] = markerHandles[source];
		markerHandles[source] = 0;
		setIndicator(destination, bit(indicatorWords, source));
		setIndicator(source, false);
	}

	public void remove(int marker) {
		requireAssigned(marker);
		ChemicalMixtureRecord removed = record(marker);
		totalQuanta = Math.subtractExact(totalQuanta, removed.totalQuanta());
		pool.release(markerHandles[marker]);
		markerHandles[marker] = 0;
		setIndicator(marker, false);
	}

	public void clear(int particleCount) {
		for (int marker = particleCount - 1; marker >= 0; marker--) if (isAssigned(marker)) remove(marker);
		Arrays.fill(dirtyCellWords, 0L);
		if (totalQuanta != 0L || pool.liveCount() != 0) throw new IllegalStateException("Chemical store did not clear");
	}

	public SplitPlan planTruncate(
			int marker, float keptVolume, float removedVolume, double keptMass, double removedMass
	) {
		requireAssigned(marker);
		if (!(keptVolume >= 0.0F && removedVolume > 0.0F) || !(keptMass >= 0.0 && removedMass > 0.0)) {
			throw new IllegalArgumentException("Invalid chemical truncation ledger");
		}
		ChemicalMixtureRecord original = record(marker);
		if (keptVolume == 0.0F) return new SplitPlan(null, original);
		TreeMap<Integer, Long> kept = new TreeMap<>(), removed = new TreeMap<>();
		for (int entry = 0; entry < original.entryCount(); entry++) {
			long[] allocation = BinaryVolumeAllocator.allocate(original.amountAt(entry),
					new float[]{keptVolume, removedVolume}, new int[]{marker, Integer.MAX_VALUE});
			if (allocation[0] > 0L) kept.put(original.speciesHandle(entry), allocation[0]);
			if (allocation[1] > 0L) removed.put(original.speciesHandle(entry), allocation[1]);
		}
		if (kept.isEmpty() || removed.isEmpty()) {
			throw new ChemicalReactionEngine.BudgetExceededException("Transfer is below the chemical quantum resolution");
		}
		ChemicalMixtureRecord keptRecord = recordFromHandles(kept, 0.0);
		ChemicalMixtureRecord removedRecord = recordFromHandles(removed, 0.0);
		keptRecord = recordFromHandles(kept, keptMass - keptRecord.exactSpeciesMass(registry).toDouble());
		removedRecord = recordFromHandles(removed, removedMass - removedRecord.exactSpeciesMass(registry).toDouble());
		return new SplitPlan(keptRecord, removedRecord);
	}

	public void commitTruncate(int marker, SplitPlan plan) {
		requireAssigned(marker);
		if (plan.kept() == null) throw new IllegalArgumentException("Full removals commit through marker removal");
		pool.overwrite(markerHandles[marker], plan.kept());
		totalQuanta = Math.subtractExact(totalQuanta, plan.removed().totalQuanta());
	}

	/** Swap-removes an ordinal without changing the parcel belonging to the moved last marker. */
	public void swapRemove(int removed, int last) {
		requireAssigned(removed); requireAssigned(last);
		remove(removed);
		if (removed != last) move(last, removed);
	}

	public void markDirtyCell(int cell) {
		if (cell < 0 || cell >>> 6 >= dirtyCellWords.length) throw new IndexOutOfBoundsException("Chemical cell " + cell);
		dirtyCellWords[cell >>> 6] |= 1L << (cell & 63);
	}
	public boolean hasDirtyCells() {
		for (long word : dirtyCellWords) if (word != 0L) return true;
		return false;
	}

	/** Full plan/validate/commit logical step. A failure leaves all parcel records and dirty bits unchanged. */
	public ReactionStats reactDirtyCells(
			int sizeX, int sizeY, int sizeZ, float cellSize,
			float[] x, float[] y, float[] z, float[] volume, double[] markerMass,
			int[] materialHandle, IntFunction<String> stableMaterialId, int particleCount
	) {
		if (!hasDirtyCells()) return new ReactionStats(0, 0, 0);
		if (particleCount < 0 || particleCount > markerHandles.length) throw new IllegalArgumentException("Invalid marker count");
		@SuppressWarnings("unchecked")
		TreeMap<Integer, List<Integer>>[] groups = new TreeMap[Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ)];
		for (int marker = 0; marker < particleCount; marker++) {
			requireAssigned(marker);
			int cellX = Math.min(sizeX - 1, Math.max(0, (int) Math.floor(x[marker] / cellSize)));
			int cellY = Math.min(sizeY - 1, Math.max(0, (int) Math.floor(y[marker] / cellSize)));
			int cellZ = Math.min(sizeZ - 1, Math.max(0, (int) Math.floor(z[marker] / cellSize)));
			int cell = (cellZ * sizeY + cellY) * sizeX + cellX;
			if (!bit(dirtyCellWords, cell)) continue;
			int phase = registry.phaseHandleForMaterial(stableMaterialId.apply(materialHandle[marker]));
			if (groups[cell] == null) groups[cell] = new TreeMap<>();
			groups[cell].computeIfAbsent(phase, ignored -> new ArrayList<>()).add(marker);
		}
		ChemicalMixtureRecord[] replacements = new ChemicalMixtureRecord[particleCount];
		ChemicalReactionEngine engine = new ChemicalReactionEngine(registry);
		int cells = 0, evaluations = 0, changed = 0;
		for (int cell = 0; cell < groups.length; cell++) {
			if (!bit(dirtyCellWords, cell)) continue;
			cells++;
			if (groups[cell] == null) continue;
			for (Map.Entry<Integer, List<Integer>> group : groups[cell].entrySet()) {
				List<Integer> markers = group.getValue();
				long[] inventory = new long[registry.species().size()];
				double residual = 0.0;
				for (int marker : markers) {
					ChemicalMixtureRecord record = record(marker);
					for (int entry = 0; entry < record.entryCount(); entry++) {
						inventory[record.speciesHandle(entry)] = Math.addExact(
								inventory[record.speciesHandle(entry)], record.amountAt(entry));
					}
					residual += record.massResidual();
				}
				ChemicalMixtureRecord aggregate = recordFromInventory(inventory, residual);
				ChemicalReactionEngine.Result result = engine.react(group.getKey(), aggregate);
				evaluations = Math.addExact(evaluations, result.evaluations());
				if (evaluations > ChemicalLimits.MAX_REACTION_EVALUATIONS_PER_STEP) {
					throw new ChemicalReactionEngine.BudgetExceededException("Logical-step reaction budget exceeded");
				}
				float[] weights = new float[markers.size()];
				int[] ordinals = new int[markers.size()];
				for (int i = 0; i < markers.size(); i++) { ordinals[i] = markers.get(i); weights[i] = volume[ordinals[i]]; }
				@SuppressWarnings("unchecked")
				TreeMap<Integer, Long>[] distributed = new TreeMap[markers.size()];
				for (int i = 0; i < distributed.length; i++) distributed[i] = new TreeMap<>();
				for (int entry = 0; entry < result.mixture().entryCount(); entry++) {
					long[] allocation = BinaryVolumeAllocator.allocate(result.mixture().amountAt(entry), weights, ordinals);
					for (int i = 0; i < allocation.length; i++) if (allocation[i] > 0L) {
						distributed[i].put(result.mixture().speciesHandle(entry), allocation[i]);
					}
				}
				for (int i = 0; i < markers.size(); i++) {
					if (distributed[i].isEmpty()) throw new ChemicalReactionEngine.BudgetExceededException("Reaction produced an empty parcel");
					ChemicalMixtureRecord withoutResidual = recordFromHandles(distributed[i], 0.0);
					double markerResidual = markerMass[ordinals[i]] - withoutResidual.exactSpeciesMass(registry).toDouble();
					replacements[ordinals[i]] = recordFromHandles(distributed[i], markerResidual);
					if (!replacements[ordinals[i]].equals(record(ordinals[i]))) changed++;
				}
			}
		}
		long candidateTotal = totalQuanta;
		for (int marker = 0; marker < replacements.length; marker++) if (replacements[marker] != null) {
			candidateTotal = Math.addExact(Math.subtractExact(candidateTotal, record(marker).totalQuanta()),
					replacements[marker].totalQuanta());
		}
		if (candidateTotal > ChemicalLimits.MAX_REGION_TOTAL_QUANTA) {
			throw new ChemicalReactionEngine.BudgetExceededException("Reaction exceeds region inventory budget");
		}
		for (int marker = 0; marker < replacements.length; marker++) if (replacements[marker] != null) {
			pool.overwrite(markerHandles[marker], replacements[marker]);
		}
		totalQuanta = candidateTotal;
		Arrays.fill(dirtyCellWords, 0L);
		return new ReactionStats(cells, evaluations, changed);
	}

	public long[] speciesTotals(int particleCount) {
		long[] totals = new long[registry.species().size()];
		long sum = 0L;
		for (int marker = 0; marker < particleCount; marker++) {
			ChemicalMixtureRecord record = record(marker);
			for (int entry = 0; entry < record.entryCount(); entry++) {
				totals[record.speciesHandle(entry)] = Math.addExact(totals[record.speciesHandle(entry)], record.amountAt(entry));
				sum = Math.addExact(sum, record.amountAt(entry));
			}
		}
		if (sum != totalQuanta) throw new IllegalStateException("Region chemical total ledger mismatch");
		return totals;
	}

	public long cpuBytes() {
		return pool.cpuBytes() + (long) markerHandles.length * Integer.BYTES
				+ (long) (indicatorWords.length + dirtyCellWords.length) * Long.BYTES;
	}

	private ChemicalMixtureRecord recordFromInventory(long[] inventory, double residual) {
		TreeMap<Integer, Long> values = new TreeMap<>();
		for (int handle = 0; handle < inventory.length; handle++) if (inventory[handle] > 0L) values.put(handle, inventory[handle]);
		return recordFromHandles(values, residual);
	}

	private ChemicalMixtureRecord recordFromHandles(Map<Integer, Long> values, double residual) {
		if (values.isEmpty() || values.size() > ChemicalLimits.MAX_SPECIES_PER_PARCEL) {
			throw new ChemicalReactionEngine.BudgetExceededException("Parcel species entry budget exceeded");
		}
		int[] species = new int[values.size()]; long[] amounts = new long[values.size()]; int entry = 0;
		for (Map.Entry<Integer, Long> value : new TreeMap<>(values).entrySet()) {
			species[entry] = value.getKey(); amounts[entry++] = value.getValue();
		}
		return new ChemicalMixtureRecord(species, amounts, Double.doubleToRawLongBits(residual));
	}

	private void validateRecord(ChemicalMixtureRecord record) {
		for (int entry = 0; entry < record.entryCount(); entry++) registry.species(record.speciesHandle(entry));
	}
	private void requireMarker(int marker) {
		if (marker < 0 || marker >= markerHandles.length) throw new IndexOutOfBoundsException("Chemical marker " + marker);
	}
	private void requireAssigned(int marker) {
		requireMarker(marker);
		if (markerHandles[marker] == 0 || !pool.isLive(markerHandles[marker])) {
			throw new IllegalStateException("Marker " + marker + " has no valid chemical parcel");
		}
	}
	private void setIndicator(int marker, boolean value) {
		long mask = 1L << (marker & 63);
		if (value) indicatorWords[marker >>> 6] |= mask;
		else indicatorWords[marker >>> 6] &= ~mask;
	}
	private static boolean bit(long[] words, int index) { return (words[index >>> 6] & 1L << (index & 63)) != 0L; }
	private static void copyAmounts(ChemicalMixtureRecord source, Map<Integer, Long> target) {
		for (int entry = 0; entry < source.entryCount(); entry++) {
			target.merge(source.speciesHandle(entry), source.amountAt(entry), Math::addExact);
		}
	}
	private static long roundNonNegative(ExactRational value) {
		if (value.signum() < 0) throw new IllegalArgumentException("Cannot round a negative inventory");
		BigInteger[] result = value.numerator().divideAndRemainder(value.denominator());
		if (result[1].shiftLeft(1).compareTo(value.denominator()) >= 0) result[0] = result[0].add(BigInteger.ONE);
		return result[0].longValueExact();
	}

	public record ReactionStats(int dirtyCells, int evaluations, int changedMarkers) { }
	public record SplitPlan(ChemicalMixtureRecord kept, ChemicalMixtureRecord removed) {
		public SplitPlan {
			if (removed == null) throw new IllegalArgumentException("Removed chemical ledger is missing");
		}
	}
}
