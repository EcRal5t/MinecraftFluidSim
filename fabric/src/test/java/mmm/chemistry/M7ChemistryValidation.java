package mmm.chemistry;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Pure-Java M7 definition, fingerprint and conservative-reaction gate. */
public final class M7ChemistryValidation {
	private M7ChemistryValidation() { }

	public static void main(String[] args) throws Exception {
		boolean fast = args.length > 0 && args[0].equals("--fast");
		validateRationalsAndCodec();
		validateCanonicalRegistry();
		validateDefaultParcels();
		validateBundledDataDefinitions();
		validateAuditLedgerCodec();
		validateRegistrySnapshot();
		validateInvalidDefinitions();
		validateStrongElectrolytes();
		validateAcidBase();
		validateNoDrift(fast ? 1_000 : 10_000);
		System.out.printf("M7 chemistry core gate passed: fingerprint=%s iterations=%d species=%d reactions=%d%n",
				BuiltinChemicalDefinitions.REGISTRY.fingerprint(), fast ? 1_000 : 10_000,
				BuiltinChemicalDefinitions.REGISTRY.species().size(),
				BuiltinChemicalDefinitions.REGISTRY.reactions().size());
	}

	private static void validateDefaultParcels() {
		var registry = BuiltinChemicalDefinitions.REGISTRY;
		var scale = mmm.units.SolverUnitScale.forCellSize(1.0F);
		var store = new ChemicalParcelStore(registry, scale, 2, 1);
		var original = store.defaultRecord("mmm:water", 0.125F, 1.0F, 125.0, false);
		check(original == store.defaultRecord(new String("mmm:water"), 0.125F, 1.0F, 125.0, false),
				"identical default inputs did not reuse immutable inventory");
		String[] materials = {"mmm:water", "mmm:water", "mmm:water", "mmm:water", "mmm:oil", "mmm:mercury"};
		for (int index = 0; index < materials.length; index++) {
			float volume = index == 0 ? Math.nextUp(0.125F) : 0.125F;
			float size = index == 1 ? Math.nextUp(1.0F) : 1.0F;
			double mass = index == 2 ? Math.nextUp(125.0) : 125.0;
			boolean indicator = index == 3;
			var actual = store.defaultRecord(materials[index], volume, size, mass, indicator);
			var fresh = new ChemicalParcelStore(registry, scale, 1, 1)
					.defaultRecord(materials[index], volume, size, mass, indicator);
			check(actual.equals(fresh), "default reuse mixed an input field at index " + index);
		}
		check(store.tryDefaultRecord("mmm:water", Float.MIN_VALUE, 1.0F, Double.MIN_VALUE, false) == null
				&& store.tryDefaultRecord("mmm:water", 0.125F, Float.MIN_VALUE, 125.0, false) == null,
				"subnormal default volume acquired a species quantum");
		check(store.defaultRecord("mmm:water", 0.125F, 1.0F, Double.MIN_VALUE, false)
				.equals(new ChemicalParcelStore(registry, scale, 1, 1)
						.defaultRecord("mmm:water", 0.125F, 1.0F, Double.MIN_VALUE, false)),
				"subnormal mass reused a different default residual");
		expectFailure(() -> store.defaultRecord("mmm:oil", 0.125F, 1.0F, 125.0, true), "default reuse accepted a rejected indicator");
		expectFailure(() -> store.defaultRecord("mmm:missing", 0.125F, 1.0F, 125.0, false), "default reuse accepted an unknown material");
		expectFailure(() -> store.defaultRecord("mmm:water", Float.NaN, 1.0F, 125.0, false), "default reuse accepted non-finite volume");
		expectFailure(() -> store.defaultRecord("mmm:water", 0.125F, Float.POSITIVE_INFINITY, 125.0, false), "default reuse accepted non-finite cell size");
		expectFailure(() -> store.defaultRecord("mmm:water", 0.125F, 1.0F, Double.NaN, false), "default reuse accepted non-finite mass");
		store.assignDefault(0, "mmm:water", 20_000.0F, 1.0F, 20_000_000.0, false);
		long inventory = store.totalQuanta(), revision = store.mutationRevision();
		expectFailure(() -> store.assignDefault(1, "mmm:water", 20_000.0F, 1.0F, 20_000_000.0, false),
				"default reuse bypassed the region inventory limit");
		check(store.totalQuanta() == inventory && store.mutationRevision() == revision && !store.isAssigned(1),
				"rejected default assignment changed inventory ownership");
	}

	private static void validateRationalsAndCodec() {
		validateRationalRounding();
		validateRationalAdditionBudget();
		check(ExactRational.parse("1.25").canonical().equals("5/4"), "finite decimal did not canonicalize exactly");
		check(ExactRational.parse("10/8").canonical().equals("5/4"), "fraction did not reduce");
		for (int numerator = -24; numerator <= 24; numerator++) {
			for (int denominator = -24; denominator <= 24; denominator++) {
				if (denominator == 0) continue;
				var n = java.math.BigInteger.valueOf(numerator);
				var d = java.math.BigInteger.valueOf(denominator);
				var divisor = n.gcd(d).multiply(java.math.BigInteger.valueOf(d.signum()));
				var rational = new ExactRational(n, d);
				check(rational.numerator().equals(n.divide(divisor))
						&& rational.denominator().equals(d.divide(divisor)), "rational canonical form changed");
			}
		}
		for (var n : List.of(java.math.BigInteger.valueOf(Long.MIN_VALUE), java.math.BigInteger.valueOf(Long.MAX_VALUE), java.math.BigInteger.ONE.shiftLeft(100).subtract(java.math.BigInteger.ONE))) {
			for (var d : List.of(java.math.BigInteger.valueOf(Long.MAX_VALUE - 1), java.math.BigInteger.ONE.shiftLeft(90))) {
				var divisor = n.gcd(d); var rational = new ExactRational(n, d);
				check(rational.numerator().equals(n.divide(divisor)) && rational.denominator().equals(d.divide(divisor)),
						"integer rational reduction or wide fallback changed its canonical result");
			}
		}
		expectFailure(() -> ExactRational.parse("0/0"), "zero fast path accepted zero denominator");
		expectFailure(() -> new ExactRational(java.math.BigInteger.ZERO,
				java.math.BigInteger.ONE.shiftLeft(ChemicalLimits.MAX_RATIONAL_BITS)),
				"zero fast path bypassed rational magnitude budget");
		String species = "{\"composition\":{\"H\":\"2\",\"O\":\"1\"},\"charge\":\"0\","
				+ "\"mass_per_au\":\"18\",\"base_argb\":\"#ff4e9cff\"}";
		ChemicalSpecies decoded = ChemicalDefinitionCodec.decodeSpecies("test:h2o", new StringReader(species));
		check(decoded.massKilogramsPerAu().equals(ExactRational.of(18)), "species exact mass codec changed value");
		expectFailure(() -> ChemicalDefinitionCodec.decodeSpecies("test:bad", new StringReader(species.replace("\"18\"", "18"))),
				"JSON number bypassed exact rational string codec");
		expectFailure(() -> ExactRational.parse("1/0"), "zero denominator accepted");
		expectFailure(() -> ExactRational.parse("1e-500000"), "unbounded decimal scale was accepted");
		expectFailure(() -> ExactRational.parse("9".repeat(ChemicalLimits.MAX_RATIONAL_TEXT_LENGTH + 1)),
				"unbounded rational text was accepted");
	}

	private static void validateRationalAdditionBudget() {
		var one = java.math.BigInteger.ONE;
		var common = one.shiftLeft(ChemicalLimits.MAX_RATIONAL_BITS - 3);
		var firstDenominator = common.multiply(java.math.BigInteger.TWO);
		var secondDenominator = common.multiply(java.math.BigInteger.valueOf(3));
		var totalDenominator = common.multiply(java.math.BigInteger.valueOf(6));
		check(new ExactRational(one, firstDenominator).add(new ExactRational(one, secondDenominator))
				.equals(new ExactRational(java.math.BigInteger.valueOf(5), totalDenominator)),
				"legal unequal-denominator sum rejected its over-budget intermediate product");
		var cancelled = new ExactRational(firstDenominator.subtract(one), firstDenominator)
				.add(new ExactRational(one.subtract(secondDenominator), secondDenominator));
		check(cancelled.equals(new ExactRational(one.negate(), totalDenominator))
				&& cancelled.add(new ExactRational(one, totalDenominator)).equals(ExactRational.ZERO),
				"wide opposing fractions lost exact cancellation before the final budget check");
		var maximumDenominator = one.shiftLeft(ChemicalLimits.MAX_RATIONAL_BITS - 1);
		var nearOne = new ExactRational(maximumDenominator.subtract(one), maximumDenominator);
		check(nearOne.add(nearOne).equals(new ExactRational(maximumDenominator.subtract(one), maximumDenominator.shiftRight(1))),
				"equal-denominator sum checked its numerator budget before reduction");
		expectFailure(() -> new ExactRational(one, maximumDenominator)
				.add(new ExactRational(one, maximumDenominator.subtract(one))),
				"canonical addition result bypassed the rational magnitude budget");
	}

	private static void validateRationalRounding() {
		var one = java.math.BigInteger.ONE;
		var wide = one.shiftLeft(2000);
		check(new ExactRational(wide.add(one), wide.add(java.math.BigInteger.valueOf(3))).toDouble() == 1.0,
				"wide finite rational became non-finite during operand conversion");
		for (int sign : new int[] {1, -1}) {
			var signed = java.math.BigInteger.valueOf(sign);
			check(Double.doubleToRawLongBits(new ExactRational(signed, one.shiftLeft(1075)).toDouble())
					== Double.doubleToRawLongBits(sign < 0 ? -0.0 : 0.0), "subnormal halfway did not round to even zero");
			check(new ExactRational(signed.multiply(java.math.BigInteger.valueOf(3)), one.shiftLeft(1075)).toDouble()
					== sign * 2 * Double.MIN_VALUE, "subnormal odd halfway did not round to even");
			check(new ExactRational(one.shiftLeft(53).subtract(one).multiply(signed), one.shiftLeft(1075)).toDouble()
					== sign * Double.MIN_NORMAL, "subnormal-normal halfway did not round to even");
			check(new ExactRational(one.shiftLeft(53).add(one).multiply(signed), one.shiftLeft(53)).toDouble()
					== sign, "normal even halfway did not round to even");
			check(new ExactRational(one.shiftLeft(53).add(java.math.BigInteger.valueOf(3)).multiply(signed), one.shiftLeft(53)).toDouble()
					== sign * Math.nextUp(Math.nextUp(1.0)), "normal odd halfway did not round to even");
			var overflowMidpoint = one.shiftLeft(54).subtract(one).shiftLeft(970);
			check(new ExactRational(overflowMidpoint.subtract(one).multiply(signed), one).toDouble() == sign * Double.MAX_VALUE,
					"finite rational below overflow halfway overflowed");
			check(new ExactRational(overflowMidpoint.multiply(signed), one).toDouble()
					== (sign < 0 ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY), "overflow halfway did not round to even");
		}
		var maximumBudget = one.shiftLeft(ChemicalLimits.MAX_RATIONAL_BITS - 1);
		check(new ExactRational(maximumBudget.add(one), maximumBudget.add(java.math.BigInteger.valueOf(3))).toDouble() == 1.0,
				"budget-valid quotient failed final binary64 conversion");
	}

	private static void validateCanonicalRegistry() {
		ChemicalRegistry reference = BuiltinChemicalDefinitions.REGISTRY;
		List<ChemicalSpecies> species = new ArrayList<>(BuiltinChemicalDefinitions.species());
		List<ChemicalPhaseDefinition> phases = new ArrayList<>(BuiltinChemicalDefinitions.phases());
		List<ChemicalReaction> reactions = new ArrayList<>(BuiltinChemicalDefinitions.reactions());
		Collections.reverse(species);
		Collections.rotate(phases, 1);
		Collections.reverse(reactions);
		ChemicalRegistry shuffled = new ChemicalRegistry(species, phases, reactions);
		check(java.util.Arrays.equals(reference.canonicalBytes(), shuffled.canonicalBytes()),
				"registry canonical bytes depend on file order");
		check(reference.fingerprint().equals(shuffled.fingerprint()), "registry fingerprint depends on file order");
		check(reference.fingerprint().matches("[0-9a-f]{64}"), "fingerprint is not SHA-256 hex");
		ChemicalRegistry heavier = new ChemicalRegistry(reference.species().stream().map(value ->
				new ChemicalSpecies(value.id(), value.composition(), value.charge(),
						value.massKilogramsPerAu().multiply(2), value.baseArgb())).toList(),
				reference.phases(), reference.reactions());
		ChemicalMixtureRecord record = ChemicalMixtureRecord.fromStableAmounts(reference,
				Map.of(BuiltinChemicalDefinitions.H2O, 123_457L), 0.125);
		ExactRational expected = reference.species(reference.speciesHandle(BuiltinChemicalDefinitions.H2O))
				.massKilogramsPerAu().multiply(123_457L).divide(ChemicalLimits.QUANTA_PER_AU);
		ChemicalMixtureRecord adjusted = record.withMassResidualKilograms(-0.25);
		ChemicalMixtureRecord rebuilt = ChemicalMixtureRecord.fromStableAmounts(reference,
				Map.of(BuiltinChemicalDefinitions.H2O, 123_457L), -0.25);
		check(adjusted.equals(rebuilt) && adjusted.hashCode() == rebuilt.hashCode()
				&& adjusted.totalQuanta() == record.totalQuanta(), "residual replacement changed canonical inventory or hash");
		adjusted.copyAmounts()[0] = 1;
		check(adjusted.amountAt(0) == 123_457L && record.amountAt(0) == 123_457L,
				"residual replacement exposed mutable inventory");
		expectFailure(() -> record.withMassResidualKilograms(Double.NaN), "residual replacement accepted non-finite mass");
		java.util.stream.IntStream.range(0, 256).parallel().forEach(index -> {
			ChemicalRegistry target = (index & 1) == 0 ? reference : heavier;
			check(adjusted.exactSpeciesMassKilograms(target).equals((index & 1) == 0 ? expected : expected.multiply(2)),
					"chemical mass memo mixed registry identity and value");
		});
		ExactRational wideMass = new ExactRational(java.math.BigInteger.ONE,
				java.math.BigInteger.ONE.shiftLeft(ChemicalLimits.MAX_RATIONAL_BITS - 30).add(java.math.BigInteger.valueOf(3)));
		var wideSpecies = new ChemicalSpecies("test:wide", Map.of("X", 1), 0, wideMass, 0xff000000);
		var wideDefinitions = new ArrayList<>(reference.species()); wideDefinitions.add(wideSpecies);
		var wideRegistry = new ChemicalRegistry(wideDefinitions, reference.phases(), reference.reactions());
		check(ChemicalMixtureRecord.fromStableAmounts(wideRegistry, Map.of(wideSpecies.id(), ChemicalLimits.QUANTA_PER_AU), 0)
				.exactSpeciesMassKilograms(wideRegistry).equals(wideMass), "mass-factor cache rejected a valid wide rational definition");
	}

	private static void validateBundledDataDefinitions() throws Exception {
		var rootUrl = M7ChemistryValidation.class.getClassLoader().getResource("data/mmm");
		check(rootUrl != null && rootUrl.getProtocol().equals("file"), "bundled chemical resource root is missing");
		java.nio.file.Path root = java.nio.file.Path.of(rootUrl.toURI());
		List<ChemicalSpecies> species = decodeDirectory(root.resolve(ChemicalDefinitionCodec.SPECIES_PATH),
				ChemicalDefinitionCodec::decodeSpecies);
		List<ChemicalPhaseDefinition> phases = decodeDirectory(root.resolve(ChemicalDefinitionCodec.PHASES_PATH),
				ChemicalDefinitionCodec::decodePhase);
		List<ChemicalReaction> reactions = decodeDirectory(root.resolve(ChemicalDefinitionCodec.REACTIONS_PATH),
				ChemicalDefinitionCodec::decodeReaction);
		ChemicalRegistry bundled = new ChemicalRegistry(species, phases, reactions);
		check(bundled.fingerprint().equals(BuiltinChemicalDefinitions.REGISTRY.fingerprint()),
				"bundled datapack definitions differ from the frozen registry");
	}

	private static <T> List<T> decodeDirectory(java.nio.file.Path directory, FileDecoder<T> decoder) throws Exception {
		ArrayList<T> result = new ArrayList<>();
		try (var files = java.nio.file.Files.walk(directory)) {
			for (java.nio.file.Path file : files.filter(path -> path.toString().endsWith(".json")).sorted().toList()) {
				String relative = directory.relativize(file).toString().replace('\\', '/');
				String id = "mmm:" + relative.substring(0, relative.length() - 5);
				try (var reader = java.nio.file.Files.newBufferedReader(file)) { result.add(decoder.decode(id, reader)); }
			}
		}
		return List.copyOf(result);
	}

	private static void validateAuditLedgerCodec() throws Exception {
		ChemicalRegistry registry = BuiltinChemicalDefinitions.REGISTRY;
		ChemicalMixtureRecord mixture = ChemicalMixtureRecord.fromStableAmounts(registry,
				Map.of(BuiltinChemicalDefinitions.H2O, ChemicalLimits.QUANTA_PER_AU), 856.0);
		ChemicalTransferLedger ledger = new ChemicalTransferLedger(List.of(new ChemicalTransferLedger.Group(
				"mmm:water", true, 1.0, 1_000.0,
				mixture.massResidualKilogramsBits(), mixture.stableAmounts(registry))));
		byte[] encoded = ChemicalAuditLedgerCodec.encode(ledger, ChemicalTransferLedger.EMPTY);
		ChemicalAuditLedgerCodec.AuditLedgers decoded = ChemicalAuditLedgerCodec.decode(encoded);
		check(decoded.externalSource().groups().equals(ledger.groups()) && decoded.escapedSink().isEmpty(),
				"chemical audit ledger did not round-trip canonically");
		byte[] truncated = java.util.Arrays.copyOf(encoded, encoded.length - 1);
		try {
			ChemicalAuditLedgerCodec.decode(truncated);
			throw new AssertionError("Truncated chemical audit payload was accepted");
		} catch (java.io.IOException expected) { }
	}

	@FunctionalInterface
	private interface FileDecoder<T> { T decode(String stableId, java.io.Reader reader); }

	private static void validateRegistrySnapshot() throws Exception {
		java.nio.file.Path root = java.nio.file.Files.createTempDirectory("mmm-chemistry-registry-");
		try {
			ChemicalRegistry definitions = BuiltinChemicalDefinitions.REGISTRY;
			java.nio.file.Path snapshot = root.resolve("chemical-registry.nbt");
			ChemicalRegistryCodec.writeSnapshot(snapshot, definitions);
			ChemicalRegistry decoded = ChemicalRegistryCodec.readSnapshot(snapshot);
			check(decoded.fingerprint().equals(definitions.fingerprint())
							&& java.util.Arrays.equals(decoded.canonicalBytes(), definitions.canonicalBytes()),
					"chemical registry snapshot did not round-trip canonically");
			check(java.util.Arrays.equals(ChemicalRegistryCodec.snapshotBytes(definitions),
							ChemicalRegistryCodec.snapshotBytes(definitions)),
					"chemical registry snapshot encoding is not deterministic");
			List<ChemicalSpecies> changedSpecies = new ArrayList<>(BuiltinChemicalDefinitions.species());
			ChemicalSpecies original = changedSpecies.getFirst();
			changedSpecies.set(0, new ChemicalSpecies(original.id(), original.composition(), original.charge(),
					original.massKilogramsPerAu(), original.baseArgb() ^ 0x00010101));
			ChemicalRegistry changed = new ChemicalRegistry(
					changedSpecies, BuiltinChemicalDefinitions.phases(), BuiltinChemicalDefinitions.reactions());
			check(!changed.fingerprint().equals(definitions.fingerprint()),
					"changed species colour must change the fingerprint");
			check(!java.util.Arrays.equals(ChemicalRegistryCodec.snapshotBytes(changed),
							ChemicalRegistryCodec.snapshotBytes(definitions)),
					"changed definitions produced identical snapshot bytes");
			var registryTag = net.minecraft.nbt.NbtIo.readCompressed(snapshot,
					net.minecraft.nbt.NbtAccounter.create(4L * 1024L * 1024L));
			registryTag.putString("UnknownField", "must-lock");
			net.minecraft.nbt.NbtIo.writeCompressed(registryTag, snapshot);
			try {
				ChemicalRegistryCodec.readSnapshot(snapshot);
				throw new AssertionError("unknown chemical registry field was ignored");
			} catch (java.io.IOException expected) { }
			registryTag.remove("UnknownField");
			net.minecraft.nbt.NbtIo.writeCompressed(registryTag, snapshot);
			ChemicalRegistryCodec.writeSnapshot(snapshot, changed);
			registryTag = net.minecraft.nbt.NbtIo.readCompressed(snapshot,
					net.minecraft.nbt.NbtAccounter.create(4L * 1024L * 1024L));
			registryTag.putString("Fingerprint", definitions.fingerprint());
			net.minecraft.nbt.NbtIo.writeCompressed(registryTag, snapshot);
			expectFailure(() -> {
				try { ChemicalRegistryCodec.readSnapshot(snapshot); }
				catch (java.io.IOException expected) { throw new RuntimeException(expected); }
			}, "chemical registry whose content does not match its fingerprint was accepted");
		} finally {
			try (var paths = java.nio.file.Files.walk(root)) {
				for (java.nio.file.Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
					java.nio.file.Files.deleteIfExists(path);
				}
			}
		}
	}

	private static void validateInvalidDefinitions() {
		ChemicalSpecies h = new ChemicalSpecies("test:h", Map.of("H", 1), 0, ExactRational.ONE, 0xff000000);
		ChemicalSpecies o = new ChemicalSpecies("test:o", Map.of("O", 1), 0, ExactRational.ONE, 0xff000000);
		ChemicalSpecies hAlias = new ChemicalSpecies("test:h_alias", Map.of("H", 1), 0, ExactRational.ONE, 0xff000000);
		ChemicalSpecies hAlias2 = new ChemicalSpecies("test:h_alias2", Map.of("H", 1), 0, ExactRational.ONE, 0xff000000);
		ChemicalSpecies chargedH = new ChemicalSpecies("test:charged_h", Map.of("H", 1), 1, ExactRational.ONE, 0xff000000);
		ChemicalSpecies heavyH = new ChemicalSpecies("test:heavy_h", Map.of("H", 1), 0, ExactRational.of(2), 0xff000000);
		List<ChemicalSpecies> all = List.of(h, o, hAlias, hAlias2, chargedH, heavyH);
		ChemicalPhaseDefinition phase = new ChemicalPhaseDefinition("test:phase", "test:material",
				List.of("test:h", "test:o", "test:h_alias", "test:h_alias2", "test:charged_h", "test:heavy_h"),
				Map.of("test:h", ExactRational.ONE), false);
		expectFailure(() -> new ChemicalRegistry(List.of(h, h), List.of(phase), List.of()), "duplicate species accepted");
		ChemicalReaction unbalanced = new ChemicalReaction("test:bad", ChemicalReaction.Kind.INSTANTANEOUS,
				"test:phase", Map.of("test:h", 1), Map.of("test:o", 1), ExactRational.ZERO);
		expectFailure(() -> new ChemicalRegistry(all, List.of(phase), List.of(unbalanced)),
				"unbalanced reaction accepted");
		ChemicalReaction badCharge = new ChemicalReaction("test:bad_charge", ChemicalReaction.Kind.INSTANTANEOUS,
				"test:phase", Map.of("test:h", 1), Map.of("test:charged_h", 1), ExactRational.ZERO);
		expectFailure(() -> new ChemicalRegistry(all, List.of(phase), List.of(badCharge)),
				"charge-unbalanced reaction accepted");
		ChemicalReaction badMass = new ChemicalReaction("test:bad_mass", ChemicalReaction.Kind.INSTANTANEOUS,
				"test:phase", Map.of("test:h", 1), Map.of("test:heavy_h", 1), ExactRational.ZERO);
		expectFailure(() -> new ChemicalRegistry(all, List.of(phase), List.of(badMass)),
				"mass-unbalanced reaction accepted");
		ChemicalReaction self = new ChemicalReaction("test:self", ChemicalReaction.Kind.INSTANTANEOUS,
				"test:phase", Map.of("test:h", 1), Map.of("test:h", 1), ExactRational.ZERO);
		expectFailure(() -> new ChemicalRegistry(all, List.of(phase), List.of(self)),
				"direct non-terminating reaction accepted");
		ChemicalReaction consumesA = new ChemicalReaction("test:consume_a", ChemicalReaction.Kind.INSTANTANEOUS,
				"test:phase", Map.of("test:h", 1), Map.of("test:h_alias", 1), ExactRational.ZERO);
		ChemicalReaction consumesB = new ChemicalReaction("test:consume_b", ChemicalReaction.Kind.INSTANTANEOUS,
				"test:phase", Map.of("test:h", 1), Map.of("test:h_alias2", 1), ExactRational.ZERO);
		expectFailure(() -> new ChemicalRegistry(all, List.of(phase),
				List.of(consumesA, consumesB)), "competing instantaneous consumers accepted");
		expectFailure(() -> new ChemicalReaction("test:volume", ChemicalReaction.Kind.INSTANTANEOUS,
				"test:phase", Map.of("test:h", 1), Map.of("test:h_alias", 1), ExactRational.ONE),
				"nonzero reaction volume change accepted");
		ChemicalReaction a = new ChemicalReaction("test:a", ChemicalReaction.Kind.COMPLETE_DISSOCIATION,
				"test:phase", Map.of("test:h", 1), Map.of("test:h_alias", 1), ExactRational.ZERO);
		ChemicalReaction b = new ChemicalReaction("test:b", ChemicalReaction.Kind.COMPLETE_DISSOCIATION,
				"test:phase", Map.of("test:h_alias", 1), Map.of("test:h", 1), ExactRational.ZERO);
		expectFailure(() -> new ChemicalRegistry(all, List.of(phase), List.of(a, b)),
				"dissociation cycle accepted");

		ChemicalReaction crossDissociation = new ChemicalReaction("test:cross_dissociation",
				ChemicalReaction.Kind.COMPLETE_DISSOCIATION, "test:phase",
				Map.of("test:h", 1), Map.of("test:h_alias", 1), ExactRational.ZERO);
		ChemicalReaction crossInstantaneous = new ChemicalReaction("test:cross_instantaneous",
				ChemicalReaction.Kind.INSTANTANEOUS, "test:phase",
				Map.of("test:h", 1), Map.of("test:h_alias2", 1), ExactRational.ZERO);
		expectFailure(() -> new ChemicalRegistry(all, List.of(phase),
				List.of(crossDissociation, crossInstantaneous)),
				"cross-stratum competing consumers were accepted");

		ChemicalReaction feedsDissociation = new ChemicalReaction("test:feeds_dissociation",
				ChemicalReaction.Kind.COMPLETE_DISSOCIATION, "test:phase",
				Map.of("test:h_alias", 1), Map.of("test:h_alias2", 1), ExactRational.ZERO);
		expectFailure(() -> new ChemicalRegistry(all, List.of(phase),
				List.of(consumesA, feedsDissociation)),
				"instantaneous product feeding the completed dissociation stratum was accepted");

		ChemicalRegistry stratified = new ChemicalRegistry(all, List.of(phase), List.of(
				new ChemicalReaction("test:z_dissociation", ChemicalReaction.Kind.COMPLETE_DISSOCIATION,
						"test:phase", Map.of("test:h", 1), Map.of("test:h_alias", 1), ExactRational.ZERO),
				new ChemicalReaction("test:a_instantaneous", ChemicalReaction.Kind.INSTANTANEOUS,
						"test:phase", Map.of("test:h_alias", 1), Map.of("test:h_alias2", 1), ExactRational.ZERO)));
		check(stratified.reactionOrder().get(0).kind() == ChemicalReaction.Kind.COMPLETE_DISSOCIATION
				&& stratified.reactionOrder().get(1).kind() == ChemicalReaction.Kind.INSTANTANEOUS,
				"reaction order did not keep complete dissociation ahead of instantaneous reactions");
	}

	private static void validateStrongElectrolytes() {
		ChemicalRegistry registry = BuiltinChemicalDefinitions.REGISTRY;
		ChemicalReactionEngine engine = new ChemicalReactionEngine(registry);
		int phase = registry.phaseHandle(BuiltinChemicalDefinitions.AQUEOUS);
		ChemicalMixtureRecord salts = record(Map.of(
				BuiltinChemicalDefinitions.NACL, 4_000_000L,
				BuiltinChemicalDefinitions.KNO3, 6_000_000L));
		ChemicalMixtureRecord result = engine.react(phase, salts).mixture();
		check(result.amount(registry.speciesHandle(BuiltinChemicalDefinitions.NACL)) == 0L, "NaCl did not fully dissociate");
		check(result.amount(registry.speciesHandle(BuiltinChemicalDefinitions.KNO3)) == 0L, "KNO3 did not fully dissociate");
		check(result.amount(registry.speciesHandle(BuiltinChemicalDefinitions.NA_PLUS)) == 4_000_000L, "Na inventory drifted");
		check(result.amount(registry.speciesHandle(BuiltinChemicalDefinitions.K_PLUS)) == 6_000_000L, "K inventory drifted");
		check(result.amount(registry.speciesHandle(BuiltinChemicalDefinitions.CL_MINUS)) == 4_000_000L, "Cl inventory drifted");
		check(result.amount(registry.speciesHandle(BuiltinChemicalDefinitions.NO3_MINUS)) == 6_000_000L, "nitrate inventory drifted");
		check(salts.exactSpeciesMassKilograms(registry).equals(result.exactSpeciesMassKilograms(registry)), "salt dissociation changed exact mass");
		check(salts.chargeQuanta(registry) == result.chargeQuanta(registry), "salt dissociation changed charge");

		ChemicalMixtureRecord firstPair = engine.react(phase, record(Map.of(
				BuiltinChemicalDefinitions.NACL, 5_000_003L,
				BuiltinChemicalDefinitions.KNO3, 5_000_003L))).mixture();
		ChemicalMixtureRecord secondPair = engine.react(phase, record(Map.of(
				BuiltinChemicalDefinitions.NANO3, 5_000_003L,
				BuiltinChemicalDefinitions.KCL, 5_000_003L))).mixture();
		check(firstPair.equals(secondPair),
				"equivalent NaCl+KNO3 and NaNO3+KCl inventories did not canonicalize identically");
	}

	private static void validateAcidBase() {
		ChemicalRegistry registry = BuiltinChemicalDefinitions.REGISTRY;
		ChemicalReactionEngine engine = new ChemicalReactionEngine(registry);
		int phase = registry.phaseHandle(BuiltinChemicalDefinitions.AQUEOUS);
		ChemicalMixtureRecord equal = engine.react(phase, record(Map.of(
				BuiltinChemicalDefinitions.HCL, 10_000L, BuiltinChemicalDefinitions.NAOH, 10_000L))).mixture();
		check(equal.amount(registry.speciesHandle(BuiltinChemicalDefinitions.H_PLUS)) == 0L, "equal acid left H+");
		check(equal.amount(registry.speciesHandle(BuiltinChemicalDefinitions.OH_MINUS)) == 0L, "equal base left OH-");
		check(engine.ph(equal) == 7.0, "equal acid/base is not neutral");
		ChemicalMixtureRecord acid = engine.react(phase, record(Map.of(
				BuiltinChemicalDefinitions.HCL, 15_000L, BuiltinChemicalDefinitions.NAOH, 10_000L))).mixture();
		check(acid.amount(registry.speciesHandle(BuiltinChemicalDefinitions.H_PLUS)) == 5_000L, "acid excess is wrong");
		check(engine.ph(acid) < 7.0, "acid excess pH is not acidic");
		ChemicalMixtureRecord base = engine.react(phase, record(Map.of(
				BuiltinChemicalDefinitions.HCL, 10_000L, BuiltinChemicalDefinitions.NAOH, 15_000L))).mixture();
		check(base.amount(registry.speciesHandle(BuiltinChemicalDefinitions.OH_MINUS)) == 5_000L, "base excess is wrong");
		check(engine.ph(base) > 7.0, "base excess pH is not basic");
	}

	private static void validateNoDrift(int iterations) {
		ChemicalRegistry registry = BuiltinChemicalDefinitions.REGISTRY;
		ChemicalReactionEngine engine = new ChemicalReactionEngine(registry);
		int phase = registry.phaseHandle(BuiltinChemicalDefinitions.AQUEOUS);
		ChemicalMixtureRecord input = record(Map.of(
				BuiltinChemicalDefinitions.HCL, 1_000_003L,
				BuiltinChemicalDefinitions.NAOH, 1_000_000L,
				BuiltinChemicalDefinitions.NANO3, 123_457L));
		ChemicalMixtureRecord expected = engine.react(phase, input).mixture();
		for (int i = 0; i < iterations; i++) {
			ChemicalMixtureRecord actual = engine.react(phase, expected).mixture();
			check(actual.equals(expected), "reaction/canonicalization drift at iteration " + i);
		}
	}

	private static ChemicalMixtureRecord record(Map<String, Long> amounts) {
		return ChemicalMixtureRecord.fromStableAmounts(BuiltinChemicalDefinitions.REGISTRY, amounts, 0.0);
	}

	private static void expectFailure(Runnable action, String message) {
		try { action.run(); }
		catch (RuntimeException expected) { return; }
		throw new AssertionError(message);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
