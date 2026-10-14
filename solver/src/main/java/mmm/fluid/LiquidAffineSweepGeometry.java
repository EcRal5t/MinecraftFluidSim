package mmm.fluid;

import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;
import java.util.List;

/** PLIC donor wedges swept by a frozen affine normal trace on a shared open face. */
final class LiquidAffineSweepGeometry {
	private LiquidAffineSweepGeometry() { }

	/** Positive directional flow rate through the supported open face, before wet PLIC clipping. */
	static double faceOutflowRate(FluidDomain domain, int donor, int receiver, int axis, boolean positive,
	                              double intercept, double tangentSlope, double otherSlope,
	                              int donorSupport, int receiverSupport) {
		ExactRational rate = ExactRational.ZERO;
		for (var micro : faceMicroRates(domain, donor, receiver, axis, positive, intercept, tangentSlope, otherSlope,
				donorSupport, receiverSupport)) rate = rate.add(micro);
		return observeVolume(rate);
	}

	private static ExactRational[] faceMicroRates(FluidDomain domain, int donor, int receiver, int axis, boolean positive,
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
		ExactRational centered = a.add(b.add(c).divide(2));
		ExactRational[] rates = new ExactRational[8]; java.util.Arrays.fill(rates, ExactRational.ZERO);
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
				rates[micro] = integral.multiply(rational(pore));
			}
			return rates;
		} catch (IllegalArgumentException magnitude) {
			throw new IllegalStateException("Exact affine liquid face rate exceeds the rational representation budget", magnitude);
		}
	}

	/** Donor-cell normal columns retain the exact correlation of the positive trace and wet source. */
	static void lowOrderPhaseVolumes(FluidDomain domain, int donor, int receiver, int axis, boolean positive,
	                                 double[] q, double dtOverH, int donorSupport, int receiverSupport,
	                                 ExactRational[] open, ExactRational[] liquid, ExactRational[] dry) {
		lowOrderPhaseMoments(domain, donor, receiver, axis, positive, q, dtOverH, donorSupport, receiverSupport,
				open, liquid, dry, null, donorSupport);
	}
	/** The requested source mask selects moments after the complete component's column normalization. */
	static ExactRational[] lowOrderPhaseSecondMoments(FluidDomain domain, int donor, int receiver, int axis, boolean positive,
	                                                double[] q, double dtOverH, int donorSupport, int receiverSupport,
	                                                int selectedSourceSupport) {
		if (selectedSourceSupport < 0 || selectedSourceSupport > 255)
			throw new IllegalArgumentException("Invalid low-order source moment support");
		var result = new ExactRational[10]; java.util.Arrays.fill(result, ExactRational.ZERO);
		lowOrderPhaseMoments(domain, donor, receiver, axis, positive, q, dtOverH, donorSupport, receiverSupport,
				new ExactRational[8], new ExactRational[8], new ExactRational[8], result, selectedSourceSupport);
		return result;
	}
	/** Donor-centred arrival moments under the same frozen normal trace. */
	static ExactRational[] arrivalSecondMoments(ExactRational[] source, int axis, boolean positive,
	                                          double[] q, double dtOverH, boolean uniformAge) {
		if (source == null || source.length != 10 || axis < 0 || axis > 2 || q == null || q.length != 3
				|| !(Double.isFinite(dtOverH) && dtOverH >= 0))
			throw new IllegalArgumentException("Invalid liquid arrival moments");
		var result = source.clone();
		ExactRational[] affine = {rational(q[0]).add(rational(q[1]).add(rational(q[2])).divide(2)),
				ExactRational.ZERO, ExactRational.ZERO, ExactRational.ZERO};
		affine[(axis + 1) % 3 + 1] = rational(q[1]); affine[(axis + 2) % 3 + 1] = rational(q[2]);
		var velocity = affine[0].multiply(source[0]); var squared = affine[0].multiply(affine[0]).multiply(source[0]);
		for (int a = 0; a < 3; a++) {
			velocity = velocity.add(affine[a + 1].multiply(source[a + 1]));
			squared = squared.add(affine[0].multiply(affine[a + 1]).multiply(source[a + 1]).multiply(2));
			for (int b = 0; b < 3; b++) squared = squared.add(affine[a + 1].multiply(affine[b + 1]).multiply(source[secondIndex(a, b)]));
		}
		var time = rational(dtOverH);
		var face = ExactRational.of(positive ? 1 : -1).divide(2);
		result[axis + 1] = uniformAge ? face.multiply(source[0]).add(time.multiply(velocity).divide(2))
				: source[axis + 1].add(time.multiply(velocity));
		for (int a = 0; a < 3; a++) {
			var cross = affine[0].multiply(source[a + 1]);
			for (int b = 0; b < 3; b++) cross = cross.add(affine[b + 1].multiply(source[secondIndex(a, b)]));
			int entry = secondIndex(axis, a);
			result[entry] = a == axis ? uniformAge
					? face.multiply(face).multiply(source[0]).add(face.multiply(time).multiply(velocity))
							.add(time.multiply(time).multiply(squared).divide(3))
					: source[entry].add(time.multiply(cross).multiply(2)).add(time.multiply(time).multiply(squared))
					: uniformAge ? face.multiply(source[a + 1]).add(time.multiply(cross).divide(2))
					: source[entry].add(time.multiply(cross));
		}
		return result;
	}
	private static int secondIndex(int a, int b) {
		int low = Math.min(a, b), high = Math.max(a, b);
		return low == 0 ? 4 + high : low == 1 ? 6 + high : 9;
	}
	private static void lowOrderPhaseMoments(FluidDomain domain, int donor, int receiver, int axis, boolean positive,
	                                        double[] q, double dtOverH, int donorSupport, int receiverSupport,
	                                        ExactRational[] open, ExactRational[] liquid, ExactRational[] dry,
	                                        ExactRational[] second, int selectedSourceSupport) {
		requireInput(domain, donor, receiver, axis, q[0], q[1], q[2], dtOverH, donorSupport, receiverSupport);
		donorSupport &= ~domain.geometry.cutCells.mask(donor);
		var rates = faceMicroRates(domain, donor, receiver, axis, positive, q[0], q[1], q[2], donorSupport, receiverSupport);
		var phase = domain.pressurePhaseGeometry;
		double fallback = poreScale(domain, donor), receiverFallback = poreScale(domain, receiver);
		int sign = positive ? 1 : -1;
		SignedDyadic[] normal = {SignedDyadic.ZERO, SignedDyadic.ZERO, SignedDyadic.ZERO};
		normal[(axis + 1) % 3] = SignedDyadic.of(q[1]).multiply(SignedDyadic.of(sign));
		normal[(axis + 2) % 3] = SignedDyadic.of(q[2]).multiply(SignedDyadic.of(sign));
		var velocity = new GasGeometryOverlap.HalfSpace(normal[0], normal[1], normal[2], SignedDyadic.of(-sign * q[0]));
		ExactRational b = rational(q[1]), c = rational(q[2]), centered = rational(q[0]).add(b.add(c).divide(2));
		ExactRational[] affine = null;
		if (second != null) {
			affine = new ExactRational[]{centered.multiply(sign), ExactRational.ZERO, ExactRational.ZERO, ExactRational.ZERO};
			affine[(axis + 1) % 3 + 1] = b.multiply(sign); affine[(axis + 2) % 3 + 1] = c.multiply(sign);
		}
		java.util.Arrays.fill(open, ExactRational.ZERO); java.util.Arrays.fill(liquid, ExactRational.ZERO);
		java.util.Arrays.fill(dry, ExactRational.ZERO);
		for (int m = 0; m < 8; m++) {
			int faceMicro = positive ? m | 1 << axis : m & ~(1 << axis), peer = m ^ 1 << axis;
			if ((donorSupport & 1 << m) == 0 || rates[faceMicro].isZero()) continue;
			ExactRational local = rational(phase.componentPoreScale(donor, m, fallback));
			if (local.isZero()) continue;
			ExactRational column = local;
			if ((donorSupport & 1 << peer) != 0) column = column.add(rational(phase.componentPoreScale(donor, peer, fallback)));
			if (column.isZero()) continue;
			ExactRational weight = rational(dtOverH).multiply(local).multiply(new ExactRational(column.denominator(), column.numerator()));
			open[m] = rates[faceMicro].multiply(weight);
			int receiverMicro = faceMicro ^ 1 << axis;
			double facePore = Math.min(phase.componentPoreScale(donor, faceMicro, fallback),
					phase.componentPoreScale(receiver, receiverMicro, receiverFallback));
			var wet = wetSourcePlane(domain, donor, m);
			boolean constant = wet.nx().signum() == 0 && wet.ny().signum() == 0 && wet.nz().signum() == 0;
			boolean selected = (selectedSourceSupport & 1 << m) != 0 && (selectedSourceSupport & 1 << faceMicro) != 0;
			ExactRational[] weighted = null;
			if (second != null && selected && (!constant || wet.threshold().signum() == 0)) {
				weighted = GasGeometryOverlap.normalizedAffineSecondMoments(m,
						constant ? List.of(velocity) : List.of(wet, velocity), affine);
				var scale = rational(facePore).multiply(weight).multiply(2);
				for (int i = 0; i < weighted.length; i++) weighted[i] = weighted[i].multiply(scale);
				liquid[m] = weighted[0];
				if (constant && !liquid[m].equals(open[m]))
					throw new IllegalStateException("Full low-order spatial measure differs from its volume");
			} else if (constant)
				liquid[m] = wet.threshold().signum() == 0 ? open[m] : ExactRational.ZERO;
			else {
				var part = GasGeometryOverlap.normalizedMoments(m, List.of(wet, velocity));
				ExactRational integral = centered.multiply(part[0]).add(b.multiply(part[(axis + 1) % 3 + 1]))
						.add(c.multiply(part[(axis + 2) % 3 + 1])).multiply(2L * sign);
				liquid[m] = integral.multiply(rational(facePore)).multiply(weight);
			}
			dry[m] = open[m].subtract(liquid[m]);
			if (liquid[m].signum() < 0 || dry[m].signum() < 0)
				throw new IllegalStateException("PLIC low-order phase measure exceeds its open source");
			if (weighted != null)
				for (int i = 0; i < second.length; i++) second[i] = second[i].add(weighted[i]);
		}
	}

	/** Actual wet source as an >= half-space, including certified constant empty and full sources. */
	static GasGeometryOverlap.HalfSpace wetSourcePlane(FluidDomain domain, int cell, int micro) {
		var phase = domain.pressurePhaseGeometry;
		var nx = SignedDyadic.of(phase.planeNormal(cell, micro, 0));
		var ny = SignedDyadic.of(phase.planeNormal(cell, micro, 1));
		var nz = SignedDyadic.of(phase.planeNormal(cell, micro, 2));
		if (nx.signum() == 0 && ny.signum() == 0 && nz.signum() == 0)
			return new GasGeometryOverlap.HalfSpace(nx, ny, nz,
					constantPhaseFraction(domain, cell, micro).isZero() ? SignedDyadic.of(1) : SignedDyadic.ZERO);
		return new GasGeometryOverlap.HalfSpace(nx.negate(), ny.negate(), nz.negate(), SignedDyadic.of(phase.planeOffset(cell, micro)).negate());
	}

	private static ExactRational constantPhaseFraction(FluidDomain domain, int cell, int micro) {
		var phase = domain.pressurePhaseGeometry;
		SignedDyadic stock = domain.markerGrid.exactCellVolumeInventory[cell], capacity = phase.poreVolume(cell);
		if (phase.hasComponentPlanes(cell)) {
			int mask = domain.geometry.cutCells.mask(cell);
			var components = PhaseLiquidGeometryEdit.current(cell, mask, domain.config.cellSize(), domain.markerGrid, domain.particles).components();
			var capacities = CellRemapMicroTopology.capacities(mask, capacity);
			for (int i = 0; i < components.size(); i++) if ((components.get(i).supportMask() & 1 << micro) != 0) {
				stock = components.get(i).volume(); capacity = capacities[i]; break;
			}
		}
		if (stock.signum() == 0) return ExactRational.ZERO;
		if (stock.equals(capacity)) return ExactRational.ONE;
		throw new IllegalStateException("Partial liquid source has no PLIC plane for its exact inventory");
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

	static void observe(ExactRational[] exact, double[] result) {
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

	/** Returns donor-centred V, X, Y, Z, XX, XY, XZ, YY, YZ, ZZ in cell-normalized coordinates. */
	static ExactRational[] sweptExactSecondMoments(FluidDomain domain, int donor, int receiver, int axis, boolean positive,
	                                            double intercept, double tangentSlope, double otherSlope, double dtOverH,
	                                            int donorSupport, int receiverSupport) {
		return sweptExactMoments(domain, donor, receiver, axis, positive, intercept, tangentSlope, otherSlope,
				dtOverH, donorSupport, receiverSupport, null, true);
	}

	private static ExactRational[] sweptExactMoments(FluidDomain domain, int donor, int receiver, int axis, boolean positive,
	                                               double intercept, double tangentSlope, double otherSlope, double dtOverH,
	                                               int donorSupport, int receiverSupport, GasGeometryOverlap.HalfSpace wetOverride) {
		return sweptExactMoments(domain, donor, receiver, axis, positive, intercept, tangentSlope, otherSlope,
				dtOverH, donorSupport, receiverSupport, wetOverride, false);
	}

	private static ExactRational[] sweptExactMoments(FluidDomain domain, int donor, int receiver, int axis, boolean positive,
	                                               double intercept, double tangentSlope, double otherSlope, double dtOverH,
	                                               int donorSupport, int receiverSupport, GasGeometryOverlap.HalfSpace wetOverride,
	                                               boolean secondMoments) {
		return sweptExactMoments(domain, donor, receiver, axis, positive, intercept, tangentSlope, otherSlope,
				dtOverH, donorSupport, receiverSupport, wetOverride, secondMoments, null);
	}
	private static ExactRational[] sweptExactMoments(FluidDomain domain, int donor, int receiver, int axis, boolean positive,
	                                               double intercept, double tangentSlope, double otherSlope, double dtOverH,
	                                               int donorSupport, int receiverSupport, GasGeometryOverlap.HalfSpace wetOverride,
	                                               boolean secondMoments, ExactRational[][] micros) {
		requireInput(domain, donor, receiver, axis, intercept, tangentSlope, otherSlope, dtOverH, donorSupport, receiverSupport);
		ExactRational[] total = new ExactRational[secondMoments ? 10 : 4];
		java.util.Arrays.fill(total, ExactRational.ZERO);
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
				var liquid = wetOverride == null ? wetSourcePlane(domain, donor, micro) : wetOverride;
				boolean constant = liquid.nx().signum() == 0 && liquid.ny().signum() == 0 && liquid.nz().signum() == 0;
				if (constant && liquid.threshold().signum() > 0) continue;
				List<GasGeometryOverlap.HalfSpace> constraints = constant ? List.of(wedge) : List.of(wedge, liquid);
				double pore = componentPores ? Math.min(phase.componentPoreScale(donor, micro, donorPore),
						phase.componentPoreScale(receiver, peerMicro, receiverPore)) : Math.min(donorPore, receiverPore);
				if (!Double.isFinite(pore) || pore < 0 || pore > 1) throw new IllegalStateException("Invalid affine sweep pore fraction");
				if (pore == 0) continue;
				ExactRational weight = rational(pore);
				ExactRational[] part = secondMoments ? GasGeometryOverlap.normalizedSecondMoments(micro, constraints)
						: GasGeometryOverlap.normalizedMoments(micro, constraints);
				for (int i = 0; i < total.length; i++) {
					part[i] = part[i].multiply(weight); total[i] = total[i].add(part[i]);
				}
				if (micros != null) micros[micro] = part;
			}
			validateMoments(total);
			return total;
		} catch (IllegalArgumentException magnitude) {
			throw new IllegalStateException("Exact affine liquid sweep exceeds the rational representation budget", magnitude);
		}
	}
	private static void validateMoments(ExactRational[] total) {
		if (total[0].signum() < 0 || total[0].compareTo(ExactRational.ONE) > 0)
			throw new IllegalStateException("Affine liquid sweep volume does not close");
		for (int i = 1; i < 4; i++) if (total[i].multiply(2).compareTo(total[0]) > 0
				|| total[i].multiply(-2).compareTo(total[0]) > 0)
			throw new IllegalStateException("Affine liquid sweep first moment lies outside its donor");
	}

	/** Micro sweep and wet measures use the same exact PLIC intersections and frozen trace. */
	static void sweptMicroVolumes(FluidDomain domain, int donor, int receiver, int axis, boolean positive,
	                             double intercept, double tangentSlope, double otherSlope, double dtOverH,
	                             int donorSupport, int receiverSupport, double[] swept, double[] wet) {
		sweptMicroVolumes(domain, donor, receiver, axis, positive, intercept, tangentSlope, otherSlope, dtOverH,
				donorSupport, receiverSupport, swept, wet, null);
	}
	static void sweptMicroVolumes(FluidDomain domain, int donor, int receiver, int axis, boolean positive,
	                             double intercept, double tangentSlope, double otherSlope, double dtOverH,
	                             int donorSupport, int receiverSupport, double[] swept, double[] wet, MicroSweep saved) {
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
			var liquid = wetSourcePlane(domain, donor, micro);
			boolean constant = liquid.nx().signum() == 0 && liquid.ny().signum() == 0 && liquid.nz().signum() == 0;
			if (constant && liquid.threshold().signum() > 0) continue;
			List<GasGeometryOverlap.HalfSpace> sweptPlanes = constant ? List.of(wedge) : List.of(wedge, liquid);
			double localPore = phase.componentPoreScale(donor, micro, donorPore);
			double peerPore = phase.componentPoreScale(receiver, peerMicro, receiverPore);
			if (!(Double.isFinite(localPore) && localPore >= 0 && localPore <= 1
					&& Double.isFinite(peerPore) && peerPore >= 0 && peerPore <= 1))
				throw new IllegalStateException("Invalid affine micro sweep pore fraction");
			wet[micro] = saved == null ? observeVolume(MicroBoxPlaneVolume.dryVolume(micro, liquid).multiply(rational(localPore)))
					: saved.wetVolume(micro, liquid, localPore);
			swept[micro] = saved != null ? observeVolume(saved.moments[micro] == null ? ExactRational.ZERO : saved.moments[micro][0])
					: dtOverH == 0 ? 0 : observeVolume(GasGeometryOverlap.normalizedVolume(micro, sweptPlanes)
							.multiply(rational(Math.min(localPore, peerPore))));
		}
	}

	/** One frozen component sweep; support restriction selects whole microboxes without reclipping. */
	static final class MicroSweep {
		static final long MAX_RETAINED_BYTES = 1024 + 8L * (48 + 4L * (160
				+ 8L * ((mmm.chemistry.ChemicalLimits.MAX_RATIONAL_BITS + 31L) / 32)));
		final long[] source;
		final ExactRational[][] moments = new ExactRational[8][];
		private final double[] wet = new double[8];
		private int wetMask;
		final long retainedBytes;
		MicroSweep(FluidDomain domain, int donor, int receiver, int axis, boolean positive, double[] q, double dt, int support) {
			source = source(domain, donor, receiver, axis, positive, q, dt, null);
			sweptExactMoments(domain, donor, receiver, axis, positive, q[0], q[1], q[2], dt, support, 255, null, false, moments);
			long bytes = 208 + 8L * source.length;
			for (var micro : moments) if (micro != null) {
				bytes += 48;
				for (var value : micro) bytes += 160 + 4L * ((value.numerator().bitLength() + 31L) / 32
						+ (value.denominator().bitLength() + 31L) / 32);
			}
			retainedBytes = bytes;
		}
		boolean matches(FluidDomain domain, int donor, int receiver, int axis, boolean positive, double[] q, double dt) {
			return source(domain, donor, receiver, axis, positive, q, dt, source) != null;
		}
		private synchronized double wetVolume(int micro, GasGeometryOverlap.HalfSpace plane, double localPore) {
			if ((wetMask & 1 << micro) == 0) {
				wet[micro] = observeVolume(MicroBoxPlaneVolume.dryVolume(micro, plane).multiply(rational(localPore)));
				wetMask |= 1 << micro;
			}
			return wet[micro];
		}
		ExactRational[] sum(int axis, boolean positive, int donorSupport, int receiverSupport) {
			ExactRational[] total = new ExactRational[4]; java.util.Arrays.fill(total, ExactRational.ZERO);
			try {
				for (int micro = 0; micro < 8; micro++) {
					int face = (micro & ~(1 << axis)) | (positive ? 1 << axis : 0);
					int peer = (micro & ~(1 << axis)) | (positive ? 0 : 1 << axis);
					if (moments[micro] == null || (donorSupport & 1 << micro) == 0
							|| (donorSupport & 1 << face) == 0 || (receiverSupport & 1 << peer) == 0) continue;
					for (int i = 0; i < 4; i++) total[i] = total[i].add(moments[micro][i]);
				}
				validateMoments(total);
				return total;
			} catch (IllegalArgumentException magnitude) {
				throw new IllegalStateException("Exact affine liquid sweep exceeds the rational representation budget", magnitude);
			}
		}
		private static long[] source(FluidDomain domain, int donor, int receiver, int axis, boolean positive, double[] q, double dt, long[] expected) {
			var phase = domain.pressurePhaseGeometry;
			double local = poreScale(domain, donor), peer = poreScale(domain, receiver);
			long[] result = expected == null ? new long[71] : expected; int n = 0;
			boolean checking = expected != null, same = true;
			same &= sourceBit(result, n++, phase.revision(), checking);
			same &= sourceBit(result, n++, domain.carrierMutationRevision(), checking);
			same &= sourceBit(result, n++, donor, checking); same &= sourceBit(result, n++, receiver, checking);
			same &= sourceBit(result, n++, axis, checking); same &= sourceBit(result, n++, positive ? 1 : 0, checking);
			for (int a = 0; a < 3; a++) same &= sourceBit(result, n++, Double.doubleToRawLongBits(q[a]), checking);
			same &= sourceBit(result, n++, Double.doubleToRawLongBits(dt), checking);
			same &= sourceBit(result, n++, domain.geometry.cutCells.mask(donor), checking);
			same &= sourceBit(result, n++, domain.geometry.cutCells.mask(receiver), checking);
			same &= sourceBit(result, n++, Double.doubleToRawLongBits(local), checking);
			same &= sourceBit(result, n++, Double.doubleToRawLongBits(peer), checking);
			same &= sourceBit(result, n++, phase.hasComponentPlanes(donor) || phase.hasComponentPlanes(receiver) ? 1 : 0, checking);
			for (int micro = 0; micro < 8; micro++) {
				same &= sourceBit(result, n++, Double.doubleToRawLongBits(phase.liquidFractionExact(donor, micro)), checking);
				for (int a = 0; a < 3; a++) same &= sourceBit(result, n++, Double.doubleToRawLongBits(phase.planeNormal(donor, micro, a)), checking);
				same &= sourceBit(result, n++, Double.doubleToRawLongBits(phase.planeOffset(donor, micro)), checking);
				same &= sourceBit(result, n++, Double.doubleToRawLongBits(phase.componentPoreScale(donor, micro, local)), checking);
				int peerMicro = (micro & ~(1 << axis)) | (positive ? 0 : 1 << axis);
				same &= sourceBit(result, n++, Double.doubleToRawLongBits(phase.componentPoreScale(receiver, peerMicro, peer)), checking);
			}
			return same ? result : null;
		}
		private static boolean sourceBit(long[] values, int index, long value, boolean checking) {
			if (checking) return values[index] == value;
			values[index] = value; return true;
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
	static void requireInput(FluidDomain domain, int donor, int receiver, int axis,
	                                 double intercept, double tangentSlope, double otherSlope, double dtOverH,
	                                 int donorSupport, int receiverSupport) {
		if (domain == null || donor < 0 || receiver < 0 || donor >= domain.cellCount || receiver >= domain.cellCount
				|| axis < 0 || axis > 2 || donorSupport < 0 || donorSupport > 255 || receiverSupport < 0 || receiverSupport > 255
				|| !Double.isFinite(intercept) || !Double.isFinite(tangentSlope) || !Double.isFinite(otherSlope)
				|| !Double.isFinite(dtOverH) || dtOverH < 0)
			throw new IllegalArgumentException("Invalid affine liquid sweep input");
	}
}
