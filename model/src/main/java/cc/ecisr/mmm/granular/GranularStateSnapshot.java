package cc.ecisr.mmm.granular;

import cc.ecisr.mmm.chemistry.ChemicalRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.IntToLongFunction;

/** Stable-ID, exact-SVQ/kilogram handoff used only by granular region state and transactional adapters. */
public final class GranularStateSnapshot {
	public record ActiveParcel(
			float x, float y, float z, float velocityX, float velocityY, float velocityZ,
			long solidVolumeSvq, double mass, int materialPaletteIndex, int solidCompositionIndex,
			int contactAge, byte flags
	) { }

	public record DepositGroup(
			int cell, int porosityQ, int materialPaletteIndex, int solidCompositionIndex,
			long solidVolumeSvq, double mass
	) {
		private static final Comparator<DepositGroup> ORDER = Comparator.comparingInt(DepositGroup::cell)
				.thenComparingInt(DepositGroup::materialPaletteIndex)
				.thenComparingInt(DepositGroup::solidCompositionIndex);
	}

	public record CompositionComponent(String speciesId, long amountQuanta) {
		public CompositionComponent {
			GranularIds.requireId(speciesId, "solid composition species id");
			if (amountQuanta <= 0L) throw new IllegalArgumentException("Solid composition amount must be positive");
		}
	}

	public record StableComposition(List<CompositionComponent> components, long massResidualBits) {
		private static final Comparator<StableComposition> ORDER = (left, right) -> {
			int size = Integer.compare(left.components.size(), right.components.size());
			for (int index = 0; size == 0 && index < left.components.size(); index++) {
				int id = left.components.get(index).speciesId().compareTo(right.components.get(index).speciesId());
				if (id != 0) return id;
				int amount = Long.compare(left.components.get(index).amountQuanta(), right.components.get(index).amountQuanta());
				if (amount != 0) return amount;
			}
			return size != 0 ? size : Long.compareUnsigned(left.massResidualBits, right.massResidualBits);
		};

		public StableComposition {
			components = List.copyOf(components);
			if (components.isEmpty() || components.size() > GranularLimits.MAX_SOLID_COMPOSITION_ENTRIES
					|| !Double.isFinite(Double.longBitsToDouble(massResidualBits))) {
				throw new IllegalArgumentException("Invalid stable solid composition");
			}
			String previous = null;
			for (CompositionComponent component : components) {
				if (previous != null && previous.compareTo(component.speciesId()) >= 0) {
					throw new IllegalArgumentException("Stable solid composition is not sorted/unique");
				}
				previous = component.speciesId();
			}
		}

		static StableComposition fromRuntime(ChemicalRegistry registry, SolidCompositionRecord record) {
			ArrayList<CompositionComponent> components = new ArrayList<>(record.entryCount());
			for (int entry = 0; entry < record.entryCount(); entry++) components.add(new CompositionComponent(
					registry.species(record.speciesHandle(entry)).id(), record.amountAt(entry)));
			components.sort(Comparator.comparing(CompositionComponent::speciesId));
			return new StableComposition(components, record.massResidualBits());
		}

		SolidCompositionRecord toRuntime(ChemicalRegistry registry) {
			TreeMap<String, Long> amounts = new TreeMap<>();
			for (CompositionComponent component : components) amounts.put(component.speciesId(), component.amountQuanta());
			return SolidCompositionRecord.fromStableAmounts(registry, amounts,
					Double.longBitsToDouble(massResidualBits));
		}
	}

	private final int sizeX, sizeY, sizeZ;
	private final String registryFingerprint, chemicalRegistryFingerprint;
	private final List<String> materialPalette;
	private final List<StableComposition> solidCompositions;
	private final Map<String, Long> solidSpeciesTotals;
	private final List<ActiveParcel> activeParcels;
	private final List<DepositGroup> depositGroups;
	private final long totalSolidVolumeSvq;
	private final long totalMassBits;
	private final GranularAuditLedger externalSource, escapedSink;

	/** Convenience constructor for inert fixtures. */
	public GranularStateSnapshot(
			int sizeX, int sizeY, int sizeZ, String registryFingerprint, List<String> materialPalette,
			List<ActiveParcel> activeParcels, List<DepositGroup> depositGroups,
			long totalSolidVolumeSvq, long totalMassBits
	) {
		this(sizeX, sizeY, sizeZ, registryFingerprint, "", materialPalette, List.of(), Map.of(),
				activeParcels, depositGroups, totalSolidVolumeSvq, totalMassBits,
				GranularAuditLedger.EMPTY, GranularAuditLedger.EMPTY);
	}

	public GranularStateSnapshot(
			int sizeX, int sizeY, int sizeZ, String registryFingerprint, String chemicalRegistryFingerprint,
			List<String> materialPalette, List<StableComposition> solidCompositions,
			Map<String, Long> solidSpeciesTotals, List<ActiveParcel> activeParcels, List<DepositGroup> depositGroups,
			long totalSolidVolumeSvq, long totalMassBits
	) {
		this(sizeX, sizeY, sizeZ, registryFingerprint, chemicalRegistryFingerprint, materialPalette,
				solidCompositions, solidSpeciesTotals, activeParcels, depositGroups, totalSolidVolumeSvq,
				totalMassBits, GranularAuditLedger.EMPTY, GranularAuditLedger.EMPTY);
	}

	public GranularStateSnapshot(
			int sizeX, int sizeY, int sizeZ, String registryFingerprint, String chemicalRegistryFingerprint,
			List<String> materialPalette, List<StableComposition> solidCompositions,
			Map<String, Long> solidSpeciesTotals, List<ActiveParcel> activeParcels, List<DepositGroup> depositGroups,
			long totalSolidVolumeSvq, long totalMassBits,
			GranularAuditLedger externalSource, GranularAuditLedger escapedSink
	) {
		if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0) throw new IllegalArgumentException("Invalid granular snapshot dimensions");
		if (registryFingerprint == null || !registryFingerprint.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException("Invalid granular registry fingerprint");
		}
		this.sizeX = sizeX; this.sizeY = sizeY; this.sizeZ = sizeZ; this.registryFingerprint = registryFingerprint;
		this.chemicalRegistryFingerprint = chemicalRegistryFingerprint == null ? "" : chemicalRegistryFingerprint;
		if (!this.chemicalRegistryFingerprint.isEmpty() && !this.chemicalRegistryFingerprint.matches("[0-9a-f]{64}")) {
			throw new IllegalArgumentException("Invalid solid composition chemical registry fingerprint");
		}
		this.materialPalette = List.copyOf(materialPalette); this.solidCompositions = List.copyOf(solidCompositions);
		this.activeParcels = List.copyOf(activeParcels); this.depositGroups = List.copyOf(depositGroups);
		if (this.materialPalette.isEmpty() || this.materialPalette.size() > GranularLimits.MAX_MATERIALS
				|| this.solidCompositions.size() > GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION
						+ GranularLimits.MAX_DEPOSIT_GROUPS_PER_REGION
				|| this.activeParcels.size() > GranularLimits.MAX_ACTIVE_PARCELS_PER_REGION
				|| this.depositGroups.size() > GranularLimits.MAX_DEPOSIT_GROUPS_PER_REGION) {
			throw new IllegalArgumentException("Granular snapshot exceeds a hard inventory budget");
		}
		String previousId = null;
		for (String id : this.materialPalette) {
			GranularIds.requireId(id, "snapshot particulate material id");
			if (previousId != null && previousId.compareTo(id) >= 0) {
				throw new IllegalArgumentException("Granular material palette is not canonical");
			}
			previousId = id;
		}
		StableComposition previousComposition = null;
		for (StableComposition composition : this.solidCompositions) {
			if (previousComposition != null && StableComposition.ORDER.compare(previousComposition, composition) >= 0) {
				throw new IllegalArgumentException("Solid composition table is not canonical and unique");
			}
			previousComposition = composition;
		}
		if (!this.solidCompositions.isEmpty() && this.chemicalRegistryFingerprint.isEmpty()) {
			throw new IllegalArgumentException("Reactive solid inventory requires chemical registry identity");
		}
		validateActive(); validateDeposits();
		TreeMap<String, Long> canonicalTotals = new TreeMap<>();
		for (Map.Entry<String, Long> entry : solidSpeciesTotals.entrySet()) {
			GranularIds.requireId(entry.getKey(), "solid species ledger id");
			if (entry.getValue() == null || entry.getValue() <= 0L) {
				throw new IllegalArgumentException("Solid species ledger amount must be positive");
			}
			canonicalTotals.put(entry.getKey(), entry.getValue());
		}
		if (!canonicalTotals.equals(recomputeStableSpeciesTotals())) {
			throw new IllegalArgumentException("Solid species ledger mismatch");
		}
		this.solidSpeciesTotals = java.util.Collections.unmodifiableMap(canonicalTotals);
		long computedVolume = 0L; double computedMass = 0.0;
		for (ActiveParcel parcel : this.activeParcels) {
			computedVolume = Math.addExact(computedVolume, parcel.solidVolumeSvq()); computedMass += parcel.mass();
		}
		for (DepositGroup group : this.depositGroups) {
			computedVolume = Math.addExact(computedVolume, group.solidVolumeSvq()); computedMass += group.mass();
		}
		if (computedVolume != totalSolidVolumeSvq || Double.doubleToRawLongBits(computedMass) != totalMassBits) {
			throw new IllegalArgumentException("Granular snapshot total ledger mismatch");
		}
		this.totalSolidVolumeSvq = totalSolidVolumeSvq; this.totalMassBits = totalMassBits;
		this.externalSource = java.util.Objects.requireNonNull(externalSource, "externalSource");
		this.escapedSink = java.util.Objects.requireNonNull(escapedSink, "escapedSink");
		if ((this.externalSource.requiresChemistry() || this.escapedSink.requiresChemistry())
				&& this.chemicalRegistryFingerprint.isEmpty()) {
			throw new IllegalArgumentException("Reactive granular audit requires chemical registry identity");
		}
	}

	public static GranularStateSnapshot capture(
			int sizeX, int sizeY, int sizeZ, GranularRegistry registry,
			GranularParcelStore parcels, GranularDepositStore deposits
	) { return capture(sizeX, sizeY, sizeZ, registry, parcels, deposits, null); }

	public static GranularStateSnapshot capture(
			int sizeX, int sizeY, int sizeZ, GranularRegistry registry,
			GranularParcelStore parcels, GranularDepositStore deposits, SolidCompositionPool pool
	) {
		return capture(sizeX, sizeY, sizeZ, registry, parcels, deposits, pool,
				GranularAuditLedger.EMPTY, GranularAuditLedger.EMPTY);
	}

	public static GranularStateSnapshot capture(
			int sizeX, int sizeY, int sizeZ, GranularRegistry registry,
			GranularParcelStore parcels, GranularDepositStore deposits, SolidCompositionPool pool,
			GranularAuditLedger externalSource, GranularAuditLedger escapedSink
	) {
		externalSource.validateAgainst(registry, pool == null ? null : pool.registry());
		escapedSink.validateAgainst(registry, pool == null ? null : pool.registry());
		List<String> palette = registry.materials().stream().map(ParticulateMaterial::id).toList();
		Set<Integer> usedHandles = new HashSet<>(); TreeSet<StableComposition> unique = new TreeSet<>(StableComposition.ORDER);
		java.util.function.IntFunction<StableComposition> stable = handle -> {
			if (handle == 0) return null;
			if (pool == null || !pool.isLive(handle) || !usedHandles.add(handle)) {
				throw new IllegalStateException("Solid composition handle ownership is invalid");
			}
			StableComposition result = StableComposition.fromRuntime(pool.registry(), pool.record(handle));
			unique.add(result); return result;
		};
		StableComposition[] activeStable = new StableComposition[parcels.count()];
		for (int index = 0; index < parcels.count(); index++) activeStable[index] = stable.apply(parcels.parcel(index).solidCompositionHandle());
		List<DepositDraft> drafts = new ArrayList<>(deposits.groupCount());
		deposits.forEachGroup((cell, porosity, material, composition, volume, mass) ->
				drafts.add(new DepositDraft(cell, porosity, material, stable.apply(composition), volume, mass)));
		if (pool != null && usedHandles.size() != pool.liveCount()) throw new IllegalStateException("Orphan solid composition handle");
		List<StableComposition> table = List.copyOf(unique); Map<StableComposition, Integer> indices = new HashMap<>();
		for (int index = 0; index < table.size(); index++) indices.put(table.get(index), index + 1);
		List<ActiveParcel> active = new ArrayList<>(parcels.count());
		for (int index = 0; index < parcels.count(); index++) {
			GranularParcelStore.Parcel parcel = parcels.parcel(index); StableComposition composition = activeStable[index];
			active.add(new ActiveParcel(parcel.x(), parcel.y(), parcel.z(), parcel.velocityX(), parcel.velocityY(),
					parcel.velocityZ(), parcel.solidVolumeSvq(), parcel.mass(), parcel.materialHandle(),
					composition == null ? 0 : indices.get(composition), parcel.contactAge(), parcel.flags()));
		}
		List<DepositGroup> deposit = new ArrayList<>(drafts.size());
		for (DepositDraft draft : drafts) deposit.add(new DepositGroup(draft.cell(), draft.porosityQ(), draft.material(),
				draft.composition() == null ? 0 : indices.get(draft.composition()), draft.volume(), draft.mass()));
		deposit.sort(DepositGroup.ORDER);
		TreeMap<String, Long> totals = stableTotals(active, deposit, table);
		long volume = Math.addExact(parcels.totalSolidVolumeSvq(), deposits.totalSolidVolumeSvq());
		double mass = parcels.totalMass() + deposits.totalMass();
		return new GranularStateSnapshot(sizeX, sizeY, sizeZ, registry.fingerprint(),
				pool == null ? "" : pool.registry().fingerprint(), palette, table, totals, active, deposit,
				volume, Double.doubleToRawLongBits(mass), externalSource, escapedSink);
	}

	public void restoreInto(
			GranularRegistry registry, GranularParcelStore parcels, GranularDepositStore deposits,
			IntToLongFunction geometricOpenSvq
	) { restoreInto(registry, parcels, deposits, null, geometricOpenSvq); }

	public void restoreInto(
			GranularRegistry registry, GranularParcelStore parcels, GranularDepositStore deposits,
			SolidCompositionPool pool, IntToLongFunction geometricOpenSvq
	) {
		if (parcels.count() != 0 || deposits.groupCount() != 0 || pool != null && pool.liveCount() != 0) {
			throw new IllegalStateException("Granular restore target is not empty");
		}
		if (!registryFingerprint.equals(registry.fingerprint())
				|| !materialPalette.equals(registry.materials().stream().map(ParticulateMaterial::id).toList())) {
			throw new IllegalArgumentException("Granular snapshot registry identity mismatch");
		}
		if (requiresChemistry() && (pool == null
				|| !chemicalRegistryFingerprint.equals(pool.registry().fingerprint()))) {
			throw new IllegalArgumentException("Granular reactive payload registry identity mismatch");
		}
		externalSource.validateAgainst(registry, pool == null ? null : pool.registry());
		escapedSink.validateAgainst(registry, pool == null ? null : pool.registry());
		for (ActiveParcel parcel : activeParcels) {
			int composition = restoreComposition(parcel.solidCompositionIndex(), parcel.mass(), pool);
			parcels.add(new GranularParcelStore.Parcel(parcel.x(), parcel.y(), parcel.z(), parcel.velocityX(),
					parcel.velocityY(), parcel.velocityZ(), parcel.solidVolumeSvq(), parcel.mass(),
					parcel.materialPaletteIndex(), composition, parcel.contactAge(), parcel.flags()));
		}
		int previousCell = -1, expectedPorosity = 0;
		for (DepositGroup group : depositGroups) {
			if (previousCell != group.cell() && previousCell >= 0 && deposits.porosityQ(previousCell) != expectedPorosity) {
				throw new IllegalArgumentException("Persisted deposit porosity is not registry-derived");
			}
			int composition = restoreComposition(group.solidCompositionIndex(), group.mass(), pool);
			if (!deposits.tryAdd(group.cell(), new GranularDepositStore.SolidPortion(group.materialPaletteIndex(), composition,
					group.solidVolumeSvq(), group.mass()), geometricOpenSvq.applyAsLong(group.cell()))) {
				throw new IllegalArgumentException("Persisted granular deposit exceeds restored cell capacity");
			}
			previousCell = group.cell(); expectedPorosity = group.porosityQ();
		}
		if (previousCell >= 0 && deposits.porosityQ(previousCell) != expectedPorosity) {
			throw new IllegalArgumentException("Persisted deposit porosity is not registry-derived");
		}
		if (Math.addExact(parcels.totalSolidVolumeSvq(), deposits.totalSolidVolumeSvq()) != totalSolidVolumeSvq
				|| Double.doubleToRawLongBits(parcels.totalMass() + deposits.totalMass()) != totalMassBits) {
			throw new IllegalStateException("Restored granular inventory does not match snapshot ledger");
		}
	}

	public void validateSolidCompositionMasses(ChemicalRegistry registry) {
		if (solidCompositions.isEmpty()) return;
		if (registry == null || !chemicalRegistryFingerprint.equals(registry.fingerprint())) {
			throw new IllegalArgumentException("Solid composition chemical registry identity mismatch");
		}
		SolidCompositionRecord[] runtime = new SolidCompositionRecord[solidCompositions.size()];
		for (int index = 0; index < runtime.length; index++) runtime[index] = solidCompositions.get(index).toRuntime(registry);
		for (ActiveParcel parcel : activeParcels) if (parcel.solidCompositionIndex() > 0
				&& !massClose(runtime[parcel.solidCompositionIndex() - 1].totalMass(registry), parcel.mass())) {
			throw new IllegalArgumentException("Active solid composition mass ledger mismatch");
		}
		for (DepositGroup group : depositGroups) if (group.solidCompositionIndex() > 0
				&& !massClose(runtime[group.solidCompositionIndex() - 1].totalMass(registry), group.mass())) {
			throw new IllegalArgumentException("Deposit solid composition mass ledger mismatch");
		}
	}

	public void validateAgainst(GranularRegistry registry, ChemicalRegistry chemistry) {
		if (registry == null) throw new IllegalArgumentException("Granular registry is missing");
		if (requiresChemistry() && (chemistry == null
				|| !chemicalRegistryFingerprint.equals(chemistry.fingerprint()))) {
			throw new IllegalArgumentException("Granular reactive payload chemical registry identity mismatch");
		}
		validateSolidCompositionMasses(chemistry);
		externalSource.validateAgainst(registry, chemistry);
		escapedSink.validateAgainst(registry, chemistry);
	}

	private int restoreComposition(int index, double expectedMass, SolidCompositionPool pool) {
		if (index == 0) return 0;
		SolidCompositionRecord record = solidCompositions.get(index - 1).toRuntime(pool.registry());
		if (!massClose(record.totalMass(pool.registry()), expectedMass)) {
			throw new IllegalArgumentException("Solid composition mass does not match granular inventory");
		}
		return pool.create(record);
	}

	private void validateActive() {
		for (ActiveParcel parcel : activeParcels) {
			if (!(Float.isFinite(parcel.x()) && Float.isFinite(parcel.y()) && Float.isFinite(parcel.z())
					&& parcel.x() >= 0.0F && parcel.x() < sizeX * 0.5F && parcel.y() >= 0.0F && parcel.y() < sizeY * 0.5F
					&& parcel.z() >= 0.0F && parcel.z() < sizeZ * 0.5F && Float.isFinite(parcel.velocityX())
					&& Float.isFinite(parcel.velocityY()) && Float.isFinite(parcel.velocityZ())
					&& parcel.solidVolumeSvq() > 0L && Double.isFinite(parcel.mass()) && parcel.mass() > 0.0
					&& validMaterial(parcel.materialPaletteIndex()) && validComposition(parcel.solidCompositionIndex())
					&& parcel.contactAge() >= 0
					&& (parcel.flags() & ~GranularParcelStore.FLAG_SUPPORTED) == 0)) {
				throw new IllegalArgumentException("Invalid active granular snapshot parcel");
			}
		}
	}

	private void validateDeposits() {
		DepositGroup previous = null; int groupsInCell = 0;
		for (DepositGroup group : depositGroups) {
			SolidVolume.requirePorosity(group.porosityQ());
			if (group.cell() < 0 || group.cell() >= sizeX * sizeY * sizeZ || !validMaterial(group.materialPaletteIndex())
					|| !validComposition(group.solidCompositionIndex()) || group.solidVolumeSvq() <= 0L
					|| !(Double.isFinite(group.mass()) && group.mass() > 0.0)) {
				throw new IllegalArgumentException("Invalid granular deposit snapshot group");
			}
			if (previous != null && DepositGroup.ORDER.compare(previous, group) >= 0) {
				throw new IllegalArgumentException("Granular deposit groups are not canonical and unique");
			}
			groupsInCell = previous != null && previous.cell() == group.cell() ? groupsInCell + 1 : 1;
			if (groupsInCell > GranularLimits.MAX_DEPOSIT_GROUPS_PER_CELL) {
				throw new IllegalArgumentException("Granular deposit cell group budget exceeded");
			}
			if (previous != null && previous.cell() == group.cell() && previous.porosityQ() != group.porosityQ()) {
				throw new IllegalArgumentException("Granular deposit cell has conflicting porosity");
			}
			previous = group;
		}
	}

	private TreeMap<String, Long> recomputeStableSpeciesTotals() {
		return stableTotals(activeParcels, depositGroups, solidCompositions);
	}

	private static TreeMap<String, Long> stableTotals(
			List<ActiveParcel> active, List<DepositGroup> deposits, List<StableComposition> compositions
	) {
		TreeMap<String, Long> totals = new TreeMap<>();
		for (ActiveParcel parcel : active) addStable(totals, parcel.solidCompositionIndex(), compositions);
		for (DepositGroup group : deposits) addStable(totals, group.solidCompositionIndex(), compositions);
		return totals;
	}

	private static void addStable(Map<String, Long> totals, int compositionIndex, List<StableComposition> compositions) {
		if (compositionIndex == 0) return;
		for (CompositionComponent component : compositions.get(compositionIndex - 1).components()) {
			totals.merge(component.speciesId(), component.amountQuanta(), Math::addExact);
		}
	}

	private boolean validMaterial(int handle) { return handle >= 0 && handle < materialPalette.size(); }
	private boolean validComposition(int index) { return index >= 0 && index <= solidCompositions.size(); }
	private static boolean massClose(double left, double right) {
		return Math.abs(left - right) <= Math.max(1.0E-9, Math.max(Math.ulp(left), Math.ulp(right)) * 8.0);
	}

	private record DepositDraft(
			int cell, int porosityQ, int material, StableComposition composition, long volume, double mass
	) { }

	public int sizeX() { return sizeX; }
	public int sizeY() { return sizeY; }
	public int sizeZ() { return sizeZ; }
	public String registryFingerprint() { return registryFingerprint; }
	public String chemicalRegistryFingerprint() { return chemicalRegistryFingerprint; }
	public boolean requiresChemistry() {
		return !solidCompositions.isEmpty() || externalSource.requiresChemistry() || escapedSink.requiresChemistry();
	}
	public List<String> materialPalette() { return materialPalette; }
	public List<StableComposition> solidCompositions() { return solidCompositions; }
	public Map<String, Long> solidSpeciesTotals() { return solidSpeciesTotals; }
	public List<ActiveParcel> activeParcels() { return activeParcels; }
	public List<DepositGroup> depositGroups() { return depositGroups; }
	public long totalSolidVolumeSvq() { return totalSolidVolumeSvq; }
	public long totalMassBits() { return totalMassBits; }
	public double totalMass() { return Double.longBitsToDouble(totalMassBits); }
	public GranularAuditLedger externalSource() { return externalSource; }
	public GranularAuditLedger escapedSink() { return escapedSink; }
}
