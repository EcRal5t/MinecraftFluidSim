package mmm.fluid;

import mmm.fluid.air.GasLimits;
import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

/** Immutable gas volumes; partial fixed or PLIC cell faces retain finite-rate ports. */
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
		int size = Math.multiplyExact(8, Math.multiplyExact(geometry.sizeX(), Math.multiplyExact(geometry.sizeY(), geometry.sizeZ())));
		int[] parent = new int[size]; Arrays.fill(parent, -1);
		for (var piece : geometry.pieces()) parent[piece.id()] = piece.id();
		for (var port : geometry.ports()) if (port.fullCutConnection()) join(parent, port.first(), port.second());
		boolean[] ambient = new boolean[size];
		if (!sealedExterior) for (var port : geometry.exteriorPorts())
			if (port.fullCutConnection()) ambient[root(parent, port.piece())] = true;
		var nodeByRoot = new TreeMap<Integer, Integer>();
		for (var piece : geometry.pieces()) {
			int root = root(parent, piece.id()); if (!ambient[root]) nodeByRoot.putIfAbsent(root, 0);
		}
		if (nodeByRoot.size() > GasLimits.MAX_NODES_PER_REGION) throw new GasTopologyBlockedException("Gas chamber count exceeds its representation budget");
		int node = 0;
		for (var entry : nodeByRoot.entrySet()) entry.setValue(++node);
		int[] pieceNodes = new int[size]; Arrays.fill(pieceNodes, -1);
		SignedDyadic[] volumes = new SignedDyadic[node + 1], volumeErrors = new SignedDyadic[node + 1];
		Arrays.fill(volumes,SignedDyadic.ZERO); Arrays.fill(volumeErrors,SignedDyadic.ZERO);
		for (var piece : geometry.pieces()) {
			int root = root(parent, piece.id()), owner = ambient[root] ? 0 : nodeByRoot.get(root);
			pieceNodes[piece.id()] = owner;
			volumes[owner] = volumes[owner].add(SignedDyadic.of(piece.volume()));
			volumeErrors[owner] = volumeErrors[owner].add(SignedDyadic.of(Math.ulp(piece.volume())).multiply(SignedDyadic.of(.5)));
		}
		var chambers = new ArrayList<Chamber>(node);
		for (var entry : nodeByRoot.entrySet()) {
			int owner = entry.getValue(); double volume = positive(volumes[owner],"Gas chamber volume");
			SignedDyadic error = volumeErrors[owner].add(SignedDyadic.of(Math.ulp(volume)).multiply(SignedDyadic.of(.5)));
			chambers.add(new Chamber(owner,entry.getKey(),volume,Math.nextUp(error.toDouble())));
		}
		var areaByNodes = new TreeMap<Long, SignedDyadic>();
		for (var port : geometry.ports()) {
			int first = pieceNodes[port.first()], second = pieceNodes[port.second()]; if (first == second) continue;
			int low = Math.min(first, second), high = Math.max(first, second); long key = (long) low << 32 | (high & 0xffffffffL);
			areaByNodes.merge(key, SignedDyadic.of(port.area()), SignedDyadic::add);
		}
		if (!sealedExterior) for (var port : geometry.exteriorPorts()) {
			int owner = pieceNodes[port.piece()];
			if (owner > 0) areaByNodes.merge((long) owner, SignedDyadic.of(port.area()), SignedDyadic::add);
		}
		var connections = new ArrayList<Connection>(areaByNodes.size());
		for (var entry : areaByNodes.entrySet()) connections.add(new Connection((int) (entry.getKey() >>> 32), (int) (long) entry.getKey(), positive(entry.getValue(), "Gas chamber port area")));
		return new GasChamberTopology(geometry, pieceNodes, chambers, connections);
	}

	private static int root(int[] parent, int piece) {
		int root = piece; while (parent[root] != root) root = parent[root];
		while (parent[piece] != piece) { int next = parent[piece]; parent[piece] = root; piece = next; }
		return root;
	}
	private static void join(int[] parent, int first, int second) {
		int a = root(parent, first), b = root(parent, second); if (a != b) parent[Math.max(a, b)] = Math.min(a, b);
	}
	private static double positive(SignedDyadic value, String name) {
		double result = value.toDouble();
		if (!(result > 0 && Double.isFinite(result))) throw new GasTopologyBlockedException(name + " is not representable");
		return result;
	}
	int nodeOfPiece(int piece) { return pieceNodes[piece]; }
	boolean matchesGeometry(GasComplementGeometry candidate) { return geometry == candidate; }
	List<Chamber> chambers() { return chambers; }
	List<Connection> connections() { return connections; }
}
