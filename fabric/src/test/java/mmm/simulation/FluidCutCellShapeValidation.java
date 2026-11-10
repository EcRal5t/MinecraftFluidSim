package mmm.simulation;

import mmm.fluid.FluidDomainContracts;

import mmm.fluid.HostFluidState;
import mmm.fluid.FluidSolverConfig;
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
		blockSamplingMatchesIndividualCells();
		exteriorFacesSurviveCollisionEdits();
		thermalFacesSampleEachWorldBlockOnce();
		pipeGeometryUsesThinConnectedPassages();
		pipeBodiesReserveOnlyEnvelope();
		filledPipeRejectsBlockedEndClosure();
		System.out.println("Minecraft cut-cell shape validation passed");
	}
	private static void exteriorFacesSurviveCollisionEdits() {
		var domain=new HostFluidState(2,2,2,new FluidSolverConfig(.5f,0,.05f,48,1e-5f,.002f),mmm.fluid.TestFluidDefinitions.load());
		int[] exterior=new int[domain.grid().cellCount()];
		exterior[domain.grid().cell(0,0,0)]=0x9 | (0x2 << 8);
		domain.replaceExteriorMasks(exterior);
		domain.setSolidMicroMask(1,1,1,1);
		var fixed=domain.captureAuthoritativeState().fixedGeometry();
		check(fixed.exteriorMask(domain.grid().cell(0,0,0),0)==9 && fixed.exteriorMask(domain.grid().cell(0,0,0),2)==2,
				"collision edit lost exterior face masks");
	}
	private static void pipeBodiesReserveOnlyEnvelope() {
		var state=mmm.block.MmmBlocks.METAL_PIPE.defaultBlockState()
				.setValue(mmm.block.PipeBlock.port(net.minecraft.core.Direction.WEST),mmm.block.PipeBlock.Port.OPEN)
				.setValue(mmm.block.PipeBlock.port(net.minecraft.core.Direction.EAST),mmm.block.PipeBlock.Port.OPEN);
		var domain=new HostFluidState(2,2,2,new FluidSolverConfig(.5f,0,.05f,48,1e-5f,.002f),mmm.fluid.TestFluidDefinitions.load());
		var bodies=PipeCollisionBodies.forBlock(net.minecraft.core.BlockPos.ZERO,state,net.minecraft.core.BlockPos.ZERO);
		try {
			PipeCollisionBodies.checkBudget(java.util.Collections.nCopies(mmm.world.MovingBodyFluidCoupling.MAXIMUM_REQUESTS_PER_STEP+1,bodies.getFirst()));
			throw new AssertionError("Pipe body budget overflow was accepted");
		} catch(FluidDomainContracts.CollisionEditBlockedException expected) { }
		domain.replaceCollisionBodies(java.util.List.of(),new mmm.fluid.EntityBodyState(bodies));
		double open=0;for(int z=0;z<2;z++)for(int y=0;y<2;y++)for(int x=0;x<2;x++)open+=domain.cellCondensedRoom(x,y,z)*.125;
		if(Math.abs(open-PipeThermalGeometry.outsideVolume(state))>1e-7)throw new AssertionError("Pipe body did not preserve its external volume: "+open);
		domain.replaceCollisionBodies(java.util.List.of(),mmm.fluid.EntityBodyState.EMPTY);
		open=0;for(int z=0;z<2;z++)for(int y=0;y<2;y++)for(int x=0;x<2;x++)open+=domain.cellCondensedRoom(x,y,z)*.125;
		if(Math.abs(open-1)>1e-7)throw new AssertionError("Removed pipe body did not restore volume");
	}
	private static void filledPipeRejectsBlockedEndClosure() {
		var definitions=mmm.fluid.TestFluidDefinitions.load();
		var domain=new HostFluidState(2,2,2,new FluidSolverConfig(.5f,0,.05f,48,1e-5f,.002f),definitions);
		domain.bindMaterialRegistry(new mmm.fluid.FluidMaterialRegistry(java.util.List.of(definitions.material().entry("mmm:water").material())));
		int both=mmm.block.PipeGeometry.bit(net.minecraft.core.Direction.NORTH)|mmm.block.PipeGeometry.bit(net.minecraft.core.Direction.SOUTH);
		byte[] open=MinecraftCollisionWorldPort.sampleBlockMasks(mmm.block.PipeGeometry.shell(both),2);
		byte[] closed=MinecraftCollisionWorldPort.sampleBlockMasks(mmm.block.PipeGeometry.shell(mmm.block.PipeGeometry.bit(net.minecraft.core.Direction.NORTH)),2);
		for(int z=0;z<2;z++)for(int y=0;y<2;y++)for(int x=0;x<2;x++) {
			int cell=(z*2+y)*2+x;
			domain.setSolidMicroMask(x,y,z,Byte.toUnsignedInt(open[cell]));
			domain.setVolume(x,y,z,.25f);
		}
		var dimension=net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
				net.minecraft.resources.Identifier.parse("test:sealed_pipe"));
		var region=new FluidRegion(java.util.UUID.randomUUID(),dimension,net.minecraft.core.BlockPos.ZERO,domain);
		long mass=domain.totalMassUnits();double volume=domain.totalVolumeExact();long generation=region.solverGeneration();
		try {
			FluidRegionWorldInteraction.applyCapturedCollisions(region,new mmm.world.CollisionField(domain.grid(),closed),2);
			throw new AssertionError("fully occupied pipe end closure was accepted");
		} catch(FluidDomainContracts.CollisionEditBlockedException expected) { }
		check(region.solverGeneration()==generation && region.hostSnapshot().totalMassUnits()==mass
				&& region.hostSnapshot().totalVolumeExact()==volume,"blocked pipe closure changed accepted liquid");
		check(java.util.Arrays.equals(region.fixedGeometry().copyMasks(),open),"blocked pipe closure changed accepted geometry");
	}

	private static void pipeGeometryUsesThinConnectedPassages() {
        GpuControllerFixture.initializeItems();GpuControllerFixture.initializeIndustrialBlocks();
        mmm.block.PipeConnectionValidation.run();
        PipeTopologyValidation.run();
        try { PipeAssetsValidation.run(); } catch(Exception failure) { throw new IllegalStateException("Pipe resource validation failed",failure); }
        var blocks=new net.minecraft.world.level.block.Block[]{mmm.block.MmmBlocks.METAL_PIPE,mmm.block.MmmBlocks.INSULATED_PIPE,
                mmm.block.MmmBlocks.GLASS_PIPE,mmm.block.MmmBlocks.INSULATED_GLASS_PIPE};
        for(int sides=0;sides<64;sides++)for(var block:blocks) {
            var state=block.defaultBlockState();
            for(var face:net.minecraft.core.Direction.values())state=state.setValue(mmm.block.PipeBlock.port(face),
                    (sides&(1<<face.ordinal()))!=0?mmm.block.PipeBlock.Port.CONNECTED:mmm.block.PipeBlock.Port.SEALED);
            var expected=mmm.block.PipeGeometry.shell(sides,sides);
            check(!Shapes.joinIsNotEmpty(expected,mmm.block.PipeBlock.collision(state),BooleanOp.NOT_SAME),"registered pipe differs from geometry");
            check(!Shapes.joinIsNotEmpty(expected,mmm.block.PipeGeometry.bore(state),BooleanOp.AND),"pipe wall overlaps bore");
            var turned=state.rotate(net.minecraft.world.level.block.Rotation.CLOCKWISE_90);
            for(var face:net.minecraft.core.Direction.values())check(state.getValue(mmm.block.PipeBlock.port(face))==
                    turned.getValue(mmm.block.PipeBlock.port(net.minecraft.world.level.block.Rotation.CLOCKWISE_90.rotate(face))),"pipe rotation lost port state");
        }
        int axial=mmm.block.PipeGeometry.bit(net.minecraft.core.Direction.WEST)|mmm.block.PipeGeometry.bit(net.minecraft.core.Direction.EAST);
        var straight=mmm.block.MmmBlocks.METAL_PIPE.defaultBlockState();
        for(var face:net.minecraft.core.Direction.values())straight=straight.setValue(mmm.block.PipeBlock.port(face),
                (axial&mmm.block.PipeGeometry.bit(face))!=0?mmm.block.PipeBlock.Port.OPEN:mmm.block.PipeBlock.Port.SEALED);
        check(mmm.block.PipeGeometry.wallVolume(straight)==.140625 && mmm.block.PipeGeometry.innerArea(straight)==2
                && mmm.block.PipeGeometry.outerArea(straight)==2.78125,"thin pipe physical measures differ");
        var closed=straight.setValue(mmm.block.PipeBlock.port(net.minecraft.core.Direction.WEST),mmm.block.PipeBlock.Port.NONE);
        check(mmm.block.PipeGeometry.wallVolume(closed)==.15625 && mmm.block.PipeBlock.armFaces(closed)==axial,
                "automatic unconnected end lost its arm or cap");
        var retracted=closed.setValue(mmm.block.PipeBlock.port(net.minecraft.core.Direction.WEST),mmm.block.PipeBlock.Port.SEALED);
        check((mmm.block.PipeBlock.armFaces(retracted)&mmm.block.PipeGeometry.bit(net.minecraft.core.Direction.WEST))==0,
                "sealed end did not retract its arm");
        for(var face:net.minecraft.core.Direction.values())for(int opening=0;opening<=2;opening++) {
            var state=mmm.block.MmmBlocks.PIPE_VALVE.defaultBlockState().setValue(mmm.block.PipeValveBlock.FACING,face)
                    .setValue(mmm.block.PipeValveBlock.OPENING,opening);
            check(!Shapes.joinIsNotEmpty(mmm.block.PipeGeometry.shell(state),mmm.block.PipeValveBlock.collision(state),BooleanOp.NOT_SAME),"registered valve differs");
            double expected=.140625+(2-opening)/128.0;
            check(mmm.block.PipeGeometry.wallVolume(state)==expected,"valve shutter volume differs");
        }
    }

	private static void blockSamplingMatchesIndividualCells() {
		for (VoxelShape shape : new VoxelShape[] {Shapes.empty(), Shapes.block(),
				Shapes.box(0.25, 0, 0.25, 0.75, 1, 0.75)}) {
			for (int subdivision = 1; subdivision <= 3; subdivision++) {
				byte[] masks = MinecraftCollisionWorldPort.sampleBlockMasks(shape, subdivision);
				for (int z = 0; z < subdivision; z++) for (int y = 0; y < subdivision; y++) {
					for (int x = 0; x < subdivision; x++) check(Byte.toUnsignedInt(masks[(z * subdivision + y) * subdivision + x])
							== MinecraftCollisionWorldPort.sampleCutCellMask(shape, x, y, z, subdivision, INSET),
							"block sampling preserves each cut-cell mask");
				}
			}
		}
	}

	private static void thermalFacesSampleEachWorldBlockOnce() {
		var origin = new net.minecraft.core.BlockPos(-13, 4, -17);
		for (int subdivision = 1; subdivision <= 3; subdivision++) {
			var visits = new java.util.HashSet<net.minecraft.core.BlockPos>();
			double[] field = ThermalBoundaryResolver.sampleField(origin, 5, 3, 7, subdivision, position -> {
				check(visits.add(position), "each world temperature is read once per capture");
				return temperatureAt(position);
			});
			int[][] directions = {{-1,0,0}, {1,0,0}, {0,-1,0}, {0,1,0}, {0,0,-1}, {0,0,1}};
			var expectedVisits = new java.util.HashSet<net.minecraft.core.BlockPos>();
			for (int z = 0; z < 7; z++) for (int y = 0; y < 3; y++) for (int x = 0; x < 5; x++) {
				for (int direction = 0; direction < 6; direction++) {
					int[] offset = directions[direction];
					var target = origin.offset(Math.floorDiv(x + offset[0], subdivision),
							Math.floorDiv(y + offset[1], subdivision), Math.floorDiv(z + offset[2], subdivision));
					expectedVisits.add(target);
					check(Double.doubleToLongBits(field[((z * 3 + y) * 5 + x) * 6 + direction])
							== Double.doubleToLongBits(temperatureAt(target)), "thermal face keeps its exact source block");
				}
			}
			check(visits.equals(expectedVisits), "world sampling reads exactly the face targets");
		}
	}

	private static double temperatureAt(net.minecraft.core.BlockPos position) {
		return position.getX() % 2 == 0 ? Double.NaN : 300 + position.getX() + 2 * position.getY() + position.getZ();
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
				0.5F, 9.81F, 1.0F / 20.0F,
				160, 5.0E-5F, 2.0E-3F);
        var definitions = mmm.fluid.TestFluidDefinitions.load();
		HostFluidState domain = new HostFluidState(24, 16, 24, config, definitions);
        var liquids = new mmm.fluid.FluidMaterialRegistry(java.util.List.of(definitions.material().entry("mmm:water").material()));
        domain.bindMaterialRegistry(liquids);
		for (int z = 0; z < 24; z++) for (int y = 0; y < 12; y++) for (int x = 0; x < 9; x++) {
			domain.setVolume(x, y, z, 1.0F);
		}
        domain.bindChemistry(definitions.chemistry());
        domain.bindGasRegistry(definitions.gas());
        domain.bindThermal(definitions.thermal(), liquids, 310.0);
		float initialVolume = domain.totalVolume();
        long initialMass = domain.totalMassUnits();
        var initialSpecies = liquidSpecies(domain);
        double initialLiquidEnergy = domain.exactInternalEnergy().subtract(domain.exactGasInternalEnergy()).toDouble();
		VoxelShape stair = Shapes.joinUnoptimized(
				Shapes.box(0.0, 0.0, 0.0, 1.0, 0.5, 1.0),
				Shapes.box(0.0, 0.5, 0.5, 1.0, 1.0, 1.0),
				BooleanOp.OR
		);
		var registry=net.minecraft.resources.ResourceKey.<net.minecraft.world.level.Level>createRegistryKey(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("mmm","shape_dimensions"));
        var dimension=net.minecraft.resources.ResourceKey.create(registry,
                net.minecraft.resources.Identifier.fromNamespaceAndPath("mmm","shape_world"));
        var region=new FluidRegion(java.util.UUID.randomUUID(),dimension,net.minecraft.core.BlockPos.ZERO,domain);
        byte[] masks=new byte[domain.grid().cellCount()];
        for (int localZ=0;localZ<2;localZ++) for (int localY=0;localY<2;localY++) for (int localX=0;localX<2;localX++) {
            int x=4+localX,y=2+localY,z=4+localZ;
            check(domain.volume(x,y,z)==1.0F,"stair fixture did not populate its collision cells");
            masks[domain.grid().cell(x,y,z)]=(byte)sample(stair,localX,localY,localZ);
        }
        var result=FluidRegionWorldInteraction.applyCapturedCollisions(region,new mmm.world.CollisionField(domain.grid(),masks),2);
        var after=region.hostSnapshot();
        checkClose(result.displacedCellVolume(),6.0F,0.0F,"documented stair displacement");
        checkClose(result.escapedCellVolume(),0.0F,0.0F,"documented stair escaped inventory");
        checkClose(after.totalVolume(),initialVolume,0.0F,"documented stair conserved liquid");
        check(after.totalMassUnits()==initialMass && liquidSpecies(after).equals(initialSpecies),
                "documented stair changed liquid mass or composition");
        double finalLiquidEnergy=after.exactInternalEnergy().subtract(after.exactGasInternalEnergy()).toDouble();
        check(Math.abs(finalLiquidEnergy-initialLiquidEnergy)<=Math.max(1e-6,Math.abs(initialLiquidEnergy)*1e-10)
                        && after.thermalAudit().expectedInventory().equals(after.exactInternalEnergy()),
                "documented stair changed carried liquid energy or thermal audit");
        for (int localZ=0;localZ<2;localZ++) for (int localY=0;localY<2;localY++) for (int localX=0;localX<2;localX++)
            check(after.solidMicroMask(4+localX,2+localY,4+localZ)==sample(stair,localX,localY,localZ),
                    "stair collision geometry was not committed");
	}

    private static java.util.Map<Integer,Long> liquidSpecies(HostFluidState domain) {
        var total=new java.util.TreeMap<Integer,Long>();
        for (var stock:domain.captureAuthoritativeState().liquid().physicalStocks()) if(stock.mixture()!=null)
            for(int i=0;i<stock.mixture().entryCount();i++) total.merge(stock.mixture().speciesHandle(i),stock.mixture().amountAt(i),Math::addExact);
        return total;
    }

	private static int sample(VoxelShape shape, int localX, int localY, int localZ) {
		return FluidRegionWorldInteraction.sampleCutCellMask(shape, localX, localY, localZ, 2, INSET);
	}

	private static void check(boolean condition, String message) {
		if (!condition) throw new AssertionError(message);
	}

	private static void checkClose(float actual, float expected, float tolerance, String message) {
		check(Math.abs(actual - expected) <= tolerance,
				message + ": expected=" + expected + ", actual=" + actual);
	}
}
