package mmm.fluid;

/** Captured normal traces of the pressure velocity field, with separate positive and negative donor sweeps. */
final class LiquidAffineFaceFlow implements ConservativeLiquidFlux.FaceSweep {
	private final FluidDomain domain;
	private final ConservativeLiquidFlux faces;
	private final double[][] trace;

	LiquidAffineFaceFlow(FluidDomain domain, ConservativeLiquidFlux faces, CellAffineVelocityField field) {
		this.domain = domain; this.faces = faces;
		trace = new double[2 * faces.physicalFaceCount][];
		for (int f = 0; f < faces.physicalFaceCount; f++) for (int direction = 0; direction < 2; direction++) {
			int donor = direction == 0 ? faces.left[f] : faces.right[f];
			if (!field.hasCell(donor)) continue;
			var coefficients = new double[3];
			field.faceTrace(donor, faces.axis[f], direction == 0, domain.sizeX, domain.sizeY, coefficients);
			for (double value : coefficients) if (!Double.isFinite(value))
				throw new FluidDomain.PressureSolveBlockedException("Nonfinite liquid normal trace");
			trace[f + direction * faces.physicalFaceCount] = coefficients;
		}
	}

	double maximumOutwardSpeed(int face, boolean positive, double verticalPrediction) {
		double[] q = trace[face + (positive ? 0 : faces.physicalFaceCount)];
		if (q == null) return 0;
		double a = q[0] + (faces.axis[face] == 1 ? verticalPrediction : 0);
		double b = positive ? Math.max(0, q[1]) : Math.min(0, q[1]);
		double c = positive ? Math.max(0, q[2]) : Math.min(0, q[2]);
		if (a == 0 && b == 0 && c == 0) return 0;
		double bound = positive ? Math.nextUp(Math.nextUp(a + b) + c) : Math.nextDown(Math.nextDown(a + b) + c);
		if (!Double.isFinite(bound)) throw new FluidDomain.PressureSolveBlockedException("Unrepresentable affine face Courant bound");
		return Math.max(0, positive ? bound : -bound);
	}

	void prepareVelocities(ConservativeLiquidFlux transport, LiquidFluxGeometry geometry) {
		if (transport.flux.length != 2 * faces.physicalFaceCount)
			throw new IllegalArgumentException("Affine transport needs two directional channels per face");
		for (int face = 0; face < faces.physicalFaceCount; face++) {
			double aperture = geometry.aperture(faces.left[face], faces.right[face], faces.axis[face]);
			for (int direction = 0; direction < 2; direction++) {
				int slot = face + direction * faces.physicalFaceCount;
				double[] q = trace[slot]; double speed = 0;
				if (q != null && aperture > 0) {
					int donor = direction == 0 ? faces.left[face] : faces.right[face];
					int receiver = direction == 0 ? faces.right[face] : faces.left[face];
					speed = LiquidAffineSweepGeometry.faceOutflowRate(domain, donor, receiver, faces.axis[face], direction == 0,
							q[0], q[1], q[2], 255, 255) / aperture;
				}
				if (!(Double.isFinite(speed) && speed >= 0))
					throw new FluidDomain.PressureSolveBlockedException("Unrepresentable directional liquid face rate");
				transport.velocity[slot] = Math.copySign(speed, direction == 0 ? 1 : -1);
			}
		}
	}

	@Override public void sweptMoments(int face, int donor, int receiver, int axis, boolean positive, double dtOverH,
	                                  int donorSupport, int receiverSupport, double[] result) {
		double[] q = trace[face + (positive ? 0 : faces.physicalFaceCount)];
		if (q == null) { java.util.Arrays.fill(result, 0, 4, 0); return; }
		LiquidAffineSweepGeometry.sweptMoments(domain, donor, receiver, axis, positive, q[0], q[1], q[2], dtOverH,
				donorSupport, receiverSupport, result);
	}
	@Override public void sweptMicroVolumes(int face, int donor, int receiver, int axis, boolean positive, double dtOverH,
	                                       int donorSupport, int receiverSupport, double[] swept, double[] wet) {
		double[] q = trace[face + (positive ? 0 : faces.physicalFaceCount)];
		if (q == null) { java.util.Arrays.fill(swept, 0); java.util.Arrays.fill(wet, 0); return; }
		LiquidAffineSweepGeometry.sweptMicroVolumes(domain, donor, receiver, axis, positive, q[0], q[1], q[2], dtOverH,
				donorSupport, receiverSupport, swept, wet);
	}
	@Override public mmm.chemistry.ExactRational[] sweptSecondMoments(int face, int donor, int receiver, int axis, boolean positive,
	                                                              double dtOverH, int donorSupport, int receiverSupport) {
		double[] q = trace[face + (positive ? 0 : faces.physicalFaceCount)];
		if (q == null) throw new IllegalStateException("Positive liquid arrival has no captured affine trace");
		return LiquidAffineSweepGeometry.sweptExactSecondMoments(domain, donor, receiver, axis, positive, q[0], q[1], q[2], dtOverH,
				donorSupport, receiverSupport);
	}

	@Override public void sweptPhaseMoments(PhaseVolumeTransportGeometry phases, int phase, int face, int donor,
	                                       int receiver, int axis, boolean positive, double width, double dtOverH,
	                                       int donorSupport, int receiverSupport, double[] result) {
		double[] q = trace[face + (positive ? 0 : faces.physicalFaceCount)];
		if (q == null) { java.util.Arrays.fill(result, 0, 4, 0); return; }
		phases.sweptAffineMoments(phase, donor, receiver, axis, positive, q[0], q[1], q[2], dtOverH,
				donorSupport, receiverSupport, result);
	}
}
