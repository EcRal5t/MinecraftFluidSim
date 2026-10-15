package mmm.fluid;

import mmm.fluid.air.GasLimits;
import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

/** Immutable gas volumes; partial fixed or PLIC cell faces retain finite-rate ports. */
final class GasChamberTopology {
	record Chamber(int node, int minimumPiece, double geometricVolume, double volumeErrorBound) { }
	record Connection(int firstNode, int secondNode, double area) { }
	private record FixedPart(int root, SignedDyadic volume, SignedDyadic error, boolean ambient) { }
	private record FixedLink(int first, int second, SignedDyadic area) { }
	private static final class Totals {
		SignedDyadic volume = SignedDyadic.ZERO, error = SignedDyadic.ZERO;
		boolean ambient;
		void add(GasComplementGeometry.Piece piece) {
			volume = volume.add(SignedDyadic.of(piece.volume())); error = error.add(pieceError(piece));
		}
	}
	private final int[] pieceNodes;
	private final GasComplementGeometry geometry;
	private final List<Chamber> chambers;
	private final List<Connection> connections;

	private GasChamberTopology(GasComplementGeometry geometry, int[] pieceNodes, List<Chamber> chambers, List<Connection> connections) {
		this.geometry = geometry;
		this.pieceNodes = pieceNodes; this.chambers = List.copyOf(chambers); this.connections = List.copyOf(connections);
	}

	/** One read-only phase search supplies unchanged geometry outside its complete variable-cell set. */
	static Prepared prepare(GasComplementGeometry source, boolean sealedExterior, boolean[] variableCells) {
		return new Prepared(source, sealedExterior, variableCells);
	}
	static final class Prepared {
		private final GasComplementGeometry source;
		private final boolean sealedExterior;
		private final boolean[] variable;
		private final int[] parent, variablePieces;
		private final long[] portKeys, exteriorKeys;
		private final List<FixedPart> parts;
		private final List<FixedLink> links;

		private Prepared(GasComplementGeometry source, boolean sealedExterior, boolean[] variableCells) {
			if (source == null || variableCells == null || variableCells.length != cellCount(source))
				throw new IllegalArgumentException("Fixed gas region requires its complete source and variable cells");
			this.source = source; this.sealedExterior = sealedExterior; variable = variableCells.clone();
			parent = emptyParents(source);
			for (var piece : source.pieces()) if (!variable[piece.cell()]) parent[piece.id()] = piece.id();
			for (var port : source.ports()) if (parent[port.first()] >= 0 && parent[port.second()] >= 0 && port.fullCutConnection())
				join(parent, port.first(), port.second());
			var totals = new TreeMap<Integer, Totals>();
			for (var piece : source.pieces()) if (!variable[piece.cell()]) {
				int root = root(parent, piece.id()); parent[piece.id()] = root;
				totals.computeIfAbsent(root, ignored -> new Totals()).add(piece);
			}
			if (!sealedExterior) for (var port : source.exteriorPorts()) if (!variable[port.piece() / 8] && port.fullCutConnection())
				totals.get(root(parent, port.piece())).ambient = true;
			var fixedParts = new ArrayList<FixedPart>(totals.size());
			for (var entry : totals.entrySet()) {
				var total = entry.getValue(); fixedParts.add(new FixedPart(entry.getKey(), total.volume, total.error, total.ambient));
			}
			parts = List.copyOf(fixedParts);
			var areas = new TreeMap<Long, SignedDyadic>();
			for (var port : source.ports()) if (parent[port.first()] >= 0 && parent[port.second()] >= 0)
				addArea(areas, root(parent, port.first()), root(parent, port.second()), port.area());
			if (!sealedExterior) for (var port : source.exteriorPorts()) if (!variable[port.piece() / 8])
				addArea(areas, -1, root(parent, port.piece()), port.area());
			var fixedLinks = new ArrayList<FixedLink>(areas.size());
			for (var entry : areas.entrySet()) fixedLinks.add(new FixedLink((int) (entry.getKey() >>> 32), (int) (long) entry.getKey(), entry.getValue()));
			links = List.copyOf(fixedLinks);
			var pieces = new ArrayList<Integer>(); var ports = new TreeSet<Long>(); var exterior = new TreeSet<Long>();
			int[] sizes = {source.sizeX(), source.sizeY(), source.sizeZ()}, strides = {1, source.sizeX(), source.sizeX() * source.sizeY()};
			for (int cell = 0; cell < variable.length; cell++) if (variable[cell]) {
				int[] coordinate = {cell % sizes[0], cell / sizes[0] % sizes[1], cell / strides[2]};
				for (int micro = 0; micro < 8; micro++) {
					int piece = 8 * cell + micro; pieces.add(piece);
					for (int axis = 0; axis < 3; axis++) {
						long key = 3L * piece + axis; ports.add(key); exterior.add(key);
						// Incoming cross-cell ports are stored at the previous cell's positive microface.
						if ((micro & 1 << axis) == 0 && coordinate[axis] > 0)
							ports.add(3L * (8 * (cell - strides[axis]) + (micro | 1 << axis)) + axis);
					}
				}
			}
			variablePieces = pieces.stream().mapToInt(Integer::intValue).toArray();
			portKeys = ports.stream().mapToLong(Long::longValue).toArray(); exteriorKeys = exterior.stream().mapToLong(Long::longValue).toArray();
		}

		GasChamberTopology capture(GasComplementGeometry candidate) {
			if (candidate == null || source.sizeX() != candidate.sizeX() || source.sizeY() != candidate.sizeY() || source.sizeZ() != candidate.sizeZ()
					|| Double.doubleToRawLongBits(source.cellVolumeCubicMeters()) != Double.doubleToRawLongBits(candidate.cellVolumeCubicMeters()))
				throw new IllegalArgumentException("Fixed gas region candidate differs in shape or metric");
			// Check absent pieces too: a new or vanished fixed piece changes the prepared graph.
			for (int piece = 0; piece < parent.length; piece++) if (!variable[piece / 8] && source.piece(piece) != candidate.piece(piece))
				throw new IllegalArgumentException("Fixed gas region changed outside its variable cells");
			var pieces = new ArrayList<GasComplementGeometry.Piece>(variablePieces.length);
			for (int id : variablePieces) { var piece = candidate.piece(id); if (piece != null) pieces.add(piece); }
			var ports = new ArrayList<GasComplementGeometry.Port>(portKeys.length);
			for (long key : portKeys) {
				int index = findPort(candidate.ports(), key); if (index >= 0) ports.add(candidate.ports().get(index));
			}
			var exterior = new ArrayList<GasComplementGeometry.ExteriorPort>(exteriorKeys.length);
			if (!sealedExterior) for (long key : exteriorKeys) {
				int index = findExterior(candidate.exteriorPorts(), key); if (index >= 0) exterior.add(candidate.exteriorPorts().get(index));
			}
			return assemble(candidate, sealedExterior, parent.clone(), parts, links, pieces, ports, exterior);
		}
	}

	static GasChamberTopology capture(GasComplementGeometry geometry, boolean sealedExterior) {
		if (geometry == null) throw new IllegalArgumentException("Gas chamber geometry is missing");
		return assemble(geometry, sealedExterior, emptyParents(geometry), List.of(), List.of(), geometry.pieces(), geometry.ports(), geometry.exteriorPorts());
	}
	private static GasChamberTopology assemble(GasComplementGeometry geometry, boolean sealedExterior, int[] parent,
	                                         List<FixedPart> fixed, List<FixedLink> links, List<GasComplementGeometry.Piece> pieces,
	                                         List<GasComplementGeometry.Port> ports, List<GasComplementGeometry.ExteriorPort> exterior) {
		for (var piece : pieces) parent[piece.id()] = piece.id();
		for (var port : ports) if (port.fullCutConnection()) join(parent, port.first(), port.second());
		boolean[] ambient = new boolean[parent.length];
		for (var part : fixed) if (part.ambient()) ambient[root(parent, part.root())] = true;
		if (!sealedExterior) for (var port : exterior) if (port.fullCutConnection()) ambient[root(parent, port.piece())] = true;
		var nodeByRoot = new TreeMap<Integer, Integer>();
		for (var part : fixed) includeRoot(nodeByRoot, parent, ambient, part.root());
		for (var piece : pieces) includeRoot(nodeByRoot, parent, ambient, piece.id());
		if (nodeByRoot.size() > GasLimits.MAX_NODES_PER_REGION) throw new GasTopologyBlockedException("Gas chamber count exceeds its representation budget");
		int node = 0; int[] owners = new int[parent.length];
		for (var entry : nodeByRoot.entrySet()) { entry.setValue(++node); owners[entry.getKey()] = node; }
		int[] pieceNodes = new int[parent.length]; Arrays.fill(pieceNodes, -1);
		for (var piece : geometry.pieces()) pieceNodes[piece.id()] = owners[root(parent, piece.id())];
		SignedDyadic[] volumes = new SignedDyadic[node + 1], volumeErrors = new SignedDyadic[node + 1];
		Arrays.fill(volumes, SignedDyadic.ZERO); Arrays.fill(volumeErrors, SignedDyadic.ZERO);
		for (var part : fixed) {
			int owner = owners[root(parent, part.root())];
			volumes[owner] = volumes[owner].add(part.volume()); volumeErrors[owner] = volumeErrors[owner].add(part.error());
		}
		for (var piece : pieces) {
			int owner = pieceNodes[piece.id()];
			volumes[owner] = volumes[owner].add(SignedDyadic.of(piece.volume())); volumeErrors[owner] = volumeErrors[owner].add(pieceError(piece));
		}
		var chambers = new ArrayList<Chamber>(node);
		for (var entry : nodeByRoot.entrySet()) {
			int owner = entry.getValue(); double volume = positive(volumes[owner], "Gas chamber volume");
			SignedDyadic error = volumeErrors[owner].add(SignedDyadic.of(Math.ulp(volume)).multiply(SignedDyadic.of(.5)));
			chambers.add(new Chamber(owner, entry.getKey(), volume, Math.nextUp(error.toDouble())));
		}
		var areas = new TreeMap<Long, SignedDyadic>();
		for (var link : links) addArea(areas, link.first() < 0 ? 0 : owners[root(parent, link.first())],
				link.second() < 0 ? 0 : owners[root(parent, link.second())], link.area());
		for (var port : ports) addArea(areas, pieceNodes[port.first()], pieceNodes[port.second()], port.area());
		if (!sealedExterior) for (var port : exterior) addArea(areas, 0, pieceNodes[port.piece()], port.area());
		var connections = new ArrayList<Connection>(areas.size());
		for (var entry : areas.entrySet()) connections.add(new Connection((int) (entry.getKey() >>> 32), (int) (long) entry.getKey(), positive(entry.getValue(), "Gas chamber port area")));
		return new GasChamberTopology(geometry, pieceNodes, chambers, connections);
	}
	private static void includeRoot(TreeMap<Integer, Integer> nodes, int[] parent, boolean[] ambient, int piece) {
		int root = root(parent, piece); if (!ambient[root]) nodes.putIfAbsent(root, 0);
	}
	private static void addArea(TreeMap<Long, SignedDyadic> areas, int first, int second, SignedDyadic area) {
		if (first == second) return;
		int low = Math.min(first, second), high = Math.max(first, second);
		areas.merge((long) low << 32 | (high & 0xffffffffL), area, SignedDyadic::add);
	}
	private static void addArea(TreeMap<Long, SignedDyadic> areas, int first, int second, double area) {
		if (first != second) addArea(areas, first, second, SignedDyadic.of(area));
	}
	private static SignedDyadic pieceError(GasComplementGeometry.Piece piece) { return SignedDyadic.of(Math.ulp(piece.volume())).multiply(SignedDyadic.of(.5)); }
	private static int cellCount(GasComplementGeometry geometry) { return Math.multiplyExact(geometry.sizeX(), Math.multiplyExact(geometry.sizeY(), geometry.sizeZ())); }
	private static int[] emptyParents(GasComplementGeometry geometry) { int[] result = new int[Math.multiplyExact(8, cellCount(geometry))]; Arrays.fill(result, -1); return result; }
	/** Both immutable port lists use their producer's piece-id then axis order. */
	private static int findPort(List<GasComplementGeometry.Port> ports, long key) {
		int low = 0, high = ports.size() - 1;
		while (low <= high) {
			int middle = (low + high) >>> 1; var port = ports.get(middle); long found = 3L * port.first() + port.axis();
			if (found == key) return middle; if (found < key) low = middle + 1; else high = middle - 1;
		}
		return -1;
	}
	private static int findExterior(List<GasComplementGeometry.ExteriorPort> ports, long key) {
		int low = 0, high = ports.size() - 1;
		while (low <= high) {
			int middle = (low + high) >>> 1; var port = ports.get(middle); long found = 3L * port.piece() + port.axis();
			if (found == key) return middle; if (found < key) low = middle + 1; else high = middle - 1;
		}
		return -1;
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
