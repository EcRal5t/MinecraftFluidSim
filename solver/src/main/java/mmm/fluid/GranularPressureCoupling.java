package mmm.fluid;

import mmm.granular.GranularParcelStore;

/** Finite parcel inertia in the liquid/gas pressure system, using the occupancy gradient and its transpose. */
final class GranularPressureCoupling {
	private final FluidDomain domain;
	private int count;
	private int[] rows = new int[0];
	private double[] gx = new double[0], gy = new double[0], gz = new double[0];
	private double[] boundaryX = new double[0], boundaryY = new double[0], boundaryZ = new double[0];
	private double[] inverseMass = new double[0];
	private final float[] liquidDiagonal;
	private float[] gasDiagonal;
	private static final int SUPPORT = SweptGranularVolumeGradient.MAX_SUPPORT;
	private final SweptGranularVolumeGradient gradient;
	private GranularParcelStore supportSource;
	private long supportRevision;
	private float supportDt;
	private int[] supportCounts = new int[0], supportCells = new int[0];
	private double[] supportX = new double[0], supportY = new double[0], supportZ = new double[0];


	GranularPressureCoupling(FluidDomain domain) {
		this.domain = domain;
		gradient = new SweptGranularVolumeGradient(domain);
		liquidDiagonal = new float[domain.cellCount]; gasDiagonal = new float[domain.cellCount + 1];
	}

	void forEachGasNode(float dt, java.util.function.IntConsumer accept) {
		prepareSupport(dt);
		for (int parcel = 0; parcel < count; parcel++) {
			for (int slot = 0; slot < supportCounts[parcel]; slot++) {
				int cell = supportCells[parcel * SUPPORT + slot];
				int node = PressureBoundaryView.wholeCellGasNode(domain, cell);
				if (domain.isAir(cell) && node >= 0) accept.accept(node);
			}
		}
	}

	/** The swept gradient depends only on the predictor parcels and the substep duration. */
	private void prepareSupport(float dt) {
		GranularParcelStore source = domain.granularState == null ? null : domain.granularState.parcels;
		count = source == null ? 0 : source.count();
		if (source == null) { supportSource = null; return; }
		long revision = source.mutationRevision();
		if (supportSource == source && supportRevision == revision && supportDt == dt) return;
		supportSource = null;
		ensureCapacity(count);
		for (int parcel = 0; parcel < count; parcel++) {
			int entries = gradient.build(source.parcel(parcel), dt), base = parcel * SUPPORT;
			supportCounts[parcel] = entries;
			System.arraycopy(gradient.cells, 0, supportCells, base, entries);
			System.arraycopy(gradient.x, 0, supportX, base, entries);
			System.arraycopy(gradient.y, 0, supportY, base, entries);
			System.arraycopy(gradient.z, 0, supportZ, base, entries);
		}
		supportRevision = revision;
		supportDt = dt;
		supportSource = source;
	}

	/** Geometry and pressure row identities stay fixed throughout PCG. Negative rows encode gas nodes. */
	void prepare(float dt) {
		prepareSupport(dt);
		int gasNodes = domain.gasState.network.nodeCount();
		domain.projection.ensureGasCapacity(gasNodes);
		if (gasDiagonal.length <= gasNodes) gasDiagonal = new float[gasNodes + 1];
		double h = domain.config.cellSize(), cellVolume = h * h * h;
		for (int parcel = 0; parcel < count; parcel++) {
			var p = domain.granularState.parcels.parcel(parcel);
			inverseMass[parcel] = cellVolume / p.mass();
			boundaryX[parcel] = boundaryY[parcel] = boundaryZ[parcel] = 0;
			int entries = supportCounts[parcel];
			int base = parcel * SUPPORT;
			for (int slot = 0; slot < SUPPORT; slot++) {
				int index = base + slot;
				rows[index] = Integer.MAX_VALUE;
				gx[index] = gy[index] = gz[index] = 0;
				if (slot >= entries) continue;
				int cell = supportCells[index];
				int node = PressureBoundaryView.wholeCellGasNode(domain, cell);
				if (!domain.isAir(cell)) node = -1;
				int row = domain.isFluid(cell) && cell != domain.pressureAnchor ? cell
						: node > 0 && domain.projection.gasPressureUnknown[node] ? -node : Integer.MAX_VALUE;
				if (row == Integer.MAX_VALUE) {
					double pressure = node >= 0
							? domain.gasBoundary.nodeGaugePressurePa(node) - domain.pressureReference : 0;
					boundaryX[parcel] += supportX[index] * pressure;
					boundaryY[parcel] += supportY[index] * pressure;
					boundaryZ[parcel] += supportZ[index] * pressure;
				} else {
					// Several support cells may belong to the same lumped gas pressure.
					int target = index;
					for (int prior = base; prior < index; prior++) if (rows[prior] == row) { target = prior; break; }
					rows[target] = row;
					gx[target] += supportX[index]; gy[target] += supportY[index]; gz[target] += supportZ[index];
				}
			}
			double scale = dt * inverseMass[parcel];
			double vx = p.velocityX() - scale * boundaryX[parcel];
			double vy = p.velocityY() - scale * boundaryY[parcel];
			double vz = p.velocityZ() - scale * boundaryZ[parcel];
			for (int index = base; index < base + SUPPORT; index++) {
				add(rows[index], domain.projection.rhs, domain.projection.gasRhs,
						domain.config.density() / dt * (gx[index] * vx + gy[index] * vy + gz[index] * vz));
			}
		}
	}

	void connectTransferSupport(MarkerTransferBudget budget) {
		for (int parcel = 0; parcel < count; parcel++) for (int index = parcel * SUPPORT; index < (parcel + 1) * SUPPORT; index++) {
			int row = rows[index]; if (row == Integer.MAX_VALUE) continue;
			if (gx[index] != 0) budget.connect(budget.pressureRow(row), budget.parcel(parcel, 0));
			if (gy[index] != 0) budget.connect(budget.pressureRow(row), budget.parcel(parcel, 1));
			if (gz[index] != 0) budget.connect(budget.pressureRow(row), budget.parcel(parcel, 2));
		}
	}

	void rebuildDiagonal() {
		rebuildDiagonal(parcel -> 1);
	}

	void rebuildDiagonal(java.util.function.IntToDoubleFunction inertiaRatio) {
		java.util.Arrays.fill(liquidDiagonal, 0); java.util.Arrays.fill(gasDiagonal, 0);
		for (int parcel = 0; parcel < count; parcel++) for (int index = parcel * SUPPORT; index < (parcel + 1) * SUPPORT; index++) {
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
			for (int index = parcel * SUPPORT; index < (parcel + 1) * SUPPORT; index++) {
				double pressure = value(rows[index], source, gasSource);
				x += gx[index] * pressure; y += gy[index] * pressure; z += gz[index] * pressure;
			}
			double scale = domain.config.density() * inverseMass[parcel];
			for (int index = parcel * SUPPORT; index < (parcel + 1) * SUPPORT; index++) {
				add(rows[index], result, gasResult, scale * (gx[index] * x + gy[index] * y + gz[index] * z));
			}
		}
	}

	void apply(double[] source, double[] gasSource, double[] result, double[] gasResult) {
		for (int parcel = 0; parcel < count; parcel++) {
			double x = 0, y = 0, z = 0;
			for (int index = parcel * SUPPORT; index < (parcel + 1) * SUPPORT; index++) {
				double pressure = value(rows[index], source, gasSource);
				x += gx[index] * pressure; y += gy[index] * pressure; z += gz[index] * pressure;
			}
			double scale = domain.config.density() * inverseMass[parcel];
			for (int index = parcel * SUPPORT; index < (parcel + 1) * SUPPORT; index++) {
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
			for (int index = parcel * SUPPORT; index < (parcel + 1) * SUPPORT; index++) {
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
		for (int parcel = 0; parcel < count; parcel++) for (int index = parcel * SUPPORT; index < (parcel + 1) * SUPPORT; index++) {
			int row = rows[index];
			if (row == Integer.MAX_VALUE) continue;
			double scale = inverseMass[parcel] * pressure[row >= 0 ? row : domain.cellCount - row];
			vector[offset + 3 * parcel] -= scale * gx[index];
			vector[offset + 3 * parcel + 1] -= scale * gy[index];
			vector[offset + 3 * parcel + 2] -= scale * gz[index];
		}
	}

	void addVelocityDivergence(double[] vector, int offset, double[] result, double scale) {
		for (int parcel = 0; parcel < count; parcel++) for (int index = parcel * SUPPORT; index < (parcel + 1) * SUPPORT; index++) {
			int row = rows[index];
			if (row == Integer.MAX_VALUE) continue;
			result[row >= 0 ? row : domain.cellCount - row] -= scale * (gx[index] * vector[offset + 3 * parcel]
					+ gy[index] * vector[offset + 3 * parcel + 1] + gz[index] * vector[offset + 3 * parcel + 2]);
		}
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
		rows = new int[requested * SUPPORT]; gx = new double[requested * SUPPORT];
		gy = new double[requested * SUPPORT]; gz = new double[requested * SUPPORT];
		inverseMass = new double[requested]; boundaryX = new double[requested];
		boundaryY = new double[requested]; boundaryZ = new double[requested];
		supportCounts = new int[requested]; supportCells = new int[requested * SUPPORT];
		supportX = new double[requested * SUPPORT]; supportY = new double[requested * SUPPORT];
		supportZ = new double[requested * SUPPORT];
	}
}
