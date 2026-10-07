package cc.ecisr.mmm.fluid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;

/** Liquid mechanics resolves material facets and actual gas chambers independently of auxiliary gas cuts. */
final class LiquidMechanicalFaceInput {
	private record Interface(int contact, double surfaceCoefficient) { }
	final LiquidControlVolumeMesh mesh;
	final int[] phaseCells, fragments, pressureNodes, contactNodes;
	final double[] surfaceCoefficient;

	LiquidMechanicalFaceInput(LiquidControlVolumeMesh phase, int[] liquid, int[] gasContacts, int[] gasRows,
			double[] coefficient, LiquidPhaseMechanics.InterfacePressure pressure) {
		if (liquid.length != phase.cells.size() || gasContacts.length != liquid.length || gasRows.length != liquid.length
				|| coefficient.length != phase.faces.size() || pressure == null) throw new IllegalArgumentException("Incomplete physical face input");
		phaseCells = java.util.stream.IntStream.range(0, liquid.length).filter(c -> liquid[c] >= 0).toArray(); fragments = new int[phaseCells.length];
		var compact = new int[liquid.length]; Arrays.fill(compact, -1);
		var cells = new ArrayList<LiquidControlVolumeProjection.Cell>();
		for (int c = 0; c < phaseCells.length; c++) { compact[phaseCells[c]] = c; fragments[c] = liquid[phaseCells[c]]; cells.add(phase.cells.get(phaseCells[c])); }
		var faces = new ArrayList<LiquidControlVolumeProjection.Face>(); var polygons = new ArrayList<LiquidExactPolygon>();
		var sourceFaces = new ArrayList<Integer>(); var rows = new ArrayList<Integer>(); var contacts = new ArrayList<Integer>(); var labels = new ArrayList<Integer>();
		var interfaces = new LinkedHashMap<Interface, Integer>();
		for (int f = 0; f < phase.faces.size(); f++) {
			var face = phase.faces.get(f); boolean a = liquid[face.first()] >= 0, b = face.second() >= 0 && liquid[face.second()] >= 0;
			if (!a && !b) continue;
			int source = a ? face.first() : face.second(), neighbor = a ? face.second() : face.first();
			int second = a && b ? compact[neighbor] : neighbor == -2 ? -2 : -1;
			int contact = second == -1 ? neighbor < 0 ? 0 : gasContacts[neighbor] : -1;
			if (second == -1 && contact < 0) throw new IllegalArgumentException("Physical interface has no gas chamber");
			int row = second == -1 && neighbor >= 0 ? gasRows[neighbor] : -1;
			if (row == LiquidPhaseMechanics.PRESCRIBED_GAS_PRESSURE) row = -1;
			var polygon = a ? phase.exactPolygons.get(f) : phase.exactPolygons.get(f).reversed();
			double prescribed = second == -1 ? pressure.at(f, neighbor) : a ? face.prescribed() : -face.prescribed();
			faces.add(new LiquidControlVolumeProjection.Face(compact[source], second, polygon.surface(), prescribed, polygon)); polygons.add(polygon);
			sourceFaces.add(f); rows.add(row); contacts.add(contact);
			labels.add(interfaces.computeIfAbsent(new Interface(contact, coefficient[f]), ignored -> interfaces.size()));
		}
		var assembled = LiquidControlVolumeMesh.assemble(cells, faces, polygons, labels.stream().mapToInt(Integer::intValue).toArray()); mesh = assembled.mesh();
		pressureNodes = new int[mesh.faces.size()]; contactNodes = pressureNodes.clone(); surfaceCoefficient = new double[mesh.faces.size()];
		for (int f = 0; f < mesh.faces.size(); f++) {
			int source = assembled.sources()[f]; pressureNodes[f] = rows.get(source); contactNodes[f] = contacts.get(source);
			surfaceCoefficient[f] = coefficient[sourceFaces.get(source)];
		}
	}
	double[] faceVelocity(double[] velocity) {
		if (velocity.length != 3 * mesh.cells.size()) throw new IllegalArgumentException("Incomplete physical cell velocity");
		var result = new double[3 * mesh.faces.size()];
		for (int f = 0; f < mesh.faces.size(); f++) {
			var face = mesh.faces.get(f);
			for (int a = 0; a < 3; a++) result[3 * f + a] = face.second() >= 0
					? 0.5 * (velocity[3 * face.first() + a] + velocity[3 * face.second() + a]) : velocity[3 * face.first() + a];
		}
		return result;
	}
}
