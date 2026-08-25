package com.example.fluid.air;

import com.example.chemistry.ExactRational;

import java.util.List;

/** Standalone M5 gas-network gate: isothermal state equation, finite-rate orifice equalization and the exact inventory ledger. */
public final class AirNetworkValidation {
	private AirNetworkValidation() { }

	public static void main(String[] args) {
		validateEquationOfState();
		validateOrificeEqualization();
		validateHoleOpenClose();
		validateSplitMergeVentLedger();
		validateAmbientInteraction();
		validateComponentTransport();
		validateClosedAuditHistoryCarry();
		validateGuards();
		System.out.println("M5 air network gate passed: isothermal EoS, finite-rate orifice equalization, "
				+ "hole open/close freeze and exact nanovolume ledger");
	}

	private static void validateEquationOfState() {
		AirNetwork network = new AirNetwork();
		int node = network.addNode(8.0, 8.0);
		check(network.gaugePressure(node) == 0.0, "ambient-balanced cavity must start at gauge zero");
		network.updateAvailableVolume(node, 4.0);
		checkRelative(network.gaugePressure(node), AirNetwork.REFERENCE_PRESSURE, "halved volume must double absolute pressure");
		network.updateAvailableVolume(node, 2.0);
		checkRelative(network.gaugePressure(node), 3.0 * AirNetwork.REFERENCE_PRESSURE, "quarter volume must quadruple absolute pressure");
		network.updateAvailableVolume(node, 16.0);
		checkRelative(network.gaugePressure(node), -0.5 * AirNetwork.REFERENCE_PRESSURE, "doubled volume must halve absolute pressure below ambient");

		int tiny = network.addNode(5.0E-5, 5.0E-5);
		check(network.gaugePressure(tiny) == 0.0, "sub-millilitre cavity must start at gauge zero");
		int tinyNano = network.addNode(1.0E-9, 1.0E-9);
		check(network.gaugePressure(tinyNano) == 0.0, "one-nanovolume cavity must start at gauge zero");
		int evacuated = network.addNode(1.0, 1.0);
		network.updateAvailableVolume(evacuated, 1.0E4);
		check(network.gaugePressure(evacuated) == AirNetwork.MIN_GAUGE_PRESSURE,
				"evacuated cavity must clamp at the near-vacuum floor");
		check(network.pressureFloorEvents() > 0, "floor clamp was not counted");

		int filled = network.addNode(1.0, 1.0);
		network.updateAvailableVolume(filled, 0.0);
		check(network.gaugePressure(filled) == AirNetwork.MAX_GAUGE_PRESSURE,
				"filled cavity must clamp at the pressure ceiling");
		check(network.pressureCeilingEvents() > 0, "ceiling clamp was not counted");
		checkLedger(network, "equation-of-state probes");
	}

	private static void validateOrificeEqualization() {
		AirNetwork network = new AirNetwork();
		int first = network.addNode(2.0, 3.0);
		int second = network.addNode(6.0, 4.5);
		network.addOrMergeEdge(first, second, 0.02, 0.65);
		double previousDelta = Math.abs(network.gaugePressure(first) - network.gaugePressure(second));
		int steps = 0;
		while (previousDelta > 1.0E-3 && steps < 100_000) {
			network.stepEdges(0.05);
			steps++;
			double delta = Math.abs(network.gaugePressure(first) - network.gaugePressure(second));
			check(Double.isFinite(delta) && delta <= previousDelta + 1.0E-9,
					"orifice equalization pressure delta must decay monotonically, step " + steps);
			checkLedger(network, "orifice equalization step " + steps);
			previousDelta = delta;
		}
		check(previousDelta <= 1.0E-3, "orifice equalization did not converge, residual delta " + previousDelta);
		double expectedFirst = 2.0 * 7.5 / 8.0;
		checkRelative(network.standardVolume(first), expectedFirst, "equalization split of the first cavity");
		checkRelative(network.standardVolume(second), 7.5 - expectedFirst, "equalization split of the second cavity");
		System.out.printf("  two-cavity equalization converged in %d steps to deltaP=%.3g Pa%n", steps, previousDelta);
	}

	private static void validateHoleOpenClose() {
		AirNetwork network = new AirNetwork();
		int first = network.addNode(2.0, 3.0);
		int second = network.addNode(6.0, 4.5);
		network.addOrMergeEdge(first, second, 0.005, 0.65);
		double initial = Math.abs(network.gaugePressure(first) - network.gaugePressure(second));
		for (int step = 0; step < 5; step++) network.stepEdges(0.05);
		double partial = Math.abs(network.gaugePressure(first) - network.gaugePressure(second));
		check(partial < initial, "open hole must partially equalize");
		check(network.removeEdge(first, second), "closing the hole must remove the edge");
		long frozenFirst = network.standardVolumeNanos(first);
		long frozenSecond = network.standardVolumeNanos(second);
		for (int step = 0; step < 200; step++) network.stepEdges(0.05);
		check(network.standardVolumeNanos(first) == frozenFirst && network.standardVolumeNanos(second) == frozenSecond,
				"a closed hole must freeze the inventories exactly");
		network.addOrMergeEdge(first, second, 0.005, 0.65);
		int steps = 0;
		double delta = partial;
		while (delta > 1.0E-3 && steps < 100_000) {
			network.stepEdges(0.05);
			steps++;
			delta = Math.abs(network.gaugePressure(first) - network.gaugePressure(second));
			checkLedger(network, "reopened hole step " + steps);
		}
		check(delta <= 1.0E-3, "reopened hole must resume and finish equalization, residual " + delta);
	}

	private static void validateSplitMergeVentLedger() {
		AirNetwork network = new AirNetwork();
		int source = network.addNode(10.0, 10.0);
		int[] children = network.splitNode(source, 2.0, 3.0, 5.0);
		check(children.length == 3 && children[0] == source, "split must keep the source id for the first child");
		check(network.nodeCount() == 3, "split must create the remaining children");
		check(network.standardVolumeNanos(children[0]) == 2_000_000_000L
				&& network.standardVolumeNanos(children[1]) == 3_000_000_000L
				&& network.standardVolumeNanos(children[2]) == 5_000_000_000L,
				"split shares must follow the available volumes exactly");
		checkLedger(network, "split");

		network.mergeNodes(children[1], children[2]);
		check(!network.hasNode(children[2]), "merged source node must disappear");
		check(network.standardVolumeNanos(children[1]) == 8_000_000_000L, "merge must sum inventories exactly");
		checkLedger(network, "merge");

		network.ventNode(children[1]);
		check(network.ventedNanos() == 8_000_000_000L, "vent must book the removed inventory");
		checkLedger(network, "vent");

		int remainder = children[0];
		network.setStandardVolume(remainder, 12.0);
		check(network.standardVolumeNanos(remainder) == 12_000_000_000L, "injection must set the inventory");
		checkLedger(network, "injection");

		int uneven = network.addNode(1.0, 1.0);
		int[] parts = network.splitNode(uneven, 1.0, 2.0);
		long sum = network.standardVolumeNanos(parts[0]) + network.standardVolumeNanos(parts[1]);
		check(sum == 1_000_000_000L, "uneven split remainder must keep the exact total, got " + sum);
		checkLedger(network, "uneven split");
	}

	private static void validateAmbientInteraction() {
		AirNetwork venting = new AirNetwork();
		int pressurized = venting.addNode(2.0, 4.0);
		venting.addOrMergeEdge(pressurized, AirNetwork.AMBIENT_NODE, 0.01, 0.65);
		double previous = venting.gaugePressure(pressurized);
		int steps = 0;
		while (previous > 1.0E-3 && steps < 100_000) {
			venting.stepEdges(0.05);
			steps++;
			double pressure = venting.gaugePressure(pressurized);
			check(Double.isFinite(pressure) && pressure <= previous + 1.0E-9,
					"venting to ambient must decay monotonically");
			checkLedger(venting, "ambient venting step " + steps);
			previous = pressure;
		}
		check(previous <= 1.0E-3, "venting to ambient did not reach gauge zero");
		check(venting.ventedNanos() == 2_000_000_000L, "vented ledger must equal the excess inventory");

		AirNetwork drawing = new AirNetwork();
		int evacuated = drawing.addNode(2.0, 1.0);
		drawing.addOrMergeEdge(evacuated, AirNetwork.AMBIENT_NODE, 0.01, 0.65);
		previous = drawing.gaugePressure(evacuated);
		steps = 0;
		while (previous < -1.0E-3 && steps < 100_000) {
			drawing.stepEdges(0.05);
			steps++;
			double pressure = drawing.gaugePressure(evacuated);
			check(Double.isFinite(pressure) && pressure >= previous - 1.0E-9,
					"drawing from ambient must raise the pressure monotonically");
			checkLedger(drawing, "ambient draw step " + steps);
			previous = pressure;
		}
		check(previous >= -1.0E-3, "drawing from ambient did not reach gauge zero");
		check(drawing.drawnFromAmbientNanos() == 2_000_000_000L,
				"drawn ledger must equal creation plus the drawn deficit");
		System.out.printf("  ambient venting and drawing both converged in <= %d-step budgets%n", steps);
	}

	private static void validateGuards() {
		AirNetwork network = new AirNetwork();
		int node = network.addNode(1.0, 1.0);
		expectThrow(() -> network.addOrMergeEdge(node, node, 0.1, 0.65), "self edge");
		expectThrow(() -> network.addOrMergeEdge(node, AirNetwork.AMBIENT_NODE, 0.0, 0.65), "zero area");
		expectThrow(() -> network.addOrMergeEdge(node, AirNetwork.AMBIENT_NODE, 0.1, 1.5), "oversized Cd");
		expectThrow(() -> network.gaugePressure(99), "unknown node pressure");
		expectThrow(() -> network.updateAvailableVolume(99, 1.0), "unknown node volume");
		expectThrow(() -> network.stepEdges(0.0), "zero dt");
		expectThrow(() -> network.updateAvailableVolume(node, -1.0), "negative available volume");

		network.addOrMergeEdge(node, AirNetwork.AMBIENT_NODE, 0.05, 0.65);
		network.addOrMergeEdge(AirNetwork.AMBIENT_NODE, node, 0.03, 0.65);
		check(network.edgeCount() == 1 && network.edges().get(0).areaSquareMeters() == 0.08,
				"parallel openings must merge into one edge with summed area");

		network.updateAvailableVolume(node, 0.5);
		network.stepEdges(1.0E9);
		check(network.standardVolumeNanos(node) >= 0L, "huge step must not drive the inventory negative");
		checkLedger(network, "guard probes");
	}

	private static void validateComponentTransport() {
		GasRegistry registry = new GasRegistry(List.of(
				new GasComponentDefinition("test:alpha", 1.0, 0.25,
						List.of(new GasComponentDefinition.DefaultSpecies("test:a", ExactRational.of(3_000_000_000L)))),
				new GasComponentDefinition("test:beta", 2.0, 0.5,
						List.of(new GasComponentDefinition.DefaultSpecies("test:b", ExactRational.of(6_000_000_000L))))),
				List.of(new GasRegistry.AmbientFraction("test:alpha", ExactRational.parse("1/3")),
						new GasRegistry.AmbientFraction("test:beta", ExactRational.parse("2/3"))));
		AirNetwork network = new AirNetwork(registry);
		int source = network.addNode(3.0, 3.0);
		int target = network.addNode(1.0, 1.0);
		GasInventory initial = network.inventory(source).merge(network.inventory(target));
		int[] split = network.splitNode(source, 1.0, 2.0);
		network.mergeNodes(target, split[1]);
		network.addOrMergeEdge(target, split[0], 0.01, 0.65);
		for (int step = 0; step < 20; step++) network.stepEdges(0.05);
		GasInventory after = network.inventory(target).merge(network.inventory(split[0]));
		check(after.standardVolumeNanos() == initial.standardVolumeNanos(),
				"component transport changed total standard volume");
		check(after.components().stream().map(GasComponentAmount::componentId).toList()
				.equals(List.of("test:alpha", "test:beta")), "component transport lost stable identities");
		check(network.auditClosed(), "component transport did not close mass/species audit");
	}

	private static void validateClosedAuditHistoryCarry() {
		AirNetwork network = new AirNetwork();
		network.addNode(2.0, 2.0);
		GasAuditLedger sourceBefore = network.externalSource();
		GasAuditLedger sinkBefore = network.escapedSink();
		GasAuditLedger turnover = GasAuditLedger.of(network.registry().createAmbient(4_005_762L));
		network.carryClosedAuditHistory(turnover);
		check(network.externalSource().equals(sourceBefore.add(turnover)),
				"carried closed history was not retained in the gas source ledger");
		check(network.escapedSink().equals(sinkBefore.add(turnover)),
				"carried closed history was not retained in the gas sink ledger");
		checkLedger(network, "closed audit history carry");
	}

	private static void checkLedger(AirNetwork network, String context) {
		long sealed = network.sealedInventoryNanos();
		if (sealed + network.ventedNanos() != network.drawnFromAmbientNanos()) {
			throw new AssertionError("gas ledger identity broken after " + context + ": sealed=" + sealed
					+ " vented=" + network.ventedNanos() + " drawn=" + network.drawnFromAmbientNanos());
		}
		if (!network.auditClosed()) throw new AssertionError("gas component audit broken after " + context);
	}

	private static void checkRelative(double actual, double expected, String what) {
		double error = Math.abs(actual - expected) / Math.abs(expected);
		if (error > 1.0E-9) throw new AssertionError(what + ": actual=" + actual + " expected=" + expected);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static void expectThrow(Runnable action, String what) {
		try {
			action.run();
		} catch (IllegalArgumentException expected) {
			return;
		}
		throw new AssertionError("expected an IllegalArgumentException for " + what);
	}
}
