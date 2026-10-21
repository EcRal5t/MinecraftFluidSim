package mmm.simulation;

import mmm.application.RegionCatalog;
import mmm.block.MmmBlocks;
import mmm.fluid.BuiltinFluidMaterials;
import mmm.fluid.FluidDomain;
import mmm.fluid.FluidMaterial;
import mmm.fluid.FluidMaterialRegistry;
import mmm.fluid.FluidSolverConfig;
import mmm.fluid.GasPressureBoundary;
import mmm.worldprofile.WorldCapability;
import mmm.worldprofile.WorldCapabilityProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Development-only `/mmm` scenarios: dam break, layered materials, acid/base, surface tension,
 * sealed gas and vanilla-water capture. They build a transient region that is never admitted as a
 * controller region and never writes region-state. The gas-demo glass scaffold replaces only air
 * blocks and is restored on demand, so a fixture never destroys a player-built block.
 */
public final class MinecraftDebugFixtures {
	static final int BLOCK_SIZE_X = 12;
	static final int BLOCK_SIZE_Y = 8;
	static final int BLOCK_SIZE_Z = 12;
	private static final long COMPATIBILITY_REGION_CELLS = (long) BLOCK_SIZE_X * BLOCK_SIZE_Y * BLOCK_SIZE_Z
			* FluidRegionFactory.CELL_VOLUMES_PER_BLOCK;
	private static final long COMPATIBILITY_REGION_MARKERS = COMPATIBILITY_REGION_CELLS * 8L;

	/** Region-runtime operations the scenarios need; owned here so the surface stays narrow. */
	interface Runtime {
		/** Re-checks availability, then re-samples collision and thermal boundaries. */
		FluidRegion.CollisionStats refreshCollisions(ServerLevel level);

		/** Runs one synchronous logical motion step through the complete candidate boundary. */
		void stepMotion(FluidRegion region);

		/** Binds the committed world capability registries to a freshly allocated domain. */
		void bindCapabilityState(FluidDomain domain);

		/** Activates or re-selects a controller; returns null on success or the failure text. */
		String activateController(ServerPlayer player, BlockPos controllerPosition);
	}

	private final FluidRegionManager regions;
	private final FluidSnapshotPublisher snapshots;
	private final MinecraftTakeoverAdapter takeover;
	private final MinecraftDebugScaffolding scaffolding;
	private final Runtime runtime;
	private final WorldCapabilityProfile profile;
	private final BooleanSupplier defaultEntityPhysics;
	private final BooleanSupplier defaultDebugOverlays;
	private String lastFailure;

	MinecraftDebugFixtures(
			FluidRegionManager regions,
			FluidSnapshotPublisher snapshots,
			MinecraftTakeoverAdapter takeover,
			MinecraftDebugScaffolding scaffolding,
			Runtime runtime,
			WorldCapabilityProfile profile,
			BooleanSupplier defaultEntityPhysics,
			BooleanSupplier defaultDebugOverlays
	) {
		this.regions = regions;
		this.snapshots = snapshots;
		this.takeover = takeover;
		this.scaffolding = scaffolding;
		this.runtime = runtime;
		this.profile = profile;
		this.defaultEntityPhysics = defaultEntityPhysics;
		this.defaultDebugOverlays = defaultDebugOverlays;
	}

	public String lastFailure() {
		return lastFailure;
	}

	/** Restores the world blocks the gas-demo glass scaffold replaced; player-edited positions are left alone. */
	public void restoreAirDemoScaffolding(MinecraftServer server) {
		scaffolding.restore(server);
	}

	/**
	 * Safely builds and activates the small persistent controller fixture used by container and
	 * chemistry smoke tests. The complete 6x6x6 occupied volume must already be loaded and air;
	 * failed placement or activation restores every block written by this method.
	 */
	public BlockPos createControllerDemo(ServerPlayer player) {
		final int interiorSize = 4;
		final int outerSize = interiorSize + 2;
		ServerLevel level = player.level();
		Direction facing = player.getDirection();
		BlockPos nearFace = player.blockPosition().relative(facing, 4).above(2);
		BlockPos minimum;
		BlockPos controller;
		if (facing.getAxis() == Direction.Axis.X) {
			int minimumX = facing == Direction.EAST ? nearFace.getX() : nearFace.getX() - outerSize + 1;
			minimum = new BlockPos(minimumX, player.blockPosition().getY(), nearFace.getZ() - 2);
			controller = new BlockPos(nearFace.getX(), minimum.getY() + 2, minimum.getZ() + 2);
		} else {
			int minimumZ = facing == Direction.SOUTH ? nearFace.getZ() : nearFace.getZ() - outerSize + 1;
			minimum = new BlockPos(nearFace.getX() - 2, player.blockPosition().getY(), minimumZ);
			controller = new BlockPos(minimum.getX() + 2, minimum.getY() + 2, nearFace.getZ());
		}

		for (int z = 0; z < outerSize; z++) for (int y = 0; y < outerSize; y++) {
			for (int x = 0; x < outerSize; x++) {
				BlockPos position = minimum.offset(x, y, z);
				if (!level.isInWorldBounds(position) || !level.isInsideBuildHeight(position)) {
					lastFailure = "快速控制器超出世界高度/边界：" + position.toShortString();
					return null;
				}
				if (level.getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4) == null) {
					lastFailure = "快速控制器需要已加载区块："
							+ (position.getX() >> 4) + "," + (position.getZ() >> 4);
					return null;
				}
				if (!level.getBlockState(position).isAir()) {
					lastFailure = "快速控制器只替换空气；请清空 " + position.toShortString() + " 后重试";
					return null;
				}
			}
		}

		List<BlockPos> placed = new ArrayList<>();
		try {
			for (int z = 0; z < outerSize; z++) for (int y = 0; y < outerSize; y++) {
				for (int x = 0; x < outerSize; x++) {
					if (x != 0 && x != outerSize - 1 && y != 0 && y != outerSize - 1
							&& z != 0 && z != outerSize - 1) continue;
					BlockPos position = minimum.offset(x, y, z);
					BlockState state = position.equals(controller)
							? MmmBlocks.FLUID_CONTROLLER.defaultBlockState()
							: MmmBlocks.FLUID_BOUNDARY.defaultBlockState();
					if (!level.setBlock(position, state, Block.UPDATE_ALL)) {
						throw new IllegalStateException("无法放置壳块 " + position.toShortString());
					}
					placed.add(position.immutable());
				}
			}
		} catch (RuntimeException failure) {
			rollbackControllerDemoBlocks(level, placed);
			lastFailure = "快速控制器已回滚：" + failure.getMessage();
			return null;
		}

		String activationFailure;
		try {
			activationFailure = runtime.activateController(player, controller);
		} catch (RuntimeException failure) {
			// An unchecked activation failure may have reached persistent region-state. Keep the
			// visible shell so recovery never has to guess whether removing it is safe.
			lastFailure = "快速控制器激活异常；为保护可能已发布的 region-state，壳体已保留："
					+ failure.getMessage();
			return null;
		}
		if (activationFailure != null) {
			rollbackControllerDemoBlocks(level, placed);
			lastFailure = "快速控制器已回滚：" + activationFailure;
			return null;
		}
		lastFailure = null;
		return controller.immutable();
	}

	private static void rollbackControllerDemoBlocks(ServerLevel level, List<BlockPos> placed) {
		for (int index = placed.size() - 1; index >= 0; index--) {
			BlockPos position = placed.get(index);
			if (MmmBlocks.isBoundary(level.getBlockState(position))) {
				level.setBlock(position, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
			}
		}
	}

	public boolean createDemo(ServerPlayer player) {
		return createDemo(player, false);
	}

	public boolean createMaterialDemo(ServerPlayer player) {
		if (!profile.hasCapability(WorldCapability.MATERIALS)) {
			lastFailure = "材料功能门未在本次启动启用；先保存配置并重启";
			return false;
		}
		return createDemo(player, true);
	}

	/** Creates a paused acid/base fixture without changing the mechanical demo geometry. */
	public boolean createChemistryDemo(ServerPlayer player) {
		if (!profile.hasCapability(WorldCapability.CHEMISTRY)) {
			lastFailure = "化学功能门未在本次启动启用；先启用 materials/chemistry 并重启";
			return false;
		}
		if (!createDemo(player, false)) return false;
		FluidDomain domain = regions.selected().domain();
		float[] positionVolume = domain.exportChemicalParticleState().materials().copyPositionVolume();
		float divider = domain.sizeX() * domain.config().cellSize() / 5.0F;
		long reagentMicromoles = mmm.chemistry.ChemicalAmount.quanta(mmm.chemistry.ExactRational.ONE);
		var stocks = domain.exportLiquidCellState().physicalStocks();
		for (int marker = 0; marker < domain.particleCount(); marker++) {
			boolean acid = positionVolume[marker * 4] < divider;
			var registry = domain.chemistry().registry();
			var stock = stocks.get(marker);
			var solution = mmm.chemistry.ChemicalMixtureRecord.fromSolutesAndSolventMass(registry,
					java.util.Map.of(acid ? "mmm:hcl" : "mmm:naoh", reagentMicromoles), "mmm:h2o", stock.massUnits());
			domain.setMarkerChemical(marker, solution.stableAmounts(registry), acid);
		}
		regions.selected().setPaused(true);
		sendSnapshot(player.level());
		return true;
	}

	public boolean createTensionDemo(ServerPlayer player, int angleDegrees) {
		if (!profile.hasCapability(WorldCapability.MATERIALS)) {
			lastFailure = "材料功能门未在本次启动启用；先保存配置并重启";
			return false;
		}
		if (profile.hasCapability(WorldCapability.CHEMISTRY)) {
			lastFailure = "表面张力测试材料没有 chemical phase；chemistry 开启时拒绝混用测试域";
			return false;
		}
		Vec3 look = player.getLookAngle();
		BlockPos center = BlockPos.containing(player.getX() + look.x * 9.0, player.getY(), player.getZ() + look.z * 9.0);
		ServerLevel level = player.level();
		if (!admitTransientRegion(level, aimedOrigin(center), true)) return false;
		runtime.refreshCollisions(level);
		FluidDomain domain = regions.selected().domain();
		for (int z = 0; z < domain.sizeZ(); z++) for (int x = 0; x < domain.sizeX(); x++) {
			domain.setSolid(x, 0, z, true);
		}
		restoreTensionCap(domain, angleDegrees);
		bindTensionDemoMaterial(domain, angleDegrees);
		regions.selected().setPaused(true);
		sendSnapshot(level);
		return true;
	}

	/**
	 * Creates a paused transient gas-pressure demo: a glass U-tube is scaffolded into the world
	 * (replacing only air), the lower part is filled with water, and the sealed left chamber's
	 * gas pocket is preset above or below ambient pressure while the right chamber stays open.
	 * Running it shows the gas pressure holding a sustained liquid-level difference. Pure water,
	 * no surface tension, no material gate; writes no region-state and the scaffold restores on
	 * removal, replacement or server shutdown.
	 */
	public boolean createAirDemo(ServerPlayer player, boolean vacuum) {
		Vec3 look = player.getLookAngle();
		BlockPos center = BlockPos.containing(player.getX() + look.x * 9.0, player.getY(), player.getZ() + look.z * 9.0);
		ServerLevel level = player.level();
		BlockPos newOrigin = aimedOrigin(center);
		if (!admitTransientRegion(level, newOrigin, true)) return false;
		scaffolding.placeAirDemo(level, newOrigin);
		runtime.refreshCollisions(level);
		FluidDomain domain = regions.selected().domain();
		fillAirDemoWater(domain);
		runtime.bindCapabilityState(domain);
		runtime.stepMotion(regions.selected());
		domain = regions.selected().domain();
		GasPressureBoundary network = domain.gasBoundary();
		if (network.nodeCount() < 1) {
			lastFailure = "左侧气腔未密闭；玻璃只替换空气方块，请清理模拟区域内的其他方块后重试";
			restoreAirDemoScaffolding(level.getServer());
			FluidRegion failedRegion = regions.selected();
			if (failedRegion != null && !failedRegion.controllerManaged()) {
				regions.remove(failedRegion.id());
				snapshots.sendRemoval(level.getServer(), failedRegion);
			}
			return false;
		}
		int node = network.nodeIds().get(0);
		// pressure adds inventory (p = P0(V0/V - 1) > 0), vacuum removes it; the soft gas spring
		// settles near a third of the preset head, so presets are scaled for a visible difference
		domain.setGasNodeStandardVolume(node, vacuum ? network.availableVolume(node) * 0.78
				: network.availableVolume(node) / 0.70);
		regions.selected().setPaused(true);
		sendSnapshot(level);
		return true;
	}

	/** Fills both chambers to 3.5 blocks; the two-block doorway column stays inside the fill range and submerged. */
	private static void fillAirDemoWater(FluidDomain domain) {
		float[] volumes=new float[domain.sizeX()*domain.sizeY()*domain.sizeZ()];
		int cellsPerBlock = FluidRegionFactory.CELLS_PER_BLOCK;
		for (int bz = 1; bz <= BLOCK_SIZE_Z - 2; bz++) {
			for (int bx = 1; bx <= BLOCK_SIZE_X - 2; bx++) {
				for (int by = 1; by <= 3; by++) {
					float fraction = by <= 2 ? 1.0F : 0.5F;
					for (int localZ = 0; localZ < cellsPerBlock; localZ++) {
						for (int localY = 0; localY < cellsPerBlock; localY++) {
							for (int localX = 0; localX < cellsPerBlock; localX++) {
								int x = bx * cellsPerBlock + localX;
								int y = by * cellsPerBlock + localY;
								int z = bz * cellsPerBlock + localZ;
								if (!domain.isSolid(x, y, z)) volumes[(z*domain.sizeY()+y)*domain.sizeX()+x] = fraction;
							}
						}
					}
				}
			}
		}
		domain.setVolumes(volumes);
	}

	private static void bindTensionDemoMaterial(FluidDomain domain, int angleDegrees) {
		FluidMaterial tensionWater = new FluidMaterial("mmm:tension_water", domain.config().density(),
				"mmm:tension_water", 100.0, Math.toRadians(angleDegrees));
		domain.bindMaterialRegistry(new FluidMaterialRegistry(List.of(tensionWater)));
	}

	private static BlockPos aimedOrigin(BlockPos center) {
		return center.offset(-BLOCK_SIZE_X / 2, -2, -BLOCK_SIZE_Z / 2);
	}

	private boolean replaceableByFixture() {
		FluidRegion active = regions.selected();
		if (active != null && active.controllerManaged()) {
			lastFailure = "拒绝用开发测试域覆盖持久控制器区域；请先显式移除控制器区域";
			return false;
		}
		return true;
	}

	/**
	 * Shared scenario prologue: drop the previous glass scaffold, finish any takeover, refuse to
	 * overwrite a persistent controller region, then admit a fresh transient region at the origin.
	 * The caller refreshes collisions itself so the gas demo can scaffold its U-tube first.
	 */
	private boolean admitTransientRegion(ServerLevel level, BlockPos origin, boolean tensionGeometry) {
		restoreAirDemoScaffolding(level.getServer());
		RestorationStats restoration = takeover.restore(level.getServer());
		if (!restoration.completed()) {
			lastFailure = restoration.failure();
			return false;
		}
		if (!replaceableByFixture()) return false;
		try {
			if (tensionGeometry) createTransientEmptyRegion(level, origin);
			else createEmpty(level, origin);
		} catch (IllegalStateException rejected) {
			lastFailure = rejected.getMessage();
			return false;
		}
		return true;
	}

	private void createTransientEmptyRegion(ServerLevel level, BlockPos origin) {
		FluidRegion replaced = regions.selected();
		if (replaced != null && replaced.controllerManaged()) {
			throw new IllegalStateException("Refusing to replace a persistent controller region; remove it explicitly first");
		}
		RegionCatalog.AdmissionResult preview = regions.previewReplacement(
				COMPATIBILITY_REGION_CELLS, COMPATIBILITY_REGION_MARKERS);
		if (!preview.accepted()) throw new IllegalStateException("Compatibility fluid region rejected: " + preview.failure());
		FluidSolverConfig config = new FluidSolverConfig(
				0.5F, 1_000.0F, 9.81F, 1.0F / 40.0F,
				300, 1.0E-5F, 2.0E-3F, 0.80F
		);
		FluidDomain domain = new FluidDomain(
				BLOCK_SIZE_X * FluidRegionFactory.CELLS_PER_BLOCK,
				BLOCK_SIZE_Y * FluidRegionFactory.CELLS_PER_BLOCK,
				BLOCK_SIZE_Z * FluidRegionFactory.CELLS_PER_BLOCK,
				config, Math.toIntExact(COMPATIBILITY_REGION_MARKERS)
		);
		FluidRegion candidate = new FluidRegion(
				UUID.randomUUID(), level.dimension(), origin, domain, COMPATIBILITY_REGION_MARKERS
		);
		candidate.setEntityPhysicsEnabled(defaultEntityPhysics.getAsBoolean());
		candidate.setDebugOverlays(defaultDebugOverlays.getAsBoolean());
		RegionCatalog.AdmissionResult admission = regions.replaceSelected(candidate);
		if (!admission.accepted()) throw new IllegalStateException("Compatibility fluid region rejected: " + admission.failure());
		if (replaced != null) snapshots.sendRemoval(level.getServer(), replaced);
	}

	private static void restoreTensionCap(FluidDomain domain, int angleDegrees) {
		int sx = domain.sizeX(), sy = domain.sizeY(), sz = domain.sizeZ();
		float cellSize = domain.config().cellSize();
		double wallY = 0.5 * cellSize;
		double targetCellVolume = 220.0;
		double theta = Math.toRadians(angleDegrees);
		double cos = Math.cos(theta);
		double radiusCells = Math.cbrt(3.0 * targetCellVolume / (Math.PI * (1.0 - cos) * (1.0 - cos) * (2.0 + cos)));
		double radius = radiusCells * cellSize;
		double centerX = 0.5 * (sx - 1) * cellSize;
		double centerY = wallY - radius * cos;
		double centerZ = 0.5 * (sz - 1) * cellSize;
		float[] volumes=new float[sx*sy*sz];
		for(int z=0;z<sz;z++) for(int y=1;y<sy;y++) for(int x=0;x<sx;x++)
			volumes[(z*sy+y)*sx+x]=(float)sphericalCapFraction(x,y,z,cellSize,centerX,centerY,centerZ,radius,wallY);
		domain.setVolumes(volumes);
	}

	private static double sphericalCapFraction(int x, int y, int z, double cellSize,
	                                           double centerX, double centerY, double centerZ,
	                                           double radius, double wallY) {
		double px = x * cellSize, py = y * cellSize, pz = z * cellSize;
		int samples = 4;
		int hits = 0;
		for (int subZ = 0; subZ < samples; subZ++) for (int subY = 0; subY < samples; subY++) {
			for (int subX = 0; subX < samples; subX++) {
				double sx = px + (subX + 0.5) * cellSize / samples;
				double sy = py + (subY + 0.5) * cellSize / samples;
				double sz = pz + (subZ + 0.5) * cellSize / samples;
				double dx = sx - centerX, dy = sy - centerY, dz = sz - centerZ;
				if (sy >= wallY && dx * dx + dy * dy + dz * dz <= radius * radius) hits++;
			}
		}
		return (double) hits / (samples * samples * samples);
	}

	private boolean createDemo(ServerPlayer player, boolean layeredMaterials) {
		Vec3 look = player.getLookAngle();
		BlockPos center = BlockPos.containing(player.getX() + look.x * 9.0, player.getY(), player.getZ() + look.z * 9.0);
		ServerLevel level = player.level();
		if (!admitTransientRegion(level, aimedOrigin(center), false)) return false;
		runtime.refreshCollisions(level);

		FluidDomain domain = regions.selected().domain();
		float[] volumes=new float[domain.sizeX()*domain.sizeY()*domain.sizeZ()];
		int fillX = domain.sizeX() * 2 / 5;
		int fillY = domain.sizeY() * 3 / 4;
		for (int z = 0; z < domain.sizeZ(); z++) {
			for (int y = 0; y < fillY; y++) {
				for (int x = 0; x < fillX; x++) {
					if (!domain.isSolid(x, y, z)) volumes[(z*domain.sizeY()+y)*domain.sizeX()+x] = 1.0F;
				}
			}
		}
		if (profile.hasCapability(WorldCapability.MATERIALS) || layeredMaterials) FluidRegionFactory.bindDefaultMaterialState(domain);
		int[] materials=layeredMaterials?new int[volumes.length]:null;
		if(materials!=null) for(int z=0;z<domain.sizeZ();z++) for(int y=0;y<domain.sizeY();y++) for(int x=0;x<domain.sizeX();x++)
			materials[(z*domain.sizeY()+y)*domain.sizeX()+x]=y+.5F>=fillY/2.0F?1:0;
		domain.setVolumes(volumes,materials);
		runtime.bindCapabilityState(domain);
		regions.selected().setPaused(true);
		sendSnapshot(level);
		return true;
	}

	public CaptureResult captureNearbyWater(ServerPlayer player, boolean takeOver) {
		ServerLevel level = player.level();
		BlockPos newOrigin = player.blockPosition().offset(-BLOCK_SIZE_X / 2, -2, -BLOCK_SIZE_Z / 2);
		if (!admitTransientRegion(level, newOrigin, false)) return new CaptureResult(0, -1, lastFailure);
		runtime.refreshCollisions(level);
		int wetCells = FluidRegionFactory.captureVanillaWater(level, regions.selected());
		runtime.bindCapabilityState(regions.selected().domain());
		MinecraftTakeoverAdapter.BeginResult begun = takeOver
				? takeover.begin(level, regions.selected(), FluidRegionFactory.CELLS_PER_BLOCK)
				: MinecraftTakeoverAdapter.BeginResult.EMPTY;
		regions.selected().setPaused(true);
		sendSnapshot(level);
		return new CaptureResult(wetCells, begun.removedBlocks(),
				begun.removedBlocks() < 0 ? takeover.lastFailure() : null);
	}

	private void createEmpty(ServerLevel level, BlockPos origin) {
		FluidRegion replaced = regions.selected();
		if (replaced != null && replaced.controllerManaged()) {
			throw new IllegalStateException("Refusing to replace a persistent controller region; remove it explicitly first");
		}
		RegionCatalog.AdmissionResult preview = regions.previewReplacement(
				COMPATIBILITY_REGION_CELLS, COMPATIBILITY_REGION_MARKERS);
		if (!preview.accepted()) throw new IllegalStateException("Compatibility fluid region rejected: " + preview.failure());
		FluidRegion candidate = FluidRegionFactory.createCommandRegion(
				level, origin, BLOCK_SIZE_X, BLOCK_SIZE_Y, BLOCK_SIZE_Z,
				COMPATIBILITY_REGION_MARKERS
		);
		candidate.setEntityPhysicsEnabled(defaultEntityPhysics.getAsBoolean());
		candidate.setDebugOverlays(defaultDebugOverlays.getAsBoolean());
		RegionCatalog.AdmissionResult admission = regions.replaceSelected(candidate);
		if (!admission.accepted()) throw new IllegalStateException("Compatibility fluid region rejected: " + admission.failure());
		if (replaced != null) snapshots.sendRemoval(level.getServer(), replaced);
	}


	private void sendSnapshot(ServerLevel level) {
		FluidRegion region = regions.selected();
		if (region != null && level.dimension().equals(region.dimension())) snapshots.sendToLevel(level, region);
	}
}
