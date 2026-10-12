package mmm.fluid;

import java.util.ArrayList;
import java.util.HashMap;
import mmm.units.SignedDyadic;

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
	/** Exact pore capacities in canonical component order, before independently arriving liquid. */
	static SignedDyadic[] capacities(int mask, SignedDyadic totalEffectiveOpen) {
		if (mask < 0 || mask > 255 || totalEffectiveOpen == null)
			throw new IllegalArgumentException("Component capacity requires a collision mask and effective open volume");
		int[] components = COMPONENTS[mask];
		SignedDyadic open = SignedDyadic.of(Integer.bitCount(~mask & 255), -3);
		SignedDyadic solid = open.subtract(totalEffectiveOpen);
		if (solid.signum() < 0 || solid.compareTo(open) > 0 || !solid.fitsLongUnits(-24))
			throw new IllegalStateException("Open component capacity requires the existing integer solid-volume lattice");
		SignedDyadic[] result = new SignedDyadic[components.length];
		if (components.length == 0) return result;
		SignedDyadic[] weights = new SignedDyadic[components.length];
		// Keep the solid-capacity lattice even when the whole-cell capacity has a compact exponent.
		for (int i = 0; i < weights.length; i++) weights[i] = SignedDyadic.of(Integer.bitCount(components[i]), -24);
		long[] solidShares = ExactVolumeAllocation.allocate(solid.longUnitsExact(-24), weights);
		for (int i = 0; i < result.length; i++) result[i] = SignedDyadic.of(Integer.bitCount(components[i]), -3)
				.subtract(SignedDyadic.of(solidShares[i], -24));
		return result;
	}
	static boolean needed(FluidDomain domain) {
		for (int cell = 0; cell < domain.cellCount; cell++) if (COMPONENTS[domain.geometry.cutCells.mask(cell)].length > 1) return true;
		return false;
	}

	/** Validates incoming physical cell/component stock before a restore changes any domain owner. */
	static void requireRestoreCapacity(FlipParticleState state) {
		float[] columns = state.positionVolume(); byte[] masks = state.collisionMicroMasks();
		var volume = new HashMap<Long, SignedDyadic>();
		for (int marker = 0; marker < state.particleCount(); marker++) {
			int base = 4 * marker;
			float x = columns[base], y = columns[base + 1], z = columns[base + 2], h = state.cellSize();
			int cell = (int) Math.floor(x / h) + state.sizeX()
					* ((int) Math.floor(y / h) + state.sizeY() * (int) Math.floor(z / h));
			int component = positionSupport(Byte.toUnsignedInt(masks[cell]), h, x, y, z);
			long key = ((long) cell << 8) | component;
			SignedDyadic after = volume.getOrDefault(key, SignedDyadic.ZERO).add(SignedDyadic.of(columns[base + 3]));
			if (after.compareTo(SignedDyadic.of(Integer.bitCount(component), -3)) > 0)
				throw new IllegalArgumentException("Restored liquid volume exceeds its connected open capacity at cell " + cell);
			volume.put(key, after);
		}
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
		return positionSupport(domain.geometry.cutCells.mask(cell), domain.config.cellSize(), x, y, z);
	}
	static int positionSupport(int mask, float cellSize, float x, float y, float z) {
		int micro = positionMicro(cellSize, x, y, z);
		int result = support(mask, micro);
		if (result == 0) throw new IllegalStateException("Liquid source is outside its open micro component");
		return result;
	}
	static int positionMicro(float cellSize, float x, float y, float z) {
		float spacing = cellSize / 2;
		float qx = x / spacing, qy = y / spacing, qz = z / spacing;
		if (!(spacing > 0 && Float.isFinite(spacing)) || spacing * 2 != cellSize || !representableIndex(qx)
				|| !representableIndex(qy) || !representableIndex(qz))
			throw new IllegalArgumentException("Liquid micro coordinates exceed their binary32 representation");
		return ((int) Math.floor(qx) & 1) | (((int) Math.floor(qy) & 1) << 1)
				| (((int) Math.floor(qz) & 1) << 2);
	}
	private static boolean representableIndex(float value) {
		return Float.isFinite(value) && value >= 0 && (double) value <= Integer.MAX_VALUE;
	}
}
