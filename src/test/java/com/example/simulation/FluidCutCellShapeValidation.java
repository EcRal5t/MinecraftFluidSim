package com.example.simulation;

import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Minecraft VoxelShape-to-runtime-cut-cell sampling regression. */
public final class FluidCutCellShapeValidation {
	private static final double INSET = 1.0E-5;

	private FluidCutCellShapeValidation() { }

	public static void main(String[] args) {
		halfSlabPreservesSolverAlignedFaces();
		stairCornerProducesFractionalSolverCells();
		thinPaneRemainsAClosedConservativeBarrier();
		System.out.println("Minecraft cut-cell shape validation passed");
	}

	private static void halfSlabPreservesSolverAlignedFaces() {
		VoxelShape slab = Shapes.box(0.0, 0.0, 0.0, 1.0, 0.5, 1.0);
		for (int z = 0; z < 2; z++) for (int x = 0; x < 2; x++) {
			check(sample(slab, x, 0, z) == 0xFF, "lower slab solver cell was not fully solid");
			check(sample(slab, x, 1, z) == 0, "upper slab solver cell was not open");
		}
	}

	private static void stairCornerProducesFractionalSolverCells() {
		VoxelShape lowerSlab = Shapes.box(0.0, 0.0, 0.0, 1.0, 0.5, 1.0);
		VoxelShape upperBackHalf = Shapes.box(0.0, 0.5, 0.5, 1.0, 1.0, 1.0);
		VoxelShape stair = Shapes.joinUnoptimized(lowerSlab, upperBackHalf, BooleanOp.OR);
		check(sample(stair, 0, 1, 0) == 0, "front stair opening was closed");
		check(sample(stair, 0, 1, 1) == 0xFF, "back stair riser was open");

		VoxelShape quarterPost = Shapes.box(0.25, 0.0, 0.25, 0.75, 1.0, 0.75);
		int mask = sample(quarterPost, 0, 0, 0);
		check(mask != 0 && mask != 0xFF, "partial post did not produce a fractional solver cell");
	}

	private static void thinPaneRemainsAClosedConservativeBarrier() {
		VoxelShape pane = Shapes.box(7.0 / 16.0, 0.0, 0.0, 9.0 / 16.0, 1.0, 1.0);
		for (int z = 0; z < 2; z++) for (int y = 0; y < 2; y++) {
			check(sample(pane, 0, y, z) != 0, "pane vanished from its negative-X solver cell");
			check(sample(pane, 1, y, z) != 0, "pane vanished from its positive-X solver cell");
		}
	}

	private static int sample(VoxelShape shape, int localX, int localY, int localZ) {
		return FluidRegionWorldInteraction.sampleCutCellMask(shape, localX, localY, localZ, 2, INSET);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}
}
