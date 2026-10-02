package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.thermal.ThermalCarrierTransport;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Continuous positive particle diffusion in a full, rectangular, single-phase liquid domain. */
final class LiquidThermalConduction {
	private static final double MOMENT = latticeMoment();
	private LiquidThermalConduction() { }

	/** Returns null when the current geometry requires the existing cut-cell condensed transport. */
	static List<ThermalCarrierTransport.Contact> contacts(FluidDomain domain) {
		var markers = domain.thermalMarkerContext();
		var thermal = domain.thermalState;
		if (thermal == null || markers.activeParcelCount() != 0 || !markers.depositKeys().isEmpty()
				|| !thermal.coherentPhases().snapshot().isEmpty() || domain.totalVolume() != domain.cellCount) return null;
		int phase = -1;
		for (int cell = 0; cell < domain.cellCount; cell++) {
			int entry = domain.markerGrid.cellPhaseHead[cell];
			if (domain.geometry.cutCells.openCellVolume(cell) != 1 || entry < 0
					|| domain.markerGrid.phaseEntryNext[entry] >= 0) return null;
			int current = domain.markerGrid.phaseEntryLabel[entry];
			if (phase >= 0 && phase != current) return null;
			phase = current;
		}
		double h = domain.config.cellSize();
		int n = markers.markerCount();
		double[][] position = new double[n][3];
		double[] k = new double[n], volume = new double[n];
		int[] head = new int[domain.cellCount], next = new int[n];
		Arrays.fill(head, -1);
		for (int p = 0; p < n; p++) {
			position[p][0] = domain.particles.positionX(p) / h;
			position[p][1] = domain.particles.positionY(p) / h;
			position[p][2] = domain.particles.positionZ(p) / h;
			int handle = thermal.thermalMaterialHandleForFluid(markers.fluidMaterialHandle(p));
			k[p] = thermal.registry().material(handle).conductivityEuPerSecondMeterK();
			volume[p] = markers.volume(p);
			int cell = markers.cell(p); next[p] = head[cell]; head[cell] = p;
		}
		int[] size = {domain.sizeX, domain.sizeY, domain.sizeZ};
		var contacts = new ArrayList<ThermalCarrierTransport.Contact>();
		double[][] wallMoment = new double[n][6];
		for (int p = 0; p < n; p++) {
			if (k[p] == 0) continue;
			int cell = markers.cell(p), cx = cell % size[0], cy = cell / size[0] % size[1], cz = cell / (size[0] * size[1]);
			for (int z = Math.max(0, cz - 1); z <= Math.min(size[2] - 1, cz + 1); z++)
				for (int y = Math.max(0, cy - 1); y <= Math.min(size[1] - 1, cy + 1); y++)
					for (int x = Math.max(0, cx - 1); x <= Math.min(size[0] - 1, cx + 1); x++)
						for (int q = head[domain.cellIndex(x, y, z)]; q >= 0; q = next[q]) {
							if (q < p || k[q] == 0) continue;
							double low = Math.min(k[p], k[q]), high = Math.max(k[p], k[q]);
							double scale = (low * (2 / (1 + low / high))) * h * volume[p] * volume[q] / MOMENT;
							double conductance = reflectedKernel(position[p], position[q], size, scale, wallMoment[p], wallMoment[q], p == q);
							if (p == q || conductance == 0) continue;
							contacts.add(new ThermalCarrierTransport.Contact(p, q, conductance, Double.NaN));
						}
		}
		for (int p = 0; p < n; p++) for (int axis = 0; axis < 3; axis++) for (int side = 0; side < 2; side++) {
			double distance = side == 0 ? position[p][axis] : size[axis] - position[p][axis];
			if (distance >= 1 || k[p] == 0) continue;
			int cell = markers.cell(p), x = cell % size[0], y = cell / size[0] % size[1], z = cell / (size[0] * size[1]);
			// Reservoir faces belong to the outermost cell even when the compact support reaches farther.
			int coordinate = axis == 0 ? x : axis == 1 ? y : z;
			if (coordinate != (side == 0 ? 0 : size[axis] - 1)) continue;
			var boundary = domain.thermalFixedBoundary(x, y, z, 2 * axis + side);
			if (boundary == null || boundary.contactAreaSquareMeters() == 0) continue;
			// Replace the reflected affine ghost gradient by its positive contact with the wall.
			double fraction = boundary.contactAreaSquareMeters() / (h * h);
			if (!(Double.isFinite(fraction) && fraction > 0 && fraction <= 1))
				throw new IllegalArgumentException("Liquid thermal wall contact exceeds its face");
			double conductance = wallMoment[p][2 * axis + side] / distance * fraction;
			if (conductance == 0) continue;
			if (!(Double.isFinite(conductance) && conductance > 0))
				throw new IllegalArgumentException("Liquid thermal wall quadrature has a non-positive first moment");
			contacts.add(new ThermalCarrierTransport.Contact(p, -1, conductance, boundary.surfaceTemperatureK()));
		}
		return contacts;
	}

	private static double reflectedKernel(double[] p, double[] q, int[] size, double scale,
	                                     double[] pMoment, double[] qMoment, boolean self) {
		double sum = 0;
		for (int image = 0; image < 27; image++) {
			double r2 = 0; boolean available = true;
			int code = image;
			for (int axis = 0; axis < 3; axis++) {
				double coordinate = q[axis];
				int reflection = code % 3; code /= 3;
				if (reflection == 1 && p[axis] < 1 && q[axis] < 1) coordinate = -coordinate;
				else if (reflection == 2 && p[axis] > size[axis] - 1 && q[axis] > size[axis] - 1) coordinate = 2 * size[axis] - coordinate;
				else if (reflection != 0) { available = false; break; }
				double d = coordinate - p[axis]; r2 += d * d;
			}
			if (!available || r2 >= 1) continue;
			double v = 1 - Math.sqrt(r2), g = scale * v * v * v;
			sum += g;
			code = image;
			for (int axis = 0; axis < 3; axis++) {
				int reflection = code % 3; code /= 3;
				if (reflection == 0) continue;
				int direction = 2 * axis + reflection - 1;
				pMoment[direction] += g * 2 * (reflection == 1 ? q[axis] : size[axis] - q[axis]);
				if (!self) qMoment[direction] += g * 2 * (reflection == 1 ? p[axis] : size[axis] - p[axis]);
			}
		}
		return sum;
	}

	/** Exact second moment of the fixed h/2 Cartesian quadrature, rather than a fitted diffusivity. */
	private static double latticeMoment() {
		double sum = 0, spacing = 1.0 / ConservativeMarkerSampling.AXIS_SAMPLES;
		int extent = ConservativeMarkerSampling.AXIS_SAMPLES - 1;
		for (int z = -extent; z <= extent; z++) for (int y = -extent; y <= extent; y++) for (int x = -extent; x <= extent; x++) {
			double r = spacing * Math.sqrt(x * x + y * y + z * z);
			if (r >= 1) continue;
			double v = 1 - r;
			sum += v * v * v * x * x * spacing * spacing;
		}
		return ConservativeMarkerSampling.NOMINAL_MARKER_VOLUME * 0.5 * sum;
	}
}
