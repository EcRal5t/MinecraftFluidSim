package cc.ecisr.mmm.fluid;

/** Read-only raw carrier values shared by native geometry capture and restore preflight. */
interface LiquidGeometryInventoryView {
	int count();
	double volume(int marker);
	double mass(int marker);
	double position(int marker, int axis);
	double velocity(int marker, int axis);

	static LiquidGeometryInventoryView of(LiquidParticleStore particles) {
		return new LiquidGeometryInventoryView() {
			public int count() { return particles.count(); }
			public double volume(int p) { return particles.volume(p); }
			public double mass(int p) { return particles.mass(p); }
			public double position(int p, int a) { return a == 0 ? particles.positionX(p) : a == 1 ? particles.positionY(p) : particles.positionZ(p); }
			public double velocity(int p, int a) { return a == 0 ? particles.velocityX(p) : a == 1 ? particles.velocityY(p) : particles.velocityZ(p); }
		};
	}
	static LiquidGeometryInventoryView of(FlipParticleState state, double[] masses, double defaultDensity) {
		return new LiquidGeometryInventoryView() {
			public int count() { return state.particleCount(); }
			public double volume(int p) { return state.positionVolume()[4 * p + 3]; }
			public double mass(int p) { return masses == null ? volume(p) * defaultDensity : masses[p]; }
			public double position(int p, int a) { return state.positionVolume()[4 * p + a]; }
			public double velocity(int p, int a) { return state.particleVelocities()[3 * p + a]; }
		};
	}
}
