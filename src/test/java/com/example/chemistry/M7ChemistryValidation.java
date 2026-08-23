package com.example.chemistry;

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

	private static void validateRationalsAndCodec() {
		check(ExactRational.parse("1.25").canonical().equals("5/4"), "finite decimal did not canonicalize exactly");
		check(ExactRational.parse("10/8").canonical().equals("5/4"), "fraction did not reduce");
		String species = "{\"composition\":{\"H\":\"2\",\"O\":\"1\"},\"charge\":\"0\","
				+ "\"mass_per_au\":\"144\",\"base_argb\":\"#ff4e9cff\"}";
		ChemicalSpecies decoded = ChemicalDefinitionCodec.decodeSpecies("test:h2o", new StringReader(species));
		check(decoded.massPerAu().equals(ExactRational.of(144)), "species exact mass codec changed value");
		expectFailure(() -> ChemicalDefinitionCodec.decodeSpecies("test:bad", new StringReader(species.replace("\"144\"", "144"))),
				"JSON number bypassed exact rational string codec");
		expectFailure(() -> ExactRational.parse("1/0"), "zero denominator accepted");
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
	}

	private static void validateBundledDataDefinitions() throws Exception {
		var rootUrl = M7ChemistryValidation.class.getClassLoader().getResource("data/realwater");
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
				String id = "realwater:" + relative.substring(0, relative.length() - 5);
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
				"realwater:water", true, 1.0, 1_000.0, mixture.massResidualBits(), mixture.stableAmounts(registry))));
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
		java.nio.file.Path root = java.nio.file.Files.createTempDirectory("realwater-chemistry-registry-");
		try {
			ChemicalRegistryStore store = new ChemicalRegistryStore(root);
			ChemicalRegistryStore.LoadResult first = store.loadOrCreate(BuiltinChemicalDefinitions.REGISTRY);
			check(first.created() && !first.definitionMismatch(), "first registry snapshot was not atomically published");
			ChemicalRegistryStore.LoadResult second = store.loadOrCreate(BuiltinChemicalDefinitions.REGISTRY);
			check(!second.created() && !second.definitionMismatch(), "stable registry snapshot reported mismatch");
			List<ChemicalSpecies> changedSpecies = new ArrayList<>(BuiltinChemicalDefinitions.species());
			ChemicalSpecies original = changedSpecies.getFirst();
			changedSpecies.set(0, new ChemicalSpecies(original.id(), original.composition(), original.charge(),
					original.massPerAu(), original.baseArgb() ^ 0x00010101));
			ChemicalRegistry changed = new ChemicalRegistry(
					changedSpecies, BuiltinChemicalDefinitions.phases(), BuiltinChemicalDefinitions.reactions());
			ChemicalRegistryStore.LoadResult mismatch = store.loadOrCreate(changed);
			check(mismatch.definitionMismatch(), "data definition change did not report snapshot mismatch");
			check(mismatch.registry().fingerprint().equals(BuiltinChemicalDefinitions.REGISTRY.fingerprint()),
					"data definition change reinterpreted authoritative inventory");
			java.nio.file.Files.writeString(root.resolve("realwater").resolve("chemistry").resolve("foreign.bin"), "foreign");
			expectFailure(() -> {
				try { store.read(); } catch (java.io.IOException expected) { throw new RuntimeException(expected); }
			}, "unknown registry store content was ignored");
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
		check(salts.exactSpeciesMass(registry).equals(result.exactSpeciesMass(registry)), "salt dissociation changed exact mass");
		check(salts.chargeQuanta(registry) == result.chargeQuanta(registry), "salt dissociation changed charge");
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
