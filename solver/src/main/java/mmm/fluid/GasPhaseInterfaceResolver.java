package mmm.fluid;

import mmm.chemistry.ExactRational;
import mmm.thermal.GasLiquidPhaseModel;
import mmm.thermal.GasThermalModel;
import mmm.thermal.ThermalPhaseTransition;
import mmm.thermal.ThermalStateFunction;
import mmm.units.SignedDyadic;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;

/** Resolves positive PLIC contacts within each fixed liquid component, retaining their actual gas pieces. */
final class GasPhaseInterfaceResolver {
	private static final ExactRational MICRO_VOLUME = ExactRational.ONE.divide(8);
	private final PhaseChangeStageSupport support;

	GasPhaseInterfaceResolver(PhaseChangeStageSupport support) { this.support = support; }

	List<Candidate> resolve(int cell, ThermalPhaseTransition transition, GasLiquidPhaseModel phase,
	                        GasDomainState gasState, double liquidTemperature) {
		if (gasState.frame == null) return List.of();
		var result = new ArrayList<Candidate>();
		for (Contact contact : contacts(gasState.frame, cell)) {
			if (contact.node() == 0) continue;
			double pressure = support.thermal().requireGasNodeState(contact.node(),
					gasState.network.inventory(contact.node()), support.thermal().gasNodeEnergy(contact.node(), gasState.network), gasState.network);
			result.add(new Candidate(contact.node(), contact.supportMask(), contact.pieces(),
					phase.saturation(transition, liquidTemperature, gasState.network.inventory(contact.node()), pressure)));
		}
		result.sort((left, right) -> {
			int imbalance = Double.compare(Math.abs(right.saturation().pressureImbalancePa()), Math.abs(left.saturation().pressureImbalancePa()));
			if (imbalance != 0) return imbalance;
			int node = Integer.compare(left.node(), right.node());
			return node != 0 ? node : Integer.compare(left.supportMask(), right.supportMask());
		});
		return List.copyOf(result);
	}

	/** Complete depletion follows the old contact's gas pieces even when the future liquid interface vanishes. */
	boolean allowsAfter(int cell, Candidate source, ThermalPhaseTransition transition, GasLiquidPhaseModel phase,
	                    double liquidTemperature, GasTopologyCoordinator.GasTopologyPlan topology,
	                    GasLiquidPhaseModel.Direction direction, boolean sourceGasEmpty) {
		var future = topology.frame();
		boolean allVapor = direction == GasLiquidPhaseModel.Direction.BOIL && Double.isNaN(liquidTemperature);
		if (!Double.isFinite(liquidTemperature) && !allVapor) return false;
		var nodes = new TreeSet<Integer>();
		for (int piece : source.contactPieces()) {
			var before = topology.source().geometry().piece(piece);
			var after = future.geometry().piece(piece);
			if (before == null || after == null || !positiveOverlap(before, after.dryPlane(), true)) continue;
			int node = future.nodeOfPiece(piece);
			if (node >= 0) nodes.add(node);
		}
		if (nodes.isEmpty()) return direction == GasLiquidPhaseModel.Direction.CONDENSE && sourceGasEmpty;
		GasThermalModel thermal = new GasThermalModel(support.thermal().registry(), support.gasState().network.registry());
		try {
			for (int node : nodes) {
				GasLiquidPhaseModel.Saturation saturation;
				if (node == 0) saturation = phase.ambientSaturation(transition,
						allVapor ? mmm.fluid.air.GasRegistry.STANDARD_TEMPERATURE_K : liquidTemperature);
				else {
					var inventory = topology.gasPlan().inventory(node);
					double energy = topology.thermalPlan().nodeEnergy(node);
					double pressure = thermal.absolutePressure(inventory, topology.gasPlan().availableVolume(node), energy);
					double temperature = allVapor ? inventory.isEmpty()
							? mmm.fluid.air.GasRegistry.STANDARD_TEMPERATURE_K : thermal.state(inventory, energy).temperatureK()
							: liquidTemperature;
					saturation = phase.saturation(transition, temperature, inventory, pressure);
				}
				if (saturation.direction() != direction && saturation.direction() != GasLiquidPhaseModel.Direction.EQUILIBRIUM) return false;
			}
			return true;
		} catch (ThermalStateFunction.EnergyOutsideValidityException outsideThermalRange) {
			throw outsideThermalRange;
		} catch (IllegalArgumentException outsideGasPhaseRange) {
			throw new PhaseChangeStageSupport.PhaseCandidateUnavailableException(
					"Future gas interface is outside its pressure or saturation validity range", outsideGasPhaseRange);
		}
	}

	boolean mayKeepBoilContact(GasRuntimeGeometry.BoilContactPlanes prepared, PhaseLiquidGeometryEdit edit,
	                          Candidate source) {
		if (prepared == null || !prepared.matchesContact(source) || prepared.unchangedContact) return true;
		try {
			var phase = prepared.rebuild(edit);
			for (int piece : source.contactPieces()) {
				var before = prepared.source.geometry().piece(piece);
				if (before == null) continue;
				int cell = piece / 8, micro = piece & 7;
				double fraction = phase.liquidFractionExact(cell, micro);
				if (fraction >= 1) continue;
				var plane = fraction == 0 ? null : new GasGeometryOverlap.HalfSpace(
						SignedDyadic.of(phase.planeNormal(cell, micro, 0)), SignedDyadic.of(phase.planeNormal(cell, micro, 1)),
						SignedDyadic.of(phase.planeNormal(cell, micro, 2)), SignedDyadic.of(phase.planeOffset(cell, micro)));
				if (positiveOverlap(before, plane, false)) return true;
			}
			return false;
		} catch (IllegalArgumentException | ArithmeticException | GasTopologyBlockedException | MechanicalTimeRefinement unresolved) {
			return true;
		}
	}

	private static boolean positiveOverlap(GasComplementGeometry.Piece before, GasGeometryOverlap.HalfSpace after, boolean positiveAfter) {
		if (after == null || after.equals(before.dryPlane())) return true;
		if (before.dryPlane() == null) return positiveAfter || MicroBoxPlaneVolume.dryVolume(before.micro(), after).signum() > 0;
		return GasGeometryOverlap.normalizedVolume(before.micro(), List.of(before.dryPlane(), after)).signum() > 0;
	}

	record Contact(int node, int supportMask, List<Integer> pieces) { }

	static List<Contact> contacts(GasRuntimeGeometry frame, int cell) {
		var found = new TreeMap<Long, TreeSet<Integer>>();
		GasComplementGeometry geometry = frame.geometry();
		int[] size = {geometry.sizeX(), geometry.sizeY(), geometry.sizeZ()}, stride = {1, size[0], size[0] * size[1]};
		int[] coordinate = {cell % size[0], cell / size[0] % size[1], cell / (size[0] * size[1])};
		for (var component : frame.liquidStock().get(cell).components()) {
			if (component.volume().signum() == 0) continue;
			int supportMask = component.supportMask();
			for (int micro = 0; micro < 8; micro++) if ((supportMask & 1 << micro) != 0) {
				var same = geometry.piece(8 * cell + micro);
				if (same != null && same.normalizedVolume().compareTo(MICRO_VOLUME) < 0)
					add(found, frame, supportMask, same.id());
				if (!(frame.liquidFractionExact(cell, micro) > 0)) continue;
				for (int axis = 0; axis < 3; axis++) for (int sign : new int[]{-1, 1}) {
					int bit = micro >>> axis & 1;
					boolean crossing = bit == (sign > 0 ? 1 : 0);
					int peerCell = crossing ? cell + sign * stride[axis] : cell;
					if (crossing && (coordinate[axis] + sign < 0 || coordinate[axis] + sign >= size[axis])) continue;
					int peerMicro = micro ^ 1 << axis;
					var gas = geometry.piece(8 * peerCell + peerMicro);
					if (gas == null) continue;
					double ownFace = crossing ? (sign > 0 ? 1 : 0) : .5, peerFace = crossing ? (sign > 0 ? 0 : 1) : .5;
					double tangent = .5 * (micro >>> ((axis + 1) % 3) & 1), other = .5 * (micro >>> ((axis + 2) % 3) & 1);
					var planes = new ArrayList<GasFaceIntersection.Plane>(2);
					if (frame.liquidFractionExact(cell, micro) < 1) planes.add(facePlane(frame, cell, micro, axis, ownFace, true));
					if (gas.dryPlane() != null) planes.add(facePlane(frame, peerCell, peerMicro, axis, peerFace, false));
					if (GasFaceIntersection.area(tangent, other, .5, planes) > 0) add(found, frame, supportMask, gas.id());
				}
			}
		}
		var result = new ArrayList<Contact>(found.size());
		for (var entry : found.entrySet()) result.add(new Contact((int) (entry.getKey() >>> 8), (int) (entry.getKey() & 255), List.copyOf(entry.getValue())));
		return List.copyOf(result);
	}

	private static GasFaceIntersection.Plane facePlane(GasRuntimeGeometry frame, int cell, int micro, int axis, double coordinate, boolean wet) {
		SignedDyadic a = SignedDyadic.of(frame.planeNormal(cell, micro, (axis + 1) % 3));
		SignedDyadic b = SignedDyadic.of(frame.planeNormal(cell, micro, (axis + 2) % 3));
		SignedDyadic threshold = SignedDyadic.of(frame.planeOffset(cell, micro))
				.subtract(SignedDyadic.of(frame.planeNormal(cell, micro, axis)).multiply(SignedDyadic.of(coordinate)));
		return new GasFaceIntersection.Plane(wet ? a.negate() : a, wet ? b.negate() : b, wet ? threshold.negate() : threshold);
	}

	private static void add(TreeMap<Long, TreeSet<Integer>> found, GasRuntimeGeometry frame, int supportMask, int piece) {
		int node = frame.nodeOfPiece(piece);
		if (node >= 0) found.computeIfAbsent((long) node << 8 | supportMask, ignored -> new TreeSet<>()).add(piece);
	}

	record Candidate(int node, int supportMask, List<Integer> contactPieces, GasLiquidPhaseModel.Saturation saturation) {
		Candidate { contactPieces = List.copyOf(contactPieces); }
	}
}
