package cc.ecisr.mmm.fluid;

/** Audits immutable resolved liquid support before persistence or backend admission. */
public final class LiquidGeometryStateValidation {
	private LiquidGeometryStateValidation() { }

	public static void requireCompatible(FlipParticleState state, double[] markerMass, double defaultDensity) {
		if (state.liquidGeometry() == null) return;
		if (markerMass != null && markerMass.length != state.particleCount()) throw new IllegalArgumentException("Incomplete geometric mass inventory");
		if (markerMass == null && (!(defaultDensity > 0) || !Double.isFinite(defaultDensity))) throw new IllegalArgumentException("Invalid geometric inventory density");
		LiquidGeometryStateMapper.requireCompatible(state.liquidGeometry(),
				LiquidGeometryInventoryView.of(state, markerMass, defaultDensity),
				state.sizeX(), state.sizeY(), state.sizeZ(), state.cellSize(), state.copyCollisionMicroMasks());
	}
}
