package cc.ecisr.mmm.fluid;

/**
 * Backend-neutral quadrature contract for conservative FLIP marker maintenance.
 *
 * <p>These values describe the chosen two-point-per-axis discretization; they are
 * sampling resolution, not material calibration or a force/stability limiter.</p>
 */
public final class ConservativeMarkerSampling {
	public static final int AXIS_SAMPLES = 2;
	public static final int NOMINAL_MARKERS_PER_FULL_CELL = AXIS_SAMPLES * AXIS_SAMPLES * AXIS_SAMPLES;
	public static final float NOMINAL_MARKER_VOLUME = 1.0F / NOMINAL_MARKERS_PER_FULL_CELL;
	/** Half the spacing of the standard 2x2x2 volume quadrature. */
	public static final float DOMAIN_CONTACT_FRACTION = 0.5F / AXIS_SAMPLES;
	public static final float POSITION_EPSILON_FRACTION = 1.0E-3F;
	public static final float SOLID_CONTACT_FRACTION =
			DOMAIN_CONTACT_FRACTION + POSITION_EPSILON_FRACTION;

	private ConservativeMarkerSampling() { }

	/**
	 * Returns the quadrature count implied by exact parcel volume. One full cell receives the
	 * complete 2x2x2 rule; partial cells receive the corresponding number of equal-volume samples.
	 */
	public static int targetMarkers(float exactCellVolume) {
		if (!(exactCellVolume > 0.0F)) return 0;
		return Math.min(NOMINAL_MARKERS_PER_FULL_CELL,
				Math.max(1, (int) Math.ceil(exactCellVolume / NOMINAL_MARKER_VOLUME)));
	}
}
