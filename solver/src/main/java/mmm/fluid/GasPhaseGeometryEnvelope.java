package mmm.fluid;

import mmm.units.SignedDyadic;
import mmm.fluid.air.AirNetwork;
import mmm.fluid.air.GasInventory;
import mmm.thermal.ThermalDomainState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Bounds future gas capacity when every valid phase remap has one complete source-stock successor. */
final class GasPhaseGeometryEnvelope {
	private final GasRuntimeGeometry source;
	private final List<Integer> variableCells;
	private final SignedDyadic fixedVolume, metric;
	private final List<GasPhaseMergeBounds.Stock> possibleStocks;

	private GasPhaseGeometryEnvelope(GasRuntimeGeometry source, List<Integer> variableCells, SignedDyadic fixedVolume,
	                                 List<GasPhaseMergeBounds.Stock> possibleStocks) {
		this.source = source; this.variableCells = List.copyOf(variableCells); this.fixedVolume = fixedVolume;
		this.possibleStocks = List.copyOf(possibleStocks);
		this.metric = SignedDyadic.of(source.geometry().cellVolumeCubicMeters());
	}

	static GasPhaseGeometryEnvelope forBoiling(PhaseChangeStageSupport support, int editedCell, int sourceNode) {
		GasRuntimeGeometry source = support.gasState().frame;
		var state = support.state();
		if (source == null || !source.sealedExterior()
				|| Float.floatToRawIntBits(source.cellSize()) != Float.floatToRawIntBits(state.cellSize())
				|| source.geometry().sizeX() != state.sizeX() || source.geometry().sizeY() != state.sizeY()
				|| source.geometry().sizeZ() != state.sizeZ()) return null;
		long[] occupied = source.occupiedSvq();
		for (int cell = 0; cell < state.cellCount(); cell++)
			if (source.geometry().collisionMask(cell) != state.geometry().cutCells.mask(cell)
					|| occupied[cell] != Math.addExact(support.granularSvq(cell), support.thermal().coherentPhases().cellOccupiedSvq(cell))
					|| !source.liquidStock().get(cell).equals(PhaseLiquidGeometryEdit.current(state, cell))) return null;
		try { return capture(source, support.gasState().network, support.thermal(), editedCell, sourceNode); }
		catch (IllegalArgumentException | ArithmeticException | GasTopologyBlockedException unresolved) { return null; }
	}

	static GasPhaseGeometryEnvelope capture(GasRuntimeGeometry source, AirNetwork network, ThermalDomainState thermal,
	                                        int editedCell, int sourceNode) {
		if (source == null || !source.sealedExterior()) return null;
		boolean[] variable = new boolean[source.liquidStock().size()]; variable[editedCell] = true;
		int[] sizes = {source.geometry().sizeX(), source.geometry().sizeY(), source.geometry().sizeZ()}, strides = {1, sizes[0], sizes[0] * sizes[1]};
		int[] position = {editedCell % sizes[0], editedCell / sizes[0] % sizes[1], editedCell / strides[2]};
		for (int axis = 0; axis < 3; axis++) for (int sign : new int[]{-1, 1})
			if (position[axis] + sign >= 0 && position[axis] + sign < sizes[axis]) {
				int neighbor = editedCell + sign * strides[axis];
				variable[neighbor] = source.cellDependsOnNeighborFractions(neighbor);
			}
		int first = source.minimumPiece(sourceNode);
		// A single microbox, or one convex component's dry half-space, has one
		// positive successor. Missing old/new overlap leaves the phase candidate unavailable.
		if (!uniqueSuccessor(source, sourceNode, variable)) return null;
		int[] parent = new int[Math.multiplyExact(8, variable.length)]; Arrays.fill(parent, -1);
		for (var piece : source.geometry().pieces()) parent[piece.id()] = piece.id();
		for (int cell = 0; cell < variable.length; cell++) if (variable[cell]) {
			int mask = source.geometry().collisionMask(cell);
			int[] supports = CellRemapMicroTopology.components(mask);
			var capacities = CellRemapMicroTopology.capacities(mask, source.geometry().poreVolume(cell));
			for (int component = 0; component < supports.length; component++) if (capacities[component].signum() > 0)
				for (int micro = 0; micro < 8; micro++) if ((supports[component] & 1 << micro) != 0)
					parent[8 * cell + micro] = 8 * cell + micro;
		}
		for (var port : source.geometry().ports()) if (port.fullCutConnection()) join(parent, port.first(), port.second());
		// Filling every potentially changing open microbox with gas encloses every future positive path.
		for (int cell = 0; cell < variable.length; cell++) if (variable[cell]) {
			int[] coordinate = {cell % sizes[0], cell / sizes[0] % sizes[1], cell / strides[2]};
			for (int micro = 0; micro < 8; micro++) if (parent[8 * cell + micro] >= 0)
				for (int axis = 0; axis < 3; axis++) for (int sign : new int[]{-1, 1}) {
					boolean crossing = (micro >>> axis & 1) == (sign > 0 ? 1 : 0);
					if (crossing && (coordinate[axis] + sign < 0 || coordinate[axis] + sign >= sizes[axis])) continue;
					int peer = crossing ? cell + sign * strides[axis] : cell, peerPiece = 8 * peer + (micro ^ 1 << axis);
					if (parent[peerPiece] >= 0 && (!crossing || fullCutFace(source.geometry(), sign > 0 ? cell : peer, sign > 0 ? peer : cell, axis))
							&& (variable[peer] || source.dryMicroFaceArea(peer, peerPiece & 7, axis,
									crossing ? sign > 0 ? 0 : 1 : 0) > 0))
						join(parent, 8 * cell + micro, peerPiece);
				}
		}
		int selected = root(parent, first);
		var possibleStocks = new ArrayList<GasPhaseMergeBounds.Stock>();
		var checkedNodes = new java.util.HashSet<Integer>();
		for (var piece : source.geometry().pieces()) if (root(parent, piece.id()) == selected) {
			int node = source.nodeOfPiece(piece.id());
			if (node != sourceNode && checkedNodes.add(node)) {
				GasInventory stock = network.inventory(node);
				double energy = thermal == null ? 0 : thermal.gasNodeEnergy(node, network);
				if (stock.isEmpty() && energy == 0) continue;
				if (thermal == null || !uniqueSuccessor(source, node, variable)) return null;
				possibleStocks.add(new GasPhaseMergeBounds.Stock(stock, energy));
			}
		}
		SignedDyadic fixed = SignedDyadic.ZERO;
		for (var piece : source.capacity().pieces()) if (!variable[piece.id() / 8] && root(parent, piece.id()) == selected)
			fixed = fixed.add(SignedDyadic.of(piece.cubicMeters()));
		var variableCells = new ArrayList<Integer>();
		for (int cell = 0; cell < variable.length; cell++) if (variable[cell])
			for (int micro = 0; micro < 8; micro++) if (parent[8 * cell + micro] >= 0 && root(parent, 8 * cell + micro) == selected) {
				variableCells.add(cell); break;
			}
		return new GasPhaseGeometryEnvelope(source, variableCells, fixed, possibleStocks);
	}

	List<GasPhaseMergeBounds.Stock> possibleStocks() { return possibleStocks; }

	private static boolean uniqueSuccessor(GasRuntimeGeometry source, int node, boolean[] variable) {
		int first = -1, count = 0;
		boolean fixed = true;
		for (var piece : source.geometry().pieces()) if (source.nodeOfPiece(piece.id()) == node) {
			count++; fixed &= !variable[piece.cell()];
			if (first < 0) first = piece.id();
		}
		return first >= 0 && (fixed || count == 1 || singleConvexComponent(source, node, first)
				|| GasPhaseSourceBackbone.retainsSource(source, node, variable));
	}

	double volumeUpper(PhaseLiquidGeometryEdit edit) {
		return volumeUpper(edit.cell(), edit.components().stream().map(LiquidComponentMoments::volume).toList());
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

	private static int root(int[] parent, int piece) {
		int result = piece; while (parent[result] != result) result = parent[result];
		while (parent[piece] != piece) { int next = parent[piece]; parent[piece] = result; piece = next; }
		return result;
	}
	private static void join(int[] parent, int first, int second) {
		int a = root(parent, first), b = root(parent, second); if (a != b) parent[Math.max(a, b)] = Math.min(a, b);
	}
}
