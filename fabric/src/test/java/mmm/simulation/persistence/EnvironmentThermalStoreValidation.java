package mmm.simulation.persistence;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import mmm.fluid.air.GasComponentAmount;
import mmm.fluid.air.GasInventory;
import mmm.thermal.EnvironmentThermalState;
import mmm.thermal.FiniteThermalDeviceDefinition;
import mmm.thermal.FixedSurfaceState;

/** Exercises world HEAD publication, complete carrier round trip and failed publication isolation. */
public final class EnvironmentThermalStoreValidation extends RegionPersistenceValidationSupport {
    public static void main(String[] args) throws Exception {
        var root = Files.createTempDirectory("mmm-environment-checkpoint-");
        try {
            var store = new EnvironmentThermalStore(root, null, null, null, null);
            var gas = new GasInventory(List.of(new GasComponentAmount("mmm:nitrogen", 42, 50,
                    List.of(new GasComponentAmount.SpeciesAmount("mmm:n2", 7)), -3)));
            var solid = new EnvironmentThermalState.Node(new EnvironmentThermalState.Key(0, 1, 2,
                    EnvironmentThermalState.Kind.SOLID, 0), "mmm:stone", 200, 0.5, GasInventory.EMPTY, 123.5,
                    List.of(new FixedSurfaceState(FiniteThermalDeviceDefinition.LocalFace.UP, "mmm:adsorbent", 12,
                            Map.of("mmm:co2", 3L), Map.of("mmm:co2", 0.25))));
            var air = new EnvironmentThermalState.Node(new EnvironmentThermalState.Key(0, 1, 2,
                    EnvironmentThermalState.Kind.AIR, 1), "mmm:air", 50, 0.5, gas, 55, List.of());
            var state = EnvironmentThermalState.initialized("minecraft:overworld", "fingerprint", List.of(solid, air),
                    List.of(new EnvironmentThermalState.Contact(0, 1, 2.5, 0),
                            new EnvironmentThermalState.Contact(1, -1, 1.0, 293.15)));
            if (store.read() != null) throw new AssertionError("New store has a snapshot");
            var region=populatedRegion(java.util.UUID.randomUUID());
            var checkpoint=mmm.simulation.RegionCheckpointFixtures.capture(region,1);
            store.publish(Map.of(state.dimensionId(), state), Map.of(region.id(),checkpoint));
            var loaded=store.read();
            if(loaded.regions().get(region.id()).manifest().checksum()!=checkpoint.manifest().checksum())
                throw new AssertionError("World checkpoint lost region generation");
            if (!state.equals(store.read().environments().get(state.dimensionId()))) throw new AssertionError("Environment round trip changed state");
            var later = new EnvironmentThermalState(state.dimensionId(), 1, state.registryFingerprint(),state.nodes(),state.contacts(),state.audit());
            var pending = store.prepare(Map.of(later.dimensionId(),later),Map.of());
            if (!state.equals(store.read().environments().get(state.dimensionId()))) throw new AssertionError("Staging changed HEAD");
            var stale = store.prepare(Map.of(later.dimensionId(),later),Map.of());
            store.commit(pending);
            try { store.commit(stale); throw new AssertionError("Stale world candidate accepted"); }
            catch (java.io.IOException expected) { }
            store.publish(Map.of(state.dimensionId(),state),Map.of());
            var mirror = new RegionGenerationStore(root);
            final EnvironmentThermalStore.Snapshot[] authority = { null };
            mirror.commitHook(new RegionGenerationStore.CommitHook() {
                public void beforeCommit(RegionState target) throws java.io.IOException {
                    store.publish(Map.of(state.dimensionId(),state),Map.of(target.manifest().regionId(),target));
                    authority[0]=store.read();
                    throw new java.io.IOException("Injected failure after world HEAD");
                }
                public RegionGenerationStore.CommittedIdentity committed(java.util.UUID id) {
                    if(authority[0]==null || !authority[0].regions().containsKey(id))return null;
                    var saved=authority[0].regions().get(id).manifest();
                    return new RegionGenerationStore.CommittedIdentity(saved.checkpointGeneration(),saved.checksum());
                }
            });
            try { mirror.publish(checkpoint); throw new AssertionError("Mirror failure was not injected"); }
            catch (java.io.IOException expected) { }
            if(mirror.physicalCommittedGeneration(region.id())!=0 || mirror.committedGeneration(region.id())!=1
                    || mirror.committedManifestChecksum(region.id())!=checkpoint.manifest().checksum())
                throw new AssertionError("World HEAD was not authoritative after mirror failure");
            mirror.commitHook(null); mirror.publish(authority[0].regions().get(region.id()));
            if(mirror.physicalCommittedGeneration(region.id())!=1)throw new AssertionError("Region mirror did not recover");
            try { store.publish(Map.of("minecraft:wrong", state), Map.of()); throw new AssertionError("Invalid publication accepted"); }
            catch (java.io.IOException expected) { }
            if (!state.equals(store.read().environments().get(state.dimensionId()))) throw new AssertionError("Failed publication changed HEAD");
        } finally { DurableFileOperations.deleteTreeWithin(root.getParent(), root); }
    }
}
