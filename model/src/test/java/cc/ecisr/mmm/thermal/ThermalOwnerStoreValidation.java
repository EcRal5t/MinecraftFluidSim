package cc.ecisr.mmm.thermal;

import cc.ecisr.mmm.units.SignedDyadic;
import cc.ecisr.mmm.chemistry.*;

import java.util.List;
import java.util.Map;

/**
 * Component-level invariants of the thermal owner stores. The stores' incremental mutators
 * are package-private to the thermal state owners, so these checks live in the thermal test
 * package and drive scratch stores directly.
 */
public final class ThermalOwnerStoreValidation {
	private ThermalOwnerStoreValidation() { }

	public static void main(String[] args) {
		validate();
		System.out.println("Thermal owner-store validation passed");
	}

	public static void validate() {
		coherentPhaseMergeSplitConservesInventories();
		granularColumnTransactionsConserveEnergy();
		liquidTemperatureTracksMutableInventory();
		referenceTermsKeepCanonicalRationalRounding();
		exactBinaryObservationsRoundOnce();
	}

	private static void exactBinaryObservationsRoundOnce() {
		var one = SignedDyadic.of(1);
		var tiny = SignedDyadic.of(1, -2000);
		require(one.add(tiny).toDouble() == 1 && one.add(tiny).negate().toDouble() == -1,
				"A wide finite inventory overflowed before exponent scaling");
		var aboveDoubleTie = one.add(SignedDyadic.of(1, -53)).add(tiny);
		require(aboveDoubleTie.toDouble() == Math.nextUp(1.0), "Wide binary64 midpoint rounded twice");
		var aboveFloatTie = one.add(SignedDyadic.of(1, -24)).add(tiny);
		require(aboveFloatTie.toFloat() == Math.nextUp(1.0F), "Binary32 observation rounded through binary64");
		var minimumTie = SignedDyadic.of(1, -1075);
		require(Double.doubleToRawLongBits(minimumTie.toDouble()) == 0
				&& Double.doubleToRawLongBits(minimumTie.negate().toDouble()) == Long.MIN_VALUE
				&& minimumTie.add(tiny).toDouble() == Double.MIN_VALUE,
				"Subnormal binary64 midpoint lost its sign or remainder");
		require(SignedDyadic.of(1, -150).toFloat() == 0
				&& SignedDyadic.of(1, -150).add(tiny).toFloat() == Float.MIN_VALUE,
				"Subnormal binary32 midpoint lost its remainder");
		require(SignedDyadic.of(Double.MAX_VALUE).add(SignedDyadic.of(1, 970)).toDouble() == Double.POSITIVE_INFINITY
				&& SignedDyadic.of(Float.MAX_VALUE).add(SignedDyadic.of(1, 103)).toFloat() == Float.POSITIVE_INFINITY,
				"Overflow midpoint did not round to the adjacent power of two");
		var random = new java.util.Random(710381L);
		for (int i = 0; i < 512; i++) {
			var significand = new java.math.BigInteger(256, random);
			if (random.nextBoolean()) significand = significand.negate();
			int exponent = random.nextInt(2300) - 1400;
			var exact = new SignedDyadic(significand, exponent);
			var decimal = new java.math.BigDecimal(significand);
			var scale = new java.math.BigDecimal(java.math.BigInteger.ONE.shiftLeft(Math.abs(exponent)));
			decimal = exponent < 0 ? decimal.divide(scale) : decimal.multiply(scale);
			require(Double.doubleToRawLongBits(exact.toDouble()) == Double.doubleToRawLongBits(decimal.doubleValue())
					&& Float.floatToRawIntBits(exact.toFloat()) == Float.floatToRawIntBits(decimal.floatValue()),
					"Exact binary observation differs from independent terminating-decimal rounding");
		}
	}

	private static void referenceTermsKeepCanonicalRationalRounding() {
		var cache = new ThermalReferenceEnergyCache(); var random = new java.util.Random(728173L);
		for (int i = 0; i < 2048; i++) {
			long numerator = i == 0 ? Long.MIN_VALUE : i == 1 ? -57300 : random.nextInt();
			long denominator = i % 3 == 0 ? 1 : (random.nextLong() & 0xfffffL) + 1;
			var rational = new ExactRational(java.math.BigInteger.valueOf(numerator), java.math.BigInteger.valueOf(denominator));
			var offset = new ThermalSpeciesOffset("test:offset", "test:species", "test:material", rational);
			for (long quanta : new long[] {0, 1, 6_944_444_444_444L, random.nextLong() & Long.MAX_VALUE}) {
				double expected = rational.multiply(quanta).divide(ChemicalLimits.QUANTA_PER_AU).toDouble();
				require(Double.doubleToRawLongBits(cache.energy(offset, quanta)) == Double.doubleToRawLongBits(expected),
						"Reference term changed canonical exact-rational binary64 rounding");
			}
		}
	}

	private static void liquidTemperatureTracksMutableInventory() {
		var material = new ThermalMaterialDefinition("test:thermal", ThermalMaterialDefinition.CarrierKind.LIQUID,
				"test:liquid", 300, 20, 0.5,
				List.of(new ThermalMaterialDefinition.CaloricSegment(1, 1000, 4)));
		var chemistry = BuiltinChemicalDefinitions.REGISTRY;
		var offsets = new java.util.ArrayList<ThermalSpeciesOffset>();
		for (var species : chemistry.species()) offsets.add(new ThermalSpeciesOffset("test:offset/" + species.id().replace(':', '/'),
				species.id(), material.id(), ExactRational.of(species.id().equals(BuiltinChemicalDefinitions.H2O) ? 1000 : 2000)));
		offsets.add(new ThermalSpeciesOffset("test:unused_offset", "aaa:unused", material.id(), ExactRational.of(3000)));
		var registry = new ThermalRegistry(List.of(material), offsets, List.of(), chemistry.fingerprint(), "");
		var function = new ThermalStateFunction(registry);
		class Markers implements ThermalCarrierContext {
			double mass = 2;
			SignedDyadic completeMass;
			ChemicalMixtureRecord composition;
			ChemicalRegistry currentChemistry = chemistry;
			public int markerCount() { return 1; }
			public int cell(int marker) { return 0; }
			public int fluidMaterialHandle(int marker) { return 0; }
			public double mass(int marker) { return mass; }
			public SignedDyadic exactMass(int marker) { return completeMass == null ? SignedDyadic.of(mass) : completeMass; }
			public double volume(int marker) { return 1; }
			public ChemicalMixtureRecord mixture(int marker) { return composition; }
			public ChemicalRegistry chemicalRegistry() { return currentChemistry; }
		}
		var markers = new Markers();
		var column = new LiquidEnergyColumn(registry, function, new cc.ecisr.mmm.units.SolverUnitScale(1),
				new int[] {0}, 300, new ThermalAuditState(ThermalAuditLedger.initialized(SignedDyadic.ZERO)), 1, markers);
		for (int iteration = 0; iteration < 1100; iteration++) {
			markers.mass = iteration + 1;
			double temperature = 310 + iteration % 20;
			column.setEnergy(0, function.internalEnergyAtTemperature(0, markers.mass, Map.of(), temperature));
			require(close(column.temperature(0, markers), temperature), "Mass edits and cache eviction preserve temperature");
			column.setEnergy(0, function.internalEnergyAtTemperature(0, markers.mass, Map.of(), temperature + 5));
			require(close(column.temperature(0, markers), temperature + 5), "Energy edits remain visible on cache hits");
		}
		markers.mass = 2;
		column.resetMarkerTemperature(0, 315, markers);
		require(close(column.temperature(0, markers), 315), "An evicted input is recomputed exactly");
		for (long amount : new long[] {1_000_000_000L, 2_000_000_000L, 1_000_000_000L}) {
			markers.composition = ChemicalMixtureRecord.fromStableAmounts(chemistry,
					Map.of(BuiltinChemicalDefinitions.H2O, amount), 0);
			double energy = function.internalEnergyAtTemperature(0, markers.mass, markers.composition, chemistry, 330);
			var prospective = column.caloricInventory(0, markers.mass, markers.composition, chemistry);
			require(Double.doubleToRawLongBits(prospective.energy(330)) == Double.doubleToRawLongBits(energy)
					&& prospective.temperature(energy) == column.temperatureForInventory(0, markers.mass, markers.composition, energy, chemistry)
					&& prospective.roundoff(330) == column.modeledEnergyRoundoff(0, markers.mass, markers.composition, 330, chemistry),
					"prospective caloric inventory changed reference terms, inversion or roundoff");
			double previousMass = markers.mass; markers.mass *= 2;
			require(prospective.energy(330) == energy, "prospective caloric inventory retained mutable carrier input");
			markers.mass = previousMass;
			column.setEnergy(0, energy);
			require(close(column.temperature(0, markers), 330), "Composition edits select the matching reference energy");
		}
		var shiftedSpecies = new java.util.ArrayList<>(chemistry.species());
		shiftedSpecies.add(new ChemicalSpecies("aaa:unused", Map.of("X", 1), 0, ExactRational.ONE, 0xff000000));
		var shifted = new ChemicalRegistry(shiftedSpecies, chemistry.phases(), chemistry.reactions());
		for (var current : new ChemicalRegistry[] {shifted, chemistry, shifted}) {
			markers.currentChemistry = current;
			double expectedBase = function.baseEnergy(0, markers.mass, markers.composition, current);
			require(Double.doubleToRawLongBits(column.baseEnergy(0, markers)) == Double.doubleToRawLongBits(expectedBase),
					"liquid reference memo retained handles from another chemical registry");
			column.setEnergy(0, function.internalEnergyAtTemperature(0, markers.mass, markers.composition, current, 340));
			require(close(column.temperature(0, markers), 340), "registry replacement left a stale caloric reference");
		}
		markers.currentChemistry = chemistry; markers.composition = null; markers.mass = 1;
		var scale = new cc.ecisr.mmm.units.SolverUnitScale(1.5);
		var midpoint = SignedDyadic.of(1).add(SignedDyadic.of(1, -53));
		require(midpoint.toDouble() == markers.mass && scale.kilograms(midpoint).toDouble() != scale.kilograms(markers.mass),
				"Caloric quantity fixture does not expose double rounding during SI conversion");
		markers.completeMass = midpoint;
		var complete = new LiquidEnergyColumn(registry, function, scale, new int[]{0}, 315,
				new ThermalAuditState(ThermalAuditLedger.initialized(SignedDyadic.ZERO)), 1, markers);
		require(complete.energy(0) == function.internalEnergyAtTemperature(0, scale.kilograms(midpoint).toDouble(), Map.of(), 315),
				"Liquid initialization rounded complete mass before SI conversion");
		for (var full : List.of(SignedDyadic.of(1), midpoint, SignedDyadic.of(1), midpoint.add(SignedDyadic.of(1, -2000)))) {
			markers.completeMass = full; double physical = scale.kilograms(full).toDouble();
			double expectedBase = function.baseEnergy(0, physical, Map.of());
			require(complete.baseEnergy(0, markers) == expectedBase, "Caloric mass memo hid a native quantity tail");
			complete.resetMarkerTemperature(0, 330, markers);
			double expectedEnergy = function.internalEnergyAtTemperature(0, physical, Map.of(), 330);
			require(complete.energy(0) == expectedEnergy && complete.modeledEnergy(0, full, null, 330, chemistry) == expectedEnergy,
					"Liquid temperature reset observed normalized mass before physical units");
			var caloric = complete.caloricInventory(0, full, null, chemistry);
			double expectedTemperature = material.temperatureForSensibleSpecificEnergy((expectedEnergy - expectedBase) / physical);
			require(complete.temperature(0, markers) == expectedTemperature && caloric.temperature(expectedEnergy) == expectedTemperature
					&& complete.temperatureForInventory(0, full, null, expectedEnergy, chemistry) == expectedTemperature
					&& caloric.energy(330) == expectedEnergy && caloric.roundoff(330) == complete.modeledEnergyRoundoff(0, full, null, 330, chemistry),
					"Current and prospective caloric states disagree on full physical mass");
			var kept = full.multiply(SignedDyadic.of(0.5));
			require(complete.planTruncate(0, kept, null, 330, markers).newEnergy()
					== function.internalEnergyAtTemperature(0, scale.kilograms(kept).toDouble(), Map.of(), 330),
					"Native extraction dropped the remaining mass tail in caloric preflight");
		}
		var domainScale = cc.ecisr.mmm.units.SolverUnitScale.forCellSize(1.1F);
		var domain = new ThermalDomainState(registry, new int[]{0}, new int[0], 330, 1, 1, 1, 1, 1.1F, markers, null, 8);
		for (var full : List.of(SignedDyadic.of(1), midpoint)) {
			markers.completeMass = full; double physical = domainScale.kilograms(full).toDouble();
			domain.resetMarkerTemperature(0, 330, markers, domain.exactEnergy(1));
			var body = domain.prepareLiquidConduction(markers).bodies().getFirst();
			require(body.massKilograms() == physical && body.baseEnergyEu() == function.baseEnergy(0, physical, Map.of())
					&& domain.carrierAccess(markers).mass(0) == physical && domain.temperature(0, markers) == body.temperatureK(),
					"Conduction and spatial caloric observations used different physical mass");
		}
	}

	private static void coherentPhaseMergeSplitConservesInventories() {
		ChemicalRegistry chemistry = new ChemicalRegistry(List.of(
				new ChemicalSpecies("test:a", Map.of("A", 1), 0, ExactRational.of(1_000_000_000L), 0),
				new ChemicalSpecies("test:b", Map.of("A", 2), 0, ExactRational.of(2_000_000_000L), 0)),
				List.of(new ChemicalPhaseDefinition("test:phase", "test:liquid", List.of("test:a", "test:b"),
						Map.of("test:a", ExactRational.ONE), false)), List.of(new ChemicalReaction(
				"test:combine", ChemicalReaction.Kind.INSTANTANEOUS, "test:phase",
				Map.of("test:a", 2), Map.of("test:b", 1), ExactRational.ZERO)));
		CoherentPhaseStore store = new CoherentPhaseStore(8, 8);
		CoherentPhaseStore.Composition first = new CoherentPhaseStore.Composition(List.of(
				new CoherentPhaseStore.Component("test:a", 8L),
				new CoherentPhaseStore.Component("test:b", 4L)), Double.doubleToRawLongBits(-12.0));
		CoherentPhaseStore.Composition second = new CoherentPhaseStore.Composition(List.of(
				new CoherentPhaseStore.Component("test:a", 8L),
				new CoherentPhaseStore.Component("test:b", 4L)), Double.doubleToRawLongBits(-12.0));
		store.add(new CoherentPhaseStore.Group(3, "test:thermal/solid", 8L, 4.0, first, 32.0));
		store.add(new CoherentPhaseStore.Group(3, "test:thermal/solid", 8L, 4.0, second, 32.0));
		CoherentPhaseStore.Group removed = store.take(3, "test:thermal/solid", 5L, chemistry);
		for (CoherentPhaseStore.Group part : List.of(removed, store.group(0))) {
			double represented = ChemicalMixtureRecord.fromStableAmounts(chemistry,
					part.composition().stableAmounts(), part.composition().massResidual()).massKilograms(chemistry);
			require(close(represented, part.mass()), "coherent split closes each physical mass column");
		}
		require(store.groupCount() == 1 && store.group(0).occupiedVolumeSvq() == 11L
				&& removed.occupiedVolumeSvq() == 5L && close(store.totalMass() + removed.mass(), 8.0)
				&& store.exactEnergy().add(SignedDyadic.of(removed.internalEnergy())).equals(SignedDyadic.of(64.0))
				&& removed.composition().stableAmounts().equals(Map.of("test:a", 5L, "test:b", 2L))
				&& removed.composition().massResidual() + store.group(0).composition().massResidual() == -24.0,
				"coherent phase merge/split did not conserve its independent inventories");
	}

	private static void granularColumnTransactionsConserveEnergy() {
		GranularEnergyColumn column = new GranularEnergyColumn(8, 8);
		column.addActive(10.0); column.addActive(20.0); column.addActive(30.0);
		GranularEnergyColumn.DepositKey first = new GranularEnergyColumn.DepositKey(1, 2, 0);
		GranularEnergyColumn.DepositKey second = new GranularEnergyColumn.DepositKey(2, 2, 0);
		column.addDeposit(first, 5.0); column.addDeposit(first, 5.0);
		SignedDyadic initial = column.exactEnergy();
		column.mergeActive(2, 0);
		double removedActive = column.takeActivePortion(0, 1L, 4L);
		double removedDeposit = column.takeDeposit(first, 1L, 4L);
		column.moveDeposit(first, second, 1L, 3L);
		SignedDyadic removed = SignedDyadic.of(removedActive).add(SignedDyadic.of(removedDeposit));
		require(column.activeCount() == 2 && close(column.activeEnergy(0), 30.0)
				&& close(column.activeEnergy(1), 20.0)
				&& close(column.depositEnergy(first), 5.0)
				&& close(column.depositEnergy(second), 2.5)
				&& column.exactEnergy().add(removed).equals(initial),
				"granular thermal split/merge/move did not conserve raw energy");
		GranularEnergyColumn restored = new GranularEnergyColumn(8, 8);
		restored.restore(column.copyActiveEnergy(), column.depositSnapshot());
		require(restored.exactEnergy().equals(column.exactEnergy())
				&& restored.clear().equals(column.exactEnergy()),
				"granular thermal column handoff/clear did not preserve exact energy");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static boolean close(double first, double second) {
		return Math.abs(first - second) <= Math.max(1.0E-10,
				Math.max(Math.ulp(first), Math.ulp(second)) * 8.0);
	}
}
