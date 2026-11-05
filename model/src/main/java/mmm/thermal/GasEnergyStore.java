package mmm.thermal;

import mmm.fluid.air.AirNetwork;
import mmm.fluid.air.GasInventory;
import mmm.fluid.air.GasRegistry;
import mmm.units.SignedDyadic;

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
	private GasInventory[] stateInventory = new GasInventory[0];
	private long[] stateEnergy = new long[0];
	private ThermalStateFunction.State[] state = new ThermalStateFunction.State[0];

	public GasEnergyStore(
			GasThermalModel gasModel, double insertionTemperatureK, ThermalAuditState audit, AirNetwork airNetwork
	) {
		this(gasModel, insertionTemperatureK, audit, airNetwork, true);
	}

	/** Allocates restored ordinals; their authoritative energies are validated before adoption. */
	static GasEnergyStore restoreTarget(
			GasThermalModel gasModel, double insertionTemperatureK, ThermalAuditState audit, AirNetwork airNetwork
	) {
		return new GasEnergyStore(gasModel, insertionTemperatureK, audit, airNetwork, false);
	}

	private GasEnergyStore(
			GasThermalModel gasModel, double insertionTemperatureK, ThermalAuditState audit, AirNetwork airNetwork,
			boolean initializeEnergy
	) {
		this.gasModel = gasModel;
		this.insertionTemperatureK = insertionTemperatureK;
		this.audit = audit;
		if (gasModel != null) {
			this.nodeEnergy = new double[airNetwork.nodeCount()];
			for (int node = 1; initializeEnergy && node <= airNetwork.nodeCount(); node++) {
				nodeEnergy[node - 1] = gasModel.internalEnergyAtTemperature(
						airNetwork.inventory(node), insertionTemperatureK);
				gasModel.absolutePressure(airNetwork.inventory(node), airNetwork.availableVolume(node), nodeEnergy[node - 1]);
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

	/** Transfer ownership of the initial gas energies to per-cell storage. */
	public void clearNodeEnergies() { nodeEnergy = new double[0]; revision++; }

	double[] nodeEnergiesRaw() { return nodeEnergy; }

	void replaceNodeEnergies(double[] restored) {
		nodeEnergy = restored;
		revision++;
	}

	/** Prevalidates restored node energies against the restored inventory pressures. */
	void validateRestoredNodeEnergies(AirNetwork network, double[] restored) {
		prepareStateStorage();
		for (int node = 0; node < restored.length; node++) {
			GasInventory inventory = network.inventory(node + 1);
			if (inventory.isEmpty()) {
				gasModel.absolutePressure(inventory, network.availableVolume(node + 1), restored[node]);
				continue;
			}
			var thermal = gasModel.state(inventory, restored[node]);
			gasModel.absolutePressure(inventory, network.availableVolume(node + 1), thermal);
			state[node] = thermal; stateInventory[node] = inventory;
			stateEnergy[node] = Double.doubleToRawLongBits(restored[node]);
		}
	}

	public double nodeEnergy(int nodeId, AirNetwork network) {
		requireGasNode(nodeId, network);
		return nodeEnergy[nodeId - 1];
	}

	public double temperature(int nodeId, AirNetwork network) {
		requireGasNode(nodeId, network);
		if (network.inventory(nodeId).isEmpty()) return insertionTemperatureK;
		return nodeState(nodeId, network).temperatureK();
	}
	public double gaugePressure(int nodeId, AirNetwork network) {
		requireGasNode(nodeId, network);
		if (network.inventory(nodeId).isEmpty()) {
			return gasModel.absolutePressure(network.inventory(nodeId), network.availableVolume(nodeId),
					nodeEnergy[nodeId - 1]) - GasRegistry.STANDARD_PRESSURE_PA;
		}
		return gasModel.absolutePressure(network.inventory(nodeId), network.availableVolume(nodeId),
				nodeState(nodeId, network)) - GasRegistry.STANDARD_PRESSURE_PA;
	}


	/** Immutable inventory identity and the actual U bits determine the caloric state across all readers. */
	private ThermalStateFunction.State nodeState(int nodeId, AirNetwork network) {
		prepareStateStorage();
		int index = nodeId - 1; GasInventory inventory = network.inventory(nodeId);
		long energy = Double.doubleToRawLongBits(nodeEnergy[index]);
		if (stateInventory[index] != inventory || stateEnergy[index] != energy || state[index] == null) {
			state[index] = gasModel.state(inventory, nodeEnergy[index]);
			stateInventory[index] = inventory; stateEnergy[index] = energy;
		}
		return state[index];
	}

	private void prepareStateStorage() {
		if (state.length != nodeEnergy.length) {
			stateInventory = new GasInventory[nodeEnergy.length]; stateEnergy = new long[nodeEnergy.length];
			state = new ThermalStateFunction.State[nodeEnergy.length];
		}
	}

	private void requireGasNode(int nodeId, AirNetwork network) {
		if (!hasGasEnergy(network.nodeCount()) || nodeId <= 0 || nodeId > nodeEnergy.length) {
			throw new IllegalStateException("Gas thermal state is not bound to the current topology");
		}
	}

	public SignedDyadic exactGasEnergy() { return SignedDyadic.sum(nodeEnergy); }
}
