package cc.ecisr.mmm.fluid.air;

import cc.ecisr.mmm.chemistry.ExactRational;

import java.util.List;

/** Standalone M5 gas-network gate: isothermal state equation, finite-rate orifice equalization and the exact inventory ledger. */
public final class AirNetworkValidation {
	private AirNetworkValidation() { }

	public static void main(String[] args) {
		validateRegistrySnapshot();
		validateEquationOfState();
		validateVacuumNode();
		validateOrificeEqualization();
		validateFlowActivity();
		validateHoleOpenClose();
		validateSplitMergeVentLedger();
		validateAmbientInteraction();
		validateComponentTransport();
		validateComponentSpeciesBoundary();
		validatePhaseColumnTake();
		validateGuards();
		System.out.println("M5 air network gate passed: isothermal EoS, finite-rate orifice equalization, "
				+ "hole open/close freeze and exact nanovolume ledger");
	}

	private static void validateRegistrySnapshot() {
		java.nio.file.Path root = null;
		try {
			root = java.nio.file.Files.createTempDirectory("mmm-gas-registry-");
			GasRegistry definitions = BuiltinGasDefinitions.createRegistry();
			java.nio.file.Path snapshot = root.resolve("gas-registry.nbt");
			GasRegistryCodec.writeSnapshot(snapshot, definitions);
			GasRegistry decoded = GasRegistryCodec.readSnapshot(snapshot);
			check(decoded.fingerprint().equals(definitions.fingerprint())
							&& java.util.Arrays.equals(decoded.canonicalBytes(), definitions.canonicalBytes()),
					"gas registry snapshot did not round-trip canonically");
			var registryTag = net.minecraft.nbt.NbtIo.readCompressed(snapshot,
					net.minecraft.nbt.NbtAccounter.create(4L * 1024L * 1024L));
			registryTag.putString("UnknownField", "must-lock");
			net.minecraft.nbt.NbtIo.writeCompressed(registryTag, snapshot);
			try {
				GasRegistryCodec.readSnapshot(snapshot);
				throw new AssertionError("unknown gas registry field was ignored");
			} catch (java.io.IOException expected) { }
		} catch (java.io.IOException failure) {
			throw new AssertionError("gas registry codec validation failed", failure);
		} finally {
			if (root != null) try (var paths = java.nio.file.Files.walk(root)) {
				for (java.nio.file.Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
					java.nio.file.Files.deleteIfExists(path);
				}
			} catch (java.io.IOException cleanup) {
				throw new AssertionError("gas registry codec cleanup failed", cleanup);
			}
		}
	}

	private static void validateFlowActivity() {
		AirNetwork network = new AirNetwork();
		int first = network.addNode(1.0, 1.0);
		int second = network.addNode(1.0, 1.0);
		check(!network.hasPressureDrivenFlow(network::gaugePressure), "isolated nodes demand transport");
		network.addOrMergeEdge(first, second, 1.0e-12, 0.65);
		check(!network.hasPressureDrivenFlow(network::gaugePressure), "equal-pressure edge demands transport");
		long revision = network.revision();
		check(network.hasPressureDrivenFlow(node -> node == first ? 1.0e-12 : 0.0),
				"thermal pressure or a very slow pore flow was ignored by sleep observation");
		check(network.revision() == revision, "activity observation changed the gas inventory");
		network.updateAvailableVolume(first, 0.5);
		check(network.hasPressureDrivenFlow(network::gaugePressure), "compressed gas edge allowed sleep");
		network.removeEdge(first, second);
		check(!network.hasPressureDrivenFlow(network::gaugePressure), "disconnected pressure difference prevents sleep");
		network.addOrMergeEdge(first, AirNetwork.AMBIENT_NODE, 0.01, 0.65);
		check(network.hasPressureDrivenFlow(network::gaugePressure), "ambient edge flow allowed sleep");
	}

	private static void validateEquationOfState() {
		AirNetwork network = new AirNetwork();
		int node = network.addNode(8.0, 8.0);
		check(network.gaugePressure(node) == 0.0 && network.density(node) == AirNetwork.AIR_DENSITY,
				"ambient-balanced cavity must start at the registry standard state");
		network.updateAvailableVolume(node, 4.0);
		checkRelative(network.gaugePressure(node), AirNetwork.REFERENCE_PRESSURE, "halved volume must double absolute pressure");
		checkRelative(network.density(node), 2.0 * AirNetwork.AIR_DENSITY,
				"halved volume must double density from the same inventory");
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
		double nearVacuum = AirNetwork.REFERENCE_PRESSURE * (1.0E-4 - 1.0);
		checkRelative(network.gaugePressure(evacuated), nearVacuum,
				"evacuated cavity must approach exact vacuum pressure without a floor");
		checkRelative(network.observedGaugePressure(evacuated), nearVacuum,
				"read-only pressure observation changed the equation of state");

		int filled = network.addNode(1.0, 1.0);
		network.updateAvailableVolume(filled, 1.0E-3);
		checkRelative(network.gaugePressure(filled), 999.0 * AirNetwork.REFERENCE_PRESSURE,
				"high-pressure cavity must retain its equation-of-state pressure without a ceiling");
		network.updateAvailableVolume(filled, 0.0);
		expectStateThrow(() -> network.gaugePressure(filled),
				"finite inventory in zero available volume");
		checkLedger(network, "equation-of-state probes");
	}

	private static void validateVacuumNode() {
		AirNetwork network = new AirNetwork();
		network.addNode(1.0, 1.0);
		GasInventory inventory = network.inventory(1);
		network.remapNodes(new double[]{1.0, 1.0},
				List.of(inventory, GasInventory.EMPTY), GasInventory.EMPTY, GasInventory.EMPTY);
		check(network.gaugePressure(2) == -AirNetwork.REFERENCE_PRESSURE,
				"empty sealed cavity must derive exact vacuum pressure");
		check(network.density(2) == 0.0, "empty sealed cavity acquired isothermal density");
		check(network.observedGaugePressure(2) == -AirNetwork.REFERENCE_PRESSURE,
				"read-only empty-cavity pressure changed the exact vacuum convention");
		new cc.ecisr.mmm.fluid.CellCarrierInventoryView.GasAmount(false, 2, 0.0,
				GasRegistry.STANDARD_TEMPERATURE_K, List.of());
		check(network.standardVolumeNanos(2) == 0L && network.auditClosed(),
				"vacuum node created gas inventory or opened the ledger");
		GasStateSnapshot snapshot = GasStateSnapshot.capture(
				2, 1, 1, new int[]{1, 2}, new int[]{1, 2}, network, "");
		AirNetwork restored = new AirNetwork(network.registry());
		restored.restoreNodes(new double[]{1.0, 1.0},
				snapshot.nodes().stream().map(GasStateSnapshot.Node::inventory).toList(),
				snapshot.externalSource(), snapshot.escapedSink(),
				snapshot.massRoundoff(), snapshot.residualRoundoff());
		check(restored.inventory(2).isEmpty() && restored.auditClosed(),
				"vacuum node did not survive complete gas handoff");
		restored.addOrMergeEdge(AirNetwork.AMBIENT_NODE, 2, 0.01, 0.65);
		for (int step = 0; step < 20 && restored.gaugePressure(2) < -1.0E-3; step++) {
			restored.stepEdges(0.05);
		}
		check(Math.abs(restored.gaugePressure(2)) <= 1.0E-3
				&& restored.standardVolumeNanos(2) == 1_000_000_000L,
				"ambient orifice did not fill a vacuum node to equilibrium");
		checkLedger(restored, "vacuum fill");
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
		AirNetwork.RemapPlan staleRemap = network.planRemapNodes(
				new double[]{1.0}, List.of(network.inventory(node)),
				GasInventory.EMPTY, GasInventory.EMPTY, List.of());
		network.setStandardVolume(node, 1.25);
		long changedInventory = network.standardVolumeNanos(node);
		expectStateThrow(() -> network.commitRemapNodes(staleRemap), "stale remap generation");
		check(network.standardVolumeNanos(node) == changedInventory,
				"stale gas remap overwrote newer inventory truth");
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
						List.of("test:a"),
						List.of(new GasComponentDefinition.DefaultSpecies("test:a", ExactRational.of(3_000_000_000L)))),
				new GasComponentDefinition("test:beta", 2.0, 0.5,
						List.of("test:b"),
						List.of(new GasComponentDefinition.DefaultSpecies("test:b", ExactRational.of(6_000_000_000L))))),
				List.of(new GasRegistry.AmbientFraction("test:alpha", ExactRational.parse("1/3")),
						new GasRegistry.AmbientFraction("test:beta", ExactRational.parse("2/3"))));
		AirNetwork network = new AirNetwork(registry);
		int source = network.addNode(3.0, 3.0);
		int target = network.addNode(1.0, 1.0);
		checkRelative(network.density(AirNetwork.AMBIENT_NODE), 5.0 / 3.0,
				"ambient mixture density must come from registry standard-state fractions");
		checkRelative(network.density(source), network.density(AirNetwork.AMBIENT_NODE),
				"balanced mixed cavity must inherit the registry ambient density");
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

	private static void validateComponentSpeciesBoundary() {
		expectThrow(() -> new GasComponentDefinition("test:invalid", 1.0, 1.0, List.of(),
				List.of(new GasComponentDefinition.DefaultSpecies("test:a", ExactRational.ONE))),
				"default species outside component allow-list");
		GasRegistry registry = new GasRegistry(List.of(
				new GasComponentDefinition("test:alpha", 1.0, 1.0, List.of("test:a"), List.of()),
				new GasComponentDefinition("test:beta", 1.0, 1.0, List.of("test:b"), List.of())),
				List.of(new GasRegistry.AmbientFraction("test:alpha", ExactRational.ONE)));
		AirNetwork network = new AirNetwork(registry);
		int node = network.addNode(1.0, 1.0);
		GasInventory wrongComponent = new GasInventory(List.of(new GasComponentAmount(
				"test:alpha", 1L, 1.0E-9,
				List.of(new GasComponentAmount.SpeciesAmount("test:b", 1L)), 0.0)));
		long before = network.standardVolumeNanos(node);
		expectThrow(() -> network.commitPhaseSource(node, wrongComponent), "species attached to wrong component");
		check(network.standardVolumeNanos(node) == before && network.auditClosed(),
				"rejected gas species source changed inventory or audit state");
	}

	private static void validatePhaseColumnTake() {
		AirNetwork network = new AirNetwork();
		int node = network.addNode(2.0, 2.0);
		GasInventory groupedVapor = new GasInventory(List.of(new GasComponentAmount(
				BuiltinGasDefinitions.WATER_VAPOR, 4_005_761L, 0.003, List.of(), 0.003)));
		network.commitPhaseSource(node, groupedVapor);
		GasInventory.Split take = network.previewComponentTake(node, BuiltinGasDefinitions.WATER_VAPOR,
				2_670_508L, 0.002, 0.002);
		GasComponentAmount moved = take.first().components().getFirst();
		GasComponentAmount remainder = take.second().component(BuiltinGasDefinitions.WATER_VAPOR);
		check(moved.standardVolumeNanos() == 2_670_508L && moved.mass() == 0.002
				&& moved.massResidual() == 0.002,
				"phase take did not preserve its explicit nanos/mass/residual quantum");
		check(remainder.standardVolumeNanos() == 1_335_253L
				&& close(remainder.mass(), 0.001) && close(remainder.massResidual(), 0.001),
				"phase take did not retain grouped-rounding remainder columns");
		network.commitPhaseSink(node, take.first());
		checkLedger(network, "phase column take");
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

	private static boolean close(double actual, double expected) {
		return Math.abs(actual - expected) <= 1.0E-12;
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

	private static void expectStateThrow(Runnable action, String what) {
		try {
			action.run();
		} catch (IllegalStateException expected) {
			return;
		}
		throw new AssertionError("expected an IllegalStateException for " + what);
	}
}
