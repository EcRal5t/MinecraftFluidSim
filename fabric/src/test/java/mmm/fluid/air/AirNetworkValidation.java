package mmm.fluid.air;

import java.util.List;

/** Gas definitions, explicit initial inventory and snapshot handoff. */
public final class AirNetworkValidation {
    private AirNetworkValidation() {}
    public static void main(String[] args) {
        validateRegistrySnapshot();validatePieceSnapshot();validateIntegerPartition();
        System.out.println("Gas definitions, initial inventory and snapshot passed");
    }

    private static void validateIntegerPartition() {
        var registry=mmm.fluid.TestFluidDefinitions.load().gas();var chemistry=mmm.fluid.TestFluidDefinitions.load().chemistry();
        long quanta=4366,residual=479,mass=quanta*440+residual;
        var source=new GasInventory(List.of(new GasComponentAmount(BuiltinGasDefinitions.CO2,1_000_003,mass,
                List.of(new GasComponentAmount.SpeciesAmount(mmm.chemistry.BuiltinChemicalDefinitions.CO2,quanta)),residual)));
        var parts=source.partition(GasPartitionWeights.of(new double[]{.13,.27,.31,.29}),registry,chemistry);
        long totalMass=0,totalQuanta=0,totalResidual=0,totalNanos=0;
        for(var part:parts)for(var component:part.components()){
            long amount=component.species().stream().mapToLong(GasComponentAmount.SpeciesAmount::amountQuanta).sum();
            if(component.massUnits()!=amount*440+component.massResidualUnits())throw new AssertionError("Gas part mass and species differ");
            totalMass+=component.massUnits();totalQuanta+=amount;totalResidual+=component.massResidualUnits();totalNanos+=component.standardVolumeNanos();
        }
        if(totalMass!=mass||totalQuanta!=quanta||totalResidual!=residual||totalNanos!=source.standardVolumeNanos())
            throw new AssertionError("Gas partition changed integer inventory");
    }

	private static void validateRegistrySnapshot() {
		java.nio.file.Path root = null;
		try {
			root = java.nio.file.Files.createTempDirectory("mmm-gas-registry-");
			GasRegistry definitions = mmm.fluid.TestFluidDefinitions.load().gas();
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

	public static void validatePieceSnapshot() {
		AirNetwork network = new AirNetwork(mmm.fluid.TestFluidDefinitions.load().gas()); network.addNode(1, 1); network.addNode(2, 2);
		int[] pieces = new int[32]; java.util.Arrays.fill(pieces, -1);
		pieces[2] = 1; pieces[7] = 2; pieces[8] = 0; pieces[9] = 1; pieces[16] = 2;
		GasStateSnapshot state = GasStateSnapshot.capture(mmm.spatial.SpatialGrid.rectangular(4,1,1), pieces, network, "");
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
		expectThrow(() -> GasStateSnapshot.capture(mmm.spatial.SpatialGrid.rectangular(4,1,1), reversed, network, ""),
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
        expectThrow(() -> mmm.spatial.SpatialGrid.rectangular(0,1,1), "invalid spatial dimensions");
	}

	private static GasStateSnapshot pieceSnapshot(GasStateSnapshot state, List<GasStateSnapshot.Node> nodes, int[] pieces) {
		return new GasStateSnapshot(state.grid(), state.registryFingerprint(), "",
				nodes, pieces, state.externalSource(), state.escapedSink(), state.phaseSource(), state.phaseSink());
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
