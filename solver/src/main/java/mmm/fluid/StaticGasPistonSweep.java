package mmm.fluid;

import mmm.units.SignedDyadic;
import java.util.Arrays;
import java.util.Objects;

/** Certifies a closed, single-cell quasistatic piston with one unchanged receiving chamber. */
final class StaticGasPistonSweep {
	record Transfer(int source, int destination) { }
	private StaticGasPistonSweep() { }

	static Transfer certify(GasRuntimeGeometry before, GasRuntimeGeometry after, int cell) {
		if (!before.sealedExterior() || !after.sealedExterior() || cell < 0
				|| before.geometry().collisionMask(cell) != 0 || after.geometry().collisionMask(cell) != 0
				|| !before.poreVolume(cell).equals(SignedDyadic.of(1))
				|| !after.poreVolume(cell).equals(SignedDyadic.of(1))
				|| !Arrays.equals(before.occupiedSvq(), after.occupiedSvq())) return null;
		int source = before.commonCellNode(cell);
		if (source <= 0 || !after.liquidStock().get(cell).volume().equals(SignedDyadic.of(1))) return null;
		int axis = -1, sign = 0;
		for (int a = 0; a < 3; a++) {
			float n = before.planeNormal(cell, 0, a);
			if (n == 0) continue;
			if (axis >= 0 || Math.abs(n) != 1) return null;
			axis = a; sign = n > 0 ? 1 : -1;
		}
		if (axis < 0) return null;
		double offset = before.planeOffset(cell, 0), depth = sign * offset;
		if (!(depth > 0 && depth < 1)) return null;
		for (int micro = 0; micro < 8; micro++) {
			if (after.geometry().piece(8 * cell + micro) != null) return null;
			if (before.planeOffset(cell, micro) != offset) return null;
			for (int a = 0; a < 3; a++)
				if (before.planeNormal(cell, micro, a) != (a == axis ? sign : 0)) return null;
		}
		for (var piece : before.geometry().pieces())
			if (before.nodeOfPiece(piece.id()) == source && piece.cell() != cell) return null;
		// The piston translates the original axis plane toward its dry end face. That face's
		// positive aperture stays unchanged until the last positive source volume disappears.
		int receiver = -1;
		for (var port : before.geometry().ports()) {
			int first = before.nodeOfPiece(port.first()), second = before.nodeOfPiece(port.second());
			if (first == second || first != source && second != source) continue;
			int peer = first == source ? second : first;
			if (peer <= 0 || port.axis() != axis || (first == source ? 1 : -1) != sign
					|| receiver > 0 && receiver != peer) return null;
			receiver = peer;
		}
		if (receiver <= 0) return null;
		for (var edge : before.topology().connections())
			if ((edge.firstNode() == source || edge.secondNode() == source
					|| edge.firstNode() == receiver || edge.secondNode() == receiver)
					&& !((edge.firstNode() == source && edge.secondNode() == receiver)
						|| (edge.firstNode() == receiver && edge.secondNode() == source))) return null;
		int destination = -1;
		for (int other = 0; other < before.liquidStock().size(); other++) {
			if (other == cell) continue;
			if (!before.liquidStock().get(other).equals(after.liquidStock().get(other))
					|| before.geometry().collisionMask(other) != after.geometry().collisionMask(other)
					|| !before.poreVolume(other).equals(after.poreVolume(other))) return null;
			for (int micro = 0; micro < 8; micro++) {
				int id = 8 * other + micro;
				if (!Objects.equals(before.geometry().piece(id), after.geometry().piece(id))) return null;
				if (before.nodeOfPiece(id) != receiver) continue;
				int next = after.nodeOfPiece(id);
				if (next <= 0 || destination > 0 && destination != next) return null;
				destination = next;
			}
		}
		if (destination <= 0) return null;
		for (var piece : after.geometry().pieces())
			if (after.nodeOfPiece(piece.id()) == destination && before.nodeOfPiece(piece.id()) != receiver) return null;
		return new Transfer(source, destination);
	}
}
