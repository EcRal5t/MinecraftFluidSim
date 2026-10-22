package mmm.chemistry;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/** Region chemistry registry, source/sink audits and bounded reaction scheduling. */
public final class RegionChemistry {
	private final ChemicalRegistry registry;
	private final long[] dirtyCellWords;
	private int reactionCursor;
	private volatile DefaultComposition defaultComposition;
	private record DefaultComposition(String materialId,long volumeBits,int cellSizeBits,long massUnits,
	                                  ChemicalMixtureRecord mixture) { }
	private ChemicalTransferLedger escapedSink=ChemicalTransferLedger.EMPTY;
	private ChemicalTransferLedger externalSource=ChemicalTransferLedger.EMPTY;
	public RegionChemistry(ChemicalRegistry registry,int cellCount){
		if(cellCount<=0)throw new IllegalArgumentException("Cell count must be positive");
		this.registry=java.util.Objects.requireNonNull(registry,"registry");
		dirtyCellWords=new long[(cellCount+63)>>>6];
	}
	public ChemicalRegistry registry(){return registry;}
	public int reactionCursor(){return reactionCursor;}
	public void restoreReactionCursor(int cell){
		if(cell<0||cell>>>6>=dirtyCellWords.length)throw new IllegalArgumentException("Reaction cursor is outside the grid");
		reactionCursor=cell;
	}
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
	/** Validates the cumulative source candidate without changing audit truth. */
	public void requireCanRecordExternalSource(ChemicalTransferLedger ledger) {
		validateAuditLedger(ledger);
		externalSource.plus(ledger);
	}
	/** Validates the cumulative sink candidate without changing audit truth. */
	public void requireCanRecordEscapedSink(ChemicalTransferLedger ledger) {
		validateAuditLedger(ledger);
		escapedSink.plus(ledger);
	}

	/** Prepares both external audit directions without changing inventory or either ledger. */
	public AuditExchange planAuditExchange(ChemicalTransferLedger source, ChemicalTransferLedger sink) {
		validateAuditLedger(source); validateAuditLedger(sink);
		return new AuditExchange(this, externalSource, escapedSink, externalSource.plus(source), escapedSink.plus(sink));
	}

	public void requireCurrentAuditExchange(AuditExchange plan) {
		if (plan == null || plan.owner != this || plan.oldSource != externalSource || plan.oldSink != escapedSink)
			throw new IllegalStateException("Chemical audit exchange is stale or belongs to another store");
	}

	public void commitAuditExchange(AuditExchange plan) {
		requireCurrentAuditExchange(plan);
		externalSource = plan.source; escapedSink = plan.sink;
	}

	public static final class AuditExchange {
		private final RegionChemistry owner;
		private final ChemicalTransferLedger oldSource, oldSink, source, sink;
		private AuditExchange(RegionChemistry owner, ChemicalTransferLedger oldSource,
				ChemicalTransferLedger oldSink, ChemicalTransferLedger source, ChemicalTransferLedger sink) {
			this.owner = owner; this.oldSource = oldSource; this.oldSink = oldSink; this.source = source; this.sink = sink;
		}
	}
	public void restoreAuditLedgers(ChemicalTransferLedger source, ChemicalTransferLedger sink) {
		if (!externalSource.isEmpty() || !escapedSink.isEmpty()) {
			throw new IllegalStateException("Chemical audit ledgers can only be restored into a fresh region chemistry");
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
			ExactRational exactSpeciesMass = ExactRational.ZERO;
			for (Map.Entry<String, Long> species : group.speciesQuanta().entrySet()) {
				int handle = registry.speciesHandle(species.getKey());
				if (!phase.allowedSpecies().contains(species.getKey())) {
					throw new IllegalArgumentException("Audit species/phase mismatch: " + species.getKey());
				}
				exactSpeciesMass = exactSpeciesMass.add(registry.species(handle).molarMassKgPerMol()
						.multiply(species.getValue()).divide(ChemicalLimits.QUANTA_PER_MOL));
			}
			if (Math.addExact(mmm.units.Mass.exactUnits(exactSpeciesMass.numerator(), exactSpeciesMass.denominator()), group.massResidualUnits()) != group.massUnits()) {
				throw new IllegalArgumentException("Chemical audit species/mass ledger mismatch");
			}
		}
	}

	public void requireCompatible(ChemicalMixtureRecord record, String materialId) {
		validateRecord(record);
		ChemicalPhaseDefinition phase = registry.phase(registry.phaseHandleForMaterial(materialId));
		for (int entry = 0; entry < record.entryCount(); entry++) {
			String speciesId = registry.species(record.speciesHandle(entry)).id();
			if (!phase.allowedSpecies().contains(speciesId)) {
				throw new IllegalArgumentException("Species " + speciesId + " is incompatible with " + phase.id());
			}
		}
	}

	/** Resolves source composition once; unresolved species remain in the integer mass residual. */
	public ChemicalMixtureRecord defaultRecord(
			String materialId, double cellVolume, float cellSize, long massUnits
	) {
		if (massUnits < 0) throw new IllegalArgumentException("Negative liquid mass");
		int phaseHandle = registry.phaseHandleForMaterial(materialId);
		ChemicalPhaseDefinition phase = registry.phase(phaseHandle);
		if (!(Double.isFinite(cellVolume) && cellVolume > 0 && Float.isFinite(cellSize) && cellSize > 0))
			throw new IllegalArgumentException("Source volume and cell size must be positive and finite");
		if (massUnits == 0) return ChemicalMixtureRecord.fromStableAmounts(registry, Map.of(), 0);
		DefaultComposition previous = defaultComposition;
		if (previous != null && previous.materialId.equals(materialId)
				&& previous.volumeBits == Double.doubleToRawLongBits(cellVolume)
				&& previous.cellSizeBits == Float.floatToRawIntBits(cellSize)
				&& previous.massUnits == massUnits) {
			requireCompatible(previous.mixture, materialId);
			return previous.mixture;
		}
		ExactRational cubicMeters = ExactRational.ofDouble(cellVolume)
				.multiply(ExactRational.ofFloat(cellSize))
				.multiply(ExactRational.ofFloat(cellSize))
				.multiply(ExactRational.ofFloat(cellSize));
		TreeMap<String, Long> amounts = new TreeMap<>();
		for (Map.Entry<String, ExactRational> entry : phase.defaultConcentrationMolPerCubicMeter().entrySet()) {
			ExactRational exactQuanta = entry.getValue().multiply(cubicMeters).multiply(ChemicalLimits.QUANTA_PER_MOL);
			long quanta = roundNonNegative(exactQuanta);
			if (quanta > 0L) amounts.put(entry.getKey(), quanta);
		}
		ChemicalMixtureRecord withoutResidual = ChemicalMixtureRecord.fromStableAmounts(registry, amounts, 0L);
		long residual = massUnits - withoutResidual.speciesMassUnits(registry);
		ChemicalMixtureRecord record = ChemicalMixtureRecord.fromStableAmounts(registry, amounts, residual);
		requireCompatible(record, materialId);
		if (previous == null) defaultComposition = new DefaultComposition(materialId, Double.doubleToRawLongBits(cellVolume),
				Float.floatToRawIntBits(cellSize), massUnits, record);
		return record;
	}

	public void markDirtyCell(int cell) {
		if (cell < 0 || cell >>> 6 >= dirtyCellWords.length) throw new IndexOutOfBoundsException("Chemical cell " + cell);
		dirtyCellWords[cell >>> 6] |= 1L << (cell & 63);
	}
	public boolean hasDirtyCells() {
		for (long word : dirtyCellWords) if (word != 0L) return true;
		return false;
	}

	public int[] dirtyCells() {
		int count = 0;
		for (long word : dirtyCellWords) count += Long.bitCount(word);
		int[] result = new int[count]; int target = 0;
		for (int word = 0; word < dirtyCellWords.length; word++) {
			long bits = dirtyCellWords[word];
			while (bits != 0L) {
				int bit = Long.numberOfTrailingZeros(bits); result[target++] = (word << 6) + bit; bits &= bits - 1L;
			}
		}
		return result;
	}

	/** Replaces only the bounded reaction scheduler cursor; inventory and audits are untouched. */
	public void restoreDirtyCells(int[] cells) {
		if (cells == null) throw new IllegalArgumentException("Chemical dirty-cell state is missing");
		Arrays.fill(dirtyCellWords, 0L);
		int previous = -1;
		for (int cell : cells) {
			if (cell <= previous || cell < 0 || cell >>> 6 >= dirtyCellWords.length) {
				throw new IllegalArgumentException("Chemical dirty-cell state is not canonical");
			}
			markDirtyCell(cell); previous = cell;
		}
	}

	private void validateRecord(ChemicalMixtureRecord record) {
		for (int entry = 0; entry < record.entryCount(); entry++) registry.species(record.speciesHandle(entry));
	}
	private static long roundNonNegative(ExactRational value) {
		if (value.signum() < 0) throw new IllegalArgumentException("Cannot round a negative inventory");
		BigInteger[] result = value.numerator().divideAndRemainder(value.denominator());
		if (result[1].shiftLeft(1).compareTo(value.denominator()) >= 0) result[0] = result[0].add(BigInteger.ONE);
		return result[0].longValueExact();
	}

	public record ReactionStats(int visitedCells,int evaluations,int changedStocks) { }
}
