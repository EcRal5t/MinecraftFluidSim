package mmm.simulation;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mmm.model.region.RegionGeometry.RegionKind;
import mmm.simulation.controller.FluidControllerRegions;
import mmm.simulation.controller.FluidRegionFootprint;
import mmm.simulation.ownership.OwnershipJournal;
import mmm.units.UnitFormat;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Explicit player selection and reversible pool transfers through the existing durable owners. */
public final class MinecraftPoolOperations {
	private static final ThreadLocal<Integer> WORLD_WRITE_DEPTH=ThreadLocal.withInitial(()->0);
	private record Preview(String dimension, BlockPos seed, FluidRegionFootprint footprint,
			List<OwnershipJournal.BlockEntry> preimage, UUID region, long expires) { }
	private final Map<UUID,Preview> previews=new HashMap<>();
	private final FluidRegionManager regions;
	private final FluidRegionOperations operations;
	private final MinecraftRegionActivation activation;
	private final MinecraftRegionAvailability availability;
	private final MinecraftPendingPhaseWorkBarrier phaseWork;
	private final MinecraftRegionLifecycle lifecycle;
	private final FluidSnapshotPublisher snapshots;
	private final WorldStartupCoordinator.Result startup;

	MinecraftPoolOperations(FluidRegionManager regions, FluidRegionOperations operations,
			MinecraftRegionActivation activation, MinecraftRegionAvailability availability,
			MinecraftPendingPhaseWorkBarrier phaseWork, MinecraftRegionLifecycle lifecycle,
			FluidSnapshotPublisher snapshots, WorldStartupCoordinator.Result startup) {
		this.regions=regions;this.operations=operations;this.activation=activation;this.availability=availability;
		this.phaseWork=phaseWork;this.lifecycle=lifecycle;this.snapshots=snapshots;this.startup=startup;
	}

	/** Only durable ownership replay may write water into a currently owned pool slot. */
	public static void withWorldWrite(Runnable action) {
		int previous=WORLD_WRITE_DEPTH.get();WORLD_WRITE_DEPTH.set(previous+1);
		try {action.run();} finally {if(previous==0)WORLD_WRITE_DEPTH.remove();else WORLD_WRITE_DEPTH.set(previous);}
	}

	public boolean allowsBlockChange(ServerLevel level, BlockPos position, BlockState after) {
		if(WORLD_WRITE_DEPTH.get()>0 || !after.is(Blocks.WATER)) return true;
		for(var region:regions.regions()) if(isPool(region) && region.dimension().equals(level.dimension())
				&& region.footprint().containsInterior(position)) return false;
		return true;
	}

	/** null means this hit belongs to another probe function. Ordinary use previews; sneaking confirms. */
	public InteractionResult probe(ServerPlayer player, BlockPos hit) {
		var level=player.level();
		FluidRegion pool=null;
		for(var region:regions.regions()) if(isPool(region) && region.dimension().equals(level.dimension())
				&& (region.footprint().interiorBlocks().contains(hit.asLong())
				|| region.footprint().boundaryBlocks().contains(hit.asLong()))) {
			if(pool!=null) return say(player,"此处邻接多个池体，请对准其中一池的池底",false);
			pool=region;
		}
		if(pool==null && (!level.hasChunkAt(hit) || !level.getBlockState(hit).is(Blocks.WATER))) return null;
		if(!player.createCommandSourceStack().permissions().hasPermission(Permissions.COMMANDS_ADMIN))
			return say(player,"接管和归还池水需要管理员权限",false);
		if(!startup.authoritativeWritesReady()) return say(player,"世界存档尚未准备好，池水保持原样",false);
		long tick=Integer.toUnsignedLong(level.getServer().getTickCount());
		previews.values().removeIf(value->value.expires()<tick);
		try {
			if(player.isShiftKeyDown()) {
				var preview=previews.remove(player.getUUID());
				if(preview==null || !preview.dimension().equals(level.dimension().identifier().toString())
						|| (pool==null ? preview.region()!=null || !preview.footprint().interiorBlocks().contains(hit.asLong())
						: !pool.id().equals(preview.region())))
					return say(player,"请先正常右击预览，再潜行右击同一池确认",false);
				if(pool!=null) return returnWater(player,pool);
				var current=FluidControllerRegions.scanPool(level,preview.seed(),maximumBlocks());
				if(!current.succeeded() || !current.footprint().equals(preview.footprint())
						|| !operations.scanOwnershipPreimage(level,current.footprint()).equals(preview.preimage()))
					return say(player,"预览后池水或池壁发生变化，请重新预览",false);
				var result=activation.activatePool(player,current.footprint());
				return say(player,result.succeeded()?"池水已接管，每个原版水源对应1 m³水；用容器装取，对池底使用探针可预览归还。":result.failure(),result.succeeded());
			}
			if(pool!=null) {
				regions.select(pool.id());
				if(!pool.solverLoaded()) return say(player,"池体正在等待区块和存档加载，请稍后重试",false);
				int blocks=operations.previewPoolWater(pool);
				previews.put(player.getUUID(),new Preview(level.dimension().identifier().toString(),hit.immutable(),pool.footprint(),List.of(),pool.id(),tick+200));
				return say(player,"当前可归还 "+blocks+" 个完整水块；现有液体 "+UnitFormat.volume(pool.liquidVolume() * Math.pow(pool.solverConfig().cellSize(), 3))
						+"。温度、混合物、零头和仍在流动的水会保留。10秒内潜行右击确认；确认按当时库存执行，随后暂停池体供装取。",true);
			}
			var scan=FluidControllerRegions.scanPool(level,hit,maximumBlocks());
			if(!scan.succeeded()) return say(player,"无法接管：需要已加载、池底池壁完整、水平水面的静水小池。"+scan.failure(),false);
			var admission=regions.previewAdd(Math.multiplyExact(scan.footprint().denseBlockCapacity(),FluidRegionFactory.CELL_VOLUMES_PER_BLOCK));
			if(!admission.accepted()) return say(player,admission.failure(),false);
			var water=operations.scanOwnershipPreimage(level,scan.footprint());
			previews.put(player.getUUID(),new Preview(level.dimension().identifier().toString(),hit.immutable(),scan.footprint(),water,null,tick+200));
			return say(player,"将接管 "+water.size()+" 个水源，共 "+water.size()+" m³水。池壁破损时暂停；10秒内潜行右击同一池确认。",true);
		} catch(IOException | RuntimeException failure) { return say(player,"池水操作未完成："+failure.getMessage(),false); }
	}

	private InteractionResult returnWater(ServerPlayer player,FluidRegion pool) throws IOException {
		if(!availability.ensureForInventoryDecrease(player.level(),pool) || availability.hasPending(pool.id())
				|| !phaseWork.settle(player.level(),pool)) return say(player,"池体暂不可用，请先恢复池壁、区块或未完成事务",false);
		pool.setPaused(true);
		var result=operations.returnPoolWater(player.level().getServer(),pool);
		if(result.failure()!=null) return say(player,result.failure(),false);
		var removal=lifecycle.removeEmptyPool(player,pool);
		if(!removal.committed()) snapshots.sendToLevel(player.level(),pool);
		return say(player,"已归还 "+result.restoredBlocks()+" 个原版水块。"+(removal.committed()?"全部内容已交接，池体接管已结束。":"其余内容仍保存在暂停的池体中，可用容器取走后再次确认归还。"),true);
	}
	private int maximumBlocks() {return (int)Math.max(1,Math.min(Integer.MAX_VALUE,regions.limits().maximumCellsPerRegion()/FluidRegionFactory.CELL_VOLUMES_PER_BLOCK));}
	private static boolean isPool(FluidRegion region) {return region.footprint()!=null && region.footprint().kind()==RegionKind.POOL;}
	private static InteractionResult say(ServerPlayer player,String text,boolean success) {
		player.sendSystemMessage(Component.literal(text));return success?InteractionResult.SUCCESS_SERVER:InteractionResult.FAIL;
	}
}
