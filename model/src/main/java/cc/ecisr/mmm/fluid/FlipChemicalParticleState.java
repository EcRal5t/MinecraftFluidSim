package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.chemistry.ChemicalLimits;
import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.chemistry.ChemicalTransferLedger;
import cc.ecisr.mmm.units.SolverUnitScale;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Immutable chemical CPU handoff: material state plus canonical stable mixtures and indicator provenance. */
public final class FlipChemicalParticleState {
	private final FlipMaterialParticleState materials;
	private final String registryFingerprint;
	private final List<String> speciesPalette;
	private volatile List<StableMixture> mixtures;
	private final ChemicalRegistry runtimeRegistry;
	private final List<ChemicalMixtureRecord> runtimeMixtures;
	private final int[] markerMixtureIndex;
	private final byte[] indicator;
	private final long[] speciesTotals;
	private final long residualLedgerBits;
	private final ChemicalTransferLedger externalSource;
	private final ChemicalTransferLedger escapedSink;

	public FlipChemicalParticleState(
			FlipMaterialParticleState materials,
			ChemicalRegistry registry,
			List<StableMixture> mixtureTable,
			int[] markerMixtureIndex,
			byte[] indicator,
			long[] speciesTotals,
			long residualLedgerBits,
			ChemicalTransferLedger externalSource,
			ChemicalTransferLedger escapedSink
	) {
		this(materials, registry, mixtureTable, markerMixtureIndex, indicator, speciesTotals,
				residualLedgerBits, externalSource, escapedSink, null);
	}

	private FlipChemicalParticleState(
			FlipMaterialParticleState materials, ChemicalRegistry registry, List<StableMixture> mixtureTable,
			int[] markerMixtureIndex, byte[] indicator, long[] speciesTotals, long residualLedgerBits,
			ChemicalTransferLedger externalSource, ChemicalTransferLedger escapedSink,
			List<ChemicalMixtureRecord> runtimeMixtures
	) {
		this.materials = java.util.Objects.requireNonNull(materials, "materials");
		java.util.Objects.requireNonNull(registry, "registry");
		this.registryFingerprint = registry.fingerprint();
		this.speciesPalette = registry.species().stream().map(cc.ecisr.mmm.chemistry.ChemicalSpecies::id).toList();
		this.runtimeRegistry = registry;
		if (runtimeMixtures == null && (mixtureTable == null || mixtureTable.size() > materials.particleCount())) {
			throw new IllegalArgumentException("Chemical mixture table is missing or over budget");
		}
		this.mixtures = mixtureTable == null ? null : List.copyOf(mixtureTable);
		this.runtimeMixtures = runtimeMixtures == null ? mixtures.stream()
				.map(mixture -> mixture.toRuntime(registry)).toList() : List.copyOf(runtimeMixtures);
		runtimeMixtures = this.runtimeMixtures;
		if (runtimeMixtures.size() > materials.particleCount()
				|| (materials.particleCount() > 0 && runtimeMixtures.isEmpty())) {
			throw new IllegalArgumentException("Chemical mixture table disagrees with marker inventory");
		}
		for (int i = 1; i < runtimeMixtures.size(); i++) {
			if (compareMixtures(runtimeMixtures.get(i - 1), runtimeMixtures.get(i)) >= 0) {
				throw new IllegalArgumentException("Chemical mixture table is not canonical/unique");
			}
		}
		if (markerMixtureIndex == null || indicator == null || speciesTotals == null
				|| markerMixtureIndex.length != materials.particleCount()
				|| indicator.length != materials.particleCount()
				|| speciesTotals.length != registry.species().size()) {
			throw new IllegalArgumentException("Chemical marker arrays disagree with material state");
		}
		this.markerMixtureIndex = markerMixtureIndex.clone();
		this.indicator = indicator.clone();
		this.speciesTotals = speciesTotals.clone();
		long[] recomputed = new long[speciesTotals.length];
		double residual = 0.0;
		double[] mass = materials.copyParticleMass();
		SolverUnitScale unitScale = SolverUnitScale.forCellSize(materials.cellSize());
		double[] mixtureMass = new double[runtimeMixtures.size()];
		for (int table = 0; table < mixtureMass.length; table++) {
			mixtureMass[table] = runtimeMixtures.get(table).massKilograms(registry);
		}
		for (int marker = 0; marker < markerMixtureIndex.length; marker++) {
			int table = markerMixtureIndex[marker];
			if (table < 0 || table >= runtimeMixtures.size() || (indicator[marker] & ~1) != 0) {
				throw new IllegalArgumentException("Invalid per-marker chemical table index/indicator");
			}
			ChemicalMixtureRecord runtime = runtimeMixtures.get(table);
			for (int entry = 0; entry < runtime.entryCount(); entry++) {
				recomputed[runtime.speciesHandle(entry)] = Math.addExact(
						recomputed[runtime.speciesHandle(entry)], runtime.amountAt(entry));
			}
			residual += runtime.massResidualKilograms();
			double representedMass = mixtureMass[table];
			double physicalMass = unitScale.kilograms(mass[marker]);
			double tolerance = Math.max(1.0E-9, Math.ulp(physicalMass) * 8.0);
			if (Math.abs(representedMass - physicalMass) > tolerance) {
				throw new IllegalArgumentException("Chemical marker mass relation does not close at ordinal " + marker);
			}
		}
		if (!Arrays.equals(recomputed, speciesTotals)) throw new IllegalArgumentException("Chemical species ledger mismatch");
		if (Double.doubleToRawLongBits(residual) != residualLedgerBits) {
			throw new IllegalArgumentException("Chemical mass residual ledger mismatch");
		}
		this.residualLedgerBits = residualLedgerBits;
		this.externalSource = java.util.Objects.requireNonNull(externalSource, "externalSource");
		this.escapedSink = java.util.Objects.requireNonNull(escapedSink, "escapedSink");
	}

	public static FlipChemicalParticleState capture(
			FlipMaterialParticleState materials, ChemicalRegistry registry,
			List<ChemicalMixtureRecord> markerMixtures, byte[] indicator,
			ChemicalTransferLedger externalSource, ChemicalTransferLedger escapedSink
	) {
		if (markerMixtures.size() != materials.particleCount()) throw new IllegalArgumentException("Marker mixture count mismatch");
		ArrayList<ChemicalMixtureRecord> table = new ArrayList<>(new HashSet<>(markerMixtures));
		table.sort(FlipChemicalParticleState::compareMixtures);
		Map<ChemicalMixtureRecord, Integer> indices = new HashMap<>();
		for (int i = 0; i < table.size(); i++) indices.put(table.get(i), i);
		int[] markerTable = new int[markerMixtures.size()];
		for (int marker = 0; marker < markerTable.length; marker++) markerTable[marker] = indices.get(markerMixtures.get(marker));
		long[] totals = new long[registry.species().size()];
		double residual = 0.0;
		for (ChemicalMixtureRecord mixture : markerMixtures) {
			for (int entry = 0; entry < mixture.entryCount(); entry++) totals[mixture.speciesHandle(entry)] = Math.addExact(
					totals[mixture.speciesHandle(entry)], mixture.amountAt(entry));
			residual += mixture.massResidualKilograms();
		}
		return new FlipChemicalParticleState(materials, registry, null, markerTable, indicator, totals,
				Double.doubleToRawLongBits(residual), externalSource, escapedSink, table);
	}

	// ChemicalRegistry assigns handles in stable species-ID order, matching the exported table order.
	private static int compareMixtures(ChemicalMixtureRecord left, ChemicalMixtureRecord right) {
		int size = Integer.compare(left.entryCount(), right.entryCount());
		for (int i = 0; size == 0 && i < left.entryCount(); i++) {
			int species = Integer.compare(left.speciesHandle(i), right.speciesHandle(i));
			if (species != 0) return species;
			int amount = Long.compare(left.amountAt(i), right.amountAt(i));
			if (amount != 0) return amount;
		}
		return size != 0 ? size : Long.compareUnsigned(left.massResidualKilogramsBits(), right.massResidualKilogramsBits());
	}

	public FlipMaterialParticleState materials() { return materials; }
	public String registryFingerprint() { return registryFingerprint; }
	public List<String> speciesPalette() { return speciesPalette; }
	public List<StableMixture> mixtures() {
		List<StableMixture> stable = mixtures;
		if (stable == null) mixtures = stable = runtimeMixtures.stream()
				.map(mixture -> StableMixture.fromRuntime(runtimeRegistry, mixture)).toList();
		return stable;
	}
	/** Immutable records may be shared; every restored store owns its marker handles and packed arrays. */
	public List<ChemicalMixtureRecord> runtimeMixtures(ChemicalRegistry registry) {
		if (registry == runtimeRegistry) return runtimeMixtures;
		if (registry == null || !registryFingerprint.equals(registry.fingerprint())) {
			throw new IllegalArgumentException("Chemical handoff registry identity mismatch");
		}
		return mixtures().stream().map(mixture -> mixture.toRuntime(registry)).toList();
	}
	public int[] copyMarkerMixtureIndex() { return markerMixtureIndex.clone(); }
	public byte[] copyIndicator() { return indicator.clone(); }
	public long[] copySpeciesTotals() { return speciesTotals.clone(); }
	public long residualLedgerBits() { return residualLedgerBits; }
	public double residualLedger() { return Double.longBitsToDouble(residualLedgerBits); }
	public ChemicalTransferLedger externalSource() { return externalSource; }
	public ChemicalTransferLedger escapedSink() { return escapedSink; }
	public int particleCount() { return materials.particleCount(); }
	public long cpuBytes() {
		long tableBytes = 0L;
		for (ChemicalMixtureRecord mixture : runtimeMixtures) tableBytes += Long.BYTES + (long) mixture.entryCount() * (Long.BYTES + 4L);
		return materials.cpuBytes() + tableBytes + (long) markerMixtureIndex.length * Integer.BYTES
				+ indicator.length + (long) speciesTotals.length * Long.BYTES;
	}

	public record StableMixture(List<Component> components, long massResidualBits) {

		public StableMixture {
			components = List.copyOf(components);
			if (components.isEmpty() || components.size() > ChemicalLimits.MAX_SPECIES_PER_PARCEL
					|| !Double.isFinite(Double.longBitsToDouble(massResidualBits))) {
				throw new IllegalArgumentException("Invalid stable chemical mixture");
			}
			String previous = null;
			for (Component component : components) {
				if (previous != null && previous.compareTo(component.speciesId) >= 0) {
					throw new IllegalArgumentException("Stable chemical mixture is not sorted/unique");
				}
				previous = component.speciesId;
			}
		}

		public static StableMixture fromRuntime(ChemicalRegistry registry, ChemicalMixtureRecord record) {
			ArrayList<Component> components = new ArrayList<>(record.entryCount());
			for (int entry = 0; entry < record.entryCount(); entry++) components.add(new Component(
					registry.species(record.speciesHandle(entry)).id(), record.amountAt(entry)));
			components.sort(Comparator.comparing(Component::speciesId));
			return new StableMixture(components, record.massResidualKilogramsBits());
		}

		public ChemicalMixtureRecord toRuntime(ChemicalRegistry registry) {
			Map<String, Long> amounts = new HashMap<>();
			for (Component component : components) amounts.put(component.speciesId, component.amountQuanta);
			return ChemicalMixtureRecord.fromStableAmounts(registry, amounts, Double.longBitsToDouble(massResidualBits));
		}
	}

	public record Component(String speciesId, long amountQuanta) {
		public Component {
			if (speciesId == null || speciesId.isBlank() || amountQuanta <= 0L) {
				throw new IllegalArgumentException("Invalid stable chemical component");
			}
		}
	}
}
