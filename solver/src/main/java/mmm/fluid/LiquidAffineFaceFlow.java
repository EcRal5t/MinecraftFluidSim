package mmm.fluid;

import mmm.chemistry.ExactRational;
import java.util.Arrays;

/** Captured normal traces of the pressure velocity field, with separate positive and negative donor sweeps. */
final class LiquidAffineFaceFlow implements ConservativeLiquidFlux.FaceSweep {
	private final FluidDomain domain;
	private final ConservativeLiquidFlux faces;
	private final CellAffineVelocityField.FaceTrace[][] trace;
	private final double[] outgoing;

	LiquidAffineFaceFlow(FluidDomain domain, ConservativeLiquidFlux faces, CellAffineVelocityField field) {
		this.domain = domain; this.faces = faces;
		outgoing=new double[field.blockCount()];
		trace = new CellAffineVelocityField.FaceTrace[2 * faces.physicalFaceCount][];
		for (int f = 0; f < faces.physicalFaceCount; f++) for (int direction = 0; direction < 2; direction++) {
			int donor = direction == 0 ? faces.left[f] : faces.right[f];
			if (!field.hasCell(donor)) continue;
			var components = field.faceTraces(donor, faces.axis[f], direction == 0, domain.sizeX, domain.sizeY);
			for (var component : components) for (double value : component.q()) if (!Double.isFinite(value))
				throw new FluidDomain.PressureSolveBlockedException("Nonfinite liquid normal trace");
			trace[f + direction * faces.physicalFaceCount] = components;
		}
	}

	double maximumOutwardSpeed(int face, boolean positive, double verticalPrediction) {
		var components = trace[face + (positive ? 0 : faces.physicalFaceCount)];
		if (components == null) return 0;
		double maximum = 0;
		for (var component : components) {
			maximum=Math.max(maximum,outwardBound(face,positive,verticalPrediction,component.q()));
		}
		return maximum;
	}
	private double outwardBound(int face,boolean positive,double prediction,double[] q) {
		double a=q[0]+(faces.axis[face]==1 ? prediction : 0);
		double b=positive ? Math.max(0,q[1]) : Math.min(0,q[1]);
		double c=positive ? Math.max(0,q[2]) : Math.min(0,q[2]);
		if(a==0 && b==0 && c==0)return 0;
		double bound=positive ? Math.nextUp(Math.nextUp(a+b)+c) : Math.nextDown(Math.nextDown(a+b)+c);
		if(!Double.isFinite(bound))throw new FluidDomain.PressureSolveBlockedException("Unrepresentable affine face Courant bound");
		return Math.max(0,positive ? bound : -bound);
	}
	double courantRate(LiquidFluxGeometry geometry,double prediction) {
		Arrays.fill(outgoing,0); double maximum=0,h=domain.config.cellSize();
		for(int face=0;face<faces.physicalFaceCount;face++) {
			int left=faces.left[face],right=faces.right[face];
			if(domain.geometry.solid[left] || domain.geometry.solid[right])continue;
			for(int direction=0;direction<2;direction++) {
				boolean positive=direction==0; int donor=positive ? left : right;
				var components=trace[face+direction*faces.physicalFaceCount];
				if(components==null || !domain.gridToParticleStage.advectionCells()[donor])continue;
				for(var component:components) {
					double open=geometry.aperture(left,right,faces.axis[face],positive ? component.support() : 255,positive ? 255 : component.support());
					if(!(open>0))continue;
					double speed=outwardBound(face,positive,prediction,component.q());
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
		for (var component : components) {
			int support = donorSupport & component.support(); if (support == 0) continue;
			double[] q = component.q();
			LiquidAffineSweepGeometry.sweptMoments(domain, donor, receiver, axis, positive, q[0], q[1], q[2], dtOverH,
					support, receiverSupport, part);
			if (part != result) for (int i = 0; i < 4; i++) result[i] += part[i];
		}
	}
	@Override public void sweptMicroVolumes(int face, int donor, int receiver, int axis, boolean positive, double dtOverH,
	                                       int donorSupport, int receiverSupport, double[] swept, double[] wet) {
		var components = trace[face + (positive ? 0 : faces.physicalFaceCount)];
		Arrays.fill(swept, 0); Arrays.fill(wet, 0);
		if (components == null) return;
		double[] part = components.length == 1 ? swept : new double[swept.length];
		double[] partWet = components.length == 1 ? wet : new double[wet.length];
		for (var component : components) {
			int support = donorSupport & component.support(); if (support == 0) continue;
			double[] q = component.q();
			LiquidAffineSweepGeometry.sweptMicroVolumes(domain, donor, receiver, axis, positive, q[0], q[1], q[2], dtOverH,
					support, receiverSupport, part, partWet);
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
