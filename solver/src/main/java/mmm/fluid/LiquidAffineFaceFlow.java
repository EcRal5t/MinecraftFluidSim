package mmm.fluid;

import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/** Captured normal traces of the pressure velocity field, with separate positive and negative donor sweeps. */
final class LiquidAffineFaceFlow implements ConservativeLiquidFlux.FaceSweep {
	private final FluidDomain domain;
	private final ConservativeLiquidFlux faces;
	private final CellAffineVelocityField.FaceTrace[][] trace;
	private final double[] outgoing;
	private final LiquidAffineSweepGeometry.MicroSweep[][] sweeps;
	private final double[][] sourceBounds;
	private final AtomicLong retained = new AtomicLong();
	private final long retentionLimit;
	private final long sourceRevision;
	private final long phaseRevision;

	LiquidAffineFaceFlow(FluidDomain domain, ConservativeLiquidFlux faces, CellAffineVelocityField field) {
		this(domain, faces, field, availableMemory() / 16);
	}
	LiquidAffineFaceFlow(FluidDomain domain, ConservativeLiquidFlux faces, CellAffineVelocityField field, long retentionLimit) {
		this.domain = domain; this.faces = faces;
		sourceRevision = domain.carrierMutationRevision();
		phaseRevision = domain.pressurePhaseGeometry.revision();
		if (retentionLimit < 0) throw new IllegalArgumentException("Negative affine sweep retention budget");
		this.retentionLimit = retentionLimit;
		outgoing=new double[field.blockCount()];
		trace = new CellAffineVelocityField.FaceTrace[2 * faces.physicalFaceCount][];
		sweeps = new LiquidAffineSweepGeometry.MicroSweep[trace.length][];
		sourceBounds = new double[trace.length][];
		for (int f = 0; f < faces.physicalFaceCount; f++) for (int direction = 0; direction < 2; direction++) {
			int donor = direction == 0 ? faces.left[f] : faces.right[f];
			if (!field.hasCell(donor)) continue;
			var components = field.faceTraces(donor, faces.axis[f], direction == 0, domain.sizeX, domain.sizeY);
			for (var component : components) for (double value : component.q()) if (!Double.isFinite(value))
				throw new FluidDomain.PressureSolveBlockedException("Nonfinite liquid normal trace");
			trace[f + direction * faces.physicalFaceCount] = components;
			sweeps[f + direction * faces.physicalFaceCount] = new LiquidAffineSweepGeometry.MicroSweep[components.length];
			sourceBounds[f + direction * faces.physicalFaceCount] = new double[components.length];
			Arrays.fill(sourceBounds[f + direction * faces.physicalFaceCount], Double.NaN);
		}
	}
	private static long availableMemory() {
		Runtime runtime = Runtime.getRuntime();
		return Math.max(0, runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory()));
	}
	/** A constant, complete-component endpoint of this actual captured face trace. */
	boolean belongsTo(FluidDomain domain) { return this.domain == domain && sourceRevision == domain.carrierMutationRevision(); }
	double constantTrace(int face, int cell) {
		if (sourceRevision != domain.carrierMutationRevision() || face < 0 || face >= faces.physicalFaceCount
				|| cell != faces.left[face] && cell != faces.right[face]) return Double.NaN;
		var components = trace[face + (cell == faces.left[face] ? 0 : faces.physicalFaceCount)];
		if (components == null || components.length != 1 || components[0].support() != 255) return Double.NaN;
		double[] q = components[0].q();
		return q[1] == 0 && q[2] == 0 ? q[0] : Double.NaN;
	}
	private LiquidAffineSweepGeometry.MicroSweep sweep(int slot, int componentIndex, int donor, int receiver,
	                                                   int axis, boolean positive, double dt, int donorSupport, int receiverSupport, boolean capture) {
		var component = trace[slot][componentIndex];
		double[] q = component.q();
		LiquidAffineSweepGeometry.requireInput(domain, donor, receiver, axis, q[0], q[1], q[2], dt, donorSupport, receiverSupport);
		// Each component has its own publication lock; clipping never holds a shared accounting lock.
		synchronized (component) {
			var saved = sweeps[slot][componentIndex];
			if (saved != null && saved.matches(domain, donor, receiver, axis, positive, component.q(), dt)) return saved;
			if (saved != null) { retained.addAndGet(-saved.retainedBytes); sweeps[slot][componentIndex] = null; }
			if (!capture || donorSupport != component.support() || receiverSupport != 255) return null;
			if (retentionLimit - retained.get() < 1024
					|| LiquidAffineSweepGeometry.MicroSweep.MAX_RETAINED_BYTES > availableMemory() / 16) return null;
			var computed = new LiquidAffineSweepGeometry.MicroSweep(domain, donor, receiver, axis, positive,
					component.q(), dt, component.support());
			long used;
			do {
				used = retained.get();
				if (computed.retainedBytes > retentionLimit - used || computed.retainedBytes > availableMemory() / 16) return computed;
			} while (!retained.compareAndSet(used, used + computed.retainedBytes));
			sweeps[slot][componentIndex] = computed;
			return computed;
		}
	}

	double maximumOutwardSpeed(int face, boolean positive, double verticalPrediction) {
		var components = trace[face + (positive ? 0 : faces.physicalFaceCount)];
		if (components == null) return 0;
		double maximum = 0;
		for (var component : components) {
			maximum=Math.max(maximum,outwardBound(face,positive,verticalPrediction,component));
		}
		return maximum;
	}
	private double outwardBound(int face, boolean positive, double prediction, CellAffineVelocityField.FaceTrace component) {
		int slot = face + (positive ? 0 : faces.physicalFaceCount), cached = -1;
		if (prediction == 0 && phaseRevision == domain.pressurePhaseGeometry.revision()
				&& sourceRevision == domain.carrierMutationRevision()) {
			for (int i = 0; i < trace[slot].length; i++) if (trace[slot][i] == component) { cached = i; break; }
			if (cached >= 0 && !Double.isNaN(sourceBounds[slot][cached])) return sourceBounds[slot][cached];
		}
		int axis = faces.axis[face], donor = positive ? faces.left[face] : faces.right[face];
		int receiver = positive ? faces.right[face] : faces.left[face], support = component.support();
		double[] q = component.q();
		if (q[0] == 0 && q[1] == 0 && q[2] == 0 && (axis != 1 || prediction == 0)) return 0;
		var sign = SignedDyadic.of(positive ? 1 : -1);
		SignedDyadic[] normal = {SignedDyadic.ZERO, SignedDyadic.ZERO, SignedDyadic.ZERO};
		normal[(axis + 1) % 3] = SignedDyadic.of(q[1]).multiply(sign);
		normal[(axis + 2) % 3] = SignedDyadic.of(q[2]).multiply(sign);
		var intercept = SignedDyadic.of(q[0]).add(SignedDyadic.of(axis == 1 ? prediction : 0)).multiply(sign);
		var function = new GasGeometryOverlap.HalfSpace(normal[0], normal[1], normal[2], intercept.negate());
		var cut = domain.geometry.cutCells;
		ExactRational maximum = ExactRational.ZERO;
		for (int micro = 0; micro < 8; micro++) {
			int faceMicro = positive ? micro | 1 << axis : micro & ~(1 << axis), peer = faceMicro ^ 1 << axis;
			if ((support & 1 << micro) == 0 || (support & 1 << faceMicro) == 0
					|| (cut.mask(donor) & (1 << micro | 1 << faceMicro)) != 0 || (cut.mask(receiver) & 1 << peer) != 0) continue;
			var value = domain.pressurePhaseGeometry.liquidShape(donor) == null
					? GasGeometryOverlap.maximumLinearValue(micro, LiquidAffineSweepGeometry.wetSourcePlane(domain, donor, micro), function)
					: LiquidShapeGeometry.maximumWetLinear(domain.pressurePhaseGeometry, donor, micro, function);
			if (value.compareTo(maximum) > 0) maximum = value;
		}
		double bound = maximum.toDouble();
		if (!Double.isFinite(bound)) throw new FluidDomain.PressureSolveBlockedException("Unrepresentable affine source Courant bound");
		if (PlicComponentGeometry.ratio(SignedDyadic.of(bound), SignedDyadic.of(1)).compareTo(maximum) < 0) bound = Math.nextUp(bound);
		if (!Double.isFinite(bound)) throw new FluidDomain.PressureSolveBlockedException("Unrepresentable affine source Courant bound");
		if (cached >= 0) sourceBounds[slot][cached] = bound;
		return bound;
	}
	@Override public double sourceCourant(LiquidFluxGeometry geometry, double dtOverH) {
		return courantRate(geometry, 0) * dtOverH * domain.config.cellSize();
	}
	@Override public double liquidTransferUpperBound(int face, boolean positive, double dtOverH) {
		var components = trace[face + (positive ? 0 : faces.physicalFaceCount)];
		if (components == null) return 0;
		var geometry = new LiquidFluxGeometry(domain); double rate = 0;
		for (var component : components) {
			double open = geometry.aperture(faces.left[face], faces.right[face], faces.axis[face],
					positive ? component.support() : 255, positive ? 255 : component.support());
			double bound = outwardBound(face, positive, 0, component);
			if (bound > 0 && open > 0) rate = Math.nextUp(rate + Math.nextUp(bound * open));
		}
		return rate == 0 ? 0 : Math.nextUp(rate * dtOverH);
	}
	double courantRate(LiquidFluxGeometry geometry,double prediction) {
		Arrays.fill(outgoing,0); double maximum=0,h=domain.config.cellSize();
		for(int face=0;face<faces.physicalFaceCount;face++) {
			int left=faces.left[face],right=faces.right[face];
			if(domain.geometry.solid[left] || domain.geometry.solid[right])continue;
			for(int direction=0;direction<2;direction++) {
				boolean positive=direction==0; int donor=positive ? left : right;
				var components=trace[face+direction*faces.physicalFaceCount];
				if(components==null || !domain.gridToParticleStage.advectionCells()[donor] && domain.pressurePhaseGeometry.liquidShape(donor)==null)continue;
				for(var component:components) {
					double open=geometry.aperture(left,right,faces.axis[face],positive ? component.support() : 255,positive ? 255 : component.support());
					if(!(open>0))continue;
					double speed=outwardBound(face,positive,prediction,component);
					// This field belongs to the liquid-only path; each open microbox has its full capacity.
					double capacity=Integer.bitCount(component.support())*.125;
					outgoing[component.velocityBlock()]+=speed/h*open/capacity;
					if(geometry.partialFace(left,right))maximum=Math.max(maximum,2*speed/h);
				}
			}
		}
		for(double rate:outgoing)maximum=Math.max(maximum,rate);
		return maximum;
	}

	void prepareVelocities(ConservativeLiquidFlux transport, LiquidFluxGeometry geometry) {
		if (transport.flux.length != 2 * faces.physicalFaceCount)
			throw new IllegalArgumentException("Affine transport needs two directional channels per face");
		for (int face = 0; face < faces.physicalFaceCount; face++) {
			double aperture = geometry.aperture(faces.left[face], faces.right[face], faces.axis[face]);
			for (int direction = 0; direction < 2; direction++) {
				int slot = face + direction * faces.physicalFaceCount;
				var components = trace[slot]; double speed = 0;
				if (components != null && aperture > 0) {
					int donor = direction == 0 ? faces.left[face] : faces.right[face];
					int receiver = direction == 0 ? faces.right[face] : faces.left[face];
					for (var component : components) {
						double[] q = component.q();
						speed += LiquidAffineSweepGeometry.faceOutflowRate(domain, donor, receiver, faces.axis[face], direction == 0,
								q[0], q[1], q[2], component.support(), 255) / aperture;
					}
				}
				if (!(Double.isFinite(speed) && speed >= 0))
					throw new FluidDomain.PressureSolveBlockedException("Unrepresentable directional liquid face rate");
				transport.velocity[slot] = Math.copySign(speed, direction == 0 ? 1 : -1);
			}
		}
	}

	@Override public void sweptMoments(int face, int donor, int receiver, int axis, boolean positive, double dtOverH,
	                                  int donorSupport, int receiverSupport, double[] result) {
		if (result == null || result.length < 4) throw new IllegalArgumentException("Missing affine sweep moments");
		var components = trace[face + (positive ? 0 : faces.physicalFaceCount)];
		Arrays.fill(result, 0);
		if (components == null) return;
		if (result.length >= 10) {
			var exact = sweptSecondMoments(face, donor, receiver, axis, positive, dtOverH, donorSupport, receiverSupport);
			for (int i = 0; i < exact.length; i++) {
				result[i] = exact[i].toDouble();
				if (!Double.isFinite(result[i]) || !exact[i].isZero() && result[i] == 0)
					throw new IllegalStateException("Affine sweep moment cannot be represented in binary64");
			}
			return;
		}
		double[] part = components.length == 1 ? result : new double[4];
		for (int c = 0; c < components.length; c++) {
			var component = components[c];
			int support = donorSupport & component.support(); if (support == 0) continue;
			double[] q = component.q();
			var saved = sweep(face + (positive ? 0 : faces.physicalFaceCount), c, donor, receiver, axis, positive, dtOverH, support, receiverSupport, true);
			if (saved == null) LiquidAffineSweepGeometry.sweptMoments(domain, donor, receiver, axis, positive, q[0], q[1], q[2], dtOverH,
					support, receiverSupport, part);
			else LiquidAffineSweepGeometry.observe(saved.sum(axis, positive, support, receiverSupport), part);
			if (part != result) for (int i = 0; i < 4; i++) result[i] += part[i];
		}
	}

	@Override public double lowOrderVolumes(int face, int donor, int receiver, int axis, boolean positive,
	                                       double dtOverH, int donorSupport, int receiverSupport, double[] liquid) {
		Arrays.fill(liquid, 0);
		var components = trace[face + (positive ? 0 : faces.physicalFaceCount)];
		if (components == null) return 0;
		var open = new ExactRational[8]; var wet = new ExactRational[8]; var dry = new ExactRational[8];
		ExactRational total = ExactRational.ZERO;
		for (var component : components) {
			int support = donorSupport & component.support(); if (support == 0) continue;
			LiquidAffineSweepGeometry.lowOrderPhaseVolumes(domain, donor, receiver, axis, positive, component.q(),
					dtOverH, component.support(), receiverSupport, open, wet, dry);
			ExactRational sum = ExactRational.ZERO;
			for (int m = 0; m < 8; m++) if ((support & 1 << m) != 0
					&& (support & 1 << (positive ? m | 1 << axis : m & ~(1 << axis))) != 0) {
				liquid[m] += wet[m].toDouble(); sum = sum.add(wet[m]);
			}
			total = total.add(sum);
		}
		double observed = total.toDouble();
		if (!Double.isFinite(observed) || observed < 0 || observed == 0 && !total.isZero())
			throw new IllegalStateException("Unrepresentable low-order phase flux");
		return observed;
	}
	@Override public ExactRational[] arrivalSecondMoments(int face, int donor, int receiver, int axis, boolean positive,
	                                                     double dtOverH, int donorSupport, int receiverSupport, boolean lowOrder) {
		return arrivalSecondMoments(face, donor, receiver, axis, positive, dtOverH, donorSupport, receiverSupport, donorSupport, lowOrder);
	}
	@Override public ExactRational[] arrivalSecondMoments(int face, int donor, int receiver, int axis, boolean positive,
	                                                     double dtOverH, int donorSupport, int receiverSupport, int selectedSourceSupport,
	                                                     boolean lowOrder) {
		if (selectedSourceSupport < 0 || selectedSourceSupport > 255 || (selectedSourceSupport & ~donorSupport) != 0)
			throw new IllegalArgumentException("Arrival source selection must belong to donor support");
		var result = new ExactRational[10]; Arrays.fill(result, ExactRational.ZERO);
		var components = trace[face + (positive ? 0 : faces.physicalFaceCount)];
		if (components == null) return result;
		for (var component : components) {
			int support = donorSupport & component.support(); if (support == 0) continue;
			int selected = support & selectedSourceSupport; if (selected == 0) continue;
			// The complete donor path remains available even when only its inner source microbox is selected.
			for (int micro = 0; micro < 8; micro++) if ((support & 1 << (positive ? micro | 1 << axis : micro & ~(1 << axis))) == 0)
				selected &= ~(1 << micro);
			double[] q = component.q();
			var source = lowOrder ? LiquidAffineSweepGeometry.lowOrderPhaseSecondMoments(domain, donor, receiver, axis, positive,
					q, dtOverH, component.support(), receiverSupport, selected)
					: LiquidAffineSweepGeometry.sweptExactSecondMoments(domain, donor, receiver, axis, positive,
							q[0], q[1], q[2], dtOverH, support, receiverSupport, selected);
			var part = LiquidAffineSweepGeometry.arrivalSecondMoments(source, axis, positive, q, dtOverH, lowOrder);
			for (int i = 0; i < result.length; i++) result[i] = result[i].add(part[i]);
		}
		return result;
	}
	@Override public void sweptMicroVolumes(int face, int donor, int receiver, int axis, boolean positive, double dtOverH,
	                                       int donorSupport, int receiverSupport, double[] swept, double[] wet) {
		var components = trace[face + (positive ? 0 : faces.physicalFaceCount)];
		Arrays.fill(swept, 0); Arrays.fill(wet, 0);
		if (components == null) return;
		double[] part = components.length == 1 ? swept : new double[swept.length];
		double[] partWet = components.length == 1 ? wet : new double[wet.length];
		for (int c = 0; c < components.length; c++) {
			var component = components[c];
			int support = donorSupport & component.support(); if (support == 0) continue;
			double[] q = component.q();
			var saved = sweep(face + (positive ? 0 : faces.physicalFaceCount), c, donor, receiver, axis, positive, dtOverH, support, receiverSupport, false);
			LiquidAffineSweepGeometry.sweptMicroVolumes(domain, donor, receiver, axis, positive, q[0], q[1], q[2], dtOverH,
					support, receiverSupport, part, partWet, saved);
			if (part != swept) for (int i = 0; i < swept.length; i++) { swept[i] += part[i]; wet[i] += partWet[i]; }
		}
	}
	@Override public ExactRational[] sweptSecondMoments(int face, int donor, int receiver, int axis, boolean positive,
	                                                              double dtOverH, int donorSupport, int receiverSupport) {
		var components = trace[face + (positive ? 0 : faces.physicalFaceCount)];
		if (components == null) throw new IllegalStateException("Positive liquid arrival has no captured affine trace");
		ExactRational[] result = null;
		for (var component : components) {
			int support = donorSupport & component.support(); if (support == 0) continue;
			double[] q = component.q();
			var part = LiquidAffineSweepGeometry.sweptExactSecondMoments(domain, donor, receiver, axis, positive, q[0], q[1], q[2], dtOverH,
					support, receiverSupport);
			if (result == null) result = part;
			else for (int i = 0; i < result.length; i++) result[i] = result[i].add(part[i]);
		}
		if (result == null) { result = new ExactRational[10]; Arrays.fill(result, ExactRational.ZERO); }
		return result;
	}

	@Override public void sweptPhaseMoments(PhaseVolumeTransportGeometry phases, int phase, int face, int donor,
	                                       int receiver, int axis, boolean positive, double width, double dtOverH,
	                                       int donorSupport, int receiverSupport, double[] result) {
		var components = trace[face + (positive ? 0 : faces.physicalFaceCount)];
		Arrays.fill(result, 0);
		if (components == null) return;
		double[] part = components.length == 1 ? result : new double[4];
		for (var component : components) {
			int support = donorSupport & component.support(); if (support == 0) continue;
			double[] q = component.q();
			phases.sweptAffineMoments(phase, donor, receiver, axis, positive, q[0], q[1], q[2], dtOverH,
					support, receiverSupport, part);
			if (part != result) for (int i = 0; i < 4; i++) result[i] += part[i];
		}
	}
}
