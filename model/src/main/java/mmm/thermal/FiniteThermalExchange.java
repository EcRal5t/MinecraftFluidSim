package mmm.thermal;

import mmm.units.SignedDyadic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pure bounded exchange planner between one finite thermal body and independent fluid energy ports. */
public final class FiniteThermalExchange {
	/** Actual caloric owner and its fraction of the exposed contact area. */
	public record Carrier(int owner,ThermalMaterialDefinition material,double mass,double baseEnergy,
	                      double internalEnergy,double contactFraction) {
		public Carrier {
			if(owner<0||material==null||!(mass>0&&Double.isFinite(mass))||!Double.isFinite(baseEnergy)
					||!Double.isFinite(internalEnergy)||!(contactFraction>=0&&Double.isFinite(contactFraction)))
				throw new IllegalArgumentException("Finite thermal carrier is invalid");
		}
		ThermalContactExchange.Carrier caloric(){return new ThermalContactExchange.Carrier(material,mass,baseEnergy,internalEnergy);}
	}
	/** A spatial port retains every owner's own temperature and caloric curve. */
	public record Contact(int ordinal,double conductanceWPerK,List<Carrier> carriers) {
		public Contact {
			if(ordinal<0||!(conductanceWPerK>0&&Double.isFinite(conductanceWPerK))||carriers==null||carriers.isEmpty())
				throw new IllegalArgumentException("Finite thermal contact is invalid");
			carriers=List.copyOf(carriers);
		}
		/** A single constant-capacity caloric port. The declared interval continues with its endpoint heat capacity. */
		public Contact(int ordinal,double temperature,double capacity,double conductance,double minimum,double maximum){
			this(ordinal,conductance,List.of(new Carrier(0,new ThermalMaterialDefinition("mmm:thermal/constant_port",
					ThermalMaterialDefinition.CarrierKind.DEVICE,"mmm:constant_port",temperature,0,0,
					List.of(new ThermalMaterialDefinition.CaloricSegment(minimum,maximum,capacity))),1,0,0,1)));
		}
		public double heatCapacityJPerK(){double sum=0;for(var carrier:carriers)sum+=carrier.caloric().heatCapacityJPerK();return sum;}
		public double temperatureK(){double sum=0,weight=0;for(var carrier:carriers){var body=carrier.caloric();double c=body.heatCapacityJPerK();sum+=c*body.temperatureK();weight+=c;}return sum/weight;}
	}
	public record CarrierDelta(int owner,double beforeEnergy,double afterEnergy) { }
	public record ContactDelta(int ordinal,double internalEnergyDeltaJ,double finalTemperatureK,List<CarrierDelta> carriers) {
		public ContactDelta { carriers=List.copyOf(carriers); }
	}

	/** Complete plan; the coordinator must compare and publish both before/after owner generations atomically. */
	public record Plan(
			FiniteThermalBodyState before,
			FiniteThermalBodyState after,
			List<Contact> initialContacts,
			List<ContactDelta> contacts,
			int substeps,
			double maximumAbsoluteTransferJ,
			SignedDyadic roundoffResidual
	) {
		public Plan {
			if (before == null || after == null || initialContacts == null || contacts == null || contacts.isEmpty()
					|| initialContacts.size() != contacts.size()
					|| contacts.size() > ThermalLimits.MAX_FINITE_DEVICE_CONTACTS
					|| substeps <= 0 || !Double.isFinite(maximumAbsoluteTransferJ)
					|| maximumAbsoluteTransferJ < 0.0 || roundoffResidual == null) {
				throw new IllegalArgumentException("Finite thermal exchange plan is incomplete");
			}
			initialContacts = List.copyOf(initialContacts);
			contacts = List.copyOf(contacts);
			if (!before.thermalRegistryFingerprint().equals(after.thermalRegistryFingerprint())
					|| !before.thermalMaterialId().equals(after.thermalMaterialId())
					|| before.massUnits() != after.massUnits()
					|| after.generation() != before.generation()
					&& after.generation() != Math.addExact(before.generation(), 1L)) {
				throw new IllegalArgumentException("Finite thermal exchange changed body identity");
			}
			int previousOrdinal = -1;
			SignedDyadic exactChange = SignedDyadic.of(after.internalEnergyJ())
					.subtract(SignedDyadic.of(before.internalEnergyJ()));
			for (int index = 0; index < contacts.size(); index++) {
				ContactDelta contact = contacts.get(index);
				if (contact == null || contact.ordinal() <= previousOrdinal) {
					throw new IllegalArgumentException("Finite thermal contact results are not canonical");
				}
				if (initialContacts.get(index).ordinal() != contact.ordinal()) {
					throw new IllegalArgumentException("Finite thermal initial contact ordinal changed");
				}
				previousOrdinal = contact.ordinal();
				exactChange = exactChange.add(SignedDyadic.of(contact.internalEnergyDeltaJ()));
			}
			if (!exactChange.equals(roundoffResidual)) {
				throw new IllegalArgumentException("Finite thermal exchange roundoff does not close");
			}
			if (after.generation() == before.generation()
					&& (!after.equals(before) || contacts.stream().anyMatch(contact -> contact.internalEnergyDeltaJ() != 0.0))) {
				throw new IllegalArgumentException("Finite thermal no-op plan changed authoritative state");
			}
		}

		public boolean changed() { return after.generation() != before.generation(); }
	}

	private FiniteThermalExchange() { }

	public static Plan plan(
			ThermalRegistry registry,
			FiniteThermalBodyState body,
			List<Contact> contacts,
			double logicalDtSeconds,
			int maximumSubsteps
	) {
		if (registry == null || body == null || contacts == null || contacts.isEmpty()
				|| contacts.size() > ThermalLimits.MAX_FINITE_DEVICE_CONTACTS) {
			throw new IllegalArgumentException("Finite thermal exchange input is missing or over budget");
		}
		if (!body.thermalRegistryFingerprint().equals(registry.fingerprint())) {
			throw new IllegalArgumentException("Finite thermal body registry fingerprint changed");
		}
		if (!(Double.isFinite(logicalDtSeconds) && logicalDtSeconds > 0.0)
				|| maximumSubsteps <= 0 || maximumSubsteps > ThermalTransport.DEFAULT_MAXIMUM_SUBSTEPS) {
			throw new IllegalArgumentException("Finite thermal timestep or substep budget is invalid");
		}
		ArrayList<Contact> canonical = new ArrayList<>(contacts);
		if (canonical.stream().anyMatch(java.util.Objects::isNull)) {
			throw new IllegalArgumentException("Finite thermal contacts contain null");
		}
		canonical.sort(Comparator.comparingInt(Contact::ordinal));
		for (int index = 1; index < canonical.size(); index++) if (canonical.get(index - 1).ordinal()
				== canonical.get(index).ordinal()) {
			throw new IllegalArgumentException("Duplicate finite thermal contact ordinal " + canonical.get(index).ordinal());
		}

		int materialHandle = registry.materialHandle(body.thermalMaterialId());
		ThermalMaterialDefinition material = registry.material(materialHandle);
		if (material.carrierKind() != ThermalMaterialDefinition.CarrierKind.DEVICE) {
			throw new IllegalArgumentException("Finite thermal body material is not a device carrier");
		}
		var function=new ThermalStateFunction(registry);
		var samples=new ArrayList<Carrier>();
		samples.add(new Carrier(0,material,body.massKilograms(),function.baseEnergy(materialHandle,body.massKilograms(),java.util.Map.of()),body.internalEnergyJ(),1));
		var graph=new ThermalCarrierContacts(canonical.stream().mapToInt(c->c.carriers().size()).sum());
		for(var contact:canonical)for(var carrier:contact.carriers()){
			int owner=samples.size();samples.add(carrier);
			if(carrier.contactFraction()>0)graph.add(0,owner,contact.conductanceWPerK()*carrier.contactFraction(),Double.NaN);
		}
		double[] energies=samples.stream().mapToDouble(Carrier::internalEnergy).toArray();
		var access=new ThermalCellGrid.CarrierAccess(){
			public int carrierCount(){return samples.size();}
			public int cell(int owner){return 0;}
			public ThermalMaterialDefinition material(int owner){return samples.get(owner).material();}
			public double mass(int owner){return samples.get(owner).mass();}
			public double volume(int owner){return 1;}
			public double baseEnergy(int owner){return samples.get(owner).baseEnergy();}
			public double internalEnergy(int owner){return energies[owner];}
			public void setInternalEnergy(int owner,double energy){energies[owner]=energy;}
		};
		var batch=ThermalCarrierTransport.prepare(access);
		var stats=ThermalCarrierTransport.solve(batch,graph,logicalDtSeconds,maximumSubsteps);batch.commit();
		boolean changed=energies[0]!=body.internalEnergyJ();
		var deltas=new ArrayList<ContactDelta>();int owner=1;
		var exactChange=SignedDyadic.of(energies[0]).subtract(SignedDyadic.of(body.internalEnergyJ()));
		for(var contact:canonical){
			var writes=new ArrayList<CarrierDelta>();var change=SignedDyadic.ZERO;double weighted=0,capacity=0;
			for(var carrier:contact.carriers()){
				double after=energies[owner++];writes.add(new CarrierDelta(carrier.owner(),carrier.internalEnergy(),after));
				change=change.add(SignedDyadic.of(after).subtract(SignedDyadic.of(carrier.internalEnergy())));
				var state=new ThermalContactExchange.Carrier(carrier.material(),carrier.mass(),carrier.baseEnergy(),after);
				double c=state.heatCapacityJPerK();weighted+=state.temperatureK()*c;capacity+=c;
			}
			double delta=change.toDouble();changed|=delta!=0;
			deltas.add(new ContactDelta(contact.ordinal(),delta,weighted/capacity,writes));
			exactChange=exactChange.add(SignedDyadic.of(delta));
		}
		var after=new FiniteThermalBodyState(changed?Math.addExact(body.generation(),1):body.generation(),
				body.thermalRegistryFingerprint(),body.thermalMaterialId(),body.massUnits(),energies[0]);
		return new Plan(body,after,canonical,deltas,stats.substeps(),stats.maximumAbsoluteTransferJ(),exactChange);
	}
}
