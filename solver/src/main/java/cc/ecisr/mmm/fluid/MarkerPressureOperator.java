package cc.ecisr.mmm.fluid;

import java.util.Arrays;

/** SI volume-rate constraint C = face incidence * area * J, and C M^-1 C^T + gas storage. */
final class MarkerPressureOperator {
	private final int rowCount, markerCount;
	private final double[] inverseMass, storage;
	private final int[][] rows;
	private final double[][] coefficients;
	private final byte[][] counts;
	private final int[][] first, second;
	private final double[][] faceCoefficients;
	private final byte[][] faceCounts;
	private final int unsupportedFaces;

	/**
	 * Rows use outward flux: the first cell contributes +area and the second -area.
	 * A row of -1 denotes a prescribed pressure. Its force belongs in the predictor.
	 * Gas storage is V*beta/dt^2 in SI; liquid rows have zero storage.
	 * This snapshot owns its coefficients, independently of subsequent transfer captures.
	 */
	MarkerPressureOperator(MarkerVelocityTransfer transfer, int rowCount, int[][] firstRows,
	                       int[][] secondRows, double[][] faceAreas, double[] storage) {
		if (rowCount < 0 || storage.length != rowCount || firstRows.length != 3
				|| secondRows.length != 3 || faceAreas.length != 3) throw new IllegalArgumentException("Pressure layout mismatch");
		this.rowCount = rowCount; markerCount = transfer.markerCount(); this.storage = storage.clone();
		for (double value : this.storage) if (!(value >= 0 && Double.isFinite(value))) throw new IllegalArgumentException("Invalid SI pressure storage");
		inverseMass = new double[markerCount];
		for (int marker = 0; marker < markerCount; marker++) {
			inverseMass[marker] = 1 / transfer.massKilograms(marker);
			if (!(inverseMass[marker] > 0 && Double.isFinite(inverseMass[marker]))) throw new IllegalArgumentException("Unrepresentable inverse marker mass");
		}
		int entries = Math.multiplyExact(markerCount, 16);
		rows = new int[3][entries]; coefficients = new double[3][entries]; counts = new byte[3][markerCount];
		int faceEntries = Math.multiplyExact(markerCount, 8);
		first = new int[3][faceEntries]; second = new int[3][faceEntries];
		faceCoefficients = new double[3][faceEntries]; faceCounts = new byte[3][markerCount];
		int unsupported = 0;
		for (int axis = 0; axis < 3; axis++) {
			int faces = transfer.faceCount(axis);
			if (firstRows[axis].length != faces || secondRows[axis].length != faces || faceAreas[axis].length != faces) throw new IllegalArgumentException("Pressure face layout mismatch");
			for (int face = 0; face < faces; face++) {
				int first = firstRows[axis][face], second = secondRows[axis][face];
				double area = faceAreas[axis][face];
				if (first < -1 || first >= rowCount || second < -1 || second >= rowCount
						|| !(area >= 0 && Double.isFinite(area))) throw new IllegalArgumentException("Invalid pressure face incidence");
				if (area > 0 && first != second && !transfer.supportsFace(axis, face)) unsupported++;
			}
			for (int marker = 0; marker < markerCount; marker++) {
				for (int slot = 0; slot < transfer.coefficientCount(axis, marker); slot++) {
					int face = transfer.faceIndex(axis, marker, slot);
					double value = faceAreas[axis][face] * transfer.coefficient(axis, marker, slot);
					if (faceAreas[axis][face] == 0 || firstRows[axis][face] == secondRows[axis][face]) continue;
					if (value == 0) throw new IllegalArgumentException("Unrepresentable pressure coefficient");
					int entry = marker * 8 + faceCounts[axis][marker]++;
					first[axis][entry] = firstRows[axis][face]; second[axis][entry] = secondRows[axis][face];
					faceCoefficients[axis][entry] = value;
					add(axis, marker, firstRows[axis][face], value);
					add(axis, marker, secondRows[axis][face], -value);
				}
				// Merge before squaring: adjacent face contributions to the same row can cancel.
				int start = marker * 16, retained = 0;
				for (int slot = 0; slot < counts[axis][marker]; slot++) if (coefficients[axis][start + slot] != 0) {
					rows[axis][start + retained] = rows[axis][start + slot];
					coefficients[axis][start + retained++] = coefficients[axis][start + slot];
				}
				counts[axis][marker] = (byte) retained;
			}
		}
		unsupportedFaces = unsupported;
	}

	private void add(int axis, int marker, int row, double value) {
		if (row < 0 || value == 0) return;
		int start = marker * 16;
		for (int slot = 0; slot < counts[axis][marker]; slot++) if (rows[axis][start + slot] == row) {
			coefficients[axis][start + slot] += value;
			if (!Double.isFinite(coefficients[axis][start + slot])) throw new IllegalArgumentException("Pressure coefficient overflow");
			return;
		}
		int entry = start + counts[axis][marker]++;
		rows[axis][entry] = row; coefficients[axis][entry] = value;
	}

	int rowCount() { return rowCount; }
	int unsupportedFaceCount() { return unsupportedFaces; }

	/** C v in cubic metres per second. */
	void volumeRate(double[][] velocity, double[] result) {
		checkMarkers(velocity); checkRows(result);
		for (double[] axis : velocity) if (axis == result) throw new IllegalArgumentException("Aliased pressure vectors");
		Arrays.fill(result, 0);
		for (int axis = 0; axis < 3; axis++) for (int marker = 0; marker < markerCount; marker++) {
			for (int slot = 0; slot < counts[axis][marker]; slot++) {
				int entry = marker * 16 + slot;
				result[rows[axis][entry]] += coefficients[axis][entry] * velocity[axis][marker];
			}
		}
	}

	/** C^T p in newtons. Physical pressure accelerates markers by +dt M^-1 C^T p. */
	void force(double[] pressure, double[][] result) {
		checkRows(pressure); checkMarkers(result);
		for (double[] axis : result) if (axis == pressure) throw new IllegalArgumentException("Aliased pressure vectors");
		for (int axis = 0; axis < 3; axis++) {
			Arrays.fill(result[axis], 0);
			for (int marker = 0; marker < markerCount; marker++) {
				result[axis][marker] = pressureForce(axis, marker, pressure);
			}
		}
	}

	/** Matrix-free pressure Gram matrix plus finite gas storage. */
	void apply(double[] pressure, double[] result) {
		checkRows(pressure); checkRows(result);
		if (pressure == result) throw new IllegalArgumentException("Aliased pressure vectors");
		for (int row = 0; row < rowCount; row++) result[row] = storage[row] * pressure[row];
		for (int axis = 0; axis < 3; axis++) for (int marker = 0; marker < markerCount; marker++) {
			int start = marker * 16;
			double acceleration = pressureForce(axis, marker, pressure) * inverseMass[marker];
			for (int slot = 0; slot < counts[axis][marker]; slot++) result[rows[axis][start + slot]] += coefficients[axis][start + slot] * acceleration;
		}
	}

	/** Difference first: constant closed pressure stays exactly force-free even at large gauges. */
	private double pressureForce(int axis, int marker, double[] pressure) {
		double result = 0;
		for (int slot = 0; slot < faceCounts[axis][marker]; slot++) {
			int entry = marker * 8 + slot;
			double pFirst = first[axis][entry] < 0 ? 0 : pressure[first[axis][entry]];
			double pSecond = second[axis][entry] < 0 ? 0 : pressure[second[axis][entry]];
			double difference = pFirst - pSecond, coefficient = faceCoefficients[axis][entry];
			// Opposite finite pressures can overflow their difference while the area-scaled force fits.
			result += Double.isInfinite(difference) && Double.isFinite(pFirst) && Double.isFinite(pSecond)
					? coefficient * pFirst - coefficient * pSecond : coefficient * difference;
		}
		return result;
	}

	void diagonal(double[] result) {
		checkRows(result); System.arraycopy(storage, 0, result, 0, rowCount);
		for (int axis = 0; axis < 3; axis++) for (int marker = 0; marker < markerCount; marker++) for (int slot = 0; slot < counts[axis][marker]; slot++) {
			int entry = marker * 16 + slot; double value = coefficients[axis][entry];
			result[rows[axis][entry]] += (value * inverseMass[marker]) * value;
		}
	}

	private void checkRows(double[] vector) {
		if (vector.length != rowCount) throw new IllegalArgumentException("Pressure row shape mismatch");
	}
	private void checkMarkers(double[][] vectors) {
		if (vectors.length != 3) throw new IllegalArgumentException("Expected three marker axes");
		for (double[] vector : vectors) if (vector.length != markerCount) throw new IllegalArgumentException("Marker shape mismatch");
		if (vectors[0] == vectors[1] || vectors[0] == vectors[2] || vectors[1] == vectors[2]) throw new IllegalArgumentException("Aliased marker axes");
	}
}
