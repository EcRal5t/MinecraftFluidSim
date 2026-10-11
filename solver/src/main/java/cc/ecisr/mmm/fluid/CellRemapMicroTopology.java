package cc.ecisr.mmm.fluid;

import java.util.ArrayList;

/** Face-connected open support inside each fixed eight-micro-box collision mask. */
final class CellRemapMicroTopology {
	private static final int[][] SUPPORT = new int[256][8];
	private static final int[][] COMPONENTS = new int[256][];
	static {
		for (int mask = 0; mask < 256; mask++) {
			var components = new ArrayList<Integer>(4);
			int unseen = ~mask & 255;
			while (unseen != 0) {
				int component = Integer.lowestOneBit(unseen), frontier = component;
				while (frontier != 0) {
					int next = 0;
					for (int bit = 0; bit < 8; bit++) if ((frontier & 1 << bit) != 0)
						for (int axis = 0; axis < 3; axis++) next |= 1 << (bit ^ 1 << axis);
					next &= unseen & ~component; component |= next; frontier = next;
				}
				for (int bit = 0; bit < 8; bit++) if ((component & 1 << bit) != 0) SUPPORT[mask][bit] = component;
				components.add(component);
				unseen &= ~component;
			}
			COMPONENTS[mask] = components.stream().mapToInt(Integer::intValue).toArray();
		}
	}

	private CellRemapMicroTopology() { }

	static int support(int mask, int micro) { return SUPPORT[mask][micro]; }

	static int[] components(int mask) {
		return COMPONENTS[mask];
	}
	static boolean needed(FluidDomain domain) {
		for (int cell = 0; cell < domain.cellCount; cell++) if (COMPONENTS[domain.geometry.cutCells.mask(cell)].length > 1) return true;
		return false;
	}

	static int markerSupport(FluidDomain domain, int particle) {
		return positionSupport(domain, domain.particleCellIndex(particle), domain.particles.positionX(particle),
				domain.particles.positionY(particle), domain.particles.positionZ(particle));
	}
	static int markerSupport(FluidDomain domain, CellCarrierRemapPlan.Marker marker) {
		int cell = marker.cell(); float h = domain.config.cellSize();
		return positionSupport(domain, cell, (float) ((cell % domain.sizeX + .5 + marker.offsetX()) * h),
				(float) ((cell / domain.sizeX % domain.sizeY + .5 + marker.offsetY()) * h),
				(float) ((cell / (domain.sizeX * domain.sizeY) + .5 + marker.offsetZ()) * h));
	}
	private static int positionSupport(FluidDomain domain, int cell, float x, float y, float z) {
		float spacing = domain.config.cellSize() / 2;
		int micro = ((int) Math.floor(x / spacing) & 1) | (((int) Math.floor(y / spacing) & 1) << 1)
				| (((int) Math.floor(z / spacing) & 1) << 2);
		int result = support(domain.geometry.cutCells.mask(cell), micro);
		if (result == 0) throw new IllegalStateException("Liquid source is outside its open micro component");
		return result;
	}
}
