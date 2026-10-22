package mmm.fluid;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** Wet/dry phase contacts on coarse fixed supports and their shared open ports. */
final class GasPhaseInterfaceResolver {
	private GasPhaseInterfaceResolver() { }

	record Contact(int node, int supportMask, List<GasComplementGeometry.Component> components) { }

	static List<Contact> contacts(GasRuntimeGeometry frame, int cell) {
		var found = new TreeMap<Long, TreeMap<Integer, GasComplementGeometry.Component>>();
		var geometry = frame.geometry();
		int nx = geometry.sizeX(), ny = geometry.sizeY(), nz = geometry.sizeZ();
		int seen = 0;
		for (var stock : frame.liquidCells().rows().get(cell)) {
			int support = stock.key().supportMask();
			if (stock.volume().signum() <= 0 || (seen & support) != 0) continue;
			seen |= support;
			// Liquid and gas in one coarse support share its single physical owner.
			add(found, frame, support, geometry.component(cell, support));
			for (int axis = 0; axis < 3; axis++) {
				int coordinate = axis == 0 ? cell % nx : axis == 1 ? cell / nx % ny : cell / (nx * ny);
				int size = axis == 0 ? nx : axis == 1 ? ny : nz;
				int stride = axis == 0 ? 1 : axis == 1 ? nx : nx * ny;
				for (int sign = -1; sign <= 1; sign += 2) {
					if (coordinate + sign < 0 || coordinate + sign >= size) continue;
					int peerCell = cell + sign * stride;
					int side = sign > 0 ? 1 : 0;
					int seenPeerSupports = 0;
					for (int bits = support; bits != 0; bits &= bits - 1) {
						int micro = Integer.numberOfTrailingZeros(bits);
						if ((micro >>> axis & 1) != side || !(frame.liquidFractionExact(cell, micro) > 0)) continue;
						int peer = 8 * peerCell + (micro ^ 1 << axis);
						// Only an actual dry trace on this shared port creates a
						// neighbouring contact. A remote dry part of the same support does not.
						var gas = geometry.componentAtMicro(peer);
						if (gas == null || (gas.dryMicroMask() & 1 << (peer & 7)) == 0
								|| (seenPeerSupports & gas.supportMask()) != 0) continue;
						seenPeerSupports |= gas.supportMask();
						add(found, frame, support, gas);
					}
				}
			}
		}
		var result = new ArrayList<Contact>(found.size());
		for (var entry : found.entrySet()) {
			result.add(new Contact((int) (entry.getKey() >>> 8), (int) (entry.getKey() & 255),
					List.copyOf(entry.getValue().values())));
		}
		return List.copyOf(result);
	}

	private static void add(TreeMap<Long, TreeMap<Integer, GasComplementGeometry.Component>> found,
	                        GasRuntimeGeometry frame, int support, GasComplementGeometry.Component component) {
		if (component == null) return;
		int node = frame.topology().nodeOfComponent(component.cell(), component.supportMask());
		if (node >= 0) {
			found.computeIfAbsent((long) node << 8 | support, ignored -> new TreeMap<>())
					.put(component.cell(), component);
		}
	}
}
