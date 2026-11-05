package mmm.fluid;

/**
 * Immutable physical gauge-pressure observations for checkpoint and phase handoff.
 */
public final class CondensedPhasePressureField {
	private final float pressureReferencePa;
	private final float[] relativeGaugePressurePa;

	public CondensedPhasePressureField(float pressureReferencePa, float[] relativeGaugePressurePa) {
		if (!Float.isFinite(pressureReferencePa) || relativeGaugePressurePa == null
				|| relativeGaugePressurePa.length == 0) {
			throw new IllegalArgumentException("Condensed-phase pressure field is invalid");
		}
		this.pressureReferencePa = pressureReferencePa;
		this.relativeGaugePressurePa = relativeGaugePressurePa.clone();
		for (float pressure : this.relativeGaugePressurePa) if (!Float.isFinite(pressure)) {
			throw new IllegalArgumentException("Condensed-phase pressure field contains a non-finite value");
		}
	}

	public float pressureReferencePa() { return pressureReferencePa; }
	public int cellCount() { return relativeGaugePressurePa.length; }
	public float[] copyRelativeGaugePressurePa() { return relativeGaugePressurePa.clone(); }
}
