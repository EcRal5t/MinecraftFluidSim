package mmm.fluid;

import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import static mmm.fluid.PhaseChangeStageSupport.PhaseMarkerAllocation;
import static mmm.fluid.PhaseChangeStageSupport.PhaseRemovalPlan;

/** Future actual liquid stock in every open collision component of one phase-edited cell. */
record PhaseLiquidGeometryEdit(int cell, int collisionMask, List<LiquidComponentMoments> components) {
	PhaseLiquidGeometryEdit {
		if (cell < 0 || collisionMask < 0 || collisionMask > 255 || components == null)
			throw new IllegalArgumentException("Invalid phase liquid geometry edit");
		components = List.copyOf(components);
		int[] support = CellRemapMicroTopology.components(collisionMask);
		if (components.size() != support.length)
			throw new IllegalArgumentException("Phase liquid geometry must include every open component");
		for (int i = 0; i < support.length; i++) {
			var component = components.get(i);
			if (component.cell() != cell || component.supportMask() != support[i])
				throw new IllegalArgumentException("Phase liquid component geometry disagrees with its collision mask");
		}
	}

	int markerCount() {
		int count = 0;
		for (var component : components) count = Math.addExact(count, component.markerCount());
		return count;
	}

	SignedDyadic volume() {
		SignedDyadic volume = SignedDyadic.ZERO;
		for (var component : components) volume = volume.add(component.volume());
		return volume;
	}

	SignedDyadic firstMomentX() { return firstMoment(0); }
	SignedDyadic firstMomentY() { return firstMoment(1); }
	SignedDyadic firstMomentZ() { return firstMoment(2); }

	SignedDyadic firstMoment(int axis) {
		if (axis < 0 || axis > 2) throw new IllegalArgumentException("Invalid phase liquid moment axis");
		SignedDyadic moment = SignedDyadic.ZERO;
		for (var component : components) moment = moment.add(component.firstMoment(axis));
		return moment;
	}

	static PhaseLiquidGeometryEdit current(PhaseChangeStageSupport.Workspace state, int cell) {
		if (cell < 0 || cell >= state.cellCount()) throw new IllegalArgumentException("Invalid phase liquid cell");
		return current(cell, state.geometry().cutCells.mask(cell), state.cellSize(), state.markerGrid(), state.particles());
	}

	static PhaseLiquidGeometryEdit current(PhaseChangeStageSupport.Workspace state, int cell, float[] futureMarkerVolumes) {
		if (cell < 0 || cell >= state.cellCount()) throw new IllegalArgumentException("Invalid phase liquid cell");
		return current(cell, state.geometry().cutCells.mask(cell), state.cellSize(), state.markerGrid(), state.particles(), futureMarkerVolumes);
	}

	static PhaseLiquidGeometryEdit current(
			int cell, int collisionMask, float cellSize, MarkerGridIndex markers, LiquidParticleStore particles) {
		return current(cell, collisionMask, cellSize, markers, particles, null);
	}

	/** Volumes retain original marker ordinals; a zero removes that marker from the future tuple. */
	static PhaseLiquidGeometryEdit current(
			int cell, int collisionMask, float cellSize, MarkerGridIndex markers, LiquidParticleStore particles,
			float[] futureMarkerVolumes) {
		if (markers == null || particles == null || cell < 0 || cell >= markers.cellCount)
			throw new IllegalArgumentException("Invalid current liquid component input");
		if (futureMarkerVolumes != null && futureMarkerVolumes.length != particles.count())
			throw new IllegalArgumentException("Future liquid volumes must use the original marker count");
		var total = new Moments(cell, collisionMask, cellSize, 0, 0);
		for (int marker = markers.cellMarkerHead[cell]; marker >= 0; marker = markers.particleCellNext[marker]) {
			float value = futureMarkerVolumes == null ? particles.volume(marker) : futureMarkerVolumes[marker];
			if (!Float.isFinite(value) || value < 0) throw new IllegalArgumentException("Invalid future liquid volume");
			if (value > 0) total.add(particles, marker, value);
		}
		return total.edit();
	}

	/** Complete component tuples from the float positions that wholesale publication will store. */
	static List<PhaseLiquidGeometryEdit> afterCellRemap(FluidDomain domain, CellRemapMarkerColumns markers) {
		var totals = new Moments[domain.cellCount];
		float h = domain.config.cellSize();
		for (int cell = 0; cell < totals.length; cell++)
			totals[cell] = new Moments(cell, domain.geometry.cutCells.mask(cell), h, domain.sizeX, domain.sizeY);
		for (int ordinal = 0; ordinal < markers.size(); ordinal++) {
			int cell = markers.cell[ordinal];
			if (cell < 0 || cell >= totals.length) throw new IllegalArgumentException("Future liquid marker cell is outside the domain");
			int x = cell % domain.sizeX, y = cell / domain.sizeX % domain.sizeY, z = cell / (domain.sizeX * domain.sizeY);
			totals[cell].add(markers.volume[ordinal],
					(float) ((x + 0.5 + markers.offset[0][ordinal]) * h),
					(float) ((y + 0.5 + markers.offset[1][ordinal]) * h),
					(float) ((z + 0.5 + markers.offset[2][ordinal]) * h));
		}
		var edits = new ArrayList<PhaseLiquidGeometryEdit>(totals.length);
		for (var total : totals) edits.add(total.edit());
		return List.copyOf(edits);
	}

	static PhaseLiquidGeometryEdit afterBoiling(PhaseChangeStageSupport.Workspace state, int cell, PhaseRemovalPlan removal) {
		var changes = new HashMap<Integer, PhaseMarkerAllocation>();
		for (var allocation : removal.allocations()) if (changes.put(allocation.marker(), allocation) != null)
			throw new IllegalArgumentException("Repeated phase liquid donor");
		var total = new Moments(state, cell);
		for (int marker = state.markerGrid().cellMarkerHead[cell]; marker >= 0; marker = state.markerGrid().particleCellNext[marker]) {
			var allocation = changes.remove(marker);
			if (allocation != null && allocation.fullRemoval()) continue;
			total.add(state.particles(), marker, allocation == null ? state.particles().volume(marker) : allocation.newVolume());
		}
		if (!changes.isEmpty()) throw new IllegalArgumentException("Phase liquid donor belongs to another cell");
		// removeSwap changes ordinals; it leaves the surviving positions and their physical cell ownership intact.
		return total.edit();
	}

	record InsertedMarker(float volume, float x, float y, float z) { }

	/** Adds actual published marker positions to the complete existing component tuples. */
	static PhaseLiquidGeometryEdit afterInsertion(FluidDomain domain, int cell, List<InsertedMarker> appended) {
		var total = new Moments(cell, domain.geometry.cutCells.mask(cell), domain.config.cellSize(), domain.sizeX, domain.sizeY);
		for (int marker = domain.markerGrid.cellMarkerHead[cell]; marker >= 0; marker = domain.markerGrid.particleCellNext[marker])
			if (domain.particles.volume(marker) > 0) total.add(domain.particles, marker, domain.particles.volume(marker));
		for (var marker : appended) total.add(marker.volume(), marker.x(), marker.y(), marker.z());
		return total.edit();
	}

	static PhaseLiquidGeometryEdit afterCondensation(
			PhaseChangeStageSupport.Workspace state, int cell, int target, float targetVolume, float appendedVolume, float[] appendedPosition) {
		var total = new Moments(state, cell); boolean found = target < 0;
		for (int marker = state.markerGrid().cellMarkerHead[cell]; marker >= 0; marker = state.markerGrid().particleCellNext[marker]) {
			if (marker == target) found = true;
			total.add(state.particles(), marker, marker == target ? targetVolume : state.particles().volume(marker));
		}
		if (!found) throw new IllegalArgumentException("Phase liquid receiver belongs to another cell");
		if (target < 0) {
			if (appendedPosition == null || appendedPosition.length != 3) throw new IllegalArgumentException("Missing published phase marker position");
			total.add(appendedVolume, appendedPosition[0], appendedPosition[1], appendedPosition[2]);
		}
		return total.edit();
	}

	private static final class Moments {
		private final int cell, mask, sizeX, sizeY;
		private final float cellSize;
		private final int[] support, count;
		private final SignedDyadic[] volume;
		private final SignedDyadic[][] first;
		Moments(PhaseChangeStageSupport.Workspace state, int cell) {
			this(cell, phaseMask(state, cell), state.cellSize(), state.sizeX(), state.sizeY());
		}
		private static int phaseMask(PhaseChangeStageSupport.Workspace state, int cell) {
			if (cell < 0 || cell >= state.cellCount()) throw new IllegalArgumentException("Invalid phase liquid cell");
			return state.geometry().cutCells.mask(cell);
		}
		Moments(int cell, int mask, float cellSize, int sizeX, int sizeY) {
			if (cell < 0 || mask < 0 || mask > 255 || !(cellSize > 0) || !Float.isFinite(cellSize))
				throw new IllegalArgumentException("Invalid liquid component geometry");
			this.cell = cell; this.mask = mask; this.cellSize = cellSize; this.sizeX = sizeX; this.sizeY = sizeY;
			support = CellRemapMicroTopology.components(mask).clone(); count = new int[support.length];
			volume = new SignedDyadic[support.length]; first = new SignedDyadic[support.length][3];
			for (int i = 0; i < support.length; i++) {
				volume[i] = SignedDyadic.ZERO;
				java.util.Arrays.fill(first[i], SignedDyadic.ZERO);
			}
		}
		void add(LiquidParticleStore particles, int marker, float value) {
			add(value, particles.positionX(marker), particles.positionY(marker), particles.positionZ(marker));
		}
		void add(float value, float x, float y, float z) {
			if (!(Float.isFinite(value) && value > 0) || !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z))
				throw new IllegalArgumentException("Invalid future phase liquid marker");
			float h = cellSize;
			if (sizeX > 0 && ((int) Math.floor(x / h) != cell % sizeX
					|| (int) Math.floor(y / h) != cell / sizeX % sizeY
					|| (int) Math.floor(z / h) != cell / (sizeX * sizeY)))
				throw new IllegalArgumentException("Future phase liquid marker belongs to another cell");
			int selected = CellRemapMicroTopology.positionSupport(mask, h, x, y, z), index = 0;
			while (index < support.length && support[index] != selected) index++;
			if (index == support.length) throw new IllegalArgumentException("Missing future phase liquid component");
			SignedDyadic weight = SignedDyadic.of(value);
			volume[index] = volume[index].add(weight); count[index] = Math.addExact(count[index], 1);
			first[index][0] = first[index][0].add(weight.multiply(SignedDyadic.of(x)));
			first[index][1] = first[index][1].add(weight.multiply(SignedDyadic.of(y)));
			first[index][2] = first[index][2].add(weight.multiply(SignedDyadic.of(z)));
		}
		PhaseLiquidGeometryEdit edit() {
			var components = new ArrayList<LiquidComponentMoments>(support.length);
			for (int i = 0; i < support.length; i++) components.add(new LiquidComponentMoments(
					cell, support[i], count[i], volume[i], first[i][0], first[i][1], first[i][2]));
			return new PhaseLiquidGeometryEdit(cell, mask, components);
		}
	}
}
