package mmm.fluid;

import mmm.units.SignedDyadic;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/** Complete single-exit gas migration on the actual quantized liquid face plan. */
final class GasAdvectionSuccessor {
	private GasAdvectionSuccessor() { }
	static Map<Integer, Integer> certify(FluidDomain domain, GasRuntimeGeometry old, GasRuntimeGeometry next,
	                                   ConservativeLiquidFlux flux, CellRemapPorts ports, float dt) {
		var result = new TreeMap<Integer, Integer>(); var flow = flux.affineFaceFlow(domain, dt);
		if (flow == null || !(dt > 0) || flux.flux.length != 2 * flux.physicalFaceCount) return result;
		int[] cells = new int[old.nodeCount() + 1]; Arrays.fill(cells, -1);
		for (var piece : old.capacity().pieces()) {
			int node = old.nodeOfPiece(piece.id()), cell = piece.id() / 8;
			if (node > 0) cells[node] = cells[node] == -1 || cells[node] == cell ? cell : -2;
		}
		for (int node = 1; node < cells.length; node++) {
			int cell = cells[node]; if (cell < 0 || old.commonCellNode(cell) != node || next.commonCellNode(cell) >= 0) continue;
			int destination = destination(domain, old, next, flux, ports, flow, cell, dt / domain.config.cellSize());
			if (destination > 0) result.put(node, destination);
		}
		return result;
	}
	private static int destination(FluidDomain d, GasRuntimeGeometry old, GasRuntimeGeometry next,
	                               ConservativeLiquidFlux flux, CellRemapPorts ports, LiquidAffineFaceFlow flow, int cell, double dt) {
		var pore = old.poreVolume(cell);
		if (old.geometry().collisionMask(cell) != 0 || next.geometry().collisionMask(cell) != 0
				|| !pore.equals(next.poreVolume(cell)) || !next.liquidStock().get(cell).volume().equals(pore)
				|| old.liquidStock().get(cell).components().size() != 1
				|| old.liquidStock().get(cell).components().getFirst().supportMask() != 255 || !samePlane(d, old, cell)) return -1;
		var gas = pore.subtract(old.liquidStock().get(cell).volume());
		int exit = -1, receiver = -1, movingAxis = -1, velocityAxis = -1; boolean positive = false; SignedDyadic width = null;
		for (int axis = 0; axis < 3; axis++) {
			int minus = face(d, cell, axis, false), plus = face(d, cell, axis, true);
			if (minus < 0 || plus < 0) return -1;
			double u = flow.constantTrace(minus, cell);
			if (!Double.isFinite(u) || u != flow.constantTrace(plus, cell)) return -1;
			if (u != 0) { if (velocityAxis >= 0) return -1; velocityAxis = axis; }
			for (int f : new int[]{minus, plus}) {
				int peer = flux.left[f] == cell ? flux.right[f] : flux.left[f];
				if (flow.constantTrace(f, peer) != u || old.geometry().collisionMask(peer) != 0
						|| next.geometry().collisionMask(peer) != 0 || !pore.equals(old.poreVolume(peer))
						|| !pore.equals(next.poreVolume(peer)) || !samePlane(d, old, peer)) return -1;
				int channel = f + (u >= 0 ? 0 : flux.physicalFaceCount), reverse = f + (u >= 0 ? flux.physicalFaceCount : 0);
				var span = SignedDyadic.of(Math.abs(u)).multiply(SignedDyadic.of(dt));
				// The common open-trace floor loss cancels across this cell; the wet inlet carries no old gas.
				var open = floor(span.multiply(pore));
				if (!open.equals(flux.openTransferBound(channel)) || flux.exactFlux[reverse].signum() != 0) return -1;
				if (ports != null && (!portVolume(ports, flux, channel).equals(flux.exactFlux[channel])
						|| !portVolume(ports, flux, reverse).equals(flux.exactFlux[reverse]))) return -1;
				var dry = open.subtract(flux.exactFlux[channel]);
				if (dry.signum() < 0) return -1;
				if (dry.signum() > 0) {
					if (exit >= 0 || flux.donor(channel) != cell || !dry.equals(gas)) return -1;
					exit = f; receiver = peer; movingAxis = axis; positive = u > 0; width = span;
				}
			}
		}
		if (exit < 0 || width.compareTo(SignedDyadic.of(1)) > 0 || next.commonCellNode(receiver) <= 0
				|| !old.liquidStock().get(receiver).volume().equals(pore)) return -1;
		// The inverse offset's lower endpoint encloses every assigned old dry capacity.
		var bracket = old.offsetBracket(cell, 0); if (bracket == null) return -1;
		if (!bracket.targetVolume().equals(PlicComponentGeometry.ratio(old.liquidStock().get(cell).volume(), pore))) return -1;
		var upper = new GasGeometryOverlap.HalfSpace(SignedDyadic.of(old.planeNormal(cell, 0, 0)),
				SignedDyadic.of(old.planeNormal(cell, 0, 1)), SignedDyadic.of(old.planeNormal(cell, 0, 2)), SignedDyadic.of(bracket.lower()));
		SignedDyadic[] normal = {SignedDyadic.ZERO, SignedDyadic.ZERO, SignedDyadic.ZERO};
		normal[movingAxis] = SignedDyadic.of(positive ? -1 : 1);
		var outside = new GasGeometryOverlap.HalfSpace(normal[0], normal[1], normal[2], positive ? width.subtract(SignedDyadic.of(1)) : width);
		boolean port = false;
		for (int micro = 0; micro < 8; micro++) {
			if (GasGeometryOverlap.hasPositiveOverlap(micro, upper, outside)) return -1;
			if (((micro >>> movingAxis & 1) != 0) == positive
					&& old.liquidFractionExact(cell, micro) < 1 && next.liquidFractionExact(receiver, micro ^ 1 << movingAxis) < 1)
				port |= GasFaceIntersection.area(.5 * (micro >>> ((movingAxis + 1) % 3) & 1),
						.5 * (micro >>> ((movingAxis + 2) % 3) & 1), .5, java.util.List.of(
						facePlane(old, cell, micro, movingAxis, positive ? 1 : 0),
						facePlane(next, receiver, micro ^ 1 << movingAxis, movingAxis, positive ? 0 : 1))) > 0;
		}
		return port ? next.commonCellNode(receiver) : -1;
	}
	private static SignedDyadic portVolume(CellRemapPorts ports, ConservativeLiquidFlux flux, int face) {
		var total = SignedDyadic.ZERO;
		for (var port : ports.outgoing(flux.donor(face), 255)) if (port.face == face) total = total.add(port.volume);
		return total;
	}
	private static GasFaceIntersection.Plane facePlane(GasRuntimeGeometry frame, int cell, int micro, int axis, int coordinate) {
		return new GasFaceIntersection.Plane(SignedDyadic.of(frame.planeNormal(cell, micro, (axis + 1) % 3)),
				SignedDyadic.of(frame.planeNormal(cell, micro, (axis + 2) % 3)), SignedDyadic.of(frame.planeOffset(cell, micro))
				.subtract(SignedDyadic.of(frame.planeNormal(cell, micro, axis)).multiply(SignedDyadic.of(coordinate))));
	}
	private static boolean samePlane(FluidDomain d, GasRuntimeGeometry old, int cell) {
		var phase = d.pressurePhaseGeometry;
		for (int micro = 0; micro < 8; micro++) {
			if (phase.liquidFractionExact(cell, micro) != old.liquidFractionExact(cell, micro)
					|| phase.planeOffset(cell, micro) != old.planeOffset(cell, micro)) return false;
			for (int a = 0; a < 3; a++) if (phase.planeNormal(cell, micro, a) != old.planeNormal(cell, micro, a)) return false;
		}
		return true;
	}
	private static SignedDyadic floor(SignedDyadic value) {
		int unit = SignedDyadic.of(ConservativeMarkerSampling.VOLUME_FLUX_RESOLUTION).exponent(), shift = value.exponent() - unit;
		return new SignedDyadic(shift < 0 ? value.significand().shiftRight(-shift) : value.significand().shiftLeft(shift), unit);
	}
	private static int face(FluidDomain d, int cell, int axis, boolean positive) {
		int x = cell % d.sizeX, y = cell / d.sizeX % d.sizeY, z = cell / (d.sizeX * d.sizeY);
		int coordinate = (axis == 0 ? x : axis == 1 ? y : z) + (positive ? 1 : 0);
		if (coordinate == 0 || coordinate == (axis == 0 ? d.sizeX : axis == 1 ? d.sizeY : d.sizeZ)) return -1;
		int nx = (d.sizeX - 1) * d.sizeY * d.sizeZ, ny = d.sizeX * (d.sizeY - 1) * d.sizeZ;
		return axis == 0 ? (z * d.sizeY + y) * (d.sizeX - 1) + coordinate - 1
				: axis == 1 ? nx + (z * (d.sizeY - 1) + coordinate - 1) * d.sizeX + x
				: nx + ny + ((coordinate - 1) * d.sizeY + y) * d.sizeX + x;
	}
}
