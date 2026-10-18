package mmm.fluid;


import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

/** Resolves positive PLIC contacts within each fixed liquid component, retaining their actual gas pieces. */
final class GasPhaseInterfaceResolver {
	private GasPhaseInterfaceResolver() { }

	record Contact(int node, int supportMask, List<Integer> pieces) { }

	static List<Contact> contacts(GasRuntimeGeometry frame, int cell) {
		var found = new TreeMap<Long, TreeSet<Integer>>();
		GasComplementGeometry geometry = frame.geometry();
		int[] size = {geometry.sizeX(), geometry.sizeY(), geometry.sizeZ()}, stride = {1, size[0], size[0] * size[1]};
		int[] coordinate = {cell % size[0], cell / size[0] % size[1], cell / (size[0] * size[1])};
		int seen=0;
		for (var stock : frame.liquidCells().rows().get(cell)) {
			int supportMask = stock.key().supportMask();
			if((seen&supportMask)!=0)continue;seen|=supportMask;
			for (int micro = 0; micro < 8; micro++) if ((supportMask & 1 << micro) != 0) {
				var same = geometry.piece(8 * cell + micro);
				if (same != null && same.normalizedVolume() < .125)
					add(found, frame, supportMask, same.id());
				if (!(frame.liquidFractionExact(cell, micro) > 0)) continue;
				for (int axis = 0; axis < 3; axis++) for (int sign : new int[]{-1, 1}) {
					int bit = micro >>> axis & 1;
					boolean crossing = bit == (sign > 0 ? 1 : 0);
					int peerCell = crossing ? cell + sign * stride[axis] : cell;
					if (crossing && (coordinate[axis] + sign < 0 || coordinate[axis] + sign >= size[axis])) continue;
					int peerMicro = micro ^ 1 << axis;
					var gas = geometry.piece(8 * peerCell + peerMicro);
					if (gas == null) continue;
					double ownFace = crossing ? (sign > 0 ? 1 : 0) : .5, peerFace = crossing ? (sign > 0 ? 0 : 1) : .5;
					double tangent = .5 * (micro >>> ((axis + 1) % 3) & 1), other = .5 * (micro >>> ((axis + 2) % 3) & 1);

					var planes = new ArrayList<GasFaceIntersection.Plane>(2);
					if (frame.liquidFractionExact(cell, micro) < 1) planes.add(facePlane(frame, cell, micro, axis, ownFace, true));
					if (gas.dryPlane() != null) planes.add(facePlane(frame, peerCell, peerMicro, axis, peerFace, false));
					if (GasFaceIntersection.area(tangent, other, .5, planes) > 0) add(found, frame, supportMask, gas.id());
				}
			}
		}
		var result = new ArrayList<Contact>(found.size());
		for (var entry : found.entrySet()) result.add(new Contact((int) (entry.getKey() >>> 8), (int) (entry.getKey() & 255), List.copyOf(entry.getValue())));
		return List.copyOf(result);
	}

	private static GasFaceIntersection.Plane facePlane(GasRuntimeGeometry frame, int cell, int micro, int axis, double coordinate, boolean wet) {
		double a = frame.planeNormal(cell, micro, (axis + 1) % 3);
		double b = frame.planeNormal(cell, micro, (axis + 2) % 3);
		double threshold = frame.planeOffset(cell, micro) - frame.planeNormal(cell, micro, axis) * coordinate;
		return new GasFaceIntersection.Plane(wet ? -a : a, wet ? -b : b, wet ? -threshold : threshold);
	}

	private static void add(TreeMap<Long, TreeSet<Integer>> found, GasRuntimeGeometry frame, int supportMask, int piece) {
		int node = frame.nodeOfPiece(piece);
		if (node >= 0) found.computeIfAbsent((long) node << 8 | supportMask, ignored -> new TreeSet<>()).add(piece);
	}

}
