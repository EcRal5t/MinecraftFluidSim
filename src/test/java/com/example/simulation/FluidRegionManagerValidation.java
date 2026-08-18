package com.example.simulation;

import com.example.fluid.FlipFluidDomain;
import com.example.fluid.FluidSolverConfig;
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
			Identifier.fromNamespaceAndPath("realwater", "validation_dimension_registry")
	);
	private static final ResourceKey<Level> DIMENSION = ResourceKey.create(
			DIMENSION_REGISTRY, Identifier.fromNamespaceAndPath("realwater", "validation_dimension")
	);

	private FluidRegionManagerValidation() { }

	public static void main(String[] args) {
		validateAdmissionAndSelection();
		validateRuntimeStateFollowsRegion();
		validateAtomicReplacement();
		validatePerRegionAndTotalRejectionDetails();
		System.out.println("FluidRegionManager validation passed");
	}

	private static void validateRuntimeStateFollowsRegion() {
		FluidRegionManager manager = manager();
		FluidRegion first = region(2, 2, 2, 64L);
		FluidRegion second = region(2, 2, 2, 64L);
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
		require(!first.entityPhysicsEnabled() && first.debugOverlays(),
				"selection lost the region's interaction or rendering state");
	}

	private static void validateAdmissionAndSelection() {
		FluidRegionManager manager = manager();
		FluidRegion first = region(2, 2, 2, 64L);
		FluidRegion second = region(2, 2, 2, 64L);
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
		FluidRegion second = region(2, 2, 2, 64L);
		first.domain().setVolume(0, 0, 0, 1.0F);
		float exactVolume = first.domain().totalVolume();
		require(manager.add(first).accepted() && manager.add(second).accepted(), "replacement setup failed");
		require(manager.select(first.id()), "could not select replacement target");

		FluidRegion rejected = region(3, 3, 2, 128L);
		FluidRegionManager.AdmissionResult rejection = manager.replaceSelected(rejected);
		require(!rejection.accepted(), "oversized replacement should be rejected");
		require(manager.selected() == first && manager.regions().contains(first), "rejected replacement changed the selected region");
		require(first.domain().totalVolume() == exactVolume, "region admission changed exact particle volume");

		FluidRegion replacement = region(2, 2, 2, 48L);
		require(manager.replaceSelected(replacement).accepted(), "valid replacement was rejected");
		require(manager.selected() == replacement && !manager.regions().contains(first), "valid replacement was not atomic");
		require(manager.usage().equals(new FluidRegionManager.BudgetUsage(2, 16L, 112L)), "replacement usage mismatch");
	}

	private static void validatePerRegionAndTotalRejectionDetails() {
		FluidRegionManager manager = manager();
		FluidRegion oversized = region(3, 3, 2, 64L);
		FluidRegionManager.AdmissionResult perRegion = manager.add(oversized);
		require(!perRegion.accepted() && perRegion.failure().contains("requires 18 cells/64 markers")
				&& perRegion.failure().contains("per-region limit 16/128"), "per-region rejection lacks budget detail");

		FluidRegion first = region(2, 2, 2, 96L);
		FluidRegion second = region(2, 2, 2, 96L);
		require(manager.add(first).accepted(), "total-budget setup failed");
		FluidRegionManager.AdmissionResult total = manager.add(second);
		require(!total.accepted() && total.failure().contains("remaining 16/64"), "total rejection lacks remaining budget");
		require(manager.regions().size() == 1 && manager.selected() == first, "rejected add mutated registry");
	}

	private static FluidRegionManager manager() {
		return new FluidRegionManager(new FluidRegionManager.Limits(2, 16L, 128L, 24L, 160L));
	}

	private static FluidRegion region(int nx, int ny, int nz, long reservedMarkers) {
		return new FluidRegion(new UUID(0L, ++nextRegionId), DIMENSION, BlockPos.ZERO,
				new FlipFluidDomain(nx, ny, nz, CONFIG, Math.toIntExact(reservedMarkers)), reservedMarkers);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
