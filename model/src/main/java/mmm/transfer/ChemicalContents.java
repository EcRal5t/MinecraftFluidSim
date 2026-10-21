package mmm.transfer;

import mmm.units.SolverUnitScale;
import mmm.api.StableId;
import mmm.chemistry.ChemicalLimits;
import mmm.chemistry.ChemicalMixtureRecord;
import mmm.chemistry.ChemicalRegistry;
import mmm.chemistry.ChemicalTransferLedger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.math.BigInteger;

/** Canonical material/indicator/species bucket inventory, independent from {@link FluidMixture}. */
public record ChemicalContents(String registryFingerprint, long quantaPerMol, List<Group> groups) {
	private static final String ENCODING_TAG = "mmm:chemical-contents;" + mmm.units.Mass.ENCODING;
	public static final int MAX_GROUPS = 16;
	public static final int MAX_TOTAL_ENTRIES = 64;
	public static final int MAX_ENCODED_BYTES = 64 * 1024;

	public ChemicalContents {
		if (registryFingerprint == null || !registryFingerprint.matches("[0-9a-f]{64}")
				|| quantaPerMol != ChemicalLimits.QUANTA_PER_MOL || groups == null || groups.isEmpty()
				|| groups.size() > MAX_GROUPS) throw new IllegalArgumentException("Invalid chemical contents identity/groups");
		ArrayList<Group> canonical = new ArrayList<>(groups);
		canonical.sort(Group.ORDER);
		if (!canonical.equals(groups)) throw new IllegalArgumentException("Chemical contents groups are not canonical");
		Set<String> keys = new HashSet<>(); int entries = 0; long volume = 0L, quanta = 0L;
		long mass = 0;
		for (Group group : canonical) {
			if (!keys.add(group.materialId() + "\u0000" + group.indicator())) {
				throw new IllegalArgumentException("Duplicate chemical material/indicator group");
			}
			entries = Math.addExact(entries, group.species().size());
			volume = Math.addExact(volume, group.volumeNanos()); mass = Math.addExact(mass, group.massUnits());
			for (SpeciesAmount species : group.species()) quanta = Math.addExact(quanta, species.amountQuanta());
		}
		if (entries > MAX_TOTAL_ENTRIES || volume > ContainerFluidLedger.NANOS_PER_BLOCK
				|| quanta > ChemicalLimits.MAX_BUCKET_TOTAL_QUANTA || mass <= 0L) {
			throw new IllegalArgumentException("Chemical contents exceed a frozen bucket budget");
		}
		groups = List.copyOf(canonical);
	}

	public static ChemicalContents fromLedger(
			ChemicalTransferLedger ledger, ChemicalRegistry registry, long expectedVolumeNanos
	) {
		if (ledger == null || ledger.isEmpty() || expectedVolumeNanos <= 0L
				|| expectedVolumeNanos > ContainerFluidLedger.NANOS_PER_BLOCK) {
			throw new IllegalArgumentException("Invalid chemical transfer ledger/bucket volume");
		}
		double totalVolume = ledger.totalNormalizedVolume(); long assigned = 0L;
		long[] nanosByGroup = new long[ledger.groups().size()];
		double[] remainders = new double[nanosByGroup.length];
		for (int index = 0; index < nanosByGroup.length; index++) {
			double exact = expectedVolumeNanos * ledger.groups().get(index).normalizedVolume() / totalVolume;
			nanosByGroup[index] = (long) Math.floor(exact); remainders[index] = exact - nanosByGroup[index];
			assigned = Math.addExact(assigned, nanosByGroup[index]);
		}
		Integer[] allocationOrder = new Integer[nanosByGroup.length];
		for (int index = 0; index < allocationOrder.length; index++) allocationOrder[index] = index;
		java.util.Arrays.sort(allocationOrder, Comparator.<Integer>comparingDouble(index -> remainders[index]).reversed()
				.thenComparingInt(Integer::intValue));
		for (long remainder = expectedVolumeNanos - assigned, index = 0; index < remainder; index++) {
			nanosByGroup[allocationOrder[(int) index]]++;
		}
		ArrayList<Group> groups = new ArrayList<>(ledger.groups().size());
		for (int index = 0; index < ledger.groups().size(); index++) {
			ChemicalTransferLedger.Group source = ledger.groups().get(index);
			long nanos = nanosByGroup[index];
			if (nanos <= 0L) throw new IllegalArgumentException("Chemical group is below bucket nanovolume resolution");
			List<SpeciesAmount> species = source.speciesQuanta().entrySet().stream()
					.sorted(java.util.Map.Entry.comparingByKey())
					.map(entry -> new SpeciesAmount(entry.getKey(), entry.getValue())).toList();
			groups.add(new Group(source.materialId(), source.indicator(), nanos,
					source.massUnits(),
					source.massResidualUnits(), species));
		}
		ChemicalContents contents = new ChemicalContents(registry.fingerprint(), ChemicalLimits.QUANTA_PER_MOL, groups);
		contents.validateAgainst(registry);
		return contents;
	}

	public void validateAgainst(ChemicalRegistry registry) {
		if (registry == null || !registryFingerprint.equals(registry.fingerprint())) {
			throw new IllegalArgumentException("Foreign chemical registry fingerprint");
		}
		for (Group group : groups) {
			var phase = registry.phase(registry.phaseHandleForMaterial(group.materialId()));
			if (group.indicator() && !phase.acceptsIndicator()) {
				throw new IllegalArgumentException("Chemical phase does not accept indicator: " + group.materialId());
			}
			java.util.Map<String, Long> amounts = new java.util.TreeMap<>();
			for (SpeciesAmount species : group.species()) {
				if (!phase.allowedSpecies().contains(species.speciesId())) {
					throw new IllegalArgumentException("Species is not compatible with chemical phase: " + species.speciesId());
				}
				amounts.put(species.speciesId(), species.amountQuanta());
			}
			ChemicalMixtureRecord mixture = ChemicalMixtureRecord.fromStableAmounts(
					registry, amounts, group.massResidualUnits());
			if (mixture.massUnits(registry) != group.massUnits()) {
				throw new IllegalArgumentException("Chemical bucket species/mass ledger mismatch");
			}
		}
	}

	public ChemicalTransferLedger toLedger(ChemicalRegistry registry, SolverUnitScale units) {
		validateAgainst(registry);
		if (units == null) {
			throw new IllegalArgumentException("Invalid container-to-cell volume scale");
		}
		List<ChemicalTransferLedger.Group> result = new ArrayList<>(groups.size());
		for (Group group : groups) {
			java.util.TreeMap<String, Long> species = new java.util.TreeMap<>();
			for (SpeciesAmount amount : group.species()) species.put(amount.speciesId(), amount.amountQuanta());
			result.add(new ChemicalTransferLedger.Group(group.materialId(), group.indicator(),
					units.normalizedVolume(ContainerFluidLedger.nanosToCubicMeters(group.volumeNanos())),
					group.massUnits(), group.massResidualUnits(), species));
		}
		return new ChemicalTransferLedger(result);
	}

	public long totalVolumeNanos() {
		long result = 0L; for (Group group : groups) result = Math.addExact(result, group.volumeNanos()); return result;
	}
	public long totalMassUnits() {
		long total = 0;
		for (Group value : groups) total = Math.addExact(total, value.massUnits());
		return total;
	}
	public double totalMass() { return mmm.units.Mass.kilograms(totalMassUnits()); }
	public long totalQuanta() {
		long result = 0L; for (Group group : groups) for (SpeciesAmount species : group.species()) {
			result = Math.addExact(result, species.amountQuanta());
		} return result;
	}

	/** Exact nanovolume/species split for partial settlement; both outputs remain canonical. */
	public Split split(long insertedNanos, ChemicalRegistry registry) {
		validateAgainst(registry);
		long total = totalVolumeNanos();
		if (insertedNanos <= 0L || insertedNanos > total) throw new IllegalArgumentException("Invalid chemical split volume");
		if (insertedNanos == total) return new Split(this, null);
		long[] insertedGroupVolume = largestRemainderVolumes(insertedNanos);
		ArrayList<Group> inserted = new ArrayList<>(), remaining = new ArrayList<>();
		for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
			Group group = groups.get(groupIndex);
			long insertedVolume = insertedGroupVolume[groupIndex];
			long remainingVolume = group.volumeNanos() - insertedVolume;
			if (insertedVolume == 0L) {
				remaining.add(group);
				continue;
			}
			if (remainingVolume == 0L) {
				inserted.add(group);
				continue;
			}
			long groupMass = group.massUnits();
			long insertedMass = mmm.units.Mass.portion(groupMass, insertedVolume, group.volumeNanos());
			long remainingMass = Math.subtractExact(groupMass, insertedMass);
			var masses=new long[]{insertedMass,remainingMass};
			var volumes=new mmm.units.SignedDyadic[]{mmm.units.SignedDyadic.of(insertedVolume,0),mmm.units.SignedDyadic.of(remainingVolume,0)};
			ArrayList<SpeciesAmount> insertedSpecies = new ArrayList<>(), remainingSpecies = new ArrayList<>();
			for (SpeciesAmount species : group.species()) {
				long amount=mmm.inventory.LiquidCellInventory.allocateSpecies(species.amountQuanta(),masses,volumes)[0];
				if (amount > 0L) insertedSpecies.add(new SpeciesAmount(species.speciesId(), amount));
				if (amount < species.amountQuanta()) remainingSpecies.add(
						new SpeciesAmount(species.speciesId(), species.amountQuanta() - amount));
			}
			if (!group.species().isEmpty() && (insertedSpecies.isEmpty() || remainingSpecies.isEmpty())) {
				throw new IllegalArgumentException("Chemical partial settlement is below species quantum resolution");
			}
			inserted.add(splitGroup(group, insertedVolume, insertedMass, insertedSpecies, registry));
			remaining.add(splitGroup(group, remainingVolume, remainingMass, remainingSpecies, registry));
		}
		if (inserted.isEmpty() || remaining.isEmpty()) throw new IllegalArgumentException("Chemical split produced an empty side");
		return new Split(new ChemicalContents(registryFingerprint, quantaPerMol, inserted),
				new ChemicalContents(registryFingerprint, quantaPerMol, remaining));
	}

	private Group splitGroup(
			Group source, long volume, long mass, List<SpeciesAmount> species, ChemicalRegistry registry
	) {
		java.util.TreeMap<String, Long> amounts = new java.util.TreeMap<>();
		for (SpeciesAmount amount : species) amounts.put(amount.speciesId(), amount.amountQuanta());
		ChemicalMixtureRecord exact = ChemicalMixtureRecord.fromStableAmounts(registry, amounts, 0L);
		long residual = Math.subtractExact(mass, exact.speciesMassUnits(registry));
		return new Group(source.materialId(), source.indicator(), volume, mass,
				residual, species);
	}

	private long[] largestRemainderVolumes(long requested) {
		long total = totalVolumeNanos(), assigned = 0L;
		long[] result = new long[groups.size()];
		BigInteger[] remainders = new BigInteger[groups.size()];
		BigInteger denominator = BigInteger.valueOf(total);
		for (int index = 0; index < groups.size(); index++) {
			BigInteger[] division = BigInteger.valueOf(requested).multiply(BigInteger.valueOf(groups.get(index).volumeNanos()))
					.divideAndRemainder(denominator);
			result[index] = division[0].longValueExact();
			remainders[index] = division[1];
			assigned = Math.addExact(assigned, result[index]);
		}
		Integer[] order = new Integer[groups.size()];
		for (int index = 0; index < order.length; index++) order[index] = index;
		java.util.Arrays.sort(order, Comparator.<Integer, BigInteger>comparing(index -> remainders[index]).reversed()
				.thenComparingInt(Integer::intValue));
		for (long left = requested - assigned, index = 0; index < left; index++) result[order[(int) index]]++;
		return result;
	}

	public record Split(ChemicalContents inserted, ChemicalContents remaining) {
		public Split {
			if (inserted == null) throw new IllegalArgumentException("Inserted chemical contents are missing");
		}
	}

	public String encode() {
		StringBuilder encoded = new StringBuilder(ENCODING_TAG).append('|').append(registryFingerprint).append('|').append(quantaPerMol);
		for (Group group : groups) {
			encoded.append('|').append(group.materialId()).append(',').append(group.indicator() ? '1' : '0')
					.append(',').append(group.volumeNanos()).append(',')
					.append(Long.toUnsignedString(group.massUnits(), 16)).append(',')
					.append(Long.toUnsignedString(group.massResidualUnits(), 16)).append(',');
			for (int index = 0; index < group.species().size(); index++) {
				if (index > 0) encoded.append('+');
				SpeciesAmount species = group.species().get(index);
				encoded.append(species.speciesId()).append('=').append(species.amountQuanta());
			}
		}
		if (encoded.length() > MAX_ENCODED_BYTES) throw new IllegalArgumentException("Chemical contents exceed 64 KiB");
		return encoded.toString();
	}

	public static ChemicalContents decode(String encoded) {
		if (encoded == null || encoded.length() > MAX_ENCODED_BYTES) throw new IllegalArgumentException("Invalid chemical contents size");
		String[] parts = encoded.split("\\|", -1);
		if (parts.length < 4 || !parts[0].equals(ENCODING_TAG)) throw new IllegalArgumentException("Unsupported chemical contents");
		long scale;
		try { scale = Long.parseLong(parts[2]); } catch (NumberFormatException invalid) {
			throw new IllegalArgumentException("Invalid chemical quanta/mol scale", invalid);
		}
		ArrayList<Group> groups = new ArrayList<>(parts.length - 3);
		for (int index = 3; index < parts.length; index++) {
			String[] fields = parts[index].split(",", -1);
			if (fields.length != 6) throw new IllegalArgumentException("Malformed chemical contents group");
			try {
				ArrayList<SpeciesAmount> species = new ArrayList<>();
				for (String entry : fields[5].isEmpty() ? new String[0] : fields[5].split("\\+", -1)) {
					String[] pair = entry.split("=", -1);
					if (pair.length != 2) throw new IllegalArgumentException("Malformed chemical species entry");
					species.add(new SpeciesAmount(pair[0], Long.parseLong(pair[1])));
				}
				groups.add(new Group(fields[0], switch (fields[1]) { case "0" -> false; case "1" -> true;
					default -> throw new IllegalArgumentException("Invalid indicator bit"); }, Long.parseLong(fields[2]),
						Long.parseUnsignedLong(fields[3], 16), Long.parseUnsignedLong(fields[4], 16), species));
			} catch (NumberFormatException invalid) { throw new IllegalArgumentException("Malformed chemical contents number", invalid); }
		}
		ChemicalContents contents = new ChemicalContents(parts[1], scale, groups);
		if (!contents.encode().equals(encoded)) throw new IllegalArgumentException("Chemical contents are not canonical");
		return contents;
	}

	public record Group(
			String materialId, boolean indicator, long volumeNanos, long massUnits,
			long massResidualUnits, List<SpeciesAmount> species
	) {
		private static final Comparator<Group> ORDER = Comparator.comparing(Group::materialId).thenComparing(Group::indicator);
		public Group {
			if (!StableId.isValid(materialId, 128) || volumeNanos <= 0L
					|| volumeNanos > ContainerFluidLedger.NANOS_PER_BLOCK || species == null
					|| species.size() > ChemicalLimits.MAX_SPECIES_PER_PARCEL
					|| !(massUnits > 0)) {
				throw new IllegalArgumentException("Invalid chemical contents group");
			}
			ArrayList<SpeciesAmount> canonical = new ArrayList<>(species);
			canonical.sort(Comparator.comparing(SpeciesAmount::speciesId));
			if (!canonical.equals(species)) throw new IllegalArgumentException("Chemical group species are not canonical");
			Set<String> ids = new HashSet<>(); for (SpeciesAmount amount : canonical) if (!ids.add(amount.speciesId())) {
				throw new IllegalArgumentException("Duplicate chemical species");
			}
			species = List.copyOf(canonical);
		}
		public double massKilograms() { return mmm.units.Mass.kilograms(massUnits); }
		public double massResidualKilograms() { return mmm.units.Mass.kilograms(massResidualUnits); }
	}

	public record SpeciesAmount(String speciesId, long amountQuanta) {
		public SpeciesAmount {
			if (!StableId.isValid(speciesId, 128) || amountQuanta <= 0L) throw new IllegalArgumentException("Invalid chemical species amount");
		}
	}
}
