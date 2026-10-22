package mmm.fluid;

/**
 * Immutable liquid-pressure continuation. Public fields carry physical pressure including
 * capillary and hydrostatic reconstruction; in-memory domain handoff retains the reduced
 * pressure column against that domain state's inventory and geometry.
 */
public final class CondensedPhasePressureField {
	private final boolean runtimeResidual;
	private final float pressureReferencePa;
	private final float[] relativeGaugePressurePa;

	public CondensedPhasePressureField(float pressureReferencePa, float[] relativeGaugePressurePa) {
		this(pressureReferencePa,relativeGaugePressurePa,false);
	}
	private CondensedPhasePressureField(float pressureReferencePa,float[] relativeGaugePressurePa,boolean runtimeResidual) {
		if (!Float.isFinite(pressureReferencePa) || relativeGaugePressurePa == null
				|| relativeGaugePressurePa.length == 0) {
			throw new IllegalArgumentException("Condensed-phase pressure field is invalid");
		}
		this.runtimeResidual=runtimeResidual;
		this.pressureReferencePa = pressureReferencePa;
		this.relativeGaugePressurePa = relativeGaugePressurePa.clone();
		for (float pressure : this.relativeGaugePressurePa) if (!Float.isFinite(pressure)) {
			throw new IllegalArgumentException("Condensed-phase pressure field contains a non-finite value");
		}
	}

	/** The enclosing immutable domain state supplies the inventory and geometry reference. */
	static CondensedPhasePressureField runtime(float[] residual) { return new CondensedPhasePressureField(0,residual,true); }
	boolean isRuntimeResidual() { return runtimeResidual; }
	void copyRuntimeResidualTo(float[] target) {
		if(!runtimeResidual)throw new IllegalStateException("Pressure field carries physical pressure");
		System.arraycopy(relativeGaugePressurePa,0,target,0,relativeGaugePressurePa.length);
	}
	public float pressureReferencePa() { return pressureReferencePa; }
	public int cellCount() { return relativeGaugePressurePa.length; }
	public float[] copyRelativeGaugePressurePa() { return relativeGaugePressurePa.clone(); }
}
