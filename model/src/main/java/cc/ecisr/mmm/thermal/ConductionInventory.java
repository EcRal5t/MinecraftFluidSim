package cc.ecisr.mmm.thermal;

import cc.ecisr.mmm.chemistry.ChemicalMixtureRecord;
import cc.ecisr.mmm.chemistry.ChemicalRegistry;
import cc.ecisr.mmm.fluid.air.AirNetwork;
import cc.ecisr.mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Complete liquid and finite-gas caloric inventories with joint conservative publication. */
public final class ConductionInventory {
	private record Stock(int cell, int material, SignedDyadic mass, cc.ecisr.mmm.units.ExactVolume volume,
			double massObservation, double volumeObservation, ChemicalMixtureRecord mixture) { }
	private final ThermalDomainState owner;
	private final ThermalCarrierContext context;
	private final ThermalDomainState.Revision revision;
	private final ChemicalRegistry chemistry;
	private final List<Stock> stocks;
	private final List<ThermalContactExchange.Carrier> liquidBodies;
	private final List<ThermalContactExchange.Body> bodies;
	private final double[] energy, gasEnergy;
	private final AirNetwork network;
	private final long networkRevision;
	private final int[] gasOrdinals;

	ConductionInventory(ThermalDomainState owner, ThermalCarrierContext context, AirNetwork network) {
		this.owner = java.util.Objects.requireNonNull(owner); this.context = java.util.Objects.requireNonNull(context);
		this.network = network; networkRevision = network == null ? 0 : network.revision();
		revision = owner.thermalRevision(); chemistry = context.chemicalRegistry();
		var sources = new ArrayList<Stock>(); var caloric = new ArrayList<ThermalContactExchange.Carrier>();
		energy = owner.liquid().copyEnergies(context.markerCount());
		var access = owner.carrierAccess(context);
		for (int p = 0; p < energy.length; p++) {
			sources.add(stock(context, p));
			caloric.add(new ThermalContactExchange.Carrier(access.material(p), access.mass(p), access.baseEnergy(p), energy[p]));
		}
		stocks = List.copyOf(sources); liquidBodies = List.copyOf(caloric);
		var all = new ArrayList<ThermalContactExchange.Body>(caloric);
		gasEnergy = owner.gas().nodeEnergies(); gasOrdinals = new int[gasEnergy.length]; Arrays.fill(gasOrdinals, -1);
		if (network == null ? gasEnergy.length != 0 : network.nodeCount() != gasEnergy.length
				|| gasEnergy.length != 0 && !owner.gas().hasGasEnergy(network.nodeCount()))
			throw new IllegalArgumentException("Conduction gas inventory is not bound to its topology");
		for (int n = 0; n < gasEnergy.length; n++) {
			owner.gas().requireNodeState(n + 1, network.inventory(n + 1), gasEnergy[n], network);
			if (!network.inventory(n + 1).isEmpty()) {
				gasOrdinals[n] = all.size(); all.add(owner.gas().caloricBody(n + 1, network));
			}
		}
		bodies = List.copyOf(all); requireSource(owner, context, network);
	}
	public List<ThermalContactExchange.Carrier> liquidBodies() { return liquidBodies; }
	public List<ThermalContactExchange.Body> bodies() { return bodies; }
	/** Vacuum and the prescribed ambient reservoir have no finite caloric unknown. */
	public int gasOrdinal(int nodeId) {
		if (nodeId == AirNetwork.AMBIENT_NODE) return -1;
		if (nodeId <= 0 || nodeId > gasOrdinals.length) throw new IllegalArgumentException("Unknown conduction gas node");
		return gasOrdinals[nodeId - 1];
	}

	/** All deltas correspond to the captured ordinals; spatial and temporal solves supply their observation certificate. */
	public Plan plan(double[] changes, SignedDyadic reservoirEnergy, SignedDyadic observationBound) {
		requireSource(owner, context, network);
		if (changes == null || changes.length != bodies.size() || reservoirEnergy == null || observationBound == null || observationBound.signum() < 0)
			throw new IllegalArgumentException("Incomplete conduction publication");
		var next = energy.clone(); var nextGas = gasEnergy.clone(); var bound = observationBound;
		for (double change : changes) if (!Double.isFinite(change)) throw new IllegalArgumentException("Non-finite conduction delta");
		var solveRoundoff = SignedDyadic.sum(changes).subtract(reservoirEnergy);
		if (solveRoundoff.compareTo(bound) > 0 || solveRoundoff.compareTo(bound.negate()) < 0)
			throw new IllegalArgumentException("Conduction solve exceeds its observation certificate");
		for (int p = 0; p < next.length; p++) {
			if (changes[p] == 0) continue;
			next[p] = SignedDyadic.of(energy[p]).add(SignedDyadic.of(changes[p])).toDouble();
			var source = liquidBodies.get(p);
			new ThermalContactExchange.Carrier(source.material(), source.massKilograms(), source.baseEnergyEu(), next[p]);
			bound = bound.add(SignedDyadic.of(Math.ulp(next[p])).multiply(SignedDyadic.of(0.5)));
		}
		for (int n = 0; n < nextGas.length; n++) {
			int ordinal = gasOrdinals[n];
			if (ordinal < 0 || changes[ordinal] == 0) continue;
			nextGas[n] = SignedDyadic.of(gasEnergy[n]).add(SignedDyadic.of(changes[ordinal])).toDouble();
			owner.gas().requireNodeState(n + 1, network.inventory(n + 1), nextGas[n], network);
			bound = bound.add(SignedDyadic.of(Math.ulp(nextGas[n])).multiply(SignedDyadic.of(0.5)));
		}
		var actualDelta = SignedDyadic.sum(next).subtract(SignedDyadic.sum(energy))
				.add(SignedDyadic.sum(nextGas).subtract(SignedDyadic.sum(gasEnergy)));
		var roundoff = actualDelta.subtract(reservoirEnergy);
		if (roundoff.compareTo(bound) > 0 || roundoff.compareTo(bound.negate()) < 0)
			throw new IllegalStateException("Conduction storage exceeds its observation certificate");
		var ledger = owner.auditLedger().withHeatExchange(reservoirEnergy).withRoundoff(roundoff);
		var expected = owner.exactEnergy(energy.length).add(actualDelta);
		if (!ledger.expectedInventory().equals(expected)) throw new IllegalStateException("Conduction source audit is inconsistent");
		return new Plan(this, next, nextGas, ledger, roundoff, bound);
	}
	private void requireSource(ThermalDomainState current, ThermalCarrierContext markers, AirNetwork currentNetwork) {
		if (current != owner || markers != context || !revision.equals(owner.thermalRevision())
				|| markers.markerCount() != energy.length || markers.chemicalRegistry() != chemistry
				|| !Arrays.equals(energy, owner.liquid().copyEnergies(energy.length))
				|| currentNetwork != network || network != null && network.revision() != networkRevision
				|| !Arrays.equals(gasEnergy, owner.gas().nodeEnergiesRaw()))
			throw new IllegalStateException("Conduction source changed after capture");
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
		private final ConductionInventory source;
		private final double[] energies, gasEnergies;
		private final ThermalAuditLedger ledger;
		private final SignedDyadic roundoffBound;
		private final SignedDyadic roundoff;
		private Plan(ConductionInventory source, double[] energies, double[] gasEnergies, ThermalAuditLedger ledger, SignedDyadic roundoff, SignedDyadic roundoffBound) {
			this.source = source; this.energies = energies; this.gasEnergies = gasEnergies; this.ledger = ledger; this.roundoff = roundoff; this.roundoffBound = roundoffBound;
		}
		public SignedDyadic roundoffResidual() { return roundoff; }
		public SignedDyadic roundoffBound() { return roundoffBound; }
		void commit(ThermalDomainState owner, ThermalCarrierContext markers, AirNetwork network) {
			source.requireSource(owner, markers, network);
			// Both inventory states and the complete audit successor passed before any write.
			owner.liquid().adoptRestored(energies); owner.gas().replaceNodeEnergies(gasEnergies); owner.audit().replace(ledger);
		}
	}
}
