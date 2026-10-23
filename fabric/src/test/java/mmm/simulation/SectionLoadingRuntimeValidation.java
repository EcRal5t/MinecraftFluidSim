package mmm.simulation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import mmm.application.RegionCatalog;
import mmm.fluid.FluidDomainStateAssertions;
import mmm.simulation.persistence.RegionGenerationStore;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/** Uses the existing cross-section fixture to exercise the real runtime adoption path. */
public final class SectionLoadingRuntimeValidation {
	private SectionLoadingRuntimeValidation() { }

	public static void validate(Path root, FluidRegion source) throws Exception {
		var repository = new RegionRepositoryAdapter(root, TestProfiles.pure(), null, null, null, null, TestProfiles.materialRegistry());
		repository.checkpoint(source);
		var expected = source.domain().captureAuthoritativeState();
		var manager = manager();
		require(repository.loadMetadata(manager).isEmpty(), "Manifest discovery rejected complete metadata");
		var target = manager.regions().getFirst();
		require(!target.solverLoaded(), "Metadata discovery allocated a solver");
		var first = repository.restoreIncrementally(target, 100, 1);
		require(first.loadedSections() == 1 && first.totalSections() > 1 && !first.restored() && !target.solverLoaded(),
				"Partial section loading published a solver");
		var repeated = repository.restoreIncrementally(target, 100, 1);
		require(repeated.equals(first), "Repeated service in one tick spent the section budget twice");
		repository.cancelRestore(target.id());
		var restarted = repository.restoreIncrementally(target, 101, 1);
		require(restarted.loadedSections() == 1 && !target.solverLoaded(), "Cancelled loading retained its payload collection");
		for (int section = 1; section < first.totalSections(); section++) {
			var progress = repository.restoreIncrementally(target, 101 + section, 1);
			boolean complete = section + 1 == first.totalSections();
			require(progress.loadedSections() == section + 1 && progress.restored() == complete
					&& target.solverLoaded() == complete, "Runtime adopted an incomplete section collection");
		}
		FluidDomainStateAssertions.assertSameDurableState(expected, target.domain().captureAuthoritativeState(), "Runtime incremental restore");

		Path regionPath = root.resolve("mmm/region-state").resolve(source.id().toString());
		var head = NbtIo.readCompressed(regionPath.resolve("HEAD.nbt"), NbtAccounter.unlimitedHeap());
		Path slot = regionPath.resolve(head.getStringOr("Slot", ""));
		Path missing;
		try (var paths = Files.list(slot)) {
			missing = paths.filter(path -> path.getFileName().toString().startsWith("section_")).sorted().findFirst().orElseThrow();
		}
		byte[] saved = Files.readAllBytes(missing);
		Files.delete(missing);
		var missingManager = manager();
		require(repository.loadMetadata(missingManager).isEmpty(), "Missing payload prevented metadata owner registration");
		var retained = missingManager.regions().getFirst();
		boolean rejected = false;
		for (int tick = 0; tick < first.totalSections(); tick++) {
			try { repository.restoreIncrementally(retained, 200 + tick, 1); }
			catch (IOException expectedFailure) { rejected = true; break; }
		}
		require(rejected && !retained.solverLoaded() && missingManager.region(retained.id()) == retained,
				"Missing section discarded metadata or published a partial solver");
		Files.write(missing, saved);
		for (int tick = 0; tick < first.totalSections(); tick++) repository.restoreIncrementally(retained, 300 + tick, 1);
		require(retained.solverLoaded(), "Repaired sections could not resume from retained metadata");
		FluidDomainStateAssertions.assertSameDurableState(expected, retained.domain().captureAuthoritativeState(), "Repaired runtime section restore");
		require(new RegionGenerationStore(root).discoverValidated().manifests().size() == 1,
				"Complete resumed generation failed strict discovery");
	}

	private static FluidRegionManager manager() {
		return new FluidRegionManager(new RegionCatalog.Limits(4, 10_000, 10_000));
	}
	private static void require(boolean accepted, String message) { if (!accepted) throw new AssertionError(message); }
}
