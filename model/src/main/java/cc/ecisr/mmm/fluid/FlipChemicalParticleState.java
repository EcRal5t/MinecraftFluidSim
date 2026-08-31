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
import java.util.List;
import java.util.Map;

/** Immutable chemical CPU handoff: material state plus canonical stable mixtures and indicator provenance. */
public final class FlipChemicalParticleState {
	private final FlipMaterialParticleState materials;
	private final String registryFingerprint;
	private final List<String> speciesPalette;
	private final List<StableMixture> mixtures;
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
		this.materials = java.util.Objects.requireNonNull(materials, "materials");
		java.util.Objects.requireNonNull(registry, "registry");
		this.registryFingerprint = registry.fingerprint();
		this.speciesPalette = registry.species().stream().map(cc.ecisr.mmm.chemistry.ChemicalSpecies::id).toList();
		if (mixtureTable == null || mixtureTable.size() > materials.particleCount()
				|| (materials.particleCount() > 0 && mixtureTable.isEmpty())) {
			throw new IllegalArgumentException("Chemical mixture table disagrees with marker inventory");
		}
		ArrayList<StableMixture> canonical = new ArrayList<>(mixtureTable);
		canonical.sort(StableMixture.ORDER);
		if (!canonical.equals(mixtureTable)) throw new IllegalArgumentException("Chemical mixture table is not canonical");
		for (int i = 1; i < canonical.size(); i++) if (canonical.get(i - 1).equals(canonical.get(i))) {
			throw new IllegalArgumentException("Chemical mixture table contains duplicates");
		}
		this.mixtures = List.copyOf(canonical);
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
		List<ChemicalMixtureRecord> runtimeMixtures = mixtures.stream()
				.map(mixture -> mixture.toRuntime(registry)).toList();
		for (int marker = 0; marker < markerMixtureIndex.length; marker++) {
			int table = markerMixtureIndex[marker];
			if (table < 0 || table >= mixtures.size() || (indicator[marker] & ~1) != 0) {
				throw new IllegalArgumentException("Invalid per-marker chemical table index/indicator");
			}
			StableMixture mixture = mixtures.get(table);
			ChemicalMixtureRecord runtime = runtimeMixtures.get(table);
			for (int entry = 0; entry < runtime.entryCount(); entry++) {
				recomputed[runtime.speciesHandle(entry)] = Math.addExact(
						recomputed[runtime.speciesHandle(entry)], runtime.amountAt(entry));
			}
			residual += runtime.massResidualKilograms();
			double representedMass = runtime.massKilograms(registry);
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
		ArrayList<StableMixture> perMarker = new ArrayList<>(markerMixtures.size());
		HashMap<ChemicalMixtureRecord, StableMixture> stableByRuntime = new HashMap<>();
		for (ChemicalMixtureRecord mixture : markerMixtures) {
			perMarker.add(stableByRuntime.computeIfAbsent(
					mixture, value -> StableMixture.fromRuntime(registry, value)));
		}
		java.util.TreeSet<StableMixture> unique = new java.util.TreeSet<>(StableMixture.ORDER);
		unique.addAll(perMarker);
		ArrayList<StableMixture> table = new ArrayList<>(unique);
		Map<StableMixture, Integer> indices = new HashMap<>();
		for (int i = 0; i < table.size(); i++) indices.put(table.get(i), i);
		int[] markerTable = new int[perMarker.size()];
		for (int marker = 0; marker < markerTable.length; marker++) markerTable[marker] = indices.get(perMarker.get(marker));
		long[] totals = new long[registry.species().size()];
		double residual = 0.0;
		for (ChemicalMixtureRecord mixture : markerMixtures) {
			for (int entry = 0; entry < mixture.entryCount(); entry++) totals[mixture.speciesHandle(entry)] = Math.addExact(
					totals[mixture.speciesHandle(entry)], mixture.amountAt(entry));
			residual += mixture.massResidualKilograms();
		}
		return new FlipChemicalParticleState(materials, registry, table, markerTable, indicator, totals,
				Double.doubleToRawLongBits(residual), externalSource, escapedSink);
	}

	public FlipMaterialParticleState materials() { return materials; }
	public String registryFingerprint() { return registryFingerprint; }
	public List<String> speciesPalette() { return speciesPalette; }
	public List<StableMixture> mixtures() { return mixtures; }
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
		for (StableMixture mixture : mixtures) tableBytes += Long.BYTES + (long) mixture.components().size() * (Long.BYTES + 4L);
		return materials.cpuBytes() + tableBytes + (long) markerMixtureIndex.length * Integer.BYTES
				+ indicator.length + (long) speciesTotals.length * Long.BYTES;
	}

	public record StableMixture(List<Component> components, long massResidualBits) {
		private static final Comparator<StableMixture> ORDER = (left, right) -> {
			int size = Integer.compare(left.components.size(), right.components.size());
			for (int i = 0; size == 0 && i < left.components.size(); i++) {
				int id = left.components.get(i).speciesId.compareTo(right.components.get(i).speciesId);
				if (id != 0) return id;
				int amount = Long.compare(left.components.get(i).amountQuanta, right.components.get(i).amountQuanta);
				if (amount != 0) return amount;
			}
			return size != 0 ? size : Long.compareUnsigned(left.massResidualBits, right.massResidualBits);
		};

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
