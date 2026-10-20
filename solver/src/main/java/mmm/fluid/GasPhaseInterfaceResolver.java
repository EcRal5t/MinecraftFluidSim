package mmm.fluid;


import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

/** Finite-volume wet/dry contacts on the common micro grid and fixed open supports. */
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
				for (int axis = 0; axis < 3; axis++) for (int sign=-1;sign<=1;sign+=2) {
					int bit = micro >>> axis & 1;
					boolean crossing = bit == (sign > 0 ? 1 : 0);
					int peerCell = crossing ? cell + sign * stride[axis] : cell;
					if (crossing && (coordinate[axis] + sign < 0 || coordinate[axis] + sign >= size[axis])) continue;
					int peerMicro = micro ^ 1 << axis;
					var gas = geometry.piece(8 * peerCell + peerMicro);
					if (gas == null) continue;
					// A wet micro control volume touches its adjacent positive dry volume.
					// Sub-micro films share this contact; fixed masks and gas ownership
					// still separate walls, liquid seals and independent chambers.
					add(found, frame, supportMask, gas.id());
				}
			}
		}
		var result = new ArrayList<Contact>(found.size());
		for (var entry : found.entrySet()) result.add(new Contact((int) (entry.getKey() >>> 8), (int) (entry.getKey() & 255), List.copyOf(entry.getValue())));
		return List.copyOf(result);
	}

	private static void add(TreeMap<Long, TreeSet<Integer>> found, GasRuntimeGeometry frame, int supportMask, int piece) {
		int node = frame.nodeOfPiece(piece);
		if (node >= 0) found.computeIfAbsent((long) node << 8 | supportMask, ignored -> new TreeSet<>()).add(piece);
	}

}
