package mmm.fluid;

import mmm.units.SignedDyadic;
import mmm.chemistry.ExactRational;
import mmm.fluid.air.AirNetwork;
import mmm.fluid.air.GasInventory;
import mmm.thermal.ThermalDomainState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Bounds a complete-stock successor or an observed persistent gas-contact backbone. */
final class GasPhaseGeometryEnvelope {
	private final GasRuntimeGeometry source;
	private final List<Integer> variableCells;
	private final SignedDyadic fixedVolume, metric;
	private final List<GasPhaseMergeBounds.Stock> possibleStocks;
	private final ExactRational sourceShare;
	private final int maximumChildren;
	private final boolean splitSource;
	private final Paths paths;

	private GasPhaseGeometryEnvelope(GasRuntimeGeometry source, List<Integer> variableCells, SignedDyadic fixedVolume,
	                                 List<GasPhaseMergeBounds.Stock> possibleStocks, ExactRational sourceShare, int maximumChildren,
	                                 boolean splitSource, Paths paths) {
		this.source = source; this.variableCells = List.copyOf(variableCells); this.fixedVolume = fixedVolume;
		this.possibleStocks = List.copyOf(possibleStocks);
		this.metric = SignedDyadic.of(source.geometry().cellVolumeCubicMeters());
		this.sourceShare = sourceShare; this.maximumChildren = maximumChildren;
		this.splitSource = splitSource;
		this.paths = paths;
	}

	static GasPhaseGeometryEnvelope forBoiling(PhaseChangeStageSupport support, int editedCell, int sourceNode) {
		return forBoiling(support, editedCell, sourceNode, List.of());
	}

	static GasPhaseGeometryEnvelope forBoiling(PhaseChangeStageSupport support, int editedCell, int sourceNode,
	                                          List<Integer> contactPieces) {
		return forBoiling(support, editedCell, sourceNode, contactPieces, support.prepareGasGeometrySource(editedCell));
	}

	static GasPhaseGeometryEnvelope forBoiling(PhaseChangeStageSupport support, int editedCell, int sourceNode,
	                                          List<Integer> contactPieces, GasRuntimeGeometry.PhaseSource prepared) {
		if (prepared == null || !prepared.frame().sealedExterior()) return null;
		return new Paths(prepared.frame(), support.gasState().network, support.thermal(), editedCell, sourceNode,
				contactPieces, prepared.region(), prepared.topology(), prepared.capacity()).capture(null);
	}

	static GasPhaseGeometryEnvelope capture(GasRuntimeGeometry source, AirNetwork network, ThermalDomainState thermal,
	                                        int editedCell, int sourceNode) {
		return capture(source, network, thermal, editedCell, sourceNode, List.of());
	}

	static GasPhaseGeometryEnvelope capture(GasRuntimeGeometry source, AirNetwork network, ThermalDomainState thermal,
	                                        int editedCell, int sourceNode, List<Integer> contactPieces) {
		if (source == null || !source.sealedExterior()) return null;
		var geometry = source.geometry();
		var region = new GasComplementGeometry.Region(geometry.sizeX(), geometry.sizeY(), geometry.sizeZ(), source.phaseEditCells(editedCell));
		var topology = GasChamberTopology.prepare(geometry, true, region);
		return new Paths(source, network, thermal, editedCell, sourceNode, contactPieces, region,
				topology, GasChamberCapacity.prepare(source, topology)).capture(null);
	}

	/** Same full-stock successor, with cross-cell paths restricted by this candidate's actual PLIC. */
	GasPhaseGeometryEnvelope forCandidate(FlipPressurePhaseGeometry phase) {
		return splitSource ? this : paths.capture(phase);
	}

	List<GasPhaseMergeBounds.Stock> possibleStocks() { return possibleStocks; }
	boolean splitSource() { return splitSource; }
	ExactRational sourceShare() { return sourceShare; }
	int maximumChildren() { return maximumChildren; }

	/** Fixed roots and open collision components enclose all future dry paths without microface integration. */
	private static final class Paths {
		private record Vertex(int cell, SignedDyadic fixedVolume, int[] oldNodes) { }
		private record Face(int first, int second, int minus, int plus, int axis) { }
		private final GasRuntimeGeometry source;
		private final AirNetwork network;
		private final ThermalDomainState thermal;
		private final int sourceNode, anchor, variableCount, children;
		private final ExactRational share;
		private final boolean wholeSource;
		private final boolean[] variable;
		private final byte[] uniqueSuccessors;
		private final int[] variableOrdinals;
		private final List<Vertex> vertices;
		private final List<Face> faces;
		private final int[][] incident;

		Paths(GasRuntimeGeometry source, AirNetwork network, ThermalDomainState thermal, int editedCell, int sourceNode,
		      List<Integer> contacts, GasComplementGeometry.Region region, GasChamberTopology.Prepared topology,
		      GasChamberCapacity.Prepared capacity) {
			this.source = source; this.network = network; this.thermal = thermal; this.sourceNode = sourceNode;
			variable = source.phaseEditCells(editedCell);
			uniqueSuccessors = new byte[source.nodeCount() + 1];
			wholeSource = GasPhaseGeometryEnvelope.uniqueSuccessor(source, sourceNode, variable);
			share = wholeSource ? ExactRational.ONE : fixedContactShare(source, sourceNode, contacts, topology, capacity);
			int count = variable.length;
			variableOrdinals = new int[count]; Arrays.fill(variableOrdinals, -1);
			int ordinal = 0;
			for (int cell : region.cells()) variableOrdinals[cell] = ordinal++;
			variableCount = ordinal;
			int[] byPiece = new int[8 * count], byRoot = new int[8 * count];
			Arrays.fill(byPiece, -1); Arrays.fill(byRoot, -1);
			var points = new ArrayList<Vertex>();
			for (var part : capacity.fixedCapacities()) {
				byRoot[part.root()] = points.size();
				points.add(new Vertex(-1, part.volume(), new int[]{source.nodeOfPiece(part.root())}));
			}
			for (var piece : source.geometry().pieces()) if (!variable[piece.cell()])
				byPiece[piece.id()] = byRoot[topology.fixedRoot(piece.id())];
			for (int cell : region.cells()) {
				int mask = source.geometry().collisionMask(cell);
				int[] supports = CellRemapMicroTopology.components(mask);
				var volumes = CellRemapMicroTopology.capacities(mask, source.geometry().poreVolume(cell));
				for (int component = 0; component < supports.length; component++) if (volumes[component].signum() > 0) {
					var nodes = new java.util.TreeSet<Integer>();
					for (int micro = 0; micro < 8; micro++) if ((supports[component] & 1 << micro) != 0) {
						int piece = 8 * cell + micro;
						byPiece[piece] = points.size();
						if (source.geometry().piece(piece) != null) nodes.add(source.nodeOfPiece(piece));
					}
					points.add(new Vertex(cell, SignedDyadic.ZERO, nodes.stream().mapToInt(Integer::intValue).toArray()));
				}
			}
			vertices = List.copyOf(points); anchor = byPiece[source.minimumPiece(sourceNode)];
			int[] sizes = {source.geometry().sizeX(), source.geometry().sizeY(), source.geometry().sizeZ()};
			int[] strides = {1, sizes[0], sizes[0] * sizes[1]};
			var edges = new ArrayList<Face>();
			for (int cell : region.cells()) {
				int[] coordinate = {cell % sizes[0], cell / sizes[0] % sizes[1], cell / strides[2]};
				for (int axis = 0; axis < 3; axis++) for (int sign : new int[]{-1, 1}) {
					if (coordinate[axis] + sign < 0 || coordinate[axis] + sign >= sizes[axis]) continue;
					int peer = cell + sign * strides[axis];
					if (variable[peer] && peer < cell) continue;
					int minus = sign > 0 ? cell : peer, plus = sign > 0 ? peer : cell;
					if (!fullCutFace(source.geometry(), minus, plus, axis)
							|| !variable[minus] && !source.fullDryFace(minus, axis, 1)
							|| !variable[plus] && !source.fullDryFace(plus, axis, 0)) continue;
					int first = byPiece[8 * minus + (1 << axis)], second = byPiece[8 * plus];
					if (first >= 0 && second >= 0) edges.add(new Face(first, second, minus, plus, axis));
				}
			}
			faces = List.copyOf(edges);
			int[] degrees = new int[vertices.size()];
			for (var face : faces) { degrees[face.first()]++; degrees[face.second()]++; }
			incident = new int[degrees.length][];
			for (int vertex = 0; vertex < degrees.length; vertex++) incident[vertex] = new int[degrees[vertex]];
			Arrays.fill(degrees, 0);
			for (int edge = 0; edge < faces.size(); edge++) {
				var face = faces.get(edge);
				incident[face.first()][degrees[face.first()]++] = edge;
				incident[face.second()][degrees[face.second()]++] = edge;
			}
			int pieces = 0;
			if (!wholeSource) for (var piece : source.geometry().pieces()) if (source.nodeOfPiece(piece.id()) == sourceNode) pieces++;
			children = wholeSource ? 1 : pieces;
		}

		GasPhaseGeometryEnvelope capture(FlipPressurePhaseGeometry phase) {
			if (share == null || anchor < 0) return null;
			boolean[] visited = new boolean[vertices.size()];
			int[] pending = new int[vertices.size()]; int head = 0, tail = 0;
			visited[anchor] = true; pending[tail++] = anchor;
			byte[] fullFaces = new byte[6 * variableCount];
			var variableCells = new java.util.TreeSet<Integer>();
			var nodes = new java.util.TreeSet<Integer>();
			var fixedByNode = new java.util.TreeMap<Integer, SignedDyadic>();
			while (head < tail) {
				int vertex = pending[head++]; var point = vertices.get(vertex);
				for (int node : point.oldNodes()) nodes.add(node);
				if (point.cell() >= 0) variableCells.add(point.cell());
				else fixedByNode.merge(point.oldNodes()[0], point.fixedVolume(), SignedDyadic::add);
				for (int edge : incident[vertex]) {
					var face = faces.get(edge); int peer = face.first() == vertex ? face.second() : face.first();
					if (visited[peer] || phase != null && (!fullFace(phase, face.minus(), face.axis(), 1, fullFaces)
							|| !fullFace(phase, face.plus(), face.axis(), 0, fullFaces))) continue;
					visited[peer] = true; pending[tail++] = peer;
				}
			}
			var possible = new ArrayList<GasPhaseMergeBounds.Stock>();
			for (int node : nodes) if (node != sourceNode) {
				GasInventory stock = network.inventory(node);
				double energy = thermal == null ? 0 : thermal.gasNodeEnergy(node, network);
				if (stock.isEmpty() && energy == 0) continue;
				if (!wholeSource || thermal == null || !uniqueSuccessor(node)) return null;
				possible.add(new GasPhaseMergeBounds.Stock(stock, energy, fixedByNode.getOrDefault(node, SignedDyadic.ZERO)));
			}
			SignedDyadic fixed = SignedDyadic.ZERO;
			for (var volume : fixedByNode.values()) fixed = fixed.add(volume);
			return new GasPhaseGeometryEnvelope(source, List.copyOf(variableCells), fixed, possible, share, children, !wholeSource, this);
		}

		private boolean uniqueSuccessor(int node) {
			if (uniqueSuccessors[node] == 0)
				uniqueSuccessors[node] = (byte) (GasPhaseGeometryEnvelope.uniqueSuccessor(source, node, variable) ? 2 : 1);
			return uniqueSuccessors[node] == 2;
		}

		private boolean fullFace(FlipPressurePhaseGeometry phase, int cell, int axis, int coordinate, byte[] cache) {
			int ordinal = variableOrdinals[cell]; if (ordinal < 0) return true;
			int key = 6 * ordinal + 2 * axis + coordinate;
			if (cache[key] == 0) cache[key] = (byte) (GasFaceIntersection.fullDryFace(phase, cell, axis, coordinate) ? 2 : 1);
			return cache[key] == 2;
		}
	}

	private static ExactRational fixedContactShare(GasRuntimeGeometry source, int node, List<Integer> contacts,
	                                              GasChamberTopology.Prepared topology, GasChamberCapacity.Prepared capacity) {
		var roots = new java.util.HashSet<Integer>();
		for (int piece : contacts) if (source.nodeOfPiece(piece) == node && topology.fixedRoot(piece) >= 0)
			roots.add(topology.fixedRoot(piece));
		SignedDyadic greatest = SignedDyadic.ZERO, total = SignedDyadic.ZERO;
		for (var part : capacity.fixedCapacities()) if (roots.contains(part.root()) && part.volume().compareTo(greatest) > 0) greatest = part.volume();
		for (var piece : source.capacity().pieces()) if (source.nodeOfPiece(piece.id()) == node) total = total.add(SignedDyadic.of(piece.cubicMeters()));
		if (greatest.signum() == 0 || total.signum() <= 0) return null;
		int exponent = Math.min(greatest.exponent(), total.exponent());
		return new ExactRational(greatest.significand().shiftLeft(greatest.exponent() - exponent),
				total.significand().shiftLeft(total.exponent() - exponent));
	}

	private static boolean uniqueSuccessor(GasRuntimeGeometry source, int node, boolean[] variable) {
		int first = -1, count = 0;
		boolean fixed = true;
		for (var piece : source.geometry().pieces()) if (source.nodeOfPiece(piece.id()) == node) {
			count++; fixed &= !variable[piece.cell()];
			if (first < 0) first = piece.id();
		}
		return first >= 0 && (fixed || count == 1 || singleConvexComponent(source, node, first));
	}

	double volumeUpper(PhaseLiquidGeometryEdit edit) {
		return volumeUpper(edit.cell(), edit.components().stream().map(LiquidComponentMoments::volume).toList());
	}

	double baseVolumeUpper(PhaseLiquidGeometryEdit edit, String vaporId) {
		double whole = volumeUpper(edit);
		if (!Double.isFinite(whole)) return whole;
		SignedDyadic base = SignedDyadic.of(whole);
		// Retain the whole envelope's pivot and final node-RN allowance in the base.
		// Each omitted optional fixed piece has unchanged positive overlap, so its
		// capacity can enter the source successor only together with its whole stock.
		for (var stock : possibleStocks) if (stock.inventory().component(vaporId) != null)
			base = base.subtract(stock.fixedVolume());
		return base.signum() < 0 ? Double.POSITIVE_INFINITY : Math.nextUp(base.toDouble());
	}

	double boilingVolumeUpper(int cell, int supportMask, long removedSvq) {
		SignedDyadic removed = SignedDyadic.of(removedSvq, -24);
		return volumeUpper(cell, source.liquidStock().get(cell).components().stream()
				.map(component -> component.volume().subtract(component.supportMask() == supportMask ? removed : SignedDyadic.ZERO)).toList());
	}

	private double volumeUpper(int editedCell, List<SignedDyadic> editedVolumes) {
		SignedDyadic upper = fixedVolume;
		for (int cell : variableCells) {
			var stock = source.liquidStock().get(cell);
			var capacities = CellRemapMicroTopology.capacities(stock.collisionMask(), source.geometry().poreVolume(cell));
			for (int component = 0; component < capacities.length; component++) {
				SignedDyadic liquid = cell == editedCell ? editedVolumes.get(component) : stock.components().get(component).volume();
				if (liquid.signum() < 0) return Double.POSITIVE_INFINITY;
				SignedDyadic available = capacities[component].subtract(liquid).multiply(metric);
				if (available.signum() < 0) return Double.POSITIVE_INFINITY;
				if (available.signum() == 0) continue;
				double roundedUpper = Math.nextUp(available.toDouble());
				if (!Double.isFinite(roundedUpper)) return Double.POSITIVE_INFINITY;
				// The positive pivot remainder is at most the exact component volume; one
				// outward ULP covers its final rounding as well as the initial conversion.
				upper = upper.add(SignedDyadic.of(roundedUpper)).add(SignedDyadic.of(Math.ulp(roundedUpper)));
			}
		}
		return Math.nextUp(upper.toDouble());
	}

	private static boolean singleConvexComponent(GasRuntimeGeometry source, int node, int first) {
		int cell = first / 8, support = 0;
		for (var component : source.liquidStock().get(cell).components())
			if ((component.supportMask() & 1 << (first & 7)) != 0) support = component.supportMask();
		int[] axes = new int[3];
		for (int micro = 0; micro < 8; micro++) if ((support & 1 << micro) != 0)
			for (int axis = 0; axis < 3; axis++) axes[axis] |= 1 << (micro >>> axis & 1);
		int box = 0;
		for (int micro = 0; micro < 8; micro++) if ((axes[0] & 1 << (micro & 1)) != 0
				&& (axes[1] & 1 << (micro >>> 1 & 1)) != 0 && (axes[2] & 1 << (micro >>> 2 & 1)) != 0) box |= 1 << micro;
		if (support == 0 || box != support) return false;
		for (var piece : source.geometry().pieces()) if (source.nodeOfPiece(piece.id()) == node
				&& (piece.cell() != cell || (support & 1 << piece.micro()) == 0)) return false;
		return true;
	}

	private static boolean fullCutFace(GasComplementGeometry geometry, int minus, int plus, int axis) {
		for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++) {
			int base = a << ((axis + 1) % 3) | b << ((axis + 2) % 3);
			if ((geometry.collisionMask(minus) & 1 << (base | 1 << axis)) != 0
					|| (geometry.collisionMask(plus) & 1 << base) != 0) return false;
		}
		return true;
	}

}
