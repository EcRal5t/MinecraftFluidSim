package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.units.SignedDyadic;
import cc.ecisr.mmm.units.ExactVolume;

/** Read-only raw carrier values shared by native geometry capture and restore preflight. */
interface LiquidGeometryInventoryView {
	int count();
	double volume(int marker);
	double mass(int marker);
	default ExactVolume exactVolume(int marker) { return ExactVolume.of(volume(marker)); }
	default SignedDyadic exactMass(int marker) { return SignedDyadic.of(mass(marker)); }
	double position(int marker, int axis);
	double velocity(int marker, int axis);

	static LiquidGeometryInventoryView of(LiquidParticleStore particles) {
		return new LiquidGeometryInventoryView() {
			public int count() { return particles.count(); }
			public double volume(int p) { return particles.exactVolume(p).toDouble(); }
			public double mass(int p) { return particles.mass(p); }
			public ExactVolume exactVolume(int p) { return particles.exactVolume(p); }
			public SignedDyadic exactMass(int p) { return particles.exactMass(p); }
			public double position(int p, int a) { return a == 0 ? particles.positionX(p) : a == 1 ? particles.positionY(p) : particles.positionZ(p); }
			public double velocity(int p, int a) { return a == 0 ? particles.velocityX(p) : a == 1 ? particles.velocityY(p) : particles.velocityZ(p); }
		};
	}
	static LiquidGeometryInventoryView of(FlipParticleState state, double[] masses, double defaultDensity) {
		return new LiquidGeometryInventoryView() {
			public int count() { return state.particleCount(); }
			public double volume(int p) { return state.inventory() == null ? state.positionVolume()[4 * p + 3] : state.inventory().volume(p).toDouble(); }
			public double mass(int p) { return state.inventory() != null ? state.inventory().mass(p).toDouble() : masses == null ? volume(p) * defaultDensity : masses[p]; }
			public ExactVolume exactVolume(int p) { return state.inventory() == null ? ExactVolume.of(volume(p)) : state.inventory().volume(p); }
			public SignedDyadic exactMass(int p) { return state.inventory() == null ? SignedDyadic.of(mass(p)) : state.inventory().mass(p); }
			public double position(int p, int a) { return state.positionVolume()[4 * p + a]; }
			public double velocity(int p, int a) { return state.particleVelocities()[3 * p + a]; }
		};
	}
}
