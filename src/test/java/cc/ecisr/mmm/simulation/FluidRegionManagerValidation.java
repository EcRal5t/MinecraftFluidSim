package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.fluid.FlipFluidDomain;
import cc.ecisr.mmm.fluid.FluidSolverConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.UUID;

/** Deterministic admission, selection and atomic-replacement regression for the in-memory region registry. */
public final class FluidRegionManagerValidation {
	private static long nextRegionId;
	private static final FluidSolverConfig CONFIG = new FluidSolverConfig(
			0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F,
			32, 1.0E-4F, 0.40F, 2.0E-3F, 0.80F
	);
	private static final ResourceKey<Registry<Level>> DIMENSION_REGISTRY = ResourceKey.createRegistryKey(
			Identifier.fromNamespaceAndPath("mmm", "validation_dimension_registry")
	);
	private static final ResourceKey<Level> DIMENSION = ResourceKey.create(
			DIMENSION_REGISTRY, Identifier.fromNamespaceAndPath("mmm", "validation_dimension")
	);

	private FluidRegionManagerValidation() { }

	public static void main(String[] args) {
		validateAdmissionAndSelection();
		validateRuntimeStateFollowsRegion();
		validateAtomicReplacement();
		validateReplacementPreview();
		validatePerRegionAndTotalRejectionDetails();
		validateOverlapRejection();
		validateBlockedRegionDoesNotBlockPeer();
		System.out.println("FluidRegionManager validation passed");
	}

	private static void validateRuntimeStateFollowsRegion() {
		FluidRegionManager manager = manager();
		FluidRegion first = region(2, 2, 2, 64L);
		FluidRegion second = regionAt(4, 0, 0, 2, 2, 2, 64L);
		first.setPaused(false);
		first.incrementTick();
		first.setStepIntervalTicks(3);
		first.setEntityPhysicsEnabled(false);
		first.setDebugOverlays(true);
		require(manager.add(first).accepted() && manager.add(second).accepted(), "runtime-state setup failed");
		require(manager.select(second.id()), "could not select pristine second region");
		require(second.paused() && second.tick() == 0L && second.stepIntervalTicks() == 1,
				"new region inherited pacing state from another region");
		require(second.entityPhysicsEnabled() && !second.debugOverlays(),
				"new region inherited interaction or rendering state from another region");
		require(manager.select(first.id()), "could not reselect first region");
		require(!first.paused() && first.tick() == 1L && first.stepIntervalTicks() == 3,
				"selection lost the region's pacing state");
		require(first.lifecycle().state() == FluidRegionLifecycle.State.REDUCED,
				"selection lost the region's ACTIVE/REDUCED lifecycle state");
		require(!first.entityPhysicsEnabled() && first.debugOverlays(),
				"selection lost the region's interaction or rendering state");
	}

	private static void validateAdmissionAndSelection() {
		FluidRegionManager manager = manager();
		FluidRegion first = region(2, 2, 2, 64L);
		FluidRegion second = regionAt(4, 0, 0, 2, 2, 2, 64L);
		require(manager.add(first).accepted(), "first region should be admitted");
		require(manager.add(second).accepted(), "second region should be admitted");
		require(manager.selected() == first, "first admitted region should remain selected");
		require(manager.select(second.id()) && manager.selected() == second, "explicit selection failed");
		require(manager.usage().equals(new FluidRegionManager.BudgetUsage(2, 16L, 128L)), "budget usage mismatch");
		FluidRegionManager.AdmissionResult countLimit = manager.add(region(2, 2, 2, 8L));
		require(!countLimit.accepted() && countLimit.failure().contains("region-count limit 2"),
				"region-count rejection lacks its configured limit");
		require(!manager.add(first).accepted(), "duplicate region id should be rejected");
		require(manager.usage().equals(new FluidRegionManager.BudgetUsage(2, 16L, 128L)),
				"rejected add changed budget usage");
		require(manager.remove(second.id()) == second && manager.selected() == first, "selected removal fallback failed");
		manager.clear();
		require(manager.selected() == null && manager.usage().regionCount() == 0, "clear did not empty registry");
	}

	private static void validateAtomicReplacement() {
		FluidRegionManager manager = manager();
		FluidRegion first = region(2, 2, 2, 64L);
		FluidRegion second = regionAt(4, 0, 0, 2, 2, 2, 64L);
		first.domain().setVolume(0, 0, 0, 1.0F);
		float exactVolume = first.domain().totalVolume();
		require(manager.add(first).accepted() && manager.add(second).accepted(), "replacement setup failed");
		require(manager.select(first.id()), "could not select replacement target");

		FluidRegion rejected = region(3, 3, 2, 128L);
		FluidRegionManager.AdmissionResult rejection = manager.replaceSelected(rejected);
		require(!rejection.accepted(), "oversized replacement should be rejected");
		require(manager.selected() == first && manager.regions().contains(first), "rejected replacement changed the selected region");
		require(first.domain().totalVolume() == exactVolume, "region admission changed exact particle volume");

		FluidRegion replacement = regionAt(0, 0, 0, 2, 2, 2, 48L);
		require(manager.replaceSelected(replacement).accepted(), "valid replacement was rejected");
		require(manager.selected() == replacement && !manager.regions().contains(first), "valid replacement was not atomic");
		require(manager.usage().equals(new FluidRegionManager.BudgetUsage(2, 16L, 112L)), "replacement usage mismatch");
	}

	private static void validateReplacementPreview() {
		FluidRegionManager manager = manager();
		require(manager.previewAdd(8L, 64L).accepted(), "valid add preview was rejected");
		require(manager.selected() == null && manager.usage().regionCount() == 0,
				"add preview changed an empty registry");
		FluidRegion original = region(2, 2, 2, 64L);
		require(manager.add(original).accepted(), "preview setup failed");
		FluidRegionManager.BudgetUsage usage = manager.usage();
		FluidRegionManager.AdmissionResult addRejected = manager.previewAdd(18L, 128L);
		require(!addRejected.accepted() && addRejected.failure().contains("requires 18 cells/128 markers"),
				"add preview omitted capacity detail");
		require(manager.selected() == original && manager.usage().equals(usage),
				"rejected add preview changed registry state");
		FluidRegionManager.AdmissionResult rejected = manager.previewReplacement(18L, 128L);
		require(!rejected.accepted() && rejected.failure().contains("requires 18 cells/128 markers"),
				"replacement preview omitted capacity detail");
		require(manager.selected() == original && manager.usage().equals(usage),
				"replacement preview changed registry state");
		require(manager.previewReplacement(8L, 64L).accepted(), "valid replacement preview was rejected");
		require(manager.selected() == original && manager.usage().equals(usage),
				"accepted replacement preview changed registry state");
	}

	private static void validatePerRegionAndTotalRejectionDetails() {
		FluidRegionManager manager = manager();
		FluidRegion oversized = region(3, 3, 2, 64L);
		FluidRegionManager.AdmissionResult perRegion = manager.add(oversized);
		require(!perRegion.accepted() && perRegion.failure().contains("requires 18 cells/64 markers")
				&& perRegion.failure().contains("per-region limit 16/128"), "per-region rejection lacks budget detail");

		FluidRegion first = region(2, 2, 2, 96L);
		FluidRegion second = regionAt(4, 0, 0, 2, 2, 2, 96L);
		require(manager.add(first).accepted(), "total-budget setup failed");
		FluidRegionManager.AdmissionResult total = manager.add(second);
		require(!total.accepted() && total.failure().contains("remaining 16/64"), "total rejection lacks remaining budget");
		require(manager.regions().size() == 1 && manager.selected() == first, "rejected add mutated registry");
	}

	private static void validateOverlapRejection() {
		FluidRegionManager manager = manager();
		FluidRegion first = regionAt(0, 0, 0, 2, 2, 2, 64L);
		FluidRegion touching = regionAt(1, 0, 0, 2, 2, 2, 64L);
		require(manager.add(first).accepted() && manager.add(touching).accepted(),
				"face-touching non-overlapping regions should be admitted");
		manager.remove(touching.id());
		FluidRegion overlapping = regionAt(0, 0, 0, 2, 2, 2, 64L);
		FluidRegionManager.AdmissionResult rejection = manager.addAndSelect(overlapping);
		require(!rejection.accepted() && rejection.failure().contains("overlaps existing region"),
				"overlap rejection did not identify its cause");
		require(manager.selected() == first && manager.regions().size() == 1,
				"rejected overlapping add changed selection or registry");
	}

	private static void validateBlockedRegionDoesNotBlockPeer() {
		FluidRegionManager manager = manager();
		FluidRegion blocked = regionAt(0, 0, 0, 2, 2, 2, 64L);
		FluidRegion runnable = regionAt(4, 0, 0, 2, 2, 2, 64L);
		require(manager.add(blocked).accepted() && manager.add(runnable).accepted(),
				"independent tick fixture admission failed");
		blocked.setPaused(false);
		runnable.setPaused(false);
		blocked.setAvailabilityFailure("associated chunk missing");
		for (FluidRegion region : manager.regions()) {
			if (region.canStep()) region.incrementTick();
		}
		require(blocked.tick() == 0L && blocked.paused(), "unavailable region advanced a partial step");
		require(runnable.tick() == 1L && !runnable.paused(), "blocked region prevented its independent peer from advancing");
	}

	private static FluidRegionManager manager() {
		return new FluidRegionManager(new FluidRegionManager.Limits(2, 16L, 128L, 24L, 160L));
	}

	private static FluidRegion region(int nx, int ny, int nz, long reservedMarkers) {
		return regionAt(0, 0, 0, nx, ny, nz, reservedMarkers);
	}

	private static FluidRegion regionAt(int x, int y, int z, int nx, int ny, int nz, long reservedMarkers) {
		return new FluidRegion(new UUID(0L, ++nextRegionId), DIMENSION, new BlockPos(x, y, z),
				new FlipFluidDomain(nx, ny, nz, CONFIG, Math.toIntExact(reservedMarkers)), reservedMarkers);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
