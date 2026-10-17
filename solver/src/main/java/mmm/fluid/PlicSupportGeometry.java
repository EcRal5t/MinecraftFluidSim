package mmm.fluid;
import mmm.units.SignedDyadic;
import java.util.List;
import static mmm.fluid.GasGeometryOverlap.HalfSpace;
/** PLIC half-spaces within a fixed open microbox. */
final class PlicSupportGeometry {
    private PlicSupportGeometry() { }
    static List<List<HalfSpace>> wetSupports(FlipPressurePhaseGeometry phase,int cell,int micro) { return plicSupport(phase,cell,micro,true); }
    static List<List<HalfSpace>> faceWetSupports(FlipPressurePhaseGeometry phase,int cell,int micro) { return plicSupport(phase,cell,micro,true); }
    static List<List<HalfSpace>> drySupports(FlipPressurePhaseGeometry phase,int cell,int micro) { return plicSupport(phase,cell,micro,false); }
	private static List<List<HalfSpace>> plicSupport(FlipPressurePhaseGeometry phase, int cell, int micro, boolean wet) {
		double fraction = phase.liquidFractionExact(cell, micro);
		if (wet ? fraction == 0 : fraction >= 1) return List.of();
		if (wet ? fraction >= 1 : fraction == 0) return List.of(List.of());
		var plane = new HalfSpace(SignedDyadic.of(phase.planeNormal(cell, micro, 0)),
				SignedDyadic.of(phase.planeNormal(cell, micro, 1)), SignedDyadic.of(phase.planeNormal(cell, micro, 2)),
				SignedDyadic.of(phase.planeOffset(cell, micro)));
		return List.of(List.of(wet ? new HalfSpace(plane.nx().negate(), plane.ny().negate(), plane.nz().negate(), plane.threshold().negate()) : plane));
	}
}
