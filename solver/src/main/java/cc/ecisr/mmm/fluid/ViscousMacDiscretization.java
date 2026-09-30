package cc.ecisr.mmm.fluid;

/** Builds normal cell stresses and coupled edge shear stresses on the pressure MAC geometry. */
final class ViscousMacDiscretization {
	interface Geometry {
		boolean liquid(int cell);
		double volume(int cell);
		double density(int cell);
		double viscosity(int cell);
		double aperture(int axis, int x, int y, int z);
		double wallArea(int cell, int normalAxis);
	}

	private final int nx, ny, nz;
	private final double h;
	private final int[] offsets;
	private final Geometry geometry;
	private final FluidSolverConfig.WallCondition wall;
	final ViscousStressOperator operator;
	private final double[] volumes, viscosities, densities;
	private final boolean[] liquid;
	private final double[] apertures;

	ViscousMacDiscretization(int nx, int ny, int nz, double h, Geometry geometry,
			FluidSolverConfig.WallCondition wall) {
		this.nx = nx; this.ny = ny; this.nz = nz; this.h = h; this.geometry = geometry; this.wall = wall;
		offsets = new int[] {0, (nx + 1) * ny * nz, (nx + 1) * ny * nz + nx * (ny + 1) * nz};
		operator = new ViscousStressOperator(offsets[2] + nx * ny * (nz + 1));
		volumes = new double[nx * ny * nz]; viscosities = new double[volumes.length];
		densities = new double[volumes.length]; liquid = new boolean[volumes.length];
		apertures = new double[operator.mass.length];
	}

	void assemble(float[][] velocity) {
		operator.clear();
		for (int cell = 0; cell < liquid.length; cell++) {
			liquid[cell] = geometry.liquid(cell);
			volumes[cell] = liquid[cell] ? geometry.volume(cell) : 0;
			if (!liquid[cell]) continue;
			densities[cell] = geometry.density(cell); viscosities[cell] = geometry.viscosity(cell);
			if (!(volumes[cell] > 0) || !Double.isFinite(volumes[cell])
					|| !(densities[cell] > 0) || !Double.isFinite(densities[cell])
					|| !(viscosities[cell] > 0) || !Double.isFinite(viscosities[cell])) {
				throw new IllegalStateException("Invalid viscous liquid control volume/properties at cell " + cell);
			}
		}
		for (int axis = 0; axis < 3; axis++) {
			int dx = axis == 0 ? 1 : 0, dy = axis == 1 ? 1 : 0, dz = axis == 2 ? 1 : 0;
			for (int z = 0; z < nz + dz; z++) for (int y = 0; y < ny + dy; y++) for (int x = 0; x < nx + dx; x++) {
				int face = face(axis, x, y, z);
				double open = geometry.aperture(axis, x, y, z);
				if (!(open >= 0 && open <= 1) || !Double.isFinite(open)) throw new IllegalStateException("Invalid viscous aperture");
				apertures[face] = open;
				int first = cell(x - dx, y - dy, z - dz), second = cell(x, y, z);
				if (open == 0 || !wet(first) && !wet(second)) continue;
				// Each adjacent liquid cell contributes one half of its actual liquid volume.
				// Aperture controls stress area, never substitutes for the sliver capacity.
				double mass = 0.5 * h * h * h * (cellMass(first) + cellMass(second));
				operator.active(face, axis, mass, velocity[axis][face - offsets[axis]]);
			}
		}
		for (int z = 0; z < nz; z++) for (int y = 0; y < ny; y++) for (int x = 0; x < nx; x++) {
			int cell = cell(x, y, z);
			if (!wet(cell)) continue;
			for (int axis = 0; axis < 3; axis++) {
				int lo = face(axis, x, y, z);
				int hi = face(axis, x + (axis == 0 ? 1 : 0), y + (axis == 1 ? 1 : 0), z + (axis == 2 ? 1 : 0));
				operator.add(2 * viscosities[cell] * h * volumes[cell], hi, 1, lo, -1, -1, 0, -1, 0);
				if (wall == FluidSolverConfig.WallCondition.NO_SLIP) addWall(cell, axis, lo, hi);
			}
		}
		for (int a = 0; a < 3; a++) for (int b = a + 1; b < 3; b++) {
			for (int z = (a == 2 || b == 2) ? 1 : 0; z < nz; z++)
				for (int y = (a == 1 || b == 1) ? 1 : 0; y < ny; y++)
					for (int x = (a == 0 || b == 0) ? 1 : 0; x < nx; x++) addShear(a, b, x, y, z);
		}
	}

	private void addShear(int a, int b, int x, int y, int z) {
		int ax = a == 0 ? 1 : 0, ay = a == 1 ? 1 : 0, az = a == 2 ? 1 : 0;
		int bx = b == 0 ? 1 : 0, by = b == 1 ? 1 : 0, bz = b == 2 ? 1 : 0;
		int c00 = cell(x - ax - bx, y - ay - by, z - az - bz);
		int c01 = cell(x - ax, y - ay, z - az), c10 = cell(x - bx, y - by, z - bz), c11 = cell(x, y, z);
		// A shear patch touching gas has natural zero traction. Gas node pressure/temperature
		// does not change this ownership. Solid tangential traction is assembled explicitly below.
		if (!wet(c00) || !wet(c01) || !wet(c10) || !wet(c11)) return;
		int ua = face(a, x, y, z), la = face(a, x - bx, y - by, z - bz);
		int ub = face(b, x, y, z), lb = face(b, x - ax, y - ay, z - az);
		double area = Math.min(Math.min(apertures[ua], apertures[la]), Math.min(apertures[ub], apertures[lb]));
		if (area == 0) return;
		double mu = harmonic(harmonic(viscosities[c00], viscosities[c01]), harmonic(viscosities[c10], viscosities[c11]));
		operator.add(mu * h * area, ua, 1, la, -1, ub, 1, lb, -1);
	}

	private void addWall(int cell, int tangent, int lo, int hi) {
		double area = 0;
		for (int axis = 0; axis < 3; axis++) if (axis != tangent) area += geometry.wallArea(cell, axis);
		if (!(area >= 0) || !Double.isFinite(area)) throw new IllegalStateException("Invalid viscous solid wall area");
		// Lump the cell-owned wall area equally onto its two tangential faces. The
		// volume-scaled half-width resolves a full cell as h/2 and shrinks with cuts.
		double conductance = viscosities[cell] * h * area / volumes[cell];
		operator.add(conductance, lo, 1, -1, 0, -1, 0, -1, 0);
		operator.add(conductance, hi, 1, -1, 0, -1, 0, -1, 0);
	}

	FluidDomain.ViscousStepStats copyVelocityTo(float[][] target, FluidDomain.ViscousStepStats stats) {
		double[] rounding = new double[3];
		for (int axis = 0; axis < 3; axis++) for (int i = 0; i < target[axis].length; i++) {
			int id = offsets[axis] + i;
			if (operator.mass[id] == 0) continue;
			float value = (float) operator.velocity[id];
			if (!Float.isFinite(value)) throw new IllegalStateException("Viscous MAC velocity exceeds float range");
			rounding[axis] += operator.mass[id] * ((double) value - operator.velocity[id]);
			target[axis][i] = value;
		}
		return new FluidDomain.ViscousStepStats(stats.stressRows(), stats.spectralBoundPerSecond(),
				stats.boundaryImpulseX(), stats.boundaryImpulseY(), stats.boundaryImpulseZ(), stats.dissipatedJoules(),
				rounding[0], rounding[1], rounding[2]);
	}

	int face(int axis, int x, int y, int z) {
		return offsets[axis] + switch (axis) {
			case 0 -> (z * ny + y) * (nx + 1) + x;
			case 1 -> (z * (ny + 1) + y) * nx + x;
			case 2 -> (z * ny + y) * nx + x;
			default -> throw new IllegalArgumentException("Invalid axis");
		};
	}
	private int cell(int x, int y, int z) {
		return x < 0 || y < 0 || z < 0 || x >= nx || y >= ny || z >= nz ? -1 : (z * ny + y) * nx + x;
	}
	private boolean wet(int cell) { return cell >= 0 && liquid[cell]; }
	private double cellMass(int cell) { return wet(cell) ? densities[cell] * volumes[cell] : 0; }
	static double harmonic(double a, double b) { double low = Math.min(a, b), high = Math.max(a, b); return low / (0.5 + 0.5 * low / high); }
}
