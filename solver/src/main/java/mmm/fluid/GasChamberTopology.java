package mmm.fluid;

import mmm.fluid.air.GasLimits;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

/** Low-Mach chambers join positive dry ports across full fixed faces; cut apertures retain finite flow. */
final class GasChamberTopology {
	record Chamber(int node, int minimumPiece, double geometricVolume, double volumeErrorBound) { }
	record Connection(int firstNode, int secondNode, double area) { }
	private final int[] pieceNodes;
	private final GasComplementGeometry geometry;
	private final List<Chamber> chambers;
	private final List<Connection> connections;

	private GasChamberTopology(GasComplementGeometry geometry, int[] pieceNodes, List<Chamber> chambers, List<Connection> connections) {
		this.geometry = geometry;
		this.pieceNodes = pieceNodes; this.chambers = List.copyOf(chambers); this.connections = List.copyOf(connections);
	}

	static GasChamberTopology capture(GasComplementGeometry geometry, boolean sealedExterior) {
		if (geometry == null) throw new IllegalArgumentException("Gas chamber geometry is missing");
		int[] parent = emptyParents(geometry);
		var pieces = geometry.pieces(); var ports = geometry.ports(); var exterior = geometry.exteriorPorts();
		for (var piece : pieces) parent[piece.id()] = piece.id();
		byte[] rank = new byte[parent.length];
		for (var port : ports) if (port.fullCutConnection()) join(parent, rank, port.first(), port.second());
		canonicalize(parent);
		boolean[] ambient = new boolean[parent.length];
		if (!sealedExterior) for (var port : exterior) if (port.fullCutConnection()) ambient[parent[port.piece()]] = true;
		int[] owners = new int[parent.length];
		for (var piece : pieces) if (!ambient[parent[piece.id()]]) owners[parent[piece.id()]] = -1;
		var roots = new ArrayList<Integer>(); int node = 0;
		for (int root = 0; root < owners.length; root++) if (owners[root] < 0) {
			if (++node > GasLimits.MAX_NODES_PER_REGION) throw new GasTopologyBlockedException("Gas chamber count exceeds its representation budget");
			owners[root] = node; roots.add(root);
		}
		int[] pieceNodes = new int[parent.length]; Arrays.fill(pieceNodes, -1);
		for (var piece : geometry.pieces()) pieceNodes[piece.id()] = owners[parent[piece.id()]];
		double[] volumes = new double[node + 1], volumeErrors = new double[node + 1];
		for (var piece : pieces) {
			int owner = pieceNodes[piece.id()];
			volumes[owner] += piece.volume(); volumeErrors[owner] += pieceError(piece) + Math.ulp(volumes[owner]);
		}
		var chambers = new ArrayList<Chamber>(node);
		for (int root : roots) {
			int owner = owners[root]; double volume = positive(volumes[owner], "Gas chamber volume");
			double error = volumeErrors[owner] + Math.ulp(volume);
			chambers.add(new Chamber(owner, root, volume, Math.nextUp(error)));
		}
		var areas = new TreeMap<Long, Double>();
		for (var port : ports) addArea(areas, pieceNodes[port.first()], pieceNodes[port.second()], port.area());
		if (!sealedExterior) for (var port : exterior) addArea(areas, 0, pieceNodes[port.piece()], port.area());
		var connections = new ArrayList<Connection>(areas.size());
		for (var entry : areas.entrySet()) connections.add(new Connection((int) (entry.getKey() >>> 32), (int) (long) entry.getKey(), positive(entry.getValue(), "Gas chamber port area")));
		return new GasChamberTopology(geometry, pieceNodes, chambers, connections);
	}
	private static void addArea(TreeMap<Long, Double> areas, int first, int second, double area) {
		if (first == second) return;
		int low = Math.min(first, second), high = Math.max(first, second);
		areas.merge((long) low << 32 | (high & 0xffffffffL), area, Double::sum);
	}
    private static double pieceError(GasComplementGeometry.Piece piece) { return .5 * Math.ulp(piece.volume()); }
	private static int cellCount(GasComplementGeometry geometry) { return Math.multiplyExact(geometry.sizeX(), Math.multiplyExact(geometry.sizeY(), geometry.sizeZ())); }
	private static int[] emptyParents(GasComplementGeometry geometry) { int[] result = new int[Math.multiplyExact(8, cellCount(geometry))]; Arrays.fill(result, -1); return result; }
	private static int root(int[] parent, int piece) {
		int root = piece; while (parent[root] != root) root = parent[root];
		while (parent[piece] != piece) { int next = parent[piece]; parent[piece] = root; piece = next; }
		return root;
	}
	private static void join(int[] parent, byte[] rank, int first, int second) {
		int a = root(parent, first), b = root(parent, second);
		if (a == b) return;
		if (rank[a] < rank[b]) parent[a] = b;
		else { parent[b] = a; if (rank[a] == rank[b]) rank[a]++; }
	}
	/** Balanced unions are flattened once into the stable minimum-piece identifiers. */
	private static void canonicalize(int[] parent) {
		int[] minimum = new int[parent.length]; Arrays.fill(minimum, Integer.MAX_VALUE);
		for (int piece = 0; piece < parent.length; piece++) if (parent[piece] >= 0) {
			int root = root(parent, piece); minimum[root] = Math.min(minimum[root], piece);
		}
		// The first pass flattened every old root before these links change.
		for (int piece = 0; piece < parent.length; piece++) if (parent[piece] >= 0) parent[piece] = minimum[parent[piece]];
	}
	private static double positive(double result, String name) {
		if (!(result > 0 && Double.isFinite(result))) throw new GasTopologyBlockedException(name + " is not representable");
		return result;
	}
	int[] copyPieceNodes() { return pieceNodes.clone(); }
	int nodeOfPiece(int piece) { return pieceNodes[piece]; }
	boolean matchesGeometry(GasComplementGeometry candidate) { return geometry == candidate; }
	List<Chamber> chambers() { return chambers; }
	List<Connection> connections() { return connections; }
}
