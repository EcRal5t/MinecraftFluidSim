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
		referenceEnergyUsesCanonicalSpeciesOrder();
		coolingInversionRetainsEnergyScale();
	}

	private static void coolingInversionRetainsEnergyScale() {
		for (double cv : new double[] {Math.scalb(1.0, -1000), 1, Math.scalb(1.0, 1000), 1e308}) {
			var material = new ThermalMaterialDefinition("test:cooling", ThermalMaterialDefinition.CarrierKind.DEVICE,
					"test:cooling", 300, 0, 1, List.of(new ThermalMaterialDefinition.CaloricSegment(1, 500, cv)));
			var state = new ThermalStateFunction(new ThermalRegistry(List.of(material)));
			double energy = state.internalEnergyAtTemperature(0, 1, Map.of(), 299);
			require(energy == -cv && state.stateForInternalEnergy(0, 1, Map.of(), energy).temperatureK() == 299,
					"cooling inversion lost a finite temperature under energy scaling");
		}
		var material = new ThermalMaterialDefinition("test:wide_cooling", ThermalMaterialDefinition.CarrierKind.DEVICE,
				"test:wide_cooling", Math.scalb(1.0, 600), 0, 1,
				List.of(new ThermalMaterialDefinition.CaloricSegment(1, Math.scalb(1.0, 600), Math.scalb(1.0, 424))));
		var state = new ThermalStateFunction(new ThermalRegistry(List.of(material)));
		double target = Math.scalb(1.0, 599), energy = state.internalEnergyAtTemperature(0, 1, Map.of(), target);
		require(energy == -Math.scalb(1.0, 1023)
				&& state.stateForInternalEnergy(0, 1, Map.of(), energy).temperatureK() == target,
				"an overflowing whole-segment capacity rejected a finite cooling state");
	}

	private static void referenceEnergyUsesCanonicalSpeciesOrder() {
		var chemistry = BuiltinChemicalDefinitions.REGISTRY;
		var material = new ThermalMaterialDefinition("test:ordered", ThermalMaterialDefinition.CarrierKind.LIQUID,
				"test:liquid", 300, 0, 0, List.of(new ThermalMaterialDefinition.CaloricSegment(1, 1000, 4)));
		var offsets = new java.util.ArrayList<ThermalSpeciesOffset>();
		String[] ids = new String[3];
		long[] energies = {10_000_000_000_000_000L, -10_000_000_000_000_000L, 1L};
		for (int i = 0; i < ids.length; i++) {
			ids[i] = chemistry.species().get(i).id();
			offsets.add(new ThermalSpeciesOffset("test:offset/" + i, ids[i], material.id(), ExactRational.of(energies[i])));
		}
		var function = new ThermalStateFunction(new ThermalRegistry(List.of(material), offsets, List.of(),
				chemistry.fingerprint(), ""));
		var canonical = new java.util.TreeMap<String, Long>();
		for (String id : ids) canonical.put(id, ChemicalLimits.QUANTA_PER_AU);
		var mixture = ChemicalMixtureRecord.fromStableAmounts(chemistry, canonical, 0);
		long expected = Double.doubleToRawLongBits(function.baseEnergy(0, 1, mixture, chemistry));
		for (int[] order : new int[][]{{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}}) {
			var map = new java.util.LinkedHashMap<String, Long>();
			for (int i : order) map.put(ids[i], ChemicalLimits.QUANTA_PER_AU);
			require(Double.doubleToRawLongBits(function.baseEnergy(0, 1, map)) == expected,
					"Reference energy changed with map iteration order or chemical representation");
			require(Double.doubleToRawLongBits(function.internalEnergyAtTemperature(0, 1, map, 320))
					== Double.doubleToRawLongBits(function.internalEnergyAtTemperature(0, 1, mixture, chemistry, 320)),
					"Caloric state depends on species map iteration order");
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
			ChemicalMixtureRecord composition;
			ChemicalRegistry currentChemistry = chemistry;
			public int markerCount() { return 1; }
			public int cell(int marker) { return 0; }
			public int fluidMaterialHandle(int marker) { return 0; }
			public double mass(int marker) { return mass; }
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
