package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.api.RegionAvailabilityReason;
import cc.ecisr.mmm.application.RegionCatalog;
import cc.ecisr.mmm.fluid.FluidDomain;
import cc.ecisr.mmm.fluid.FluidSolverConfig;
import cc.ecisr.mmm.fluid.GasTopologyBlockedException;
import cc.ecisr.mmm.world.CollisionField;
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
		validateCommittedMetadataSurvivesPolicyBudget();
		validateBlockedRegionDoesNotBlockPeer();
		validateCollisionPublication();
		validateClosedGasCollisionRejection();
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
		require(manager.usage().equals(new RegionCatalog.BudgetUsage(2, 16L, 128L)), "budget usage mismatch");
		RegionCatalog.AdmissionResult countLimit = manager.add(region(2, 2, 2, 8L));
		require(!countLimit.accepted() && countLimit.failure().contains("region-count limit 2"),
				"region-count rejection lacks its configured limit");
		require(!manager.add(first).accepted(), "duplicate region id should be rejected");
		require(manager.usage().equals(new RegionCatalog.BudgetUsage(2, 16L, 128L)),
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
		RegionCatalog.AdmissionResult rejection = manager.replaceSelected(rejected);
		require(!rejection.accepted(), "oversized replacement should be rejected");
		require(manager.selected() == first && manager.regions().contains(first), "rejected replacement changed the selected region");
		require(first.domain().totalVolume() == exactVolume, "region admission changed exact particle volume");

		FluidRegion replacement = regionAt(0, 0, 0, 2, 2, 2, 48L);
		require(manager.replaceSelected(replacement).accepted(), "valid replacement was rejected");
		require(manager.selected() == replacement && !manager.regions().contains(first), "valid replacement was not atomic");
		require(manager.usage().equals(new RegionCatalog.BudgetUsage(2, 16L, 112L)), "replacement usage mismatch");
	}

	private static void validateReplacementPreview() {
		FluidRegionManager manager = manager();
		require(manager.previewAdd(8L, 64L).accepted(), "valid add preview was rejected");
		require(manager.selected() == null && manager.usage().regionCount() == 0,
				"add preview changed an empty registry");
		FluidRegion original = region(2, 2, 2, 64L);
		require(manager.add(original).accepted(), "preview setup failed");
		RegionCatalog.BudgetUsage usage = manager.usage();
		RegionCatalog.AdmissionResult addRejected = manager.previewAdd(18L, 128L);
		require(!addRejected.accepted() && addRejected.failure().contains("requires 18 cells/128 markers"),
				"add preview omitted capacity detail");
		require(manager.selected() == original && manager.usage().equals(usage),
				"rejected add preview changed registry state");
		RegionCatalog.AdmissionResult rejected = manager.previewReplacement(18L, 128L);
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
		RegionCatalog.AdmissionResult perRegion = manager.add(oversized);
		require(!perRegion.accepted() && perRegion.failure().contains("requires 18 cells/64 markers")
				&& perRegion.failure().contains("per-region limit 16/128"), "per-region rejection lacks budget detail");

		FluidRegion first = region(2, 2, 2, 96L);
		FluidRegion second = regionAt(4, 0, 0, 2, 2, 2, 96L);
		require(manager.add(first).accepted(), "total-budget setup failed");
		RegionCatalog.AdmissionResult total = manager.add(second);
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
		RegionCatalog.BudgetUsage before = manager.usage();
		RegionCatalog.AdmissionResult preview = manager.previewAdd(overlapping);
		require(!preview.accepted() && preview.reason() == RegionAvailabilityReason.OVERLAP
				&& preview.failure().contains("overlaps existing region")
				&& manager.usage().equals(before) && manager.selected() == first,
				"complete add preview did not reject overlap without registering the candidate");
		RegionCatalog.AdmissionResult rejection = manager.addAndSelect(overlapping);
		require(!rejection.accepted() && rejection.failure().contains("overlaps existing region"),
				"overlap rejection did not identify its cause");
		require(manager.selected() == first && manager.regions().size() == 1,
				"rejected overlapping add changed selection or registry");
	}

	private static void validateCommittedMetadataSurvivesPolicyBudget() {
		FluidRegionManager manager = manager();
		FluidRegion first = regionAt(0, 0, 0, 2, 2, 2, 96L);
		FluidRegion overBudget = regionAt(4, 0, 0, 2, 2, 2, 96L);
		require(manager.registerDiscovered(first).accepted() && manager.registerDiscovered(overBudget).accepted(),
				"valid committed metadata was discarded by runtime policy");
		require(manager.regions().size() == 2 && !manager.runtimeAdmission(first).accepted()
				&& manager.runtimeAdmission(first).reason() == RegionAvailabilityReason.BUDGET
				&& !manager.runtimeAdmission(overBudget).accepted(),
				"over-budget committed reservations were not retained in PAUSED_BUDGET state");
	}

	private static void validateBlockedRegionDoesNotBlockPeer() {
		FluidRegionManager manager = manager();
		FluidRegion blocked = regionAt(0, 0, 0, 2, 2, 2, 64L);
		FluidRegion runnable = regionAt(4, 0, 0, 2, 2, 2, 64L);
		require(manager.add(blocked).accepted() && manager.add(runnable).accepted(),
				"independent tick fixture admission failed");
		blocked.setPaused(false);
		runnable.setPaused(false);
		blocked.setAvailabilityFailure(RegionAvailabilityReason.CHUNK_MISSING, "associated chunk missing");
		for (FluidRegion region : manager.regions()) {
			if (region.canStep()) region.incrementTick();
		}
		require(blocked.tick() == 0L && !blocked.paused()
				&& blocked.availabilityReason() == RegionAvailabilityReason.CHUNK_MISSING,
				"availability block advanced a partial step or overwrote running operator intent");
		require(runnable.tick() == 1L && !runnable.paused(), "blocked region prevented its independent peer from advancing");
		blocked.blockCollision("moving body overlaps the committed region");
		require(!blocked.paused() && blocked.availabilityReason() == RegionAvailabilityReason.BODY_OVERLAP,
				"runtime safety block overwrote operator intent or lost its closed reason");
	}

	private static FluidRegionManager manager() {
		return new FluidRegionManager(new RegionCatalog.Limits(2, 16L, 128L, 24L, 160L));
	}

	private static void validateCollisionPublication() {
		FluidRegion region = region(2, 2, 2, 64L);
		FluidDomain original = region.domain();
		original.setVolume(0, 0, 0, 0.5F);
		float volume = original.totalVolume();
		long generation = region.solverGeneration();
		byte[] masks = new byte[8];
		CollisionField empty = new CollisionField(2, 2, 2, masks);
		require(FluidRegionWorldInteraction.applyCapturedCollisions(region, empty, 2).changedCells() == 0
				&& region.domain() == original && region.solverGeneration() == generation,
				"unchanged collision capture replaced the live solver");
		masks[0] = (byte) 255;
		CollisionField occupied = new CollisionField(2, 2, 2, masks);
		FluidRegion.CollisionStats stats = FluidRegionWorldInteraction.applyCapturedCollisions(region, occupied, 2);
		require(stats.changedCells() == 1 && stats.displacedCellVolume() == volume
				&& stats.escapedCellVolume() == 0.0F && region.domain().totalVolume() == volume,
				"collision successor lost displaced liquid or reported uncommitted statistics");
		require(region.domain() != original && region.solverGeneration() == generation + 1
				&& region.domain().solidMicroMask(0, 0, 0) == 255
				&& original.solidMicroMask(0, 0, 0) == 0 && original.totalVolume() == volume,
				"collision publication mutated its original or failed to advance the solver generation");
		FluidDomain committed = region.domain();
		FluidDomain stale = FluidDomain.restoreAuthoritativeState(original.captureAuthoritativeState());
		try {
			region.adoptCollisionCandidate(original, generation, stale);
			throw new AssertionError("stale collision candidate was accepted");
		} catch (IllegalStateException expected) {
			require(region.domain() == committed && region.solverGeneration() == generation + 1,
					"stale collision candidate changed the committed solver");
		}
	}

	private static void validateClosedGasCollisionRejection() {
		FluidRegion region = region(2, 2, 2, 64L);
		FluidDomain original = region.domain();
		original.sealDomainBoundary();
		var before = original.bootstrapGasState(null);
		require(!before.nodes().isEmpty(), "closed gas collision fixture has no finite inventory");
		long generation = region.solverGeneration();
		byte[] masks = new byte[8];
		java.util.Arrays.fill(masks, (byte) 255);
		try {
			FluidRegionWorldInteraction.applyCapturedCollisions(region, new CollisionField(2, 2, 2, masks), 2);
			throw new AssertionError("collision removed every cavity holding closed gas");
		} catch (FluidDomain.CollisionEditBlockedException expected) {
			require(expected.getCause() instanceof GasTopologyBlockedException,
					"gas-capacity rejection lost its typed physical cause");
		}
		require(region.domain() == original && region.solverGeneration() == generation,
				"rejected gas collision published a partial successor");
		for (int z = 0; z < 2; z++) for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) {
			require(original.solidMicroMask(x, y, z) == 0, "rejected collision changed live geometry");
		}
		var after = original.captureAuthoritativeState().gas();
		require(before.nodes().equals(after.nodes()) && before.externalSource().equals(after.externalSource())
				&& before.escapedSink().equals(after.escapedSink())
				&& before.massRoundoff().equals(after.massRoundoff())
				&& before.residualRoundoff().equals(after.residualRoundoff())
				&& java.util.Arrays.equals(before.pieceNodeOrdinal(), after.pieceNodeOrdinal()),
				"rejected collision changed closed gas inventory, audit or ownership");
		// Removing the offending world block must allow a later refresh without repairing the live domain.
		masks[0] = 0;
		require(FluidRegionWorldInteraction.applyCapturedCollisions(region,
				new CollisionField(2, 2, 2, masks), 2).changedCells() == 7,
				"a corrected collision capture could not be committed after rejection");
		require(region.solverGeneration() == generation + 1
				&& region.domain().captureAuthoritativeState().gas().nodes().equals(before.nodes()),
				"valid closed-gas compression changed the finite inventory");
	}

	private static FluidRegion region(int nx, int ny, int nz, long reservedMarkers) {
		return regionAt(0, 0, 0, nx, ny, nz, reservedMarkers);
	}

	private static FluidRegion regionAt(int x, int y, int z, int nx, int ny, int nz, long reservedMarkers) {
		return new FluidRegion(new UUID(0L, ++nextRegionId), DIMENSION, new BlockPos(x, y, z),
				new FluidDomain(nx, ny, nz, CONFIG, Math.toIntExact(reservedMarkers)), reservedMarkers);
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
