package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.granular.GranularParcelStore;
import cc.ecisr.mmm.granular.SolidVolume;

/** Finite parcel inertia in the liquid/gas pressure system, using the occupancy gradient and its transpose. */
final class GranularPressureCoupling {
	private final FluidDomain domain;
	private int count;
	private int[] rows = new int[0];
	private double[] gx = new double[0], gy = new double[0], gz = new double[0];
	private double[] boundaryX = new double[0], boundaryY = new double[0], boundaryZ = new double[0];
	private double[] inverseMass = new double[0];
	private final float[] liquidDiagonal, gasDiagonal;
	private final int[] cells = new int[8];
	private final double[] weights = new double[8], dx = new double[8], dy = new double[8], dz = new double[8];

	GranularPressureCoupling(FluidDomain domain) {
		this.domain = domain;
		liquidDiagonal = new float[domain.cellCount]; gasDiagonal = new float[domain.cellCount + 1];
	}

	void forEachGasNode(java.util.function.IntConsumer accept) {
		if (domain.granularState == null) return;
		for (int parcel = 0; parcel < domain.granularState.parcels.count(); parcel++) {
			int entries = gradient(domain.granularState.parcels.parcel(parcel));
			for (int slot = 0; slot < entries; slot++) if (domain.isAir(cells[slot])) {
				accept.accept(domain.gasNodeOfAirCell(cells[slot]));
			}
		}
	}

	/** Geometry and pressure row identities stay fixed throughout PCG. Negative rows encode gas nodes. */
	void prepare(float dt) {
		count = domain.granularState == null ? 0 : domain.granularState.parcels.count();
		ensureCapacity(count);
		double h = domain.config.cellSize(), cellVolume = h * h * h;
		for (int parcel = 0; parcel < count; parcel++) {
			var p = domain.granularState.parcels.parcel(parcel);
			inverseMass[parcel] = cellVolume / p.mass();
			boundaryX[parcel] = boundaryY[parcel] = boundaryZ[parcel] = 0;
			int entries = gradient(p);
			int base = parcel * 8;
			for (int slot = 0; slot < 8; slot++) {
				int index = base + slot;
				rows[index] = Integer.MAX_VALUE;
				gx[index] = gy[index] = gz[index] = 0;
				if (slot >= entries) continue;
				int cell = cells[slot];
				int node = domain.isAir(cell) ? domain.gasNodeOfAirCell(cell) : -1;
				int row = domain.isFluid(cell) && cell != domain.pressureAnchor ? cell
						: node > 0 && domain.projection.gasPressureUnknown[node] ? -node : Integer.MAX_VALUE;
				if (row == Integer.MAX_VALUE) {
					double pressure = domain.isAir(cell)
							? domain.gasPressureOfAirCell(cell) - domain.pressureReference : 0;
					boundaryX[parcel] += dx[slot] * pressure;
					boundaryY[parcel] += dy[slot] * pressure;
					boundaryZ[parcel] += dz[slot] * pressure;
				} else {
					// Several support cells may belong to the same lumped gas pressure.
					int target = index;
					for (int prior = base; prior < index; prior++) if (rows[prior] == row) { target = prior; break; }
					rows[target] = row;
					gx[target] += dx[slot]; gy[target] += dy[slot]; gz[target] += dz[slot];
				}
			}
			double scale = dt * inverseMass[parcel];
			double vx = p.velocityX() - scale * boundaryX[parcel];
			double vy = p.velocityY() - scale * boundaryY[parcel];
			double vz = p.velocityZ() - scale * boundaryZ[parcel];
			for (int index = base; index < base + 8; index++) {
				add(rows[index], domain.projection.rhs, domain.projection.gasRhs,
						domain.config.density() / dt * (gx[index] * vx + gy[index] * vy + gz[index] * vz));
			}
		}
	}

	void rebuildDiagonal() {
		rebuildDiagonal(parcel -> 1);
	}

	void rebuildDiagonal(java.util.function.IntToDoubleFunction inertiaRatio) {
		java.util.Arrays.fill(liquidDiagonal, 0); java.util.Arrays.fill(gasDiagonal, 0);
		for (int parcel = 0; parcel < count; parcel++) for (int index = parcel * 8; index < (parcel + 1) * 8; index++) {
			double value = domain.config.density() * inverseMass[parcel] * inertiaRatio.applyAsDouble(parcel)
					* (gx[index] * gx[index] + gy[index] * gy[index] + gz[index] * gz[index]);
			int row = rows[index];
			if (row == Integer.MAX_VALUE) continue;
			if (row >= 0) liquidDiagonal[row] += (float) value;
			else gasDiagonal[-row] += (float) value;
		}
	}
	float liquidDiagonal(int cell) { return liquidDiagonal[cell]; }
	float gasDiagonal(int node) { return gasDiagonal[node]; }

	void apply(float[] source, float[] gasSource, float[] result, float[] gasResult) {
		for (int parcel = 0; parcel < count; parcel++) {
			double x = 0, y = 0, z = 0;
			for (int index = parcel * 8; index < (parcel + 1) * 8; index++) {
				double pressure = value(rows[index], source, gasSource);
				x += gx[index] * pressure; y += gy[index] * pressure; z += gz[index] * pressure;
			}
			double scale = domain.config.density() * inverseMass[parcel];
			for (int index = parcel * 8; index < (parcel + 1) * 8; index++) {
				add(rows[index], result, gasResult, scale * (gx[index] * x + gy[index] * y + gz[index] * z));
			}
		}
	}

	void apply(double[] source, double[] gasSource, double[] result, double[] gasResult) {
		for (int parcel = 0; parcel < count; parcel++) {
			double x = 0, y = 0, z = 0;
			for (int index = parcel * 8; index < (parcel + 1) * 8; index++) {
				double pressure = value(rows[index], source, gasSource);
				x += gx[index] * pressure; y += gy[index] * pressure; z += gz[index] * pressure;
			}
			double scale = domain.config.density() * inverseMass[parcel];
			for (int index = parcel * 8; index < (parcel + 1) * 8; index++) {
				add(rows[index], result, gasResult, scale * (gx[index] * x + gy[index] * y + gz[index] * z));
			}
		}
	}

	void project(float dt) {
		project(dt, null, null);
	}

	void project(float dt, double[] liquidPressure, double[] gasPressure) {
		for (int parcel = 0; parcel < count; parcel++) {
			var p = domain.granularState.parcels.parcel(parcel);
			double x = boundaryX[parcel], y = boundaryY[parcel], z = boundaryZ[parcel];
			for (int index = parcel * 8; index < (parcel + 1) * 8; index++) {
				double pressure = liquidPressure == null
						? value(rows[index], domain.projection.pressure, domain.projection.gasPressure)
						: value(rows[index], liquidPressure, gasPressure);
				x += gx[index] * pressure; y += gy[index] * pressure; z += gz[index] * pressure;
			}
			double scale = dt * inverseMass[parcel];
			double vx = p.velocityX() - scale * x, vy = p.velocityY() - scale * y, vz = p.velocityZ() - scale * z;
			domain.granularState.parcels.overwrite(parcel, new GranularParcelStore.Parcel(p.x(), p.y(), p.z(),
					(float) vx, (float) vy, (float) vz, p.solidVolumeSvq(), p.mass(), p.materialHandle(),
					p.solidCompositionHandle(), p.contactAge(), p.flags()));
			domain.lastGranularBoundaryImpulseX += p.mass() * (vx - (float) vx);
			domain.lastGranularBoundaryImpulseY += p.mass() * (vy - (float) vy);
			domain.lastGranularBoundaryImpulseZ += p.mass() * (vz - (float) vz);
		}
	}

	void addBoundaryVelocity(double[] vector, int offset, double dt) {
		for (int parcel = 0; parcel < count; parcel++) {
			vector[offset + 3 * parcel] -= dt * inverseMass[parcel] * boundaryX[parcel];
			vector[offset + 3 * parcel + 1] -= dt * inverseMass[parcel] * boundaryY[parcel];
			vector[offset + 3 * parcel + 2] -= dt * inverseMass[parcel] * boundaryZ[parcel];
		}
	}

	void addPressureVelocity(double[] vector, int offset, double[] pressure) {
		for (int parcel = 0; parcel < count; parcel++) for (int index = parcel * 8; index < (parcel + 1) * 8; index++) {
			int row = rows[index];
			if (row == Integer.MAX_VALUE) continue;
			double scale = inverseMass[parcel] * pressure[row >= 0 ? row : domain.cellCount - row];
			vector[offset + 3 * parcel] -= scale * gx[index];
			vector[offset + 3 * parcel + 1] -= scale * gy[index];
			vector[offset + 3 * parcel + 2] -= scale * gz[index];
		}
	}

	void addVelocityDivergence(double[] vector, int offset, double[] result, double scale) {
		for (int parcel = 0; parcel < count; parcel++) for (int index = parcel * 8; index < (parcel + 1) * 8; index++) {
			int row = rows[index];
			if (row == Integer.MAX_VALUE) continue;
			result[row >= 0 ? row : domain.cellCount - row] -= scale * (gx[index] * vector[offset + 3 * parcel]
					+ gy[index] * vector[offset + 3 * parcel + 1] + gz[index] * vector[offset + 3 * parcel + 2]);
		}
	}

	/** Derivative of normalized trilinear solid fraction; all eight derivatives sum to zero. */
	private int gradient(GranularParcelStore.Parcel p) {
		double h = domain.config.cellSize();
		double x = p.x() / h - 0.5, y = p.y() / h - 0.5, z = p.z() / h - 0.5;
		int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y), z0 = (int) Math.floor(z);
		double fx = x - x0, fy = y - y0, fz = z - z0;
		double sum = 0, sumX = 0, sumY = 0, sumZ = 0;
		int entries = 0;
		for (int oz = 0; oz <= 1; oz++) for (int oy = 0; oy <= 1; oy++) for (int ox = 0; ox <= 1; ox++) {
			int cx = x0 + ox, cy = y0 + oy, cz = z0 + oz;
			if (cx < 0 || cx >= domain.sizeX || cy < 0 || cy >= domain.sizeY || cz < 0 || cz >= domain.sizeZ) continue;
			double wx = ox == 0 ? 1 - fx : fx, wy = oy == 0 ? 1 - fy : fy, wz = oz == 0 ? 1 - fz : fz;
			cells[entries] = domain.cellIndex(cx, cy, cz);
			weights[entries] = wx * wy * wz;
			dx[entries] = (ox == 0 ? -1 : 1) * wy * wz / h;
			dy[entries] = wx * (oy == 0 ? -1 : 1) * wz / h;
			dz[entries] = wx * wy * (oz == 0 ? -1 : 1) / h;
			sum += weights[entries]; sumX += dx[entries]; sumY += dy[entries]; sumZ += dz[entries];
			entries++;
		}
		double fraction = p.solidVolumeSvq() / (double) SolidVolume.SVQ_PER_CELL;
		for (int i = 0; i < entries; i++) {
			dx[i] = fraction * (dx[i] * sum - weights[i] * sumX) / (sum * sum);
			dy[i] = fraction * (dy[i] * sum - weights[i] * sumY) / (sum * sum);
			dz[i] = fraction * (dz[i] * sum - weights[i] * sumZ) / (sum * sum);
		}
		return entries;
	}

	private static double value(int row, float[] liquid, float[] gas) {
		return row == Integer.MAX_VALUE ? 0 : row >= 0 ? liquid[row] : gas[-row];
	}
	private static void add(int row, float[] liquid, float[] gas, double value) {
		if (row == Integer.MAX_VALUE) return;
		if (row >= 0) liquid[row] += (float) value; else gas[-row] += (float) value;
	}

	private static double value(int row, double[] liquid, double[] gas) {
		return row == Integer.MAX_VALUE ? 0 : row >= 0 ? liquid[row] : gas[-row];
	}
	private static void add(int row, double[] liquid, double[] gas, double value) {
		if (row == Integer.MAX_VALUE) return;
		if (row >= 0) liquid[row] += value; else gas[-row] += value;
	}
	private void ensureCapacity(int requested) {
		if (inverseMass.length >= requested) return;
		rows = new int[requested * 8]; gx = new double[requested * 8];
		gy = new double[requested * 8]; gz = new double[requested * 8];
		inverseMass = new double[requested]; boundaryX = new double[requested];
		boundaryY = new double[requested]; boundaryZ = new double[requested];
	}
}
