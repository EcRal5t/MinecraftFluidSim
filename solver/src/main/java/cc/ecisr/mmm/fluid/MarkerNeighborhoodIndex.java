package cc.ecisr.mmm.fluid;

import java.util.Arrays;
import java.util.function.IntUnaryOperator;

/** Short-lived position index for a batch whose edits affect velocities only. */
final class MarkerNeighborhoodIndex {
	private final int sizeX, sizeY, sizeZ;
	private final int[] heads, next;

	MarkerNeighborhoodIndex(int sizeX, int sizeY, int sizeZ, int markerCount, IntUnaryOperator markerCell) {
		this.sizeX = sizeX; this.sizeY = sizeY; this.sizeZ = sizeZ;
		heads = new int[sizeX * sizeY * sizeZ]; Arrays.fill(heads, -1);
		next = new int[markerCount];
		for (int marker = 0; marker < markerCount; marker++) {
			int cell = markerCell.applyAsInt(marker);
			next[marker] = heads[cell]; heads[cell] = marker;
		}
	}

	int[] around(float x, float y, float z, float cellSize) {
		// The kernel spans 1.5 cells; a two-cell halo also covers float position subtraction at its edge.
		int cx = (int) Math.floor(x / cellSize), cy = (int) Math.floor(y / cellSize), cz = (int) Math.floor(z / cellSize);
		int[] markers = new int[Math.min(64, next.length)];
		int count = 0;
		for (int iz = Math.max(0, cz - 2); iz <= Math.min(sizeZ - 1, (long) cz + 2); iz++) {
			for (int iy = Math.max(0, cy - 2); iy <= Math.min(sizeY - 1, (long) cy + 2); iy++) {
				for (int ix = Math.max(0, cx - 2); ix <= Math.min(sizeX - 1, (long) cx + 2); ix++) {
					for (int marker = heads[(iz * sizeY + iy) * sizeX + ix]; marker >= 0; marker = next[marker]) {
						if (count == markers.length) markers = Arrays.copyOf(markers, Math.min(next.length, Math.max(1, count * 2)));
						markers[count++] = marker;
					}
				}
			}
		}
		markers = Arrays.copyOf(markers, count);
		Arrays.sort(markers); // Preserve the original summation and velocity-write order.
		return markers;
	}
}
