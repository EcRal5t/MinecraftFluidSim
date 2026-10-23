package mmm.fluid.air;

import mmm.units.SignedDyadic;
import mmm.chemistry.ExactRational;
import java.math.BigInteger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Conservative gas region network with an isothermal equation of state and
 * finite-rate orifice transport. Thermal state may supply non-isothermal pressure, density and
 * a jointly validated energy-transfer commit without moving thermal truth into this owner.
 *
 * <p>Inventories are stable component groups with independent standard-state volume,
 * mass and optional species columns. Every quantum originates from the ambient reservoir,
 * so the total identity {@code sealed + vented == drawnFromAmbient} and the
 * per-component volume/species identities hold exactly
 * across node creation, injection, orifice transport, merging, splitting and
 * venting. Pressures are derived gauge pressures in solver units (pascal) with
 * ambient at exactly zero; ambient is id {@link #AMBIENT_NODE} with unbounded
 * inventory and never stores state. Persistence captures this runtime owner through the
 * thermal region payload; this type itself is not a wire format.</p>
 */
public final class AirNetwork {
	public interface NodeThermodynamics {
		double gaugePressure(int nodeId);
		double density(int nodeId);
		/** Standard volume per actual source volume, including the source temperature. */
		default double standardVolumeRatio(int nodeId) {
			return (REFERENCE_PRESSURE + gaugePressure(nodeId)) / REFERENCE_PRESSURE;
		}
		/** Standard-volume storage dVstd/dP at the actual thermal state (m³/Pa). */
		default double pressureStorage(int nodeId) { return Double.NaN; }
		/** Donor fraction whose outgoing enthalpy leaves positive-temperature inventory. */
		default double maximumOutflowFraction(int nodeId) { return Math.nextDown(1.0); }
		/** Actual candidate pressures, indexed in batch node order; null selects isothermal storage. */
		default PortThermodynamics portThermodynamics(int first, int second) { return null; }

	}

	/** Continuous two-cavity response; inventory encoding is applied once after this solve. */
	public record PortThermodynamics(int source, int target, double pressureDrop, double sourceDensity,
	                                 double sourceRatio, double maximumVolume, double quantum,
	                                 java.util.function.DoubleUnaryOperator pressureAfterTransfer) { }

	@FunctionalInterface
	public interface BatchTransferPlanner { TransferCommit plan(FlowBatch batch); }
	public record GasTransfer(int source, int target, GasInventory moved) { }
	public record FlowBatch(List<Integer> nodeIds, List<GasInventory> inventories, List<GasInventory> retained, List<GasTransfer> transfers) {
		public FlowBatch {
			nodeIds = List.copyOf(nodeIds); inventories = List.copyOf(inventories); retained = List.copyOf(retained); transfers = List.copyOf(transfers);
		}
	}

	/** A fully validated cross-owner commit. Implementations must only publish prepared values. */
	@FunctionalInterface
	public interface TransferCommit {
		void commitPrepared();
	}
	/** Reference (ambient) absolute pressure in Pa; gauge zero matches the solver convention. */
	public static final double REFERENCE_PRESSURE = 101_325.0;
	/** The ambient reservoir: gauge pressure zero, unbounded inventory, never stored as a node. */
	public static final int AMBIENT_NODE = 0;

	/** Converts cubic metres to exact nanovolume quanta. */
	public static long nanos(double cubicMeters) { return AirNode.toNanos(cubicMeters); }

	/** Converts nanovolume quanta to cubic metres. */
	public static double cubicMeters(long nanos) { return nanos * AirNode.NANO_VOLUME; }

	private TreeMap<Integer, AirNode> nodes = new TreeMap<>();
	private List<AirEdge> edges = new ArrayList<>();
	private final GasRegistry registry;
	private final double ambientDensity;
	private int nextNodeId = 1;
	private GasAuditLedger escapedSink = GasAuditLedger.EMPTY;
	private GasAuditLedger externalSource = GasAuditLedger.EMPTY;
	private long revision;
	private PhaseMutationPlan lastCommittedPhasePlan;
	private int transferClampEvents;

	public AirNetwork() { this(BuiltinGasDefinitions.createRegistry()); }

	public AirNetwork(GasRegistry registry) {
		this.registry = Objects.requireNonNull(registry, "registry");
		this.ambientDensity = registry.createAmbient(AirNode.toNanos(1.0)).totalMass();
		if (!(Double.isFinite(ambientDensity) && ambientDensity > 0.0)) {
			throw new IllegalArgumentException("Ambient gas density is invalid");
		}
	}

	public GasRegistry registry() { return registry; }
	/** Monotonic token over authoritative nodes, inventories, volumes, edges and audit columns. */
	public long revision() { return revision; }

	/** Immutable runtime continuation; only this owner can create a validated instance. */
	public static final class RuntimeSnapshot {
		private final GasRegistry registry; private final List<RuntimeNode> nodes; private final List<AirEdge> edges;
		private final int nextNodeId, transferClampEvents; private final GasAuditLedger source, sink; private final long revision;
		private RuntimeSnapshot(AirNetwork owner) {
			registry = owner.registry; nodes = owner.nodes.entrySet().stream().map(e -> new RuntimeNode(e.getKey(),e.getValue().availableVolume(),e.getValue().inventory())).toList();
			edges = List.copyOf(owner.edges); nextNodeId = owner.nextNodeId; transferClampEvents = owner.transferClampEvents;
			source = owner.externalSource; sink = owner.escapedSink; revision = owner.revision;
		}
	}
	private record RuntimeNode(int id, double volume, GasInventory inventory) { }
	public RuntimeSnapshot runtimeSnapshot() { return new RuntimeSnapshot(this); }
	public static AirNetwork fromRuntimeSnapshot(RuntimeSnapshot state) {
		AirNetwork result = new AirNetwork(state.registry);
		for (var node : state.nodes) result.nodes.put(node.id(),new AirNode(node.volume(),node.inventory()));
		result.edges = new ArrayList<>(state.edges); result.nextNodeId = state.nextNodeId; result.transferClampEvents = state.transferClampEvents;
		result.externalSource = state.source; result.escapedSink = state.sink; result.revision = state.revision;
		return result;
	}

	/** Creates a sealed cavity node; the initial component inventory is booked as drawn from ambient. */
	public int addNode(double availableVolume, double standardVolume) {
		if (nodes.size() == GasLimits.MAX_NODES_PER_REGION)
			throw new IllegalArgumentException("Gas creation exceeds the regional node budget");
		int id = nextNodeId, nextAfter = Math.addExact(nextNodeId, 1);
		GasInventory created = ambientInventory(AirNode.toNanos(standardVolume));
		AirNode node = new AirNode(availableVolume, created);
		TreeMap<Integer, AirNode> replacement = new TreeMap<>(nodes);
		replacement.put(id, node);
		commitRemapNodes(planNodeMutation(replacement, new ArrayList<>(edges), nextAfter, created, GasInventory.EMPTY));
		return id;
	}

	public boolean hasNode(int nodeId) { return nodeId != AMBIENT_NODE && nodes.containsKey(nodeId); }
	public List<Integer> nodeIds() { return new ArrayList<>(nodes.keySet()); }
	public int nodeCount() { return nodes.size(); }

	/** Uses the transport's integer equilibrium limit; slow openings remain active until that limit is zero. */
	public boolean hasPressureDrivenFlow(java.util.function.IntToDoubleFunction pressure) {
		return hasPressureDrivenFlow(pressure, null);
	}

	public boolean hasPressureDrivenFlow(NodeThermodynamics thermodynamics) {
		Objects.requireNonNull(thermodynamics, "thermodynamics");
		return hasPressureDrivenFlow(thermodynamics::gaugePressure, thermodynamics);
	}

	private boolean hasPressureDrivenFlow(java.util.function.IntToDoubleFunction pressure, NodeThermodynamics thermodynamics) {
		Objects.requireNonNull(pressure, "pressure");
		var values = new java.util.HashMap<Integer, Double>();
		values.put(AMBIENT_NODE, 0.0);
		for (int id : nodes.keySet()) values.put(id, pressure.applyAsDouble(id));
		for (AirEdge edge : edges) {
			double first = values.get(edge.firstNode()), second = values.get(edge.secondNode());
			if (!Double.isFinite(first) || !Double.isFinite(second)) return true;
			if (first == second) continue;
			int source = first > second ? edge.firstNode() : edge.secondNode();
			int target = first > second ? edge.secondNode() : edge.firstNode();
			double sourceStorage = source == 0 ? Double.POSITIVE_INFINITY : flowStorage(source, thermodynamics, values.get(source));
			double targetStorage = target == 0 ? Double.POSITIVE_INFINITY : flowStorage(target, thermodynamics, values.get(target));
			double equilibriumVolume = Math.abs(first - second) / (1 / sourceStorage + 1 / targetStorage);
			double quantum = source == 0 ? AirNode.NANO_VOLUME : minimumTransportVolume(inventory(source));
			if (thermodynamics != null && source != 0 && thermodynamics.maximumOutflowFraction(source) <= 0) continue;
			if (equilibriumVolume >= quantum) return true;
		}
		return false;
	}

	public double availableVolume(int nodeId) { return sealed(nodeId).availableVolume(); }
	public void updateAvailableVolume(int nodeId, double availableVolume) {
		sealed(nodeId).setAvailableVolume(availableVolume);
		revision++;
	}

	public long standardVolumeNanos(int nodeId) { return sealed(nodeId).standardVolumeNanos(); }
	public double standardVolume(int nodeId) { return sealed(nodeId).standardVolume(); }
	public GasInventory inventory(int nodeId) { return sealed(nodeId).inventory(); }

	/** Creates an ambient inventory without mutating this network; used by topology planning. */
	public GasInventory ambientInventory(long standardVolumeNanos) { return registry.createAmbientInterval(externalSource.standardVolumeNanos(), standardVolumeNanos); }

	/** Preview for an exact phase-boundary take; no gas or ledger state is changed. */
	public GasInventory.Split previewComponentTake(int nodeId, String componentId, long standardVolumeNanos) {
		return sealed(nodeId).inventory().splitComponent(componentId, standardVolumeNanos);
	}

	/** Preview for a phase take whose mass/residual quantum is independent of gas nanos rounding. */
	public GasInventory.Split previewComponentTake(
			int nodeId, String componentId, long standardVolumeNanos,
			long mass, long massResidual
	) {
		return sealed(nodeId).inventory().splitComponent(
				componentId, standardVolumeNanos, mass, massResidual);
	}

	/** Books material entering the gas owner from another phase and merges all independent columns. */
	public void commitPhaseSource(int nodeId, GasInventory addition) {
		commitPhaseMutation(planPhaseSource(nodeId, addition));
	}

	/** Prepares node and cumulative audit columns before the other phase publishes any edits. */
	public PhaseMutationPlan planPhaseSource(int nodeId, GasInventory addition) {
		if (addition == null || addition.isEmpty()) throw new IllegalArgumentException("Gas phase source is empty");
		registry.requireCompatible(addition);
		AirNode node = sealed(nodeId);
		try {
			GasInventory after = node.inventory().merge(addition);
			return planPhaseMutation(node, after, addition, GasInventory.EMPTY);
		} catch (GasInventory.RepresentationException capacity) {
			throw new PhaseCapacityExceededException(capacity);
		}
	}

	/** Books material leaving the gas owner into another phase after an exact preview. */
	public void commitPhaseSink(int nodeId, GasInventory expectedRemoval) {
		commitPhaseMutation(planPhaseSink(nodeId, expectedRemoval));
	}

	/** Prepares the exact component take and its complete cumulative sink ledger. */
	public PhaseMutationPlan planPhaseSink(int nodeId, GasInventory expectedRemoval) {
		if (expectedRemoval == null || expectedRemoval.components().size() != 1) {
			throw new IllegalArgumentException("Gas phase sink must name one component portion");
		}
		GasComponentAmount component = expectedRemoval.components().getFirst();
		AirNode node = sealed(nodeId);
		GasInventory.Split split = node.inventory().splitComponent(component.componentId(),
				component.standardVolumeNanos(), component.massUnits(), component.massResidualUnits());
		if (!split.first().equals(expectedRemoval)) throw new IllegalStateException("Gas phase sink preview changed");
		try {
			return planPhaseMutation(node, split.second(), GasInventory.EMPTY, split.first());
		} catch (GasInventory.RepresentationException capacity) {
			throw new PhaseCapacityExceededException(capacity);
		}
	}

	public void requirePhaseMutationCurrent(PhaseMutationPlan plan) {
		if (plan == null || plan.owner != this || plan.baseRevision != revision)
			throw new IllegalStateException("Gas phase mutation plan is stale or belongs to another network");
	}

	private PhaseMutationPlan planPhaseMutation(AirNode node, GasInventory after, GasInventory source, GasInventory sink) {
		TransferMutation mutation = planTransferMutation(liveMassColumns(), node, after, null, null, source, sink);
		long total = 0L;
		for (AirNode current : nodes.values()) total = GasInventory.addColumn(total,
				(current == node ? after : current.inventory()).standardVolumeNanos(), "regional standard volume");
		return new PhaseMutationPlan(this, revision, after, mutation);
	}

	/** Publishes the precomputed columns without repeating inventory or ledger arithmetic. */
	public void commitPhaseMutation(PhaseMutationPlan plan) {
		requirePhaseMutationCurrent(plan);
		commitTransferMutation(plan.mutation);
		lastCommittedPhasePlan = plan;
	}

	/**
	 * Explicitly injects or removes inventory (developer presets and tests). Increases are
	 * booked as drawn from ambient and decreases as vented, keeping the ledger identity exact.
	 */
	public void setStandardVolume(int nodeId, double standardVolume) {
		AirNode node = sealed(nodeId);
		long target = AirNode.toNanos(standardVolume);
		long current = node.standardVolumeNanos();
		if (target == current) return;
		GasInventory source = GasInventory.EMPTY, sink = GasInventory.EMPTY;
		GasInventory after;
		if (target > current) {
			source = ambientInventory(target - current);
			after = node.inventory().merge(source);
		} else {
			GasInventory.Split split = node.inventory().splitTotal(target);
			after = split.first(); sink = split.second();
		}
		TreeMap<Integer, AirNode> replacement = new TreeMap<>(nodes);
		replacement.put(nodeId, new AirNode(node.availableVolume(), after));
		commitRemapNodes(planNodeMutation(replacement, new ArrayList<>(edges), nextNodeId, source, sink));
	}

	/** Adds an orifice edge; parallel openings between the same pair of nodes sum their areas. */
	public void addOrMergeEdge(int firstNode, int secondNode, double areaSquareMeters, double dischargeCoefficient) {
		if (firstNode == secondNode) throw new IllegalArgumentException("An edge needs two distinct nodes");
		if (firstNode != AMBIENT_NODE) sealed(firstNode);
		if (secondNode != AMBIENT_NODE) sealed(secondNode);
		AirEdge incoming = new AirEdge(firstNode, secondNode, areaSquareMeters, dischargeCoefficient);
		int existing = findEdge(firstNode, secondNode);
		if (existing >= 0) {
			AirEdge edge = edges.get(existing);
			double combinedArea = edge.areaSquareMeters() + areaSquareMeters;
			double combinedDischarge = (edge.dischargeCoefficient() * edge.areaSquareMeters()
					+ dischargeCoefficient * areaSquareMeters) / combinedArea;
			edges.set(existing, new AirEdge(firstNode, secondNode, combinedArea, combinedDischarge));
			revision++;
			return;
		}
		edges.add(incoming);
		revision++;
	}

	public boolean removeEdge(int firstNode, int secondNode) {
		int existing = findEdge(firstNode, secondNode);
		if (existing < 0) return false;
		edges.remove(existing);
		revision++;
		return true;
	}

	private int findEdge(int firstNode, int secondNode) {
		for (int i = 0; i < edges.size(); i++) {
			AirEdge edge = edges.get(i);
			boolean samePair = (edge.firstNode() == firstNode && edge.secondNode() == secondNode)
					|| (edge.firstNode() == secondNode && edge.secondNode() == firstNode);
			if (samePair) return i;
		}
		return -1;
	}

	public List<AirEdge> edges() { return List.copyOf(edges); }

	public int edgeCount() { return edges.size(); }

	/**
	 * Isothermal state equation: {@code p = P0 * (V0 / V - 1)}. Ambient is exactly zero with
	 * no state. A finite inventory in zero volume is a singular candidate and is rejected rather
	 * than repaired by changing its pressure.
	 */
	public double gaugePressure(int nodeId) {
		if (nodeId == AMBIENT_NODE) return 0.0;
		AirNode node = sealed(nodeId);
		if (node.inventory().isEmpty()) return -REFERENCE_PRESSURE;
		return rawGaugePressure(node);
	}

	/** Read-only derivation of the same pressure; safe for Query/probe consumers. */
	public double observedGaugePressure(int nodeId) {
		if (nodeId == AMBIENT_NODE) return 0.0;
		AirNode node = sealed(nodeId);
		if (node.inventory().isEmpty()) return -REFERENCE_PRESSURE;
		return rawGaugePressure(node);
	}

	private double rawGaugePressure(AirNode node) {
		if (!(node.availableVolume() > 0.0)) {
			throw new IllegalStateException("Finite gas inventory has no available volume");
		}
		double pressure = REFERENCE_PRESSURE * (node.standardVolume() / node.availableVolume() - 1.0);
		if (!Double.isFinite(pressure)) throw new IllegalStateException("Derived gas pressure is not finite");
		return pressure;
	}

	/** Isothermal density from the same inventory and available volume as pressure. */
	public double density(int nodeId) {
		if (nodeId == AMBIENT_NODE) return ambientDensity;
		AirNode node = sealed(nodeId);
		if (node.inventory().isEmpty()) return 0.0;
		if (!(node.availableVolume() > 0.0)) {
			throw new IllegalStateException("Finite gas inventory has no available volume");
		}
		double density = node.inventory().density(node.availableVolume());
		if (!(Double.isFinite(density) && density > 0.0)) {
			throw new IllegalStateException("Derived gas density is invalid");
		}
		return density;
	}

	/**
	 * Quasi-static finite-orifice flow with frozen conductance and simultaneous inventory allocation.
	 * Thermal flow solves the committed inventory/energy EOS; isothermal flow uses linear storage.
	 * This models cavity equilibration; acoustic waves and resolved jets belong to the carrier solver.
	 */
	public void stepEdges(double dt) { stepEdges(dt,id->true); }
	public void stepEdges(double dt,java.util.function.IntPredicate activeNode) {
		stepEdges(dt, new NodeThermodynamics() {
			@Override public double gaugePressure(int id) { return AirNetwork.this.gaugePressure(id); }
			@Override public double density(int id) { return AirNetwork.this.density(id); }
		}, batch -> () -> { },activeNode);
	}

	public void stepEdges(double dt, NodeThermodynamics thermodynamics, BatchTransferPlanner thermalPlanner) { stepEdges(dt,thermodynamics,thermalPlanner,id->true); }
	public void stepEdges(double dt, NodeThermodynamics thermodynamics, BatchTransferPlanner thermalPlanner,
			java.util.function.IntPredicate activeNode) {
		if (!(Double.isFinite(dt) && dt > 0)) throw new IllegalArgumentException("Gas flow dt must be positive and finite");
		Objects.requireNonNull(thermodynamics, "thermodynamics"); Objects.requireNonNull(thermalPlanner, "thermalPlanner");
		Objects.requireNonNull(activeNode,"Active gas node selection");
		if (edges.isEmpty()) return;
		List<AirEdge> edges = this.edges.stream().filter(edge->(edge.firstNode()==0 || activeNode.test(edge.firstNode()))
				&& (edge.secondNode()==0 || activeNode.test(edge.secondNode()))).sorted(java.util.Comparator
				.comparingInt((AirEdge edge) -> Math.min(edge.firstNode(), edge.secondNode()))
				.thenComparingInt(edge -> Math.max(edge.firstNode(), edge.secondNode()))).toList();
		if(edges.isEmpty()) return;
		if (thermodynamics.portThermodynamics(edges.getFirst().firstNode(), edges.getFirst().secondNode()) != null) {
			for (int pass = 0; pass < 2; pass++) for (int j = 0; j < edges.size(); j++) {
				AirEdge edge = edges.get(pass == 0 ? j : edges.size() - 1 - j);
				var port = thermodynamics.portThermodynamics(edge.firstNode(), edge.secondNode());
				double amount = GasOrificeFlow.solveThermalPort(dt * .5, edge, port);
				if (amount == 0) continue;
				var ids = new ArrayList<Integer>(2);
				if (edge.firstNode() != 0) ids.add(edge.firstNode());
				if (edge.secondNode() != 0) ids.add(edge.secondNode());
				ids.sort(Integer::compare);
				var index = new java.util.HashMap<Integer, Integer>(); index.put(0, 0);
				for (int i = 0; i < ids.size(); i++) index.put(ids.get(i), i + 1);
				var batch = flowBatch(ids, index, new int[]{index.get(edge.firstNode())},
						new int[]{index.get(edge.secondNode())},
						new double[]{port.source() == edge.firstNode() ? amount : -amount}, thermodynamics);
				commitPortFlow(batch, thermalPlanner);
			}
			return;
		}
		List<Integer> ids = nodeIds(); int count = ids.size();
		var index = new java.util.HashMap<Integer, Integer>(); index.put(0, 0);
		double[] pressure = new double[count + 1], storage = new double[count + 1], density = new double[count + 1], ratio = new double[count + 1];
		density[0] = thermodynamics.density(0); ratio[0] = thermodynamics.standardVolumeRatio(0);
		for (int i = 1; i <= count; i++) {
			int id = ids.get(i - 1); index.put(id, i);
			pressure[i] = thermodynamics.gaugePressure(id);
			storage[i] = flowStorage(id, thermodynamics, pressure[i]);
			density[i] = thermodynamics.density(id); ratio[i] = thermodynamics.standardVolumeRatio(id);
		}
		int[] first = new int[edges.size()], second = new int[edges.size()];
		for (int e = 0; e < edges.size(); e++) { first[e] = index.get(edges.get(e).firstNode()); second[e] = index.get(edges.get(e).secondNode()); }
		double[] transported = GasOrificeFlow.solve(dt, edges, first, second, pressure, storage, density, ratio);
		FlowBatch batch = flowBatch(ids, index, first, second, transported, thermodynamics);
		commitFlowBatch(batch, thermalPlanner);
	}

	/** Local exact-column closure prepares both owners before either publishes the port. */
	private void commitPortFlow(FlowBatch batch, BatchTransferPlanner thermalPlanner) {
		if (batch.transfers().isEmpty()) return;
		var transfer = batch.transfers().getFirst();
		var source = transfer.source() == 0 ? transfer.moved() : GasInventory.EMPTY;
		var sink = transfer.target() == 0 ? transfer.moved() : GasInventory.EMPTY;
		var before = batch.nodeIds().stream().map(this::inventory).toList();
		for (var inventory : batch.inventories()) registry.requireCompatible(inventory);
		GasRegionAudit.requireClosure(GasRegionAudit.aggregate(before), GasRegionAudit.aggregate(batch.inventories()),
				GasAuditLedger.of(source), GasAuditLedger.of(sink));
		var sourceAfter = externalSource.add(source); var sinkAfter = escapedSink.add(sink);
		sourceAfter.standardVolumeNanos(); sinkAfter.standardVolumeNanos();
		long expectedRevision = revision;
		TransferCommit thermal = Objects.requireNonNull(thermalPlanner.plan(batch), "Gas thermal port plan");
		if (revision != expectedRevision) throw new IllegalStateException("Gas port source changed after planning");
		thermal.commitPrepared();
		for (int i = 0; i < batch.nodeIds().size(); i++) nodes.get(batch.nodeIds().get(i)).setInventory(batch.inventories().get(i));
		externalSource = sourceAfter; escapedSink = sinkAfter; lastCommittedPhasePlan = null; revision++;
	}

	private void commitFlowBatch(FlowBatch batch, BatchTransferPlanner thermalPlanner) {
		if (batch.transfers().isEmpty()) return;
		ArrayList<GasInventory> sources = new ArrayList<>(), sinks = new ArrayList<>();
		for (var transfer : batch.transfers()) {
			if (transfer.source() == 0) sources.add(transfer.moved());
			if (transfer.target() == 0) sinks.add(transfer.moved());
		}
		InventoryBatchPlan gas = planInventoryBatch(batch.nodeIds(), batch.inventories(), GasRegionAudit.aggregate(sources), GasRegionAudit.aggregate(sinks));
		TransferCommit thermal = Objects.requireNonNull(thermalPlanner.plan(batch), "Gas thermal flow plan");
		requireInventoryBatchCurrent(gas);
		thermal.commitPrepared(); commitInventoryBatch(gas);
	}

	private FlowBatch flowBatch(List<Integer> ids, java.util.Map<Integer, Integer> index,
	                           int[] first, int[] second, double[] transported, NodeThermodynamics thermodynamics) {
		ArrayList<ArrayList<Integer>> outgoing = new ArrayList<>();
		for (int i = 0; i <= ids.size(); i++) outgoing.add(new ArrayList<>());
		for (int e = 0; e < transported.length; e++) if (transported[e] != 0)
			outgoing.get(transported[e] > 0 ? first[e] : second[e]).add(e);
		GasInventory[] retained = new GasInventory[ids.size() + 1]; retained[0] = GasInventory.EMPTY;
		for (int i = 1; i <= ids.size(); i++) retained[i] = inventory(ids.get(i - 1));
		ArrayList<GasTransfer> transfers = new ArrayList<>();
		long sourceCursor = externalSource.standardVolumeNanos();
		for (int i = 0; i <= ids.size(); i++) {
			var ports = outgoing.get(i); if (ports.isEmpty()) continue;
			int id = i == 0 ? 0 : ids.get(i - 1);
			if (i == 0) {
				for (int edge : ports) {
					long amount = quantizedVolume(Math.abs(transported[edge]));
					if (amount == 0) continue;
					int target = first[edge] == 0 ? second[edge] : first[edge];
					var moved = registry.createAmbientInterval(sourceCursor, amount); sourceCursor = Math.addExact(sourceCursor, amount);
					transfers.add(new GasTransfer(0, ids.get(target - 1), moved));
				}
				continue;
			}
			GasInventory donor = retained[i]; if (donor.isEmpty()) continue;
			double requested = 0; for (int edge : ports) requested += Math.abs(transported[edge]);
			double fraction = Math.min(Math.nextDown(1.0), thermodynamics.maximumOutflowFraction(id));
			if (!(Double.isFinite(fraction) && fraction >= 0)) throw new IllegalArgumentException("Gas donor thermal fraction is invalid");
			double scale = Math.min(1, cubicMeters(donor.standardVolumeNanos()) * fraction / requested);
			long[] weights = new long[ports.size() + 1]; long total = 1L << 52, used = 0;
			for (int j = 0; j < ports.size(); j++) {
				double share = Math.abs(transported[ports.get(j)]) * scale / cubicMeters(donor.standardVolumeNanos());
				weights[j + 1] = Math.min(total - 1 - used, Math.max(0, (long) Math.floor(share * total)));
				used += weights[j + 1];
			}
			weights[0] = total - used;
			var shares = donor.partitionTransport(weights, externalSource.standardVolumeNanos() ^ Long.rotateLeft(escapedSink.standardVolumeNanos(), 23)); retained[i] = shares.getFirst();
			for (int j = 0; j < ports.size(); j++) {
				var moved = shares.get(j + 1); if (moved.isEmpty()) continue;
				int edge = ports.get(j), target = first[edge] == i ? second[edge] : first[edge];
				transfers.add(new GasTransfer(id, target == 0 ? 0 : ids.get(target - 1), moved));
			}
		}
		var donorRemainders = List.copyOf(java.util.Arrays.asList(java.util.Arrays.copyOfRange(retained, 1, retained.length)));
		for (var transfer : transfers) if (transfer.target() != 0) {
			int target = index.get(transfer.target()); retained[target] = retained[target].merge(transfer.moved());
		}
		var after = java.util.Arrays.asList(java.util.Arrays.copyOfRange(retained, 1, retained.length));
		return new FlowBatch(ids, after, donorRemainders, transfers);
	}

	private double flowStorage(int node, NodeThermodynamics thermal, double pressure) {
		double value = thermal == null ? Double.NaN : thermal.pressureStorage(node);
		if (Double.isNaN(value)) {
			double absolute = REFERENCE_PRESSURE + pressure;
			value = absolute > 0 ? standardVolume(node) / absolute : availableVolume(node) / REFERENCE_PRESSURE;
		}
		if (!(Double.isFinite(value) && value > 0)) throw new IllegalArgumentException("Gas pressure storage is invalid");
		return value;
	}

	private static double minimumTransportVolume(GasInventory inventory) {
		double minimum = Double.POSITIVE_INFINITY;
		for (var component : inventory.components()) {
			double nanosForMass = Math.ceil((double) component.standardVolumeNanos() / component.massUnits());
			minimum = Math.min(minimum, nanosForMass * inventory.standardVolumeNanos() / component.standardVolumeNanos());
		}
		return minimum * AirNode.NANO_VOLUME;
	}

	private static long quantizedVolume(double volume) {
		if (!(volume >= 0 && Double.isFinite(volume))) throw new IllegalArgumentException("Gas transported volume is invalid");
		return volume >= cubicMeters(Long.MAX_VALUE) ? Long.MAX_VALUE : (long) Math.floor(volume / AirNode.NANO_VOLUME);
	}

	/** Fixed-node inventory replacement shared by flow and batched phase changes. */
	public InventoryBatchPlan planInventoryBatch(List<Integer> ids, List<GasInventory> inventories,
	                                            GasInventory source, GasInventory sink) {
		return planInventoryBatch(ids, inventories, GasAuditLedger.of(source), GasAuditLedger.of(sink));
	}
	public InventoryBatchPlan planInventoryBatch(List<Integer> ids, List<GasInventory> inventories,
	                                            GasAuditLedger source, GasAuditLedger sink) {
		if (inventories.size() != ids.size()) throw new IllegalArgumentException("Gas inventory batch dimensions differ");
		ArrayList<GasInventory> before = new ArrayList<>(); TreeMap<Integer, AirNode> replacement = new TreeMap<>(nodes);
		int previous = 0;
		for (int i = 0; i < ids.size(); i++) {
			if (ids.get(i) <= previous) throw new IllegalArgumentException("Gas inventory nodes must be unique and ordered");
			previous = ids.get(i);
			AirNode old = sealed(ids.get(i)); before.add(old.inventory()); registry.requireCompatible(inventories.get(i));
			replacement.put(ids.get(i), new AirNode(old.availableVolume(), inventories.get(i)));
		}
		GasRegionAudit.requireClosure(GasRegionAudit.aggregate(before), GasRegionAudit.aggregate(inventories),
				source, sink);
		return new InventoryBatchPlan(this, revision, replacement, externalSource.add(source), escapedSink.add(sink));
	}
	public void requireInventoryBatchCurrent(InventoryBatchPlan plan) {
		if (plan == null || plan.owner != this || plan.revision != revision) throw new IllegalStateException("Gas inventory batch source changed");
	}
	public void commitInventoryBatch(InventoryBatchPlan plan) {
		requireInventoryBatchCurrent(plan); nodes = plan.nodes; externalSource = plan.source; escapedSink = plan.sink;
		lastCommittedPhasePlan = null; revision++;
	}
	public static final class InventoryBatchPlan {
		private final AirNetwork owner; private final long revision; private final TreeMap<Integer, AirNode> nodes;
		private final GasAuditLedger source, sink;
		private InventoryBatchPlan(AirNetwork owner, long revision, TreeMap<Integer, AirNode> nodes, GasAuditLedger source, GasAuditLedger sink) {
			this.owner = owner; this.revision = revision; this.nodes = nodes; this.source = source; this.sink = sink;
		}
	}

	/**
	 * Merges source cavities into the target. Inventories sum exactly; edges touching a source
	 * are rewired to the target, dropping self loops and summing parallel areas.
	 */
	public void mergeNodes(int targetNodeId, int... sourceNodeIds) {
		if (sourceNodeIds.length == 0) throw new IllegalArgumentException("A merge needs at least one source");
		for (int sourceNodeId : sourceNodeIds) {
			if (sourceNodeId == targetNodeId) throw new IllegalArgumentException("Cannot merge a node into itself");
			sealed(sourceNodeId);
		}
		for (int first = 0; first < sourceNodeIds.length; first++) {
			for (int second = first + 1; second < sourceNodeIds.length; second++) {
				if (sourceNodeIds[first] == sourceNodeIds[second]) {
					throw new IllegalArgumentException("Cannot merge the same source node twice");
				}
			}
		}
		AirNode target = sealed(targetNodeId);
		GasInventory merged = target.inventory();
		for (int sourceNodeId : sourceNodeIds) merged = merged.merge(sealed(sourceNodeId).inventory());
		TreeMap<Integer, AirNode> replacement = new TreeMap<>(nodes);
		replacement.put(targetNodeId, new AirNode(target.availableVolume(), merged));
		for (int sourceNodeId : sourceNodeIds) {
			replacement.remove(sourceNodeId);
		}
		commitRemapNodes(planNodeMutation(replacement, rewiredEdges(targetNodeId, sourceNodeIds), nextNodeId,
				GasInventory.EMPTY, GasInventory.EMPTY));
	}

	/**
	 * Splits a cavity into children with the given available volumes. The first child keeps
	 * the source id and its edges; later children get fresh ids. Shares are proportional to
	 * the available volumes; each component's largest share receives its mass-column remainder.
	 */
	public int[] splitNode(int sourceNodeId, double... childAvailableVolumes) {
		AirNode source = sealed(sourceNodeId);
		if (childAvailableVolumes.length < 2) {
			throw new IllegalArgumentException("A split needs at least two children");
		}
		if ((long) nodes.size() + childAvailableVolumes.length - 1 > GasLimits.MAX_NODES_PER_REGION)
			throw new IllegalArgumentException("Gas split exceeds the regional node budget");
		int nextAfter = Math.addExact(nextNodeId, childAvailableVolumes.length - 1);
		boolean positiveVolume = false;
		var weights = new ArrayList<ExactRational>(childAvailableVolumes.length);
		for (double volume : childAvailableVolumes) {
			if (!(volume >= 0.0) || !Double.isFinite(volume)) {
				throw new IllegalArgumentException("Child available volumes must be finite and non-negative");
			}
			positiveVolume |= volume>0;
			SignedDyadic value = SignedDyadic.of(volume);
			weights.add(value.exponent()>=0 ? new ExactRational(value.significand().shiftLeft(value.exponent()),BigInteger.ONE)
					: new ExactRational(value.significand(),BigInteger.ONE.shiftLeft(-value.exponent())));
		}
		if (!positiveVolume) throw new IllegalArgumentException("A split needs positive available volume");
		List<GasInventory> partitions = source.inventory().partition(weights);
		TreeMap<Integer, AirNode> replacement = new TreeMap<>(nodes);
		int[] childIds = new int[childAvailableVolumes.length];
		for (int child = 0; child < childAvailableVolumes.length; child++) {
			int id = child == 0 ? sourceNodeId : nextNodeId + child - 1;
			replacement.put(id, new AirNode(childAvailableVolumes[child], partitions.get(child)));
			childIds[child] = id;
		}
		commitRemapNodes(planNodeMutation(replacement, new ArrayList<>(edges), nextAfter, GasInventory.EMPTY, GasInventory.EMPTY));
		return childIds;
	}

	/** Vents a cavity's whole inventory to ambient and removes it together with its edges. */
	public void ventNode(int nodeId) {
		AirNode node = sealed(nodeId);
		TreeMap<Integer, AirNode> replacement = new TreeMap<>(nodes);
		replacement.remove(nodeId);
		commitRemapNodes(planNodeMutation(replacement, rewiredEdges(-1, nodeId), nextNodeId,
				GasInventory.EMPTY, node.inventory()));
	}

	/**
	 * Rewires edges after node removal. A non-negative replacement adopts the edges of removed
	 * nodes (self loops dropped, parallel areas summed); a negative replacement drops every
	 * edge that touched a removed node, because the opening vanished with the node.
	 */
	private ArrayList<AirEdge> rewiredEdges(int replacement, int... removedNodes) {
		ArrayList<AirEdge> rewired = new ArrayList<>();
		for (AirEdge edge : edges) {
			boolean touchesRemoved = false;
			for (int removed : removedNodes) if (edge.touches(removed)) { touchesRemoved = true; break; }
			if (!touchesRemoved) { rewired.add(edge); continue; }
			if (replacement < 0) continue;
			int first = edge.firstNode();
			int second = edge.secondNode();
			for (int removed : removedNodes) {
				if (first == removed) first = replacement;
				if (second == removed) second = replacement;
			}
			if (first == second) continue;
			rewired.add(new AirEdge(first, second, edge.areaSquareMeters(), edge.dischargeCoefficient()));
		}
		return aggregateEdges(rewired);
	}

	/** Parallel openings retain their summed area and discharge-area product, independent of input order. */
	private static ArrayList<AirEdge> aggregateEdges(List<AirEdge> input) {
		var totals = new TreeMap<Long, EdgeTotals>();
		for (AirEdge edge : input) {
			int first = Math.min(edge.firstNode(), edge.secondNode()), second = Math.max(edge.firstNode(), edge.secondNode());
			long pair = ((long) first << 32) | Integer.toUnsignedLong(second);
			SignedDyadic area = SignedDyadic.of(edge.areaSquareMeters());
			SignedDyadic dischargeArea = area.multiply(SignedDyadic.of(edge.dischargeCoefficient()));
			EdgeTotals old = totals.get(pair);
			totals.put(pair, old == null ? new EdgeTotals(area, dischargeArea)
					: new EdgeTotals(old.area.add(area), old.dischargeArea.add(dischargeArea)));
		}
		var result = new ArrayList<AirEdge>(totals.size());
		for (var entry : totals.entrySet()) {
			EdgeTotals total = entry.getValue();
			int shift = total.dischargeArea.exponent() - total.area.exponent();
			BigInteger numerator = total.dischargeArea.significand(), denominator = total.area.significand();
			if (shift >= 0) numerator = numerator.shiftLeft(shift); else denominator = denominator.shiftLeft(-shift);
			result.add(new AirEdge((int) (entry.getKey() >>> 32), (int) (long) entry.getKey(),
					total.area.toDouble(), new ExactRational(numerator, denominator).toDouble()));
		}
		return result;
	}

	private record EdgeTotals(SignedDyadic area, SignedDyadic dischargeArea) { }

	/** Applies a topology plan whose explicit source/sink deltas close every exact component column. */
	public void remapNodes(
			double[] availableVolumes, List<GasInventory> inventories,
			GasInventory sourceDelta, GasInventory sinkDelta
	) {
		commitRemapNodes(planRemapNodes(availableVolumes, inventories, sourceDelta, sinkDelta, List.of()));
	}

	/** Completes all topology, closure and ledger calculations without changing the live network. */
	public RemapPlan planRemapNodes(
			double[] availableVolumes, List<GasInventory> inventories,
			GasInventory sourceDelta, GasInventory sinkDelta, List<AirEdge> replacementEdges
	) {
		if (sourceDelta == null || sinkDelta == null) throw new IllegalArgumentException("Gas remap plan is incomplete");
		return planRemapNodes(availableVolumes, inventories, GasAuditLedger.of(sourceDelta), GasAuditLedger.of(sinkDelta), replacementEdges);
	}

	/** Region source/sink summaries retain exact masses and the registry-wide component budget. */
	public RemapPlan planRemapNodes(
			double[] availableVolumes, List<GasInventory> inventories,
			GasAuditLedger sourceDelta, GasAuditLedger sinkDelta, List<AirEdge> replacementEdges
	) {
		return planRemapNodes(availableVolumes, inventories, sourceDelta, sinkDelta, replacementEdges, null);
	}

	/** Prepares the successor topology against the already planned phase columns. */
	public RemapPlan planRemapAfterPhase(PhaseMutationPlan phase, double[] availableVolumes,
			List<GasInventory> inventories, GasAuditLedger sourceDelta, GasAuditLedger sinkDelta,
			List<AirEdge> replacementEdges) {
		requirePhaseMutationCurrent(phase);
		return planRemapNodes(availableVolumes, inventories, sourceDelta, sinkDelta, replacementEdges, phase);
	}

	private RemapPlan planRemapNodes(double[] availableVolumes, List<GasInventory> inventories,
			GasAuditLedger sourceDelta, GasAuditLedger sinkDelta, List<AirEdge> replacementEdges,
			PhaseMutationPlan phase) {
		if (availableVolumes.length != inventories.size()) throw new IllegalArgumentException("Gas remap slots disagree");
		if (availableVolumes.length > GasLimits.MAX_NODES_PER_REGION)
			throw new IllegalArgumentException("Gas remap exceeds the regional node budget");
		if (sourceDelta == null || sinkDelta == null || replacementEdges == null) {
			throw new IllegalArgumentException("Gas remap plan is incomplete");
		}
		for (GasInventory inventory : inventories) registry.requireCompatible(inventory);
		for (var entry : sourceDelta.entries()) registry.requireCompatible(entry.componentId(), entry.species());
		for (var entry : sinkDelta.entries()) registry.requireCompatible(entry.componentId(), entry.species());
		TreeMap<Integer, AirNode> replacementNodes = new TreeMap<>();
		for (int slot = 0; slot < availableVolumes.length; slot++) {
			if (!(Double.isFinite(availableVolumes[slot]) && availableVolumes[slot] >= 0.0) || inventories.get(slot) == null) {
				throw new IllegalArgumentException("Invalid gas remap slot");
			}
			replacementNodes.put(slot + 1, new AirNode(availableVolumes[slot], inventories.get(slot)));
		}
		ArrayList<AirEdge> plannedEdges = new ArrayList<>(replacementEdges.size());
		for (AirEdge edge : replacementEdges) {
			if (edge == null || edge.firstNode() < AMBIENT_NODE || edge.secondNode() < AMBIENT_NODE
					|| edge.firstNode() > inventories.size() || edge.secondNode() > inventories.size()) {
				throw new IllegalArgumentException("Gas remap edge names an unknown node");
			}
			plannedEdges.add(edge);
		}
		ArrayList<GasInventory> baseline = new ArrayList<>(nodes.size());
		for (AirNode node : nodes.values()) baseline.add(phase != null && node == phase.mutation.sourceNode()
				? phase.inventoryAfter : node.inventory());
		GasAuditLedger beforeInventory = GasRegionAudit.aggregate(baseline);
		GasAuditLedger afterInventory = GasRegionAudit.aggregate(inventories);
		GasRegionAudit.requireClosure(beforeInventory, afterInventory, sourceDelta, sinkDelta);
		MassColumns before = massColumns(beforeInventory), after = massColumns(afterInventory);
		MassColumns source = massColumns(sourceDelta), sink = massColumns(sinkDelta);
		requireMassClosure(before, after, source, sink);
		return new RemapPlan(this, phase == null ? revision : Math.addExact(revision, 1), phase, replacementNodes, aggregateEdges(plannedEdges), inventories.size() + 1,
				(phase == null ? externalSource : phase.mutation.externalSourceAfter()).add(sourceDelta),
				(phase == null ? escapedSink : phase.mutation.escapedSinkAfter()).add(sinkDelta));
	}

	/** Checks a prepared successor before the phase performs its first write. */
	public void requireRemapAfterPhaseCurrent(RemapPlan remap, PhaseMutationPlan phase) {
		requirePhaseMutationCurrent(phase);
		if (remap == null || remap.owner != this || remap.parentPhase != phase || remap.baseRevision != Math.addExact(revision, 1))
			throw new IllegalStateException("Gas phase successor topology is stale");
	}

	/** Rejects a remap planned against another owner or an older authoritative generation. */
	public void requireCanCommitRemap(RemapPlan plan) {
		if (plan == null || plan.owner != this) {
			throw new IllegalArgumentException("Gas remap plan belongs to another network");
		}
		if (plan.baseRevision != revision) {
			throw new IllegalStateException("Gas network changed after remap planning");
		}
		if (plan.parentPhase != null && plan.parentPhase != lastCommittedPhasePlan)
			throw new IllegalStateException("Gas phase successor does not match the committed phase");
	}

	/** Publishes a previously validated remap using assignments only. */
	public void commitRemapNodes(RemapPlan plan) {
		requireCanCommitRemap(plan);
		nodes = plan.replacementNodes;
		edges = plan.replacementEdges;
		nextNodeId = plan.nextNodeId;

		externalSource = plan.externalSourceAfter;
		escapedSink = plan.escapedSinkAfter;
		lastCommittedPhasePlan = null;
		revision++;
	}

	/** Restores a previously validated complete gas generation into a fresh runtime network. */
	public void restoreNodes(
			double[] availableVolumes, List<GasInventory> inventories,
			GasAuditLedger source, GasAuditLedger sink
	) {
		if (!nodes.isEmpty() || !externalSource.entries().isEmpty() || !escapedSink.entries().isEmpty()) {
			throw new IllegalStateException("Gas restore requires a fresh network");
		}
		if (availableVolumes.length != inventories.size() || availableVolumes.length > GasLimits.MAX_NODES_PER_REGION || source == null || sink == null) {
			throw new IllegalArgumentException("Gas restore state is incomplete");
		}
		for (int slot = 0; slot < availableVolumes.length; slot++) {
			if (!(Double.isFinite(availableVolumes[slot]) && availableVolumes[slot] >= 0.0) || inventories.get(slot) == null) {
				throw new IllegalArgumentException("Invalid restored gas node");
			}
		}
		for (GasInventory inventory : inventories) registry.requireCompatible(inventory);
		for (GasAuditLedger.Entry entry : source.entries()) registry.requireCompatible(entry.componentId(), entry.species());
		for (GasAuditLedger.Entry entry : sink.entries()) registry.requireCompatible(entry.componentId(), entry.species());
		TreeMap<Integer, AirNode> replacement = new TreeMap<>();
		for (int slot = 0; slot < inventories.size(); slot++) {
			replacement.put(slot + 1, new AirNode(availableVolumes[slot], inventories.get(slot)));
		}
		if (!GasRegionAudit.closed(GasRegionAudit.aggregate(inventories), source, sink))
			throw new IllegalArgumentException("Restored gas audit does not close");
		nodes = replacement; edges = new ArrayList<>(); nextNodeId = inventories.size() + 1;
		externalSource = source; escapedSink = sink;

		revision++;
	}

	public long sealedInventoryNanos() {
		long sum = 0L;
		for (AirNode node : nodes.values()) sum = Math.addExact(sum, node.standardVolumeNanos());
		return sum;
	}

	/** Immutable aggregate of every sealed component column; ambient is deliberately absent. */
	public GasAuditLedger sealedInventory() { return GasRegionAudit.aggregate(nodes.values().stream().map(AirNode::inventory).toList()); }

	public long ventedNanos() { return escapedSink.standardVolumeNanos(); }

	public long drawnFromAmbientNanos() { return externalSource.standardVolumeNanos(); }

	public GasAuditLedger externalSource() { return externalSource; }
	public GasAuditLedger escapedSink() { return escapedSink; }

	/** Full exact-column audit used by persistence and validation boundaries. */
	public boolean auditClosed() {
		return GasRegionAudit.closed(sealedInventory(), externalSource, escapedSink);
	}

	public int transferClampEvents() { return transferClampEvents; }

	private int sealedId(int nodeId) {
		if (nodeId == AMBIENT_NODE || !nodes.containsKey(nodeId)) {
			throw new IllegalArgumentException("Unknown sealed gas node " + nodeId);
		}
		return nodeId;
	}

	private AirNode sealed(int nodeId) { return nodes.get(sealedId(nodeId)); }

	/** Stages public node edits and all fallible cumulative accounting before their first publication. */
	private RemapPlan planNodeMutation(TreeMap<Integer, AirNode> replacement, ArrayList<AirEdge> replacementEdges,
	                                 int nextAfter, GasInventory sourceDelta, GasInventory sinkDelta) {
		if (replacement.size() > GasLimits.MAX_NODES_PER_REGION)
			throw new IllegalArgumentException("Gas mutation exceeds the regional node budget");
		MassColumns before = liveMassColumns(), after = massColumns(replacement.values().stream().map(AirNode::inventory).toList());
		MassColumns source = massColumns(sourceDelta), sink = massColumns(sinkDelta);
		requireMassClosure(before, after, source, sink);
		return new RemapPlan(this, revision, null, replacement, replacementEdges, nextAfter,
				externalSource.add(sourceDelta), escapedSink.add(sinkDelta));
	}

	/** Performs every fallible ledger calculation before either gas or thermal state is published. */
	private TransferMutation planTransferMutation(
			MassColumns before,
			AirNode sourceNode, GasInventory sourceAfter,
			AirNode targetNode, GasInventory targetAfter,
			GasInventory sourceDelta, GasInventory sinkDelta
	) {
		MassColumns after = liveMassColumnsReplacing(sourceNode, sourceAfter, targetNode, targetAfter);
		MassColumns source = massColumns(sourceDelta), sink = massColumns(sinkDelta);
		requireMassClosure(before, after, source, sink);
		return new TransferMutation(sourceNode, sourceAfter, targetNode, targetAfter,
				externalSource.add(sourceDelta), escapedSink.add(sinkDelta));
	}

	private static void requireMassClosure(MassColumns before, MassColumns after, MassColumns source, MassColumns sink) {
		if (Math.addExact(after.mass(), sink.mass()) != Math.addExact(before.mass(), source.mass())
				|| Math.addExact(after.residual(), sink.residual()) != Math.addExact(before.residual(), source.residual())) {
			throw new IllegalStateException("Gas transfer mass counts do not close");
		}
	}

	private void commitTransferMutation(TransferMutation mutation) {
		if (mutation.sourceNode() != null) mutation.sourceNode().setInventory(mutation.sourceAfter());
		if (mutation.targetNode() != null) mutation.targetNode().setInventory(mutation.targetAfter());

		externalSource = mutation.externalSourceAfter();
		escapedSink = mutation.escapedSinkAfter();
		revision++;
	}

	private MassColumns liveMassColumns() {
		return liveMassColumnsReplacing(null, null, null, null);
	}

	private MassColumns liveMassColumnsReplacing(
			AirNode sourceNode, GasInventory sourceAfter,
			AirNode targetNode, GasInventory targetAfter
	) {
		return massColumns(nodes.values().stream().map(node ->
				node == sourceNode ? sourceAfter : node == targetNode ? targetAfter : node.inventory()).toList());
	}

	private static MassColumns massColumns(GasInventory inventory) {
		return massColumns(List.of(inventory));
	}

	private static MassColumns massColumns(List<GasInventory> inventories) {
		long mass = 0, residual = 0;
		for (GasInventory inventory : inventories) for (GasComponentAmount amount : inventory.components()) {
			mass = Math.addExact(mass, amount.massUnits());
			residual = Math.addExact(residual, amount.massResidualUnits());
		}
		return new MassColumns(mass, residual);
	}

	private static MassColumns massColumns(GasAuditLedger ledger) {
		long mass = 0, residual = 0;
		for (GasAuditLedger.Entry entry : ledger.entries()) {
			mass = Math.addExact(mass, entry.massUnits()); residual = Math.addExact(residual, entry.massResidualUnits());
		}
		return new MassColumns(mass, residual);
	}

	private record MassColumns(long mass, long residual) { }
	public static final class PhaseCapacityExceededException extends IllegalArgumentException {
		private PhaseCapacityExceededException(RuntimeException cause) {
			super("Gas phase inventory or cumulative audit exceeds its representation", cause);
		}
	}

	public static final class PhaseMutationPlan {
		private final AirNetwork owner;
		private final long baseRevision;
		private final GasInventory inventoryAfter;
		private final TransferMutation mutation;

		private PhaseMutationPlan(AirNetwork owner, long baseRevision, GasInventory inventoryAfter, TransferMutation mutation) {
			this.owner = owner; this.baseRevision = baseRevision;
			this.inventoryAfter = inventoryAfter; this.mutation = mutation;
		}
		public GasInventory inventoryAfter() { return inventoryAfter; }
	}

	public static final class RemapPlan {
		private final AirNetwork owner;
		private final long baseRevision;
		private final PhaseMutationPlan parentPhase;
		private final TreeMap<Integer, AirNode> replacementNodes;
		private final ArrayList<AirEdge> replacementEdges;
		private final int nextNodeId;

		private final GasAuditLedger externalSourceAfter;
		private final GasAuditLedger escapedSinkAfter;

		public GasInventory inventory(int nodeId) { return replacementNodes.get(nodeId).inventory(); }
		public double availableVolume(int nodeId) { return replacementNodes.get(nodeId).availableVolume(); }

		private RemapPlan(
				AirNetwork owner, long baseRevision, PhaseMutationPlan parentPhase, TreeMap<Integer, AirNode> replacementNodes,
				ArrayList<AirEdge> replacementEdges, int nextNodeId,

				GasAuditLedger externalSourceAfter, GasAuditLedger escapedSinkAfter
		) {
			this.owner = owner;
			this.baseRevision = baseRevision;
			this.parentPhase = parentPhase;
			this.replacementNodes = replacementNodes;
			this.replacementEdges = replacementEdges;
			this.nextNodeId = nextNodeId;

			this.externalSourceAfter = externalSourceAfter;
			this.escapedSinkAfter = escapedSinkAfter;
			externalSourceAfter.standardVolumeNanos(); escapedSinkAfter.standardVolumeNanos();
		}
	}
	private record TransferMutation(
			AirNode sourceNode, GasInventory sourceAfter,
			AirNode targetNode, GasInventory targetAfter,

			GasAuditLedger externalSourceAfter, GasAuditLedger escapedSinkAfter
	) {
		private TransferMutation {
			externalSourceAfter.standardVolumeNanos();
			escapedSinkAfter.standardVolumeNanos();
		}
	}
}
