package cc.ecisr.mmm.thermal;

import cc.ecisr.mmm.units.SignedDyadic;
import java.util.Arrays;
import java.util.List;

/** Finite-contact limits, heterogeneous caloric entropy, and authoritative carrier validity. */
public final class ThermalContactValidation {
	private ThermalContactValidation() { }
	public static void main(String[] args) { validate(); System.out.println("Thermal contact validation passed"); }
	public static void validate() {
		finiteContactMatchesAnalyticPair();
		heterogeneousContactsPreserveTemperatureOrderAndEntropy();
		invalidCarrierCannotHideInValidCellMean();
		carrierGraphPreservesCaloricInventoryAndPreflights();
		condensedCellTransportHasFiniteRates();
	}

	private static void condensedCellTransportHasFiniteRates() {
		for (var kind : new ThermalMaterialDefinition.CarrierKind[] {
				ThermalMaterialDefinition.CarrierKind.LIQUID, ThermalMaterialDefinition.CarrierKind.GRANULAR,
				ThermalMaterialDefinition.CarrierKind.COHERENT_SOLID}) {
			double firstChange = 0;
			for (double k : new double[] {0, 1e-8, 1e-7, 1e8}) {
				var material = new ThermalMaterialDefinition("test:thermal/finite", kind, "test:finite", 300, 0, k,
						List.of(new ThermalMaterialDefinition.CaloricSegment(1, 500, 10)));
				double[] energy = {-300, 500, -300, 500}, base = {1000, -2000, 3000, -4000};
				for (int p = 0; p < 4; p++) energy[p] += base[p];
				var owners = new ThermalCellGrid.CarrierAccess() {
					@Override public int carrierCount() { return 4; }
					@Override public int cell(int p) { return p / 2; }
					@Override public ThermalMaterialDefinition material(int p) { return material; }
					@Override public double mass(int p) { return 1; }
					@Override public double volume(int p) { return 0.25; }
					@Override public double baseEnergy(int p) { return base[p]; }
					@Override public double internalEnergy(int p) { return energy[p]; }
					@Override public void setInternalEnergy(int p, double u) { energy[p] = u; }
				};
				var grid = new ThermalCellGrid(2); grid.rebuild(owners);
				var before = SignedDyadic.sum(energy); double[] raw = energy.clone();
				var result = new ThermalTransport(2, 1, 1, 0.5, 8).step(grid, (x, y, z, axis) -> 0.125, 1);
				double change = material.temperatureForSensibleSpecificEnergy(energy[0] - base[0]) - 270;
				if (k == 0) require(Arrays.equals(raw, energy), "insulating condensed geometry edited U");
				else if (k < 1) {
					require(change > 0 && change < 1e-5, "positive small k lost finite condensed-carrier response");
					if (firstChange > 0) require(Math.abs(change / firstChange - 10) < 0.001, "condensed transport lost linear k limit");
					firstChange = change;
				}
				for (int p = 0; p < 4; p++) {
					double t = material.temperatureForSensibleSpecificEnergy(energy[p] - base[p]);
					require(t >= 270 && t <= 350, "condensed transport violated the maximum principle");
				}
				require(SignedDyadic.sum(energy).subtract(before).equals(result.roundoffResidual())
						&& result.reservoirExchange().equals(SignedDyadic.ZERO), "condensed transport failed energy closure");
			}
		}
	}

	private static void finiteContactMatchesAnalyticPair() {
		var first = material("first", 10, 10); var second = material("second", 20, 20);
		var cold = carrier(first, 2, 10000, 270); var hot = carrier(second, 3, -20000, 350);
		for (double g : new double[] {0, 1e-12, 1, 1e12}) for (double dt : new double[] {1e-6, 0.1, 1000}) {
			double q = ThermalContactExchange.transfer(cold, hot, g, dt);
			double expected = g == 0 ? 0 : 80 / (1 / (g * dt) + 1.0 / 20 + 1.0 / 60);
			require(Math.abs(q - expected) <= 8 * Math.ulp(expected), "finite contact failed analytic backward Euler: " + q);
			require(q == -ThermalContactExchange.transfer(hot, cold, g, dt), "contact depends on orientation");
			if (g > 0 && g * dt < 1e-8) require(Math.abs(q / (g * dt * 80) - 1) < 1e-8,
					"small conductance or dt lost the finite-rate limit");
			double boundary = ThermalContactExchange.transfer(cold, new ThermalContactExchange.Reservoir(350), g, dt);
			double expectedBoundary = g == 0 ? 0 : 80 / (1 / (g * dt) + 1.0 / 20);
			require(Math.abs(boundary - expectedBoundary) <= 8 * Math.ulp(expectedBoundary), "fixed contact failed analytic limit");
		}
		// Both multiplication orders describe the same representable contact-times-dt.
		var low = carrier(first, 2, 0, 270); var high = carrier(second, 3, 0, 350);
		double tinyG = ThermalContactExchange.transfer(low, high, 1e-310, 1e300);
		double tinyDt = ThermalContactExchange.transfer(low, high, 1e300, 1e-310);
		require(Math.abs(tinyG / tinyDt - 1) < 1e-13 && tinyG > 0, "reciprocal overflow hid finite heat transfer");
	}

	private static void heterogeneousContactsPreserveTemperatureOrderAndEntropy() {
		var first = material("first", 10, 40); var second = material("second", 70, 20);
		for (double dt : new double[] {0.001, 0.5, 1000}) for (double g : new double[] {1e-8, 1, 1e8}) {
			var cold = carrier(first, 2, 100000, 270); var hot = carrier(second, 3, -200000, 400);
			double q = ThermalContactExchange.transfer(cold, hot, g, dt);
			var coldAfter = new ThermalContactExchange.Carrier(first, 2, cold.baseEnergyEu(), cold.internalEnergyEu() + q);
			var hotAfter = new ThermalContactExchange.Carrier(second, 3, hot.baseEnergyEu(), hot.internalEnergyEu() - q);
			double coldT = coldAfter.temperatureK(), hotT = hotAfter.temperatureK();
			require(coldT >= 270 - 1e-10 && hotT <= 400 + 1e-10 && coldT <= hotT + 1e-10,
					"heterogeneous finite contact reversed temperature order");
			double residual = q / g / dt - (hotT - coldT);
			require(Math.abs(residual) < 1e-9, "heterogeneous contact failed its heat-flux equation: " + residual);
			double entropy = 2 * entropyChange(first, 270, coldT) + 3 * entropyChange(second, 400, hotT);
			require(entropy >= -1e-10, "closed heat contact reduced entropy: " + entropy);
			double[] before = {cold.internalEnergyEu(), hot.internalEnergyEu()}, after = {coldAfter.internalEnergyEu(), hotAfter.internalEnergyEu()};
			require(Math.abs(SignedDyadic.sum(after).subtract(SignedDyadic.sum(before)).toDouble())
					<= 16 * Math.max(Math.ulp(before[0]), Math.ulp(before[1])), "finite contact changed inventory beyond rounding");
		}
		// Every contact remains finite on a stiff, heterogeneous hot/cold chain, including isolated carriers.
		double[] temperatures = {1, 350, 1, 350, 1}, masses = {1, 2, 3, 4, 5}, bases = {10000, -20000, 30000, -40000, 50000};
		ThermalMaterialDefinition[] materials = {first, second, first, second, first};
		double[] energies = new double[temperatures.length];
		for (int i = 0; i < energies.length; i++) energies[i] = carrier(materials[i], masses[i], bases[i], temperatures[i]).internalEnergyEu();
		double isolated = energies[4]; var before = SignedDyadic.sum(energies);
		for (int step = 0; step < 3; step++) for (int sweep = 0; sweep < 2; sweep++) for (int j = 0; j < 4; j++) {
			int i = sweep == 0 ? j : 3 - j;
			var left = new ThermalContactExchange.Carrier(materials[i], masses[i], bases[i], energies[i]);
			var right = new ThermalContactExchange.Carrier(materials[i + 1], masses[i + 1], bases[i + 1], energies[i + 1]);
			double q = ThermalContactExchange.transfer(left, right, i == 3 ? 0 : 1000, 500);
			energies[i] += q; energies[i + 1] -= q;
			for (int k = 0; k < energies.length; k++) {
				double t = new ThermalContactExchange.Carrier(materials[k], masses[k], bases[k], energies[k]).temperatureK();
				require(t >= 1 - 1e-10 && t <= 350 + 1e-10, "finite-contact chain violated its maximum principle");
			}
		}
		require(energies[4] == isolated && Math.abs(SignedDyadic.sum(energies).subtract(before).toDouble()) < 1e-8,
				"adiabatic contact chain changed isolated U or failed energy closure");
	}

	private static void invalidCarrierCannotHideInValidCellMean() {
		var material = material("invalid", 10, 10);
		for (double mass : new double[] {1, 0, -1, Double.NaN}) {
			double[] energies = {-3010, 1010}; var before = energies.clone();
			var access = new ThermalCellGrid.CarrierAccess() {
				@Override public int carrierCount() { return 2; }
				@Override public int cell(int i) { return 0; }
				@Override public ThermalMaterialDefinition material(int i) { return material; }
				@Override public double mass(int i) { return i == 0 ? mass : 1; }
				@Override public double volume(int i) { return 1; }
				@Override public double baseEnergy(int i) { return 0; }
				@Override public double internalEnergy(int i) { return energies[i]; }
				@Override public void setInternalEnergy(int i, double u) { energies[i] = u; }
			};
			boolean rejected = false;
			try { new ThermalCellGrid(1).rebuild(access); } catch (IllegalArgumentException expected) { rejected = true; }
			require(rejected && Arrays.equals(before, energies), "valid cell mean concealed an invalid real carrier");
		}
	}

	private static void carrierGraphPreservesCaloricInventoryAndPreflights() {
		var materials = new ThermalMaterialDefinition[] {material("graph_first", 10, 40), material("graph_second", 70, 20), material("isolated", 10, 10)};
		double[] mass = {2, 3, 1}, base = {100000, -200000, 50000}, initialT = {270, 400, 300};
		for (double g : new double[] {0.001, 1e6}) {
			double[] energy = new double[3];
			for (int p = 0; p < 3; p++) energy[p] = carrier(materials[p], mass[p], base[p], initialT[p]).internalEnergyEu();
			var owners = new ThermalCellGrid.CarrierAccess() {
				@Override public int carrierCount() { return 3; }
				@Override public int cell(int p) { return 0; }
				@Override public ThermalMaterialDefinition material(int p) { return materials[p]; }
				@Override public double mass(int p) { return mass[p]; }
				@Override public double volume(int p) { return 1; }
				@Override public double baseEnergy(int p) { return base[p]; }
				@Override public double internalEnergy(int p) { return energy[p]; }
				@Override public void setInternalEnergy(int p, double u) { energy[p] = u; }
			};
			var before = SignedDyadic.sum(energy); double isolated = energy[2];
			var stats = ThermalCarrierTransport.step(owners, List.of(new ThermalCarrierTransport.Contact(0, 1, g, Double.NaN)), g < 1 ? 1 : 1000, 8);
			double entropy = 0;
			for (int p = 0; p < 3; p++) {
				double t = new ThermalContactExchange.Carrier(materials[p], mass[p], base[p], energy[p]).temperatureK();
				require(t >= 270 && t <= 400, "carrier graph violated the caloric maximum principle");
				entropy += mass[p] * entropyChange(materials[p], initialT[p], t);
			}
			require(entropy >= -1e-10 && energy[2] == isolated
					&& SignedDyadic.sum(energy).subtract(before).equals(stats.roundoffResidual())
					&& stats.reservoirExchange().equals(SignedDyadic.ZERO), "carrier graph lost entropy, isolated energy or conservative closure");
			double[] unchanged = energy.clone();
			boolean rejected = false;
			try { ThermalCarrierTransport.step(owners, List.of(new ThermalCarrierTransport.Contact(0, 3, g, Double.NaN)), 1, 8); }
			catch (IllegalArgumentException expected) { rejected = true; }
			require(rejected && Arrays.equals(energy, unchanged), "invalid graph edited a real energy owner");
		}
	}

	private static ThermalMaterialDefinition material(String name, double lowCv, double highCv) {
		return new ThermalMaterialDefinition("test:thermal/" + name, ThermalMaterialDefinition.CarrierKind.LIQUID,
				"test:" + name, 300, 0, 1, List.of(new ThermalMaterialDefinition.CaloricSegment(1, 300, lowCv),
				new ThermalMaterialDefinition.CaloricSegment(300, 500, highCv)));
	}
	private static ThermalContactExchange.Carrier carrier(ThermalMaterialDefinition material, double mass, double base, double t) {
		return new ThermalContactExchange.Carrier(material, mass, base, base + mass * material.sensibleSpecificEnergyAt(t));
	}
	private static double entropyChange(ThermalMaterialDefinition material, double from, double to) {
		double low = Math.min(from, to), high = Math.max(from, to), value = 0;
		for (var segment : material.caloricSegments()) {
			double a = Math.max(low, segment.minimumTemperatureK()), b = Math.min(high, segment.maximumTemperatureK());
			if (b > a) value += segment.specificHeatEuPerMuK() * Math.log(b / a);
		}
		return to < from ? -value : value;
	}
	private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
