package mmm.simulation;

import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import mmm.application.RegionCatalog;
import mmm.fluid.HostFluidState;
import mmm.fluid.FluidSolverConfig;
import mmm.network.DemoAction;
import mmm.network.DemoActionPayload;
import mmm.network.DemoPreset;
import mmm.network.DemoWorkbenchPayload;
import mmm.simulation.controller.FluidRegionFootprint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Exercises the actual workbench request state machine with a deterministic world adapter. */
public final class DemoInteractionValidation {
	private DemoInteractionValidation() { }
	public static void main(String[] args) throws Exception {
		validatePlantBudget();
		var manager = new FluidRegionManager(new RegionCatalog.Limits(8, 65536, 131072));
		var root = Files.createTempDirectory("mmm-demo-interaction-");
		var store = new DemoWorkbenchStore(root);
		var workbench = new DemoWorkbench(manager, store);
		var host = new TestHost(manager);
		var opened = workbench.open(host);
		host.admin = false;
		var denied = request(workbench, host, opened, DemoPreset.WATER, DemoAction.START);
		require(denied.failure() != null && manager.regions().isEmpty(), "Unauthorized player created a demo");
		host.admin = true; host.ready = false;
		var unavailable = request(workbench, host, denied, DemoPreset.WATER, DemoAction.START);
		require(unavailable.failure() != null && manager.regions().isEmpty(), "Startup failure created a demo");
		host.ready = true;
		BlockPos blocked = host.position;
		host.failCreate = true;
		var failed = request(workbench, host, unavailable, DemoPreset.WATER, DemoAction.START);
		require(failed.failure() != null && failed.regionId() == null, "Blocked creation reported success");
		host.position = blocked.offset(32, 0, 0); host.failCreate = false;
		var started = request(workbench, host, failed, DemoPreset.WATER, DemoAction.START);
		require(started.failure() == null && started.regionId() != null && !started.paused(), "Water start failed");
		var id = started.regionId();
		require(host.lastAnchor.equals(host.position) && !host.lastAnchor.equals(blocked), "Retry retained failed creation location");
		require(store.loadAll().size() == 1 && store.loadAll().getFirst().region().equals(id), "Tank identity was not saved");
		host.failPause = true;
		var pauseFailure = request(workbench, host, started, DemoPreset.WATER, DemoAction.PAUSE);
		require(pauseFailure.failure() != null && !manager.region(id).paused(), "Failed pause was reported as successful");
		host.failPause = false;
		var stale = workbench.execute(host, new DemoActionPayload(pauseFailure.sessionId(), pauseFailure.generation(), id, DemoPreset.WATER, DemoAction.PAUSE));
		var staleAgain = workbench.execute(host, new DemoActionPayload(pauseFailure.sessionId(), pauseFailure.generation(), id, DemoPreset.WATER, DemoAction.PAUSE));
		require(stale.failure() == null && staleAgain.failure() != null && manager.region(id).paused(), "Replayed request changed the region");
		var current = workbench.open(host);
		var single = request(workbench, host, current, DemoPreset.WATER, DemoAction.STEP);
		require(single.failure() == null && host.steps == 1 && manager.region(id).paused(), "Paused single step failed");
		host.near = false;
		var away = request(workbench, host, single, DemoPreset.WATER, DemoAction.START);
		require(away.failure() != null && manager.region(id).paused(), "Distant player changed the demo");
		host.near = true;
		host.failRecipe = true;
		var rejectedRecipe = request(workbench, host, away, DemoPreset.CHEMISTRY, DemoAction.RESET);
		require(rejectedRecipe.failure() != null && id.equals(rejectedRecipe.regionId()) && !store.loadAll().getFirst().recipeReady(),
				"Failed recipe lost its recoverable identity");
		host.failRecipe = false;
		var chemistry = request(workbench, host, rejectedRecipe, DemoPreset.CHEMISTRY, DemoAction.RESET);
		require(chemistry.failure() == null && chemistry.paused() && id.equals(chemistry.regionId())
				&& store.loadAll().getFirst().preset() == DemoPreset.CHEMISTRY, "Recipe reset did not retain tank identity");
		var resumed = request(workbench, host, chemistry, DemoPreset.CHEMISTRY, DemoAction.START);
		require(resumed.failure() == null && !resumed.paused(), "Start did not resume the selected recipe");
		var persisted = new DemoWorkbench(manager, store);
		var reopened = persisted.open(host);
		require(id.equals(reopened.regionId()) && reopened.preset() == DemoPreset.CHEMISTRY, "Reopening lost saved tank identity");
		host.dimension = "minecraft:the_nether";
		var otherDimension = persisted.open(host);
		require(otherDimension.regionId() == null && !otherDimension.sessionId().equals(reopened.sessionId()), "Dimension reused another session");
		var wrongDimension = persisted.execute(host, new DemoActionPayload(reopened.sessionId(), reopened.generation(), id,
				DemoPreset.CHEMISTRY, DemoAction.REMOVE));
		require(wrongDimension.failure() != null && manager.region(id) != null, "Old dimension request removed a tank");
		host.dimension = "minecraft:overworld";
		var removal = request(persisted, host, persisted.open(host), DemoPreset.CHEMISTRY, DemoAction.REMOVE);
		require(removal.failure() == null && manager.region(id) == null && host.shell.isEmpty() && store.loadAll().isEmpty(),
				"Removal left the region, shell or durable identity");
		host.position = host.position.offset(32, 0, 0);
		var recreated = request(persisted, host, removal, DemoPreset.WATER, DemoAction.RESET);
		require(recreated.failure() == null && !id.equals(recreated.regionId()) && host.lastAnchor.equals(host.position),
				"Removed demo was not recreated at the player's new position");
		host.failPreflight = true;
		var blockedSwitch = request(persisted, host, recreated, DemoPreset.FLOW, DemoAction.RESET);
		require(blockedSwitch.failure() != null && recreated.regionId().equals(blockedSwitch.regionId())
				&& manager.region(recreated.regionId()) != null && !host.shell.isEmpty(),
				"Blocked cross-type replacement removed the existing tank or shell");
		host.failPreflight = false;
		BlockPos flowOrigin = DemoWorkbench.transientOrigin(host.position, host.facing(), DemoPreset.FLOW);
		var other = new FluidRegion(UUID.randomUUID(), host.key(), flowOrigin.offset(0, 0, 4), host.domain());
		require(manager.add(other).accepted(), "Overlapping replacement fixture admission failed");
		var overlap = request(persisted, host, blockedSwitch, DemoPreset.FLOW, DemoAction.RESET);
		require(overlap.failure() != null && manager.region(recreated.regionId()) != null && !host.shell.isEmpty(),
				"Overlapping destination removed the existing tank");
		manager.remove(other.id());
		var flow = request(persisted, host, overlap, DemoPreset.FLOW, DemoAction.RESET);
		require(flow.failure() == null && flow.paused() && manager.region(recreated.regionId()) == null && host.shell.isEmpty(),
				"Switching to flow left the persistent tank");
		var firstFlow = flow.regionId();
		var pressure = request(persisted, host, flow, DemoPreset.PRESSURE, DemoAction.RESET);
		require(pressure.failure() == null && !firstFlow.equals(pressure.regionId()) && manager.region(firstFlow) == null,
				"Transient reset did not replace the previous region");
		var removed = request(persisted, host, pressure, DemoPreset.PRESSURE, DemoAction.REMOVE);
		require(removed.failure() == null && manager.regions().isEmpty(), "Transient removal left a region");
		validateAllPresets(persisted, host);
		validateExternalRemoval(persisted, host);
		var mismatchId = UUID.randomUUID();
		var mismatch = new FluidRegion(mismatchId, host.key(), host.position, host.domain());
		require(manager.add(mismatch).accepted(), "Mismatch fixture admission failed");
		store.save(java.util.List.of(new DemoWorkbenchStore.Entry(host.player, host.dimension, mismatchId,
				host.position, host.facing(), DemoPreset.WATER, true)));
		var mismatched = new DemoWorkbench(manager, store);
		var invalid = mismatched.open(host);
		require(invalid.failure() != null && invalid.failure().contains("布局"), "Mismatched stored layout did not report its failure");
		var rejected = request(mismatched, host, invalid, DemoPreset.WATER, DemoAction.REMOVE);
		require(rejected.failure() != null && manager.region(mismatchId) == mismatch, "Mismatched identity changed a live region");
		System.out.println("Demo interaction validation passed");
	}
	private static void validatePlantBudget() {
		var limits = new RegionCatalog.Limits(4, 2048, 4096);
		MinecraftRegionActivation.checkSelectionBudget(new RegionCatalog.BudgetUsage(2, 2048), limits, new long[]{1024, 1024});
		for (var usage : java.util.List.of(new RegionCatalog.BudgetUsage(3, 1024), new RegionCatalog.BudgetUsage(2, 3072))) {
			boolean rejected = false;
			try { MinecraftRegionActivation.checkSelectionBudget(usage, limits, new long[]{1024, 1024}); }
			catch (IllegalStateException expected) { rejected = true; }
			require(rejected, "Two-unit plant exceeded the available region budget");
		}
	}
	private static void validateAllPresets(DemoWorkbench workbench, TestHost host) {
		for (var preset : DemoPreset.values()) {
			var reset = request(workbench, host, workbench.open(host), preset, DemoAction.RESET);
			require(reset.failure() == null && reset.paused(), "Initial reset failed for " + preset);
			UUID first = reset.regionId(); BlockPos anchor = host.lastAnchor;
			var start = request(workbench, host, reset, preset, DemoAction.START);
			require(start.failure() == null && !start.paused(), "Start failed for " + preset);
			var illegalStep = request(workbench, host, start, preset, DemoAction.STEP);
			require(illegalStep.failure() != null && !illegalStep.paused(), "Running single step was accepted for " + preset);
			var pause = request(workbench, host, illegalStep, preset, DemoAction.PAUSE);
			require(pause.failure() == null && pause.paused(), "Pause failed for " + preset);
			host.failStep = true;
			var rejected = request(workbench, host, pause, preset, DemoAction.STEP);
			require(rejected.failure() != null && rejected.paused(), "Failed step changed pause state for " + preset);
			host.failStep = false;
			var step = request(workbench, host, rejected, preset, DemoAction.STEP);
			require(step.failure() == null && step.paused(), "Paused step failed for " + preset);
			if (!preset.tank()) {
				host.failTransient = true;
				var failure = request(workbench, host, step, preset, DemoAction.RESET);
				require(failure.failure() != null && first.equals(failure.regionId()) && host.manager.region(first) != null,
						"Failed transient replacement lost its previous region for " + preset);
				host.failTransient = false;
				step = workbench.open(host);
				require(step.failure() == null && first.equals(step.regionId()), "Failed replacement marked the old demo incomplete for " + preset);
			}
			host.position = host.position.offset(1, 0, 0);
			var again = request(workbench, host, step, preset, DemoAction.RESET);
			require(again.failure() == null && again.paused() && anchor.equals(host.lastAnchor), "Reset moved the demo for " + preset);
			require(preset.tank() == first.equals(again.regionId()), "Reset did not preserve the correct region identity for " + preset);
			var removed = request(workbench, host, again, preset, DemoAction.REMOVE);
			require(removed.failure() == null && host.manager.regions().isEmpty(), "Removal failed for " + preset);
			host.position = host.position.offset(24, 0, 0);
			var rebuilt = request(workbench, host, removed, preset, DemoAction.RESET);
			require(rebuilt.failure() == null && host.position.equals(host.lastAnchor), "Rebuild used an old anchor for " + preset);
			require(request(workbench, host, rebuilt, preset, DemoAction.REMOVE).failure() == null, "Rebuilt removal failed for " + preset);
		}
	}
	private static void validateExternalRemoval(DemoWorkbench workbench, TestHost host) {
		var created = request(workbench, host, workbench.open(host), DemoPreset.WATER, DemoAction.RESET);
		UUID old = created.regionId(); host.manager.remove(old);
		host.failVerifyRemoved = true;
		var unconfirmed = request(workbench, host, created, DemoPreset.WATER, DemoAction.RESET);
		require(unconfirmed.failure() != null && old.equals(unconfirmed.regionId()) && !host.shell.isEmpty(),
				"Unconfirmed external removal discarded durable identity or shell");
		host.failVerifyRemoved = false;
		var rebuilt = request(workbench, host, unconfirmed, DemoPreset.WATER, DemoAction.RESET);
		require(rebuilt.failure() == null && !old.equals(rebuilt.regionId()), "Confirmed external removal did not permit reset");
		host.manager.remove(rebuilt.regionId());
		var removed = request(workbench, host, rebuilt, DemoPreset.WATER, DemoAction.REMOVE);
		require(removed.failure() == null && removed.regionId() == null && host.shell.isEmpty(), "External removal was not reconciled");
	}
	private static DemoWorkbenchPayload request(DemoWorkbench workbench, TestHost host, DemoWorkbenchPayload previous,
			DemoPreset preset, DemoAction action) {
		return workbench.execute(host, new DemoActionPayload(previous.sessionId(), previous.generation(), previous.regionId(), preset, action));
	}
	private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
	private static final class TestHost implements DemoWorkbench.Host {
		private final FluidRegionManager manager;
		private final UUID player = UUID.randomUUID();
		private final Set<Long> shell = new HashSet<>();
		String dimension = "minecraft:overworld";
		BlockPos position = new BlockPos(0, 64, 0), lastAnchor;
		boolean near = true, admin = true, ready = true, failCreate, failRecipe, failPause, failStep, failTransient, failVerifyRemoved, failPreflight;
		int steps;
		TestHost(FluidRegionManager manager) { this.manager = manager; }
		public UUID playerId() { return player; }
		public String dimension() { return dimension; }
		public BlockPos position() { return position; }
		public Direction facing() { return Direction.SOUTH; }
		public boolean serverThread() { return true; }
		public boolean administrator() { return admin; }
		public boolean writesReady() { return ready; }
		public boolean near(FluidRegion region) { return near; }
		public boolean nearController(BlockPos controller) { return near; }
		public void registerTank(FluidRegion region) { }
		public void preflightReplacement(FluidRegion previous, ControllerDemoLayout oldTank, BlockPos anchor, Direction facing, DemoPreset preset) {
			var target = MinecraftDebugFixtures.preflightSpace(anchor, facing, preset, position -> {
				if (failPreflight) throw new IllegalStateException("destination occupied");
				if (shell.contains(position.asLong()) && (oldTank == null || !oldTank.shell().contains(position)))
					throw new IllegalStateException("another shell occupies the destination");
			});
			var result = manager.previewReplacement(key(), target.origin(), target.sizeX(), target.sizeY(), target.sizeZ(), target.cells());
			if (!result.accepted()) throw new IllegalStateException(result.failure());
		}
		public void createTank(BlockPos anchor, Direction facing) {
			if (failCreate) throw new IllegalStateException("world occupied");
			lastAnchor = anchor; var layout = ControllerDemoLayout.at(anchor, facing);
			for (var block : layout.shell()) shell.add(block.asLong());
            var footprint = FluidRegionFootprint.box(layout.minimum(), layout.minimum().offset(3, 3, 3));
			var region = new FluidRegion(UUID.randomUUID(), key(), footprint.minimumInterior(), domain(), footprint);
			require(manager.addAndSelect(region).accepted(), "Tank admission failed");
		}
		public void createTransient(BlockPos origin, DemoPreset preset) {
			if (failTransient) throw new IllegalStateException("world occupied");
			int sx = preset == DemoPreset.FLOW ? DemoSceneLayout.FLOW_SIZE_X : DemoSceneLayout.AIR_SIZE_X;
			int sz = preset == DemoPreset.FLOW ? DemoSceneLayout.FLOW_SIZE_Z : DemoSceneLayout.AIR_SIZE_Z;
			lastAnchor = origin.offset(sx / 2, 0, sz / 2).relative(facing().getOpposite(), 9);
			var selected = manager.selected();
			var region = new FluidRegion(UUID.randomUUID(), key(), origin, domain());
			var result = selected != null && !selected.hasSelectedVolume() ? manager.replaceSelected(region) : manager.addAndSelect(region);
			require(result.accepted(), "Transient admission failed");
		}
		public void applyTank(FluidRegion region, DemoPreset preset) {
			if (failRecipe) throw new IllegalStateException("recipe failed");
			region.setPaused(true);
		}
		public void removeTank(FluidRegion region) { manager.remove(region.id()); }
		public void removeTransient() { manager.remove(manager.selected().id()); }
		public void clearShell(ControllerDemoLayout layout) { for (var block : layout.shell()) shell.remove(block.asLong()); }
		public void verifyRemoved(UUID region) { if (failVerifyRemoved || manager.region(region) != null) throw new IllegalStateException("not removed"); }
		public void giveSupplies() { }
		public boolean setPaused(boolean paused) { if (failPause) return false; manager.selected().setPaused(paused); return true; }
		public int step() { if (failStep) return 0; steps++; return 1; }
		private ResourceKey<Level> key() { return ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension)); }
		private HostFluidState domain() { return new HostFluidState(4, 4, 4, new FluidSolverConfig(.5F, 9.81F, .05F, 48, 1e-4F, .002F), mmm.fluid.TestFluidDefinitions.load()); }
	}
}
