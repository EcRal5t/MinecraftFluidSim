package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.api.RegionAvailabilityReason;
import cc.ecisr.mmm.api.query.FluidCellObservation;
import cc.ecisr.mmm.api.query.FluidQueryResult;
import cc.ecisr.mmm.api.query.FluidQueryStatus;

import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;

/** Selection gate for the two cells touching one observed shell face. */
public final class ShellFluidQueryValidation {
	private ShellFluidQueryValidation() { }

	public static void main(String[] args) {
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

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
