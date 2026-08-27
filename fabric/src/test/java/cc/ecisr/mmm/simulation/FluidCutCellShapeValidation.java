package cc.ecisr.mmm.simulation;

import cc.ecisr.mmm.fluid.FlipFluidDomain;
import cc.ecisr.mmm.fluid.FluidSolverConfig;
import cc.ecisr.mmm.world.MovingBodyPressureIntegrator;
import cc.ecisr.mmm.world.MovingBodySurfaceSnapshot;
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
		documentedStairPlacementRestoresExactLedger();
		axisAlignedMovingBodySurfaceIsExactAndBounded();
		hydrostaticPressureSurfaceIntegralMatchesDisplacement();
		System.out.println("Minecraft cut-cell shape validation passed");
	}

	private static void axisAlignedMovingBodySurfaceIsExactAndBounded() {
		MovingBodySurfaceSnapshot.BoxPose pose = new MovingBodySurfaceSnapshot.BoxPose(
				0.25, 1.0, 2.0, 1.25, 3.0, 5.0);
		MovingBodySurfaceSnapshot.Body body = new MovingBodySurfaceSnapshot.Body(
				7L, pose, pose, 0.0, 0.0, 0.0,
				MovingBodySurfaceSnapshot.CouplingMode.KINEMATIC_EXTERNAL, 0.0);
		MovingBodySurfaceSnapshot snapshot = new MovingBodySurfaceSnapshot(java.util.List.of(body));
		double totalArea = body.faces().stream().mapToDouble(
				MovingBodySurfaceSnapshot.Face::areaSquareMetres).sum();
		check(snapshot.faceCount() == 6 && body.displacedVolumeCubicMetres() == 6.0
				&& totalArea == 22.0,
				"axis-aligned moving body did not freeze six exact analytic faces");
		expectFailure(() -> new MovingBodySurfaceSnapshot(java.util.List.of(body, body)),
				"moving-body snapshot accepted a duplicate transient id");
		MovingBodySurfaceSnapshot.Body overlap = new MovingBodySurfaceSnapshot.Body(
				8L, pose, pose, 0.0, 0.0, 0.0,
				MovingBodySurfaceSnapshot.CouplingMode.KINEMATIC_EXTERNAL, 0.0);
		expectFailure(() -> new MovingBodySurfaceSnapshot(java.util.List.of(body, overlap)),
				"moving-body snapshot accepted overlapping boxes");
	}

	private static void hydrostaticPressureSurfaceIntegralMatchesDisplacement() {
		double density = 997.0, gravity = 9.81, dt = 0.05;
		MovingBodySurfaceSnapshot.BoxPose pose = new MovingBodySurfaceSnapshot.BoxPose(
				0.2, 1.2, 0.3, 0.8, 2.0, 0.8);
		MovingBodySurfaceSnapshot.Body body = new MovingBodySurfaceSnapshot.Body(
				11L, pose, pose, 0.0, 0.25, 0.0,
				MovingBodySurfaceSnapshot.CouplingMode.DYNAMIC, 70.0);
		MovingBodyPressureIntegrator.Result result = MovingBodyPressureIntegrator.integrate(body,
				(x, y, z) -> 101_325.0 + density * gravity * (3.0 - y), dt);
		double expectedUpwardImpulse = density * gravity * body.displacedVolumeCubicMetres() * dt;
		check(Math.abs(result.impulseXKilogramMetresPerSecond()) <= Math.ulp(101_325.0) * 8.0
				&& Math.abs(result.impulseZKilogramMetresPerSecond()) <= Math.ulp(101_325.0) * 8.0
				&& Math.abs(result.impulseYKilogramMetresPerSecond() - expectedUpwardImpulse)
				<= Math.ulp(expectedUpwardImpulse) * 16.0
				&& Math.abs(result.displacementWorkJoules() - expectedUpwardImpulse * 0.25)
				<= Math.ulp(expectedUpwardImpulse * 0.25) * 16.0,
				"hydrostatic pressure surface integral did not reduce to rho*g*displaced-volume");
		MovingBodyPressureIntegrator.Result uniform = MovingBodyPressureIntegrator.integrate(body,
				(x, y, z) -> 101_325.0, dt);
		check(uniform.impulseXKilogramMetresPerSecond() == 0.0
				&& uniform.impulseYKilogramMetresPerSecond() == 0.0
				&& uniform.impulseZKilogramMetresPerSecond() == 0.0,
				"closed moving-body surface did not cancel uniform absolute pressure");
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

	private static void documentedStairPlacementRestoresExactLedger() {
		FluidSolverConfig config = new FluidSolverConfig(
				0.5F, 1_000.0F, 9.81F, 1.0F / 20.0F,
				160, 5.0E-5F, 0.40F, 2.0E-3F, 0.80F
		);
		FlipFluidDomain domain = new FlipFluidDomain(24, 16, 24, config, 73_728);
		for (int z = 0; z < 24; z++) for (int y = 0; y < 12; y++) for (int x = 0; x < 9; x++) {
			domain.setVolume(x, y, z, 1.0F);
		}
		float initialVolume = domain.totalVolume();
		VoxelShape stair = Shapes.joinUnoptimized(
				Shapes.box(0.0, 0.0, 0.0, 1.0, 0.5, 1.0),
				Shapes.box(0.0, 0.5, 0.5, 1.0, 1.0, 1.0),
				BooleanOp.OR
		);
		float displaced = 0.0F;
		for (int localZ = 0; localZ < 2; localZ++) for (int localY = 0; localY < 2; localY++) {
			for (int localX = 0; localX < 2; localX++) {
				displaced += domain.setSolidMicroMask(
						4 + localX, 2 + localY, 4 + localZ, sample(stair, localX, localY, localZ)
				);
			}
		}
		FlipFluidDomain.VolumeAddition restoration = domain.addVolumeNearBudgeted(5, 3, 5, displaced, 8);
		checkClose(displaced, 6.0F, 0.0F, "documented stair displacement");
		checkClose(restoration.addedVolume(), displaced, 0.0F, "documented stair restoration");
		check(!restoration.markerBudgetLimited(), "documented stair restoration exhausted marker budget");
		checkClose(domain.totalVolume(), initialVolume, 0.0F, "documented stair exact marker ledger");
	}

	private static int sample(VoxelShape shape, int localX, int localY, int localZ) {
		return FluidRegionWorldInteraction.sampleCutCellMask(shape, localX, localY, localZ, 2, INSET);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static void expectFailure(Runnable action, String message) {
		try { action.run(); }
		catch (RuntimeException expected) { return; }
		throw new AssertionError(message);
	}

	private static void checkClose(float actual, float expected, float tolerance, String message) {
		check(Math.abs(actual - expected) <= tolerance,
				message + ": expected=" + expected + ", actual=" + actual);
	}
}
