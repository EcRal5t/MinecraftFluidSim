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
		System.out.println("Shell fluid query validation passed");
	}

	private static void require(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
