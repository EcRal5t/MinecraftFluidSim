package cc.ecisr.mmm.world;

/** Data-driven Minecraft-facing mass and geometry policy without loader types. */
public record MovingBodyDefinition(
		String id,
		SelectorKind selectorKind,
		String selectorId,
		MovingBodySurfaceSnapshot.CouplingMode couplingMode,
		double massKilograms,
		GeometryProvider geometryProvider
) {
	public enum SelectorKind { ENTITY_TYPE, ENTITY_TAG }
	public enum GeometryProvider { AXIS_ALIGNED_BOX }

	public MovingBodyDefinition {
		if (id == null || id.isBlank() || selectorKind == null || selectorId == null
				|| !selectorId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
				|| couplingMode == null || geometryProvider == null) {
			throw new IllegalArgumentException("Moving-body definition identity is invalid");
		}
		if (couplingMode == MovingBodySurfaceSnapshot.CouplingMode.DYNAMIC) {
			if (!(Double.isFinite(massKilograms) && massKilograms > 0.0)) {
				throw new IllegalArgumentException("Dynamic moving-body definition requires positive kg");
			}
		} else if (Double.doubleToRawLongBits(massKilograms) != Double.doubleToRawLongBits(0.0)) {
			throw new IllegalArgumentException("Kinematic moving-body definition must use zero kg");
		}
	}

	String canonical() {
		return id + '\n' + selectorKind + '\n' + selectorId + '\n' + couplingMode + '\n'
				+ Double.toHexString(massKilograms) + '\n' + geometryProvider + '\n';
	}
}
