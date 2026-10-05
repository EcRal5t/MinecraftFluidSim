package cc.ecisr.mmm.fluid.air;

import cc.ecisr.mmm.units.SignedDyadic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Stable-handle-free gas topology/inventory handoff used at the region persistence boundary. */
public record GasStateSnapshot(
		int sizeX,
		int sizeY,
		int sizeZ,
		String registryFingerprint,
		String chemicalRegistryFingerprint,
		List<Node> nodes,
		int[] cellNodeOrdinal,
		int[] cellLineageOrdinal,
		GasAuditLedger externalSource,
		GasAuditLedger escapedSink,
		SignedDyadic massRoundoff,
		SignedDyadic residualRoundoff,
		GasGeometryState geometry
) {
	public record Node(int topologyKey, GasInventory inventory) {
		public Node {
			if (topologyKey < 0 || inventory == null) {
				throw new IllegalArgumentException("Invalid persisted gas node");
			}
		}
	}

	public GasStateSnapshot {
		if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0
				|| registryFingerprint == null || !registryFingerprint.matches("[0-9a-f]{64}")
				|| chemicalRegistryFingerprint == null
				|| !(chemicalRegistryFingerprint.isEmpty() || chemicalRegistryFingerprint.matches("[0-9a-f]{64}"))
				|| nodes == null || nodes.size() > GasLimits.MAX_NODES_PER_REGION
				|| cellNodeOrdinal == null
				|| cellNodeOrdinal.length != Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ)
				|| cellLineageOrdinal == null || cellLineageOrdinal.length != cellNodeOrdinal.length
				|| externalSource == null || escapedSink == null || massRoundoff == null || residualRoundoff == null) {
			throw new IllegalArgumentException("Invalid gas state identity or dimensions");
		}
		nodes = List.copyOf(nodes);
		int previousKey = -1;
		int[] minimumCell = new int[nodes.size()];
		java.util.Arrays.fill(minimumCell, Integer.MAX_VALUE);
		for (Node node : nodes) {
			if (node == null || node.topologyKey() <= previousKey) {
				throw new IllegalArgumentException("Gas nodes are not in canonical topology order");
			}
			previousKey = node.topologyKey();
		}
		for (int cell = 0; cell < cellNodeOrdinal.length; cell++) {
			int ordinal = cellNodeOrdinal[cell];
			int lineage = cellLineageOrdinal[cell];
			if (lineage < -1 || lineage > nodes.size() || ordinal > 0 && lineage != ordinal) {
				throw new IllegalArgumentException("Gas cell lineage ordinal is invalid or disagrees with membership");
			}
			if (ordinal < 0 || ordinal > nodes.size()) throw new IllegalArgumentException("Gas cell node ordinal is invalid");
			if (ordinal > 0) minimumCell[ordinal - 1] = Math.min(minimumCell[ordinal - 1], cell);
			if (geometry != null && (ordinal != 0 || lineage != -1))
				throw new IllegalArgumentException("Resolved gas membership must be carried by its complete spatial support");
		}
		if (geometry != null && geometry.nodeCount() != nodes.size()) throw new IllegalArgumentException("Resolved gas node cardinality mismatch");
		for (int ordinal = 0; ordinal < nodes.size(); ordinal++) {
			int key = geometry == null ? minimumCell[ordinal] : geometry.topologyKey(ordinal + 1);
			if (key != nodes.get(ordinal).topologyKey()) {
				throw new IllegalArgumentException("Gas node topology key does not match complete spatial membership");
			}
		}
		boolean species = nodes.stream().flatMap(node -> node.inventory().components().stream())
				.anyMatch(component -> !component.species().isEmpty())
				|| externalSource.entries().stream().anyMatch(entry -> !entry.species().isEmpty())
				|| escapedSink.entries().stream().anyMatch(entry -> !entry.species().isEmpty());
		if (species && chemicalRegistryFingerprint.isEmpty()) {
			throw new IllegalArgumentException("Gas species state requires a chemical registry fingerprint");
		}
		cellNodeOrdinal = cellNodeOrdinal.clone();
		cellLineageOrdinal = cellLineageOrdinal.clone();
	}
	public GasStateSnapshot(int sizeX, int sizeY, int sizeZ, String registryFingerprint, String chemicalRegistryFingerprint,
			List<Node> nodes, int[] cellNodeOrdinal, int[] cellLineageOrdinal, GasAuditLedger externalSource,
			GasAuditLedger escapedSink, SignedDyadic massRoundoff, SignedDyadic residualRoundoff) {
		this(sizeX, sizeY, sizeZ, registryFingerprint, chemicalRegistryFingerprint, nodes, cellNodeOrdinal, cellLineageOrdinal,
				externalSource, escapedSink, massRoundoff, residualRoundoff, null);
	}

	@Override public int[] cellNodeOrdinal() { return cellNodeOrdinal.clone(); }
	@Override public int[] cellLineageOrdinal() { return cellLineageOrdinal.clone(); }

	/** Runtime node ordinals follow the resolved geometry's canonical chamber order. */
	public static GasStateSnapshot captureResolved(int sizeX, int sizeY, int sizeZ, GasGeometryState geometry,
			AirNetwork network, String chemicalRegistryFingerprint) {
		if (geometry == null || network == null || geometry.nodeCount() != network.nodeCount())
			throw new IllegalArgumentException("Resolved gas topology and network disagree");
		var nodes = new ArrayList<Node>();
		for (int node = 1; node <= geometry.nodeCount(); node++) nodes.add(new Node(geometry.topologyKey(node), network.inventory(node)));
		int[] members = new int[Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ)], lineages = new int[members.length];
		java.util.Arrays.fill(lineages, -1);
		return new GasStateSnapshot(sizeX, sizeY, sizeZ, network.registry().fingerprint(), chemicalRegistryFingerprint,
				nodes, members, lineages, network.externalSource(), network.escapedSink(), network.massRoundoff(), network.residualRoundoff(), geometry);
	}

	/** Captures runtime node ids into canonical minimum-cell topology order. */
	public static GasStateSnapshot capture(int sizeX, int sizeY, int sizeZ, int[] runtimeCellNodeId,
	                                      int[] runtimeCellLineageId, AirNetwork network,
	                                      String chemicalRegistryFingerprint) {
		if (runtimeCellNodeId == null || runtimeCellLineageId == null
				|| runtimeCellLineageId.length != runtimeCellNodeId.length || network == null) {
			throw new IllegalArgumentException("Gas runtime topology is unavailable");
		}
		int nodeCount = network.nodeCount();
		int[] keys = new int[nodeCount + 1]; java.util.Arrays.fill(keys, Integer.MAX_VALUE);
		for (int cell = 0; cell < runtimeCellNodeId.length; cell++) {
			int node = runtimeCellNodeId[cell];
			if (node > 0) {
				if (node > nodeCount) throw new IllegalArgumentException("Gas runtime topology names an unknown node");
				keys[node] = Math.min(keys[node], cell);
			}
		}
		ArrayList<RuntimeNode> ordered = new ArrayList<>(nodeCount);
		for (int node = 1; node <= nodeCount; node++) {
			if (keys[node] == Integer.MAX_VALUE) throw new IllegalArgumentException("Gas runtime node has no owning cell");
			ordered.add(new RuntimeNode(node, keys[node], network.inventory(node)));
		}
		ordered.sort(Comparator.comparingInt(RuntimeNode::key));
		int[] ordinalByRuntime = new int[nodeCount + 1];
		ArrayList<Node> nodes = new ArrayList<>(nodeCount);
		for (int ordinal = 0; ordinal < ordered.size(); ordinal++) {
			RuntimeNode runtime = ordered.get(ordinal); ordinalByRuntime[runtime.nodeId()] = ordinal + 1;
			nodes.add(new Node(runtime.key(), runtime.inventory()));
		}
		int[] memberships = new int[runtimeCellNodeId.length];
		int[] lineages = new int[memberships.length];
		for (int cell = 0; cell < memberships.length; cell++) {
			int runtime = runtimeCellNodeId[cell]; memberships[cell] = runtime > 0 ? ordinalByRuntime[runtime] : 0;
			int lineage = runtimeCellLineageId[cell];
			if (lineage < -1 || lineage > nodeCount) throw new IllegalArgumentException("Gas runtime lineage names an unknown node");
			lineages[cell] = lineage > 0 ? ordinalByRuntime[lineage] : lineage;
		}
		return new GasStateSnapshot(sizeX, sizeY, sizeZ, network.registry().fingerprint(), chemicalRegistryFingerprint,
				List.copyOf(nodes), memberships, lineages, network.externalSource(), network.escapedSink(),
				network.massRoundoff(), network.residualRoundoff());
	}

	private record RuntimeNode(int nodeId, int key, GasInventory inventory) { }
}
