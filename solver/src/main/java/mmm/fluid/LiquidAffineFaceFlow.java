package mmm.fluid;

import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/** Common normal numerical traces, with positive donor sweeps over disjoint shared and one-sided regions. */
final class LiquidAffineFaceFlow implements ConservativeLiquidFlux.FaceSweep {
	private final FluidDomain domain;
	private final ConservativeLiquidFlux faces;
	private final LiquidFaceFluxTrace.Piece[][] trace;
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
		domain.ensurePressureGeometry();
		this.domain = domain; this.faces = faces;
		sourceRevision = domain.carrierMutationRevision();
		phaseRevision = domain.pressurePhaseGeometry.revision();
		if (retentionLimit < 0) throw new IllegalArgumentException("Negative affine sweep retention budget");
		this.retentionLimit = retentionLimit;
		outgoing=new double[field.blockCount()];
		trace = LiquidFaceFluxTrace.capture(domain,faces,field);
		sweeps = new LiquidAffineSweepGeometry.MicroSweep[trace.length][];
		sourceBounds = new double[trace.length][];
		for (int slot=0;slot<trace.length;slot++) if(trace[slot]!=null) {
			sweeps[slot] = new LiquidAffineSweepGeometry.MicroSweep[trace[slot].length];
			sourceBounds[slot] = new double[trace[slot].length]; Arrays.fill(sourceBounds[slot],Double.NaN);
		}
	}
	private static long availableMemory() {
		Runtime runtime = Runtime.getRuntime();
		return Math.max(0, runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory()));
	}
	/** A constant, complete-component endpoint of this actual captured face trace. */
	boolean belongsTo(FluidDomain domain) {
		return this.domain == domain && sourceRevision == domain.carrierMutationRevision()
				&& phaseRevision == domain.pressurePhaseGeometry.revision();
	}
	double constantTrace(int face, int cell) {
		if (sourceRevision != domain.carrierMutationRevision() || face < 0 || face >= faces.physicalFaceCount
				|| cell != faces.left[face] && cell != faces.right[face]) return Double.NaN;
		var components = trace[face + (cell == faces.left[face] ? 0 : faces.physicalFaceCount)];
		if (components == null) return Double.NaN;
		double constant=Double.NaN; int support=0;
		for(var component:components) {
			var q=component.q(); if(q[1]!=0 || q[2]!=0)return Double.NaN;
			if(Double.isNaN(constant))constant=q[0];else if(constant!=q[0])return Double.NaN;
			support|=component.support();
		}
		return support==255 ? constant : Double.NaN;
	}
	private LiquidAffineSweepGeometry.MicroSweep sweep(int slot, int componentIndex, int donor, int receiver,
	                                                   int axis, boolean positive, double dt, int donorSupport, int receiverSupport, boolean capture) {
		return sweep(slot, componentIndex, donor, receiver, axis, positive, dt, donorSupport, receiverSupport, capture, false);
	}
	private LiquidAffineSweepGeometry.MicroSweep sweep(int slot, int componentIndex, int donor, int receiver,
	                                                   int axis, boolean positive, double dt, int donorSupport, int receiverSupport,
	                                                   boolean capture, boolean secondMoments) {
		var component = trace[slot][componentIndex];
		double[] q = component.q();
		LiquidAffineSweepGeometry.requireInput(domain, donor, receiver, axis, q[0], q[1], q[2], dt, donorSupport, receiverSupport);
		// Each component has its own publication lock; clipping never holds a shared accounting lock.
		synchronized (component) {
			var saved = sweeps[slot][componentIndex];
			if (saved != null && (!secondMoments || saved.secondMoments) && saved.covers(donorSupport, receiverSupport)
					&& saved.matches(domain, donor, receiver, axis, positive, component.q(), dt,component.clip())) return saved;
			if (saved != null) { retained.addAndGet(-saved.retainedBytes); sweeps[slot][componentIndex] = null; }
			long maximumBytes = secondMoments ? LiquidAffineSweepGeometry.MicroSweep.MAX_SECOND_RETAINED_BYTES
					: LiquidAffineSweepGeometry.MicroSweep.MAX_RETAINED_BYTES;
			boolean retain=capture && (secondMoments || donorSupport==component.support() && receiverSupport==255)
					&& retentionLimit-retained.get()>=1024 && maximumBytes<=availableMemory()/16;
			var computed = new LiquidAffineSweepGeometry.MicroSweep(domain, donor, receiver, axis, positive,
					component.q(), dt, donorSupport,receiverSupport,secondMoments,component.clip());
			if(!retain)return computed;
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
	private double outwardBound(int face, boolean positive, double prediction, LiquidFaceFluxTrace.Piece component) {
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
					double open=component.clip().isEmpty()
							? geometry.aperture(left,right,faces.axis[face],positive?component.support():255,positive?255:component.support())
							: LiquidAffineSweepGeometry.faceOutflowRate(domain,donor,positive?right:left,faces.axis[face],positive,
									positive?1:-1,0,0,component.support(),255,component.clip());
					if(!(open>0))continue;
					double speed=outwardBound(face,positive,prediction,component);
					// This field belongs to the liquid-only path; each open microbox has its full capacity.
					double capacity=Integer.bitCount(component.blockSupport())*.125;
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
								q[0], q[1], q[2], component.support(), 255,component.clip()) / aperture;
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
			var saved = sweep(face + (positive ? 0 : faces.physicalFaceCount), c, donor, receiver, axis, positive, dtOverH, support, receiverSupport, true);
			LiquidAffineSweepGeometry.observe(saved.sum(axis, positive, support, receiverSupport), part);
			if (part != result) for (int i = 0; i < 4; i++) result[i] += part[i];
		}
	}

	@Override public void sweptMicroVolumes(int face, int donor, int receiver, int axis, boolean positive, double dtOverH,
	                                       int donorSupport, int receiverSupport, double[] sourceWet, double[] swept) {
		var components = trace[face + (positive ? 0 : faces.physicalFaceCount)];
		Arrays.fill(swept, 0);
		if (components == null) return;
		double[] part = components.length == 1 ? swept : new double[swept.length];
		for (int c = 0; c < components.length; c++) {
			var component = components[c];
			int support = donorSupport & component.support(); if (support == 0) continue;
			var saved = sweep(face + (positive ? 0 : faces.physicalFaceCount), c, donor, receiver, axis, positive, dtOverH, support, receiverSupport, false);
			saved.volumes(axis,positive,support,receiverSupport,sourceWet,part);
			if (part != swept) for (int i = 0; i < swept.length; i++) swept[i] += part[i];
		}
	}
	@Override public ExactRational[] sweptSecondMoments(int face, int donor, int receiver, int axis, boolean positive,
	                                                              double dtOverH, int donorSupport, int receiverSupport) {
		var components = trace[face + (positive ? 0 : faces.physicalFaceCount)];
		if (components == null) throw new IllegalStateException("Positive liquid arrival has no captured affine trace");
		ExactRational[] result = null;
		for (int c = 0; c < components.length; c++) {
			var component = components[c];
			int support = donorSupport & component.support(); if (support == 0) continue;
			var saved = sweep(face + (positive ? 0 : faces.physicalFaceCount), c, donor, receiver, axis, positive,
					dtOverH, support, receiverSupport, true, true);
			var part = saved.secondSum(axis, positive, support, receiverSupport);
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
					support, receiverSupport, part,component.clip());
			if (part != result) for (int i = 0; i < 4; i++) result[i] += part[i];
		}
	}
}
