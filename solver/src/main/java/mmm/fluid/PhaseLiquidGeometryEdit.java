package mmm.fluid;

import mmm.units.SignedDyadic;
import java.util.List;

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

	double firstMomentX() { return firstMoment(0); }
	double firstMomentY() { return firstMoment(1); }
	double firstMomentZ() { return firstMoment(2); }

	double firstMoment(int axis) {
		if (axis < 0 || axis > 2) throw new IllegalArgumentException("Invalid phase liquid moment axis");
		double moment = 0;
		for (var component : components) moment += component.firstMoment(axis);
		return moment;
	}

}
