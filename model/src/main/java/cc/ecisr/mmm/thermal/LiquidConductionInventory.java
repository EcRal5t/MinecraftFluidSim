package cc.ecisr.mmm.thermal;

import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Immutable caloric liquid inventory and preflighted conservative energy publication. */
public final class LiquidConductionInventory {
	private record Stock(int cell, int material, SignedDyadic mass, SignedDyadic volume,
			double massObservation, double volumeObservation, ChemicalMixtureRecord mixture) { }
	private final ThermalDomainState owner;
	private final ThermalCarrierContext context;
	private final ThermalDomainState.Revision revision;
	private final ChemicalRegistry chemistry;
	private final List<Stock> stocks;
	private final List<ThermalContactExchange.Carrier> bodies;
	private final double[] energy;

	LiquidConductionInventory(ThermalDomainState owner, ThermalCarrierContext context) {
		this.owner = java.util.Objects.requireNonNull(owner); this.context = java.util.Objects.requireNonNull(context);
		revision = owner.thermalRevision(); chemistry = context.chemicalRegistry();
		var sources = new ArrayList<Stock>(); var caloric = new ArrayList<ThermalContactExchange.Carrier>();
		energy = owner.liquid().copyEnergies(context.markerCount());
		var access = owner.carrierAccess(context);
		for (int p = 0; p < energy.length; p++) {
			sources.add(stock(context, p));
			caloric.add(new ThermalContactExchange.Carrier(access.material(p), access.mass(p), access.baseEnergy(p), energy[p]));
		}
		stocks = List.copyOf(sources); bodies = List.copyOf(caloric); requireSource(owner, context);
	}
	public List<ThermalContactExchange.Carrier> bodies() { return bodies; }

	/** All deltas correspond to the captured ordinals; spatial and temporal solves supply their observation certificate. */
	public Plan plan(double[] changes, SignedDyadic reservoirEnergy, SignedDyadic observationBound) {
		requireSource(owner, context);
		if (changes == null || changes.length != energy.length || reservoirEnergy == null || observationBound == null || observationBound.signum() < 0)
			throw new IllegalArgumentException("Incomplete liquid conduction publication");
		var next = energy.clone(); var bound = observationBound;
		var solveRoundoff = SignedDyadic.sum(changes).subtract(reservoirEnergy);
		if (solveRoundoff.compareTo(bound) > 0 || solveRoundoff.compareTo(bound.negate()) < 0)
			throw new IllegalArgumentException("Liquid conduction solve exceeds its observation certificate");
		for (int p = 0; p < next.length; p++) {
			if (!Double.isFinite(changes[p])) throw new IllegalArgumentException("Non-finite liquid conduction delta");
			if (changes[p] == 0) continue;
			next[p] = SignedDyadic.of(energy[p]).add(SignedDyadic.of(changes[p])).toDouble();
			var source = bodies.get(p);
			new ThermalContactExchange.Carrier(source.material(), source.massKilograms(), source.baseEnergyEu(), next[p]);
			bound = bound.add(SignedDyadic.of(Math.ulp(next[p])).multiply(SignedDyadic.of(0.5)));
		}
		var actualDelta = SignedDyadic.sum(next).subtract(SignedDyadic.sum(energy)); var roundoff = actualDelta.subtract(reservoirEnergy);
		if (roundoff.compareTo(bound) > 0 || roundoff.compareTo(bound.negate()) < 0)
			throw new IllegalStateException("Liquid conduction storage exceeds its observation certificate");
		var ledger = owner.auditLedger().withHeatExchange(reservoirEnergy).withRoundoff(roundoff);
		var expected = owner.exactEnergy(energy.length).add(actualDelta);
		if (!ledger.expectedInventory().equals(expected)) throw new IllegalStateException("Liquid conduction source audit is inconsistent");
		return new Plan(this, next, ledger, bound);
	}
	private void requireSource(ThermalDomainState current, ThermalCarrierContext markers) {
		if (current != owner || markers != context || !revision.equals(owner.thermalRevision())
				|| markers.markerCount() != energy.length || markers.chemicalRegistry() != chemistry
				|| !Arrays.equals(energy, owner.liquid().copyEnergies(energy.length)))
			throw new IllegalStateException("Liquid conduction source changed after capture");
		for (int p = 0; p < energy.length; p++) if (!stocks.get(p).equals(stock(markers, p)))
			throw new IllegalStateException("Liquid conduction inventory changed after capture");
	}
	private static Stock stock(ThermalCarrierContext context, int marker) {
		var mass = context.exactMass(marker); var volume = context.exactVolume(marker);
		if (mass == null || volume == null || mass.signum() <= 0 || volume.signum() <= 0)
			throw new IllegalArgumentException("Invalid complete liquid conduction inventory");
		return new Stock(context.cell(marker), context.fluidMaterialHandle(marker), mass, volume, context.mass(marker), context.volume(marker), context.mixture(marker));
	}
	public static final class Plan {
		private final LiquidConductionInventory source;
		private final double[] energies;
		private final ThermalAuditLedger ledger;
		private final SignedDyadic roundoffBound;
		private Plan(LiquidConductionInventory source, double[] energies, ThermalAuditLedger ledger, SignedDyadic roundoffBound) {
			this.source = source; this.energies = energies; this.ledger = ledger; this.roundoffBound = roundoffBound;
		}
		public SignedDyadic roundoffBound() { return roundoffBound; }
		void commit(ThermalDomainState owner, ThermalCarrierContext markers) {
			source.requireSource(owner, markers);
			// Both inventory states and the complete audit successor passed before any write.
			owner.liquid().adoptRestored(energies); owner.audit().replace(ledger);
		}
	}
}
