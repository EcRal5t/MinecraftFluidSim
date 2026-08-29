package cc.ecisr.mmm.thermal;

import cc.ecisr.mmm.units.SignedDyadic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Pure bounded exchange planner between one finite thermal body and independent fluid energy ports. */
public final class FiniteThermalExchange {
	/**
	 * One caller-owned fluid energy port linearized inside a single caloric interval. Conductance
	 * already includes fluid, wall and contact-area resistance and therefore has units EU/(s K).
	 */
	public record Contact(
			int ordinal,
			double temperatureK,
			double heatCapacityEuPerK,
			double conductanceEuPerSecondK,
			double minimumTemperatureK,
			double maximumTemperatureK
	) {
		public Contact {
			if (ordinal < 0
					|| !(Double.isFinite(temperatureK)
					&& Double.isFinite(minimumTemperatureK) && Double.isFinite(maximumTemperatureK)
					&& minimumTemperatureK >= ThermalLimits.MIN_ABSOLUTE_TEMPERATURE_K
					&& maximumTemperatureK > minimumTemperatureK
					&& temperatureK >= minimumTemperatureK && temperatureK <= maximumTemperatureK)
					|| !(Double.isFinite(heatCapacityEuPerK) && heatCapacityEuPerK > 0.0)
					|| !(Double.isFinite(conductanceEuPerSecondK) && conductanceEuPerSecondK > 0.0)) {
				throw new IllegalArgumentException("Finite thermal contact is invalid");
			}
		}
	}

	public record ContactDelta(int ordinal, double internalEnergyDeltaEu, double finalTemperatureK) {
		public ContactDelta {
			if (ordinal < 0 || !Double.isFinite(internalEnergyDeltaEu)
					|| !Double.isFinite(finalTemperatureK)) {
				throw new IllegalArgumentException("Finite thermal contact result is invalid");
			}
		}
	}

	/** Complete plan; the coordinator must compare and publish both before/after owner generations atomically. */
	public record Plan(
			FiniteThermalBodyState before,
			FiniteThermalBodyState after,
			List<ContactDelta> contacts,
			int substeps,
			double maximumAbsoluteTransferEu,
			SignedDyadic roundoffResidual
	) {
		public Plan {
			if (before == null || after == null || contacts == null || contacts.isEmpty()
					|| contacts.size() > ThermalLimits.MAX_FINITE_DEVICE_CONTACTS
					|| substeps <= 0 || !Double.isFinite(maximumAbsoluteTransferEu)
					|| maximumAbsoluteTransferEu < 0.0 || roundoffResidual == null) {
				throw new IllegalArgumentException("Finite thermal exchange plan is incomplete");
			}
			contacts = List.copyOf(contacts);
			if (!before.thermalRegistryFingerprint().equals(after.thermalRegistryFingerprint())
					|| !before.thermalMaterialId().equals(after.thermalMaterialId())
					|| Double.doubleToRawLongBits(before.massKilograms())
					!= Double.doubleToRawLongBits(after.massKilograms())
					|| after.generation() != before.generation()
					&& after.generation() != Math.addExact(before.generation(), 1L)) {
				throw new IllegalArgumentException("Finite thermal exchange changed body identity");
			}
			int previousOrdinal = -1;
			SignedDyadic exactChange = SignedDyadic.of(after.internalEnergyEu())
					.subtract(SignedDyadic.of(before.internalEnergyEu()));
			for (ContactDelta contact : contacts) {
				if (contact == null || contact.ordinal() <= previousOrdinal) {
					throw new IllegalArgumentException("Finite thermal contact results are not canonical");
				}
				previousOrdinal = contact.ordinal();
				exactChange = exactChange.add(SignedDyadic.of(contact.internalEnergyDeltaEu()));
			}
			if (!exactChange.equals(roundoffResidual)) {
				throw new IllegalArgumentException("Finite thermal exchange roundoff does not close");
			}
			if (after.generation() == before.generation()
					&& (!after.equals(before) || contacts.stream().anyMatch(contact -> contact.internalEnergyDeltaEu() != 0.0))) {
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
		ThermalStateFunction stateFunction = new ThermalStateFunction(registry);
		stateFunction.stateForInternalEnergy(
				materialHandle, body.massKilograms(), java.util.Map.of(), body.internalEnergyEu());
		double minimumBodyHeatCapacity = Double.POSITIVE_INFINITY;
		for (ThermalMaterialDefinition.CaloricSegment segment : material.caloricSegments()) {
			minimumBodyHeatCapacity = Math.min(minimumBodyHeatCapacity,
					body.massKilograms() * segment.specificHeatEuPerMuK());
		}
		if (!(Double.isFinite(minimumBodyHeatCapacity) && minimumBodyHeatCapacity > 0.0)) {
			throw new IllegalArgumentException("Finite thermal body heat-capacity bound is invalid");
		}
		double conductanceSum = 0.0;
		double stableDt = Double.POSITIVE_INFINITY;
		for (Contact contact : canonical) {
			conductanceSum += contact.conductanceEuPerSecondK();
			stableDt = Math.min(stableDt, 0.5 * contact.heatCapacityEuPerK()
					/ contact.conductanceEuPerSecondK());
		}
		if (!(Double.isFinite(conductanceSum) && conductanceSum > 0.0)) {
			throw new IllegalArgumentException("Finite thermal conductance sum is invalid");
		}
		// The body may cross a caloric-segment boundary during this plan. Using only its initial
		// heat capacity can make a later lower-capacity segment unstable, so bind the whole plan
		// to the smallest capacity in the registry-frozen validity range.
		stableDt = Math.min(stableDt, 0.5 * minimumBodyHeatCapacity / conductanceSum);
		int substeps = (int) Math.ceil(logicalDtSeconds / stableDt);
		if (substeps > maximumSubsteps) {
			throw new IllegalArgumentException("Finite thermal stability substep budget exceeded: " + substeps);
		}

		double bodyEnergy = body.internalEnergyEu();
		double[] fluidTemperature = canonical.stream().mapToDouble(Contact::temperatureK).toArray();
		double[] fluidDelta = new double[canonical.size()];
		double maximumTransfer = 0.0;
		double dt = logicalDtSeconds / substeps;
		for (int step = 0; step < substeps; step++) {
			double bodyTemperature = stateFunction.stateForInternalEnergy(
					materialHandle, body.massKilograms(), java.util.Map.of(), bodyEnergy).temperatureK();
			double bodyDelta = 0.0;
			for (int index = 0; index < canonical.size(); index++) {
				Contact contact = canonical.get(index);
				double transfer = contact.conductanceEuPerSecondK()
						* (bodyTemperature - fluidTemperature[index]) * dt;
				if (!Double.isFinite(transfer)) throw new IllegalArgumentException("Finite thermal transfer is not finite");
				double candidateTemperature = fluidTemperature[index] + transfer / contact.heatCapacityEuPerK();
				if (!(Double.isFinite(candidateTemperature) && candidateTemperature >= contact.minimumTemperatureK()
						&& candidateTemperature <= contact.maximumTemperatureK())) {
					throw new IllegalArgumentException("Finite thermal contact left its caloric validity interval");
				}
				fluidTemperature[index] = candidateTemperature;
				fluidDelta[index] += transfer;
				bodyDelta -= transfer;
				maximumTransfer = Math.max(maximumTransfer, Math.abs(transfer));
			}
			bodyEnergy += bodyDelta;
			stateFunction.stateForInternalEnergy(materialHandle, body.massKilograms(), java.util.Map.of(), bodyEnergy);
		}

		boolean changed = Double.doubleToRawLongBits(bodyEnergy)
				!= Double.doubleToRawLongBits(body.internalEnergyEu());
		for (double delta : fluidDelta) changed |= delta != 0.0;
		long generation = changed ? Math.addExact(body.generation(), 1L) : body.generation();
		FiniteThermalBodyState after = new FiniteThermalBodyState(generation,
				body.thermalRegistryFingerprint(), body.thermalMaterialId(), body.massKilograms(), bodyEnergy);
		ArrayList<ContactDelta> deltas = new ArrayList<>(canonical.size());
		SignedDyadic exactChange = SignedDyadic.of(after.internalEnergyEu()).subtract(
				SignedDyadic.of(body.internalEnergyEu()));
		for (int index = 0; index < canonical.size(); index++) {
			Contact contact = canonical.get(index);
			double finalTemperature = contact.temperatureK() + fluidDelta[index] / contact.heatCapacityEuPerK();
			if (!(Double.isFinite(finalTemperature) && finalTemperature >= contact.minimumTemperatureK()
					&& finalTemperature <= contact.maximumTemperatureK())) {
				throw new IllegalArgumentException("Finite thermal contact result left its caloric validity interval");
			}
			deltas.add(new ContactDelta(contact.ordinal(), fluidDelta[index], finalTemperature));
			exactChange = exactChange.add(SignedDyadic.of(fluidDelta[index]));
		}
		return new Plan(body, after, deltas, substeps, maximumTransfer, exactChange);
	}
}
