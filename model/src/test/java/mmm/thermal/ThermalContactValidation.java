package mmm.thermal;

import java.util.Arrays;
import java.util.List;

/** Authoritative carrier validity. */
public final class ThermalContactValidation {
	private ThermalContactValidation() { }
	public static void main(String[] args) { validate(); System.out.println("Thermal contact validation passed"); }
	public static void validate() {
		invalidCarrierCannotHideInValidCellMean();
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

	private static ThermalMaterialDefinition material(String name, double lowCv, double highCv) {
		return new ThermalMaterialDefinition("test:thermal/" + name, ThermalMaterialDefinition.CarrierKind.LIQUID,
				"test:" + name, 300, 0, 1, List.of(new ThermalMaterialDefinition.CaloricSegment(1, 300, lowCv),
				new ThermalMaterialDefinition.CaloricSegment(300, 500, highCv)));
	}

	private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
