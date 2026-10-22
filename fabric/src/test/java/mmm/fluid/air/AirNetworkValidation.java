package mmm.fluid.air;

import mmm.chemistry.ExactRational;
import mmm.units.SignedDyadic;

import java.math.BigInteger;
import java.util.List;
import java.util.TreeMap;

/** Standalone M5 gas-network gate: isothermal state equation, finite-rate orifice equalization and the exact inventory ledger. */
public final class AirNetworkValidation {
	private AirNetworkValidation() { }

	public static void main(String[] args) {
		validateRegistrySnapshot();
		validateEquationOfState();
		validateVacuumNode();
		validatePieceSnapshot();
		validateOrificeEqualization();
		validateFlowActivity();
		validateHoleOpenClose();
		validateSplitMergeVentLedger();
		validateAmbientInteraction();
		validateComponentTransport();
		validateAirRecipeAndTraceTransport();
		validateExactTopologyPartition();
		validateSplitPreflightsAllOwners();
		validatePublicMutatorsPreflightAllOwners();
		validatePhaseSuccessorAndParallelEdges();
		validateComponentSpeciesBoundary();
		validatePhaseColumnTake();
		validateRegionalComponentAggregation();
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
		check(!network.hasPressureDrivenFlow(node -> node == first ? 1.0e-12 : 0.0),
				"unrepresentable equilibrium remainder kept an idle network awake");
		check(network.hasPressureDrivenFlow(node -> node == first ? 100.0 : 0.0),
				"thermal pressure or a very slow pore flow was ignored by sleep observation");
		check(network.revision() == revision, "activity observation changed the gas inventory");
		network.updateAvailableVolume(first, 0.5);
		check(network.hasPressureDrivenFlow(network::gaugePressure), "compressed gas edge allowed sleep");
		long beforeSlowStep = network.revision();
		network.stepEdges(0.05);
		check(network.revision() == beforeSlowStep && network.hasPressureDrivenFlow(network::gaugePressure),
				"zero rounded transfer through a tiny opening was mistaken for equilibrium");
		network.removeEdge(first, second);
		check(!network.hasPressureDrivenFlow(network::gaugePressure), "disconnected pressure difference prevents sleep");
		network.addOrMergeEdge(first, AirNetwork.AMBIENT_NODE, 0.01, 0.65);
		check(network.hasPressureDrivenFlow(network::gaugePressure), "ambient edge flow allowed sleep");
	}

	private static void validateEquationOfState() {
		AirNetwork network = new AirNetwork();
		int node = network.addNode(8.0, 8.0);
		double initialDensity = network.density(node);
		check(network.gaugePressure(node) == 0.0 && network.density(node) == network.registry().createAmbient(8_000_000_000L).totalMass() / 8,
				"ambient-balanced cavity must start at the registry standard state");
		network.updateAvailableVolume(node, 4.0);
		checkRelative(network.gaugePressure(node), AirNetwork.REFERENCE_PRESSURE, "halved volume must double absolute pressure");
		checkRelative(network.density(node), 2.0 * initialDensity,
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
		new mmm.fluid.CellCarrierInventoryView.GasAmount(false, 2, 0.0,
				GasRegistry.STANDARD_TEMPERATURE_K, List.of());
		check(network.standardVolumeNanos(2) == 0L && network.auditClosed(),
				"vacuum node created gas inventory or opened the ledger");
		int[] pieces = new int[16]; java.util.Arrays.fill(pieces, -1); pieces[0] = 1; pieces[8] = 2;
		GasStateSnapshot snapshot = GasStateSnapshot.capture(2, 1, 1, pieces, network, "");
		AirNetwork restored = new AirNetwork(network.registry());
		restored.restoreNodes(new double[]{1.0, 1.0},
				snapshot.nodes().stream().map(GasStateSnapshot.Node::inventory).toList(),
				snapshot.externalSource(), snapshot.escapedSink());
		check(restored.inventory(2).isEmpty() && restored.auditClosed(),
				"vacuum node did not survive complete gas handoff");
		restored.addOrMergeEdge(AirNetwork.AMBIENT_NODE, 2, 0.01, 0.65);
		double previous=restored.gaugePressure(2);
		for(int step=0;step<20;step++){
			restored.stepEdges(.05);
			double pressure=restored.gaugePressure(2);
			check(pressure>=previous&&pressure<=0,"Vacuum fill overshot or reversed its pressure response");
			checkLedger(restored,"vacuum fill step "+step);previous=pressure;
		}
		// Frozen-coefficient backward Euler leaves a first-order transient after one second.
		// Judge the physical pressure response separately from exact inventory accounting.
		check(Math.abs(previous)<=1e-3*AirNetwork.REFERENCE_PRESSURE,
				"Ambient orifice left a macroscopically unresolved vacuum after one second: "+previous);
		long deficit=1_000_000_000L-restored.standardVolumeNanos(2);
		check(deficit>=0&&deficit<=1_000_000L,"Vacuum fill inventory disagrees with its bounded pressure response");
		// At an integer endpoint one gas volume quantum is the permitted stationary remainder.
		AirNetwork endpoint=new AirNetwork();int terminal=endpoint.addNode(1,1);
		endpoint.updateAvailableVolume(terminal,1.000000001);
		endpoint.addOrMergeEdge(0,terminal,.01,.65);
		long before=endpoint.standardVolumeNanos(terminal);endpoint.stepEdges(.05);
		check(endpoint.standardVolumeNanos(terminal)>=before
				&&Math.abs(endpoint.gaugePressure(terminal))<=2*AirNetwork.REFERENCE_PRESSURE*AirNode.NANO_VOLUME,
				"Integer equilibrium remainder grew or overshot its pressure quantum");
		checkLedger(endpoint,"integer ambient endpoint");
		checkLedger(restored, "vacuum fill");
	}

	public static void validatePieceSnapshot() {
		AirNetwork network = new AirNetwork(); network.addNode(1, 1); network.addNode(2, 2);
		int[] pieces = new int[32]; java.util.Arrays.fill(pieces, -1);
		pieces[2] = 1; pieces[7] = 2; pieces[8] = 0; pieces[9] = 1; pieces[16] = 2;
		GasStateSnapshot state = GasStateSnapshot.capture(4, 1, 1, pieces, network, "");
		check(state.nodes().getFirst().topologyKey() == 2 && state.nodes().getLast().topologyKey() == 7
				&& state.nodes().getFirst().inventory().equals(network.inventory(1))
				&& state.nodes().getLast().inventory().equals(network.inventory(2)),
				"piece capture changed canonical inventory order or global micro keys");
		check(java.util.Arrays.equals(state.cellNodeOrdinal(), new int[]{-1, -1, 2, -1}),
				"cell observation selected an owner from a mixed gas cell");
		pieces[2] = 0; int[] copy = state.pieceNodeOrdinal(); copy[7] = -1;
		check(state.pieceNodeOrdinal()[2] == 1 && state.pieceNodeOrdinal()[7] == 2,
				"snapshot primary piece membership aliases caller arrays");
		int[] reversed = state.pieceNodeOrdinal(); reversed[2] = 2; reversed[7] = 1;
		expectThrow(() -> GasStateSnapshot.capture(4, 1, 1, reversed, network, ""),
				"independent snapshot sorting of noncanonical inventory/thermal ordinals");
		int[] shortColumn = java.util.Arrays.copyOf(state.pieceNodeOrdinal(), 31);
		expectThrow(() -> pieceSnapshot(state, state.nodes(), shortColumn), "incomplete final micro membership");
		int[] unknown = state.pieceNodeOrdinal(); unknown[31] = 3;
		expectThrow(() -> pieceSnapshot(state, state.nodes(), unknown), "unknown piece owner");
		int[] negative = state.pieceNodeOrdinal(); negative[31] = -2;
		expectThrow(() -> pieceSnapshot(state, state.nodes(), negative), "invalid absent piece sentinel");
		int[] missing = state.pieceNodeOrdinal();
		for (int p = 0; p < missing.length; p++) if (missing[p] == 2) missing[p] = -1;
		expectThrow(() -> pieceSnapshot(state, state.nodes(), missing), "finite node without a positive piece owner");
		expectThrow(() -> pieceSnapshot(state, List.of(state.nodes().getFirst(),
				new GasStateSnapshot.Node(Integer.MAX_VALUE, network.inventory(2))), missing), "missing owner aliased the minimum-key sentinel");
		expectThrow(() -> pieceSnapshot(state, List.of(new GasStateSnapshot.Node(1, network.inventory(1)),
				state.nodes().getLast()), state.pieceNodeOrdinal()), "incorrect global minimum-piece key");
		expectThrow(() -> pieceSnapshot(state, List.of(state.nodes().getLast(), state.nodes().getFirst()),
				state.pieceNodeOrdinal()), "noncanonical node key order");
		expectThrow(() -> new GasStateSnapshot(Integer.MAX_VALUE, 1, 1, state.registryFingerprint(), "",
				state.nodes(), state.pieceNodeOrdinal(), state.externalSource(), state.escapedSink()), "piece dimension overflow");
	}

	private static GasStateSnapshot pieceSnapshot(GasStateSnapshot state, List<GasStateSnapshot.Node> nodes, int[] pieces) {
		return new GasStateSnapshot(state.sizeX(), state.sizeY(), state.sizeZ(), state.registryFingerprint(), "",
				nodes, pieces, state.externalSource(), state.escapedSink());
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
		for (int step = 0; step < 20; step++) network.stepEdges(0.05);
		check(Math.abs(network.gaugePressure(first) - network.gaugePressure(second)) <= 1e-3,
				"Settled implicit flow grew a resolved pressure contrast");
		checkLedger(network, "settled implicit flow");

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
		AirNetwork small=new AirNetwork();int pocket=small.addNode(4.856e-6,4.856e-6);
		small.updateAvailableVolume(pocket,4.818663001060486e-6);
		small.addOrMergeEdge(pocket,AirNetwork.AMBIENT_NODE,4.10723e-7,.65);
		long stock=small.standardVolumeNanos(pocket);small.stepEdges(.0015625);
		check(small.standardVolumeNanos(pocket)<stock && small.gaugePressure(pocket)>0 && small.auditClosed(),
				"A narrow ambient port instantaneously vented a finite PLIC-scale pocket");
		check(small.removeEdge(pocket,AirNetwork.AMBIENT_NODE),"Closing the narrow ambient port lost its edge");
		GasInventory retained=small.inventory(pocket);small.stepEdges(.0015625);
		check(small.inventory(pocket).equals(retained) && small.auditClosed(),
				"Closing a narrow ambient port recreated or deleted the finite pocket inventory");
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
		check(venting.ventedNanos() == 4_000_000_000L - venting.standardVolumeNanos(pressurized),
				"vented ledger disagrees with the actual released inventory");

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
		check(drawing.drawnFromAmbientNanos() == drawing.standardVolumeNanos(evacuated),
				"drawn ledger disagrees with creation plus actual inflow");
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
				new GasComponentDefinition("test:alpha", 1.0,
						List.of("test:a"),
						List.of(new GasComponentDefinition.DefaultSpecies("test:a", ExactRational.of(3_000L), 1))),
				new GasComponentDefinition("test:beta", 2.0,
						List.of("test:b"),
						List.of(new GasComponentDefinition.DefaultSpecies("test:b", ExactRational.of(6_000L), 1)))),
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
		validateTinyPartitionColumns();
	}

	private static void validateAirRecipeAndTraceTransport() {
		var registry = BuiltinGasDefinitions.createRegistry();
		var bulk = registry.createAmbient(1_000_000L);
		check(bulk.component(BuiltinGasDefinitions.WATER_VAPOR) == null && bulk.components().size() == 3, "Dry-air recipe has incorrect components");
		var wet = registry.createAtState(.125, GasRegistry.STANDARD_PRESSURE_PA, GasRegistry.STANDARD_TEMPERATURE_K,
				BuiltinGasDefinitions.airRecipe(ExactRational.parse(".02")));
		check(wet.standardVolumeNanos() == 125_000_000 && wet.component(BuiltinGasDefinitions.WATER_VAPOR).standardVolumeNanos() == 2_500_000,
				"Wet-air recipe changed total pressure instead of replacing dry partial pressure");
		AirNetwork sources = new AirNetwork(registry); int node = sources.addNode(1, 0);
		for (int i = 1; i <= 10_000; i++) {
			sources.setStandardVolume(node, i * 100e-9);
			if (i % 1000 == 0) sources = AirNetwork.fromRuntimeSnapshot(sources.runtimeSnapshot());
		}
		check(sources.inventory(node).equals(bulk) && sources.auditClosed(), "Tiny repeated ambient sources lost trace components or their chemical quanta");
		for (var component : bulk.components()) {
			long chemicalMass = 0;
			for (var species : component.species()) {
				var definition = registry.component(component.componentId()).defaultSpeciesConcentrations().stream()
						.filter(value -> value.speciesId().equals(species.speciesId())).findFirst().orElseThrow();
				chemicalMass += species.amountQuanta() * definition.massUnitsPerQuantum();
			}
			check(component.massUnits() == chemicalMass + component.massResidualUnits(), "Ambient source double-counted its chemical mass residual");
		}
		AirNetwork flowing = new AirNetwork(registry); int from = flowing.addNode(1, 1.1), to = flowing.addNode(1, .9);
		flowing.addOrMergeEdge(from, to, 1e-8, .65);
		var original = flowing.sealedInventory();
		long initialCO2 = flowing.inventory(to).component(BuiltinGasDefinitions.CO2).standardVolumeNanos();
		for (int i = 0; i < 10_000; i++) {
			flowing.stepEdges(.05);
			if (i % 1000 == 0) flowing = AirNetwork.fromRuntimeSnapshot(flowing.runtimeSnapshot());
		}
		long co2Moved = flowing.inventory(to).component(BuiltinGasDefinitions.CO2).standardVolumeNanos() - initialCO2;
		check(co2Moved > 100 && flowing.sealedInventory().equals(original) && flowing.auditClosed(), "Repeated low-flow ports retained their trace CO2 or changed conserved columns");
		System.out.printf("  dry-air trace transport: 10,000 tiny ambient sources match bulk; 10,000 low-flow steps moved %d CO2 nanos%n", co2Moved);
	}

	private static void validateTinyPartitionColumns() {
		GasRegistry registry = BuiltinGasDefinitions.createRegistry();
		for (long nanos : new long[] {1_000_000_000L, 2_000_000_000_000_000L, 1L << 54, Long.MAX_VALUE / 32}) {
			GasInventory inventory = registry.createAmbient(nanos);
			GasInventory.Split tinyFirst = inventory.splitTotal(1), tinyLast = inventory.splitTotal(nanos - 1);
			var first = tinyFirst.first().components().getFirst();
			var last = tinyLast.second().components().getFirst();
			check(first.standardVolumeNanos() == 1 && last.standardVolumeNanos() == 1 && last.mass() > 0,
					"representable tiny gas remainder lost positive mass");
			check(Double.doubleToRawLongBits(first.mass()) == Double.doubleToRawLongBits(last.mass())
					&& Double.doubleToRawLongBits(first.massResidual()) == Double.doubleToRawLongBits(last.massResidual()),
					"gas take direction changed the tiny mass/residual columns");
			var column = new GasComponentAmount("test:alpha", nanos, mmm.units.Mass.fromKilograms(inventory.totalMass()),
					List.of(new GasComponentAmount.SpeciesAmount("test:a", 17)), mmm.units.Mass.fromKilograms(-inventory.totalMass() / 3));
			GasInventory composed = new GasInventory(List.of(column));
			List<GasInventory> forward = composed.partition(new long[] {nanos - 3, 0, 1, 2});
			List<GasInventory> reverse = composed.partition(new long[] {2, 1, 0, nanos - 3});
			long mass = 0L, residual = 0L; long quanta = 0;
			for (int child = 0; child < forward.size(); child++) {
				check(forward.get(child).components().equals(reverse.get(3 - child).components()),
						"unique gas share permutation changed independent columns");
				for (var amount : forward.get(child).components()) {
					mass = Math.addExact(mass, amount.massUnits()); residual = Math.addExact(residual, amount.massResidualUnits());
					for (var species : amount.species()) quanta += species.amountQuanta();
				}
			}
			var pivot = forward.getFirst().components().getFirst();
			check(quanta == 17 && forward.get(2).standardVolumeNanos() == 1 && forward.get(3).standardVolumeNanos() == 2,
					"gas tiny partition changed exact species or nanos");
			check(mass == column.massUnits() && residual == column.massResidualUnits(),
					"Gas partition changed integer mass or residual inventory");
		}
		for (boolean reverse : new boolean[] {false, true}) {
			AirNetwork network = new AirNetwork(registry); int source = network.addNode(1, 1);
			int[] children = network.splitNode(source, reverse ? 1e-9 : .999999999, reverse ? .999999999 : 1e-9);
			check(network.inventory(children[reverse ? 0 : 1]).standardVolumeNanos() == 1 && network.auditClosed(),
					"production tiny topology split failed its exact mass ledger");
		}
		GasInventory subQuantumMass = new GasInventory(List.of(
				new GasComponentAmount(BuiltinGasDefinitions.NITROGEN, 2, 1L, List.of(), 0L)));
		expectThrow(() -> subQuantumMass.splitTotal(1), "unrepresentable positive gas child mass");
	}

	private static void validateExactTopologyPartition() {
		for (Runnable invalid : List.<Runnable>of(
				() -> GasPartitionWeights.ofRationals(List.of(ExactRational.ONE)).partitionControlVolume(Double.NaN),
				() -> GasPartitionWeights.ofRationals(List.of(ExactRational.of(-1))))) {
			try { invalid.run(); throw new AssertionError("Invalid gas partition input was accepted"); }
			catch (GasPartitionWeights.UnrepresentableVolumeException physical) {
				throw new AssertionError("Invalid gas partition input was classified as a physical limit", physical);
			} catch (IllegalArgumentException expected) { }
		}
		try {
			GasPartitionWeights.ofRationals(List.of(ExactRational.ONE, ExactRational.of(2)))
					.partitionControlVolume(Double.MIN_VALUE);
			throw new AssertionError("Positive unrepresentable gas child volume was accepted");
		} catch (GasPartitionWeights.UnrepresentableVolumeException expected) { }
		GasInventory source = new GasInventory(List.of(
				new GasComponentAmount("test:a", 61, mmm.units.Mass.fromKilograms(.125), List.of(
						new GasComponentAmount.SpeciesAmount("test:s", 101)), mmm.units.Mass.fromKilograms(-.03125)),
				new GasComponentAmount("test:b", 17, mmm.units.Mass.fromKilograms(1.5), List.of(
						new GasComponentAmount.SpeciesAmount("test:s", 13)), mmm.units.Mass.fromKilograms(.25))));
		var normal = source.partition(List.of(ExactRational.ZERO, ExactRational.ONE.divide(4), ExactRational.of(3).divide(4)));
		check(normal.equals(source.partition(new long[] {0, 1, 3})),
				"Dyadic topology weights changed a normal partition's stored columns");
		checkPartitionColumns(source, normal);
		GasInventory large = new GasInventory(List.of(new GasComponentAmount("test:a", Long.MAX_VALUE,
				mmm.units.Mass.fromKilograms(.125), List.of(new GasComponentAmount.SpeciesAmount("test:s", 7)), mmm.units.Mass.fromKilograms(-.03125))));
		try {
			large.merge(new GasInventory(List.of(new GasComponentAmount("test:a", 1, mmm.units.Mass.fromKilograms(.125), List.of(), 0L))));
			throw new AssertionError("Overflowing gas merge was accepted");
		} catch (GasInventory.RepresentationException expected) { }
		AirNetwork invalidSource = new AirNetwork();
		try {
			invalidSource.planPhaseSource(-1, GasInventory.EMPTY);
			throw new AssertionError("Invalid gas source node was accepted");
		} catch (GasInventory.RepresentationException physical) {
			throw new AssertionError("Invalid gas source node was classified as a physical limit", physical);
		} catch (IllegalArgumentException expected) { }
		var before = new GasInventory(large.components());
        for (double tiny : new double[]{Math.scalb(1.0, -60), Double.MIN_VALUE}) {
            var weights = GasPartitionWeights.of(new double[]{tiny, 1});
            long[] shares = weights.allocateNanos(Long.MAX_VALUE);
            check(Math.addExact(shares[0], shares[1]) == Long.MAX_VALUE && shares[0] >= 0,
                    "Bounded geometric shares lost integer inventory");
            check(shares[0] <= (long) (Long.MAX_VALUE * tiny) + (Long.MAX_VALUE >>> 52) + 2,
                    "Geometric share approximation exceeded one weight quantum");
        }
		check(large.equals(before), "Read-only rational topology partition changed its source inventory");
		validateGeometricSumPartition(source);
		for(boolean reverse:new boolean[]{false,true}) {
			AirNetwork network=new AirNetwork();int node=network.addNode(3e-10,1e-9);
			int[] split=network.splitNode(node,reverse?2.99e-10:1e-12,reverse?1e-12:2.99e-10);
			check(network.inventory(split[reverse?1:0]).isEmpty() && network.inventory(split[reverse?0:1]).standardVolumeNanos()==1
					&& network.auditClosed(),"Public cavity split replaced sub-nano volume proportions by equal minimum weights");
		}
	}

    private static void validateGeometricSumPartition(GasInventory source) {
        var before = new GasInventory(source.components());
        var symmetric = GasPartitionWeights.of(new double[]{2, 2});
        var parts = source.partition(symmetric);
        checkPartitionColumns(source, parts);
        check(parts.getFirst().component("test:a").standardVolumeNanos() == 31
                && parts.getFirst().component("test:b").standardVolumeNanos() == 9,
                "Equal floating geometry changed ordinal integer ties");
        check(java.util.Arrays.equals(symmetric.partitionControlVolume(1), new double[]{.5, .5}),
                "Equal geometry changed the physical control volume");
        for (double scale : new double[]{1e-100, 1, 1e100}) {
            var weights = GasPartitionWeights.of(new double[]{scale, 3 * scale, 0});
            check(java.util.Arrays.equals(weights.allocateNanos(61), new long[]{15, 46, 0}),
                    "Geometry scaling changed conservative allocation");
            checkPartitionColumns(source, source.partition(weights));
        }
        var tiny = GasPartitionWeights.of(new double[]{Double.MIN_VALUE, 1});
        check(java.util.Arrays.equals(tiny.allocateNanos(1), new long[]{0, 1}),
                "One-nano stock lost its actual destination");
        check(source.equals(before), "Geometric partition changed its source inventory");
    }

	private static void validateSplitPreflightsAllOwners() {
		AirNetwork full = new AirNetwork();
		double[] volumes = new double[GasLimits.MAX_NODES_PER_REGION];
		java.util.Arrays.fill(volumes, 1);
		full.restoreNodes(volumes, java.util.Collections.nCopies(volumes.length, GasInventory.EMPTY),
				GasAuditLedger.EMPTY, GasAuditLedger.EMPTY);
		AirNetwork oversized = new AirNetwork();
		double[] excessVolumes = new double[volumes.length + 1];
		java.util.Arrays.fill(excessVolumes, 1);
		checkNodeMutationRejectsUnchanged(oversized, () -> oversized.restoreNodes(excessVolumes,
				java.util.Collections.nCopies(excessVolumes.length, GasInventory.EMPTY), GasAuditLedger.EMPTY,
				GasAuditLedger.EMPTY));
		checkNodeMutationRejectsUnchanged(oversized, () -> oversized.remapNodes(excessVolumes,
				java.util.Collections.nCopies(excessVolumes.length, GasInventory.EMPTY), GasInventory.EMPTY, GasInventory.EMPTY));
		full.addOrMergeEdge(1, 2, .01, .65);
		checkSplitRejectsUnchanged(full, .5, .5);
		full.ventNode(volumes.length);
		int[] accepted = full.splitNode(1, .5, .5);
		check(accepted[0] == 1 && accepted[1] == volumes.length + 1 && full.nodeCount() == volumes.length && full.auditClosed(),
				"Rejected over-budget split consumed a node id or prevented a legal split");

		// A zero net mass change leaves this exact, closed ledger representable despite its wide stored roundoff.
		AirNetwork wide = restoredWideLedger(false);
		GasAuditLedger source = wide.externalSource();
		wide.addOrMergeEdge(1, AirNetwork.AMBIENT_NODE, .01, .65);
		check(wide.auditClosed() && Double.isFinite(wide.gaugePressure(1)) && wide.density(1) == 3,
				"Split audit-budget fixture is not a valid restored gas owner");
		long revision = wide.revision();
		var edges = wide.edges();
		int[] exactChildren = wide.splitNode(1, .5, .5);
		check(exactChildren[0] == 1 && exactChildren[1] == 2 && wide.revision() == revision + 1
				&& wide.externalSource().equals(source) && wide.escapedSink().equals(GasAuditLedger.EMPTY)
				&& wide.edges().equals(edges) && wide.availableVolume(1) == .5 && wide.availableVolume(2) == .5 && wide.auditClosed(),
				"Zero-delta split changed the wide audit or rejected a representable final ledger");

		GasInventory rounded = new GasInventory(List.of(new GasComponentAmount(BuiltinGasDefinitions.NITROGEN,
				3, mmm.units.Mass.fromKilograms(3.2), List.of(), 0L)));
		var parts = rounded.partition(new long[] {1, 2});
		check(Math.addExact(parts.getFirst().totalMassUnits(), parts.getLast().totalMassUnits()) == rounded.totalMassUnits(),
				"Unequal gas split changed integer mass inventory");
	}

	private static AirNetwork restoredWideLedger(boolean twoNodes) {
		GasInventory inventory = new GasInventory(List.of(new GasComponentAmount(BuiltinGasDefinitions.NITROGEN,
				twoNodes ? 500_000_000L : 1_000_000_000L, mmm.units.Mass.fromKilograms(twoNodes ? 1.5 : 3), List.of(), 0L)));
		GasAuditLedger source = new GasAuditLedger(List.of(new GasAuditLedger.Entry(
				BuiltinGasDefinitions.NITROGEN, 1_000_000_000L, mmm.units.Mass.fromKilograms(3), List.of(), 0L)));
		AirNetwork network = new AirNetwork();
		network.restoreNodes(twoNodes ? new double[] {.5, .5} : new double[] {1},
				twoNodes ? List.of(inventory, inventory) : List.of(inventory), source, GasAuditLedger.EMPTY);
		return network;
	}

	private static void validatePublicMutatorsPreflightAllOwners() {
		AirNetwork cumulative = new AirNetwork(new GasRegistry(List.of(new GasComponentDefinition(
				BuiltinGasDefinitions.NITROGEN, .01, List.of(), List.of())), List.of(new GasRegistry.AmbientFraction(BuiltinGasDefinitions.NITROGEN, ExactRational.ONE))));
		int sourceNode = cumulative.addNode(1, AirNetwork.cubicMeters(Long.MAX_VALUE));
		cumulative.setStandardVolume(sourceNode, 0);
		check(cumulative.auditClosed() && cumulative.drawnFromAmbientNanos() == Long.MAX_VALUE
				&& cumulative.ventedNanos() == Long.MAX_VALUE, "Cumulative source-budget fixture is invalid");
		checkNodeMutationRejectsUnchanged(cumulative, () -> cumulative.setStandardVolume(sourceNode, 1e-9));
		checkNodeMutationRejectsUnchanged(cumulative, () -> cumulative.addNode(1, 1e-9));
		checkNodeMutationRejectsUnchanged(cumulative, () -> cumulative.addNode(Double.NaN, 0));
		check(cumulative.addNode(1, 0) == 2 && cumulative.auditClosed(), "Rejected creation consumed a node id");
		GasRegistry mixture = new GasRegistry(List.of(
				new GasComponentDefinition("test:a", .01, List.of(), List.of()),
				new GasComponentDefinition("test:b", .01, List.of(), List.of())), List.of(
				new GasRegistry.AmbientFraction("test:a", ExactRational.ONE.divide(2)),
				new GasRegistry.AmbientFraction("test:b", ExactRational.ONE.divide(2))));
		AirNetwork mixed = new AirNetwork(mixture);
		int vacuum = mixed.addNode(1, AirNetwork.cubicMeters(Long.MAX_VALUE));
		mixed.setStandardVolume(vacuum, 0);
		mixed.addOrMergeEdge(AirNetwork.AMBIENT_NODE, vacuum, 3e-10, .65);
		check(mixed.auditClosed() && mixed.drawnFromAmbientNanos() == Long.MAX_VALUE
				&& mixed.externalSource().entries().stream().allMatch(entry -> entry.standardVolumeNanos() < Long.MAX_VALUE),
				"Multi-component cumulative source-budget fixture is invalid");
		checkNodeMutationRejectsUnchanged(mixed, () -> mixed.stepEdges(.01));
		for (int action = 0; action < 4; action++) {
			AirNetwork network = restoredWideLedger(action == 1);
			network.addOrMergeEdge(1, AirNetwork.AMBIENT_NODE, .01, .65);
			if (action == 1) network.addOrMergeEdge(2, AirNetwork.AMBIENT_NODE, .02, .65);
			GasAuditLedger source = network.externalSource();
			long revision = network.revision();
			switch (action) {
				case 0 -> check(network.addNode(1, 0) == 2 && network.inventory(2).isEmpty(), "Vacuum creation changed identity");
				case 1 -> { network.mergeNodes(1, 2); check(network.nodeIds().equals(List.of(1)) && network.availableVolume(1) == .5
						&& network.edgeCount() == 1 && close(network.edges().getFirst().areaSquareMeters(), .03), "Merge changed capacity or edge rewiring"); }
				case 2 -> { network.ventNode(1); check(network.nodeCount() == 0 && network.edgeCount() == 0, "Vent retained nodes or edges"); }
				case 3 -> { network.setStandardVolume(1, 0); check(network.inventory(1).isEmpty() && network.availableVolume(1) == 1
						&& network.edgeCount() == 1, "Explicit removal changed physical capacity or edge identity"); }
			}
			check(network.revision() == revision + 1 && network.externalSource().equals(source)
					&& network.ventedNanos() == (action >= 2 ? 1_000_000_000L : 0) && network.auditClosed(),
					"Zero-delta public edit rejected or changed its wide audit");
		}
		AirNetwork phase = restoredWideLedger(false);
		phase.commitPhaseSource(1, new GasInventory(List.of(new GasComponentAmount(BuiltinGasDefinitions.NITROGEN,
				500_000_000L, mmm.units.Mass.fromKilograms(.5), List.of(), 0L))));
		check(phase.inventory(1).totalMass() == 3.5 && phase.auditClosed(),
				"Zero-delta phase source rejected a representable wide ledger");
		phase = restoredWideLedger(false);
		phase.commitPhaseSink(1, phase.inventory(1).splitTotal(500_000_000L).first());
		check(phase.inventory(1).totalMass() == 1.5 && phase.auditClosed(),
				"Zero-delta phase sink rejected a representable wide ledger");
		AirNetwork pore = restoredWideLedger(true);
		pore.updateAvailableVolume(1, .25); pore.updateAvailableVolume(2, .75);
		pore.addOrMergeEdge(1, 2, .01, .65);
		pore.stepEdges(1);
		check(pore.standardVolumeNanos(1) >= 250_000_000L && pore.standardVolumeNanos(1) < 500_000_000L && pore.auditClosed(),
				"Zero-delta finite-rate transfer rejected a representable wide ledger");

		AirNetwork edges = new AirNetwork(); edges.addNode(1, 1); edges.addNode(1, 1);
		checkNodeMutationRejectsUnchanged(edges, () -> edges.addOrMergeEdge(1, 99, .01, .65));
		expectThrow(() -> new AirEdge(1, 2, Double.POSITIVE_INFINITY, .65), "nonfinite direct gas edge");
		edges.addOrMergeEdge(1, 2, .01, .65);
		checkNodeMutationRejectsUnchanged(edges, () -> edges.addOrMergeEdge(1, 2, -.001, .65));
		checkNodeMutationRejectsUnchanged(edges, () -> edges.addOrMergeEdge(1, 2, .001, 2));
		checkNodeMutationRejectsUnchanged(edges, () -> edges.addOrMergeEdge(1, 2, Double.POSITIVE_INFINITY, .65));
	}

	private static void validatePhaseSuccessorAndParallelEdges() {
		AirNetwork phase = new AirNetwork();
		int node = phase.addNode(1, 0);
		var first = phase.planPhaseSource(node, phase.ambientInventory(1));
		var second = phase.planPhaseSource(node, phase.ambientInventory(2));
		var successor = phase.planRemapAfterPhase(first, new double[]{1}, List.of(first.inventoryAfter()),
				GasAuditLedger.EMPTY, GasAuditLedger.EMPTY, List.of());
		checkNodeMutationRejectsUnchanged(phase, () -> phase.requireRemapAfterPhaseCurrent(successor, second), true);
		phase.requireRemapAfterPhaseCurrent(successor, first);
		phase.commitPhaseMutation(second);
		checkNodeMutationRejectsUnchanged(phase, () -> phase.commitRemapNodes(successor), true);
		check(phase.standardVolumeNanos(node) == 2 && phase.drawnFromAmbientNanos() == 2,
				"A different phase successor replaced committed inventory or source");
		var next = phase.planPhaseSource(node, phase.ambientInventory(1));
		var correct = phase.planRemapAfterPhase(next, new double[]{1}, List.of(next.inventoryAfter()),
				GasAuditLedger.EMPTY, GasAuditLedger.EMPTY, List.of());
		phase.requireRemapAfterPhaseCurrent(correct, next);
		phase.commitPhaseMutation(next); phase.commitRemapNodes(correct);
		check(phase.standardVolumeNanos(1) == 3 && phase.drawnFromAmbientNanos() == 3 && phase.auditClosed(),
				"The matching phase successor did not preserve its inventory and audit");
		checkNodeMutationRejectsUnchanged(phase, () -> phase.commitRemapNodes(correct), true);

		double[] areas = {.01, .02, .03}, coefficients = {.1, .9, .4};
		int[][] orders = {{0,1,2}, {0,2,1}, {1,0,2}, {1,2,0}, {2,0,1}, {2,1,0}};
		for (int count = 2; count <= 3; count++) {
			java.math.BigDecimal area = java.math.BigDecimal.ZERO, dischargeArea = java.math.BigDecimal.ZERO;
			for (int i = 0; i < count; i++) {
				var exactArea = new java.math.BigDecimal(areas[i]);
				area = area.add(exactArea);
				dischargeArea = dischargeArea.add(exactArea.multiply(new java.math.BigDecimal(coefficients[i])));
			}
			double expectedArea = area.doubleValue();
			double expectedCoefficient = dischargeArea.divide(area, new java.math.MathContext(100)).doubleValue();
			for (int[] order : orders) for (boolean reverse : new boolean[]{false, true}) {
				AirNetwork merged = new AirNetwork();
				for (int i = 0; i < count; i++) merged.addNode(1, 1);
				var incoming = new java.util.ArrayList<AirEdge>();
				for (int ordinal : order) if (ordinal < count) {
					int firstNode = reverse ? 0 : ordinal + 1, secondNode = reverse ? ordinal + 1 : 0;
					merged.addOrMergeEdge(firstNode, secondNode, areas[ordinal], coefficients[ordinal]);
					incoming.add(new AirEdge(reverse ? 1 : 0, reverse ? 0 : 1, areas[ordinal], coefficients[ordinal]));
				}
				merged.mergeNodes(1, count == 2 ? new int[]{2} : new int[]{2, 3});
				AirEdge expected = new AirEdge(0, 1, expectedArea, expectedCoefficient);
				check(merged.edges().equals(List.of(expected)) && merged.auditClosed(),
						"Node merge changed parallel conductance with edge order or orientation");
				var remap = merged.planRemapNodes(new double[]{1}, List.of(merged.inventory(1)),
						GasAuditLedger.EMPTY, GasAuditLedger.EMPTY, incoming);
				merged.commitRemapNodes(remap);
				check(merged.edges().equals(List.of(expected)) && merged.auditClosed(),
						"Topology remap did not aggregate all parallel openings canonically");
			}
		}
	}

	private static void checkSplitRejectsUnchanged(AirNetwork network, double... volumes) {
		checkNodeMutationRejectsUnchanged(network, () -> network.splitNode(1, volumes));
	}

	private static void checkNodeMutationRejectsUnchanged(AirNetwork network, Runnable mutation) {
		checkNodeMutationRejectsUnchanged(network, mutation, false);
	}

	private static void checkNodeMutationRejectsUnchanged(AirNetwork network, Runnable mutation, boolean stalePlan) {
		var ids = network.nodeIds();
		var inventories = ids.stream().map(network::inventory).toList();
		double[] capacities = ids.stream().mapToDouble(network::availableVolume).toArray();
		var edges = List.copyOf(network.edges());
		GasAuditLedger source = network.externalSource(), sink = network.escapedSink();
		long revision = network.revision();
		int clamps = network.transferClampEvents();
		boolean rejected = false;
		try { mutation.run(); } catch (IllegalArgumentException | ArithmeticException expected) {
			if (stalePlan) throw expected;
			rejected = true;
		}
		catch (IllegalStateException stale) { if (!stalePlan) throw stale; rejected = true; }
		check(rejected, "Unrepresentable gas mutation did not reject its complete candidate");
		check(network.nodeIds().equals(ids) && network.edges().equals(edges) && network.revision() == revision && network.transferClampEvents() == clamps
				&& network.externalSource().equals(source) && network.escapedSink().equals(sink) && network.auditClosed(),
				"Rejected gas mutation changed node identity, edges, audit or revision");
		for (int slot = 0; slot < ids.size(); slot++) check(network.inventory(ids.get(slot)).equals(inventories.get(slot))
				&& Double.doubleToRawLongBits(network.availableVolume(ids.get(slot))) == Double.doubleToRawLongBits(capacities[slot]),
				"Rejected gas mutation changed a node inventory or capacity");
	}

	private static void checkPartitionColumns(GasInventory source, List<GasInventory> parts) {
		for (GasComponentAmount original : source.components()) {
			long nanos = 0;
			var species = new TreeMap<String, Long>();
			SignedDyadic mass = SignedDyadic.ZERO, residual = SignedDyadic.ZERO;
			GasComponentAmount pivot = null;
			for (GasInventory part : parts) {
				GasComponentAmount portion = part.component(original.componentId());
				if (portion == null) continue;
				nanos = Math.addExact(nanos, portion.standardVolumeNanos());
				mass = mass.add(SignedDyadic.of(portion.mass()));
				residual = residual.add(SignedDyadic.of(portion.massResidual()));
				for (var amount : portion.species()) species.merge(amount.speciesId(), amount.amountQuanta(), Math::addExact);
				if (pivot == null || portion.standardVolumeNanos() > pivot.standardVolumeNanos()) pivot = portion;
			}
			check(nanos == original.standardVolumeNanos(), "Rational topology partition lost component nanos");
			check(species.size() == original.species().size(), "Rational topology partition changed species membership");
			for (var amount : original.species()) check(species.get(amount.speciesId()) == amount.amountQuanta(),
					"Rational topology partition lost integer species");
			double[] stored = {original.mass(), original.massResidual()}, pivotColumns = {pivot.mass(), pivot.massResidual()};
			SignedDyadic[] totals = {mass, residual};
			for (int column = 0; column < totals.length; column++) {
				SignedDyadic difference = totals[column].subtract(SignedDyadic.of(stored[column]));
				if (difference.signum() < 0) difference = difference.negate();
				SignedDyadic halfUlp = SignedDyadic.of(Math.ulp(pivotColumns[column])).multiply(SignedDyadic.of(.5));
				check(difference.compareTo(halfUlp) <= 0, "Rational topology partition exceeded its pivot rounding bound");
			}
		}
	}

	private static void validateComponentSpeciesBoundary() {
		expectThrow(() -> new GasComponentDefinition("test:invalid", 1.0, List.of(),
				List.of(new GasComponentDefinition.DefaultSpecies("test:a", ExactRational.ONE, 1))),
				"default species outside component allow-list");
		GasRegistry registry = new GasRegistry(List.of(
				new GasComponentDefinition("test:alpha", 1.0, List.of("test:a"), List.of()),
				new GasComponentDefinition("test:beta", 1.0, List.of("test:b"), List.of())),
				List.of(new GasRegistry.AmbientFraction("test:alpha", ExactRational.ONE)));
		AirNetwork network = new AirNetwork(registry);
		int node = network.addNode(1.0, 1.0);
		GasInventory wrongComponent = new GasInventory(List.of(new GasComponentAmount(
				"test:alpha", 1L, mmm.units.Mass.fromKilograms(1.0E-9),
				List.of(new GasComponentAmount.SpeciesAmount("test:b", 1L)), 0L)));
		long before = network.standardVolumeNanos(node);
		expectThrow(() -> network.commitPhaseSource(node, wrongComponent), "species attached to wrong component");
		check(network.standardVolumeNanos(node) == before && network.auditClosed(),
				"rejected gas species source changed inventory or audit state");
	}

	private static void validatePhaseColumnTake() {
		AirNetwork network = new AirNetwork();
		int node = network.addNode(2.0, 2.0);
		GasInventory groupedVapor = new GasInventory(List.of(new GasComponentAmount(
				BuiltinGasDefinitions.WATER_VAPOR, 4_005_761L, mmm.units.Mass.fromKilograms(0.003), List.of(), mmm.units.Mass.fromKilograms(0.003))));
		network.commitPhaseSource(node, groupedVapor);
		GasInventory.Split take = network.previewComponentTake(node, BuiltinGasDefinitions.WATER_VAPOR,
				2_670_508L, mmm.units.Mass.fromKilograms(0.002), mmm.units.Mass.fromKilograms(0.002));
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
		validatePhaseMutationPlans();
	}

	private static void validatePhaseMutationPlans() {
		AirNetwork network = new AirNetwork(new GasRegistry(List.of(new GasComponentDefinition(
				BuiltinGasDefinitions.NITROGEN, .01, List.of(), List.of())),
				List.of(new GasRegistry.AmbientFraction(BuiltinGasDefinitions.NITROGEN, ExactRational.ONE))));
		int first = network.addNode(1.0, 0.0), second = network.addNode(1.0, 0.0);
		GasInventory oneNano = new GasInventory(List.of(new GasComponentAmount(BuiltinGasDefinitions.NITROGEN, 1L, 1L, List.of(), 1L)));
		AirNetwork.PhaseMutationPlan plan = network.planPhaseSource(first, oneNano);
		check(network.standardVolumeNanos(first) == 0L, "phase planning changed live gas");
		AirNetwork other = new AirNetwork();
		other.addNode(1.0, 0.0);
		expectStateThrow(() -> other.commitPhaseMutation(plan), "foreign phase mutation");
		network.updateAvailableVolume(first, 0.5);
		expectStateThrow(() -> network.commitPhaseMutation(plan), "stale phase mutation");
		check(network.standardVolumeNanos(first) == 0L && other.sealedInventoryNanos() == 0L,
				"rejected phase plan changed gas");
		AirNetwork.PhaseMutationPlan committed = network.planPhaseSource(first, oneNano);
		network.commitPhaseMutation(committed);
		expectStateThrow(() -> network.commitPhaseMutation(committed), "reused phase mutation");
		checkLedger(network, "phase plan ownership and generation");

		network.commitPhaseSource(first, network.registry().createAmbient(Long.MAX_VALUE - 1L));
		long revision = network.revision();
		GasInventory inventory = network.inventory(first);
		GasAuditLedger source = network.externalSource(), sink = network.escapedSink();
		try {
			network.planPhaseSource(second, oneNano);
			throw new AssertionError("phase plan accepted overflowing regional gas inventory and source ledger");
		} catch (AirNetwork.PhaseCapacityExceededException expected) { }
		check(network.revision() == revision && network.inventory(first).equals(inventory)
				&& network.standardVolumeNanos(second) == 0L
				&& network.externalSource().equals(source) && network.escapedSink().equals(sink)
				&& network.sealedInventoryNanos() == Long.MAX_VALUE,
				"unrepresentable regional phase plan changed raw gas or audit columns");
		checkLedger(network, "rejected regional phase capacity");
	}

	private static void validateRegionalComponentAggregation() {
		java.util.ArrayList<GasComponentDefinition> definitions = new java.util.ArrayList<>();
		java.util.ArrayList<GasComponentAmount> first = new java.util.ArrayList<>(), second = new java.util.ArrayList<>();
		for (int component = 0; component < 33; component++) {
			String id = "test:regional_" + component;
			definitions.add(new GasComponentDefinition(id, 1.0, List.of(), List.of()));
			(component < 17 ? first : second).add(new GasComponentAmount(id, 1L, mmm.units.Mass.fromKilograms(1.0), List.of(), 0L));
		}
		GasRegistry registry = new GasRegistry(definitions, List.of(new GasRegistry.AmbientFraction("test:regional_0", ExactRational.ONE)));
		AirNetwork network = new AirNetwork(registry);
		int a = network.addNode(1.0, 0.0), b = network.addNode(1.0, 0.0);
		network.commitPhaseSource(a, new GasInventory(first)); network.commitPhaseSource(b, new GasInventory(second));
		check(network.sealedInventory().entries().size() == 33 && network.auditClosed(), "regional summary used the single-node component limit");
		int[] pieces = new int[16]; java.util.Arrays.fill(pieces, -1); pieces[0] = a; pieces[8] = b;
		var snapshot = GasStateSnapshot.capture(2, 1, 1, pieces, network, "");
		AirNetwork restored = new AirNetwork(registry);
		restored.restoreNodes(new double[]{1, 1}, snapshot.nodes().stream().map(GasStateSnapshot.Node::inventory).toList(),
				snapshot.externalSource(), snapshot.escapedSink());
		check(restored.auditClosed() && restored.sealedInventory().equals(network.sealedInventory()), "33-component region failed restore");
		var identity = restored.planRemapNodes(new double[]{1, 1}, List.of(restored.inventory(1), restored.inventory(2)),
				GasAuditLedger.EMPTY, GasAuditLedger.EMPTY, List.of());
		restored.commitRemapNodes(identity); check(restored.auditClosed(), "regional identity remap failed");
		restored.commitRemapNodes(restored.planRemapNodes(new double[]{1, 1}, List.of(GasInventory.EMPTY, GasInventory.EMPTY),
				GasAuditLedger.EMPTY, restored.sealedInventory(), List.of()));
		check(restored.sealedInventory().isEmpty() && restored.escapedSink().entries().size() == 33 && restored.auditClosed(),
				"regional clear rebuilt the sink with a single-node limit");
		first.addAll(second);
		expectThrow(() -> new GasInventory(first), "single node above 32 components");

		AirNetwork rejected = new AirNetwork(registry);
		GasAuditLedger source = rejected.externalSource(), sink = rejected.escapedSink();
		expectThrow(() -> rejected.restoreNodes(new double[]{1, 1}, List.of(network.inventory(a), network.inventory(b)),
				GasAuditLedger.EMPTY, GasAuditLedger.EMPTY), "nonclosing regional restore");
		check(rejected.nodeCount() == 0 && rejected.revision() == 0 && rejected.externalSource() == source
				&& rejected.escapedSink() == sink, "failed restore published nodes or ledger");
		GasInventory hugeFirst = new GasInventory(List.of(new GasComponentAmount("test:regional_0", Long.MAX_VALUE, mmm.units.Mass.fromKilograms(1), List.of(), 0L)));
		GasInventory hugeSecond = new GasInventory(List.of(new GasComponentAmount("test:regional_1", 1, mmm.units.Mass.fromKilograms(1), List.of(), 0L)));
		try {
			rejected.restoreNodes(new double[]{1, 1}, List.of(hugeFirst, hugeSecond), GasAuditLedger.EMPTY,
					GasAuditLedger.EMPTY);
			throw new AssertionError("regional nanos overflow was accepted");
		} catch (ArithmeticException expected) { }
		check(rejected.nodeCount() == 0 && rejected.revision() == 0 && rejected.externalSource() == source
				&& rejected.escapedSink() == sink, "throwing restore published partial state");

		GasInventory hugeMass = new GasInventory(List.of(new GasComponentAmount("test:regional_0", 1, Long.MAX_VALUE, List.of(), 0L)));
		AirNetwork massive = new AirNetwork(registry);
		massive.commitPhaseSource(massive.addNode(1, 0), hugeMass);
		int secondNode = massive.addNode(1, 0);
		checkNodeMutationRejectsUnchanged(massive, () -> massive.commitPhaseSource(secondNode, hugeMass));
		check(massive.auditClosed() && massive.sealedInventory().component("test:regional_0").massUnits() == Long.MAX_VALUE,
				"Rejected integer mass overflow changed the gas inventory");
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
