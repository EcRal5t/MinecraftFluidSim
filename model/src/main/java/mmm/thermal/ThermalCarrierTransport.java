package mmm.thermal;

/** Positive, conservative conduction on real caloric carriers, without an LTE redistribution. */
public final class ThermalCarrierTransport {
	/** second == -1 denotes a fixed-temperature external contact. */
	public record Contact(int first, int second, double conductance, double reservoirTemperatureK) {
		public Contact { require(first, second, conductance, reservoirTemperatureK); }
		static void require(int first, int second, double conductance, double reservoirTemperatureK) {
			if (first < 0 || second < -1 || first == second
					|| !(Double.isFinite(conductance) && conductance > 0))
				throw new IllegalArgumentException("Invalid carrier conduction contact");
			if (second < 0) new ThermalContactExchange.Reservoir(reservoirTemperatureK);
		}
	}

	private ThermalCarrierTransport() { }
	/** One private carrier sample shared by spatial contacts, caloric solve and final publication. */
	public static final class Batch implements ThermalCellGrid.CarrierAccess {
		private final ThermalCellGrid.CarrierAccess owners;
		private final ThermalMaterialDefinition[] materials;
		private final double[] mass, base, energy, initialTemperature, heatCapacity;
		private double[] temperature;
		private Batch(ThermalCellGrid.CarrierAccess owners) {
			this.owners = owners;
			int count = owners.carrierCount();
			materials = new ThermalMaterialDefinition[count];
			mass = new double[count]; base = new double[count]; energy = new double[count];
			initialTemperature = new double[count];
			temperature=initialTemperature;heatCapacity=new double[count];
			for (int owner = 0; owner < count; owner++) {
				materials[owner] = owners.material(owner); mass[owner] = owners.mass(owner);
				base[owner] = owners.baseEnergy(owner); energy[owner] = owners.internalEnergy(owner);
				ThermalStateFunction.State state;
				try{state=ThermalCaloricInversion.owner(materials[owner],mass[owner],energy[owner],base[owner]);}
				catch(RuntimeException failure){
					failure.addSuppressed(new IllegalStateException("Thermal carrier="+owner+", cell="+owners.cell(owner)
							+", material="+materials[owner].id()+", mass="+mass[owner]+", U="+energy[owner]+", base="+base[owner]));
					throw failure;
				}
				initialTemperature[owner]=state.temperatureK();heatCapacity[owner]=state.heatCapacityJPerK();
			}
		}
		@Override public int carrierCount() { return energy.length; }
		@Override public int cell(int owner) { return owners.cell(owner); }
		@Override public int supportMask(int owner) { return owners.supportMask(owner); }
		@Override public ThermalMaterialDefinition material(int owner) { return materials[owner]; }
		@Override public double mass(int owner) { return mass[owner]; }
		@Override public double volume(int owner) { return owners.volume(owner); }
		@Override public double baseEnergy(int owner) { return base[owner]; }
		@Override public double internalEnergy(int owner) { return energy[owner]; }
		@Override public void setInternalEnergy(int owner, double value) { throw new UnsupportedOperationException("Conduction batch is private"); }
		// Carrier publication retains the supplied doubles, so the final caloric sample remains valid.

		/** Exact thermal equilibrium permits omitting contact geometry before the conduction solve. */
		/** Unresolved conduction leaves the energy columns unchanged; its temperature error stays bounded. */
		public boolean conductionBelowResolution(double[] reservoirTemperatures) {
			if(temperature.length==0)return true;
			double low=temperature[0],high=low;
			for(int owner=1;owner<temperature.length;owner++){
				low=Math.min(low,temperature[owner]);high=Math.max(high,temperature[owner]);
				if(high-low>ThermalTransport.CONDUCTION_RESOLUTION_K)return false;
			}
			if(reservoirTemperatures!=null)for(double reservoir:reservoirTemperatures)if(!Double.isNaN(reservoir)){
				low=Math.min(low,reservoir);high=Math.max(high,reservoir);
				if(high-low>ThermalTransport.CONDUCTION_RESOLUTION_K)return false;
			}
			return true;
		}
		ThermalCellGrid.CarrierAccess owners(){return owners;}
		public double temperatureK(int owner){return temperature[owner];}
		double heatCapacityJPerK(int owner){return heatCapacity[owner];}
	}
	public static Batch prepare(ThermalCellGrid.CarrierAccess owners) { return new Batch(owners); }

}
