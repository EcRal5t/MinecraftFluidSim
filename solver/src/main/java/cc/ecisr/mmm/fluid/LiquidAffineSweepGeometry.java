package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.chemistry.ExactRational;
import cc.ecisr.mmm.units.SignedDyadic;
import java.util.List;

/** PLIC donor wedges swept by a frozen affine normal trace on a shared open face. */
final class LiquidAffineSweepGeometry {
	private LiquidAffineSweepGeometry() { }

	/** Positive directional flow rate through the supported open face, before wet PLIC clipping. */
	static double faceOutflowRate(FluidDomain domain, int donor, int receiver, int axis, boolean positive,
	                              double intercept, double tangentSlope, double otherSlope,
	                              int donorSupport, int receiverSupport) {
		requireInput(domain, donor, receiver, axis, intercept, tangentSlope, otherSlope, 0, donorSupport, receiverSupport);
		var phase = domain.pressurePhaseGeometry;
		var cut = domain.geometry.cutCells;
		int sign = positive ? 1 : -1;
		SignedDyadic[] normal = {SignedDyadic.ZERO, SignedDyadic.ZERO, SignedDyadic.ZERO};
		normal[(axis + 1) % 3] = SignedDyadic.of(tangentSlope).multiply(SignedDyadic.of(sign));
		normal[(axis + 2) % 3] = SignedDyadic.of(otherSlope).multiply(SignedDyadic.of(sign));
		var velocity = new GasGeometryOverlap.HalfSpace(normal[0], normal[1], normal[2], SignedDyadic.of(-sign * intercept));
		double donorPore = poreScale(domain, donor), receiverPore = poreScale(domain, receiver);
		boolean componentPores = phase.hasComponentPlanes(donor) || phase.hasComponentPlanes(receiver);
		ExactRational a = rational(intercept), b = rational(tangentSlope), c = rational(otherSlope);
		ExactRational centered = a.add(b.add(c).divide(2)), rate = ExactRational.ZERO;
		try {
			for (int micro = 0; micro < 8; micro++) {
				if ((micro >>> axis & 1) != (positive ? 1 : 0)) continue;
				int peerMicro = micro ^ 1 << axis;
				if ((donorSupport & 1 << micro) == 0 || (receiverSupport & 1 << peerMicro) == 0
						|| (cut.mask(donor) & 1 << micro) != 0 || (cut.mask(receiver) & 1 << peerMicro) != 0) continue;
				double pore = componentPores ? Math.min(phase.componentPoreScale(donor, micro, donorPore),
						phase.componentPoreScale(receiver, peerMicro, receiverPore)) : Math.min(donorPore, receiverPore);
				if (!Double.isFinite(pore) || pore < 0 || pore > 1) throw new IllegalStateException("Invalid affine face pore fraction");
				if (pore == 0) continue;
				ExactRational[] part = GasGeometryOverlap.normalizedMoments(micro, List.of(velocity));
				// The selected box has normal thickness 1/2; its tangential velocity is constant along that axis.
				ExactRational integral = centered.multiply(part[0]).add(b.multiply(part[(axis + 1) % 3 + 1]))
						.add(c.multiply(part[(axis + 2) % 3 + 1])).multiply(2L * sign);
				if (integral.signum() < 0) throw new IllegalStateException("Affine positive face rate has negative measure");
				rate = rate.add(integral.multiply(rational(pore)));
			}
			double observed = rate.toDouble();
			if (!Double.isFinite(observed) || rate.signum() > 0 && observed == 0)
				throw new IllegalStateException("Affine face rate cannot be represented in binary64");
			return observed;
		} catch (IllegalArgumentException magnitude) {
			throw new IllegalStateException("Exact affine liquid face rate exceeds the rational representation budget", magnitude);
		}
	}

	/**
	 * The normal trace is intercept + tangentSlope*x[(axis+1)%3] + otherSlope*x[(axis+2)%3],
	 * in donor-local [0,1] coordinates. Results are normalized V and donor-centred M1.
	 */
	static void sweptMoments(FluidDomain domain, int donor, int receiver, int axis, boolean positive,
	                         double intercept, double tangentSlope, double otherSlope, double dtOverH,
	                         int donorSupport, int receiverSupport, double[] result) {
		if (result == null || result.length < 4) throw new IllegalArgumentException("Missing affine sweep moments");
		ExactRational[] exact = sweptExactMoments(domain, donor, receiver, axis, positive, intercept,
				tangentSlope, otherSlope, dtOverH, donorSupport, receiverSupport);
		observe(exact, result);
	}

	/** Sweeps a caller-supplied phase PLIC nx*x+ny*y+nz*z <= offset over the same open support. */
	static void sweptPlaneMoments(FluidDomain domain, int donor, int receiver, int axis, boolean positive,
	                              double intercept, double tangentSlope, double otherSlope, double dtOverH,
	                              int donorSupport, int receiverSupport, double nx, double ny, double nz,
	                              double offset, double[] result) {
		if (result == null || result.length < 4 || !Double.isFinite(nx) || !Double.isFinite(ny)
				|| !Double.isFinite(nz) || !Double.isFinite(offset))
			throw new IllegalArgumentException("Invalid phase PLIC sweep input");
		var wet = new GasGeometryOverlap.HalfSpace(SignedDyadic.of(nx).negate(), SignedDyadic.of(ny).negate(),
				SignedDyadic.of(nz).negate(), SignedDyadic.of(offset).negate());
		observe(sweptExactMoments(domain, donor, receiver, axis, positive, intercept, tangentSlope, otherSlope,
				dtOverH, donorSupport, receiverSupport, wet), result);
	}

	private static void observe(ExactRational[] exact, double[] result) {
		double[] observed = new double[4];
		for (int i = 0; i < 4; i++) {
			observed[i] = exact[i].toDouble();
			if (!Double.isFinite(observed[i]) || !exact[i].isZero() && observed[i] == 0)
				throw new IllegalStateException("Affine sweep moment cannot be represented in binary64");
		}
		System.arraycopy(observed, 0, result, 0, 4);
	}

	static ExactRational[] sweptExactMoments(FluidDomain domain, int donor, int receiver, int axis, boolean positive,
	                                      double intercept, double tangentSlope, double otherSlope, double dtOverH,
	                                      int donorSupport, int receiverSupport) {
		return sweptExactMoments(domain, donor, receiver, axis, positive, intercept, tangentSlope, otherSlope,
				dtOverH, donorSupport, receiverSupport, null);
	}

	private static ExactRational[] sweptExactMoments(FluidDomain domain, int donor, int receiver, int axis, boolean positive,
	                                               double intercept, double tangentSlope, double otherSlope, double dtOverH,
	                                               int donorSupport, int receiverSupport, GasGeometryOverlap.HalfSpace wetOverride) {
		requireInput(domain, donor, receiver, axis, intercept, tangentSlope, otherSlope, dtOverH, donorSupport, receiverSupport);
		ExactRational[] total = {ExactRational.ZERO, ExactRational.ZERO, ExactRational.ZERO, ExactRational.ZERO};
		if (dtOverH == 0 || donorSupport == 0 || receiverSupport == 0) return total;
		var cut = domain.geometry.cutCells;
		var phase = domain.pressurePhaseGeometry;
		var wedge = wedge(axis, positive, intercept, tangentSlope, otherSlope, dtOverH);
		double donorPore = poreScale(domain, donor), receiverPore = poreScale(domain, receiver);
		boolean componentPores = phase.hasComponentPlanes(donor) || phase.hasComponentPlanes(receiver);
		try {
			for (int micro = 0; micro < 8; micro++) {
				int faceMicro = (micro & ~(1 << axis)) | (positive ? 1 << axis : 0);
				int peerMicro = (micro & ~(1 << axis)) | (positive ? 0 : 1 << axis);
				if ((donorSupport & 1 << micro) == 0 || (donorSupport & 1 << faceMicro) == 0
						|| (receiverSupport & 1 << peerMicro) == 0 || (cut.mask(donor) & (1 << micro | 1 << faceMicro)) != 0
						|| (cut.mask(receiver) & 1 << peerMicro) != 0) continue;
				double wet = wetOverride == null ? phase.liquidFractionExact(donor, micro) : 1;
				if (wet == 0) continue;
				List<GasGeometryOverlap.HalfSpace> constraints = wetOverride != null ? List.of(wedge, wetOverride) : wet >= 1 ? List.of(wedge)
						: List.of(wedge, new GasGeometryOverlap.HalfSpace(
								SignedDyadic.of(phase.planeNormal(donor, micro, 0)).negate(),
								SignedDyadic.of(phase.planeNormal(donor, micro, 1)).negate(),
								SignedDyadic.of(phase.planeNormal(donor, micro, 2)).negate(),
								SignedDyadic.of(phase.planeOffset(donor, micro)).negate()));
				double pore = componentPores ? Math.min(phase.componentPoreScale(donor, micro, donorPore),
						phase.componentPoreScale(receiver, peerMicro, receiverPore)) : Math.min(donorPore, receiverPore);
				if (!Double.isFinite(pore) || pore < 0 || pore > 1) throw new IllegalStateException("Invalid affine sweep pore fraction");
				if (pore == 0) continue;
				ExactRational weight = rational(pore);
				ExactRational[] part = GasGeometryOverlap.normalizedMoments(micro, constraints);
				for (int i = 0; i < 4; i++) total[i] = total[i].add(part[i].multiply(weight));
			}
			if (total[0].signum() < 0 || total[0].compareTo(ExactRational.ONE) > 0)
				throw new IllegalStateException("Affine liquid sweep volume does not close");
			for (int i = 1; i < 4; i++) if (total[i].multiply(2).compareTo(total[0]) > 0
					|| total[i].multiply(-2).compareTo(total[0]) > 0)
				throw new IllegalStateException("Affine liquid sweep first moment lies outside its donor");
			return total;
		} catch (IllegalArgumentException magnitude) {
			throw new IllegalStateException("Exact affine liquid sweep exceeds the rational representation budget", magnitude);
		}
	}

	/** Micro sweep and wet measures use the same exact PLIC intersections and frozen trace. */
	static void sweptMicroVolumes(FluidDomain domain, int donor, int receiver, int axis, boolean positive,
	                             double intercept, double tangentSlope, double otherSlope, double dtOverH,
	                             int donorSupport, int receiverSupport, double[] swept, double[] wet) {
		requireInput(domain, donor, receiver, axis, intercept, tangentSlope, otherSlope, dtOverH, donorSupport, receiverSupport);
		java.util.Arrays.fill(swept, 0); java.util.Arrays.fill(wet, 0);
		var phase = domain.pressurePhaseGeometry; var cut = domain.geometry.cutCells;
		var wedge = wedge(axis, positive, intercept, tangentSlope, otherSlope, dtOverH);
		double donorPore = poreScale(domain, donor), receiverPore = poreScale(domain, receiver);
		for (int micro = 0; micro < 8; micro++) {
			int faceMicro = (micro & ~(1 << axis)) | (positive ? 1 << axis : 0);
			int peerMicro = (micro & ~(1 << axis)) | (positive ? 0 : 1 << axis);
			if ((donorSupport & 1 << micro) == 0 || (donorSupport & 1 << faceMicro) == 0
					|| (receiverSupport & 1 << peerMicro) == 0 || (cut.mask(donor) & (1 << micro | 1 << faceMicro)) != 0
					|| (cut.mask(receiver) & 1 << peerMicro) != 0) continue;
			double fraction = phase.liquidFractionExact(donor, micro);
			if (fraction == 0) continue;
			var liquid = new GasGeometryOverlap.HalfSpace(SignedDyadic.of(phase.planeNormal(donor, micro, 0)).negate(),
					SignedDyadic.of(phase.planeNormal(donor, micro, 1)).negate(), SignedDyadic.of(phase.planeNormal(donor, micro, 2)).negate(),
					SignedDyadic.of(phase.planeOffset(donor, micro)).negate());
			List<GasGeometryOverlap.HalfSpace> wetPlanes = fraction >= 1 ? List.of() : List.of(liquid);
			List<GasGeometryOverlap.HalfSpace> sweptPlanes = fraction >= 1 ? List.of(wedge) : List.of(wedge, liquid);
			double localPore = phase.componentPoreScale(donor, micro, donorPore);
			double peerPore = phase.componentPoreScale(receiver, peerMicro, receiverPore);
			if (!(Double.isFinite(localPore) && localPore >= 0 && localPore <= 1
					&& Double.isFinite(peerPore) && peerPore >= 0 && peerPore <= 1))
				throw new IllegalStateException("Invalid affine micro sweep pore fraction");
			wet[micro] = observeVolume(GasGeometryOverlap.normalizedMoments(micro, wetPlanes)[0].multiply(rational(localPore)));
			swept[micro] = dtOverH == 0 ? 0 : observeVolume(GasGeometryOverlap.normalizedMoments(micro, sweptPlanes)[0]
					.multiply(rational(Math.min(localPore, peerPore))));
		}
	}

	private static double observeVolume(ExactRational volume) {
		double observed = volume.toDouble();
		if (!(Double.isFinite(observed) && observed >= 0) || observed == 0 && !volume.isZero())
			throw new IllegalStateException("Affine liquid micro volume is unrepresentable in binary64");
		return observed;
	}

	/** Positive flow retains x_a >= 1-dt*u/h; negative flow retains x_a <= -dt*u/h. */
	static GasGeometryOverlap.HalfSpace wedge(int axis, boolean positive, double intercept,
	                                        double tangentSlope, double otherSlope, double dtOverH) {
		SignedDyadic dt = SignedDyadic.of(dtOverH);
		SignedDyadic[] normal = {SignedDyadic.ZERO, SignedDyadic.ZERO, SignedDyadic.ZERO};
		int sign = positive ? 1 : -1;
		normal[axis] = SignedDyadic.of(sign);
		normal[(axis + 1) % 3] = dt.multiply(SignedDyadic.of(tangentSlope)).multiply(SignedDyadic.of(sign));
		normal[(axis + 2) % 3] = dt.multiply(SignedDyadic.of(otherSlope)).multiply(SignedDyadic.of(sign));
		SignedDyadic offset = dt.multiply(SignedDyadic.of(intercept));
		if (positive) offset = SignedDyadic.of(1).subtract(offset);
		return new GasGeometryOverlap.HalfSpace(normal[0], normal[1], normal[2], offset);
	}

	private static double poreScale(FluidDomain domain, int cell) {
		double open = domain.geometry.cutCells.openCellVolume(cell);
		return open > 0 ? domain.effectiveOpenCellVolume(cell) / open : 0;
	}
	private static ExactRational rational(double value) {
		return PlicComponentGeometry.ratio(SignedDyadic.of(value), SignedDyadic.of(1));
	}
	private static void requireInput(FluidDomain domain, int donor, int receiver, int axis,
	                                 double intercept, double tangentSlope, double otherSlope, double dtOverH,
	                                 int donorSupport, int receiverSupport) {
		if (domain == null || donor < 0 || receiver < 0 || donor >= domain.cellCount || receiver >= domain.cellCount
				|| axis < 0 || axis > 2 || donorSupport < 0 || donorSupport > 255 || receiverSupport < 0 || receiverSupport > 255
				|| !Double.isFinite(intercept) || !Double.isFinite(tangentSlope) || !Double.isFinite(otherSlope)
				|| !Double.isFinite(dtOverH) || dtOverH < 0)
			throw new IllegalArgumentException("Invalid affine liquid sweep input");
	}
}
