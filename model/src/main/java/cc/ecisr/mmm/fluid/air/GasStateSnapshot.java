package cc.ecisr.mmm.fluid.air;

import cc.ecisr.mmm.units.SignedDyadic;

import java.util.ArrayList;
import java.util.List;

/** Stable-handle-free gas topology/inventory handoff used at the region persistence boundary. */
public record GasStateSnapshot(
		int sizeX,
		int sizeY,
		int sizeZ,
		String registryFingerprint,
		String chemicalRegistryFingerprint,
		List<Node> nodes,
		int[] pieceNodeOrdinal,
		GasAuditLedger externalSource,
		GasAuditLedger escapedSink,
		SignedDyadic massRoundoff,
		SignedDyadic residualRoundoff
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
				|| pieceNodeOrdinal == null || pieceNodeOrdinal.length != pieceCount(sizeX, sizeY, sizeZ)
				|| externalSource == null || escapedSink == null || massRoundoff == null || residualRoundoff == null) {
			throw new IllegalArgumentException("Invalid gas state identity or dimensions");
		}
		nodes = List.copyOf(nodes);
		pieceNodeOrdinal = pieceNodeOrdinal.clone();
		int previousKey = -1;
		int[] minimumPiece = new int[nodes.size()];
		java.util.Arrays.fill(minimumPiece, Integer.MAX_VALUE);
		for (Node node : nodes) {
			if (node == null || node.topologyKey() <= previousKey) {
				throw new IllegalArgumentException("Gas nodes are not in canonical topology order");
			}
			previousKey = node.topologyKey();
		}
		for (int piece = 0; piece < pieceNodeOrdinal.length; piece++) {
			int ordinal = pieceNodeOrdinal[piece];
			if (ordinal < -1 || ordinal > nodes.size()) throw new IllegalArgumentException("Gas piece node ordinal is invalid");
			if (ordinal > 0) minimumPiece[ordinal - 1] = Math.min(minimumPiece[ordinal - 1], piece);
		}
		for (int ordinal = 0; ordinal < nodes.size(); ordinal++) {
			if (minimumPiece[ordinal] == Integer.MAX_VALUE || minimumPiece[ordinal] != nodes.get(ordinal).topologyKey()) {
				throw new IllegalArgumentException("Gas node topology key does not match complete piece membership");
			}
		}
		boolean species = nodes.stream().flatMap(node -> node.inventory().components().stream())
				.anyMatch(component -> !component.species().isEmpty())
				|| externalSource.entries().stream().anyMatch(entry -> !entry.species().isEmpty())
				|| escapedSink.entries().stream().anyMatch(entry -> !entry.species().isEmpty());
		if (species && chemicalRegistryFingerprint.isEmpty()) {
			throw new IllegalArgumentException("Gas species state requires a chemical registry fingerprint");
		}
	}

	@Override public int[] pieceNodeOrdinal() { return pieceNodeOrdinal.clone(); }

	/** Unique common owner for observation; absent or mixed cells return -1. */
	public int[] cellNodeOrdinal() {
		int[] result = new int[pieceNodeOrdinal.length / 8];
		for (int cell = 0; cell < result.length; cell++) {
			int owner = -1;
			for (int slot = 0; slot < 8; slot++) {
				int node = pieceNodeOrdinal[8 * cell + slot];
				if (node < 0) continue;
				if (owner < 0) owner = node;
				else if (owner != node) { owner = -1; break; }
			}
			result[cell] = owner;
		}
		return result;
	}

	/** Captures an already canonical runtime, preserving the inventory and thermal node order. */
	public static GasStateSnapshot capture(int sizeX, int sizeY, int sizeZ, int[] runtimePieceNodeId, AirNetwork network,
	                                      String chemicalRegistryFingerprint) {
		if (runtimePieceNodeId == null || runtimePieceNodeId.length != pieceCount(sizeX, sizeY, sizeZ) || network == null) {
			throw new IllegalArgumentException("Gas runtime topology is unavailable");
		}
		int nodeCount = network.nodeCount();
		int[] keys = new int[nodeCount + 1]; java.util.Arrays.fill(keys, Integer.MAX_VALUE);
		for (int piece = 0; piece < runtimePieceNodeId.length; piece++) {
			int node = runtimePieceNodeId[piece];
			if (node < -1 || node > nodeCount) throw new IllegalArgumentException("Gas runtime topology names an unknown node");
			if (node > 0) {
				keys[node] = Math.min(keys[node], piece);
			}
		}
		ArrayList<Node> nodes = new ArrayList<>(nodeCount);
		int previousKey = -1;
		for (int node = 1; node <= nodeCount; node++) {
			if (keys[node] == Integer.MAX_VALUE || keys[node] <= previousKey)
				throw new IllegalArgumentException("Gas runtime nodes are not in canonical minimum-piece order");
			nodes.add(new Node(keys[node], network.inventory(node))); previousKey = keys[node];
		}
		return new GasStateSnapshot(sizeX, sizeY, sizeZ, network.registry().fingerprint(), chemicalRegistryFingerprint,
				List.copyOf(nodes), runtimePieceNodeId, network.externalSource(), network.escapedSink(),
				network.massRoundoff(), network.residualRoundoff());
	}

	private static int pieceCount(int x, int y, int z) {
		if (x <= 0 || y <= 0 || z <= 0) throw new IllegalArgumentException("Gas dimensions must be positive");
		try { return Math.multiplyExact(8, Math.multiplyExact(Math.multiplyExact(x, y), z)); }
		catch (ArithmeticException overflow) { throw new IllegalArgumentException("Gas piece dimensions overflow", overflow); }
	}
}
