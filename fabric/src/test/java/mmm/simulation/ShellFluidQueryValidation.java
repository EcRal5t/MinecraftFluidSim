package mmm.simulation;

import mmm.api.RegionAvailabilityReason;
import mmm.api.query.FluidCellObservation;
import mmm.api.query.FluidQueryResult;
import mmm.api.query.FluidQueryStatus;

import mmm.fluid.HostFluidState;
import mmm.fluid.FluidDomainStateAssertions;
import mmm.fluid.FluidSolverConfig;
import mmm.simulation.controller.FluidRegionFootprint;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;

/** Selection gate for the two cells touching one observed shell face. */
public final class ShellFluidQueryValidation {
	private ShellFluidQueryValidation() { }

	public static void main(String[] args) {
		validateDirectionalContainerPorts();
		validateControllerDemoLayout();
		validateCapturedWaterSampling();
		FluidQueryResult outside = FluidQueryResult.absent(FluidQueryStatus.NO_REGION, "outside");
		FluidQueryResult empty = FluidQueryResult.absent(FluidQueryStatus.EMPTY, "empty interior");
		FluidQueryResult liquid = FluidQueryResult.ok(new FluidCellObservation(
				"minecraft:overworld", UUID.randomUUID(), 3L, 1, 2, 3, 0.25,
				0.01, 10.0, 1_000.0, OptionalDouble.of(293.15),
				List.of(new FluidCellObservation.MaterialAmount("mmm:water", 0.01, 10.0)), List.of()));

		require(ShellFluidQuery.select(outside, liquid) == liquid,
				"query did not select the unique interior through the shell");
		require(ShellFluidQuery.select(empty, outside) == empty,
				"empty authoritative interior was discarded as if no region existed");
		FluidQueryResult ambiguous = ShellFluidQuery.select(empty, liquid);
		require(ambiguous.status() == FluidQueryStatus.REGION_UNAVAILABLE
				&& ambiguous.availabilityReason() == RegionAvailabilityReason.OVERLAP
				&& ambiguous.observation() == null,
				"two claimed shell sides did not fail closed as OVERLAP");
		FluidQueryResult locked = FluidQueryResult.unavailable(
				RegionAvailabilityReason.PROFILE_LOCKED, "profile locked");
		require(ShellFluidQuery.select(locked, locked).availabilityReason()
				== RegionAvailabilityReason.PROFILE_LOCKED,
				"global profile lock was misclassified as a local shell overlap");

		java.util.ArrayList<String> visited = new java.util.ArrayList<>();
		int[] axial = FluidWorldTargetResolver.firstMatchingCell(
				0.25, 1.5, 1.5, 1.0, 0.0, 0.0, 5, 4, 4,
				(x, y, z) -> { visited.add(x + "," + y + "," + z); return x == 3; });
		require(java.util.Arrays.equals(axial, new int[] {3, 1, 1})
				&& visited.equals(List.of("0,1,1", "1,1,1", "2,1,1", "3,1,1")),
				"grid-boundary traversal skipped or duplicated an axial cell");
		visited.clear();
		int[] diagonal = FluidWorldTargetResolver.firstMatchingCell(
				0.5, 0.5, 1.5, 1.0, 1.0, -0.0, 4, 4, 3,
				(x, y, z) -> { visited.add(x + "," + y + "," + z); return x == 2 && y == 2; });
		require(java.util.Arrays.equals(diagonal, new int[] {2, 2, 1})
				&& visited.equals(List.of("0,0,1", "1,1,1", "2,2,1")),
				"corner traversal sampled a cell touched only at zero thickness");
		require(FluidWorldTargetResolver.firstMatchingCell(
				0.5, 0.5, 0.5, 0.0, -0.0, 0.0, 2, 2, 2, (x, y, z) -> true) == null,
				"zero-length probe ray was accepted");
		System.out.println("Shell fluid query validation passed");
	}

	private static void validateCapturedWaterSampling() {
		var dimension = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
				Identifier.fromNamespaceAndPath("mmm", "capture"));
		var domain = new HostFluidState(4, 4, 4, new FluidSolverConfig(.5F, 1000, 9.81F, .05F, 240, 1e-4F, .002F));
		var region = new FluidRegion(UUID.randomUUID(), dimension, BlockPos.ZERO, domain);
		require(FluidRegionFactory.captureWater(region, position -> 0.0F) == 0 && domain.totalVolume() == 0.0F,
				"Empty world produced captured liquid");
		require(FluidRegionFactory.captureWater(region, position -> position.equals(BlockPos.ZERO) ? 1.0F : 0.0F) == 8,
				"A source block did not fill its eight solver cells");
		require(domain.totalVolume() == 8.0F, "Captured source block did not retain one cubic metre");
	}

	private static void validateControllerDemoLayout() {
		var dimension = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
				Identifier.fromNamespaceAndPath("mmm", "demo"));
		for (Direction facing : List.of(Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST)) {
			var layout = ControllerDemoLayout.at(new BlockPos(16, 64, 16), facing);
			Set<Long> shell = new HashSet<>();
			for (var block : layout.shell()) shell.add(block.asLong());
			var world = new mmm.simulation.controller.FluidBoundaryScanner.ScanWorld() {
				public boolean isInsideBuildHeight(BlockPos p) { return p.getY() >= -64 && p.getY() < 320; }
				public boolean isChunkLoaded(int x, int z) { return true; }
				public boolean isBoundary(BlockPos p) { return shell.contains(p.asLong()); }
			};
			var scan = mmm.simulation.controller.FluidBoundaryScanner.scan(layout.controller(), world, 512);
			require(scan.succeeded(), "Controller demo scan failed: " + facing + " " + scan.failure());
			var footprint = scan.footprint();
			require(footprint.interiorBlocks().size() == 8, "Controller demo interior changed: " + facing);
			var domain = new HostFluidState(4, 4, 4, new FluidSolverConfig(.5F, 1000, 9.81F, .05F, 240, 1e-4F, .002F));
			var region = new FluidRegion(UUID.randomUUID(), dimension, footprint.minimumInterior(), domain, footprint);
			require(DemoWorkbench.matchesTank(region, layout, dimension.identifier().toString()),
					"Restored demo footprint did not match its layout: " + facing);
			require(!DemoWorkbench.matchesTank(region, layout, "mmm:other"), "Demo matched a different dimension");
			var interior = new java.util.HashSet<>(footprint.interiorBlocks());
			interior.remove(interior.iterator().next());
			var changedFootprint = new mmm.simulation.controller.FluidRegionFootprint(footprint.controllerPosition(),
					footprint.minimumInterior(), footprint.maximumInterior(), interior, footprint.boundaryBlocks(), footprint.requiredChunks());
			var changed = new FluidRegion(UUID.randomUUID(), dimension, footprint.minimumInterior(),
					new HostFluidState(4, 4, 4, new FluidSolverConfig(.5F, 1000, 9.81F, .05F, 240, 1e-4F, .002F)), changedFootprint);
			require(!DemoWorkbench.matchesTank(changed, layout, dimension.identifier().toString()),
					"Demo accepted an altered interior footprint");
			var inward = layout.portOutward().getOpposite();
			var target = FluidWorldTargetResolver.resolvePortTarget(List.of(region), dimension,
					layout.port(), layout.portOutward(), inward);
			require(target != null && target.interiorBlock().equals(layout.port().relative(inward)),
					"Controller demo port did not reach its interior: " + facing);
			require(FluidWorldTargetResolver.resolvePortTarget(List.of(region), dimension,
					layout.port(), layout.portOutward(), layout.portOutward()) == null,
					"Controller demo port accepted the rear face: " + facing);
		}
	}

	private static void validateDirectionalContainerPorts() {
		ResourceKey<Registry<Level>> registry =
				ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath("mmm", "port_validation_registry"));
		var dimension = ResourceKey.create(registry,
				Identifier.fromNamespaceAndPath("mmm", "ports"));
		var otherDimension = ResourceKey.create(registry,
				Identifier.fromNamespaceAndPath("mmm", "other_ports"));
		var source = portRegion(new UUID(631, 641), dimension);
		var initial = source.editableHost().captureAuthoritativeState();
		var lifecycle = source.lifecycle().state();
		for (var outward : Direction.values()) {
			var port = portPosition(outward);
			var inward = outward.getOpposite();
			for (var requested : Direction.values()) {
				var target = FluidWorldTargetResolver.resolvePortTarget(List.of(source), dimension, port, outward, requested);
				if (requested == inward) require(target != null && target.region() == source
						&& target.interiorBlock().equals(port.relative(inward)), "Port front did not select its immediate interior: " + outward);
				else require(target == null, "Back or side approach crossed a directional port: " + outward + "/" + requested);
			}
			require(FluidWorldTargetResolver.resolvePortTarget(List.of(source), dimension, port, null, inward) == null,
					"Ordinary wall without a port facing admitted a container");
			require(FluidWorldTargetResolver.resolvePortTarget(List.of(source), dimension, port, outward, null) == null,
					"Container with no approach direction crossed a port");
			require(FluidWorldTargetResolver.resolvePortTarget(List.of(source), otherDimension, port, outward, inward) == null,
					"Port selected an owner in another dimension");
		}
		require(FluidWorldTargetResolver.resolvePortTarget(List.of(source), dimension,
				new BlockPos(-1, -1, -1), Direction.WEST, Direction.EAST) == null,
				"Corner port guessed a diagonal interior");
		var overlapping = portRegion(new UUID(643, 647), dimension);
		require(FluidWorldTargetResolver.resolvePortTarget(List.of(source, overlapping), dimension,
				portPosition(Direction.WEST), Direction.WEST, Direction.EAST) == null,
				"Ambiguous port selected one of two owners");
		FluidDomainStateAssertions.assertSameState(initial, source.editableHost().captureAuthoritativeState(), "Read-only port resolution");
		require(source.lifecycle().state() == lifecycle, "Port lookup changed the region lifecycle");
		require(source.recordPublishedCheckpoint(1) && source.releaseSolverAfterCheckpoint(1), "Port metadata fixture could not unload");
		require(FluidWorldTargetResolver.resolvePortTarget(List.of(source), dimension,
				portPosition(Direction.WEST), Direction.WEST, Direction.EAST) == null
				&& !source.solverLoaded() && source.checkpointGeneration() == 1,
				"Container port loaded or admitted an unloaded solver");
	}

	private static FluidRegion portRegion(UUID id, ResourceKey<Level> dimension) {
		var origin = BlockPos.ZERO;
		var controller = new BlockPos(-1, 0, 0);
		var interior = new HashSet<Long>();
		var boundary = new HashSet<Long>();
		for (int z = 0; z < 2; z++) for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++)
			interior.add(new BlockPos(x, y, z).asLong());
		for (long position : interior) for (var direction : Direction.values()) {
			var adjacent = BlockPos.of(position).relative(direction);
			if (!interior.contains(adjacent.asLong())) boundary.add(adjacent.asLong());
		}
		var footprint = new FluidRegionFootprint(controller, origin,
				new BlockPos(1, 1, 1), interior, boundary, Set.of(0L));
		var domain = new HostFluidState(4, 4, 4, new FluidSolverConfig(.5F, 1000, 0, .025F, 120, 1e-5F, .002F));
		domain.sealDomainBoundary();
		domain.setVolume(1, 1, 1, .5F);
		return new FluidRegion(id, dimension, origin, domain, footprint);
	}

	private static BlockPos portPosition(Direction outward) {
		return switch (outward) {
			case WEST -> new BlockPos(-1, 1, 1);
			case EAST -> new BlockPos(2, 1, 1);
			case DOWN -> new BlockPos(1, -1, 1);
			case UP -> new BlockPos(1, 2, 1);
			case NORTH -> new BlockPos(1, 1, -1);
			case SOUTH -> new BlockPos(1, 1, 2);
		};
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
