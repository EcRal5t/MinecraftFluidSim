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
		if (args.length == 1 && args[0].equals("defaults")) { validateDefaultParcels(); System.out.println("Chemical default quantity validation passed"); return; }
		boolean fast = args.length > 0 && args[0].equals("--fast");
		validateRationalsAndCodec();
		validateCanonicalRegistry();
		validateDefaultParcels();
		validateBundledDataDefinitions();
		validateAuditLedgerCodec();
		validateRegistrySnapshot();
		validateInvalidDefinitions();
		validatePhDisplayPhases();
		System.out.printf("M7 chemistry core gate passed: fingerprint=%s iterations=%d species=%d reactions=%d%n",
				mmm.fluid.TestFluidDefinitions.load().chemistry().fingerprint(), fast ? 1_000 : 10_000,
				mmm.fluid.TestFluidDefinitions.load().chemistry().species().size(),
				mmm.fluid.TestFluidDefinitions.load().chemistry().reactions().size());
	}

	private static void validatePhDisplayPhases() {
		var materials = new mmm.fluid.FluidMaterialRegistry(mmm.simulation.ProductionRegistries.palette());
		var domain = new mmm.fluid.HostFluidState(4, 4, 4,
				new mmm.fluid.FluidSolverConfig(.5F, 0, .01F, 64, 1e-6F, .002F), mmm.fluid.TestFluidDefinitions.load());
		domain.bindMaterialRegistry(materials);
		domain.setVolume(1, 1, 1, .25F);
		domain.bindChemistry(mmm.fluid.TestFluidDefinitions.load().chemistry());
		int cells = 64, cell = 21;
		int water = materials.handle(mmm.fluid.TestFluidDefinitions.load().material().entry("mmm:water").material().id());
		int oil = materials.handle(mmm.fluid.TestFluidDefinitions.load().material().entry("mmm:oil").material().id());
		int waterOffset = materials.phaseHandle(water) * cells + cell;
		int oilOffset = materials.phaseHandle(oil) * cells + cell;
		byte[] neutral = domain.copyChemicalDisplaySnapshot().copyPh();
		check(Byte.toUnsignedInt(neutral[waterOffset]) == 70
				&& Byte.toUnsignedInt(neutral[oilOffset]) == ChemicalDisplaySnapshot.PH_NO_DATA,
				"Neutral aqueous pH and absent oil did not keep distinct display codes");
		domain.setMarkerChemical(0, Map.of(BuiltinChemicalDefinitions.H_PLUS, 1_000_000L,
				BuiltinChemicalDefinitions.CL_MINUS, 1_000_000L));
		byte acid = domain.copyChemicalDisplaySnapshot().copyPh()[waterOffset];
		check(Byte.toUnsignedInt(acid) < 70, "Acidic water did not produce an acidic pH code");
		check(domain.tryInsertExactVolume(1, 1, 1, .25F, 0, oil).transferred(),
				"Oil-water display fixture could not insert its oil inventory");
		byte[] mixed = domain.copyChemicalDisplaySnapshot().copyPh();
		check(mixed[waterOffset] == acid
				&& Byte.toUnsignedInt(mixed[oilOffset]) == ChemicalDisplaySnapshot.PH_NO_DATA,
				"Oil diluted the aqueous pH denominator or acquired an aqueous pH value");

		var builtin = mmm.fluid.TestFluidDefinitions.load().chemistry();
		var omitted = java.util.Set.of(BuiltinChemicalDefinitions.H_PLUS, BuiltinChemicalDefinitions.OH_MINUS);
		var waterSpecies = builtin.species().stream().filter(value -> !omitted.contains(value.id())).toList();
		var originalPhase = builtin.phase(builtin.phaseHandleForMaterial("mmm:water"));
		var waterPhase = new ChemicalPhaseDefinition(originalPhase.id(), originalPhase.materialId(),
				originalPhase.allowedSpecies().stream().filter(value -> !omitted.contains(value)).toList(),
				originalPhase.defaultConcentrationMolPerCubicMeter());
		var saltReactions = builtin.reactions().stream().filter(value -> value.phaseId().equals(waterPhase.id())
				&& value.reactants().keySet().stream().noneMatch(omitted::contains)
				&& value.products().keySet().stream().noneMatch(omitted::contains)).toList();
		var partial = new mmm.fluid.HostFluidState(4, 4, 4, domain.config(), mmm.fluid.TestFluidDefinitions.load());
		partial.bindMaterialRegistry(new mmm.fluid.FluidMaterialRegistry(List.of(mmm.fluid.TestFluidDefinitions.load().material().entry("mmm:water").material())));
		partial.setVolume(1, 1, 1, .25F);
		partial.bindChemistry(new ChemicalRegistry(waterSpecies, List.of(waterPhase), saltReactions));
		check(Byte.toUnsignedInt(partial.copyChemicalDisplaySnapshot().copyPh()[cell]) == ChemicalDisplaySnapshot.PH_NO_DATA,
				"A registry without acid/base species acquired a neutral pH display value");
	}

	private static void validateDefaultParcels() {
		var registry = mmm.fluid.TestFluidDefinitions.load().chemistry();
		var store = new RegionChemistry(registry,1);
		var original = store.defaultRecord("mmm:water", 0.125F, 1.0F, mmm.units.SolverUnitScale.forCellSize(1.0F).massUnitsForNormalized(125.0));
		check(original == store.defaultRecord(new String("mmm:water"), 0.125F, 1.0F, mmm.units.SolverUnitScale.forCellSize(1.0F).massUnitsForNormalized(125.0)),
				"identical default inputs did not reuse immutable inventory");
		String[] materials = {"mmm:water", "mmm:water", "mmm:water", "mmm:water", "mmm:oil", "mmm:mercury"};
		for (int index = 0; index < materials.length; index++) {
			float volume = index == 0 ? Math.nextUp(0.125F) : 0.125F;
			float size = index == 1 ? Math.nextUp(1.0F) : 1.0F;
			double mass = index == 2 ? Math.nextUp(125.0) : 125.0;
			var actual = store.defaultRecord(materials[index], volume, size, mmm.units.SolverUnitScale.forCellSize(size).massUnitsForNormalized(mass));
			var fresh = new RegionChemistry(registry,1)
					.defaultRecord(materials[index], volume, size, mmm.units.SolverUnitScale.forCellSize(size).massUnitsForNormalized(mass));
			check(actual.equals(fresh), "default reuse mixed an input field at index " + index);
		}
		var unresolved = store.defaultRecord("mmm:water", Math.scalb(1.0,-100), 1.0F, 1L);
		check(unresolved.entryCount() == 0 && unresolved.massResidualUnits() == 1L,
				"Unresolved default species did not retain actual integer mass");
		var tail = store.defaultRecord("mmm:water", Float.MIN_VALUE, 1.0F, 0L);
		check(tail.entryCount() == 0 && tail.massResidualUnits() == 0,
				"Zero-mass geometric tail acquired physical composition");
		expectFailure(() -> store.defaultRecord("mmm:missing", 0.125F, 1.0F, mmm.units.SolverUnitScale.forCellSize(1.0F).massUnitsForNormalized(125.0)), "default reuse accepted an unknown material");
		expectFailure(() -> store.defaultRecord("mmm:water", Float.NaN, 1.0F, mmm.units.SolverUnitScale.forCellSize(1.0F).massUnitsForNormalized(125.0)), "default reuse accepted non-finite volume");
		expectFailure(() -> store.defaultRecord("mmm:water", 0.125F, Float.POSITIVE_INFINITY, mmm.units.SolverUnitScale.forCellSize(Float.POSITIVE_INFINITY).massUnitsForNormalized(125.0)), "default reuse accepted non-finite cell size");
		expectFailure(() -> store.defaultRecord("mmm:water", 0.125F, 1.0F, mmm.units.SolverUnitScale.forCellSize(1.0F).massUnitsForNormalized(Double.NaN)), "default reuse accepted non-finite mass");
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
		for (var n : List.of(java.math.BigInteger.valueOf(Long.MIN_VALUE), java.math.BigInteger.valueOf(Long.MAX_VALUE),
				java.math.BigInteger.ONE.shiftLeft(100).subtract(java.math.BigInteger.ONE), java.math.BigInteger.valueOf(-3).shiftLeft(255))) {
			for (var d : List.of(java.math.BigInteger.valueOf(Long.MAX_VALUE - 1), java.math.BigInteger.ONE.shiftLeft(90),
					java.math.BigInteger.ONE.shiftLeft(ChemicalLimits.MAX_RATIONAL_BITS - 1))) {
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
				+ "\"molar_mass_kg_per_mol\":\"0.018\",\"base_argb\":\"#ff4e9cff\"}";
		ChemicalSpecies decoded = ChemicalDefinitionCodec.decodeSpecies("test:h2o", new StringReader(species));
		check(decoded.molarMassKgPerMol().equals(ExactRational.parse("0.018")), "species exact mass codec changed value");
		expectFailure(() -> ChemicalDefinitionCodec.decodeSpecies("test:bad", new StringReader(species.replace("\"0.018\"", "0.018"))),
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
		ChemicalRegistry reference = mmm.fluid.TestFluidDefinitions.load().chemistry();
		List<ChemicalSpecies> species = new ArrayList<>(mmm.fluid.TestFluidDefinitions.load().chemistry().species());
		List<ChemicalPhaseDefinition> phases = new ArrayList<>(mmm.fluid.TestFluidDefinitions.load().chemistry().phases());
		List<ChemicalReaction> reactions = new ArrayList<>(mmm.fluid.TestFluidDefinitions.load().chemistry().reactions());
		Collections.reverse(species);
		Collections.rotate(phases, 1);
		Collections.reverse(reactions);
		ChemicalRegistry shuffled = new ChemicalRegistry(species, phases, reactions, mmm.fluid.TestFluidDefinitions.load().chemistry().gasLiquidMassTransfers());
		check(java.util.Arrays.equals(reference.canonicalBytes(), shuffled.canonicalBytes()),
				"registry canonical bytes depend on file order");
		check(reference.fingerprint().equals(shuffled.fingerprint()), "registry fingerprint depends on file order");
		check(reference.fingerprint().matches("[0-9a-f]{64}"), "fingerprint is not SHA-256 hex");
		ChemicalRegistry heavier = new ChemicalRegistry(reference.species().stream().map(value ->
				new ChemicalSpecies(value.id(), value.composition(), value.charge(),
						value.molarMassKgPerMol().multiply(2), value.baseArgb())).toList(),
				reference.phases(), reference.reactions());
		ChemicalMixtureRecord record = ChemicalMixtureRecord.fromStableAmounts(reference,
				Map.of(BuiltinChemicalDefinitions.H2O, 123_457L), mmm.units.Mass.fromKilograms(0.125));
		ExactRational expected = reference.species(reference.speciesHandle(BuiltinChemicalDefinitions.H2O))
				.molarMassKgPerMol().multiply(123_457L).divide(ChemicalLimits.QUANTA_PER_MOL);
		ChemicalMixtureRecord adjusted = record.withMassResidualUnits(mmm.units.Mass.fromKilograms(-0.25));
		ChemicalMixtureRecord rebuilt = ChemicalMixtureRecord.fromStableAmounts(reference,
				Map.of(BuiltinChemicalDefinitions.H2O, 123_457L), mmm.units.Mass.fromKilograms(-0.25));
		check(adjusted.equals(rebuilt) && adjusted.hashCode() == rebuilt.hashCode()
				&& adjusted.totalQuanta() == record.totalQuanta(), "residual replacement changed canonical inventory or hash");
		adjusted.copyAmounts()[0] = 1;
		check(adjusted.amountAt(0) == 123_457L && record.amountAt(0) == 123_457L,
				"residual replacement exposed mutable inventory");
		expectFailure(() -> record.withMassResidualUnits(mmm.units.Mass.fromKilograms(Double.NaN)), "residual replacement accepted non-finite mass");
		java.util.stream.IntStream.range(0, 256).parallel().forEach(index -> {
			ChemicalRegistry target = (index & 1) == 0 ? reference : heavier;
			check(adjusted.exactSpeciesMassKilograms(target).equals((index & 1) == 0 ? expected : expected.multiply(2)),
					"chemical mass memo mixed registry identity and value");
		});
		ExactRational wideMass = new ExactRational(java.math.BigInteger.ONE,
				java.math.BigInteger.ONE.shiftLeft(ChemicalLimits.MAX_RATIONAL_BITS - 30).add(java.math.BigInteger.valueOf(3)));
		expectFailure(() -> new ChemicalSpecies("test:wide", Map.of("X", 1), 0, wideMass, 0xff000000),
				"Species accepted a molar mass below the fixed-point mass resolution");
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
		var transfers = decodeDirectory(root.resolve(ChemicalDefinitionCodec.GAS_LIQUID_MASS_TRANSFERS_PATH),
				ChemicalDefinitionCodec::decodeGasLiquidMassTransfer);
		ChemicalRegistry bundled = new ChemicalRegistry(species, phases, reactions, transfers);
		check(bundled.fingerprint().equals(mmm.fluid.TestFluidDefinitions.load().chemistry().fingerprint()),
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
		ChemicalRegistry registry = mmm.fluid.TestFluidDefinitions.load().chemistry();
		ChemicalMixtureRecord mixture = ChemicalMixtureRecord.fromStableAmounts(registry,
				Map.of(BuiltinChemicalDefinitions.H2O, ChemicalLimits.QUANTA_PER_MOL), mmm.units.Mass.fromKilograms(856.0));
		ChemicalTransferLedger ledger = new ChemicalTransferLedger(List.of(new ChemicalTransferLedger.Group(
				"mmm:water", 1.0, mmm.units.Mass.fromKilograms(1_000.0),
				mixture.massResidualUnits(), mixture.stableAmounts(registry))));
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
			ChemicalRegistry definitions = mmm.fluid.TestFluidDefinitions.load().chemistry();
			java.nio.file.Path snapshot = root.resolve("chemical-registry.nbt");
			ChemicalRegistryCodec.writeSnapshot(snapshot, definitions);
			ChemicalRegistry decoded = ChemicalRegistryCodec.readSnapshot(snapshot);
			check(decoded.fingerprint().equals(definitions.fingerprint())
							&& java.util.Arrays.equals(decoded.canonicalBytes(), definitions.canonicalBytes()),
					"chemical registry snapshot did not round-trip canonically");
			check(decoded.gasLiquidMassTransfers().equals(definitions.gasLiquidMassTransfers()),
					"gas/liquid mass transfer definitions did not round-trip");
			var transfer = definitions.gasLiquidMassTransfers().getFirst();
			check(transfer.henrySolubilityAt(298.15) == 3.4e-4
					&& transfer.henrySolubilityAt(318.15) < transfer.henrySolubilityAt(298.15),
					"Henry reference or temperature direction changed");
			check(transfer.overallLiquidFilmVelocityAt(298.15, mmm.fluid.air.GasRegistry.MOLAR_GAS_CONSTANT)
					< transfer.liquidFilmVelocityMPerS(), "Series film resistance was omitted");
			check(!transfer.supportsTemperature(1) && !transfer.supportsTemperature(500)
					&& transfer.supportsTemperature(273.15) && transfer.supportsTemperature(373.15),
					"Henry law escaped its declared liquid-water fit interval");
			var differentTransfer = new GasLiquidMassTransferDefinition(transfer.id(), transfer.speciesId(),
					transfer.liquidPhaseId(), transfer.gasComponentId(), transfer.referenceTemperatureK(),
					transfer.henrySolubilityMolPerM3Pa(), transfer.temperatureSlopeK(),
					transfer.liquidFilmVelocityMPerS() * 2, transfer.gasFilmVelocityMPerS(),
					transfer.minimumTemperatureK(),transfer.maximumTemperatureK());
			var transferChanged = new ChemicalRegistry(definitions.species(), definitions.phases(), definitions.reactions(), List.of(differentTransfer));
			check(!transferChanged.fingerprint().equals(definitions.fingerprint()), "Film definition must affect the world fingerprint");
			expectFailure(() -> new ChemicalRegistry(definitions.species(), definitions.phases(), definitions.reactions(),
					List.of(new GasLiquidMassTransferDefinition("test:unknown", "test:unknown", transfer.liquidPhaseId(),
							transfer.gasComponentId(), 298.15, 3.4e-4, 2400, 1e-5, 1e-2,273.15,373.15))),
					"Mass transfer accepted an unknown species");
			check(java.util.Arrays.equals(ChemicalRegistryCodec.snapshotBytes(definitions),
							ChemicalRegistryCodec.snapshotBytes(definitions)),
					"chemical registry snapshot encoding is not deterministic");
			List<ChemicalSpecies> changedSpecies = new ArrayList<>(mmm.fluid.TestFluidDefinitions.load().chemistry().species());
			ChemicalSpecies original = changedSpecies.getFirst();
			changedSpecies.set(0, new ChemicalSpecies(original.id(), original.composition(), original.charge(),
					original.molarMassKgPerMol(), original.baseArgb() ^ 0x00010101));
			ChemicalRegistry changed = new ChemicalRegistry(
					changedSpecies, mmm.fluid.TestFluidDefinitions.load().chemistry().phases(), mmm.fluid.TestFluidDefinitions.load().chemistry().reactions(),
					mmm.fluid.TestFluidDefinitions.load().chemistry().gasLiquidMassTransfers());
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
				Map.of("test:h", ExactRational.ONE));
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
		var forward = new ChemicalReaction("test:forward", ChemicalReaction.Kind.FINITE_RATE,
				"test:phase", Map.of("test:h", 1), Map.of("test:h_alias", 1), ExactRational.ZERO,
				new ChemicalReaction.Kinetics(2, 1, 1000, 500, 298.15));
		var competitor = new ChemicalReaction("test:competitor", ChemicalReaction.Kind.INSTANTANEOUS,
				"test:phase", Map.of("test:h", 1), Map.of("test:h_alias2", 1), ExactRational.ZERO);
		var cycle = new ChemicalReaction("test:cycle", ChemicalReaction.Kind.FINITE_RATE,
				"test:phase", Map.of("test:h_alias", 1), Map.of("test:h", 1), ExactRational.ZERO,
				new ChemicalReaction.Kinetics(1, 0, 0, 0, 298.15));
		var competing = new ChemicalRegistry(all, List.of(phase), List.of(forward, competitor, cycle));
		check(competing.reactions().size() == 3, "Conservative competing/reversible network was not retained");
		expectFailure(() -> new ChemicalReaction("test:volume", ChemicalReaction.Kind.INSTANTANEOUS,
				"test:phase", Map.of("test:h", 1), Map.of("test:h_alias", 1), ExactRational.ONE),
				"nonzero reaction volume change accepted");
	}

	private static ChemicalMixtureRecord record(Map<String, Long> amounts) {
		return ChemicalMixtureRecord.fromStableAmounts(mmm.fluid.TestFluidDefinitions.load().chemistry(), amounts, 0L);
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
