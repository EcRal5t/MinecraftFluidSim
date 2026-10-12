package mmm.fluid;

/** Continuous marker motion with normal-only contact against the cut-cell micro boxes. */
final class FlipParticleCollision {
	private final int[] sizes;
	private final float microSize;
	private final FlipCutCellGeometry cutCells;

	FlipParticleCollision(int sizeX, int sizeY, int sizeZ, float cellSize, FlipCutCellGeometry cutCells) {
		this.sizes = new int[] {sizeX * 2, sizeY * 2, sizeZ * 2};
		this.microSize = cellSize / FlipCutCellGeometry.MICRO_CELLS_PER_AXIS;
		this.cutCells = cutCells;
	}

	void advect(float[] particleX, float[] particleY, float[] particleZ,
			float[] motionX, float[] motionY, float[] motionZ,
			float[] velocityX, float[] velocityY, float[] velocityZ, int particleCount, float dt) {
		float[][] positions = {particleX, particleY, particleZ}, motion = {motionX, motionY, motionZ};
		float[][] velocity = {velocityX, velocityY, velocityZ};
		double[] point = new double[3], displacement = new double[3], crossing = new double[3];
		int[] cell = new int[3];
		for (int particle = 0; particle < particleCount; particle++) {
			for (int a = 0; a < 3; a++) {
				point[a] = positions[a][particle];
				displacement[a] = (double) (float) (point[a] + (double) motion[a][particle] * dt) - point[a];
			}
			// Every contact removes at least one moving axis. The remaining motion follows
			// the wall tangent for the rest of the step, so three contacts suffice in 3D.
			for (int contact = 0; contact < 3; contact++) {
				int normal = firstContact(point, displacement, cell, crossing);
				if (normal == 0) {
					for (int a = 0; a < 3; a++) point[a] += displacement[a];
					break;
				}
				double time = crossing[0];
				for (int a = 0; a < 3; a++) {
					double endpoint = point[a] + displacement[a];
					point[a] += time * displacement[a];
					if ((normal & 1 << a) != 0) {
						boolean positive = displacement[a] > 0;
						// Choose the closest binary32 point owned by the last open micro box.
						float stored = (float) ((cell[a] + (positive ? 1.0 : 0.0)) * microSize);
						while (microCoordinate(stored) != cell[a])
							stored = Math.nextAfter(stored, positive ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY);
						point[a] = stored;
						displacement[a] = 0;
						velocity[a][particle] = positive ? Math.min(0, velocity[a][particle]) : Math.max(0, velocity[a][particle]);
					} else {
						point[a] = (float) point[a];
						displacement[a] = (double) (float) endpoint - point[a];
					}
				}
			}
			for (int a = 0; a < 3; a++) positions[a][particle] = (float) point[a];
			if (centerInSolid(particleX[particle], particleY[particle], particleZ[particle]))
				throw new IllegalStateException("Collision placed a marker inside solid micro geometry");
		}
	}

	boolean centerInSolid(float x, float y, float z) {
		return cutCells.pointInsideSolid(x, y, z, microSize * 2);
	}

	/** Visits each micro box intersected by the actual straight segment in time order. */
	private int firstContact(double[] point, double[] displacement, int[] cell, double[] crossing) {
		for (int a = 0; a < 3; a++) cell[a] = microCoordinate((float) point[a]);
		while (true) {
			double time = Double.POSITIVE_INFINITY;
			for (int a = 0; a < 3; a++) {
				crossing[a] = displacement[a] == 0 ? Double.POSITIVE_INFINITY
						: Math.max(0, ((cell[a] + (displacement[a] > 0 ? 1.0 : 0.0)) * microSize - point[a]) / displacement[a]);
				time = Math.min(time, crossing[a]);
			}
			if (time > 1) return 0;
			int normal = 0;
			for (int a = 0; a < 3; a++) if (crossing[a] == time) {
				normal |= 1 << a; cell[a] += displacement[a] > 0 ? 1 : -1;
			}
			if (solid(cell)) {
				int crossed = normal;
				normal = contactNormals(crossed, cell, displacement);
				for (int a = 0; a < 3; a++) if ((normal & 1 << a) != 0) cell[a] -= displacement[a] > 0 ? 1 : -1;
				crossing[0] = time;
				return normal;
			}
		}
	}

	/** Selects the least change of motion at a union of boxes, independent of axis order. */
	private int contactNormals(int crossed, int[] cell, double[] displacement) {
		double minimum = Double.POSITIVE_INFINITY;
		int normal = 0;
		for (int subset = 1; subset < 8; subset++) if ((subset & crossed) == subset) {
			shift(cell, displacement, subset, -1);
			if (!solid(cell)) {
				double removed = 0;
				for (int a = 0; a < 3; a++) if ((subset & 1 << a) != 0) removed += displacement[a] * displacement[a];
				if (removed < minimum) { minimum = removed; normal = subset; }
				else if (removed == minimum) normal |= subset;
			}
			shift(cell, displacement, subset, 1);
		}
		// Equal least-change choices constrain their combined normals. If that
		// combination reaches another occupied box, retain the original open box.
		shift(cell, displacement, normal, -1);
		boolean open = !solid(cell);
		shift(cell, displacement, normal, 1);
		return open ? normal : crossed;
	}

	private static void shift(int[] cell, double[] displacement, int axes, int sign) {
		for (int a = 0; a < 3; a++) if ((axes & 1 << a) != 0) cell[a] += sign * (displacement[a] > 0 ? 1 : -1);
	}

	private boolean solid(int[] cell) {
		for (int a = 0; a < 3; a++) if (cell[a] < 0 || cell[a] >= sizes[a]) return true;
		int index = ((cell[2] / 2) * (sizes[1] / 2) + cell[1] / 2) * (sizes[0] / 2) + cell[0] / 2;
		return !cutCells.microCellOpen(index, cell[0] & 1, cell[1] & 1, cell[2] & 1);
	}

	private int microCoordinate(float coordinate) { return (int) Math.floor(coordinate / microSize); }
}
