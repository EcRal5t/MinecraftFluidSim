package mmm.thermal;

import mmm.units.SignedDyadic;
import java.util.Arrays;
import java.util.List;

/** Finite-contact limits, heterogeneous caloric entropy, and authoritative carrier validity. */
public final class ThermalContactValidation {
	private ThermalContactValidation() { }
	public static void main(String[] args) { validate(); System.out.println("Thermal contact validation passed"); }
	public static void validate() {
		finiteContactMatchesAnalyticPair();
		finiteContactRetainsEnergyScale();
		heterogeneousContactsPreserveTemperatureOrderAndEntropy();
		registeredMultiSegmentContactKeepsPositiveResponse();
		invalidCarrierCannotHideInValidCellMean();
		carrierGraphPreservesCaloricInventoryAndPreflights();
	}
	private static void registeredMultiSegmentContactKeepsPositiveResponse(){
		var segments=new java.util.ArrayList<ThermalMaterialDefinition.CaloricSegment>();
		for(int segment=0;segment<12;segment++)segments.add(new ThermalMaterialDefinition.CaloricSegment(
				1+100*segment,101+100*segment,Math.scalb(16.0,11-segment)));
		var material=new ThermalMaterialDefinition("test:thermal/multi_segment",ThermalMaterialDefinition.CarrierKind.DEVICE,
				"test:multi_segment",300,0,1,segments);
		for(double dt:new double[]{.001,1,1000}){
			var cold=carrier(material,1,0,10);var hot=carrier(material,2,0,1190);
			double q=ThermalContactExchange.transfer(cold,hot,1000,dt);
			double first=cold.stateAfterEnergyChange(q).temperatureK(),second=hot.stateAfterEnergyChange(-q).temperatureK();
			require(q>0&&first>=10&&first<=second+1e-4&&second<=1190,
					"Registered caloric segments stalled a finite heat contact or reversed temperature order");
		}
	}

	private static void finiteContactMatchesAnalyticPair() {
		var first = material("first", 10, 10); var second = material("second", 20, 20);
		var cold = carrier(first, 2, 10000, 270); var hot = carrier(second, 3, -20000, 350);
		for (double g : new double[] {0, .01, 1, 1e4}) for (double dt : new double[] {.001, .1, 100}) {
			double q = ThermalContactExchange.transfer(cold, hot, g, dt);
			double expected = g == 0 ? 0 : 80 / (1 / (g * dt) + 1.0 / 20 + 1.0 / 60);
			require(Math.abs(q - expected) <= 1e-6 * Math.max(1, Math.abs(expected)), "finite contact failed analytic backward Euler: " + q);
			require(q == -ThermalContactExchange.transfer(hot, cold, g, dt), "contact depends on orientation");
			double boundary = ThermalContactExchange.transfer(cold, new ThermalContactExchange.Reservoir(350), g, dt);
			double expectedBoundary = g == 0 ? 0 : 80 / (1 / (g * dt) + 1.0 / 20);
			require(Math.abs(boundary - expectedBoundary) <= 1e-6 * Math.max(1, Math.abs(expectedBoundary)), "fixed contact failed analytic limit");
		}

	}

	private static void heterogeneousContactsPreserveTemperatureOrderAndEntropy() {
		var first = material("first", 10, 40); var second = material("second", 70, 20);
		for (double dt : new double[] {0.001, 0.5, 1000}) for (double g : new double[] {.01, 1, 1000}) {
			var cold = carrier(first, 2, 100000, 270); var hot = carrier(second, 3, -200000, 400);
			double q = ThermalContactExchange.transfer(cold, hot, g, dt);
			var coldAfter = new ThermalContactExchange.Carrier(first, 2, cold.baseEnergyJ(), cold.internalEnergyJ() + q);
			var hotAfter = new ThermalContactExchange.Carrier(second, 3, hot.baseEnergyJ(), hot.internalEnergyJ() - q);
			double coldT = coldAfter.temperatureK(), hotT = hotAfter.temperatureK();
			require(coldT >= 270 - 1e-4 && hotT <= 400 + 1e-4 && coldT <= hotT + 1e-4,
					"heterogeneous finite contact reversed temperature order");
			double residual = q / g / dt - (hotT - coldT);
			require(Math.abs(residual) <= 1e-4, "heterogeneous contact exceeded its macroscopic heat-flux tolerance: " + residual);
			double entropy = 2 * entropyChange(first, 270, coldT) + 3 * entropyChange(second, 400, hotT);
			require(entropy >= -1e-4, "closed heat contact reduced entropy: " + entropy);
			double[] before = {cold.internalEnergyJ(), hot.internalEnergyJ()}, after = {coldAfter.internalEnergyJ(), hotAfter.internalEnergyJ()};
			require(Math.abs(SignedDyadic.sum(after).subtract(SignedDyadic.sum(before)).toDouble())
					<= 1e-4 * (cold.heatCapacityJPerK() + hot.heatCapacityJPerK()),
					"finite contact changed energy beyond its macroscopic caloric resolution");
		}
		// Every contact remains finite on a stiff, heterogeneous hot/cold chain, including isolated carriers.
		double[] temperatures = {1, 350, 1, 350, 1}, masses = {1, 2, 3, 4, 5}, bases = {10000, -20000, 30000, -40000, 50000};
		ThermalMaterialDefinition[] materials = {first, second, first, second, first};
		double[] energies = new double[temperatures.length];
		for (int i = 0; i < energies.length; i++) energies[i] = carrier(materials[i], masses[i], bases[i], temperatures[i]).internalEnergyJ();
		double isolated = energies[4]; var before = SignedDyadic.sum(energies);
		for (int step = 0; step < 3; step++) for (int sweep = 0; sweep < 2; sweep++) for (int j = 0; j < 4; j++) {
			int i = sweep == 0 ? j : 3 - j;
			var left = new ThermalContactExchange.Carrier(materials[i], masses[i], bases[i], energies[i]);
			var right = new ThermalContactExchange.Carrier(materials[i + 1], masses[i + 1], bases[i + 1], energies[i + 1]);
			double q = ThermalContactExchange.transfer(left, right, i == 3 ? 0 : 1000, 500);
			energies[i] += q; energies[i + 1] -= q;
			for (int k = 0; k < energies.length; k++) {
				double t = new ThermalContactExchange.Carrier(materials[k], masses[k], bases[k], energies[k]).temperatureK();
				require(t >= 1 - 1e-4 && t <= 350 + 1e-4, "finite-contact chain violated its maximum principle");
			}
		}
		require(energies[4] == isolated && Math.abs(SignedDyadic.sum(energies).subtract(before).toDouble()) < 1e-8,
				"adiabatic contact chain changed isolated U or failed energy closure");
	}

	private static void finiteContactRetainsEnergyScale() {
		for(double capacity:new double[]{.5,850,4184,10000})for(double mass:new double[]{1,125}) {
			var material=constantMaterial("scaled",capacity,300,1,3000);
			var states=new ThermalStateFunction(new ThermalRegistry(List.of(material)));
			double hotU=states.internalEnergyAtTemperature(0,mass,java.util.Map.of(),556);
			var cold=new ThermalContactExchange.Carrier(material,mass,0,0);
			var hot=new ThermalContactExchange.Carrier(material,mass,0,hotU);
			double q=ThermalContactExchange.transfer(cold,hot,capacity*mass,1),expected=256*capacity*mass/3;
			require(Math.abs(states.stateForInternalEnergy(0,mass,java.util.Map.of(),hotU).temperatureK()-556)<1e-4,
					"Ordinary contact did not retain its source temperature");
			require(q>0&&Math.abs(q-expected)<1e-6*Math.max(1,expected)
					&&q==-ThermalContactExchange.transfer(hot,cold,capacity*mass,1),
					"Contact changed its finite heat-capacity response");
		}
	}
	private static ThermalMaterialDefinition constantMaterial(String name, double cv, double reference, double low, double high) {
		return new ThermalMaterialDefinition("test:thermal/" + name, ThermalMaterialDefinition.CarrierKind.DEVICE,
				"test:" + name, reference, 0, 1, List.of(new ThermalMaterialDefinition.CaloricSegment(low, high, cv)));
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
			for (int p = 0; p < 3; p++) energy[p] = carrier(materials[p], mass[p], base[p], initialT[p]).internalEnergyJ();
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
			var packed = new ThermalCarrierContacts(0); packed.add(0, 1, g, Double.NaN);
			var stats = ThermalCarrierTransport.step(owners, g < 1 ? packed
					: List.of(new ThermalCarrierTransport.Contact(0, 1, g, Double.NaN)), g < 1 ? 1 : 1000, 8);
			double entropy = 0;
			for (int p = 0; p < 3; p++) {
				double t = new ThermalContactExchange.Carrier(materials[p], mass[p], base[p], energy[p]).temperatureK();
				require(t >= 270 && t <= 400, "carrier graph violated the caloric maximum principle");
				entropy += mass[p] * entropyChange(materials[p], initialT[p], t);
			}
			require(entropy >= -1e-10 && energy[2] == isolated
					&& SignedDyadic.sum(energy).subtract(before).equals(stats.roundoffResidual())
					&& stats.reservoirExchange().equals(SignedDyadic.ZERO), "carrier graph lost entropy, isolated energy or conservative closure");
			for (int p = 0; p < 3; p++) energy[p] = carrier(materials[p], mass[p], base[p], 300).internalEnergyJ();
			double[] isothermal = energy.clone();
			var zero = ThermalCarrierTransport.step(owners, List.of(
					new ThermalCarrierTransport.Contact(0, 1, g, Double.NaN),
					new ThermalCarrierTransport.Contact(2, -1, g, 300)), 1000, 8);
			require(Arrays.equals(energy, isothermal) && zero.substeps() == 1
					&& zero.maximumTemperatureChangeK() == 0 && zero.maximumAbsoluteTransferJ() == 0
					&& zero.reservoirExchange().equals(SignedDyadic.ZERO)
					&& zero.roundoffResidual().equals(SignedDyadic.ZERO), "isothermal contacts changed caloric inventory");
			mass[1] = 0;
			boolean invalidMass = false;
			try { ThermalCarrierTransport.prepare(owners); }
			catch (IllegalArgumentException expected) { invalidMass = true; }
			mass[1] = 3;
			require(invalidMass && Arrays.equals(energy, isothermal), "zero-flux preflight concealed invalid owner mass");
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
			if (b > a) value += segment.specificHeatJPerKgK() * Math.log(b / a);
		}
		return to < from ? -value : value;
	}
	private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
