package mmm.fluid.air;


import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/** Explicit initial gas inventories and their source/sink audit. */
public final class AirNetwork {
	/** Reference (ambient) absolute pressure in Pa; gauge zero matches the solver convention. */
	public static final double REFERENCE_PRESSURE = 101_325.0;
	/** The ambient reservoir: gauge pressure zero, unbounded inventory, never stored as a node. */
	public static final int AMBIENT_NODE = 0;

	/** Converts cubic metres to exact nanovolume quanta. */
	public static long nanos(double cubicMeters) { return AirNode.toNanos(cubicMeters); }

	/** Converts nanovolume quanta to cubic metres. */
	public static double cubicMeters(long nanos) { return nanos * AirNode.NANO_VOLUME; }

	private TreeMap<Integer, AirNode> nodes = new TreeMap<>();
	private final GasRegistry registry;
	private int nextNodeId = 1;
	private GasAuditLedger escapedSink = GasAuditLedger.EMPTY;
	private GasAuditLedger externalSource = GasAuditLedger.EMPTY;
	private GasAuditLedger phaseSource = GasAuditLedger.EMPTY;
	private GasAuditLedger phaseSink = GasAuditLedger.EMPTY;
	private long revision;

	public AirNetwork() { this(BuiltinGasDefinitions.createRegistry()); }

	public AirNetwork(GasRegistry registry) {
		this.registry = Objects.requireNonNull(registry, "registry");

	}

	public GasRegistry registry() { return registry; }
	/** Monotonic token over authoritative nodes, inventories, volumes and audit columns. */
	public long revision() { return revision; }

	/** Creates a sealed cavity node; the initial component inventory is booked as drawn from ambient. */
	public int addNode(double availableVolume, double standardVolume) {
		if (nodes.size() == GasLimits.MAX_NODES_PER_REGION)
			throw new IllegalArgumentException("Gas creation exceeds the regional node budget");
		int id = nextNodeId, nextAfter = Math.addExact(nextNodeId, 1);
		GasInventory created = ambientInventory(AirNode.toNanos(standardVolume));
		AirNode node = new AirNode(availableVolume, created);
		GasAuditLedger sourceAfter=externalSource.add(created);
		nodes.put(id,node);nextNodeId=nextAfter;externalSource=sourceAfter;revision++;
		return id;
	}

	public boolean hasNode(int nodeId) { return nodeId != AMBIENT_NODE && nodes.containsKey(nodeId); }
	public List<Integer> nodeIds() { return new ArrayList<>(nodes.keySet()); }
	public int nodeCount() { return nodes.size(); }

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

	/**
	 * Explicitly injects or removes inventory (developer presets and tests). Increases are
	 * booked as drawn from ambient and decreases as vented, keeping the ledger identity exact.
	 */
	public void setStandardVolume(int nodeId, double standardVolume, mmm.chemistry.ChemicalRegistry chemistry) {
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
			GasInventory.Split split = node.inventory().splitTotal(target, registry, chemistry);
			after = split.first(); sink = split.second();
			if (after.standardVolumeNanos() != target) throw new GasInventory.RepresentationException("Gas volume is below the representable component share");
		}
		GasAuditLedger sourceAfter=externalSource.add(source),sinkAfter=escapedSink.add(sink);
		nodes.put(nodeId,new AirNode(node.availableVolume(),after));
		externalSource=sourceAfter;escapedSink=sinkAfter;revision++;
	}

	/** Restores a previously validated complete gas generation into a fresh runtime network. */
	public void restoreNodes(
			double[] availableVolumes, List<GasInventory> inventories,
			GasAuditLedger source, GasAuditLedger sink, GasAuditLedger phaseSource, GasAuditLedger phaseSink
	) {
		if (!nodes.isEmpty() || !externalSource.entries().isEmpty() || !escapedSink.entries().isEmpty()) {
			throw new IllegalStateException("Gas restore requires a fresh network");
		}
		if (availableVolumes.length != inventories.size() || availableVolumes.length > GasLimits.MAX_NODES_PER_REGION || source == null || sink == null || phaseSource == null || phaseSink == null) {
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
		for (GasAuditLedger.Entry entry : phaseSource.entries()) registry.requireCompatible(entry.componentId(), entry.species());
		for (GasAuditLedger.Entry entry : phaseSink.entries()) registry.requireCompatible(entry.componentId(), entry.species());
		TreeMap<Integer, AirNode> replacement = new TreeMap<>();
		for (int slot = 0; slot < inventories.size(); slot++) {
			replacement.put(slot + 1, new AirNode(availableVolumes[slot], inventories.get(slot)));
		}
		if (!GasRegionAudit.closed(GasRegionAudit.aggregate(inventories), source.add(phaseSource), sink.add(phaseSink)))
			throw new IllegalArgumentException("Restored gas audit does not close");
		nodes = replacement; nextNodeId = inventories.size() + 1;
		externalSource = source; escapedSink = sink; this.phaseSource=phaseSource; this.phaseSink=phaseSink;

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
	public GasAuditLedger phaseSource() { return phaseSource; }
	public GasAuditLedger phaseSink() { return phaseSink; }

	/** Full exact-column audit used by persistence and validation boundaries. */
	public boolean auditClosed() {
		return GasRegionAudit.closed(sealedInventory(), externalSource.add(phaseSource), escapedSink.add(phaseSink));
	}


	private int sealedId(int nodeId) {
		if (nodeId == AMBIENT_NODE || !nodes.containsKey(nodeId)) {
			throw new IllegalArgumentException("Unknown sealed gas node " + nodeId);
		}
		return nodeId;
	}

	private AirNode sealed(int nodeId) { return nodes.get(sealedId(nodeId)); }

}
