package cc.ecisr.mmm.thermal;

import cc.ecisr.mmm.fluid.air.AirNetwork;
import cc.ecisr.mmm.fluid.air.GasInventory;
import cc.ecisr.mmm.fluid.air.GasRegistry;
import cc.ecisr.mmm.units.SignedDyadic;

import java.util.List;

/**
 * Owns the per-gas-node internal energy column and its gas thermal model binding: node
 * reads, pressure/temperature/density inversions, remap/vent/transfer energy plans with
 * their audit booking, and the gas revision token. Gas node ordinals change only here.
 */
public final class GasEnergyStore {
	private final GasThermalModel gasModel;
	private final double insertionTemperatureK;
	private final ThermalAuditState audit;
	private double[] nodeEnergy;
	private long revision;

	public GasEnergyStore(
			GasThermalModel gasModel, double insertionTemperatureK, ThermalAuditState audit, AirNetwork airNetwork
	) {
		this.gasModel = gasModel;
		this.insertionTemperatureK = insertionTemperatureK;
		this.audit = audit;
		if (gasModel != null) {
			this.nodeEnergy = new double[airNetwork.nodeCount()];
			for (int node = 1; node <= airNetwork.nodeCount(); node++) {
				nodeEnergy[node - 1] = gasModel.internalEnergyAtTemperature(
						airNetwork.inventory(node), insertionTemperatureK);
			}
		} else {
			this.nodeEnergy = new double[0];
		}
	}

	public long revision() { return revision; }

	public boolean hasGasEnergy(int nodeCount) { return gasModel != null && nodeEnergy.length == nodeCount; }
	public boolean gasModelBound() { return gasModel != null; }

	/** Read-only snapshot of the node energies; callers can never observe later in-place commits. */
	public double[] nodeEnergies() { return nodeEnergy.clone(); }

	double[] nodeEnergiesRaw() { return nodeEnergy; }

	void replaceNodeEnergies(double[] restored) {
		nodeEnergy = restored;
		revision++;
	}

	/** Prevalidates restored node energies against the restored inventory pressures. */
	void validateRestoredNodeEnergies(AirNetwork network, double[] restored) {
		for (int node = 0; node < restored.length; node++) {
			gasModel.absolutePressure(network.inventory(node + 1), network.availableVolume(node + 1), restored[node]);
		}
	}

	public double nodeEnergy(int nodeId, AirNetwork network) {
		requireGasNode(nodeId, network);
		return nodeEnergy[nodeId - 1];
	}

	public double temperature(int nodeId, AirNetwork network) {
		requireGasNode(nodeId, network);
		if (network.inventory(nodeId).isEmpty()) return insertionTemperatureK;
		return gasModel.state(network.inventory(nodeId), nodeEnergy[nodeId - 1]).temperatureK();
	}

	public double gaugePressure(int nodeId, AirNetwork network) {
		requireGasNode(nodeId, network);
		return gasModel.absolutePressure(network.inventory(nodeId), network.availableVolume(nodeId),
				nodeEnergy[nodeId - 1]) - GasRegistry.STANDARD_PRESSURE_PA;
	}

	public double density(int nodeId, AirNetwork network) {
		requireGasNode(nodeId, network);
		return gasModel.density(network.inventory(nodeId), network.availableVolume(nodeId));
	}

	public double requireNodeState(int nodeId, GasInventory inventory, double energy, AirNetwork network) {
		requireGasNode(nodeId, network);
		return gasModel.absolutePressure(inventory, network.availableVolume(nodeId), energy);
	}

	void commitNodeEnergy(int nodeId, double energy) {
		if (nodeId <= 0 || nodeId > nodeEnergy.length || !Double.isFinite(energy)) {
			throw new IllegalArgumentException("Gas phase energy commit is invalid");
		}
		nodeEnergy[nodeId - 1] = energy;
		revision++;
	}

	public double[] partitionNodeEnergy(int nodeId, List<GasInventory> children, AirNetwork network) {
		requireGasNode(nodeId, network);
		return gasModel.partitionEnergy(network.inventory(nodeId), nodeEnergy[nodeId - 1], children);
	}

	public double ambientGasEnergy(GasInventory inventory) {
		if (gasModel == null) throw new IllegalStateException("Gas thermal model is not bound");
		return gasModel.internalEnergyAtTemperature(inventory, insertionTemperatureK);
	}

	GasRemapPlan planRemap(
			AirNetwork oldNetwork, List<GasInventory> inventories, double[] availableVolumes,
			double[] mixedEnergy, double[] inheritedAvailableVolumes,
			double sourceEnergy, double sinkEnergy
	) {
		if (!hasGasEnergy(oldNetwork.nodeCount()) || inventories == null
				|| inventories.size() != availableVolumes.length || inventories.size() != mixedEnergy.length
				|| inventories.size() != inheritedAvailableVolumes.length
				|| !Double.isFinite(sourceEnergy) || !Double.isFinite(sinkEnergy)) {
			throw new IllegalArgumentException("Gas thermal remap plan is incompatible");
		}
		double[] result = mixedEnergy.clone();
		for (int node = 0; node < result.length; node++) {
			if (!(Double.isFinite(availableVolumes[node]) && availableVolumes[node] > 0.0)
					|| !(Double.isFinite(inheritedAvailableVolumes[node]) && inheritedAvailableVolumes[node] > 0.0)
					|| !Double.isFinite(result[node])) {
				throw new IllegalArgumentException("Gas thermal remap produced an invalid node");
			}
			if (inventories.get(node).isEmpty()) {
				if (result[node] != 0.0) throw new IllegalArgumentException("Vacuum remap carries internal energy");
				result[node] = 0.0;
				continue;
			}
			if (Double.doubleToRawLongBits(inheritedAvailableVolumes[node])
					== Double.doubleToRawLongBits(availableVolumes[node])) {
				gasModel.absolutePressure(inventories.get(node), availableVolumes[node], result[node]);
			} else {
				result[node] = gasModel.compressAdiabatically(inventories.get(node), result[node],
						inheritedAvailableVolumes[node], availableVolumes[node]).finalInternalEnergy();
			}
		}
		SignedDyadic oldGas = SignedDyadic.sum(nodeEnergy);
		SignedDyadic mixed = SignedDyadic.sum(mixedEnergy);
		SignedDyadic finalGas = SignedDyadic.sum(result);
		SignedDyadic source = SignedDyadic.of(sourceEnergy), sink = SignedDyadic.of(sinkEnergy);
		SignedDyadic work = finalGas.subtract(mixed);
		SignedDyadic roundoff = finalGas.subtract(oldGas).subtract(source).add(sink).subtract(work);
		return new GasRemapPlan(revision, result, source, sink, work, roundoff);
	}

	void requireCanCommitRemap(GasRemapPlan plan) {
		if (plan == null) throw new IllegalArgumentException("Gas thermal remap plan is missing");
		if (plan.baseRevision() != revision) {
			throw new IllegalStateException("Gas energy changed after remap planning");
		}
	}

	void commitRemap(GasRemapPlan plan) {
		requireCanCommitRemap(plan);
		ThermalAuditLedger auditAfter = audit.ledger()
				.withExternalSourceEnergy(plan.sourceEnergy()).withExternalSinkEnergy(plan.sinkEnergy())
				.withMechanicalWork(plan.mechanicalWork()).withRoundoff(plan.roundoff());
		nodeEnergy = plan.nodeEnergy();
		audit.replace(auditAfter);
		revision++;
	}

	/** Builds the already region-validated all-node gas-energy removal plan. */
	GasVentPlan planVent() {
		SignedDyadic removed = SignedDyadic.sum(nodeEnergy);
		return new GasVentPlan(nodeEnergy.length, removed, audit.ledger().withExternalSinkEnergy(removed));
	}

	/** Publishes an already validated all-node gas-energy removal using assignments only. */
	void commitVent(GasVentPlan plan) {
		if (plan == null || nodeEnergy.length != plan.nodeCount()
				|| !SignedDyadic.sum(nodeEnergy).equals(plan.removedEnergy())) {
			throw new IllegalStateException("Gas energy changed after vent planning");
		}
		nodeEnergy = new double[0];
		audit.replace(plan.auditAfter());
		revision++;
	}

	public record GasVentPlan(int nodeCount, SignedDyadic removedEnergy, ThermalAuditLedger auditAfter) { }

	AirNetwork.TransferCommit planTransfer(
			int sourceNode, int targetNode, GasInventory moved,
			GasInventory sourceAfter, GasInventory targetAfter, AirNetwork network
	) {
		if (!hasGasEnergy(network.nodeCount()) || moved == null || moved.isEmpty()) {
			throw new IllegalArgumentException("Gas thermal transfer plan is incompatible");
		}
		double[] result = nodeEnergy.clone();
		double sourceTemperature = sourceNode == AirNetwork.AMBIENT_NODE
				? insertionTemperatureK : temperature(sourceNode, network);
		double carried = gasModel.enthalpy(moved, sourceTemperature);
		SignedDyadic before = SignedDyadic.sum(nodeEnergy);
		SignedDyadic imported = SignedDyadic.ZERO, exported = SignedDyadic.ZERO;
		if (sourceNode == AirNetwork.AMBIENT_NODE) {
			result[targetNode - 1] += carried;
			gasModel.absolutePressure(targetAfter, network.availableVolume(targetNode), result[targetNode - 1]);
			imported = SignedDyadic.of(carried);
		} else {
			result[sourceNode - 1] -= carried;
			gasModel.absolutePressure(sourceAfter, network.availableVolume(sourceNode), result[sourceNode - 1]);
			if (targetNode == AirNetwork.AMBIENT_NODE) exported = SignedDyadic.of(carried);
			else {
				result[targetNode - 1] += carried;
				gasModel.absolutePressure(targetAfter, network.availableVolume(targetNode), result[targetNode - 1]);
			}
		}
		SignedDyadic after = SignedDyadic.sum(result);
		SignedDyadic roundoff = after.subtract(before).subtract(imported).add(exported);
		ThermalAuditLedger auditAfter = audit.ledger().withExternalSourceEnergy(imported)
				.withExternalSinkEnergy(exported).withRoundoff(roundoff);
		return () -> {
			nodeEnergy = result;
			audit.replace(auditAfter);
			revision++;
		};
	}

	private void requireGasNode(int nodeId, AirNetwork network) {
		if (!hasGasEnergy(network.nodeCount()) || nodeId <= 0 || nodeId > nodeEnergy.length) {
			throw new IllegalStateException("Gas thermal state is not bound to the current topology");
		}
	}

	public record GasRemapPlan(
			long baseRevision, double[] nodeEnergy, SignedDyadic sourceEnergy, SignedDyadic sinkEnergy,
			SignedDyadic mechanicalWork, SignedDyadic roundoff
	) {
		public GasRemapPlan { nodeEnergy = nodeEnergy.clone(); }
		@Override public double[] nodeEnergy() { return nodeEnergy.clone(); }
	}

	public SignedDyadic exactGasEnergy() { return SignedDyadic.sum(nodeEnergy); }
}
