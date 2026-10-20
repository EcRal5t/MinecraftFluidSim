package mmm.fluid;

import mmm.fluid.air.GasLimits;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;

/**
 * Gas shares one pressure within each coarse fixed-pore component, including unresolved liquid films.
 * Resolved coarse dry capacity joins complete fixed faces; tiny pockets and cut apertures use actual dry ports.
 */
final class GasChamberTopology {
	record Chamber(int node, int minimumPiece, double geometricVolume, double volumeErrorBound) { }
	record Connection(int firstNode, int secondNode, double area) { }
	private final int[] pieceNodes;
	private final int[] componentNodes;
	private final GasComplementGeometry geometry;
	private final List<Chamber> chambers;
	private final List<Connection> connections;
	private final boolean sealedExterior;
	private GasChamberCapacity preparedCapacity;

	private GasChamberTopology(GasComplementGeometry geometry, int[] pieceNodes, int[] componentNodes, List<Chamber> chambers, List<Connection> connections, boolean sealedExterior) {
		this.geometry = geometry;
		this.sealedExterior = sealedExterior;
		this.pieceNodes = pieceNodes; this.componentNodes=componentNodes; this.chambers = List.copyOf(chambers); this.connections = List.copyOf(connections);
	}

	static GasChamberTopology capture(GasComplementGeometry geometry, boolean sealedExterior) {
		if (geometry == null) throw new IllegalArgumentException("Gas chamber geometry is missing");
		var cached=geometry.cachedTopology(sealedExterior);if(cached!=null)return cached;
		int cells = cellCount(geometry);
		int[] componentOfPiece = new int[8 * cells];
		Arrays.fill(componentOfPiece, -1);
		int[] minimumPiece = new int[4 * cells];
		int count = 0;
		// One union-find item per coarse pore component; micro pieces only name geometry.
		for (int cell = 0; cell < cells; cell++) {
			for (int support : CellRemapMicroTopology.components(geometry.collisionMask(cell))) {
				int minimum = Integer.MAX_VALUE;
				for (int bits = support; bits != 0; bits &= bits - 1) {
					int piece = 8 * cell + Integer.numberOfTrailingZeros(bits);
					if (geometry.piece(piece) == null) continue;
					minimum = Math.min(minimum, piece);
					componentOfPiece[piece] = count;
				}
				if (minimum != Integer.MAX_VALUE) minimumPiece[count++] = minimum;
			}
		}
		int[] parent = new int[count];
		byte[] rank = new byte[count];
		for (int component = 0; component < count; component++) parent[component] = component;
		for (var port : geometry.ports()) if (port.fullCutConnection())
			join(parent, rank, componentOfPiece[port.first()], componentOfPiece[port.second()]);
		boolean[] ambient = new boolean[count];
		if (!sealedExterior) for (var port : geometry.exteriorPorts()) if (port.fullCutConnection())
			ambient[root(parent, componentOfPiece[port.piece()])] = true;
		int[] minimum = new int[count];
		Arrays.fill(minimum, Integer.MAX_VALUE);
		for (int component = 0; component < count; component++) {
			int owner = root(parent, component);
			minimum[owner] = Math.min(minimum[owner], minimumPiece[component]);
		}
		long[] roots = new long[count];
		int nodes = 0;
		for (int component = 0; component < count; component++) if (parent[component] == component && !ambient[component])
			roots[nodes++] = (long) minimum[component] << 32 | Integer.toUnsignedLong(component);
		if (nodes > GasLimits.MAX_NODES_PER_REGION)
			throw new GasTopologyBlockedException("Gas chamber count exceeds its representation budget");
		Arrays.sort(roots, 0, nodes);
		int[] owners = new int[count];
		for (int node = 0; node < nodes; node++) owners[(int) roots[node]] = node + 1;
		int[] pieceNodes = new int[8 * cells];
		Arrays.fill(pieceNodes, -1);
		int[] componentNodes=new int[8*cells];Arrays.fill(componentNodes,-1);
		double[] volumes = new double[nodes + 1], errors = new double[nodes + 1];
		for (var piece : geometry.pieces()) {
			int owner = owners[parent[componentOfPiece[piece.id()]]];
			pieceNodes[piece.id()] = owner;
			int support=CellRemapMicroTopology.support(geometry.collisionMask(piece.cell()),piece.micro());
			componentNodes[8*piece.cell()+Integer.numberOfTrailingZeros(support)]=owner;
			volumes[owner] += piece.volume();
			errors[owner] += pieceError(piece) + Math.ulp(volumes[owner]);
		}
		var chambers = new ArrayList<Chamber>(nodes);
		for (int node = 0; node < nodes; node++) {
			int owner = node + 1;
			double volume = positive(volumes[owner], "Gas chamber volume");
			chambers.add(new Chamber(owner, (int) (roots[node] >>> 32), volume,
					Math.nextUp(errors[owner] + Math.ulp(volume))));
		}
		var areas = new TreeMap<Long, Double>();
		for (var port : geometry.ports()) addArea(areas, pieceNodes[port.first()], pieceNodes[port.second()], port.area());
		if (!sealedExterior) for (var port : geometry.exteriorPorts()) addArea(areas, 0, pieceNodes[port.piece()], port.area());
		var connections = new ArrayList<Connection>(areas.size());
		for (var entry : areas.entrySet()) connections.add(new Connection((int) (entry.getKey() >>> 32),
				(int) (long) entry.getKey(), positive(entry.getValue(), "Gas chamber port area")));
		var result=new GasChamberTopology(geometry, pieceNodes, componentNodes, chambers, connections, sealedExterior);
		geometry.cacheTopology(sealedExterior,result);return result;
	}
	private static void addArea(TreeMap<Long, Double> areas, int first, int second, double area) {
		if (first == second) return;
		int low = Math.min(first, second), high = Math.max(first, second);
		areas.merge((long) low << 32 | (high & 0xffffffffL), area, Double::sum);
	}
    private static double pieceError(GasComplementGeometry.Piece piece) { return .5 * Math.ulp(piece.volume()); }
	private static int cellCount(GasComplementGeometry geometry) { return Math.multiplyExact(geometry.sizeX(), Math.multiplyExact(geometry.sizeY(), geometry.sizeZ())); }
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
	private static double positive(double result, String name) {
		if (!(result > 0 && Double.isFinite(result))) throw new GasTopologyBlockedException(name + " is not representable");
		return result;
	}
	int[] copyPieceNodes() { return pieceNodes.clone(); }
	int nodeOfPiece(int piece) { return pieceNodes[piece]; }
	int nodeOfComponent(int cell,int support) { return componentNodes[8*cell+Integer.numberOfTrailingZeros(support)]; }
	boolean matchesGeometry(GasComplementGeometry candidate) { return geometry == candidate; }
	GasChamberCapacity preparedCapacity(){return preparedCapacity;}
	void preparedCapacity(GasChamberCapacity capacity){preparedCapacity=capacity;}
	List<Chamber> chambers() { return chambers; }
	List<Connection> connections() { return connections; }
}
