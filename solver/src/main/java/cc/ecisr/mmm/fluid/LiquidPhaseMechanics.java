package cc.ecisr.mmm.fluid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import cc.ecisr.mmm.fluid.LiquidExactPolygon.Fraction;

/** Pressure, viscous stress and interface motion share the same complete phase geometry. */
final class LiquidPhaseMechanics {
	/** Finite gas support with a prescribed pressure and no representable compliance row. */
	static final int PRESCRIBED_GAS_PRESSURE = -2;
	interface InterfacePressure { double at(int face, int gasCell); }
	record Result(LiquidControlVolumeViscosity.Result viscosity, LiquidControlVolumeProjection.Result pressure,
			LiquidInterfaceVelocityExtension.Result extension, LiquidControlVolumeVelocity.Result[] velocity) { }
	private final LiquidControlVolumeMesh mesh;
	private final int[] liquid, liquidCells, faceIds, faceSigns;
	private final LiquidControlVolumeProjection projection;
	private final LiquidControlVolumeViscosity viscosity;

	LiquidPhaseMechanics(LiquidControlVolumeMesh mesh, int[] liquid, double[] density, double[] dynamicViscosity,
			List<LiquidControlVolumeProjection.GasNode> gasNodes, int[] gasCellNode, InterfacePressure pressure,
			FluidSolverConfig.WallCondition wall) {
		int count = mesh.cells.size();
		if (liquid.length != count || density.length != count || dynamicViscosity.length != count || gasCellNode.length != count || pressure == null)
			throw new IllegalArgumentException("Incomplete phase mechanics properties");
		this.mesh = mesh; this.liquid = liquid.clone();
		requireGasNodes(mesh, liquid, gasCellNode, gasNodes.size());
		var cells = new ArrayList<LiquidControlVolumeProjection.Cell>(); var compact = new int[count]; Arrays.fill(compact, -1);
		liquidCells = java.util.stream.IntStream.range(0, count).filter(c -> liquid[c] >= 0).toArray(); var coefficients = new double[liquidCells.length];
		for (int c : liquidCells) {
			compact[c] = cells.size(); cells.add(new LiquidControlVolumeProjection.Cell(mesh.cells.get(c).geometry(), density[c])); coefficients[compact[c]] = dynamicViscosity[c];
		}
		var faces = new ArrayList<LiquidControlVolumeProjection.Face>(); var ids = new ArrayList<Integer>(); var signs = new ArrayList<Integer>(); var nodes = new ArrayList<Integer>();
		for (int f = 0; f < mesh.faces.size(); f++) {
			var face = mesh.faces.get(f); int first = compact[face.first()], second = face.second() >= 0 ? compact[face.second()] : face.second();
			boolean a = first >= 0, b = face.second() >= 0 && second >= 0; if (!a && !b) continue;
			int sign = a ? 1 : -1, source = a ? first : second; var surface = face.surface();
			if (sign < 0) { var n = surface.areaVector(); surface = new LiquidPolyhedron.Surface(surface.area(), new LiquidPolyhedron.Point(-n.x(), -n.y(), -n.z()), surface.centroid()); }
			int neighbor = a && b ? second : face.second() == -2 ? -2 : -1; int node = -1; double prescribed = face.prescribed();
			if (neighbor == -1) {
				int gas = a ? face.second() : face.first(); prescribed = pressure.at(f, gas);
				if (gas >= 0) {
					node = gasCellNode[gas];
					if (node < PRESCRIBED_GAS_PRESSURE || node >= gasNodes.size()) throw new IllegalArgumentException("Gas cell has no pressure node");
					if (node == PRESCRIBED_GAS_PRESSURE) node = -1;
				}
			}
			var vector = sign > 0 ? face.areaVector() : face.areaVector().stream().map(LiquidExactPolygon.Fraction::negate).toList();
			faces.add(new LiquidControlVolumeProjection.Face(source, neighbor, surface, prescribed, vector, face.centroid())); ids.add(f); signs.add(sign); nodes.add(node);
		}
		faceIds = ids.stream().mapToInt(Integer::intValue).toArray(); faceSigns = signs.stream().mapToInt(Integer::intValue).toArray();
		projection = new LiquidControlVolumeProjection(cells, faces, gasNodes, nodes.stream().mapToInt(Integer::intValue).toArray());
		viscosity = new LiquidControlVolumeViscosity(cells, faces, coefficients, wall);
	}
	private static void requireGasNodes(LiquidControlVolumeMesh mesh, int[] liquid, int[] nodes, int count) {
		var roots = LiquidPhasePartition.gasComponents(mesh, liquid); var ambient = new boolean[liquid.length];
		for (var face : mesh.faces) {
			if (face.second() == -1 && liquid[face.first()] < 0) ambient[roots[face.first()]] = true;
			if (face.second() >= 0 && liquid[face.first()] < 0 && liquid[face.second()] < 0 && nodes[face.first()] != nodes[face.second()])
				throw new IllegalArgumentException("Connected gas geometry has inconsistent pressure nodes");
		}
		var support = new int[count]; Arrays.fill(support, -1);
		for (int c = 0; c < liquid.length; c++) if (liquid[c] < 0) {
			int node = nodes[c]; if (node < PRESCRIBED_GAS_PRESSURE || node >= count || ambient[roots[c]] != (node == -1))
				throw new IllegalArgumentException("Gas pressure node differs from its resolved chamber");
			if (node >= 0) {
				if (support[node] >= 0 && support[node] != roots[c]) throw new IllegalArgumentException("Separate gas chambers share one pressure node");
				support[node] = roots[c];
			}
		}
	}

	Result advance(double[] cellVelocity, double[] faceVelocity, double[] acceleration, double dt,
			double momentumTolerance, double continuityTolerance, int maximumIterations) {
		if (cellVelocity.length != 3 * liquid.length || acceleration.length != cellVelocity.length || faceVelocity.length != 3 * mesh.faces.size())
			throw new IllegalArgumentException("Incomplete phase mechanics velocity field");
		var incoming = new double[3 * liquidCells.length]; var traces = new double[3 * faceIds.length];
		for (int c = 0; c < liquidCells.length; c++) System.arraycopy(cellVelocity, 3 * liquidCells[c], incoming, 3 * c, 3);
		for (int f = 0; f < faceIds.length; f++) System.arraycopy(faceVelocity, 3 * faceIds[f], traces, 3 * f, 3);
		var viscous = viscosity.advance(incoming, traces, dt, momentumTolerance, maximumIterations); var predictor = viscous.cellVelocity().clone();
		for (int c = 0; c < liquidCells.length; c++) for (int a = 0; a < 3; a++) predictor[3 * c + a] += dt * acceleration[3 * liquidCells[c] + a];
		var projected = projection.project(predictor, new LiquidPolyhedron.Point(0, 0, 0), dt, continuityTolerance, maximumIterations);
		var rates = new Fraction[mesh.faces.size()]; Arrays.fill(rates, Fraction.ZERO); var primary = cellVelocity.clone();
		for (int c = 0; c < liquidCells.length; c++) System.arraycopy(projected.cellVelocity(), 3 * c, primary, 3 * liquidCells[c], 3);
		for (int f = 0; f < faceIds.length; f++) rates[faceIds[f]] = faceSigns[f] > 0 ? projected.volumeRate()[f] : projected.volumeRate()[f].negate();
		var extended = LiquidInterfaceVelocityExtension.extend(mesh, liquid, rates, primary, continuityTolerance, maximumIterations);
		return new Result(viscous, projected, extended, LiquidControlVolumeVelocity.reconstruct(mesh, extended.volumeRate(), extended.cellVelocity()));
	}
}
