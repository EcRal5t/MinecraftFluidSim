package cc.ecisr.mmm.fluid;

import cc.ecisr.mmm.units.SignedDyadic;
import java.util.HashMap;
import static cc.ecisr.mmm.fluid.PhaseChangeStageSupport.PhaseMarkerAllocation;
import static cc.ecisr.mmm.fluid.PhaseChangeStageSupport.PhaseRemovalPlan;

/** Future actual liquid volume and its position moment for one phase-edited cell. */
record PhaseLiquidGeometryEdit(
		int cell, int markerCount, SignedDyadic volume,
		SignedDyadic firstMomentX, SignedDyadic firstMomentY, SignedDyadic firstMomentZ) {
	PhaseLiquidGeometryEdit {
		if (cell < 0 || markerCount < 0 || volume == null || firstMomentX == null || firstMomentY == null || firstMomentZ == null
				|| volume.signum() < 0 || (markerCount == 0) != (volume.signum() == 0)
				|| volume.signum() == 0 && (firstMomentX.signum() != 0 || firstMomentY.signum() != 0 || firstMomentZ.signum() != 0))
			throw new IllegalArgumentException("Invalid phase liquid geometry edit");
	}

	/** Volume is in cell-volume units; each first moment is that volume multiplied by physical metres. */
	SignedDyadic firstMoment(int axis) {
		return switch (axis) { case 0 -> firstMomentX; case 1 -> firstMomentY; case 2 -> firstMomentZ;
			default -> throw new IllegalArgumentException("Invalid phase liquid moment axis"); };
	}

	static PhaseLiquidGeometryEdit current(PhaseChangeStageSupport.Workspace state, int cell) {
		var total = new Moments();
		for (int marker = state.markerGrid().cellMarkerHead[cell]; marker >= 0; marker = state.markerGrid().particleCellNext[marker])
			total.add(state.particles(), marker, state.particles().volume(marker));
		return total.edit(cell);
	}

	static PhaseLiquidGeometryEdit afterBoiling(PhaseChangeStageSupport.Workspace state, int cell, PhaseRemovalPlan removal) {
		var changes = new HashMap<Integer, PhaseMarkerAllocation>();
		for (var allocation : removal.allocations()) if (changes.put(allocation.marker(), allocation) != null)
			throw new IllegalArgumentException("Repeated phase liquid donor");
		var total = new Moments();
		for (int marker = state.markerGrid().cellMarkerHead[cell]; marker >= 0; marker = state.markerGrid().particleCellNext[marker]) {
			var allocation = changes.remove(marker);
			if (allocation != null && allocation.fullRemoval()) continue;
			total.add(state.particles(), marker, allocation == null ? state.particles().volume(marker) : allocation.newVolume());
		}
		if (!changes.isEmpty()) throw new IllegalArgumentException("Phase liquid donor belongs to another cell");
		// removeSwap changes ordinals; it leaves the surviving positions and their physical cell ownership intact.
		return total.edit(cell);
	}

	static PhaseLiquidGeometryEdit afterCondensation(
			PhaseChangeStageSupport.Workspace state, int cell, int target, float targetVolume, float appendedVolume, float[] appendedPosition) {
		var total = new Moments(); boolean found = target < 0;
		for (int marker = state.markerGrid().cellMarkerHead[cell]; marker >= 0; marker = state.markerGrid().particleCellNext[marker]) {
			if (marker == target) found = true;
			total.add(state.particles(), marker, marker == target ? targetVolume : state.particles().volume(marker));
		}
		if (!found) throw new IllegalArgumentException("Phase liquid receiver belongs to another cell");
		if (target < 0) {
			if (appendedPosition == null || appendedPosition.length != 3) throw new IllegalArgumentException("Missing published phase marker position");
			total.add(appendedVolume, appendedPosition[0], appendedPosition[1], appendedPosition[2]);
		}
		return total.edit(cell);
	}

	private static final class Moments {
		private int count;
		private SignedDyadic volume = SignedDyadic.ZERO;
		private final SignedDyadic[] first = {SignedDyadic.ZERO, SignedDyadic.ZERO, SignedDyadic.ZERO};
		void add(LiquidParticleStore particles, int marker, float value) {
			add(value, particles.positionX(marker), particles.positionY(marker), particles.positionZ(marker));
		}
		void add(float value, float x, float y, float z) {
			if (!(Float.isFinite(value) && value > 0) || !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z))
				throw new IllegalArgumentException("Invalid future phase liquid marker");
			SignedDyadic weight = SignedDyadic.of(value); volume = volume.add(weight); count = Math.addExact(count, 1);
			first[0] = first[0].add(weight.multiply(SignedDyadic.of(x)));
			first[1] = first[1].add(weight.multiply(SignedDyadic.of(y)));
			first[2] = first[2].add(weight.multiply(SignedDyadic.of(z)));
		}
		PhaseLiquidGeometryEdit edit(int cell) { return new PhaseLiquidGeometryEdit(cell, count, volume, first[0], first[1], first[2]); }
	}
}
