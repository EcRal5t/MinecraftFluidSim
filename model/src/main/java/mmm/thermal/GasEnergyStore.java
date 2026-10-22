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
	public record PhaseState(double absolutePressurePa,double temperatureK,double heatCapacityJPerK) { }
	/** Phase preparation reads the same N/U caloric state once for both pressure and heat response. */
	public PhaseState phaseState(int nodeId,AirNetwork network) {
		requireGasNode(nodeId,network);var inventory=network.inventory(nodeId);
		if(inventory.isEmpty())return new PhaseState(gasModel.absolutePressure(inventory,network.availableVolume(nodeId),
				nodeEnergy[nodeId-1]),insertionTemperatureK,0);
		var thermal=nodeState(nodeId,network);
		return new PhaseState(gasModel.absolutePressure(inventory,network.availableVolume(nodeId),thermal),
				thermal.temperatureK(),thermal.heatCapacityJPerK());
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

	public double density(int nodeId, AirNetwork network) {
		requireGasNode(nodeId, network);
		return gasModel.density(network.inventory(nodeId), network.availableVolume(nodeId));
	}

	public GasThermalModel.HydrodynamicState hydrodynamicState(int nodeId, AirNetwork network) {
		requireGasNode(nodeId, network);
		if (network.inventory(nodeId).isEmpty())
			return gasModel.hydrodynamicState(network.inventory(nodeId), network.availableVolume(nodeId), nodeEnergy[nodeId - 1]);
		return gasModel.hydrodynamicState(network.inventory(nodeId), network.availableVolume(nodeId),
				nodeState(nodeId, network));
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

	/** One thermal closure per node; flow and its positive donor bound share these values. */
	public AirNetwork.NodeThermodynamics thermodynamics(AirNetwork network) {
		double[] pressure = new double[nodeEnergy.length + 1], density = new double[pressure.length],
				ratio = new double[pressure.length], storage = new double[pressure.length];
		density[0] = network.density(0) * GasRegistry.STANDARD_TEMPERATURE_K / insertionTemperatureK;
		ratio[0] = GasRegistry.STANDARD_TEMPERATURE_K / insertionTemperatureK;
		var ambient = network.registry().standardAmbientInventory();
		double ambientEnergy = gasModel.internalEnergyAtTemperature(ambient, insertionTemperatureK);
		double ambientGamma = gasModel.hydrodynamicState(ambient, 1, ambientEnergy).isentropicExponent();
		for (int id : network.nodeIds()) {
			GasInventory inventory = network.inventory(id);
			if (inventory.isEmpty()) {
				pressure[id] = -GasRegistry.STANDARD_PRESSURE_PA;
				storage[id] = network.availableVolume(id) * ratio[0] / (ambientGamma * GasRegistry.STANDARD_PRESSURE_PA);
				continue;
			}
			var thermal = nodeState(id, network);
			var hydro = gasModel.hydrodynamicState(inventory, network.availableVolume(id), thermal);
			pressure[id] = hydro.absolutePressurePa() - GasRegistry.STANDARD_PRESSURE_PA;
			density[id] = hydro.densityKgPerCubicMeter(); ratio[id] = network.standardVolume(id) / network.availableVolume(id);
			storage[id] = network.standardVolume(id) / hydro.adiabaticBulkModulusPa();
		}
		return new AirNetwork.NodeThermodynamics() {
			public double gaugePressure(int id) { return pressure[id]; }
			public double density(int id) { return density[id]; }
			public double standardVolumeRatio(int id) { return ratio[id]; }
			public double pressureStorage(int id) { return storage[id]; }
			public double maximumOutflowFraction(int id) { return gasModel.maximumOutflowFraction(network.inventory(id), nodeEnergy[id - 1], network.availableVolume(id)); }
			public AirNetwork.PortThermodynamics portThermodynamics(int first, int second) {
				return gasModel.portThermodynamics(network, first, second, nodeEnergy, insertionTemperatureK);
			}
		};
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
			SignedDyadic sourceEnergy, SignedDyadic sinkEnergy
	) {
		return planRemap(oldNetwork, inventories, availableVolumes, mixedEnergy, inheritedAvailableVolumes,
				sourceEnergy, sinkEnergy, nodeEnergy, null, revision, null);
	}
	/** Topology remapping carries already worked source energies; receivers only mix their inventories. */
	GasRemapPlan planRemapWithSourceWork(AirNetwork oldNetwork,List<GasInventory> inventories,double[] volumes,
	                                  double[] mixed,SignedDyadic source,SignedDyadic sink,SignedDyadic work){
		java.util.Objects.requireNonNull(work,"Gas source work is missing");
		return planRemap(oldNetwork,inventories,volumes,mixed,volumes,source,sink,nodeEnergy,null,revision,work);
	}

	GasRemapPlan planRemapAfterPhase(AirNetwork network, int node, double energy,
			ThermalAuditLedger afterPhase, List<GasInventory> inventories, double[] volumes,
			double[] mixed, double[] inherited, SignedDyadic source, SignedDyadic sink) {
		requireGasNode(node, network);
		double[] baseline = nodeEnergy.clone(); baseline[node - 1] = energy;
		return planRemap(network, inventories, volumes, mixed, inherited, source, sink,
				baseline, afterPhase, Math.addExact(revision, 1), null);
	}

	/** The phase inventory and its topology successor publish together from the original gas revision. */
	public GasRemapPlan planRemapAfterInventoryBatch(AirNetwork network, InventoryEnergyPlan phase,
			ThermalAuditLedger afterPhase, List<GasInventory> inventories, double[] volumes,
			double[] mixed, double[] inherited, SignedDyadic source, SignedDyadic sink) {
		requireInventoryEnergyCurrent(phase, network);
		return planRemap(network, inventories, volumes, mixed, inherited, source, sink,
				phase.energies, afterPhase, revision, null);
	}
	public void requireRemapAfterInventoryBatchCurrent(GasRemapPlan remap, InventoryEnergyPlan phase,
			AirNetwork network, ThermalAuditLedger afterPhase) {
		requireInventoryEnergyCurrent(phase, network);
		if (remap == null || remap.owner != this || remap.baseRevision != revision || remap.beforeAudit != afterPhase)
			throw new IllegalStateException("Phase batch gas remap source changed");
	}

	private GasRemapPlan planRemap(AirNetwork oldNetwork, List<GasInventory> inventories,
			double[] availableVolumes, double[] mixedEnergy, double[] inheritedAvailableVolumes,
			SignedDyadic sourceEnergy, SignedDyadic sinkEnergy, double[] baselineEnergy,
			ThermalAuditLedger beforeAudit, long baseRevision,SignedDyadic preparedSourceWork) {
		if (!hasGasEnergy(oldNetwork.nodeCount()) || inventories == null
				|| inventories.size() != availableVolumes.length || inventories.size() != mixedEnergy.length
				|| inventories.size() != inheritedAvailableVolumes.length
				|| sourceEnergy == null || sinkEnergy == null) {
			throw new IllegalArgumentException("Gas thermal remap plan is incompatible");
		}
		double[] result = mixedEnergy.clone();
		for (int node = 0; node < result.length; node++) {
			boolean vacuum = inventories.get(node).isEmpty();
			if (!(Double.isFinite(availableVolumes[node]) && (availableVolumes[node] > 0.0 || vacuum && availableVolumes[node] == 0.0))
					|| !(Double.isFinite(inheritedAvailableVolumes[node]) && (inheritedAvailableVolumes[node] > 0.0 || vacuum && inheritedAvailableVolumes[node] == 0.0))
					|| !Double.isFinite(result[node])) {
				throw new IllegalArgumentException("Gas thermal remap produced an invalid node: node=" + node
						+ ", available=" + availableVolumes[node] + ", inherited=" + inheritedAvailableVolumes[node]
						+ ", energy=" + result[node] + ", vacuum=" + inventories.get(node).isEmpty());
			}
			if (inventories.get(node).isEmpty()) {
				if (result[node] != 0.0) throw new IllegalArgumentException("Vacuum remap carries internal energy");
				result[node] = 0.0;
				continue;
			}
			if (preparedSourceWork!=null||Double.doubleToRawLongBits(inheritedAvailableVolumes[node])
					== Double.doubleToRawLongBits(availableVolumes[node])) {
				gasModel.absolutePressure(inventories.get(node), availableVolumes[node], result[node]);
			} else {
				result[node] = gasModel.compressAdiabatically(inventories.get(node), result[node],
						inheritedAvailableVolumes[node], availableVolumes[node]).finalInternalEnergy();
			}
		}
		SignedDyadic oldGas = SignedDyadic.sum(baselineEnergy);
		SignedDyadic mixed = preparedSourceWork==null?SignedDyadic.sum(mixedEnergy):SignedDyadic.ZERO;
		SignedDyadic finalGas = SignedDyadic.sum(result);
		SignedDyadic source = sourceEnergy, sink = sinkEnergy;
		SignedDyadic work = preparedSourceWork==null?finalGas.subtract(mixed):preparedSourceWork;
		SignedDyadic roundoff = finalGas.subtract(oldGas).subtract(source).add(sink).subtract(work);
		ThermalAuditLedger afterAudit = (beforeAudit == null ? audit.ledger() : beforeAudit).withExternalSourceEnergy(source).withExternalSinkEnergy(sink)
				.withMechanicalWork(work).withRoundoff(roundoff);
		afterAudit.expectedInventory();
		return new GasRemapPlan(this, baseRevision, result, source, sink, work, roundoff, beforeAudit, beforeAudit == null ? null : afterAudit);
	}

	void requireCanCommitRemap(GasRemapPlan plan) {
		if (plan == null) throw new IllegalArgumentException("Gas thermal remap plan is missing");
		if (plan.owner != this || plan.baseRevision() != revision || plan.beforeAudit() != null && audit.ledger() != plan.beforeAudit()) {
			throw new IllegalStateException("Gas energy changed after remap planning");
		}
	}

	void requireRemapAfterPhaseCurrent(GasRemapPlan plan, ThermalAuditLedger afterPhase) {
		if (plan == null || plan.owner != this || plan.baseRevision() != Math.addExact(revision, 1) || plan.beforeAudit() != afterPhase)
			throw new IllegalStateException("Gas energy successor topology is stale");
	}

	void commitRemap(GasRemapPlan plan) {
		requireCanCommitRemap(plan);
		ThermalAuditLedger after = plan.afterAudit() != null ? plan.afterAudit() : audit.ledger()
				.withExternalSourceEnergy(plan.sourceEnergy()).withExternalSinkEnergy(plan.sinkEnergy())
				.withMechanicalWork(plan.mechanicalWork()).withRoundoff(plan.roundoff());
		nodeEnergy = plan.nodeEnergy();
		audit.replace(after);
		revision++;
	}

	/** Builds the already region-validated all-node gas-energy removal plan. */
	GasVentPlan planVent() {
		SignedDyadic removed = SignedDyadic.sum(nodeEnergy);
		return new GasVentPlan(nodeEnergy.length, removed, audit.ledger().withExternalSinkEnergy(removed));
	}

	/** Publishes an already validated all-node gas-energy removal using assignments only. */
	void requireCanCommitVent(GasVentPlan plan) {
		if (plan == null || nodeEnergy.length != plan.nodeCount()
				|| !SignedDyadic.sum(nodeEnergy).equals(plan.removedEnergy())) {
			throw new IllegalStateException("Gas energy changed after vent planning");
		}
	}

	/** Publishes an already validated all-node gas-energy removal using assignments only. */
	void commitVent(GasVentPlan plan) {
		requireCanCommitVent(plan);
		nodeEnergy = new double[plan.nodeCount()];
		audit.replace(plan.auditAfter());
		revision++;
	}

	public record GasVentPlan(int nodeCount, SignedDyadic removedEnergy, ThermalAuditLedger auditAfter) { }

	/** All ports share the donor blowdown integral and paired outgoing/incoming energy. */
	public AirNetwork.TransferCommit planFlow(AirNetwork.FlowBatch batch, AirNetwork network) {
		double[] result = flowEnergy(batch, network);
		SignedDyadic imported = SignedDyadic.ZERO, exported = SignedDyadic.ZERO;
		for (var transfer : batch.transfers()) {
			if (transfer.source() == 0) imported = imported.add(SignedDyadic.of(gasModel.enthalpy(transfer.moved(), insertionTemperatureK)));
			if (transfer.target() == 0) {
				long total = 0;
				for (var other : batch.transfers()) if (other.source() == transfer.source()) total += other.moved().standardVolumeNanos();
				double carried = donorFlowEnergy(batch, transfer.source(), network)
						* ((double) transfer.moved().standardVolumeNanos() / total);
				exported = exported.add(SignedDyadic.of(carried));
			}
		}
		var prepared = planInventoryEnergy(network, batch.nodeIds(), batch.inventories(), result,
				imported, exported, SignedDyadic.ZERO);
		return () -> commitInventoryEnergy(prepared);
	}

	/** Integrates the well-mixed donor's isentropic blowdown before mixing all incoming enthalpy. */
	private double donorFlowEnergy(AirNetwork.FlowBatch batch, int id, AirNetwork network) {
		int index = batch.nodeIds().indexOf(id);
		var source = network.inventory(id); var retained = batch.retained().get(index);
		if (source == retained || source.equals(retained)) return 0;
		double temperature = temperature(id, network), volume = network.availableVolume(id);
		double initial = gasModel.internalEnergyAtTemperature(retained, temperature);
		double fraction = (double) retained.standardVolumeNanos() / source.standardVolumeNanos();
		double finalEnergy = gasModel.compressAdiabatically(retained, initial, volume * fraction, volume).finalInternalEnergy();
		return nodeEnergy[id - 1] - finalEnergy;
	}

	private double[] flowEnergy(AirNetwork.FlowBatch batch, AirNetwork network) {
		double[] result = nodeEnergy.clone(), carried = new double[nodeEnergy.length + 1];
		long[] amount = new long[carried.length];
		for (var transfer : batch.transfers()) if (transfer.source() != 0)
			amount[transfer.source()] += transfer.moved().standardVolumeNanos();
		for (int id : batch.nodeIds()) if (amount[id] != 0) {
			carried[id] = donorFlowEnergy(batch, id, network); result[id - 1] -= carried[id];
		}
		for (var transfer : batch.transfers()) {
			double energy = transfer.source() == 0 ? gasModel.enthalpy(transfer.moved(), insertionTemperatureK)
					: carried[transfer.source()] * ((double) transfer.moved().standardVolumeNanos() / amount[transfer.source()]);
			if (transfer.target() != 0) result[transfer.target() - 1] += energy;
		}
		return result;
	}

	/** Final-state thermal preflight for fixed-node flow or a simultaneous gas/liquid phase transaction. */
	public InventoryEnergyPlan planInventoryEnergy(AirNetwork network, List<Integer> ids,
			List<GasInventory> inventories, double[] energies, SignedDyadic externalSource,
			SignedDyadic externalSink, SignedDyadic internalEnergyTransfer) {
		return planInventoryEnergy(network, ids, inventories, energies, externalSource, externalSink, internalEnergyTransfer, true);
	}
	public InventoryEnergyPlan planPhaseInventoryEnergy(AirNetwork network, List<Integer> ids,
			List<GasInventory> inventories, double[] energies, SignedDyadic internalEnergyTransfer) {
		return planInventoryEnergy(network, ids, inventories, energies, SignedDyadic.ZERO, SignedDyadic.ZERO, internalEnergyTransfer, false);
	}
	private InventoryEnergyPlan planInventoryEnergy(AirNetwork network, List<Integer> ids,
			List<GasInventory> inventories, double[] energies, SignedDyadic externalSource,
			SignedDyadic externalSink, SignedDyadic internalEnergyTransfer, boolean checkVolume) {
		if (!ids.equals(network.nodeIds()) || inventories.size() != ids.size() || energies.length != nodeEnergy.length)
			throw new IllegalArgumentException("Gas energy batch must cover the current nodes");
		double[] result = energies.clone();
		ThermalStateFunction.State[] finalStates = new ThermalStateFunction.State[result.length];
		GasInventory[] finalInventories = new GasInventory[result.length];
		for (int i = 0; i < ids.size(); i++) {
			int id = ids.get(i); var inventory = inventories.get(i); finalInventories[id - 1] = inventory;
			if (inventory.isEmpty()) {
				if (result[id - 1] != 0) throw new IllegalArgumentException("Vacuum phase inventory carries energy");
				if (checkVolume) gasModel.absolutePressure(inventory, network.availableVolume(id), result[id - 1]);
			}
			else {
				var thermal = gasModel.state(inventory, result[id - 1]); finalStates[id - 1] = thermal;
				if (checkVolume) gasModel.absolutePressure(inventory, network.availableVolume(id), thermal);
			}
		}
		SignedDyadic roundoff = SignedDyadic.sum(result).subtract(SignedDyadic.sum(nodeEnergy))
				.subtract(internalEnergyTransfer).subtract(externalSource).add(externalSink);
		var before = audit.ledger();
		var after = before.withExternalSourceEnergy(externalSource).withExternalSinkEnergy(externalSink).withRoundoff(roundoff);
		return new InventoryEnergyPlan(this, revision, network.revision(), result, roundoff, externalSource, externalSink, before, after, finalStates, finalInventories);
	}
	public void requireInventoryEnergyCurrent(InventoryEnergyPlan plan, AirNetwork network) {
		if (plan == null || plan.owner != this || plan.revision != revision || plan.networkRevision != network.revision()
				|| plan.beforeAudit != audit.ledger()) throw new IllegalStateException("Gas energy batch source changed");
	}
	/** Caller preflights the shared transaction and publishes its combined thermal ledger separately. */
	public void commitInventoryEnergyValues(InventoryEnergyPlan plan) {
		nodeEnergy = plan.energies; state = plan.states; stateInventory = plan.inventories;
		stateEnergy = plan.energyBits;
		revision++;
	}
	public void commitInventoryEnergy(InventoryEnergyPlan plan) {
		if (plan.owner != this || plan.revision != revision || plan.beforeAudit != audit.ledger())
			throw new IllegalStateException("Gas energy batch source changed");
		commitInventoryEnergyValues(plan); audit.replace(plan.afterAudit);
	}
	public static final class InventoryEnergyPlan {
		private final GasEnergyStore owner; private final long revision, networkRevision; private final double[] energies;
		private final SignedDyadic roundoff, externalSource, externalSink;
		private final ThermalAuditLedger beforeAudit, afterAudit;
		private final ThermalStateFunction.State[] states; private final GasInventory[] inventories; private final long[] energyBits;
		private InventoryEnergyPlan(GasEnergyStore owner, long revision, long networkRevision, double[] energies,
				SignedDyadic roundoff, SignedDyadic externalSource, SignedDyadic externalSink,
				ThermalAuditLedger beforeAudit, ThermalAuditLedger afterAudit, ThermalStateFunction.State[] states, GasInventory[] inventories) {
			this.states = states; this.inventories = inventories;
			this.energyBits = new long[energies.length];
			for (int i = 0; i < energies.length; i++) energyBits[i] = Double.doubleToRawLongBits(energies[i]);
			this.owner = owner; this.revision = revision; this.networkRevision = networkRevision; this.energies = energies;
			this.roundoff = roundoff; this.externalSource = externalSource; this.externalSink = externalSink;
			this.beforeAudit = beforeAudit; this.afterAudit = afterAudit;
		}
		public SignedDyadic roundoff() { return roundoff; }
		public SignedDyadic externalSource() { return externalSource; }
		public SignedDyadic externalSink() { return externalSink; }
		public ThermalAuditLedger beforeAudit() { return beforeAudit; }
		public ThermalAuditLedger afterAudit() { return afterAudit; }
	}

	private void requireGasNode(int nodeId, AirNetwork network) {
		if (!hasGasEnergy(network.nodeCount()) || nodeId <= 0 || nodeId > nodeEnergy.length) {
			throw new IllegalStateException("Gas thermal state is not bound to the current topology");
		}
	}

	public static final class GasRemapPlan {
		private final GasEnergyStore owner;
		private final long baseRevision;
		private final double[] nodeEnergy;
		private final SignedDyadic sourceEnergy, sinkEnergy, mechanicalWork, roundoff;
		private final ThermalAuditLedger beforeAudit, afterAudit;

		private GasRemapPlan(GasEnergyStore owner, long baseRevision, double[] nodeEnergy,
				SignedDyadic sourceEnergy, SignedDyadic sinkEnergy, SignedDyadic mechanicalWork,
				SignedDyadic roundoff, ThermalAuditLedger beforeAudit, ThermalAuditLedger afterAudit) {
			this.owner = owner; this.baseRevision = baseRevision; this.nodeEnergy = nodeEnergy;
			this.sourceEnergy = sourceEnergy; this.sinkEnergy = sinkEnergy;
			this.mechanicalWork = mechanicalWork; this.roundoff = roundoff;
			this.beforeAudit = beforeAudit; this.afterAudit = afterAudit;
		}
		public long baseRevision() { return baseRevision; }
		public double[] nodeEnergy() { return nodeEnergy.clone(); }
		public double nodeEnergy(int nodeId) { return nodeEnergy[nodeId - 1]; }
		public SignedDyadic sourceEnergy() { return sourceEnergy; }
		public SignedDyadic sinkEnergy() { return sinkEnergy; }
		public SignedDyadic mechanicalWork() { return mechanicalWork; }
		public SignedDyadic roundoff() { return roundoff; }
		private ThermalAuditLedger beforeAudit() { return beforeAudit; }
		private ThermalAuditLedger afterAudit() { return afterAudit; }
	}

	public SignedDyadic exactGasEnergy() { return SignedDyadic.sum(nodeEnergy); }
}
